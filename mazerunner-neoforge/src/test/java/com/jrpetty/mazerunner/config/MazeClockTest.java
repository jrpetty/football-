package com.jrpetty.mazerunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jrpetty.mazerunner.config.MazeClock.Event;

/** The custom day/night clock and its event schedule. */
class MazeClockTest {

    @Test
    @DisplayName("each daily event fires at its own tick and nowhere else")
    void eventTicks() {
        assertEquals(Event.DAWN, MazeClock.eventAt(0));
        assertEquals(Event.DOORS_OPEN, MazeClock.eventAt(MazeClock.DOORS_OPEN_AT));
        assertEquals(Event.DUSK_WARNING, MazeClock.eventAt(MazeClock.DOORS_WARN_AT));
        assertEquals(Event.DOORS_CLOSE, MazeClock.eventAt(MazeClock.DOORS_CLOSE_AT));
        assertEquals(Event.MAZE_SHIFT, MazeClock.eventAt(MazeClock.SHIFT_AT));
        assertNull(MazeClock.eventAt(500));
        assertNull(MazeClock.eventAt(20000));
    }

    @Test
    @DisplayName("a normal tick crosses at most one event")
    void normalTickIsQuiet() {
        assertTrue(MazeClock.eventsCrossed(500, 501).isEmpty());
        assertEquals(List.of(Event.DOORS_OPEN),
            MazeClock.eventsCrossed(MazeClock.DOORS_OPEN_AT - 1, MazeClock.DOORS_OPEN_AT));
    }

    /**
     * Regression: skipping time must behave like time actually passing. Jumping
     * from mid-afternoon to night has to fire the dusk warning AND seal the
     * doors on the way, not just land at the new time.
     */
    @Test
    @DisplayName("skipping to night still warns and seals the doors")
    void skipToNightSealsDoors() {
        List<Event> crossed = MazeClock.eventsCrossed(11000, 12600);
        assertEquals(List.of(Event.DUSK_WARNING, Event.DOORS_CLOSE), crossed);
    }

    @Test
    @DisplayName("skipping to deep night also shifts the maze")
    void skipToShiftMovesWalls() {
        List<Event> crossed = MazeClock.eventsCrossed(11000, MazeClock.SHIFT_AT);
        assertTrue(crossed.contains(Event.DOORS_CLOSE), "doors seal on the way");
        assertTrue(crossed.contains(Event.MAZE_SHIFT), "walls move");
    }

    @Test
    @DisplayName("a full day fires all five events exactly once, in order")
    void fullDayFiresEveryEventOnce() {
        List<Event> crossed = MazeClock.eventsCrossed(0, MazeClock.DAY_TICKS);
        assertEquals(List.of(Event.DOORS_OPEN, Event.DUSK_WARNING, Event.DOORS_CLOSE,
            Event.MAZE_SHIFT, Event.DAWN), crossed);
    }

    @Test
    @DisplayName("skipping to morning runs dusk → shift → dawn → doors open")
    void skipToMorningRunsWholeNight() {
        long from = 11000;
        long to = MazeClock.nextOccurrence(from, 1100);
        List<Event> crossed = MazeClock.eventsCrossed(from, to);
        assertEquals(List.of(Event.DUSK_WARNING, Event.DOORS_CLOSE, Event.MAZE_SHIFT,
            Event.DAWN, Event.DOORS_OPEN), crossed);
    }

    @Test
    @DisplayName("nextOccurrence always moves strictly forward")
    void nextOccurrenceMovesForward() {
        assertEquals(1100, MazeClock.nextOccurrence(0, 1100));
        assertEquals(MazeClock.DAY_TICKS + 1100, MazeClock.nextOccurrence(1100, 1100));
        assertEquals(MazeClock.DAY_TICKS + 1100, MazeClock.nextOccurrence(5000, 1100));
        long deep = 3L * MazeClock.DAY_TICKS + 20000;
        assertTrue(MazeClock.nextOccurrence(deep, 1100) > deep);
    }

    @Test
    @DisplayName("day runs at 1/6 speed and night at 1/3")
    void clockRates() {
        assertEquals(1, MazeClock.sixthsPerTick(0));
        assertEquals(1, MazeClock.sixthsPerTick(11999));
        assertEquals(2, MazeClock.sixthsPerTick(MazeClock.NIGHTFALL));
        assertEquals(2, MazeClock.sixthsPerTick(23999));
    }

    @Test
    @DisplayName("a full cycle is 90 real minutes: 60 of day, 30 of night")
    void realDurations() {
        assertEquals(90 * 60, MazeClock.realSecondsUntil(0, MazeClock.DAY_TICKS), "whole day");
        assertEquals(60 * 60, MazeClock.realSecondsUntil(0, MazeClock.NIGHTFALL), "daytime");
        assertEquals(30 * 60,
            MazeClock.realSecondsUntil(MazeClock.NIGHTFALL, MazeClock.DAY_TICKS), "night");
    }

    @Test
    @DisplayName("countdowns shrink monotonically as the day advances")
    void countdownIsMonotonic() {
        int previous = Integer.MAX_VALUE;
        for (int t = 0; t <= MazeClock.DOORS_CLOSE_AT; t += 25) {
            int remain = MazeClock.realSecondsUntil(t, MazeClock.DOORS_CLOSE_AT);
            assertTrue(remain <= previous, "countdown went backwards at tick " + t);
            assertTrue(remain >= 0, "countdown went negative at tick " + t);
            previous = remain;
        }
        assertEquals(0, MazeClock.realSecondsUntil(MazeClock.DOORS_CLOSE_AT, MazeClock.DOORS_CLOSE_AT));
    }

    @Test
    @DisplayName("night is exactly the doors-sealed window")
    void nightMatchesSealedDoors() {
        assertTrue(MazeClock.isNight(0), "pre-dawn counts as night");
        assertFalse(MazeClock.isNight(MazeClock.DOORS_OPEN_AT), "doors open → day");
        assertFalse(MazeClock.isNight(MazeClock.DOORS_CLOSE_AT - 1));
        assertTrue(MazeClock.isNight(MazeClock.DOORS_CLOSE_AT), "doors sealed → night");
        assertTrue(MazeClock.isNight(MazeClock.SHIFT_AT));
        assertTrue(MazeClock.isNight(MazeClock.DAY_TICKS - 1));
    }
}
