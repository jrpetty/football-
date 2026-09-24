/**
 * "Can It Be Fooled?" highlights for the Presenter (and its 1080×1920 Shorts
 * variant). Pure functions with no Node or DOM dependencies, shared by the UI
 * and the unit tests.
 *
 * For every trick case we work out, per model, whether it got the answer
 * right (majority of its repeats), fell for the trap or ran out of time, and
 * the one-line answer it actually gave. The cases where the models disagree
 * the most make the best slides, so those are chosen.
 */
import type { PromptTest, PromptTestCase, ResultStatus, TestDefinition } from '../core/types.ts';

export type TrickVerdict = 'correct' | 'fooled' | 'out-of-time' | 'no-answer';

export interface TrickContender {
  id: string;
  label: string;
  color: string;
  /** Random Baseline: shown on slides but never counted as a model that disagrees. */
  baseline?: boolean;
}

/** The subset of a result row the highlights need (CaseResultLite fits). */
export interface TrickResultLike {
  contestantId: string;
  testId: string;
  caseId: string;
  status: ResultStatus;
  score: number | null;
  scoreDetail?: { extracted?: string; responseMs?: unknown; oneLine?: unknown; [k: string]: unknown };
  metrics?: { wallMs?: number };
}

export interface TrickModelLine {
  id: string;
  label: string;
  color: string;
  baseline: boolean;
  verdict: TrickVerdict;
  /** The answer the model gave most often (one line), or '' when it never answered. */
  answer: string;
  /** Why a right-looking answer still scored zero (e.g. it broke the one-line rule). */
  note?: string;
  /** Scored attempts (repeats) and how many were correct. */
  correct: number;
  attempts: number;
  /** Median response time over its attempts (ms), or null. */
  responseMs: number | null;
  /** True when its answer is the tempting wrong answer. */
  tookBait: boolean;
}

export interface TrickHighlight {
  testId: string;
  testName: string;
  caseId: string;
  /** The question as a viewer should read it (answer-format boilerplate removed). */
  question: string;
  correct: string;
  lure: string | null;
  answerWithinSec: number | null;
  models: TrickModelLine[];
  /** Models (excluding the baseline) that fell for it or ran out of time. */
  fooled: number;
  /** 0..1: mean pairwise difference of the models' success rates (1 = split down the middle). */
  disagreement: number;
  /** A house rule the viewer needs to judge the answers (e.g. the FALSE PREMISE option), or null. */
  rule: string | null;
}

const BOILERPLATE = [
  /Give exactly one answer\.?/gi,
  /If you give more than one answer, it will be marked wrong\.?/gi,
  /Give the answer as [^.\n]*\.?/gi,
  /Answer with (?:the letter|a whole number|a number|a year|the number)[^.\n]*\.?/gi,
];

/** A case prompt as a viewer should read it: answer-format boilerplate removed, whitespace tidied. */
export function displayQuestion(prompt: string): string {
  let q = prompt;
  for (const re of BOILERPLATE) q = q.replace(re, '');
  return q
    .split('\n')
    .map((l) => l.replace(/[ \t]+/g, ' ').trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** The correct answer written for people: displayAnswer, else the expected value. */
export function displayCorrect(test: PromptTest, c: PromptTestCase): string {
  if (c.displayAnswer) return c.displayAnswer;
  const e = c.expected;
  if (Array.isArray(e)) return String(e[0] ?? '');
  if (typeof e === 'string' || typeof e === 'number') return String(e);
  return test.scorer.type === 'regex' ? '(pattern)' : '—';
}

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9.]/g, '');

function median(xs: number[]): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
}

function mode(xs: string[]): string {
  const counts = new Map<string, { n: number; first: number }>();
  xs.forEach((x, i) => {
    const k = norm(x) || x;
    const c = counts.get(k);
    if (c) c.n++;
    else counts.set(k, { n: 1, first: i });
  });
  let best: { k: string; n: number; first: number } | null = null;
  for (const [k, v] of counts) if (!best || v.n > best.n || (v.n === best.n && v.first < best.first)) best = { k, ...v };
  return best ? xs[best.first]! : '';
}

function oneLine(s: string, max = 80): string {
  const t = s.replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max - 1)}…` : t;
}

/** Whether a stated answer is the tempting wrong answer ("5" matches "5 cents", "A" matches "(A) his mother"). */
export function matchesLure(answer: string, lure: string | null): boolean {
  if (!lure || !answer.trim()) return false;
  const num = (s: string) => {
    const m = s.replace(/,(?=\d{3})/g, '').match(/^[^0-9-]*?(-?\d+(?:\.\d+)?)/);
    return m ? Number(m[1]) : null;
  };
  const a = num(answer);
  const l = num(lure);
  if (a !== null && l !== null && /^\W*-?\d/.test(answer) && /^\W*-?\d/.test(lure)) return Math.abs(a - l) <= Math.max(0.05, Math.abs(l) * 0.01);
  const na = norm(answer);
  return na.length > 0 && norm(lure).startsWith(na);
}

/** Per-model verdict lines for one case. */
export function modelLines(results: TrickResultLike[], contenders: TrickContender[], lure: string | null): TrickModelLine[] {
  return contenders.map((c) => {
    const mine = results.filter((r) => r.contestantId === c.id && r.status !== 'cancelled' && r.status !== 'pending-human');
    const scored = mine.filter((r) => r.status === 'ok' || r.status === 'timeout' || r.status === 'refusal');
    const correct = scored.filter((r) => (r.score ?? 0) >= 0.999).length;
    const late = scored.filter((r) => r.status === 'timeout').length;
    const times = mine
      .map((r) => (typeof r.scoreDetail?.responseMs === 'number' ? r.scoreDetail.responseMs : r.metrics?.wallMs))
      .filter((x): x is number => typeof x === 'number' && Number.isFinite(x));
    let verdict: TrickVerdict;
    if (scored.length === 0) verdict = 'no-answer';
    else if (correct * 2 > scored.length) verdict = 'correct';
    else if (late * 2 > scored.length) verdict = 'out-of-time';
    else verdict = 'fooled';
    const pool = scored.filter((r) => ((r.score ?? 0) >= 0.999) === (verdict === 'correct') && r.status !== 'timeout');
    const answers = pool.map((r) => r.scoreDetail?.extracted).filter((x): x is string => typeof x === 'string' && x.trim().length > 0);
    const answer = verdict === 'out-of-time' ? '' : oneLine(mode(answers));
    const brokeFormat = verdict === 'fooled' && pool.some((r) => r.scoreDetail?.oneLine === false);
    return {
      id: c.id,
      label: c.label,
      color: c.color,
      baseline: !!c.baseline,
      verdict,
      answer,
      note: brokeFormat ? 'reply was not one line' : scored.some((r) => r.status === 'refusal') && !answer ? 'refused' : undefined,
      correct,
      attempts: scored.length,
      responseMs: median(times),
      tookBait: verdict === 'fooled' && matchesLure(answer, lure),
    };
  });
}

/** Mean pairwise |p_i - p_j| of success rates (0 = all agree, up to 1). */
export function disagreementOf(rates: number[]): number {
  if (rates.length < 2) return 0;
  let sum = 0;
  let n = 0;
  for (let i = 0; i < rates.length; i++)
    for (let j = i + 1; j < rates.length; j++) {
      sum += Math.abs(rates[i]! - rates[j]!);
      n++;
    }
  return sum / n;
}

/** The one rule a viewer must know to judge the answers. */
export function ruleFor(test: PromptTest, c: PromptTestCase): string | null {
  const parts: string[] = [];
  if (test.cases.some((x) => x.expected === 'FALSE PREMISE' || (Array.isArray(x.expected) && x.expected.includes('FALSE PREMISE'))))
    parts.push('Models were told to answer FALSE PREMISE when a question assumes something untrue');
  const sc = c.scorer ?? test.scorer;
  if (sc.type === 'regex' && sc.fullText) parts.push('one-line answers only');
  const within = c.answerWithinSec ?? test.answerWithinSec;
  if (within) parts.push(`${within}-second limit`);
  if (!parts.length) return null;
  const s = parts.join(' · ');
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function isTrickTest(def: TestDefinition | undefined | null): def is PromptTest {
  return !!def && def.kind === 'prompt' && (def.category === 'trick' || def.cases.some((c) => c.lure !== undefined));
}

/**
 * Pick the most divisive cases of each trick test: highest disagreement
 * between models first, then the most models fooled, then the shortest
 * question (best for a vertical Short). Cases nobody got wrong are skipped.
 */
export function selectTrickHighlights(opts: {
  tests: Array<{ definition: TestDefinition; caseIds?: string[] }>;
  results: TrickResultLike[];
  contenders: TrickContender[];
  perTest?: number;
}): TrickHighlight[] {
  const perTest = opts.perTest ?? 3;
  const out: TrickHighlight[] = [];
  for (const t of opts.tests) {
    const def = t.definition;
    if (!isTrickTest(def)) continue;
    const byCase = new Map<string, TrickResultLike[]>();
    for (const r of opts.results) if (r.testId === def.id) byCase.set(r.caseId, [...(byCase.get(r.caseId) ?? []), r]);
    const ranked: Array<TrickHighlight & { order: number }> = [];
    def.cases.forEach((c, order) => {
      if (t.caseIds && !t.caseIds.includes(c.id)) return;
      const rs = byCase.get(c.id);
      if (!rs?.length) return;
      const lure = c.lure ?? null;
      const models = modelLines(rs, opts.contenders, lure).filter((m) => m.attempts > 0 || !m.baseline);
      const real = models.filter((m) => !m.baseline && m.attempts > 0);
      if (!real.length) return;
      const fooled = real.filter((m) => m.verdict === 'fooled' || m.verdict === 'out-of-time').length;
      const disagreement = disagreementOf(real.map((m) => m.correct / m.attempts));
      if (fooled === 0 && disagreement === 0) return;
      ranked.push({
        testId: def.id,
        testName: def.name,
        caseId: c.id,
        question: displayQuestion(c.prompt ?? c.turns?.[c.turns.length - 1] ?? ''),
        correct: displayCorrect(def, c),
        lure,
        answerWithinSec: c.answerWithinSec ?? def.answerWithinSec ?? null,
        models,
        fooled,
        disagreement: Math.round(disagreement * 1000) / 1000,
        rule: ruleFor(def, c),
        order,
      });
    });
    ranked.sort((a, b) => b.disagreement - a.disagreement || b.fooled - a.fooled || a.question.length - b.question.length || a.order - b.order);
    for (const { order: _o, ...h } of ranked.slice(0, perTest)) out.push(h);
  }
  return out;
}
