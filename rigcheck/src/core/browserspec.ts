import type { Resolution } from './types.ts';

/**
 * What a browser can honestly say about the machine it is running on.
 *
 * This is the interpretation half — pure, and tested. The gathering half lives
 * in ui/bench/inspect.ts because it touches WebGL, timers and navigator.
 *
 * The reason it is worth doing at all: the specification form asks for a
 * processor, a graphics card, memory, storage and a display, and a browser can
 * answer two of those outright and narrow two more. Every field it fills is one
 * the user does not have to find in a list of hundreds.
 *
 * The reason it is worth doing CAREFULLY: two of these APIs look more precise
 * than they are.
 *
 * `navigator.deviceMemory` is deliberately coarse. It is rounded to a power of
 * two and **capped at 8**, so a machine with 32GB and a machine with 8GB both
 * report 8. Reading it as the memory total would understate most gaming
 * machines by a factor of four, and would do it silently. It is therefore
 * treated as a FLOOR — "8GB or more" — and never used to fill the field when it
 * reads at the cap.
 *
 * `navigator.hardwareConcurrency` is logical processors as the browser sees
 * them, which is not always what the machine has: it is clamped in some
 * privacy modes, reduced under battery saver on some platforms, and reflects
 * the container rather than the host inside one. It is good evidence and not
 * proof, and it is labelled that way.
 */

/** Where a detected value came from and how far it can be trusted. */
export type SpecConfidence =
  /** The platform stated it outright; wrong only if the platform lied. */
  | 'reported'
  /** Derived from something the platform stated, by a rule that can be checked. */
  | 'derived'
  /** Measured here and now, so subject to the conditions at the time. */
  | 'measured'
  /** A lower or upper bound only — the true value is on one side of it. */
  | 'bound';

export interface SpecField<T> {
  value: T;
  confidence: SpecConfidence;
  /** One sentence a user can read, saying where this came from and what limits it. */
  note: string;
}

export interface BrowserInspection {
  /** The unmasked renderer string, verbatim. Empty when the browser withheld it. */
  renderer: string;
  gpuName: SpecField<string> | null;
  threads: SpecField<number> | null;
  /** A FLOOR in gigabytes, never a total — see the note on deviceMemory above. */
  memoryFloorGB: SpecField<number> | null;
  platform: SpecField<string> | null;
  display: SpecField<{ width: number; height: number; refreshHz: number }> | null;
  webgpu: boolean;
  /** Anything the browser refused or could not answer. */
  notes: string[];
}

/** The cap the spec puts on navigator.deviceMemory. Above this it stops counting. */
export const DEVICE_MEMORY_CAP_GB = 8;

/**
 * Map a pixel width to the four resolutions the catalogue has references for.
 *
 * A display that is none of them gets the nearest one BELOW it rather than the
 * nearest overall: estimating a 3840-wide machine as 1440p flatters it, while
 * estimating a 2560-wide one as 4K does the opposite, and of the two errors the
 * flattering one is the one that costs somebody money.
 */
export function resolutionFor(width: number): Resolution | null {
  if (!Number.isFinite(width) || width <= 0) return null;
  if (width >= 3840) return '2160p';
  if (width >= 3440) return '3440x1440';
  if (width >= 2560) return '1440p';
  if (width >= 1920) return '1080p';
  return null;
}

/**
 * Round a measured refresh rate to the panel it most likely is.
 *
 * Counting animation frames gives 59.8 or 61 for a 60Hz panel and 143.6 for a
 * 144Hz one; reporting those verbatim would look like precision that is not
 * there. Anything not close to a standard rate is returned as measured, because
 * silently snapping a 75Hz panel to 60 would be worse than an odd-looking
 * number.
 */
export function standardRefresh(measured: number): number {
  const PANELS = [50, 60, 75, 100, 120, 144, 165, 170, 180, 200, 240, 280, 360, 480, 500];
  if (!Number.isFinite(measured) || measured <= 0) return 0;
  // NEAREST of the candidates in range, not the first one found. 165 and 170
  // are close enough together that a tolerance wide enough to catch either
  // catches both, and taking the first would report a 170Hz panel measured at
  // 168 as a 165Hz one.
  let best: { panel: number; off: number } | null = null;
  for (const panel of PANELS) {
    const off = Math.abs(measured - panel);
    if (off > Math.max(2, panel * 0.03)) continue;
    if (!best || off < best.off) best = { panel, off };
  }
  return best ? best.panel : Math.round(measured);
}

/**
 * A short, honest sentence about what the inspection established.
 *
 * Leads with what it could NOT do, because the useful thing to know about an
 * automatic reading is where it stops.
 */
export function inspectionSummary(i: BrowserInspection): string {
  const got: string[] = [];
  if (i.gpuName) got.push('the graphics card');
  if (i.threads) got.push('the processor thread count');
  if (i.display) got.push('the display');
  const missing = ['the processor model'];
  if (!i.memoryFloorGB) missing.push('memory');
  else missing.push('the exact memory fitted');
  if (!i.gpuName) missing.unshift('the graphics card');

  return (
    (got.length
      ? `Read ${list(got)} from this browser. `
      : 'This browser would not identify the hardware. ') +
    `It cannot see ${list(missing)}, so those still need you — a browser is not allowed to ` +
    `read the processor model, and the memory figure it does expose is capped at ` +
    `${DEVICE_MEMORY_CAP_GB}GB, so it can only ever set a floor.`
  );
}

/** "a", "a and b", "a, b and c" — a comma-joined list reads as a fragment. */
function list(items: string[]): string {
  if (items.length <= 1) return items[0] ?? '';
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}
