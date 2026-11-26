package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [wpb] The owner's hub, as the Business Core opens it (MyBusinesses, the same pages either way): the status line and the
 * first-time tour on the summary; the decisions in one place (hours and days, a discount day, quality against cost, an
 * advert on the town's notice board, bigger premises, its credit at the bank); its orders and suppliers and where its stock
 * is kept; and its History (the week's takings and profit day by day, the weeks before, its best sellers, its customers and
 * their complaints, the staff's morale, the deputy's record, a timeline of what happened).
 */
final class CoreHub {

    private CoreHub() {}

    static final String[] OPEN = { "6", "7", "8", "9", "10" }, CLOSE = { "16", "17", "18", "19", "20", "22" };
    static final String[] DAYS_V = { "127", "63", "31" }, DAYS_L = { "Every day", "Mon to Sat", "Mon to Fri" };
    static final String[] DISC_V = { "-1", "0", "1", "2", "3", "4", "5", "6" }, DISC_L = { "None", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
    static final int ADVERT = 3;

    static String[] hrs(String[] a) {
        String[] out = new String[a.length];
        for (int i = 0; i < a.length; i++) out[i] = String.format(Locale.ROOT, "%02d:00", Integer.parseInt(a[i]));
        return out;
    }

    /** The summary's top: the status line, and the tour the first time it opens from the Core. */
    static void top(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page, PlayerLife.Ctx c) {
        page.text(BusinessCore.statusLine(level, v.id(), b, true), CoreSites.isOpen(level, v.id(), b, Enterprise.rec(v.id(), b.id)) ? 1 : 3);
        int step = c.args.getInt("tour");
        if (step <= 0) return;
        String[][] tour = {
            { "Your Business Core", "This desk is " + b.name + "'s hub: everything about the business, and every decision, from here. Anyone else who opens it sees its public face." },
            { "Staff and Deputy", "Take on hands and set their pay on Staff. Make one your deputy manager, and on Deputy choose exactly what they may do while you're away." },
            { "Stock and Prices", "Stock shows what runs low and lets you set its targets; Prices has your markup, opening hours, a discount day, quality and an advert." },
            { "Offers, History, Owners", "Offers for the business come to you to accept, refuse or counter. History has its takings, best sellers and its timeline. Owners: partners, a branch, selling." } };
        int i = Math.min(step, tour.length) - 1;
        page.box(tour[i][0] + " (" + (i + 1) + " of " + tour.length + ")", List.of(tour[i][1]), 1);
        page.buttons().button(i + 1 < tour.length ? "Next" : "Done", "tour", LifePage.args("n", i + 1 < tour.length ? step + 1 : 0)).primary()
            .button("Skip the tour", "tour", LifePage.args("n", 0));
    }

    /** The decisions: hours and days, a discount day, quality, an advert, premises, credit. */
    static void decisions(ServerLevel level, Villages.Village v, Business b, LifePage page) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        if (r == null) return;
        page.heading("Opening hours: " + BusinessCore.hoursWords(r));
        int[] h = BusinessCore.hours(r);
        page.choice("open", "Opens", OPEN, hrs(OPEN), Integer.toString(h[0]));
        page.choice("close", "Shuts", CLOSE, hrs(CLOSE), Integer.toString(h[1]));
        page.choice("days", "Days", DAYS_V, DAYS_L, Integer.toString(h[2] == 127 || h[2] == 63 || h[2] == 31 ? h[2] : 127));
        page.buttons().button("Keep these hours", "hours", null).button("Open whenever the staff are in", "allhours", null, BusinessCore.keepsHours(r), "It does already");
        page.text("Shut, it sells nothing: fewer hours save nothing on wages, but a deputy who opens late costs less when the doors open late anyway.", 4);
        page.heading("A discount day, and its quality");
        page.choice("disc", "A tenth off on", DISC_V, DISC_L, Integer.toString(r.contains("discountDay") ? r.getInt("discountDay") : -1));
        page.buttons().button("Set the discount day", "discount", null);
        String q = r.getString("quality").isEmpty() ? "standard" : r.getString("quality");
        page.buttons().button((q.equals("cheap") ? "● " : "") + "Cheap", "quality", LifePage.args("q", "cheap"), !q.equals("cheap"), "Chosen")
            .button((q.equals("standard") ? "● " : "") + "Standard", "quality", LifePage.args("q", "standard"), !q.equals("standard"), "Chosen")
            .button((q.equals("fine") ? "● " : "") + "Fine", "quality", LifePage.args("q", "fine"), !q.equals("fine"), "Chosen");
        page.text(q.equals("fine") ? "Fine: its prices about 8% dearer, and its name in town rises a little each day (customers come back for it)."
            : q.equals("cheap") ? "Cheap: its prices about 7% under, more buyers on price, and its name slips a little each day."
            : "Standard: priced as the trade goes. Fine is dearer and builds its name; cheap sells on price and wears it down.", 4);
        page.heading("Getting it known");
        long day = Businesses.today(level);
        boolean on = r.getLong("advertUntil") >= day;
        page.buttons().button(on ? "Advertised till day " + (r.getLong("advertUntil") + 1) : "An advert on the notice board (" + ADVERT + "c, a week)", "advert", null, !on && b.till >= ADVERT * 100L,
            on ? "It's on the board already" : "Its till needs " + ADVERT + "c");
        page.heading("Growing");
        page.text("Its trade credit at the bank: up to " + Businesses.pence(Businesses.creditLimit(b, day)) + (b.account < 0 ? ", " + Businesses.pence(-b.account) + " of it drawn" : ", none drawn")
            + ". The bank lends against its books: good weeks raise it, losses cut it.", 4);
        page.buttons().button("Bigger premises (the property office)", "premises", null).button("Open a branch", "tab:more", null);
    }

    /** What runs low, its orders and suppliers, and where its stock is kept. */
    static void suppliers(ServerLevel level, Villages.Village v, Business b, LifePage page) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        List<String> low = BusinessCore.low(v.id(), b, r);
        if (!low.isEmpty()) page.text("Running low: " + String.join(", ", low) + ".", 2);
        page.heading("Its orders and suppliers");
        if (b.orders.isEmpty()) page.text("No orders yet.", 4);
        for (int i = b.orders.size() - 1; i >= 0 && i >= b.orders.size() - 5; i--) {
            Business.Order o = b.orders.get(i);
            page.text("Day " + (o.day() + 1) + ": " + o.qty() + " " + Stockroom.plural(o.item()).toLowerCase(Locale.ROOT) + " from " + o.supplier() + ", " + Businesses.pence(o.total())
                + (o.discount() > 0 ? " (" + Businesses.pence(o.discount()) + " off)" : ""), 4);
        }
        page.buttons().button("Show me where its stock is kept", "showstock", null);
    }

    /** Its History. */
    static void history(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page) {
        long day = Businesses.today(level);
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        page.heading("This week, day by day");
        long most = 1;
        for (int i = 6; i >= 0; i--) most = Math.max(most, b.last(day - i, 1).revenue);
        for (int i = 6; i >= 0; i--) {
            Business.Day d = b.last(day - i, 1);
            page.bar("Day " + (day - i + 1), d.revenue / (double) most, "sold " + Businesses.pence(d.revenue) + " · " + (d.net() >= 0 ? "profit " : "loss ") + Businesses.pence(Math.abs(d.net())));
        }
        List<String> weeks = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            Business.Day d = b.last(day - 7L * w, 7);
            weeks.add((w == 0 ? "This week" : w == 1 ? "Last week" : w + " weeks ago") + ": sales " + Businesses.pence(d.revenue) + ", " + (d.net() >= 0 ? "profit " : "loss ") + Businesses.pence(Math.abs(d.net())));
        }
        page.box("The last four weeks", weeks, 4);
        page.buttons().button("Charts and data", "dataof", LifePage.args("firm", b.id));
        page.heading("Its best sellers (four weeks)");
        Business.Day m = b.last(day, 28);
        List<Map.Entry<String, Business.Line>> lines = new ArrayList<>(m.lines.entrySet());
        lines.removeIf(e -> e.getValue().units <= 0);
        lines.sort(Comparator.comparingLong((Map.Entry<String, Business.Line> e) -> e.getValue().revenue).reversed());
        if (lines.isEmpty()) page.text("Nothing sold yet.", 4);
        for (int i = 0; i < Math.min(5, lines.size()); i++) {
            Map.Entry<String, Business.Line> e = lines.get(i);
            ItemStack one = Stockroom.sampleOf(e.getKey());
            page.row(one, Stockroom.plural(e.getKey()), e.getValue().units + " sold" + (e.getValue().missed > 0 ? ", " + e.getValue().missed + " missed for want of stock" : ""), Businesses.pence(e.getValue().revenue));
        }
        page.heading("Its customers");
        Business.Day w = b.last(day, 7);
        long missed = 0;
        for (Business.Line l : w.lines.values()) missed += l.missed;
        int complaints = 0;
        if (r != null) for (CompoundTag d : Deputy.log(r, "depLog", day - 7)) if (d.getBoolean("complaint")) complaints++;
        int sat = satisfaction(w, missed, complaints, r);
        List<String> cust = new ArrayList<>();
        cust.add("This week " + w.customers + " customers, " + w.repeats + " of them back again; " + missed + " sales missed for want of stock" + (complaints > 0 ? "; " + complaints + " complained" : "") + ".");
        cust.add("Satisfaction about " + sat + "/100: " + (sat >= 75 ? "they like it" : sat >= 55 ? "they put up with it" : "they're going elsewhere") + ". Its name in town: "
            + String.format(Locale.ROOT, "%.0f", Sellers.reputation(v.id(), b) * 100) + "/100.");
        page.box("Customers", cust, sat >= 55 ? 4 : 2);
        page.heading("The staff's morale");
        List<VillageFolkEntity> hs = Enterprise.hands(v.id(), b);
        if (hs.isEmpty()) page.text("No folk staff here just now.", 4);
        int morale = 0;
        for (VillageFolkEntity f : hs) {
            LabourBook.Contract k = LabourMarket.contract(f);
            int mo = morale(r, k);
            morale += mo;
            page.text(f.displayNameCap() + ": " + (mo >= 70 ? "content" : mo >= 50 ? "getting by" : "unhappy") + (k == null ? "" : k.m >= 1.08 ? " (well paid)" : k.m < 0.97 ? " (feels underpaid)" : ""), mo >= 50 ? 4 : 2);
        }
        if (!hs.isEmpty()) page.text("On the whole: " + (morale / hs.size() >= 70 ? "a contented shop" : morale / hs.size() >= 50 ? "no complaints worth the name" : "an unhappy shop: a raise, or a different deputy, may help") + ".", 4);
        if (r != null && r.hasUUID("manager")) {
            int rt = Deputy.rating(r);
            page.text("The deputy, " + Deputy.name(v.id(), r) + ": " + (rt < 0 ? "not yet tried" : rt + "/100, " + Deputy.ratingWord(rt)) + " (Deputy tab).", 4);
        }
        page.heading("What happened");
        List<String> tl = r == null ? List.of() : BusinessCore.timeline(r, 12);
        if (tl.isEmpty()) page.text("Nothing of note yet.", 4);
        for (String s : tl) page.text(s, 4);
    }

    /** Customers' satisfaction: sales missed, complaints, and how its prices sit with them (0-100). */
    static int satisfaction(Business.Day w, long missed, int complaints, CompoundTag r) {
        double s = 80;
        long wanted = w.trans + missed;
        if (wanted > 0) s -= 40.0 * missed / wanted;
        s -= complaints * 5;
        if (w.customers > 0) s += 15.0 * w.repeats / w.customers;
        if (r != null && r.getString("quality").equals("fine")) s += 5;
        if (r != null && r.getString("quality").equals("cheap")) s -= 5;
        return (int) Math.max(0, Math.min(100, Math.round(s)));
    }

    static int morale(CompoundTag r, LabourBook.Contract k) {
        int m = 65;
        if (k != null) m += (int) Math.round((k.m - 1) * 120);
        if (r != null) {
            for (CompoundTag d : Deputy.log(r, "depLog", Long.MIN_VALUE)) if (d.contains("walkout")) m -= 8;
            if (r.getCompound("depSnap").getInt("marks") != 0 && (r.getCompound("depSnap").getInt("marks") & 1 << Temperament.Mark.QUARRELSOME.ordinal()) != 0) m -= 10;
        }
        return Math.max(0, Math.min(100, m));
    }

    static String act(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, String action, CompoundTag args, PlayerLife.Ctx c) {
        if (action.equals("tour")) { c.args.putInt("tour", args.getInt("n")); return ""; }
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        if (r == null) return "!Its record is missing.";
        if (action.equals("showstock")) {
            List<BlockPos> chests = Villages.storeChests(level, v.id());
            BlockPos at = chests.isEmpty() ? BusinessCore.siteOf(level, v, b) : chests.get(0);
            WorkWay.showMe(p, at, "its stock (in the stores, in its name)");
            return "The arrow points to where its stock is kept: the town's stores hold it in its name till its hands fetch it.";
        }
        if (action.equals("premises")) { PropertyOffice.openOn(p, "market", null, "buy"); return "#open:"; }
        if (!Partners.canDecide(v.id(), b, p.getUUID())) return "!" + Partners.whyNot(v.id(), b);
        CompoundTag f = args.getCompound("f");
        long day = Businesses.today(level);
        switch (action) {
            case "hours" -> {
                int open = ShopPage.parse(f.getString("open"), 7), close = ShopPage.parse(f.getString("close"), 19), days = ShopPage.parse(f.getString("days"), 127);
                if (close <= open) return "!It must shut after it opens.";
                BusinessCore.setHours(r, open, close, days);
                b.note(day, "its hours set: " + BusinessCore.hoursWords(r));
                return b.name + " keeps " + BusinessCore.hoursWords(r) + ".";
            }
            case "allhours" -> { r.remove("hours"); Accounts.dirty(); return b.name + " opens whenever its staff are in."; }
            case "discount" -> {
                int d = ShopPage.parse(f.getString("disc"), -1);
                if (d < 0) r.remove("discountDay"); else r.putInt("discountDay", d);
                Accounts.dirty();
                return d < 0 ? "No discount day." : "A tenth off every " + CorePage.dayName(d) + ".";
            }
            case "quality" -> {
                String q = args.getString("q");
                if (q.equals("standard")) r.remove("quality"); else r.putString("quality", q.equals("fine") ? "fine" : "cheap");
                Accounts.dirty();
                b.note(day, "its quality: " + q);
                BusinessCore.event(r, day, "went " + q + " on quality");
                return "Quality: " + q + ".";
            }
            case "advert" -> {
                if (r.getLong("advertUntil") >= day) return "!It's on the board already.";
                if (b.till < ADVERT * 100L) return "!Its till needs " + ADVERT + "c.";
                b.till -= ADVERT * 100L;
                b.today(day).other += ADVERT * 100L;
                b.allTime.other += ADVERT * 100L;
                Businesses.toTreasury(v.id(), ADVERT * 100L);
                r.putLong("advertUntil", day + 7);
                Villages.tell(v.id(), level.getDayTime() / 24000L, "a notice on the board: " + b.name + ", " + BusinessCore.hoursWords(r));
                BusinessCore.event(r, day, "advertised on the notice board");
                Accounts.dirty();
                return "On the notice board for a week (" + ADVERT + "c from its till): its name in town rises while it's up.";
            }
            default -> { return null; }
        }
    }
}
