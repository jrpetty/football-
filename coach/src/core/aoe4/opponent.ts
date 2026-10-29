// An opponent's public record on AoE4World, looked up before a match by the
// player (by name), never during one: the civilisations they play and how
// often they win with each, their recent form, rating, and from their last
// few games, how they usually open (age-up landmark, first military units,
// Feudal time). The same public information AoE4World's own pages show.

import type { GameFacts, HistoryGame } from './history.ts'
import { itemKey } from './names.ts'

export interface OpponentOpening {
  readonly gameId: number
  readonly civ: string
  /** feudal → seconds. */
  readonly feudal: number | null
  /** The landmark completed as they reached Feudal (icon name), when there was one. */
  readonly landmark: string | null
  /** Their first two military units (icon names). */
  readonly firstUnits: readonly string[]
  readonly result: 'win' | 'loss' | null
}

export interface OpponentProfile {
  readonly games: number
  readonly wins: number
  readonly rating: number | null
  /** Most played first. */
  readonly civs: readonly { readonly civ: string; readonly games: number; readonly wins: number }[]
  /** Newest first, up to 10. */
  readonly recent: readonly ('win' | 'loss' | null)[]
  readonly openings: readonly OpponentOpening[]
}

const NOT_MILITARY = /^(villager|scout|fishing_boat|trade.*|trader|monk|imam|prelate|scholar|dervish|shaman)$/

export function opponentProfile(games: readonly HistoryGame[], facts: readonly GameFacts[]): OpponentProfile {
  const civs = new Map<string, { games: number; wins: number }>()
  for (const g of games) {
    const c = civs.get(g.civ) ?? { games: 0, wins: 0 }
    c.games++
    if (g.result === 'win') c.wins++
    civs.set(g.civ, c)
  }
  const openings = facts.flatMap((f) => {
    const g = games.find((x) => x.gameId === f.gameId)
    if (!g) return []
    const feudal = f.ages.feudal ?? null
    const lm = feudal !== null ? f.build.find((e) => e.kind === 'building' && itemKey(e.item).startsWith('landmark_') && Math.abs(e.t - feudal) <= 3) : undefined
    const units: string[] = []
    for (const e of f.build) if (e.kind === 'unit' && !NOT_MILITARY.test(itemKey(e.item)) && !units.includes(e.item) && units.length < 2) units.push(e.item)
    return [{ gameId: f.gameId, civ: g.civ, feudal, landmark: lm?.item ?? null, firstUnits: units, result: g.result }]
  })
  return {
    games: games.length,
    wins: games.filter((g) => g.result === 'win').length,
    rating: games.find((g) => g.rating !== null)?.rating ?? null,
    civs: [...civs].map(([civ, c]) => ({ civ, ...c })).sort((a, b) => b.games - a.games).slice(0, 6),
    recent: games.slice(0, 10).map((g) => g.result),
    openings,
  }
}
