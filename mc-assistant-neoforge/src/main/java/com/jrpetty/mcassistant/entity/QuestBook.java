package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
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
 * [quests] The quests: every one a folk has offered, every one a player has taken on, and how each came out,
 * kept with the world.
 *
 * <p>A quest is a folk's own (its giver), offered to whoever asks it for work, and once taken it is that one
 * player's: nobody else can take the same favour. It is a list of steps, each a real thing in the world (go
 * there, find them, bring that, talk to them, choose), and the steps of a story are written as it unfolds, so a
 * choice made at one step decides the next. What is promised for it (the coin, from the giver's purse or the
 * treasury; goods out of the stores; the giver's warmth) is promised by the giver at the time, out of what it
 * has: nobody offers coin it has not got.
 *
 * <p>The book also keeps what a player has earned in each town by its quests (its titles, its honours, how many
 * it has done there), the risks a player runs for what it did (a cut taken off the smugglers may yet come out),
 * and when each town last had a story, so a town is not one long adventure.
 */
public final class QuestBook extends SavedData {

    static final String ID = "mc_assistant_quests";

    /** How many finished quests a player's journal keeps. */
    static final int KEEP_FINISHED = 30;

    public enum State { OFFERED, ACTIVE, DONE, FAILED, ABANDONED }

    /** What sort of quest, for the journal's headings and the board. */
    public enum Kind {
        FAVOUR("A favour"), TOWN("For the town"), WAR("War and peace"), CAVES("Below ground"), STORY("A story"),
        BOARD("The quest board");

        public final String label;

        Kind(String label) { this.label = label; }
    }

    /** What a step asks: say it in the journal's words, check it in the world's. */
    public enum StepType {
        /** Talk to somebody. */
        TALK,
        /** Go to a place (and, with {@code night}, be there after dark). */
        GO,
        /** Find somebody (a folk, a pet) where it is: be near it. */
        FIND,
        /** Have a thing in your pack (pick it up, take it out of a chest). */
        GET,
        /** Hand somebody so many of a thing. */
        GIVE,
        /** Carry a quest's own thing (a letter, the peace terms) to somebody. */
        DELIVER,
        /** Kill so many monsters about a place. */
        KILL,
        /** Break the spawner at a place. */
        SEAL,
        /** Light a place: so many torches or lanterns about it. */
        LIGHT,
        /** Make a choice, when you talk to somebody. */
        CHOOSE,
        /** Wait for something the story is watching for (a child home, a caravan in). */
        WAIT
    }

    /** One step of a quest. */
    public static final class Step {
        public StepType type;
        /** The script's own name for the step ("friend", "camp"), so it knows what has been done. */
        public String key = "";
        /** A story's chapter, as its journal lists it. */
        public String chapter = "";
        /** What the journal says to do. */
        public String text = "";
        @Nullable public BlockPos at;
        public String dim = "minecraft:overworld";
        public int radius = 4;
        @Nullable public UUID who;
        public String whoName = "";
        /** What is wanted (QuestItems.matches). */
        public String item = "";
        public int count = 1, progress;
        /** What is to be killed: "monsters", "zombies"... */
        public String mob = "";
        /** The choices: a key and the words on its button. */
        public final List<String[]> options = new ArrayList<>();
        /** A GO step that only counts after dark. */
        public boolean night;
        public boolean done;
        /** What came of it, in a line, for the journal: "Pip said they played by the north woods". */
        public String note = "";

        public Step(StepType type, String key, String text) {
            this.type = type;
            this.key = key;
            this.text = text;
        }

        Step at(BlockPos p, int r) {
            this.at = p.immutable();
            this.radius = r;
            return this;
        }

        Step who(VillageFolkEntity f) {
            this.who = f.getUUID();
            this.whoName = f.displayNameCap();
            this.dim = f.level().dimension().location().toString();
            return this;
        }

        Step who(UUID id, String name) {
            this.who = id;
            this.whoName = name;
            return this;
        }

        Step item(String key, int n) {
            this.item = key;
            this.count = Math.max(1, n);
            return this;
        }

        Step chapter(String c) {
            this.chapter = c;
            return this;
        }

        Step option(String key, String label) {
            options.add(new String[]{ key, label });
            return this;
        }

        @Nullable
        public String optionLabel(String key) {
            for (String[] o : options) if (o[0].equals(key)) return o[1];
            return null;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Type", type.name());
            t.putString("Key", key);
            if (!chapter.isEmpty()) t.putString("Chapter", chapter);
            t.putString("Text", text);
            if (at != null) t.putLong("At", at.asLong());
            t.putString("Dim", dim);
            t.putInt("Radius", radius);
            if (who != null) t.putUUID("Who", who);
            t.putString("WhoName", whoName);
            if (!item.isEmpty()) t.putString("Item", item);
            t.putInt("Count", count);
            t.putInt("Progress", progress);
            if (!mob.isEmpty()) t.putString("Mob", mob);
            if (!options.isEmpty()) {
                ListTag l = new ListTag();
                for (String[] o : options) l.add(StringTag.valueOf(o[0] + "|" + o[1]));
                t.put("Options", l);
            }
            t.putBoolean("Night", night);
            t.putBoolean("Done", done);
            if (!note.isEmpty()) t.putString("Note", note);
            return t;
        }

        static Step load(CompoundTag t) {
            StepType type;
            try {
                type = StepType.valueOf(t.getString("Type"));
            } catch (IllegalArgumentException e) {
                type = StepType.WAIT;
            }
            Step s = new Step(type, t.getString("Key"), t.getString("Text"));
            s.chapter = t.getString("Chapter");
            if (t.contains("At")) s.at = BlockPos.of(t.getLong("At"));
            if (t.contains("Dim")) s.dim = t.getString("Dim");
            s.radius = t.getInt("Radius");
            if (t.hasUUID("Who")) s.who = t.getUUID("Who");
            s.whoName = t.getString("WhoName");
            s.item = t.getString("Item");
            s.count = Math.max(1, t.getInt("Count"));
            s.progress = t.getInt("Progress");
            s.mob = t.getString("Mob");
            for (Tag o : t.getList("Options", Tag.TAG_STRING)) {
                String[] p = o.getAsString().split("\\|", 2);
                if (p.length == 2) s.options.add(p);
            }
            s.night = t.getBoolean("Night");
            s.done = t.getBoolean("Done");
            s.note = t.getString("Note");
            return s;
        }
    }

    /** One quest. */
    public static final class Quest {
        public int id;
        /** The script that runs it (QuestScripts): "favour.healer", "story.child"... */
        public String script = "";
        public Kind kind = Kind.FAVOUR;
        public State state = State.OFFERED;
        public UUID village;
        public UUID giver;
        public String giverName = "";
        @Nullable public UUID player;
        public String playerName = "";
        public String title = "";
        /** What the giver said when it asked. */
        public String offer = "";
        public final List<Step> steps = new ArrayList<>();
        /** The coin promised, and who pays it: "purse" (the giver's own, and any other payers in flags), or "treasury". */
        public int coins;
        public String payer = "treasury";
        /** Goods promised out of the stores: "minecraft:bread*8". */
        public String goods = "";
        /** How much warmer the giver will feel for it. */
        public int warmth = 10;
        public long posted, taken = -1, deadline, finished = -1;
        /** Days to do it in, once taken. */
        public int days = 3;
        /** The script's own notes: who is who, what was chosen. */
        public final Map<String, String> flags = new LinkedHashMap<>();
        /** A story's ending, as its last page reads; or the last word of a favour. */
        public String ending = "";
        /** What came of it, in a line. */
        public String outcome = "";

        public boolean story() { return kind == Kind.STORY; }

        public boolean open() { return state == State.OFFERED || state == State.ACTIVE; }

        /** The step it is on: the first not done; null when every step is done. */
        @Nullable
        public Step current() {
            for (Step s : steps) if (!s.done) return s;
            return null;
        }

        @Nullable
        public Step step(String key) {
            for (Step s : steps) if (s.key.equals(key)) return s;
            return null;
        }

        public String flag(String key) {
            return flags.getOrDefault(key, "");
        }

        @Nullable
        public UUID flagId(String key) {
            String s = flags.get(key);
            if (s == null || s.isEmpty()) return null;
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public String rewardWords() {
            List<String> parts = new ArrayList<>();
            if (coins > 0) parts.add(coins + (coins == 1 ? " coin" : " coins") + (payer.equals("purse") ? " from " + giverName + "'s purse" : " from the treasury"));
            if (!goods.isEmpty()) {
                String[] g = goods.split("\\*");
                if (g.length == 2) parts.add(g[1] + " " + g[0].replaceFirst("^[a-z_]+:", "").replace('_', ' ') + " from the stores");
            }
            String extra = flags.get("reward");
            if (extra != null && !extra.isEmpty()) parts.add(extra);
            if (parts.isEmpty()) parts.add(giverName + "'s thanks");
            return String.join(", ", parts);
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            t.putString("Script", script);
            t.putString("Kind", kind.name());
            t.putString("State", state.name());
            t.putUUID("Village", village);
            t.putUUID("Giver", giver);
            t.putString("GiverName", giverName);
            if (player != null) t.putUUID("Player", player);
            t.putString("PlayerName", playerName);
            t.putString("Title", title);
            t.putString("Offer", offer);
            ListTag l = new ListTag();
            for (Step s : steps) l.add(s.save());
            t.put("Steps", l);
            t.putInt("Coins", coins);
            t.putString("Payer", payer);
            t.putString("Goods", goods);
            t.putInt("Warmth", warmth);
            t.putLong("Posted", posted);
            t.putLong("Taken", taken);
            t.putLong("Deadline", deadline);
            t.putLong("Finished", finished);
            t.putInt("Days", days);
            CompoundTag f = new CompoundTag();
            for (Map.Entry<String, String> e : flags.entrySet()) f.putString(e.getKey(), e.getValue());
            t.put("Flags", f);
            t.putString("Ending", ending);
            t.putString("Outcome", outcome);
            return t;
        }

        @Nullable
        static Quest load(CompoundTag t) {
            if (!t.hasUUID("Village") || !t.hasUUID("Giver")) return null;
            Quest q = new Quest();
            q.id = t.getInt("Id");
            q.script = t.getString("Script");
            try {
                q.kind = Kind.valueOf(t.getString("Kind"));
                q.state = State.valueOf(t.getString("State"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            q.village = t.getUUID("Village");
            q.giver = t.getUUID("Giver");
            q.giverName = t.getString("GiverName");
            if (t.hasUUID("Player")) q.player = t.getUUID("Player");
            q.playerName = t.getString("PlayerName");
            q.title = t.getString("Title");
            q.offer = t.getString("Offer");
            for (Tag s : t.getList("Steps", Tag.TAG_COMPOUND)) q.steps.add(Step.load((CompoundTag) s));
            q.coins = t.getInt("Coins");
            q.payer = t.getString("Payer");
            q.goods = t.getString("Goods");
            q.warmth = t.getInt("Warmth");
            q.posted = t.getLong("Posted");
            q.taken = t.getLong("Taken");
            q.deadline = t.getLong("Deadline");
            q.finished = t.getLong("Finished");
            q.days = Math.max(1, t.getInt("Days"));
            CompoundTag f = t.getCompound("Flags");
            for (String k : f.getAllKeys()) q.flags.put(k, f.getString(k));
            q.ending = t.getString("Ending");
            q.outcome = t.getString("Outcome");
            return q;
        }
    }

    // ------------------------------------------------------------------ the book

    private final Map<Integer, Quest> quests = new LinkedHashMap<>();
    /** A player's titles in each town: player, then town, then the titles in the order earned. */
    private final Map<UUID, Map<UUID, List<String>>> titles = new HashMap<>();
    /** How many quests a player has done in each town: "player/town". */
    private final Map<String, Integer> done = new HashMap<>();
    /** The honours a town has given a player ("medal", "key"), and those still to be made: "player/town" -> "medal,key". */
    private final Map<String, String> honours = new HashMap<>();
    private final Map<String, String> owed = new HashMap<>();
    /** The day each town's last story ended. */
    private final Map<UUID, Long> storyEnded = new HashMap<>();
    /** What may yet come out: a quest's id, and the last day it can. */
    private final Map<Integer, Long> secrets = new HashMap<>();
    private int nextId = 1;

    /** Without a server (a plain unit test) the book is kept here instead. */
    private static QuestBook loose;

    static QuestBook of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new QuestBook();
            return loose;
        }
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(QuestBook::new, QuestBook::load, null), ID);
    }

    /** Tests: an empty book (the tests share one world). */
    public static void resetForTests() {
        QuestBook b = of();
        b.quests.clear();
        b.titles.clear();
        b.done.clear();
        b.honours.clear();
        b.owed.clear();
        b.storyEnded.clear();
        b.secrets.clear();
        b.setDirty();
    }

    static void changed() {
        of().setDirty();
    }

    // ------------------------------------------------------------------ quests

    static Quest create() {
        QuestBook b = of();
        Quest q = new Quest();
        q.id = b.nextId++;
        b.setDirty();
        return q;
    }

    static void add(Quest q) {
        QuestBook b = of();
        b.quests.put(q.id, q);
        b.setDirty();
    }

    @Nullable
    public static Quest get(int id) {
        return of().quests.get(id);
    }

    static void remove(int id) {
        if (of().quests.remove(id) != null) changed();
    }

    public static List<Quest> all() {
        return new ArrayList<>(of().quests.values());
    }

    /** A player's quests, the open ones first (in the order taken), then the finished, latest first. */
    public static List<Quest> of(UUID player) {
        List<Quest> open = new ArrayList<>(), shut = new ArrayList<>();
        for (Quest q : of().quests.values()) {
            if (!player.equals(q.player)) continue;
            if (q.state == State.ACTIVE) open.add(q);
            else if (q.state != State.OFFERED) shut.add(q);
        }
        shut.sort((a, b) -> Long.compare(b.finished, a.finished));
        open.addAll(shut);
        return open;
    }

    public static List<Quest> active(UUID player) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : of().quests.values()) if (q.state == State.ACTIVE && player.equals(q.player)) out.add(q);
        return out;
    }

    /** What this folk is offering, if anything (one at a time). */
    @Nullable
    public static Quest offerOf(UUID giver) {
        for (Quest q : of().quests.values()) if (q.state == State.OFFERED && giver.equals(q.giver)) return q;
        return null;
    }

    /** Is this folk giving a quest just now (offered, or taken and not finished)? */
    public static boolean giving(UUID giver) {
        for (Quest q : of().quests.values()) if (q.open() && giver.equals(q.giver)) return true;
        return false;
    }

    public static List<Quest> offers(UUID village) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : of().quests.values()) if (q.state == State.OFFERED && village.equals(q.village)) out.add(q);
        return out;
    }

    /** The town's open quests (offered or under way). */
    public static List<Quest> open(UUID village) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : of().quests.values()) if (q.open() && village.equals(q.village)) out.add(q);
        return out;
    }

    /** The town's story, if one is running (offered or under way): one at a time. */
    @Nullable
    public static Quest story(UUID village) {
        for (Quest q : of().quests.values()) if (q.open() && q.story() && village.equals(q.village)) return q;
        return null;
    }

    /** Does the town have quest givers (anybody offering or owed a hand)? The shop keeps journals for them. */
    public static boolean hasGivers(UUID village) {
        for (Quest q : of().quests.values()) if (village.equals(q.village)) return true;
        return false;
    }

    /** Finished quests done for this town in the last so many days, latest first. */
    public static List<Quest> deeds(UUID village, long since) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : of().quests.values()) {
            if (q.state == State.DONE && village.equals(q.village) && q.finished >= since) out.add(q);
        }
        out.sort((a, b) -> Long.compare(b.finished, a.finished));
        return out;
    }

    /** A player's journal keeps its last thirty finished quests; older ones go out of the book. */
    static void trim(UUID player) {
        List<Quest> shut = new ArrayList<>();
        for (Quest q : of().quests.values()) if (player.equals(q.player) && !q.open()) shut.add(q);
        if (shut.size() <= KEEP_FINISHED) return;
        shut.sort((a, b) -> Long.compare(a.finished, b.finished));
        for (int i = 0; i < shut.size() - KEEP_FINISHED; i++) of().quests.remove(shut.get(i).id);
        changed();
    }

    // ------------------------------------------------------------------ what a player has earned

    public static List<String> titles(UUID player, UUID village) {
        Map<UUID, List<String>> m = of().titles.get(player);
        List<String> l = m == null ? null : m.get(village);
        return l == null ? List.of() : List.copyOf(l);
    }

    static void title(UUID player, UUID village, String title) {
        List<String> l = of().titles.computeIfAbsent(player, k -> new HashMap<>()).computeIfAbsent(village, k -> new ArrayList<>());
        if (!l.contains(title)) l.add(title);
        changed();
    }

    public static int doneIn(UUID player, UUID village) {
        return of().done.getOrDefault(player + "/" + village, 0);
    }

    static void tally(UUID player, UUID village) {
        of().done.merge(player + "/" + village, 1, Integer::sum);
        changed();
    }

    /** The honours this town has given this player: "medal", "key". */
    public static boolean honoured(UUID player, UUID village, String what) {
        String s = of().honours.get(player + "/" + village);
        return s != null && List.of(s.split(",")).contains(what);
    }

    static void honour(UUID player, UUID village, String what) {
        String k = player + "/" + village;
        String s = of().honours.getOrDefault(k, "");
        if (!List.of(s.split(",")).contains(what)) of().honours.put(k, s.isEmpty() ? what : s + "," + what);
        changed();
    }

    /** An honour voted but not yet made (the smith short of the gold for it). */
    static boolean owed(UUID player, UUID village, String what) {
        String s = of().owed.get(player + "/" + village);
        return s != null && List.of(s.split(",")).contains(what);
    }

    static void owe(UUID player, UUID village, String what, boolean on) {
        String k = player + "/" + village;
        List<String> l = new ArrayList<>(List.of(of().owed.getOrDefault(k, "").split(",")));
        l.removeIf(String::isEmpty);
        if (on && !l.contains(what)) l.add(what);
        if (!on) l.remove(what);
        if (l.isEmpty()) of().owed.remove(k);
        else of().owed.put(k, String.join(",", l));
        changed();
    }

    /** Every honour still owed: "player/town" and what. */
    static Map<String, String> allOwed() {
        return new HashMap<>(of().owed);
    }

    static long storyEnded(UUID village) {
        return of().storyEnded.getOrDefault(village, -100L);
    }

    static void storyEnded(UUID village, long day) {
        of().storyEnded.put(village, day);
        changed();
    }

    /** A thing a player did that may yet come out, till the last day it can. */
    static void secret(int quest, long until) {
        of().secrets.put(quest, until);
        changed();
    }

    static Map<Integer, Long> secrets() {
        return new HashMap<>(of().secrets);
    }

    static void kept(int quest) {
        if (of().secrets.remove(quest) != null) changed();
    }

    // ------------------------------------------------------------------ kept with the world

    public static QuestBook load(CompoundTag tag, HolderLookup.Provider registries) {
        QuestBook b = new QuestBook();
        b.nextId = Math.max(1, tag.getInt("NextId"));
        for (Tag t : tag.getList("Quests", Tag.TAG_COMPOUND)) {
            Quest q = Quest.load((CompoundTag) t);
            if (q != null) {
                b.quests.put(q.id, q);
                b.nextId = Math.max(b.nextId, q.id + 1);
            }
        }
        for (Tag t : tag.getList("Titles", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (!c.hasUUID("Player") || !c.hasUUID("Town")) continue;
            List<String> l = new ArrayList<>();
            for (Tag s : c.getList("Titles", Tag.TAG_STRING)) l.add(s.getAsString());
            b.titles.computeIfAbsent(c.getUUID("Player"), k -> new HashMap<>()).put(c.getUUID("Town"), l);
        }
        CompoundTag d = tag.getCompound("Done");
        for (String k : d.getAllKeys()) b.done.put(k, d.getInt(k));
        CompoundTag h = tag.getCompound("Honours");
        for (String k : h.getAllKeys()) b.honours.put(k, h.getString(k));
        CompoundTag o = tag.getCompound("Owed");
        for (String k : o.getAllKeys()) b.owed.put(k, o.getString(k));
        for (Tag t : tag.getList("Stories", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (c.hasUUID("Town")) b.storyEnded.put(c.getUUID("Town"), c.getLong("Day"));
        }
        CompoundTag s = tag.getCompound("Secrets");
        for (String k : s.getAllKeys()) {
            try {
                b.secrets.put(Integer.parseInt(k), s.getLong(k));
            } catch (NumberFormatException ignored) {
                // a key from a later build
            }
        }
        return b;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextId", nextId);
        ListTag ql = new ListTag();
        for (Quest q : quests.values()) ql.add(q.save());
        tag.put("Quests", ql);
        ListTag tl = new ListTag();
        for (Map.Entry<UUID, Map<UUID, List<String>>> e : titles.entrySet()) {
            for (Map.Entry<UUID, List<String>> t : e.getValue().entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putUUID("Player", e.getKey());
                c.putUUID("Town", t.getKey());
                ListTag names = new ListTag();
                for (String s : t.getValue()) names.add(StringTag.valueOf(s));
                c.put("Titles", names);
                tl.add(c);
            }
        }
        tag.put("Titles", tl);
        CompoundTag d = new CompoundTag();
        for (Map.Entry<String, Integer> e : done.entrySet()) d.putInt(e.getKey(), e.getValue());
        tag.put("Done", d);
        CompoundTag h = new CompoundTag();
        for (Map.Entry<String, String> e : honours.entrySet()) h.putString(e.getKey(), e.getValue());
        tag.put("Honours", h);
        CompoundTag o = new CompoundTag();
        for (Map.Entry<String, String> e : owed.entrySet()) o.putString(e.getKey(), e.getValue());
        tag.put("Owed", o);
        ListTag sl = new ListTag();
        for (Map.Entry<UUID, Long> e : storyEnded.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Town", e.getKey());
            c.putLong("Day", e.getValue());
            sl.add(c);
        }
        tag.put("Stories", sl);
        CompoundTag s = new CompoundTag();
        for (Map.Entry<Integer, Long> e : secrets.entrySet()) s.putLong(Integer.toString(e.getKey()), e.getValue());
        tag.put("Secrets", s);
        return tag;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the book written out and read back, as a restart would. Returns how many quests came back. */
    public static int roundTripForTests() {
        QuestBook b = of();
        CompoundTag saved = b.save(new CompoundTag(), null);
        QuestBook back = load(saved, null);
        b.quests.clear();
        b.quests.putAll(back.quests);
        b.titles.clear();
        b.titles.putAll(back.titles);
        b.done.clear();
        b.done.putAll(back.done);
        b.honours.clear();
        b.honours.putAll(back.honours);
        b.nextId = back.nextId;
        return back.quests.size();
    }
}
