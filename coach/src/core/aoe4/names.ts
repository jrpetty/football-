// Names for the things in an AoE4World build order. The summary names each
// building, unit and technology by its icon ("gold_mining_camp",
// "building_landmark_house_of_wisdom", "horseman_2"); these turn that into a
// stable key (to match the same thing across games and build orders) and a
// readable name, using the pack's roster where it knows the thing.

import type { Roster } from '../profile/schema.ts'

const AGE_NAMES: Record<string, string> = { dark: 'Dark Age', feudal: 'Feudal Age', castle: 'Castle Age', imperial: 'Imperial Age' }

/** Common economy technologies, by icon. Others are named from the icon itself. */
const UPGRADE_NAMES: Record<string, string> = {
  wheelbarrow: 'Wheelbarrow',
  hunting_villager: 'Survival Techniques',
  wood_gather_1: 'Double Broadaxe',
  wood_gather_2: 'Lumber Preservation',
  wood2_lumber_preservation: 'Lumber Preservation',
  tree_fell_rate: 'Forestry',
  gold_gather_1: 'Specialized Pick',
  gold_gather_2: 'Acid Distillation',
  food_gather_1: 'Horticulture',
  food_gather_2: 'Fertilization',
  professional_scouts: 'Professional Scouts',
  textiles: 'Textiles',
}

/** Words in icon names that are the same thing as in the roster. */
const ALIASES: [RegExp, string][] = [
  [/^town_?centre(_capitol)?$/, 'town_center'],
  [/^(gold_|stone_)?mining_?camp$/, 'mining_camp'],
  [/^gristmill$/, 'mill'],
  [/manatarms/, 'man_at_arms'],
]

/** A stable key for a build-order item: the icon name without its prefixes and tier numbers. */
export function itemKey(slug: string): string {
  let k = slug.toLowerCase().trim()
  k = k.replace(/^building_landmark_(age\d_)?/, 'landmark_').replace(/^building_/, '')
  k = k.replace(/_age\d$/, '').replace(/_\d+$/, '')
  for (const [re, to] of ALIASES) k = k.replace(re, to)
  return k
}

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '')
const title = (s: string) => s.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()).replace(/\bOf\b/g, 'of').replace(/\bThe\b/g, 'the')

/** A readable name, from the roster where it is known: "Mining Camp", "House of Wisdom (landmark)", "Horseman". */
export function itemName(kind: 'building' | 'unit' | 'upgrade' | 'age', slug: string, roster?: Roster | null): string {
  if (kind === 'age') return AGE_NAMES[slug] ?? title(slug)
  if (kind === 'upgrade') return UPGRADE_NAMES[slug] ?? title(slug.replace(/_\d+$/, ''))
  const key = itemKey(slug)
  if (key.startsWith('landmark_')) {
    const n = norm(key.slice('landmark_'.length))
    for (const f of roster?.factions ?? []) for (const l of f.landmarks ?? []) if (norm(l.name) === n || n.endsWith(norm(l.name)) || norm(l.name).endsWith(n)) return `${l.name} (landmark)`
    return `${title(key.slice('landmark_'.length))} (landmark)`
  }
  const list = kind === 'building' ? roster?.buildings : roster?.units
  const id = key.replace(/_/g, '-')
  const hit = list?.find((x) => x.id === id || norm(x.name) === norm(key))
  return hit ? hit.name.replace(/\s*\(.*$/, '') : title(key)
}

/** The key of a name as the screen reports it ("Archery Range", "2 Mining Camps"), to match it with an icon name. */
export function nameKey(name: string): string {
  let k = name
    .toLowerCase()
    .replace(/^\d+\s*[x×]?\s*/, '')
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_|_$/g, '')
  // Plurals as the screen reports them: Spearmen, Archers, Men-at-Arms (Barracks is one building).
  k = k.replace(/^men_at_arms$/, 'man_at_arms')
  if (!/(man_at_arms|barracks|ss)$/.test(k)) k = k.replace(/men$/, 'man').replace(/ies$/, 'y').replace(/s$/, '')
  return itemKey(k)
}
