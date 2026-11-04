// Scores every json-scored test in the library with the real scorer against a fixed battery of answers (the key,
// the key re-presented, do-nothing, a copy of the prompt, random filler and every scripted answer the generators
// keep) and prints one stable JSON line per case. Used to prove that a change to the json scorer's options moved
// nothing: run it before and after, then diff.   node verification/json-options-proof.mts > /tmp/scores.jsonl
import { readFileSync, existsSync } from 'node:fs';
import { loadTests, caseScorer, renderCase } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { createRng } from '../src/core/rng.ts';
import type { PromptTest } from '../src/core/types.ts';
// @ts-expect-error plain JavaScript generator module (no types)
import { MODULES as L1 } from './realworld-l1/build.mjs';

const read = (p: string) => (existsSync(new URL(p, import.meta.url)) ? JSON.parse(readFileSync(new URL(p, import.meta.url), 'utf8')) : {});
const scripted: Record<string, Record<string, Record<string, unknown>>> = { ...read('./realworld-work/scripted.json'), ...read('./realworld-business/scripted.json') };
for (const m of L1 as Array<{ META: { id: string }; generate(): Array<{ case: { id: string }; careless: unknown }> }>) {
  scripted[m.META.id] = Object.fromEntries(m.generate().map((g) => [g.case.id, { careless: g.careless }]));
}

const only = process.argv[2] ? new RegExp(process.argv[2]) : null;
const words = 'the model result answer benchmark quickly random value system data story river light lamp order help please thanks store product price name city year'.split(' ');
for (const t of loadTests()) {
  const d = t.definition;
  if (d.kind !== 'prompt' || (only && !only.test(d.id))) continue;
  for (const c of d.cases) {
    const sc = caseScorer(d as PromptTest, c);
    if (sc.type !== 'json') continue;
    const task = renderCase(d as PromptTest, c).turns.join('\n');
    const answers: Record<string, string> = {
      key: '```json\n' + JSON.stringify(c.expected, null, 2) + '\n```',
      key_unfenced: `Here you go: ${JSON.stringify(c.expected)} Thanks.`,
      nothing: '```json\n{}\n```',
      copy: task,
    };
    for (let k = 0; k < 3; k++) {
      const rng = createRng(500 + k);
      answers[`filler${k}`] = Array.from({ length: 30 + rng.int(0, 90) }, () => rng.pick(words)).join(' ') + '.';
    }
    for (const [kind, ans] of Object.entries(scripted[d.id]?.[c.id] ?? {})) if (ans !== undefined) answers[kind] = '```json\n' + JSON.stringify(ans) + '\n```';
    const scores: Record<string, number | null> = {};
    for (const [kind, response] of Object.entries(answers)) {
      const out = await scoreResponse({ scorer: sc, expected: c.expected, response, stopReason: 'end', taskText: task, judges: { ids: [], ask: async () => [] }, saveArtifact: (name) => ({ name, kind: 'text', file: name, bytes: 0 }), signal: new AbortController().signal });
      scores[kind] = out.score;
    }
    console.log(JSON.stringify({ test: d.id, case: c.id, scores }));
  }
}
