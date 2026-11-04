// Scores the scripted answers in scripted.json (and do-nothing / reference answers) with the REAL scorer and prints
// the expected spread per test and tier: node verification/realworld-business/spread.mts
import { readFileSync } from 'node:fs';
import { loadTests, renderCase } from '../../src/core/registry.ts';
import { scoreResponse } from '../../src/scoring/index.ts';
import type { PromptTest } from '../../src/core/types.ts';

const scripted = JSON.parse(readFileSync(new URL('./scripted.json', import.meta.url), 'utf8')) as Record<string, Record<string, Record<string, unknown>>>;
const ids = Object.keys(scripted);
const tests = loadTests().filter((t) => ids.includes(t.definition.id));

export async function score(def: PromptTest, caseId: string, response: string): Promise<number> {
  const c = def.cases.find((x) => x.id === caseId)!;
  const out = await scoreResponse({
    scorer: c.scorer ?? def.scorer,
    expected: c.expected,
    response,
    stopReason: 'end',
    taskText: renderCase(def, c).turns.join('\n'),
    judges: { ids: [], ask: async () => [] },
    saveArtifact: (name) => ({ name, kind: 'text', file: name, bytes: 0 }),
    signal: new AbortController().signal,
  });
  return out.score ?? 0;
}

const fmt = (x: number) => `${(x * 100).toFixed(0)}%`.padStart(5);
for (const t of tests) {
  const def = t.definition as PromptTest;
  const kinds = new Set<string>(['reference', 'do-nothing']);
  for (const c of Object.values(scripted[def.id]!)) for (const k of Object.keys(c)) kinds.add(k);
  console.log(`\n${def.id}`);
  for (const kind of kinds) {
    const byTier: Record<string, number[]> = {};
    for (const c of def.cases) {
      const tier = /^\[(\w+)\]/.exec(c.notes ?? '')?.[1] ?? '?';
      const ans = kind === 'reference' ? c.expected : kind === 'do-nothing' ? {} : scripted[def.id]![c.id]![kind];
      if (ans === undefined) continue;
      const s = await score(def, c.id, '```json\n' + JSON.stringify(ans) + '\n```');
      (byTier[tier] ??= []).push(s);
    }
    const all = Object.values(byTier).flat();
    const avg = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / Math.max(1, xs.length);
    console.log(`  ${kind.padEnd(30)} overall ${fmt(avg(all))}  easy ${fmt(avg(byTier.easy ?? []))}  medium ${fmt(avg(byTier.medium ?? []))}  hard ${fmt(avg(byTier.hard ?? []))}`);
  }
}
