// Shared test fixtures.

import { BUILT_IN_PROFILES } from '../src/profiles/index.ts'
import { DEFAULT_SETTINGS, type CoachSettings } from '../src/core/settings.ts'
import type { Frame, NormRect } from '../src/core/types.ts'

export const arena = BUILT_IN_PROFILES[0]

export function settings(over: Partial<CoachSettings> = {}): CoachSettings {
  return { ...DEFAULT_SETTINGS, profileId: arena.id, profileModeId: 'ranked', ...over }
}

/** A solid-colour frame, optionally with rectangles painted on it. */
export function frame(
  width: number,
  height: number,
  bg: readonly [number, number, number],
  rects: { r: NormRect; c: readonly [number, number, number] }[] = [],
  timestamp = 0,
): Frame {
  const data = new Uint8ClampedArray(width * height * 4)
  for (let i = 0; i < data.length; i += 4) {
    data[i] = bg[0]
    data[i + 1] = bg[1]
    data[i + 2] = bg[2]
    data[i + 3] = 255
  }
  for (const { r, c } of rects) {
    for (let y = Math.round(r.y * height); y < Math.round((r.y + r.h) * height); y++) {
      for (let x = Math.round(r.x * width); x < Math.round((r.x + r.w) * width); x++) {
        const i = (y * width + x) * 4
        data[i] = c[0]
        data[i + 1] = c[1]
        data[i + 2] = c[2]
      }
    }
  }
  return { width, height, data, timestamp }
}
