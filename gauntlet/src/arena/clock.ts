/**
 * Chess-clock time control for Arena games ("Speed 4+0" by default).
 *
 * Each player has one clock per game. Only that player's own model time is charged: for every call, the
 * duration of the attempt that produced the reply (request sent → reply complete). Queueing for a provider slot
 * and transport-retry back-off are never charged (the recorder starts the clock when the request is sent); a
 * rejected (illegal) move and its retry are charged, because the clock keeps running. The increment is added
 * after every completed move. When a clock reaches zero the call is aborted at once (the recorder's
 * OutOfTimeError path) and the game's flag rule applies (see ArenaClockSpec.onFlag and the engines).
 *
 * "Pretty much never go over" is enforced, not just asked for: every call's output-token allowance is sized
 * from the clock and the player's measured speed (planMove), so a model that follows its allowance cannot
 * flag while the speed estimate holds. The prompt states the clock, the suggested time and the allowance, with
 * firmer wording as the clock runs down (clockSection / clockReminder).
 *
 * Pure module (no Node APIs): the UI imports the formatting and time-trouble helpers.
 */
import type { ModelReply } from '../core/types.ts';
import type { ArenaClockSpec, Side, TimeControl } from './types.ts';

/** Bump when the clock section, reminder or urgency text changes (part of every clocked fingerprint, never classic ones). */
export const CLOCK_PROMPT_VERSION = '1';

/** New tournaments play Speed 4+0 unless the owner picks something else. */
export const DEFAULT_TIME_CONTROL: TimeControl = { minutes: 4, incrementSec: 0 };

export interface TimeControlPreset {
  id: string;
  label: string;
  hint: string;
  /** null = Classic (no clock). */
  timeControl: TimeControl | null;
  recommended?: boolean;
}

export const TIME_CONTROL_PRESETS: TimeControlPreset[] = [
  { id: '4+0', label: 'Speed 4+0', hint: '4 minutes per player per game, no increment. Recommended.', timeControl: { minutes: 4, incrementSec: 0 }, recommended: true },
  { id: '1+0', label: 'Bullet 1+0', hint: '1 minute per player per game: very short replies only. A model that is slow to start answering can lose on time in a long game whatever it does.', timeControl: { minutes: 1, incrementSec: 0 } },
  { id: '2+1', label: 'Blitz 2+1', hint: '2 minutes, plus 1 second after every move. Tight for models that are slow to start answering.', timeControl: { minutes: 2, incrementSec: 1 } },
  { id: '3+2', label: 'Blitz 3+2', hint: '3 minutes, plus 2 seconds after every move.', timeControl: { minutes: 3, incrementSec: 2 } },
  { id: '5+0', label: 'Blitz 5+0', hint: '5 minutes per player per game.', timeControl: { minutes: 5, incrementSec: 0 } },
  { id: '10+0', label: 'Rapid 10+0', hint: '10 minutes per player per game.', timeControl: { minutes: 10, incrementSec: 0 } },
  { id: 'classic', label: 'Classic (no clock)', hint: 'No time limit per game (the old format). Allows copy & paste models.', timeControl: null },
];

/** "4+0", "2+1". */
export function timeControlName(tc: TimeControl | null | undefined): string {
  return tc ? `${tc.minutes}+${tc.incrementSec}` : 'Classic';
}

/** "Speed 4+0" / "Blitz 2+1" / "Classic (no clock)". */
export function timeControlLabel(tc: TimeControl | null | undefined): string {
  if (!tc) return 'Classic (no clock)';
  return TIME_CONTROL_PRESETS.find((p) => p.timeControl && p.timeControl.minutes === tc.minutes && p.timeControl.incrementSec === tc.incrementSec)?.label ?? `Speed ${timeControlName(tc)}`;
}

/** Validates a requested time control; throws a plain-English Error. undefined → the default, null → Classic. */
export function resolveTimeControl(tc: TimeControl | null | undefined): TimeControl | null {
  if (tc === null) return null;
  if (tc === undefined) return { ...DEFAULT_TIME_CONTROL };
  const minutes = Number(tc.minutes);
  const incrementSec = Number(tc.incrementSec ?? 0);
  if (!Number.isFinite(minutes) || minutes <= 0 || minutes > 60) throw new Error('Time control: minutes per player must be more than 0 and at most 60');
  if (!Number.isFinite(incrementSec) || incrementSec < 0 || incrementSec > 60) throw new Error('Time control: the increment must be between 0 and 60 seconds');
  return { minutes, incrementSec };
}

// ─────────────────────────────── Formatting ───────────────────────────────

/** Clock text for prompts: m:ss, rounded DOWN (a player is never told it has more time than it does). */
export function fmtClockText(ms: number): string {
  const t = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`;
}

/** Clock text for screens: m:ss, with tenths under 10 s ("0:07.3"). */
export function fmtClockDisplay(ms: number): string {
  if (!(ms > 0)) return '0:00';
  if (ms < 10_000) return `0:0${Math.floor(ms / 1000)}.${Math.floor((ms % 1000) / 100)}`;
  return fmtClockText(ms);
}

const thousands = (n: number) => String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');

/** "about 5 seconds", "about 1 second", "about 1:30". */
function aboutTime(ms: number): string {
  const s = Math.max(1, Math.round(ms / 1000));
  if (s < 90) return `about ${s} second${s === 1 ? '' : 's'}`;
  return `about ${fmtClockText(s * 1000)}`;
}

// ─────────────────────────────── Urgency ───────────────────────────────

/** Prompt urgency for the player's own clock. */
export type UrgencyTier = 'calm' | 'low' | 'pressure' | 'trouble';

/** calm → low (under half the starting time) → pressure (under 60 s) → trouble (under 20 s). */
export function urgencyTier(remainingMs: number, initialMs: number): UrgencyTier {
  if (remainingMs < 20_000) return 'trouble';
  if (remainingMs < 60_000) return 'pressure';
  if (remainingMs < initialMs / 2) return 'low';
  return 'calm';
}

/** What a viewer's clock looks like. */
export type ClockDisplayState = 'normal' | 'low' | 'critical' | 'trouble' | 'flagged';

/**
 * Viewer state of one clock: amber pulse under 60 s ('low'), red under 30 s ('critical'), red with a
 * "TIME TROUBLE" badge under 20 s ('trouble'), and 'flagged' once it has fallen.
 */
export function clockDisplayState(ms: number, flagged = false): ClockDisplayState {
  if (flagged || ms <= 0) return 'flagged';
  if (ms < 20_000) return 'trouble';
  if (ms < 30_000) return 'critical';
  if (ms < 60_000) return 'low';
  return 'normal';
}

/**
 * A short commentary call-out for a clock, e.g. "Black is down to 0:12!" (null when there is nothing to say).
 * "Under a minute" is not news in a game that started with a minute or less (`initialMs`).
 */
export function clockCallout(name: string, ms: number, flagged = false, initialMs = Infinity): string | null {
  const st = clockDisplayState(ms, flagged);
  if (st === 'flagged') return `${name}'s flag has fallen!`;
  if (st === 'trouble' || st === 'critical') return `${name} is down to ${fmtClockText(ms)}!`;
  if (st === 'low' && initialMs > 60_000) return `${name} has under a minute left.`;
  return null;
}

/** The side to move's clock while it is thinking, from the last settled value. */
export function tickingClock(settledMs: number, sinceMs: number, nowMs: number): number {
  return Math.max(0, settledMs - Math.max(0, nowMs - sinceMs));
}

// ─────────────────────────────── Speed measurement ───────────────────────────────

/** Before any measurement: a conservative 40 tokens/s with a 2 s first-token delay. */
export const DEFAULT_SPEED = { tokensPerSec: 40, firstTokenMs: 2000 };

export interface SpeedSample {
  outputTokens: number;
  totalMs: number;
  ttftMs: number | null;
}

export interface SpeedEstimate {
  /** Output tokens per second once the first token has arrived (never above what was observed). */
  tokensPerSec: number;
  /** Mean delay before generation starts. */
  firstTokenMs: number;
  /**
   * Output tokens per second over the WHOLE call, delay included. Never more than the true generation speed, so
   * an allowance of throughput × time cannot overrun by more than one first-token delay, whatever the model
   * hides: the allowance never exceeds it.
   */
  throughputPerSec: number;
  measured: boolean;
}

/** Highest generation speed the meter will believe (faster models are under-, never over-estimated). */
export const MAX_PLAUSIBLE_TOKENS_PER_SEC = 1000;

/**
 * One player's measured speed in this tournament (its most recent 30 calls). Each call is split into a delay
 * and a generation part: delay = the first-token time, but at most half the call. The cap keeps the estimate
 * safe for models whose reasoning is hidden (their first visible token arrives late although their output
 * tokens include the reasoning): such a call counts as half delay, half generation, so the speed is never
 * overstated more than the safety margins allow. Speed = total tokens / total generation time; delay = the
 * mean delay.
 */
export class SpeedMeter {
  private samples: SpeedSample[] = [];

  observe(s: SpeedSample): void {
    if (!(s.totalMs > 0) || !(s.outputTokens >= 0)) return;
    this.samples.push({ outputTokens: s.outputTokens, totalMs: s.totalMs, ttftMs: s.ttftMs });
    if (this.samples.length > 30) this.samples.shift();
  }

  get count(): number {
    return this.samples.length;
  }

  estimate(): SpeedEstimate {
    if (!this.samples.length) return { ...DEFAULT_SPEED, throughputPerSec: DEFAULT_SPEED.tokensPerSec, measured: false };
    let delay = 0;
    let tokens = 0;
    let gen = 0;
    let total = 0;
    for (const s of this.samples) {
      const ttft = s.ttftMs ?? Math.min(s.totalMs, DEFAULT_SPEED.firstTokenMs);
      const d = Math.max(0, Math.min(ttft, s.totalMs / 2));
      delay += d;
      if (s.outputTokens >= 8) {
        tokens += s.outputTokens;
        gen += Math.max(1, s.totalMs - d);
        total += s.totalMs;
      }
    }
    const firstTokenMs = Math.max(50, delay / this.samples.length);
    const tokensPerSec = tokens ? Math.max(1, Math.min(MAX_PLAUSIBLE_TOKENS_PER_SEC, (tokens / gen) * 1000)) : DEFAULT_SPEED.tokensPerSec;
    const throughputPerSec = tokens ? Math.max(1, Math.min(tokensPerSec, (tokens / total) * 1000)) : DEFAULT_SPEED.tokensPerSec;
    return { tokensPerSec, firstTokenMs, throughputPerSec, measured: true };
  }
}

// ─────────────────────────────── The allowance ───────────────────────────────

export interface TurnsLeft {
  /** Expected turns left for this player, this one included (the game's estimate, already floored). */
  expected: number;
  /** Most turns this player could still have to make (e.g. up to the move cap). */
  max: number;
}

export interface MovePlan {
  /** The player's clock now; the call is aborted when it is used up. */
  remainingMs: number;
  /** Suggested time for this turn (shown in the prompt). */
  suggestedMs: number;
  /** Time this turn may take at most, first-token delay included. */
  budgetMs: number;
  /** Output-token allowance (including hidden reasoning). */
  maxOutputTokens: number;
}

/** Safety factors: speed can be ~20% slower than measured, latency ~15% longer. */
const SPEED_MARGIN = 1.25;
const DELAY_MARGIN = 1.15;

/**
 * Size one turn from the clock.
 *
 *   E = expected turns left, M = most turns left.
 *   reserve = (M − 1) × the time of a minimal answer (delay + minTokens), with margins: what every later turn
 *     needs at the very least, so a player can always still answer up to the move cap.
 *   pool = remaining − reserve;  suggested = pool / E.
 *   budget = min(2 × suggested, 70% of the pool) for E ≥ 8 (the factor 2 shrinks towards 1 for the last few
 *     turns of a debate, so one speech never takes almost everything).
 *   tokens = speed × (budget − delay), and never more than throughput × budget (the measured whole-call rate,
 *     which cannot overrun by more than one delay even for a model that hides its reasoning); never below an
 *     equal share of what is left over the most turns possible; clamped between minTokens and the normal maximum.
 *
 * Each turn then spends at most ~2/E of the free pool (E ≥ 8 for board games), so the clock decays
 * geometrically and the reserve covers every later answer: following the allowance cannot flag while the
 * speed estimate holds.
 */
export function planMove(p: { remainingMs: number; turns: TurnsLeft; speed: SpeedEstimate; maxOutputTokens: number; minTokens: number }): MovePlan {
  const R = Math.max(0, p.remainingMs);
  const E = Math.max(1, p.turns.expected);
  const M = Math.max(1, Math.ceil(p.turns.max));
  const v = p.speed.tokensPerSec / 1000; // tokens per ms
  const tp = (p.speed.throughputPerSec ?? p.speed.tokensPerSec) / 1000;
  const d = p.speed.firstTokenMs * DELAY_MARGIN;
  const tMin = d + (p.minTokens / v) * SPEED_MARGIN;
  const reserve = (M - 1) * tMin;
  const pool = Math.max(0, R - reserve);
  const mult = E >= 8 ? 2 : 1 + E / 8;
  const budgetMs = Math.min((mult * pool) / E, 0.7 * pool);
  const suggestedMs = Math.min(pool / E, budgetMs);
  const fromBudget = Math.floor(Math.min(v * Math.max(0, budgetMs - d), tp * budgetMs) / SPEED_MARGIN);
  // Equal share of everything left over the most turns possible (keeps a slow player answering to the cap).
  const share = R / (M + 1) / 1.3;
  const fromShare = Math.min(256, Math.floor(Math.min(v * Math.max(0, share - d), tp * share) / SPEED_MARGIN));
  const tokens = Math.max(p.minTokens, Math.min(p.maxOutputTokens, Math.max(fromBudget, fromShare)));
  return { remainingMs: R, suggestedMs: Math.max(0, suggestedMs), budgetMs, maxOutputTokens: Math.floor(tokens) };
}

// ─────────────────────────────── The game clock ───────────────────────────────

export interface ClockOptions {
  timeControl: TimeControl;
  /** Per seat: the player's speed in this tournament (updated after every call). */
  speed: [SpeedMeter, SpeedMeter];
  /** Per seat: answers instantly and is never charged (the Random Baseline). */
  instant?: [boolean, boolean];
  /** The normal output-token maximum (the allowance never exceeds it). */
  maxOutputTokens: number;
}

/** One game's two clocks. */
export class GameClock {
  readonly initialMs: number;
  readonly incrementMs: number;
  readonly remaining: [number, number];
  readonly flagged: [boolean, boolean] = [false, false];
  /** Completed turns per seat. */
  readonly made: [number, number] = [0, 0];
  readonly options: ClockOptions;

  constructor(o: ClockOptions) {
    this.options = o;
    this.initialMs = Math.round(o.timeControl.minutes * 60_000);
    this.incrementMs = Math.round((o.timeControl.incrementSec ?? 0) * 1000);
    this.remaining = [this.initialMs, this.initialMs];
  }

  isInstant(side: Side): boolean {
    return Boolean(this.options.instant?.[side]);
  }

  plan(side: Side, turns: TurnsLeft, minTokens: number): MovePlan {
    return planMove({ remainingMs: this.remaining[side], turns, speed: this.options.speed[side].estimate(), maxOutputTokens: this.options.maxOutputTokens, minTokens });
  }

  /** Charge one attempt's model time. Returns true when the flag fell. */
  charge(side: Side, ms: number): boolean {
    if (this.isInstant(side) || this.flagged[side]) return this.flagged[side];
    this.remaining[side] = Math.max(0, this.remaining[side] - Math.max(0, ms));
    if (this.remaining[side] <= 0) this.flagged[side] = true;
    return this.flagged[side];
  }

  /** The flag fell (the call was aborted at the limit). */
  flag(side: Side): void {
    this.remaining[side] = 0;
    this.flagged[side] = true;
  }

  observe(side: Side, s: SpeedSample): void {
    if (!this.isInstant(side)) this.options.speed[side].observe(s);
  }

  /** A turn is complete: add the increment. */
  moveDone(side: Side): void {
    this.made[side]++;
    if (!this.flagged[side]) this.remaining[side] += this.incrementMs;
  }

  snapshot(): [number, number] {
    return [Math.round(this.remaining[0]), Math.round(this.remaining[1])];
  }

  /** Per-call limit for the seat (none for an instant seat). */
  limitFor(side: Side): number | undefined {
    return this.isInstant(side) ? undefined : Math.max(1, Math.floor(this.remaining[side]));
  }

  /** What the prompt shows this seat for this turn. */
  view(side: Side, plan: MovePlan, words: ClockWords): ClockView {
    return {
      initialMs: this.initialMs,
      incrementMs: this.incrementMs,
      remainingMs: this.remaining[side],
      opponentMs: this.remaining[1 - side]!,
      opponentFlagged: this.flagged[1 - side]!,
      made: this.made[side],
      suggestedMs: plan.suggestedMs,
      maxOutputTokens: plan.maxOutputTokens,
      words,
    };
  }

  record(): { initialMs: number; incrementMs: number; final: [number, number]; flagged: [boolean, boolean] } {
    return { initialMs: this.initialMs, incrementMs: this.incrementMs, final: this.snapshot(), flagged: [this.flagged[0], this.flagged[1]] };
  }
}

// ─────────────────────────────── Prompt text ───────────────────────────────

export type ClockWords = Pick<ArenaClockSpec, 'unit' | 'units' | 'finish' | 'closing' | 'flagRule' | 'troubleLoss'>;

/** Everything a clocked prompt shows about the clock (built by GameClock.view). */
export interface ClockView {
  initialMs: number;
  incrementMs: number;
  remainingMs: number;
  opponentMs: number;
  opponentFlagged?: boolean;
  made: number;
  suggestedMs: number;
  maxOutputTokens: number;
  words: ClockWords;
}

function banner(v: ClockView): string | null {
  const tier = urgencyTier(v.remainingMs, v.initialMs);
  if (tier === 'trouble') return `== TIME TROUBLE: ${fmtClockText(v.remainingMs)} LEFT ==\n${NOW[v.words.unit]} NOW. Do not analyse. ${cap(v.words.finish)} immediately or ${v.words.troubleLoss}.`;
  if (tier === 'pressure') return `== TIME PRESSURE ==\nUnder a minute left. Decide fast: a quick sensible ${v.words.unit} now beats running out of time.`;
  return null;
}

const NOW: Record<ClockWords['unit'], string> = { move: 'Move', decision: 'Act', speech: 'Speak' };
const VERB: Record<ClockWords['unit'], string> = { move: 'move', decision: 'act', speech: 'speak' };
const GERUND: Record<ClockWords['unit'], string> = { move: 'moving', decision: 'acting', speech: 'speaking' };
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** The urgency banner for the very top of the prompt (time pressure / time trouble), or null. */
export function clockBanner(v: ClockView | undefined): string | null {
  return v ? banner(v) : null;
}

/** The "== CLOCK (SPEED GAME) ==" section near the top of every clocked prompt. */
export function clockSection(v: ClockView): string {
  const { words: w } = v;
  const inc = v.incrementMs ? `plus ${Math.round(v.incrementMs / 1000)} second${v.incrementMs === 1000 ? '' : 's'} added after each of your ${w.units}` : 'no increment';
  const tier = urgencyTier(v.remainingMs, v.initialMs);
  const lines = [
    '== CLOCK (SPEED GAME) ==',
    `This is a speed game: ${VERB[w.unit]} briskly. Each player has ${fmtClockText(v.initialMs)} in total for ALL of their ${w.units} in this game (${inc}). Your clock runs only while you are answering. ${w.flagRule}`,
    `Your time left: ${fmtClockText(v.remainingMs)}. Your opponent's time left: ${v.opponentFlagged ? '0:00 (out of time)' : fmtClockText(v.opponentMs)}. You have made ${v.made} ${v.made === 1 ? w.unit : w.units}.`,
    `Suggested time for this ${w.unit}: ${aboutTime(v.suggestedMs)}. Your reply this ${w.unit} is limited to about ${thousands(v.maxOutputTokens)} tokens, including any hidden reasoning, ${v.maxOutputTokens < 100 ? `so do not reason at length: ${w.finish} straight away` : `so think briefly and ${w.finish}`}. ${w.closing}`,
  ];
  if (tier === 'low') lines.push(`Your clock is running down. Keep your ${w.units} quick.`);
  if (!v.opponentFlagged && v.opponentMs < v.remainingMs) {
    lines.push(urgencyTier(v.opponentMs, v.initialMs) === 'pressure' || urgencyTier(v.opponentMs, v.initialMs) === 'trouble' ? `Your opponent is in time trouble (${fmtClockText(v.opponentMs)} left) — keep the pressure on by ${GERUND[w.unit]} quickly.` : 'Your opponent has less time left than you.');
  }
  return lines.join('\n');
}

/** The one-line reminder right before "It is your turn" (plus the urgency banner again when time is short). */
export function clockReminder(v: ClockView): string {
  const line = `Clock: you have ${fmtClockText(v.remainingMs)} left (your opponent ${v.opponentFlagged ? '0:00' : fmtClockText(v.opponentMs)}). Use ${aboutTime(v.suggestedMs)} and at most about ${thousands(v.maxOutputTokens)} tokens for this ${v.words.unit}.`;
  const b = banner(v);
  return b ? `${line}\n\n${b}` : line;
}

/** Adds the banner at the top, the clock section after the intro, and the reminder before the last section. */
export function withClock(parts: string[], v: ClockView | undefined, afterIntro = 1): string[] {
  if (!v) return parts;
  const out = parts.slice();
  out.splice(afterIntro, 0, clockSection(v));
  out.splice(out.length - 1, 0, clockReminder(v));
  const b = banner(v);
  if (b) out.unshift(b);
  return out;
}

/** True for the recorder's OutOfTimeError (matched by name, so test doubles can throw it too). */
export function isOutOfTime(err: unknown): err is Error & { elapsedMs?: number } {
  return err instanceof Error && err.name === 'OutOfTimeError';
}

// ─────────────────────────────── Engine helper ───────────────────────────────

export interface TimedCall {
  /** null when the flag fell (the reply, if any, came too late to count). */
  reply: ModelReply | null;
  /** Model time charged to the clock for this attempt. */
  chargedMs: number;
  flagged: boolean;
}

/**
 * One model call under the clock: sends the allowance and the remaining clock as the call's limits, charges the
 * attempt's own duration (request sent → reply complete, as measured by the recorder), feeds the speed meter,
 * and turns an aborted call (OutOfTimeError) into a flag fall. Without a clock it is a plain call.
 */
export async function timedComplete(
  clock: GameClock | null,
  side: Side,
  plan: MovePlan | null,
  call: (limits: { maxOutputTokens?: number; timeLimitMs?: number }) => Promise<ModelReply>,
  defaultMaxOutputTokens?: number,
): Promise<TimedCall> {
  if (!clock || clock.isInstant(side)) return { reply: await call({ maxOutputTokens: defaultMaxOutputTokens }), chargedMs: 0, flagged: false };
  const before = clock.remaining[side];
  try {
    const reply = await call({ maxOutputTokens: plan?.maxOutputTokens ?? defaultMaxOutputTokens, timeLimitMs: clock.limitFor(side) });
    const ms = Math.max(0, reply.totalMs);
    clock.observe(side, { outputTokens: reply.outputTokens, totalMs: reply.totalMs, ttftMs: reply.ttftMs });
    const flagged = clock.charge(side, ms);
    return { reply: flagged ? null : reply, chargedMs: Math.min(ms, before), flagged };
  } catch (err) {
    if (!isOutOfTime(err)) throw err;
    clock.flag(side);
    return { reply: null, chargedMs: before, flagged: true };
  }
}
