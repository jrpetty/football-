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

    /**
     * Every place somebody can stand, walking in from round the lot: on a floor with headroom, or on a
     * ladder; stepping across, up a block (a stair, a step, a jump) or down a few, and up and down ladders.
     */
    static Set<Long> walk(Drawing d) {
        int x0 = d.minX - 1, x1 = d.maxX + 1, z0 = d.minZ - 1, z1 = d.maxZ + 1, top = d.maxH + 2;
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                if (stand(d, x, 0, z) && seen.add(Drawing.at(x, 0, z))) todo.add(new int[]{ x, 0, z });
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
                if (!stand(d, q[0], q[1], q[2])) continue;
                if (seen.add(Drawing.at(q[0], q[1], q[2]))) todo.add(q);
            }
        }
        return seen;
    }

    /** Somebody can be here: room for the body, and a floor under the feet or a ladder in hand. */
    static boolean stand(Drawing d, int x, int h, int z) {
        Shape feet = d.shapeAt(x, h, z), head = d.shapeAt(x, h + 1, z);
        if (!passable(feet) || !passable(head)) return false;
        if (feet == Shape.LADDER || d.shapeAt(x, h - 1, z) == Shape.LADDER) return true;
        return floor(d.shapeAt(x, h - 1, z));
    }
}
