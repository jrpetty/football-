package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who is at war with whom, and how near to it a town stands.
 *
 * <p>[war] The shared seam of the wars between towns. Going to war and making peace (the council, the
 * ultimatum, the declaration, the treaty) belongs to the war-and-peace work; scouting, the town's
 * preparations and the fighting only ask it. A war is kept with the world on both towns' books (Ledger
 * notes "war/&lt;other&gt;", the day it began), so it outlasts a restart; until the war-and-peace work
 * fills in when a town stands on its guard short of war, a feud (Diplomacy.FEUD) reads as TENSION.
 */
public final class Wars {

    private Wars() {}

    /** How near to war a town stands: at peace; on its guard (a feud, an ultimatum, a neighbour arming); at war. */
    public enum Footing { PEACE, TENSION, WAR }

    /** Are these two towns at war with each other? */
    public static boolean atWar(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        String s = Ledger.note(a, "war/" + b);
        return s != null && !s.isEmpty();
    }

    /** The day the war between these two began, or -1 when they are at peace. */
    public static long since(UUID a, UUID b) {
        String s = a == null || b == null ? null : Ledger.note(a, "war/" + b);
        if (s == null || s.isEmpty()) return -1L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Every town this one is at war with. */
    public static List<UUID> enemies(UUID village) {
        List<UUID> out = new ArrayList<>();
        if (village == null) return out;
        for (Villages.Village v : Villages.every()) {
            if (!v.id().equals(village) && atWar(village, v.id())) out.add(v.id());
        }
        return out;
    }

    /** How near to war this town stands. */
    public static Footing footing(UUID village) {
        if (village == null) return Footing.PEACE;
        if (!enemies(village).isEmpty()) return Footing.WAR;
        for (Villages.Village v : Villages.every()) {
            if (!v.id().equals(village) && Ledger.relation(village, v.id()) <= Diplomacy.FEUD) return Footing.TENSION;
        }
        return Footing.PEACE;
    }

    /** War between these two from today, on both towns' books. */
    public static void begin(UUID a, UUID b, long day) {
        if (a == null || b == null || a.equals(b)) return;
        Ledger.note(a, "war/" + b, Long.toString(day));
        Ledger.note(b, "war/" + a, Long.toString(day));
    }

    /** Peace between these two, on both towns' books. */
    public static void end(UUID a, UUID b) {
        if (a == null || b == null) return;
        Ledger.note(a, "war/" + b, "");
        Ledger.note(b, "war/" + a, "");
    }
}
