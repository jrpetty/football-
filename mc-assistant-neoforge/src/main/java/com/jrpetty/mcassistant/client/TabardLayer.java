package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * [arms] A festival tabard, worn (entity/Arms: lent out of the town's stores on a festival's day): a panel of the
 * banner's cloth hung from the shoulders, front and back, over the folk's own clothes, bearing whatever arms the
 * tabard was given (its field and its charges, drawn as a banner's are). A plain one is the wool's white.
 */
public class TabardLayer extends RenderLayer<VillageFolkEntity, FolkModel> {

    /** The cloth's size against a banner's: six of the body's eight across, its twelve down. */
    private static final float CLOTH = 0.3F;
    /** How far out from the middle of the body each panel hangs, in the model's sixteenths (the coat is just under it). */
    private static final float OUT = 2.6F;

    private final ModelPart flag;

    public TabardLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, EntityModelSet models) {
        super(parent);
        this.flag = models.bakeLayer(ModelLayers.BANNER).getChild("flag");
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, VillageFolkEntity folk, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack worn = folk.getItemBySlot(EquipmentSlot.CHEST);
        if (folk.isInvisible() || !worn.is(McAssistantMod.TABARD.get())) return;
        DyeColor field = worn.getOrDefault(DataComponents.BASE_COLOR, DyeColor.WHITE);
        BannerPatternLayers arms = worn.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        flag.xRot = 0.0F;
        flag.y = 0.0F;
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            getParentModel().body().translateAndRotate(pose);
            if (side == 1) pose.mulPose(Axis.YP.rotationDegrees(180.0F));          // the back panel, its cloth facing back
            // The banner's cloth faces its own -z, as a body's front does; its back just clear of the coat.
            pose.translate(0.0F, 0.0F, (-OUT + CLOTH) / 16.0F);
            pose.scale(CLOTH, CLOTH, CLOTH);
            BannerRenderer.renderPatterns(pose, buffers, light, OverlayTexture.NO_OVERLAY, flag, ModelBakery.BANNER_BASE, true, field, arms);
            pose.popPose();
        }
    }
}
