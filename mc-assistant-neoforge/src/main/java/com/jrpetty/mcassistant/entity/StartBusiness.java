package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.jrpetty.mcassistant.village.EarnMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [player15] Starting a business, three clicks from anywhere: the town hall's clerk or elder, the bank, the village
 * board, the Your life in town page, the city books' business list, or your own shop's counter ("make it a registered
 * business").
 * <ol>
 * <li><b>A kind.</b> Every kind the town can have, the best for you first: what the town is short of this week (the
 *     entrepreneurs' own reckoning, Enterprise.scan), what you are good at (your shifts' skills), what you sell already;
 *     each with a line of why and the takings to expect in a week. The best is chosen for you.</li>
 * <li><b>Premises.</b> Your own shop (its checklist shown), the town's own premises to let (a stall on the square, or a
 *     room), or a building the town puts up for you: a gallery of the town's drawings with a picture of each, its size,
 *     what is in it, the quote and how long; then its plot on a small map and outlined on the ground itself, nudged and
 *     turned till it suits. A sensible one is chosen for you.</li>
 * <li><b>Confirm.</b> The charter fee (and the building's price), what you have, and if you are short, the bank's loan
 *     for the rest in the same click.</li>
 * </ol>
 * Then a welcome: the firm's name, and its next steps (a hand, stock, prices, its books), each a click to the place it is
 * done. Nothing is typed; the charter is the same the town has always granted (Enterprise.found), the fee into the
 * treasury, only it is no longer the hall alone that grants it.
 */
public final class StartBusiness implements PlayerLife.Page {

    /** A kind as recommended to this player: the kind, its score for them, why, and the week's takings to expect. */
    record Pick(BusinessTypes.BusinessType type, double score, String why, int week, boolean allowed) {}

    static List<Pick> picks(ServerLevel level, Villages.Village v, ServerPlayer p) {
        List<BusinessTypes.BusinessType> allowed = BusinessTypes.allowed(v.id());
        List<Enterprise.Opportunity> opps = new ArrayList<>();
        try {
            opps = Enterprise.scan(level, v);
        } catch (RuntimeException e) {
            // a town with no market yet has no reckoning: every kind on its own merits
        }
        PlayerShops.Shop shop = PlayerShops.of(v.id(), p.getUUID());
        java.util.Set<String> sells = new java.util.HashSet<>();
        if (shop != null) sells.addAll(PlayerShops.wares(level, shop, new java.util.LinkedHashMap<>(), true).keySet().stream()
            .map(k -> k.endsWith("*") ? k.substring(0, k.length() - 1) : k).toList());
        List<Pick> out = new ArrayList<>();
        for (BusinessTypes.BusinessType t : BusinessTypes.ALL) {
            Enterprise.Opportunity o = null;
            for (Enterprise.Opportunity x : opps) if (x.type() == t) o = x;
            List<String> why = new ArrayList<>();
            double score = 0;
            int week = 0;
            if (o != null) {
                score += Math.max(-5, Math.min(10, o.score()));
                week = (int) Math.max(0, Math.round(o.profit7()));
                if (o.gap() > 0.5) why.add(String.format(Locale.ROOT, "the town wants %.0f %s a week and has %.0f", o.demand(),
                    Stockroom.plural(o.good()).toLowerCase(Locale.ROOT), o.supply()));
                else why.add("the town has enough " + Stockroom.plural(o.good()).toLowerCase(Locale.ROOT) + " for now");
            }
            ShiftRole role = PlayerJobs.roleFor(t.work());
            int lv = role == null ? 0 : EarnMath.level(Shifts.xp(p, role));
            if (lv > 0) { score += lv * 2; why.add("you're " + EarnMath.LEVELS[lv].toLowerCase(Locale.ROOT) + " at the work"); }
            boolean sellsIt = false;
            for (String g : t.wares()) if (sells.contains(g)) sellsIt = true;
            if (sellsIt) { score += 3; why.add("you sell its wares already"); }
            boolean ok = allowed.contains(t);
            if (!ok) why.add(0, Villages.ageOf(v.id()).ordinal() < t.from().ordinal() ? "not before the " + t.from().name().toLowerCase(Locale.ROOT) + " age"
                : "wants a town of " + t.minFolk());
            out.add(new Pick(t, ok ? score : score - 100, why.isEmpty() ? "a " + t.noun() + " of your own" : String.join("; ", why), week, ok));
        }
        out.sort(Comparator.comparingDouble(Pick::score).reversed());
        return out;
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        LifePage page = new LifePage("start", "Start a business", v == null ? "No town near" : "In " + Villages.name(v.id()) + ": three steps", p.registryAccess());
        if (v == null) {
            page.text("Businesses are chartered by a town: go to one.", 3);
            return page;
        }
        if (!c.tab.isEmpty()) {                                              // a tab clicked: that step
            c.args.putInt("step", ShopPage.parse(c.tab, 1));
            c.tab = "";
        }
        int step = Math.max(1, c.args.getInt("step"));
        if (c.args.getString("kind").isEmpty()) {
            List<Pick> ps = picks(level, v, p);
            if (!ps.isEmpty()) c.args.putString("kind", ps.get(0).type().key());
        }
        if (c.args.getString("prem").isEmpty()) {
            boolean shop = PlayerShops.of(v.id(), p.getUUID()) != null || c.args.getString("premises").equals("shop");
            c.args.putString("prem", shop ? "shop" : "town");
        }
        page.tab("1", "1. A kind", step == 1).tab("2", "2. Premises", step == 2).tab("3", "3. Confirm", step == 3);
        if (step != 2 || !c.args.getString("prem").equals("build")) page.ghost(null);
        if (step >= 4) welcome(level, p, v, c, page);
        else if (step == 3) confirm(level, p, v, c, page);
        else if (step == 2) premises(level, p, v, c, page);
        else kinds(level, p, v, c, page);
        return page;
    }

    private void kinds(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        page.heading("What kind of business? The best for you first");
        String chosen = c.args.getString("kind");
        BusinessTypes.BusinessType ct = BusinessTypes.of(chosen);
        page.buttons().button("Next: premises" + (ct == null ? "" : " for your " + ct.noun()), "step", LifePage.args("s", 2)).primary();
        for (Pick k : picks(level, v, p)) {
            boolean on = k.type().key().equals(chosen);
            page.row(icon(k.type()), (on ? "✔ " : "") + k.type().title() + (k.week() > 0 ? "" : ""), k.why(),
                    k.week() > 0 ? "~" + k.week() + "c a week" : "")
                .button(on ? "Chosen" : "Choose", "kind", LifePage.args("k", k.type().key()), k.allowed() && !on, k.allowed() ? "Chosen" : k.why());
        }
        page.buttons().button("Next: premises", "step", LifePage.args("s", 2)).primary();
    }

    static ItemStack icon(BusinessTypes.BusinessType t) {
        return switch (t.key()) {
            case "baker" -> new ItemStack(Items.BREAD);
            case "butcher" -> new ItemStack(Items.COOKED_BEEF);
            case "fishmonger" -> new ItemStack(Items.COD);
            case "toolsmith" -> new ItemStack(Items.IRON_PICKAXE);
            case "armourer" -> new ItemStack(Items.IRON_CHESTPLATE);
            case "carpenter" -> new ItemStack(Items.CHEST);
            case "clothier", "weaver" -> new ItemStack(Items.WHITE_WOOL);
            case "chandler" -> new ItemStack(Items.CANDLE);
            case "potter" -> new ItemStack(Items.FLOWER_POT);
            case "glassworks" -> new ItemStack(Items.GLASS);
            case "cafe" -> new ItemStack(Items.COOKIE);
            case "tavern", "inn" -> new ItemStack(Items.HONEY_BOTTLE);
            case "apothecary" -> new ItemStack(Items.POTION);
            case "greengrocer" -> new ItemStack(Items.CARROT);
            case "stationer" -> new ItemStack(Items.PAPER);
            default -> new ItemStack(Items.BARREL);
        };
    }

    private void premises(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        BusinessTypes.BusinessType t = BusinessTypes.of(c.args.getString("kind"));
        String prem = c.args.getString("prem");
        page.heading("Where will " + (t == null ? "it" : "your " + t.noun()) + " trade?");
        PlayerShops.Shop shop = PlayerShops.of(v.id(), p.getUUID());
        if (shop != null) {
            List<ShopNeeds.Need> needs = PlayerShops.needs(level, shop);
            boolean ready = ShopNeeds.ready(needs);
            page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), (prem.equals("shop") ? "✔ " : "") + "Your shop, " + shop.name,
                    ready ? "It has everything a shop needs. No rent: it is yours." : "Still " + ShopNeeds.missing(needs) + ": it trades once that is put right.", "free")
                .button(prem.equals("shop") ? "Chosen" : "Choose", "prem", LifePage.args("p", "shop"), !prem.equals("shop"), "Chosen");
            if (prem.equals("shop") && t != null) {
                ShopNeeds.Survey sv = PlayerShops.survey(level, shop);
                for (ShopNeeds.Need n : ShopNeeds.check(level, v, sv, ShopNeeds.Kind.forBusiness(t.key()), shop.back)) {
                    if (!n.ok()) page.text("  " + n.line() + " — " + n.fix(), 2);
                }
            }
        }
        long rent = t == null ? 0 : Math.round(t.stall() ? Enterprise.STALL_PENCE * MarketMaker.priceLevel(v.id()) / 100.0 : 0);
        page.row(new ItemStack(Items.OAK_FENCE), (prem.equals("town") ? "✔ " : "") + (t != null && t.stall() ? "A stall on the square" : "A room the town lets"),
                t != null && t.stall() ? "Bought of the town, its fixtures your firm's own" : "Its rent out of the firm's till each week", t != null && t.stall() ? PlayerLife.coins(rent) : "rent")
            .button(prem.equals("town") ? "Chosen" : "Choose", "prem", LifePage.args("p", "town"), !prem.equals("town"), "Chosen");
        page.row(new ItemStack(Items.BRICKS), (prem.equals("build") ? "✔ " : "") + "Have the town build it", "Choose from the town's drawings, with a picture of each", "")
            .button(prem.equals("build") ? "Chosen" : "Choose", "prem", LifePage.args("p", "build"), true, "");
        if (prem.equals("build")) gallery(level, p, v, c, page, t == null ? "shop" : BuildDesk.forBusiness(t.key()));
        page.buttons().button("Back", "step", LifePage.args("s", 1)).button("Next: confirm", "step", LifePage.args("s", 3),
            !prem.equals("build") || c.args.contains("px"), "Choose a building and its plot first").primary();
    }

    /** The gallery: each drawing with its picture, size, what is in it, its quote and how long; then its plot. */
    static void gallery(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, String suited) {
        String bp = c.args.getString("bp");
        List<String> styles = BuildDesk.styles(v.id());                      // [join21] the builders' styles, as the age allows
        String style = styles.contains(c.args.getString("style")) ? c.args.getString("style") : styles.get(0);
        String[] sv = styles.toArray(new String[0]), sl = new String[sv.length];
        for (int i = 0; i < sv.length; i++) sl[i] = BuildDesk.styleLabel(sv[i]);
        page.choice("style", "In", sv, sl, style);
        page.buttons().button("Show it in this style", "style", null);
        List<BuildDesk.Design> designs = new ArrayList<>(BuildDesk.PREMISES);
        designs.sort(Comparator.comparingInt(d -> d.blueprint().equals(suited) ? 0 : 1));
        List<String> drawings = BuildOrders.drawings(v.id());
        for (BuildDesk.Design d : designs) {
            if (!drawings.contains(d.blueprint())) continue;                 // [join21] what the builders will take an order for
            int[] size = BuildDesk.size(d.blueprint());
            BuildPlots.Plot plot = BuildPlots.first(level, v, d.blueprint());
            BuildOrders.Quote q = plot == null ? null : BuildDesk.quote(level, v.id(), d.blueprint(), style, plot.at(), plot.facing(), p.getUUID());
            page.picture(d.blueprint(), BuildDesk.picture(d.blueprint(), style));
            boolean on = d.blueprint().equals(bp);
            page.row(null, (on ? "✔ " : "") + d.title() + (d.blueprint().equals(suited) ? " (suits it best)" : "") + " — " + size[0] + " by " + size[1] + ", "
                    + size[2] + (size[2] == 1 ? " storey" : " storeys"), "For a " + d.suits() + ". Inside: " + BuildDesk.furnishing(d.blueprint()) + ".",
                    q == null ? "no plot" : PlayerLife.coins(q.total))
                .button(on ? "Chosen" : "Choose", "bp", LifePage.args("bp", d.blueprint()), q != null, "No plot in town fits it");
            if (q != null) for (String line : BuildDesk.quoteLines(q)) page.text(line, 4);   // [join21] the builders' quote and time
            if (on) place(level, p, v, c, page, d.blueprint(), style);
        }
    }

    /** The plot: a small map of the town's free plots, the chosen one outlined on the ground; nudged and turned. */
    static void place(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, String bp, String style) {
        List<BuildPlots.Plot> plots = BuildPlots.find(level, v, bp, 6);
        BuildPlots.Plot first = plots.isEmpty() ? BuildPlots.first(level, v, bp) : plots.get(0);   // [join21] else BuildOrders.suggestPlot's
        if (first != null && plots.isEmpty()) plots = List.of(first);
        if (!c.args.contains("px") && first != null) setPlot(c, first.at(), first.facing());
        if (!c.args.contains("px")) {
            page.text("No free plot in town fits it.", 3);
            return;
        }
        BlockPos at = new BlockPos(c.args.getInt("px"), c.args.getInt("py"), c.args.getInt("pz"));
        Direction facing = Direction.from2DDataValue(c.args.getInt("face"));
        String bad = BuildPlots.whyNot(level, v, bp, at, facing);
        BlockPos centre = v.centre();
        LifePage.Map map = page.map("Its plot (click a dot to choose; the outline is on the ground)");
        map.place(0, 0, "The square", false, 1, null, null);
        map.place(p.getBlockX() - centre.getX(), p.getBlockZ() - centre.getZ(), "You", false, 2, null, null);
        for (BuildPlots.Plot pl : plots) {
            boolean sel = pl.at().getX() == at.getX() && pl.at().getZ() == at.getZ();
            map.place(pl.at().getX() - centre.getX(), pl.at().getZ() - centre.getZ(), pl.label(), sel, 0, "plot",
                LifePage.args("x", pl.at().getX(), "y", pl.at().getY(), "z", pl.at().getZ(), "f", pl.facing().get2DDataValue()));
        }
        if (plots.stream().noneMatch(pl -> pl.at().getX() == at.getX() && pl.at().getZ() == at.getZ())) {
            map.place(at.getX() - centre.getX(), at.getZ() - centre.getZ(), "Your plot (nudged)", true, 0, null, null);
        }
        page.text(bad.isEmpty() ? "✔ It can go here: " + BuildPlots.label(centre, at) + ", its front facing " + facing.getName() + "."
            : "✘ Not here: " + bad + ". Nudge it or choose another dot.", bad.isEmpty() ? 1 : 3);
        page.buttons().button("← West", "nudge", LifePage.args("dx", -1, "dz", 0)).button("East →", "nudge", LifePage.args("dx", 1, "dz", 0))
            .button("↑ North", "nudge", LifePage.args("dx", 0, "dz", -1)).button("South ↓", "nudge", LifePage.args("dx", 0, "dz", 1))
            .button("Turn it", "turn", null);
        int[] g = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(bp);
        int[] size = BuildDesk.size(bp);
        CompoundTag ghost = new CompoundTag();
        ghost.putInt("x", at.getX());
        ghost.putInt("y", at.getY());
        ghost.putInt("z", at.getZ());
        boolean z = facing.getAxis() == Direction.Axis.Z;
        ghost.putInt("gx", z ? g[0] : g[1]);
        ghost.putInt("gz", z ? g[1] : g[0]);
        ghost.putInt("h", size[2] * 4 + 2);
        ghost.putInt("face", facing.get2DDataValue());
        ghost.putBoolean("ok", bad.isEmpty());
        page.ghost(ghost);
        page.buttons().button("Next: confirm", "step", LifePage.args("s", 3), bad.isEmpty(), "Choose a plot it can go on first").primary();
    }

    static void setPlot(PlayerLife.Ctx c, BlockPos at, Direction facing) {
        c.args.putInt("px", at.getX());
        c.args.putInt("py", at.getY());
        c.args.putInt("pz", at.getZ());
        c.args.putInt("face", facing.get2DDataValue());
    }

    /** What it will cost and what you have; the loan if you're short. */
    private void confirm(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        BusinessTypes.BusinessType t = BusinessTypes.of(c.args.getString("kind"));
        if (t == null) { c.args.putInt("step", 1); kinds(level, p, v, c, page); return; }
        int fee = Enterprise.CHARTER_FEE;
        BuildOrders.Quote q = buildQuote(level, p, v, c);
        int total = fee + (q == null ? 0 : q.deposit);
        int have = Market.coinsHeld(p);
        page.heading("Your " + t.noun() + " in " + Villages.name(v.id()));
        page.row(icon(t), t.nameFor(p.getGameProfile().getName()), "a " + t.noun() + ", sells " + wares(t), "").button("Change", "step", LifePage.args("s", 1));
        String prem = c.args.getString("prem");
        PlayerShops.Shop shop = PlayerShops.of(v.id(), p.getUUID());
        page.row(null, "Premises: " + (prem.equals("shop") && shop != null ? "your shop, " + shop.name : prem.equals("build") && q != null
                ? BuildOrders.title(q.drawing) + " the town builds, " + BuildPlots.label(v.centre(), q.plot)
                : t.stall() ? "a stall on the square" : "a room the town lets"), "", "").button("Change", "step", LifePage.args("s", 2));
        page.heading("The cost");
        page.row(null, "The charter, to the town", "", PlayerLife.coins(fee));
        if (q != null) {                                                     // [join21] the builders' quote: the deposit now, the rest as it goes up
            page.row(null, "The building: its deposit now", "Of " + PlayerLife.coins(q.total) + " all told; " + q.stage + "c more at half built, "
                + q.balance + "c when it stands", PlayerLife.coins(q.deposit));
            for (String line : BuildDesk.quoteLines(q)) page.text(line, 4);
            if (!q.ok) page.text("✘ The builders won't build there: " + q.why + ". Choose another plot.", 3);
        }
        page.row(null, "All told", "You have " + PlayerLife.coins(have), PlayerLife.coins(total));
        String standing = mayCharter(level, v, p);
        if (!standing.isEmpty()) {
            page.text(standing, 3);
            page.buttons().button("Ask the nearest folk to live here", "citizen", null);
            return;
        }
        if (have >= total) {
            page.buttons().button("Back", "step", LifePage.args("s", 2)).button("Confirm and open it (" + PlayerLife.coins(total) + ")", "confirm", null).primary();
        } else {
            int short_ = total - have;
            page.text("You need " + PlayerLife.coins(total) + " and have " + have + ": borrow the other " + short_ + " from the bank?", 2);
            page.buttons().button("Back", "step", LifePage.args("s", 2))
                .button("Borrow " + short_ + " and open it", "confirm", LifePage.args("borrow", short_), Bank.open(v.id()), "The town has no bank yet: sell something or take a shift first")
                .primary();
        }
    }

    static String wares(BusinessTypes.BusinessType t) {
        List<String> out = new ArrayList<>();
        for (String g : t.wares()) {
            if (out.size() >= 3) { out.add("..."); break; }
            out.add(Stockroom.plural(g).toLowerCase(Locale.ROOT));
        }
        return String.join(", ", out);
    }

    @Nullable
    static BuildOrders.Quote buildQuote(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c) {
        if (!c.args.getString("prem").equals("build") || !c.args.contains("px") || c.args.getString("bp").isEmpty()) return null;
        List<String> styles = BuildDesk.styles(v.id());
        String style = styles.contains(c.args.getString("style")) ? c.args.getString("style") : styles.get(0);
        return BuildDesk.quote(level, v.id(), c.args.getString("bp"), style,
            new BlockPos(c.args.getInt("px"), c.args.getInt("py"), c.args.getInt("pz")), Direction.from2DDataValue(c.args.getInt("face")), p.getUUID());
    }

    /** "" if the player may hold a charter here, else why not. */
    static String mayCharter(ServerLevel level, Villages.Village v, ServerPlayer p) {
        if (Citizens.is(v.id(), p.getUUID()) || Standing.of(v.id(), p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) return "";
        return "Only a citizen or a friend of " + Villages.name(v.id()) + " may hold a charter. Become a citizen (ask any folk \"May I live here?\"), "
            + "or make friends: gifts, help, trade.";
    }

    private void welcome(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        Business b = Businesses.of(v.id(), c.args.getString("firm"));
        page.heading(b == null ? "Your business" : "Welcome to " + b.name + "!");
        page.text("Your charter is granted. Next:", 1);
        page.row(new ItemStack(Items.IRON_SWORD), "Take on a hand", "Somebody of the trade to make and sell", "").button("Hire a hand", "next", LifePage.args("tab", "hands"));
        page.row(new ItemStack(Items.CHEST), "Stock up", "Order its makings from the town's market", "").button("Order stock", "next", LifePage.args("tab", "stock"));
        page.row(new ItemStack(Items.NAME_TAG), "Set its prices", "Its costs and a markup", "").button("Set prices", "next", LifePage.args("tab", "prices"));
        page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get()), "Fund it", "Coin in its till to pay for stock and wages", "").button("Invest", "next", LifePage.args("tab", "money"));
        page.row(new ItemStack(Items.WRITABLE_BOOK), "Its books", "Takings, costs and profit", "").button("Open the books", "books", null);
        if (c.args.getString("prem").equals("build")) {                      // [join21] the builders' order, as it stands
            BuildDesk.Status st = c.args.getString("order").isEmpty() ? null : BuildDesk.status(level, c.args.getString("order"));
            page.text("The town's builders have your order" + (st == null ? "" : ": " + st.stage() + (st.eta().isEmpty() ? "" : ", done in " + st.eta()))
                + ". You'll be told as it goes up; the rest is paid as it does (the property office has the order).", 4);
        }
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town here.";
        switch (action) {
            case "tab" -> { c.args.putInt("step", Integer.parseInt(c.tab)); return ""; }
            case "step" -> { c.args.putInt("step", args.getInt("s")); return ""; }
            case "kind" -> {
                BusinessTypes.BusinessType t = BusinessTypes.of(args.getString("k"));
                if (t == null || !BusinessTypes.allowed(v.id()).contains(t)) return "!The town can't have one of those yet.";
                c.args.putString("kind", t.key());
                return "";
            }
            case "prem" -> {
                String pr = args.getString("p");
                if (pr.equals("shop") && PlayerShops.of(v.id(), p.getUUID()) == null) return "!You keep no shop here.";
                c.args.putString("prem", pr);
                if (pr.equals("build") && c.args.getString("bp").isEmpty()) {
                    BusinessTypes.BusinessType t = BusinessTypes.of(c.args.getString("kind"));
                    c.args.putString("bp", t == null ? "shop" : BuildDesk.forBusiness(t.key()));
                    c.args.remove("px");
                }
                return "";
            }
            case "bp" -> {
                c.args.putString("bp", args.getString("bp"));
                c.args.remove("px");
                return "";
            }
            case "style" -> {
                c.args.putString("style", args.getCompound("f").getString("style"));
                return "";
            }
            case "plot" -> {
                setPlot(c, new BlockPos(args.getInt("x"), args.getInt("y"), args.getInt("z")), Direction.from2DDataValue(args.getInt("f")));
                return "";
            }
            case "nudge" -> {
                if (!c.args.contains("px")) return "";
                int x = c.args.getInt("px") + args.getInt("dx"), z = c.args.getInt("pz") + args.getInt("dz");
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                setPlot(c, new BlockPos(x, y, z), Direction.from2DDataValue(c.args.getInt("face")));
                return "";
            }
            case "turn" -> {
                if (!c.args.contains("px")) return "";
                c.args.putInt("face", Direction.from2DDataValue(c.args.getInt("face")).getClockWise().get2DDataValue());
                return "";
            }
            case "citizen" -> { return LifeTown.sayToNearest(p, "May I live here?"); }
            case "confirm" -> { return confirmed(level, p, v, c, args.getInt("borrow")); }
            case "next" -> {
                PlayerLife.open(p, "firm", LifePage.args("firm", c.args.getString("firm"), "tab", args.getString("tab")));
                return "#open:";
            }
            case "books" -> {
                Business b = Businesses.of(v.id(), c.args.getString("firm"));
                if (b != null) BusinessBooks.show(p, v.id(), b);
                return b == null ? "!No such business." : "#open:";
            }
            default -> { return null; }
        }
    }

    /** The charter granted (borrowing first if asked), its premises seen to, the welcome. */
    static String confirmed(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, int borrow) {
        BusinessTypes.BusinessType t = BusinessTypes.of(c.args.getString("kind"));
        if (t == null || !BusinessTypes.allowed(v.id()).contains(t)) return "!Choose a kind the town can have.";
        String may = mayCharter(level, v, p);
        if (!may.isEmpty()) return "!" + may;
        BuildOrders.Quote q = buildQuote(level, p, v, c);
        if (c.args.getString("prem").equals("build")) {
            if (q == null) return "!Choose a building and its plot first.";
            String bad = !q.ok ? q.why : BuildPlots.whyNot(level, v, q.drawing, q.plot, q.facing);
            if (!bad.isEmpty()) return "!Its plot won't do: " + bad + ".";
        }
        int total = Enterprise.CHARTER_FEE + (q == null ? 0 : q.deposit);
        if (borrow > 0 && Market.coinsHeld(p) < total) {
            String said = FolkCredit.playerBorrows(level, p, v.id(), Math.min(borrow, total - Market.coinsHeld(p)));
            if (Market.coinsHeld(p) < total) return "!The bank said: " + said;
        }
        if (Market.coinsHeld(p) < total) return "!You need " + PlayerLife.coins(total) + "; you have " + Market.coinsHeld(p) + ".";
        Market.payOut(p, Enterprise.CHARTER_FEE);
        com.jrpetty.mcassistant.village.Ledger.addCoins(v.id(), Enterprise.CHARTER_FEE);
        CoinCensus.playerIn(v.id(), Enterprise.CHARTER_FEE, p, PlayerFinance.Cat.FEES, "a firm's charter");   // [join21]
        TownBudget.in(v.id(), "fees", Enterprise.CHARTER_FEE * 100L);
        Business b = Enterprise.found(level, v, t, null, p, Businesses.today(level), null);
        if (b == null) return "!The charter could not be drawn up (your fee is kept by the treasury).";
        c.args.putString("firm", b.id);
        String prem = c.args.getString("prem");
        PlayerShops.Shop shop = PlayerShops.of(v.id(), p.getUUID());
        if (prem.equals("shop") && shop != null) {
            b.at = shop.anchor;
            shop.firm = b.id;
            PlayerShops.save(shop);
            CompoundTag r = Enterprise.rec(v.id(), b.id);
            if (r != null) r.putLong("at", shop.anchor.asLong());
            Businesses.dirty();
        } else if (prem.equals("build") && q != null) {
            String id = BuildDesk.place(level, p, v.id(), q, "premises", b.id);
            if (id.startsWith("!")) return "Your charter is granted, but the building wasn't: " + id.substring(1);
            c.args.putString("order", id);
        }
        c.args.putInt("step", 4);
        return "Your charter is granted: " + b.name + ", a " + t.noun() + ".";
    }

    /** Tests: the whole flow at once, with its defaults. */
    public static String startForTests(ServerPlayer p, String kind, String prem) {
        PlayerLife.open(p, "start", null);
        PlayerLife.Ctx c = PlayerLife.ctx(p);
        c.args.putString("kind", kind);
        c.args.putString("prem", prem);
        Villages.Village v = PlayerLife.here(p);
        return v == null ? "!no town" : confirmed(p.serverLevel(), p, v, c, 0);
    }
}
