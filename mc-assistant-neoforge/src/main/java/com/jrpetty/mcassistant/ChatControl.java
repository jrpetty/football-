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

        String lower = raw.toLowerCase();

        Matcher g = GATHER.matcher(lower);
        if (g.find()) {
            GatherGoal.Kind kind = GatherGoal.Kind.fromWord(g.group(1));
            if (kind == null) {
                a.say("I can gather: logs, stone, dirt.");
                return;
            }
            int amount = g.group(2) != null ? Integer.parseInt(g.group(2)) : 8;
            a.requestGather(kind, amount);
            return;
        }

        if (lower.startsWith("!follow")) {
            a.setMode(AssistantEntity.Mode.FOLLOW);
            a.say("Following you.");
        } else if (lower.startsWith("!stay") || lower.startsWith("!stop") || lower.startsWith("!halt")) {
            a.setMode(AssistantEntity.Mode.STAY);
            a.say("Holding here.");
        } else if (lower.startsWith("!guard") || lower.startsWith("!protect")) {
            a.setMode(AssistantEntity.Mode.GUARD);
            a.say("Guard mode on — I'll watch your back.");
        } else if (lower.startsWith("!come") || lower.startsWith("!here")) {
            a.setMode(AssistantEntity.Mode.STAY);
            a.getNavigation().moveTo(player, 1.25D);
            a.say("Coming.");
        } else if (lower.startsWith("!deposit") || lower.startsWith("!stash")) {
            a.requestDeposit();
        } else if (lower.startsWith("!status")) {
            a.say(String.format("HP %.0f/20, mode %s, carrying %d items.",
                a.getHealth(), a.getMode().name().toLowerCase(), a.countItems()));
        } else if (lower.startsWith("!help")) {
            a.say("I know: !follow, !stay, !guard, !come, !gather <logs|stone|dirt> [n], !deposit, !status.");
        }
    }
}
