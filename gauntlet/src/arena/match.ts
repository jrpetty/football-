/**
 * Match engine: plays ONE game between two seats.
 *
 * Each move is a fresh stateless prompt (see prompt.ts). An unreadable or
 * illegal move gets one retry with the problem explained; if that also fails a
 * seeded-random legal move is played for the seat and it receives a strike.
 * `maxStrikes` strikes lose the game. Seats are plain ModelHandles, so API
 * models, the Random Baseline, manual (copy & paste) contestants and scripted
 * test models all play through the same code.
 */
import { createRng } from '../core/rng.ts';
import type { ModelHandle } from '../core/types.ts';
import { buildMovePrompt, extractMove } from './prompt.ts';
import type { ArenaGame, ArenaMove, GameConfig, GameOutcome, MoveAttempt, Side } from './types.ts';

export interface Meter {
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
}

export interface Seat {
  handle: ModelHandle;
  /** Running totals of this seat's recorder, used to attribute cost and tokens to each move. */
  meter?: () => Meter;
}

export interface PlayGameOptions {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  game: ArenaGame<any>;
  config: GameConfig;
  seed: number;
  seats: [Seat, Seat];
  maxStrikes: number;
  maxOutputTokens?: number;
  /**
   * Random legal plies the harness plays before the models start (seeded, so
   * identical for both players). Used for sudden-death games so that two
   * deterministic models do not simply replay game 1.
   */
  openingPlies?: number;
  signal?: AbortSignal;
  /** Called before every model call; throw to stop the game (e.g. spending cap reached). */
  beforeCall?: (side: Side) => void;
  /** A seat starts thinking about a move (attempt 1 or 2). */
  onTurn?: (side: Side, ply: number, attempt: number) => void;
  onMove?: (move: ArenaMove, strikes: [number, number]) => void;
}

export interface PlayedGame extends GameOutcome {
  moves: ArenaMove[];
  initial: unknown;
  strikes: [number, number];
  illegal: [number, number];
}

const KEEP_CHARS = 1500;

function keepTail(text: string): string {
  return text.length > KEEP_CHARS ? `…${text.slice(text.length - KEEP_CHARS)}` : text;
}

function abortIfNeeded(signal?: AbortSignal): void {
  if (signal?.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
}

export async function playGame(o: PlayGameOptions): Promise<PlayedGame> {
  const { game, config } = o;
  const rng = createRng(o.seed);
  let state = game.setup(rng.fork('setup'), config);
  const fallback = rng.fork('fallback');
  const initial = game.snapshot(state);
  const moves: ArenaMove[] = [];
  const labels: string[] = [];
  const strikes: [number, number] = [0, 0];
  const illegal: [number, number] = [0, 0];
  const zero: Meter = { costUsd: 0, inputTokens: 0, outputTokens: 0 };

  const opening = rng.fork('opening');
  for (let i = 0; i < (o.openingPlies ?? 0) && !game.outcome(state); i++) {
    const move = opening.pick(game.legalMoves(state));
    const side = game.toMove(state);
    const label = game.label(state, move);
    state = game.play(state, move);
    labels.push(label);
    const record: ArenaMove = { ply: moves.length + 1, side, move, label, attempts: [], forfeit: false, opening: true, ms: 0, costUsd: 0, inputTokens: 0, outputTokens: 0, snapshot: game.snapshot(state) };
    moves.push(record);
    o.onMove?.(record, [0, 0]);
  }

  for (;;) {
    const done = game.outcome(state);
    if (done) return { ...done, moves, initial, strikes, illegal };
    if (moves.length >= config.maxPlies) return { ...game.adjudicate(state), moves, initial, strikes, illegal };

    const side = game.toMove(state);
    const legal = game.legalMoves(state);
    if (!legal.length) throw new Error(`${game.name}: no legal moves but the game is not over (game bug)`);
    const seat = o.seats[side];
    const before = seat.meter?.() ?? zero;
    const started = Date.now();
    const attempts: MoveAttempt[] = [];
    let accepted: string | null = null;

    for (let attempt = 1; attempt <= 2 && accepted === null; attempt++) {
      abortIfNeeded(o.signal);
      o.beforeCall?.(side);
      o.onTurn?.(side, moves.length + 1, attempt);
      const prev = attempts[attempts.length - 1];
      const prompt = buildMovePrompt({
        game,
        state,
        side,
        config,
        labels,
        strikes,
        maxStrikes: o.maxStrikes,
        retry: prev ? { reply: prev.text, extracted: prev.extracted, error: prev.error ?? '' } : undefined,
      });
      const t0 = Date.now();
      const reply = await seat.handle.complete({
        messages: [{ role: 'user', content: prompt }],
        maxOutputTokens: o.maxOutputTokens,
        label: `${game.sides[side].name} · move ${moves.length + 1}${attempt > 1 ? ' · retry' : ''}`,
      });
      const ms = Date.now() - t0;
      const extracted = extractMove(reply.text);
      let error: string | undefined;
      if (extracted === null) {
        if (!reply.text.trim()) error = reply.stopReason === 'refusal' ? 'The reply was a refusal with no move.' : 'The reply was empty.';
        else if (reply.stopReason === 'max_tokens') error = 'The reply was cut off at the output-token limit before the "MOVE:" line. Think more briefly.';
        else error = 'The reply did not contain a "MOVE: <move>" line.';
      } else {
        const parsed = game.parseMove(state, extracted);
        if (parsed.ok) accepted = parsed.move;
        else error = parsed.error;
      }
      attempts.push({ text: keepTail(reply.text), extracted, error, ms });
      if (accepted === null) illegal[side]++;
    }

    const forfeit = accepted === null;
    if (forfeit) strikes[side]++;
    const after = seat.meter?.() ?? zero;
    const base = {
      ply: moves.length + 1,
      side,
      attempts,
      forfeit,
      ms: Date.now() - started,
      costUsd: Math.round((after.costUsd - before.costUsd) * 1e8) / 1e8,
      inputTokens: after.inputTokens - before.inputTokens,
      outputTokens: after.outputTokens - before.outputTokens,
    };

    if (forfeit && strikes[side] >= o.maxStrikes) {
      // The final strike ends the game at once: nothing is played on the board.
      const record: ArenaMove = { ...base, move: '', label: `strike ${strikes[side]}`, snapshot: game.snapshot(state) };
      moves.push(record);
      o.onMove?.(record, [strikes[0], strikes[1]]);
      const loser = game.sides[side].name;
      return { winner: (1 - side) as Side, reason: `${loser} made ${o.maxStrikes} illegal moves`, moves, initial, strikes, illegal };
    }

    const move = accepted ?? fallback.pick(legal);
    const label = game.label(state, move);
    state = game.play(state, move);
    labels.push(label);
    const record: ArenaMove = { ...base, move, label, snapshot: game.snapshot(state) };
    moves.push(record);
    o.onMove?.(record, [strikes[0], strikes[1]]);
  }
}
