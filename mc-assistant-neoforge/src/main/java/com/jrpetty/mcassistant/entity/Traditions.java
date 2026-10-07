package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town's customs [batchD] (Culture): its great days, kept every year on their anniversary.
 *
 * <p>What makes a day great is in the town's own chronicle: its founding, the night the raiders came at a
 * gate and the watch held it, a great storm, the first diamond out of its mines. The first of each kind
 * becomes a custom, three in a town at most (its founding always the first of them), each kept its own
 * way on the same day of the town's year (four weeks: TownCalendar):
 * <ul>
 * <li><b>a minute's silence at the bell</b> for those who fell, at the dusk bell: the bell tolls, and every
 *     grown folk stops where it is, faces the bell with its head bowed, and is quiet a minute (a minute of
 *     ours: twelve hundred ticks); the bell again, and the evening goes on;</li>
 * <li><b>lanterns lit on the square</b> (the founding, a great storm): before dusk a hand at the town's works
 *     sets lanterns round the square out of the stores (torches, if it has none), and takes them in again
 *     the next morning, back into the stores;</li>
 * <li><b>a toast at the tavern</b> (a raid beaten off with nobody lost, the first diamond): of an evening
 *     the town goes to the tavern (or the square, with none), and once they are in the eldest raises a cup
 *     "To Ember, and the first diamond!", and they all drink it, a bottle of the café's each out of the
 *     stores (water, with none).</li>
 * </ul>
 * The crier cries the custom on its day and the day before, and the motto on every feast day (the weekly
 * feast, Founding Day, a celebration, a wedding, the day of rest). The board and the Culture page of the
 * town's books list them, with the next day each is kept.
 */
public final class Traditions {

    private Traditions() {}

    /** Customs a town keeps at most. */
    static final int MOST = 3;
    /** How long the town is quiet: a minute. */
    static final long SILENCE = 1200L;
    /** When the silence begins: just after the dusk bell (its ringer has rung it, and the town is on its way home). */
    static final long DUSK = 12100L;
    /** The toast's evening: from when the folk are in, till late. */
    static final long TOAST_FROM = 13000L, TOAST_TO = 15500L;
    /** Lanterns set out from the afternoon, by day (the town's works are done by day). */
    static final long LANTERNS_FROM = 9000L;
    /** How many lanterns go round the square. */
    static final int[][] RING = { { 5, 0 }, { -5, 0 }, { 0, 5 }, { 0, -5 }, { 4, 4 }, { -4, 4 }, { 4, -4 }, { -4, -4 } };

    /** What made the day great. */
    public enum Why {
        FOUNDING("the founding"), RAID("the raid beaten off"), STORM("the great storm"), DIAMOND("the first diamond"),
        // [war-peace] The end of a war: Remembrance Day, kept with a minute's silence at the dusk bell (WarAndPeace).
        WAR("the war's end");

        public final String words;
        Why(String words) { this.words = words; }
    }

    /** How the day is kept. */
    public enum How {
        SILENCE("a minute's silence", "at the dusk bell"),
        LANTERNS("lanterns lit on the square", "at dusk"),
        TOAST("a toast at the tavern", "in the evening");

        public final String words, when;
        How(String words, String when) {
            this.words = words;
            this.when = when;
        }
    }

    /** A custom: why, how, the day it began, its name, to whom it is kept, and the last day it was kept. */
    public record Custom(Why why, How how, long day, String name, String toWhom, long kept) {
        String[] row() {
            return new String[]{ why.name(), how.name(), Long.toString(day), name, toWhom, Long.toString(kept) };
        }
    }

    private static final String NOTE = "culture.customs", LIT = "culture.lit";

    /** Each town's customs as last read, against the note they were read from (every folk asks every few ticks of an evening). */
    private static final Map<UUID, Object[]> READ = new ConcurrentHashMap<>();

    /** A town's customs, in the order they were taken up. */
    public static List<Custom> customs(UUID village) {
        String raw = Ledger.note(village, NOTE);
        Object[] was = READ.get(village);
        if (was != null && java.util.Objects.equals(was[0], raw)) {
            @SuppressWarnings("unchecked") List<Custom> same = (List<Custom>) was[1];
            return new ArrayList<>(same);
        }
        List<Custom> out = new ArrayList<>();
        for (String[] r : Culture.rows(village, NOTE)) {
            if (r.length < 6) continue;
            try {
                out.add(new Custom(Why.valueOf(r[0]), How.valueOf(r[1]), Culture.num(r[2], 0), r[3], r[4], Culture.num(r[5], -1)));
            } catch (IllegalArgumentException ignored) {
                // a kind from a later build: left out
            }
        }
        READ.put(village, new Object[]{ raw, List.copyOf(out) });
        return out;
    }

    /**
     * [war-peace] A war's end kept every year (WarAndPeace): Remembrance Day, a minute's silence at the dusk bell
     * on the day the peace was made, for whoever the war cost. Kept whatever other customs the town has: a town
     * does not choose between its founding and its dead.
     */
    public static void remember(UUID village, long day, String name, String toWhom) {
        List<Custom> have = customs(village);
        for (Custom c : have) if (c.why() == Why.WAR && c.day() == day) return;
        have.add(new Custom(Why.WAR, How.SILENCE, day, name, toWhom, -1));
        save(village, have);
    }

    private static void save(UUID village, List<Custom> customs) {
        List<String[]> rows = new ArrayList<>();
        for (Custom c : customs) rows.add(c.row());
        Culture.rows(village, NOTE, rows);
    }

    /** Is today this custom's day: its anniversary, in the town's year (the founding's: Founding Day)? */
    static boolean today(UUID village, Custom c, long day) {
        if (c.why() == Why.FOUNDING) return FoundingDay.today(village, day);
        return day > c.day() && (day - c.day()) % TownCalendar.YEAR_DAYS == 0;
    }

    /** The next day this custom is kept, from this day (today, if today is its day). */
    static long next(UUID village, Custom c, long day) {
        long from = c.why() == Why.FOUNDING ? FoundingDay.founded(village) : c.day();
        if (from < 0) return -1;
        long n = from + TownCalendar.YEAR_DAYS * Math.max(1, (day - from + TownCalendar.YEAR_DAYS - 1) / TownCalendar.YEAR_DAYS);
        return n;
    }

    // ------------------------------------------------------------------ the great days

    /** What great day a line of the chronicle tells of, or null. */
    @Nullable
    static Why great(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("raiders came")) return Why.RAID;
        if (t.contains("a great storm broke")) return Why.STORM;
        if (t.contains("first diamond") || t.contains("diamond") && t.contains("the first")) return Why.DIAMOND;
        return null;
    }

    /** A great day made a custom: its name, how it is kept, and to whom. */
    static Custom custom(Why why, Chronicle.Entry e) {
        long day = e.day();
        String t = e.text();
        return switch (why) {
            case RAID -> t.contains("of the village fell")
                ? new Custom(why, How.SILENCE, day, "the Watch's Silence", "those who fell at the gate on day " + (day + 1), -1)
                : new Custom(why, How.TOAST, day, "Gate Night", "the watch, who held the gate on day " + (day + 1), -1);
            case STORM -> new Custom(why, How.LANTERNS, day, "Stormlight", "the great storm of day " + (day + 1), -1);
            case DIAMOND -> new Custom(why, How.TOAST, day, "Diamond Night", finder(t) + "the first diamond", -1);
            case FOUNDING -> new Custom(why, How.LANTERNS, day, "the Founders' Lanterns", "the founders", -1);
            case WAR -> new Custom(why, How.SILENCE, day, "Remembrance Day", "those the war cost", -1);   // [war-peace]
        };
    }

    /** "Ember the miner mined the town's first diamond": "Ember, and ". */
    private static String finder(String text) {
        int the = text.indexOf(" the ");
        int sp = text.indexOf(' ');
        String who = the > 0 ? text.substring(0, the) : sp > 0 ? text.substring(0, sp) : "";
        return who.isEmpty() || Character.isLowerCase(who.charAt(0)) ? "" : who + ", and ";
    }

    /**
     * The town's great days looked over (each minute): its founding a custom from the first, and each other
     * kind of great day the first time it comes, while the town keeps fewer than three. Into its history.
     */
    static void adopt(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<Custom> have = customs(id);
        if (have.size() >= MOST) return;
        long today = level.getDayTime() / 24000L;
        boolean changed = false;
        Set<Why> kinds = new HashSet<>();
        for (Custom c : have) kinds.add(c.why());
        long founded = FoundingDay.founded(id);
        if (founded >= 0 && !kinds.contains(Why.FOUNDING)) {
            have.add(custom(Why.FOUNDING, new Chronicle.Entry(founded, "")));
            kinds.add(Why.FOUNDING);
            changed = true;
        }
        for (Chronicle.Entry e : Chronicle.of(id)) {
            if (have.size() >= MOST) break;
            Why w = great(e.text());
            if (w == null || kinds.contains(w)) continue;
            Custom c = custom(w, e);
            have.add(c);
            kinds.add(w);
            changed = true;
            Villages.tell(id, today, "the town resolved to keep " + c.name() + " every year, " + c.how().words + " for " + c.toWhom());
        }
        if (changed) save(id, have);
    }

    // ------------------------------------------------------------------ the town's look

    /** Each town's thunder today: the day, and how many seconds of it. */
    private static final Map<UUID, long[]> THUNDER = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SCANNED = new ConcurrentHashMap<>();
    /** Today's silence in each town: the day, who kept it, and whether the bell has tolled its start and its end. */
    private static final Map<UUID, Silence> SILENCES = new ConcurrentHashMap<>();
    private static final Map<UUID, Toast> TOASTS = new ConcurrentHashMap<>();
    /** What the crier was given to cry of the customs and the motto, the last time (the tests read it). */
    private static final Map<UUID, List<String>> CRIED = new ConcurrentHashMap<>();
    /** What the lanterns wait on, in words, while they wait. */
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();

    static final class Silence {
        final long day;
        final Set<UUID> kept = ConcurrentHashMap.newKeySet();
        boolean tolled, ended;
        Silence(long day) { this.day = day; }
    }

    static final class Toast {
        final long day;
        final Set<UUID> there = ConcurrentHashMap.newKeySet();
        boolean given;
        long givenAt;
        String by = "";
        int drinks;
        Toast(long day) { this.day = day; }
    }

    static void resetForTests() {
        READ.clear();
        THUNDER.clear();
        SCANNED.clear();
        SILENCES.clear();
        TOASTS.clear();
        CRIED.clear();
        SHORT.clear();
    }

    /** The town's look at its customs (Culture, each second). */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime(), day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        storm(level, v, day);
        Long scanned = SCANNED.get(id);
        if (scanned == null || now - scanned >= 1200L) {
            SCANNED.put(id, now);
            adopt(level, v);
        }
        takeIn(level, v, day, t);
        for (Custom c : customs(id)) {
            if (!today(id, c, day)) continue;
            switch (c.how()) {
                case LANTERNS -> { if (t >= LANTERNS_FROM && t < 12500L) setOut(level, v, c, day); }
                case SILENCE -> toll(level, v, c, day, t);
                case TOAST -> { }                                   // the folk's own (toast), and its close below
            }
        }
    }

    /** A thunderstorm over the town for most of a minute is a great storm: into its history, once a day. */
    private static void storm(ServerLevel level, Villages.Village v, long day) {
        if (!level.isThundering() || !level.canSeeSky(v.centre().above(2))) return;
        long[] th = THUNDER.computeIfAbsent(v.id(), k -> new long[]{ day, 0 });
        if (th[0] != day) {
            th[0] = day;
            th[1] = 0;
        }
        if (++th[1] == 45) Villages.tell(v.id(), day, "a great storm broke over " + Villages.name(v.id()));
    }

    // ------------------------------------------------------------------ the silence

    /** The bell tolled at the start of the silence and at its end; and its end written down. */
    private static void toll(ServerLevel level, Villages.Village v, Custom c, long day, long t) {
        if (t < DUSK || t > DUSK + SILENCE + 400L) return;              // (a town come back to the world late that night: let be)
        Silence s = SILENCES.compute(v.id(), (k, was) -> was == null || was.day != day ? new Silence(day) : was);
        BlockPos bell = bell(level, v);
        if (!s.tolled) {
            s.tolled = true;
            for (int i = 0; i < 3; i++) level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.0F, 0.6F);
        }
        if (!s.ended && t >= DUSK + SILENCE) {
            s.ended = true;
            level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.0F, 0.7F);
            if (!s.kept.isEmpty()) {
                Villages.tell(v.id(), day, "the town kept " + c.name() + ", a minute's silence at the bell for " + c.toWhom()
                    + " (" + s.kept.size() + (s.kept.size() == 1 ? " folk" : " folk") + ")");
                kept(v.id(), c, day);
            }
        }
    }

    /** The town's bell, or where its crier stands with none. */
    static BlockPos bell(ServerLevel level, Villages.Village v) {
        BlockPos b = TownBell.bellAt(level, v);
        return b != null ? b : TownBell.crierSpot(v);
    }

    /** A folk's part in the silence: where it is, still, facing the bell, its head bowed. True while it keeps it. */
    static boolean silence(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        if (t < DUSK || t >= DUSK + SILENCE || Assemblies.attending(f)) return false;
        if (f.getUUID().equals(TownBell.ringer(v.id())) || TownJobs.busy(f)) return false;     // the bell's own ringer rings it
        Custom c = todays(v.id(), day, How.SILENCE);
        if (c == null) return false;
        if (f.blockPosition().distSqr(v.centre()) > (double) Villages.townReach(v.id()) * Villages.townReach(v.id())) return false;
        Culture.mark(f, Culture.Role.SILENCE, "keeping a minute's silence for " + c.toWhom());
        f.getNavigation().stop();
        BlockPos bell = bell(level, v);
        f.getLookControl().setLookAt(bell.getX() + 0.5, f.getEyeY() - 3.0, bell.getZ() + 0.5);
        SILENCES.compute(v.id(), (k, was) -> was == null || was.day != day ? new Silence(day) : was).kept.add(f.getUUID());
        return true;
    }

    @Nullable
    private static Custom todays(UUID village, long day, How how) {
        for (Custom c : customs(village)) if (c.how() == how && today(village, c, day)) return c;
        return null;
    }

    private static void kept(UUID village, Custom c, long day) {
        List<Custom> all = customs(village);
        for (int i = 0; i < all.size(); i++) {
            Custom x = all.get(i);
            if (x.why() == c.why()) all.set(i, new Custom(x.why(), x.how(), x.day(), x.name(), x.toWhom(), day));
        }
        save(village, all);
    }

    // ------------------------------------------------------------------ the lanterns

    /** The lanterns set out on the square: the day, and where each stands. */
    private static List<BlockPos> lit(UUID village, long[] dayOut) {
        List<BlockPos> out = new ArrayList<>();
        dayOut[0] = -1;
        for (String[] r : Culture.rows(village, LIT)) {
            if (r.length < 4) continue;
            dayOut[0] = Culture.num(r[0], -1);
            out.add(new BlockPos((int) Culture.num(r[1], 0), (int) Culture.num(r[2], 0), (int) Culture.num(r[3], 0)));
        }
        return out;
    }

    private static void lit(UUID village, long day, List<BlockPos> at) {
        List<String[]> rows = new ArrayList<>();
        for (BlockPos p : at) rows.add(new String[]{ Long.toString(day), Integer.toString(p.getX()), Integer.toString(p.getY()), Integer.toString(p.getZ()) });
        if (rows.isEmpty()) Ledger.forget(village, LIT);
        else Culture.rows(village, LIT, rows);
    }

    /** The next lantern set out round the square, out of the stores. True while there is more to do. */
    static boolean setOut(ServerLevel level, Villages.Village v, Custom c, long day) {
        UUID id = v.id();
        long[] when = new long[1];
        List<BlockPos> at = lit(id, when);
        if (when[0] >= 0 && when[0] != day) return false;              // last year's still out: taken in first
        int done = 0;
        for (int[] o : RING) {
            BlockPos spot = lanternSpot(level, v, o);
            if (spot == null) continue;
            if (at.contains(spot)) { done++; continue; }
            boolean lantern = Crafts.stock(level, v, s -> s.is(Items.LANTERN)) > 0;
            boolean torch = !lantern && Crafts.stock(level, v, s -> s.is(Items.TORCH)) > 0;
            if (!lantern && !torch) {
                SHORT.put(id, "lanterns (or torches) in the stores");
                return false;
            }
            SHORT.remove(id);
            if (!TownJobs.atWork(level, v, "lanterns", spot, "setting out lanterns on the square for " + c.name())) return true;
            if (!Crafts.take(level, v, s -> s.is(lantern ? Items.LANTERN : Items.TORCH), 1)) return false;
            level.setBlock(spot, (lantern ? Blocks.LANTERN : Blocks.TORCH).defaultBlockState(), 3);
            level.sendParticles(ParticleTypes.FLAME, spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
            at.add(spot);
            lit(id, day, at);
            if (at.size() == 1) {
                Villages.tell(id, day, "the town kept " + c.name() + ": lanterns lit on the square, for " + c.toWhom());
                kept(id, c, day);
            }
            return true;
        }
        return false;
    }

    /** Where a lantern goes on the square: on the ground there, in the open, clear of doors and of the streets. */
    @Nullable
    private static BlockPos lanternSpot(ServerLevel level, Villages.Village v, int[] o) {
        BlockPos c = v.centre();
        if (TownPlan.isStreet(o[0], o[1])) return null;
        // Down the column from a little over the heart's height to the first open cell on firm ground (a lantern
        // set out already is open: it is that lantern's place), under the open sky.
        for (int y = c.getY() + 4; y >= c.getY() - 6; y--) {
            BlockPos f = new BlockPos(c.getX() + o[0], y, c.getZ() + o[1]);
            if (!level.isLoaded(f)) return null;
            BlockState here = level.getBlockState(f);
            if (!here.isAir() && !here.is(Blocks.LANTERN) && !here.is(Blocks.TORCH)) continue;
            if (!level.getBlockState(f.below()).isFaceSturdy(level, f.below(), Direction.UP)) continue;
            if (!level.canSeeSky(f)) return null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(f.relative(d)).getBlock() instanceof DoorBlock) return null;
            }
            return f.immutable();
        }
        return null;
    }

    /** The morning after: the lanterns taken in, one at a time, back into the stores. True while there is more to do. */
    static boolean takeIn(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        long[] when = new long[1];
        List<BlockPos> at = lit(id, when);
        if (at.isEmpty() || when[0] == day || t < 1000L || t >= 12000L) return false;
        for (BlockPos p : new ArrayList<>(at)) {
            BlockState s = level.getBlockState(p);
            Block b = s.getBlock();
            if (b != Blocks.LANTERN && b != Blocks.TORCH) {            // gone already (somebody took it): nothing to take in
                at.remove(p);
                lit(id, when[0], at);
                continue;
            }
            if (!TownJobs.atWork(level, v, "lanterns", p, "taking in the lanterns from the square")) return true;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            Crafts.store(level, v, new ItemStack(b == Blocks.LANTERN ? Items.LANTERN : Items.TORCH));
            at.remove(p);
            lit(id, when[0], at);
            return true;
        }
        return false;
    }

    /** Tests: every lantern the stores run to set out now, for the custom kept today; how many stand. */
    public static int lanternsForTests(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        Custom c = todays(v.id(), day, How.LANTERNS);
        if (c == null) return -1;
        for (int i = 0; i < RING.length + 1; i++) if (!setOut(level, v, c, day)) break;
        long[] when = new long[1];
        return lit(v.id(), when).size();
    }

    /** Tests: every lantern taken in now (it must be a later day); how many are still out. */
    public static int takeInForTests(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        for (int i = 0; i < RING.length + 1; i++) if (!takeIn(level, v, day, t)) break;
        long[] when = new long[1];
        return lit(v.id(), when).size();
    }

    // ------------------------------------------------------------------ the toast

    /**
     * A folk's part in the toast: to the tavern (the square, with none), and once the town is in, the eldest's
     * toast and a drink each. True while it is about it.
     */
    static boolean toast(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L, now = level.getGameTime();
        if (t < TOAST_FROM || t >= TOAST_TO || Assemblies.attending(f)) return false;
        Custom c = todays(v.id(), day, How.TOAST);
        if (c == null) return false;
        Toast toast = TOASTS.compute(v.id(), (k, was) -> was == null || was.day != day ? new Toast(day) : was);
        if (toast.given && now - toast.givenAt > 600L) return false;   // drunk, and back to its own evening
        Ledger.Building tav = Tavern.of(v.id());
        int h = f.getUUID().hashCode();
        BlockPos spot;
        if (tav != null) {
            Direction back = tav.facing(), right = back.getClockWise();
            spot = tav.anchor().relative(right, Math.floorMod(h, 4)).relative(back, Math.floorMod(h >> 3, 5) - 2);
        } else {
            BlockPos square = TownBell.crierSpot(v);
            double a = Math.floorMod(h, 360) * Math.PI / 180.0;
            spot = square.offset((int) Math.round(Math.cos(a) * 3), 0, (int) Math.round(Math.sin(a) * 3));
        }
        Culture.mark(f, Culture.Role.TOAST, (tav != null ? "at the tavern" : "on the square") + " for " + c.name());
        if (!Culture.arrive(f, spot, 1.6, 0.9)) return true;
        toast.there.add(f.getUUID());
        if (!toast.given) {
            int grown = 0;
            for (AssistantEntity a : Villages.folkOf(v.id())) if (!a.isBaby()) grown++;
            if (toast.there.size() >= Math.max(3, grown / 2) || t >= TOAST_FROM + 1000L && toast.there.size() >= 2) {
                give(level, v, c, toast, day);
            }
        } else if (now - toast.givenAt < 40L) {
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        return true;
    }

    /** The toast given by the eldest there, answered by all, and a drink each out of the stores. */
    private static void give(ServerLevel level, Villages.Village v, Custom c, Toast toast, long day) {
        List<VillageFolkEntity> there = new ArrayList<>();
        for (UUID u : toast.there) if (level.getEntity(u) instanceof VillageFolkEntity g && g.isAlive()) there.add(g);
        if (there.isEmpty()) return;
        there.sort((a, b) -> Integer.compare(b.ageYears(), a.ageYears()));
        VillageFolkEntity eldest = there.get(0);
        RandomSource r = level.getRandom();
        toast.given = true;
        toast.givenAt = level.getGameTime();
        toast.by = eldest.displayNameCap();
        FolkTalk.speak(eldest, "Friends! It is " + c.name() + ". Raise your cups: to " + c.toWhom() + "!");
        eldest.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        for (VillageFolkEntity g : there) {
            if (g != eldest) g.sayLater(FolkTalk.pick(r, "To " + c.toWhom() + "!", "Hear, hear!", "To " + c.name() + "!"), 20 + r.nextInt(40));
            ItemStack d = Tavern.pour(level, v, r);
            if (!d.isEmpty()) {
                String kind = Cafe.drinkOf(d);
                Cafe.Drink drink = kind == null ? null : Cafe.drinkFor(kind);
                if (drink != null) g.addEffect(new MobEffectInstance(drink.effect(), drink.ticks(), 0));
                Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));     // the bottle goes back
                toast.drinks++;
            }
            g.persona().remember(day, "we drank to " + c.toWhom() + " on " + c.name(), 2);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, g.getX(), g.getY() + 2.0, g.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
        }
        level.playSound(null, eldest.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        Villages.tell(v.id(), day, "the town kept " + c.name() + " with a toast" + (Tavern.of(v.id()) != null ? " at the tavern" : " on the square")
            + " to " + c.toWhom() + ", given by " + eldest.displayNameCap());
        kept(v.id(), c, day);
    }

    // ------------------------------------------------------------------ what the crier cries

    /** Is it a feast day in this town: the weekly feast, a celebration, an honouring, a wedding, Founding Day, the day of rest? */
    static boolean feastDay(UUID village, long day) {
        Gatherings.Kind k = Gatherings.tonight(village, day);
        if (k == Gatherings.Kind.FEAST || k == Gatherings.Kind.CELEBRATION || k == Gatherings.Kind.HONOUR || k == Gatherings.Kind.WEDDING) return true;
        return FoundingDay.today(village, day) || RestDay.today(village, day);
    }

    /**
     * What the crier cries of the town's customs and its motto (Crier.script, before its sign-off): a custom
     * kept today, one kept tomorrow, and on a feast day (or a custom's) the motto.
     */
    public static List<String> crierLines(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        for (Custom c : customs(id)) {
            if (today(id, c, day)) out.add("Today we keep " + c.name() + ": " + c.how().words + " " + c.how().when + ", for " + c.toWhom() + "!");
            else if (today(id, c, day + 1)) out.add("Tomorrow is " + c.name() + ": " + c.how().words + " " + c.how().when + ".");
        }
        String motto = Heraldry.motto(id);
        if (motto != null && (feastDay(id, day) || !out.isEmpty())) out.add("And remember our motto: " + motto + "!");
        CRIED.put(id, List.copyOf(out));
        return out;
    }

    /** Tests: what the crier was last given of the customs and the motto. */
    public static List<String> criedForTests(UUID village) {
        return CRIED.getOrDefault(village, List.of());
    }

    /** Tests: the great days looked over now. */
    public static List<Custom> adoptForTests(ServerLevel level, Villages.Village v) {
        adopt(level, v);
        return customs(v.id());
    }

    /** Tests: is this custom kept today? */
    public static boolean todayForTests(UUID village, Custom c, long day) {
        return today(village, c, day);
    }

    /** Tests: who kept today's silence, and whether the bell has tolled its end. */
    public static int silentForTests(UUID village) {
        Silence s = SILENCES.get(village);
        return s == null ? 0 : s.kept.size();
    }

    /** Tests: today's toast: {given, how many were there, drinks poured}. */
    public static int[] toastForTests(UUID village) {
        Toast t = TOASTS.get(village);
        return t == null ? new int[]{ 0, 0, 0 } : new int[]{ t.given ? 1 : 0, t.there.size(), t.drinks };
    }

    /**
     * Tests: today's toast given now, the town's grown folk all in (as if they had walked to the tavern): the eldest's
     * toast and a drink each out of the stores. False with no toast today.
     */
    public static boolean toastNowForTests(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        Custom c = todays(v.id(), day, How.TOAST);
        if (c == null) return false;
        Toast toast = TOASTS.compute(v.id(), (k, was) -> was == null || was.day != day ? new Toast(day) : was);
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && !f.isBaby()) toast.there.add(f.getUUID());
        if (!toast.given) give(level, v, c, toast, day);
        return toast.given;
    }

    /** Tests: the town's look at its customs now (the bell's toll, the lanterns, the storm's count). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    // ------------------------------------------------------------------ what the player reads

    /** The board's line: "Our customs: the Founders' Lanterns (day 29), Gate Night (today)". */
    @Nullable
    static String boardLine(UUID village, long day) {
        List<Custom> all = customs(village);
        if (all.isEmpty()) return null;
        List<String> bits = new ArrayList<>();
        for (Custom c : all) {
            long n = next(village, c, day);
            bits.add(Culture.capital(c.name()) + " (" + (today(village, c, day) ? "today: " + c.how().words : "day " + (n + 1)) + ")");
        }
        return "Our customs: " + String.join(", ", bits) + ".";
    }

    /** The Culture page's customs: each with why, how, to whom, when it began and when it is next kept. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        ListTag list = new ListTag();
        List<String> lines = new ArrayList<>();
        for (Custom c : customs(id)) {
            CompoundTag t = new CompoundTag();
            long n = next(id, c, day);
            t.putString("name", Culture.capital(c.name()));
            t.putString("why", c.why().words);
            t.putString("how", c.how().words + " " + c.how().when);
            t.putString("to", c.toWhom());
            t.putLong("began", c.day() + 1);
            t.putLong("next", n + 1);
            t.putBoolean("today", today(id, c, day));
            t.putLong("kept", c.kept() < 0 ? -1 : c.kept() + 1);
            list.add(t);
            lines.add(Culture.capital(c.name()) + ": " + c.how().words + " " + c.how().when + ", for " + c.toWhom()
                + (today(id, c, day) ? " — today!" : " — next on day " + (n + 1)));
        }
        out.put("list", list);
        out.put("lines", Culture.strings(lines));
        String s = SHORT.get(id);
        out.putString("short", s == null ? "" : s);
        return out;
    }
}
