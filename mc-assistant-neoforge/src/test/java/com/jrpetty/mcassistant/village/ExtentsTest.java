package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [house34b] A building's ground (village/Extents, Blueprints.extent, BuildGoal.box): for every drawing at every turn,
 * the box is exactly what its cells cover, never a column past an even drawing's end; and the places that keep, clear,
 * fill or sell a building's ground reckon it so.
 */
class ExtentsTest {

    /** {least dx, most dx, least dz, most dz} of a drawing's cells: its footing (h <= 0) or all of it. */
    private static int[] extent(BlueprintSoundnessTest.Drawing d, boolean full) {
        int x0 = 0, x1 = 0, z0 = 0, z1 = 0;
        for (BlueprintSoundnessTest.Cell c : BlueprintSoundnessTest.cells(d)) {
            if (!full && c.h() > 0) continue;
            x0 = Math.min(x0, c.dx()); x1 = Math.max(x1, c.dx());
            z0 = Math.min(z0, c.dz()); z1 = Math.max(z1, c.dz());
        }
        return new int[]{ x0, x1, z0, z1 };
    }

    @Test
    void everyDrawingsBoxIsWhatItsCellsCoverAtEveryTurn() throws IOException {
        int ax = 1234, az = -567;
        for (BlueprintSoundnessTest.Drawing d : BlueprintSoundnessTest.drawings()) {
            for (boolean full : new boolean[]{ false, true }) {
                int[] e = extent(d, full);
                for (int back = 0; back < 4; back++) {
                    int[] a = Extents.axes(back);
                    int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
                    for (BlueprintSoundnessTest.Cell c : BlueprintSoundnessTest.cells(d)) {
                        if (!full && c.h() > 0) continue;
                        int x = ax + c.dx() * a[0] + c.dz() * a[2], z = az + c.dx() * a[1] + c.dz() * a[3];
                        x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z);
                    }
                    x0 = Math.min(x0, ax); x1 = Math.max(x1, ax); z0 = Math.min(z0, az); z1 = Math.max(z1, az);
                    assertArrayEquals(new int[]{ x0, x1, z0, z1 }, Extents.turn(e, ax, az, back),
                        d.name + (full ? " (all)" : " (footing)") + " turned " + back);
                }
            }
        }
    }

    @Test
    void anEvenDrawingRunsOneFurtherRightAndTheBoxSaysSo() throws IOException {
        int even = 0;
        for (BlueprintSoundnessTest.Drawing d : BlueprintSoundnessTest.drawings()) {
            int[] e = extent(d, false);
            if (-e[0] == e[1] && -e[2] == e[3]) continue;
            for (int back = 0; back < 4; back++) {
                int[] exact = Extents.turn(e, 0, 0, back), old = Extents.halves(e, 0, 0, back);
                int exactArea = (exact[1] - exact[0] + 1) * (exact[3] - exact[2] + 1);
                int oldArea = (old[1] - old[0] + 1) * (old[3] - old[2] + 1);
                assertTrue(exactArea < oldArea, d.name + ": the old half-box held more than its ground at turn " + back);
            }
            if (d.name.equals("terrace2") || d.name.equals("terrace3")) {
                even++;
                // its back north: dx runs east, -10 to +11; across the street to its left, the column x -11 is not its own
                assertArrayEquals(new int[]{ -10, 11, -5, 5 }, Extents.turn(e, 0, 0, TownPlan.NORTH), d.name);
                assertArrayEquals(new int[]{ -11, 10, -5, 5 }, Extents.turn(e, 0, 0, TownPlan.SOUTH), d.name);
            }
            if (d.name.equals("terrace4") || d.name.equals("terrace5")) {
                even++;
                // [house34c] the corner and the crescent: twenty-two across, and deep onto the lots behind (their back north: -z)
                int deep = d.name.equals("terrace4") ? 15 : 13;
                assertArrayEquals(new int[]{ -10, 11, -deep, 5 }, Extents.turn(e, 0, 0, TownPlan.NORTH), d.name);
            }
        }
        assertTrue(even == 4, "the four wide rows are among the drawings an even number across");
    }

    /** The places that keep, clear, fill, cut back or sell a building's ground reckon it as its own box. */
    @Test
    void theGroundKeepersReckonTheExactBox() throws IOException {
        Path src = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(src.resolve("src/main/java"))) src = src.resolve("mc-assistant-neoforge");
        Path e = src.resolve("src/main/java/com/jrpetty/mcassistant/entity");
        for (String f : List.of("Footways.java", "TownBlocks.java", "goal/BuildGoal.java", "BuildOrders.java", "HousingMarket.java",
                "Renovation.java", "Sweepers.java", "Rebuilding.java", "FireBrigade.java", "PropertyMarket.java", "BuildPlots.java",
                "Weather.java", "CityWater.java", "Retiring.java", "TownWonders.java", "Villages.java", "Homes.java")) {
            String s = Files.readString(e.resolve(f), StandardCharsets.UTF_8);
            assertTrue(s.contains("box(") || s.contains("span(") || s.contains("extent("), f + " reckons a building's ground by its own box");
        }
    }
}
