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

    /** Forget every settlement. For tests, which share one JVM and would
     *  otherwise inherit each other's villages. */
    /** Tests only: put a village straight into an age. */
    public static void ageForTests(UUID id, Age age) { AGE.put(id, age); }

    // ------------------------------ the village's news ------------------------
    //
    // What everybody is talking about: who had a child, who took up with whom, what
    // went up, what age the place has come to. Folk pass it on when you ask them.

    public record News(long day, String text) {}

    private static final Map<UUID, java.util.Deque<News>> NEWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> AGED_ON = new ConcurrentHashMap<>();

    public static void tell(UUID villageId, long day, String text) {
        java.util.Deque<News> d = NEWS.computeIfAbsent(villageId, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        d.addFirst(new News(day, text));
        while (d.size() > 12) d.pollLast();
        com.jrpetty.mcassistant.village.Chronicle.record(villageId, day, text);
    }

    // ------------------------------ a village's name --------------------------

    private static final String[] NAME_HEADS = {
        "Oak", "Ash", "Elm", "Birch", "Willow", "Thorn", "Stone", "Brook", "Mill", "Hollow",
        "Fern", "Moss", "Red", "Green", "Black", "White", "High", "Low", "Fox", "Wolf",
        "Raven", "Hart", "Bramble", "Cold", "Wind", "Sun", "Mead", "Heather", "Clay", "Iron",
        "Alder", "Hazel", "Rush", "Swan", "Linden", "Rowan", "Marsh", "Dun", "Kings", "Bell",
    };
    private static final String[] NAME_TAILS = {
        "hollow", "ford", "brook", "field", "stead", "wick", "ton", "bury", "dale", "holm",
        "mere", "cross", "well", "combe", "ridge", "haven", "moor", "gate", "thorpe", "leigh",
        "worth", "by", "hurst", "den", "fold",
    };

    /** A village's name: its own, the same for ever, worked out from who it is. */
    public static String name(UUID villageId) {
        long a = villageId.getMostSignificantBits(), b = villageId.getLeastSignificantBits();
        String head = NAME_HEADS[(int) Math.floorMod(a ^ (a >>> 29), (long) NAME_HEADS.length)];
        String tail = NAME_TAILS[(int) Math.floorMod(b ^ (b >>> 31), (long) NAME_TAILS.length)];
        if (head.toLowerCase(java.util.Locale.ROOT).endsWith(tail)) tail = "ton";
        return head + tail;
    }

    public static List<News> news(UUID villageId) {
        java.util.Deque<News> d = NEWS.get(villageId);
        return d == null ? List.of() : new ArrayList<>(d);
    }

    /** The game day this village last came of age, or -100. */
    public static long agedOn(UUID villageId) {
        return AGED_ON.getOrDefault(villageId, -100L);
    }

    /** Is this spot inside a house the village built for a player? */
    public static boolean inAGuestHouse(UUID villageId, BlockPos pos) {
        for (com.jrpetty.mcassistant.village.Chronicle.Guest g : com.jrpetty.mcassistant.village.Chronicle.guests(villageId)) {
            if (!g.built) continue;
            if (Math.abs(pos.getX() - g.x) <= 6 && Math.abs(pos.getZ() - g.z) <= 6 && Math.abs(pos.getY() - g.y) <= 4) return true;
        }
        return false;
    }

    // ------------------------------ the village elder -------------------------
    //
    // The one the others look up to: chosen afresh each day from what everybody in
    // the village feels for everybody else. It speaks for the village.

    private record Elder(UUID id, String name, long day) {}

    private static final Map<UUID, Elder> ELDERS = new ConcurrentHashMap<>();

    @Nullable
    public static UUID elder(UUID villageId) {
        Elder e = ELDERS.get(villageId);
        return e == null ? null : e.id();
    }

    public static String elderName(UUID villageId) {
        Elder e = ELDERS.get(villageId);
        return e == null ? "" : e.name();
    }

    /** Once a day: who does the village look up to? */
    public static void chooseElder(UUID villageId, long day) {
        Elder now = ELDERS.get(villageId);
        if (now != null && now.day() == day) return;
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : folkOf(villageId)) {
            if (a instanceof VillageFolkEntity f && f.persona().rolled()) folk.add(f);
        }
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VillageFolkEntity c : folk) {
            if (c.isBaby()) continue;
            int score = (int) Math.min(30, Math.max(0, day - c.persona().since()));     // years count
            for (VillageFolkEntity o : folk) if (o != c) score += o.life().affinity(c.getUUID());
            if (score > bestScore) { bestScore = score; best = c; }
        }
        if (best == null) return;
        boolean changed = now == null || !now.id().equals(best.getUUID());
        ELDERS.put(villageId, new Elder(best.getUUID(), best.displayNameCap(), day));
        if (changed && folk.size() >= 4) {
            tell(villageId, day, best.displayNameCap() + " was chosen as the village elder");
            best.persona().remember(day, "the village chose me as its elder", 9);
        }
    }

    /** "the meeting hall", "a new house": a building as folk would speak of it. */
    public static String spoken(String structure) {
        return switch (structure) {
            case "fortify" -> "the wall";
            case "storage" -> "the storehouse";
            case "house" -> "a new house";
            case "hall" -> "the meeting hall";
            case "pen" -> "the animal pen";
            case "guesthouse" -> "a house for the village's honoured guest";
            default -> "the " + structure;
        };
    }

    public static void resetForTests() {
        Standing.resetForTests();
        Gatherings.resetForTests();
        ELDERS.clear();
        NEWS.clear();
        AGED_ON.clear();
        ALL.clear();
        AGE.clear();
        BUILT.clear();
        LAST_PROJECT.clear();
        POP.clear();
        LAST_BIRTH.clear();
        SITES.clear();
        LOT_TAKEN.clear();
        BAD_LOTS.clear();
        WHY_NOT.clear();
        LAPS.clear();
        DEFERRED.clear();
        FOUNDED.clear();
        LEAD.clear();
        LEAD_AT.clear();
        LAST_BAKE.clear();
        STOCK.clear();
        STOCK_TICK.clear();
    }

    /** Every settlement this session knows about. */
    public static java.util.List<Village> every() {
        return new ArrayList<>(ALL.values());
    }

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
        return nearest(level, pos, VILLAGE_RANGE);
    }

    /** The same, out to a range of the caller's choosing. A vanilla village
     *  routinely spans more than the ninety-six blocks a founded one does, so
     *  the takeover asks further out than anybody else. */
    @Nullable
    public static Village nearest(Level level, BlockPos pos, int range) {
        Village best = null;
        double bestDist = Double.MAX_VALUE;
        for (Village v : ALL.values()) {
            if (!v.dim().equals(level.dimension())) continue;   // not our world
            double d = v.centre().distSqr(pos);
            if (d > (double) range * range || d >= bestDist) continue;
            bestDist = d;
            best = v;
        }
        return best;
    }



    public static Village found(Level level, BlockPos centre) {
        Village v = new Village(UUID.randomUUID(), centre.immutable(), level.dimension());
        ALL.put(v.id(), v);
        FOUNDED.put(v.id(), level.getGameTime());
        com.jrpetty.mcassistant.village.Chronicle.record(v.id(), level.getDayTime() / 24000L,
            name(v.id()) + " was founded");
        return v;
    }

    /** The settlement is gone. Drops its register entry and everything hung
     *  off it, so nothing keeps pointing at a village nobody lives in. */
    public static void forget(UUID villageId) {
        POP.remove(villageId);
        LAST_BIRTH.remove(villageId);
        SITES.remove(villageId);
        LOT_TAKEN.remove(villageId);
        BAD_LOTS.remove(villageId);
        WHY_NOT.remove(villageId);
        LAPS.remove(villageId);
        FOUNDED.remove(villageId);
        LEAD.remove(villageId);
        LEAD_AT.remove(villageId);
        LAST_BAKE.remove(villageId);
        ALL.remove(villageId);
        AGE.remove(villageId);
        BUILT.remove(villageId);
        LAST_PROJECT.remove(villageId);
    }

    /** Restore a settlement from what one of its folk remembers. Village
     *  state lives in memory only, so the folk carry it: the first one to
     *  load puts its village — and how far it had got — back on the map. */
    public static void restore(Level level, UUID id, BlockPos centre, Age age, List<String> built) {
        restore(level, id, centre, age, built, 0);
    }

    public static void restore(Level level, UUID id, BlockPos centre, Age age,
                               List<String> built, int population) {
        ALL.putIfAbsent(id, new Village(id, centre, level.dimension()));
        AGE.putIfAbsent(id, age);
        BUILT.computeIfAbsent(id, k -> new ArrayList<>(built));
        if (population > 0) POP.merge(id, population, Math::max);
    }

    // ------------------------------ how many people live here ----------------
    //
    // Counting the folk that happen to be LOADED is not counting the folk who
    // live here, and every number this settlement runs on is worked out from
    // the answer: how much food it needs, how much iron, how far its people
    // search for ground, how much of it stays awake, and whether it is allowed
    // to raise another child. A town of a hundred keeps eight chunks ticking
    // and spreads over fifteen, so with nobody nearby it would have reported
    // about thirty people, decided thirty people's rations were plenty, and
    // gone on breeding past its own cap because thirty is under it.
    //
    // The roll is kept instead: written down when folk are seen, carried on
    // their save data so it survives a restart, and it only ever comes down
    // when somebody actually dies.

    private static final Map<UUID, Integer> POP = new ConcurrentHashMap<>();

    /** Somebody was born here. */
    public static void recordBirth(UUID villageId) {
        POP.merge(villageId, 1, Integer::sum);
    }

    private static final Map<UUID, Long> LAST_BIRTH = new ConcurrentHashMap<>();

    /**
     * May this settlement raise another child yet? One clock for the whole
     * village, because the per-folk clocks alone let a dozen fed hands stood
     * together produce a child every few seconds. The gap shortens as the
     * village grows — a hundred folk can raise a child every half minute,
     * twelve every few minutes — so growth is steady rather than explosive at
     * either end.
     */
    public static boolean mayBirth(UUID villageId, long gameTime) {
        int folk = Math.max(1, headcount(villageId));
        long gap = Math.max(600L, Math.min(6000L, 12000L / (folk / 4 + 1)));
        return gameTime - LAST_BIRTH.getOrDefault(villageId, -gap) >= gap;
    }

    /** A child was raised here just now (starts the village's clock). */
    public static void noteBirth(UUID villageId, long gameTime) {
        LAST_BIRTH.put(villageId, gameTime);
    }

    /** Somebody died here — the one thing that makes a village smaller. */
    public static void recordDeath(UUID villageId) {
        POP.computeIfPresent(villageId, (k, n) -> Math.max(0, n - 1));
    }

    /** What this village should be saving as its roll. */
    public static int recordedPopulation(@Nullable UUID villageId) {
        if (villageId == null) return 0;
        return POP.getOrDefault(villageId, 0);
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

    /**
     * How many people live here — not how many are loaded. The roll is the
     * higher of the two, and seeing more than the roll says is itself proof
     * the roll was low, so it is corrected on the spot.
     */
    public static int headcount(@Nullable UUID villageId) {
        int live = folkOf(villageId).size();
        if (villageId == null) return live;
        Integer roll = POP.get(villageId);
        if (roll == null || live > roll) {
            if (live > 0) POP.put(villageId, live);
            return live;
        }
        return Math.max(live, roll);
    }

    /** Everybody who is actually here to be given a job right now. */
    public static int loadedCount(@Nullable UUID villageId) {
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
        // works out its share against a village of one and takes the farm —
        // and count against the ROLL, not the room: a newborn in a town of a
        // hundred with thirty loaded must not size its village at thirty.
        int total = Math.max(1, Math.max(folk.size(), headcount(villageId)));
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
        // Everything below is worked out from how many mouths and hands the
        // place actually has — see VillageMath. These were flat numbers, and a
        // flat sixty-four food is a fortnight's larder for ten people and
        // twenty minutes' eating for a hundred: a town would have declared
        // itself well fed and then starved with the plan saying nothing.
        int foodNow = com.jrpetty.mcassistant.village.VillageMath.foodWanted(folk);
        switch (at) {
            case WOOD -> {
                // Timber, a roof, and food coming in. Everything a place needs
                // before it can afford to think about stone.
                need(wants, level, v, "food in the stores", Task.FOOD, foodNow);
                if (built(villageId, "storage") < 1) wants.add(new Need("somewhere to store things", Task.BUILD, 1));
                need(wants, level, v, "timber", Task.LOGS,
                    com.jrpetty.mcassistant.village.VillageMath.timberWanted(folk));
                if (built(villageId, "shelter") < 1) wants.add(new Need("a shelter", Task.BUILD, 1));
                // Houses are measured the way they are used: room for everybody, and some to spare.
                if (built(villageId, "house") < 1 || folk >= housing(villageId) - 2) {
                    wants.add(new Need("houses", Task.BUILD, 1));
                }
                if (built(villageId, "well") < 1) wants.add(new Need("a well", Task.BUILD, 1));
            }
            case STONE -> {
                // Quarry, wall, and a fire to work by.
                need(wants, level, v, "stone", Task.STONE,
                    com.jrpetty.mcassistant.village.VillageMath.stoneWanted(folk));
                need(wants, level, v, "coal", Task.COAL,
                    com.jrpetty.mcassistant.village.VillageMath.coalWanted(folk));
                if (built(villageId, "fortify") < 1) wants.add(new Need("a wall around the village", Task.BUILD, 1));
                if (folk >= housing(villageId) - 5) {
                    wants.add(new Need("more houses", Task.BUILD, 1));
                }
                if (built(villageId, "smeltery") < 1) wants.add(new Need("a smeltery", Task.BUILD, 1));
                if (built(villageId, "hall") < 1) wants.add(new Need("a meeting hall", Task.BUILD, 1));
                need(wants, level, v, "food in the stores", Task.FOOD, foodNow);
            }
            case IRON -> {
                // Enough metal to put the watch in armour and a decent tool in
                // every hand — which is what "the Iron Age" is FOR, rather
                // than a round number somebody picked.
                need(wants, level, v, "iron", Task.IRON,
                    com.jrpetty.mcassistant.village.VillageMath.ironWanted(folk));
                if (built(villageId, "workshop") < 1) wants.add(new Need("a workshop", Task.BUILD, 1));
                if (built(villageId, "watchtower") < 1) wants.add(new Need("a watchtower", Task.BUILD, 1));
                if (built(villageId, "market") < 1) wants.add(new Need("a market", Task.BUILD, 1));
                need(wants, level, v, "food in the stores", Task.FOOD, foodNow);
            }
            case DIAMOND -> {
                need(wants, level, v, "diamonds", Task.DIAMOND,
                    com.jrpetty.mcassistant.village.VillageMath.diamondsWanted(folk));
                // Twice the Iron Age's metal. It asked for armour for EVERYBODY (twenty-four
                // a head, nine hundred and sixty for a village of forty), which is a wall and
                // not a goal: the best real-terrain village held nine.
                need(wants, level, v, "iron", Task.IRON,
                    2 * com.jrpetty.mcassistant.village.VillageMath.ironWanted(folk));
                if (built(villageId, "lighthouse") < 1) wants.add(new Need("a lighthouse", Task.BUILD, 1));
                if (built(villageId, "chapel") < 1) wants.add(new Need("a chapel", Task.BUILD, 1));
                need(wants, level, v, "food in the stores", Task.FOOD, foodNow);
            }
            case NETHER -> {
                // The last thing a settlement builds for itself is a way out
                // of the world it started in...
                if (built(villageId, "gateway") < 1) {
                    need(wants, level, v, "obsidian", Task.OBSIDIAN,
                        com.jrpetty.mcassistant.village.VillageMath.obsidianWanted(folk));
                    need(wants, level, v, "diamonds", Task.DIAMOND,
                        2 * com.jrpetty.mcassistant.village.VillageMath.diamondsWanted(folk));
                    wants.add(new Need("a gateway", Task.BUILD, 1));
                    need(wants, level, v, "food in the stores", Task.FOOD, foodNow);
                    break;
                }
                // ...and after that it never stops: every great work raised asks the
                // stores for a quarter more of everything before the next.
                int r = renown(villageId);
                int more = 4 + r;                        // in quarters
                need(wants, level, v, "food in the stores", Task.FOOD, foodNow * more / 4);
                need(wants, level, v, "stone", Task.STONE,
                    com.jrpetty.mcassistant.village.VillageMath.stoneWanted(folk) * more / 4);
                need(wants, level, v, "iron", Task.IRON,
                    com.jrpetty.mcassistant.village.VillageMath.ironWanted(folk) * more / 4);
                wants.add(new Need("a " + nextGreatWork(villageId), Task.BUILD, 1));
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
        long day = level.getDayTime() / 24000L;
        AGED_ON.put(villageId, day);
        tell(villageId, day, "the village came into " + next.label);
        for (AssistantEntity a : folkOf(villageId)) {
            if (a instanceof VillageFolkEntity f) f.persona().remember(day, "I saw the village come into " + next.label, 7);
        }
        Village v = get(villageId);
        String where = v == null ? "" : " (" + v.centre().getX() + ", " + v.centre().getZ() + ")";
        net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.literal(
            name(villageId) + " has entered " + next.label + where + ".")
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
            // What the VILLAGE holds is what carries the village's name, whoever
            // happens to be asking (the command, a test, a folk).
            boolean before = ZoneChests.askAs(true);
            java.util.List<ZoneChests.Found> stores;
            try {
                stores = ZoneChests.around(level, centre, radius, 64);
            } finally {
                ZoneChests.askAs(before);
            }
            for (ZoneChests.Found f : stores) {
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack st = c.getItem(i);
                    if (st.isEmpty()) continue;
                    for (Task t : all) {
                        if (matches(t, st)) cache[t.ordinal()] += st.getCount();
                    }
                    // Wheat is not food, but it is three to a loaf and every
                    // field grows it. A larder that ignored it read "short of
                    // food" beside chests of it, and the ages never turned.
                    if (st.is(net.minecraft.world.item.Items.WHEAT)) {
                        cache[Task.FOOD.ordinal()] += st.getCount() / 3;
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
        return com.jrpetty.mcassistant.village.VillageMath.storesRadius(headcount(villageId));
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
     *  not feel like one — but eight minutes between one and the next meant that
     *  a player who watched a village for half an hour saw two buildings go up. */
    private static final long PROJECT_GAP = 4800L;   // four minutes

    /** The wait between one project and the next. Four minutes for the twelve a village
     *  is founded with, shorter as it grows: fifty folk have more hands to spare and
     *  a great deal more to put up, and waiting the same four minutes between houses
     *  left a big village standing in front of the list. */
    private static long gapFor(UUID villageId) {
        return Math.max(1200L, PROJECT_GAP * 12L / Math.max(12, headcount(villageId)));
    }

    public static boolean projectDue(UUID villageId, long gameTime) {
        if (gameTime > CLOCK) CLOCK = gameTime;
        long gap = gapFor(villageId);
        return gameTime - LAST_PROJECT.getOrDefault(villageId, -gap) >= gap;
    }

    /** Somebody has set off to build something. Paces the next project;
     *  says nothing about whether this one succeeds. */
    public static void noteAttempt(UUID villageId, long gameTime) {
        LAST_PROJECT.put(villageId, gameTime);
    }

    /** Somebody looked, and the stores were not yet up to it. That is not a
     *  project started, so it must not cost the village its whole eight-minute
     *  gap: look again in two. (The very first look, seconds after founding,
     *  always finds an almost empty larder — and used to cost eight minutes.) */
    public static void retrySoon(UUID villageId, long gameTime) {
        long gap = gapFor(villageId);
        LAST_PROJECT.put(villageId, gameTime - gap + Math.min(2400L, gap / 2));
    }

    /** Look again in this many ticks (the lead has set about making a fixture, which takes seconds,
     *  not the minutes a wait for stone does). */
    public static void retryIn(UUID villageId, long gameTime, long delay) {
        long gap = gapFor(villageId);
        LAST_PROJECT.put(villageId, gameTime - gap + Math.min(delay, gap));
    }

    /** Somebody looked for a lot and there was none to be had yet (the ground
     *  still arriving, or all of it rough): look again in a minute. */
    public static void retryShortly(UUID villageId, long gameTime) {
        long gap = gapFor(villageId);
        LAST_PROJECT.put(villageId, gameTime - gap + Math.min(1200L, gap / 4));
    }

    /** This village sent out a founding party. Written down beside its buildings, so it
     *  is carried by the folk across a restart like everything else the village has done. */
    public static void noteColony(UUID villageId) {
        BUILT.computeIfAbsent(villageId, k -> new ArrayList<>()).add("colony");
    }

    /** Something actually went up. Only finished buildings count toward the
     *  village's ages — a village that could not find the timber has not got
     *  a storehouse, however many times it tried. */
    public static void noteProject(UUID villageId, String structure, long gameTime) {
        if (!"colony".equals(structure) && !"guesthouse".equals(structure)) tell(villageId, gameTime / 24000L, spoken(structure) + " went up");
        LAST_PROJECT.put(villageId, gameTime);
        BUILT.computeIfAbsent(villageId, k -> new ArrayList<>()).add(structure);
        Map<String, Site> pending = SITES.get(villageId);
        if ("guesthouse".equals(structure)) {
            com.jrpetty.mcassistant.village.Chronicle.Guest g = com.jrpetty.mcassistant.village.Chronicle.awaitingAHouse(villageId);
            Site site = pending == null ? null : pending.get(structure);
            Village v = get(villageId);
            BlockPos at = site != null ? site.anchor() : v != null ? v.centre() : BlockPos.ZERO;
            if (g != null) {
                g.built = true;
                g.x = at.getX();
                g.y = at.getY();
                g.z = at.getZ();
                com.jrpetty.mcassistant.village.Chronicle.touch();
                tell(villageId, gameTime / 24000L, g.name + "'s house went up");
            }
        }
        if (pending != null) pending.remove(structure);      // its ground is spoken for now
        LEAD.remove(villageId);                              // and the next one starts fresh
        LEAD_AT.remove(villageId);
    }

    private static int built(UUID villageId, String structure) {
        int n = 0;
        for (String s : BUILT.getOrDefault(villageId, List.of())) {
            if (s.equals(structure)) n++;
        }
        return n;
    }

    /** Has the village got one of these standing? */
    public static boolean hasBuilt(UUID villageId, String structure) {
        return built(villageId, structure) > 0;
    }

    private static final Map<UUID, Long> FOUNDED = new ConcurrentHashMap<>();

    /** How long the storehouse has first call on a new village's planks, stone and chests. */
    private static final long STOREHOUSE_FIRST = 36000L;       // half an hour

    /**
     * Is the storehouse still to be raised, in a village young enough that the
     * stock it was founded with belongs to the storehouse?
     *
     * <p>Every hand came out of the founding with a chest to set down, a
     * bench and tools — and then, each one, went back to the stores for spares:
     * planks for a pick, a chest for its plot. Twelve hands drained forty-eight
     * planks and four chests in the first minutes, and the building that was
     * meant to be made of them (four chests, seventy blocks) could then not be
     * afforded for three game days on a map with few trees. The builder draws
     * on the stores directly and is not asked to wait; everyone else leaves the
     * timber, the stone and the chests alone until the storehouse stands — or ten
     * minutes go by, so that a village which cannot build one is not starved of
     * planks for ever.
     */
    public static boolean storehouseFirst(UUID villageId, long gameTime) {
        if (built(villageId, "storage") > 0) return false;
        long since = FOUNDED.computeIfAbsent(villageId, k -> gameTime);
        return gameTime - since < STOREHOUSE_FIRST;
    }

    /**
     * Projects set aside for now, and until when. A building with no ground for it, or
     * one the stores cannot pay for yet, used to stand at the head of the list and hold
     * up everything behind it: every Stone Age village on four real maps had "no lot for
     * the fortify" for days, and the smeltery and the meeting hall waited behind the
     * wall, so not one of them came of age. Set aside, it gets another look later and
     * the next thing on the list goes up meanwhile.
     */
    private static final Map<UUID, Map<String, Long>> DEFERRED = new ConcurrentHashMap<>();
    /** The latest game time any village has been asked about (see projectDue). */
    private static volatile long CLOCK;

    public static void defer(UUID villageId, String project, long until) {
        DEFERRED.computeIfAbsent(villageId, k -> new ConcurrentHashMap<>()).put(project, until);
    }

    /** Not set aside: the village may take this project on now. */
    private static boolean open(UUID villageId, String project) {
        Map<String, Long> d = DEFERRED.get(villageId);
        if (d == null) return true;
        Long until = d.get(project);
        return until == null || CLOCK >= until;
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
        for (String p : projectsWanted(villageId)) {
            if (open(villageId, p)) return p;
        }
        return null;
    }

    /**
     * Everything the village would build now, most pressing first. The first one not
     * set aside is the next project (see {@link #defer}); the rest wait their turn.
     */
    public static List<String> projectsWanted(UUID villageId) {
        List<String> out = new ArrayList<>();
        int folk = headcount(villageId);
        if (folk == 0) return out;
        Age at = age(villageId);

        if (built(villageId, "storage") < 1) out.add("storage");
        if (built(villageId, "shelter") < 1) out.add("shelter");
        // Room before anything else: a village with every home full stops growing, and
        // growing is the whole of how it gets the hands for everything after this.
        boolean house = folk >= housing(villageId) - 2 || built(villageId, "house") < 1;
        if (house) out.add("house");
        if (built(villageId, "well") < 1) out.add("well");
        // A house for the player the village has taken to its heart.
        if (com.jrpetty.mcassistant.village.Chronicle.awaitingAHouse(villageId) != null
                && built(villageId, "storage") > 0) out.add("guesthouse");
        if (at == Age.WOOD) return out;

        if (built(villageId, "fortify") < 1) out.add("fortify");        // the wall
        // A Stone Age village keeps a few homes spare, not just one.
        if (!house && folk >= housing(villageId) - 5) out.add("house");
        if (built(villageId, "smeltery") < 1) out.add("smeltery");
        if (built(villageId, "hall") < 1) out.add("hall");
        if (at == Age.STONE) return out;

        if (built(villageId, "workshop") < 1) out.add("workshop");
        // Whatever the headcount: the Iron Age asks for it, and a village
        // that has lost people must still be able to finish its list.
        if (built(villageId, "watchtower") < 1) out.add("watchtower");
        if (built(villageId, "market") < 1) out.add("market");
        String pen = penIfWanted(villageId, folk);
        if (pen != null) out.add(pen);
        if (at == Age.IRON) return out;

        if (built(villageId, "lighthouse") < 1) out.add("lighthouse");
        if (built(villageId, "chapel") < 1) out.add("chapel");
        if (at == Age.DIAMOND) return out;

        if (built(villageId, "gateway") < 1) out.add("gateway");
        // And then the great works, one after another for as long as the village stands:
        // a town that has been everywhere its ages lead goes on building.
        out.add(nextGreatWork(villageId));
        return out;
    }

    /**
     * What a village builds once it has come through every age: a granary, barracks and
     * a monument, round and round, each on new ground and each asking the stores for more
     * than the last (see {@link #renown}). It is what keeps a finished village growing.
     */
    public static final List<String> GREAT_WORKS = List.of("granary", "barracks", "monument");

    /** How many great works this village has raised: its renown. */
    public static int renown(UUID villageId) {
        int n = 0;
        for (String g : GREAT_WORKS) n += built(villageId, g);
        return n;
    }

    /** The great work this village raises next. */
    public static String nextGreatWork(UUID villageId) {
        return GREAT_WORKS.get(renown(villageId) % GREAT_WORKS.size());
    }

    /**
     * How many people this village has room for. A village is founded with room for
     * twelve — the founders sleep rough round the heart — and every home it builds is
     * room for five more: the shelter three, the meeting hall six. Two fed folk in work
     * raise a child only while there is room for one, so a village grows exactly as
     * fast as it builds homes, and that is the reason it builds them.
     */
    public static int housing(UUID villageId) {
        return 12 + 5 * built(villageId, "house") + 3 * built(villageId, "shelter") + 6 * built(villageId, "hall")
            + 6 * built(villageId, "barracks");
    }

    /**
     * Food the stores must hold before anybody raises a child: a day's meals for
     * everybody already here. Children are raised out of what is put by, not out
     * of the last loaf. Three real-terrain villages grew from twelve to twenty-five
     * and thirty while their stores fell from a hundred and twenty to eight: every
     * surplus became a child, the stores never reached what the Wood Age asks for,
     * and a forest village of forty-one with eight buildings never left it.
     */
    public static int larderForBirth(UUID villageId) {
        return com.jrpetty.mcassistant.village.VillageMath.larderForBirth(headcount(villageId));
    }

    /** True when the village's stores hold enough food for it to raise children. */
    public static boolean larderFull(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        Village v = get(villageId);
        if (v == null) return false;
        return stock(level, v.centre(), Task.FOOD, storesRadius(villageId)) >= larderForBirth(villageId);
    }

    /** Every village's folk, together: what the whole world holds. */
    public static int worldHeadcount() {
        int n = 0;
        for (Village v : every()) n += headcount(v.id());
        return n;
    }

    /** Whether the village is growing and, if not, what it is waiting for. */
    public static String growthNote(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        int folk = headcount(villageId);
        if (folk >= com.jrpetty.mcassistant.AssistantConfig.villageGrowthCap()) return "no — at the cap";
        if (worldHeadcount() >= com.jrpetty.mcassistant.AssistantConfig.villageWorldCap()) {
            return "no — the world holds as many folk as it may (villageWorldCap)";
        }
        if (folk >= housing(villageId)) return "no — every home is full, a house comes first";
        Village v = get(villageId);
        if (v == null) return "no";
        int food = stock(level, v.centre(), Task.FOOD, storesRadius(villageId));
        int want = larderForBirth(villageId);
        if (food < want) return "no — the stores hold " + food + " food and a child needs " + want + " put by";
        return "yes";
    }

    /** Why the village wants the building it wants next, in a line a player can read. */
    public static String whyBuild(UUID villageId, @Nullable String project) {
        if (project == null) return "nothing for now — the village is gathering what its age asks for";
        int folk = headcount(villageId);
        return switch (project) {
            case "guesthouse" -> {
                com.jrpetty.mcassistant.village.Chronicle.Guest g = com.jrpetty.mcassistant.village.Chronicle.awaitingAHouse(villageId);
                yield "a house for " + (g == null ? "an honoured guest" : g.name) + ", the village's honoured guest";
            }
            case "storage" -> "a storehouse, so what is gathered has somewhere to go";
            case "shelter" -> "a shelter, somewhere to wait out the first nights";
            case "house" -> "a house: " + folk + " live here and there is room for " + housing(villageId)
                + ", and nobody is born without room";
            case "well" -> "a well at the heart, the mark of a village rather than a camp";
            case "fortify" -> "a wall round the village, against the things that come out at night";
            case "smeltery" -> "a smeltery, three furnaces for the ore the mines bring up";
            case "hall" -> "a meeting hall, which a village must have before the Iron Age";
            case "workshop" -> "a workshop, where iron becomes tools";
            case "watchtower" -> "a watchtower, to see trouble coming";
            case "lighthouse" -> "a lighthouse, so anyone out after dark can find the way home";
            case "pen" -> "a pen, for the rancher's herd";
            case "market" -> "a market, stalls under one roof for what the village makes";
            case "chapel" -> "a chapel, which the Diamond Age asks for";
            case "gateway" -> "a gateway of obsidian, the way out of the world the Nether Age is named for";
            case "granary" -> "a granary (great work " + (renown(villageId) + 1) + "): a town that has come through every age goes on building";
            case "barracks" -> "barracks (great work " + (renown(villageId) + 1) + "), room for six more and a home for the watch";
            case "monument" -> "a monument (great work " + (renown(villageId) + 1) + ") to how far the village has come";
            default -> "the " + project;
        };
    }

    /** A pen, once the place is big enough to keep a rancher — but LAST, after
     *  everything an age actually asks for. It used to sit ahead of the
     *  workshop and the watchtower, and its fences were never made, so a
     *  village of fourteen could never finish the Iron Age. */
    @Nullable
    private static String penIfWanted(UUID villageId, int folk) {
        return folk >= 14 && built(villageId, "pen") < 1 ? "pen" : null;
    }

    // ---- who is baking ----

    private static final Map<UUID, Long> LAST_BAKE = new ConcurrentHashMap<>();

    /** One bread errand at a time for the whole village: a dozen idle hands at
     *  the heart at dusk would otherwise all walk to the same chest for the
     *  same wheat. */
    public static boolean mayBake(UUID villageId, long gameTime) {
        return gameTime - LAST_BAKE.getOrDefault(villageId, -1200L) >= 1200L;
    }

    public static void noteBake(UUID villageId, long gameTime) {
        LAST_BAKE.put(villageId, gameTime);
    }

    // ---- who is raising the next building ----

    private static final Map<UUID, UUID> LEAD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LEAD_AT = new ConcurrentHashMap<>();
    private static final long LEAD_TERM = 6000L;      // five minutes without progress

    /**
     * Is this hand the one raising the village's next building? The first to
     * ask becomes the lead and stays it: its pack is where the timber, the
     * chests and the ladders pile up over several visits, so the next
     * volunteer must not start again from nothing with a pack of its own —
     * half a building's fixtures scattered across a dozen packs is a building
     * nobody can ever start. The post passes on when the lead dies, is nowhere
     * to be found, or five minutes go by without it getting anywhere.
     */
    public static boolean isLead(UUID villageId, UUID me, long now) {
        UUID cur = LEAD.get(villageId);
        Long since = LEAD_AT.get(villageId);
        boolean valid = cur != null && since != null && now - since < LEAD_TERM && livesHere(villageId, cur);
        if (!valid) {
            LEAD.put(villageId, me);
            LEAD_AT.put(villageId, now);
            return true;
        }
        return cur.equals(me);
    }

    /** Is this hand the lead right now (without taking the post if it is free)? */
    public static boolean holdsTheLead(UUID villageId, UUID me, long now) {
        UUID cur = LEAD.get(villageId);
        Long since = LEAD_AT.get(villageId);
        return me.equals(cur) && since != null && now - since < LEAD_TERM;
    }

    /** The lead got somewhere — a load drawn, a fixture crafted: the term restarts. */
    public static void leadProgress(UUID villageId, UUID me, long now) {
        if (me.equals(LEAD.get(villageId))) LEAD_AT.put(villageId, now);
    }

    private static boolean livesHere(UUID villageId, UUID folk) {
        for (AssistantEntity a : AssistantEntity.allFor(villageId)) {
            if (a.isAlive() && a.getUUID().equals(folk)) return true;
        }
        return false;
    }

    // ---- where the buildings go ----

    /** Where a building goes: the spot it is centred on, which way it faces
     *  (its door is on the heart's side), and — for a wall — how big a ring. */
    public record Site(BlockPos anchor, net.minecraft.core.Direction facing, int radius) {}

    private static final Map<UUID, Map<String, Site>> SITES = new ConcurrentHashMap<>();
    /** Which lots (by index in LOTS) are spoken for: chosen for a project, built on, or given up. */
    private static final Map<UUID, java.util.Set<Integer>> LOT_TAKEN = new ConcurrentHashMap<>();
    /** Lots the builders found they could not get to, as the column of the lot's middle. */
    private static final Map<UUID, java.util.Set<Long>> BAD_LOTS = new ConcurrentHashMap<>();
    /** The most a lot's ground may stand above or below the ground at the heart. */
    private static final int LOT_RISE = 12;
    /** How many times round its lots a village has been without finding one. Each
     *  lap lets the ground be a little rougher, so a village founded on a
     *  mountainside is not left without a storehouse for ever. */
    private static final Map<UUID, Integer> LAPS = new ConcurrentHashMap<>();

    /** Nine blocks a lot: a five-by-five house or store and a margin. */
    private static final int PITCH = 9;
    /** The wall rings the first lots. */
    private static final int WALL_RADIUS = 13;

    /** Lot offsets in the order buildings claim them: the ring closest to the
     *  heart first, working round. */
    private static final int[][] LOTS = lots();

    private static int[][] lots() {
        List<int[]> cells = new ArrayList<>();
        for (int r = 1; r <= 7; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == r) cells.add(new int[]{ x, z });
                }
            }
        }
        cells.sort(java.util.Comparator.<int[]>comparingInt(c -> Math.max(Math.abs(c[0]), Math.abs(c[1])))
            .thenComparingDouble(c -> Math.atan2(c[1], c[0])));
        return cells.toArray(new int[0][]);
    }

    /**
     * Where this project goes up — chosen ONCE and kept until it stands.
     *
     * <p>Buildings used to go up wherever the volunteer happened to be
     * standing, four blocks in front of it. Any interruption — a fight, a
     * shelter, the end of the day — and the retry laid the blueprint out again
     * somewhere else, leaving the first attempt half-built behind and never
     * finishing the second. A lot is claimed on the village's own grid, flat
     * and clear, and the job is anchored to it, so a build picks up where it
     * left off.
     */
    @Nullable
    public static Site siteFor(net.minecraft.server.level.ServerLevel level, UUID villageId, String project) {
        Village v = get(villageId);
        if (v == null) return null;
        Map<String, Site> pending = SITES.computeIfAbsent(villageId, k -> new ConcurrentHashMap<>());
        Site have = pending.get(project);
        if (have != null) return have;

        Site site = null;
        if (project.equals("fortify")) {
            // The wall rings the heart and follows the ground wherever it goes (BuildGoal), so
            // it needs no lot, only the height of the heart to measure from. It used to be
            // given one only where the heart itself would do for a house — dry, flat to four
            // blocks, nothing standing — and every Stone Age village on four real maps had
            // something at its heart (the founding stores, a building, a slope), had "no lot
            // for the fortify" for days, and never came of age.
            BlockPos ground = groundFor(level, v.centre().getX(), v.centre().getZ(), false, Integer.MIN_VALUE, 0, null);
            if (ground == null) {
                int y = heartGround(level, v.centre());
                if (y != Integer.MIN_VALUE) ground = new BlockPos(v.centre().getX(), y, v.centre().getZ());
            }
            if (ground != null) site = new Site(ground, net.minecraft.core.Direction.NORTH, WALL_RADIUS);
        } else {
            java.util.Set<Long> bad = BAD_LOTS.computeIfAbsent(villageId, k -> ConcurrentHashMap.newKeySet());
            java.util.Set<Integer> taken = LOT_TAKEN.computeIfAbsent(villageId, k -> ConcurrentHashMap.newKeySet());
            int heartGround = heartGround(level, v.centre());
            int laps = LAPS.getOrDefault(villageId, 0);
            // Look at the lots nearest the heart, and of the first few that will do take the
            // one that costs least to build on — a flat lot a ring further out beats a slope
            // that wants fifty blocks of stone under its floor, which a young village may not
            // have for a day.
            int bestIndex = -1;
            int bestScore = Integer.MAX_VALUE;
            int valid = 0;
            int free = 0;
            boolean waiting = false;
            for (int i = 0; i < LOTS.length && valid < 8; i++) {
                if (taken.contains(i)) continue;
                int[] c = LOTS[i];
                int x = v.centre().getX() + c[0] * PITCH;
                int z = v.centre().getZ() + c[1] * PITCH;
                if (bad.contains(BlockPos.asLong(x, 0, z))) continue;
                free++;
                // A lot whose ground has not arrived yet has not been found wanting. The ring
                // of chunks round a new village comes in over its first minute, and every lot
                // looked at before that used to be written off for good.
                if (!lotLoaded(level, x, z)) { waiting = true; continue; }
                BlockPos ground = groundFor(level, x, z, true, heartGround, laps, whyNot(villageId));
                if (ground == null) continue;
                valid++;
                int ring = Math.max(Math.abs(c[0]), Math.abs(c[1]));
                int score = com.jrpetty.mcassistant.entity.goal.BuildGoal.fillCells(level, ground).size() + 4 * ring;
                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = i;
                    site = new Site(ground, facingOf(c[0], c[1]), 0);
                }
            }
            if (site != null) {
                taken.add(bestIndex);
            } else if (!waiting && free > 0) {
                LAPS.merge(villageId, 1, Integer::sum);         // a whole look and nothing: less particular next time
            }
        }
        if (site != null) pending.put(project, site);
        return site;
    }

    /** The builders could not get to this lot: give it up, never pick it again,
     *  and have another look in half a minute. */
    public static void rejectSite(UUID villageId, String project, long gameTime) {
        Map<String, Site> pending = SITES.get(villageId);
        Site gone = pending == null ? null : pending.remove(project);
        if (gone != null) {
            why(whyNot(villageId), TAKEN);
            BAD_LOTS.computeIfAbsent(villageId, k -> ConcurrentHashMap.newKeySet())
                .add(BlockPos.asLong(gone.anchor().getX(), 0, gone.anchor().getZ()));
        }
        LEAD.remove(villageId);
        LEAD_AT.remove(villageId);
        long gap = gapFor(villageId);
        LAST_PROJECT.put(villageId, gameTime - gap + Math.min(600L, gap / 8));
    }

    private static final int WET = 0, CLIFF = 1, HEIGHT = 2, TRUNK = 3, BLOCKED = 4, TAKEN = 5;

    /** What a village's lot search turned down, and the last cliff it saw (where, and how high). */
    private static final class Why {
        final int[] n = new int[6];
        String lastCliff = "";
    }

    private static final Map<UUID, Why> WHY_NOT = new ConcurrentHashMap<>();

    private static Why whyNot(UUID villageId) {
        return WHY_NOT.computeIfAbsent(villageId, k -> new Why());
    }

    private static void why(@Nullable Why why, int reason) {
        if (why != null) why.n[reason]++;
    }

    /** Where the lot search has got to and what it has turned down, for the log:
     *  a village that cannot build has a reason, and it is one of these. */
    public static String lotReport(UUID villageId) {
        Why why = WHY_NOT.get(villageId);
        int[] w = why == null ? new int[6] : why.n;
        return "lap " + LAPS.getOrDefault(villageId, 0) + ", " + LOT_TAKEN.getOrDefault(villageId, java.util.Set.of()).size() + " of " + LOTS.length + " lots spoken for"
            + "; turned down: wet " + w[WET] + ", cliff " + w[CLIFF] + ", too high or low " + w[HEIGHT]
            + ", built on or rocky " + w[BLOCKED] + ", given up " + w[TAKEN]
            + (why == null || why.lastCliff.isEmpty() ? "" : "; last cliff " + why.lastCliff);
    }

    /** Is the ground a lot here would stand on all in the world yet? */
    private static boolean lotLoaded(net.minecraft.server.level.ServerLevel level, int x, int z) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (!level.hasChunk((x + dx) >> 4, (z + dz) >> 4)) return false;
            }
        }
        return true;
    }

    /** The height of the ground at the heart, or MIN_VALUE if it is not loaded. */
    private static int heartGround(net.minecraft.server.level.ServerLevel level, BlockPos centre) {
        if (!level.hasChunk(centre.getX() >> 4, centre.getZ() >> 4)) return Integer.MIN_VALUE;
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            centre.getX(), centre.getZ());
    }

    /** Which way is "away from the heart" for a lot at this offset. */
    private static net.minecraft.core.Direction facingOf(int dx, int dz) {
        return Math.abs(dx) >= Math.abs(dz)
            ? (dx >= 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
            : (dz >= 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
    }

    /**
     * Level-enough, dry, clear-enough ground for a five-by-five building
     * centred here — or null. The anchor is the first free block above the
     * ground, which is the floor level every blueprint is measured from.
     *
     * <p>Ground that is nearly flat is built on where it lies. Ground that is
     * not — a hillside, a bank — is built UP to: the anchor is the highest of
     * the nine heights sampled, and the builder fills the columns that stop short
     * of the floor and takes down the tree leaves in the way (BuildGoal). The
     * first version insisted on ground flat to two blocks across seven, and on
     * rolling country found none within sixty-three blocks of the heart: a
     * village that never built a thing.
     */
    @Nullable
    private static BlockPos groundFor(net.minecraft.server.level.ServerLevel level, int x, int z,
                                      boolean needsClearance, int heartGround, int laps, @Nullable Why why) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (!level.hasChunk((x + dx) >> 4, (z + dz) >> 4)) return null;
                int h = com.jrpetty.mcassistant.entity.goal.BuildGoal.groundTop(level, x + dx, z + dz);
                // The ground itself has to be dry and something a wall can stand on.
                net.minecraft.world.level.block.state.BlockState top =
                    level.getBlockState(new BlockPos(x + dx, h - 1, z + dz));
                if (!top.getFluidState().isEmpty() || !top.isSolid()) { why(why, WET); return null; }
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
            }
        }
        int slope = hi - lo;
        if (slope > 4 + Math.min(laps, 2)) {                 // a cliff, not a lot
            why(why, CLIFF);
            if (why != null) why.lastCliff = x + "," + z + " from " + lo + " to " + hi;
            return null;
        }
        int y = slope <= 2 ? (lo + hi) / 2 : hi;
        // Level ground is not enough: it has to be ground the heart's people can walk to.
        // A plateau forty blocks above the village is flat and is nobody's lot.
        if (heartGround != Integer.MIN_VALUE && Math.abs(y - heartGround) > LOT_RISE + 8 * Math.min(laps, 2)) { why(why, HEIGHT); return null; }
        BlockPos at = new BlockPos(x, y, z);
        if (!needsClearance) return at;
        // Something already stands here — a house, a field, a rock — if much of the
        // footprint is not open air or something soft. A tree is not counted: the
        // builder takes its leaves and trunk down (BuildGoal), and gets the wood.
        int blocked = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos c = at.offset(dx, dy, dz);
                    net.minecraft.world.level.block.state.BlockState st = level.getBlockState(c);
                    if (com.jrpetty.mcassistant.entity.goal.BuildGoal.soft(st) || st.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
                    if (st.is(net.minecraft.tags.BlockTags.LOGS)
                            && com.jrpetty.mcassistant.entity.goal.BuildGoal.isTreeLog(level, c)) continue;
                    blocked++;
                }
            }
        }
        if (blocked > 10 + 12 * Math.min(laps, 2)) { why(why, BLOCKED); return null; }
        return at;
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
