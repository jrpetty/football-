package com.jrpetty.mazerunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jrpetty.mazerunner.config.RunScoring.Entry;

/** Scoring and ranking of escape runs. */
class RunScoringTest {

    private static Entry entry(String name, long finalMs, int deaths) {
        return new Entry(name, name, finalMs, deaths);
    }

    @Test
    @DisplayName("a clean run scores exactly its clock time")
    void cleanRunHasNoPenalty() {
        assertEquals(90_000, RunScoring.finalMillis(90_000, 0));
        assertEquals(0, RunScoring.penaltyMillis(0));
    }

    @Test
    @DisplayName("each death adds a fixed penalty")
    void deathsCostTime() {
        assertEquals(90_000 + RunScoring.DEATH_PENALTY_MS, RunScoring.finalMillis(90_000, 1));
        assertEquals(90_000 + 3 * RunScoring.DEATH_PENALTY_MS, RunScoring.finalMillis(90_000, 3));
        assertEquals(3 * RunScoring.DEATH_PENALTY_MS, RunScoring.penaltyMillis(3));
    }

    @Test
    @DisplayName("a fast reckless run can lose to a slower clean one")
    void penaltiesActuallyChangeTheOrder() {
        long reckless = RunScoring.finalMillis(60_000, 2);  // 1:00 + 2 deaths
        long careful = RunScoring.finalMillis(100_000, 0);  // 1:40 clean
        assertTrue(careful < reckless, "the clean run should win despite the slower clock");
    }

    @Test
    @DisplayName("nonsense inputs cannot produce a negative or rewarding score")
    void guardsAgainstBadInput() {
        assertEquals(0, RunScoring.finalMillis(-5_000, 0));
        assertEquals(0, RunScoring.penaltyMillis(-3), "negative deaths must not refund time");
        assertEquals(0, RunScoring.finalMillis(-1, -1));
    }

    @Test
    @DisplayName("a first finish always counts as a record")
    void firstRunIsAlwaysBest() {
        assertTrue(RunScoring.isBetter(120_000, -1));
        assertTrue(RunScoring.isBetter(1, -1));
    }

    @Test
    @DisplayName("only a strictly faster time beats the incumbent")
    void tiesDoNotBeatTheRecord() {
        assertTrue(RunScoring.isBetter(90_000, 100_000));
        assertFalse(RunScoring.isBetter(100_000, 100_000), "equalling is not beating");
        assertFalse(RunScoring.isBetter(110_000, 100_000));
    }

    @Test
    @DisplayName("the leaderboard is fastest-first")
    void leaderboardOrdersByTime() {
        List<Entry> ranked = RunScoring.ranked(List.of(
            entry("slow", 300_000, 0),
            entry("fast", 100_000, 0),
            entry("mid", 200_000, 0)), 10);
        assertEquals(List.of("fast", "mid", "slow"), ranked.stream().map(Entry::name).toList());
    }

    @Test
    @DisplayName("equal times are broken by fewer deaths, then by name")
    void tieBreaksAreDeterministic() {
        List<Entry> ranked = RunScoring.ranked(List.of(
            entry("bravo", 100_000, 1),
            entry("alpha", 100_000, 1),
            entry("clean", 100_000, 0)), 10);
        assertEquals(List.of("clean", "alpha", "bravo"), ranked.stream().map(Entry::name).toList());
    }

    @Test
    @DisplayName("the leaderboard honours its limit and never mutates the input")
    void limitIsApplied() {
        List<Entry> all = List.of(
            entry("a", 100_000, 0), entry("b", 200_000, 0), entry("c", 300_000, 0));
        assertEquals(2, RunScoring.ranked(all, 2).size());
        assertEquals(3, RunScoring.ranked(all, 10).size(), "a limit above the size returns everything");
        assertEquals(0, RunScoring.ranked(all, 0).size());
        assertEquals(List.of("a", "b", "c"), all.stream().map(Entry::name).toList());
    }

    @Test
    @DisplayName("an empty leaderboard is empty, not an error")
    void emptyBoard() {
        assertTrue(RunScoring.ranked(List.of(), 10).isEmpty());
    }

    @Test
    @DisplayName("times format as a speedrun clock")
    void formatting() {
        assertEquals("0:00.0", RunScoring.format(0));
        assertEquals("0:09.5", RunScoring.format(9_500));
        assertEquals("1:00.0", RunScoring.format(60_000));
        assertEquals("12:34.5", RunScoring.format(754_500));
        assertEquals("0:00.0", RunScoring.format(-1), "negatives clamp rather than print junk");
    }
}
