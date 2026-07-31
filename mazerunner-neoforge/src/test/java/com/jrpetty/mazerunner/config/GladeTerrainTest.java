package com.jrpetty.mazerunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Glade: elevation, lake, forest and the vanilla trees that fill it. */
class GladeTerrainTest {

    private final MazeConfigData cfg = MazeConfigs.get();

    @Test
    @DisplayName("Glade bounds follow the config")
    void boundsFollowConfig() {
        assertEquals(cfg.gladeBlockMin, GladeTerrain.min());
        assertEquals(cfg.gladeBlockMax, GladeTerrain.max());
        assertEquals(96, GladeTerrain.span(), "96-block Glade at minigame scale");
        assertTrue(GladeTerrain.inGlade(GladeTerrain.center(), GladeTerrain.center()));
        assertFalse(GladeTerrain.inGlade(GladeTerrain.min() - 1, GladeTerrain.center()));
    }

    @Test
    @DisplayName("hills stay inside the height cap and never step more than one block")
    void elevationIsSmoothAndCapped() {
        int maxSeen = 0;
        for (int x = GladeTerrain.min(); x <= GladeTerrain.max(); x++) {
            for (int z = GladeTerrain.min(); z <= GladeTerrain.max(); z++) {
                int h = GladeTerrain.heightAt(x, z);
                assertTrue(h >= 0 && h <= GladeTerrain.MAX_RAISE, "height out of range at " + x + "," + z);
                maxSeen = Math.max(maxSeen, h);
                if (x > GladeTerrain.min()) {
                    assertTrue(Math.abs(h - GladeTerrain.heightAt(x - 1, z)) <= 1,
                        "cliff in X at " + x + "," + z);
                }
                if (z > GladeTerrain.min()) {
                    assertTrue(Math.abs(h - GladeTerrain.heightAt(x, z - 1)) <= 1,
                        "cliff in Z at " + x + "," + z);
                }
            }
        }
        assertTrue(maxSeen >= 2, "the Glade actually rolls (max +" + maxSeen + ")");
    }

    @Test
    @DisplayName("the Box elevator sits on flat ground")
    void elevatorPadIsFlat() {
        int c = GladeTerrain.center();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                assertEquals(0, GladeTerrain.heightAt(c + dx, c + dz),
                    "ground under the Box must be flat at " + (c + dx) + "," + (c + dz));
            }
        }
    }

    @Test
    @DisplayName("the lake is present, bounded in depth, and mixes four bed materials")
    void lakeIsMixed() {
        int water = 0, maxDepth = 0;
        boolean sand = false, clay = false, gravel = false, dirt = false;
        for (int x = GladeTerrain.min(); x <= GladeTerrain.max(); x++) {
            for (int z = GladeTerrain.min(); z <= GladeTerrain.max(); z++) {
                if (!GladeTerrain.inLake(x, z)) continue;
                water++;
                int d = GladeTerrain.lakeDepth(x, z);
                assertTrue(d >= 1 && d <= GladeTerrain.LAKE_MAX_DEPTH, "lake depth " + d);
                maxDepth = Math.max(maxDepth, d);
                switch (GladeTerrain.bedMaterial(x, z)) {
                    case GladeTerrain.BED_SAND -> sand = true;
                    case GladeTerrain.BED_CLAY -> clay = true;
                    case GladeTerrain.BED_GRAVEL -> gravel = true;
                    default -> dirt = true;
                }
                assertEquals(0, GladeTerrain.heightAt(x, z), "lake bed is not raised");
            }
        }
        assertTrue(water > 300, "lake covers real ground (" + water + " columns)");
        assertEquals(GladeTerrain.LAKE_MAX_DEPTH, maxDepth, "the lake reaches full depth");
        assertTrue(sand && clay && gravel && dirt, "bed mixes sand/clay/gravel/dirt");
    }

    @Test
    @DisplayName("the lake bed never cuts into bedrock")
    void lakeStaysAboveBedrock() {
        for (int x = GladeTerrain.min(); x <= GladeTerrain.max(); x++) {
            for (int z = GladeTerrain.min(); z <= GladeTerrain.max(); z++) {
                if (!GladeTerrain.inLake(x, z)) continue;
                int bedY = cfg.floorY - GladeTerrain.lakeDepth(x, z);
                assertTrue(bedY > cfg.bedrockY,
                    "lake bed y" + bedY + " must stay above bedrock y" + cfg.bedrockY);
            }
        }
    }

    @Test
    @DisplayName("the forest covers a meaningful slice of the Glade")
    void forestCoverage() {
        int forest = 0, total = 0;
        for (int x = GladeTerrain.min(); x <= GladeTerrain.max(); x++) {
            for (int z = GladeTerrain.min(); z <= GladeTerrain.max(); z++) {
                total++;
                if (GladeTerrain.inForest(x, z)) forest++;
            }
        }
        double share = forest / (double) total;
        assertTrue(share > 0.10 && share < 0.50, "forest share was " + share);
    }

    @Test
    @DisplayName("trees are a natural mix of species and of small/medium/large sizes")
    void treesAreMixed() {
        int[] species = new int[4];
        int[] sizes = new int[3];
        int trees = 0;
        for (int x = GladeTerrain.min(); x <= GladeTerrain.max(); x++) {
            for (int z = GladeTerrain.min(); z <= GladeTerrain.max(); z++) {
                if (!GladeTerrain.isTrunkSite(x, z)) continue;
                trees++;
                species[GladeTerrain.speciesAt(x, z)]++;
                sizes[GladeTerrain.treeSize(x, z)]++;
                assertFalse(GladeTerrain.inLake(x, z), "no trees in the lake at " + x + "," + z);
                assertTrue(GladeTerrain.inForest(x, z), "trunks only inside the forest mask");
            }
        }
        assertTrue(trees > 30, "the forest is populated (" + trees + " trees)");
        for (int s = 0; s < 3; s++) {
            assertTrue(sizes[s] > 0, "size tier " + s + " must appear");
        }
        assertTrue(species[GladeTerrain.OAK] > 0 && species[GladeTerrain.BIRCH] > 0
            && species[GladeTerrain.DARK_OAK] > 0, "oak, birch and dark oak all appear");
        assertTrue(sizes[GladeTerrain.SIZE_LARGE] > trees / 12,
            "a real share of large trees (" + sizes[GladeTerrain.SIZE_LARGE] + "/" + trees + ")");
    }

    @Test
    @DisplayName("bigger size tiers really do grow taller trunks")
    void largerTiersGrowTaller() {
        long[] sum = new long[3];
        int[] count = new int[3];
        for (int x = GladeTerrain.min(); x <= GladeTerrain.max(); x++) {
            for (int z = GladeTerrain.min(); z <= GladeTerrain.max(); z++) {
                if (!GladeTerrain.isTrunkSite(x, z)) continue;
                int tier = GladeTerrain.treeSize(x, z);
                sum[tier] += GladeTerrain.trunkHeight(x, z);
                count[tier]++;
            }
        }
        double small = sum[0] / (double) count[0];
        double medium = sum[1] / (double) count[1];
        double large = sum[2] / (double) count[2];
        assertTrue(medium > small, "medium taller than small (" + small + " vs " + medium + ")");
        assertTrue(large > medium, "large taller than medium (" + medium + " vs " + large + ")");
        assertTrue(large - small >= 1.5, "clear spread small→large (" + small + " vs " + large + ")");
    }

    /** Regression: leaves must never poke above the maze walls. */
    @Test
    @DisplayName("no tree crown breaks through the wall-top leaf clamp")
    void crownsStayUnderTheWalls() {
        int maxLeafY = cfg.wallTopY - 1;
        int tallest = 0;
        for (int x = GladeTerrain.min(); x <= GladeTerrain.max(); x++) {
            for (int z = GladeTerrain.min(); z <= GladeTerrain.max(); z++) {
                if (!GladeTerrain.isTrunkSite(x, z)) continue;
                int ground = cfg.floorY + GladeTerrain.heightAt(x, z);
                int top = TreeShape.crownTopY(ground, GladeTerrain.trunkHeight(x, z),
                    GladeTerrain.speciesAt(x, z), GladeTerrain.treeSize(x, z));
                assertTrue(top <= maxLeafY,
                    "crown at " + x + "," + z + " reaches y" + top + " above the y" + maxLeafY + " clamp");
                tallest = Math.max(tallest, top);
            }
        }
        assertTrue(tallest > cfg.floorY + 6, "trees have real height (tallest crown y" + tallest + ")");
    }

    @Test
    @DisplayName("terrain generation is deterministic")
    void deterministic() {
        int x = GladeTerrain.min() + 33;
        int z = GladeTerrain.min() + 44;
        assertEquals(GladeTerrain.heightAt(x, z), GladeTerrain.heightAt(x, z));
        assertEquals(GladeTerrain.speciesAt(x, z), GladeTerrain.speciesAt(x, z));
        assertEquals(GladeTerrain.treeSize(x, z), GladeTerrain.treeSize(x, z));
        assertEquals(GladeTerrain.lakeDepth(x, z), GladeTerrain.lakeDepth(x, z));
    }
}
