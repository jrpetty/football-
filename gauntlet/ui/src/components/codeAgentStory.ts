/**
 * Plain-data helpers for the Fix the Bug replay: the "bug the visible tests
 * didn't show" check and how the action budget was spent. Recorded frames only.
 */
import type { CodeReplayFrame, ReplayFrame } from '../types.ts';

/** Hidden tests still failing although every visible test passes (0 when not the case or not recorded). */
export function missedBug(code: Pick<CodeReplayFrame, 'tests' | 'hidden'>): number {
  const t = code.tests;
  const h = code.hidden;
  if (!t || !h || t.total === 0) return 0;
  return t.passed === t.total && h.passed < h.total ? h.total - h.passed : 0;
}

/** The actions taken up to frame `upTo` (start and verdict frames are not actions), and how many the counter says were used without a frame. */
export function spentActions(frames: ReplayFrame[], upTo: number, used: number): { acts: ReplayFrame[]; counts: Map<CodeReplayFrame['kind'], number>; unseen: number } {
  const acts = frames.slice(0, upTo + 1).filter((f) => f.code && f.code.kind !== 'start' && f.code.kind !== 'final');
  const counts = new Map<CodeReplayFrame['kind'], number>();
  for (const f of acts) counts.set(f.code!.kind, (counts.get(f.code!.kind) ?? 0) + 1);
  return { acts, counts, unseen: Math.max(0, used - acts.length) };
}
