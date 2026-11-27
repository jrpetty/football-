package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.InventionTree.Invention;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LecternBlock;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tech-tree] The town's scholars: the few with the head for it, who work out its inventions (Inventions) in their own
 * time. Not a trade: a scholar keeps its own, as the teacher does, and goes to its desk of an evening (and on the day of
 * rest, the afternoon long).
 *
 * <p><b>Who.</b> A town of six keeps one, and one more for every fifteen folk, four at most. The best heads in it, by
 * their <b>aptitude</b> (0 to 100): a curious nature (35), a love of reading or of the stars (20), the years at its
 * trade (a level in two, to 25), the teacher and the museum's curator (10 each), one who hopes to write a book (10). Never
 * the leader, never the watch, never a child. Chosen once and kept; one that dies, leaves or takes up the watch is replaced
 * by the next best the next morning, and the chronicle says so ("Ada Reed took up a scholar's desk").
 *
 * <p><b>At the desk.</b> Off work, free and awake, it goes to its desk: one of the academy's lecterns once the academy
 * stands (blueprints/academy.txt), else the town library's, the library's, the school's or the meeting hall's, one each
 * as far as they go. There it stands at the lectern, its eyes on its notes, a page turning (the enchanting glyphs over
 * the desk), now and then a wisp of smoke off the experiment on the bench. Every beat at the desk (BEAT ticks) is learning
 * for the town's next morning (Inventions.rate), half again in the academy; its first beat of the day writes up a page of
 * notes on a sheet of the stores' paper (no paper: no notes, and its evening counts for half). A eureka is a chime and a
 * shower of sparks and the scholar beside itself; a failed experiment a bang and a cloud of smoke (nothing harmed).
 *
 * <p><b>Kept.</b> The scholars, their aptitude, why they were chosen and their beats at the desk the day before are kept
 * with the town's inventions (Inventions' SavedData), so a restart loses nothing.
 */
public final class Scholars {

    private Scholars() {}

    /** A town keeps one scholar from this many folk, one more for every PER, MOST at most. */
    static final int FROM = 6, PER = 15, MOST = 4;
    /** A beat at the desk (ticks). */
    static final int BEAT = 150;
    /** How near the lectern counts as at it. */
    static final double AT_DESK = 2.2;
    /** The study hours of a working evening, and of the day of rest (day ticks). */
    static final long EVENING_FROM = 11500L, EVENING_TO = 13700L, REST_FROM = 6000L, REST_TO = 11500L;
    /** The buildings whose lecterns are desks, best first. */
    static final String[] DESKS = { InventionWorks.ACADEMY, Library.STRUCTURE, "library", "school", "hall", "townhall" };

    /** Who is at a desk now: the lectern, when it came, its last beat. */
    private record AtDesk(BlockPos lectern, String building, long since, long lastBeat) {}

    private static final Map<UUID, AtDesk> AT = new ConcurrentHashMap<>();
    /** A building's lecterns, by its anchor, as last looked for: {when, the lecterns}. */
    private static final Map<Long, Object[]> LECTERNS = new ConcurrentHashMap<>();

    static void resetForTests() {
        AT.clear();
        LECTERNS.clear();
        instant = false;
    }

    /** Tests: a scholar's study goes on wherever it stands (no walking to a desk). */
    static boolean instant;

    public static void instantForTests(boolean on) {
        instant = on;
    }

    // ------------------------------------------------------------------ who

    /** The town's scholars, kept with its inventions. */
    static ListTag list(UUID village) {
        return Civics.list(Inventions.town(village), "scholars");
    }

    @Nullable
    static CompoundTag entry(UUID village, UUID folk) {
        ListTag l = list(village);
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getString("id").equals(folk.toString())) return l.getCompound(i);
        return null;
    }

    @Nullable
    static CompoundTag entry(UUID folk) {
        for (Villages.Village v : Villages.every()) {
            CompoundTag e = entry(v.id(), folk);
            if (e != null) return e;
        }
        return null;
    }

    /** The town's scholars, by id. */
    public static List<UUID> appointed(@Nullable UUID village) {
        List<UUID> out = new ArrayList<>();
        if (village == null) return out;
        ListTag l = list(village);
        for (int i = 0; i < l.size(); i++) {
            try {
                out.add(UUID.fromString(l.getCompound(i).getString("id")));
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    /** Is this folk one of its town's scholars? */
    public static boolean is(VillageFolkEntity f) {
        return f.ownerId() != null && entry(f.ownerId(), f.getUUID()) != null;
    }

    /** How many a town of so many keeps. */
    static int wanted(int folk) {
        return folk < FROM ? 0 : Math.min(MOST, 1 + folk / PER);
    }

    /**
     * Its head for it, 0 to 100: a curious nature, a reader's or a stargazer's love, its years at its trade, the
     * teacher's and the curator's learning, a hope of writing a book.
     */
    static int aptitude(VillageFolkEntity f) {
        int a = 0;
        if (f.life().has(Social.Trait.CURIOUS)) a += 35;
        Persona.Hobby h = f.persona().hobby();
        if (h == Persona.Hobby.READING || h == Persona.Hobby.STARGAZING) a += 20;
        int best = 0;
        for (AssistantEntity.StationTask t : AssistantEntity.StationTask.values()) {
            if (t != AssistantEntity.StationTask.NONE) best = Math.max(best, f.tradeLevel(t));
        }
        a += Math.min(25, best / 2);
        if (f.level() instanceof ServerLevel level && f.ownerId() != null && School.teacher(level, f.ownerId(), false) == f) a += 10;
        if (Museum.isCurator(f)) a += 10;
        if (f.persona().ambition() == Persona.Ambition.WRITE_BOOK) a += 10;
        return Math.max(0, Math.min(100, a));
    }

    /** Its aptitude: as it is now if it is about, else as it was when last seen. */
    public static int aptitude(@Nullable ServerLevel level, UUID folk) {
        if (level != null && level.getEntity(folk) instanceof VillageFolkEntity f) return aptitude(f);
        CompoundTag e = entry(folk);
        return e == null ? 0 : e.getInt("apt");
    }

    /** Why it was chosen, in words ("curious, a reader, the teacher"). */
    static String why(VillageFolkEntity f) {
        List<String> w = new ArrayList<>();
        if (f.life().has(Social.Trait.CURIOUS)) w.add("curious");
        Persona.Hobby h = f.persona().hobby();
        if (h == Persona.Hobby.READING) w.add("a reader");
        if (h == Persona.Hobby.STARGAZING) w.add("a stargazer");
        if (f.level() instanceof ServerLevel level && f.ownerId() != null && School.teacher(level, f.ownerId(), false) == f) w.add("the teacher");
        if (Museum.isCurator(f)) w.add("the curator");
        if (f.persona().ambition() == Persona.Ambition.WRITE_BOOK) w.add("would write a book");
        if (w.isEmpty()) w.add("the best head to hand");
        return String.join(", ", w);
    }

    public static String whyChosen(@Nullable ServerLevel level, UUID folk) {
        if (level != null && level.getEntity(folk) instanceof VillageFolkEntity f) return why(f);
        CompoundTag e = entry(folk);
        return e == null ? "" : e.getString("why");
    }

    public static String name(@Nullable ServerLevel level, UUID folk) {
        if (level != null && level.getEntity(folk) instanceof VillageFolkEntity f) return f.displayNameCap();
        CompoundTag e = entry(folk);
        return e == null ? "a scholar" : e.getString("name");
    }

    /** Could it be a scholar: grown, of the town, not its leader, not the watch, not a stand-in. */
    static boolean fit(VillageFolkEntity f, UUID village) {
        if (f.isBaby() || !f.isAlive() || f.isShowcase() || f.isHired() || !village.equals(f.ownerId())) return false;
        if (f.stationTask() == AssistantEntity.StationTask.GUARD) return false;
        return !f.getUUID().equals(Villages.elder(village));
    }

    /**
     * The scholars seen to (Inventions.morning): those gone (dead, left, the watch, the leader now) let go, and the
     * places filled by the best heads in the town not already at a desk. Folk not loaded keep their places.
     */
    static void appoint(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        ListTag l = list(id);
        List<VillageFolkEntity> folk = Civics.grown(id);
        boolean anyLoaded = !folk.isEmpty();
        for (int i = l.size() - 1; i >= 0; i--) {
            CompoundTag e = l.getCompound(i);
            UUID who;
            try {
                who = UUID.fromString(e.getString("id"));
            } catch (IllegalArgumentException bad) {
                l.remove(i);
                continue;
            }
            VillageFolkEntity f = level.getEntity(who) instanceof VillageFolkEntity g ? g : null;
            // Not about: kept, unless the rest of the town is and it has not been seen these five days (dead, or gone).
            boolean gone = f == null ? anyLoaded && day - e.getLong("seen") > 5 : !fit(f, id);
            if (!gone) {
                if (f != null) {
                    e.putInt("apt", aptitude(f));
                    e.putString("name", f.displayNameCap());
                    e.putLong("seen", day);
                }
                continue;
            }
            l.remove(i);
            AT.remove(who);
            Villages.tell(id, day, e.getString("name") + " left the scholars' desks" + (f != null && f.stationTask() == AssistantEntity.StationTask.GUARD
                ? " for the watch" : f != null && who.equals(Villages.elder(id)) ? " to lead the town" : ""));
        }
        int want = wanted(Villages.headcount(id));
        while (l.size() > want) {
            CompoundTag e = l.getCompound(l.size() - 1);
            l.remove(l.size() - 1);
            Villages.tell(id, day, e.getString("name") + " gave up a scholar's desk: the town is smaller now");
        }
        if (l.size() < want && anyLoaded) {
            List<VillageFolkEntity> pool = new ArrayList<>();
            for (VillageFolkEntity f : folk) if (fit(f, id) && entry(id, f.getUUID()) == null) pool.add(f);
            pool.sort((a, b) -> Integer.compare(aptitude(b), aptitude(a)));
            for (VillageFolkEntity f : pool) {
                if (l.size() >= want) break;
                CompoundTag e = new CompoundTag();
                e.putString("id", f.getUUID().toString());
                e.putString("name", f.displayNameCap());
                e.putInt("apt", aptitude(f));
                e.putString("why", why(f));
                e.putLong("since", day);
                e.putLong("seen", day);
                l.add(e);
                Villages.tell(id, day, f.displayNameCap() + " took up a scholar's desk (" + why(f) + ")");
                f.persona().remember(day, "the town chose me as one of its scholars", 5);
                FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "A scholar! Me! I'll not let the town down.",
                    "They want me at the desks of an evening. I've a hundred ideas already."));
            }
        }
        if (l.isEmpty() && want > 0 && !Inventions.town(id).getBoolean("noScholarsTold") && anyLoaded) {
            Inventions.town(id).putBoolean("noScholarsTold", true);
            Villages.tell(id, day, "with nobody to take up a scholar's desk, the town's inventing has all but stopped");
        }
        if (!l.isEmpty()) Inventions.town(id).remove("noScholarsTold");
        Inventions.changed(null);
    }

    /** The best of the town's scholars that is about (the one who speaks for them), or null. */
    @Nullable
    static VillageFolkEntity lead(ServerLevel level, UUID village) {
        VillageFolkEntity best = null;
        int b = -1;
        for (UUID s : appointed(village)) {
            if (!(level.getEntity(s) instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            int a = aptitude(f);
            if (a > b) { b = a; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the beats

    /** Its beats at the desk on that day. */
    public static int beats(UUID folk, long day) {
        CompoundTag e = entry(folk);
        if (e == null) return 0;
        return e.getLong("beatDay") == day ? e.getInt("beats") : e.getLong("lastDay") == day ? e.getInt("lastBeats") : 0;
    }

    /** Were its beats that day at the academy's desks? */
    public static boolean atAcademy(UUID folk, long day) {
        CompoundTag e = entry(folk);
        if (e == null) return false;
        return e.getLong("beatDay") == day ? e.getBoolean("acad") : e.getLong("lastDay") == day && e.getBoolean("lastAcad");
    }

    /** One beat at the desk, kept with the world; the first of the day a page of notes on the stores' paper. */
    static void beat(ServerLevel level, VillageFolkEntity f, boolean academy) {
        UUID id = f.ownerId();
        if (id == null) return;
        CompoundTag e = entry(id, f.getUUID());
        if (e == null) return;
        long day = level.getDayTime() / 24000L;
        if (e.getLong("beatDay") != day) {
            e.putLong("lastDay", e.getLong("beatDay"));
            e.putInt("lastBeats", e.getInt("beats"));
            e.putBoolean("lastAcad", e.getBoolean("acad"));
            e.putLong("beatDay", day);
            e.putInt("beats", 0);
            e.putBoolean("acad", false);
            Villages.Village v = Villages.get(id);
            boolean paper = v != null && Crafts.take(level, v, s -> s.is(Items.PAPER), 1);
            e.putBoolean("paper", paper);
            if (paper) {
                CompoundTag t = Inventions.town(id);
                t.putInt("notes", t.getInt("notes") + 1);
            }
        }
        // No paper for its notes: every other beat goes for nothing.
        if (!e.getBoolean("paper") && level.getGameTime() / BEAT % 2 == 1) return;
        e.putInt("beats", e.getInt("beats") + 1);
        if (academy) e.putBoolean("acad", true);
        Inventions.changed(null);
    }

    /** Tests: so many beats at the desk on that day. */
    public static void beatsForTests(UUID village, UUID folk, long day, int beats, boolean academy) {
        CompoundTag e = entry(village, folk);
        if (e == null) return;
        e.putLong("beatDay", day);
        e.putInt("beats", beats);
        e.putBoolean("acad", academy);
        e.putBoolean("paper", true);
        Inventions.changed(null);
    }

    /** Tests: this folk made one of the town's scholars now, whatever its head. */
    public static void appointForTests(UUID village, VillageFolkEntity f) {
        if (entry(village, f.getUUID()) != null) return;
        CompoundTag e = new CompoundTag();
        e.putString("id", f.getUUID().toString());
        e.putString("name", f.displayNameCap());
        e.putInt("apt", aptitude(f));
        e.putString("why", why(f));
        e.putLong("seen", f.level().getDayTime() / 24000L);
        list(village).add(e);
        Inventions.changed(null);
    }

    // ------------------------------------------------------------------ the desks

    /** The lecterns of a building, looked for round its anchor (and kept a minute). */
    static List<BlockPos> lecterns(ServerLevel level, Ledger.Building b) {
        long key = b.anchor().asLong();
        Object[] got = LECTERNS.get(key);
        long now = level.getGameTime();
        if (got != null && now - (Long) got[0] < 1200L && now >= (Long) got[0]) {
            @SuppressWarnings("unchecked") List<BlockPos> l = (List<BlockPos>) got[1];
            return l;
        }
        List<BlockPos> out = new ArrayList<>();
        if (level.isLoaded(b.anchor())) {
            for (BlockPos p : BlockPos.betweenClosed(b.anchor().offset(-6, -1, -6), b.anchor().offset(6, 5, 6))) {
                if (level.getBlockState(p).getBlock() instanceof LecternBlock) out.add(p.immutable());
            }
        }
        LECTERNS.put(key, new Object[]{ now, out });
        return out;
    }

    /** Its desk: {the lectern, the building} by the town's best building with a lectern free of the other scholars. */
    @Nullable
    static Object[] desk(ServerLevel level, VillageFolkEntity f, UUID village) {
        List<UUID> all = appointed(village);
        int me = Math.max(0, all.indexOf(f.getUUID()));
        int seen = 0;
        for (String name : DESKS) {
            Ledger.Building b = Villages.builtStructure(village, name);
            if (b == null) continue;
            List<BlockPos> ls = lecterns(level, b);
            for (BlockPos p : ls) {
                if (seen++ == me) return new Object[]{ p, name };
            }
        }
        // Fewer desks than scholars: they share the first.
        for (String name : DESKS) {
            Ledger.Building b = Villages.builtStructure(village, name);
            if (b == null) continue;
            List<BlockPos> ls = lecterns(level, b);
            if (!ls.isEmpty()) return new Object[]{ ls.get(me % ls.size()), name };
        }
        return null;
    }

    /** Its study hours: a working evening, or the day of rest's afternoon. */
    static boolean hours(ServerLevel level, UUID village) {
        long t = level.getDayTime() % 24000L;
        if (t >= EVENING_FROM && t < EVENING_TO) return true;
        return RestDay.now(village, level.getDayTime()) != null && t >= REST_FROM && t < REST_TO;
    }

    /**
     * From the folk's tick (VillageFolkEntity.aiStep), every few ticks: a scholar in its study hours, free, to its desk
     * and at it. True while it is (its own evening waits).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || f.isShowcase()) return release(f);
        if (!is(f) || Inventions.current(id) == null) return release(f);
        if (!instant && (!hours(level, id) || !Culture.free(f, level) || !f.offWorkNow())) return release(f);
        if (Assemblies.attending(f) || Families.busy(f)) return release(f);
        AtDesk at = AT.get(f.getUUID());
        long now = level.getGameTime();
        if (at == null || now - at.since() > 6000L || now < at.since()) {
            Object[] d = instant ? new Object[]{ f.blockPosition(), "" } : desk(level, f, id);
            if (d == null) return release(f);
            at = new AtDesk((BlockPos) d[0], (String) d[1], now, now);
            AT.put(f.getUUID(), at);
        }
        BlockPos lectern = at.lectern();
        if (!instant && !Civics.goTo(f, lectern, AT_DESK, 0.6)) {
            f.hobbyNow = "on the way to the scholars' desk";
            return true;
        }
        f.getLookControl().setLookAt(lectern.getX() + 0.5, lectern.getY() + 1.0, lectern.getZ() + 0.5);
        f.hobbyNow = "at the scholars' desk";
        f.lastLeisureTick = f.tickCount;
        if (now - at.lastBeat() >= BEAT) {
            AT.put(f.getUUID(), new AtDesk(lectern, at.building(), at.since(), now));
            boolean academy = InventionWorks.ACADEMY.equals(at.building());
            beat(level, f, academy);
            show(level, f, lectern, academy);
        }
        return true;
    }

    /** Between its looks: still at its desk? */
    public static boolean busy(VillageFolkEntity f) {
        AtDesk at = AT.get(f.getUUID());
        return at != null && f.level().getGameTime() - at.lastBeat() <= BEAT + 20;
    }

    private static boolean release(VillageFolkEntity f) {
        AT.remove(f.getUUID());
        return false;
    }

    /** What the player sees of a beat: the page turned (the glyphs), the hand at the notes, now and then the bench's smoke and a word. */
    static void show(ServerLevel level, VillageFolkEntity f, BlockPos lectern, boolean academy) {
        level.sendParticles(ParticleTypes.ENCHANT, lectern.getX() + 0.5, lectern.getY() + 1.3, lectern.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.4);
        f.swing(InteractionHand.MAIN_HAND);
        if (level.getRandom().nextInt(3) == 0) {
            BlockPos bench = academy ? lectern.above() : f.blockPosition().above(2);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, bench.getX() + 0.5, bench.getY() + 0.3, bench.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.01);
            level.playSound(null, lectern, SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.6F, 1.0F);
        }
        if (level.getRandom().nextInt(8) == 0) {
            Invention i = Inventions.current(f.ownerId());
            if (i != null) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Hmm. If the gearing went the other way...",
                "Now where did I put that drawing of the " + i.title.toLowerCase().replace("the ", "") + "?",
                "Again. Carefully this time.", "Write it down, write it all down.", "So close. So very close."));
        }
    }

    /** A eureka: a chime and a shower of sparks at the scholar, and the scholar beside itself. */
    static void eureka(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity who) {
        BlockPos at = who != null ? who.blockPosition() : deskOrCentre(level, v);
        level.playSound(null, at, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.NEUTRAL, 1.5F, 1.4F);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.getX() + 0.5, at.getY() + 2.0, at.getZ() + 0.5, 30, 0.4, 0.4, 0.4, 0.3);
        if (who != null) {
            FolkTalk.speak(who, FolkTalk.pick(level.getRandom(), "Eureka! I've got it!", "That's it! That's the answer!", "Eureka! Fetch paper, quick!"));
            who.persona().remember(level.getDayTime() / 24000L, "I had a eureka at the scholars' desk", 4);
        }
    }

    /** A failed experiment: a bang, a puff and a cloud of smoke at the academy (nothing broken, nobody hurt). */
    static void bang(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity who) {
        BlockPos at = deskOrCentre(level, v);
        level.playSound(null, at, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 0.7F, 1.3F);
        level.sendParticles(ParticleTypes.EXPLOSION, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 2, 0.3, 0.3, 0.3, 0.0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5, 30, 0.6, 0.6, 0.6, 0.02);
        if (who != null) FolkTalk.speak(who, FolkTalk.pick(level.getRandom(), "That was not meant to happen.", "*cough* Back to the drawing board.",
            "Well. Now we know what doesn't work."));
    }

    /** The academy's first lectern, else the town's centre. */
    static BlockPos deskOrCentre(ServerLevel level, Villages.Village v) {
        for (String name : DESKS) {
            Ledger.Building b = Villages.builtStructure(v.id(), name);
            if (b == null) continue;
            List<BlockPos> ls = lecterns(level, b);
            if (!ls.isEmpty()) return ls.get(0);
        }
        return v.centre();
    }

    // ------------------------------------------------------------------ its card

    /** Its card (FolkTalk.card): "Scholar (aptitude 55: curious, a reader); at the Pike; 9 beats at the desk yesterday". */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !is(f)) return "";
        long day = f.level().getDayTime() / 24000L;
        Invention i = Inventions.current(id);
        String desk = AT.containsKey(f.getUUID()) ? "at its desk now" : beats(f.getUUID(), day) > 0 ? beats(f.getUUID(), day) + " beats at the desk today"
            : beats(f.getUUID(), day - 1) + " beats at the desk yesterday";
        return "a scholar (head for it " + aptitude(f) + ": " + why(f) + ")" + (i == null ? "" : "; working on " + i.title) + "; " + desk;
    }
}
