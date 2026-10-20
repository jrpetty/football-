/**
 * Arena speed games (chess-clock time control): the clock and what is charged, flag rules per game, the
 * clock-sized output allowance (a long simulation proves compliant players never flag), the prompt's clock
 * section and urgency tiers (and Classic prompts byte-identical), the recorder's per-call limit, planning
 * (default 4+0, fingerprint, copy & paste refused) and the viewer time-trouble helpers.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-arena-clock-'));
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_NO_BROWSER = '1';
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  for (let i = 0; i < 4; i++) models.contestants.push({ id: `cbot-${i}`, label: `Clock Bot ${i}`, vendor: 'Test', provider: 'baseline', model: `random-${i}`, color: '#123456', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } });
  models.contestants.push({ id: 'cpaste', label: 'Paste Model', vendor: 'Test', provider: 'manual', model: 'paste', color: '#222222', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } });
  models.contestants.push({ id: 'cpricey', label: 'Pricey', vendor: 'Test', provider: 'baseline', model: 'pricey', color: '#333333', enabled: true, pricing: { inputPerM: 1, outputPerM: 10, verifiedAt: '2026-01-01' } });
  writeFileSync(file, JSON.stringify(models));
  const sfile = join(sandbox, 'config', 'settings.json');
  const settings = JSON.parse(readFileSync(sfile, 'utf8'));
  settings.judges = [];
  writeFileSync(sfile, JSON.stringify(settings));
}

const clockMod = await import('../src/arena/clock.ts');
const { GameClock, SpeedMeter, planMove, allowanceError, RESERVE_TURNS, USEFUL_FLOOR_TOKENS, clockSection, clockReminder, urgencyTier, clockDisplayState, clockCallout, tickingClock, fmtClockDisplay, fmtClockText, resolveTimeControl, DEFAULT_TIME_CONTROL, TIME_CONTROL_PRESETS } = clockMod;
const { chess, fromFen, START_FEN, cannotMate } = await import('../src/arena/games/chess.ts');
const { connect4 } = await import('../src/arena/games/connect4.ts');
const { poker } = await import('../src/arena/games/poker.ts');
const { debate, courtroom } = await import('../src/arena/games/debate.ts');
const { buildMovePrompt } = await import('../src/arena/prompt.ts');
const { playGame } = await import('../src/arena/match.ts');
const { playTurnGame } = await import('../src/arena/turns.ts');
const { playJudgedGame } = await import('../src/arena/judged.ts');
const { computeState, buildKnockout } = await import('../src/arena/bracket.ts');
const { createRng } = await import('../src/core/rng.ts');
const { createRecorder, OutOfTimeError } = await import('../src/engine/recorder.ts');
const { Semaphore } = await import('../src/engine/semaphore.ts');
const tournament = await import('../src/arena/tournament.ts');
type ModelHandle = import('../src/core/types.ts').ModelHandle;
type ModelReply = import('../src/core/types.ts').ModelReply;
type Contestant = import('../src/core/types.ts').Contestant;
type CompletionRequest = import('../src/core/types.ts').CompletionRequest;
type ArenaGameLite = import('../src/arena/types.ts').ArenaGameLite;
type ArenaEntrant = import('../src/arena/types.ts').ArenaEntrant;

const TC4 = { minutes: 4, incrementSec: 0 };
const clockOpts = (tc = TC4, instant?: [boolean, boolean]) => ({ timeControl: tc, speed: [new SpeedMeter(), new SpeedMeter()] as [InstanceType<typeof SpeedMeter>, InstanceType<typeof SpeedMeter>], maxOutputTokens: 16000, ...(instant ? { instant } : {}) });
const outOfTime = (limit: number) => Object.assign(new Error('Out of time'), { name: 'OutOfTimeError', elapsedMs: limit });

interface SimCall {
  maxOutputTokens?: number;
  timeLimitMs?: number;
  prompt: string;
}

/**
 * A simulated player on virtual time: each reply takes ttft + tokens / speed (speed jittered ±20% per call)
 * and uses its FULL output allowance (or `fixedTokens`, ignoring the allowance). A reply slower than the call's
 * time limit is aborted with OutOfTimeError, exactly like the recorder does. No real waiting.
 */
function simPlayer(o: { tps: number; ttftMs: number; seed: number; fixedTokens?: number; hidden?: boolean; answer: (prompt: string, rng: ReturnType<typeof createRng>) => string }): ModelHandle & { calls: SimCall[] } {
  const rng = createRng(o.seed);
  const calls: SimCall[] = [];
  return {
    calls,
    async complete(req): Promise<ModelReply> {
      const prompt = req.messages[req.messages.length - 1]!.content;
      calls.push({ maxOutputTokens: req.maxOutputTokens, timeLimitMs: req.timeLimitMs, prompt });
      const n = o.fixedTokens ?? req.maxOutputTokens ?? 16000;
      const v = o.tps * (0.8 + 0.4 * rng.next());
      const totalMs = o.ttftMs + (n / v) * 1000;
      if (req.timeLimitMs && totalMs > req.timeLimitMs) throw outOfTime(req.timeLimitMs);
      return { text: o.answer(prompt, rng), stopReason: 'end', totalMs, ttftMs: o.hidden ? totalMs * 0.95 : o.ttftMs, outputTokens: n };
    },
    chat() {
      throw new Error('not used');
    },
  };
}

const pickTicked = (key: string) => (prompt: string, rng: ReturnType<typeof createRng>) => {
  const opts = [...prompt.matchAll(new RegExp('`' + key + ': ([^`]+)`', 'g'))].map((m) => m[1]!);
  return `${key}: ${rng.pick(opts)}`;
};
const speechAnswer = () => 'Members of the panel, my case rests on three clear points that I will now set out briefly and plainly.';

const SPEEDS = [20, 40, 80, 120, 200];
const DELAYS = [300, 1000, 2000, 3000];

// ─────────────────────────────── Basics ───────────────────────────────

test('clock: formatting, presets and time-control validation', () => {
  assert.equal(fmtClockText(192_999), '3:12');
  assert.equal(fmtClockText(14_000), '0:14');
  assert.equal(fmtClockDisplay(7_340), '0:07.3');
  assert.equal(fmtClockDisplay(65_000), '1:05');
  assert.equal(fmtClockDisplay(0), '0:00');
  assert.deepEqual(DEFAULT_TIME_CONTROL, { minutes: 4, incrementSec: 0 });
  assert.deepEqual(resolveTimeControl(undefined), { minutes: 4, incrementSec: 0 }, 'new tournaments default to Speed 4+0');
  assert.equal(resolveTimeControl(null), null, 'null = Classic');
  assert.throws(() => resolveTimeControl({ minutes: 0, incrementSec: 0 }), /minutes/);
  assert.throws(() => resolveTimeControl({ minutes: 4, incrementSec: -1 }), /increment/);
  assert.deepEqual(TIME_CONTROL_PRESETS.map((p) => p.id), ['4+0', '1+0', '2+1', '3+2', '5+0', '10+0', 'classic']);
  assert.ok(TIME_CONTROL_PRESETS[0]!.recommended);
});

test('clock: only charged model time counts; increment after each completed move; instant seats never run', () => {
  const c = new GameClock(clockOpts({ minutes: 2, incrementSec: 1 }, [false, true]));
  assert.deepEqual(c.snapshot(), [120_000, 120_000]);
  assert.equal(c.charge(0, 5_000), false);
  c.moveDone(0);
  assert.equal(c.remaining[0], 116_000, '120 − 5 + 1 increment');
  assert.equal(c.charge(1, 999_999), false, 'the Random Baseline answers instantly');
  assert.equal(c.remaining[1], 120_000);
  assert.equal(c.limitFor(1), undefined);
  assert.equal(c.limitFor(0), 116_000, 'the call limit is the remaining clock');
  assert.equal(c.charge(0, 200_000), true, 'the flag falls at 0:00');
  assert.equal(c.remaining[0], 0);
  c.moveDone(0);
  assert.equal(c.remaining[0], 0, 'no increment after a flag fall');
});

test('chess flag rule: the opponent needs mating material (FIDE): K, K+B, K+N cannot mate', () => {
  const b = fromFen('8/8/8/4k3/8/8/8/K6n w - - 0 1').board;
  assert.equal(cannotMate(b, 'b'), true, 'K+N');
  assert.equal(cannotMate(b, 'w'), true, 'lone K');
  assert.equal(cannotMate(fromFen('8/8/8/4k3/8/8/8/K5bb w - - 0 1').board, 'b'), false, 'two bishops can mate');
  assert.equal(cannotMate(fromFen('8/8/8/4k3/8/8/p7/K7 w - - 0 1').board, 'b'), false, 'a pawn can promote');
  const s = fromFen('8/8/8/4k3/8/8/8/K6n w - - 0 1');
  assert.deepEqual(chess.clock!.onFlag!(s, 0), { winner: null, reason: "Draw: White's flag fell but Black cannot checkmate" });
  assert.deepEqual(chess.clock!.onFlag!(fromFen(START_FEN), 1), { winner: 0, reason: 'Black lost on time' });
});

// ─────────────────────────────── The allowance ───────────────────────────────

type TL = (made: number) => { expected: number; max: number };
const PURE: Record<string, { n: number; tl: TL; min: number }> = {
  chess: { n: 60, tl: (m) => chess.clock!.turnsLeft({ plies: 2 * m } as never, 0, m, chess.defaults), min: chess.clock!.minTokens },
  connect4: { n: 21, tl: (m) => connect4.clock!.turnsLeft({ plies: 2 * m } as never, 0, m, connect4.defaults), min: connect4.clock!.minTokens },
  poker: { n: 35, tl: (m) => poker.clock!.turnsLeft({ cfg: { hands: 10 }, results: { length: Math.floor(m / 3.5) } } as never, 0, m, poker.defaults), min: poker.clock!.minTokens },
  debate: { n: 3, tl: (m) => ({ expected: 3 - m, max: 3 - m }), min: debate.clock!.minTokens },
};

/** Plays `n` turns where the player always uses its FULL allowance at a jittered speed; returns whether it flagged. */
function pureRun(g: (typeof PURE)[string], tps: number, ttft: number, seed: number, tc = TC4, hidden = false, prior = false): { flagged: boolean; leftMs: number; tokens: number[] } {
  const rng = createRng(seed);
  const meter = prior ? knownSpeed(tps, ttft) : new SpeedMeter();
  let R = tc.minutes * 60_000;
  const tokens: number[] = [];
  for (let k = 0; k < g.n; k++) {
    const p = planMove({ remainingMs: R, turns: g.tl(k), speed: meter.estimate(), maxOutputTokens: 16000, minTokens: g.min });
    tokens.push(p.maxOutputTokens);
    const t = ttft + (p.maxOutputTokens / (tps * (0.8 + 0.4 * rng.next()))) * 1000;
    meter.observe({ outputTokens: p.maxOutputTokens, totalMs: t, ttftMs: hidden ? t * 0.95 : ttft });
    R -= t;
    if (R <= 0) return { flagged: true, leftMs: 0, tokens };
    R += tc.incrementSec * 1000;
  }
  return { flagged: false, leftMs: R, tokens };
}

test('allowance: compliant players (20–200 tok/s, 0.3–3 s first token, ±20% jitter, FULL allowance every turn) never flag over long games', () => {
  for (const [name, g] of Object.entries(PURE)) {
    let runs = 0;
    for (const tps of SPEEDS)
      for (const d of DELAYS)
        for (let seed = 1; seed <= 15; seed++) {
          for (const [hidden, prior] of [[false, false], [true, false], [false, true]] as const) {
            const r = pureRun(g, tps, d, seed * 101 + tps + d, TC4, hidden, prior);
            assert.equal(r.flagged, false, `${name} at ${tps} tok/s, ${d} ms first token, seed ${seed}${hidden ? ', hidden reasoning' : ''}${prior ? ', speed known' : ''} flagged`);
            runs++;
          }
        }
    assert.equal(runs, SPEEDS.length * DELAYS.length * 45);
  }
  // The longer presets hold too.
  for (const tc of [{ minutes: 3, incrementSec: 2 }, { minutes: 5, incrementSec: 0 }, { minutes: 10, incrementSec: 0 }])
    for (const tps of SPEEDS) for (const d of DELAYS) assert.equal(pureRun(PURE.chess!, tps, d, tps * d, tc).flagged, false, `${tc.minutes}+${tc.incrementSec} chess ${tps}/${d}`);
});

test('allowance: shrinks with the clock, stays within the normal maximum and the floor, grows with measured speed', () => {
  const fast = new SpeedMeter();
  for (let i = 0; i < 5; i++) fast.observe({ outputTokens: 2000, totalMs: 300 + 10_000, ttftMs: 300 });
  const plan = (R: number, meter = fast) => planMove({ remainingMs: R, turns: { expected: 30, max: 50 }, speed: meter.estimate(), maxOutputTokens: 16000, minTokens: 12 });
  const a = plan(200_000);
  const b = plan(60_000);
  const c = plan(10_000);
  assert.ok(a.maxOutputTokens > b.maxOutputTokens && b.maxOutputTokens >= c.maxOutputTokens, `${a.maxOutputTokens} > ${b.maxOutputTokens} >= ${c.maxOutputTokens}`);
  assert.ok(c.maxOutputTokens >= 12);
  assert.ok(planMove({ remainingMs: 3_600_000, turns: { expected: 8, max: 8 }, speed: fast.estimate(), maxOutputTokens: 4000, minTokens: 12 }).maxOutputTokens <= 4000);
  assert.ok(a.maxOutputTokens > plan(200_000, new SpeedMeter()).maxOutputTokens, 'a measured fast model gets more than the conservative default');
  // Each turn's budget is a small share of the free time (2/E with E >= 8 for chess).
  assert.ok(a.budgetMs <= (2 * 200_000) / 30 + 1);
});

test('allowance: a player that ignores it (a slow model) flags; bullet with a slow first token cannot be saved', () => {
  const slow = pureRun({ ...PURE.chess!, tl: PURE.chess!.tl }, 40, 1000, 1);
  assert.equal(slow.flagged, false);
  // Ignoring the allowance: 6,000 tokens a move at 40 tok/s = 150 s a move.
  let R = 240_000;
  let k = 0;
  for (; R > 0 && k < 60; k++) R -= 1000 + (6000 / 40) * 1000;
  assert.ok(k <= 2, 'flags on the second move');
});

/** A meter that already knows the player's true speed (earlier calls of the same model). */
const knownSpeed = (tps: number, ttftMs: number) => new SpeedMeter(Array.from({ length: 5 }, () => ({ outputTokens: 600, totalMs: ttftMs + (600 / tps) * 1000, ttftMs })));
const quantile = (a: number[], p: number) => [...a].sort((x, y) => x - y)[Math.min(a.length - 1, Math.floor(p * a.length))]!;

test('allowance: the reserve holds back at most 12 minimal answers, so move 1 is a useful move once the model is measured', () => {
  assert.equal(RESERVE_TURNS, 12);
  const chess1 = PURE.chess!.tl(0);
  const known = planMove({ remainingMs: 240_000, turns: chess1, speed: knownSpeed(100, 500).estimate(), maxOutputTokens: 16000, minTokens: 12 });
  assert.ok(known.maxOutputTokens >= USEFUL_FLOOR_TOKENS, `move 1 at 100 tok/s: ${known.maxOutputTokens} tokens`);
  assert.ok(known.suggestedMs >= 2500, `suggested ${known.suggestedMs} ms`);
  const unknown = planMove({ remainingMs: 240_000, turns: chess1, speed: new SpeedMeter().estimate(), maxOutputTokens: 16000, minTokens: 12 });
  assert.ok(unknown.maxOutputTokens >= 80, `unmeasured move 1: ${unknown.maxOutputTokens} tokens (was 57)`);
  // Through a whole 60-move game at 100 tok/s the allowance stays useful (it used to drop to a few dozen tokens).
  const run = (meter: InstanceType<typeof SpeedMeter>) => {
    let R = 240_000;
    const toks: number[] = [];
    for (let k = 0; k < 60; k++) {
      const pl = planMove({ remainingMs: R, turns: PURE.chess!.tl(k), speed: meter.estimate(), maxOutputTokens: 16000, minTokens: 12 });
      toks.push(pl.maxOutputTokens);
      const t = 500 + (pl.maxOutputTokens / 100) * 1000;
      meter.observe({ outputTokens: pl.maxOutputTokens, totalMs: t, ttftMs: 500 });
      R -= t;
      assert.ok(R > 0, `flagged at move ${k + 1}`);
    }
    return toks;
  };
  const toks = run(knownSpeed(100, 500));
  assert.ok(quantile(toks, 0.5) >= 350, `median ${quantile(toks, 0.5)}`);
  assert.ok(quantile(toks, 0.1) >= 200, `p10 ${quantile(toks, 0.1)}`);
});

test('speed meter: a player is sized from its own calls, else the same model’s earlier calls, else the default', () => {
  const fresh = new SpeedMeter();
  assert.equal(fresh.source, 'default');
  assert.equal(fresh.estimate().measured, false);
  const prior = knownSpeed(120, 400);
  assert.equal(prior.source, 'earlier');
  const e = prior.estimate();
  assert.ok(e.measured && Math.abs(e.tokensPerSec - 120) < 1 && Math.abs(e.firstTokenMs - 400) < 1);
  prior.observe({ outputTokens: 100, totalMs: 300 + 2000, ttftMs: 300 });
  assert.equal(prior.source, 'tournament', 'its own calls in this tournament win');
  assert.ok(Math.abs(prior.estimate().tokensPerSec - 50) < 1);
  // The delay is the median: one slow outlier does not move it.
  const m = new SpeedMeter();
  for (const ttft of [400, 450, 500, 9000, 420]) m.observe({ outputTokens: 400, totalMs: ttft + 4000, ttftMs: ttft });
  assert.equal(m.estimate().firstTokenMs, 450);
});

test('speed priors: earlier tournaments first, then Gauntlet results, matched by model configuration only', () => {
  const data = join(sandbox, 'data');
  const entrant = (id: string, configHash: string) => ({ id, label: id, configHash, seed: 1, index: null });
  const tdir = join(data, 'arena', 'arena-prior-test');
  mkdirSync(tdir, { recursive: true });
  writeFileSync(join(tdir, 'manifest.json'), JSON.stringify({ id: 'arena-prior-test', entrants: [entrant('m-a', 'hash-a'), entrant('m-b', 'hash-b')], settings: {}, matches: [], game: { id: 'chess' } }));
  const call = (out: number, total: number, ttft: number) => ({ usage: { outputTokens: out }, totalMs: total, ttftMs: ttft, messages: [], response: '' });
  writeFileSync(join(tdir, 'games.jsonl'), JSON.stringify({ key: 'g1', players: ['m-a', 'm-b'], metrics: [{ costUsd: 0 }, { costUsd: 0 }], transcripts: [Array.from({ length: 6 }, () => call(500, 5500, 500)), [call(300, 9000, 6000)]] }) + '\n');
  const rdir = join(data, 'runs', 'run-prior-test');
  mkdirSync(rdir, { recursive: true });
  writeFileSync(join(rdir, 'manifest.json'), JSON.stringify({ id: 'run-prior-test' }));
  writeFileSync(join(rdir, 'results.jsonl'), [1, 2, 3].map((i) => JSON.stringify({ key: `k${i}`, contestantHash: 'hash-c', status: 'ok', metrics: { outputTokens: 800, outputTokensPerSec: 80, ttftMs: 700 } })).join('\n') + '\n');
  const pri = tournament.priorSpeedSamples(['hash-a', 'hash-b', 'hash-c', 'hash-unknown']);
  assert.equal(pri.get('hash-a')!.length, 6, 'from the earlier tournament');
  assert.deepEqual(pri.get('hash-a')![0], { outputTokens: 500, totalMs: 5500, ttftMs: 500 });
  assert.equal(pri.get('hash-b')!.length, 0, 'too few Arena calls: Gauntlet results are tried (there are none for this model)');
  assert.equal(pri.get('hash-c')!.length, 3, 'from Gauntlet results');
  assert.deepEqual(pri.get('hash-c')![0], { outputTokens: 800, totalMs: 10_000, ttftMs: 700 });
  assert.equal(pri.get('hash-unknown')!.length, 0, 'never another model’s numbers');
});

test('prompt: a model that reasons is told plainly when its allowance is small', () => {
  const v = { ...chessView(150_000, 150_000), maxOutputTokens: 300, reasoning: true };
  assert.match(clockSection(v), /Answer at once with very little reasoning: you only have about 300 tokens\./);
  assert.doesNotMatch(clockSection({ ...v, maxOutputTokens: 2000 }), /very little reasoning/);
  assert.doesNotMatch(clockSection({ ...v, reasoning: false }), /very little reasoning/);
});

test('retry: a reply that ran out of its allowance before the move gets the problem explained and a small fixed allowance', async () => {
  const calls: Array<{ max?: number; prompt: string }> = [];
  const h: ModelHandle = {
    async complete(req) {
      calls.push({ max: req.maxOutputTokens, prompt: req.messages[0]!.content });
      if (calls.length === 1) return { text: 'Let me think about the Sicilian…', stopReason: 'max_tokens', totalMs: 3000, ttftMs: 300, outputTokens: req.maxOutputTokens ?? 0 };
      return { text: 'MOVE: e4', stopReason: 'end', totalMs: 400, ttftMs: 300, outputTokens: 6 };
    },
    chat: () => {
      throw new Error('x');
    },
  };
  const other = simPlayer({ tps: 100, ttftMs: 300, seed: 1, answer: pickTicked('MOVE') });
  const g = await playGame({ game: chess, config: { ...chess.defaults, maxPlies: 1 }, seed: 1, seats: [{ handle: h }, { handle: other }], maxStrikes: 3, clock: clockOpts(), maxOutputTokens: 16000 });
  assert.equal(g.moves[0]!.attempts[0]!.error, 'You ran out of your token allowance before giving a move — give your move immediately, no reasoning.');
  assert.equal(allowanceError(chess.clock!), g.moves[0]!.attempts[0]!.error);
  assert.equal(calls[1]!.max, chess.clock!.retryTokens, 'the retry gets the small fixed allowance');
  assert.match(calls[1]!.prompt, /PREVIOUS ANSWER WAS REJECTED[\s\S]*ran out of your token allowance/);
  assert.equal(g.moves[0]!.move, 'e2e4');
  // Poker says so in its own words.
  assert.match(allowanceError(poker.clock!), /before you could give your ACTION line — give your ACTION line immediately, no reasoning\./);
});

// ─────────────────────────────── Engines ───────────────────────────────

test('speed chess: simulated compliant players play to the move cap without flagging; clocks after every move', async () => {
  let reachedCap = 0;
  for (const [tps, d] of [[20, 3000], [40, 2000], [80, 1000], [200, 300]] as const) {
    for (const seed of [1, 2]) {
      const a = simPlayer({ tps, ttftMs: d, seed, answer: pickTicked('MOVE') });
      const b = simPlayer({ tps, ttftMs: d, seed: seed + 50, answer: pickTicked('MOVE') });
      const g = await playGame({ game: chess, config: chess.defaults, seed, seats: [{ handle: a }, { handle: b }], maxStrikes: 3, clock: clockOpts(), maxOutputTokens: 16000 });
      assert.ok(!/on time|flag/i.test(g.reason), `${tps}/${d}: ${g.reason}`);
      assert.deepEqual(g.clock!.flagged, [false, false]);
      if (g.moves.length >= chess.defaults.maxPlies) reachedCap++;
      for (const m of g.moves) {
        assert.equal(m.clockMs!.length, 2);
        assert.ok(m.clockMs![m.side] > 0);
        assert.ok(typeof m.chargedMs === 'number');
      }
      // Every call carried the remaining clock as its limit and an allowance.
      for (const c of a.calls) assert.ok(c.timeLimitMs! > 0 && c.timeLimitMs! <= 240_000 && c.maxOutputTokens! >= 12);
      assert.match(a.calls[0]!.prompt, /== CLOCK \(SPEED GAME\) ==/);
    }
  }
  assert.ok(reachedCap >= 1, 'at least one random game went the full 120 half-moves');
});

test('speed chess: a model that ignores its allowance loses on time; the aborted call is the flag', async () => {
  const slow = simPlayer({ tps: 40, ttftMs: 1000, seed: 3, fixedTokens: 6000, answer: pickTicked('MOVE') });
  const ok = simPlayer({ tps: 100, ttftMs: 500, seed: 4, answer: pickTicked('MOVE') });
  const g = await playGame({ game: chess, config: chess.defaults, seed: 3, seats: [{ handle: slow }, { handle: ok }], maxStrikes: 3, clock: clockOpts(), maxOutputTokens: 16000 });
  assert.equal(g.winner, 1);
  assert.equal(g.reason, 'White lost on time');
  const last = g.moves[g.moves.length - 1]!;
  assert.equal(last.flag, true);
  assert.equal(last.side, 0);
  assert.equal(last.clockMs![0], 0);
  assert.match(last.attempts[0]!.error!, /Out of time/);
  assert.deepEqual(g.clock!.flagged, [true, false]);
  assert.equal(g.illegal[0], 0, 'a flag is not an illegal move');
});

test('speed chess: illegal-move retries consume clock; an out-of-time throw flags; draw when the opponent cannot mate', async () => {
  let n = 0;
  const retrying: ModelHandle = {
    async complete() {
      n++;
      return n === 1 ? { text: 'MOVE: Ke9', stopReason: 'end', totalMs: 10_000, ttftMs: 100, outputTokens: 50 } : { text: 'MOVE: e4', stopReason: 'end', totalMs: 5_000, ttftMs: 100, outputTokens: 50 };
    },
    chat: () => {
      throw new Error('x');
    },
  };
  const other = simPlayer({ tps: 100, ttftMs: 300, seed: 1, answer: pickTicked('MOVE') });
  const g = await playGame({ game: chess, config: { ...chess.defaults, maxPlies: 2 }, seed: 1, seats: [{ handle: retrying }, { handle: other }], maxStrikes: 3, clock: clockOpts({ minutes: 4, incrementSec: 2 }), maxOutputTokens: 16000 });
  assert.equal(g.moves[0]!.chargedMs, 15_000, 'both attempts are charged');
  assert.equal(g.moves[0]!.clockMs![0], 240_000 - 15_000 + 2_000, 'increment after the completed move');
  assert.equal(g.illegal[0], 1);

  const thrower: ModelHandle = {
    async complete(req) {
      throw outOfTime(req.timeLimitMs ?? 0);
    },
    chat: () => {
      throw new Error('x');
    },
  };
  const lone = { ...chess.defaults, startFen: '8/8/8/4k3/8/8/8/KQ5n w - - 0 1' };
  const d = await playGame({ game: chess, config: lone, seed: 1, seats: [{ handle: thrower }, { handle: other }], maxStrikes: 3, clock: clockOpts(), maxOutputTokens: 16000 });
  assert.equal(d.winner, null);
  assert.equal(d.reason, "Draw: White's flag fell but Black cannot checkmate");
  const c4 = await playGame({ game: connect4, config: connect4.defaults, seed: 1, seats: [{ handle: other }, { handle: thrower }], maxStrikes: 3, clock: clockOpts(), maxOutputTokens: 16000 });
  assert.equal(c4.winner, 0);
  assert.equal(c4.reason, 'Yellow lost on time');
});

test('speed games: the Random Baseline (instant) never flags and gets no time limit', async () => {
  const baseline: ModelHandle & { limits: Array<number | undefined> } = {
    limits: [],
    async complete(req) {
      this.limits.push(req.timeLimitMs);
      const opts = [...req.messages[0]!.content.matchAll(/`MOVE: ([^`]+)`/g)].map((m) => m[1]!);
      return { text: `MOVE: ${opts[0]}`, stopReason: 'end', totalMs: 500_000, ttftMs: 1, outputTokens: 3 };
    },
    chat: () => {
      throw new Error('x');
    },
  };
  const other = simPlayer({ tps: 100, ttftMs: 300, seed: 9, answer: pickTicked('MOVE') });
  const g = await playGame({ game: connect4, config: connect4.defaults, seed: 2, seats: [{ handle: baseline }, { handle: other }], maxStrikes: 3, clock: clockOpts(TC4, [true, false]), maxOutputTokens: 16000 });
  assert.equal(g.clock!.flagged[0], false);
  assert.equal(g.clock!.final[0], 240_000);
  assert.ok(baseline.limits.every((x) => x === undefined));
});

test('speed poker: full sessions never flag for compliant players; after a flag every decision is auto check/fold with no calls', async () => {
  const cfg = { ...poker.defaults, hands: 10 };
  for (const [tps, d] of [[20, 3000], [80, 1000], [200, 300]] as const) {
    const a = simPlayer({ tps, ttftMs: d, seed: 1, answer: pickTicked('ACTION') });
    const b = simPlayer({ tps, ttftMs: d, seed: 2, answer: pickTicked('ACTION') });
    const g = await playTurnGame({ game: poker, config: cfg, seed: 7, seats: [{ handle: a }, { handle: b }], maxStrikes: 3, clock: clockOpts(), maxOutputTokens: 16000 });
    assert.deepEqual(g.clock!.flagged, [false, false], `${tps}/${d}: ${g.reason}`);
    assert.ok(!/ran out of time/.test(g.reason));
    assert.match(a.calls[0]!.prompt, /== CLOCK \(SPEED GAME\) ==[\s\S]*decisions[\s\S]*ACTION line/);
  }
  const slow = simPlayer({ tps: 40, ttftMs: 1000, seed: 3, fixedTokens: 5000, answer: pickTicked('ACTION') });
  const fine = simPlayer({ tps: 100, ttftMs: 300, seed: 4, answer: pickTicked('ACTION') });
  const g = await playTurnGame({ game: poker, config: cfg, seed: 7, seats: [{ handle: slow }, { handle: fine }], maxStrikes: 3, clock: clockOpts(), maxOutputTokens: 16000 });
  assert.equal(g.clock!.flagged[0], true);
  const flagAt = g.moves.findIndex((m) => m.flag);
  assert.ok(flagAt >= 0);
  const later = g.moves.slice(flagAt + 1).filter((m) => m.side === 0);
  assert.ok(later.length > 0 && later.every((m) => m.auto === 'out-of-time' && m.attempts.length === 0 && /: (fold|check)$/.test(m.label)));
  assert.equal(slow.calls.length, g.moves.slice(0, flagAt + 1).filter((m) => m.side === 0).reduce((x, m) => x + m.attempts.length, 0), 'no model calls after the flag');
  assert.match(g.reason, /Seat 1 ran out of time \(hand \d+ of 10, [a-z-]+\): every later decision was made for it with no model call \(check when free, otherwise fold\)/);
  assert.equal(g.strikes[0], 0, 'auto decisions are not strikes');
  assert.equal(g.margin![0] + g.margin![1], 0, 'chips still sum to zero');
});

test('speed debate: speeches after a flag are "(No speech: out of time)" and the judges are told; compliant debaters never flag', async () => {
  for (const [tps, d] of [[20, 3000], [200, 300]] as const) {
    const a = simPlayer({ tps, ttftMs: d, seed: 1, answer: speechAnswer });
    const b = simPlayer({ tps, ttftMs: d, seed: 2, answer: speechAnswer });
    const g = await playJudgedGame({ game: courtroom, config: courtroom.defaults, seed: 1, seats: [{ handle: a }, { handle: b }], maxStrikes: 3, clock: clockOpts(), maxOutputTokens: 16000 });
    assert.deepEqual(g.clock!.flagged, [false, false]);
  }
  const slow = simPlayer({ tps: 20, ttftMs: 1000, seed: 3, fixedTokens: 4000, answer: speechAnswer });
  const fine = simPlayer({ tps: 100, ttftMs: 300, seed: 4, answer: speechAnswer });
  let material = '';
  const g = await playJudgedGame({
    game: debate,
    config: debate.defaults,
    seed: 1,
    seats: [{ handle: fine }, { handle: slow }],
    maxStrikes: 3,
    clock: clockOpts(),
    maxOutputTokens: 16000,
    judge: async (state) => {
      material = debate.judge!.material(state as never, 0);
      const { judgingFrom } = await import('../src/arena/judge.ts');
      return judgingFrom([]);
    },
  });
  assert.equal(g.clock!.flagged[1], true);
  const opp = g.moves.filter((m) => m.side === 1 && m.kind !== 'verdict');
  assert.ok(opp.some((m) => m.flag));
  assert.ok(opp.every((m) => m.flag || m.auto === 'out-of-time' || m.attempts.length > 0));
  assert.match(material, /\(No speech: out of time\.\)/);
  assert.match(material, /== CLOCK ==\nThis was a speed game[\s\S]*Side B ran out of time/);
  assert.match(g.reason, /Opposition ran out of time: its remaining speeches are missing/);
});

// ─────────────────────────────── Prompts ───────────────────────────────

const chessView = (remainingMs: number, opponentMs: number, initialMs = 240_000) => ({ initialMs, incrementMs: 0, remainingMs, opponentMs, made: 14, suggestedMs: 5_000, maxOutputTokens: 1200, words: chess.clock! });

test('prompt: the clock section, the reminder, and urgency that escalates with the clock', () => {
  const st = fromFen(START_FEN);
  const p = (v: ReturnType<typeof chessView>) => buildMovePrompt({ game: chess, state: st, side: 0, config: chess.defaults, labels: [], strikes: [0, 0], maxStrikes: 3, clock: v });
  const calm = p(chessView(192_000, 178_000));
  assert.ok(calm.startsWith('You are playing Chess'), 'no banner while there is plenty of time');
  assert.match(
    calm,
    /== CLOCK \(SPEED GAME\) ==\nThis is a speed game: move briskly\. Each player has 4:00 in total for ALL of their moves in this game \(no increment\)\. Your clock runs only while you are answering\. If your clock reaches 0:00 you lose the game on time at once/,
  );
  assert.match(calm, /Your time left: 3:12\. Your opponent's time left: 2:58\. You have made 14 moves\./);
  assert.match(calm, /Suggested time for this move: about 5 seconds\. Your reply this move is limited to about 1,200 tokens, including any hidden reasoning, so think briefly and give your move\. A good move in time beats a perfect move too late\./);
  assert.match(calm, /Clock: you have 3:12 left \(your opponent 2:58\)\. Use about 5 seconds and at most about 1,200 tokens for this move\.\n\nIt is your turn: White to play/);
  assert.ok(calm.indexOf('== CLOCK') < calm.indexOf('== RULES =='), 'the clock comes before the rules');
  assert.doesNotMatch(calm, /running down|TIME PRESSURE|TIME TROUBLE/);
  assert.match(calm, /Think briefly \(your clock is running\)/);
  // No backticks except the legal-move list; never a model name.
  const ticks = [...calm.matchAll(/`([^`]+)`/g)].map((m) => m[1]!);
  assert.ok(ticks.every((t) => /^MOVE: /.test(t)));

  const low = p(chessView(100_000, 150_000));
  assert.match(low, /Your clock is running down\. Keep your moves quick\./);
  assert.doesNotMatch(low, /TIME PRESSURE/);

  const pressure = p(chessView(45_000, 150_000));
  assert.ok(pressure.startsWith('== TIME PRESSURE ==\nUnder a minute left. Decide fast: a quick sensible move now beats running out of time.'));
  assert.match(pressure, /== TIME PRESSURE ==\nUnder a minute left\.[^\n]*\n\nIt is your turn/, 'repeated right before the turn line');

  const trouble = p(chessView(14_000, 150_000));
  assert.ok(trouble.startsWith('== TIME TROUBLE: 0:14 LEFT ==\nMove NOW. Do not analyse. Give your move immediately or you lose on time.'));
  assert.match(trouble, /== TIME TROUBLE: 0:14 LEFT ==\nMove NOW\.[^\n]*\n\nIt is your turn/);
  assert.doesNotMatch(trouble, /TIME PRESSURE/);

  const oppTrouble = p(chessView(150_000, 12_000));
  assert.match(oppTrouble, /Your opponent is in time trouble \(0:12 left\) — keep the pressure on by moving quickly\./);
  assert.match(p(chessView(150_000, 120_000)), /Your opponent has less time left than you\./);

  assert.equal(urgencyTier(240_000, 240_000), 'calm');
  assert.equal(urgencyTier(119_999, 240_000), 'low');
  assert.equal(urgencyTier(59_999, 240_000), 'pressure');
  assert.equal(urgencyTier(19_999, 240_000), 'trouble');
  assert.equal(urgencyTier(20_000, 240_000), 'pressure');

  // Game words: poker and debate.
  const pv = { ...chessView(45_000, 30_000), words: poker.clock! };
  assert.match(clockSection(pv), /act briskly[\s\S]*ALL of their decisions[\s\S]*every remaining decision of yours in this session is made for you/);
  assert.match(clockReminder(pv), /for this decision\.\n\n== TIME PRESSURE ==\nUnder a minute left\. Decide fast: a quick sensible decision now/);
  const dv = { ...chessView(10_000, 30_000), words: debate.clock! };
  assert.match(clockReminder(dv), /== TIME TROUBLE: 0:10 LEFT ==\nSpeak NOW\. Do not analyse\. Write your speech immediately or your remaining speeches are lost\./);
});

test('prompt: Classic prompts are byte-identical to before speed games existed (no clock text at all)', () => {
  const h = (x: string) => createHash('sha256').update(x).digest('hex').slice(0, 16);
  const out: string[] = [];
  const s = fromFen(START_FEN);
  out.push(buildMovePrompt({ game: chess, state: chess.play(s, 'e2e4'), side: 1, config: chess.defaults, labels: ['e4'], strikes: [0, 1], maxStrikes: 3 }));
  const c = connect4.play(connect4.setup(createRng(1), connect4.defaults), '4');
  out.push(buildMovePrompt({ game: connect4, state: c, side: 1, config: connect4.defaults, labels: ['4'], strikes: [0, 0], maxStrikes: 3, retry: { reply: 'MOVE: 9', extracted: '9', error: 'x' } }));
  const pc = { ...poker.defaults, hands: 10 };
  let ps = poker.setup(createRng(5), pc);
  ps = poker.play(ps, poker.legalMoves(ps)[1]!);
  out.push(poker.prompt!(ps, poker.toMove(ps), { config: pc, strikes: [0, 0], maxStrikes: 3 }));
  for (const g of [debate, courtroom]) {
    let ds = g.setup(createRng(3), g.defaults);
    ds = g.play(ds, 'An opening speech with several words.');
    out.push(g.prompt!(ds, 1, { config: g.defaults, strikes: [0, 0], maxStrikes: 3 }));
    out.push(g.judge!.material(g.play(ds, ''), 1));
  }
  // Hashes of the same prompts built by the code before the clock was added.
  assert.deepEqual(out.map(h), ['4af4bd9b377b105e', 'f063959bb05fe730', 'a0f65592e233a8b4', 'd2b938e3846e1a8c', '3fefd0375f4fab08', '5a80d31931d568e5', '1a94ed2541cceb1e']);
  for (const p of out) assert.doesNotMatch(p, /CLOCK|TIME PRESSURE|TIME TROUBLE|speed game/);
});

// ─────────────────────────────── Recorder ───────────────────────────────

const contestant: Contestant = { id: 'x', label: 'X', vendor: 'V', provider: 'p', model: 'm', color: '#000000', enabled: true, pricing: { inputPerM: 1, outputPerM: 10 } };
const usage = { inputTokens: 100, outputTokens: 20, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 };
const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const t = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => (clearTimeout(t), reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }))), { once: true });
  });
const adapterTaking = (ms: number) => ({
  async complete(req: CompletionRequest) {
    const t0 = Date.now();
    await sleep(ms, req.signal);
    return { text: 'MOVE: e4', usage, startedAt: t0, ttftMs: 5, totalMs: Date.now() - t0, stopReason: 'end' as const, rawStopReason: 'end', servedModel: 'm' };
  },
});

test('recorder: a per-call time limit aborts the call when the clock runs out; the call is recorded and its cost counted', async () => {
  const rec = createRecorder({ target: { contestant, adapter: adapterTaking(2000) as never, semaphore: new Semaphore(1) }, policy: { maxRetries: 0, temperature: 0, defaultMaxOutputTokens: 500 }, signal: new AbortController().signal, maxOutputTokens: 500 });
  const t0 = Date.now();
  await assert.rejects(rec.handle.complete({ messages: [{ role: 'user', content: 'move?' }], maxOutputTokens: 300, timeLimitMs: 80 }), (e: Error) => e instanceof OutOfTimeError && e.name === 'OutOfTimeError');
  assert.ok(Date.now() - t0 < 1000, 'aborted at the limit, not after the reply');
  const e = rec.transcript[0]!;
  assert.equal(e.rawStopReason, 'out_of_time');
  assert.equal(e.timeLimitMs, 80);
  assert.equal(e.costEstimated, true);
  assert.equal(e.usage.outputTokens, 300, 'upper bound: the whole output limit');
  assert.ok(e.costUsd > 0);
  assert.ok(Math.abs(rec.costUsd - e.costUsd) < 1e-12, 'counted in the recorder total');
  assert.equal(rec.apiCalls, 1);
});

test('recorder: queueing for a provider slot never counts against the clock', async () => {
  const sem = new Semaphore(1);
  const release = await sem.acquire();
  setTimeout(release, 300);
  const rec = createRecorder({ target: { contestant, adapter: adapterTaking(20) as never, semaphore: sem }, policy: { maxRetries: 0, temperature: 0, defaultMaxOutputTokens: 50 }, signal: new AbortController().signal, maxOutputTokens: 50 });
  const r = await rec.handle.complete({ messages: [{ role: 'user', content: 'move?' }], timeLimitMs: 200 });
  assert.equal(r.text, 'MOVE: e4', 'waited 300 ms in the queue, then answered in time');
  assert.ok(r.totalMs < 200);
});

// ─────────────────────────────── Planning & tournaments ───────────────────────────────

test('planning: Speed 4+0 by default, recorded and fingerprinted; Classic fingerprint unchanged; copy & paste refused in speed', () => {
  const speed = tournament.planTournament({ game: 'chess', contestantIds: ['cbot-0', 'cbot-1'], seeding: 'manual' });
  assert.deepEqual(speed.settings.timeControl, { minutes: 4, incrementSec: 0 });
  const classic = tournament.planTournament({ game: 'chess', contestantIds: ['cbot-0', 'cbot-1'], seeding: 'manual', timeControl: null });
  assert.equal(classic.settings.timeControl, null);
  assert.notEqual(speed.fingerprint, classic.fingerprint);
  assert.notEqual(speed.fingerprint, tournament.planTournament({ game: 'chess', contestantIds: ['cbot-0', 'cbot-1'], seeding: 'manual', timeControl: { minutes: 3, incrementSec: 2 } }).fingerprint);
  // Classic hashes exactly the settings it always did (no time-control key).
  const { timeControl: _tc, ...rest } = classic.settings;
  assert.equal(tournament.arenaFingerprint(classic.gameHash, rest as never, classic.seed), classic.fingerprint);
  assert.throws(() => tournament.planTournament({ game: 'chess', contestantIds: ['cbot-0', 'cpaste'] }), /Speed games need API models; switch to Classic to include copy & paste models/);
  assert.ok(tournament.planTournament({ game: 'chess', contestantIds: ['cbot-0', 'cpaste'], timeControl: null }).entrants.some((e) => e.manual));
  for (const g of ['connect4', 'poker', 'debate', 'courtroom']) assert.deepEqual(tournament.planTournament({ game: g, contestantIds: ['cbot-0', 'cbot-1'] }).settings.timeControl, { minutes: 4, incrementSec: 0 }, g);
});

test('estimate: in a speed game the clock caps each player’s output, so the estimate is lower than Classic', () => {
  const speed = tournament.estimateTournament({ game: 'chess', contestantIds: ['cpricey', 'cbot-1'], seeding: 'manual' });
  const classic = tournament.estimateTournament({ game: 'chess', contestantIds: ['cpricey', 'cbot-1'], seeding: 'manual', timeControl: null });
  assert.ok(speed.estCostUsd < classic.estCostUsd, `${speed.estCostUsd} < ${classic.estCostUsd}`);
  assert.ok(speed.estCostUsdHigh < classic.estCostUsdHigh);
  assert.ok(speed.estCostUsd > 0);
  assert.ok(speed.warnings.some((w) => /limited by its clock/.test(w)));
});

test('tournament: a speed tournament records both clocks per move, the clock per game, and flags per entrant', async () => {
  const id = tournament.startTournament({ game: 'connect4', contestantIds: ['cbot-0', 'cbot-1'], seeding: 'manual' });
  await tournament.waitForTournament(id);
  const d = tournament.tournamentDetail(id)!;
  assert.equal(d.manifest.status, 'completed');
  assert.deepEqual(d.manifest.settings.timeControl, { minutes: 4, incrementSec: 0 });
  const g = tournament.gameRecord(id, 'R1-M1-g1')!;
  assert.deepEqual(g.clock!.initialMs, 240_000);
  assert.deepEqual(g.flags, [0, 0]);
  assert.ok(g.moves.every((m) => m.clockMs && m.clockMs.length === 2));
  assert.match(g.transcripts[0][0]!.messages[0]!.content, /You are playing Connect Four/);
  assert.doesNotMatch(g.transcripts[0][0]!.messages[0]!.content, /== CLOCK/, 'the Random Baseline answers instantly: no allowance');
  assert.ok(d.state.standings.every((r) => r.flags === 0));
});

test('bracket: flags are counted per entrant and per match (like illegal moves); tie-breaks unchanged', () => {
  const entrants = ['a', 'b'].map((id, i) => ({ id, label: id, seed: i + 1 }) as unknown as ArenaEntrant);
  const matches = buildKnockout(['a', 'b']);
  const settings = { format: 'knockout', gamesPerMatch: 2, suddenDeath: 0, game: { maxPlies: 10, listLegalMoves: true } } as never;
  const lite = (n: number, players: [string, string], winner: 0 | 1 | null, flags: [number, number]): ArenaGameLite =>
    ({ key: `R1-M1-g${n}`, tournamentId: 't', matchId: 'R1-M1', gameNo: n, seed: 1, players, status: 'ok', winner, reason: '', initial: null, strikes: [0, 0], illegal: [0, 0], metrics: [{ costUsd: 0 }, { costUsd: 0 }], startedAt: '', finishedAt: '', plies: 1, lastSnapshot: null, flags }) as unknown as ArenaGameLite;
  const st = computeState({ seed: 1, entrants, settings, matches }, [lite(1, ['a', 'b'], 1, [1, 0]), lite(2, ['b', 'a'], 0, [0, 0])]);
  assert.deepEqual(st.matches[0]!.flags, [1, 0]);
  assert.equal(st.standings.find((r) => r.contestantId === 'a')!.flags, 1);
  assert.equal(st.standings.find((r) => r.contestantId === 'b')!.flags, 0);
});

// ─────────────────────────────── Viewer helpers ───────────────────────────────

test('viewer: time-trouble states, call-outs and the ticking clock', () => {
  assert.equal(clockDisplayState(240_000), 'normal');
  assert.equal(clockDisplayState(59_999), 'low');
  assert.equal(clockDisplayState(29_999), 'critical');
  assert.equal(clockDisplayState(19_999), 'trouble');
  assert.equal(clockDisplayState(0), 'flagged');
  assert.equal(clockDisplayState(50_000, true), 'flagged');
  assert.equal(clockCallout('Black', 12_400), 'Black is down to 0:12!');
  assert.equal(clockCallout('White', 45_000), 'White has under a minute left.');
  assert.equal(clockCallout('White', 0, true), "White's flag has fallen!");
  assert.equal(clockCallout('White', 200_000), null);
  assert.equal(tickingClock(60_000, 1_000, 11_000), 50_000);
  assert.equal(tickingClock(5_000, 0, 60_000), 0);
});
