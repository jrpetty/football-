package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The morning news, for a player who asks for it (/village news on): once a morning, what
 * happened in the village yesterday — who was born, what went up, who came or went — and what
 * it is short of now, in a few lines of chat to a player within reach of it. Off unless asked
 * for, and it comes from the village's own chronicle.
 */
public final class News {

    private News() {}

    /** The player's choice, kept with the player (it survives a death and a restart). */
    private static final String KEY = "mc_assistant_morning_news";

    public static boolean wants(ServerPlayer p) {
        return p.getPersistentData().getBoolean(KEY);
    }

    public static void choose(ServerPlayer p, boolean on) {
        p.getPersistentData().putBoolean(KEY, on);
    }

    /** The morning's news of one village (Market.tick, once a day), to whoever asked for it. */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        List<ServerPlayer> readers = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (wants(p) && p.blockPosition().closerThan(v.centre(), 256)) readers.add(p);
        }
        if (readers.isEmpty()) return;
        UUID id = v.id();
        List<String> yesterday = new ArrayList<>();
        for (Chronicle.Entry e : Chronicle.of(id)) {
            if (e.day() == day - 1 || e.day() == day) yesterday.add(e.text());
        }
        List<Villages.Need> needs = Villages.needs(level, id);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Morning news from " + Villages.name(id) + " (" + Villages.headcount(id) + " folk, "
            + Villages.ageOf(id).label + ")").withStyle(ChatFormatting.GOLD));
        int shown = 0;
        for (int i = Math.max(0, yesterday.size() - 6); i < yesterday.size(); i++) {
            lines.add(Component.literal(" • " + capital(yesterday.get(i))).withStyle(ChatFormatting.GRAY));
            shown++;
        }
        if (shown == 0) lines.add(Component.literal(" • A quiet day.").withStyle(ChatFormatting.GRAY));
        if (!needs.isEmpty()) {
            StringBuilder sb = new StringBuilder(" Short of: ");
            for (int i = 0; i < Math.min(3, needs.size()); i++) sb.append(i == 0 ? "" : "; ").append(needs.get(i).what());
            lines.add(Component.literal(sb.toString()).withStyle(ChatFormatting.YELLOW));
        }
        for (ServerPlayer p : readers) for (Component c : lines) p.sendSystemMessage(c);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1) + (s.endsWith(".") ? "" : ".");
    }
}
