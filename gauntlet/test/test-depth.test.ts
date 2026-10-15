import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadTests } from '../src/core/registry.ts';
import { describeMarking, testDepth } from '../src/core/test-depth.ts';
import type { PromptTest, ScorerSpec, TestDefinition } from '../src/core/types.ts';

/** "See in depth" is built from each test's real definition, so every test in the library must produce it. */
test('every built-in test gets steps, marking rules and key numbers', () => {
  const all = loadTests().filter((t) => t.source !== 'custom');
  assert.ok(all.length > 40, `expected the whole library, got ${all.length}`);
  for (const t of all) {
    const prog = t.definition.kind === 'program' ? 'Scored by the program.' : undefined;
    const d = testDepth(t.definition, prog);
    assert.ok(d.steps.length >= 2, `${t.definition.id}: needs at least 2 steps`);
    assert.ok(d.marking.length >= 1, `${t.definition.id}: needs at least one marking rule`);
    assert.ok(d.facts.length >= 2, `${t.definition.id}: needs key numbers`);
    for (const s of [...d.steps, ...d.marking]) {
      const text = typeof s === 'string' ? s : s.detail;
      assert.ok(text.length > 10 && !/undefined|NaN|\[object/.test(text), `${t.definition.id}: bad text "${text}"`);
    }
  }
});

test('marking rules say what each scorer really does', () => {
  const m = (scorer: ScorerSpec) => describeMarking(scorer).join(' ');
  assert.match(m({ type: 'number', tolerance: 0.001 }), /exact number.*within 0\.001/);
  assert.match(m({ type: 'choice' }), /one correct option/);
  assert.match(m({ type: 'contains', all: ['cave'], none: ['guess'] }), /Must mention: “cave”.*Must NOT mention: “guess”/);
  assert.match(m({ type: 'json', allOrNothing: true }), /or the answer scores zero/);
  assert.match(m({ type: 'json' }), /share of fields right/);
  assert.match(m({ type: 'constraints' }), /share of rules met/);
  assert.match(m({ type: 'judge', rubric: 'Be clear.', passThreshold: 6 }), /AI judges.*Be clear\./);
  assert.match(m({ type: 'human', rubric: 'Taste.' }), /Blind Review/);
  assert.match(m({ type: 'ladder', answer: 'grid' }), /cell by cell/);
});

test('a long rubric is shortened for the reader, never cut mid-word', () => {
  const long = 'word '.repeat(400);
  const out = describeMarking({ type: 'judge', rubric: long })[0]!;
  assert.ok(out.length < 800, 'shortened');
  assert.ok(out.endsWith('…'));
});

test('a multi-turn, picture test says so in its steps', () => {
  const def: PromptTest = {
    kind: 'prompt',
    id: 'x.y',
    version: '1.0.0',
    name: 'X',
    category: 'x',
    description: 'd',
    difficulty: 'hard',
    tags: [],
    scorer: { type: 'exact' },
    cases: [
      { id: 'a', turns: ['one', 'two'], expected: 'A', images: ['p.png'] },
      { id: 'b', prompt: 'hi', expected: 'B' },
    ],
  } as unknown as PromptTest;
  const d = testDepth(def as TestDefinition);
  assert.ok(d.steps.some((s) => s.title === 'Several turns'));
  assert.ok(d.steps.some((s) => s.title === 'Pictures'));
  assert.equal(d.facts.find((f) => f.label === 'Questions')?.value, '2');
  assert.ok(d.steps.some((s) => /FINAL ANSWER/.test(s.detail)));
});
