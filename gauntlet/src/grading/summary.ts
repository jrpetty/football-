/**
 * 30-word performance summaries: one short paragraph per model × test,
 * built only from recorded results (scores, which cases passed or failed and
 * why, judge rationales, time and cost). Deterministic and free, so it is
 * always available; an optional AI-written version lives in station.ts.
 *
 * Pure (no Node APIs): the Run detail page, the result inspector, the
 * Grading Station, the Presenter and the Studio all call it directly.
 */
import type { CaseMetrics, ResultStatus, ScoreDetail } from '../core/types.ts';

export const SUMMARY_WORD_LIMIT = 30;

/** The parts of a stored result the summary reads (a CaseResultLite fits). */
export interface SummaryResult {
  caseId: string;
  repeat: number;
  status: ResultStatus;
  score: number | null;
  passed: boolean | null;
  summary: string;
  scoreDetail?: ScoreDetail;
  metrics?: Partial<CaseMetrics>;
  error?: string;
  humanScores?: Array<{ score: number }>;
}

export interface SummaryInput {
  /** Singular noun for one case: "question", "task", "world", "conversation", "game". */
  unit: string;
  kind: 'prompt' | 'program';
  results: SummaryResult[];
  /** Answers pasted by hand: time and cost are not measurements, so they are left out. */
  manual?: boolean;
}

// ───────────────────────────── Word helpers ─────────────────────────────

export function countWords(text: string): number {
  return text.split(/\s+/).filter(Boolean).length;
}

/** Cut to at most `limit` words, ending cleanly with a full stop or an ellipsis. */
export function clampWords(text: string, limit = SUMMARY_WORD_LIMIT): string {
  const one = text.replace(/\s+/g, ' ').trim();
  const words = one.split(' ').filter(Boolean);
  if (words.length <= limit) return one;
  let out = words.slice(0, limit).join(' ');
  // Prefer ending on a whole sentence when one ends in the last third.
  const lastStop = Math.max(out.lastIndexOf('. '), out.lastIndexOf('! '), out.lastIndexOf('? '));
  if (lastStop > out.length * 0.6) return out.slice(0, lastStop + 1);
  out = out.replace(/[,;:–—-]+$/, '');
  return /[.!?…]$/.test(out) ? out : `${out}…`;
}

function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

function pluralNoun(unit: string): string {
  return /(s|x|ch|sh)$/.test(unit) ? `${unit}es` : `${unit}s`;
}

function pts(x: number): string {
  return `${Math.round(x * 100)}/100`;
}

/** A short quoted snippet of model output: at most `maxWords` words and 28 characters. */
export function quoteSnippet(s: unknown, maxWords = 3): string {
  const text = (typeof s === 'string' ? s : JSON.stringify(s) ?? '').replace(/\s+/g, ' ').trim().replace(/^["“']|["”']$/g, '');
  if (!text) return '""';
  const words = text.split(' ');
  let cut = words.slice(0, maxWords).join(' ');
  if (cut.length > 28) cut = `${cut.slice(0, 27).replace(/\s+\S*$/, '')}`;
  const more = cut.length < text.length;
  return `"${cut}${more ? '…' : ''}"`;
}

/** "c7" → "question 7", "seed-42" → "world 42", other ids as they are. */
export function caseName(caseId: string, unit: string): string {
  const seed = /^seed[-_]?(\d+)$/i.exec(caseId);
  if (seed) return `world ${Number(seed[1])}`;
  const q = /^(?:c|case|q)[-_]?(\d+)$/i.exec(caseId);
  if (q) return `${unit} ${Number(q[1])}`;
  return caseId;
}

function firstSentence(text: string, maxWords: number): string {
  const t = text.replace(/\s+/g, ' ').trim();
  const s = t.match(/^(.+?[.!?])(\s|$)/)?.[1] ?? t;
  const words = s.split(' ');
  return words.length > maxWords ? `${words.slice(0, maxWords).join(' ').replace(/[,;:.]+$/, '')}…` : s.replace(/[.!?]$/, '');
}

function fmtSec(ms: number): string {
  const s = ms / 1000;
  if (s < 1) return `${s.toFixed(1)} s`;
  if (s < 60) return `${Math.round(s)} s`;
  return `${(s / 60).toFixed(1)} min`;
}

function fmtUsd(usd: number): string {
  if (usd <= 0) return 'nothing';
  if (usd < 0.01) return 'under 1¢';
  if (usd < 10) return `$${usd.toFixed(2)}`;
  return `$${Math.round(usd)}`;
}

// ───────────────────────────── Why a case missed ─────────────────────────────

function looksLikePattern(v: unknown): boolean {
  return typeof v === 'string' && /[\\^$|[\]()]/.test(v);
}

/** One short clause saying why this result lost points (grounded in its stored detail). */
export function missReason(r: SummaryResult): string {
  const d = r.scoreDetail ?? {};
  if (r.status === 'timeout') return 'ran out of time';
  if (r.status === 'refusal') return 'refused to answer';
  if (r.status === 'error') return 'hit an error';
  if (d.hedged) return 'hedged between two answers';
  if (typeof d.label === 'string' && d.label) return `judged ${d.label.replace(/_/g, ' ').toLowerCase()}`;
  const expected = Array.isArray(d.expected) && d.expected.every((x) => typeof x === 'string') ? d.expected[0] : d.expected;
  const scalar = (v: unknown) => typeof v === 'string' || typeof v === 'number';
  if (scalar(d.extracted) && String(d.extracted).trim() && scalar(expected) && !looksLikePattern(expected) && String(d.extracted).length <= 80) {
    return `answered ${quoteSnippet(String(d.extracted))}, key says ${quoteSnippet(String(expected))}`;
  }
  if (d.formatOk === false && !d.items?.length) return 'gave no final answer line';
  const failed = (d.items ?? []).filter((i) => !i.passed);
  if (failed.length) {
    const label = quoteSnippet(failed[0]!.label, 5);
    return failed.length === 1 ? `failed ${label}` : `failed ${failed.length} checks, e.g. ${label}`;
  }
  const judges = (d.judge ?? []).filter((j) => j.rationale?.trim());
  if (judges.length) {
    const worst = judges.reduce((a, b) => (b.score < a.score ? b : a));
    return `a judge noted "${firstSentence(worst.rationale, 9)}"`;
  }
  if (typeof d.loadError === 'string') return 'its code failed to load';
  if (r.summary) return `recorded as ${quoteSnippet(r.summary, 6)}`;
  return 'lost points';
}

// ───────────────────────────── The summary ─────────────────────────────

interface CaseAgg {
  caseId: string;
  score: number;
  worst: SummaryResult;
}

/**
 * The deterministic summary (≤ 30 words). Example:
 * "Got 9 of 10 questions right (90/100). Missed question 7: answered "Wolf", key says "Fox". Averaged 12 s per question, costing $0.04."
 */
export function templateSummary(input: SummaryInput): string {
  const { unit, results } = input;
  const units = pluralNoun(unit);
  if (!results.length) return 'No results recorded for this test yet.';

  const skipped = results.filter((r) => r.status === 'skipped');
  // An answer whose judges were missing is not broken: it is waiting to be graded (the Grading Station can do it).
  const judgeWait = (r: SummaryResult) => r.status === 'error' && /all judges failed|no judges/i.test(r.error ?? '');
  const errors = results.filter((r) => (r.status === 'error' && !judgeWait(r)) || r.status === 'cancelled');
  const noJudge = results.filter(judgeWait);
  const pending = results.filter((r) => r.status === 'pending-human' || (r.score === null && r.status === 'ok') || judgeWait(r));
  const scored = results.filter((r) => typeof r.score === 'number' && r.status !== 'skipped');
  const caseIds = [...new Set(results.map((r) => r.caseId))];

  if (!scored.length) {
    if (skipped.length === results.length) return clampWords(`Skipped all ${plural(new Set(skipped.map((r) => r.caseId)).size, `picture ${unit}`, `picture ${units}`)}: this model cannot see images, so they are not counted against it.`);
    if (pending.length && !errors.length) {
      if (noJudge.length === pending.length) return clampWords(`No score yet: no AI judge could grade ${noJudge.length === 1 ? 'this answer' : `these ${noJudge.length} answers`} during the run, so ${noJudge.length === 1 ? 'it waits' : 'they wait'} in the Grading Station.`);
      return clampWords(`No score yet: ${plural(pending.length, 'answer')} ${pending.length === 1 ? 'is' : 'are'} waiting for grading in the Grading Station.`);
    }
    if (errors.length) {
      const head = `No score: ${errors.length === results.length ? 'every attempt' : plural(errors.length, 'attempt')} failed with an error.`;
      return clampWords(head + (pending.length ? ` ${pending.length} more awaiting grading.` : '') + ' Errors are left out of the average; resume the run to retry.');
    }
    return 'No scored results yet.';
  }

  // Case scores: mean over repeats, then over cases (exactly how the leaderboard averages).
  const byCase = new Map<string, SummaryResult[]>();
  for (const r of scored) byCase.set(r.caseId, [...(byCase.get(r.caseId) ?? []), r]);
  const cases: CaseAgg[] = [...byCase.entries()].map(([caseId, rs]) => ({
    caseId,
    score: rs.reduce((s, r) => s + (r.score as number), 0) / rs.length,
    worst: rs.reduce((a, b) => ((b.score as number) < (a.score as number) ? b : a)),
  }));
  const order = new Map(caseIds.map((id, i) => [id, i]));
  cases.sort((a, b) => (order.get(a.caseId) ?? 0) - (order.get(b.caseId) ?? 0));
  const testScore = cases.reduce((s, c) => s + c.score, 0) / cases.length;
  const binary = scored.every((r) => r.score === 0 || r.score === 1);
  const repeats = scored.length > cases.length;
  // "Judges scored it" only when the judges are the whole score (artifact tests mix in automated checks).
  const judged = scored.some((r) => (r.scoreDetail?.judge?.length ?? 0) > 0) && !scored.some((r) => (r.scoreDetail?.items?.length ?? 0) > 0);
  const official = scored.map((r) => (r.scoreDetail?.official as { source?: string } | undefined)?.source).find(Boolean);
  const human = official === 'human' || official === 'arbitration' || scored.some((r) => r.scoreDetail?.humanScored);

  // Sentence 1: the headline number.
  let head: string;
  if (binary && input.kind === 'prompt') {
    const right = scored.filter((r) => r.score === 1).length;
    head = repeats
      ? `Right on ${right} of ${scored.length} tries across ${plural(cases.length, unit, units)} (${pts(testScore)}).`
      : `Got ${right} of ${plural(cases.length, unit, units)} right (${pts(testScore)}).`;
    if (right === 0) head = repeats ? `Got none of ${scored.length} tries right across ${plural(cases.length, unit, units)} (0/100).` : `Got none of ${plural(cases.length, unit, units)} right (0/100).`;
  } else if (binary) {
    const won = scored.filter((r) => r.score === 1).length;
    head = `Passed ${won} of ${plural(scored.length, repeats ? 'run' : unit, repeats ? 'runs' : units)} (${pts(testScore)}).`;
  } else if (human) {
    head = `Human-graded ${pts(testScore)} over ${plural(cases.length, unit, units)}.`;
  } else if (judged) {
    head = `Judges scored it ${pts(testScore)} over ${plural(cases.length, unit, units)}.`;
  } else if (input.kind === 'program') {
    head = `Averaged ${pts(testScore)} over ${plural(cases.length, unit, units)}.`;
  } else {
    head = `Scored ${pts(testScore)} across ${plural(cases.length, unit, units)}.`;
  }

  // Sentence 2: where it lost points, and why.
  const why: string[] = [];
  const misses = cases.filter((c) => c.score < 1);
  if (!misses.length) {
    why.push(cases.length === 1 ? 'Full marks.' : `Perfect on every ${unit}.`);
  } else if (binary && input.kind === 'prompt') {
    const worst = misses[0]!;
    const names = misses.map((c) => caseName(c.caseId, unit));
    const reason = missReason(worst.worst);
    if (misses.length === cases.length && cases.length > 1) why.push(`Every ${unit} missed, e.g. ${names[0]}: ${reason}.`);
    else if (misses.length === 1) why.push(`Missed ${names[0]}: ${reason}.`);
    else if (misses.length === 2) why.push(`Missed ${names[0]} (${reason}) and ${names[1]}.`);
    else if (misses.length < cases.length) why.push(`Missed ${misses.length}, e.g. ${names[0]}: ${reason}.`);
    else why.push(`Every ${unit} missed, e.g. ${names[0]}: ${reason}.`);
  } else {
    const worst = misses.reduce((a, b) => (b.score < a.score ? b : a));
    const best = cases.reduce((a, b) => (b.score > a.score ? b : a));
    const name = caseName(worst.caseId, unit);
    const reason = input.kind === 'program' && worst.worst.summary ? `"${firstSentence(worst.worst.summary, 8)}"` : missReason(worst.worst);
    if (cases.length > 1) why.push(`Weakest: ${name} at ${pts(worst.score)}, ${reason}.`);
    else why.push(`${reason.charAt(0).toUpperCase()}${reason.slice(1)}.`);
    if (cases.length > 1 && best.score > worst.score) why.push(`Best: ${caseName(best.caseId, unit)} at ${pts(best.score)}.`);
  }

  // Extras: errors, skips, pending grading.
  const extras: string[] = [];
  if (errors.length) extras.push(`${plural(errors.length, 'attempt')} errored and ${errors.length === 1 ? 'was' : 'were'} left out.`);
  if (skipped.length) extras.push(`${plural(skipped.length, `picture ${unit}`, `picture ${units}`)} skipped (no image input).`);
  if (pending.length) extras.push(`${pending.length} still awaiting grading.`);

  // Sentence 3: time and cost (API measurements only).
  let cost = '';
  if (!input.manual) {
    const walls = scored.map((r) => r.metrics?.wallMs).filter((x): x is number => typeof x === 'number' && x > 0);
    const spend = scored.reduce((s, r) => s + (r.metrics?.costUsd ?? 0), 0);
    if (walls.length) cost = `Took ${fmtSec(walls.reduce((s, x) => s + x, 0) / walls.length)} per ${unit} on average; cost ${fmtUsd(spend)}.`;
  } else cost = 'Answers pasted by hand.';

  // Assemble within the word limit, most important first.
  const parts: string[] = [head];
  const fits = (s: string) => countWords([...parts, s].join(' ')) <= SUMMARY_WORD_LIMIT;
  const primary = why[0];
  if (primary) {
    if (fits(primary)) parts.push(primary);
    else if (misses.length) {
      const short = binary && input.kind === 'prompt' ? `Missed ${plural(misses.length, unit, units)}.` : `Weakest: ${caseName(misses.reduce((a, b) => (b.score < a.score ? b : a)).caseId, unit)}.`;
      if (fits(short)) parts.push(short);
    }
  }
  for (const e of extras) if (fits(e)) parts.push(e);
  if (cost && fits(cost)) parts.push(cost);
  for (const w of why.slice(1)) if (fits(w)) parts.push(w);
  return clampWords(parts.join(' '));
}

/** Summaries for every model × test in a set of results, keyed "contestantId|testId". */
export function templateSummaries(
  results: Array<SummaryResult & { contestantId: string; testId: string }>,
  info: { unitOf: (testId: string) => string; kindOf: (testId: string) => 'prompt' | 'program'; manual?: (contestantId: string) => boolean },
): Map<string, string> {
  const groups = new Map<string, Array<SummaryResult & { contestantId: string; testId: string }>>();
  for (const r of results) {
    const k = `${r.contestantId}|${r.testId}`;
    groups.set(k, [...(groups.get(k) ?? []), r]);
  }
  const out = new Map<string, string>();
  for (const [k, rs] of groups) {
    const { contestantId, testId } = rs[0]!;
    out.set(k, templateSummary({ unit: info.unitOf(testId), kind: info.kindOf(testId), results: rs, manual: info.manual?.(contestantId) }));
  }
  return out;
}
