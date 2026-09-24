import type { ArtifactCheck, ArtifactKind, ArtifactRef, JudgeLabel, ScoreBreakdownItem, ScoreDetail, ScorerSpec, StopReason } from '../core/types.ts';
import { extractCodeBlock, extractFinalAnswer, extractTagged, normalize, parseJsonLoose, parseNumber } from '../core/extract.ts';
import { checkConstraints } from './constraints.ts';
import { compareJson } from './json-compare.ts';
import { runCodeTests, type CodeTest } from './code-sandbox.ts';
import { probeHtml, renderSvg } from './browser.ts';
import { JUDGE_ARTIFACT_TEMPLATE, JUDGE_CLASSIFY_TEMPLATE, JUDGE_RUBRIC_TEMPLATE, JUDGE_SYSTEM, fill } from './judge-prompts.ts';

export interface JudgeCall {
  judgeId: string;
  text: string;
  error?: string;
}

/** A panel of judge models. `ask` calls every judge in parallel. */
export interface JudgePanel {
  ids: string[];
  ask(system: string, user: string, label: string): Promise<JudgeCall[]>;
}

export interface ScoringInput {
  scorer: ScorerSpec;
  expected: unknown;
  /** Final reply text. */
  response: string;
  stopReason: StopReason;
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
    const numbers = a.replace(/(\d),(\d{3})/g, '$1$2').match(/-?\d+(?:\.\d+)?/g) ?? [];
    return numbers.length >= 2 && /\b(or|either|maybe|possibly|between)\b|±|\+\/-/i.test(a);
  }
  return /\b(either)\b/i.test(a) || /\s(or|and\/or)\s/i.test(a);
}

export async function scoreResponse(input: ScoringInput): Promise<ScoringOutcome> {
  const { scorer, expected, response } = input;
  switch (scorer.type) {
    case 'exact': {
      const { answer, formatOk } = extractFinalAnswer(response);
      const accepted = (Array.isArray(expected) ? expected : [expected]) as string[];
      const mode = scorer.normalize ?? 'lower';
      const ok = accepted.some((e) => normalize(answer, mode) === normalize(e, mode));
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
      const value = parseNumber(answer);
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
      if (isHedged(answer, 'choice')) {
        return { score: 0, passed: false, summary: `Hedged answer ${quote(answer)} (multiple answers are marked wrong)`, detail: { extracted: answer, expected, formatOk, hedged: true } };
      }
      const letter = answer.match(/\b([A-Z])\b/i)?.[1]?.toUpperCase() ?? answer.trim().charAt(0).toUpperCase();
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
      const parsed = parseJsonLoose(response);
      if (parsed === undefined) return { score: 0, passed: false, summary: 'No valid JSON found', detail: { formatOk: false, expected } };
      const r = compareJson(expected, parsed, { unorderedArrays: scorer.unorderedArrays, numberTolerance: scorer.numberTolerance });
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
      const block = extractCodeBlock(response, ['javascript', 'js', 'mjs', 'jsx', 'typescript', 'ts', '']);
      if (!block) return { score: 0, passed: false, summary: 'No code block in response', detail: { formatOk: false } };
      input.saveArtifact('solution.js', 'text', block.code);
      const r = await runCodeTests(block.code, e.functionName, e.tests, scorer.timeoutMs ?? 2000, input.signal);
      const score = r.total ? r.passed / r.total : 0;
      return {
        score: round(score),
        passed: r.passed === r.total,
        summary: r.loadError ? `Code failed: ${r.loadError.slice(0, 80)}` : `${r.passed}/${r.total} unit tests passed`,
        detail: { formatOk: true, items: r.items, loadError: r.loadError ?? undefined, extracted: block.code.slice(0, 20000) },
      };
    }
    case 'judge':
      return judgeRubric(input, scorer.rubric, scorer.passThreshold ?? 0.7);
    case 'judge-classify':
      return judgeClassify(input, scorer.instructions, scorer.labels);
    case 'artifact':
      return scoreArtifact(input, scorer.format, scorer.checks ?? [{ check: 'parses' }], scorer.rubric, scorer.judgeWeight ?? 0);
    case 'human':
      return { score: null, passed: null, summary: 'Awaiting human review', detail: { notes: scorer.rubric }, pendingHuman: true };
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Judges
// ─────────────────────────────────────────────────────────────────────────────

function referenceText(expected: unknown): string {
  if (expected === undefined || expected === null) return '';
  return typeof expected === 'string' ? expected : JSON.stringify(expected, null, 2);
}

async function runRubricPanel(input: ScoringInput, user: string, label: string): Promise<{ score: number | null; judge: NonNullable<ScoreDetail['judge']>; errors: string[] }> {
  const calls = await input.judges.ask(JUDGE_SYSTEM, user, label);
  const judge: NonNullable<ScoreDetail['judge']> = [];
  const errors: string[] = [];
  for (const c of calls) {
    if (c.error) {
      errors.push(`${c.judgeId}: ${c.error}`);
      continue;
    }
    const raw = extractTagged(c.text, 'SCORE');
    const n = raw === null ? null : parseNumber(raw);
    if (n === null || n < 0 || n > 10) {
      errors.push(`${c.judgeId}: unparsable verdict`);
      continue;
    }
    judge.push({ contestantId: c.judgeId, score: n / 10, rationale: c.text.replace(/SCORE\s*:.*$/im, '').trim().slice(0, 1200) });
  }
  const score = judge.length ? judge.reduce((s, j) => s + j.score, 0) / judge.length : null;
  return { score, judge, errors };
}

async function judgeRubric(input: ScoringInput, rubric: string, passThreshold: number): Promise<ScoringOutcome> {
  if (!input.response.trim()) return { score: 0, passed: false, summary: 'Empty response', detail: {} };
  const user = fill(JUDGE_RUBRIC_TEMPLATE, { task: clip(input.taskText, 30_000), reference: referenceText(input.expected), rubric, response: clip(input.response) });
  const { score, judge, errors } = await runRubricPanel(input, user, 'judge');
  if (score === null) throw new Error(`All judges failed: ${errors.join('; ') || 'no judges configured'}`);
  const spread = judge.length > 1 ? Math.max(...judge.map((j) => j.score)) - Math.min(...judge.map((j) => j.score)) : 0;
  return {
    score: round(score),
    passed: score >= passThreshold,
    summary: `Judge panel ${(score * 10).toFixed(1)}/10${spread > 0.3 ? ' · judges disagree' : ''}`,
    detail: { judge, judgeSpread: round(spread), judgeDisagreement: spread > 0.3 || undefined, notes: errors.length ? `Judge issues: ${errors.join('; ')}` : undefined },
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
    const raw = (extractTagged(c.text, 'LABEL') ?? '').toUpperCase().replace(/[^A-Z0-9_]/g, '');
    const label = labels.find((l) => l.id === raw) ?? labels.find((l) => raw.startsWith(l.id));
    if (!label) {
      errors.push(`${c.judgeId}: unparsable label "${raw}"`);
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
  return {
    score: round(score),
    passed: score >= 0.99,
    summary: `${majority.replace(/_/g, ' ').toLowerCase()} (${judge.map((j) => j.label).join(' / ')})${unanimous ? '' : ' · judges disagree'}`,
    detail: { judge, label: majority, judgeDisagreement: unanimous ? undefined : true, notes: errors.length ? `Judge issues: ${errors.join('; ')}` : undefined },
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

async function scoreArtifact(input: ScoringInput, format: 'html' | 'svg', checks: ArtifactCheck[], rubric: string | undefined, judgeWeight: number): Promise<ScoringOutcome> {
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
    if (r.judge.length > 1) {
      const spread = Math.max(...r.judge.map((j) => j.score)) - Math.min(...r.judge.map((j) => j.score));
      if (spread > 0.3) judgeNotes = `Judges disagree (spread ${(spread * 10).toFixed(1)} points) — flagged for human review`;
    }
    if (r.errors.length) judgeNotes = `Judge issues: ${r.errors.join('; ')}`;
    if (judgeScore === null) throw new Error(`All judges failed: ${r.errors.join('; ') || 'no judges configured'}`);
  }
  const score = judgeScore === null ? checkScore : (1 - judgeWeight) * checkScore + judgeWeight * judgeScore;
  const passedChecks = items.filter((i) => i.passed).length;
  return {
    score: round(score),
    passed: score >= 0.7,
    summary: `${passedChecks}/${items.length} checks${judgeScore !== null ? ` · judges ${(judgeScore * 10).toFixed(1)}/10` : ''}${skipped.length ? ' · browser checks skipped' : ''}`,
    detail: {
      items,
      judge: judgeDetail,
      bytes,
      checkScore: round(checkScore),
      judgeScore: judgeScore === null ? undefined : round(judgeScore),
      skippedChecks: skipped.length ? skipped : undefined,
      consoleErrors: probe?.consoleErrors.slice(0, 5),
      judgeDisagreement: judgeNotes?.startsWith('Judges disagree') || undefined,
      notes: judgeNotes,
    },
  };
}
