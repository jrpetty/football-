/**
 * Graphics presets: naming, ordering, and what changing one actually does.
 *
 * The model had no preset dimension until this existed — every reference in
 * data/catalogue/references.json is expressed at one implicit preset, so a
 * fixture captured at "low" and one at "ultra" on identical hardware produced
 * the same prediction and the difference was absorbed as error.
 *
 * The multipliers in PRESET_MODEL are PRIORS, not fitted values, and the
 * reasoning (including the failed attempt to fit them from the corpus) is
 * recorded there. This module is the logic that applies them; read the constant
 * before trusting the output.
 */
import { PRESET_ALIASES, PRESET_MODEL } from './constants.ts';
import type { Archetype } from './types.ts';

export type Preset = (typeof PRESET_MODEL.ladder)[number];

export const PRESETS: readonly Preset[] = PRESET_MODEL.ladder;

/**
 * Map whatever a person or a review site calls a preset onto the ladder.
 *
 * Games do not agree on names — "Epic", "Cinematic", "Ultra Nightmare" and
 * "Max" all mean the top rung, and Cyberpunk's "Psycho" is a ray-tracing tier
 * that people quote as a preset. Refusing an unrecognised name would be worse
 * than mapping it: the caller would silently get the reference preset anyway.
 * An unrecognised name returns the reference preset AND is reported by
 * `isKnownPreset`, so a caller that cares can say so.
 */
export function normalisePreset(raw: string | undefined | null): Preset {
  if (!raw) return PRESET_MODEL.referencePreset;
  const k = String(raw).trim().toLowerCase();
  if ((PRESET_MODEL.ladder as readonly string[]).includes(k)) return k as Preset;
  const alias = PRESET_ALIASES[k];
  if (alias) return alias as Preset;
  return PRESET_MODEL.referencePreset;
}

/** False when `normalisePreset` fell back rather than recognising the name. */
export function isKnownPreset(raw: string | undefined | null): boolean {
  if (!raw) return false;
  const k = String(raw).trim().toLowerCase();
  return (PRESET_MODEL.ladder as readonly string[]).includes(k) || k in PRESET_ALIASES;
}

export interface PresetEffect {
  preset: Preset;
  /** FPS multiplier on the GPU-bound side. */
  gpu: number;
  /** FPS multiplier on the CPU-bound side. Much flatter, deliberately. */
  cpu: number;
  /** VRAM demand multiplier. Moves furthest — texture pools dominate. */
  vram: number;
  /** Rungs above (+) or below (-) the reference preset. Drives band widening. */
  steps: number;
}

/**
 * What a preset does to a title of this archetype.
 *
 * Archetype matters more than people expect: the gap between low and highest is
 * about 28% in an esports title and more than 2x in a UE5 one, because Lumen
 * and Nanite quality scale hard while a competitive shooter is mostly drawing
 * flat surfaces either way.
 */
export function presetEffect(preset: string | undefined | null, archetype: Archetype | string): PresetEffect {
  const p = normalisePreset(preset);
  const ref = PRESET_MODEL.referencePreset;
  const gpuRow = PRESET_MODEL.gpu[archetype] ?? PRESET_MODEL.gpu['aaa-raster'];
  const cpuRow = PRESET_MODEL.cpu[archetype] ?? PRESET_MODEL.cpu['aaa-raster'];
  const ladder = PRESET_MODEL.ladder as readonly string[];
  return {
    preset: p,
    gpu: gpuRow[p] ?? 1,
    cpu: cpuRow[p] ?? 1,
    vram: PRESET_MODEL.vram[p] ?? 1,
    steps: ladder.indexOf(p) - ladder.indexOf(ref),
  };
}

/** One rung down, or null at the bottom. Used to walk a settings search. */
export function lowerPreset(p: Preset): Preset | null {
  const i = PRESETS.indexOf(p);
  return i > 0 ? PRESETS[i - 1] : null;
}

/** One rung up, or null at the top. */
export function higherPreset(p: Preset): Preset | null {
  const i = PRESETS.indexOf(p);
  return i >= 0 && i < PRESETS.length - 1 ? PRESETS[i + 1] : null;
}

/** Human label. The ladder's own names are already the ones people use. */
export function presetLabel(p: Preset): string {
  return p === 'highest' ? 'highest / epic' : p;
}
