// Relations between peoples: first contact, friendship and rivalry, trade
// pacts, alliances, tribute, raids, wars, battles, peace treaties and
// conquest.
//
// Opinion between two tribes drifts every day with the things that drive it
// in real history: borders pressing together, competition for the same ore
// and fishing grounds, hunger next to plenty, shared enemies, trade,
// marriages across the line, old wounds, and the temper of their leaders.
// Leaders (AI minds through the "diplomacy" action, or instinct) propose
// pacts, declare war and sue for peace. Wars are fought by real people:
// warriors muster at their hub, march on the enemy, and fight battles whose
// outcome depends on numbers, health, weapons (spears to rifles to drones),
// fortifications and morale. Raids carry off stores and burn buildings;
// decisive victories bring tribute or swallow the losing people whole.

import { BUILDINGS } from './buildings.ts'
import { ITEMS, invAdd, invCount, type Item } from './data.ts'
import { DAY, HOUR, YEAR } from './time.ts'
import type { Agent, Tribe } from './types.ts'
import { eraOf } from './tech.ts'
import type { World } from './world.ts'

export type PactKind = 'alliance' | 'trade' | 'tribute'
export interface Pact { a: number; b: number; kind: PactKind; since: number; until: number; payer?: number }
export interface War {
  id: number
  a: number // the side that declared
  b: number
  since: number
  reason: string
  goal: 'raid' | 'conquest' | 'revenge'
  score: number // + favours a, − favours b
  battles: number
  dead: Record<number, number>
  parent?: number // a war joined to honour an alliance
  ended?: number
}
export interface Campaign {
  id: number
  war: number
  side: number
  enemy: number
  kind: 'raid' | 'assault' | 'defend'
  units: number[]
  tx: number
  ty: number
  phase: 'muster' | 'march' | 'battle' | 'return'
  started: number
  att0?: number
  def0?: number
  hours?: number
  vs?: number // the opposing campaign in a battle
}
export interface Diplomacy {
  contact: Record<string, number>
  grievance: Record<string, number>
  pacts: Pact[]
  wars: War[]
  campaigns: Campaign[]
  nextId: number
}

export function newDiplomacy(): Diplomacy {
  return { contact: {}, grievance: {}, pacts: [], wars: [], campaigns: [], nextId: 1 }
}

export const key = (a: number, b: number) => (a < b ? `${a}|${b}` : `${b}|${a}`)
const clamp = (v: number, a: number, b: number) => (v < a ? a : v > b ? b : v)

// ---------------------------------------------------------------- queries

export function met(w: World, a: number, b: number): boolean {
  return a !== b && w.dip.contact[key(a, b)] !== undefined
}
export function warBetween(w: World, a: number, b: number): War | undefined {
  return w.dip.wars.find((x) => !x.ended && ((x.a === a && x.b === b) || (x.a === b && x.b === a)))
}
export function pactBetween(w: World, a: number, b: number, kind: PactKind): Pact | undefined {
  return w.dip.pacts.find((p) => p.kind === kind && ((p.a === a && p.b === b) || (p.a === b && p.b === a)))
}
export function allies(w: World, t: number): number[] {
  return w.dip.pacts.filter((p) => p.kind === 'alliance' && (p.a === t || p.b === t)).map((p) => (p.a === t ? p.b : p.a))
}
export function enemies(w: World, t: number): number[] {
  return w.dip.wars.filter((x) => !x.ended && (x.a === t || x.b === t)).map((x) => (x.a === t ? x.b : x.a))
}

export type Status = 'war' | 'alliance' | 'trade' | 'tribute' | 'friendly' | 'neutral' | 'tense' | 'hostile' | 'unknown'
export function status(w: World, a: number, b: number): Status {
  if (!met(w, a, b)) return 'unknown'
  if (warBetween(w, a, b)) return 'war'
  if (pactBetween(w, a, b, 'alliance')) return 'alliance'
  if (pactBetween(w, a, b, 'tribute')) return 'tribute'
  if (pactBetween(w, a, b, 'trade')) return 'trade'
  const r = w.tribeRelation(a, b)
  return r > 35 ? 'friendly' : r > -15 ? 'neutral' : r > -45 ? 'tense' : 'hostile'
}

/** Weapons by knowledge: clubs → spears → bows → bronze → iron → steel → guns → machines. */
export function militaryTier(known: Set<string>): { mult: number; weapon: 'club' | 'spear' | 'bow' | 'sword' | 'rifle'; name: string } {
  if (known.has('artificial_intelligence')) return { mult: 22, weapon: 'rifle', name: 'drones and smart weapons' }
  if (known.has('computing')) return { mult: 16, weapon: 'rifle', name: 'guided weapons' }
  if (known.has('flight')) return { mult: 12, weapon: 'rifle', name: 'aircraft and armour' }
  if (known.has('combustion')) return { mult: 9, weapon: 'rifle', name: 'machine guns and armoured trucks' }
  if (known.has('industrialization')) return { mult: 6.5, weapon: 'rifle', name: 'rifles and artillery' }
  if (known.has('gunpowder')) return { mult: 4.5, weapon: 'rifle', name: 'muskets and cannon' }
  if (known.has('steel')) return { mult: 3.2 * (known.has('horseback_riding') ? 1.15 : 1), weapon: 'sword', name: 'steel swords and armour' }
  if (known.has('iron_working')) return { mult: 2.6 * (known.has('horseback_riding') ? 1.15 : 1), weapon: 'sword', name: 'iron swords' }
  if (known.has('bronze_working')) return { mult: 2 * (known.has('horseback_riding') ? 1.15 : 1), weapon: 'sword', name: 'bronze blades and shields' }
  if (known.has('bow_and_arrow')) return { mult: 1.6, weapon: 'bow', name: 'bows' }
  if (known.has('hunting')) return { mult: 1.3, weapon: 'spear', name: 'spears' }
  return { mult: 1, weapon: 'club', name: 'clubs and stones' }
}

/** Who can fight: healthy adults of fighting age. */
export function fighters(w: World, t: number): Agent[] {
  return w.members(t).filter((a) => { const age = w.age(a); return age >= 15 && age <= 55 && a.health > 45 && !a.sick })
}

/** A people's fighting power in the field (before fortifications). */
export function strength(w: World, t: number): number {
  const known = w.techs(t)
  const barracks = w.tribeBuildings(t, 'barracks').length ? 1.25 : 1
  return fighters(w, t).reduce((s, a) => s + (0.6 + a.traits.strength * 0.6) * (a.health / 100), 0) * militaryTier(known).mult * barracks
}

/** Walls and watchtowers multiply the strength of defenders at home. */
export function fortification(w: World, t: number): number {
  let m = 1.15 // fighting on home ground
  const b = w.tribeBuildings(t)
  if (b.some((x) => x.type === 'walls')) m += w.hasTech(t, 'engineering') ? 0.7 : 0.45
  m += Math.min(0.4, b.filter((x) => x.type === 'watchtower').length * 0.15)
  return m
}

// ---------------------------------------------------------------- the daily round

export function diplomacyDaily(w: World) {
  const live = w.tribes.filter((t) => t.alive)
  // first contact: overlapping exploration or people meeting
  for (const a of live) for (const b of live) {
    if (a.id >= b.id || met(w, a.id, b.id)) continue
    if (touching(w, a, b)) firstContact(w, a, b)
  }
  for (const a of live) for (const b of live) {
    if (a.id >= b.id || !met(w, a.id, b.id)) continue
    drift(w, a, b)
    incident(w, a, b)
  }
  pactsDaily(w, live)
  for (const t of live) leaderDecides(w, t)
  warsDaily(w)
}

function touching(w: World, a: Tribe, b: Tribe): boolean {
  const ea = w.explored.get(a.id), eb = w.explored.get(b.id)
  if (ea && eb) {
    // sample: shared explored ground
    for (let i = 0; i < ea.length; i += 37) if (ea[i] && eb[i]) return true
  }
  const ma = w.members(a.id), mb = w.members(b.id)
  for (const x of ma) for (const y of mb) if (Math.abs(x.x - y.x) + Math.abs(x.y - y.y) < 8) return true
  return false
}

function firstContact(w: World, a: Tribe, b: Tribe) {
  w.dip.contact[key(a.id, b.id)] = w.time
  // first impressions: alike peoples warm to each other; temperament of the leaders colours it
  const la = w.agentById.get(a.leaderId), lb = w.agentById.get(b.leaderId)
  const warmth = ((la?.traits.agreeableness ?? 0.5) + (lb?.traits.agreeableness ?? 0.5) - 1) * 30
  const kin = similarity(a, b) * 25
  const start = Math.round(warmth + kin + (w.rng.next() - 0.5) * 20)
  a.relations[b.id] = start
  b.relations[a.id] = start
  w.log('tribe', `First contact: the ${a.name} ${a.emblem} meet the ${b.name} ${b.emblem}. ${start > 15 ? 'They greet each other warmly.' : start < -15 ? 'Both sides keep their spears close.' : 'Wary curiosity on both sides.'}`, '🤝', undefined, a.id)
  for (const m of [...w.members(a.id), ...w.members(b.id)]) w.remember(m, `We have met another people: the ${m.tribeId === a.id ? b.name : a.name}.`, 6)
}

/** How alike two cultures are: shared words in their values and beliefs. */
function similarity(a: Tribe, b: Tribe): number {
  const words = (s: string) => new Set(s.toLowerCase().split(/[^a-z]+/).filter((x) => x.length > 4))
  const wa = words(`${a.values} ${a.beliefs}`), wb = words(`${b.values} ${b.beliefs}`)
  if (!wa.size || !wb.size) return 0
  let n = 0
  for (const x of wa) if (wb.has(x)) n++
  return Math.min(1, n / 3)
}

export function food(w: World, t: Tribe): number {
  let f = 0
  for (const k in t.storage) if (ITEMS[k as Item]?.food) f += t.storage[k as Item] ?? 0
  return f / Math.max(1, w.members(t.id).length)
}

/**
 * Border incidents: neighbours who live close, and closer as the land fills,
 * rub against each other — stolen herds, a hunter killed, a disputed spring.
 * Each leaves a grudge; allies and trading partners usually settle them.
 */
function incident(w: World, a: Tribe, b: Tribe) {
  if (warBetween(w, a.id, b.id)) return
  const dist = Math.hypot(a.center.x - b.center.x, a.center.y - b.center.y)
  // horses, ships and rails carry quarrels further
  const reach = 40 + Math.max(eraOf(w.techs(a.id)), eraOf(w.techs(b.id))) * 12
  if (dist > reach) return
  const full = w.living.length / Math.max(1, w.cfg.popCap)
  const p = 0.004 + (reach - dist) / reach * 0.008 + Math.max(0, full - 0.7) * 0.02
  if (!w.rng.chance(p)) return
  const friends = pactBetween(w, a.id, b.id, 'alliance') ? 0.75 : pactBetween(w, a.id, b.id, 'trade') ? 0.4 : 0
  const [x, y] = w.rng.chance(0.5) ? [a, b] : [b, a] // x wrongs y
  if (w.rng.chance(friends)) {
    w.log('tribe', `A quarrel between ${x.name} and ${y.name} herders is settled by their elders before blood is spilled.`, '🤝', undefined, y.id)
    w.shiftTribeRelation(a.id, b.id, -1)
    return
  }
  const what = w.rng.pick([
    `${x.name} herders drive off cattle belonging to the ${y.name}`,
    `a ${y.name} hunter is found dead in the hills, and the ${x.name} are blamed`,
    `the ${x.name} dam a stream the ${y.name} drink from`,
    `${x.name} woodcutters fell a grove the ${y.name} hold sacred`,
    `a ${x.name} raiding party steals grain from a ${y.name} farm`,
    `${x.name} and ${y.name} fishermen come to blows over a fishing ground`,
    `the ${x.name} build on land the ${y.name} call their own`,
  ])
  const k = key(a.id, b.id)
  w.dip.grievance[k] = (w.dip.grievance[k] ?? 0) + 10
  w.shiftTribeRelation(a.id, b.id, -5)
  w.log('tribe', `Border trouble: ${what}.`, '😠', undefined, y.id)
  for (const m of w.members(y.id)) if (w.rng.chance(0.4)) w.remember(m, `Border trouble: ${what}.`, 5)
}

/** Daily drift of opinion between two peoples. */
function drift(w: World, a: Tribe, b: Tribe) {
  let d = 0
  const reasons: string[] = []
  // borders pressing together
  const dist = Math.hypot(a.center.x - b.center.x, a.center.y - b.center.y)
  if (dist < 20) { d -= (20 - dist) / 25; reasons.push('borders') }
  // settlements spreading into each other: the more they crowd, the more they quarrel
  const ba = w.tribeBuildings(a.id).filter((x) => x.type !== 'road'), bb = w.tribeBuildings(b.id).filter((x) => x.type !== 'road')
  let crowd = 0
  for (let i = 0; i < ba.length && crowd < 30; i += 2) for (const y of bb) if (Math.abs(ba[i].x - y.x) + Math.abs(ba[i].y - y.y) < 9) { crowd++; break }
  if (crowd) { d -= Math.min(1.1, crowd * 0.08); reasons.push('land') }
  // as the world fills up, every neighbour is a rival for land
  const full = w.living.length / Math.max(1, w.cfg.popCap)
  if (full > 0.75 && crowd) d -= (full - 0.75) * 4
  // strangers in custom and faith drift apart
  if (a.beliefs && b.beliefs && similarity(a, b) === 0) d -= 0.12
  // the same ore and fishing grounds
  const ka = w.knownList(a.id), kbSet = w.knownDeposits(b.id)
  let shared = 0
  for (let k = 0; k < ka.length && shared < 20; k += 5) if (kbSet[ka[k]]) shared++
  if (shared > 5) { d -= 0.3; reasons.push('ore') }
  // hunger next to plenty breeds envy
  const fa = food(w, a), fb = food(w, b)
  if (Math.min(fa, fb) < 2 && Math.max(fa, fb) > 10) d -= 0.4
  // shared enemies make friends
  const ea = enemies(w, a.id), eb = enemies(w, b.id)
  if (ea.some((x) => eb.includes(x))) d += 0.6
  // trade, alliance and tribute
  if (pactBetween(w, a.id, b.id, 'trade')) d += 0.4
  if (pactBetween(w, a.id, b.id, 'alliance')) d += 0.3
  if (pactBetween(w, a.id, b.id, 'tribute')) d -= 0.25
  // marriages across the line
  const mixed = w.members(a.id).filter((m) => { const p = m.partnerId ? w.agentById.get(m.partnerId) : null; return p && p.alive && p.tribeId === b.id }).length
  d += mixed * 0.3
  // kindred cultures
  d += similarity(a, b) * 0.2
  // old wounds heal, slowly
  const g = w.dip.grievance[key(a.id, b.id)] ?? 0
  if (g > 0) { d -= Math.min(0.6, g / 60); w.dip.grievance[key(a.id, b.id)] = g * 0.97 }
  // at war, hatred deepens
  if (warBetween(w, a.id, b.id)) d -= 0.5
  // temperaments of the leaders
  const la = w.agentById.get(a.leaderId), lb = w.agentById.get(b.leaderId)
  const temper = ((la?.traits.agreeableness ?? 0.5) + (lb?.traits.agreeableness ?? 0.5)) / 2
  d += d < 0 ? d * (0.5 - temper) * 0.6 : d * (temper - 0.5) * 0.3
  // a little day-to-day noise: incidents, kindnesses
  d += (w.rng.next() - 0.5) * 0.6
  for (const [x, y] of [[a, b], [b, a]] as const) {
    const r = x.relations[y.id] ?? 0
    // everything drifts slowly back toward indifference
    // opinions settle where their causes put them, rather than running away
    x.relations[y.id] = clamp(r + d - r * 0.03, -100, 100)
  }
}

// ---------------------------------------------------------------- pacts

function pactsDaily(w: World, live: Tribe[]) {
  const ids = new Set(live.map((t) => t.id))
  w.dip.pacts = w.dip.pacts.filter((p) => {
    if (!ids.has(p.a) || !ids.has(p.b)) return false
    if (p.until && w.time > p.until) {
      const a = w.tribeById(p.a)!, b = w.tribeById(p.b)!
      w.log('tribe', p.kind === 'tribute' ? `The ${w.tribeById(p.payer ?? p.a)?.name}'s years of tribute are over.` : `The ${p.kind} between the ${a.name} and the ${b.name} lapses.`, '📜', undefined, p.a)
      return false
    }
    // alliances and trade sour when opinion collapses
    if ((p.kind === 'alliance' || p.kind === 'trade') && w.tribeRelation(p.a, p.b) < -20) {
      w.log('tribe', `The ${w.tribeById(p.a)?.name} and the ${w.tribeById(p.b)?.name} break off their ${p.kind === 'alliance' ? 'alliance' : 'trade'}.`, '💔', undefined, p.a)
      return false
    }
    return true
  })
  for (const p of w.dip.pacts) {
    const a = w.tribeById(p.a)!, b = w.tribeById(p.b)!
    if (p.kind === 'trade' || p.kind === 'alliance') {
      // goods flow where they are short; ideas flow with them
      exchange(a, b, p.kind === 'trade' ? 4 : 2)
      exchange(b, a, p.kind === 'trade' ? 4 : 2)
      diffuse(w, a, b, p.kind === 'alliance' ? 0.04 : 0.025)
      diffuse(w, b, a, p.kind === 'alliance' ? 0.04 : 0.025)
    }
    if (p.kind === 'tribute' && p.payer !== undefined) {
      const payer = p.payer === a.id ? a : b, to = payer === a ? b : a
      let paid = 0
      const goods = Object.entries(payer.storage).filter(([, n]) => (n ?? 0) > 6).sort((x, y) => (y[1] ?? 0) - (x[1] ?? 0)).slice(0, 3)
      for (const [k, n] of goods) { const m = Math.ceil((n ?? 0) * 0.03); invAdd(payer.storage, k as Item, -m); invAdd(to.storage, k as Item, m); paid += m }
      if (paid && w.rng.chance(0.15)) w.log('tribe', `The ${payer.name} send tribute to the ${to.name}: ${paid} goods.`, '💰', undefined, payer.id)
    }
  }
}

/** Surplus goods move to a partner who lacks them. */
function exchange(from: Tribe, to: Tribe, n: number) {
  for (const k of Object.keys(from.storage) as Item[]) {
    if (n <= 0) break
    if (invCount(from.storage, k) > 40 && invCount(to.storage, k) < 10) {
      const m = Math.min(n, 6)
      invAdd(from.storage, k, -m)
      invAdd(to.storage, k, m)
      n -= m
    }
  }
}

/** Knowledge seeps across friendly borders. */
function diffuse(w: World, from: Tribe, to: Tribe, frac: number) {
  const known = w.techs(from.id), theirs = w.techs(to.id)
  for (const d of w.available(to)) {
    if (!known.has(d.id) || theirs.has(d.id)) continue
    to.insight[d.id] = (to.insight[d.id] ?? 0) + d.cost * frac
    if (w.techProgress(to, d.id) >= d.cost) { w.learnTech(to, d.id, w.agentById.get(to.leaderId) ?? w.members(to.id)[0], 'research'); break }
  }
}

// ---------------------------------------------------------------- proposals

export type Proposal = 'alliance' | 'trade' | 'peace' | 'war' | 'tribute' | 'gift' | 'break'

/** One people asks another for something. Returns whether it was accepted (and what happened). */
export function propose(w: World, from: number, to: number, kind: Proposal, by?: Agent, words = ''): { ok: boolean; text: string } {
  const A = w.tribeById(from), Bt = w.tribeById(to)
  if (!A || !Bt || !A.alive || !Bt.alive || from === to) return { ok: false, text: 'There is no such people.' }
  if (!met(w, from, to)) return { ok: false, text: `We have never met the ${Bt.name}.` }
  const r = w.tribeRelation(to, from)
  const s = strength(w, from) / Math.max(1, strength(w, to))
  const quote = words ? ` “${words.slice(0, 120)}”` : ''
  const envoy = by ? `${by.name} of the ${A.name}` : `The ${A.name}`
  const common = enemies(w, from).some((e) => enemies(w, to).includes(e))
  switch (kind) {
    case 'war': {
      if (warBetween(w, from, to)) return { ok: false, text: `We are already at war with the ${Bt.name}.` }
      const goal = s > 2.2 && w.members(to).length < w.members(from).length * 0.6 ? 'conquest' : 'raid'
      declareWar(w, from, to, words || 'old grievances', goal)
      return { ok: true, text: `${envoy} declare war on the ${Bt.name}.` }
    }
    case 'peace': {
      const war = warBetween(w, from, to)
      if (!war) return { ok: false, text: `We are not at war with the ${Bt.name}.` }
      // the other side weighs how the war is going for them, and how tired they are
      const theirScore = war.a === to ? war.score : -war.score
      const tired = (war.dead[to] ?? 0) * 8 + (w.time - war.since) / DAY * 0.6
      const ok = theirScore < 25 || tired > 35 || w.rng.chance(0.1)
      if (ok) makePeace(w, war, `${envoy} asked for peace.${quote}`)
      else w.log('tribe', `${envoy} sue for peace, but the ${Bt.name} fight on.${quote}`, '🏳️', by?.id, from)
      return { ok, text: ok ? `Peace with the ${Bt.name}.` : `The ${Bt.name} refused peace.` }
    }
    case 'alliance': {
      if (pactBetween(w, from, to, 'alliance')) return { ok: false, text: 'We are already allies.' }
      if (warBetween(w, from, to)) return { ok: false, text: 'We are at war with them.' }
      const ok = r > 20 || (common && r > -5) || (r > 5 && w.rng.chance(0.3))
      if (ok) {
        w.dip.pacts.push({ a: from, b: to, kind: 'alliance', since: w.time, until: 0 })
        w.shiftTribeRelation(from, to, 10)
        w.log('tribe', `Alliance! The ${A.name} ${A.emblem} and the ${Bt.name} ${Bt.emblem} swear to stand together.${quote}`, '🤝', by?.id, from)
      } else w.log('tribe', `${envoy} offer an alliance; the ${Bt.name} decline.${quote}`, '✋', by?.id, from)
      return { ok, text: ok ? `We are now allied with the ${Bt.name}.` : `The ${Bt.name} declined an alliance.` }
    }
    case 'trade': {
      if (pactBetween(w, from, to, 'trade')) return { ok: false, text: 'We already trade with them.' }
      if (warBetween(w, from, to)) return { ok: false, text: 'We are at war with them.' }
      const ok = r > -12 || w.rng.chance(0.3)
      if (ok) {
        w.dip.pacts.push({ a: from, b: to, kind: 'trade', since: w.time, until: 0 })
        w.shiftTribeRelation(from, to, 6)
        w.log('tribe', `The ${A.name} and the ${Bt.name} agree to trade. Caravans begin to move between them.${quote}`, '🐫', by?.id, from)
      } else w.log('tribe', `${envoy} offer trade; the ${Bt.name} want nothing from them.${quote}`, '✋', by?.id, from)
      return { ok, text: ok ? `We now trade with the ${Bt.name}.` : `The ${Bt.name} refused to trade.` }
    }
    case 'tribute': {
      // a demand: pay us, or else
      const ok = s > 1.8 && w.techs(from).has('writing') && !pactBetween(w, from, to, 'tribute') && (r > -60 || w.rng.chance(0.5))
      if (ok) {
        w.dip.pacts.push({ a: from, b: to, kind: 'tribute', since: w.time, until: w.time + 2 * YEAR, payer: to })
        w.shiftTribeRelation(from, to, -12)
        w.log('tribe', `Cowed by their strength, the ${Bt.name} agree to pay tribute to the ${A.name}.${quote}`, '💰', by?.id, from)
      } else {
        w.shiftTribeRelation(from, to, -15)
        w.dip.grievance[key(from, to)] = (w.dip.grievance[key(from, to)] ?? 0) + 15
        w.log('tribe', `${envoy} demand tribute; the ${Bt.name} send the envoy home with nothing.${quote}`, '😤', by?.id, from)
      }
      return { ok, text: ok ? `The ${Bt.name} will pay us tribute.` : `The ${Bt.name} refused to pay tribute.` }
    }
    case 'gift': {
      const goods = Object.entries(A.storage).filter(([, n]) => (n ?? 0) > 10).sort((x, y) => (y[1] ?? 0) - (x[1] ?? 0)).slice(0, 2)
      let n = 0
      for (const [k, v] of goods) { const m = Math.ceil((v ?? 0) * 0.15); invAdd(A.storage, k as Item, -m); invAdd(Bt.storage, k as Item, m); n += m }
      w.shiftTribeRelation(from, to, Math.min(15, 3 + n / 4))
      w.log('tribe', `${envoy} bring gifts to the ${Bt.name}: ${n} goods.${quote}`, '🎁', by?.id, from)
      return { ok: true, text: `We sent gifts to the ${Bt.name}.` }
    }
    case 'break': {
      const p = pactBetween(w, from, to, 'alliance') ?? pactBetween(w, from, to, 'trade')
      if (!p) return { ok: false, text: 'We have no pact with them.' }
      w.dip.pacts = w.dip.pacts.filter((x) => x !== p)
      w.shiftTribeRelation(from, to, -20)
      w.log('tribe', `The ${A.name} break their ${p.kind} with the ${Bt.name}.${quote}`, '💔', by?.id, from)
      return { ok: true, text: `Our ${p.kind} with the ${Bt.name} is over.` }
    }
  }
}

// ---------------------------------------------------------------- war and peace

export function declareWar(w: World, a: number, b: number, reason: string, goal: War['goal'], parent?: number) {
  const A = w.tribeById(a)!, Bt = w.tribeById(b)!
  // treaties break
  w.dip.pacts = w.dip.pacts.filter((p) => !((p.a === a && p.b === b) || (p.a === b && p.b === a)))
  const war: War = { id: w.dip.nextId++, a, b, since: w.time, reason, goal, score: 0, battles: 0, dead: {}, parent }
  w.dip.wars.push(war)
  w.shiftTribeRelation(a, b, -25)
  w.log('tribe', parent
    ? `Honouring their alliance, the ${A.name} ${A.emblem} go to war against the ${Bt.name} ${Bt.emblem}.`
    : `WAR. The ${A.name} ${A.emblem} declare war on the ${Bt.name} ${Bt.emblem}${reason ? ` — ${reason}` : ''}.`, '⚔️', undefined, a)
  for (const m of [...w.members(a), ...w.members(b)]) { w.remember(m, `We are at war with the ${m.tribeId === a ? Bt.name : A.name}.`, 9); m.urgentThink = true }
  if (parent) return
  // allies are called to arms — the defender's first, then the aggressor's
  for (const ally of allies(w, b)) if (ally !== a && !warBetween(w, ally, a)) declareWar(w, ally, a, `to defend the ${Bt.name}`, 'revenge', war.id)
  for (const ally of allies(w, a)) if (ally !== b && !warBetween(w, ally, b) && w.tribeRelation(ally, b) < 0) declareWar(w, ally, b, `alongside the ${A.name}`, 'raid', war.id)
}

export function makePeace(w: World, war: War, why: string) {
  if (war.ended) return
  war.ended = w.time
  const A = w.tribeById(war.a), Bt = w.tribeById(war.b)
  // linked wars end with this one
  for (const x of w.dip.wars) if (!x.ended && (x.parent === war.id || war.parent === x.id)) x.ended = w.time
  // the campaigns go home
  for (const c of w.dip.campaigns) if (c.war === war.id) c.phase = 'return'
  if (!A || !Bt) return
  let terms = 'Neither side gains anything.'
  const loser = war.score >= 30 ? Bt : war.score <= -30 ? A : null
  const winner = loser === A ? Bt : loser === Bt ? A : null
  if (loser && winner) {
    const hard = Math.abs(war.score) >= 70
    w.dip.pacts.push({ a: winner.id, b: loser.id, kind: 'tribute', since: w.time, until: w.time + (hard ? 4 : 2) * YEAR, payer: loser.id })
    terms = `The ${loser.name} must pay the ${winner.name} tribute for ${hard ? 'four' : 'two'} years.`
  }
  w.shiftTribeRelation(war.a, war.b, 15)
  w.dip.grievance[key(war.a, war.b)] = (w.dip.grievance[key(war.a, war.b)] ?? 0) + 15 + ((war.dead[war.a] ?? 0) + (war.dead[war.b] ?? 0)) * 2
  w.log('tribe', `PEACE between the ${A.name} and the ${Bt.name} after ${Math.max(1, Math.round((w.time - war.since) / DAY))} days of war. ${why} ${terms}`, '🕊️', undefined, war.a)
  for (const m of [...w.members(war.a), ...w.members(war.b)]) w.remember(m, `The war between the ${A.name} and the ${Bt.name} is over. ${terms}`, 8)
}

/** A people broken in war is absorbed by its conqueror. */
function conquer(w: World, war: War, winner: Tribe, loser: Tribe) {
  w.log('tribe', `CONQUEST. The ${loser.name} ${loser.emblem} are defeated and absorbed into the ${winner.name} ${winner.emblem}. Their homes, their stores and their knowledge pass to the victors.`, '👑', undefined, winner.id)
  for (const b of w.buildings) if (b.tribeId === loser.id) b.tribeId = winner.id
  for (const k of Object.keys(loser.storage) as Item[]) invAdd(winner.storage, k, invCount(loser.storage, k))
  loser.storage = {}
  // the victors learn what the vanquished knew
  for (const id of loser.techs) if (!winner.techs.includes(id)) winner.insight[id] = (winner.insight[id] ?? 0) + 1e6
  for (const m of w.members(loser.id)) { w.joinTribe(m, winner); w.remember(m, `The ${loser.name} are no more. We live now under the ${winner.name}.`, 10) }
  // a known body or explored land is shared knowledge now
  const ka = w.knownDeposits(winner.id), kb = w.knownDeposits(loser.id)
  for (let i = 0; i < kb.length; i++) if (kb[i] && !ka[i]) { ka[i] = 1; w.knownList(winner.id).push(i) }
  loser.alive = false
  war.ended = w.time
  w.buildVersion++
  w.territoryDirty = true
}

function warsDaily(w: World) {
  for (const war of w.dip.wars) {
    if (war.ended) continue
    const A = w.tribeById(war.a), Bt = w.tribeById(war.b)
    if (!A?.alive || !Bt?.alive) { war.ended = w.time; continue }
    // conquest: a beaten, broken people is swallowed
    if (war.goal === 'conquest' && war.score >= 90 && fighters(w, Bt.id).length <= 3) { conquer(w, war, A, Bt); continue }
    if (war.score <= -90 && fighters(w, A.id).length <= 2 && w.members(A.id).length < w.members(Bt.id).length * 0.5) { conquer(w, war, Bt, A); continue }
    // weariness: the dead, the days, the empty stores
    const days = (w.time - war.since) / DAY
    for (const [me, them, sign] of [[A, Bt, 1], [Bt, A, -1]] as const) {
      const leader = w.agentById.get(me.leaderId)
      if (leader && w.aiLeads(leader) && w.rng.chance(0.7)) continue // an AI leader makes this call
      const myScore = war.score * sign
      const tired = (war.dead[me.id] ?? 0) * 8 + days * 0.8 + (food(w, me) < 2 ? 20 : 0)
      const want = myScore < -35 || tired > 40 + (1 - (leader?.traits.agreeableness ?? 0.5)) * 30 || (myScore > 50 && days > 12)
      if (want && (days > 4 || war.battles >= 2) && w.rng.chance(0.2)) { propose(w, me.id, them.id, 'peace', leader); break }
    }
    if (war.ended) continue
    // launch campaigns: the aggressor attacks more often; defenders counter-raid
    for (const [me, them, eager] of [[A, Bt, 0.3], [Bt, A, 0.12]] as const) {
      if (w.dip.campaigns.some((c) => c.side === me.id && c.war === war.id && c.kind !== 'defend')) continue
      if (!w.rng.chance(eager)) continue
      launch(w, war, me, them, war.goal === 'conquest' && me.id === war.a && war.score > 10 ? 'assault' : 'raid')
    }
  }
}

function launch(w: World, war: War, me: Tribe, them: Tribe, kind: 'raid' | 'assault') {
  const pool = fighters(w, me.id).filter((a) => !w.dip.campaigns.some((c) => c.units.includes(a.id)))
  if (pool.length < 2) return
  const n = Math.min(kind === 'assault' ? 16 : 10, Math.max(2, Math.ceil(pool.length * (kind === 'assault' ? 0.6 : 0.4))))
  pool.sort((x, y) => y.traits.strength + y.health / 100 - (x.traits.strength + x.health / 100))
  const units = pool.slice(0, n)
  const hub = w.tribeHub(them.id)
  const c: Campaign = { id: w.dip.nextId++, war: war.id, side: me.id, enemy: them.id, kind, units: units.map((u) => u.id), tx: hub.x, ty: hub.y, phase: 'muster', started: w.time }
  w.dip.campaigns.push(c)
  const tier = militaryTier(w.techs(me.id))
  w.log('tribe', `${units.length} warriors of the ${me.name} gather with ${tier.name} to ${kind === 'assault' ? 'storm the town' : 'raid the lands'} of the ${them.name}.`, '🛡️', units[0].id, me.id)
  for (const u of units) w.order(u, { action: 'war', target: them.name, amount: 0, text: kind })
}

/** Defenders rally at home when an enemy column draws near. */
function rallyDefence(w: World, c: Campaign): Campaign | null {
  const existing = w.dip.campaigns.find((d) => d.kind === 'defend' && d.side === c.enemy && d.vs === c.id)
  if (existing) return existing
  const hub = w.tribeHub(c.enemy)
  const pool = fighters(w, c.enemy).filter((a) => Math.hypot(a.x - hub.x, a.y - hub.y) < 30 && !w.dip.campaigns.some((x) => x.units.includes(a.id)))
  const d: Campaign = { id: w.dip.nextId++, war: c.war, side: c.enemy, enemy: c.side, kind: 'defend', units: pool.map((u) => u.id), tx: hub.x, ty: hub.y, phase: 'battle', started: w.time, vs: c.id }
  w.dip.campaigns.push(d)
  for (const u of pool) w.order(u, { action: 'war', target: w.tribeById(c.side)?.name ?? '', amount: 0, text: 'defend' })
  return d
}

const power = (w: World, ids: number[], tribe: number) => {
  const tier = militaryTier(w.techs(tribe)).mult * (w.tribeBuildings(tribe, 'barracks').length ? 1.25 : 1)
  let s = 0
  for (const id of ids) { const a = w.agentById.get(id); if (a?.alive) s += (0.6 + a.traits.strength * 0.6) * (a.health / 100) * tier }
  return s
}

/** Hourly: armies move and battles are fought. */
export function diplomacyHourly(w: World) {
  if (!w.dip.campaigns.length) return
  for (const c of w.dip.campaigns) {
    c.units = c.units.filter((id) => { const a = w.agentById.get(id); return a?.alive && a.tribeId === c.side && a.health > 30 })
    for (const id of c.units) {
      const a = w.agentById.get(id)!
      if (a.task?.action !== 'war' && a.hunger < 60 && a.thirst < 60 && a.energy > 20 && c.phase !== 'return') w.order(a, { action: 'war', target: w.tribeById(c.enemy)?.name ?? '', amount: 0, text: c.kind })
    }
    const war = w.dip.wars.find((x) => x.id === c.war)
    if (!war || war.ended) c.phase = 'return'
    const units = c.units.map((id) => w.agentById.get(id)!).filter(Boolean)
    if (!units.length) { c.phase = 'return'; continue }
    if (c.phase === 'muster') {
      const hub = w.tribeHub(c.side)
      const ready = units.filter((u) => Math.hypot(u.x - hub.x, u.y - hub.y) < 5).length
      if (ready >= units.length * 0.7 || w.time - c.started > 8 * HOUR) c.phase = 'march'
    } else if (c.phase === 'march') {
      const near = units.filter((u) => Math.hypot(u.x - c.tx, u.y - c.ty) < 7).length
      if (w.time - c.started > 3 * DAY && near < units.length * 0.3) {
        w.log('tribe', `The warriors of the ${w.tribeById(c.side)?.name} never reach the ${w.tribeById(c.enemy)?.name} and straggle home.`, '🏳️', units[0].id, c.side)
        c.phase = 'return'
        continue
      }
      if (near >= Math.max(1, units.length * 0.6)) {
        const def = rallyDefence(w, c)
        c.phase = 'battle'
        c.vs = def?.id
        c.att0 = power(w, c.units, c.side)
        c.def0 = def ? power(w, def.units, def.side) * fortification(w, c.enemy) : 0
        c.hours = 0
        const E = w.tribeById(c.enemy)
        w.log('tribe', `Battle at the home of the ${E?.name}: ${units.length} attackers of the ${w.tribeById(c.side)?.name} against ${def?.units.length ?? 0} defenders.`, '⚔️', units[0].id, c.side)
      }
    } else if (c.phase === 'battle' && c.kind !== 'defend') {
      fightHour(w, c)
    }
  }
  // campaigns that are over release their people
  for (const c of w.dip.campaigns) {
    if (c.phase !== 'return') continue
    for (const id of c.units) { const a = w.agentById.get(id); if (a?.alive && a.task?.action === 'war') { a.task = null; a.moving = false } }
  }
  w.dip.campaigns = w.dip.campaigns.filter((c) => c.phase !== 'return')
}

function fightHour(w: World, c: Campaign) {
  const war = w.dip.wars.find((x) => x.id === c.war)!
  const def = w.dip.campaigns.find((d) => d.id === c.vs)
  c.hours = (c.hours ?? 0) + 1
  const fort = fortification(w, c.enemy)
  const att = power(w, c.units, c.side)
  const dpow = def ? power(w, def.units, def.side) * fort : 0
  const hit = (ids: number[], tribe: number, ratio: number, foe: number) => {
    // each hour, losses scale with how badly outmatched a side is
    const p = clamp(0.12 * ratio, 0.02, 0.6)
    for (const id of [...ids]) {
      const a = w.agentById.get(id)
      if (!a?.alive || !w.rng.chance(p)) continue
      a.health -= 25 + w.rng.next() * 45
      a.injured = Math.max(a.injured, 2)
      w.emote(a, '🩸', 4000)
      if (a.health <= 0) {
        war.dead[tribe] = (war.dead[tribe] ?? 0) + 1
        w.kill(a, `wounds taken in battle against the ${w.tribeById(foe)?.name}`)
      } else if (a.health < 35) {
        // the badly wounded drag themselves home
        const camp = w.dip.campaigns.find((x) => x.units.includes(id))
        if (camp) camp.units = camp.units.filter((u) => u !== id)
        a.task = null
        w.remember(a, 'I was badly wounded in the battle and fell back.', 8)
      }
    }
  }
  hit(c.units, c.side, dpow / Math.max(0.1, att), c.enemy)
  if (def) hit(def.units, def.side, att / Math.max(0.1, dpow), c.side)
  const attNow = power(w, c.units, c.side), defNow = def ? power(w, def.units, def.side) * fort : 0
  const attBroken = attNow < (c.att0 ?? 1) * 0.35, defBroken = !def || defNow < (c.def0 ?? 1) * 0.35 || !def.units.length
  if (!attBroken && !defBroken && c.hours < 6) return
  const won = defBroken && !attBroken ? true : attBroken && !defBroken ? false : attNow > defNow
  const A = w.tribeById(c.side)!, E = w.tribeById(c.enemy)!
  const sign = war.a === c.side ? 1 : -1
  war.battles++
  if (won) {
    war.score = clamp(war.score + sign * (c.kind === 'assault' ? 30 : 20), -100, 100)
    // plunder and fire
    const loot = plunder(w, E, c)
    const burnt = burn(w, E)
    w.log('tribe', `VICTORY for the ${A.name}: the ${E.name} are beaten at their own hearths. ${loot ? `The raiders carry off ${loot}.` : ''}${burnt ? ` The ${burnt} goes up in flames.` : ''}`, '🔥', c.units[0], A.id)
    w.shiftTribeRelation(A.id, E.id, -10)
  } else {
    war.score = clamp(war.score - sign * 20, -100, 100)
    w.log('tribe', `DEFEAT for the ${A.name}: the ${E.name} drive the attackers off.`, '🛡️', def?.units[0], E.id)
  }
  for (const id of [...c.units, ...(def?.units ?? [])]) {
    const a = w.agentById.get(id)
    if (!a?.alive) continue
    const ours = a.tribeId === c.side
    const victory = ours ? won : !won
    w.remember(a, victory ? `We won the battle against the ${ours ? E.name : A.name}.` : `We lost the battle against the ${ours ? E.name : A.name}.`, 8)
    w.emote(a, victory ? '🙌' : '😞', 6000)
    a.mood = clamp(a.mood + (victory ? 10 : -12), 0, 100)
  }
  c.phase = 'return'
  if (def) def.phase = 'return'
}

function plunder(w: World, E: Tribe, c: Campaign): string {
  const units = c.units.map((id) => w.agentById.get(id)).filter((a): a is Agent => !!a?.alive)
  const cap = units.length * 8
  const got: string[] = []
  let taken = 0
  const goods = (Object.keys(E.storage) as Item[]).sort((a, b) => invCount(E.storage, b) - invCount(E.storage, a))
  for (const k of goods) {
    if (taken >= cap) break
    const m = Math.min(cap - taken, Math.ceil(invCount(E.storage, k) * 0.3))
    if (m <= 0) continue
    invAdd(E.storage, k, -m)
    taken += m
    // shared among the raiders, to be brought home
    for (let i = 0; i < m; i++) invAdd(units[i % units.length].inv, k, 1)
    if (got.length < 3) got.push(`${m} ${ITEMS[k].name.toLowerCase()}`)
  }
  return got.join(', ')
}

function burn(w: World, E: Tribe): string {
  const hub = w.tribeHub(E.id)
  const near = w.tribeBuildings(E.id).filter((b) => b.done && b.type !== 'road' && b.type !== 'farm' && Math.hypot(b.x - hub.x, b.y - hub.y) < 10)
  if (!near.length || !w.rng.chance(0.6)) return ''
  const b = near[Math.floor(w.rng.next() * near.length)]
  b.hp -= 45 + w.rng.next() * 50
  if (b.hp <= 0) { w.destroyBuilding(b, `was burnt to the ground by raiders`); return `${BUILDINGS[b.type].name.toLowerCase()} (destroyed)` }
  return BUILDINGS[b.type].name.toLowerCase()
}

// ---------------------------------------------------------------- leaders decide (instinct)

function leaderDecides(w: World, t: Tribe) {
  const leader = w.agentById.get(t.leaderId)
  if (!leader?.alive) return
  // an AI-minded leader mostly decides for themself through the "diplomacy" action
  if (w.aiLeads(leader) && w.rng.chance(0.8)) return
  const temperament = (1 - leader.traits.agreeableness) * 0.6 + leader.traits.extraversion * 0.15 + (food(w, t) < 2 ? 0.3 : 0)
  const mine = strength(w, t.id)
  for (const o of w.tribes) {
    if (!o.alive || o.id === t.id || !met(w, t.id, o.id)) continue
    const r = w.tribeRelation(t.id, o.id)
    const aggression = inTruce(w, t.id, o.id) ? 0 : Math.max(temperament, popularAnger(w, t.id, o.id))
    const s = mine / Math.max(1, strength(w, o.id))
    const war = warBetween(w, t.id, o.id)
    const common = enemies(w, t.id).some((e) => enemies(w, o.id).includes(e))
    if (war) continue
    // war: deep hatred, an edge in strength and a hard leader — or hunger beside a full granary
    const hungryRaid = food(w, t) < 1.5 && food(w, o) > 10 && r < 0 && s > 1 && fighters(w, t.id).length >= 4 && w.techs(t.id).has('hunting')
    const allied = !!pactBetween(w, t.id, o.id, 'alliance')
    const goal: War['goal'] = s > 2.2 && w.members(o.id).length < w.members(t.id).length * 0.6 ? 'conquest' : 'raid'
    // grudges: deep enough dislike, an edge in strength, a hard leader
    const eager = appetite(w, t.id)
    if (!allied && ((r < -30 && s > 1.2 && aggression > 0.45) || (r < -45 && s > 0.85 && aggression > 0.35) || hungryRaid) && w.rng.chance((hungryRaid ? 0.04 : 0.03) * eager)) {
      declareWar(w, t.id, o.id, hungryRaid ? 'driven by hunger to take what their neighbours have' : 'after years of bad blood', goal)
      return
    }
    // resources: they hold ore we need and cannot find
    const want = wantedOre(w, t.id, o.id)
    if (!allied && want && r < 10 && s > 1.3 && aggression > 0.35 && w.rng.chance(0.012 * eager)) {
      declareWar(w, t.id, o.id, `to seize their ${want}`, goal)
      return
    }
    // expansion: a strong, ambitious state and a weak neighbour it does not love
    if (!allied && s > 1.8 && r < 5 && aggression > 0.5 && w.techs(t.id).has('code_of_laws') && w.rng.chance(0.01 * eager)) {
      declareWar(w, t.id, o.id, 'to bring their lands under its rule', 'conquest')
      return
    }
    // tribute from the weak
    if (s > 2.5 && r < 0 && aggression > 0.5 && w.techs(t.id).has('writing') && w.rng.chance(0.02)) { propose(w, t.id, o.id, 'tribute', leader); return }
    if (r > 25 && !pactBetween(w, t.id, o.id, 'alliance') && w.rng.chance(0.05)) { propose(w, t.id, o.id, 'alliance', leader); return }
    if (common && r > 0 && !pactBetween(w, t.id, o.id, 'alliance') && w.rng.chance(0.15)) { propose(w, t.id, o.id, 'alliance', leader, 'Our enemies are the same.'); return }
    const canTrade = ['the_wheel', 'sailing', 'currency'].some((x) => w.techs(t.id).has(x))
    if (canTrade && r > -8 && !pactBetween(w, t.id, o.id, 'trade') && w.rng.chance(w.techs(t.id).has('currency') ? 0.06 : 0.025)) { propose(w, t.id, o.id, 'trade', leader); return }
    if (r < -20 && r > -50 && leader.traits.agreeableness > 0.6 && w.rng.chance(0.03)) { propose(w, t.id, o.id, 'gift', leader); return }
  }
}

/** War-weariness: a people that has fought recently has little appetite for more (1 = fresh, → 0 = exhausted). */
export function appetite(w: World, t: number): number {
  const recent = w.dip.wars.filter((x) => (x.a === t || x.b === t) && w.time - x.since < 8 * YEAR).length
  return 1 / (1 + recent * recent * 0.8)
}

/** A peace is kept for a while: no new war between the same peoples for two years. */
export function inTruce(w: World, a: number, b: number): boolean {
  return w.dip.wars.some((x) => x.ended && ((x.a === a && x.b === b) || (x.a === b && x.b === a)) && w.time - x.ended < 2 * YEAR)
}

/** How hard a people pushes its leader toward war with another, whatever the leader's own temper. */
export function popularAnger(w: World, me: number, them: number): number {
  const r = w.tribeRelation(me, them)
  const g = w.dip.grievance[key(me, them)] ?? 0
  // land hunger: in a full world, a close neighbour's fields look like the answer
  const A = w.tribeById(me), Bt = w.tribeById(them)
  let land = 0
  if (A && Bt && r < 15) {
    const reach = 40 + eraOf(w.techs(me)) * 12
    const full = w.living.length / Math.max(1, w.cfg.popCap)
    if (Math.hypot(A.center.x - Bt.center.x, A.center.y - Bt.center.y) < reach) land = Math.max(0, full - 0.82) * 3
  }
  return Math.min(0.75, Math.max(0, (-r - 35) / 55) + Math.max(0, (g - 25) / 90) + land)
}

/** An ore this people needs but only knows of near the other people. */
export function wantedOre(w: World, me: number, them: number): string | null {
  const known = w.techs(me)
  const need: Item[] = []
  if (known.has('bronze_working')) need.push('copper', 'tin')
  if (known.has('iron_working')) need.push('iron_ore')
  if (known.has('steam_power')) need.push('coal')
  if (known.has('oil_drilling')) need.push('oil')
  if (!need.length) return null
  const have = new Set<string>()
  for (const i of w.knownList(me)) have.add(ITEM_OF[w.deposit[i]] ?? '')
  for (const b of w.tribeBuildings(them)) {
    if (b.type !== 'mine' && b.type !== 'oil_well') continue
    const d = w.deposit[w.idx(b.x, b.y)]
    const item = ITEM_OF[d]
    if (item && need.includes(item as Item) && !have.has(item)) return `${item.replace('_', ' ')} ${b.type === 'oil_well' ? 'wells' : 'mines'}`
  }
  return null
}
const ITEM_OF: Record<number, string> = { 1: 'copper', 2: 'tin', 3: 'iron_ore', 4: 'coal', 5: 'gold', 6: 'oil', 7: 'bauxite', 8: 'uranium', 9: 'lithium' }

// ---------------------------------------------------------------- for minds and panels

export function describeRelations(w: World, t: number): string[] {
  const out: string[] = []
  const mine = strength(w, t)
  for (const o of w.tribes) {
    if (!o.alive || o.id === t || !met(w, t, o.id)) continue
    const st = status(w, t, o.id)
    const r = Math.round(w.tribeRelation(t, o.id))
    const s = mine / Math.max(1, strength(w, o.id))
    const war = warBetween(w, t, o.id)
    const p = pactBetween(w, t, o.id, 'tribute')
    const bits = [
      `${o.name} ${o.emblem}: ${st.toUpperCase()} (opinion ${r > 0 ? '+' : ''}${r})`,
      `${w.members(o.id).length} people, ${s > 1.5 ? 'much weaker than us' : s > 1.1 ? 'weaker than us' : s > 0.9 ? 'about as strong as us' : s > 0.6 ? 'stronger than us' : 'far stronger than us'} (they fight with ${militaryTier(w.techs(o.id)).name})`,
    ]
    if (war) bits.push(`war since day ${Math.floor(war.since / DAY)}, ${war.battles} battles, the war is ${((war.a === t ? 1 : -1) * war.score) > 20 ? 'going our way' : ((war.a === t ? 1 : -1) * war.score) < -20 ? 'going badly for us' : 'undecided'}; our dead ${war.dead[t] ?? 0}, theirs ${war.dead[o.id] ?? 0}`)
    if (p) bits.push(p.payer === t ? 'we pay them tribute' : 'they pay us tribute')
    if ((w.dip.grievance[key(t, o.id)] ?? 0) > 10) bits.push('old grievances between us')
    out.push(bits.join('; '))
  }
  return out
}
