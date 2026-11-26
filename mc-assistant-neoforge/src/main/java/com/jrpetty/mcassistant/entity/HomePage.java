package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpc] Home (page "home"): the houses a player owns here and the home it rents.
 * <ul>
 * <li><b>Your houses.</b> Each with who lives there and on what rent, its rates, and buttons: let it (a rent chosen), give
 *     the tenants notice (three days, then they move on), sell it (the property office's market: each town-bought house is
 *     put on its register), a mortgage, a copy of the deed. A house the player built itself is registered here too, by
 *     standing in it.</li>
 * <li><b>Renting.</b> The town's empty houses and flats, and other players' houses to let, each with its rent and a Show
 *     me; Rent it. The tenancy (Tenancy) holds the house, sets the player's waking place in it, and the rent goes out each
 *     morning by standing order. Giving notice is a button.</li>
 * <li><b>Rates</b> (HomeRates here): a hundredth of an owned house's price a week, a citizen half that; out of the account by
 *     standing order, unpaid on the debts ledger.</li>
 * </ul>
 */
public final class HomePage implements PlayerLife.Page {

    /** Rates a week, in the thousand of a house's price: a resident's and a citizen's. */
    static final int RATES_PERMILLE = 10, CITIZEN_PERMILLE = 5;

    static CompoundTag rates(UUID village) {
        return PropertyBook.town(village, "homerates");
    }

    /** A week's rates on a house. */
    static int ratesOn(UUID village, Homes.Home h) {
        int price = h.price > 0 ? h.price : Homes.price(village, h);
        int pm = h.landlord != null && Citizens.is(village, h.landlord) ? CITIZEN_PERMILLE : RATES_PERMILLE;
        return Math.max(1, price * pm / 1000);
    }

    /** Each morning: a week's rates on each player's house that has come due; every player's house on the property register. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag r = rates(id);
        for (Homes.Home h : Homes.homes(id).values()) {
            if (h.tenure != Homes.Tenure.PLAYER || h.landlord == null) continue;
            String k = Long.toString(h.anchor.asLong());
            if (!r.contains(k)) { r.putLong(k, day + 7); continue; }
            if (r.getLong(k) > day) continue;
            r.putLong(k, r.getLong(k) + 7);
            StandingOrders.bill(level, id, h.landlord, h.landlordName, ratesOn(id, h), PlayerFinance.Cat.TAXES, "rates on " + Homes.address(id, v, h), "rates", "the town", "home:" + k);
            onMarket(level, id, h);
        }
        PropertyBook.dirty();
    }

    /** A player's house on the property register (so the office can sell or let it), once. */
    static void onMarket(ServerLevel level, UUID village, Homes.Home h) {
        if (h.landlord == null || PropertyMarket.get(village, PropertyMarket.keyAt(h.anchor)) != null) return;
        Direction facing = Direction.NORTH;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.anchor().equals(h.anchor)) facing = b.facing();
        int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(h.structure);
        Villages.Village v = Villages.get(village);
        String name = h.landlordName + "'s house " + (v == null ? "" : PropertyMarket.where(village, h.anchor));
        PropertyMarket.register(level, village, h.landlord, h.landlordName, name, "home", h.anchor, facing, h.structure, half[0], half[1]);
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) {
            LifePage page = new LifePage("home", "Home", "No town near", p.registryAccess());
            page.text("Homes are a town's. Walk into one and open this again.", 4);
            return page;
        }
        UUID id = v.id(), me = p.getUUID();
        Homes.enrol(id);
        Tenancy.load(id);
        List<Homes.Home> mine = new ArrayList<>();
        for (Homes.Home h : Homes.homes(id).values()) if (h.tenure == Homes.Tenure.PLAYER && me.equals(h.landlord)) mine.add(h);
        BlockPos rented = Tenancy.home(id, me);
        LifePage page = new LifePage("home", "Home in " + Villages.name(id), mine.isEmpty() ? rented != null ? "You rent a home here" : "No home of your own here yet"
            : "You own " + mine.size() + (mine.size() == 1 ? " house" : " houses") + " here", p.registryAccess());
        if (c.tab.isEmpty()) c.tab = mine.isEmpty() && rented == null ? "rent" : "mine";
        page.tab("mine", "Your home", c.tab.equals("mine")).tab("rent", "Rent a home", c.tab.equals("rent")).tab("buy", "Buy a home", c.tab.equals("buy"));
        PropertyBook.confirmBox(page, c);
        switch (c.tab) {
            case "rent" -> rentTab(level, p, v, c, page, rented);
            case "buy" -> buyTab(level, p, v, page);
            default -> mineTab(level, p, v, c, page, mine, rented);
        }
        page.buttons().button("Your land", "land", null).button("Mortgages", "mortgage", null).button("Your will", "will", null).button("Back", "life", null);
        return page;
    }

    void mineTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, List<Homes.Home> mine, @Nullable BlockPos rented) {
        UUID id = v.id(), me = p.getUUID();
        PropertyBook.hint(p, page, "home.mine", "Your houses and your tenancy. Let a house to a household for rent, give tenants notice, or sell it. "
            + "Rates come out of your account each week.");
        if (rented != null) {
            Homes.Home h = Homes.homes(id).get(rented.asLong());
            CompoundTag t = Tenancy.of(id, me);
            page.heading("Where you live");
            page.row(new ItemStack(Items.RED_BED), h == null ? "Your rented home" : capital(Homes.address(id, v, h)), "Rented from " + (t != null && t.hasUUID("landlord") ? t.getString("landlordName") : "the town")
                    + " at " + (t == null ? 0 : t.getInt("rent")) + "c a day, out of your account each morning" + (t != null && t.getLong("noticeEnds") > 0 ? "; notice given: it ends day " + t.getLong("noticeEnds") : ""), "")
                .button("Show me", "show", LifePage.args("at", rented.asLong()))
                .button("Give notice", "leave", null, t != null && t.getLong("noticeEnds") <= 0, "Notice is given already");
        }
        if (mine.isEmpty()) {
            if (rented == null) page.text("You have no home here. Rent one on the next tab, or buy one (outright or with a mortgage).", 4);
            page.buttons().button("Register a house I built", "register", null);
            return;
        }
        page.heading("Your houses");
        int due = Homes.parse(Ledger.note(id, "rentdue/" + me));
        for (Homes.Home h : mine) {
            List<VillageFolkEntity> in = Homes.loadedMembers(id, h);
            String who = h.members.isEmpty() ? (h.toLet ? "Empty, to let at " + h.rent + "c a day" : "Empty: yours alone") : (in.isEmpty() ? h.members.size() + " tenants" : names(in)) + " at " + h.rent + "c a day";
            Bank.Loan l = Bank.playerLoan(id, me);
            boolean mortgaged = l != null && l.anchor == h.anchor.asLong();
            page.row(new ItemStack(Items.OAK_DOOR), capital(Homes.address(id, v, h)), who + ". Rates " + ratesOn(id, h) + "c a week" + (mortgaged ? "; mortgaged, " + l.owed() + " owed" : ""), "worth " + Homes.price(id, h))
                .button("Show me", "show", LifePage.args("at", h.anchor.asLong()))
                .button("Notice to tenants", "evict", LifePage.args("at", h.anchor.asLong()), !h.members.isEmpty(), "Nobody lives there")
                .button("Sell", "sell", LifePage.args("at", h.anchor.asLong()));
        }
        page.choice("letrent", "Let for", new String[]{ "2", "3", "4", "5", "6", "8" }, new String[]{ "2c a day", "3c a day", "4c a day", "5c a day", "6c a day", "8c a day" }, "3");
        page.buttons().button("Let my house", "let", null).button("Collect rent (" + due + "c)", "collect", null, due > 0, "No rent waiting").button("Register a house I built", "register", null)
            .button("Copies of my deeds", "deeds", null);
    }

    static String names(List<VillageFolkEntity> in) {
        List<String> n = new ArrayList<>();
        for (VillageFolkEntity f : in) n.add(f.displayNameCap());
        return String.join(", ", n);
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    void rentTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, @Nullable BlockPos rented) {
        UUID id = v.id();
        PropertyBook.hint(p, page, "home.rent", "Renting is the easy start: a room of your own, rent each morning from your account, three days' notice either way. "
            + "Your bed there is where you wake.");
        if (rented != null) page.text("You rent a home already. Give notice on the Your home tab to move.", 2);
        int shown = 0;
        for (Homes.Home h : Homes.homes(id).values()) {
            boolean town = h.members.isEmpty() && h.tenure != Homes.Tenure.PLAYER && !Homes.seat(h) && !Tenancy.held(h.anchor);
            boolean player = h.members.isEmpty() && h.tenure == Homes.Tenure.PLAYER && h.toLet && !p.getUUID().equals(h.landlord) && !Tenancy.held(h.anchor);
            if (!town && !player) continue;
            if (shown++ >= 8) break;
            int rent = player ? h.rent : Homes.rent(id, h);
            page.row(new ItemStack(Flats.isFlat(h) ? Items.LADDER : Items.OAK_DOOR), capital(Homes.address(id, v, h)), (Flats.isFlat(h) ? "A flat" : "A house") + ", "
                    + Homes.bedsIn(level, id, h).size() + " beds; let by " + (player ? h.landlordName : "the town"), rent + "c/day")
                .button("Rent it", "rent", LifePage.args("at", h.anchor.asLong()), rented == null, "You rent a home here already")
                .button("Show me", "show", LifePage.args("at", h.anchor.asLong()));
        }
        if (shown == 0) page.text("No house or flat is empty to let just now. The inn has rooms by the night; the builders put up more as the town grows.", 4);
    }

    void buyTab(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page) {
        UUID id = v.id();
        PropertyBook.hint(p, page, "home.buy", "Buy one of the town's empty houses outright, or with the bank's mortgage, or have one built on a lot of your choosing.");
        int shown = 0;
        for (Homes.Home h : Mortgages.forSale(id)) {
            if (shown++ >= 6) break;
            int price = Mortgages.priceFor(id, p.getUUID(), h);
            page.row(new ItemStack(Items.OAK_DOOR), capital(Homes.address(id, v, h)), Homes.bedsIn(level, id, h).size() + " beds", price + "c")
                .button("Buy outright", "buy", LifePage.args("at", h.anchor.asLong()), Market.coinsHeld(p) >= price, "You carry " + Market.coinsHeld(p) + " of " + price + " (stand in it to buy)")
                .button("Show me", "show", LifePage.args("at", h.anchor.asLong()));
        }
        if (shown == 0) page.text("No house of the town's is for sale just now.", 4);
        page.buttons().button("With a mortgage", "mortgage", null).button("Have one built", "builder", null).primary();
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        String common = PropertyBook.common(p, c, action, args, this);
        if (common != null) return common;
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town near.";
        UUID id = v.id(), me = p.getUUID();
        Homes.Home h = args.contains("at") ? Homes.homes(id).get(args.getLong("at")) : null;
        switch (action) {
            case "show" -> { return PropertyBook.showMe(p, BlockPos.of(args.getLong("at")), h == null ? "The house" : capital(Homes.address(id, v, h))); }
            case "rent" -> {
                if (h == null) return "!That house isn't on the books.";
                int rent = h.tenure == Homes.Tenure.PLAYER ? h.rent : Homes.rent(id, h);
                if (!args.getBoolean("sure") && rent * 7 > 20) {
                    return PropertyBook.ask(c, "rent", LifePage.args("at", args.getLong("at")), "Rent " + Homes.address(id, v, h) + " at " + rent + "c a day (about " + rent * 7
                        + " a week), the first day now and then each morning from your account? Three days' notice either way.", "Rent it");
                }
                String s = Tenancy.start(level, id, me, Belonging.called(p), h.anchor, rent, false);
                if (!s.isEmpty()) return s;
                c.tab = "mine";
                return "You rent " + Homes.address(id, v, h) + " at " + rent + "c a day. You'll wake in its bed; the rent goes out each morning. Next: find work to cover it (the jobs board).";
            }
            case "leave" -> { return Tenancy.notice(level, id, me, true); }
            case "evict" -> {
                if (h == null || !me.equals(h.landlord)) return "!That house isn't yours.";
                if (h.members.isEmpty()) return "!Nobody lives there.";
                if (!args.getBoolean("sure")) return PropertyBook.ask(c, "evict", LifePage.args("at", args.getLong("at")), "Give the household at " + Homes.address(id, v, h)
                    + " notice? They have three days to find somewhere, and the rent stops.", "Give notice");
                CompoundTag n = PropertyBook.town(id, "evictions");
                n.putLong(Long.toString(h.anchor.asLong()), PropertyBook.day(level) + Tenancy.NOTICE);
                PropertyBook.dirty();
                return "Notice given: the household moves on by day " + (PropertyBook.day(level) + Tenancy.NOTICE) + ". The house is yours again after that.";
            }
            case "sell" -> {
                if (h == null || !me.equals(h.landlord)) return "!That house isn't yours.";
                onMarket(level, id, h);
                return PropertyDesk.open(p, "sell", "home");
            }
            case "let" -> { return Homes.playerLets(level, p, ShopPage.parse(args.getCompound("f").getString("letrent"), 3)); }
            case "collect" -> { return Homes.playerCollects(level, p); }
            case "buy" -> {
                if (h == null) return "!That house isn't on the books.";
                if (Homes.homeAt(id, p.blockPosition()) != h) return PropertyBook.showMe(p, h.anchor, capital(Homes.address(id, v, h))) + " Stand inside it and press Buy outright again.";
                String s = Homes.playerBuys(level, p);
                if (s.startsWith("It's yours")) { onMarket(level, id, h); PropertyItems.deed(p, id, "house", Long.toString(h.anchor.asLong()), Homes.address(id, v, h)); FirstWeek.check(p); c.tab = "mine"; return s; }
                return "!" + s;
            }
            case "register" -> {
                PropertyOffice.openOn(p, "property", null, "register");
                return "#open:";
            }
            case "deeds" -> {
                int n = 0;
                for (Homes.Home x : Homes.homes(id).values()) if (x.tenure == Homes.Tenure.PLAYER && me.equals(x.landlord)) { PropertyItems.deed(p, id, "house", Long.toString(x.anchor.asLong()), Homes.address(id, v, x)); n++; }
                return n == 0 ? "!You own no house here." : n + (n == 1 ? " deed" : " deeds") + " in your pack.";
            }
            case "land" -> { PlayerLife.open(p, "land", null); return "#open:"; }
            case "mortgage" -> { PlayerLife.open(p, "mortgage", null); return "#open:"; }
            case "will" -> { PlayerLife.open(p, "will", null); return "#open:"; }
            case "builder" -> { PlayerLife.open(p, "build", LifePage.args("for", "home", "back", "home")); return "#open:"; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            default -> { return null; }
        }
    }

    /** Each morning: households given notice move on when it runs out. */
    static void evictions(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag n = PropertyBook.town(id, "evictions");
        for (String k : new ArrayList<>(n.getAllKeys())) {
            if (n.getLong(k) > day) continue;
            n.remove(k);
            Homes.Home h = Homes.homes(id).get(Long.parseLong(k));
            if (h == null) continue;
            List<UUID> out = new ArrayList<>(h.members);
            h.members.clear();
            h.toLet = false;
            Homes.save(id, h);
            for (UUID m : out) {
                VillageFolkEntity f = Homes.loaded(id, m);
                if (f != null) f.forgetBed();
            }
            if (h.landlord != null) PropertyBook.tell(level, h.landlord, "Your tenants at " + Homes.address(id, v, h) + " have moved on. The house is yours alone.");
            Villages.tell(id, day, "a household moved out of " + Homes.address(id, v, h) + " on its landlord's notice");
        }
        PropertyBook.dirty();
    }
}
