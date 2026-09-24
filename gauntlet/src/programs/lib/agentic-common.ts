/**
 * Shared plumbing for the turn-based "Agents & Planning" environments
 * (Survival Island, Escape Room, Startup).
 *
 * Every turn is a single stateless call: a fixed system prompt (the rules)
 * plus one compact observation. There is no growing chat history, so the
 * cost per turn is bounded and the bytes a model sees depend only on the
 * seed and its own previous actions.
 */
import { extractTagged, stripDecoration } from '../../core/extract.ts';
import type { ModelReply, ProgramContext } from '../../core/types.ts';

/** Maximum length of the model's NOTE memo (its only long-term memory). */
export const NOTE_LIMIT = 300;

export interface ModelTurn {
  reply: ModelReply;
  /** Raw command text (uppercased, cleaned), or null when none could be read. */
  action: string | null;
  /** New NOTE memo, or null when the reply did not contain one. */
  note: string | null;
  /** Set when the whole turn is void (refusal / empty output). */
  failure: string | null;
}

/**
 * Ask the model for one turn. Genuine API / abort errors from `ctx.model`
 * propagate; bad model output never throws.
 */
export async function askTurn(ctx: ProgramContext, system: string, observation: string, label: string): Promise<ModelTurn> {
  const reply = await ctx.model.complete({
    system,
    messages: [{ role: 'user', content: observation }],
    maxOutputTokens: ctx.maxOutputTokens,
    label,
  });
  const text = typeof reply.text === 'string' ? reply.text : '';
  if (reply.stopReason === 'refusal') return { reply, action: null, note: null, failure: 'The model refused to answer.' };
  if (!text.trim()) return { reply, action: null, note: null, failure: 'The reply was empty.' };
  return { reply, action: parseActionLine(text), note: parseNote(text), failure: null };
}

/** Normalise a command: strip markdown, quotes, trailing punctuation / comments; uppercase; collapse spaces. */
export function cleanCommand(raw: string): string {
  let s = stripDecoration(raw);
  s = s.replace(/^["'“”‘’]+|["'“”‘’]+$/g, '');
  // Drop trailing explanations such as "MOVE N (towards the spring)" or "GATHER - for wood".
  s = s.replace(/\s*\([^)]*\)\s*$/, '').replace(/\s+[-–—]\s+.*$/, '');
  s = s.replace(/[.!;,]+$/, '');
  return s.replace(/\s+/g, ' ').trim().toUpperCase();
}

/**
 * The command after the last `ACTION:` line. As a courtesy to terse
 * players, a reply that consists of a single bare line is taken as the
 * command itself.
 */
export function parseActionLine(text: string): string | null {
  const tagged = extractTagged(text, 'ACTION');
  if (tagged !== null) {
    const c = cleanCommand(tagged);
    return c || null;
  }
  const lines = text
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean);
  if (lines.length === 1 && !/^NOTE\s*[:：]/i.test(lines[0]!)) {
    const c = cleanCommand(lines[0]!.replace(/^`+|`+$/g, ''));
    return c || null;
  }
  return null;
}

/** The last `NOTE:` line, trimmed to NOTE_LIMIT characters. */
export function parseNote(text: string): string | null {
  const note = extractTagged(text, 'NOTE');
  if (note === null) return null;
  // Backticks are reserved for the "Available actions" list, so they never appear in echoed notes.
  const clean = note.replace(/`/g, "'").replace(/\s+/g, ' ').trim();
  return clean.length > NOTE_LIMIT ? clean.slice(0, NOTE_LIMIT) : clean;
}

export function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n));
}

export function clamp01(n: number): number {
  return clamp(n, 0, 1);
}

export function round(n: number, digits = 0): number {
  const f = 10 ** digits;
  return Math.round(n * f) / f;
}

/** Thousands separators without relying on ICU locale data (identical bytes on every platform). */
function groupDigits(n: number): string {
  return String(Math.abs(Math.round(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** "$12,345" / "−$1,200". */
export function money(n: number): string {
  const v = Math.round(n);
  const s = `$${groupDigits(v)}`;
  return v < 0 ? `−${s}` : s;
}

/** "+$1,200" / "−$300". */
export function signedMoney(n: number): string {
  const v = Math.round(n);
  return v >= 0 ? `+${money(v)}` : money(v);
}

/** Shorten free text for replay frames / event logs. */
export function truncate(text: string, max: number): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max - 1)}…` : t;
}

/** Wrap each command in backticks for the "Available actions" line (the Random Baseline picks from these). */
export function backtickList(commands: readonly string[]): string {
  return commands.map((c) => `\`${c}\``).join(', ');
}

/** Render the model's memo for the observation. */
export function noteBlock(note: string): string {
  return note ? `Your note (from your previous turn): "${note}"` : 'Your note: (empty — write NOTE: <text> to remember things)';
}
