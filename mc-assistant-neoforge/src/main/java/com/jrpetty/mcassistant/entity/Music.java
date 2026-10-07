package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The band and the choir [batchD] (Culture).
 *
 * <p><b>The band.</b> The folk who love music (Leisure's musicians), four at most, play together: at the
 * tavern on the evening of the day of rest (after the play, if there is one), and at weddings and feasts —
 * the weekly feast, a celebration, an honouring, Founding Day — standing together to one side of whoever
 * speaks, quiet through the speeches and playing through the procession and the feasting. Each plays a note
 * block, and a note block is a real thing: its own, if it has one, or one lent out of the town's stores for
 * the evening and put back after. A town that has made none (a note block is eight planks and a redstone)
 * has no band, and the books say so; the tavern's own note blocks and its tune go on as before. The lead
 * takes the tune on the flute's voice, the next the bass, the third the guitar's harmony and the fourth a
 * bell on the beat, a jig early in the evening and a slow air later (the tavern's own tunes), a march for a
 * wedding.
 *
 * <p><b>The choir.</b> At the morning service on the day of rest, in a town with a chapel, the musicians and
 * the sociable cheerful folk (six at most, two at least) stand before the altar facing the congregation and
 * sing the town's hymn a line at a time, in their own voices and in chords.
 */
public final class Music {

    private Music() {}

    static final int BAND = 4, CHOIR = 6;
    /** The band's evening at the tavern on the day of rest. */
    static final long TAVERN_FROM = 13000L, TAVERN_TO = 17000L;
    /** A wedding march, a beat to a note (-1 a rest). */
    static final int[] MARCH = { 12, -1, 12, 12, 17, -1, 17, -1, 16, -1, 14, 16, 17, -1, -1, -1,
        12, -1, 12, 12, 19, -1, 19, -1, 17, -1, 16, 14, 12, -1, -1, -1 };

    /** Who plays what, by their place in the band. */
    private static final List<Holder.Reference<SoundEvent>> VOICES = List.of(SoundEvents.NOTE_BLOCK_FLUTE, SoundEvents.NOTE_BLOCK_BASS,
        SoundEvents.NOTE_BLOCK_GUITAR, SoundEvents.NOTE_BLOCK_BELL);
    private static final String[] INSTRUMENTS = { "the tune", "the bass", "the guitar", "the bell" };

    enum Where { TAVERN, GATHERING }

    /** The band out tonight in one town: where, who (in order of their parts), whose note block is the stores'. */
    static final class Session {
        final Where where;
        final long day;
        final BlockPos spot;
        final Direction row, faces;
        final String what;
        final List<UUID> members = new ArrayList<>();
        final Set<UUID> lent = ConcurrentHashMap.newKeySet();
        int beat, played;
        boolean wedding;

        Session(Where where, long day, BlockPos spot, Direction row, Direction faces, String what) {
            this.where = where;
            this.day = day;
            this.spot = spot;
            this.row = row;
            this.faces = faces;
            this.what = what;
        }

        BlockPos place(int i) {
            return spot.relative(row, i);
        }
    }

    /** The choir at this morning's service. */
    static final class Choir {
        final long day;
        final Ledger.Building chapel;
        final List<UUID> members = new ArrayList<>();
        final List<String> sung = new ArrayList<>();
        int line;
        long nextAt;

        Choir(long day, Ledger.Building chapel) {
            this.day = day;
            this.chapel = chapel;
        }
    }

    private static final Map<UUID, Session> BANDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Choir> CHOIRS = new ConcurrentHashMap<>();
    /** Why there is no band tonight, in words, while there is none. */
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();
    /** When a band that could not get up (too few musicians, no note blocks) is next looked for. */
    private static final Map<UUID, Long> RETRY = new ConcurrentHashMap<>();

    static void resetForTests() {
        BANDS.clear();
        CHOIRS.clear();
        SHORT.clear();
        RETRY.clear();
    }

    // ------------------------------------------------------------------ who

    /** A musician of the town: grown, its pastime music, not the watch. */
    static boolean musician(VillageFolkEntity f) {
        return !f.isBaby() && !f.isShowcase() && f.persona().rolled() && f.persona().hobby() == Persona.Hobby.MUSIC
            && f.stationTask() != AssistantEntity.StationTask.GUARD;
    }

    /** The band: the town's musicians free to play, four at most, the same order every evening. */
    static List<VillageFolkEntity> band(ServerLevel level, UUID village, Set<UUID> not) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && musician(f) && !not.contains(f.getUUID()) && Culture.free(f, level)) out.add(f);
        }
        out.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        return out.subList(0, Math.min(BAND, out.size()));
    }

    /** The choir: the musicians, then the sociable and the cheerful, six at most. */
    static List<VillageFolkEntity> choir(ServerLevel level, UUID village) {
        List<VillageFolkEntity> first = new ArrayList<>(), then = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || !f.persona().rolled() || !Culture.free(f, level)) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) continue;          // the watch works the day of rest
            if (musician(f)) first.add(f);
            else if (f.life().has(Social.Trait.SOCIABLE) && f.life().has(Social.Trait.CHEERFUL)) then.add(f);
        }
        first.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        then.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        first.addAll(then);
        return first.subList(0, Math.min(CHOIR, first.size()));
    }

    // ------------------------------------------------------------------ the town's look

    /** Where the band should be now: a wedding or a feast under way, else the tavern on the day of rest; null for nowhere. */
    @Nullable
    static Session wanted(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        Assemblies.Assembly a = Assemblies.underWay(id);
        if (a != null && festive(a.kind)) {
            BlockPos side = a.focus.relative(a.audience.getClockWise(), 5);
            BlockPos ground = Watch.floorAt(level, side.getX(), side.getZ(), a.focus.getY());
            if (ground != null) {
                Session s = new Session(Where.GATHERING, day, ground, a.audience, a.audience.getCounterClockWise(), "at " + Assemblies.describe(a));
                s.wedding = a.kind == Assemblies.Kind.WEDDING;
                return s;
            }
        }
        Ledger.Building tav = Tavern.of(id);
        if (tav != null && RestDay.today(id, day) && t >= TAVERN_FROM && t < TAVERN_TO && !Theatre.onOrToCome(level, id) && level.isLoaded(tav.anchor())) {
            Direction back = tav.facing(), out = back.getOpposite();
            List<BlockPos> blocks = Tavern.noteBlocks(tav);
            BlockPos spot = blocks.isEmpty() ? tav.anchor().relative(back, 2) : blocks.get(0).relative(out);
            return new Session(Where.TAVERN, day, spot, back.getCounterClockWise(), out, "at the tavern");
        }
        return null;
    }

    static boolean festive(Assemblies.Kind k) {
        return k == Assemblies.Kind.WEDDING || k == Assemblies.Kind.FEAST || k == Assemblies.Kind.CELEBRATION
            || k == Assemblies.Kind.HONOUR || k == Assemblies.Kind.FOUNDING;
    }

    /** The town's look at its music (Culture, each second): the band out where it is wanted, or home; the choir at the service. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Session want = wanted(level, v);
        Session s = BANDS.get(id);
        if (s != null && (want == null || want.where != s.where || !want.spot.equals(s.spot) || want.day != s.day)) {
            end(level, v, s);
            s = null;
        }
        long now = level.getGameTime();
        if (s == null && want != null && now >= RETRY.getOrDefault(id, 0L) && start(level, v, want) == null) RETRY.put(id, now + 200L);
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        Choir c = CHOIRS.get(id);
        Ledger.Building chapel = Culture.building(id, "chapel");
        boolean service = chapel != null && RestDay.today(id, day) && t >= RestDay.SERVICE_FROM && t < RestDay.GAMES_FROM
            && level.isLoaded(chapel.anchor());
        if (c != null && (!service || c.day != day)) {
            CHOIRS.remove(id, c);
            c = null;
        }
        if (c == null && service) gather(level, v, chapel, day);
    }

    /** The band out: its musicians, each with a note block (its own, or one lent out of the stores). Null with fewer than two. */
    @Nullable
    static Session start(ServerLevel level, Villages.Village v, Session want) {
        UUID id = v.id();
        Set<UUID> not = ConcurrentHashMap.newKeySet();
        Assemblies.Assembly a = Assemblies.underWay(id);
        if (want.where == Where.GATHERING && a != null) {
            not.addAll(a.principals);                                   // the bride and the groom do not play at their own wedding
            if (a.host != null) not.add(a.host);
        }
        List<VillageFolkEntity> band = band(level, id, not);
        if (band.size() < 2) {
            SHORT.put(id, band.isEmpty() ? "musicians: nobody here loves music" : "musicians: only one here loves music");
            return null;
        }
        for (VillageFolkEntity f : band) {
            if (f.countCarried(st -> st.is(Items.NOTE_BLOCK)) > 0) {
                want.members.add(f.getUUID());
                continue;
            }
            if (Crafts.stock(level, v, st -> st.is(Items.NOTE_BLOCK)) <= 0) continue;
            if (!Crafts.take(level, v, st -> st.is(Items.NOTE_BLOCK), 1)) continue;
            ItemStack left = f.insertItem(new ItemStack(Items.NOTE_BLOCK));
            if (!left.isEmpty()) {
                Crafts.store(level, v, left);                          // a full pack: none for it
                continue;
            }
            want.members.add(f.getUUID());
            want.lent.add(f.getUUID());
        }
        if (want.members.size() < 2) {
            end(level, v, want);
            SHORT.put(id, "note blocks: the stores hold " + Crafts.stock(level, v, st -> st.is(Items.NOTE_BLOCK)) + " (a player each)");
            return null;
        }
        SHORT.remove(id);
        BANDS.put(id, want);
        return want;
    }

    /** The band home: the note blocks lent out of the stores put back. */
    static void end(ServerLevel level, Villages.Village v, Session s) {
        BANDS.remove(v.id(), s);
        for (UUID u : s.lent) {
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive() && f.removeMatching(st -> st.is(Items.NOTE_BLOCK), 1) == 1) {
                Crafts.store(level, v, new ItemStack(Items.NOTE_BLOCK));
            }
        }
        s.lent.clear();
        if (s.played > 16) {
            long day = level.getDayTime() / 24000L;
            if (Ledger.note(v.id(), "culture.band") == null) {
                Villages.tell(v.id(), day, "the town's band played for the first time, " + s.what);
            }
            Ledger.note(v.id(), "culture.band", day + "\t" + s.what);
        }
    }

    /** The choir gathered for this morning's service. */
    static void gather(ServerLevel level, Villages.Village v, Ledger.Building chapel, long day) {
        List<VillageFolkEntity> voices = choir(level, v.id());
        if (voices.size() < 2) return;
        Choir c = new Choir(day, chapel);
        for (VillageFolkEntity f : voices) c.members.add(f.getUUID());
        c.nextAt = level.getGameTime() + 200;
        CHOIRS.put(v.id(), c);
    }

    /** Where a voice of the choir stands: before the altar, in two rows, facing the pews. */
    static BlockPos place(Choir c, int i) {
        int[] across = { 0, -1, 1, -2, 2, 0 };
        return Culture.at(c.chapel, across[Math.min(i, across.length - 1)], 0, i < 5 ? 4 : 3);
    }

    // ------------------------------------------------------------------ the music itself

    /** A beat (every five ticks, Culture): the band playing where it stands together; the choir's next line and its chords. */
    static void beat(ServerLevel level, Villages.Village v) {
        Session s = BANDS.get(v.id());
        if (s != null && playingNow(s, v.id())) {
            List<VillageFolkEntity> there = new ArrayList<>();
            for (int i = 0; i < s.members.size(); i++) {
                if (level.getEntity(s.members.get(i)) instanceof VillageFolkEntity f && f.blockPosition().distSqr(s.place(i)) <= 2.5 * 2.5) there.add(f);
                else there.add(null);
            }
            int at = 0;
            for (VillageFolkEntity f : there) if (f != null) at++;
            if (at >= 2) play(level, s, there);
        }
        Choir c = CHOIRS.get(v.id());
        if (c != null) sing(level, v, c);
    }

    /** Is the band playing now (it plays through a wedding's procession and the feasting; it is quiet for the speeches)? */
    static boolean playingNow(Session s, UUID village) {
        if (s.where == Where.TAVERN) return true;
        Assemblies.Assembly a = Assemblies.underWay(village);
        if (a == null) return false;
        return a.kind == Assemblies.Kind.WEDDING
            ? a.phase == Assemblies.Phase.GATHER || a.phase == Assemblies.Phase.CLOSE || a.phase == Assemblies.Phase.DISPERSE
            : a.phase == Assemblies.Phase.MINGLE;
    }

    private static void play(ServerLevel level, Session s, List<VillageFolkEntity> there) {
        long t = level.getDayTime() % 24000L;
        int[] tune = s.wedding ? MARCH : t < 15000L ? Tavern.JIG : Tavern.AIR;
        int beat = s.beat++;
        int note = tune[Math.floorMod(beat, tune.length)];
        s.played++;
        if (note < 0) return;
        for (int i = 0; i < there.size(); i++) {
            VillageFolkEntity f = there.get(i);
            if (f == null) continue;
            int n;
            switch (i) {
                case 0 -> n = note;                                          // the tune
                case 1 -> { if (beat % 2 != 0) continue; n = note - 12; }    // the bass, on the beat
                case 2 -> { if (beat % 2 != 1) continue; n = note + 4; }     // the harmony, off it
                default -> { if (beat % 4 != 0) continue; n = note + 7; }    // a bell to mark the bar
            }
            n = Math.max(0, Math.min(24, n));
            float pitch = (float) Math.pow(2.0, (n - 12) / 12.0);
            level.playSound(null, f.getX(), f.getY() + 1.0, f.getZ(), VOICES.get(Math.min(i, VOICES.size() - 1)).value(),
                SoundSource.RECORDS, i == 0 ? 1.1F : 0.8F, pitch);
            level.sendParticles(ParticleTypes.NOTE, f.getX(), f.getY() + 2.2, f.getZ(), 0, n / 24.0, 0.0, 0.0, 1.0);
            if (beat % 8 == i) f.swing(net.minecraft.world.InteractionHand.OFF_HAND);
        }
    }

    /** The town's hymn, a line at a time. */
    static List<String> hymn(UUID village) {
        String town = Villages.name(village);
        String motto = Heraldry.motto(village);
        return List.of("Morning has come to " + town + ",", "bless the hands that sow the field,", "bless the well and bless the gate,",
            "bless the hearth and bless the yield.", "Keep the watch upon the wall,", "keep the lanterns burning bright,",
            motto != null ? motto + "," : "keep us, keep us, one and all,",
            "and bring us safely home tonight.");
    }

    private static void sing(ServerLevel level, Villages.Village v, Choir c) {
        long now = level.getGameTime();
        List<VillageFolkEntity> there = new ArrayList<>();
        for (int i = 0; i < c.members.size(); i++) {
            if (level.getEntity(c.members.get(i)) instanceof VillageFolkEntity f && f.blockPosition().distSqr(place(c, i)) <= 2.0 * 2.0) there.add(f);
        }
        if (there.size() < 2) return;
        // A chord under the line every second, in three voices.
        if (now % 20 == 1) {
            int[] chord = c.line % 2 == 0 ? new int[]{ 6, 10, 13 } : new int[]{ 8, 11, 15 };
            for (int i = 0; i < there.size(); i++) {
                VillageFolkEntity f = there.get(i);
                int n = chord[i % chord.length];
                level.playSound(null, f.getX(), f.getY() + 1.6, f.getZ(), i % 2 == 0 ? SoundEvents.NOTE_BLOCK_FLUTE.value()
                    : SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.RECORDS, 0.6F, (float) Math.pow(2.0, (n - 12) / 12.0));
                level.sendParticles(ParticleTypes.NOTE, f.getX(), f.getY() + 2.2, f.getZ(), 0, n / 24.0, 0.0, 0.0, 1.0);
            }
        }
        if (now < c.nextAt) return;
        List<String> hymn = hymn(v.id());
        String line = hymn.get(c.line % hymn.size());
        VillageFolkEntity voice = there.get(c.line % there.size());
        FolkTalk.speak(voice, "♪ " + line + " ♪");
        c.sung.add(line);
        c.line++;
        c.nextAt = now + 100;
        if (c.line == hymn.size() && Ledger.note(v.id(), "culture.choir") == null) {
            Ledger.note(v.id(), "culture.choir", Long.toString(c.day));
            Villages.tell(v.id(), c.day, "the choir sang the town's hymn at the morning service for the first time");
        }
    }

    // ------------------------------------------------------------------ the folk's part

    /** A folk's part in the band: to its place beside the others, facing the room or the crowd, its note block in hand. */
    static boolean band(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        Session s = BANDS.get(v.id());
        if (s == null) return false;
        int i = s.members.indexOf(f.getUUID());
        if (i < 0) return false;
        Culture.mark(f, Culture.Role.BAND, "playing " + INSTRUMENTS[Math.min(i, INSTRUMENTS.length - 1)] + " in the band " + s.what);
        BlockPos place = s.place(i);
        if (Culture.arrive(f, place, 0.8, 1.0)) {
            BlockPos look = place.relative(s.faces, 4);
            f.getLookControl().setLookAt(look.getX() + 0.5, f.getEyeY(), look.getZ() + 0.5);
            Culture.prop(f, Items.NOTE_BLOCK);
        }
        return true;
    }

    /** A folk's part in the choir: to its place before the altar, facing the pews. */
    static boolean choir(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        Choir c = CHOIRS.get(v.id());
        if (c == null) return false;
        int i = c.members.indexOf(f.getUUID());
        if (i < 0) return false;
        Culture.mark(f, Culture.Role.CHOIR, "singing in the choir at the chapel");
        BlockPos place = place(c, i);
        if (Culture.arrive(f, place, 0.8, 0.9)) {
            BlockPos look = place.relative(c.chapel.facing().getOpposite(), 5);
            f.getLookControl().setLookAt(look.getX() + 0.5, f.getEyeY(), look.getZ() + 0.5);
        }
        return true;
    }

    /** Is the band playing at the tavern tonight (its own tune waits: Tavern.play)? */
    public static boolean playing(UUID village) {
        Session s = BANDS.get(village);
        return s != null && s.where == Where.TAVERN;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: the band out now where it is wanted (a gathering, or the tavern on the day of rest). Its members, by name. */
    public static List<String> startForTests(ServerLevel level, Villages.Village v) {
        Session old = BANDS.get(v.id());
        if (old != null) end(level, v, old);
        Session want = wanted(level, v);
        Session s = want == null ? null : start(level, v, want);
        List<String> out = new ArrayList<>();
        if (s != null) for (UUID u : s.members) if (level.getEntity(u) instanceof VillageFolkEntity f) out.add(f.displayNameCap());
        return out;
    }

    /** Tests: the band sent home now (the stores' note blocks put back). */
    public static void endForTests(ServerLevel level, Villages.Village v) {
        Session s = BANDS.get(v.id());
        if (s != null) end(level, v, s);
    }

    /** Tests: the band's beats played so far, and where it plays ("TAVERN", "GATHERING"), or null with no band out. */
    @Nullable
    public static String bandForTests(UUID village) {
        Session s = BANDS.get(village);
        return s == null ? null : s.where + " played=" + s.played + " lent=" + s.lent.size() + " members=" + s.members.size();
    }

    /** Tests: where the band's player of this part stands tonight, or null with no band out. */
    @Nullable
    public static BlockPos placeForTests(UUID village, int i) {
        Session s = BANDS.get(village);
        return s == null ? null : s.place(i);
    }

    /** Tests: a beat of the band's (and the choir's) music now. */
    public static void beatForTests(ServerLevel level, Villages.Village v) {
        beat(level, v);
    }

    /** Tests: why there is no band, in words. */
    @Nullable
    public static String shortForTests(UUID village) {
        return SHORT.get(village);
    }

    /** Tests: the choir gathered now (the morning of the day of rest, a chapel standing). Its voices, by name. */
    public static List<String> choirForTests(ServerLevel level, Villages.Village v) {
        CHOIRS.remove(v.id());
        tick(level, v);
        Choir c = CHOIRS.get(v.id());
        List<String> out = new ArrayList<>();
        if (c != null) for (UUID u : c.members) if (level.getEntity(u) instanceof VillageFolkEntity f) out.add(f.displayNameCap());
        return out;
    }

    /** Tests: the lines of the hymn sung so far this morning. */
    public static List<String> sungForTests(UUID village) {
        Choir c = CHOIRS.get(village);
        return c == null ? List.of() : List.copyOf(c.sung);
    }

    /** Tests: where the choir's voice of this place stands. */
    @Nullable
    public static BlockPos choirPlaceForTests(UUID village, int i) {
        Choir c = CHOIRS.get(village);
        return c == null ? null : place(c, i);
    }

    // ------------------------------------------------------------------ what the player reads

    /** The folk's card: its part in the band, and in the choir. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !musician(f)) return null;
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity g && musician(g)) all.add(g);
        if (all.size() < 2) return "plays alone at the well: the town has no band without two musicians";
        all.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        int i = all.indexOf(f);
        String part = i >= 0 && i < BAND ? "plays " + INSTRUMENTS[i] + " in the town's band" : "sings with the band";
        return part + (Culture.building(id, "chapel") != null ? ", and in the choir" : "");
    }

    /** The Culture page's music: the band and its note blocks, where it plays; the choir and its hymn. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<String> band = new ArrayList<>(), choir = new ArrayList<>();
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && musician(f)) all.add(f);
        all.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        for (int i = 0; i < Math.min(BAND, all.size()); i++) band.add(all.get(i).displayNameCap() + " (" + INSTRUMENTS[i] + ")");
        out.put("band", Culture.strings(band));
        int blocks = Crafts.stock(level, v, st -> st.is(Items.NOTE_BLOCK));
        out.putInt("noteBlocks", blocks);
        Session s = BANDS.get(id);
        out.putString("now", s == null ? "" : "playing " + s.what);
        String why = SHORT.get(id);
        out.putString("short", why == null ? "" : why);
        Ledger.Building chapel = Culture.building(id, "chapel");
        out.putBoolean("chapel", chapel != null);
        Choir c = CHOIRS.get(id);
        if (c != null) {
            for (UUID u : c.members) if (level.getEntity(u) instanceof VillageFolkEntity f) choir.add(f.displayNameCap());
        } else {
            for (VillageFolkEntity f : all) if (choir.size() < CHOIR) choir.add(f.displayNameCap());
        }
        out.put("choir", Culture.strings(choir));
        out.put("hymn", Culture.strings(hymn(id)));
        List<String> lines = new ArrayList<>();
        lines.add(band.size() >= 2 ? "the band: " + String.join(", ", band) + "; " + blocks + " note blocks in the stores"
            + (s != null ? "; " + out.getString("now") : "")
            : "no band yet: it wants two who love music (" + band.size() + " now) and a note block each");
        if (!out.getString("short").isEmpty()) lines.add("the band waits on " + out.getString("short"));
        lines.add(chapel != null ? "the choir sings at the morning service on the day of rest" + (choir.isEmpty() ? "" : ": " + String.join(", ", choir))
            : "no choir until the town has a chapel");
        out.put("lines", Culture.strings(lines));
        return out;
    }
}
