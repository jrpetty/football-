package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.AdminCommands;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.CityTree;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.net.AdminPanelPayload;
import com.jrpetty.mcassistant.village.AdminArgs;
import com.jrpetty.mcassistant.village.AdminSpec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * [admin] The server's side of the admin panel: it answers "open" and "refresh" with the panel's state (the towns, the
 * chosen town's inspect lines, the jobs, the tick time, the player's recent actions, the areas it may use), and runs
 * a line the panel sends exactly as the same command typed would run, through AdminRunner, permission and all.
 */
public final class AdminPanel {

    private AdminPanel() {}

    /** The panel asked: open, refresh, or run a line. */
    public static void ask(ServerPlayer p, String what, String town, String line) {
        if (!AdminAccess.anyArea(p)) {
            p.displayClientMessage(Component.literal("Only an operator may open the admin panel."), true);
            return;
        }
        AdminRunner.Outcome result = null;
        String path = "";
        if (what.equals("run") && !line.isBlank()) {
            String[] r = route(line);
            path = r[0];
            result = switch (r[0]) {
                case "confirm" -> AdminConfirm.confirm(p.createCommandSourceStack(), r[1].trim());
                case "cancel" -> AdminJobs.cancel(r[1].trim()) ? AdminRunner.Outcome.ok(List.of("Job " + r[1].trim() + " stopping."))
                    : AdminRunner.Outcome.fail("No job " + r[1].trim() + ".");
                case "" -> AdminRunner.Outcome.fail("Not an admin action: " + line);
                default -> AdminRunner.run(p.createCommandSourceStack(), r[0], r[1]);
            };
            AdminCommands.reply(p.createCommandSourceStack(), path, result);
        }
        open(p, what.equals("open") ? "" : null, town, result);
    }

    /** Split a line into the action's path and its values: the longest path the line starts with. */
    static String[] route(String line) {
        String l = line.trim();
        if (l.startsWith("/")) l = l.substring(1);
        if (l.startsWith("village admin ")) l = l.substring(14);
        for (String meta : new String[]{ "confirm", "cancel" }) if (l.startsWith(meta + " ")) return new String[]{ meta, l.substring(meta.length() + 1) };
        List<String> w = AdminArgs.tokens(l);
        if (w.size() >= 2 && AdminSpec.byPath(w.get(0) + " " + w.get(1)) != null) {
            String p = w.get(0) + " " + w.get(1);
            int at = l.indexOf(w.get(1), w.get(0).length()) + w.get(1).length();
            return new String[]{ p, l.substring(Math.min(l.length(), at)).trim() };
        }
        if (!w.isEmpty() && AdminSpec.byPath(w.get(0)) != null) return new String[]{ w.get(0), l.substring(w.get(0).length()).trim() };
        return new String[]{ "", "" };
    }

    public static void open(ServerPlayer p, String area, String town) {
        open(p, area, town, null);
    }

    /** Send the panel's state; {@code area} non-null opens it (on that area, "" for the first). */
    static void open(ServerPlayer p, @javax.annotation.Nullable String area, String town, @javax.annotation.Nullable AdminRunner.Outcome result) {
        AdminProbe.watch();
        CompoundTag t = new CompoundTag();
        if (area != null) {
            t.putBoolean("open", true);
            t.putString("area", area);
        }
        ServerLevel level = p.serverLevel();
        Villages.Village v = town.isBlank() ? Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE * 4) : AdminRunner.town(town);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        ListTag towns = new ListTag();
        for (Villages.Village x : Villages.every()) towns.add(StringTag.valueOf(Villages.name(x.id())));
        t.put("towns", towns);
        t.putString("town", v == null ? "" : Villages.name(v.id()));
        t.putInt("folk", v == null ? 0 : Villages.headcount(v.id()));
        t.putFloat("mspt", p.server.getCurrentSmoothedTickTime());
        if (v != null && AdminAccess.granted(p, AdminSpec.Area.TOWN)) {
            ServerLevel tl = p.server.getLevel(v.dim());
            t.put("inspect", strings(AdminTools.inspect(tl == null ? level : tl, v)));
        }
        ListTag jobs = new ListTag();
        for (AdminJobs.Job j : AdminJobs.running()) {
            CompoundTag jt = new CompoundTag();
            jt.putString("id", j.id());
            jt.putString("label", j.label());
            jt.putInt("done", Math.min(j.done(), j.total()));
            jt.putInt("total", j.total());
            jobs.add(jt);
        }
        t.put("jobs", jobs);
        t.put("recent", strings(AdminLog.recent(AdminAccess.who(p.createCommandSourceStack()))));
        ListTag areas = new ListTag();
        for (AdminSpec.Area a : AdminSpec.Area.values()) if (AdminAccess.granted(p, a)) areas.add(StringTag.valueOf(a.key()));
        t.put("areas", areas);
        if (area != null) {                                    // the lists the fields offer, sent once when it opens
            t.put("trades", strings(AdminTools.tradeWords()));
            t.put("structures", strings(Showcase.ORDER));
            ListTag civics = new ListTag();
            for (CityTree.Civic c : CityTree.Civic.values()) civics.add(StringTag.valueOf(c.key()));
            t.put("civics", civics);
            t.put("players", strings(List.of(p.server.getPlayerNames())));
        }
        if (result != null) {
            t.putBoolean("resultOk", result.ok());
            t.put("result", strings(result.lines()));
            if (result.confirmId() != null) t.putString("confirm", result.confirmId());
        }
        try {
            if (p.connection != null && p.connection.hasChannel(AdminPanelPayload.TYPE)) PacketDistributor.sendToPlayer(p, new AdminPanelPayload(t));
        } catch (RuntimeException ignored) {
            // a client without the mod, or going away: nothing to show it on
        }
    }

    private static ListTag strings(List<String> lines) {
        ListTag l = new ListTag();
        for (String s : lines) l.add(StringTag.valueOf(s));
        return l;
    }
}
