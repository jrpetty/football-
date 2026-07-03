package com.jrpetty.mazerunner.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Points of interest inside the maze — pure placement logic, no Minecraft.
 *
 * <p>A fixed set of "plazas": 2×2-cell clearings whose interior walls are
 * removed (which can only ADD connectivity, so layouts stay solvable). Each
 * plaza hosts a procedural ruin: a broken shelter, a pillar court, a
 * collapsed watchtower, or a surface ruin with an underground dungeon room
 * (spawner + loot). Placement is deterministic and identical in every world,
 * chosen away from the Glade, the border, exits, and any toggle point whose
 * wall the plaza would swallow.
 */
public final class MazeStructures {

    public static final int TYPE_SHELTER = 0;
    public static final int TYPE_PILLARS = 1;
    public static final int TYPE_DUNGEON = 2;
    public static final int TYPE_TOWER = 3;

    public static final int PLAZA_COUNT = 10;
    private static final int SPACING_CELLS = 14;
    private static final long SEED = 0x6D617A65C0FFEEL;

    /** One 2×2-cell clearing; (cellX, cellZ) is its north-west cell. */
    public record Plaza(int cellX, int cellZ, int type) {
        /** Anchor: the block at the shared corner of the four cells. */
        public int anchorX() { return (cellX + 1) * 16; }

        public int anchorZ() { return (cellZ + 1) * 16; }

        public boolean containsCell(int cx, int cz) {
            return cx >= cellX && cx <= cellX + 1 && cz >= cellZ && cz <= cellZ + 1;
        }

        public boolean touchesChunk(int chunkX, int chunkZ) {
            return containsCell(chunkX, chunkZ);
        }
    }

    private static volatile List<Plaza> plazas;
    private static Map<Long, Plaza> byCell;
    private static Set<Long> interiorEdges;
    private static List<int[]> chests;   // {x, y, z}
    private static List<int[]> spawners; // {x, y, z}

    private MazeStructures() {}

    // ------------------------------------------------------------- build

    public static List<Plaza> plazas(MazeConfigData cfg) {
        List<Plaza> local = plazas;
        if (local == null) {
            synchronized (MazeStructures.class) {
                if (plazas == null) compute(cfg);
                local = plazas;
            }
        }
        return local;
    }

    private static void compute(MazeConfigData cfg) {
        // Edges that carry a toggle point must never become plaza interior.
        Set<Long> toggleEdges = new HashSet<>();
        for (MazeConfigData.TogglePoint tp : cfg.togglePoints.values()) {
            toggleEdges.add(MazeConfigData.edgeKey(tp.ax(), tp.az(), tp.bx(), tp.bz()));
        }

        int exMin = cfg.gladeCellMin - 5; // keep clear of the Glade ring
        int exMax = cfg.gladeCellMax + 5;

        List<int[]> candidates = new ArrayList<>();
        for (int cx = 4; cx <= cfg.gridCells - 6; cx++) {
            for (int cz = 4; cz <= cfg.gridCells - 6; cz++) {
                boolean nearGlade = cx + 1 >= exMin && cx <= exMax && cz + 1 >= exMin && cz <= exMax;
                if (nearGlade) continue;
                if (toggleEdges.contains(MazeConfigData.edgeKey(cx, cz, cx + 1, cz))
                    || toggleEdges.contains(MazeConfigData.edgeKey(cx, cz, cx, cz + 1))
                    || toggleEdges.contains(MazeConfigData.edgeKey(cx + 1, cz, cx + 1, cz + 1))
                    || toggleEdges.contains(MazeConfigData.edgeKey(cx, cz + 1, cx + 1, cz + 1))) {
                    continue;
                }
                candidates.add(new int[] { cx, cz });
            }
        }

        Collections.shuffle(candidates, new Random(SEED));
        int[] typeOrder = { TYPE_DUNGEON, TYPE_SHELTER, TYPE_PILLARS, TYPE_TOWER,
            TYPE_DUNGEON, TYPE_SHELTER, TYPE_PILLARS, TYPE_TOWER, TYPE_SHELTER, TYPE_DUNGEON };

        List<Plaza> picked = new ArrayList<>();
        for (int[] cand : candidates) {
            if (picked.size() >= PLAZA_COUNT) break;
            boolean tooClose = false;
            for (Plaza p : picked) {
                if (Math.max(Math.abs(p.cellX() - cand[0]), Math.abs(p.cellZ() - cand[1])) < SPACING_CELLS) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) picked.add(new Plaza(cand[0], cand[1], typeOrder[picked.size()]));
        }

        Map<Long, Plaza> cellIndex = new HashMap<>();
        Set<Long> edges = new HashSet<>();
        List<int[]> chestList = new ArrayList<>();
        List<int[]> spawnerList = new ArrayList<>();
        for (Plaza p : picked) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    cellIndex.put(MazeConfigData.chunkKey(p.cellX() + dx, p.cellZ() + dz), p);
                }
            }
            edges.add(MazeConfigData.edgeKey(p.cellX(), p.cellZ(), p.cellX() + 1, p.cellZ()));
            edges.add(MazeConfigData.edgeKey(p.cellX(), p.cellZ(), p.cellX(), p.cellZ() + 1));
            edges.add(MazeConfigData.edgeKey(p.cellX() + 1, p.cellZ(), p.cellX() + 1, p.cellZ() + 1));
            edges.add(MazeConfigData.edgeKey(p.cellX(), p.cellZ() + 1, p.cellX() + 1, p.cellZ() + 1));

            int ax = p.anchorX();
            int az = p.anchorZ();
            switch (p.type()) {
                case TYPE_SHELTER -> chestList.add(new int[] { ax + 2, cfg.wallBaseY, az + 1 });
                case TYPE_TOWER -> chestList.add(new int[] { ax + 1, cfg.wallBaseY, az });
                case TYPE_DUNGEON -> {
                    chestList.add(new int[] { ax - 3, cfg.floorY - 3, az - 3 });
                    chestList.add(new int[] { ax + 3, cfg.floorY - 3, az + 3 });
                    spawnerList.add(new int[] { ax, cfg.floorY - 3, az });
                }
                default -> { /* pillar court has no loot */ }
            }
        }

        byCell = cellIndex;
        interiorEdges = edges;
        chests = chestList;
        spawners = spawnerList;
        plazas = picked; // publish last
    }

    // ------------------------------------------------------------- queries

    public static Plaza plazaAtCell(MazeConfigData cfg, int cellX, int cellZ) {
        plazas(cfg);
        return byCell.get(MazeConfigData.chunkKey(cellX, cellZ));
    }

    /**
     * Full floor-plan test replacing {@link MazeConfigData#isBaseOpen}:
     * base geometry plus plaza interiors (their inner wall bands and the
     * shared corner post are cleared).
     */
    public static boolean isOpen(MazeConfigData cfg, int blockX, int blockZ) {
        int cx = Math.floorDiv(blockX, cfg.cellSize);
        int cz = Math.floorDiv(blockZ, cfg.cellSize);
        if (!cfg.inGrid(cx, cz)) return false;
        if (cfg.inGlade(cx, cz)) return true;
        plazas(cfg);

        int lx = Math.floorMod(blockX, cfg.cellSize);
        int lz = Math.floorMod(blockZ, cfg.cellSize);
        boolean midX = lx >= 4 && lx <= 11;
        boolean midZ = lz >= 4 && lz <= 11;
        if (midX && midZ) return true;
        if (midZ && lx >= 12) return edgeOpen(cfg, cx, cz, cx + 1, cz);
        if (midZ && lx <= 3) return edgeOpen(cfg, cx - 1, cz, cx, cz);
        if (midX && lz >= 12) return edgeOpen(cfg, cx, cz, cx, cz + 1);
        if (midX && lz <= 3) return edgeOpen(cfg, cx, cz - 1, cx, cz);

        // corner post: open only when all four meeting cells share one plaza
        int nx = lx >= 12 ? cx + 1 : cx - 1;
        int nz = lz >= 12 ? cz + 1 : cz - 1;
        Plaza p = byCell.get(MazeConfigData.chunkKey(cx, cz));
        return p != null && p.containsCell(nx, cz) && p.containsCell(cx, nz) && p.containsCell(nx, nz);
    }

    private static boolean edgeOpen(MazeConfigData cfg, int ax, int az, int bx, int bz) {
        long key = MazeConfigData.edgeKey(ax, az, bx, bz);
        return cfg.baseOpenEdges.contains(key) || interiorEdges.contains(key);
    }

    public static List<int[]> allChests(MazeConfigData cfg) {
        plazas(cfg);
        return chests;
    }

    public static List<int[]> chestsIn(MazeConfigData cfg, int chunkX, int chunkZ) {
        plazas(cfg);
        List<int[]> out = new ArrayList<>();
        for (int[] c : chests) {
            if (c[0] >> 4 == chunkX && c[2] >> 4 == chunkZ) out.add(c);
        }
        return out;
    }

    public static List<int[]> spawnersIn(MazeConfigData cfg, int chunkX, int chunkZ) {
        plazas(cfg);
        List<int[]> out = new ArrayList<>();
        for (int[] s : spawners) {
            if (s[0] >> 4 == chunkX && s[2] >> 4 == chunkZ) out.add(s);
        }
        return out;
    }
}
