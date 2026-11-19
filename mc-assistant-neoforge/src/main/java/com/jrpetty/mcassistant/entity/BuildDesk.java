package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * [player15] A building the town builds for a player: the one place the player's screens ask for a quote, place the
 * order, watch it go up and cancel it. The town's build orders (another round's BuildOrders: the builders' queue, honest
 * costs, the property market) answer behind these same calls; until they land, a plain engine here does the work, so
 * the gallery, the plot and the progress all work now:
 * <ul>
 * <li><b>The quote</b>: every block of the drawing at the town's price, the builders' hours at a craftsman's rate (twelve
 *     blocks an hour), the plot, the permit; a deposit of the whole; how long, and why.</li>
 * <li><b>Building</b>: the town's hands lay it a few blocks at a time from the footing up, out of the stores where the
 *     stores have the block (the rest bought at the market's price, which the quote paid for), and say what they are
 *     waiting on; a word to the player at each quarter, a line in the chronicle when it stands. A shop or premises comes
 *     complete: its counter and a chest for its stock are set in when the walls are up, and it is the player's shop.</li>
 * </ul>
 * Orders kept in the town's ledger (notes "pbuild/&lt;id&gt;").
 */
public final class BuildDesk {

    private BuildDesk() {}

    private static final String NOTE = "pbuild/";
    /** Blocks laid every two seconds by the plain engine, and how long it waits on the stores before buying a block in. */
    static final int PER_STEP = 8, WAIT = 400;

    /** The styles a building may be had in: the wood of the walls and the roof, or stone. */
    public enum Style {
        OAK("oak", "Oak and spruce", Showcase.OAK),
        BIRCH("birch", "Birch and dark oak", Showcase.BIRCH),
        BRICK("brick", "Spruce under brick", Showcase.SPRUCE),
        STONE("stone", "Stone and slate", new Showcase.Palette(Blocks.STONE_BRICKS, Blocks.DARK_OAK_LOG, Blocks.DEEPSLATE_TILE_STAIRS,
            Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILES, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.DARK_OAK_FENCE,
            Blocks.DARK_OAK_FENCE_GATE, Blocks.GRAY_BED, Blocks.GRAY_CARPET));

        public final String id, label;
        public final Showcase.Palette palette;

        Style(String id, String label, Showcase.Palette palette) {
            this.id = id;
            this.label = label;
            this.palette = palette;
        }

        public static Style of(String id) {
            for (Style s : values()) if (s.id.equals(id)) return s;
            return OAK;
        }
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

    // ------------------------------------------------------------------ the quote

    public record Line(String what, ItemStack item, int count, double each, double total) {}

    public record Quote(String blueprint, String style, BlockPos plot, Direction facing, List<Line> materials, double labourHours,
                        double labour, double land, double fees, double margin, double total, int deposit, int etaLow, int etaHigh,
                        String etaWhy) {}

    /** What putting this up here would cost, all told, and how long it would take. */
    public static Quote quote(ServerLevel level, UUID village, String blueprint, Style style, BlockPos plot, Direction facing, UUID buyer) {
        Map<Block, Integer> counts = new LinkedHashMap<>();
        Function<BuildGoal.Placement, BlockState> paint = Showcase.painter(style.palette);
        int blocks = 0;
        for (BuildGoal.Placement p : BuildGoal.plan(blueprint, plot, facing, 8)) {
            BlockState st = paint.apply(p);
            if (st == null || st.isAir()) continue;
            counts.merge(st.getBlock(), 1, Integer::sum);
            blocks++;
        }
        List<Line> lines = new ArrayList<>();
        double materials = 0;
        for (Map.Entry<Block, Integer> e : counts.entrySet()) {
            ItemStack one = new ItemStack(e.getKey().asItem());
            if (one.isEmpty()) continue;
            double each = Math.max(0.02, CostBook.atMarket(level, village, one));
            lines.add(new Line(one.getHoverName().getString(), one, e.getValue(), each, each * e.getValue()));
            materials += each * e.getValue();
        }
        lines.sort(Comparator.comparingDouble(Line::total).reversed());
        double hours = blocks / 12.0;
        double rate = Math.max(1, JobWorth.rate(AssistantEntity.StationTask.SMITH, village)) / 10.0;
        double labour = hours * rate;
        int[] g = BuildGoal.footprint(blueprint);
        double land = 4 + (2 * g[0] + 1) * (2 * g[1] + 1) / 10.0;
        double fees = Math.max(2, (materials + labour) / 20.0);
        double total = Math.ceil(materials + labour + land + fees);
        int busy = orders(village).size();
        int low = busy == 0 ? 1 : 1 + busy, high = low + 1;
        String why = busy == 0 ? "the town's hands are free to start at once" : busy + " order" + (busy == 1 ? "" : "s") + " ahead of yours";
        return new Quote(blueprint, style.id, plot.immutable(), facing, lines, hours, labour, land, fees, 0, total, (int) total, low, high, why);
    }

    // ------------------------------------------------------------------ orders

    public record Status(String id, String title, int percent, String stage, String waitingOn, int etaLow, int etaHigh, boolean done) {
        public String words() {
            return done ? title + " stands, finished." : title + " is " + percent + "% built" + (waitingOn.isEmpty() ? "" : ": waiting for " + waitingOn);
        }
    }

    /** Place the order: the whole of the quote out of the player's pack into the treasury. Returns its id, or "!why". */
    public static String place(ServerLevel level, ServerPlayer p, UUID village, Quote q, String purpose, @Nullable String firm) {
        if (Market.coinsHeld(p) < q.deposit()) return "!It costs " + PlayerLife.coins(q.deposit()) + " and you have " + Market.coinsHeld(p) + ".";
        String bad = BuildPlots.whyNot(level, Villages.get(village), q.blueprint(), q.plot(), q.facing());
        if (!bad.isEmpty()) return "!Not there: " + bad + ".";
        Market.payOut(p, q.deposit());
        Ledger.addCoins(village, q.deposit());
        CoinCensus.playerIn(village, q.deposit());
        TownBudget.in(village, "fees", q.deposit() * 100L);
        int seq = Integer.parseInt(Ledger.note(village, NOTE + "seq") == null ? "0" : Ledger.note(village, NOTE + "seq")) + 1;
        Ledger.note(village, NOTE + "seq", Integer.toString(seq));
        String id = "b" + seq;
        CompoundTag o = new CompoundTag();
        o.putString("id", id);
        o.putUUID("owner", p.getUUID());
        o.putString("ownerName", p.getGameProfile().getName());
        o.putString("blueprint", q.blueprint());
        o.putString("style", q.style());
        o.putLong("plot", q.plot().asLong());
        o.putInt("facing", q.facing().get2DDataValue());
        o.putString("purpose", purpose);
        if (firm != null) o.putString("firm", firm);
        o.putInt("laid", 0);
        o.putInt("of", BuildGoal.plan(q.blueprint(), q.plot(), q.facing(), 8).size());
        o.putInt("paid", q.deposit());
        o.putLong("day", level.getDayTime() / 24000L);
        Ledger.note(village, NOTE + id, o.toString());
        Design d = design(q.blueprint());
        Villages.tell(village, level.getDayTime() / 24000L, p.getGameProfile().getName() + " commissioned " + (d == null ? "a building" : d.title().toLowerCase(Locale.ROOT))
            + " of their own: " + PlayerLife.coins(q.deposit()) + " all told");
        return id;
    }

    static List<CompoundTag> orders(UUID village) {
        List<CompoundTag> out = new ArrayList<>();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith(NOTE) || e.getKey().equals(NOTE + "seq")) continue;
            try {
                CompoundTag t = TagParser.parseTag(e.getValue());
                if (!t.getBoolean("done")) out.add(t);
            } catch (Exception ex) {
                // a note that could not be read is left alone
            }
        }
        out.sort(Comparator.comparing(t -> t.getString("id")));
        return out;
    }

    /** The player's orders in this town, done and not. */
    public static List<Status> mine(UUID village, UUID player) {
        List<Status> out = new ArrayList<>();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith(NOTE) || e.getKey().equals(NOTE + "seq")) continue;
            try {
                CompoundTag t = TagParser.parseTag(e.getValue());
                if (t.getUUID("owner").equals(player) && (!t.getBoolean("done") || t.getLong("doneDay") + 2 >= dayNow())) out.add(status(t));
            } catch (Exception ex) {
                // skip
            }
        }
        return out;
    }

    private static long dayNow() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0 : server.overworld().getDayTime() / 24000L;
    }

    @Nullable
    public static Status status(UUID village, String id) {
        String n = Ledger.note(village, NOTE + id);
        if (n == null) return null;
        try {
            return status(TagParser.parseTag(n));
        } catch (Exception e) {
            return null;
        }
    }

    static Status status(CompoundTag t) {
        Design d = design(t.getString("blueprint"));
        int of = Math.max(1, t.getInt("of"));
        int pct = Math.min(100, t.getInt("laid") * 100 / of);
        int left = Math.max(0, of - t.getInt("laid"));
        int eta = left == 0 ? 0 : 1;
        return new Status(t.getString("id"), d == null ? "Your building" : "Your " + d.title().substring(2).toLowerCase(Locale.ROOT), pct,
            t.getBoolean("done") ? "done" : t.getInt("laid") == 0 ? "waiting to start" : "going up", t.getString("waiting"), eta, eta + 1, t.getBoolean("done"));
    }

    /** Cancel it: what was not yet laid refunded (its share of the price), the rest stands as far as it got. */
    public static String cancel(ServerLevel level, ServerPlayer p, UUID village, String id) {
        String n = Ledger.note(village, NOTE + id);
        if (n == null) return "!No such order.";
        CompoundTag t;
        try { t = TagParser.parseTag(n); } catch (Exception e) { return "!No such order."; }
        if (!t.getUUID("owner").equals(p.getUUID()) || t.getBoolean("done")) return "!That isn't yours to cancel.";
        int refund = (int) Math.floor(t.getInt("paid") * (1.0 - t.getInt("laid") / (double) Math.max(1, t.getInt("of"))) * 0.9);
        refund = Math.min(refund, Ledger.coins(village));
        if (refund > 0) {
            Ledger.takeCoins(village, refund);
            Dealings.giveCoins(p, refund);
            CoinCensus.playerOut(village, refund);
        }
        Ledger.forget(village, NOTE + id);
        return "Cancelled: " + PlayerLife.coins(refund) + " back (what was not yet built, less a tenth for the trouble).";
    }

    // ------------------------------------------------------------------ the plain engine

    /** Every couple of seconds for a town: the orders' next blocks laid. */
    public static void tick(ServerLevel level, Villages.Village v) {
        for (CompoundTag t : orders(v.id())) step(level, v, t);
    }

    static void step(ServerLevel level, Villages.Village v, CompoundTag t) {
        String bp = t.getString("blueprint");
        BlockPos plot = BlockPos.of(t.getLong("plot"));
        Direction facing = Direction.from2DDataValue(t.getInt("facing"));
        if (!level.isLoaded(plot)) return;
        Style style = Style.of(t.getString("style"));
        Function<BuildGoal.Placement, BlockState> paint = Showcase.painter(style.palette);
        List<BuildGoal.Placement> plan = BuildGoal.plan(bp, plot, facing, 8);
        plan.sort(Comparator.comparingInt((BuildGoal.Placement p) -> p.pos().getY()));
        int laid = t.getInt("laid");
        if (laid == 0) BuildGoal.clearWildPlants(level, bp, plot, facing, 8);
        Set<BuildGoal.Placement> now = new HashSet<>();
        String waiting = "";
        long tick = level.getGameTime();
        for (int i = laid; i < plan.size() && now.size() < PER_STEP; i++) {
            BuildGoal.Placement p = plan.get(i);
            BlockState st = paint.apply(p);
            if (st != null && !st.isAir() && st.getBlock().asItem() != net.minecraft.world.item.Items.AIR) {
                Block b = st.getBlock();
                boolean had = Crafts.take(level, v, s -> s.is(b.asItem()), 1);
                if (!had) {
                    long since = t.getLong("waitSince");
                    if (since == 0) { t.putLong("waitSince", tick); since = tick; }
                    if (tick - since < WAIT) {
                        int need = 0;
                        for (int k = i; k < plan.size(); k++) { BlockState s2 = paint.apply(plan.get(k)); if (s2 != null && s2.getBlock() == b) need++; }
                        waiting = need + " " + new ItemStack(b.asItem()).getHoverName().getString().toLowerCase(Locale.ROOT);
                        break;
                    }
                }
                t.putLong("waitSince", 0);
            }
            now.add(p);
        }
        if (!now.isEmpty()) BuildGoal.stampOnly(level, bp, plot, facing, 8, paint, now::contains);
        int was = t.getInt("laid") * 100 / Math.max(1, t.getInt("of"));
        t.putInt("laid", laid + now.size());
        t.putString("waiting", waiting);
        int pct = t.getInt("laid") * 100 / Math.max(1, t.getInt("of"));
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(t.getUUID("owner"));
        Status s = status(t);
        if (owner != null && pct / 25 > was / 25 && pct < 100) PlayerShops.notify(owner, s.words());
        Ledger.note(v.id(), NOTE + t.getString("id"), t.toString());
        if (t.getInt("laid") >= plan.size()) {
            t.putBoolean("done", true);
            Ledger.note(v.id(), NOTE + t.getString("id"), t.toString());
            finish(level, v, t, plan, facing, owner);
            Ledger.note(v.id(), NOTE + t.getString("id"), t.toString());
        }
    }

    /** It stands: a shop or premises gets its counter and a chest, and is the player's shop; the chronicle has it. */
    static void finish(ServerLevel level, Villages.Village v, CompoundTag t, List<BuildGoal.Placement> plan, Direction facing, @Nullable ServerPlayer owner) {
        t.putBoolean("done", true);
        t.putLong("doneDay", level.getDayTime() / 24000L);
        Design d = design(t.getString("blueprint"));
        boolean home = d != null && HOMES.contains(d);
        String title = d == null ? "a building" : d.title().toLowerCase(Locale.ROOT);
        Villages.tell(v.id(), level.getDayTime() / 24000L, t.getString("ownerName") + "'s " + title.substring(2) + " was finished, every block of it paid for");
        if (owner != null) {
            PlayerShops.notify(owner, "Your " + title.substring(2) + " is finished!");
            owner.sendSystemMessage(Component.literal("Your " + title.substring(2) + " is finished at " + BlockPos.of(t.getLong("plot")).toShortString() + ".").withStyle(ChatFormatting.GREEN));
        }
        if (home) return;
        // The counter: in place of its first cask or barrel, else just inside the door, facing the way in.
        BlockPos counter = null, chest = null;
        for (BuildGoal.Placement p : plan) {
            if (counter == null && p.part() == BuildGoal.Part.BARREL) counter = p.pos();
            else if (chest == null && p.part() == BuildGoal.Part.CHEST) chest = p.pos();
        }
        BlockPos door = null;
        for (BuildGoal.Placement p : plan) if (p.part() == BuildGoal.Part.DOOR) { door = p.pos(); break; }
        if (counter == null && door != null) counter = door.relative(facing.getOpposite(), 2);
        if (counter != null) {
            level.setBlockAndUpdate(counter, McAssistantMod.SHOP_COUNTER.get().defaultBlockState()
                .setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, facing));
        }
        if (chest == null && counter != null) {
            chest = counter.relative(facing.getOpposite()).relative(facing.getClockWise());
            if (level.getBlockState(chest).isAir()) level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing));
        }
        if (counter != null && owner != null && PlayerShops.of(v.id(), owner.getUUID()) == null) PlayerShops.placed(level, counter, owner, true);
        String firm = t.getString("firm");
        if (!firm.isEmpty() && counter != null) {
            Business b = Businesses.of(v.id(), firm);
            if (b != null) {
                b.at = counter;
                Businesses.dirty();
            }
            PlayerShops.Shop s = owner == null ? null : PlayerShops.of(v.id(), owner.getUUID());
            if (s != null) { s.firm = firm; PlayerShops.save(s); }
        }
    }

    // ------------------------------------------------------------------ the picture

    /** The drawing's blocks for the picture, in this style: x, y, z and the block's state id, four ints each. */
    public static int[] picture(String blueprint, Style style) {
        Function<BuildGoal.Placement, BlockState> paint = Showcase.painter(style.palette);
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

    /** Tests: the plain engine run till the order stands (or so many steps). */
    public static void buildForTests(ServerLevel level, Villages.Village v, String id, int steps) {
        for (int i = 0; i < steps; i++) {
            String n = Ledger.note(v.id(), NOTE + id);
            if (n == null) return;
            try {
                CompoundTag t = TagParser.parseTag(n);
                if (t.getBoolean("done")) return;
                t.putLong("waitSince", level.getGameTime() - WAIT - 1);
                step(level, v, t);
            } catch (Exception e) {
                return;
            }
        }
    }

    static int heightAt(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
