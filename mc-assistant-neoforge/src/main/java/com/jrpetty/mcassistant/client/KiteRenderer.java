package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.KiteEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * [leisure] A kite in the sky (KiteEntity): a diamond of paper in its dye's colour on two crossed sticks, facing its
 * flier and leaning back into the wind, rocking a little; a tail of bows streaming and waving under it; and its string,
 * sagging, from the kite down to its flier's hand. Drawn whenever the kite or its flier is in sight, so the string is
 * there to follow up from a child in the park to the kite over the roofs.
 */
public class KiteRenderer extends EntityRenderer<KiteEntity> {

    private static final ResourceLocation CLOTH = tex("kite_cloth"), FRAME = tex("kite_frame"), BOW = tex("kite_bow"), STRING = tex("kite_string");
    /** The kite's size: across, and from its nose to its tail's end. */
    private static final float WIDE = 1.3F, TALL = 1.7F;
    /** Bows on the tail, and how far apart. */
    private static final int BOWS = 6;
    private static final float GAP = 0.42F;

    public KiteRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/" + name + ".png");
    }

    @Override
    public boolean shouldRender(KiteEntity kite, Frustum frustum, double camX, double camY, double camZ) {
        Entity f = kite.flier();
        AABB box = kite.getBoundingBox().inflate(1.5, 4.0, 1.5);
        if (f != null) box = box.minmax(f.getBoundingBox());
        return frustum.isVisible(box);
    }

    @Override
    public void render(KiteEntity kite, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        float t = kite.tickCount + pt;
        float ph = kite.phase();
        int rgb = kite.colour();
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        // A second colour for the bows: the kite's own, turned toward white, so the tail always shows against it.
        int br = Math.min(255, r / 2 + 128), bg = Math.min(255, g / 2 + 128), bb = Math.min(255, b / 2 + 128);
        float facing = Mth.rotLerp(pt, kite.yRotO, kite.getYRot());

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-facing));
        pose.mulPose(Axis.XP.rotationDegrees(-28.0F + Mth.sin(t * 0.09F + ph) * 6.0F));          // leaning back into the wind
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 0.13F + ph * 1.3F) * 9.0F));            // rocking side to side
        PoseStack.Pose p = pose.last();
        // The cloth, in its colour; the sticks and the hem over it, as they are.
        quad(buffers.getBuffer(RenderType.entityCutoutNoCull(CLOTH)), p, -WIDE / 2, -TALL * 0.62F, WIDE / 2, TALL * 0.38F, 0.0F, r, g, b, light);
        quad(buffers.getBuffer(RenderType.entityCutoutNoCull(FRAME)), p, -WIDE / 2, -TALL * 0.62F, WIDE / 2, TALL * 0.38F, -0.01F, 255, 255, 255, light);
        // The tail: from the kite's foot, bows on a string, streaming back and waving, each a little behind the last.
        VertexConsumer bows = buffers.getBuffer(RenderType.entityCutoutNoCull(BOW));
        float footY = -TALL * 0.62F;
        float px = 0.0F, py = footY, pz = 0.0F;
        float[] xs = new float[BOWS + 1], ys = new float[BOWS + 1], zs = new float[BOWS + 1];
        xs[0] = px;
        ys[0] = py;
        zs[0] = pz;
        for (int i = 1; i <= BOWS; i++) {
            float wave = Mth.sin(t * 0.22F - i * 0.75F + ph) * 0.09F * i;
            px = wave;
            py = footY - GAP * i;
            pz = -(0.03F * i * i + Mth.sin(t * 0.17F - i * 0.6F + ph) * 0.05F * i);       // streaming away downwind
            xs[i] = px;
            ys[i] = py;
            zs[i] = pz;
            float s = 0.15F - i * 0.008F;
            quad(bows, p, px - s, py - s * 0.8F, px + s, py + s * 0.8F, pz, br, bg, bb, light);
        }
        line(buffers.getBuffer(RenderType.entityCutoutNoCull(STRING)), p, xs, ys, zs, 0.012F, 235, 228, 210, light);
        pose.popPose();

        // The string, from the kite's foot down to the flier's hand, sagging under its own weight.
        Entity f = kite.flier();
        if (f != null) {
            Vec3 at = kite.getPosition(pt);
            Vec3 hand = hand(f, pt).subtract(at);
            Vec3 foot = new Vec3(0.0, footY * 0.88, 0.0);
            int n = 28;
            float[] sx = new float[n + 1], sy = new float[n + 1], sz = new float[n + 1];
            double len = hand.subtract(foot).length();
            double sag = 0.4 + len * 0.05;
            for (int i = 0; i <= n; i++) {
                double k = i / (double) n;
                sx[i] = (float) Mth.lerp(k, foot.x, hand.x);
                sy[i] = (float) (Mth.lerp(k, foot.y, hand.y) - Math.sin(Math.PI * k) * sag);
                sz[i] = (float) Mth.lerp(k, foot.z, hand.z);
            }
            line(buffers.getBuffer(RenderType.entityCutoutNoCull(STRING)), pose.last(), sx, sy, sz, 0.016F, 240, 236, 225, light);
        }
        super.render(kite, yaw, pt, pose, buffers, light);
    }

    /** Where the flier holds the string: its hand, out a little in front of it. */
    private static Vec3 hand(Entity f, float pt) {
        Vec3 base = f.getPosition(pt);
        float yaw = (f instanceof LivingEntity l ? Mth.rotLerp(pt, l.yBodyRotO, l.yBodyRot) : f.getYRot()) * Mth.DEG_TO_RAD;
        double side = f instanceof LivingEntity l && l.getMainArm() == HumanoidArm.LEFT ? 0.32 : -0.32;
        double scale = f instanceof LivingEntity l && l.isBaby() ? 0.5 : 1.0;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        double rx = Mth.cos(yaw), rz = Mth.sin(yaw);
        return base.add(fx * 0.35 * scale + rx * side * scale, f.getBbHeight() * 0.6, fz * 0.35 * scale + rz * side * scale);
    }

    /** A quad in the pose's x-y plane at depth z, the whole texture on it, tinted, seen from both sides. */
    private static void quad(VertexConsumer vc, PoseStack.Pose p, float x0, float y0, float x1, float y1, float z, int r, int g, int b, int light) {
        vc.addVertex(p, x0, y0, z).setColor(r, g, b, 255).setUv(0.0F, 1.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0.0F, 0.0F, 1.0F);
        vc.addVertex(p, x1, y0, z).setColor(r, g, b, 255).setUv(1.0F, 1.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0.0F, 0.0F, 1.0F);
        vc.addVertex(p, x1, y1, z).setColor(r, g, b, 255).setUv(1.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0.0F, 0.0F, 1.0F);
        vc.addVertex(p, x0, y1, z).setColor(r, g, b, 255).setUv(0.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0.0F, 0.0F, 1.0F);
    }

    /**
     * A string through these points: at each step two thin strips crossed, one across and one up and down, so it shows
     * from any side, as the game draws a lead (but as separate quads, so the tail's string and the kite's never run
     * together).
     */
    private static void line(VertexConsumer vc, PoseStack.Pose p, float[] x, float[] y, float[] z, float w, int r, int g, int b, int light) {
        for (int i = 0; i + 1 < x.length; i++) {
            int shade = i % 2 == 0 ? 0 : 16;
            int cr = r - shade, cg = g - shade, cb = b - shade;
            float v0 = i / (float) x.length, v1 = (i + 1) / (float) x.length;
            vertex(vc, p, x[i] - w, y[i], z[i], 0.0F, v0, cr, cg, cb, light);
            vertex(vc, p, x[i] + w, y[i], z[i], 1.0F, v0, cr, cg, cb, light);
            vertex(vc, p, x[i + 1] + w, y[i + 1], z[i + 1], 1.0F, v1, cr, cg, cb, light);
            vertex(vc, p, x[i + 1] - w, y[i + 1], z[i + 1], 0.0F, v1, cr, cg, cb, light);
            vertex(vc, p, x[i], y[i], z[i] - w, 0.0F, v0, cr, cg, cb, light);
            vertex(vc, p, x[i], y[i], z[i] + w, 1.0F, v0, cr, cg, cb, light);
            vertex(vc, p, x[i + 1], y[i + 1], z[i + 1] + w, 1.0F, v1, cr, cg, cb, light);
            vertex(vc, p, x[i + 1], y[i + 1], z[i + 1] - w, 0.0F, v1, cr, cg, cb, light);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v, int r, int g, int b, int light) {
        vc.addVertex(p, x, y, z).setColor(r, g, b, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(KiteEntity kite) {
        return CLOTH;
    }
}
