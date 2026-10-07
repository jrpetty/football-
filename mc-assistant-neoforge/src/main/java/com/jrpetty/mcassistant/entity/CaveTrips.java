package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [caves] The cave team's trips: planned before it sets out, kept with the town while it is away (over a restart too),
 * looked for when overdue, and camped through the nights of a long one.
 *
 * <p><b>The plan</b> (plan): the length of a trip is the work waiting, plus the walk, cut down to what the town can spare.
 * <ol>
 * <li>A cave nobody has mapped (no cave, or none of its veins listed) gets a day's scouting: map it, list its veins, mine
 *     what is close, and home. Later trips are planned from the list.</li>
 * <li>A known cave: <i>the work</i> is the hours to mine the listed veins the team's picks will take (each block as long
 *     as the pick needs, shared out, and a walk between veins), leaving out veins worth little of what the town has
 *     plenty; <i>the walk</i> is there and back, three blocks a second and the way down twice over. Walk and work over a
 *     working day of ten hours' daylight, to the half day: half a day to four days.</li>
 * <li>The town badly short of an ore the cave has (the age waiting on iron or diamonds): a day more.</li>
 * <li>Cut to what the town can spare: two meals a member a day and a day's spare (the stores never left short of the
 *     town's own day), sixty-four torches a member a day (and what its packs could make of their coal), and no more
 *     than a day for a team with a member short of its kit.</li>
 * </ol>
 * The plan in words goes on the board, into the chronicle and on the Caves page with its reckoning: "Three days to the
 * deep caves north-east: 14 veins listed (iron, the town's want), a day's walk there and back, food and torches for
 * three."
 *
 * <p><b>Away</b>: the trip is kept with the town (Ledger "caves.trip") while the team is out: the town counts it as its
 * team (it takes up nobody in its place, and its beds stay its own), the card and the Caves page say "on an expedition,
 * day 2 of 3", and a member that comes back into the world after a restart takes the trip up again where it is (resume).
 * A day past the plan and not home, the town sends a search party (overdue).
 *
 * <p><b>Nights</b> (camp): on a trip of more than a day the team makes camp at dusk in a nook out of the way, walls it
 * in with cobblestone, sets a torch inside, eats, and sleeps while one keeps watch, by turns; at first light it eats,
 * takes its walls down, and goes on.
 */
public final class CaveTrips {

    private CaveTrips() {}

    /** A working day of daylight, in ticks (ten hours). */
    static final double DAYLIGHT = 10000.0;
    /** The shortest and the longest trip, in days. */
    static final double SHORTEST = 0.5, LONGEST_DAYS = 4.0;
    /** Meals a member a day; torches a member a day out. */
    static final int MEALS = 2, TORCHES_A_DAY = 64;
    /** Cobblestone for a night's camp walls, a member. */
    static final int CAMP_COBBLE = 12;
    /** Dusk, when a long trip makes camp; first light, when it breaks camp. */
    static final long DUSK = 11800, DAWN = 300;

    /** Tests: the plan's days forced (null: as reckoned). */
    @Nullable private static Double daysForTests;

    public static void resetForTests() {
        daysForTests = null;
        RESUMED.clear();
    }

    /** Tests: every trip planned this many days long, whatever the reckoning says (null: as reckoned). */
    public static void daysForTests(@Nullable Double days) {
        daysForTests = days;
    }

    // ------------------------------------------------------------------ the plan

    /**
     * A trip planned: how many days, of what kind, to where, the work and the walk it was reckoned from, what each
     * member packs for it, and the plan in words with its reckoning.
     */
    public record Plan(double days, String kind, boolean mapped, @Nullable BlockPos cave, String where, int veins, int blocks,
                       List<String> ores, double workHours, double walkHours, int meals, int torches, int cobble, String words,
                       List<String> reckoning) {
        /** Nights out under the rock. */
        public int nights() {
            return Math.max(0, (int) Math.ceil(days) - 1);
        }
    }

    /** "Half a day", "A day", "A day and a half", "Two days". */
    static String daysWords(double d) {
        int whole = (int) Math.floor(d + 1e-6);
        boolean half = d - whole >= 0.49;
        String[] n = { "", "one", "two", "three", "four" };
        if (whole == 0) return "Half a day";
        if (whole == 1) return half ? "A day and a half" : "A day";
        String w = Character.toUpperCase(n[Math.min(4, whole)].charAt(0)) + n[Math.min(4, whole)].substring(1);
        return half ? w + " and a half days" : w + " days";
    }

    static double roundHalf(double d) {
        return Math.round(d * 2.0) / 2.0;
    }

    static double floorHalf(double d) {
        return Math.floor(d * 2.0 + 1e-6) / 2.0;
    }

    /** What an ore comes out as (for its worth and the stores' count of it). */
    static Item dropOf(String ore) {
        return switch (ore) {
            case "coal" -> Items.COAL;
            case "iron" -> Items.RAW_IRON;
            case "copper" -> Items.RAW_COPPER;
            case "gold" -> Items.RAW_GOLD;
            case "redstone" -> Items.REDSTONE;
            case "lapis" -> Items.LAPIS_LAZULI;
            case "diamond" -> Items.DIAMOND;
            case "emerald" -> Items.EMERALD;
            case "obsidian" -> Items.OBSIDIAN;
            case "amethyst" -> Items.AMETHYST_SHARD;
            default -> Items.AIR;
        };
    }

    /** A vein worth little that the town already has plenty of: left out of the reckoning. */
    static boolean littleWorth(ServerLevel level, UUID village, String ore, List<String> wanted) {
        if (wanted.contains(ore)) return false;
        Item it = dropOf(ore);
        if (it == Items.AIR) return false;
        int have = Market.stock(level, village, s -> s.is(it));
        return have >= 128 && PriceIndex.factor(village, new ItemStack(it)) < 0.9;
    }

    /** Is it dear in the town just now (the price index)? */
    static boolean dear(UUID village, String ore) {
        Item it = dropOf(ore);
        return it != Items.AIR && PriceIndex.factor(village, new ItemStack(it)) >= 1.25;
    }

    /** The food the town can spare: what the stores hold over a day's meals for everybody (nothing while it is hungry). */
    static int spareFood(ServerLevel level, UUID village) {
        if (Market.hungry(village)) return 0;
        int stock = Market.stock(level, village, CaveDwellers::food);
        return Math.max(0, stock - Villages.headcount(village) * PackedLunch.DAY);
    }

    /** Is a member short of its kit (no armour, no blade, or no pick)? */
    static boolean shortOfKit(ServerLevel level, Villages.Village v, VillageFolkEntity m) {
        boolean armour = false;
        for (net.minecraft.world.entity.EquipmentSlot s : new net.minecraft.world.entity.EquipmentSlot[]{
            net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET }) {
            if (!m.getItemBySlot(s).isEmpty()) armour = true;
        }
        boolean storesArmour = Market.stock(level, v.id(), s -> s.getItem() instanceof net.minecraft.world.item.ArmorItem) > 0;
        boolean blade = m.countCarried(CaveCraft::blade) > 0 || Market.stock(level, v.id(), CaveCraft::blade) > 0;
        boolean pick = !CaveDwellers.bestPick(m).isEmpty() || Market.stock(level, v.id(), CaveDwellers::isPickaxe) > 0;
        return !(armour || storesArmour) || !blade || !pick;
    }

    /**
     * The trip, reckoned: to this cave (null: out to find one), for this team, out of what the town can spare. A plan
     * of no days means the team cannot go (no food, or not torches enough even for half a day).
     */
    public static Plan plan(ServerLevel level, Villages.Village v, List<VillageFolkEntity> team, @Nullable BlockPos dest) {
        UUID id = v.id();
        int heads = Math.max(1, team.size());
        BlockPos home = v.centre();
        List<String> reckoning = new ArrayList<>();
        CaveDwellers.Find cave = dest == null ? null : CaveDwellers.caveNear(id, dest);
        BlockPos key = cave != null ? cave.at() : dest;
        List<CaveDwellers.Vein> list = key == null ? List.of() : CaveDwellers.veins(id, key);
        boolean mapped = !list.isEmpty();
        int dist = dest == null ? CaveDwellers.range(id) : (int) Math.sqrt(Scouts.flat(home, dest));
        int deep = cave != null ? cave.a() : dest != null ? Math.max(0, home.getY() - dest.getY()) : 20;
        String heading = dest == null ? "round about" : Guide.direction(home, dest);
        String place;
        if (dest == null) place = "the caves round about, to find one and map it";
        else if (!mapped) place = (cave != null ? "the " + cave.label().replaceFirst("^an? ", "") : "the cave") + " " + heading + ", to map it";
        else if (deep >= 40) place = "the deep caves " + heading;
        else place = (cave != null ? "the " + cave.label().replaceFirst("^an? ", "") : "the cave") + " " + heading;
        reckoning.add("where: " + place + (dest == null ? "" : ", " + dist + " blocks out and " + deep + " down"));
        // The walk there and back: three blocks a second, and the way down counted twice over.
        double walk = 2.0 * (dist + 2.0 * deep) * 20.0 / 3.0;
        double work = 0;
        int veins = 0, blocks = 0, skipped = 0;
        Set<String> ores = new LinkedHashSet<>();
        List<String> wanted = CaveDwellers.wantedOres(level, id);
        double days;
        if (!mapped) {
            days = 1.0;
            reckoning.add("work: nobody has mapped it yet: a day to map it, list its veins and mine what is close");
        } else {
            List<ItemStack> picks = new ArrayList<>();
            VillageFolkEntity lead = null;
            for (VillageFolkEntity m : team) {
                ItemStack pick = CaveDwellers.bestPick(m);
                if (!pick.isEmpty()) picks.add(pick);
                if (lead == null || CaveDwellers.better(m, lead)) lead = m;
            }
            Workshop.Found stores = Workshop.bestInStores(level, id, CaveDwellers::isPickaxe, CaveDwellers::pickScore, -1);
            if (stores != null) picks.add(stores.box().getItem(stores.slot()));
            for (CaveDwellers.Vein vn : list) {
                if (!"todo".equals(vn.state()) && !"waiting".equals(vn.state())) continue;
                BlockState st = CaveDwellers.oreState(vn.ore());
                if (st == null || !takes(picks, st)) continue;               // waits on a better pick
                if (littleWorth(level, id, vn.ore(), wanted)) { skipped++; continue; }
                veins++;
                blocks += vn.size();
                ores.add(vn.ore());
                int per = lead != null && CaveDwellers.isPickaxe(lead.getMainHandItem()) ? lead.workTicksFor(st) : 60;
                work += (vn.size() * (per + 20.0) + vn.behind() * 40.0) / Math.max(1.0, heads * 0.75) + 200.0;
            }
            days = Math.max(SHORTEST, Math.min(LONGEST_DAYS, roundHalf((walk + work) / DAYLIGHT)));
            reckoning.add("work: " + veins + (veins == 1 ? " vein" : " veins") + " (" + blocks + " blocks) the team's picks will take, about "
                + hours(work));
            if (skipped > 0) reckoning.add("left out: " + skipped + (skipped == 1 ? " vein" : " veins") + " worth little, of what the stores have plenty");
            reckoning.add("walk: about " + hours(walk) + " there and back");
            reckoning.add("days: (" + hours(walk) + " + " + hours(work) + ") over ten hours a day: " + daysWords(days).toLowerCase(Locale.ROOT));
            // Urgency: the age waiting on an ore the cave has.
            try {
                for (Villages.Need n : Villages.needs(level, id)) {
                    boolean iron = n.task() == Villages.Task.IRON && ores.contains("iron") && n.amount() >= 16;
                    boolean gems = n.task() == Villages.Task.DIAMOND && ores.contains("diamond") && n.amount() >= 1;
                    if ((iron || gems) && days >= 1.0 && days < LONGEST_DAYS) {
                        days = Math.min(LONGEST_DAYS, days + 1.0);
                        reckoning.add("urgency: a day more: the age is waiting on " + (iron ? "iron" : "diamonds"));
                        break;
                    }
                }
            } catch (RuntimeException ex) {
                CaveDwellers.LOG.debug("[MCA-CAVES] needs look failed: {}", ex.toString());
            }
        }
        if (daysForTests != null) {
            days = daysForTests;
            reckoning.add("(the test's " + daysWords(days).toLowerCase(Locale.ROOT) + ")");
        }
        double planned = days;
        // What the town can spare cuts it down.
        int foodHave = spareFood(level, id), torchHave = CaveDwellers.spareTorches(level, id);
        for (VillageFolkEntity m : team) {
            foodHave += m.countMatching(CaveDwellers::food);
            torchHave += m.countMatching(s -> s.is(Items.TORCH)) + CaveCraft.torchesToMake(m);
        }
        double foodDays = foodHave / (double) (MEALS * heads) - 1.0;
        double torchDays = torchHave / (double) (TORCHES_A_DAY * heads);
        reckoning.add("to spare: food for " + Math.max(0, (int) Math.floor(foodDays * 2) / 2.0) + " days (" + foodHave + " meals), torches for "
            + Math.floor(torchDays * 2) / 2.0 + " days (" + torchHave + ")");
        String cut = "";
        if (foodHave < heads || torchHave < CaveDwellers.TORCHES_MIN * heads) {
            days = 0;
            cut = foodHave < heads ? "nothing to eat to be had" : "not torches enough to go";
        } else {
            if (foodDays < days) {
                days = Math.max(SHORTEST, floorHalf(foodDays));
                cut = "the food";
            }
            if (torchDays < days) {
                days = Math.max(SHORTEST, floorHalf(torchDays));
                cut = cut.isEmpty() ? "the torches" : cut + " and the torches";
            }
            boolean kitShort = false;
            for (VillageFolkEntity m : team) if (shortOfKit(level, v, m)) kitShort = true;
            if (kitShort && days > 1.0) {
                days = 1.0;
                cut = cut.isEmpty() ? "a member short of kit" : cut + ", and a member short of kit";
            }
        }
        if (!cut.isEmpty()) reckoning.add("cut: " + (days <= 0 ? "no trip: " + cut : "to " + daysWords(days).toLowerCase(Locale.ROOT) + ", by " + cut));
        int ceil = Math.max(1, (int) Math.ceil(days));
        int meals = MEALS * (ceil + 1);
        int torches = TORCHES_A_DAY * ceil;
        int nights = Math.max(0, ceil - 1);
        int cobble = CaveDwellers.COBBLE + CAMP_COBBLE * nights;
        reckoning.add("pack: " + meals + " meals, " + torches + " torches, " + cobble + " cobblestone a member");
        String kind = days <= 0 ? "none" : days <= SHORTEST ? "short" : days <= 1.0 ? "day" : "long";
        // In words.
        StringBuilder w = new StringBuilder(daysWords(days <= 0 ? SHORTEST : days)).append(" to ").append(place);
        List<String> parts = new ArrayList<>();
        if (mapped) {
            String which = null;
            for (String o : wanted) if (ores.contains(o)) { which = o + ", the town's want"; break; }
            if (which == null) for (String o : ores) if (dear(id, o)) { which = o + ", dear just now"; break; }
            if (veins == 0) parts.add("nothing on its list the team's picks will take: a look round it for more");
            else if (veins <= 3) parts.add(numberWord(veins) + " " + (ores.size() == 1 ? ores.iterator().next() + " " : "")
                + (veins == 1 ? "vein" : "veins") + " left");
            else parts.add(veins + " veins listed" + (which != null ? " (" + which + ")" : !ores.isEmpty() ? " (" + String.join(" and ", ores) + ")" : ""));
            double wh = walk / 1000.0;
            parts.add(wh < 1.0 ? "a short walk there and back" : wh < 3.0 ? "an hour or two's walk there and back"
                : wh < 6.0 ? "half a day's walk there and back" : "a day's walk there and back");
        }
        if (days > 0 && days < planned) parts.add("cut to " + daysWords(days).toLowerCase(Locale.ROOT) + " by " + cut);
        else if (days > 1.0) parts.add("food and torches for " + numberWord(ceil));
        if (days <= 0) parts.add("not today: " + cut);
        if (!parts.isEmpty()) w.append(": ").append(String.join(", ", parts));
        w.append('.');
        return new Plan(days, kind, mapped, dest, place, veins, blocks, new ArrayList<>(ores), work / 1000.0, walk / 1000.0, meals, torches,
            cobble, w.toString(), reckoning);
    }

    private static boolean takes(List<ItemStack> picks, BlockState st) {
        for (ItemStack s : picks) if (s.isCorrectToolForDrops(st)) return true;
        return false;
    }

    private static String hours(double ticks) {
        double h = ticks / 1000.0;
        if (h < 1.0) return "under an hour";
        return Math.round(h) + (Math.round(h) == 1 ? " hour" : " hours");
    }

    static String numberWord(int n) {
        String[] w = { "no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten" };
        return n >= 0 && n < w.length ? w[n] : Integer.toString(n);
    }

    /** When a trip of so many days, set out at this day time, turns for home: half a day at noon, a day at its hour
     *  of the afternoon, the last day of a longer one the same (at noon, for the half). */
    static long turnAt(long start, double days) {
        long day = start / 24000L;
        int whole = (int) Math.floor(days + 1e-6);
        boolean half = days - whole >= 0.49;
        if (whole == 0) return day * 24000L + 6000L;
        long last = day + whole - 1 + (half ? 1 : 0);
        return last * 24000L + (half ? 6000L : CaveDwellers.TURN);
    }

    // ------------------------------------------------------------------ the trip, kept with the town

    /** The trip under way, as the town keeps it (so a restart or the team's ground going to sleep loses nothing). */
    public record Trip(long start, long turnAt, double days, UUID leader, List<UUID> members, @Nullable BlockPos caveKey,
                       @Nullable BlockPos cave, int nights, boolean searched, String words) {
        String encode() {
            StringBuilder m = new StringBuilder();
            for (UUID u : members) m.append(m.length() == 0 ? "" : ",").append(u);
            return start + "|" + turnAt + "|" + days + "|" + leader + "|" + m + "|" + pos(caveKey) + "|" + pos(cave) + "|" + nights + "|"
                + (searched ? 1 : 0) + "|" + words.replace('|', '/').replace('\n', ' ');
        }

        private static String pos(@Nullable BlockPos p) {
            return p == null ? "-" : p.getX() + "," + p.getY() + "," + p.getZ();
        }

        @Nullable
        private static BlockPos pos(String s) {
            if (s.equals("-")) return null;
            String[] p = s.split(",");
            return p.length < 3 ? null : new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }

        @Nullable
        static Trip decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 10) return null;
            try {
                List<UUID> members = new ArrayList<>();
                for (String u : p[4].split(",")) if (!u.isEmpty()) members.add(UUID.fromString(u));
                return new Trip(Long.parseLong(p[0]), Long.parseLong(p[1]), Double.parseDouble(p[2]), UUID.fromString(p[3]), members, pos(p[5]),
                    pos(p[6]), Integer.parseInt(p[7]), "1".equals(p[8]), p[9]);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** "day 2 of 3" at this day time. */
        public String dayOf(long now) {
            long n = Math.max(1, (now / 24000L) - (start / 24000L) + 1);
            int of = Math.max(1, (int) Math.ceil(days));
            return "day " + n + " of " + of;
        }
    }

    /** The town's trip under way, or null. */
    @Nullable
    public static Trip trip(@Nullable UUID village) {
        if (village == null) return null;
        String s = Ledger.note(village, "caves.trip");
        return s == null || s.isEmpty() ? null : Trip.decode(s);
    }

    static void keep(CaveDwellers.Party p) {
        if (p.planWords.isEmpty() || p.reported) return;          // a lone walk home (comeHome, separate) is no trip of the town's
        Ledger.note(p.village, "caves.trip", new Trip(p.startTime, p.turnAt, p.days, p.leader, new ArrayList<>(p.members), p.caveKey, p.cave,
            p.nights, p.searched, p.planWords).encode());
    }

    static void end(UUID village) {
        Ledger.note(village, "caves.trip", "");
    }

    /** Is this folk one of a team on a trip that is a day past its plan, and not home? */
    public static boolean overdue(VillageFolkEntity f) {
        Trip t = trip(f.ownerId());
        return t != null && t.members().contains(f.getUUID()) && f.level().getDayTime() > t.turnAt() + 24000L;
    }

    /** How many of the town's team are away on the trip and not in the world just now (their ground asleep). */
    static int away(UUID village, List<VillageFolkEntity> here) {
        Trip t = trip(village);
        if (t == null) return 0;
        Set<UUID> seen = new java.util.HashSet<>();
        for (VillageFolkEntity f : here) seen.add(f.getUUID());
        int n = 0;
        for (UUID u : t.members()) if (!seen.contains(u)) n++;
        return n;
    }

    /** The parties taken up again after a restart, by town. */
    private static final Map<UUID, CaveDwellers.Party> RESUMED = new ConcurrentHashMap<>();

    /**
     * A member of a trip under way, back in the world with nothing in hand (a restart, its ground woken again): it
     * takes the trip up where it is, with whoever of the team is back too. False if there is no trip for it.
     */
    static boolean resume(ServerLevel level, VillageFolkEntity f, Villages.Village v) {
        Trip t = trip(v.id());
        if (t == null || !t.members().contains(f.getUUID())) return false;
        long now = level.getDayTime();
        if (now > t.turnAt() + 2 * 24000L) return false;                   // long over: it comes home on its own
        CaveDwellers.Party p = RESUMED.get(v.id());
        if (p == null || p.startTime != t.start() || p.reported) {
            p = CaveDwellers.resumedParty(level, v, t);
            RESUMED.put(v.id(), p);
        }
        CaveDwellers.rejoin(level, f, v, p);
        CaveDwellers.LOG.info("[MCA-CAVES] {} took up the trip again ({}) at {}", f.displayNameCap(), t.dayOf(now), f.blockPosition().toShortString());
        return true;
    }

    /**
     * A day past the plan and the team not home: the town sends a search party after its leader (or whoever of it is
     * in the world), once. Returns whether one went out.
     */
    public static boolean checkOverdue(ServerLevel level, Villages.Village v) {
        Trip t = trip(v.id());
        if (t == null) return false;
        if (level.getDayTime() > t.turnAt() + 5 * 24000L) {
            // Four days past it, and the search long given up: the trip is closed, and the town may take up a team again.
            end(v.id());
            Villages.tell(v.id(), level.getDayTime() / 24000L, "the town gave up waiting for the cave team, gone since day " + (t.start() / 24000L + 1));
            return false;
        }
        if (t.searched() || level.getDayTime() <= t.turnAt() + 24000L) return false;
        VillageFolkEntity lost = null;
        if (level.getEntity(t.leader()) instanceof VillageFolkEntity l && l.isAlive()) lost = l;
        for (UUID u : t.members()) if (lost == null && level.getEntity(u) instanceof VillageFolkEntity m && m.isAlive()) lost = m;
        long day = level.getDayTime() / 24000L;
        Ledger.note(v.id(), "caves.trip", new Trip(t.start(), t.turnAt(), t.days(), t.leader(), t.members(), t.caveKey(), t.cave(), t.nights(),
            true, t.words()).encode());
        CaveDwellers.Party p = lost == null ? null : CaveDwellers.partyOf(lost);
        if (p != null) p.searched = true;
        boolean went = lost != null && SearchParties.start(level, v, lost, level.getGameTime());
        Villages.tell(v.id(), day, "the cave team is a day overdue from " + t.words().replaceFirst("^[^:]* to ", "").replaceFirst("[:.].*$", "")
            + (went ? ": a search party went out after them" : ": nobody could be spared to look for them yet"));
        CaveDwellers.LOG.info("[MCA-CAVES] the team of {} is overdue ({} past the plan): search party {}", Villages.name(v.id()),
            level.getDayTime() - t.turnAt(), went);
        return went;
    }

    // ------------------------------------------------------------------ the extra half day

    /**
     * At the hour the plan turns the team for home: does it stay on? When the work is going well (the cave still has
     * veins to take near it) and the supplies hold (a day's meals and torches each, or the coal to make them), it
     * stays another half day, twice at most, never past four days.
     */
    static boolean stayOn(ServerLevel level, VillageFolkEntity lead, CaveDwellers.Party p) {
        // A half day's trip (a vein to finish, a cave close by) is home at noon, whatever: only a day's or longer stays on.
        if (p.days < 1.0 || p.extended >= 2 || p.days + 0.5 * (p.extended + 1) > LONGEST_DAYS || p.phase != CaveDwellers.Party.Phase.IN) return false;
        if (CaveDwellers.nextVein(level, lead, p) == null) return false;
        if (p.mined < 4 * Math.max(1, p.days)) return false;                    // only when the work has gone well
        for (VillageFolkEntity m : CaveDwellers.members(level, p)) {
            if (m.countMatching(CaveDwellers::food) < MEALS + 1) return false;
            if (m.countMatching(s -> s.is(Items.TORCH)) + CaveCraft.torchesToMake(m) < TORCHES_A_DAY / 2) return false;
            if (m.getHealth() < m.getMaxHealth() * 0.7F) return false;
        }
        p.extended++;
        p.turnAt += 12000L;
        keep(p);
        FolkTalk.speak(lead, "The work's going well, and we've food and torches yet. Another half day, everybody.");
        CaveDwellers.LOG.info("[MCA-CAVES] the team of {} stays on another half day ({} mined)", Villages.name(p.village), p.mined);
        return true;
    }

    // ------------------------------------------------------------------ nights under the rock

    /** Is it time to make camp: dusk on a trip with more of it to come? */
    static boolean nightfall(ServerLevel level, CaveDwellers.Party p) {
        long now = level.getDayTime(), t = now % 24000L;
        return (t >= DUSK || t < DAWN) && p.turnAt > now + 3000L;
    }

    /** The ring round a camp (two out from its middle, at the feet and the head): the openings to wall up. */
    static List<BlockPos> ring(BlockPos c) {
        List<BlockPos> out = new ArrayList<>();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue;
                    out.add(c.offset(dx, dy, dz));
                }
            }
        }
        return out;
    }

    private static boolean open(ServerLevel level, BlockPos q) {
        BlockState s = level.getBlockState(q);
        return (s.isAir() || s.canBeReplaced()) && !s.is(Blocks.TORCH) && !s.is(Blocks.WALL_TORCH);
    }

    /** How many openings a camp here would have to wall up. */
    static int openings(ServerLevel level, BlockPos c) {
        int n = 0;
        for (BlockPos q : ring(c)) if (open(level, q)) n++;
        return n;
    }

    /** A nook for the night near the leader: somewhere to stand under the rock, dry, away from lava, with the fewest
     *  openings to wall up. */
    @Nullable
    static BlockPos nook(ServerLevel level, VillageFolkEntity lead) {
        BlockPos feet = lead.blockPosition();
        BlockPos best = null;
        int bestOpen = Integer.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos q = feet.offset(dx, dy, dz);
                    if (!CaveDwellers.standable(level, q) || CaveDwellers.lavaNear(level, q, 3)) continue;
                    int n = openings(level, q) * 4 + (int) Math.sqrt(q.distSqr(feet));
                    if (n < bestOpen) { bestOpen = n; best = q; }
                }
            }
        }
        return best;
    }

    /** The leader pitches camp: the nook, said aloud, kept with the town. */
    static void pitch(ServerLevel level, VillageFolkEntity lead, CaveDwellers.Party p) {
        BlockPos c = nook(level, lead);
        if (c == null) c = lead.blockPosition();
        p.campFrom = p.phase == CaveDwellers.Party.Phase.CAMP ? p.campFrom : p.phase;
        p.phase = CaveDwellers.Party.Phase.CAMP;
        p.camp = c.immutable();
        p.campStart = level.getDayTime();
        p.campTick = level.getGameTime();
        p.campWalls.clear();
        p.nights++;
        p.campNoCobble = false;
        for (VillageFolkEntity m : CaveDwellers.members(level, p)) {
            if (m.expedition() != null && m.expedition().delve() != null) m.expedition().delve().ateCamp = false;
            m.getNavigation().stop();
        }
        keep(p);
        FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), "Night's coming on. We camp here: wall it in, a torch up, and we take turns on watch.",
            "That's the day. Camp, everybody — in here, out of the way."));
        CaveDwellers.LOG.info("[MCA-CAVES] the team of {} camps for the night at {} ({} openings, night {})", Villages.name(p.village),
            c.toShortString(), openings(level, c), p.nights);
    }

    /**
     * A member's night in camp: to the camp, the leader walling it up a block at a time and setting a torch inside;
     * supper; then asleep, all but the one on watch (a turn each, two hours a turn). At first light the leader breaks
     * camp. True while camped.
     */
    static boolean camp(ServerLevel level, VillageFolkEntity f, CaveDwellers.Delve d, CaveDwellers.Party p, boolean leading) {
        long now = level.getDayTime(), t = now % 24000L;
        if (p.camp == null) {
            if (!leading) return false;
            pitch(level, f, p);
        }
        BlockPos c = p.camp;
        if (t >= DAWN && t < DUSK && now - p.campStart > 4000L) {
            if (leading) breakCamp(level, f, p);
            return true;
        }
        double dist = f.position().distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
        // One of the others that could not get in for all the waiting (CAMP_WAIT) settles down where it is.
        boolean settle = !leading && level.getGameTime() - p.campTick > CAMP_WAIT;
        if (dist > 1.5 * 1.5 && !settle) {
            if (f.getNavigation().isDone() || f.tickCount - d.followTick > 40) {
                // The others to the camp's middle or a step to its side, so all fit in.
                List<VillageFolkEntity> team = CaveDwellers.members(level, p);
                int i = Math.max(0, team.indexOf(f));
                BlockPos spot = i == 0 ? c : c.offset(i % 2 == 1 ? 1 : -1, 0, i >= 2 ? 1 : 0);
                if (!CaveDwellers.standable(level, spot)) spot = c;
                f.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0D);
                d.followTick = f.tickCount;
            }
            f.hobbyNow = "making for the camp";
            return true;
        }
        f.getNavigation().stop();
        if (leading) wallUp(level, f, p);
        if (!d.ateCamp) {
            d.ateCamp = true;
            if (f.eatFromPack()) p.meals++;
        }
        List<VillageFolkEntity> team = CaveDwellers.members(level, p);
        int n = Math.max(1, team.size());
        int watch = (int) ((now / 2000L) % n);
        boolean mine = team.indexOf(f) == watch;
        f.setShiftKeyDown(!mine);
        if (mine) {
            f.hobbyNow = "keeping watch over the camp";
            if (f.tickCount % 60 == 0) {
                double a = f.getRandom().nextDouble() * Math.PI * 2;
                f.getLookControl().setLookAt(f.getX() + Math.cos(a) * 6, f.getEyeY(), f.getZ() + Math.sin(a) * 6);
            }
        } else {
            f.hobbyNow = "asleep in the camp, under the rock";
        }
        return true;
    }

    /** How long the leader waits for the team to come into camp before the walls go up without one (game ticks). */
    static final long CAMP_WAIT = 1200;

    /** A block of the camp's wall at a time, out of the team's cobblestone; a torch inside when it is dark. */
    static void wallUp(ServerLevel level, VillageFolkEntity lead, CaveDwellers.Party p) {
        BlockPos c = p.camp;
        if (c == null) return;
        // Everybody in first (a minute at most), so nobody is walled out; one shut out by a wall already up (it came in
        // late) is let in through it. The wait was by the day's clock, four hundred of it: a test's skip of the hours
        // (or a night's sleep in town) ran it out at once, the walls went up round the leader alone, and the other stood
        // outside them, "making for the camp", all night.
        if (level.getGameTime() - p.campTick < CAMP_WAIT) {
            for (VillageFolkEntity m : CaveDwellers.members(level, p)) {
                if (m.position().distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5) <= 2.2 * 2.2) continue;
                letIn(level, lead, p, m);
                return;
            }
        }
        if (level.getBrightness(LightLayer.BLOCK, c) < 8 && !p.campLit) {
            p.campLit = CaveDwellers.placeTorch(level, lead, p, c);
        }
        if (p.campNoCobble) return;
        for (BlockPos q : ring(c)) {
            if (!open(level, q) || !level.getFluidState(q).isEmpty() && level.getFluidState(q).isSource()) continue;
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(q)).isEmpty()) continue;
            if (!takeCobble(level, p)) {
                p.campNoCobble = true;
                FolkTalk.speak(lead, "Out of cobble. We'll keep a sharp watch instead.");
                return;
            }
            level.setBlockAndUpdate(q, Blocks.COBBLESTONE.defaultBlockState());
            lead.placeSound(q);
            lead.swing(InteractionHand.MAIN_HAND);
            p.campWalls.add(q.immutable());
            return;                                                     // a block at a time
        }
    }

    /**
     * One of the team outside the camp's walls, near them, with no way in left (every column of the ring shut, by the
     * walls or the rock): the column of the wall nearest it taken down, its cobble back in the leader's pack. It goes
     * up again once everybody is in.
     */
    static void letIn(ServerLevel level, VillageFolkEntity lead, CaveDwellers.Party p, VillageFolkEntity m) {
        BlockPos c = p.camp;
        if (c == null || p.campWalls.isEmpty() || m.blockPosition().distSqr(c) > 8 * 8) return;
        for (BlockPos q : ring(c)) {
            if (q.getY() == c.getY() && open(level, q) && open(level, q.above())) return;     // a way in still open
        }
        BlockPos nearest = null;
        for (BlockPos q : p.campWalls) {
            if (!level.getBlockState(q).is(Blocks.COBBLESTONE)) continue;
            if (nearest == null || q.distSqr(m.blockPosition()) < nearest.distSqr(m.blockPosition())) nearest = q;
        }
        if (nearest == null) return;
        int back = 0;
        for (BlockPos q : List.of(nearest.atY(c.getY()), nearest.atY(c.getY() + 1))) {
            if (!p.campWalls.contains(q) || !level.getBlockState(q).is(Blocks.COBBLESTONE)) continue;
            level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
            p.campWalls.remove(q);
            back++;
        }
        if (back == 0) return;
        ItemStack left = lead.insertItem(new ItemStack(Items.COBBLESTONE, back));
        if (!left.isEmpty()) lead.spawnAtLocation(left);
        lead.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(lead, "Hold on, " + m.displayNameCap() + " — I'll let you in.");
    }

    /** A cobblestone out of any of the team's packs. */
    static boolean takeCobble(ServerLevel level, CaveDwellers.Party p) {
        for (VillageFolkEntity m : CaveDwellers.members(level, p)) {
            if (m.removeMatching(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE), 1) == 1) return true;
        }
        return false;
    }

    /** First light: breakfast, the walls down (their cobble back in the pack), and on. */
    static void breakCamp(ServerLevel level, VillageFolkEntity lead, CaveDwellers.Party p) {
        int back = 0;
        for (BlockPos q : p.campWalls) {
            if (!level.getBlockState(q).is(Blocks.COBBLESTONE)) continue;
            level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
            back++;
        }
        if (back > 0) {
            ItemStack left = lead.insertItem(new ItemStack(Items.COBBLESTONE, back));
            if (!left.isEmpty()) lead.spawnAtLocation(left);
        }
        for (VillageFolkEntity m : CaveDwellers.members(level, p)) {
            m.setShiftKeyDown(false);
            if (m.eatFromPack()) p.meals++;
        }
        p.campWalls.clear();
        p.camp = null;
        p.campLit = false;
        p.phase = p.campFrom == null || p.campFrom == CaveDwellers.Party.Phase.CAMP ? CaveDwellers.Party.Phase.IN : p.campFrom;
        p.campFrom = null;
        keep(p);
        FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), "Morning. Walls down, and on we go.", "Up, everybody. There's work waiting."));
        CaveDwellers.LOG.info("[MCA-CAVES] the team of {} breaks camp ({} wall blocks back), {}", Villages.name(p.village), back,
            trip(p.village) == null ? "" : trip(p.village).dayOf(level.getDayTime()));
    }
}
