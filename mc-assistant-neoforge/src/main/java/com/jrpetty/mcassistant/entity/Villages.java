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

    public record Village(UUID id, BlockPos centre) {}

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
        new Slot(AssistantEntity.StationTask.HAUL, 1, 11),
        new Slot(AssistantEntity.StationTask.STORE, 1, 12),
        new Slot(AssistantEntity.StationTask.RANCH, 1, 14),
        new Slot(AssistantEntity.StationTask.GUARD, 1, 16),
        new Slot(AssistantEntity.StationTask.FISH, 1, 18));

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
            double d = v.centre().distSqr(pos);
            if (d > (double) VILLAGE_RANGE * VILLAGE_RANGE || d >= bestDist) continue;
            bestDist = d;
            best = v;
        }
        return best;
    }

    public static Village found(BlockPos centre) {
        Village v = new Village(UUID.randomUUID(), centre.immutable());
        ALL.put(v.id(), v);
        return v;
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

    public enum Stage {
        CAMP("a camp"), HAMLET("a hamlet"), VILLAGE("a village"), TOWN("a town");
        public final String label;
        Stage(String label) { this.label = label; }
    }

    /** A thing the village still wants, and the kind of work that gets it. */
    public record Need(String what, Task task, int amount) {}

    public enum Task { FOOD, LOGS, STONE, IRON, BUILD, HANDS, NONE }

    private static final Map<UUID, Stage> STAGE = new ConcurrentHashMap<>();

    public static Stage stage(UUID villageId) {
        return STAGE.getOrDefault(villageId, Stage.CAMP);
    }

    /**
     * What the settlement still needs before it is the next thing up. Returns
     * null when this stage is complete — at which point the village advances
     * and a longer list appears.
     */
    @Nullable
    public static Need nextNeed(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        Village v = get(villageId);
        if (v == null) return null;
        int folk = headcount(villageId);
        Stage at = stage(villageId);

        List<Need> wants = new ArrayList<>();
        switch (at) {
            case CAMP -> {
                // A camp becomes a hamlet when it can feed itself and has
                // somewhere to put what it grows.
                if (built(villageId, "storage") < 1) wants.add(new Need("somewhere to store things", Task.BUILD, 1));
                need(wants, level, v, "food in the stores", Task.FOOD, 64);
                need(wants, level, v, "timber", Task.LOGS, 64);
            }
            case HAMLET -> {
                // A hamlet becomes a village when everybody is under a roof
                // and the forge is lit.
                if (built(villageId, "house") < Math.max(1, folk / 3)) wants.add(new Need("a roof for everyone", Task.BUILD, 1));
                need(wants, level, v, "stone", Task.STONE, 128);
                if (built(villageId, "smeltery") < 1) wants.add(new Need("a smeltery", Task.BUILD, 1));
                need(wants, level, v, "food in the stores", Task.FOOD, 128);
            }
            case VILLAGE -> {
                // A village becomes a town on metal and hands.
                need(wants, level, v, "iron", Task.IRON, 32);
                if (built(villageId, "workshop") < 1) wants.add(new Need("a workshop", Task.BUILD, 1));
                if (folk < VILLAGE_SIZE) wants.add(new Need("more hands", Task.HANDS, VILLAGE_SIZE - folk));
                need(wants, level, v, "food in the stores", Task.FOOD, 256);
            }
            case TOWN -> {
                // A town is never finished; it simply keeps itself.
                need(wants, level, v, "food in the stores", Task.FOOD, 256);
                need(wants, level, v, "timber", Task.LOGS, 128);
                need(wants, level, v, "iron", Task.IRON, 64);
            }
        }
        if (wants.isEmpty()) {
            advance(villageId, at);
            return null;
        }
        return wants.get(0);
    }

    private static void advance(UUID villageId, Stage from) {
        Stage next = switch (from) {
            case CAMP -> Stage.HAMLET;
            case HAMLET -> Stage.VILLAGE;
            case VILLAGE -> Stage.TOWN;
            case TOWN -> Stage.TOWN;
        };
        if (next == from) return;
        STAGE.put(villageId, next);
        AssistantEntity boss = leader(villageId);
        if (boss != null) {
            boss.say("That's us " + next.label + " now. Good work, all of you.");
        }
    }

    /** Add a stores requirement only if the stores actually fall short. */
    private static void need(List<Need> wants, net.minecraft.server.level.ServerLevel level,
                             Village v, String what, Task task, int amount) {
        int have = stock(level, v.centre(), task);
        if (have < amount) wants.add(new Need(what, task, amount - have));
    }

    /** What the settlement holds, counted from the chests around its heart.
     *  Cached briefly: this is asked by every folk with a spare moment. */
    private static final Map<UUID, long[]> STOCK_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> STOCK = new ConcurrentHashMap<>();

    public static int stock(net.minecraft.server.level.ServerLevel level, BlockPos centre, Task task) {
        int idx = task.ordinal();
        UUID key = UUID.nameUUIDFromBytes(("v" + centre.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long[] when = STOCK_TICK.computeIfAbsent(key, k -> new long[Task.values().length]);
        int[] cache = STOCK.computeIfAbsent(key, k -> new int[Task.values().length]);
        long now = level.getGameTime();
        if (now - when[idx] < 200L) return cache[idx];
        when[idx] = now;
        int total = 0;
        for (com.jrpetty.mcassistant.entity.ZoneChests.Found f
                : com.jrpetty.mcassistant.entity.ZoneChests.around(level, centre, 32, 6)) {
            if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
            net.minecraft.world.Container c = f.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack st = c.getItem(i);
                if (st.isEmpty()) continue;
                if (matches(task, st)) total += st.getCount();
            }
        }
        cache[idx] = total;
        return total;
    }

    private static boolean matches(Task task, net.minecraft.world.item.ItemStack st) {
        return switch (task) {
            case FOOD -> st.get(net.minecraft.core.component.DataComponents.FOOD) != null;
            case LOGS -> st.is(net.minecraft.tags.ItemTags.LOGS) || st.is(net.minecraft.tags.ItemTags.PLANKS);
            case STONE -> st.is(net.minecraft.world.item.Items.COBBLESTONE)
                || st.is(net.minecraft.world.item.Items.STONE)
                || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE);
            case IRON -> st.is(net.minecraft.world.item.Items.IRON_INGOT)
                || st.is(net.minecraft.world.item.Items.RAW_IRON)
                || st.is(net.minecraft.world.item.Items.IRON_ORE)
                || st.is(net.minecraft.world.item.Items.DEEPSLATE_IRON_ORE);
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
     * now. Storage first — nowhere to put anything is the first thing that
     * hurts — then a roof per few folk, then the workshop and the smeltery
     * that the trades actually use.
     */
    @Nullable
    public static String nextProject(UUID villageId) {
        int folk = headcount(villageId);
        if (folk == 0) return null;
        if (built(villageId, "storage") < 1) return "storage";
        if (built(villageId, "house") < Math.max(1, folk / 3)) return "house";
        boolean smelter = false;
        for (AssistantEntity a : folkOf(villageId)) {
            if (a.stationTask() == AssistantEntity.StationTask.SMELT) { smelter = true; break; }
        }
        if (smelter && built(villageId, "smeltery") < 1) return "smeltery";
        if (folk >= 4 && built(villageId, "workshop") < 1) return "workshop";
        if (folk >= 8 && built(villageId, "watchtower") < 1) return "watchtower";
        return null;
    }
}
