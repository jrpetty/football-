package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town bell keeps the day.
 *
 * <p>Three times a day somebody walks to the town's bell and rings it: <b>three strokes at dawn</b>
 * (six in the morning) to get the town up and to work, <b>six at noon</b> for the midday meal, and
 * <b>nine at dusk</b> (six in the evening) to send everybody home — the hours the town keeps its
 * meals by (Meals). It is rung as a player rings one: the bell swings and is heard all round.
 * <ul>
 * <li><b>The bell</b> is the town's own: one it already has (the bell tower's, one by the leader's hall
 *     or the board, the alarm bell on the square, the chapel's, the bell of a village the folk moved
 *     into), the nearest to where a town bell belongs — before the leader's hall, else the board,
 *     else the heart. Nobody in a village can make a bell; one found, bought or brought by a player
 *     and put in the stores is hung by the town's works, on a plinth out of the stores: before the
 *     leader's hall once it stands, else where the watch hangs its alarm bell on the square (Watch).
 *     Till there is one, the ringer calls the hours at the board, as a town crier would.</li>
 * <li><b>The ringer</b> is somebody sensible and awake: at dawn a guard of the second watch (up all
 *     night anyway), at noon the storekeeper, at dusk a guard going on watch — or the next of them,
 *     or a courier, or whoever is grown and nearest. It sets off a little before the hour, waits at the
 *     bell and rings on the hour. If it cannot get there, whoever is standing by the bell rings it a
 *     little late; with nobody by it the hour goes unrung, and the day goes on without it.</li>
 * <li><b>Folk answer it.</b> Before the dawn bell the town lies in (the watch excepted); the bell wakes
 *     it and the day's work begins, the morning assembly at the board first. The noon bell is
 *     everybody's break at once, and the midday meal waits for it: to the café or the tavern's bar for
 *     a folk with a few coins (a dish bought out of the stores, the coin into the treasury), else home
 *     to its household's table, else to the stores; there it sits down to the meal, which Meals serves
 *     as it serves every meal (out of its pack, its home's chest or the stores) — nothing eaten twice.
 *     At the dusk bell the day's work stops and folk walk home to their beds; the guards go on watch;
 *     on Founding Day they go to the board instead.</li>
 * </ul>
 * The bell keeps the day it began: noon and dusk are rung only on a day the dawn bell was, so a town
 * that comes back to the world mid-morning keeps its own hours till the next dawn. The day's bells,
 * and who answered them when, are on the board, in the town's books and on each folk's card.
 */
public final class TownBell {

    private TownBell() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The three bells of the day: their hour, and how many strokes each has. */
    public enum Peal {
        DAWN("dawn", 0L, 3), NOON("noon", 6000L, 6), DUSK("dusk", 12000L, 9);

        public final String word;
        /** The time of day it is due. */
        public final long due;
        public final int strokes;

        Peal(String word, long due, int strokes) {
            this.word = word;
            this.due = due;
            this.strokes = strokes;
        }

        public String title() { return "the " + word + " bell"; }

        /** When it is due, in the bell's own day (which begins at 23000). */
        long inDay() { return Math.floorMod(due + 1000L, 24000L); }
    }

    /** The town's look at its bells, every so many ticks (TownCalendar's server tick). */
    static final int EVERY = 5;
    /** The ringer sets off so long before the hour. */
    static final long SET_OFF = 400L;
    /** So long after the hour, whoever is by the bell rings it; a while after that the hour is let go. */
    static final long LATE = 500L, LET_GO = 600L;
    /** Between strokes. */
    static final long STROKE = 25L;
    /** How long after the noon bell a folk still goes to its meal, and after the dusk bell home. */
    static final long MEAL_WINDOW = 2400L, HOME_WINDOW = 2000L;
    /** The end of the midday meal's hours (Meals). */
    static final long LUNCH_ENDS = 7800L;

    /** The bell's day: from an hour before dawn (23000) to the next, so the dawn bell opens the day it rings for. */
    static long bellDay(long dayTime) { return Math.floorDiv(dayTime + 1000L, 24000L); }

    static long inDay(long dayTime) { return Math.floorMod(dayTime + 1000L, 24000L); }

    /** A bell rung: when, by whom, whether late, and whether on a bell or called aloud. */
    public record Rung(Peal peal, long dayTime, String ringer, boolean late, boolean bell) {}

    /** A town's bells for one day. */
    static final class Day {
        final long bellDay;
        final EnumMap<Peal, Rung> rung = new EnumMap<>(Peal.class);
        final EnumSet<Peal> missed = EnumSet.noneOf(Peal.class);
        /** The bell whose ringer is on its way, the ringer, and since when. */
        @Nullable Peal calling;
        @Nullable UUID ringer;
        int walkTick = -1000;
        long closeSince = -1;
        /** Where the ringer stands to ring it, worked out once for the bell it is. */
        @Nullable BlockPos stand, standFor;
        /** The town lay in for this morning's dawn bell. */
        boolean dawnWaited;
        /** A bell called for out of its hour (/village bell call): its ringer walks to it and rings it, whatever the clock says. */
        boolean forced;
        long forcedAt;
        /** The peal being rung: the strokes so far, the next, the bell and who is ringing it. */
        @Nullable Peal striking;
        int struck;
        long nextStroke;
        @Nullable BlockPos strikeAt;
        @Nullable UUID striker;

        Day(long bellDay) { this.bellDay = bellDay; }
    }

    /** One folk's answers to the day's bells. */
    static final class Answer {
        final long bellDay;
        long dawn = -1, noon = -1, dusk = -1;
        String noonWhat = "", duskWhat = "";
        /** The midday meal: 0 not begun, 1 on its way, 2 at it, 3 done. */
        int meal;
        @Nullable BlockPos mealAt;
        String mealPlace = "";
        boolean homeward;
        long stageAt;
        int walkTick = -1000;
        /** It rang one of today's bells itself. */
        @Nullable Peal rang;
        long rangAt = -1;

        Answer(long bellDay) { this.bellDay = bellDay; }
    }

    private static final Map<UUID, Day> DAYS = new ConcurrentHashMap<>();
    private static final Map<UUID, Answer> ANSWERS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> BELL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Strokes rung on each town's bell since the server started (the tests count them). */
    private static final Map<UUID, Integer> STROKES = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DAYS.clear();
        ANSWERS.clear();
        BELL.clear();
        LOOKED.clear();
        STROKES.clear();
    }

    // ------------------------------------------------------------------ the day's bells

    /** This town's bells for the bell-day, as far as it has got (from the world's records after a restart). */
    static Day day(UUID village, long bellDay) {
        Day d = DAYS.get(village);
        if (d != null && d.bellDay == bellDay) return d;
        d = load(village, bellDay);
        DAYS.put(village, d);
        return d;
    }

    /** From each town's look at its bells (TownCalendar), every few ticks. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), t = inDay(dt), now = level.getGameTime();
        Day d = day(id, bellDay(dt));
        strike(level, v, d, now);                          // a peal under way: its next stroke
        if (Raids.underAlarm(id)) {                        // the alarm has the bell: no hours rung till it stops
            d.calling = null;
            d.ringer = null;
            return;
        }
        if (Villages.headcount(id) < 3) return;            // a camp of two keeps its own hours
        if (t > 1000L && t < 13500L && now % (BellFrame.building(level, id) ? 40L : 1200L) < EVERY) keepTheBell(level, v);
        if (d.forced && d.calling != null) {
            // Called for out of its hour: the ringer on its way, and rung by whoever is by it if it never gets there.
            VillageFolkEntity ringer = d.ringer == null ? null : live(level, d.ringer);
            if (ringer == null || !fitToRing(ringer, d.calling, id)) appoint(level, v, d, d.calling);
            if (now - d.forcedAt > 1200L) late(level, v, d, d.calling);
            return;
        }
        for (Peal p : Peal.values()) {
            if (d.rung.containsKey(p) || d.missed.contains(p)) continue;
            long due = p.inDay();
            if (t < due - SET_OFF) continue;
            // The bell keeps the day it began: no dawn bell, no noon or dusk bell either.
            if (p != Peal.DAWN && !d.rung.containsKey(Peal.DAWN)) { miss(v, d, p, "no dawn bell today"); continue; }
            if (t > due + LATE + LET_GO) { miss(v, d, p, "its hour went by"); continue; }
            if (d.calling != p) {
                d.calling = p;
                d.ringer = null;
                d.closeSince = -1;
                if (p == Peal.DAWN) d.dawnWaited = true;
            }
            VillageFolkEntity ringer = d.ringer == null ? null : live(level, d.ringer);
            if (ringer == null || !fitToRing(ringer, p, id)) appoint(level, v, d, p);
            if (t >= due + LATE && d.calling == p) late(level, v, d, p);
            break;                                          // one bell at a time
        }
    }

    // ------------------------------------------------------------------ the ringer

    @Nullable
    private static VillageFolkEntity live(ServerLevel level, UUID id) {
        return level.getEntity(id) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** Free to go and ring the bell: grown, at home in the town, not fighting, not talking, not wanted elsewhere. */
    static boolean fitToRing(VillageFolkEntity f, Peal p, UUID village) {
        if (!f.isAlive() || f.isBaby() || f.isHired() || f.isShowcase() || !village.equals(f.ownerId())) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || Drover.busy(f)) return false;
        if (f.getTarget() != null || Patrols.escorting(f) || Assemblies.attending(f) || TownJobs.busy(f)) return false;
        if (f.talkPartner() != null || f.companionPlayer() != null || f.guidePlayer() != null) return false;
        return p == Peal.DAWN || !f.isSleeping();             // the dawn's ringer is woken for it
    }

    /** Who rings this bell today: a guard at dawn and dusk, the storekeeper at noon, or the next best. */
    static void appoint(ServerLevel level, Villages.Village v, Day d, Peal p) {
        BlockPos at = bellAt(level, v);
        if (at == null) at = crierSpot(v);
        VillageFolkEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !fitToRing(f, p, v.id())) continue;
            AssistantEntity.StationTask trade = f.stationTask();
            double score = 0;
            switch (p) {
                // The second watch is up at dawn anyway; the storekeeper next, woken a little early.
                case DAWN -> score += trade == AssistantEntity.StationTask.GUARD ? (f.isSleeping() ? 20 : 100)
                    : trade == AssistantEntity.StationTask.STORE ? 60 : trade == AssistantEntity.StationTask.HAUL ? 40 : 0;
                case NOON -> score += trade == AssistantEntity.StationTask.STORE ? 100 : trade == AssistantEntity.StationTask.GUARD ? 70
                    : trade == AssistantEntity.StationTask.HAUL ? 50 : 0;
                case DUSK -> score += trade == AssistantEntity.StationTask.GUARD ? 100 : trade == AssistantEntity.StationTask.STORE ? 70
                    : trade == AssistantEntity.StationTask.HAUL ? 50 : 0;
            }
            if (f.isElder()) score -= 80;                     // the leader has its own day to see to
            if (f.isOld()) score -= 15;
            if (f.isSleeping()) score -= 10;
            score -= Math.sqrt(f.blockPosition().distSqr(at)) / 4.0;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        d.ringer = best == null ? null : best.getUUID();
        d.walkTick = -1000;
        d.closeSince = -1;
        if (best != null) best.brain("to ring " + p.title());
    }

    /** The ringer's part, from its own tick: to the bell, and on the hour, ring it. True while it is about it. */
    private static boolean ringerStep(VillageFolkEntity f, ServerLevel level, Day d) {
        Peal p = d.calling;
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        if (p == null || v == null || !fitToRing(f, p, id)) {
            d.ringer = null;
            return false;
        }
        if (f.isSleeping()) {
            f.stopSleeping();                                 // up before the town, to wake it
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Yawn — bell time already.", "Up, up. The town won't wake itself."));
        }
        BlockPos bell = bellAt(level, v);
        if (bell != null && !bell.equals(d.standFor)) {
            BellFrame.Frame frame = BellFrame.of(id);
            d.standFor = bell;
            d.stand = frame != null && bell.equals(frame.bell()) ? frame.stand() : standBy(level, bell);   // before its frame
        }
        BlockPos stand = bell != null && d.stand != null ? d.stand : crierSpot(v);
        long t = inDay(level.getDayTime());
        boolean there = there(f, stand, bell, level.getGameTime(), d);
        f.hobbyNow = (there ? "waiting to ring " : "on the way to ring ") + p.title() + (bell == null ? " (calling the hour, for want of a bell)" : "");
        f.lastLeisureTick = f.tickCount;
        if (!there) {
            if (f.getNavigation().isDone() || f.tickCount - d.walkTick > 60) {
                f.walkTo(stand, 1.0D);
                d.walkTick = f.tickCount;
            }
            return true;
        }
        f.getNavigation().stop();
        BlockPos look = bell != null ? bell : v.centre();
        f.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 0.5, look.getZ() + 0.5);
        if (t >= p.inDay() || d.forced) begin(level, v, d, p, f, false);
        return true;
    }

    /** At the bell: beside it (or at the foot of its tower, for the rope), or as near as the way lets it get. */
    private static boolean there(VillageFolkEntity f, BlockPos stand, @Nullable BlockPos bell, long now, Day d) {
        if (flat(f, stand) <= 1.6 && Math.abs(f.getY() - stand.getY()) <= 2.5) return true;
        if (bell != null && flat(f, bell) <= 3.5 && f.getY() <= bell.getY() + 1.5 && f.getY() >= bell.getY() - 20) return true;
        // Stuck a few steps short (a plinth in the way, a crowd at the board): near enough to reach.
        double near = bell != null ? flat(f, bell) : flat(f, stand);
        if (near <= 6.0 && f.getNavigation().isDone()) {
            if (d.closeSince < 0) d.closeSince = now;
            return now - d.closeSince > 100;
        }
        d.closeSince = -1;
        return false;
    }

    private static double flat(Entity e, BlockPos p) {
        double dx = e.getX() - (p.getX() + 0.5), dz = e.getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Past its hour with nobody at the bell: whoever is standing by it rings it, a little late; else it goes unrung. */
    private static void late(ServerLevel level, Villages.Village v, Day d, Peal p) {
        BlockPos bell = bellAt(level, v);
        BlockPos at = bell != null ? bell : crierSpot(v);
        VillageFolkEntity by = null;
        double nearest = 8.0 * 8.0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping() || f.isHired() || f.isShowcase()) continue;
            double dd = f.blockPosition().distSqr(at);
            if (dd < nearest) { nearest = dd; by = f; }
        }
        if (by != null) begin(level, v, d, p, by, true);
        else miss(v, d, p, "nobody came to ring it");
    }

    private static void miss(Villages.Village v, Day d, Peal p, String why) {
        d.missed.add(p);
        d.forced = false;
        if (d.calling == p) {
            d.calling = null;
            d.ringer = null;
        }
        save(v.id(), d);
        LOG.info("[MCA-BELL] {}: {} not rung ({})", Villages.name(v.id()), p.title(), why);
    }

    /** The bell rung: written down, the ringer's call, and the first stroke. */
    static void begin(ServerLevel level, Villages.Village v, Day d, Peal p, @Nullable VillageFolkEntity by, boolean late) {
        BlockPos bell = bellAt(level, v);
        long dt = level.getDayTime();
        d.rung.put(p, new Rung(p, dt, by == null ? "" : by.displayNameCap(), late, bell != null));
        d.missed.remove(p);
        d.forced = false;
        if (d.calling == p) {
            d.calling = null;
            d.ringer = null;
        }
        d.striking = bell != null ? p : null;
        d.struck = 0;
        d.nextStroke = level.getGameTime();
        d.strikeAt = bell;
        d.striker = by == null ? null : by.getUUID();
        save(v.id(), d);
        if (by != null) {
            Answer a = answer(by, d.bellDay);
            a.rang = p;
            a.rangAt = dt;
            FolkTalk.speak(by, call(p, bell != null, by.getRandom()));
            by.brain("rang " + p.title());
        }
        strike(level, v, d, level.getGameTime());
        LOG.info("[MCA-BELL] {}: {} rang at {}{} by {}", Villages.name(v.id()), p.title(), TownCalendar.clock(dt),
            late ? " (late)" : "", by == null ? "nobody" : by.displayNameCap() + (bell == null ? " (called aloud)" : " at " + bell.toShortString()));
    }

    /** The next stroke of a peal under way, if it is time: the bell swings and sounds, the ringer pulls. */
    private static void strike(ServerLevel level, Villages.Village v, Day d, long now) {
        Peal p = d.striking;
        if (p == null || now < d.nextStroke) return;
        BlockPos b = d.strikeAt;
        BlockState s = b == null || !level.isLoaded(b) ? null : level.getBlockState(b);
        if (s == null || !(s.getBlock() instanceof BellBlock bell)) {
            d.striking = null;
            return;
        }
        Entity by = d.striker == null ? null : level.getEntity(d.striker);
        bell.attemptToRing(by, level, b, s.getValue(BellBlock.FACING));
        if (by instanceof VillageFolkEntity f) {
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            f.getLookControl().setLookAt(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        }
        STROKES.merge(v.id(), 1, Integer::sum);
        d.struck++;
        d.nextStroke = now + STROKE;
        if (d.struck >= p.strokes) d.striking = null;
    }

    /** What the ringer calls out with the first stroke (or instead of it, with no bell to ring). */
    private static String call(Peal p, boolean bell, RandomSource r) {
        String head = bell ? "" : FolkTalk.pick(r, "Hear ye! ", "Oyez! ", "");
        return head + switch (p) {
            case DAWN -> FolkTalk.pick(r, "Dawn! Up, all of you, and to work!", "Morning, town! Up you get!", "Six o'clock and a fine day — up!");
            case NOON -> FolkTalk.pick(r, "Noon! Down tools — time to eat!", "Midday! Go and get your dinner.", "Twelve o'clock! Bread and rest, everybody.");
            case DUSK -> FolkTalk.pick(r, "Dusk! Home, everyone — the day's done.", "That's the day! Home with you.", "Evening! Lay down your tools and go home.");
        };
    }

    // ------------------------------------------------------------------ the bell itself

    /** Where the town's bell belongs: in its frame on the square (BellFrame), else at the board, else at the heart. */
    static BlockPos place(Villages.Village v) {
        BellFrame.Frame frame = BellFrame.of(v.id());
        if (frame != null) return frame.bell();
        BlockPos lectern = VillageBoards.lectern(v.id());
        return lectern != null ? lectern : v.centre();
    }

    /** Forget where the bell hung (it has been taken down): looked for afresh. */
    static void forget(UUID village) {
        BELL.remove(village);
        LOOKED.remove(village);
    }

    /** For the tests: where the bell hangs looked for afresh. */
    public static void forgetForTests(UUID village) {
        forget(village);
    }

    /** A bell in a bell tower or a chapel's tower: the town has its belfry, and rings it where it hangs. */
    static boolean inABelfry(Villages.Village v, BlockPos bell) {
        BlockPos tower = Villages.builtAt(v.id(), "belltower"), chapel = Villages.builtAt(v.id(), "chapel");
        return tower != null && bell.distSqr(tower) < 8 * 8 || chapel != null && bell.distSqr(chapel) < 14 * 14;
    }

    /** Before the leader's hall's door, or null if there is no hall. */
    @Nullable
    private static BlockPos hallFront(UUID village) {
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!b.structure().equals("townhall")) continue;
            int[] half = com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf("townhall");
            int depth = b.facing().getAxis() == Direction.Axis.X ? half[0] : half[1];
            return b.anchor().relative(b.facing(), Math.max(3, depth + 2));
        }
        return null;
    }

    /** Where the hours are called with no bell to ring: before the bell's frame once it is begun, else at the board, else the heart. */
    static BlockPos crierSpot(Villages.Village v) {
        BellFrame.Frame frame = BellFrame.of(v.id());
        if (frame != null && frame.begun) return frame.stand();
        BlockPos lectern = VillageBoards.lectern(v.id());
        return lectern != null ? lectern : v.centre();
    }

    /** The town's bell, or null if it has none. Looked for again a minute after it found none. */
    @Nullable
    public static BlockPos bellAt(ServerLevel level, Villages.Village v) {
        BlockPos known = BELL.get(v.id());
        if (known != null && level.isLoaded(known) && level.getBlockState(known).is(Blocks.BELL)) return known;
        long now = level.getGameTime();
        Long looked = LOOKED.get(v.id());
        if (known == null && looked != null && now - looked < 1200L) return null;
        LOOKED.put(v.id(), now);
        BlockPos found = findBell(level, v);
        if (found != null) BELL.put(v.id(), found);
        else BELL.remove(v.id());
        return found;
    }

    /** Every bell standing in the town, the nearest to where the town bell belongs (a bell tower's first, then its frame's). */
    @Nullable
    private static BlockPos findBell(ServerLevel level, Villages.Village v) {
        BlockPos want = place(v);
        BlockPos tower = Villages.builtAt(v.id(), "belltower");
        BellFrame.Frame frame = BellFrame.of(v.id());
        int cx = v.centre().getX() >> 4, cz = v.centre().getZ() >> 4, reach = 5;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof BellBlockEntity)) continue;
                    BlockPos p = be.getBlockPos();
                    double score = Math.sqrt(p.distSqr(want));
                    if (tower != null && Math.abs(p.getX() - tower.getX()) <= 4 && Math.abs(p.getZ() - tower.getZ()) <= 4) score -= 200;
                    if (frame != null && p.equals(frame.bell())) score -= 150;
                    if (score < bestScore) { bestScore = score; best = p.immutable(); }
                }
            }
        }
        return best;
    }

    /** Where the ringer stands: beside the bell, or the nearest floor below it (a tower's bell is rung by its rope). */
    static BlockPos standBy(ServerLevel level, BlockPos bell) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 1; dy >= -20; dy--) {
                    BlockPos p = bell.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) break;
                    BlockState here = level.getBlockState(p), head = level.getBlockState(p.above());
                    if (!here.getCollisionShape(level, p).isEmpty() || !head.getCollisionShape(level, p.above()).isEmpty()) continue;
                    if (!level.getFluidState(p).isEmpty()) continue;
                    if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                    double score = Math.abs(dx) + Math.abs(dz) + Math.abs(dy + 1) * 0.6;
                    if (score < bestScore) { bestScore = score; best = p.immutable(); }
                    break;                                  // the first floor down this column
                }
            }
        }
        return best != null ? best : bell;
    }

    /**
     * The town's bell seen to (by day): its own frame on the square, built by the town's works out of the
     * stores and the bell moved into it (BellFrame). A bell in a bell tower or a chapel is rung where it
     * hangs. With no frame to be had (no board yet, no ground for one), a bell put by in the stores is hung
     * where the watch hangs its alarm bell, on a plinth out of the stores.
     */
    private static void keepTheBell(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos bell = bellAt(level, v);
        if (bell != null && inABelfry(v, bell)) return;
        if (VillageBoards.lectern(id) != null) {
            BellFrame.work(level, v, bell);
            if (BellFrame.of(id) != null) return;
        }
        if (bell != null || Market.stock(level, id, st -> st.is(Items.BELL)) == 0) return;
        BlockPos hung = Watch.bell(level, v, false);            // the square, as the watch hangs its alarm bell
        if (hung != null) {
            LOOKED.remove(id);
            BELL.put(id, hung);
            Villages.tell(id, level.getDayTime() / 24000L, "The town bell was hung on the square");
        }
    }

    // ------------------------------------------------------------------ folk answering it

    static Answer answer(VillageFolkEntity f, long bellDay) {
        Answer a = ANSWERS.get(f.getUUID());
        if (a == null || a.bellDay != bellDay) {
            a = new Answer(bellDay);
            ANSWERS.put(f.getUUID(), a);
            if (ANSWERS.size() > 8192) ANSWERS.values().removeIf(x -> x.bellDay < bellDay - 1);
        }
        return a;
    }

    /**
     * A folk's part in the day's bells, from its own tick (TownCalendar.hold): the ringer to its bell;
     * up at the dawn bell; to its meal at the noon bell; home at the dusk bell. True while it is about it.
     */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Day d = id == null ? null : DAYS.get(id);
        long dt = level.getDayTime(), bd = bellDay(dt);
        if (d == null || d.bellDay != bd) return false;
        if (d.calling != null && f.getUUID().equals(d.ringer)) return ringerStep(f, level, d);
        if (f.isBaby() || Raids.underAlarm(id)) return false;
        Answer a = answer(f, bd);
        long t = inDay(dt), now = level.getGameTime();
        // The dawn bell: it wakes the town, and the day's work begins.
        Rung dawn = d.rung.get(Peal.DAWN);
        if (dawn != null && a.dawn < 0) {
            if (f.isSleeping() && f.onShift()) f.stopSleeping();
            if (!f.isSleeping()) {
                a.dawn = dt;
                if (f.getRandom().nextInt(4) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There's the bell. Up we get.",
                    "Morning already?", "Right — to work.", "Yawn. Five more minutes… no? Fine."));
            }
        }
        // The noon bell: the break begins, and the meal comes first.
        Rung noon = d.rung.get(Peal.NOON);
        if (noon != null && a.meal == 0 && t - inDay(noon.dayTime()) < MEAL_WINDOW && t >= inDay(noon.dayTime())
                && dt % 24000L < LUNCH_ENDS - 200L && !f.isSleeping() && f.peekJob() == null && f.offWorkNow() && !Assemblies.attending(f)) {
            if (!PutAway.look(f)) startMeal(f, level, a, now);  // [economy] the morning's work put away first (PutAway)
        }
        if (a.meal == 1 || a.meal == 2) return meal(f, level, a, now);
        // The dusk bell: the day's work is done; home.
        Rung dusk = d.rung.get(Peal.DUSK);
        if (dusk != null && a.dusk < 0 && t >= inDay(dusk.dayTime()) && t - inDay(dusk.dayTime()) < HOME_WINDOW
                && !f.isSleeping() && f.peekJob() == null && !Assemblies.attending(f)) {
            if (!PutAway.look(f)) answerDusk(f, level, a, dt, now);  // [economy] the day's work put away, then home (PutAway)
        }
        if (a.homeward) return homeward(f, a, now);
        return false;
    }

    /**
     * Off to its midday meal: to the café or the tavern with coin to spend there, else home to its household's
     * table, else to the stores — wherever is in reach; else it sits down where it is.
     */
    private static void startMeal(VillageFolkEntity f, ServerLevel level, Answer a, long now) {
        UUID id = f.ownerId();
        a.meal = 1;
        a.stageAt = now;
        a.mealAt = null;
        a.mealPlace = "";
        BlockPos cafe = Villages.builtAt(id, "cafe");
        Ledger.Building tavern = Tavern.of(id);
        BlockPos home = Homes.homeOf(f);
        BlockPos stores = f.storesSpot(level, id);
        if (f.purse() >= 2 && cafe != null && Cafe.open(id, "cafe") && cafe.distSqr(f.blockPosition()) < 96 * 96) {
            a.mealAt = cafe;
            a.mealPlace = "the café";
        } else if (f.purse() >= 2 && tavern != null && tavern.anchor().distSqr(f.blockPosition()) < 96 * 96) {
            a.mealAt = tavern.anchor();
            a.mealPlace = "the tavern";
        } else if (home != null && home.distSqr(f.blockPosition()) < (double) Meals.HOME_REACH * Meals.HOME_REACH) {
            a.mealAt = f.bedPos() != null && f.bedPos().distSqr(home) < 16 * 16 ? f.bedPos() : home;
            a.mealPlace = "home";
        } else if (stores != null && stores.distSqr(f.blockPosition()) < (double) Meals.STORES_REACH * Meals.STORES_REACH) {
            a.mealAt = stores;
            a.mealPlace = "the stores";
        }
        f.getNavigation().stop();
        if (f.getRandom().nextInt(4) == 0) FolkTalk.speak(f, a.mealAt != null && !a.mealPlace.equals("home") && !a.mealPlace.equals("the stores")
            ? FolkTalk.pick(f.getRandom(), "Dinner at " + a.mealPlace + " — I've earned it.", "Off to " + a.mealPlace + "!")
            : FolkTalk.pick(f.getRandom(), "Dinner time!", "About time. I'm starving.", "Bread and a sit-down, at last."));
    }

    private static boolean meal(VillageFolkEntity f, ServerLevel level, Answer a, long now) {
        f.lastLeisureTick = f.tickCount;
        if (a.meal == 1) {
            if (a.mealAt != null && flat(f, a.mealAt) > 3.5 && now - a.stageAt < 800) {
                if (f.getNavigation().isDone() || f.tickCount - a.walkTick > 60) {
                    f.walkTo(a.mealAt, 0.95D);
                    a.walkTick = f.tickCount;
                }
                f.hobbyNow = "on the way " + (a.mealPlace.equals("home") ? "home" : "to " + a.mealPlace) + " for the midday meal";
                return true;
            }
            f.getNavigation().stop();
            Villages.Village v = Villages.get(f.ownerId());
            a.noonWhat = v == null ? "" : sitDown(level, v, f, a);
            a.noon = level.getDayTime();
            a.meal = 2;
            a.stageAt = now;
            f.brain("the midday meal: " + a.noonWhat);
            return true;
        }
        if (now - a.stageAt < 100) {
            f.hobbyNow = "at the midday meal" + (a.mealPlace.isEmpty() ? "" : a.mealPlace.equals("home") ? ", at home" : " at " + a.mealPlace);
            f.getNavigation().stop();
            return true;
        }
        a.meal = 3;
        return false;
    }

    /** Is its midday meal had (Meals keeps the meals: this only asks)? */
    private static boolean hadLunch(VillageFolkEntity f) {
        Meals.Book b = f.meals();
        return b.day == f.level().getDayTime() / 24000L && (b.taken & (1 << Meals.Meal.LUNCH.ordinal())) != 0;
    }

    /**
     * At the table: a dish bought at the café or the tavern's bar (out of the stores, into its pack, the coin
     * into the treasury), and the meal itself eaten as Meals has every meal eaten — out of its pack, its
     * household's chest or the stores — if it has not had it yet. What it had, in words.
     */
    private static String sitDown(ServerLevel level, Villages.Village v, VillageFolkEntity f, Answer a) {
        boolean already = hadLunch(f);
        String at = a.mealAt == null || flat(f, a.mealAt) > 6.0 ? "" : a.mealPlace;
        String bought = null;
        if (!already && (at.equals("the café") || at.equals("the tavern"))) {
            bought = buyDish(level, v, f, at.equals("the café") ? Stockroom.Seller.CAFE : Stockroom.Seller.TAVERN);
        }
        Meals.tick(f);                                          // the meal, if it is mealtime and not had
        String where = at.isEmpty() ? "where it stood" : at.equals("home") ? "at home" : "at " + at;
        if (already) return "had eaten already, and sat with the others " + where;
        if (hadLunch(f)) {
            String what = lower(f.meals().lastWhat());
            return (what.isEmpty() ? "its dinner" : what) + " " + where + (bought != null ? " (bought there: " + bought + ")" : "");
        }
        if (Meals.Meal.at((int) (level.getDayTime() % 24000L)) != Meals.Meal.LUNCH) return "came too late: the midday meal was over";
        return "nothing to eat in reach " + where;
    }

    /** A dish at the café's counter or the tavern's bar: one of the café's menu out of the stores, at its price, into its pack. */
    @Nullable
    private static String buyDish(ServerLevel level, Villages.Village v, VillageFolkEntity f, Stockroom.Seller seller) {
        List<ItemStack> menu = Cafe.menuGoods(level, v.id());
        if (menu.isEmpty()) return null;
        ItemStack pick = menu.get(f.getRandom().nextInt(menu.size()));
        Market.Good g = Market.goodFor(pick);
        int price = g == null ? 1 : Market.sellPrice(g, Market.stock(level, v.id(), s -> ItemStack.isSameItemSameComponents(s, pick)), false);
        price = Math.max(1, price / Math.max(1, g == null ? 1 : g.bundle()));
        price = Stockroom.asked(level, v.id(), pick, price, 1);
        price = FolkSkills.thrifty(f, price);
        if (f.purse() < price) return null;
        if (!TownWork.take(level, v, s -> ItemStack.isSameItemSameComponents(s, pick), 1)) return null;
        ItemStack dish = pick.copyWithCount(1);
        ItemStack left = f.insertGiven(dish);
        if (!left.isEmpty()) {                                   // no room in its pack: back on the counter
            Crafts.store(level, v, left);
            return null;
        }
        f.spend(price);
        Ledger.addCoins(v.id(), price);
        Stockroom.sold(level, v.id(), seller, pick, 1, price);
        return lower(pick.getHoverName().getString());
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    /** It heard the dusk bell: the watch goes on watch; everybody else home (or, on Founding Day, to the board). */
    private static void answerDusk(VillageFolkEntity f, ServerLevel level, Answer a, long dt, long now) {
        a.dusk = dt;
        RandomSource r = f.getRandom();
        if (f.stationTask() == AssistantEntity.StationTask.GUARD) {
            a.duskWhat = "went on watch";
            if (r.nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "That's my watch.", "On the walls, then.", "Night's watch — off I go."));
            return;
        }
        if (FoundingDay.today(f.ownerId(), level.getDayTime() / 24000L)) {
            a.duskWhat = "went to the board for Founding Day";
            return;
        }
        if (f.bedPos() == null) {
            a.duskWhat = "had no bed of its own yet, so stayed out for the evening";
            return;
        }
        a.homeward = true;
        a.stageAt = now;
        a.duskWhat = "set off home";
        f.getNavigation().stop();
        if (r.nextInt(4) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Home time!", "That's the day done.", "Supper, then bed.", "Home, and my feet up."));
    }

    private static boolean homeward(VillageFolkEntity f, Answer a, long now) {
        BlockPos home = f.bedPos();
        if (home == null || now - a.stageAt > 1500) {
            a.homeward = false;
            return false;
        }
        if (flat(f, home) <= 3.0 && Math.abs(f.getY() - home.getY()) <= 3.0) {
            a.homeward = false;
            a.duskWhat = "went home (there by " + TownCalendar.clock(f.level().getDayTime()) + ")";
            f.getNavigation().stop();
            return false;
        }
        if (f.getNavigation().isDone() || f.tickCount - a.walkTick > 80) {
            f.walkTo(home, 0.9D);
            a.walkTick = f.tickCount;
        }
        f.hobbyNow = "on the way home at the dusk bell";
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    // ------------------------------------------------------------------ what the rest of the folk's day asks

    /** Is this folk about the bell just now — ringing it, at its meal, on its way home (its own work waits)? */
    static boolean busy(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Day d = id == null ? null : DAYS.get(id);
        if (d == null) return false;
        if (d.calling != null && f.getUUID().equals(d.ringer)) return true;
        Answer a = ANSWERS.get(f.getUUID());
        return a != null && a.bellDay == d.bellDay && (a.meal == 1 || a.meal == 2 || a.homeward);
    }

    /**
     * Is this folk at work now, as the bell has it (VillageFolkEntity.onShift)? False before the dawn bell
     * (the town lies in for it), after the dusk bell, and while it is about the bell; null when the bell
     * has no say (the watch, a hired hand, a child, a town that does not keep the bell today).
     */
    @Nullable
    public static Boolean shift(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Day d = id == null ? null : DAYS.get(id);
        if (d == null) return null;
        if (TownCalendar.busy(f)) return false;
        if (f.stationTask() == AssistantEntity.StationTask.GUARD || f.isHired() || f.isBaby()) return null;
        long dt = f.level().getDayTime();
        if (d.bellDay != bellDay(dt)) return null;
        if (d.dawnWaited && !d.rung.containsKey(Peal.DAWN) && inDay(dt) < Peal.DAWN.inDay() + LATE) return false;
        if (d.rung.containsKey(Peal.DUSK)) return false;
        return null;
    }

    /**
     * Does this town's midday meal wait for its noon bell (Meals)? On a day the bell keeps, till the bell has
     * rung (or been let go by), and while the meal's hours last.
     */
    public static boolean lunchWaits(@Nullable UUID village, long dayTime) {
        Day d = village == null ? null : DAYS.get(village);
        if (d == null || d.bellDay != bellDay(dayTime)) return false;
        if (!d.rung.containsKey(Peal.DAWN) || d.rung.containsKey(Peal.NOON) || d.missed.contains(Peal.NOON)) return false;
        return dayTime % 24000L < LUNCH_ENDS - 300L;
    }

    /** Is the town up: not lying in for its dawn bell (the morning assembly waits for it: Assemblies)? */
    public static boolean up(@Nullable UUID village, long dayTime) {
        Day d = village == null ? null : DAYS.get(village);
        if (d == null || d.bellDay != bellDay(dayTime)) return true;
        return !(d.dawnWaited && !d.rung.containsKey(Peal.DAWN) && !d.missed.contains(Peal.DAWN) && inDay(dayTime) < Peal.DAWN.inDay() + LATE);
    }

    /**
     * When this folk's break begins today (VillageFolkEntity.breakNow): at the noon bell, all together, on a
     * day the bell keeps; before it rings, not at all; otherwise at its own hour.
     */
    public static long breakFrom(@Nullable UUID village, Level level, long own) {
        Day d = village == null ? null : DAYS.get(village);
        if (d == null) return own;
        long dt = level.getDayTime();
        if (d.bellDay != bellDay(dt)) return own;
        Rung noon = d.rung.get(Peal.NOON);
        if (noon != null) return Math.floorMod(noon.dayTime(), 24000L);
        if (!d.rung.containsKey(Peal.DAWN) || d.missed.contains(Peal.NOON)) return own;
        return 100000L;                                       // the break waits for the noon bell
    }

    // ------------------------------------------------------------------ what the player sees

    /** When it rang today, or null. */
    @Nullable
    public static Rung rung(UUID village, Peal p, long dayTime) {
        Day d = DAYS.get(village);
        return d == null || d.bellDay != bellDay(dayTime) ? null : d.rung.get(p);
    }

    /** For the tests: strokes rung on this town's bell so far. */
    public static int strokes(UUID village) {
        return STROKES.getOrDefault(village, 0);
    }

    /** For the tests: is a peal being rung on this town's bell just now (strokes still to come)? */
    public static boolean ringing(UUID village) {
        Day d = DAYS.get(village);
        return d != null && d.striking != null;
    }

    /** For the tests: who is to ring the bell now, or null. */
    @Nullable
    public static UUID ringer(UUID village) {
        Day d = DAYS.get(village);
        return d == null || d.calling == null ? null : d.ringer;
    }

    /** For the tests: when this folk answered today's bell (its time of day), or -1. */
    public static long answered(VillageFolkEntity f, Peal p) {
        Answer a = ANSWERS.get(f.getUUID());
        if (a == null || a.bellDay != bellDay(f.level().getDayTime())) return -1;
        return switch (p) { case DAWN -> a.dawn; case NOON -> a.noon; case DUSK -> a.dusk; };
    }

    /** For the tests and the card: what it did at the noon bell ("bread from its pack"), and at the dusk bell. */
    public static String did(VillageFolkEntity f, Peal p) {
        Answer a = ANSWERS.get(f.getUUID());
        if (a == null || a.bellDay != bellDay(f.level().getDayTime())) return "";
        return p == Peal.NOON ? a.noonWhat : p == Peal.DUSK ? a.duskWhat : a.dawn >= 0 ? "got up" : "";
    }

    /** Is it on its way home from the dusk bell (the tests)? */
    public static boolean homeward(VillageFolkEntity f) {
        Answer a = ANSWERS.get(f.getUUID());
        return a != null && a.homeward && a.bellDay == bellDay(f.level().getDayTime());
    }

    /** Where it hangs, in words. */
    private static String where(ServerLevel level, Villages.Village v, BlockPos bell) {
        BlockPos hall = hallFront(v.id()), tower = Villages.builtAt(v.id(), "belltower"), chapel = Villages.builtAt(v.id(), "chapel");
        BlockPos lectern = VillageBoards.lectern(v.id());
        BellFrame.Frame frame = BellFrame.of(v.id());
        if (frame != null && bell.equals(frame.bell())) return "in its own frame on the square";
        if (tower != null && bell.distSqr(tower) < 8 * 8) return "in the bell tower";
        if (hall != null && bell.distSqr(hall) < 10 * 10) return "before the leader's hall";
        if (chapel != null && bell.distSqr(chapel) < 14 * 14) return "in the chapel's tower";
        if (lectern != null && bell.distSqr(lectern) < 12 * 12) return "by the board";
        if (bell.distSqr(v.centre()) < 16 * 16) return "on the square";
        return "at " + bell.getX() + ", " + bell.getZ();
    }

    /** The board's line: today's bells, who rang them, and the next. Null for a town that keeps no bell. */
    @Nullable
    static String line(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null || Villages.headcount(village) < 3) return null;
        long dt = level.getDayTime();
        Day d = day(village, bellDay(dt));
        BlockPos bell = BELL.get(village);
        StringBuilder sb = new StringBuilder(bell != null ? "The bell today: " : "The hours today (no bell yet, so they are called at the board): ");
        List<String> parts = new ArrayList<>();
        for (Peal p : Peal.values()) {
            Rung r = d.rung.get(p);
            if (r != null) parts.add(p.word + " " + TownCalendar.clock(r.dayTime()) + (r.ringer().isEmpty() ? "" : " (" + r.ringer() + ")") + (r.late() ? ", late" : ""));
            else if (d.missed.contains(p)) parts.add(p.word + " not rung");
            else parts.add(p.word + " at " + TownCalendar.clock(p.due));
        }
        sb.append(String.join(", ", parts)).append('.');
        return sb.toString();
    }

    /** The books' lines (the News page): where the bell hangs, and each of today's bells: when, by whom, who answered. */
    static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.get(village);
        if (v == null) return out;
        if (Villages.headcount(village) < 3) {
            out.add("The town bell: not kept yet — a camp of fewer than three keeps its own hours.");
            return out;
        }
        BlockPos bell = bellAt(level, v);
        out.add(bell != null ? "The town bell hangs " + where(level, v, bell) + ": three strokes at dawn (6:00), six at noon, nine at dusk (18:00)."
            : "No town bell yet: the hours are called " + (BellFrame.of(village) != null ? "before its frame" : "at the board")
                + ". A bell put in the stores is hung " + (BellFrame.of(village) != null ? "in its frame on the square." : "on the square."));
        BellFrame.Frame frame = BellFrame.of(village);
        if (frame != null && !BellFrame.hung(level, frame)) {
            out.add(BellFrame.standing(level, frame) ? "The bell's frame stands on the square, waiting for the bell."
                : "The town bell's own frame is going up on the square" + (frame.begun ? "" : " (once the stores have the timber for it)")
                    + "; the bell keeps ringing where it hangs till it is moved there.");
        }
        long dt = level.getDayTime();
        Day d = day(village, bellDay(dt));
        int up = 0, ate = 0, cafe = 0, home = 0, grown = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            grown++;
            Answer an = ANSWERS.get(f.getUUID());
            if (an == null || an.bellDay != d.bellDay) continue;
            if (an.dawn >= 0) up++;
            if (an.noon >= 0) {
                ate++;
                if (an.noonWhat.contains("café") || an.noonWhat.contains("tavern")) cafe++;
            }
            if (an.dusk >= 0) home++;
        }
        for (Peal p : Peal.values()) {
            Rung r = d.rung.get(p);
            String head = capital(p.word) + " bell";
            if (r == null) {
                out.add(head + (d.missed.contains(p) ? ": not rung today." : ": due at " + TownCalendar.clock(p.due) + "."));
                continue;
            }
            String answered = switch (p) {
                case DAWN -> up + " of " + grown + " got up to it";
                case NOON -> ate + " of " + grown + " went to eat" + (cafe > 0 ? " (" + cafe + " at the café or the tavern)" : "");
                case DUSK -> home + " of " + grown + " went home or on watch";
            };
            out.add(head + (r.bell() ? " rang at " : " called at ") + TownCalendar.clock(r.dayTime())
                + (r.ringer().isEmpty() ? "" : ", rung by " + r.ringer()) + (r.late() ? " (late)" : "") + " — " + answered + ".");
        }
        return out;
    }

    /** A folk's card: when it answered today's bells, and any it rang. Empty if nothing yet today. */
    public static String cardLine(VillageFolkEntity f) {
        Answer a = ANSWERS.get(f.getUUID());
        if (a == null || a.bellDay != bellDay(f.level().getDayTime())) return "";
        List<String> parts = new ArrayList<>();
        if (a.rang != null) parts.add("rang " + a.rang.title() + " at " + TownCalendar.clock(a.rangAt));
        if (a.dawn >= 0) parts.add("up at the dawn bell (" + TownCalendar.clock(a.dawn) + ")");
        if (a.noon >= 0) parts.add("ate at the noon bell (" + TownCalendar.clock(a.noon) + "): " + a.noonWhat);
        else if (a.meal == 1) parts.add("on its way to its meal");
        if (a.dusk >= 0) parts.add(a.duskWhat + " at the dusk bell (" + TownCalendar.clock(a.dusk) + ")");
        return parts.isEmpty() ? "" : capital(String.join("; ", parts)) + ".";
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the command

    /** /village bell: where it hangs, today's bells, who rings next. A "BELL-AT x y z" line for scripts. */
    public static String status(ServerLevel level, Villages.Village v) {
        StringBuilder sb = new StringBuilder("The bell of " + Villages.name(v.id()) + ". ");
        LOOKED.remove(v.id());                                  // asked: looked for afresh
        BlockPos bell = bellAt(level, v);
        BlockPos lectern = VillageBoards.lectern(v.id());
        if (lectern != null) sb.append("BOARD ").append(lectern.getX()).append(' ').append(lectern.getY()).append(' ').append(lectern.getZ()).append(". ");
        if (bell != null) sb.append("BELL-AT ").append(bell.getX()).append(' ').append(bell.getY()).append(' ').append(bell.getZ()).append(". ");
        else {
            BlockPos spot = crierSpot(v);
            sb.append("BELL-NONE (the hours are called at the board) SPOT ").append(spot.getX()).append(' ').append(spot.getY())
                .append(' ').append(spot.getZ()).append(". ");
        }
        sb.append(BellFrame.status(level, v)).append(' ');
        for (String l : book(level, v.id())) sb.append(l).append(' ');
        Day d = DAYS.get(v.id());
        if (d != null && d.calling != null) {
            VillageFolkEntity r = d.ringer == null ? null : live(level, d.ringer);
            sb.append("On the way to ring ").append(d.calling.title()).append(": ").append(r == null ? "nobody yet" : r.displayNameCap()).append('.');
        }
        return sb.toString().trim();
    }

    /**
     * /village bell call dawn|noon|dusk: the bell called for now, out of its hour: its ringer walks to it and
     * rings it, and the town answers it as at its hour.
     */
    public static String callNow(ServerLevel level, Villages.Village v, Peal p) {
        long dt = level.getDayTime();
        Day d = day(v.id(), bellDay(dt));
        LOOKED.remove(v.id());
        d.rung.remove(p);
        d.missed.remove(p);
        d.calling = p;
        d.forced = true;
        d.forcedAt = level.getGameTime();
        if (p == Peal.DAWN) d.dawnWaited = true;
        appoint(level, v, d, p);
        afresh(v, d, p);
        VillageFolkEntity by = d.ringer == null ? null : live(level, d.ringer);
        return capital(p.title()) + " called for: " + (by == null ? "nobody to ring it yet" : by.displayNameCap() + " is on the way to ring it") + ".";
    }

    /** /village bell ring dawn|noon|dusk: the bell rung now, by whoever would ring it, and the town answers. */
    public static String ringNow(ServerLevel level, Villages.Village v, Peal p) {
        long dt = level.getDayTime();
        Day d = day(v.id(), bellDay(dt));
        LOOKED.remove(v.id());                                  // a bell set up a moment ago is found
        appoint(level, v, d, p);
        VillageFolkEntity by = d.ringer == null ? null : live(level, d.ringer);
        d.calling = p;
        if (p == Peal.DAWN) d.dawnWaited = true;
        begin(level, v, d, p, by, false);
        afresh(v, d, p);
        return capital(p.title()) + " rang at " + TownCalendar.clock(dt) + (by == null ? " (nobody to ring it: rung by hand)" : ", rung by " + by.displayNameCap())
            + (bellAt(level, v) == null ? " — called aloud: the town has no bell yet" : "") + ".";
    }

    /** A bell rung (or called for) by hand today: the town answers it afresh. */
    private static void afresh(Villages.Village v, Day d, Peal p) {
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            Answer an = ANSWERS.get(a.getUUID());
            if (an == null || an.bellDay != d.bellDay) continue;
            switch (p) {
                case DAWN -> an.dawn = -1;
                case NOON -> { an.noon = -1; an.meal = 0; an.noonWhat = ""; }
                case DUSK -> { an.dusk = -1; an.homeward = false; an.duskWhat = ""; }
            }
        }
    }

    // ------------------------------------------------------------------ kept with the world

    private static void save(UUID village, Day d) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("BellDay", d.bellDay);
        tag.putBoolean("DawnWaited", d.dawnWaited);
        ListTag rung = new ListTag();
        for (Rung r : d.rung.values()) {
            CompoundTag one = new CompoundTag();
            one.putString("Peal", r.peal().name());
            one.putLong("Time", r.dayTime());
            one.putString("Ringer", r.ringer());
            one.putBoolean("Late", r.late());
            one.putBoolean("Bell", r.bell());
            rung.add(one);
        }
        tag.put("Rung", rung);
        ListTag missed = new ListTag();
        for (Peal p : d.missed) missed.add(StringTag.valueOf(p.name()));
        tag.put("Missed", missed);
        TownCalendar.bells(village, tag);
    }

    private static Day load(UUID village, long bellDay) {
        Day d = new Day(bellDay);
        CompoundTag tag = TownCalendar.bells(village);
        if (tag == null || tag.getLong("BellDay") != bellDay) return d;
        d.dawnWaited = tag.getBoolean("DawnWaited");
        for (Tag t : tag.getList("Rung", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            try {
                Peal p = Peal.valueOf(one.getString("Peal"));
                d.rung.put(p, new Rung(p, one.getLong("Time"), one.getString("Ringer"), one.getBoolean("Late"), one.getBoolean("Bell")));
            } catch (IllegalArgumentException ignored) { }
        }
        for (Tag t : tag.getList("Missed", Tag.TAG_STRING)) {
            try { d.missed.add(Peal.valueOf(t.getAsString())); } catch (IllegalArgumentException ignored) { }
        }
        return d;
    }
}
