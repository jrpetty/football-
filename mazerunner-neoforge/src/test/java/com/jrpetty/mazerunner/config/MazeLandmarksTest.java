package com.jrpetty.mazerunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Corridor landmarks: enough of them, and never in the way. */
class MazeLandmarksTest {

    private final MazeConfigData cfg = MazeConfigs.get();

    @Test
    @DisplayName("landmarks populate the maze without overrunning it")
    void reasonableDensity() {
        int cells = 0;
        int marked = 0;
        for (int cx = 0; cx < cfg.gridCells; cx++) {
            for (int cz = 0; cz < cfg.gridCells; cz++) {
                if (cfg.inGlade(cx, cz)) continue;
                cells++;
                if (MazeLandmarks.hasLandmark(cfg, cx, cz)) marked++;
            }
        }
        assertTrue(marked > 200, "the maze should have plenty of landmarks (" + marked + ")");
        double share = marked / (double) cells;
        assertTrue(share < 0.20, "landmarks should stay special, not line every corridor (" + share + ")");
    }

    @Test
    @DisplayName("landmarks keep clear of the Glade, its door ring and the exit")
    void exclusionZones() {
        MazeConfigData.ExitDef exit = cfg.fixedExit();
        assertFalse(MazeLandmarks.hasLandmark(cfg, exit.cellX(), exit.cellZ()), "not in the exit cell");
        for (int cx = cfg.gladeCellMin - 1; cx <= cfg.gladeCellMax + 1; cx++) {
            for (int cz = cfg.gladeCellMin - 1; cz <= cfg.gladeCellMax + 1; cz++) {
                assertFalse(MazeLandmarks.hasLandmark(cfg, cx, cz),
                    "no landmark at " + cx + "," + cz + " (Glade or its door ring)");
            }
        }
        assertFalse(MazeLandmarks.hasLandmark(cfg, -1, 5), "nothing outside the grid");
        assertFalse(MazeLandmarks.hasLandmark(cfg, cfg.gridCells, 5), "nothing outside the grid");
    }

    /**
     * The safety invariant: a cell's open centre is a 2×2 of columns, and a
     * landmark may occupy at most one of them — so a corridor can never be
     * sealed by decoration.
     */
    @Test
    @DisplayName("a landmark never occupies more than one column of a cell's centre")
    void neverBlocksACorridor() {
        int lo = MazeConfigData.WALL_BAND;
        int hi = cfg.cellSize - 1 - MazeConfigData.WALL_BAND;
        int inspected = 0;

        for (int cx = 0; cx < cfg.gridCells; cx++) {
            for (int cz = 0; cz < cfg.gridCells; cz++) {
                if (!MazeLandmarks.hasLandmark(cfg, cx, cz)) continue;
                inspected++;
                int occupied = 0;
                for (int lx = lo; lx <= hi; lx++) {
                    for (int lz = lo; lz <= hi; lz++) {
                        int type = MazeLandmarks.typeFor(cfg, cx * cfg.cellSize + lx, cz * cfg.cellSize + lz);
                        if (type != MazeLandmarks.NONE) occupied++;
                    }
                }
                assertEquals(1, occupied,
                    "cell " + cx + "," + cz + " must use exactly one centre column, used " + occupied);
            }
        }
        assertTrue(inspected > 100, "checked a real sample (" + inspected + " cells)");
    }

    /** The cell's middle square is where a dead-end chest stands. */
    @Test
    @DisplayName("landmarks never take the chest column")
    void leavesTheChestColumnFree() {
        int mid = cfg.cellSize / 2;
        for (int cx = 0; cx < cfg.gridCells; cx++) {
            for (int cz = 0; cz < cfg.gridCells; cz++) {
                if (!MazeLandmarks.hasLandmark(cfg, cx, cz)) continue;
                assertEquals(MazeLandmarks.NONE,
                    MazeLandmarks.typeFor(cfg, cx * cfg.cellSize + mid, cz * cfg.cellSize + mid),
                    "cell " + cx + "," + cz + " put a landmark on the chest column");
            }
        }
    }

    @Test
    @DisplayName("landmarks only sit on open corridor floor")
    void onlyOnOpenFloor() {
        int checked = 0;
        for (int cx = 0; cx < cfg.gridCells && checked < 400; cx++) {
            for (int cz = 0; cz < cfg.gridCells && checked < 400; cz++) {
                if (!MazeLandmarks.hasLandmark(cfg, cx, cz)) continue;
                for (int lx = 0; lx < cfg.cellSize; lx++) {
                    for (int lz = 0; lz < cfg.cellSize; lz++) {
                        int bx = cx * cfg.cellSize + lx;
                        int bz = cz * cfg.cellSize + lz;
                        if (MazeLandmarks.typeFor(cfg, bx, bz) == MazeLandmarks.NONE) continue;
                        assertTrue(cfg.isBaseOpen(bx, bz),
                            "landmark at " + bx + "," + bz + " must stand on open floor");
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 0, "found landmark columns to check");
    }

    @Test
    @DisplayName("every landmark type gets used, and none is out of range")
    void typesAreInRangeAndVaried() {
        Set<Integer> seen = new HashSet<>();
        for (int cx = 0; cx < cfg.gridCells; cx++) {
            for (int cz = 0; cz < cfg.gridCells; cz++) {
                if (!MazeLandmarks.hasLandmark(cfg, cx, cz)) continue;
                int type = MazeLandmarks.typeAt(cx, cz);
                assertTrue(type >= 0 && type < MazeLandmarks.TYPES, "type out of range: " + type);
                seen.add(type);
                int corner = MazeLandmarks.featureCorner(cx, cz);
                assertTrue(corner >= 0 && corner < 3, "corner out of range: " + corner);
            }
        }
        assertEquals(MazeLandmarks.TYPES, seen.size(), "all landmark types should appear somewhere");
    }

    @Test
    @DisplayName("section accents cover all eight sections and stay in range")
    void accentsFollowSections() {
        Set<Integer> accents = new HashSet<>();
        int span = cfg.gridCells * cfg.cellSize;
        for (int x = 0; x < span; x += 13) {
            for (int z = 0; z < span; z += 13) {
                int accent = MazeLandmarks.accentFor(cfg, x, z);
                assertTrue(accent >= 0 && accent < MazeLandmarks.ACCENTS, "accent out of range: " + accent);
                accents.add(accent);
            }
        }
        assertEquals(MazeLandmarks.ACCENTS, accents.size(), "all eight section accents should occur");
    }

    @Test
    @DisplayName("placement is deterministic")
    void deterministic() {
        for (int cx = 20; cx < 30; cx++) {
            for (int cz = 20; cz < 30; cz++) {
                assertEquals(MazeLandmarks.hasLandmark(cfg, cx, cz), MazeLandmarks.hasLandmark(cfg, cx, cz));
                assertEquals(MazeLandmarks.typeAt(cx, cz), MazeLandmarks.typeAt(cx, cz));
                assertEquals(MazeLandmarks.featureCorner(cx, cz), MazeLandmarks.featureCorner(cx, cz));
            }
        }
    }
}
