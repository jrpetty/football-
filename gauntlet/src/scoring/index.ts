import type { ArtifactCheck, ArtifactKind, ArtifactRef, ChatImage, JudgeLabel, NormalizeMode, ScoreBreakdownItem, ScoreDetail, ScorerSpec, StopReason } from '../core/types.ts';
import { extractCodeBlock, extractCodeBlocks, extractFinalAnswer, extractTagged, normalize, parseAnswerNumber, parseJsonLoose, stripReasoning } from '../core/extract.ts';
import { checkConstraints } from './constraints.ts';
import { compareJson } from './json-compare.ts';
import { promptExampleArgs, runCodeTests, trivialSolutionReason, type CodeTest } from './code-sandbox.ts';
import { probeHtml, renderSvg } from './browser.ts';
import { scoreLadder } from './ladder.ts';
import { scoreGameJam } from './game-jam.ts';
import { JUDGE_ARTIFACT_TEMPLATE, JUDGE_CLASSIFY_TEMPLATE, JUDGE_RUBRIC_TEMPLATE, JUDGE_SYSTEM, fill } from './judge-prompts.ts';

export interface JudgeCall {
  judgeId: string;
  text: string;
  error?: string;
  /** True when this judge was sent the images of `JudgeAskOptions` (it accepts image input). */
  sawImages?: boolean;
}

/** Optional extras for one judge question. */
export interface JudgeAskOptions {
  /** Pictures shown to judges that accept image input (e.g. The Game Jam's playtest screenshots). */
  images?: ChatImage[];
  /** The question for judges without image input (defaults to the same text). */
  textOnlyUser?: string;
}

/** A panel of judge models. `ask` calls every judge in parallel. */
export interface JudgePanel {
  ids: string[];
  /** Pictures go as ask options, or as a plain list (program judges, e.g. the Gallery). */
  ask(system: string, user: string, label: string, opts?: JudgeAskOptions | ChatImage[]): Promise<JudgeCall[]>;
}

export interface ScoringInput {
  scorer: ScorerSpec;
  expected: unknown;
  /** Final reply text. */
  response: string;
  stopReason: StopReason;
  /** Set when a spend limit lowered the reply's output limit, so a cut-off reply can say who stopped it. */
  outputLimitBy?: 'spend-limit' | 'per-answer';
  /** The task as the model saw it (for judges). */
  taskText: string;
  judges: JudgePanel;
  saveArtifact(name: string, kind: ArtifactKind, content: string | Buffer): ArtifactRef;
  signal: AbortSignal;
}

export interface ScoringOutcome {
  score: number | null;
  passed: boolean | null;
  summary: string;
  detail: ScoreDetail;
  pendingHuman?: boolean;
}

const MAX_JUDGE_CHARS = 60_000;

function clip(text: string, max = MAX_JUDGE_CHARS): string {
  if (text.length <= max) return text;
  const half = Math.floor(max / 2);
  return `${text.slice(0, half)}\n\n[… ${text.length - max} characters omitted by the harness …]\n\n${text.slice(-half)}`;
}

function quote(s: string, max = 48): string {
  const one = s.replace(/\s+/g, ' ').trim();
  return one.length > max ? `"${one.slice(0, max - 1)}…"` : `"${one}"`;
}

function round(n: number): number {
  return Math.round(n * 10000) / 10000;
}

// ─────────────────────────────── Judge verdict parsing ───────────────────────────────

/**
 * The grade on a judge's `SCORE:` line (0–10), or null when the line is not one clear grade on a 0–10 scale.
 * Accepts "7", "7.5", "**7**", "<7>", "7/10", "7 / 10", "7 out of 10", "7 (out of 10)" and trailing words
 * ("7 — solid"). Rejects other scales ("4/5"), ranges and choices ("7-8", "7 or 8"), negative or
 * out-of-range numbers and "N/A". A rejected verdict is left out of the panel (never read as 0 or 10).
 */
export function parseJudgeScore(raw: string | null): number | null {
  if (raw === null) return null;
  const t = raw
    .replace(/[*_`"“”]/g, '')
    .replace(/[−–—]/g, '-')
    .trim()
    .replace(/^[<[(]\s*/, '')
    .replace(/^(\d+),(\d)(?![\d,])/, '$1.$2'); // decimal comma: "8,5"
  const m = /^(\d+(?:\.\d+)?)(?!\d|\.\d)(.*)$/s.exec(t);
  if (!m) return null;
  const n = Number(m[1]);
  const rest = m[2]!.trim().replace(/^[>\])]\s*/, '').replace(/^\(\s*/, '');
  const scale = /^(?:\/|out\s+of\b|of\b)\s*(\d+(?:\.\d+)?)/i.exec(rest);
  if (scale) {
    if (Number(scale[1]) !== 10) return null;
  } else if (/^(?:-|to\b|or\b|\/|,)\s*\d/i.test(rest)) return null;
  return Number.isFinite(n) && n >= 0 && n <= 10 ? n : null;
}

/**
 * The label on a judge's `LABEL:` line, or null unless exactly one of the test's labels is named.
 * Case, spaces, hyphens and decoration are ignored ("**Caught trap**" = CAUGHT_TRAP). A line naming two
 * labels ("PARTIAL or CORRECT", "CORRECT (not WRONG)") is a hedge and is left out of the panel.
 */
export function parseJudgeLabel(raw: string | null, labels: readonly JudgeLabel[]): JudgeLabel | null {
  if (raw === null) return null;
  const key = (s: string) => `_${s.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '')}_`;
  const line = key(raw);
  const hits = labels.filter((l) => line.includes(key(l.id)));
  // A label whose id is part of a longer named label (e.g. CORRECT inside PARTLY_CORRECT) is not a second label.
  const named = hits.filter((h) => !hits.some((o) => o !== h && key(o.id).length > key(h.id).length && key(o.id).includes(key(h.id))));
  return named.length === 1 ? named[0]! : null;
}

/**
 * Whether a panel's grades (0..1 each) disagree: the spread is MORE than 3 points out of 10 (docs/METHODOLOGY.md).
 * Compared in whole points, so floating-point noise (0.8 − 0.5 = 0.30000000000000004) never flags a spread of exactly 3.
 */
export function judgesDisagree(scores: readonly number[]): boolean {
  if (scores.length < 2) return false;
  const spreadPoints = Math.round((Math.max(...scores) - Math.min(...scores)) * 10 * 1e6) / 1e6;
  return spreadPoints > 3;
}

/**
 * Several judges were asked but only one gave a usable verdict (the others failed or were unparsable). Its grade is
 * kept, but nothing checked it, so the result is flagged for human review exactly like a disagreement.
 */
export function singleJudge(asked: number, valid: number): boolean {
  return asked >= 2 && valid === 1;
}

export function singleJudgeNote(asked: number): string {
  return `Only one judge answered (of ${asked}) — flagged for human review`;
}

/**
 * Anti-hedging: an answer that offers alternatives ("42 or 43", "A/B",
 * "either X or Y") is wrong, even if one alternative is right. Every prompt
 * with a single expected answer states this rule.
 */
export function isHedged(answer: string, kind: 'number' | 'choice' | 'text'): boolean {
  const a = answer.trim();
  if (kind === 'choice') {
    const letters = new Set((a.match(/\b[A-H]\b/g) ?? []).map((x) => x.toUpperCase()));
    return letters.size > 1;
  }
  if (kind === 'number') {
    const plain = a.replace(/(?<=\d),(?=\d{3}(?!\d))/g, '');
    const numbers = plain.match(/-?\d+(?:\.\d+)?/g) ?? [];
    if (numbers.length < 2) return false;
    if (/\b(or|either|maybe|possibly|between)\b|±|\+\/-/i.test(a)) return true;
    // A bare list of different numbers ("21, 22", "21; 22", "21 and 22") is several answers.
    const list = /^\s*[-−+]?\d+(?:\.\d+)?\s*%?(?:\s*(?:[,;&]|\band\b)\s*[-−+]?\d+(?:\.\d+)?\s*%?)+\s*$/i.test(plain);
    return list && new Set(numbers.map(Number)).size > 1;
  }
  return /\b(either)\b/i.test(a) || /\s(or|and\/or)\s/i.test(a);
}

/** The options of a multiple-choice prompt ("(A) text", "A) text" or "A. text" lines), by letter. */
export function choiceOptions(taskText: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const m of taskText.matchAll(/^[ \t]*\(?([A-H])[).:][ \t]+(\S.*)$/gm)) out.set(m[1]!, m[2]!.trim());
  // Only a real option list counts: it starts at A and has at least two options.
  return out.has('A') && out.size >= 2 ? out : new Map();
}

const alnum = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

/**
 * Read the chosen letter from a multiple-choice FINAL ANSWER. Accepts "B", "(B)", "**B**", "Option B",
 * "\boxed{B}", "B) H, H, T" (the letter followed by its own option text, even when that text contains other
 * capital letters) and the exact text of one option. Offering two options ("A or C", "B/C", "A, B, C"), or
 * a letter followed by the text of a different option, is a hedge.
 */
export function readChoice(answer: string, taskText: string): { letter: string; hedged: boolean } {
  const options = choiceOptions(taskText);
  const valid = options.size ? new Set(options.keys()) : new Set('ABCDEFGH'.split(''));
  const a = answer.replace(/^\s*(?:the\s+)?(?:correct\s+|final\s+)?(?:answer|option|choice)(?:\s+is)?\s*[:\-–—]?\s*/i, '').trim();
  const lead = /^[[(]?([A-Za-z])(?:[\])]|(?=$|[\s.:,;\-–—]))[.:,;\-–—]?\s*/.exec(a);
  if (lead && valid.has(lead[1]!.toUpperCase()) && (lead[1] === lead[1]!.toUpperCase() || /^[[(]?[a-z](?:[\])]|[.:]|$)/.test(a))) {
    const letter = lead[1]!.toUpperCase();
    const rest = alnum(a.slice(lead[0].length));
    if (!rest) return { letter, hedged: false };
    if (options.size) {
      const own = alnum(options.get(letter)!);
      if (own && (rest === own || own.includes(rest) || rest.includes(own))) return { letter, hedged: false };
      for (const [other, text] of options) if (other !== letter && alnum(text) && rest.includes(alnum(text))) return { letter, hedged: true };
    }
    const others = new Set((a.slice(lead[0].length).match(/\b[A-H]\b/g) ?? []).filter((x) => valid.has(x) && x !== letter));
    return { letter, hedged: others.size > 0 };
  }
  for (const [letter, text] of options) if (alnum(text) && alnum(a) === alnum(text)) return { letter, hedged: false };
  const letters = [...new Set((a.match(/\b[A-H]\b/g) ?? []).filter((x) => valid.has(x)))];
  if (letters.length === 1) return { letter: letters[0]!, hedged: false };
  if (letters.length > 1) return { letter: letters[0]!, hedged: true };
  const lower = [...new Set((a.match(/\b[b-h]\b/g) ?? []).map((x) => x.toUpperCase()).filter((x) => valid.has(x)))];
  if (lower.length === 1) return { letter: lower[0]!, hedged: false };
  if (lower.length > 1) return { letter: lower[0]!, hedged: true };
  return { letter: '', hedged: false };
}

const JS_LANGS = ['javascript', 'js', 'mjs', 'cjs', 'jsx', 'node', 'typescript', 'ts', ''];

/**
 * The code block to run for a coding test: the last JavaScript (or unlabelled) block that defines the
 * function, so a usage example or test snippet written after the solution is not run instead of it.
 * Falls back to the last such block, then to the last block of any language.
 */
export function solutionBlock(response: string, functionName: string): { code: string; lang: string } | null {
  const blocks = extractCodeBlocks(response);
  if (!blocks.length) return null;
  const js = blocks.filter((b) => JS_LANGS.includes(b.lang));
  const name = functionName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const defines = new RegExp(`\\bfunction\\s*\\*?\\s*${name}\\s*\\(|\\b(?:const|let|var)\\s+${name}\\s*=|\\bclass\\s+${name}\\b|exports\\.${name}\\s*=|module\\.exports\\s*=`);
  for (let i = js.length - 1; i >= 0; i--) if (defines.test(js[i]!.code)) return js[i]!;
  return js[js.length - 1] ?? blocks[blocks.length - 1]!;
}

/**
 * Whether an extracted answer equals one accepted answer of an `exact` test. Besides the plain normalised
 * comparison, three cosmetic differences are forgiven, none of which can turn a wrong answer into a right one:
 *  - list answers (keys with "," or ";"): a closing "and" ("Diego and Tobias") and leading articles on items
 *    ("an atlas" = "atlas"), on both sides;
 *  - value-shaped keys (a time, date, year or number such as "11:00" or "2027-11-23"): words around the one
 *    value ("11:00 local time"), as long as the answer contains no second value.
 */
export function exactMatches(answer: string, accepted: string, mode: NormalizeMode): boolean {
  if (normalize(answer, mode) === normalize(accepted, mode)) return true;
  if (/[,;]/.test(accepted)) {
    const noArticles = (s: string) => s.replace(/(^|[,;:]\s*)(?:a|an|the)\s+/gi, '$1');
    const a = /\sand\s/i.test(accepted) ? answer : joinListAnd(answer);
    if (normalize(noArticles(a), mode) === normalize(noArticles(accepted), mode)) return true;
  }
  const key = accepted.trim();
  if (/^\d[\d:\-./]*$/.test(key)) {
    const values = answer.match(/\d[\d:\-./]*\d|\d/g) ?? [];
    if (values.length === 1 && !isHedged(answer, 'text') && !/\bp\.?\s?m\b/i.test(answer) && normalize(values[0]!, 'alnum') === normalize(key, 'alnum')) return true;
  }
  return false;
}

/** "Maeve, Hana, Diego and Tobias" → "Maeve, Hana, Diego, Tobias": a list's closing "and" is not part of the answer. */
function joinListAnd(answer: string): string {
  return answer.replace(/,?\s+and\s+(?=[^,]*$)/i, ', ');
}

export async function scoreResponse(input: ScoringInput): Promise<ScoringOutcome> {
  const { scorer, expected } = input;
  // A reasoning block printed into the reply (<think>…</think>) is not part of the answer.
  const response = stripReasoning(input.response);
  if (response !== input.response) input = { ...input, response };
  switch (scorer.type) {
    case 'exact': {
      const { answer, formatOk } = extractFinalAnswer(response);
      const accepted = (Array.isArray(expected) ? expected : [expected]) as string[];
      const mode = scorer.normalize ?? 'lower';
      const ok = accepted.some((e) => exactMatches(answer, e, mode));
      if (!ok && isHedged(answer, 'text') && accepted.some((e) => normalize(answer, 'lower').includes(normalize(e, 'lower')))) {
        return { score: 0, passed: false, summary: `Hedged answer ${quote(answer)} (multiple answers are marked wrong)`, detail: { extracted: answer, expected, formatOk, hedged: true } };
      }
      return {
        score: ok ? 1 : 0,
        passed: ok,
        summary: ok ? `Correct: ${quote(answer)}` : `Answered ${quote(answer)} · expected ${quote(accepted[0] ?? '')}`,
        detail: { extracted: answer, expected, formatOk },
      };
    }
    case 'number': {
      const { answer, formatOk } = extractFinalAnswer(response);
      const exp = expected as number;
      if (isHedged(answer, 'number')) {
        return { score: 0, passed: false, summary: `Hedged answer ${quote(answer)} (multiple answers are marked wrong)`, detail: { extracted: answer, expected: exp, formatOk, hedged: true } };
      }
      const value = parseAnswerNumber(answer);
      const tol = scorer.tolerance ?? 1e-6;
      const ok = value !== null && (scorer.relative ? Math.abs(value - exp) <= tol * Math.max(1e-12, Math.abs(exp)) : Math.abs(value - exp) <= tol);
      return {
        score: ok ? 1 : 0,
        passed: ok,
        summary: ok ? `Correct: ${answer}` : `Answered ${quote(answer)} · expected ${exp}`,
        detail: { extracted: answer, expected: exp, formatOk, parsed: value },
      };
    }
    case 'choice': {
      const { answer, formatOk } = extractFinalAnswer(response);
      const { letter, hedged } = readChoice(answer, input.taskText);
      if (hedged) {
        return { score: 0, passed: false, summary: `Hedged answer ${quote(answer)} (multiple answers are marked wrong)`, detail: { extracted: answer, expected, formatOk, hedged: true } };
      }
      const ok = letter === String(expected).toUpperCase();
      return {
        score: ok ? 1 : 0,
        passed: ok,
        summary: ok ? `Correct: ${letter}` : `Chose ${letter || '—'} · expected ${expected}`,
        detail: { extracted: letter, expected, formatOk },
      };
    }
    case 'regex': {
      const pattern = scorer.pattern ?? String(expected);
      const re = new RegExp(pattern, scorer.flags);
      const { answer, formatOk } = scorer.fullText ? { answer: response, formatOk: true } : extractFinalAnswer(response);
      const ok = re.test(answer);
      if (scorer.fullText && /final\s+answer\s*[:：]/i.test(response)) {
        // Whole-reply patterns (e.g. "one line only" lightning rounds): report the stated answer, not the regex.
        const stated = extractFinalAnswer(response).answer;
        const oneLine = response.trim().split(/\n/).filter((l) => l.trim()).length === 1;
        return {
          score: ok ? 1 : 0,
          passed: ok,
          summary: ok ? `Correct: ${quote(stated)}` : `Answered ${quote(stated)}${oneLine ? '' : ' · reply was not a single line'}`,
          detail: { extracted: stated, expected: pattern, formatOk: true, oneLine },
        };
      }
      return {
        score: ok ? 1 : 0,
        passed: ok,
        summary: ok ? `Matched /${pattern}/` : `No match for /${pattern}/ in ${quote(answer)}`,
        detail: { extracted: scorer.fullText ? undefined : answer, expected: pattern, formatOk },
      };
    }
    case 'contains': {
      const e = (expected ?? {}) as { all?: string[]; any?: string[]; none?: string[] };
      const all = scorer.all ?? e.all ?? [];
      const any = scorer.any ?? e.any ?? [];
      const none = scorer.none ?? e.none ?? [];
      const hay = scorer.caseSensitive ? response : response.toLowerCase();
      const f = (s: string) => (scorer.caseSensitive ? s : s.toLowerCase());
      const items: ScoreBreakdownItem[] = [
        ...all.map((s) => ({ label: `mentions "${s}"`, passed: hay.includes(f(s)) })),
        ...(any.length ? [{ label: `mentions one of ${any.map((s) => `"${s}"`).join(', ')}`, passed: any.some((s) => hay.includes(f(s))) }] : []),
        ...none.map((s) => ({ label: `never mentions "${s}"`, passed: !hay.includes(f(s)) })),
      ];
      const passedCount = items.filter((i) => i.passed).length;
      const score = items.length ? passedCount / items.length : 0;
      return { score: round(score), passed: passedCount === items.length, summary: `${passedCount}/${items.length} content checks`, detail: { items } };
    }
    case 'constraints': {
      const items = checkConstraints(response, expected as never);
      const passedCount = items.filter((i) => i.passed).length;
      const all = passedCount === items.length;
      const score = scorer.allOrNothing ? (all ? 1 : 0) : passedCount / items.length;
      const failed = items.filter((i) => !i.passed).map((i) => i.label);
      return {
        score: round(score),
        passed: all,
        summary: all ? `All ${items.length} constraints met` : `${passedCount}/${items.length} constraints · missed ${failed.slice(0, 2).join('; ')}${failed.length > 2 ? '…' : ''}`,
        detail: { items },
      };
    }
    case 'json': {
      const parsed = parseJsonLoose(response, expected);
      if (parsed === undefined) return { score: 0, passed: false, summary: 'No valid JSON found', detail: { formatOk: false, expected } };
      const r = compareJson(expected, parsed, { unorderedArrays: scorer.unorderedArrays, numberTolerance: scorer.numberTolerance, aliases: scorer.aliases });
      const all = r.matched === r.total;
      return {
        score: scorer.allOrNothing ? (all ? 1 : 0) : round(r.score),
        passed: all,
        summary: scorer.allOrNothing && !all ? `${r.matched}/${r.total} fields correct · all-or-nothing, so 0` : `${r.matched}/${r.total} fields correct`,
        detail: { formatOk: true, items: r.items, expected, extracted: JSON.stringify(parsed).slice(0, 4000) },
      };
    }
    case 'code-js': {
      const e = expected as { functionName: string; tests: CodeTest[] };
      const block = solutionBlock(response, e.functionName);
      if (!block) return { score: 0, passed: false, summary: 'No code block in response', detail: { formatOk: false } };
      input.saveArtifact('solution.js', 'text', block.code);
      const r = await runCodeTests(block.code, e.functionName, e.tests, scorer.timeoutMs ?? 2000, input.signal);
      const trivial = trivialSolutionReason(e.tests, r, promptExampleArgs(input.taskText, e.functionName));
      const score = trivial ? 0 : r.total ? r.passed / r.total : 0;
      return {
        score: round(score),
        passed: !trivial && r.passed === r.total,
        summary: r.loadError ? `Code failed: ${r.loadError.slice(0, 80)}` : trivial ? `${r.passed}/${r.total} unit tests passed, but the code ${trivial}, so 0` : `${r.passed}/${r.total} unit tests passed`,
        detail: { formatOk: true, items: r.items, loadError: r.loadError ?? undefined, extracted: block.code.slice(0, 20000), notes: trivial ? `Scored 0: the code ${trivial}.` : undefined },
      };
    }
    case 'judge':
      return judgeRubric(input, scorer.rubric, scorer.passThreshold ?? 0.7);
    case 'judge-classify':
      return judgeClassify(input, scorer.instructions, scorer.labels);
    case 'artifact':
      if (scorer.playtest) return scoreGameJam(input, scorer);
      return scoreArtifact(input, scorer.format, scorer.checks ?? [{ check: 'parses' }], scorer.rubric, scorer.judgeWeight ?? 0, scorer.zeroIfJudgedZero ?? false);
    case 'human':
      return { score: null, passed: null, summary: 'Awaiting human review', detail: { notes: scorer.rubric }, pendingHuman: true };
    case 'ladder':
      return scoreLadder(scorer.answer, expected, response);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Judges
// ─────────────────────────────────────────────────────────────────────────────

function referenceText(expected: unknown): string {
  if (expected === undefined || expected === null) return '';
  return typeof expected === 'string' ? expected : JSON.stringify(expected, null, 2);
}

async function runRubricPanel(input: ScoringInput, user: string, label: string): Promise<{ score: number | null; judge: NonNullable<ScoreDetail['judge']>; errors: string[]; asked: number }> {
  const calls = await input.judges.ask(JUDGE_SYSTEM, user, label);
  const judge: NonNullable<ScoreDetail['judge']> = [];
  const errors: string[] = [];
  for (const c of calls) {
    if (c.error) {
      errors.push(`${c.judgeId}: ${c.error}`);
      continue;
    }
    const n = parseJudgeScore(extractTagged(c.text, 'SCORE'));
    if (n === null) {
      errors.push(`${c.judgeId}: unparsable verdict`);
      continue;
    }
    judge.push({ contestantId: c.judgeId, score: n / 10, rationale: c.text.replace(/SCORE\s*:.*$/im, '').trim().slice(0, 1200) });
  }
  const score = judge.length ? judge.reduce((s, j) => s + j.score, 0) / judge.length : null;
  return { score, judge, errors, asked: calls.length };
}

async function judgeRubric(input: ScoringInput, rubric: string, passThreshold: number): Promise<ScoringOutcome> {
  if (!input.response.trim()) return { score: 0, passed: false, summary: 'Empty response', detail: {} };
  const user = fill(JUDGE_RUBRIC_TEMPLATE, { task: clip(input.taskText, 30_000), reference: referenceText(input.expected), rubric, response: clip(input.response) });
  const { score, judge, errors, asked } = await runRubricPanel(input, user, 'judge');
  if (score === null) throw new Error(`All judges failed: ${errors.join('; ') || 'no judges configured'}`);
  const spread = judge.length > 1 ? Math.max(...judge.map((j) => j.score)) - Math.min(...judge.map((j) => j.score)) : 0;
  const disagree = judgesDisagree(judge.map((j) => j.score));
  const single = singleJudge(asked, judge.length);
  return {
    score: round(score),
    passed: score >= passThreshold,
    summary: `Judge panel ${(score * 10).toFixed(1)}/10${disagree ? ' · judges disagree' : ''}${single ? ' · only one judge answered' : ''}`,
    detail: {
      judge,
      judgeSpread: round(spread),
      judgeDisagreement: disagree || undefined,
      singleJudge: single || undefined,
      notes: [single ? singleJudgeNote(asked) : '', errors.length ? `Judge issues: ${errors.join('; ')}` : ''].filter(Boolean).join(' · ') || undefined,
    },
  };
}

async function judgeClassify(input: ScoringInput, instructions: string, labels: JudgeLabel[]): Promise<ScoringOutcome> {
  if (!input.response.trim()) {
    return { score: 0, passed: false, summary: 'Empty response', detail: {} };
  }
  const user = fill(JUDGE_CLASSIFY_TEMPLATE, {
    task: clip(input.taskText, 30_000),
    reference: referenceText(input.expected),
    instructions,
    labels: labels.map((l) => `- ${l.id}: ${l.description}`).join('\n'),
    response: clip(input.response),
  });
  const calls = await input.judges.ask(JUDGE_SYSTEM, user, 'judge');
  const judge: NonNullable<ScoreDetail['judge']> = [];
  const errors: string[] = [];
  for (const c of calls) {
    if (c.error) {
      errors.push(`${c.judgeId}: ${c.error}`);
      continue;
    }
    const raw = extractTagged(c.text, 'LABEL');
    const label = parseJudgeLabel(raw, labels);
    if (!label) {
      errors.push(`${c.judgeId}: unparsable label "${(raw ?? '').slice(0, 60)}"`);
      continue;
    }
    judge.push({ contestantId: c.judgeId, score: label.score, label: label.id, rationale: c.text.replace(/LABEL\s*:.*$/im, '').trim().slice(0, 1200) });
  }
  if (judge.length === 0) throw new Error(`All judges failed: ${errors.join('; ') || 'no judges configured'}`);
  const score = judge.reduce((s, j) => s + j.score, 0) / judge.length;
  // Majority label for the summary.
  const counts = new Map<string, number>();
  for (const j of judge) counts.set(j.label!, (counts.get(j.label!) ?? 0) + 1);
  const majority = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]![0];
  const unanimous = counts.size === 1;
  const single = singleJudge(calls.length, judge.length);
  return {
    score: round(score),
    passed: score >= 0.99,
    summary: `${majority.replace(/_/g, ' ').toLowerCase()} (${judge.map((j) => j.label).join(' / ')})${unanimous ? '' : ' · judges disagree'}${single ? ' · only one judge answered' : ''}`,
    detail: {
      judge,
      label: majority,
      judgeDisagreement: unanimous ? undefined : true,
      singleJudge: single || undefined,
      notes: [single ? singleJudgeNote(calls.length) : '', errors.length ? `Judge issues: ${errors.join('; ')}` : ''].filter(Boolean).join(' · ') || undefined,
    },
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Artifacts (HTML games, SVG illustrations)
// ─────────────────────────────────────────────────────────────────────────────

export function extractArtifact(response: string, format: 'html' | 'svg'): string | null {
  if (format === 'html') {
    const block = extractCodeBlock(response, ['html', 'htm']);
    if (block && /<(html|body|canvas|script|div|svg|!doctype)/i.test(block.code)) return block.code.trim();
    const raw = response.match(/<!doctype html[\s\S]*<\/html>|<html[\s\S]*<\/html>/i);
    return raw ? raw[0] : null;
  }
  const block = extractCodeBlock(response, ['svg', 'xml', 'html', '']);
  const src = block && /<svg[\s>]/i.test(block.code) ? block.code : response;
  const m = src.match(/<svg[\s\S]*<\/svg>/i);
  return m ? m[0] : null;
}

async function scoreArtifact(input: ScoringInput, format: 'html' | 'svg', checks: ArtifactCheck[], rubric: string | undefined, judgeWeight: number, zeroIfJudgedZero = false): Promise<ScoringOutcome> {
  const artifact = extractArtifact(input.response, format);
  if (!artifact) {
    return { score: 0, passed: false, summary: `No ${format.toUpperCase()} artifact found`, detail: { formatOk: false } };
  }
  const file = format === 'html' ? 'artifact.html' : 'artifact.svg';
  input.saveArtifact(file, format, artifact);
  const bytes = Buffer.byteLength(artifact);
  const items: ScoreBreakdownItem[] = [];
  const skipped: string[] = [];

  const needsBrowser = format === 'html' ? checks.some((c) => ['runs_without_errors', 'has_canvas_or_svg', 'responds_to_input', 'no_external_requests'].includes(c.check)) : true;
  const probe = format === 'html' && needsBrowser ? await probeHtml(artifact).catch(() => null) : null;
  const svgRender = format === 'svg' ? await renderSvg(artifact).catch(() => null) : null;
  if (probe) input.saveArtifact('screenshot.png', 'png', probe.screenshot);
  if (svgRender) input.saveArtifact('render.png', 'png', svgRender.png);

  for (const c of checks) {
    switch (c.check) {
      case 'parses': {
        if (format === 'svg') {
          if (svgRender) items.push({ label: 'SVG renders', passed: svgRender.ok, detail: svgRender.error });
          else items.push({ label: 'SVG is well-formed', passed: /^<svg[\s\S]*<\/svg>$/i.test(artifact.trim()) && /xmlns|viewBox|width/i.test(artifact) });
        } else {
          const ok = /<(body|canvas|script|div|main)/i.test(artifact) && bytes > 80;
          items.push({ label: 'HTML document parses', passed: ok });
        }
        break;
      }
      case 'contains':
        items.push({ label: `contains "${c.text}"`, passed: artifact.toLowerCase().includes(c.text.toLowerCase()) });
        break;
      case 'max_bytes':
        items.push({ label: `≤ ${Math.round(c.bytes / 1000)} kB`, passed: bytes <= c.bytes, detail: `${(bytes / 1000).toFixed(1)} kB` });
        break;
      case 'no_external_requests': {
        if (probe) items.push({ label: 'no external requests', passed: probe.externalRequests.length === 0, detail: probe.externalRequests.slice(0, 3).join(', ') || undefined });
        else {
          const ext = artifact.match(/(?:src|href)\s*=\s*["']https?:\/\/[^"']+|@import\s+url\(["']?https?:|fetch\(\s*["']https?:/gi) ?? [];
          items.push({ label: 'no external requests (static scan)', passed: ext.length === 0, detail: ext.slice(0, 2).join(', ') || undefined });
        }
        break;
      }
      case 'runs_without_errors':
        if (probe) items.push({ label: 'runs without JavaScript errors', passed: probe.pageErrors.length === 0, detail: probe.pageErrors[0] });
        else skipped.push('runs_without_errors');
        break;
      case 'has_canvas_or_svg':
        if (probe) items.push({ label: 'renders a canvas/SVG', passed: probe.hasCanvasOrSvg });
        else skipped.push('has_canvas_or_svg');
        break;
      case 'responds_to_input':
        if (probe) items.push({ label: 'reacts to keyboard/mouse input', passed: probe.respondsToInput });
        else skipped.push('responds_to_input');
        break;
    }
  }

  const checkScore = items.length ? items.filter((i) => i.passed).length / items.length : 1;
  let judgeScore: number | null = null;
  let judgeDetail: ScoreDetail['judge'];
  let judgeNotes: string | undefined;
  let judgeDisagreement = false;
  let single = false;
  if (rubric && judgeWeight > 0) {
    const user = fill(JUDGE_ARTIFACT_TEMPLATE, {
      task: clip(input.taskText, 20_000),
      checks: items.map((i) => `- ${i.passed ? 'PASS' : 'FAIL'}: ${i.label}${i.detail ? ` (${i.detail})` : ''}`).join('\n') || '- (none)',
      rubric,
      format: format.toUpperCase(),
      artifact: clip(artifact),
    });
    const r = await runRubricPanel(input, user, 'judge');
    judgeScore = r.score;
    judgeDetail = r.judge;
    if (judgesDisagree(r.judge.map((j) => j.score))) {
      const spread = Math.max(...r.judge.map((j) => j.score)) - Math.min(...r.judge.map((j) => j.score));
      judgeDisagreement = true;
      judgeNotes = `Judges disagree (spread ${(spread * 10).toFixed(1)} points) — flagged for human review`;
    }
    single = singleJudge(r.asked, r.judge.length);
    if (single) judgeNotes = [judgeNotes, singleJudgeNote(r.asked)].filter(Boolean).join(' · ');
    // A judge that failed must not hide the disagreement of the others (it decides human arbitration).
    if (r.errors.length) judgeNotes = [judgeNotes, `Judge issues: ${r.errors.join('; ')}`].filter(Boolean).join(' · ');
    if (judgeScore === null) throw new Error(`All judges failed: ${r.errors.join('; ') || 'no judges configured'}`);
  }
  // A 0/10 from the judges is the rubric's "automatic zero" (not the thing asked for, or unusable): the hygiene checks
  // (renders, size, a canvas exists…) must not earn points on their own, or an empty page would beat the floor.
  const gated = zeroIfJudgedZero && judgeScore !== null && judgeScore <= 0.05;
  const score = gated ? 0 : judgeScore === null ? checkScore : (1 - judgeWeight) * checkScore + judgeWeight * judgeScore;
  const passedChecks = items.filter((i) => i.passed).length;
  return {
    score: round(score),
    passed: score >= 0.7,
    summary: `${passedChecks}/${items.length} checks${judgeScore !== null ? ` · judges ${(judgeScore * 10).toFixed(1)}/10` : ''}${gated ? ' · judged 0/10, so 0' : ''}${skipped.length ? ' · browser checks skipped' : ''}`,
    detail: {
      items,
      judge: judgeDetail,
      bytes,
      checkScore: round(checkScore),
      judgeScore: judgeScore === null ? undefined : round(judgeScore),
      skippedChecks: skipped.length ? skipped : undefined,
      zeroedByJudges: gated || undefined,
      consoleErrors: probe?.consoleErrors.slice(0, 5),
      judgeDisagreement: judgeDisagreement || undefined,
      singleJudge: single || undefined,
      notes: judgeNotes,
    },
  };
}
