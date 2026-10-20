/**
 * Chess clocks for speed games: m:ss (tenths under 10 s), amber pulse under a minute, red under 30 s, a
 * "TIME TROUBLE" badge under 20 s, and a flag with a short flash when the flag falls. Live clocks tick down
 * client-side from the last settled value; every recorded move settles them again.
 */
import { useEffect, useRef } from 'react';
import { clockCallout, clockDisplayState, fmtClockDisplay, tickingClock, timeControlLabel } from '../../../src/arena/clock.ts';
import { cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { sfx } from '../components/present/sfx.ts';
import { useNow } from '../hooks.ts';
import type { ArenaMove, GameClockRecord, LiveGame, TimeControl } from './types.ts';
import './clock.css';

export interface ClockPair {
  /** Both clocks as last settled (ms). */
  ms: [number, number];
  flagged: [boolean, boolean];
  /** The side whose clock is running (live), or null. */
  running: 0 | 1 | null;
  /** When the running clock was last settled (ISO). */
  since?: string;
  initialMs: number;
}

/** Clocks of a recorded game after `upTo` moves (replay); null for Classic games. */
export function replayClocks(clock: GameClockRecord | undefined, moves: ArenaMove[], upTo: number): ClockPair | null {
  if (!clock) return null;
  const last = [...moves.slice(0, upTo)].reverse().find((m) => m.clockMs);
  const flagged: [boolean, boolean] = [false, false];
  for (const m of moves.slice(0, upTo)) if (m.flag) flagged[m.side] = true;
  return { ms: last?.clockMs ?? [clock.initialMs, clock.initialMs], flagged, running: null, initialMs: clock.initialMs };
}

/** Clocks of a game in progress; null for Classic games. */
export function liveClocks(g: LiveGame, tc: TimeControl | null | undefined): ClockPair | null {
  if (!tc || !g.clockMs) return null;
  const flagged = g.flagged ?? [false, false];
  return { ms: g.clockMs, flagged, running: g.phase || flagged[g.toMove] ? null : g.toMove, since: g.clockSince ?? g.turnStartedAt, initialMs: Math.round(tc.minutes * 60_000) };
}

/** The value each clock shows right now (the running one ticks down). */
export function shownMs(c: ClockPair, side: 0 | 1, now: number): number {
  if (c.running !== side || !c.since) return c.ms[side];
  return tickingClock(c.ms[side], new Date(c.since).getTime(), now);
}

/** Re-render often enough for tenths when a clock is running. */
export function useClockNow(c: ClockPair | null): number {
  return useNow(c && c.running !== null ? 100 : null);
}

/** One clock face. */
export function ClockFace({ ms, flagged, running, big, label }: { ms: number; flagged?: boolean; running?: boolean; big?: boolean; label?: string }) {
  const st = clockDisplayState(ms, flagged);
  return (
    <span
      className={cx('ar-clock', `st-${st}`, running && 'running', big && 'big')}
      role="timer"
      aria-label={`${label ? `${label}: ` : ''}${st === 'flagged' ? 'out of time' : `${fmtClockDisplay(ms)} left`}`}
      title={st === 'flagged' ? 'Flag fallen: out of time' : st === 'trouble' ? 'Time trouble: under 20 seconds' : undefined}
    >
      {st === 'flagged' ? <Icon.Flag width={big ? 18 : 14} height={big ? 18 : 14} aria-hidden="true" /> : <span className="ar-clock-dot" aria-hidden="true" />}
      <b className="tnum">{st === 'flagged' ? '0:00' : fmtClockDisplay(ms)}</b>
      {st === 'trouble' && (running || big) && <span className="ar-clock-badge">TIME TROUBLE</span>}
    </span>
  );
}

/** "Speed 4+0" chip. */
export function TimeControlChip({ tc }: { tc: TimeControl | null | undefined }) {
  if (!tc) return null;
  return (
    <span className="badge outline ar-tc-chip" title="Each player has this much time for all of their moves in a game">
      ⏱ {timeControlLabel(tc)}
    </span>
  );
}

/** A soft tick each second while the running clock is in its last 10 s (Presenter sound effects; silent when they are off). */
export function useClockTicks(ms: number | null, enabled: boolean): void {
  const last = useRef<number | null>(null);
  useEffect(() => {
    if (!enabled || ms === null || ms <= 0 || ms > 10_000) {
      last.current = null;
      return;
    }
    const sec = Math.ceil(ms / 1000);
    if (last.current !== sec) {
      last.current = sec;
      sfx.clockTick();
    }
  }, [ms, enabled]);
}

/** One plain-English line about the clocks for the broadcast caption ("Black is down to 0:12!"), or null. */
export function clockLine(c: ClockPair | null, names: [string, string], now: number): string | null {
  if (!c) return null;
  const order: Array<0 | 1> = c.running !== null ? [c.running, (1 - c.running) as 0 | 1] : [0, 1];
  for (const side of order) {
    const line = clockCallout(names[side], shownMs(c, side, now), c.flagged[side], c.initialMs);
    if (line) return line;
  }
  return null;
}

/** Both final clocks of a finished game (cards, tables); nothing for Classic games. */
export function GameClocks({ clock, names }: { clock: GameClockRecord | undefined; names?: [string, string] }) {
  if (!clock) return null;
  return (
    <span className="ar-game-clocks" aria-label="Clocks at the end of the game">
      {([0, 1] as const).map((i) => (
        <ClockFace key={i} ms={clock.final[i]} flagged={clock.flagged[i]} label={names?.[i]} />
      ))}
    </span>
  );
}
