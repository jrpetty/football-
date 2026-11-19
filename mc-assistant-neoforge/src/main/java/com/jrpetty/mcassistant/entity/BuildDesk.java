package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * [player15] A building the town builds for a player: the one place the player's screens ask for a quote, place the
 * order, watch it go up and cancel it. [join21] The town's build orders answer every call (BuildOrders: the builders'
 * queue, honest costs line by line, the deposit and the stage payments, the time with its reason, the property register),
 * so the gallery, the confirm step, the progress bars and toasts and the property office all tell the same story:
 * <ul>
 * <li><b>The quote</b> is BuildOrders.quote's: materials at the market's ask, each trade's hours at its wage, overheads,
 *     the land (bought or leased), the permit, rates and registry, the builders' margin; a deposit down, a stage payment at
 *     half, the balance on completion; how long, as a range, and why.</li>
 * <li><b>Building</b>: the town's own crews put it up course by course (BuildOrders), and say what they wait on; a word to
 *     the player at each quarter. A shop or premises comes complete: when it stands, its counter and a chest for its stock
 *     are set in, and it is the player's shop (and its firm's premises, if a firm ordered it).</li>
 * </ul>
 * The link from an order to what it is for (a firm's premises, a shop) is kept in the town's ledger (notes
 * "pbuild2/&lt;order&gt;").
 */
public final class BuildDesk {

    private BuildDesk() {}

    private static final String NOTE = "pbuild2/";

    /** The styles a building may be had in here (BuildOrders: the timbers, then stone, brick and the civic look as the age allows). */
    public static List<String> styles(UUID village) {
        return BuildOrders.styles(village);
    }

    /** A style's name on a button. */
    public static String styleLabel(String style) {
        return switch (style) {
            case "oak" -> "Oak";
            case "birch" -> "Birch";
            case "spruce" -> "Spruce";
            case "stone" -> "Stone";
            case "brick" -> "Brick";
            case "civic" -> "Civic";
            default -> style;
        };
    }

    /** A building the town will put up for a player: its drawing, what it is called, what it suits and what is in it. */
    public record Design(String blueprint, String title, String suits, String inside) {}

    public static final List<Design> PREMISES = List.of(
        new Design("shop", "A shop", "general store, grocer's, home goods, clothier's, tool shop", "a counter, shelves and a stockroom"),
        new Design("bakery", "A bakery", "bakery, butcher's, café", "an oven, a counter and a store"),
        new Design("smithy", "A smithy", "toolsmith, armourer", "a forge, an anvil and a counter"),
        new Design("cafe", "A café", "café, tavern, bakery", "tables, a kitchen and a counter"),
        new Design("tavern", "A tavern", "tavern, inn", "a bar, seats and a cellar"),
        new Design("store", "A store", "general store, greengrocer's, stationer's", "a long counter and a storeroom"));

    public static final List<Design> HOMES = List.of(
        new Design("house", "A cottage", "a home", "four beds"),
        new Design("house2", "A family house", "a home", "six beds, two storeys"),
        new Design("villa", "A villa", "a home", "six beds, a parlour and a porch"));

    @Nullable
    public static Design design(String blueprint) {
        for (Design d : PREMISES) if (d.blueprint().equals(blueprint)) return d;
        for (Design d : HOMES) if (d.blueprint().equals(blueprint)) return d;
        return null;
    }

    /** Which drawing suits a kind of business best. */
    public static String forBusiness(String type) {
        return switch (type) {
            case "baker", "butcher" -> "bakery";
            case "toolsmith", "armourer" -> "smithy";
            case "cafe" -> "cafe";
            case "tavern", "inn" -> "tavern";
            case "generalstore", "greengrocer", "stationer" -> "store";
            default -> "shop";
        };
    }

    // ------------------------------------------------------------------ the quote (BuildOrders')

    /** What putting this up here would cost, all told, and how long it would take: the town's builders' own quote. */
    public static BuildOrders.Quote quote(ServerLevel level, UUID village, String blueprint, String style, BlockPos plot, Direction facing, UUID buyer) {
        return BuildOrders.quote(level, village, blueprint, style, plot, facing, buyer);
    }

    /** "materials 120c (oak planks, glass...), labour 40c (32 hours), ...: 230c all told" and the time, for a card. */
    public static List<String> quoteLines(BuildOrders.Quote q) {
        List<String> out = new ArrayList<>();
        StringBuilder m = new StringBuilder();
        List<BuildOrders.Material> mats = new ArrayList<>(q.materials);
        mats.sort((a, b) -> Double.compare(b.total(), a.total()));
        for (int i = 0; i < Math.min(3, mats.size()); i++) {
            BuildOrders.Material x = mats.get(i);
            m.append(i == 0 ? "" : ", ").append(x.count() - x.supplied()).append(" ").append(x.name().toLowerCase(Locale.ROOT));
        }
        out.add(String.format(Locale.ROOT, "Materials %.0fc (%s%s), labour %.0fc (%.0f hours), overheads %.0fc, land %.0fc (%s), fees %.0fc, builders' margin %.0fc: %dc all told.",
            q.materialsCost, m, mats.size() > 3 ? "..." : "", q.labourCost, q.labourHours, q.overheads, q.landCost, q.landTerms, q.fees, q.margin, q.total));
        out.add("Paid " + q.deposit + "c down, " + q.stage + "c at half built, " + q.balance + "c when it stands."
            + (q.eta == null ? "" : " Ready in " + q.eta.words() + ": " + q.eta.reason() + "."));
        return out;
    }

    // ------------------------------------------------------------------ orders

    /** An order as the player's pages show it: its id, name, how far on, the stage, what it waits on, when, what is due. */
    public record Status(String id, String title, int percent, String stage, String waitingOn, String eta, int due, boolean done) {
        public String words() {
            return done ? title + " stands, finished." : title + " is " + percent + "% built" + (waitingOn.isEmpty() ? "" : ": " + waitingOn);
        }
    }

    /** Place the order with the town's builders (BuildOrders.place takes the deposit). Returns its id, or "!why". */
    public static String place(ServerLevel level, ServerPlayer p, UUID village, BuildOrders.Quote q, String purpose, @Nullable String firm) {
        if (!q.ok) return "!Not there: " + q.why + ".";
        String bad = BuildPlots.whyNot(level, Villages.get(village), q.drawing, q.plot, q.facing);
        if (!bad.isEmpty()) return "!Not there: " + bad + ".";
        BuildOrders.Result r = BuildOrders.place(level, p, q);
        if (!r.ok()) return "!" + r.message();
        CompoundTag o = new CompoundTag();
        o.putString("order", r.orderId());
        o.putUUID("owner", p.getUUID());
        o.putString("purpose", purpose);
        if (firm != null) o.putString("firm", firm);
        o.putInt("quarter", 0);
        Ledger.note(village, NOTE + r.orderId(), o.toString());
        return r.orderId();
    }

    /** The links in this town: order id -> what it is for. */
    static Map<String, CompoundTag> links(UUID village) {
        Map<String, CompoundTag> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith(NOTE)) continue;
            try {
                out.put(e.getKey().substring(NOTE.length()), TagParser.parseTag(e.getValue()));
            } catch (Exception ex) {
                // a note that could not be read is left alone
            }
        }
        return out;
    }

    /** The player's orders with the town's builders here, going up and lately done (the last two days). */
    public static List<Status> mine(ServerLevel level, UUID village, UUID player) {
        List<Status> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        for (String id : BuildOrders.ordersOf(village, player)) {
            BuildOrders.Status s = BuildOrders.status(level, id);
            if (s == null || s.status.equals("cancelled")) continue;
            CompoundTag o = BuildOrders.order(id);
            if (s.status.equals("done") && o != null && o.getLong("done") + 2 < day) continue;
            out.add(view(s));
        }
        return out;
    }

    static Status view(BuildOrders.Status s) {
        boolean done = s.status.equals("done");
        String name = s.name.isEmpty() ? "Your building" : "Your " + s.name.replaceFirst("^an? ", "");
        return new Status(s.orderId, name, done ? 100 : s.percent, s.stage, s.waitingOn,
            s.eta == null || done ? "" : s.eta.words(), s.due, done);
    }

    @Nullable
    public static Status status(ServerLevel level, String id) {
        BuildOrders.Status s = BuildOrders.status(level, id);
        return s == null ? null : view(s);
    }

    /** Cancel it (BuildOrders: the builders keep what they earned, what was held and not spent comes back). */
    public static String cancel(ServerLevel level, ServerPlayer p, String id) {
        return BuildOrders.cancel(level, p, id);
    }

    // ------------------------------------------------------------------ following the orders

    /** Every couple of seconds for a town (PlayerShops.tick): a word at each quarter; a finished shop fitted out. */
    public static void tick(ServerLevel level, Villages.Village v) {
        for (Map.Entry<String, CompoundTag> e : links(v.id()).entrySet()) {
            CompoundTag t = e.getValue();
            if (t.getBoolean("fitted")) continue;
            BuildOrders.Status s = BuildOrders.status(level, e.getKey());
            if (s == null || s.status.equals("cancelled")) {
                Ledger.forget(v.id(), NOTE + e.getKey());
                continue;
            }
            ServerPlayer owner = t.hasUUID("owner") && level.getServer() != null ? level.getServer().getPlayerList().getPlayer(t.getUUID("owner")) : null;
            int q = s.status.equals("done") ? 4 : s.percent / 25;
            if (q > t.getInt("quarter") && owner != null) {
                t.putInt("quarter", q);
                if (q < 4) PlayerShops.notify(owner, view(s).words() + (s.eta == null ? "" : "; done in " + s.eta.words()));
                Ledger.note(v.id(), NOTE + e.getKey(), t.toString());
            }
            if (s.status.equals("done") && owner != null) {
                CompoundTag o = BuildOrders.order(e.getKey());
                if (o != null) finish(level, v, t, o, owner);
                t.putBoolean("fitted", true);
                Ledger.note(v.id(), NOTE + e.getKey(), t.toString());
            }
        }
    }

    /** It stands: a shop or premises gets its counter and a chest, and is the player's shop (and its firm's premises). */
    static void finish(ServerLevel level, Villages.Village v, CompoundTag t, CompoundTag o, ServerPlayer owner) {
        String drawing = o.getString("drawing");
        PlayerShops.notify(owner, "Your " + BuildOrders.title(drawing).replaceFirst("^an? ", "") + " is finished!");
        if (BuildOrders.home(drawing)) return;
        BlockPos anchor = BlockPos.of(o.getLong("anchor"));
        Direction facing = Direction.from2DDataValue(o.getInt("facing"));
        List<BuildGoal.Placement> plan = BuildGoal.plan(drawing, anchor, facing, 8);
        // The counter: in place of its first cask or barrel, else just inside the door, facing the way in.
        BlockPos counter = null, chest = null, door = null;
        for (BuildGoal.Placement p : plan) {
            if (counter == null && p.part() == BuildGoal.Part.BARREL) counter = p.pos();
            else if (chest == null && p.part() == BuildGoal.Part.CHEST) chest = p.pos();
            if (door == null && p.part() == BuildGoal.Part.DOOR) door = p.pos();
        }
        if (counter == null && door != null) counter = door.relative(facing.getOpposite(), 2);
        if (counter == null || !level.isLoaded(counter)) return;
        level.setBlockAndUpdate(counter, McAssistantMod.SHOP_COUNTER.get().defaultBlockState()
            .setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, facing));
        if (chest == null || !level.getBlockState(chest).is(Blocks.CHEST)) {
            BlockPos c = counter.relative(facing.getOpposite()).relative(facing.getClockWise());
            if (level.getBlockState(c).isAir()) level.setBlockAndUpdate(c, Blocks.CHEST.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing));
        }
        if (PlayerShops.of(v.id(), owner.getUUID()) == null) PlayerShops.placed(level, counter, owner, true);
        String firm = t.getString("firm");
        if (!firm.isEmpty()) {
            Business b = Businesses.of(v.id(), firm);
            if (b != null) {
                b.at = counter;
                CompoundTag r = Enterprise.rec(v.id(), b.id);
                if (r != null) r.putLong("at", counter.asLong());
                Businesses.dirty();
            }
            PlayerShops.Shop s = PlayerShops.of(v.id(), owner.getUUID());
            if (s != null) { s.firm = firm; PlayerShops.save(s); }
        }
    }

    // ------------------------------------------------------------------ the picture

    /** The drawing's blocks for the picture, in this style: x, y, z and the block's state id, four ints each. */
    public static int[] picture(String blueprint, String style) {
        Function<BuildGoal.Placement, BlockState> paint = Showcase.painter(BuildOrders.palette(style));   // [join21] the builders' own palette
        List<Integer> out = new ArrayList<>();
        Direction facing = Direction.SOUTH;
        for (BuildGoal.Placement p : BuildGoal.plan(blueprint, BlockPos.ZERO, facing, 8)) {
            BlockState st = paint.apply(p);
            if (st == null || st.isAir() || st.is(Blocks.WATER)) continue;
            st = orient(st, p, facing);
            out.add(p.pos().getX());
            out.add(p.pos().getY());
            out.add(p.pos().getZ());
            out.add(Block.getId(st));
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** Turned the way the drawing says, near enough for a picture. */
    static BlockState orient(BlockState st, BuildGoal.Placement p, Direction facing) {
        Direction d = p.way() == Blueprints.Way.UP ? facing : Blueprints.world(p.way(), facing);
        if (st.hasProperty(HorizontalDirectionalBlock.FACING)) st = st.setValue(HorizontalDirectionalBlock.FACING, st.getBlock() instanceof StairBlock ? d : d);
        if (st.hasProperty(StairBlock.HALF) && p.style() == Blueprints.Style.ROOF_STAIR_TOP) st = st.setValue(StairBlock.HALF, Half.TOP);
        if (st.hasProperty(RotatedPillarBlock.AXIS)) {
            if (p.style() == Blueprints.Style.BEAM_ACROSS) st = st.setValue(RotatedPillarBlock.AXIS, facing.getClockWise().getAxis());
            else if (p.style() == Blueprints.Style.BEAM_ALONG) st = st.setValue(RotatedPillarBlock.AXIS, facing.getAxis());
        }
        if (st.is(BlockTags.SLABS) && p.style() == Blueprints.Style.ROOF_SLAB_TOP && st.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)) {
            st = st.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP);
        }
        return st;
    }

    /** Its size: across, deep, storeys. */
    public static int[] size(String blueprint) {
        int[] g = BuildGoal.footprint(blueprint);
        int top = 0;
        for (BuildGoal.Placement p : BuildGoal.plan(blueprint, BlockPos.ZERO, Direction.SOUTH, 8)) top = Math.max(top, p.pos().getY());
        return new int[]{ 2 * g[0] + 1, 2 * g[1] + 1, Math.max(1, (top + 1) / 4) };
    }

    /** What is inside it, counted off the drawing. */
    public static String furnishing(String blueprint) {
        Map<BuildGoal.Part, Integer> n = BuildGoal.partCounts(blueprint, 8);
        List<String> out = new ArrayList<>();
        int chests = n.getOrDefault(BuildGoal.Part.CHEST, 0) + n.getOrDefault(BuildGoal.Part.BARREL, 0);
        if (chests > 0) out.add(chests + " chest" + (chests == 1 ? "" : "s") + " and casks");
        if (n.getOrDefault(BuildGoal.Part.FURNACE, 0) + n.getOrDefault(BuildGoal.Part.SMOKER, 0) > 0) out.add("an oven");
        if (n.getOrDefault(BuildGoal.Part.ANVIL, 0) > 0) out.add("an anvil");
        if (n.getOrDefault(BuildGoal.Part.CRAFTING_TABLE, 0) > 0) out.add("a workbench");
        if (n.getOrDefault(BuildGoal.Part.BED, 0) > 0) out.add(n.get(BuildGoal.Part.BED) + " beds");
        if (n.getOrDefault(BuildGoal.Part.DOOR, 0) > 0) out.add(n.get(BuildGoal.Part.DOOR) + " door" + (n.get(BuildGoal.Part.DOOR) == 1 ? "" : "s"));
        return out.isEmpty() ? "bare walls" : String.join(", ", out);
    }

    /** Tests: the link kept for an order (what it is for), or null. */
    @Nullable
    public static CompoundTag linkForTests(UUID village, String orderId) {
        return links(village).get(orderId);
    }
}
