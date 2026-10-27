// Built-in profiles. Each is validated at load; an invalid built-in profile is
// a programming error and fails loudly rather than being skipped.

import { validateProfile, type GameProfile } from '../core/profile/schema.ts'
import syntheticArena from './synthetic-arena.json' with { type: 'json' }

function load(raw: unknown): GameProfile {
  const result = validateProfile(raw)
  if (!result.ok) throw new Error(`Built-in profile is invalid:\n${result.errors.join('\n')}`)
  return result.profile
}

export const BUILT_IN_PROFILES: readonly GameProfile[] = [load(syntheticArena)]
