/**
 * Episode auto-play pacing (A in the Presenter). Each step holds for as long
 * as it needs: reveals get a longer beat than plain slides, and slides with a
 * lot to read stay up longer. On top of the hold, auto-play never advances
 * while something on the stage is still animating (see stageBusy()).
 */
import { useEffect } from 'react';

export type PaceKind =
  | 'title'
  | 'how'
  | 'explainer'
  | 'result'
  | 'final'
  | 'scatter'
  | 'medals'
  | 'outro'
  | 'trick'
  | 'trick-empty'
  | 'truth'
  | 'sim'
  | 'moment'
  | 'standings'
  | 'podium'
  | string;

export interface PaceInput {
  kind: PaceKind;
  reveal: number;
  maxReveal: number;
  /** Rows / models drawn on the slide (longer animations with more rows). */
  rows?: number;
  /** podium only: the reveal step is the drumroll / the winner. */
  drumroll?: boolean;
  winner?: boolean;
}

/** Milliseconds auto-play holds on this step before moving on. */
export function autoHoldMs(p: PaceInput): number {
  const rows = Math.max(1, p.rows ?? 6);
  const revealing = p.reveal < p.maxReveal;
  switch (p.kind) {
    case 'podium':
      if (p.winner) return 11000;
      if (p.drumroll) return 3400;
      return p.reveal === 0 ? 3500 : 5200;
    case 'trick':
      return revealing ? (p.reveal === 0 ? 7000 : 4500) : 9000;
    case 'final':
      return revealing ? 1400 : 10000;
    case 'standings':
      // Bars grow (~1.3 s), rows reorder (~0.9 s), then a beat to read the labels.
      return 7500 + rows * 150;
    case 'result':
      // Count-up of every row, then time to read.
      return 1300 + rows * 120 + 7500;
    case 'title':
      return 8000;
    case 'how':
      return 13000;
    case 'explainer':
      return 12000;
    case 'truth':
    case 'moment':
    case 'sim':
      return 12000;
    case 'scatter':
      return 11000;
    case 'medals':
      return 9500;
    case 'outro':
      return 8000;
    default:
      return 9000;
  }
}

// ── "Still animating" registry: components that tween with requestAnimationFrame
// (not visible to document.getAnimations) report themselves busy here.
const busy = new Set<symbol>();

export function useAutoBusy(active: boolean, ms: number, key: unknown): void {
  useEffect(() => {
    if (!active || ms <= 0) return;
    const token = Symbol('busy');
    busy.add(token);
    const t = setTimeout(() => busy.delete(token), ms);
    return () => {
      clearTimeout(t);
      busy.delete(token);
    };
  }, [active, ms, key]);
}

/** The bit of the Web Animations API stageBusy() reads (kept structural so this file has no DOM dependency). */
interface AnimatedRoot {
  getAnimations?: (o?: { subtree?: boolean }) => Array<{ playState: string; effect: { getComputedTiming(): { endTime?: unknown } } | null }>;
}

/** True while a finite CSS animation/transition or a registered tween is running inside `root`. */
export function stageBusy(root: AnimatedRoot | null): boolean {
  if (busy.size > 0) return true;
  if (!root || typeof root.getAnimations !== 'function') return false;
  try {
    return root.getAnimations({ subtree: true }).some((a) => {
      if (a.playState !== 'running') return false;
      const end = a.effect?.getComputedTiming().endTime;
      return typeof end === 'number' && Number.isFinite(end);
    });
  } catch {
    return false;
  }
}
