package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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

    /** A full village, and the trades it wants in it, the weights reckoned
     *  against ten: the farmers first and most (seven in ten before the later
     *  trades take their shares), three miners for stone and metal, two
     *  woodcutters for every build, one smelter to turn the ore into tools. */
    public static final int VILLAGE_SIZE = 10;

    /**
     * What a settlement wants, and when it starts wanting it. The first ten
     * are the village proper — farmers most of all to feed it, miners for stone
     * and metal, woodcutters to supply every build, a smelter to turn ore into
     * tools, a fisher and a hunter. Past ten a settlement can afford specialists:
     * someone to carry things between the trades, someone to keep the stores,
     * then a pen and a watch.
     *
     * <p>{@code from} is the headcount at which the trade becomes worth having
     * at all — a village of four has no business keeping a guard.
     */
    private record Slot(AssistantEntity.StationTask trade, int weight, int from, Age age, int max) {
        Slot(AssistantEntity.StationTask trade, int weight, int from) { this(trade, weight, from, Age.WOOD, Integer.MAX_VALUE); }

        /** Does a village of this many, in this age, want the trade at all? */
        boolean wanted(int total, Age at) { return total >= from && at.ordinal() >= age.ordinal(); }

        /** Its share of a village this size, never past the most the village wants of it. */
        double target(int total) { return Math.min(max, weight * total / (double) VILLAGE_SIZE); }
    }

    private static final List<Slot> SLOTS = List.of(
        // [farms] Seven in ten, up from four: once the fishers, hunters, couriers, the watch and the crafts
        // took their shares, four in ten came out as three farmers in a town of ten and five in a town of
        // thirty, and the long game's larder emptied every few weeks. Now nearly two in five of a working
        // town farm (four or five at ten, eight at thirty), and the food trades together about half.
        new Slot(AssistantEntity.StationTask.FARM, 7, 1),
        new Slot(AssistantEntity.StationTask.MINE, 3, 2),
        new Slot(AssistantEntity.StationTask.WOOD, 2, 3),
        new Slot(AssistantEntity.StationTask.SMELT, 1, 6),
        // The watch comes BEFORE the carrier and the storekeeper. Settlements
        // are founded at eight to twelve and there is no way for one to grow,
        // so a guard at sixteen was a guard no village was ever going to have
        // — which made the armour, the priority and the whole watch a thing
        // that only existed on paper. A village of ten has no watch at all.
        new Slot(AssistantEntity.StationTask.GUARD, 1, 11),
        // The couriers: every worker's output waits in its production chest at its plot for one of
        // them to bring it in to the storehouse, so the first comes early, and more with the plots.
        new Slot(AssistantEntity.StationTask.HAUL, 1, 6),
        new Slot(AssistantEntity.StationTask.STORE, 1, 13),
        new Slot(AssistantEntity.StationTask.RANCH, 1, 14),
        // [economy] Fishers from eight (a town of fifteen had none, and no fish), and more when the larder is short (Leader).
        new Slot(AssistantEntity.StationTask.FISH, 1, 8),
        // The crafts, as a village grows into them: each wants its age and its building
        // (Crafts, Cafe), and one or two hands at most, however big the town.
        new Slot(AssistantEntity.StationTask.COOK, 1, 14, Age.STONE, 2),
        new Slot(AssistantEntity.StationTask.SMITH, 1, 16, Age.IRON, 1),
        new Slot(AssistantEntity.StationTask.TAILOR, 1, 18, Age.STONE, 1),
        new Slot(AssistantEntity.StationTask.SHOP, 1, 18, Age.IRON, 1),
        new Slot(AssistantEntity.StationTask.BEEKEEP, 1, 20, Age.STONE, 1),
        // The brewer and the enchanter an age sooner than they were: a town of seventy-four in
        // the Iron Age had neither, and few villages ever saw the Nether Age at all.
        new Slot(AssistantEntity.StationTask.BREW, 1, 22, Age.IRON, 1),
        new Slot(AssistantEntity.StationTask.ENCHANT, 1, 24, Age.DIAMOND, 1),
        // Scouts once the village is a town of forty: one or two, out every morning (Scouts).
        new Slot(AssistantEntity.StationTask.SCOUT, 1, Scouts.FROM, Age.WOOD, 2),
        // Hunters, out past the fields after game (VillageFolkEntity.huntWork).
        // [economy] From ten, one to every ten, up to four (more when the larder is short: Leader), while there is game.
        new Slot(AssistantEntity.StationTask.HUNT, 1, 10, Age.WOOD, 4),
        // The banker, once the bank stands: one, and chosen for its nature (Bank.appoint), not by who asks first.
        new Slot(AssistantEntity.StationTask.BANK, 1, Bank.FROM, Age.IRON, 1),
        // [caves] The cave dwellers: a team of two from twenty-five in the Iron Age, three at sixty, four at a hundred,
        // chosen by the town from its most skilled (CaveDwellers.team, appoint).
        new Slot(AssistantEntity.StationTask.CAVE, 1, CaveDwellers.FROM, Age.IRON, CaveDwellers.MOST),
        // [transport] The ferryman: one, while the town's ferry runs (Ferries), whatever its size and age.
        new Slot(AssistantEntity.StationTask.FERRY, 1, 1, Age.WOOD, 1),
        // [fletcher] The fletcher: from the Stone Age, once the watch carries bows (or the range stands); one, two at sixty (Fletchers).
        new Slot(AssistantEntity.StationTask.FLETCHER, 1, 1, Age.STONE, 2),
        // [golems] The golem keeper: from the Iron Age, after two raids in a fortnight or at sixty folk; one (Golems).
        new Slot(AssistantEntity.StationTask.GOLEMS, 1, 1, Age.IRON, 1),
        // [fireworks] The fireworks maker: one, in a Stone Age town of eight that has kept its festivals and has gunpowder
        // put by, once its powder hut stands; chosen by the town for its nature (FireworksMaker.appoint).
        new Slot(AssistantEntity.StationTask.FIREWORKS, 1, FireworksMaker.FROM, Age.STONE, 1));

    /** Forget every settlement. For tests, which share one JVM and would
     *  otherwise inherit each other's villages. */
    /** Tests only: put a village straight into an age. */
    public static void ageForTests(UUID id, Age age) { AGE.put(id, age); }

    /** Tests: the stores counted afresh on the next look. */
    public static void resetStockForTests() { STOCK_TICK.clear(); STEADY.clear(); }

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
        String given = com.jrpetty.mcassistant.village.Ledger.note(villageId, "name");
        if (given != null && !given.isEmpty()) return given;
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

    /** The leader is dead: nobody leads until the village looks for someone to stand in (chooseElder). */
    public static void elderGone(UUID villageId, UUID who) {
        Elder e = ELDERS.get(villageId);
        if (e != null && e.id().equals(who)) ELDERS.remove(villageId);
    }

    public static String elderName(UUID villageId) {
        Elder e = ELDERS.get(villageId);
        return e == null ? "" : e.name();
    }

    /** Once a day: who does the village look up to? */
    /** The day each village last held an election (Assemblies). */
    private static final Map<UUID, Long> ELECTED_ON = new ConcurrentHashMap<>();

    /** Elected: the elder until the next election, or until it is gone. */
    public static void electElder(UUID villageId, VillageFolkEntity f, long day) {
        electElder(villageId, f.getUUID(), f.displayNameCap(), day, f);
    }

    /** Elected (Elections): kept in the Ledger, so the one the village chose leads its whole term, restarts and all. */
    public static void electElder(UUID villageId, UUID who, String name, long day, @Nullable VillageFolkEntity f) {
        Elder was = ELDERS.get(villageId);
        ELDERS.put(villageId, new Elder(who, name, day));
        ELECTED_ON.put(villageId, day);
        com.jrpetty.mcassistant.village.Ledger.note(villageId, "elder", who + "|" + name + "|" + day);
        if (was == null || !was.id().equals(who)) {
            tell(villageId, day, name + " was elected " + Homeland.leaderTitle(villageId));
            if (f != null) f.persona().remember(day, "the village elected me its " + Homeland.leaderTitle(villageId), 9);
        }
    }

    /** The one the village elected, if it still holds office (the Ledger, after a restart). */
    @Nullable
    private static Elder elected(UUID villageId) {
        String saved = com.jrpetty.mcassistant.village.Ledger.note(villageId, "elder");
        if (saved == null || saved.isEmpty()) return null;
        String[] p = saved.split("\\|", 3);
        try {
            return new Elder(UUID.fromString(p[0]), p.length > 1 ? p[1] : "", p.length > 2 ? Long.parseLong(p[2]) : -1L);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static long electedOn(UUID villageId) {
        return ELECTED_ON.getOrDefault(villageId, -100L);
    }

    /**
     * An election: the three the village thinks most of stand, and every grown folk votes for
     * the one it likes best (itself, if it stands). Returns the votes, most first.
     */
    public static java.util.LinkedHashMap<VillageFolkEntity, Integer> election(UUID villageId, long day) {
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : folkOf(villageId)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.persona().rolled()) folk.add(f);
        }
        Map<VillageFolkEntity, Integer> esteem = new java.util.HashMap<>();
        for (VillageFolkEntity c : folk) {
            int score = (int) Math.min(30, Math.max(0, day - c.persona().since()));
            for (VillageFolkEntity o : folk) if (o != c) score += o.life().affinity(c.getUUID());
            score += Homeland.leaderFit(villageId, c);                    // the nature the land asks for in a leader
            esteem.put(c, score);
        }
        List<VillageFolkEntity> standing = new ArrayList<>(folk);
        standing.sort((a, b) -> Integer.compare(esteem.get(b), esteem.get(a)));
        if (standing.size() > 3) standing = new ArrayList<>(standing.subList(0, 3));
        Map<VillageFolkEntity, Integer> votes = new java.util.HashMap<>();
        for (VillageFolkEntity c : standing) votes.put(c, 0);
        for (VillageFolkEntity voter : folk) {
            VillageFolkEntity pick = null;
            int best = Integer.MIN_VALUE;
            for (VillageFolkEntity c : standing) {
                int like = (c == voter ? 60 : voter.life().affinity(c.getUUID())) + Homeland.leaderFit(villageId, c) / 2;
                if (like > best) { best = like; pick = c; }
            }
            if (pick != null) votes.merge(pick, 1, Integer::sum);
        }
        List<VillageFolkEntity> order = new ArrayList<>(votes.keySet());
        order.sort((a, b) -> votes.get(b).equals(votes.get(a))
            ? Integer.compare(esteem.get(b), esteem.get(a)) : Integer.compare(votes.get(b), votes.get(a)));
        java.util.LinkedHashMap<VillageFolkEntity, Integer> out = new java.util.LinkedHashMap<>();
        for (VillageFolkEntity c : order) out.put(c, votes.get(c));
        return out;
    }

    public static void chooseElder(UUID villageId, long day) {
        Elder now = ELDERS.get(villageId);
        if (now != null && now.day() == day) return;
        // An elected leader serves its whole term (Elections), whether or not it happens to be about
        // just now; only its death (Elections.vacancy) ends it early.
        Elder office = elected(villageId);
        if (office != null) {
            ELDERS.put(villageId, new Elder(office.id(), office.name(), day));
            if (office.day() >= 0) ELECTED_ON.putIfAbsent(villageId, office.day());
            return;
        }
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
            score += Homeland.leaderFit(villageId, c);                                // the nature the land asks for
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
            case "storehouse" -> "the Village Storehouse";
            case "house" -> "a new house";
            case "hall" -> "the meeting hall";
            case "townhall" -> "the leader's hall";
            case "court" -> "the courtyard before the board";
            case "pen" -> "the animal pen";
            case "guesthouse" -> "a house for the village's honoured guest";
            case "fountain" -> "the fountain on the square";
            case "manor" -> "a manor house";
            case "belltower" -> "the bell tower";
            case "flats" -> "a block of flats";                   // [flats]
            case "pitch" -> "the football pitch";                 // [batchC]
            case "range" -> "the archery range";                  // [batchC]
            case "postoffice" -> "the post office";               // [batchF]
            case "townlibrary" -> "the town library";             // [library]
            case "statue" -> "the statue on the square";          // [batchF]
            case "trainingyard" -> "the training yard";            // [war-prep]
            case "firestation" -> "the fire station";              // [disasters]
            case "lodge" -> "the Delvers' Lodge";                 // [caves]
            case "fletcher" -> "the fletcher's hut";              // [fletcher]
            case "golemyard" -> "the golem yard";                 // [golems]
            case "powderhut" -> "the powder hut";                // [fireworks]
            default -> "the " + structure;
        };
    }

    public static void resetForTests() {
        MADE_UP.clear();
        Bank.resetForTests();
        Culture.resetForTests();                                   // [batchD] the banner's works, the customs, the theatre, the band
        Museum.resetForTests();
        Library.resetForTests();                                   // [library] the writing, the readers, the seats
        Storehouses.resetForTests();
        Storekeeping.resetForTests();
        Couriers.resetForTests();
        Toolrack.resetForTests();
        Workshop.resetForTests();
        Store.resetForTests();                                     // [econ-store]
        Sweepers.resetForTests();
        Meals.resetForTests();
        Stables.resetForTests();
        TownLook.resetForTests();           // [batchE] the town's look
        VillageBoards.resetForTests();
        Retiring.resetForTests();
        HAS_STORES.clear();
        REQUESTED.clear();
        ELECTED_ON.clear();
        Assemblies.resetForTests();
        TownCalendar.resetForTests();       // the town bell, birthdays, Founding Day
        GLUT.clear();
        GLUT_AT.clear();
        GREW.clear();
        Standing.resetForTests();
        Gatherings.resetForTests();
        Families.resetForTests();           // pets, games, suppers, stories, gardens, remembrance
        ELDERS.clear();
        NEWS.clear();
        AGED_ON.clear();
        ALL.clear();
        AGE.clear();
        BUILT.clear();
        BUILT_AT.clear();
        TownLife.resetForTests();
        Crafts.resetForTests();
        Raids.resetForTests();
        Contentment.resetForTests();
        RestDay.resetForTests();
        Sport.resetForTests();              // [batchC] the pitch, the matches, the league, the contests, the range
        Tavern.resetForTests();
        Council.resetForTests();
        Referendums.resetForTests();        // [civic] the town's votes, great works and newcomers
        WarAndPeace.resetForTests();        // [war-peace]
        Laws.resetForTests();
        Diplomacy.resetForTests();
        Envoys.resetForTests();
        TownJobs.resetForTests();
        JobMarket.resetForTests();
        Market.resetForTests();
        PriceIndex.resetForTests();         // [econ-prices] the towns' prices
        Purchases.resetForTests();          // [econ-prices] the folk's accounts at the counter
        JobWorth.resetForTests();           // [econ-wages] the day's pay scales
        Homeland.resetForTests();
        Economy.resetForTests();
        Scouts.resetForTests();
        CaveDwellers.resetForTests();       // [caves]
        Fletchers.resetForTests();          // [fletcher]
        Golems.resetForTests();             // [golems]
        FireworksMaker.resetForTests();     // [fireworks]
        FireworkShows.resetForTests();      // [fireworks]
        Fashion.resetForTests();            // [fashion] the season's looks, the tailor's book, the shows
        Quests.resetForTests();
        Services.resetForTests();
        PlayerServices.resetForTests();     // [players] the nights' bounties, the milestones' looks
        Land.resetForTests();
        Grow.resetForTests();
        Waterfront.resetForTests();
        Orders.resetForTests();
        Leader.resetForTests();
        Larder.resetForTests();             // [economy] the mouths the books were made up for
        Strays.resetForTests();             // [economy] stock carried about that is the village's
        PackedLunch.resetForTests();        // [economy] the far hands' meals
        PutAway.resetForTests();            // [economy] the day's work put away twice a day
        Fields.resetForTests();             // [economy] the tended fields, and folk stuck fast
        Mishap.resetForTests();             // [economy] the deaths, for the books and the watch
        Ages.resetForTests();
        Interiors.resetForTests();
        Decor.resetForTests();
        Luxuries.resetForTests();
        Palettes.resetForTests();
        Court.resetForTests();
        School.resetForTests();
        Annals.resetForTests();
        Trades.resetForTests();
        Links.resetForTests();
        Asks.resetForTests();
        Nether.resetForTests();
        Drover.resetForTests();
        Cafe.resetForTests();
        Woods.resetForTests();              // [wf] the woodcutters' stumps and errands
        FireBrigade.resetForTests();        // [wf] the fires and the hands at them
        Weather.resetForTests();            // [wf] the storm (and a test's storm let go), the rods looked at
        Civics.resetForTests();             // [batchF] the post, petitions, the meeting, wardens, the fund, searches, favours
        Crime.resetForTests();              // [crime] the cases, the folk's records, the plans, the court
        Weave.resetForTests();              // [weave] the homeless waiting to go, the day's looks; the round off again
        Roads.reset();
        LAST_PROJECT.clear();
        POP.clear();
        LAST_BIRTH.clear();
        SITES.clear();
        LOT_TAKEN.clear();
        BAD_LOTS.clear();
        WHY_NOT.clear();
        LAPS.clear();
        DEFERRED.clear();
        DEFER_WHY.clear();
        Elections.resetForTests();
        Homes.resetForTests();
        FOUNDED.clear();
        CREWS.clear();
        LAST_BAKE.clear();
        STOCK.clear();
        STOCK_TICK.clear();
        NO_WATER.clear();                   // [sf] the water no fisher could fish
        STEADY.clear();                     // [sf] the stores' last good morning reading
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
        // The land it stands in, looked over now, and a name that fits it (Homeland).
        if (level instanceof net.minecraft.server.level.ServerLevel land) {
            Homeland.Land l = Homeland.survey(land, v);
            if (Homeland.known(v.id()) != null) {
                long a = v.id().getMostSignificantBits();
                String head = NAME_HEADS[(int) Math.floorMod(a ^ (a >>> 29), (long) NAME_HEADS.length)];
                String named = Homeland.nameFor(v.id(), head, l);
                if (named != null) com.jrpetty.mcassistant.village.Ledger.note(v.id(), "name", named);
            }
        }
        com.jrpetty.mcassistant.village.Chronicle.record(v.id(), level.getDayTime() / 24000L,
            name(v.id()) + " was founded");
        // The founders' notice board, on the square: what the village is doing, how it is
        // getting on, and what it is working towards (VillageBoards).
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            com.jrpetty.mcassistant.Guard.run("village board", () -> VillageBoards.raiseFor(server, v));
        }
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
        CREWS.remove(villageId);
        LAST_BAKE.remove(villageId);
        ALL.remove(villageId);
        AGE.remove(villageId);
        BUILT.remove(villageId);
        BUILT_AT.remove(villageId);
        com.jrpetty.mcassistant.village.Ledger.forget(villageId);
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
        return mayBirth(villageId, gameTime, 1);
    }

    /** As mayBirth, with the gap between births stretched this many times (lean times). */
    public static boolean mayBirth(UUID villageId, long gameTime, int stretch) {
        int folk = Math.max(1, headcount(villageId));
        long gap = Math.max(600L, Math.min(6000L, 12000L / (folk / 4 + 1))) * Math.max(1, stretch);
        // A sociable, cheerful or generous leader's village has its children sooner (Leader.family).
        gap = (long) (gap * Leader.family(villageId));
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

    /** The beds this settlement's folk call their own (the heads). */
    public static java.util.Set<BlockPos> bedsClaimed(@Nullable UUID villageId) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        for (AssistantEntity a : folkOf(villageId)) if (a.bedPos() != null) out.add(a.bedPos());
        out.addAll(CaveDwellers.awayBeds(villageId));          // [caves] the cave team's, away on a trip, stay theirs
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
     * newcomer — so ten folk settle into farmers first and most, then miners,
     * woodcutters, a smelter, a fisher and a hunter without anyone being told,
     * and a village that loses its smelter replaces it with the next one to
     * look for work.
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
        Age at = villageId == null ? Age.WOOD : ageOf(villageId);
        double fit = fit(villageId, total, hands(folk, total), at);
        AssistantEntity.StationTask best = AssistantEntity.StationTask.FARM;
        double bestDeficit = -Double.MAX_VALUE;
        int bestWeight = 0;
        for (Slot slot : SLOTS) {
            if (!wantedHere(slot, villageId, total, at)) continue;      // too small (or too young) to want one yet
            if (!craftReady(villageId, slot.trade())) continue;   // a smith with no smithy has nothing to work at
            if (slot.trade() == AssistantEntity.StationTask.BANK) continue;   // the banker is appointed (Bank.appoint)
            if (slot.trade() == AssistantEntity.StationTask.CAVE) continue;   // [caves] the team is chosen (CaveDwellers.appoint)
            if (slot.trade() == AssistantEntity.StationTask.FIREWORKS) continue;   // [fireworks] the maker is chosen (FireworksMaker.appoint)
            double target = target(villageId, slot, total) * fit;
            double deficit = target - have.getOrDefault(slot.trade(), 0);
            // The first hand of a craft the village has grown into comes before one more of a trade
            // it already has plenty of: a craft is one or two hands however big the town, and its
            // share never outweighed the farms' and the mines', which grow with every newcomer.
            if (slot.trade().isCraft() && have.getOrDefault(slot.trade(), 0) == 0) deficit += 2.0;
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
        Age at = villageId == null ? Age.WOOD : ageOf(villageId);
        for (Slot slot : SLOTS) {
            if (!wantedHere(slot, villageId, total, at)) continue;   // too small (or too young) to want one yet
            if (slot.age() != Age.WOOD) continue;    // crafts below, once the trades have their hands
            if (!craftReady(villageId, slot.trade())) continue;   // nowhere to work at it yet
            if (have.getOrDefault(slot.trade(), 0) == 0) return slot.trade();
        }
        // The watch down to a handful: in the long game the guards fell one by one (old age, the
        // night's fights) from fourteen to one in a village of eighty, and nobody new took it up.
        double fit = fit(villageId, total, hands(folk, total), at);
        for (Slot slot : SLOTS) {
            if (slot.trade() != AssistantEntity.StationTask.GUARD || !wantedHere(slot, villageId, total, at)) continue;
            double want = target(villageId, slot, total);
            if (want >= 2.0 && have.getOrDefault(slot.trade(), 0) < Math.max(2.0, want * fit)) return slot.trade();
        }
        // A craft the village has grown into and has the building for, with nobody at it: a hand
        // from a trade with more than its share takes it up. Crafts were only ever taken by
        // newcomers, and newcomers always found the fields or the mines shorter — a village of
        // eighty had no beekeeper and no brewer in fifty days, and its first cook came at day 35.
        for (Slot slot : SLOTS) {
            if (slot.age() == Age.WOOD || !wantedHere(slot, villageId, total, at)) continue;
            if (slot.trade() == AssistantEntity.StationTask.BANK) continue;   // the banker is appointed (Bank.appoint)
            if (slot.trade() == AssistantEntity.StationTask.CAVE) continue;   // [caves] the team is chosen (CaveDwellers.appoint)
            if (slot.trade() == AssistantEntity.StationTask.FIREWORKS) continue;   // [fireworks] the maker is chosen (FireworksMaker.appoint)
            if (have.getOrDefault(slot.trade(), 0) > 0) continue;
            if (craftReady(villageId, slot.trade())) return slot.trade();
        }
        // A trade well short while others have hands to spare — the farms cut back over a full
        // larder (weighGluts), the mines short of hands for the iron. Only a hand and a half
        // short, and only ever from a trade over its own share (changedTrade asks overStaffed),
        // so nobody is shuffled back and forth.
        AssistantEntity.StationTask most = null;
        double worst = 1.5;
        for (Slot slot : SLOTS) {
            if (!wantedHere(slot, villageId, total, at) || slot.trade().isCraft() || slot.trade() == AssistantEntity.StationTask.GUARD) continue;
            if (slot.trade() == AssistantEntity.StationTask.CAVE) continue;   // [caves] the team is chosen (CaveDwellers.appoint)
            if (!craftReady(villageId, slot.trade())) continue;
            double short_ = target(villageId, slot, total) * fit - have.getOrDefault(slot.trade(), 0);
            if (short_ > worst) { worst = short_; most = slot.trade(); }
        }
        return most;
    }

    /**
     * Every trade well short of hands (a hand and a half under its share, as vacancy's last look
     * reckons it), the shortest first: where a hand from a trade over its share goes when the
     * shortest has no ground to be had. Vacancy names only the one, and a village of a hundred whose
     * shortest was the fishers, with no water within reach of the town, moved nobody anywhere — not
     * to the couriers four hands short, nor the pen — while its woodcutters stood idle by the score.
     */
    public static List<AssistantEntity.StationTask> shortOfHands(@Nullable UUID villageId) {
        List<AssistantEntity> folk = folkOf(villageId);
        int total = folk.size();
        List<AssistantEntity.StationTask> out = new ArrayList<>();
        if (total <= 1) return out;
        Map<AssistantEntity.StationTask, Integer> have = new EnumMap<>(AssistantEntity.StationTask.class);
        for (AssistantEntity a : folk) {
            if (a.stationTask() != AssistantEntity.StationTask.NONE) have.merge(a.stationTask(), 1, Integer::sum);
        }
        Age at = villageId == null ? Age.WOOD : ageOf(villageId);
        double fit = fit(villageId, total, hands(folk, total), at);
        Map<AssistantEntity.StationTask, Double> by = new EnumMap<>(AssistantEntity.StationTask.class);
        for (Slot slot : SLOTS) {
            if (!wantedHere(slot, villageId, total, at) || slot.trade().isCraft() || slot.trade() == AssistantEntity.StationTask.GUARD) continue;
            if (slot.trade() == AssistantEntity.StationTask.CAVE) continue;   // [caves] the team is chosen (CaveDwellers.appoint)
            if (!craftReady(villageId, slot.trade())) continue;
            double short_ = target(villageId, slot, total) * fit - have.getOrDefault(slot.trade(), 0);
            if (short_ > 1.5) { by.put(slot.trade(), short_); out.add(slot.trade()); }
        }
        out.sort((a, b) -> Double.compare(by.get(b), by.get(a)));
        return out;
    }

    /**
     * What every trade's share is scaled by so that the shares add up to the hands the village
     * has. Each trade's weight is reckoned against a village of ten, and once a village has grown
     * into the later trades they add up to more than it has — half as many again at thirty — and
     * its children count toward its size but work at nothing. Then every trade read as short and
     * none as over: nobody could ever move to the watch or a craft, every child that grew up
     * went to its parent's fields or mine, and a village of thirty had one guard and no tailor.
     */
    static double fit(@Nullable UUID villageId, int total, int hands, Age at) {
        double sum = 0;
        for (Slot slot : SLOTS) {
            if (wantedHere(slot, villageId, total, at) && craftReady(villageId, slot.trade())) sum += target(villageId, slot, total);
        }
        return sum <= hands || sum <= 0 ? 1.0 : hands / sum;
    }

    /** The hands a village of this size has to work: everybody but the children. */
    static int hands(List<AssistantEntity> folk, int total) {
        int children = 0;
        for (AssistantEntity a : folk) if (a.isBaby()) children++;
        return Math.max(1, total - children);
    }

    /**
     * Has the trade somewhere to work yet? No shopkeeper before there is a shop, no cook
     * before the café, no brewer before the brewery, no smith before the smithy; no
     * storekeeper and no carrier before there is a storehouse to keep and carry to. (The
     * fields, the woods, the mines, the forge, the pasture, the pond and the hives a hand
     * makes for itself, so those want nothing built first.)
     */
    static boolean craftReady(@Nullable UUID villageId, AssistantEntity.StationTask trade) {
        // [sf] A fisher needs water it can get a line into and walk to. Where its fishers found none within reach
        // of the town, the village wants none for a few days: the mountain town kept one fisher on "0 fish" for
        // good, and every newcomer it sent to the trade looked for the water that was not there.
        if (trade == AssistantEntity.StationTask.FISH) return !dryForFishers(villageId);
        if (trade == AssistantEntity.StationTask.CAVE) return CaveDwellers.ready(villageId);   // [caves] a few miners and a watch first
        if (trade == AssistantEntity.StationTask.FERRY) return Ferries.wanted(villageId);      // [transport] while the ferry runs
        if (trade == AssistantEntity.StationTask.FLETCHER) return Fletchers.wanted(villageId); // [fletcher] a watch with bows, or the range
        if (trade == AssistantEntity.StationTask.GOLEMS) return Golems.wanted(villageId);      // [golems] the raids, or sixty folk
        if (trade == AssistantEntity.StationTask.FIREWORKS) return FireworksMaker.ready(villageId);   // [fireworks] opened, and its hut up
        if (trade == AssistantEntity.StationTask.STORE || trade == AssistantEntity.StationTask.HAUL) {
            return villageId != null && (Storehouses.stands(villageId) || hasBuilt(villageId, "storage")
                || builtAt(villageId, "storage") != null);
        }
        if (!trade.isCraft() || trade == AssistantEntity.StationTask.BEEKEEP) return true;
        if (WatchKit.beforeItsBuilding(villageId, trade)) return true;     // [guard-kit] the watch's makers, before their buildings
        String building = VillageFolkEntity.buildingFor(trade);
        return building == null || (villageId != null && (hasBuilt(villageId, building) || builtAt(villageId, building) != null));
    }

    /** [sf] How long a village wants no fisher after its fishers found no water within reach of the town. */
    public static final long NO_WATER_FOR = 3 * 24000L;
    /** [sf] When each village's fishers last found no water within reach (game time); kept in the ledger too. */
    private static final Map<UUID, Long> NO_WATER = new ConcurrentHashMap<>();

    /** [sf] No open water the town can walk to, as its fisher found (VillageFolkEntity.newWaters): no fisher wanted for now. */
    public static void noWaterForFishers(@Nullable UUID villageId, long gameTime) {
        if (villageId == null) return;
        if (gameTime > CLOCK) CLOCK = gameTime;
        NO_WATER.put(villageId, gameTime);
        com.jrpetty.mcassistant.village.Ledger.note(villageId, "fish.dry", Long.toString(gameTime));
    }

    /** [sf] Has the village found no water for a fisher in the last few days? */
    public static boolean dryForFishers(@Nullable UUID villageId) {
        if (villageId == null) return false;
        Long at = NO_WATER.get(villageId);
        if (at == null) {
            String kept = com.jrpetty.mcassistant.village.Ledger.note(villageId, "fish.dry");
            try {
                at = kept == null || kept.isEmpty() ? Long.MIN_VALUE : Long.parseLong(kept);
            } catch (NumberFormatException e) {
                at = Long.MIN_VALUE;
            }
            NO_WATER.put(villageId, at);
        }
        if (at == Long.MIN_VALUE) return false;
        long since = CLOCK - at;
        return since >= 0 && since < NO_WATER_FOR;
    }

    /** Is this village big enough (and far enough on) to want this trade at all? */
    public static boolean wants(@Nullable UUID villageId, AssistantEntity.StationTask trade) {
        List<AssistantEntity> folk = folkOf(villageId);
        int total = Math.max(1, Math.max(folk.size(), headcount(villageId)));
        Age at = villageId == null ? Age.WOOD : ageOf(villageId);
        for (Slot slot : SLOTS) if (slot.trade() == trade) return wantedHere(slot, villageId, total, at);
        return false;
    }

    /** A trade's share of a village this size, as the elder's order shapes it (Orders): the order's
     *  trades a little more, every other a little less. */
    /**
     * Does a village this size, in this age, want the trade? Sooner than anywhere else if its land
     * lives by it (Homeland): a coast town fishes from its first days.
     */
    static boolean wantedHere(Slot slot, @Nullable UUID villageId, int total, Age at) {
        if (slot.wanted(total, at)) return true;
        int sooner = Homeland.sooner(villageId, slot.trade());
        return sooner > 0 && total >= sooner && at.ordinal() >= slot.age().ordinal();
    }

    static double target(@Nullable UUID villageId, Slot slot, int total) {
        int boost = Orders.boost(villageId, slot.trade());
        // The land leans the village to the trades it lives by (Homeland): more fishers on the
        // coast, more woodcutters and hunters in the forest, more miners in the hills.
        double t = (slot.weight() + boost) * total / (double) VILLAGE_SIZE * Orders.scale(villageId)
            * glut(villageId, slot.trade()) * Homeland.lean(villageId, slot.trade());
        if (slot.trade() == AssistantEntity.StationTask.CAVE) t = CaveDwellers.team(total);   // [caves] two, three at sixty, four at a hundred
        if (slot.trade() == AssistantEntity.StationTask.FERRY) t = Ferries.wanted(villageId) ? 1.0 : 0.0;  // [transport] one ferryman
        if (slot.trade() == AssistantEntity.StationTask.FLETCHER) t = Fletchers.wanted(villageId) ? Fletchers.hands(villageId) : 0.0;   // [fletcher]
        if (slot.trade() == AssistantEntity.StationTask.GOLEMS) t = Golems.wanted(villageId) ? 1.0 : 0.0;   // [golems] one keeper
        if (slot.trade() == AssistantEntity.StationTask.FIREWORKS) t = FireworksMaker.ready(villageId) ? 1.0 : 0.0;   // [fireworks] one maker
        // A courier for every five workers out on plots of their own (their production chests).
        if (slot.trade() == AssistantEntity.StationTask.HAUL && villageId != null) {
            int producers = 0;
            for (AssistantEntity a : folkOf(villageId)) if (VillageFolkEntity.producer(a.stationTask())) producers++;
            t = Math.max(t, Math.ceil(producers / 5.0));
        }
        // And the street sweeper (Sweepers): one of the couriers takes up the broom in a town of sixteen with a
        // storehouse, one more for every sixteen after, so a hand more for each, and the runs not left short.
        if (slot.trade() == AssistantEntity.StationTask.HAUL && villageId != null) t += Sweepers.wanted(villageId);
        // A hungry village wants its food-makers: half as many farmers and fishers again while
        // the larder is low. It is fed by its own fields and waters, and nothing else.
        // And the leader, reading its books, wants more again in a famine (Leader.foodFactor).
        double leader = Leader.foodFactor(villageId, slot.trade());
        if ((slot.trade() == AssistantEntity.StationTask.FARM || slot.trade() == AssistantEntity.StationTask.FISH)
                && villageId != null && Market.hungry(villageId)) leader = Math.max(leader, 1.5);
        t *= leader;
        // [econ-trade] A standing trade deal leans the shares (TradeDeals.lean): fewer farmers where the bread comes in
        // reliably from a partner, more miners where stone is promised to one; back at once if the deliveries stop.
        t *= TradeDeals.lean(villageId, slot.trade());
        // [fleet] A town with a fishing fleet wants a fisher for every one of its boats (Fleet).
        if (slot.trade() == AssistantEntity.StationTask.FISH) t = Math.max(t, Fleet.handsWanted(villageId));
        // Houses waiting on beds want the wool: twice the ranchers (their sheep) till they are made up.
        if (slot.trade() == AssistantEntity.StationTask.RANCH && villageId != null && Market.bedsShort(villageId) >= 6) t *= 2.0;
        // [economy] The watch grows with the town, and by half again when monsters have been killing its folk (Mishap.watch).
        if (slot.trade() == AssistantEntity.StationTask.GUARD) t = Mishap.watch(villageId, t, total, CLOCK);
        int max = slot.max() == Integer.MAX_VALUE ? Integer.MAX_VALUE
            : slot.max() + Math.max(0, boost) + Homeland.extraMost(villageId, slot.trade());
        // And the shop's hands at its bench (Workshop): the shop's share is its keeper and the hands it wants.
        double hands = slot.trade() == AssistantEntity.StationTask.SHOP && villageId != null
            ? Workshop.handsWanted(villageId) + ShopRoles.staffWanted(villageId) : 0;   // [econ-store] and its assistants and stock keeper
        // [war-prep] On a war footing the watch's share rises to meet the enemy, out of the trades a town can spare (WarFooting).
        return WarFooting.share(villageId, slot.trade(), Math.max(0.0, Math.min(max + hands, t + hands)));
    }

    /** How many hands a trade has over (positive) or under (negative) its share, as the order has
     *  it; a trade the village has no use for yet counts as having none to spare and none short. */
    public static double share(@Nullable UUID villageId, AssistantEntity.StationTask trade) {
        List<AssistantEntity> folk = folkOf(villageId);
        int total = Math.max(1, Math.max(folk.size(), headcount(villageId)));
        Age at = villageId == null ? Age.WOOD : ageOf(villageId);
        int have = 0;
        for (AssistantEntity a : folk) if (a.stationTask() == trade) have++;
        if (trade == AssistantEntity.StationTask.CAVE) return have - CaveDwellers.wanted(villageId);   // [caves] the team, whole
        for (Slot slot : SLOTS) {
            if (slot.trade() != trade) continue;
            if (!wantedHere(slot, villageId, total, at) || !craftReady(villageId, trade)) return 0.0;
            return have - target(villageId, slot, total) * fit(villageId, total, hands(folk, total), at);
        }
        return 0.0;
    }

    /**
     * Is this trade carrying more hands than the village's shape calls for?
     * The guard on re-badging: a village must never strip a trade that is
     * merely busy to staff one that is empty.
     */
    public static boolean overStaffed(@Nullable UUID villageId, AssistantEntity.StationTask trade) {
        if (trade == AssistantEntity.StationTask.NONE) return true;
        // A trade with nowhere to work at it (a shopkeeper with no shop) has nobody it needs.
        if (!craftReady(villageId, trade)) return true;
        List<AssistantEntity> folk = folkOf(villageId);
        int total = folk.size();
        if (total <= 0) return false;
        int have = 0;
        for (AssistantEntity a : folk) if (a.stationTask() == trade) have++;
        if (trade == AssistantEntity.StationTask.CAVE) return have > CaveDwellers.wanted(villageId);   // [caves] the team, whole
        Age at = villageId == null ? Age.WOOD : ageOf(villageId);
        for (Slot slot : SLOTS) {
            if (slot.trade() != trade) continue;
            double target = target(villageId, slot, total) * fit(villageId, total, hands(folk, total), at);
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
        weighGluts(level, villageId, all);
        return all;
    }

    /** How much each trade's share is cut for what its stores are drowning in (1: not at all). */
    private static final Map<UUID, Map<AssistantEntity.StationTask, Double>> GLUT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GLUT_AT = new ConcurrentHashMap<>();

    /**
     * A trade whose stores are piled far past anything the village can use gets a smaller share,
     * and the hands go where they are wanted: the long game's village had twenty-three farmers on
     * 1,115 food and six miners on fourteen iron. Food over four larders halves the farms (over
     * two, three quarters); logs and stone likewise past their marks — but the mines are never cut
     * while the age is short of iron or diamonds, which come out of the same holes.
     */
    private static void weighGluts(net.minecraft.server.level.ServerLevel level, UUID villageId, List<Need> wants) {
        long now = level.getGameTime();
        if (now - GLUT_AT.getOrDefault(villageId, -100000L) < 1200L) return;
        GLUT_AT.put(villageId, now);
        Village v = get(villageId);
        if (v == null) return;
        int folk = Math.max(1, headcount(villageId));
        int radius = storesRadius(villageId);
        Map<AssistantEntity.StationTask, Double> cut = new java.util.EnumMap<>(AssistantEntity.StationTask.class);
        int food = stock(level, v.centre(), Task.FOOD, radius);
        food -= TradeDeals.spokenFor(villageId, Task.FOOD);              // [econ-trade] promised to a partner: no glut
        int larder = Math.max(64, larderForBirth(villageId));
        cut.put(AssistantEntity.StationTask.FARM, food > 4 * larder ? 0.5 : food > 2 * larder ? 0.75 : 1.0);
        int logs = stock(level, v.centre(), Task.LOGS, radius);
        logs -= TradeDeals.spokenFor(villageId, Task.LOGS);              // [econ-trade]
        int timber = Math.max(256, com.jrpetty.mcassistant.village.VillageMath.timberWanted(folk));
        cut.put(AssistantEntity.StationTask.WOOD, logs > 4 * timber ? 0.5 : logs > 2 * timber ? 0.75 : 1.0);
        boolean deepShort = false;
        for (Need n : wants) if (n.task() == Task.IRON || n.task() == Task.DIAMOND || n.task() == Task.OBSIDIAN) deepShort = true;
        int stone = stock(level, v.centre(), Task.STONE, radius);
        stone -= TradeDeals.spokenFor(villageId, Task.STONE);            // [econ-trade]
        int stoneMark = Math.max(384, com.jrpetty.mcassistant.village.VillageMath.stoneWanted(folk));
        cut.put(AssistantEntity.StationTask.MINE, deepShort ? 1.0 : stone > 4 * stoneMark ? 0.6 : 1.0);
        GLUT.put(villageId, cut);
    }

    /** The cut a trade's share takes for a glut (Villages.weighGluts). */
    static double glut(@Nullable UUID villageId, AssistantEntity.StationTask trade) {
        Map<AssistantEntity.StationTask, Double> cut = villageId == null ? null : GLUT.get(villageId);
        return cut == null ? 1.0 : cut.getOrDefault(trade, 1.0);
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
        // The stock an age asks for before the next is sized to a village of up to AGE_FOLK: past
        // that, a village growing faster than it gathers chased a mark that moved away from it
        // (a hamlet of forty-six, still in the Wood Age at day sixteen, wanted five hundred and
        // twenty-eight logs put by). The larder still grows with every mouth.
        int ageFolk = Math.min(folk, AGE_FOLK);
        switch (at) {
            case WOOD -> {
                // Timber, a roof, and food coming in. Everything a place needs
                // before it can afford to think about stone.
                need(wants, level, v, "food in the stores", Task.FOOD, foodNow);
                if (built(villageId, "storage") < 1) wants.add(new Need("somewhere to store things", Task.BUILD, 1));
                need(wants, level, v, "timber", Task.LOGS,
                    com.jrpetty.mcassistant.village.VillageMath.timberWanted(ageFolk));
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
                    com.jrpetty.mcassistant.village.VillageMath.stoneWanted(ageFolk));
                need(wants, level, v, "coal", Task.COAL,
                    com.jrpetty.mcassistant.village.VillageMath.coalWanted(ageFolk));
                if (built(villageId, "fortify") < 1) wants.add(new Need("a wall around the village", Task.BUILD, 1));
                // A growing town is always a few beds behind its births, and its houses are planned for them all the
                // same (projectsWantedInOrder). Asking for five beds to spare before the Iron Age held a town of sixty,
                // with its wall, smeltery and hall built and stone and coal enough, in the Stone Age from day 23 to
                // day 44 and on: every new house filled before the next one went up. The age waits on houses only
                // when more than one in ten has no bed.
                if (folk - housing(villageId) > Math.max(2, folk / 10)) {
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
                int r = greatWorks(villageId);
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
        if (task == Task.IRON) have += WatchKit.ironCredit(level, v.id());   // [guard-kit] the watch's armour is iron the age asked for
        if (have < amount) wants.add(new Need(what, task, amount - have));
    }

    /** What the settlement holds, counted from the chests around its heart —
     *  which is what a "storage area" actually means here: the stores are
     *  wherever the village keeps them, near the middle, and every count of
     *  what the place owns reads from exactly that. Cached briefly, because
     *  every hand with a spare moment asks. */
    private static final Map<UUID, long[]> STOCK_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> STOCK = new ConcurrentHashMap<>();

    /** Count the stores afresh at the next asking (tests, and anything that just filled them). */
    public static void forgetStock() {
        STOCK_TICK.clear();
    }

    public static int stock(net.minecraft.server.level.ServerLevel level, BlockPos centre, Task task) {
        return stock(level, centre, task, 32);
    }

    /**
     * How far above and below the heart the village's stores reach: the one height every count
     * of them and every draw on them uses. The plan counted sixty-four up and down while the
     * builder looked thirty-two (and only a hundred and twelve out, against the plan's two
     * hundred and twenty): a mountain town of sixty-six whose heart stands at ninety-five, its
     * plots down the slopes and its mines more than a hundred and fifty blocks off, counted 921
     * logs and 1,245 stone and had been told by its builders, within the two minutes before, that
     * it could not afford a meeting hall whose start wants at most 1,367 of them — and it sat in
     * the Stone Age for forty days.
     */
    public static final int STORES_TALL = 64;

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
        // [sf] Kept by the reach it was counted over as well as the heart: one count over thirty-two blocks
        // (stock(level, centre, task)) answered every count over the whole of a big town's reach for the next
        // ten seconds, the field chests out past thirty-two missing from it.
        UUID key = UUID.nameUUIDFromBytes(("v" + centre.asLong() + "r" + radius).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
                stores = new java.util.ArrayList<>(ZoneChests.around(level, centre, radius, STORES_TALL));
            } finally {
                ZoneChests.askAs(before);
            }
            // [sf] And the goods waiting in the door of a storehouse that has come apart (a unit out of its
            // cube, being laid again): still the village's, and still counted. Skipped as "not a store",
            // the whole storehouse went out of the count for as long as the cube stood broken, which is
            // what one morning in the mountain town looked like: "food 185 stone 151 iron 0" against
            // 1,235, 1,658 and 31 the day before and back to 1,200 the day after, and eight hands sent to
            // the fields for the day.
            // (Only round the heart, where a storehouse stands: Storehouses.REACH.)
            stores.addAll(ZoneChests.waitingGoods(level, centre, Storehouses.REACH, STORES_TALL));
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

    /** [sf] The stores' last good morning reading, by village: what each count read, and the morning it was last doubted. */
    private static final class Steady {
        final int[] good = new int[Task.values().length];
        final long[] doubted = new long[Task.values().length];

        Steady() {
            java.util.Arrays.fill(doubted, Long.MIN_VALUE);
        }
    }

    private static final Map<UUID, Steady> STEADY = new ConcurrentHashMap<>();
    /** [sf] A good reading under this is too small to doubt a fall from: a few loaves either way. */
    static final int STEADY_FLOOR = 48;

    /**
     * [sf] The stores as the morning's decisions read them (Market's hungry village, the leader's books and
     * plan): the count as it stands, unless it has fallen by more than three quarters since the last good one
     * while the storehouse stands. A storehouse does not empty itself overnight; a count that missed it does
     * (a cube come apart and being laid again, a chunk half come back from the disk), and the mountain town
     * read "food 185" against 1,235 the day before and 1,200 the day after, and sent eight hands to the
     * fields for a day. Then the last good reading is used for the day, and the books say so. Only one morning
     * in a row: a second low morning is believed.
     */
    public static int steadyStock(net.minecraft.server.level.ServerLevel level, Village v, Task task, long day) {
        Steady s = STEADY.computeIfAbsent(v.id(), k -> new Steady());
        int i = task.ordinal();
        synchronized (s) {
            int now = stock(level, v.centre(), task, storesRadius(v.id()));
            int good = s.good[i];
            boolean low = good >= STEADY_FLOOR && now * 4 < good && Storehouses.stands(v.id());
            if (low && s.doubted[i] == day) return good;               // doubted already this morning
            if (low && s.doubted[i] != day - 1) {
                s.doubted[i] = day;
                tell(v.id(), day, "the stores read " + now + " " + task.name().toLowerCase(java.util.Locale.ROOT) + " this morning against "
                    + good + " at the last good count, with the storehouse standing: the day was planned on the last good count");
                return good;
            }
            s.good[i] = now;
            return now;
        }
    }

    /** [sf] Tests: the morning's read of the stores on this day. */
    public static int steadyStockForTests(net.minecraft.server.level.ServerLevel level, Village v, Task task, long day) {
        forgetStock();
        return steadyStock(level, v, task, day);
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
        long gap = Math.max(1200L, PROJECT_GAP * 12L / Math.max(12, headcount(villageId)));
        // A town with food to spare and a bed for nearly everyone does not stand about between one building
        // and the next: half a minute, and the next crew is at it (thriving).
        return thriving(villageId) ? Math.min(gap, THRIVING_GAP) : gap;
    }

    /** The wait between projects in a thriving town. */
    static final long THRIVING_GAP = 600L;

    /**
     * Can the town afford to build as fast as it has hands and materials: food to spare (the leader's
     * plan is plenty), a bed for all but a few, and no raid at the gates. Building is what a town does
     * with what it has over; a town short of any of these builds at the old, careful pace.
     */
    public static boolean thriving(UUID villageId) {
        int folk = headcount(villageId);
        if (folk == 0) return false;
        if (Leader.plan(villageId) != Leader.Plan.PLENTY) return false;
        if (folk - housing(villageId) > 4) return false;
        return !Raids.underAlarm(villageId);
    }

    /** How many buildings a town may have going up at once: one; two in a thriving town of twelve or more;
     *  three in a thriving town of forty or more. Each crew raises a different building. */
    public static int crewsAllowed(UUID villageId) {
        int folk = headcount(villageId);
        if (folk < 12 || !thriving(villageId)) return 1;
        return folk >= 40 ? 3 : 2;
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
        REQUESTED.remove(villageId, structure);                       // what was asked for is up
        if (!"colony".equals(structure) && !"guesthouse".equals(structure)) tell(villageId, gameTime / 24000L, spoken(structure) + " went up");
        LAST_PROJECT.put(villageId, gameTime);
        BUILT.computeIfAbsent(villageId, k -> new ArrayList<>()).add(structure);
        Council.built(villageId, structure);
        Map<String, Site> pending = SITES.get(villageId);
        Site raised = pending == null ? null : pending.get(structure);
        if (raised != null) {
            BUILT_AT.computeIfAbsent(villageId, k -> new ConcurrentHashMap<>()).putIfAbsent(structure, raised.anchor());
            // And into the register of the town's buildings, kept with the world (TownLife hangs on it).
            com.jrpetty.mcassistant.village.Ledger.built(villageId, structure, raised.anchor(), raised.facing());
            // And opened: the village gathers in front of it this evening (Assemblies).
            Assemblies.opening(villageId, structure, raised.anchor(), raised.facing(), gameTime);
        }
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
        crewDone(villageId, structure);                      // and its crew starts fresh on the next
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

    /** Set aside, and why (the status line says it: "Set aside: the meeting hall (no lot ...)"). */
    public static void defer(UUID villageId, String project, long until, String why) {
        defer(villageId, project, until);
        DEFER_WHY.computeIfAbsent(villageId, k -> new ConcurrentHashMap<>()).put(project, why);
    }

    private static final Map<UUID, Map<String, String>> DEFER_WHY = new ConcurrentHashMap<>();

    /** The projects the village wants that are set aside just now, each with why; "" if none. */
    public static String setAside(UUID villageId) {
        Map<String, Long> d = DEFERRED.get(villageId);
        if (d == null) return "";
        List<String> out = new ArrayList<>();
        for (String p : projectsWanted(villageId)) {
            if (open(villageId, p)) continue;
            String why = DEFER_WHY.getOrDefault(villageId, Map.of()).get(p);
            out.add(p + (why == null ? "" : " (" + why + ")"));
        }
        return String.join("; ", out);
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

    /** The amenities in the order the council voted for (a graveyard that is wanted for the dead
     *  stays first: that is not a question for a vote). */
    static List<String> voted(UUID villageId, List<String> extras) {
        if (extras.size() < 2) return extras;
        boolean yard = extras.get(0).equals("graveyard");
        List<String> rest = new ArrayList<>(yard ? extras.subList(1, extras.size()) : extras);
        List<String> out = new ArrayList<>();
        if (yard) out.add("graveyard");
        out.addAll(Council.order(villageId, rest));
        return out;
    }

    /**
     * Everything the village would build now, most pressing first. The first one not
     * set aside is the next project (see {@link #defer}); the rest wait their turn.
     */
    public static List<String> projectsWanted(UUID villageId) {
        // [flats] a block of flats ahead of the next house, in the Iron Age when the town wants one (Flats)
        // [batchF] and, after everything else, the post office and the statue its own people paid for (Civics.wanted);
        // [war-prep] and on a war footing, the defences at the head of the list (WarWorks)
        return requestedFirst(villageId, WarWorks.wanted(villageId,
            Civics.wanted(villageId, Flats.wanted(villageId, ageBeforeTheHouse(villageId, projectsWantedInOrder(villageId))))));
    }

    /** The buildings an age asks for before the next (Villages.needs): the wall, the smeltery and the hall;
     *  the workshop, the watchtower and the market; the lighthouse and the chapel; the gateway. */
    private static final java.util.Set<String> AGE_BUILDINGS = java.util.Set.of(
        "fortify", "smeltery", "hall", "workshop", "watchtower", "market", "lighthouse", "chapel", "gateway");

    /**
     * In a big town where all but a few have a bed, the age's own buildings go up before the next house.
     * A town having children faster than it can house them always wants one more: a mountain town of
     * seventy-seven, every day of its third week with "a new house, the meeting hall" at the head of its
     * list, was nineteen days in the Stone Age for want of a hall. Short of beds for more than a few,
     * the house still comes first (nobody sleeps on the ground for a hall).
     */
    static List<String> ageBeforeTheHouse(UUID villageId, List<String> out) {
        int folk = headcount(villageId);
        if (age(villageId) == Age.WOOD || folk < AGE_FOLK || folk - housing(villageId) > 4) return out;
        int house = out.indexOf("house");
        if (house < 0) return out;
        int lastAge = -1;
        for (int i = house + 1; i < out.size(); i++) if (AGE_BUILDINGS.contains(out.get(i))) lastAge = i;
        if (lastAge < 0) return out;
        out.add(lastAge, out.remove(house));          // just after the last of the age's buildings
        return out;
    }

    private static List<String> projectsWantedInOrder(UUID villageId) {
        List<String> out = new ArrayList<>();
        int folk = headcount(villageId);
        if (folk == 0) return out;
        Age at = age(villageId);

        if (built(villageId, "storage") < 1) out.add("storage");
        // A storehouse shed built before there were storehouse units has none in it: the
        // builders lay the twenty-seven units in it, one by one.
        // (A village that took over a vanilla one counts the vanilla stores as its shed: its
        // storehouse goes up on a lot of its own on the square.)
        else if (built(villageId, "storehouse") < 1 && !Storehouses.stands(villageId)) out.add("storehouse");
        if (built(villageId, "shelter") < 1) out.add("shelter");
        // Room before anything else: a village with every home full stops growing, and
        // growing is the whole of how it gets the hands for everything after this. A leader
        // elected for homes (Elections) keeps more spare.
        int spare = Elections.mandate(villageId) == Values.Value.HOMES ? 6 : 2;
        // A thriving town builds its houses ahead of the folk who will want them, so a town with the timber
        // and the food is not putting up one house a day as the children come (thriving).
        if (thriving(villageId)) spare = Math.max(spare, Math.min(10, 2 + folk / 6));
        boolean house = folk >= housing(villageId) - spare || built(villageId, "house") < 1 || HOUSE_WANTED.getOrDefault(villageId, false);
        if (house) out.add("house");
        if (built(villageId, "well") < 1) out.add("well");
        // A house for the player the village has taken to its heart.
        if (com.jrpetty.mcassistant.village.Chronicle.awaitingAHouse(villageId) != null
                && built(villageId, "storage") > 0) out.add("guesthouse");
        if (at == Age.WOOD) { housesForBeds(villageId, folk, out); return out; }

        if (built(villageId, "fortify") < 1) out.add("fortify");        // the wall
        if (built(villageId, "smeltery") < 1) out.add("smeltery");
        if (built(villageId, "hall") < 1) out.add("hall");
        // A Stone Age village keeps a few homes spare, not just one — but after the buildings the
        // age asks for once. A house kept spare stood in front of the meeting hall, and a growing
        // village nearly always wants one: a mountain town that spent forty days in the Stone Age
        // had a house at the head of its list on twenty-five of the days it was looked at (ten of
        // them only a house kept spare) and its hall on five. A house for somebody with no bed
        // (the first house rule, above) still comes before all of it.
        if (!house && folk >= housing(villageId) - 5) out.add("house");
        // The amenities (a café, the crafts' buildings) go on a list of their own, built
        // after everything the age asks for and its homes: they never hold an age back.
        List<String> extras = new ArrayList<>();
        if (folk >= 14 && built(villageId, "cafe") < 1) extras.add("cafe");
        if (folk >= 12 && built(villageId, "tavern") < 1) extras.add("tavern");
        // A Stone Age village's square gets a fountain.
        if (folk >= 10 && built(villageId, "fountain") < 1) extras.add("fountain");
        // A schoolhouse, once there are children enough to fill one (School).
        if (School.wanted(villageId, folk) && built(villageId, "school") < 1) extras.add("school");
        // [fleet] An auction house, once an Iron Age town of twenty-five has held a few auctions on its square (Auctions).
        if (Auctions.wanted(villageId, folk) && built(villageId, "auction") < 1) extras.add("auction");
        // And a park among the homes, once the town is big enough to want one (Park).
        if (Park.wanted(villageId, folk)) extras.add(Park.STRUCTURE);
        // [batchC] A football pitch by the park, for the rest day's match (Pitch).
        if (Pitch.wanted(villageId, folk)) extras.add(Pitch.STRUCTURE);
        // A stable, once the village has horses of its own (or, in the Iron Age, a rancher and a saddle: Stables).
        if (built(villageId, "stable") < 1 && Stables.wanted(villageId)) extras.add("stable");
        // [batchA] An infirmary once the town is twenty and its meeting hall stands (Infirmary).
        if (Infirmary.wanted(villageId, folk)) extras.add(Infirmary.STRUCTURE);
        // The courtyard before the board, where the village gathers; and, once the town is big enough
        // to want governing, a hall for whoever leads it, on the great lot behind the board.
        if (VillageBoards.boardOf(villageId) != null && built(villageId, "hall") > 0 && built(villageId, "court") < 1) extras.add("court");
        if (folk >= 16 && built(villageId, "hall") > 0 && built(villageId, "townhall") < 1) extras.add("townhall");
        // Somewhere to lay the dead, once there are any; another when it is full.
        if (com.jrpetty.mcassistant.village.Ledger.graves(villageId).size() > Graves.room(villageId)) extras.add(0, "graveyard");
        // [batchE] The town's look: the windmill, the bakery, the orchard, the allotments and (Iron Age, or thirty folk) the inn.
        TownLook.wanted(villageId, folk, at, extras, s -> built(villageId, s) < 1);
        // [fireworks] The powder hut, out at the edge of the town, once the town takes up fireworks (FireworksMaker): from the
        // Stone Age on, so it is asked for wherever the list stops.
        if (FireworksMaker.hutWanted(villageId)) extras.add(FireworksMaker.STRUCTURE);
        // [library] A library for the town's books, once it is twelve strong (Library).
        if (Library.wanted(villageId, folk) && built(villageId, Library.STRUCTURE) < 1) extras.add(Library.STRUCTURE);
        // [fletcher] The fletcher's hut, once the town keeps a fletcher (Fletchers).
        if (Fletchers.hutWanted(villageId)) extras.add(Fletchers.STRUCTURE);
        if (at == Age.STONE) { homesAndAmenities(villageId, folk, out, extras); return out; }

        if (built(villageId, "workshop") < 1) out.add("workshop");
        // Whatever the headcount: the Iron Age asks for it, and a village
        // that has lost people must still be able to finish its list.
        if (built(villageId, "watchtower") < 1) out.add("watchtower");
        if (built(villageId, "market") < 1) out.add("market");
        String pen = penIfWanted(villageId, folk);
        if (pen != null) out.add(pen);
        // [guard-kit] A smith at work for the watch with no smithy: the smithy with what the age asks for.
        if (built(villageId, "smithy") < 1 && WatchKit.smithWithoutASmithy(villageId)) out.add("smithy");
        // The crafts' buildings come after everything the age itself asks for: a smithy and a
        // shop are what a big Iron Age town has, not what makes it one.
        if (folk >= 16 && built(villageId, "smithy") < 1 && !out.contains("smithy")) extras.add("smithy");
        if (folk >= 18 && built(villageId, "shop") < 1) extras.add("shop");
        // [econ-store] The town store, once the town has outgrown its shop (Store).
        if (built(villageId, Store.STRUCTURE) < 1 && Store.wanted(villageId, folk)) extras.add(Store.STRUCTURE);
        if (folk >= 22 && built(villageId, "brewery") < 1) extras.add("brewery");
        // A town of thirty keeps its savings somewhere safe and lends them to its home-buyers: a bank (Bank).
        if (folk >= Bank.FROM && built(villageId, "bank") < 1 && builtStructure(villageId, "bank") == null) extras.add("bank");
        // A museum, once the town has finds worth showing (Museum): its diamonds, fossils, the sea's treasure.
        if (folk >= Museum.FROM_FOLK && built(villageId, "museum") < 1 && Museum.worthAMuseum(villageId)) extras.add("museum");
        // [batchD] A theatre, once the town has players enough to fill its stage (Theatre).
        if (Theatre.wanted(villageId, folk) && built(villageId, Theatre.STRUCTURE) < 1) extras.add(Theatre.STRUCTURE);
        // The Iron Age's best homes: a manor house on a long lot by the square, one for every
        // thirty folk past twenty — six beds each.
        if (folk >= 20 && built(villageId, "manor") < 1 + (folk - 20) / 30 || Homes.wantsAManor(villageId)) extras.add("manor");
        // [batchC] An archery range by the wall, once the town keeps a watch of two or more (Archery).
        if (Archery.wanted(villageId)) extras.add(Archery.STRUCTURE);
        // [disasters] A fire station, once an Iron Age town has had two fires (FireSafety).
        if (FireSafety.wanted(villageId)) extras.add(FireSafety.STATION);
        // [caves] The Delvers' Lodge, once the town keeps a cave team (Lodge).
        if (Lodge.wanted(villageId)) extras.add(Lodge.STRUCTURE);
        // [golems] The golem yard, once the town keeps a golem keeper (Golems).
        if (Golems.yardWanted(villageId)) extras.add(Golems.STRUCTURE);
        if (at == Age.IRON) { homesAndAmenities(villageId, folk, out, extras); return out; }

        if (built(villageId, "lighthouse") < 1) out.add("lighthouse");
        if (built(villageId, "chapel") < 1) out.add("chapel");
        if (folk >= 24 && built(villageId, "library") < 1) extras.add("library");
        // And a bell tower on the square, to ring the town's hours.
        if (folk >= 24 && built(villageId, "belltower") < 1) extras.add("belltower");
        if (at == Age.DIAMOND) { homesAndAmenities(villageId, folk, out, extras); return out; }

        if (built(villageId, "gateway") < 1) out.add("gateway");
        homesAndAmenities(villageId, folk, out, extras);   // (the Nether Age: before the great works)
        // And then the great works, one after another for as long as the village stands:
        // a town that has been everywhere its ages lead goes on building.
        out.add(nextGreatWork(villageId));
        return out;
    }

    /** A household waiting for a house and none empty (Homes.wantsAHouse), refreshed with the homes. */
    static final Map<UUID, Boolean> HOUSE_WANTED = new ConcurrentHashMap<>();

    /** What a player asked the elder to build next (Asks.build), until it goes up. */
    private static final Map<UUID, String> REQUESTED = new ConcurrentHashMap<>();

    public static void request(UUID villageId, String structure) {
        REQUESTED.put(villageId, structure);
    }

    /** The building asked for, to the front of the list, if the village would build it at all. */
    private static List<String> requestedFirst(UUID villageId, List<String> out) {
        String asked = REQUESTED.get(villageId);
        if (asked != null && out.remove(asked)) out.add(0, asked);
        return out;
    }

    /**
     * A bed for everybody. Once what this age asks for is built, the village goes on
     * raising houses for as long as it has more people than its homes have beds. It
     * comes after the age's own buildings, so it never holds the village back, and it
     * means a village always has a home in hand for whoever has none.
     */
    /**
     * The homes it is short of and the amenities it has grown into (the café, the tavern, the
     * crafts' buildings), turn about: a house, then an amenity, then a house. Houses for beds
     * used to come first every time, and a village that grew faster than it built — the long
     * game's, at seventy-four — never got past them to its smithy, café or tavern at all.
     */
    private static void homesAndAmenities(UUID villageId, int folk, List<String> out, List<String> extras) {
        List<String> amenities = voted(villageId, extras);
        String last = "";
        List<String> built = BUILT.getOrDefault(villageId, List.of());
        for (int i = built.size() - 1; i >= 0; i--) {
            if (!"colony".equals(built.get(i))) { last = built.get(i); break; }
        }
        if (!amenities.isEmpty() && "house".equals(last)) {
            out.addAll(amenities);
            housesForBeds(villageId, folk, out);
        } else {
            housesForBeds(villageId, folk, out);
            out.addAll(amenities);
        }
    }

    private static void housesForBeds(UUID villageId, int folk, List<String> out) {
        // Two children to a bed's worth (they sleep by their family's), and a house started two
        // beds before the last one is taken, not after: a house takes days to go up, and in the
        // long game the village was always a house behind its births.
        int children = 0;
        for (AssistantEntity a : folkOf(villageId)) if (a.isBaby()) children++;
        int need = Math.max(0, folk - children) + (children + 1) / 2;
        if (need + 2 > bedsPlanned(villageId) && !out.contains("house")) out.add("house");
    }

    private static final Map<UUID, long[]> MADE_UP = new ConcurrentHashMap<>();

    /**
     * Plain stone kept back from the village's looks (a building made over, a house rebuilt in
     * stone): in the Stone Age, what the age itself asks for before the Iron Age. A town of sixty
     * spent its stone on new walls as fast as it was quarried and sat a few dozen short of the
     * Iron Age for a fortnight. Stone bricks put by are not held back, and after the Stone Age
     * nothing is.
     */
    public static int stoneHeldBack(UUID villageId) {
        return age(villageId) == Age.STONE
            ? com.jrpetty.mcassistant.village.VillageMath.stoneWanted(Math.min(headcount(villageId), AGE_FOLK)) : 0;
    }

    /** The headcount the ages' stock is sized to, at most (wantsFor). */
    public static final int AGE_FOLK = 24;

    /** Count the beds afresh next time (a house just made up, or a test that has just built one). */
    public static void recountBeds(UUID villageId) { MADE_UP.remove(villageId); }

    /** Tests: so many beds made up, as if just counted. */
    public static void bedsForTests(UUID villageId, long now, int beds) { MADE_UP.put(villageId, new long[]{ now, beds, 0 }); }

    /**
     * The beds actually made up in the village's houses and barracks, counted from the world (the
     * camp's round the heart and the guest house's left out): "homes for" used to be four a house
     * on paper, whether the house had a bed in it or not. Counted at most once a minute.
     */
    public static int bedsMadeUp(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        long now = level.getGameTime();
        long[] seen = MADE_UP.get(villageId);
        if (seen != null && now - seen[0] < 1200L) return (int) seen[1];
        Village v = get(villageId);
        if (v == null) return 0;
        BlockPos guest = builtAt(villageId, "guesthouse");
        int reach = Math.min(6, Math.max(3, storesRadius(villageId) / 16));
        int cx = v.centre().getX() >> 4, cz = v.centre().getZ() >> 4;
        int n = 0, camp = 0;
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof net.minecraft.world.level.block.entity.BedBlockEntity)) continue;
                    BlockPos p = be.getBlockPos();
                    net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
                    if (!st.hasProperty(net.minecraft.world.level.block.BedBlock.PART)
                        || st.getValue(net.minecraft.world.level.block.BedBlock.PART) != net.minecraft.world.level.block.state.properties.BedPart.HEAD) continue;
                    if (Math.max(Math.abs(p.getX() - v.centre().getX()), Math.abs(p.getZ() - v.centre().getZ()))
                            <= com.jrpetty.mcassistant.VillageSpawner.CAMP_REACH) {
                        camp++;                                // the camp: room, but not a home
                        continue;
                    }
                    if (guest != null && p.distSqr(guest) <= 100) continue;
                    if (Inn.isInnBed(villageId, p)) continue;     // [batchE] the inn's rooms are the travellers', not a home
                    // A bed buried in the ground (a ruin's, a vault's) is nobody's home and nobody sleeps
                    // in it (VillageFolkEntity.bedFit): counted, a mountain town of twenty-five thought
                    // it had four beds more than it had, and built and bought for four fewer.
                    if (buriedBed(level, villageId, p)) continue;
                    n++;
                }
            }
        }
        MADE_UP.put(villageId, new long[]{ now, n, camp });
        return n;
    }

    /**
     * A bed down in the ground (a buried ruin's, a vault's), not one in a home: more than eight
     * under the surface over it, and not in anything the village built. The village's own roofs are
     * no ground: under the eaves of a two-storey house, or the ridge of a cottage, a bed is ten or
     * eleven below the top of the roof, and every bed in every house was passed over as buried (a
     * town of fifty-eight with thirty-five beds made up had sixteen folk in them).
     */
    public static boolean buriedBed(net.minecraft.world.level.LevelReader level, UUID villageId, BlockPos p) {
        if (p.getY() >= level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                p.getX(), p.getZ()) - 8) return false;
        Village v = get(villageId);
        if (v != null && Math.max(Math.abs(p.getX() - v.centre().getX()), Math.abs(p.getZ() - v.centre().getZ()))
                    <= com.jrpetty.mcassistant.VillageSpawner.CAMP_REACH
                && Math.abs(p.getY() - v.centre().getY()) <= 3) return false;          // the camp
        return !Land.inABuilding(villageId, p);
    }

    /** Beds the village's homes hold: four a house, six a barracks (the guest house is the player's). */
    public static int bedsPlanned(UUID villageId) {
        return com.jrpetty.mcassistant.village.VillageMath.BEDS_PER_HOUSE * built(villageId, "house")
            + 6 * built(villageId, "barracks") + 6 * built(villageId, "manor")
            + Flats.bedsPlanned(villageId);                         // [flats] nine a block of flats
    }

    /**
     * What a village builds once it has come through every age: a granary, barracks and
     * a monument, round and round, each on new ground and each asking the stores for more
     * than the last (see {@link #renown}). It is what keeps a finished village growing.
     */
    public static final List<String> GREAT_WORKS = List.of("granary", "barracks", "monument");

    /**
     * Its renown: ten for every great work it has raised, and what its museum has on show, the
     * rarer the more (Museum). It is what the ranks ask for past the Town.
     */
    public static int renown(UUID villageId) {
        return Museum.GREAT_WORK_RENOWN * greatWorks(villageId) + Museum.renown(villageId);
    }

    /** How many great works this village has raised. */
    public static int greatWorks(UUID villageId) {
        int n = 0;
        for (String g : GREAT_WORKS) n += built(villageId, g);
        return n;
    }

    /**
     * What a place has made of itself, beyond its age: a hamlet, a village, a town, a city, a
     * capital. The ages end at the Nether; the ranks go on asking for more — people, great works,
     * colonies of its own — so a village always has somewhere further to get to.
     */
    public enum Rank {
        HAMLET("a hamlet"), VILLAGE("a village"), TOWN("a town"), CITY("a city"), CAPITAL("a capital");
        public final String label;
        Rank(String label) { this.label = label; }
    }

    public static Rank rank(UUID villageId) {
        int folk = headcount(villageId);
        int renown = renown(villageId);
        int colonies = 0;
        for (String s : BUILT.getOrDefault(villageId, List.of())) if ("colony".equals(s)) colonies++;
        Age age = ageOf(villageId);
        if (age == Age.NETHER && renown >= 6 * Museum.GREAT_WORK_RENOWN && folk >= 80 && colonies >= 2) return Rank.CAPITAL;
        if (age.ordinal() >= Age.DIAMOND.ordinal() && renown >= 2 * Museum.GREAT_WORK_RENOWN && folk >= 50) return Rank.CITY;
        if (age.ordinal() >= Age.IRON.ordinal() && folk >= 30) return Rank.TOWN;
        if (age.ordinal() >= Age.STONE.ordinal() && folk >= 12) return Rank.VILLAGE;
        return Rank.HAMLET;
    }

    /** What the next rank asks for, in words (for the status and the journal). */
    public static String nextRankNote(UUID villageId) {
        return switch (rank(villageId)) {
            case HAMLET -> "a village: the Stone Age and twelve folk";
            case VILLAGE -> "a town: the Iron Age and thirty folk";
            case TOWN -> "a city: the Diamond Age, renown 20 (two great works, or the finds in a museum) and fifty folk";
            case CITY -> "a capital: the Nether Age, renown 60 (six great works, or fewer and a museum), eighty folk and two colonies";
            case CAPITAL -> "nothing higher — every great work adds to its renown";
        };
    }

    /**
     * Once a morning: has the place risen in rank? A rise is told everywhere, the treasury is
     * given a purse for it by the traders who now come further to its market, and the folk
     * remember the day. Kept in the ledger, so it is told once.
     */
    public static void checkRank(net.minecraft.server.level.ServerLevel level, Village v, long day) {
        Rank now = rank(v.id());
        String was = com.jrpetty.mcassistant.village.Ledger.note(v.id(), "rank");
        Rank before = Rank.HAMLET;
        try { if (was != null) before = Rank.valueOf(was); } catch (IllegalArgumentException ignored) { }
        if (now.ordinal() <= before.ordinal()) return;
        com.jrpetty.mcassistant.village.Ledger.note(v.id(), "rank", now.name());
        if (was == null && now.ordinal() <= Rank.VILLAGE.ordinal()) return;   // a new or restored place: nothing to tell
        tell(v.id(), day, name(v.id()) + " has grown into " + now.label);
        for (AssistantEntity a : folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f) f.persona().remember(day, "I saw " + name(v.id()) + " become " + now.label, 6);
        }
        net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.literal(
            name(v.id()) + " has grown into " + now.label + "!").withStyle(net.minecraft.ChatFormatting.GOLD);
        for (net.minecraft.server.level.ServerPlayer p : level.players()) p.sendSystemMessage(line);
    }

    /** The great work this village raises next. */
    public static String nextGreatWork(UUID villageId) {
        return GREAT_WORKS.get(greatWorks(villageId) % GREAT_WORKS.size());
    }

    /**
     * How many people this village has room for. A village is founded with room for
     * twelve — the founders sleep rough round the heart — and every home it builds is
     * room for five more: the shelter three, the meeting hall six. Two fed folk in work
     * raise a child only while there is room for one, so a village grows exactly as
     * fast as it builds homes, and that is the reason it builds them.
     */
    public static int housing(UUID villageId) {
        // The beds actually made up, in the houses and still at the camp, once they have been counted
        // (bedsMadeUp): the reckoning by buildings said a mountain town of eighty-three had room for
        // eighty-three when it had forty-five beds, so it built no houses and went on having children.
        long[] seen = MADE_UP.get(villageId);
        if (seen != null && seen.length > 2) return (int) (seen[1] + seen[2]);
        return 12 + 5 * built(villageId, "house") + 3 * built(villageId, "shelter") + 6 * built(villageId, "hall")
            + 6 * built(villageId, "barracks") + 6 * built(villageId, "manor")
            + 2 * com.jrpetty.mcassistant.village.Ledger.grownCount(villageId)
            + Flats.bedsPlanned(villageId);                         // [flats]
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
        int content = Contentment.score(villageId);
        int plenty = want * (content >= 70 ? 3 : content >= 50 ? 4 : 5) / 5;
        // [economy] The fields against the mouths (Larder): a full larder is not enough if less is grown than eaten.
        Larder.Verdict fed = Larder.oneMore(villageId, food);
        if (!fed.yes() && food >= Math.max(4, want * 2 / 5)) return "no — " + fed.why();
        if (food >= plenty) return "yes, the village being " + Contentment.word(content);
        int lean = Math.max(4, want * 2 / 5);
        if (food >= lean) return "slowly — lean times: " + food + " food put by, " + plenty + " would be plenty";
        return "no — the stores hold " + food + " food and even lean times need " + lean + " put by";
    }

    /** Why the village wants the building it wants next, in a line a player can read. */
    public static String whyBuild(UUID villageId, @Nullable String project) {
        if (project == null) return "nothing for now — the village is gathering what its age asks for";
        String war = WarWorks.whyBuild(villageId, project);        // [war-prep] the defences, on a war footing
        if (war != null) return war;
        int folk = headcount(villageId);
        return switch (project) {
            case "guesthouse" -> {
                com.jrpetty.mcassistant.village.Chronicle.Guest g = com.jrpetty.mcassistant.village.Chronicle.awaitingAHouse(villageId);
                yield "a house for " + (g == null ? "an honoured guest" : g.name) + ", the village's honoured guest";
            }
            case "storage" -> "a storehouse, so what is gathered has somewhere to go";
            case "storehouse" -> "the Village Storehouse in the storehouse shed, so the whole village keeps its goods in one place";
            case "shelter" -> "a shelter, somewhere to wait out the first nights";
            case "house" -> "a house: " + folk + " live here and there is room for " + housing(villageId)
                + ", and nobody is born without room";
            case "well" -> "a well at the heart, the mark of a village rather than a camp";
            case "fortify" -> "a wall round the village, against the things that come out at night";
            case "smeltery" -> "a smeltery, three furnaces for the ore the mines bring up";
            case "hall" -> "a meeting hall, which a village must have before the Iron Age";
            case "townhall" -> "a hall for whoever leads us: the best and biggest building in the town, where the leader lives and the council sits";
            case "court" -> "a paved courtyard before the board, where the village gathers: the morning assembly, weddings, elections";
            case "workshop" -> "a workshop, where iron becomes tools";
            case "watchtower" -> "a watchtower, to see trouble coming";
            case "lighthouse" -> "a lighthouse, so anyone out after dark can find the way home";
            case "pen" -> "a pen, for the rancher's herd";
            case "stable" -> "a stable for the village's horses: four stalls, hay and water, so the couriers and scouts can ride";
            case "market" -> "a market, stalls under one roof for what the village makes";
            case "chapel" -> "a chapel, which the Diamond Age asks for";
            case "cafe" -> "a café, where folk can sit down to a drink and a bite on their break";
            case "tavern" -> "a tavern, for the evenings: a fire, a tune and a story";
            case "graveyard" -> "a graveyard, to lay our dead to rest";
            case "smithy" -> "a smithy, for the watch's armour and the miners' picks";
            case "shop" -> "a shop, to sell what the village's crafts make";
            case "store" -> Store.why(villageId);                  // [econ-store]
            case "bank" -> "a bank, " + folk + " folk's savings kept safe behind iron bars, and lent to households buying their houses";
            case "brewery" -> "a brewery, for the brewer's potions";
            case "library" -> "a library, where the enchanter keeps its books";
            case "school" -> School.why(villageId);
            case "auction" -> Auctions.why(villageId);             // [fleet]
            case "fountain" -> "a fountain on the square, now that the village builds in stone";
            case "park" -> "a park among the homes, a fountain and benches: somewhere to sit of an evening, now the town has "
                + folk + " folk";
            case "manor" -> "a manor house, six beds under a slate roof: the best homes a town of the Iron Age has";
            case "flats" -> Flats.why(villageId);                  // [flats]
            case "pitch" -> Pitch.why(villageId);                  // [batchC]
            case "range" -> Archery.why(villageId);                // [batchC]
            case "belltower" -> "a bell tower on the square, to ring the hours of a Diamond Age town";
            case "gateway" -> "a gateway of obsidian, the way out of the world the Nether Age is named for";
            case "granary" -> "a granary (great work " + (greatWorks(villageId) + 1) + "): a town that has come through every age goes on building";
            case "barracks" -> "barracks (great work " + (greatWorks(villageId) + 1) + "), room for six more and a home for the watch";
            case "monument" -> "a monument (great work " + (greatWorks(villageId) + 1) + ") to how far the village has come";
            case "museum" -> "a museum, to put the town's rare finds on show and keep its chronicle as books";
            case "infirmary" -> Infirmary.why(villageId);          // [batchA]
            case "lodge" -> Lodge.why(villageId);                  // [caves]
            case "fletcher" -> Fletchers.why(villageId);           // [fletcher]
            case "golemyard" -> Golems.why(villageId);             // [golems]
            case "powderhut" -> FireworksMaker.why(villageId);     // [fireworks]
            case "theatre" -> Theatre.why(villageId);             // [batchD]
            case "windmill", "bakery", "inn", "orchard", "allotments" -> TownLook.why(villageId, project);   // [batchE]
            case "postoffice" -> Post.why(villageId);                     // [batchF]
            case "statue" -> PublicFund.why(villageId);                   // [batchF]
            case "firestation" -> FireSafety.why(villageId);              // [disasters]
            case "townlibrary" -> Library.why(villageId);                 // [library]
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

    // ---- who is raising the town's buildings ----

    /** A hand raising one of the town's buildings: the project it is on (null till it settles on one),
     *  when it took the post, and when it last got anywhere. */
    private static final class Crew {
        final UUID folk;
        final long started;
        volatile String project;
        volatile long since;

        Crew(UUID folk, long now) {
            this.folk = folk;
            this.started = now;
            this.since = now;
        }
    }

    /** Each town's building crews, by the hand leading each. */
    private static final Map<UUID, Map<UUID, Crew>> CREWS = new ConcurrentHashMap<>();
    private static final long LEAD_TERM = 6000L;      // five minutes without progress

    /** The town's crews still at it: a lead five minutes without progress, dead or gone gives its post up. */
    private static Map<UUID, Crew> crews(UUID villageId, long now) {
        Map<UUID, Crew> m = CREWS.computeIfAbsent(villageId, k -> new ConcurrentHashMap<>());
        m.values().removeIf(c -> now - c.since >= LEAD_TERM || !livesHere(villageId, c.folk));
        return m;
    }

    /**
     * Is this hand one of those raising the village's buildings? A hand that leads a crew stays its
     * lead: its pack is where the timber, the chests and the ladders pile up over several visits, so
     * the next volunteer must not start again from nothing with a pack of its own. While the town has
     * a crew to spare (crewsAllowed) the first to ask takes one. The post passes on when the lead dies,
     * is nowhere to be found, or five minutes go by without it getting anywhere.
     */
    public static boolean isLead(UUID villageId, UUID me, long now) {
        Map<UUID, Crew> m = crews(villageId, now);
        if (m.containsKey(me)) return true;
        if (m.size() >= crewsAllowed(villageId)) return false;
        m.put(me, new Crew(me, now));
        return true;
    }

    /** The hand that has led a building longest just now, or null (the one idle hands go to help). */
    @Nullable
    public static UUID currentLead(UUID villageId, long now) {
        Crew best = null;
        for (Crew c : crews(villageId, now).values()) if (best == null || c.started < best.started) best = c;
        return best == null ? null : best.folk;
    }

    /** Every crew is in other living hands just now, and none is this one's. */
    public static boolean ledByAnother(UUID villageId, UUID me, long now) {
        Map<UUID, Crew> m = crews(villageId, now);
        return !m.containsKey(me) && m.size() >= crewsAllowed(villageId);
    }

    /** Is this hand leading one of the town's buildings right now (without taking a post if one is free)? */
    public static boolean holdsTheLead(UUID villageId, UUID me, long now) {
        Map<UUID, Crew> m = CREWS.get(villageId);
        Crew c = m == null ? null : m.get(me);
        return c != null && now - c.since < LEAD_TERM;
    }

    /** The lead got somewhere — a load drawn, a fixture crafted: the term restarts. */
    public static void leadProgress(UUID villageId, UUID me, long now) {
        Map<UUID, Crew> m = CREWS.get(villageId);
        Crew c = m == null ? null : m.get(me);
        if (c != null) c.since = now;
    }

    /**
     * The building this hand is to raise: the one its crew is on, while the town still wants it and it
     * is not set aside; else the first the town wants that no other crew is raising. Two crews never
     * raise the same building.
     */
    @Nullable
    public static String projectFor(UUID villageId, UUID me) {
        Map<UUID, Crew> m = CREWS.get(villageId);
        Crew mine = m == null ? null : m.get(me);
        List<String> wanted = projectsWanted(villageId);
        if (mine != null && mine.project != null && wanted.contains(mine.project) && open(villageId, mine.project)) return mine.project;
        java.util.Set<String> taken = new java.util.HashSet<>();
        if (m != null) for (Crew c : m.values()) if (!c.folk.equals(me) && c.project != null) taken.add(c.project);
        for (String p : wanted) if (open(villageId, p) && !taken.contains(p)) return p;
        return null;
    }

    /** This hand's crew is on this building now. */
    public static void leadOn(UUID villageId, UUID me, String project) {
        Map<UUID, Crew> m = CREWS.get(villageId);
        Crew c = m == null ? null : m.get(me);
        if (c != null) c.project = project;
    }

    /** The building is up, or its lot given up: the crew on it is free for the next. */
    private static void crewDone(UUID villageId, String project) {
        Map<UUID, Crew> m = CREWS.get(villageId);
        if (m == null) return;
        boolean any = m.values().removeIf(c -> project.equals(c.project));
        if (!any && m.size() == 1) m.clear();          // the one crew there was, whatever it called its building
    }

    /** Tests and the books: the buildings going up now, a line each. */
    public static List<String> crewsReport(UUID villageId, long now) {
        List<String> out = new ArrayList<>();
        for (Crew c : crews(villageId, now).values()) out.add(c.folk + " on " + c.project);
        return out;
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

    /** The buildings this village is putting up now: their sites, by structure. */
    public static Map<String, Site> sitesOf(UUID villageId) {
        Map<String, Site> s = SITES.get(villageId);
        return s == null ? Map.of() : Map.copyOf(s);
    }
    /** Which squares of the town plan are spoken for (TownPlan.cellKey): chosen for a project or built on. */
    private static final Map<UUID, java.util.Set<Long>> LOT_TAKEN = new ConcurrentHashMap<>();
    /** Lots the builders found they could not get to, as the column of the lot's middle. */
    private static final Map<UUID, java.util.Set<Long>> BAD_LOTS = new ConcurrentHashMap<>();
    /** The most a lot's ground may stand above or below the ground at the heart. */
    private static final int LOT_RISE = 12;
    /** How many times round its lots a village has been without finding one. Each
     *  lap lets the ground be a little rougher, so a village founded on a
     *  mountainside is not left without a storehouse for ever. */
    private static final Map<UUID, Integer> LAPS = new ConcurrentHashMap<>();

    // ---- the stores ----

    /** Where the first of each kind of building went up (the storehouse, the smeltery). Memory
     *  only, like the rest of the register: the folk carry it over a restart. */
    private static final Map<UUID, Map<String, BlockPos>> BUILT_AT = new ConcurrentHashMap<>();

    @Nullable
    public static BlockPos builtAt(UUID villageId, String structure) {
        Map<String, BlockPos> m = BUILT_AT.get(villageId);
        BlockPos at = m == null ? null : m.get(structure);
        if (at != null) return at;
        // After a restart: the ledger (which the world keeps) knows where everything stands.
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(villageId)) {
            if (b.structure().equals(structure)) return b.anchor();
        }
        return null;
    }

    /** Where a building of this kind stands and which way round, from the ledger, or null. */
    @Nullable
    public static com.jrpetty.mcassistant.village.Ledger.Building builtStructure(UUID villageId, String structure) {
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(villageId)) {
            if (b.structure().equals(structure)) return b;
        }
        return null;
    }

    /** Put back where a building stands, from what a folk remembers (the first to load wins). */
    public static void rememberBuiltAt(UUID villageId, String structure, BlockPos at) {
        BUILT_AT.computeIfAbsent(villageId, k -> new ConcurrentHashMap<>()).putIfAbsent(structure, at.immutable());
    }

    /** Tests: say where a building stands. */
    public static void builtAtForTests(UUID villageId, String structure, BlockPos at) {
        BUILT_AT.computeIfAbsent(villageId, k -> new ConcurrentHashMap<>()).put(structure, at.immutable());
    }

    /** How far round the heart the village's own stores are: the square, and the storehouse,
     *  granary, market and workshop that face it (village/TownPlan). */
    public static final int STORE_AREA = 30;

    /** Is this one of the stores at the heart (not a field's chest, not a mine's)? */
    public static boolean inStoreArea(UUID villageId, BlockPos pos) {
        Village v = get(villageId);
        if (v == null) return false;
        return Math.max(Math.abs(pos.getX() - v.centre().getX()), Math.abs(pos.getZ() - v.centre().getZ())) <= STORE_AREA
            && Math.abs(pos.getY() - v.centre().getY()) <= 16;
    }

    /**
     * The village's stores at its heart, in the order they are filled: the storehouse's own
     * chests first, then the founding chest at the heart, then the other stores round the
     * square (the granary, the market, the workshop). Never the player's guest house.
     */
    public static List<BlockPos> storeChests(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        Village v = get(villageId);
        if (v == null) return List.of();
        BlockPos house = builtAt(villageId, "storage");
        List<BlockPos> out = new ArrayList<>();
        // A worker's production chest near the heart is its own, not the stores': the couriers empty
        // it, and nothing of the stores' is put into it.
        java.util.Set<Long> inUse = VillageFolkEntity.chestsInUse(villageId);
        boolean before = ZoneChests.askAs(true);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, v.centre(), STORE_AREA, 16)) {
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                if (inAGuestHouse(villageId, f.pos())) continue;
                if (Homes.inAHome(villageId, f.pos())) continue;          // a household's own chest (Homes)
                if (WarWorks.inArmoury(villageId, f.pos())) continue;     // [war-prep] the armoury's racks are not the stores
                if (inUse.contains(f.pos().asLong())) continue;
                out.add(f.pos().immutable());
            }
        } finally {
            ZoneChests.askAs(before);
        }
        BlockPos heart = v.centre();
        BlockPos store = Storehouses.doorFor(level, villageId);
        out.sort(java.util.Comparator.<BlockPos>comparingInt(p -> p.equals(store) ? -1 : house != null && p.distSqr(house) <= 36 ? 0 : 1)
            .thenComparingDouble(p -> p.distSqr(heart)));
        return out;
    }

    private static final Map<UUID, long[]> HAS_STORES = new ConcurrentHashMap<>();

    /** The stores have just changed (a first chest set down): ask again next time. */
    public static void forgetStores(UUID villageId) {
        HAS_STORES.remove(villageId);
    }

    /**
     * Has the village any stores at all — its Village Storehouse, or chests at its heart?
     * Once it has, its folk keep everything there and set no chest of their own on their
     * plots (JobSpec). Looked at once in ten seconds.
     */
    public static boolean hasStores(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        if (Storehouses.stands(villageId)) return true;
        long now = level.getGameTime();
        long[] seen = HAS_STORES.get(villageId);
        if (seen != null && now - seen[0] < 200L && now >= seen[0]) return seen[1] != 0L;
        boolean has = !storeChests(level, villageId).isEmpty();
        HAS_STORES.put(villageId, new long[]{ now, has ? 1L : 0L });
        return has;
    }

    /** Where a load for the stores goes: the first store with an empty slot, or null if every one is full. */
    @Nullable
    public static BlockPos depot(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        for (BlockPos p : storeChests(level, villageId)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) return p;
        }
        BlockPos grown = growStores(level, villageId);
        if (grown != null || !clearRubbish(level, villageId)) return grown;
        for (BlockPos p : storeChests(level, villageId)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) return p;
        }
        return null;
    }

    private static final Map<UUID, Long> CLEARED = new ConcurrentHashMap<>();

    /** What the stores keep of the rubbish (any more goes out): dirt, gravel, rough stone and the like. */
    private static final Map<net.minecraft.world.item.Item, Integer> RUBBISH = Map.ofEntries(
        Map.entry(net.minecraft.world.item.Items.DIRT, 16), Map.entry(net.minecraft.world.item.Items.COARSE_DIRT, 0),
        Map.entry(net.minecraft.world.item.Items.ROOTED_DIRT, 0), Map.entry(net.minecraft.world.item.Items.GRAVEL, 32),
        Map.entry(net.minecraft.world.item.Items.ANDESITE, 64), Map.entry(net.minecraft.world.item.Items.DIORITE, 64),
        Map.entry(net.minecraft.world.item.Items.GRANITE, 64), Map.entry(net.minecraft.world.item.Items.TUFF, 0),
        Map.entry(net.minecraft.world.item.Items.CALCITE, 0), Map.entry(net.minecraft.world.item.Items.COBBLED_DEEPSLATE, 64),
        Map.entry(net.minecraft.world.item.Items.ROTTEN_FLESH, 0), Map.entry(net.minecraft.world.item.Items.POISONOUS_POTATO, 0),
        Map.entry(net.minecraft.world.item.Items.SPIDER_EYE, 8), Map.entry(net.minecraft.world.item.Items.STICK, 64));

    /**
     * Every store full and no timber for another chest: the rubbish goes out (dirt, gravel, spare
     * rough stone, rotten flesh), past a handful of each, so the harvest has somewhere to go. A
     * town's stores filled with what the miners and levellers brought in, the farmers had nowhere to
     * put their crops, and the village starved with full packs. Returns whether room was made.
     */
    static boolean clearRubbish(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        long now = level.getGameTime();
        if (now - CLEARED.getOrDefault(villageId, -100000L) < 600L) return false;
        CLEARED.put(villageId, now);
        Map<net.minecraft.world.item.Item, Integer> seen = new HashMap<>();
        int freed = 0;
        for (BlockPos p : storeChests(level, villageId)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack st = c.getItem(i);
                Integer keep = st.isEmpty() ? null : RUBBISH.get(st.getItem());
                if (keep == null) continue;
                int had = seen.merge(st.getItem(), st.getCount(), Integer::sum);
                int over = Math.min(st.getCount(), had - keep);
                if (over <= 0) continue;
                st.shrink(over);
                if (st.isEmpty()) { c.setItem(i, net.minecraft.world.item.ItemStack.EMPTY); freed++; }
            }
            c.setChanged();
        }
        if (freed > 0) tell(villageId, now / 24000L, "the stores were full: the rubbish went out to make room for the harvest");
        return freed > 0;
    }

    /** The village's colour (its guards' tabards, the tailor's boots): the client's banner colours. */
    private static final int[] COLOURS = { 0x324C9C, 0x9C2A2E, 0x2E7044, 0x5E3A86, 0x2A2A32, 0x22777E, 0xA65A22 };

    public static int colour(UUID villageId) {
        return COLOURS[Math.floorMod(Math.floorMod(villageId.hashCode(), 64), COLOURS.length)];
    }

    private static final Map<UUID, Long> GREW = new ConcurrentHashMap<>();

    /**
     * Every store full: another chest, set down beside the others in the storehouse (or by the
     * heart while there is none), of eight planks or two logs out of the stores themselves. The
     * carriers used to stand by a full storehouse saying it needed another chest, and the stores
     * dropped what would not fit on the ground at the heart. Returns the new chest, or null.
     */
    @Nullable
    public static BlockPos growStores(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        long now = level.getGameTime();
        if (now - GREW.getOrDefault(villageId, -100000L) < 200L) return null;
        GREW.put(villageId, now);
        Village v = get(villageId);
        if (v == null) return null;
        List<BlockPos> chests = storeChests(level, villageId);
        if (chests.size() >= 64) return null;
        BlockPos spot = null;
        for (BlockPos c : chests) {
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos p = c.relative(d);
                if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                        && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) {
                    spot = p;
                    break;
                }
            }
            if (spot != null) break;
        }
        if (spot == null) {
            BlockPos house = builtAt(villageId, "storage");
            spot = Trades.floorSpot(level, house != null ? house : v.centre(), 6);
        }
        if (spot == null || !inStoreArea(villageId, spot)) return null;
        // Knocked together and set down by a hand from the village (TownJobs). (Unpaid hands
        // are the rule for this one: whatever needed the room waits a moment at the door.)
        if (!TownJobs.atWork(level, v, "stores", spot, "setting down another store chest")) return null;
        if (!TownWork.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 8)
                && !TownWork.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 2)) return null;
        level.setBlock(spot, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, spot);
        return spot;
    }

    /** The wall rings the square (village/TownPlan). */
    private static final int WALL_RADIUS = com.jrpetty.mcassistant.village.TownPlan.PLAZA;

    /**
     * How far out the town's buildings reach, a block of the plan beyond the last one
     * built on: the streets are laid that far, and fields, woods, mines and pens are
     * staked outside it, so the town has its ground to grow into.
     */
    public static int townReach(UUID villageId) {
        int b = 0;
        for (String s : BUILT.getOrDefault(villageId, List.of())) if (!"colony".equals(s)) b++;
        int rings = b <= 14 ? 1 : b <= 44 ? 2 : 3;
        return com.jrpetty.mcassistant.village.TownPlan.RING + rings * com.jrpetty.mcassistant.village.TownPlan.PERIOD + 2;
    }

    // ------------------------------------------------------------------ the farmland

    /**
     * The town's own ground, never farmed: the square, its first block of lots and the street
     * round them (village/TownPlan), forty-one blocks out.
     */
    public static final int FIRST_BLOCK = com.jrpetty.mcassistant.village.TownPlan.RING
        + com.jrpetty.mcassistant.village.TownPlan.PERIOD + 2;

    /** From one field to the next: a full-grown field and a two-block lane between. */
    public static final int FIELD_STEP = 2 * VillageFolkEntity.FIELD_MOST + 3;

    /** How far out the farmland's squares are laid (they go on past the town's plan). */
    private static final int FIELD_ROWS = 6;

    /**
     * Which side of the town is its farmland (TownPlan.NORTH..WEST), or -1 before the village
     * has chosen. The village chooses once, by the ground (VillageFolkEntity.chooseFieldsSide):
     * its fields go out that way, side by side, and the town grows the other three ways.
     */
    public static int fieldsSide(@Nullable UUID villageId) {
        if (villageId == null) return -1;
        String note = com.jrpetty.mcassistant.village.Ledger.note(villageId, "fields.side");
        if (note == null || note.isEmpty()) return -1;
        try {
            int side = Integer.parseInt(note.trim());
            return side >= 0 && side <= 3 ? side : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void setFieldsSide(UUID villageId, int side) {
        com.jrpetty.mcassistant.village.Ledger.note(villageId, "fields.side", Integer.toString(side));
    }

    /** How far out this offset from the heart is, toward the given side. */
    public static int along(int side, int dx, int dz) {
        return switch (side) {
            case com.jrpetty.mcassistant.village.TownPlan.EAST -> dx;
            case com.jrpetty.mcassistant.village.TownPlan.WEST -> -dx;
            case com.jrpetty.mcassistant.village.TownPlan.SOUTH -> dz;
            default -> -dz;
        };
    }

    /** How far this offset is to one side of the line out to the given side. */
    public static int across(int side, int dx, int dz) {
        return side == com.jrpetty.mcassistant.village.TownPlan.EAST || side == com.jrpetty.mcassistant.village.TownPlan.WEST ? dz : dx;
    }

    /** The offset (dx, dz) from the heart that is this far out toward the side and this far across. */
    public static int[] offset(int side, int along, int across) {
        return switch (side) {
            case com.jrpetty.mcassistant.village.TownPlan.EAST -> new int[]{ along, across };
            case com.jrpetty.mcassistant.village.TownPlan.WEST -> new int[]{ -along, across };
            case com.jrpetty.mcassistant.village.TownPlan.SOUTH -> new int[]{ across, along };
            default -> new int[]{ across, -along };
        };
    }

    /**
     * The village's farmland, laid out before a furrow is cut: full-grown fields (twenty-seven
     * across) side by side on one side of the town, a two-block lane between each and the next,
     * starting just past the town's first block (FIRST_BLOCK) and going on out — the nearest
     * first. The lane up the middle is the avenue, carried on out as a farm track. Each is an
     * offset (dx, dz) from the heart to the field's middle. The fields widen as they go out (each
     * row keeps inside the diagonals from the heart), so the town can grow round the other three
     * sides without ever coming up against them.
     */
    public static java.util.List<int[]> fieldSquares(int side) {
        return FIELD_SQUARES.get(Math.floorMod(side, 4));
    }

    /** The farmland's squares for each side, worked out once (they are the same for every village). */
    private static final java.util.List<java.util.List<int[]>> FIELD_SQUARES = java.util.List.of(
        layFieldSquares(0), layFieldSquares(1), layFieldSquares(2), layFieldSquares(3));

    private static java.util.List<int[]> layFieldSquares(int side) {
        int most = VillageFolkEntity.FIELD_MOST;
        int first = FIRST_BLOCK + most + 1;
        int lane = com.jrpetty.mcassistant.village.TownPlan.AVENUE + 1 + most;
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int row = 0; row < FIELD_ROWS; row++) {
            int far = first + row * FIELD_STEP;
            for (int k = 0; ; k++) {
                int across = lane + k * FIELD_STEP;
                if (across > far - most) break;
                out.add(offset(side, far, across));
                out.add(offset(side, far, -across));
            }
        }
        return java.util.List.copyOf(out);
    }

    /** Do two boxes, each a centre and a half-width each way, overlap (with a gap of this many blocks wanted between)? */
    static boolean boxesMeet(int ax, int az, int ahx, int ahz, int bx, int bz, int bhx, int bhz, int gap) {
        return Math.abs(ax - bx) <= ahx + bhx + gap && Math.abs(az - bz) <= ahz + bhz + gap;
    }

    /**
     * Is this box (an offset from the heart and its half-widths) on the village's farmland — on
     * any of its fields, sown or still to be? Nothing but fields goes there: not the town's
     * houses, not a mine, not a wood.
     */
    public static boolean onFarmland(@Nullable UUID villageId, int dx, int dz, int hx, int hz) {
        int side = fieldsSide(villageId);
        if (side < 0) return false;
        int most = VillageFolkEntity.FIELD_MOST;
        // Nowhere near the farmland's side of the town: no need to look at its squares one by one.
        int al = along(side, dx, dz), ac = Math.abs(across(side, dx, dz));
        int reachOut = Math.max(hx, hz);
        if (al + reachOut < FIRST_BLOCK || ac - reachOut > al + reachOut + most) return false;
        for (int[] f : fieldSquares(side)) {
            if (boxesMeet(dx, dz, hx, hz, f[0], f[1], most, most, 0)) return true;
        }
        return false;
    }

    /** The same, for a plot this big round a spot. */
    public static boolean onFarmland(@Nullable UUID villageId, BlockPos heart, BlockPos spot, int plotRadius) {
        return onFarmland(villageId, spot.getX() - heart.getX(), spot.getZ() - heart.getZ(), plotRadius, plotRadius);
    }

    /**
     * Is this lot of the town's plan kept off? It is if it lies on the farmland, or on ground
     * somebody already works for good — a field (sown before the village chose its farmland),
     * a pen or the hives: the town builds round its fields and pastures, never over them.
     */
    public static boolean lotKeptOff(UUID villageId, BlockPos heart, com.jrpetty.mcassistant.village.TownPlan.Lot lot) {
        boolean turned = lot.back() == com.jrpetty.mcassistant.village.TownPlan.EAST
            || lot.back() == com.jrpetty.mcassistant.village.TownPlan.WEST;
        int hx = turned ? lot.halfDeep() : lot.halfAcross();
        int hz = turned ? lot.halfAcross() : lot.halfDeep();
        if (lot.kind() == com.jrpetty.mcassistant.village.TownPlan.Kind.SQUARE) return false;
        if (onFarmland(villageId, lot.x(), lot.z(), hx, hz)) return true;
        if (Railways.crosses(villageId, heart.getX() + lot.x() - hx, heart.getZ() + lot.z() - hz,
                heart.getX() + lot.x() + hx, heart.getZ() + lot.z() + hz)) return true;   // [transport] a railway runs there
        for (AssistantEntity a : folkOf(villageId)) {
            AssistantEntity.StationTask t = a.stationTask();
            if (t != AssistantEntity.StationTask.FARM && t != AssistantEntity.StationTask.RANCH
                && t != AssistantEntity.StationTask.BEEKEEP) continue;
            WorkZone z = a.workZone();
            if (z == null) continue;
            if (boxesMeet(lot.x(), lot.z(), hx, hz, z.center().getX() - heart.getX(), z.center().getZ() - heart.getZ(),
                    z.radius(), z.radius(), 1)) return true;
        }
        return false;
    }

    /** Has the town built (or claimed for building) any lot this box would overlap? */
    public static boolean builtOver(UUID villageId, int dx, int dz, int hx, int hz) {
        java.util.Set<Long> taken = LOT_TAKEN.get(villageId);
        if (taken == null || taken.isEmpty()) return false;
        int half = com.jrpetty.mcassistant.village.TownPlan.LOT / 2;
        for (com.jrpetty.mcassistant.village.TownPlan.Lot lot : com.jrpetty.mcassistant.village.TownPlan.lots()) {
            if (lot.kind() != com.jrpetty.mcassistant.village.TownPlan.Kind.LOT || !taken.contains(lot.key())) continue;
            if (boxesMeet(dx, dz, hx, hz, lot.x(), lot.z(), half, half, 1)) return true;
        }
        return false;
    }

    /** Is this spot, with a plot this big round it, clear of the town's ground? */
    public static boolean outsideTown(UUID villageId, BlockPos centre, BlockPos spot, int plotRadius) {
        int d = Math.max(Math.abs(spot.getX() - centre.getX()), Math.abs(spot.getZ() - centre.getZ()));
        return d - plotRadius > townReach(villageId);
    }

    /** A direction for the plan's numbering of them. */
    public static net.minecraft.core.Direction direction(int back) {
        return switch (back) {
            case com.jrpetty.mcassistant.village.TownPlan.EAST -> net.minecraft.core.Direction.EAST;
            case com.jrpetty.mcassistant.village.TownPlan.SOUTH -> net.minecraft.core.Direction.SOUTH;
            case com.jrpetty.mcassistant.village.TownPlan.WEST -> net.minecraft.core.Direction.WEST;
            default -> net.minecraft.core.Direction.NORTH;
        };
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
        // The Village Storehouse goes into the storehouse shed that already stands: the same
        // ground, the same way round, so its units fill the middle of the shed.
        if (project.equals("storehouse")) {
            com.jrpetty.mcassistant.village.Ledger.Building shed = builtStructure(villageId, "storage");
            if (shed != null) return new Site(shed.anchor(), shed.facing(), 0);
        }
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
        } else if (project.equals("court")) {
            // The courtyard lies before the board, its back along the board's foot: no lot of its own.
            site = courtSite(level, villageId);
        } else {
            java.util.Set<Long> bad = BAD_LOTS.computeIfAbsent(villageId, k -> ConcurrentHashMap.newKeySet());
            java.util.Set<Long> taken = LOT_TAKEN.computeIfAbsent(villageId, k -> ConcurrentHashMap.newKeySet());
            int heartGround = heartGround(level, v.centre());
            int laps = LAPS.getOrDefault(villageId, 0);
            int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(project);
            boolean great = "great".equals(com.jrpetty.mcassistant.village.TownPlan.placeFor(project));
            // The town plan's places for this kind of building, best first; of the first few
            // that will do, the one that costs least to build on — a flat lot a little further
            // down the list beats a slope that wants fifty blocks of stone under its floor.
            com.jrpetty.mcassistant.village.TownPlan.Lot best = null;
            int bestScore = Integer.MAX_VALUE;
            int valid = 0;
            int free = 0;
            int index = 0;
            boolean waiting = false;
            // The great lot behind the board is the leader's hall's: kept for it, and offered it first.
            com.jrpetty.mcassistant.village.TownPlan.Lot seat = built(villageId, "townhall") < 1 ? seatLot(villageId) : null;
            // The plan's lots for it, its own quarter's first (Quarters: the market round the square,
            // the crafts on their side, the homes on theirs).
            List<com.jrpetty.mcassistant.village.TownPlan.Lot> places = new ArrayList<>(Quarters.candidates(villageId, project));
            if (seat != null) {
                places.remove(seat);
                if (project.equals("townhall")) places.add(0, seat);
            }
            int[] court = courtRect(villageId);
            for (com.jrpetty.mcassistant.village.TownPlan.Lot lot : places) {
                if (valid >= 6) break;
                index++;
                boolean spoken = false;
                for (long cell : lot.cells()) if (taken.contains(cell)) { spoken = true; break; }
                if (spoken) continue;
                // Nothing on the square where the courtyard goes.
                if (court != null && lot.kind() == com.jrpetty.mcassistant.village.TownPlan.Kind.SQUARE
                        && overlaps(court, v.centre().getX() + lot.x(), v.centre().getZ() + lot.z(), lot.halfAcross(), lot.halfDeep())) continue;
                // The farmland and the pastures are kept off: the town grows round them.
                if (lotKeptOff(villageId, v.centre(), lot)) continue;
                int x = v.centre().getX() + lot.x();
                int z = v.centre().getZ() + lot.z();
                if (bad.contains(BlockPos.asLong(x, 0, z))) continue;
                // Too big for the lot (a hall on an ordinary lot): not this one.
                if (half[0] > lot.halfAcross() || half[1] > lot.halfDeep()) continue;
                free++;
                net.minecraft.core.Direction back = direction(lot.back());
                boolean turned = back.getAxis() == net.minecraft.core.Direction.Axis.X;
                int hx = turned ? half[1] : half[0], hz = turned ? half[0] : half[1];
                // A lot whose ground has not arrived yet has not been found wanting. The ring
                // of chunks round a new village comes in over its first minute, and every lot
                // looked at before that used to be written off for good.
                if (!lotLoaded(level, x, z, hx, hz)) { waiting = true; continue; }
                // The great buildings (the hall above all: no Iron Age without one) want the most
                // ground of anything, and on a mountainside found none flat to six blocks across
                // twenty: a town of fifty-eight sat in the Stone Age for want of one. Missed on
                // look after look, they are let onto steeper ground, terraced up under the floor.
                BlockPos ground = groundFor(level, x, z, hx, hz, true, heartGround, laps,
                    great ? 4 : 2, whyNot(villageId));
                if (ground == null) continue;
                if (Floods.lowGround(villageId, ground)) continue;     // [disasters] not on the low ground the river came over
                // [districts] A park's lawn at the middle height of its lot, cut and filled to it (ParkGround).
                ground = ParkGround.floorFor(level, project, ground);
                valid++;
                int score = com.jrpetty.mcassistant.entity.goal.BuildGoal.fillCells(level, ground, back, half[0], half[1]).size()
                    + 6 * index + Quarters.misfit(villageId, project, lot)      // out of its own quarter: the worse
                    + ParkGround.roughness(level, project, ground);             // [districts] a park: earth to cut, a drop's edge
                if (score < bestScore) {
                    bestScore = score;
                    best = lot;
                    site = new Site(ground, back, 0);
                }
            }
            if (site != null) {
                for (long cell : best.cells()) taken.add(cell);
            } else if (!waiting && free > 0) {
                LAPS.merge(villageId, 1, Integer::sum);         // a whole look and nothing: less particular next time
            }
        }
        if (site != null) pending.put(project, site);
        return site;
    }

    /**
     * The great lot behind the board (the nearest to it of the long lots by the square): the leader's
     * hall's, so that it stands at the back of the courtyard where the village gathers. Null if the
     * village has no board.
     */
    @Nullable
    static com.jrpetty.mcassistant.village.TownPlan.Lot seatLot(UUID villageId) {
        Village v = get(villageId);
        BlockPos board = VillageBoards.boardOf(villageId);
        net.minecraft.core.Direction f = VillageBoards.facingOf(villageId);
        if (v == null || board == null || f == null) return null;
        BlockPos mid = board.relative(com.jrpetty.mcassistant.block.VillageBoardBlock.right(f), com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE / 2);
        int bx = mid.getX() - v.centre().getX(), bz = mid.getZ() - v.centre().getZ();
        com.jrpetty.mcassistant.village.TownPlan.Lot best = null;
        long bestD = Long.MAX_VALUE;
        for (com.jrpetty.mcassistant.village.TownPlan.Lot l : com.jrpetty.mcassistant.village.TownPlan.lots()) {
            if (!l.use().equals("great")) continue;
            long d = (long) (l.x() - bx) * (l.x() - bx) + (long) (l.z() - bz) * (l.z() - bz);
            if (d < bestD) { bestD = d; best = l; }
        }
        return best;
    }

    /** Where the courtyard goes: before the board, its back along the board's foot, its middle four blocks out. */
    @Nullable
    static Site courtSite(net.minecraft.server.level.ServerLevel level, UUID villageId) {
        BlockPos board = VillageBoards.boardOf(villageId);
        net.minecraft.core.Direction f = VillageBoards.facingOf(villageId);
        if (board == null || f == null) return null;
        BlockPos c = board.relative(com.jrpetty.mcassistant.block.VillageBoardBlock.right(f), com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE / 2)
            .relative(f, 4);
        if (!level.hasChunk(c.getX() >> 4, c.getZ() >> 4)) return null;
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
        return new Site(new BlockPos(c.getX(), Math.min(y, board.getY()), c.getZ()), f.getOpposite(), 0);
    }

    /** The courtyard's ground, as {minX, maxX, minZ, maxZ}, or null if there is no board. */
    @Nullable
    static int[] courtRect(UUID villageId) {
        BlockPos board = VillageBoards.boardOf(villageId);
        net.minecraft.core.Direction f = VillageBoards.facingOf(villageId);
        if (board == null || f == null) return null;
        BlockPos c = board.relative(com.jrpetty.mcassistant.block.VillageBoardBlock.right(f), com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE / 2)
            .relative(f, 4);
        int across = 5, deep = 3;
        boolean alongX = f.getAxis() == net.minecraft.core.Direction.Axis.X;
        int hx = alongX ? deep : across, hz = alongX ? across : deep;
        return new int[]{ c.getX() - hx, c.getX() + hx, c.getZ() - hz, c.getZ() + hz };
    }

    /** Tests: the centre of the lot kept for the leader's hall, as an offset from the heart, or null. */
    @Nullable
    public static int[] seatLotForTests(UUID villageId) {
        com.jrpetty.mcassistant.village.TownPlan.Lot l = seatLot(villageId);
        return l == null ? null : new int[]{ l.x(), l.z() };
    }

    /** Tests: the courtyard's ground {minX, maxX, minZ, maxZ}, or null. */
    @Nullable
    public static int[] courtRectForTests(UUID villageId) {
        return courtRect(villageId);
    }

    private static boolean overlaps(int[] r, int x, int z, int hx, int hz) {
        return x + hx >= r[0] && x - hx <= r[1] && z + hz >= r[2] && z - hz <= r[3];
    }

    /**
     * [econ-housing] A folk's own house (HousingMarket) holds its lot as a project does (siteFor, under its drawing's
     * name, so the gardens, the woods and the sweepers keep off it while it goes up): a lot the household found for itself
     * (steeper or wetter than the council builds on, the made ground at its own cost), or its site again after a restart,
     * its plan lot spoken for.
     */
    public static void holdPrivateSite(UUID villageId, String key, Site site) {
        SITES.computeIfAbsent(villageId, k -> new ConcurrentHashMap<>()).put(key, site);
        Village v = get(villageId);
        if (v == null) return;
        java.util.Set<Long> taken = LOT_TAKEN.computeIfAbsent(villageId, k -> ConcurrentHashMap.newKeySet());
        for (com.jrpetty.mcassistant.village.TownPlan.Lot lot : com.jrpetty.mcassistant.village.TownPlan.lots()) {
            if (v.centre().getX() + lot.x() != site.anchor().getX() || v.centre().getZ() + lot.z() != site.anchor().getZ()) continue;
            for (long cell : lot.cells()) taken.add(cell);
        }
    }

    /**
     * [econ-housing] A folk's own house stands: its site comes off the list of what is going up, its lot spoken for as any
     * built on; given up before a block is laid (the household could not pay after all), the lot is free again.
     */
    public static void privateSiteDone(UUID villageId, String key, boolean free) {
        Map<String, Site> pending = SITES.get(villageId);
        Site gone = pending == null ? null : pending.remove(key);
        Village v = get(villageId);
        if (!free || gone == null || v == null) return;
        java.util.Set<Long> taken = LOT_TAKEN.get(villageId);
        if (taken == null) return;
        for (com.jrpetty.mcassistant.village.TownPlan.Lot lot : com.jrpetty.mcassistant.village.TownPlan.lots()) {
            if (v.centre().getX() + lot.x() != gone.anchor().getX() || v.centre().getZ() + lot.z() != gone.anchor().getZ()) continue;
            for (long cell : lot.cells()) taken.remove(cell);
        }
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
        crewDone(villageId, project);
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
        return "lap " + LAPS.getOrDefault(villageId, 0) + ", " + LOT_TAKEN.getOrDefault(villageId, java.util.Set.of()).size() + " of "
            + com.jrpetty.mcassistant.village.TownPlan.lots().size() + " lots spoken for"
            + "; turned down: wet " + w[WET] + ", cliff " + w[CLIFF] + ", too high or low " + w[HEIGHT]
            + ", built on or rocky " + w[BLOCKED] + ", given up " + w[TAKEN]
            + (why == null || why.lastCliff.isEmpty() ? "" : "; last cliff " + why.lastCliff);
    }

    /** Is the ground a lot here would stand on all in the world yet? */
    private static boolean lotLoaded(net.minecraft.server.level.ServerLevel level, int x, int z, int hx, int hz) {
        for (int dx : new int[]{ -hx, 0, hx }) {
            for (int dz : new int[]{ -hz, 0, hz }) {
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
        return groundFor(level, x, z, 3, 3, needsClearance, heartGround, laps, 2, why);
    }

    /** As above, for a footprint {@code hx} blocks each side across x and {@code hz} across z. */
    @Nullable
    private static BlockPos groundFor(net.minecraft.server.level.ServerLevel level, int x, int z, int hx, int hz,
                                      boolean needsClearance, int heartGround, int laps, int mostLaps, @Nullable Why why) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx : new int[]{ -hx, 0, hx }) {
            for (int dz : new int[]{ -hz, 0, hz }) {
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
        if (slope > 4 + Math.min(laps, mostLaps)) {          // a cliff, not a lot
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
        for (int dx = -hx; dx <= hx; dx++) {
            for (int dz = -hz; dz <= hz; dz++) {
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
        int area = (2 * hx + 1) * (2 * hz + 1);
        if (blocked > Math.max(10, area / 5) + 12 * Math.min(laps, mostLaps)) { why(why, BLOCKED); return null; }
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
