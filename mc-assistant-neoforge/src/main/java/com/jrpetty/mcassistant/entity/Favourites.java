package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * [ui] A player's favourites: a short list of folk it follows, and how each is getting on. Purely the player's choice:
 * nobody is followed until a player stars one (its profile, its talk screen, /village favourite add), and a player who
 * never does sees nothing new.
 *
 * <p>Kept with the world (one book for the server), by the player's UUID, each list in the player's own order and no
 * longer than {@link #CAP}. A folk is kept by its UUID, so it is followed across towns, while it is away or its ground
 * unloaded, and after it dies; each followed folk has one {@link Track}, shared by everyone who follows it:
 * <ul>
 * <li>its <b>last-known snapshot</b>: name, trade and level, wage, purse and worth, mood and health, its home and town,
 *     partner, children and friends, its firm, what it was doing and where (taken when it is looked at, about once a
 *     minute while it is loaded, so an unloaded folk still shows as it was last seen);</li>
 * <li>its <b>history</b>: one snapshot each morning it is about, the last {@link #DAILY} days kept day by day and older
 *     ones thinned to one a week ({@link #WEEKLY} of those);</li>
 * <li>its <b>life events</b>: what changed between two looks (a new trade, a rise, laid off, married, a child, a new
 *     house, a firm founded or joined, ill and better, a pay rise or cut) and the weighty things it remembers
 *     (Persona's memories: the town's chronicle as it lived it), the latest {@link #EVENTS} of them;</li>
 * <li>its <b>death</b>, with its day: it stays on the list, remembered, until the player takes it off.</li>
 * </ul>
 * A milestone is sent to whoever follows it and is on line (the client shows a toast if the player wants one).
 * Nothing here runs unless somebody follows somebody.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Favourites extends SavedData {

    private static final String ID = "mc_assistant_favourites";
    /** The most folk one player may follow, days kept day by day, weekly snapshots kept beyond, and events kept. */
    public static final int CAP = 24, DAILY = 60, WEEKLY = 40, EVENTS = 80;
    /** How often (server ticks) the followed folk who are about are looked at. */
    static final int LOOK_EVERY = 1200;
    /** The event kinds that are milestones: a toast for those who follow it. */
    private static final java.util.Set<String> MILESTONES = java.util.Set.of("level", "trade", "laid_off", "married",
        "child", "moved", "firm", "ill", "died", "widowed");

    /** Each player's list, in its own order. */
    final Map<UUID, List<UUID>> lists = new LinkedHashMap<>();
    /** Each followed folk's record. */
    final Map<UUID, Track> tracks = new HashMap<>();
    /** The last day snapshots were taken. */
    long snapDay = -1;

    @Nullable private static Favourites loose;
    /** Milestones go out through this (the network sets it; tests listen). */
    @Nullable private static Consumer<Notice> notices;

    /** A milestone for one player: whose, the folk's name, the event. */
    public record Notice(UUID player, UUID folk, String name, String kind, String text) {}

    public Favourites() {}

    static Favourites of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Favourites();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Favourites::new, Favourites::load, null), ID);
    }

    /** The network's sender for milestones. */
    public static void onNotice(@Nullable Consumer<Notice> sink) {
        notices = sink;
    }

    // ------------------------------------------------------------------ one followed folk

    /** One followed folk: its last-known snapshot, its history, its events, its death. */
    static final class Track {
        UUID folk;
        CompoundTag last = new CompoundTag();
        final List<CompoundTag> daily = new ArrayList<>(), weekly = new ArrayList<>(), events = new ArrayList<>();
        boolean dead;
        long diedDay = -1, firstDay = -1;
        /** Keys of the memories already on its timeline (day * 31 + text hash), the newest last. */
        final List<Integer> memKeys = new ArrayList<>();

        String name() { return last.getString("name"); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Folk", folk);
            t.put("Last", last);
            t.put("Daily", list(daily));
            t.put("Weekly", list(weekly));
            t.put("Events", list(events));
            t.putBoolean("Dead", dead);
            t.putLong("Died", diedDay);
            t.putLong("First", firstDay);
            t.put("Mem", new IntArrayTag(memKeys.stream().mapToInt(Integer::intValue).toArray()));
            return t;
        }

        static Track load(CompoundTag t) {
            Track k = new Track();
            k.folk = t.getUUID("Folk");
            k.last = t.getCompound("Last");
            read(t.getList("Daily", Tag.TAG_COMPOUND), k.daily);
            read(t.getList("Weekly", Tag.TAG_COMPOUND), k.weekly);
            read(t.getList("Events", Tag.TAG_COMPOUND), k.events);
            k.dead = t.getBoolean("Dead");
            k.diedDay = t.getLong("Died");
            k.firstDay = t.getLong("First");
            for (int m : t.getIntArray("Mem")) k.memKeys.add(m);
            return k;
        }

        private static ListTag list(List<CompoundTag> l) {
            ListTag out = new ListTag();
            out.addAll(l);
            return out;
        }

        private static void read(ListTag in, List<CompoundTag> out) {
            for (int i = 0; i < in.size(); i++) out.add(in.getCompound(i));
        }

        /** Every snapshot, oldest first: the weekly ones, then the daily. */
        List<CompoundTag> history() {
            List<CompoundTag> all = new ArrayList<>(weekly);
            all.addAll(daily);
            return all;
        }
    }

    // ------------------------------------------------------------------ saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag ls = new ListTag();
        for (Map.Entry<UUID, List<UUID>> e : lists.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Player", e.getKey());
            ListTag folk = new ListTag();
            for (UUID u : e.getValue()) {
                CompoundTag f = new CompoundTag();
                f.putUUID("U", u);
                folk.add(f);
            }
            c.put("Folk", folk);
            ls.add(c);
        }
        tag.put("Lists", ls);
        ListTag ts = new ListTag();
        for (Track t : tracks.values()) ts.add(t.save());
        tag.put("Tracks", ts);
        tag.putLong("SnapDay", snapDay);
        return tag;
    }

    static Favourites load(CompoundTag tag, HolderLookup.Provider registries) {
        Favourites b = new Favourites();
        ListTag ls = tag.getList("Lists", Tag.TAG_COMPOUND);
        for (int i = 0; i < ls.size(); i++) {
            CompoundTag c = ls.getCompound(i);
            List<UUID> folk = new ArrayList<>();
            ListTag fl = c.getList("Folk", Tag.TAG_COMPOUND);
            for (int j = 0; j < fl.size(); j++) folk.add(fl.getCompound(j).getUUID("U"));
            b.lists.put(c.getUUID("Player"), folk);
        }
        ListTag ts = tag.getList("Tracks", Tag.TAG_COMPOUND);
        for (int i = 0; i < ts.size(); i++) {
            Track t = Track.load(ts.getCompound(i));
            b.tracks.put(t.folk, t);
        }
        b.snapDay = tag.getLong("SnapDay");
        return b;
    }

    /** Tests: the book written out and read back, as a save and a load of the world do. */
    public static void reloadForTests(HolderLookup.Provider registries) {
        Favourites b = of();
        Favourites again = load(b.save(new CompoundTag(), registries), registries);
        b.lists.clear();
        b.lists.putAll(again.lists);
        b.tracks.clear();
        b.tracks.putAll(again.tracks);
        b.snapDay = again.snapDay;
    }

    /** Tests: nobody follows anybody. */
    public static void resetForTests() {
        Favourites b = of();
        b.lists.clear();
        b.tracks.clear();
        b.snapDay = -1;
        b.setDirty();
    }

    // ------------------------------------------------------------------ the list

    /** The folk this player follows, in its order. */
    public static List<UUID> list(UUID player) {
        List<UUID> l = of().lists.get(player);
        return l == null ? List.of() : List.copyOf(l);
    }

    public static boolean has(UUID player, UUID folk) {
        List<UUID> l = of().lists.get(player);
        return l != null && l.contains(folk);
    }

    /** What adding a folk came to. */
    public enum Added { ADDED, ALREADY, FULL }

    /** Follow this folk (now about). */
    public static Added add(ServerPlayer player, VillageFolkEntity f) {
        Favourites b = of();
        List<UUID> l = b.lists.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
        if (l.contains(f.getUUID())) return Added.ALREADY;
        if (l.size() >= CAP) return Added.FULL;
        l.add(f.getUUID());
        Track t = b.tracks.get(f.getUUID());
        long day = day(f.level());
        if (t == null) {
            t = new Track();
            t.folk = f.getUUID();
            t.firstDay = day;
            b.tracks.put(f.getUUID(), t);
            t.last = snapshot(f);
            // What it remembers already, the weighty things: its story so far, on its timeline from the first.
            memories(t, f, 10);
            event(t, day, "followed", "First followed, on day " + day + ".");
            t.daily.add(t.last.copy());
        } else {
            look(b, t, f, false);
        }
        b.setDirty();
        return Added.ADDED;
    }

    /** Stop following this folk; its record goes when nobody follows it. */
    public static boolean remove(UUID player, UUID folk) {
        Favourites b = of();
        List<UUID> l = b.lists.get(player);
        if (l == null || !l.remove(folk)) return false;
        if (l.isEmpty()) b.lists.remove(player);
        boolean anyone = false;
        for (List<UUID> o : b.lists.values()) anyone |= o.contains(folk);
        if (!anyone) b.tracks.remove(folk);
        b.setDirty();
        return true;
    }

    /** Move a folk up (negative) or down its player's list. */
    public static boolean move(UUID player, UUID folk, int by) {
        Favourites b = of();
        List<UUID> l = b.lists.get(player);
        if (l == null) return false;
        int i = l.indexOf(folk);
        if (i < 0) return false;
        int j = Math.max(0, Math.min(l.size() - 1, i + by));
        if (j == i) return false;
        l.remove(i);
        l.add(j, folk);
        b.setDirty();
        return true;
    }

    /** A followed folk of this player's, by name (any case), alive or not; null if none. */
    @Nullable
    public static UUID byName(UUID player, String name) {
        Favourites b = of();
        for (UUID u : list(player)) {
            Track t = b.tracks.get(u);
            if (t != null && t.name().equalsIgnoreCase(name.trim())) return u;
        }
        return null;
    }

    /** The last-known snapshot of a followed folk (a copy), or an empty tag. */
    public static CompoundTag last(UUID folk) {
        Track t = of().tracks.get(folk);
        return t == null ? new CompoundTag() : t.last.copy();
    }

    public static boolean dead(UUID folk) {
        Track t = of().tracks.get(folk);
        return t != null && t.dead;
    }

    /** How many snapshots its history holds (daily and weekly). */
    public static int historySize(UUID folk) {
        Track t = of().tracks.get(folk);
        return t == null ? 0 : t.daily.size() + t.weekly.size();
    }

    /** Its events (copies), oldest first. */
    public static List<CompoundTag> events(UUID folk) {
        Track t = of().tracks.get(folk);
        List<CompoundTag> out = new ArrayList<>();
        if (t != null) for (CompoundTag e : t.events) out.add(e.copy());
        return out;
    }

    // ------------------------------------------------------------------ looking at them

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 100 != 61) return;
        Favourites b = of();
        if (b.tracks.isEmpty()) return;
        Guard.run("favourites", () -> {
            long day = day(event.getServer().overworld());
            if (day != b.snapDay) morning(event.getServer(), day);
            else if (tick % LOOK_EVERY == 61) lookAll(event.getServer());
        });
    }

    /** Every followed folk who is about, looked at: its snapshot brought up to date and what changed put down. */
    public static void lookAll(MinecraftServer server) {
        Favourites b = of();
        for (Track t : b.tracks.values()) {
            if (t.dead) continue;
            VillageFolkEntity f = find(server, t.folk);
            if (f != null) look(b, t, f, false);
        }
    }

    /** A new day: every followed folk who is about looked at and its snapshot put in its history. */
    public static void morning(MinecraftServer server, long day) {
        Favourites b = of();
        b.snapDay = day;
        for (Track t : b.tracks.values()) {
            if (t.dead) continue;
            VillageFolkEntity f = find(server, t.folk);
            if (f != null) look(b, t, f, true);
        }
        b.setDirty();
    }

    /** It died: one last look, then remembered with its day. */
    public static void died(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.isShowcase()) return;
        Favourites b = of();
        Track t = b.tracks.get(f.getUUID());
        if (t == null || t.dead) return;
        look(b, t, f, false);
        long day = day(level);
        t.dead = true;
        t.diedDay = day;
        t.last.putBoolean("dead", true);
        t.last.putLong("died", day);
        event(b, t, day, "died", f.displayNameCap() + " died, aged " + f.ageYears() + ".");
        b.setDirty();
    }

    @Nullable
    static VillageFolkEntity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof VillageFolkEntity f && f.isAlive()) return f;
        }
        return null;
    }

    private static void look(Favourites b, Track t, VillageFolkEntity f, boolean morning) {
        CompoundTag now = snapshot(f);
        long day = now.getLong("day");
        if (!t.last.isEmpty()) changes(b, t, t.last, now, day);
        memories(t, f, 6);
        t.last = now;
        if (morning) {
            CompoundTag s = now.copy();
            long lastDay = t.daily.isEmpty() ? Long.MIN_VALUE : t.daily.get(t.daily.size() - 1).getLong("day");
            if (lastDay == day) t.daily.set(t.daily.size() - 1, s);
            else if (lastDay < day) t.daily.add(s);                         // a clock put back adds nothing out of turn
            compact(t);
        }
        b.setDirty();
    }

    /** The history kept to its size: past {@link #DAILY} daily snapshots, the oldest goes to the weekly list if a week
     *  has passed since the last weekly one, else it is let go; past {@link #WEEKLY} weekly ones, the oldest goes. */
    static void compact(Track t) {
        while (t.daily.size() > DAILY) {
            CompoundTag old = t.daily.remove(0);
            long lastWeek = t.weekly.isEmpty() ? Long.MIN_VALUE / 2 : t.weekly.get(t.weekly.size() - 1).getLong("day");
            if (old.getLong("day") - lastWeek >= 7) t.weekly.add(old);
        }
        while (t.weekly.size() > WEEKLY) t.weekly.remove(0);
    }

    /** Tests: so many days of snapshots put down at once, as that many mornings would. */
    public static void historyForTests(UUID folk, int days) {
        Track t = of().tracks.get(folk);
        if (t == null) return;
        long d = t.last.getLong("day");
        for (int i = 0; i < days; i++) {
            CompoundTag s = t.last.copy();
            s.putLong("day", ++d);
            t.daily.add(s);
            compact(t);
        }
    }

    /** What it is like now, for its history and its card on the list. */
    static CompoundTag snapshot(VillageFolkEntity f) {
        CompoundTag s = new CompoundTag();
        s.putLong("day", day(f.level()));
        s.putString("name", f.displayNameCap());
        StationTask job = f.stationTask();
        s.putString("trade", f.isBaby() ? "Child" : job == StationTask.NONE ? "No trade" : job.title);
        s.putInt("ord", job.ordinal());
        s.putBoolean("child", f.isBaby());
        s.putInt("years", f.ageYears());
        int lvl = f.veteranLevel(), xp = f.levelXp();
        s.putInt("level", lvl);
        int here = AssistantEntity.xpForLevel(lvl), next = AssistantEntity.xpForLevel(lvl + 1);
        s.putInt("xp_pct", next > here ? (int) Math.max(0, Math.min(100, (xp - here) * 100L / (next - here))) : 100);
        s.putInt("wage", f.isBaby() ? 0 : Wealth.wage(f));
        s.putInt("coin", f.purse());
        s.putInt("worth", f.isBaby() ? f.purse() : Wealth.worth(f));
        s.putInt("mood", f.persona().mood());
        s.putInt("health", Math.round(f.getHealth() * 100F / Math.max(1F, f.getMaxHealth())));
        s.putString("ill", Health.illWord(f));
        UUID village = f.ownerId();
        s.putString("town", village == null ? "" : Villages.name(village));
        CompoundTag home = Homes.profile(f);
        s.putString("home", home.getString("address").isEmpty() ? home.getString("lodging") : home.getString("address"));
        Social.Life life = f.life();
        s.putString("partner", life.partner() == null ? "" : life.partnerName());
        s.putInt("kids", life.children());
        s.putInt("friends", life.friends().size());
        s.putString("firm", firm(f));
        s.putString("doing", FolkTalk.nowDoing(f));
        s.putInt("x", f.blockPosition().getX());
        s.putInt("y", f.blockPosition().getY());
        s.putInt("z", f.blockPosition().getZ());
        s.putString("dim", f.level().dimension().location().toString());
        s.putInt("colour", FolkIdentity.rgb(f));
        s.putString("saying", FolkIdentity.saying(f));
        return s;
    }

    /** Its firm: "owns 40% of Ash & Co", "founded ...", "works at ...", or "". */
    static String firm(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return "";
        for (Business b : Businesses.all(village)) {
            if (!b.ownedBy(f.getUUID())) continue;
            int parts = 0;
            for (Business.Stake s : b.owners) if (f.getUUID().equals(s.who())) parts += s.parts();
            int pct = parts * 100 / Business.WHOLE;
            return pct >= 100 ? "owns " + b.name : "owns " + pct + "% of " + b.name;
        }
        Business works = Businesses.employer(village, f);
        return works == null ? "" : "works at " + works.name;
    }

    /** What changed between two looks, put down as events. */
    private static void changes(Favourites b, Track t, CompoundTag was, CompoundTag now, long day) {
        String n = now.getString("name");
        if (!was.getString("trade").equals(now.getString("trade"))) {
            if (now.getString("trade").equals("No trade") && !was.getBoolean("child")) event(b, t, day, "laid_off", n + " lost its place as " + article(was.getString("trade")) + ".");
            else if (!now.getBoolean("child")) event(b, t, day, "trade", n + " took up a new trade: " + now.getString("trade") + ".");
        }
        if (now.getInt("level") > was.getInt("level")) event(b, t, day, "level", n + " rose to level " + now.getInt("level") + " at its trade.");
        int w0 = was.getInt("wage"), w1 = now.getInt("wage");
        if (w0 > 0 && w1 >= w0 + Math.max(2, w0 / 10)) event(b, t, day, "raise", n + "'s wage went up, " + w0 + "c to " + w1 + "c a day.");
        else if (w0 > 0 && w1 > 0 && w1 <= w0 - Math.max(2, w0 / 10)) event(b, t, day, "cut", n + "'s wage went down, " + w0 + "c to " + w1 + "c a day.");
        String p0 = was.getString("partner"), p1 = now.getString("partner");
        if (p0.isEmpty() && !p1.isEmpty()) event(b, t, day, "married", n + " married " + p1 + ".");
        else if (!p0.isEmpty() && p1.isEmpty()) event(b, t, day, "widowed", n + " lost " + p0 + ".");
        if (now.getInt("kids") > was.getInt("kids")) event(b, t, day, "child", n + " had a child (" + now.getInt("kids") + " now).");
        String h0 = was.getString("home"), h1 = now.getString("home");
        if (!h1.isEmpty() && !h1.equals(h0)) event(b, t, day, "moved", n + " moved house: " + h1 + ".");
        String f0 = was.getString("firm"), f1 = now.getString("firm");
        if (!f1.isEmpty() && !f1.equals(f0)) event(b, t, day, "firm", n + " " + f1 + ".");
        String i0 = was.getString("ill"), i1 = now.getString("ill");
        if (i0.isEmpty() && !i1.isEmpty()) event(b, t, day, "ill", n + " fell ill: " + i1 + ".");
        else if (!i0.isEmpty() && i1.isEmpty()) event(b, t, day, "well", n + " is well again.");
        if (!was.getString("town").isEmpty() && !now.getString("town").isEmpty() && !was.getString("town").equals(now.getString("town"))) {
            event(b, t, day, "moved", n + " moved to " + now.getString("town") + ".");
        }
    }

    /** The weighty things it remembers that are not on its timeline yet, oldest first, at most {@code most}. */
    private static void memories(Track t, VillageFolkEntity f, int most) {
        List<Persona.Memory> mem = f.persona().memories();
        List<Persona.Memory> fresh = new ArrayList<>();
        for (int i = mem.size() - 1; i >= 0 && fresh.size() < most; i--) {
            Persona.Memory m = mem.get(i);
            if (m.weight() < 7) continue;
            int key = (int) (m.day() * 31 + m.text().hashCode());
            if (t.memKeys.contains(key)) break;
            fresh.add(0, m);
        }
        for (Persona.Memory m : fresh) {
            t.memKeys.add((int) (m.day() * 31 + m.text().hashCode()));
            event(t, m.day(), "memory", m.text());
        }
        while (t.memKeys.size() > 48) t.memKeys.remove(0);
    }

    private static void event(Favourites b, Track t, long day, String kind, String text) {
        event(t, day, kind, text);
        if (!MILESTONES.contains(kind) || notices == null) return;
        for (Map.Entry<UUID, List<UUID>> e : b.lists.entrySet()) {
            if (!e.getValue().contains(t.folk)) continue;
            Notice n = new Notice(e.getKey(), t.folk, t.name(), kind, text);
            Guard.run("favourites notice", () -> notices.accept(n));      // a toast that cannot go never stops the record
        }
    }

    private static void event(Track t, long day, String kind, String text) {
        CompoundTag e = new CompoundTag();
        e.putLong("day", day);
        e.putString("kind", kind);
        e.putString("text", text);
        t.events.add(e);
        while (t.events.size() > EVENTS) t.events.remove(0);
    }

    static long day(net.minecraft.world.level.Level level) {
        return level.getDayTime() / 24000L;
    }

    private static String article(String trade) {
        String t = trade.toLowerCase(java.util.Locale.ROOT);
        return ("aeiou".indexOf(t.isEmpty() ? 'x' : t.charAt(0)) >= 0 ? "an " : "a ") + t;
    }

    // ------------------------------------------------------------------ for the screens

    /** The list, as the favourites screen shows it: each folk's card, with how it has moved since yesterday and last week. */
    public static CompoundTag listTag(ServerPlayer player) {
        Favourites b = of();
        CompoundTag out = new CompoundTag();
        out.putString("kind", "list");
        out.putInt("cap", CAP);
        out.putLong("day", day(player.level()));
        ListTag cards = new ListTag();
        MinecraftServer server = player.getServer();
        for (UUID u : list(player.getUUID())) {
            Track t = b.tracks.get(u);
            if (t == null) continue;
            VillageFolkEntity f = server == null || t.dead ? null : find(server, u);
            if (f != null) look(b, t, f, false);                         // as it is now, when it is about
            CompoundTag c = t.last.copy();
            c.putUUID("uuid", u);
            c.putBoolean("dead", t.dead);
            c.putLong("died", t.diedDay);
            c.putBoolean("about", f != null);
            c.putInt("entity", f != null && f.level() == player.level() ? f.getId() : -1);
            List<CompoundTag> hist = t.history();
            // Since yesterday and since a week ago; followed for less than that, since the first snapshot.
            CompoundTag first = hist.isEmpty() ? null : hist.get(0);
            CompoundTag yesterday = at(hist, c.getLong("day") - 1), week = at(hist, c.getLong("day") - 7);
            if (yesterday == null) yesterday = first;
            if (week == null) week = first;
            for (String k : new String[]{ "level", "coin", "mood", "wage", "worth" }) {
                c.putInt("d1_" + k, yesterday == null ? 0 : c.getInt(k) - yesterday.getInt(k));
                c.putInt("d7_" + k, week == null ? 0 : c.getInt(k) - week.getInt(k));
            }
            c.putIntArray("spark", series(hist, "worth", 14));
            c.putInt("events", t.events.size());
            if (!t.events.isEmpty()) c.putString("latest", t.events.get(t.events.size() - 1).getString("text"));
            cards.add(c);
        }
        out.put("cards", cards);
        return out;
    }

    /** One followed folk in full: its last snapshot, its history as series, its events, and (when it is about) its
     *  identity and live profile. Null if this player does not follow it. */
    @Nullable
    public static CompoundTag detailTag(ServerPlayer player, UUID folk) {
        Favourites b = of();
        if (!has(player.getUUID(), folk)) return null;
        Track t = b.tracks.get(folk);
        if (t == null) return null;
        MinecraftServer server = player.getServer();
        VillageFolkEntity f = server == null || t.dead ? null : find(server, folk);
        if (f != null) look(b, t, f, false);
        CompoundTag out = new CompoundTag();
        out.putString("kind", "detail");
        out.putUUID("uuid", folk);
        CompoundTag last = t.last.copy();
        last.putBoolean("dead", t.dead);
        last.putLong("died", t.diedDay);
        out.put("last", last);
        out.putBoolean("about", f != null);
        out.putInt("entity", f != null && f.level() == player.level() ? f.getId() : -1);
        out.putLong("first", t.firstDay);
        // Its history with how it is now at the end (today's point brought up to date, or added after the last morning).
        List<CompoundTag> hist = t.history();
        if (hist.isEmpty() || hist.get(hist.size() - 1).getLong("day") < t.last.getLong("day")) hist.add(t.last);
        else if (hist.get(hist.size() - 1).getLong("day") == t.last.getLong("day")) hist.set(hist.size() - 1, t.last);
        long[] days = new long[hist.size()];
        for (int i = 0; i < hist.size(); i++) days[i] = hist.get(i).getLong("day");
        out.putLongArray("days", days);
        for (String k : new String[]{ "level", "xp_pct", "coin", "wage", "worth", "mood", "health", "friends" }) {
            out.putIntArray(k, series(hist, k, hist.size()));
        }
        ListTag ev = new ListTag();
        for (int i = t.events.size() - 1; i >= 0; i--) ev.add(t.events.get(i).copy());   // newest first
        out.put("events", ev);
        if (f != null) out.put("self", FolkIdentity.tag(f));
        return out;
    }

    @Nullable
    private static CompoundTag at(List<CompoundTag> hist, long day) {
        CompoundTag best = null;
        for (CompoundTag s : hist) if (s.getLong("day") <= day) best = s;
        return best;
    }

    private static int[] series(List<CompoundTag> hist, String key, int n) {
        int len = Math.min(n, hist.size());
        int[] out = new int[len];
        for (int i = 0; i < len; i++) out[i] = hist.get(hist.size() - len + i).getInt(key);
        return out;
    }
}
