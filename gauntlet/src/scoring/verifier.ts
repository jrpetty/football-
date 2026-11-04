/**
 * The generic verifier scorer: `{ "type": "verifier", "verifier": "<name>" }`.
 *
 * The case's `expected` is a VerifierKey: the structured world (never sent to the model), the solver's proven
 * optimum (plan verifiers) and one reference answer. Marking, in order:
 *
 *  1. The answer is read from the reply: every JSON object in it (in or out of a code fence; trailing commas and
 *     // comments tolerated; one wrapper such as {"answer": {...}} unwrapped) that has one of the verifier's answer
 *     keys. Objects copied from the prompt's own format example are ignored.
 *  2. No answer → 0. Two different answers in one reply, or a key offering an alternative ("alternative",
 *     "plan_b", "backup", "option2") → 0: hedging is marked wrong.
 *  3. The verifier checks the answer against the world, rule by rule (src/scoring/verifiers/<name>.ts).
 *     - Plan verifiers: a broken rule → 0 (the plan would not work). A workable plan as good as the proven
 *       optimum → 1. A workable plan that is worse scores SUBOPTIMAL_MAX × (best ÷ yours)² for costs (or
 *       × (yours ÷ best)² for values): always under 0.5, so only an optimal plan scores 100, while a plan 1% off
 *       (0.49) still visibly beats one that costs twice as much (0.125). When the answer must quote its total, a
 *       missing or wrong total halves the score.
 *     - Answer verifiers: the score is the share of checks passed (field by field, pair by pair).
 *
 * The verifier's source is part of the test hash (registry.ts), so changing a checker changes every test using it.
 */
import type { ScoreDetail } from '../core/types.ts';
import { extractFinalAnswer } from '../core/extract.ts';
import { VERIFIERS } from './verifiers/index.ts';
import type { PlanVerifier, Verifier, VerifierItem, VerifierKey } from './verifiers/types.ts';
import { normKey } from './verifiers/common.ts';

/** Most a workable but non-optimal plan can score. */
export const SUBOPTIMAL_MAX = 0.5;

/** The published partial-credit formula for a workable plan that is not optimal. */
export function partialCredit(objective: 'min' | 'max', optimum: number, value: number): number {
  if (objective === 'min') {
    if (value <= optimum) return 1;
    if (optimum <= 0) return 0;
    return round4(SUBOPTIMAL_MAX * (optimum / value) ** 2);
  }
  if (value >= optimum) return 1;
  if (value <= 0 || optimum <= 0) return 0;
  return round4(SUBOPTIMAL_MAX * (value / optimum) ** 2);
}

function round4(n: number): number {
  return Math.round(n * 10000) / 10000;
}

export interface VerifierOutcome {
  score: number;
  passed: boolean;
  summary: string;
  detail: ScoreDetail;
}

/** End index (inclusive) of the bracketed value opening at `start`, ignoring brackets inside strings; -1 if unbalanced. */
function balancedEnd(text: string, start: number): number {
  const stack: string[] = [];
  let inString = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i]!;
    if (inString) {
      if (ch === '\\') i++;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === '{' || ch === '[') stack.push(ch === '{' ? '}' : ']');
    else if (ch === '}' || ch === ']') {
      if (stack.pop() !== ch) return -1;
      if (stack.length === 0) return i;
    }
  }
  return -1;
}

/** JSON.parse, then once more without // comments and trailing commas (common hand-written slips). */
function parseLenient(src: string): unknown {
  try {
    return JSON.parse(src);
  } catch {
    /* try the tidied text */
  }
  const tidy = src
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/,\s*\/\/[^\n"]*$/gm, ',')
    .replace(/,(\s*[}\]])/g, '$1');
  try {
    return JSON.parse(tidy);
  } catch {
    return undefined;
  }
}

/** Every top-level JSON object in a text, in order. */
export function jsonObjectsIn(text: string): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = [];
  if (text.length > 400_000) return out;
  const whole = parseLenient(text.trim().replace(/^```[\w-]*\s*\n?|\n?```\s*$/g, ''));
  if (whole && typeof whole === 'object' && !Array.isArray(whole)) return [whole as Record<string, unknown>];
  let attempts = 0;
  for (let i = 0; i < text.length && attempts < 2000; i++) {
    if (text[i] !== '{') continue;
    attempts++;
    const end = balancedEnd(text, i);
    if (end < 0) continue;
    const v = parseLenient(text.slice(i, end + 1));
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      out.push(v as Record<string, unknown>);
      i = end;
    }
  }
  return out;
}

const WRAPPERS = new Set(['answer', 'result', 'plan', 'final', 'finalanswer', 'solution', 'output']);
const HEDGE_KEYS = new Set(['alternative', 'alternatives', 'alt', 'alternativeplan', 'option2', 'optionb', 'planb', 'backup', 'backupplan', 'otheroption', 'or', 'secondoption']);

function hasAnswerKey(o: Record<string, unknown>, keys: string[]): boolean {
  const want = new Set(keys.map(normKey));
  return Object.keys(o).some((k) => want.has(normKey(k)));
}

/** Unwrap {"answer": {...}} (one wrapper key, or a wrapper next to notes) when the inner object is the answer. */
function unwrap(o: Record<string, unknown>, keys: string[]): Record<string, unknown> {
  if (hasAnswerKey(o, keys)) return o;
  for (const [k, v] of Object.entries(o)) {
    if (WRAPPERS.has(normKey(k)) && v && typeof v === 'object' && !Array.isArray(v) && hasAnswerKey(v as Record<string, unknown>, keys)) return v as Record<string, unknown>;
  }
  return o;
}

/** Canonical form for comparing two answers: keys normalised and sorted, strings trimmed and lower-cased. */
function canon(v: unknown): string {
  const walk = (x: unknown): unknown => {
    if (Array.isArray(x)) return x.map(walk);
    if (x && typeof x === 'object') {
      const out: Record<string, unknown> = {};
      for (const k of Object.keys(x as object).sort((a, b) => normKey(a).localeCompare(normKey(b)))) out[normKey(k)] = walk((x as Record<string, unknown>)[k]);
      return out;
    }
    return typeof x === 'string' ? x.trim().toLowerCase().replace(/\s+/g, ' ') : x;
  };
  return JSON.stringify(walk(v));
}

/** The answer objects of a reply: answer-shaped, prompt examples left out, duplicates merged. */
export function answerCandidates(response: string, keys: string[], taskText = ''): Record<string, unknown>[] {
  const examples = new Set(jsonObjectsIn(taskText).map((o) => canon(unwrap(o, keys))));
  const seen = new Set<string>();
  const out: Record<string, unknown>[] = [];
  for (const raw of jsonObjectsIn(response)) {
    const o = unwrap(raw, keys);
    if (!hasAnswerKey(o, keys)) continue;
    const c = canon(o);
    if (examples.has(c) || seen.has(c)) continue;
    seen.add(c);
    out.push(o);
  }
  return out;
}

function firstFailures(items: VerifierItem[], onlyHard: boolean, n = 2): string {
  const failed = items.filter((i) => !i.passed && (!onlyHard || i.hard));
  const shown = failed.slice(0, n).map((i) => (i.detail ? `${i.label} (${i.detail})` : i.label));
  return shown.join('; ') + (failed.length > n ? ` (+${failed.length - n} more)` : '');
}

export function verifierFor(name: string): Verifier | undefined {
  return Object.prototype.hasOwnProperty.call(VERIFIERS, name) ? VERIFIERS[name] : undefined;
}

/** Mark one reply with the named verifier. Never throws on a bad reply. */
export function scoreVerifier(name: string, expected: unknown, response: string, taskText = ''): VerifierOutcome {
  const v = verifierFor(name);
  if (!v) throw new Error(`Unknown verifier "${name}"`);
  const key = (expected ?? {}) as VerifierKey;
  const formatOk = /```/.test(response) || /^\s*\{/.test(response);
  const cands = answerCandidates(response, v.answerKeys, taskText);
  if (cands.length === 0) {
    const fa = extractFinalAnswer(response);
    return { score: 0, passed: false, summary: `No ${v.noun} found (the answer must be the JSON asked for)`, detail: { formatOk: false, verdict: 'no-answer', extracted: fa.formatOk ? fa.answer.slice(0, 200) : undefined } };
  }
  if (cands.length > 1) {
    return {
      score: 0,
      passed: false,
      summary: `${cands.length} different ${v.noun}s in one reply (giving alternatives is marked wrong)`,
      detail: { formatOk, hedged: true, verdict: 'hedged', extracted: JSON.stringify(cands).slice(0, 4000) },
    };
  }
  const answer = cands[0]!;
  const extracted = JSON.stringify(answer).slice(0, 4000);
  const hedgeKey = Object.keys(answer).find((k) => HEDGE_KEYS.has(normKey(k)));
  if (hedgeKey) {
    return { score: 0, passed: false, summary: `The answer offers an alternative ("${hedgeKey}"), which is marked wrong`, detail: { formatOk, hedged: true, verdict: 'hedged', extracted } };
  }
  if (v.kind === 'answer') {
    let items: VerifierItem[];
    try {
      items = v.check(key.world as never, answer).items;
    } catch (err) {
      items = [{ label: 'answer could be read', passed: false, detail: (err as Error).message.slice(0, 120) }];
    }
    const passedN = items.filter((i) => i.passed).length;
    const score = items.length ? round4(passedN / items.length) : 0;
    const all = items.length > 0 && passedN === items.length;
    return {
      score,
      passed: all,
      summary: all ? `All ${items.length} checks right` : `${passedN}/${items.length} checks right · ${firstFailures(items, false)}`,
      detail: { formatOk, items, extracted, verdict: all ? 'correct' : 'partly' },
    };
  }
  return scorePlan(v, key, answer, extracted, formatOk);
}

function scorePlan(v: PlanVerifier, key: VerifierKey, answer: Record<string, unknown>, extracted: string, formatOk: boolean): VerifierOutcome {
  let r;
  try {
    r = v.check(key.world as never, answer);
  } catch (err) {
    r = { feasible: false, items: [{ label: `${v.noun} could be read`, passed: false, hard: true, detail: (err as Error).message.slice(0, 120) }] };
  }
  const items = r.items.slice();
  const optimum = Number(key.optimum);
  const base = { formatOk, extracted, optimum, value: r.value, stated: r.stated ?? null };
  if (!r.feasible || r.value === undefined) {
    return { score: 0, passed: false, summary: `Not a workable ${v.noun}: ${firstFailures(items, true) || 'it could not be checked'}`, detail: { ...base, items, verdict: 'infeasible' } };
  }
  const better = v.objective === 'min' ? r.value < optimum : r.value > optimum;
  const optimal = r.value === optimum || better;
  items.push({ label: `${v.objective === 'min' ? 'as cheap as' : 'as good as'} the best possible (${v.formatValue(optimum)})`, passed: optimal, detail: optimal ? undefined : `this ${v.noun}: ${v.formatValue(r.value)}` });
  let score = optimal ? 1 : partialCredit(v.objective, optimum, r.value);
  let totalNote = '';
  if (v.quotesTotal) {
    const ok = r.stated !== null && r.stated !== undefined && r.stated === r.value;
    items.push({ label: 'quoted total is what it really comes to', passed: ok, detail: ok ? undefined : r.stated === null || r.stated === undefined ? 'no total quoted' : `quoted ${v.formatValue(r.stated)}, really ${v.formatValue(r.value)}` });
    if (!ok) {
      score = round4(score / 2);
      totalNote = r.stated === null || r.stated === undefined ? ' · no total quoted (halved)' : ` · quoted total ${v.formatValue(r.stated)} is wrong (halved)`;
    }
  }
  const pct = optimum ? Math.abs((r.value - optimum) / optimum) * 100 : 0;
  const head = optimal
    ? `Optimal ${v.noun}: ${v.formatValue(r.value)}, the best possible${better ? ' (better than the proven optimum: please report)' : ''}`
    : `Workable ${v.noun}, but ${v.formatValue(r.value)} against the best ${v.formatValue(optimum)} (${v.objective === 'min' ? '+' : '−'}${pct.toFixed(1)}%) · partial credit`;
  return {
    score,
    passed: optimal && score === 1,
    summary: head + totalNote,
    detail: { ...base, items, verdict: optimal ? 'optimal' : 'suboptimal', ...(better ? { notes: 'This plan beats the solver’s proven optimum: the answer key needs checking.' } : {}) },
  };
}
