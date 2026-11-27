package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.pa.PaFishHook;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * [pa38] The assistant's bobber and line, drawn as the game draws a player's: the bobber facing the camera, the line a
 * sagging curve from the rod's tip in its hand (the main hand, or the other if the rod is there) to the float.
 */
public class PaFishHookRenderer extends EntityRenderer<PaFishHook> {

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/fishing_hook.png");
    private static final RenderType TYPE = RenderType.entityCutout(TEXTURE);

    public PaFishHookRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(PaFishHook hook, float yaw, float pt, PoseStack pose, MultiBufferSource buf, int light) {
        if (!(hook.getOwner() instanceof LivingEntity who)) return;
        pose.pushPose();
        pose.pushPose();
        pose.scale(0.5F, 0.5F, 0.5F);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        PoseStack.Pose p = pose.last();
        VertexConsumer vc = buf.getBuffer(TYPE);
        vertex(vc, p, light, 0.0F, 0, 0, 1);
        vertex(vc, p, light, 1.0F, 0, 1, 1);
        vertex(vc, p, light, 1.0F, 1, 1, 0);
        vertex(vc, p, light, 0.0F, 1, 0, 0);
        pose.popPose();
        float swing = Mth.sin(Mth.sqrt(who.getAttackAnim(pt)) * (float) Math.PI);
        Vec3 hand = hand(who, swing, pt);
        Vec3 at = hook.getPosition(pt).add(0.0, 0.25, 0.0);
        float dx = (float) (hand.x - at.x), dy = (float) (hand.y - at.y), dz = (float) (hand.z - at.z);
        VertexConsumer line = buf.getBuffer(RenderType.lineStrip());
        PoseStack.Pose lp = pose.last();
        for (int j = 0; j <= 16; j++) string(dx, dy, dz, line, lp, j / 16F, (j + 1) / 16F);
        pose.popPose();
        super.render(hook, yaw, pt, pose, buf, light);
    }

    /** The rod's tip in its hand, from its body's turn (as the game finds a player's hand in third person). */
    private static Vec3 hand(LivingEntity who, float swing, float pt) {
        int side = who.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        if (!who.getMainHandItem().canPerformAction(net.neoforged.neoforge.common.ItemAbilities.FISHING_ROD_CAST)) side = -side;
        float body = Mth.lerp(pt, who.yBodyRotO, who.yBodyRot) * Mth.DEG_TO_RAD;
        double s = Mth.sin(body), c = Mth.cos(body);
        float scale = who.getScale();
        double across = side * 0.35 * scale, ahead = 0.8 * scale;
        float crouch = who.isCrouching() ? -0.1875F : 0.0F;
        return who.getEyePosition(pt).add(-c * across - s * ahead, crouch - 0.45 * scale, -s * across + c * ahead);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, int light, float x, int y, int u, int v) {
        vc.addVertex(p, x - 0.5F, y - 0.5F, 0.0F).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0.0F, 1.0F, 0.0F);
    }

    private static void string(float dx, float dy, float dz, VertexConsumer vc, PoseStack.Pose p, float a, float b) {
        float x = dx * a, y = dy * (a * a + a) * 0.5F + 0.25F, z = dz * a;
        float nx = dx * b - x, ny = dy * (b * b + b) * 0.5F + 0.25F - y, nz = dz * b - z;
        float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        vc.addVertex(p, x, y, z).setColor(-16777216).setNormal(p, nx / len, ny / len, nz / len);
    }

    @Override
    public ResourceLocation getTextureLocation(PaFishHook hook) {
        return TEXTURE;
    }
}
