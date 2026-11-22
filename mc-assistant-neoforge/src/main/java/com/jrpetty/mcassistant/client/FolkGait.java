package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.Manner;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

/**
 * [vision27] How a folk walks and runs: its legs keep pace with the ground under it, so its feet are planted, not slid.
 *
 * <p>The game's own walk (the player's, which the folk had) swings the legs by a fixed rule from how far the body has
 * gone: at a stroll the legs barely moved while the body glided on (the feet skated), and at a run they flailed twice as
 * far as the ground went by. Here the folk's own speed is measured each frame, from where it is drawn, and smoothed:
 * <ul>
 * <li>its <b>stride</b> (one step of each foot) grows with its speed, from about one and a quarter of its own heights'
 *     worth of leg at a stroll to over three at a run, and is shorter for the old and the tired, longer for the proud and
 *     a skipping child; the legs go round once a stride, so the faster it goes the quicker and the longer its steps;</li>
 * <li>the legs swing <b>just as far as the stride needs</b>: the foot on the ground moves back exactly as fast as the
 *     body moves on (a leg of twelve pixels swung through the angle that covers half a stride);</li>
 * <li>the body <b>dips</b> as the legs open (both feet down) and rises as they pass (one foot under it), which is what
 *     keeps the feet on the ground; the arms swing against the legs, the trunk turns a little with the shoulders and
 *     rolls over the foot it stands on;</li>
 * <li>at a <b>run</b> the arms swing wider and are carried forward, the trunk leans into it;</li>
 * <li>starting and stopping is eased (the speed is smoothed over a few ticks), so a folk never snaps from still to
 *     striding.</li>
 * </ul>
 * Its state (the phase of its stride, its smoothed speed, where it was last frame) is kept on the folk
 * (FolkAction.Seen); nothing is made a frame.
 */
public final class FolkGait {

    private FolkGait() {}

    /** [dev] The game's own walk instead (AnimDev "oldgait"), for the before and after. */
    static boolean old;

    /** A folk's leg, hip to sole, in model pixels. */
    private static final float LEG = 12.0F;

    /**
     * The walk put on the model's limbs (the pose before anything else is laid over it: the sit, the manner's gait, the
     * act's clip). Returns the walk's weight (0 standing, 1 walking), for the others to scale by.
     */
    static float apply(VillageFolkEntity folk, ModelPart head, ModelPart body, ModelPart ra, ModelPart la, ModelPart rl, ModelPart ll,
                       float limbSwing, float limbSwingAmount, float age, boolean young, boolean working) {
        FolkAction.Seen s = folk.animSeen();
        if (old) {
            float step = limbSwing * 0.6662F;
            rl.xRot = Mth.cos(step) * 1.4F * limbSwingAmount;
            ll.xRot = Mth.cos(step + Mth.PI) * 1.4F * limbSwingAmount;
            ra.xRot = Mth.cos(step + Mth.PI) * limbSwingAmount;
            la.xRot = Mth.cos(step) * limbSwingAmount;
            s.gaitPhase = step;
            s.gaitWeight = Math.min(1.0F, limbSwingAmount * 2.0F);
            return s.gaitWeight;
        }
        update(folk, s, age, young);
        float w = s.gaitWeight;
        if (w <= 0.001F) return 0.0F;
        float run = s.gaitRun;
        float a = s.gaitAmp * w;
        float c = Mth.cos(s.gaitPhase), sn = Mth.sin(s.gaitPhase);
        rl.xRot = c * a;
        ll.xRot = -c * a;
        float arms = a * (0.8F + 0.45F * run);
        ra.xRot = -c * arms - 0.35F * run * w;
        la.xRot = c * arms - 0.35F * run * w;
        ra.zRot += 0.06F * run * w;
        la.zRot -= 0.06F * run * w;
        // The dip that keeps the feet down: as low as a leg swung through this angle is short of its full length.
        float drop = LEG * (1.0F - Mth.cos(a * c)) + 0.35F * run * w * Math.abs(sn);
        head.y += drop;
        body.y += drop;
        ra.y += drop;
        la.y += drop;
        rl.y += drop;
        ll.y += drop;
        // The roll over the standing foot, the lean of a run.
        body.zRot += 0.045F * sn * w;
        body.xRot += 0.12F * run * w;
        head.xRot -= 0.06F * run * w;                   // the eyes kept on the way ahead as the trunk leans
        if (!working) {
            // The shoulders turned with the arms' swing (the arms' sockets carried round with the trunk, as the game's
            // own swing does it).
            float turn = 0.07F * c * a * (1.0F + run);
            body.yRot += turn;
            ra.z = Mth.sin(turn) * 5.0F;
            ra.x = -Mth.cos(turn) * 5.0F;
            la.z = -Mth.sin(turn) * 5.0F;
            la.x = Mth.cos(turn) * 5.0F;
        }
        return w;
    }

    /** Its speed measured where it is drawn, smoothed, and its stride's phase moved on by the ground it covered. */
    private static void update(VillageFolkEntity folk, FolkAction.Seen s, float age, boolean young) {
        float partial = age - folk.tickCount;
        double x = Mth.lerp(partial, folk.xo, folk.getX()), z = Mth.lerp(partial, folk.zo, folk.getZ());
        float dt = age - s.gaitAge;
        if (dt == 0.0F) return;                                  // drawn twice this frame (a shadow, a likeness): once is enough
        boolean fresh = s.gaitAge < 0.0F || dt < 0.0F || dt > 20.0F;
        double dx = x - s.gaitX, dz = z - s.gaitZ;
        s.gaitX = x;
        s.gaitZ = z;
        s.gaitAge = age;
        if (fresh) return;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 3.0) return;                                   // carried off (a teleport, a boat): no stride for that
        boolean still = folk.isPassenger() || folk.isSleeping() || folk.getPose() == Pose.SITTING || folk.getPose() == Pose.SWIMMING;
        float raw = still ? 0.0F : (float) (dist / dt * 20.0);   // blocks a second
        float k = 1.0F - (float) Math.exp(-dt / 3.0F);
        s.gaitSpeed += (raw - s.gaitSpeed) * k;
        float scale = young ? 0.62F : 0.95F;
        float u = s.gaitSpeed / scale;                            // its speed in its own heights
        int gait = Manner.gaitOf(folk.clientManner());
        float kind = switch (gait) {
            case Manner.OLD -> 0.62F;
            case Manner.TIRED -> 0.78F;
            case Manner.SHY -> 0.88F;
            case Manner.PROUD -> 1.1F;
            case Manner.SKIP -> 1.12F;
            default -> 1.0F;
        };
        float stride = Mth.clamp(1.1F + 0.42F * u, 1.2F, 3.6F) * kind;         // in its own heights' worth of leg
        s.gaitAmp = stride / (Mth.TWO_PI * 0.75F);
        s.gaitPhase = (s.gaitPhase + (float) (dist / (stride * scale)) * Mth.TWO_PI) % (Mth.TWO_PI * 64.0F);
        s.gaitWeight = smooth((s.gaitSpeed - 0.15F) / 0.6F);
        s.gaitRun = smooth((s.gaitSpeed / scale - 3.9F) / 1.8F);
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** How far through a change of pose (sitting down, standing up) the folk is drawn: 1 done, 0 just begun. */
    static float settle(VillageFolkEntity folk, float age) {
        FolkAction.Seen s = folk.animSeen();
        Pose p = folk.getPose();
        int kind = p == Pose.SITTING ? 1 : p == Pose.CROUCHING ? 2 : 0;
        if (kind != s.poseKind) {
            s.poseFrom = s.poseKind;
            s.poseKind = kind;
            s.poseAt = age;
        }
        return smooth((age - s.poseAt) / 7.0F);
    }

    /**
     * Stood about with nothing in its hands to do (no act, no walk, no moment of its manner's): never dead still. Its eyes
     * go round slowly (now this way, now that, now down), its weight shifts from one foot to the other every several
     * seconds (the hip out over the standing leg, the other knee easing forward), each folk on its own clock.
     */
    static void idle(VillageFolkEntity folk, ModelPart head, ModelPart body, ModelPart ra, ModelPart la, ModelPart rl, ModelPart ll,
                     float age, float walk) {
        if (old) return;
        float w = 1.0F - Math.min(1.0F, walk * 3.0F);
        if (w <= 0.0F) return;
        int id = folk.getId();
        float t = age / 20.0F;
        float look = noise(id * 3 + 1, t / 4.5F), down = noise(id * 3 + 2, t / 6.0F), weight = noise(id * 3, t / 8.0F);
        head.yRot += 0.42F * look * w;
        head.xRot += (0.08F + 0.1F * down) * w;
        float shift = Mth.clamp(weight * 1.6F, -1.0F, 1.0F) * w;
        body.zRot += 0.035F * shift;
        body.x += 0.35F * shift;
        head.x += 0.35F * shift;
        ra.x += 0.35F * shift;
        la.x += 0.35F * shift;
        rl.xRot -= 0.11F * Math.max(0.0F, shift);
        ll.xRot -= 0.11F * Math.max(0.0F, -shift);
        rl.zRot += 0.03F * shift;
        ll.zRot += 0.03F * shift;
    }

    /** A smooth wander between -1 and 1 (value noise): a new aim every step of t, eased into. */
    private static float noise(int seed, float t) {
        int i = Mth.floor(t);
        float f = t - i;
        float a = hash(seed, i), b = hash(seed, i + 1);
        return Mth.lerp(f * f * (3.0F - 2.0F * f), a, b);
    }

    private static float hash(int seed, int i) {
        int h = seed * 0x27d4eb2d ^ i * 0x165667b1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 32767.5F - 1.0F;
    }
}
