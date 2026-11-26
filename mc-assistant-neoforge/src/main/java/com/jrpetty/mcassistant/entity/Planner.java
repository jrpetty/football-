package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpa] The personal planner's one way in: every package puts the player's coming events here (a shift, an interview,
 * jury duty, a vote, a bill due), each with when, its kind, a line and where. WP-D draws it (the planner screen and the
 * diary); until then this keeps the entries, so the hooks are in place and the screen has something to show the day it
 * lands. A stub kept deliberately small: entries live while the server runs, the soonest first, at most a few dozen a
 * player, and any older than a day are dropped.
 */
public final class Planner {

    private Planner() {}

    /** One entry: the game day and time of day (ticks into the day), its kind ("jury", "vote", "tax"), the words, where. */
    public record Entry(long day, long time, String kind, String text, @Nullable BlockPos pos) {}

    private static final int MOST = 48;
    private static final Map<UUID, List<Entry>> ENTRIES = new ConcurrentHashMap<>();

    /**
     * The one API: put an event on the player's planner. {@code when} is the game time of day in absolute ticks
     * (day * 24000 + time). The same kind and text on the same day is kept once.
     */
    public static void add(ServerPlayer player, long when, String kind, String text, @Nullable BlockPos pos) {
        add(player.getUUID(), when, kind, text, pos);
    }

    /** [wpb] By day and hour (0-23, the clock's hour; day time 0 is six in the morning). */
    public static void add(ServerPlayer player, long day, int hour, String kind, String text, @Nullable BlockPos pos) {
        add(player.getUUID(), day * 24000L + (long) Math.floorMod(hour - 6, 24) * 1000L, kind, text, pos);
    }

    public static void add(UUID player, long when, String kind, String text, @Nullable BlockPos pos) {
        long day = Math.max(0, when / 24000L), time = Math.floorMod(when, 24000L);
        List<Entry> l = ENTRIES.computeIfAbsent(player, k -> new ArrayList<>());
        synchronized (l) {
            l.removeIf(e -> e.day() == day && e.kind().equals(kind) && e.text().equals(text));
            l.add(new Entry(day, time, kind, text, pos == null ? null : pos.immutable()));
            l.sort((a, b) -> a.day() != b.day() ? Long.compare(a.day(), b.day()) : Long.compare(a.time(), b.time()));
            while (l.size() > MOST) l.remove(l.size() - 1);
        }
    }

    /** The player's entries from {@code today} on, soonest first (the planner screen, the tests). */
    public static List<Entry> of(UUID player, long today) {
        List<Entry> l = ENTRIES.get(player);
        if (l == null) return List.of();
        synchronized (l) {
            l.removeIf(e -> e.day() < today - 1);
            return List.copyOf(l);
        }
    }

    public static void resetForTests(UUID player) {
        ENTRIES.remove(player);
    }
}
