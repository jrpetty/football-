// What's out there. Every place in the city has a number of infected that
// drifts a little day by day, and a great roaming horde wanders the city,
// mostly round the bigger, richer places downtown, now and then into the
// quiet streets. Places near the horde are crawling, far more than usual.
// Nobody knows where it is unless somebody looks: a scout sent out alone and
// quietly maps a place, counts what's inside and watches the streets, and
// comes home hours later with the numbers (and, if it was near, where the
// horde was last seen and which way it was heading). Scouting is slow and
// not without risk.
import { S, day, log, placeOf, survivorStats, killSurvivor, exposeInfection, addMoraleEvent, getS } from './state.js'
import { LOCATIONS, DAY_MIN, zombieMix, INFECTION } from './data.js'
import { genCity } from '../world/city.js'
import { bus, clamp, chance, rint, rand, weighted } from '../core/util.js'

// ---------------------------------------------------------------- the city
let city = null
let citySeed = null
export function setCity(c) {
  city = c
  citySeed = S?.seed
}
function theCity() {
  if (!city || citySeed !== S.seed) {
    city = genCity(S.seed)
    citySeed = S.seed
  }
  return city
}
export const locById = (id) => theCity().locs.find((l) => l.id === id) || null
const campPos = () => theCity().camp.gate || theCity().camp
// How far a place is from the gate, along the roads (roughly).
export const roadDist = (loc) => Math.hypot(loc.x - campPos().x, loc.z - campPos().z) * 1.3

// ---------------------------------------------------------------- infected counts
// A place's usual number: its size and level, the same every time.
export function basePop(loc) {
  const L = LOCATIONS[loc.type]
  const area = (L.size[0] * L.size[1]) / 300
  const b = Math.round(4 + loc.level * 3 + area * 0.6)
  // a little spread per place, fixed by its id
  let h = 7
  for (const ch of String(loc.id)) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return b + (h % (2 + loc.level))
}
// How many are inside today (before the horde).
export function popOf(loc) {
  const P = placeOf(loc.id)
  if (P.cleared) return 0
  if (P.pop == null) P.pop = basePop(loc)
  return P.pop
}
// Each morning the numbers shift: they drift back toward the usual, a few
// either way, and a place left half-cleared slowly fills up again.
export function driftPopulations() {
  for (const loc of theCity().locs) {
    const P = placeOf(loc.id)
    if (P.cleared) continue
    const base = basePop(loc)
    const cur = P.pop ?? base
    const d = cur < base * 0.85 ? rint(0, 2) : cur > base * 1.25 ? rint(-2, 1) : rint(-2, 2)
    P.pop = clamp(cur + d, 0, Math.round(base * 1.6))
  }
}
// After a run: whoever the squad left alive is the count now.
export function setPop(locId, n) {
  placeOf(locId).pop = Math.max(0, n)
}

// ---------------------------------------------------------------- the horde
export const HORDE = { speed: 0.6, radius: 320, at: 90, minSize: 45, maxSize: 140 }
export function horde() {
  if (!S) return null
  if (!S.roamer) {
    // it starts somewhere downtown, well away from the camp
    const far = theCity().locs.filter((l) => l.level >= 3 && (l.dCamp ?? 1) > 0.45)
    const L = far.length ? far[Math.floor(Math.random() * far.length)] : theCity().locs[0]
    S.roamer = { x: L.x, z: L.z, size: rint(55, 75), goal: L.id, tx: L.x, tz: L.z, wait: rand(60, 240), heading: 0, seen: null }
    // where it heads once it stirs
    const N = nextGoal(S.roamer)
    Object.assign(S.roamer, { goal: N.id, tx: N.x + rand(-25, 25), tz: N.z + rand(-25, 25) })
  }
  return S.roamer
}
// Somewhere new to go: mostly the bigger, higher-level places, now and then
// anywhere; never right up to the camp.
function nextGoal(H) {
  const locs = theCity().locs.filter((l) => (l.dCamp ?? 1) > 0.18 && l.id !== H.goal)
  const any = chance(0.22)
  const list = locs.map((l) => {
    const d = Math.hypot(l.x - H.x, l.z - H.z)
    const w = (any ? 1 : Math.pow(l.level, 2.2)) / (1 + d / 700)
    return { l, w }
  })
  const pickd = weighted(list.map((o) => ({ ...o, w: o.w })))
  return pickd.l
}
// Move it along (dtMin: game minutes).
export function tickHorde(dtMin) {
  const H = horde()
  if (!H) return
  if (H.wait > 0) {
    H.wait -= dtMin
    return
  }
  const dx = H.tx - H.x
  const dz = H.tz - H.z
  const d = Math.hypot(dx, dz)
  const step = HORDE.speed * dtMin
  if (d <= step) {
    H.x = H.tx
    H.z = H.tz
    // it lingers a few hours, then moves on
    H.wait = rand(180, 720)
    const L = nextGoal(H)
    H.goal = L.id
    H.tx = L.x + rand(-25, 25)
    H.tz = L.z + rand(-25, 25)
  } else {
    H.x += (dx / d) * step
    H.z += (dz / d) * step
    H.heading = Math.atan2(dx, dz)
  }
}
// It grows as stragglers join it, and thins out in the cold.
export function hordeDaily() {
  const H = horde()
  if (!H) return
  H.size = clamp(H.size + rint(-1, 3), HORDE.minSize, HORDE.maxSize)
}
const hordeDist = (loc) => {
  const H = horde()
  return H ? Math.hypot(loc.x - H.x, loc.z - H.z) : Infinity
}
// How many extra the horde puts in a place right now.
export function hordeBonus(loc) {
  const H = horde()
  if (!H || placeOf(loc.id).cleared) return 0
  const d = hordeDist(loc)
  if (d < HORDE.at) return Math.round(H.size * 0.6)
  if (d < HORDE.radius) return Math.round(H.size * (0.15 + 0.4 * (1 - (d - HORDE.at) / (HORDE.radius - HORDE.at))))
  return 0
}
// What a run will find inside: today's count (more on every floor of a tall
// building) and whatever the horde has brought.
export function spawnCount(loc, floors = 1) {
  if (placeOf(loc.id).cleared) return 0
  return Math.min(80, Math.round(popOf(loc) * (1 + 0.55 * (floors - 1))) + hordeBonus(loc))
}

// ---------------------------------------------------------------- scouts
export const SCOUT = { watch: [240, 360], hunt: [480, 720], footSpeed: 1 / 3 }
// Who is fit to go: on their feet and in camp.
export const canScout = (s) => !!s && s.status === 'ok' && !(s.infection >= INFECTION.sick)
// How quiet they are: 1 is anyone, less is better.
export function stealthOf(s) {
  const st = survivorStats(s)
  let k = 1
  if (s.traits?.includes('quiet')) k *= 0.6
  if (s.traits?.includes('clumsy')) k *= 1.4
  if (s.occ === 'scout' || s.occ === 'hunter') k *= 0.65
  if (st.stealth) k *= 1 - Math.min(0.5, st.stealth)
  k *= 1 - Math.min(0.3, (s.skills.scavenge || 1) * 0.02)
  return clamp(k, 0.25, 1.6)
}
// Game minutes for a scouting trip there and back, watching on site.
export function scoutTime(s, target) {
  if (target === 'horde') return Math.round(rand(SCOUT.hunt[0], SCOUT.hunt[1]))
  const loc = locById(target)
  const each = (8 + roadDist(loc) / 55) / SCOUT.footSpeed
  const speed = 1 + (survivorStats(s).speed - 3) * 0.15
  return Math.round((each * 2) / Math.max(0.7, speed) + rand(SCOUT.watch[0], SCOUT.watch[1]))
}
// The chance something goes wrong out there.
export function scoutRisk(s, target) {
  let r
  if (target === 'horde') r = 0.22
  else {
    const loc = locById(target)
    r = 0.03 + loc.level * 0.035 + (hordeBonus(loc) > 0 ? 0.18 : 0)
  }
  return clamp(r * stealthOf(s), 0.02, 0.6)
}
export function sendScout(s, target) {
  if (!canScout(s)) return false
  S.scouts ||= []
  const t = scoutTime(s, target)
  const loc = target === 'horde' ? null : locById(target)
  S.scouts.push({ sid: s.id, target, left: S.time, back: S.time + t, risk: scoutRisk(s, target) })
  s.status = 'scout'
  s.job = null
  log(`${s.first} slipped out to ${loc ? `scout ${loc.name}` : 'look for the horde'}. Back in about ${Math.round(t / 60)} hours.`, 'story')
  bus.emit('change')
  return true
}
export const scoutOf = (s) => (S.scouts || []).find((x) => x.sid === s.id) || null
// The report: a place's count and kinds, its floor plan, and the horde if
// it was near (or found, on a hunt).
function report(sc, s) {
  const out = { day: day(), time: S.time, target: sc.target }
  const H = horde()
  if (sc.target === 'horde') {
    out.found = true
  } else {
    const loc = locById(sc.target)
    const n = spawnCount(loc, LOCATIONS[loc.type].tall ? 2 : 1)
    const mix = zombieMix(loc.level, LOCATIONS[loc.type].zombieTheme)
    const kinds = {}
    for (let i = 0; i < n; i++) {
      const t = weighted(mix).t
      kinds[t] = (kinds[t] || 0) + 1
    }
    out.count = n
    out.kinds = kinds
    out.horde = hordeBonus(loc) > 0
    const P = placeOf(loc.id)
    P.recon = { day: day(), time: S.time, count: n, kinds, horde: out.horde, by: s.first }
    P.mapped = true
    // from a rooftop they can see a long way: the horde too, if it's within a kilometre
    out.found = hordeDist(loc) < 1000
  }
  if (out.found && H) {
    H.seen = { x: H.x, z: H.z, time: S.time, size: H.size, heading: H.heading, moving: H.wait <= 0, by: s.first }
    out.seen = H.seen
  }
  return out
}
// Bring scouts home when their time is up, and roll what happened to them.
export function tickScouts() {
  if (!S.scouts?.length) return
  for (const sc of [...S.scouts]) {
    if (S.time < sc.back) continue
    S.scouts = S.scouts.filter((x) => x !== sc)
    const s = getS(sc.sid)
    if (!s) continue
    const rep = report(sc, s)
    const where = sc.target === 'horde' ? 'the hunt for the horde' : locById(sc.target)?.name || 'the city'
    let fate = 'fine'
    if (Math.random() < sc.risk) {
      const k = Math.random()
      if (k < 0.15 && (sc.target === 'horde' || (locById(sc.target)?.level || 1) >= 3)) fate = 'dead'
      else if (k < 0.45) fate = 'bitten'
      else fate = 'hurt'
    }
    rep.fate = fate
    if (fate === 'dead') {
      killSurvivor(s, `Never came back from scouting ${sc.target === 'horde' ? 'for the horde' : where}`)
      rep.text = `${s.first} never came back from ${where}.`
      if (rep.seen) rep.text += ' Before the radio went quiet, they called in where the horde was.'
      log(rep.text, 'bad')
    } else {
      s.status = 'ok'
      if (fate === 'hurt') {
        s.hp = Math.max(5, s.hp - survivorStats(s).maxHp * rand(0.3, 0.5))
        s.status = s.hp < survivorStats(s).maxHp * 0.3 ? 'injured' : 'ok'
      }
      let infected = false
      if (fate === 'bitten') {
        s.hp = Math.max(5, s.hp - survivorStats(s).maxHp * 0.25)
        // as on a run, a bite only infects someone already below 30% health
        infected = exposeInfection(s, 1, 0, s.hp / survivorStats(s).maxHp)
      }
      const what = sc.target === 'horde' ? (rep.seen ? `found the horde: about ${rep.seen.size} of them` : 'lost the horde\'s trail') : `counted ${rep.count} infected inside${rep.horde ? ', and the horde is close by' : ''}`
      rep.text = `${s.first} is back from ${where} and ${what}.${fate === 'hurt' ? ' They got spotted and took a beating getting away.' : fate === 'bitten' ? (infected ? ' They were bitten.' : ' They were bitten, but it did not break the skin.') : ''}`
      log(rep.text, fate === 'fine' ? 'good' : 'bad')
      if (fate !== 'fine') addMoraleEvent(`${s.first} hurt scouting`, -2, 1)
    }
    bus.emit('scoutBack', s, rep)
    bus.emit('change')
  }
}
// The economy calls this each tick with game minutes passed.
export function tickRecon(dtMin, newDay) {
  if (!S) return
  tickHorde(dtMin)
  tickScouts()
  if (newDay) {
    driftPopulations()
    hordeDaily()
  }
}
// Where the horde was last seen, and how long ago, for the map.
export function lastSeen() {
  return S?.roamer?.seen || null
}
// Is the camp's knowledge of a place fresh? (a day old or less)
export function reconOf(locId) {
  const r = S?.places?.[locId]?.recon
  if (!r) return null
  return { ...r, age: S.time - r.time, fresh: S.time - r.time < DAY_MIN }
}
