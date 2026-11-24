package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [pa33] The Player AI Assistants' own books, kept with the world (data/mc_assistant_assistants.dat) and keyed by the
 * owning player's UUID. Nothing here is the villages' saved data. Each record is one assistant: who owns it, its
 * name, look, voice and personality, its owner's settings for it, its spawner, whether it is fallen (and till when),
 * the conversation, and a copy of the assistant itself while it is out of the world (its owner logged out, or it is
 * crossing to another dimension, or it is waiting to come back).
 */
public final class PaData extends SavedData {

    public static final String ID = "mc_assistant_assistants";

    /** One line of the conversation: who said it (0 the player, 1 the assistant, 2 a note), and when. */
    /** A line of talk; times is how often it was said running (shown "×2"); greet marks a hello. [pa33b] */
    public record Line(int who, String text, long time, int times, boolean greet) {
        public Line(int who, String text, long time) { this(who, text, time, 1, false); }
    }

    public static final class Rec {
        public UUID owner;
        public String ownerName = "";
        public int slot;
        /** Which incarnation is the real one: a copy left in a chunk from before (a crash, a server stop) is sent away. */
        public long incarnation;
        @Nullable public UUID entity;
        public String name = "";
        public int look, voice, personality;
        // the owner's settings
        public boolean autoHelp = true, useChests = true, breakNearBuilds = false, confirm = true, sleepAtNight = false;
        public int roam = 32, chatty = 2, spend = 64;
        /** [pa33b] Its place in the personality triangle (funny, gloomy; serious is the rest): Banter to start. */
        public float funny = (float) PaPersona.DEFAULT_FUNNY, gloomy = (float) PaPersona.DEFAULT_GLOOMY;
        /** [pa33b] Told to hush: 0 talking, 1 no jokes (plain words), 2 quiet (answers and warnings only). Kept until lifted. */
        public int mute;
        /** [pa33b] Match my energy: its language back (0 Clean, 1 Cheeky, 2 Unfiltered; never past the server cap), roasting others back (0 On, 1 only when they insult it, 2 Off), defending its player. */
        public int language, roastBack;
        public boolean defend = true;
        /** [pa35] Ping an ore it may not mine yet when it meets one (Settings, Behaviour). */
        public boolean pingLocked = true;
        /** [pa33c-2b] Its beds: the one it was told to use, and the last it slept in (where it comes back after a fall). */
        @Nullable public BlockPos bed, lastBed;
        public String bedDim = "", lastBedDim = "";
        /** [pa33c-2b] Named places ("the mine", "base"): name -> spot (with its world). */
        public final java.util.Map<String, BlockPos> waypoints = new java.util.LinkedHashMap<>();
        public final java.util.Map<String, String> waypointDims = new java.util.HashMap<>();
        /** [pa33c-2b] Items it keeps (locked in its pack): never handed over by "Give me everything" or dumped. */
        public final java.util.Set<String> keep = new java.util.LinkedHashSet<>();
        /** [pa33b] When its player last left (wall clock), for "you've been gone ages". */
        public long leftAt;
        /** [pa33d] Its experience, kept here too: a body stood up afresh (a lost copy) keeps its level. */
        public long xp;
        /** [pa33e] When it was first found missing from the world (not saved; -1 when it isn't). */
        public long missingSince = -1;

        public PaPersona.Tri tri() {
            return PaPersona.Tri.of(funny, gloomy);
        }
        // places
        @Nullable public BlockPos spawner;
        public String spawnerDim = "";
        // life and death
        public long deadUntil = -1, summonReadyAt = 0, made;
        public String lastDeath = "";
        @Nullable public CompoundTag snapshot;
        public boolean away;
        public boolean introduced, toured;
        public int jobsDone;
        /** [pa33g] Arrows it has loosed, and those that struck what it meant (its hit rate on the Stats tab). */
        public int shots, hits;
        public final ArrayDeque<Line> log = new ArrayDeque<>();
        /** [pa35] What it remembers of what its player told it (kept with the record: survives saves, deaths and new bodies). */
        public final PaMemory.Book memory = new PaMemory.Book();
        /** [pa35] Its bond with its player: the friendship, its favourites, its want, our story (PaBond). */
        public final PaBond.State bond = new PaBond.State();
        /** Chests its player pointed it at (packed positions): always allowed. */
        public final java.util.Set<Long> pointed = new java.util.HashSet<>();

        public boolean dead(long now) {
            return deadUntil > now;
        }

        public void say(int who, String text, long now) {
            say(who, text, now, false);
        }

        /**
         * [pa33b] Logged without the clutter of a reconnect or a re-sent order: the same line again straight after
         * itself becomes "×2" on the one line; a hello straight after a hello replaces it (one greeting per login).
         */
        public void say(int who, String text, long now, boolean greet) {
            Line last = log.peekLast();
            if (last != null && last.who() == who && last.text().equals(text)) {
                log.removeLast();
                log.addLast(new Line(who, text, now, last.times() + 1, last.greet()));
                return;
            }
            if (greet && last != null && last.greet()) log.removeLast();
            log.addLast(new Line(who, text, now, 1, greet));
            while (log.size() > 80) log.removeFirst();
        }

        /** [pa33b] Tests: its record as saved, and back. */
        public CompoundTag saveForTests() { return save(); }

        public static Rec loadForTests(CompoundTag t) { return load(t); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Owner", owner);
            t.putString("OwnerName", ownerName);
            t.putInt("Slot", slot);
            t.putLong("Inc", incarnation);
            if (entity != null) t.putUUID("Entity", entity);
            t.putString("Name", name);
            t.putInt("Look", look);
            t.putInt("Voice", voice);
            t.putInt("Pers", personality);
            t.putBoolean("Auto", autoHelp);
            t.putBoolean("Chests", useChests);
            t.putBoolean("Break", breakNearBuilds);
            t.putBoolean("Confirm", confirm);
            t.putBoolean("Sleep", sleepAtNight);
            t.putInt("Roam", roam);
            t.putInt("Chatty", chatty);
            t.putFloat("Funny", funny);
            t.putFloat("Gloomy", gloomy);
            t.putInt("Mute", mute);
            t.putInt("Lang", language);
            t.putInt("RoastBack", roastBack);
            t.putBoolean("Defend", defend);
            t.putBoolean("PingLocked", pingLocked);
            if (bed != null) { t.putLong("Bed", bed.asLong()); t.putString("BedDim", bedDim); }
            if (lastBed != null) { t.putLong("LastBed", lastBed.asLong()); t.putString("LastBedDim", lastBedDim); }
            CompoundTag wp = new CompoundTag();
            waypoints.forEach((k, v) -> { CompoundTag e = new CompoundTag(); e.putLong("P", v.asLong()); e.putString("D", waypointDims.getOrDefault(k, "")); wp.put(k, e); });
            t.put("Waypoints", wp);
            net.minecraft.nbt.ListTag kl = new net.minecraft.nbt.ListTag();
            for (String k : keep) kl.add(net.minecraft.nbt.StringTag.valueOf(k));
            t.put("Keep", kl);
            t.putLong("LeftAt", leftAt);
            t.putLong("Xp", xp);
            t.putInt("Spend", spend);
            if (spawner != null) { t.putLong("Spawner", spawner.asLong()); t.putString("SpawnerDim", spawnerDim); }
            t.putLong("DeadUntil", deadUntil);
            t.putLong("SummonAt", summonReadyAt);
            t.putLong("Made", made);
            t.putString("LastDeath", lastDeath);
            if (snapshot != null) t.put("Snap", snapshot);
            t.putBoolean("Away", away);
            t.putBoolean("Intro", introduced);
            t.putBoolean("Toured", toured);
            t.putInt("Done", jobsDone);
            t.putInt("Shots", shots);
            t.putInt("Hits", hits);
            ListTag lines = new ListTag();
            for (Line l : log) {
                CompoundTag c = new CompoundTag();
                c.putInt("W", l.who());
                c.putString("T", l.text());
                c.putLong("At", l.time());
                if (l.times() > 1) c.putInt("N", l.times());
                if (l.greet()) c.putBoolean("G", true);
                lines.add(c);
            }
            t.put("Log", lines);
            t.putLongArray("Pointed", pointed.stream().mapToLong(Long::longValue).toArray());
            t.put("Mem", PaMemoryNbt.save(memory));                                // [pa35]
            t.put("Bond", PaMemoryNbt.saveBond(bond));
            return t;
        }

        static Rec load(CompoundTag t) {
            Rec r = new Rec();
            r.owner = t.getUUID("Owner");
            r.ownerName = t.getString("OwnerName");
            r.slot = t.getInt("Slot");
            r.incarnation = t.getLong("Inc");
            if (t.hasUUID("Entity")) r.entity = t.getUUID("Entity");
            r.name = t.getString("Name");
            r.look = t.getInt("Look");
            r.voice = t.getInt("Voice");
            r.personality = t.getInt("Pers");
            r.autoHelp = t.getBoolean("Auto");
            r.useChests = t.getBoolean("Chests");
            r.breakNearBuilds = t.getBoolean("Break");
            r.confirm = t.getBoolean("Confirm");
            r.sleepAtNight = t.getBoolean("Sleep");
            r.roam = t.contains("Roam") ? t.getInt("Roam") : 32;
            r.chatty = t.contains("Chatty") ? t.getInt("Chatty") : 2;
            if (t.contains("Funny")) { r.funny = t.getFloat("Funny"); r.gloomy = t.getFloat("Gloomy"); }
            r.mute = Math.max(0, Math.min(2, t.getInt("Mute")));
            r.language = Math.max(0, Math.min(2, t.getInt("Lang")));
            r.roastBack = Math.max(0, Math.min(2, t.getInt("RoastBack")));
            r.defend = !t.contains("Defend") || t.getBoolean("Defend");
            r.pingLocked = !t.contains("PingLocked") || t.getBoolean("PingLocked");
            if (t.contains("Bed")) { r.bed = BlockPos.of(t.getLong("Bed")); r.bedDim = t.getString("BedDim"); }
            if (t.contains("LastBed")) { r.lastBed = BlockPos.of(t.getLong("LastBed")); r.lastBedDim = t.getString("LastBedDim"); }
            CompoundTag wp = t.getCompound("Waypoints");
            for (String k : wp.getAllKeys()) { r.waypoints.put(k, BlockPos.of(wp.getCompound(k).getLong("P"))); r.waypointDims.put(k, wp.getCompound(k).getString("D")); }
            for (net.minecraft.nbt.Tag k : t.getList("Keep", net.minecraft.nbt.Tag.TAG_STRING)) r.keep.add(k.getAsString());
            r.leftAt = t.getLong("LeftAt");
            r.xp = t.getLong("Xp");
            r.spend = t.contains("Spend") ? t.getInt("Spend") : 64;
            if (t.contains("Spawner")) { r.spawner = BlockPos.of(t.getLong("Spawner")); r.spawnerDim = t.getString("SpawnerDim"); }
            r.deadUntil = t.getLong("DeadUntil");
            r.summonReadyAt = t.getLong("SummonAt");
            r.made = t.getLong("Made");
            r.lastDeath = t.getString("LastDeath");
            if (t.contains("Snap")) r.snapshot = t.getCompound("Snap");
            r.away = t.getBoolean("Away");
            r.introduced = t.getBoolean("Intro");
            r.toured = t.getBoolean("Toured");
            r.jobsDone = t.getInt("Done");
            r.shots = t.getInt("Shots");
            r.hits = t.getInt("Hits");
            for (Tag e : t.getList("Log", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                r.log.addLast(new Line(c.getInt("W"), c.getString("T"), c.getLong("At"), Math.max(1, c.getInt("N")), c.getBoolean("G")));
            }
            for (long p : t.getLongArray("Pointed")) r.pointed.add(p);
            if (t.contains("Mem")) PaMemoryNbt.load(r.memory, t.getCompound("Mem"));    // [pa35]
            if (t.contains("Bond")) PaMemoryNbt.loadBond(r.bond, t.getCompound("Bond"));
            return r;
        }
    }

    private final Map<UUID, List<Rec>> byOwner = new HashMap<>();
    private long nextIncarnation = 1;

    /** The books, or null with no server (a unit test). */
    @Nullable
    public static PaData get() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PaData::new, PaData::load, null), ID);
    }

    /** Every assistant this player has, in slot order. */
    public static List<Rec> of(UUID owner) {
        PaData d = get();
        if (d == null) return List.of();
        return d.byOwner.getOrDefault(owner, List.of());
    }

    /** This player's first assistant, or null. */
    @Nullable
    public static Rec first(UUID owner) {
        List<Rec> l = of(owner);
        return l.isEmpty() ? null : l.get(0);
    }

    /** The record of this assistant (by its owner and slot), or null. */
    @Nullable
    public static Rec find(UUID owner, int slot) {
        for (Rec r : of(owner)) if (r.slot == slot) return r;
        return null;
    }

    /** A new record for this player, in the first free slot. */
    public static Rec create(UUID owner, String ownerName, long now) {
        PaData d = get();
        Rec r = new Rec();
        r.owner = owner;
        r.ownerName = ownerName;
        r.made = now;
        if (d == null) return r;
        List<Rec> l = d.byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
        int slot = 0;
        while (true) {
            final int s = slot;
            if (l.stream().noneMatch(x -> x.slot == s)) break;
            slot++;
        }
        r.slot = slot;
        l.add(r);
        l.sort(java.util.Comparator.comparingInt(x -> x.slot));
        d.setDirty();
        return r;
    }

    public static void remove(Rec r) {
        PaData d = get();
        if (d == null) return;
        List<Rec> l = d.byOwner.get(r.owner);
        if (l != null) {
            l.remove(r);
            if (l.isEmpty()) d.byOwner.remove(r.owner);
        }
        d.setDirty();
    }

    /** A fresh incarnation number: the newest copy of an assistant is the only real one. */
    public static long incarnate(Rec r) {
        PaData d = get();
        r.incarnation = d == null ? r.incarnation + 1 : d.nextIncarnation++;
        touch();
        return r.incarnation;
    }

    /** Every record (the server's watch over fallen and away assistants). */
    public static List<Rec> all() {
        PaData d = get();
        if (d == null) return List.of();
        List<Rec> out = new ArrayList<>();
        for (List<Rec> l : d.byOwner.values()) out.addAll(l);
        return out;
    }

    public static void touch() {
        PaData d = get();
        if (d != null) d.setDirty();
    }

    /** Tests: forget everything. */
    public static void resetForTests() {
        PaData d = get();
        if (d != null) { d.byOwner.clear(); d.setDirty(); }
    }

    public static PaData load(CompoundTag tag, HolderLookup.Provider registries) {
        PaData d = new PaData();
        d.nextIncarnation = Math.max(1, tag.getLong("NextInc"));
        for (Tag t : tag.getList("Assistants", Tag.TAG_COMPOUND)) {
            try {
                Rec r = Rec.load((CompoundTag) t);
                d.byOwner.computeIfAbsent(r.owner, k -> new ArrayList<>()).add(r);
            } catch (Exception ignored) {
                // a damaged record is dropped, not the whole book
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("NextInc", nextIncarnation);
        ListTag all = new ListTag();
        for (List<Rec> l : byOwner.values()) for (Rec r : l) all.add(r.save());
        tag.put("Assistants", all);
        return tag;
    }
}
