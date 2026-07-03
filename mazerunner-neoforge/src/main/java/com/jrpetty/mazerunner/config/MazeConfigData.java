package com.jrpetty.mazerunner.config;

import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parsed view of {@code maze_config_v2.json} — the source of truth for the
 * whole maze: base corridor graph, toggle points, exits, the 7 layouts, and
 * all derived geometry. Pure Java (Gson only, no Minecraft classes) so it can
 * be validated standalone.
 *
 * <p>Coordinate model: the maze grid is {@code gridCells × gridCells} cells,
 * one cell = one chunk = 16 blocks. Config block coordinates are used directly
 * as world coordinates (cell (0,0) is world chunk (0,0); the Glade centre —
 * and world spawn — is at block ~(768, 768)). Each cell has an open 8-wide
 * centre strip at local 4..11; an open edge between two adjacent cells carves
 * the 8-wide connecting strip through the 4-block wall bands on both sides.
 */
public final class MazeConfigData {

    // ------------------------------------------------------------- types

    /** Inclusive block-space box. */
    public record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        public boolean contains(int x, int y, int z) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
        }
    }

    public record TogglePoint(String id, String kind, int ax, int az, int bx, int bz, Box box) {}

    public record ExitDef(String id, int cellX, int cellZ, int portalX, int portalY, int portalZ,
                          String facing, Box gapBox) {}

    public record DoorDef(String name, int ringCellX, int ringCellZ, Box box) {}

    public record LayoutDef(int index, String name, String exitId, Set<String> open) {}

    /** A mutable wall segment the runtime manages: a toggle, a Glade door, or an exit gap. */
    public record StateBox(Kind kind, String id, Box box) {
        public enum Kind { TOGGLE, DOOR, EXIT_GAP }
    }

    // ------------------------------------------------------------- fields

    /** World build limit: blocks exist at y 0..85, dimension height 86. */
    public static final int WORLD_MAX_Y = 85;

    public final int gridCells;
    public final int cellSize;
    public final int floorY;
    public final int wallBaseY;
    public final int wallTopY; // inclusive, clamped to the world build limit
    public final int gladeCellMin;
    public final int gladeCellMax;
    public final int gladeBlockMin;
    public final int gladeBlockMax;

    public final Set<Long> baseOpenEdges = new HashSet<>();
    public final Map<String, TogglePoint> togglePoints = new LinkedHashMap<>();
    public final Map<String, ExitDef> exits = new LinkedHashMap<>();
    public final List<LayoutDef> layouts = new ArrayList<>();
    public final List<DoorDef> doors = new ArrayList<>();

    /** chunk key ((cx&0xFFFFFFFFL)<<32 | cz&0xFFFFFFFFL) → mutable boxes touching it. */
    public final Map<Long, List<StateBox>> boxesByChunk = new HashMap<>();
    /** Deterministically chosen dead-end cells that hold a supply chest. */
    public final List<int[]> chestCells = new ArrayList<>();

    // ------------------------------------------------------------- parse

    public static MazeConfigData parse(Reader reader) {
        return new MazeConfigData(JsonParser.parseReader(reader).getAsJsonObject());
    }

    private MazeConfigData(JsonObject root) {
        JsonObject meta = root.getAsJsonObject("meta");
        this.gridCells = meta.get("gridCells").getAsInt();
        this.cellSize = meta.get("cellSize").getAsInt();
        this.floorY = meta.get("floorY").getAsInt();
        this.wallBaseY = meta.get("wallBaseY").getAsInt();
        this.wallTopY = Math.min(wallBaseY + meta.get("wallHeight").getAsInt() - 1, WORLD_MAX_Y);

        JsonArray glade = meta.getAsJsonArray("gladeBlocks");
        this.gladeBlockMin = glade.get(0).getAsInt();
        this.gladeBlockMax = glade.get(2).getAsInt();
        this.gladeCellMin = gladeBlockMin / cellSize;
        this.gladeCellMax = gladeBlockMax / cellSize;

        for (JsonElement e : root.getAsJsonArray("baseOpenEdges")) {
            int[] c = parseEdgeKey(e.getAsString());
            baseOpenEdges.add(edgeKey(c[0], c[1], c[2], c[3]));
        }

        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("togglePoints").entrySet()) {
            JsonObject tp = entry.getValue().getAsJsonObject();
            int[] c = parseEdgeKey(tp.get("edge").getAsString());
            JsonArray f = tp.getAsJsonArray("from");
            JsonArray t = tp.getAsJsonArray("to");
            Box box = new Box(f.get(0).getAsInt(), Math.min(f.get(1).getAsInt(), WORLD_MAX_Y), f.get(2).getAsInt(),
                t.get(0).getAsInt(), Math.min(t.get(1).getAsInt(), WORLD_MAX_Y), t.get(2).getAsInt());
            togglePoints.put(entry.getKey(),
                new TogglePoint(entry.getKey(), tp.get("kind").getAsString(), c[0], c[1], c[2], c[3], box));
        }

        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("exits").entrySet()) {
            JsonObject ex = entry.getValue().getAsJsonObject();
            JsonArray cell = ex.getAsJsonArray("cell");
            JsonArray portal = ex.getAsJsonArray("portal");
            int cx = cell.get(0).getAsInt();
            int cz = cell.get(1).getAsInt();
            String facing = ex.get("facing").getAsString();
            exits.put(entry.getKey(), new ExitDef(entry.getKey(), cx, cz,
                portal.get(0).getAsInt(), portal.get(1).getAsInt(), portal.get(2).getAsInt(),
                facing, exitGapBox(cx, cz, facing)));
        }

        int li = 0;
        for (JsonElement e : root.getAsJsonArray("layouts")) {
            JsonObject l = e.getAsJsonObject();
            Set<String> open = new HashSet<>();
            for (JsonElement id : l.getAsJsonArray("open")) open.add(id.getAsString());
            layouts.add(new LayoutDef(li++, l.get("name").getAsString(),
                l.get("exit").getAsString(), Collections.unmodifiableSet(open)));
        }

        for (Map.Entry<String, JsonElement> entry : meta.getAsJsonObject("doors").entrySet()) {
            JsonArray rc = entry.getValue().getAsJsonObject().getAsJsonArray("ringCell");
            int rx = rc.get(0).getAsInt();
            int rz = rc.get(1).getAsInt();
            doors.add(new DoorDef(entry.getKey(), rx, rz, doorBox(rx, rz)));
        }

        indexBoxes();
        pickChestCells();
    }

    private static int[] parseEdgeKey(String key) {
        // "x,z>x2,z2"
        int gt = key.indexOf('>');
        String[] a = key.substring(0, gt).split(",");
        String[] b = key.substring(gt + 1).split(",");
        return new int[] {
            Integer.parseInt(a[0]), Integer.parseInt(a[1]),
            Integer.parseInt(b[0]), Integer.parseInt(b[1]),
        };
    }

    // ------------------------------------------------------------- keys

    public static long edgeKey(int ax, int az, int bx, int bz) {
        // Normalise so (a) is the lesser cell.
        if (bx < ax || (bx == ax && bz < az)) {
            int tx = ax; ax = bx; bx = tx;
            int tz = az; az = bz; bz = tz;
        }
        return (((long) (ax * 4096 + az)) << 24) | (bx * 4096L + bz);
    }

    public static long chunkKey(int cx, int cz) {
        return ((cx & 0xFFFFFFFFL) << 32) | (cz & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------- geometry

    public boolean inGrid(int cellX, int cellZ) {
        return cellX >= 0 && cellZ >= 0 && cellX < gridCells && cellZ < gridCells;
    }

    public boolean inGlade(int cellX, int cellZ) {
        return cellX >= gladeCellMin && cellX <= gladeCellMax
            && cellZ >= gladeCellMin && cellZ <= gladeCellMax;
    }

    public boolean isBaseOpenEdge(int ax, int az, int bx, int bz) {
        return baseOpenEdges.contains(edgeKey(ax, az, bx, bz));
    }

    /**
     * Base (permanent) geometry at block level: true = floor/corridor, false =
     * wall. Toggle points, doors and exit gaps are all "closed" in the base —
     * the runtime opens them per day. Blocks outside the grid are void.
     */
    public boolean isBaseOpen(int blockX, int blockZ) {
        int cx = Math.floorDiv(blockX, cellSize);
        int cz = Math.floorDiv(blockZ, cellSize);
        if (!inGrid(cx, cz)) return false;
        if (inGlade(cx, cz)) return true;
        int lx = Math.floorMod(blockX, cellSize);
        int lz = Math.floorMod(blockZ, cellSize);
        boolean midX = lx >= 4 && lx <= 11;
        boolean midZ = lz >= 4 && lz <= 11;
        if (midX && midZ) return true; // open cell centre
        if (midZ && lx >= 12) return isBaseOpenEdge(cx, cz, cx + 1, cz);
        if (midZ && lx <= 3) return isBaseOpenEdge(cx - 1, cz, cx, cz);
        if (midX && lz >= 12) return isBaseOpenEdge(cx, cz, cx, cz + 1);
        if (midX && lz <= 3) return isBaseOpenEdge(cx, cz - 1, cx, cz);
        return false; // corner blocks are always wall
    }

    /** Box across the shared edge of two adjacent cells (matches the generator script). */
    public Box edgeBox(int ax, int az, int bx, int bz) {
        if (bx == ax + 1) {
            return new Box(ax * cellSize + 12, wallBaseY, az * cellSize + 4,
                ax * cellSize + 19, wallTopY, az * cellSize + 11);
        }
        return new Box(ax * cellSize + 4, wallBaseY, az * cellSize + 12,
            ax * cellSize + 11, wallTopY, az * cellSize + 19);
    }

    /** A Glade door: the edge box between the ring cell and its adjacent Glade cell. */
    private Box doorBox(int ringX, int ringZ) {
        int gx = Math.max(gladeCellMin, Math.min(gladeCellMax, ringX));
        int gz = Math.max(gladeCellMin, Math.min(gladeCellMax, ringZ));
        // Exactly one axis differs; normalise order for edgeBox.
        int ax = Math.min(ringX, gx);
        int az = Math.min(ringZ, gz);
        int bx = Math.max(ringX, gx);
        int bz = Math.max(ringZ, gz);
        return edgeBox(ax, az, bx, bz);
    }

    /** The opening through the outer border wall in front of an exit portal. */
    private Box exitGapBox(int cellX, int cellZ, String facing) {
        int bx = cellX * cellSize;
        int bz = cellZ * cellSize;
        return switch (facing) {
            case "north" -> new Box(bx + 4, wallBaseY, bz, bx + 11, wallTopY, bz + 3);
            case "south" -> new Box(bx + 4, wallBaseY, bz + 12, bx + 11, wallTopY, bz + 15);
            case "west" -> new Box(bx, wallBaseY, bz + 4, bx + 3, wallTopY, bz + 11);
            default -> new Box(bx + 12, wallBaseY, bz + 4, bx + 15, wallTopY, bz + 11);
        };
    }

    public LayoutDef layout(int index) {
        return layouts.get(Math.floorMod(index, layouts.size()));
    }

    /** 1..8 clockwise section number (film-style), from the Glade centre; 1 = north. */
    public int sectionOf(int blockX, int blockZ) {
        double cx = gridCells * cellSize / 2.0;
        double dx = blockX + 0.5 - cx;
        double dz = blockZ + 0.5 - cx;
        double ang = Math.atan2(dx, -dz); // 0 at north, clockwise positive
        double norm = (ang + 2 * Math.PI + Math.PI / 8) % (2 * Math.PI); // sectors centred on compass points
        return (int) (norm / (Math.PI / 4)) + 1;
    }

    // ------------------------------------------------------------- indices

    private void indexBoxes() {
        for (TogglePoint tp : togglePoints.values()) {
            index(new StateBox(StateBox.Kind.TOGGLE, tp.id(), tp.box()));
        }
        for (DoorDef door : doors) {
            index(new StateBox(StateBox.Kind.DOOR, door.name(), door.box()));
        }
        for (ExitDef exit : exits.values()) {
            index(new StateBox(StateBox.Kind.EXIT_GAP, exit.id(), exit.gapBox()));
        }
    }

    private void index(StateBox sb) {
        for (int cx = sb.box().x0() >> 4; cx <= sb.box().x1() >> 4; cx++) {
            for (int cz = sb.box().z0() >> 4; cz <= sb.box().z1() >> 4; cz++) {
                boxesByChunk.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(sb);
            }
        }
    }

    public List<StateBox> boxesIn(int chunkX, int chunkZ) {
        return boxesByChunk.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
    }

    /** Dead-end cells (degree 1 in the base graph), thinned to a spread-out set. */
    private void pickChestCells() {
        Map<Long, Integer> degree = new HashMap<>();
        for (long key : baseOpenEdges) {
            long a = key >>> 24;
            long b = key & 0xFFFFFF;
            degree.merge(a, 1, Integer::sum);
            degree.merge(b, 1, Integer::sum);
        }
        List<long[]> deadEnds = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : degree.entrySet()) {
            if (e.getValue() != 1) continue;
            int cx = (int) (e.getKey() / 4096);
            int cz = (int) (e.getKey() % 4096);
            // keep chests away from doors and the ring corridor
            boolean nearGlade = cx >= gladeCellMin - 2 && cx <= gladeCellMax + 2
                && cz >= gladeCellMin - 2 && cz <= gladeCellMax + 2;
            if (!nearGlade) deadEnds.add(new long[] { e.getKey(), cx, cz });
        }
        deadEnds.sort((a, b) -> Long.compare(a[0], b[0]));
        int step = Math.max(1, deadEnds.size() / 100); // ~100 chests
        for (int i = 0; i < deadEnds.size(); i += step) {
            chestCells.add(new int[] { (int) deadEnds.get(i)[1], (int) deadEnds.get(i)[2] });
        }
    }

    // ------------------------------------------------------------- validate

    /**
     * BFS from the Glade (through all four doors) to a layout's exit cell over
     * the base graph plus that layout's open toggles. Returns the distance in
     * cells, or -1 if unreachable.
     */
    public int validate(int layoutIndex) {
        LayoutDef layout = layout(layoutIndex);
        Set<Long> open = new HashSet<>(baseOpenEdges);
        for (String id : layout.open()) {
            TogglePoint tp = togglePoints.get(id);
            if (tp != null) open.add(edgeKey(tp.ax(), tp.az(), tp.bx(), tp.bz()));
        }
        ExitDef exit = exits.get(layout.exitId());
        long target = exit.cellX() * 4096L + exit.cellZ();

        Map<Long, Integer> dist = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (DoorDef door : doors) {
            long start = door.ringCellX() * 4096L + door.ringCellZ();
            dist.put(start, 0);
            queue.add(start);
        }
        while (!queue.isEmpty()) {
            long cur = queue.poll();
            if (cur == target) return dist.get(cur);
            int cx = (int) (cur / 4096);
            int cz = (int) (cur % 4096);
            int d = dist.get(cur);
            int[][] steps = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
            for (int[] s : steps) {
                int nx = cx + s[0];
                int nz = cz + s[1];
                if (!inGrid(nx, nz) || inGlade(nx, nz)) continue;
                if (!open.contains(edgeKey(cx, cz, nx, nz))) continue;
                long nk = nx * 4096L + nz;
                if (dist.containsKey(nk)) continue;
                dist.put(nk, d + 1);
                queue.add(nk);
            }
        }
        return -1;
    }
}
