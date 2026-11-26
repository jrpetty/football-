package com.jrpetty.mcassistant.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashSet;
import java.util.Set;

/**
 * [ui53] The village board's last line of defence. The board draws forty-odd pages from whatever the server sent, and
 * anything a page or a row throws while drawing (or a click, a key, the wheel) would otherwise take the whole game down
 * with a "Rendering screen" crash report. Here it is caught, logged once (not every frame), the drawing state put back
 * as it was (the pose stack, the scissor), and "—" shown in the place of what failed. The real bugs are fixed where they
 * are; this is only so that one the review missed costs a page, never the game.
 */
final class BoardGuard {

    private BoardGuard() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    /** What has been logged already (where and what), so a page that fails every frame is logged once. */
    private static final Set<String> TOLD = new HashSet<>();

    /** Log a failure once: where it was (the page, the row) and what it was. */
    static void log(String where, Throwable e) {
        String key = where + "|" + e;
        if (TOLD.size() < 512 && TOLD.add(key)) {
            LOG.error("[ui53] the village board: {} failed and is shown as — (the game goes on)", where, e);
        }
    }

    /** May this be caught here? Everything but running out of memory (which nothing here could put right). */
    static boolean catchable(Throwable e) {
        return !(e instanceof VirtualMachineError) || e instanceof StackOverflowError;
    }

    /** Before drawing what might fail: a pose of its own, to come back to. */
    static PoseStack.Pose mark(GuiGraphics g) {
        g.pose().pushPose();
        return g.pose().last();
    }

    /** After it drew without failing: its pose let go. */
    static void done(GuiGraphics g, PoseStack.Pose mark) {
        unwindPose(g, mark);
        if (!g.pose().clear()) g.pose().popPose();
    }

    /**
     * After it failed part way: every pose it pushed and never popped let go, back to the mark (and the mark's own), and
     * every scissor it set let go, then {@code scissor} (x0, y0, x1, y1), if given, set again for what is drawn after.
     */
    static void recover(GuiGraphics g, PoseStack.Pose mark, int[] scissor) {
        try {
            g.flush();
        } catch (RuntimeException ignored) {
            // Half a batch: dropped with the rest of the frame.
        }
        done(g, mark);
        try {
            for (int i = 0; i < 64; i++) g.disableScissor();        // down to none: the stack says when it is empty
        } catch (IllegalStateException empty) {
            // "Scissor stack underflow": all let go.
        }
        if (scissor != null) g.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
    }

    private static void unwindPose(GuiGraphics g, PoseStack.Pose mark) {
        for (int i = 0; i < 256 && g.pose().last() != mark && !g.pose().clear(); i++) g.pose().popPose();
    }

    /** "—" and a word on why, where a page could not be drawn. */
    static void pageFailed(GuiGraphics g, Font font, String page, int x, int y, int w) {
        g.drawString(font, "—", x, y, UiStyle.TEXT_FAINT, false);
        UiStyle.text(g, font, "This page could not be drawn from the town's books just now (" + page
            + "). The other pages are fine; the game's log has the details.", x + 12, y, w - 12, UiStyle.TEXT_FAINT);
    }
}
