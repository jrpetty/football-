package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [wpb] The Deputy tab of a business (MyBusinesses, and the Business Core's hub): who is in charge now; the deputy, its
 * character and its record; its authority (on or off, the mode, a switch a task); the owner's standing orders while away;
 * a raise asked for; money missing from the till, with sacking or the watch; the reports kept from the owner's absences.
 */
final class DeputyPage {

    private DeputyPage() {}

    static final String[] LO = { "5", "10", "15", "20", "25", "30" }, HI = { "30", "40", "50", "60", "80", "100" },
        SPEND = { "5", "10", "20", "40", "80" }, FLOOR = { "0", "1", "3", "5", "10" }, RAISE = { "0", "5", "10", "15", "20" };

    static String[] pct(String[] a) {
        String[] out = new String[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] + "%";
        return out;
    }

    static String[] coins(String[] a) {
        String[] out = new String[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] + "c";
        return out;
    }

    /** A folk's character as a deputy would show it: its marks, and an honest look or not (only as the owner might guess). */
    static List<String> traits(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        for (Temperament.Mark m : Deputy.marksOf(f)) out.add(m.word);
        if (out.isEmpty()) out.add("steady");
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    /** Under a hand's row: the title and the rise, and the button that makes it so. */
    static void appointBox(VillageFolkEntity f, CompoundTag r, LifePage page) {
        page.box("Make " + f.displayNameCap() + " your deputy manager", List.of(
            "While you're away they open up, restock, set the day's prices, bank the takings and keep the wages covered, within your standing orders.",
            "Their character shows in how they do it: " + String.join(", ", traits(f)) + ". They get better at it over a few weeks."), 1);
        page.choice("dtitle", "Title", Deputy.TITLES, Deputy.TITLES, Deputy.TITLES[0]);
        page.choice("draise", "A rise of", RAISE, pct(RAISE), "10");
        page.buttons().button("Set " + f.displayNameCap() + " as deputy manager", "appoint", LifePage.args("h", f.getUUID().toString())).primary()
            .button("Not now", "deputy", LifePage.args("h", ""));
    }

    /** The summary's top: who runs it now, and a report waiting from the owner's last absence. */
    static void summaryTop(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        if (r == null) return;
        Deputy.Charge ch = Deputy.charge(level, v.id(), b, r, Businesses.today(level));
        page.text("In charge now: " + ch.who() + ".", ch.mode() == Deputy.Mode.PAUSED ? 3 : 4);
        ListTag rep = r.getList("report/" + p.getUUID(), Tag.TAG_STRING);
        if (!rep.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < rep.size(); i++) lines.add(rep.getString(i));
            page.box("While you were away", lines, 1);
            page.buttons().button("Got it", "readreport", null).button("The deputy's record", "tab:away", null);
        }
    }

    static void build(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page, PlayerLife.Ctx c) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        if (r == null) { page.text("Its record is missing.", 3); return; }
        boolean decide = Partners.canDecide(v.id(), b, p.getUUID());
        String no = decide ? "" : Partners.whyNot(v.id(), b);
        long day = Businesses.today(level);
        Deputy.Charge ch = Deputy.charge(level, v.id(), b, r, day);
        page.heading("Who's in charge");
        page.text("Now: " + ch.who() + ".", ch.mode() == Deputy.Mode.PAUSED ? 3 : 1);
        page.text("You, when you're at the business. Else a partner in town, then a player hand in town (the acting manager), then your deputy. "
            + "With nobody, the folk staff keep the doors open and sell what's in stock; it shuts only if all its staff are players and none is about.", 4);
        java.util.UUID did = Deputy.deputyId(r);
        if (did == null) {
            page.heading("Your deputy manager");
            page.text("None yet. Choose one of your folk staff: they cover for you while you're away, and their character shows in how.", 4);
            for (VillageFolkEntity f : Enterprise.hands(v.id(), b)) {
                page.row(null, f.displayNameCap(), String.join(", ", traits(f)) + " · paid " + Wealth.wage(f) + "c a day", "")
                    .button("Set as deputy manager", "deputy", LifePage.args("h", f.getUUID().toString()), decide, no);
                if (c.args.getString("appoint").equals(f.getUUID().toString())) appointBox(f, r, page);
            }
            if (Enterprise.hands(v.id(), b).isEmpty()) page.text("You have no folk staff here to choose from: take one on (Staff).", 2);
            orders(r, page, decide, no);
            history(r, page);
            return;
        }
        String dn = Deputy.name(v.id(), r);
        int rating = Deputy.rating(r);
        page.heading(Deputy.title(r) + ": " + dn);
        List<String> card = new ArrayList<>();
        card.add("Character: " + String.join(", ", Deputy.traitWords(r).isEmpty() ? List.of("steady") : Deputy.traitWords(r)) + ".");
        card.add(r.getInt("depDays") + " days in charge; " + (r.getInt("depDays") >= Deputy.LEARN_DAYS ? "they know the work now." : "still learning the work.")
            + (r.getInt("depRaise") > 0 ? " Paid " + r.getInt("depRaise") + "% over their wage." : ""));
        card.add("Rating: " + (rating < 0 ? "not yet tried" : rating + "/100, " + Deputy.ratingWord(rating)) + ".");
        for (String s : Deputy.reasons(r)) card.add(s + ".");
        page.box(dn + (Deputy.on(r) ? "" : " (authority off)"), card, rating < 0 ? 4 : rating >= 70 ? 1 : rating >= 50 ? 4 : 3);
        page.buttons().button("Show me " + dn, "showme", LifePage.args("h", did.toString())).button("Remove as deputy", "undeputy", LifePage.args("h", did.toString()), decide, no);
        // A raise asked for.
        if (r.contains("raiseAsked") && r.getLong("raiseAsked") != Long.MAX_VALUE) {
            page.box(dn + " asks for a raise", List.of("\"I've been running the place " + r.getInt("depDays") + " days now. I'd like that reflected.\"",
                "A tenth more keeps an ambitious deputy; refused, they may look elsewhere sooner."), 2);
            page.buttons().button("Grant a tenth more", "raiseyes", null, decide, no).button("Refuse", "raiseno", null, decide, no);
        }
        // Money missing.
        List<String> skims = new ArrayList<>();
        for (CompoundTag d : Deputy.log(r, "depLog", day - 28)) {
            if (d.getInt("skim") > 0) skims.add("Till short " + d.getInt("skim") + "c on day " + (d.getLong("d") + 1) + (d.contains("saw") ? ": " + d.getString("saw") + " saw it taken." : "."));
        }
        if (!skims.isEmpty()) {
            page.box("Money missing from the till", skims, 3);
            boolean sureSack = c.args.getBoolean("sureSack"), sureReport = c.args.getBoolean("sureReport");
            page.buttons().button(sureSack ? "Confirm: sack " + dn : "Sack " + dn, "sack", LifePage.args("sure", sureSack), decide, no)
                .button(sureReport ? "Confirm: report " + dn : "Report to the watch", "report", LifePage.args("sure", sureReport), decide, no);
        }
        // Its authority.
        page.heading("What " + dn + " may do");
        boolean on = Deputy.on(r), always = Deputy.always(r);
        page.buttons().button(on ? "Deputy in charge: On" : "Deputy in charge: Off", "depon", null, decide, no).primary()
            .button(r.getBoolean("depNotify") ? "Notify me: On" : "Notify me: Off", "depnotify", null, decide, no);
        page.text(on ? "On: they decide what the switches below allow." : "Off: they keep the title and the pay, and decide nothing. While you're away the staff carry on with what's in stock.", 4);
        page.buttons().button((!always ? "● " : "") + "Only while I'm away", "depmode", LifePage.args("m", "away"), decide && on && always, !on ? "Their authority is off" : always ? no : "Chosen")
            .button((always ? "● " : "") + "Always help", "depmode", LifePage.args("m", "always"), decide && on && !always, !on ? "Their authority is off" : !always ? no : "Chosen");
        page.text(always ? "Always help: with you there they do the routine work (open up, bank, keep the wages covered), never prices or spending; with you away, all you allow."
            : "Only while I'm away: the moment you're at the business, they stand back.", 4);
        LifePage.Row row = page.buttons();
        for (int i = 0; i < 3; i++) row.button(Deputy.TASK_WORDS[i] + ": " + (Deputy.may(r, Deputy.TASKS[i]) ? "yes" : "no"), "deptask", LifePage.args("k", Deputy.TASKS[i]), decide && on, on ? no : "Their authority is off");
        row = page.buttons();
        for (int i = 3; i < 6; i++) row.button(Deputy.TASK_WORDS[i] + ": " + (Deputy.may(r, Deputy.TASKS[i]) ? "yes" : "no"), "deptask", LifePage.args("k", Deputy.TASKS[i]), decide && on, on ? no : "Their authority is off");
        row = page.buttons();                                               // [rota] the rota, while you're away
        for (int i = 6; i < Deputy.TASKS.length; i++) row.button(Deputy.TASK_WORDS[i] + ": " + (Deputy.may(r, Deputy.TASKS[i]) ? "yes" : "no"), "deptask", LifePage.args("k", Deputy.TASKS[i]), decide && on, on ? no : "Their authority is off");
        if (r.contains("ownerPrice")) {
            page.text("You set the markup yourself (day " + (r.getLong("ownerPrice") + 1) + "): " + dn + " leaves your prices alone. A ware's own markup is always yours.", 4);
            page.buttons().button("Let " + dn + " set prices", "letprice", null, decide, no);
        } else page.text("Your own stock targets (Stock) and a ware's own markup (Prices) are never touched; setting the markup yourself keeps them off it too.", 4);
        orders(r, page, decide, no);
        // The last week.
        page.heading("Their last week");
        List<CompoundTag> week = Deputy.log(r, "depLog", day - 7);
        if (week.isEmpty()) page.text("Nothing yet: they take charge the first morning you're away.", 4);
        for (CompoundTag d : week) {
            page.text("Day " + (d.getLong("d") + 1) + ": " + (d.getBoolean("full") ? "in charge" : "helping") + (d.getBoolean("late") ? ", opened late" : ", on time")
                + (d.getLong("spend") > 0 ? ", spent " + Businesses.pence(d.getLong("spend")) : "") + (d.getLong("banked") > 0 ? ", banked " + Businesses.pence(d.getLong("banked")) : "")
                + (d.getBoolean("full") ? ", shelves " + Math.round(d.getDouble("fill") * 100) + "%" : "") + " · " + d.getInt("score") + "/100", d.getInt("score") >= 70 ? 4 : 2);
        }
        history(r, page);
    }

    static void orders(CompoundTag r, LifePage page, boolean decide, String no) {
        CompoundTag o = Deputy.orders(r);
        page.heading("While I'm away: standing orders");
        page.choice("lo", "Markup at least", LO, pct(LO), Integer.toString(o.getInt("lo")));
        page.choice("hi", "Markup at most", HI, pct(HI), Integer.toString(o.getInt("hi")));
        page.choice("spend", "Spend at most a day", SPEND, coins(SPEND), Integer.toString(o.getInt("spend")));
        page.choice("floor", "Keep in the till", FLOOR, coins(FLOOR), Integer.toString(o.getInt("floor")));
        page.buttons().button("Save the standing orders", "orders", null, decide, no);
        page.text("Spending stops at the day's limit or the till's floor, whichever comes first. Banked takings wait for you as drawings (Money).", 4);
    }

    static void history(CompoundTag r, LifePage page) {
        ListTag hist = r.getList("reports", Tag.TAG_COMPOUND);
        if (hist.isEmpty()) return;
        page.heading("While you were away: the reports");
        for (int i = hist.size() - 1; i >= 0 && i >= hist.size() - 3; i--) {
            CompoundTag h = hist.getCompound(i);
            ListTag l = h.getList("l", Tag.TAG_STRING);
            List<String> lines = new ArrayList<>();
            for (int j = 0; j < l.size(); j++) lines.add(l.getString(j));
            page.box("Back on day " + (h.getLong("d") + 1), lines, 4);
        }
    }

    static String act(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, String action, CompoundTag args, PlayerLife.Ctx c) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        if (r == null) return "!Its record is missing.";
        if (action.equals("readreport")) { r.remove("report/" + p.getUUID()); Accounts.dirty(); return ""; }
        if (action.equals("showme")) {
            UUID h;
            try { h = UUID.fromString(args.getString("h")); } catch (IllegalArgumentException e) { return "!Nobody to show."; }
            VillageFolkEntity f = Homes.loaded(v.id(), h);
            if (f == null) return "!They're not about just now.";
            WorkWay.showMe(p, f.blockPosition(), f.displayNameCap());
            return "The arrow points to " + f.displayNameCap() + ".";
        }
        if (!Partners.canDecide(v.id(), b, p.getUUID())) return "!" + Partners.whyNot(v.id(), b);
        CompoundTag f = args.getCompound("f");
        switch (action) {
            case "deputy" -> {
                c.args.putString("appoint", args.getString("h"));
                return "";
            }
            case "appoint" -> {
                VillageFolkEntity folk = hand(v, b, args.getString("h"));
                if (folk == null) return "!They don't work there any more.";
                c.args.remove("appoint");
                String title = f.getString("dtitle");
                boolean ok = false;
                for (String t : Deputy.TITLES) ok |= t.equals(title);
                return Deputy.appoint(level, v.id(), b, folk, ok ? title : Deputy.TITLES[0], ShopPage.parse(f.getString("draise"), 10));
            }
            case "undeputy" -> {
                String n = Deputy.name(v.id(), r);
                Deputy.demote(v.id(), b, r);
                b.note(Businesses.today(level), n + " is no longer deputy");
                BusinessCore.event(r, Businesses.today(level), n + " stood down as deputy");
                return n + " is a hand again (their rise taken back). While you're away the staff carry on with what's in stock.";
            }
            case "depon" -> {
                if (Deputy.on(r)) r.putBoolean("depOff", true); else r.remove("depOff");
                Accounts.dirty();
                return Deputy.on(r) ? Deputy.name(v.id(), r) + " is in charge again while you're away." : Deputy.name(v.id(), r) + " keeps the title and decides nothing.";
            }
            case "depmode" -> {
                r.putString("depMode", args.getString("m").equals("always") ? "always" : "away");
                Accounts.dirty();
                return Deputy.always(r) ? "Always help: the routine work even with you there; never prices or spending." : "Only while you're away: they stand back when you're there.";
            }
            case "deptask" -> {
                String k = args.getString("k");
                CompoundTag t = r.getCompound("depTasks");
                t.putBoolean(k, !Deputy.may(r, k));
                r.put("depTasks", t);
                Accounts.dirty();
                int i = java.util.Arrays.asList(Deputy.TASKS).indexOf(k);
                return (i < 0 ? k : Deputy.TASK_WORDS[i]) + ": " + (t.getBoolean(k) ? "theirs to do." : "not theirs to touch.");
            }
            case "depnotify" -> {
                r.putBoolean("depNotify", !r.getBoolean("depNotify"));
                Accounts.dirty();
                return r.getBoolean("depNotify") ? "You'll get a toast when something needs you (at most one a day)." : "Quiet: just the report when you're back.";
            }
            case "orders" -> {
                CompoundTag o = Deputy.orders(r);
                int lo = ShopPage.parse(f.getString("lo"), o.getInt("lo")), hi = ShopPage.parse(f.getString("hi"), o.getInt("hi"));
                o.putInt("lo", Math.min(lo, hi));
                o.putInt("hi", Math.max(lo, hi));
                o.putInt("spend", ShopPage.parse(f.getString("spend"), o.getInt("spend")));
                o.putInt("floor", ShopPage.parse(f.getString("floor"), o.getInt("floor")));
                Accounts.dirty();
                return "Standing orders: markup " + o.getInt("lo") + "-" + o.getInt("hi") + "%, at most " + o.getInt("spend") + "c a day on stock, " + o.getInt("floor") + "c kept in the till.";
            }
            case "letprice" -> {
                r.remove("ownerPrice");
                Accounts.dirty();
                return "Prices are the deputy's to set again while you're away, within your range.";
            }
            case "raiseyes", "raiseno" -> { return Deputy.answerRaise(level, v.id(), b, r, action.equals("raiseyes")); }
            case "sack", "report" -> {
                String key = action.equals("sack") ? "sureSack" : "sureReport";
                if (!args.getBoolean("sure")) { c.args.putBoolean(key, true); return "!Press again to " + (action.equals("sack") ? "sack them" : "report them to the watch") + "."; }
                c.args.remove(key);
                return Deputy.deal(level, p, v, b, r, action.equals("report"));
            }
            case "target" -> {
                String k = args.getString("k");
                int d = args.getInt("d");
                CompoundTag own = r.getCompound("stockTarget");
                if (d == 0) own.remove(k);
                else {
                    int was = own.contains(k) ? own.getInt(k) : r.getCompound("targets").getInt(k);
                    own.putInt(k, Math.max(0, Math.min(256, was + d)));
                }
                r.put("stockTarget", own);
                Accounts.dirty();
                return d == 0 ? Stockroom.plural(k) + ": its own reckoning again." : Stockroom.plural(k) + ": kept at " + own.getInt(k) + ", by you; nobody changes it.";
            }
            default -> { return null; }
        }
    }

    static VillageFolkEntity hand(Villages.Village v, Business b, String id) {
        for (VillageFolkEntity x : Enterprise.hands(v.id(), b)) if (x.getUUID().toString().equals(id)) return x;
        return null;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
