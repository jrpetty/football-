/**
 * Records the replays that mock mode (?mock=1) shows for the Business Ladder's Level 4–5 work-world
 * simulations: a strong run (the reference player), a middling one (the hasty player) and a weak one (the
 * careless or gullible player) for each program, played by the real programs on each test's first seed.
 * Writes ui/src/mock/businessSimReplays.json. Demo data only: these are scripted players, not models.
 *
 * Usage: node verification/agent-replay/business-mock-replays.ts
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { createRng } from '../../src/core/rng.ts';
import type { ProgramDefinition, ReplayData } from '../../src/core/types.ts';
import { createFakeModel, createTestContext, type Responder } from '../../test/helpers/fake-model.ts';
import { program as assistant } from '../../src/programs/assistant.ts';
import { generateAssistant, type Tier } from '../../src/programs/lib/assistant/world.ts';
import * as assistantP from '../../src/programs/lib/assistant/players.ts';
import { program as clerk } from '../../src/programs/accounts-clerk.ts';
import { generateClerk } from '../../src/programs/lib/clerk/world.ts';
import * as clerkP from '../../src/programs/lib/clerk/players.ts';
import { program as analyst } from '../../src/programs/analyst-agent.ts';
import { generateAnalyst } from '../../src/programs/lib/analyst/world.ts';
import * as analystP from '../../src/programs/lib/analyst/players.ts';
import { program as helpdesk } from '../../src/programs/helpdesk-agent.ts';
import { generateHelpdesk } from '../../src/programs/lib/helpdesk/world.ts';
import * as helpdeskP from '../../src/programs/lib/helpdesk/players.ts';
import { program as monthEnd } from '../../src/programs/month-end.ts';
import { generateMonthEnd } from '../../src/programs/lib/monthend/world.ts';
import * as monthEndP from '../../src/programs/lib/monthend/players.ts';
import { program as office } from '../../src/programs/office-week.ts';
import { generateOffice } from '../../src/programs/lib/office/world.ts';
import * as officeP from '../../src/programs/lib/office/players.ts';
import { program as recruiter } from '../../src/programs/recruiter.ts';
import { generateRecruiter } from '../../src/programs/lib/recruiter/world.ts';
import * as recruiterP from '../../src/programs/lib/recruiter/players.ts';
import { program as supportWeek } from '../../src/programs/support-week.ts';
import { generateSupportWeek } from '../../src/programs/lib/supportweek/world.ts';
import * as supportWeekP from '../../src/programs/lib/supportweek/players.ts';
import { program as warehouse } from '../../src/programs/warehouse-week.ts';
import { generateWarehouse } from '../../src/programs/lib/warehouse/world.ts';
import * as warehouseP from '../../src/programs/lib/warehouse/players.ts';

interface TestFile {
  id: string;
  seeds: number[];
  config: Record<string, unknown>;
}
const load = (f: string): TestFile => JSON.parse(readFileSync(new URL(`../../tests/work/${f}.json`, import.meta.url), 'utf8'));
const scripted = (s: string[]): Responder => (_a, _b, _c, info) => s[info.index] ?? 'ACTION: FINISH';

type Players = { referenceScript: (s: never) => string[]; hastyScript: (s: never) => string[]; carelessScript: (s: never) => string[]; gullibleScript: (s: never) => string[] };
type Rec = { replay: ReplayData; score: number; summary: string };

async function record(program: ProgramDefinition, t: TestFile, seed: number, script: string[]): Promise<Rec> {
  const model = createFakeModel(scripted(script));
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config: t.config });
  const r = await program.run(ctx);
  const replay: ReplayData = { ...r.replay!, frames: r.replay!.frames.map(({ grid: _g, observation: _o, ...f }) => f) };
  return { replay, score: r.score, summary: r.summary };
}

const SIMS: Array<{ file: string; program: ProgramDefinition; spec: (seed: number, t: TestFile) => unknown; players: Players; weak: 'careless' | 'gullible' }> = [
  { file: 'assistant', program: assistant, spec: (s, t) => generateAssistant(createRng(s), (t.config.tier as Tier) ?? 'standard', s), players: assistantP as unknown as Players, weak: 'gullible' },
  { file: 'assistant-hard', program: assistant, spec: (s, t) => generateAssistant(createRng(s), (t.config.tier as Tier) ?? 'standard', s), players: assistantP as unknown as Players, weak: 'careless' },
  { file: 'accounts-clerk', program: clerk, spec: (s) => generateClerk(createRng(s), s), players: clerkP as unknown as Players, weak: 'gullible' },
  { file: 'analyst-agent', program: analyst, spec: (s) => generateAnalyst(createRng(s), s), players: analystP as unknown as Players, weak: 'careless' },
  { file: 'helpdesk-agent', program: helpdesk, spec: (s) => generateHelpdesk(createRng(s), s), players: helpdeskP as unknown as Players, weak: 'gullible' },
  { file: 'month-end', program: monthEnd, spec: (s) => generateMonthEnd(createRng(s), s), players: monthEndP as unknown as Players, weak: 'careless' },
  { file: 'office-week', program: office, spec: (s) => generateOffice(createRng(s), s), players: officeP as unknown as Players, weak: 'careless' },
  { file: 'recruiter', program: recruiter, spec: (s) => generateRecruiter(createRng(s), s), players: recruiterP as unknown as Players, weak: 'careless' },
  { file: 'support-week', program: supportWeek, spec: (s) => generateSupportWeek(createRng(s), s), players: supportWeekP as unknown as Players, weak: 'careless' },
  { file: 'warehouse-week', program: warehouse, spec: (s) => generateWarehouse(createRng(s), s), players: warehouseP as unknown as Players, weak: 'careless' },
];

const out: Record<string, { good: Rec; mid: Rec; weak: Rec }> = {};
for (const sim of SIMS) {
  const t = load(sim.file);
  const seed = t.seeds[0]!;
  const spec = sim.spec(seed, t) as never;
  const weakScript = sim.weak === 'gullible' ? sim.players.gullibleScript(spec) : sim.players.carelessScript(spec);
  out[t.id] = {
    good: await record(sim.program, t, seed, sim.players.referenceScript(spec)),
    mid: await record(sim.program, t, seed, sim.players.hastyScript(spec)),
    weak: await record(sim.program, t, seed, weakScript),
  };
  const o = out[t.id]!;
  console.log(t.id.padEnd(22), [o.good, o.mid, o.weak].map((r) => (r.score * 100).toFixed(0)).join(' / '));
}
writeFileSync(new URL('../../ui/src/mock/businessSimReplays.json', import.meta.url), JSON.stringify(out));
