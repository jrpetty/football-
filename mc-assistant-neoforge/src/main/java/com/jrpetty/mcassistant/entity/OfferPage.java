package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * [wpb] Making an offer for a business, or a share in one, and answering offers (BusinessOffers): the valuation guide, the
 * price and terms, the coin into escrow; your offers made, with the owners' answers and counters; and, on a business of
 * yours, the offers for it (MyBusinesses' Offers tab calls {@link #incoming}).
 */
public final class OfferPage implements PlayerLife.Page {

    static final String[] SHARE_V = { "10000", "5000", "2500", "1000" }, SHARE_L = { "The whole business", "A half share", "A quarter share", "A tenth share" };
    static final double[] PRICE_K = { 0.8, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0 };
    static final String[] PRICE_W = { "under fair value", "fair value", "a quarter over", "half again", "twice fair value", "two and a half times", "three times" };

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        LifePage page = new LifePage("offer", "Make an offer", v == null ? "No town near" : "In " + Villages.name(v.id()), p.registryAccess());
        if (v == null) return page;
        Business b = Businesses.of(v.id(), c.args.getString("firm"));
        mine(level, p, page, c);
        if (b == null) {
            if (BusinessOffers.byBuyer(p.getUUID()).isEmpty()) page.text("Choose a business to make an offer for: its Business Core, or the Businesses page.", 4);
            return page;
        }
        int parts = c.args.contains("parts") ? c.args.getInt("parts") : Business.WHOLE;
        if (parts <= 0) parts = Business.WHOLE;
        BusinessOffers.Valuation val = BusinessOffers.value(level, v.id(), b);
        page.heading("An offer for " + b.name);
        Business.Day wk = b.last(Businesses.today(level), 7);
        page.text("Owned by " + b.ownersWords() + "; trading since day " + (b.opened + 1) + ". This week: sales " + Businesses.pence(wk.revenue) + ", "
            + (wk.net() >= 0 ? "a profit of " : "a loss of ") + Businesses.pence(Math.abs(wk.net())) + ".", 4);
        page.box("What it's worth", List.of(
            "Book value " + Businesses.pence(val.book()) + ": its stock, till and kit, less what it owes.",
            "Its last four weeks: " + (val.profit28() >= 0 ? "a profit of " : "a loss of ") + Businesses.pence(Math.abs(val.profit28())) + ".",
            "Goodwill " + Businesses.pence(val.goodwill()) + " (eight weeks of its profit, if it makes one).",
            "Fair value about " + Businesses.pence(val.fair()) + ". Owners usually ask more: folk owners rarely sell near fair value, a thriving or family firm far over it, and a few won't sell at all."), 1);
        String no = BusinessOffers.whyNot(level, v.id(), b, p.getUUID(), parts);
        if (!no.isEmpty()) { page.text("Not possible: " + no + ".", 3); return page; }
        page.choice("parts", "For", SHARE_V, SHARE_L, Integer.toString(parts));
        double share = parts / (double) Business.WHOLE;
        String[] pv = new String[PRICE_K.length], pl = new String[PRICE_K.length];
        for (int i = 0; i < PRICE_K.length; i++) {
            long coins = Math.max(1, Math.round(val.fair() * share * PRICE_K[i] / 100.0));
            pv[i] = Long.toString(coins);
            pl[i] = coins + "c (" + PRICE_W[i] + ")";
        }
        page.choice("price", "Offer", pv, pl, pv[3]);
        page.field("own", "Your own price", "", 6);
        page.choice("staff", "The staff", new String[]{ "1", "0" }, new String[]{ "Kept on", "Mine to change" }, "1");
        page.choice("name", "The name", new String[]{ "1", "0" }, new String[]{ "Kept", "Mine to change" }, "1");
        if (parts >= Business.WHOLE) page.choice("stays", "The owner", new String[]{ "0", "1" }, new String[]{ "Leaves", "Stays as manager" }, "0");
        page.choice("days", "It stands for", new String[]{ "1", "3", "7" }, new String[]{ "A day", "Three days", "A week" }, "3");
        page.buttons().button("Make the offer", "make", null).primary();
        page.text("The price goes into escrow at the hall now and comes back if the offer is refused, withdrawn, or lapses. You carry " + Market.coinsHeld(p) + "c.", 4);
        return page;
    }

    /** Your offers made: where each stands, and what you can do with it. */
    static void mine(ServerLevel level, ServerPlayer p, LifePage page, PlayerLife.Ctx c) {
        List<BusinessOffers.Offer> mine = BusinessOffers.byBuyer(p.getUUID());
        if (mine.isEmpty()) return;
        page.heading("Offers you've made");
        for (BusinessOffers.Offer o : mine) {
            String state = switch (o.state) {
                case OPEN -> o.decideAt > 0 ? "with the owners, who are thinking it over" : "with the owners";
                case COUNTERED -> "countered at " + o.counter + "c";
                case ACCEPTED -> "accepted: it's yours";
                case REFUSED -> "refused";
                case WITHDRAWN -> "withdrawn";
                case LAPSED -> "lapsed";
            };
            LifePage.Row row = page.row(null, o.price + "c for " + o.what(), state + (o.live() ? " · stands till day " + (o.expires + 1) : ""), o.live() ? o.escrow + "c held" : "");
            if (o.state == BusinessOffers.State.COUNTERED) row.button("Take " + o.counter + "c", "take", LifePage.args("id", o.id), Market.coinsHeld(p) + o.escrow >= o.counter, "You need " + (o.counter - o.escrow) + "c more")
                .button("Meet halfway", "meet", LifePage.args("id", o.id));
            if (o.live()) row.button("Withdraw", "withdraw", LifePage.args("id", o.id));
            if (!o.reason.isEmpty() && o.reason.contains("\"")) page.text("  " + o.reason, o.state == BusinessOffers.State.REFUSED ? 3 : 4);
        }
    }

    /** On a business of yours: the offers for it, and the answers (MyBusinesses' Offers tab). */
    static void incoming(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page) {
        List<BusinessOffers.Offer> in = BusinessOffers.forFirm(v.id(), b.id);
        page.heading("Offers for " + b.name);
        BusinessOffers.Valuation val = BusinessOffers.value(level, v.id(), b);
        page.text("Fair value about " + Businesses.pence(val.fair()) + " (book " + Businesses.pence(val.book()) + ", goodwill " + Businesses.pence(val.goodwill()) + ").", 4);
        if (in.isEmpty()) page.text("No offers just now. List it for sale (Owners & sale) and buyers come; a thriving business gets the odd offer anyway.", 4);
        boolean decide = Partners.canDecide(v.id(), b, p.getUUID());
        String no = decide ? "" : Partners.whyNot(v.id(), b);
        for (BusinessOffers.Offer o : in) {
            String votes = "";
            if (b.owners.size() > 1) {
                List<String> vs = new ArrayList<>();
                for (Business.Stake s : b.owners) if (s.who() != null && o.votes.containsKey(s.who())) vs.add(s.name() + (o.votes.get(s.who()) ? " yes" : " no"));
                votes = vs.isEmpty() ? " · no answers yet" : " · " + String.join(", ", vs);
            }
            LifePage.Row row = page.row(null, o.buyerName + (o.folkBuyer ? "" : " (a player)") + ": " + o.price + "c for " + (o.parts >= Business.WHOLE ? "the whole" : o.parts * 100 / Business.WHOLE + "%"),
                "till day " + (o.expires + 1) + votes, "");
            page.text("  Terms: " + BusinessOffers.terms(o).replace(" (", "").replace(")", "") + ". " + o.escrow + "c held in escrow.", 4);
            if (o.state == BusinessOffers.State.COUNTERED) { page.text("  You countered at " + o.counter + "c: it's with them.", 4); continue; }
            row.button("Accept", "oaccept", LifePage.args("id", o.id)).button("Refuse", "orefuse", LifePage.args("id", o.id))
                .button("Counter +25%", "ocounter", LifePage.args("id", o.id, "k", 125), decide, no).button("+50%", "ocounter", LifePage.args("id", o.id, "k", 150), decide, no);
        }
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town here.";
        CompoundTag f = args.getCompound("f");
        if (action.equals("make")) {
            Business b = Businesses.of(v.id(), c.args.getString("firm"));
            if (b == null) return "!No such business.";
            int parts = ShopPage.parse(f.getString("parts"), Business.WHOLE);
            if (parts != c.args.getInt("parts") && c.args.contains("parts") || !c.args.contains("parts") && parts != Business.WHOLE) {
                c.args.putInt("parts", parts);                              // a different share: its prices again
                return "!The prices are for " + (parts >= Business.WHOLE ? "the whole" : parts * 100 / Business.WHOLE + "%") + " now: choose again and press Make the offer.";
            }
            int price = ShopPage.parse(f.getString("own").trim(), 0);
            if (price <= 0) price = ShopPage.parse(f.getString("price"), 0);
            return BusinessOffers.make(level, p, v.id(), b, parts, price, f.getString("staff").equals("1"), f.getString("name").equals("1"), f.getString("stays").equals("1"),
                ShopPage.parse(f.getString("days"), 3));
        }
        BusinessOffers.Offer o = BusinessOffers.get(args.getInt("id"));
        if (o == null) return "!That offer has gone.";
        return switch (action) {
            case "take" -> BusinessOffers.takeCounter(level, p, o);
            case "meet" -> BusinessOffers.counter(level, p, o, (o.price + o.counter + 1) / 2);
            case "withdraw" -> BusinessOffers.withdraw(level, p, o);
            default -> null;
        };
    }

    /** The owner's answers, from MyBusinesses. */
    static String answer(ServerLevel level, ServerPlayer p, String action, CompoundTag args) {
        BusinessOffers.Offer o = BusinessOffers.get(args.getInt("id"));
        if (o == null) return "!That offer has gone.";
        return switch (action) {
            case "oaccept" -> BusinessOffers.accept(level, p, o);
            case "orefuse" -> BusinessOffers.refuse(level, p, o);
            case "ocounter" -> BusinessOffers.counter(level, p, o, (int) Math.round(o.price * args.getInt("k") / 100.0));
            default -> null;
        };
    }
}
