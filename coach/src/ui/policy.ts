// The renderer's view of the publisher-policy registry: which game a session
// is, and what the registry allows for it right now.

import registryJson from '../policy/registry.json' with { type: 'json' }
import { resolvePolicy, validateRegistry, type PolicyRegistry, type ResolvedPolicy } from '../core/policy/registry.ts'
import type { GameProfile } from '../core/profile/schema.ts'
import type { CoachSettings } from '../core/settings.ts'

export const REGISTRY: PolicyRegistry = (() => {
  const r = validateRegistry(registryJson)
  // The registry ships with the app; an invalid one is a build error, not a runtime state.
  if (!r.ok) throw new Error(`The policy registry is invalid:\n${r.errors.join('\n')}`)
  return r.registry
})()

/** Games the registry has an entry for, for the generic pack's "which game is this?" choice. */
export const REGISTRY_GAMES: readonly { readonly id: string; readonly title: string }[] = (() => {
  const seen = new Map<string, string>()
  for (const e of REGISTRY.entries) {
    for (const id of e.gameIds) if (id !== '*' && id !== 'synthetic-arena' && !seen.has(id)) seen.set(id, e.game)
  }
  return [...seen].map(([id, title]) => ({ id, title })).sort((a, b) => a.title.localeCompare(b.title))
})()

/** A specific pack is its own game; the generic pack is whatever game the player says it is. */
export function gameIdFor(profile: GameProfile, settings: CoachSettings): string | null {
  return profile.depth === 'pack' ? profile.id : settings.gameId
}

export function policyFor(profile: GameProfile, settings: CoachSettings, now = new Date()): ResolvedPolicy {
  const mode = profile.modes.find((m) => m.id === settings.profileModeId) ?? null
  return resolvePolicy(REGISTRY, {
    gameId: gameIdFor(profile, settings),
    modeId: mode?.id ?? null,
    modeFacts: mode ? { multiplayer: mode.multiplayer, competitive: mode.competitive } : null,
    tournament: settings.tournament,
    now,
  })
}
