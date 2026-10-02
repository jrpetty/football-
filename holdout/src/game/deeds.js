// What each survivor has done, the name the camp gives them for it, and the
// short story their sheet tells. Deeds are counted where they happen (a run,
// a horde fight, a bench, the building site); a nickname is earned when a
// deed crosses a mark, and a better one replaces it later.
import { S, day, log } from './state.js'
import { ITEMS, STATIONS, OCCUPATIONS } from './data.js'
import { bus } from '../core/util.js'

// what a favourite weapon makes of you
const WEAPON_NICK = {
  fists: 'Knuckles',
  crowbar: 'Crowbar',
  bat: 'Slugger',
  pipe: 'Pipes',
  nailbat: 'Nails',
  spear: 'Spear',
  machete: 'Machete',
  axe: 'Axe',
  sledge: 'Sledge',
  katana: 'Blade',
  pistol: 'Two-Shot',
  revolver: 'Six-Gun',
  smg: 'Rattler',
  ar: 'Rattler',
  shotgun: 'Boomstick',
  crossbow: 'Whisper',
  rifle: 'Deadeye',
}
// and a long stretch at one station
const JOB_NICK = {
  farm: 'Greenthumb',
  kitchen: 'Cookie',
  infirmary: 'Doc',
  forge: 'Smithy',
  kiln: 'Smithy',
  lumber: 'Timber',
  scrapyard: 'Scrapper',
  radio: 'Sparks',
  electronics: 'Sparks',
  watchtower: 'Hawkeye',
  boiler: 'Stoker',
  generator: 'Stoker',
  still: 'Moonshine',
  training: 'Sarge',
  collector: 'Rainmaker',
  filter: 'Rainmaker',
  workbench: 'Tinker',
  weapons: 'Gunsmith',
  ammo: 'Brass',
  tailor: 'Needles',
  chemlab: 'Professor',
  research: 'Professor',
  fabricator: 'Gears',
  assembler: 'Gears',
}
const JOB_DAYS = 20
const wName = (id) => ITEMS[id]?.name || 'bare hands'
const plural = (n, a, b = a + 's') => `${n} ${n === 1 ? a : b}`
const favourite = (map = {}) => Object.entries(map).sort((a, b) => b[1] - a[1])[0] || null

// Every name a survivor can earn: highest rank wins, and a name is only ever
// replaced by a better one.
function candidates(s) {
  const d = s.deeds || {}
  const out = []
  const add = (rank, nick, why) => out.push({ rank, ...nick, why })
  const fav = favourite(d.weapons)
  if (fav && fav[1] >= 25 && WEAPON_NICK[fav[0]]) add(fav[1] >= 60 ? 4 : 3, { pre: WEAPON_NICK[fav[0]] }, `${fav[1]} infected put down with a ${wName(fav[0])}`)
  const kills = (d.melee || 0) + (d.ranged || 0)
  if (kills >= 200) add(7, { pre: 'Reaper' }, `${kills} infected put down`)
  if ((d.melee || 0) >= 100) add(6, { post: 'the Butcher' }, `${d.melee} infected put down up close`)
  if ((d.brutes || 0) >= 3) add(6, { pre: 'Giant-Killer' }, `${d.brutes} brutes brought down`)
  if ((d.specials || 0) >= 10) add(5, { post: 'the Hunter' }, `${d.specials} stalkers, screamers and bloaters dealt with`)
  if ((d.downs || 0) >= 6) add(4, { pre: 'Nine-Lives' }, `getting back up ${d.downs} times`)
  else if ((d.downs || 0) >= 3) add(2, { pre: 'Lucky' }, `getting back up ${d.downs} times`)
  if ((d.revives || 0) >= 8) add(5, { post: 'the Angel' }, `${d.revives} friends pulled back to their feet`)
  else if ((d.revives || 0) >= 3) add(3, { pre: 'Doc' }, `${d.revives} friends pulled back to their feet`)
  if ((d.rescues || 0) >= 2) add(4, { post: 'the Shepherd' }, `${d.rescues} strangers brought home from the city`)
  const runs = s.runs || 0
  if (runs >= 30) add(4, { pre: 'Long-Haul' }, `${runs} supply runs`)
  else if (runs >= 12) add(2, { pre: 'Roadrunner' }, `${runs} supply runs`)
  if ((d.loot || 0) >= 400) add(3, { pre: 'Packmule' }, `${d.loot} finds hauled home`)
  else if ((d.searched || 0) >= 80) add(2, { pre: 'Magpie' }, `${d.searched} places searched`)
  const th = d.thrown || {}
  if ((th.molotov || 0) >= 6) add(3, { pre: 'Firestarter' }, `${th.molotov} molotovs thrown`)
  if ((th.pipebomb || 0) >= 6) add(3, { pre: 'Fuse' }, `${th.pipebomb} pipe bombs thrown`)
  if ((d.raidKills || 0) >= 60) add(4, { pre: 'Gatekeeper' }, `${d.raidKills} infected put down at the wall`)
  else if ((d.raids || 0) >= 8) add(3, { post: 'of the Wall' }, `${d.raids} hordes held off`)
  if ((d.built || 0) >= 30) add(4, { post: 'the Builder' }, `${d.built} buildings raised`)
  else if ((d.built || 0) >= 10) add(2, { pre: 'Hammer' }, `${d.built} buildings raised`)
  if ((d.crafted || 0) >= 200) add(4, { pre: 'Gearhead' }, `${d.crafted} things made at the benches`)
  else if ((d.crafted || 0) >= 60) add(2, { pre: 'Tinker' }, `${d.crafted} things made at the benches`)
  const job = favourite(d.work)
  if (job && job[1] >= JOB_DAYS && JOB_NICK[job[0]]) add(2, { pre: JOB_NICK[job[0]] }, `${job[1]} days at the ${STATIONS[job[0]]?.name || job[0]}`)
  if (S && day() - (s.joined || 1) >= 120) add(3, { pre: 'Old-Timer' }, `${day() - (s.joined || 1)} days in the camp`)
  return out
}

// Look again after a deed: a better name, if one was earned.
export function nameCheck(s) {
  if (!s || s.nickBy === 'player') return
  let best = null
  for (const c of candidates(s)) if (!best || c.rank > best.rank) best = c
  if (!best || (s.nick && s.nick.rank >= best.rank)) return
  const was = s.nick ? callName(s) : null
  const same = s.nick && (s.nick.pre || null) === (best.pre || null) && (s.nick.post || null) === (best.post || null)
  s.nick = { pre: best.pre || null, post: best.post || null, why: best.why, rank: best.rank, day: same ? s.nick.day : S ? day() : 1 }
  // the same name for a bigger deed: nothing to announce
  if (same) return
  const now = callName(s)
  log(was ? `${was} goes by ${now} now, for ${best.why}.` : `${s.first} has a name in camp now: ${now}, for ${best.why}.`, 'story')
  bus.emit('nickname', s)
}
// Count a deed (and maybe earn a name).
export function deed(s, key, n = 1) {
  if (!s?.id || !n) return
  const d = (s.deeds ??= {})
  d[key] = (d[key] || 0) + n
  nameCheck(s)
}
function deedIn(s, map, key, n = 1) {
  if (!s?.id || !key) return
  const d = (s.deeds ??= {})
  const m = (d[map] ??= {})
  m[key] = (m[key] || 0) + n
}
// A kill, with what it was made with and where.
export function creditKill(s, z, weaponId, atWall) {
  if (!s?.id) return
  const d = (s.deeds ??= {})
  const w = weaponId || 'fists'
  const gun = ITEMS[w]?.kind === 'gun'
  d[gun ? 'ranged' : 'melee'] = (d[gun ? 'ranged' : 'melee'] || 0) + 1
  deedIn(s, 'weapons', w)
  if (z?.type === 'brute') d.brutes = (d.brutes || 0) + 1
  else if (z?.def?.stalk || z?.def?.scream || z?.def?.burst) d.specials = (d.specials || 0) + 1
  if (atWall) d.raidKills = (d.raidKills || 0) + 1
  nameCheck(s)
}
export function creditThrow(s, item) {
  deedIn(s, 'thrown', item)
  nameCheck(s)
}
// once a day: a day's work at their station, and time served
export function dailyDeeds() {
  for (const s of S.survivors) {
    if (s.status === 'ok' && s.job) {
      const st = S.stations.find((x) => x.id === s.job)
      if (st) deedIn(s, 'work', st.type)
    }
    nameCheck(s)
  }
}

// ---------------------------------------------------------------- names
export const nickText = (s) => (s?.nick ? s.nick.pre || s.nick.post : '')
// what the camp calls them: "Two-Shot Mara", "Mara the Butcher"
export const callName = (s) => (!s ? '' : !s.nick ? s.first : s.nick.pre ? `${s.nick.pre} ${s.first}` : `${s.first} ${s.nick.post}`)
// and in full: "Two-Shot Mara Lindqvist", "Mara Lindqvist, the Butcher"
export const fullName = (s) => (!s ? '' : !s.nick ? s.name : s.nick.pre ? `${s.nick.pre} ${s.name}` : `${s.name}${/^(the|of) /.test(s.nick.post) ? ', ' : ' '}${s.nick.post}`)
// The player names them themselves (or takes the name away).
export function setNick(s, text) {
  const t = String(text || '').trim().slice(0, 18)
  s.nickBy = 'player'
  s.nick = t ? { pre: /^(the|of) /i.test(t) ? null : t, post: /^(the|of) /i.test(t) ? t : null, why: 'a name you gave them', rank: 99, day: day() } : null
  bus.emit('nickname', s)
}
// Hand naming back to the camp.
export function campNames(s) {
  s.nickBy = null
  s.nick = null
  nameCheck(s)
  bus.emit('nickname', s)
}

// ---------------------------------------------------------------- the story
export function bio(s) {
  const d = s.deeds || {}
  const lines = []
  const occ = OCCUPATIONS[s.occ]?.name.toLowerCase()
  const who = occ || 'survivor'
  lines.push(`${/^[aeiou]/.test(who) ? 'An' : 'A'} ${who} before all this. Joined the camp on day ${s.joined || 1}.`)
  const kills = (d.melee || 0) + (d.ranged || 0)
  if (kills) {
    const fav = favourite(d.weapons)
    let t = `Has put down ${plural(kills, 'infected', 'infected')}`
    if (fav && fav[1] >= Math.max(3, kills * 0.4)) t += `, ${fav[1]} of them with ${fav[0] === 'fists' ? 'bare hands' : `a ${wName(fav[0])}`}`
    if (d.brutes) t += `, and ${plural(d.brutes, 'brute')}`
    lines.push(t + '.')
  }
  if (s.runs) lines.push(`Went out on ${plural(s.runs, 'supply run')}${d.loot ? ` and hauled home ${d.loot} finds` : ''}${d.rescues ? `, bringing ${plural(d.rescues, 'stranger')} back alive` : ''}.`)
  if (d.raids) lines.push(`Stood at the wall through ${plural(d.raids, 'horde')}${d.raidKills ? `, ${d.raidKills} down` : ''}.`)
  if (d.downs) lines.push(d.downs === 1 ? 'Went down once and got back up.' : `Went down ${d.downs} times and got back up every time.`)
  if (d.revives) lines.push(`Got ${plural(d.revives, 'friend')} back on their feet.`)
  const th = d.thrown || {}
  const thrown = (th.molotov || 0) + (th.pipebomb || 0) + (th.noisemaker || 0)
  if (thrown) lines.push(`Has thrown ${[th.molotov ? plural(th.molotov, 'molotov') : null, th.pipebomb ? plural(th.pipebomb, 'pipe bomb') : null, th.noisemaker ? plural(th.noisemaker, 'noise maker') : null].filter(Boolean).join(', ')}.`)
  const job = favourite(d.work)
  if (job && job[1] >= 3) lines.push(`Has spent ${job[1]} days at the ${STATIONS[job[0]]?.name || job[0]}.`)
  if (d.built || d.crafted) lines.push([d.built ? `Helped raise ${plural(d.built, 'building')}` : null, d.crafted ? `${d.built ? 'made' : 'Made'} ${d.crafted} things at the benches` : null].filter(Boolean).join(' and ') + '.')
  if (lines.length === 1) lines.push('Their story is still being written.')
  return lines
}
