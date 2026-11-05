/**
 * A small drawing of what one rung of a difficulty ladder looks like (its size or difficulty), shown in the slot
 * on the right of each level of the climb (DifficultyLadder.tsx).
 *
 * STUB: the real drawings are being built separately and replace this file. Until then no ladder has a thumbnail,
 * and the climb lays out its rung slots without one.
 */
import type { JSX } from 'react';

/** True when `testId` has rung drawings. */
export function hasRungThumb(testId: string): boolean {
  void testId;
  return false;
}

/** The drawing of level `level` of `testId` (null when there is none). `size` is the side in px (about 40–56). */
export function RungThumb(props: { testId: string; level: number; size?: number; tone?: 'default' | 'broadcast' }): JSX.Element | null {
  void props;
  return null;
}
