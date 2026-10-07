package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.Individual;
import com.jrpetty.mcassistant.entity.Manner;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;

/**
 * [individual] How a folk carries itself, on the model (FolkModel.setupAnim): from the one number the server sends
 * (Manner.pack) and its marks (the stoop of its years). All of it worked out from the pose the model is already in,
 * every frame, with no state of its own.
 *
 * <ul>
 * <li>A child skips, a little hop in every step and its arms up; the old stoop (more in their nineties), take short
 *     steps and plant the stick ahead of them; the proud stride with their chins up; the shy keep their heads down
 *     and their arms in; the tired drag their feet with their arms hanging.</li>
 * <li>A miserable folk's head hangs; a happy one, stood about, looks this way and that.</li>
 * <li>The moments: a stretch, a yawn behind a hand, a scratch of the head, arms folded, a wave, a laugh, a foot
 *     tapped, a pipe to the lips, a book held up to read, a knife and a stick in its hands.</li>
 * </ul>
 */
public final class FolkPoses {

    private FolkPoses() {}

    public static void apply(FolkModel m, VillageFolkEntity folk, float limbSwing, float swing, float age, boolean young) {
        int manner = folk.clientManner();
        int gait = Manner.gaitOf(manner), posture = Manner.postureOf(manner), idle = Manner.idleOf(manner);
        int stoop = young ? 0 : Individual.stoopOf(folk.clientMarks());
        ModelPart head = m.getHead(), body = m.body(), ra = m.rightArm(), la = m.leftArm(), rl = m.rightLeg(), ll = m.leftLeg();
        boolean sitting = folk.getPose() == Pose.SITTING || folk.getPose() == Pose.CROUCHING;
        boolean rightHanded = folk.getMainArm() == HumanoidArm.RIGHT;
        ModelPart main = rightHanded ? ra : la, off = rightHanded ? la : ra;
        float side = rightHanded ? 1.0F : -1.0F;
        boolean working = m.attackTime > 0.0F;

        if (!sitting && !working) {
            switch (gait) {
                case Manner.SKIP -> {
                    float hop = Math.abs(Mth.sin(limbSwing * 0.6662F)) * swing * 1.6F;
                    for (ModelPart p : new ModelPart[]{head, body, ra, la, rl, ll}) p.y -= hop;
                    ra.xRot *= 1.35F;
                    la.xRot *= 1.35F;
                    ra.zRot += swing * 0.25F;
                    la.zRot -= swing * 0.25F;
                }
                case Manner.OLD -> {
                    rl.xRot *= 0.55F;
                    ll.xRot *= 0.55F;
                    ra.xRot *= 0.5F;
                    la.xRot *= 0.5F;
                }
                case Manner.PROUD -> {
                    head.xRot -= 0.12F;
                    body.xRot -= 0.04F;
                    ra.xRot *= 1.25F;
                    la.xRot *= 1.25F;
                }
                case Manner.SHY -> {
                    head.xRot += 0.32F;
                    ra.xRot *= 0.6F;
                    la.xRot *= 0.6F;
                    ra.zRot -= 0.05F;
                    la.zRot += 0.05F;
                }
                case Manner.TIRED -> {
                    head.xRot += 0.18F;
                    ra.xRot *= 0.35F;
                    la.xRot *= 0.35F;
                    rl.xRot *= 0.7F;
                    ll.xRot *= 0.7F;
                }
                default -> { }
            }
        }
        // The stoop of great age: bent forward at the neck of the back, the hips back, the head carried low and forward.
        if (stoop > 0 && !sitting) {
            float k = 0.12F + 0.07F * stoop;
            body.xRot += k;
            rl.z += k * 9.0F;
            ll.z += k * 9.0F;
            head.y += k * 3.0F;
            head.z -= k * 4.0F;
            head.xRot -= k * 0.6F;
            ra.y += k * 2.0F;
            la.y += k * 2.0F;
            ra.z -= k * 2.5F;
            la.z -= k * 2.5F;
        }
        // The stick, planted ahead in its other hand.
        if (Individual.stickOf(folk.clientMarks()) && !sitting && folk.getOffhandItem().isEmpty()) {
            off.xRot = -0.32F + Mth.cos(limbSwing * 0.6662F) * swing * 0.15F;
            off.zRot = 0.0F;
        }
        // The mood, in how it holds its head.
        if (posture == Manner.SAD) {
            head.xRot = Math.min(0.85F, head.xRot + 0.42F);
            ra.zRot *= 0.4F;
            la.zRot *= 0.4F;
        } else if (posture == Manner.HAPPY && swing < 0.1F && idle == Manner.NO_IDLE) {
            head.yRot += Mth.sin(age * 0.045F) * 0.45F;
            head.xRot -= 0.06F;
        }
        if (working) return;
        switch (idle) {
            case Manner.STRETCH -> {
                ra.xRot = -2.9F;
                la.xRot = -2.9F;
                ra.zRot = 0.28F + Mth.sin(age * 0.2F) * 0.05F;
                la.zRot = -0.28F - Mth.sin(age * 0.2F) * 0.05F;
                body.xRot -= 0.06F;
                head.xRot = -0.3F;
            }
            case Manner.YAWN -> {
                main.xRot = -2.15F;
                main.yRot = -0.5F * side;
                main.zRot = 0.0F;
                head.xRot = -0.35F;
            }
            case Manner.SCRATCH -> {
                main.xRot = -2.55F + Mth.sin(age * 0.9F) * 0.08F;
                main.zRot = -0.35F * side;
                main.yRot = 0.0F;
                head.xRot = Math.max(head.xRot, 0.1F);
                head.zRot = 0.1F * side;
            }
            case Manner.CROSSED -> {
                ra.xRot = -1.0F;
                ra.yRot = -0.62F;
                ra.zRot = 0.0F;
                la.xRot = -1.05F;
                la.yRot = 0.62F;
                la.zRot = 0.0F;
                head.xRot -= 0.05F;
            }
            case Manner.WAVE -> {
                main.xRot = -2.75F;
                main.yRot = 0.0F;
                main.zRot = (-0.2F + Mth.sin(age * 0.65F) * 0.38F) * side;
            }
            case Manner.LAUGH -> {
                head.xRot = -0.32F + Mth.sin(age * 1.3F) * 0.08F;
                body.zRot = Mth.sin(age * 1.5F) * 0.03F;
                off.xRot = -0.55F;
                off.yRot = 0.45F * side;
            }
            case Manner.TAP -> {
                ModelPart foot = rightHanded ? rl : ll;
                foot.xRot = -0.22F * Math.max(0.0F, Mth.sin(age * 0.5F));
                head.xRot += Mth.sin(age * 0.5F) * 0.06F;
            }
            case Manner.PIPE -> {
                boolean puff = Mth.sin(age * 0.06F) > 0.45F;
                main.xRot = puff ? -1.75F : sitting ? -0.62F : main.xRot;
                main.yRot = puff ? -0.45F * side : main.yRot;
            }
            case Manner.READ -> {
                ra.xRot = -1.0F;
                la.xRot = -1.0F;
                ra.yRot = -0.22F;
                la.yRot = 0.22F;
                head.xRot = 0.42F;
            }
            case Manner.WHITTLE -> {
                ra.xRot = -0.85F + Mth.sin(age * 0.5F) * 0.12F;
                la.xRot = -0.8F;
                ra.yRot = -0.3F;
                la.yRot = 0.3F;
                head.xRot = 0.45F;
            }
            default -> { }
        }
    }
}
