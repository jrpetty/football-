package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [earning] What earning adds to every service screen: selling to the counter (Buyers), the counter's paid shift
 * (Shifts: its Work tab, the line to serve), the town's Wanted orders delivered at the store, and the bank's money
 * changer and property (BankCounter). The shop's own panel shows them in its tabs; a plain desk gets them as sections.
 * Its buttons are checked here before the desk's own.
 */
final class EarnDesk {

    private EarnDesk() {}

    /** The actions this answers (ServicePoints.act asks before the desk). */
    static final Set<String> ACTIONS = Set.of("sellto", "serve", "takeShift", "quitShift", "deliver", "change", "collectRent", "sellHouse");

    static boolean handles(String action) {
        return ACTIONS.contains(action);
    }

    /** Is this counter one a counter hand works at? */
    static boolean counterRole(ServiceRole r) {
        return r == ServiceRole.SHOP || r == ServiceRole.CAFE || r == ServiceRole.TAVERN;
    }

    /** What earning shows that may change while the screen is open (the line at the counter, the player's shift). */
    static int print(ServicePoints.Visit visit) {
        int h = visit.point() == null || !counterRole(visit.role()) ? 0 : ServiceQueue.lineAt(visit.point().pos()).hashCode();
        int[] st = visit.player() == null ? null : Shifts.state(visit.player());
        return st == null ? h : h * 31 + java.util.Arrays.hashCode(st);
    }

    /** A plain desk's extra sections: we buy, the work going here, the bank's changer and property. */
    static void extend(ServicePoints.Visit visit, CompoundTag body) {
        if (visit.player() == null) return;
        DeskRows d = new DeskRows(visit.level().registryAccess());
        if (counterRole(visit.role())) workSection(d, visit);
        Buyers.deskSection(d, visit);
        if (visit.role() == ServiceRole.BANK) BankCounter.sections(d, visit);
        ListTag mine = d.build().getList("sections", Tag.TAG_COMPOUND);
        if (mine.isEmpty()) return;
        ListTag all = body.getList("sections", Tag.TAG_COMPOUND);
        all.addAll(mine);
        body.put("sections", all);
    }

    /** The counter's shift as rows: the line to serve (on shift here), or the job to take. */
    private static void workSection(DeskRows d, ServicePoints.Visit visit) {
        CompoundTag w = ShiftTasks.workTag(visit);
        if (w.isEmpty()) return;
        if (w.getBoolean("onShift")) {
            d.section("Your shift: " + w.getString("title"));
            d.line(w.getInt("tasks") + " served, " + w.getInt("pay") + "c paid, " + w.getInt("tips") + "c in tips. Serve the first in line.");
            ListTag line = w.getList("line", Tag.TAG_COMPOUND);
            for (int i = 0; i < line.size(); i++) {
                CompoundTag c = line.getCompound(i);
                int left = Math.round(c.getFloat("patience") * 100);
                CompoundTag row = d.row("serve", "Serve", (c.getBoolean("first") ? "First in line: " : "Waiting: ") + c.getString("name"),
                    (c.getString("wants").isEmpty() ? "" : "Wants " + c.getString("wants") + ". ") + "Patience " + left + "%",
                    new ItemStack(Items.PLAYER_HEAD), "");
                row.getCompound("args").putString("folk", c.getString("id"));
            }
            if (line.isEmpty()) d.line("Nobody in line just now.");
            d.row("quitShift", "Clock off", "Finish your shift", "Paid for what you've done.", new ItemStack(Items.CLOCK), "");
            return;
        }
        if (!w.contains("title")) return;
        d.section("Work here: " + w.getString("title"));
        d.line(w.getString("hours") + " · " + w.getString("pay") + " · your skill: " + w.getString("level"));
        String why = w.getString("why");
        CompoundTag once = d.row("takeShift", "Take", "Take a shift", "Four hours behind the counter.", new ItemStack(Items.CLOCK), why);
        once.getCompound("args").putBoolean("standing", false);
        CompoundTag standing = d.row("takeShift", "Every day", "Take it as a standing job", "Your shift starts each day at its hours while you're in town.",
            new ItemStack(Items.WRITABLE_BOOK), why.startsWith("Opens") ? "" : why);
        standing.getCompound("args").putBoolean("standing", true);
    }

    /** One of earning's buttons at a counter. */
    static ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        ServerPlayer p = visit.player();
        if (p == null) return ServicePoints.Result.no("");
        return switch (action) {
            case "sellto" -> Buyers.sell(visit, args.getString("id"), Math.max(1, args.getInt("amount") > 0 ? args.getInt("amount") : args.getInt("n")));
            case "serve" -> {
                UUID folk = Earnings.uuid(args.getString("folk"));
                if (folk == null) yield ServicePoints.Result.no("Serve whom?");
                String said = ShiftTasks.serve(visit.level(), p, folk);
                yield said.isEmpty() ? ServicePoints.Result.done("Served.") : ServicePoints.Result.no(said);
            }
            case "takeShift" -> {
                String said = Shifts.take(p, visit.village().id(), ShiftRole.COUNTER.id, args.getBoolean("standing"));
                yield said.isEmpty() || said.startsWith("Your standing job") ? ServicePoints.Result.said(said.isEmpty() ? "You're on: serve the first in line." : said)
                    : ServicePoints.Result.no(said);
            }
            case "quitShift" -> {
                String said = Shifts.quit(p, false);
                yield ServicePoints.Result.said(said.isEmpty() ? "You clocked off." : said);
            }
            case "deliver" -> WantedBoard.deliverAt(visit, args.getString("id"));
            case "change", "collectRent", "sellHouse" -> BankCounter.act(visit, action, args);
            default -> ServicePoints.Result.no("");
        };
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
