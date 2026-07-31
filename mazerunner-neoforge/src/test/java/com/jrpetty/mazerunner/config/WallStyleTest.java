package com.jrpetty.mazerunner.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wall skin: the weathering palette and the anti-climb greenery cap. */
class WallStyleTest {

    private final MazeConfigData cfg = MazeConfigs.get();

    @Test
    @DisplayName("the palette stays inside its band range at every height")
    void paletteBandsInRange() {
        for (int y = cfg.wallBaseY; y <= cfg.wallTopY; y++) {
            for (int x = 0; x < 64; x++) {
                int band = WallStyle.bandAt(x, y, x * 3 + 7, cfg.wallBaseY, cfg.wallTopY);
                assertTrue(band >= 0 && band < WallStyle.BANDS, "band " + band + " at y" + y);
            }
        }
    }

    @Test
    @DisplayName("the palette darkens at the base and lightens toward the top")
    void paletteRunsDarkToLight() {
        long low = 0;
        long high = 0;
        int samples = 0;
        for (int x = 0; x < 400; x++) {
            low += WallStyle.bandAt(x, cfg.wallBaseY, x * 5, cfg.wallBaseY, cfg.wallTopY);
            high += WallStyle.bandAt(x, cfg.wallTopY, x * 5, cfg.wallBaseY, cfg.wallTopY);
            samples++;
        }
        assertTrue(high / (double) samples > low / (double) samples,
            "wall tops should average lighter than the base");
    }

    /**
     * Regression: vines are climbable and leaf clumps have collision. If either
     * reached the wall crest a runner could climb out and walk across the maze
     * roof straight to the exit, skipping the whole game.
     */
    @Test
    @DisplayName("greenery never reaches the wall crest, so the maze can't be climbed")
    void greeneryLeavesTheCrestBare() {
        int cap = WallStyle.greeneryTopY(cfg.wallTopY);
        assertTrue(cap < cfg.wallTopY, "a bare crest band must exist");
        assertTrue(cfg.wallTopY - cap >= 4, "the bare band must be too tall to jump");

        int found = 0;
        for (int run = 0; run < 600; run++) {
            for (int line = 0; line < 40; line++) {
                int[] span = WallStyle.ivySpan(run, line, cfg.wallBaseY, cfg.wallTopY);
                if (span == null) continue;
                found++;
                assertTrue(span[1] <= cap,
                    "ivy at run " + run + " line " + line + " reaches y" + span[1] + " above cap y" + cap);
                assertTrue(span[0] >= cfg.wallBaseY, "ivy must not hang below the wall base");
                assertTrue(span[0] <= span[1], "ivy span must be ordered");
            }
        }
        assertTrue(found > 100, "ivy still generates in quantity (" + found + " columns)");
    }

    @Test
    @DisplayName("the bare crest sits above the build limit, so it can't be reached by pillaring")
    void crestIsOutOfBuildReach() {
        int cap = WallStyle.greeneryTopY(cfg.wallTopY);
        assertTrue(MazeConfigData.BUILD_LIMIT_Y <= cap + 1,
            "players must not be able to build up to the greenery cap and step onto the crest");
        assertTrue(MazeConfigData.BUILD_LIMIT_Y < cfg.wallTopY, "build limit stays under the wall top");
    }
}
