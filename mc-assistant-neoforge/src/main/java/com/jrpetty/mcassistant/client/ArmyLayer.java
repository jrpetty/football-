package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * [war] A fighter in the field (entity/Armies, sent as VillageFolkEntity.clientWar and clientSurcoat): a surcoat of
 * its town's arms over its armour, front and back, so a line reads as one army from a hill; the Captain's long
 * cloak of the arms down its back; a sergeant's sash in the arms' field colour; the medic's white sash. Nothing is
 * made a frame: the cloth is the surcoat stack the server sent, read as it is.
 */
public class ArmyLayer extends RenderLayer<VillageFolkEntity, FolkModel> {

    private static final ResourceLocation KIT = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/folk/watch_kit.png");
    /** The surcoat's cloth against a banner's (a touch narrower than the festival tabard) and how far out it hangs, over armour. */
    private static final float CLOTH = 0.29F, OUT = 3.3F;
    /** The Captain's cloak: wider and longer, hung from the shoulders down the back. */
    private static final float CLOAK = 0.42F;
    /** The bits of VillageFolkEntity.clientWar (Armies.flags): role + 1, rank, down. */
    private static final int ROLE = 7, RANK_SHIFT = 3, RANK = 3, DOWN = 1 << 5;
    private static final int MEDIC = 5, SERGEANT = 1, CAPTAIN = 3;

    private final ModelPart flag;
    private final WatchModel kit;

    public ArmyLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, EntityModelSet models, WatchModel kit) {
        super(parent);
        this.flag = models.bakeLayer(ModelLayers.BANNER).getChild("flag");
        this.kit = kit;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, VillageFolkEntity folk, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        int war = folk.clientWar();
        if (war == 0 || folk.isInvisible() || folk.isBaby()) return;
        ItemStack coat = folk.clientSurcoat();
        DyeColor field = coat.getOrDefault(DataComponents.BASE_COLOR, DyeColor.WHITE);
        BannerPatternLayers arms = coat.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        int role = war & ROLE, rank = (war >> RANK_SHIFT) & RANK;
        if (role == MEDIC) {
            sash(pose, buffers, light, folk, 0xFFF2F2F2);
            return;
        }
        flag.xRot = 0.0F;
        flag.y = 0.0F;
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            getParentModel().body().translateAndRotate(pose);
            if (side == 1) pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            pose.translate(0.0F, 0.0F, (-OUT + CLOTH) / 16.0F);
            pose.scale(CLOTH, CLOTH, CLOTH);
            BannerRenderer.renderPatterns(pose, buffers, light, OverlayTexture.NO_OVERLAY, flag, ModelBakery.BANNER_BASE, true, field, arms);
            pose.popPose();
        }
        if (rank == CAPTAIN && (war & DOWN) == 0) {
            pose.pushPose();
            getParentModel().body().translateAndRotate(pose);
            pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            // A little out from the back and swinging with the walk.
            pose.translate(0.0F, -0.02F, (-OUT - 0.6F) / 16.0F);
            pose.mulPose(Axis.XP.rotationDegrees(-6.0F - Math.min(20.0F, limbSwingAmount * 25.0F)));
            pose.scale(CLOAK, CLOAK * 1.15F, CLOAK * 0.5F);
            BannerRenderer.renderPatterns(pose, buffers, light, OverlayTexture.NO_OVERLAY, flag, ModelBakery.BANNER_BASE, true, field, arms);
            pose.popPose();
        }
        if (rank == SERGEANT) sash(pose, buffers, light, folk, 0xFF000000 | field.getTextureDiffuseColor());
    }

    private void sash(PoseStack pose, MultiBufferSource buffers, int light, VillageFolkEntity folk, int colour) {
        kit.follow(getParentModel());
        VertexConsumer to = buffers.getBuffer(RenderType.entityCutoutNoCull(KIT));
        WatchModel.draw(kit.body, kit.sash, pose, to, light, LivingEntityRenderer.getOverlayCoords(folk, 0.0F), colour);
    }
}
