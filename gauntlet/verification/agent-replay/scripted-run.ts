/**
 * Builds a verification run where scripted players (the unit-test helpers)
 * play the eight agent & social simulation cases, so the replays show
 * rescues, escapes, profits and caught liars — not only the Random Baseline's
 * flailing. It copies an existing run of those eight tests (made with the
 * Random Baseline) and replaces every result with a scripted play of the same
 * seed. The contestant is relabelled "Scripted player (test helper)" so the
 * screens never pass it off as a real model.
 *
 * Usage (GAUNTLET_DATA_DIR set like the server's):
 *   node verification/agent-replay/scripted-run.ts <sourceRunId> [newRunId]
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createRng } from '../../src/core/rng.ts';
import type { CaseResult, ProgramDefinition, RunManifest } from '../../src/core/types.ts';
import { runDir } from '../../src/engine/store.ts';
import { program as island } from '../../src/programs/survival-island.ts';
import { program as escape } from '../../src/programs/escape-room.ts';
import { program as startup } from '../../src/programs/startup-sim.ts';
import { program as liars, buildWorld, readConfig } from '../../src/programs/liars-table.ts';
import { ISLAND_DEFAULTS, type IslandConfig } from '../../src/programs/lib/agentic-island.ts';
import { ESCAPE_DEFAULTS, generateEscape, type EscapeConfig } from '../../src/programs/lib/agentic-escape.ts';
import { createFakeModel, createTestContext, type Responder } from '../../test/helpers/fake-model.ts';
import { islandResponder, startupHeuristicResponder } from '../../test/helpers/agentic-policies.ts';
import { examinerPolicy } from '../../test/helpers/liars-table-policies.ts';

const src = process.argv[2];
if (!src) throw new Error('usage: scripted-run.ts <sourceRunId> [newRunId]');
const dst = process.argv[3] ?? `${src}-scripted`;

/** Escape: the optimal plan, but a first wrong guess before every code (a human-like slip) and a LOOK now and then. */
function sloppyEscape(seed: number, cfg: EscapeConfig): Responder {
  const world = generateEscape(createRng(seed), cfg);
  const script: string[] = [];
  world.plan.forEach((cmd, i) => {
    const m = cmd.match(/^ENTER (.+) ON (.+)$/);
    if (m && i % 2 === 0) {
      const v = m[1]!;
      const wrong = /^\d+$/.test(v) ? v.split('').reverse().join('') === v ? `${v.slice(0, -1)}${(Number(v.at(-1)) + 1) % 10}` : v.split('').reverse().join('') : v.split(' ').reverse().join(' ');
      script.push(`ENTER ${wrong === v ? `${v}X` : wrong} ON ${m[2]}`);
    }
    if (i === 3) script.push('LOOK');
    script.push(cmd);
  });
  let i = 0;
  return () => `Working through the clues.\nACTION: ${script[i++] ?? 'LOOK'}`;
}

function play(program: ProgramDefinition, seed: number, config: Record<string, unknown>, responder: Responder) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config });
  return program.run(ctx);
}

function testConfig(testId: string): Record<string, unknown> {
  const [cat, name] = testId.split('.');
  return JSON.parse(readFileSync(new URL(`../../tests/${cat}/${name}.json`, import.meta.url), 'utf8')).config;
}

const manifest = JSON.parse(readFileSync(join(runDir(src), 'manifest.json'), 'utf8')) as RunManifest;
const results = readFileSync(join(runDir(src), 'results.jsonl'), 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((l) => JSON.parse(l) as CaseResult);

// Extra island seeds where the scripted player completes the rescue (see test/program.survival-island.test.ts).
const extra: Array<[string, number]> = [
  ['agentic.survival-island', 1],
  ['agentic.survival-island', 4],
  ['agentic.survival-island-hard', 25],
];
for (const [testId, seed] of extra) {
  const base = results.find((r) => r.testId === testId);
  if (base) results.push({ ...base, caseId: `seed-${seed}`, seed, key: base.key.replace(/seed-\d+/, `seed-${seed}`) });
}

const out: CaseResult[] = [];
for (const r of results) {
  const seed = r.seed ?? Number(r.caseId.replace('seed-', ''));
  const config = testConfig(r.testId);
  let res;
  if (r.testId.includes('survival-island')) {
    const cfg: IslandConfig = { ...ISLAND_DEFAULTS, ...(config as Partial<IslandConfig>) };
    res = await play(island, seed, config, islandResponder(seed, cfg));
  } else if (r.testId.includes('escape-room')) {
    const cfg = { ...ESCAPE_DEFAULTS, ...escape.defaults, ...config } as unknown as EscapeConfig;
    res = await play(escape, seed, config, sloppyEscape(seed, cfg));
  } else if (r.testId.includes('startup-sim')) {
    res = await play(startup, seed, config, startupHeuristicResponder(seed, Number(config.months ?? 12), config.volatile === true));
  } else {
    const cfg = readConfig({ ...liars.defaults, ...config });
    res = await play(liars, seed, config, examinerPolicy(buildWorld({ rng: createRng(seed) }, cfg), cfg.questionBudget));
  }
  out.push({ ...r, runId: dst, score: res.score, passed: res.passed ?? null, summary: res.summary, scoreDetail: res.detail, replay: res.replay, transcript: [] } as CaseResult);
  console.log(`${r.testId} ${r.caseId}: ${res.summary}`);
}

const m2: RunManifest = {
  ...manifest,
  id: dst,
  name: 'Scripted players (replay verification)',
  contestants: manifest.contestants.map((c) => ({ ...c, label: 'Scripted player (test helper)' })),
  tests: manifest.tests.map((t) => ({ ...t, caseIds: [...t.caseIds, ...extra.filter(([id]) => id === t.id).map(([, seed]) => `seed-${seed}`)] })),
};
mkdirSync(runDir(dst), { recursive: true });
writeFileSync(join(runDir(dst), 'manifest.json'), JSON.stringify(m2, null, 2));
writeFileSync(join(runDir(dst), 'results.jsonl'), out.map((r) => JSON.stringify(r)).join('\n') + '\n');
console.log(`wrote run ${dst}`);
