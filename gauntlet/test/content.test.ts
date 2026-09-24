import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadSuites, loadTests, renderCase, caseScorer, validateTest } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import type { ArtifactRef } from '../src/core/types.ts';

/**
 * Library integrity: every built-in test validates, and every answer key is
 * self-consistent with its scorer (a model that states the expected answer in
 * the required format gets full marks). This catches typos in answer keys.
 */

const all = loadTests();

test('the library is not empty', () => {
  assert.ok(all.length > 0);
});

test('every test definition validates', () => {
  const problems: string[] = [];
  for (const t of all) {
    const errs = validateTest(t.definition, all, { selfFile: t.file });
    if (errs.length) problems.push(`${t.file}: ${errs.join('; ')}`);
  }
  assert.deepEqual(problems, []);
});

test('every suite references existing tests and cases', () => {
  const byId = new Map(all.map((t) => [t.definition.id, t]));
  const missing: string[] = [];
  for (const s of loadSuites()) {
    for (const e of s.tests) {
      if (e.id === '*') continue;
      const t = byId.get(e.id);
      if (!t) {
        missing.push(`${s.id} → ${e.id}`);
        continue;
      }
      const d = t.definition;
      const known = d.kind === 'prompt' ? d.cases.map((c) => c.id) : d.seeds.map((x) => `seed-${x}`);
      for (const cid of e.cases ?? []) if (!known.includes(cid)) missing.push(`${s.id} → ${e.id}/${cid}`);
    }
  }
  assert.deepEqual(missing, []);
});

const noJudges = { ids: [], ask: async () => [] };
const noArtifacts = (name: string): ArtifactRef => ({ name, kind: 'text', file: name, bytes: 0 });

test('answer keys score 100% when stated correctly', async () => {
  const failures: string[] = [];
  for (const t of all) {
    const d = t.definition;
    if (d.kind !== 'prompt') continue;
    for (const c of d.cases) {
      const scorer = caseScorer(d, c);
      let response: string | null = null;
      if (scorer.type === 'exact') response = `FINAL ANSWER: ${Array.isArray(c.expected) ? c.expected[0] : c.expected}`;
      else if (scorer.type === 'number' || scorer.type === 'choice') response = `Working...\nFINAL ANSWER: ${c.expected}`;
      else if (scorer.type === 'json') response = '```json\n' + JSON.stringify(c.expected, null, 2) + '\n```';
      if (response === null) continue;
      const out = await scoreResponse({
        scorer,
        expected: c.expected,
        response,
        stopReason: 'end',
        taskText: renderCase(d, c).turns.join('\n'),
        judges: noJudges,
        saveArtifact: noArtifacts,
        signal: new AbortController().signal,
      });
      if (out.score !== 1) failures.push(`${d.id}/${c.id}: scored ${out.score} (${out.summary})`);
    }
  }
  assert.deepEqual(failures, []);
});

test('rendered prompts of answer-format tests end with the standard answer instruction', () => {
  for (const t of all) {
    const d = t.definition;
    if (d.kind !== 'prompt') continue;
    for (const c of d.cases) {
      const s = caseScorer(d, c);
      const r = renderCase(d, c);
      const last = r.turns[r.turns.length - 1]!;
      if (s.type === 'exact' || s.type === 'number' || s.type === 'choice') assert.match(last, /FINAL ANSWER: <answer>$/, `${d.id}/${c.id}`);
    }
  }
});

test('random filler scores near zero on every automatically graded test (tests measure skill, not luck)', async () => {
  const { createRng } = await import('../src/core/rng.ts');
  const words = 'the model result answer benchmark quickly random value system data story river light lamp order help please thanks store product price name city year'.split(' ');
  const offenders: string[] = [];
  for (const t of all) {
    const d = t.definition;
    if (d.kind !== 'prompt') continue;
    let sum = 0;
    let n = 0;
    for (const c of d.cases) {
      const scorer = caseScorer(d, c);
      if (['judge', 'judge-classify', 'artifact', 'human', 'code-js'].includes(scorer.type)) continue;
      for (let k = 0; k < 4; k++) {
        const rng = createRng(1000 + k * 31 + n);
        const filler = Array.from({ length: 20 + rng.int(0, 120) }, () => rng.pick(words)).join(' ') + '.';
        const response = k % 2 ? filler : `${filler}\nFINAL ANSWER: ${rng.int(0, 99)}`;
        const out = await scoreResponse({ scorer, expected: c.expected, response, stopReason: 'end', taskText: '', judges: noJudges, saveArtifact: noArtifacts, signal: new AbortController().signal });
        sum += out.score ?? 0;
        n++;
      }
    }
    if (n && sum / n > 0.1) offenders.push(`${d.id}: ${((sum / n) * 100).toFixed(1)}%`);
  }
  assert.deepEqual(offenders, []);
});
