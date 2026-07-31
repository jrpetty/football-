package com.jrpetty.mazerunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The authored maze itself: graph, solvability, geometry and chest placement. */
class MazeConfigDataTest {

    private final MazeConfigData cfg = MazeConfigs.get();

    @Test
    @DisplayName("bundled config loads at the minigame play scale")
    void loadsAtPlayScale() {
        assertEquals(6, cfg.cellSize, "cell size");
        assertEquals(2, cfg.corridorWidth(), "corridor width");
        assertEquals(96, cfg.gridCells, "grid size");
        assertEquals(40, cfg.gladeCellMin);
        assertEquals(55, cfg.gladeCellMax);
        assertEquals(240, cfg.gladeBlockMin);
        assertEquals(335, cfg.gladeBlockMax);
        assertEquals(7, cfg.layouts.size(), "seven daily layouts");
        assertEquals(4, cfg.doors.size(), "four Glade doors");
        assertFalse(cfg.togglePoints.isEmpty(), "toggle points parsed");
    }

    @Test
    @DisplayName("all seven daily layouts are solvable to the one fixed exit")
    void everyLayoutSolvable() {
        assertNotNull(cfg.fixedExitId, "a fixed exit was chosen");
        for (int i = 0; i < cfg.layouts.size(); i++) {
            int dist = cfg.validate(i);
            assertTrue(dist > 0, cfg.layout(i).name() + " must reach exit " + cfg.fixedExitId
                + " (got distance " + dist + ")");
        }
    }

    @Test
    @DisplayName("the exit portal sits outside the maze square")
    void exitPortalOutsideGrid() {
        MazeConfigData.ExitDef exit = cfg.fixedExit();
        int span = cfg.gridCells * cfg.cellSize;
        boolean outside = exit.portalX() < 0 || exit.portalZ() < 0
            || exit.portalX() >= span || exit.portalZ() >= span;
        assertTrue(outside, "portal at " + exit.portalX() + "," + exit.portalZ() + " must be outside 0.." + span);
    }

    @Test
    @DisplayName("the maze actually changes between consecutive days")
    void layoutsDifferDayToDay() {
        for (int i = 0; i < cfg.layouts.size(); i++) {
            Set<String> today = cfg.open(i);
            Set<String> tomorrow = cfg.open((i + 1) % cfg.layouts.size());
            int changed = 0;
            for (MazeConfigData.TogglePoint tp : cfg.togglePoints.values()) {
                if (today.contains(tp.id()) != tomorrow.contains(tp.id())) changed++;
            }
            assertTrue(changed > 0,
                cfg.layout(i).name() + " → " + cfg.layout(i + 1).name() + " must move some walls");
        }
    }

    @Test
    @DisplayName("the weekly cycle repeats: layout 7 wraps back to layout 1")
    void weeklyCycleRepeats() {
        assertEquals(cfg.layout(0).index(), cfg.layout(cfg.layouts.size()).index());
        assertEquals(cfg.open(0), cfg.open(cfg.layouts.size()));
    }

    @Test
    @DisplayName("base floor plan: Glade open, outer corner solid, corridors never sealed")
    void baseGeometry() {
        assertTrue(cfg.isBaseOpen(cfg.gladeBlockMin + 10, cfg.gladeBlockMin + 10), "Glade interior open");
        assertFalse(cfg.isBaseOpen(0, 0), "grid corner is wall");

        int openCentres = 0;
        for (int cx = 0; cx < cfg.gridCells; cx++) {
            for (int cz = 0; cz < cfg.gridCells; cz++) {
                if (cfg.inGlade(cx, cz)) continue;
                int bx = cx * cfg.cellSize + cfg.cellSize / 2;
                int bz = cz * cfg.cellSize + cfg.cellSize / 2;
                assertTrue(cfg.isBaseOpen(bx, bz),
                    "cell centre (" + cx + "," + cz + ") must be walkable corridor");
                openCentres++;
            }
        }
        assertTrue(openCentres > 1000, "found the corridor network (" + openCentres + " cells)");
    }

    @Test
    @DisplayName("edge keys are order-independent")
    void edgeKeysNormalise() {
        assertEquals(MazeConfigData.edgeKey(4, 7, 5, 7), MazeConfigData.edgeKey(5, 7, 4, 7));
        assertEquals(MazeConfigData.edgeKey(9, 2, 9, 3), MazeConfigData.edgeKey(9, 3, 9, 2));
    }

    /**
     * Regression: at a 6-block cell a CELL index is not a CHUNK index. Chest
     * sites must be indexed by the chunk that actually contains their block, or
     * the runtime writes them into the wrong (possibly unloaded) chunk.
     */
    @Test
    @DisplayName("every supply chest is indexed under the chunk that contains it")
    void chestsIndexedByContainingChunk() {
        assertFalse(cfg.chestSites().isEmpty(), "supply chests exist");
        int seen = 0;
        for (int[] site : cfg.chestSites()) {
            int bx = site[0];
            int bz = site[2];
            int chunkX = bx >> 4;
            int chunkZ = bz >> 4;
            assertTrue(cfg.chestSitesIn(chunkX, chunkZ).contains(site),
                "chest at block " + bx + "," + bz + " must be listed under chunk " + chunkX + "," + chunkZ);
            assertEquals(site[1], cfg.floorY + 1, "chest sits on the corridor floor");
            assertTrue(cfg.isBaseOpen(bx, bz), "chest must stand in open corridor");
            int cellX = site[3];
            int cellZ = site[4];
            assertFalse(cfg.inGlade(cellX, cellZ), "no chests inside the Glade");
            assertEquals(cellX * cfg.cellSize + cfg.cellSize / 2, bx, "block X derives from its cell");
            assertEquals(cellZ * cfg.cellSize + cfg.cellSize / 2, bz, "block Z derives from its cell");
            seen++;
        }
        assertTrue(seen > 50, "a decent spread of caches (" + seen + ")");
    }

    @Test
    @DisplayName("chest sites are unique positions")
    void chestSitesUnique() {
        Set<Long> seen = new HashSet<>();
        for (int[] site : cfg.chestSites()) {
            assertTrue(seen.add(((long) site[0] << 32) ^ site[2]),
                "duplicate chest at " + site[0] + "," + site[2]);
        }
    }

    @Test
    @DisplayName("mutable wall boxes are indexed under every chunk they touch")
    void stateBoxesIndexedByChunk() {
        int checked = 0;
        for (MazeConfigData.TogglePoint tp : cfg.togglePoints.values()) {
            MazeConfigData.Box b = tp.box();
            for (int cx = b.x0() >> 4; cx <= b.x1() >> 4; cx++) {
                for (int cz = b.z0() >> 4; cz <= b.z1() >> 4; cz++) {
                    boolean found = cfg.boxesIn(cx, cz).stream()
                        .anyMatch(sb -> sb.id().equals(tp.id()));
                    assertTrue(found, "toggle " + tp.id() + " missing from chunk " + cx + "," + cz);
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "toggle boxes were checked");
    }

    @Test
    @DisplayName("doors and toggles span the full wall height so nothing floats")
    void mutableBoxesSpanWallHeight() {
        for (MazeConfigData.DoorDef door : cfg.doors) {
            assertEquals(cfg.wallBaseY, door.box().y0(), "door " + door.name() + " starts at wall base");
            assertEquals(cfg.wallTopY, door.box().y1(), "door " + door.name() + " reaches wall top");
        }
        for (MazeConfigData.TogglePoint tp : cfg.togglePoints.values()) {
            assertEquals(cfg.wallBaseY, tp.box().y0());
            assertEquals(cfg.wallTopY, tp.box().y1());
        }
    }

    @Test
    @DisplayName("compass sections cover 1..8")
    void sectionsCoverCompass() {
        Set<Integer> sections = new HashSet<>();
        int span = cfg.gridCells * cfg.cellSize;
        for (int x = 0; x < span; x += 7) {
            for (int z = 0; z < span; z += 7) {
                int s = cfg.sectionOf(x, z);
                assertTrue(s >= 1 && s <= 8, "section out of range: " + s);
                sections.add(s);
            }
        }
        assertEquals(8, sections.size(), "all eight sections reachable");
    }
}
