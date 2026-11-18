package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * [ui] A folk's likeness in a frame, the same on every screen that shows one (the profile, the favourites, their cards):
 * the folk itself, live, turning to the mouse, when the client has it in the world; else its initial on a backdrop of
 * its own colour with its trade's colour along the foot, so a folk who is away still reads as itself. The backdrop is a
 * wash of the folk's own colour, so two portraits side by side differ before a word is read.
 */
public final class FolkPortrait {

    private FolkPortrait() {}

    /** The folk with this entity id (and this UUID, when given) in the client's world, alive; or null. */
    @Nullable
    public static LivingEntity find(int entityId, @Nullable UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || entityId < 0) return null;
        Entity e = mc.level.getEntity(entityId);
        return e instanceof LivingEntity l && l.isAlive() && (uuid == null || e.getUUID().equals(uuid)) ? l : null;
    }

    /**
     * Draw the frame at x, y, w by h. {@code bust} frames the head and shoulders (a small card); otherwise the whole
     * folk stands in it (the profile's tall portrait). {@code rgb} is its own colour, {@code jobColour} its trade's.
     */
    public static void draw(GuiGraphics g, Font font, @Nullable LivingEntity e, String name, int rgb, int jobColour,
                            int x, int y, int w, int h, boolean bust, int mx, int my) {
        int wash = rgb == 0 ? 0xFF8FA3B5 : UiStyle.accentWash(rgb);
        UiStyle.well(g, x, y, w, h, wash);
        g.fill(x + 1, y + 1, x + w - 1, y + 1 + h / 3, 0x18FFFFFF);       // a lighter sky over the shoulders
        if (e != null) {
            g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
            if (bust) {
                int scale = Math.round(h * 0.62F);
                int centre = y + 3 + Math.round(scale * e.getBbHeight() / 2F);
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, x, y + 1, x + w, 2 * centre - y - 1, scale, 0.0625F, mx, my, e);
            } else {
                int scale = Math.max(8, Math.round((h - 8) / Math.max(1.0F, e.getBbHeight()) * 0.92F));
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, x, y + 4, x + w, y + h - 2, scale, 0.0625F, mx, my, e);
            }
            g.disableScissor();
        } else {
            g.fill(x + 1, y + h - 5, x + w - 1, y + h - 1, jobColour);
            String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            float k = h >= 40 ? 2F : 1F;
            g.pose().pushPose();
            g.pose().translate(x + w / 2F, y + (h - 5) / 2F - 4.5F * k, 0);
            g.pose().scale(k, k, 1F);
            g.drawString(font, letter, -font.width(letter) / 2, 0, UiStyle.TEXT_ON_DARK, true);
            g.pose().popPose();
        }
        g.renderOutline(x, y, w, h, UiStyle.EDGE);
    }
}
