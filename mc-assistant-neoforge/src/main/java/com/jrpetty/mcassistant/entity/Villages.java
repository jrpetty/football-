package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village register: which settlement a folk belongs to, where its heart is,
 * and — the part that makes a village a village rather than ten strangers —
 * which trade it is currently short of.
 *
 * <p>A village's id doubles as the "owner" every folk in it shares. That is not
 * a trick for its own sake: every piece of crew machinery in this mod is keyed
 * on the owner — the claim book so two hands never walk to the same block, the
 * field-banding, the shared finds, the worn paths, the shift handover. Giving a
 * village one id hands all of it to the folk for free, and keeps one village's
 * business out of the next one's.
 *
 * <p>Nothing here is saved. It does not need to be: every folk persists its own
 * village id and centre, and the first one to load re-registers the settlement.
 */
public final class Villages {

    private Villages() {}

    public record Village(UUID id, BlockPos centre,
                          net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {}

    private static final Map<UUID, Village> ALL = new ConcurrentHashMap<>();

    /** How far apart two settlements have to be to be two settlements. */
    public static final int VILLAGE_RANGE = 96;

    /** A full village, and the trades it wants in it. Four farmers feed the
     *  rest, three miners keep the stone and metal coming, two woodcutters
     *  supply every build, and one smelter turns the ore into tools. */
    public static final int VILLAGE_SIZE = 10;

    /**
     * What a settlement wants, and when it starts wanting it. The first ten
     * are the village proper — four farmers to feed it, three miners for stone
     * and metal, two woodcutters to supply every build, one smelter to turn
     * ore into tools. Past ten a settlement can afford specialists: someone to
     * carry things between the trades, someone to keep the stores, then a pen,
     * a watch, and a boat.
     *
     * <p>{@code from} is the headcount at which the trade becomes worth having
     * at all — a village of four has no business keeping a guard.
     */
    private record Slot(AssistantEntity.StationTask trade, int weight, int from) {}

    private static final List<Slot> SLOTS = List.of(
        new Slot(AssistantEntity.StationTask.FARM, 4, 1),
        new Slot(AssistantEntity.StationTask.MINE, 3, 2),
        new Slot(AssistantEntity.StationTask.WOOD, 2, 3),
        new Slot(AssistantEntity.StationTask.SMELT, 1, 6),
        // The watch comes BEFORE the carrier and the storekeeper. Settlements
        // are founded at eight to twelve and there is no way for one to grow,
        // so a guard at sixteen was a guard no village was ever going to have
        // — which made the armour, the priority and the whole watch a thing
        // that only existed on paper. The ten-folk shape is untouched by this:
        // four farmers, three miners, two woodcutters and a smelter is exactly
        // what ten still comes out as.
        new Slot(AssistantEntity.StationTask.GUARD, 1, 11),
        new Slot(AssistantEntity.StationTask.HAUL, 1, 12),
        new Slot(AssistantEntity.StationTask.STORE, 1, 13),
        new Slot(AssistantEntity.StationTask.RANCH, 1, 14),
        new Slot(AssistantEntity.StationTask.FISH, 1, 16));

    public static void register(Village village) {
        ALL.put(village.id(), village);
    }

    @Nullable
    public static Village get(@Nullable UUID id) {
        return id == null ? null : ALL.get(id);
    }

    /** The settlement whose heart is nearest this spot, if there is one close
     *  enough to walk to. */
    @Nullable
    public static Village nearest(Level level, BlockPos pos) {
        Village best = null;
        double bestDist = Double.MAX_VALUE;
        for (Village v : ALL.values()) {
            if (!v.dim().equals(level.dimension())) continue;   // not our world
            double d = v.centre().distSqr(pos);
            if (d > (double) VILLAGE_RANGE * VILLAGE_RANGE || d >= bestDist) continue;
            bestDist = d;
            best = v;
        }
        return best;
    }

    public static Village found(Level level, BlockPos centre) {
        Village v = new Village(UUID.randomUUID(), centre.immutable(), level.dimension());
        ALL.put(v.id(), v);
        return v;
    }

    /** The settlement is gone. Drops its register entry and everything hung
     *  off it, so nothing keeps pointing at a village nobody lives in. */
    public static void forget(UUID villageId) {
        ALL.remove(villageId);
        AGE.remove(villageId);
        BUILT.remove(villageId);
        LAST_PROJECT.remove(villageId);
    }

    /** Restore a settlement from what one of its folk remembers. Village
     *  state lives in memory only, so the folk carry it: the first one to
     *  load puts its village — and how far it had got — back on the map. */
    public static void restore(Level level, UUID id, BlockPos centre, Age age, List<String> built) {
        ALL.putIfAbsent(id, new Village(id, centre, level.dimension()));
        AGE.putIfAbsent(id, age);
        BUILT.computeIfAbsent(id, k -> new ArrayList<>(built));
    }

    public static Age ageOf(UUID id) { return age(id); }

    public static List<String> builtList(UUID id) {
        return new ArrayList<>(BUILT.getOrDefault(id, List.of()));
    }

    /** Everyone alive who belongs to this settlement. */
    public static List<AssistantEntity> folkOf(@Nullable UUID villageId) {
        if (villageId == null) return List.of();
        List<AssistantEntity> out = new ArrayList<>();
        for (AssistantEntity a : AssistantEntity.allFor(villageId)) {
            if (a.isAlive()) out.add(a);
        }
        return out;
    }

    public static int headcount(@Nullable UUID villageId) {
        return folkOf(villageId).size();
    }

    /**
     * The trade this settlement most needs the next pair of hands to take up.
     * Whichever trade is furthest below its share of the village gets the
     * newcomer — so ten folk settle into four farmers, three miners, two
     * woodcutters and a smelter without anyone being told, and a village that
     * loses its smelter replaces it with the next one to look for work.
     */
    public static AssistantEntity.StationTask needed(@Nullable UUID villageId) {
        List<AssistantEntity> folk = folkOf(villageId);
        Map<AssistantEntity.StationTask, Integer> have =
            new EnumMap<>(AssistantEntity.StationTask.class);
        for (AssistantEntity a : folk) {
            if (a.stationTask() != AssistantEntity.StationTask.NONE) {
                have.merge(a.stationTask(), 1, Integer::sum);
            }
        }
        // Count the newcomer itself, so the very first folk in an empty village
        // works out its share against a village of one and takes the farm.
        int total = Math.max(1, folk.size());
        AssistantEntity.StationTask best = AssistantEntity.StationTask.FARM;
        double bestDeficit = -Double.MAX_VALUE;
        int bestWeight = 0;
        for (Slot slot : SLOTS) {
            if (total < slot.from()) continue;          // too small to want one yet
            double target = slot.weight() * total / (double) VILLAGE_SIZE;
            double deficit = target - have.getOrDefault(slot.trade(), 0);
            // Ties break toward the trade the village wants most of, which
            // keeps a young settlement growing food before it grows anything
            // else.
            if (deficit > bestDeficit || (deficit == bestDeficit && slot.weight() > bestWeight)) {
                bestDeficit = deficit;
                bestWeight = slot.weight();
                best = slot.trade();
            }
        }
        return best;
    }

    /**
     * A trade this settlement has NOBODY left doing and is big enough to want.
     * The one case worth asking a working hand to change trade over: a village
     * whose only smelter died has a cold forge for ever otherwise, because the
     * ten who founded the place are the ten it has.
     */
    @Nullable
    public static AssistantEntity.StationTask vacancy(@Nullable UUID villageId) {
        List<AssistantEntity> folk = folkOf(villageId);
        int total = folk.size();
        if (total <= 1) return null;                 // one pair of hands is not a shortage
        Map<AssistantEntity.StationTask, Integer> have =
            new EnumMap<>(AssistantEntity.StationTask.class);
        for (AssistantEntity a : folk) {
            if (a.stationTask() != AssistantEntity.StationTask.NONE) {
                have.merge(a.stationTask(), 1, Integer::sum);
            }
        }
        for (Slot slot : SLOTS) {
            if (total < slot.from()) continue;       // too small to want one yet
            if (have.getOrDefault(slot.trade(), 0) == 0) return slot.trade();
        }
        return null;
    }

    /**
     * Is this trade carrying more hands than the village's shape calls for?
     * The guard on re-badging: a village must never strip a trade that is
     * merely busy to staff one that is empty.
     */
    public static boolean overStaffed(@Nullable UUID villageId, AssistantEntity.StationTask trade) {
        if (trade == AssistantEntity.StationTask.NONE) return true;
        List<AssistantEntity> folk = folkOf(villageId);
        int total = folk.size();
        if (total <= 0) return false;
        int have = 0;
        for (AssistantEntity a : folk) if (a.stationTask() == trade) have++;
        for (Slot slot : SLOTS) {
            if (slot.trade() != trade) continue;
            double target = slot.weight() * total / (double) VILLAGE_SIZE;
            // One over the share, and never below one: the last farmer in a
            // village is not spare however the arithmetic reads.
            return have > Math.max(1, (int) Math.ceil(target));
        }
        return have > 0;      // a trade the shape does not ask for at all
    }

    /**
     * Who speaks for the settlement. Not an election — the longest-serving,
     * most experienced pair of hands is simply the one everybody defers to,
     * and it is recomputed from scratch whenever anybody asks, so a leader
     * that dies is replaced by the next-best that same moment. The leader
     * does no different work; it just keeps the plan and says it out loud.
     */
    @Nullable
    public static AssistantEntity leader(@Nullable UUID villageId) {
        AssistantEntity best = null;
        int bestXp = -1;
        for (AssistantEntity a : folkOf(villageId)) {
            int xp = a.lifetimeXp();
            if (xp > bestXp || (xp == bestXp && best != null && a.getId() < best.getId())) {
                bestXp = xp;
                best = a;
            }
        }
        return best;
    }

    // ======================= what the village is FOR ==========================
    // A settlement with no plan is ten people standing in a field. The plan is
    // a ladder of stages, each with plain requirements, and every hand with a
    // spare moment works on whichever requirement is not met yet. It is the
    // same mechanism that answers "why build a village?", "what should I do
    // when my own trade has nothing for me?" and "what is this place FOR?".

    /**
     * The ages of a settlement. Every one is named for the material that
     * defines it, because that is what actually changes: a village in the Wood
     * Age is felling and building in timber, a village in the Stone Age is
     * quarrying and walling itself in, and a village in the Iron Age is
     * running a forge. What they gather, what they build and what they arm
     * themselves with all follow from which age they are in.
     */
    public enum Age {
        WOOD("the Wood Age"),
        STONE("the Stone Age"),
        IRON("the Iron Age"),
        DIAMOND("the Diamond Age"),
        NETHER("the Nether Age");

        public final String label;
        Age(String label) { this.label = label; }

        Age next() {
            return this == NETHER ? NETHER : values()[ordinal() + 1];
        }
    }

    /** A thing the village still wants, and the kind of work that gets it. */
    public record Need(String what, Task task, int amount) {}

    public enum Task { FOOD, LOGS, STONE, COAL, IRON, DIAMOND, OBSIDIAN, BUILD, HANDS, NONE }

    private static final Map<UUID, Age> AGE = new ConcurrentHashMap<>();

    public static Age age(UUID villageId) {
        return AGE.getOrDefault(villageId, Age.WOOD);
    }

    /**
     * What the settlement still needs before it comes of age. Returns null
     * when this age's list is complete — at which point it advances, says so,
     * and a longer list appears.
     */
    /** The whole list, in order. Callers take the first one they can actually
     *  do something about — a building nobody has the timber for must never
     *  hide the timber-cutting behind it. */
    public static List<Need> needs(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        List<Need> all = wantsFor(level, villageId);
        if (all.isEmpty()) advance(level, villageId, age(villageId));
        return all;
    }

    @Nullable
    public static Need nextNeed(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        List<Need> all = needs(level, villageId);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<Need> wantsFor(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        Village v = get(villageId);
        int folk = headcount(villageId);
        Age at = age(villageId);

        List<Need> wants = new ArrayList<>();
        if (v == null) return wants;
        switch (at) {
            case WOOD -> {
                // Timber, a roof, and food coming in. Everything a place needs
                // before it can afford to think about stone.
                need(wants, level, v, "food in the stores", Task.FOOD, 64);
                if (built(villageId, "storage") < 1) wants.add(new Need("somewhere to store things", Task.BUILD, 1));
                need(wants, level, v, "timber", Task.LOGS, 128);
                if (built(villageId, "shelter") < 1) wants.add(new Need("a shelter", Task.BUILD, 1));
                if (built(villageId, "house") < Math.max(1, folk / 4)) wants.add(new Need("houses", Task.BUILD, 1));
            }
            case STONE -> {
                // Quarry, wall, and a fire to work by.
                need(wants, level, v, "stone", Task.STONE, 256);
                need(wants, level, v, "coal", Task.COAL, 32);
                if (built(villageId, "fortify") < 1) wants.add(new Need("a wall around the village", Task.BUILD, 1));
                if (built(villageId, "house") < Math.max(2, folk / 3)) wants.add(new Need("more houses", Task.BUILD, 1));
                if (built(villageId, "smeltery") < 1) wants.add(new Need("a smeltery", Task.BUILD, 1));
                need(wants, level, v, "food in the stores", Task.FOOD, 128);
            }
            case IRON -> {
                // Metal, and the buildings that only a village with metal can
                // afford the time to put up.
                need(wants, level, v, "iron", Task.IRON, 64);
                if (built(villageId, "workshop") < 1) wants.add(new Need("a workshop", Task.BUILD, 1));
                if (built(villageId, "watchtower") < 1) wants.add(new Need("a watchtower", Task.BUILD, 1));
                need(wants, level, v, "food in the stores", Task.FOOD, 256);
            }
            case DIAMOND -> {
                need(wants, level, v, "diamonds", Task.DIAMOND, 8);
                need(wants, level, v, "iron", Task.IRON, 128);
                if (built(villageId, "lighthouse") < 1) wants.add(new Need("a lighthouse", Task.BUILD, 1));
            }
            case NETHER -> {
                // The last thing a settlement builds for itself is a way out
                // of the world it started in.
                need(wants, level, v, "obsidian", Task.OBSIDIAN, 10);
                need(wants, level, v, "diamonds", Task.DIAMOND, 16);
                need(wants, level, v, "food in the stores", Task.FOOD, 256);
            }
        }
        return wants;
    }

    /**
     * Coming of age. This is the ONE thing a settlement says out loud — the
     * folk themselves never speak, but a village reaching its next age is a
     * thing worth being told about wherever you are.
     */
    private static void advance(net.minecraft.server.level.ServerLevel level, UUID villageId, Age from) {
        Age next = from.next();
        if (next == from) return;
        AGE.put(villageId, next);
        Village v = get(villageId);
        String where = v == null ? "" : " (" + v.centre().getX() + ", " + v.centre().getZ() + ")";
        net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.literal(
            "A village has entered " + next.label + where + ".")
            .withStyle(net.minecraft.ChatFormatting.GOLD);
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            p.sendSystemMessage(line);
        }
    }

    /** Add a stores requirement only if the stores actually fall short. */
    private static void need(List<Need> wants, net.minecraft.server.level.ServerLevel level,
                             Village v, String what, Task task, int amount) {
        int have = stock(level, v.centre(), task, storesRadius(v.id()));
        if (have < amount) wants.add(new Need(what, task, amount - have));
    }

    /** What the settlement holds, counted from the chests around its heart —
     *  which is what a "storage area" actually means here: the stores are
     *  wherever the village keeps them, near the middle, and every count of
     *  what the place owns reads from exactly that. Cached briefly, because
     *  every hand with a spare moment asks. */
    private static final Map<UUID, long[]> STOCK_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> STOCK = new ConcurrentHashMap<>();

    public static int stock(net.minecraft.server.level.ServerLevel level, BlockPos centre, Task task) {
        return stock(level, centre, task, 32);
    }

    /**
     * What the settlement holds, counted from the chests within {@code radius}
     * of its heart.
     *
     * <p>THE RADIUS HAS TO GROW WITH THE VILLAGE, and this is the rung that
     * quietly broke everything above it when it did not. The stores were read
     * from thirty-two blocks around the middle while plots are staked sixty
     * and more out — and further the bigger the place gets — so the harvest
     * went into a field chest the village's own plan could not see. The plan
     * therefore read "short of food" no matter how much was grown, the ages
     * never advanced because their thresholds were never met, and every hand
     * spent its life lending itself out to gather more of what the settlement
     * already had piles of.
     *
     * <p>One sweep fills every counter, rather than one sweep per thing asked
     * about: a grown village's ring is a couple of hundred chunks, and asking
     * ten separate questions about it ten times a minute is how you make a
     * settlement cost more than the rest of the world put together.
     */
    public static int stock(net.minecraft.server.level.ServerLevel level, BlockPos centre,
                            Task task, int radius) {
        UUID key = UUID.nameUUIDFromBytes(("v" + centre.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long[] when = STOCK_TICK.computeIfAbsent(key, k -> new long[1]);
        int[] cache = STOCK.computeIfAbsent(key, k -> new int[Task.values().length]);
        long now = level.getGameTime();
        if (now - when[0] >= 200L || when[0] == 0L) {
            when[0] = now;
            java.util.Arrays.fill(cache, 0);
            Task[] all = Task.values();
            for (com.jrpetty.mcassistant.entity.ZoneChests.Found f
                    : com.jrpetty.mcassistant.entity.ZoneChests.around(level, centre, radius, 8)) {
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack st = c.getItem(i);
                    if (st.isEmpty()) continue;
                    for (Task t : all) {
                        if (matches(t, st)) cache[t.ordinal()] += st.getCount();
                    }
                }
            }
        }
        return cache[task.ordinal()];
    }

    /**
     * How far out the stores reach, which is however far the plots do. Kept in
     * step with the fan-out a growing village searches on, so a village never
     * loses sight of its own output by getting bigger.
     */
    public static int storesRadius(@Nullable UUID villageId) {
        int folk = headcount(villageId);
        return Math.min(112, 40 + Math.max(0, folk - 8) * 5);
    }

    private static boolean matches(Task task, net.minecraft.world.item.ItemStack st) {
        return switch (task) {
            case FOOD -> st.get(net.minecraft.core.component.DataComponents.FOOD) != null;
            case LOGS -> st.is(net.minecraft.tags.ItemTags.LOGS) || st.is(net.minecraft.tags.ItemTags.PLANKS);
            case STONE -> st.is(net.minecraft.world.item.Items.COBBLESTONE)
                || st.is(net.minecraft.world.item.Items.STONE)
                || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE);
            case COAL -> st.is(net.minecraft.world.item.Items.COAL)
                || st.is(net.minecraft.world.item.Items.CHARCOAL);
            case IRON -> st.is(net.minecraft.world.item.Items.IRON_INGOT)
                || st.is(net.minecraft.world.item.Items.RAW_IRON)
                || st.is(net.minecraft.world.item.Items.IRON_ORE)
                || st.is(net.minecraft.world.item.Items.DEEPSLATE_IRON_ORE);
            case DIAMOND -> st.is(net.minecraft.world.item.Items.DIAMOND);
            case OBSIDIAN -> st.is(net.minecraft.world.item.Items.OBSIDIAN);
            default -> false;
        };
    }

    // ---- the settlement's own building work, kept to one project at a time ----

    private static final Map<UUID, Long> LAST_PROJECT = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> BUILT = new ConcurrentHashMap<>();

    /** Long enough that a village grows over days rather than minutes. A
     *  settlement that threw up every building the hour it was founded would
     *  not feel like one. */
    private static final long PROJECT_GAP = 9600L;   // eight minutes

    public static boolean projectDue(UUID villageId, long gameTime) {
        return gameTime - LAST_PROJECT.getOrDefault(villageId, -PROJECT_GAP) >= PROJECT_GAP;
    }

    /** Somebody has set off to build something. Paces the next project;
     *  says nothing about whether this one succeeds. */
    public static void noteAttempt(UUID villageId, long gameTime) {
        LAST_PROJECT.put(villageId, gameTime);
    }

    /** Something actually went up. Only finished buildings count toward the
     *  village's ages — a village that could not find the timber has not got
     *  a storehouse, however many times it tried. */
    public static void noteProject(UUID villageId, String structure, long gameTime) {
        LAST_PROJECT.put(villageId, gameTime);
        BUILT.computeIfAbsent(villageId, k -> new ArrayList<>()).add(structure);
    }

    private static int built(UUID villageId, String structure) {
        int n = 0;
        for (String s : BUILT.getOrDefault(villageId, List.of())) {
            if (s.equals(structure)) n++;
        }
        return n;
    }

    /**
     * What the settlement should put up next, or null when it is content for
     * now. Buildings follow the age: a village in the Wood Age puts up timber
     * — a store, a shelter, houses — and only once it is quarrying does it
     * wall itself in and light a smeltery. Parts are placed from whatever the
     * builder is carrying, so what the village gathered for its age is what
     * its buildings come out of.
     *
     * <p>Earlier ages' buildings are still wanted if they were never built: a
     * settlement does not skip its storehouse because it found iron.
     */
    @Nullable
    public static String nextProject(UUID villageId) {
        int folk = headcount(villageId);
        if (folk == 0) return null;
        Age at = age(villageId);

        if (built(villageId, "storage") < 1) return "storage";
        if (built(villageId, "shelter") < 1) return "shelter";
        if (built(villageId, "house") < Math.max(1, folk / 4)) return "house";
        if (at == Age.WOOD) return null;

        if (built(villageId, "fortify") < 1) return "fortify";        // the wall
        if (built(villageId, "house") < Math.max(2, folk / 3)) return "house";
        if (built(villageId, "smeltery") < 1) return "smeltery";
        if (at == Age.STONE) return null;

        if (built(villageId, "workshop") < 1) return "workshop";
        if (folk >= 8 && built(villageId, "watchtower") < 1) return "watchtower";
        if (at == Age.IRON) return null;

        if (built(villageId, "lighthouse") < 1) return "lighthouse";
        return null;
    }

    /** What an idle hand should be gathering for the age the village is in —
     *  the answer to "there is nothing in my own trade to do right now". */
    public static Task gatherFor(Age age) {
        return switch (age) {
            case WOOD -> Task.LOGS;
            case STONE -> Task.STONE;
            case IRON, DIAMOND, NETHER -> Task.IRON;
        };
    }
}
