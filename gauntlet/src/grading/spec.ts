/**
 * What a test is graded on — one answer for people and for AI judges.
 *
 * `gradingSpecFor(test)` reads the test's real scorer config (checks, rubric,
 * labels, weights, answer key), the program's scoring formula and the
 * plain-English explainer, and turns them into a grading spec: the checklist
 * of automatic checks, rubric criteria with anchors and point values, the
 * answer key, and the rules for who may grade it. The Grading Station draws
 * its sliders / radio anchors / checklists from this, and the AI judges get
 * the same rubric text, so a person and a judge always grade the same thing.
 *
 * Pure (no Node APIs): the UI imports it too. New test types plug in with no
 * special-casing: anything with a `rubric` gets criteria parsed from it, and
 * anything else falls back to a 0–10 overall score (see docs/ADDING_TESTS.md).
 */
import type { ArtifactCheck, Constraint, JudgeLabel, PromptTestCase, ScorerSpec, TestDefinition } from '../core/types.ts';
import { explainerForDefinition, type ExplainStep, type TestExplainer } from '../core/explainers.ts';
import { checkConstraints } from '../scoring/constraints.ts';
import { JAM_CRITERIA, JAM_WEIGHTS } from '../scoring/game-jam-shared.ts';

/**
 *  - objective: scored against an answer key or by machine (exact, number, JSON, code, constraints…); people may only dispute.
 *  - judged: AI judges grade open-ended text against a rubric or labels.
 *  - artifact: a build (HTML game, SVG) checked in a browser, optionally with judges.
 *  - human: only people grade it.
 *  - simulation: a program scores the world state its moves produced.
 *  - arena: head-to-head judged games (debate, courtroom), graded on the Arena judging page.
 */
export type GradingKind = 'objective' | 'judged' | 'artifact' | 'human' | 'simulation' | 'arena';

/** What a human grade does for this test: counts (per the official-score policy), is a second opinion only, or can only raise a dispute. */
export type HumanRole = 'grade' | 'second-opinion' | 'dispute';
/** What an AI grade from the Grading Station does: grades (per policy), second opinion only, or not available. */
export type AiRole = 'grade' | 'second-opinion' | 'none';

export interface RubricAnchor {
  value: number;
  text: string;
}

export type RequirementState = 'met' | 'partial' | 'missed';

export interface RubricCriterion {
  id: string;
  label: string;
  /** What to look for, from the rubric (may be long). */
  help?: string;
  min: number;
  max: number;
  step: number;
  /** Share of the rubric total (max ÷ sum of maxima). */
  weight: number;
  /** Anchor points ("3 = loads without errors…"), highest first. Always at least the two ends. */
  anchors: RubricAnchor[];
  /**
   * Numbered requirements from the case prompt, graded one by one:
   *  - share: points divided equally, a partly met requirement earns half its share;
   *  - deduct: start from max and subtract one point per missed requirement;
   *  - all: max only if every item is met.
   */
  requirements?: { mode: 'share' | 'deduct' | 'all'; items: Array<{ id: string; text: string }> };
}

export interface ChecklistItem {
  id: string;
  label: string;
  /** Where the check comes from. */
  source: 'answer-key' | 'auto-check' | 'constraint' | 'unit-tests' | 'field' | 'phrase' | 'formula' | 'browser';
}

export interface LabelOption extends JudgeLabel {
  /** Keyboard shortcut (1-based). */
  key: number;
  /** False when the label cannot apply to this case (e.g. a "real question" label on a trap question). */
  applies: boolean;
}

export interface AnswerKey {
  expected: unknown;
  /** Human-readable answer when `expected` is not display-friendly. */
  display?: string;
  /** The tempting wrong answer. */
  lure?: string;
  /** Auditor notes (how the answer was derived). */
  notes?: string;
  /** How the answer is compared, in one line. */
  rule: string;
}

export interface GradingSpec {
  testId: string;
  testName: string;
  kind: GradingKind;
  /** e.g. "exact", "artifact", "program:escape-room", "arena:debate". */
  scorerType: string;
  /** One plain-English line: what this test is graded on. */
  gradedOn: string;
  /** The explainer's "how it's scored" steps. */
  howScored: ExplainStep[];
  humanRole: HumanRole;
  aiRole: AiRole;
  /** Singular noun for one case ("question", "task", "world", "conversation", "game"). */
  unit: string;
  /** Rubric criteria (empty for objective tests and label tests). */
  criteria: RubricCriterion[];
  /** Sum of the criteria maxima (the rubric's total, usually 10). */
  scaleMax: number;
  /** judge-classify: pick exactly one label; each maps to a score. */
  labels?: LabelOption[];
  /** What the harness checks automatically (answer key, constraints, browser checks, unit tests…). */
  checklist: ChecklistItem[];
  /** Artifact tests: share of the score from the judges (the rest is the automated checks). */
  judgeWeight?: number;
  /** Score (0..1) needed to count as passed. */
  passThreshold: number;
  /** Automatic caps / automatic zero / rounding rules from the rubric. */
  rules: string[];
  /** The rubric / judge instructions exactly as the judges get them. */
  rubricText?: string;
  /** Simulations: the scoring formula. */
  formula?: string;
  /** Only when built for one case: the answer key (never shown to models). */
  answerKey?: AnswerKey;
  /** What the output viewer should expect: 'html' | 'svg' | 'json' | 'code' | 'text' | 'replay' | 'image'. */
  output: string;
}

const GENERIC_ANCHORS: RubricAnchor[] = [
  { value: 10, text: 'Flawless: meets every requirement' },
  { value: 8, text: 'Strong, with minor flaws' },
  { value: 5, text: 'Half right: noticeable gaps or errors' },
  { value: 2, text: 'Mostly wrong or unusable' },
  { value: 0, text: 'No credit' },
];

const OBJECTIVE = new Set(['exact', 'number', 'choice', 'regex', 'contains', 'constraints', 'json', 'code-js']);

function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40) || 'c';
}

function clipText(s: string, max: number): string {
  const one = s.replace(/\s+/g, ' ').trim();
  return one.length > max ? `${one.slice(0, max - 1).replace(/\s+\S*$/, '')}…` : one;
}

/** Singular noun for one case of a test (matches the Presenter's wording). */
export function unitFor(def: TestDefinition): string {
  if (def.kind === 'program') return 'world';
  if (def.cases.some((c) => (c.turns?.length ?? 0) > 1)) return 'conversation';
  if (def.scorer.type === 'code-js' || def.scorer.type === 'artifact' || def.scorer.type === 'human') return 'task';
  return 'question';
}

// ───────────────────────────── Rubric parsing ─────────────────────────────

const CRITERION_RE = /^\s*(\d+)\.\s+([^(\n]+?)\s*\((\d+(?:\.\d+)?)\s*[-–]\s*(\d+(?:\.\d+)?)\s*points?\)\s*:?\s*(.*)$/i;
const RULE_RE = /^\s*(automatic\s+\w+|rounding|caps?|note)\s*:/i;

function parseAnchors(rest: string, min: number, max: number): RubricAnchor[] {
  const out: RubricAnchor[] = [];
  for (const part of rest.split(/;\s*(?=\d+(?:\.\d+)?\s*=)/)) {
    const m = part.match(/^\s*(\d+(?:\.\d+)?)\s*=\s*([\s\S]+?)\s*\.?\s*$/);
    if (!m) continue;
    const v = Number(m[1]);
    if (v >= min && v <= max && !out.some((a) => a.value === v)) out.push({ value: v, text: m[2]!.trim() });
  }
  if (!out.some((a) => a.value === max)) out.push({ value: max, text: max === 1 ? 'Yes: fully met' : 'Full marks' });
  if (!out.some((a) => a.value === min)) out.push({ value: min, text: min === 0 && max === 1 ? 'No: not met' : 'No credit' });
  return out.sort((a, b) => b.value - a.value);
}

/**
 * Numbered (1., 2.) or lettered (A., B.) requirement lines from a case prompt.
 * `letters`: technical requirements such as "A. Deliver ONE self-contained HTML file".
 */
export function promptRequirements(prompt: string | undefined, letters = false): Array<{ id: string; text: string }> {
  if (!prompt) return [];
  const re = letters ? /^\s*([A-H])[.)]\s+(.+)$/ : /^\s*(\d{1,2})[.)]\s+(.+)$/;
  const out: Array<{ id: string; text: string }> = [];
  for (const line of prompt.split('\n')) {
    const m = line.match(re);
    if (m && !out.some((x) => x.id === m[1])) out.push({ id: m[1]!, text: clipText(m[2]!, 220) });
  }
  return out;
}

function requirementAnchors(min: number, max: number, mode: 'share' | 'deduct' | 'all', n: number, parsed: RubricAnchor[]): RubricAnchor[] {
  if (mode === 'all') return [{ value: max, text: `All ${n} requirements met` }, { value: min, text: 'At least one requirement missing' }];
  if (mode === 'deduct') {
    const out: RubricAnchor[] = [];
    for (let v = max; v >= min; v--) out.push({ value: v, text: v === max ? 'Every requirement works' : v === min ? `${max - min} or more missing or broken` : `${max - v} missing or broken` });
    return out;
  }
  const real = parsed.filter((a) => !['Full marks', 'No credit'].includes(a.text));
  return real.length >= 2 ? parsed : [{ value: max, text: `All ${n} requirements fully met` }, { value: (max + min) / 2, text: 'About half met' }, { value: min, text: 'None met' }];
}

/** Rubric criteria ("1. Name (0-3 points): 3 = …; 2 = …") plus the automatic-caps / rounding rules. */
export function parseRubric(rubric: string | undefined, prompt?: string): { criteria: RubricCriterion[]; rules: string[] } {
  const criteria: RubricCriterion[] = [];
  const rules: string[] = [];
  for (const raw of (rubric ?? '').split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    const m = line.match(CRITERION_RE);
    if (m) {
      const min = Number(m[3]);
      const max = Number(m[4]);
      if (!(max > min)) continue;
      const label = m[2]!.trim().replace(/[:.]$/, '');
      const rest = m[5]!.trim();
      const c: RubricCriterion = { id: `${m[1]}-${slug(label)}`, label, help: rest || undefined, min, max, step: max > 3 ? 0.5 : 1, weight: 0, anchors: parseAnchors(rest, min, max) };
      // Requirement lists the rubric refers to: "numbered requirements", "Technical requirements A-F".
      const numbered = /numbered\s+(game\s+|game-specific\s+)?requirements?|game-specific numbered/i.test(label + ' ' + rest);
      const lettered = /requirements?\s+[A-H]\s*[-–]\s*[A-H]/i.test(label + ' ' + rest);
      if (prompt && (numbered || lettered)) {
        const items = promptRequirements(prompt, lettered && !numbered);
        if (items.length) {
          const mode: 'share' | 'deduct' | 'all' = /subtract|deduct/i.test(rest) ? 'deduct' : /\ball of\b|only if all|1 if all/i.test(rest) || lettered ? 'all' : 'share';
          c.requirements = { mode, items };
          c.anchors = requirementAnchors(c.min, c.max, mode, items.length, c.anchors);
        }
      }
      criteria.push(c);
    } else if (RULE_RE.test(line)) rules.push(line);
  }
  const total = criteria.reduce((s, c) => s + (c.max - c.min), 0);
  for (const c of criteria) c.weight = total > 0 ? (c.max - c.min) / total : 0;
  return { criteria, rules };
}

/** Points a criterion earns from per-requirement states (requirement criteria only). */
export function requirementPoints(c: RubricCriterion, states: Record<string, RequirementState | undefined>): number {
  const items = c.requirements?.items ?? [];
  if (!items.length) return c.min;
  const st = items.map((it) => states[it.id] ?? 'missed');
  const mode = c.requirements!.mode;
  if (mode === 'all') return st.every((s) => s === 'met') ? c.max : c.min;
  if (mode === 'deduct') return Math.max(c.min, c.max - st.filter((s) => s !== 'met').length);
  const share = (c.max - c.min) / items.length;
  const raw = st.reduce((s, x) => s + (x === 'met' ? share : x === 'partial' ? share / 2 : 0), 0);
  return c.min + Math.round(raw * 2) / 2;
}

/** 0..1 score from rubric points (criterion id → points), or from a chosen label. */
export function scoreFromGrade(spec: Pick<GradingSpec, 'criteria' | 'labels'>, grade: { criteria?: Record<string, number>; label?: string }): number | null {
  if (spec.labels?.length) {
    const l = spec.labels.find((x) => x.id === grade.label);
    return l ? l.score : null;
  }
  if (!spec.criteria.length) return null;
  // Weighted mean of each criterion's share of its range. For parsed rubrics the weight is the criterion's share of
  // the points, so this equals "points earned ÷ points available"; the Game Jam gives its own weights.
  let got = 0;
  let total = 0;
  for (const c of spec.criteria) {
    const v = grade.criteria?.[c.id];
    if (typeof v !== 'number' || !Number.isFinite(v)) return null;
    const w = c.weight > 0 ? c.weight : c.max - c.min;
    got += (w * (Math.min(c.max, Math.max(c.min, v)) - c.min)) / (c.max - c.min || 1);
    total += w;
  }
  return total > 0 ? Math.round((got / total) * 10000) / 10000 : null;
}

/**
 * Game Jam rubric (artifact scorer with `playtest`, src/scoring/game-jam.ts): the requirement checklist (PASS 1,
 * PARTIAL ½, FAIL 0 per numbered requirement from case.expected.requirements) plus five 0–10 criteria, with the
 * scorer's own weights and the anchors written in the test's rubric ("PLAYS (0-10): … 10 = … 7 = …").
 */
function jamRubric(rubric: string | undefined, expected: unknown): { criteria: RubricCriterion[]; rules: string[] } {
  const text = rubric ?? '';
  const reqs = Array.isArray((expected as { requirements?: unknown })?.requirements) ? ((expected as { requirements: string[] }).requirements) : [];
  const criteria: RubricCriterion[] = [];
  for (const k of JAM_CRITERIA) {
    const weight = JAM_WEIGHTS[k.id];
    if (!k.key) {
      criteria.push({ id: k.id, label: k.label, help: 'PASS = implemented and working; PARTIAL = present but incomplete or buggy (half credit); FAIL = missing or broken.', min: 0, max: 10, step: 0.5, weight, anchors: [{ value: 10, text: 'Every requirement passes' }, { value: 5, text: 'About half the checklist' }, { value: 0, text: 'Nothing on the checklist works' }], requirements: reqs.length ? { mode: 'share', items: reqs.map((r, i) => ({ id: String(i + 1), text: r })) } : undefined });
      continue;
    }
    const para = new RegExp(`^${k.key}\\s*\\(0-10\\):\\s*([^\\n]*)\\n?([^\\n]*)`, 'm').exec(text);
    const help = para?.[1]?.trim();
    const anchors: RubricAnchor[] = [];
    for (const piece of (para?.[2] ?? '').split(/\s(?=\d{1,2} = )/)) {
      const m = /^(\d{1,2}) = (.+?)\.?\s*$/.exec(piece.trim());
      if (m && Number(m[1]) <= 10 && !anchors.some((a) => a.value === Number(m[1]))) anchors.push({ value: Number(m[1]), text: m[2]!.split(/\.\s(?=[A-Z])/)[0]! });
    }
    if (!anchors.some((a) => a.value === 10)) anchors.push({ value: 10, text: 'Outstanding' });
    if (!anchors.some((a) => a.value === 0)) anchors.push({ value: 0, text: 'None at all' });
    criteria.push({ id: k.id, label: k.label, help, min: 0, max: 10, step: 0.5, weight, anchors: anchors.sort((a, b) => b.value - a.value) });
  }
  const rules = text.split('\n').filter((l) => /^hard rules?:/i.test(l.trim()));
  return { criteria, rules };
}

function overallCriterion(help?: string): RubricCriterion {
  return { id: 'overall', label: 'Overall score', help, min: 0, max: 10, step: 0.5, weight: 1, anchors: GENERIC_ANCHORS };
}

// ───────────────────────────── Checklists & answer keys ─────────────────────────────

function artifactCheckLabel(c: ArtifactCheck, format: 'html' | 'svg'): string {
  switch (c.check) {
    case 'parses':
      return format === 'svg' ? 'SVG renders' : 'HTML document parses';
    case 'contains':
      return `contains "${c.text}"`;
    case 'max_bytes':
      return `≤ ${Math.round(c.bytes / 1000)} kB`;
    case 'no_external_requests':
      return 'no external requests';
    case 'runs_without_errors':
      return 'runs without JavaScript errors';
    case 'has_canvas_or_svg':
      return 'renders a canvas/SVG';
    case 'responds_to_input':
      return 'reacts to keyboard/mouse input';
  }
}

function jsonFieldPaths(v: unknown, prefix = '', out: string[] = []): string[] {
  if (out.length >= 40) return out;
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    for (const [k, x] of Object.entries(v as Record<string, unknown>)) jsonFieldPaths(x, prefix ? `${prefix}.${k}` : k, out);
  } else if (Array.isArray(v) && v.some((x) => x && typeof x === 'object')) {
    v.forEach((x, i) => jsonFieldPaths(x, `${prefix}[${i}]`, out));
  } else out.push(prefix || '(value)');
  return out;
}

function checklistFor(sc: ScorerSpec, c?: PromptTestCase): ChecklistItem[] {
  switch (sc.type) {
    case 'exact':
      return [{ id: 'answer', label: `Final answer matches the key (${sc.normalize === 'alnum' ? 'letters and digits only' : sc.normalize === 'none' ? 'exactly' : 'ignoring case'})`, source: 'answer-key' }];
    case 'number':
      return [{ id: 'answer', label: `Final number matches the key${sc.tolerance ? ` within ${sc.relative ? `${sc.tolerance * 100}%` : `±${sc.tolerance}`}` : ''}`, source: 'answer-key' }];
    case 'choice':
      return [{ id: 'answer', label: 'Chosen letter matches the key', source: 'answer-key' }];
    case 'regex':
      return [{ id: 'answer', label: sc.fullText ? 'Whole reply matches the required pattern' : 'Final answer matches the required pattern', source: 'answer-key' }];
    case 'contains': {
      const e = (c?.expected ?? {}) as { all?: string[]; any?: string[]; none?: string[] };
      const all = sc.all ?? e.all ?? [];
      const any = sc.any ?? e.any ?? [];
      const none = sc.none ?? e.none ?? [];
      const items: ChecklistItem[] = [
        ...all.map((s, i) => ({ id: `all-${i}`, label: `mentions "${s}"`, source: 'phrase' as const })),
        ...(any.length ? [{ id: 'any', label: `mentions one of ${any.map((s) => `"${s}"`).join(', ')}`, source: 'phrase' as const }] : []),
        ...none.map((s, i) => ({ id: `none-${i}`, label: `never mentions "${s}"`, source: 'phrase' as const })),
      ];
      return items.length ? items : [{ id: 'phrases', label: 'Required phrases present, forbidden ones absent', source: 'phrase' }];
    }
    case 'constraints': {
      const list = Array.isArray(c?.expected) ? (c!.expected as Constraint[]) : null;
      if (!list) return [{ id: 'rules', label: 'Every machine-checkable rule in the prompt', source: 'constraint' }];
      return checkConstraints('', list).map((it, i) => ({ id: `rule-${i}`, label: it.label, source: 'constraint' as const }));
    }
    case 'json': {
      if (c?.expected === undefined) return [{ id: 'fields', label: 'Each field of the expected JSON', source: 'field' }];
      return jsonFieldPaths(c.expected).map((p, i) => ({ id: `field-${i}`, label: `field ${p}`, source: 'field' as const }));
    }
    case 'code-js': {
      const n = Array.isArray((c?.expected as { tests?: unknown[] } | undefined)?.tests) ? (c!.expected as { tests: unknown[] }).tests.length : null;
      return [{ id: 'tests', label: n ? `${n} hidden unit tests run on the code` : 'Hidden unit tests run on the code', source: 'unit-tests' }];
    }
    case 'artifact':
      return (sc.checks ?? [{ check: 'parses' }]).map((ch, i) => ({ id: `check-${i}`, label: artifactCheckLabel(ch, sc.format), source: ['runs_without_errors', 'has_canvas_or_svg', 'responds_to_input', 'no_external_requests'].includes(ch.check) || sc.format === 'svg' ? ('browser' as const) : ('auto-check' as const) }));
    default:
      return [];
  }
}

function answerRule(sc: ScorerSpec): string {
  switch (sc.type) {
    case 'exact':
      return 'The FINAL ANSWER line must equal an accepted answer. Offering two answers is wrong.';
    case 'number':
      return `The FINAL ANSWER number must equal the key${sc.tolerance ? ` (tolerance ${sc.relative ? `${sc.tolerance * 100}%` : `±${sc.tolerance}`})` : ''}. Hedged numbers are wrong.`;
    case 'choice':
      return 'The chosen letter must match. Picking two letters is wrong.';
    case 'regex':
      return 'The answer must match the pattern.';
    case 'json':
      return `Each expected field is compared${sc.allOrNothing ? '; one wrong field scores 0' : '; score = share of fields right'}.`;
    case 'constraints':
      return `Each rule is checked by machine${sc.allOrNothing ? '; break one and it scores 0' : '; score = share of rules followed'}.`;
    case 'contains':
      return 'Score = share of required/forbidden phrases handled correctly.';
    case 'code-js':
      return 'The code is run against hidden unit tests; score = share passed.';
    case 'judge':
    case 'human':
      return 'Reference for the grader (may be empty).';
    case 'judge-classify':
      return 'Ground truth for the grader: pick the label that fits.';
    case 'artifact':
      return 'Automated checks plus the rubric.';
  }
}

/** Labels that apply to a case: when the instructions say `For type "trap": …` and the case has `expected.type`, only the labels named there. */
function applicableLabels(instructions: string, labels: JudgeLabel[], expected: unknown): Set<string> | null {
  const type = expected && typeof expected === 'object' && typeof (expected as { type?: unknown }).type === 'string' ? (expected as { type: string }).type : null;
  if (!type) return null;
  const t = type.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // Prefer a "For type "x": …" rule; otherwise any sentence about type "x".
  for (const lead of ['For type', 'type']) {
    const re = new RegExp(`${lead}\\s+"${t}"[^:]*:([\\s\\S]*?)(?=\\n?\\s*\\d+\\.\\s+For type|\\n\\n|$)`, 'gi');
    for (const m of instructions.matchAll(re)) {
      const seg = m[1] ?? '';
      const named = new Set(labels.filter((l) => new RegExp(`\\b${l.id}\\b`).test(seg)).map((l) => l.id));
      if (named.size) return named;
    }
  }
  return null;
}

function outputKind(sc: ScorerSpec): string {
  if (sc.type === 'artifact') return sc.format;
  if (sc.type === 'json') return 'json';
  if (sc.type === 'code-js') return 'code';
  return 'text';
}

// ───────────────────────────── The spec ─────────────────────────────

export interface SpecOptions {
  /** Build for one case: case scorer override, numbered requirements from its prompt, and its answer key. */
  caseId?: string;
  /** Program tests: the program's scoring text (PROGRAMS[id].scoring). */
  programScoring?: string;
  /** Explainer override (defaults to the hand-written one, or one generated from the definition). */
  explainer?: TestExplainer;
}

export function gradingSpecFor(def: TestDefinition, opts: SpecOptions = {}): GradingSpec {
  const explainer = opts.explainer ?? explainerForDefinition(def, opts.programScoring);
  const base = { testId: def.id, testName: def.name, howScored: explainer.howScored, unit: unitFor(def) };
  if (def.kind === 'program') {
    return {
      ...base,
      kind: 'simulation',
      scorerType: `program:${def.program}`,
      gradedOn: 'Scored by the simulation from the world its moves produced, using a fixed formula. Nobody grades it by hand.',
      humanRole: 'dispute',
      aiRole: 'none',
      criteria: [],
      scaleMax: 0,
      checklist: [
        ...explainer.howScored.map((s, i) => ({ id: `step-${i}`, label: s.text, source: 'formula' as const })),
      ],
      passThreshold: 0.5,
      rules: [],
      formula: opts.programScoring,
      output: 'replay',
    };
  }
  const c = opts.caseId ? def.cases.find((x) => x.id === opts.caseId) : undefined;
  const sc = c?.scorer ?? def.scorer;
  const prompt = c ? [def.preamble, ...(c.turns ?? [c.prompt ?? ''])].filter(Boolean).join('\n\n') : undefined;
  const answerKey: AnswerKey | undefined = c
    ? { expected: c.expected, display: c.displayAnswer, lure: c.lure, notes: c.notes, rule: answerRule(sc) }
    : undefined;
  const images = (c?.images?.length ?? 0) > 0;
  const common = { ...base, scorerType: sc.type, checklist: checklistFor(sc, c), answerKey, output: images && OBJECTIVE.has(sc.type) ? (outputKind(sc) === 'text' ? 'image' : outputKind(sc)) : outputKind(sc) };

  if (sc.type !== 'judge-classify' && sc.type !== 'judge' && sc.type !== 'human' && sc.type !== 'artifact') {
    return {
      ...common,
      kind: 'objective',
      gradedOn:
        sc.type === 'code-js'
          ? 'The code is run against hidden unit tests. The machine decides; a person can flag a dispute but not change the score.'
          : sc.type === 'constraints'
            ? 'Every rule is checked by machine. A person can flag a dispute but not change the score.'
            : 'Checked against an answer key. A person can flag a dispute but never overwrite the key.',
      humanRole: 'dispute',
      aiRole: 'none',
      criteria: [],
      scaleMax: 0,
      passThreshold: 1,
      rules: [],
    };
  }

  if (sc.type === 'judge-classify') {
    const only = applicableLabels(sc.instructions, sc.labels, c?.expected);
    return {
      ...common,
      kind: 'judged',
      gradedOn: `Pick the one label that fits the answer (${sc.labels.map((l) => l.id.replace(/_/g, ' ').toLowerCase()).join(', ')}); each label is worth a fixed score.`,
      humanRole: 'grade',
      aiRole: 'grade',
      criteria: [],
      scaleMax: 1,
      labels: sc.labels.map((l, i) => ({ ...l, key: i + 1, applies: only ? only.has(l.id) : true })),
      passThreshold: 0.99,
      rules: [],
      rubricText: sc.instructions,
    };
  }

  if (sc.type === 'judge' || sc.type === 'human') {
    const parsed = parseRubric(sc.rubric, prompt);
    const criteria = parsed.criteria.length ? parsed.criteria : [overallCriterion(sc.rubric)];
    return {
      ...common,
      kind: sc.type === 'human' ? 'human' : 'judged',
      gradedOn: sc.type === 'human' ? 'Graded by people against the rubric below. The AI can give a second opinion.' : 'AI judges from other vendors grade the answer against the rubric below; a person can grade it too.',
      humanRole: 'grade',
      aiRole: sc.type === 'human' ? 'second-opinion' : 'grade',
      criteria,
      scaleMax: criteria.reduce((s, x) => s + x.max - x.min, 0),
      passThreshold: sc.type === 'judge' ? (sc.passThreshold ?? 0.7) : 0.7,
      rules: parsed.rules,
      rubricText: sc.rubric,
    };
  }

  // Artifact: automated checks, plus a rubric the judges (and people) grade when judgeWeight > 0.
  const w = sc.judgeWeight ?? 0;
  const parsed = sc.playtest ? jamRubric(sc.rubric, c?.expected) : parseRubric(sc.rubric, prompt);
  const criteria = sc.rubric ? (parsed.criteria.length ? parsed.criteria : [overallCriterion(sc.rubric)]) : [overallCriterion('How good is the build? There is no rubric for this test, so this rating is a second opinion only.')];
  return {
    ...common,
    kind: 'artifact',
    gradedOn:
      w > 0
        ? `Opened in a real browser and checked automatically (${Math.round((1 - w) * 100)}% of the score), then graded against the rubric (${Math.round(w * 100)}%).`
        : 'Opened in a real browser and checked automatically. A person can add a second-opinion rating; it does not change the score.',
    humanRole: w > 0 ? 'grade' : 'second-opinion',
    aiRole: w > 0 ? 'grade' : sc.rubric ? 'second-opinion' : 'none',
    criteria,
    scaleMax: criteria.reduce((s, x) => s + x.max - x.min, 0),
    judgeWeight: w,
    passThreshold: 0.7,
    rules: parsed.rules,
    rubricText: sc.rubric,
  };
}

// ───────────────────────────── Arena (judged games) ─────────────────────────────

/** Judged Arena games (debate, courtroom): each side is scored 1–10 on every rubric item and a winner is picked. */
export function gradingSpecForArena(game: { id: string; name: string; rubric: Array<{ key: string; label: string; help: string }> }): GradingSpec {
  const criteria: RubricCriterion[] = game.rubric.map((r) => ({
    id: r.key,
    label: r.label,
    help: r.help,
    min: 1,
    max: 10,
    step: 1,
    weight: 1 / Math.max(1, game.rubric.length),
    anchors: [
      { value: 10, text: 'Outstanding' },
      { value: 7, text: 'Good' },
      { value: 4, text: 'Weak' },
      { value: 1, text: 'Very poor' },
    ],
  }));
  return {
    testId: `arena.${game.id}`,
    testName: game.name,
    kind: 'arena',
    scorerType: `arena:${game.id}`,
    gradedOn: 'Each side is scored 1–10 on every rubric item from a blinded transcript (Side A / Side B), then a winner is picked. Graded on the Arena judging page.',
    howScored: [],
    humanRole: 'grade',
    aiRole: 'grade',
    unit: 'game',
    criteria,
    scaleMax: criteria.length * 9,
    checklist: [],
    passThreshold: 0.5,
    rules: [],
    output: 'transcript',
  };
}
