/** Grading Station UI logic: keyboard grading on real rubrics, and the JSON answer-vs-key diff. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { gradingSpecFor } from '../src/grading/spec.ts';
import { applyKey, draftScore, emptyDraft, isStopDone, nudge, stopsFor, type RubricDraft } from '../ui/src/grading/rubricKeys.ts';
import { jsonDiff } from '../ui/src/components/viewer/jsonDiff.ts';

const load = (p: string) => JSON.parse(readFileSync(new URL(`../tests/${p}.json`, import.meta.url), 'utf8'));

/** Press keys one after another the way the station does: a key that sets a value moves to the next stop. */
function press(spec: ReturnType<typeof gradingSpecFor>, keys: string[]): RubricDraft {
  const stops = stopsFor(spec);
  let d = emptyDraft();
  let focus = 0;
  for (const k of keys) {
    const next = applyKey(spec, d, stops[focus], k);
    if (next) {
      d = next;
      focus = Math.min(stops.length - 1, focus + 1);
    }
  }
  return d;
}

test('a whole game is graded from the keyboard: points, requirement verdicts, all-or-nothing lines', () => {
  const games = load('creative/one-shot-games');
  const spec = gradingSpecFor(games, { caseId: 'g01' });
  const stops = stopsFor(spec);
  // 1 core loop + 6 game requirements + 3 single-point lines + 6 technical requirements.
  assert.equal(stops.length, 16);
  const d = press(spec, ['3', '1', '1', '1', '0', '1', '1', '1', '1', '0', '1', '1', '1', '1', '1', '1']);
  assert.ok(stops.every((s) => isStopDone(spec, d, s)));
  // 3 + (3 − 1 missing) + 1 + 1 + 0 + 1 (all A–F met) = 8 / 10
  assert.equal(draftScore(spec, d), 0.8);
  assert.equal(applyKey(spec, d, stops[0], '7'), null, 'a digit above the line maximum does nothing');
  assert.equal(applyKey(spec, d, stops[1], '2'), null, 'half credit only exists where the rubric gives it');
});

test('0–10 scales: 1–9, 0 = 10, X = zero, arrows nudge by half points', () => {
  const def = { kind: 'prompt', id: 'creative.x', version: '1.0.0', name: 'X', category: 'creative', description: 'd', difficulty: 'easy', scorer: { type: 'human', rubric: 'Rate it.' }, cases: [{ id: 'c1', prompt: 'p' }] } as const;
  const spec = gradingSpecFor(def as never, { caseId: 'c1' });
  const [stop] = stopsFor(spec);
  assert.equal(applyKey(spec, emptyDraft(), stop, '0')!.criteria.overall, 10);
  assert.equal(applyKey(spec, emptyDraft(), stop, 'x')!.criteria.overall, 0);
  const seven = applyKey(spec, emptyDraft(), stop, '7')!;
  assert.equal(nudge(spec, seven, stop, 1).criteria.overall, 7.5);
  assert.equal(draftScore(spec, nudge(spec, seven, stop, -1)), 0.65);
});

test('labels: digits pick the numbered label; arrows move through them', () => {
  const honesty = load('honesty/honesty-trap');
  const spec = gradingSpecFor(honesty, { caseId: honesty.cases[0].id });
  const [stop] = stopsFor(spec);
  const d = applyKey(spec, emptyDraft(), stop, '3')!;
  assert.equal(d.label, spec.labels![2]!.id);
  assert.equal(draftScore(spec, d), spec.labels![2]!.score);
  assert.equal(nudge(spec, d, stop, 1).label, spec.labels![3]!.id);
  assert.equal(applyKey(spec, emptyDraft(), stop, '9'), null);
});

test('jsonDiff marks matching, wrong, missing and extra fields', () => {
  const rows = jsonDiff({ a: 'Paris ', b: 2, c: { d: [1, 2] }, e: 'x' }, { a: 'paris', b: 3, c: { d: [1] }, f: true });
  const state = Object.fromEntries(rows.map((r) => [r.path, r.state]));
  assert.deepEqual(state, { a: 'match', b: 'wrong', 'c.d[0]': 'match', 'c.d[1]': 'missing', e: 'missing', f: 'extra' });
});
