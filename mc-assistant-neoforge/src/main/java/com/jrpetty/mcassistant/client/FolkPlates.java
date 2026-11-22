package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * [vision27] The folk's name tags, tidied: the game's own (a panel with the words on it, faint through walls), but cut to
 * what the player chose (AnimConfig.plates: the whole tag, the name, only the folk looked at, or none), shown out to a
 * shorter range than the game's sixty-four blocks, and faded out over the last third of it rather than snapping off, so
 * a crowd in the distance is not a cloud of labels and one walking away does not lose its name all at once. F1 (and the
 * camera's clean shot) hides them all, as the game hides its own.
 */
public final class FolkPlates {

    private FolkPlates() {}

    /** Whether this folk's tag is drawn at all (given the game's own answer: its F1, its range, its teams). */
    public static boolean show(VillageFolkEntity folk, boolean game, EntityRenderDispatcher camera) {
        if (!game) return false;
        if (FolkPortrait.drawing()) return true;
        AnimConfig.Plates p = AnimConfig.plates();
        if (p == AnimConfig.Plates.OFF || CinemaCam.cleanShot()) return false;
        if (p == AnimConfig.Plates.LOOK) return camera.crosshairPickEntity == folk;
        int range = AnimConfig.plateRange();
        return camera.distanceToSqr(folk) < (double) range * range;
    }

    /** Each folk's tidied tag, kept while the server's tag is the same one (it is sent anew only when it changes). */
    private static final java.util.Map<Integer, Component[]> TIDIED = new java.util.HashMap<>();

    private static Component tidied(VillageFolkEntity folk, Component tag) {
        Component src = folk.getCustomName();
        Component[] kept = TIDIED.get(folk.getId());
        if (kept != null && kept[0] == src && src != null) return kept[1];
        Component out = PlateText.tidy(tag);
        if (TIDIED.size() > 2048) TIDIED.clear();
        TIDIED.put(folk.getId(), new Component[] {src, out});
        return out;
    }

    /** The tag drawn: the game's way, faded with the distance. */
    public static void draw(VillageFolkEntity folk, Component tag, PoseStack pose, MultiBufferSource buffer, int light, float partial,
                            Font font, EntityRenderDispatcher camera) {
        AnimConfig.Plates p = AnimConfig.plates();
        Component text = p == AnimConfig.Plates.FULL ? tag : tidied(folk, tag);
        double d = Math.sqrt(camera.distanceToSqr(folk));
        float range = p == AnimConfig.Plates.LOOK ? 64.0F : AnimConfig.plateRange();
        float fade = Mth.clamp((float) ((range - d) / (range / 3.0)), 0.0F, 1.0F);
        if (fade < 0.04F) return;
        Vec3 at = folk.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, folk.getViewYRot(partial));
        if (at == null) return;
        boolean open = !folk.isDiscrete();
        pose.pushPose();
        pose.translate(at.x, at.y + 0.5, at.z);
        pose.mulPose(camera.cameraOrientation());
        pose.scale(0.025F, -0.025F, 0.025F);
        Matrix4f m = pose.last().pose();
        float back = Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * fade;
        int panel = (int) (back * 255.0F) << 24;
        float x = -font.width(text) / 2.0F;
        int ghost = Math.max(4, (int) (0x20 * fade)) << 24 | 0xFFFFFF;
        font.drawInBatch(text, x, 0.0F, ghost, false, m, buffer, open ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, panel, light);
        if (open) {
            int ink = Math.max(4, (int) (255 * fade)) << 24 | 0xFFFFFF;
            font.drawInBatch(text, x, 0.0F, ink, false, m, buffer, Font.DisplayMode.NORMAL, 0, light);
        }
        pose.popPose();
    }
}
