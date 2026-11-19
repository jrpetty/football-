package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [player15] Your shop's screen (PlayerShops), from its counter or sign, the Your life in town page or the city books:
 * <ul>
 * <li><b>Setup</b>: its name, what kind of shop, its hours, and the checklist of what it needs, each missing thing with
 *     where to get it; Register (the licence, the first time) and Save.</li>
 * <li><b>Shop</b>: how it stands, the till (Take the till), today's and the week's takings, its name with the town, and
 *     what it still needs.</li>
 * <li><b>Stock</b>: each ware on its shelves and in the stockroom with its price for a lot (− and +, shift for ten;
 *     Going price; Keep back) beside the going price and how folk will take it, who would buy it and why; and each chest
 *     of the shop, on the shelves or back stock, with a click to change it.</li>
 * <li><b>Books</b>: takings by day, the last sales, what sold out, what was too dear, the customers' mood, its name.</li>
 * <li><b>Keeper</b>: the keeper, its wage and hours, or who could be taken on.</li>
 * </ul>
 * Every button is checked on the server: the shop must be yours, and the price a number.
 */
public final class ShopPage implements PlayerLife.Page {

    static final int[] OPEN_HOURS = { 5, 6, 7, 8, 9, 10, 12 }, CLOSE_HOURS = { 12, 14, 16, 17, 18, 19, 20, 21, 22, 24 };

    static int tick(int hour) {
        return Math.floorMod((hour - 6) * 1000, 24000);
    }

    static int hour(int tick) {
        return Math.floorMod(tick / 1000 + 6, 24);
    }

    private static String[] hourValues(int[] hours) {
        String[] out = new String[hours.length];
        for (int i = 0; i < hours.length; i++) out[i] = Integer.toString(hours[i]);
        return out;
    }

    private static String[] hourLabels(int[] hours) {
        String[] out = new String[hours.length];
        for (int i = 0; i < hours.length; i++) out[i] = PlayerShops.clock(tick(hours[i]));
        return out;
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        PlayerShops.Shop s = PlayerShops.mine(p);
        if (s == null) {
            LifePage page = new LifePage("shop", "Your shop", "You keep no shop yet", p.registryAccess());
            page.heading("How to open a shop of your own");
            page.text("1. Build a room in town however you like: walls, a roof, a door onto a street.");
            page.text("2. Set a Shop Counter inside (or hang a Shop Sign on its wall). Craft one, or buy one at the town's shop.");
            page.text("3. A chest, barrel or shelf inside holds what you sell. Name it, set its hours, and folk will come.");
            page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), "Shop Counter",
                    "Three wooden slabs over a chest between two planks, a gold nugget under the chest", "")
                .button("Where to buy one", "where", null);
            page.buttons().button("Start a business instead", "start", null).button("Back to your life in town", "life", null);
            return page;
        }
        if (c.tab.isEmpty()) c.tab = s.setUp ? "shop" : "setup";
        String state = PlayerShops.stateWords(level, s);
        LifePage page = new LifePage("shop", s.name, ShopNeeds.Kind.of(s.kind).label + " in " + Villages.name(s.village) + " — " + state, p.registryAccess());
        page.tab("shop", "Shop", c.tab.equals("shop")).tab("setup", "Setup", c.tab.equals("setup")).tab("stock", "Stock & prices", c.tab.equals("stock"))
            .tab("books", "Books", c.tab.equals("books")).tab("keeper", "Keeper", c.tab.equals("keeper"));
        switch (c.tab) {
            case "setup" -> setup(level, p, s, page);
            case "stock" -> stock(level, s, page);
            case "books" -> books(level, s, page);
            case "keeper" -> keeper(level, s, page);
            default -> overview(level, p, s, page, c);
        }
        return page;
    }

    private void checklist(ServerLevel level, PlayerShops.Shop s, LifePage page) {
        List<ShopNeeds.Need> needs = PlayerShops.needs(level, s);
        boolean ready = ShopNeeds.ready(needs);
        page.heading(ready ? "Everything a shop needs ✔" : "What it still needs");
        for (ShopNeeds.Need n : needs) {
            LifePage.Row r = page.row(null, n.line(), n.ok() ? "" : n.fix(), "");
            if (!n.ok() && (n.id().equals("counter") || n.id().equals("kit"))) r.button("Where to buy", "where", null);
        }
        page.buttons().button("Check again", "recheck", null);
    }

    private void setup(ServerLevel level, ServerPlayer p, PlayerShops.Shop s, LifePage page) {
        page.heading("Your shop");
        page.field("name", "Its name", s.name, 32);
        ShopNeeds.Kind[] kinds = ShopNeeds.Kind.values();
        String[] kv = new String[kinds.length], kl = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) { kv[i] = kinds[i].id; kl[i] = kinds[i].label; }
        page.choice("kind", "What kind", kv, kl, s.kind);
        page.choice("opens", "Opens at", hourValues(OPEN_HOURS), hourLabels(OPEN_HOURS), Integer.toString(hour(s.opens)));
        page.choice("closes", "Closes at", hourValues(CLOSE_HOURS), hourLabels(CLOSE_HOURS), Integer.toString(hour(s.closes) == 0 ? 24 : hour(s.closes)));
        page.choice("tell", "Tell me of sales", new String[]{ "yes", "no" }, new String[]{ "Yes, in chat", "No" }, s.tell ? "yes" : "no");
        int fee = PlayerShops.licence(s.village);
        if (!s.licensed) page.text("Registering it with " + Villages.name(s.village) + " costs a licence of " + PlayerLife.coins(fee)
            + " (you have " + Market.coinsHeld(p) + "); then the business rates, " + PlayerLife.coins(TaxBook.ratesCoins(s.village))
            + " a week, out of its till.", Market.coinsHeld(p) >= fee ? 0 : 2);
        page.buttons().button(s.licensed ? "Save" : "Register the shop (" + PlayerLife.coins(fee) + ")", "setup", null).primary();
        checklist(level, s, page);
    }

    private void overview(ServerLevel level, ServerPlayer p, PlayerShops.Shop s, LifePage page, PlayerLife.Ctx c) {
        boolean ready = ShopNeeds.ready(PlayerShops.needs(level, s));
        page.heading("How it stands");
        page.text(PlayerShops.stateWords(level, s) + ". Hours: " + PlayerShops.clock(s.opens) + " till " + PlayerShops.clock(s.closes) + ".",
            PlayerShops.state(level, s) == PlayerShops.State.OPEN ? 1 : ready ? 0 : 3);
        long day = PlayerShops.today(level);
        PlayerShops.roll(s, day);
        page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get()), "The till: " + PlayerLife.coins(s.till),
                "Today " + PlayerLife.coins(s.weekCoin[0]) + " for " + s.weekCount[0] + " things; this week " + PlayerLife.coins(PlayerShops.week(s.weekCoin))
                    + (s.ratesOwed > 0 ? "; rates owed " + s.ratesOwed : ""), "")
            .button("Take the till", "till", null, s.till > 0, "Nothing in it yet");
        page.bar("Its name in town", s.rep, words(s.rep));
        page.text("This week: " + s.fair + " pleased with a fair price, " + s.dearN + " thought it too dear, " + s.bare + " found nothing they wanted"
            + (s.keeper != null ? ", " + s.greeted + " greeted by " + s.keeperName : "") + ".", 4);
        if (!ready) checklist(level, s, page);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> w = PlayerShops.wares(level, s, counts, false);
        if (ready && w.isEmpty()) page.text("Its shelves are bare: put what you'd sell in a chest inside (not one marked back stock).", 2);
        page.heading("More");
        page.buttons().button("Stock & prices", "tab:stock", null).button("Take on a keeper", "tab:keeper", null)
            .button("Make it a registered business", "business", null);
        page.buttons().button("Sell", "property", LifePage.args("what", "sell")).button("Let", "property", LifePage.args("what", "let"))
            .button("Repurpose", "property", LifePage.args("what", "repurpose"))
            .button(c.args.getBoolean("confirmClose") ? "Yes, close it for good" : "Close the shop", "close", LifePage.args("sure", c.args.getBoolean("confirmClose")));
    }

    static String words(double rep) {
        return rep >= 0.8 ? "everybody speaks well of it" : rep >= 0.6 ? "well thought of" : rep >= 0.4 ? "known" : rep >= 0.2 ? "thought dear" : "avoided";
    }

    private void stock(ServerLevel level, PlayerShops.Shop s, LifePage page) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> w = PlayerShops.wares(level, s, counts, true);
        for (String k : s.prices.keySet()) if (!w.containsKey(k)) { ItemStack x = PlayerStalls.sampleOf(k); if (!x.isEmpty()) { w.put(k, x); counts.put(k, 0); } }
        page.heading("Prices (a lot each; shift-click for ten)");
        if (w.isEmpty()) page.text("Nothing in its chests yet. Put what you'd sell in a chest, barrel or shelf inside the shop.", 2);
        for (Map.Entry<String, ItemStack> e : w.entrySet()) {
            ItemStack x = e.getValue();
            String k = e.getKey();
            int lot = PlayerStalls.lot(x);
            int going = PlayerStalls.going(level, s.village, x);
            Integer set = s.prices.get(k);
            int price = set != null ? set : going;
            String take = set != null && set == 0 ? "kept back" : going <= 0 ? "nobody here deals in it" : judge(price, going);
            page.row(x, PlayerStalls.nameOf(x) + " — " + counts.getOrDefault(k, 0) + " (a lot is " + lot + ")",
                    "Going " + going + ", yours " + (set == null ? going + " (going)" : set == 0 ? "kept back" : set) + ": " + take + ". "
                        + ShopCustomers.whoBuys(level, s.village, x), "")
                .button("-", "price", LifePage.args("k", k, "d", -1)).shift()
                .button("+", "price", LifePage.args("k", k, "d", 1)).shift()
                .button("Going", "price", LifePage.args("k", k, "going", true), set != null, "It sells at the going price already")
                .button("Keep back", "price", LifePage.args("k", k, "zero", true), set == null || set != 0, "Kept back already");
        }
        // [join21] What customers looked at and had no use for (ItemUses), and why: stock what the town can use.
        net.minecraft.nbt.ListTag refused = ItemUses.refusedTag(s.village, ShopCustomers.refusals(s.owner));
        if (!refused.isEmpty()) {
            page.heading("Looked at, but no use to them");
            for (int i = 0; i < Math.min(5, refused.size()); i++) {
                net.minecraft.nbt.CompoundTag r = refused.getCompound(i);
                page.text(ShopCustomers.capital(r.getString("what")) + ": " + r.getString("who") + " — \"" + r.getString("why") + "\"", 4);
            }
        }
        ShopNeeds.Survey sv = PlayerShops.survey(level, s);
        page.heading("Its chests");
        if (sv.stock.isEmpty()) page.text("No chest, barrel or shelf inside its walls yet.", 3);
        for (BlockPos pos : sv.stock) {
            boolean back = s.back.contains(pos);
            String what = level.getBlockEntity(pos) instanceof Container cc ? summary(cc) : "";
            page.row(new ItemStack(level.getBlockState(pos).getBlock().asItem()), (back ? "Back stock" : "On the shelves") + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(),
                    what, "")
                .button(back ? "Put on the shelves" : "Make it back stock", "back", LifePage.args("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
        }
        page.text("Back stock is the stockroom's: customers don't see it, and a keeper fills the shelves from it.", 4);
    }

    static String judge(int price, int going) {
        double r = price / (double) Math.max(1, going);
        return r < 0.9 ? "a bargain" : r <= 1.0 ? "fair" : r <= 1.1 ? "a little dear: the comfortable" : r <= 1.25 ? "dear: the well-off"
            : r <= 1.5 ? "dear: the rich only" : "too dear: nobody";
    }

    private static String summary(Container c) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack x = c.getItem(i);
            if (!x.isEmpty()) n.merge(PlayerStalls.nameOf(x).toLowerCase(Locale.ROOT), x.getCount(), Integer::sum);
        }
        if (n.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : n.entrySet()) {
            if (sb.length() > 60) { sb.append("..."); break; }
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append(" ").append(e.getKey());
        }
        return sb.toString();
    }

    private void books(ServerLevel level, PlayerShops.Shop s, LifePage page) {
        long day = PlayerShops.today(level);
        PlayerShops.roll(s, day);
        page.heading("Takings, day by day");
        int most = 1;
        for (int x : s.weekCoin) most = Math.max(most, x);
        for (int i = 0; i < PlayerShops.WEEK; i++) {
            page.bar(i == 0 ? "Today" : i == 1 ? "Yesterday" : "Day " + (day - i), s.weekCoin[i] / (double) most, PlayerLife.coins(s.weekCoin[i]) + ", " + s.weekCount[i] + " sold");
        }
        page.text("All told: " + s.soldAll + " sold for " + PlayerLife.coins(s.takenAll) + " since day " + s.since + ".", 4);
        page.heading("The last sales");
        if (s.sales.isEmpty()) page.text("None yet.", 4);
        for (int i = 0; i < Math.min(10, s.sales.size()); i++) {
            PlayerShops.Sale x = s.sales.get(i);
            page.row(PlayerStalls.sampleOf(x.key()), x.who() + " bought " + x.n() + " " + PlayerStalls.nameOf(PlayerStalls.sampleOf(x.key())).toLowerCase(Locale.ROOT),
                "day " + x.day(), PlayerLife.coins(x.price()));
        }
        page.heading("Too dear");
        if (s.dear.isEmpty()) page.text("Nobody has thought it too dear.", 4);
        for (int i = 0; i < Math.min(6, s.dear.size()); i++) {
            PlayerShops.Dear d = s.dear.get(i);
            page.text(d.who() + " on day " + d.day() + ": " + d.price() + " for " + PlayerStalls.nameOf(PlayerStalls.sampleOf(d.key())).toLowerCase(Locale.ROOT)
                + ", the going price " + d.going() + ".");
        }
        if (!s.soldOut.isEmpty()) {
            page.heading("Sold out");
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> e : s.soldOut.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(PlayerStalls.nameOf(PlayerStalls.sampleOf(e.getKey())).toLowerCase(Locale.ROOT)).append(" (day ").append(e.getValue()).append(")");
            }
            page.text(sb.toString());
        }
        page.heading("Its customers");
        page.bar("Its name", s.rep, words(s.rep));
        page.text("This week " + s.fair + " bought at a price they thought fair, " + s.dearN + " thought it too dear, " + s.bare
            + " found nothing they wanted. Fair prices, full shelves and a keeper who greets them bring more.");
    }

    private void keeper(ServerLevel level, PlayerShops.Shop s, LifePage page) {
        if (s.keeper != null) {
            page.heading("Its keeper");
            VillageFolkEntity f = Homes.loaded(s.village, s.keeper);
            page.row(null, s.keeperName + (f == null ? "" : ", " + (f.hobbyNow() == null ? "about the town" : f.hobbyNow())),
                    PlayerLife.coins(s.wage) + " a day out of the till" + (s.owed > 0 ? "; owed " + s.owed : "") + ", since day " + s.keeperSince, "")
                .button("Let it go", "letgo", null);
            page.choice("khours", "Its hours", new String[]{ "shop", "morning", "afternoon" },
                new String[]{ "The shop's hours", "Mornings (till noon)", "Afternoons (from noon)" },
                s.kOpens == s.opens && s.kCloses == s.closes ? "shop" : s.kCloses == tick(12) ? "morning" : "afternoon");
            page.buttons().button("Set its hours", "khours", null);
            page.text("In its hours it stands behind the counter, greets each customer and fills the shelves from the back stock.", 4);
            return;
        }
        page.heading("Take on a keeper");
        page.text("A keeper minds the counter while you are away, greets customers and restocks the shelves from the back stock, for its day's wage out of the till each morning.", 4);
        List<ShopKeepers.Candidate> cs = ShopKeepers.candidates(level, s.village, 8);
        if (cs.isEmpty()) page.text("Nobody in " + Villages.name(s.village) + " can be spared just now: every hand is at its trade. Try again tomorrow.", 2);
        for (ShopKeepers.Candidate cand : cs) {
            page.row(null, cand.folk().displayNameCap(), cand.why() + "; asks " + PlayerLife.coins(cand.wage()) + " a day", "")
                .button("Take on", "hire", LifePage.args("u", cand.folk().getUUID().toString()));
        }
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        if (action.equals("life")) { PlayerLife.open(p, "life", null); return "#open:"; }
        if (action.equals("start")) { PlayerLife.open(p, "start", null); return "#open:"; }
        if (action.equals("where")) return whereToBuy(level, p);
        PlayerShops.Shop s = PlayerShops.mine(p);
        if (s == null) return "!You keep no shop.";
        if (action.startsWith("tab:")) { c.tab = action.substring(4); return ""; }
        CompoundTag f = args.getCompound("f");
        switch (action) {
            case "recheck" -> {
                PlayerShops.resurvey(level, s);
                return ShopNeeds.ready(s.needs) ? "It has everything a shop needs." : "Still " + ShopNeeds.missing(s.needs) + ".";
            }
            case "setup" -> {
                int opens = tick(parse(f.getString("opens"), hour(s.opens)));
                int closes = tick(parse(f.getString("closes"), hour(s.closes)));
                s.tell = !f.getString("tell").equals("no");
                String said = PlayerShops.setUp(level, p, s, f.getString("name"), f.getString("kind"), opens, closes);
                if (!said.startsWith("!") && ShopNeeds.ready(s.needs)) c.tab = "shop";
                return said;
            }
            case "till" -> {
                int n = PlayerShops.takeTill(level, p, s);
                return n > 0 ? PlayerLife.coins(n) + " from the till, into your pack." : "!The till is empty.";
            }
            case "price" -> {
                String k = args.getString("k");
                ItemStack sample = PlayerStalls.sampleOf(k);
                if (sample.isEmpty()) return "!No such ware.";
                if (args.getBoolean("going")) s.prices.remove(k);
                else if (args.getBoolean("zero")) s.prices.put(k, 0);
                else {
                    int now = s.prices.getOrDefault(k, PlayerStalls.going(level, s.village, sample));
                    s.prices.put(k, Math.max(0, Math.min(9999, now + args.getInt("d"))));
                }
                PlayerShops.save(s);
                return "";
            }
            case "back" -> {
                BlockPos pos = new BlockPos(args.getInt("x"), args.getInt("y"), args.getInt("z"));
                if (!PlayerShops.survey(level, s).stock.contains(pos)) return "!That chest isn't the shop's.";
                if (!s.back.remove(pos)) s.back.add(pos);
                PlayerShops.resurvey(level, s);
                PlayerShops.save(s);
                return s.back.contains(pos) ? "Back stock now: customers won't see it." : "On the shelves now.";
            }
            case "hire" -> {
                java.util.UUID u;
                try { u = java.util.UUID.fromString(args.getString("u")); } catch (IllegalArgumentException e) { return "!Who?"; }
                VillageFolkEntity folk = Homes.loaded(s.village, u);
                if (folk == null) return "!They aren't about.";
                boolean ok = false;
                for (ShopKeepers.Candidate cand : ShopKeepers.candidates(level, s.village, 64)) if (cand.folk() == folk) ok = true;
                if (!ok) return "!" + folk.displayNameCap() + " can't be spared now.";
                return ShopKeepers.hire(level, s, folk);
            }
            case "letgo" -> {
                String name = s.keeperName;
                ShopKeepers.letGo(level, s, "let go by its owner");
                return name + " is let go.";
            }
            case "khours" -> {
                String h = f.getString("khours");
                if (h.equals("morning")) { s.kOpens = s.opens; s.kCloses = tick(12); }
                else if (h.equals("afternoon")) { s.kOpens = tick(12); s.kCloses = s.closes; }
                else { s.kOpens = s.opens; s.kCloses = s.closes; }
                PlayerShops.save(s);
                return "Its keeper works " + PlayerShops.clock(s.kOpens) + " till " + PlayerShops.clock(s.kCloses) + ".";
            }
            case "business" -> {
                PlayerLife.open(p, "start", LifePage.args("premises", "shop"));
                return "#open:";
            }
            case "property" -> {
                return PropertyDesk.open(p, args.getString("what"), "shop");
            }
            case "close" -> {
                if (!args.getBoolean("sure")) {
                    c.args.putBoolean("confirmClose", true);
                    return "!Close " + s.name + " for good? Press Close the shop again to be sure.";
                }
                return PlayerShops.close(level, p, s);
            }
            default -> { return null; }
        }
    }

    static int parse(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    /** Where a shop counter (or the kit) can be had: the town's shop, else the crafting. */
    static String whereToBuy(ServerLevel level, ServerPlayer p) {
        Villages.Village v = PlayerLife.here(p);
        BlockPos shop = v == null ? null : Villages.builtAt(v.id(), Store.buildingForShop(v.id()));
        if (shop != null) {
            PlayerLife.mark(p, shop, "the town's shop");
            return "The town's shop sells shop counters and signs (ask at its counter); a marker is set on it. Or craft one: three wooden slabs over a chest between two planks, a gold nugget under.";
        }
        return "Craft a shop counter: three wooden slabs over a chest between two planks, a gold nugget under the chest.";
    }
}
