package com.jrpetty.mazerunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Crown geometry for the vanilla Glade trees. */
class TreeShapeTest {

    private static final int[] SPECIES = {
        GladeTerrain.OAK, GladeTerrain.BIRCH, GladeTerrain.DARK_OAK, GladeTerrain.APPLE_OAK };

    @Test
    @DisplayName("every species/size produces a usable crown within the scan radius")
    void radiiAreSane() {
        for (int species : SPECIES) {
            for (int size = 0; size <= 2; size++) {
                int rH = TreeShape.horizontalRadius(species, size);
                int rV = TreeShape.verticalRadius(species, size);
                assertTrue(rH >= 1 && rH <= TreeShape.MAX_RADIUS,
                    "horizontal radius " + rH + " (species " + species + ", size " + size + ")");
                assertTrue(rV >= 2 && rV <= 4,
                    "vertical radius " + rV + " (species " + species + ", size " + size + ")");
            }
        }
    }

    @Test
    @DisplayName("crowns never exceed the neighbour-scan radius used when placing")
    void radiusFitsScanWindow() {
        for (int species : SPECIES) {
            for (int size = 0; size <= 2; size++) {
                assertTrue(TreeShape.horizontalRadius(species, size) <= TreeShape.MAX_RADIUS,
                    "a crown wider than MAX_RADIUS would be clipped at chunk scan time");
            }
        }
    }

    @Test
    @DisplayName("bigger tiers never shrink the crown")
    void crownsGrowWithSize() {
        for (int species : SPECIES) {
            for (int size = 1; size <= 2; size++) {
                assertTrue(TreeShape.horizontalRadius(species, size)
                    >= TreeShape.horizontalRadius(species, size - 1), "horizontal shrank");
                assertTrue(TreeShape.verticalRadius(species, size)
                    >= TreeShape.verticalRadius(species, size - 1), "vertical shrank");
            }
        }
    }

    @Test
    @DisplayName("species silhouettes differ: birch slim and tall, dark oak broad and low")
    void silhouettesDiffer() {
        int birchH = TreeShape.horizontalRadius(GladeTerrain.BIRCH, 2);
        int birchV = TreeShape.verticalRadius(GladeTerrain.BIRCH, 2);
        int darkH = TreeShape.horizontalRadius(GladeTerrain.DARK_OAK, 2);
        int darkV = TreeShape.verticalRadius(GladeTerrain.DARK_OAK, 2);
        assertTrue(birchH < darkH, "birch narrower than dark oak");
        assertTrue(birchV > darkV, "birch taller-crowned than dark oak");
        assertTrue(birchV > birchH, "birch reads as a slim upright tree");
        assertTrue(darkH >= darkV, "dark oak reads as a broad canopy");
    }

    @Test
    @DisplayName("the crown centre sits one block above the trunk top")
    void crownCentreAboveTrunk() {
        assertEquals(67, TreeShape.crownCenterY(60, 6));
        assertEquals(67 + 3, TreeShape.crownTopY(60, 6, GladeTerrain.OAK, 1));
    }

    @Test
    @DisplayName("the crown always includes its own centre and excludes far corners")
    void crownFieldIsCoherent() {
        int cy = 70;
        for (int species : SPECIES) {
            for (int size = 0; size <= 2; size++) {
                int rH = TreeShape.horizontalRadius(species, size);
                int rV = TreeShape.verticalRadius(species, size);
                assertTrue(TreeShape.inCrown(0, 0, cy, cy, rH, rV, 100, 200),
                    "centre of the crown must be leaves");
                assertTrue(!TreeShape.inCrown(rH + 1, 0, cy, cy, rH, rV, 100, 200),
                    "beyond the horizontal radius must be empty");
                assertTrue(!TreeShape.inCrown(0, 0, cy + rV + 2, cy, rH, rV, 100, 200),
                    "well above the crown must be empty");
            }
        }
    }

    @Test
    @DisplayName("crown shape is deterministic for a given column")
    void deterministic() {
        boolean first = TreeShape.inCrown(1, 1, 71, 70, 2, 3, 512, 640);
        for (int i = 0; i < 5; i++) {
            assertEquals(first, TreeShape.inCrown(1, 1, 71, 70, 2, 3, 512, 640));
        }
    }
}
