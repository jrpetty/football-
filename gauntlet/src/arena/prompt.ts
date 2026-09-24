/**
 * Arena prompts and move extraction.
 *
 * Every move is a fresh, stateless prompt: full rules, the move history, the
 * current position, the legal moves and the required answer format. Nothing
 * from earlier calls is carried over, so every model sees exactly the same
 * kind of prompt at every move. Any change to this file changes every arena
 * fingerprint.
 */
import type { ArenaGame, GameConfig, Side } from './types.ts';

/** Bump when the prompt template changes in a way that alters what models see. */
export const ARENA_PROMPT_VERSION = '1';

/**
 * The text after the LAST "MOVE:" label in a reply, or null when there is none.
 * Tolerates markdown ("**MOVE:** e4", "MOVE: `e2e4`"), any case, full-width
 * colons, and the move on the line after the label.
 */
export function extractMove(reply: string): string | null {
  const text = reply.replace(/\r\n?/g, '\n');
  const re = /(?:^|[^A-Za-z])MOVE[\s*_`]*[:：=]\s*(.*)$/gim;
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

export interface PromptInput {
  game: ArenaGame<unknown>;
  state: unknown;
  side: Side;
  config: GameConfig;
  /** Display labels of every move played so far. */
  labels: string[];
  strikes: [number, number];
  maxStrikes: number;
  /** Set on the second attempt at the same move. */
  retry?: { reply: string; extracted: string | null; error: string };
}

function lastLine(text: string): string {
  const lines = text.trim().split('\n').map((l) => l.trim()).filter(Boolean);
  const l = lines[lines.length - 1] ?? '';
  return l.length > 160 ? `${l.slice(0, 157)}…` : l;
}

export function buildMovePrompt(p: PromptInput): string {
  const { game, state, side, config } = p;
  const me = game.sides[side];
  const opp = game.sides[1 - side]!;
  const ply = p.labels.length + 1;
  const parts: string[] = [];
  parts.push(`You are playing ${game.name} against another AI model in the Gauntlet Arena. You play ${me.name}; your opponent plays ${opp.name}.`);
  parts.push(`== RULES ==\n${game.rules}\nMove cap: ${config.maxPlies} half-moves in total. ${game.capRule}`);
  parts.push(
    [
      '== HOW TO ANSWER ==',
      'Think as much as you like, then finish your reply with exactly one line in this format:',
      'MOVE: <your move>',
      `where <your move> is ${game.moveHelp}.`,
      `If your move is illegal or cannot be read, you get one retry with the problem explained. If the retry also fails, a random legal move is played for you and you receive a strike. ${p.maxStrikes} strikes lose the game.`,
      `Strikes so far: you ${p.strikes[side]}, your opponent ${p.strikes[1 - side]}.`,
    ].join('\n'),
  );
  parts.push(`== MOVES SO FAR ==\n${game.formatHistory(p.labels)}`);
  parts.push(`== CURRENT POSITION ==\n${game.view(state, side)}`);
  if (config.listLegalMoves) {
    const legal = game.legalMoves(state);
    parts.push(`== LEGAL MOVES (${legal.length}) ==\n${legal.map((m) => `\`MOVE: ${m}\``).join(' ')}`);
  }
  if (p.retry) {
    parts.push(
      [
        '== YOUR PREVIOUS ANSWER WAS REJECTED ==',
        p.retry.extracted !== null ? `You answered: "MOVE: ${p.retry.extracted.slice(0, 80)}"` : `Your reply ended with: "${lastLine(p.retry.reply) || '(empty reply)'}"`,
        `Problem: ${p.retry.error}`,
        'This is your last chance for this move. End your reply with one valid MOVE line.',
      ].join('\n'),
    );
  }
  parts.push(`It is your turn: ${me.name} to play, half-move ${ply}.`);
  return parts.join('\n\n');
}
