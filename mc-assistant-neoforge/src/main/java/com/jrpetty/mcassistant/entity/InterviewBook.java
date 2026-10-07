package com.jrpetty.mcassistant.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [interviews] The town's interview book, kept with the world: every interview set, being held and held (the post,
 * the day, the panel, each candidate and how the interview went for it, what was said, who got it and why), who holds
 * each post the town filled at interview, and what each candidate's card says of it. Interviews (the running of them)
 * reads and writes it; an interview half-held when the world was saved is held again from the start (Interviews.load).
 */
public final class InterviewBook extends SavedData {

    private static final String ID = "mc_assistant_interviews";
    /** How many held interviews each town keeps in its book. */
    static final int KEEP = 10;

    /** Each town's book. */
    final Map<UUID, Town> towns = new HashMap<>();
    /** What each folk's card says of its interviews: "day|line", the latest last, three at most. */
    final Map<UUID, List<String>> cards = new HashMap<>();
    /** Each candidate's last interview: {the day, 1 chosen or 0 not}, for its spirits and its week of hard work after. */
    final Map<UUID, long[]> after = new HashMap<>();

    @Nullable private static InterviewBook loose;

    public InterviewBook() {}

    static InterviewBook of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new InterviewBook();
            return loose;
        }
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(InterviewBook::new, InterviewBook::load, null), ID);
    }

    static void changed() {
        of().setDirty();
    }

    /** Tests: an empty book. */
    void clear() {
        towns.clear();
        cards.clear();
        after.clear();
        setDirty();
    }

    static Town town(UUID village) {
        return of().towns.computeIfAbsent(village, k -> new Town());
    }

    // ------------------------------------------------------------------ what is kept

    /** How an interview stands. */
    public enum Stage {
        /** Set for a day: announced, the candidates writing their letters, the table set out. */
        SET,
        /** The day come: panel and candidates to their seats. */
        GATHERING,
        /** One candidate at a time across the table. */
        SITTING,
        /** The candidates out, the panel in a huddle. */
        CONFERRING,
        /** A player leader's choice awaited (till the day's end). */
        AWAITING,
        /** The choice told, the hand shaken. */
        ANNOUNCING,
        DONE,
        CANCELLED;

        boolean on() { return this == GATHERING || this == SITTING || this == CONFERRING || this == ANNOUNCING; }
        boolean open() { return this != DONE && this != CANCELLED; }
    }

    /** One town's book: its interviews (the coming, the running and the held), and who holds each post it filled so. */
    static final class Town {
        int seq;
        final List<Interview> list = new ArrayList<>();
        /** Post → {holder, holder's name, the day}: the panel's choice, for the posts the town reckons each day. */
        final Map<String, String[]> held = new LinkedHashMap<>();
        /** Post → the day it was last filled unopposed, or its interview put off for good: not set again that day. */
        final Map<String, Long> quiet = new HashMap<>();
    }

    /** A word for a candidate: a friend, a partner, an old master, a rival, or a player. */
    static final class Ref {
        UUID by;
        String byName = "", line = "", kind = "";
        boolean player;
        int weight;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("By", by);
            t.putString("Name", byName);
            t.putString("Line", line);
            t.putString("Kind", kind);
            t.putBoolean("Player", player);
            t.putInt("Weight", weight);
            return t;
        }

        static Ref load(CompoundTag t) {
            Ref r = new Ref();
            r.by = t.hasUUID("By") ? t.getUUID("By") : new UUID(0, 0);
            r.byName = t.getString("Name");
            r.line = t.getString("Line");
            r.kind = t.getString("Kind");
            r.player = t.getBoolean("Player");
            r.weight = t.getInt("Weight");
            return r;
        }
    }

    /** One candidate: who, from where, how it stood on paper, and how the interview went for it, part by part. */
    public static final class Cand {
        UUID id;
        String name = "", homeName = "";
        UUID home;
        /** From another town (an applicant to the town's notice), or one of the town's own. */
        boolean outside;
        /** Its trade (by name) and its level at the post's trade, its years, the knacks of it it chose. */
        String trade = "";
        int level, age, knacks;
        /** Its standing on paper (JobMarket's weighing, or the post's own), and what told for it. */
        int paper;
        String good = "";
        /** It wrote a letter of application (out of its town's stores, at its own cost), and what it says. */
        boolean letter;
        String letterWords = "";
        /** Came to the interview; could not come (its letter read out); set off for it; got there. */
        boolean absent, setOff, arrived;
        long leaveAt = -1;
        /** How the interview went, part by part (each in the score's own points). */
        int nerves, prep, answers, evidence, refs, honesty, boast, away;
        int total;
        String evidenceWords = "";
        final List<Ref> words = new ArrayList<>();
        /** Its turn done; what came of it ("chosen", "not chosen"), and why, as it was told. */
        boolean seen;
        String outcome = "", why = "";

        public UUID id() { return id; }
        public String name() { return name; }
        public int paper() { return paper; }
        public int total() { return total; }
        public boolean outside() { return outside; }
        public boolean letter() { return letter; }
        public boolean arrived() { return arrived; }
        public boolean absent() { return absent; }
        public String outcome() { return outcome; }
        public String why() { return why; }
        public int refs() { return refs; }
        public int boast() { return boast; }
        public int evidence() { return evidence; }
        public int nerves() { return nerves; }
        /** Its score as it stands: the paper, and what its interview has made of it so far. */
        public int score() { return paper + interview(); }
        public String evidenceWords() { return evidenceWords; }
        /** How its interview went, part by part, in words: "nerves -1, letter +2, answers +3". */
        public String parts() { return Interviews.parts(this); }
        /** Who put in a word for it (or against): "Mira (friend) +4". */
        public List<String> refNames() {
            List<String> out = new ArrayList<>();
            for (Ref r : words) out.add(r.byName + " (" + r.kind + ") " + (r.weight >= 0 ? "+" : "") + r.weight);
            return out;
        }

        /** What the interview made of it, all told: kept within twelve either way, so a good one lifts a slightly weaker
         *  candidate over a stronger one, but never a much weaker one over a much better. */
        int interview() {
            int s = nerves + prep + answers + evidence + refs + honesty + boast + away;
            return Math.max(-Interviews.SWAY, Math.min(Interviews.SWAY, s));
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putString("Name", name);
            if (home != null) t.putUUID("Home", home);
            t.putString("HomeName", homeName);
            t.putBoolean("Outside", outside);
            t.putString("Trade", trade);
            t.putInt("Level", level);
            t.putInt("Age", age);
            t.putInt("Knacks", knacks);
            t.putInt("Paper", paper);
            t.putString("Good", good);
            t.putBoolean("Letter", letter);
            t.putString("LetterWords", letterWords);
            t.putBoolean("Absent", absent);
            t.putBoolean("SetOff", setOff);
            t.putBoolean("Arrived", arrived);
            t.putLong("LeaveAt", leaveAt);
            t.putIntArray("Parts", new int[]{ nerves, prep, answers, evidence, refs, honesty, boast, away, total });
            t.putString("Evidence", evidenceWords);
            ListTag w = new ListTag();
            for (Ref r : words) w.add(r.save());
            t.put("Words", w);
            t.putBoolean("Seen", seen);
            t.putString("Outcome", outcome);
            t.putString("Why", why);
            return t;
        }

        static Cand load(CompoundTag t) {
            Cand c = new Cand();
            c.id = t.getUUID("Id");
            c.name = t.getString("Name");
            c.home = t.hasUUID("Home") ? t.getUUID("Home") : null;
            c.homeName = t.getString("HomeName");
            c.outside = t.getBoolean("Outside");
            c.trade = t.getString("Trade");
            c.level = t.getInt("Level");
            c.age = t.getInt("Age");
            c.knacks = t.getInt("Knacks");
            c.paper = t.getInt("Paper");
            c.good = t.getString("Good");
            c.letter = t.getBoolean("Letter");
            c.letterWords = t.getString("LetterWords");
            c.absent = t.getBoolean("Absent");
            c.setOff = t.getBoolean("SetOff");
            c.arrived = t.getBoolean("Arrived");
            c.leaveAt = t.getLong("LeaveAt");
            int[] p = t.getIntArray("Parts");
            if (p.length >= 9) {
                c.nerves = p[0]; c.prep = p[1]; c.answers = p[2]; c.evidence = p[3]; c.refs = p[4]; c.honesty = p[5]; c.boast = p[6];
                c.away = p[7]; c.total = p[8];
            }
            c.evidenceWords = t.getString("Evidence");
            ListTag w = t.getList("Words", Tag.TAG_COMPOUND);
            for (int i = 0; i < w.size(); i++) c.words.add(Ref.load(w.getCompound(i)));
            c.seen = t.getBoolean("Seen");
            c.outcome = t.getString("Outcome");
            c.why = t.getString("Why");
            return c;
        }
    }

    /** One interview: the post, the day and the place, the panel, the candidates, what was said, and the choice. */
    public static final class Interview {
        int id;
        UUID village;
        /** The post, by its key (InterviewPosts): "teacher", "constable", "master:SMITH", "opening:7". */
        String post = "", title = "", trade = "";
        /** The job market's notice it fills (opening:...), or -1. */
        int opening = -1;
        boolean big;
        long setDay, dueDay, dueTime;
        int putOff;
        Stage stage = Stage.SET;
        /** The panel: the chair (a folk, or the player who leads), the post's master or the trade's best hand, a
         *  councillor for a big post, and an honoured guest (a player) if one was asked to sit. */
        @Nullable UUID chair, master, councillor, guest;
        String chairName = "", masterName = "", councillorName = "", guestName = "";
        boolean playerChairs;
        /** Where: "the meeting hall", "the board". */
        String where = "";
        final List<Cand> cands = new ArrayList<>();
        /** What was said, a line at a time: "speaker|words". */
        final List<String> said = new ArrayList<>();
        /** The players' choices (the leader's stands; a guest's is a vote), by player: the candidate. */
        final Map<UUID, UUID> chose = new LinkedHashMap<>();
        @Nullable UUID winner;
        String winnerName = "", reason = "";
        long decidedDay = -1;
        boolean close;

        // What the running of it needs, kept in memory only (a restart holds it again from the start).
        transient int current = -1, step;
        transient long nextAt, stageAt, chatterAt;
        transient final List<InterviewScript.Line> lines = new ArrayList<>();
        transient final java.util.Set<UUID> greeted = new java.util.HashSet<>();
        transient @Nullable InterviewTable.Table table;
        /** Who is held by it just now (beyond the panel and the candidates): a referee walking over. */
        transient @Nullable UUID referee;
        transient @Nullable String waitingFor;
        transient long waitSince;
        /** Who spoke last (the others look at it); a piece of work held up just now, by whom, and what was in its hand. */
        transient @Nullable UUID lastSpeaker;
        transient @Nullable InterviewScript.Piece shown;
        transient @Nullable UUID shownBy;
        transient net.minecraft.world.item.ItemStack savedHand = net.minecraft.world.item.ItemStack.EMPTY;
        /** The chosen has come up to the table to shake on it; who holds a candidate's letter just now. */
        transient boolean shaken;
        /** Who has sat in the candidate's chair this sitting (the tests, the log). */
        transient final java.util.Set<UUID> sat = new java.util.LinkedHashSet<>();
        transient @Nullable UUID letterWith;

        public int id() { return id; }
        public UUID village() { return village; }
        public String post() { return post; }
        public String title() { return title; }
        public Stage stage() { return stage; }
        public List<Cand> cands() { return cands; }
        public List<String> said() { return said; }
        @Nullable public UUID winner() { return winner; }
        public String winnerName() { return winnerName; }
        public String reason() { return reason; }
        public long dueDay() { return dueDay; }
        public String where() { return where; }
        public String chairName() { return chairName; }

        @Nullable
        Cand cand(UUID who) {
            for (Cand c : cands) if (c.id.equals(who)) return c;
            return null;
        }

        /** The same interview as the panel alone would decide it (no player's choice): for telling the two apart. */
        Interview panelOnly() {
            Interview x = new Interview();
            x.village = village;
            x.chair = chair;
            x.master = master;
            x.councillor = councillor;
            x.cands.addAll(cands);
            return x;
        }

        @Nullable
        Cand now() {
            return current >= 0 && current < cands.size() ? cands.get(current) : null;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            t.putUUID("Village", village);
            t.putString("Post", post);
            t.putString("Title", title);
            t.putString("Trade", trade);
            t.putInt("Opening", opening);
            t.putBoolean("Big", big);
            t.putLong("SetDay", setDay);
            t.putLong("DueDay", dueDay);
            t.putLong("DueTime", dueTime);
            t.putInt("PutOff", putOff);
            // Half-held when the world was saved: it is held again from the start.
            t.putString("Stage", (stage.on() ? Stage.SET : stage).name());
            if (chair != null) t.putUUID("Chair", chair);
            if (master != null) t.putUUID("Master", master);
            if (councillor != null) t.putUUID("Councillor", councillor);
            if (guest != null) t.putUUID("Guest", guest);
            t.putString("ChairName", chairName);
            t.putString("MasterName", masterName);
            t.putString("CouncillorName", councillorName);
            t.putString("GuestName", guestName);
            t.putBoolean("PlayerChairs", playerChairs);
            t.putString("Where", where);
            ListTag cs = new ListTag();
            for (Cand c : cands) cs.add(c.save());
            t.put("Cands", cs);
            ListTag s = new ListTag();
            for (String l : said) s.add(StringTag.valueOf(l));
            t.put("Said", s);
            CompoundTag ch = new CompoundTag();
            for (Map.Entry<UUID, UUID> e : chose.entrySet()) ch.putUUID(e.getKey().toString(), e.getValue());
            t.put("Chose", ch);
            if (winner != null) t.putUUID("Winner", winner);
            t.putString("WinnerName", winnerName);
            t.putString("Reason", reason);
            t.putLong("Decided", decidedDay);
            t.putBoolean("Close", close);
            return t;
        }

        static Interview load(CompoundTag t) {
            Interview iv = new Interview();
            iv.id = t.getInt("Id");
            iv.village = t.getUUID("Village");
            iv.post = t.getString("Post");
            iv.title = t.getString("Title");
            iv.trade = t.getString("Trade");
            iv.opening = t.getInt("Opening");
            iv.big = t.getBoolean("Big");
            iv.setDay = t.getLong("SetDay");
            iv.dueDay = t.getLong("DueDay");
            iv.dueTime = t.getLong("DueTime");
            iv.putOff = t.getInt("PutOff");
            try {
                iv.stage = Stage.valueOf(t.getString("Stage"));
            } catch (IllegalArgumentException e) {
                iv.stage = Stage.CANCELLED;
            }
            iv.chair = t.hasUUID("Chair") ? t.getUUID("Chair") : null;
            iv.master = t.hasUUID("Master") ? t.getUUID("Master") : null;
            iv.councillor = t.hasUUID("Councillor") ? t.getUUID("Councillor") : null;
            iv.guest = t.hasUUID("Guest") ? t.getUUID("Guest") : null;
            iv.chairName = t.getString("ChairName");
            iv.masterName = t.getString("MasterName");
            iv.councillorName = t.getString("CouncillorName");
            iv.guestName = t.getString("GuestName");
            iv.playerChairs = t.getBoolean("PlayerChairs");
            iv.where = t.getString("Where");
            ListTag cs = t.getList("Cands", Tag.TAG_COMPOUND);
            for (int i = 0; i < cs.size(); i++) iv.cands.add(Cand.load(cs.getCompound(i)));
            ListTag s = t.getList("Said", Tag.TAG_STRING);
            for (int i = 0; i < s.size(); i++) iv.said.add(s.getString(i));
            CompoundTag ch = t.getCompound("Chose");
            for (String k : ch.getAllKeys()) {
                try {
                    iv.chose.put(UUID.fromString(k), ch.getUUID(k));
                } catch (RuntimeException ignored) { }
            }
            iv.winner = t.hasUUID("Winner") ? t.getUUID("Winner") : null;
            iv.winnerName = t.getString("WinnerName");
            iv.reason = t.getString("Reason");
            iv.decidedDay = t.getLong("Decided");
            iv.close = t.getBoolean("Close");
            return iv;
        }
    }

    // ------------------------------------------------------------------ saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag ts = new CompoundTag();
        for (Map.Entry<UUID, Town> e : towns.entrySet()) {
            Town town = e.getValue();
            CompoundTag t = new CompoundTag();
            t.putInt("Seq", town.seq);
            ListTag l = new ListTag();
            for (Interview iv : town.list) l.add(iv.save());
            t.put("List", l);
            CompoundTag h = new CompoundTag();
            for (Map.Entry<String, String[]> x : town.held.entrySet()) h.putString(x.getKey(), String.join("|", x.getValue()));
            t.put("Held", h);
            CompoundTag q = new CompoundTag();
            for (Map.Entry<String, Long> x : town.quiet.entrySet()) q.putLong(x.getKey(), x.getValue());
            t.put("Quiet", q);
            ts.put(e.getKey().toString(), t);
        }
        tag.put("Towns", ts);
        CompoundTag cs = new CompoundTag();
        for (Map.Entry<UUID, List<String>> e : cards.entrySet()) {
            ListTag l = new ListTag();
            for (String s : e.getValue()) l.add(StringTag.valueOf(s));
            cs.put(e.getKey().toString(), l);
        }
        tag.put("Cards", cs);
        CompoundTag af = new CompoundTag();
        for (Map.Entry<UUID, long[]> e : after.entrySet()) af.putLongArray(e.getKey().toString(), e.getValue());
        tag.put("After", af);
        return tag;
    }

    public static InterviewBook load(CompoundTag tag, HolderLookup.Provider registries) {
        InterviewBook b = new InterviewBook();
        CompoundTag ts = tag.getCompound("Towns");
        for (String k : ts.getAllKeys()) {
            try {
                CompoundTag t = ts.getCompound(k);
                Town town = new Town();
                town.seq = t.getInt("Seq");
                ListTag l = t.getList("List", Tag.TAG_COMPOUND);
                for (int i = 0; i < l.size(); i++) town.list.add(Interview.load(l.getCompound(i)));
                CompoundTag h = t.getCompound("Held");
                for (String p : h.getAllKeys()) town.held.put(p, h.getString(p).split("\\|", -1));
                CompoundTag q = t.getCompound("Quiet");
                for (String p : q.getAllKeys()) town.quiet.put(p, q.getLong(p));
                b.towns.put(UUID.fromString(k), town);
            } catch (RuntimeException ignored) { }
        }
        CompoundTag cs = tag.getCompound("Cards");
        for (String k : cs.getAllKeys()) {
            try {
                ListTag l = cs.getList(k, Tag.TAG_STRING);
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) lines.add(l.getString(i));
                b.cards.put(UUID.fromString(k), lines);
            } catch (RuntimeException ignored) { }
        }
        CompoundTag af = tag.getCompound("After");
        for (String k : af.getAllKeys()) {
            try {
                b.after.put(UUID.fromString(k), af.getLongArray(k));
            } catch (RuntimeException ignored) { }
        }
        return b;
    }
}
