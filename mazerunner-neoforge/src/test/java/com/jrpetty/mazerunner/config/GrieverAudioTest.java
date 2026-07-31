package com.jrpetty.mazerunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jrpetty.mazerunner.config.GrieverAudio.Cue;

/** The Griever fear curve: what you hear, how close it is, how often. */
class GrieverAudioTest {

    @Test
    @DisplayName("cues escalate as a Griever closes in")
    void cuesEscalateWithProximity() {
        assertEquals(Cue.SILENT, GrieverAudio.cueFor(GrieverAudio.DISTANT_RANGE + 1, false));
        assertEquals(Cue.DISTANT, GrieverAudio.cueFor(GrieverAudio.DISTANT_RANGE, false));
        assertEquals(Cue.DISTANT, GrieverAudio.cueFor(GrieverAudio.STALK_RANGE + 1, false));
        assertEquals(Cue.STALKING, GrieverAudio.cueFor(GrieverAudio.STALK_RANGE, false));
        assertEquals(Cue.STALKING, GrieverAudio.cueFor(GrieverAudio.HUNT_RANGE + 1, false));
        assertEquals(Cue.HUNTING, GrieverAudio.cueFor(GrieverAudio.HUNT_RANGE, false));
        assertEquals(Cue.HUNTING, GrieverAudio.cueFor(0, false));
    }

    @Test
    @DisplayName("being hunted is heard sooner than merely being near")
    void targetingPromotesTheCue() {
        double midRange = (GrieverAudio.HUNT_RANGE + GrieverAudio.STALK_RANGE) / 2.0;
        assertEquals(Cue.STALKING, GrieverAudio.cueFor(midRange, false));
        assertEquals(Cue.HUNTING, GrieverAudio.cueFor(midRange, true), "locked on → heartbeat");
    }

    @Test
    @DisplayName("a Griever that has not found you still cannot be heard from across the maze")
    void targetingDoesNotBreakTheSilenceRange() {
        assertEquals(Cue.SILENT, GrieverAudio.cueFor(GrieverAudio.DISTANT_RANGE + 5, true));
        assertEquals(Cue.DISTANT, GrieverAudio.cueFor(GrieverAudio.STALK_RANGE + 5, true));
    }

    @Test
    @DisplayName("the ranges are ordered and sane")
    void rangesAreOrdered() {
        assertTrue(GrieverAudio.HUNT_RANGE < GrieverAudio.STALK_RANGE);
        assertTrue(GrieverAudio.STALK_RANGE < GrieverAudio.DISTANT_RANGE);
        assertTrue(GrieverAudio.HUNT_RANGE > 0);
    }

    @Test
    @DisplayName("closer cues repeat more insistently")
    void closerCuesAreMoreFrequent() {
        assertTrue(GrieverAudio.intervalSeconds(Cue.HUNTING)
            < GrieverAudio.intervalSeconds(Cue.STALKING));
        assertTrue(GrieverAudio.intervalSeconds(Cue.STALKING)
            < GrieverAudio.intervalSeconds(Cue.DISTANT));
        assertTrue(GrieverAudio.intervalSeconds(Cue.HUNTING) >= 1, "never faster than once a second");
    }

    @Test
    @DisplayName("silence never schedules a sound")
    void silenceNeverPlays() {
        assertEquals(0.0F, GrieverAudio.volumeFor(Cue.SILENT));
        // a modulo against this interval effectively never fires
        assertTrue(GrieverAudio.intervalSeconds(Cue.SILENT) > 1_000_000);
    }

    @Test
    @DisplayName("distant cues are loud enough to carry, and pitched below their source")
    void volumesCarryAndPitchesAreDeep() {
        // Minecraft's audible radius is 16 × volume.
        assertTrue(GrieverAudio.volumeFor(Cue.DISTANT) * 16 >= GrieverAudio.DISTANT_RANGE,
            "distant grinding must reach the far edge of its own range");
        assertTrue(GrieverAudio.volumeFor(Cue.STALKING) * 16 >= GrieverAudio.STALK_RANGE,
            "footfalls must reach the far edge of stalking range");
        assertTrue(GrieverAudio.volumeFor(Cue.DISTANT) > GrieverAudio.volumeFor(Cue.STALKING));
        for (Cue cue : new Cue[] { Cue.DISTANT, Cue.STALKING }) {
            assertTrue(GrieverAudio.pitchFor(cue) < 1.0F, cue + " must be pitched down");
            assertTrue(GrieverAudio.pitchFor(cue) >= 0.5F, cue + " must stay in Minecraft's pitch range");
        }
    }

    @Test
    @DisplayName("negative or nonsense distances are silent, not loud")
    void guardsAgainstBadInput() {
        assertEquals(Cue.SILENT, GrieverAudio.cueFor(-1, true));
        assertEquals(Cue.SILENT, GrieverAudio.cueFor(Double.MAX_VALUE, true));
    }
}
