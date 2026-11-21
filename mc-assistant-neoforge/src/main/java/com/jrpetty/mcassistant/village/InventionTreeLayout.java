package com.jrpetty.mcassistant.village;

import com.jrpetty.mcassistant.village.InventionTree.Invention;

import java.util.EnumMap;
import java.util.Map;

/**
 * [screens13] Where the Inventions page (client/InventionsPage) draws each card of the tree, as plain arithmetic so the
 * unit tests can check it without the game: a column for each branch, a band for each step down the road of needs
 * (Invention.depth), the cards of a branch at the same step stacked in its band. Between the columns run the channels
 * the lines go down; a line from a thing needed leaves under it, runs along to the channel beside what needs it, goes
 * down the channel and enters its side.
 */
public final class InventionTreeLayout {

    /** The card sizes and gaps at a zoom; {@code stepHead} is the strip at the top of each band for its label. */
    public record Metrics(int cardW, int cardH, int gapX, int gapY, int stepHead) {
        /** The room left of the first column: half a channel and a little. */
        public int padLeft() {
            return gapX / 2 + 2;
        }
    }

    /** A card's place, in the tree's own pixels from its top left. */
    public record Place(Invention inv, int x, int y) {}

    /** The whole tree laid out: every card, each band's top (index by step, one past the last for the bottom), the size. */
    public record Layout(Metrics m, Map<Invention, Place> places, int[] bandTop, int maxDepth, int width, int height) {

        /** The x of the channel a line to {@code to} from {@code from} goes down. */
        public int channelX(Invention from, Invention to) {
            Place f = places.get(from), t = places.get(to);
            return t.x() < f.x() ? t.x() + m.cardW() + m.gapX() / 2 : t.x() - m.gapX() / 2;
        }
    }

    private InventionTreeLayout() {}

    public static final int BRANCHES = InventionTree.Branch.values().length;

    /** How wide the tree is at these sizes. */
    public static int width(Metrics m) {
        return 2 * m.padLeft() + BRANCHES * m.cardW() + (BRANCHES - 1) * m.gapX();
    }

    public static Layout layout(Metrics m) {
        int maxDepth = 1;
        for (Invention inv : Invention.values()) maxDepth = Math.max(maxDepth, inv.depth());
        int[] rows = new int[maxDepth + 1];
        int[][] stack = new int[BRANCHES][maxDepth + 1];
        for (Invention inv : Invention.values()) rows[inv.depth()] = Math.max(rows[inv.depth()], ++stack[inv.branch.ordinal()][inv.depth()]);
        int[] bandTop = new int[maxDepth + 2];
        int by = 0;
        for (int d = 1; d <= maxDepth; d++) {
            bandTop[d] = by;
            by += m.stepHead() + rows[d] * (m.cardH() + m.gapY());
        }
        bandTop[maxDepth + 1] = by;
        Map<Invention, Place> at = new EnumMap<>(Invention.class);
        int[][] placed = new int[BRANCHES][maxDepth + 1];
        for (Invention inv : Invention.values()) {
            int b = inv.branch.ordinal(), d = inv.depth();
            int slot = placed[b][d]++;
            at.put(inv, new Place(inv, m.padLeft() + b * (m.cardW() + m.gapX()), bandTop[d] + m.stepHead() + slot * (m.cardH() + m.gapY())));
        }
        return new Layout(m, at, bandTop, maxDepth, width(m), by);
    }
}
