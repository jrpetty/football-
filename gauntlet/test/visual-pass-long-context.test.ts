/**
 * Visual pass (long-context, drawing and picture tests).
 *
 * 1. Regression: the replay-only data added for the UI (needle passages, the
 *    whispers fact trace, Draw It Blind's word limit and unmatched shapes) must
 *    not change a single byte the model sees, nor the score. The fingerprints
 *    below were captured from the programs BEFORE the visual pass, with fixed
 *    seeds and scripted fake players.
 * 2. Unit tests for the new visual data and the UI's pure helpers.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRng } from '../src/core/rng.ts';
import type { ProgramDefinition, ProgramResult } from '../src/core/types.ts';
import { program as needle, buildHaystack, readConfig as readNhConfig } from '../src/programs/needle-haystack.ts';
import { program as whispers, buildStory, readConfig as readCwConfig } from '../src/programs/chain-of-whispers.ts';
import { program as draw, buildScene, readConfig as readDibConfig } from '../src/programs/draw-it-blind.ts';
import { factPresent } from '../src/programs/lib/chain-of-whispers-story.ts';
import type { SourceStory } from '../src/programs/lib/chain-of-whispers-story.ts';
import { countWords } from '../src/programs/lib/needle-haystack-normalize.ts';
import { createFakeModel, createTestContext, mockBaselineResponder } from './helpers/fake-model.ts';
import type { FakeModel, Responder } from './helpers/fake-model.ts';
import { qualitativePolicy } from './helpers/draw-it-blind-policies.ts';

const NH_HARD = { targetWords: 76000, mix: { single: 3, twoHop: 2, threeHop: 3, sum5: 2, superseded: 2 } };
const CW_HARD = { cycles: 5, summaryWords: 60, storyWords: 450, storyMaxWords: 500, storyMinWords: 300, facts: 20, story: 'auto' };
const DIB_HARD = {
  minShapes: 9,
  maxShapes: 12,
  descriptionWords: 90,
  palette: 'extended',
  overlap: true,
  rotation: true,
  positionFalloff: 60,
  weights: { type: 0.2, color: 0.2, position: 0.4, size: 0.2 },
  hyphenSplit: true,
};

async function play(p: ProgramDefinition, seed: number, responder: Responder, config: Record<string, unknown>) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, config, defaults: p.defaults });
  const result = await p.run(ctx);
  return { result, model };
}

/** Everything the model saw, plus the score: the bytes that must never change. */
function fingerprint(model: FakeModel, result: ProgramResult): string {
  const seen = model.calls.map((c) => ({ system: c.system ?? null, messages: c.messages, max: c.maxOutputTokens ?? null }));
  return createHash('sha256')
    .update(JSON.stringify({ seen, score: result.score, passed: result.passed ?? null, summary: result.summary }))
    .digest('hex')
    .slice(0, 16);
}

/** Needle player: right on odd questions, "unknown" on even ones. */
function needlePlayer(seed: number, config: Record<string, unknown>): Responder {
  const h = buildHaystack(createRng(seed).fork('needle-haystack'), readNhConfig({ ...needle.defaults, ...config }));
  return () => h.needles.map((n) => `A${n.n}: ${n.n % 2 ? n.expected : n.n % 4 === 0 ? 'unknown' : '12'}`).join('\n');
}

/** Whispers player: keeps the canonical facts it can still find, drops one fact per summary. */
function whispersPlayer(story: SourceStory): Responder {
  return (_s, userText, _h, info) => {
    const kept = story.facts.filter((f) => factPresent(userText, f));
    if (userText.startsWith('Summarise')) return kept.slice(0, Math.max(0, kept.length - 1 - (info.index % 2))).map((f) => `${f.canonical}.`).join(' ');
    let text = kept.map((f) => `${f.canonical}.`).join(' ');
    while (countWords(text) < 320) text += ' The town talked about it for a long time afterwards.';
    return text;
  };
}

function drawPlayer(seed: number, config: Record<string, unknown>): Responder {
  const cfg = readDibConfig({ ...draw.defaults, ...config });
  const scene = buildScene({ rng: createRng(seed) }, cfg);
  return qualitativePolicy(scene, { grid: 3, wordsPerShape: 14, limit: cfg.descriptionWords, mergeCloseColours: true, ignoreDirection: true });
}

const BEFORE: Record<string, string> = {
  'needle 101': '315a6bb48b7a83dc',
  'needle-hard 202': '9522f936e611ed93',
  'needle baseline 303': '8666de42da00e034',
  'whispers 101': 'd0f1a9baa8877c6f',
  'whispers-hard 202': 'aed7db3e5ffcad0f',
  'whispers baseline 303': '830f02a284293b54',
  'draw 101': 'd162aaf59bad0c81',
  'draw-hard 202': '19d17b2be8235349',
  'draw baseline 303': '343ad7ec1cda87ec',
};

async function runAll(): Promise<Record<string, { fp: string; result: ProgramResult }>> {
  const out: Record<string, { fp: string; result: ProgramResult }> = {};
  const rec = async (name: string, p: ProgramDefinition, seed: number, responder: Responder, config: Record<string, unknown>) => {
    const { result, model } = await play(p, seed, responder, config);
    out[name] = { fp: fingerprint(model, result), result };
  };
  await rec('needle 101', needle, 101, needlePlayer(101, {}), {});
  await rec('needle-hard 202', needle, 202, needlePlayer(202, NH_HARD), NH_HARD);
  await rec('needle baseline 303', needle, 303, mockBaselineResponder(), {});
  const cfg = (c: Record<string, unknown>) => readCwConfig({ ...whispers.defaults, ...c });
  await rec('whispers 101', whispers, 101, whispersPlayer(buildStory({ rng: createRng(101) }, cfg({}))), {});
  await rec('whispers-hard 202', whispers, 202, whispersPlayer(buildStory({ rng: createRng(202) }, cfg(CW_HARD))), CW_HARD);
  await rec('whispers baseline 303', whispers, 303, mockBaselineResponder(), {});
  await rec('draw 101', draw, 101, drawPlayer(101, {}), {});
  await rec('draw-hard 202', draw, 202, drawPlayer(202, DIB_HARD), DIB_HARD);
  await rec('draw baseline 303', draw, 303, mockBaselineResponder(), {});
  return out;
}

test('visual pass: prompts and scores are byte-identical to before the visual data was added', async () => {
  const all = await runAll();
  if (process.env.PRINT_FINGERPRINTS) console.log(JSON.stringify(Object.fromEntries(Object.entries(all).map(([k, v]) => [k, `${v.fp} · ${v.result.summary}`])), null, 2));
  for (const [name, fp] of Object.entries(BEFORE)) assert.equal(all[name]!.fp, fp, name);
});
