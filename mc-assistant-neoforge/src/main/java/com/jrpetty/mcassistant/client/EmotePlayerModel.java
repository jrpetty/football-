package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.FolkAction;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * [anim] The game's own player model, with the player's emote put on after its own pose: the same clips as the folk's
 * (FolkAnimations), on the same six parts, blended in and out; then everything worn over a limb put back over it, as the
 * game does: the hat over the head, the jacket over the body, the sleeves and the trousers over the arms and legs, and the
 * cape hung from the shoulders as they lean and turn. A player not emoting is posed exactly as the game poses it.
 *
 * <p>Each frame starts from the parts' own rest. The game's pose sets most of a part's numbers every frame but not all
 * of them (the body's and the head's depth, the head's tilt): an emote's lean and twist moved those, and without the
 * rest put back first they added up frame on frame, the chest drifting off its hips and the head screwing round.
 */
public final class EmotePlayerModel extends PlayerModel<AbstractClientPlayer> {

    private final FolkAnimations.Rig rig;
    private final ModelPart cape;
    private final ModelPart[] limbs;

    public EmotePlayerModel(ModelPart root, boolean slim) {
        super(root, slim);
        this.rig = new FolkAnimations.Rig(head, body, rightArm, leftArm, rightLeg, leftLeg);
        this.cape = root.getChild("cloak");
        this.limbs = new ModelPart[]{head, hat, body, jacket, rightArm, leftArm, rightSleeve, leftSleeve, rightLeg, leftLeg, rightPants, leftPants, cape};
    }

    @Override
    public void setupAnim(AbstractClientPlayer p, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        for (ModelPart part : limbs) part.resetPose();
        super.setupAnim(p, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        FolkAction.Seen s = PlayerEmotesClient.seen(p, ageInTicks);
        if (s == null || crouching || riding) {
            SWING.remove(p.getId());                                   // [anim] the cape's swing starts afresh next time
            return;
        }
        FolkAnimations.play(rig, s, ageInTicks, PlayerEmotesClient.style(p));
        hat.copyFrom(head);
        leftPants.copyFrom(leftLeg);
        rightPants.copyFrom(rightLeg);
        leftSleeve.copyFrom(leftArm);
        rightSleeve.copyFrom(rightArm);
        jacket.copyFrom(body);
        // The cape hangs from the shoulders: it goes where the body goes. The cape layer draws it turned half round
        // (its own frame faces the other way), so its lean and its offsets across and back are the body's reversed.
        cape.x = -body.x;
        cape.y = body.y;
        cape.z -= body.z;
        cape.zRot = -body.zRot;
        // [anim] Cloth, not a board: as the body leans the cape falls part of the way back toward hanging straight down
        // (bowed, it lies along the back and its hem drops behind the knees), and it comes after the body on a spring,
        // a little late and swinging past, as the trunk turns in a dance or bends into a bow and up again.
        float[] w = SWING.computeIfAbsent(p.getId(), k -> new float[]{-body.xRot * FOLLOW, 0.0F, body.yRot, 0.0F, ageInTicks});
        float dt = net.minecraft.util.Mth.clamp((ageInTicks - w[4]) / 20.0F, 0.0F, 0.1F);
        w[4] = ageInTicks;
        w[1] += (STIFF * (-body.xRot * FOLLOW - w[0]) - DAMP * w[1]) * dt;
        w[0] += w[1] * dt;
        w[3] += (STIFF * (body.yRot - w[2]) - DAMP * w[3]) * dt;
        w[2] += w[3] * dt;
        cape.xRot = w[0];
        cape.yRot = w[2];
        // Sat or knelt low, the cape's hem would go through the ground under it: it lies back over the ground instead,
        // swung out behind as far as it must (its length, sixteen pixels, from the shoulders; the game's cape layer
        // hangs it six degrees back to start with).
        float hang = 6.0F * net.minecraft.util.Mth.DEG_TO_RAD - cape.xRot;   // how far back its hem hangs now
        float room = (CAPE_FLOOR - body.y) / 16.0F;
        if (room < 1.0F) {
            float need = room <= -1.0F ? net.minecraft.util.Mth.PI : (float) Math.acos(room);
            if (need > hang) cape.xRot -= need - hang;
        }
    }

    /** How much of the body's lean the cape takes, and its spring: how stiff, how damped (it swings a little past). */
    private static final float FOLLOW = 0.6F, STIFF = 90.0F, DAMP = 9.0F;
    /** Each emoting player's cape: its lean and how fast it is swinging, its turn and how fast, the time of the last frame. */
    private static final java.util.Map<Integer, float[]> SWING = new java.util.HashMap<>();

    /** How far down (model pixels, the feet at 24) the cape's hem may come: a hair over the ground. */
    private static final float CAPE_FLOOR = 23.4F;
}
