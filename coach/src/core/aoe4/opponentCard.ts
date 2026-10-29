// The opponent card: one screen on the civilisation you are playing against,
// from the pack's researched knowledge (not from the opponent's own games):
// their power spikes and weak windows, the plans they usually go for and when,
// their unique units with what beats them (from your civilisation), and your
// own priorities for the age. Shown before the match, and during play where
// live coaching is allowed.

import type { RosterFaction, RosterUnit, Roster } from '../profile/schema.ts'

export interface OpponentCard {
  readonly civ: string
  readonly name: string
  readonly playstyle: string | null
  readonly howToPlayAgainst: string | null
  /** Age by age (1 Dark … 4 Imperial), their spikes, weak windows and usual plans. */
  readonly ages: readonly {
    readonly age: number
    readonly spikes: readonly string[]
    readonly weak: readonly string[]
    readonly plans: readonly { readonly name: string; readonly timing: string | null; readonly response: string | null }[]
  }[]
  /** Their unique units, earliest first, with what beats them for your civilisation. */
  readonly uniques: readonly { readonly name: string; readonly age: number | null; readonly beatenBy: readonly string[] }[]
  /** Your priorities this age, when your civilisation is known. */
  readonly yourPlan: { readonly civ: string; readonly age: number; readonly lines: readonly string[] } | null
}

export const AGE_NAMES = ['Dark Age', 'Feudal Age', 'Castle Age', 'Imperial Age']

function ageOf(u: RosterUnit, f: RosterFaction): number | null {
  return u.ageFor?.[f.id] ?? u.age ?? null
}

/** What beats a unit, as units your civilisation can make (its replacements; none it lacks). */
function answers(roster: Roster, u: RosterUnit, mine: RosterFaction | null): string[] {
  const byId = new Map(roster.units.map((x) => [x.id, x]))
  const out: string[] = []
  for (const ref of u.counteredBy) {
    const direct = byId.get(ref)
    const cands = direct ? [direct] : roster.units.filter((x) => x.classes.includes(ref) && !x.factions?.length).slice(0, 2)
    for (const c of cands) {
      let id: string | null = c.id
      if (c.factions?.length) id = mine && c.factions.includes(mine.id) ? c.id : null
      else if (mine?.replaces?.[c.id]) id = mine.replaces[c.id]
      else if (mine?.lacks?.includes(c.id)) id = null
      const name = id ? byId.get(id)?.name.replace(/\s*\(.*$/, '') : null
      if (name && !out.includes(name)) out.push(name)
    }
  }
  return out.slice(0, 3)
}

export function opponentCard(roster: Roster, theirCiv: string, yourCiv: string | null, yourAge = 1): OpponentCard | null {
  const f = roster.factions.find((x) => x.id === theirCiv)
  if (!f) return null
  const mine = yourCiv ? (roster.factions.find((x) => x.id === yourCiv) ?? null) : null
  const byId = new Map(roster.units.map((u) => [u.id, u]))
  const ages = (f.agePlans ?? [])
    .map((p) => ({
      age: p.age,
      spikes: (p.powerSpikes ?? []).slice(0, 3),
      weak: (p.weakWindows ?? []).slice(0, 2),
      plans: p.theirMoves.slice(0, 3).map((m) => ({ name: m.name, timing: m.timing ?? null, response: m.response[0] ?? null })),
    }))
    .sort((a, b) => a.age - b.age)
  const uniques = f.uniqueUnits
    .map((id) => byId.get(id))
    .filter((u): u is RosterUnit => !!u)
    .map((u) => ({ name: u.name.replace(/\s*\(.*$/, ''), age: ageOf(u, f), beatenBy: answers(roster, u, mine) }))
    .sort((a, b) => (a.age ?? 9) - (b.age ?? 9))
    .slice(0, 5)
  const plan = mine?.agePlans?.find((p) => p.age === yourAge)
  return {
    civ: f.id,
    name: f.name,
    playstyle: f.playstyle ?? null,
    howToPlayAgainst: f.howToPlayAgainst ?? null,
    ages,
    uniques,
    yourPlan: mine && plan ? { civ: mine.name, age: yourAge, lines: plan.playAs.slice(0, 3) } : null,
  }
}
