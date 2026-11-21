package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.FootballEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

/**
 * [leisure] The leather football on the ground: a ball half a block across, rounded (three boxes laid through one
 * another, so it has no square corners from any side), of stitched leather panels with a lace at its mouth, turning
 * as it rolls, the way it rolls (FootballEntity keeps its spin), with a soft shadow under it.
 */
public class FootballRenderer extends EntityRenderer<FootballEntity> {

    public static final ModelLayerLocation LAYER =
        new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "football"), "main");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/football.png");

    private final ModelPart ball;

    public FootballRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.ball = ctx.bakeLayer(LAYER).getChild("ball");
        this.shadowRadius = 0.22F;
        this.shadowStrength = 0.7F;
    }

    /** Drawn at four fifths, so the ten-pixel model is half a block across, its eight-by-eight faces room for the
     *  three strips of leather and their seams. */
    private static final float SCALE = 0.8F;

    /** The ball: ten long by eight by eight, each way, through one another (each box's two ends two of its six faces,
     *  the rest only its rounded edges). */
    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("ball", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-5.0F, -4.0F, -4.0F, 10.0F, 8.0F, 8.0F)
                .texOffs(0, 16).addBox(-4.0F, -5.0F, -4.0F, 8.0F, 10.0F, 8.0F)
                .texOffs(0, 34).addBox(-4.0F, -4.0F, -5.0F, 8.0F, 8.0F, 10.0F),
            PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void render(FootballEntity e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0F, FootballEntity.RADIUS, 0.0F);
        Quaternionf q = new Quaternionf();
        e.spinO.slerp(e.spin, partialTick, q);
        pose.mulPose(q);
        pose.scale(SCALE, SCALE, SCALE);
        ball.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(e, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(FootballEntity e) {
        return TEXTURE;
    }
}
