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
            a.requestGather(kind, (int) Math.max(1, Math.min(64, amount)));
            return;
        }

        if (lower.startsWith("!follow")) {
            a.cancelTasks(); // a new order supersedes a running gather/deposit
            a.setMode(AssistantEntity.Mode.FOLLOW);
            a.say("Following you.");
        } else if (lower.startsWith("!stop") || lower.startsWith("!halt") || lower.startsWith("!cancel")) {
            a.requestStop(); // cancels the current task too, not just future movement
        } else if (lower.startsWith("!stay") || lower.startsWith("!wait")) {
            a.cancelTasks();
            a.setMode(AssistantEntity.Mode.STAY);
            a.say("Holding here.");
        } else if (lower.startsWith("!guard") || lower.startsWith("!protect")) {
            a.cancelTasks();
            a.setMode(AssistantEntity.Mode.GUARD);
            a.say("Guard mode on — I'll watch your back.");
        } else if (lower.startsWith("!come") || lower.startsWith("!here")) {
            // Come to me and keep following, rather than freezing on arrival.
            a.cancelTasks();
            a.setMode(AssistantEntity.Mode.FOLLOW);
            a.getNavigation().moveTo(player, 1.25D);
            a.say("Coming.");
        } else if (lower.startsWith("!deposit") || lower.startsWith("!stash")) {
            a.requestDeposit();
        } else if (lower.startsWith("!status")) {
            a.say(String.format("HP %.0f/20, mode %s, carrying %d items.",
                a.getHealth(), a.getMode().name().toLowerCase(), a.countItems()));
        } else if (lower.startsWith("!help")) {
            a.say("I know: !follow, !stay, !guard, !come, !stop, !gather <logs|stone|dirt> [n], !deposit, !status. Right-click me to open my inventory.");
        } else {
            // Always acknowledge, so you can tell I'm still listening.
            a.say("Didn't catch that — try !follow, !guard, !stop, !gather logs 16, or right-click me.");
        }
    }
}
