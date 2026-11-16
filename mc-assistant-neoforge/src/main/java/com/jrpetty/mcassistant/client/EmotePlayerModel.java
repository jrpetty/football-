package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.FolkAction;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * [anim] The game's own player model, with the player's emote put on after its own pose: the same clips as the folk's
 * (FolkAnimations), on the same six parts, blended in and out; then the sleeves, trousers, jacket and hat put back over
 * the limbs they cover, as the game does. A player not emoting is posed exactly as the game poses it.
 */
public final class EmotePlayerModel extends PlayerModel<AbstractClientPlayer> {

    private final FolkAnimations.Rig rig;

    public EmotePlayerModel(ModelPart root, boolean slim) {
        super(root, slim);
        this.rig = new FolkAnimations.Rig(head, body, rightArm, leftArm, rightLeg, leftLeg);
    }

    @Override
    public void setupAnim(AbstractClientPlayer p, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
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
    }
}
