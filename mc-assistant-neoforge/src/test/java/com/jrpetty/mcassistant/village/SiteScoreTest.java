package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [found23] The spot finder's verdicts on made-up ground. */
class SiteScoreTest {

    private static SiteScore.Ground flat(int y) {
        SiteScore.Ground g = new SiteScore.Ground();
        g.heights = new int[140];
        java.util.Arrays.fill(g.heights, y);
        g.columns = 140;
        g.hollowRead = 9;
        g.centreY = y;
        return g;
    }

    @Test
    void flatRiverMeadowBeatsCliffAndSea() {
        SiteScore.Ground meadow = flat(68);
        meadow.waterAt = 30;
        meadow.water = "river";
        meadow.woodAt = 20;
        meadow.wood = "oak";
        SiteScore.Verdict m = SiteScore.judge(meadow);

        SiteScore.Ground cliff = flat(68);
        for (int i = 0; i < 70; i++) cliff.heights[i] = 68 + 18;
        cliff.land = "upland";
        SiteScore.Verdict c = SiteScore.judge(cliff);

        SiteScore.Ground sea = flat(62);
        sea.wetColumns = 100;
        sea.centreWet = true;
        SiteScore.Verdict s = SiteScore.judge(sea);

        assertTrue(m.ok() && c.ok(), "both may be founded on");
        assertFalse(s.ok(), "not in the sea");
        assertTrue(m.score() > c.score() + 30, "the meadow well above the cliff: " + m.score() + " vs " + c.score());
        assertEquals("flat meadow by a river, oak wood 20 blocks", m.words());
        assertTrue(c.words().startsWith("steep"), c.words());
    }

    @Test
    void tooNearATownIsRefusedAndNearIsMarkedDown() {
        SiteScore.Ground g = flat(70);
        g.townAt = -5;
        g.town = "Oakford";
        SiteScore.Verdict v = SiteScore.judge(g);
        assertFalse(v.ok());
        assertTrue(v.no().contains("Oakford"));
        SiteScore.Ground far = flat(70);
        SiteScore.Ground close = flat(70);
        close.townAt = 5;
        assertTrue(SiteScore.judge(far).score() > SiteScore.judge(close).score());
    }

    @Test
    void wordsForLandAndWood() {
        assertEquals("meadow", SiteScore.landWord("minecraft:sunflower_plains"));
        assertEquals("pine woods", SiteScore.landWord("minecraft:taiga"));
        assertEquals("dark oak", SiteScore.woodWord("minecraft:dark_oak_log"));
    }
}
