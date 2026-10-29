// Upgrade timings for Age of Empires IV: Wheelbarrow (Mill), Textiles (Town
// Center) and the twelve Blacksmith upgrades, from AoE4World's summary of a
// game, so after the match only. The player's own times are compared with
// the build they train with and with the full game the build came from (a
// build's steps stop around the Castle Age; the game goes on for 20 minutes).
//
// A summary names an upgrade by the game's icon (e.g. "wheelbarrow",
// "gold_gather_1"); the Blacksmith ones are matched by their names or by the
// line and tier in the icon name ("melee_damage_1"), as the game's own
// attribute names have them (upgrade_melee_damage_i).

import type { BuildEvent } from '../truth/aoe4world.ts'

export interface TrackedUpgrade {
  readonly id: string
  readonly name: string
  readonly group: 'economy' | 'blacksmith'
  /** Where it is researched, for the table. */
  readonly at: string
}

const LINES: readonly { readonly line: string; readonly words: RegExp; readonly tiers: readonly [string, string, string] }[] = [
  { line: 'melee-damage', words: /melee_?(attack|damage)/, tiers: ['Bloomery', 'Decarbonization', 'Damascus Steel'] },
  { line: 'melee-armor', words: /melee_?armou?r/, tiers: ['Fitted Leatherwork', 'Insulated Helm', 'Master Smiths'] },
  { line: 'ranged-damage', words: /ranged?_?(attack|damage)/, tiers: ['Steeled Arrow', 'Balanced Projectiles', 'Platecutter Point'] },
  { line: 'ranged-armor', words: /ranged?_?armou?r/, tiers: ['Iron Undermesh', 'Wedge Rivets', 'Angled Surfaces'] },
]

export const TRACKED_UPGRADES: readonly TrackedUpgrade[] = [
  { id: 'wheelbarrow', name: 'Wheelbarrow', group: 'economy', at: 'Mill' },
  { id: 'textiles', name: 'Textiles', group: 'economy', at: 'Town Center' },
  ...LINES.flatMap((l) => l.tiers.map((name, i) => ({ id: `${l.line}-${i + 1}`, name, group: 'blacksmith' as const, at: 'Blacksmith' }))),
]

const slugOf = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, '_')
const TIER: Record<string, number> = { '1': 1, i: 1, '2': 2, ii: 2, '3': 3, iii: 3 }

/** Which tracked upgrade an icon name is, or null. */
export function trackedUpgrade(slug: string): string | null {
  const s = slug.toLowerCase().replace(/^.*\//, '').replace(/\.png$/, '')
  if (/wheelbarrow/.test(s)) return 'wheelbarrow'
  if (/textile|villager_health/.test(s)) return 'textiles'
  for (const l of LINES) {
    const byName = l.tiers.findIndex((n) => s.includes(slugOf(n)))
    if (byName >= 0) return `${l.line}-${byName + 1}`
    if (l.words.test(s)) {
      const tier = TIER[/_(i{1,3}|[123])$/.exec(s)?.[1] ?? '']
      if (tier) return `${l.line}-${tier}`
    }
  }
  return null
}

/** The first time each tracked upgrade finished (game seconds) in a game's events. */
export function upgradeTimes(events: readonly BuildEvent[]): Record<string, number> {
  const out: Record<string, number> = {}
  for (const e of [...events].sort((a, b) => a.t - b.t)) {
    if (e.kind !== 'upgrade') continue
    const id = trackedUpgrade(e.item)
    if (id && !(id in out)) out[id] = e.t
  }
  return out
}

export interface UpgradeRow {
  readonly upgrade: TrackedUpgrade
  /** Game seconds, or null when not researched (in the 20 minutes the summary covers). */
  readonly yours: number | null
  /** The build's step, when it has one. */
  readonly build: number | null
  /** In the game the build came from. */
  readonly source: number | null
}

/**
 * Rows for the upgrades in any of the three, in the order of the table. `gameSec` is how long the
 * player's game lasted: an upgrade the reference got later than that is not "missing".
 */
export function upgradeRows(yours: Readonly<Record<string, number>>, build: Readonly<Record<string, number>> | null, source: Readonly<Record<string, number>> | null, gameSec: number): UpgradeRow[] {
  return TRACKED_UPGRADES.filter((u) => u.id in yours || (build && u.id in build) || (source && u.id in source && source[u.id] <= gameSec)).map((u) => ({
    upgrade: u,
    yours: yours[u.id] ?? null,
    build: build?.[u.id] ?? null,
    source: source?.[u.id] ?? null,
  }))
}

const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`

/** The biggest gaps against the reference (the build, else the game it came from), biggest first. */
export function upgradeFindings(rows: readonly UpgradeRow[], max = 3): string[] {
  const out: { gap: number; text: string }[] = []
  for (const r of rows) {
    const ref = r.build ?? r.source
    if (ref === null) continue
    const whose = r.build !== null ? 'the build' : 'their game'
    if (r.yours === null) out.push({ gap: 600, text: `${r.upgrade.name}: not researched (${whose} at ${clock(ref)}).` })
    else if (r.yours - ref >= 45) out.push({ gap: r.yours - ref, text: `${r.upgrade.name} ${clock(r.yours - ref)} later than ${whose} (${clock(r.yours)} against ${clock(ref)}).` })
  }
  return out
    .sort((a, b) => b.gap - a.gap)
    .slice(0, max)
    .map((x) => x.text)
}
