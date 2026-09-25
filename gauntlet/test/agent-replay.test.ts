/**
 * Visual replay data for the agent & social simulations (ReplayData.sim /
 * ReplayFrame.sim) and the pure story helpers the UI draws from.
 */
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRng } from '../src/core/rng.ts';
import type { EscapeSimFrame, IslandSimFrame, LiarsSimFrame, ProgramDefinition, ReplayData, StartupSimFrame } from '../src/core/types.ts';
import { program as island } from '../src/programs/survival-island.ts';
import { program as escape } from '../src/programs/escape-room.ts';
import { program as startup } from '../src/programs/startup-sim.ts';
import { program as liars, buildWorld, readConfig } from '../src/programs/liars-table.ts';
import { ISLAND_DEFAULTS } from '../src/programs/lib/agentic-island.ts';
import { ESCAPE_DEFAULTS, generateEscape, type EscapeConfig } from '../src/programs/lib/agentic-escape.ts';
import {
  bestMoment,
  charDiff,
  escapeLocks,
  islandTimeline,
  islandTrail,
  liarsBoard,
  simFinale,
  simStory,
} from '../ui/src/components/viz/simStory.ts';
import { computeFingerprints, GOLDEN_FILE, type Fingerprint } from './helpers/agent-replay-golden.ts';
import { constantResponder, createFakeModel, createTestContext, mockBaselineResponder, type Responder } from './helpers/fake-model.ts';
import { islandResponder, startupHeuristicResponder } from './helpers/agentic-policies.ts';
import { examinerPolicy } from './helpers/liars-table-policies.ts';

async function play(program: ProgramDefinition, seed: number, responder: Responder, config: Record<string, unknown> = {}) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config });
  const result = await program.run(ctx);
  return { result, replay: result.replay as ReplayData };
}

function testConfig(path: string): { seeds: number[]; config: Record<string, unknown> } {
  return JSON.parse(readFileSync(new URL(`../tests/${path}.json`, import.meta.url), 'utf8'));
}

describe('agent replays: models see exactly the same thing', () => {
  it('prompts, scores and scoring details are byte-identical to before the visual pass (fixed seeds, scripted players and the Random Baseline)', async () => {
    const golden = JSON.parse(readFileSync(GOLDEN_FILE, 'utf8')) as Record<string, Fingerprint>;
    const now = await computeFingerprints();
    assert.deepEqual(Object.keys(now).sort(), Object.keys(golden).sort());
    for (const [k, g] of Object.entries(golden)) assert.deepEqual(now[k], g, k);
  });

  it('the recorded scene data never leaks into what the model is sent', async () => {
    const model = createFakeModel(mockBaselineResponder());
    const { ctx } = createTestContext({ seed: 101, model, defaults: liars.defaults });
    const r = await liars.run(ctx);
    const sent = model.calls.map((c) => c.messages.map((m) => m.content).join('\n')).join('\n');
    const world = r.replay!.sim as { culprit: string; truth: Record<string, string[]> };
    assert.ok(!sent.includes('"culprit"') && !sent.includes('contradictionSources'));
    assert.ok(world.culprit);
  });
});

describe('Survival Island replay', () => {
  it('a rescue run records the island, fog, trail, events and a rescued finale', async () => {
    const { result, replay } = await play(island, 1, islandResponder(1, { ...ISLAND_DEFAULTS }));
    assert.equal(result.detail.rescued, true);
    const w = replay.sim!;
    assert.equal(w.kind, 'island');
    if (w.kind !== 'island') return;
    assert.equal(w.terrain.length, w.size);
    assert.ok(replay.frames.every((f) => f.sim?.kind === 'island'), 'every frame carries island data');
    const first = replay.frames[0]!.sim as IslandSimFrame;
    assert.deepEqual(first.pos, w.start);
    assert.equal(first.phase, -1);
    const fog0 = first.explored.join('').split('').filter((c) => c === '0').length;
    const lastSim = replay.frames.at(-1)!.sim as IslandSimFrame;
    assert.ok(lastSim.explored.join('').split('').filter((c) => c === '0').length < fog0, 'exploring lifts the fog');
    assert.ok(lastSim.rescued && lastSim.events.includes('rescued'));
    const tags = islandTimeline(replay.frames).map((m) => m.tag);
    for (const t of ['found-water', 'signal-built', 'signal-lit', 'rescued'] as const) assert.ok(tags.includes(t), `timeline has ${t}`);
    // The trail steps along straight lines only (MOVE goes 1-3 tiles in one direction).
    const trail = islandTrail(replay.frames, replay.frames.length - 1);
    for (let i = 1; i < trail.length; i++) assert.ok(trail[i]![0] === trail[i - 1]![0] || trail[i]![1] === trail[i - 1]![1]);
    const fin = simFinale(replay)!;
    assert.equal(fin.tone, 'good');
    assert.match(fin.title, /^Rescued on day \d+$/);
    assert.ok(replay.frames[bestMoment(replay)]!.sim && (replay.frames[bestMoment(replay)]!.sim as IslandSimFrame).events.includes('rescued'));
    const story = simStory(replay, bestMoment(replay), 'Model X')!;
    assert.match(story.headline, /rescued/);
    assert.equal(story.tone, 'good');
  });

  it('death and wasted turns are tagged and explained', async () => {
    const { replay } = await play(island, 101, constantResponder('I refuse to pick a command.'));
    const all = replay.frames.map((f) => f.sim as IslandSimFrame);
    assert.ok(all.some((s) => s.events.includes('invalid')));
    assert.ok(all.at(-1)!.events.includes('died') || all.some((s) => s.events.includes('died')));
    const fin = simFinale(replay)!;
    assert.equal(fin.tone, 'bad');
    assert.match(fin.title, /^Died of .+ on day \d+$/);
    assert.ok(fin.notes.some((n) => /wasted/.test(n.text)));
    assert.equal(simStory(replay, 1, 'M')!.tone, 'bad');
  });
});

describe('Escape Room replay', () => {
  it('records wrong attempts against the answer and the moment each lock opens', async () => {
    const world = generateEscape(createRng(101), { ...ESCAPE_DEFAULTS } as EscapeConfig);
    const script: string[] = [];
    let wrongFor = '';
    for (const cmd of world.plan) {
      const m = cmd.match(/^ENTER (\d+) ON (.+)$/);
      if (m && !wrongFor) {
        wrongFor = m[2]!;
        script.push(`ENTER 000 ON ${m[2]}`, 'gibberish without a command');
      }
      script.push(cmd);
    }
    let i = 0;
    const { result, replay } = await play(escape, 101, () => `ACTION: ${script[i++] ?? 'LOOK'}`);
    assert.equal(result.passed, true);
    const w = replay.sim!;
    assert.equal(w.kind, 'escape');
    if (w.kind !== 'escape') return;
    assert.equal(w.optimal, world.optimal);
    const locks = escapeLocks(w, replay.frames, replay.frames.length - 1);
    assert.ok(locks.every((l) => l.open), 'all locks opened');
    const tried = locks.find((l) => l.lock.name === wrongFor)!;
    assert.equal(tried.attempts[0]!.ok, false);
    assert.equal(tried.attempts[0]!.value, '000');
    assert.equal(tried.attempts.at(-1)!.ok, true);
    assert.equal(tried.attempts.at(-1)!.value, tried.lock.answer);
    assert.ok(tried.openedAt! > tried.attempts[0]!.move);
    const events = replay.frames.map((f) => (f.sim as EscapeSimFrame).event.type);
    assert.ok(events.includes('invalid'), 'an unreadable command is tagged');
    assert.equal(events[0], 'start');
    assert.equal(events.at(-1), 'escape');
    assert.ok(events.includes('wrong'));
    const fin = simFinale(replay)!;
    assert.match(fin.title, /^Escaped in \d+ moves$/);
    assert.equal(replay.frames[bestMoment(replay)]!.sim && (replay.frames[bestMoment(replay)]!.sim as EscapeSimFrame).event.type, 'escape');
    // Before a lock opens, the attempts shown against it carry no answer.
    const early = escapeLocks(w, replay.frames, 1);
    assert.ok(early.every((l) => !l.open));
  });

  it('a trapped run ends on a "Trapped" finale', async () => {
    const { replay } = await play(escape, 202, mockBaselineResponder(), testConfig('agentic/escape-room').config);
    const fin = simFinale(replay)!;
    assert.equal(fin.tone, 'bad');
    assert.match(fin.title, /^Trapped in /);
  });

  it('charDiff marks exactly the differing characters', () => {
    assert.deepEqual(
      charDiff('4172', '4127').map((c) => c.ok),
      [true, true, false, false],
    );
    assert.deepEqual(
      charDiff('RED BLUE', 'RED GREEN').map((c) => c.ok),
      [true, false],
    );
  });
});

describe('Startup replay', () => {
  it('records every month with decisions, demand, events and the oracle to compare with', async () => {
    const { result, replay } = await play(startup, 202, startupHeuristicResponder(202));
    const w = replay.sim!;
    assert.equal(w.kind, 'startup');
    if (w.kind !== 'startup') return;
    assert.equal(w.oracle.length, 13);
    assert.equal(w.autopilot.length, 13);
    assert.equal(w.oraclePlan.length, 12);
    assert.equal(w.oracleEquity, Math.round(result.detail.oracleEquity as number));
    const months = replay.frames.map((f) => f.sim as StartupSimFrame | undefined).filter((s): s is StartupSimFrame => !!s);
    assert.deepEqual(
      months.map((m) => m.month),
      Array.from({ length: 12 }, (_, i) => i + 1),
    );
    for (const m of months) assert.ok(m.sold <= m.demand, `month ${m.month}: sold ${m.sold} ≤ demand ${m.demand}`);
    const war = w.events.priceWar;
    assert.ok(months.filter((m) => m.month >= war.start && m.month < war.start + war.months).every((m) => m.news.some((n) => n.type === 'price-war')));
    assert.equal(replay.frames[0]!.sim, undefined, 'launch frame keeps the old shape');
    assert.ok(simStory(replay, 0, 'M'));
    const fin = simFinale(replay)!;
    assert.match(fin.title, /^Finished with \$/);
  });

  it('bankruptcy is the finale when the cash runs out', async () => {
    const { result, replay } = await play(startup, 101, mockBaselineResponder());
    if (result.detail.bankrupt) {
      assert.match(simFinale(replay)!.title, /^Bankrupt in month \d+$/);
      assert.equal(simStory(replay, replay.frames.length - 2, 'M')!.tone, 'bad');
    }
  });
});

describe("Liar's Table replay", () => {
  it('the board lights up a contradiction only on the thief, as soon as the questions expose it (standard and hard cases)', async () => {
    for (const [file, seeds] of [
      ['social/liars-table', [101, 202, 303, 404, 606, 808]],
      ['social/liars-table-hard', [101, 404, 606]],
    ] as const) {
      const cfgRaw = testConfig(file).config;
      const cfg = readConfig({ ...liars.defaults, ...cfgRaw });
      for (const seed of seeds) {
        const w = buildWorld({ rng: createRng(seed) }, cfg);
        const { result, replay } = await play(liars, seed, examinerPolicy(w, cfg.questionBudget), cfgRaw);
        const sim = replay.sim!;
        assert.equal(sim.kind, 'liars');
        if (sim.kind !== 'liars') continue;
        const board = liarsBoard(sim, replay.frames, replay.frames.length - 1);
        const hard = Object.entries(board.cells).filter(([, cells]) => cells.some((c) => c.conflict?.hard)).map(([n]) => n);
        for (const n of hard) assert.equal(n, w.culprit, `${file} seed ${seed}: only the thief's story breaks`);
        if (result.detail.informed) assert.deepEqual(hard, [w.culprit], `${file} seed ${seed}: informed accusation shows the contradiction`);
        if (board.firstExposed !== null) {
          assert.match(simStory(replay, board.firstExposed, 'M')!.headline, /contradiction/);
          assert.equal(bestMoment(replay), board.firstExposed);
        }
      }
    }
  });

  it('invalid questions and the verdict are recorded; the truth is only in the replay', async () => {
    const script = ['what is going on?', 'ACTION: ACCUSE Nobody', 'ACTION: ACCUSE Ada BECAUSE a hunch'];
    const w = buildWorld({ rng: createRng(303) }, readConfig({ ...liars.defaults }));
    const who = w.suspects[0]!.name;
    script[2] = `ACTION: ACCUSE ${who} BECAUSE a hunch`;
    const { replay } = await play(liars, 303, (_s, _u, _h, info) => script[Math.min(info.index, 2)]!);
    const frames = replay.frames.map((f) => f.sim as LiarsSimFrame);
    assert.equal(frames[1]!.invalid, true);
    const last = frames.at(-1)!;
    assert.equal(last.accuse?.who, who);
    assert.equal(last.accuse?.correct, who === w.culprit);
    const fin = simFinale(replay)!;
    assert.equal(fin.tone, who === w.culprit ? 'good' : 'bad');
  });

  it('old replays without scene data keep the generic view', () => {
    const old: ReplayData = { title: 'old', frames: [{ step: 0, label: 'x' }] };
    assert.equal(simStory(old, 0, 'M'), null);
    assert.equal(simFinale(old), null);
    assert.equal(bestMoment(old), 0);
  });
});
