package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * [pa36b] What a Player AI Assistant may take out of its player's chests on its own account: the engine's idle
 * top-ups (food, kit makings, armour, torches' coal) when nobody asked for anything. Ordered work is unchanged (its
 * player's "use your chests" setting and the chest rules in PaChests and PaContainers still apply).
 *
 * <p>On its own account it takes only from a chest its player allows (the Storage view's "Take" setting): by default
 * its own chests and the drop-off, never a storage chest. Never below a keep-stocked amount, never the valuables (iron
 * and up) unless that chest allows "anything". Each borrowing is said once, in the Talk log.
 */
public final class PaIdleTake {

    private PaIdleTake() {}

    /** Is it about its own business (no order of its player's under way)? */
    public static boolean idle(PlayerAssistantEntity a) {
        PaPlan.Step cur = a.plan.current;
        return cur == null || cur.auto;
    }

    /** Iron and up: what a top-up never turns into kit unless the chest allows anything. */
    public static boolean valuable(ItemStack s) {
        return s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON) || s.is(Items.IRON_BLOCK) || s.is(Items.RAW_IRON_BLOCK)
            || s.is(Items.GOLD_INGOT) || s.is(Items.RAW_GOLD) || s.is(Items.GOLD_BLOCK) || s.is(Items.GOLD_NUGGET)
            || s.is(Items.DIAMOND) || s.is(Items.DIAMOND_BLOCK) || s.is(Items.EMERALD) || s.is(Items.EMERALD_BLOCK)
            || s.is(Items.NETHERITE_INGOT) || s.is(Items.NETHERITE_SCRAP) || s.is(Items.ANCIENT_DEBRIS) || s.is(Items.NETHERITE_BLOCK)
            || s.is(Items.LAPIS_BLOCK) || s.is(Items.AMETHYST_SHARD) || s.is(Items.NETHER_STAR) || s.is(Items.TOTEM_OF_UNDYING)
            || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ELYTRA)
            || isGear(s, "iron_", "golden_", "diamond_", "netherite_", "chainmail_");
    }

    private static boolean isGear(ItemStack s, String... prefixes) {
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (!(s.isDamageableItem() || path.endsWith("_horse_armor"))) return false;
        for (String p : prefixes) if (path.startsWith(p)) return true;
        return false;
    }

    /** The idle-take level for a chest: 0 nothing, 1 basics, 2 anything (unknown chests: nothing). */
    public static int level(PlayerAssistantEntity a, BlockPos chest) {
        UUID owner = a.getOwnerId();
        if (owner == null) return 0;
        PaStore.Known k = PaStore.of(owner).at(chest, a.level().dimension().location().toString());
        return k == null ? 0 : k.takeLevel();
    }

    /** May it take anything at all from here now? (Ordered work: yes, as before.) */
    public static boolean any(PlayerAssistantEntity a, BlockPos chest) {
        return !idle(a) || level(a, chest) > 0;
    }

    /** How many of this stack it may take on its own account: 0 when not allowed, never below a keep-stocked amount. */
    public static int allowance(PlayerAssistantEntity a, BlockPos chest, Container c, ItemStack s) {
        if (!idle(a)) return s.getCount();
        int lvl = level(a, chest);
        if (lvl <= 0) return 0;
        if (lvl < 2 && valuable(s)) return 0;
        UUID owner = a.getOwnerId();
        String dim = a.level().dimension().location().toString();
        int spare = s.getCount();
        for (PaStore.Stock st : PaStore.of(owner).stock) {
            if (st.furnaces || st.pos != chest.asLong() || !st.dim.equals(dim)) continue;
            Predicate<ItemStack> p = PaLogiItems.of(st.item);
            if (!p.test(s)) continue;
            int have = PaContainers.count(c, p);
            spare = Math.min(spare, Math.max(0, have - st.n));
        }
        return spare;
    }

    /** What may come out of this chest for its own sourcing (a fill, a top-up) without going under a keep-stocked amount there. */
    public static int aboveStock(PlayerAssistantEntity a, BlockPos chest, Container c, Predicate<ItemStack> what) {
        UUID owner = a.getOwnerId();
        if (owner == null) return Integer.MAX_VALUE;
        String dim = a.level().dimension().location().toString();
        int spare = Integer.MAX_VALUE;
        for (PaStore.Stock st : PaStore.of(owner).stock) {
            if (st.furnaces || st.pos != chest.asLong() || !st.dim.equals(dim)) continue;
            Predicate<ItemStack> rule = PaLogiItems.of(st.item);
            if (PaContainers.count(c, what.and(rule)) == 0) continue;
            spare = Math.min(spare, Math.max(0, PaContainers.count(c, rule) - st.n));
        }
        return spare;
    }

    // ---- borrowing, said once per trip

    private record Key(BlockPos chest, String item) {}

    private static final Map<PlayerAssistantEntity, Map<Key, Integer>> BORROWED = new WeakHashMap<>();

    /** It took these on its own account: noted, said at the end of its tick. */
    public static void took(PlayerAssistantEntity a, BlockPos chest, ItemStack what, int n) {
        if (n <= 0 || !idle(a)) return;
        String id = BuiltInRegistries.ITEM.getKey(what.getItem()).toString();
        BORROWED.computeIfAbsent(a, k -> new LinkedHashMap<>()).merge(new Key(chest.immutable(), id), n, Integer::sum);
    }

    /** End of its tick: one line per chest it borrowed from. */
    public static void flush(PlayerAssistantEntity a) {
        Map<Key, Integer> m = BORROWED.remove(a);
        if (m == null || m.isEmpty() || a.getOwnerId() == null) return;
        Map<BlockPos, Map<String, Integer>> byChest = new LinkedHashMap<>();
        m.forEach((k, n) -> byChest.computeIfAbsent(k.chest(), x -> new LinkedHashMap<>()).merge(k.item(), n, Integer::sum));
        for (Map.Entry<BlockPos, Map<String, Integer>> e : byChest.entrySet()) {
            StringBuilder what = new StringBuilder();
            String why = null;
            int i = 0, total = 0;
            for (Map.Entry<String, Integer> it : e.getValue().entrySet()) {
                if (i > 0) what.append(i == e.getValue().size() - 1 ? " and " : ", ");
                String item = "item:" + it.getKey();
                what.append(it.getValue()).append(' ').append(PaLogiItems.name(item, it.getValue()));
                total += it.getValue();
                if (why == null) why = why(new ItemStack(BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(it.getKey()))));
                i++;
            }
            String chest = PaLogistics.called(a, e.getKey(), null);
            String line = PaLogiTalk.line(a, "logi.borrowed", "what", what.toString(), "n", String.valueOf(total), "chest", chest, "why", why == null ? "" : why);
            PaTalk.comment(a, 0, line, true);                                       // always said: it is your stuff
        }
    }

    /** What the borrowing was for, in a few words ("for tools", "to eat"). */
    static String why(ItemStack s) {
        if (s.get(net.minecraft.core.component.DataComponents.FOOD) != null) return "to eat";
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(Items.STICK) || s.is(Items.COBBLESTONE)) return "for tools";
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) return "for torches";
        if (s.is(Items.TORCH)) return "to see by";
        if (s.is(Items.ARROW)) return "for the bow";
        if (s.getItem() instanceof net.minecraft.world.item.ArmorItem) return "to wear";
        if (s.isDamageableItem()) return "to work with";
        return "for my kit";
    }
}
