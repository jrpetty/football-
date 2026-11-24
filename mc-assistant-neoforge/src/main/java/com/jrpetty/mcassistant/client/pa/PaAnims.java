package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.FolkAnimations;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;

import java.util.HashMap;
import java.util.Map;

/**
 * [pa33c-2b] Its gestures, with the village folk's own clips (FolkAnimations): a neutral helper, no village state. The
 * server says which (an act and a turn count, so the same one twice plays twice); while it talks with nothing else on,
 * its hands talk too. Its personality shows in how it stands: a gloomy one's head hangs a little, a funny one's tilts
 * this way and that, a serious one stands straight.
 */
public final class PaAnims {

    private PaAnims() {}

    private static final Map<Integer, FolkAction.Seen> SEEN = new HashMap<>();
    private static final Map<Integer, Integer> LAST = new HashMap<>();
    private static final FolkAnimations.Style STYLE = new FolkAnimations.Style();

    /** After the model's own pose: the gesture blended over it, then its bearing. */
    static void apply(HumanoidModel<PlayerAssistantEntity> m, FolkAnimations.Rig rig, PlayerAssistantEntity a, float age) {
        int v = a.animValue();
        Integer was = LAST.put(a.getId(), v);
        FolkAction.Seen s = SEEN.computeIfAbsent(a.getId(), k -> new FolkAction.Seen());
        int act = v & 0xFF;
        boolean talking = PaClient.BUBBLES.containsKey(a.getId()) && PaClient.bubble(a.getId(), a.level().getGameTime()) != null;
        int want = act != 0 ? act : talking && !a.isAggressive() && a.walkAnimation.speed() < 0.2F ? FolkAction.Act.TALK.ordinal() : 0;
        if ((was != null && was != v && act != 0) || want != s.act) s.begin(want, a.tickCount);
        // [pa33g] a bow drawn, a crossbow, a shield up or gliding: the pose is the game's, and no gesture or talking hands break it
        boolean busyArms = a.isUsingItem() || a.isFallFlying() || m.rightArmPose.isTwoHanded() || m.leftArmPose.isTwoHanded()
            || m.rightArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD || m.leftArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD;
        if (!busyArms && !(m.crouching || m.riding) && (s.act != 0 || age - s.changed <= 8)) {
            STYLE.mirror = a.getMainArm() == HumanoidArm.LEFT;
            STYLE.speed = 1.0F;
            STYLE.phase = a.getId() * 7919L;
            STYLE.lean = 1.0F;
            STYLE.bounce = 1.0F;
            STYLE.keepMain = m.attackTime > 0.0F;
            FolkAnimations.play(rig, s, age, STYLE);
        }
        if (!busyArms) bearing(m, a, age);
        if (m instanceof PlayerModel<?> pm) {
            pm.hat.copyFrom(pm.head);
            pm.jacket.copyFrom(pm.body);
            pm.leftSleeve.copyFrom(pm.leftArm);
            pm.rightSleeve.copyFrom(pm.rightArm);
            pm.leftPants.copyFrom(pm.leftLeg);
            pm.rightPants.copyFrom(pm.rightLeg);
        } else {
            m.hat.copyFrom(m.head);
        }
    }

    /** Its personality in how it stands (only its own player's panel knows the mix: others see it neutral). */
    private static void bearing(HumanoidModel<?> m, PlayerAssistantEntity a, float age) {
        PlayerAssistantEntity mine = PaClient.mine();
        if (mine == null || mine.getId() != a.getId()) return;
        var set = PaClient.state.getCompound("set");
        if (!set.contains("funny")) return;
        float funny = set.getFloat("funny"), gloomy = set.getFloat("gloomy");
        m.head.xRot += 0.18F * gloomy;
        m.head.zRot += 0.07F * funny * (float) Math.sin(age * 0.045F + a.getId());
        if (gloomy > 0.5F && a.walkAnimation.speed() < 0.1F) { m.rightArm.xRot *= 0.6F; m.leftArm.xRot *= 0.6F; }
    }

    static void reset(ModelPart... parts) {
        for (ModelPart p : parts) p.resetPose();
    }

    /** The player-shaped body with its gestures. */
    static final class Player extends PlayerModel<PlayerAssistantEntity> {
        private final FolkAnimations.Rig rig;
        private final ModelPart[] all;

        Player(ModelPart root) {
            super(root, false);
            rig = new FolkAnimations.Rig(head, body, rightArm, leftArm, rightLeg, leftLeg);
            all = new ModelPart[]{head, hat, body, jacket, rightArm, leftArm, rightSleeve, leftSleeve, rightLeg, leftLeg, rightPants, leftPants};
        }

        @Override
        public void setupAnim(PlayerAssistantEntity a, float limbSwing, float amount, float age, float yaw, float pitch) {
            reset(all);
            super.setupAnim(a, limbSwing, amount, age, yaw, pitch);
            apply(this, rig, a, age);
        }
    }

    /** The skeleton, zombie and enderman bodies, with the same gestures. */
    static final class Body extends HumanoidModel<PlayerAssistantEntity> {
        private final FolkAnimations.Rig rig;
        private final ModelPart[] all;

        Body(ModelPart root) {
            super(root);
            rig = new FolkAnimations.Rig(head, body, rightArm, leftArm, rightLeg, leftLeg);
            all = new ModelPart[]{head, hat, body, rightArm, leftArm, rightLeg, leftLeg};
        }

        @Override
        public void setupAnim(PlayerAssistantEntity a, float limbSwing, float amount, float age, float yaw, float pitch) {
            reset(all);
            super.setupAnim(a, limbSwing, amount, age, yaw, pitch);
            apply(this, rig, a, age);
        }
    }
}
