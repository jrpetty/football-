package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * [orders17] A building commissioned from the town's builders, by a player: an honest quote, an honest time, and the
 * builders at it until it stands.
 *
 * <p><b>The quote.</b> Every block of the drawing, in the style asked for, at the market's ask today (less what the buyer
 * brings itself); the ground made good first (the earth cut away inside the walls, the hollows under the floor filled with
 * cobblestone); the labour block by block, each block by the trade that lays it (masons the stone and brick, carpenters
 * the timber, the doors and the fences, roofers the roof, glaziers the glass, fitters the furnaces, chests and beds,
 * labourers the ground), at that trade's time a block (slower the higher it goes: scaffolding) and that trade's going
 * wage (JobWorth, the labour market), with a tenth on top for the tools, the scaffolding and the foreman; the land (the
 * plot's value, by its size, where it lies and the housing market, as the folk's own plots are priced; nothing on the
 * buyer's own land; or a ground lease, a twelfth of the value a week); the permit (a twentieth of the works), the business
 * rates for its first four weeks if it is premises, and the registry's coin; and the builders' margin (a tenth). Prices
 * and wages follow the market and the wage index, so a boom makes building dearer.
 *
 * <p><b>The time.</b> The town's commission crews (one, two in a town of twenty-four, three in one of forty), each of so many
 * hands (a hand for every six grown folk, two to four), more practised each age; ten hours a day, all of them when the
 * town's own crews are idle or the town is thriving, the afternoon only (four of the ten) while its crews are on its own
 * buildings, eight of the ten on a priority order (the town's essential works still first). The jobs ahead (other
 * commissions placed before it, priority orders, a folk's own house going up) are dealt to the crews in turn, and it
 * starts when a crew comes free; the materials the stores lack are made or bought in at what the town's trades turn out
 * a day. The estimate is a range, and it is revised as the work goes on, with the reason.
 *
 * <p><b>Paying.</b> A deposit on ordering (the land, the fees, any priority premium and three tenths of the works), a stage
 * payment when three tenths of it is laid (more than half the works again), and the balance on completion; everything held
 * for the order is counted in the census until it is spent: each block's price to the market as it is laid, the builders'
 * wages into their purses as they lay it, the margin and the overheads to the treasury when it stands. Cancelled, what was
 * not spent comes back, and whatever the buyer brought that was not laid.
 *
 * <p>All of it in the town's accounts (Accounts, "orders17"), so it is kept with the world.
 */
public final class BuildOrders {

    private BuildOrders() {}

    // ------------------------------------------------------------------ the numbers

    /** A builder's working day, in hours: from six in the morning (day time 0) to four (10000). */
    static final int HOURS_A_DAY = 10;
    /** Tools, scaffolding and the foreman's time, on the labour; the builders' margin, on the works; in the hundred. */
    static final int OVERHEADS = 10, MARGIN = 10;
    /** The priority premium: a quarter of the labour and overheads, to the treasury for the town's works put back. */
    static final int PRIORITY = 25;
    /** The deposit and the stage payment, as parts of the works (materials, labour, overheads, margin), in the hundred. */
    static final int DEPOSIT = 30, STAGE = 55;
    /** The stage payment falls due when this much of the work is laid, in the hundred. */
    static final int STAGE_AT = 30;
    /** The permit, as for a folk's own house; the registry's fee. */
    static final int PERMIT_PERCENT = 5, PERMIT_LEAST = 2, REGISTRY = 1;
    /** Weeks of ground rent paid with the deposit on a lease. */
    static final int LEASE_WEEKS = 4;
    /** Days the builders wait for a fitting (an anvil, a lantern) before leaving it out, and not charging for it. */
    static final int LEAVE_OUT_DAYS = 3;
    /** The most hours of work caught up in one go (a long sleep). */
    static final int CATCH_UP = 48;

    static final String BOOK = "orders17";

    /** The trades that lay a building, each its own pace and pay: blocks an hour, the trade it is paid as, and its premium in the hundred. */
    public enum Trade {
        LABOURERS("labourers (ground)", 18, AssistantEntity.StationTask.HAUL, 100),
        MASONS("masons", 10, AssistantEntity.StationTask.SMITH, 100),
        CARPENTERS("carpenters", 12, AssistantEntity.StationTask.SHOP, 100),
        ROOFERS("roofers", 9, AssistantEntity.StationTask.SMITH, 110),
        GLAZIERS("glaziers", 8, AssistantEntity.StationTask.SMELT, 105),
        FITTERS("fitters", 6, AssistantEntity.StationTask.SHOP, 100);

        public final String words;
        final int perHour, premium;
        final AssistantEntity.StationTask paidAs;

        Trade(String words, int perHour, AssistantEntity.StationTask paidAs, int premium) {
            this.words = words;
            this.perHour = perHour;
            this.paidAs = paidAs;
            this.premium = premium;
        }
    }

    /** The styles a player may build in, by name: the timbers, then stone, brick and the civic look as the age allows. */
    static final Map<String, Showcase.Palette> STYLES = new LinkedHashMap<>();
    static {
        STYLES.put("oak", Showcase.OAK);
        STYLES.put("birch", Showcase.BIRCH);
        STYLES.put("spruce", Showcase.SPRUCE);
        STYLES.put("stone", Grow.STONE);
        STYLES.put("brick", Grow.BRICK);
        STYLES.put("civic", Ages.CIVIC);
    }

    /** The drawings a player may order: homes, premises and a few others. */
    static final List<String> DRAWINGS = List.of("house", "house2", "villa", "manor", "shop", "store", "bakery", "tavern", "cafe",
        "restaurant", "smithy", "workshop", "storage", "granary", "barn", "stable", "inn", "guesthouse", "brewery", "apiary",
        "lodge", "shelter", "smeltery", "fletcher", "wheelwright", "huntlodge");

    public static List<String> styles(UUID village) {
        List<String> out = new ArrayList<>(List.of("oak", "birch", "spruce"));
        int age = Villages.ageOf(village).ordinal();
        if (age >= Villages.Age.STONE.ordinal()) out.add("stone");
        if (age >= Villages.Age.STONE.ordinal() + 1) out.add("brick");
        if (age >= Villages.Age.STONE.ordinal() + 1) out.add("civic");
        return out;
    }

    public static List<String> drawings(UUID village) {
        List<String> out = new ArrayList<>();
        for (String d : DRAWINGS) if (Blueprints.has(d)) out.add(d);
        return out;
    }

    static Showcase.Palette palette(String style) {
        Showcase.Palette p = STYLES.get(style == null ? "" : style.toLowerCase(Locale.ROOT));
        return p == null ? Showcase.OAK : p;
    }

    /** "a family house", "a bakery": what the drawing is called. */
    public static String title(String drawing) {
        String base = drawing.endsWith(Blueprints.TALL) ? drawing.substring(0, drawing.length() - Blueprints.TALL.length()) : drawing;
        String w = switch (base) {
            case "house" -> "cottage";
            case "house2" -> "family house";
            case "store" -> "general store";
            case "cafe" -> "café";
            case "storage" -> "warehouse";
            case "huntlodge" -> "hunting lodge";
            case "guesthouse" -> "guest house";
            default -> base;
        };
        if (drawing.endsWith(Blueprints.TALL)) w = "two-storey " + w;
        return ("aeiou".indexOf(w.charAt(0)) >= 0 ? "an " : "a ") + w;
    }

    /** Is this drawing a home (a house folk could live in)? */
    public static boolean home(String drawing) {
        String b = drawing.endsWith(Blueprints.TALL) ? drawing.substring(0, drawing.length() - Blueprints.TALL.length()) : drawing;
        return b.equals("house") || b.equals("house2") || b.equals("villa") || b.equals("manor");
    }

    // ------------------------------------------------------------------ prices and wages

    /** One of this at the market's ask today (the stores' price before the market opens). */
    static double unit(ServerLevel level, UUID village, Item item) {
        ItemStack one = new ItemStack(item);
        double p = MarketMaker.live(village) ? MarketMaker.ask(level, village, one) : PriceIndex.each(level, village, one);
        return Math.max(0.01, p);
    }

    /** A trade's hour here today: its day's going wage over the working day, with its premium. */
    static double hourly(UUID village, Trade t) {
        return Math.max(0.05, JobWorth.rate(t.paidAs, village) / (double) HOURS_A_DAY * t.premium / 100.0);
    }

    // ------------------------------------------------------------------ the cells

    /** One block of the work: where, what goes there (null: dug out), the item it is paid as, the trade, its minutes. */
    record Cell(BlockPos pos, @Nullable BuildGoal.Placement placement, @Nullable Block block, Item item, Trade trade, double minutes, boolean fitting) {}

    static boolean fitting(BuildGoal.Part p) {
        return switch (p) {
            case BLOCK, WINDOW, DOOR, FENCE, GATE, LADDER, OBSIDIAN, CLEAR, WATER -> false;
            default -> true;
        };
    }

    static Trade tradeFor(BuildGoal.Placement p, Block b) {
        BuildGoal.Part part = p.part();
        if (part == BuildGoal.Part.WINDOW) return Trade.GLAZIERS;
        if (part == BuildGoal.Part.DOOR || part == BuildGoal.Part.FENCE || part == BuildGoal.Part.GATE || part == BuildGoal.Part.LADDER) return Trade.CARPENTERS;
        if (fitting(part)) return Trade.FITTERS;
        String s = p.style().name();
        if (s.startsWith("ROOF")) return Trade.ROOFERS;
        if (HousingMarket.wooden(b)) return Trade.CARPENTERS;
        if (b.defaultBlockState().is(BlockTags.IMPERMEABLE)) return Trade.GLAZIERS;
        return Trade.MASONS;
    }

    static double minutes(Trade t, int heightAbove) {
        return 60.0 / t.perHour * (1.0 + 0.05 * Math.max(0, heightAbove - 4));
    }

    static Item itemOf(Block b) {
        if (b == Blocks.WALL_TORCH) return Items.TORCH;
        if (b == Blocks.WATER) return Items.WATER_BUCKET;
        return b.asItem();
    }

    /** The building's own cells for a drawing in a style: a course at a time from the footing, then the fittings. */
    static List<Cell> structure(String drawing, Showcase.Palette pal, BlockPos anchor, Direction facing, @Nullable String skipBase) {
        java.util.function.Function<BuildGoal.Placement, BlockState> paint = Showcase.painter(pal);
        java.util.Set<String> base = new java.util.HashSet<>();
        if (skipBase != null) {
            for (BuildGoal.Placement p : BuildGoal.plan(skipBase, anchor, facing, 13)) {
                BlockState st = p.part() == BuildGoal.Part.CLEAR ? null : paint.apply(p);
                if (st != null) base.add(p.pos().asLong() + "|" + st.getBlock());
            }
        }
        List<Cell> walls = new ArrayList<>(), things = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(drawing, anchor, facing, 13)) {
            if (p.part() == BuildGoal.Part.CLEAR || p.part() == BuildGoal.Part.WATER) continue;
            BlockState st = paint.apply(p);
            if (st == null || st.isAir()) continue;
            Block b = st.getBlock();
            if (skipBase != null && base.contains(p.pos().asLong() + "|" + b)) continue;
            Item it = itemOf(b);
            if (it == Items.AIR) continue;
            Trade t = tradeFor(p, b);
            Cell c = new Cell(p.pos(), p, b, it, t, minutes(t, p.pos().getY() - anchor.getY()), fitting(p.part()));
            (c.fitting() ? things : walls).add(c);
        }
        walls.sort(Comparator.comparingInt(c -> c.pos().getY()));
        things.sort(Comparator.comparingInt(c -> c.pos().getY()));
        walls.addAll(things);
        return walls;
    }

    /** The ground made good: the earth inside the building's box above its floor cut away, and the hollows under it filled. */
    static List<BlockPos>[] ground(ServerLevel level, String drawing, BlockPos anchor, Direction facing, List<Cell> cells) {
        @SuppressWarnings("unchecked") List<BlockPos>[] out = new List[]{ new ArrayList<>(), new ArrayList<>() };
        if (!level.isLoaded(anchor)) return out;
        int[] half = BuildGoal.footprint(drawing);
        java.util.Set<Long> own = new java.util.HashSet<>();
        int top = anchor.getY();
        for (Cell c : cells) { own.add(c.pos().asLong()); top = Math.max(top, c.pos().getY()); }
        int[] box = BuildGoal.box(drawing, anchor, facing, false);           // [house34b] its own ground, not one past an even drawing's end
        for (int x = box[0]; x <= box[1]; x++) for (int z = box[2]; z <= box[3]; z++) {
            for (int y = anchor.getY(); y <= Math.min(top, anchor.getY() + 12); y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (own.contains(p.asLong())) continue;
                BlockState st = level.getBlockState(p);
                if (!st.isAir() && !BuildGoal.soft(st) && st.getFluidState().isEmpty() && !st.hasBlockEntity()) out[0].add(p);
            }
        }
        out[1].addAll(BuildGoal.fillCells(level, anchor, box));
        return out;
    }

    // ------------------------------------------------------------------ the quote

    /** What the buyer asks for besides the building: priority, a ground lease, what it brings itself. */
    public static final class Options {
        public boolean priority;
        public boolean lease;
        public Map<Item, Integer> supplied = new LinkedHashMap<>();
        @Nullable public Player fromInventory;
    }

    public record Material(Item item, String name, int count, int supplied, double unit, double total) {}

    public record Labour(Trade trade, int blocks, double hours, double rate, double cost) {
        public String words() { return trade.words; }
    }

    /** How long: a range in days, the place in the queue, the jobs ahead, the crews and their hands, what the stores lack, and why. */
    public record Eta(double low, double days, double high, int position, int ahead, int crews, int hands, int materialsShort,
                      double materialsDays, double startDays, double buildDays, String reason) {
        public String words() {
            if (high < 1.0) return "within the day";
            return String.format(Locale.ROOT, "about %.1f days (%.1f to %.1f)", days, low, high);
        }
    }

    /** A builder's price, line by line. */
    public static final class Quote {
        public UUID village;
        @Nullable public UUID buyer;
        public String kind = "build";
        public String drawing = "", style = "oak", use = "", name = "";
        public BlockPos plot = BlockPos.ZERO;
        public Direction facing = Direction.NORTH;
        @Nullable public String baseDrawing;
        public boolean ok = true;
        public String why = "";
        public final List<Material> materials = new ArrayList<>();
        public final List<Labour> labour = new ArrayList<>();
        public double materialsCost, labourHours, labourCost, overheads, landCost, leasePerWeek, permit, rates, registry, fees, margin, premium;
        public String landTerms = "";
        public int total, deposit, stage, balance, cells, cut, fill;
        public Eta eta;
        public long startDay, finishDay;
        public boolean priority, lease;
        public final Map<Item, Integer> supplied = new LinkedHashMap<>();
        /** Explicit cells for a refit (its kit), in the order they go in. */
        public final List<Cell> explicit = new ArrayList<>();
        /** Cells to take down first (an old roof under a new storey). */
        public final List<BlockPos> cutCells = new ArrayList<>();

        /** The works: what the builders are paid for, before the land and the fees. */
        public double works() {
            return materialsCost + labourCost + overheads + margin;
        }

        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add(name + (ok ? "" : " - not here: " + why));
            out.add(String.format(Locale.ROOT, "Materials %.1f (%d lines%s)", materialsCost, materials.size(), supplied.isEmpty() ? "" : ", less what you bring"));
            for (Material m : materials) {
                out.add(String.format(Locale.ROOT, "  %d %s at %.2f = %.1f%s", m.count() - m.supplied(), m.name(), m.unit(), m.total(),
                    m.supplied() > 0 ? " (" + m.supplied() + " your own)" : ""));
            }
            out.add(String.format(Locale.ROOT, "Labour %.1f (%.0f hours)", labourCost, labourHours));
            for (Labour l : labour) out.add(String.format(Locale.ROOT, "  %s: %d blocks, %.1f hours at %.2f = %.1f", l.words(), l.blocks(), l.hours(), l.rate(), l.cost()));
            out.add(String.format(Locale.ROOT, "Overheads %.1f (tools, scaffolding, the foreman)", overheads));
            out.add(String.format(Locale.ROOT, "Land %.1f (%s)", landCost, landTerms));
            out.add(String.format(Locale.ROOT, "Fees %.1f (permit %.1f, rates %.1f, registry %.1f)", fees, permit, rates, registry));
            out.add(String.format(Locale.ROOT, "Builders' margin %.1f", margin));
            if (premium > 0) out.add(String.format(Locale.ROOT, "Priority %.1f", premium));
            out.add("Total " + total + " coins: " + deposit + " down, " + stage + " when " + STAGE_AT + "% is laid, " + balance + " on completion");
            if (eta != null) {
                out.add("Time " + eta.words() + ": " + eta.reason());
                out.add("Builders start about day " + (startDay + 1) + ", done about day " + (finishDay + 1));
            }
            return out;
        }

        public CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString("kind", kind);
            t.putString("drawing", drawing);
            t.putString("style", style);
            t.putString("name", name);
            t.putString("use", use);
            t.putLong("plot", plot.asLong());
            t.putInt("facing", facing.get2DDataValue());
            t.putBoolean("ok", ok);
            t.putString("why", why);
            t.putInt("total", total);
            t.putInt("deposit", deposit);
            t.putInt("stage", stage);
            t.putInt("balance", balance);
            t.putDouble("materials", materialsCost);
            t.putDouble("labour", labourCost);
            t.putDouble("overheads", overheads);
            t.putDouble("land", landCost);
            t.putDouble("fees", fees);
            t.putDouble("margin", margin);
            t.putDouble("premium", premium);
            t.putBoolean("priority", priority);
            t.putBoolean("lease", lease);
            if (eta != null) {
                t.putDouble("etaLow", eta.low());
                t.putDouble("eta", eta.days());
                t.putDouble("etaHigh", eta.high());
                t.putString("etaWhy", eta.reason());
            }
            ListTag l = new ListTag();
            for (String s : lines()) l.add(StringTag.valueOf(s));
            t.put("lines", l);
            return t;
        }
    }

    /** Is the plot one a building may go on? "" if so, else why not. */
    static String plotProblem(ServerLevel level, Villages.Village v, String drawing, BlockPos anchor, Direction facing, @Nullable BlockPos ignore) {
        int[] box = BuildGoal.box(drawing, anchor, facing, false);           // [house34b] its own ground, not one past an even drawing's end
        BlockPos c = v.centre();
        if (Math.max(Math.abs(anchor.getX() - c.getX()), Math.abs(anchor.getZ() - c.getZ())) > Villages.VILLAGE_RANGE) {
            return "outside the town";
        }
        for (int x = box[0]; x <= box[1]; x++) for (int z = box[2]; z <= box[3]; z++) {
            int ox = x - c.getX(), oz = z - c.getZ();
            if (TownPlan.isSquare(ox, oz)) return "on the square";
            if (TownPlan.isStreet(ox, oz)) return "on a street";
        }
        if (MineGround.meets(v.id(), box[0], box[2], box[1], box[3])) return "on the mine's ground";
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (ignore != null && b.anchor().equals(ignore)) continue;
            if (overlaps(b.structure(), b.anchor(), b.facing(), box)) return "on " + b.structure() + " ground";
        }
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(v.id()).entrySet()) {
            Villages.Site s = e.getValue();
            if (ignore != null && s.anchor().equals(ignore)) continue;
            if (Blueprints.has(e.getKey()) && overlaps(e.getKey(), s.anchor(), s.facing(), box)) return "where the town is building its " + e.getKey();
        }
        int wx = Math.max(anchor.getX() - box[0], box[1] - anchor.getX()), wz = Math.max(anchor.getZ() - box[2], box[3] - anchor.getZ());
        String taken = PropertyMarket.overlapping(v.id(), anchor, wx, wz, ignore);
        if (taken != null) return "on " + taken;
        for (CompoundTag o : open(v.id())) {
            BlockPos at = BlockPos.of(o.getLong("anchor"));
            if (ignore != null && at.equals(ignore)) continue;
            if (!"build".equals(o.getString("kind"))) continue;
            Direction f = Direction.from2DDataValue(o.getInt("facing"));
            if (overlaps(o.getString("drawing"), at, f, box)) return "where another order is going up";
        }
        return "";
    }

    /** The nearest plot to a spot where this drawing may go (a ring at a time outward), on the ground there; null if none within reach. */
    @Nullable
    public static BlockPos suggestPlot(ServerLevel level, UUID village, String drawing, BlockPos near, Direction facing, int reach) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        for (int r = 0; r <= reach; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = near.getX() + dx, z = near.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y, z);
                if (plotProblem(level, v, drawing, p, facing, null).isEmpty()) return p;
            }
        }
        return null;
    }

    /** [house34b] Does this building's ground (its own, BuildGoal.box) touch or overlap this ground, with no block between? */
    static boolean overlaps(String structure, BlockPos at, Direction f, int[] box) {
        return Footways.gap(BuildGoal.box(structure, at, f, false), box) < 1;
    }

    static boolean overlaps(String structure, BlockPos at, Direction f, BlockPos anchor, int wx, int wz) {
        int[] h = BuildGoal.footprint(structure);
        boolean t = f.getAxis() == Direction.Axis.X;
        int bx = t ? h[1] : h[0], bz = t ? h[0] : h[1];
        return Math.abs(at.getX() - anchor.getX()) <= bx + wx + 1 && Math.abs(at.getZ() - anchor.getZ()) <= bz + wz + 1;
    }

    /** The land under a building: its value as a plot of the town's, by its footprint against a cottage's. */
    static double landValue(UUID village, String drawing, BlockPos anchor) {
        int[] h = BuildGoal.footprint(drawing), c = BuildGoal.footprint("house");
        double area = (2.0 * h[0] + 1) * (2.0 * h[1] + 1), cot = (2.0 * c[0] + 1) * (2.0 * c[1] + 1);
        return HousingMarket.plotFee(village, anchor) * Math.max(0.5, area / Math.max(1, cot)) * (MarketMaker.live(village) ? LandTitles.index(village) : 1.0);
    }

    public static Quote quote(ServerLevel level, UUID village, String drawing, String style, BlockPos plot, Direction facing, @Nullable UUID buyer) {
        return quote(level, village, drawing, style, plot, facing, buyer, new Options());
    }

    public static Quote quote(ServerLevel level, UUID village, String drawing, String style, BlockPos plot, Direction facing, @Nullable UUID buyer, Options o) {
        Quote q = new Quote();
        q.village = village;
        q.buyer = buyer;
        q.drawing = drawing;
        q.style = style == null || !STYLES.containsKey(style) ? "oak" : style;
        q.plot = plot;
        q.facing = facing;
        q.priority = o.priority;
        q.lease = o.lease;
        q.use = home(drawing) ? "home" : drawing;
        q.name = title(drawing) + " in " + q.style;
        Villages.Village v = Villages.get(village);
        if (v == null || !Blueprints.has(drawing)) {
            q.ok = false;
            q.why = v == null ? "no town" : "no such drawing";
            return q;
        }
        String problem = plotProblem(level, v, drawing, plot, facing, null);
        if (!problem.isEmpty()) { q.ok = false; q.why = problem; }
        List<Cell> cells = structure(drawing, palette(q.style), plot, facing, null);
        List<BlockPos>[] g = ground(level, drawing, plot, facing, cells);
        q.cut = g[0].size();
        q.fill = g[1].size();
        price(level, v, q, cells, o);
        double land = landValue(village, drawing, plot);
        if (buyer != null && PropertyMarket.ownsLandAt(village, buyer, plot)) {
            q.landTerms = "your own land";
        } else if (o.lease) {
            q.leasePerWeek = Math.max(0.5, land / LandTitles.YEARS_WEEKS);
            q.landCost = q.leasePerWeek * LEASE_WEEKS;
            q.landTerms = String.format(Locale.ROOT, "ground lease, %.1f a week, the first %d weeks now", q.leasePerWeek, LEASE_WEEKS);
        } else {
            q.landCost = land;
            q.landTerms = "the title, bought from the town";
        }
        q.rates = home(drawing) ? 0 : TaxBook.ratesCoins(village) * 4.0;
        finishQuote(level, v, q);
        return q;
    }

    /** Materials and labour priced for a list of cells (and the quote's ground), less what the buyer brings. */
    static void price(ServerLevel level, Villages.Village v, Quote q, List<Cell> cells, Options o) {
        UUID id = v.id();
        Map<Item, Integer> count = new LinkedHashMap<>();
        Map<Trade, double[]> lab = new LinkedHashMap<>();
        for (Cell c : cells) {
            count.merge(c.item(), 1, Integer::sum);
            double[] a = lab.computeIfAbsent(c.trade(), k -> new double[2]);
            a[0]++;
            a[1] += c.minutes();
        }
        if (q.fill > 0) count.merge(Items.COBBLESTONE, q.fill, Integer::sum);
        if (q.cut + q.fill > 0) {
            double[] a = lab.computeIfAbsent(Trade.LABOURERS, k -> new double[2]);
            a[0] += q.cut + q.fill;
            a[1] += q.fill * minutes(Trade.LABOURERS, 0) + q.cut * 60.0 / 15;
        }
        Map<Item, Integer> bring = new LinkedHashMap<>(o.supplied);
        if (o.fromInventory != null) {
            for (Item it : count.keySet()) {
                int have = carried(o.fromInventory, it);
                if (have > 0) bring.merge(it, have, Math::max);
            }
        }
        q.cells = cells.size();
        for (Map.Entry<Item, Integer> e : count.entrySet()) {
            int n = e.getValue();
            int s = Math.min(n, bring.getOrDefault(e.getKey(), 0));
            double u = unit(level, id, e.getKey());
            if (s > 0) q.supplied.put(e.getKey(), s);
            q.materials.add(new Material(e.getKey(), new ItemStack(e.getKey()).getHoverName().getString(), n, s, u, u * (n - s)));
            q.materialsCost += u * (n - s);
        }
        for (Map.Entry<Trade, double[]> e : lab.entrySet()) {
            double hours = e.getValue()[1] / 60.0, rate = hourly(id, e.getKey());
            q.labour.add(new Labour(e.getKey(), (int) e.getValue()[0], hours, rate, hours * rate));
            q.labourHours += hours;
            q.labourCost += hours * rate;
        }
    }

    /** The fees, the margin, the premium, the totals and the stages, and the time. */
    static void finishQuote(ServerLevel level, Villages.Village v, Quote q) {
        q.overheads = q.labourCost * OVERHEADS / 100.0;
        q.permit = Math.max(PERMIT_LEAST, (q.materialsCost + q.labourCost) * PERMIT_PERCENT / 100.0);
        q.registry = REGISTRY;
        q.fees = q.permit + q.rates + q.registry;
        q.margin = (q.materialsCost + q.labourCost + q.overheads) * MARGIN / 100.0;
        q.premium = q.priority ? (q.labourCost + q.overheads) * PRIORITY / 100.0 : 0;
        double works = q.works();
        q.total = (int) Math.ceil(works + q.landCost + q.fees + q.premium - 1e-9);
        q.deposit = (int) Math.ceil(q.landCost + q.fees + q.premium + works * DEPOSIT / 100.0 - 1e-9);
        q.stage = (int) Math.ceil(works * STAGE / 100.0 - 1e-9);
        q.balance = Math.max(0, q.total - q.deposit - q.stage);
        if (q.deposit > q.total) q.deposit = q.total;
        q.eta = estimate(level, v, q.priority, workMinutes(q), q.materials, q.supplied, Integer.MAX_VALUE);
        long today = level.getDayTime() / 24000L;
        q.startDay = today + (long) Math.floor(q.eta.startDays());
        q.finishDay = today + (long) Math.ceil(q.eta.days());
    }

    static double workMinutes(Quote q) {
        double m = 0;
        for (Labour l : q.labour) m += l.hours() * 60;
        return m;
    }

    static int carried(Player p, Item it) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(it)) n += s.getCount();
        }
        return n;
    }

    static int takeCarried(Player p, Item it, int want) {
        int got = 0;
        for (int i = 0; i < p.getInventory().getContainerSize() && got < want; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.is(it)) continue;
            int k = Math.min(want - got, s.getCount());
            s.shrink(k);
            got += k;
        }
        p.getInventory().setChanged();
        return got;
    }

    // ------------------------------------------------------------------ the time

    /** The town's commission crews: one, two in a town of twenty-four, three in one of forty. */
    static int crews(UUID village) {
        int n = Villages.headcount(village);
        return n >= 40 ? 3 : n >= 24 ? 2 : 1;
    }

    /** Hands to a crew: one for every six grown folk, two to four. */
    static int hands(UUID village) {
        int grown = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isBaby()) grown++;
        return Math.max(2, Math.min(4, grown / 6));
    }

    /** How practised the town's builders are: a twentieth faster each age. */
    static double skill(UUID village) {
        return 1.0 + 0.05 * Villages.ageOf(village).ordinal();
    }

    /** Is the town's own building under way (its crews at a building of its own)? */
    static boolean townBusy(ServerLevel level, UUID village) {
        if (Villages.thriving(village)) return false;
        return !Villages.crewsReport(village, level.getGameTime()).isEmpty() || Villages.nextProject(village) != null && Villages.headcount(village) >= 3;
    }

    /** The town's next building is one that cannot wait (the wall, the war's works, a house for the homeless). */
    static boolean essentialNext(UUID village) {
        String n = Villages.nextProject(village);
        return n != null && (n.equals("fortify") || n.startsWith("war") || n.equals("house") && Villages.headcount(village) - Villages.housing(village) > 2);
    }

    /** The hours of the working day a commission gets: {first hour, last hour}. */
    static int[] window(ServerLevel level, UUID village, boolean priority) {
        boolean busy = townBusy(level, village);
        if (!busy) return new int[]{ 0, HOURS_A_DAY };
        if (priority && !essentialNext(village)) return new int[]{ 2, HOURS_A_DAY };
        return new int[]{ 6, HOURS_A_DAY };
    }

    static double share(ServerLevel level, UUID village, boolean priority) {
        int[] w = window(level, village, priority);
        return (w[1] - w[0]) / (double) HOURS_A_DAY;
    }

    /** What the town's trades make a day of this, and the days to buy it in when nobody makes it. {per day, lead days} */
    static double[] supply(UUID village, Item it) {
        Block b = Block.byItem(it);
        AssistantEntity.StationTask t;
        double each;
        if (b != Blocks.AIR && HousingMarket.wooden(b) || it == Items.STICK) { t = AssistantEntity.StationTask.WOOD; each = 48; }
        else if (it == Items.COBBLESTONE || it == Items.STONE || it == Items.STONE_BRICKS || it.toString().contains("stone") || it.toString().contains("deepslate")) { t = AssistantEntity.StationTask.MINE; each = 64; }
        else if (it.toString().contains("brick") || it.toString().contains("glass") || it.toString().contains("terracotta")) { t = AssistantEntity.StationTask.SMELT; each = 24; }
        else if (it.toString().contains("wool") || it.toString().contains("carpet") || it.toString().contains("bed")) { t = AssistantEntity.StationTask.RANCH; each = 8; }
        else { t = null; each = 12; }
        int makers = 0;
        if (t != null) for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.stationTask() == t) makers++;
        return makers > 0 ? new double[]{ each * makers, 0 } : new double[]{ 8, 2 };
    }

    /** What the stores hold toward this (timber counted in planks, a log four). */
    static int held(ServerLevel level, UUID village, Item it) {
        Block b = Block.byItem(it);
        if (b != Blocks.AIR && b.defaultBlockState().is(BlockTags.LOGS)) return Market.stock(level, village, s -> s.is(net.minecraft.tags.ItemTags.LOGS));
        if (b != Blocks.AIR && HousingMarket.wooden(b)) {
            int planks = Market.stock(level, village, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) + 4 * Market.stock(level, village, s -> s.is(net.minecraft.tags.ItemTags.LOGS));
            return planks / Math.max(1, HousingMarket.planksFor(b));
        }
        return Market.stock(level, village, s -> s.is(it));
    }

    /**
     * The estimate: the jobs ahead dealt to the crews, the start when one comes free, the build at the crew's pace in the hours
     * the town can give it, and the wait for what the stores lack beyond what the build reaches before it needs it.
     * {@code seqBefore}: only orders placed before this one count as ahead (Integer.MAX_VALUE: every one, for a new quote).
     */
    static Eta estimate(ServerLevel level, Villages.Village v, boolean priority, double minutes, List<Material> mats, Map<Item, Integer> supplied, int seqBefore) {
        UUID id = v.id();
        int k = crews(id), h = hands(id);
        double pace = HOURS_A_DAY * 60.0 * h * skill(id);
        List<double[]> ahead = new ArrayList<>();          // {minutes, share}
        List<String> names = new ArrayList<>();
        for (CompoundTag o : open(id)) {
            if (o.getInt("seq") >= seqBefore) continue;
            boolean pri = o.getBoolean("priority");
            if (priority && !pri) continue;                // a priority order goes ahead of the plain ones
            double left = remainingMinutes(o);
            if (left <= 0) continue;
            ahead.add(new double[]{ left, share(level, id, pri) });
            names.add(o.getString("buyerName") + "'s " + title(o.getString("drawing")).replaceFirst("^an? ", ""));
        }
        if (!priority) {
            int[] folk = HousingMarket.buildForTests(id);
            if (folk != null && folk.length > 8 && folk[8] > folk[7]) {
                ahead.add(new double[]{ (folk[8] - folk[7]) * 5.0, share(level, id, false) });
                names.add("a folk's own house");
            }
        }
        double[] free = new double[k];
        for (double[] j : ahead) {
            int i = 0;
            for (int c = 1; c < k; c++) if (free[c] < free[i]) i = c;
            free[i] += j[0] / (pace * j[1]);
        }
        double start = free[0];
        for (double f : free) start = Math.min(start, f);
        double myShare = share(level, id, priority);
        double build = minutes / (pace * myShare);
        int short_ = 0;
        double matDays = 0;
        List<String> lacks = new ArrayList<>();
        for (Material m : mats) {
            int need = m.count() - supplied.getOrDefault(m.item(), 0);
            if (need <= 0) continue;
            int have = held(level, id, m.item());
            if (have >= need) continue;
            Map<Item, Integer> make = new LinkedHashMap<>();
            make.put(m.item(), need - have);
            if (Block.byItem(m.item()) != Blocks.AIR && !HousingMarket.wooden(Block.byItem(m.item())) && Masonry.canAll(level, v, make)) continue;   // made of the makings in the stores
            short_ += need - have;
            double[] s = supply(id, m.item());
            double d = s[1] + (need - have) / s[0];
            matDays = Math.max(matDays, d);
            if (lacks.size() < 3) lacks.add((need - have) + " " + m.name().toLowerCase(Locale.ROOT));
        }
        double delay = Math.max(0, matDays - start - build * 0.5);
        double days = start + build + delay;
        double low = start * 0.75 + build * 0.85 + delay * 0.5, high = start * 1.3 + build * 1.25 + delay * 1.5 + (short_ > 0 ? 0.5 : 0);
        StringBuilder why = new StringBuilder();
        int pos = ahead.size() + 1;
        why.append(pos == 1 ? "first in the builders' queue" : ordinal(pos) + " in the builders' queue, behind " + String.join(", ", names));
        why.append(String.format(Locale.ROOT, "; %d crew%s of %d hands", k, k == 1 ? "" : "s", h));
        if (myShare < 1) why.append(priority ? "; the town's essential works first" : "; afternoons only while the town builds its own");
        else if (priority) why.append("; on priority");
        if (short_ > 0) why.append(String.format(Locale.ROOT, "; %s to be made or bought in (about %.1f days)", String.join(", ", lacks), matDays));
        else why.append("; the materials in the stores");
        if (!supplied.isEmpty()) why.append("; you bring some yourself");
        return new Eta(low, days, high, pos, ahead.size(), k, h, short_, matDays, start, build, why.toString());
    }

    static String ordinal(int n) {
        return n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; });
    }

    // ------------------------------------------------------------------ the books

    static CompoundTag book(UUID village) {
        return Accounts.sub(Accounts.town(village), BOOK);
    }

    static CompoundTag orders(UUID village) {
        return Accounts.sub(book(village), "orders");
    }

    /** The orders still open (not done or cancelled), in the order the builders take them: priority first, then as placed. */
    static List<CompoundTag> open(UUID village) {
        List<CompoundTag> out = new ArrayList<>();
        CompoundTag all = orders(village);
        for (String k : all.getAllKeys()) {
            CompoundTag o = all.getCompound(k);
            String s = o.getString("status");
            if (s.equals("open")) out.add(o);
        }
        out.sort(Comparator.comparing((CompoundTag o) -> !o.getBoolean("priority")).thenComparingInt(o -> o.getInt("seq")));
        return out;
    }

    @Nullable
    static CompoundTag order(String orderId) {
        int hash = orderId.indexOf('#');
        if (hash <= 0) return null;
        try {
            UUID village = UUID.fromString(orderId.substring(0, hash));
            CompoundTag all = orders(village);
            String n = orderId.substring(hash + 1);
            return all.contains(n, Tag.TAG_COMPOUND) ? all.getCompound(n) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static UUID villageOf(String orderId) {
        return UUID.fromString(orderId.substring(0, orderId.indexOf('#')));
    }

    static String idOf(UUID village, CompoundTag o) {
        return village + "#" + o.getInt("n");
    }

    public static List<String> ordersOf(UUID village, UUID player) {
        List<CompoundTag> mine = new ArrayList<>();
        CompoundTag all = orders(village);
        for (String k : all.getAllKeys()) if (player.equals(uuid(all.getCompound(k), "buyer"))) mine.add(all.getCompound(k));
        mine.sort(Comparator.comparingInt((CompoundTag o) -> -o.getInt("n")));
        List<String> out = new ArrayList<>();
        for (CompoundTag o : mine) out.add(idOf(village, o));
        return out;
    }

    @Nullable
    static UUID uuid(CompoundTag t, String k) {
        return t.hasUUID(k) ? t.getUUID(k) : null;
    }

    /** The pennies held for players' orders in this town, not yet spent (CoinCensus counts them). */
    public static long heldPence(UUID village) {
        long n = 0;
        CompoundTag town = Accounts.town(village);
        if (!town.contains(BOOK, Tag.TAG_COMPOUND)) return 0;          // a town nobody has ordered from: nothing written for it
        CompoundTag all = orders(village);
        for (String k : all.getAllKeys()) n += all.getCompound(k).getLong("escrow");
        return n;
    }

    // ------------------------------------------------------------------ the cells of an order

    private static final Map<String, List<Cell>> SEQ = new ConcurrentHashMap<>();

    /** Every cell of the order in the order it is laid: the earth cut, the hollows filled, the courses, the fittings. */
    static List<Cell> seq(UUID village, CompoundTag o) {
        String key = village + "#" + o.getInt("n") + "@" + o.getInt("rev");
        List<Cell> got = SEQ.get(key);
        if (got != null) return got;
        List<Cell> out = new ArrayList<>();
        for (long l : o.getLongArray("cut")) out.add(new Cell(BlockPos.of(l), null, null, Items.AIR, Trade.LABOURERS, 60.0 / 15, false));
        for (long l : o.getLongArray("fill")) out.add(new Cell(BlockPos.of(l), null, Blocks.COBBLESTONE, Items.COBBLESTONE, Trade.LABOURERS, minutes(Trade.LABOURERS, 0), false));
        BlockPos anchor = BlockPos.of(o.getLong("anchor"));
        Direction f = Direction.from2DDataValue(o.getInt("facing"));
        if (o.contains("explicit", Tag.TAG_LIST)) {
            for (Tag t : o.getList("explicit", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(c.getString("b")));
                Trade tr = Trade.valueOf(c.getString("t"));
                out.add(new Cell(BlockPos.of(c.getLong("p")), null, b, itemOf(b), tr, minutes(tr, 0), true));
            }
        } else {
            String base = o.getString("base");
            out.addAll(structure(o.getString("drawing"), palette(o.getString("style")), anchor, f, base.isEmpty() ? null : base));
        }
        SEQ.put(key, out);
        return out;
    }

    static double remainingMinutes(CompoundTag o) {
        UUID v = uuid(o, "village");
        if (v == null) return 0;
        List<Cell> s = seq(v, o);
        BitSet laid = BitSet.valueOf(o.getLongArray("laid"));
        double m = 0;
        for (int i = laid.nextClearBit(0); i < s.size(); i = laid.nextClearBit(i + 1)) m += s.get(i).minutes();
        return Math.max(0, m - o.getDouble("credit"));
    }

    // ------------------------------------------------------------------ ordering

    public record Result(boolean ok, String orderId, String message) {}

    /** The order placed on a quote: the deposit (and what the buyer brings) taken, the land and the fees paid, the builders told. */
    public static Result place(ServerLevel level, ServerPlayer buyer, Quote q) {
        UUID id = q.village;
        Villages.Village v = Villages.get(id);
        if (v == null) return new Result(false, "", "There's no town for that order.");
        if (!q.ok) return new Result(false, "", "The builders won't build there: " + q.why + ".");
        if ("build".equals(q.kind)) {
            String p = plotProblem(level, v, q.drawing, q.plot, q.facing, null);
            if (!p.isEmpty()) return new Result(false, "", "The builders won't build there: " + p + ".");
        }
        int held = Market.coinsHeld(buyer);
        if (held < q.deposit) return new Result(false, "", "The deposit is " + q.deposit + Homes.coins(q.deposit) + "; you have " + held + ".");
        Map<Item, Integer> brought = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : q.supplied.entrySet()) {
            int got = takeCarried(buyer, e.getKey(), e.getValue());
            if (got > 0) brought.put(e.getKey(), got);
        }
        Market.payOut(buyer, q.deposit);
        CoinCensus.playerIn(id, q.deposit, buyer, PlayerFinance.Cat.INVESTED, "deposit on " + q.name);   // [join21]
        CompoundTag book = book(id);
        int n = book.getInt("next") + 1;
        book.putInt("next", n);
        int seq = book.getInt("seq") + 1;
        book.putInt("seq", seq);
        long day = level.getDayTime() / 24000L;
        CompoundTag o = new CompoundTag();
        o.putInt("n", n);
        o.putInt("seq", seq);
        o.putUUID("village", id);
        o.putUUID("buyer", buyer.getUUID());
        o.putString("buyerName", buyer.getName().getString());
        o.putString("kind", q.kind);
        o.putString("drawing", q.drawing);
        if (q.baseDrawing != null) o.putString("base", q.baseDrawing);
        o.putString("style", q.style);
        o.putString("use", q.use);
        o.putString("name", q.name);
        o.putLong("anchor", q.plot.asLong());
        o.putInt("facing", q.facing.get2DDataValue());
        o.putBoolean("priority", q.priority);
        o.putBoolean("lease", q.lease);
        o.putLong("placed", day);
        o.putString("status", "open");
        o.putLong("materials", pence(q.materialsCost));
        o.putLong("labour", pence(q.labourCost));
        o.putLong("overheads", pence(q.overheads));
        o.putLong("land", pence(q.landCost));
        o.putLong("lease", pence(q.leasePerWeek));
        o.putLong("fees", pence(q.fees));
        o.putLong("margin", pence(q.margin));
        o.putLong("premium", pence(q.premium));
        o.putInt("total", q.total);
        o.putInt("deposit", q.deposit);
        o.putInt("stageAmt", q.stage);
        o.putInt("balanceAmt", q.balance);
        o.putInt("paid", q.deposit);
        o.putInt("stagePaid", 0);
        o.putLong("escrow", q.deposit * 100L);
        CompoundTag prices = new CompoundTag();
        for (Material m : q.materials) prices.putLong(key(m.item()), pence(m.unit()));
        o.put("prices", prices);
        CompoundTag sup = new CompoundTag();
        for (Map.Entry<Item, Integer> e : brought.entrySet()) sup.putInt(key(e.getKey()), e.getValue());
        o.put("supplied", sup);
        o.put("broughtAll", sup.copy());
        if (!q.explicit.isEmpty()) {
            ListTag l = new ListTag();
            for (Cell c : q.explicit) {
                CompoundTag t = new CompoundTag();
                t.putLong("p", c.pos().asLong());
                t.putString("b", BuiltInRegistries.BLOCK.getKey(c.block()).toString());
                t.putString("t", c.trade().name());
                l.add(t);
            }
            o.put("explicit", l);
        }
        if ("build".equals(q.kind)) {
            List<Cell> cells = structure(q.drawing, palette(q.style), q.plot, q.facing, null);
            List<BlockPos>[] g = ground(level, q.drawing, q.plot, q.facing, cells);
            o.putLongArray("cut", g[0].stream().mapToLong(BlockPos::asLong).toArray());
            o.putLongArray("fill", g[1].stream().mapToLong(BlockPos::asLong).toArray());
        } else if (!q.cutCells.isEmpty()) {
            o.putLongArray("cut", q.cutCells.stream().mapToLong(BlockPos::asLong).toArray());
        }
        if (q.eta != null) {
            o.putDouble("etaFirst", q.eta.days());
            o.putLong("etaDayFirst", q.finishDay);
            o.putDouble("eta", q.eta.days());
            o.putLong("etaDay", q.finishDay);
            o.putString("etaWhy", q.eta.reason());
        }
        o.putDouble("clock", hourNow(level, id));
        orders(id).put(Integer.toString(n), o);
        // The land and the fees to the treasury now (the permit is issued, the title or the lease written); a premium too.
        long now = pence(q.landCost) + pence(q.fees) + pence(q.premium);
        now = Math.min(now, o.getLong("escrow"));
        o.putLong("escrow", o.getLong("escrow") - now);
        Businesses.toTreasury(id, now);
        TownBudget.in(id, "fees", now);
        o.putLong("toTown", now);
        if ("build".equals(q.kind)) {
            int[] half = BuildGoal.footprint(q.drawing);
            Villages.holdLot(id, q.plot, half[0], half[1], q.facing);                   // [orders17] the town plans round it
            if (q.landCost > 0) PropertyMarket.landBought(level, id, buyer.getUUID(), buyer.getName().getString(), q.plot, q.drawing, q.facing,
                q.lease ? pence(q.leasePerWeek) : 0, pence(q.landCost), day);
        }
        Accounts.dirty();
        String oid = idOf(id, o);
        Villages.tell(id, day, buyer.getName().getString() + " commissioned " + q.name + " from the town's builders: " + q.total
            + Homes.coins(q.total) + " all told, " + q.deposit + " down; " + (q.eta == null ? "" : q.eta.words()));
        return new Result(true, oid, "Ordered: " + q.name + ", " + q.total + Homes.coins(q.total) + " all told. You paid " + q.deposit + " down"
            + (brought.isEmpty() ? "" : " and handed over your own materials") + ". " + (q.eta == null ? "" : "Expect it in " + q.eta.words() + "."));
    }

    static long pence(double coins) {
        return Math.round(coins * 100.0);
    }

    static String key(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    // ------------------------------------------------------------------ the status

    public static final class Status {
        public String orderId = "", name = "", stage = "", waitingOn = "", status = "";
        public int percent, paid, due, refundable, total;
        public Eta eta;
        public long etaDay, etaDayFirst;
        public List<String> history = new ArrayList<>();

        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add(name + ": " + percent + "% - " + stage);
            if (!waitingOn.isEmpty()) out.add("Waiting: " + waitingOn);
            if (eta != null && !status.equals("done") && !status.equals("cancelled")) {
                out.add("Expected about day " + (etaDay + 1) + " (" + eta.words() + " from now)" + (etaDayFirst > 0 && etaDay != etaDayFirst ? "; first quoted day " + (etaDayFirst + 1) : ""));
                out.add(eta.reason());
            }
            out.add("Paid " + paid + " of " + total + (due > 0 ? "; " + due + " due now" : "") + (refundable > 0 ? "; " + refundable + " would come back if cancelled" : ""));
            out.addAll(history);
            return out;
        }

        public CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString("id", orderId);
            t.putString("name", name);
            t.putString("stage", stage);
            t.putString("waiting", waitingOn);
            t.putString("status", status);
            t.putInt("percent", percent);
            t.putInt("paid", paid);
            t.putInt("due", due);
            t.putInt("total", total);
            t.putInt("refundable", refundable);
            t.putLong("etaDay", etaDay);
            ListTag l = new ListTag();
            for (String s : lines()) l.add(StringTag.valueOf(s));
            t.put("lines", l);
            return t;
        }
    }

    @Nullable
    public static Status status(ServerLevel level, String orderId) {
        CompoundTag o = order(orderId);
        if (o == null) return null;
        UUID id = villageOf(orderId);
        Villages.Village v = Villages.get(id);
        Status s = new Status();
        s.orderId = orderId;
        s.name = o.getString("name");
        s.status = o.getString("status");
        List<Cell> seq = seq(id, o);
        int laid = BitSet.valueOf(o.getLongArray("laid")).cardinality();
        s.percent = seq.isEmpty() ? 100 : Math.min(100, laid * 100 / seq.size());
        s.paid = o.getInt("paid");
        s.total = o.getInt("total");
        s.due = o.getInt("due");
        s.refundable = (int) (o.getLong("escrow") / 100);
        s.waitingOn = o.getString("waiting");
        s.etaDay = o.getLong("etaDay");
        s.etaDayFirst = o.getLong("etaDayFirst");
        for (Tag t : o.getList("history", Tag.TAG_STRING)) s.history.add(t.getAsString());
        switch (s.status) {
            case "done" -> s.stage = "done, handed over on day " + (o.getLong("done") + 1);
            case "cancelled" -> s.stage = "cancelled";
            case "awaiting" -> s.stage = "built: awaiting the balance";
            default -> s.stage = stageOf(id, o, seq, laid);
        }
        if (v != null && s.status.equals("open")) {
            s.eta = estimate(level, v, o.getBoolean("priority"), remainingMinutes(o), remainingMaterials(level, id, o), supplied(o), o.getInt("seq"));
        } else {
            s.eta = new Eta(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, s.status);
        }
        return s;
    }

    static String stageOf(UUID village, CompoundTag o, List<Cell> seq, int laid) {
        int pos = 1;
        for (CompoundTag x : open(village)) { if (x.getInt("n") == o.getInt("n")) break; pos++; }
        if (laid == 0 && o.getDouble("credit") <= 0) return "queued (" + ordinal(pos) + ")";
        BitSet b = BitSet.valueOf(o.getLongArray("laid"));
        int next = b.nextClearBit(0);
        if (next >= seq.size()) return "finishing";
        Cell c = seq.get(next);
        if (c.placement() == null && !o.contains("explicit", Tag.TAG_LIST)) return "groundwork";
        if (c.fitting()) return o.contains("explicit", Tag.TAG_LIST) ? "refitting" : "fitting out";
        if (c.trade() == Trade.ROOFERS) return "the roof";
        return "walls (course at y " + c.pos().getY() + ")";
    }

    static Map<Item, Integer> supplied(CompoundTag o) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        CompoundTag s = o.getCompound("supplied");
        for (String k : s.getAllKeys()) out.put(BuiltInRegistries.ITEM.get(ResourceLocation.parse(k)), s.getInt(k));
        return out;
    }

    static List<Material> remainingMaterials(ServerLevel level, UUID village, CompoundTag o) {
        List<Cell> seq = seq(village, o);
        BitSet laid = BitSet.valueOf(o.getLongArray("laid"));
        Map<Item, Integer> n = new LinkedHashMap<>();
        for (int i = laid.nextClearBit(0); i < seq.size(); i = laid.nextClearBit(i + 1)) if (seq.get(i).item() != Items.AIR) n.merge(seq.get(i).item(), 1, Integer::sum);
        List<Material> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : n.entrySet()) out.add(new Material(e.getKey(), new ItemStack(e.getKey()).getHoverName().getString(), e.getValue(), 0, 0, 0));
        return out;
    }

    // ------------------------------------------------------------------ the work

    private static final Map<UUID, Double> OFFSET = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();

    /** The town's clock in hours (game day time / 1000), and what the tests have wound it on by. */
    static double hourNow(ServerLevel level, UUID village) {
        return level.getDayTime() / 1000.0 + OFFSET.getOrDefault(village, 0.0);
    }

    /** Every fifteen seconds or so (Grow.tick): the open orders worked for the hours gone by. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 300L && now >= last) return;
        TICKED.put(id, now);
        if (!Accounts.town(id).contains(BOOK, Tag.TAG_COMPOUND)) return;
        CompoundTag all = orders(id);
        if (all.isEmpty()) return;
        double hour = hourNow(level, id);
        for (CompoundTag o : open(id)) advance(level, v, o, hour, true);
    }

    /** Tests: the town's clock wound on so many hours, every open order worked through them (no hand needs to walk there). */
    public static void hoursForTests(ServerLevel level, Villages.Village v, double hours) {
        double start = hourNow(level, v.id());
        for (double h = 0.5; h <= hours + 1e-9; h += 0.5) {
            OFFSET.merge(v.id(), 0.5, Double::sum);
            for (CompoundTag o : open(v.id())) advance(level, v, o, start + h, false);
        }
    }

    /** The order worked from its clock up to this hour, half an hour at a time. */
    static void advance(ServerLevel level, Villages.Village v, CompoundTag o, double hour, boolean live) {
        double clock = o.getDouble("clock");
        if (hour <= clock) return;
        if (hour - clock > CATCH_UP) clock = hour - CATCH_UP;
        UUID id = v.id();
        List<CompoundTag> queue = open(id);
        int k = crews(id), pos = 0;
        for (CompoundTag x : queue) { if (x.getInt("n") == o.getInt("n")) break; pos++; }
        while (clock < hour - 1e-9) {
            double step = Math.min(0.5, hour - clock);
            double hod = ((clock % 24) + 24) % 24;
            clock += step;
            if (!o.getString("status").equals("open")) break;
            if (pos >= k) { waiting(level, id, o, "queued: " + ordinal(pos + 1) + " for the builders' crews"); continue; }
            int[] w = window(level, id, o.getBoolean("priority"));
            if (hod < w[0] || hod >= w[1]) continue;                  // off the builders' hours (or the town's own building's mornings)
            if (o.getInt("due") > 0 && !collect(level, id, o)) { waiting(level, id, o, "your stage payment of " + o.getInt("due") + " coins"); continue; }
            BlockPos anchor = BlockPos.of(o.getLong("anchor"));
            if (live && !TownJobs.instantNow()) {
                if (!Land.areaLoaded(level, anchor, 9)) continue;
                if (!TownJobs.atWork(level, v, "commission" + o.getInt("n"), anchor, "building " + o.getString("buyerName") + "'s " + o.getString("name"))) {
                    waiting(level, id, o, "a hand on its way to the site");
                    continue;
                }
            }
            o.putDouble("credit", o.getDouble("credit") + step * 60.0 * hands(id) * skill(id));
            work(level, v, o);
        }
        o.putDouble("clock", Math.max(o.getDouble("clock"), clock));
        Accounts.dirty();
    }

    static void waiting(ServerLevel level, UUID village, CompoundTag o, String why) {
        if (why.equals(o.getString("waiting"))) return;
        o.putString("waiting", why);
        revise(level, village, o, why);
    }

    /** The estimate written again, with its reason when it moved by half a day or more. */
    static void revise(ServerLevel level, UUID village, CompoundTag o, String why) {
        Villages.Village v = Villages.get(village);
        if (v == null || !o.getString("status").equals("open")) return;
        Eta e = estimate(level, v, o.getBoolean("priority"), remainingMinutes(o), remainingMaterials(level, village, o), supplied(o), o.getInt("seq"));
        long today = (long) Math.floor(hourNow(level, village) / 24.0);
        long day = today + (long) Math.ceil(e.days());
        long was = o.getLong("etaDay");
        o.putDouble("eta", e.days());
        o.putString("etaWhy", e.reason());
        if (Math.abs(day - was) >= 1 && !why.isEmpty()) {
            ListTag h = o.getList("history", Tag.TAG_STRING);
            h.add(StringTag.valueOf("Day " + (today + 1) + ": now about day " + (day + 1) + " (was " + (was + 1) + "): " + why));
            while (h.size() > 6) h.remove(0);
            o.put("history", h);
            o.putLong("etaDay", day);
        }
    }

    /** Lay what the credit runs to, from the cell the work is at: each block out of what the buyer brought or the stores. */
    static void work(ServerLevel level, Villages.Village v, CompoundTag o) {
        UUID id = v.id();
        List<Cell> seq = seq(id, o);
        BitSet laid = BitSet.valueOf(o.getLongArray("laid"));
        double credit = o.getDouble("credit");
        BlockPos anchor = BlockPos.of(o.getLong("anchor"));
        Direction facing = Direction.from2DDataValue(o.getInt("facing"));
        Map<BlockPos, BlockState> lay = new HashMap<>();
        Map<BlockPos, Cell> explicitLay = new HashMap<>();
        String shortOf = "";
        long day = (long) Math.floor(hourNow(level, id) / 24.0);
        int next = laid.nextClearBit(0);
        int y = next < seq.size() ? seq.get(next).pos().getY() : 0;
        boolean things = next < seq.size() && seq.get(next).fitting();
        boolean groundPhase = next < seq.size() && seq.get(next).placement() == null && !o.contains("explicit", Tag.TAG_LIST);
        int laidNow = 0;
        CompoundTag prices = o.getCompound("prices");
        for (int i = next; i < seq.size() && credit > 0; i = laid.nextClearBit(i + 1)) {
            Cell c = seq.get(i);
            boolean ground = c.placement() == null && !o.contains("explicit", Tag.TAG_LIST);
            if (ground != groundPhase) break;
            if (!ground && c.fitting() != things) break;
            if (!ground && !things && c.pos().getY() != y) break;
            if (credit < c.minutes()) break;
            long cost = c.item() == Items.AIR ? 0 : prices.getLong(key(c.item()));
            if (c.item() != Items.AIR && cost == 0) cost = pence(unit(level, id, c.item()));
            boolean own = c.item() != Items.AIR && useSupplied(o, c.item());
            if (!own && cost > 0 && o.getLong("escrow") < cost) {
                // Held too little for the next block: the next payment falls due now (none left: the builders' own risk on the odd penny).
                askNext(o);
                if (o.getInt("due") > 0 && !collect(level, id, o)) { shortOf = "your next payment"; break; }
            }
            if (c.item() != Items.AIR && !own) {
                boolean had;
                try (Accounts.Scope paid = Accounts.free("a player's commission, paid by the block")) { had = takeOne(level, v, c); }
                if (!had) {
                    if (c.fitting() && left(o, day)) { laid.set(i); o.putInt("leftOut", o.getInt("leftOut") + 1); continue; }
                    if (shortOf.isEmpty()) shortOf = StoneYard.yardWork(c.item()) && StoneYard.has(id)          // [featbuild] the yard's stone
                        ? StoneYard.waitWords(remainingOf(seq, laid, c.item(), i), c.item())
                        : remainingOf(seq, laid, c.item(), i) + " " + new ItemStack(c.item()).getHoverName().getString().toLowerCase(Locale.ROOT);
                    continue;                                       // another of the course may be had
                }
                spend(level, id, o, cost, true);
            }
            credit -= c.minutes();
            o.putLong("labourDue", o.getLong("labourDue") + pence(c.minutes() / 60.0 * hourly(id, c.trade())));
            laid.set(i);
            laidNow++;
            if (c.block() == null) clearCell(level, v, c.pos());
            else if (c.placement() == null) explicitLay.put(c.pos(), c);
            else lay.put(c.pos(), stateFor(c, o.getString("style")));
        }
        if (!lay.isEmpty()) {
            for (BlockPos p : lay.keySet()) clearCell(level, v, p);
            String drawing = o.getString("drawing");
            BuildGoal.stampOnly(level, drawing, anchor, facing, 13, p -> lay.get(p.pos()), p -> lay.containsKey(p.pos()));
        }
        for (Map.Entry<BlockPos, Cell> e : explicitLay.entrySet()) {
            Cell c = e.getValue();
            if (c.item() == Items.COBBLESTONE && c.block() == Blocks.COBBLESTONE) { level.setBlock(e.getKey(), Blocks.COBBLESTONE.defaultBlockState(), 2 | 16); continue; }
            placeKit(level, v, e.getKey(), c.block(), facing);
        }
        o.putLongArray("laid", laid.toLongArray());
        o.putDouble("credit", laidNow == 0 && !shortOf.isEmpty() ? 0 : Math.min(credit, 120));
        payHands(level, v, o, false);
        if (!shortOf.isEmpty()) {
            if (o.getLong("waitSince") <= 0) o.putLong("waitSince", day + 1);
            waiting(level, id, o, shortOf.equals("your next payment") ? "your next payment of " + o.getInt("due") + " coins"
                : shortOf.startsWith("stone from the yard") ? "waiting on " + shortOf : "waiting for " + shortOf);   // [featbuild]
        } else if (laidNow > 0) {
            o.putLong("waitSince", 0);
            if (!o.getString("waiting").isEmpty()) { o.putString("waiting", ""); revise(level, id, o, "under way again"); }
        }
        // The stage payment: due once so much is laid.
        if (o.getInt("stagePaid") == 0 && o.getInt("due") == 0 && laid.cardinality() * 100 >= seq.size() * STAGE_AT && o.getInt("stageAmt") > 0) {
            o.putInt("due", o.getInt("stageAmt"));
            collect(level, id, o);
        }
        if (laid.nextClearBit(0) >= seq.size()) complete(level, v, o, day);
    }

    /** The next payment falls due early: the stage, or else the balance. */
    static void askNext(CompoundTag o) {
        if (o.getInt("due") > 0) return;
        if (o.getInt("stagePaid") == 0 && o.getInt("stageAmt") > 0) o.putInt("due", o.getInt("stageAmt"));
        else if (o.getInt("balancePaid") == 0 && o.getInt("balanceAmt") > 0) o.putInt("due", o.getInt("balanceAmt"));
    }

    /** Waited long enough for a fitting: it is left out (and not charged for). */
    static boolean left(CompoundTag o, long day) {
        long since = o.getLong("waitSince") - 1;
        return since >= 0 && day - since >= LEAVE_OUT_DAYS;
    }

    static int remainingOf(List<Cell> seq, BitSet laid, Item it, int from) {
        int n = 0;
        for (int i = from; i < seq.size(); i++) if (!laid.get(i) && seq.get(i).item() == it) n++;
        return n;
    }

    static boolean useSupplied(CompoundTag o, Item it) {
        CompoundTag s = o.getCompound("supplied");
        String k = key(it);
        int n = s.getInt(k);
        if (n <= 0) return false;
        if (n == 1) s.remove(k); else s.putInt(k, n - 1);
        o.put("supplied", s);
        return true;
    }

    static BlockState stateFor(Cell c, String style) {
        BlockState st = Showcase.painter(palette(style)).apply(c.placement());
        return st == null ? c.block().defaultBlockState() : st;
    }

    /** One block's makings out of the stores: timber in planks, stone and brick made if need be, the fittings as the folk's houses take them. */
    static boolean takeOne(ServerLevel level, Villages.Village v, Cell c) {
        if (c.block() == null) return true;
        if (c.placement() != null && HousingMarket.furnishing(c.placement().part())) {
            BlockState st = HousingMarket.takeThing(level, v, c.placement(), HousingMarket.Design.COTTAGE);
            if (st != null) return true;
        }
        // [featbuild] City buildings draw their stone from the stone yard first (StoneYard), then the stores.
        if (StoneYard.isYardStone(c.item()) && StoneYard.forBuild(level, v, c.item(), 1, "a commission")) return true;
        Map<Block, Integer> want = new LinkedHashMap<>();
        want.put(c.block() == Blocks.WALL_TORCH ? Blocks.TORCH : c.block(), 1);
        if (HousingMarket.takeBlocks(level, v, want)) return true;
        if (Crafts.take(level, v, s -> s.is(c.item()), 1)) return true;
        // [featbuild] A cut stone (stairs, slabs, walls) the stores are short of: its blocks carted from the yard, then cut.
        if (StoneYard.feedStores(level, v, c.item(), "a commission")) return HousingMarket.takeBlocks(level, v, want) || Crafts.take(level, v, s -> s.is(c.item()), 1);
        return false;
    }

    static void clearCell(ServerLevel level, Villages.Village v, BlockPos at) {
        BlockState st = level.getBlockState(at);
        if (st.isAir() || !st.getFluidState().isEmpty() && st.getBlock() != Blocks.WATER) return;
        if (!BuildGoal.soft(st) && !st.hasBlockEntity()) {
            Item it = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) ? Items.DIRT : st.getBlock().asItem();
            if (it != Items.AIR) Crafts.store(level, v, new ItemStack(it));
        }
        if (!st.hasBlockEntity()) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
    }

    /** A piece of kit set down in a refit: facing into the room, a bed laid with its head. */
    static void placeKit(ServerLevel level, Villages.Village v, BlockPos at, Block b, Direction facing) {
        clearCell(level, v, at);
        BlockState st = b.defaultBlockState();
        if (b instanceof net.minecraft.world.level.block.BedBlock) {
            BlockPos head = at.relative(facing);
            level.setBlock(at, st.setValue(net.minecraft.world.level.block.BedBlock.FACING, facing), 3);
            level.setBlock(head, st.setValue(net.minecraft.world.level.block.BedBlock.FACING, facing)
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
            return;
        }
        if (st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            st = st.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, facing.getOpposite());
        }
        level.setBlock(at, st, 3);
    }

    // ------------------------------------------------------------------ the money

    /** Out of what is held: a block's price to the market (or the treasury before it opens). */
    static void spend(ServerLevel level, UUID village, CompoundTag o, long p, boolean materials) {
        p = Math.min(p, o.getLong("escrow"));
        if (p <= 0) return;
        o.putLong("escrow", o.getLong("escrow") - p);
        if (MarketMaker.live(village)) MarketMaker.cashIn(village, p);
        else Businesses.toTreasury(village, p);
        if (materials) {
            o.putLong("spentMaterials", o.getLong("spentMaterials") + p);
            Economy.spentInTown(village, (int) (p / 100));
        }
    }

    /** The builders' wages owed so far, in whole coins into the crew's purses (the odd pennies wait for the next block). */
    static void payHands(ServerLevel level, Villages.Village v, CompoundTag o, boolean last) {
        UUID id = v.id();
        long owed = o.getLong("labourDue") - o.getLong("labourPaid");
        if (owed <= 0) return;
        List<VillageFolkEntity> crew = crew(level, v, o);
        int share = Math.max(1, crew.size());
        long coins = Math.min(owed, o.getLong("escrow")) / 100;
        if (coins >= share || last && coins > 0) {
            long each = coins / share, extra = coins % share;
            if (crew.isEmpty()) {
                Businesses.toTreasury(id, coins * 100);           // no hand to be found: the council's crews, the treasury's
            } else {
                CompoundTag paid = Accounts.sub(o, "builders");
                for (int i = 0; i < crew.size(); i++) {
                    int c = (int) (each + (i < extra ? 1 : 0));
                    if (c <= 0) continue;
                    crew.get(i).earn(c);
                    Accounts.add(paid, crew.get(i).getName().getString(), c);
                }
            }
            o.putLong("escrow", o.getLong("escrow") - coins * 100);
            o.putLong("labourPaid", o.getLong("labourPaid") + coins * 100);
        }
        if (last) {
            long rest = Math.min(o.getLong("labourDue") - o.getLong("labourPaid"), o.getLong("escrow"));
            if (rest > 0) {
                Businesses.toTreasury(id, rest);                      // the odd pennies, to the council's crews
                o.putLong("escrow", o.getLong("escrow") - rest);
                o.putLong("labourPaid", o.getLong("labourPaid") + rest);
            }
        }
    }

    /** Who lays it: the hand the town sent (TownJobs), and the grown folk nearest the site whose own trades can spare them. */
    static List<VillageFolkEntity> crew(ServerLevel level, Villages.Village v, CompoundTag o) {
        int want = hands(v.id());
        BlockPos at = BlockPos.of(o.getLong("anchor"));
        List<VillageFolkEntity> out = new ArrayList<>();
        VillageFolkEntity sent = TownJobs.handAt(level, v.id(), "commission" + o.getInt("n"));
        if (sent != null) out.add(sent);
        List<VillageFolkEntity> rest = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby() || out.contains(f)) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) continue;
            rest.add(f);
        }
        rest.sort(Comparator.comparingDouble(f -> f.blockPosition().distSqr(at)));
        for (VillageFolkEntity f : rest) { if (out.size() >= want) break; out.add(f); }
        return out;
    }

    /** What is due now taken from the buyer if it is about with the coin; true when nothing is due any more. */
    static boolean collect(ServerLevel level, UUID village, CompoundTag o) {
        int due = o.getInt("due");
        if (due <= 0) return true;
        UUID buyer = uuid(o, "buyer");
        ServerPlayer p = buyer == null || level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(buyer);
        if (p == null || Market.coinsHeld(p) < due) return false;
        Market.payOut(p, due);
        CoinCensus.playerIn(village, due, p, PlayerFinance.Cat.INVESTED, "builders on " + o.getString("name"));   // [join21]
        received(level, village, o, due);
        p.displayClientMessage(net.minecraft.network.chat.Component.literal("Paid " + due + Homes.coins(due) + " to the builders on " + o.getString("name") + "."), false);
        return true;
    }

    /** A payment in from the buyer, against the stage or the balance. */
    static void received(ServerLevel level, UUID village, CompoundTag o, int coins) {
        o.putLong("escrow", o.getLong("escrow") + coins * 100L);
        o.putInt("paid", o.getInt("paid") + coins);
        if (o.getInt("stagePaid") == 0 && o.getInt("stageAmt") > 0) o.putInt("stagePaid", 1);
        else o.putInt("balancePaid", 1);
        o.putInt("due", 0);
        Accounts.dirty();
        if (o.getString("status").equals("awaiting")) {
            Villages.Village v = Villages.get(village);
            if (v != null) complete(level, v, o, (long) Math.floor(hourNow(level, village) / 24.0));
        }
    }

    /** The buyer pays what is due now (a stage, or the balance). */
    public static String pay(ServerLevel level, ServerPlayer p, String orderId) {
        CompoundTag o = order(orderId);
        if (o == null) return "No such order.";
        if (!p.getUUID().equals(uuid(o, "buyer"))) return "That order isn't yours.";
        UUID id = villageOf(orderId);
        if (o.getInt("due") <= 0 && o.getString("status").equals("open")) {
            // Paying ahead: the stage, then the balance.
            askNext(o);
            if (o.getInt("due") <= 0) return "Nothing is owed on " + o.getString("name") + ".";
        }
        int due = o.getInt("due");
        if (Market.coinsHeld(p) < due) return due + Homes.coins(due) + " is due; you have " + Market.coinsHeld(p) + ".";
        Market.payOut(p, due);
        CoinCensus.playerIn(id, due, p, PlayerFinance.Cat.INVESTED, "builders on " + o.getString("name"));   // [join21]
        received(level, id, o, due);
        return "Paid " + due + Homes.coins(due) + " on " + o.getString("name") + ".";
    }

    /** The buyer pays the priority premium on an order already placed: it goes ahead of the plain ones. */
    public static String prioritise(ServerLevel level, ServerPlayer p, String orderId) {
        CompoundTag o = order(orderId);
        if (o == null) return "No such order.";
        if (!p.getUUID().equals(uuid(o, "buyer"))) return "That order isn't yours.";
        if (o.getBoolean("priority")) return "It's on priority already.";
        if (!o.getString("status").equals("open")) return "It's not waiting on the builders.";
        UUID id = villageOf(orderId);
        double left = remainingMinutes(o) / 60.0;
        double labourLeft = 0;
        List<Cell> seq = seq(id, o);
        if (!seq.isEmpty()) labourLeft = o.getLong("labour") / 100.0 * left / Math.max(1e-6, totalMinutes(seq) / 60.0);
        int premium = (int) Math.ceil(labourLeft * (100 + OVERHEADS) / 100.0 * PRIORITY / 100.0 - 1e-9);
        premium = Math.max(1, premium);
        if (Market.coinsHeld(p) < premium) return "Priority costs " + premium + Homes.coins(premium) + "; you have " + Market.coinsHeld(p) + ".";
        Market.payOut(p, premium);
        CoinCensus.playerIn(id, premium, p, PlayerFinance.Cat.FEES, "priority on " + o.getString("name"));   // [join21]
        Businesses.toTreasury(id, premium * 100L);
        TownBudget.in(id, "fees", premium * 100L);
        o.putBoolean("priority", true);
        o.putLong("premium", o.getLong("premium") + premium * 100L);
        o.putInt("paid", o.getInt("paid") + premium);
        o.putInt("total", o.getInt("total") + premium);
        revise(level, id, o, "priority paid");
        Accounts.dirty();
        return "On priority, for " + premium + Homes.coins(premium) + ": ahead of the other commissions.";
    }

    static double totalMinutes(List<Cell> seq) {
        double m = 0;
        for (Cell c : seq) m += c.minutes();
        return m;
    }

    /** The order called off: what was not spent back to the buyer, and what it brought that was not laid. */
    public static String cancel(ServerLevel level, ServerPlayer p, String orderId) {
        CompoundTag o = order(orderId);
        if (o == null) return "No such order.";
        if (!p.getUUID().equals(uuid(o, "buyer"))) return "That order isn't yours.";
        if (!o.getString("status").equals("open")) return "It's " + o.getString("status") + " already.";
        UUID id = villageOf(orderId);
        Villages.Village v = Villages.get(id);
        if (v != null) payHands(level, v, o, true);                         // the builders keep what they earned
        long back = o.getLong("escrow");
        int coins = (int) (back / 100);
        if (back % 100 > 0) Businesses.toTreasury(id, back % 100);
        o.putLong("escrow", 0);
        if (coins > 0) {
            Dealings.giveCoins(p, coins);
            CoinCensus.playerOut(id, coins, p, PlayerFinance.Cat.CASHED, "refund on " + o.getString("name"));   // [join21]
        }
        o.putInt("refunded", coins);
        int items = 0;
        for (Map.Entry<Item, Integer> e : supplied(o).entrySet()) {
            ItemStack s = new ItemStack(e.getKey(), e.getValue());
            items += e.getValue();
            while (!s.isEmpty()) {
                ItemStack part = s.split(s.getMaxStackSize());
                if (!p.getInventory().add(part)) p.drop(part, false);
            }
        }
        o.put("supplied", new CompoundTag());
        o.putString("status", "cancelled");
        long day = level.getDayTime() / 24000L;
        int laid = BitSet.valueOf(o.getLongArray("laid")).cardinality();
        if ("build".equals(o.getString("kind"))) {
            if (laid == 0) {
                int[] half = BuildGoal.footprint(o.getString("drawing"));
                Villages.freeLot(id, BlockPos.of(o.getLong("anchor")), half[0], half[1], Direction.from2DDataValue(o.getInt("facing")));
            } else {
                PropertyMarket.registerBuilt(level, id, o, day, true);         // what stands is the buyer's
            }
        }
        Accounts.dirty();
        Villages.tell(id, day, p.getName().getString() + " called off " + o.getString("name") + " (" + laid + " blocks laid): " + coins + Homes.coins(coins) + " back");
        return "Cancelled. " + coins + Homes.coins(coins) + " back (what wasn't spent)" + (items > 0 ? " and " + items + " of your own materials" : "")
            + (laid > 0 ? "; what was built is yours, on the property register." : ".");
    }

    // ------------------------------------------------------------------ completion

    /** It stands: the margin and overheads to the treasury, the balance asked for, and the building handed over once paid. */
    static void complete(ServerLevel level, Villages.Village v, CompoundTag o, long day) {
        UUID id = v.id();
        payHands(level, v, o, true);
        long owed = o.getLong("overheads") + o.getLong("margin");
        long already = o.getLong("marginPaid");
        long want = Math.max(0, owed - already);
        if (o.getLong("escrow") < want) {
            askNext(o);
            if (o.getInt("due") <= 0) o.putInt("due", (int) Math.ceil((want - o.getLong("escrow")) / 100.0));
            if (!collect(level, id, o)) {
                if (!o.getString("status").equals("awaiting")) {
                    o.putString("status", "awaiting");
                    o.putString("waiting", "your balance of " + o.getInt("due") + " coins: the keys wait at the hall");
                    tellBuyer(level, o, o.getString("name") + " is built. The balance of " + o.getInt("due") + Homes.coins(o.getInt("due")) + " is due; the keys wait for it.");
                }
                Accounts.dirty();
                return;
            }
        }
        long k = Math.min(want, o.getLong("escrow"));
        if (k > 0) {
            o.putLong("escrow", o.getLong("escrow") - k);
            Businesses.toTreasury(id, k);
            TownBudget.in(id, "fees", k);
            o.putLong("marginPaid", already + k);
        }
        // What was held and not spent (fittings left out, a cheaper course) goes back to the buyer.
        long left = o.getLong("escrow");
        o.putLong("escrow", 0);
        int coins = (int) (left / 100);
        if (left % 100 > 0) Businesses.toTreasury(id, left % 100);
        if (coins > 0) toPlayer(level, id, uuid(o, "buyer"), coins, PlayerFinance.Cat.CASHED, "unspent on " + o.getString("name"));   // [join21]
        o.putInt("refunded", coins);
        o.putString("status", "done");
        o.putLong("done", day);
        o.putString("waiting", "");
        o.putInt("due", 0);
        if ("build".equals(o.getString("kind"))) PropertyMarket.registerBuilt(level, id, o, day, false);
        else Refits.finished(level, id, o, day);
        Accounts.dirty();
        long first = o.getLong("etaDayFirst");
        String names = String.join(", ", Accounts.sub(o, "builders").getAllKeys());
        Villages.tell(id, day, o.getString("name") + " went up for " + o.getString("buyerName") + ", every block paid for"
            + (names.isEmpty() ? "" : "; built by " + names) + " (quoted for day " + (first + 1) + ", done day " + (day + 1) + ")");
        tellBuyer(level, o, o.getString("name") + " is finished and yours" + (coins > 0 ? "; " + coins + Homes.coins(coins) + " not spent came back to you" : "") + ".");
    }

    static void tellBuyer(ServerLevel level, CompoundTag o, String text) {
        UUID buyer = uuid(o, "buyer");
        ServerPlayer p = buyer == null || level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(buyer);
        if (p != null) p.displayClientMessage(net.minecraft.network.chat.Component.literal(text), false);
    }

    /** Coin to a player: into its pack if it is about, else waiting for it at the hall (counted in the census either way). */
    static void toPlayer(ServerLevel level, UUID village, @Nullable UUID player, int coins) {
        toPlayer(level, village, player, coins, PlayerFinance.Cat.CASHED, "from the builders");
    }

    /** [join21] The same, written in the player's own books as so much of a kind (Your finances). */
    static void toPlayer(ServerLevel level, UUID village, @Nullable UUID player, int coins, PlayerFinance.Cat cat, String what) {
        if (player == null || coins <= 0) { if (coins > 0) Businesses.toTreasury(village, coins * 100L); return; }
        ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(player);
        if (p != null) {
            Dealings.giveCoins(p, coins);
            CoinCensus.playerOut(village, coins, p, cat, what);   // [join21]
        } else {
            Accounts.add(Accounts.sub(TownBonds.book(village), "due"), player.toString(), coins * 100L);
            PlayerFinance.accrued(player, village, coins * 100L, cat);   // [join21] booked as this kind when collected
            CapitalBook.dirty();
        }
    }

    // ------------------------------------------------------------------ tests

    public static void resetForTests() {
        SEQ.clear();
        OFFSET.clear();
        TICKED.clear();
    }

    public static CompoundTag orderForTests(String orderId) {
        return order(orderId);
    }

    public static int laidForTests(String orderId) {
        CompoundTag o = order(orderId);
        return o == null ? 0 : BitSet.valueOf(o.getLongArray("laid")).cardinality();
    }

    public static int cellsForTests(String orderId) {
        CompoundTag o = order(orderId);
        return o == null ? 0 : seq(villageOf(orderId), o).size();
    }

    public static double hoursPassedForTests(UUID village) {
        return OFFSET.getOrDefault(village, 0.0);
    }
}
