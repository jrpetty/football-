/**
 * "Why this score?" — explains, in plain English, how one contestant's
 * category scores and Gauntlet Index were built from its recorded results.
 * Pure: reads only the leaderboard (the same numbers the table shows), so the
 * explanation can never disagree with the score.
 *
 * The maths it explains (src/engine/aggregate.ts):
 *   question score = mean over repeats (0–100)
 *   test score     = mean over its questions
 *   category score = weighted mean of its tests' scores
 *   Gauntlet Index = weighted mean of the category scores
 * Errors, skipped picture questions and answers still waiting for a human
 * grade are left out (never counted as 0); tests that were not run don't count.
 */
import type { CaseResult, Leaderboard, LeaderboardRow, ResultStatus } from './types.ts';

export type TestStatus = 'scored' | 'not-run' | 'no-score';

export interface TestLine {
  id: string;
  name: string;
  /** 0–100, or null when not run / nothing scorable. */
  score: number | null;
  weight: number;
  status: TestStatus;
  /** Scored answers (questions × repeats). */
  answers: number;
  /** Answers that passed (when the test has a pass mark). */
  passed: number | null;
  errors: number;
  skipped: number;
  pendingHuman: number;
  /** The recorded one-line summary of the median answer, when there is one. */
  summary?: string;
  /** Random guessing on this test (0–100), when the baseline ran it. */
  baseline: number | null;
  /** Plain-English notes about this line. */
  notes: string[];
}

export interface CategoryLine {
  id: string;
  name: string;
  color: string;
  weight: number;
  /** 0–100, or null when no test in it was scored. */
  score: number | null;
  tests: TestLine[];
  /** e.g. "(80 + 44) ÷ 2 = 62" */
  formula: string | null;
  /** One-paragraph reason, plus extra bullet points. */
  headline: string;
  points: string[];
  /** Random guessing in this category (0–100). */
  baseline: number | null;
  /** Best real model in this category (0–100) and who. */
  best: { label: string; score: number } | null;
}

export interface ScoreExplanation {
  contestantId: string;
  label: string;
  isBaseline: boolean;
  index: number | null;
  indexFormula: string | null;
  headline: string;
  points: string[];
  categories: CategoryLine[];
  baselineIndex: number | null;
}

const fmt = (v: number) => (Math.round(v * 10) / 10).toFixed(Math.abs(v - Math.round(v)) < 0.05 ? 0 : 1);
const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** Same rule as the leaderboard: the random-baseline contestant is a reference, not a competitor. */
export function isBaselineRow(row: Pick<LeaderboardRow, 'contestantId' | 'vendor' | 'label'>): boolean {
  return /(^|[-_.])(random|baseline)([-_.]|$)/i.test(row.contestantId) || /^baseline$/i.test(row.vendor ?? '') || /random baseline/i.test(row.label ?? '');
}

/** "(a + b) ÷ 2 = c", or the weighted version when weights differ. Null for fewer than one value. */
export function averageFormula(parts: Array<{ value: number; weight: number }>, result: number): string | null {
  if (parts.length === 0) return null;
  if (parts.length === 1) return `${fmt(parts[0]!.value)} (the only one, so it is the score)`;
  const equal = parts.every((p) => p.weight === parts[0]!.weight);
  if (equal) return `(${parts.map((p) => fmt(p.value)).join(' + ')}) ÷ ${parts.length} = ${fmt(result)}`;
  const w = parts.reduce((s, p) => s + p.weight, 0);
  return `(${parts.map((p) => `${fmt(p.value)}×${fmt(p.weight)}`).join(' + ')}) ÷ ${fmt(w)} = ${fmt(result)}`;
}

function bandWord(score: number): string {
  if (score >= 90) return 'near-perfect';
  if (score >= 70) return 'strong';
  if (score >= 45) return 'middling';
  if (score >= 20) return 'weak';
  return 'very low';
}

export function explainScore(lb: Leaderboard, contestantId: string): ScoreExplanation | null {
  const row = lb.rows.find((r) => r.contestantId === contestantId);
  if (!row) return null;
  const baselineRow = lb.rows.find((r) => isBaselineRow(r));
  const isBaseline = isBaselineRow(row);
  const real = lb.rows.filter((r) => !isBaselineRow(r));

  const categories: CategoryLine[] = [];
  for (const cat of lb.categories) {
    const defs = lb.tests.filter((t) => t.category === cat.id);
    if (defs.length === 0) continue;
    const tests: TestLine[] = defs.map((t) => {
      const agg = row.tests[t.id];
      const base = baselineRow?.tests[t.id]?.score;
      const notes: string[] = [];
      const errors = agg?.errors ?? 0;
      const skipped = agg?.skipped ?? 0;
      const pendingHuman = agg?.pendingHuman ?? 0;
      let status: TestStatus = 'scored';
      if (!agg) {
        status = 'not-run';
        notes.push('Not run for this model, so it does not count (it is not a 0).');
      } else if (agg.score === null) {
        status = 'no-score';
        if (pendingHuman > 0) notes.push(`${plural(pendingHuman, 'answer')} waiting for your grade — not counted until you mark ${pendingHuman === 1 ? 'it' : 'them'}.`);
        else if (skipped > 0) notes.push('Skipped: this model can’t take this kind of question (pictures in or out), so it is left out, not scored 0.');
        else if (errors > 0) notes.push(`Every answer failed with an error (${plural(errors, 'error')}), so nothing could be marked. Errors are left out, not scored 0 — re-run to fill it in.`);
        else notes.push('No answer could be marked yet.');
      } else {
        if (errors > 0) notes.push(`${plural(errors, 'answer')} failed with an error and ${errors === 1 ? 'was' : 'were'} left out of the average (not counted as 0).`);
        if (skipped > 0) notes.push(`${plural(skipped, 'question')} skipped — the model can’t handle that kind of picture — left out, not 0.`);
        if (pendingHuman > 0) notes.push(`${plural(pendingHuman, 'answer')} still waiting for your grade; the score will change once you mark ${pendingHuman === 1 ? 'it' : 'them'}.`);
      }
      const answers = agg?.n ?? 0;
      return {
        id: t.id,
        name: t.name,
        score: agg?.score == null ? null : agg.score * 100,
        weight: t.weight,
        status,
        answers,
        passed: agg?.passRate == null ? null : Math.round(agg.passRate * answers),
        errors,
        skipped,
        pendingHuman,
        ...(agg?.summary ? { summary: agg.summary } : {}),
        baseline: typeof base === 'number' ? base * 100 : null,
        notes,
      };
    });

    const raw = row.categoryScores?.[cat.id];
    const score = typeof raw === 'number' ? raw * 100 : null;
    const counted = tests.filter((t) => t.score !== null);
    const formula = score === null ? null : averageFormula(counted.map((t) => ({ value: t.score!, weight: t.weight })), score);
    const baseRaw = baselineRow?.categoryScores?.[cat.id];
    let best: CategoryLine['best'] = null;
    for (const r of real) {
      const v = r.categoryScores?.[cat.id];
      if (typeof v === 'number' && (!best || v * 100 > best.score)) best = { label: r.label, score: v * 100 };
    }

    const points: string[] = [];
    let headline: string;
    if (score === null) {
      headline = `No ${cat.name} score: none of its tests produced a markable answer for ${row.label}, so this category is left out of the Gauntlet Index.`;
    } else {
      const parts = counted.map((t) => `${t.name} ${fmt(t.score!)}`).join(', ');
      headline =
        counted.length === 1
          ? `${cat.name} has one test run here — ${parts} — so that is the category score.`
          : `${cat.name} is the average of its ${counted.length} tests: ${parts}.`;
      if (counted.length > 1) {
        const sorted = [...counted].sort((a, b) => b.score! - a.score!);
        const hi = sorted[0]!;
        const lo = sorted[sorted.length - 1]!;
        if (hi.score! - lo.score! >= 15) points.push(`Strongest: ${hi.name} (${fmt(hi.score!)}). Weakest: ${lo.name} (${fmt(lo.score!)}) — that is what pulled the score ${score >= 50 ? 'down' : 'this low'}.`);
      }
      if (counted.length > 1 && !counted.every((t) => t.weight === counted[0]!.weight)) points.push('Some tests carry more weight than others (shown as ×), so they move the average more.');
      if (!isBaseline && typeof baseRaw === 'number') {
        const b = baseRaw * 100;
        const gap = score - b;
        points.push(
          gap > 5
            ? `Random guessing scored ${fmt(b)} here, so ${row.label} beat pure luck by ${fmt(gap)} points.`
            : `Random guessing scored ${fmt(b)} here — ${row.label} did ${gap < -0.05 ? 'worse than' : 'no better than'} luck in this category.`,
        );
      }
      if (!isBaseline && best && best.label !== row.label && best.score - score >= 1) points.push(`Best in this category: ${best.label} with ${fmt(best.score)}.`);
      if (!isBaseline && best && best.label === row.label && real.length > 1) points.push(`This is the top ${cat.name} score on the board.`);
    }
    const notRun = tests.filter((t) => t.status === 'not-run').length;
    if (notRun > 0 && score !== null) points.push(`${plural(notRun, 'test')} in this category ${notRun === 1 ? 'was' : 'were'} not run for this model and ${notRun === 1 ? 'does' : 'do'} not count.`);
    const leftOut = tests.reduce((s, t) => s + t.errors + t.skipped + t.pendingHuman, 0);
    if (leftOut > 0) points.push(`${plural(leftOut, 'answer')} left out of the average (errors, skipped or waiting for a grade) — see the notes under each test.`);

    categories.push({
      id: cat.id,
      name: cat.name,
      color: cat.color,
      weight: lb.categoryWeights?.[cat.id] ?? cat.weight ?? 1,
      score,
      tests,
      formula,
      headline,
      points,
      baseline: typeof baseRaw === 'number' ? baseRaw * 100 : null,
      best,
    });
  }

  const scoredCats = categories.filter((c) => c.score !== null);
  const index = row.index;
  const indexFormula = index === null ? null : averageFormula(scoredCats.map((c) => ({ value: c.score!, weight: c.weight })), index);
  const points: string[] = [];
  let headline: string;
  if (index === null) {
    headline = `${row.label} has no Gauntlet Index yet: none of its answers could be marked.`;
  } else if (isBaseline) {
    headline = `This is not an AI. The Random Baseline answers every question by guessing, so ${fmt(index)} is what pure luck scores on these tests. Every real model should be well above it.`;
  } else {
    headline = `${row.label}’s Gauntlet Index of ${fmt(index)} is the average of its ${plural(scoredCats.length, 'category score')} (${bandWord(index)} overall).`;
    if (scoredCats.length > 1) {
      const sorted = [...scoredCats].sort((a, b) => b.score! - a.score!);
      points.push(`Best category: ${sorted[0]!.name} (${fmt(sorted[0]!.score!)}). Weakest: ${sorted[sorted.length - 1]!.name} (${fmt(sorted[sorted.length - 1]!.score!)}).`);
    }
    if (typeof baselineRow?.index === 'number') points.push(`Random guessing scored ${fmt(baselineRow.index)} overall, so this model is ${fmt(index - baselineRow.index)} points above pure luck.`);
  }
  const missing = categories.filter((c) => c.score === null).length;
  if (missing > 0 && index !== null) points.push(`${plural(missing, 'category', 'categories')} had no markable answers and ${missing === 1 ? 'is' : 'are'} left out — the Index only averages what was actually scored.`);
  if (index !== null && row.indexCi95) points.push(`Margin of luck: with these questions the Index could reasonably sit anywhere from ${fmt(row.indexCi95[0])} to ${fmt(row.indexCi95[1])}. Two models closer than that are effectively tied.`);

  return {
    contestantId: row.contestantId,
    label: row.label,
    isBaseline,
    index,
    indexFormula,
    headline,
    points,
    categories,
    baselineIndex: typeof baselineRow?.index === 'number' ? baselineRow.index : null,
  };
}

/** How the Random Baseline answers, per kind of test — shown wherever it appears. */
export const RANDOM_BASELINE_EXPLAINER = {
  short: 'Not an AI — a built-in “contestant” that answers every question by random guessing. Free, no key needed, never ranked. It shows what pure luck scores, so you can tell a real model is actually thinking.',
  how: [
    'Multiple-choice questions: picks a random letter.',
    'Number questions: picks a random number from 0 to 100.',
    'Games and simulations: picks a random move from the ones on offer each turn.',
    'Coding: hands in an empty function that does nothing.',
    'Writing, art and data tasks: random filler words, a grey circle, or an empty answer.',
  ],
  why: [
    'It is the floor. A model that scores close to it is doing no better than luck on that test.',
    'On most tests it scores 0 or close to 0. On multiple-choice or some games it can pick up a few points by luck alone — that is exactly the luck a real model must clearly beat.',
    'Its answers are the same every time (the “random” picks are fixed by the question), so it is a fair, repeatable reference line.',
    'It is never shown as the leader and never costs anything.',
  ],
} as const;

// ─────────────────────────────────────────────────────────────────────────────
// Question by question: every answer behind one test score
// ─────────────────────────────────────────────────────────────────────────────

/** One recorded answer, trimmed to what the drill-down shows (served by GET /api/score-answers). */
export interface ScoreAnswer {
  key: string;
  runId: string;
  contestantId: string;
  caseId: string;
  repeat: number;
  status: ResultStatus;
  /** 0..1 */
  score: number | null;
  passed: boolean | null;
  summary: string;
  error?: string;
  /** The model's extracted final answer, when short enough to show. */
  modelSaid?: string;
  /** The answer key, when short enough to show. */
  correct?: string;
  /** Machine checks passed, e.g. hidden unit tests or brief items. */
  checks?: { passed: number; total: number };
  /** Marked (or re-marked) by a person. */
  humanGraded?: boolean;
  /** Fair-play check: 'cheating' scored 0; 'flagged' kept its score and waits for a person to look. */
  fairPlay?: { verdict: 'cheating' | 'flagged'; rule: string; excerpt: string };
}

const SHOWABLE = 140;
function short(v: unknown): string | undefined {
  if (v === null || v === undefined) return undefined;
  const t = typeof v === 'string' ? v : typeof v === 'number' || typeof v === 'boolean' ? String(v) : Array.isArray(v) && v.every((x) => typeof x !== 'object') ? v.join(', ') : undefined;
  if (t === undefined) return undefined;
  const one = t.replace(/\s+/g, ' ').trim();
  if (!one || one.length > SHOWABLE) return undefined;
  return one;
}

export function toScoreAnswer(r: Pick<CaseResult, 'key' | 'runId' | 'contestantId' | 'caseId' | 'repeat' | 'status' | 'score' | 'passed' | 'summary' | 'error' | 'scoreDetail' | 'humanScores'>): ScoreAnswer {
  const d = r.scoreDetail ?? {};
  const items = Array.isArray(d.items) ? d.items : [];
  const modelSaid = short(d.extracted);
  const correct = short(d.expected);
  return {
    key: r.key,
    runId: r.runId,
    contestantId: r.contestantId,
    caseId: r.caseId,
    repeat: r.repeat,
    status: r.status,
    score: r.score,
    passed: r.passed,
    summary: r.summary ?? '',
    ...(r.error ? { error: r.error.slice(0, 200) } : {}),
    ...(modelSaid !== undefined ? { modelSaid } : {}),
    ...(correct !== undefined ? { correct } : {}),
    ...(items.length ? { checks: { passed: items.filter((i) => i.passed).length, total: items.length } } : {}),
    ...(r.humanScores?.length ? { humanGraded: true } : {}),
    ...(d.integrity ? { fairPlay: d.integrity } : {}),
  };
}

/** Same rule as the leaderboard (src/engine/aggregate.ts isScored). */
export function answerCounts(a: Pick<ScoreAnswer, 'score' | 'status'>): boolean {
  return a.score !== null && a.status !== 'error' && a.status !== 'cancelled' && a.status !== 'pending-human';
}

export type Verdict = 'right' | 'partly' | 'wrong' | 'out';

export interface TryLine extends ScoreAnswer {
  counted: boolean;
  /** 0–100, or null. */
  score100: number | null;
  verdict: Verdict;
  /** Plain-English reason for this answer's mark. */
  why: string;
}

export interface QuestionLine {
  caseId: string;
  /** 0–100 average over the tries that count, or null. */
  score: number | null;
  verdict: Verdict;
  tries: TryLine[];
  /** e.g. "Asked 3 times: 100, 100, 0 → 66.7" (only with more than one try). */
  triesSum: string | null;
}

export interface QuestionBreakdown {
  questions: QuestionLine[];
  /** 0–100: the test score, the average of the question scores. */
  score: number | null;
  formula: string | null;
  counted: number;
  leftOut: number;
  right: number;
  partly: number;
  wrong: number;
}

/** Same cut-offs as the score bands everywhere else (80+ mostly right, 40–79 partly, under 40 mostly wrong). */
export function verdictOf(score100: number | null): Verdict {
  if (score100 === null) return 'out';
  if (score100 >= 80) return 'right';
  if (score100 >= 40) return 'partly';
  return 'wrong';
}

function whyFor(a: ScoreAnswer, counted: boolean, score100: number | null): string {
  if (a.status === 'pending-human') return 'Waiting for your grade — not counted until you mark it.';
  if (a.status === 'error') return `Failed with an error${a.error ? ` (${a.error.replace(/\s+/g, ' ').slice(0, 120)})` : ''}, so it could not be marked. Left out — not counted as 0.`;
  if (a.status === 'cancelled') return 'Cancelled before it finished. Left out — not counted as 0.';
  if (a.status === 'skipped') return a.summary || 'Skipped: this model can’t take this kind of question. Left out — not counted as 0.';
  if (!counted) return 'No mark recorded. Left out — not counted as 0.';
  if (a.fairPlay?.verdict === 'cheating') {
    return `Scored 0 for breaking the fair-play rules: the reply ${a.fairPlay.rule} (“${a.fairPlay.excerpt}”). Every model is told these rules at the start of every prompt.`;
  }
  const pts = fmt(score100!);
  const parts: string[] = [];
  if (a.status === 'refusal') parts.push(`The model refused to answer, which scores ${pts}.`);
  else if (a.status === 'timeout') parts.push(`Ran out of time, which scores ${pts}.`);
  if (a.modelSaid !== undefined && a.correct !== undefined) {
    const same = a.modelSaid.trim().toLowerCase() === a.correct.trim().toLowerCase();
    const tail = score100! >= 99.95 ? ' — right' : score100! <= 0.05 ? ' — wrong' : same ? ' — right answer, but marks were lost (for example on the answer format)' : ' — part marks';
    parts.push(`Answered “${a.modelSaid}”; the correct answer is “${a.correct}”${tail}.`);
  } else if (a.correct !== undefined && a.status === 'ok' && a.modelSaid === undefined) {
    parts.push(`No final answer could be found in the reply; the correct answer is “${a.correct}”.`);
  }
  if (a.checks) parts.push(`Passed ${a.checks.passed} of ${a.checks.total} checks.`);
  const bareVerdict = /^(correct|incorrect|right|wrong|pass(ed)?|fail(ed)?)\.?$/i.test(a.summary.trim());
  if (a.summary && !(bareVerdict && parts.length > 0) && !parts.some((p) => p.includes(a.summary))) parts.push(a.summary.endsWith('.') ? a.summary : `${a.summary}.`);
  if (a.humanGraded) parts.push('Marked by a person.');
  if (a.fairPlay?.verdict === 'flagged') parts.push(`Flagged: ${a.fairPlay.rule} (“${a.fairPlay.excerpt}”). The mark stands; the answer is listed in Blind Review for a person to check.`);
  if (parts.length === 0) parts.push(`Marked ${pts} out of 100.`);
  return parts.join(' ');
}

const natural = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });

/** Group a test's answers by question, mark each in plain English, and show the sum that makes the test score. */
export function questionBreakdown(answers: ScoreAnswer[]): QuestionBreakdown {
  const byCase = new Map<string, ScoreAnswer[]>();
  for (const a of answers) byCase.set(a.caseId, [...(byCase.get(a.caseId) ?? []), a]);
  const questions: QuestionLine[] = [...byCase.entries()]
    .sort(([x], [y]) => natural.compare(x, y))
    .map(([caseId, list]) => {
      const tries: TryLine[] = list
        .slice()
        .sort((x, y) => x.repeat - y.repeat)
        .map((a) => {
          const counted = answerCounts(a);
          const score100 = counted ? a.score! * 100 : null;
          return { ...a, counted, score100, verdict: verdictOf(score100), why: whyFor(a, counted, score100) };
        });
      const used = tries.filter((t) => t.counted);
      const score = used.length ? used.reduce((s, t) => s + t.score100!, 0) / used.length : null;
      const triesSum = tries.length > 1 ? `Asked ${tries.length} times: ${tries.map((t) => (t.counted ? fmt(t.score100!) : 'left out')).join(', ')}${score === null ? '' : ` → average ${fmt(score)}`}` : null;
      return { caseId, score, verdict: verdictOf(score), tries, triesSum };
    });
  const scored = questions.filter((q) => q.score !== null);
  const score = scored.length ? scored.reduce((s, q) => s + q.score!, 0) / scored.length : null;
  let formula: string | null = null;
  if (score !== null) {
    formula =
      scored.length <= 12
        ? averageFormula(scored.map((q) => ({ value: q.score!, weight: 1 })), score)
        : `${fmt(scored.reduce((s, q) => s + q.score!, 0))} (all ${scored.length} question scores added up) ÷ ${scored.length} = ${fmt(score)}`;
  }
  return {
    questions,
    score,
    formula,
    counted: scored.length,
    leftOut: questions.length - scored.length,
    right: scored.filter((q) => q.verdict === 'right').length,
    partly: scored.filter((q) => q.verdict === 'partly').length,
    wrong: scored.filter((q) => q.verdict === 'wrong').length,
  };
}
