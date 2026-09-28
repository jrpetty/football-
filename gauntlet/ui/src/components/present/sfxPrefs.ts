/** Stored Presenter sound settings (pure, no DOM; see sfx.ts). */

/** Master volume 0..1 (the default is gentle; 1 is still well below clipping). */
export const DEFAULT_VOLUME = 0.5;

/** Parse the stored settings: sound is off unless it was switched on; volume is clamped to 0..1. */
export function parseSfxPrefs(on: string | null, vol: string | null): { on: boolean; volume: number } {
  const v = Number(vol);
  return { on: on === '1', volume: vol !== null && Number.isFinite(v) ? Math.max(0, Math.min(1, v)) : DEFAULT_VOLUME };
}
