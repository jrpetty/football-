package com.jrpetty.mcassistant.entity.goal;

import net.minecraft.core.Direction;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village's buildings, as drawings.
 *
 * <p>Every building a village raises is drawn in a text file under
 * {@code data/mc_assistant/blueprints/}: one layer of the building to a block of
 * rows, from the footing up; in each layer the back of the building is the top
 * row and the front (where the door is) the bottom; one character to a block.
 * The middle row and the middle column are the building's centre line.
 *
 * <p>A character says what a block is FOR, not what it is made of. "W" is a wall,
 * and a builder lays planks there if it has them; "F" is the footing, stone; "L" a
 * post, a log standing up; "^" a roof stair rising toward the back. What it is
 * made of is whatever the village has: the oak, spruce or birch of its own
 * woods, the cobble of its own mine. A builder short of the right thing uses
 * any building block it has, so a building is never held up for want of a
 * stair. (tools/blueprints.py reads the same files and draws them.)
 */
public final class Blueprints {

    private Blueprints() {}

    /** What a block in a drawing is for: what a builder looks for first, and how it sits. */
    public enum Style {
        /** Any building block, cheapest first. */
        GENERIC,
        /** The stone footing and the stone lower course of a wall. */
        FOUNDATION, WALL_LOW,
        /** Dressed stone: stone bricks if there are any, else cobble. */
        MASONRY,
        /** Brick, for a chimney; else dressed stone. */
        BRICK,
        /** Boards: floors, walls, the roof's solid parts. */
        FLOOR, WALL, ROOF_BLOCK,
        /** Logs: a post standing up, a beam lying across the building or along it. */
        POST, BEAM_ACROSS, BEAM_ALONG,
        /** Roof stairs (a top-half one is upside down), roof slabs (low or high). */
        ROOF_STAIR, ROOF_STAIR_TOP, ROOF_SLAB, ROOF_SLAB_TOP,
        /** Stone slabs and steps. */
        STONE_SLAB, STONE_STAIR,
        /** Earth, for a flower box. */
        SOIL,
        /** Glass: a pane in a window, or a whole block. */
        PANE, GLASS,
        /** A lantern hung from what is above it. */
        HANGING,
        NONE
    }

    /** Which way something faces, in the drawing's own terms. */
    public enum Way { BACK, FRONT, LEFT, RIGHT, UP }

    /** One character of the legend: the part it is, its style, and its way. */
    public record Key(BuildGoal.Part part, Style style, Way way) {}

    /** One block of a drawing: across (right is +), up, and depth (toward the back is +). */
    public record Cell(int dx, int h, int dz, Key key) {}

    private static final Map<Character, Key> LEGEND = new HashMap<>();

    private static void key(char c, BuildGoal.Part part, Style style, Way way) {
        LEGEND.put(c, new Key(part, style, way));
    }

    static {
        key('#', BuildGoal.Part.BLOCK, Style.GENERIC, Way.UP);
        key('F', BuildGoal.Part.BLOCK, Style.FOUNDATION, Way.UP);
        key('w', BuildGoal.Part.BLOCK, Style.WALL_LOW, Way.UP);
        key('S', BuildGoal.Part.BLOCK, Style.MASONRY, Way.UP);
        key('Z', BuildGoal.Part.BLOCK, Style.BRICK, Way.UP);
        key('f', BuildGoal.Part.BLOCK, Style.FLOOR, Way.UP);
        key('W', BuildGoal.Part.BLOCK, Style.WALL, Way.UP);
        key('R', BuildGoal.Part.BLOCK, Style.ROOF_BLOCK, Way.UP);
        key('L', BuildGoal.Part.BLOCK, Style.POST, Way.UP);
        key('-', BuildGoal.Part.BLOCK, Style.BEAM_ACROSS, Way.RIGHT);
        key('|', BuildGoal.Part.BLOCK, Style.BEAM_ALONG, Way.BACK);
        key('^', BuildGoal.Part.BLOCK, Style.ROOF_STAIR, Way.BACK);
        key('v', BuildGoal.Part.BLOCK, Style.ROOF_STAIR, Way.FRONT);
        key('<', BuildGoal.Part.BLOCK, Style.ROOF_STAIR, Way.LEFT);
        key('>', BuildGoal.Part.BLOCK, Style.ROOF_STAIR, Way.RIGHT);
        key('n', BuildGoal.Part.BLOCK, Style.ROOF_STAIR_TOP, Way.BACK);
        key('u', BuildGoal.Part.BLOCK, Style.ROOF_STAIR_TOP, Way.FRONT);
        key('{', BuildGoal.Part.BLOCK, Style.ROOF_STAIR_TOP, Way.LEFT);
        key('}', BuildGoal.Part.BLOCK, Style.ROOF_STAIR_TOP, Way.RIGHT);
        key('_', BuildGoal.Part.BLOCK, Style.ROOF_SLAB, Way.UP);
        key('=', BuildGoal.Part.BLOCK, Style.ROOF_SLAB_TOP, Way.UP);
        key('s', BuildGoal.Part.BLOCK, Style.STONE_SLAB, Way.UP);
        key('z', BuildGoal.Part.BLOCK, Style.STONE_SLAB, Way.UP);
        key('k', BuildGoal.Part.BLOCK, Style.STONE_STAIR, Way.BACK);
        key('d', BuildGoal.Part.BLOCK, Style.SOIL, Way.UP);
        key('G', BuildGoal.Part.WINDOW, Style.PANE, Way.UP);
        key('O', BuildGoal.Part.WINDOW, Style.GLASS, Way.UP);
        key('D', BuildGoal.Part.DOOR, Style.NONE, Way.BACK);
        // [flats] A door in a wall that runs front to back (a flat's, off the stair hall: entity/Flats).
        key('Y', BuildGoal.Part.DOOR, Style.NONE, Way.RIGHT);
        key('P', BuildGoal.Part.FENCE, Style.NONE, Way.UP);
        key('g', BuildGoal.Part.GATE, Style.NONE, Way.BACK);
        key('T', BuildGoal.Part.CRAFTING_TABLE, Style.NONE, Way.UP);
        key('U', BuildGoal.Part.FURNACE, Style.NONE, Way.FRONT);
        key('C', BuildGoal.Part.CHEST, Style.NONE, Way.FRONT);
        key('c', BuildGoal.Part.CHEST, Style.NONE, Way.BACK);
        key('(', BuildGoal.Part.CHEST, Style.NONE, Way.LEFT);
        key(')', BuildGoal.Part.CHEST, Style.NONE, Way.RIGHT);
        key('B', BuildGoal.Part.BED, Style.NONE, Way.BACK);
        key('b', BuildGoal.Part.BED, Style.NONE, Way.FRONT);
        key('[', BuildGoal.Part.BED, Style.NONE, Way.LEFT);
        key(']', BuildGoal.Part.BED, Style.NONE, Way.RIGHT);
        key('t', BuildGoal.Part.TORCH, Style.NONE, Way.UP);
        key('l', BuildGoal.Part.LANTERN, Style.NONE, Way.UP);
        key('j', BuildGoal.Part.LANTERN, Style.HANGING, Way.UP);
        key('H', BuildGoal.Part.LADDER, Style.NONE, Way.FRONT);
        key('o', BuildGoal.Part.OBSIDIAN, Style.NONE, Way.UP);
        key('~', BuildGoal.Part.WATER, Style.NONE, Way.UP);
        key('y', BuildGoal.Part.HAY, Style.NONE, Way.UP);
        key('Q', BuildGoal.Part.BARREL, Style.NONE, Way.UP);
        key('*', BuildGoal.Part.FLOWER, Style.NONE, Way.UP);
        key('X', BuildGoal.Part.CARPET, Style.NONE, Way.UP);
        key('a', BuildGoal.Part.ANVIL, Style.NONE, Way.RIGHT);
        key('&', BuildGoal.Part.CAULDRON, Style.NONE, Way.UP);
        key('e', BuildGoal.Part.BELL, Style.NONE, Way.FRONT);
        key('K', BuildGoal.Part.BOOKSHELF, Style.NONE, Way.UP);
        key('r', BuildGoal.Part.LECTERN, Style.NONE, Way.FRONT);
        key('E', BuildGoal.Part.ENCHANTING, Style.NONE, Way.UP);
        key('I', BuildGoal.Part.BREWING, Style.NONE, Way.UP);
        key('M', BuildGoal.Part.SMOKER, Style.NONE, Way.FRONT);
        key('N', BuildGoal.Part.LOOM, Style.NONE, Way.FRONT);
        key('V', BuildGoal.Part.GRINDSTONE, Style.NONE, Way.FRONT);
        key('h', BuildGoal.Part.CAMPFIRE, Style.NONE, Way.UP);
        key('m', BuildGoal.Part.NOTE_BLOCK, Style.NONE, Way.UP);
        key('$', BuildGoal.Part.STOREHOUSE, Style.NONE, Way.FRONT);
    }

    private static final Map<String, List<Cell>> DRAWINGS = new ConcurrentHashMap<>();
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Is there a drawing of this building? */
    public static boolean has(String name) {
        return !cells(name).isEmpty();
    }

    /** The drawing of this building, read once; empty if there is none. A name ending "_tall" is the
     *  building with a second storey put on (raised). */
    public static List<Cell> cells(String name) {
        List<Cell> got = DRAWINGS.get(name);
        if (got != null) return got;
        got = name.endsWith(TALL) ? raised(name.substring(0, name.length() - TALL.length())) : read(name);
        DRAWINGS.put(name, got);
        return got;
    }

    /** The mark of a building with a second storey on it. */
    public static final String TALL = "_tall";

    private static boolean roofish(Style s) {
        return s == Style.ROOF_STAIR || s == Style.ROOF_STAIR_TOP || s == Style.ROOF_SLAB || s == Style.ROOF_SLAB_TOP
            || s == Style.ROOF_BLOCK;
    }

    private static boolean wallish(Key k) {
        if (k.part() == BuildGoal.Part.WINDOW) return true;
        if (k.part() == BuildGoal.Part.LANTERN) return k.style() == Style.HANGING;
        if (k.part() != BuildGoal.Part.BLOCK) return false;
        return switch (k.style()) {
            case WALL, POST, MASONRY, BRICK, WALL_LOW, GENERIC, BEAM_ACROSS, BEAM_ALONG, GLASS -> true;
            default -> false;
        };
    }

    /**
     * A building with a second storey put on it, drawn from the building itself: everything up to
     * its eaves as it was; over it a new floor (the old ceiling's beams and boards), its walls built
     * up again a second time (the windows where they were, the doorway walled), a ladder up from the
     * ground floor, and its roof lifted onto the new walls. The old eaves stay where they were, a
     * skirt of roof between the storeys. Empty for a building with no eaves to raise (a tower, a well).
     */
    private static List<Cell> raised(String base) {
        List<Cell> cells = cells(base);
        if (cells.isEmpty()) return List.of();
        int eaves = Integer.MAX_VALUE;
        for (Cell c : cells) if (c.h() >= 2 && roofish(c.key().style())) eaves = Math.min(eaves, c.h());
        if (eaves == Integer.MAX_VALUE) return List.of();
        int lift = eaves;
        Key wall = LEGEND.get('W'), ladder = LEGEND.get('H');
        // The outline of the ground storey: every column a wall, post, window or door stands in.
        java.util.Set<Long> ring = new java.util.HashSet<>();
        java.util.Set<Long> used = new java.util.HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : cells) {
            if (c.h() < 0 || c.h() >= eaves) continue;
            long key = ((long) c.dx() << 32) ^ (c.dz() & 0xffffffffL);
            used.add(key);
            if ((wallish(c.key()) && c.key().part() != BuildGoal.Part.LANTERN) || c.key().part() == BuildGoal.Part.DOOR) {
                ring.add(key);
                minX = Math.min(minX, c.dx()); maxX = Math.max(maxX, c.dx());
                minZ = Math.min(minZ, c.dz()); maxZ = Math.max(maxZ, c.dz());
            }
        }
        if (ring.isEmpty()) return List.of();
        // A ladder up, against the back wall: a column inside that nothing on the ground floor uses.
        int[] up = null;
        for (int dz = maxZ - 1; dz > minZ && up == null; dz--) {
            for (int dx = minX + 1; dx < maxX && up == null; dx++) {
                long k = ((long) dx << 32) ^ (dz & 0xffffffffL), behind = ((long) dx << 32) ^ ((dz + 1) & 0xffffffffL);
                if (!used.contains(k) && ring.contains(behind)) up = new int[]{ dx, dz };
            }
        }
        List<Cell> out = new ArrayList<>();
        for (Cell c : cells) {
            boolean roof = roofish(c.key().style());
            if (c.h() < eaves) out.add(c);
            if (c.h() == eaves) {
                // The new floor (beams and boards) — and the old eaves left as a skirt between the storeys.
                boolean ladderHole = up != null && c.dx() == up[0] && c.dz() == up[1];
                if (!ladderHole) out.add(c);
            }
            if (c.h() >= eaves) out.add(new Cell(c.dx(), c.h() + lift, c.dz(), c.key()));    // the roof, lifted
        }
        // The walls built up again: each course of the ground storey's walls, a storey higher.
        for (int h = 1; h < eaves; h++) {
            java.util.Set<Long> laid = new java.util.HashSet<>();
            for (Cell c : cells) {
                if (c.h() != h || !wallish(c.key())) continue;
                out.add(new Cell(c.dx(), c.h() + lift, c.dz(), c.key()));
                laid.add(((long) c.dx() << 32) ^ (c.dz() & 0xffffffffL));
            }
            for (long k : ring) {
                if (laid.contains(k)) continue;                                // the doorway, walled up there
                out.add(new Cell((int) (k >> 32), h + lift, (int) k, wall));
            }
        }
        if (up != null && ladder != null) {
            for (int h = 0; h <= eaves; h++) out.add(new Cell(up[0], h, up[1], ladder));
        }
        return List.copyOf(out);
    }

    private static List<Cell> read(String name) {
        String path = "/data/mc_assistant/blueprints/" + name + ".txt";
        try (InputStream in = Blueprints.class.getResourceAsStream(path)) {
            if (in == null) return List.of();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(name, r.lines().toList());
        } catch (Exception e) {
            LOG.error("[MCA-BLUEPRINT] could not read {}: {}", path, e.toString());
            return List.of();
        }
    }

    /** Read a drawing's lines into cells. Public for the tests. */
    public static List<Cell> parse(String name, List<String> lines) {
        List<Cell> out = new ArrayList<>();
        Integer layer = null;
        List<String> rows = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (line.startsWith("#") || line.isBlank()) continue;
            if (line.startsWith("name ")) continue;
            if (line.startsWith("layer ")) {
                if (layer != null) flush(name, layer, rows, out);
                layer = Integer.parseInt(line.substring(6).trim());
                rows = new ArrayList<>();
                continue;
            }
            rows.add(line);
        }
        if (layer != null) flush(name, layer, rows, out);
        return List.copyOf(out);
    }

    private static void flush(String name, int layer, List<String> rows, List<Cell> out) {
        int depth = rows.size();
        int width = 0;
        for (String r : rows) width = Math.max(width, r.length());
        for (int i = 0; i < depth; i++) {
            String row = rows.get(i);
            int dz = (depth - 1) / 2 - i;
            for (int j = 0; j < row.length(); j++) {
                char c = row.charAt(j);
                if (c == '.' || c == ' ') continue;
                Key k = LEGEND.get(c);
                if (k == null) {
                    LOG.warn("[MCA-BLUEPRINT] {} layer {}: no such mark '{}'", name, layer, c);
                    continue;
                }
                out.add(new Cell(j - (width - 1) / 2, layer, dz, k));
            }
        }
    }

    /** Half the width and half the depth of the building on the ground (its footing). */
    public static int[] groundHalf(String name) {
        int w = 0, d = 0;
        for (Cell c : cells(name)) {
            if (c.h() > 0) continue;
            w = Math.max(w, Math.abs(c.dx()));
            d = Math.max(d, Math.abs(c.dz()));
        }
        return new int[]{ w, d };
    }

    /** Half the width and depth of everything, eaves and porches included. */
    public static int[] fullHalf(String name) {
        int w = 0, d = 0;
        for (Cell c : cells(name)) {
            w = Math.max(w, Math.abs(c.dx()));
            d = Math.max(d, Math.abs(c.dz()));
        }
        return new int[]{ w, d };
    }

    /** How many of each style its blocks want, for the people who stock a build. */
    public static Map<Style, Integer> styleCounts(String name) {
        Map<Style, Integer> out = new EnumMap<>(Style.class);
        for (Cell c : cells(name)) out.merge(c.key().style(), 1, Integer::sum);
        return out;
    }

    /** A way in the drawing, as a direction in the world, for a building whose back is toward {@code facing}. */
    public static Direction world(Way way, Direction facing) {
        return switch (way) {
            case BACK -> facing;
            case FRONT -> facing.getOpposite();
            case LEFT -> facing.getCounterClockWise();
            case RIGHT -> facing.getClockWise();
            case UP -> Direction.UP;
        };
    }
}
