package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [police] The watch's roster: who does what today.
 *
 * <p>The guards are the town's defence and its police both, one force on one roster. Every morning the captain (the
 * town's constable, once it has one, else the most seasoned guard) draws it up, and the board, the watch house's
 * notice board, the Watch page and each guard's card show it:
 * <ul>
 * <li><b>wall and gate</b> (defence): a gate by day, a post on the wall by night;</li>
 * <li><b>beat patrol</b>: a set route through the town's quarters, where the trouble is at that hour;</li>
 * <li><b>the station desk</b>: the watch house's front desk, its cells and its casebook;</li>
 * <li><b>investigation</b>: the cases (Inquiry gives them to whoever has it before anybody else);</li>
 * <li><b>escort and court</b>: prisoners to the cells, to the council and back, and the caravans out of town;</li>
 * <li><b>event duty</b>: a gathering's crowd, the stores on market day, the bank at night when it holds a lot;</li>
 * <li><b>rest</b>: a day off.</li>
 * </ul>
 * The day's duties are put in a list, defence and policing turn about, and each guard takes the one its place on the
 * watch and the day bring round: so the list turns a place a day, and in a week every guard has stood on the walls and
 * walked a beat. The constable keeps to the watch house's own work, the cases and the desk, but takes its turn on the
 * walls one day in seven like the rest. Raids and emergencies override the lot: with the bell ringing every guard is
 * on the walls, and a fire or a flood takes every guard who is not already at it.
 */
public final class Roster {

    private Roster() {}

    /** What a guard is on today. The first is defence; the rest but REST are policing. */
    public enum Duty {
        WALLS("wall and gate", "W", true),
        BEAT("beat patrol", "B", false),
        DESK("the station desk", "D", false),
        CASES("investigation", "I", false),
        ESCORT("escort and court", "E", false),
        EVENT("event duty", "V", false),
        REST("rest", "R", false),
        /** Not drawn on a roster: what the bell, a fire or a flood makes of everybody's duty. */
        BELL("the walls: the bell", "!", true),
        FIRE("the fire", "F", false),
        FLOOD("the flood", "L", false);

        public final String words;
        /** A letter for the Watch page's grid. */
        public final String letter;
        public final boolean defence;

        Duty(String words, String letter, boolean defence) {
            this.words = words;
            this.letter = letter;
            this.defence = defence;
        }

        /** Policing: the beat, the desk, the cases, the escort and the events. */
        public boolean policing() {
            return this == BEAT || this == DESK || this == CASES || this == ESCORT || this == EVENT;
        }

        static Duty named(String s) {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return BEAT;
            }
        }
    }

    // ------------------------------------------------------------------ the captain

    /** The captain: the constable if the town has one, else the most seasoned guard (the first by its id on a tie). */
    @Nullable
    static VillageFolkEntity captain(UUID village) {
        VillageFolkEntity constable = Inquiry.constable(village);
        if (constable != null) return constable;
        VillageFolkEntity best = null;
        for (VillageFolkEntity g : Patrols.watch(village)) {
            if (best == null || g.veteranLevel() > best.veteranLevel()) best = g;
        }
        return best;
    }

    // ------------------------------------------------------------------ the day's list

    /**
     * The day's duties, one for each guard, defence and policing turn about: a wall for every guard in three (one at
     * least), a beat for every two, and the desk, the cases, the escort and the events as the day wants them. With a
     * watch of four or more one guard rests.
     */
    static List<Duty> duties(ServerLevel level, Villages.Village v, int n, long day) {
        List<Duty> out = new ArrayList<>();
        if (n <= 0) return out;
        UUID id = v.id();
        if (n == 1) {
            // A watch of one: the walls one day, the streets the next.
            out.add(day % 2 == 0 ? Duty.WALLS : Duty.BEAT);
            return out;
        }
        boolean house = WatchHouse.of(id) != null;
        boolean cases = !Crime.open(id).isEmpty() || Inquiry.constable(id) != null;
        boolean prisoners = WatchHouse.count(id) > 0 || awaitingTrial(id);
        boolean event = Market.marketDay(id, day) || Elections.countsToday(id, day) || Referendums.gatheringDue(id, day)
            || Festivals.today(id, day) != null || FoundingDay.due(id, day) || Bank.cash(id) >= Police.BANK_GUARD;
        List<Duty> policing = new ArrayList<>();
        policing.add(Duty.BEAT);
        if (house) policing.add(Duty.DESK);
        if (cases) policing.add(Duty.CASES);
        if (prisoners) policing.add(Duty.ESCORT);
        if (event) policing.add(Duty.EVENT);
        int rest = n >= 4 ? 1 : 0;
        int working = n - rest;
        int walls = Math.max(1, working / 3);
        // Policing fills what is left, the beats doubling up in a big watch.
        List<Duty> police = new ArrayList<>();
        int k = 0;
        while (walls + police.size() < working) {
            police.add(k < policing.size() ? policing.get(k) : Duty.BEAT);
            k++;
        }
        // Turn about: a wall, a policing duty, a wall... so that any two days running a guard has had one of each.
        List<Duty> defence = new ArrayList<>();
        for (int i = 0; i < walls; i++) defence.add(Duty.WALLS);
        int d = 0, p = 0;
        while (d < defence.size() || p < police.size()) {
            if (d < defence.size()) out.add(defence.get(d++));
            if (p < police.size()) out.add(police.get(p++));
            // Two policing for one wall in a big watch: the second straight after.
            if (p < police.size() && police.size() - p > defence.size() - d) out.add(police.get(p++));
        }
        if (rest > 0) out.add(out.size() / 2, Duty.REST);
        return out;
    }

    /** Is anybody of the town awaiting trial (accused, before the council or not)? */
    static boolean awaitingTrial(UUID village) {
        for (Crime.Case c : Crime.open(village)) {
            if (c.stage == Crime.Stage.ACCUSED || c.stage == Crime.Stage.TRIAL) return true;
        }
        return false;
    }

    /**
     * The roster for the day: the watch in its fixed order (by id), each guard the duty its place and the day bring
     * round. The constable takes the cases (else the desk) six days in seven and the walls the seventh, its place in
     * the list going to whoever the turn would have given it to.
     */
    static Map<UUID, Duty> draw(ServerLevel level, Villages.Village v, long day) {
        Map<UUID, Duty> out = new LinkedHashMap<>();
        List<VillageFolkEntity> watch = new ArrayList<>(Patrols.watch(v.id()));
        watch.sort(Comparator.comparing(net.minecraft.world.entity.Entity::getUUID));
        if (watch.isEmpty()) return out;
        VillageFolkEntity constable = Inquiry.constable(v.id());
        List<VillageFolkEntity> rota = new ArrayList<>(watch);
        Duty constableDuty = null;
        if (constable != null && watch.size() >= 3) {
            rota.remove(constable);
            int turn = Math.floorMod(constable.getUUID().hashCode(), 7);
            boolean house = WatchHouse.of(v.id()) != null;
            constableDuty = Math.floorMod(day, 7) == turn ? Duty.WALLS : !Crime.open(v.id()).isEmpty() || !house ? Duty.CASES : Duty.DESK;
        }
        List<Duty> list = duties(level, v, rota.size(), day);
        if (constableDuty != null) {
            // The constable's own duty is not dealt again: the list's copy of it goes to a beat (or, the constable on the
            // walls today, the list's first wall goes to the beat instead).
            int same = constableDuty == Duty.WALLS ? -1 : list.indexOf(constableDuty);   // on the walls, it is one more there
            if (same >= 0) list.set(same, Duty.BEAT);
        }
        int n = rota.size();
        for (int i = 0; i < n; i++) out.put(rota.get(i).getUUID(), list.get(Math.floorMod(i + day, n)));
        if (constable != null && constableDuty != null) out.put(constable.getUUID(), constableDuty);
        return out;
    }

    // ------------------------------------------------------------------ kept with the world

    /** Today's roster, as last read, by town: looked at again a second later at most (a guard's shift asks it often). */
    private record Kept(long day, long at, Map<UUID, Duty> roster) {}

    private static final Map<UUID, Kept> KEPT = new ConcurrentHashMap<>();

    /** The roster read afresh next time it is asked for (it was changed by hand: a command, a test). */
    static void forget(UUID village) {
        KEPT.remove(village);
    }

    static void resetForTests() {
        KEPT.clear();
    }

    /** Today's roster as kept (Police's town record), drawn afresh if it is not today's or the watch has changed. */
    static Map<UUID, Duty> today(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L, gt = level.getGameTime();
        Kept k = KEPT.get(v.id());
        if (k != null && k.day() == day && gt >= k.at() && gt - k.at() < 20) return k.roster();
        Map<UUID, Duty> r = java.util.Collections.unmodifiableMap(readOrDraw(level, v, day));
        KEPT.put(v.id(), new Kept(day, gt, r));
        return r;
    }

    private static Map<UUID, Duty> readOrDraw(ServerLevel level, Villages.Village v, long day) {
        CompoundTag t = Police.town(v.id());
        CompoundTag r = t.getCompound("roster");
        List<VillageFolkEntity> watch = Patrols.watch(v.id());
        boolean same = t.getLong("rosterDay") == day && t.contains("roster") && r.size() == watch.size();
        if (same) for (VillageFolkEntity g : watch) if (!r.contains(g.getStringUUID())) { same = false; break; }
        if (!same) {
            Map<UUID, Duty> drawn = draw(level, v, day);
            CompoundTag nr = new CompoundTag(), names = new CompoundTag();
            for (Map.Entry<UUID, Duty> e : drawn.entrySet()) nr.putString(e.getKey().toString(), e.getValue().name());
            for (VillageFolkEntity g : watch) names.putString(g.getStringUUID(), g.displayNameCap());
            boolean fresh = t.getLong("rosterDay") != day || !t.contains("roster");
            t.putLong("rosterDay", day);
            t.put("roster", nr);
            t.put("rosterNames", names);
            VillageFolkEntity captain = captain(v.id());
            if (captain != null) {
                t.putUUID("captain", captain.getUUID());
                t.putString("captainName", captain.displayNameCap());
            } else {
                t.remove("captain");
                t.putString("captainName", "");
            }
            // The week's rosters, for the Watch page's grid: seven days kept.
            CompoundTag week = t.getCompound("week");
            week.put(Long.toString(day), nr.copy());
            for (String key : new ArrayList<>(week.getAllKeys())) {
                try {
                    if (day - Long.parseLong(key) > 6) week.remove(key);
                } catch (NumberFormatException e) {
                    week.remove(key);
                }
            }
            t.put("week", week);
            Police.changed();
            if (fresh && captain != null && !drawn.isEmpty()) announce(level, v, captain, drawn, day);
            return drawn;
        }
        Map<UUID, Duty> out = new LinkedHashMap<>();
        for (String key : r.getAllKeys()) {
            try {
                out.put(UUID.fromString(key), Duty.named(r.getString(key)));
            } catch (IllegalArgumentException ignored) {
                // a hand-edited roster: the line is left out
            }
        }
        return out;
    }

    /** The captain posts the day's roster: a word for the watch, and the chronicle has a new captain's first. */
    private static void announce(ServerLevel level, Villages.Village v, VillageFolkEntity captain, Map<UUID, Duty> drawn, long day) {
        if (captain.isSleeping() || !captain.isAlive()) return;
        Duty mine = drawn.get(captain.getUUID());
        FolkTalk.speak(captain, FolkTalk.pick(captain.getRandom(), "The roster's up. Read it and get to it.",
            "Today's roster is on the board. " + (mine == null ? "" : "I'm on " + mine.words + "."),
            "Right, the watch: the roster's posted. Walls first, then the streets."));
    }

    /** A guard's duty today: the override first (the bell, a fire, a flood), else the roster's. Null for no guard of the watch. */
    @Nullable
    static Duty dutyOf(ServerLevel level, VillageFolkEntity g) {
        UUID id = g.ownerId();
        if (id == null || g.stationTask() != AssistantEntity.StationTask.GUARD || g.isBaby() || g.isHired()) return null;
        if (Raids.underAlarm(id)) return Duty.BELL;
        Duty emergency = Incidents.emergency(id);
        if (emergency != null) return emergency;
        Villages.Village v = Villages.get(id);
        if (v == null) return null;
        Duty d = today(level, v).get(g.getUUID());
        return d == null ? Duty.BEAT : d;
    }

    /** The guards on a duty today (the roster's, not the override's), in the roster's order. */
    static List<VillageFolkEntity> on(ServerLevel level, Villages.Village v, Duty d) {
        List<VillageFolkEntity> out = new ArrayList<>();
        Map<UUID, Duty> r = today(level, v);
        for (VillageFolkEntity g : Patrols.watch(v.id())) if (r.get(g.getUUID()) == d) out.add(g);
        return out;
    }

    /** The roster in words, for the board and the commands: "walls Bram; beat Ash, Rook; desk Wren; rest Moss". */
    static String words(ServerLevel level, Villages.Village v) {
        Map<UUID, Duty> r = today(level, v);
        CompoundTag names = Police.town(v.id()).getCompound("rosterNames");
        Map<Duty, List<String>> by = new LinkedHashMap<>();
        for (Duty d : Duty.values()) by.put(d, new ArrayList<>());
        for (Map.Entry<UUID, Duty> e : r.entrySet()) {
            String n = names.getString(e.getKey().toString());
            by.get(e.getValue()).add(n.isEmpty() ? "a guard" : n);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Duty, List<String>> e : by.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            parts.add(short_(e.getKey()) + " " + String.join(", ", e.getValue()));
        }
        return String.join("; ", parts);
    }

    /** A duty in a word for the board. */
    static String short_(Duty d) {
        return switch (d) {
            case WALLS -> "walls";
            case BEAT -> "beat";
            case DESK -> "desk";
            case CASES -> "cases";
            case ESCORT -> "escort";
            case EVENT -> "events";
            case REST -> "resting";
            case BELL -> "the bell";
            case FIRE -> "the fire";
            case FLOOD -> "the flood";
        };
    }

    /** The last seven days' rosters, by day: each guard's duty letter. For the Watch page's grid. */
    static CompoundTag week(UUID village) {
        CompoundTag out = new CompoundTag();
        CompoundTag week = Police.town(village).getCompound("week");
        for (String day : week.getAllKeys()) {
            CompoundTag r = week.getCompound(day), letters = new CompoundTag();
            for (String g : r.getAllKeys()) letters.putString(g, Duty.named(r.getString(g)).letter);
            out.put(day, letters);
        }
        return out;
    }

    /** Has this guard had both kinds of duty in the week kept? (The Watch page's tick, and the tests.) */
    static boolean bothKinds(UUID village, UUID guard) {
        boolean defence = false, police = false;
        CompoundTag week = Police.town(village).getCompound("week");
        for (String day : week.getAllKeys()) {
            CompoundTag r = week.getCompound(day);
            if (!r.contains(guard.toString(), Tag.TAG_STRING)) continue;
            Duty d = Duty.named(r.getString(guard.toString()));
            if (d.defence) defence = true;
            if (d.policing()) police = true;
        }
        return defence && police;
    }
}
