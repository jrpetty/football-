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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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
    private final BoardArms arms;                                   // [arms]

    public VillageBoardRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
        this.arms = new BoardArms(context);                           // [arms] the town's arms in the header
    }

    @Override
    public void render(VillageBoardBlockEntity board, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        List<String> lines = board.lines();
        if (lines.isEmpty() || !(board.getBlockState().getBlock() instanceof VillageBoardBlock)) return;
        Direction facing = board.getBlockState().getValue(VillageBoardBlock.FACING);
        Layout at = layout(board, lines);

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

        // The name, twice the size, centred; the line under it.
        pose.pushPose();
        pose.scale(2.0F, 2.0F, 2.0F);
        font.drawInBatch(at.title, at.titleX, MARGIN / 2.0F, TITLE, false, pose.last().pose(), buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, glow);
        pose.popPose();
        // [arms] The town's arms either side of its name, a banner hung in each top corner.
        if (at.heraldry != null) {
            arms.draw(at.heraldry, board.getLevel(), pose, buffers, MARGIN, 4, 16, glow);
            arms.draw(at.heraldry, board.getLevel(), pose, buffers, WIDTH - MARGIN - 16, 4, 16, glow);
        }
        draw(at.head, m, buffers, glow);

        // The two columns, and the foot under them, each at its page of the moment (see Layout).
        long turn = board.getLevel() == null ? 0 : board.getLevel().getGameTime() / PAGE_TICKS;
        draw(at.left, turn, m, buffers, glow);
        draw(at.right, turn, m, buffers, glow);
        draw(at.footRule, m, buffers, glow);
        draw(at.foot, turn, m, buffers, glow);
        pose.popPose();
    }

    // ------------------------------------------------------------------ the layout, worked out once

    /**
     * What the board shows, laid out: the writing in its places, each line wrapped, each column split into its pages.
     * It is the same for the same lines, so it is worked out when the board's lines change (a new list from the server,
     * a hundred ticks apart at the soonest) and kept; each frame only picks the page of the moment and draws it. It was
     * worked out afresh every frame, every line wrapped over and over (to measure the foot, to see whether a column
     * fits, to page it, to draw it), for every board in sight.
     */
    private static final class Layout {
        final List<String> lines;
        final int epoch;
        String title = "";
        float titleX;
        @Nullable String heraldry;
        /** The subtitle and the rule under it. */
        final List<Op> head = new ArrayList<>();
        /** The rule over the foot (none when there is no foot). */
        final List<Op> footRule = new ArrayList<>();
        @Nullable Paged left, right, foot;

        Layout(List<String> lines, int epoch) {
            this.lines = lines;
            this.epoch = epoch;
        }
    }

    /** A column's pages: what each shows (its "2 / 3" in the corner among it, when there is more than one). */
    private record Paged(List<List<Op>> pages) {}

    /** One piece of writing in its place: a String drawn as a String, a wrapped line as a wrapped line, as ever. */
    private record Op(@Nullable String text, @Nullable FormattedCharSequence line, float x, float y, int colour) {}

    /** The boards' layouts, by board; a board gone is let go with it. */
    private final Map<VillageBoardBlockEntity, Layout> layouts = new WeakHashMap<>();

    private Layout layout(VillageBoardBlockEntity board, List<String> lines) {
        boolean keep = TextCache.fresh();
        Layout got = layouts.get(board);
        if (keep && got != null && got.lines == lines && got.epoch == TextCache.epoch()) return got;
        Layout made = lay(lines);
        if (keep) layouts.put(board, made);
        else layouts.remove(board);
        return made;
    }

    private Layout lay(List<String> lines) {
        Layout out = new Layout(lines, TextCache.epoch());
        List<String[]> left = new ArrayList<>(), right = new ArrayList<>(), foot = new ArrayList<>();
        String title = "", sub = "", heraldry = null;
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
                case 'A' -> heraldry = cell[1];                       // [arms]
                default -> { }
            }
        }
        out.title = title;
        float tw = font.width(title);
        out.titleX = (WIDTH / 2.0F - tw) / 2.0F;
        out.heraldry = heraldry;
        int y = MARGIN + 22;
        centred(sub, y, SUB, out.head);
        y += LINE + 4;
        rule(y, out.head);
        y += 6;

        // The two columns, and the foot under them. Every feature of the town has a line to put up, and the foot
        // ("what we're working towards") grew upwards with each one until it ran off the top of the board and over
        // the town's name. Each part now keeps to its share of the board, the foot to two fifths of it at most, and
        // what does not fit is turned to like the pages of a notice: a new page every eight seconds.
        int colW = (WIDTH - MARGIN * 2 - GUTTER) / 2, footW = WIDTH - MARGIN * 2;
        int body = HEIGHT - MARGIN - y;
        int footH = Math.min(needed(foot, footW), body * 2 / 5);
        int footTop = HEIGHT - MARGIN - footH - 6;
        out.left = paged(left, MARGIN, y, colW, footTop - 4);
        out.right = paged(right, MARGIN + colW + GUTTER, y, colW, footTop - 4);
        if (!foot.isEmpty()) {
            rule(footTop, out.footRule);
            out.foot = paged(foot, MARGIN, footTop + 6, footW, HEIGHT - MARGIN);
        }
        return out;
    }

    private void draw(List<Op> ops, Matrix4f m, MultiBufferSource buffers, int glow) {
        for (Op op : ops) {
            if (op.text() != null) {
                font.drawInBatch(op.text(), op.x(), op.y(), op.colour(), false, m, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, glow);
            } else {
                font.drawInBatch(op.line(), op.x(), op.y(), op.colour(), false, m, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, glow);
            }
        }
    }

    /** A column at its page of the moment, turning with the clock. */
    private void draw(@Nullable Paged p, long turn, Matrix4f m, MultiBufferSource buffers, int glow) {
        if (p == null) return;
        int at = (int) Math.floorMod(turn, (long) p.pages().size());
        draw(p.pages().get(at), m, buffers, glow);
    }

    /** Ticks a page of the board stays up before the next is turned to. */
    private static final long PAGE_TICKS = 160L;

    /** The height these lines take, wrapped to w. */
    private int needed(List<String[]> lines, int w) {
        int h = 0;
        for (String[] l : lines) h += height(l, w);
        return h;
    }

    /** The height one line takes: a heading, or an entry wrapped to w. */
    private int height(String[] l, int w) {
        if (l[0].equals("H")) return LINE + 3;
        return TextCache.splitPlain(font, l[1], w).size() * LINE + 2;
    }

    /**
     * These lines in a box from y to bottom: all of them if they fit, else split into pages that fit (each page under
     * the heading it falls under, and no heading left alone at the foot of a page), each page with its number in the
     * box's corner. Null when there is no room or nothing to put there.
     */
    @Nullable
    private Paged paged(List<String[]> lines, int x, int y, int w, int bottom) {
        int room = bottom - y;
        if (room < LINE || lines.isEmpty()) return null;
        if (needed(lines, w) <= room) {
            List<Op> all = new ArrayList<>();
            column(lines, x, y, w, bottom, all);
            return new Paged(List.of(all));
        }
        int pageRoom = room - LINE;                                 // a line kept for the page number
        List<List<String[]>> pages = new ArrayList<>();
        List<String[]> page = new ArrayList<>();
        String[] heading = null;
        int used = 0;
        for (String[] l : lines) {
            boolean head = l[0].equals("H");
            int need = Math.min(height(l, w), pageRoom);
            int keep = head ? need + LINE + 2 : need;                   // a heading wants a line under it on its page
            boolean onlyHeading = page.size() == 1 && page.get(0) == heading;
            if (used + keep > pageRoom && !page.isEmpty() && !onlyHeading) {
                pages.add(page);
                page = new ArrayList<>();
                used = 0;
                if (!head && heading != null) {
                    page.add(heading);
                    used += height(heading, w);
                }
            }
            if (head) heading = l;
            page.add(l);
            used += need;
        }
        if (!page.isEmpty()) pages.add(page);
        List<List<Op>> laid = new ArrayList<>();
        for (int at = 0; at < pages.size(); at++) {
            List<Op> ops = new ArrayList<>();
            column(pages.get(at), x, y, w, bottom - LINE, ops);
            String mark = (at + 1) + " / " + pages.size();
            ops.add(new Op(mark, null, x + w - font.width(mark), bottom - LINE + 1, QUIET));
            laid.add(ops);
        }
        return new Paged(laid);
    }

    /** One column of lines, wrapped to its width, stopping at the bottom. */
    private void column(List<String[]> lines, int x, int y, int w, int bottom, List<Op> out) {
        for (String[] l : lines) {
            if (y + LINE > bottom) return;
            int colour = colour(l[0]);
            if (l[0].equals("H")) {
                out.add(new Op(l[1].toUpperCase(java.util.Locale.ROOT), null, x, y, colour));
                y += LINE + 3;
                continue;
            }
            List<FormattedCharSequence> wrapped = TextCache.splitPlain(font, l[1], w);
            for (int i = 0; i < wrapped.size(); i++) {
                if (y + LINE > bottom) return;
                out.add(new Op(null, wrapped.get(i), x + (i == 0 ? 0 : 6), y, colour));
                y += LINE;
            }
            y += 2;
        }
    }

    private void centred(String s, int y, int colour, List<Op> out) {
        String shown = s;
        while (font.width(shown) > WIDTH - MARGIN * 2 && shown.length() > 4) shown = shown.substring(0, shown.length() - 2);
        out.add(new Op(shown, null, (WIDTH - font.width(shown)) / 2.0F, y, colour));
    }

    /** A rule across the board: a row of dots, which every font has. */
    private void rule(int y, List<Op> out) {
        String dot = "· ";
        int n = (WIDTH - MARGIN * 2) / Math.max(1, font.width(dot));
        String row = dot.repeat(Math.max(1, n));
        out.add(new Op(row, null, MARGIN, y, 0xFF6E5A3A));
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
