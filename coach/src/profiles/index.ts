// Built-in game packs. Each is validated at load; an invalid built-in pack is
// a programming error and fails loudly rather than being skipped.

import { validateProfile, type GameProfile } from '../core/profile/schema.ts'
import aoe2 from './aoe2-de.json' with { type: 'json' }
import aoe4 from './aoe4.json' with { type: 'json' }
import codMw4 from './cod-mw4.json' with { type: 'json' }
import cs2 from './cs2.json' with { type: 'json' }
import eaFc from './ea-fc.json' with { type: 'json' }
import generic from './generic.json' with { type: 'json' }
import syntheticArena from './synthetic-arena.json' with { type: 'json' }

function load(raw: unknown): GameProfile {
  const result = validateProfile(raw)
  if (!result.ok) throw new Error(`Built-in profile is invalid:\n${result.errors.join('\n')}`)
  return result.profile
}

/** Generic last: it is the fallback when no specific pack matches. */
export const BUILT_IN_PROFILES: readonly GameProfile[] = [load(codMw4), load(cs2), load(aoe4), load(eaFc), load(aoe2), load(syntheticArena), load(generic)]

export function builtInProfile(id: string): GameProfile {
  const p = BUILT_IN_PROFILES.find((x) => x.id === id)
  if (!p) throw new Error(`No built-in profile "${id}"`)
  return p
}
