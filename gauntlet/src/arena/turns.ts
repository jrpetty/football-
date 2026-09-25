/**
 * Turn engine for games that write their own prompts (engine: 'turns'), e.g.
 * poker: hidden information, an answer keyword other than MOVE ("ACTION:"), a
 * one-line "REASON:" shown to viewers, a game-chosen fallback (check / fold)
 * and optionally no strike-out loss.
 *
 * Same contract as match.ts (one fresh stateless prompt per decision, one
 * retry with the problem explained, then the fallback and a strike), so the
 * tournament scheduler, spending cap, live events and recording are shared.
 * match.ts is left untouched so existing Connect Four and chess fingerprints
 * (which hash it) stay valid.
 */
import { createRng } from '../core/rng.ts';
import type { PlayGameOptions, PlayedGame } from './match.ts';
import type { ArenaJudging, ArenaMove, MoveAttempt, Side } from './types.ts';

/** A played game plus the extension results ('margin' scoring, judge step). */
export type PlayedGameExt = PlayedGame & { margin?: [number, number]; judging?: ArenaJudging };

const KEEP_CHARS = 1500;

function keepTail(text: string): string {
  return text.length > KEEP_CHARS ? `…${text.slice(text.length - KEEP_CHARS)}` : text;
}

function abortIfNeeded(signal?: AbortSignal): void {
  if (signal?.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
}

/**
 * The text after the LAST "<KEY>:" label in a reply, or null. Tolerates
 * markdown ("**ACTION:** call"), any case, full-width colons and the value on
 * the next line.
 */
export function extractAnswer(reply: string, key: string): string | null {
  const text = reply.replace(/\r\n?/g, '\n');
  const re = new RegExp(`(?:^|[^A-Za-z])${key}[\\s*_\`]*[:：=]\\s*(.*)$`, 'gim');
  let last: RegExpExecArray | null = null;
  for (let m = re.exec(text); m; m = re.exec(text)) last = m;
  if (!last) return null;
  let value = (last[1] ?? '').replace(/[*_`]+/g, ' ').trim();
  if (!value) {
    const after = text.slice(last.index + last[0].length).split('\n').map((l) => l.replace(/[*_`]+/g, ' ').trim()).filter(Boolean);
    value = after[0] ?? '';
  }
  value = value.replace(/^[<[(]\s*/, '').replace(/\s*[>\])]$/, '').trim();
  return value || null;
}

/** The model's one-line "REASON:" (trimmed to 160 characters), if any. */
export function extractReason(reply: string): string | undefined {
  const r = extractAnswer(reply, 'REASON');
  if (!r) return undefined;
  const one = r.replace(/\s+/g, ' ').trim();
  return one.length > 160 ? `${one.slice(0, 157)}…` : one;
}

function lastLine(text: string): string {
  const lines = text.trim().split('\n').map((l) => l.trim()).filter(Boolean);
  const l = lines[lines.length - 1] ?? '';
  return l.length > 160 ? `${l.slice(0, 157)}…` : l;
}

export function retryBlock(key: string, retry: { reply: string; extracted: string | null; error: string }): string {
  return [
    '== YOUR PREVIOUS ANSWER WAS REJECTED ==',
    retry.extracted !== null ? `You answered: "${key}: ${retry.extracted.slice(0, 80)}"` : `Your reply ended with: "${lastLine(retry.reply) || '(empty reply)'}"`,
    `Problem: ${retry.error}`,
    `This is your last chance for this decision. End your reply with one valid ${key} line.`,
  ].join('\n');
}

export async function playTurnGame(o: PlayGameOptions): Promise<PlayedGameExt> {
  const { game, config } = o;
  if (!game.prompt) throw new Error(`${game.name}: the 'turns' engine needs a prompt() hook`);
  const key = game.answerKey ?? 'MOVE';
  const rng = createRng(o.seed);
  let state = game.setup(rng.fork('setup'), config);
  const fallback = rng.fork('fallback');
  const initial = game.snapshot(state);
  const moves: ArenaMove[] = [];
  const strikes: [number, number] = [0, 0];
  const illegal: [number, number] = [0, 0];
  const zero = { costUsd: 0, inputTokens: 0, outputTokens: 0 };
  const done = (r: { winner: Side | null; reason: string }): PlayedGameExt => ({ ...r, moves, initial, strikes, illegal, margin: game.margin?.(state) });

  for (;;) {
    const over = game.outcome(state);
    if (over) return done(over);
    if (moves.length >= config.maxPlies) return done(game.adjudicate(state));

    const side = game.toMove(state);
    const legal = game.legalMoves(state);
    if (!legal.length) throw new Error(`${game.name}: no legal moves but the game is not over (game bug)`);
    const seat = o.seats[side];
    const before = seat.meter?.() ?? zero;
    const started = Date.now();
    const attempts: MoveAttempt[] = [];
    let accepted: string | null = null;
    let note: string | undefined;

    for (let attempt = 1; attempt <= 2 && accepted === null; attempt++) {
      abortIfNeeded(o.signal);
      o.beforeCall?.(side);
      o.onTurn?.(side, moves.length + 1, attempt);
      const prev = attempts[attempts.length - 1];
      let prompt = game.prompt(state, side, { config, strikes: [strikes[0], strikes[1]], maxStrikes: o.maxStrikes });
      if (prev) prompt += `\n\n${retryBlock(key, { reply: prev.text, extracted: prev.extracted, error: prev.error ?? '' })}`;
      const t0 = Date.now();
      const reply = await seat.handle.complete({
        messages: [{ role: 'user', content: prompt }],
        maxOutputTokens: o.maxOutputTokens,
        label: `${game.sides[side].name} · decision ${moves.length + 1}${attempt > 1 ? ' · retry' : ''}`,
      });
      const ms = Date.now() - t0;
      const extracted = extractAnswer(reply.text, key);
      let error: string | undefined;
      if (extracted === null) {
        if (!reply.text.trim()) error = reply.stopReason === 'refusal' ? `The reply was a refusal with no ${key} line.` : 'The reply was empty.';
        else if (reply.stopReason === 'max_tokens') error = `The reply was cut off at the output-token limit before the "${key}:" line. Think more briefly.`;
        else error = `The reply did not contain a "${key}: <…>" line.`;
      } else {
        const parsed = game.parseMove(state, extracted);
        if (parsed.ok) {
          accepted = parsed.move;
          note = extractReason(reply.text);
        } else error = parsed.error;
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

    if (forfeit && game.strikesLose !== false && strikes[side] >= o.maxStrikes) {
      const record: ArenaMove = { ...base, move: '', label: `strike ${strikes[side]}`, snapshot: game.snapshot(state) };
      moves.push(record);
      o.onMove?.(record, [strikes[0], strikes[1]]);
      return { winner: (1 - side) as Side, reason: `${game.sides[side].name} made ${o.maxStrikes} illegal moves`, moves, initial, strikes, illegal, margin: game.margin?.(state) };
    }

    const move = accepted ?? game.fallbackMove?.(state) ?? fallback.pick(legal);
    const label = game.label(state, move);
    state = game.play(state, move);
    const record: ArenaMove = { ...base, move, label, snapshot: game.snapshot(state), ...(note ? { note } : {}) };
    moves.push(record);
    o.onMove?.(record, [strikes[0], strikes[1]]);
  }
}
