package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.block.VillageBoardBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The writing on a Village Board, across all ten by five blocks of it: the village's
 * name large at the top, a line under it, "What we're doing" down the left, "How we're
 * doing" down the right, and "What we're working towards" along the foot. It glows a
 * little, the way a lit notice does, so it reads at night as well as by day.
 */
public class VillageBoardRenderer implements BlockEntityRenderer<VillageBoardBlockEntity> {

    /** Pixels of writing to a block: 48 makes about 24 lines from top to bottom. */
    private static final float PX = 48.0F;
    private static final int WIDTH = (int) (VillageBoardBlock.WIDE * PX), HEIGHT = (int) (VillageBoardBlock.HIGH * PX);
    private static final int MARGIN = 10, GUTTER = 12, LINE = 10;

    private static final int TITLE = 0xFFFFE08A, SUB = 0xFFB9C4D0, HEAD = 0xFFF2C14E, PLAIN = 0xFFEDEDED,
        GOOD = 0xFF8FE08F, WARN = 0xFFFFC870, BAD = 0xFFFF7A6E, QUIET = 0xFFA9B3BE;

    private final Font font;

    public VillageBoardRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(VillageBoardBlockEntity board, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        List<String> lines = board.lines();
        if (lines.isEmpty() || !(board.getBlockState().getBlock() instanceof VillageBoardBlock)) return;
        Direction facing = board.getBlockState().getValue(VillageBoardBlock.FACING);

        pose.pushPose();
        // To the middle of the bottom-left panel, then turned so +X runs along the board to
        // the reader's right, +Y up, and +Z out of its face towards the reader.
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        // The face is three pixels proud of the back of the cell; write just in front of it.
        pose.translate(-0.5, VillageBoardBlock.HIGH - 0.5, -0.5 + 3.0 / 16.0 + 0.005);
        pose.scale(1.0F / PX, -1.0F / PX, 1.0F / PX);
        Matrix4f m = pose.last().pose();
        int glow = LightTexture.FULL_BRIGHT;

        List<String[]> left = new ArrayList<>(), right = new ArrayList<>(), foot = new ArrayList<>();
        String title = "", sub = "";
        for (String l : lines) {
            int bar = l.indexOf('|');
            if (bar < 2) continue;
            String[] cell = { String.valueOf(l.charAt(1)), l.substring(bar + 1) };
            switch (l.charAt(0)) {
                case 'T' -> title = cell[1];
                case 'S' -> sub = cell[1];
                case 'L' -> left.add(cell);
                case 'R' -> right.add(cell);
                case 'F' -> foot.add(cell);
                default -> { }
            }
        }

        // The name, twice the size, centred; the line under it.
        pose.pushPose();
        pose.scale(2.0F, 2.0F, 2.0F);
        float tw = font.width(title);
        font.drawInBatch(title, (WIDTH / 2.0F - tw) / 2.0F, MARGIN / 2.0F, TITLE, false, pose.last().pose(), buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, glow);
        pose.popPose();
        int y = MARGIN + 22;
        drawCentred(sub, y, SUB, m, buffers, glow);
        y += LINE + 4;
        rule(y, m, buffers, glow);
        y += 6;

        // The two columns, and the foot under them.
        int colW = (WIDTH - MARGIN * 2 - GUTTER) / 2;
        int footLines = 0;
        for (String[] f : foot) footLines += f[0].equals("H") ? 1 : Math.min(2, font.split(FormattedText.of(f[1]), WIDTH - MARGIN * 2).size());
        int footTop = HEIGHT - MARGIN - footLines * LINE - 6;
        column(left, MARGIN, y, colW, footTop - 4, m, buffers, glow);
        column(right, MARGIN + colW + GUTTER, y, colW, footTop - 4, m, buffers, glow);
        rule(footTop, m, buffers, glow);
        column(foot, MARGIN, footTop + 6, WIDTH - MARGIN * 2, HEIGHT - MARGIN, m, buffers, glow);
        pose.popPose();
    }

    /** One column of lines, wrapped to its width, stopping at the bottom. */
    private void column(List<String[]> lines, int x, int y, int w, int bottom, Matrix4f m, MultiBufferSource buffers, int glow) {
        for (String[] l : lines) {
            if (y + LINE > bottom) return;
            int colour = colour(l[0]);
            if (l[0].equals("H")) {
                font.drawInBatch(l[1].toUpperCase(java.util.Locale.ROOT), x, y, colour, false, m, buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, glow);
                y += LINE + 3;
                continue;
            }
            List<FormattedCharSequence> wrapped = font.split(FormattedText.of(l[1]), w);
            for (int i = 0; i < wrapped.size(); i++) {
                if (y + LINE > bottom) return;
                font.drawInBatch(wrapped.get(i), x + (i == 0 ? 0 : 6), y, colour, false, m, buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, glow);
                y += LINE;
            }
            y += 2;
        }
    }

    private void drawCentred(String s, int y, int colour, Matrix4f m, MultiBufferSource buffers, int glow) {
        String shown = s;
        while (font.width(shown) > WIDTH - MARGIN * 2 && shown.length() > 4) shown = shown.substring(0, shown.length() - 2);
        font.drawInBatch(shown, (WIDTH - font.width(shown)) / 2.0F, y, colour, false, m, buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, glow);
    }

    /** A rule across the board: a row of dots, which every font has. */
    private void rule(int y, Matrix4f m, MultiBufferSource buffers, int glow) {
        String dot = "· ";
        int n = (WIDTH - MARGIN * 2) / Math.max(1, font.width(dot));
        String row = dot.repeat(Math.max(1, n));
        font.drawInBatch(row, MARGIN, y, 0xFF6E5A3A, false, m, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, glow);
    }

    private static int colour(String how) {
        return switch (how) {
            case "H" -> HEAD;
            case "G" -> GOOD;
            case "W" -> WARN;
            case "B" -> BAD;
            case "M" -> QUIET;
            default -> PLAIN;
        };
    }

    @Override
    public boolean shouldRenderOffScreen(VillageBoardBlockEntity board) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public AABB getRenderBoundingBox(VillageBoardBlockEntity board) {
        BlockPos a = board.getBlockPos();
        if (!(board.getBlockState().getBlock() instanceof VillageBoardBlock)) return new AABB(a);
        Direction facing = board.getBlockState().getValue(VillageBoardBlock.FACING);
        BlockPos far = VillageBoardBlock.cell(a, facing, VillageBoardBlock.WIDE - 1, VillageBoardBlock.HIGH - 1);
        return new AABB(a).minmax(new AABB(far)).inflate(1.0);
    }
}
