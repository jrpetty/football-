/**
 * Records the replays that mock mode (?mock=1) shows for Survival Island, The
 * Escape Room, The Startup and The Liar's Table: one strong run by the
 * scripted test players and one weak run by the Random Baseline mock for each
 * program, played by the real programs. Writes ui/src/mock/simReplays.json.
 *
 * Usage: node verification/agent-replay/mock-replays.ts
 */
import { writeFileSync } from 'node:fs';
import { createRng } from '../../src/core/rng.ts';
import type { ProgramDefinition, ReplayData } from '../../src/core/types.ts';
import { program as island } from '../../src/programs/survival-island.ts';
import { program as escape } from '../../src/programs/escape-room.ts';
import { program as startup } from '../../src/programs/startup-sim.ts';
import { program as liars, buildWorld, readConfig } from '../../src/programs/liars-table.ts';
import { ISLAND_DEFAULTS } from '../../src/programs/lib/agentic-island.ts';
import { ESCAPE_DEFAULTS, generateEscape, type EscapeConfig } from '../../src/programs/lib/agentic-escape.ts';
import { createFakeModel, createTestContext, mockBaselineResponder, type Responder } from '../../test/helpers/fake-model.ts';
import { islandResponder, startupHeuristicResponder } from '../../test/helpers/agentic-policies.ts';
import { examinerPolicy } from '../../test/helpers/liars-table-policies.ts';

async function record(program: ProgramDefinition, seed: number, responder: Responder): Promise<{ replay: ReplayData; score: number; summary: string }> {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config: {} });
  const r = await program.run(ctx);
  // Mock mode only needs the scene data: drop the generic tile grid and long observations.
  const replay: ReplayData = { ...r.replay!, frames: r.replay!.frames.map(({ grid: _g, observation: _o, ...f }) => f) };
  return { replay, score: r.score, summary: r.summary };
}

/** Escape: the optimal plan with a wrong first guess on alternate codes. */
function sloppyEscape(seed: number): Responder {
  const world = generateEscape(createRng(seed), { ...ESCAPE_DEFAULTS } as EscapeConfig);
  const script: string[] = [];
  world.plan.forEach((cmd, i) => {
    const m = cmd.match(/^ENTER (\d+) ON (.+)$/);
    if (m && i % 2 === 0) script.push(`ENTER ${m[1]!.split('').reverse().join('')}1 ON ${m[2]}`);
    script.push(cmd);
  });
  let i = 0;
  return () => `Working through the clues.\nACTION: ${script[i++] ?? 'LOOK'}`;
}

const lt = readConfig({ ...liars.defaults });
const out = {
  'survival-island': {
    good: await record(island, 1, islandResponder(1, { ...ISLAND_DEFAULTS })),
    weak: await record(island, 101, mockBaselineResponder()),
  },
  'escape-room': {
    good: await record(escape, 101, sloppyEscape(101)),
    weak: await record(escape, 202, mockBaselineResponder()),
  },
  startup: {
    good: await record(startup, 202, startupHeuristicResponder(202)),
    weak: await record(startup, 101, mockBaselineResponder()),
  },
  'liars-table': {
    good: await record(liars, 606, examinerPolicy(buildWorld({ rng: createRng(606) }, lt), lt.questionBudget)),
    weak: await record(liars, 303, mockBaselineResponder()),
  },
};
for (const [k, v] of Object.entries(out)) console.log(k, '·', v.good.summary, '·', v.weak.summary);
writeFileSync(new URL('../../ui/src/mock/simReplays.json', import.meta.url), JSON.stringify(out));
