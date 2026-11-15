// Crafting, car repair and base building, from Bohemia's game scripts
// (scripts/build-dayz-crafting.mjs: the recipes the game registers, and what
// each vehicle needs to start) and the base-building reference
// (src/profiles/dayz/building.json: scripts, Central Economy and the DayZ
// Wiki). "How do I make a splint", "what can I make with rags", "what does my
// Olga need", "what do I need for a fence", "how do code locks work", "how
// does raiding work".

import crafting from '../../profiles/dayz/crafting.json' with { type: 'json' }
import building from '../../profiles/dayz/building.json' with { type: 'json' }

export interface Ingredient {
  readonly items: readonly string[]
  readonly minQty: number | null
  readonly use: 'consumed' | 'kept' | 'damaged' | 'quantity'
  readonly takes: number | null
}

export interface Recipe {
  readonly id: string
  readonly name: string
  readonly results: readonly { readonly item: string; readonly qty: number | null }[]
  readonly ingredients: readonly Ingredient[]
  readonly conditions: string | null
  readonly notes: string | null
}

export interface Vehicle {
  readonly class: string
  readonly name: string
  readonly kind: string
  readonly start: readonly string[]
  readonly wheels: { readonly item: string; readonly count: number; readonly spare: boolean | null } | null
  readonly battery: string | null
  readonly plug: string | null
  readonly radiator: boolean
  readonly lights: string | null
  readonly doors: readonly string[]
  readonly fuelL: number | null
  readonly coolantL: number | null
  readonly notes: string | null
}

export interface Build {
  readonly id: string
  readonly name: string
  readonly kit: { readonly name: string; readonly craft: string } | null
  readonly placement?: string | null
  readonly stages: readonly { readonly name: string; readonly needs: readonly (readonly [string, number | null])[]; readonly tools: readonly string[]; readonly notes?: string | null }[]
  readonly notes?: string | null
}

export const RECIPES: readonly Recipe[] = crafting.recipes as unknown as Recipe[]
export const VEHICLES: readonly Vehicle[] = crafting.vehicles as unknown as Vehicle[]
export const BUILDS: readonly Build[] = building.builds as unknown as Build[]
export const LOCKS = building.locks as readonly { readonly name: string; readonly dials: number | null; readonly where: string; readonly notes: string }[]
export const RAIDING: readonly string[] = building.raiding
export const TERRITORY: readonly string[] = building.territory
export const BUILD_TIPS: readonly string[] = building.tips
export const BUILD_SOURCES = building.sources as readonly { readonly title: string; readonly url: string }[]

const norm = (s: string) => ` ${s.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()} `
const STOP = new Set(['a', 'an', 'the', 'of', 'from', 'with', 'and', 'or', 'to', 'in', 'on', 'my', 'make', 'craft', 'how', 'do', 'i', 'can', 'you', 'what', 'need', 'break', 'down', 'improvised'])
const words = (s: string) => norm(s).trim().split(' ').filter((w) => w.length > 1 && !STOP.has(w))
const plural = (w: string) => [w, w.endsWith('s') ? w.slice(0, -1) : `${w}s`]

// --- Crafting ---------------------------------------------------------------------------------

export function isCraftQuestion(q: string): boolean {
  return /\b(how (?:do|can|would) (?:i|you) (?:make|craft|get)|how to (?:make|craft)|craft(?:ing)?|recipe(?:s)? for|what can i (?:make|craft) (?:with|from|out of)|make (?:a|an|some) |turn .* into)\b/i.test(q)
}

/** Recipes whose result is named in the question, best first ("how do I make rope" → rope from rags, rope from guts). */
export function recipesFor(question: string): Recipe[] {
  const q = norm(question)
  const wantsBreak = /\b(break down|dismantle|take apart|split)\b/i.test(question)
  const scored = RECIPES.map((r) => {
    const target = words(`${r.name} ${r.results.map((x) => x.item).join(' ')}`)
    const hit = target.filter((w) => plural(w).some((p) => q.includes(` ${p} `))).length
    const isBreak = /^(break down|split|cut out|cut up|gut|skin)\b/i.test(r.name)
    return { r, score: hit ? hit - (isBreak && !wantsBreak ? 1.5 : 0) - target.length * 0.05 : 0 }
  })
    .filter((x) => x.score > 0.5)
    .sort((a, b) => b.score - a.score)
  const top = scored[0]?.score ?? 0
  return scored.filter((x) => x.score >= top - 0.6).slice(0, 3).map((x) => x.r)
}

/** Recipes that use an item named in "what can I make with rags". */
export function recipesUsing(question: string): Recipe[] {
  const m = question.toLowerCase().match(/\b(?:with|from|out of|using)\s+(?:a |an |some |my )?(.+?)[?.!]*$/)
  if (!m) return []
  const want = words(m[1])
  if (!want.length) return []
  return RECIPES.filter((r) => r.ingredients.some((i) => i.items.some((name) => want.every((w) => plural(w).some((p) => norm(name).includes(` ${p} `)))))).slice(0, 12)
}

function ingredientLine(i: Ingredient): string {
  const names = i.items.length > 4 ? `${i.items.slice(0, 4).join(', ')} or another` : i.items.join(' or ')
  const qty = i.use === 'quantity' && i.takes ? `${i.takes}× ` : i.minQty && i.minQty > 1 ? `${i.minQty}× ` : ''
  const how = i.use === 'kept' ? ' (kept)' : i.use === 'damaged' ? ' (takes some wear)' : ''
  return `${qty}${names}${how}`
}

export function recipeLine(r: Recipe): string {
  const out = r.results.map((x) => `${x.qty && x.qty > 1 ? `${x.qty}× ` : ''}${x.item}`).join(' and ')
  return `${r.name}: ${r.ingredients.map(ingredientLine).join(' + ')}${out ? ` → ${out}` : ''}.${r.conditions ? ` ${r.conditions}.` : ''}`.replace(/\.\./g, '.')
}

export function craftAnswer(question: string): string | null {
  const using = /\bwhat can i (?:make|craft)\b/i.test(question) ? recipesUsing(question) : []
  if (using.length) return `With that you can make: ${[...new Set(using.map((r) => r.name))].slice(0, 10).join(', ')}. Ask "how do I make …" for any of them.`
  const found = recipesFor(question)
  if (!found.length) return null
  const [first, ...more] = found
  return `${recipeLine(first)}${first.notes ? ` ${first.notes}` : ''}${more.length ? ` Also: ${more.map(recipeLine).join(' ')}` : ''} Combine the two in your hands or inventory to craft.`
}

// --- Vehicles ---------------------------------------------------------------------------------

const VEHICLE_NAMES: readonly (readonly [RegExp, string])[] = [
  [/\bolga\b|\bcivilian ?sedan\b/i, 'CivilianSedan'],
  [/\bada\b|\b4x4\b|\boffroad hatchback\b|\bniva\b/i, 'OffroadHatchback'],
  [/\bgunter\b|\bhatchback\b/i, 'Hatchback_02'],
  [/\bsarka\b/i, 'Sedan_02'],
  [/\bhumvee\b|\bm1025\b|\bhummer\b/i, 'Offroad_02'],
  [/\bm3s\b|\btruck\b|\bv3s\b/i, 'Truck_01_Covered'],
  [/\b(?:dirt ?bike|enduro|motorbike|motorcycle|bike)\b/i, 'Motorbike_01_ColorBase'],
  [/\b(?:rubber )?boat\b|\bdinghy\b/i, 'Boat_01_ColorBase'],
]

export function vehicleIn(q: string): Vehicle | null {
  const id = VEHICLE_NAMES.find(([re]) => re.test(q))?.[1]
  return id ? (VEHICLES.find((v) => v.class === id) ?? null) : null
}

export function isVehicleQuestion(q: string): boolean {
  const s = q.toLowerCase()
  if (/\b(where do i find|where can i find|where does .* spawn)\b/.test(s)) return false
  return (!!vehicleIn(s) || /\b(car|vehicle|cars)\b/.test(s)) && /\b(fix|repair|start|run|running|need|needs|parts?|won'?t start|doesn'?t start|not starting|get .* working|working|drive|overheat\w*|wheels?|battery|spark ?plug|glow ?plug|radiator|fuel|coolant)\b/.test(s)
}

export function vehicleLine(v: Vehicle): string {
  const wheels = v.wheels ? `${v.wheels.count} wheels (${v.wheels.item})` : null
  const parts = [v.battery, v.plug, v.radiator ? 'a radiator filled with water' : null, wheels, v.lights ? `headlights: ${v.lights}` : null].filter(Boolean)
  return `${v.name}: to start it needs ${v.start.map((s) => (s === 'fuel' ? 'fuel' : s)).join(', ')}. Parts: ${parts.join('; ')}.${v.fuelL ? ` Fuel tank about ${v.fuelL} L.` : ''}${v.notes ? ` ${v.notes}` : ''}`
}

export function vehicleAnswer(question: string): string | null {
  const v = vehicleIn(question)
  if (v) return vehicleLine(v)
  if (!isVehicleQuestion(question)) return null
  return 'Every car needs a charged battery (a truck battery for the M3S), fuel, and on most a spark plug and a radiator with water (the Humvee takes a glow plug and no radiator). Then all its wheels, and doors and a hood for safety. Which car is it? "What does the Olga need?"'
}

// --- Base building ----------------------------------------------------------------------------

const BUILD_WORDS: readonly (readonly [RegExp, string])[] = [
  [/\bgate\b/i, 'gate'],
  [/\bwatch ?tower\b|\btower\b/i, 'watchtower'],
  [/\bflag ?pole\b|\bterritory\b|\bflag\b/i, 'flagpole'],
  [/\bfence\b|\bwall\b/i, 'fence'],
  [/\bshelter\b/i, 'shelter'],
  [/\btent\b/i, 'tents'],
  [/\bcrate\b/i, 'crate'],
  [/\bbarrel\b/i, 'barrel'],
  [/\b(stash|bury|buried|underground)\b/i, 'stash'],
]

export function buildIn(q: string): Build | null {
  const hit = BUILD_WORDS.find(([re]) => re.test(q))?.[1]
  if (!hit) return null
  return BUILDS.find((b) => b.id === hit) ?? BUILDS.find((b) => b.id.startsWith(hit) || b.name.toLowerCase().includes(hit)) ?? null
}

export type BuildTopic = 'build' | 'locks' | 'raiding' | 'territory' | 'tips'

export function buildTopic(q: string): BuildTopic | null {
  const s = q.toLowerCase()
  if (/\b(raid|raiding|raided|break into|get into (?:a|someone'?s) base|destroy (?:a |the )?(?:wall|gate|fence|base))\b/.test(s)) return 'raiding'
  if (/\b(code ?lock|combination lock|combo lock|lock pick|padlock|locks?)\b/.test(s) && /\b(how|work|works|forget|forgot|lock|open|remove|cut|set)\b/.test(s)) return 'locks'
  if (/\b(base building|build(?:ing)? a base|base tips|start(?:ing)? a base)\b/.test(s)) return 'tips'
  if (/\b(flag ?pole|territory)\b/.test(s) && /\b(how|work|works|refresh|lifetime|keep|despawn)\b/.test(s)) return 'territory'
  if (buildIn(s) && /\b(build|make|need|materials?|how many|nails|planks|logs|place|put up|craft|cost)\b/.test(s)) return 'build'
  return null
}

export function buildLine(b: Build): string {
  const stages = b.stages.map((st) => `${st.name}: ${st.needs.map(([n, q]) => `${q ?? 'some'} ${n.toLowerCase()}${q && q > 1 && !/(s|metal|wire|net)$/i.test(n) ? 's' : ''}`).join(', ') || 'nothing extra'}${st.tools.length ? ` (with ${st.tools.slice(0, 3).join(', ').toLowerCase()})` : ''}`)
  return `${b.name}${b.kit ? `: kit ${b.kit.craft.charAt(0).toLowerCase()}${b.kit.craft.slice(1)}` : ''}. ${stages.join('; ')}.${b.notes ? ` ${b.notes}` : ''}`
}

export function buildAnswer(question: string): string | null {
  const t = buildTopic(question)
  if (!t) return null
  if (t === 'raiding') return `Raiding: ${RAIDING.slice(0, 5).join(' ')}`
  if (t === 'locks') return LOCKS.map((l) => `${l.name}: ${l.notes}`).slice(0, 3).join(' ')
  if (t === 'territory') return `Flag pole: ${TERRITORY.slice(0, 4).join(' ')}`
  if (t === 'tips') return `Base building: ${BUILD_TIPS.slice(0, 5).join(' ')} Ask "what do I need for a fence" or "a watchtower" for the materials.`
  const b = buildIn(question)
  return b ? `${buildLine(b)} Check the prompt in game: material counts come from the DayZ Wiki and can change between versions.` : null
}
