package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.AdminArgs;
import com.jrpetty.mcassistant.village.AdminSpec;
import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [admin] Every admin action goes through here, from a command, the panel or a script: the permission checked, the
 * values checked, the towns and folk chosen by their selectors, a dry run answered with what would change, a
 * destructive action held for its confirm token, and then the action run (AdminActions) with each result written to
 * the admin log and, where something changed, to the town's chronicle as "By decree: ...".
 */
public final class AdminRunner {

    private AdminRunner() {}

    /** What came of a call: whether it worked, its lines, and a confirm token or a job when it made one. */
    public record Outcome(boolean ok, List<String> lines, @Nullable String confirmId, @Nullable String jobId) {
        public static Outcome fail(String line) { return new Outcome(false, List.of(line), null, null); }
        public static Outcome ok(List<String> lines) { return new Outcome(true, lines, null, null); }
    }

    /** One call's context: who, where, the action and its values, and whether it is only a dry run. */
    public record Ctx(MinecraftServer server, ServerLevel level, CommandSourceStack src, AdminSpec.Action action, AdminArgs args, String who) {
        public BlockPos pos() { return BlockPos.containing(src.getPosition()); }
    }

    /** The last town an admin founded (town:newest), for scripts that found one and then work on it. */
    @Nullable static volatile UUID newest;

    /** Run an action by its path ("ages set") with what followed it. */
    public static Outcome run(CommandSourceStack src, String path, String raw) {
        AdminSpec.Action action = AdminSpec.byPath(path);
        if (action == null) return Outcome.fail("No admin action \"" + path + "\". Try /village admin help.");
        MinecraftServer server = src.getServer();
        String who = AdminAccess.who(src);
        String refused = AdminAccess.refusal(src, action.area());
        if (refused != null) {
            AdminLog.append(server, who, action.path(), "-", "refused: " + refused);
            return Outcome.fail(refused);
        }
        AdminArgs args = AdminArgs.parse(raw, action);
        if (args.error() != null) return Outcome.fail(args.error());
        Ctx c = new Ctx(server, src.getLevel(), src, action, args, who);
        if (src.getPlayer() != null) AdminLog.ran(who, ("/village admin " + action.path() + " " + raw).trim());

        List<Villages.Village> towns = new ArrayList<>();
        if (action.scope() != AdminSpec.Scope.WORLD) {
            String why = towns(c, towns);
            if (why != null) return Outcome.fail(why);
        }
        if (args.dryRun()) {
            List<String> lines = new ArrayList<>();
            for (Villages.Village v : scopeTowns(action, towns)) lines.add("DRY-RUN " + AdminActions.preview(c, v));
            lines.add("(nothing was changed)");
            AdminLog.append(server, who, action.path() + " --dry-run", targets(towns), String.join(" / ", lines));
            return Outcome.ok(lines);
        }
        if (action.destructive() && !AdminAccess.mayDestroyNow(src)) {
            List<String> lines = new ArrayList<>();
            for (Villages.Village v : scopeTowns(action, towns)) lines.add("WILL " + AdminActions.preview(c, v));
            String id = AdminConfirm.ask(src, String.join(" / ", lines), () -> execute(c, towns));
            lines.add("This cannot be undone. Back up first (/save-all, and /village admin snapshot for the town's saved data).");
            lines.add("CONFIRM /village admin confirm " + id + " within " + AdminConfig.confirmSeconds() + "s");
            AdminLog.append(server, who, action.path(), targets(towns), "asked to confirm " + id);
            return new Outcome(true, lines, id, null);
        }
        return execute(c, towns);
    }

    /** The action run on its towns (or once, for the world), every result logged and decreed. */
    static Outcome execute(Ctx c, List<Villages.Village> towns) {
        List<String> lines = new ArrayList<>();
        boolean any = false;
        String job = null;
        if (c.action().big()) {
            if (c.args().flag(AdminSpec.SNAPSHOT)) for (Villages.Village v : towns) lines.add(AdminSnapshots.take(c.server(), v).text());
            else lines.add("A big change: /save-all first is wise; add --snapshot to keep a copy of the town's saved data.");
        }
        for (Villages.Village v : scopeTowns(c.action(), towns)) {
            AdminTools.Done d;
            try {
                d = AdminActions.run(c, v);
            } catch (RuntimeException e) {
                d = AdminTools.Done.no("Failed: " + e);
                com.mojang.logging.LogUtils.getLogger().error("[MCA-ADMIN] {} failed", c.action().id(), e);
            }
            any |= d.ok();
            if (d.text().startsWith("JOB ")) job = d.text().substring(4, 8);
            lines.add((d.ok() ? "" : "NO ") + d.text());
            AdminLog.append(c.server(), c.who(), c.action().path() + (c.args().positional().isEmpty() ? "" : " " + String.join(" ", c.args().positional())),
                v == null ? "world" : Villages.name(v.id()), (d.ok() ? "ok: " : "failed: ") + d.text());
            if (d.ok() && d.decree() != null) decree(c.level(), v, d.decree());
        }
        return new Outcome(any, lines, null, job);
    }

    /** "By decree: ..." in the town's chronicle (and the world's, for an action on no town, nowhere). */
    public static void decree(ServerLevel level, @Nullable Villages.Village v, String what) {
        if (v == null) return;
        Chronicle.record(v.id(), level.getDayTime() / 24000L, "By decree: " + what);
    }

    /** The towns a world action still names (one null: it acts once). */
    private static List<Villages.Village> scopeTowns(AdminSpec.Action a, List<Villages.Village> towns) {
        if (a.scope() != AdminSpec.Scope.WORLD) return towns;
        List<Villages.Village> once = new ArrayList<>();
        once.add(null);
        return once;
    }

    private static String targets(List<Villages.Village> towns) {
        if (towns.isEmpty()) return "world";
        List<String> n = new ArrayList<>();
        for (Villages.Village v : towns) n.add(Villages.name(v.id()));
        return String.join(",", n);
    }

    // ------------------------------------------------------------------ selectors

    /** Fill {@code out} with the towns the selector means; the reason when it means none. */
    @Nullable
    static String towns(Ctx c, List<Villages.Village> out) {
        AdminArgs.TownSel sel = c.args().town();
        if (c.action().scope() == AdminSpec.Scope.FOLK && sel.kind() == AdminArgs.TownSel.Kind.NEAREST
                && c.args().folk().kind() == AdminArgs.FolkSel.Kind.NAMED) {
            List<VillageFolkEntity> named = folk(c, null);                  // a folk by name: its own town
            if (named.isEmpty()) return "No folk called " + c.args().folk().value() + " is about.";
            Villages.Village home = named.get(0).ownerId() == null ? null : Villages.get(named.get(0).ownerId());
            if (home != null) {
                out.add(home);
                return null;
            }
        }
        switch (sel.kind()) {
            case ALL -> out.addAll(Villages.every());
            case NAMED -> {
                Villages.Village v = town(sel.value());
                if (v != null) out.add(v);
                else return "No town called " + sel.value() + ". Towns: " + String.join(", ", townNames());
            }
            default -> {
                Villages.Village v = nearest(c);
                if (v != null) out.add(v);
            }
        }
        if (out.isEmpty()) return Villages.every().isEmpty() ? "There are no towns yet." : "No town near here: name one with town:<name> (or town:all).";
        return null;
    }

    /** The town nearest the source; from the console with no town near, the only town there is. */
    @Nullable
    static Villages.Village nearest(Ctx c) {
        Villages.Village v = Villages.nearest(c.level(), c.pos(), Villages.VILLAGE_RANGE * 4);
        if (v == null && c.src().getPlayer() == null && Villages.every().size() == 1) v = Villages.every().get(0);
        return v;
    }

    /** A town by name (spaces or underscores, any case), by id or the start of one, or "newest". */
    @Nullable
    public static Villages.Village town(String key) {
        String k = key.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (k.equals("newest") && newest != null) return Villages.get(newest);
        for (Villages.Village v : Villages.every()) if (Villages.name(v.id()).toLowerCase(Locale.ROOT).equals(k)) return v;
        if (k.length() >= 4) for (Villages.Village v : Villages.every()) if (v.id().toString().startsWith(k)) return v;
        return null;
    }

    public static List<String> townNames() {
        List<String> out = new ArrayList<>();
        for (Villages.Village v : Villages.every()) {
            String n = Villages.name(v.id());
            out.add(n.contains(" ") ? "\"" + n + "\"" : n);
        }
        return out;
    }

    /** The folk the selector means in these towns (a name or uuid is looked for in every town). */
    static List<VillageFolkEntity> folk(Ctx c, @Nullable Villages.Village v) {
        AdminArgs.FolkSel sel = c.args().folk();
        List<VillageFolkEntity> out = new ArrayList<>();
        switch (sel.kind()) {
            case ALL -> { if (v != null) out.addAll(AdminTools.folk(v.id())); }
            case TRADE -> {
                if (v == null) break;
                var t = AdminTools.trade(sel.value());
                for (VillageFolkEntity f : AdminTools.folk(v.id())) {
                    if (t != null ? f.stationTask() == t : sel.value().equals("children") ? f.isBaby() : sel.value().equals("grown") && !f.isBaby()) out.add(f);
                }
            }
            default -> {
                String want = sel.value().toLowerCase(Locale.ROOT).replace('_', ' ');
                for (Villages.Village t : Villages.every()) {
                    for (VillageFolkEntity f : AdminTools.folk(t.id())) {
                        if (f.displayNameCap().toLowerCase(Locale.ROOT).equals(want) || f.getUUID().toString().startsWith(want)) out.add(f);
                    }
                }
            }
        }
        return out;
    }

    /** Folk names in a town, for tab completion. */
    public static List<String> folkNames(@Nullable Villages.Village v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        for (VillageFolkEntity f : AdminTools.folk(v.id())) {
            String n = f.displayNameCap();
            out.add(n.contains(" ") ? "\"" + n + "\"" : n);
            if (out.size() >= 60) break;
        }
        return out;
    }

    public static void resetForTests() {
        newest = null;
    }
}
