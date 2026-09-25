/**
 * Mock-mode Arena simulator. Plays whole tournaments with the REAL game
 * engines and bracket logic (src/arena/*), using scripted policies whose
 * strength differs per model, then reveals them over time so the live view,
 * the bracket and the replays can be demoed with no server and no API keys.
 */
import { GAMES } from '../../../src/arena/games/index.ts';
import { attacked, inCheck, material, sqIndex, type ChessState } from '../../../src/arena/games/chess.ts';
import type { C4State } from '../../../src/arena/games/connect4.ts';
import { buildKnockout, buildRoundRobin, computeState, playableSlots } from '../../../src/arena/bracket.ts';
import { buildMovePrompt } from '../../../src/arena/prompt.ts';
import { createRng, hashString } from '../../../src/core/rng.ts';
import type { Rng, TranscriptEntry } from '../types.ts';
import type {
  ArenaEntrant,
  ArenaGame,
  ArenaGameLite,
  ArenaGameRecord,
  ArenaMove,
  ArenaSettings,
  GameSlot,
  LiveGame,
  Side,
  SideMetrics,
  TournamentManifest,
} from '../arena/types.ts';
import type { ContestantView } from '../types.ts';
import type { PokerState } from '../../../src/arena/games/poker.ts';
import { countWords, type DebateState } from '../../../src/arena/games/debate.ts';
import { verdictSnapshot } from '../../../src/arena/judged.ts';
import { pokerChoice, simulateJudging, speechFor } from './arenaFormatsSim.ts';

/** How strong each demo model plays (0 = random). */
export const STRENGTH: Record<string, number> = {
  'meridian-atlas-4-ultra': 0.95,
  'kestrel-kite-reasoner': 0.9,
  'helios-nova-3-pro': 0.8,
  'meridian-atlas-4-mini': 0.62,
  'manual-orbit-chat': 0.55,
  'obsidian-sable-large': 0.5,
  'helios-quill-flash': 0.4,
  'random-baseline': 0,
};
const strengthOf = (id: string) => STRENGTH[id] ?? 0.5;

// ─────────────────────────────── Policies ───────────────────────────────

interface Choice {
  move: string;
  why: string;
}

function c4Policy(game: ArenaGame<C4State>, s: C4State, strength: number, rng: Rng): Choice {
  const legal = game.legalMoves(s);
  if (strength === 0) return { move: rng.pick(legal), why: 'Picked a column at random.' };
  const me = s.turn;
  const wins = legal.filter((m) => game.outcome(game.play(s, m))?.winner === me);
  if (wins.length && rng.chance(0.55 + 0.45 * strength)) return { move: wins[0]!, why: `Column ${wins[0]} completes four in a row.` };
  const flipped: C4State = { ...s, turn: (1 - me) as Side };
  const threats = legal.filter((m) => game.outcome(game.play(flipped, m))?.winner === 1 - me);
  if (threats.length && rng.chance(0.35 + 0.65 * strength)) return { move: threats[0]!, why: `${me === 0 ? 'Yellow' : 'Red'} threatens four in column ${threats[0]}; I must block it.` };
  const safe = legal.filter((m) => {
    const after = game.play(s, m);
    return !game.legalMoves(after).some((r) => game.outcome(game.play(after, r))?.winner === 1 - me);
  });
  const pool = safe.length && rng.chance(strength) ? safe : legal;
  const weight = (m: string) => Math.pow(4 - Math.abs(4 - Number(m)), 1 + strength * 2) * (0.5 + rng.next());
  const best = pool.reduce((a, b) => (weight(b) > weight(a) ? b : a));
  const why = Math.abs(Number(best) - 4) <= 1 ? `Column ${best} keeps building in the centre, where most lines of four pass.` : `Column ${best} avoids handing my opponent a winning drop.`;
  return { move: best, why };
}

const VAL: Record<string, number> = { P: 1, N: 3, B: 3, R: 5, Q: 9, K: 0 };
const NAME: Record<string, string> = { P: 'pawn', N: 'knight', B: 'bishop', R: 'rook', Q: 'queen', K: 'king' };

function chessPolicy(game: ArenaGame<ChessState>, s: ChessState, strength: number, rng: Rng): Choice {
  const legal = game.legalMoves(s);
  if (strength === 0) return { move: rng.pick(legal), why: 'Picked one of the listed legal moves at random.' };
  const me = s.turn;
  const opp = me === 'w' ? 'b' : 'w';
  let best = legal[0]!;
  let bestScore = -Infinity;
  let bestWhy = '';
  for (const m of legal) {
    const from = sqIndex(m.slice(0, 2));
    const to = sqIndex(m.slice(2, 4));
    const piece = s.board[from]!.toUpperCase();
    const victim = s.board[to] ? s.board[to]!.toUpperCase() : '';
    const next = game.play(s, m);
    let score = rng.next() * (2 + 14 * (1 - strength));
    let why = '';
    const check = inCheck(next);
    if (check && game.legalMoves(next).length === 0 && rng.chance(0.4 + 0.6 * strength)) {
      score += 1000;
      why = 'This is checkmate.';
    }
    if (victim) {
      score += VAL[victim]! * 10 * strength;
      why ||= `Taking the ${NAME[victim]} on ${m.slice(2, 4)} wins material.`;
    }
    if (m.length === 5) {
      score += 70 * strength;
      why ||= 'Promoting the pawn to a queen.';
    }
    if (attacked(next.board, to, opp) && !attacked(next.board, to, me)) {
      score -= VAL[piece]! * 9 * strength;
    }
    if (check) {
      score += 2 * strength;
      why ||= `${game.label(s, m)} gives check and keeps the initiative.`;
    }
    const centre = [27, 28, 35, 36].includes(to) ? 1.5 * strength : 0;
    score += centre;
    if (!why) why = centre ? 'Fighting for the centre.' : piece === 'P' ? 'A useful pawn move that gains space.' : `Improving the ${NAME[piece]}.`;
    if (score > bestScore) {
      bestScore = score;
      best = m;
      bestWhy = why;
    }
  }
  return { move: best, why: bestWhy };
}

// ─────────────────────────────── One game ───────────────────────────────

export interface SimGame {
  record: ArenaGameRecord;
  /** Display time per move (ms), used for live pacing. */
  pace: number[];
}

function emptyMetrics(): SideMetrics {
  return { costUsd: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, apiCalls: 0, retries: 0, ms: 0 };
}

export function simulateGame(opts: {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  game: ArenaGame<any>;
  settings: ArenaSettings;
  tournamentId: string;
  slot: GameSlot & { matchId: string };
  contestants: Map<string, ContestantView>;
  fast: boolean;
  /** Spending cap: checked before every model call, exactly like the real engine. */
  budget?: { spent: number; cap: number };
  /** Judge pool for judged games (debate, courtroom). */
  judgePool?: ContestantView[];
  noJudges?: boolean;
}): SimGame {
  const { game, settings, slot } = opts;
  if ((game.engine ?? 'board') !== 'board') return simulateFormatGame(opts);
  const rng = createRng(hashString(`${opts.tournamentId}|${slot.key}`));
  let state = game.setup(createRng(slot.seed).fork('setup'), settings.game);
  const initial = game.snapshot(state);
  const moves: ArenaMove[] = [];
  const pace: number[] = [];
  const labels: string[] = [];
  const strikes: [number, number] = [0, 0];
  const illegal: [number, number] = [0, 0];
  const metrics: [SideMetrics, SideMetrics] = [emptyMetrics(), emptyMetrics()];
  const isChess = game.id === 'chess';
  let outcome = game.outcome(state);
  if (slot.suddenDeath) {
    const open = createRng(slot.seed).fork('opening');
    for (let i = 0; i < 2 && !outcome; i++) {
      const move = open.pick(game.legalMoves(state));
      const side = game.toMove(state);
      const label = game.label(state, move);
      state = game.play(state, move);
      labels.push(label);
      moves.push({ ply: moves.length + 1, side, move, label, attempts: [], forfeit: false, opening: true, ms: 0, costUsd: 0, inputTokens: 0, outputTokens: 0, snapshot: game.snapshot(state) });
      pace.push(900);
      outcome = game.outcome(state);
    }
  }
  let stopped = false;
  while (!outcome) {
    if (moves.length >= settings.game.maxPlies) {
      outcome = game.adjudicate(state);
      break;
    }
    if (opts.budget && opts.budget.spent >= opts.budget.cap) {
      stopped = true;
      break;
    }
    const side = game.toMove(state);
    const who = slot.players[side];
    const c = opts.contestants.get(who);
    const strength = strengthOf(who);
    const choice = isChess ? chessPolicy(game, state, strength, rng) : c4Policy(game, state, strength, rng);
    const label = game.label(state, choice.move);
    const shown = isChess ? (rng.chance(0.5) ? label : choice.move) : choice.move;
    const attempts: ArenaMove['attempts'] = [];
    const think = (strength === 0 ? 0.02 : 0.4 + strength) * (opts.fast ? 0.35 : 1);
    // Weaker models occasionally write an illegal move first (and fix it on the retry).
    if (strength > 0 && rng.chance((1 - strength) * 0.12)) {
      const bad = isChess ? rng.pick(['Ke3', 'Qxh7', 'O-O', 'e5e4', 'Nf6']) : String(rng.pick(['8', '0', ...game.legalMoves(state).length < 7 ? ['4'] : []]));
      const parsed = game.parseMove(state, bad);
      if (!parsed.ok) {
        attempts.push({ text: `${rng.pick(['Let me look at the position.', 'Scanning for threats first.', 'Considering my options.'])}\n\nMOVE: ${bad}`, extracted: bad, error: parsed.error, ms: Math.round(2500 * think + rng.next() * 3000) });
        illegal[side]++;
      }
    }
    const ms = Math.round((isChess ? 4000 : 2500) * think + rng.next() * 9000 * think);
    const material0 = isChess ? material((state as ChessState).board) : null;
    const legalNow = game.legalMoves(state);
    const others = rng.shuffle(legalNow.filter((m) => m !== choice.move)).slice(0, 2);
    const cands = rng.shuffle([choice.move, ...others]).map((m) => (isChess ? game.label(state, m) : m));
    const reasoning =
      strength === 0
        ? choice.why
        : isChess
          ? [
              'Let me look at the position.',
              `- Material: White ${material0!.w}, Black ${material0!.b}.`,
              `- ${rng.pick(['No immediate threats against my king.', 'I should check every capture first.', 'The opponent’s last move left a weak square.', 'Development comes before attacking.', 'The long diagonal needs watching.'])}`,
              `Candidate moves: ${cands.join(', ')}.`,
              choice.why,
            ].join('\n')
          : [
              `Scanning the board: ${labels.length} discs played so far.`,
              `- ${rng.pick(['No three-in-a-row for my opponent yet.', 'The centre column is still the most valuable.', 'I must not give my opponent a drop on top of my disc.', 'Looking for a double threat.'])}`,
              `Candidate columns: ${cands.join(', ')}.`,
              choice.why,
            ].join('\n');
    attempts.push({ text: `${reasoning}\n\nMOVE: ${shown}`, extracted: shown, ms });
    const inTok = (isChess ? 1050 : 620) + labels.length * (isChess ? 4 : 3);
    const outTok = strength === 0 ? 12 : Math.round(250 + strength * 2600 * (0.6 + rng.next() * 0.8));
    const calls = attempts.length;
    const price = c?.pricing ?? { inputPerM: 0, outputPerM: 0 };
    const manual = c?.providerType === 'manual';
    const cost = manual ? 0 : (calls * inTok * price.inputPerM + calls * outTok * price.outputPerM) / 1e6;
    if (opts.budget) opts.budget.spent += cost;
    const totalMs = attempts.reduce((sum, a) => sum + a.ms, 0);
    state = game.play(state, choice.move);
    labels.push(label);
    const displayMs = opts.fast ? 1100 + Math.round(rng.next() * 1300) : totalMs;
    moves.push({ ply: moves.length + 1, side, move: choice.move, label, attempts, forfeit: false, ms: opts.fast ? displayMs : totalMs, costUsd: Math.round(cost * 1e8) / 1e8, inputTokens: inTok * calls, outputTokens: outTok * calls, snapshot: game.snapshot(state) });
    pace.push(displayMs);
    const m = metrics[side];
    m.costUsd += cost;
    m.inputTokens += inTok * calls;
    m.outputTokens += outTok * calls;
    m.reasoningTokens += Math.round(outTok * calls * 0.7);
    m.apiCalls += calls;
    m.ms += opts.fast ? displayMs : totalMs;
    outcome = game.outcome(state);
  }
  for (const m of metrics) m.costUsd = Math.round(m.costUsd * 1e6) / 1e6;
  const record: ArenaGameRecord = {
    key: slot.key,
    tournamentId: opts.tournamentId,
    matchId: slot.matchId,
    gameNo: slot.gameNo,
    seed: slot.seed,
    players: slot.players,
    status: stopped ? 'cancelled' : 'ok',
    winner: stopped ? null : outcome!.winner,
    reason: stopped ? 'Stopped: budget cap reached' : outcome!.reason,
    moves,
    initial,
    strikes,
    illegal,
    metrics,
    transcripts: [[], []],
    startedAt: '',
    finishedAt: '',
  };
  return { record, pace };
}

/** Poker, debate and courtroom: the same records the 'turns' and 'debate' engines write. */
function simulateFormatGame(opts: Parameters<typeof simulateGame>[0]): SimGame {
  const { game, settings, slot } = opts;
  const engine = game.engine!;
  const rng = createRng(hashString(`${opts.tournamentId}|${slot.key}`));
  let state = game.setup(createRng(slot.seed).fork('setup'), settings.game);
  const initial = game.snapshot(state);
  const moves: ArenaMove[] = [];
  const pace: number[] = [];
  const strikes: [number, number] = [0, 0];
  const illegal: [number, number] = [0, 0];
  const metrics: [SideMetrics, SideMetrics] = [emptyMetrics(), emptyMetrics()];
  let stopped = false;
  while (!game.outcome(state) && moves.length < settings.game.maxPlies) {
    if (opts.budget && opts.budget.spent >= opts.budget.cap) {
      stopped = true;
      break;
    }
    const side = game.toMove(state);
    const who = slot.players[side];
    const c = opts.contestants.get(who);
    const strength = strengthOf(who);
    const attempts: ArenaMove['attempts'] = [];
    let move: string;
    let note: string | undefined;
    let inTok: number;
    let outTok: number;
    let ms: number;
    let display: number;
    if (engine === 'turns') {
      const choice = pokerChoice(state as PokerState, strength, rng);
      // Weaker models sometimes size a raise below the minimum first (and fix it on the retry).
      if (strength > 0 && choice.move.startsWith('raise') && rng.chance((1 - strength) * 0.15)) {
        const p = game.parseMove(state, 'raise 1');
        if (!p.ok) {
          attempts.push({ text: `I want to raise a little.\nACTION: raise 1`, extracted: 'raise 1', error: p.error, ms: 1500 + Math.round(rng.next() * 3000) });
          illegal[side]++;
        }
      }
      move = choice.move;
      note = strength === 0 ? undefined : choice.note;
      ms = Math.round((1500 + rng.next() * 6000) * (0.4 + strength));
      attempts.push({ text: choice.text, extracted: choice.text.split('ACTION: ').pop() ?? move, ms });
      inTok = 1150 + (state as PokerState).results.length * 6;
      outTok = strength === 0 ? 10 : Math.round(120 + strength * 900 * (0.5 + rng.next()));
      display = opts.fast ? 900 + Math.round(rng.next() * 900) : ms;
    } else {
      const text = speechFor(state as DebateState, strength, rng);
      const p = game.parseMove(state, text);
      move = p.ok ? p.move : '';
      ms = Math.round((9000 + rng.next() * 20000) * (0.5 + strength));
      attempts.push({ text, extracted: null, ms });
      inTok = (game.id === 'courtroom' ? 1500 : 800) + (state as DebateState).speeches.length * 230;
      outTok = Math.round(countWords(text) * 1.4 + strength * 700 * rng.next());
      display = opts.fast ? 5200 + Math.round(rng.next() * 2600) : ms;
    }
    const calls = attempts.length;
    const price = c?.pricing ?? { inputPerM: 0, outputPerM: 0 };
    const cost = c?.providerType === 'manual' ? 0 : (calls * inTok * price.inputPerM + calls * outTok * price.outputPerM) / 1e6;
    if (opts.budget) opts.budget.spent += cost;
    const label = game.label(state, move);
    state = game.play(state, move);
    const totalMs = attempts.reduce((a, x) => a + x.ms, 0);
    moves.push({ ply: moves.length + 1, side, move, label, attempts, forfeit: false, ms: opts.fast ? display : totalMs, costUsd: Math.round(cost * 1e8) / 1e8, inputTokens: inTok * calls, outputTokens: outTok * calls, snapshot: game.snapshot(state), ...(note ? { note } : {}) });
    pace.push(display);
    const m = metrics[side];
    m.costUsd += cost;
    m.inputTokens += inTok * calls;
    m.outputTokens += outTok * calls;
    m.reasoningTokens += Math.round(outTok * calls * 0.5);
    m.apiCalls += calls;
    m.ms += opts.fast ? display : totalMs;
  }
  for (const m of metrics) m.costUsd = Math.round(m.costUsd * 1e6) / 1e6;
  let winner: Side | null = null;
  let reason = 'Stopped: budget cap reached';
  let judging: ArenaGameRecord['judging'];
  if (!stopped && engine === 'debate') {
    const players = slot.players.map((p) => opts.contestants.get(p)!) as [ContestantView, ContestantView];
    judging = simulateJudging({ game, state: state as DebateState, seed: slot.seed, players, strengths: [strengthOf(slot.players[0]), strengthOf(slot.players[1])], pool: opts.judgePool ?? [], rng: rng.fork('judges'), noJudges: opts.noJudges });
    if (opts.budget) opts.budget.spent += judging.costUsd;
    winner = judging.status === 'judged' ? judging.winner : null;
    const votes = `${Math.max(...judging.votes)}–${Math.min(...judging.votes)}`;
    reason = judging.status !== 'judged' ? 'Awaiting human judging' : winner === null ? judging.decision : `${judging.decision}${judging.verdicts.length > 1 ? ` (${votes})` : ''}`;
    moves.push({
      ply: moves.length + 1,
      side: winner ?? 0,
      move: 'verdict',
      label: judging.status === 'judged' ? `Verdict: ${judging.decision}` : 'Awaiting human judging',
      attempts: [],
      forfeit: false,
      ms: opts.fast ? 6000 : 45000,
      costUsd: 0,
      inputTokens: 0,
      outputTokens: 0,
      snapshot: { ...(game.snapshot(state) as object), verdict: verdictSnapshot(judging) },
      kind: 'verdict',
    });
    pace.push(opts.fast ? 6000 : 45000);
  } else if (!stopped) {
    const out = game.outcome(state) ?? game.adjudicate(state);
    winner = out.winner;
    reason = out.reason;
  }
  const record: ArenaGameRecord = {
    key: slot.key,
    tournamentId: opts.tournamentId,
    matchId: slot.matchId,
    gameNo: slot.gameNo,
    seed: slot.seed,
    players: slot.players,
    status: stopped ? 'cancelled' : judging?.status === 'awaiting-human' ? 'awaiting-judges' : 'ok',
    winner,
    reason,
    moves,
    initial,
    strikes,
    illegal,
    metrics,
    transcripts: [[], []],
    startedAt: '',
    finishedAt: '',
    ...(!stopped && game.margin ? { margin: game.margin(state) } : {}),
    ...(judging ? { judging } : {}),
  };
  return { record, pace };
}

/** Rebuild the per-move prompts for a simulated game (the transcript tab). */
export function transcriptsFor(record: ArenaGameRecord, gameId: string, settings: ArenaSettings, contestants: Map<string, ContestantView>): [TranscriptEntry[], TranscriptEntry[]] {
  const game = GAMES[gameId]!;
  let state = game.setup(createRng(record.seed).fork('setup'), settings.game);
  const out: [TranscriptEntry[], TranscriptEntry[]] = [[], []];
  const labels: string[] = [];
  for (const mv of record.moves) {
    if (!mv.opening) {
      mv.attempts.forEach((a, i) => {
        const prev = mv.attempts[i - 1];
        const prompt = game.prompt
          ? game.prompt(state, mv.side, { config: settings.game, strikes: [0, 0], maxStrikes: settings.maxStrikes })
          : buildMovePrompt({ game, state, side: mv.side, config: settings.game, labels, strikes: [0, 0], maxStrikes: settings.maxStrikes, retry: prev ? { reply: prev.text, extracted: prev.extracted, error: prev.error ?? '' } : undefined });
        const c = contestants.get(record.players[mv.side]);
        const n = mv.attempts.length;
        out[mv.side].push({
          label: `${game.sides[mv.side].name} · move ${mv.ply}${i > 0 ? ' · retry' : ''}`,
          messages: [{ role: 'user', content: prompt }],
          response: a.text,
          usage: { inputTokens: Math.round(mv.inputTokens / n), outputTokens: Math.round(mv.outputTokens / n), reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 },
          ttftMs: Math.round(a.ms * 0.3),
          totalMs: a.ms,
          stopReason: 'end',
          rawStopReason: c?.providerType === 'manual' ? 'manual' : 'stop',
          costUsd: mv.costUsd / n,
          retries: 0,
        });
      });
    }
    if (mv.kind !== 'verdict' && (mv.move || game.engine === 'debate')) {
      state = game.play(state, mv.move);
      labels.push(mv.label);
    }
  }
  return out;
}

// ─────────────────────────────── Whole tournaments ───────────────────────────────

export interface SimTournament {
  manifest: TournamentManifest;
  games: SimGame[];
  /** Start / end offsets (ms after t0) of each game, and cumulative move times. */
  timeline: Array<{ start: number; end: number; moveAt: number[] }>;
  /** Epoch ms the (virtual) tournament started; far in the past = already finished. */
  t0: number;
  contestants: Map<string, ContestantView>;
  cancelledAt?: number;
  /** The simulated spend reached the cap: the tournament stops like the real engine does. */
  budgetStopped?: boolean;
  /** How it was built (to rebuild on resume with a new cap). */
  build?: Parameters<typeof buildSimTournament>[0];
}

export function lite(r: ArenaGameRecord): ArenaGameLite {
  const { transcripts: _t, moves, ...rest } = r;
  if (rest.judging) rest.judging = { ...rest.judging, transcript: [] };
  return { ...rest, plies: moves.filter((m) => m.move && m.kind !== 'verdict').length, lastSnapshot: moves.length ? moves[moves.length - 1]!.snapshot : r.initial };
}

export function buildSimTournament(opts: {
  id: string;
  name: string;
  gameId: string;
  entrants: ContestantView[];
  format: 'knockout' | 'round-robin';
  gamesPerMatch: number;
  createdAt: string;
  t0: number;
  fast: boolean;
  maxCostUsd?: number;
  seeding?: 'index' | 'manual';
  options?: Record<string, string>;
  judgePool?: ContestantView[];
  noJudges?: boolean;
}): SimTournament {
  const game = GAMES[opts.gameId]!;
  let gameCfg = { ...game.defaults };
  if (game.configure) gameCfg = game.configure(gameCfg, Object.fromEntries((game.options ?? []).map((o) => [o.key, opts.options?.[o.key] ?? o.default])));
  const entrants: ArenaEntrant[] = opts.entrants.map((c, i) => ({ ...c, seed: i + 1, index: opts.seeding === 'manual' ? null : Math.round(strengthOf(c.id) * 800) / 10, manual: c.providerType === 'manual' || undefined, baseline: c.providerType === 'mock' || c.id === 'random-baseline' || undefined }));
  const settings: ArenaSettings = {
    format: opts.format,
    seeding: opts.seeding ?? 'index',
    gamesPerMatch: opts.gamesPerMatch,
    suddenDeath: opts.format === 'knockout' && game.suddenDeath !== false ? 2 : 0,
    maxStrikes: 3,
    concurrency: 1,
    temperature: 0,
    maxOutputTokens: 16000,
    maxCostUsd: opts.maxCostUsd,
    game: gameCfg,
    protocolVersion: '2026.09',
  };
  const matches = opts.format === 'knockout' ? buildKnockout(entrants.map((e) => e.id)) : buildRoundRobin(entrants.map((e) => e.id));
  const manifest: TournamentManifest = {
    id: opts.id,
    name: opts.name,
    status: 'completed',
    createdAt: opts.createdAt,
    startedAt: opts.createdAt,
    harnessVersion: '1.0.0',
    gitCommit: 'a1b2c3d',
    node: 'v24.4.0',
    platform: 'win32-x64',
    game: { id: game.id, name: game.name, version: game.version, hash: `${({ chess: '7c1e', connect4: '3f9a', poker: '51ad', debate: 'd3b8', courtroom: 'c0a7' } as Record<string, string>)[game.id] ?? '0000'}d2b8e04c` },
    fingerprint: hashString(`${opts.gameId}|${opts.format}|${opts.gamesPerMatch}`).toString(16).padStart(8, '0') + '4e1a',
    seed: 1,
    entrants,
    settings,
    matches,
  };
  const contestants = new Map(opts.entrants.map((c) => [c.id, c]));
  const games: SimGame[] = [];
  const spec = { seed: 1, entrants, settings, matches };
  // Same budget rule as the real engine: checked before every call, so at most one call goes over.
  const budget = opts.maxCostUsd !== undefined ? { spent: 0, cap: opts.maxCostUsd } : undefined;
  let budgetStopped = false;
  for (let guard = 0; guard < 200; guard++) {
    const state = computeState(spec, games.map((g) => lite(g.record)));
    if (state.complete) break;
    if (budget && budget.spent >= budget.cap) {
      budgetStopped = true;
      break;
    }
    const next = playableSlots(state, new Set(), { pairsInOrder: game.sequentialPairs })[0];
    if (!next) break;
    const g = simulateGame({ game, settings, tournamentId: opts.id, slot: next, contestants, fast: opts.fast, budget, judgePool: opts.judgePool, noJudges: opts.noJudges });
    games.push(g);
    if (g.record.status === 'cancelled') {
      budgetStopped = true;
      break;
    }
  }
  const timeline: SimTournament['timeline'] = [];
  let t = 0;
  for (const g of games) {
    const start = t;
    const moveAt: number[] = [];
    for (const p of g.pace) {
      t += p;
      moveAt.push(t - start);
    }
    t += 400;
    timeline.push({ start, end: t, moveAt });
    t += opts.fast ? 2600 : 60_000;
  }
  const iso = (ms: number) => new Date(opts.t0 + ms).toISOString();
  games.forEach((g, i) => {
    g.record.startedAt = iso(timeline[i]!.start);
    g.record.finishedAt = iso(timeline[i]!.end);
  });
  return { manifest, games, timeline, t0: opts.t0, contestants, budgetStopped, build: opts };
}

export interface SimView {
  status: TournamentManifest['status'];
  revealed: ArenaGameRecord[];
  live: LiveGame | null;
  /** Streamed text of the move being thought about right now. */
  thinking: string;
  finishedAt?: string;
}

/** What the tournament looks like at `now`. */
export function viewAt(sim: SimTournament, now: number): SimView {
  const at = (sim.cancelledAt ?? now) - sim.t0;
  const revealed: ArenaGameRecord[] = [];
  let live: LiveGame | null = null;
  let thinking = '';
  sim.games.forEach((g, i) => {
    const tl = sim.timeline[i]!;
    if (tl.end <= at) revealed.push(g.record);
    else if (tl.start <= at && !sim.cancelledAt) {
      const into = at - tl.start;
      const n = tl.moveAt.filter((x) => x <= into).length;
      const moves = g.record.moves.slice(0, n);
      const nextMove = g.record.moves[n];
      const turnStart = n ? tl.moveAt[n - 1]! : 0;
      const metrics: [SideMetrics, SideMetrics] = [emptyMetrics(), emptyMetrics()];
      for (const m of moves) {
        metrics[m.side].costUsd += m.costUsd;
        metrics[m.side].inputTokens += m.inputTokens;
        metrics[m.side].outputTokens += m.outputTokens;
        metrics[m.side].apiCalls += m.attempts.length;
        metrics[m.side].ms += m.ms;
      }
      if (nextMove) {
        const text = nextMove.attempts[nextMove.attempts.length - 1]?.text ?? '';
        const span = (tl.moveAt[n] ?? turnStart + 1) - turnStart;
        const frac = Math.min(1, (into - turnStart) / Math.max(1, span * 0.85));
        thinking = text.slice(0, Math.floor(text.length * frac));
      }
      live = {
        key: g.record.key,
        matchId: g.record.matchId,
        gameNo: g.record.gameNo,
        players: g.record.players,
        moves,
        initial: g.record.initial,
        toMove: nextMove ? nextMove.side : ((1 - (moves[moves.length - 1]?.side ?? 1)) as Side),
        thinking,
        strikes: [0, 0],
        metrics,
        startedAt: new Date(sim.t0 + tl.start).toISOString(),
        turnStartedAt: new Date(sim.t0 + tl.start + turnStart).toISOString(),
        ...(nextMove?.kind === 'verdict' ? { phase: 'judging' } : {}),
      };
    }
  });
  const lastEnd = sim.timeline.length ? sim.timeline[sim.timeline.length - 1]!.end : 0;
  const status = sim.cancelledAt ? 'cancelled' : at >= lastEnd ? 'completed' : at < 0 ? 'queued' : 'running';
  return { status, revealed, live, thinking, finishedAt: status === 'completed' ? new Date(sim.t0 + lastEnd).toISOString() : undefined };
}
