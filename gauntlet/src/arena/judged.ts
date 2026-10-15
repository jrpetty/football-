/**
 * Engine for free-text, judged games (engine: 'debate'): debate and courtroom.
 *
 * Each speech is one fresh stateless call built by the game's `prompt` hook.
 * An empty or refused reply gets one retry; a second failure records "no
 * speech" and a strike (no strike-out: the judges see the gap). Over-limit
 * speeches are cut by the game. When the speeches are done the tournament's
 * judge step runs (src/arena/judge.ts); without judges the game is returned as
 * "awaiting human judging".
 *
 * Fair play: every speech is checked (src/scoring/integrity.ts, speech mode: addressing the judges is rhetoric, but
 * asking for a score, "ignore your instructions" and forged judge lines or tags are not). A violation ends the game at
 * once: the speech is not shown to anyone and the other side wins without judging.
 */
import { createRng } from '../core/rng.ts';
import { judgingFrom } from './judge.ts';
import type { PlayGameOptions } from './match.ts';
import { retryBlock, type PlayedGameExt } from './turns.ts';
import type { ArenaJudging, ArenaMove, MoveAttempt, Side } from './types.ts';
import { checkReply, fairPlaySummary, type IntegrityFinding } from '../scoring/integrity.ts';

const KEEP_CHARS = 6000;

export interface JudgedGameOptions extends PlayGameOptions {
  /** Runs the judge panel on the finished game state. Absent = wait for human judging. */
  judge?: (state: unknown) => Promise<ArenaJudging>;
  /** Game number within the match (for call labels in the Manual Inbox: "Game 2 · Defence · Opening (round 1 of 3)"). */
  gameNo?: number;
  /** Phase changes, e.g. 'judging'. */
  onPhase?: (phase: string) => void;
}

function abortIfNeeded(signal?: AbortSignal): void {
  if (signal?.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
}

/** A speech broke the fair-play rules: the game ends at once and the other side wins without judging. */
function fairPlayLoss(
  side: Side,
  foul: IntegrityFinding,
  g: {
    game: JudgedGameOptions['game'];
    state: unknown;
    moves: ArenaMove[];
    initial: unknown;
    strikes: [number, number];
    illegal: [number, number];
    started: number;
    before: { costUsd: number; inputTokens: number; outputTokens: number };
    after: { costUsd: number; inputTokens: number; outputTokens: number };
    onMove?: JudgedGameOptions['onMove'];
    attempts: MoveAttempt[];
  },
): PlayedGameExt {
  const record: ArenaMove = {
    ply: g.moves.length + 1,
    side,
    move: '',
    label: `${g.game.sides[side].name}: ${fairPlaySummary(foul)}`,
    attempts: g.attempts,
    forfeit: true,
    ms: Date.now() - g.started,
    costUsd: Math.round((g.after.costUsd - g.before.costUsd) * 1e8) / 1e8,
    inputTokens: g.after.inputTokens - g.before.inputTokens,
    outputTokens: g.after.outputTokens - g.before.outputTokens,
    snapshot: { ...(g.game.snapshot(g.state as never) as object), fairPlay: { side, ...foul } },
  };
  g.moves.push(record);
  g.onMove?.(record, [g.strikes[0], g.strikes[1]]);
  const winner = (1 - side) as Side;
  return { winner, reason: `${g.game.sides[side].name} broke the fair-play rules (${foul.rule}), so ${g.game.sides[winner].name} wins without judging`, moves: g.moves, initial: g.initial, strikes: g.strikes, illegal: g.illegal };
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
        label: `${o.gameNo ? `Game ${o.gameNo} · ` : ''}${game.turnLabel?.(state) ?? `${game.sides[side].name} · speech ${moves.length + 1}`}${attempt > 1 ? ' · retry' : ''}`,
      });
      const ms = Date.now() - t0;
      let error: string | undefined;
      const parsed = game.parseMove(state, reply.text);
      const foul = parsed.ok ? checkReply(reply.text, { taskText: prompt, speech: true }) : null;
      if (foul?.verdict === 'cheating') {
        attempts.push({ text: reply.text.length > KEEP_CHARS ? `${reply.text.slice(0, KEEP_CHARS)}…` : reply.text, extracted: null, error: fairPlaySummary(foul), ms: Date.now() - t0 });
        return fairPlayLoss(side, foul, { game, state, moves, initial, strikes, illegal, started, before: before, after: seat.meter?.() ?? zero, onMove: o.onMove, attempts });
      }
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
