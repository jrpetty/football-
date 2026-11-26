package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [grow54] The one-time move of the village sizes in an old config file (SizeMigration). */
class SizeMigrationTest {

    @Test
    void theOldDefaultsAreMovedOnce() {
        SizeMigration.Result r = SizeMigration.decide(8, 12, false);
        assertTrue(r.migrated());
        assertEquals(30, r.min());
        assertEquals(40, r.max());
        assertTrue(r.done());
        // Marked done: an admin who sets 8 and 12 again afterwards keeps them.
        SizeMigration.Result again = SizeMigration.decide(8, 12, true);
        assertFalse(again.migrated());
        assertEquals(8, again.min());
        assertEquals(12, again.max());
    }

    @Test
    void sizesAnAdminChoseAreKept() {
        for (int[] s : new int[][]{ {8, 20}, {10, 12}, {5, 9}, {20, 50}, {30, 40} }) {
            SizeMigration.Result r = SizeMigration.decide(s[0], s[1], false);
            assertFalse(r.migrated(), s[0] + "-" + s[1]);
            assertEquals(s[0], r.min());
            assertEquals(s[1], r.max());
            assertTrue(r.done(), "decided once, either way");
        }
    }
}
