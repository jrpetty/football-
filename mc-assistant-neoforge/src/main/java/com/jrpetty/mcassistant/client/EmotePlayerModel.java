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
        if (s == null || crouching || riding) return;
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
        cape.xRot = -body.xRot;
        cape.yRot = body.yRot;
        cape.zRot = -body.zRot;
    }
}
