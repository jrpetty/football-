package com.jrpetty.mcassistant.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [nether] A Nether run, planned before the runners set out: by what the town needs out of the Nether, how far it is to
 * get it, and what the town can spare to send them with.
 *
 * <p><b>The needs</b> (needs), each what the town keeps of a thing against what its stores hold:
 * <ul>
 * <li>the brewer's nether wart (sixteen, or four for seed once the wart farm at home has four plants growing) and its
 *     blaze rods (eight: blaze powder to fire the stand, for strength, for the magma cream of fire resistance, and one
 *     day for eyes of ender);</li>
 * <li>quartz for the builders (sixty-four: the Nether Age's great buildings are trimmed in quartz brick), glowstone dust
 *     for the lamps (thirty-two: a lamp is four);</li>
 * <li>soul sand for the wart farm (eight, till the farm has its eight blocks);</li>
 * <li>what only the piglins give: ender pearls (twelve, for later), obsidian (sixteen: enchanting tables, and a gateway
 *     for a colony), magma cream (four).</li>
 * </ul>
 * The enchanter's lapis is the miners' and its books the tailor's: not the runners' business.
 *
 * <p><b>The work</b> each need asks: quartz and glowstone are dug near the outpost; wart, soul sand and blaze rods want
 * the fortress (found by the runners, and reached along their own path); pearls, obsidian and magma cream are bartered
 * from the piglins with the town's gold, if it can spare any. A first run builds the outpost first (an hour and a half).
 *
 * <p><b>How long</b>: the work, at so long a piece (a quartz ore and a half a minute; a blaze rod a quarter hour, two
 * blazes to a rod; a barter six seconds and the walk), plus the walk through the gateway and out to the work and back
 * (three blocks a second, the fortress's path twice), over ten hours of a working day, to the half day; half a day at
 * the least, three days at the most; a day more when the brewer is out of wart with no farm, or out of fire
 * resistance. Then cut to what the town can spare: two meals a runner a day and a day over, thirty-two arrows a runner
 * a day out, two potions of fire resistance a runner a day (a run without them is kept to the outpost's doorstep: no
 * blazes), and the cobblestone to wall and bridge with.
 */
public final class NetherPlan {

    private NetherPlan() {}

    /** A working day of daylight, in ticks; the shortest and the longest run, in days. */
    static final double DAYLIGHT = 10000.0, SHORTEST = 0.5, LONGEST = 3.0;
    /** Meals, arrows and fire resistance a runner a day; cobblestone a runner for bridging, and the outpost's. */
    static final int MEALS = 2, ARROWS = 32, POTIONS = 2, COBBLE = 32, OUTPOST_COBBLE = 128;
    /** Gold the town keeps whatever the runners want to barter with; the most they take on one run. */
    static final int GOLD_KEPT = 8, GOLD_MOST = 16;

    /** One thing the town wants out of the Nether: what, how many it keeps, how many it has, and for whom. */
    public record Need(String what, Item item, int keep, int have, String forWhom) {
        public int want() {
            return Math.max(0, keep - have);
        }
    }

    /** The run, reckoned: days, what it goes for, what each runner packs, and the plan in words with its reckoning. */
    public record Plan(double days, List<Need> needs, List<String> work, int meals, int arrows, int potions, int cobble, int gold,
                       boolean outpost, boolean fortress, String words, List<String> reckoning) {
        public int nights() {
            return Math.max(0, (int) Math.ceil(days) - 1);
        }

        /** Does it go for this (quartz, glowstone, wart, blaze, soul, barter)? */
        public boolean wants(String job) {
            return work.contains(job);
        }
    }

    /** Tests: every run planned this many days long (null: as reckoned). */
    @Nullable private static Double daysForTests;

    public static void daysForTests(@Nullable Double days) {
        daysForTests = days;
    }

    public static void resetForTests() {
        daysForTests = null;
    }

    static int stock(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    static boolean fireResistance(ItemStack s) {
        return (s.is(Items.POTION) || s.is(Items.SPLASH_POTION))
            && s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.FIRE_RESISTANCE)
            || s.is(Items.POTION) && s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.LONG_FIRE_RESISTANCE);
    }

    /** What the town wants out of the Nether, the most wanted first. */
    public static List<Need> needs(ServerLevel level, UUID village) {
        List<Need> out = new ArrayList<>();
        int farm = NetherHome.wartPlants(village);
        boolean brewer = has(village, AssistantEntity.StationTask.BREW);
        out.add(new Need("nether wart", Items.NETHER_WART, farm >= 4 ? 4 : brewer ? 16 : 8, stock(level, village, Items.NETHER_WART),
            farm >= 4 ? "seed for the wart farm" : "the brewer's awkward potions"));
        int powder = stock(level, village, Items.BLAZE_POWDER);
        out.add(new Need("blaze rods", Items.BLAZE_ROD, 8, stock(level, village, Items.BLAZE_ROD) + powder / 2,
            "the brewing stand's fire, and the magma cream for fire resistance"));
        out.add(new Need("quartz", Items.QUARTZ, 64, stock(level, village, Items.QUARTZ) + 4 * stock(level, village, Items.QUARTZ_BLOCK),
            "the quartz trim of the great buildings"));
        out.add(new Need("glowstone dust", Items.GLOWSTONE_DUST, 32, stock(level, village, Items.GLOWSTONE_DUST) + 4 * stock(level, village, Items.GLOWSTONE),
            "the glowstone lamps in the streets"));
        out.add(new Need("soul sand", Items.SOUL_SAND, NetherHome.farmWants(village), stock(level, village, Items.SOUL_SAND), "the wart farm at home"));
        out.add(new Need("ender pearls", Items.ENDER_PEARL, 12, stock(level, village, Items.ENDER_PEARL), "eyes of ender, one day"));
        out.add(new Need("obsidian", Items.OBSIDIAN, 16, stock(level, village, Items.OBSIDIAN) + stock(level, village, Items.CRYING_OBSIDIAN),
            "the enchanter's tables, and a gateway for a colony"));
        out.add(new Need("magma cream", Items.MAGMA_CREAM, 4, stock(level, village, Items.MAGMA_CREAM), "fire resistance"));
        // [nether] A player's ask comes first (NetherGuests): "bring us blaze rods" moves that need to the head of the list.
        String asked = NetherGuests.askedItem(level, village);
        if (asked != null) {
            for (int i = 0; i < out.size(); i++) {
                Need n = out.get(i);
                if (n.what().equals(asked)) {
                    out.remove(i);
                    out.add(0, new Need(n.what(), n.item(), Math.max(n.keep(), n.have() + 8), n.have(), "as a player asked"));
                    break;
                }
            }
        }
        return out;
    }

    private static boolean has(UUID village, AssistantEntity.StationTask t) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t) return true;
        return false;
    }

    /** The job a need asks of the runners. */
    static String job(Need n) {
        return switch (n.what()) {
            case "quartz" -> "quartz";
            case "glowstone dust" -> "glowstone";
            case "nether wart" -> "wart";
            case "blaze rods" -> "blaze";
            case "soul sand" -> "soul";
            default -> "barter";
        };
    }

    /** About how long a piece of each takes, in ticks (the digging, the fighting, the walking between). */
    static double per(String job) {
        return switch (job) {
            case "quartz" -> 50.0;          // an ore, the walk to the next, and a bit over a quartz to an ore
            case "glowstone" -> 45.0;       // a block a few dust, a pillar up to it now and then
            case "wart" -> 25.0;
            case "soul" -> 20.0;
            case "blaze" -> 300.0;          // two blazes to a rod, shot from range
            default -> 160.0;               // a barter: the gold thrown, six seconds admired, the haul picked up
        };
    }

    /** The food the town can spare: what its stores hold over a day's meals for everybody. */
    static int spareFood(ServerLevel level, UUID village) {
        if (Market.hungry(village)) return 0;
        int stock = Market.stock(level, village, CaveDwellers::food);
        return Math.max(0, stock - Villages.headcount(village) * PackedLunch.DAY);
    }

    /** The town's gold it can spare the piglins: what it has over a few kept. */
    static int spareGold(ServerLevel level, UUID village) {
        return Math.max(0, stock(level, village, Items.GOLD_INGOT) - GOLD_KEPT);
    }

    /**
     * The run, reckoned for this team out of what the town can spare. A plan of no days means it cannot go (no food, no
     * outpost and no cobblestone to build it).
     */
    public static Plan plan(ServerLevel level, Villages.Village v, List<VillageFolkEntity> team) {
        UUID id = v.id();
        int heads = Math.max(1, team.size());
        List<String> reckoning = new ArrayList<>();
        List<Need> all = needs(level, id);
        List<Need> wanted = new ArrayList<>();
        for (Need n : all) if (n.want() > 0) wanted.add(n);
        boolean outpost = !NetherOutpost.built(id);
        NetherRuns.Find fort = NetherRuns.fortress(id);
        int gold = Math.min(GOLD_MOST, spareGold(level, id));
        for (VillageFolkEntity m : team) gold += m.countMatching(s -> s.is(Items.GOLD_INGOT));
        double work = 0;
        List<String> jobs = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (Need n : wanted) {
            String job = job(n);
            if (job.equals("barter") && gold <= 0) {
                reckoning.add("not " + n.what() + ": no gold the town can spare to barter with");
                continue;
            }
            if (!jobs.contains(job)) jobs.add(job);
            int want = Math.min(n.want(), job.equals("blaze") ? 8 : job.equals("barter") ? Math.min(gold, 8) : 64);
            double t = want * per(job) / Math.max(1.0, heads * 0.8);
            work += t;
            parts.add(want + " " + n.what() + " (" + n.forWhom() + ")");
        }
        if (outpost) {
            work += 1500.0;
            reckoning.add("the outpost: a safe room walled round the portal on the far side, the first thing done");
        }
        if (jobs.isEmpty() && !outpost) jobs.add("quartz");                    // nothing short: the builders always use quartz
        reckoning.add("wants: " + (parts.isEmpty() ? "nothing short, so quartz for the builders" : String.join(", ", parts)));
        // The walk: to the gateway and through, out to the work and back; the fortress's path twice when it goes there.
        boolean toFort = fort != null && (jobs.contains("wart") || jobs.contains("blaze") || jobs.contains("soul"));
        double walk = 2.0 * 40 * 20.0 / 3.0;                                   // the gateway and out to the work, and back
        if (toFort) walk += 2.0 * Math.max(16, fort.a()) * 20.0 / 3.0;
        reckoning.add("work: about " + hours(work) + "; walk: about " + hours(walk) + (toFort ? " (the fortress " + fort.a() + " blocks out)" : ""));
        double days = Math.max(SHORTEST, Math.min(LONGEST, Math.round((work + walk) / DAYLIGHT * 2.0) / 2.0));
        // Urgency: the brewer out of wart with no farm, or the town out of fire resistance.
        int wart = stock(level, id, Items.NETHER_WART), fireRes = Market.stock(level, id, NetherPlan::fireResistance);
        if ((wart == 0 && NetherHome.wartPlants(id) < 4 || fireRes == 0 && has(id, AssistantEntity.StationTask.BREW)) && days < LONGEST && days >= 1.0) {
            days += 1.0;
            reckoning.add("urgency: a day more: " + (wart == 0 ? "the brewer is out of wart" : "the town is out of fire resistance"));
        }
        if (daysForTests != null) {
            days = daysForTests;
            reckoning.add("(the test's " + CaveTrips.daysWords(days).toLowerCase(Locale.ROOT) + ")");
        }
        double planned = days;
        // What the town can spare cuts it down.
        int food = spareFood(level, id), arrows = Math.max(0, stock(level, id, Items.ARROW) - 16), potions = Market.stock(level, id, NetherPlan::fireResistance);
        int cobble = Math.max(0, stock(level, id, Items.COBBLESTONE) - 32);
        for (VillageFolkEntity m : team) {
            food += m.countMatching(CaveDwellers::food);
            arrows += m.countMatching(s -> s.is(Items.ARROW));
            potions += m.countMatching(NetherPlan::fireResistance);
            cobble += m.countMatching(s -> s.is(Items.COBBLESTONE));
        }
        double foodDays = food / (double) (MEALS * heads) - 1.0;
        String cut = "";
        if (food < heads) {
            days = 0;
            cut = "nothing to eat to be had";
        } else if (outpost && cobble < 48) {
            days = 0;
            cut = "not cobblestone enough to wall the outpost";
        } else {
            if (foodDays < days) {
                days = Math.max(SHORTEST, Math.floor(foodDays * 2.0 + 1e-6) / 2.0);
                cut = "the food";
            }
            if (arrows < ARROWS * heads / 2 && days > 1.0) {
                days = 1.0;
                cut = cut.isEmpty() ? "the arrows" : cut + " and the arrows";
            }
        }
        if (potions < heads && jobs.contains("blaze")) {
            jobs.remove("blaze");
            reckoning.add("no blazes this time: no fire resistance to go into a fortress with");
        }
        reckoning.add("to spare: food for " + Math.max(0, Math.floor(foodDays * 2) / 2.0) + " days, " + arrows + " arrows, " + potions
            + " potions of fire resistance, " + cobble + " cobblestone, " + gold + " gold to barter");
        if (!cut.isEmpty()) reckoning.add("cut: " + (days <= 0 ? "no run: " + cut : "to " + CaveTrips.daysWords(days).toLowerCase(Locale.ROOT) + ", by " + cut));
        int ceil = Math.max(1, (int) Math.ceil(days));
        int meals = MEALS * (ceil + 1);
        int arrowsEach = ARROWS * ceil;
        int potionsEach = jobs.contains("blaze") ? POTIONS * ceil : 1;
        int cobbleEach = COBBLE + (outpost ? (OUTPOST_COBBLE + heads - 1) / heads : 0);
        int goldEach = jobs.contains("barter") ? (gold + heads - 1) / heads : 0;
        reckoning.add("pack: " + meals + " meals, " + arrowsEach + " arrows, " + potionsEach + " fire resistance, " + cobbleEach
            + " cobblestone a runner" + (goldEach > 0 ? ", " + Math.min(gold, GOLD_MOST) + " gold between them" : ""));
        // In words.
        StringBuilder w = new StringBuilder(CaveTrips.daysWords(days <= 0 ? SHORTEST : days)).append(" in the Nether");
        List<String> says = new ArrayList<>();
        if (outpost) says.add("the outpost to build round the portal first");
        List<String> what = new ArrayList<>();
        for (String j : jobs) {
            what.add(switch (j) {
                case "quartz" -> "quartz";
                case "glowstone" -> "glowstone";
                case "wart" -> "nether wart";
                case "blaze" -> "blaze rods";
                case "soul" -> "soul sand";
                default -> "a barter for " + barterWords(wanted);
            });
        }
        if (!what.isEmpty()) says.add(JobMarket.join(what) + (toFort ? " (the fortress " + fort.a() + " blocks from the outpost)" : ""));
        if (days > 0 && days < planned) says.add("cut to " + CaveTrips.daysWords(days).toLowerCase(Locale.ROOT) + " by " + cut);
        else if (days > 1.0) says.add("food and arrows for " + CaveTrips.numberWord(ceil));
        if (days <= 0) says.add("not today: " + cut);
        if (!says.isEmpty()) w.append(": ").append(String.join("; ", says));
        w.append('.');
        return new Plan(days, wanted, jobs, meals, arrowsEach, potionsEach, cobbleEach, goldEach, outpost, toFort, w.toString(), reckoning);
    }

    private static String barterWords(List<Need> wanted) {
        List<String> out = new ArrayList<>();
        for (Need n : wanted) if (job(n).equals("barter")) out.add(n.what());
        return out.isEmpty() ? "what the piglins give" : JobMarket.join(out);
    }

    private static String hours(double ticks) {
        double h = ticks / 1000.0;
        if (h < 1.0) return "under an hour";
        return Math.round(h) + (Math.round(h) == 1 ? " hour" : " hours");
    }

    /** When a run of so many days, set out at this day time, turns for home (as the cave team's do). */
    static long turnAt(long start, double days) {
        return CaveTrips.turnAt(start, days);
    }
}
