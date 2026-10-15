package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.StairBlock;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The theatre [batchD] (Culture): an open-air stage (blueprints/theatre.txt), and the plays put on on it.
 *
 * <p><b>The building.</b> An Iron Age town of twenty with two or more folk who love a story, a tune or a bit
 * of carving (reading, music, whittling: Leisure's pastimes) wants one among its amenities: a raised wooden
 * stage with a back wall and the town's banner either side over it (Heraldry), steps up at either end, and
 * two rows of benches before it.
 *
 * <p><b>The play.</b> On the evening of the day of rest, after supper and unless it rains, two or three of
 * those players put on a short play, and the town comes to watch. The play is the town's own: a story out of
 * its chronicle, the weightiest it has not played yet — the night the raiders came at the gate, a wedding, a
 * birth, a death remembered, a building opened, a new age, the first diamond, the great storm, or how the town
 * began — told by its players in turn, in character (a teller, a guard and a raider; the bride and the groom),
 * a line every few seconds over their heads. The audience take the benches (the rest stand behind), face the
 * stage and react as a crowd does, and at the end the players bow and the town cheers. A player can come and
 * watch too. Into the town's history, everybody's memory and the books.
 *
 * <p>[culture-wonders] <b>The company and its nights.</b> The play is written by the town's playwright (Playwright)
 * out of the chronicle and the war books: the Battle of Ashby Field in three acts with its real Captain and its real
 * dead, a siege, a fire, a flood, an election, a wedding, a player's own deeds. Its beats are played out: the players
 * go to their marks, say their lines, fight with the wardrobe's wooden swords, die on the boards and get up again for
 * the bows, each dressed for the part (Troupe). Besides the day of rest a play is put on the evening after a great
 * event (a battle, a siege, a wedding) and on a festival's evening. The audience buy a ticket for a coin each (half
 * to the treasury, half to the players), boo the enemy and weep at a death; the folk who were played in it say so.
 * The cast rehearses twice a week (Troupe), and the next morning the review is on the board and in the gazette
 * (Playbill). Rain puts an open-air play off to the next fine evening; a town that is rained off twice, or grows
 * great, wants a roofed playhouse (blueprints/playhouse.txt), and a town with the Grand Amphitheatre plays there
 * in fine weather (TownWonders). An actor missing (ill, away, dead) is stood in for by another of the players; with
 * nobody, the play is stopped and the town told.
 */
public final class Theatre {

    private Theatre() {}

    public static final String STRUCTURE = "theatre";
    /** [culture-wonders] The theatre under a roof (blueprints/playhouse.txt). */
    public static final String PLAYHOUSE = "playhouse";
    /** The town it takes: twenty folk, the Iron Age, and players enough. */
    static final int FROM_FOLK = 20;
    /** The play begins after supper, and not after this. */
    static final long START = 13000L, LATEST = 14000L;
    /** [culture-wonders] The company rehearses before supper on its rehearsal evenings. */
    static final long REHEARSE_FROM = 12300L, REHEARSE_TO = 12900L;
    /** How long the players are given to get to their marks. */
    static final long CALL = 600L;
    /** The bows and the cheering. */
    static final long BOWS = 160L;
    /** [culture-wonders] A seat's price. */
    public static final int TICKET = 1;

    /** The town's banner on the back wall, either side over the stage (in the drawing's terms: across, up, toward the back). */
    static final int[][] BANNERS = { { -2, 3, 4 }, { 2, 3, 4 } };
    /** The players' marks on the stage: centre, left, right, down-left, down-right, up-stage, the wings. */
    static final int[][] MARKS = { { 0, 1, 3 }, { -2, 1, 3 }, { 2, 1, 3 }, { -1, 1, 2 }, { 1, 1, 2 }, { 0, 1, 4 }, { -3, 1, 4 }, { 3, 1, 4 } };
    /** The benches, front row first: stairs with their backs to the street. */
    static final int[][] SEATS = { { -1, 0, -1 }, { 1, 0, -1 }, { -2, 0, -1 }, { 2, 0, -1 }, { -3, 0, -1 }, { 3, 0, -1 },
        { -1, 0, -3 }, { 1, 0, -3 }, { -2, 0, -3 }, { 2, 0, -3 }, { -3, 0, -3 }, { 3, 0, -3 } };

    // ------------------------------------------------------------------ [culture-wonders] the houses it plays in

    /** A house a play is put on in: the building, its marks, its seats, where the house stands, and whether it has a roof. */
    record Venue(Ledger.Building b, int[][] marks, int[][] seats, boolean roofed, String words) {
        BlockPos mark(int i) {
            int[] m = marks[Math.floorMod(i, marks.length)];
            return Culture.at(b, m[0], m[1], m[2]);
        }

        BlockPos seat(int i) {
            int[] s = seats[i];
            return Culture.at(b, s[0], s[1], s[2]);
        }

        /** A place standing, for the n-th of the house the benches did not hold. */
        BlockPos standing(int n) {
            return switch (b.structure()) {
                case PLAYHOUSE -> Culture.at(b, (n % 2 == 0 ? 1 : -1) * (1 + (n / 2) % 4), 0, n < 8 ? -2 : -4);
                case TownWonders.AMPHITHEATRE -> Culture.at(b, (n % 5) - 2, 0, -6 + n / 5);
                default -> Culture.at(b, (n % 7) - 3, 0, -5 - n / 7);
            };
        }
    }

    static final int[][] PLAYHOUSE_MARKS = { { 0, 1, 3 }, { -2, 1, 3 }, { 2, 1, 3 }, { -1, 1, 2 }, { 1, 1, 2 }, { 0, 1, 4 }, { -3, 1, 4 }, { 3, 1, 4 } };
    static final int[][] PLAYHOUSE_SEATS = { { -1, 0, -1 }, { 1, 0, -1 }, { -2, 0, -1 }, { 2, 0, -1 }, { -3, 0, -1 }, { 3, 0, -1 }, { -4, 0, -1 }, { 4, 0, -1 },
        { -1, 0, -3 }, { 1, 0, -3 }, { -2, 0, -3 }, { 2, 0, -3 }, { -3, 0, -3 }, { 3, 0, -3 }, { -4, 0, -3 }, { 4, 0, -3 } };
    static final int[][] ARENA_MARKS = { { 0, 1, 7 }, { -2, 1, 7 }, { 2, 1, 7 }, { -1, 1, 6 }, { 1, 1, 6 }, { 0, 1, 8 }, { -3, 1, 8 }, { 3, 1, 8 } };
    static final int[][] ARENA_SEATS;

    static {
        List<int[]> s = new ArrayList<>();
        for (int x = -2; x <= 2; x++) if (Math.abs(x) >= 2) s.add(new int[]{ x, 0, -7 });
        for (int z = 4; z >= -6; z--) {
            s.add(new int[]{ -3, 0, z });
            s.add(new int[]{ 3, 0, z });
        }
        for (int x : new int[]{ -3, 3, -4, 4 }) s.add(new int[]{ x, 0, -7 });
        for (int z = 4; z >= -6; z--) {
            s.add(new int[]{ -4, 1, z });
            s.add(new int[]{ 4, 1, z });
        }
        for (int x : new int[]{ -2, 2, -3, 3, -4, 4 }) s.add(new int[]{ x, 1, -8 });
        ARENA_SEATS = s.toArray(new int[0][]);
    }

    /**
     * Where tonight's play is put on: the Grand Amphitheatre in fine weather, then the playhouse (rain or shine),
     * then the open-air theatre in fine weather; null if the town has nowhere to play tonight.
     */
    @Nullable
    static Venue venue(ServerLevel level, UUID village, boolean raining) {
        Ledger.Building arena = TownWonders.standing(village, TownWonders.AMPHITHEATRE);
        if (arena != null && !raining) return new Venue(arena, ARENA_MARKS, ARENA_SEATS, false, "the Grand Amphitheatre");
        Ledger.Building house = Culture.building(village, PLAYHOUSE);
        if (house != null) return new Venue(house, PLAYHOUSE_MARKS, PLAYHOUSE_SEATS, true, "the playhouse");
        Ledger.Building theatre = Culture.building(village, STRUCTURE);
        if (theatre != null && !raining) return new Venue(theatre, MARKS, SEATS, false, "the theatre");
        return null;
    }

    /** Has the town a house to play in at all, rain or not? */
    static boolean anyHouse(UUID village) {
        return Culture.building(village, STRUCTURE) != null || Culture.building(village, PLAYHOUSE) != null
            || TownWonders.standing(village, TownWonders.AMPHITHEATRE) != null;
    }

    // ------------------------------------------------------------------ wanting one

    /** A player at heart: a reader (a story), a musician (a performer), a whittler (an artist's hands). */
    static boolean player(VillageFolkEntity f) {
        if (f.isBaby() || !f.persona().rolled()) return false;
        Persona.Hobby h = f.persona().hobby();
        return h == Persona.Hobby.READING || h == Persona.Hobby.MUSIC || h == Persona.Hobby.WHITTLING;
    }

    static int players(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && player(f)) n++;
        return n;
    }

    /** Does the town want a theatre: twenty folk, the Iron Age or later, and two players at least? */
    public static boolean wanted(UUID village, int folk) {
        return folk >= FROM_FOLK && Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal() && players(village) >= 2;
    }

    /** Why the town wants it, in a line (Villages.whyBuild). */
    public static String why(UUID village) {
        return "a theatre, an open stage with benches before it, so the town's " + players(village)
            + " players can put on its own stories on the evening of the day of rest";
    }

    /**
     * [culture-wonders] Does the town want the playhouse: its theatre standing and none under a roof yet, and either
     * the Diamond Age, or a town of forty, or its plays put off for rain twice?
     */
    public static boolean playhouseWanted(UUID village) {
        if (Culture.building(village, STRUCTURE) == null || Culture.building(village, PLAYHOUSE) != null
            || Villages.hasBuilt(village, PLAYHOUSE)) return false;
        Villages.Age age = Villages.ageOf(village);
        if (age.ordinal() >= Villages.Age.DIAMOND.ordinal()) return true;
        return age.ordinal() >= Villages.Age.IRON.ordinal() && (Villages.headcount(village) >= 40 || rainedOff(village) >= 2);
    }

    /** Why the playhouse (Villages.whyBuild). */
    public static String playhouseWhy(UUID village) {
        int off = rainedOff(village);
        return "a playhouse, the theatre under a roof, so the players can play in any weather"
            + (off > 0 ? " (the rain has put off " + off + (off == 1 ? " play" : " plays") + " at the open-air theatre)" : "");
    }

    static int rainedOff(UUID village) {
        return (int) Culture.num(String.valueOf(Ledger.note(village, "theatre.rainedoff")), 0);
    }

    @Nullable
    static Ledger.Building of(UUID village) {
        return Culture.building(village, STRUCTURE);
    }

    // ------------------------------------------------------------------ the story

    /** The story tonight: the weightiest line of the chronicle not played yet, the latest of those; the founding if none. */
    static Chronicle.Entry story(UUID village, long day) {
        Set<String> played = new HashSet<>();
        for (String[] r : Culture.rows(village, "culture.plays")) if (r.length > 4) played.add(r[4]);
        Chronicle.Entry best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Chronicle.Entry e : Chronicle.of(village)) {
            Playwright.Kind k = Playwright.kind(e.text());
            if (k == null) continue;
            int w = FoundingDay.weight(e.text());
            if (k == Playwright.Kind.TALE && w < 6) continue;
            double score = w + Playwright.extra(k) - Math.max(0, day - e.day()) / 14.0;
            if (played.contains(key(e))) score -= 100;
            if (score > bestScore) { bestScore = score; best = e; }
        }
        if (best == null) {
            long f = Math.max(0, FoundingDay.founded(village));
            best = new Chronicle.Entry(f, Villages.name(village) + " was founded");
        }
        return best;
    }

    static String key(Chronicle.Entry e) {
        return e.day() + ":" + Integer.toHexString(e.text().hashCode());
    }

    /**
     * [culture-wonders] A great event of yesterday or today not yet played (a battle, a siege, a wedding): the play the
     * company puts on the evening after it, whatever the day; null if none.
     */
    @Nullable
    static Chronicle.Entry event(UUID village, long day) {
        Set<String> played = new HashSet<>();
        for (String[] r : Culture.rows(village, "culture.plays")) if (r.length > 4) played.add(r[4]);
        Chronicle.Entry found = null;
        for (Chronicle.Entry e : Chronicle.of(village)) {
            if (e.day() < day - 1 || e.day() > day || played.contains(key(e))) continue;
            Playwright.Kind k = Playwright.kind(e.text());
            if (k == Playwright.Kind.BATTLE || k == Playwright.Kind.SIEGE || k == Playwright.Kind.WEDDING) found = e;
        }
        return found;
    }

    /** A play written out of a line of the town's chronicle. */
    static Playwright.Play write(UUID village, Chronicle.Entry e) {
        return Playwright.write(village, e);
    }

    /** [culture-wonders] Tonight's play: a player's request first, a great event's next, else the chronicle's weightiest. */
    static Playwright.Play choose(UUID village, long day) {
        Playwright.Play asked = Playbill.requested(village);
        if (asked != null) return asked;
        Chronicle.Entry ev = event(village, day);
        return write(village, ev != null ? ev : story(village, day));
    }

    // ------------------------------------------------------------------ the evening

    enum Phase { CALL, PLAY, BOWS, DONE }

    /** Tonight's play in one town: its players, who came, and how far on it is. */
    static final class Show {
        final long day;
        final Playwright.Play play;
        final Venue venue;
        /** A rehearsal: the cast alone, the first act, no tickets and no review. */
        final boolean rehearsal;
        final String occasion;
        final List<UUID> cast = new ArrayList<>();
        final List<String> castNames = new ArrayList<>();
        /** Who stood in for whom (by place in the cast). */
        final Map<Integer, UUID> under = new HashMap<>();
        final List<String> notes = new ArrayList<>();
        final Map<UUID, Integer> seats = new HashMap<>();
        final Set<UUID> watched = new HashSet<>();
        final Set<UUID> paid = new HashSet<>();
        final Set<UUID> players = new LinkedHashSet<>();
        final List<String> said = new ArrayList<>();
        /** Where each of the cast stands now (by place in the cast), and who lies dead on the boards. */
        final Map<Integer, Integer> markOf = new HashMap<>();
        final Set<Integer> fallen = new HashSet<>();
        final Map<Troupe.Piece, Integer> lent = new HashMap<>();
        int fightA = -1, fightB = -1;
        long fightUntil;
        int takings;
        Phase phase = Phase.CALL;
        long phaseAt, nextAt;
        int next;
        boolean closed, stopped, dressed;
        @Nullable Playbill.Review review;

        Show(long day, Playwright.Play play, Venue venue, boolean rehearsal, String occasion) {
            this.day = day;
            this.play = play;
            this.venue = venue;
            this.rehearsal = rehearsal;
            this.occasion = occasion;
        }

        /** Who says a part: the teller is the first player; with fewer players than parts, the parts are doubled. */
        int actorFor(int part) {
            return Math.min(part % Math.max(1, cast.size()), cast.size() - 1);
        }

        /** The beats of it: the whole play, or a rehearsal's first act. */
        int length() {
            if (!rehearsal) return play.beats().size();
            int acts = 0, says = 0;
            for (int i = 0; i < play.beats().size(); i++) {
                Playwright.Beat b = play.beats().get(i);
                if (b.what() == Playwright.Do.SAY && b.part() == 0 && b.text().startsWith("Act ") && ++acts == 2) return i;
                if (b.what() == Playwright.Do.SAY && ++says >= 7) return i + 1;
            }
            return play.beats().size();
        }
    }

    private static final Map<UUID, Show> SHOWS = new ConcurrentHashMap<>();
    /** The day each town last had (or let go) its play; kept with the world too (theatre.played), across a restart. */
    private static final Map<UUID, Long> PLAYED = new ConcurrentHashMap<>();
    /** The day each town's company last rehearsed. */
    private static final Map<UUID, Long> REHEARSED_ON = new ConcurrentHashMap<>();

    static void resetForTests() {
        SHOWS.clear();
        PLAYED.clear();
        REHEARSED_ON.clear();
    }

    static long played(UUID village) {
        Long p = PLAYED.get(village);
        if (p != null) return p;
        long n = Culture.num(String.valueOf(Ledger.note(village, "theatre.played")), -1);
        PLAYED.put(village, n);
        return n;
    }

    static void played(UUID village, long day) {
        PLAYED.put(village, day);
        Ledger.note(village, "theatre.played", Long.toString(day));
    }

    /** Is a play on in this town just now (from the call to the bows)? The band waits for it (Music). */
    static boolean on(UUID village) {
        Show s = SHOWS.get(village);
        return s != null && s.phase != Phase.DONE && !s.rehearsal;
    }

    /** Is this folk one of the cast of a play or a rehearsal under way (its costume stays on)? */
    static boolean inCast(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Show s = id == null ? null : SHOWS.get(id);
        return s != null && s.phase != Phase.DONE && (s.cast.contains(f.getUUID()) || s.under.containsValue(f.getUUID()));
    }

    /**
     * [culture-wonders] What tonight is, for a play: the day of rest, a festival, the evening after a great event, or
     * a play put off by the rain; null if it is no night for one.
     */
    @Nullable
    static String occasion(UUID village, long day) {
        if (RestDay.today(village, day)) return "the day of rest";
        long put = Culture.num(String.valueOf(Ledger.note(village, "theatre.postponed")), -1);
        if (put >= 0 && put < day && day - put <= 7) return "the play the rain put off";
        Chronicle.Entry ev = event(village, day);
        if (ev != null) return "the evening after " + Culture.capital(Playwright.write(village, ev).title());
        if (Arms.festival(village, day)) return "the festival";
        return null;
    }

    /** Is tonight's play on, or still to come (a theatre standing, a night for one, before the latest it begins)? The band waits for it. */
    static boolean onOrToCome(ServerLevel level, UUID village) {
        if (on(village)) return true;
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        return anyHouse(village) && occasion(village, day) != null && t < LATEST && played(village) != day;
    }

    /** The players tonight: two to four of those who love a story, a tune or a carving, the readers first and the famous. */
    static List<VillageFolkEntity> cast(ServerLevel level, UUID village, long day, int most) {
        List<VillageFolkEntity> all = new ArrayList<>();
        Map<VillageFolkEntity, Integer> score = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !player(f) || f.isShowcase() || !Culture.free(f, level)) continue;
            int s = switch (f.persona().hobby()) {
                case READING -> 30;
                case MUSIC -> 25;
                default -> 20;
            };
            if (f.life().has(Social.Trait.SOCIABLE)) s += 15;
            if (f.life().has(Social.Trait.CHEERFUL)) s += 10;
            if (f.life().has(Social.Trait.SHY)) s -= 25;
            s += Math.min(20, Troupe.fameOf(village, f.getUUID()));           // [culture-wonders] a player of name, first
            s += Math.floorMod((int) (day * 31L) + f.getUUID().hashCode(), 12);
            score.put(f, s);
            all.add(f);
        }
        all.sort(Comparator.comparingInt((VillageFolkEntity f) -> -score.get(f)).thenComparing(f -> f.getUUID()));
        return all.subList(0, Math.min(Math.max(2, Math.min(4, most)), all.size()));
    }

    /** Tonight's play cast and written; null if there are not two players to put it on, or nowhere to put it on. */
    @Nullable
    static Show begin(ServerLevel level, Villages.Village v, long day) {
        return begin(level, v, day, false, "the day of rest");
    }

    @Nullable
    static Show begin(ServerLevel level, Villages.Village v, long day, boolean rehearsal, String occasion) {
        Venue venue = venue(level, v.id(), level.isRaining());
        if (venue == null) return null;
        Playwright.Play play = rehearsal ? next(v.id(), day) : choose(v.id(), day);
        List<VillageFolkEntity> cast = cast(level, v.id(), day, play.parts().size());
        if (cast.size() < 2) return null;
        Show s = new Show(day, play, venue, rehearsal, occasion);
        for (VillageFolkEntity f : cast) {
            s.cast.add(f.getUUID());
            s.castNames.add(f.displayNameCap());
        }
        if (!rehearsal) {
            String made = Troupe.stock(level, v, play);                       // the wardrobe made up for it out of the stores
            if (!made.isEmpty()) s.notes.add("the wardrobe: " + made);
        }
        s.phaseAt = level.getGameTime();
        SHOWS.put(v.id(), s);
        return s;
    }

    /** The play the company is working on: the one it would put on next (rehearsals and the playbill). */
    static Playwright.Play next(UUID village, long day) {
        long rest = day;
        for (long d = day; d < day + 8; d++) if (RestDay.today(village, d)) { rest = d; break; }
        return choose(village, rest);
    }

    /** The town's look at the theatre (Culture, each second): tonight's play begun, and moved on. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L, now = level.getGameTime();
        Show s = SHOWS.get(id);
        if (s != null && (s.day != day || s.phase == Phase.DONE && now - s.phaseAt > 200)) {
            if (s.phase != Phase.DONE) stop(level, v, s, "the night was over");
            undressAll(level, s);
            SHOWS.remove(id, s);
            s = null;
        }
        if (s == null) {
            if (!anyHouse(id) || Raids.underAlarm(id)) return;
            if (t >= REHEARSE_FROM && t < REHEARSE_TO && Troupe.rehearsalDay(id, day) && REHEARSED_ON.getOrDefault(id, -1L) != day
                    && occasion(id, day) == null) {
                REHEARSED_ON.put(id, day);
                Venue venue = venue(level, id, level.isRaining());
                if (venue != null && level.isLoaded(venue.b().anchor())) s = begin(level, v, day, true, "a rehearsal");
                if (s == null) return;
            } else {
                if (t < START || t >= LATEST || played(id) == day) return;
                String why = occasion(id, day);
                if (why == null) return;
                Venue venue = venue(level, id, level.isRaining());
                if (venue == null) {
                    if (level.isRaining() && Culture.building(id, STRUCTURE) != null) rainedOff(level, id, day);
                    return;
                }
                if (!level.isLoaded(venue.b().anchor())) return;
                played(id, day);
                s = begin(level, v, day, false, why);
                if (s == null) return;
                Ledger.forget(id, "theatre.postponed");
            }
        }
        step(level, v, s, now, false);
    }

    /** The rain puts the open-air play off: to the next fine evening, the town told once. */
    static void rainedOff(ServerLevel level, UUID id, long day) {
        if (played(id) == day) return;
        played(id, day);
        Ledger.note(id, "theatre.postponed", Long.toString(day));
        Ledger.note(id, "theatre.rainedoff", Integer.toString(rainedOff(id) + 1));
        Villages.tell(id, day, "the play was put off for the rain, to the next fine evening");
    }

    /** The cast member who plays this part tonight: the cast's own, else an understudy found now, else another of the cast. */
    @Nullable
    static VillageFolkEntity actor(ServerLevel level, Show s, int part) {
        int idx = s.actorFor(part);
        VillageFolkEntity f = alive(level, s.cast.get(idx));
        if (f != null) return f;
        UUID u = s.under.get(idx);
        if (u != null && alive(level, u) != null) return alive(level, u);
        // An understudy: another of the town's players, free and near the house.
        Villages.Village v = Villages.get(s.venue.b() == null ? null : ownerOf(level, s));
        if (v != null) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity g) || !player(g) || s.cast.contains(g.getUUID()) || s.under.containsValue(g.getUUID())) continue;
                if (!Culture.free(g, level) || g.distanceToSqr(s.venue.b().anchor().getX(), g.getY(), s.venue.b().anchor().getZ()) > 64 * 64) continue;
                s.under.put(idx, g.getUUID());
                s.notes.add(g.displayNameCap() + " stood in for " + s.castNames.get(idx));
                s.seats.remove(g.getUUID());
                if (!s.rehearsal) Troupe.dress(g, v.id(), s.play.parts().get(Math.min(idx, s.play.parts().size() - 1)).kit(), s.lent);
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "I know the part! I'll do it!", "Give me the book — the show goes on!"));
                return g;
            }
        }
        // Nobody to stand in: another of the cast doubles the part.
        for (int i = 0; i < s.cast.size(); i++) {
            VillageFolkEntity g = alive(level, s.cast.get(i));
            if (g != null) {
                if (!s.notes.contains(s.castNames.get(i) + " doubled the part of " + s.castNames.get(idx))) {
                    s.notes.add(s.castNames.get(i) + " doubled the part of " + s.castNames.get(idx));
                }
                return g;
            }
        }
        return null;
    }

    @Nullable
    private static UUID ownerOf(ServerLevel level, Show s) {
        for (UUID u : s.cast) {
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.ownerId() != null) return f.ownerId();
        }
        for (Map.Entry<UUID, Show> e : SHOWS.entrySet()) if (e.getValue() == s) return e.getKey();
        return null;
    }

    @Nullable
    static VillageFolkEntity alive(ServerLevel level, UUID u) {
        return level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** The play moved on: the players to their marks, a beat at a time, the bows. {@code force}: no waiting (the tests). */
    static void step(ServerLevel level, Villages.Village v, Show s, long now, boolean force) {
        Venue venue = s.venue;
        switch (s.phase) {
            case CALL -> {
                if (!s.dressed && !s.rehearsal) {
                    s.dressed = true;
                    for (int i = 0; i < s.cast.size(); i++) {
                        VillageFolkEntity f = alive(level, s.cast.get(i));
                        if (f != null) Troupe.dress(f, v.id(), s.play.parts().get(Math.min(i, s.play.parts().size() - 1)).kit(), s.lent);
                    }
                }
                int there = 0;
                for (int i = 0; i < s.cast.size(); i++) {
                    if (alive(level, s.cast.get(i)) instanceof VillageFolkEntity f && f.blockPosition().distSqr(venue.mark(i)) <= 2.5 * 2.5) there++;
                }
                boolean ready = there >= s.cast.size() || now - s.phaseAt > CALL && there >= 2;
                if (force || ready) {
                    s.phase = Phase.PLAY;
                    s.phaseAt = now;
                    s.nextAt = now + 40;
                    level.playSound(null, venue.mark(0), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 1.5F, 1.4F);
                    if (s.rehearsal && alive(level, s.cast.get(0)) instanceof VillageFolkEntity lead) {
                        FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), "From the top, everybody!", "Places! And — from the top."));
                    }
                } else if (now - s.phaseAt > CALL * 2) {
                    s.phase = Phase.DONE;                                   // the players never came: no play tonight
                    s.phaseAt = now;
                    undressAll(level, s);
                }
            }
            case PLAY -> {
                int end = s.length();
                while (s.next < end && (force || now >= s.nextAt)) {
                    Playwright.Beat b = s.play.beats().get(s.next++);
                    VillageFolkEntity f = actor(level, s, b.part());
                    if (f == null) {
                        stop(level, v, s, "nobody was left to play " + s.play.parts().get(b.part()).name());
                        return;
                    }
                    s.nextAt = now + beat(level, s, b, f, now);
                    if (!force) break;
                }
                if (s.next >= end && (force || now >= s.nextAt)) {
                    s.phase = Phase.BOWS;
                    s.phaseAt = now;
                    s.fallen.clear();
                    s.fightA = s.fightB = -1;
                    close(level, v, s);
                }
            }
            case BOWS -> {
                if (force || now - s.phaseAt > BOWS) {
                    s.phase = Phase.DONE;
                    s.phaseAt = now;
                    undressAll(level, s);
                }
            }
            case DONE -> { }
        }
    }

    /** One beat played: what it does, and how long until the next. */
    private static long beat(ServerLevel level, Show s, Playwright.Beat b, VillageFolkEntity f, long now) {
        int idx = s.actorFor(b.part());
        String partName = s.play.parts().get(Math.min(b.part(), s.play.parts().size() - 1)).name();
        switch (b.what()) {
            case SAY -> {
                FolkTalk.speak(f, b.text());
                f.swing(InteractionHand.MAIN_HAND);
                s.said.add(partName + ": " + b.text());
                if (!s.rehearsal) react(level, s, b.react());
                return Math.max(70, 40 + b.text().length() * 2L);
            }
            case MOVE -> {
                s.markOf.put(idx, b.mark());
                return 50;
            }
            case FIGHT -> {
                int other = s.actorFor(b.other());
                if (other == idx) return 10;                               // one player doubling both: no fight to show
                s.fightA = idx;
                s.fightB = other;
                s.fightUntil = now + 100;
                s.markOf.put(idx, 3);
                s.markOf.put(other, 4);
                level.playSound(null, s.venue.mark(0), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.NEUTRAL, 1.0F, 1.0F);
                if (!s.rehearsal) react(level, s, '!');
                return 110;
            }
            case FALL -> {
                s.fallen.add(idx);
                s.fightA = s.fightB = -1;
                f.setPose(Pose.SITTING);
                level.sendParticles(ParticleTypes.CRIT, f.getX(), f.getY() + 1.0, f.getZ(), 6, 0.2, 0.3, 0.2, 0.0);
                level.playSound(null, f.blockPosition(), SoundEvents.PLAYER_HURT, SoundSource.NEUTRAL, 0.6F, 1.1F);
                if (!s.rehearsal) react(level, s, '~');
                return 50;
            }
            case RISE -> {
                s.fallen.remove(idx);
                if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
                return 30;
            }
        }
        return 40;
    }

    /** How the house takes a line: a cheer, a murmur, a hush, a boo for the enemy, tears. */
    private static void react(ServerLevel level, Show s, char how) {
        RandomSource r = level.getRandom();
        int voices = 0;
        for (UUID u : s.watched) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            switch (how) {
                case '!' -> {
                    if (voices < 2 && r.nextInt(3) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Hooray!", "Bravo!", "Ha! I remember that!", "Well said!"));
                        voices++;
                    }
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 1.6, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
                }
                case '?' -> {
                    if (voices < 1 && r.nextInt(4) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Ooh.", "Ha!", "That's right, it was!", "Shh!"));
                        voices++;
                    }
                }
                case 'b' -> {
                    if (voices < 2 && r.nextInt(3) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Boo!", "Hiss!", "Get off!", "Boo! Booo!"));
                        voices++;
                    }
                    level.sendParticles(ParticleTypes.ANGRY_VILLAGER, f.getX(), f.getY() + 1.8, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
                }
                case '~' -> {
                    f.setXRot(20.0F);
                    if (s.play.tragedy() && voices < 1 && r.nextInt(5) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "*sniff*", "Oh, no...", "I can't watch this bit.", "Poor soul."));
                        voices++;
                    }
                }
                default -> { }
            }
        }
    }

    /** Every cast member out of its costume, up off the boards. */
    static void undressAll(ServerLevel level, Show s) {
        List<UUID> all = new ArrayList<>(s.cast);
        all.addAll(s.under.values());
        for (UUID u : all) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            Troupe.undress(f);
            if (f.getPose() == Pose.SITTING && !Culture.seated(f)) f.setPose(Pose.STANDING);
        }
    }

    /** The play stopped half way: the town told, the players undressed, no review. */
    static void stop(ServerLevel level, Villages.Village v, Show s, String why) {
        if (s.closed || s.phase == Phase.DONE) return;
        s.stopped = true;
        s.closed = true;
        s.phase = Phase.DONE;
        s.phaseAt = level.getGameTime();
        undressAll(level, s);
        if (s.rehearsal) return;
        long day = level.getDayTime() / 24000L;
        // The seats paid for are paid back.
        for (UUID u : s.paid) if (level.getEntity(u) instanceof VillageFolkEntity f) f.earn(TICKET);
        Villages.tell(v.id(), day, "the play “" + s.play.title() + "” was stopped half way: " + why);
    }

    /** [culture-wonders] One of the company died: a play under way goes on with another player in its part. */
    static void actorLost(ServerLevel level, UUID village, VillageFolkEntity f) {
        Show s = SHOWS.get(village);
        if (s == null || s.phase == Phase.DONE) return;
        int idx = s.cast.indexOf(f.getUUID());
        if (idx >= 0 && s.under.get(idx) == null) s.notes.add(f.displayNameCap() + " was lost to the company that night");
    }

    /** The bows: the house cheers; into the town's history, the players' and the audience's memories and the books. */
    private static void close(ServerLevel level, Villages.Village v, Show s) {
        if (s.closed) return;
        s.closed = true;
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        RandomSource r = level.getRandom();
        List<String> as = new ArrayList<>();
        List<VillageFolkEntity> actors = new ArrayList<>();
        for (int i = 0; i < s.cast.size(); i++) {
            int part = Math.min(i, s.play.parts().size() - 1);
            VillageFolkEntity f = alive(level, s.cast.get(i));
            if (f == null && s.under.get(i) != null) f = alive(level, s.under.get(i));
            if (f == null) continue;
            if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
            f.swing(InteractionHand.MAIN_HAND);
            actors.add(f);
            if (s.rehearsal) {
                f.persona().remember(day, "I rehearsed " + s.play.parts().get(part).name() + " for “" + s.play.title() + "”", 1);
                continue;
            }
            f.persona().remember(day, "I played " + s.play.parts().get(part).name() + " in “" + s.play.title() + "” at " + s.venue.words(), 4);
            f.persona().enjoyedHobby(day);
            as.add(f.displayNameCap() + " as " + s.play.parts().get(part).name());
            if (i == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Thank you, thank you all!", "That's our play. Thank you for coming!"));
        }
        String key = s.play.about().isEmpty() ? key(new Chronicle.Entry(s.play.storyDay(), s.play.story())) : "hero:" + s.play.about();
        if (s.rehearsal) {
            Troupe.rehearsedOnce(id, key);
            if (!actors.isEmpty()) FolkTalk.speak(actors.get(0), FolkTalk.pick(r, "Good! Same again next time — and louder.",
                "Better. Much better. Off you go."));
            return;
        }
        // The players who came to see it (and bought a seat, or stood at the back).
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(s.venue.mark(0)) <= 28 * 28) s.players.add(p.getUUID());
        }
        for (UUID u : s.watched) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            f.persona().remember(day, "I saw “" + s.play.title() + "” at " + s.venue.words(), 2);
            f.swing(InteractionHand.OFF_HAND);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.0, f.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
            for (VillageFolkEntity p : actors) f.life().feel(p.getUUID(), p.displayNameCap(), 1);
            Stagecraft.applause(f, day);
        }
        // The folk who were played in it, watching: how they take it.
        portrayed(level, s, day, r);
        level.playSound(null, s.venue.mark(0), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.8F, 1.2F);
        // The takings: half into the treasury, half shared among the players.
        int share = actors.isEmpty() ? 0 : s.takings / 2 / actors.size();
        for (VillageFolkEntity f : actors) f.earn(share);
        int house = s.takings - share * actors.size();
        if (house > 0) Ledger.addCoins(id, house);
        s.review = Playbill.review(level, v, s, actors);
        for (VillageFolkEntity f : actors) Troupe.applaud(id, f.getUUID(), f.displayNameCap(), 1 + s.review.stars());
        String[] lost = Troupe.lost(id);
        String memory = lost != null ? ", in memory of " + lost[0] : "";
        if (lost != null) Troupe.lostHonoured(id);
        Villages.tell(id, day, "the players put on “" + s.play.title() + "” at " + s.venue.words() + " (" + String.join(", ", as) + "), and "
            + s.watched.size() + " came to watch" + memory);
        List<String[]> rows = Culture.rows(id, "culture.plays");
        rows.add(new String[]{ Long.toString(day), s.play.title(), String.join(", ", as), Integer.toString(s.watched.size()), key,
            s.play.genre(), Integer.toString(s.review.stars()), s.review.text(), s.venue.words(), Integer.toString(s.takings) });
        while (rows.size() > 8) rows.remove(0);
        Culture.rows(id, "culture.plays", rows);
        Playbill.played(level, v, s);
    }

    /** The folk played in it, alive and watching: proud, moved, or put out (a defeat, a death of their own). */
    private static void portrayed(ServerLevel level, Show s, long day, RandomSource r) {
        for (Playwright.Part part : s.play.parts()) {
            if (part.real() == null || !(level.getEntity(part.real()) instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            boolean watching = s.watched.contains(f.getUUID());
            boolean sore = s.play.kind() == Playwright.Kind.BATTLE && s.play.tragedy();
            String words = sore ? FolkTalk.pick(r, "I lived that. I don't need to see it again.", "It wasn't quite like that... but it wasn't far off.")
                : s.play.kind() == Playwright.Kind.WEDDING ? FolkTalk.pick(r, "That's us! Did I really say that?", "Oh, my cheeks are burning!")
                : FolkTalk.pick(r, "That's me up there!", "I never said that! ...Well, maybe I did.", "They got my walk just right!");
            if (watching) FolkTalk.speak(f, words);
            f.persona().remember(day, "the players played me in “" + s.play.title() + "”" + (watching ? ", and I watched" : ""), 5);
            Stagecraft.portrayed(f, day, !sore);
        }
    }

    // ------------------------------------------------------------------ the folk's part

    static BlockPos mark(Ledger.Building b, int i) {
        int[] m = MARKS[Math.min(i, MARKS.length - 1)];
        return Culture.at(b, m[0], m[1], m[2]);
    }

    /** The way to face from here toward there. */
    static float yawToward(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** A folk's part in tonight's play: a player to its mark, the rest to a bench (or behind them), facing the stage. */
    static boolean hold(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        Troupe.tidy(f);
        Show s = SHOWS.get(v.id());
        if (s == null || s.phase == Phase.DONE) return false;
        Venue venue = s.venue;
        UUID me = f.getUUID();
        BlockPos front = venue.standing(3);
        int part = s.cast.indexOf(me);
        if (part < 0) for (Map.Entry<Integer, UUID> e : s.under.entrySet()) if (e.getValue().equals(me)) part = e.getKey();
        if (part >= 0) {
            int p = Math.min(part, s.play.parts().size() - 1);
            Culture.mark(f, Culture.Role.ACTOR, (s.rehearsal ? "rehearsing " : "playing ") + s.play.parts().get(p).name() + " in “"
                + s.play.title() + "” at " + venue.words());
            if (s.fallen.contains(part)) {
                f.getNavigation().stop();
                if (f.getPose() != Pose.SITTING) f.setPose(Pose.SITTING);
                return true;
            }
            if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
            BlockPos at = venue.mark(s.markOf.getOrDefault(part, part));
            boolean there = Culture.arrive(f, at, 0.8, 1.0);
            long now = level.getGameTime();
            if ((part == s.fightA || part == s.fightB) && now < s.fightUntil) {
                int other = part == s.fightA ? s.fightB : s.fightA;
                VillageFolkEntity o = actor(level, s, other);
                if (o != null) {
                    f.getLookControl().setLookAt(o, 30.0F, 30.0F);
                    if ((f.tickCount + part * 5) % 10 < 4) {
                        f.swing(InteractionHand.MAIN_HAND);
                        if (part == s.fightA) level.playSound(null, f.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.NEUTRAL, 0.9F, 1.3F);
                    }
                }
            } else if (there) {
                f.getLookControl().setLookAt(front.getX() + 0.5, front.getY() + 1.0, front.getZ() + 0.5);
            }
            return true;
        }
        if (s.rehearsal) return false;                                    // nobody watches a rehearsal
        if (Assemblies.attending(f) || f.blockPosition().distSqr(venue.b().anchor()) > 80.0 * 80.0) return false;
        Culture.Held h = Culture.mark(f, Culture.Role.AUDIENCE, "watching “" + s.play.title() + "” at " + venue.words());
        Integer seat = s.seats.get(me);
        if (seat == null) {
            seat = freeSeat(level, venue, s);
            s.seats.put(me, seat);
            // [culture-wonders] A seat for a coin, from those with coin to spare; the rest stand at the back for nothing.
            if (f.purse() >= TICKET + 3 && f.spend(TICKET)) {
                s.paid.add(me);
                s.takings += TICKET;
            }
        }
        BlockPos stage = venue.mark(0);
        if (seat >= 0) {
            BlockPos p = venue.seat(seat);
            float yaw = yawToward(p, stage);
            if (!h.seated && Culture.arrive(f, p, 1.2, 0.9) && Seats.seat(level, p, level.getBlockState(p))) Culture.sit(f, h, p, yaw);
        } else {
            BlockPos p = venue.standing(-seat - 1);
            if (Culture.arrive(f, p, 1.0, 0.9)) f.getLookControl().setLookAt(stage.getX() + 0.5, stage.getY() + 1.5, stage.getZ() + 0.5);
        }
        if (h.seated) f.getLookControl().setLookAt(stage.getX() + 0.5, stage.getY() + 1.5, stage.getZ() + 0.5);
        if (s.phase == Phase.PLAY || s.phase == Phase.BOWS) s.watched.add(me);
        return true;
    }

    /** A bench for one more of the audience (its index), or a place standing behind them (counted down from -1). */
    private static int freeSeat(ServerLevel level, Venue venue, Show s) {
        Set<Integer> taken = new HashSet<>(s.seats.values());
        for (int i = 0; i < venue.seats().length; i++) {
            if (taken.contains(i)) continue;
            if (level.getBlockState(venue.seat(i)).getBlock() instanceof StairBlock) return i;
        }
        int standing = 0;
        for (int i : taken) if (i < 0) standing++;
        return -1 - standing;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: tonight's play begun now, whatever the day or the hour (the players cast, the play written). Its title, or null. */
    @Nullable
    public static String beginForTests(ServerLevel level, Villages.Village v) {
        Show old = SHOWS.remove(v.id());
        if (old != null) undressAll(level, old);
        Show s = begin(level, v, level.getDayTime() / 24000L);
        return s == null ? null : s.play.title();
    }

    /** Tests: a rehearsal begun now. Its title, or null. */
    @Nullable
    public static String rehearseForTests(ServerLevel level, Villages.Village v) {
        Show old = SHOWS.remove(v.id());
        if (old != null) undressAll(level, old);
        Show s = begin(level, v, level.getDayTime() / 24000L, true, "a rehearsal");
        return s == null ? null : s.play.title();
    }

    /** Tests: the play under way moved on now, as the town's look moves it (force: the whole of it at once). */
    public static void stepForTests(ServerLevel level, Villages.Village v, boolean force) {
        Show s = SHOWS.get(v.id());
        if (s != null) step(level, v, s, level.getGameTime(), force);
    }

    /** Tests: the play under way moved on by so many beats, each played as the town would (no waiting). */
    public static void beatsForTests(ServerLevel level, Villages.Village v, int n) {
        Show s = SHOWS.get(v.id());
        if (s == null || s.phase != Phase.PLAY) return;
        for (int i = 0; i < n && s.phase == Phase.PLAY; i++) {
            s.nextAt = 0;
            step(level, v, s, level.getGameTime(), false);
        }
    }

    /** Tests: the town's look at the theatre now (the evening's play begun if it is due, or put off). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    /** Tests: the players cast tonight, by name. */
    public static List<String> castForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? List.of() : List.copyOf(s.castNames);
    }

    /** Tests: every line said so far, "part: words", in order. */
    public static List<String> saidForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? List.of() : List.copyOf(s.said);
    }

    /** Tests: the play's lines, in order, as written. */
    public static List<String> scriptForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? new ArrayList<>() : new ArrayList<>(s.play.lines());
    }

    /** Tests: the play's phase ("CALL", "PLAY", "BOWS", "DONE") and how many watched, or null with none. */
    @Nullable
    public static String phaseForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? null : s.phase.name() + " watched=" + s.watched.size();
    }

    /** Tests: the show under way, in words: "bill|venue|occasion|takings|paid|notes|fallen|review". */
    public static String showForTests(UUID village) {
        Show s = SHOWS.get(village);
        if (s == null) return "";
        return s.play.bill() + "|" + s.venue.words() + "|" + s.occasion + "|" + s.takings + "|" + s.paid.size() + "|"
            + String.join("; ", s.notes) + "|" + s.fallen + "|" + (s.review == null ? "" : s.review.stars() + " " + s.review.text())
            + (s.stopped ? "|stopped" : "");
    }

    /** Tests: a play written out of this line of the chronicle: its title, then its lines. */
    public static List<String> writeForTests(UUID village, long day, String line) {
        Playwright.Play p = write(village, new Chronicle.Entry(day, line));
        List<String> out = new ArrayList<>();
        out.add(p.title());
        out.addAll(p.lines());
        return out;
    }

    /** Tests: where tonight's play would be put on, rain or not ("the theatre", "the playhouse"...), or "". */
    public static String venueForTests(ServerLevel level, UUID village, boolean raining) {
        Venue v = venue(level, village, raining);
        return v == null ? "" : v.words();
    }

    /** Tests: tonight's occasion for a play, or null. */
    @Nullable
    public static String occasionForTests(UUID village, long day) {
        return occasion(village, day);
    }

    // ------------------------------------------------------------------ the stage

    /**
     * /village culture stage: a theatre set out where the operator stands, the town's banners on its back wall,
     * three players on the stage in the middle of a play out of the town's chronicle and an audience on the
     * benches; says where to look from. [culture-wonders] "battle": the town's latest battle (or a battle of the
     * pictures' own) at its height, the players in their costumes and the swords out.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        return stage(level, v, at, "");
    }

    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at, String what) {
        int x = at.getX(), y = at.getY(), z = at.getZ();
        com.jrpetty.mcassistant.Showcase.stage(level, x - 7, x + 7, z - 9, z + 14, y);
        Direction back = Direction.NORTH;
        BuildGoal.stamp(level, STRUCTURE, at, back, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.Building b = new Ledger.Building(STRUCTURE, at.immutable(), back);
        Heraldry.choose(level, v);
        Heraldry.Design d = Heraldry.design(v.id());
        if (d == null) d = Heraldry.draw(v.id());
        for (int[] c : BANNERS) Decor.hangBanner(level, Culture.at(b, c[0], c[1], c[2]), back.getOpposite(), Heraldry.item(level, v.id(), d));
        boolean battle = "battle".equals(what);
        Playwright.Play play;
        if (battle) {
            Chronicle.Entry real = null;
            for (Chronicle.Entry e : Chronicle.of(v.id())) if (Playwright.kind(e.text()) == Playwright.Kind.BATTLE) real = e;
            play = write(v.id(), real != null ? real : new Chronicle.Entry(level.getDayTime() / 24000L,
                "The Battle of Ashby Field: we held the field against Brook (2 of ours down, 5 of theirs)"));
        } else {
            play = write(v.id(), story(v.id(), level.getDayTime() / 24000L));
        }
        String[] names = { "Wren", "Tom", "Pip", "Ada", "Nell", "Kit", "Bram", "Sal", "Ivy", "Hal", "Moss", "Fen" };
        List<VillageFolkEntity> players = new ArrayList<>();
        float toAudience = back.getOpposite().toYRot();
        int[] marks = battle ? new int[]{ 5, 3, 4, 1 } : new int[]{ 0, 1, 2 };
        int colour = Villages.colour(v.id());
        for (int i = 0; i < marks.length && i < play.parts().size(); i++) {
            VillageFolkEntity f = standIn(level, mark(b, marks[i]), toAudience, false, names[i]);
            if (f == null) continue;
            players.add(f);
            Playwright.Kit kit = play.parts().get(i).kit();
            // For the pictures: the costume the wardrobe would lend (a stand-in's, gone with it).
            for (Troupe.Piece p : Troupe.pieces(kit)) {
                switch (p) {
                    case SWORD -> f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, Troupe.costume(net.minecraft.world.item.Items.WOODEN_SWORD, "sword", -1));
                    case HELMET -> f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, Troupe.costume(net.minecraft.world.item.Items.LEATHER_HELMET, "helmet",
                        kit == Playwright.Kit.FOE ? -1 : colour));
                    case TUNIC -> f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, Troupe.costume(net.minecraft.world.item.Items.LEATHER_CHESTPLATE, "tunic", colour));
                    case FLOWER -> f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, Troupe.costume(net.minecraft.world.item.Items.OXEYE_DAISY, "flowers", -1));
                    case BOOK -> f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, Troupe.costume(net.minecraft.world.item.Items.BOOK, "book", -1));
                }
            }
        }
        if (battle && players.size() >= 3) {
            // The Captain and the enemy at it, sword to sword, the young soldier fallen at their feet.
            players.get(1).lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, players.get(2).getEyePosition());
            players.get(2).lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, players.get(1).getEyePosition());
            players.get(1).swing(InteractionHand.MAIN_HAND);
            if (players.size() >= 4) players.get(3).setPose(Pose.SITTING);
            FolkTalk.speak(players.get(1), "Not while I stand! At them!");
            FolkTalk.speak(players.get(2), "Yield, " + Villages.name(v.id()) + "!");
        }
        for (int i = 0; i < 8 && i < SEATS.length; i++) {
            BlockPos p = Culture.at(b, SEATS[i][0], SEATS[i][1], SEATS[i][2]);
            VillageFolkEntity f = standIn(level, p, back.toYRot(), i % 4 == 3, names[4 + i % 8]);
            if (f != null) f.setPose(Pose.SITTING);
        }
        if (!battle && !players.isEmpty() && !play.lines().isEmpty()) FolkTalk.speak(players.get(0), play.lines().get(0));
        List<String> out = new ArrayList<>();
        out.add("THEATRE " + x + " " + y + " " + z + " playing “" + play.bill() + "”");
        BlockPos stage = mark(b, 0);
        // From the street behind the benches, over the audience's heads to the stage and its banners.
        out.add("VIEW theatre-house " + x + " " + (y + 3) + " " + (z + 11) + " " + stage.getX() + " " + (stage.getY() + 1) + " " + stage.getZ());
        // From the side of the stage, along the players to the benches.
        out.add("VIEW theatre-stage " + (x + 7) + " " + (y + 3) + " " + (z - 4) + " " + x + " " + (y + 1) + " " + (z + 1));
        return out;
    }

    @Nullable
    private static VillageFolkEntity standIn(ServerLevel level, BlockPos pos, float yaw, boolean child, String name) {
        VillageFolkEntity f = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        double up = level.getBlockState(pos).getBlock() instanceof StairBlock ? 0.5 : 0.0;
        f.moveTo(pos.getX() + 0.5, pos.getY() + up, pos.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.makeShowcase(AssistantEntity.StationTask.NONE);
        if (child) f.setChild(true);
        f.rename(name);
        f.addTag("folk_lineup");
        return level.addFreshEntity(f) ? f : null;
    }

    // ------------------------------------------------------------------ what the player reads

    /** The folk's card: the last part it played, and its name as a player. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return null;
        String me = f.displayNameCap() + " as ";
        String last = null;
        List<String[]> rows = Culture.rows(id, "culture.plays");
        for (int i = rows.size() - 1; i >= 0 && last == null; i--) {
            String[] r = rows.get(i);
            if (r.length < 3) continue;
            for (String one : r[2].split(", ")) {
                if (one.startsWith(me)) { last = "played " + one.substring(me.length()) + " in “" + r[1] + "” on day " + (Culture.num(r[0], 0) + 1); break; }
            }
        }
        String fame = Troupe.cardLine(f);                                // [culture-wonders]
        if (last != null) return fame == null ? last : last + "; " + fame;
        return player(f) && anyHouse(id) ? "one of the town's players" : null;
    }

    /** The board's line: tonight's play, or the one on now. */
    @Nullable
    static String boardLine(ServerLevel level, UUID village) {
        Show s = SHOWS.get(village);
        if (s != null && s.phase != Phase.DONE && !s.rehearsal) {
            return "At " + s.venue.words() + " now: “" + s.play.bill() + "”, with " + String.join(", ", s.castNames) + " — come and watch!";
        }
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        String why = occasion(village, day);
        if (anyHouse(village) && why != null && t < LATEST && played(village) != day) {
            return "Tonight, after supper (" + why + "): “" + next(village, day).bill() + "”. A seat for a coin; standing free.";
        }
        return null;
    }

    /** The Culture page's theatre: whether it stands (or why it is wanted), its players, the play tonight, the plays so far. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        Ledger.Building b = of(id);
        out.putBoolean("built", b != null);
        int folk = Villages.headcount(id);
        out.putBoolean("wanted", b == null && wanted(id, folk));
        List<String> players = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && player(f)) players.add(f.displayNameCap() + " (" + f.persona().hobby().word + ")");
        }
        out.put("players", Culture.strings(players));
        long next = -1;
        for (long d = day; d < day + 8; d++) if (RestDay.today(id, d)) { next = d; break; }
        out.putLong("next", next < 0 ? -1 : next + 1);
        Show s = SHOWS.get(id);
        out.putString("now", s != null && s.phase != Phase.DONE && !s.rehearsal ? "“" + s.play.title() + "” with " + String.join(", ", s.castNames) : "");
        List<String> plays = new ArrayList<>();
        List<String[]> rows = Culture.rows(id, "culture.plays");
        for (int i = rows.size() - 1; i >= 0; i--) {
            String[] r = rows.get(i);
            if (r.length < 4) continue;
            plays.add("Day " + (Culture.num(r[0], 0) + 1) + ": “" + r[1] + "”" + (r.length > 5 && !r[5].isEmpty() ? ", " + r[5] : "") + " — "
                + r[2] + "; " + r[3] + " watched" + (r.length > 6 ? "; " + Playbill.stars((int) Culture.num(r[6], 0)) : ""));
        }
        out.put("plays", Culture.strings(plays));
        List<String> lines = new ArrayList<>();
        boolean house = anyHouse(id);
        if (house) {
            lines.add("the theatre stands" + (Culture.building(id, PLAYHOUSE) != null ? ", and the playhouse under its roof" : "")
                + (TownWonders.standing(id, TownWonders.AMPHITHEATRE) != null ? "; the Grand Amphitheatre in fine weather" : "")
                + (next >= 0 ? "; the next play on day " + (next + 1) + ", the day of rest" : ""));
            lines.add("coming: “" + next(id, day).bill() + "”");
        } else {
            lines.add(wanted(id, folk) ? "wanted: " + why(id) : "not yet: it wants twenty folk in the Iron Age and two players ("
                + players.size() + " now)");
        }
        if (playhouseWanted(id)) lines.add("wanted: " + playhouseWhy(id));
        if (!out.getString("now").isEmpty()) lines.add("on now: " + out.getString("now"));
        lines.addAll(Troupe.lines(id));
        lines.addAll(Playbill.lines(id));
        lines.addAll(plays);
        out.put("lines", Culture.strings(lines));
        return out;
    }

    /** [culture-wonders] The theatre's say for the town's books, as plain lines (/village theatre). */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        return Culture.strings(report(level, v), "lines");
    }

}
