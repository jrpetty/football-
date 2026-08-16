package com.jrpetty.mcassistant.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The crew's request board. A specialist that cannot supply itself says so
 * here, and anyone carrying what it asked for takes it over on their next run
 * — so "the smelter is out of coal" becomes work someone else picks up rather
 * than a message the player has to read and act on.
 *
 * <p>Supply chains ({@link Supply}) route the three obvious flows without
 * anyone asking. This is the other half: the things nobody predicted, said out
 * loud. A request expires on its own, so a need that has since been met by hand
 * does not sit on the board forever.
 */
public final class Requests {

    private Requests() {}

    /** How long a posted need stands before it lapses (ticks). */
    private static final int LIFETIME = 6000;   // five minutes

    /** What a specialist can ask for. Keyed off the same wording its checklist
     *  uses, so the board and the screens never disagree about what is short. */
    public enum Need {
        FOOD("rations", s -> s.get(DataComponents.FOOD) != null),
        REDSTONE("redstone", s -> s.is(Items.REDSTONE)),
        TORCHES("torches", s -> s.is(Items.TORCH)),
        AXE("an axe", s -> named(s, "_axe")),
        PICKAXE("a pickaxe", s -> named(s, "_pickaxe")),
        SWORD("a sword", s -> named(s, "_sword")),
        SHEARS("shears", s -> s.is(Items.SHEARS)),
        ROD("a fishing rod", s -> s.is(Items.FISHING_ROD)),
        FUEL("fuel", s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)),
        ORE("raw ore", AssistantEntity.SMELTABLE_ORE),
        FEED("breeding food", AssistantEntity.BREEDING_FOOD),
        SEEDS("seed stock", s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS)
            || s.is(Items.CARROT) || s.is(Items.POTATO)),
        SAPLINGS("saplings", s -> s.is(ItemTags.SAPLINGS)),
        WAGE("a wage", s -> s.is(Items.IRON_INGOT) || s.is(Items.GOLD_INGOT) || s.is(Items.DIAMOND));

        public final String label;
        public final Predicate<ItemStack> matches;
        Need(String label, Predicate<ItemStack> matches) { this.label = label; this.matches = matches; }

        private static boolean named(ItemStack s, String suffix) {
            return !s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(s.getItem()).getPath().endsWith(suffix);
        }

        /**
         * Read a need out of the wording a checklist produced. The checklist is
         * written for a player to read, so this maps its phrases rather than
         * making every job carry a parallel machine-readable list that could
         * drift out of step with the words on screen.
         */
        @Nullable
        public static Need fromGap(String gap) {
            String g = gap.toLowerCase();
            if (g.startsWith("food")) return FOOD;
            if (g.startsWith("redstone")) return REDSTONE;
            if (g.contains("torch")) return TORCHES;
            if (g.contains("axe") && !g.contains("pickaxe")) return AXE;
            if (g.contains("pickaxe")) return PICKAXE;
            if (g.contains("sword")) return SWORD;
            if (g.contains("shears")) return SHEARS;
            if (g.contains("fishing rod")) return ROD;
            if (g.startsWith("fuel")) return FUEL;
            if (g.contains("raw ore")) return ORE;
            if (g.contains("breeding food")) return FEED;
            if (g.contains("wage")) return WAGE;
            return null;   // chests, water, animals — not something anyone can carry over
        }
    }

    public record Request(UUID asker, Need need, int postedAt) {}

    private static final Map<UUID, List<Request>> BOARD = new ConcurrentHashMap<>();

    /** Post a need, or refresh one already standing. */
    public static void post(UUID owner, UUID asker, Need need, int now) {
        if (owner == null || asker == null || need == null) return;
        List<Request> open = BOARD.computeIfAbsent(owner, k -> new ArrayList<>());
        synchronized (open) {
            open.removeIf(r -> r.asker().equals(asker) && r.need() == need);
            open.add(new Request(asker, need, now));
            // A crew cannot meaningfully be short of more than a handful of
            // things at once; past that the oldest entries are stale anyway.
            while (open.size() > 24) open.remove(0);
        }
    }

    /** Clear everything this bot was asking for — it has what it needed. */
    public static void clear(UUID owner, UUID asker) {
        List<Request> open = owner == null ? null : BOARD.get(owner);
        if (open == null) return;
        synchronized (open) {
            open.removeIf(r -> r.asker().equals(asker));
        }
    }

    /** Everything this crew is currently short of, freshest first. */
    public static List<Request> open(UUID owner, int now) {
        List<Request> open = owner == null ? null : BOARD.get(owner);
        if (open == null) return List.of();
        synchronized (open) {
            open.removeIf(r -> now - r.postedAt() > LIFETIME);
            return new ArrayList<>(open);
        }
    }

    /** Is anyone asking for something this carrier is holding? Returns the
     *  asker, so the load can be taken to their chest. */
    @Nullable
    public static Request matching(UUID owner, AssistantEntity carrier, int now) {
        for (Request r : open(owner, now)) {
            if (r.asker().equals(carrier.getUUID())) continue;
            if (carrier.countCarried(r.need().matches) > 0) return r;
        }
        return null;
    }
}
