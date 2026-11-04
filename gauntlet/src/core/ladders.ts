/**
 * Difficulty ladders (config/ladders.json): every test built as ten frozen rungs L01…L10, each strictly harder
 * than the one below. Pure (no file system), so the server, the Presenter, the UI and its demo mode all use the
 * same rules; config/ladders.json is read by ladders-file.ts on the server and imported directly by the UI.
 *
 * The height rule (the Horizon rule, for every ladder):
 *
 *  - An attempt PASSES a rung when it scores at least the test's pass mark (`passMark`: 1 = full marks, the
 *    exact-answer ladders; 0.9 = 90% of the rung's points, the partial-credit business ladders).
 *  - A rung is passed RELIABLY when at least two thirds of its attempts pass (with one attempt: that attempt).
 *  - The ladder HEIGHT is the highest level L such that every level 1..L was passed reliably (0 = not even L1).
 *  - A pass higher up ("once solved level 7") is reported separately and never lifts the height.
 *  - BORDERLINE: the height rests on a rung passed by some but not all attempts, or (partial credit) on an
 *    attempt within 5 points of the pass mark. JUST SHORT: the next rung up was passed by some attempt, or
 *    (partial credit) missed by less than 5 points. Both are shown so luck is never sold as a clear result.
 *  - The test score stays the mean over the levels run (the share of the ladder climbed).
 *
 * test/ladders.test.ts checks the config and every rule here; test/horizon.test.ts keeps Horizon identical.
 */

export type LadderArea = 'benchmark' | 'business';

export interface LadderTestSpec {
  id: string;
  /** Display name (the test library's name wins once the test is built). */
  name: string;
  area: LadderArea;
  category: string;
  /** Share of a rung's points an attempt needs to pass it (1 = full marks). */
  passMark: number;
  /** One line: what grows from rung to rung. */
  grows: string;
  /** The growth parameter of each rung, bottom (L01) to top. Required once the test is built. */
  rungs?: string[];
  /** The existing test this ladder is built from (kept untouched so old results stay comparable). */
  basedOn?: string;
  /** Not built yet: shown as "coming soon". Delete the flag when the test lands. */
  planned?: boolean;
  /** What a blind test measured (replaces the design-target bands in the ladder view). See calibrationVerdict. */
  calibration?: LadderCalibration;
}

// ───────────────────────────── calibration (what blind play measured) ─────────────────────────────

/** The model sizes a ladder is blind-tested with. */
export type CalibrationTier = 'small' | 'mid' | 'top';
export const CALIBRATION_TIERS: readonly CalibrationTier[] = ['small', 'mid', 'top'];
/**
 * separates: each bigger model got further than the smaller one · top-clears: the top model passed every level it
 * was tested on · partly: some sizes were not told apart.
 */
export type CalibrationVerdict = 'separates' | 'top-clears' | 'partly';
export const CALIBRATION_VERDICTS: readonly CalibrationVerdict[] = ['separates', 'top-clears', 'partly'];

/** One model size's blind result on one ladder: the levels it was tested on (one attempt each). */
export interface CalibrationTierResult {
  passed: number[];
  failed: number[];
  /** Levels still being tested. When the numbers arrive, move each level into passed or failed and delete this. */
  pending?: number[];
}

export interface LadderCalibration {
  /** When the blind test ran: "2026-11". */
  date: string;
  tiers: Partial<Record<CalibrationTier, CalibrationTierResult>>;
  /** Overrides the verdict worked out from the tiers (calibrationVerdict). Leave out unless the data misleads. */
  verdict?: CalibrationVerdict;
  /** One plain sentence, e.g. what a surprising miss was. */
  note?: string;
}

export interface LaddersConfig {
  version: string;
  title: string;
  about?: string;
  /** Rungs per ladder (10). */
  levels: number;
  /** How the blind calibration was done (one line, shown beside the measured strip). */
  calibrationMethod?: string;
  tests: LadderTestSpec[];
}

/** What the height function needs to know about a test. */
export interface LadderRule {
  passMark: number;
  /** Rungs drawn (more are drawn if results exist above them). */
  levels?: number;
}

export const LADDER_LEVELS = 10;
/** A rung is passed reliably when at least this share of its attempts pass. */
export const LADDER_RELIABLE_SHARE = 2 / 3;
/** Points (0..1) either side of a partial-credit pass mark that count as "within noise". */
export const LADDER_BORDER_MARGIN = 0.05;
/** The Horizon rule: full marks. */
export const FULL_MARKS: LadderRule = { passMark: 1 };

/** "L07" → 7; anything else → null. */
export function ladderLevel(caseId: string): number | null {
  const m = /^L(\d{1,3})$/.exec(caseId);
  return m ? Number(m[1]) : null;
}

/** ['L01', …, 'L10']. */
export function ladderCaseIds(levels = LADDER_LEVELS): string[] {
  return Array.from({ length: levels }, (_, i) => `L${String(i + 1).padStart(2, '0')}`);
}

/** Does one attempt pass a rung? Full-marks ladders allow a rounding hair (0.999), exactly as Horizon always has. */
export function passesRung(score: number, passMark: number): boolean {
  return passMark >= 1 ? score >= 0.999 : score >= passMark - 1e-9;
}

// ───────────────────────────── the height function ─────────────────────────────

export type RungState = 'passed' | 'sometimes' | 'partial' | 'failed' | 'not-run';

export interface RungResult {
  level: number;
  /** Scores (0..1) of the attempts, in the order recorded. */
  scores: number[];
  /** Per attempt: passed the rung? */
  passed: boolean[];
  attempts: number;
  /** Attempts that passed. */
  passes: number;
  /** Mean score of the attempts (partial credit counts); 0 when not run. */
  mean: number;
  /** Passed by at least two thirds of the attempts. */
  reliable: boolean;
  /** passed (reliably) · sometimes (some attempts passed) · partial (no pass, some points) · failed · not-run. */
  state: RungState;
}

export interface LadderHeight {
  /** One entry per level 1..levels (attempts 0 = not run). */
  rungs: RungResult[];
  /** Ladder height: every level from 1 up to this one passed reliably (0 = none). */
  height: number;
  /** Highest level with at least one passing attempt (0 = none). */
  best: number;
  /** Levels above the height with at least one passing attempt ("once solved L7"), lowest first. */
  onceSolved: number[];
  /** Mean over the levels that were run: the test score. Null when nothing was run. */
  score: number | null;
  levels: number;
  levelsRun: number;
  /** The height rests on a shaky rung (see the header). */
  borderline: boolean;
  /** The next rung up was nearly passed (see the header). */
  justShort: boolean;
  passMark: number;
}

export interface LadderClimb extends LadderHeight {
  contestantId: string;
}

function rungOf(level: number, scores: number[], passMark: number): RungResult {
  const passed = scores.map((s) => passesRung(s, passMark));
  const passes = passed.filter(Boolean).length;
  const attempts = scores.length;
  const mean = attempts ? scores.reduce((a, b) => a + b, 0) / attempts : 0;
  const reliable = attempts > 0 && passes / attempts >= LADDER_RELIABLE_SHARE - 1e-9;
  const state: RungState = !attempts ? 'not-run' : reliable ? 'passed' : passes > 0 ? 'sometimes' : mean > 0 ? 'partial' : 'failed';
  return { level, scores, passed, attempts, passes, mean, reliable, state };
}

/**
 * The height of one model on one ladder, from its attempt scores per level (`scores[level]` or a Map).
 * Pure; every screen that shows a height calls this (through ladderClimbs or attachLadderHeights).
 */
export function climbLadder(scores: ReadonlyMap<number, readonly number[]> | Readonly<Record<number, readonly number[]>>, rule: LadderRule): LadderHeight {
  const get = (lv: number): number[] => [...((scores instanceof Map ? scores.get(lv) : (scores as Record<number, readonly number[]>)[lv]) ?? [])];
  const keys = scores instanceof Map ? [...scores.keys()] : Object.keys(scores).map(Number);
  const levels = Math.max(rule.levels ?? LADDER_LEVELS, ...keys.filter((k) => Number.isInteger(k) && k > 0));
  const rungs: RungResult[] = [];
  for (let level = 1; level <= levels; level++) rungs.push(rungOf(level, get(level), rule.passMark));
  let height = 0;
  while (height < rungs.length && rungs[height]!.reliable) height++;
  const run = rungs.filter((r) => r.attempts > 0);
  const partialCredit = rule.passMark < 1;
  const shaky = (r: RungResult) => r.passes < r.attempts || (partialCredit && r.scores.some((s, i) => r.passed[i] && s < rule.passMark + LADDER_BORDER_MARGIN - 1e-9));
  const next = rungs[height];
  const nearly = !!next && next.attempts > 0 && (next.passes > 0 || (partialCredit && next.scores.some((s) => s >= rule.passMark - LADDER_BORDER_MARGIN - 1e-9)));
  return {
    rungs,
    height,
    best: Math.max(0, ...rungs.filter((r) => r.passes > 0).map((r) => r.level)),
    onceSolved: rungs.filter((r) => r.level > height && r.passes > 0).map((r) => r.level),
    score: run.length ? run.reduce((a, r) => a + r.mean, 0) / run.length : null,
    levels,
    levelsRun: run.length,
    borderline: rungs.slice(0, height).some(shaky),
    justShort: nearly,
    passMark: rule.passMark,
  };
}

export interface LadderResultLike {
  contestantId: string;
  testId: string;
  caseId: string;
  score: number | null;
  status?: string;
  /** A copy & paste answer moved to a named model counts only there. */
  reassignedTo?: unknown;
}

/** Statuses whose score counts as an attempt (errors and cancellations are not attempts; a refusal scores 0). */
const COUNTED = new Set(['ok', 'timeout', 'refusal']);

/** Attempt scores per contestant and level for one test. Contestants in first-seen order. */
export function ladderAttempts(results: readonly LadderResultLike[], testId: string): Map<string, Map<number, number[]>> {
  const by = new Map<string, Map<number, number[]>>();
  for (const r of results) {
    if (r.testId !== testId || typeof r.score !== 'number' || r.reassignedTo) continue;
    if (r.status && !COUNTED.has(r.status)) continue;
    const lv = ladderLevel(r.caseId);
    if (lv === null) continue;
    let m = by.get(r.contestantId);
    if (!m) by.set(r.contestantId, (m = new Map()));
    m.set(lv, [...(m.get(lv) ?? []), r.score]);
  }
  return by;
}

/** How far up one ladder every contestant got. */
export function ladderClimbs(results: readonly LadderResultLike[], testId: string, rule: LadderRule): LadderClimb[] {
  const by = ladderAttempts(results, testId);
  // Every climber gets the same rungs: the rule's levels, or more when anyone has results above them.
  const levels = Math.max(rule.levels ?? LADDER_LEVELS, ...[...by.values()].flatMap((m) => [...m.keys()]));
  return [...by].map(([contestantId, m]) => ({ contestantId, ...climbLadder(m, { ...rule, levels }) }));
}

// ───────────────────────────── comparisons ─────────────────────────────

export interface NamedHeight {
  label: string;
  height: number;
  borderline?: boolean;
  justShort?: boolean;
  /** Excluded from comparisons (the Random Baseline). */
  baseline?: boolean;
}

const rungsWord = (n: number) => `${n} rung${n === 1 ? '' : 's'}`;

/** "Atlas climbs 2 rungs higher than Nova (level 5 vs 3)." Plain English for any two heights. */
export function compareHeights(a: NamedHeight, b: NamedHeight): string {
  const [hi, lo] = a.height >= b.height ? [a, b] : [b, a];
  const gap = hi.height - lo.height;
  if (gap === 0) return hi.height === 0 ? `Neither ${a.label} nor ${b.label} passed level 1 reliably.` : `${a.label} and ${b.label} reach the same height: level ${hi.height}.`;
  const shaky = gap === 1 && (hi.borderline || lo.justShort);
  return `${hi.label} climbs ${rungsWord(gap)} higher than ${lo.label} (level ${hi.height} vs ${lo.height})${shaky ? ', but one rung is within the noise' : ''}.`;
}

/** One sentence for a whole comparison: the leader against the next model down (baselines ignored). */
export function heightsNote(models: readonly NamedHeight[]): string | null {
  const real = models.filter((m) => !m.baseline).slice().sort((x, y) => y.height - x.height);
  if (real.length === 0) return null;
  if (real.length === 1) return real[0]!.height === 0 ? `${real[0]!.label} did not pass level 1 reliably.` : `${real[0]!.label} reaches level ${real[0]!.height}.`;
  const top = real[0]!.height;
  const leaders = real.filter((m) => m.height === top);
  if (leaders.length === real.length) return top === 0 ? 'No model passed level 1 reliably.' : `All ${real.length} models reach the same height: level ${top}.`;
  if (leaders.length > 1) {
    const next = real[leaders.length]!;
    return `${leaders.length === 2 ? `${leaders[0]!.label} and ${leaders[1]!.label}` : `${leaders.length} models`} share the top at level ${top}, ${rungsWord(top - next.height)} above ${next.label}.`;
  }
  return compareHeights(real[0]!, real[1]!);
}

// ───────────────────────────── leaderboards ─────────────────────────────

/** A model's height on one ladder test, as carried on a leaderboard cell (TestAggregate.ladder). */
export interface LadderCell {
  height: number;
  best: number;
  onceSolved: number[];
  levels: number;
  levelsRun: number;
  borderline: boolean;
  justShort: boolean;
  passMark: number;
}

export function ladderCell(h: LadderHeight): LadderCell {
  return { height: h.height, best: h.best, onceSolved: h.onceSolved, levels: h.levels, levelsRun: h.levelsRun, borderline: h.borderline, justShort: h.justShort, passMark: h.passMark };
}

interface BoardLike {
  tests: ReadonlyArray<{ id: string }>;
  rows: Array<{ contestantId: string; tests: Record<string, { ladder?: LadderCell }>; ladderHeight?: number | null; ladderTests?: number }>;
}

/**
 * Adds every ladder test's height to a leaderboard: `row.tests[id].ladder`, and per row `ladderHeight` (the mean
 * height over the ladder tests it has results on) with `ladderTests` (how many). Boards without ladder tests are
 * left untouched. `rules` maps a test id to its rule (ladderRules(config)).
 */
export function attachLadderHeights<B extends BoardLike>(board: B, results: readonly LadderResultLike[], rules: ReadonlyMap<string, LadderRule>): B {
  const ids = board.tests.map((t) => t.id).filter((id) => rules.has(id));
  if (!ids.length) return board;
  const heights = new Map<string, number[]>();
  for (const id of ids) {
    for (const c of ladderClimbs(results, id, rules.get(id)!)) {
      const row = board.rows.find((r) => r.contestantId === c.contestantId);
      const cell = row?.tests[id];
      if (!row || !cell) continue;
      cell.ladder = ladderCell(c);
      heights.set(row.contestantId, [...(heights.get(row.contestantId) ?? []), c.height]);
    }
  }
  for (const row of board.rows) {
    const hs = heights.get(row.contestantId) ?? [];
    row.ladderHeight = hs.length ? hs.reduce((a, b) => a + b, 0) / hs.length : null;
    row.ladderTests = hs.length;
  }
  return board;
}

/** One ladder test's climbs across every recorded run (GET /api/ladders/:testId). */
export interface LadderBoard {
  testId: string;
  spec: LadderTestSpec | null;
  climbs: Array<{ id: string; label: string; color: string; climb: LadderClimb }>;
  /** Demo mode: invented results. */
  demo?: boolean;
}

// ───────────────────────────── calibration: verdict and plain English ─────────────────────────────

/** What the design-target strip means, on ladders not blind-tested yet (METHODOLOGY §7q). */
export const DESIGN_TARGET_TEXT = 'Built for (design target, not yet blind-tested): small models L1–L2, mid-sized L3–L4, today’s top models L5–L7, beyond any model today L8–L10.';

const TIER_WORD: Record<CalibrationTier, string> = { small: 'small', mid: 'mid', top: 'top' };
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-11" → "Nov 2026" (anything else is returned as given). */
export function calibrationMonth(date: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(date ?? '');
  const i = m ? Number(m[2]) - 1 : -1;
  return m && i >= 0 && i < 12 ? `${MONTHS[i]} ${m[1]}` : date;
}

/** The tiers a calibration has, smallest first. */
export function calibrationTiers(cal: LadderCalibration): CalibrationTier[] {
  return CALIBRATION_TIERS.filter((t) => cal.tiers?.[t]);
}

/** Highest level a tier passed (0 = none). */
const bestOf = (r: CalibrationTierResult | undefined) => Math.max(0, ...(r?.passed ?? []));

/** What a tier showed on one level: passed, failed, still being tested, or not tested (null). */
export function calibrationAt(cal: LadderCalibration, tier: CalibrationTier, level: number): 'passed' | 'failed' | 'pending' | null {
  const r = cal.tiers?.[tier];
  if (!r) return null;
  if (r.passed.includes(level)) return 'passed';
  if (r.failed.includes(level)) return 'failed';
  if (r.pending?.includes(level)) return 'pending';
  return null;
}

/**
 * The verdict: the explicit one, else worked out from the tiers. "top-clears" when the top model passed every level
 * it was tested on; "separates" when small < mid < top by the highest level passed (and the top model failed
 * somewhere); otherwise "partly".
 */
export function calibrationVerdict(cal: LadderCalibration): CalibrationVerdict {
  if (cal.verdict) return cal.verdict;
  const { small, mid, top } = cal.tiers ?? {};
  if (top && top.passed.length > 0 && top.failed.length === 0) return 'top-clears';
  if (small && mid && top && bestOf(small) < bestOf(mid) && bestOf(mid) < bestOf(top)) return 'separates';
  return 'partly';
}

/** "L2", "L2 and L4", "L2, L4 and L6"; runs of consecutive levels as "L9–L10". */
export function levelsText(levels: readonly number[]): string {
  const xs = [...new Set(levels)].sort((a, b) => a - b);
  const parts: string[] = [];
  for (let i = 0; i < xs.length; i++) {
    let j = i;
    while (j + 1 < xs.length && xs[j + 1] === xs[j]! + 1) j++;
    parts.push(j > i ? `L${xs[i]}–L${xs[j]}` : `L${xs[i]}`);
    i = j;
  }
  return andJoin(parts);
}

function andJoin(xs: string[]): string {
  return xs.length <= 1 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`;
}
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** "Small model (blind test, Nov 2026): passed L2, failed L4, L6 and L8". */
export function calibrationTierText(cal: LadderCalibration, tier: CalibrationTier): string {
  const r = cal.tiers?.[tier];
  const head = `${cap(TIER_WORD[tier])} model (blind test, ${calibrationMonth(cal.date)})`;
  if (!r) return `${head}: not tested`;
  const bits = [r.passed.length ? `passed ${levelsText(r.passed)}` : '', r.failed.length ? `failed ${levelsText(r.failed)}` : '', r.pending?.length ? `${levelsText(r.pending)} still being tested` : ''].filter(Boolean);
  return `${head}: ${bits.join('; ') || 'no levels tested'}`;
}

/** "L9–L10 still being tested for the mid and top models" (null when nothing is pending). */
export function calibrationPendingText(cal: LadderCalibration): string | null {
  const by = new Map<string, CalibrationTier[]>();
  for (const t of calibrationTiers(cal)) {
    const p = cal.tiers[t]!.pending;
    if (!p?.length) continue;
    const k = levelsText(p);
    by.set(k, [...(by.get(k) ?? []), t]);
  }
  if (!by.size) return null;
  return [...by].map(([lv, ts]) => `${lv} still being tested for the ${andJoin(ts.map((t) => TIER_WORD[t]))} model${ts.length > 1 ? 's' : ''}`).join('; ');
}

/**
 * The one-line verdict on a ladder card and page, e.g. "Blind test, Nov 2026: separates small, mid and top models"
 * or "Blind test, Nov 2026: today’s top models clear every level tested (up to L9) — this ladder measures
 * reliability at scale and separates small models". `area` "business": a ladder the top models clear is framed as
 * a measure of reliability at scale (the owner's call, docs/AUDIT.md); a benchmark one says "so far".
 */
export function calibrationVerdictText(cal: LadderCalibration, area: LadderArea = 'benchmark'): string {
  const head = `Blind test, ${calibrationMonth(cal.date)}`;
  const tiers = calibrationTiers(cal);
  const pending = calibrationPendingText(cal);
  const tail = pending ? ` (${pending})` : '';
  const verdict = calibrationVerdict(cal);
  if (verdict === 'separates') return `${head}: separates ${andJoin(tiers.map((t) => TIER_WORD[t]))} models${tail}`;
  if (verdict === 'top-clears') {
    const top = cal.tiers.top;
    const upTo = top?.passed.length ? ` (up to L${bestOf(top)})` : '';
    const stopped = tiers.filter((t) => t !== 'top' && cal.tiers[t]!.failed.length > 0);
    const separates = stopped.length ? `separates ${andJoin(stopped.map((t) => TIER_WORD[t]))} models` : '';
    const clears = `today’s top models clear every level tested${pending ? ' so far' : ''}${upTo}`;
    if (area === 'business') {
      const rest = separates ? ` and ${separates}` : tiers.length > 1 ? '; smaller models passed every level they were tested on too' : '';
      return `${head}: ${clears} — this ladder measures reliability at scale${rest}${tail}`;
    }
    // A benchmark ladder names every size that cleared it ("today’s mid and top models clear every level tested").
    const cleared = tiers.filter((t) => cal.tiers[t]!.passed.length > 0 && cal.tiers[t]!.failed.length === 0);
    const who = cleared.length > 1 ? `today’s ${andJoin(cleared.map((t) => TIER_WORD[t]))} models` : 'today’s top models';
    return `${head}: ${clears.replace('today’s top models', who)}${separates ? `; it ${separates}${stopped.length === 1 ? ' only' : ''}` : ''}${tail}`;
  }
  // partly: name the sizes that were not told apart (a smaller one got as far as the next one up).
  const ties: string[] = [];
  for (let i = 0; i + 1 < tiers.length; i++) {
    const a = tiers[i]!;
    const b = tiers[i + 1]!;
    if (bestOf(cal.tiers[a]) >= bestOf(cal.tiers[b])) ties.push(`${TIER_WORD[a]} and ${TIER_WORD[b]}`);
  }
  const last = tiers[tiers.length - 1];
  const lastAhead = !!last && tiers.length > 1 && tiers.slice(0, -1).every((t) => bestOf(cal.tiers[t]) < bestOf(cal.tiers[last]));
  if (!ties.length) return `${head}: partly separates ${andJoin(tiers.map((t) => TIER_WORD[t]))} models${tail}`;
  return `${head}: partly separates — ${andJoin(ties)} models came out level${lastAhead ? `; ${TIER_WORD[last!]} models got further` : ''}${tail}`;
}

/** Problems with one test's calibration (empty = valid). `levels` = the ladder's rungs. */
export function validateCalibration(at: string, cal: unknown, levels: number): string[] {
  if (!cal || typeof cal !== 'object' || Array.isArray(cal)) return [`${at}: calibration must be an object`];
  const errs: string[] = [];
  const c = cal as Record<string, unknown>;
  for (const k of Object.keys(c)) if (!['date', 'tiers', 'verdict', 'note'].includes(k)) errs.push(`${at}: calibration has an unknown field "${k}" (date, tiers, verdict, note)`);
  if (typeof c.date !== 'string' || !/^\d{4}-(0[1-9]|1[0-2])$/.test(c.date)) errs.push(`${at}: calibration.date must be "YYYY-MM", e.g. "2026-11"`);
  if (c.verdict !== undefined && !CALIBRATION_VERDICTS.includes(c.verdict as CalibrationVerdict)) errs.push(`${at}: calibration.verdict must be ${CALIBRATION_VERDICTS.map((v) => `"${v}"`).join(', ')} (or left out)`);
  if (c.note !== undefined && (typeof c.note !== 'string' || !c.note.trim())) errs.push(`${at}: calibration.note must be a sentence (or left out)`);
  const tiers = c.tiers;
  if (!tiers || typeof tiers !== 'object' || Array.isArray(tiers)) return [...errs, `${at}: calibration.tiers must be an object with small, mid and/or top`];
  if (!Object.keys(tiers).length) errs.push(`${at}: calibration.tiers needs at least one of small, mid, top`);
  for (const [tier, r] of Object.entries(tiers as Record<string, unknown>)) {
    const tt = `${at}: calibration.tiers.${tier}`;
    if (!CALIBRATION_TIERS.includes(tier as CalibrationTier)) {
      errs.push(`${tt}: the tiers are small, mid and top only`);
      continue;
    }
    if (!r || typeof r !== 'object' || Array.isArray(r)) {
      errs.push(`${tt} must be { "passed": [...], "failed": [...] }`);
      continue;
    }
    const x = r as Record<string, unknown>;
    for (const k of Object.keys(x)) if (!['passed', 'failed', 'pending'].includes(k)) errs.push(`${tt} has an unknown field "${k}" (passed, failed, pending)`);
    const seen = new Map<number, string>();
    for (const name of ['passed', 'failed', 'pending']) {
      const list = x[name];
      if (list === undefined && name === 'pending') continue;
      if (!Array.isArray(list)) {
        errs.push(`${tt}.${name} must be a list of level numbers (empty is fine)`);
        continue;
      }
      for (const lv of list as unknown[]) {
        if (typeof lv !== 'number' || !Number.isInteger(lv) || lv < 1 || lv > levels) {
          errs.push(`${tt}.${name}: ${JSON.stringify(lv)} is not a level (1..${levels})`);
          continue;
        }
        const before = seen.get(lv);
        if (before === name) errs.push(`${tt}.${name}: L${lv} is listed twice`);
        else if (before) errs.push(`${tt}: L${lv} is both ${before} and ${name}`);
        else seen.set(lv, name);
      }
    }
  }
  return errs;
}

// ───────────────────────────── the config ─────────────────────────────

/** Rules for every BUILT ladder test (planned tests have no results to measure). */
export function ladderRules(cfg: LaddersConfig): Map<string, LadderRule> {
  return new Map(cfg.tests.filter((t) => !t.planned).map((t) => [t.id, { passMark: t.passMark, levels: t.rungs?.length ?? cfg.levels }]));
}

export function ladderSpec(cfg: LaddersConfig, testId: string): LadderTestSpec | undefined {
  return cfg.tests.find((t) => t.id === testId);
}

/** Is every test of this suite a ladder test? (planned suite entries count.) */
export function isLadderSuite(cfg: LaddersConfig, suite: { id: string; tests?: ReadonlyArray<{ id: string }> }): boolean {
  const ids = (suite.tests ?? []).map((t) => t.id).filter((id) => id !== '*');
  return ids.length > 0 && ids.every((id) => cfg.tests.some((t) => t.id === id));
}

/** What the validator needs to know about a test in the library. */
export interface LibraryTest {
  category: string;
  /** Case ids (prompt tests) or seed ids (programs). */
  caseIds: string[];
}

/**
 * Problems with config/ladders.json (empty = valid). `library` holds every test in the library: a test not
 * marked planned must exist and be a real ladder (cases exactly L01…Ln, n ≥ levels, one rung label per level);
 * a planned test that now exists must be flipped (delete "planned": true and add its rung labels).
 */
export function validateLadders(cfg: LaddersConfig, library: ReadonlyMap<string, LibraryTest>): string[] {
  const errs: string[] = [];
  if (!/^\d+\.\d+\.\d+$/.test(cfg.version ?? '')) errs.push('version must be semver (x.y.z)');
  if (!cfg.title?.trim()) errs.push('needs a title');
  if (cfg.calibrationMethod !== undefined && !(typeof cfg.calibrationMethod === 'string' && cfg.calibrationMethod.trim())) errs.push('calibrationMethod must be one line of text (or left out)');
  if (!Number.isInteger(cfg.levels) || cfg.levels < 2) errs.push('levels must be a whole number ≥ 2 (10)');
  const seen = new Set<string>();
  for (const t of cfg.tests ?? []) {
    const at = `${t.id ?? '?'}`;
    if (!/^[a-z][a-z0-9-]*\.[a-z0-9][a-z0-9.-]*$/.test(t.id ?? '')) errs.push(`${at}: id must look like "category.name"`);
    if (seen.has(t.id)) errs.push(`${at}: listed twice`);
    seen.add(t.id);
    if (!t.name?.trim()) errs.push(`${at}: needs a name`);
    if (!t.grows?.trim()) errs.push(`${at}: needs "grows" (one line: what grows from rung to rung)`);
    if (t.area !== 'benchmark' && t.area !== 'business') errs.push(`${at}: area must be "benchmark" or "business"`);
    const business = t.id?.startsWith('work.') || t.category === 'work';
    if ((t.area === 'business') !== !!business) errs.push(`${at}: work.* tests are area "business", every other ladder is "benchmark"`);
    if (!(typeof t.passMark === 'number' && t.passMark > 0 && t.passMark <= 1)) errs.push(`${at}: passMark must be in (0, 1] (1 = full marks)`);
    if (t.basedOn && !library.has(t.basedOn)) errs.push(`${at}: basedOn "${t.basedOn}" is not in the test library`);
    if (t.calibration !== undefined) errs.push(...validateCalibration(at, t.calibration, t.rungs?.length || cfg.levels));
    const lib = library.get(t.id);
    if (t.planned) {
      if (lib) errs.push(`${at} now exists in the test library: delete "planned": true and add its ${cfg.levels} "rungs" labels in config/ladders.json (and flip it in its suite)`);
      if (t.rungs && t.rungs.length !== cfg.levels) errs.push(`${at}: rungs must have ${cfg.levels} labels (or none while planned)`);
      continue;
    }
    if (!lib) {
      errs.push(`${at} is not in the test library: mark it "planned": true until it is built`);
      continue;
    }
    if (lib.category !== t.category) errs.push(`${at}: category "${t.category}" but the test file says "${lib.category}"`);
    const levels = lib.caseIds.map(ladderLevel);
    const n = lib.caseIds.length;
    if (levels.some((l) => l === null) || JSON.stringify(lib.caseIds) !== JSON.stringify(ladderCaseIds(n))) errs.push(`${at}: cases must be exactly L01…L${String(n).padStart(2, '0')} in order (got ${lib.caseIds.slice(0, 4).join(', ')}${n > 4 ? ', …' : ''})`);
    if (n < cfg.levels) errs.push(`${at}: has ${n} levels, a ladder needs at least ${cfg.levels}`);
    if (!t.rungs || t.rungs.length !== n || t.rungs.some((r) => !r?.trim())) errs.push(`${at}: needs "rungs": one short label per level (${n}), bottom first, e.g. "3×3 · 20 moves"`);
  }
  return errs;
}
