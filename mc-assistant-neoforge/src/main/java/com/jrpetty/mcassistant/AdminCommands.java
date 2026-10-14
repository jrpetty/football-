package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.admin.AdminAccess;
import com.jrpetty.mcassistant.admin.AdminConfig;
import com.jrpetty.mcassistant.admin.AdminConfirm;
import com.jrpetty.mcassistant.admin.AdminJobs;
import com.jrpetty.mcassistant.admin.AdminLog;
import com.jrpetty.mcassistant.admin.AdminPanel;
import com.jrpetty.mcassistant.admin.AdminPresets;
import com.jrpetty.mcassistant.admin.AdminRunner;
import com.jrpetty.mcassistant.admin.AdminScripts;
import com.jrpetty.mcassistant.admin.AdminSnapshots;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.CityTree;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.AdminArgs;
import com.jrpetty.mcassistant.village.AdminSpec;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * [admin] /village admin: the admin toolkit's commands, built from its one list of actions (village/AdminSpec), so the
 * words, the help, the tab completion and the panel never disagree. Every action is
 * {@code /village admin <area> <action> [values] [town:<name|id|nearest|all|newest>] [folk:<name|uuid|@trade|@all>]
 * [--dry-run] [--snapshot]}; and the toolkit's own words: help, log, jobs, cancel, confirm, panel.
 *
 * <p>From the console and RCON the answers are plain lines, a keyword first (ADMIN, TOWN, JOB, CONFIRM, DRY-RUN...) so a
 * script can read them; a player gets them coloured, a town clickable to go there, and a link to the panel.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class AdminCommands {

    private AdminCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin").requires(src -> AdminConfig.enabled());
        // Each action under its words: the first word a node of its own, the second (if any) under it.
        Map<String, LiteralArgumentBuilder<CommandSourceStack>> heads = new LinkedHashMap<>();
        for (AdminSpec.Action a : AdminSpec.all()) {
            String[] words = a.path().split(" ");
            LiteralArgumentBuilder<CommandSourceStack> head = heads.computeIfAbsent(words[0], Commands::literal);
            LiteralArgumentBuilder<CommandSourceStack> leaf = words.length == 1 ? head : Commands.literal(words[1]);
            leaf.executes(ctx -> run(ctx, a, ""))
                .then(Commands.argument("values", StringArgumentType.greedyString())
                    .suggests((ctx, b) -> suggest(ctx, a, b))
                    .executes(ctx -> run(ctx, a, StringArgumentType.getString(ctx, "values"))));
            if (words.length > 1) head.then(leaf);
        }
        for (LiteralArgumentBuilder<CommandSourceStack> h : heads.values()) admin.then(h);
        admin.then(Commands.literal("help").executes(ctx -> help(ctx, ""))
                .then(Commands.argument("area", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(areaKeys(), b))
                    .executes(ctx -> help(ctx, StringArgumentType.getString(ctx, "area")))))
            .then(Commands.literal("log").executes(ctx -> log(ctx, 10))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, 200)).executes(ctx -> log(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
            .then(Commands.literal("jobs").executes(AdminCommands::jobs))
            .then(Commands.literal("cancel").then(Commands.argument("job", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(AdminJobs.running().stream().map(AdminJobs.Job::id).toList(), b))
                .executes(ctx -> cancel(ctx, StringArgumentType.getString(ctx, "job")))))
            .then(Commands.literal("confirm").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> reply(ctx.getSource(), "confirm", AdminConfirm.confirm(ctx.getSource(), StringArgumentType.getString(ctx, "id"))))))
            .then(Commands.literal("panel").executes(ctx -> panel(ctx, ""))
                .then(Commands.argument("area", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(areaKeys(), b))
                    .executes(ctx -> panel(ctx, StringArgumentType.getString(ctx, "area")))));
        event.getDispatcher().register(Commands.literal("village").then(admin));
    }

    private static List<String> areaKeys() {
        List<String> out = new ArrayList<>();
        for (AdminSpec.Area a : AdminSpec.Area.values()) out.add(a.key());
        return out;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, AdminSpec.Action a, String values) {
        return reply(ctx.getSource(), a.path(), AdminRunner.run(ctx.getSource(), a.path(), values));
    }

    // ------------------------------------------------------------------ answers

    /** Say an outcome: plain parseable lines to the console, coloured and clickable to a player. */
    public static int reply(CommandSourceStack src, String path, AdminRunner.Outcome o) {
        boolean console = src.getPlayer() == null;
        if (console) {
            src.sendSuccess(() -> Component.literal("ADMIN " + (o.ok() ? "ok " : "fail ") + path), false);
            for (String l : o.lines()) src.sendSuccess(() -> Component.literal(l), false);
            return o.ok() ? 1 : 0;
        }
        boolean first = true;
        for (String l : o.lines()) {
            MutableComponent line = Component.literal(first ? "[admin] " : "  ").withStyle(ChatFormatting.DARK_GRAY);
            ChatFormatting ink = !o.ok() || l.startsWith("NO ") ? ChatFormatting.RED : l.startsWith("CONFIRM") || l.startsWith("WILL")
                ? ChatFormatting.GOLD : l.startsWith("DRY-RUN") ? ChatFormatting.AQUA : first ? ChatFormatting.GREEN : ChatFormatting.GRAY;
            line.append(Component.literal(l).withStyle(ink));
            if (l.startsWith("TOWN ")) line.append(chip(" [go there]", "/village admin world tp " + l.substring(5, l.indexOf(' ', 5) < 0 ? l.length() : l.indexOf(' ', 5)), "Teleport to the town"));
            if (l.startsWith("CONFIRM ")) line.append(chip(" [confirm]", l.substring(8, l.indexOf(" within") < 0 ? l.length() : l.indexOf(" within")), "Go ahead"));
            if (l.startsWith("JOB ")) line.append(chip(" [cancel]", "/village admin cancel " + l.substring(4, 8), "Stop this job"));
            src.sendSystemMessage(line);
            first = false;
        }
        AdminSpec.Action a = AdminSpec.byPath(path);
        if (a != null) src.sendSystemMessage(chip("  [open the " + a.area().title + " panel]", "/village admin panel " + a.area().key(), "The admin panel"));
        return o.ok() ? 1 : 0;
    }

    private static MutableComponent chip(String text, String command, String hover) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover + ": " + command))));
    }

    /** /village admin help [area]: the areas, or one area's actions with their usage and an example. */
    private static int help(CommandContext<CommandSourceStack> ctx, String area) {
        CommandSourceStack src = ctx.getSource();
        List<String> lines = new ArrayList<>();
        AdminSpec.Area a = AdminSpec.Area.byKey(area);
        if (a == null) {
            lines.add("The admin toolkit: /village admin <area> <action> [values] [town:<name|all>] [folk:<name|@trade|@all>] [--dry-run] [--snapshot]");
            for (AdminSpec.Area x : AdminSpec.Area.values()) {
                lines.add(x.key() + ": " + x.blurb + (AdminAccess.refusal(src, x) == null ? "" : " (not yours)"));
            }
            lines.add("Also: help <area>, log [n], jobs, cancel <job>, confirm <id>, panel [area]. Big actions run as jobs; destructive ones ask to confirm.");
        } else {
            lines.add(a.title + " (" + a.blurb + "):");
            for (AdminSpec.Action x : AdminSpec.of(a)) {
                lines.add(x.usage() + (x.destructive() ? "  [confirm]" : x.big() ? "  [job]" : "") + " - " + x.summary() + " e.g. /village admin " + x.example());
            }
        }
        return reply(src, "help", AdminRunner.Outcome.ok(lines));
    }

    private static int log(CommandContext<CommandSourceStack> ctx, int n) {
        String refused = AdminAccess.refusal(ctx.getSource(), AdminSpec.Area.TOWN);
        if (refused != null) return reply(ctx.getSource(), "log", AdminRunner.Outcome.fail(refused));
        List<String> lines = AdminLog.last(n);
        return reply(ctx.getSource(), "log", AdminRunner.Outcome.ok(lines.isEmpty() ? List.of("Nothing in the admin log yet.") : lines));
    }

    private static int jobs(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = new ArrayList<>();
        for (AdminJobs.Job j : AdminJobs.running()) lines.add("JOB " + j.line());
        return reply(ctx.getSource(), "jobs", AdminRunner.Outcome.ok(lines.isEmpty() ? List.of("No admin jobs running.") : lines));
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx, String id) {
        String refused = AdminAccess.refusal(ctx.getSource(), AdminSpec.Area.TOWN);
        if (refused != null) return reply(ctx.getSource(), "cancel", AdminRunner.Outcome.fail(refused));
        boolean ok = AdminJobs.cancel(id);
        AdminLog.append(ctx.getSource().getServer(), AdminAccess.who(ctx.getSource()), "cancel " + id, "-", ok ? "cancelled" : "no such job");
        return reply(ctx.getSource(), "cancel", ok ? AdminRunner.Outcome.ok(List.of("Job " + id + " will stop at its next slice; what it did stays done."))
            : AdminRunner.Outcome.fail("No admin job " + id + " is running."));
    }

    private static int panel(CommandContext<CommandSourceStack> ctx, String area) {
        CommandSourceStack src = ctx.getSource();
        if (src.getPlayer() == null) return reply(src, "panel", AdminRunner.Outcome.fail("The panel opens on a player's screen."));
        if (!AdminAccess.anyArea(src.getPlayer())) return reply(src, "panel", AdminRunner.Outcome.fail("Only an operator may open the admin panel."));
        AdminPanel.open(src.getPlayer(), area, "");
        return 1;
    }

    // ------------------------------------------------------------------ tab completion

    /** Suggest the next value: a selector's names, a flag, or what the next parameter takes. */
    static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, AdminSpec.Action a, SuggestionsBuilder b) {
        String typed = b.getRemaining();
        int cut = typed.lastIndexOf(' ') + 1;
        String last = typed.substring(cut);
        List<String> done = AdminArgs.tokens(typed.substring(0, cut));
        SuggestionsBuilder at = b.createOffset(b.getStart() + cut);
        String low = last.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (low.startsWith("town:")) {
            out.add("town:nearest");
            out.add("town:all");
            out.add("town:newest");
            for (String n : AdminRunner.townNames()) out.add("town:" + n);
        } else if (low.startsWith("folk:")) {
            out.add("folk:@all");
            for (String t : AdminTools.tradeWords()) out.add("folk:@" + t);
            Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
            for (String n : AdminRunner.folkNames(v)) out.add("folk:" + n);
        } else if (low.startsWith("-")) {
            out.add(AdminSpec.DRY_RUN);
            if (a.big()) out.add(AdminSpec.SNAPSHOT);
        } else {
            int index = 0;
            for (String d : done) if (!d.startsWith("--") && !d.toLowerCase(Locale.ROOT).startsWith("town:") && !d.toLowerCase(Locale.ROOT).startsWith("folk:")) index++;
            AdminSpec.Param p = a.param(index);
            if (p != null) out.addAll(values(ctx.getSource(), a, p, low));
            if (a.scope() != AdminSpec.Scope.WORLD) out.add("town:");
            if (a.scope() == AdminSpec.Scope.FOLK) out.add("folk:");
            out.add(AdminSpec.DRY_RUN);
        }
        return SharedSuggestionProvider.suggest(out, at);
    }

    private static List<String> values(CommandSourceStack src, AdminSpec.Action a, AdminSpec.Param p, String typed) {
        return switch (p.kind()) {
            case INT -> List.of(p.fallback(), Integer.toString(p.min()), Integer.toString(p.max()));
            case WORD -> p.choices();
            case AGE -> AdminSpec.AGES;
            case TIER -> AdminSpec.TIERS;
            case PHASE -> AdminSpec.PHASES;
            case SCENARIO -> AdminSpec.SCENARIOS;
            case EVENT -> AdminSpec.EVENTS;
            case TRADE -> AdminTools.tradeWords();
            case TOWN -> {
                List<String> t = new ArrayList<>(AdminRunner.townNames());
                t.add("rival");
                yield t;
            }
            case CIVIC -> {
                List<String> keys = new ArrayList<>();
                for (CityTree.Civic c : CityTree.Civic.values()) keys.add(c.key());
                yield keys;
            }
            case STRUCTURE -> Showcase.ORDER;
            case PLAYER -> List.of(src.getServer().getPlayerNames());
            case ITEM -> {
                List<String> items = new ArrayList<>();
                for (var id : BuiltInRegistries.ITEM.keySet()) {
                    if (items.size() >= 60) break;
                    if (id.getNamespace().equals("minecraft") ? id.getPath().startsWith(typed) : id.toString().startsWith(typed)) {
                        items.add(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
                    }
                }
                yield items;
            }
            case FILE -> switch (a.id()) {
                case "run" -> AdminScripts.names(src.getServer());
                case "restore" -> AdminSnapshots.names(src.getServer());
                default -> AdminPresets.names(src.getServer());
            };
            case TEXT -> {
                List<String> t = new ArrayList<>(AdminRunner.townNames());
                for (String s : Showcase.ORDER) t.add("building " + s);
                yield t;
            }
            default -> List.of();
        };
    }
}
