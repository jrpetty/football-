package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.jrpetty.mcassistant.entity.Manner;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * [anim] The folk's own animations, and the player's emotes: one keyframe clip for every act (FolkAction.Act), in the
 * game's own format (AnimationDefinition, AnimationChannel, Keyframe: the warden's, the camel's, the frog's), played on
 * a folk's model from the one number the server sends, and on a player's from its emote.
 *
 * <p>How a clip is put on a body (apply):
 * <ul>
 * <li>The pose the model is already in (the walk, the sit, the gait of its manner, its idle moment) is faded out of the
 *     limbs the clip moves, and the clip faded in over it, a third of a second each way: an act never snaps on or off,
 *     and the one before it is blended out while the new one comes in.</li>
 * <li>Four "virtual bones" move the whole body the way a single-jointed block figure cannot by its parts alone: a lean
 *     from the hips (the head and shoulders carried forward with the chest, the feet planted), a twist of the trunk (the
 *     arms swung round with the shoulders, as a player's are when it swings), and a lowering of everything above the legs
 *     (bent knees, a kneel, a bob of the dance).</li>
 * <li>Each folk keeps time its own way: a phase of its own through every loop and a speed a few in a hundred either side
 *     of the beat, so two smiths at two anvils are never in step; the tired and the old work slower, a child bouncier, the
 *     proud stand taller at their work (less lean), the tired stoop into it.</li>
 * <li>A left-handed folk plays the mirror of every clip: its other hand on the hammer.</li>
 * </ul>
 *
 * <p>The clips are written here as the game's own data, then read once into flat arrays: the frame's work is a walk down
 * those arrays with one kept vector, and nothing is made a frame (the game's own KeyframeAnimations.animate makes a lambda
 * per bone per frame, and looks each bone up by name).
 */
public final class FolkAnimations {

    private FolkAnimations() {}

    // ------------------------------------------------------------------ the bones

    static final int HEAD = 0, BODY = 1, RA = 2, LA = 3, RL = 4, LL = 5, LEAN = 6, LOWER = 7, TWIST = 8;
    private static final String[] BONE_NAMES = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg", "lean", "lower", "twist"};
    private static final int[] MIRROR = {HEAD, BODY, LA, RA, LL, RL, LEAN, LOWER, TWIST};

    /** A clip read into flat arrays: each track's bone, whether it turns or moves the bone, and its keyframes. */
    public static final class Clip {
        final AnimationDefinition def;
        final boolean loop;
        final float length;
        final int[] bone;
        final boolean[] rot;
        final Keyframe[][] keys;
        /** Which of the body's six parts it moves (the pose under them is faded out as it comes in). */
        final boolean[] moves = new boolean[6];
        /** The moments in the loop the work lands (the hammer on the anvil), in seconds: FolkEffects' sparks and chips. */
        public final float[] hits;

        Clip(AnimationDefinition def, float[] hits) {
            this.def = def;
            this.loop = def.looping();
            this.length = def.lengthInSeconds();
            this.hits = hits;
            int n = 0;
            for (List<AnimationChannel> l : def.boneAnimations().values()) n += l.size();
            bone = new int[n];
            rot = new boolean[n];
            keys = new Keyframe[n][];
            int i = 0;
            for (Map.Entry<String, List<AnimationChannel>> e : def.boneAnimations().entrySet()) {
                int b = boneOf(e.getKey());
                for (AnimationChannel c : e.getValue()) {
                    bone[i] = b;
                    rot[i] = c.target() == AnimationChannel.Targets.ROTATION;
                    keys[i] = c.keyframes();
                    if (b < 6 && rot[i]) moves[b] = true;
                    i++;
                }
            }
        }

        public boolean loop() { return loop; }
        public float length() { return length; }
    }

    private static int boneOf(String name) {
        for (int i = 0; i < BONE_NAMES.length; i++) if (BONE_NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException("no bone " + name);
    }

    // ------------------------------------------------------------------ writing a clip

    /** A clip being written: rotations in degrees, positions in model pixels (y down), times in seconds. */
    private static final class B {
        private final AnimationDefinition.Builder b;
        private float[] hits = new float[0];

        B(float length, boolean loop) {
            b = AnimationDefinition.Builder.withLength(length);
            if (loop) b.looping();
        }

        /** A part turned, eased (Catmull-Rom): t, x, y, z for each key. */
        B r(int bone, float... k) { return channel(bone, AnimationChannel.Targets.ROTATION, k, AnimationChannel.Interpolations.CATMULLROM, true); }

        /** A part turned in straight lines between keys (a blow that lands hard). */
        B rl(int bone, float... k) { return channel(bone, AnimationChannel.Targets.ROTATION, k, AnimationChannel.Interpolations.LINEAR, true); }

        /** A part moved (pixels, y down). */
        B p(int bone, float... k) { return channel(bone, AnimationChannel.Targets.POSITION, k, AnimationChannel.Interpolations.CATMULLROM, false); }

        /** A lean from the hips: t, degrees forward. */
        B lean(float... k) { return virt(LEAN, k, true); }

        /** Everything above the legs lowered: t, pixels down (negative: up off the ground). */
        B low(float... k) { return virt(LOWER, k, false); }

        /** The trunk twisted: t, degrees to its right. */
        B twist(float... k) { return virt(TWIST, k, true); }

        B hit(float... at) { hits = at; return this; }

        private B virt(int bone, float[] k, boolean degrees) {
            Keyframe[] out = new Keyframe[k.length / 2];
            for (int i = 0; i < out.length; i++) {
                float v = k[i * 2 + 1] * (degrees ? Mth.DEG_TO_RAD : 1.0F);
                out[i] = new Keyframe(k[i * 2], new Vector3f(v, 0.0F, 0.0F), AnimationChannel.Interpolations.CATMULLROM);
            }
            b.addAnimation(BONE_NAMES[bone], new AnimationChannel(AnimationChannel.Targets.POSITION, out));
            return this;
        }

        private B channel(int bone, AnimationChannel.Target target, float[] k, AnimationChannel.Interpolation how, boolean degrees) {
            if (k.length % 4 != 0) throw new IllegalArgumentException("keys of four");
            Keyframe[] out = new Keyframe[k.length / 4];
            for (int i = 0; i < out.length; i++) {
                float s = degrees ? Mth.DEG_TO_RAD : 1.0F;
                out[i] = new Keyframe(k[i * 4], new Vector3f(k[i * 4 + 1] * s, k[i * 4 + 2] * s, k[i * 4 + 3] * s), how);
            }
            b.addAnimation(BONE_NAMES[bone], new AnimationChannel(target, out));
            return this;
        }

        Clip done() { return new Clip(b.build(), hits); }
    }

    private static B loop(float length) { return new B(length, true); }

    private static B once(float length) { return new B(length, false); }

    // ------------------------------------------------------------------ the clips

    private static final Clip[] CLIPS = new Clip[Act.values().length];

    public static Clip clip(Act a) { return CLIPS[a.ordinal()]; }

    static {
        // ---------------- work
        // The smith: the hammer lifted over the shoulder, held a beat, brought down hard on the anvil, the jar of it up
        // the arm; the tongs in the other hand keep the iron on the horn; head bent to the work, the trunk turning with
        // the blow, the knees giving a little as it lands.
        put(Act.HAMMER, loop(1.1F)
            .r(RA, 0, -62, -8, 0, 0.30F, -146, 6, 8, 0.44F, -156, 6, 8, 0.55F, -52, -8, 0, 0.64F, -68, -8, 0, 0.8F, -58, -8, 0, 1.1F, -62, -8, 0)
            .r(LA, 0, -50, 22, 0, 0.5F, -50, 22, 0, 0.58F, -46, 22, 0, 0.75F, -50, 22, 0, 1.1F, -50, 22, 0)
            .r(HEAD, 0, 22, 0, 0, 0.3F, 16, 0, 0, 0.56F, 28, 0, 0, 1.1F, 22, 0, 0)
            .twist(0, 0, 0.32F, 9, 0.56F, -5, 0.8F, -2, 1.1F, 0)
            .lean(0, 8, 0.32F, 4, 0.56F, 13, 0.8F, 9, 1.1F, 8)
            .low(0, 0, 0.5F, 0, 0.58F, 0.8F, 0.75F, 0.2F, 1.1F, 0)
            .hit(0.55F).done());
        // The woodcutter: the axe taken back over the right shoulder in both hands, the trunk wound round, then the swing
        // through to the trunk at chest height and on past it, the weight onto the front foot.
        put(Act.CHOP, loop(1.3F)
            .r(RA, 0, -45, -22, 0, 0.42F, -158, 18, 28, 0.52F, -162, 18, 28, 0.68F, -82, -28, 0, 0.8F, -72, -34, 0, 1.3F, -45, -22, 0)
            .r(LA, 0, -48, 26, 0, 0.42F, -140, 38, 0, 0.52F, -144, 38, 0, 0.68F, -80, 26, 0, 0.8F, -70, 30, 0, 1.3F, -48, 26, 0)
            .r(HEAD, 0, 8, 0, 0, 0.45F, -4, 18, 0, 0.7F, 14, -6, 0, 1.3F, 8, 0, 0)
            .twist(0, 0, 0.45F, 15, 0.68F, -12, 0.85F, -8, 1.3F, 0)
            .lean(0, 4, 0.45F, -4, 0.7F, 12, 1.3F, 4)
            .r(RL, 0, 0, 0, 0, 0.45F, 6, 0, 0, 0.7F, -8, 0, 0, 1.3F, 0, 0, 0)
            .r(LL, 0, 0, 0, 0, 0.45F, -6, 0, 0, 0.7F, 8, 0, 0, 1.3F, 0, 0, 0)
            .hit(0.68F).done());
        // The miner: the pick swung up over the head and down into the rock face, the off hand sliding down the haft.
        put(Act.PICK, loop(1.0F)
            .r(RA, 0, -45, -10, 0, 0.36F, -168, 0, 10, 0.44F, -172, 0, 10, 0.55F, -72, -10, 0, 0.63F, -84, -10, 0, 1.0F, -45, -10, 0)
            .r(LA, 0, -58, 26, 0, 0.36F, -148, 30, 0, 0.44F, -150, 30, 0, 0.55F, -76, 28, 0, 0.63F, -82, 28, 0, 1.0F, -58, 26, 0)
            .r(HEAD, 0, 12, 0, 0, 0.38F, -6, 0, 0, 0.56F, 20, 0, 0, 1.0F, 12, 0, 0)
            .lean(0, 6, 0.38F, -3, 0.56F, 15, 0.7F, 11, 1.0F, 6)
            .low(0, 0, 0.55F, 1.0F, 0.75F, 0.3F, 1.0F, 0)
            .hit(0.55F).done());
        // The shovel: the blade set, a foot on it and the weight down, the spoil lifted and thrown off to the side.
        put(Act.DIG, loop(1.4F)
            .r(RA, 0, -52, -18, 0, 0.4F, -30, -14, 0, 0.8F, -72, -14, 0, 1.0F, -98, -2, 0, 1.4F, -52, -18, 0)
            .r(LA, 0, -56, 24, 0, 0.4F, -36, 22, 0, 0.8F, -76, 24, 0, 1.0F, -96, 34, 0, 1.4F, -56, 24, 0)
            .r(RL, 0, 0, 0, 0, 0.25F, -22, 0, 0, 0.4F, -10, 0, 0, 0.6F, 0, 0, 0, 1.4F, 0, 0, 0)
            .r(HEAD, 0, 22, 0, 0, 0.4F, 30, 0, 0, 1.0F, 10, 16, 0, 1.4F, 22, 0, 0)
            .twist(0, 0, 0.8F, 0, 1.0F, 24, 1.4F, 0)
            .lean(0, 12, 0.4F, 20, 0.8F, 12, 1.0F, 6, 1.4F, 12)
            .low(0, 0, 0.4F, 2.0F, 0.8F, 0.5F, 1.4F, 0)
            .hit(0.4F, 1.0F).done());
        // The hoe: chopped into the soil a stride ahead and drawn back toward the feet, bent over the row.
        put(Act.HOE, loop(1.2F)
            .r(RA, 0, -46, -14, 0, 0.38F, -112, -10, 0, 0.58F, -38, -14, 0, 0.82F, -26, -14, 0, 1.2F, -46, -14, 0)
            .r(LA, 0, -48, 24, 0, 0.38F, -106, 22, 0, 0.58F, -42, 24, 0, 0.82F, -30, 24, 0, 1.2F, -48, 24, 0)
            .r(HEAD, 0, 28, 0, 0, 0.38F, 18, 0, 0, 0.6F, 34, 0, 0, 1.2F, 28, 0, 0)
            .lean(0, 18, 0.38F, 12, 0.6F, 24, 0.85F, 20, 1.2F, 18)
            .hit(0.58F).done());
        // Planting: down on its haunches over the row, a hole poked with two fingers, the seed dropped in and pressed down.
        put(Act.PLANT, loop(2.0F)
            .r(RA, 0, -38, -6, 0, 0.35F, -46, -10, 0, 0.6F, -26, -8, 0, 0.9F, -40, -4, 0, 1.2F, -46, 6, 0, 1.4F, -26, 4, 0, 2.0F, -38, -6, 0)
            .r(LA, 0, -34, 14, -6, 1.0F, -38, 16, -6, 2.0F, -34, 14, -6)
            .r(RL, 0, -48, 8, 0, 2.0F, -48, 8, 0)
            .r(LL, 0, 18, -4, 0, 2.0F, 18, -4, 0)
            .r(HEAD, 0, 34, 0, 0, 0.6F, 40, -6, 0, 1.4F, 40, 8, 0, 2.0F, 34, 0, 0)
            .lean(0, 30, 0.6F, 34, 1.4F, 34, 2.0F, 30)
            .low(0, 5, 1.0F, 5.5F, 2.0F, 5)
            .hit(0.6F, 1.4F).done());
        // The harvest: bent to the crop, the sickle swept in under it, the stalks gathered in the other hand and laid by.
        put(Act.HARVEST, loop(1.6F)
            .r(RA, 0, -50, 22, 0, 0.5F, -52, -34, 0, 0.62F, -50, -30, 0, 0.95F, -72, -6, 0, 1.6F, -50, 22, 0)
            .r(LA, 0, -40, 6, 0, 0.45F, -62, 30, 0, 0.62F, -58, 28, 0, 1.0F, -86, 10, 0, 1.25F, -40, 30, -10, 1.6F, -40, 6, 0)
            .r(HEAD, 0, 30, 0, 0, 0.5F, 36, -10, 0, 1.0F, 20, 0, 0, 1.6F, 30, 0, 0)
            .twist(0, 8, 0.5F, -10, 1.0F, 0, 1.6F, 8)
            .lean(0, 30, 0.5F, 36, 1.0F, 22, 1.6F, 30)
            .low(0, 2, 0.5F, 2.5F, 1.0F, 1.0F, 1.6F, 2)
            .hit(0.5F).done());
        // The fisher: the rod drawn back over the shoulder, cast out, a long still wait with the tip twitching, a strike
        // as it bites, and the line reeled in hand over hand.
        put(Act.FISH, loop(6.0F)
            .r(RA, 0, -60, -6, 0, 0.45F, -166, 0, 8, 0.6F, -170, 0, 8, 0.8F, -66, -6, 0, 1.2F, -58, -6, 0, 2.6F, -56, -6, 0, 3.4F, -60, -6, 0,
                4.2F, -56, -6, 0, 4.6F, -110, -6, 0, 5.0F, -82, -6, 0, 5.6F, -70, -6, 0, 6.0F, -60, -6, 0)
            .r(LA, 0, -30, 20, 0, 0.6F, -30, 20, 0, 4.4F, -36, 22, 0, 4.7F, -66, 26, 0, 4.85F, -54, 26, -14, 5.0F, -66, 26, 0,
                5.15F, -54, 26, -14, 5.3F, -66, 26, 0, 5.45F, -54, 26, -14, 5.7F, -40, 22, 0, 6.0F, -30, 20, 0)
            .r(HEAD, 0, 6, 0, 0, 0.5F, -6, 10, 0, 0.9F, 4, 0, 0, 3.0F, 8, 0, 0, 4.5F, 0, 0, 0, 6.0F, 6, 0, 0)
            .twist(0, 0, 0.5F, 16, 0.8F, -6, 1.2F, 0, 6.0F, 0)
            .lean(0, 2, 0.5F, -6, 0.8F, 8, 1.4F, 3, 4.6F, -4, 5.2F, 4, 6.0F, 2)
            .hit(1.3F, 4.6F).done());
        // A pot stirred round and round with a spoon, the other hand on its rim, the head bent over the steam.
        put(Act.STIR_POT, loop(1.6F)
            .r(RA, 0, -50, -22, 0, 0.4F, -60, -6, -6, 0.8F, -50, 8, 0, 1.2F, -42, -8, 6, 1.6F, -50, -22, 0)
            .r(LA, 0, -46, 26, 0, 1.6F, -46, 26, 0)
            .r(HEAD, 0, 30, 0, 0, 0.8F, 32, 4, 0, 1.6F, 30, 0, 0)
            .twist(0, -4, 0.4F, 0, 0.8F, 4, 1.2F, 0, 1.6F, -4)
            .lean(0, 8, 0.8F, 10, 1.6F, 8)
            .hit(0.0F).done());
        // The baker: the dough pushed away under the heels of both hands, folded back, turned, and pushed again.
        put(Act.KNEAD, loop(1.0F)
            .r(RA, 0, -54, -16, 0, 0.42F, -36, -10, 0, 0.55F, -38, -12, 0, 0.8F, -60, -20, 0, 1.0F, -54, -16, 0)
            .r(LA, 0, -54, 16, 0, 0.42F, -36, 10, 0, 0.55F, -38, 12, 0, 0.8F, -60, 22, 0, 1.0F, -54, 16, 0)
            .r(HEAD, 0, 30, 0, 0, 0.45F, 36, 0, 0, 1.0F, 30, 0, 0)
            .lean(0, 10, 0.42F, 18, 0.6F, 15, 1.0F, 10)
            .low(0, 0, 0.42F, 1.0F, 1.0F, 0)
            .hit(0.42F).done());
        // The brewer: the long rod in both hands, round the cauldron in big slow circles.
        put(Act.BREW, loop(1.8F)
            .r(RA, 0, -58, -26, 0, 0.45F, -70, -6, 0, 0.9F, -58, 10, 0, 1.35F, -46, -10, 0, 1.8F, -58, -26, 0)
            .r(LA, 0, -62, 6, 0, 0.45F, -74, 26, 0, 0.9F, -62, 38, 0, 1.35F, -50, 20, 0, 1.8F, -62, 6, 0)
            .r(HEAD, 0, 28, 0, 0, 0.9F, 32, 0, 0, 1.8F, 28, 0, 0)
            .twist(0, -8, 0.45F, 0, 0.9F, 8, 1.35F, 0, 1.8F, -8)
            .lean(0, 10, 0.9F, 14, 1.8F, 10)
            .done());
        // The tailor: the cloth held up in one hand, the needle drawn out and up the length of the thread, and back in.
        put(Act.SEW, loop(1.2F)
            .r(LA, 0, -62, 26, 0, 1.2F, -62, 26, 0)
            .r(RA, 0, -60, -22, 0, 0.25F, -64, -18, 0, 0.55F, -102, 18, 14, 0.7F, -98, 20, 14, 1.0F, -62, -20, 0, 1.2F, -60, -22, 0)
            .r(HEAD, 0, 24, 0, 0, 0.55F, 14, 6, 0, 1.0F, 24, 0, 0, 1.2F, 24, 0, 0)
            .lean(0, 6, 1.2F, 6)
            .done());
        // The carpenter: the saw pushed and drawn through the plank, the other hand holding the plank down.
        put(Act.SAW, loop(0.8F)
            .r(RA, 0, -54, -8, 0, 0.38F, -76, -8, 0, 0.8F, -54, -8, 0)
            .p(RA, 0, 0, 0, 1.0F, 0.38F, 0, 0, -2.0F, 0.8F, 0, 0, 1.0F)
            .r(LA, 0, -44, 30, 0, 0.4F, -46, 30, 0, 0.8F, -44, 30, 0)
            .r(HEAD, 0, 30, 0, 0, 0.8F, 30, 0, 0)
            .twist(0, 4, 0.38F, -5, 0.8F, 4)
            .lean(0, 14, 0.38F, 18, 0.8F, 14)
            .hit(0.38F).done());
        // The mason: the chisel held to the stone, the mallet tapping it in short sharp blows.
        put(Act.CHISEL, loop(0.9F)
            .r(LA, 0, -56, 26, 0, 0.45F, -56, 26, 0, 0.5F, -53, 26, 0, 0.9F, -56, 26, 0)
            .r(RA, 0, -68, -12, 0, 0.32F, -112, -6, 0, 0.38F, -114, -6, 0, 0.46F, -60, -12, 0, 0.55F, -66, -12, 0, 0.9F, -68, -12, 0)
            .r(HEAD, 0, 30, 0, 0, 0.9F, 30, 0, 0)
            .lean(0, 12, 0.46F, 15, 0.9F, 12)
            .hit(0.46F).done());
        // The builder: the block reached up and set in its place, then two taps of the hammer to bed it.
        put(Act.BUILD, loop(1.6F)
            .r(RA, 0, -34, -10, 0, 0.45F, -104, -4, 0, 0.55F, -100, -4, 0, 0.85F, -46, -10, 0, 1.05F, -118, -6, 0, 1.18F, -80, -6, 0,
                1.32F, -116, -6, 0, 1.44F, -80, -6, 0, 1.6F, -34, -10, 0)
            .r(LA, 0, -40, 14, 0, 0.45F, -90, 18, 0, 0.7F, -50, 14, 0, 1.6F, -40, 14, 0)
            .r(HEAD, 0, 4, 0, 0, 0.5F, -14, 0, 0, 1.2F, 6, 0, 0, 1.6F, 4, 0, 0)
            .lean(0, 4, 0.5F, 2, 1.2F, 8, 1.6F, 4)
            .hit(0.5F, 1.18F, 1.44F).done());
        // The sweeper: the broom low in both hands, swept side to side with the trunk turning into each stroke.
        put(Act.SWEEP, loop(1.4F)
            .r(RA, 0, -42, 18, 0, 0.6F, -40, -36, 0, 0.7F, -42, -34, 0, 1.3F, -42, 18, 0, 1.4F, -42, 18, 0)
            .r(LA, 0, -36, 46, 0, 0.6F, -34, -6, 0, 0.7F, -36, -4, 0, 1.3F, -36, 46, 0, 1.4F, -36, 46, 0)
            .r(HEAD, 0, 20, 10, 0, 0.65F, 20, -10, 0, 1.4F, 20, 10, 0)
            .twist(0, 16, 0.65F, -16, 1.4F, 16)
            .lean(0, 14, 0.65F, 16, 1.4F, 14)
            .hit(0.6F, 1.3F).done());
        // A crate in both arms, held against the chest, leaning back a little under its weight.
        put(Act.CARRY, loop(1.0F)
            .r(RA, 0, -56, -22, 0, 0.5F, -58, -22, 0, 1.0F, -56, -22, 0)
            .r(LA, 0, -56, 22, 0, 0.5F, -58, 22, 0, 1.0F, -56, 22, 0)
            .r(HEAD, 0, -6, 0, 0, 1.0F, -6, 0, 0)
            .lean(0, -6, 0.5F, -5, 1.0F, -6)
            .done());
        // A sack over the shoulder, held by its neck, bowed a little under it.
        put(Act.SACK, loop(1.0F)
            .r(RA, 0, -156, -24, 6, 0.5F, -152, -24, 6, 1.0F, -156, -24, 6)
            .r(HEAD, 0, 6, -6, -4, 1.0F, 6, -6, -4)
            .twist(0, 4, 1.0F, 4)
            .lean(0, 8, 0.5F, 9, 1.0F, 8)
            .done());
        // Goods handed over the counter, held out, let go of, a nod; the other hand on the counter.
        put(Act.HAND_OVER, loop(2.2F)
            .r(RA, 0, -34, -8, 0, 0.55F, -86, -6, 0, 1.2F, -84, -6, 0, 1.4F, -76, -4, 0, 1.9F, -34, -8, 0, 2.2F, -34, -8, 0)
            .r(LA, 0, -42, 14, 0, 2.2F, -42, 14, 0)
            .r(HEAD, 0, 6, 0, 0, 0.9F, 2, 0, 0, 1.15F, 14, 0, 0, 1.4F, 4, 0, 0, 2.2F, 6, 0, 0)
            .lean(0, 4, 0.6F, 10, 1.3F, 8, 2.2F, 4)
            .done());
        // Coins counted out of the palm one at a time, a nod for each.
        put(Act.COUNT_COINS, loop(1.6F)
            .r(LA, 0, -52, 24, 0, 1.6F, -52, 24, 0)
            .r(RA, 0, -56, -16, 0, 0.2F, -48, -14, 0, 0.4F, -56, -16, 0, 0.6F, -48, -14, 0, 0.8F, -56, -16, 0, 1.0F, -48, -14, 0,
                1.2F, -56, -16, 0, 1.4F, -48, -14, 0, 1.6F, -56, -16, 0)
            .r(HEAD, 0, 34, 0, 0, 0.2F, 38, 0, 0, 0.4F, 34, 0, 0, 0.6F, 38, 0, 0, 0.8F, 34, 0, 0, 1.0F, 38, 0, 0, 1.2F, 34, 0, 0, 1.4F, 38, 0, 0, 1.6F, 34, 0, 0)
            .lean(0, 6, 1.6F, 6)
            .hit(0.2F, 0.6F, 1.0F, 1.4F).done());
        // At the desk: the quill moving in small strokes along the line, a dip in the ink now and then.
        put(Act.WRITE, loop(2.4F)
            .r(RA, 0, -50, -10, 0, 0.2F, -48, -4, 2, 0.4F, -50, -10, 0, 0.6F, -48, -2, 2, 0.8F, -50, -8, 0, 1.0F, -48, 0, 2,
                1.2F, -50, -6, 0, 1.4F, -48, 2, 2, 1.6F, -50, -12, 0, 1.95F, -62, -30, 0, 2.15F, -60, -28, 0, 2.4F, -50, -10, 0)
            .r(LA, 0, -44, 26, 0, 2.4F, -44, 26, 0)
            .r(HEAD, 0, 34, 0, 4, 1.2F, 36, 4, 4, 2.0F, 30, -10, 0, 2.4F, 34, 0, 4)
            .lean(0, 12, 2.4F, 12)
            .done());
        // The teacher: a turn to the board and a point at it, then back to the class, the hand explaining.
        put(Act.TEACH, loop(3.2F)
            .r(RA, 0, -40, 0, 0, 0.5F, -104, 26, 10, 1.2F, -102, 26, 10, 1.6F, -62, 0, 0, 2.0F, -66, 10, 6, 2.4F, -58, -6, -4, 2.8F, -64, 6, 4, 3.2F, -40, 0, 0)
            .r(LA, 0, -50, 20, 0, 3.2F, -50, 20, 0)
            .r(HEAD, 0, 0, 0, 0, 0.5F, -6, 34, 0, 1.2F, -6, 34, 0, 1.7F, 0, 0, 0, 2.4F, 4, 0, 4, 3.2F, 0, 0, 0)
            .twist(0, 0, 0.5F, 12, 1.2F, 12, 1.7F, 0, 3.2F, 0)
            .done());
        // The healer: bent over the bed, both hands at the bandage, winding it round.
        put(Act.TEND, loop(1.6F)
            .r(RA, 0, -50, -18, 0, 0.4F, -56, -6, -8, 0.8F, -48, -10, 0, 1.2F, -54, -24, 6, 1.6F, -50, -18, 0)
            .r(LA, 0, -52, 18, 0, 0.4F, -46, 12, 6, 0.8F, -54, 6, 0, 1.2F, -48, 22, -6, 1.6F, -52, 18, 0)
            .r(HEAD, 0, 24, 0, 0, 0.8F, 28, 4, 0, 1.6F, 24, 0, 0)
            .lean(0, 32, 0.8F, 35, 1.6F, 32)
            .done());
        // Preaching, crying the news: both arms up and out to the crowd, then down to open palms, then one hand making
        // the point.
        put(Act.PREACH, loop(3.2F)
            .r(RA, 0, -40, 0, 0, 0.6F, -158, 0, 30, 1.3F, -150, 0, 34, 1.8F, -90, 0, 22, 2.3F, -72, -10, 4, 2.6F, -82, -6, 8, 3.2F, -40, 0, 0)
            .r(LA, 0, -40, 0, 0, 0.6F, -158, 0, -30, 1.3F, -150, 0, -34, 1.8F, -90, 0, -22, 2.3F, -40, 0, -6, 3.2F, -40, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.6F, -18, 0, 0, 1.3F, -14, 0, 0, 2.0F, 0, 0, 0, 2.5F, 6, -8, 0, 3.2F, 0, 0, 0)
            .lean(0, 0, 0.6F, -6, 1.3F, -5, 2.0F, 2, 3.2F, 0)
            .done());
        // The guard on watch: both hands on the pommel of the blade stood point down before it, its eyes going slowly round.
        put(Act.WATCH, loop(6.0F)
            .r(RA, 0, -74, -16, 0, 3.0F, -76, -16, 0, 6.0F, -74, -16, 0)
            .r(LA, 0, -70, 30, 0, 3.0F, -72, 30, 0, 6.0F, -70, 30, 0)
            .r(HEAD, 0, -2, -30, 0, 1.5F, -2, -30, 0, 3.0F, -2, 30, 0, 4.5F, -2, 30, 0, 6.0F, -2, -30, 0)
            .twist(0, -3, 3.0F, 3, 6.0F, -3)
            .r(RL, 0, 0, 0, 4, 6.0F, 0, 0, 4)
            .r(LL, 0, 0, 0, -4, 6.0F, 0, 0, -4)
            .done());
        // Milking: sat low at the cow's side, both hands pulling turn about.
        put(Act.MILK, loop(1.0F)
            .r(RA, 0, -42, -8, 0, 0.25F, -26, -8, 0, 0.5F, -42, -8, 0, 1.0F, -42, -8, 0)
            .r(LA, 0, -42, 8, 0, 0.5F, -42, 8, 0, 0.75F, -26, 8, 0, 1.0F, -42, 8, 0)
            .r(RL, 0, -62, 10, 0, 1.0F, -62, 10, 0)
            .r(LL, 0, -62, -10, 0, 1.0F, -62, -10, 0)
            .r(HEAD, 0, 22, 0, 0, 1.0F, 22, 0, 0)
            .lean(0, 22, 1.0F, 22)
            .low(0, 6, 1.0F, 6)
            .hit(0.25F, 0.75F).done());
        // Shearing: bent over the sheep, the shears snipping, the other hand on the fleece.
        put(Act.SHEAR, loop(1.0F)
            .r(RA, 0, -60, -10, 0, 0.12F, -62, -6, 8, 0.24F, -60, -10, 0, 0.5F, -66, 10, 0, 0.62F, -68, 14, 8, 0.74F, -66, 10, 0, 1.0F, -60, -10, 0)
            .r(LA, 0, -56, 14, 0, 0.5F, -60, 10, 0, 1.0F, -56, 14, 0)
            .r(HEAD, 0, 24, 0, 0, 1.0F, 24, 0, 0)
            .lean(0, 26, 0.5F, 28, 1.0F, 26)
            .hit(0.12F, 0.62F).done());
        // Leading an animal: the rope held out behind, a look back now and then to see it comes.
        put(Act.LEAD, loop(3.0F)
            .r(RA, 0, 18, 0, 16, 1.5F, 22, 0, 18, 3.0F, 18, 0, 16)
            .r(HEAD, 0, 0, 0, 0, 1.2F, 0, 0, 0, 1.5F, 0, 50, 0, 2.0F, 0, 50, 0, 2.3F, 0, 0, 0, 3.0F, 0, 0, 0)
            .done());
        // Feeding: a hand into the bag at the hip and the grain flung out wide in an arc.
        put(Act.FEED, loop(1.6F)
            .r(LA, 0, -30, 22, 0, 1.6F, -30, 22, 0)
            .r(RA, 0, -36, -26, 0, 0.4F, -30, -34, 0, 0.75F, -82, 38, 24, 0.95F, -76, 40, 26, 1.6F, -36, -26, 0)
            .r(HEAD, 0, 10, 0, 0, 0.8F, 6, 20, 0, 1.6F, 10, 0, 0)
            .twist(0, -6, 0.8F, 10, 1.6F, -6)
            .hit(0.8F).done());
        // The beekeeper: the smoker held to the hive, the bellows squeezed with the other hand, a slow sway.
        put(Act.SMOKE_HIVE, loop(2.0F)
            .r(RA, 0, -70, -10, 0, 2.0F, -70, -10, 0)
            .r(LA, 0, -64, 22, 0, 0.5F, -70, 30, 0, 1.0F, -64, 22, 0, 1.5F, -70, 30, 0, 2.0F, -64, 22, 0)
            .r(HEAD, 0, 14, 0, 0, 1.0F, 16, 6, 0, 2.0F, 14, 0, 0)
            .twist(0, -6, 1.0F, 6, 2.0F, -6)
            .lean(0, 10, 2.0F, 10)
            .hit(0.5F, 1.5F).done());
        // The fletcher: the shaft in one hand, the feather bound to it with the other.
        put(Act.FLETCH, loop(1.6F)
            .r(LA, 0, -66, 30, 0, 1.6F, -66, 30, 0)
            .r(RA, 0, -64, -24, 0, 0.4F, -70, -18, 10, 0.8F, -60, -26, 0, 1.2F, -68, -30, -10, 1.6F, -64, -24, 0)
            .r(HEAD, 0, 30, 0, 0, 1.6F, 30, 0, 0)
            .lean(0, 6, 1.6F, 6)
            .done());
        // The enchanter: both hands spread over the book, palms down, drifting as if feeling the words.
        put(Act.ENCHANT, loop(3.0F)
            .r(RA, 0, -68, -14, 0, 0.75F, -76, -6, -6, 1.5F, -66, -18, 0, 2.25F, -74, -10, 6, 3.0F, -68, -14, 0)
            .r(LA, 0, -68, 14, 0, 0.75F, -62, 18, 6, 1.5F, -74, 8, 0, 2.25F, -64, 20, -6, 3.0F, -68, 14, 0)
            .r(HEAD, 0, 22, 0, 0, 1.5F, 18, 0, 3, 3.0F, 22, 0, 0)
            .lean(0, 6, 1.5F, 8, 3.0F, 6)
            .low(0, 0, 1.5F, -0.6F, 3.0F, 0)
            .done());
        // The smelter: coal scooped low and thrown into the furnace mouth.
        put(Act.STOKE, loop(1.4F)
            .r(RA, 0, -40, -16, 0, 0.4F, -28, -14, 0, 0.75F, -60, -14, 0, 0.9F, -92, -10, 0, 1.4F, -40, -16, 0)
            .r(LA, 0, -44, 24, 0, 0.4F, -32, 22, 0, 0.75F, -62, 24, 0, 0.9F, -90, 26, 0, 1.4F, -44, 24, 0)
            .r(HEAD, 0, 20, 0, 0, 0.4F, 30, 0, 0, 0.9F, 8, 0, 0, 1.4F, 20, 0, 0)
            .lean(0, 18, 0.4F, 26, 0.9F, 8, 1.4F, 18)
            .low(0, 1, 0.4F, 2, 0.9F, 0, 1.4F, 1)
            .hit(0.9F).done());
        // The scout: the spyglass at the eye, the other hand steadying it, the view swept slowly along the horizon.
        put(Act.SPYGLASS, loop(5.0F)
            .r(RA, 0, -112, -26, 0, 5.0F, -112, -26, 0)
            .r(LA, 0, -96, 30, 0, 5.0F, -96, 30, 0)
            .r(HEAD, 0, -6, -20, 0, 2.5F, -6, 20, 0, 5.0F, -6, -20, 0)
            .twist(0, -10, 2.5F, 10, 5.0F, -10)
            .done());
        // The barkeep: the bottle tipped over the cup in the other hand, then set upright.
        put(Act.POUR, loop(2.4F)
            .r(RA, 0, -60, -12, 0, 0.6F, -80, -18, 0, 0.9F, -80, -18, -40, 1.6F, -80, -18, -42, 1.9F, -78, -16, 0, 2.4F, -60, -12, 0)
            .r(LA, 0, -56, 22, 0, 2.4F, -56, 22, 0)
            .r(HEAD, 0, 18, 0, 0, 1.2F, 24, -6, 0, 2.4F, 18, 0, 0)
            .lean(0, 4, 1.2F, 6, 2.4F, 4)
            .done());
        // A waiter's tray up on the flat of one hand, the dish set down with the other, a little bow.
        put(Act.SERVE, loop(2.4F)
            .r(LA, 0, -100, 6, -18, 2.4F, -100, 6, -18)
            .r(RA, 0, -40, -6, 0, 0.7F, -74, 26, 14, 1.2F, -60, 10, 8, 1.6F, -40, -6, 0, 2.4F, -40, -6, 0)
            .r(HEAD, 0, 0, 0, 0, 0.9F, 14, 0, 0, 1.6F, 0, 0, 0, 2.4F, 0, 0, 0)
            .lean(0, 0, 0.9F, 10, 1.6F, 0, 2.4F, 0)
            .done());
        // The cartographer: a long stroke along the ruler, the sheet held flat with the other hand.
        put(Act.MAP, loop(2.6F)
            .r(RA, 0, -56, -22, 0, 1.0F, -54, 16, 0, 1.3F, -60, 14, 0, 2.2F, -58, -20, 0, 2.6F, -56, -22, 0)
            .r(LA, 0, -50, 26, 0, 2.6F, -50, 26, 0)
            .r(HEAD, 0, 38, -6, 0, 1.0F, 38, 8, 0, 2.6F, 38, -6, 0)
            .lean(0, 18, 1.3F, 20, 2.6F, 18)
            .done());
        // The engineer: bent close over the works, a quick turn of the spanner, a look, another turn.
        put(Act.TINKER, loop(1.6F)
            .r(RA, 0, -56, -20, 0, 0.15F, -56, -20, 22, 0.3F, -56, -20, -10, 0.45F, -56, -20, 22, 0.6F, -56, -20, 0, 1.6F, -56, -20, 0)
            .r(LA, 0, -54, 22, 0, 1.6F, -54, 22, 0)
            .r(HEAD, 0, 30, 0, 0, 0.8F, 30, 0, 0, 1.0F, 22, 12, 0, 1.4F, 30, 0, 0, 1.6F, 30, 0, 0)
            .lean(0, 22, 1.6F, 22)
            .hit(0.45F).done());
        // The fireworks maker: the rocket upright in one hand, the powder tamped in with careful taps, held up and looked at.
        put(Act.FUSE, loop(2.6F)
            .r(LA, 0, -70, 22, 0, 1.4F, -70, 22, 0, 1.8F, -110, 10, 0, 2.2F, -108, 10, 0, 2.6F, -70, 22, 0)
            .r(RA, 0, -76, -20, 0, 0.25F, -70, -20, 0, 0.5F, -76, -20, 0, 0.75F, -70, -20, 0, 1.0F, -76, -20, 0, 1.4F, -60, -16, 0, 2.6F, -76, -20, 0)
            .r(HEAD, 0, 26, 0, 0, 1.4F, 26, 0, 0, 1.9F, -8, 6, 0, 2.2F, -8, 6, 0, 2.6F, 26, 0, 0)
            .lean(0, 10, 1.4F, 10, 1.9F, 0, 2.6F, 10)
            .done());
        // Stocking the shelves: a box lifted from the knees up onto the shelf over the head.
        put(Act.STOCK, loop(1.8F)
            .r(RA, 0, -40, -18, 0, 0.8F, -142, -10, 0, 1.0F, -138, -10, 0, 1.8F, -40, -18, 0)
            .r(LA, 0, -40, 18, 0, 0.8F, -142, 10, 0, 1.0F, -138, 10, 0, 1.8F, -40, 18, 0)
            .r(HEAD, 0, 16, 0, 0, 0.8F, -22, 0, 0, 1.0F, -18, 0, 0, 1.8F, 16, 0, 0)
            .lean(0, 16, 0.8F, -4, 1.0F, -2, 1.8F, 16)
            .low(0, 2, 0.8F, 0, 1.8F, 2)
            .hit(0.8F).done());
        // Watering the beds: the can tipped, swept along the row.
        put(Act.WATER, loop(2.0F)
            .r(RA, 0, -60, 10, -30, 1.0F, -62, -20, -34, 2.0F, -60, 10, -30)
            .r(LA, 0, -10, 0, -8, 2.0F, -10, 0, -8)
            .r(HEAD, 0, 28, 6, 0, 1.0F, 28, -10, 0, 2.0F, 28, 6, 0)
            .lean(0, 8, 2.0F, 8)
            .hit(0.0F, 0.5F, 1.0F, 1.5F).done());
        // The golem keeper: an ingot held to the golem's iron and hammered on, at chest height.
        put(Act.REPAIR, loop(1.2F)
            .r(RA, 0, -98, -10, 0, 0.36F, -172, 0, 10, 0.46F, -176, 0, 10, 0.56F, -96, -10, 0, 0.66F, -106, -10, 0, 1.2F, -98, -10, 0)
            .r(LA, 0, -88, 24, 0, 1.2F, -88, 24, 0)
            .r(HEAD, 0, -4, 0, 0, 0.56F, 2, 0, 0, 1.2F, -4, 0, 0)
            .twist(0, 0, 0.36F, 8, 0.56F, -4, 1.2F, 0)
            .hit(0.56F).done());
        // Wiping the counter in small circles, the other hand flat on it.
        put(Act.WIPE, loop(1.6F)
            .r(RA, 0, -44, -14, 0, 0.4F, -48, 6, -8, 0.8F, -42, 14, 0, 1.2F, -40, -4, 8, 1.6F, -44, -14, 0)
            .r(LA, 0, -36, 16, 0, 1.6F, -36, 16, 0)
            .r(HEAD, 0, 18, 0, 0, 1.6F, 18, 0, 0)
            .lean(0, 10, 1.6F, 10)
            .done());

        // ---------------- life
        // A bite: the food up to the mouth, a chew or two, down again; the plate in the other hand.
        put(Act.EAT, loop(1.8F)
            .r(RA, 0, -36, -10, 0, 0.45F, -106, -36, 0, 0.95F, -102, -34, 0, 1.4F, -40, -10, 0, 1.8F, -36, -10, 0)
            .r(LA, 0, -40, 16, 0, 1.8F, -40, 16, 0)
            .r(HEAD, 0, 6, 0, 0, 0.45F, 0, 0, 0, 0.6F, 6, 0, 0, 0.75F, 1, 0, 0, 0.9F, 6, 0, 0, 1.8F, 6, 0, 0)
            .hit(0.55F).done());
        // A drink: the cup to the lips, the head tipped back for the draught.
        put(Act.DRINK, loop(2.2F)
            .r(RA, 0, -40, -10, 0, 0.55F, -112, -30, 0, 1.3F, -122, -28, 0, 1.7F, -42, -10, 0, 2.2F, -40, -10, 0)
            .r(LA, 0, -12, 0, -6, 2.2F, -12, 0, -6)
            .r(HEAD, 0, 0, 0, 0, 0.6F, -6, 0, 0, 0.9F, -24, 0, 0, 1.3F, -26, 0, 0, 1.7F, 0, 0, 0, 2.2F, 0, 0, 0)
            .done());
        // Sat down: hands in the lap, a shift of the shoulders now and then.
        put(Act.SIT, loop(5.0F)
            .r(RA, 0, -40, -6, 0, 2.5F, -42, -6, 0, 5.0F, -40, -6, 0)
            .r(LA, 0, -40, 6, 0, 2.5F, -38, 6, 0, 5.0F, -40, 6, 0)
            .lean(0, 2, 2.5F, -1, 5.0F, 2)
            .done());
        // Asleep: the bed lays it down; the arms slack, the breath slow.
        put(Act.SLEEP, loop(4.0F)
            .r(RA, 0, 0, 0, 4, 2.0F, 0, 0, 7, 4.0F, 0, 0, 4)
            .r(LA, 0, 0, 0, -4, 2.0F, 0, 0, -7, 4.0F, 0, 0, -4)
            .done());
        // Talking: the hands at the words, open palms out and back, a nod, a turn of the shoulders.
        put(Act.TALK, loop(2.8F)
            .r(RA, 0, -36, 0, 0, 0.5F, -62, 22, 8, 0.9F, -50, 10, 4, 1.4F, -40, 0, 0, 2.2F, -54, 16, 6, 2.8F, -36, 0, 0)
            .r(LA, 0, -20, 0, -4, 1.2F, -24, 0, -4, 1.7F, -56, -18, -8, 2.1F, -40, -8, -4, 2.8F, -20, 0, -4)
            .r(HEAD, 0, 0, 0, 0, 0.6F, 4, 0, 3, 1.0F, -2, 0, 0, 1.8F, 4, 0, -3, 2.8F, 0, 0, 0)
            .twist(0, 0, 0.6F, -4, 1.8F, 4, 2.8F, 0)
            .done());
        // Listening: the hands clasped in front, a nod, the head tilted to the speaker.
        put(Act.LISTEN, loop(4.0F)
            .r(RA, 0, -34, -4, -18, 4.0F, -34, -4, -18)
            .r(LA, 0, -34, 4, 18, 4.0F, -34, 4, 18)
            .r(HEAD, 0, 2, 0, 4, 1.4F, 2, 0, 4, 1.6F, 10, 0, 4, 1.8F, 2, 0, 4, 3.0F, 2, 0, -3, 3.2F, 9, 0, -3, 3.4F, 2, 0, -3, 4.0F, 2, 0, 4)
            .done());
        // A handshake: the hand out, gripped, pumped three times, let go, a nod over it.
        put(Act.HANDSHAKE, once(2.0F)
            .r(RA, 0, -10, 0, 0, 0.4F, -58, -14, 0, 0.6F, -64, -14, 0, 0.75F, -50, -14, 0, 0.9F, -64, -14, 0, 1.05F, -50, -14, 0,
                1.2F, -60, -14, 0, 1.6F, -54, -14, 0, 2.0F, -10, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.6F, 4, 0, 0, 0.8F, 14, 0, 0, 1.1F, 2, 0, 0, 2.0F, 0, 0, 0)
            .lean(0, 0, 0.6F, 8, 1.2F, 6, 2.0F, 0)
            .done());
        // A cheer: both arms flung up, pumped, a little jump.
        put(Act.CHEER, once(1.8F)
            .r(RA, 0, -10, 0, 0, 0.15F, -170, 0, 24, 0.4F, -150, 0, 30, 0.6F, -176, 0, 24, 0.8F, -150, 0, 30, 1.0F, -172, 0, 24, 1.8F, -14, 0, 0)
            .r(LA, 0, -10, 0, 0, 0.15F, -170, 0, -24, 0.4F, -150, 0, -30, 0.6F, -176, 0, -24, 0.8F, -150, 0, -30, 1.0F, -172, 0, -24, 1.8F, -14, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.2F, -18, 0, 0, 1.1F, -14, 0, 0, 1.8F, 0, 0, 0)
            .low(0, 0, 0.08F, 1.2F, 0.2F, -2.5F, 0.35F, 0, 0.6F, -1.5F, 0.75F, 0, 1.8F, 0)
            .done());
        // Clapping: the hands brought together in front and apart, quick and bright.
        put(Act.CLAP, loop(0.5F)
            .r(RA, 0, -70, -26, 0, 0.25F, -66, 4, 0, 0.5F, -70, -26, 0)
            .r(LA, 0, -70, 26, 0, 0.25F, -66, -4, 0, 0.5F, -70, 26, 0)
            .r(HEAD, 0, -6, 0, 0, 0.5F, -6, 0, 0)
            .low(0, 0.3F, 0.25F, 0, 0.5F, 0.3F)
            .hit(0.0F).done());
        // A country dance: a bob on every beat, a kick, a hand up and the other on the hip, then the other way round.
        put(Act.DANCE, loop(2.0F)
            .r(RA, 0, -150, 0, 20, 0.25F, -160, 0, 34, 0.5F, -150, 0, 20, 0.75F, -160, 0, 34, 1.0F, -30, 0, 40, 1.5F, -26, 0, 44, 2.0F, -150, 0, 20)
            .r(LA, 0, -30, 0, -40, 0.5F, -26, 0, -44, 1.0F, -150, 0, -20, 1.25F, -160, 0, -34, 1.5F, -150, 0, -20, 1.75F, -160, 0, -34, 2.0F, -30, 0, -40)
            .r(RL, 0, 0, 0, 0, 0.25F, -42, 0, 0, 0.5F, 0, 0, 0, 1.25F, 0, 0, 0, 1.5F, 10, 0, 0, 2.0F, 0, 0, 0)
            .r(LL, 0, 0, 0, 0, 0.75F, 0, 0, 0, 1.0F, 10, 0, 0, 1.25F, -42, 0, 0, 1.5F, 0, 0, 0, 2.0F, 0, 0, 0)
            .r(HEAD, 0, 0, 0, 6, 0.5F, -4, 0, 6, 1.0F, 0, 0, -6, 1.5F, -4, 0, -6, 2.0F, 0, 0, 6)
            .twist(0, 14, 0.5F, 10, 1.0F, -14, 1.5F, -10, 2.0F, 14)
            .low(0, 0, 0.12F, 1.5F, 0.25F, -1, 0.37F, 1.5F, 0.5F, 0, 0.62F, 1.5F, 0.75F, -1, 0.87F, 1.5F, 1.0F, 0, 1.12F, 1.5F, 1.25F, -1,
                1.37F, 1.5F, 1.5F, 0, 1.62F, 1.5F, 1.75F, -1, 1.87F, 1.5F, 2.0F, 0)
            .hit(0.25F, 1.25F).done());
        // Mourning: down on one knee at the grave, the head bowed, the hands clasped, the shoulders shaking now and then.
        put(Act.MOURN, loop(4.0F)
            .r(RA, 0, -30, -18, -14, 4.0F, -30, -18, -14)
            .r(LA, 0, -30, 18, 14, 4.0F, -30, 18, 14)
            .r(RL, 0, -84, 6, 0, 4.0F, -84, 6, 0)
            .r(LL, 0, 54, -4, 0, 4.0F, 54, -4, 0)
            .r(HEAD, 0, 38, 0, 0, 2.0F, 42, 0, 0, 2.2F, 40, 0, 2, 2.4F, 42, 0, -2, 2.6F, 40, 0, 0, 4.0F, 38, 0, 0)
            .lean(0, 10, 2.0F, 12, 4.0F, 10)
            .low(0, 7, 4.0F, 7)
            .done());
        // Praying: knelt, the hands together at the breast, the head bowed.
        put(Act.PRAY, loop(4.0F)
            .r(RA, 0, -74, -34, 0, 2.0F, -76, -34, 0, 4.0F, -74, -34, 0)
            .r(LA, 0, -74, 34, 0, 2.0F, -76, 34, 0, 4.0F, -74, 34, 0)
            .r(RL, 0, -84, 6, 0, 4.0F, -84, 6, 0)
            .r(LL, 0, 54, -4, 0, 4.0F, 54, -4, 0)
            .r(HEAD, 0, 26, 0, 0, 2.0F, 30, 0, 0, 4.0F, 26, 0, 0)
            .lean(0, 4, 4.0F, 4)
            .low(0, 7, 4.0F, 7)
            .done());
        // The lute: the neck out in the left hand, the strings strummed with the right, a nod to the beat.
        put(Act.LUTE, loop(1.0F)
            .r(LA, 0, -56, -24, 0, 0.5F, -60, -22, 0, 1.0F, -56, -24, 0)
            .r(RA, 0, -40, -34, 10, 0.125F, -40, -34, -8, 0.25F, -40, -34, 10, 0.375F, -40, -34, -8, 0.5F, -40, -34, 10,
                0.625F, -40, -34, -8, 0.75F, -40, -34, 10, 0.875F, -40, -34, -8, 1.0F, -40, -34, 10)
            .r(HEAD, 0, 10, 0, 8, 0.25F, 4, 0, 8, 0.5F, 10, 0, 8, 0.75F, 4, 0, 8, 1.0F, 10, 0, 8)
            .low(0, 0, 0.25F, 0.5F, 0.5F, 0, 0.75F, 0.5F, 1.0F, 0)
            .hit(0.0F, 0.5F).done());
        // The drum: both sticks, turn about, on the drum at the waist.
        put(Act.DRUM, loop(0.6F)
            .r(RA, 0, -80, -10, 0, 0.15F, -50, -10, 0, 0.3F, -80, -10, 0, 0.6F, -80, -10, 0)
            .r(LA, 0, -50, 10, 0, 0.3F, -80, 10, 0, 0.45F, -50, 10, 0, 0.6F, -50, 10, 0)
            .r(HEAD, 0, 14, 0, 0, 0.15F, 18, 0, 0, 0.45F, 18, 0, 0, 0.6F, 14, 0, 0)
            .hit(0.15F, 0.45F).done());
        // Tag: running with the arms out to catch (or flung up out of reach), the legs left to the run.
        put(Act.TAG, loop(0.6F)
            .r(RA, 0, -96, 0, 10, 0.3F, -76, 0, 16, 0.6F, -96, 0, 10)
            .r(LA, 0, -76, 0, -16, 0.3F, -96, 0, -10, 0.6F, -76, 0, -16)
            .r(HEAD, 0, -10, 0, 0, 0.6F, -10, 0, 0)
            .lean(0, 8, 0.6F, 8)
            .done());
        // Hopscotch: a hop on one foot, the arms out for balance, a landing on both, and again.
        put(Act.HOPSCOTCH, loop(1.6F)
            .r(RA, 0, -20, 0, 46, 0.4F, -24, 0, 56, 0.8F, -10, 0, 20, 1.2F, -24, 0, 56, 1.6F, -20, 0, 46)
            .r(LA, 0, -20, 0, -46, 0.4F, -24, 0, -56, 0.8F, -10, 0, -20, 1.2F, -24, 0, -56, 1.6F, -20, 0, -46)
            .r(LL, 0, 46, 0, 0, 0.7F, 46, 0, 0, 0.8F, 0, 0, 0, 1.0F, 0, 0, 0, 1.1F, 46, 0, 0, 1.6F, 46, 0, 0)
            .r(HEAD, 0, 14, 0, 0, 1.6F, 14, 0, 0)
            .low(0, 0, 0.15F, -3, 0.3F, 0, 0.45F, -3, 0.6F, 0, 0.8F, 1, 1.0F, 0, 1.15F, -3, 1.3F, 0, 1.45F, -3, 1.6F, 0)
            .done());
        // A ladder, hand over hand and foot over foot.
        put(Act.CLIMB, loop(0.8F)
            .r(RA, 0, -168, -6, 0, 0.4F, -138, -6, 0, 0.8F, -168, -6, 0)
            .r(LA, 0, -138, 6, 0, 0.4F, -168, 6, 0, 0.8F, -138, 6, 0)
            .r(RL, 0, 0, 0, 0, 0.4F, -44, 0, 0, 0.8F, 0, 0, 0)
            .r(LL, 0, -44, 0, 0, 0.4F, 0, 0, 0, 0.8F, -44, 0, 0)
            .r(HEAD, 0, -20, 0, 0, 0.8F, -20, 0, 0)
            .done());
        // A gate pushed open: a reach, a shove out to the side, the hand back.
        put(Act.OPEN_GATE, once(1.0F)
            .r(RA, 0, -10, 0, 0, 0.3F, -82, -6, 0, 0.6F, -80, 34, 10, 1.0F, -10, 0, 0)
            .lean(0, 0, 0.35F, 4, 0.6F, 8, 1.0F, 0)
            .done());
        // Rowing: both oars pulled back with the body leaning into it, then forward again.
        put(Act.ROW, loop(1.6F)
            .r(RA, 0, -92, -6, 0, 0.8F, -28, -6, 10, 1.6F, -92, -6, 0)
            .r(LA, 0, -92, 6, 0, 0.8F, -28, 6, -10, 1.6F, -92, 6, 0)
            .lean(0, 16, 0.8F, -16, 1.6F, 16)
            .hit(1.0F).done());
        // In the saddle: the reins in both hands, a bob with the horse's stride.
        put(Act.RIDE, loop(0.7F)
            .r(RA, 0, -40, -14, 0, 0.35F, -44, -14, 0, 0.7F, -40, -14, 0)
            .r(LA, 0, -40, 14, 0, 0.35F, -44, 14, 0, 0.7F, -40, 14, 0)
            .low(0, 0, 0.35F, -0.8F, 0.7F, 0)
            .lean(0, 6, 0.7F, 6)
            .done());
        // Treading water: the arms sculling out in front and to the sides, the legs kicking.
        put(Act.SWIM, loop(1.2F)
            .r(RA, 0, -60, 0, 30, 0.6F, -40, 0, 60, 1.2F, -60, 0, 30)
            .r(LA, 0, -60, 0, -30, 0.6F, -40, 0, -60, 1.2F, -60, 0, -30)
            .r(RL, 0, 20, 0, 0, 0.3F, -20, 0, 0, 0.6F, 20, 0, 0, 0.9F, -20, 0, 0, 1.2F, 20, 0, 0)
            .r(LL, 0, -20, 0, 0, 0.3F, 20, 0, 0, 0.6F, -20, 0, 0, 0.9F, 20, 0, 0, 1.2F, -20, 0, 0)
            .r(HEAD, 0, -20, 0, 0, 1.2F, -20, 0, 0)
            .done());
        // On guard behind the shield: the shield up before the chest, the blade held back and low, the feet apart.
        put(Act.BLOCK, loop(2.0F)
            .r(LA, 0, -82, 40, 0, 1.0F, -86, 40, 0, 2.0F, -82, 40, 0)
            .r(RA, 0, -24, 0, 12, 1.0F, -28, 0, 12, 2.0F, -24, 0, 12)
            .r(RL, 0, -16, 0, 6, 2.0F, -16, 0, 6)
            .r(LL, 0, 14, 0, -6, 2.0F, 14, 0, -6)
            .r(HEAD, 0, 6, 0, 0, 2.0F, 6, 0, 0)
            .lean(0, 8, 1.0F, 10, 2.0F, 8)
            .low(0, 1.5F, 1.0F, 1.8F, 2.0F, 1.5F)
            .done());
        // The thrust: the blade drawn back, driven straight out with a lunge, recovered to the guard.
        put(Act.THRUST, loop(1.4F)
            .r(RA, 0, -42, 0, 0, 0.25F, -38, 0, 0, 0.36F, -92, -4, 0, 0.5F, -90, -4, 0, 0.8F, -42, 0, 0, 1.4F, -42, 0, 0)
            .p(RA, 0, 0, 0, 2.0F, 0.25F, 0, 0, 3.0F, 0.36F, 0, 0, -2.0F, 0.8F, 0, 0, 2.0F, 1.4F, 0, 0, 2.0F)
            .r(LA, 0, -56, 30, 0, 1.4F, -56, 30, 0)
            .r(RL, 0, -10, 0, 0, 0.36F, -30, 0, 0, 0.8F, -10, 0, 0, 1.4F, -10, 0, 0)
            .r(LL, 0, 12, 0, 0, 0.36F, 18, 0, 0, 0.8F, 12, 0, 0, 1.4F, 12, 0, 0)
            .twist(0, 6, 0.25F, 10, 0.36F, -14, 0.8F, 6, 1.4F, 6)
            .lean(0, 6, 0.36F, 14, 0.8F, 6, 1.4F, 6)
            .low(0, 1, 0.36F, 2, 0.8F, 1, 1.4F, 1)
            .hit(0.36F).done());
        // A fall: down hard on its seat, arms out behind, a dazed moment, a push up off the ground, and the dust brushed off.
        put(Act.FALL, once(2.6F)
            .r(RA, 0, 30, 0, 40, 0.2F, 34, 0, 30, 1.0F, 30, 0, 24, 1.6F, 20, 0, 10, 2.1F, -10, 0, 4, 2.3F, -40, -24, 0, 2.6F, -10, 0, 0)
            .r(LA, 0, 30, 0, -40, 0.2F, 34, 0, -30, 1.0F, 30, 0, -24, 1.6F, 20, 0, -10, 2.1F, -10, 0, -4, 2.6F, -10, 0, 0)
            .r(RL, 0, -82, 10, 0, 1.3F, -80, 10, 0, 2.0F, -10, 0, 0, 2.6F, 0, 0, 0)
            .r(LL, 0, -82, -10, 0, 1.3F, -78, -10, 0, 2.0F, -20, 0, 0, 2.6F, 0, 0, 0)
            .r(HEAD, 0, -10, 0, 0, 0.5F, 4, 0, 8, 0.8F, 4, 0, -8, 1.1F, 4, 0, 6, 1.6F, 20, 0, 0, 2.4F, 10, 0, 0, 2.6F, 0, 0, 0)
            .lean(0, -18, 0.3F, -12, 1.2F, -10, 1.7F, 30, 2.2F, 6, 2.6F, 0)
            .low(0, 11, 1.3F, 11, 1.8F, 6, 2.2F, 0, 2.6F, 0)
            .done());
        // A wave: the hand up and waggled, the head tilted with it.
        put(Act.WAVE, once(2.0F)
            .r(RA, 0, -10, 0, 0, 0.3F, -164, 0, 14, 0.5F, -164, 0, 42, 0.7F, -164, 0, 12, 0.9F, -164, 0, 42, 1.1F, -164, 0, 12,
                1.3F, -164, 0, 42, 1.5F, -164, 0, 14, 2.0F, -10, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.4F, -4, 0, 6, 1.5F, -4, 0, 6, 2.0F, 0, 0, 0)
            .twist(0, 0, 0.4F, -4, 1.5F, -4, 2.0F, 0)
            .done());
        // A bow: one hand to the breast, the other behind the back, bent from the hips, held, and up.
        put(Act.BOW, once(2.2F)
            .r(RA, 0, -10, 0, 0, 0.4F, -44, -32, -30, 1.6F, -44, -32, -30, 2.2F, -10, 0, 0)
            .r(LA, 0, 0, 0, 0, 0.4F, 30, 0, -10, 1.6F, 30, 0, -10, 2.2F, 0, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.7F, 22, 0, 0, 1.3F, 22, 0, 0, 1.9F, 0, 0, 0, 2.2F, 0, 0, 0)
            .lean(0, 0, 0.3F, -2, 0.7F, 40, 1.3F, 40, 1.8F, -2, 2.2F, 0)
            .done());
        // A shrug: the shoulders up, the hands out palm up, the head on one side; and down.
        put(Act.SHRUG, once(1.5F)
            .r(RA, 0, -10, 0, 0, 0.35F, -46, 24, 22, 0.95F, -46, 24, 22, 1.5F, -10, 0, 0)
            .r(LA, 0, -10, 0, 0, 0.35F, -46, -24, -22, 0.95F, -46, -24, -22, 1.5F, -10, 0, 0)
            .p(RA, 0, 0, 0, 0, 0.35F, 0, -1.6F, 0, 0.95F, 0, -1.6F, 0, 1.5F, 0, 0, 0)
            .p(LA, 0, 0, 0, 0, 0.35F, 0, -1.6F, 0, 0.95F, 0, -1.6F, 0, 1.5F, 0, 0, 0)
            .p(HEAD, 0, 0, 0, 0, 0.35F, 0, 0.8F, 0, 0.95F, 0, 0.8F, 0, 1.5F, 0, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.35F, -4, 0, 14, 0.95F, -4, 0, 14, 1.5F, 0, 0, 0)
            .done());
        // A salute: the hand to the brow, stiff, held; and down.
        put(Act.SALUTE, once(1.6F)
            .r(RA, 0, -10, 0, 0, 0.25F, -132, -42, 0, 1.2F, -132, -42, 0, 1.6F, -10, 0, 0)
            .r(LA, 0, 0, 0, 0, 1.6F, 0, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.25F, -6, 0, 0, 1.2F, -6, 0, 0, 1.6F, 0, 0, 0)
            .lean(0, 0, 0.25F, -3, 1.2F, -3, 1.6F, 0)
            .done());
        // A point: the arm straight out at the spot, held, the head following it.
        put(Act.POINT, once(1.8F)
            .r(RA, 0, -10, 0, 0, 0.35F, -92, 0, 0, 1.4F, -92, 0, 0, 1.8F, -10, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.35F, 2, 0, 0, 1.4F, 2, 0, 0, 1.8F, 0, 0, 0)
            .lean(0, 0, 0.35F, 4, 1.4F, 4, 1.8F, 0)
            .done());
        // "Come on": the arm out, the hand curled back toward itself again and again, a nod the way to go.
        put(Act.BECKON, once(2.0F)
            .r(RA, 0, -10, 0, 0, 0.3F, -72, -10, 0, 0.5F, -112, -10, 0, 0.7F, -72, -10, 0, 0.9F, -112, -10, 0, 1.1F, -72, -10, 0,
                1.3F, -112, -10, 0, 1.6F, -72, -10, 0, 2.0F, -10, 0, 0)
            .r(HEAD, 0, 0, 0, 0, 0.5F, 8, 0, 6, 0.9F, 0, 0, 6, 1.3F, 8, 0, 6, 2.0F, 0, 0, 0)
            .twist(0, 0, 0.5F, -10, 1.6F, -10, 2.0F, 0)
            .done());
        // Thinking: a hand to the chin, the other arm across under its elbow, eyes up, a slow look about.
        put(Act.THINK, loop(4.0F)
            .r(RA, 0, -112, -40, -20, 1.0F, -112, -40, -18, 1.1F, -112, -40, -22, 1.2F, -112, -40, -18, 4.0F, -112, -40, -20)
            .r(LA, 0, -60, 46, 0, 4.0F, -60, 46, 0)
            .r(HEAD, 0, -8, -12, 0, 2.0F, -10, 14, 4, 4.0F, -8, -12, 0)
            .done());
        // A kneel on one knee, a hand on the raised knee.
        put(Act.KNEEL, loop(4.0F)
            .r(RA, 0, -42, -10, 0, 4.0F, -42, -10, 0)
            .r(LA, 0, 0, 0, -4, 4.0F, 0, 0, -4)
            .r(RL, 0, -84, 6, 0, 4.0F, -84, 6, 0)
            .r(LL, 0, 54, -4, 0, 4.0F, 54, -4, 0)
            .r(HEAD, 0, 12, 0, 0, 4.0F, 12, 0, 0)
            .lean(0, 4, 4.0F, 4)
            .low(0, 7, 4.0F, 7)
            .done());
        // A quarrel: a finger jabbed at the other one, a hand on the hip, a shake of the head.
        put(Act.QUARREL, loop(1.8F)
            .r(RA, 0, -40, 0, 0, 0.3F, -92, -6, 0, 0.45F, -86, -6, 0, 0.6F, -94, -6, 0, 0.9F, -40, 0, 0, 1.8F, -40, 0, 0)
            .r(LA, 0, 10, 0, -34, 1.8F, 10, 0, -34)
            .r(HEAD, 0, 0, 0, 0, 0.3F, 6, 0, 0, 1.0F, 0, 12, 0, 1.2F, 0, -12, 0, 1.4F, 0, 10, 0, 1.8F, 0, 0, 0)
            .lean(0, 2, 0.3F, 10, 0.9F, 2, 1.8F, 2)
            .done());
        // A protest: a fist punched at the sky, again and again.
        put(Act.PROTEST, loop(1.2F)
            .r(RA, 0, -140, 0, 6, 0.3F, -176, 0, 6, 0.6F, -140, 0, 6, 0.9F, -176, 0, 6, 1.2F, -140, 0, 6)
            .r(HEAD, 0, -14, 0, 0, 1.2F, -14, 0, 0)
            .low(0, 0, 0.3F, -0.6F, 0.6F, 0, 0.9F, -0.6F, 1.2F, 0)
            .done());
        // Reading: the book open in both hands, a page turned now and then.
        put(Act.READ, loop(4.0F)
            .r(RA, 0, -68, -20, 0, 3.0F, -68, -20, 0, 3.3F, -74, -40, 0, 3.6F, -68, -20, 0, 4.0F, -68, -20, 0)
            .r(LA, 0, -68, 20, 0, 4.0F, -68, 20, 0)
            .r(HEAD, 0, 30, -4, 0, 1.5F, 30, 4, 0, 3.0F, 30, -4, 0, 4.0F, 30, -4, 0)
            .done());
        // Sat on the ground: the legs out in front, leaning back on the hands.
        put(Act.SIT_GROUND, loop(4.0F)
            .r(RA, 0, 26, 0, 16, 4.0F, 26, 0, 16)
            .r(LA, 0, 26, 0, -16, 4.0F, 26, 0, -16)
            .r(RL, 0, -88, 8, 0, 4.0F, -88, 8, 0)
            .r(LL, 0, -88, -8, 0, 4.0F, -88, -8, 0)
            .r(HEAD, 0, -4, 0, 0, 2.0F, -6, 8, 0, 4.0F, -4, 0, 0)
            .lean(0, -12, 4.0F, -12)
            .low(0, 11, 4.0F, 11)
            .done());
        // Lost: a look this way and that, a scratch of the head.
        put(Act.LOST, loop(3.2F)
            .r(RA, 0, -10, 0, 0, 1.3F, -10, 0, 0, 1.6F, -150, -24, 0, 1.75F, -146, -24, 0, 1.9F, -150, -24, 0, 2.05F, -146, -24, 0, 2.4F, -10, 0, 0, 3.2F, -10, 0, 0)
            .r(HEAD, 0, 0, -44, 0, 0.8F, 0, 44, 0, 1.6F, 6, 0, 6, 2.4F, 0, -30, 0, 3.2F, 0, -44, 0)
            .done());
        for (Act a : Act.values()) {
            if (CLIPS[a.ordinal()] == null && a != Act.NONE) throw new IllegalStateException("no clip for " + a);
        }
    }

    private static void put(Act a, Clip c) {
        CLIPS[a.ordinal()] = c;
    }

    // ------------------------------------------------------------------ putting a clip on a body

    /** The kept vector every key is worked out into (the render thread only). */
    private static final Vector3f V = new Vector3f();
    /** A third of a second either way: an act blended in, the one before blended out. */
    private static final float BLEND = 6.0F;

    /** The six parts of a body, in the bones' order: a folk's (FolkModel) or a player's (EmotePlayerModel). */
    public static final class Rig {
        final ModelPart[] parts;
        final float[] virt = new float[3];

        public Rig(ModelPart head, ModelPart body, ModelPart ra, ModelPart la, ModelPart rl, ModelPart ll) {
            parts = new ModelPart[]{head, body, ra, la, rl, ll};
        }
    }

    /** How a body keeps time and carries itself in its work: speed, phase (ms), and how far it leans into it. */
    public static final class Style {
        public float speed = 1.0F, lean = 1.0F, bounce = 1.0F;
        public long phase;
        public boolean mirror;
        /** Its arm swinging at something just now (a blow in a fight): the clip leaves the main arm to it. */
        public boolean keepMain;
    }

    private static final Style STYLE = new Style();

    /** A folk's: from FolkModel.setupAnim, after its walk, its sit and its manner's pose. */
    public static void apply(Rig rig, VillageFolkEntity folk, float age, boolean rightHanded, boolean young, float attackTime) {
        FolkAction.Seen s = folk.animSeen();
        if (s.act == 0 && (s.prev == 0 || age - s.changed >= BLEND)) return;
        Style st = style(folk, rightHanded, young);
        Act act = Act.of(s.act);
        st.keepMain = attackTime > 0.0F && (act == Act.THRUST || act == Act.BLOCK || act == Act.WATCH);
        play(rig, s, age, st);
    }

    /** Where in its clip a folk is at a tick (for the effects: the moment the hammer lands). */
    public static float secondsFor(VillageFolkEntity folk, Clip c, float age) {
        Style st = style(folk, true, folk.isBaby());
        return seconds(c, age - folk.animSeen().start, st);
    }

    private static Style style(VillageFolkEntity folk, boolean rightHanded, boolean young) {
        int manner = folk.clientManner();
        int gait = Manner.gaitOf(manner);
        int h = folk.getUUID().hashCode();
        Style st = STYLE;
        st.mirror = !rightHanded;
        st.phase = (h & 0x3FF) * 3L;                                  // up to three seconds out of step with the next
        st.speed = 0.93F + ((h >>> 10) & 15) / 100.0F;                // 0.93 to 1.08 of the beat
        st.lean = 1.0F;
        st.bounce = 1.0F;
        if (gait == Manner.TIRED) { st.speed *= 0.82F; st.lean = 1.3F; }
        else if (gait == Manner.OLD) { st.speed *= 0.78F; st.lean = 1.15F; }
        else if (gait == Manner.PROUD) { st.lean = 0.65F; }
        if (young || gait == Manner.SKIP) { st.speed *= 1.15F; st.bounce = 1.35F; }
        st.keepMain = false;
        return st;
    }

    /**
     * The act (and the one before it, while it fades) put on the rig. The one blended out runs on from where it was, the
     * new one from its start; both at the body's own speed and, looping, its own phase.
     */
    public static void play(Rig rig, FolkAction.Seen s, float age, Style st) {
        float w = Mth.clamp((age - s.changed) / BLEND, 0.0F, 1.0F);
        w = w * w * (3.0F - 2.0F * w);                                // eased in and out
        Clip cur = s.act == 0 ? null : CLIPS[s.act];
        Clip prev = s.prev == 0 || w >= 1.0F ? null : CLIPS[s.prev];
        ModelPart[] parts = rig.parts;
        // The pose under the clip, faded out of the limbs it moves (the head keeps its look: the clip adds to it).
        for (int b = BODY; b <= LL; b++) {
            float d = Math.max(cur != null && cur.moves[b] ? w : 0.0F, prev != null && prev.moves[b] ? 1.0F - w : 0.0F);
            if (d <= 0.0F) continue;
            if (st.keepMain && b == (st.mirror ? LA : RA)) continue;
            ModelPart p = parts[b];
            float k = 1.0F - d;
            p.xRot *= k;
            p.yRot *= k;
            p.zRot *= k;
        }
        float[] virt = rig.virt;
        virt[0] = virt[1] = virt[2] = 0.0F;
        if (prev != null) eval(prev, seconds(prev, age - s.prevStart, st), 1.0F - w, rig, st);
        if (cur != null) eval(cur, seconds(cur, age - s.start, st), w, rig, st);
        whole(parts, virt[0] * st.lean, virt[1] * (virt[1] < 0 ? st.bounce : 1.0F), virt[2]);
    }

    /** Where in its clip a body is, so many ticks after the act began. */
    public static float seconds(Clip c, float ticks, Style st) {
        if (c.loop) {
            float t = (ticks * 50.0F * st.speed + st.phase) / 1000.0F;
            return t % c.length;
        }
        // Played once; if the act is held longer than the clip (a staged folk, /village anim play), again after a breath.
        float t = ticks / 20.0F;
        return t <= c.length ? t : Math.min(c.length, (t - c.length) % (c.length + 0.6F));
    }

    private static void eval(Clip c, float sec, float scale, Rig rig, Style st) {
        ModelPart[] parts = rig.parts;
        for (int i = 0; i < c.bone.length; i++) {
            Keyframe[] k = c.keys[i];
            int a = 0;
            while (a + 1 < k.length && k[a + 1].timestamp() <= sec) a++;
            int b = Math.min(k.length - 1, a + 1);
            float alpha = b == a ? 0.0F : Mth.clamp((sec - k[a].timestamp()) / (k[b].timestamp() - k[a].timestamp()), 0.0F, 1.0F);
            k[b].interpolation().apply(V, alpha, k, a, b, scale);
            int bone = st.mirror ? MIRROR[c.bone[i]] : c.bone[i];
            if (bone >= LEAN) {
                rig.virt[bone - LEAN] += bone == TWIST && st.mirror ? -V.x : V.x;
                continue;
            }
            if (st.keepMain && bone == (st.mirror ? LA : RA)) continue;
            ModelPart p = parts[bone];
            if (c.rot[i]) {
                p.xRot += V.x;
                p.yRot += st.mirror ? -V.y : V.y;
                p.zRot += st.mirror ? -V.z : V.z;
            } else {
                p.x += st.mirror ? -V.x : V.x;
                p.y += V.y;
                p.z += V.z;
            }
        }
    }

    /** The whole body: twisted at the waist, leant from the hips, lowered at the knees. */
    private static void whole(ModelPart[] parts, float lean, float lower, float twist) {
        ModelPart head = parts[HEAD], body = parts[BODY], ra = parts[RA], la = parts[LA];
        if (twist != 0.0F) {
            float c = Mth.cos(twist), sn = Mth.sin(twist);
            body.yRot += twist;
            head.yRot += twist * 0.5F;
            ra.x += 5.0F - 5.0F * c;
            ra.z += 5.0F * sn;
            la.x -= 5.0F - 5.0F * c;
            la.z -= 5.0F * sn;
            ra.yRot += twist;
            la.yRot += twist;
        }
        if (lean != 0.0F) {
            float c = 1.0F - Mth.cos(lean), sn = Mth.sin(lean);
            body.xRot += lean;
            body.y += 12.0F * c;
            body.z -= 12.0F * sn;
            head.y += 12.0F * c;
            head.z -= 12.0F * sn;
            ra.y += 10.0F * c;
            ra.z -= 10.0F * sn;
            la.y += 10.0F * c;
            la.z -= 10.0F * sn;
        }
        if (lower != 0.0F) {
            head.y += lower;
            body.y += lower;
            ra.y += lower;
            la.y += lower;
        }
    }
}
