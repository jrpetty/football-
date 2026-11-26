package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * [wpc] The builder (page "build"): a building of your own, from the ground up, seen before a coin is paid.
 * <ol>
 * <li><b>The plot.</b> The town's plan as a map of its real lots (TownPlan), each coloured by what it is: free, the town's,
 *     its fields, yours (LandPlots), another player's, kept for the town. Click a free lot or one of your own; its outline
 *     goes up on the ground at once.</li>
 * <li><b>The design.</b> A gallery of what fits that ground: homes (a cottage, a family house, a villa, a manor) or premises
 *     (a shop, a store, a bakery, a café...), each with its picture (dragged to turn), its size, what is inside and the
 *     builders' quote.</li>
 * <li><b>See it standing.</b> Every block of the drawing drawn translucent where it would stand (client/BuildGhost), red
 *     where something is in its way: turned, nudged a block at a time within the lot, and walked round with the screen
 *     closed (U comes back).</li>
 * <li><b>Cost and time.</b> The builders' quote line by line (BuildOrders: materials, labour, the land, fees, margin; a
 *     deposit, a stage payment, the balance) and how long, then a question before any money moves.</li>
 * <li><b>Progress.</b> Each order's bar, what it waits on, and the blocks still to go up shown in place.</li>
 * </ol>
 * Starting a business (StartBusiness) and the property office's Commission tab open it; from a business it hands the plot
 * and the drawing back to the charter's own confirm step.
 */
public final class BuildPicker implements PlayerLife.Page {

    /** Drawings offered as homes, and as premises (what the builders take orders for). */
    static final List<String> HOMES = List.of("house", "house2", "villa", "manor");
    static final List<String> PREMISES = List.of("shop", "store", "bakery", "cafe", "tavern", "restaurant", "smithy", "workshop", "storage");
    /** How far a building may be nudged within its lot, blocks each way. */
    static final int NUDGE = 3;

    /** Ground the builder can put a building on: a free plan lot, or a title of the player's. Box in world blocks. */
    record Site(String id, int[] box, Direction facing, String label, boolean own) {
        BlockPos centre(ServerLevel level) {
            int x = (box[0] + box[2]) / 2, z = (box[1] + box[3]) / 2;
            int y = level.isLoaded(new BlockPos(x, 64, z)) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : 64;
            return new BlockPos(x, y, z);
        }
    }

    // ------------------------------------------------------------------ the sites

    /** The site the page has chosen, or null. "L:x:z" a plan lot by its offset; anything else a land title's id. */
    @Nullable
    static Site site(UUID village, String id, UUID me) {
        Villages.Village v = Villages.get(village);
        if (v == null || id.isEmpty()) return null;
        BlockPos heart = v.centre();
        if (id.startsWith("L:")) {
            String[] p = id.split(":");
            try {
                TownPlan.Lot l = LandPlots.lotAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                if (l == null) return null;
                int[] b = TownPlan.box(l);
                return new Site(id, new int[]{ heart.getX() + b[0], heart.getZ() + b[1], heart.getX() + b[2], heart.getZ() + b[3] }, LandPlots.door(l.back()),
                    "a free lot " + PropertyMarket.where(village, heart.offset(l.x(), 0, l.z())), false);
            } catch (RuntimeException e) {
                return null;
            }
        }
        LandPlots.Title t = LandPlots.get(village, id);
        if (t == null || !t.owner().equals(me)) return null;
        return new Site(id, t.box(), t.facing(), "your " + t.name().toLowerCase(Locale.ROOT), true);
    }

    /** Why a plan lot is no good for a player's building ("" if it may have one): as the land office says, but the growth ring is fine (you buy it with the house). */
    static String lotProblem(UUID village, BlockPos heart, TownPlan.Lot l, boolean premises) {
        if (!CityGrowth.laidOut(village, l)) return "the town hasn't laid that ring out yet";
        boolean ok = l.use().equals("home") || premises && l.use().equals("civic");
        if (!ok) return "kept for the town's " + (l.use().equals("civic") ? "trades on the square" : l.use().startsWith("great") ? "great buildings" : "watchtowers");
        if (LandPlots.at(village, heart.getX() + l.x(), heart.getZ() + l.z()) != null) return "somebody holds it";
        if (Villages.lotTaken(village, l)) return "the town has built on it, or is building on it";
        if (Villages.lotKeptOff(village, heart, l)) return "it's the town's fields, pens or the mine's ground";
        return "";
    }

    /** The drawings for this purpose that fit the site (within its lot, nudges and all). */
    static List<String> fitting(UUID village, Site s, boolean premises) {
        List<String> out = new ArrayList<>();
        List<String> can = BuildOrders.drawings(village);
        for (String d : premises ? PREMISES : HOMES) if (can.contains(d) && fits(d, s, s.facing(), 0, 0)) out.add(d);
        return out;
    }

    /** Does the drawing, turned and nudged so, stand within the site's ground? */
    static boolean fits(String drawing, Site s, Direction facing, int nx, int nz) {
        int[] b = s.box();
        BlockPos at = new BlockPos((b[0] + b[2]) / 2 + nx, 0, (b[1] + b[3]) / 2 + nz);
        int[] box = BuildGoal.box(drawing, at, facing, false);
        return box[0] >= b[0] && box[1] <= b[2] && box[2] >= b[1] && box[3] <= b[3];
    }

    // ------------------------------------------------------------------ the picture in the world

    /**
     * The drawing's blocks at this spot, as the builders would paint them: x, y, z, the block's state id and whether something
     * already stands there in its way (1), five ints each. With {@code rest}, only those not yet built (the progress step).
     */
    static int[] cells(ServerLevel level, String drawing, String style, BlockPos anchor, Direction facing, boolean rest) {
        Function<BuildGoal.Placement, BlockState> paint = Showcase.painter(BuildOrders.palette(style));
        List<Integer> out = new ArrayList<>();
        int most = 9000;
        for (BuildGoal.Placement p : BuildGoal.plan(drawing, anchor, facing, 8)) {
            BlockState st = paint.apply(p);
            if (st == null || st.isAir() || st.is(Blocks.WATER)) continue;
            st = BuildDesk.orient(st, p, facing);
            BlockPos at = p.pos();
            BlockState there = level.isLoaded(at) ? level.getBlockState(at) : Blocks.AIR.defaultBlockState();
            if (rest && !there.isAir() && !there.canBeReplaced()) continue;   // built already
            boolean bad = !rest && at.getY() >= anchor.getY() && !there.isAir() && !there.canBeReplaced() && !there.is(BlockTags.LEAVES)
                && !there.is(BlockTags.FLOWERS) && !there.is(BlockTags.REPLACEABLE_BY_TREES);
            out.add(at.getX());
            out.add(at.getY());
            out.add(at.getZ());
            out.add(Block.getId(st));
            out.add(bad ? 1 : 0);
            if (out.size() >= most * 5) break;
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static int clashes(int[] cells) {
        int n = 0;
        for (int i = 4; i < cells.length; i += 5) n += cells[i];
        return n;
    }

    // ------------------------------------------------------------------ the page

    static boolean premises(PlayerLife.Ctx c) {
        String f = c.args.getString("for");
        return f.equals("premises") || f.equals("start");
    }

    static String style(UUID village, PlayerLife.Ctx c) {
        List<String> styles = BuildDesk.styles(village);
        return styles.contains(c.args.getString("style")) ? c.args.getString("style") : styles.get(0);
    }

    static Direction facing(Site s, PlayerLife.Ctx c) {
        return Direction.from2DDataValue(Math.floorMod(s.facing().get2DDataValue() + c.args.getInt("turn"), 4));
    }

    static BlockPos anchor(ServerLevel level, Site s, PlayerLife.Ctx c) {
        BlockPos m = s.centre(level);
        int x = m.getX() + c.args.getInt("nx"), z = m.getZ() + c.args.getInt("nz");
        int y = level.isLoaded(new BlockPos(x, 64, z)) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : m.getY();
        return new BlockPos(x, y, z);
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) {
            LifePage page = new LifePage("build", "The builder", "No town near", p.registryAccess());
            page.text("The town's builders build in town. Walk into one and open this again.", 4);
            page.blockGhost(null);
            return page;
        }
        UUID id = v.id(), me = p.getUUID();
        boolean prem = premises(c);
        if (!c.tab.isEmpty()) {                                             // a tab is a step
            try { c.args.putInt("step", Integer.parseInt(c.tab)); } catch (NumberFormatException e) { /* left as it was */ }
            c.tab = "";
        }
        List<BuildDesk.Status> orders = BuildDesk.mine(level, id, me);
        int step = Math.max(1, Math.min(5, c.args.getInt("step") == 0 ? (orders.isEmpty() || c.args.contains("site") ? 1 : 5) : c.args.getInt("step")));
        c.args.putInt("step", step);
        Site s = site(id, c.args.getString("site"), me);
        String bp = c.args.getString("bp");
        LifePage page = new LifePage("build", prem ? "The builder: premises" : "The builder: a home of your own",
            step == 5 ? "Your buildings going up" : "Step " + step + " of 4: " + new String[]{ "", "the plot", "the design", "see it standing", "cost and time" }[step], p.registryAccess());
        if (!c.args.getString("back").isEmpty()) {                          // the screen's Back: where it came from, as it was
            page.done().putString("back", c.args.getString("back"));
            if (c.args.contains("ret")) page.done().put("backArgs", c.args.getCompound("ret").copy());
        }
        page.tab("1", "1 Plot", step == 1).tab("2", "2 Design", step == 2).tab("3", "3 See it", step == 3).tab("4", "4 Cost", step == 4);
        if (!orders.isEmpty()) page.tab("5", "Progress", step == 5);
        PropertyBook.confirmBox(page, c);
        switch (step) {
            case 2 -> design(level, p, v, c, page, s, prem);
            case 3 -> see(level, p, v, c, page, s, bp);
            case 4 -> cost(level, p, v, c, page, s, bp);
            case 5 -> progress(level, p, v, c, page, orders);
            default -> plot(level, p, v, c, page, s, prem);
        }
        return page;
    }

    void plot(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, @Nullable Site s, boolean prem) {
        UUID id = v.id(), me = p.getUUID();
        BlockPos heart = v.centre();
        PropertyBook.hint(p, page, "build.plot", "Choose where it goes: a free lot of the town's plan (green), or land of your own (blue). "
            + "The lot's outline appears on the ground the moment you click it, with posts at its corners.");
        LifePage.LotMap map = page.lotMap("The town's plan: click a green or blue lot", heart.getX(), heart.getZ());
        map.mark(heart.getX(), heart.getZ(), 1, "The square");
        map.mark(p.getBlockX(), p.getBlockZ(), 2, "You");
        int rings = Math.min(CityGrowth.rings(id), 5);
        for (TownPlan.Lot l : LandPlots.lots().values()) {
            if (TownPlan.ringOf(l) >= rings) continue;
            LandPlots.Title t = LandPlots.at(id, heart.getX() + l.x(), heart.getZ() + l.z());
            if (t != null && t.owner().equals(me)) continue;                // drawn whole below
            int[] b = TownPlan.box(l);
            String why = lotProblem(id, heart, l, prem);
            int st = t != null ? 5 : why.isEmpty() ? 0 : Villages.lotKeptOff(id, heart, l) ? 3 : Villages.lotTaken(id, l) ? 2 : 6;
            String sid = "L:" + l.x() + ":" + l.z();
            boolean click = why.isEmpty();
            map.lot(heart.getX() + b[0], heart.getZ() + b[1], heart.getX() + b[2], heart.getZ() + b[3], st, s != null && s.id().equals(sid),
                (click ? "Free lot " : "Lot ") + PropertyMarket.where(id, heart.offset(l.x(), 0, l.z())) + (why.isEmpty() ? ": click to choose" : ": " + why),
                click ? "site" : null, click ? LifePage.args("site", sid) : null);
        }
        for (LandPlots.Title t : LandPlots.of(id, me)) {
            if (t.kind() == LandPlots.Kind.FIELD) continue;
            int[] b = t.box();
            map.lot(b[0], b[1], b[2], b[3], 4, s != null && s.id().equals(t.id()), "Your land: " + t.name() + (t.built() ? " (built on)" : ": click to build here"),
                t.built() ? null : "site", t.built() ? null : LifePage.args("site", t.id()));
        }
        if (s == null) {
            page.text("Click a lot on the map to choose it.", 4);
            page.blockGhost(null);
            page.buttons().button("Buy a bigger plot first", "land", null);
            return;
        }
        List<String> fit = fitting(id, s, prem);
        int w = s.box()[2] - s.box()[0] + 1, d = s.box()[3] - s.box()[1] + 1;
        page.row(new ItemStack(s.own() ? Items.FILLED_MAP : Items.OAK_FENCE), "Chosen: " + s.label(), w + " by " + d + " blocks, its front to the "
            + s.facing().getName() + (s.own() ? "; your own land, so no land to buy" : "; the builders' quote includes the land") + ". Its outline is on the ground.", "")
            .button("Show me", "show", null);
        BlockPos m = s.centre(level);
        CompoundTag g = new CompoundTag();
        g.putIntArray("lot", new int[]{ s.box()[0], s.box()[1], s.box()[2], s.box()[3], m.getY() });
        g.putBoolean("ok", !fit.isEmpty());
        g.putIntArray("cells", new int[0]);
        page.blockGhost(g);
        if (fit.isEmpty()) page.text("✘ None of the " + (prem ? "premises" : "houses") + " fit this lot. A bigger plot (two lots, a long lot, a block) takes the bigger ones: buy one on the Land page.", 3);
        else page.text("✔ " + fit.size() + (fit.size() == 1 ? " design fits" : " designs fit") + " here: " + names(fit) + ".", 1);
        page.buttons().button("Next: choose a design", "step", LifePage.args("s", 2), !fit.isEmpty(), "Nothing fits this lot: choose another, or buy a bigger plot").primary()
            .button("Buy a bigger plot", "land", null);
    }

    static String names(List<String> drawings) {
        List<String> n = new ArrayList<>();
        for (String d : drawings) n.add(BuildOrders.title(d).replaceFirst("^an? ", ""));
        return String.join(", ", n);
    }

    void design(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, @Nullable Site s, boolean prem) {
        UUID id = v.id();
        if (s == null) { page.text("Choose a plot first.", 3); page.buttons().button("Back to the plot", "step", LifePage.args("s", 1)).primary(); return; }
        PropertyBook.hint(p, page, "build.design", "Only what fits your plot is shown. Drag a picture to turn it. Choose one, and the next step shows it standing on the ground.");
        String style = style(id, c);
        List<String> styles = BuildDesk.styles(id);
        String[] sv = styles.toArray(new String[0]), sl = new String[sv.length];
        for (int i = 0; i < sv.length; i++) sl[i] = BuildDesk.styleLabel(sv[i]);
        page.choice("style", "Built in", sv, sl, style);
        page.buttons().button("Show them in this style", "style", null);
        String bp = c.args.getString("bp");
        BlockPos at = anchor(level, s, c);
        for (String d : fitting(id, s, prem)) {
            int[] size = BuildDesk.size(d);
            BuildDesk.Design info = BuildDesk.design(d);
            BuildOrders.Quote q = BuildDesk.quote(level, id, d, style, at, facing(s, c), p.getUUID());
            page.picture(d, BuildDesk.picture(d, style));
            boolean on = d.equals(bp);
            String title = info != null ? info.title() : capital(BuildOrders.title(d));
            page.row(null, (on ? "✔ " : "") + title + " — " + size[0] + " by " + size[1] + ", " + size[2] + (size[2] == 1 ? " storey" : " storeys"),
                    (info != null ? "For " + info.suits() + ". " : "") + "Inside: " + BuildDesk.furnishing(d) + ". Ready in " + (q.eta == null ? "a while" : q.eta.words()) + ".",
                    PropertyBook.coins(q.total))
                .button(on ? "Chosen" : "Choose", "bp", LifePage.args("bp", d), !on, "Chosen");
        }
        page.buttons().button("Back", "step", LifePage.args("s", 1))
            .button("Next: see it standing", "step", LifePage.args("s", 3), !bp.isEmpty() && fits(bp, s, facing(s, c), c.args.getInt("nx"), c.args.getInt("nz")), "Choose a design first").primary();
        page.blockGhost(null);
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    void see(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, @Nullable Site s, String bp) {
        UUID id = v.id();
        if (s == null || bp.isEmpty()) { page.text("Choose a plot and a design first.", 3); page.buttons().button("Back", "step", LifePage.args("s", s == null ? 1 : 2)).primary(); return; }
        PropertyBook.hint(p, page, "build.see", "This is the building itself, every block where it would stand. Red blocks are where something is in the way. "
            + "Turn it, nudge it, or close the screen and walk round it (U brings you back).");
        String style = style(id, c);
        Direction f = facing(s, c);
        BlockPos at = anchor(level, s, c);
        boolean fit = fits(bp, s, f, c.args.getInt("nx"), c.args.getInt("nz"));
        String why = BuildPlots.whyNot(level, v, bp, at, f);
        int[] cells = cells(level, bp, style, at, f, false);
        int clash = clashes(cells);
        CompoundTag g = new CompoundTag();
        g.putIntArray("cells", cells);
        BlockPos m = s.centre(level);
        g.putIntArray("lot", new int[]{ s.box()[0], s.box()[1], s.box()[2], s.box()[3], m.getY() });
        boolean ok = fit && why.isEmpty() && clash == 0;
        g.putBoolean("ok", ok);
        if (c.args.getBoolean("walk")) {
            g.putBoolean("walk", true);
            g.putString("say", "Walking round your " + BuildOrders.title(bp).replaceFirst("^an? ", "") + (ok ? "" : " (red: in the way)") + ". Press U to go back to the builder.");
        }
        page.blockGhost(g);
        page.picture(bp, BuildDesk.picture(bp, style));
        page.row(new ItemStack(Items.SPYGLASS), capital(BuildOrders.title(bp)) + " in " + BuildDesk.styleLabel(style).toLowerCase(Locale.ROOT) + ", on " + s.label(),
            "Front facing " + f.getName() + (c.args.getInt("nx") != 0 || c.args.getInt("nz") != 0 ? ", nudged " + nudgeWords(c) : "") + ". " + cells.length / 5 + " blocks.", "");
        if (!fit) page.text("✘ It runs off the lot like that: nudge it back or turn it.", 3);
        else if (!why.isEmpty()) page.text("✘ The builders won't build it there: " + why + ".", 3);
        else if (clash > 0) page.text("✘ " + clash + (clash == 1 ? " block is" : " blocks are") + " in the way (shown red): a tree, a wall, a path. Nudge it, or clear them first.", 3);
        else page.text("✔ It fits, and nothing is in its way. Walk round it if you like, then see the cost.", 1);
        page.buttons().button("Turn it", "turn", null).button("← West", "nudge", LifePage.args("dx", -1, "dz", 0)).button("East →", "nudge", LifePage.args("dx", 1, "dz", 0))
            .button("↑ North", "nudge", LifePage.args("dx", 0, "dz", -1)).button("South ↓", "nudge", LifePage.args("dx", 0, "dz", 1));
        page.buttons().button("Walk round it", "walk", null).button("Back", "step", LifePage.args("s", 2))
            .button("Next: cost and time", "step", LifePage.args("s", 4), ok, !fit ? "It runs off the lot" : !why.isEmpty() ? "The builders won't build there: " + why : "Something is in the way (red)").primary();
    }

    static String nudgeWords(PlayerLife.Ctx c) {
        int nx = c.args.getInt("nx"), nz = c.args.getInt("nz");
        List<String> w = new ArrayList<>();
        if (nx != 0) w.add(Math.abs(nx) + (nx > 0 ? " east" : " west"));
        if (nz != 0) w.add(Math.abs(nz) + (nz > 0 ? " south" : " north"));
        return String.join(" and ", w);
    }

    @Nullable
    static BuildOrders.Quote quote(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, @Nullable Site s, String bp) {
        if (s == null || bp.isEmpty()) return null;
        return BuildDesk.quote(level, v.id(), bp, style(v.id(), c), anchor(level, s, c), facing(s, c), p.getUUID());
    }

    void cost(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, @Nullable Site s, String bp) {
        UUID id = v.id();
        BuildOrders.Quote q = quote(level, p, v, c, s, bp);
        if (q == null) { page.text("Choose a plot and a design first.", 3); page.buttons().button("Back", "step", LifePage.args("s", 1)).primary(); return; }
        PropertyBook.hint(p, page, "build.cost", "The builders' own quote: what the materials cost at the market today, each trade's hours at its wage, the land, the fees. "
            + "You pay a deposit now, more at half built, and the balance when it stands.");
        page.picture(bp, BuildDesk.picture(bp, q.style));
        page.heading(capital(BuildOrders.title(bp)) + " on " + s.label());
        page.row(null, "Materials", "At the market's asking price today", String.format(Locale.ROOT, "%.0fc", q.materialsCost));
        page.row(null, "Labour", String.format(Locale.ROOT, "%.0f hours of the builders' trades at their wages", q.labourHours), String.format(Locale.ROOT, "%.0fc", q.labourCost));
        page.row(null, "Overheads and the builders' margin", "Their tools, carting and keep", String.format(Locale.ROOT, "%.0fc", q.overheads + q.margin));
        page.row(null, "The land", q.landTerms, String.format(Locale.ROOT, "%.0fc", q.landCost));
        page.row(null, "Fees", "The permit, rates and the registry", String.format(Locale.ROOT, "%.0fc", q.fees));
        page.row(null, "All told", q.eta == null ? "" : "Ready in " + q.eta.words() + ": " + q.eta.reason(), PropertyBook.coins(q.total));
        page.text(q.deposit + "c down now, " + q.stage + "c at half built, " + q.balance + "c when it stands.", 0);
        int have = Market.coinsHeld(p);
        boolean start = c.args.getString("for").equals("start");
        if (!q.ok) page.text("✘ The builders won't build there: " + q.why + ". Go back and choose another plot.", 3);
        if (start) {
            page.text("This goes back to your business's charter: it is ordered when the charter is granted, its deposit with the charter fee.", 4);
            page.buttons().button("Back", "step", LifePage.args("s", 3)).button("Use this for my business", "use", null, q.ok, "The builders won't build there").primary();
        } else {
            String why = !q.ok ? "The builders won't build there: " + q.why : have < q.deposit ? "You carry " + have + " of the " + q.deposit + "c deposit" : "";
            page.buttons().button("Back", "step", LifePage.args("s", 3)).button("Order it (" + q.deposit + "c down)", "order", null, why.isEmpty(), why).primary();
        }
        page.blockGhost(null);
    }

    void progress(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, List<BuildDesk.Status> orders) {
        UUID id = v.id();
        PropertyBook.hint(p, page, "build.progress", "The builders work their hours and say what they wait on. The blocks still to go up show where they'll be, so you can watch it rise.");
        if (orders.isEmpty()) {
            page.text("Nothing going up for you just now.", 4);
            page.buttons().button("Build something", "step", LifePage.args("s", 1)).primary();
            page.blockGhost(null);
            return;
        }
        String shown = c.args.getString("order");
        if (shown.isEmpty()) shown = orders.get(0).id();
        for (BuildDesk.Status s : orders) {
            page.bar(s.title(), s.percent() / 100.0, s.done() ? "finished" : s.percent() + "%");
            page.row(null, s.done() ? "It stands. The keys are yours." : s.stage().isEmpty() ? "Under way" : capital(s.stage()),
                    (s.waitingOn().isEmpty() ? "" : "Waiting on " + s.waitingOn() + ". ") + (s.eta().isEmpty() ? "" : "Done in " + s.eta() + ".") + (s.due() > 0 ? " " + s.due() + "c due now." : ""), "")
                .button(s.id().equals(shown) ? "Shown" : "Show what's left", "showorder", LifePage.args("order", s.id()), !s.id().equals(shown), "Shown on the ground now")
                .button("Show me", "goto", LifePage.args("order", s.id()))
                .button("The property office", "office", null);
        }
        CompoundTag o = BuildOrders.order(shown);
        if (o != null) {
            BlockPos anchor = BlockPos.of(o.getLong("anchor"));
            int[] cells = cells(level, o.getString("drawing"), o.getString("style"), anchor, Direction.from2DDataValue(o.getInt("facing")), true);
            CompoundTag g = new CompoundTag();
            g.putIntArray("cells", cells);
            g.putBoolean("rest", true);
            g.putBoolean("ok", true);
            page.blockGhost(g);
            page.text(cells.length / 5 + " blocks still to go up, shown faint where they'll be.", 4);
        }
        page.buttons().button("Build something else", "new", null);
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
        if (f.contains("style")) c.args.putString("style", f.getString("style"));
        c.args.remove("walk");
        Site s = site(id, c.args.getString("site"), p.getUUID());
        String bp = c.args.getString("bp");
        switch (action) {
            case "step" -> { c.args.putInt("step", args.getInt("s")); return ""; }
            case "site" -> {
                Site n = site(id, args.getString("site"), p.getUUID());
                if (n == null) return "!That lot can't be had.";
                c.args.putString("site", n.id());
                c.args.remove("nx");
                c.args.remove("nz");
                c.args.remove("turn");
                if (!bp.isEmpty() && !fitting(id, n, premises(c)).contains(bp)) c.args.remove("bp");
                return "Chosen: " + n.label() + ". Its outline is on the ground. Next: choose a design.";
            }
            case "style" -> { return ""; }
            case "bp" -> {
                if (s == null) return "!Choose a plot first.";
                if (!fitting(id, s, premises(c)).contains(args.getString("bp"))) return "!That doesn't fit this plot.";
                c.args.putString("bp", args.getString("bp"));
                c.args.remove("nx");
                c.args.remove("nz");
                return "Chosen: " + BuildOrders.title(args.getString("bp")) + ". Next: see it standing on the plot.";
            }
            case "turn" -> {
                if (s == null || bp.isEmpty()) return "!Choose a plot and a design first.";
                int t = Math.floorMod(c.args.getInt("turn") + 1, 4);
                Direction nf = Direction.from2DDataValue(Math.floorMod(s.facing().get2DDataValue() + t, 4));
                if (!fits(bp, s, nf, 0, 0)) {
                    t = Math.floorMod(t + 1, 4);                             // a long building on a long lot turns end for end
                    nf = Direction.from2DDataValue(Math.floorMod(s.facing().get2DDataValue() + t, 4));
                }
                c.args.putInt("turn", t);
                c.args.remove("nx");
                c.args.remove("nz");
                return "Turned: its front faces " + nf.getName() + " now.";
            }
            case "nudge" -> {
                if (s == null || bp.isEmpty()) return "!Choose a plot and a design first.";
                int nx = Math.max(-NUDGE, Math.min(NUDGE, c.args.getInt("nx") + args.getInt("dx")));
                int nz = Math.max(-NUDGE, Math.min(NUDGE, c.args.getInt("nz") + args.getInt("dz")));
                if (!fits(bp, s, facing(s, c), nx, nz)) return "!That would put it off the lot.";
                c.args.putInt("nx", nx);
                c.args.putInt("nz", nz);
                return "";
            }
            case "walk" -> {
                c.args.putBoolean("walk", true);
                PlayerLife.send(p, c);
                c.args.remove("walk");
                return "#close";
            }
            case "show" -> { return s == null ? "!Choose a plot first." : PropertyBook.showMe(p, s.centre(level), "The plot"); }
            case "land" -> { PlayerLife.open(p, "land", LifePage.args("tab", "buy")); return "#open:"; }
            case "use" -> {
                BuildOrders.Quote q = quote(level, p, v, c, s, bp);
                if (q == null || !q.ok) return "!The builders won't build there" + (q == null ? "" : ": " + q.why) + ".";
                CompoundTag back = c.args.getCompound("ret").copy();
                BlockPos at = anchor(level, s, c);
                back.putString("prem", "build");
                back.putString("bp", bp);
                back.putString("style", style(id, c));
                back.putInt("px", at.getX());
                back.putInt("py", at.getY());
                back.putInt("pz", at.getZ());
                back.putInt("face", facing(s, c).get2DDataValue());
                back.putInt("step", 3);
                back.putString("tab", "3");
                PlayerLife.open(p, "start", back);
                return "#open:";
            }
            case "order" -> {
                BuildOrders.Quote q = quote(level, p, v, c, s, bp);
                if (q == null) return "!Choose a plot and a design first.";
                if (!args.getBoolean("sure")) {
                    return PropertyBook.ask(c, "order", new CompoundTag(), "Order " + BuildOrders.title(bp) + " on " + s.label() + " for " + PropertyBook.coins(q.total)
                        + " all told? " + q.deposit + "c is paid now, " + q.stage + "c at half built and " + q.balance + "c when it stands. You can cancel it at the property office "
                        + "(the builders keep what they've earned).", "Order it (" + q.deposit + "c now)");
                }
                String r = BuildDesk.place(level, p, id, q, premises(c) ? "premises" : "home", c.args.getString("firm").isEmpty() ? null : c.args.getString("firm"));
                if (r.startsWith("!")) return r;
                LandPlots.markUsed(id, q.plot.getX(), q.plot.getZ(), false);
                c.args.putInt("step", 5);
                c.args.putString("order", r);
                Planner.add(p, (PropertyBook.day(level) + 1) * 24000L + 7000, "build", "The builders start on your " + BuildOrders.title(bp).replaceFirst("^an? ", ""), q.plot);
                return "Ordered: " + q.deposit + "c down. The builders start on it" + (q.eta == null ? "" : " and expect to finish in " + q.eta.words())
                    + ". You can watch it rise here: the blocks still to go up show faint on the plot.";
            }
            case "showorder" -> { c.args.putString("order", args.getString("order")); return ""; }
            case "goto" -> {
                CompoundTag o = BuildOrders.order(args.getString("order"));
                return o == null ? "!That order isn't on the books." : PropertyBook.showMe(p, BlockPos.of(o.getLong("anchor")), "Your building");
            }
            case "office" -> { PropertyOffice.openOn(p, "orders", null, "orders"); return "#open:"; }
            case "new" -> {
                c.args.remove("site");
                c.args.remove("bp");
                c.args.putInt("step", 1);
                return "";
            }
            default -> { return null; }
        }
    }

    /** From starting a business (StartBusiness): the builder for its premises, the charter's choices kept to come back to. */
    static void openForStart(ServerPlayer p, CompoundTag startArgs) {
        CompoundTag a = LifePage.args("for", "start", "back", "start");
        a.put("ret", startArgs.copy());
        PlayerLife.open(p, "build", a);
    }

    /** Tests: the site's id for a plan lot by its offset. */
    public static String lotSite(int ox, int oz) {
        return "L:" + ox + ":" + oz;
    }

    /** Tests: the cells the ghost would show (5 ints each), and how many clash. */
    public static int[] cellsForTests(ServerLevel level, String drawing, BlockPos anchor, Direction facing) {
        return cells(level, drawing, "oak", anchor, facing, false);
    }
}
