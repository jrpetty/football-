/**
 * Game-show helpers for the Episode Presenter (pure, no DOM):
 *
 * - runningStandings(): the Gauntlet Index of every model after the first N
 *   tests of the episode, for the "Standings after N of M tests" bar race.
 *   It feeds the recorded per-test scores into the leaderboard's own formula
 *   (engine/aggregate.ts indexFromTestScores), so the last step is exactly the
 *   final Index.
 * - bestAndWorst(): a model's best and worst recorded test score.
 * - revealState(): what the last-to-first podium reveal shows at each keypress.
 */
import { indexFromTestScores } from '../engine/aggregate.ts';
import type { Leaderboard, LeaderboardRow } from '../core/types.ts';

export interface StandingRow {
  id: string;
  /** Running Gauntlet Index 0..100 after this step (null = nothing scored yet). */
  index: number | null;
  /** 1-based position after this step (null when unscored). */
  rank: number | null;
  prevIndex: number | null;
  prevRank: number | null;
  /** Places gained since the previous step (positive = moved up). 0 on the first step. */
  gained: number;
}

export interface StandingsStep {
  /** 1-based: standings after `step` of `of` tests. */
  step: number;
  of: number;
  /** The test just played. */
  testId: string;
  /** Rows in the new order (ranked first, unscored last). */
  rows: StandingRow[];
  leaderId: string | null;
  prevLeaderId: string | null;
  /** The leader changed on this step (never true on step 1). */
  newLeader: boolean;
  /** The biggest climber on this step, or null when nobody moved up. */
  mover: { id: string; places: number } | null;
  /** Random guessing's running Index (null when the run has no baseline or it is unscored). */
  baseline: number | null;
}

function rankRows(entries: Array<{ id: string; index: number | null }>, tieOrder: Map<string, number>): Map<string, number | null> {
  const sorted = [...entries].sort((a, b) => (b.index ?? -1) - (a.index ?? -1) || (tieOrder.get(a.id) ?? 99) - (tieOrder.get(b.id) ?? 99) || a.id.localeCompare(b.id));
  const out = new Map<string, number | null>();
  let pos = 0;
  for (const e of sorted) out.set(e.id, e.index === null ? null : ++pos);
  return out;
}

/**
 * Standings after each test, in episode order.
 * `testIds` is the order the episode plays the tests; tests missing from the
 * leaderboard are skipped. `baselineIds` are reference players (random
 * guessing): they are not ranked, their score becomes the reference line.
 */
export function runningStandings(lb: Pick<Leaderboard, 'tests' | 'rows' | 'categoryWeights'>, testIds: string[], baselineIds: Set<string>): StandingsStep[] {
  const byId = new Map(lb.tests.map((t) => [t.id, t]));
  const order = testIds.filter((id) => byId.has(id));
  const competitors = lb.rows.filter((r) => !baselineIds.has(r.contestantId));
  const base = lb.rows.find((r) => baselineIds.has(r.contestantId)) ?? null;
  // Ties keep the final leaderboard's order, so the race never flickers between equal scores.
  const tieOrder = new Map(competitors.map((r, i) => [r.contestantId, r.rank || i + 1] as const));
  const allTests = order.length === lb.tests.length;

  const indexAfter = (row: LeaderboardRow, k: number): number | null => {
    // Final step over the whole run: the recorded Index itself (no rounding drift).
    if (k === order.length && allTests) return typeof row.index === 'number' ? row.index : null;
    const tests = order.slice(0, k).map((id) => byId.get(id)!);
    const scores = new Map<string, number | null>(tests.map((t) => [t.id, typeof row.tests?.[t.id]?.score === 'number' ? row.tests[t.id]!.score : null]));
    const { index } = indexFromTestScores(tests, scores, lb.categoryWeights ?? {});
    return index === null ? null : Math.round(index * 100) / 100;
  };

  const steps: StandingsStep[] = [];
  let prev: Map<string, { index: number | null; rank: number | null }> | null = null;
  let prevLeader: string | null = null;
  for (let k = 1; k <= order.length; k++) {
    const entries = competitors.map((r) => ({ id: r.contestantId, index: indexAfter(r, k) }));
    const ranks = rankRows(entries, tieOrder);
    const rows: StandingRow[] = entries.map((e) => {
      const p = prev?.get(e.id);
      const rank = ranks.get(e.id) ?? null;
      const prevRank = p?.rank ?? null;
      return { id: e.id, index: e.index, rank, prevIndex: p?.index ?? null, prevRank, gained: rank !== null && prevRank !== null ? prevRank - rank : 0 };
    });
    rows.sort((a, b) => (a.rank ?? 999) - (b.rank ?? 999) || (tieOrder.get(a.id) ?? 99) - (tieOrder.get(b.id) ?? 99));
    const leaderId = rows[0]?.rank === 1 ? rows[0].id : null;
    let mover: StandingsStep['mover'] = null;
    for (const r of rows) {
      if (r.gained <= 0) continue;
      const gain = (r.index ?? 0) - (r.prevIndex ?? 0);
      const best = mover ? rows.find((x) => x.id === mover!.id)! : null;
      if (!mover || r.gained > mover.places || (r.gained === mover.places && gain > (best!.index ?? 0) - (best!.prevIndex ?? 0))) mover = { id: r.id, places: r.gained };
    }
    steps.push({
      step: k,
      of: order.length,
      testId: order[k - 1]!,
      rows,
      leaderId,
      prevLeaderId: prevLeader,
      newLeader: k > 1 && leaderId !== null && prevLeader !== null && leaderId !== prevLeader,
      mover,
      baseline: base ? indexAfter(base, k) : null,
    });
    prev = new Map(rows.map((r) => [r.id, { index: r.index, rank: r.rank }]));
    prevLeader = leaderId;
  }
  return steps;
}

export interface TestPick {
  id: string;
  name: string;
  /** 0..1 */
  score: number;
}

/** A model's best and worst recorded test (worst is null with fewer than two scored tests). */
export function bestAndWorst(row: Pick<LeaderboardRow, 'tests'>, tests: Array<{ id: string; name: string }>): { best: TestPick | null; worst: TestPick | null } {
  const scored = tests
    .map((t) => ({ id: t.id, name: t.name, score: row.tests?.[t.id]?.score }))
    .filter((t): t is TestPick => typeof t.score === 'number');
  if (!scored.length) return { best: null, worst: null };
  // Stable: earliest test wins ties.
  let best = scored[0]!;
  let worst = scored[0]!;
  for (const t of scored) {
    if (t.score > best.score) best = t;
    if (t.score < worst.score) worst = t;
  }
  return { best, worst: scored.length > 1 ? worst : null };
}

export interface RevealState {
  /** Places 1..n that are on screen (the last `shown` places). */
  shownFrom: number;
  /** The place revealed by this keypress (null before the first reveal and on the drumroll). */
  current: number | null;
  drumroll: boolean;
  winner: boolean;
}

/** Reveal steps for n ranked models: places n..2 one per press, a drumroll, then the winner. */
export function revealSteps(n: number): number {
  return n <= 0 ? 0 : n + 1;
}

/** What the reveal shows after `reveal` presses (0 = everything hidden). */
export function revealState(n: number, reveal: number): RevealState {
  const r = Math.max(0, Math.min(revealSteps(n), reveal));
  if (n <= 0 || r === 0) return { shownFrom: n + 1, current: null, drumroll: false, winner: false };
  if (r <= n - 1) return { shownFrom: n - r + 1, current: n - r + 1, drumroll: false, winner: false };
  if (r === n) return { shownFrom: 2, current: null, drumroll: true, winner: false };
  return { shownFrom: 1, current: 1, drumroll: false, winner: true };
}
