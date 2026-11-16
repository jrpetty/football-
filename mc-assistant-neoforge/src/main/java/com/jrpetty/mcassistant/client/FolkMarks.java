package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * [anim] The little picture over a folk's head when something happens (FolkAction.Mark): an idea when it founds a
 * business, a coin at a sale, a question when it is lost, a heart for its partner, a note as it sings, a puff of temper, a
 * tear at a grave, a handshake on a deal. It pops up (a bounce a little past full size and back), floats up a touch, and
 * fades. And the Zzz over a folk asleep, drifting up and away. Drawn in a thought bubble so it reads against the sky;
 * the picture is tools/anim_art.py's (textures/entity/folk/marks.png).
 */
public final class FolkMarks {

    private FolkMarks() {}

    private static final ResourceLocation SHEET = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/folk/marks.png");
    private static final int CELLS = 12;

    public static void draw(VillageFolkEntity folk, PoseStack pose, MultiBufferSource buffer, Font font, EntityRenderDispatcher camera,
                            float partial, double far, int light) {
        if (far > 32 * 32 || folk.isInvisible() || !AnimConfig.marks()) return;
        FolkAction.Seen s = folk.animSeen();
        float age = folk.tickCount + partial;
        if (folk.isSleeping() || folk.getPose() == net.minecraft.world.entity.Pose.SLEEPING) {
            zzz(folk, pose, buffer, font, camera, age);
            return;
        }
        float t = age - s.markAt;
        if (s.mark <= 0 || s.mark >= CELLS || t < 0 || t > FolkAction.MARK_TICKS) return;
        float pop = t < 3 ? t / 3.0F * 1.25F : t < 7 ? 1.25F - (t - 3) / 4.0F * 0.25F : 1.0F;
        float fade = t > FolkAction.MARK_TICKS - 10 ? (FolkAction.MARK_TICKS - t) / 10.0F : 1.0F;
        float size = 0.28F * pop;
        pose.pushPose();
        pose.translate(0.0F, folk.getBbHeight() + 0.45F + t * 0.004F, 0.0F);
        pose.mulPose(camera.cameraOrientation());
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffer.getBuffer(RenderType.text(SHEET));
        float u0 = s.mark / (float) CELLS, u1 = (s.mark + 1) / (float) CELLS;
        int a = (int) (255 * Mth.clamp(fade, 0.0F, 1.0F));
        int lit = LightTexture.FULL_BRIGHT;
        vc.addVertex(m, -size, 2 * size, 0.0F).setColor(255, 255, 255, a).setUv(u1, 0.0F).setLight(lit);
        vc.addVertex(m, -size, 0.0F, 0.0F).setColor(255, 255, 255, a).setUv(u1, 1.0F).setLight(lit);
        vc.addVertex(m, size, 0.0F, 0.0F).setColor(255, 255, 255, a).setUv(u0, 1.0F).setLight(lit);
        vc.addVertex(m, size, 2 * size, 0.0F).setColor(255, 255, 255, a).setUv(u0, 0.0F).setLight(lit);
        pose.popPose();
    }

    /** Three z's, small to large, drifting up from a sleeper's head and fading as they go. */
    private static void zzz(VillageFolkEntity folk, PoseStack pose, MultiBufferSource buffer, Font font, EntityRenderDispatcher camera, float age) {
        float off = (folk.getId() & 15) / 16.0F;
        for (int i = 0; i < 3; i++) {
            float ph = (age * 0.012F + off + i / 3.0F) % 1.0F;
            int a = (int) (230 * (1.0F - ph) * Math.min(1.0F, ph * 6.0F));
            if (a < 8) continue;
            float k = 0.012F + ph * 0.014F;
            pose.pushPose();
            pose.translate(Mth.sin((age + i * 20) * 0.08F) * 0.12F + ph * 0.25F, 0.6F + ph * 0.9F, 0.0F);
            pose.mulPose(camera.cameraOrientation());
            pose.scale(k, -k, k);
            String z = ph > 0.5F ? "Z" : "z";
            font.drawInBatch(z, -font.width(z) / 2.0F, 0.0F, a << 24 | 0xE8F0FF, false, pose.last().pose(), buffer,
                Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
    }
}
