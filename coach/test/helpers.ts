// Shared test fixtures.

import registryJson from '../src/policy/registry.json' with { type: 'json' }
import { CoachEngine, type EngineOptions } from '../src/core/engine.ts'
import { resolvePolicy, validateRegistry, type ResolvedPolicy } from '../src/core/policy/registry.ts'
import type { GameProfile } from '../src/core/profile/schema.ts'
import { DEFAULT_SETTINGS, type CoachSettings } from '../src/core/settings.ts'
import type { Frame, NormRect } from '../src/core/types.ts'
import { builtInProfile } from '../src/profiles/index.ts'

export const arena = builtInProfile('synthetic-arena')
export const TEST_NOW = new Date('2026-10-28T12:00:00Z')

export const registry = (() => {
  const r = validateRegistry(registryJson)
  if (!r.ok) throw new Error(r.errors.join('\n'))
  return r.registry
})()

export function settings(over: Partial<CoachSettings> = {}): CoachSettings {
  return { ...DEFAULT_SETTINGS, profileId: arena.id, profileModeId: 'ranked', ...over }
}

/** The policy for a pack's mode, as the app resolves it. */
export function policyFor(profile: GameProfile, modeId: string | null, opts: { gameId?: string; tournament?: boolean } = {}): ResolvedPolicy {
  const mode = profile.modes.find((m) => m.id === modeId) ?? null
  return resolvePolicy(registry, {
    gameId: opts.gameId ?? profile.id,
    modeId,
    modeFacts: mode ? { multiplayer: mode.multiplayer, competitive: mode.competitive } : null,
    tournament: opts.tournament ?? false,
    now: TEST_NOW,
  })
}

/** An engine with the policy resolved from the registry for the settings' mode. */
export function engineFor(profile: GameProfile, s: CoachSettings, opts: Partial<EngineOptions> & { gameId?: string; tournament?: boolean } = {}): CoachEngine {
  return new CoachEngine(profile, s, {
    policy: policyFor(profile, s.profileModeId, opts),
    clock: () => TEST_NOW,
    ...opts,
  })
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
