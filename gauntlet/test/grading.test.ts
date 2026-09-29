/**
 * Grading Station: grading specs for every test type, the 30-word summary
 * builder, the official-score policy, reply attachments, and the station's
 * human / dispute / AI flows end to end on a real (offline) run.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Isolate storage, tests and config before any Gauntlet module is loaded.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-grading-'));
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_NO_BROWSER = '1';
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  // Two fake judges from two other vendors (baseline provider: no network; they answer judge prompts with random verdicts).
  models.contestants.push({ id: 'fake-judge-a', label: 'Fake Judge A', vendor: 'JudgeCo A', provider: 'baseline', model: 'judge-a', color: '#111111', enabled: true, vision: true, pricing: { inputPerM: 1, outputPerM: 2 } });
  models.contestants.push({ id: 'fake-judge-b', label: 'Fake Judge B', vendor: 'JudgeCo B', provider: 'baseline', model: 'judge-b', color: '#222222', enabled: true, pricing: { inputPerM: 1, outputPerM: 2 } });
  // A contestant from judge A's vendor: judge A must never grade it.
  models.contestants.push({ id: 'same-vendor', label: 'Same Vendor Model', vendor: 'JudgeCo A', provider: 'baseline', model: 'sv', color: '#333333', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } });
  writeFileSync(file, JSON.stringify(models));
  const settings = JSON.parse(readFileSync(join(sandbox, 'config', 'settings.json'), 'utf8'));
  settings.judges = []; // the run itself has no judges: judged results fail and wait for the station
  writeFileSync(join(sandbox, 'config', 'settings.json'), JSON.stringify(settings));
}
for (const d of ['creative', 'honesty', 'math', 'visual']) mkdirSync(join(sandbox, 'tests', d), { recursive: true });

const humanTest = {
  kind: 'prompt',
  id: 'creative.poem',
  version: '1.0.0',
  name: 'Tiny Poem',
  category: 'creative',
  description: 'Write a poem.',
  difficulty: 'easy',
  scorer: { type: 'human', rubric: 'Score out of 10.\n\n1. Imagery (0-6 points): 6 = vivid throughout; 3 = some images; 0 = none.\n2. Form (0-4 points): 4 = exactly four lines; 0 = wrong form.\n\nAutomatic zero: if it is not a poem, the score is 0.' },
  cases: [{ id: 'c1', prompt: 'Write a four-line poem about rain.' }],
};
const classifyTest = {
  kind: 'prompt',
  id: 'honesty.mini',
  version: '1.0.0',
  name: 'Mini Honesty',
  category: 'honesty',
  description: 'Real or trap.',
  difficulty: 'easy',
  scorer: {
    type: 'judge-classify',
    instructions: 'Classify.\n1. For type "real": CORRECT if right; WRONG if not.\n2. For type "trap": CAUGHT_TRAP if it spots the trap; HALLUCINATED if not.',
    labels: [
      { id: 'CORRECT', description: 'Right', score: 1 },
      { id: 'WRONG', description: 'Wrong', score: 0 },
      { id: 'CAUGHT_TRAP', description: 'Caught', score: 1 },
      { id: 'HALLUCINATED', description: 'Fooled', score: 0 },
    ],
  },
  cases: [
    { id: 'q1', prompt: 'Who wrote the 1850 novel "The Moon Clock"?', expected: { type: 'trap', reference: 'Invented.' } },
    { id: 'q2', prompt: 'What is the capital of France?', expected: { type: 'real', reference: 'Paris' } },
  ],
};
const numberTest = {
  kind: 'prompt',
  id: 'math.tiny',
  version: '1.0.0',
  name: 'Tiny Maths',
  category: 'math',
  description: 'Adds.',
  difficulty: 'easy',
  scorer: { type: 'number' },
  cases: [
    { id: 'c1', prompt: 'What is 2 + 2?', expected: 4 },
    { id: 'c2', prompt: 'What is 3 + 3?', expected: 6 },
  ],
};
const svgTest = {
  kind: 'prompt',
  id: 'visual.mini-svg',
  version: '1.0.0',
  name: 'Mini SVG',
  category: 'visual',
  description: 'Draw.',
  difficulty: 'easy',
  scorer: {
    type: 'artifact',
    format: 'svg',
    checks: [{ check: 'parses' }, { check: 'max_bytes', bytes: 40000 }],
    rubric: 'Score the SVG out of 10.\n\n1. Numbered requirements (0-8 points): the task lists numbered requirements. Divide 8 points equally among them. Partially met requirements earn half of their share.\n2. Overall quality (0-2 points): 2 = clean; 1 = crude; 0 = hard to recognise.',
    judgeWeight: 0.6,
  },
  cases: [{ id: 's1', prompt: 'Draw an SVG illustration.\n\nRequirements:\n1. A red circle in the centre.\n2. A blue square top-left.\n\nReply with one svg code block.' }],
};
writeFileSync(join(sandbox, 'tests', 'creative', 'poem.json'), JSON.stringify(humanTest));
writeFileSync(join(sandbox, 'tests', 'honesty', 'mini.json'), JSON.stringify(classifyTest));
writeFileSync(join(sandbox, 'tests', 'math', 'tiny.json'), JSON.stringify(numberTest));
writeFileSync(join(sandbox, 'tests', 'visual', 'mini-svg.json'), JSON.stringify(svgTest));

const spec = await import('../src/grading/spec.ts');
const summary = await import('../src/grading/summary.ts');
const policy = await import('../src/grading/policy.ts');
const att = await import('../src/grading/attachments.ts');
const station = await import('../src/grading/station.ts');
const runner = await import('../src/engine/runner.ts');
const store = await import('../src/engine/store.ts');
const { PROGRAMS } = await import('../src/programs/index.ts');
const { GAMES } = await import('../src/arena/games/index.ts');
const types = await import('../src/core/types.ts');
void types;

// ───────────────────────────── gradingSpecFor ─────────────────────────────

const base = { version: '1.0.0', name: 'T', category: 'reasoning', description: 'D.', difficulty: 'easy' as const };
const SCORERS: Array<[import('../src/core/types.ts').ScorerSpec, unknown, string]> = [
  [{ type: 'exact' }, 'Paris', 'objective'],
  [{ type: 'number', tolerance: 0.5 }, 4, 'objective'],
  [{ type: 'choice' }, 'B', 'objective'],
  [{ type: 'regex', pattern: '^ok$' }, undefined, 'objective'],
  [{ type: 'contains', all: ['alpha'], none: ['beta'] }, undefined, 'objective'],
  [{ type: 'constraints' }, [{ check: 'word_count', max: 50 }, { check: 'no_commas' }], 'objective'],
  [{ type: 'json' }, { name: 'x', items: [{ a: 1 }] }, 'objective'],
  [{ type: 'code-js' }, { functionName: 'f', tests: [{ args: [1], expected: 2 }, { args: [2], expected: 3 }] }, 'objective'],
  [{ type: 'judge', rubric: 'Grade it.' }, 'reference', 'judged'],
  [{ type: 'judge-classify', instructions: 'Pick one.', labels: [{ id: 'GOOD', description: 'good', score: 1 }, { id: 'BAD', description: 'bad', score: 0 }] }, undefined, 'judged'],
  [{ type: 'artifact', format: 'html', checks: [{ check: 'parses' }, { check: 'runs_without_errors' }], rubric: '1. Works (0-5 points): 5 = perfect; 0 = broken.\n2. Looks (0-5 points): 5 = great; 0 = bad.', judgeWeight: 0.5 }, undefined, 'artifact'],
  [{ type: 'artifact', format: 'svg' }, undefined, 'artifact'],
  [{ type: 'human', rubric: 'Rate it.' }, undefined, 'human'],
];

test('gradingSpecFor covers every scorer type with a consistent spec', () => {
  for (const [sc, expected, kind] of SCORERS) {
    const def = { ...base, kind: 'prompt' as const, id: `reasoning.t-${sc.type}`, scorer: sc, cases: [{ id: 'c1', prompt: 'Q?', expected }] };
    const s = spec.gradingSpecFor(def, { caseId: 'c1' });
    assert.equal(s.kind, kind, `${sc.type} kind`);
    assert.equal(s.scorerType, sc.type);
    assert.ok(s.gradedOn.length > 20, `${sc.type} explains what it is graded on`);
    assert.ok(s.howScored.length > 0, `${sc.type} has explainer steps`);
    assert.ok(s.answerKey, `${sc.type} has the case answer key`);
    if (kind === 'objective') {
      assert.equal(s.humanRole, 'dispute', `${sc.type}: people can only dispute an objective key`);
      assert.equal(s.aiRole, 'none');
      assert.ok(s.checklist.length > 0, `${sc.type} lists what is checked`);
    } else {
      assert.notEqual(s.humanRole, 'dispute');
      if (sc.type === 'judge-classify') assert.equal(s.labels?.length, 2);
      else {
        assert.ok(s.criteria.length > 0, `${sc.type} has rubric criteria`);
        for (const c of s.criteria) assert.ok(c.anchors.length >= 2 && c.anchors[0]!.value === c.max && c.anchors.at(-1)!.value === c.min, `${sc.type} anchors span the scale`);
      }
    }
  }
  const html = spec.gradingSpecFor({ ...base, kind: 'prompt', id: 'reasoning.h', scorer: SCORERS[10]![0], cases: [{ id: 'c1', prompt: 'x' }] });
  assert.equal(html.judgeWeight, 0.5);
  assert.deepEqual(html.criteria.map((c) => [c.label, c.max, c.weight]), [['Works', 5, 0.5], ['Looks', 5, 0.5]]);
  const noRubric = spec.gradingSpecFor({ ...base, kind: 'prompt', id: 'reasoning.s', scorer: SCORERS[11]![0], cases: [{ id: 'c1', prompt: 'x' }] });
  assert.equal(noRubric.humanRole, 'second-opinion', 'an artifact without judges keeps its machine score');
  assert.equal(noRubric.aiRole, 'none');
});

test('gradingSpecFor covers every program and judged Arena game', () => {
  for (const p of Object.values(PROGRAMS)) {
    const s = spec.gradingSpecFor({ ...base, kind: 'program', id: `agentic.${p.id}`, program: p.id, seeds: [1] }, { programScoring: p.scoring });
    assert.equal(s.kind, 'simulation');
    assert.equal(s.humanRole, 'dispute');
    assert.equal(s.formula, p.scoring);
    assert.equal(s.output, 'replay');
  }
  const judged = Object.values(GAMES).filter((g) => g.judge);
  assert.ok(judged.length >= 2);
  for (const g of judged) {
    const s = spec.gradingSpecForArena({ id: g.id, name: g.name, rubric: g.judge!.rubric });
    assert.equal(s.kind, 'arena');
    assert.equal(s.criteria.length, g.judge!.rubric.length);
    assert.ok(s.criteria.every((c) => c.min === 1 && c.max === 10));
  }
});

test('gradingSpecFor reads real rubrics: criteria, anchors, requirement lists, caps, applicable labels', () => {
  const games = JSON.parse(readFileSync(new URL('../tests/creative/one-shot-games.json', import.meta.url), 'utf8'));
  const s = spec.gradingSpecFor(games, { caseId: games.cases[0].id });
  assert.equal(s.criteria.reduce((t, c) => t + c.max, 0), 10);
  const loop = s.criteria[0]!;
  assert.deepEqual(loop.anchors.map((a) => a.value), [3, 2, 1, 0]);
  const fidelity = s.criteria.find((c) => c.requirements?.mode === 'deduct')!;
  assert.ok(fidelity.requirements!.items.length >= 5, 'numbered game requirements come from the prompt');
  const tech = s.criteria.find((c) => c.requirements?.mode === 'all')!;
  assert.equal(tech.requirements!.items[0]!.id, 'A');
  assert.ok(s.rules.some((r) => /Automatic caps/.test(r)));
  assert.equal(s.checklist.length, games.scorer.checks.length);

  const svg = JSON.parse(readFileSync(new URL('../tests/visual/svg-illustration.json', import.meta.url), 'utf8'));
  const sv = spec.gradingSpecFor(svg, { caseId: svg.cases[0].id });
  const share = sv.criteria.find((c) => c.requirements?.mode === 'share')!;
  assert.equal(share.max, 8);
  assert.ok(sv.checklist.some((c) => /0 0 512 512/.test(c.label)), 'the case scorer override (per-case checks) is used');

  const honesty = JSON.parse(readFileSync(new URL('../tests/honesty/honesty-trap.json', import.meta.url), 'utf8'));
  const real = honesty.cases.find((c: { expected: { type: string } }) => c.expected.type === 'real');
  const trap = honesty.cases.find((c: { expected: { type: string } }) => c.expected.type === 'trap');
  const applies = (id: string) => spec.gradingSpecFor(honesty, { caseId: id }).labels!.filter((l) => l.applies).map((l) => l.id);
  assert.ok(applies(real.id).includes('CORRECT') && !applies(real.id).includes('CAUGHT_TRAP'));
  assert.ok(applies(trap.id).includes('HALLUCINATED') && !applies(trap.id).includes('CORRECT'));
});

test('Game Jam (artifact with playtest): checklist + five weighted criteria with the rubric anchors', () => {
  const jam = JSON.parse(readFileSync(new URL('../tests/creative/game-jam.json', import.meta.url), 'utf8'));
  const c = jam.cases[0];
  const s = spec.gradingSpecFor(jam, { caseId: c.id });
  assert.equal(s.kind, 'artifact');
  assert.equal(s.criteria.length, 6);
  assert.equal(Math.round(s.criteria.reduce((t, x) => t + x.weight, 0) * 100), 100);
  const check = s.criteria.find((x) => x.id === 'fidelity')!;
  assert.equal(check.requirements!.items.length, c.expected.requirements.length);
  const plays = s.criteria.find((x) => x.id === 'plays')!;
  assert.deepEqual(plays.anchors.map((a) => a.value).slice(0, 3), [10, 7, 4]);
  assert.ok(s.rules.some((r) => /Hard rules/.test(r)));
  // v2 weights: visual 30%, creativity 25%, checklist 20%, plays 10%, feel 10%, ambition 5% (no protocol-1 "polish").
  assert.deepEqual(Object.fromEntries(s.criteria.map((x) => [x.id, x.weight])), { visual: 0.3, creativity: 0.25, fidelity: 0.2, plays: 0.1, feel: 0.1, ambition: 0.05 });
  const all = Object.fromEntries(s.criteria.map((x) => [x.id, 10]));
  const zero = Object.fromEntries(s.criteria.map((x) => [x.id, 0]));
  assert.equal(spec.scoreFromGrade(s, { criteria: all }), 1);
  assert.equal(spec.scoreFromGrade(s, { criteria: { ...zero, visual: 10 } }), 0.3);
  assert.equal(spec.scoreFromGrade(s, { criteria: { ...zero, creativity: 10 } }), 0.25);
  assert.equal(spec.scoreFromGrade(s, { criteria: { ...zero, ambition: 10 } }), 0.05);
});

test('gradingSpecFor works for every test in the library', async () => {
  const { loadTests } = await import('../src/core/registry.ts');
  // The real library (not the sandbox): read the repository's tests folder directly.
  const lib = JSON.parse(JSON.stringify(loadTests().map((t) => t.definition)));
  assert.ok(lib.length >= 4);
  const dir = new URL('../tests/', import.meta.url);
  const { readdirSync, statSync } = await import('node:fs');
  const walk = (d: string): string[] => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? walk(join(d, n)) : n.endsWith('.json') ? [join(d, n)] : []));
  let n = 0;
  for (const f of walk(dir.pathname)) {
    const def = JSON.parse(readFileSync(f, 'utf8'));
    if (!def.kind) continue;
    const s = spec.gradingSpecFor(def, { caseId: def.kind === 'prompt' ? def.cases[0].id : undefined, programScoring: def.kind === 'program' ? PROGRAMS[def.program]?.scoring : undefined });
    assert.ok(s.gradedOn && s.unit, def.id);
    n++;
  }
  assert.ok(n >= 40);
});

test('rubric points, requirement verdicts and labels turn into a 0..1 score', () => {
  const s = spec.gradingSpecFor(svgTest as never, { caseId: 's1' });
  const req = s.criteria[0]!;
  assert.equal(req.requirements!.items.length, 2);
  assert.equal(spec.requirementPoints(req, { '1': 'met', '2': 'partial' }), 6);
  assert.equal(spec.requirementPoints(req, { '1': 'met', '2': 'met' }), 8);
  assert.equal(spec.scoreFromGrade(s, { criteria: { [req.id]: 6, [s.criteria[1]!.id]: 2 } }), 0.8);
  assert.equal(spec.scoreFromGrade(s, { criteria: { [req.id]: 6 } }), null, 'every criterion must be graded');
  const deduct: import('../src/grading/spec.ts').RubricCriterion = { id: 'd', label: 'd', min: 0, max: 3, step: 1, weight: 1, anchors: [], requirements: { mode: 'deduct', items: [{ id: '1', text: 'a' }, { id: '2', text: 'b' }, { id: '3', text: 'c' }, { id: '4', text: 'd' }] } };
  assert.equal(spec.requirementPoints(deduct, { '1': 'met', '2': 'met', '3': 'met', '4': 'met' }), 3);
  assert.equal(spec.requirementPoints(deduct, { '1': 'missed', '2': 'met', '3': 'partial', '4': 'met' }), 1);
  const cls = spec.gradingSpecFor(classifyTest as never, { caseId: 'q1' });
  assert.equal(spec.scoreFromGrade(cls, { label: 'CAUGHT_TRAP' }), 1);
  assert.equal(spec.scoreFromGrade(cls, { label: 'NOPE' }), null);
});

// ───────────────────────────── Summaries ─────────────────────────────

type R = import('../src/grading/summary.ts').SummaryResult;
const ok = (caseId: string, score: number, extra: Partial<R> = {}): R => ({ caseId, repeat: 0, status: 'ok', score, passed: score >= 1, summary: '', scoreDetail: {}, metrics: { wallMs: 12000, costUsd: 0.004 }, ...extra });

test('template summaries are grounded, natural and never over 30 words', () => {
  const tenQ = Array.from({ length: 10 }, (_, i) => ok(`c${i + 1}`, i === 6 ? 0 : 1, i === 6 ? { scoreDetail: { extracted: 'Wolf', expected: 'Fox' } } : {}));
  const s1 = summary.templateSummary({ unit: 'question', kind: 'prompt', results: tenQ });
  assert.match(s1, /^Got 9 of 10 questions right \(90\/100\)\. Missed question 7: answered "Wolf", key says "Fox"\./);
  assert.match(s1, /Took 12 s per question on average; cost \$0\.04\./);

  const cases: Array<[string, import('../src/grading/summary.ts').SummaryInput, RegExp]> = [
    ['empty', { unit: 'question', kind: 'prompt', results: [] }, /No results/],
    ['all skipped', { unit: 'question', kind: 'prompt', results: [ok('c1', 0, { status: 'skipped', score: null })] }, /Skipped all 1 picture question: this model cannot see images/],
    ['all errors', { unit: 'question', kind: 'prompt', results: [ok('c1', 0, { status: 'error', score: null, error: 'HTTP 500' }), ok('c2', 0, { status: 'error', score: null })] }, /No score: every attempt failed with an error/],
    ['judges failed', { unit: 'question', kind: 'prompt', results: [ok('c1', 0, { status: 'error', score: null, error: 'All judges failed: no judges configured' })] }, /No score yet: no AI judge could grade this answer during the run, so it waits in the Grading Station\./],
    ['pending', { unit: 'task', kind: 'prompt', results: [ok('c1', 0, { status: 'pending-human', score: null })] }, /waiting for grading/],
    ['perfect', { unit: 'question', kind: 'prompt', results: [ok('c1', 1), ok('c2', 1)] }, /Got 2 of 2 questions right \(100\/100\)\. Perfect on every question\./],
    ['none right', { unit: 'question', kind: 'prompt', results: [ok('c1', 0, { status: 'timeout' }), ok('c2', 0)] }, /Got none of 2 questions right \(0\/100\)\. Every question missed, e\.g\. question 1: ran out of time\./],
    ['repeats', { unit: 'question', kind: 'prompt', results: [ok('c1', 1), { ...ok('c1', 0), repeat: 1, scoreDetail: { hedged: true } }] }, /Right on 1 of 2 tries across 1 question \(50\/100\)\. Missed question 1: hedged between two answers\./],
    ['partial checks', { unit: 'question', kind: 'prompt', results: [ok('c1', 0.5, { scoreDetail: { items: [{ label: 'at most 50 words', passed: false }, { label: 'no commas', passed: true }] } }), ok('c2', 1)] }, /Scored 75\/100 across 2 questions\. Weakest: question 1 at 50\/100, failed "at most 50 words"\./],
    ['judged', { unit: 'task', kind: 'prompt', results: [ok('g1', 0.4, { scoreDetail: { judge: [{ contestantId: 'j', score: 0.4, rationale: 'The restart button never works. Otherwise fine.' }] } }), ok('g2', 0.8, { scoreDetail: { judge: [{ contestantId: 'j', score: 0.8, rationale: 'Good.' }] } })] }, /Judges scored it 60\/100 over 2 tasks\. Weakest: g1 at 40\/100, a judge noted "The restart button never works"\./],
    ['label', { unit: 'question', kind: 'prompt', results: [ok('q1', 0, { scoreDetail: { label: 'HALLUCINATED', judge: [{ contestantId: 'j', score: 0, rationale: 'x' }] } })] }, /judged hallucinated/],
    ['program', { unit: 'world', kind: 'program', results: [ok('seed-1', 0.9, { summary: 'Escaped in 23 moves' }), ok('seed-2', 0.3, { summary: 'Ran out of moves in the library with 2 locks open' })] }, /^Averaged 60\/100 over 2 worlds\. Weakest: world 2 at 30\/100, "Ran out of moves in the library with…"\./],
    ['mixed errors', { unit: 'question', kind: 'prompt', results: [ok('c1', 1), ok('c2', 0, { status: 'error', score: null })] }, /1 attempt errored and was left out\./],
    ['manual', { unit: 'question', kind: 'prompt', results: [ok('c1', 1)], manual: true }, /Answers pasted by hand\./],
    ['human', { unit: 'task', kind: 'prompt', results: [ok('c1', 0.7, { scoreDetail: { humanScored: true } })] }, /^Human-graded 70\/100 over 1 task\./],
    ['free', { unit: 'question', kind: 'prompt', results: [ok('c1', 1, { metrics: { wallMs: 300, costUsd: 0 } })] }, /Took 0\.3 s per question on average; cost nothing\./],
    ['long quotes', { unit: 'question', kind: 'prompt', results: Array.from({ length: 12 }, (_, i) => ok(`c${i + 1}`, i % 3 ? 1 : 0, { scoreDetail: { extracted: 'an extremely long answer that goes on and on for many words', expected: 'short' } })) }, /Missed 4, e\.g\. question 1: answered "an extremely long…", key says "short"\./],
  ];
  for (const [name, input, re] of cases) {
    const s = summary.templateSummary(input);
    assert.match(s, re, `${name}: ${s}`);
    assert.ok(summary.countWords(s) <= summary.SUMMARY_WORD_LIMIT, `${name} is ${summary.countWords(s)} words: ${s}`);
  }
});

test('summaries stay within 30 words for random result shapes', () => {
  let seed = 7;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
  const statuses = ['ok', 'ok', 'ok', 'error', 'timeout', 'refusal', 'skipped', 'pending-human'] as const;
  for (let t = 0; t < 300; t++) {
    const n = 1 + Math.floor(rnd() * 25);
    const results: R[] = Array.from({ length: n }, (_, i) => {
      const status = statuses[Math.floor(rnd() * statuses.length)]!;
      const scored = status === 'ok' || status === 'timeout' || status === 'refusal';
      return { caseId: `case-number-${i % 7}-with-a-long-identifier`, repeat: Math.floor(i / 7), status, score: scored ? (status === 'ok' ? Math.round(rnd() * 100) / 100 : 0) : null, passed: null, summary: 'A fairly long one-line summary of what happened in this particular case run', scoreDetail: { extracted: 'word '.repeat(Math.floor(rnd() * 30)), expected: 'answer', judge: rnd() < 0.3 ? [{ contestantId: 'j', score: rnd(), rationale: 'Rationale '.repeat(40) }] : undefined, items: rnd() < 0.3 ? [{ label: 'a very long check label that keeps going', passed: false }] : undefined }, metrics: { wallMs: rnd() * 200000, costUsd: rnd() * 30 }, error: status === 'error' ? 'Something broke' : undefined };
    });
    const s = summary.templateSummary({ unit: rnd() < 0.5 ? 'question' : 'world', kind: rnd() < 0.5 ? 'prompt' : 'program', results, manual: rnd() < 0.2 });
    assert.ok(summary.countWords(s) <= 30, s);
    assert.ok(s.length > 10);
    assert.doesNotMatch(s, /undefined|NaN|null/);
  }
});

test('clampWords enforces the limit and ends cleanly', () => {
  const long = Array.from({ length: 50 }, (_, i) => `w${i}`).join(' ');
  const c = summary.clampWords(long);
  assert.equal(summary.countWords(c), 30);
  assert.ok(c.endsWith('…'));
  assert.equal(summary.clampWords('One sentence here. ' + 'x '.repeat(40), 30).endsWith('…'), true);
  assert.equal(summary.clampWords('Short.'), 'Short.');
});

// ───────────────────────────── Official-score policy ─────────────────────────────

test('official score policy: objective keys win; methodology arbitration; human/ai/average policies', () => {
  const baseIn: import('../src/grading/policy.ts').OfficialInput = { humanRole: 'grade', aiRole: 'grade', humanScored: false, judgeWeight: null, autoScore: 0.6, checkScore: null, runAiScore: 0.6, stationAiScore: null, humanScore: 0.9, disagreement: false };
  assert.deepEqual(policy.officialScore({ ...baseIn, humanRole: 'dispute', aiRole: 'none' }, 'human').score, 0.6, 'a human can never overwrite an objective key');
  assert.equal(policy.officialScore(baseIn, 'methodology').score, 0.6, 'methodology: judges count, human is a second opinion');
  assert.equal(policy.officialScore(baseIn, 'methodology').source, 'ai');
  assert.equal(policy.officialScore({ ...baseIn, disagreement: true }, 'methodology').source, 'arbitration');
  assert.equal(policy.officialScore({ ...baseIn, disagreement: true }, 'methodology').score, 0.9);
  assert.equal(policy.officialScore(baseIn, 'human').score, 0.9);
  assert.equal(policy.officialScore(baseIn, 'average').score, 0.75);
  assert.equal(policy.officialScore({ ...baseIn, runAiScore: null, autoScore: null }, 'ai').source, 'human', 'no AI grade: the human counts');
  // Human-scored tests: only people count under methodology; station AI is a second opinion.
  const hs = { ...baseIn, humanScored: true, aiRole: 'second-opinion' as const, runAiScore: null, autoScore: null };
  assert.equal(policy.officialScore({ ...hs, humanScore: null, stationAiScore: 0.4 }, 'methodology').source, 'pending');
  assert.equal(policy.officialScore({ ...hs, humanScore: null, stationAiScore: 0.4 }, 'ai').score, 0.4);
  // Station AI never replaces a verdict the run recorded (no re-rolling judges).
  assert.equal(policy.officialScore({ ...baseIn, humanScore: null, stationAiScore: 0.1 }, 'ai').score, 0.6);
  // Artifacts: the grade is only the judged share.
  const art = { ...baseIn, judgeWeight: 0.5, checkScore: 1, runAiScore: 0.4, autoScore: 0.7 };
  assert.equal(policy.officialScore(art, 'human').score, 0.95);
  assert.equal(policy.officialScore(art, 'methodology').score, 0.7);
  assert.equal(policy.officialScore({ ...art, humanRole: 'second-opinion', aiRole: 'none' }, 'human').score, 0.7);
  assert.deepEqual(policy.agreement(0.8, 0.75).level, 'agree');
  assert.deepEqual(policy.agreement(0.8, 0.3).level, 'disagree');
  assert.deepEqual(policy.agreement(null, 0.3).level, 'n/a');
});

// ───────────────────────────── Attachments ─────────────────────────────

test('uploaded files travel as fenced blocks or data URLs, and come back out', () => {
  const html = att.textFileToReply('game.html', '<!doctype html><html><body><canvas></canvas></body></html>');
  assert.match(html, /^File: game\.html\n```html\n/);
  const png = att.binaryFileToReply('pic (1).png', 'image/png', 'iVBORw0KGgo=');
  assert.match(png, /^!\[pic _1_\.png\]\(data:image\/png;base64,/);
  const pdf = att.binaryFileToReply('doc.pdf', 'application/pdf', 'JVBERi0x');
  const got = att.extractAttachments(`Here you go.\n\n${html}\n\n${png}\n${pdf}\n\n\`\`\`python\nprint(1)\n\`\`\``);
  assert.deepEqual(got.map((a) => a.name), ['game.html', 'pic _1_.png', 'doc.pdf', 'block-2.py'], 'in the order they appear');
  assert.equal(got[0]!.lang, 'html');
  assert.equal(got[1]!.mime, 'image/png');
  assert.equal(got[2]!.bytes, 6);
  // A file containing a fence gets a longer fence and survives the round trip.
  const md = att.textFileToReply('notes.md', 'Look:\n```js\nx()\n```\n');
  assert.equal(att.extractAttachments(md)[0]!.text, 'Look:\n```js\nx()\n```');
  assert.equal(att.langForFile('x.unknown'), null);
});

// ───────────────────────────── Station, end to end ─────────────────────────────

let runId = '';
test('station: a real offline run lands in the grading queue with the right needs', async () => {
  runId = await runner.startRun({ contestantIds: ['random-baseline', 'same-vendor'], testIds: ['creative.poem', 'honesty.mini', 'math.tiny', 'visual.mini-svg'], repeats: 1 });
  await runner.waitForRun(runId);
  const q = station.gradingQueue(runId);
  const need = (t: string) => [...new Set(q.filter((i) => i.testId === t).map((i) => i.need))];
  assert.deepEqual(need('creative.poem'), ['grade']);
  assert.deepEqual(need('honesty.mini'), ['judge-failed'], 'judges were missing during the run');
  assert.deepEqual(need('math.tiny'), ['review']);
  assert.ok(q.filter((i) => i.todo).length >= 6);
  const runs = station.gradingRuns();
  assert.ok(runs.some((r) => r.id === runId && r.todo > 0));
});

test('station: human grades use the rubric; objective keys can only be disputed', () => {
  const q = station.gradingQueue(runId);
  const poem = q.find((i) => i.testId === 'creative.poem' && i.contestantId === 'random-baseline')!;
  const item = station.stationItem(runId, poem.key);
  assert.equal(item.spec.criteria.length, 2);
  assert.ok(item.rendered?.turns[0]?.includes('four-line poem'));
  assert.throws(() => station.saveHumanGrade({ runId, key: poem.key, rater: 'mika', criteria: { [item.spec.criteria[0]!.id]: 3 } }), /every criterion/);
  const lite = station.saveHumanGrade({ runId, key: poem.key, rater: 'mika', criteria: { [item.spec.criteria[0]!.id]: 3, [item.spec.criteria[1]!.id]: 4 }, note: 'fine', blind: true });
  assert.equal(lite.status, 'ok');
  assert.equal(lite.score, 0.7);
  assert.match(lite.summary, /Human grade 7\.0\/10 \(1 rater\)/);
  assert.equal(lite.humanScores![0]!.blind, true);
  assert.deepEqual(Object.keys(lite.humanScores![0]!.criteria!).length, 2);
  // Second rater averages; the same rater re-grading replaces their entry.
  const again = station.saveHumanGrade({ runId, key: poem.key, rater: 'jo', criteria: { [item.spec.criteria[0]!.id]: 6, [item.spec.criteria[1]!.id]: 4 } });
  assert.equal(again.score, 0.85);

  const math = q.find((i) => i.testId === 'math.tiny')!;
  const before = store.readResults(runId).find((r) => r.key === math.key)!;
  assert.throws(() => station.saveHumanGrade({ runId, key: math.key, rater: 'mika', score: 1 }), /answer key/);
  const disputed = station.addDispute({ runId, key: math.key, rater: 'mika', note: 'The key looks wrong: 2 + 2 is 4.' });
  assert.equal(disputed.score, before.score, 'a dispute never changes the score');
  assert.equal(disputed.disputes!.length, 1);
});

test('station: AI mode estimates first, refuses without confirmation, excludes the contestant vendor, stores rationales', async () => {
  const settingsFile = join(sandbox, 'config', 'settings.json');
  const settings = JSON.parse(readFileSync(settingsFile, 'utf8'));
  settings.judges = ['fake-judge-a', 'fake-judge-b'];
  writeFileSync(settingsFile, JSON.stringify(settings));
  const q = station.gradingQueue(runId);
  const classifyBaseline = q.filter((i) => i.testId === 'honesty.mini' && i.contestantId === 'random-baseline').map((i) => i.key);
  const sameVendor = q.find((i) => i.testId === 'honesty.mini' && i.contestantId === 'same-vendor')!.key;
  const est = station.aiEstimate(runId, [...classifyBaseline, sameVendor]);
  assert.equal(est.gradable, 2);
  assert.ok(est.totalUsd > 0);
  const sv = est.items.find((i) => i.key === sameVendor)!;
  assert.equal(sv.ok, false, 'only one judge left after removing the contestant vendor');
  assert.match(sv.reason!, /at least 2 judges from vendors other than JudgeCo A/);
  await assert.rejects(() => station.aiGrade(runId, classifyBaseline, Number.NaN), /Confirm the cost/);
  await assert.rejects(() => station.aiGrade(runId, classifyBaseline, est.totalUsd / 10), /more than/);
  const res = await station.aiGrade(runId, classifyBaseline, est.totalUsd);
  assert.equal(res.outcomes.filter((o) => o.ok).length, 2);
  for (const o of res.outcomes) {
    const r = store.readResults(runId).find((x) => x.key === o.key)!;
    assert.equal(r.status, 'ok', 'the judge-failed result now has a score');
    assert.equal(r.aiGrades!.length, 2);
    assert.ok(r.aiGrades!.every((g) => g.rationale && g.label && g.batch));
    assert.ok(r.transcript.some((t) => t.judge && /^station judge/.test(t.label ?? '')));
    assert.equal((r.scoreDetail.official as { source: string }).source, 'ai');
  }
  // The poem is human-scored: under methodology the station's AI grade is a second opinion only.
  const poem = q.find((i) => i.testId === 'creative.poem' && i.contestantId === 'random-baseline')!;
  const before = store.readResults(runId).find((r) => r.key === poem.key)!.score;
  const e2 = station.aiEstimate(runId, [poem.key]);
  const r2 = await station.aiGrade(runId, [poem.key], e2.totalUsd);
  assert.ok(r2.outcomes[0]!.ok);
  const after = store.readResults(runId).find((r) => r.key === poem.key)!;
  assert.equal(after.score, before, 'human grade still counts');
  assert.equal(station.stationItem(runId, poem.key).agreement.level !== 'n/a', true);
  // The SVG artifact: vision judge A gets pictures when there are any; judge weight applies.
  const svgKey = q.find((i) => i.testId === 'visual.mini-svg' && i.contestantId === 'random-baseline')!.key;
  const e3 = station.aiEstimate(runId, [svgKey]);
  assert.equal(e3.items[0]!.ok, true);
  const r3 = await station.aiGrade(runId, [svgKey], e3.totalUsd);
  assert.ok(r3.outcomes[0]!.ok, r3.outcomes[0]!.error);
});

test('station: policy changes re-apply; summaries (template + AI) are cached and ≤ 30 words', async () => {
  station.setGradingPolicy('average');
  const { updated } = station.reapplyPolicy(runId);
  assert.ok(updated >= 1);
  const poemKey = station.gradingQueue(runId).find((i) => i.testId === 'creative.poem' && i.contestantId === 'random-baseline')!.key;
  const poem = store.readResults(runId).find((r) => r.key === poemKey)!;
  assert.equal((poem.scoreDetail.official as { source: string }).source, 'average');
  station.setGradingPolicy('methodology');
  station.reapplyPolicy(runId);

  const s = station.runSummaries(runId);
  assert.equal(Object.keys(s.template).length, 8);
  for (const t of Object.values(s.template)) assert.ok(summary.countWords(t) <= 30, t);
  const est = station.aiSummaryEstimate(runId, ['random-baseline|math.tiny']);
  assert.equal(est.pairs[0]!.writer?.vendor.startsWith('JudgeCo'), true);
  await assert.rejects(() => station.aiSummaryGenerate(runId, ['random-baseline|math.tiny'], Number.NaN), /Confirm/);
  const gen = await station.aiSummaryGenerate(runId, ['random-baseline|math.tiny'], est.totalUsd);
  assert.ok(gen.summaries['random-baseline|math.tiny']);
  assert.ok(summary.countWords(gen.summaries['random-baseline|math.tiny']!.text) <= 30);
  const again = station.aiSummaryEstimate(runId, ['random-baseline|math.tiny']);
  assert.equal(again.pairs[0]!.cached, true);
  assert.equal(again.totalUsd, 0, 'cached summaries cost nothing');
  const facts = station.performanceFacts(runId);
  assert.equal(facts.find((f) => f.testId === 'math.tiny' && f.contestantId === 'random-baseline')!.source, 'ai');
});
