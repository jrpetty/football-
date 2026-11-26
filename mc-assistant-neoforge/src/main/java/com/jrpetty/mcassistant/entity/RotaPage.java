package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [rota] The Rota tab of a business (MyBusinesses, the Business Core's hub): the week's grid, a line for each member of
 * staff; who set it and who may change it; one person's week to edit (days on and off, a day's shift by presets or an
 * hour either way, a split shift); "Fill the rota for me"; the cover by the hour with its gaps and its too-many; the wage
 * bill and the overtime line; the staff's requests for a day off or a swap; a usual and a busy week saved; timesheets.
 * Every button says what happened and what next; one that can't be pressed says why; a big change is asked twice.
 */
final class RotaPage {

    private RotaPage() {}

    static final String[] OT = { "40", "44", "48", "54", "60" };

    static void build(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page, PlayerLife.Ctx c) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        String no = Rota.whyNotEdit(id, b, p.getUUID());
        boolean edit = no.isEmpty();
        Business.Hours h = WorkHours.hoursOf(id, b);
        TownHall.hint(p, page, "rota.first", "The rota says who works when. Pick a person, switch their days on or off, and set each day's shift with the presets "
            + "or an hour either way, or press Fill the rota for me. Folk follow it from their next shift; player staff see it on their Jobs page and planner. "
            + "With no rota, everyone works the opening hours.");
        Rota r = b.rota != null && !b.rota.auto ? b.rota : null;
        if (r != null) Rota.sync(level, id, b, r);
        page.heading("The rota: " + b.name);
        List<String> who = new ArrayList<>();
        who.add("Opening hours: " + h.words() + " (Hours on the week page, or Prices).");
        who.add(r == null || !r.active() ? "No rota set yet: the staff work its opening hours." : "Set by " + (r.setBy.isEmpty() ? "you" : r.setBy)
            + (r.setDay >= 0 ? ", " + Calendar.name(r.setDay) + " (day " + (r.setDay + 1) + ")" : "") + ".");
        who.add("Who can change it: " + Rota.whoMay(id, b) + ". Staff see only their own shifts and ask for a day off or a swap here.");
        page.box("Who sets it", who, edit ? 4 : 2);
        if (!edit) page.text(no + ".", 2);
        boolean sureFill = c.args.getBoolean("rotaSureFill"), sureClear = c.args.getBoolean("rotaSureClear");
        if (r == null || !r.active()) {
            List<VillageFolkEntity> staff = Rota.staffOf(id, b);
            int players = PlayerHiring.hands(level, "firm:" + b.id).size();
            page.text(staff.size() + players == 0 ? "Nobody works here yet: take on staff (Staff tab) and come back to roster them."
                : (staff.size() + players) + " on the staff. Fill it for them, or start from the opening hours and change it.", 4);
            boolean any = staff.size() + players > 0;
            page.buttons().button("Fill the rota for me", "rfill", LifePage.args("sure", true), edit && any, !edit ? no : "Nobody to roster: take on staff first").primary()
                .button("Start from the opening hours", "rstart", null, edit && any, !edit ? no : "Nobody to roster: take on staff first");
            templates(page, r, edit, no);
            return;
        }
        // The wage bill.
        Rota.Bill bill = Rota.bill(id, b, r);
        List<String> bl = new ArrayList<>();
        bl.add(bill.hours() + " hours rostered a week. Folk are paid by the hours: " + Rota.FULL_WEEK + " a week is their full wage; past " + r.overtimeAt
            + " it's time and a half.");
        bl.addAll(bill.lines());
        page.box("Weekly wage bill: about " + bill.coins() + "c" + (bill.overtime() > 0 ? " (" + bill.overtime() + "c of it overtime)" : ""), bl, bill.overtime() > 0 ? 2 : 4);
        page.choice("ot", "Overtime past", OT, hoursWords(OT), Integer.toString(r.overtimeAt));
        page.buttons().button("Set the overtime line", "rot", null, edit, no);
        // The grid.
        page.heading("The week");
        String chosen = c.args.getString("rotaWho");
        for (Rota.Line l : r.lines.values()) {
            VillageFolkEntity f = Homes.loaded(id, l.who);
            String like = "";
            int colour = 0;
            if (f != null && !l.owner) {
                Rota.Feeling fe = Rota.feeling(f, l.weekHours());
                if (!fe.why().isEmpty()) like = " · " + fe.why() + (fe.mood() < 0 ? " (morale " + fe.mood() + ")" : "");
                colour = fe.mood() < 0 ? 2 : 0;
            }
            int late = Rota.lateDays(r, l.who);
            page.row(new ItemStack(l.player ? Items.PLAYER_HEAD : l.owner ? Items.GOLDEN_HELMET : Items.LEATHER_HELMET),
                    (l.who.toString().equals(chosen) ? "▶ " : "") + l.name + (l.player ? " (player)" : l.owner ? " (owner)" : ""),
                    gridLine(l) + like + (late > 0 ? " · late " + late + "× in two weeks" : ""), l.weekHours() + "h · " + l.daysWorked() + " days")
                .button(l.who.toString().equals(chosen) ? "Editing" : "Edit", "redit", LifePage.args("w", l.who.toString()), edit && !l.who.toString().equals(chosen),
                    !edit ? no : "Editing now: below");
            if (colour == 2 && f != null) page.text("  " + f.displayNameCap() + " would rather " + (l.weekHours() > Rota.prefHours(f) ? "fewer" : "more") + " hours: about " + Rota.prefHours(f) + " a week.", 2);
            if (l.who.toString().equals(chosen) && edit) editor(page, r, l, h, c);
        }
        // Cover by the hour.
        page.heading("Cover by the hour");
        int[][] cov = Rota.coverage(r);
        for (int d = 0; d < 7; d++) {
            if (!h.openOn(d)) {
                int stray = 0;
                for (int i = 0; i < 24; i++) stray += cov[d][i];
                page.text(Rota.DAY_SHORT[d] + ": shut" + (stray > 0 ? " (but someone is rostered)" : ""), stray > 0 ? 2 : 4);
                continue;
            }
            int covered = 0, open = Math.min(24, h.close()) - h.open();
            StringBuilder sb = new StringBuilder();
            for (int i = h.open(); i < Math.min(24, h.close()); i++) { if (cov[d][i] > 0) covered++; sb.append(cov[d][i]); }
            page.bar(Rota.DAY_SHORT[d] + "  " + Rota.hh(h.open()) + " " + spaced(sb.toString()) + " " + Rota.hh(Math.min(24, h.close())),
                open == 0 ? 1 : covered / (double) open, covered + "/" + open + "h");
        }
        List<Rota.Warning> ws = Rota.warnings(id, b, r, h);
        if (ws.isEmpty()) page.text("No gaps and nobody idle: every opening hour has someone on, and everyone has two days off.", 1);
        for (Rota.Warning w : ws.subList(0, Math.min(10, ws.size()))) page.text(w.text(), w.colour());
        if (ws.size() > 10) page.text("…and " + (ws.size() - 10) + " more.", 4);
        // Fill, clear.
        page.buttons().button(sureFill ? "Confirm: replace the whole rota" : "Fill the rota for me", "rfill", LifePage.args("sure", sureFill), edit, no).primary()
            .button(sureClear ? "Confirm: clear the rota" : "Clear the rota", "rclear", LifePage.args("sure", sureClear), edit, no);
        if (sureFill) page.text("Filling replaces every shift above with one built round the opening hours, the busy hours and everyone's days off. Press again to do it.", 2);
        if (sureClear) page.text("Clearing it puts everyone back on the opening hours, every open day. Press again to do it.", 2);
        // Requests.
        page.heading("Requests from staff");
        boolean anyReq = false;
        for (int i = r.requests.size() - 1; i >= 0; i--) {
            Rota.Request q = r.requests.get(i);
            if (q.day < day - 1) continue;
            anyReq = true;
            boolean open = q.state.equals("asked");
            LifePage.Row row = page.row(new ItemStack(q.kind.equals("swap") ? Items.PAPER : Items.CLOCK),
                q.name + (q.kind.equals("swap") ? " asks " + q.withName + " to cover " : " asks for a day off: ") + Calendar.name(q.day) + " (day " + (q.day + 1) + ")",
                (q.why.isEmpty() ? "" : "Why: " + q.why + ". ") + (open ? "Waiting for your answer." : "You " + q.state + " it."), Calendar.when(day, q.day));
            if (open) row.button("Approve", "rreq", LifePage.args("id", q.id, "yes", true), edit, no).button("Decline", "rreq", LifePage.args("id", q.id, "yes", false), edit, no);
        }
        if (!anyReq) page.text("None. Folk ask when their club night or a family day falls on a shift; player staff ask from their Jobs page.", 4);
        templates(page, r, edit, no);
        // Timesheets.
        page.heading("Timesheets");
        boolean anyClock = false;
        for (Rota.Line l : r.lines.values()) {
            List<String> ts = Rota.timesheet(r, l.who, 3);
            if (ts.isEmpty()) continue;
            anyClock = true;
            page.box(l.name, ts, Rota.lateDays(r, l.who) > 1 ? 2 : 4);
        }
        if (!anyClock) page.text("Nothing yet. Folk clock in when they start their shift; player staff clock in and out at the Business Core.", 4);
    }

    static void templates(LifePage page, Rota r, boolean edit, String no) {
        page.heading("Saved weeks");
        boolean have = r != null && r.active();
        boolean usual = r != null && r.templates.containsKey("usual"), busy = r != null && r.templates.containsKey("busy");
        page.text("Save this week to put it back later. A busy week is used on the fair's week (the fourth of the month), when the town is full.", 4);
        page.buttons().button("Save as the usual week", "rsave", LifePage.args("t", "usual"), edit && have, !edit ? no : "Nothing rostered to save yet")
            .button("Load the usual week", "rload", LifePage.args("t", "usual"), edit && usual && have, !edit ? no : !usual ? "No usual week saved yet" : "Start a rota first")
            .button("Save as the busy week", "rsave", LifePage.args("t", "busy"), edit && have, !edit ? no : "Nothing rostered to save yet");
        page.buttons().button("Busy week on the fair's week: " + (r == null || r.busyOnFair ? "On" : "Off"), "rbusy", null, edit && r != null, !edit ? no : "Start a rota first")
            .button("Load the busy week now", "rload", LifePage.args("t", "busy"), edit && busy && have, !edit ? no : !busy ? "No busy week saved yet" : "Start a rota first");
    }

    /** One person's week to edit: the days, the chosen day's shift. */
    static void editor(LifePage page, Rota r, Rota.Line l, Business.Hours h, PlayerLife.Ctx c) {
        int dsel = c.args.contains("rotaDay") ? c.args.getInt("rotaDay") : 0;
        page.box("Editing " + l.name + "'s week", List.of("Days: switch each on (its opening hours, or the shift you set last) or off.",
            "A day: choose it, then a preset or move its start and end an hour at a time. Split adds a second shift after a break."), 1);
        LifePage.Row days = page.buttons();
        for (int d = 0; d < 7; d++) {
            boolean on = Rota.works(l.day(d));
            days.button((on ? "● " : "○ ") + Rota.DAY_SHORT[d], "rtoggle", LifePage.args("w", l.who.toString(), "d", d), true, "");
        }
        LifePage.Row pick = page.buttons();
        for (int d = 0; d < 7; d++) pick.button((d == dsel ? "▶ " : "") + Rota.DAY_SHORT[d], "rday", LifePage.args("d", d), d != dsel, "Chosen");
        int[] s = l.day(dsel);
        boolean on = Rota.works(s);
        page.text(Calendar.Day.values()[dsel].word + ": " + Rota.words(s) + (on ? " (" + Rota.hours(s) + "h)" : "") + (h.openOn(dsel) ? "" : " · the business is shut that day"),
            h.openOn(dsel) || !on ? 0 : 2);
        CompoundTag a = LifePage.args("w", l.who.toString(), "d", dsel);
        LifePage.Row pre = page.buttons();
        for (String[] ps : new String[][]{ { "morning", "Morning" }, { "afternoon", "Afternoon" }, { "evening", "Evening" }, { "full", "Full day" }, { "split", "Split" }, { "off", "Off" } }) {
            CompoundTag x = a.copy();
            x.putString("p", ps[0]);
            pre.button(ps[1], "rpreset", x, ps[0].equals("off") ? on : h.openOn(dsel), ps[0].equals("off") ? "Off already" : "It's shut that day: change its hours first");
        }
        LifePage.Row adj = page.buttons();
        adj.button("Start −", "radj", adjArgs(a, "s0", -1), on && s[0] > 5, on ? "It can't start before 05:00" : "A day off: pick a preset first")
            .button("Start +", "radj", adjArgs(a, "s0", 1), on && s[0] + 1 < s[1], on ? "At least an hour long" : "A day off: pick a preset first")
            .button("End −", "radj", adjArgs(a, "e0", -1), on && s[1] - 1 > s[0], on ? "At least an hour long" : "A day off: pick a preset first")
            .button("End +", "radj", adjArgs(a, "e0", 1), on && s[1] < 24 && (s[2] == 0 || s[1] + 1 < s[2]), on ? "Up against midnight or the second shift" : "A day off: pick a preset first");
        if (s[2] > 0) {
            page.buttons().button("2nd start −", "radj", adjArgs(a, "s1", -1), s[2] - 1 > s[1], "Up against the first shift")
                .button("2nd start +", "radj", adjArgs(a, "s1", 1), s[2] + 1 < s[3], "At least an hour long")
                .button("2nd end −", "radj", adjArgs(a, "e1", -1), s[3] - 1 > s[2], "At least an hour long")
                .button("2nd end +", "radj", adjArgs(a, "e1", 1), s[3] < 24, "Up against midnight");
        }
        page.buttons().button("Copy " + Rota.DAY_SHORT[dsel] + " to every working day", "rcopy", a, on, "A day off: there's nothing to copy")
            .button("Done", "rdone", null).primary();
    }

    static CompoundTag adjArgs(CompoundTag a, String k, int d) {
        CompoundTag x = a.copy();
        x.putString("k", k);
        x.putInt("n", d);
        return x;
    }

    /** "Mon 7–15 · Tue off · Wed 7–11/15–19 …" */
    static String gridLine(Rota.Line l) {
        List<String> out = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            int[] s = l.day(d);
            out.add(Rota.DAY_SHORT[d] + " " + (!Rota.works(s) ? "off" : s[0] + "–" + s[1] + (s[2] > 0 ? "/" + s[2] + "–" + s[3] : "")));
        }
        return String.join(" · ", out);
    }

    static String spaced(String digits) {
        StringBuilder sb = new StringBuilder();
        for (char ch : digits.toCharArray()) sb.append(sb.length() == 0 ? "" : " ").append(ch == '0' ? '·' : ch);
        return sb.toString();
    }

    static String[] hoursWords(String[] a) {
        String[] out = new String[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] + "h a week";
        return out;
    }

    // ------------------------------------------------------------------ buttons

    static String act(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, String action, CompoundTag args, PlayerLife.Ctx c) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        String by = p.getGameProfile().getName();
        if (action.equals("rday")) { c.args.putInt("rotaDay", args.getInt("d")); return "Editing " + Calendar.Day.values()[Math.floorMod(args.getInt("d"), 7)].word + ": pick a preset or move its hours."; }
        if (action.equals("rdone")) { c.args.remove("rotaWho"); return "Done. Folk follow the rota from their next shift; player staff see it on their Jobs page."; }
        String no = Rota.whyNotEdit(id, b, p.getUUID());
        if (!no.isEmpty()) return "!" + no + ".";
        Business.Hours h = WorkHours.hoursOf(id, b);
        Rota r = b.rota != null && !b.rota.auto ? b.rota : null;
        switch (action) {
            case "rfill" -> {
                boolean have = r != null && r.active() && totalHours(r) > 0;
                if (have && !args.getBoolean("sure")) { c.args.putBoolean("rotaSureFill", true); c.args.remove("rotaSureClear"); return "!That replaces every shift. Press Confirm to fill it afresh."; }
                c.args.remove("rotaSureFill");
                return Rota.autoFill(level, id, b, by);
            }
            case "rstart" -> {
                Rota n = new Rota();
                b.rota = n;
                Rota.sync(level, id, b, n);
                if (n.lines.isEmpty()) return "!Nobody works there yet: take on staff first (Staff tab).";
                for (Rota.Line l : n.lines.values()) {
                    l.fresh = false;
                    java.util.Arrays.fill(l.h, 0);
                    int k = 0;
                    for (int d = 0; d < 7; d++) if (h.openOn(d) && k < Rota.MOST_DAYS) { l.set(d, Rota.preset("full", h)); k++; }
                }
                n.setBy = by;
                n.setDay = day;
                Businesses.dirty();
                Rota.pushPlanner(level, id, b);
                return "Started the rota from the opening hours (" + h.words() + "), five days each at most. Edit anyone's week below, or Fill the rota for me to spread days off.";
            }
            case "rclear" -> {
                if (r == null) return "!There's no rota to clear.";
                if (!args.getBoolean("sure")) { c.args.putBoolean("rotaSureClear", true); c.args.remove("rotaSureFill"); return "!Clearing puts everyone on the opening hours. Press Confirm to clear it."; }
                c.args.remove("rotaSureClear");
                r.lines.clear();
                r.overrides.clear();
                r.setBy = by;
                r.setDay = day;
                Businesses.dirty();
                for (ServerPlayer x : PlayerHiring.hands(level, "firm:" + b.id)) Planner.clear(x.getUUID(), "rota");
                b.note(day, "the rota cleared by " + by);
                return "Rota cleared: the staff work the opening hours (" + h.words() + ") from their next shift. Saved weeks are kept.";
            }
            case "rot" -> {
                if (r == null) return "!Start a rota first.";
                int ot = ShopPage.parse(args.getCompound("f").getString("ot"), r.overtimeAt);
                r.overtimeAt = Math.max(30, Math.min(70, ot));
                Businesses.dirty();
                return "Overtime from " + r.overtimeAt + " hours a week: hours past it cost time and a half. The wage bill above is worked out again.";
            }
            case "rsave" -> { return r == null ? "!Start a rota first." : Rota.saveTemplate(r, args.getString("t").equals("busy") ? "busy" : "usual"); }
            case "rload" -> {
                if (r == null) return "!Start a rota first.";
                String said = Rota.loadTemplate(r, args.getString("t").equals("busy") ? "busy" : "usual", by, day);
                if (!said.startsWith("!")) Rota.pushPlanner(level, id, b);
                return said;
            }
            case "rbusy" -> {
                if (r == null) return "!Start a rota first.";
                r.busyOnFair = !r.busyOnFair;
                Businesses.dirty();
                return r.busyOnFair ? "On the fair's week the busy week is used" + (r.templates.containsKey("busy") ? "." : " (save one first: Save as the busy week).")
                    : "The usual rota every week, the fair's too.";
            }
            case "rreq" -> { return r == null ? "!Start a rota first." : Rota.answer(level, id, b, args.getInt("id"), args.getBoolean("yes"), by); }
            default -> { }
        }
        if (r == null) return "!Start a rota first: Fill the rota for me, or Start from the opening hours.";
        UUID who;
        try { who = UUID.fromString(args.getString("w")); } catch (IllegalArgumentException e) { return "!Pick somebody on the rota first."; }
        Rota.Line l = r.lines.get(who);
        if (l == null) return "!They're not on the rota any more.";
        int d = Math.floorMod(args.getInt("d"), 7);
        String dayWord = Calendar.Day.values()[d].word;
        String said;
        switch (action) {
            case "redit" -> {
                c.args.putString("rotaWho", who.toString());
                if (!c.args.contains("rotaDay")) c.args.putInt("rotaDay", Calendar.dow(day));
                return "Editing " + l.name + "'s week: switch days on or off, then set each day's shift.";
            }
            case "rtoggle" -> {
                boolean on = Rota.works(l.day(d));
                if (!on && l.daysWorked() >= Rota.MOST_DAYS && !l.owner) return "!" + l.name + " already works " + l.daysWorked() + " days: everyone keeps two days off a week. Take another day off first.";
                if (!on && !h.openOn(d)) return "!" + b.name + " is shut on " + dayWord + "s: change its hours first, or pick another day.";
                r.put(who, d, on ? new int[4] : Rota.preset("full", h), by, day);
                c.args.putInt("rotaDay", d);
                said = on ? l.name + " has " + dayWord + " off." : l.name + " works " + dayWord + ", " + Rota.words(l.day(d)) + ". Change the hours below.";
            }
            case "rpreset" -> {
                String ps = args.getString("p");
                if (!ps.equals("off") && !Rota.works(l.day(d)) && l.daysWorked() >= Rota.MOST_DAYS && !l.owner)
                    return "!" + l.name + " already works " + l.daysWorked() + " days: everyone keeps two days off a week.";
                r.put(who, d, Rota.preset(ps, h), by, day);
                said = l.name + " on " + dayWord + ": " + Rota.words(l.day(d)) + ".";
            }
            case "radj" -> {
                int[] s = l.day(d);
                if (!Rota.works(s)) return "!" + dayWord + " is a day off: pick a preset first.";
                int k = switch (args.getString("k")) { case "e0" -> 1; case "s1" -> 2; case "e1" -> 3; default -> 0; };
                s[k] += args.getInt("n") < 0 ? -1 : 1;
                r.put(who, d, s, by, day);
                said = l.name + " on " + dayWord + ": " + Rota.words(l.day(d)) + " (" + Rota.hours(l.day(d)) + "h).";
            }
            case "rcopy" -> {
                int[] s = l.day(d);
                int n = 0;
                for (int x = 0; x < 7; x++) if (x != d && Rota.works(l.day(x))) { l.set(x, s); n++; }
                l.fresh = false;
                r.setBy = by;
                r.setDay = day;
                said = "Copied " + Rota.words(s) + " to " + n + " other working " + (n == 1 ? "day" : "days") + " of " + l.name + "'s.";
            }
            default -> { return "!That button does nothing yet."; }
        }
        Businesses.dirty();
        Rota.pushPlanner(level, id, b);
        int wk = l.weekHours();
        return said + " " + l.name + " is on " + wk + " hours a week" + (wk > r.overtimeAt ? ", " + (wk - r.overtimeAt) + " of them overtime" : "") + ". The cover below is updated.";
    }

    static int totalHours(Rota r) {
        int n = 0;
        for (Rota.Line l : r.lines.values()) n += l.weekHours();
        return n;
    }
}
