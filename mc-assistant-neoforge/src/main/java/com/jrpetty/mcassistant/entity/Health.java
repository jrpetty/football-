package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [batchA] Health and care: a cold, and the town looking after its sick and its hurt.
 *
 * <ul>
 * <li><b>A cold.</b> A folk caught out in the rain or a thunderstorm with no roof over it for a good while
 *     (two minutes of it in a day), or worked to the bone (a long stretch at its work with no break, which a
 *     hard-driving leader or a harvest that would not wait can bring about), stands a small chance of
 *     catching a cold: one in eight for the wet, one in six for the work, the odds doubled on a poor diet,
 *     and no town catches more than four in a week. A town has to be settled first (three days founded,
 *     six folk), so a camp of founders in its first wet week is spared. It may pass to the folk it lives
 *     with, a small chance a day each. A folk with a cold works at half its pace (every clock its trade
 *     keeps, its building too), coughs and sneezes now and then and into what it says, spends the first
 *     quarter-day of it laid up in bed, and goes to bed early of an evening: at the infirmary if the town has
 *     one with a bed free, else in its own. It is over it in one to two days; the infirmary's beds see it off
 *     twice as fast, and the healer's care sooner still. A cold never kills anybody.</li>
 * <li><b>The hurt.</b> Once the town has its infirmary, a folk down to three fifths of its health and out of
 *     any fight walks there and lies in one of its beds, mending half a heart more every eight seconds than
 *     it would on its own (twice the pace), until it is nine tenths whole.</li>
 * <li><b>The healer.</b> The town's care is a works of its own (TownJobs "care", the brewer before anybody,
 *     else a hand the town can spare): one folk goes round the infirmary's patients, and visits the bedridden
 *     at home, about a minute apart, with a honey bottle, a golden carrot, the brewer's healing potion or a
 *     handful of sweet berries out of the stores (the potion first for a wound, the honey first for a cold);
 *     the bottle goes back. With none of those in the stores it sits with them a while all the same, and rest
 *     and company do a little good. The patient is the fonder of whoever looked after it.</li>
 * </ul>
 *
 * <p>A folk's state is kept on it and saved with it (VillageFolkEntity "Health"). Its card says how it is,
 * the town's books show who is ill (the Folk page), and the chronicle hears of it only when three or more
 * are down with it at once. Each folk's part runs from its own tick ({@link #hold}); the town's (the cold
 * passed round a house, the outbreak, the healer's round) every five seconds ({@link #tick}).
 */
public final class Health {

    private Health() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** How long a cold lasts, in ticks of its own: one to two days. */
    static final int COLD_LEAST = 24000, COLD_MOST = 48000;
    /** Out in the wet this long in a day, and its luck is tried once. */
    static final int EXPOSED = 2400;
    /** At its work this long without a rest (its break, the evening), and it is worn out. A working day
     *  has its break in it somewhere: only a break that never came makes a stretch this long. */
    static final int WORN_OUT = 10000;
    /** One in so many catch a cold: from the wet, from the work. */
    static final int WET_ODDS = 8, WORN_ODDS = 6;
    /** The most new colds a town catches in a week (seven days back from today). */
    static final int WEEKLY_MOST = 4;
    /** A housemate's chance a day of catching it from a folk with a cold under the same roof. */
    static final int SPREAD_ODDS = 12;
    /** A town catches colds only once it is settled: days since its founding, and folk. */
    static final int SETTLED_DAYS = 3, SETTLED_FOLK = 6;
    /** The pace of a folk with a cold, in percent of its own. */
    static final int ILL_PACE = 50;
    /** Laid up in bed for the first stretch of a cold (a quarter of a day). */
    static final long LAID_UP = 6000L;
    /** The evening: a folk with a cold goes to bed from now (after supper, an hour before the town), till the morning. */
    static final long EVENING = 13000L, MORNING = 1000L;
    /** Down to this share of its health it goes to the infirmary; it gets up at this share. */
    static final float WOUNDED = 0.6F, MENDED = 0.9F;
    /** A patient is seen to at most this often. */
    static final long CARE_EVERY = 1200L;
    /** What the care takes off a cold: a honey bottle or a golden carrot, berries or a potion, company alone. */
    static final int REMEDY = 6000, SOME = 3000, COMFORT = 1500;
    /** So many ill at once is an outbreak, and the chronicle hears of it. */
    static final int OUTBREAK = 3;

    // ------------------------------------------------------------------ one folk's health

    /** One folk's health: kept on it and saved with it. */
    public static final class State {
        /** Ticks of its cold still to run; nought when it is well. */
        int cold;
        /** The day it caught its last cold, and how. */
        long caughtDay = -100;
        String how = "";
        /** Laid up in bed till this game time (0: up and about), and why: "cold", "night", "wound". */
        long laidUntil;
        String laidFor = "";
        /** Who last saw to it, and with what. */
        String tendedBy = "", tendedWith = "";
        int tendedTimes;
        // Not saved: how its day has gone, and where it is lying.
        int wet;
        long wetDay = -1;
        int stretch;
        long triedDay = -1;
        boolean wounded;
        @Nullable BlockPos bed;
        boolean infirmary, lying;
        int walkTick = -1000, fails, beats;
        double best = Double.MAX_VALUE;
        long progress, tended;
        int mended;

        public boolean ill() { return cold > 0; }

        public int coldLeft() { return cold; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            if (cold > 0) t.putInt("Cold", cold);
            t.putLong("Caught", caughtDay);
            if (!how.isEmpty()) t.putString("How", how);
            if (laidUntil > 0) {
                t.putLong("LaidUntil", laidUntil);
                t.putString("LaidFor", laidFor);
            }
            if (!tendedBy.isEmpty()) {
                t.putString("TendedBy", tendedBy);
                t.putString("TendedWith", tendedWith);
            }
            if (tendedTimes > 0) t.putInt("Tended", tendedTimes);
            return t;
        }

        void load(CompoundTag t) {
            cold = t.getInt("Cold");
            caughtDay = t.contains("Caught") ? t.getLong("Caught") : -100;
            how = t.getString("How");
            laidUntil = t.getLong("LaidUntil");
            laidFor = t.getString("LaidFor");
            // A patient laid up for its wounds when the world was saved looks at them afresh; the bed it
            // was in is found again (nothing about where it lay is kept).
            wounded = laidFor.equals("wound");
            tendedBy = t.getString("TendedBy");
            tendedWith = t.getString("TendedWith");
            tendedTimes = t.getInt("Tended");
        }
    }

    /** VillageFolkEntity's save: its health, if there is anything to say about it. */
    public static void save(VillageFolkEntity f, CompoundTag tag) {
        State s = f.health();
        if (s.cold > 0 || s.caughtDay >= 0 || s.laidUntil > 0 || s.tendedTimes > 0) tag.put("Health", s.save());
    }

    public static void load(VillageFolkEntity f, CompoundTag tag) {
        if (tag.contains("Health")) f.health().load(tag.getCompound("Health"));
    }

    // ------------------------------------------------------------------ the tests' say

    /** Tests: colds in any town (true), in none (false), or as the town's own age and size have it (null). */
    private static volatile Boolean coldsForTests;
    /** Tests: one in so many catch it (0: the game's own odds). */
    private static volatile int oddsForTests;
    /** Tests: out in the rain (true), dry (false), or the sky's own (null). */
    private static volatile Boolean wetForTests;

    public static void coldsForTests(@Nullable Boolean on) { coldsForTests = on; }

    public static void oddsForTests(int oneIn) { oddsForTests = Math.max(0, oneIn); }

    public static void wetForTests(@Nullable Boolean on) { wetForTests = on; }

    /** Tests: the town's own round of the sick (the healer) held off, so a test sees each visit it makes itself. */
    private static volatile boolean roundsOffForTests;

    public static void roundsOffForTests(boolean off) { roundsOffForTests = off; }

    // ------------------------------------------------------------------ the town's books, and what was said

    /** The day's care in a town: patients seen to, what with, and the last of it in words. */
    static final class Book {
        long day = -1;
        int caught, recovered, tended, remedies, comforted, mended;
        String last = "";
    }

    private static final Map<UUID, Book> BOOKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SPREAD_LOOKED = new ConcurrentHashMap<>();
    /** The infirmary's beds, taken (by the bed's head) by a patient (lying in it or on its way). */
    private static final Map<Long, UUID> TAKEN = new ConcurrentHashMap<>();
    private static final Deque<String> SAID = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public static void resetForTests() {
        BOOKS.clear();
        TICKED.clear();
        SPREAD_LOOKED.clear();
        TAKEN.clear();
        SAID.clear();
        coldsForTests = null;
        oddsForTests = 0;
        wetForTests = null;
        roundsOffForTests = false;
    }

    static Book book(UUID village, long day) {
        Book b = BOOKS.computeIfAbsent(village, k -> new Book());
        if (b.day != day) {
            b.day = day;
            b.caught = b.recovered = b.tended = b.remedies = b.comforted = b.mended = 0;
        }
        return b;
    }

    /** Said out loud (FolkTalk), and kept for the tests. */
    static void say(VillageFolkEntity f, String text) {
        FolkTalk.speak(f, text);
        SAID.addLast(f.displayNameCap() + ": " + text);
        while (SAID.size() > 64) SAID.pollFirst();
    }

    public static List<String> saidForTests() {
        return new ArrayList<>(SAID);
    }

    // ------------------------------------------------------------------ what it does to a folk

    /** The pace of its work, in percent of its own: half, with a cold (AssistantEntity's clocks, VillageFolkEntity). */
    public static int pacePercent(VillageFolkEntity f) {
        State s = f.health();                            // (null while the folk is still being made)
        return s != null && s.cold > 0 && !f.isBaby() ? ILL_PACE : 100;
    }

    /** Laid up in bed just now (its work waits: VillageFolkEntity.onShift, calledAway)? */
    public static boolean laidUp(VillageFolkEntity f) {
        State s = f.health();                            // (null while the folk is still being made)
        return s != null && s.laidUntil > 0L && f.level().getGameTime() < s.laidUntil;
    }

    /**
     * Everything it says, now and then, with a cough in it, while it has a cold (FolkTalk.speak): about one
     * line in three.
     */
    public static String cough(VillageFolkEntity f, String text) {
        if (f.health().cold <= 0 || text == null || text.isBlank() || text.startsWith("*")) return text;
        RandomSource r = f.getRandom();
        if (r.nextInt(3) != 0) return text;
        return r.nextBoolean() ? FolkTalk.pick(r, "*cough* ", "*sniff* ", "*cough, cough* ") + text
            : text + FolkTalk.pick(r, " *cough*", " *sniff*", " *ahem* — sorry, this cold.");
    }

    public static String coughForTests(VillageFolkEntity f, String text) {
        for (int i = 0; i < 40; i++) {
            String said = cough(f, text);
            if (!said.equals(text)) return said;
        }
        return text;
    }

    /** How it feels (VillageFolkEntity.refreshMood): a cold puts it out of sorts. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        if (f.health().cold <= 0) return m;
        why.add(new Object[]{ "cold", 7 });
        return m - 6;
    }

    /** How it puts it, asked how it is (FolkTalk's mood words). */
    public static String moodWords(VillageFolkEntity f) {
        State s = f.health();
        if (s.cold <= 0) return "";
        return FolkTalk.pick(f.getRandom(), "I've a stinking cold. *sniff*", "This cold's got the better of me.",
            "Can't stop coughing. It's this cold.") + (s.tendedBy.isEmpty() ? "" : " " + s.tendedBy + " has been good to me.");
    }

    // ------------------------------------------------------------------ each folk's tick

    /**
     * From the folk's tick (VillageFolkEntity.aiStep): once a second its day is looked at (the wet, the work,
     * its cold running its course, its wounds); and while it is laid up, to its bed and kept there. True while
     * it is (its own day waits).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.ownerId() == null || f.isShowcase() || f.isHired() || !f.isAlive()) return false;
        State s = f.health();
        if (f.tickCount % 20 == 9) second(f, level, s);
        if (s.laidUntil <= 0L) {
            if (s.bed != null || s.lying) getUp(f, s);
            return false;
        }
        long now = level.getGameTime();
        if (now >= s.laidUntil || !layable(f)) {
            s.laidUntil = 0L;
            s.laidFor = "";
            getUp(f, s);
            return false;
        }
        return lieUp(f, level, s, now);
    }

    /** May it be laid up now: no fight on its hands, no bell ringing, not away from the town. */
    static boolean layable(VillageFolkEntity f) {
        if (f.getTarget() != null || Raids.underAlarm(f.ownerId())) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || JobSeekers.busy(f)) return false;
        return f.talkPartner() == null && f.companionPlayer() == null && f.guidePlayer() == null;
    }

    /** Once a second: the wet, the work, its luck, its cold, its wounds and the infirmary's mending. */
    static void second(VillageFolkEntity f, ServerLevel level, State s) {
        UUID village = f.ownerId();
        long now = level.getGameTime(), dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        // Out in the wet, and at its work without a rest.
        if (s.wetDay != day) {
            s.wetDay = day;
            s.wet = 0;
        }
        if (s.cold <= 0 && !f.isSleeping() && exposed(level, f)) s.wet += 20;
        // (Not the watch: its long hours, the day and then half the night, are what the watch is.)
        if (!f.isBaby() && !f.isSleeping() && f.stationTask() != StationTask.NONE && f.stationTask() != StationTask.GUARD
                && !f.offWorkNow()) s.stretch += 20;
        else s.stretch = 0;
        luck(level, f, s, village, day);
        // The cold runs its course: twice as fast in the infirmary's beds.
        if (s.cold > 0) {
            s.cold -= s.lying && s.infirmary && f.isSleeping() ? 40 : 20;
            if (s.cold <= 0) {
                recovered(level, f, s, day);
            } else {
                if (!f.isSleeping() && f.getRandom().nextInt(45) == 0) sneeze(level, f);
                // Of an evening, early to bed and there till the morning.
                if (t >= EVENING && s.laidUntil <= now && !(f.stationTask() == StationTask.GUARD && f.onWatch())) {
                    s.laidUntil = now + (24000L - t) + MORNING;
                    s.laidFor = "night";
                }
            }
        }
        // Its wounds: to the infirmary's beds once there are any, and up again when it is nearly whole.
        float hp = f.getHealth(), most = f.getMaxHealth();
        if (s.wounded) {
            if (hp >= most * MENDED || !layable(f)) {
                s.wounded = false;
                if (s.laidFor.equals("wound")) {
                    s.laidUntil = 0L;
                    s.laidFor = "";
                }
                if (hp >= most * MENDED && s.mended > 0 && !f.isSleeping()) {
                    say(f, FolkTalk.pick(f.getRandom(), "Good as new. Thank you, infirmary.", "Patched up and back on my feet."));
                }
            } else if (s.laidFor.equals("wound")) {
                s.laidUntil = Math.max(s.laidUntil, now + 400L);
            }
        } else if (hp < most * WOUNDED && layable(f) && f.hurtTime == 0 && f.tickCount - f.getLastHurtByMobTimestamp() > 100
                && !(f.stationTask() == StationTask.GUARD && f.onWatch()) && s.laidUntil <= now
                && Infirmary.freeBed(level, village, f) != null) {
            s.wounded = true;
            s.laidUntil = now + 400L;
            s.laidFor = "wound";
            s.mended = 0;
            if (f.peekJob() != null) f.clearQueue();
            say(f, FolkTalk.pick(f.getRandom(), "I'd best get this seen to at the infirmary.", "That's a nasty one. Infirmary for me.",
                "Ow. I'm going to have a lie down at the infirmary."));
        }
        // In one of the infirmary's beds it mends half a heart more every eight seconds: twice its own pace.
        if (s.lying && s.infirmary && f.isSleeping() && hp < most && ++s.beats % 8 == 0) {
            f.heal(1.0F);
            s.mended++;
            book(village, day).mended++;
        }
    }

    /** Its luck tried, once a day at the most, once it has been out in the wet or at its work too long. */
    static void luck(ServerLevel level, VillageFolkEntity f, State s, @Nullable UUID village, long day) {
        if (s.cold > 0 || s.triedDay == day || s.wet < EXPOSED && s.stretch < WORN_OUT || !coldsHere(village, day)) return;
        s.triedDay = day;
        boolean wet = s.wet >= EXPOSED;
        int odds = oddsForTests > 0 ? oddsForTests : wet ? WET_ODDS : WORN_ODDS;
        if (f.dietPercent() < 60) odds = Math.max(1, odds / 2);            // a body poorly fed takes a chill the easier
        odds = Perks.coldOdds(f, odds);                                      // [perks] a Frail folk takes one the easier
        if (f.getRandom().nextInt(odds) != 0 || caughtThisWeek(village, day) >= WEEKLY_MOST) return;
        catchCold(level, f, wet ? (Weather.stormy(level) ? "caught out in a thunderstorm" : "caught out in the rain")
            : "worn out at its work", day);
    }

    /** Out in the rain (or a thunderstorm) with nothing over its head? */
    static boolean exposed(ServerLevel level, VillageFolkEntity f) {
        Boolean w = wetForTests;
        if (w != null) return w;
        if (!level.isRaining()) return false;
        BlockPos head = BlockPos.containing(f.getX(), f.getEyeY(), f.getZ());
        return level.isRainingAt(head) || Weather.stormy(level) && !Weather.roofed(level, f.blockPosition()) && level.canSeeSky(head);
    }

    /** Does this town catch colds yet: settled (three days founded, six folk), or as the tests have it? */
    static boolean coldsHere(@Nullable UUID village, long day) {
        if (village == null) return false;
        Boolean on = coldsForTests;
        if (on != null) return on;
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(village);
        return founded >= 0 && day - founded >= SETTLED_DAYS && Villages.headcount(village) >= SETTLED_FOLK;
    }

    /** How many of the town's folk caught a cold in the last seven days. */
    static int caughtThisWeek(@Nullable UUID village, long day) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.health().caughtDay >= 0 && day - f.health().caughtDay < 7) n++;
        }
        return n;
    }

    /** It has caught a cold: a day or two of it, the first quarter of it in bed. */
    static void catchCold(ServerLevel level, VillageFolkEntity f, String how, long day) {
        State s = f.health();
        RandomSource r = f.getRandom();
        s.cold = COLD_LEAST + r.nextInt(COLD_MOST - COLD_LEAST + 1);
        s.cold = Math.max(1, Perks.coldLength(f, s.cold));                  // [perks] a Hardy folk shakes it off, a Traditionalist leader's remedies
        s.caughtDay = day;
        s.how = how;
        s.tended = 0L;
        s.tendedBy = "";
        s.tendedWith = "";
        if (!(f.stationTask() == StationTask.GUARD && f.onWatch())) {
            s.laidUntil = level.getGameTime() + LAID_UP;
            s.laidFor = "cold";
        }
        f.persona().remember(day, "I came down with a cold, " + how.replace("its work", "my work"), -2);
        if (!f.isSleeping()) {
            sneeze(level, f);
            say(f, FolkTalk.pick(r, "Achoo! Oh, no. I think I've caught a cold.", "*sniff* I've taken a chill. Bed for me, I think.",
                "My head's all stuffed up. I'm coming down with something."));
        }
        if (f.ownerId() != null) book(f.ownerId(), day).caught++;
        f.refreshMood();
        LOG.info("[MCA-HEALTH] {} of {} caught a cold ({}): {} ticks of it", f.displayNameCap(), Villages.name(f.ownerId()), how, s.cold);
    }

    /** Over it. */
    static void recovered(ServerLevel level, VillageFolkEntity f, State s, long day) {
        s.cold = 0;
        if (s.laidFor.equals("cold") || s.laidFor.equals("night")) {
            s.laidUntil = 0L;
            s.laidFor = "";
        }
        f.persona().remember(day, "I got over my cold" + (s.tendedBy.isEmpty() ? "" : ", with " + s.tendedBy + " looking after me"), 1);
        if (!f.isSleeping()) say(f, FolkTalk.pick(f.getRandom(), "That's better. I can breathe again!", "Cold's gone. Back to my old self.",
            "Feeling much better, thank you."));
        if (f.ownerId() != null) book(f.ownerId(), day).recovered++;
        f.refreshMood();
        LOG.info("[MCA-HEALTH] {} is over its cold", f.displayNameCap());
    }

    /** A sneeze or a cough, out loud. */
    static void sneeze(ServerLevel level, VillageFolkEntity f) {
        level.sendParticles(ParticleTypes.SNEEZE, f.getX(), f.getEyeY(), f.getZ(), 4, 0.2, 0.1, 0.2, 0.02);
        level.playSound(null, f.blockPosition(), SoundEvents.PANDA_SNEEZE, SoundSource.NEUTRAL, 0.35F, 1.5F);
        if (f.getRandom().nextInt(3) == 0) say(f, FolkTalk.pick(f.getRandom(), "*Cough, cough.*", "Achoo!", "*sniff*", "*Cough* Excuse me."));
    }

    // ------------------------------------------------------------------ laid up in bed

    /** To its bed (the infirmary's, else its own) and in it. True while it is laid up. */
    static boolean lieUp(VillageFolkEntity f, ServerLevel level, State s, long now) {
        if (s.bed == null || !isBed(level, s.bed)) {
            release(s, f);
            if (!chooseBed(level, f, s)) {
                // No bed to be had: up and about (at half pace, with a cold; a wound mends as it would anyway).
                s.laidUntil = 0L;
                s.laidFor = "";
                s.wounded = false;
                return false;
            }
            s.best = Double.MAX_VALUE;
            s.progress = now;
        }
        f.hobbyNow = laidWords(f, s);
        f.lastLeisureTick = f.tickCount;
        if (f.isSleeping()) {
            s.lying = true;
            // A night in bed is a night in bed, the infirmary's or its own (its spirits: Persona).
            long t = level.getDayTime() % 24000L;
            if (t >= EVENING || t < MORNING) f.persona().sleptInABed(level.getDayTime() / 24000L);
            return true;
        }
        s.lying = false;
        if (f.peekJob() != null) f.clearQueue();
        BlockPos bed = s.bed;
        double d = f.distanceToSqr(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
        if (d <= 2.6 * 2.6) {
            f.getNavigation().stop();
            BlockState st = level.getBlockState(bed);
            if (st.getBlock() instanceof BedBlock && st.getValue(BedBlock.OCCUPIED)) {
                release(s, f);                                   // somebody is in it: another, next look
                return true;
            }
            f.startSleeping(bed);
            s.lying = true;
            s.fails = 0;
            if (s.infirmary && f.getRandom().nextInt(2) == 0) {
                say(f, s.cold > 0 ? FolkTalk.pick(f.getRandom(), "A proper bed and a bit of quiet. Just what I need.", "*cough* I'll lie here a while.")
                    : FolkTalk.pick(f.getRandom(), "Ah. That's better.", "Somebody wake me when it stops hurting."));
            }
            return true;
        }
        if (d < s.best - 1.0) {
            s.best = d;
            s.progress = now;
        } else if (now - s.progress > 600L || now < s.progress) {
            // Could not get to it: another bed, or (twice beaten) up and about after all.
            release(s, f);
            s.progress = now;
            if (++s.fails >= 2) {
                s.fails = 0;
                s.laidUntil = 0L;
                s.laidFor = "";
                s.wounded = false;
                return false;
            }
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount - s.walkTick > 60 || f.tickCount < s.walkTick) {
            f.walkTo(bed, 0.8D);
            s.walkTick = f.tickCount;
        }
        return true;
    }

    /** What it is doing, for its card. */
    static String laidWords(VillageFolkEntity f, State s) {
        String where = s.infirmary ? "at the infirmary" : "at home";
        if (s.wounded) return (s.lying ? "laid up " : "on the way to bed ") + where + ", mending";
        return (s.lying ? "laid up in bed " : "off to bed ") + where + " with a cold";
    }

    /** A bed for it: one of the infirmary's, free; else (with a cold) its own. */
    static boolean chooseBed(ServerLevel level, VillageFolkEntity f, State s) {
        BlockPos spare = Infirmary.freeBed(level, f.ownerId(), f);
        if (spare != null) {
            s.bed = spare;
            s.infirmary = true;
            TAKEN.put(spare.asLong(), f.getUUID());
            return true;
        }
        if (s.cold <= 0) return false;
        BlockPos mine = f.bedPos() != null ? f.bedPos() : Homes.bedFor(level, f);    // its own bed, or its house's for it
        if (mine == null) return false;
        BlockPos own = head(level, mine);
        if (own == null) return false;
        s.bed = own;
        s.infirmary = false;
        return true;
    }

    /** The head of the bed one half of which is here, or null if it is no bed. */
    @Nullable
    static BlockPos head(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof BedBlock)) return null;
        return st.getValue(BedBlock.PART) == BedPart.HEAD ? pos : pos.relative(st.getValue(BedBlock.FACING));
    }

    static boolean isBed(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof BedBlock;
    }

    /** Is this bed of the infirmary's taken by some other patient still laid up in it (or on its way to it)? */
    static boolean takenByAnother(ServerLevel level, BlockPos head, @Nullable VillageFolkEntity f) {
        UUID who = TAKEN.get(head.asLong());
        if (who == null || f != null && who.equals(f.getUUID())) return false;
        if (level.getEntity(who) instanceof VillageFolkEntity o && o.isAlive() && head.equals(o.health().bed) && laidUp(o)) return true;
        TAKEN.remove(head.asLong(), who);
        return false;
    }

    private static void release(State s, VillageFolkEntity f) {
        if (s.bed != null) TAKEN.remove(s.bed.asLong(), f.getUUID());
        s.bed = null;
        s.lying = false;
    }

    /** Up out of bed: the laid-up spell over. */
    static void getUp(VillageFolkEntity f, State s) {
        if (s.lying && f.isSleeping()) f.stopSleeping();
        release(s, f);
        s.infirmary = false;
        s.wounded = s.wounded && s.laidFor.equals("wound");
    }

    // ------------------------------------------------------------------ the town's round

    /**
     * Every five seconds or so for each village (a folk's agenda): the cold passed round a house once a day,
     * an outbreak heard of, and the healer's round.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 100L && now >= last) return;
        TICKED.put(id, now);
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (t >= MORNING && SPREAD_LOOKED.getOrDefault(id, Long.MIN_VALUE) != day) {
            SPREAD_LOOKED.put(id, day);
            spread(level, v, day, SPREAD_ODDS);
        }
        outbreak(level, v, day);
        if (!Raids.underAlarm(id) && !roundsOffForTests) care(level, v, now, day);
    }

    /** Once a day: a cold passes, a small chance each, to whoever lives under the same roof. Returns how many caught it. */
    static int spread(ServerLevel level, Villages.Village v, long day, int oneIn) {
        UUID id = v.id();
        if (!coldsHere(id, day)) return 0;
        int caught = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.health().cold <= 0 || f.health().caughtDay >= day) continue;
            Homes.Home h = Homes.homeOf(id, f.getUUID());
            if (h == null) continue;
            for (VillageFolkEntity m : Homes.loadedMembers(id, h)) {
                if (m == f || !m.isAlive() || m.health().cold > 0 || m.health().caughtDay == day) continue;
                if (caughtThisWeek(id, day) >= WEEKLY_MOST) return caught;
                if (level.getRandom().nextInt(Math.max(1, oneIn)) != 0) continue;
                catchCold(level, m, "from " + f.displayNameCap() + " at home", day);
                caught++;
            }
        }
        return caught;
    }

    /** Three or more down with it at once: the chronicle hears of it, once an outbreak. */
    static void outbreak(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<String> ill = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && f.health().cold > 0) ill.add(f.displayNameCap());
        String told = Ledger.note(id, "health.outbreak");
        boolean noted = told != null && !told.isEmpty();
        if (ill.size() >= OUTBREAK && !noted) {
            Villages.tell(id, day, "a cold is going round: " + ill.size() + " are down with it (" + JobMarket.join(ill) + ")");
            Ledger.note(id, "health.outbreak", Long.toString(day));
            LOG.info("[MCA-HEALTH] an outbreak in {}: {}", Villages.name(id), ill);
        } else if (ill.isEmpty() && noted) {
            Ledger.forget(id, "health.outbreak");
        }
    }

    /** The patients laid up in bed (the infirmary's or their own), the neediest first. */
    static List<VillageFolkEntity> patients(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && f.health().lying && laidUp(f)) out.add(f);
        }
        out.sort((p, q) -> {
            float a = p.getHealth() / p.getMaxHealth(), b = q.getHealth() / q.getMaxHealth();
            if (Math.abs(a - b) > 0.05F) return Float.compare(a, b);
            return Integer.compare(q.health().cold, p.health().cold);
        });
        return out;
    }

    /**
     * The healer's round: the neediest patient not seen to in the last minute, by the town's care (TownJobs
     * "care": the brewer before anybody), at its bedside. Returns who was seen to, or null.
     */
    @Nullable
    static VillageFolkEntity care(ServerLevel level, Villages.Village v, long now, long day) {
        for (VillageFolkEntity p : patients(v.id())) {
            State s = p.health();
            if (now - s.tended < CARE_EVERY && now >= s.tended) continue;
            String what = s.infirmary ? "tending " + p.displayNameCap() + " at the infirmary"
                : "visiting " + p.displayNameCap() + ", laid up at home";
            if (!TownJobs.atWork(level, v, "care", p.blockPosition(), what, StationTask.BREW)) return null;
            tend(level, v, p, carer(level, v, p, what), day, now);
            return p;
        }
        return null;
    }

    /** Who is at the bedside: the town's care, else (the works done at once, as in the tests) the nearest well folk. */
    @Nullable
    static VillageFolkEntity carer(ServerLevel level, Villages.Village v, VillageFolkEntity patient, String what) {
        VillageFolkEntity near = null;
        double best = 10.0 * 10.0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity c) || c == patient || !c.isAlive()) continue;
            if (what.equals(TownJobs.doing(c))) return c;
            if (c.isBaby() || c.isSleeping() || c.health().cold > 0) continue;
            double d = c.distanceToSqr(patient);
            if (d < best) { best = d; near = c; }
        }
        return near;
    }

    /** What the healer gives, out of the stores. */
    record Remedy(String word, Predicate<ItemStack> what, int eases, float heals, boolean bottle) {}

    static final Remedy HONEY = new Remedy("a honey bottle", s -> s.is(Items.HONEY_BOTTLE), REMEDY, 3.0F, true);
    static final Remedy CARROT = new Remedy("a golden carrot", s -> s.is(Items.GOLDEN_CARROT), REMEDY, 6.0F, false);
    static final Remedy BERRIES = new Remedy("sweet berries", s -> s.is(Items.SWEET_BERRIES), SOME, 2.0F, false);
    static final Remedy POTION = new Remedy("a healing potion", Links::healing, SOME, 0.0F, true);

    /** At the bedside: a remedy out of the stores, or company alone. */
    static void tend(ServerLevel level, Villages.Village v, VillageFolkEntity p, @Nullable VillageFolkEntity carer, long day, long now) {
        State s = p.health();
        List<Remedy> order = s.wounded && s.cold <= 0 ? List.of(POTION, CARROT, HONEY, BERRIES) : List.of(HONEY, CARROT, BERRIES, POTION);
        Remedy kitchen = Kitchen.remedy(level, v, p, s.wounded && s.cold <= 0);      // [kitchen] a bandage for a wound, the healer's tea for a cold
        if (kitchen != null) order = java.util.stream.Stream.concat(java.util.stream.Stream.of(kitchen), order.stream()).toList();
        Remedy used = null;
        ItemStack got = ItemStack.EMPTY;
        for (Remedy r : order) {
            got = Crafts.takeOne(level, v, r.what());
            if (!got.isEmpty()) { used = r; break; }
        }
        String who = carer == null ? "the town's healer" : carer.displayNameCap();
        RandomSource r = p.getRandom();
        Book b = book(v.id(), day);
        int eased = used == null ? COMFORT : used.eases();
        if (used == null) {
            p.heal(1.0F);
            b.comforted++;
        } else {
            if (used == POTION) drink(p, got);
            else p.heal(used.heals());
            if (used.bottle()) Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));     // the bottle back
            b.remedies++;
            Kitchen.tended(level, v, p, used);                          // [kitchen] the tea's cold noted, the bandage's good
        }
        if (s.cold > 0) {
            s.cold -= eased;
            if (s.cold <= 0) recovered(level, p, s, day);
        }
        s.tended = now;
        s.tendedBy = who;
        s.tendedWith = used == null ? "rest and company" : used.word();
        s.tendedTimes++;
        b.tended++;
        b.last = who + " saw to " + p.displayNameCap() + (s.infirmary ? " at the infirmary" : " at home") + " with " + s.tendedWith;
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 0.8, p.getZ(), 5, 0.4, 0.3, 0.4, 0.0);
        if (carer != null) {
            carer.getLookControl().setLookAt(p, 30.0F, 30.0F);
            carer.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            p.life().feel(carer.getUUID(), who, 3);
            carer.life().feel(p.getUUID(), p.displayNameCap(), 2);
            p.persona().remember(day, who + " looked after me when I was laid up", 4);
            say(carer, used == null ? FolkTalk.pick(r, "Nothing in the stores to give you, but I'll sit a while. Rest's the thing.",
                    "I'll keep you company a bit. You'll mend.")
                : used == HONEY ? FolkTalk.pick(r, "Here — honey for that throat.", "A drop of honey, " + p.displayNameCap() + ". You'll be right as rain.")
                : used == POTION ? FolkTalk.pick(r, "Drink this. The brewer swears by it.", "One of the brewer's potions. Down in one.")
                : Kitchen.isRemedy(used) ? Kitchen.careWords(r, used, p)                               // [kitchen]
                : FolkTalk.pick(r, "Eat this — it'll do you good.", "Something to build you up, " + p.displayNameCap() + "."));
            if (!p.isSleeping() || p.health().lying) p.sayLater(FolkTalk.pick(r, "Thank you, " + who + ".", "*cough* You're kind.", "Bless you."), 40);
        }
        LOG.info("[MCA-HEALTH] {} saw to {} ({}) with {}: cold {} left, health {}/{}", who, p.displayNameCap(),
            s.infirmary ? "infirmary" : "home", s.tendedWith, s.cold, p.getHealth(), p.getMaxHealth());
    }

    /** The brewer's potion, drunk: its healing at once, or its effect. */
    static void drink(VillageFolkEntity f, ItemStack potion) {
        PotionContents pc = potion.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        if (pc.is(Potions.HEALING)) f.heal(4.0F);
        else if (pc.is(Potions.STRONG_HEALING)) f.heal(8.0F);
        for (MobEffectInstance e : pc.getAllEffects()) {
            if (!e.getEffect().value().isInstantenous()) f.addEffect(new MobEffectInstance(e));
        }
    }

    // ------------------------------------------------------------------ what the player reads

    /** Its card's line: its cold, where it lies and who has seen to it; or its wounds at the infirmary. Empty when well. */
    public static String cardLine(VillageFolkEntity f) {
        State s = f.health();
        if (f.ownerId() == null) return "";
        StringBuilder sb = new StringBuilder();
        if (s.cold > 0) {
            int hours = Math.max(1, Math.round(s.cold / 1000.0F));
            sb.append("Down with a cold since day ").append(s.caughtDay + 1).append(" (").append(s.how.replace("its work", "work"))
                .append(")").append(f.isBaby() ? "" : ": working at half pace").append("; better in about ")
                .append(hours >= 24 ? (hours + 12) / 24 + (hours >= 36 ? " days" : " day") : hours + (hours == 1 ? " hour" : " hours"));
        } else if (s.wounded || s.lying && laidUp(f)) {
            sb.append("Hurt: mending").append(s.lying && s.infirmary ? " in a bed at the infirmary" : "");
        }
        if (sb.length() == 0) return "";
        if (laidUp(f)) sb.append(". ").append(Character.toUpperCase(laidWords(f, s).charAt(0))).append(laidWords(f, s).substring(1));
        sb.append(" (").append(Math.round(f.getHealth())).append(" of ").append(Math.round(f.getMaxHealth())).append(" health)");
        if (!s.tendedBy.isEmpty()) sb.append("; seen to by ").append(s.tendedBy).append(", with ").append(s.tendedWith);
        return sb.append('.').toString();
    }

    /** One word for the books' Folk page: "a cold", "mending", or "". */
    public static String illWord(VillageFolkEntity f) {
        State s = f.health();
        if (s.cold > 0) return laidUp(f) && s.lying ? "a cold, in bed" : "a cold";
        if (s.wounded) return "mending";
        return "";
    }

    /** The town's care, for its books (Annals "care"): who is ill, the infirmary, the day's care, and the poor box. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        ListTag ill = new ListTag();
        int sick = 0, laid = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            String w = illWord(f);
            if (w.isEmpty()) continue;
            if (f.health().cold > 0) sick++;
            if (laidUp(f)) laid++;
            ill.add(StringTag.valueOf(f.displayNameCap() + "|" + w));
        }
        out.put("ill", ill);
        out.putInt("sick", sick);
        out.putInt("laid", laid);
        out.putInt("week", caughtThisWeek(id, day));
        Ledger.Building b = Infirmary.of(id);
        out.putBoolean("infirmary", b != null);
        if (b != null) {
            List<BlockPos> beds = Infirmary.beds(level, b);
            int taken = 0;
            for (BlockPos p : beds) if (takenByAnother(level, p, null)) taken++;
            out.putInt("beds", beds.size());
            out.putInt("beds_taken", taken);
        }
        Book k = book(id, day);
        out.putInt("tended", k.tended);
        out.putInt("remedies", k.remedies);
        out.putInt("comforted", k.comforted);
        out.putInt("caught", k.caught);
        out.putInt("recovered", k.recovered);
        out.putString("last", k.last);
        out.put("poorbox", PoorBox.report(id, day));
        out.put("neighbours", Neighbourly.report(id, day));
        return out;
    }

    /** The report in lines (/village care). */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        Ledger.Building b = Infirmary.of(id);
        out.add("CARE in " + Villages.name(id) + " on day " + (day + 1) + ": " + (b == null ? "no infirmary yet ("
            + Infirmary.why(id) + ")" : "the infirmary at " + b.anchor().toShortString() + ", " + Infirmary.beds(level, b).size() + " beds")
            + "; " + caughtThisWeek(id, day) + " colds caught this week (" + WEEKLY_MOST + " at most)"
            + (coldsHere(id, day) ? "" : "; too young a town to catch them yet"));
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            String c = cardLine(f);
            if (!c.isEmpty()) out.add("  " + f.displayNameCap() + ": " + c);
        }
        Book k = book(id, day);
        out.add("  Today: " + k.caught + " caught, " + k.recovered + " got over it, " + k.tended + " seen to (" + k.remedies
            + " with a remedy, " + k.comforted + " with company), " + k.mended + " half-hearts mended in the infirmary's beds"
            + (k.last.isEmpty() ? "" : "; last: " + k.last));
        out.addAll(PoorBox.lines(level, v));
        out.addAll(Neighbourly.lines(level, v));
        return out;
    }

    // ------------------------------------------------------------------ commands

    /** /village care: the town's sick, its infirmary, its poor box and its neighbourliness; and, for operators, a cold now, a stage. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("care")
            .executes(Health::cmdReport)
            // The nearest grown folk catches a cold now (operators; the pictures and the tests).
            .then(Commands.literal("cold").requires(src -> src.hasPermission(2)).executes(Health::cmdCold))
            // An infirmary set out where you stand, its beds full and the healer at a bedside (operators; the pictures).
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                List<String> views = Infirmary.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal("INFIRMARY | " + String.join(" | ", views)), false);
                return views.size();
            }));
    }

    @Nullable
    private static Villages.Village villageOf(CommandContext<CommandSourceStack> ctx) {
        return Villages.nearest(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
    }

    private static int cmdReport(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = villageOf(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("There's no village here."));
            return 0;
        }
        List<String> lines = lines(ctx.getSource().getLevel(), v);
        for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        return lines.size();
    }

    private static int cmdCold(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        VillageFolkEntity near = null;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(here).inflate(48.0),
                f -> f.isAlive() && !f.isBaby() && f.ownerId() != null && !f.isShowcase() && f.health().cold <= 0)) {
            if (near == null || f.distanceToSqr(here.getX(), here.getY(), here.getZ()) < near.distanceToSqr(here.getX(), here.getY(), here.getZ())) near = f;
        }
        if (near == null) {
            ctx.getSource().sendFailure(Component.literal("Nobody near enough to catch one."));
            return 0;
        }
        catchCold(level, near, "caught out in the rain", level.getDayTime() / 24000L);
        VillageFolkEntity f = near;
        ctx.getSource().sendSuccess(() -> Component.literal("COLD " + f.displayNameCap() + " " + f.blockPosition().getX() + " "
            + f.blockPosition().getY() + " " + f.blockPosition().getZ()), false);
        return 1;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: one second of its day, now. */
    public static void secondForTests(VillageFolkEntity f) {
        if (f.level() instanceof ServerLevel level) second(f, level, f.health());
    }

    /** Tests: it catches a cold now. */
    public static void catchForTests(VillageFolkEntity f, String how) {
        if (f.level() instanceof ServerLevel level) catchCold(level, f, how, level.getDayTime() / 24000L);
    }

    /** Tests: so many ticks of its cold gone by (it recovers if that is all of it). */
    public static void passForTests(VillageFolkEntity f, int ticks) {
        State s = f.health();
        if (s.cold <= 0 || !(f.level() instanceof ServerLevel level)) return;
        s.cold -= ticks;
        if (s.cold <= 0) recovered(level, f, s, level.getDayTime() / 24000L);
    }

    /** Tests: the cold passed round the houses now, one in so many. */
    public static int spreadForTests(ServerLevel level, UUID village, int oneIn) {
        Villages.Village v = Villages.get(village);
        return v == null ? 0 : spread(level, v, level.getDayTime() / 24000L, oneIn);
    }

    /** Tests: the outbreak looked at now. */
    public static void outbreakForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v != null) outbreak(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the healer's round now (the patient's last care forgotten). Returns who was seen to. */
    @Nullable
    public static VillageFolkEntity careForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        for (VillageFolkEntity p : patients(village)) p.health().tended = 0L;
        return care(level, v, level.getGameTime(), level.getDayTime() / 24000L);
    }

    /** Tests: laid up now, for so long, for this ("cold", "wound"). */
    public static void layUpForTests(VillageFolkEntity f, long ticks, String why) {
        State s = f.health();
        s.laidUntil = f.level().getGameTime() + ticks;
        s.laidFor = why;
        s.wounded = why.equals("wound");
    }

    public static boolean lyingForTests(VillageFolkEntity f) {
        return f.health().lying && f.isSleeping();
    }

    public static boolean inInfirmaryForTests(VillageFolkEntity f) {
        return f.health().infirmary;
    }

    @Nullable
    public static BlockPos bedForTests(VillageFolkEntity f) {
        return f.health().bed;
    }

    public static int mendedForTests(VillageFolkEntity f) {
        return f.health().mended;
    }

    public static String tendedForTests(VillageFolkEntity f) {
        return f.health().tendedBy + " / " + f.health().tendedWith;
    }

    public static int caughtThisWeekForTests(UUID village, long day) {
        return caughtThisWeek(village, day);
    }

    /** Tests: a fresh state read back from this one's save. */
    public static int[] roundTripForTests(VillageFolkEntity f) {
        State back = new State();
        back.load(f.health().save());
        return new int[]{ back.cold, (int) back.caughtDay, back.how.equals(f.health().how) ? 1 : 0,
            back.laidUntil == f.health().laidUntil ? 1 : 0 };
    }

    /** Tests: its luck tried now, on what its day has been (the wet, the work). */
    public static void luckForTests(VillageFolkEntity f) {
        if (f.level() instanceof ServerLevel level) luck(level, f, f.health(), f.ownerId(), level.getDayTime() / 24000L);
    }

    /** Tests: the day it caught its cold. */
    public static void caughtDayForTests(VillageFolkEntity f, long day) {
        f.health().caughtDay = day;
    }

    /** Tests: the stretch at its work set, as if it had been at it this long without a rest. */
    public static void workedForTests(VillageFolkEntity f, int ticks) {
        f.health().stretch = ticks;
    }

    /** Tests: today's luck not yet tried. */
    public static void untriedForTests(VillageFolkEntity f) {
        f.health().triedDay = -1;
    }

    public static void wetTicksForTests(VillageFolkEntity f, int ticks) {
        f.health().wetDay = f.level().getDayTime() / 24000L;
        f.health().wet = ticks;
    }
}
