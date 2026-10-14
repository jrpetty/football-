package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every building drawing (data/mc_assistant/blueprints/*.txt) read as the game reads it, and gone
 * over as a builder would go over it before laying a block: whatever hangs has something to hang
 * from, whatever stands has something to stand on, a ladder has a wall at its back, a bed has room
 * for its head, a door opens onto a floor with headroom both sides, water is held in, and the beds,
 * the benches, the bells and every storey can be walked to from the street, up the stairs and the
 * ladders. Checked without a world, from the drawing's own characters and the game's own legend (read
 * out of Blueprints.java, so a new mark there is known here too); and the same again for every
 * building the Iron Age puts a storey on (Ages.TALL), raised as Blueprints.raised raises it.
 *
 * <p>[blueprints] And, for the way a town looks and is got about in: a building with doors has one onto the
 * street with clear ground (or steps) two places out from it, and is got into through its doors and not
 * through a gap in its walls; no one-block hole in a wall that is not a window; every chest opens (nothing
 * solid on its lid) and every chest and cask can be got at; every furnished storey can be walked up to (a bare
 * roof space need not be); no hole in an upper floor that somebody would fall down; everywhere in behind the
 * doors and walled in has a roof over it; the shop's, the café's and the store's counters have room in front
 * for their price tags and their customers; and every drawing keeps the footprint and the door side the town
 * plan's lots are sized by.
 *
 * <p>A cell outside a drawing is open air above the ground (layer 0 and up) and the ground itself
 * below it (layer -1 and down): the builder levels a lot before it lays a footing. Each check lists
 * every fault it finds, the drawing, the layer and the place in it, before it fails.
 */
class BlueprintSoundnessTest {

    // ------------------------------------------------------------------ the legend and the drawings

    /** One mark of the legend: the part it is and its style and way, by name, as Blueprints.java has them. */
    record Key(char ch, String part, String style, String way) {}

    /** How a block sits, for what may hang from it, stand on it or lean on it. */
    enum Shape {
        /** Nothing there. */
        AIR,
        /** A whole block: walls, floors, posts, beams, glass, the full-block furniture. */
        FULL,
        /** A stair the right way up (its underside whole), or a slab low in its block. */
        LOW_STAIR, LOW_SLAB,
        /** A stair upside down, or a slab high in its block (their tops whole). */
        HIGH_STAIR, HIGH_SLAB,
        /** A fence post or a window pane: a thin post up the middle, which holds a lantern above or below. */
        POST,
        /** A gate, a door: neither holds anything nor is stood on. */
        THIN,
        /** Furniture that is not a whole block: a chest, a bed, a lectern, an anvil, a cauldron... */
        FURNITURE,
        /** Small things that hold nothing and wash away: a torch, a flower, a carpet. */
        SMALL,
        /** A lantern, standing or hung. */
        LANTERN,
        /** A ladder. */
        LADDER,
        WATER
    }

    static Shape shape(Key k) {
        if (k == null) return Shape.AIR;
        switch (k.part()) {
            case "BLOCK":
                return switch (k.style()) {
                    case "ROOF_STAIR", "STONE_STAIR" -> Shape.LOW_STAIR;
                    case "ROOF_STAIR_TOP" -> Shape.HIGH_STAIR;
                    case "ROOF_SLAB", "STONE_SLAB" -> Shape.LOW_SLAB;
                    case "ROOF_SLAB_TOP" -> Shape.HIGH_SLAB;
                    default -> Shape.FULL;
                };
            case "WINDOW":
                return "GLASS".equals(k.style()) ? Shape.FULL : Shape.POST;
            case "FENCE":
                return Shape.POST;
            case "GATE", "DOOR":
                return Shape.THIN;
            case "CRAFTING_TABLE", "FURNACE", "SMOKER", "LOOM", "BARREL", "HAY", "BOOKSHELF", "NOTE_BLOCK",
                 "OBSIDIAN", "STOREHOUSE":
                return Shape.FULL;
            case "TORCH", "FLOWER", "CARPET":
                return Shape.SMALL;
            case "LANTERN":
                return Shape.LANTERN;
            case "LADDER":
                return Shape.LADDER;
            case "WATER":
                return Shape.WATER;
            default:
                return Shape.FURNITURE;   // chest, bed, lectern, enchanting table, brewing stand, grindstone,
                                          // campfire, anvil, cauldron, bell, and anything new until it is placed here
        }
    }

    /** The underside holds a hanging lantern (a whole face, a low stair or slab, or a post: Block.canSupportCenter down). */
    static boolean holdsBelow(Shape s) {
        return s == Shape.FULL || s == Shape.LOW_STAIR || s == Shape.LOW_SLAB || s == Shape.POST;
    }

    /** The top holds a torch or a standing lantern (Block.canSupportCenter up). */
    static boolean holdsOnTop(Shape s) {
        return s == Shape.FULL || s == Shape.HIGH_STAIR || s == Shape.HIGH_SLAB || s == Shape.POST;
    }

    /** The top is a whole face (a door's sill, a bell's floor). */
    static boolean sturdyTop(Shape s) {
        return s == Shape.FULL || s == Shape.HIGH_STAIR || s == Shape.HIGH_SLAB;
    }

    /** A floor to walk on or lay a bed on. */
    static boolean floor(Shape s) {
        return s == Shape.FULL || s == Shape.LOW_STAIR || s == Shape.LOW_SLAB || s == Shape.HIGH_STAIR || s == Shape.HIGH_SLAB;
    }

    /** Somebody walks through it (a door or a gate opens; a rug, a torch or a flower is underfoot). */
    static boolean passable(Shape s) {
        return s == Shape.AIR || s == Shape.SMALL || s == Shape.THIN || s == Shape.LADDER;
    }

    /** Water stops at it (flowing water washes a torch, a flower or a rug away, and goes on). */
    static boolean holdsWater(Shape s) {
        return s != Shape.AIR && s != Shape.SMALL;
    }

    /** One drawing, read: its cells by place. */
    static final class Drawing {
        final String name;
        final Map<Long, Key> cells = new HashMap<>();
        final List<String> unknown = new ArrayList<>();
        /** For a raised building with a floor upstairs: did the raising find a place for its ladder up? */
        boolean wayUp = true;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;

        Drawing(String name) { this.name = name; }

        static long at(int dx, int h, int dz) {
            return ((long) (dx & 0xfffff) << 40) | ((long) (h & 0xfffff) << 20) | (dz & 0xfffff);
        }

        Key key(int dx, int h, int dz) { return cells.get(at(dx, h, dz)); }

        /** What is at a place: what the drawing has there, else air above the ground and the ground below it. */
        Shape shapeAt(int dx, int h, int dz) {
            Key k = key(dx, h, dz);
            if (k != null) return shape(k);
            return h < 0 ? Shape.FULL : Shape.AIR;
        }

        void put(int dx, int h, int dz, Key k) {
            cells.put(at(dx, h, dz), k);
            minX = Math.min(minX, dx); maxX = Math.max(maxX, dx);
            minZ = Math.min(minZ, dz); maxZ = Math.max(maxZ, dz);
            minH = Math.min(minH, h); maxH = Math.max(maxH, h);
        }

        String where(int dx, int h, int dz) {
            Key k = key(dx, h, dz);
            return name + " layer " + h + " (dx " + dx + ", dz " + dz + ")" + (k == null ? "" : " '" + k.ch() + "'");
        }
    }

    /** One cell of a drawing, for walking over them all. */
    record Cell(int dx, int h, int dz, Key key) {}

    static List<Cell> cells(Drawing d) {
        List<Cell> out = new ArrayList<>();
        for (Map.Entry<Long, Key> e : d.cells.entrySet()) {
            long v = e.getKey();
            out.add(new Cell(signed((v >> 40) & 0xfffff), signed((v >> 20) & 0xfffff), signed(v & 0xfffff), e.getValue()));
        }
        out.sort((a, b) -> a.h() != b.h() ? Integer.compare(a.h(), b.h())
            : a.dz() != b.dz() ? Integer.compare(b.dz(), a.dz()) : Integer.compare(a.dx(), b.dx()));
        return out;
    }

    private static int signed(long v) {
        return (int) (v >= 0x80000 ? v - 0x100000 : v);
    }

    /** A way in the drawing as a step across it: right is +dx, the back is +dz. */
    static int[] step(String way) {
        return switch (way) {
            case "BACK" -> new int[]{ 0, 1 };
            case "FRONT" -> new int[]{ 0, -1 };
            case "LEFT" -> new int[]{ -1, 0 };
            case "RIGHT" -> new int[]{ 1, 0 };
            default -> new int[]{ 0, 0 };
        };
    }

    private static Path project() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p : List.of(here, here.resolve("mc-assistant-neoforge"))) {
            if (Files.isDirectory(p.resolve(BLUEPRINTS))) return p;
        }
        throw new AssertionError("no blueprints under " + here);
    }

    private static final String BLUEPRINTS = "src/main/resources/data/mc_assistant/blueprints";
    private static final String LEGEND_SOURCE = "src/main/java/com/jrpetty/mcassistant/entity/goal/Blueprints.java";
    private static final String AGES_SOURCE = "src/main/java/com/jrpetty/mcassistant/entity/Ages.java";

    private static Map<Character, Key> legendCache;
    private static List<Drawing> drawingsCache;

    /** The game's legend, out of Blueprints.java: key('c', BuildGoal.Part.P, Style.S, Way.W). */
    static synchronized Map<Character, Key> legend() throws IOException {
        if (legendCache != null) return legendCache;
        String src = Files.readString(project().resolve(LEGEND_SOURCE), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("key\\('(\\\\?.)',\\s*BuildGoal\\.Part\\.(\\w+),\\s*Style\\.(\\w+),\\s*Way\\.(\\w+)\\)").matcher(src);
        Map<Character, Key> out = new HashMap<>();
        while (m.find()) {
            String c = m.group(1);
            char ch = c.length() == 2 ? c.charAt(1) : c.charAt(0);
            out.put(ch, new Key(ch, m.group(2), m.group(3), m.group(4)));
        }
        legendCache = out;
        return out;
    }

    /** Every drawing, read as Blueprints.parse reads it, and after them the buildings that go up a storey
     *  in the Iron Age (Ages.TALL) as Blueprints.raised raises them ("house_tall"...). */
    static synchronized List<Drawing> drawings() throws IOException {
        if (drawingsCache != null) return drawingsCache;
        List<Drawing> out = new ArrayList<>(drawn());
        Set<String> tall = tall();
        for (Drawing d : List.copyOf(out)) {
            if (!tall.contains(d.name)) continue;
            Drawing r = raised(d);
            if (r != null) out.add(r);
        }
        drawingsCache = out;
        return out;
    }

    /** The names in Ages.TALL, out of Ages.java. */
    static Set<String> tall() throws IOException {
        String src = Files.readString(project().resolve(AGES_SOURCE), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("Set<String>\\s+TALL\\s*=\\s*Set\\.of\\(([^;]*)\\);").matcher(src);
        Set<String> out = new HashSet<>();
        if (!m.find()) return out;
        Matcher q = Pattern.compile("\"(\\w+)\"").matcher(m.group(1));
        while (q.find()) out.add(q.group(1));
        return out;
    }

    private static boolean roofish(Key k) {
        return "BLOCK".equals(k.part()) && switch (k.style()) {
            case "ROOF_STAIR", "ROOF_STAIR_TOP", "ROOF_SLAB", "ROOF_SLAB_TOP", "ROOF_BLOCK" -> true;
            default -> false;
        };
    }

    private static boolean wallish(Key k) {
        if ("WINDOW".equals(k.part())) return true;
        if ("LANTERN".equals(k.part())) return "HANGING".equals(k.style());
        if (!"BLOCK".equals(k.part())) return false;
        return switch (k.style()) {
            case "WALL", "POST", "MASONRY", "BRICK", "WALL_LOW", "GENERIC", "BEAM_ACROSS", "BEAM_ALONG", "GLASS" -> true;
            default -> false;
        };
    }

    /**
     * A building with a second storey put on, as Blueprints.raised puts it on (keep the two in step):
     * everything to the eaves as it was, the old ceiling the new floor (but for a ladder's hole), the
     * walls laid again a storey higher (the doorway walled), the roof lifted, and a ladder up against the
     * back wall in the first column nothing on the ground floor uses. Null for a building with no eaves.
     */
    static Drawing raised(Drawing base) throws IOException {
        List<Cell> cells = cells(base);
        int eaves = Integer.MAX_VALUE;
        for (Cell c : cells) if (c.h() >= 2 && roofish(c.key())) eaves = Math.min(eaves, c.h());
        if (eaves == Integer.MAX_VALUE) return null;
        int lift = eaves;
        Map<Character, Key> legend = legend();
        Key wall = legend.get('W'), ladder = legend.get('H');
        Set<Long> ring = new HashSet<>(), used = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : cells) {
            if (c.h() < 0 || c.h() >= eaves) continue;
            long k = Drawing.at(c.dx(), 0, c.dz());
            used.add(k);
            if ((wallish(c.key()) && !"LANTERN".equals(c.key().part())) || "DOOR".equals(c.key().part())) {
                ring.add(k);
                minX = Math.min(minX, c.dx()); maxX = Math.max(maxX, c.dx());
                minZ = Math.min(minZ, c.dz()); maxZ = Math.max(maxZ, c.dz());
            }
        }
        if (ring.isEmpty()) return null;
        int[] up = null;
        for (int dz = maxZ - 1; dz > minZ && up == null; dz--) {
            for (int dx = minX + 1; dx < maxX && up == null; dx++) {
                if (!used.contains(Drawing.at(dx, 0, dz)) && ring.contains(Drawing.at(dx, 0, dz + 1))) up = new int[]{ dx, dz };
            }
        }
        Drawing d = new Drawing(base.name + "_tall");
        for (Cell c : cells) {
            if (c.h() < eaves) d.put(c.dx(), c.h(), c.dz(), c.key());
            if (c.h() == eaves && !(up != null && c.dx() == up[0] && c.dz() == up[1])) d.put(c.dx(), c.h(), c.dz(), c.key());
        }
        for (Cell c : cells) if (c.h() >= eaves) d.put(c.dx(), c.h() + lift, c.dz(), c.key());
        for (int h = 1; h < eaves; h++) {
            Set<Long> laid = new HashSet<>();
            for (Cell c : cells) {
                if (c.h() != h || !wallish(c.key())) continue;
                d.put(c.dx(), c.h() + lift, c.dz(), c.key());
                laid.add(Drawing.at(c.dx(), 0, c.dz()));
            }
            for (Cell c : cells) {
                long k = Drawing.at(c.dx(), 0, c.dz());
                if (c.h() < 0 || c.h() >= eaves || !ring.contains(k) || laid.contains(k)) continue;
                d.put(c.dx(), h + lift, c.dz(), wall);                         // the doorway, walled up there
                laid.add(k);
            }
        }
        if (up != null) for (int h = 0; h <= eaves; h++) d.put(up[0], h, up[1], ladder);
        for (Cell c : cells) {                                   // [blueprints] a loft's ladder carried on up
            if (c.h() != eaves || !"LADDER".equals(c.key().part())) continue;
            for (int h = eaves + 1; h < eaves + lift; h++) d.put(c.dx(), h, c.dz(), c.key());
        }
        // A way up is wanted where the old ceiling is boarded, a floor to stand on up there.
        boolean floored = false;
        for (Cell c : cells) if (c.h() == eaves && "FLOOR".equals(c.key().style())) floored = true;
        d.wayUp = up != null || !floored;
        return d;
    }

    /** Every drawing file, read as Blueprints.parse reads it. */
    static List<Drawing> drawn() throws IOException {
        Map<Character, Key> legend = legend();
        List<Drawing> out = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(project().resolve(BLUEPRINTS))) {
            files = s.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
        for (Path f : files) {
            String name = f.getFileName().toString().replace(".txt", "");
            Drawing d = new Drawing(name);
            Integer layer = null;
            List<String> rows = new ArrayList<>();
            for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String line = raw.stripTrailing();
                if (line.startsWith("#") || line.isBlank() || line.startsWith("name ")) continue;
                if (line.startsWith("layer ")) {
                    if (layer != null) flush(d, layer, rows, legend);
                    layer = Integer.parseInt(line.substring(6).trim());
                    rows = new ArrayList<>();
                    continue;
                }
                rows.add(line);
            }
            if (layer != null) flush(d, layer, rows, legend);
            out.add(d);
        }
        return out;
    }

    private static void flush(Drawing d, int layer, List<String> rows, Map<Character, Key> legend) {
        int depth = rows.size(), width = 0;
        for (String r : rows) width = Math.max(width, r.length());
        for (int i = 0; i < depth; i++) {
            String row = rows.get(i);
            int dz = (depth - 1) / 2 - i;
            for (int j = 0; j < row.length(); j++) {
                char c = row.charAt(j);
                if (c == '.' || c == ' ') continue;
                int dx = j - (width - 1) / 2;
                Key k = legend.get(c);
                if (k == null) {
                    d.unknown.add(d.name + " layer " + layer + " (dx " + dx + ", dz " + dz + "): no such mark '" + c + "'");
                    continue;
                }
                d.put(dx, layer, dz, k);
            }
        }
    }

    private static void report(String what, List<String> faults) {
        StringBuilder sb = new StringBuilder(what).append(": ").append(faults.size()).append(faults.size() == 1 ? " fault" : " faults");
        for (String f : faults) sb.append("\n  ").append(f);
        assertTrue(faults.isEmpty(), sb.toString());
    }

    // ------------------------------------------------------------------ the checks

    @Test
    @DisplayName("every drawing reads: the legend is found, and every mark in every drawing is in it")
    void everyDrawingReads() throws IOException {
        Map<Character, Key> legend = legend();
        assertTrue(legend.size() > 40, "the legend read out of Blueprints.java: " + legend.size() + " marks");
        for (char c : "WFfLjlHDB~t".toCharArray()) assertTrue(legend.containsKey(c), "the legend has '" + c + "'");
        List<Drawing> all = drawings();
        assertTrue(all.size() > 20, "the drawings: " + all.size());
        List<String> faults = new ArrayList<>();
        for (Drawing d : all) {
            faults.addAll(d.unknown);
            if (d.cells.isEmpty()) faults.add(d.name + ": nothing drawn");
        }
        report("marks the game does not know", faults);
    }

    @Test
    @DisplayName("a hanging lantern hangs from something: a whole block, a beam, a low stair or slab, a post")
    void hangingLanternsHangFromSomething() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            for (Cell c : cells(d)) {
                if (!"LANTERN".equals(c.key().part()) || !"HANGING".equals(c.key().style())) continue;
                Shape above = d.shapeAt(c.dx(), c.h() + 1, c.dz());
                if (!holdsBelow(above)) {
                    faults.add(d.where(c.dx(), c.h(), c.dz()) + ": nothing above to hang from (" + above.name().toLowerCase() + ")");
                }
            }
        }
        report("hanging lanterns", faults);
    }

    @Test
    @DisplayName("whatever stands stands on something: torches, standing lanterns, flowers, rugs, doors, bells")
    void standingThingsStandOnSomething() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            for (Cell c : cells(d)) {
                Key k = c.key();
                Shape below = d.shapeAt(c.dx(), c.h() - 1, c.dz());
                Key under = d.key(c.dx(), c.h() - 1, c.dz());
                String here = d.where(c.dx(), c.h(), c.dz());
                switch (k.part()) {
                    case "TORCH" -> {
                        if (!holdsOnTop(below)) faults.add(here + ": a torch with nothing under it to stand on (" + below.name().toLowerCase() + ")");
                    }
                    case "LANTERN" -> {
                        if (!"HANGING".equals(k.style()) && !holdsOnTop(below)) {
                            faults.add(here + ": a standing lantern with nothing under it to stand on (" + below.name().toLowerCase() + ")");
                        }
                    }
                    case "FLOWER" -> {
                        // A flower grows in earth: the drawing's soil, or the ground under the lot.
                        boolean earth = under == null ? c.h() <= 0 : "SOIL".equals(under.style());
                        if (!earth) faults.add(here + ": a flower with no earth under it");
                    }
                    case "CARPET" -> {
                        if (below == Shape.AIR || below == Shape.WATER) faults.add(here + ": a rug over nothing");
                    }
                    case "DOOR" -> {
                        if (!sturdyTop(below)) faults.add(here + ": a door with no sill under it (" + below.name().toLowerCase() + ")");
                        Shape upper = d.shapeAt(c.dx(), c.h() + 1, c.dz());
                        if (upper != Shape.AIR) faults.add(here + ": no room above it for the door's upper half");
                    }
                    case "BELL" -> {
                        if (!sturdyTop(below)) faults.add(here + ": a bell with no floor under it (" + below.name().toLowerCase() + ")");
                    }
                    default -> { }
                }
            }
        }
        report("things that stand", faults);
    }

    @Test
    @DisplayName("a ladder has a whole block at its back")
    void laddersLeanOnAWall() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            for (Cell c : cells(d)) {
                if (!"LADDER".equals(c.key().part())) continue;
                // A ladder faces its way (toward the door, by default) and is fixed to the block behind it.
                int[] s = step(c.key().way());
                if (s[0] == 0 && s[1] == 0) s = step("FRONT");
                Shape back = d.shapeAt(c.dx() - s[0], c.h(), c.dz() - s[1]);
                if (back != Shape.FULL) faults.add(d.where(c.dx(), c.h(), c.dz()) + ": a ladder with no wall at its back (" + back.name().toLowerCase() + ")");
            }
        }
        report("ladders", faults);
    }

    @Test
    @DisplayName("a bed has room for both halves, and a floor under both")
    void bedsHaveRoomForBothHalves() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            for (Cell c : cells(d)) {
                if (!"BED".equals(c.key().part())) continue;
                // The mark is the foot; the head lies one block the bed's way (BuildGoal.bedHead).
                int[] s = step(c.key().way());
                int hx = c.dx() + s[0], hz = c.dz() + s[1];
                String here = d.where(c.dx(), c.h(), c.dz());
                if (d.key(hx, c.h(), hz) != null) faults.add(here + ": the head's place is taken by " + d.where(hx, c.h(), hz));
                if (!floor(d.shapeAt(c.dx(), c.h() - 1, c.dz()))) faults.add(here + ": no floor under the foot");
                if (!floor(d.shapeAt(hx, c.h() - 1, hz))) faults.add(here + ": no floor under the head");
                if (!passable(d.shapeAt(c.dx(), c.h() + 1, c.dz())) || !passable(d.shapeAt(hx, c.h() + 1, hz))) {
                    faults.add(here + ": no room over the bed to get into it");
                }
            }
        }
        report("beds", faults);
    }

    @Test
    @DisplayName("a door opens onto a floor at its own level, with headroom, on both sides")
    void doorsOpenOntoAFloor() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            for (Cell c : cells(d)) {
                if (!"DOOR".equals(c.key().part())) continue;
                int[] s = step(c.key().way());
                for (int side : new int[]{ -1, 1 }) {
                    int x = c.dx() + side * s[0], z = c.dz() + side * s[1];
                    String here = d.where(c.dx(), c.h(), c.dz()) + (side < 0 ? " (before it)" : " (behind it)");
                    if (!floor(d.shapeAt(x, c.h() - 1, z))) faults.add(here + ": no floor at the door's level");
                    if (!passable(d.shapeAt(x, c.h(), z)) || !passable(d.shapeAt(x, c.h() + 1, z))) {
                        faults.add(here + ": the way through is blocked by " + d.where(x, c.h(), z) + " / layer " + (c.h() + 1)
                            + " " + d.shapeAt(x, c.h() + 1, z).name().toLowerCase());
                    }
                }
            }
        }
        report("doors", faults);
    }

    @Test
    @DisplayName("water is held in: a basin round it and under it, or a fall straight into more of it")
    void waterIsHeldIn() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            for (Cell c : cells(d)) {
                if (!"WATER".equals(c.key().part())) continue;
                String here = d.where(c.dx(), c.h(), c.dz());
                // Water with nothing under it, or running over an edge, falls straight down; it is held
                // if it falls into water (a spring spilling into its basin). Anywhere else it spreads.
                if (!holdsWater(d.shapeAt(c.dx(), c.h() - 1, c.dz())) && !fallsIntoWater(d, c.dx(), c.h() - 1, c.dz())) {
                    faults.add(here + ": nothing under the water, and it runs out below");
                }
                for (int[] s : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                    int x = c.dx() + s[0], z = c.dz() + s[1];
                    if (holdsWater(d.shapeAt(x, c.h(), z))) continue;
                    if (!fallsIntoWater(d, x, c.h() - 1, z)) {
                        faults.add(here + ": runs out at (dx " + x + ", dz " + z + ")");
                    }
                }
            }
        }
        report("water", faults);
    }

    /** Falling from here straight down through the open, does water land in water? */
    static boolean fallsIntoWater(Drawing d, int x, int h, int z) {
        while (h > d.minH - 2 && !holdsWater(d.shapeAt(x, h, z))) h--;
        return d.shapeAt(x, h, z) == Shape.WATER;
    }

    /** What somebody comes to a building to use, and so must be able to get to: a bed, a door, a ladder, a
     *  workstation (a folk walks up to its bench to work: Trades.workstation), a bell. Not a chest or a
     *  barrel: in a building those are furniture, the stores being the Village Storehouse's alone. */
    private static final Set<String> USED = Set.of("BED", "DOOR", "LADDER", "CRAFTING_TABLE", "FURNACE", "SMOKER",
        "LECTERN", "ENCHANTING", "BREWING", "LOOM", "GRINDSTONE", "ANVIL", "BELL");

    @Test
    @DisplayName("every bed, door, ladder, chest, bench, bell and the like can be walked to from the street")
    void everythingCanBeWalkedTo() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            if (!d.wayUp) faults.add(d.name + ": no column against the back wall free for the ladder up to the new storey");
            Set<Long> reach = walk(d);
            for (Cell c : cells(d)) {
                String part = c.key().part();
                if (!USED.contains(part)) continue;
                boolean ok;
                if (part.equals("DOOR") || part.equals("LADDER")) {
                    ok = reach.contains(Drawing.at(c.dx(), c.h(), c.dz()));
                } else {
                    // Somebody standing beside it, level with it or a block below (a barrel on a barrel, a
                    // brewing stand on its bench), or beside either half of a bed.
                    List<int[]> places = new ArrayList<>();
                    places.add(new int[]{ c.dx(), c.dz() });
                    if (part.equals("BED")) {
                        int[] s = step(c.key().way());
                        places.add(new int[]{ c.dx() + s[0], c.dz() + s[1] });
                    }
                    ok = false;
                    for (int[] p : places) {
                        for (int[] n : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                            for (int h = c.h() - 1; h <= c.h(); h++) {
                                if (reach.contains(Drawing.at(p[0] + n[0], h, p[1] + n[1]))) ok = true;
                            }
                        }
                    }
                }
                if (!ok) faults.add(d.where(c.dx(), c.h(), c.dz()) + ": nobody can get to it from outside");
            }
        }
        report("the way in", faults);
    }

    // ------------------------------------------------------------------ [blueprints] the footprints kept

    /** Half the width and depth on the ground (Blueprints.groundHalf: layer 0 and under) and of everything (fullHalf). */
    static int[] footprint(Drawing d) {
        int gw = 0, gd = 0, fw = 0, fd = 0;
        for (Cell c : cells(d)) {
            fw = Math.max(fw, Math.abs(c.dx()));
            fd = Math.max(fd, Math.abs(c.dz()));
            if (c.h() > 0) continue;
            gw = Math.max(gw, Math.abs(c.dx()));
            gd = Math.max(gd, Math.abs(c.dz()));
        }
        return new int[]{ gw, gd, fw, fd };
    }

    /** Which side of the building its way in from the street is on: front, back, left, right, or none (no door). */
    static String doorSide(Drawing d) {
        Set<Long> outside = walk(d, false);
        List<Cell> street = streetDoors(d, outside);
        if (street.isEmpty()) return "none";
        Cell c = street.get(0);
        int[] out = outIn(c, outside)[0];
        int sx = Integer.signum(out[0] - c.dx()), sz = Integer.signum(out[2] - c.dz());
        return sz < 0 ? "front" : sz > 0 ? "back" : sx < 0 ? "left" : "right";
    }

    /**
     * Every drawing's footprint and the side its door is on, as the town plan lays its lots out for them (a
     * lot is sized by the building's half-width and half-depth: Blueprints.groundHalf and fullHalf, and its door
     * faces the street). Change a footprint or turn a door and the lots no longer fit: change it here only with
     * the town plan. "name groundW groundD fullW fullD doorSide".
     */
    static final List<String> FOOTPRINTS = List.of(
        "allotments 4 4 4 4 none",
        "arena 5 10 5 10 none",
        "armoury 3 3 4 4 front",
        "auction 3 5 4 5 front",
        "bakery 4 3 4 4 front",
        "bank 3 4 4 5 front",
        "barracks 3 6 4 7 front",
        "belltower 2 3 3 3 front",
        "brewery 4 3 4 4 front",
        "cafe 4 4 4 4 front",
        "cathedral 5 10 6 11 front",
        "chapel 3 10 4 10 front",
        "clockworkgate 5 4 6 4 right",
        "colossus 4 4 4 4 none",
        "court 5 3 5 3 none",
        "divershed 2 2 3 3 front",
        "firestation 3 4 3 4 front",
        "flats 5 5 5 5 front",
        "flats4 5 5 5 5 front",
        "fletcher 2 2 3 3 front",
        "fountain 3 3 3 3 none",
        "gateway 4 2 4 2 none",
        "golemyard 2 2 3 3 none",
        "granary 3 3 3 3 front",
        "grandbazaar 5 10 5 10 none",
        "grandlibrary 5 10 6 11 front",
        "graveyard 4 4 4 4 none",
        "greatforge 5 9 6 10 front",
        "greatlighthouse 4 4 4 4 front",
        "guesthouse 3 5 4 5 front",
        "hall 4 8 5 8 front",
        "house 4 4 4 4 front",
        "house2 4 4 4 4 front",
        "infirmary 3 5 4 5 front",
        "inn 4 4 4 5 front",
        "library 3 5 4 5 front",
        "lighthouse 3 3 3 3 front",
        "lodge 4 5 5 5 front",
        "manor 5 6 6 6 front",
        "maproom 3 3 4 4 front",
        "market 4 4 5 5 none",
        "monument 3 3 3 3 none",
        "museum 5 5 5 5 front",
        "observatory 5 5 5 5 front",
        "orchard 4 4 4 4 none",
        "park 5 5 5 5 none",
        "pitch 5 10 5 10 none",
        "postoffice 3 4 4 4 front",
        "powderhut 3 4 3 4 front",
        "range 5 5 5 5 none",
        "redstoneworks 3 3 4 4 front",
        "school 3 5 4 5 front",
        "shelter 2 2 3 3 none",
        "shop 3 3 4 4 front",
        "skygarden 5 8 5 8 front",
        "smeltery 3 3 4 4 none",
        "smithy 3 3 4 4 none",
        "stable 4 4 5 5 none",
        "station 2 4 2 4 none",
        "statue 2 2 2 2 none",
        "storage 2 3 3 4 front",
        "store 5 9 6 9 front",
        "storehouse 1 1 1 1 none",
        "tavern 4 5 5 5 front",
        "theatre 4 5 4 5 none",
        "townhall 5 10 6 10 front",
        "townlibrary 4 5 5 5 front",
        "tradingpost 2 2 3 3 none",
        "trainingyard 4 3 4 3 none",
        "villa 5 5 5 5 front",
        "watchhouse 4 5 4 5 front",
        "watchtower 2 2 3 3 front",
        "well 2 2 2 2 none",
        "windmill 2 2 4 3 front",
        "workshop 3 3 4 4 none"
    );

    @Test
    @DisplayName("[blueprints] every drawing keeps its footprint and its door's side (the town plan's lots are sized by them)")
    void footprintsKept() throws IOException {
        Map<String, String> want = new HashMap<>();
        for (String f : FOOTPRINTS) want.put(f.substring(0, f.indexOf(' ')), f);
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawn()) {
            int[] f = footprint(d);
            String got = d.name + " " + f[0] + " " + f[1] + " " + f[2] + " " + f[3] + " " + doorSide(d);
            if (!want.containsKey(d.name)) faults.add(d.name + ": a new drawing, not in FOOTPRINTS: " + got);
            else if (!want.get(d.name).equals(got)) faults.add(d.name + ": was \"" + want.get(d.name) + "\", now \"" + got + "\"");
        }
        report("footprints", faults);
    }

    // ------------------------------------------------------------------ [blueprints] the way in, the rooms, the roof

    /** The two places a door is between, at its own level: before it (toward its front) and behind it. */
    static int[][] sides(Cell door) {
        int[] s = step(door.key().way());
        return new int[][]{ { door.dx() - s[0], door.h(), door.dz() - s[1] }, { door.dx() + s[0], door.h(), door.dz() + s[1] } };
    }

    static List<Cell> doors(Drawing d) {
        List<Cell> out = new ArrayList<>();
        for (Cell c : cells(d)) if (isDoor(c.key())) out.add(c);
        return out;
    }

    /** The doors that open onto the open air: one side of them got to from round the lot with every door shut. */
    static List<Cell> streetDoors(Drawing d, Set<Long> outside) {
        List<Cell> out = new ArrayList<>();
        for (Cell c : doors(d)) {
            for (int[] p : sides(c)) if (outside.contains(Drawing.at(p[0], p[1], p[2]))) { out.add(c); break; }
        }
        return out;
    }

    /** The side of a street door that is out in the open, and the side that is in. */
    static int[][] outIn(Cell door, Set<Long> outside) {
        int[][] s = sides(door);
        return outside.contains(Drawing.at(s[0][0], s[0][1], s[0][2])) ? s : new int[][]{ s[1], s[0] };
    }

    /** The columns the walls of the ground storey stand in (as Blueprints.raised finds them), and its eaves. */
    static Set<Long> ring(Drawing d) {
        Set<Long> ring = new HashSet<>();
        int eaves = eaves(d);
        for (Cell c : cells(d)) {
            if (c.h() < 0 || c.h() >= eaves) continue;
            if ((wallish(c.key()) && !"LANTERN".equals(c.key().part())) || isDoor(c.key())) ring.add(Drawing.at(c.dx(), 0, c.dz()));
        }
        return ring;
    }

    /** The lowest roof course at layer 2 or over (as Blueprints.raised finds it); the top of the drawing if none. */
    static int eaves(Drawing d) {
        int eaves = Integer.MAX_VALUE;
        for (Cell c : cells(d)) if (c.h() >= 2 && roofish(c.key())) eaves = Math.min(eaves, c.h());
        return eaves == Integer.MAX_VALUE ? d.maxH + 1 : eaves;
    }

    /** Is there anything over this place, higher than a body standing in it: a roof, a ceiling, a canopy? */
    static boolean covered(Drawing d, int x, int h, int z) {
        for (int y = h + 2; y <= d.maxH; y++) {
            Shape s = d.shapeAt(x, y, z);
            if (s != Shape.AIR && s != Shape.SMALL && s != Shape.LANTERN && s != Shape.LADDER) return true;
        }
        return false;
    }

    @Test
    @DisplayName("[blueprints] a building with doors has one onto the street, with clear ground (or steps) in front of it")
    void aDoorOntoTheStreet() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            List<Cell> doors = doors(d);
            if (doors.isEmpty()) continue;
            Set<Long> outside = walk(d, false);
            List<Cell> street = streetDoors(d, outside);
            if (street.isEmpty()) {
                faults.add(d.name + ": none of its " + doors.size() + " doors opens onto the street (the first at "
                    + d.where(doors.get(0).dx(), doors.get(0).h(), doors.get(0).dz()) + ")");
                continue;
            }
            for (Cell c : street) {
                // Two clear places straight out from it: nobody comes out of a door into a wall or off a drop.
                int[][] oi = outIn(c, outside);
                int ox = oi[0][0] - c.dx(), oz = oi[0][2] - c.dz();
                int fx = c.dx() + 2 * ox, fz = c.dz() + 2 * oz;
                boolean clear = false;
                for (int h = c.h() - 1; h <= c.h() + 1; h++) if (outside.contains(Drawing.at(fx, h, fz))) clear = true;
                if (!clear) faults.add(d.where(c.dx(), c.h(), c.dz()) + ": the second place out of the door (dx " + fx + ", dz " + fz
                    + ") is not clear ground, nor a step up or down");
            }
        }
        report("the door onto the street", faults);
    }

    @Test
    @DisplayName("[blueprints] a lamp by every door onto the street: nobody fumbles for the way in after dark")
    void doorwaysAreLit() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            Set<Long> outside = walk(d, false);
            for (Cell c : streetDoors(d, outside)) {
                int[][] oi = outIn(c, outside);
                int[] out = oi[0], in = oi[1];
                boolean lit = false;
                for (Cell l : cells(d)) {
                    String part = l.key().part();
                    if (!part.equals("LANTERN") && !part.equals("TORCH") && !part.equals("CAMPFIRE")) continue;
                    if (l.h() < c.h() - 1 || l.h() > c.h() + 3) continue;
                    // Out in front of it, or (a door at the very edge of its lot) hung just inside it: a door lets the light by.
                    if (Math.abs(l.dx() - out[0]) <= 2 && Math.abs(l.dz() - out[2]) <= 2) lit = true;
                    if (Math.abs(l.dx() - in[0]) + Math.abs(l.dz() - in[2]) <= 1) lit = true;
                }
                if (!lit) faults.add(d.where(c.dx(), c.h(), c.dz()) + ": no lamp within two places of the way in");
            }
        }
        report("lit doorways", faults);
    }

    @Test
    @DisplayName("[blueprints] a building is got into through its doors, not through a gap in its walls")
    void inOnlyByTheDoors() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            Set<Long> outside = walk(d, false);
            Set<Long> ring = ring(d);
            for (Cell c : streetDoors(d, outside)) {
                int[] in = outIn(c, outside)[1];
                if (!outside.contains(Drawing.at(in[0], in[1], in[2]))) continue;
                // In behind the door and out again without it: find where, the way a builder would (the shortest
                // way out from behind the door, with the door shut) and say where it crosses the walls' line.
                faults.add(d.where(c.dx(), c.h(), c.dz()) + ": the inside is open to the street besides the door: " + gap(d, in, ring));
            }
        }
        report("gaps in the walls", faults);
    }

    /** The shortest way out from a place inside with the doors shut: where it goes through the walls' line. */
    static String gap(Drawing d, int[] from, Set<Long> ring) {
        Map<Long, Long> came = new HashMap<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        long start = Drawing.at(from[0], from[1], from[2]);
        came.put(start, start);
        todo.add(from);
        int x0 = d.minX - 1, x1 = d.maxX + 1, z0 = d.minZ - 1, z1 = d.maxZ + 1;
        while (!todo.isEmpty()) {
            int[] p = todo.poll();
            if (p[0] <= x0 || p[0] >= x1 || p[2] <= z0 || p[2] >= z1) {
                // Back along the way, to the first place in the walls' line.
                List<int[]> way = new ArrayList<>();
                long k = Drawing.at(p[0], p[1], p[2]);
                way.add(p);
                while (came.get(k) != k) {
                    k = came.get(k);
                    way.add(0, new int[]{ signed((k >> 40) & 0xfffff), signed((k >> 20) & 0xfffff), signed(k & 0xfffff) });
                }
                for (int[] q : way) if (ring.contains(Drawing.at(q[0], 0, q[2]))) return "through layer " + q[1] + " (dx " + q[0] + ", dz " + q[2] + ")";
                int[] q = way.get(Math.min(way.size() - 1, 1));
                return "by layer " + q[1] + " (dx " + q[0] + ", dz " + q[2] + ")";
            }
            for (int[] n : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                for (int dh = -3; dh <= 1; dh++) {
                    int nx = p[0] + n[0], nh = p[1] + dh, nz = p[2] + n[1];
                    if (dh == 1 && !passable(d.shapeAt(p[0], p[1] + 2, p[2]))) continue;
                    boolean clearDown = true;
                    for (int y = nh + 1; y <= p[1] + 1 && dh < 0; y++) if (!passable(d.shapeAt(nx, y, nz))) clearDown = false;
                    if (!clearDown || !stand(d, nx, nh, nz, false)) continue;
                    long k = Drawing.at(nx, nh, nz);
                    if (came.containsKey(k)) continue;
                    came.put(k, Drawing.at(p[0], p[1], p[2]));
                    todo.add(new int[]{ nx, nh, nz });
                }
            }
        }
        return "somewhere";
    }

    @Test
    @DisplayName("[blueprints] no one-block hole in a wall: what is open in a wall is a window, a door or a doorway")
    void noHolesInTheWalls() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            Set<Long> ring = ring(d);
            int eaves = eaves(d);
            for (long k : ring) {
                int x = signed((k >> 40) & 0xfffff), z = signed(k & 0xfffff);
                for (int h = 0; h < eaves; h++) {
                    if (d.key(x, h, z) != null) continue;
                    Key below = d.key(x, h - 1, z);
                    if (isDoor(below)) continue;                                                // a door's upper half
                    Shape b = d.shapeAt(x, h - 1, z), a = d.shapeAt(x, h + 1, z);
                    if (!(floor(b) || b == Shape.POST) || !(floor(a) || a == Shape.POST)) continue;
                    // Walled either side along the wall's line, and open both sides across it: a hole through a wall.
                    boolean alongX = wallAt(d, x - 1, h, z) && wallAt(d, x + 1, h, z)
                        && passable(d.shapeAt(x, h, z - 1)) && passable(d.shapeAt(x, h, z + 1));
                    boolean alongZ = wallAt(d, x, h, z - 1) && wallAt(d, x, h, z + 1)
                        && passable(d.shapeAt(x - 1, h, z)) && passable(d.shapeAt(x + 1, h, z));
                    if (alongX || alongZ) faults.add(d.where(x, h, z) + ": a one-block hole in the wall (glaze it, or wall it)");
                }
            }
        }
        report("holes in the walls", faults);
    }

    static boolean wallAt(Drawing d, int x, int h, int z) {
        Key k = d.key(x, h, z);
        return k != null && (wallish(k) || isDoor(k));
    }

    @Test
    @DisplayName("[blueprints] every chest opens (nothing solid on its lid) and every chest and cask can be got at")
    void chestsAndCasksCanBeGotAt() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            Set<Long> reach = walk(d);
            for (Cell c : cells(d)) {
                String part = c.key().part();
                if (!part.equals("CHEST") && !part.equals("BARREL")) continue;
                String here = d.where(c.dx(), c.h(), c.dz());
                Key lid = d.key(c.dx(), c.h() + 1, c.dz());
                // A chest will not open under a whole block that carries redstone: anything solid but glass.
                if (part.equals("CHEST") && lid != null && shape(lid) == Shape.FULL && !"GLASS".equals(lid.style())) {
                    faults.add(here + ": the chest cannot be opened, " + d.where(c.dx(), c.h() + 1, c.dz()) + " sits on its lid");
                }
                boolean ok = false;
                for (int[] n : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                    for (int h = c.h() - 2; h <= c.h(); h++) if (reach.contains(Drawing.at(c.dx() + n[0], h, c.dz() + n[1]))) ok = true;
                }
                if (!ok) faults.add(here + ": nobody can get to it");
            }
        }
        report("chests and casks", faults);
    }

    @Test
    @DisplayName("[blueprints] every storey can be walked to: no floor under a roof that nobody can get up to")
    void everyStoreyHasAWayUp() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            Set<Long> reach = walk(d);
            Set<Long> ring = ring(d);
            if (ring.isEmpty()) continue;
            Set<Long> lost = new HashSet<>();
            for (int h = 1; h <= d.maxH; h++) {
                for (int x = d.minX; x <= d.maxX; x++) {
                    for (int z = d.minZ; z <= d.maxZ; z++) {
                        if (!stand(d, x, h, z) || reach.contains(Drawing.at(x, h, z)) || !covered(d, x, h, z)) continue;
                        if (!within(ring, x, z)) continue;
                        lost.add(Drawing.at(x, h, z));
                    }
                }
            }
            // Gathered into floors: a floor of three places or more, not all of it tucked under the roof's slope, is a storey.
            Set<Long> done = new HashSet<>();
            for (long k : lost) {
                if (done.contains(k)) continue;
                List<int[]> floor = new ArrayList<>();
                ArrayDeque<Long> todo = new ArrayDeque<>(List.of(k));
                done.add(k);
                while (!todo.isEmpty()) {
                    long q = todo.poll();
                    int x = signed((q >> 40) & 0xfffff), h = signed((q >> 20) & 0xfffff), z = signed(q & 0xfffff);
                    floor.add(new int[]{ x, h, z });
                    for (int[] n : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                        for (int dh = -1; dh <= 1; dh++) {
                            long m = Drawing.at(x + n[0], h + dh, z + n[1]);
                            if (lost.contains(m) && done.add(m)) todo.add(m);
                        }
                    }
                }
                if (floor.size() < 3) continue;                                         // the top of a dresser
                // A floor with something on it (a bed, a chest, a lamp, a rug) or a door onto it is a storey somebody
                // is meant to be on; a bare one is the roof space, boarded over and shut up.
                boolean furnished = false;
                for (int[] p : floor) {
                    for (int y = p[1]; y <= p[1] + 1; y++) {
                        Key on = d.key(p[0], y, p[2]);
                        if (on != null && furnishing(on)) furnished = true;
                        for (int[] n : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                            Key m = d.key(p[0] + n[0], y, p[2] + n[1]);
                            if (m != null && furnishing(m) && !"LADDER".equals(m.part()) && !("LANTERN".equals(m.part()) && y > p[1])) furnished = true;
                        }
                    }
                }
                if (!furnished) continue;
                int[] p = floor.get(0);
                faults.add(d.where(p[0], p[1], p[2]) + ": a floor of " + floor.size() + " places nobody can get up to");
            }
        }
        report("storeys with no way up", faults);
    }

    /** Something put in a room for somebody: not the building itself, nor a flower bed on a roof garden. */
    static boolean furnishing(Key k) {
        return !"BLOCK".equals(k.part()) && !"WINDOW".equals(k.part()) && !"FLOWER".equals(k.part()) && !"FENCE".equals(k.part());
    }

    /** Is this inside the walls' outline (between its walls both ways)? */
    static boolean within(Set<Long> ring, int x, int z) {
        boolean l = false, r = false, f = false, b = false;
        for (long k : ring) {
            int rx = signed((k >> 40) & 0xfffff), rz = signed(k & 0xfffff);
            if (rz == z && rx < x) l = true;
            if (rz == z && rx > x) r = true;
            if (rx == x && rz < z) f = true;
            if (rx == x && rz > z) b = true;
        }
        return l && r && f && b;
    }

    @Test
    @DisplayName("[blueprints] no hole in an upper floor that somebody walking on it would fall down")
    void noHolesInTheFloors() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            Set<Long> reach = walk(d);
            for (int h = 1; h <= d.maxH; h++) {
                for (int x = d.minX; x <= d.maxX; x++) {
                    for (int z = d.minZ; z <= d.maxZ; z++) {
                        if (!passable(d.shapeAt(x, h, z)) || d.shapeAt(x, h, z) == Shape.LADDER) continue;
                        // Somebody walking the floor beside it, on the same boards.
                        boolean beside = false;
                        for (int[] n : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                            Key under = d.key(x + n[0], h, z + n[1]);
                            if (under != null && "FLOOR".equals(under.style()) && reach.contains(Drawing.at(x + n[0], h + 1, z + n[1]))) beside = true;
                        }
                        if (!beside || !passable(d.shapeAt(x, h + 1, z))) continue;
                        int y = h;
                        while (y > d.minH - 1 && passable(d.shapeAt(x, y, z)) && d.shapeAt(x, y, z) != Shape.LADDER) y--;
                        int fall = h + 1 - (y + 1);
                        if (d.shapeAt(x, y, z) == Shape.LADDER) continue;                       // a ladder's well
                        if (fall >= 3) faults.add(d.where(x, h, z) + ": a hole in the floor, a fall of " + fall + " (rail it, or floor it)");
                    }
                }
            }
        }
        report("holes in the floors", faults);
    }

    @Test
    @DisplayName("[blueprints] the roof keeps the rain out: everywhere in behind the doors has a roof over it")
    void roofsKeepTheRainOut() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            Set<Long> outside = walk(d, false), all = walk(d, true);
            if (streetDoors(d, outside).isEmpty()) continue;
            int open = 0;
            String first = null;
            for (long k : all) {
                if (outside.contains(k)) continue;
                int x = signed((k >> 40) & 0xfffff), h = signed((k >> 20) & 0xfffff), z = signed(k & 0xfffff);
                if (covered(d, x, h, z) || !enclosed(d, x, h, z)) continue;     // a roof garden, a gallery, a roof's slope
                open++;
                if (first == null) first = d.where(x, h, z);
            }
            if (open > 0) faults.add(first + ": open to the sky in behind the doors (" + open + " places)");
        }
        report("roofs", faults);
    }

    /** Walled in at head height every way: a room (its doorways lead on to more walls), not a terrace or a roof. */
    static boolean enclosed(Drawing d, int x, int h, int z) {
        for (int[] n : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
            int cx = x, cz = z;
            boolean wall = false;
            while (cx >= d.minX && cx <= d.maxX && cz >= d.minZ && cz <= d.maxZ) {
                cx += n[0];
                cz += n[1];
                if (!passable(d.shapeAt(cx, h + 1, cz)) || isDoor(d.key(cx, h, cz))) { wall = true; break; }
            }
            if (!wall) return false;
        }
        return true;
    }

    @Test
    @DisplayName("[blueprints] a counter has room in front of it for its price tag and its customer")
    void countersFaceTheirCustomers() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Drawing d : drawings()) {
            String base = d.name.replace("_tall", "");
            if (!base.equals("shop") && !base.equals("cafe") && !base.equals("store")) continue;
            List<Cell> doors = doors(d);
            if (doors.isEmpty()) continue;
            Cell door = doors.get(0);
            for (Cell c : doors(d)) if (c.h() < door.h() || c.h() == door.h() && c.dz() < door.dz()) door = c;
            Set<Long> reach = walk(d);
            for (Cell c : cells(d)) {
                if (!"BARREL".equals(c.key().part()) || d.key(c.dx(), c.h() + 1, c.dz()) != null) continue;
                if (base.equals("store") && !(c.dz() < 0 && c.h() == 0)) continue;
                // As Cafe.priceTag: the side toward the door, the one more that way.
                int ddx = door.dx() - c.dx(), ddz = door.dz() - c.dz();
                int fx = c.dx(), fz = c.dz();
                if (Math.abs(ddz) >= Math.abs(ddx)) fz += ddz >= 0 ? 1 : -1; else fx += ddx >= 0 ? 1 : -1;
                String here = d.where(c.dx(), c.h(), c.dz());
                if (d.key(fx, c.h(), fz) != null) faults.add(here + ": no room for its price tag, " + d.where(fx, c.h(), fz) + " is there");
                if (c.h() <= 1 && !reach.contains(Drawing.at(fx, c.h(), fz)) && !reach.contains(Drawing.at(fx, c.h() - 1, fz))) {
                    faults.add(here + ": nobody can come up to it from the door's side");
                }
            }
        }
        report("counters", faults);
    }

    /**
     * Every place somebody can stand, walking in from round the lot: on a floor with headroom, or on a
     * ladder; stepping across, up a block (a stair, a step, a jump) or down a few, and up and down ladders.
     */
    static Set<Long> walk(Drawing d) {
        return walk(d, true);
    }

    /** As walk, with the doors open or (for what lies behind them) shut. [blueprints] */
    static Set<Long> walk(Drawing d, boolean doorsOpen) {
        // Round the lot two wide: the street a door opens onto is clear ground two places out. [blueprints]
        int x0 = d.minX - 2, x1 = d.maxX + 2, z0 = d.minZ - 2, z1 = d.maxZ + 2, top = d.maxH + 2;
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                if (stand(d, x, 0, z, doorsOpen) && seen.add(Drawing.at(x, 0, z))) todo.add(new int[]{ x, 0, z });
            }
        }
        while (!todo.isEmpty()) {
            int[] p = todo.poll();
            int x = p[0], h = p[1], z = p[2];
            List<int[]> next = new ArrayList<>();
            for (int[] n : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                int nx = x + n[0], nz = z + n[1];
                if (nx < x0 || nx > x1 || nz < z0 || nz > z1) continue;
                next.add(new int[]{ nx, h, nz });
                if (passable(d.shapeAt(x, h + 2, z))) next.add(new int[]{ nx, h + 1, nz });      // up a step
                for (int down = 1; down <= 3; down++) {                                           // down a few
                    if (!passable(d.shapeAt(nx, h - down + 1, nz)) || !passable(d.shapeAt(nx, h - down + 2, nz))) break;
                    next.add(new int[]{ nx, h - down, nz });
                }
            }
            if (d.shapeAt(x, h, z) == Shape.LADDER || d.shapeAt(x, h + 1, z) == Shape.LADDER) {   // up and down a ladder,
                next.add(new int[]{ x, h + 1, z });                                               // or a jump to one overhead
            }
            if (d.shapeAt(x, h, z) == Shape.LADDER || d.shapeAt(x, h - 1, z) == Shape.LADDER) next.add(new int[]{ x, h - 1, z });
            for (int[] q : next) {
                if (q[1] < -1 || q[1] > top) continue;
                if (!stand(d, q[0], q[1], q[2], doorsOpen)) continue;
                if (seen.add(Drawing.at(q[0], q[1], q[2]))) todo.add(q);
            }
        }
        return seen;
    }

    /** Somebody can be here: room for the body, and a floor under the feet or a ladder in hand. */
    static boolean stand(Drawing d, int x, int h, int z) {
        return stand(d, x, h, z, true);
    }

    static boolean stand(Drawing d, int x, int h, int z, boolean doorsOpen) {
        if (!doorsOpen && (isDoor(d.key(x, h, z)) || isDoor(d.key(x, h - 1, z)) && d.key(x, h, z) == null)) return false;
        Shape feet = d.shapeAt(x, h, z), head = d.shapeAt(x, h + 1, z);
        if (!passable(feet) || !passable(head)) return false;
        if (feet == Shape.LADDER || d.shapeAt(x, h - 1, z) == Shape.LADDER) return true;
        return floor(d.shapeAt(x, h - 1, z));
    }

    static boolean isDoor(Key k) {
        return k != null && "DOOR".equals(k.part());
    }
}
