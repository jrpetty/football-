package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [player15] Everything a player can do, and where to do it without typing a command (a server may ban them): every one
 * of the mod's player commands has its way in the world here (a block, a screen's button, a folk's talk option, a key),
 * and a button that goes there or does it. The few with no screen of their own are done from this page itself, on the
 * server, never sent as a command or as chat. {@link #ROUTES} is the table; a game test checks that every player command
 * the mod registers is in it, so a new one cannot be left without a way in.
 */
public final class InWorld implements PlayerLife.Page {

    /** A command's way in: its first words, the tab it is listed on, how it is done in the world, and the button. */
    public record Route(String command, String tab, String how, String action) {}

    static Route r(String command, String tab, String how, String action) {
        return new Route(command, tab, how, action);
    }

    /** Operators' and the world's own tools: not a player's. */
    public static final List<String> OPERATOR = List.of("admin", "spawnat", "level", "tidy", "speed", "version", "money",
        "anim", "outdoor");   // the animators' own tool (AnimCommands: playing, marking and staging the folk's moves)

    public static final List<Route> ROUTES = List.of(
        // ---------------------------------------------------------------- work and money
        r("earnings", "work", "The Earnings page (the city books, the U page)", "page:Earnings"),
        r("earnings shift", "work", "Take a shift: the jobs board, the Earnings page, a counter's Work tab, or the boss in talk", "life:jobs"),
        r("earnings quit", "work", "Quit a shift or a standing job: the jobs board, or the shift HUD's card", "life:jobs"),
        r("earnings wanted", "work", "The town's Wanted orders: the store's Sell tab (Deliver), the town board", "page:Earnings"),
        r("jobs", "work", "The Jobs page of the city books; apply for an opening on the jobs board", "life:jobs"),
        r("labour", "work", "The labour market: the Jobs page of the city books", "page:Jobs"),
        r("labour post", "business", "Post an opening for your firm: Your businesses, its Hands tab", "life:firm"),
        r("labour hire", "business", "Take the best who applied: Your businesses, its Hands tab", "life:firm"),
        r("labour minwage", "civic", "The minimum wage (a leader's): the Leader's page", "talk:What are your plans for the town?"),
        r("wages", "work", "Wages: the Jobs page of the city books, and each folk's card", "page:Jobs"),
        r("interviews", "civic", "Interviews for the town's posts: the Interviews page of the city books (choose, recommend)", "page:Interviews"),
        r("bank", "money", "The bank's counter (right-click it, or the banker's Bank talk option): deposit, withdraw, mortgage, repay", ""),
        r("capital", "money", "Savings, loans and bonds at the bank's counter; land titles and shares on this page's Capital tab", "tab:capital"),
        r("donate", "money", "Give to the town's treasury: this page (choose how much)", "tab:money"),
        r("accounts", "money", "The town's accounts: the Money page of the city books", "page:Money"),
        r("quests", "work", "Quests: the quest journal (its item), and any folk's Quests talk option", "talk:Any work for me?"),
        // ---------------------------------------------------------------- shops and business
        r("stall", "business", "Your stall: right-click a stall to let (or a folk's \"Could I rent a stall?\"); its screen from its barrel or sign", "talk:Could I rent a stall?"),
        r("shop", "business", "Your own shop: its counter or sign; the town's shop: its counter", "life:shop"),
        r("business", "business", "The town's businesses: the Businesses page of the city books; a business's books from its owner in talk", "page:Businesses"),
        r("business invest", "business", "Invest in your firm: Your businesses, its Money tab (or the books' Invest button)", "life:firm"),
        r("business draw", "business", "Take your drawings: Your businesses, its Money tab", "life:firm"),
        r("business order", "business", "Order stock: Your businesses, its Stock tab", "life:firm"),
        r("business buy", "business", "Buy a business: its books' Buy button (the Businesses page)", "page:Businesses"),
        r("firms", "business", "Start a business: the bank, the village board's button, any folk's \"I'd like to start a business\"", "life:start"),
        r("firms charter", "business", "Start a business: three clicks (a kind, premises, confirm)", "life:start"),
        r("firms hire", "business", "Take on a hand: Your businesses, its Hands tab", "life:firm"),
        r("firms markup", "business", "Set its prices: Your businesses, its Prices tab", "life:firm"),
        r("auction", "business", "The auction house's counter: bid, put a lot up, its books", "page:Auction"),
        r("market", "business", "The market: the Market page of the city books", "page:Market"),
        r("prices", "business", "Prices: the Prices page of the city books", "page:Prices"),
        r("trade", "business", "Trade between towns: the Trade page of the city books", "page:Trade"),
        r("trades", "business", "Learn a trade from a master: a folk's \"Will you take me as your apprentice?\"", "talk:Will you take me as your apprentice?"),
        r("workshop", "business", "The shop's workshop: the Shops page; order a thing at the town shop's counter (Order)", "page:Shops"),
        r("stock", "business", "The town's stock: the Stock page of the city books", "page:Stock"),
        r("stores", "business", "The stores: the Stores page of the city books", "page:Stores"),
        r("emerald", "business", "The emerald trader: talk to it (its trade option)", "page:Trading Post"),
        r("harbour", "business", "The harbour: the Harbour page of the city books", "page:Harbour"),
        r("fleet", "business", "The fishing fleet: the Harbour page", "page:Harbour"),
        r("hospitality", "business", "The inn, the tavern, the dining rooms: their counters", "page:Shops"),
        r("dining", "business", "The restaurant: its counter (the order screen)", "page:Shops"),
        r("larder", "business", "The town's larder: the Production page", "page:Production"),
        r("items", "business", "The town's work items: the Production page", "page:Production"),
        // ---------------------------------------------------------------- town and civic
        r("civic", "civic", "Standing for leader: a folk's \"I'd like to stand for election\" (the hustings page and its buttons)", "talk:I'd like to stand for election"),
        r("leader", "civic", "Leading the town: the Leader's page and its buttons (the Leader page of the city books)", "page:Leader"),
        r("referendum", "civic", "A referendum: the Leader page; vote in talk", "page:Leader"),
        r("knight", "civic", "A knight's service, vote, manor and retinue: the Knights page of the city books (its buttons) and talk", "page:Knights"),
        r("taxes", "civic", "Taxes: the Taxes page; pay at any folk's \"I'll pay my taxes\"", "page:Taxes"),
        r("taxes pay", "civic", "Pay your taxes: any folk's talk option", "talk:I'll pay my taxes"),
        r("identity", "civic", "The town's identity and laws: the Identity page", "page:Identity"),
        r("inventions", "civic", "Research and inventions: the Inventions page (give, pick, sponsor, suggest)", "page:Inventions"),
        r("research", "civic", "Research: the Research page", "page:Research"),
        r("offices", "civic", "The town's offices: the Civic page", "page:Civic"),
        r("borders", "civic", "Borders and disputes: the Lands page and the war room's map table", "page:Lands"),
        r("war", "civic", "War: the War page, the war room's map table, and the Captain in talk", "page:War"),
        r("afterwar", "civic", "After a war: the War page", "page:War"),
        r("arms", "civic", "The town's arms: the village board (its banner)", "page:Identity"),
        r("watch", "civic", "The watch: the Watch page", "page:Watch"),
        r("police", "civic", "The police: the Watch page", "page:Watch"),
        r("crime", "civic", "Crime and cases: the Cases page", "page:Cases"),
        r("newcomers", "civic", "Newcomers: the People page", "page:People"),
        r("migration", "civic", "Migration: the People page", "page:People"),
        r("relations", "civic", "Other towns: the Trade page", "page:Trade"),
        r("districts", "civic", "Districts: the Town page", "page:Town"),
        r("wonder", "civic", "Wonders: the Culture page", "page:Culture"),
        // ---------------------------------------------------------------- home and belonging
        r("house", "home", "Your home: Your life in town (buy the house you stand in, collect rent); the bank's mortgage", "life:life"),
        r("house market", "home", "The housing market: the Homes page", "page:Homes"),
        r("flats", "home", "Flats: the Homes page", "page:Homes"),
        r("favourite", "home", "Favourite folk: the star on a folk's profile, the favourites key, the city books' star button", ""),
        r("emote", "home", "Emotes: the emote wheel key", ""),
        r("individual", "home", "A folk's card and profile: click it in the Folk page, or its profile in talk", "page:Folk"),
        r("knacks", "home", "Knacks and skills: the Perks page", "page:Perks"),
        r("perks", "home", "Perks, quirks and wonders: the Perks page", "page:Perks"),
        r("standing", "home", "Your standing: Your life in town", "life:life"),
        r("theatre", "home", "The theatre: its playbill; a ticket from this page", "cmd:village theatre ticket"),
        r("fair", "home", "The fair: enter from this page", "cmd:village fair enter"),
        r("busk", "home", "Busking on the square: from this page", "cmd:village busk"),
        r("sport", "home", "Sport: the Culture page", "page:Culture"),
        r("pets", "home", "Pets: the Society page", "page:Society"),
        r("fashion", "home", "Fashion: the Fashion page", "page:Fashion"),
        r("birthdays", "home", "Birthdays: the News page", "page:News"),
        r("fireworks", "home", "Fireworks: any folk's \"When are the next fireworks?\"", "talk:When are the next fireworks?"),
        r("bell", "home", "The town bell: the News page", "page:News"),
        r("season", "home", "The season: the Overview", "page:Overview"),
        r("news", "home", "The morning news in chat: on or off, from this page", "cmd:village news on"),
        r("culture", "home", "Culture: the Culture page", "page:Culture"),
        r("library", "home", "The library: the Library page", "page:Library"),
        r("museum", "home", "The museum: the Museum page", "page:Museum"),
        r("school", "home", "The school: the School page", "page:School"),
        r("care", "home", "The town's care: the Society page", "page:Society"),
        r("lifespans", "home", "Lifespans: the People page", "page:People"),
        // ---------------------------------------------------------------- the town's books
        r("stats", "info", "The town's books: the village board (right-click it)", "page:Overview"),
        r("status", "info", "How the town stands: the Overview", "page:Overview"),
        r("list", "info", "The towns about: the Trade page", "page:Trade"),
        r("people", "info", "Its people: the People page", "page:People"),
        r("folk", "info", "Its folk: the Folk page", "page:Folk"),
        r("chronicle", "info", "The chronicle: the News page, or any folk's Chronicle option", "page:News"),
        r("ledger", "info", "The ledger: the Records page", "page:Records"),
        r("top", "info", "The leading towns: the Records page", "page:Records"),
        r("economy", "info", "The economy: the Economy page", "page:Economy"),
        r("crafts", "info", "The crafts: the Production page", "page:Production"),
        r("decor", "info", "The town's look: the Buildings page", "page:Buildings"),
        r("townlook", "info", "The town's look: the Buildings page", "page:Buildings"),
        r("theme", "info", "The town's theme: the Buildings page", "page:Buildings"),
        r("wall", "info", "The town's wall: the Buildings page", "page:Buildings"),
        r("roads", "info", "Roads: the Roads page", "page:Roads"),
        r("highways", "info", "Highways: the Roads page", "page:Roads"),
        r("paths", "info", "Paths: the Roads page", "page:Roads"),
        r("ways", "info", "The ways of the town: any folk's \"What's this town like?\"", "talk:What's this town like?"),
        r("transport", "info", "Transport: the Transport page", "page:Transport"),
        r("wagon", "info", "The wagons: the Transport page", "page:Transport"),
        r("horses", "info", "The stable: the Transport page", "page:Transport"),
        r("maps", "info", "Maps: the Maps page", "page:Maps"),
        r("caves", "info", "The caves: the Caves page", "page:Caves"),
        r("nether", "info", "The Nether: the Nether page", "page:Nether"),
        r("mine", "info", "The mine: the Production page", "page:Production"),
        r("monsters", "info", "Monsters: the Watch page", "page:Watch"),
        r("disasters", "info", "Disasters: the News page", "page:News"),
        r("golems", "info", "Golems: the Watch page", "page:Watch"),
        r("fletcher", "info", "The fletcher: the Production page", "page:Production"),
        r("diver", "info", "The diver: the Harbour page", "page:Harbour"),
        r("redstone", "info", "The redstone works: the Production page", "page:Production"),
        r("sweeper", "info", "The sweeper: the Town page", "page:Town"),
        r("founding", "info", "Founding a town: the village board (its founding screen)", ""),
        r("spawn", "info", "A town of folk: set a Village Folk spawner, or found one at a village board", ""));

    /** The route for a command line ("village stall rent"): the longest key it starts with. */
    @Nullable
    public static Route route(String line) {
        String l = line.startsWith("/") ? line.substring(1) : line;
        if (l.startsWith("village ")) l = l.substring(8);
        Route best = null;
        for (Route x : ROUTES) {
            if ((l.equals(x.command()) || l.startsWith(x.command() + " ")) && (best == null || x.command().length() > best.command().length())) best = x;
        }
        return best;
    }

    public static boolean operator(String line) {
        String l = line.startsWith("/") ? line.substring(1) : line;
        if (l.startsWith("village ")) l = l.substring(8);
        for (String o : OPERATOR) if (l.equals(o) || l.startsWith(o + " ")) return true;
        return l.startsWith("assistant") || l.startsWith("emotecape");
    }

    static final String[][] TABS = { { "work", "Work" }, { "money", "Money" }, { "capital", "Capital" }, { "business", "Shops & business" },
        { "civic", "Town & civic" }, { "home", "Home & belonging" }, { "info", "The town's books" } };

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        if (c.tab.isEmpty()) c.tab = "work";
        LifePage page = new LifePage("all", "Everything you can do", "Every way in, no commands needed", p.registryAccess());
        for (String[] t : TABS) page.tab(t[0], t[1], c.tab.equals(t[0]));
        Villages.Village v = PlayerLife.here(p);
        if (c.tab.equals("capital")) { capital(level, p, v, page); return page; }
        if (c.tab.equals("money") && v != null) {
            page.heading("Give to the town");
            page.choice("coins", "Give", new String[]{ "1", "5", "10", "20", "50" }, new String[]{ "1 coin", "5 coins", "10 coins", "20 coins", "50 coins" }, "5");
            page.buttons().button("Give it to the treasury", "donate", null);
        }
        page.heading(label(c.tab));
        for (Route x : ROUTES) {
            if (!x.tab().equals(c.tab)) continue;
            String a = x.action();
            LifePage.Row row = page.row(null, x.how(), "", "");
            if (a.isEmpty()) continue;
            String b = a.startsWith("page:") ? "Open the page" : a.startsWith("life:") ? "Open" : a.startsWith("talk:") ? "Ask the nearest folk"
                : a.startsWith("tab:") ? "Go there" : "Do it";
            row.button(b, "go", LifePage.args("a", a));
        }
        return page;
    }

    static String label(String tab) {
        for (String[] t : TABS) if (t[0].equals(tab)) return t[1];
        return tab;
    }

    /** Land titles to buy, shares to buy and sell: all by buttons. */
    private void capital(ServerLevel level, ServerPlayer p, @Nullable Villages.Village v, LifePage page) {
        if (v == null) { page.text("No town near.", 4); return; }
        page.heading("Savings, loans and bonds");
        page.text(FolkCredit.playerLine(p, v.id()), 4);
        page.text("Save, borrow, repay and buy bonds at the bank's counter.", 4);
        page.heading("Land titles");
        page.text(LandTitles.playerLine(p, v.id()), 4);
        List<String> plots = LandTitles.playerList(v.id());
        for (int i = 0; i < Math.min(12, plots.size()); i++) {
            String line = plots.get(i);
            int n = number(line, i + 1);
            page.row(new ItemStack(Items.FILLED_MAP), line, "", "").button("Buy it", "land", LifePage.args("n", n));
        }
        page.buttons().button("Collect what my land has earned", "landcollect", null);
        page.heading("Shares on the bourse");
        page.text(Bourse.line(level, v.id()), 4);
        List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        for (Business b : Enterprise.open(v.id())) { ids.add(b.id); names.add(b.name); }
        if (ids.isEmpty()) { page.text("No firm is on the bourse yet.", 4); return; }
        page.choice("firm", "Of", ids.toArray(new String[0]), names.toArray(new String[0]), ids.get(0));
        page.choice("n", "How many", new String[]{ "1", "5", "10", "25" }, new String[]{ "1 share", "5 shares", "10 shares", "25 shares" }, "1");
        page.buttons().button("Buy", "sharesbuy", null).button("Sell", "sharessell", null);
    }

    /** The plot's number at the start of its line ("3. ..."), else its place in the list. */
    static int number(String line, int dflt) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*#?(\\d+)").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : dflt;
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        CompoundTag f = args.getCompound("f");
        switch (action) {
            case "go" -> {
                String a = args.getString("a");
                if (a.startsWith("page:")) { LifeTown.books(p, a.substring(5)); return "#close"; }
                if (a.startsWith("life:")) { PlayerLife.open(p, a.substring(5), null); return "#open:"; }
                if (a.startsWith("talk:")) return LifeTown.sayToNearest(p, a.substring(5));
                if (a.startsWith("tab:")) { c.tab = a.substring(4); return ""; }
                if (a.startsWith("cmd:")) return PlayerLife.command(p, a.substring(4));
                return null;
            }
            case "donate" -> { return PlayerLife.command(p, "village donate " + ShopPage.parse(f.getString("coins"), 5)); }
            case "land" -> { return v == null ? "!No town." : LandTitles.playerBuys(level, p, v.id(), args.getInt("n")); }
            case "landcollect" -> { return v == null ? "!No town." : TownBonds.playerCollects(p, v.id()); }
            case "sharesbuy" -> { return v == null ? "!No town." : Bourse.playerBuys(level, p, v.id(), f.getString("firm"), ShopPage.parse(f.getString("n"), 1)); }
            case "sharessell" -> { return v == null ? "!No town." : Bourse.playerSells(level, p, v.id(), f.getString("firm"), ShopPage.parse(f.getString("n"), 1), 0); }
            default -> { return null; }
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
