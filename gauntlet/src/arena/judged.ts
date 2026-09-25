/**
 * Engine for free-text, judged games (engine: 'debate'): debate and courtroom.
 *
 * Each speech is one fresh stateless call built by the game's `prompt` hook.
 * An empty or refused reply gets one retry; a second failure records "no
 * speech" and a strike (no strike-out: the judges see the gap). Over-limit
 * speeches are cut by the game. When the speeches are done the tournament's
 * judge step runs (src/arena/judge.ts); without judges the game is returned as
 * "awaiting human judging".
 */
import { createRng } from '../core/rng.ts';
import { judgingFrom } from './judge.ts';
import type { PlayGameOptions } from './match.ts';
import { retryBlock, type PlayedGameExt } from './turns.ts';
import type { ArenaJudging, ArenaMove, MoveAttempt, Side } from './types.ts';

const KEEP_CHARS = 6000;

export interface JudgedGameOptions extends PlayGameOptions {
  /** Runs the judge panel on the finished game state. Absent = wait for human judging. */
  judge?: (state: unknown) => Promise<ArenaJudging>;
  /** Phase changes, e.g. 'judging'. */
  onPhase?: (phase: string) => void;
}

function abortIfNeeded(signal?: AbortSignal): void {
  if (signal?.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
}

/** A light copy of the judging for the final snapshot (no transcripts). */
export function verdictSnapshot(j: ArenaJudging): unknown {
  const { transcript: _t, metrics: _m, ...rest } = j;
  return rest;
}

export async function playJudgedGame(o: JudgedGameOptions): Promise<PlayedGameExt> {
  const { game, config } = o;
  if (!game.prompt) throw new Error(`${game.name}: the 'debate' engine needs a prompt() hook`);
  const rng = createRng(o.seed);
  let state = game.setup(rng.fork('setup'), config);
  const initial = game.snapshot(state);
  const moves: ArenaMove[] = [];
  const strikes: [number, number] = [0, 0];
  const illegal: [number, number] = [0, 0];
  const zero = { costUsd: 0, inputTokens: 0, outputTokens: 0 };

  while (!game.outcome(state) && moves.length < config.maxPlies) {
    const side = game.toMove(state);
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
      let prompt = game.prompt(state, side, { config, strikes: [strikes[0], strikes[1]], maxStrikes: o.maxStrikes });
      if (prev) prompt += `\n\n${retryBlock('SPEECH', { reply: prev.text, extracted: null, error: prev.error ?? '' })}`.replace('End your reply with one valid SPEECH line.', 'Reply with your speech text only.');
      const t0 = Date.now();
      const reply = await seat.handle.complete({
        messages: [{ role: 'user', content: prompt }],
        maxOutputTokens: o.maxOutputTokens,
        label: `${game.sides[side].name} · speech ${moves.length + 1}${attempt > 1 ? ' · retry' : ''}`,
      });
      const ms = Date.now() - t0;
      let error: string | undefined;
      const parsed = game.parseMove(state, reply.text);
      if (parsed.ok) accepted = parsed.move;
      else error = reply.stopReason === 'refusal' ? 'The reply was a refusal. The motion is a standard debating topic; argue your assigned side.' : reply.stopReason === 'max_tokens' && !reply.text.trim() ? 'The reply was cut off before any speech text. Think more briefly.' : parsed.error;
      attempts.push({ text: reply.text.length > KEEP_CHARS ? `${reply.text.slice(0, KEEP_CHARS)}…` : reply.text, extracted: null, error, ms });
      if (accepted === null) illegal[side]++;
    }
    const forfeit = accepted === null;
    if (forfeit) strikes[side]++;
    const after = seat.meter?.() ?? zero;
    const move = accepted ?? game.fallbackMove?.(state) ?? '';
    const label = game.label(state, move);
    state = game.play(state, move);
    const record: ArenaMove = {
      ply: moves.length + 1,
      side,
      move,
      label,
      attempts,
      forfeit,
      ms: Date.now() - started,
      costUsd: Math.round((after.costUsd - before.costUsd) * 1e8) / 1e8,
      inputTokens: after.inputTokens - before.inputTokens,
      outputTokens: after.outputTokens - before.outputTokens,
      snapshot: game.snapshot(state),
    };
    moves.push(record);
    o.onMove?.(record, [strikes[0], strikes[1]]);
  }

  o.onPhase?.('judging');
  abortIfNeeded(o.signal);
  const started = Date.now();
  const judging = o.judge ? await o.judge(state) : judgingFrom([], { note: 'This game has no judge panel. A person can judge it on the human judging screen.' });
  const winner: Side | null = judging.status === 'judged' ? judging.winner : null;
  const verdict: ArenaMove = {
    ply: moves.length + 1,
    side: winner ?? 0,
    move: 'verdict',
    label: judging.status === 'judged' ? `Verdict: ${judging.decision}` : 'Awaiting human judging',
    attempts: [],
    forfeit: false,
    ms: Date.now() - started,
    costUsd: 0,
    inputTokens: 0,
    outputTokens: 0,
    snapshot: { ...(game.snapshot(state) as object), verdict: verdictSnapshot(judging) },
    kind: 'verdict',
  };
  moves.push(verdict);
  o.onMove?.(verdict, [strikes[0], strikes[1]]);
  const votes = `${Math.max(...judging.votes)}–${Math.min(...judging.votes)}`;
  const reason = judging.status !== 'judged' ? 'Awaiting human judging' : winner === null ? judging.decision : `${judging.decision}${judging.verdicts.length > 1 ? ` (${votes})` : ''}`;
  return { winner, reason, moves, initial, strikes, illegal, judging };
}
