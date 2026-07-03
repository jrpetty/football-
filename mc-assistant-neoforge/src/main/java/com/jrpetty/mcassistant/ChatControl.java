package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat control: the owner types "!follow", "!gather logs 16", "!status" in
 * normal chat and the assistant obeys — same command set as /assistant, but
 * conversational. Non-owners are ignored.
 */
public final class ChatControl {

    private static final Pattern GATHER = Pattern.compile(
        "^!(?:gather|get|mine|chop)\\s+([a-z]+)\\s*(\\d+)?", Pattern.CASE_INSENSITIVE);

    private ChatControl() {}

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        String raw = event.getMessage().getString().trim();
        if (!raw.startsWith("!")) return;
        ServerPlayer player = event.getPlayer();
        AssistantEntity a = AssistantCommands.findAssistant(player);
        if (a == null) return;

        // Never let one bad command permanently break command handling — every
        // future chat message re-enters this handler cleanly.
        try {
            dispatch(a, player, raw.toLowerCase());
        } catch (Exception ex) {
            a.say("Something went wrong with that one — try again.");
        }
    }

    private static void dispatch(AssistantEntity a, ServerPlayer player, String lower) {
        Matcher g = GATHER.matcher(lower);
        if (g.find()) {
            GatherGoal.Kind kind = GatherGoal.Kind.fromWord(g.group(1));
            if (kind == null) {
                a.say("I can gather: logs, stone, dirt.");
                return;
            }
            long amount = g.group(2) != null ? Long.parseLong(g.group(2)) : 8;
            int n = (int) Math.max(1, Math.min(64, amount));
            int before = a.jobCount();
            a.requestGather(kind, n); // appends to the queue
            if (before > 0) a.say("Queued: gather " + n + " " + kind.label + " (#" + a.jobCount() + " in line).");
            return;
        }

        if (lower.startsWith("!follow")) {
            a.clearQueue(); // a direct movement order takes over now
            a.setMode(AssistantEntity.Mode.FOLLOW);
            a.say("Following you.");
        } else if (lower.startsWith("!stop") || lower.startsWith("!halt") || lower.startsWith("!cancel")) {
            a.requestStop(); // clears the whole queue and holds
        } else if (lower.startsWith("!jobs") || lower.startsWith("!queue") || lower.startsWith("!tasks")) {
            reportJobs(a);
        } else if (lower.startsWith("!stay") || lower.startsWith("!wait")) {
            a.clearQueue();
            a.setMode(AssistantEntity.Mode.STAY);
            a.say("Holding here.");
        } else if (lower.startsWith("!guard") || lower.startsWith("!protect")) {
            a.clearQueue();
            a.setMode(AssistantEntity.Mode.GUARD);
            a.say("Guard mode on — I'll watch your back.");
        } else if (lower.startsWith("!come") || lower.startsWith("!here")) {
            // Come to me and keep following, rather than freezing on arrival.
            a.clearQueue();
            a.setMode(AssistantEntity.Mode.FOLLOW);
            a.getNavigation().moveTo(player, 1.25D);
            a.say("Coming.");
        } else if (lower.startsWith("!deposit") || lower.startsWith("!stash")) {
            int before = a.jobCount();
            a.requestDeposit(); // appends to the queue
            if (before > 0) a.say("Queued: deposit loot (#" + a.jobCount() + " in line).");
        } else if (lower.startsWith("!status")) {
            a.say(String.format("HP %.0f/%.0f, mode %s, carrying %d items, %d jobs queued.",
                a.getHealth(), a.getMaxHealth(), a.getMode().name().toLowerCase(), a.countItems(), a.jobCount()));
        } else if (lower.startsWith("!help")) {
            a.say("I know: !follow, !stay, !guard, !come, !stop, !gather <logs|stone|dirt> [n], !deposit, !jobs, !status. Gather/deposit orders QUEUE and run in sequence. Right-click me to open my inventory.");
        } else {
            // Always acknowledge, so you can tell I'm still listening.
            a.say("Didn't catch that — try !follow, !guard, !stop, !gather logs 16, !jobs, or right-click me.");
        }
    }

    private static void reportJobs(AssistantEntity a) {
        java.util.List<String> labels = a.jobLabels();
        if (labels.isEmpty()) {
            a.say("No jobs queued — I'm on " + a.getMode().name().toLowerCase() + ".");
            return;
        }
        StringBuilder sb = new StringBuilder("Jobs: ");
        for (int i = 0; i < labels.size(); i++) {
            sb.append(i + 1).append(") ").append(labels.get(i));
            if (i < labels.size() - 1) sb.append("  ");
        }
        a.say(sb.toString());
    }
}
