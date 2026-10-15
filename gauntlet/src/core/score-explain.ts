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
import type { Leaderboard, LeaderboardRow } from './types.ts';

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
