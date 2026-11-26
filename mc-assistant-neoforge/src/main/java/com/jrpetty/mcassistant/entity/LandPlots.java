package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [wpc] Land a player may buy: vacant ground in the sizes the town's own plan is laid out in, so a player's land lines up
 * with its streets and looks like its neighbours'.
 * <ul>
 * <li><b>The sizes.</b> A <i>lot</i> (one of the plan's house lots, eleven square, on its street); <i>a double frontage</i>
 *     (two lots side by side on the same street); <i>a long lot</i> (two end to end beside an avenue, as the town's own long
 *     lots: a villa and its garden, a house with a shop); <i>a whole block</i> (the four lots between four streets: a
 *     manor, a house and shop with a yard); and <i>a smallholding</i> out past the town's growth line, a field the size of
 *     the folk's own (VillageFolkEntity.FIELD_MOST each way), for a farm of your own. Two titles side by side may be merged
 *     into one when the town is not short of houses.</li>
 * <li><b>The price</b> is the builders' own price for the ground (HousingMarket.plotFee: nearer the square dearer, the
 *     town's age, wealth and land index), times its area in lots, with a premium for assembling more than one lot, a quarter
 *     more while households wait for a roof, and a field's ground at two fifths of a building lot's.</li>
 * <li><b>The limits.</b> A friend of the town may hold one lot; a citizen up to four (a smallholding besides). Players
 *     together never hold more than fifteen in the hundred of the plan's house lots. Never land the town has built on or is
 *     building on, its farmland or the mine's ground, lots kept for its hall, trades and towers, nor the ring of the plan
 *     kept clear for it to grow into (LandUse's growth line); a smallholding goes only past that line.</li>
 * <li><b>Owning it.</b> A title on the register (and a deed, the register's copy, in the pack), corner posts with lanterns
 *     on the ground, the lots held off the town's own builders, and rates every week: two in the hundred of the price (a
 *     citizen a quarter less), twice that once it has stood vacant fourteen days. Unpaid rates go on the debts ledger
 *     (Debts). The town buys land back at four fifths of what was paid.</li>
 * </ul>
 * Kept with the town's books (PropertyBook "land").
 */
public final class LandPlots implements PlayerLife.Page {

    /** The most lots a player may hold in a town; players' share of the plan's house lots, in the hundred. */
    static final int MOST_LOTS = 4, PLAYERS_SHARE = 15;
    /** Weekly rates, in the hundred of the price; vacant land pays double after so many days. */
    static final int RATE_PCT = 2, VACANT_DAYS = 14;
    /** What the town pays to buy land back, in the hundred of what was paid. */
    static final int BUY_BACK = 80;
    /** A smallholding's half-width: a folk's own field. */
    static final int FIELD = VillageFolkEntity.FIELD_MOST;

    /** The sizes, in the order the page offers them. */
    public enum Kind {
        LOT("A lot", "one house lot, eleven blocks square, on its street", 1),
        DOUBLE("A double frontage", "two lots side by side on one street", 2),
        LONG("A long lot", "two lots end to end by an avenue", 2),
        BLOCK("A whole block", "the four lots between four streets", 4),
        FIELD("A smallholding", "a field the size of the folk's own, past the town's growth line", 0);

        public final String title, words;
        public final int lots;

        Kind(String title, String words, int lots) {
            this.title = title;
            this.words = words;
            this.lots = lots;
        }

        static Kind of(String s) {
            try { return valueOf(s.toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { return LOT; }
        }
    }

    /** A title: its id, holder, size, ground (world x0, z0, x1, z1), door, lots, when bought, what was paid, and the rest. */
    public record Title(String id, UUID owner, String ownerName, Kind kind, int[] box, Direction facing, long[] cells, long since,
                        int price, boolean built, String firm, String name) {
        public BlockPos centre(int y) {
            return new BlockPos((box[0] + box[2]) / 2, y, (box[1] + box[3]) / 2);
        }

        public int area() {
            return (box[2] - box[0] + 1) * (box[3] - box[1] + 1);
        }
    }

    /** Ground the page can offer: its size, its plan lots (none for a field), its box in offsets from the heart, its back. */
    record Parcel(Kind kind, List<TownPlan.Lot> lots, int[] box, int back) {}

    // ------------------------------------------------------------------ the register

    static CompoundTag book(UUID village) {
        return PropertyBook.town(village, "land");
    }

    static Title view(String id, CompoundTag t) {
        UUID owner = t.hasUUID("owner") ? t.getUUID("owner") : new UUID(0, 0);
        return new Title(id, owner, t.getString("ownerName"), Kind.of(t.getString("kind")), t.getIntArray("box"),
            Direction.from2DDataValue(t.getInt("face")), t.getLongArray("cells"), t.getLong("since"), t.getInt("price"),
            t.getBoolean("built"), t.getString("firm"), t.getString("name"));
    }

    /** Every title in the town. */
    public static List<Title> all(UUID village) {
        List<Title> out = new ArrayList<>();
        CompoundTag b = book(village);
        for (String k : b.getAllKeys()) if (b.get(k) instanceof CompoundTag t && t.hasUUID("owner")) out.add(view(k, t));
        out.sort((a, c) -> Long.compare(a.since(), c.since()));
        return out;
    }

    /** A player's titles here (its own, and its firms'). */
    public static List<Title> of(UUID village, UUID player) {
        List<Title> out = new ArrayList<>();
        for (Title t : all(village)) if (t.owner().equals(player)) out.add(t);
        return out;
    }

    @Nullable
    public static Title get(UUID village, String id) {
        CompoundTag b = book(village);
        return b.get(id) instanceof CompoundTag t && t.hasUUID("owner") ? view(id, t) : null;
    }

    @Nullable
    static CompoundTag raw(UUID village, String id) {
        CompoundTag b = book(village);
        return b.get(id) instanceof CompoundTag t ? t : null;
    }

    /** Does this player hold the ground at this spot? */
    public static boolean owns(UUID village, UUID player, BlockPos p) {
        for (Title t : of(village, player)) {
            int[] b = t.box();
            if (p.getX() >= b[0] && p.getX() <= b[2] && p.getZ() >= b[1] && p.getZ() <= b[3]) return true;
        }
        return false;
    }

    /** The title over this spot, whoever holds it. */
    @Nullable
    public static Title at(UUID village, int x, int z) {
        for (Title t : all(village)) {
            int[] b = t.box();
            if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) return t;
        }
        return null;
    }

    /**
     * Does this player hold title to all of this ground (world corners, either way round)? Every column of it must lie in a
     * title of the player's (one, or several side by side). For a farm of its own on land it bought (WP-B: PlayerFarms).
     */
    public static boolean holdsTitle(UUID village, UUID player, int x0, int z0, int x1, int z1) {
        List<Title> mine = of(village, player);
        if (mine.isEmpty()) return false;
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        if ((long) (bx - ax + 1) * (bz - az + 1) > 200_000) return false;
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                boolean in = false;
                for (Title t : mine) {
                    int[] b = t.box();
                    if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) { in = true; break; }
                }
                if (!in) return false;
            }
        }
        return true;
    }

    /** Lots players hold here in all (a field counts none). */
    static int playersLots(UUID village) {
        int n = 0;
        for (Title t : all(village)) n += t.cells().length;
        return n;
    }

    static int lotsOf(UUID village, UUID player) {
        int n = 0;
        for (Title t : of(village, player)) n += t.cells().length;
        return n;
    }

    /** The plan's house lots laid out in the town (what the players' share is reckoned on). */
    static int homeLots(UUID village) {
        int n = 0;
        for (TownPlan.Lot l : TownPlan.lots()) if (l.kind() == TownPlan.Kind.LOT && l.use().equals("home") && CityGrowth.laidOut(village, l)) n++;
        return n;
    }

    // ------------------------------------------------------------------ the plan's lots

    private static Map<Long, TownPlan.Lot> LOTS;

    /** The plan's ordinary lots by cell. */
    static synchronized Map<Long, TownPlan.Lot> lots() {
        if (LOTS == null) {
            Map<Long, TownPlan.Lot> m = new HashMap<>();
            for (TownPlan.Lot l : TownPlan.lots()) if (l.kind() == TownPlan.Kind.LOT) m.put(TownPlan.cellKey(l.x(), l.z()), l);
            LOTS = m;
        }
        return LOTS;
    }

    @Nullable
    static TownPlan.Lot lotAt(int ox, int oz) {
        return lots().get(TownPlan.cellKey(ox, oz));
    }

    /** The lot back to back with this one across its block, along one axis (0 if it has none: the lots by the avenues). */
    static int partner(int c) {
        int a = Math.abs(c), s = Integer.signum(c);
        if (a < TownPlan.RING + TownPlan.STREET) return 0;
        int off = Math.floorMod(a - (TownPlan.RING + TownPlan.STREET), TownPlan.PERIOD);
        return s * (off < TownPlan.LOT ? a + TownPlan.LOT : a - TownPlan.LOT);
    }

    /** A lot's door, as the way a building's front faces. */
    static Direction door(int back) {
        return switch (back) {
            case TownPlan.NORTH -> Direction.SOUTH;
            case TownPlan.SOUTH -> Direction.NORTH;
            case TownPlan.EAST -> Direction.WEST;
            default -> Direction.EAST;
        };
    }

    /** The ground of the given size round this lot (offsets from the heart), or null if the plan has none there. */
    @Nullable
    static Parcel parcel(Kind kind, int ox, int oz) {
        TownPlan.Lot a = lotAt(ox, oz);
        if (a == null) return null;
        List<TownPlan.Lot> cells = new ArrayList<>();
        cells.add(a);
        switch (kind) {
            case LOT -> { }
            case DOUBLE -> {
                boolean ns = a.back() == TownPlan.NORTH || a.back() == TownPlan.SOUTH;   // its street runs east-west: the neighbour along x
                TownPlan.Lot b = ns ? lotAt(partner(ox), oz) : lotAt(ox, partner(oz));
                if (b == null || b.back() != a.back()) {
                    // the other way: side by side across the block (both lots facing one street)
                    b = ns ? lotAt(ox, partner(oz)) : lotAt(partner(ox), oz);
                    if (b == null || b.back() != a.back()) return null;
                }
                cells.add(b);
            }
            case LONG -> {
                for (TownPlan.Lot l : TownPlan.lots()) {
                    if (l.kind() != TownPlan.Kind.LONG || !l.use().equals("long")) continue;
                    for (long c : l.cells()) if (c == a.key()) {
                        cells.clear();
                        for (long k : l.cells()) {
                            TownPlan.Lot part = lots().get(k);
                            if (part == null) return null;
                            cells.add(part);
                        }
                        return new Parcel(kind, cells, box(cells), l.back());
                    }
                }
                return null;
            }
            case BLOCK -> {
                int px = partner(ox), pz = partner(oz);
                if (px == 0 || pz == 0) return null;
                TownPlan.Lot b = lotAt(px, oz), c = lotAt(ox, pz), d = lotAt(px, pz);
                if (b == null || c == null || d == null) return null;
                cells.add(b);
                cells.add(c);
                cells.add(d);
            }
            case FIELD -> { return null; }
        }
        return new Parcel(kind, cells, box(cells), a.back());
    }

    static int[] box(List<TownPlan.Lot> cells) {
        int[] out = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (TownPlan.Lot l : cells) {
            int[] b = TownPlan.box(l);
            out[0] = Math.min(out[0], b[0]);
            out[1] = Math.min(out[1], b[1]);
            out[2] = Math.max(out[2], b[2]);
            out[3] = Math.max(out[3], b[3]);
        }
        return out;
    }

    /**
     * Is something of the town's standing on this lot: a building on its books, a house, a property on the register, a site its
     * builders hold? (The plan's own list of lots spoken for is kept in memory and may be short after a restart.)
     */
    static boolean builtOn(UUID village, BlockPos heart, TownPlan.Lot l) {
        int[] b = TownPlan.box(l);
        int x0 = heart.getX() + b[0], z0 = heart.getZ() + b[1], x1 = heart.getX() + b[2], z1 = heart.getZ() + b[3];
        for (com.jrpetty.mcassistant.village.Ledger.Building s : com.jrpetty.mcassistant.village.Ledger.buildings(village)) {
            int[] f;
            try { f = com.jrpetty.mcassistant.entity.goal.BuildGoal.box(s.structure(), s.anchor(), s.facing(), false); } catch (RuntimeException e) { continue; }
            if (f[0] <= x1 && f[1] >= x0 && f[2] <= z1 && f[3] >= z0) return true;
        }
        for (Homes.Home h : Homes.homes(village).values()) if (h.anchor.getX() >= x0 && h.anchor.getX() <= x1 && h.anchor.getZ() >= z0 && h.anchor.getZ() <= z1) return true;
        for (Villages.Site s : Villages.sitesOf(village).values()) if (s.anchor().getX() >= x0 - 2 && s.anchor().getX() <= x1 + 2 && s.anchor().getZ() >= z0 - 2 && s.anchor().getZ() <= z1 + 2) return true;
        int cx = heart.getX() + l.x(), cz = heart.getZ() + l.z();
        return PropertyMarket.overlapping(village, new BlockPos(cx, 0, cz), b[2] - l.x(), b[3] - l.z(), null) != null;
    }

    /** Something standing on the ground itself (a wall, a floor, a path), sampled at its middle and toward its corners. */
    static boolean blocked(ServerLevel level, int[] world) {
        int[][] pts = { { (world[0] + world[2]) / 2, (world[1] + world[3]) / 2 }, { world[0] + 2, world[1] + 2 }, { world[2] - 2, world[1] + 2 },
            { world[0] + 2, world[3] - 2 }, { world[2] - 2, world[3] - 2 } };
        for (int[] p : pts) {
            if (!level.isLoaded(new BlockPos(p[0], 64, p[1]))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p[0], p[1]);
            net.minecraft.world.level.block.state.BlockState g = level.getBlockState(new BlockPos(p[0], y - 1, p[1]));
            if (!BuildPlots.natural(g) && !g.is(net.minecraft.tags.BlockTags.LEAVES) && !g.is(net.minecraft.tags.BlockTags.LOGS)) return true;
        }
        return false;
    }

    /** Why the player may not have this lot of the plan ("" if it may): the town's, kept, or another's. */
    static String lotProblem(UUID village, BlockPos heart, TownPlan.Lot l) {
        if (!CityGrowth.laidOut(village, l)) return "the town hasn't laid that ring out yet";
        if (!l.use().equals("home")) return "kept for the town's " + (l.use().equals("civic") ? "trades on the square" : l.use().startsWith("great") ? "great buildings" : "watchtowers");
        if (TownPlan.ringOf(l) >= Math.max(1, LandUse.builtRings(village))) return "inside the ring kept clear for the town to grow into";
        if (Villages.lotTaken(village, l)) {
            Title t = at(village, heart.getX() + l.x(), heart.getZ() + l.z());
            return t != null ? "it's " + t.ownerName() + "'s" : "the town has built on it, or is building on it";
        }
        if (Villages.lotKeptOff(village, heart, l)) return "it's the town's fields, pens or the mine's ground";
        if (builtOn(village, heart, l)) return "the town has built on it, or is building on it";
        if (at(village, heart.getX() + l.x(), heart.getZ() + l.z()) != null) return "somebody holds it already";
        for (Villages.Site s : Villages.sitesOf(village).values()) {
            int[] b = TownPlan.box(l);
            int dx = s.anchor().getX() - heart.getX(), dz = s.anchor().getZ() - heart.getZ();
            if (dx >= b[0] - 2 && dx <= b[2] + 2 && dz >= b[1] - 2 && dz <= b[3] + 2) return "the town has its builders queued for it";
        }
        return "";
    }

    /** What the lot shows as on the map: 0 free, 2 the town's, 3 fields or mine, 4 yours, 5 another's, 6 kept for the town. */
    static int state(UUID village, BlockPos heart, TownPlan.Lot l, UUID me) {
        Title t = at(village, heart.getX() + l.x(), heart.getZ() + l.z());
        if (t != null) return t.owner().equals(me) ? 4 : 5;
        if (Villages.lotTaken(village, l) || builtOn(village, heart, l)) return 2;
        if (Villages.lotKeptOff(village, heart, l)) return 3;
        return lotProblem(village, heart, l).isEmpty() ? 0 : 6;
    }

    // ------------------------------------------------------------------ smallholdings

    /** Up to four smallholdings to be had: field-sized ground past the growth line, off the farmland, the mine and others'. */
    static List<int[]> fields(ServerLevel level, Villages.Village v) {
        List<int[]> out = new ArrayList<>();
        UUID id = v.id();
        BlockPos c = v.centre();
        int from = LandUse.growthReach(id) + FIELD + 2;
        for (int r = from; r <= from + 40 && out.size() < 4; r += 6) {
            for (int a = 0; a < 16 && out.size() < 4; a++) {
                double ang = Math.PI * 2 * a / 16.0;
                int dx = (int) Math.round(Math.cos(ang) * r), dz = (int) Math.round(Math.sin(ang) * r);
                if (!fieldProblem(level, v, dx, dz).isEmpty()) continue;
                boolean near = false;
                for (int[] o : out) if (Math.abs(o[0] - dx) <= 2 * FIELD + 4 && Math.abs(o[1] - dz) <= 2 * FIELD + 4) near = true;
                if (!near) out.add(new int[]{ dx, dz });
            }
        }
        return out;
    }

    /** Why a smallholding may not go here ("" if it may). */
    static String fieldProblem(ServerLevel level, Villages.Village v, int dx, int dz) {
        UUID id = v.id();
        BlockPos c = v.centre();
        int x = c.getX() + dx, z = c.getZ() + dz;
        if (Math.max(Math.abs(dx), Math.abs(dz)) - FIELD <= LandUse.growthReach(id)) return "it's inside the town's growth line";
        if (Villages.onFarmland(id, dx, dz, FIELD, FIELD)) return "it's on the town's farmland";
        if (MineGround.anyMeets(x - FIELD, z - FIELD, x + FIELD, z + FIELD)) return "it's on the mine's ground";
        if (Villages.builtOver(id, dx, dz, FIELD, FIELD)) return "the town has built there";
        for (Title t : all(id)) {
            int[] b = t.box();
            if (x + FIELD >= b[0] - 2 && x - FIELD <= b[2] + 2 && z + FIELD >= b[1] - 2 && z - FIELD <= b[3] + 2) return "it's " + t.ownerName() + "'s";
        }
        if (!level.isLoaded(new BlockPos(x, c.getY(), z))) return "that ground isn't loaded";
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (!level.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty()) return "it's water";
        return "";
    }

    // ------------------------------------------------------------------ the price

    /** The price and how it was reached: {total, base, premium, pressure}, coins. */
    static int[] price(UUID village, BlockPos heart, Kind kind, int[] box) {
        BlockPos mid = heart.offset((box[0] + box[2]) / 2, 0, (box[1] + box[3]) / 2);
        double lotFee = HousingMarket.plotFee(village, mid) * (MarketMaker.live(village) ? Math.min(1.5, Math.sqrt(Math.max(0.25, LandTitles.index(village)))) : 1.0);
        double area = (box[2] - box[0] + 1.0) * (box[3] - box[1] + 1.0) / (TownPlan.LOT * TownPlan.LOT);
        double base = lotFee * area * (kind == Kind.FIELD ? 0.4 : 1.0);
        double premium = kind.lots > 1 ? base * 0.1 * (kind.lots - 1) : 0;
        boolean pressed = kind != Kind.FIELD && pressure(village);
        double press = pressed ? (base + premium) * 0.25 : 0;
        int b = (int) Math.ceil(base), pr = (int) Math.round(premium), ps = (int) Math.round(press);
        return new int[]{ Math.max(2, b + pr + ps), b, pr, ps };
    }

    /** Are households waiting for a roof (no merging, and a quarter on the price)? */
    static boolean pressure(UUID village) {
        if (PRESSURE_FOR_TESTS != null) return PRESSURE_FOR_TESTS;
        Villages.Village v = Villages.get(village);
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (v == null || server == null) return false;
        try {
            return Homes.wantsAHouse(server.overworld(), village);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nullable public static Boolean PRESSURE_FOR_TESTS;

    /** A week's rates on a title, coins: two in the hundred of its price, a citizen a quarter off, twice once vacant a fortnight. */
    static int rates(ServerLevel level, UUID village, Title t) {
        double r = Math.max(1, t.price() * RATE_PCT / 100.0);
        if (Citizens.is(village, t.owner())) r *= 0.75;
        if (vacant(level, village, t)) r *= 2;
        return (int) Math.max(1, Math.round(r));
    }

    /** Has it stood empty a fortnight (nothing built or going up on it)? */
    static boolean vacant(ServerLevel level, UUID village, Title t) {
        if (t.built() || t.kind() == Kind.FIELD && farmed(village, t)) return false;
        return PropertyBook.day(level) - t.since() >= VACANT_DAYS;
    }

    /** A smallholding with a farm registered on it is in use (WP-B's farms check titles; a hoed field counts too). */
    static boolean farmed(UUID village, Title t) {
        CompoundTag r = raw(village, t.id());
        return r != null && r.getBoolean("farmed");
    }

    // ------------------------------------------------------------------ buying, merging, selling

    /** Why this player may not buy this ("" if it may): its standing, its share, the players' share. */
    static String standingProblem(ServerLevel level, UUID village, ServerPlayer p, Kind kind) {
        UUID me = p.getUUID();
        boolean citizen = Citizens.is(village, me);
        Standing.Title title = Standing.of(village, me, level.getGameTime()).title();
        if (!citizen && !title.atLeast(Standing.Title.FRIEND)) return "the town sells land to its friends: be one first (talk with folk, give gifts)";
        if (Insolvency.bankrupt(village, me)) return "you're bankrupt till your discharge: no land till then";
        if (kind == Kind.FIELD) {
            for (Title t : of(village, me)) if (t.kind() == Kind.FIELD) return "one smallholding to a player";
            return "";
        }
        int have = lotsOf(village, me), want = kind.lots;
        if (!citizen && have + want > 1) return "a friend of the town may hold one lot; a citizen up to " + MOST_LOTS;
        if (have + want > MOST_LOTS) return "a player may hold " + MOST_LOTS + " lots here at most (you hold " + have + ")";
        int cap = homeLots(village) * PLAYERS_SHARE / 100;
        if (playersLots(village) + want > Math.max(1, cap)) return "players together may hold " + PLAYERS_SHARE + "% of the town's house lots (" + Math.max(1, cap) + "), and do";
        return "";
    }

    /** Buy this ground (a lot of the plan's, by its offset; a field's middle offset). Asks first; then the title and the deed. */
    static String buy(ServerLevel level, ServerPlayer p, Villages.Village v, Kind kind, int ox, int oz, boolean sure, PlayerLife.Ctx c) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        String who = standingProblem(level, id, p, kind);
        if (!who.isEmpty()) return "!You can't buy that: " + who + ".";
        int[] box;
        List<TownPlan.Lot> cells = new ArrayList<>();
        int back;
        if (kind == Kind.FIELD) {
            String bad = fieldProblem(level, v, ox, oz);
            if (!bad.isEmpty()) return "!Not there: " + bad + ".";
            box = new int[]{ ox - FIELD, oz - FIELD, ox + FIELD, oz + FIELD };
            back = BuildPlots.toward(heart.offset(ox, 0, oz), heart).getOpposite().get2DDataValue() == Direction.NORTH.get2DDataValue() ? TownPlan.NORTH : TownPlan.SOUTH;
        } else {
            Parcel pc = parcel(kind, ox, oz);
            if (pc == null) return "!The plan has no " + kind.title.toLowerCase(Locale.ROOT) + " there: choose another lot, or another size.";
            for (TownPlan.Lot l : pc.lots()) {
                String bad = lotProblem(id, heart, l);
                if (!bad.isEmpty()) return "!Not that ground: " + bad + ".";
            }
            box = pc.box();
            cells.addAll(pc.lots());
            back = pc.back();
            if (blocked(level, new int[]{ heart.getX() + box[0], heart.getZ() + box[1], heart.getX() + box[2], heart.getZ() + box[3] })) {
                return "!Not that ground: something stands on it already (a floor, a path, a wall). Choose another lot.";
            }
        }
        int[] pr = price(id, heart, kind, box);
        int have = Market.coinsHeld(p) + PropertyBook.account(id, p.getUUID());
        if (have < pr[0]) return "!" + kind.title + " here is " + PropertyBook.coins(pr[0]) + "; you have " + have + " with your bank account. Nothing was paid.";
        if (!sure) {
            return PropertyBook.ask(c, "buy", LifePage.args("kind", kind.name(), "ox", ox, "oz", oz),
                "Buy " + kind.title.toLowerCase(Locale.ROOT) + " " + PropertyMarket.where(id, heart.offset((box[0] + box[2]) / 2, 0, (box[1] + box[3]) / 2))
                    + " for " + PropertyBook.coins(pr[0]) + "? Rates are about " + PropertyBook.coins(Math.max(1, pr[0] * RATE_PCT / 100)) + " a week, and double once it stands "
                    + "empty " + VACANT_DAYS + " days. The town buys land back at " + BUY_BACK + "% of what you paid.", "Buy it");
        }
        int paid = PropertyBook.pay(p, id, pr[0], PlayerFinance.Cat.INVESTED, "land bought: " + kind.title.toLowerCase(Locale.ROOT), true);
        if (paid < pr[0]) return "!You couldn't find " + PropertyBook.coins(pr[0]) + " between your pack and your account. Nothing was paid.";
        long day = PropertyBook.day(level);
        String tid = "L" + day + "_" + Math.abs((heart.getX() + box[0]) * 31 + (heart.getZ() + box[1]));
        CompoundTag t = new CompoundTag();
        t.putUUID("owner", p.getUUID());
        t.putString("ownerName", Belonging.called(p));
        t.putString("kind", kind.name());
        int[] world = { heart.getX() + box[0], heart.getZ() + box[1], heart.getX() + box[2], heart.getZ() + box[3] };
        t.putIntArray("box", world);
        t.putInt("face", door(back).get2DDataValue());
        long[] keys = new long[cells.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = cells.get(i).key();
        t.put("cells", new LongArrayTag(keys));
        t.putLong("since", day);
        t.putLong("paidTo", day + 7);
        t.putInt("price", pr[0]);
        String name = kind.title + " " + PropertyMarket.where(id, heart.offset((box[0] + box[2]) / 2, 0, (box[1] + box[3]) / 2));
        t.putString("name", name);
        book(id).put(tid, t);
        PropertyBook.dirty();
        hold(id, view(tid, t));
        posts(level, t, true);
        PropertyItems.deed(p, id, "land", tid, name);
        Villages.tell(id, day, Belonging.called(p) + " bought " + name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1) + " for " + PropertyBook.coins(pr[0]));
        Planner.add(p, (day + 7) * 24000L + 6000, "rates", "Land rates due on " + name.toLowerCase(Locale.ROOT) + ": about " + PropertyBook.coins(rates(level, id, view(tid, t))), world == null ? null : view(tid, t).centre(heart.getY()));
        return "Bought: " + name.toLowerCase(Locale.ROOT) + " for " + PropertyBook.coins(pr[0]) + ". Its corners have posts with lanterns, and the deed is in your pack. "
            + "Next: build on it (Build on it, on the Your land tab), or rates double after " + VACANT_DAYS + " days empty.";
    }

    /** The lots of a title held off the town's builders (again after a restart: Villages keeps that in memory). */
    static void hold(UUID village, Title t) {
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        for (long k : t.cells()) {
            TownPlan.Lot l = lots().get(k);
            if (l == null) continue;
            Villages.holdLot(village, v.centre().offset(l.x(), 0, l.z()), 0, 0, Direction.NORTH);
        }
    }

    static void free(UUID village, Title t) {
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        for (long k : t.cells()) {
            TownPlan.Lot l = lots().get(k);
            if (l == null) continue;
            Villages.freeLot(village, v.centre().offset(l.x(), 0, l.z()), 0, 0, Direction.NORTH);
        }
    }

    /** The corner posts: a fence post with a lantern on it at each corner (set, or taken up). */
    static void posts(ServerLevel level, CompoundTag t, boolean set) {
        int[] b = t.getIntArray("box");
        if (b.length < 4) return;
        if (set) {
            ListTag at = new ListTag();
            int[][] cs = { { b[0], b[1] }, { b[2], b[1] }, { b[0], b[3] }, { b[2], b[3] } };
            for (int[] k : cs) {
                if (!level.isLoaded(new BlockPos(k[0], 64, k[1]))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, k[0], k[1]);
                BlockPos post = new BlockPos(k[0], y, k[1]);
                if (!level.getBlockState(post).canBeReplaced() || !level.getBlockState(post.above()).canBeReplaced()) continue;
                if (!level.getBlockState(post.below()).isFaceSturdy(level, post.below(), Direction.UP)) continue;
                level.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
                level.setBlockAndUpdate(post.above(), Blocks.LANTERN.defaultBlockState());
                at.add(net.minecraft.nbt.LongTag.valueOf(post.asLong()));
            }
            t.put("posts", at);
        } else {
            for (Tag e : t.getList("posts", Tag.TAG_LONG)) {
                BlockPos post = BlockPos.of(((net.minecraft.nbt.LongTag) e).getAsLong());
                if (!level.isLoaded(post)) continue;
                if (level.getBlockState(post.above()).is(Blocks.LANTERN)) level.setBlockAndUpdate(post.above(), Blocks.AIR.defaultBlockState());
                if (level.getBlockState(post).is(Blocks.OAK_FENCE)) level.setBlockAndUpdate(post, Blocks.AIR.defaultBlockState());
            }
            t.remove("posts");
        }
    }

    /** Two of a player's titles side by side made one (the town not short of houses). */
    static String merge(ServerLevel level, ServerPlayer p, UUID village, String a, String b) {
        Title x = get(village, a), y = get(village, b);
        if (x == null || y == null || !x.owner().equals(p.getUUID()) || !y.owner().equals(p.getUUID())) return "!Both must be yours.";
        if (x.kind() == Kind.FIELD || y.kind() == Kind.FIELD) return "!A smallholding isn't merged with building land.";
        if (pressure(village)) return "!Not while households wait for a roof: the town won't merge lots under housing pressure. Try again once they're housed.";
        int[] bx = x.box(), by = y.box();
        int[] u = { Math.min(bx[0], by[0]), Math.min(bx[1], by[1]), Math.max(bx[2], by[2]), Math.max(bx[3], by[3]) };
        int area = (u[2] - u[0] + 1) * (u[3] - u[1] + 1);
        boolean meet = bx[0] <= by[2] + TownPlan.STREET + 1 && by[0] <= bx[2] + TownPlan.STREET + 1 && bx[1] <= by[3] + TownPlan.STREET + 1 && by[1] <= bx[3] + TownPlan.STREET + 1;
        if (!meet || area > x.area() + y.area() + 2 * TownPlan.LOT) return "!Those two don't lie side by side: only neighbouring titles merge into one plot.";
        int cells = x.cells().length + y.cells().length;
        CompoundTag t = raw(village, a);
        t.putIntArray("box", u);
        long[] keys = new long[cells];
        System.arraycopy(x.cells(), 0, keys, 0, x.cells().length);
        System.arraycopy(y.cells(), 0, keys, x.cells().length, y.cells().length);
        t.put("cells", new LongArrayTag(keys));
        t.putString("kind", cells >= 4 ? Kind.BLOCK.name() : x.kind() == Kind.LONG || y.kind() == Kind.LONG ? Kind.LONG.name() : Kind.DOUBLE.name());
        t.putInt("price", x.price() + y.price());
        t.putBoolean("built", x.built() || y.built());
        t.putString("name", Kind.of(t.getString("kind")).title + " " + PropertyMarket.where(village, view(a, t).centre(64)));
        CompoundTag old = raw(village, b);
        posts(level, old, false);
        posts(level, t, false);
        posts(level, t, true);
        book(village).remove(b);
        PropertyBook.dirty();
        PropertyItems.deed(p, village, "land", a, t.getString("name"));
        return "Merged: one title now, " + t.getString("name").toLowerCase(Locale.ROOT) + " (" + (u[2] - u[0] + 1) + " by " + (u[3] - u[1] + 1)
            + "). A new deed is in your pack; the old ones are void. Bigger ground takes the villa and the manor.";
    }

    /** The town buys it back: four fifths of what was paid, into the pack (or the account). */
    static String sellBack(ServerLevel level, ServerPlayer p, UUID village, String id, boolean sure, PlayerLife.Ctx c) {
        Title t = get(village, id);
        if (t == null || !t.owner().equals(p.getUUID())) return "!That isn't yours.";
        if (t.built()) return "!Something stands on it: sell the building and its land together at the property office.";
        int back = t.price() * BUY_BACK / 100;
        if (!sure) return PropertyBook.ask(c, "sellback", LifePage.args("id", id), "Sell " + t.name().toLowerCase(Locale.ROOT) + " back to the town for "
            + PropertyBook.coins(back) + " (" + BUY_BACK + "% of the " + t.price() + " you paid)? The posts come up and the title is gone.", "Sell it back");
        int paid = Ledger.takeCoins(village, back);
        if (paid < back) {
            if (paid > 0) Ledger.addCoins(village, paid);
            return "!The treasury hasn't " + PropertyBook.coins(back) + " just now. Nothing changed; try again after the town's takings.";
        }
        release(level, village, id);
        PropertyBook.give(level, village, p.getUUID(), Belonging.called(p), back, PlayerFinance.Cat.CASHED, "land sold back to the town");
        return "Sold back to the town for " + PropertyBook.coins(back) + ". The posts are up and the lots are the town's again.";
    }

    /** A title gone (sold back, repossessed): its posts up, its lots the town's again. */
    static void release(ServerLevel level, UUID village, String id) {
        CompoundTag t = raw(village, id);
        if (t == null) return;
        Title v = view(id, t);
        posts(level, t, false);
        free(village, v);
        book(village).remove(id);
        PropertyBook.dirty();
    }

    /** A title passed on whole (a will, an estate, a firm sold): the holder changed, the deed re-issued if it is here. */
    static void transfer(ServerLevel level, UUID village, String id, UUID to, String name) {
        CompoundTag t = raw(village, id);
        if (t == null) return;
        t.putUUID("owner", to);
        t.putString("ownerName", name);
        t.putLong("since", PropertyBook.day(level));
        PropertyBook.dirty();
        ServerPlayer p = PropertyBook.online(level, to);
        if (p != null) PropertyItems.deed(p, village, "land", id, t.getString("name"));
    }

    /** A building ordered on it (BuildPicker), or a farm registered: it isn't vacant. */
    static void markUsed(UUID village, int x, int z, boolean farm) {
        Title t = at(village, x, z);
        if (t == null) return;
        CompoundTag r = raw(village, t.id());
        if (r == null) return;
        r.putBoolean(farm ? "farmed" : "built", true);
        PropertyBook.dirty();
    }

    // ------------------------------------------------------------------ the morning

    private static final java.util.Set<UUID> HELD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Every few seconds: the lots held off the builders once after a restart. */
    static void tick(ServerLevel level, Villages.Village v) {
        if (!HELD.add(v.id())) return;
        for (Title t : all(v.id())) hold(v.id(), t);
    }

    /** Each morning: a week's rates on each title that has come due, out of the account (and pack); unpaid, a debt. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (Title t : all(id)) {
            CompoundTag r = raw(id, t.id());
            if (r == null || r.getLong("paidTo") > day) continue;
            int due = rates(level, id, t);
            r.putLong("paidTo", r.getLong("paidTo") + 7);
            StandingOrders.bill(level, id, t.owner(), t.ownerName(), due, PlayerFinance.Cat.TAXES, "land rates on " + t.name().toLowerCase(Locale.ROOT)
                + (vacant(level, id, t) ? " (doubled: empty " + VACANT_DAYS + " days)" : ""), "rates", "the town", "land:" + t.id());
            PropertyBook.dirty();
        }
    }

    /** Tests: forget that the lots were held (as after a restart). */
    public static void resetForTests() {
        HELD.clear();
    }

    // ------------------------------------------------------------------ the page ("land")

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) {
            LifePage page = new LifePage("land", "Land", "No town near", p.registryAccess());
            page.text("Land is bought from a town. Walk into one and open this again.", 4);
            return page;
        }
        UUID id = v.id(), me = p.getUUID();
        List<Title> mine = of(id, me);
        LifePage page = new LifePage("land", "Land in " + Villages.name(id), mine.isEmpty() ? "Buy a lot of the town's plan, a bigger plot, or a smallholding"
            : "You hold " + mine.size() + (mine.size() == 1 ? " title" : " titles") + " here", p.registryAccess());
        if (c.tab.isEmpty()) c.tab = mine.isEmpty() ? "buy" : "mine";
        page.tab("buy", "Buy land", c.tab.equals("buy")).tab("mine", "Your land", c.tab.equals("mine")).tab("rules", "How it works", c.tab.equals("rules"));
        PropertyBook.confirmBox(page, c);
        switch (c.tab) {
            case "mine" -> mineTab(level, p, v, c, page, mine);
            case "rules" -> rulesTab(level, p, v, page);
            default -> buyTab(level, p, v, c, page);
        }
        page.buttons().button("Back to your life in town", "life", null).button("The builder", "builder", null);
        return page;
    }

    void buyTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        UUID id = v.id(), me = p.getUUID();
        BlockPos heart = v.centre();
        PropertyBook.hint(p, page, "land.buy", "Land comes in the town's own sizes: a lot, two side by side, a long lot, a whole block, or a smallholding out past "
            + "the town for a farm. Pick a size, then click a green lot on the map: its outline appears on the ground.");
        Kind kind = Kind.of(c.args.getString("kind").isEmpty() ? "LOT" : c.args.getString("kind"));
        String[] kv = new String[Kind.values().length], kl = new String[kv.length];
        for (int i = 0; i < kv.length; i++) { kv[i] = Kind.values()[i].name(); kl[i] = Kind.values()[i].title; }
        page.choice("kind", "Size", kv, kl, kind.name());
        page.buttons().button("Show this size", "size", null);
        page.text(kind.title + ": " + kind.words + ".", 4);
        // the map: every lot laid out, coloured by what it is; a smallholding's candidates out past the growth line
        boolean hasSel = c.args.contains("ox");
        int sx = c.args.getInt("ox"), sz = c.args.getInt("oz");
        Parcel sel = hasSel && kind != Kind.FIELD ? parcel(kind, sx, sz) : null;
        java.util.Set<Long> selKeys = new java.util.HashSet<>();
        if (sel != null) for (TownPlan.Lot l : sel.lots()) selKeys.add(l.key());
        LifePage.LotMap map = page.lotMap("The town's plan: click a free lot (" + kind.title.toLowerCase(Locale.ROOT) + " round it is chosen)", heart.getX(), heart.getZ());
        map.mark(heart.getX(), heart.getZ(), 1, "The square");
        map.mark(p.getBlockX(), p.getBlockZ(), 2, "You");
        int rings = Math.min(CityGrowth.rings(id), Math.max(2, LandUse.builtRings(id) + 1));
        for (TownPlan.Lot l : lots().values()) {
            if (TownPlan.ringOf(l) >= rings) continue;
            int[] b = TownPlan.box(l);
            int st = state(id, heart, l, me);
            String why = st == 0 ? "" : lotProblem(id, heart, l);
            boolean click = kind != Kind.FIELD && (st == 0);
            map.lot(heart.getX() + b[0], heart.getZ() + b[1], heart.getX() + b[2], heart.getZ() + b[3], st, selKeys.contains(l.key()),
                (st == 0 ? "Free lot " : "Lot ") + PropertyMarket.where(id, heart.offset(l.x(), 0, l.z())) + (why.isEmpty() ? "" : ": " + why),
                click ? "pick" : null, click ? LifePage.args("ox", l.x(), "oz", l.z()) : null);
        }
        List<int[]> fields = kind == Kind.FIELD ? fields(level, v) : List.of();
        for (int[] f : fields) {
            boolean on = hasSel && f[0] == sx && f[1] == sz;
            map.lot(heart.getX() + f[0] - FIELD, heart.getZ() + f[1] - FIELD, heart.getX() + f[0] + FIELD, heart.getZ() + f[1] + FIELD, 0, on,
                "A smallholding " + PropertyMarket.where(id, heart.offset(f[0], 0, f[1])), "pick", LifePage.args("ox", f[0], "oz", f[1]));
        }
        for (Title t : all(id)) if (t.kind() == Kind.FIELD) {
            int[] b = t.box();
            map.lot(b[0], b[1], b[2], b[3], t.owner().equals(me) ? 4 : 5, false, t.ownerName() + "'s smallholding", null, null);
        }
        // the choice, its outline on the ground, its price and why not
        int[] box = null;
        String bad = "";
        if (kind == Kind.FIELD) {
            if (fields.isEmpty()) page.text("No smallholding to be had: the ground past the town's growth line is farmland, mine, water or taken (or not loaded: walk out that way).", 2);
            if (hasSel) {
                bad = fieldProblem(level, v, sx, sz);
                box = new int[]{ sx - FIELD, sz - FIELD, sx + FIELD, sz + FIELD };
            }
        } else if (hasSel) {
            if (sel == null) bad = "the plan has no " + kind.title.toLowerCase(Locale.ROOT) + " round that lot: try another lot or size";
            else {
                box = sel.box();
                for (TownPlan.Lot l : sel.lots()) {
                    String w = lotProblem(id, heart, l);
                    if (!w.isEmpty()) { bad = "one of its lots: " + w; break; }
                }
            }
        }
        if (box == null) {
            page.text(hasSel ? "✘ " + bad + "." : "Click a green lot on the map to choose it.", hasSel ? 3 : 4);
            page.blockGhost(null);
            return;
        }
        int w = box[2] - box[0] + 1, d = box[3] - box[1] + 1;
        BlockPos mid = heart.offset((box[0] + box[2]) / 2, 0, (box[1] + box[3]) / 2);
        int y = level.isLoaded(mid) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mid.getX(), mid.getZ()) : heart.getY();
        CompoundTag g = new CompoundTag();
        g.putIntArray("lot", new int[]{ heart.getX() + box[0], heart.getZ() + box[1], heart.getX() + box[2], heart.getZ() + box[3], y });
        g.putBoolean("ok", bad.isEmpty());
        g.putIntArray("cells", new int[0]);
        page.blockGhost(g);
        int[] pr = price(id, heart, kind, box);
        String standing = standingProblem(level, id, p, kind);
        page.heading(kind.title + " " + PropertyMarket.where(id, mid));
        page.row(new ItemStack(Items.OAK_FENCE), w + " by " + d + " blocks" + (kind.lots > 0 ? ", " + kind.lots + (kind.lots == 1 ? " lot" : " lots") : ", a field"),
            bad.isEmpty() ? "✔ Free to buy. Its outline is on the ground now; walk over and look." : "✘ " + bad, PropertyBook.coins(pr[0]));
        page.text("The ground: " + pr[1] + "c (the builders' price for land here, by area)" + (pr[2] > 0 ? "; assembling " + kind.lots + " lots: +" + pr[2] + "c" : "")
            + (pr[3] > 0 ? "; households are waiting for houses: +" + pr[3] + "c" : "") + ". Rates about " + PropertyBook.coins(Math.max(1, pr[0] * RATE_PCT / 100))
            + " a week (double once empty " + VACANT_DAYS + " days).", 4);
        int have = Market.coinsHeld(p) + PropertyBook.account(id, me);
        String why = !bad.isEmpty() ? "Not that ground: " + bad : !standing.isEmpty() ? standing : have < pr[0] ? "You have " + have + " of " + pr[0] + " (pack and account)" : "";
        page.buttons().button("Buy it (" + PropertyBook.coins(pr[0]) + ")", "buy", LifePage.args("kind", kind.name(), "ox", sx, "oz", sz), why.isEmpty(), why).primary()
            .button("Show me", "showsel", null);
        if (!standing.isEmpty()) page.text("You can't buy land yet: " + standing + ".", 2);
    }

    void mineTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, List<Title> mine) {
        UUID id = v.id();
        PropertyBook.hint(p, page, "land.mine", "Your land pays rates each week from your account (or pack). Build on it from here: the builder opens on your own plot. "
            + "Two titles side by side merge into one bigger plot.");
        if (mine.isEmpty()) {
            page.text("You hold no land here yet. The Buy land tab has the town's plan.", 4);
            page.buttons().button("Buy land", "tab", LifePage.args("tab", "buy")).primary();
            page.blockGhost(null);
            return;
        }
        if (c.args.contains("outline")) page.blockGhost(c.args.getCompound("outline"));
        long day = PropertyBook.day(level);
        for (Title t : mine) {
            int[] b = t.box();
            boolean empty = vacant(level, id, t);
            long left = VACANT_DAYS - (day - t.since());
            String state = t.built() ? "built on" : t.kind() == Kind.FIELD && farmed(id, t) ? "farmed" : empty ? "empty: rates doubled" : "empty, rates double in " + Math.max(0, left) + " days";
            CompoundTag r = raw(id, t.id());
            page.row(new ItemStack(t.kind() == Kind.FIELD ? Items.WHEAT : Items.OAK_FENCE), t.name(), (b[2] - b[0] + 1) + " by " + (b[3] - b[1] + 1) + "; " + state
                    + "; rates " + PropertyBook.coins(rates(level, id, t)) + " a week, next on day " + (r == null ? day : r.getLong("paidTo")), "paid " + t.price() + "c")
                .button("Build on it", "buildon", LifePage.args("id", t.id()), !t.built() && t.kind() != Kind.FIELD, t.built() ? "Something is built on it already" : "A smallholding is for a farm")
                .button("Show me", "show", LifePage.args("id", t.id()))
                .button("Sell back", "sellback", LifePage.args("id", t.id()), !t.built(), "Sell the building with its land at the property office");
        }
        if (mine.size() >= 2) {
            page.heading("Merge two plots");
            boolean pressed = pressure(id);
            for (int i = 0; i < mine.size(); i++) for (int j = i + 1; j < mine.size(); j++) {
                Title a = mine.get(i), b = mine.get(j);
                if (a.kind() == Kind.FIELD || b.kind() == Kind.FIELD) continue;
                page.row(null, a.name() + " + " + b.name(), "One title, one plot: room for a villa or a manor", "")
                    .button("Merge", "merge", LifePage.args("a", a.id(), "b", b.id()), !pressed, "Households are waiting for houses: the town won't merge lots now");
            }
        }
        page.buttons().button("A copy of my deeds", "deeds", null);
    }

    void rulesTab(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page) {
        UUID id = v.id();
        int cap = Math.max(1, homeLots(id) * PLAYERS_SHARE / 100);
        page.heading("Buying land");
        page.text("Sizes follow the town's own plan: a lot is one of its eleven-block house lots on a street; bigger plots are two or four of them; a smallholding is a field the size of the folk's.", 0);
        page.text("A friend of the town may hold one lot; a citizen up to " + MOST_LOTS + ", and one smallholding. Players together may hold " + PLAYERS_SHARE + "% of the house lots: "
            + playersLots(id) + " of " + cap + " here now.", 0);
        page.text("Never for sale: what the town has built or queued, its farmland and the mine, the lots kept for its hall, trades and towers, and the ring of the plan kept clear for it to grow into ("
            + LandUse.growthReach(id) + " blocks out now). A smallholding lies past that line.", 0);
        page.heading("Owning it");
        page.text("Rates each week: " + RATE_PCT + "% of what you paid (a citizen a quarter less), doubled once it has stood empty " + VACANT_DAYS + " days. They come out of your account, "
            + "or your pack if you're here. Unpaid rates go on your debts.", 0);
        page.text("The town buys land back at " + BUY_BACK + "% of the price. Two plots side by side merge into one, unless households are waiting for houses.", 0);
        page.text(pressure(id) ? "Households are waiting for a roof just now: land costs a quarter more and nothing merges." : "No households are waiting for a roof just now.", pressure(id) ? 2 : 1);
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        String common = PropertyBook.common(p, c, action, args, this);
        if (common != null) return common;
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town near.";
        UUID id = v.id();
        CompoundTag f = args.getCompound("f");
        if (f.contains("kind") && !action.equals("buy")) {
            String was = c.args.getString("kind");
            c.args.putString("kind", f.getString("kind"));
            if (!f.getString("kind").equals(was)) { c.args.remove("ox"); c.args.remove("oz"); }
        }
        switch (action) {
            case "size" -> { return ""; }
            case "pick" -> {
                c.args.putInt("ox", args.getInt("ox"));
                c.args.putInt("oz", args.getInt("oz"));
                return "";
            }
            case "showsel" -> {
                if (!c.args.contains("ox")) return "!Choose a lot first.";
                return PropertyBook.showMe(p, v.centre().offset(c.args.getInt("ox"), 0, c.args.getInt("oz")), "The plot");
            }
            case "buy" -> {
                Kind k = Kind.of(args.getString("kind"));
                String said = buy(level, p, v, k, args.getInt("ox"), args.getInt("oz"), args.getBoolean("sure"), c);
                if (!said.startsWith("!") && !said.isEmpty()) { c.tab = "mine"; c.args.remove("ox"); }
                return said;
            }
            case "show" -> {
                Title t = get(id, args.getString("id"));
                if (t == null) return "!That title isn't on the register.";
                CompoundTag g = new CompoundTag();
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (t.box()[0] + t.box()[2]) / 2, (t.box()[1] + t.box()[3]) / 2);
                g.putIntArray("lot", new int[]{ t.box()[0], t.box()[1], t.box()[2], t.box()[3], y });
                g.putBoolean("ok", true);
                c.args.put("outline", g);
                return PropertyBook.showMe(p, t.centre(y), t.name());
            }
            case "merge" -> { return merge(level, p, id, args.getString("a"), args.getString("b")); }
            case "sellback" -> { return sellBack(level, p, id, args.getString("id"), args.getBoolean("sure"), c); }
            case "buildon" -> {
                Title t = get(id, args.getString("id"));
                if (t == null) return "!That title isn't on the register.";
                PlayerLife.open(p, "build", LifePage.args("for", "home", "site", t.id(), "back", "land"));
                return "#open:";
            }
            case "builder" -> { PlayerLife.open(p, "build", LifePage.args("for", "home", "back", "land")); return "#open:"; }
            case "deeds" -> {
                int n = 0;
                for (Title t : of(id, p.getUUID())) { PropertyItems.deed(p, id, "land", t.id(), t.name()); n++; }
                return n == 0 ? "!You hold no land here." : "A fresh copy of " + n + (n == 1 ? " deed" : " deeds") + " is in your pack. The register is what counts; the deed is its copy.";
            }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            default -> { return null; }
        }
    }

    /** Tests: buy without the question. */
    public static String buyForTests(ServerPlayer p, String kind, int ox, int oz) {
        Villages.Village v = PlayerLife.here(p);
        PlayerLife.open(p, "land", null);
        return v == null ? "!no town" : buy(p.serverLevel(), p, v, Kind.of(kind), ox, oz, true, PlayerLife.ctx(p));
    }

    /** Tests: the parcel of a size round a lot, its box in offsets (null if none). */
    @Nullable
    public static int[] parcelForTests(String kind, int ox, int oz) {
        Parcel pc = parcel(Kind.of(kind), ox, oz);
        return pc == null ? null : pc.box();
    }

    /** Tests: a lot a player may buy here, nearest the square (offsets), or null. */
    @Nullable
    public static int[] freeLotForTests(UUID village, String kind) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.kind() != TownPlan.Kind.LOT || !lotProblem(village, v.centre(), l).isEmpty()) continue;
            Parcel pc = parcel(Kind.of(kind), l.x(), l.z());
            if (pc == null) continue;
            boolean ok = true;
            for (TownPlan.Lot x : pc.lots()) if (!lotProblem(village, v.centre(), x).isEmpty()) ok = false;
            if (ok) return new int[]{ l.x(), l.z() };
        }
        return null;
    }

    /** Tests: why a lot may not be bought ("" if it may). */
    public static String lotProblemForTests(UUID village, int ox, int oz) {
        Villages.Village v = Villages.get(village);
        TownPlan.Lot l = lotAt(ox, oz);
        return v == null || l == null ? "no lot" : lotProblem(village, v.centre(), l);
    }

    public static int ratesForTests(ServerLevel level, UUID village, String id) {
        Title t = get(village, id);
        return t == null ? -1 : rates(level, village, t);
    }

    public static void ageForTests(UUID village, String id, long days) {
        CompoundTag r = raw(village, id);
        if (r != null) { r.putLong("since", r.getLong("since") - days); r.putLong("paidTo", r.getLong("paidTo") - days); }
    }
}
