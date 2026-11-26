package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featfolk] Walking out: what comes between two folk being fond of each other and the wedding.
 *
 * <p>Two grown folk, neither spoken for nor courting anyone else, not kin, who have come to like each other (both at
 * {@link #START} or more) start walking out ({@link #notice}): the town has it in its news. Off work, once a day, the
 * two go for a walk together ({@link #walk}): one sets off for the park or the edge of the square, the other falls in
 * beside it, and they talk on the way; at the end of it they stand together a while, hearts over them. On a walk the
 * one who has not yet given a present brings the other a flower, handed over then and there (the other keeps it: its
 * own, Homes.keepsake). Each walk warms them to each other. After {@link #WALKS} walks and a flower, two who are close
 * enough ({@link Beliefs#weddingWarmth}) are ready ({@link #ready}): at the end of the next walk, or the next time the
 * two stand together, one asks the other (VillageFolkEntity.courted), and the town holds the wedding (Assemblies), at the
 * chapel if it has one.
 */
public final class Courtship {

    private Courtship() {}

    /** How fond both must be to start walking out. */
    public static final int START = 40;
    /** Walks before a proposal. */
    public static final int WALKS = 2;
    /** How warm a walk makes the two of them. */
    static final int WALK_WARMTH = 6;
    /** A walk's length, in ticks, at the most. */
    static final int WALK_TICKS = 900;

    /** A walk under way: who leads, where to, since when, whether they have got there, and the flower given on it. */
    private static final class Walk {
        final UUID lead, other;
        final BlockPos to;
        final long since;
        long arrived = -1;
        boolean flower;

        Walk(UUID lead, UUID other, BlockPos to, long since) {
            this.lead = lead;
            this.other = other;
            this.to = to;
            this.since = since;
        }
    }

    private static final Map<UUID, Walk> WALKS_ON = new ConcurrentHashMap<>();

    public static void resetForTests() { WALKS_ON.clear(); }

    // ------------------------------------------------------------------ starting

    /** Free to court: grown, not spoken for, not courting someone else (or courting this one). */
    static boolean free(VillageFolkEntity f, @Nullable VillageFolkEntity other) {
        if (f.isBaby() || f.life().partner() != null || !f.life().rolled()) return false;
        UUID c = f.lines().courting;
        return c == null || other != null && c.equals(other.getUUID());
    }

    /** Kin: a parent, a child, a brother or a sister. */
    static boolean kin(VillageFolkEntity a, VillageFolkEntity b) {
        if (a.life().parents().contains(b.displayNameCap()) || b.life().parents().contains(a.displayNameCap())) return true;
        if (a.parentIds().contains(b.getUUID()) || b.parentIds().contains(a.getUUID())) return true;
        for (UUID p : a.parentIds()) if (b.parentIds().contains(p)) return true;
        return !a.life().parents().isEmpty() && a.life().parents().equals(b.life().parents());
    }

    /**
     * Two folk together (VillageFolkEntity.socialBeat): if they are fond enough of each other and free, they start walking
     * out. Returns true if they are (now, or already) courting each other.
     */
    public static boolean notice(VillageFolkEntity a, VillageFolkEntity b, UUID village) {
        if (courting(a, b)) return true;
        if (!free(a, b) || !free(b, a) || kin(a, b)) return false;
        if (a.life().affinity(b.getUUID()) < START || b.life().affinity(a.getUUID()) < START) return false;
        if (a.life().clashesWith(b.life())) return false;
        begin(a, b, village);
        return true;
    }

    /** They are walking out, from today. */
    static void begin(VillageFolkEntity a, VillageFolkEntity b, UUID village) {
        long day = a.level().getDayTime() / 24000L;
        a.lines().courting = b.getUUID();
        a.lines().courtingName = b.displayNameCap();
        b.lines().courting = a.getUUID();
        b.lines().courtingName = a.displayNameCap();
        a.lines().walks = b.lines().walks = 0;
        a.lines().gifts = b.lines().gifts = 0;
        a.persona().remember(day, "I started walking out with " + b.displayNameCap(), 6);
        b.persona().remember(day, "I started walking out with " + a.displayNameCap(), 6);
        Villages.tell(village, day, a.displayNameCap() + " and " + b.displayNameCap() + " are walking out together");
        RandomSource r = a.getRandom();
        FolkTalk.speak(a, FolkTalk.pick(r, "Walk with me this evening? If you're not busy.", "I was going to take a walk later. You could come.",
            "There's a good spot by the trees. I could show you, after work.", "I'll be finished by evening. If you wanted a walk.",
            "Nobody's asked me for a walk in years. So I'm asking you.", "You. Me. A walk. Evening. Yes?"));
        b.sayLater(FolkTalk.pick(r, "I'd like that.", "Go on, then.", "I'm not busy. Funny, that.", "Evening it is.",
            "I'd wondered when you'd ask.", "Yes. Don't be late."), 40);
        FolkAction.mark(a, FolkAction.Mark.HEART);
        FolkAction.mark(b, FolkAction.Mark.HEART);
    }

    /** Are these two walking out with each other? */
    public static boolean courting(VillageFolkEntity a, VillageFolkEntity b) {
        return b.getUUID().equals(a.lines().courting) && a.getUUID().equals(b.lines().courting);
    }

    /** Walked out long enough, a flower given, and close enough to wed. */
    public static boolean ready(VillageFolkEntity a, VillageFolkEntity b) {
        if (!courting(a, b)) return false;
        int warmth = Beliefs.weddingWarmth(a.ownerId());
        return Math.min(a.lines().walks, b.lines().walks) >= WALKS && a.lines().gifts + b.lines().gifts >= 1
            && a.life().affinity(b.getUUID()) >= warmth && b.life().affinity(a.getUUID()) >= warmth;
    }

    /** Pledged: the courting is over (VillageFolkEntity.courted). */
    public static void pledged(VillageFolkEntity a, VillageFolkEntity b) {
        for (VillageFolkEntity f : new VillageFolkEntity[]{ a, b }) {
            f.lines().courting = null;
            f.lines().courtingName = "";
            WALKS_ON.remove(f.getUUID());
        }
    }

    /** One of them is gone (died, moved, wed another): the other is free again. */
    public static void endFor(VillageFolkEntity gone, @Nullable ServerLevel level) {
        UUID other = gone.lines().courting;
        gone.lines().courting = null;
        WALKS_ON.remove(gone.getUUID());
        if (other != null && level != null && level.getEntity(other) instanceof VillageFolkEntity o && gone.getUUID().equals(o.lines().courting)) {
            o.lines().courting = null;
            o.lines().courtingName = "";
            WALKS_ON.remove(other);
        }
    }

    // ------------------------------------------------------------------ the walk

    /**
     * Off work (VillageFolkEntity.socialise): a folk walking out takes its walk of the day with the other, if both are
     * free for it. Returns true while it is on the walk.
     */
    public static boolean walk(VillageFolkEntity f, ServerLevel level) {
        UUID otherId = f.lines().courting;
        if (otherId == null || f.isSleeping() || level.isNight()) return false;
        if (!(level.getEntity(otherId) instanceof VillageFolkEntity other) || !other.isAlive() || other.isSleeping()
                || !f.getUUID().equals(other.lines().courting)) return false;
        long day = level.getDayTime() / 24000L;
        Walk w = WALKS_ON.get(f.getUUID());
        if (w == null) {
            if (f.lines().walkDay == day || other.lines().walkDay == day) return false;   // a walk a day
            if (!other.offWorkNow() || f.distanceToSqr(other) > 48 * 48) return false;
            BlockPos to = destination(f, level);
            if (to == null) return false;
            // The one with the lower id leads; both have the walk.
            VillageFolkEntity lead = f.getUUID().compareTo(other.getUUID()) < 0 ? f : other;
            w = new Walk(lead.getUUID(), lead == f ? other.getUUID() : f.getUUID(), to, level.getGameTime());
            WALKS_ON.put(f.getUUID(), w);
            WALKS_ON.put(other.getUUID(), w);
        }
        long now = level.getGameTime();
        if (now - w.since > WALK_TICKS) {
            finish(level, w, f, other, day);
            return false;
        }
        boolean leads = f.getUUID().equals(w.lead);
        RandomSource r = f.getRandom();
        if (leads) {
            if (w.arrived < 0) {
                if (f.blockPosition().distSqr(w.to) > 9.0) {
                    if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(w.to, 0.75D);
                } else {
                    w.arrived = now;
                    f.getNavigation().stop();
                }
            } else {
                f.getLookControl().setLookAt(other, 30.0F, 30.0F);
                if (now - w.arrived > 200) {
                    finish(level, w, f, other, day);
                    return false;
                }
            }
        } else {
            // Beside the one leading, a step off its shoulder.
            VillageFolkEntity lead = other;
            if (f.distanceToSqr(lead) > 2.5 * 2.5) {
                if (f.getNavigation().isDone() || f.tickCount % 20 == 0) f.getNavigation().moveTo(lead, 0.85D);
            } else {
                f.getNavigation().stop();
                f.getLookControl().setLookAt(lead, 30.0F, 30.0F);
            }
            // The flower: whoever has not given one yet, on the way, picked and handed over.
            if (!w.flower && f.lines().gifts == 0 && f.distanceToSqr(lead) < 3.0 * 3.0 && now - w.since > 160) giveFlower(level, w, f, lead, day);
        }
        if (leads && !w.flower && f.lines().gifts == 0 && f.distanceToSqr(other) < 3.0 * 3.0 && now - w.since > 320) giveFlower(level, w, f, other, day);
        if (f.tickCount % 60 == 0 && f.distanceToSqr(other) < 9.0) {
            level.sendParticles(ParticleTypes.HEART, f.getX(), f.getY() + 2.1, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
        }
        if (leads && r.nextInt(160) == 0 && f.distanceToSqr(other) < 9.0 && level.getNearestPlayer(f, 20.0) != null) {
            FolkTalk.speak(f, FolkTalk.pick(r, "It's quieter out this way.", "Mind the step. Or don't, and I'll catch you.",
                "I come here when the day's been long.", "You can see half the town from here.", "Your hands are cold.",
                "I like this time of day. Everyone else is busy."));
            other.sayLater(FolkTalk.pick(r, "It is.", "I'd noticed.", "Show me the rest some time.", "Mm. Keep walking.",
                "I'm not cold. Well. A bit.", "Same time tomorrow?"), 40);
            FolkAction.play(f, FolkAction.Act.TALK, 50);
            FolkAction.play(other, FolkAction.Act.LISTEN, 50);
        }
        return true;
    }

    /** The park if the town has one, else a spot off the square, else somewhere on the edge of the town. */
    @Nullable
    static BlockPos destination(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        BlockPos centre = f.villageCentre();
        if (village == null || centre == null) return null;
        BlockPos park = Villages.builtAt(village, "park");
        if (park != null) return park;
        RandomSource r = f.getRandom();
        for (int i = 0; i < 6; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            int d = 12 + r.nextInt(10);
            int x = centre.getX() + (int) Math.round(Math.cos(a) * d), z = centre.getZ() + (int) Math.round(Math.sin(a) * d);
            if (!level.hasChunkAt(new BlockPos(x, centre.getY(), z))) continue;
            BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (level.getBlockState(top.below()).getFluidState().isEmpty()) return top;
        }
        return centre;
    }

    private static void giveFlower(ServerLevel level, Walk w, VillageFolkEntity giver, VillageFolkEntity to, long day) {
        w.flower = true;
        giver.lines().gifts++;
        RandomSource r = giver.getRandom();
        ItemStack flower = new ItemStack(switch (r.nextInt(5)) {
            case 0 -> Items.POPPY;
            case 1 -> Items.DANDELION;
            case 2 -> Items.CORNFLOWER;
            case 3 -> Items.OXEYE_DAISY;
            default -> Items.AZURE_BLUET;
        });
        String what = flower.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        Homes.keepsake(flower, to);
        ItemStack left = to.insertGiven(flower);
        if (!left.isEmpty()) to.spawnAtLocation(left);
        giver.getLookControl().setLookAt(to, 30.0F, 30.0F);
        FolkAction.play(giver, FolkAction.Act.HAND_OVER, 40);
        FolkAction.mark(to, FolkAction.Mark.HEART);
        level.sendParticles(ParticleTypes.HEART, to.getX(), to.getY() + 2.1, to.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
        to.life().feel(giver.getUUID(), giver.displayNameCap(), 5);
        to.persona().remember(day, giver.displayNameCap() + " gave me " + FolkTalk.article(what) + " on our walk", 6);
        FolkTalk.speak(giver, FolkTalk.pick(r, "Here. It was growing by the path. Seemed a waste.", "For you. Don't make a thing of it.",
            "I picked it on the way. It's not much.", "It matched your coat. More or less.", "Take it before I feel daft.",
            "I meant to pick a better one. This one wanted to come."));
        to.sayLater(FolkTalk.pick(r, "I'll keep it.", "It's lovely. Thank you.", "You're daft. Thank you.", "I'll put it by the window.",
            "Nobody's given me a flower before.", "It'll be dead by Tuesday. I'll keep it anyway."), 40);
    }

    private static void finish(ServerLevel level, Walk w, VillageFolkEntity f, VillageFolkEntity other, long day) {
        WALKS_ON.remove(f.getUUID());
        WALKS_ON.remove(other.getUUID());
        if (f.lines().walkDay == day) return;
        for (VillageFolkEntity x : new VillageFolkEntity[]{ f, other }) {
            x.lines().walkDay = day;
            x.lines().walks++;
        }
        f.life().feel(other.getUUID(), other.displayNameCap(), WALK_WARMTH);
        other.life().feel(f.getUUID(), f.displayNameCap(), WALK_WARMTH);
        f.persona().remember(day, "I went walking with " + other.displayNameCap(), 4);
        other.persona().remember(day, "I went walking with " + f.displayNameCap(), 4);
        UUID village = f.ownerId();
        if (village != null && ready(f, other)) {
            VillageFolkEntity asks = w.lead.equals(f.getUUID()) ? f : other;
            asks.courted(asks == f ? other : f, level, village);
        }
    }

    // ------------------------------------------------------------------ the card

    /** "Walking out with Fen: 2 walks, a flower given", for the folk's card, or null. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        FolkLines l = f.lines();
        if (l.courting == null) return null;
        return "Walking out with " + l.courtingName + ": " + l.walks + (l.walks == 1 ? " walk" : " walks")
            + (l.gifts > 0 ? ", " + (l.gifts == 1 ? "a flower" : l.gifts + " flowers") + " given" : "");
    }

    // ------------------------------------------------------------------ tests

    /** Tests: one walk had, as though they had been, with a flower from {@code giver} if asked. */
    public static void walkedForTests(VillageFolkEntity a, VillageFolkEntity b, boolean flower) {
        ServerLevel level = (ServerLevel) a.level();
        long day = level.getDayTime() / 24000L;
        Walk w = new Walk(a.getUUID(), b.getUUID(), a.blockPosition(), level.getGameTime());
        if (flower) giveFlower(level, w, a, b, day);
        a.lines().walkDay = b.lines().walkDay = -1;
        finish(level, w, a, b, day);
    }
}
