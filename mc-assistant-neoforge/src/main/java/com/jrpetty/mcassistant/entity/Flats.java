package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks of flats. A town in the Iron Age that is short of homes, with its young folk and its
 * hard-up households waiting for a roof and little ground left near its square, builds up instead
 * of out: three storeys of small flats on one house's lot, two to a landing, off a stair that winds
 * up the back of the hall (village blueprint {@code flats}). The builders raise it like anything
 * else, out of the stores, a load and a storey at a time, and it is built to look like a town house:
 * a slate plinth, stone walls with brick quoins at the corners, windows in three bays with window
 * boxes under the ground floor's, a balcony on brackets across each upper flat's front, the front
 * door under a fanlight and a little pediment with a lantern hung at either end of it, a bracketed
 * cornice, a pitched slate roof with a dormer, and a brick chimney stack out of each stone gable.
 *
 * <p>Every flat is a home on the village's books (Homes), its own: a room with a bed (two in a
 * couple's flat), a chest, a table and a lantern, and its number on the landing ("Flat 2B"). The
 * village lets them, never sells them, at half a house's rent: a house's day's rent every other
 * day. Who gets one first:
 * <ul>
 * <li>a grown folk on its own: the young one who has come of age and left its parents' house;</li>
 * <li>a couple starting out, with no children yet;</li>
 * <li>a household that could not afford a house's rent (nobody earning, or not a week's rent in
 *     hand over what it lives on).</li>
 * </ul>
 * A family that can afford a house is offered a house first, and a flat only when there is none.
 * A household in a flat that wants a house of its own saves toward one, as any tenant does (the
 * price of a house, not of the flat); once it has the price and a house stands empty it moves out
 * and buys it, and the flat is let to the next. A family with children that can afford a house's
 * rent moves out to an empty house as its tenant. Nobody is put out: the rent goes on the slate,
 * as a house's does.
 *
 * <p>Once a block stands, the town's hands see to it a piece at a time out of the stores (TownJobs):
 * the beds it went up without, the ground floor's doors, the flat numbers on the landings, and
 * iron railings round its balconies, the smith's work. In the Diamond Age a block whose flats are
 * all let, while folk still wait, gets a fourth storey (blueprint {@code flats4}): two more flats.
 * Its roof, its plinth and its trimmings are slated with the rest of the town's (Ages): built in
 * whatever wood the stores have, they are slate once the Iron Age's make-over comes round to it.
 */
public final class Flats {

    private Flats() {}

    /** The block's drawing and its name in the town's register. */
    public static final String BLOCK = "flats";
    /** The block with its fourth storey (the Diamond Age). */
    public static final String TALLER = "flats4";
    /** A flat's name on the homes' books. */
    public static final String FLAT = "flat";

    /** Layers to a storey: its floor and three of room. */
    static final int STOREY = 4;
    /** The flats' rooms run from dx -4 to dx 1 across the drawing; the hall is dx 3 and 4. */
    private static final int ROOM_LEFT = -4, ROOM_RIGHT = 1;
    /** Each flat's door, in the wall between it and the stair hall (dx 2). */
    private static final int HALL_WALL = 2, FRONT_DOOR = -2, BACK_DOOR = 1;
    /** The row in front of the front wall, where the balconies stand out from it. */
    private static final int BALCONY = -5;
    /** How far out from the heart "near the square" is: TownPlan's first ring of homes, sixteen lots. */
    static final int NEAR = TownPlan.RING + TownPlan.PERIOD;
    /** Home lots free near the square at or under which the ground counts as running short. */
    static final int SCARCE = 3;
    /** Households waiting at which the town builds up whatever ground it has. */
    static final int CROWD = 3;
    /** What the town calls its blocks, street by street: Elm Row Flats, then Elm Row Buildings... */
    private static final String[] NAMES = { "Flats", "Buildings", "Court", "Mansions", "House" };

    private static final String OPENED = "flats/opened/";
    private static final String TALL = "flats/tall/";
    private static final String RAISING = "flats/raising/";
    private static final String STRIPPED = "flats/stripped/";
    private static final String LOOK = "flats/look/";

    /** One flat: its block, its storey (0, the ground floor), front or back, its anchor on the homes' books, and its number. */
    record Spot(Ledger.Building block, int storey, boolean back, BlockPos anchor, String number) {}

    /** Why the village would build a block of flats now (the builders' list), by village; absent if it would not. */
    private static final Map<UUID, String> WANT = new ConcurrentHashMap<>();
    /** Each block's drawing laid out (by drawing, anchor and way round), worked out once. */
    private static final Map<String, List<BuildGoal.Placement>> PLANS = new ConcurrentHashMap<>();
    /** When each block was last found with all its beds in (by anchor). */
    private static final Map<Long, Long> FURNISHED = new ConcurrentHashMap<>();
    /** The beds in each drawing. */
    private static final Map<String, Integer> BEDS = new ConcurrentHashMap<>();
    /** How many times each layer of a rising storey has been laid (anchor:y). */
    private static final Map<String, Integer> TRIED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WANT.clear();
        PLANS.clear();
        FURNISHED.clear();
        TRIED.clear();
    }

    // ------------------------------------------------------------------ the block and its flats

    static boolean isFlat(Homes.Home h) {
        return FLAT.equals(h.structure);
    }

    /** The village's blocks of flats, in the order they went up. */
    static List<Ledger.Building> blocks(UUID village) {
        List<Ledger.Building> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) if (BLOCK.equals(b.structure())) out.add(b);
        return out;
    }

    /** Has this block its fourth storey? */
    static boolean tall(UUID village, Ledger.Building b) {
        return "1".equals(Ledger.note(village, TALL + b.anchor().asLong()));
    }

    /** Is a fourth storey going up on it now (begun, its roof coming off)? */
    static boolean raising(UUID village, Ledger.Building b) {
        return "1".equals(Ledger.note(village, RAISING + b.anchor().asLong()));
    }

    static int storeys(UUID village, Ledger.Building b) {
        return tall(village, b) ? 4 : 3;
    }

    /** The drawing standing there now. */
    static String drawing(UUID village, Ledger.Building b) {
        return tall(village, b) ? TALLER : BLOCK;
    }

    /** A cell of the block's drawing, in the world: across (right is +), up, and toward the back. */
    static BlockPos cell(Ledger.Building b, int dx, int h, int dz) {
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz).above(h);
    }

    /** A spot in the world, in the block's drawing: {across, up, toward the back}. */
    static int[] local(Ledger.Building b, BlockPos p) {
        int x = p.getX() - b.anchor().getX(), z = p.getZ() - b.anchor().getZ();
        Direction f = b.facing(), r = f.getClockWise();
        return new int[]{ x * r.getStepX() + z * r.getStepZ(), p.getY() - b.anchor().getY(), x * f.getStepX() + z * f.getStepZ() };
    }

    /** The block's drawing laid out where it stands. */
    static List<BuildGoal.Placement> plan(String drawing, Ledger.Building b) {
        if (PLANS.size() > 64) PLANS.clear();
        return PLANS.computeIfAbsent(drawing + "|" + b.anchor().asLong() + "|" + b.facing(),
            k -> List.copyOf(BuildGoal.plan(drawing, b.anchor(), b.facing(), 13)));
    }

    /** Every flat in the block, ground floor first, the front one (A) before the back (B). */
    static List<Spot> spots(UUID village, Ledger.Building b) {
        List<Spot> out = new ArrayList<>();
        int n = storeys(village, b);
        for (int s = 0; s < n; s++) {
            for (boolean back : new boolean[]{ false, true }) {
                out.add(new Spot(b, s, back, cell(b, -1, STOREY * s, back ? 2 : -2), (s + 1) + (back ? "B" : "A")));
            }
        }
        return out;
    }

    /** The flat whose anchor this is, or null. */
    @Nullable
    static Spot spotAt(UUID village, BlockPos anchor) {
        for (Ledger.Building b : blocks(village)) {
            if (Math.abs(anchor.getX() - b.anchor().getX()) > 6 || Math.abs(anchor.getZ() - b.anchor().getZ()) > 6) continue;
            for (Spot s : spots(village, b)) if (s.anchor().equals(anchor)) return s;
        }
        return null;
    }

    /** The register's entry for a flat (its block's way round, at the flat's own anchor), or null if it is no flat. */
    @Nullable
    static Ledger.Building building(UUID village, long anchor) {
        Spot s = spotAt(village, BlockPos.of(anchor));
        return s == null ? null : new Ledger.Building(FLAT, s.anchor(), s.block().facing());
    }

    /** As above, for the homes' books' key (the anchor written out). */
    @Nullable
    static Ledger.Building building(UUID village, String key) {
        try {
            return building(village, Long.parseLong(key));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Is this spot in the flat: its room, from the floor to the ceiling, or its doorway? */
    static boolean inside(Spot s, BlockPos p) {
        int[] d = local(s.block(), p);
        int base = STOREY * s.storey();
        if (d[1] < base - 1 || d[1] > base + 2) return false;
        if (d[0] == HALL_WALL && d[2] == (s.back() ? BACK_DOOR : FRONT_DOOR)) return true;
        if (d[0] < ROOM_LEFT || d[0] > ROOM_RIGHT) return false;
        return s.back() ? d[2] >= 1 && d[2] <= 3 : d[2] >= -3 && d[2] <= -1;
    }

    /** Homes.homeAt: is this spot in this flat? */
    static boolean inside(UUID village, Homes.Home h, BlockPos p) {
        // Nowhere near it (asked of every chest and bed in the village, so asked cheaply first).
        int dy = p.getY() - h.anchor.getY();
        if (Math.abs(p.getX() - h.anchor.getX()) > 4 || Math.abs(p.getZ() - h.anchor.getZ()) > 4 || dy < -1 || dy > 2) return false;
        Spot s = spotAt(village, h.anchor);
        return s != null && inside(s, p);
    }

    /** Every flat of the village's blocks is on the homes' books, empty until somebody takes it (Homes.enrol). */
    static void enrol(UUID village, Map<Long, Homes.Home> homes) {
        for (Ledger.Building b : blocks(village)) {
            for (Spot s : spots(village, b)) {
                if (homes.containsKey(s.anchor().asLong())) continue;
                Homes.Home h = new Homes.Home(s.anchor(), FLAT);
                homes.put(s.anchor().asLong(), h);
                Homes.save(village, h);
            }
        }
    }

    /** The heads of the beds made up in the flat: its own (from the drawing) and any bought for a child. */
    static List<BlockPos> bedsIn(ServerLevel level, UUID village, Homes.Home h) {
        List<BlockPos> out = new ArrayList<>();
        Spot s = spotAt(village, h.anchor);
        if (s == null || !level.isLoaded(h.anchor)) return out;
        for (BuildGoal.Placement p : plan(drawing(village, s.block()), s.block())) {
            if (p.part() != BuildGoal.Part.BED || !inside(s, p.pos())) continue;
            BlockPos head = p.pos().relative(lie(p, s.block().facing()));
            if (Homes.isBedHead(level, head) && !out.contains(head)) out.add(head);
        }
        String extra = Ledger.note(village, "homebeds/" + h.anchor.asLong());
        if (extra != null && !extra.isEmpty()) {
            for (String e : extra.split(",")) {
                try {
                    BlockPos head = BlockPos.of(Long.parseLong(e));
                    if (Homes.isBedHead(level, head) && !out.contains(head)) out.add(head);
                } catch (NumberFormatException ignored) { }
            }
        }
        out.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return out;
    }

    private static Direction lie(BuildGoal.Placement p, Direction facing) {
        return p.way() == Blueprints.Way.UP ? facing : Blueprints.world(p.way(), facing);
    }

    /** The flat's chest, as it stands now. */
    @Nullable
    static BlockPos chestOf(ServerLevel level, UUID village, Homes.Home h) {
        Spot s = spotAt(village, h.anchor);
        if (s == null || !level.isLoaded(h.anchor)) return null;
        for (BuildGoal.Placement p : plan(drawing(village, s.block()), s.block())) {
            if (p.part() == BuildGoal.Part.CHEST && inside(s, p.pos())
                    && level.getBlockState(p.pos()).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) return p.pos();
        }
        return null;
    }

    /** Where a child's bed goes in a flat: two clear cells on its floor, as far from the grown-ups' as the room allows. */
    @Nullable
    static BlockPos bedSpot(ServerLevel level, UUID village, Homes.Home h, List<BlockPos> beds) {
        Spot s = spotAt(village, h.anchor);
        if (s == null) return null;
        double cx = h.anchor.getX(), cz = h.anchor.getZ();
        if (!beds.isEmpty()) {
            cx = 0;
            cz = 0;
            for (BlockPos b : beds) { cx += b.getX(); cz += b.getZ(); }
            cx /= beds.size();
            cz /= beds.size();
        }
        BlockPos best = null;
        double far = -1;
        int base = STOREY * s.storey();
        for (int dx = ROOM_LEFT; dx <= ROOM_RIGHT; dx++) {
            for (int dz = s.back() ? 1 : -3; dz <= (s.back() ? 3 : -1); dz++) {
                BlockPos p = cell(s.block(), dx, base, dz);
                Direction lie = Homes.spotLie(level, p);
                BlockPos head = p.relative(lie);
                if (!inside(s, head) || !Homes.clear(level, p) || !Homes.clear(level, head)) continue;
                double d = (p.getX() - cx) * (p.getX() - cx) + (p.getZ() - cz) * (p.getZ() - cz);
                if (d > far) { far = d; best = p; }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ names

    /** What the town calls the block: the street it stands on, "Elm Row Flats", and the next on that street "Elm Row Buildings". */
    public static String name(UUID village, Ledger.Building b) {
        Villages.Village v = Villages.get(village);
        String street = street(village, v, b);
        int i = 0;
        for (Ledger.Building o : blocks(village)) {
            if (o.anchor().equals(b.anchor())) break;
            if (street.equals(street(village, v, o))) i++;
        }
        return street + " " + NAMES[i % NAMES.length];
    }

    private static String street(UUID village, @Nullable Villages.Village v, Ledger.Building b) {
        String[] a = v == null ? null : TownLife.address(village, v.centre(), b);
        return a != null ? a[1] : Villages.name(village);
    }

    /** The name for the sign by its door (a sign holds fifteen letters a line): "Elm Row Flats", or "Flats". */
    public static String signName(UUID village, Ledger.Building b) {
        String n = name(village, b);
        return n.length() <= 15 ? n : n.substring(n.lastIndexOf(' ') + 1);
    }

    /** "flat 2B, Elm Row Flats". */
    static String address(UUID village, Homes.Home h) {
        Spot s = spotAt(village, h.anchor);
        return s == null ? "a flat at " + h.anchor.getX() + ", " + h.anchor.getZ() : "flat " + s.number() + ", " + name(village, s.block());
    }

    // ------------------------------------------------------------------ who flats are for

    /** A house as the village would let one now: its rent, and what a household has put by against it. */
    static Homes.Home house(UUID village, int saved) {
        Homes.Home probe = new Homes.Home(BlockPos.ZERO, "house");
        probe.rent = Homes.rent(village, probe);
        probe.saved = saved;
        return probe;
    }

    /** Could not afford a house's rent: nobody earning, or not a week of it in hand over the dozen coins a head it lives on. */
    static boolean hardUp(UUID village, List<VillageFolkEntity> household, int saved) {
        return !Homes.affords(household, house(village, saved));
    }

    /** What the flats are for: a grown folk on its own, a couple with no children yet, or a household that could not afford a house. */
    static boolean suited(UUID village, List<VillageFolkEntity> household) {
        List<VillageFolkEntity> grown = Homes.grown(household);
        if (grown.isEmpty()) return false;
        if (grown.size() == household.size() && grown.size() <= 2) return true;
        return hardUp(village, household, 0);
    }

    /** The empty flat that suits this household best: beds enough for its grown folk, the fewest spare, the lowest. */
    @Nullable
    static Homes.Home vacancy(ServerLevel level, UUID village, List<VillageFolkEntity> household) {
        int grown = Homes.grown(household).size();
        Homes.Home best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Homes.Home h : Homes.homes(village).values()) {
            if (!isFlat(h) || !h.vacant() || h.tenure == Homes.Tenure.PLAYER || !level.isLoaded(h.anchor)) continue;
            Spot s = spotAt(village, h.anchor);
            if (s == null) continue;
            int beds = bedsIn(level, village, h).size();
            if (beds < Math.max(1, grown)) continue;
            int score = (beds - grown) * 16 + s.storey() * 2 + (s.back() ? 1 : 0);
            if (score < bestScore) { bestScore = score; best = h; }
        }
        return best;
    }

    /**
     * Homes.tick, before any house is let: each household waiting that a flat is for (a single, a
     * couple starting out, the hard-up) takes an empty flat that fits it, and comes off the list.
     */
    static void letFirst(ServerLevel level, Villages.Village v, List<List<VillageFolkEntity>> waiting, long day) {
        waiting.removeIf(household -> suited(v.id(), household) && let(level, v, household, day));
    }

    /** An empty flat for this household, if one fits it: it moves in. */
    static boolean let(ServerLevel level, Villages.Village v, List<VillageFolkEntity> household, long day) {
        Homes.Home flat = vacancy(level, v.id(), household);
        if (flat == null) return false;
        settle(level, v, flat, household, day);
        return true;
    }

    /** A household takes a flat, as the village's tenant. */
    static void settle(ServerLevel level, Villages.Village v, Homes.Home flat, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        flat.tenure = Homes.Tenure.RENTED;
        flat.price = Homes.price(id, flat);
        flat.rent = Homes.rent(id, flat);
        flat.owed = 0;
        if (flat.saved > 0) Ledger.addCoins(id, flat.saved);      // savings left behind in an empty flat: nobody's now
        flat.saved = 0;
        boolean leaving = false;
        for (VillageFolkEntity f : household) {
            Homes.Home was = Homes.homeOf(id, f.getUUID());
            if (was != null && was != flat) {
                was.members.remove(f.getUUID());
                Homes.save(id, was);
                leaving = true;
            }
        }
        flat.since = day;
        Homes.moveIn(level, v, flat, household, day, true);
        String where = address(id, flat);
        String terms = Homes.rentFree(household) ? "rent-free, founders of the village, till they can afford it"
            : "at " + flat.rent + Homes.coins(flat.rent) + " every other day";
        boolean couple = household.size() == 2 && Homes.grown(household).size() == 2;
        boolean single = household.size() == 1;
        Villages.tell(id, day, Homes.names(household) + (leaving ? " moved out of the family home into " : " took ") + where + ", " + terms);
        for (VillageFolkEntity f : Homes.grown(household)) {
            f.persona().remember(day, (leaving ? "I moved out into " : single ? "I took " : "we took ") + where + " on day " + day, 6);
        }
        VillageFolkEntity first = Homes.grown(household).isEmpty() ? household.get(0) : Homes.grown(household).get(0);
        if (leaving) {
            FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "A flat of my own! Small, but it's mine.",
                "My own front door — up the stair, but mine.", "Off I go. I'll be round for supper, mind."));
        } else if (single) {
            FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "A flat of my own! Small, but it's mine.",
                "A room, a bed and a door that shuts. What more could I want?", "Half a house's rent — I can manage that."));
        } else if (couple) {
            FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "Our first place together. We'll save for a house.",
                "A flat to start us off. Cosy, isn't it?", "Two beds and a table — that's all we need for now."));
        } else {
            FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "A roof we can afford. That'll do us nicely.",
                "Half a house's rent — we can manage that.", "Somewhere warm and dry at last."));
        }
    }

    // ------------------------------------------------------------------ the rent, and saving for a house

    /** A flat's rent falls due every other day: half a house's. The first the day after a household moves in. */
    static boolean rentDay(Homes.Home h, long day) {
        return Math.floorMod(day - Math.max(0L, h.since), 2L) == 1L;
    }

    /**
     * A flat's payday (Homes.payday): the rent every other day (a house's day's rent: half what a
     * house costs) and anything owed, out of the purses and then what is put by; what can't be paid
     * goes on the slate, let off as a house's is. A household that wants a house of its own puts by
     * toward a house's price, as any tenant does; it never buys the flat. (Moving out to a house once
     * it has the price is Flats.tick's, when a house stands empty.)
     */
    static void payday(ServerLevel level, Villages.Village v, Homes.Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        h.rent = Homes.rent(id, h);
        h.price = Homes.price(id, h);
        boolean hard = Homes.earners(household) == 0;
        boolean free = Homes.rentFree(household);
        if (free && Homes.affords(household, h)) {
            Homes.startPaying(level, v, h, household, day);
            free = false;
        }
        boolean letOff = CityTree.rentFreeToday(id, Homes.baseRent(id, h), day);
        int due = (hard || free || letOff || !rentDay(h, day) ? 0 : h.rent) + h.owed;
        int paid = Homes.take(household, h, due);
        if (paid > 0) {
            Ledger.addCoins(id, paid);
            Economy.rent(id, paid);
        }
        h.owed = due - paid;
        if (h.owed > 0 && (hard || Homes.generous(id))) {
            h.owed = 0;
        } else if (h.owed > Homes.WRITE_OFF_DAYS * Math.max(1, h.rent)) {
            Villages.tell(id, day, "the village wrote off the " + h.owed + Homes.coins(h.owed) + " of rent " + Homes.names(household)
                + " owed on " + address(id, h));
            h.owed = 0;
        }
        Homes.Wish w = Homes.wish(id, h, household);
        if (!w.yes() && h.saved > 0 && w.score() <= 0) Homes.giveBack(household, h);
        if (w.yes() && h.owed == 0) {
            int was = h.saved;
            Homes.putBy(h, household, day);
            h.saved += CityTree.housingFund(id, h.saved - was, h.price - h.saved);
        }
        Homes.save(id, h);
    }

    /** "I rent it from the village at 1 coin every other day, half what a house costs; I'm saving for a house of my own: ..." */
    static String tenancy(UUID village, Homes.Home h) {
        List<VillageFolkEntity> household = Homes.loadedMembers(village, h);
        boolean many = household.size() > 1;
        String we = many ? "we" : "I", us = many ? "us" : "me", our = many ? "our" : "my";
        String rent = h.rent + Homes.coins(h.rent) + " every other day, half what a house costs";
        StringBuilder sb = new StringBuilder(we + " rent it from the village at " + rent);
        if (Homes.rentFree(household)) {
            sb = new StringBuilder(we + " live in it rent-free — " + (many ? "we're" : "I'm") + " one of the founders — till "
                + we + " can afford the " + rent);
        } else if (!household.isEmpty() && Homes.earners(household) == 0) {
            sb.append(", though the village lets ").append(us).append(" off while nobody here earns");
        } else if (h.owed > 0) {
            sb.append(", and ").append(we).append(" owe ").append(h.owed).append(Homes.coins(h.owed)).append(" of it");
        }
        Homes.Wish w = Homes.wish(village, h, household);
        int price = Homes.price(village, h);
        if (w.yes()) {
            sb.append("; ").append(many ? "we're" : "I'm").append(" saving for a house of ").append(our).append(" own: ")
                .append(h.saved).append(" of ").append(price).append(Homes.coins(price)).append(" put by (").append(w.mine()).append(")");
        } else {
            sb.append("; a flat suits ").append(us).append(" for now (").append(w.mine()).append(")");
        }
        return sb.toString();
    }

    /** The Homes page's note on a flat's rent. */
    static final String RENT_NOTE = "a flat: due every other day, half a house's rent";

    // ------------------------------------------------------------------ the households, every so often

    /**
     * Homes.tick, after the houses are let: whoever still waits takes a flat that fits it, if there
     * is one; households in flats that can have a house now move out to an empty one (and the builders
     * are told a house is wanted when there is none); and whether the town wants another block.
     * Then a piece of work on the blocks themselves.
     */
    static void tick(ServerLevel level, Villages.Village v, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        opened(id, day);
        int unhoused = 0;
        for (List<VillageFolkEntity> household : Homes.waiting(id, folk, day)) {
            if (!let(level, v, household, day)) unhoused++;
        }
        boolean wantsHouse = moveOut(level, v, day);
        if (wantsHouse || unhoused > 0 && !anEmptyHouse(id)) Villages.HOUSE_WANTED.put(id, true);
        weigh(level, v, folk, day);
        fit(level, v, unhoused > 0);
    }

    /** Is there an empty house of the village's to let (not a flat, not a manor, not the leader's hall)? */
    private static boolean anEmptyHouse(UUID village) {
        for (Homes.Home h : Homes.homes(village).values()) {
            if (h.vacant() && !isFlat(h) && !Homes.seat(h) && !"manor".equals(h.structure)) return true;
        }
        return false;
    }

    /**
     * Households in flats that can have a house: one that wants a house of its own and has put by its
     * price buys an empty one; a family with children that can afford a house's rent takes one as the
     * village's tenant. The flat is let to the next. True if some household is ready and no house stands empty.
     */
    static boolean moveOut(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        boolean wanting = false;
        for (Homes.Home h : new ArrayList<>(Homes.homes(id).values())) {
            if (!isFlat(h) || h.members.isEmpty() || h.tenure != Homes.Tenure.RENTED) continue;
            List<VillageFolkEntity> household = Homes.loadedMembers(id, h);
            if (household.isEmpty() || household.size() < h.members.size()) continue;
            Homes.Wish w = Homes.wish(id, h, household);
            int price = Homes.price(id, h);
            boolean buy = w.yes() && price > 0 && h.saved >= price;
            boolean family = Homes.grown(household).size() < household.size() && !hardUp(id, household, h.saved);
            // Two grown in a single's flat (two who married, one moving in with the other): a couple's flat,
            // if one stands empty; else a house, if they can afford one.
            boolean cramped = bedsIn(level, id, h).size() < Homes.grown(household).size();
            if (cramped && !buy) {
                Homes.Home bigger = vacancy(level, id, household);
                if (bigger != null) {
                    across(level, v, h, bigger, household, day);
                    continue;
                }
                family |= !hardUp(id, household, h.saved);
            }
            if (!buy && !family) continue;
            Homes.Home house = houseFor(level, id, household);
            if (house == null) {
                wanting = true;
                continue;
            }
            move(level, v, h, house, household, buy, day);
        }
        return wanting;
    }

    /** An empty house of the village's for this household: beds for its grown folk (room for all of it if there is one), the smallest. */
    @Nullable
    private static Homes.Home houseFor(ServerLevel level, UUID village, List<VillageFolkEntity> household) {
        int grown = Homes.grown(household).size(), all = household.size();
        Homes.Home best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Homes.Home h : Homes.homes(village).values()) {
            if (!h.vacant() || isFlat(h) || Homes.seat(h) || h.tenure == Homes.Tenure.PLAYER || !"house".equals(h.structure)) continue;
            if (Ledger.raising(village, h.anchor) || !level.isLoaded(h.anchor)) continue;
            int beds = Homes.bedsIn(level, village, h).size();
            if (beds < Math.max(1, grown)) continue;
            int score = (beds >= all ? 0 : 100) + beds;
            if (score < bestScore) { bestScore = score; best = h; }
        }
        return best;
    }

    /** From one flat to a bigger one: what the household put by and owes goes with it, and its things. */
    static void across(ServerLevel level, Villages.Village v, Homes.Home from, Homes.Home to, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        if (to.saved > 0) Ledger.addCoins(id, to.saved);          // savings left behind in an empty flat: nobody's now
        to.tenure = Homes.Tenure.RENTED;
        to.price = Homes.price(id, to);
        to.rent = Homes.rent(id, to);
        to.saved = from.saved;
        to.owed = from.owed;
        to.since = from.since;                                    // its rent days as they were
        from.saved = 0;
        from.owed = 0;
        Homes.moveIn(level, v, to, household, day, true);
        Homes.vacate(id, from, null);
        Homes.save(id, to);
        Villages.tell(id, day, Homes.names(household) + " moved from " + address(id, from) + " into " + address(id, to) + ", with a bed each");
        List<VillageFolkEntity> grown = Homes.grown(household);
        if (!grown.isEmpty()) FolkTalk.speak(grown.get(0), FolkTalk.pick(level.getRandom(), "A bed each at last — and the same stair.",
            "Across the landing to a bigger flat. Room to turn round!"));
    }

    /** Out of the flat into a house: bought with what the household put by, or let to it; its things carried over. */
    static void move(ServerLevel level, Villages.Village v, Homes.Home flat, Homes.Home house, List<VillageFolkEntity> household,
                     boolean buy, long day) {
        UUID id = v.id();
        String from = address(id, flat), to = Homes.address(id, v, house);
        int saved = flat.saved, owed = flat.owed;
        flat.saved = 0;
        flat.owed = 0;
        if (house.saved > 0) Ledger.addCoins(id, house.saved);    // savings left behind in an empty house: nobody's now
        house.tenure = Homes.Tenure.RENTED;
        house.price = Homes.price(id, house);
        house.rent = Homes.rent(id, house);
        house.saved = saved;                                      // what they put by goes with them
        house.owed = owed;
        house.since = day;
        Homes.moveIn(level, v, house, household, day, true);
        Homes.vacate(id, flat, null);
        String how;
        boolean bought = false;
        if (buy && house.price > 0 && house.saved >= house.price) {
            int price = house.price;
            house.saved -= price;
            Ledger.addCoins(id, price);
            Economy.houseSold(id, price);
            Homes.giveBack(household, house);                     // the change
            house.tenure = Homes.Tenure.OWNED;
            house.rent = 0;
            house.owed = 0;
            how = "bought " + to + " for " + price + Homes.coins(price) + " they had put by";
            bought = true;
        } else {
            how = "took " + to + " from the village at " + house.rent + Homes.coins(house.rent) + " a day";
        }
        Homes.save(id, house);
        Villages.tell(id, day, Homes.names(household) + " moved out of " + from + " and " + how + "; the flat is to let");
        for (VillageFolkEntity f : Homes.grown(household)) {
            f.persona().remember(day, "we left " + from + " and " + how.replace("they had", "we had"), 7);
        }
        List<VillageFolkEntity> grown = Homes.grown(household);
        VillageFolkEntity first = grown.isEmpty() ? household.get(0) : grown.get(0);
        FolkTalk.speak(first, bought
            ? FolkTalk.pick(level.getRandom(), "A house of our own — saved for every coin of it in that little flat!",
                "Goodbye, flat; hello, front garden.", "Paid for, outright. No more stairs!")
            : FolkTalk.pick(level.getRandom(), "A house with room for the little ones at last.",
                "The flat was snug, but the children need the space.", "A garden for the children — that's what we wanted."));
    }

    // ------------------------------------------------------------------ the builders' list

    /**
     * Should the village build a block of flats now? In the Iron Age and after, when households wait
     * for a home, some of them the flats' sort (a single, a couple starting out, the hard-up), and the
     * ground near the square is running short (or the queue is long), and the blocks it has are full:
     * one block for every thirty folk, the first at once.
     */
    static void weigh(ServerLevel level, Villages.Village v, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        String why = null;
        if (Villages.ageOf(id).ordinal() >= Villages.Age.IRON.ordinal()) {
            List<List<VillageFolkEntity>> waiting = Homes.waiting(id, folk, day);
            int suited = 0;
            for (List<VillageFolkEntity> household : waiting) if (suited(id, household)) suited++;
            int blocks = blocks(id).size();
            int cap = 1 + Villages.headcount(id) / 30;
            int empty = 0;
            for (Homes.Home h : Homes.homes(id).values()) if (isFlat(h) && h.vacant()) empty++;
            int near = freeLotsNear(id);
            if (blocks < cap && empty <= 1 && suited > 0 && (near <= SCARCE || waiting.size() >= CROWD)) {
                why = waiting.size() + (waiting.size() == 1 ? " household waits" : " households wait") + " for a home ("
                    + (suited == waiting.size() ? (suited == 1 ? "young or hard up" : "all young or hard up") : suited + " of them young or hard up")
                    + ") and " + (near == 0 ? "no lot stands" : near + (near == 1 ? " lot stands" : " lots stand")) + " free near the square";
            }
        }
        if (why == null) WANT.remove(id);
        else WANT.put(id, why);
    }

    /** Villages.projectsWanted: a block of flats, ahead of the next house, when the town wants one. */
    public static List<String> wanted(UUID village, List<String> projects) {
        if (!WANT.containsKey(village) || projects.contains(BLOCK)) return projects;
        if (Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal()) return projects;
        // Not on a stale word: a block gone up since the town last weighed it, with flats standing empty.
        if (blocks(village).size() >= 1 + Villages.headcount(village) / 30) return projects;
        int empty = 0;
        for (Homes.Home h : Homes.homes(village).values()) if (isFlat(h) && h.vacant()) empty++;
        if (empty > 1) return projects;
        int at = projects.indexOf("house");
        if (at >= 0) projects.add(at, BLOCK);
        else projects.add(BLOCK);
        return projects;
    }

    /** Villages.whyBuild: why a block of flats. */
    public static String why(UUID village) {
        String w = WANT.get(village);
        return "a block of flats, six homes on one lot near the square for the young and the hard-up"
            + (w == null ? "" : ": " + w);
    }

    /** Home lots near the square that nothing stands on, is going up on, or is kept off (the fields). */
    static int freeLotsNear(UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;
        List<Ledger.Building> all = Ledger.buildings(village);
        Map<String, Villages.Site> going = Villages.sitesOf(village);
        int n = 0;
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.kind() != TownPlan.Kind.LOT || !"home".equals(l.use()) || l.distance() > NEAR) continue;
            if (Villages.lotKeptOff(village, v.centre(), l) || Villages.builtOver(village, l.x(), l.z(), 0, 0)) continue;
            boolean stands = false;
            for (Ledger.Building b : all) if (onLot(v, l, b.anchor())) { stands = true; break; }
            for (Villages.Site s : going.values()) if (!stands && onLot(v, l, s.anchor())) stands = true;
            if (!stands) n++;
        }
        return n;
    }

    private static boolean onLot(Villages.Village v, TownPlan.Lot l, BlockPos at) {
        int dx = at.getX() - v.centre().getX() - l.x(), dz = at.getZ() - v.centre().getZ() - l.z();
        return Math.abs(dx) <= TownPlan.LOT / 2 && Math.abs(dz) <= TownPlan.LOT / 2;
    }

    /** The beds the village's blocks hold (Villages.bedsPlanned: nine a block, twelve with its fourth storey). */
    public static int bedsPlanned(UUID village) {
        int n = 0;
        for (Ledger.Building b : blocks(village)) n += bedsDrawn(drawing(village, b));
        return n;
    }

    private static int bedsDrawn(String drawing) {
        return BEDS.computeIfAbsent(drawing, d -> {
            int n = 0;
            for (Blueprints.Cell c : Blueprints.cells(d)) if (c.key().part() == BuildGoal.Part.BED) n++;
            return n;
        });
    }

    // ------------------------------------------------------------------ the chronicle, the books

    /** A block newly up goes into the chronicle: the town's first block of flats, then each after it. */
    private static void opened(UUID id, long day) {
        List<Ledger.Building> all = blocks(id);
        int before = 0;
        for (Ledger.Building b : all) {
            String key = OPENED + b.anchor().asLong();
            if (Ledger.note(id, key) != null) { before++; continue; }
            Ledger.note(id, key, Long.toString(day));
            int n = spots(id, b).size();
            String name = name(id, b);
            Villages.tell(id, day, before == 0
                ? "the town's first block of flats opened: " + name + ", " + n + " flats on one lot, let at half a house's rent to the young and the hard-up"
                : name + " opened: " + n + " more flats, let to the young and the hard-up");
            before++;
        }
    }

    /** The Buildings page (Annals): a block's name, its storeys, and its flats let and free. */
    static void annals(UUID village, Ledger.Building b, CompoundTag c) {
        if (!BLOCK.equals(b.structure())) return;
        Map<Long, Homes.Home> homes = Homes.homes(village);
        int n = 0, let = 0, living = 0;
        for (Spot s : spots(village, b)) {
            n++;
            Homes.Home h = homes.get(s.anchor().asLong());
            if (h != null && !h.members.isEmpty()) {
                let++;
                living += h.members.size();
            }
        }
        c.putString("title", name(village, b));
        c.putInt("storeys", storeys(village, b));
        if (raising(village, b)) c.putBoolean("raising", true);
        c.putInt("living", living);
        c.putString("tenure", let + " of " + n + " flats let, " + (n - let) + " free");
        c.putInt("flats", n);
        c.putInt("flats_let", let);
    }

    /** For the status line (Homes.line): "; flats: 4 of 6 let", or "" with none built. */
    static String line(UUID village) {
        int n = 0, let = 0;
        for (Homes.Home h : Homes.homes(village).values()) {
            if (!isFlat(h)) continue;
            n++;
            if (!h.members.isEmpty()) let++;
        }
        return n == 0 ? "" : "; flats: " + let + " of " + n + " let";
    }

    /** /village flats: the village's blocks, each flat, who lives there and on what terms. */
    public static List<String> list(ServerLevel level, BlockPos near) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.nearest(level, near, Villages.VILLAGE_RANGE);
        if (v == null) { out.add("There's no village here."); return out; }
        UUID id = v.id();
        Homes.enrol(id);
        List<Ledger.Building> all = blocks(id);
        String why = WANT.get(id);
        if (all.isEmpty()) {
            out.add(Villages.name(id) + " has no flats yet" + (why != null ? "; it means to build a block: " + why
                : Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal() ? " (blocks of flats come with the Iron Age)" : ""));
            return out;
        }
        Map<Long, Homes.Home> homes = Homes.homes(id);
        for (Ledger.Building b : all) {
            out.add(name(id, b) + " (" + storeys(id, b) + " storeys, " + b.anchor().getX() + ", " + b.anchor().getY() + ", "
                + b.anchor().getZ() + (raising(id, b) ? ", a storey going up" : "") + ")");
            for (Spot s : spots(id, b)) {
                Homes.Home h = homes.get(s.anchor().asLong());
                int beds = h == null ? 0 : bedsIn(level, id, h).size();
                StringBuilder sb = new StringBuilder("  flat ").append(s.number()).append(" (").append(beds).append(beds == 1 ? " bed" : " beds").append("): ");
                if (h == null || h.members.isEmpty()) sb.append("to let");
                else {
                    List<VillageFolkEntity> m = Homes.loadedMembers(id, h);
                    sb.append(m.isEmpty() ? h.members.size() + " folk" : Homes.names(m)).append(", ").append(h.rent).append(Homes.coins(h.rent))
                        .append(" every other day");
                    if (h.owed > 0) sb.append(", owes ").append(h.owed);
                    if (h.saved > 0) sb.append(", ").append(h.saved).append(" of ").append(Homes.price(id, h)).append(" put by for a house");
                }
                out.add(sb.toString());
            }
        }
        if (why != null) out.add("Another block wanted: " + why);
        return out;
    }

    // ------------------------------------------------------------------ the blocks' upkeep

    /**
     * A piece of work on the village's blocks, by a hand there (TownJobs) and out of the stores: a bed
     * where one is missing, the ground floor's doors, the flat numbers on the landings, the balconies'
     * railings; and in the Diamond Age, a fourth storey on a block whose flats are all let while folk
     * still wait.
     */
    static void fit(ServerLevel level, Villages.Village v, boolean folkWait) {
        UUID id = v.id();
        List<Ledger.Building> all = blocks(id);
        for (Ledger.Building b : all) {
            if (!Land.areaLoaded(level, b.anchor(), 9)) continue;
            if (raising(id, b)) {
                storey(level, v, b, 24, false);
                return;
            }
            if (furnish(level, v, b)) return;
            if (doors(level, v, b, false) > 0) return;
            numbers(level, v, b, false);
            if (railings(level, v, b, false) > 0) return;
        }
        if (Villages.ageOf(id).ordinal() < Villages.Age.DIAMOND.ordinal()) return;
        if (!folkWait && !WANT.containsKey(id)) return;
        Map<Long, Homes.Home> homes = Homes.homes(id);
        for (Ledger.Building b : all) {
            if (tall(id, b) || !Land.areaLoaded(level, b.anchor(), 9)) continue;
            boolean full = true;
            for (Spot s : spots(id, b)) {
                Homes.Home h = homes.get(s.anchor().asLong());
                if (h == null || h.members.isEmpty()) { full = false; break; }
            }
            if (!full) continue;
            storey(level, v, b, 24, false);
            return;
        }
    }

    /**
     * A bed into a flat that stands without one: from the stores, or made there and then of three wool
     * and three planks out of them (Grow does the houses'). One bed a turn; true if one went in.
     */
    static boolean furnish(ServerLevel level, Villages.Village v, Ledger.Building b) {
        UUID id = v.id();
        if (level.getGameTime() - FURNISHED.getOrDefault(b.anchor().asLong(), -100000L) < 6000L) return false;
        String plan = drawing(id, b);
        for (BuildGoal.Placement p : plan(plan, b)) {
            if (p.part() != BuildGoal.Part.BED) continue;
            BlockPos foot = p.pos();
            BlockPos head = foot.relative(lie(p, b.facing()));
            if (level.getBlockState(foot).is(BlockTags.BEDS)) continue;
            if (!level.getBlockState(foot).canBeReplaced() || !level.getBlockState(head).canBeReplaced()) continue;
            if (!level.getBlockState(foot.below()).isSolid() || !level.getBlockState(head.below()).isSolid()) continue;
            if (Market.stock(level, id, s -> s.is(ItemTags.BEDS)) == 0 && Market.stock(level, id, s -> s.is(ItemTags.WOOL)) < 3) return false;
            if (!TownJobs.atWork(level, v, "beds", foot, "making up a bed in " + name(id, b))) return false;
            BlockState bed = bedFromTheStores(level, v);
            if (bed == null) return false;
            final BlockState laid = bed;
            BuildGoal.stampOnly(level, plan, b.anchor(), b.facing(), 13, x -> laid, x -> x.pos().equals(foot));
            return true;
        }
        FURNISHED.put(b.anchor().asLong(), level.getGameTime());
        return false;
    }

    /** A bed out of the stores, or one made of their wool and planks (a log is four planks). */
    @Nullable
    private static BlockState bedFromTheStores(ServerLevel level, Villages.Village v) {
        ItemStack bed = Crafts.takeOne(level, v, s -> s.is(ItemTags.BEDS));
        if (!bed.isEmpty() && Block.byItem(bed.getItem()) instanceof net.minecraft.world.level.block.BedBlock bb) return bb.defaultBlockState();
        if (!bed.isEmpty()) Crafts.store(level, v, bed);
        if (Market.stock(level, v.id(), s -> s.is(ItemTags.WOOL)) < 3) return null;
        ItemStack wool = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL));
        if (wool.isEmpty()) return null;
        if (!Crafts.take(level, v, s -> s.is(wool.getItem()), 2) && !Crafts.take(level, v, s -> s.is(ItemTags.WOOL), 2)) {
            Crafts.store(level, v, wool);
            return null;
        }
        if (!Crafts.usePlanks(level, v, 3)) {
            Crafts.store(level, v, new ItemStack(wool.getItem(), 3));
            return null;
        }
        String colour = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(wool.getItem()).getPath().replace("_wool", "");
        Block made = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace(colour + "_bed"));
        return made instanceof net.minecraft.world.level.block.BedBlock ? made.defaultBlockState() : Blocks.WHITE_BED.defaultBlockState();
    }

    /**
     * The ground floor's two flat doors, hung once the block stands (left out of the drawing, so the
     * lamp posts the town puts by a building's doors go by its front door and not in its hall): a door
     * out of the stores, or two planks. Returns how many were hung.
     */
    static int doors(ServerLevel level, @Nullable Villages.Village v, Ledger.Building b, boolean free) {
        int n = 0;
        Direction way = b.facing().getClockWise();
        for (int dz : new int[]{ FRONT_DOOR, BACK_DOOR }) {
            BlockPos at = cell(b, HALL_WALL, 0, dz);
            if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()) continue;
            if (!level.getBlockState(at.below()).isSolid()) continue;
            Block door = Blocks.SPRUCE_DOOR;
            if (!free) {
                if (v == null || !TownJobs.atWork(level, v, "flats", at, "hanging the doors in " + name(v.id(), b))) return n;
                ItemStack made = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOODEN_DOORS));
                if (!made.isEmpty() && Block.byItem(made.getItem()) instanceof net.minecraft.world.level.block.DoorBlock) {
                    door = Block.byItem(made.getItem());
                } else {
                    if (!made.isEmpty()) Crafts.store(level, v, made);
                    if (!Crafts.usePlanks(level, v, 2)) return n;
                    door = Masonry.woodBlock(Masonry.woodOf(level, v), "_door");
                }
            }
            BlockState st = door.defaultBlockState();
            if (st.hasProperty(net.minecraft.world.level.block.DoorBlock.FACING)) {
                st = st.setValue(net.minecraft.world.level.block.DoorBlock.FACING, way);
            }
            BuildGoal.hangDoor(level, at, st);
            n++;
        }
        return n;
    }

    /**
     * Each flat's number on the landing beside its door, and who lives there ("Flat 2B / Tansy"), on a
     * sign nailed to the hall wall: a sign out of the stores, or two planks, the first time. The names
     * are kept up to date as households come and go.
     */
    static int numbers(ServerLevel level, @Nullable Villages.Village v, Ledger.Building b, boolean free) {
        UUID id = v == null ? null : v.id();
        Map<Long, Homes.Home> homes = id == null ? Map.of() : Homes.homes(id);
        Direction face = b.facing().getClockWise();
        int n = 0;
        int storeys = id == null ? 3 : storeys(id, b);
        for (int s = 0; s < storeys; s++) {
            for (boolean back : new boolean[]{ false, true }) {
                BlockPos at = cell(b, HALL_WALL + 1, STOREY * s + 1, back ? 0 : -3);
                BlockPos wall = at.relative(face.getOpposite());
                BlockState there = level.getBlockState(at);
                if (!(there.getBlock() instanceof WallSignBlock)) {
                    if (!there.isAir() || !level.getBlockState(wall).isSolid()) continue;
                    if (!free) {
                        if (v == null || !TownJobs.atWork(level, v, "signs", at, "nailing up the flat numbers in " + name(id, b))) return n;
                        if (!Crafts.sign(level, v)) return n;
                    }
                    level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, face), 3);
                    n++;
                }
                if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) continue;
                String number = "Flat " + (s + 1) + (back ? "B" : "A");
                String who = "To let";
                Homes.Home h = homes.get(cell(b, -1, STOREY * s, back ? 2 : -2).asLong());
                if (h != null && !h.members.isEmpty() && id != null) {
                    List<VillageFolkEntity> m = Homes.grown(Homes.loadedMembers(id, h));
                    if (!m.isEmpty()) who = m.get(0).displayNameCap() + (m.size() > 1 ? " & " + m.get(1).displayNameCap() : "");
                }
                TownLife.write(sign, new String[]{ "", number, who.length() > 15 ? who.substring(0, 15) : who, "" });
            }
        }
        return n;
    }

    /**
     * Iron railings round the balconies across the upper flats' fronts (the drawing's slabs on their
     * brackets, a storey up and more): the smith's work, out of the stores (iron bars put by, or six
     * bars of iron beaten into sixteen, never while the village is putting iron by for its age). From
     * the Iron Age.
     */
    static int railings(ServerLevel level, @Nullable Villages.Village v, Ledger.Building b, boolean free) {
        if (v != null && Villages.ageOf(v.id()).ordinal() < Villages.Age.IRON.ordinal() && !free) return 0;
        int n = 0;
        List<BlockPos> run = new ArrayList<>();
        int storeys = v == null ? 3 : storeys(v.id(), b);
        for (int s = 1; s < storeys; s++) {
            for (int dx = ROOM_LEFT; dx <= ROOM_RIGHT; dx++) {
                BlockPos at = cell(b, dx, STOREY * s, BALCONY);
                if (!level.getBlockState(at).isAir() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) continue;
                run.add(at);
            }
        }
        if (run.isEmpty()) return 0;
        if (!free) {
            // Worked from the street below the first floor's balcony (the hand's reach goes no higher).
            BlockPos under = cell(b, ROOM_LEFT, STOREY, BALCONY);
            if (v == null || !TownJobs.atWork(level, v, "flats", under, "putting up the balconies' railings at " + name(v.id(), b))) return 0;
        }
        for (BlockPos at : run) {
            if (!free && !bars(level, v)) break;
            level.setBlock(at, Blocks.IRON_BARS.defaultBlockState(), 3);
            n++;
        }
        for (BlockPos at : run) {
            BlockState st = level.getBlockState(at);
            BlockState joined = Block.updateFromNeighbourShapes(st, level, at);
            if (joined != st) level.setBlock(at, joined, 3);
        }
        return n;
    }

    /** A length of iron railing out of the stores: one put by, or sixteen beaten out of six bars of iron. */
    private static boolean bars(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.IRON_BARS), 1)) return true;
        if (Crafts.savingIron(level, v)) return false;
        if (Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) < 6 + Crafts.IRON_KEPT) return false;
        if (!Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 6)) return false;
        Crafts.store(level, v, new ItemStack(Items.IRON_BARS, 15));
        return true;
    }

    // ------------------------------------------------------------------ a fourth storey (the Diamond Age)

    /**
     * A fourth storey on a block: two more flats. Paid for all at once out of the stores before the
     * roof comes off (Masonry.buildIn: what the stores can pay for, kept with the block); the roof, its
     * gables and chimneys and the cornice under it then come off where the new storey differs, top
     * down, and back into the stores; and the storey goes up a layer at a time, the roof back on top.
     * Its beds come out of the stores later, as any flat's do. Returns the blocks changed.
     */
    static int storey(ServerLevel level, Villages.Village v, Ledger.Building b, int budget, boolean free) {
        UUID id = v.id();
        long key = b.anchor().asLong();
        List<BuildGoal.Placement> was = plan(BLOCK, b), will = plan(TALLER, b);
        if (will.isEmpty() || tall(id, b)) return 0;
        String name = name(id, b);
        if (!raising(id, b)) {
            if (!free && !payForStorey(level, v, b, was, will)) return 0;
            Ledger.note(id, RAISING + key, "1");
            Villages.tell(id, level.getDayTime() / 24000L, "the builders began a fourth storey on " + name);
        }
        if (!free && !TownJobs.atWork(level, v, "storeys", b.anchor(), "putting a fourth storey on " + name)) return 0;
        Map<BlockPos, BuildGoal.Placement> next = new HashMap<>();
        for (BuildGoal.Placement p : will) next.put(p.pos(), p);
        // The roof off, top down, where the new storey differs from it (once: after that, what stands there is the new storey):
        // the roof and its gables and chimneys, the garret's floor, the cornice's brackets under the eaves, the old top's rail.
        int roofAt = b.anchor().getY() + STOREY * 2 + 3;
        boolean stripped = "1".equals(Ledger.note(id, STRIPPED + key));
        if (!stripped) {
            // The fires on the old chimneys' tops (the town's: TownLife) put out first, being no part of the drawing.
            for (BlockPos top : TownLife.fittings(b).chimneys()) {
                if (!level.getBlockState(top.above()).is(Blocks.CAMPFIRE)) continue;
                level.setBlock(top.above(), Blocks.AIR.defaultBlockState(), 3);
                if (!free) Crafts.giveBack(level, v, Items.CAMPFIRE, 1);
            }
        }
        List<BuildGoal.Placement> off = new ArrayList<>();
        for (BuildGoal.Placement p : stripped ? List.<BuildGoal.Placement>of() : was) {
            if (p.pos().getY() < roofAt - 3 || p.part() == BuildGoal.Part.CLEAR) continue;
            BuildGoal.Placement q = next.get(p.pos());
            if (q != null && q.part() == p.part() && q.style() == p.style() && q.way() == p.way()) continue;
            BlockState now = level.getBlockState(p.pos());
            if (now.isAir() || now.is(BlockTags.BEDS) || now.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) continue;
            off.add(p);
        }
        if (!off.isEmpty()) {
            off.sort((x, y) -> y.pos().getY() - x.pos().getY());
            Map<net.minecraft.world.item.Item, Integer> back = new HashMap<>();
            int n = 0;
            for (BuildGoal.Placement p : off) {
                if (n >= budget) break;
                if (!free) back.merge(level.getBlockState(p.pos()).getBlock().asItem(), 1, Integer::sum);
                level.setBlock(p.pos(), Blocks.AIR.defaultBlockState(), 2 | 16);
                n++;
            }
            if (!free) for (Map.Entry<net.minecraft.world.item.Item, Integer> e : back.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
            return n;
        }
        if (!stripped) Ledger.note(id, STRIPPED + key, "1");
        // The new storey, a layer at a time from the bottom: what is missing of the taller drawing. A layer tried
        // three times is done with (something in the way must not hold up the roof).
        java.util.function.Function<BuildGoal.Placement, BlockState> paint = free ? com.jrpetty.mcassistant.Showcase.painter(Ages.CIVIC)
            : lookOf(level, id, b);
        int lowest = Integer.MAX_VALUE;
        Set<BlockPos> missing = new HashSet<>();
        for (BuildGoal.Placement p : will) {
            if (p.part() == BuildGoal.Part.CLEAR || p.pos().getY() < roofAt - 3) continue;
            if (!free && p.part() == BuildGoal.Part.BED) continue;             // furnished out of the stores later
            if (paint.apply(p) == null) continue;
            BlockState now = level.getBlockState(p.pos());
            // (A fire the town lit on an old chimney top while the new storey went up is built over.)
            if (!now.isAir() && !now.is(Blocks.CAMPFIRE) && !(now.canBeReplaced() && now.getFluidState().isEmpty())) continue;
            if (TRIED.getOrDefault(key + ":" + p.pos().getY(), 0) >= 3) continue;
            missing.add(p.pos());
            lowest = Math.min(lowest, p.pos().getY());
        }
        if (missing.isEmpty()) {
            TRIED.keySet().removeIf(k -> k.startsWith(key + ":"));
            Ledger.note(id, TALL + key, "1");
            Ledger.forget(id, RAISING + key);
            Ledger.forget(id, STRIPPED + key);
            Ledger.forget(id, LOOK + key);
            FURNISHED.remove(key);
            Villages.tell(id, level.getDayTime() / 24000L, name + " got its fourth storey: two more flats to let");
            return 0;
        }
        final int layer = lowest;
        TRIED.merge(key + ":" + layer, 1, Integer::sum);
        Set<BlockPos> now = new HashSet<>();
        for (BlockPos p : missing) if (p.getY() == layer && now.size() < Math.max(budget, 12)) now.add(p);
        return BuildGoal.stampOnly(level, TALLER, b.anchor(), b.facing(), 13, paint, p -> now.contains(p.pos()));
    }

    /** What the storey is laid in: what was paid for (kept in the ledger), else the Iron Age's look. */
    private static java.util.function.Function<BuildGoal.Placement, BlockState> lookOf(ServerLevel level, UUID village, Ledger.Building b) {
        Masonry.Look look = Masonry.Look.decode(Ledger.note(village, LOOK + b.anchor().asLong()));
        return look != null ? look.painter(level) : com.jrpetty.mcassistant.Showcase.painter(Ages.CIVIC);
    }

    /** The new storey's makings, out of the stores all at once, in what they can pay for; kept for the storey to be built of. */
    private static boolean payForStorey(ServerLevel level, Villages.Village v, Ledger.Building b,
                                        List<BuildGoal.Placement> was, List<BuildGoal.Placement> will) {
        Map<BlockPos, BuildGoal.Placement> before = new HashMap<>();
        for (BuildGoal.Placement p : was) before.put(p.pos(), p);
        int roofAt = b.anchor().getY() + STOREY * 2 + 3;
        List<BuildGoal.Placement> cells = new ArrayList<>();
        for (BuildGoal.Placement p : will) {
            if (p.part() == BuildGoal.Part.CLEAR || p.part() == BuildGoal.Part.BED || p.pos().getY() < roofAt - 3) continue;
            BuildGoal.Placement q = before.get(p.pos());
            if (q != null && q.part() == p.part() && q.style() == p.style() && q.way() == p.way()) continue;      // stays as it is
            cells.add(p);
        }
        Masonry.Look look = Masonry.buildIn(level, v, Ages.CIVIC, cells, true);
        if (look == null) return false;
        Ledger.note(v.id(), LOOK + b.anchor().asLong(), look.encode());
        return true;
    }

    // ------------------------------------------------------------------ the stage, for the pictures

    /**
     * A block of flats set out on a stage of its own, as the town would have it in the Iron Age, from a
     * palette rather than anybody's stores (/village flats stage): furnished, its doors hung, its numbers
     * up, its balconies railed, its name by the door, the lamp posts the town puts by a front door, and
     * its chimneys smoking. For the pictures. Returns "x y z", the middle of its ground floor; its door
     * is to the south.
     */
    public static String stage(ServerLevel level, BlockPos at) {
        com.jrpetty.mcassistant.Showcase.stage(level, at.getX() - 9, at.getX() + 9, at.getZ() - 9, at.getZ() + 9, at.getY());
        BuildGoal.stamp(level, BLOCK, at, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(Ages.CIVIC));
        Ledger.Building b = new Ledger.Building(BLOCK, at, Direction.NORTH);
        doors(level, null, b, true);
        numbers(level, null, b, true);
        railings(level, null, b, true);
        nameplate(level, b, new String[]{ "Elm Row Flats", "No. 3", "Elm Row", "" });
        Ages.lamps(level, null, b, BLOCK, true);
        TownLife.chimney(level, b, null, true);
        return at.getX() + " " + at.getY() + " " + at.getZ();
    }

    /** The sign by the front door, as the town's (TownLife.addressSign) would read: on the wall to the right of it. */
    private static void nameplate(ServerLevel level, Ledger.Building b, String[] lines) {
        BlockPos spot = cell(b, 4, 1, BALCONY);
        if (!level.getBlockState(spot).isAir()) return;
        level.setBlock(spot, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, b.facing().getOpposite()), 3);
        if (level.getBlockEntity(spot) instanceof SignBlockEntity sign) TownLife.write(sign, lines);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: why the village would build a block of flats, or null. */
    @Nullable
    public static String wantForTests(UUID village) {
        return WANT.get(village);
    }

    /** Tests: the anchors of every flat in the village, on the homes' books or not. */
    public static List<BlockPos> flatsForTests(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Ledger.Building b : blocks(village)) for (Spot s : spots(village, b)) out.add(s.anchor());
        return out;
    }

    /** Tests: a flat's number ("2B"), or null if the anchor is no flat. */
    @Nullable
    public static String numberForTests(UUID village, BlockPos anchor) {
        Spot s = spotAt(village, anchor);
        return s == null ? null : s.number();
    }

    /** Tests: is this spot inside the flat at that anchor? */
    public static boolean insideForTests(UUID village, BlockPos anchor, BlockPos p) {
        Spot s = spotAt(village, anchor);
        return s != null && inside(s, p);
    }

    /** Tests: the home lots free near the square. */
    public static int freeLotsNearForTests(UUID village) {
        return freeLotsNear(village);
    }

    /** Tests: the block's upkeep (beds, doors, numbers, railings), now. */
    public static void fitForTests(ServerLevel level, Villages.Village v) {
        for (Ledger.Building b : blocks(v.id())) {
            FURNISHED.remove(b.anchor().asLong());
            for (int i = 0; i < 12 && furnish(level, v, b); i++) FURNISHED.remove(b.anchor().asLong());
            doors(level, v, b, false);
            numbers(level, v, b, false);
            railings(level, v, b, false);
        }
    }

    /** Tests: the fourth storey put on this block all at once, for nothing. */
    public static void raiseForTests(ServerLevel level, Villages.Village v, BlockPos anchor) {
        for (Ledger.Building b : blocks(v.id())) {
            if (!b.anchor().equals(anchor)) continue;
            for (int i = 0; i < 60 && !tall(v.id(), b); i++) storey(level, v, b, 400, true);
        }
    }

    /** Tests: what the Buildings page is sent about the block at this anchor (Annals). */
    public static CompoundTag annalsForTests(UUID village, BlockPos anchor) {
        CompoundTag c = new CompoundTag();
        for (Ledger.Building b : blocks(village)) if (b.anchor().equals(anchor)) annals(village, b, c);
        return c;
    }

    /** Tests: the beds the village's blocks hold. */
    public static int bedsPlannedForTests(UUID village) {
        return bedsPlanned(village);
    }

    /** Tests: the name of the block at this anchor. */
    @Nullable
    public static String nameForTests(UUID village, BlockPos anchor) {
        for (Ledger.Building b : blocks(village)) if (b.anchor().equals(anchor)) return name(village, b);
        return null;
    }
}
