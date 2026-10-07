package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The housing market: the council's houses and what they cost, the houses folk have built for
 * themselves, and what a house fetches and lets for as homes grow scarce or stand empty. [econ-housing]
 *
 * <ul>
 * <li><b>The council's houses.</b> Every house the village builds is the council's: let first, and sold
 *     to the tenants who save for it (Homes), as it always was. Its builders' time and the stores' blocks
 *     were never counted; now each is costed the day it is first on the books, its every block at the
 *     town's price that day (PriceIndex) and its builders' hours at a craftsman's rate, and the cost is
 *     written against the treasury. What a house cost to build is what it is worth: the market prices it
 *     from that.</li>
 * <li><b>A folk's own house.</b> Past the Wood Age, in a town of sixteen, a household that has done well
 *     can have a house built for itself: the well-off, a family outgrowing a rented cottage, a Visionary
 *     that wants a house to its own drawing, a Merchant that wants a front to show, a couple newly wed.
 *     It chooses among four drawings by its taste and its purse (a timber cottage; a family house of two
 *     storeys in dressed stone; a town house under slate; a brick villa of its own drawing), and a lot of
 *     the town's plan where homes may go (Villages.siteFor, the homes' quarter first). The bill is every
 *     block of the drawing at the town's price, the builders' hours, the plot (dearer by the park and near
 *     the square, cheaper in the crafts' smoke and out at the edge, and as the market stands), the beds
 *     and rugs it wants, and the council's permit. It commissions only if it can pay all of it: its
 *     purses (a dozen coins a head kept back to live on), what it had put by toward its rented house, its
 *     savings at the bank, and the bank's mortgage for the rest with a fifth down, if its wages carry the
 *     payment over what it eats and its rent. Otherwise it keeps saving, and says so.</li>
 * <li><b>Paying, and building.</b> The plot and the permit go to the treasury the day it is agreed; the
 *     rest is held for the build. The town's hands raise it a course at a time (TownJobs: a hand its own
 *     trade can spare, so its hours are its own and the household pays it for them, into its purse, as
 *     it lays each course). Each course's blocks come out of the stores there and then (Masonry: made of
 *     their makings if need be) and are paid for into the treasury; if the stores run short, the build
 *     waits for them. The town's own buildings come first, unless the town is thriving.</li>
 * <li><b>Moving in.</b> When it stands the household owns it outright (or on the bank's mortgage) and moves
 *     in; a house it owned before is sold at the going price to a household that can buy it, or back to
 *     the council at four-fifths of it; a rented one goes back to the council to let.</li>
 * <li><b>Supply and demand.</b> Each morning the market weighs the households waiting for a home, those
 *     outgrowing theirs and the tenants saving to buy, against the houses and flats standing empty: a
 *     housing index that moves house prices, rents and plots up to about five in the hundred a week, between
 *     six tenths and nearly twice what a house cost to build. Rents rise when homes are scarce and fall when
 *     they stand empty. Every sale is kept.</li>
 * </ul>
 * All of it is in the ledger's notes (under "hm."), beside the rest of the village's books.
 */
public final class HousingMarket {

    private HousingMarket() {}

    // ------------------------------------------------------------------ the numbers

    /** A day's move of the index at full pressure: about five in the hundred a week. */
    static final double STEP = 0.0075;
    /** The index never falls below, or rises past, these: a house never sells for under six tenths of what it cost. */
    static final double LOW = 0.6, HIGH = 1.8;
    /** A builder lays twelve blocks an hour, by hand: each one fetched, measured and set. */
    static final int BLOCKS_AN_HOUR = 12;
    /** A builder's day, in hours, for its hourly rate. */
    static final int HOURS_A_DAY = 10;
    /** The council's permit: a twentieth of the building's cost, at least two coins. */
    static final int PERMIT_PERCENT = 5, PERMIT_LEAST = 2;
    /** A plot's price in a Wood Age hamlet near the square, before the market and the quarter. */
    static final int PLOT_BASE = 8;
    /** A house nobody else can buy, the council buys back at this part of the going price, in the hundred. */
    static final int COUNCIL_PERCENT = 80;
    /** A town lets its folk build their own once it is past the Wood Age and this big. */
    static final int FROM_FOLK = 16;
    /** How much a work visit lays: so many blocks, a course at a time. */
    static final int BUDGET = 24;
    /** The town's work the builders of a folk's house are called to (TownJobs). */
    static final String WORKS = "homebuild";
    /** How many days of the index are kept for its trend. */
    static final int HISTORY = 14;

    private static final String INDEX = "hm.index", MARKET = "hm.market", BUILD = "hm.build", SALES = "hm.sales", DONE = "hm.done",
        COST = "hm.cost/", OWN = "hm.own/", PLAN = "hm.plan/", BOOK = "hm.book";

    /**
     * A house a folk can have built: its drawing, what it is recorded as once it stands, whether it is the
     * grown (two-storey) house, its beds, and the look it is built in (Masonry chooses what the stores can
     * pay for, the palette's first).
     */
    public enum Design {
        COTTAGE("cottage", "a cottage", "house", "house", false, Showcase.OAK, "timber on a stone footing, under a pitched roof"),
        FAMILY("family house", "a family house", "house2", "house", true, Grow.STONE, "two storeys of dressed stone under a shingled roof"),
        TOWN("town house", "a town house", "house2", "house", true, Ages.CIVIC, "two storeys of dressed stone under slate"),
        VILLA("villa", "a villa", "villa", "villa", false, Grow.BRICK, "detached, of brick, under a hipped slate roof, with a porch");

        public final String word, a, drawing, structure, looks;
        final boolean grown;
        final Showcase.Palette palette;

        Design(String word, String a, String drawing, String structure, boolean grown, Showcase.Palette palette, String looks) {
            this.word = word;
            this.a = a;
            this.drawing = drawing;
            this.structure = structure;
            this.grown = grown;
            this.palette = palette;
            this.looks = looks;
        }

        /** The name its lot is held under among the town's sites (Villages.siteFor): its own drawing's
         *  ground. A cottage stands on a house's ground, the same as a grown house's. */
        String siteKey() {
            return this == VILLA ? "villa" : "house2";
        }

        int beds() {
            int n = 0;
            for (Blueprints.Cell c : Blueprints.cells(drawing)) if (c.key().part() == BuildGoal.Part.BED) n++;
            return n;
        }
    }

    // ------------------------------------------------------------------ the index

    /** What the housing market stands at in this town: 1 is what houses cost to build. */
    public static double index(@Nullable UUID village) {
        if (village == null) return 1.0;
        String s = Ledger.note(village, INDEX);
        if (s == null || s.isEmpty()) return 1.0;
        try {
            return clamp(Double.parseDouble(s.split("\\|", -1)[0]));
        } catch (RuntimeException e) {
            return 1.0;
        }
    }

    private static double clamp(double d) {
        return Math.max(LOW, Math.min(HIGH, d));
    }

    /** The last fortnight of the index, oldest first (in thousandths). */
    static int[] history(UUID village) {
        String s = Ledger.note(village, INDEX);
        if (s == null || s.isEmpty()) return new int[0];
        String[] p = s.split("\\|", -1);
        if (p.length < 3 || p[2].isEmpty()) return new int[0];
        String[] h = p[2].split(",");
        int[] out = new int[h.length];
        for (int i = 0; i < h.length; i++) out[i] = Homes.parse(h[i]);
        return out;
    }

    /** How far the index has moved in the last week, in the hundred (0 with no week behind it). */
    public static int weekChange(UUID village) {
        int[] h = history(village);
        if (h.length < 2) return 0;
        int then = h[Math.max(0, h.length - 8)], now = (int) Math.round(index(village) * 1000);
        return then <= 0 ? 0 : (int) Math.round((now - then) * 100.0 / then);
    }

    private static void setIndex(UUID village, double index, long day) {
        int[] h = history(village);
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, h.length - (HISTORY - 1));
        for (int i = from; i < h.length; i++) sb.append(sb.length() == 0 ? "" : ",").append(h[i]);
        sb.append(sb.length() == 0 ? "" : ",").append(Math.round(index * 1000));
        Ledger.note(village, INDEX, String.format(Locale.ROOT, "%.4f", index) + "|" + day + "|" + sb);
    }

    private static long indexDay(UUID village) {
        String s = Ledger.note(village, INDEX);
        if (s == null || s.isEmpty()) return -1;
        String[] p = s.split("\\|", -1);
        try {
            return p.length > 1 ? Long.parseLong(p[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** What the market weighed this morning: {households wanting a home, outgrowing theirs, tenants saving to buy,
     *  houses and flats empty, houses going up, demand x10, supply x10}. */
    static int[] market(UUID village) {
        String s = Ledger.note(village, MARKET);
        int[] out = new int[7];
        if (s == null || s.isEmpty()) return out;
        String[] p = s.split("\\|", -1);
        for (int i = 0; i < out.length && i < p.length; i++) out[i] = Homes.parse(p[i]);
        return out;
    }

    /**
     * The morning's reckoning: the households wanting a home (the waiting list: those with no house, the
     * grown children at home, the newcomers), those outgrowing theirs (more of them than beds) and, lightly,
     * the tenants saving to buy, against the houses and flats standing empty and the houses going up. The
     * index moves by what is short or over, as a share of the households, up to three quarters of a coin
     * in the hundred a day; with the two about even it drifts back toward what a house costs to build.
     */
    static void reckon(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (indexDay(id) >= day) return;
        int[] c = Homes.counts(level, id);
        int waiting = c[1], saving = c[8], empty = c[6], households = c[0] + c[1];
        int outgrowing = 0;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (h.members.isEmpty() || Homes.seat(h) || !level.isLoaded(h.anchor)) continue;
            if (h.members.size() > Math.max(1, Homes.bedsIn(level, id, h).size())) outgrowing++;
        }
        int going = load(id) == null ? 0 : 1;
        double demand = waiting + outgrowing + 0.25 * saving;
        double supply = empty + 0.5 * going;
        double pressure = Math.max(-1.0, Math.min(1.0, (demand - supply) / Math.max(2.0, households / 4.0)));
        double index = index(id);
        double next = Math.abs(pressure) < 0.1 ? index + (1.0 - index) * 0.02 : index * (1.0 + STEP * pressure);
        setIndex(id, clamp(next), day);
        Ledger.note(id, MARKET, waiting + "|" + outgrowing + "|" + saving + "|" + empty + "|" + going + "|"
            + Math.round(demand * 10) + "|" + Math.round(supply * 10));
    }

    /** A day's rent as the market stands: the house's rent before the market times the index, never under a coin. */
    public static int rent(@Nullable UUID village, int base) {
        double i = index(village);
        if (i == 1.0) return base;
        return Math.max(1, (int) Math.round(base * i));
    }

    // ------------------------------------------------------------------ what a house is worth

    /** What a house cost to build: its blocks at the town's prices then, its builders' hours, and (one a folk had built) its furnishing. */
    record Cost(String drawing, int age, int blocks, int labour, int furnish, long day, int book, boolean custom) {
        int worth() { return blocks + labour + furnish; }

        String encode() {
            return drawing + "|" + age + "|" + blocks + "|" + labour + "|" + furnish + "|" + day + "|" + book + "|" + (custom ? 1 : 0);
        }

        @Nullable
        static Cost decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            if (p.length < 8) return null;
            return new Cost(p[0], Homes.parse(p[1]), Homes.parse(p[2]), Homes.parse(p[3]), Homes.parse(p[4]), Homes.parse(p[5]),
                Homes.parse(p[6]), "1".equals(p[7]));
        }
    }

    /** What each drawing costs to build in each town today, in its age's materials: {blocks, labour}, and the day reckoned. */
    private static final Map<String, int[]> REPLACE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        REPLACE.clear();
        TICKED.clear();
        OPEN_FOR_TESTS = null;
    }

    /** The look the council builds its houses in, by the age: timber first, then stone, then brick. */
    static Showcase.Palette councilPalette(UUID village) {
        Villages.Age age = Villages.ageOf(village);
        return age.ordinal() >= Villages.Age.STONE.ordinal() ? Grow.palette(age) : Showcase.OAK;
    }

    /** {blocks, labour} to build this drawing in the council's look today, reckoned at most once a day. */
    static int[] replacement(ServerLevel level, UUID village, String drawing) {
        long day = level.getDayTime() / 24000L;
        String key = village + "|" + drawing + "|" + Villages.ageOf(village).ordinal();
        int[] have = REPLACE.get(key);
        if (have != null && have[2] == day) return have;
        Map<Item, Integer> items = new LinkedHashMap<>();
        java.util.function.Function<BuildGoal.Placement, BlockState> paint = Showcase.painter(councilPalette(village));
        int cells = 0;
        for (BuildGoal.Placement p : BuildGoal.plan(drawing, BlockPos.ZERO.above(64), Direction.NORTH, 13)) {
            // The house, not its furniture: the beds and rugs are the household's, furnished out of the stores as it needs them.
            if (p.part() == BuildGoal.Part.CLEAR || p.part() == BuildGoal.Part.WATER || furnishing(p.part())) continue;
            BlockState st = paint.apply(p);
            if (st == null || st.isAir()) continue;
            items.merge(st.getBlock().asItem(), 1, Integer::sum);
            cells++;
        }
        double blocks = 0;
        for (Map.Entry<Item, Integer> e : items.entrySet()) blocks += PriceIndex.each(level, village, e.getKey()) * e.getValue();
        int[] out = { (int) Math.round(blocks), labour(village, cells), (int) day };
        REPLACE.put(key, out);
        return out;
    }

    /**
     * What a house is worth: what it cost to build. One a folk had built, what it paid for its blocks, its
     * builders and its furnishing; one of the council's, its cost when it was costed, again when it has been
     * raised a storey or the town has come into a new age (its houses refaced in the age's stone and brick);
     * failing that, what such a house costs to build today; and before anything has been reckoned, the old
     * flat figure (35 a house, 55 a two-storey house, 120 a manor, a quarter more an age).
     */
    static double worth(UUID village, Homes.Home h) {
        return worthBare(village, h) * WindowBoxes.premium(village, h.anchor);   // [workitems] its window boxes in flower
    }

    private static double worthBare(UUID village, Homes.Home h) {
        // A flat is never sold: what a household in one saves toward is a house's price (Flats).
        boolean flat = Flats.isFlat(h);
        String drawing = flat ? "house" : Homes.drawing(village, h);
        int age = Villages.ageOf(village).ordinal();
        if (!flat && !h.anchor.equals(BlockPos.ZERO)) {
            Cost c = Cost.decode(Ledger.note(village, COST + h.anchor.asLong()));
            if (c != null && (c.custom() || c.drawing().equals(drawing) && c.age() == age)) return Math.max(1, c.worth());
        }
        int[] r = REPLACE.get(village + "|" + drawing + "|" + age);
        if (r != null) return Math.max(1, r[0] + r[1]);
        int base = h.structure.equals("manor") ? 120 : h.structure.equals("villa") ? 90
            : Ledger.grown(village, h.anchor) ? 55 : 35;
        return base * (1.0 + 0.25 * age);
    }

    /** The council's houses costed: each the day it is first on the books (and again when it is raised or the age turns),
     *  its cost written against the treasury's books. */
    static void costCouncilHouses(ServerLevel level, UUID village, long day) {
        int age = Villages.ageOf(village).ordinal();
        for (Homes.Home h : Homes.homes(village).values()) {
            if (Homes.seat(h) || Flats.isFlat(h) || h.tenure == Homes.Tenure.PLAYER) continue;
            String drawing = Homes.drawing(village, h);
            if (!Blueprints.has(drawing)) continue;
            Cost was = Cost.decode(Ledger.note(village, COST + h.anchor.asLong()));
            if (was != null && (was.custom() || was.drawing().equals(drawing) && was.age() == age)) continue;
            int[] r = replacement(level, village, drawing);
            int book = was == null ? r[0] + r[1] : was.book();
            Ledger.note(village, COST + h.anchor.asLong(), new Cost(drawing, age, r[0], r[1], 0, day, book, false).encode());
            if (was == null) {
                int[] b = councilBook(village);
                Ledger.note(village, BOOK, (b[0] + 1) + "|" + (b[1] + r[0]) + "|" + (b[2] + r[1]));
            }
        }
    }

    /** The council's houses on the treasury's books: {how many costed, their blocks, their builders' labour}. */
    static int[] councilBook(UUID village) {
        String s = Ledger.note(village, BOOK);
        int[] out = new int[3];
        if (s == null || s.isEmpty()) return out;
        String[] p = s.split("\\|", -1);
        for (int i = 0; i < 3 && i < p.length; i++) out[i] = Homes.parse(p[i]);
        return out;
    }

    // ------------------------------------------------------------------ the labour, the plot, the permit

    /** A builder's day: a craftsman's rate in this town (a smith's), its hours at that. */
    static double hourly(UUID village) {
        return Wealth.tradeWage(AssistantEntity.StationTask.SMITH, village) / (double) HOURS_A_DAY;
    }

    /** The builders' hours for so many blocks, at their rate, rounded up to the coin. */
    static int labour(UUID village, int cells) {
        return (int) Math.ceil(cells / (double) BLOCKS_AN_HOUR * hourly(village) - 1e-9);
    }

    /**
     * A plot of the town's plan for a house: eight coins in a Wood Age hamlet, a quarter more an age, as much
     * more as the place's wages are (a town's ground is twice a hamlet's); a quarter more on the first ring of
     * streets round the square, a fifth less out on the third; dearer by the park and cheaper in the crafts'
     * smoke (Quarters); and as the housing market stands.
     */
    static int plotFee(UUID village, BlockPos anchor) {
        Villages.Village v = Villages.get(village);
        double base = PLOT_BASE * (1.0 + 0.25 * Villages.ageOf(village).ordinal()) * Wealth.standing(village) / 10.0;
        int d = v == null ? 50 : Math.max(Math.abs(anchor.getX() - v.centre().getX()), Math.abs(anchor.getZ() - v.centre().getZ()));
        double near = d <= TownPlan.RING + TownPlan.PERIOD ? 1.25 : d <= TownPlan.RING + 2 * TownPlan.PERIOD ? 1.0 : 0.8;
        return Math.max(2, (int) Math.round(base * near * Quarters.homePercent(village, anchor) / 100.0 * index(village)));
    }

    static String plotWords(UUID village, BlockPos anchor) {
        Villages.Village v = Villages.get(village);
        int d = v == null ? 50 : Math.max(Math.abs(anchor.getX() - v.centre().getX()), Math.abs(anchor.getZ() - v.centre().getZ()));
        int p = Quarters.homePercent(village, anchor);
        String where = d <= TownPlan.RING + TownPlan.PERIOD ? "near the square" : d <= TownPlan.RING + 2 * TownPlan.PERIOD ? "on the second ring"
            : "out at the edge";
        return where + (p > 100 ? ", by the park" : p < 100 ? ", in the crafts' smoke" : "");
    }

    static int permit(int blocks, int labour) {
        return Math.max(PERMIT_LEAST, (int) Math.round((blocks + labour) * PERMIT_PERCENT / 100.0));
    }

    // ------------------------------------------------------------------ the bill

    /** A line of a bill: so many of a thing at so much each, in coin. */
    public record Line(Item item, String what, int n, double each, boolean furnishing) {
        public double cost() { return each * n; }
    }

    /** What makes the furnishing (beds, rugs, flowers, a barrel), as against the house itself. */
    static boolean furnishing(BuildGoal.Part part) {
        return part == BuildGoal.Part.BED || part == BuildGoal.Part.CARPET || part == BuildGoal.Part.FLOWER
            || part == BuildGoal.Part.BARREL || part == BuildGoal.Part.BOOKSHELF;
    }

    /** The bill for a design on a plot, item by item: what the household would pay. */
    public static final class Bill {
        public final Design design;
        final BlockPos anchor;
        final Direction facing;
        public final List<Line> lines = new ArrayList<>();
        @Nullable String look;
        public int cells, ground, beds;
        public int blocks, labour, plot, furnish, permit;
        public double hours, rate;

        Bill(Design design, BlockPos anchor, Direction facing) {
            this.design = design;
            this.anchor = anchor;
            this.facing = facing;
        }

        public int total() { return blocks + labour + plot + furnish + permit; }

        /** "blocks 61, labour 18, plot 12, furnishing 24, permit 3". */
        public String parts() {
            return "blocks " + blocks + ", builders' labour " + labour + " (" + Math.round(hours) + " hours at "
                + String.format(Locale.ROOT, "%.1f", rate) + "c), plot " + plot + ", furnishing " + furnish + ", permit " + permit;
        }

        /** The exact sum of the house's block lines (and the furnishing's). */
        public double blocksExact() {
            double d = 0;
            for (Line l : lines) if (!l.furnishing()) d += l.cost();
            return d;
        }

        public double furnishExact() {
            double d = 0;
            for (Line l : lines) if (l.furnishing()) d += l.cost();
            return d;
        }
    }

    /** What a cell of the house is laid in: the look the stores can pay for (Masonry), else the design's palette. */
    @Nullable
    static Block blockFor(@Nullable Masonry.Look look, Design d, BuildGoal.Placement p) {
        if (furnishing(p.part())) {
            BlockState st = Showcase.painter(d.palette).apply(p);
            return st == null ? null : st.getBlock();
        }
        Masonry.Role r = Masonry.role(p);
        if (r == null) return null;
        if (look != null) return look.of(r);
        BlockState st = Showcase.painter(d.palette).apply(p);
        return st == null ? null : st.getBlock();
    }

    /**
     * The cells of the house, in the order they go up: the house a course at a time from the footing, then its
     * furnishing (only so many of its beds as the household wants). The same list whatever the house is laid in,
     * so what has been laid is kept by its place in it; a cell the look leaves out (no glass to be had when it was
     * agreed) is passed over when its turn comes, and not paid for.
     */
    static List<BuildGoal.Placement> cells(Design d, BlockPos anchor, Direction facing, int beds) {
        List<BuildGoal.Placement> house = new ArrayList<>(), things = new ArrayList<>();
        int bedsLaid = 0;
        for (BuildGoal.Placement p : BuildGoal.plan(d.drawing, anchor, facing, 13)) {
            if (p.part() == BuildGoal.Part.CLEAR || p.part() == BuildGoal.Part.WATER) continue;
            if (!furnishing(p.part()) && Masonry.role(p) == null) continue;
            if (p.part() == BuildGoal.Part.BED && bedsLaid++ >= beds) continue;
            (furnishing(p.part()) ? things : house).add(p);
        }
        house.sort(Comparator.comparingInt(p -> p.pos().getY()));          // a stable sort: the drawing's order within a course
        things.sort(Comparator.comparingInt(p -> p.pos().getY()));
        house.addAll(things);
        return house;
    }

    /** How steep a lot a household will build on, paying for the ground made up: twice what the council's builders take. */
    static final int PRIVATE_SLOPE = 8;
    /** How deep a pool or a river's edge a household will build out over, filled from its bed, and over how much of the lot. */
    static final int PRIVATE_WADE = 3;

    /**
     * The ground made up under a house: every column of its footprint that stops short of its floor, built up from the ground
     * (or, by the water, from the bed of it: a shallow edge is filled, as a jetty's is) to the floor; not the cells its own
     * footing goes in, which are laid as the house.
     */
    static List<BlockPos> groundCells(ServerLevel level, BlockPos anchor, Direction facing, Design d) {
        int[] half = BuildGoal.footprint(d.drawing);
        boolean turned = facing.getAxis() == Direction.Axis.X;
        int wx = turned ? half[1] : half[0], wz = turned ? half[0] : half[1];
        java.util.Set<BlockPos> footing = new java.util.HashSet<>();
        for (BuildGoal.Placement p : BuildGoal.plan(d.drawing, anchor, facing, 13)) if (p.pos().getY() < anchor.getY()) footing.add(p.pos());
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -wx; dx <= wx; dx++) {
            for (int dz = -wz; dz <= wz; dz++) {
                int x = anchor.getX() + dx, z = anchor.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = BuildGoal.groundTop(level, x, z);
                while (top > anchor.getY() - 8 && !level.getBlockState(new BlockPos(x, top - 1, z)).getFluidState().isEmpty()) top--;
                for (int y = Math.max(top, anchor.getY() - 8); y < anchor.getY(); y++) {
                    BlockPos at = new BlockPos(x, y, z);
                    if (!footing.contains(at)) out.add(at);
                }
            }
        }
        return out;
    }

    /**
     * A lot a household finds for itself where the council's builders found none (Villages.siteFor: dry under every corner,
     * no steeper than four or six): one of the plan's home lots, its own quarter's first, kept off the fields, nothing built
     * or going up on it, its ground no steeper than eight across the house and within reach of the heart's height, a third of
     * it at most a shallow river's edge (filled from the bed), not rocky; of the first dozen that will do, the one wanting the
     * least made ground. The household pays for the made ground (the bill's cobblestone), so it will build where the council
     * would not. Held for it as any site is. Null if there is none.
     */
    @Nullable
    static Villages.Site privateLot(ServerLevel level, Villages.Village v, Design d) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        int heartY = level.hasChunk(heart.getX() >> 4, heart.getZ() >> 4) ? BuildGoal.groundTop(level, heart.getX(), heart.getZ()) : Integer.MIN_VALUE;
        int[] half = BuildGoal.footprint(d.drawing);
        List<Ledger.Building> built = Ledger.buildings(id);
        java.util.Collection<Villages.Site> going = Villages.sitesOf(id).values();
        Villages.Site best = null;
        int bestScore = Integer.MAX_VALUE, valid = 0, index = 0;
        for (TownPlan.Lot lot : Quarters.candidates(id, d.siteKey())) {
            if (valid >= 12) break;
            index++;
            if (lot.kind() != TownPlan.Kind.LOT || !"home".equals(lot.use())) continue;
            if (half[0] > lot.halfAcross() || half[1] > lot.halfDeep()) continue;
            if (Villages.lotKeptOff(id, heart, lot) || Villages.builtOver(id, lot.x(), lot.z(), 0, 0)) continue;
            int x = heart.getX() + lot.x(), z = heart.getZ() + lot.z();
            boolean taken = false;
            for (Ledger.Building b : built) {
                if (Math.abs(b.anchor().getX() - x) <= TownPlan.LOT / 2 + 1 && Math.abs(b.anchor().getZ() - z) <= TownPlan.LOT / 2 + 1) { taken = true; break; }
            }
            for (Villages.Site s : going) {
                if (Math.abs(s.anchor().getX() - x) <= TownPlan.LOT && Math.abs(s.anchor().getZ() - z) <= TownPlan.LOT) { taken = true; break; }
            }
            if (taken) continue;
            Direction back = Villages.direction(lot.back());
            boolean turned = back.getAxis() == Direction.Axis.X;
            int hx = turned ? half[1] : half[0], hz = turned ? half[0] : half[1];
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, wet = 0, deep = 0;
            boolean ok = true;
            for (int dx = -hx; dx <= hx && ok; dx++) {
                for (int dz = -hz; dz <= hz; dz++) {
                    int cx = x + dx, cz = z + dz;
                    if (!level.hasChunk(cx >> 4, cz >> 4)) { ok = false; break; }
                    int h = BuildGoal.groundTop(level, cx, cz);
                    BlockState top = level.getBlockState(new BlockPos(cx, h - 1, cz));
                    if (!top.getFluidState().isEmpty()) {
                        int bed = h - 1;
                        while (bed > h - 1 - PRIVATE_WADE - 1 && !level.getBlockState(new BlockPos(cx, bed, cz)).getFluidState().isEmpty()) bed--;
                        if (h - 1 - bed > PRIVATE_WADE || top.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) { ok = false; break; }
                        wet++;
                    } else if (!top.isSolid()) {
                        ok = false;
                        break;
                    }
                    lo = Math.min(lo, h);
                    hi = Math.max(hi, h);
                }
            }
            int area = (2 * hx + 1) * (2 * hz + 1);
            if (!ok || wet * 3 > area || hi - lo > PRIVATE_SLOPE) continue;
            if (heartY != Integer.MIN_VALUE && Math.abs(hi - heartY) > 20) continue;
            BlockPos at = new BlockPos(x, hi, z);
            int blocked = 0;
            for (int dx = -hx; dx <= hx; dx++) {
                for (int dz = -hz; dz <= hz; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos c = at.offset(dx, dy, dz);
                        BlockState st = level.getBlockState(c);
                        if (BuildGoal.soft(st) || st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) && BuildGoal.isTreeLog(level, c)) continue;
                        blocked++;
                    }
                }
            }
            if (blocked > area / 5) continue;
            valid++;
            int score = groundCells(level, at, back, d).size() + 6 * index + Quarters.misfit(id, d.siteKey(), lot);
            if (score < bestScore) {
                bestScore = score;
                best = new Villages.Site(at, back, 0);
            }
        }
        if (best != null) Villages.holdPrivateSite(id, d.siteKey(), best);
        return best;
    }

    /** How many beds a household wants in a house of its own: one each and one to spare, two at least, no more than it holds. */
    static int bedsWanted(Design d, int household) {
        return Math.min(d.beds(), Math.max(2, household + 1));
    }

    /**
     * The bill for this design on this plot for a household of so many: every block of the drawing at the
     * town's price today (what the stores can pay for it in, else the design's own look), the ground made up
     * under it, the builders' hours at a craftsman's rate, the plot, its beds and rugs, and the permit.
     */
    static Bill quote(ServerLevel level, Villages.Village v, Design d, BlockPos anchor, Direction facing, int household) {
        return quote(level, v, d, anchor, facing, household, true);
    }

    static Bill quote(ServerLevel level, Villages.Village v, Design d, BlockPos anchor, Direction facing, int household, boolean ground) {
        UUID id = v.id();
        Bill b = new Bill(d, anchor, facing);
        Masonry.Look look = lookFor(level, v, d, anchor, facing);
        b.look = look == null ? null : look.encode();
        b.beds = bedsWanted(d, household);
        Map<Item, Integer> house = new LinkedHashMap<>(), things = new LinkedHashMap<>();
        for (BuildGoal.Placement p : cells(d, anchor, facing, b.beds)) {
            Block bl = blockFor(look, d, p);
            if (bl == null) continue;
            Item it = bl == Blocks.WALL_TORCH ? Items.TORCH : bl.asItem();
            if (it == Items.AIR) continue;
            (furnishing(p.part()) ? things : house).merge(it, 1, Integer::sum);
            b.cells++;
        }
        b.ground = ground && level.isLoaded(anchor) ? groundCells(level, anchor, facing, d).size() : 0;
        if (b.ground > 0) house.merge(Items.COBBLESTONE, b.ground, Integer::sum);
        for (Map.Entry<Item, Integer> e : house.entrySet()) b.lines.add(line(level, id, e.getKey(), e.getValue(), false));
        for (Map.Entry<Item, Integer> e : things.entrySet()) b.lines.add(line(level, id, e.getKey(), e.getValue(), true));
        b.blocks = (int) Math.round(b.blocksExact());
        b.furnish = (int) Math.round(b.furnishExact());
        int laid = b.cells + b.ground;
        b.hours = laid / (double) BLOCKS_AN_HOUR;
        b.rate = hourly(id);
        b.labour = labour(id, laid);
        b.plot = plotFee(id, anchor);
        b.permit = permit(b.blocks, b.labour);
        return b;
    }

    private static Line line(ServerLevel level, UUID village, Item item, int n, boolean things) {
        return new Line(item, new ItemStack(item).getHoverName().getString(), n, PriceIndex.each(level, village, item), things);
    }

    /**
     * What a house is to be laid in: its own look, as far as the stores can pay for all of it (Masonry: brick where
     * there is brick, else the next best); a town whose stores cannot run to that builds it plainer, in the
     * council's timber and stone; and if they cannot run to even that today, its own look, and the builders wait
     * on the stores for it.
     */
    @Nullable
    static Masonry.Look lookFor(ServerLevel level, Villages.Village v, Design d, BlockPos anchor, Direction facing) {
        List<BuildGoal.Placement> structure = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(d.drawing, anchor, facing, 13)) {
            if (p.part() != BuildGoal.Part.CLEAR && p.part() != BuildGoal.Part.WATER && !furnishing(p.part())) structure.add(p);
        }
        Masonry.Look look = Masonry.buildIn(level, v, d.palette, structure, false);
        if (look == null && d.palette != Showcase.OAK) look = Masonry.buildIn(level, v, Showcase.OAK, structure, false);
        return look;
    }

    /** What each design comes to in each town for a household of so many, reckoned once a day: {total, day}. */
    private static final Map<String, int[]> ESTIMATES = new ConcurrentHashMap<>();

    /** What a design would come to here for a household of so many, on a plot of the going price on the second ring of
     *  streets: for choosing and saving, before a plot is chosen. */
    static int estimate(ServerLevel level, Villages.Village v, Design d, int household) {
        long day = level.getDayTime() / 24000L;
        int beds = bedsWanted(d, household);
        String key = v.id() + "|" + d + "|" + beds;
        int[] have = ESTIMATES.get(key);
        if (have != null && have[1] == day) return have[0];
        BlockPos probe = v.centre().offset(TownPlan.RING + TownPlan.PERIOD, 0, 0);
        Bill b = quote(level, v, d, probe, Direction.NORTH, household, false);
        int total = b.total();
        ESTIMATES.put(key, new int[]{ total, (int) day });
        return total;
    }

    // ------------------------------------------------------------------ can it pay?

    /** How a household would pay: what it has to hand, a fifth down, the bank's loan, the week's payment, and why not if it cannot. */
    public record Means(boolean can, int cash, int down, int loan, int weekly, int term, int living, String why) {}

    /** What a household can put to a house: its purses over a dozen coins a head, what it put by toward its rented house, its savings at the bank. */
    static int cashOf(UUID village, List<VillageFolkEntity> household) {
        int n = 0;
        for (VillageFolkEntity f : Homes.grown(household)) n += Math.max(0, f.purse() - Homes.LIVE_ON) + Math.max(0, Bank.balance(f));
        Homes.Home h = household.isEmpty() ? null : Homes.homeOf(village, household.get(0).getUUID());
        if (h != null && h.tenure == Homes.Tenure.RENTED && !Homes.seat(h)) n += Math.max(0, h.saved);
        return n;
    }

    /**
     * A week's living for the household: two loaves a day each grown folk eats at the town's price, the
     * rent on the house it lives in now (while the new one goes up), and what it pays the bank on it.
     */
    static int livingAWeek(ServerLevel level, UUID village, List<VillageFolkEntity> household) {
        double bread = PriceIndex.each(level, village, Items.BREAD);
        double a = Homes.grown(household).size() * 2 * bread * Bank.WEEK;
        Homes.Home h = household.isEmpty() ? null : Homes.homeOf(village, household.get(0).getUUID());
        if (h != null && !Homes.seat(h) && (h.tenure == Homes.Tenure.RENTED || h.tenure == Homes.Tenure.PLAYER)) a += Bank.WEEK * Math.max(0, h.rent);
        for (VillageFolkEntity f : Homes.grown(household)) {
            Bank.Loan l = Bank.loanOf(village, f);
            if (l != null) { a += l.weekly; break; }
        }
        return (int) Math.ceil(a);
    }

    /**
     * Can a household pay this bill? All of it out of what it has; or, with the bank open, a fifth down
     * and a mortgage the bank would lend (the vault keeping its reserve) whose week's payment its wages
     * carry: no more than a third of them (the bank's rule), and no more than they leave over its week's
     * bread, rent and payments. Otherwise it cannot, and says what it lacks.
     */
    static Means means(ServerLevel level, Villages.Village v, List<VillageFolkEntity> household, int total) {
        UUID id = v.id();
        List<VillageFolkEntity> grown = Homes.grown(household);
        int living = livingAWeek(level, id, household);
        if (grown.isEmpty() || Homes.earners(household) == 0) return new Means(false, 0, 0, 0, 0, 0, living, "nobody in it earns");
        int cash = cashOf(id, household);
        if (cash >= total) return new Means(true, total, total, 0, 0, 0, living, "pays for it outright");
        if (!Bank.open(id)) {
            return new Means(false, cash, total, 0, 0, 0, living, "has " + cash + " of the " + total + Homes.coins(total)
                + ", and there is no bank to lend the rest");
        }
        int down = (total + Bank.DOWN_PART - 1) / Bank.DOWN_PART;
        if (cash < down) return new Means(false, cash, down, 0, 0, 0, living, "a fifth down wanted: " + cash + " of " + down + " put by");
        int loan = total - cash;
        int lendable = Bank.lendable(Bank.state(id));
        if (loan > lendable) return new Means(false, cash, down, loan, 0, 0, living, "the bank has only " + lendable + " to lend");
        int wages = 0;
        for (VillageFolkEntity f : grown) wages += Wealth.wage(f);
        int week = Bank.WEEK * wages;
        int carry = Math.min(week / Bank.CARRY_PART, week - living);
        for (int t = Bank.SHORTEST; t <= Bank.LONGEST; t++) {
            int w = Bank.payment(loan, Bank.LOAN_BP, t);
            if (w <= carry) return new Means(true, cash, down, loan, w, t, living, cash + " down and " + loan + " on the bank's mortgage, "
                + w + " a week for " + t + " weeks");
        }
        return new Means(false, cash, down, loan, 0, 0, living, "its wages (" + week + " a week, " + living + " of it to live on) would not carry "
            + Bank.payment(loan, Bank.LOAN_BP, Bank.LONGEST) + " a week to the bank");
    }

    // ------------------------------------------------------------------ who wants one, and which

    /** Why a household wants a house of its own built, how much, and in its own words. */
    public record Interest(int score, List<String> why, String mine) {}

    /**
     * Does this household want a house built for itself? The well-off (+2), the comfortable (+1); a family
     * outgrowing the house it has (+2); a Visionary that wants a house to its own drawing (+2), a Merchant
     * with a front to show (+1); a couple wed this week (+2); any household that wants to own at all (+1);
     * and a Free Spirit would rather rent and keep its coin (-3). Three or more and it means to.
     */
    static Interest interest(ServerLevel level, UUID village, List<VillageFolkEntity> household, @Nullable Homes.Home home, long day) {
        List<VillageFolkEntity> grown = Homes.grown(household);
        List<String> why = new ArrayList<>();
        int score = 0;
        String mine = "";
        Wealth.Tier best = Wealth.Tier.POOR;
        for (VillageFolkEntity f : grown) if (Wealth.tier(f).ordinal() > best.ordinal()) best = Wealth.tier(f);
        if (best.ordinal() >= Wealth.Tier.WELL_OFF.ordinal()) { score += 2; why.add(best.label); mine = "we've done well enough for a house of our own"; }
        else if (best == Wealth.Tier.COMFORTABLE) { score += 1; why.add("comfortable"); }
        if (home != null && level.isLoaded(home.anchor) && household.size() > Math.max(1, Homes.bedsIn(level, village, home).size())) {
            score += 2;
            why.add("outgrowing its house");
            mine = "we've outgrown the house";
        }
        int visionary = 0, merchant = 0, free = 0;
        for (VillageFolkEntity f : grown) {
            Values.Value top = Values.top(f);
            if (top == Values.Value.PROGRESS) visionary++;
            if (top == Values.Value.WEALTH) merchant++;
            if (top == Values.Value.LEISURE) free++;
        }
        if (visionary > 0) { score += 2; why.add("a Visionary wants a house to its own drawing"); mine = "I want a house built to my own drawing"; }
        if (merchant > 0 && best.ordinal() >= Wealth.Tier.COMFORTABLE.ordinal()) { score += 1; why.add("a Merchant with a front to show"); }
        if (free * 2 > grown.size()) { score -= 3; why.add("a Free Spirit would rather rent and keep its coin"); }
        for (VillageFolkEntity f : grown) {
            long wed = Families.wedOn(f);
            if (wed >= 0 && day - wed <= 7) { score += 2; why.add("newly wed"); mine = "we've just been wed: we want a home of our own"; break; }
        }
        if (home != null && home.tenure != Homes.Tenure.OWNED && !Homes.seat(home) && Homes.wish(village, home, household).yes()) {
            score += 1;
            why.add("wants to own its home");
        } else if (home == null && best.ordinal() >= Wealth.Tier.COMFORTABLE.ordinal()) {
            score += 1;
            why.add("no home of its own yet");
        }
        if (mine.isEmpty()) mine = "a house of our own, built for us";
        return new Interest(score, why, mine);
    }

    /** The designs a household would have, first choice first: by its taste, and by its size (a cottage for two). */
    static List<Design> taste(List<VillageFolkEntity> household) {
        Map<Values.Value, Integer> natures = new java.util.EnumMap<>(Values.Value.class);
        for (VillageFolkEntity f : Homes.grown(household)) natures.merge(Values.top(f), 1, Integer::sum);
        Values.Value top = natures.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Values.Value.HOMES);
        boolean big = household.size() >= 3;
        return switch (top) {
            case PROGRESS -> List.of(Design.VILLA, Design.TOWN, Design.FAMILY, Design.COTTAGE);
            case WEALTH -> List.of(Design.TOWN, Design.VILLA, Design.FAMILY, Design.COTTAGE);
            case TRADITION -> List.of(Design.COTTAGE, Design.FAMILY, Design.TOWN);
            case LEISURE -> List.of(Design.COTTAGE);
            default -> big ? List.of(Design.FAMILY, Design.TOWN, Design.COTTAGE) : List.of(Design.COTTAGE, Design.FAMILY);
        };
    }

    /** Is the town letting its folk build their own houses: past the Wood Age, and sixteen strong? */
    public static boolean open(UUID village) {
        if (OPEN_FOR_TESTS != null) return OPEN_FOR_TESTS;
        return Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal() && Villages.headcount(village) >= FROM_FOLK;
    }

    @Nullable private static Boolean OPEN_FOR_TESTS;

    // ------------------------------------------------------------------ a house going up

    /** The house going up for a household: what was agreed, what has been paid and laid, and what it waits on. */
    static final class Commission {
        Design design = Design.COTTAGE;
        UUID lead;
        final List<UUID> members = new ArrayList<>();
        String names = "";
        BlockPos anchor = BlockPos.ZERO;
        Direction facing = Direction.NORTH;
        String address = "";
        String look = "";
        int beds, cells, ground;
        int blocks, labour, plot, furnish, permit;
        /** Coin held for the build, and how it was raised. */
        int escrow, down, loan, weekly;
        double blocksDue, furnishDue;
        int blocksPaid, furnishPaid, labourPaid, laidCount;
        boolean groundDone, foreclosed;
        long started, day, waitSince = -1;
        String waiting = "";
        final Map<Item, Double> prices = new LinkedHashMap<>();
        final Map<UUID, Integer> builders = new LinkedHashMap<>();
        final BitSet laid = new BitSet();

        int total() { return blocks + labour + plot + furnish + permit; }

        @Nullable Masonry.Look lookOf() { return look.isEmpty() ? null : Masonry.Look.decode(look); }

        String encode() {
            StringBuilder m = new StringBuilder();
            for (UUID u : members) m.append(m.length() == 0 ? "" : ",").append(u);
            StringBuilder pr = new StringBuilder();
            for (Map.Entry<Item, Double> e : prices.entrySet()) {
                pr.append(pr.length() == 0 ? "" : ";").append(BuiltInRegistries.ITEM.getKey(e.getKey())).append('=')
                    .append(String.format(Locale.ROOT, "%.5f", e.getValue()));
            }
            StringBuilder bu = new StringBuilder();
            for (Map.Entry<UUID, Integer> e : builders.entrySet()) bu.append(bu.length() == 0 ? "" : ";").append(e.getKey()).append('=').append(e.getValue());
            StringBuilder bits = new StringBuilder();
            for (long w : laid.toLongArray()) bits.append(bits.length() == 0 ? "" : ",").append(Long.toHexString(w));
            String[] f = { design.name(), lead.toString(), m.toString(), clean(names), Long.toString(anchor.asLong()),
                Integer.toString(facing.get2DDataValue()), look, Integer.toString(beds), Integer.toString(cells), Integer.toString(ground),
                Integer.toString(blocks), Integer.toString(labour), Integer.toString(plot), Integer.toString(furnish), Integer.toString(permit),
                Integer.toString(escrow), Integer.toString(down), Integer.toString(loan), Integer.toString(weekly),
                String.format(Locale.ROOT, "%.5f", blocksDue), String.format(Locale.ROOT, "%.5f", furnishDue),
                Integer.toString(blocksPaid), Integer.toString(furnishPaid), Integer.toString(labourPaid), Integer.toString(laidCount),
                groundDone ? "1" : "0", foreclosed ? "1" : "0", Long.toString(started), Long.toString(day), clean(waiting),
                pr.toString(), bu.toString(), bits.toString(), clean(address), Long.toString(waitSince) };
            return String.join("\t", f);
        }

        @Nullable
        static Commission decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            try {
                String[] p = s.split("\t", -1);
                Commission c = new Commission();
                c.design = Design.valueOf(p[0]);
                c.lead = UUID.fromString(p[1]);
                if (!p[2].isEmpty()) for (String u : p[2].split(",")) c.members.add(UUID.fromString(u));
                c.names = p[3];
                c.anchor = BlockPos.of(Long.parseLong(p[4]));
                c.facing = Direction.from2DDataValue(Integer.parseInt(p[5]));
                c.look = p[6];
                c.beds = Homes.parse(p[7]);
                c.cells = Homes.parse(p[8]);
                c.ground = Homes.parse(p[9]);
                c.blocks = Homes.parse(p[10]);
                c.labour = Homes.parse(p[11]);
                c.plot = Homes.parse(p[12]);
                c.furnish = Homes.parse(p[13]);
                c.permit = Homes.parse(p[14]);
                c.escrow = Homes.parse(p[15]);
                c.down = Homes.parse(p[16]);
                c.loan = Homes.parse(p[17]);
                c.weekly = Homes.parse(p[18]);
                c.blocksDue = Double.parseDouble(p[19]);
                c.furnishDue = Double.parseDouble(p[20]);
                c.blocksPaid = Homes.parse(p[21]);
                c.furnishPaid = Homes.parse(p[22]);
                c.labourPaid = Homes.parse(p[23]);
                c.laidCount = Homes.parse(p[24]);
                c.groundDone = "1".equals(p[25]);
                c.foreclosed = "1".equals(p[26]);
                c.started = Long.parseLong(p[27]);
                c.day = Long.parseLong(p[28]);
                c.waiting = p[29];
                if (!p[30].isEmpty()) {
                    for (String e : p[30].split(";")) {
                        int eq = e.indexOf('=');
                        Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.substring(0, eq)));
                        c.prices.put(it, Double.parseDouble(e.substring(eq + 1)));
                    }
                }
                if (!p[31].isEmpty()) {
                    for (String e : p[31].split(";")) {
                        int eq = e.indexOf('=');
                        c.builders.put(UUID.fromString(e.substring(0, eq)), Homes.parse(e.substring(eq + 1)));
                    }
                }
                if (!p[32].isEmpty()) {
                    String[] w = p[32].split(",");
                    long[] words = new long[w.length];
                    for (int i = 0; i < w.length; i++) words[i] = Long.parseUnsignedLong(w[i], 16);
                    c.laid.or(BitSet.valueOf(words));
                }
                c.address = p.length > 33 ? p[33] : "";
                c.waitSince = p.length > 34 ? Long.parseLong(p[34]) : -1;
                return c;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("\t", " ").replace("\n", " ");
    }

    @Nullable
    static Commission load(UUID village) {
        return Commission.decode(Ledger.note(village, BUILD));
    }

    static void store(UUID village, Commission c) {
        Ledger.note(village, BUILD, c.encode());
    }

    /** Is a folk's own house going up on this ground (its anchor), its mortgage standing on a house not yet built (Bank)? */
    static boolean goingUp(UUID village, long anchor) {
        Commission c = load(village);
        return c != null && c.anchor.asLong() == anchor;
    }

    /**
     * The house going up on this ground, for the bank's round while it stands unfinished: its household, so the
     * week's payment on its mortgage is taken out of their purses as on any house (Bank.collect). Null if none.
     */
    @Nullable
    static Homes.Home standIn(UUID village, long anchor) {
        Commission c = load(village);
        if (c == null || c.anchor.asLong() != anchor) return null;
        Homes.Home h = new Homes.Home(c.anchor, c.design.structure);
        h.tenure = Homes.Tenure.OWNED;
        for (UUID u : c.members) if (Homes.loaded(village, u) != null) h.members.add(u);
        return h;
    }

    /** Is this house a folk's own, built for it and still privately owned (not the council's to rebuild, raise or furnish)? */
    public static boolean isPrivate(@Nullable UUID village, BlockPos anchor) {
        if (village == null) return false;
        String s = Ledger.note(village, OWN + anchor.asLong());
        return s != null && !s.isEmpty();
    }

    // ------------------------------------------------------------------ payday

    /**
     * The morning, after the rent (Market.tick): the market reckoned, then a household that wants a house of
     * its own built and can pay for it commissions one (one going up at a time); the others note what they
     * are saving for, and talk of it.
     */
    public static void payday(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        reckon(level, v, day);
        if (!open(id)) return;
        consider(level, v, day, false);
    }

    /** The households of the town: each grown folk with its partner and their children, once. */
    static List<List<VillageFolkEntity>> households(UUID village) {
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isShowcase() && !f.isHired()) folk.add(f);
        }
        List<List<VillageFolkEntity>> out = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (VillageFolkEntity f : folk) {
            if (f.isBaby() || seen.contains(f.getUUID())) continue;
            List<VillageFolkEntity> h = new ArrayList<>();
            h.add(f);
            seen.add(f.getUUID());
            VillageFolkEntity p = Homes.partner(f, folk);
            if (p != null && seen.add(p.getUUID())) h.add(p);
            for (VillageFolkEntity c : folk) {
                if (!c.isBaby() || seen.contains(c.getUUID())) continue;
                if (Homes.bornTo(c, f) || p != null && Homes.bornTo(c, p)) { h.add(c); seen.add(c.getUUID()); }
            }
            out.add(h);
        }
        return out;
    }

    /**
     * Who commissions a house: of the households that want one, the most eager that can pay for its first
     * choice it can afford. One at a time: while a house is going up for one household, the rest save. A
     * household that wants one and cannot pay notes what it is saving for (the plan on its card). Returns
     * the commission made, if any.
     */
    @Nullable
    static Commission consider(ServerLevel level, Villages.Village v, long day, boolean force) {
        UUID id = v.id();
        Commission going = load(id);
        List<List<VillageFolkEntity>> all = households(id);
        all.sort(Comparator.comparingInt((List<VillageFolkEntity> h) -> -cashOf(id, h)));
        Commission made = null;
        for (List<VillageFolkEntity> household : all) {
            VillageFolkEntity lead = household.get(0);
            Homes.Home home = Homes.homeOf(id, lead.getUUID());
            if (home != null && Homes.seat(home)) continue;                       // the leader's household lives in the hall
            if (going != null && going.members.contains(lead.getUUID())) continue;
            Interest want = interest(level, id, household, home, day);
            if (want.score() < 3 && !force) {
                Ledger.forget(id, PLAN + lead.getUUID());
                continue;
            }
            // Its choice: the first of its tastes it can pay for; else what it is saving for (its first choice).
            Design dream = null, can = null;
            int dreamTotal = 0;
            Means canMeans = null;
            for (Design d : taste(household)) {
                if (d.beds() < Homes.grown(household).size()) continue;
                int total = estimate(level, v, d, household.size());
                if (dream == null) { dream = d; dreamTotal = total; }
                Means m = means(level, v, household, total + total / 10);        // a tenth in hand for the plot's place and the ground
                if (m.can()) { can = d; canMeans = m; break; }
            }
            if (dream == null) continue;
            if (can == null || going != null || made != null) {
                plan(level, id, household, can != null ? can : dream, can != null ? estimate(level, v, can, household.size()) : dreamTotal,
                    can != null ? (going != null || made != null ? "waiting for the builders: one house goes up at a time"
                        : canMeans.why()) : means(level, v, household, dreamTotal).why(), day);
                continue;
            }
            Commission c = commission(level, v, household, can, day);
            if (c != null) made = c;
        }
        return made;
    }

    /** A household's plan noted: what it is saving for, its cost, what it has toward it, and why it waits. */
    static void plan(ServerLevel level, UUID village, List<VillageFolkEntity> household, Design d, int total, String why, long day) {
        VillageFolkEntity lead = household.get(0);
        int have = cashOf(village, household);
        String key = PLAN + lead.getUUID();
        String was = Ledger.note(village, key);
        Ledger.note(village, key, d.name() + "\t" + total + "\t" + have + "\t" + day + "\t" + clean(why));
        // Talk of it, now and then: the house it is saving for, and how far off it is.
        if (level.getRandom().nextInt(was == null || was.isEmpty() ? 1 : 4) != 0) return;
        int down = (total + Bank.DOWN_PART - 1) / Bank.DOWN_PART;
        String toward = Bank.open(village) ? (have >= down ? "we've the fifth down; it's the bank's say now" : have + " of the " + down + " down put by")
            : have + " of " + total;
        FolkTalk.speak(lead, FolkTalk.pick(level.getRandom(),
            "Saving for " + d.a + " of our own — " + toward + ". We'll get there.",
            "Every coin we can spare goes on the house we mean to build: " + d.a + ", about " + total + " coins all told.",
            "Not this year, maybe next: " + d.a + " of our own, paid for block by block.",
            "I've been pricing " + d.a + ". The blocks, the builders, the plot — " + total + " coins, near enough."));
    }

    /** What a household is saving to build, from its plan: {design, total, have, day, why}, or null. */
    @Nullable
    static String[] planOf(UUID village, UUID lead) {
        String s = Ledger.note(village, PLAN + lead);
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\t", -1);
        return p.length < 5 ? null : p;
    }

    /**
     * A house commissioned: a lot of the town's plan for it, the exact bill on that lot, and if the household
     * can pay it all, the money raised (its purses, what it had put by, its savings at the bank, and the
     * bank's loan) and the plot and the permit paid to the treasury there and then. Null (and nothing taken,
     * the lot let go) if no lot can be had or the bill is past it after all.
     */
    @Nullable
    static Commission commission(ServerLevel level, Villages.Village v, List<VillageFolkEntity> household, Design d, long day) {
        return commission(level, v, household, d, day, null);
    }

    /**
     * As above, on this ground if it is given (an operator's stage, for the pictures); else a lot of the town's plan as the
     * council's builders choose one (Villages.siteFor), else one the household finds for itself on ground the council would
     * not build on, steeper or by the water, the made ground at its own cost (privateLot). A villa with no lot to its size
     * anywhere is built as a town house instead.
     */
    @Nullable
    static Commission commission(ServerLevel level, Villages.Village v, List<VillageFolkEntity> household, Design want, long day,
                                 @Nullable Villages.Site given) {
        UUID id = v.id();
        VillageFolkEntity lead = household.get(0);
        Design d = want;
        Villages.Site site = given;
        if (site != null) Villages.holdPrivateSite(id, d.siteKey(), site);
        if (site == null) site = Villages.siteFor(level, id, d.siteKey());
        if (site == null) site = privateLot(level, v, d);
        if (site == null && d == Design.VILLA) {
            d = Design.TOWN;
            site = Villages.siteFor(level, id, d.siteKey());
            if (site == null) site = privateLot(level, v, d);
        }
        if (site == null) {
            plan(level, id, household, want, estimate(level, v, want, household.size()), "no plot to be had in the town's plan just now", day);
            return null;
        }
        Bill b = quote(level, v, d, site.anchor(), site.facing(), household.size());
        Means m = means(level, v, household, b.total());
        if (!m.can()) {
            Villages.privateSiteDone(id, d.siteKey(), true);                     // [econ-housing] the lot back to the town
            plan(level, id, household, d, b.total(), m.why(), day);
            return null;
        }
        // The money raised: what it put by toward its rented house first, then its purses (a dozen kept back each), then
        // the bank: its savings there, and the loan for the rest (Bank.lend, as for any house: on this ground).
        int escrow = 0;
        Homes.Home old = Homes.homeOf(id, lead.getUUID());
        int fromSaved = 0;
        if (old != null && old.tenure == Homes.Tenure.RENTED && !Homes.seat(old) && old.saved > 0) {
            fromSaved = Math.min(old.saved, b.total());
            old.saved -= fromSaved;
            Homes.save(id, old);
            escrow += fromSaved;
        }
        List<VillageFolkEntity> grown = Homes.grown(household);
        grown.sort((x, y) -> Integer.compare(y.purse(), x.purse()));
        Map<VillageFolkEntity, Integer> fromPurse = new LinkedHashMap<>();
        for (VillageFolkEntity f : grown) {
            int k = Math.min(b.total() - escrow, Math.max(0, f.purse() - Homes.LIVE_ON));
            if (k > 0 && f.spend(k)) { escrow += k; fromPurse.put(f, k); }
            if (escrow >= b.total()) break;
        }
        int loan = 0, weekly = 0;
        if (escrow < b.total()) {
            Homes.Home ground = new Homes.Home(site.anchor(), d.structure);
            ground.price = b.total();
            ground.saved = escrow;
            Bank.lend(level, v, ground, household, day);
            Bank.Loan l = Bank.state(id).loans.get(site.anchor().asLong());
            if (l != null) { loan = l.lent; weekly = l.weekly; }
            if (ground.saved < b.total()) {
                // The bank said no after all: everything back where it came from.
                int back = ground.saved;
                for (Map.Entry<VillageFolkEntity, Integer> e : fromPurse.entrySet()) { e.getKey().earn(e.getValue()); back -= e.getValue(); }
                if (old != null && fromSaved > 0) { old.saved += fromSaved; Homes.save(id, old); back -= fromSaved; }
                if (back > 0 && !grown.isEmpty()) grown.get(0).earn(back);         // its savings drawn out of the bank, back in its purse
                Villages.privateSiteDone(id, d.siteKey(), true);
                plan(level, id, household, d, b.total(), "the bank would not lend: " + Bank.whyForTests(site.anchor()), day);
                return null;
            }
            escrow = ground.saved;
        }
        Commission c = new Commission();
        c.design = d;
        c.lead = lead.getUUID();
        for (VillageFolkEntity f : grown) c.members.add(f.getUUID());
        c.names = Homes.names(household);
        c.anchor = site.anchor().immutable();
        c.facing = site.facing();
        c.address = addressAt(id, v, c.anchor, c.facing, d);
        c.look = b.look == null ? "" : b.look;
        c.beds = b.beds;
        c.cells = cells(d, c.anchor, c.facing, b.beds).size();
        c.ground = b.ground;
        c.blocks = b.blocks;
        c.labour = b.labour;
        c.plot = b.plot;
        c.furnish = b.furnish;
        c.permit = b.permit;
        for (Line ln : b.lines) c.prices.put(ln.item(), ln.each());
        c.loan = loan;
        c.weekly = weekly;
        c.down = b.total() - loan;
        c.started = c.day = day;
        // The council's share, there and then: the plot and the permit, into the treasury.
        int council = c.plot + c.permit;
        escrow -= council;
        Ledger.addCoins(id, council);
        Economy.houseSold(id, council);
        c.escrow = escrow;
        store(id, c);
        Ledger.forget(id, PLAN + lead.getUUID());
        Villages.tell(id, day, c.names + " commissioned " + d.a + " of their own at " + c.address + " (" + d.looks + "): " + b.total() + Homes.coins(b.total())
            + " all told — " + b.parts() + (loan > 0 ? "; " + c.down + " down and " + loan + " on the bank's mortgage" : "; paid for outright"));
        for (VillageFolkEntity f : grown) f.persona().remember(day, "we commissioned " + d.a + " of our own, " + b.total() + " coins all told", 7);
        FolkTalk.speak(lead, FolkTalk.pick(level.getRandom(), "We've done it — " + d.a + " of our own, and every block paid for!",
            "The builders start on our house. Our own drawing, our own plot.", "Signed and paid: the plot, the permit, the lot. Now we wait for the builders."));
        return c;
    }

    /** A house's address once it stands, from its ground. */
    static String addressAt(UUID village, Villages.Village v, BlockPos anchor, Direction facing, Design d) {
        String[] a = TownLife.address(village, v.centre(), new Ledger.Building(d.structure, anchor, facing));
        return a == null ? "the plot at " + anchor.getX() + ", " + anchor.getZ() : a[0] + ", " + a[1];
    }

    // ------------------------------------------------------------------ the build

    /** Every quarter of a minute (Grow.tick, with the town's other works): the council's houses costed, and a course more on the house going up. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        Long last = TICKED.get(id);
        if (last != null && now - last < 300L && now >= last) return;
        TICKED.put(id, now);
        costCouncilHouses(level, id, day);
        Commission c = load(id);
        if (c != null && !Villages.sitesOf(id).containsKey(c.design.siteKey())) {
            Villages.holdPrivateSite(id, c.design.siteKey(), new Villages.Site(c.anchor, c.facing, 0));   // the town forgot it over a restart
        }
        if (c != null) work(level, v, c, BUDGET, false);
    }

    /**
     * Can the stores spare a course for a folk's own house? In a thriving town, yes; otherwise only while they still hold
     * what the town's own next building wants of stone and timber, and a course over: a household's house never holds up
     * the hall, the wall or the next council house the town is waiting on.
     */
    static boolean storesToSpare(ServerLevel level, Villages.Village v) {
        if (Villages.thriving(v.id())) return true;
        String next = Villages.nextProject(v.id());
        if (next == null) return true;
        int need = BuildGoal.partCounts(next, 13).getOrDefault(BuildGoal.Part.BLOCK, 0);
        return Market.stock(level, v.id(), BuildGoal::isBuildingBlock) >= need + BUDGET;
    }

    /** The words the builders are called with, and that say who is at it (TownJobs.doing). */
    static String doing(Commission c) {
        return "building " + c.names + "'s " + c.design.word;
    }

    /** Who lays the course: the hand the town sent to the work; with the works done at once (a command, the tests), the
     *  nearest grown hand with a trade that is not of the household. */
    @Nullable
    static VillageFolkEntity builder(ServerLevel level, Villages.Village v, Commission c) {
        String what = doing(c);
        VillageFolkEntity best = null;
        double near = Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby()) continue;
            if (what.equals(TownJobs.doing(f))) return f;
            if (c.members.contains(f.getUUID()) || f.stationTask() == AssistantEntity.StationTask.NONE) continue;
            double d = f.blockPosition().distSqr(c.anchor);
            if (d < near) { near = d; best = f; }
        }
        return best;
    }

    /** Is this block wood (paid in planks, or a log for a post), as Masonry pays for it? */
    static boolean wooden(Block b) {
        BlockState st = b.defaultBlockState();
        return st.is(BlockTags.PLANKS) || st.is(BlockTags.LOGS) || st.is(BlockTags.WOODEN_STAIRS) || st.is(BlockTags.WOODEN_SLABS)
            || st.is(BlockTags.WOODEN_DOORS) || st.is(BlockTags.WOODEN_FENCES) || st.is(BlockTags.FENCE_GATES) || b == Blocks.LADDER
            || b == Blocks.CHEST || b == Blocks.CRAFTING_TABLE;
    }

    /** Planks a wooden thing costs, as Masonry reckons it: a plank a board, a stair or a rung; two a door or a fence; four a
     *  bench or a gate; eight a chest. */
    static int planksFor(Block b) {
        BlockState st = b.defaultBlockState();
        if (st.is(BlockTags.WOODEN_DOORS) || st.is(BlockTags.WOODEN_FENCES)) return 2;
        if (st.is(BlockTags.FENCE_GATES) || b == Blocks.CRAFTING_TABLE) return 4;
        if (b == Blocks.CHEST) return 8;
        return 1;
    }

    /**
     * Take the makings of so many blocks out of the stores, all of them or none: stone, brick, slate and glass
     * at what each really costs (Masonry, made of their makings if need be), timber a plank a board and a log a
     * post, out of whatever wood the stores hold.
     */
    static boolean takeBlocks(ServerLevel level, Villages.Village v, Map<Block, Integer> want) {
        Map<Item, Integer> bill = new LinkedHashMap<>();
        int planks = 0, logs = 0;
        for (Map.Entry<Block, Integer> e : want.entrySet()) {
            Block b = e.getKey();
            if (b.defaultBlockState().is(BlockTags.LOGS)) logs += e.getValue();
            else if (wooden(b)) planks += e.getValue() * planksFor(b);
            else bill.merge(b == Blocks.WALL_TORCH ? Items.TORCH : b.asItem(), e.getValue(), Integer::sum);
        }
        int planksHeld = Market.stock(level, v.id(), s -> s.is(ItemTags.PLANKS)), logsHeld = Market.stock(level, v.id(), s -> s.is(ItemTags.LOGS));
        if (logsHeld < logs || planksHeld + 4 * (logsHeld - logs) < planks) return false;
        if (!bill.isEmpty() && !Masonry.takeAll(level, v, bill)) return false;
        if (logs > 0 && !Crafts.take(level, v, s -> s.is(ItemTags.LOGS), logs)) {
            for (Map.Entry<Item, Integer> e : bill.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
            return false;
        }
        if (!Crafts.usePlanks(level, v, planks)) {
            for (Map.Entry<Item, Integer> e : bill.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
            Crafts.giveBack(level, v, Items.OAK_LOG, logs);
            return false;
        }
        return true;
    }

    /** The first block of this kind the stores cannot pay for just now, for the books ("12 glass panes"); "" if they can. */
    static String shortOf(ServerLevel level, Villages.Village v, Map<Block, Integer> want) {
        for (Map.Entry<Block, Integer> e : want.entrySet()) {
            Map<Block, Integer> one = new LinkedHashMap<>();
            one.put(e.getKey(), e.getValue());
            if (!canTake(level, v, one)) return e.getValue() + " " + new ItemStack(e.getKey().asItem()).getHoverName().getString().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    static boolean canTake(ServerLevel level, Villages.Village v, Map<Block, Integer> want) {
        Map<Item, Integer> bill = new LinkedHashMap<>();
        int planks = 0, logs = 0;
        for (Map.Entry<Block, Integer> e : want.entrySet()) {
            Block b = e.getKey();
            if (b.defaultBlockState().is(BlockTags.LOGS)) logs += e.getValue();
            else if (wooden(b)) planks += e.getValue() * planksFor(b);
            else bill.merge(b == Blocks.WALL_TORCH ? Items.TORCH : b.asItem(), e.getValue(), Integer::sum);
        }
        int planksHeld = Market.stock(level, v.id(), s -> s.is(ItemTags.PLANKS)), logsHeld = Market.stock(level, v.id(), s -> s.is(ItemTags.LOGS));
        if (logsHeld < logs || planksHeld + 4 * (logsHeld - logs) < planks) return false;
        return bill.isEmpty() || Masonry.canAll(level, v, bill);
    }

    /**
     * A piece of furnishing out of the stores, and the block it is laid as: a bed (or one made there and then of
     * three wool and three planks), a rug (or one cut of the wool), a flower, a barrel (or seven planks). Null if
     * the stores have none of it.
     */
    @Nullable
    static BlockState takeThing(ServerLevel level, Villages.Village v, BuildGoal.Placement p, Design d) {
        switch (p.part()) {
            case BED -> {
                ItemStack bed = Crafts.takeOne(level, v, s -> s.is(ItemTags.BEDS));
                if (!bed.isEmpty() && Block.byItem(bed.getItem()) instanceof BedBlock bb) return bb.defaultBlockState();
                if (!bed.isEmpty()) Crafts.store(level, v, bed);
                if (Market.stock(level, v.id(), s -> s.is(ItemTags.WOOL)) >= 3 && Crafts.take(level, v, s -> s.is(ItemTags.WOOL), 3)) {
                    if (Crafts.usePlanks(level, v, 3)) return d.palette.bed().defaultBlockState();
                    Crafts.giveBack(level, v, Items.WHITE_WOOL, 3);
                }
                return null;
            }
            case CARPET -> {
                Block want = d.palette.carpet();
                if (Masonry.take(level, v, want.asItem(), 1)) return want.defaultBlockState();
                ItemStack rug = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL_CARPETS));
                return rug.isEmpty() ? null : Block.byItem(rug.getItem()).defaultBlockState();
            }
            case FLOWER -> {
                ItemStack f = Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
                return f.isEmpty() ? null : Block.byItem(f.getItem()).defaultBlockState();
            }
            case BARREL -> {
                if (Crafts.take(level, v, s -> s.is(Items.BARREL), 1) || Crafts.usePlanks(level, v, 7)) return Blocks.BARREL.defaultBlockState();
                return null;
            }
            case BOOKSHELF -> {
                if (Crafts.take(level, v, s -> s.is(Items.BOOKSHELF), 1)) return Blocks.BOOKSHELF.defaultBlockState();
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** What was taken down to make way for a cell (a sod of turf, a stone, a log), into the stores, rather than thrown away. */
    private static void clearCell(ServerLevel level, Villages.Village v, BlockPos at) {
        BlockState st = level.getBlockState(at);
        if (st.isAir() || BuildGoal.soft(st) || !st.getFluidState().isEmpty()) return;
        Item it = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) ? Items.DIRT : st.getBlock().asItem();
        if (it != Items.AIR) Crafts.store(level, v, new ItemStack(it));
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
    }

    /**
     * A course more on the house, if a hand is there (TownJobs) and the town's own buildings do not want the
     * builders first (they do, unless the town is thriving): the ground made up, then the next course's blocks
     * out of the stores and paid for into the treasury at the price agreed, and the builder paid its hours;
     * then the beds and rugs. Short of anything, the build waits for the stores. Returns what was done.
     */
    static String work(ServerLevel level, Villages.Village v, Commission c, int budget, boolean force) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (!force) {
            if (!Villages.thriving(id) && !Villages.crewsReport(id, level.getGameTime()).isEmpty()) {
                return note(id, c, "the builders are on the town's own building first");
            }
            if (!storesToSpare(level, v)) return note(id, c, "the stores' stone and timber kept for the town's own next building");
            if (!Land.areaLoaded(level, c.anchor, 9)) return "not loaded";
            if (!TownJobs.atWork(level, v, WORKS, c.anchor, doing(c))) return note(id, c, "waiting for a hand the town can spare");
        }
        // The bank took it back (three weeks behind): the council has the build now, and finishes it as one of its own.
        if (c.loan > 0 && !c.foreclosed && !Bank.state(id).loans.containsKey(c.anchor.asLong()) && c.laidCount < c.cells) {
            c.foreclosed = true;
            Ledger.addCoins(id, c.escrow);                                         // what was held for it, the council's now
            c.escrow = 0;
            Villages.tell(id, day, "the council took over the building of " + c.names + "'s " + c.design.word + " at " + c.address
                + " from the bank: it finishes it as one of its own houses");
        }
        VillageFolkEntity hand = builder(level, v, c);
        Masonry.Look look = c.lookOf();
        // The ground made up under it first, of the stores' cobblestone.
        if (!c.groundDone) {
            List<BlockPos> fill = groundCells(level, c.anchor, c.facing, c.design);
            if (!fill.isEmpty()) {
                if (!Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), fill.size())) {
                    return note(id, c, "waiting on the stores for " + fill.size() + " cobblestone to make up the ground");
                }
                for (BlockPos p : fill) level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), 2 | 16);
                c.blocksDue += fill.size() * c.prices.getOrDefault(Items.COBBLESTONE, PriceIndex.each(level, id, Items.COBBLESTONE));
            }
            c.groundDone = true;
            pay(level, v, c, hand, false);
            store(id, c);
            return "the ground made up (" + fill.size() + ")";
        }
        List<BuildGoal.Placement> all = cells(c.design, c.anchor, c.facing, c.beds);
        // Cells the look leaves out (nothing to make them of when it was agreed) are passed over, and not paid for.
        for (int i = c.laid.nextClearBit(0); i < all.size(); i = c.laid.nextClearBit(i + 1)) {
            if (furnishing(all.get(i).part()) || blockFor(look, c.design, all.get(i)) != null) break;
            c.laid.set(i);
        }
        int next = c.laid.nextClearBit(0);
        if (next >= all.size()) {
            finish(level, v, c, day);
            return "finished";
        }
        BuildGoal.Placement first = all.get(next);
        boolean things = furnishing(first.part());
        int y = first.pos().getY();
        // The course: the cells of this height still to lay (the house's, or the furnishing's), as many as a visit does.
        List<Integer> course = new ArrayList<>();
        for (int i = next; i < all.size() && course.size() < budget; i++) {
            BuildGoal.Placement p = all.get(i);
            if (c.laid.get(i)) continue;
            if (furnishing(p.part()) != things) break;
            if (!things && p.pos().getY() != y) break;
            if (!things && blockFor(look, c.design, p) == null) { c.laid.set(i); continue; }
            course.add(i);
        }
        if (course.isEmpty()) {
            c.laidCount = c.laid.cardinality();
            store(id, c);
            return "passed over what the look leaves out";
        }
        Map<BlockPos, BlockState> lay = new HashMap<>();
        List<Integer> done = new ArrayList<>();
        if (!things) {
            // By kind of block: what the stores can pay for goes in; what they cannot, waits.
            Map<Block, List<Integer>> byBlock = new LinkedHashMap<>();
            for (int i : course) byBlock.computeIfAbsent(blockFor(look, c.design, all.get(i)), k -> new ArrayList<>()).add(i);
            String shortOf = "";
            for (Map.Entry<Block, List<Integer>> e : byBlock.entrySet()) {
                Map<Block, Integer> want = new LinkedHashMap<>();
                want.put(e.getKey(), e.getValue().size());
                if (!takeBlocks(level, v, want)) {
                    if (shortOf.isEmpty()) shortOf = shortOf(level, v, want);
                    continue;
                }
                java.util.function.Function<BuildGoal.Placement, BlockState> paint = look != null ? look.painter(level) : Showcase.painter(c.design.palette);
                for (int i : e.getValue()) {
                    BuildGoal.Placement p = all.get(i);
                    BlockState st = paint.apply(p);
                    if (st == null) st = e.getKey().defaultBlockState();
                    lay.put(p.pos(), st);
                    done.add(i);
                    Item it = e.getKey() == Blocks.WALL_TORCH ? Items.TORCH : e.getKey().asItem();
                    c.blocksDue += c.prices.getOrDefault(it, PriceIndex.each(level, id, it));
                }
            }
            if (done.isEmpty()) {
                // Waited three days on the same thing: the rest of the house laid in what the stores can pay for now.
                if (c.waitSince < 0) c.waitSince = day;
                else if (day - c.waitSince >= 3) relook(level, v, c);
                return note(id, c, "waiting on the stores for " + (shortOf.isEmpty() ? "its blocks" : shortOf));
            }
            c.waitSince = -1;
            c.waiting = shortOf.isEmpty() ? "" : "waiting on the stores for " + shortOf;
        } else {
            for (int i : course) {
                BuildGoal.Placement p = all.get(i);
                BlockState st = takeThing(level, v, p, c.design);
                if (st == null) {
                    if (done.isEmpty()) return note(id, c, "waiting on the stores for " + thingWord(p.part()));
                    break;
                }
                lay.put(p.pos(), st);
                done.add(i);
                Block bl = blockFor(look, c.design, p);
                Item it = bl == null ? st.getBlock().asItem() : bl.asItem();
                c.furnishDue += c.prices.getOrDefault(it, PriceIndex.each(level, id, it));
            }
            c.waiting = "";
        }
        for (BlockPos p : lay.keySet()) {
            clearCell(level, v, p);
            BuildGoal.Placement pl = null;
            for (int i : done) if (all.get(i).pos().equals(p)) { pl = all.get(i); break; }
            if (pl != null && pl.part() == BuildGoal.Part.BED) {
                Direction lie = pl.way() == Blueprints.Way.UP ? c.facing : Blueprints.world(pl.way(), c.facing);
                clearCell(level, v, p.relative(lie));
            }
        }
        BuildGoal.stampOnly(level, c.design.drawing, c.anchor, c.facing, 13, p -> lay.get(p.pos()), p -> lay.containsKey(p.pos()));
        for (int i : done) c.laid.set(i);
        c.laidCount = c.laid.cardinality();
        pay(level, v, c, hand, false);
        store(id, c);
        if (hand != null && level.getRandom().nextInt(6) == 0) {
            FolkTalk.speak(hand, FolkTalk.pick(level.getRandom(), "Another course on " + firstName(c) + "'s " + c.design.word + ".",
                "Paid by the course, this one. Good work, honest coin.", "Mind the mortar — this one's somebody's own house."));
        }
        if (c.laid.nextClearBit(0) >= all.size()) {
            finish(level, v, c, day);
            return "finished";
        }
        return "laid " + done.size() + (things ? " things" : " blocks at y " + y);
    }

    /**
     * A build that has waited three days on the stores for the same thing: what is still to lay is laid in what the stores
     * can pay for now (brick short, dressed stone; slate short, stone or the town's own timber), each new kind of block at
     * the town's price today. The household pays no more than the blocks agreed: a builder's price is its price.
     */
    static void relook(ServerLevel level, Villages.Village v, Commission c) {
        List<BuildGoal.Placement> all = cells(c.design, c.anchor, c.facing, c.beds), rest = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (!c.laid.get(i) && !furnishing(all.get(i).part())) rest.add(all.get(i));
        if (rest.isEmpty()) return;
        Masonry.Look look = Masonry.buildIn(level, v, c.design.palette, rest, false);
        if (look == null) look = Masonry.buildIn(level, v, Showcase.OAK, rest, false);
        if (look == null) return;
        c.look = look.encode();
        for (BuildGoal.Placement p : rest) {
            Block b = look.of(Masonry.role(p));
            if (b == null) continue;
            Item it = b == Blocks.WALL_TORCH ? Items.TORCH : b.asItem();
            c.prices.putIfAbsent(it, PriceIndex.each(level, v.id(), it));
        }
        c.waitSince = -1;
        Villages.tell(v.id(), level.getDayTime() / 24000L, "the stores could not run to " + c.names + "'s " + c.design.word
            + " as drawn: the rest of it goes up in what they have");
    }

    private static String firstName(Commission c) {
        int and = c.names.indexOf(" and ");
        return and > 0 ? c.names.substring(0, and) : c.names;
    }

    private static String thingWord(BuildGoal.Part part) {
        return switch (part) {
            case BED -> "a bed (or three wool and three planks)";
            case CARPET -> "a rug (or the wool for one)";
            case FLOWER -> "flowers";
            case BARREL -> "a barrel (or seven planks)";
            default -> "its furnishing";
        };
    }

    private static String note(UUID village, Commission c, String why) {
        if (!why.equals(c.waiting)) {
            c.waiting = why;
            store(village, c);
        }
        return why;
    }

    /**
     * The coin due for what has been laid, out of what is held for the build: the blocks and the furnishing into
     * the treasury at the agreed price (the folk's own coin spent in town: Economy), and the builders' hours, a
     * share of the labour for each block laid, into the purse of the hand that laid them. At the end the last of
     * each, to the coin agreed.
     */
    static void pay(ServerLevel level, Villages.Village v, Commission c, @Nullable VillageFolkEntity hand, boolean last) {
        UUID id = v.id();
        int blocksDue = last ? Math.min(c.blocks, (int) Math.round(c.blocksDue)) : Math.min(c.blocks, (int) Math.floor(c.blocksDue + 1e-9));
        int furnishDue = last ? Math.min(c.furnish, (int) Math.round(c.furnishDue)) : Math.min(c.furnish, (int) Math.floor(c.furnishDue + 1e-9));
        int laid = c.laidCount + (c.groundDone ? c.ground : 0), of = Math.max(1, c.cells + c.ground);
        int labourDue = last && c.laidCount >= c.cells ? c.labour : (int) Math.floor(c.labour * (double) laid / of + 1e-9);
        if (last && c.laidCount >= c.cells) {
            blocksDue = c.blocks;
            furnishDue = c.furnish;
        }
        int toTreasury = Math.max(0, blocksDue - c.blocksPaid) + Math.max(0, furnishDue - c.furnishPaid);
        int toHands = Math.max(0, labourDue - c.labourPaid);
        if (c.foreclosed) {
            // The council's own build now: nothing more is owed it by the household.
            c.blocksPaid = Math.max(c.blocksPaid, blocksDue);
            c.furnishPaid = Math.max(c.furnishPaid, furnishDue);
            c.labourPaid = Math.max(c.labourPaid, labourDue);
            return;
        }
        int want = toTreasury + toHands;
        if (want > c.escrow) {
            // Held too little (a slope that wanted more ground than was quoted): the household makes it up from its purses.
            List<VillageFolkEntity> household = new ArrayList<>();
            for (UUID u : c.members) { VillageFolkEntity f = Homes.loaded(id, u); if (f != null) household.add(f); }
            int got = Homes.take(household, new Homes.Home(BlockPos.ZERO, "house"), want - c.escrow);
            c.escrow += got;
        }
        int k = Math.min(toTreasury, c.escrow);
        if (k > 0) {
            c.escrow -= k;
            Ledger.addCoins(id, k);
            Economy.spentInTown(id, k);
            int b = Math.min(k, Math.max(0, blocksDue - c.blocksPaid));
            c.blocksPaid += b;
            c.furnishPaid += k - b;
        }
        int h = Math.min(toHands, c.escrow);
        if (h > 0) {
            c.escrow -= h;
            c.labourPaid += h;
            if (hand != null) {
                hand.earn(h);
                c.builders.merge(hand.getUUID(), h, Integer::sum);
            } else {
                Ledger.addCoins(id, h);                                            // no hand to be found: the council's crews, the treasury's
            }
        }
    }

    /**
     * Built: the last of the bill paid, the house on the town's books, and the household moves in as its owner (on
     * the bank's mortgage, if it has one; what was held and not spent pays it down, or goes back to the purses). A
     * house it owned before is sold at the market; a rented one goes back to the council to let. The chronicle, the
     * gazette and the morning's assembly have it.
     */
    static void finish(ServerLevel level, Villages.Village v, Commission c, long day) {
        UUID id = v.id();
        pay(level, v, c, builder(level, v, c), true);
        Ledger.built(id, c.design.structure, c.anchor, c.facing);
        if (c.design.grown) Ledger.grow(id, c.anchor);
        Villages.privateSiteDone(id, c.design.siteKey(), false);                 // [econ-housing] its lot built on
        Ledger.forget(id, BUILD);
        Homes.enrol(id);
        Homes.Home h = Homes.homes(id).get(c.anchor.asLong());
        int total = c.total();
        // The household as it is now: the lead (or whoever of it is left), its partner and their children.
        List<VillageFolkEntity> household = new ArrayList<>();
        VillageFolkEntity lead = Homes.loaded(id, c.lead);
        if (lead == null) for (UUID u : c.members) { lead = Homes.loaded(id, u); if (lead != null) break; }
        if (lead != null) {
            for (List<VillageFolkEntity> hh : households(id)) if (hh.contains(lead)) { household = hh; break; }
        }
        Ledger.note(id, COST + c.anchor.asLong(), new Cost(c.design.drawing, Villages.ageOf(id).ordinal(), c.blocksPaid, c.labourPaid,
            c.furnishPaid, day, c.blocksPaid + c.labourPaid + c.furnishPaid, true).encode());
        recordDone(id, c, day);
        if (h == null) {                                                         // not on the homes' books after all: its coin back
            if (c.escrow > 0) {
                if (lead != null) lead.earn(c.escrow);
                else Ledger.addCoins(id, c.escrow);
            }
            return;
        }
        if (c.foreclosed || household.isEmpty()) {
            // The council's now: let like any of its houses.
            h.tenure = Homes.Tenure.RENTED;
            h.price = 0;
            h.rent = Homes.rent(id, h);
            Homes.save(id, h);
            if (c.escrow > 0) Ledger.addCoins(id, c.escrow);
            Villages.tell(id, day, c.design.a + " went up at " + c.address + ", the council's to let");
            return;
        }
        // What was held and not spent: toward the mortgage, then back to the purses.
        int left = c.escrow;
        if (left > 0) left -= Bank.payDown(id, c.anchor.asLong(), left);
        if (left > 0) {
            List<VillageFolkEntity> grown = Homes.grown(household);
            for (int i = 0; i < grown.size(); i++) grown.get(i).earn(left / grown.size() + (i < left % grown.size() ? 1 : 0));
        }
        // The homes it is leaving.
        java.util.Set<Homes.Home> was = new java.util.LinkedHashSet<>();
        for (VillageFolkEntity f : household) {
            Homes.Home o = Homes.homeOf(id, f.getUUID());
            if (o != null && o != h) was.add(o);
        }
        h.tenure = Homes.Tenure.OWNED;
        h.price = total;
        h.rent = 0;
        h.owed = 0;
        h.saved = 0;
        h.since = day;
        Homes.moveIn(level, v, h, household, day, true);
        Ledger.note(id, OWN + c.anchor.asLong(), c.design.name() + "|" + c.lead + "|" + total + "|" + day);
        for (Homes.Home o : was) {
            if (!o.members.isEmpty() || Homes.seat(o) || o.tenure == Homes.Tenure.PLAYER) continue;
            if (o.tenure == Homes.Tenure.OWNED) sell(level, v, o, lead, day, "moving into the house it had built");
            else Homes.vacate(id, o, lead);
        }
        String builders = builderNames(id, c);
        Villages.tell(id, day, c.names + " moved into the " + c.design.word + " they had built at " + c.address + ": " + total + Homes.coins(total)
            + " all told, every block of it paid for (blocks " + c.blocks + ", labour " + c.labour + (builders.isEmpty() ? "" : " to " + builders)
            + ", plot " + c.plot + ", furnishing " + c.furnish + ", permit " + c.permit + ")");
        Market.assemblyNews(id, c.names + " have built themselves " + c.design.a + " at " + c.address + ", and moved in. Paid for, block by block.");
        for (VillageFolkEntity f : Homes.grown(household)) f.persona().remember(day, "we moved into the " + c.design.word + " we had built, " + total + " coins", 8);
        FolkTalk.speak(lead, FolkTalk.pick(level.getRandom(), "Our own house, built to our own drawing — and every block paid for!",
            "Home. Our own. I'll never tire of saying it.", "We built it. Well — the builders did. We paid!"));
    }

    private static String builderNames(UUID village, Commission c) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : c.builders.entrySet()) {
            VillageFolkEntity f = Homes.loaded(village, e.getKey());
            out.add((f == null ? "a hand" : f.displayNameCap()) + " " + e.getValue());
        }
        return String.join(", ", out);
    }

    /** The houses folk have had built, kept for the books: the last five. */
    private static void recordDone(UUID village, Commission c, long day) {
        String was = Ledger.note(village, DONE);
        List<String> rows = new ArrayList<>();
        rows.add(day + "\t" + c.design.name() + "\t" + clean(c.names) + "\t" + clean(c.address) + "\t" + c.total() + "\t" + c.blocks + "\t" + c.labour
            + "\t" + c.plot + "\t" + c.furnish + "\t" + c.permit + "\t" + c.loan + "\t" + c.lead + "\t" + c.anchor.asLong());
        if (was != null && !was.isEmpty()) for (String r : was.split("\n")) if (rows.size() < 5) rows.add(r);
        Ledger.note(village, DONE, String.join("\n", rows));
    }

    static List<String[]> done(UUID village) {
        List<String[]> out = new ArrayList<>();
        String s = Ledger.note(village, DONE);
        if (s == null || s.isEmpty()) return out;
        for (String r : s.split("\n")) {
            String[] p = r.split("\t", -1);
            if (p.length >= 13) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ selling a house

    /** The least a house will fetch: what the council pays for it, four-fifths of the going price. */
    public static int saleFloor(UUID village, Homes.Home h) {
        return (int) Math.round(Homes.price(village, h) * COUNCIL_PERCENT / 100.0);
    }

    /**
     * A house its owners have left (moving up, into a partner's house, into the leader's hall, into a house they
     * had built), sold. At the going price (Homes.price: what it cost to build, as the market stands, its place
     * and its furnishing) to a household that wants a house and can pay for it — those waiting for one first,
     * then the tenants saving to buy, the one with the most to hand first — out of what it has, and the bank's
     * loan for the rest if the house carries no mortgage; else back to the council, at four-fifths of it, out of
     * the treasury as far as it can spare. The seller's mortgage on it is paid off out of the price; the rest is
     * the seller's. The sale is kept. Returns what the seller had.
     */
    public static int sell(ServerLevel level, Villages.Village v, Homes.Home h, @Nullable VillageFolkEntity seller, long day, String why) {
        UUID id = v.id();
        if (h.saved > 0) {
            if (seller != null) seller.earn(h.saved);
            else Ledger.addCoins(id, h.saved);
            h.saved = 0;
        }
        int price = Homes.price(id, h);
        long key = h.anchor.asLong();
        int owed = owedOn(id, key);
        String where = Homes.address(id, v, h);
        // A buyer at the market.
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isShowcase() && !f.isHired()) folk.add(f);
        List<List<VillageFolkEntity>> buyers = new ArrayList<>();
        for (List<VillageFolkEntity> hh : Homes.waiting(id, folk, day)) buyers.add(hh);
        for (Homes.Home o : Homes.homes(id).values()) {
            if (o == h || o.tenure != Homes.Tenure.RENTED || Homes.seat(o) || o.members.isEmpty()) continue;
            List<VillageFolkEntity> hh = Homes.loadedMembers(id, o);
            if (!hh.isEmpty() && Homes.wish(id, o, hh).yes()) buyers.add(hh);
        }
        int beds = level.isLoaded(h.anchor) ? Homes.bedsIn(level, id, h).size() : 4;
        List<VillageFolkEntity> buyer = null;
        // A house worth less than its mortgage (the market fallen since it was bought) goes back to the council, which takes on
        // what its seller cannot pay off (Bank.reconcile): a buyer never takes on another's debt with the house.
        if (owed > price) buyers.clear();
        for (List<VillageFolkEntity> hh : buyers) {
            if (hh.isEmpty() || seller != null && hh.contains(seller) || Homes.grown(hh).size() > Math.max(1, beds)) continue;
            if (Homes.earners(hh) == 0) continue;
            int cash = cashOf(id, hh);
            boolean can = cash >= price || owed == 0 && means(level, v, hh, price).can();
            if (!can) continue;
            if (buyer == null || cashOf(id, buyer) < cash) buyer = hh;
        }
        int proceeds;
        String kind;
        String buyerNames = "";
        if (buyer != null && buy(level, v, h, buyer, price, day)) {
            proceeds = price;
            kind = "market";
            buyerNames = Homes.names(buyer);
        } else {
            int floor = (int) Math.round(price * COUNCIL_PERCENT / 100.0);
            proceeds = Ledger.takeCoins(id, Math.min(floor, Math.max(0, Ledger.coins(id) - Market.wageBill(id))));
            Economy.spent(id, proceeds);
            kind = "council";
            Homes.vacate(id, h, null);
            Ledger.forget(id, OWN + key);
        }
        // The seller's mortgage paid off out of the price; the rest is its own.
        int toBank = owed > 0 ? Bank.payDown(id, key, Math.min(owed, proceeds)) : 0;
        if (seller != null && proceeds - toBank > 0) seller.earn(proceeds - toBank);
        else if (seller == null && proceeds - toBank > 0) Ledger.addCoins(id, proceeds - toBank);
        String sellerName = seller == null ? "the last of its household" : seller.displayNameCap();
        recordSale(id, day, where, proceeds, kind, sellerName, buyerNames, price);
        if (kind.equals("market")) {
            Villages.tell(id, day, sellerName + " sold " + where + " to " + buyerNames + " for " + price + Homes.coins(price) + ", the going price ("
                + why + ")" + (toBank > 0 ? "; " + toBank + " of it paid off the mortgage" : ""));
            if (seller != null) seller.persona().remember(day, "I sold " + where + " for " + price + " coins", 5);
        } else {
            Villages.tell(id, day, sellerName + " sold " + where + " back to the council for " + proceeds + Homes.coins(proceeds) + " ("
                + COUNCIL_PERCENT + " in the hundred of the going " + price + ": nobody could buy it; " + why + ")");
        }
        return proceeds - toBank;
    }

    /** A household buys a house at a price: out of its purses, what it put by and its savings, and the bank's loan if it needs
     *  it; it moves in and owns it. False (nothing taken) if it cannot after all. */
    static boolean buy(ServerLevel level, Villages.Village v, Homes.Home h, List<VillageFolkEntity> household, int price, long day) {
        UUID id = v.id();
        List<VillageFolkEntity> grown = Homes.grown(household);
        Homes.Home old = Homes.homeOf(id, household.get(0).getUUID());
        int got = 0, fromSaved = 0;
        if (old != null && old.tenure == Homes.Tenure.RENTED && !Homes.seat(old) && old.saved > 0) {
            fromSaved = Math.min(old.saved, price);
            old.saved -= fromSaved;
            Homes.save(id, old);
            got += fromSaved;
        }
        Map<VillageFolkEntity, Integer> fromPurse = new LinkedHashMap<>();
        grown.sort((a, b) -> Integer.compare(b.purse(), a.purse()));
        for (VillageFolkEntity f : grown) {
            int k = Math.min(price - got, Math.max(0, f.purse() - Homes.LIVE_ON));
            if (k > 0 && f.spend(k)) { got += k; fromPurse.put(f, k); }
            if (got >= price) break;
        }
        if (got < price) {
            // Its savings at the bank, then the bank's loan, on this house (Bank.lend, as for any house).
            h.price = price;
            h.saved = got;
            Bank.lend(level, v, h, household, day);
            if (h.saved < price) {
                int back = h.saved;
                for (Map.Entry<VillageFolkEntity, Integer> e : fromPurse.entrySet()) { e.getKey().earn(e.getValue()); back -= e.getValue(); }
                if (old != null && fromSaved > 0) { old.saved += fromSaved; Homes.save(id, old); back -= fromSaved; }
                if (back > 0 && !grown.isEmpty()) grown.get(0).earn(back);
                h.saved = 0;
                return false;
            }
            got = h.saved;
        }
        h.saved = 0;
        if (got > price && !grown.isEmpty()) grown.get(0).earn(got - price);
        List<Homes.Home> left = new ArrayList<>();
        for (VillageFolkEntity f : household) {
            Homes.Home o = Homes.homeOf(id, f.getUUID());
            if (o != null && o != h && !left.contains(o)) left.add(o);
        }
        h.tenure = Homes.Tenure.OWNED;
        h.price = price;
        h.rent = 0;
        h.owed = 0;
        h.since = day;
        Homes.moveIn(level, v, h, household, day, true);
        for (Homes.Home o : left) if (o.members.isEmpty() && o.tenure == Homes.Tenure.RENTED && !Homes.seat(o)) Homes.vacate(id, o, grown.isEmpty() ? null : grown.get(0));
        for (VillageFolkEntity f : grown) f.persona().remember(day, "we bought " + Homes.address(id, v, h) + " for " + price + " coins, the going price", 7);
        if (!grown.isEmpty()) FolkTalk.speak(grown.get(0), FolkTalk.pick(level.getRandom(), "Bought it at the going price — and worth every coin.",
            "Our own house at last. Somebody else's first, but ours now."));
        return true;
    }

    /** What is still owed the bank on the house on this ground (0: none). */
    static int owedOn(UUID village, long anchor) {
        if (!Bank.open(village)) return 0;
        Bank.Loan l = Bank.state(village).loans.get(anchor);
        return l == null ? 0 : l.owed();
    }

    private static void recordSale(UUID village, long day, String where, int price, String kind, String seller, String buyer, int going) {
        String was = Ledger.note(village, SALES);
        List<String> rows = new ArrayList<>();
        rows.add(day + "\t" + clean(where) + "\t" + price + "\t" + kind + "\t" + clean(seller) + "\t" + clean(buyer) + "\t" + going);
        if (was != null && !was.isEmpty()) for (String r : was.split("\n")) if (rows.size() < 12) rows.add(r);
        Ledger.note(village, SALES, String.join("\n", rows));
    }

    /** The sales kept, newest first: {day, where, price, kind (market/council), seller, buyer, the going price}. */
    public static List<String[]> sales(UUID village) {
        List<String[]> out = new ArrayList<>();
        String s = Ledger.note(village, SALES);
        if (s == null || s.isEmpty()) return out;
        for (String r : s.split("\n")) {
            String[] p = r.split("\t", -1);
            if (p.length >= 7) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ what a player sees

    /**
     * The card's line about a folk's own house: going up ("its own family house going up at No. 6, Elm Street:
     * 120 of 290 laid; the bill 142c — blocks 61, ..."), built ("built its own villa on day 41, 162c all told"),
     * or saved for ("saving for a cottage of its own: about 66c all told, 34 to hand ..."). Empty otherwise.
     */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby()) return "";
        Commission c = load(id);
        if (c != null && c.members.contains(f.getUUID())) {
            int pct = c.cells == 0 ? 0 : 100 * c.laidCount / c.cells;
            return "Its own " + c.design.word + " going up at " + c.address + ": " + c.laidCount + " of " + c.cells + " blocks laid (" + pct + "%)"
                + (c.waiting.isEmpty() ? "" : ", " + c.waiting) + ". The bill " + c.total() + "c: blocks " + c.blocks + ", the builders' labour "
                + c.labour + ", the plot " + c.plot + ", furnishing " + c.furnish + ", the permit " + c.permit + "; "
                + (c.loan > 0 ? c.down + " down and " + c.loan + " on the bank's mortgage (" + c.weekly + " a week)" : "paid for outright")
                + "; " + (c.blocksPaid + c.furnishPaid + c.labourPaid + c.plot + c.permit) + "c paid out so far.";
        }
        Homes.Home h = Homes.homeOf(id, f.getUUID());
        if (h != null && isPrivate(id, h.anchor)) {
            for (String[] d : done(id)) {
                if (Long.parseLong(d[12]) != h.anchor.asLong()) continue;
                return "Built its own " + Design.valueOf(d[1]).word + " on day " + d[0] + ": " + d[4] + "c all told (blocks " + d[5] + ", labour " + d[6]
                    + ", plot " + d[7] + ", furnishing " + d[8] + ", permit " + d[9] + ")" + (Homes.parse(d[10]) > 0 ? ", " + d[10] + " of it on the bank's mortgage" : "")
                    + "; worth " + Homes.price(id, h) + "c on the market today.";
            }
            return "Lives in a house it had built for itself; worth " + Homes.price(id, h) + "c on the market today.";
        }
        VillageFolkEntity lead = f;
        if (planOf(id, lead.getUUID()) == null) {
            UUID p = f.life().partner();
            if (p != null && planOf(id, p) != null) {
                VillageFolkEntity o = Homes.loaded(id, p);
                if (o != null) lead = o;
            }
        }
        String[] plan = planOf(id, lead.getUUID());
        if (plan == null) return "";
        Design d;
        try { d = Design.valueOf(plan[0]); } catch (IllegalArgumentException e) { return ""; }
        return "Saving to build " + d.a + " of its own: about " + plan[1] + "c all told, " + plan[2] + " to hand — " + plan[4] + ".";
    }

    /**
     * What a folk adds when asked where it lives (Homes.talk), in its own words: " And we're having a family house
     * built at No. 6, Elm Street — near half of it up, every block paid for." / " We're saving to build a cottage of
     * our own: 34 of about 66 coins." Empty if neither.
     */
    public static String talkTail(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby()) return "";
        Commission c = load(id);
        if (c != null && c.members.contains(f.getUUID())) {
            int pct = c.cells == 0 ? 0 : 100 * c.laidCount / c.cells;
            String up = pct < 10 ? "the footing's going in" : pct < 45 ? "the walls are going up" : pct < 90 ? "past half of it up" : "nearly done";
            return " And we're having " + c.design.a + " of our own built at " + c.address + " — " + up + ", every block paid for"
                + (c.waiting.startsWith("waiting on the stores") ? ", though the builders wait on the stores just now" : "") + ".";
        }
        String[] plan = planOf(id, f.getUUID());
        if (plan == null && f.life().partner() != null) plan = planOf(id, f.life().partner());
        if (plan == null) return "";
        try {
            return " We're saving to build " + Design.valueOf(plan[0]).a + " of our own: " + plan[2] + " of about " + plan[1] + " coins.";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** "1.12 (up 4 in the hundred this week)". */
    public static String indexWords(UUID village) {
        int w = weekChange(village);
        return String.format(Locale.ROOT, "%.2f", index(village)) + (w == 0 ? " (steady this week)" : " (" + (w > 0 ? "up " : "down ") + Math.abs(w)
            + " in the hundred this week)");
    }

    /** For /village house: the market's lines (the index, prices and rents, the waiting against the empty, the house going up,
     *  the last sales). */
    public static List<String> lines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        int[] m = market(village);
        int house = Homes.priceForTests(village, "house"), rent = Homes.rentForTests(village, "house");
        int[] book = councilBook(village);
        out.add("The housing market: index " + indexWords(village) + "; a house " + house + "c, its rent " + rent + "c a day; " + m[0]
            + " households waiting and " + m[1] + " outgrowing their homes, against " + m[3] + " empty"
            + (book[0] > 0 ? "; the council's " + book[0] + " houses cost the treasury " + (book[1] + book[2]) + "c to build (blocks " + book[1]
                + ", labour " + book[2] + ")" : ""));
        Commission c = load(village);
        if (c != null) {
            out.add("Going up: " + c.names + "'s " + c.design.word + " at " + c.address + ", " + c.laidCount + " of " + c.cells + " blocks laid"
                + (c.waiting.isEmpty() ? "" : " (" + c.waiting + ")") + "; the bill " + c.total() + "c (blocks " + c.blocks + ", labour " + c.labour
                + ", plot " + c.plot + ", furnishing " + c.furnish + ", permit " + c.permit + ")" + (c.loan > 0 ? ", " + c.loan + " on a mortgage" : ""));
        }
        for (String[] d : done(village)) {
            if (out.size() >= 4) break;
            out.add("Built for itself: " + d[2] + "'s " + Design.valueOf(d[1]).word + " at " + d[3] + ", day " + d[0] + ", " + d[4] + "c");
        }
        List<String[]> s = sales(village);
        for (int i = 0; i < Math.min(3, s.size()); i++) {
            String[] r = s.get(i);
            out.add("Sold day " + r[0] + ": " + r[1] + " for " + r[2] + "c" + (r[3].equals("council") ? " to the council (the going price " + r[6] + ")"
                : " to " + r[5]));
        }
        return out;
    }

    /** The bill in full, a line an item, for /village house market. */
    static List<String> billLines(Commission c) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Item, Double> e : c.prices.entrySet()) {
            out.add(new ItemStack(e.getKey()).getHoverName().getString() + " at " + String.format(Locale.ROOT, "%.2f", e.getValue()) + "c");
        }
        return out;
    }

    /**
     * The Homes page's market (Annals): the index and its fortnight, the week's move, prices and rents for each kind
     * of house, the waiting against the empty, the council's houses on the treasury's books, the house going up
     * with its bill and progress, the houses folk have built, the plans, and the last sales.
     */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        out.putInt("index", (int) Math.round(index(village) * 1000));
        out.putInt("week", weekChange(village));
        out.putIntArray("history", history(village));
        int[] m = market(village);
        out.putInt("waiting", m[0]);
        out.putInt("outgrowing", m[1]);
        out.putInt("saving", m[2]);
        out.putInt("empty", m[3]);
        out.putInt("demand10", m[5]);
        out.putInt("supply10", m[6]);
        out.putInt("house_price", Homes.priceForTests(village, "house"));
        out.putInt("house_rent", Homes.rentForTests(village, "house"));
        out.putInt("manor_price", Homes.priceForTests(village, "manor"));
        out.putInt("manor_rent", Homes.rentForTests(village, "manor"));
        out.putBoolean("open", open(village));
        int[] book = councilBook(village);
        out.putInt("council_houses", book[0]);
        out.putInt("council_blocks", book[1]);
        out.putInt("council_labour", book[2]);
        Commission c = load(village);
        if (c != null) {
            CompoundTag b = new CompoundTag();
            b.putString("design", c.design.word);
            b.putString("names", c.names);
            b.putString("address", c.address);
            b.putInt("total", c.total());
            b.putInt("blocks", c.blocks);
            b.putInt("labour", c.labour);
            b.putInt("plot", c.plot);
            b.putInt("furnish", c.furnish);
            b.putInt("permit", c.permit);
            b.putInt("laid", c.laidCount);
            b.putInt("cells", c.cells);
            b.putInt("loan", c.loan);
            b.putInt("down", c.down);
            b.putInt("weekly", c.weekly);
            b.putInt("paid", c.blocksPaid + c.furnishPaid + c.labourPaid + c.plot + c.permit);
            b.putString("waiting", c.waiting);
            b.putString("builders", builderNames(village, c));
            b.putLong("day", c.started);
            ListTag lines = new ListTag();
            for (String s : billLines(c)) lines.add(net.minecraft.nbt.StringTag.valueOf(s));
            b.put("prices", lines);
            out.put("build", b);
        }
        ListTag built = new ListTag();
        for (String[] d : done(village)) {
            CompoundTag r = new CompoundTag();
            r.putLong("day", Homes.parse(d[0]));
            r.putString("design", Design.valueOf(d[1]).word);
            r.putString("names", d[2]);
            r.putString("address", d[3]);
            r.putInt("total", Homes.parse(d[4]));
            built.add(r);
        }
        out.put("built", built);
        ListTag sales = new ListTag();
        for (String[] s : sales(village)) {
            CompoundTag r = new CompoundTag();
            r.putLong("day", Homes.parse(s[0]));
            r.putString("where", s[1]);
            r.putInt("price", Homes.parse(s[2]));
            r.putString("kind", s[3]);
            r.putString("seller", s[4]);
            r.putString("buyer", s[5]);
            r.putInt("going", Homes.parse(s[6]));
            sales.add(r);
        }
        out.put("sales", sales);
        ListTag plans = new ListTag();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith(PLAN) || plans.size() >= 6) continue;
            String[] p = e.getValue().split("\t", -1);
            if (p.length < 5) continue;
            VillageFolkEntity f;
            try { f = Homes.loaded(village, UUID.fromString(e.getKey().substring(PLAN.length()))); } catch (IllegalArgumentException x) { continue; }
            if (f == null) continue;
            CompoundTag r = new CompoundTag();
            r.putString("name", f.displayNameCap());
            try { r.putString("design", Design.valueOf(p[0]).word); } catch (IllegalArgumentException x) { continue; }
            r.putInt("total", Homes.parse(p[1]));
            r.putInt("have", Homes.parse(p[2]));
            r.putString("why", p[4]);
            plans.add(r);
        }
        out.put("plans", plans);
        out.putString("line", lines(level, village).get(0));
        return out;
    }

    // ------------------------------------------------------------------ commands

    /**
     * /village house market: the market's lines and, the house going up, its bill item by item. For operators and
     * the pictures: "custom" has the best-placed household that wants a house commission one now (an operator's
     * grant from the treasury making up what it lacks, said so in the chronicle); "build N" lays N blocks of it
     * now, the stores paying, as the builders would; "day" runs the morning's market.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("market").executes(ctx -> marketCmd(ctx))
            .then(Commands.literal("custom").requires(src -> src.hasPermission(2)).executes(HousingMarket::customCmd))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(HousingMarket::stageCmd))
            .then(Commands.literal("build").requires(src -> src.hasPermission(2))
                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 2000))
                    .executes(ctx -> buildCmd(ctx, IntegerArgumentType.getInteger(ctx, "blocks")))))
            .then(Commands.literal("day").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                Ledger.note(v.id(), INDEX, index(v.id()) + "|-1|" + joined(history(v.id())));
                reckon(level, v, level.getDayTime() / 24000L);
                ctx.getSource().sendSuccess(() -> Component.literal("HOUSING " + String.join(" | ", lines(level, v.id()))), false);
                return 1;
            }));
    }

    private static String joined(int[] h) {
        StringBuilder sb = new StringBuilder();
        for (int i : h) sb.append(sb.length() == 0 ? "" : ",").append(i);
        return sb.toString();
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE);
        if (v == null) ctx.getSource().sendFailure(Component.literal("There's no village here."));
        return v;
    }

    private static int marketCmd(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        List<String> out = new ArrayList<>(lines(level, v.id()));
        Commission c = load(v.id());
        if (c != null) out.add("Its bill, item by item: " + String.join(", ", billLines(c)));
        ctx.getSource().sendSuccess(() -> Component.literal("HOUSING " + String.join(" | ", out)), false);
        return out.size();
    }

    private static int customCmd(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        Commission c = load(v.id());
        if (c == null) {
            // The household with the most to hand, made able to pay for its first choice by a grant from the treasury.
            List<List<VillageFolkEntity>> all = households(v.id());
            all.sort(Comparator.comparingInt((List<VillageFolkEntity> h) -> -cashOf(v.id(), h)));
            for (List<VillageFolkEntity> hh : all) {
                if (Homes.earners(hh) == 0) continue;
                Homes.Home home = Homes.homeOf(v.id(), hh.get(0).getUUID());
                if (home != null && Homes.seat(home)) continue;
                Design d = taste(hh).get(0);
                int total = estimate(level, v, d, hh.size()) * 13 / 10;
                int lacks = Math.max(0, total - cashOf(v.id(), hh));
                int grant = Ledger.takeCoins(v.id(), lacks);
                hh.get(0).earn(grant);
                if (grant > 0) Villages.tell(v.id(), day, "the council granted " + hh.get(0).displayNameCap() + " " + grant + " coins toward a house of its own");
                c = commission(level, v, hh, d, day);
                if (c != null) break;
            }
        }
        Commission made = c;
        String said = made == null ? "Nobody could commission a house: " + Villages.lotReport(v.id())
            : "BUILD " + made.names + " " + made.design.word + " at " + made.anchor.getX() + " " + made.anchor.getY() + " " + made.anchor.getZ()
            + " facing " + made.facing.getName() + " bill " + made.total() + " (" + made.blocks + "/" + made.labour + "/" + made.plot + "/" + made.furnish
            + "/" + made.permit + ") lead " + made.lead;
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return made == null ? 0 : 1;
    }

    /**
     * For the pictures (an operator's): a villa commissioned on ground made ready for it at the spot (or the first spot
     * eastward clear of the town's buildings): a plot fifteen across levelled, the makings of the villa delivered into the
     * town's stores and what the household lacks of the bill granted to it (out of the treasury as far as it can, then
     * the operator's own), every grant said in the chronicle; then commissioned and paid for as any house is, and laid by
     * "build". Says where it stands and where its owner's door is.
     */
    private static int stageCmd(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (load(id) != null) {
            ctx.getSource().sendFailure(Component.literal("A house is going up already: " + String.join(" | ", lines(level, id))));
            return 0;
        }
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        // Clear of anything the town has built or is building.
        for (int tries = 0; tries < 12; tries++) {
            boolean clear = true;
            for (Ledger.Building b : Ledger.buildings(id)) {
                if (Math.abs(b.anchor().getX() - at.getX()) <= 16 && Math.abs(b.anchor().getZ() - at.getZ()) <= 16) { clear = false; break; }
            }
            for (Villages.Site s : Villages.sitesOf(id).values()) {
                if (Math.abs(s.anchor().getX() - at.getX()) <= 16 && Math.abs(s.anchor().getZ() - at.getZ()) <= 16) { clear = false; break; }
            }
            if (clear) break;
            at = at.east(16);
        }
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        int y = BuildGoal.groundTop(level, at.getX(), at.getZ());
        BlockPos anchor = new BlockPos(at.getX(), y, at.getZ());
        Showcase.stage(level, anchor.getX() - 7, anchor.getX() + 7, anchor.getZ() - 7, anchor.getZ() + 7, y);
        // The household with the most to hand.
        List<List<VillageFolkEntity>> all = households(id);
        all.sort(Comparator.comparingInt((List<VillageFolkEntity> h) -> -cashOf(id, h)));
        List<VillageFolkEntity> household = null;
        for (List<VillageFolkEntity> hh : all) {
            Homes.Home home = Homes.homeOf(id, hh.get(0).getUUID());
            if (Homes.earners(hh) == 0 || home != null && Homes.seat(home)) continue;
            household = hh;
            break;
        }
        if (household == null) {
            ctx.getSource().sendFailure(Component.literal("Nobody in " + Villages.name(id) + " earns a wage to build a house with."));
            return 0;
        }
        Design d = Design.VILLA;
        // The makings, delivered: what the villa's own look is laid in, a tenth over.
        Map<Item, Integer> makings = new LinkedHashMap<>();
        int planks = 0;
        java.util.function.Function<BuildGoal.Placement, BlockState> paint = Showcase.painter(d.palette);
        for (BuildGoal.Placement p : cells(d, anchor, Direction.NORTH, bedsWanted(d, household.size()))) {
            BlockState st = paint.apply(p);
            if (st == null) continue;
            Block bl = st.getBlock();
            if (!furnishing(p.part()) && wooden(bl) && !st.is(BlockTags.LOGS)) planks += planksFor(bl);
            else makings.merge(bl.asItem(), 1, Integer::sum);
        }
        makings.merge(Items.OAK_PLANKS, planks, Integer::sum);
        int delivered = 0;
        for (Map.Entry<Item, Integer> e : makings.entrySet()) {
            int n = e.getValue() + e.getValue() / 10 + 1;
            Crafts.giveBack(level, v, e.getKey(), n);
            delivered += n;
        }
        Villages.tell(id, day, "a delivery of " + delivered + " blocks for a villa came into the stores (an operator's)");
        Villages.forgetStores(id);
        Bill b = quote(level, v, d, anchor, Direction.NORTH, household.size());
        int lacks = Math.max(0, b.total() * 11 / 10 - cashOf(id, household));
        int fromTreasury = Ledger.takeCoins(id, Math.min(lacks, Math.max(0, Ledger.coins(id) - Market.wageBill(id))));
        VillageFolkEntity lead = household.get(0);
        lead.earn(lacks);                                                       // the treasury's part, and the operator's for the rest
        if (lacks > 0) Villages.tell(id, day, lead.displayNameCap() + " was granted " + lacks + " coins toward a house of its own ("
            + fromTreasury + " from the treasury, " + (lacks - fromTreasury) + " an operator's)");
        Commission c = commission(level, v, household, d, day, new Villages.Site(anchor, Direction.NORTH, 0));
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal("The household could not commission it after all: " + planOfWhy(id, lead)));
            return 0;
        }
        BlockPos door = anchor.south(4);
        for (BuildGoal.Placement p : BuildGoal.plan(d.drawing, anchor, Direction.NORTH, 13)) {
            if (p.part() == BuildGoal.Part.DOOR) { door = p.pos().south(2); break; }
        }
        String said = "BUILD " + c.names + " " + c.design.word + " at " + c.anchor.getX() + " " + c.anchor.getY() + " " + c.anchor.getZ()
            + " facing " + c.facing.getName() + " bill " + c.total() + " (" + c.blocks + "/" + c.labour + "/" + c.plot + "/" + c.furnish
            + "/" + c.permit + ") lead " + c.lead + " DOOR " + door.getX() + " " + door.getY() + " " + door.getZ();
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    private static String planOfWhy(UUID village, VillageFolkEntity lead) {
        String[] p = planOf(village, lead.getUUID());
        return p == null ? "no plan noted" : p[4];
    }

    private static int buildCmd(CommandContext<CommandSourceStack> ctx, int blocks) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        Commission c = load(v.id());
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal("No house is going up."));
            return 0;
        }
        int laid = 0;
        String last = "";
        for (int i = 0; i < 400 && laid < blocks; i++) {
            Commission now = load(v.id());
            if (now == null) break;
            int before = now.laidCount;
            last = work(level, v, now, Math.min(BUDGET, blocks - laid), true);
            Commission after = load(v.id());
            if (after == null) { laid = blocks; break; }
            if (after.laidCount == before && !last.startsWith("the ground")) break;
            laid += after.laidCount - before;
        }
        String said = "BUILT " + laid + " (" + last + ") " + String.join(" | ", lines(level, v.id()));
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town letting its folk build (true), not (false), or as its age and size have it (null). */
    public static void openForTests(@Nullable Boolean on) {
        OPEN_FOR_TESTS = on;
    }

    /** Tests: the bill for this design on this ground for a household of so many, now. */
    public static Bill quoteForTests(ServerLevel level, Villages.Village v, Design d, BlockPos anchor, Direction facing, int household) {
        return quote(level, v, d, anchor, facing, household);
    }

    /** Tests: can this household pay so much (and how)? */
    public static Means meansForTests(ServerLevel level, Villages.Village v, List<VillageFolkEntity> household, int total) {
        return means(level, v, household, total);
    }

    /** Tests: the households consider a house of their own now (forced: wanting one whatever their nature). */
    public static boolean considerForTests(ServerLevel level, Villages.Village v, boolean force) {
        return consider(level, v, level.getDayTime() / 24000L, force) != null;
    }

    /** Tests: this household commissions this design now; true if it could pay. */
    public static boolean commissionForTests(ServerLevel level, Villages.Village v, List<VillageFolkEntity> household, Design d) {
        return commission(level, v, household, d, level.getDayTime() / 24000L) != null;
    }

    /** Tests: {total, blocks, labour, plot, furnish, permit, escrow, laid, cells, loan, blocksPaid, labourPaid, furnishPaid} of the house
     *  going up, or null. */
    @Nullable
    public static int[] buildForTests(UUID village) {
        Commission c = load(village);
        return c == null ? null : new int[]{ c.total(), c.blocks, c.labour, c.plot, c.furnish, c.permit, c.escrow, c.laidCount, c.cells, c.loan,
            c.blocksPaid, c.labourPaid, c.furnishPaid };
    }

    /** Tests: the ground of the house going up, or null. */
    @Nullable
    public static BlockPos buildAnchorForTests(UUID village) {
        Commission c = load(village);
        return c == null ? null : c.anchor;
    }

    /** Tests: which way the house going up has its back, or null. */
    @Nullable
    public static Direction buildFacingForTests(UUID village) {
        Commission c = load(village);
        return c == null ? null : c.facing;
    }

    /** Tests: who has been paid for laying the house going up, and how much. */
    public static Map<UUID, Integer> buildersForTests(UUID village) {
        Commission c = load(village);
        return c == null ? Map.of() : Map.copyOf(c.builders);
    }

    /** Tests: a visit's work on the house going up, now (the hand there, the town's buildings out of the way). */
    public static String workForTests(ServerLevel level, Villages.Village v, int budget) {
        Commission c = load(v.id());
        return c == null ? "none" : work(level, v, c, budget, true);
    }

    /** Tests: the morning's market reckoning, for so many days running from today. */
    public static void daysForTests(ServerLevel level, Villages.Village v, int days) {
        long day = Math.max(level.getDayTime() / 24000L, indexDay(v.id()));
        for (int i = 0; i < days; i++) reckon(level, v, day + i + 1);
    }

    /** Tests: the index set outright. */
    public static void indexForTests(UUID village, double index) {
        Ledger.note(village, INDEX, String.format(Locale.ROOT, "%.4f", clamp(index)) + "|-1|");
    }

    /** Tests: the council's houses costed now (and each drawing's cost to build today). */
    public static void costForTests(ServerLevel level, UUID village) {
        costCouncilHouses(level, village, level.getDayTime() / 24000L);
    }

    /** Tests: {blocks, labour, book, custom} on the books for the house on this ground, or null. */
    @Nullable
    public static int[] costOfForTests(UUID village, BlockPos anchor) {
        Cost c = Cost.decode(Ledger.note(village, COST + anchor.asLong()));
        return c == null ? null : new int[]{ c.blocks(), c.labour(), c.book(), c.custom() ? 1 : 0 };
    }

    /** Tests: the house on this ground sold by this folk (its household has left it), as when its owners move up. */
    public static int sellForTests(ServerLevel level, Villages.Village v, BlockPos anchor, @Nullable VillageFolkEntity seller) {
        Homes.Home h = Homes.homes(v.id()).get(anchor.asLong());
        if (h == null) return -1;
        for (UUID m : new ArrayList<>(h.members)) {
            VillageFolkEntity f = Homes.loaded(v.id(), m);
            if (f != null) f.forgetBed();
        }
        h.members.clear();
        Homes.save(v.id(), h);
        return sell(level, v, h, seller, level.getDayTime() / 24000L, "moving up");
    }

    /** Tests: the house on this ground made this household's own, bought for so much. */
    public static void ownForTests(UUID village, BlockPos anchor, int price) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        if (h == null) return;
        h.tenure = Homes.Tenure.OWNED;
        h.price = price;
        h.rent = 0;
        h.owed = 0;
        Homes.save(village, h);
    }

    /** Tests: what is held for the house going up (in no purse, the treasury or the vault). */
    public static int escrowForTests(UUID village) {
        Commission c = load(village);
        return c == null ? 0 : c.escrow;
    }

    /** Tests: what the folk's card says of its own house. */
    public static String cardForTests(VillageFolkEntity f) {
        return cardLine(f);
    }

    /** Tests: the plot's price on this ground today. */
    public static int plotForTests(UUID village, BlockPos anchor) {
        return plotFee(village, anchor);
    }

    /** Tests: a builder's hourly rate in this town, and the hours so many blocks take. */
    public static double hourlyForTests(UUID village) {
        return hourly(village);
    }

    /** Tests: what the house on this ground fetches on the market today (Homes.price), or -1. */
    public static int marketPriceForTests(UUID village, BlockPos anchor) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        return h == null ? -1 : Homes.price(village, h);
    }

    /** Tests: the household this folk heads (it, its partner, their children), as the market counts households. */
    public static List<VillageFolkEntity> householdForTests(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id != null) for (List<VillageFolkEntity> hh : households(id)) if (hh.contains(f)) return hh;
        return List.of(f);
    }

    /** Tests: the cells of a design's drawing the household pays for (beds as wanted). */
    public static int cellsForTests(Design d, int household) {
        return cells(d, BlockPos.ZERO.above(70), Direction.NORTH, bedsWanted(d, household)).size();
    }
}
