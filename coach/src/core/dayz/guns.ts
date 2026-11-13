// DayZ guns, answered on this computer from the pack's arsenal (built by
// scripts/build-dayz-weapons.mjs): what a gun is and looks like, its calibre
// and magazines, how far it shoots (bullet drop over its zero), where it
// spawns on each map, what fits on it, which guns share a round, how two
// compare, and the best of a kind.

import type { ArsenalItem } from '../profile/schema.ts'
import { mapPreference } from './ask.ts'

type Profile = { readonly knowledge: { readonly arsenal?: readonly ArsenalItem[] } }
type Context = Readonly<Record<string, string | number>>
type MapId = 'chernarus' | 'livonia' | 'sakhal'

const MAP_NAMES: Readonly<Record<MapId, string>> = { chernarus: 'Chernarus', livonia: 'Livonia', sakhal: 'Sakhal' }
const WHERE_KEY: Readonly<Record<MapId, string>> = { chernarus: 'whereChernarus', livonia: 'whereLivonia', sakhal: 'whereSakhal' }

const CLASS_WORDS: Readonly<Record<string, string>> = {
  pistol: 'pistol',
  revolver: 'revolver',
  smg: 'submachine gun',
  shotgun: 'shotgun',
  assault_rifle: 'assault rifle',
  battle_rifle: 'battle rifle',
  marksman_rifle: 'marksman rifle',
  bolt_action_rifle: 'bolt-action rifle',
  semi_auto_rifle: 'semi-automatic rifle',
  lever_action_rifle: 'lever-action rifle',
  break_action_rifle: 'break-action rifle',
  rimfire_rifle: '.22 rifle',
  crossbow: 'crossbow',
  grenade_launcher: 'grenade launcher',
  flare_gun: 'flare pistol',
}

/** Calibres as players say them → the pack's calibre names. Longer patterns first (5.45 before .45). */
const CALIBRES: readonly (readonly [RegExp, string])[] = [
  [/\b12 ?(?:ga|gauge|g)\b|\bbuck ?shot\b|\bslugs?\b|\bshells?\b/, '12ga'],
  [/5\.45|\b545\b/, '5.45x39mm'],
  [/5\.56|\b556\b/, '5.56x45mm'],
  [/7\.62 ?x ?39|\b762x39\b|\b7\.62 short\b/, '7.62x39mm'],
  [/7\.62 ?x ?54|\b762x54\b|\b54r\b/, '7.62x54mmR'],
  [/\.308|\b308\b|7\.62 ?x ?51/, '.308 WIN'],
  [/9 ?x ?39|\b939\b/, '9x39mm'],
  [/9 ?(?:x ?19|mm)\b|\b9x19\b/, '9x19mm'],
  [/(?<![\d.])\.?380\b/, '.380 ACP'],
  [/(?<![\d.])\.45\b|\b45 acp\b/, '.45 ACP'],
  [/\.357|\b357\b/, '.357'],
  [/\.22\b|\b22 ?lr\b/, '.22 LR'],
  [/\b40 ?mm\b/, '40mm grenade'],
]

export function guns(profile: Profile): readonly ArsenalItem[] {
  return (profile.knowledge.arsenal ?? []).filter((a) => a.category === 'weapon')
}

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9.]+/g, ' ').replace(/(^|\s)\.|\.(\s|$)/g, '$1$2').replace(/\s+/g, ' ').trim()

/** Every gun named in the text, in the order they appear: by name, a nickname, the real gun, or the game's class name. */
export function gunsIn(text: string, profile: Profile): ArsenalItem[] {
  const q = ` ${norm(text)} `
  const tokens = new Set(q.trim().split(' '))
  const hits: { item: ArsenalItem; at: number; len: number }[] = []
  for (const item of guns(profile)) {
    let best: { at: number; len: number } | null = null
    for (const n of [item.name, ...(item.aliases ?? [])]) {
      const spaced = norm(n)
      const compact = spaced.replace(/ /g, '')
      // Two letters only for a short name written in capitals (AK) or with a digit (M4).
      if (spaced.length < 2 || (spaced.length < 3 && !/\d/.test(spaced) && n !== n.toUpperCase())) continue
      const at = q.indexOf(` ${spaced} `)
      const hit = at >= 0 ? { at, len: spaced.length } : compact.length >= 3 && tokens.has(compact) ? { at: q.indexOf(` ${compact} `), len: compact.length } : null
      if (hit && (!best || hit.len > best.len)) best = hit
    }
    if (best) hits.push({ item, ...best })
  }
  // A longer name inside the same words wins (the Sawed-off Mosin over the Mosin).
  const kept = hits.filter((h) => !hits.some((o) => o !== h && o.len > h.len && o.at <= h.at && o.at + o.len >= h.at + h.len))
  return kept.sort((a, b) => a.at - b.at).map((h) => h.item)
}

export function findGun(text: string, profile: Profile): ArsenalItem | null {
  return gunsIn(text, profile)[0] ?? null
}

export function calibreIn(text: string): string | null {
  const q = text.toLowerCase()
  return CALIBRES.find(([re]) => re.test(q))?.[1] ?? null
}

const s = (i: ArsenalItem, k: string): string | number | undefined => i.stats?.[k]
const num = (i: ArsenalItem, k: string): number | null => (typeof i.stats?.[k] === 'number' ? (i.stats[k] as number) : null)
const kind = (i: ArsenalItem) => CLASS_WORDS[i.class ?? ''] ?? 'gun'
const dmgText = (i: ArsenalItem) => (num(i, 'damage') ? `${num(i, 'damage')} damage${s(i, 'calibre') === '12ga' ? ' a slug' : ''}` : null)
const short = (i: ArsenalItem) => i.bestRange === 'close' || i.bestRange === 'close-mid'
/** The drop worth saying: at 100 m for short-range guns, at 300 m for the rest. */
function dropText(i: ArsenalItem): string | null {
  const zero = num(i, 'zeroForHold')
  if (!zero || s(i, 'calibre') === '12ga') return null
  const at = short(i) ? 100 : 300
  const hold = num(i, `hold${at}`)
  return hold && zero < at && hold < 30 ? `${metres(hold)} high at ${at} m over a ${zero} m zero` : null
}
const metres = (m: number) => (m < 1 ? `${Math.round(m * 100)} cm` : `${m.toFixed(1)} m`)

function magazineText(i: ArsenalItem): string | null {
  const mags = s(i, 'magazines')
  const internal = s(i, 'internalMagazine')
  if (mags) return `${mags} magazines`
  if (internal) return `holds ${internal}`
  return null
}

/** How far it shoots: the hold over its zero at range, the speed it keeps, its sights. */
export function rangeLine(i: ArsenalItem): string {
  const cal = s(i, 'calibre')
  if (cal === '12ga') return `${i.name}: shotgun, sighted for about 25 m. Buckshot is for close range; slugs carry further but drop fast. Best at ${i.bestRange} range.`
  const zero = num(i, 'zeroForHold')
  const parts: string[] = []
  if (zero) {
    const holds = [200, 300, 400, 600]
      .filter((m) => m > zero)
      .map((m) => [m, num(i, `hold${m}`)] as const)
      .filter((x): x is readonly [number, number] => x[1] !== null && x[1] > 0.05 && x[1] < 30)
      .slice(0, 3)
    if (holds.length) parts.push(`Zeroed at ${zero} m, aim about ${holds.map(([m, h]) => `${metres(h)} high at ${m} m`).join(', ')}`)
    const v0 = num(i, 'muzzleSpeed')
    const v300 = num(i, 'speed300')
    if (v0 && v300) parts.push(`the ${cal} round leaves at ${v0} m/s and keeps ${Math.round((v300 / v0) * 100)}% of that at 300 m`)
  }
  const sight = num(i, 'maxSightM')
  if (sight && sight > 100) parts.push(`sights zero out to ${sight} m`)
  const body = parts.length ? `${parts.join('; ')}.` : ''
  const note = zero ? ' (Drop worked out from the game’s speed and drag for the round.)' : ''
  return `${i.name}: best at ${i.bestRange} range.${body ? ` ${body}` : ''}${note}`
}

export function whereLine(i: ArsenalItem, map: MapId | null): string {
  const maps: MapId[] = map ? [map] : ['chernarus', 'livonia', 'sakhal']
  return `${i.name} spawns: ${maps.map((m) => `${MAP_NAMES[m]}, ${s(i, WHERE_KEY[m]) ?? 'unknown'}`).join('. ')}.`
}

export function lookLine(i: ArsenalItem): string {
  return `${i.name} (${kind(i)}${i.summary.includes('modelled on the') ? `, like the ${i.summary.match(/modelled on the ([^,.]+)/)?.[1]}` : ''}): ${i.look ?? 'no description yet.'}${s(i, 'variants') ? ` Finishes: ${s(i, 'variants')}.` : ''}`
}

export function attachmentsLine(i: ArsenalItem): string {
  const parts = [magazineText(i), s(i, 'sights') ? `sights and scopes: ${s(i, 'sights')}` : null, s(i, 'spawnsWith') ? `can spawn with: ${s(i, 'spawnsWith')}` : null].filter(Boolean)
  return parts.length ? `${i.name}: ${parts.join('; ')}.` : `${i.name}: no attachments listed; it uses its own fixed sights.`
}

export function ammoLine(i: ArsenalItem, profile: Profile): string {
  const cal = s(i, 'calibre')
  if (!cal) return `${i.name}: no calibre listed.`
  const others = guns(profile).filter((g) => g !== i && s(g, 'calibre') === cal && !/^Sawed-off/.test(g.name)).map((g) => g.name)
  const dmg = dmgText(i)
  const mags = s(i, 'magazines') ? ` It takes ${s(i, 'magazines')} magazines.` : s(i, 'internalMagazine') ? ` It holds ${s(i, 'internalMagazine')}.` : ''
  return `${i.name} fires ${cal}${dmg ? ` (${dmg}, ${num(i, 'shock')} shock a hit)` : ''}.${mags}${others.length ? ` The same round fits the ${others.join(', ')}.` : ''}`
}

/** The short card: what it is, its round and magazines, its fire modes, its range, and where to find it on this map. */
export function gunCard(i: ArsenalItem, map: MapId | null): string {
  const what = [s(i, 'calibre'), magazineText(i), s(i, 'fireModes') ? String(s(i, 'fireModes')).toLowerCase() : null].filter(Boolean).join(', ')
  const drop = dropText(i)
  const range = s(i, 'calibre') === '12ga' ? 'close range' : drop ? `${i.bestRange} range; about ${drop}` : `${i.bestRange} range`
  const where = map ? ` On ${MAP_NAMES[map]}: ${s(i, WHERE_KEY[map])}.` : ''
  const weak = i.weaknesses?.[0] ? ` Watch: ${i.weaknesses[0].replace(/:.*/, '')}.` : ''
  return `${i.name}, ${kind(i)}${what ? ` (${what})` : ''}. Best at ${range}.${where}${weak}`
}

export function compareLine(a: ArsenalItem, b: ArsenalItem, map: MapId | null): string {
  const row = (i: ArsenalItem) => {
    const bits = [kind(i), s(i, 'calibre'), dmgText(i), num(i, 'rpm') && num(i, 'rpm')! > 100 ? `${num(i, 'rpm')} rpm` : null, s(i, 'recoil') ? `${String(s(i, 'recoil')).toLowerCase()} recoil` : null, num(i, 'hold300') && num(i, 'hold300')! < 30 ? `${metres(num(i, 'hold300')!)} drop at 300 m` : null]
    const count = map ? spawnCount(i, map) : null
    if (count !== null) bits.push(`about ${count} on ${MAP_NAMES[map!]}`)
    return `${i.name}: ${bits.filter(Boolean).join(', ')}`
  }
  const da = num(a, 'damage') ?? 0
  const db = num(b, 'damage') ?? 0
  const verdicts: string[] = []
  if (da && db && da !== db) verdicts.push(`The ${da > db ? a.name : b.name} hits harder a round`)
  else if (da && db && s(a, 'calibre') === s(b, 'calibre')) verdicts.push('Same round')
  const ra = num(a, 'rpm') ?? 0
  const rb = num(b, 'rpm') ?? 0
  if (ra && rb && Math.abs(ra - rb) >= 100) verdicts.push(`the ${ra > rb ? a.name : b.name} fires faster`)
  const ca = map ? spawnCount(a, map) : null
  const cb = map ? spawnCount(b, map) : null
  if (ca !== null && cb !== null && ca !== cb && Math.max(ca, cb) >= 2 * Math.max(1, Math.min(ca, cb))) verdicts.push(`the ${ca > cb ? a.name : b.name} is far easier to find`)
  const verdict = verdicts.length ? ` ${verdicts.join('; ').replace(/^./, (c) => c.toUpperCase())}.` : ''
  return `${row(a)}. ${row(b)}.${verdict}`
}

function spawnCount(i: ArsenalItem, map: MapId): number | null {
  const m = String(s(i, WHERE_KEY[map]) ?? '').match(/about (\d+)/)
  return m ? Number(m[1]) : null
}

const KIND_WORDS: readonly (readonly [RegExp, (i: ArsenalItem) => boolean, string])[] = [
  [/\bsnipers?\b|\blong[- ]range\b|\bfar\b/, (i) => i.bestRange === 'long' || i.bestRange === 'mid-long', 'long range'],
  [/\bpistols?\b|\bhandguns?\b|\bsidearms?\b/, (i) => i.class === 'pistol' || i.class === 'revolver', 'pistols'],
  [/\bsmgs?\b|\bsub ?machine/, (i) => i.class === 'smg', 'submachine guns'],
  [/\bshotguns?\b/, (i) => i.class === 'shotgun', 'shotguns'],
  [/\bassault rifles?\b|\b(?:ar|ars)\b|\bautomatic\b/, (i) => i.class === 'assault_rifle' || i.class === 'battle_rifle', 'assault and battle rifles'],
  [/\bquiet\b|\bsilent\b|\bsuppress/, (i) => /quiet|suppress/i.test((i.strengths ?? []).join(' ')), 'quiet guns'],
  [/\bclose\b|\bcqb\b|\bindoors?\b|\bbuildings?\b/, (i) => i.bestRange === 'close' || i.bestRange === 'close-mid', 'close range'],
]

/** The best of a kind: the hardest-hitting that actually spawns, with how many there are on the map. */
export function bestLine(q: string, profile: Profile, map: MapId | null): string | null {
  const k = KIND_WORDS.find(([re]) => re.test(q))
  const pool = guns(profile).filter((i) => !/^Sawed-off/.test(i.name) && i.class !== 'flare_gun' && i.class !== 'grenade_launcher' && (!k || k[1](i)))
  const ranked = pool
    .map((i) => ({ i, n: map ? spawnCount(i, map) : Math.max(...(['chernarus', 'livonia', 'sakhal'] as MapId[]).map((m) => spawnCount(i, m) ?? 0)) }))
    .filter((x) => (x.n ?? 0) > 0)
    .sort((a, b) => (num(b.i, 'damage') ?? 0) - (num(a.i, 'damage') ?? 0) || (num(b.i, 'maxSightM') ?? 0) - (num(a.i, 'maxSightM') ?? 0))
  if (!ranked.length) return null
  const top = ranked.slice(0, 4).map(({ i, n }) => `${i.name} (${[s(i, 'calibre'), dmgText(i)].filter(Boolean).join(', ')}, about ${n}${map ? '' : ' per map at most'})`)
  // The common pick: the one with the most on the map among the good ones.
  const common = [...ranked.slice(0, 8)].sort((a, b) => (b.n ?? 0) - (a.n ?? 0))[0].i
  return `Hardest-hitting ${k ? k[2] : 'guns'}${map ? ` on ${MAP_NAMES[map]}` : ''}: ${top.join('; ')}. Easiest of those to find: ${common.name} (${s(common, map ? WHERE_KEY[map] : 'whereChernarus')}).`
}

export function gunsForCalibre(cal: string, profile: Profile): string {
  const list = guns(profile).filter((i) => s(i, 'calibre') === cal && !/^Sawed-off/.test(i.name))
  if (!list.length) return `No gun in my list fires ${cal}.`
  return `${cal} fits: ${list.map((i) => `${i.name} (${kind(i)})`).join(', ')}.`
}

function mapIn(q: string): MapId | null {
  if (/\b(livonia|enoch)\b/.test(q)) return 'livonia'
  if (/\bsakhal\b/.test(q)) return 'sakhal'
  if (/\bchernarus\b/.test(q)) return 'chernarus'
  return null
}

const GUN_WORDS = /\b(guns?|rifles?|snipers?|pistols?|handguns?|smgs?|shotguns?|weapons?|carbines?|bullet drop|zero(?:ing|ed)?|calib(?:re|er)|ammo|rounds?)\b/

/** A gun question: one is named, a calibre is asked about, the best of a kind, or the gun in hand. */
export function isGunQuestion(question: string, profile: Profile): boolean {
  const q = question.toLowerCase()
  if (findGun(q, profile)) return true
  if (calibreIn(q) && /\b(use|uses|take|takes|fire|fires|fits?|gun|guns|which|what)\b/.test(q)) return true
  if (/\b(best|good|strongest|which|what)\b/.test(q) && GUN_WORDS.test(q) && !/\b(where am i|nearest|closest)\b/.test(q)) return true
  return /\bwhat (?:gun|weapon) (?:is this|am i (?:holding|using))\b|\bbullet drop\b/.test(q)
}

export function answerGunQuestion(question: string, profile: Profile, context: Context): string | null {
  const q = question.toLowerCase()
  const map = mapIn(q) ?? mapPreference(context)
  const inHand = typeof context.weaponInHands === 'string' ? findGun(context.weaponInHands, profile) : null
  const named = gunsIn(q, profile)
  const one = named[0] ?? (/\b(this|my|in (?:my )?hands?|holding|using)\b/.test(q) ? inHand : null)
  if (!one && /\bwhat (?:gun|weapon) (?:is this|am i (?:holding|using))\b/.test(q)) return 'I have not read a gun in your hands yet. Hold it with the HUD showing, or tell me its name.'
  if (named.length >= 2 && /\b(vs|versus|or|compare\w*|differen\w*|better|than)\b/.test(q)) return compareLine(named[0], named[1], map)
  if (one) {
    if (/\blook(?:s)? like\b|\bwhat does\b.*\blook\b|\brecogni[sz]e|\btell (?:it|them) apart\b|\bspot\b/.test(q)) return lookLine(one)
    if (/\b(range|drop|zero\w*|how far|distance|metres|meters|hold ?over|effective|reach)\b/.test(q)) return rangeLine(one)
    if (/\b(where|find|spawn\w*|loot|get one|located|location)\b/.test(q)) return whereLine(one, map)
    if (/\b(attach\w*|scopes?|sights?|optics?|mags?|magazines?|suppress\w*|silencer|mods?|stocks?|handguards?|drums?)\b/.test(q)) return attachmentsLine(one)
    if (/\b(ammo|calib(?:re|er)|bullets?|rounds?|cartridges?|what does it (?:use|take|fire)|shoot)\b/.test(q)) return ammoLine(one, profile)
    return gunCard(one, map)
  }
  const cal = calibreIn(q)
  if (cal) return gunsForCalibre(cal, profile)
  if (/\b(best|good|strongest|which|what)\b/.test(q)) return bestLine(q, profile, map)
  return null
}

/** One line for commentary when a new gun is in the player's hands. */
export function gunLine(name: string, profile: Profile): string | null {
  const i = findGun(name, profile)
  if (!i) return null
  const drop = dropText(i)
  const range = s(i, 'calibre') === '12ga' ? 'close range' : drop ? drop.replace(/ over a .*$/, '') : `${i.bestRange} range`
  return `${i.name}: ${[s(i, 'calibre'), magazineText(i)].filter(Boolean).join(', ')}; ${range}.`
}
