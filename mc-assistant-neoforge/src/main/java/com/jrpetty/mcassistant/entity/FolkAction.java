package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * [anim] What a folk is doing with its body, for the client to animate: one number, sent only when it changes.
 *
 * <p>The server knows what each folk is really about (its trade, the block it stands at, the tool in its hand, the
 * gathering it is at, the player it is talking to, its bed, its boat, its ladder). Every quarter of a second this looks
 * at all of that and names one {@link Act}: a smith at its anvil is HAMMER, a farmer with seed in its hand over tilled
 * ground is PLANT, a courier on a run with its pack full is CARRY, a folk at the wedding when the vows are said CLAPs.
 * Things that happen once (a handshake at an interview, a cheer at a speech, a bite of bread, a bow to the leader, a
 * shrug at a request it will not do) are {@link #play played} for so many ticks over whatever it was doing.
 *
 * <p>Sent as one int (VillageFolkEntity.DATA_ACTION): the act, a count bumped each time an act starts again (so the
 * client restarts its keyframes), and a little picture over its head with its own count ({@link Mark}: an idea, a coin,
 * a question...). The client (client/FolkAnimations, FolkActionLayer, FolkEffects) does the rest from that number: the
 * keyframes, the tool in its hands, the sparks off the anvil. Nothing here is saved: a folk loaded from disk works out
 * what it is doing again on its first look.
 */
public final class FolkAction {

    private FolkAction() {}

    /** What its body is doing. The order is the wire's: add at the END only. */
    public enum Act {
        NONE(true),
        // ---- work, by trade
        HAMMER(true), CHOP(true), PICK(true), DIG(true), HOE(true), PLANT(true), HARVEST(true), FISH(true), STIR_POT(true),
        KNEAD(true), BREW(true), SEW(true), SAW(true), CHISEL(true), BUILD(true), SWEEP(true), CARRY(true), SACK(true),
        HAND_OVER(true), COUNT_COINS(true), WRITE(true), TEACH(true), TEND(true), PREACH(true), WATCH(true), MILK(true),
        SHEAR(true), LEAD(true), FEED(true), SMOKE_HIVE(true), FLETCH(true), ENCHANT(true), STOKE(true), SPYGLASS(true),
        POUR(true), SERVE(true), MAP(true), TINKER(true), FUSE(true), STOCK(true), WATER(true), REPAIR(true), WIPE(true),
        // ---- life
        EAT(true), DRINK(true), SIT(true), SLEEP(true), TALK(true), LISTEN(true), HANDSHAKE(false), CHEER(false), CLAP(true),
        DANCE(true), MOURN(true), PRAY(true), LUTE(true), DRUM(true), TAG(true), HOPSCOTCH(true), CLIMB(true),
        OPEN_GATE(false), ROW(true), RIDE(true), SWIM(true), BLOCK(true), THRUST(true), FALL(false), WAVE(false),
        BOW(false), SHRUG(false), SALUTE(false), POINT(false), BECKON(false), THINK(true), KNEEL(true), QUARREL(true),
        PROTEST(true), READ(true), SIT_GROUND(true), LOST(true);

        /** Played round and round while it lasts (a hammer's beat), or once through (a bow). */
        public final boolean loop;

        Act(boolean loop) { this.loop = loop; }

        private static final Act[] ALL = values();

        public static Act of(int id) { return id >= 0 && id < ALL.length ? ALL[id] : NONE; }

        /** By its name as a command types it ("stir_pot", "handshake"), or null. */
        @Nullable
        public static Act named(String name) {
            String n = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Act a : ALL) if (a.name().equals(n)) return a;
            return null;
        }

        public String word() { return name().toLowerCase(Locale.ROOT); }

        public boolean work() { return ordinal() >= HAMMER.ordinal() && ordinal() <= WIPE.ordinal(); }
    }

    /** The little picture over its head when something happens. The order is the wire's: add at the END only. */
    public enum Mark {
        NONE, IDEA, COIN, QUESTION, EXCLAIM, HEART, NOTE, ANGER, TEAR, STAR, SWEAT, HANDSHAKE;

        private static final Mark[] ALL = values();

        public static Mark of(int id) { return id >= 0 && id < ALL.length ? ALL[id] : NONE; }

        @Nullable
        public static Mark named(String name) {
            for (Mark m : ALL) if (m.name().equalsIgnoreCase(name.trim())) return m;
            return null;
        }
    }

    // ------------------------------------------------------------------ the wire

    public static int pack(int act, int seq, int mark, int markSeq) {
        return act & 0xFF | (seq & 0xFF) << 8 | (mark & 0xFF) << 16 | (markSeq & 0xFF) << 24;
    }

    public static int actOf(int packed) { return packed & 0xFF; }
    public static int seqOf(int packed) { return packed >>> 8 & 0xFF; }
    public static int markOf(int packed) { return packed >>> 16 & 0xFF; }
    public static int markSeqOf(int packed) { return packed >>> 24 & 0xFF; }

    /** How long a mark shows over a head (ticks), on the client. */
    public static final int MARK_TICKS = 50;

    // ------------------------------------------------------------------ each folk's

    /** What the server keeps for each folk (VillageFolkEntity.animState): never saved. */
    public static final class State {
        int act;
        int seq;
        /** A played act lasts until this tick; 0 while the act is worked out from what it is doing. */
        int until;
        int mark;
        int markSeq;
        /** The last time it swung its arm at something (VillageFolkEntity.swing): its hands at work. */
        int swung = -1000;
        /** The last time it said something out loud (FolkTalk.speak). */
        int spoke = -1000;
        /** The last time it ate or drank. */
        int ate = -1000;
        boolean drank;
        ItemStack food = ItemStack.EMPTY;
        /** The block it works at, found by looking round where it stands (worked out again only when it moves). */
        @Nullable BlockPos station;
        @Nullable Block stationBlock;
        long scannedAt = Long.MIN_VALUE;
        /** How many times the act has been sent (for the tests: only on a change). */
        int sends;
        boolean wasOnGround = true;
        float fell;
        /** Its hobbyNow as last read, and the act it read as (worked out again only when the words change). */
        @Nullable String hobbyRead;
        Act hobbyAct = Act.NONE;
    }

    /**
     * The client's side of it (VillageFolkEntity.animSeen): the act it is shown doing and when it began, the act before it
     * and when that began (the two are blended for a few ticks, so nothing snaps), and the mark over its head. Kept by
     * the entity; written from onSyncedDataUpdated, read by the model every frame. Plain numbers, nothing made a frame.
     */
    public static final class Seen {
        public int act, prev;
        /** Its own clock (tickCount) when the act began, the one before began, and when the change came. */
        public int start, prevStart, changed = -1000;
        public int mark, markAt = -1000;
        private int seq = -1, markSeq = -1;
        /** The effects' own (client/FolkEffects): the tick they last ran, where in its clip it was, and for which act. */
        public int fxTick = -1, fxAct;
        /** Drawn this frame in one picture with its outfit, its dye and its finery baked in (client/FolkFaces.dressed). */
        public boolean baked;
        public float fxSec = -1.0F;
        /** The game's own clock for a keyframe animation, started at each new act (as the warden's and the camel's are). */
        public final net.minecraft.world.entity.AnimationState state = new net.minecraft.world.entity.AnimationState();
        /** [vision27] Its walk (client/FolkGait): where in its stride, how far the legs swing, how much it walks and runs,
         *  its smoothed speed (blocks a second), and where and when it was last drawn. */
        public float gaitPhase, gaitAmp, gaitWeight, gaitRun, gaitSpeed, gaitAge = -1.0F;
        public double gaitX, gaitZ;
        /** [vision27] Its pose's change (0 standing, 1 sat, 2 crouched): from what, to what, and when (client/FolkGait.settle). */
        public int poseKind, poseFrom;
        public float poseAt = -1000.0F;

        /** A player's emote begun (or 0: over), on its own clock (client/PlayerEmotesClient). */
        public void begin(int a, int tick) {
            prev = act;
            prevStart = start;
            changed = tick;
            act = a;
            start = tick;
            state.start(tick);
        }

        public void heard(int packed, int tick) {
            int a = actOf(packed), q = seqOf(packed);
            if (q != seq) {
                if (seq >= 0 || a != 0) {
                    prev = act;
                    prevStart = start;
                    changed = tick;
                    act = a;
                    start = tick;
                    state.start(tick);
                }
                seq = q;
            }
            int m = markOf(packed), mq = markSeqOf(packed);
            if (mq != markSeq) {
                if (markSeq >= 0 && m != 0) {
                    mark = m;
                    markAt = tick;
                }
                markSeq = mq;
            }
        }
    }

    /** A rough tick for each folk, so a hundred of them do not all look at once. */
    private static final int EVERY = 5;

    /** Its hands are at its work while it swung within this many ticks. */
    private static final int SWING_FRESH = 60;

    // ------------------------------------------------------------------ the server's side

    /** From VillageFolkEntity.aiStep, server side, every tick (cheap: most ticks it does nothing). */
    public static void tick(VillageFolkEntity f) {
        if (!measuring) {
            tick0(f);
            return;
        }
        long t0 = System.nanoTime();
        tick0(f);
        long d = System.nanoTime() - t0;
        measuredNs += d;
        measuredTicks++;
        if (d > measuredMax) measuredMax = d;
    }

    private static void tick0(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return;
        State s = f.animState();
        fallen(f, s);
        if ((f.tickCount + f.getId()) % EVERY != 0) return;
        if (s.until != 0 && f.tickCount < s.until) return;   // a played act runs its course
        s.until = 0;
        if (measuring) measuredDerives++;
        Act now = Guard_derive(f, level, s);
        set(f, s, now, false);
    }

    /** What the work-out costs, measured while /village anim perf is on: time, folk-ticks, work-outs, the longest. */
    public static boolean measuring;
    public static long measuredNs, measuredTicks, measuredDerives, measuredMax;

    /** A hard fall (more than four blocks), landed: down, and up again. */
    private static void fallen(VillageFolkEntity f, State s) {
        boolean ground = f.onGround();
        if (!ground) s.fell = Math.max(s.fell, f.fallDistance);
        if (ground && !s.wasOnGround && s.fell > 4.0F && !f.isInWater() && f.getVehicle() == null) {
            play(f, Act.FALL, 50);
        }
        if (ground) s.fell = 0.0F;
        s.wasOnGround = ground;
    }

    private static Act Guard_derive(VillageFolkEntity f, ServerLevel level, State s) {
        try {
            return derive(f, level, s);
        } catch (RuntimeException e) {
            return Act.NONE;                                   // a bug in a look never stops a folk's tick
        }
    }

    /** An act for so many ticks over whatever it was doing (a handshake, a cheer, a bow), sent at once. */
    public static void play(VillageFolkEntity f, Act act, int ticks) {
        if (f.level().isClientSide) return;
        State s = f.animState();
        s.until = f.tickCount + Math.max(1, ticks);
        set(f, s, act, true);
    }

    /** A request turned down: a shrug, and the words it says (for a return line: {@code return FolkAction.shrug(f, "...")}). */
    public static String shrug(VillageFolkEntity f, String words) {
        play(f, Act.SHRUG, 30);
        return words;
    }

    /** A played act over now: back to what it is doing. */
    public static void stop(VillageFolkEntity f) {
        State s = f.animState();
        s.until = 0;
        if (f.level() instanceof ServerLevel level) set(f, s, derive(f, level, s), false);
    }

    /** A little picture over its head (an idea, a coin, a question), sent at once. */
    public static void mark(VillageFolkEntity f, Mark mark) {
        if (f.level().isClientSide || mark == Mark.NONE) return;
        State s = f.animState();
        s.mark = mark.ordinal();
        s.markSeq = (s.markSeq + 1) & 0xFF;
        send(f, s);
    }

    /** Its arm swung at something: from VillageFolkEntity.swing. */
    public static void swung(VillageFolkEntity f) {
        if (!f.level().isClientSide) f.animState().swung = f.tickCount;
    }

    /** It said something out loud: from FolkTalk.speak. */
    public static void spoke(VillageFolkEntity f) {
        f.animState().spoke = f.tickCount;
    }

    /** It ate (or drank): from VillageFolkEntity.ateFood and the meals. A bite to the mouth, and crumbs. */
    public static void ate(VillageFolkEntity f, ItemStack food) {
        ate(f, food, isDrink(food));
    }

    public static void ate(VillageFolkEntity f, ItemStack food, boolean drink) {
        if (f.level().isClientSide) return;
        State s = f.animState();
        s.ate = f.tickCount;
        s.drank = drink;
        s.food = food.isEmpty() ? ItemStack.EMPTY : food.copyWithCount(1);
        play(f, s.drank ? Act.DRINK : Act.EAT, 60);
    }

    static boolean isDrink(ItemStack s) {
        Item i = s.getItem();
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath();
        return i == Items.POTION || i == Items.MILK_BUCKET || i == Items.HONEY_BOTTLE || id.contains("cider") || id.contains("mead")
            || id.contains("tea") || id.contains("stout") || id.contains("cup") || id.contains("ale") || id.contains("wine");
    }

    private static void set(VillageFolkEntity f, State s, Act act, boolean restart) {
        int id = act.ordinal();
        if (id == s.act && !restart) return;
        if (act == Act.LOST) { s.mark = Mark.QUESTION.ordinal(); s.markSeq = (s.markSeq + 1) & 0xFF; }    // lost: a question mark
        else if (act == Act.MOURN) { s.mark = Mark.TEAR.ordinal(); s.markSeq = (s.markSeq + 1) & 0xFF; }  // at the grave: a tear
        else if (act == Act.LUTE || act == Act.DRUM) { s.mark = Mark.NOTE.ordinal(); s.markSeq = (s.markSeq + 1) & 0xFF; }
        else if ((act == Act.THRUST || act == Act.BLOCK) && s.act != Act.THRUST.ordinal() && s.act != Act.BLOCK.ordinal()) {
            s.mark = Mark.EXCLAIM.ordinal();                           // a foe seen: a shout
            s.markSeq = (s.markSeq + 1) & 0xFF;
        } else if (act.work() && !restart && Manner.gaitOf(f.clientManner()) == Manner.TIRED && f.getRandom().nextInt(3) == 0) {
            s.mark = Mark.SWEAT.ordinal();                             // tired at its work: a bead of sweat
            s.markSeq = (s.markSeq + 1) & 0xFF;
        }
        s.act = id;
        s.seq = (s.seq + 1) & 0xFF;
        send(f, s);
    }

    private static void send(VillageFolkEntity f, State s) {
        int packed = pack(s.act, s.seq, s.mark, s.markSeq);
        if (f.showAction(packed)) s.sends++;
    }

    /** What it is doing just now (the server's word), for the tests and /village anim. */
    public static Act now(VillageFolkEntity f) {
        return Act.of(f.animState().act);
    }

    /** How many times its act has gone to the client (for the tests: only on a change). */
    public static int sendsForTests(VillageFolkEntity f) {
        return f.animState().sends;
    }

    /** For the tests: worked out now, whatever the tick. */
    public static Act deriveForTests(VillageFolkEntity f) {
        State s = f.animState();
        s.until = 0;
        s.scannedAt = Long.MIN_VALUE;                          // the blocks round it looked at afresh
        Act a = f.level() instanceof ServerLevel level ? derive(f, level, s) : Act.NONE;
        set(f, s, a, false);
        return a;
    }

    // ------------------------------------------------------------------ what it is doing

    static Act derive(VillageFolkEntity f, ServerLevel level, State s) {
        if (f.isSleeping()) return Act.SLEEP;
        Entity ride = f.getVehicle();
        if (ride instanceof net.minecraft.world.entity.vehicle.Boat) return Act.ROW;
        if (ride instanceof net.minecraft.world.entity.animal.horse.AbstractHorse || ride instanceof net.minecraft.world.entity.animal.Animal) return Act.RIDE;
        if (ride != null) return Act.SIT;
        if (f.isShowcase()) return Act.NONE;                    // stood up to be looked at: only what it is told to do
        if (f.onClimbable() && !f.onGround()) return Act.CLIMB;
        if (f.isInWater() && !f.onGround() || f.isSwimming()) return Act.SWIM;
        Pose pose = f.getPose();
        boolean still = f.getNavigation().isDone() && f.getDeltaMovement().horizontalDistanceSqr() < 0.002;
        int t = f.tickCount;
        // A fight: the watch's stance, the shield up or the blade ready.
        if (f.getTarget() != null && f.getTarget().isAlive() && f.distanceToSqr(f.getTarget()) < 36.0) {
            return f.getOffhandItem().is(Items.SHIELD) && f.getHealth() < f.getMaxHealth() * 0.6F ? Act.BLOCK : Act.THRUST;
        }
        boolean talking = f.talkPartner() != null || t - s.spoke < 50;
        if (pose == Pose.SITTING) {
            if (t - s.ate < 200) return s.drank ? Act.DRINK : Act.EAT;
            if (talking) return Act.TALK;
            Act hob = hobby(f, s);
            if (hob == Act.EAT || hob == Act.DRINK || hob == Act.READ || hob == Act.LUTE || hob == Act.WRITE) return hob;
            return Act.SIT;
        }
        if (pose == Pose.CROUCHING) return Act.NONE;            // hiding (hide-and-seek): the model crouches it
        // A gathering: the cheer, the hush, the dance, the vigil, the preaching.
        Act gathered = Assemblies.animOf(f);
        if (gathered != Act.NONE) return gathered;
        net.minecraft.world.entity.player.Player partner = f.talkPartner();
        if (partner != null) {
            f.getLookControl().setLookAt(partner, 30.0F, 30.0F);   // its eyes on whoever talks to it
            return t - s.spoke < 50 ? Act.TALK : Act.LISTEN;
        }
        if (t - s.spoke < 50 && still) return Act.TALK;
        // Mourning at a grave: the grief of the last day, knelt where the dead lie.
        if (still && level.getDayTime() / 24000L - f.griefDay <= 1 && nearGrave(f, level)) return Act.MOURN;
        // An instrument in its hands (the buskers' lute, a note block for a drum): played.
        Item held = f.getMainHandItem().getItem();
        if (still && held == com.jrpetty.mcassistant.item.LeisureItems.LUTE.get()) return Act.LUTE;
        if (still && held == Items.NOTE_BLOCK && f.offWorkNow()) return Act.DRUM;
        // [life28] On the town's work, at the spot: at it with its hands between the pieces the town lays (TownJobs).
        if (still && Busywork.ENABLED && TownJobs.busy(f)) {
            Act tw = townWork(TownJobs.doing(f));
            if (tw != Act.NONE) return tw;
        }
        Act busy = Busywork.act(f, still);                       // [life28] filling its time (Busywork)
        if (busy != Act.NONE) return busy;
        // Its own time: what it does for itself (hobbyNow's words).
        Act hob = hobby(f, s);
        if (hob != Act.NONE && (still || hob == Act.TAG || hob == Act.SACK || hob == Act.CARRY || hob == Act.DANCE)) return hob;
        if (f.isBaby()) return still && (f.getId() + level.getDayTime() / 1200L) % 5 == 0 ? Act.HOPSCOTCH : Act.NONE;
        // Its work.
        Act carried = carrying(f);
        if (carried != Act.NONE && !still) return carried;
        if (!f.offWorkNow() && still) {
            Act w = work(f, level, s);
            if (w != Act.NONE) return w;
            // [life28] Down the town's works on its shift, stood at the face between cuts: at it with the pick.
            if (Busywork.ENABLED && com.jrpetty.mcassistant.entity.goal.WorksGoal.ours(f, f.peekJob())) return Act.PICK;
        }
        if (carried != Act.NONE) return carried;
        if (f.stationTask() == StationTask.GUARD && f.onShift() && still) return Act.WATCH;
        if (still && f.clientStatus().startsWith("Needs") && (t / 200 + f.getId()) % 3 == 0) return Act.THINK;
        if (still && f.quarrelledOn() == level.getDayTime() / 24000L && (t / 300 + f.getId()) % 4 == 0) return Act.QUARREL;
        // [life28] Stood by somebody in its own time (keeping company, waiting at the stores): passing the time of day, by turns.
        if (still && Busywork.ENABLED && f.offWorkNow() && !f.isBaby()) {
            VillageFolkEntity by = company(f, level);
            if (by != null) {
                f.getLookControl().setLookAt(by, 30.0F, 30.0F);
                return ((t / 70) + (f.getId() < by.getId() ? 0 : 1)) % 2 == 0 ? Act.TALK : Act.LISTEN;
            }
        }
        return Act.NONE;
    }

    /** [life28] The act of a piece of the town's work, by its words ("levelling the ground": DIG). */
    static Act townWork(@Nullable String what) {
        if (what == null) return Act.NONE;
        String w = what.toLowerCase(Locale.ROOT);
        if (w.contains("level") || w.contains("dig") || w.contains("grave") || w.contains("trench") || w.contains("ditch")) return Act.DIG;
        if (w.contains("clearing") || w.contains("sweep") || w.contains("spill") || w.contains("tidy")) return Act.SWEEP;
        if (w.contains("garden") || w.contains("green") || w.contains("plant") || w.contains("hedge") || w.contains("flower")) return Act.PLANT;
        if (w.contains("making") || w.contains("carv") || w.contains("at the bench")) return Act.SAW;
        if (w.contains("lamp") || w.contains("light")) return Act.REPAIR;
        return Act.BUILD;
    }

    /** [life28] A grown folk of its town stood still within a couple of steps of it, or null. */
    @Nullable
    private static VillageFolkEntity company(VillageFolkEntity f, ServerLevel level) {
        java.util.UUID town = f.ownerId();
        if (town == null) return null;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(2.2, 1.0, 2.2))) {
            if (o == f || !o.isAlive() || o.isBaby() || o.isSleeping() || !town.equals(o.ownerId())) continue;
            if (!o.getNavigation().isDone()) continue;
            return o;
        }
        return null;
    }

    /** A courier on a run with its goods, a shopper carrying its shopping home: a crate in its arms, a sack on its back. */
    static Act carrying(VillageFolkEntity f) {
        boolean haul = f.stationTask() == StationTask.HAUL && Couriers.onARun(f);
        int stacks = 0;
        boolean crate = false;
        for (ItemStack st : f.getInventoryItems()) {
            if (st.isEmpty()) continue;
            stacks++;
            if (Crates.isCrate(st) && !net.minecraft.world.item.component.ItemContainerContents.EMPTY.equals(
                st.getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY))) crate = true;
        }
        if (haul && (crate || stacks >= 6)) return Act.CARRY;
        if (haul && stacks >= 2) return Act.SACK;
        return Act.NONE;
    }

    /** Its own time, by what it is doing for itself (VillageFolkEntity.hobbyNow's words), read again only when they change. */
    static Act hobby(VillageFolkEntity f, State s) {
        String h = f.hobbyNow();
        if (h == null) {
            s.hobbyRead = null;
            s.hobbyAct = Act.NONE;
            return Act.NONE;
        }
        if (h != s.hobbyRead) {
            s.hobbyRead = h;
            s.hobbyAct = hobbyAct(h);
        }
        return s.hobbyAct;
    }

    /** The act a folk's own time reads as, by its words ("dancing round the maypole": DANCE). */
    public static Act hobbyAct(String h) {
        String w = h.toLowerCase(Locale.ROOT);
        if (w.startsWith("on the way") || w.startsWith("on its way") || w.startsWith("going") || w.startsWith("off to")
            || w.startsWith("making for") || w.startsWith("on the road")) {
            return w.contains("carrying") ? Act.SACK : Act.NONE;
        }
        if (w.contains("danc")) return Act.DANCE;
        if (w.contains("playing tag") || w.contains("tagging")) return Act.TAG;
        if (w.contains("teaching")) return Act.TEACH;
        if (w.contains("crying the news") || w.contains("holding court")) return Act.PREACH;
        if (w.contains("at the midday meal") || w.contains("at supper") || w.contains("supper with")) return Act.EAT;
        if (w.contains("at the tavern")) return Act.DRINK;
        if (w.contains("reading") || w.contains("going over") || w.contains("at the scholars")) return Act.READ;
        if (w.contains("fishing")) return Act.FISH;
        if (w.contains("baking")) return Act.KNEAD;
        if (w.contains("fireworks")) return Act.FUSE;
        if (w.contains("keeping watch") || w.contains("on picket") || w.contains("fire watch")) return Act.WATCH;
        if (w.contains("binding its wounds")) return Act.TEND;
        if (w.contains("snowman")) return Act.BUILD;
        if (w.contains("crafting table")) return Act.SAW;
        if (w.contains("lost in")) return Act.LOST;
        if (w.contains("on strike") || w.contains("protest")) return Act.PROTEST;
        if (w.contains("carrying") || w.contains("taking its") || w.contains("taking the") && w.contains("haul")) return Act.SACK;
        if (w.contains("looking round") || w.contains("the notices")) return Act.THINK;
        if (w.contains("lute") || w.contains("music") || w.contains("singing") || w.contains("busking")) return Act.LUTE;
        if (w.contains("drum")) return Act.DRUM;
        if (w.contains("pray") || w.contains("chapel") || w.contains("the rite")) return Act.PRAY;
        if (w.contains("watching") && (w.contains("match") || w.contains("race") || w.contains("game"))) return Act.CHEER;
        return Act.NONE;
    }

    // ------------------------------------------------------------------ at its work

    /**
     * At its work, standing still on its shift: by its trade, the block it stands at and the tool in its hand. A trade
     * that works with its hands at a block (the anvil, the loom, the stove) is at it while it stands there; one that
     * works out in the open (the fields, the woods, the pit) while its arm has swung at something lately.
     */
    static Act work(VillageFolkEntity f, ServerLevel level, State s) {
        boolean swinging = f.tickCount - s.swung < SWING_FRESH;
        if (Sweepers.sweeping(f)) return Act.SWEEP;
        if (School.teaching(f)) return Act.TEACH;
        ItemStack hand = f.getMainHandItem();
        Block at = station(f, level, s);
        StationTask job = f.stationTask();
        // Roles over the trade: the builders laying blocks, the healer at a bedside.
        if (swinging && hand.getItem() instanceof BlockItem bi && job != StationTask.FARM && job != StationTask.WOOD
            && !(bi.getBlock() instanceof net.minecraft.world.level.block.CropBlock)) {
            face(f, s);
            return Act.BUILD;
        }
        Act byBlock = at == null ? Act.NONE : byBlock(f, at, hand);
        Act a = switch (job) {
            case FARM -> !swinging ? Act.NONE : hand.is(ItemTags.HOES) ? Act.HOE
                : hand.getItem() == com.jrpetty.mcassistant.item.FieldItems.WATERING_CAN.get() ? Act.WATER
                : plantable(hand) ? Act.PLANT : Act.HARVEST;
            case WOOD -> !swinging ? Act.NONE : hand.is(ItemTags.SAPLINGS) ? Act.PLANT : Act.CHOP;
            case MINE, CAVE, NETHER -> !swinging ? Act.NONE : hand.is(ItemTags.SHOVELS) ? Act.DIG : Act.PICK;
            case RANCH -> !swinging ? Act.NONE : hand.is(Items.SHEARS) ? Act.SHEAR : hand.is(Items.BUCKET) || hand.is(Items.MILK_BUCKET) ? Act.MILK
                : hand.is(Items.LEAD) ? Act.LEAD : Act.FEED;
            case GUARD -> f.onWatch() ? Act.WATCH : Act.NONE;
            case SMELT -> byBlock != Act.NONE ? byBlock : swinging ? Act.STOKE : Act.NONE;
            case FISH -> f.isInWater() ? Act.NONE : swinging || nearWater(f, level) ? Act.FISH : Act.NONE;
            case STORE -> byBlock == Act.STOCK || swinging ? Act.STOCK : f.stationPos() != null && near(f, f.stationPos(), 3.0) ? Act.COUNT_COINS : Act.NONE;
            case HAUL -> swinging ? Act.STOCK : Act.NONE;
            case SMITH -> byBlock != Act.NONE ? byBlock : swinging ? Act.HAMMER : Act.NONE;
            case TAILOR -> byBlock != Act.NONE ? byBlock : swinging ? Act.SEW : Act.NONE;
            case BEEKEEP -> byBlock == Act.SMOKE_HIVE || swinging ? Act.SMOKE_HIVE : Act.NONE;
            case BREW -> byBlock != Act.NONE ? byBlock : swinging ? Act.BREW : Act.NONE;
            case ENCHANT -> byBlock != Act.NONE ? byBlock : swinging ? Act.ENCHANT : Act.NONE;
            case COOK -> cook(f, byBlock, swinging);
            case SHOP -> (byBlock == Act.CHISEL || byBlock == Act.SAW || byBlock == Act.HAMMER) && (swinging || Workshop.isHand(f)) ? byBlock
                : Workshop.isHand(f) ? (swinging ? Act.SAW : Act.NONE)
                : swinging ? Act.HAND_OVER : atCounter(f) ? ((f.tickCount / 160 + f.getId()) % 3 == 0 ? Act.HAND_OVER : Act.COUNT_COINS) : Act.NONE;
            case SCOUT, HUNT -> swinging ? Act.NONE : (f.tickCount / 200 + f.getId()) % 4 == 0 ? Act.SPYGLASS : Act.NONE;
            case BANK -> atCounter(f) || swinging ? ((f.tickCount / 200 + f.getId()) % 2 == 0 ? Act.COUNT_COINS : Act.WRITE) : Act.NONE;
            case FERRY -> Act.NONE;
            case FLETCHER -> byBlock != Act.NONE ? byBlock : swinging ? Act.FLETCH : Act.NONE;
            case GOLEMS -> swinging ? Act.REPAIR : Act.NONE;
            case FIREWORKS -> byBlock != Act.NONE ? byBlock : swinging ? Act.FUSE : Act.NONE;
            case CARTOGRAPHER -> byBlock != Act.NONE ? byBlock : swinging ? Act.MAP : Act.NONE;
            case EMERALD -> swinging ? Act.HAND_OVER : Act.NONE;
            case DIVER -> swinging ? Act.HARVEST : Act.NONE;
            case REDSTONE -> byBlock != Act.NONE && byBlock != Act.SAW ? byBlock : swinging ? Act.TINKER : Act.NONE;
            case HEAL -> swinging || Healers.goingTo(f) != null && f.getNavigation().isDone() ? Act.TEND : Act.NONE;
            case NONE -> Act.NONE;
        };
        if (a == Act.NONE && swinging && byBlock != Act.NONE) a = byBlock;   // a trade's hand at a block of another's
        if (a != Act.NONE && s.station != null && (a == byBlock || a.work())) face(f, s);
        return a;
    }

    private static Act cook(VillageFolkEntity f, Act byBlock, boolean swinging) {
        switch (Hospitality.role(f).name()) {
            case "BAKER" -> { return byBlock != Act.NONE || swinging ? Act.KNEAD : Act.NONE; }
            case "BARKEEP" -> { return (f.tickCount / 140 + f.getId()) % 3 == 0 ? Act.WIPE : swinging ? Act.POUR : Act.WIPE; }
            case "WAITER" -> { return swinging ? Act.SERVE : Act.NONE; }
            case "INNKEEPER", "HOST" -> { return atCounter(f) ? Act.WRITE : Act.NONE; }
            default -> { }
        }
        if (byBlock == Act.KNEAD || byBlock == Act.STIR_POT) return byBlock;
        return swinging ? Act.STIR_POT : Act.NONE;
    }

    /** The act of working at a block, whoever's trade it is. */
    static Act byBlock(VillageFolkEntity f, Block b, ItemStack hand) {
        BlockState st = b.defaultBlockState();
        if (st.is(BlockTags.ANVIL) || b == Blocks.SMITHING_TABLE || b == Blocks.GRINDSTONE) return Act.HAMMER;
        if (b == Blocks.STONECUTTER) return Act.CHISEL;
        if (b == Blocks.LOOM) return Act.SEW;
        if (b == Blocks.BREWING_STAND || b == Blocks.CAULDRON || b == Blocks.WATER_CAULDRON) return Act.BREW;
        if (b == Blocks.SMOKER || b == Blocks.CAMPFIRE || b == Blocks.FURNACE && f.stationTask() == StationTask.COOK) return Act.STIR_POT;
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE) return Act.STOKE;
        if (b == Blocks.ENCHANTING_TABLE) return Act.ENCHANT;
        if (b == Blocks.FLETCHING_TABLE) return Act.FLETCH;
        if (b == Blocks.CARTOGRAPHY_TABLE) return Act.MAP;
        if (b == Blocks.LECTERN) return Act.WRITE;
        if (b == Blocks.BEEHIVE || b == Blocks.BEE_NEST) return Act.SMOKE_HIVE;
        if (b == Blocks.CRAFTING_TABLE) return f.stationTask() == StationTask.COOK ? Act.KNEAD : Act.SAW;
        if (b == Blocks.BARREL || b == Blocks.CHEST) return Act.STOCK;
        if (b == Blocks.REDSTONE_WIRE || b == Blocks.REPEATER || b == Blocks.COMPARATOR || b == Blocks.PISTON) return Act.TINKER;
        return Act.NONE;
    }

    /** The work blocks it may stand at, nearest first within two blocks of its feet (looked for again when it moves). */
    @Nullable
    static Block station(VillageFolkEntity f, ServerLevel level, State s) {
        long here = f.blockPosition().asLong();
        if (s.scannedAt == here) return s.stationBlock;
        s.scannedAt = here;
        s.station = null;
        s.stationBlock = null;
        BlockPos feet = f.blockPosition();
        double best = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    p.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    Block b = level.getBlockState(p).getBlock();
                    if (!workBlock(b)) continue;
                    double d = p.distToCenterSqr(f.getX(), f.getY() + 0.5, f.getZ());
                    if (d < best) {
                        best = d;
                        s.station = p.immutable();
                        s.stationBlock = b;
                    }
                }
            }
        }
        return s.stationBlock;
    }

    private static boolean workBlock(Block b) {
        return b instanceof net.minecraft.world.level.block.AnvilBlock || b == Blocks.SMITHING_TABLE || b == Blocks.GRINDSTONE
            || b == Blocks.STONECUTTER || b == Blocks.LOOM || b == Blocks.BREWING_STAND || b == Blocks.CAULDRON
            || b == Blocks.WATER_CAULDRON || b == Blocks.SMOKER || b == Blocks.CAMPFIRE || b == Blocks.FURNACE
            || b == Blocks.BLAST_FURNACE || b == Blocks.ENCHANTING_TABLE || b == Blocks.FLETCHING_TABLE
            || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.LECTERN || b == Blocks.BEEHIVE || b == Blocks.BEE_NEST
            || b == Blocks.CRAFTING_TABLE;
    }

    /** Turned to the block it works at, its head bent to it: the hands meet the anvil, the loom, the pot. */
    static void face(VillageFolkEntity f, State s) {
        if (s.station == null) return;
        double dx = s.station.getX() + 0.5 - f.getX(), dz = s.station.getZ() + 0.5 - f.getZ();
        if (dx * dx + dz * dz < 0.04) return;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        f.setYRot(yaw);
        f.setYBodyRot(yaw);
        f.setYHeadRot(yaw);
        f.getLookControl().setLookAt(s.station.getX() + 0.5, s.station.getY() + 1.0, s.station.getZ() + 0.5);
    }

    private static boolean plantable(ItemStack s) {
        Item i = s.getItem();
        return s.is(ItemTags.VILLAGER_PLANTABLE_SEEDS) || i == Items.WHEAT_SEEDS || i == Items.CARROT || i == Items.POTATO
            || i == Items.BEETROOT_SEEDS || i == Items.MELON_SEEDS || i == Items.PUMPKIN_SEEDS || i == Items.BONE_MEAL
            || s.is(ItemTags.SAPLINGS);
    }

    private static boolean atCounter(VillageFolkEntity f) {
        BlockPos st = f.stationPos();
        return st != null && near(f, st, 4.0);
    }

    private static boolean near(VillageFolkEntity f, BlockPos p, double r) {
        return p.distToCenterSqr(f.getX(), f.getY(), f.getZ()) < r * r;
    }

    private static boolean nearWater(VillageFolkEntity f, ServerLevel level) {
        BlockPos feet = f.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                p.set(feet.getX() + dx, feet.getY() - 1, feet.getZ() + dz);
                if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    /** By a grave of the town's (a gravestone, a headstone: Graves lays them as these). */
    private static boolean nearGrave(VillageFolkEntity f, ServerLevel level) {
        BlockPos feet = f.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                p.set(feet.getX() + dx, feet.getY() - 1, feet.getZ() + dz);
                if (!level.getBlockState(p).is(Blocks.COARSE_DIRT)) continue;
                BlockPos up = p.above();
                if (Graves.isHeadstone(level.getBlockState(up.north())) || Graves.isHeadstone(level.getBlockState(up.south()))
                    || Graves.isHeadstone(level.getBlockState(up.east())) || Graves.isHeadstone(level.getBlockState(up.west()))) return true;
            }
        }
        return false;
    }
}
