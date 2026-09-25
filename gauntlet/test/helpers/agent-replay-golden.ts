/**
 * Fingerprints of what the agent / social programs send to a model and how
 * they score, for fixed seeds and scripted players. The replay-visual pass
 * added optional replay fields to these programs; the regression test
 * (test/agent-replay.test.ts) proves the prompts and scores stayed
 * byte-identical by comparing against test/fixtures/agent-replay-golden.json,
 * captured before that change.
 *
 * Regenerate (only when a prompt change is intended): node test/helpers/agent-replay-golden.ts --write
 */
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { createRng } from '../../src/core/rng.ts';
import type { ProgramDefinition } from '../../src/core/types.ts';
import { program as island } from '../../src/programs/survival-island.ts';
import { program as escape } from '../../src/programs/escape-room.ts';
import { program as startup } from '../../src/programs/startup-sim.ts';
import { program as liars, buildWorld, readConfig } from '../../src/programs/liars-table.ts';
import { ISLAND_DEFAULTS, type IslandConfig } from '../../src/programs/lib/agentic-island.ts';
import { ESCAPE_DEFAULTS, type EscapeConfig } from '../../src/programs/lib/agentic-escape.ts';
import { createFakeModel, createTestContext, mockBaselineResponder, type Responder } from './fake-model.ts';
import { escapeResponder, islandResponder, startupHeuristicResponder } from './agentic-policies.ts';
import { examinerPolicy } from './liars-table-policies.ts';

export const GOLDEN_FILE = new URL('../fixtures/agent-replay-golden.json', import.meta.url);

export interface Fingerprint {
  calls: number;
  /** sha256 over every system prompt + message sent, in order. */
  prompts: string;
  score: number;
  passed: boolean | undefined;
  summary: string;
  /** sha256 of the result detail (the scoring breakdown). */
  detail: string;
}

function testDef(path: string): { seeds: number[]; config: Record<string, unknown> } {
  return JSON.parse(readFileSync(new URL(`../../tests/${path}.json`, import.meta.url), 'utf8'));
}

async function fingerprint(program: ProgramDefinition, seed: number, config: Record<string, unknown>, responder: Responder): Promise<Fingerprint> {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config });
  const result = await program.run(ctx);
  const h = createHash('sha256');
  for (const c of model.calls) {
    h.update(`\u0000S${c.system ?? ''}`);
    for (const m of c.messages) h.update(`\u0000${m.role}\u0000${m.content}`);
  }
  return {
    calls: model.calls.length,
    prompts: h.digest('hex'),
    score: result.score,
    passed: result.passed,
    summary: result.summary,
    detail: createHash('sha256').update(JSON.stringify(result.detail)).digest('hex'),
  };
}

/** Every (test, seed, player) case, keyed by a readable name. */
export async function computeFingerprints(): Promise<Record<string, Fingerprint>> {
  const out: Record<string, Fingerprint> = {};
  for (const file of ['survival-island', 'survival-island-hard']) {
    const def = testDef(`agentic/${file}`);
    const cfg: IslandConfig = { ...ISLAND_DEFAULTS, ...(def.config as Partial<IslandConfig>) };
    const seed = def.seeds[0]!;
    out[`${file}/${seed}/scripted`] = await fingerprint(island, seed, def.config, islandResponder(seed, cfg));
    out[`${file}/${seed}/baseline`] = await fingerprint(island, seed, def.config, mockBaselineResponder());
  }
  for (const file of ['escape-room', 'escape-room-hard']) {
    const def = testDef(`agentic/${file}`);
    const cfg = { ...ESCAPE_DEFAULTS, ...escape.defaults, ...def.config } as unknown as EscapeConfig;
    const seed = def.seeds[0]!;
    out[`${file}/${seed}/scripted`] = await fingerprint(escape, seed, def.config, escapeResponder(seed, cfg));
    out[`${file}/${seed}/baseline`] = await fingerprint(escape, seed, def.config, mockBaselineResponder());
  }
  for (const file of ['startup-sim', 'startup-sim-hard']) {
    const def = testDef(`agentic/${file}`);
    const seed = def.seeds[0]!;
    const months = Number(def.config.months ?? 12);
    out[`${file}/${seed}/scripted`] = await fingerprint(startup, seed, def.config, startupHeuristicResponder(seed, months, def.config.volatile === true));
    out[`${file}/${seed}/baseline`] = await fingerprint(startup, seed, def.config, mockBaselineResponder());
  }
  for (const file of ['liars-table', 'liars-table-hard']) {
    const def = testDef(`social/${file}`);
    for (const seed of def.seeds) {
      const cfg = readConfig({ ...liars.defaults, ...def.config });
      const w = buildWorld({ rng: createRng(seed) }, cfg);
      out[`${file}/${seed}/scripted`] = await fingerprint(liars, seed, def.config, examinerPolicy(w, cfg.questionBudget));
      out[`${file}/${seed}/baseline`] = await fingerprint(liars, seed, def.config, mockBaselineResponder());
    }
  }
  return out;
}

if (process.argv.includes('--write')) {
  const fp = await computeFingerprints();
  writeFileSync(GOLDEN_FILE, `${JSON.stringify(fp, null, 2)}\n`);
  console.log(`wrote ${Object.keys(fp).length} fingerprints`);
}
