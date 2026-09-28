/**
 * Head to Head — pure builder (unit-tested in test/versus.test.ts, reused by the
 * mock API). Compares two models round by round on stored results only: no model
 * is called, nothing is estimated or invented.
 *
 * Rules
 *  - A round is a test where both models have scored results. Only the cases
 *    both answered are compared, so a half-finished run can't skew a round.
 *  - Round score = mean over those cases of the mean over repeats (the same
 *    formula as the leaderboard's test score).
 *  - Scores less than `tieMargin` points apart (out of 100) are a tied round.
 *  - The match goes to whoever wins more rounds; equal rounds are a draw.
 *  - Cost and time are for ONE pass through the compared cases (mean per case,
 *    summed), so running a model twice doesn't make it look twice as expensive.
 *  - The decisive moment is a case where one model was right and the other wrong,
 *    quoted from the recorded responses. Rounds without such a case have none.
 */
import type { CaseMetrics, CaseResult, CategoryInfo, Contestant, ScoreDetail, TranscriptEntry } from '../core/types.ts';
import type { VersusAnswer, VersusData, VersusFighter, VersusMoment, VersusOptions, VersusRound, VersusScope, VersusSide, VersusSkipped, VersusTotals } from './types.ts';

/** Default draw margin, in points out of 100. */
export const TIE_MARGIN = 2;
/** Longest quote per answer (characters). */
export const QUOTE_MAX = 240;
const QUESTION_MAX = 220;

/** The fields of a stored result the builder reads (full results or mock ones). */
export type VersusResult = Pick<CaseResult, 'key' | 'runId' | 'contestantId' | 'testId' | 'caseId' | 'status' | 'score' | 'passed' | 'summary' | 'finishedAt'> & {
  scoreDetail: ScoreDetail;
  metrics: Pick<CaseMetrics, 'costUsd' | 'wallMs' | 'outputTokens' | 'outputTokensPerSec'>;
  transcript?: TranscriptEntry[];
};

export interface VersusTestInfo {
  id: string;
  name: string;
  category: string;
  hook?: string;
}

export class VersusInputError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** Contestant → fighter. `baseline` / `manual` come from the provider type. */
export function fighterOf(c: Pick<Contestant, 'id' | 'label' | 'vendor' | 'color' | 'pricing' | 'contextWindow'>, flags: { baseline?: boolean; manual?: boolean } = {}): VersusFighter {
  const priced = !flags.baseline && !flags.manual && c.pricing && (c.pricing.inputPerM > 0 || c.pricing.outputPerM > 0);
  return {
    id: c.id,
    label: c.label || c.id,
    vendor: c.vendor || '',
    color: c.color || '#64748b',
    pricing: priced ? { inputPerM: c.pricing.inputPerM, outputPerM: c.pricing.outputPerM } : null,
    contextWindow: typeof c.contextWindow === 'number' && c.contextWindow > 0 ? c.contextWindow : null,
    ...(flags.baseline ? { baseline: true } : {}),
    ...(flags.manual ? { manual: true } : {}),
  };
}

/** Results that count toward scores (same rule as the leaderboard). */
export function isScoredResult(r: Pick<VersusResult, 'score' | 'status'>): boolean {
  return r.score !== null && r.status !== 'error' && r.status !== 'cancelled' && r.status !== 'pending-human' && r.status !== 'skipped';
}

const round4 = (n: number) => Math.round(n * 1e4) / 1e4;
const round6 = (n: number) => Math.round(n * 1e6) / 1e6;
const avg = (xs: number[]) => (xs.length ? xs.reduce((s, x) => s + x, 0) / xs.length : null);

/** Right / wrong for the decisive moment: the recorded pass flag, else a full or zero score. */
function verdict(r: VersusResult): 'right' | 'wrong' | null {
  if (r.passed === true) return 'right';
  if (r.passed === false) return 'wrong';
  if (r.score === null) return null;
  if (r.score >= 0.999) return 'right';
  if (r.score <= 0.001) return 'wrong';
  return null;
}

function collapse(text: string): string {
  return text
    .replace(/\r\n?/g, '\n')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** Cut at a word boundary: keep the start (`from: 'start'`) or the end. */
function cut(text: string, max: number, from: 'start' | 'end'): string {
  if (text.length <= max) return text;
  if (from === 'start') {
    const s = text.slice(0, max);
    const sp = s.lastIndexOf(' ');
    return (sp > max * 0.6 ? s.slice(0, sp) : s).trimEnd();
  }
  const s = text.slice(text.length - max);
  const sp = s.indexOf(' ');
  return (sp >= 0 && sp < max * 0.4 ? s.slice(sp + 1) : s).trimStart();
}

/**
 * A short quote of a recorded response: the whole thing when short, else the
 * window that ends just after the extracted answer, else the conclusion.
 */
export function quoteResponse(response: string, extracted?: string, max = QUOTE_MAX): { quote: string; truncated: boolean } {
  const text = collapse(response);
  if (text.length <= max) return { quote: text, truncated: false };
  const needle = extracted?.trim();
  const at = needle && needle.length <= max / 2 ? text.lastIndexOf(needle) : -1;
  if (at >= 0) {
    // End at the end of the line (or sentence) holding the answer, at most 40 characters on.
    const after = text.slice(at + needle!.length, at + needle!.length + 40);
    const stop = after.search(/[\n.!?]/);
    const end = at + needle!.length + (stop >= 0 ? stop + (after[stop] === '\n' ? 0 : 1) : 0);
    const start = Math.max(0, end - max);
    const q = text.slice(start, end);
    return { quote: start > 0 ? cut(q, q.length - 1, 'end') : q, truncated: true };
  }
  return { quote: cut(text, max, 'end'), truncated: true };
}

/** The model's final reply in a result (the last non-judge call). */
export function finalResponse(r: VersusResult): string | null {
  const calls = (r.transcript ?? []).filter((t) => !t.judge);
  for (let i = calls.length - 1; i >= 0; i--) {
    const text = calls[i]!.response;
    if (typeof text === 'string' && text.trim()) return text;
  }
  return null;
}

/** The question the model was asked (first user message of the first call), truncated. */
export function questionOf(r: VersusResult): string | undefined {
  const first = (r.transcript ?? []).find((t) => !t.judge);
  const msg = first?.messages.find((m) => m.role === 'user');
  // Drop the answer-format instructions every question shares ("…write FINAL ANSWER: <answer>").
  const raw = typeof msg?.content === 'string' ? msg.content : '';
  const kept = raw.split(/\n+/).filter((line) => !/FINAL ANSWER/i.test(line));
  const text = collapse(kept.join('\n') || raw);
  if (!text) return undefined;
  return text.length > QUESTION_MAX ? `${cut(text, QUESTION_MAX, 'start')}…` : text;
}

function shortValue(v: unknown, max = 80): string | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return String(v);
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  if (typeof v === 'string' && v.trim() && v.trim().length <= max) return v.trim();
  return undefined;
}

function answerOf(r: VersusResult): VersusAnswer {
  const extracted = shortValue(r.scoreDetail?.extracted, 120);
  const response = finalResponse(r);
  const q = response ? quoteResponse(response, extracted) : { quote: '', truncated: false };
  return {
    quote: q.quote,
    truncated: q.truncated,
    ...(extracted ? { extracted } : {}),
    ...(r.summary ? { summary: r.summary } : {}),
    score: r.score,
    passed: r.passed,
    runId: r.runId,
    key: r.key,
  };
}

/** Latest scored result per case. */
function latestByCase(rs: VersusResult[]): Map<string, VersusResult> {
  const m = new Map<string, VersusResult>();
  for (const r of rs) {
    const cur = m.get(r.caseId);
    if (!cur || (r.finishedAt ?? '') > (cur.finishedAt ?? '')) m.set(r.caseId, r);
  }
  return m;
}

function pickMoment(caseIds: string[], a: VersusResult[], b: VersusResult[], prefer: 'a' | 'b' | 'tie'): VersusMoment | null {
  const la = latestByCase(a);
  const lb = latestByCase(b);
  const candidates: Array<{ caseId: string; right: 'a' | 'b'; gap: number; ra: VersusResult; rb: VersusResult; len: number }> = [];
  for (const id of caseIds) {
    const ra = la.get(id);
    const rb = lb.get(id);
    if (!ra || !rb) continue;
    const va = verdict(ra);
    const vb = verdict(rb);
    if (!va || !vb || va === vb) continue;
    const right = va === 'right' ? 'a' : 'b';
    const len = (finalResponse(ra)?.length ?? 0) + (finalResponse(rb)?.length ?? 0);
    candidates.push({ caseId: id, right, gap: Math.abs((ra.score ?? 0) - (rb.score ?? 0)), ra, rb, len });
  }
  if (!candidates.length) return null;
  // The round winner being right explains the round; then the biggest gap; then answers that quote whole; then case order.
  candidates.sort((x, y) => Number(y.right === prefer) - Number(x.right === prefer) || y.gap - x.gap || x.len - y.len || x.caseId.localeCompare(y.caseId));
  const c = candidates[0]!;
  const expected = shortValue(c.ra.scoreDetail?.expected) ?? shortValue(c.rb.scoreDetail?.expected);
  const question = questionOf(c.ra) ?? questionOf(c.rb);
  return {
    caseId: c.caseId,
    right: c.right,
    ...(question ? { question } : {}),
    ...(expected ? { expected } : {}),
    a: answerOf(c.ra),
    b: answerOf(c.rb),
  };
}

function sideOf(rs: VersusResult[], caseIds: Set<string>): VersusSide {
  const shared = rs.filter((r) => caseIds.has(r.caseId));
  const scored = shared.filter(isScoredResult);
  const byCase = new Map<string, VersusResult[]>();
  for (const r of shared) byCase.set(r.caseId, [...(byCase.get(r.caseId) ?? []), r]);
  const caseScores: number[] = [];
  let cost = 0;
  let time = 0;
  for (const list of byCase.values()) {
    const s = avg(list.filter(isScoredResult).map((r) => r.score!));
    if (s !== null) caseScores.push(s);
    cost += avg(list.map((r) => r.metrics.costUsd || 0)) ?? 0;
    time += avg(list.map((r) => r.metrics.wallMs || 0)) ?? 0;
  }
  const withPass = scored.filter((r) => typeof r.passed === 'boolean');
  const score = avg(caseScores);
  return {
    score: score === null ? null : round4(score),
    samples: scored.length,
    passRate: withPass.length ? round4(withPass.filter((r) => r.passed).length / withPass.length) : null,
    costUsd: round6(cost),
    timeMs: byCase.size ? Math.round(time) : null,
  };
}

function totalsOf(rounds: VersusRound[], side: 'a' | 'b', compared: VersusResult[]): VersusTotals {
  const scores = rounds.map((r) => r[side].score).filter((s): s is number => s !== null);
  const avgScore = scores.length ? Math.round((scores.reduce((s, x) => s + x, 0) / scores.length) * 1000) / 10 : null;
  const cost = round6(rounds.reduce((s, r) => s + r[side].costUsd, 0));
  const times = rounds.map((r) => r[side].timeMs).filter((t): t is number => t !== null);
  let genSec = 0;
  let outTok = 0;
  for (const r of compared) {
    const tps = r.metrics.outputTokensPerSec;
    if (tps && tps > 0 && r.metrics.outputTokens > 0) {
      genSec += r.metrics.outputTokens / tps;
      outTok += r.metrics.outputTokens;
    }
  }
  return {
    roundsWon: rounds.filter((r) => r.winner === side).length,
    avgScore,
    costUsd: cost,
    timeMs: times.length ? times.reduce((s, t) => s + t, 0) : null,
    tokensPerSec: genSec > 0 ? Math.round((outTok / genSec) * 10) / 10 : null,
    value: avgScore !== null && cost > 0 ? Math.round((avgScore / cost) * 10) / 10 : null,
  };
}

export interface VersusInput {
  scope: VersusScope;
  a: VersusFighter;
  b: VersusFighter;
  /** Tests in display order. Results for other tests are ignored. */
  tests: VersusTestInfo[];
  categories: CategoryInfo[];
  results: VersusResult[];
  tieMargin?: number;
  now?: string;
}

export function buildVersus(input: VersusInput): VersusData {
  const { a, b } = input;
  if (a.id === b.id) throw new VersusInputError(400, 'Pick two different models.');
  const tieMargin = input.tieMargin ?? TIE_MARGIN;
  const cats = new Map(input.categories.map((c) => [c.id, c]));
  const mine = (id: string, testId: string) => input.results.filter((r) => r.contestantId === id && r.testId === testId && r.status !== 'skipped');

  const rounds: VersusRound[] = [];
  const skipped: VersusSkipped[] = [];
  const compared: { a: VersusResult[]; b: VersusResult[] } = { a: [], b: [] };
  for (const t of input.tests) {
    const ra = mine(a.id, t.id);
    const rb = mine(b.id, t.id);
    const casesA = new Set(ra.filter(isScoredResult).map((r) => r.caseId));
    const casesB = new Set(rb.filter(isScoredResult).map((r) => r.caseId));
    if (!casesA.size && !casesB.size) continue;
    if (!casesB.size) {
      skipped.push({ testId: t.id, testName: t.name, reason: 'only-a' });
      continue;
    }
    if (!casesA.size) {
      skipped.push({ testId: t.id, testName: t.name, reason: 'only-b' });
      continue;
    }
    const shared = [...casesA].filter((c) => casesB.has(c)).sort();
    if (!shared.length) {
      skipped.push({ testId: t.id, testName: t.name, reason: 'no-shared-cases' });
      continue;
    }
    const set = new Set(shared);
    const sa = sideOf(ra, set);
    const sb = sideOf(rb, set);
    const diff = ((sa.score ?? 0) - (sb.score ?? 0)) * 100;
    const winner: VersusRound['winner'] = Math.abs(diff) < tieMargin - 1e-9 ? 'tie' : diff > 0 ? 'a' : 'b';
    const cat = cats.get(t.category);
    const sharedA = ra.filter((r) => set.has(r.caseId) && isScoredResult(r));
    const sharedB = rb.filter((r) => set.has(r.caseId) && isScoredResult(r));
    compared.a.push(...ra.filter((r) => set.has(r.caseId)));
    compared.b.push(...rb.filter((r) => set.has(r.caseId)));
    rounds.push({
      testId: t.id,
      testName: t.name,
      category: t.category,
      categoryName: cat?.name ?? t.category,
      categoryColor: cat?.color ?? '#64748b',
      ...(t.hook ? { hook: t.hook } : {}),
      cases: shared.length,
      a: sa,
      b: sb,
      winner,
      margin: Math.round(Math.abs(diff) * 10) / 10,
      moment: pickMoment(shared, sharedA, sharedB, winner),
    });
  }

  const totals = { a: totalsOf(rounds, 'a', compared.a), b: totalsOf(rounds, 'b', compared.b) };
  return {
    scope: input.scope,
    a,
    b,
    rounds,
    skipped,
    totals,
    ties: rounds.filter((r) => r.winner === 'tie').length,
    winner: totals.a.roundsWon > totals.b.roundsWon ? 'a' : totals.b.roundsWon > totals.a.roundsWon ? 'b' : 'tie',
    tieMargin,
    generatedAt: input.now ?? new Date().toISOString(),
  };
}

/** Who can be compared in a scope, plus a suggested pair (the two best models that share the most tests). */
export function buildVersusOptions(scope: VersusScope, fighters: VersusFighter[], tests: VersusTestInfo[], results: VersusResult[], runs: VersusOptions['runs']): VersusOptions {
  const testIds = new Set(tests.map((t) => t.id));
  const perFighter = new Map<string, Map<string, number[]>>();
  for (const r of results) {
    if (!testIds.has(r.testId) || !isScoredResult(r)) continue;
    const m = perFighter.get(r.contestantId) ?? new Map<string, number[]>();
    m.set(r.testId, [...(m.get(r.testId) ?? []), r.score!]);
    perFighter.set(r.contestantId, m);
  }
  const rows = fighters
    .filter((f) => perFighter.has(f.id))
    .map((f) => {
      const m = perFighter.get(f.id)!;
      const means = [...m.values()].map((xs) => avg(xs)!);
      return { ...f, tests: m.size, _avg: avg(means) ?? 0, _set: new Set(m.keys()) };
    });
  const ranked = rows.filter((r) => !r.baseline).sort((x, y) => y._avg - x._avg || x.label.localeCompare(y.label));
  const pool = ranked.length >= 2 ? ranked : [...rows].sort((x, y) => y._avg - x._avg);
  let suggested: [string, string] | null = null;
  if (pool.length >= 2) {
    const first = pool[0]!;
    const overlap = (r: (typeof pool)[number]) => [...r._set].filter((t) => first._set.has(t)).length;
    const second = pool.slice(1).sort((x, y) => overlap(y) - overlap(x) || y._avg - x._avg)[0]!;
    suggested = [first.id, second.id];
  }
  return {
    scope,
    fighters: rows.map(({ _avg: _a, _set: _s, ...f }) => f).sort((x, y) => Number(!!x.baseline) - Number(!!y.baseline) || x.label.localeCompare(y.label)),
    suggested,
    runs,
  };
}
