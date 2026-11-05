// The Genesis Mind: our own AI, built into the game. It needs no key and no
// network — it reads the same world a Claude mind reads (body, family, tribe,
// stores, knowledge, neighbours, wars) and decides like a person would: it
// weighs needs against ambitions through its personality, picks a project,
// works backwards through production chains into a multi-step plan, and puts
// its reasons into words. Leaders run statecraft (laws, values, beliefs,
// alliances, trade, war and peace), scholars write, elders teach. It can
// drive every person at once, or share the world with Claude, DeepSeek & co.

import type { World } from '../sim/world.ts'
import type { Agent, PlanStep, Tribe, Memory, Heard, Building } from '../sim/types.ts'
import { FOOD_ITEMS, invCount, invTotal, type Item } from '../sim/data.ts'
import { BUILDINGS, RECIPES, type BuildingType } from '../sim/buildings.ts'
import { TECH_BY_ID, RESEARCH_PRIORITY, eraOf, ERAS } from '../sim/tech.ts'
import { foodStep, gatherFor, resolve, instinctStep } from '../sim/instinct.ts'
import { enemies, met, warBetween, pactBetween, strength, fighters, food as foodPerHead, wantedOre, militaryTier, popularAnger, inTruce, appetite, type War } from '../sim/diplomacy.ts'
import { isNight, hourOf, DAY, YEAR, SEASONS } from '../sim/time.ts'
import { CARRY_CAP } from '../sim/world.ts'
import type { Decision, Reflection } from './prompts.ts'

export const NATIVE_ID = 'genesis-mind'

const rnd = Math.random
const pick = <T>(xs: readonly T[]): T => xs[Math.floor(rnd() * xs.length)]
const chance = (p: number) => rnd() < p
const S = (action: PlanStep['action'], target = '', amount = 0, text = ''): PlanStep => ({ action, target, amount, text })
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)
const bname = (b: BuildingType) => BUILDINGS[b].name.toLowerCase()
const itemName = (i: string) => i.replace(/_/g, ' ')
const listing = (xs: string[]) => xs.length <= 1 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`

// ------------------------------------------------------------------ who this person is

type Voice = 'warm' | 'blunt' | 'anxious' | 'dreamer' | 'steady'
function voiceOf(a: Agent): Voice {
  const t = a.traits
  if (t.neuroticism > 0.68) return 'anxious'
  if (t.openness > 0.72 && t.intelligence > 0.5) return 'dreamer'
  if (t.agreeableness > 0.64) return 'warm'
  if (t.agreeableness < 0.36) return 'blunt'
  return 'steady'
}

interface Sit {
  w: World
  a: Agent
  t: Tribe | undefined
  known: Set<string>
  era: number
  members: number
  food: number // in the tribe's stores and my pack
  perHead: number
  leader: boolean
  voice: Voice
  wars: War[]
  campaign: boolean
  threat: boolean
  partner?: Agent
  near: Agent[] // adults within earshot
  heard?: Heard // newest thing said to me since I last thought
  recent?: Memory // something important lately
  homeless: boolean
  site?: Building
  storeFull: boolean
}

function situation(w: World, a: Agent): Sit {
  const t = w.tribeById(a.tribeId)
  const known = w.techs(a.tribeId)
  let food = 0
  for (const f of FOOD_ITEMS) food += invCount(a.inv, f) + (t ? invCount(t.storage, f) : 0)
  const members = t ? w.members(t.id).length : 1
  const wars = t ? w.dip.wars.filter((x) => !x.ended && (x.a === t.id || x.b === t.id)) : []
  const near = w.living.filter((o) => o !== a && w.isAdult(o) && Math.abs(o.x - a.x) < 7 && Math.abs(o.y - a.y) < 7)
  const heard = a.heard.filter((h) => h.t > a.aiLastT && h.fromId !== a.id).pop()
  const recent = [...a.memories].reverse().find((m) => m.imp >= 6 && w.time - m.t < 1.5 * DAY && !/^I realised/.test(m.text))
  const housing = t ? w.tribeBuildings(t.id).reduce((s, b) => s + (BUILDINGS[b.type].housing ?? 0), 0) : 0
  return {
    w, a, t, known, era: eraOf(known), members, food, perHead: food / Math.max(1, members),
    leader: !!t && t.leaderId === a.id, voice: voiceOf(a), wars,
    campaign: w.dip.campaigns.some((c) => c.units.includes(a.id)),
    threat: !!t && (enemies(w, t.id).length > 0 || w.tribes.some((o) => o.alive && o.id !== t.id && met(w, t.id, o.id) && w.tribeRelation(t.id, o.id) < -35)),
    partner: a.partnerId ? w.agentById.get(a.partnerId) : undefined,
    near, heard, recent,
    homeless: !a.homeId && housing < members,
    site: t ? w.buildings.find((x) => x.tribeId === t.id && !x.done && x.type !== 'road') : undefined,
    storeFull: !!t && w.storageUsed(t) > w.storageCap(t.id) * 0.82,
  }
}

// ------------------------------------------------------------------ projects

interface Project {
  id: string
  score: number
  steps: PlanStep[]
  why: string // first-person reason, used in the thought
  tell?: string // something to say to whoever is near about it
}

/** Gather what a building needs (through whole production chains), then raise it. */
function buildPlan(s: Sit, type: BuildingType): PlanStep[] {
  const { w, a } = s
  if (s.site?.type === type) return [S('build', type), S('build', type)]
  const miss = w.missing(a, BUILDINGS[type].cost)
  const steps: PlanStep[] = []
  let load = invTotal(a.inv)
  for (const m of miss.slice(0, 3)) {
    const [n, item] = m.split(' ')
    const st = resolve(w, a, item as Item, +n, 0)
    if (!st) continue
    if (st.action === 'gather') { if (load + st.amount > CARRY_CAP) { steps.push(S('store')); load = 0 } load += st.amount }
    steps.push(st)
    if (st.action === 'build' && st.target !== type) return steps // a workshop first
  }
  steps.push(S('build', type))
  return steps
}

const costWords = (s: Sit, type: BuildingType) => {
  const miss = s.w.missing(s.a, BUILDINGS[type].cost).map((m) => itemName(m.split(' ')[1]))
  return miss.length ? `I'll fetch the ${listing(miss.slice(0, 3))} and ` : ''
}

function techFor(s: Sit): { id: string; why: string } | null {
  const { w, t, a } = s
  if (!t) return null
  const av = w.available(t)
  if (!av.length) return null
  const hungry = s.perHead < 8, sick = w.living.some((o) => o.tribeId === t.id && o.sick > 0.4)
  const need: Record<string, [number, string]> = {}
  const add = (ids: string[], v: number, why: string) => { for (const id of ids) if (!need[id] || need[id][0] < v) need[id] = [v, why] }
  if (hungry) add(['agriculture', 'fishing', 'hunting', 'animal_husbandry', 'irrigation', 'plough', 'food_preservation', 'crop_rotation', 'heavy_plough', 'fertilizer', 'tractors', 'haber_bosch', 'green_revolution', 'boats', 'canning', 'refrigeration'], 2.2, 'so no one goes hungry again')
  if (s.threat) add(['bow_and_arrow', 'bronze_working', 'iron_working', 'masonry', 'steel', 'gunpowder', 'horseback_riding', 'engineering'], 2, 'because our enemies will not wait')
  if (sick) add(['herbalism', 'medicine', 'sanitation', 'germ_theory', 'vaccination', 'antibiotics', 'mrna_vaccines', 'anaesthesia'], 2.1, 'because I am tired of burying the sick')
  if (w.season >= 2) add(['fire', 'clothing', 'shelter', 'weaving', 'tanning'], 1.6, 'before the cold bites')
  if (a.traits.openness > 0.6) add(['writing', 'mathematics', 'philosophy', 'astronomy', 'scientific_method', 'physics', 'chemistry', 'optics', 'printing_press', 'education', 'electricity', 'computing', 'artificial_intelligence', 'rocketry'], 1.5, 'because I want to understand how things are')
  if (a.traits.extraversion > 0.6) add(['the_wheel', 'sailing', 'currency', 'code_of_laws', 'banking', 'navigation', 'telegraph', 'telephone', 'radio', 'internet'], 1.4, 'so our people can reach the world')
  let best = av[0], bs = -1
  for (const d of av) {
    const prog = w.techProgress(t, d.id) / d.cost
    const sc = (need[d.id]?.[0] ?? 1) * (RESEARCH_PRIORITY[d.id] ?? 1) * (1 + prog * 3) / Math.sqrt(d.cost) * (0.75 + rnd() * 0.5)
    if (sc > bs) { bs = sc; best = d }
  }
  const prog = w.techProgress(t, best.id) / best.cost
  return { id: best.id, why: need[best.id]?.[1] ?? (prog > 0.4 ? 'we are close to it now' : pick(['every new idea opens the next', 'our children should know more than we do', 'it could change everything'])) }
}

function nextBuilding(s: Sit): BuildingType | null {
  const { w, t, known, members, a } = s
  if (!t) return null
  const can = (b: BuildingType) => !BUILDINGS[b].tech || known.has(BUILDINGS[b].tech!)
  const count = (b: BuildingType) => w.buildings.filter((x) => x.tribeId === t.id && x.type === b).length
  const T = a.traits
  // personality colours what a person thinks the people need most
  const list: [BuildingType, number, number][] = [ // type, how many, weight
    ['campfire', 1, 3], ['stockpile', 1 + Math.floor(members / 10), s.storeFull ? 3 : 1.2],
    ['farm', Math.ceil(members / 2.5), s.perHead < 12 ? 2.4 : 1.2], ['well', 1, 1.2], ['workshop', Math.ceil(members / 15), 1 + T.conscientiousness],
    ['kiln', 1, 1], ['granary', 1 + Math.floor(members / 14), s.storeFull ? 2.5 : 1], ['pasture', Math.ceil(members / 8), 1],
    ['house', Math.ceil(members / 4), s.homeless ? 2.6 : 0.9], ['smithy', 1, 1.1], ['library', Math.ceil(members / 15), 0.8 + T.openness * 1.2],
    ['temple', 1, 0.6 + T.agreeableness * 0.6 + T.neuroticism * 0.5], ['market', 1, 0.6 + T.extraversion], ['town_hall', 1, s.leader ? 1.6 : 0.8],
    ['mill', 1, 1], ['dock', 1, known.has('fishing') ? 1.2 : 0.5], ['watchtower', 1, s.threat ? 1.6 : 0.4], ['barracks', 1, s.threat ? 2.2 : 0],
    ['walls', 1, s.threat ? 2 : 0], ['university', 1, 0.6 + T.openness], ['hospital', 1, 0.6 + T.agreeableness], ['monument', 1, s.leader ? 0.3 + T.extraversion : 0.15],
    ['warehouse', 1 + Math.floor(members / 30), s.storeFull ? 2.4 : 0.8], ['factory', 1, 1], ['refinery', 1, 0.8], ['harbour', 1, 0.8], ['radio_tower', 1, 0.7],
    ['lab', 1, 0.6 + T.intelligence], ['apartment', Math.floor(members / 20), s.homeless ? 2 : 0.6], ['airport', 1, 0.6], ['fish_farm', 1, 0.6],
    ['data_center', 1, 0.6 + T.intelligence * 0.5], ['skyscraper', Math.floor(members / 40), 0.4], ['launch_pad', 1, 0.3 + T.openness * 0.6],
    [known.has('nuclear_power') ? 'nuclear_plant' : known.has('solar_power') ? 'solar_farm' : known.has('wind_power') ? 'wind_farm' : 'power_plant', 1, 1],
  ]
  let best: BuildingType | null = null, bs = 0
  for (const [b, want, wt] of list) {
    if (!BUILDINGS[b] || !can(b) || wt <= 0) continue
    const n = count(b)
    if (n >= want) continue
    const affordable = w.missing(a, BUILDINGS[b].cost).length === 0
    const sc = wt * (1 + (want - n) * 0.15) * (affordable ? 1.4 : 1) * (0.7 + rnd() * 0.6)
    if (sc > bs) { bs = sc; best = b }
  }
  return best
}

function exploreDir(s: Sit): string {
  const { w, a } = s
  const dirs: [string, number, number][] = [['north', 0, -1], ['south', 0, 1], ['east', 1, 0], ['west', -1, 0], ['northeast', 0.7, -0.7], ['northwest', -0.7, -0.7], ['southeast', 0.7, 0.7], ['southwest', -0.7, 0.7]]
  let best = 'north', bs = -1
  for (const [name, dx, dy] of dirs) {
    let sc = 0
    for (const r of [10, 18, 26, 34]) {
      const x = Math.round(a.x + dx * r), y = Math.round(a.y + dy * r)
      if (!w.inBounds(x, y)) break
      const i = w.idx(x, y)
      if (!w.walkableTile(i)) continue
      if (!w.isExplored(a.tribeId, i)) sc += 1
    }
    sc += rnd() * 0.8
    if (sc > bs) { bs = sc; best = name }
  }
  return best
}

const lastStatecraft = new Map<number, number>()
const lastDecree = new Map<number, number>()

/** A leader's turn at statecraft: war, peace, pacts, demands, gifts. */
function statecraft(s: Sit): Project | null {
  const { w, t, a } = s
  if (!t || !s.leader) return null
  if (w.time - (lastStatecraft.get(t.id) ?? -1e9) < 1.2 * DAY) return null
  const T = a.traits
  const aggression = (1 - T.agreeableness) * 0.6 + T.extraversion * 0.15 + T.neuroticism * 0.1 + (foodPerHead(w, t) < 2 ? 0.3 : 0)
  const mine = strength(w, t.id)
  const out = (kind: string, o: Tribe, words: string, why: string, tell: string, score: number): Project => {
    lastStatecraft.set(t.id, w.time)
    return { id: 'statecraft', score, steps: [S('diplomacy', o.name, 0, `${kind}: ${words}`)], why, tell }
  }
  // wars first: is it time to stop?
  for (const war of s.wars) {
    const o = w.tribeById(war.a === t.id ? war.b : war.a)
    if (!o) continue
    const my = war.a === t.id ? war.score : -war.score
    const days = (w.time - war.since) / DAY
    const tired = (war.dead[t.id] ?? 0) * 8 + days * 0.8 + (foodPerHead(w, t) < 2 ? 20 : 0)
    if ((my < -30 || tired > 34 + (1 - T.agreeableness) * 30 || (my > 50 && days > 10)) && (days > 3 || war.battles >= 2)) {
      const words = my > 30 ? `You have learned what we can do. Lay down your arms and we will leave you in peace.` : pick(['Enough of our people lie in the ground. Let us bury our dead and live as neighbours.', 'This war feeds no one. I ask for peace.', 'Our mothers weep on both sides of the river. Let it end.'])
      return out('peace', o, words, `the war with the ${o.name} has cost too much`, `I am sending envoys to the ${o.name} to ask for peace.`, 9)
    }
  }
  const options: Project[] = []
  for (const o of w.tribes) {
    if (!o.alive || o.id === t.id || !met(w, t.id, o.id) || warBetween(w, t.id, o.id)) continue
    const truce = inTruce(w, t.id, o.id)
    const r = w.tribeRelation(t.id, o.id)
    const ratio = mine / Math.max(1, strength(w, o.id))
    const allied = !!pactBetween(w, t.id, o.id, 'alliance')
    const common = enemies(w, t.id).some((e) => enemies(w, o.id).includes(e))
    const goal = ratio > 2.2 && w.members(o.id).length < s.members * 0.6
    const hungryRaid = foodPerHead(w, t) < 1.5 && foodPerHead(w, o) > 10 && r < 0 && ratio > 1 && fighters(w, t.id).length >= 4 && s.known.has('hunting')
    const ore = wantedOre(w, t.id, o.id)
    const close = Math.hypot(t.center.x - o.center.x, t.center.y - o.center.y) < 36 + s.era * 12 // reach grows with horses, ships and rails
    const full = w.living.length / Math.max(1, w.cfg.popCap)
    // war is weighed as a temptation, not a score: hard leaders give in to it more often
    // the people's anger can drive even a gentle leader to war
    const anger = popularAnger(w, t.id, o.id)
    // opportunity: a far stronger people next to a weak one it dislikes is tempted, whoever leads it
    const opportunity = ratio > 1.7 && r < -8 ? 0.42 + Math.min(0.2, (ratio - 1.7) * 0.15) : 0
    const temper = Math.max(0, Math.min(1.5, (Math.max(aggression, anger, opportunity) - 0.33) * 2.5))
    if (!allied && !truce && temper > 0 && fighters(w, t.id).length >= 3) {
      const motives: [number, string, string, string][] = []
      if (hungryRaid) motives.push([0.18, 'hunger leaves us no choice', `our children are hungry while the ${o.name} sit on full granaries`, `Ready the spears. We take what the ${o.name} will not share.`])
      if (anger > 0.45 && ratio > 0.85) motives.push([0.025 * anger, 'our people demand justice for what was done to them', `my people will not forgive the ${o.name}, and I cannot hold them back`, `Our people have suffered enough at the hands of the ${o.name}. We answer.`])
      if (r < -18) motives.push([(0.022 + (-r - 18) / 400) * Math.min(1.3, ratio), 'for every insult we have swallowed', `I am done swallowing the insults of the ${o.name}`, `The ${o.name} have mocked us long enough. We march.`])
      if (ore && r < 8 && ratio > 1.2) motives.push([0.035, `to take their ${ore}`, `we need the ${ore} the ${o.name} keep from us`, `The ${o.name} will not share their ${ore}. We will take them.`])
      if (close && ratio > 1.3 && r < 5) motives.push([0.02 * (ratio - 0.8), goal ? 'to bring their lands under our rule' : 'for the good land along our border', `the ${o.name} hold land that should be ours, and they are weaker than we are`, `The ${o.name} are weak and their land is good. We go to war.`])
      if (close && full > 0.85 && r < 15 && ratio > 0.85) motives.push([0.028, 'for land — there is no longer room for us both', `there is no more room, and the ${o.name} stand in our way`, `There is not land enough for us and the ${o.name}. Ready yourselves.`])
      const eager = appetite(w, t.id)
      for (const [p, words, why, tell] of motives) if (chance(p * temper * eager)) return out('war', o, words, why, tell, 9)
    }
    if (ratio > 2.4 && r < 0 && aggression > 0.5 && s.known.has('writing') && !pactBetween(w, t.id, o.id, 'tribute'))
      options.push(out('tribute', o, `You are weak and we are strong. Send us a share of your harvest and you will have nothing to fear.`, `the ${o.name} should pay for our protection`, `I have sent word to the ${o.name}: they will pay us tribute.`, 3 * rnd()))
    if (!allied && (r > 24 || (common && r > 0)))
      options.push(out('alliance', o, common ? 'Our enemies are the same. Let us stand together.' : 'You have been good neighbours. Let our peoples stand as one.', `the ${o.name} have proved themselves friends`, `I will offer the ${o.name} an alliance.`, (common ? 5 : 3.5) * (0.5 + T.agreeableness * 0.6)))
    const canTrade = ['the_wheel', 'sailing', 'currency'].some((x) => s.known.has(x))
    if (canTrade && r > (T.extraversion > 0.6 ? -5 : 4) && !pactBetween(w, t.id, o.id, 'trade') && w.dip.pacts.filter((p) => p.kind === 'trade' && (p.a === t.id || p.b === t.id)).length < 1 + Math.round(T.extraversion * 2))
      options.push(out('trade', o, `Let caravans move between us: our ${pick(['grain', 'tools', 'cloth', 'pots', 'timber'])} for what you have plenty of.`, `trade with the ${o.name} would make us both richer`, `We should trade with the ${o.name}. I'll send envoys.`, (2.5 + T.extraversion * 2) * (0.6 + rnd() * 0.4)))
    if (r < -12 && r > -50 && T.agreeableness > 0.52)
      options.push(out('gift', o, 'Take these as a sign that we want no quarrel with you.', `bad blood with the ${o.name} helps no one`, `Let us send gifts to the ${o.name} and mend this.`, 2 * T.agreeableness * (0.6 + rnd() * 0.6)))
  }
  options.sort((x, y) => y.score - x.score)
  if (!options.length) return null
  // only commit to the top option when it is weighty; otherwise wait a day
  const top = options[0]
  if (top.score < 1.6) { lastStatecraft.set(t.id, w.time - 0.6 * DAY); return null }
  return top
}

interface LawDef { key: RegExp; when: (s: Sit) => boolean; fit: (s: Sit) => number; text: (s: Sit) => string; why: string }
const LAWS: LawDef[] = [
  { key: /hoard|common store/i, when: (s) => s.perHead < 8 && s.members > 5, fit: (s) => 1 + s.a.traits.agreeableness, text: () => pick(['No one hoards food while others go hungry: every harvest goes to the common store.', 'Food belongs to all: whoever takes more than their share from the stores goes without for a day.']), why: 'hunger divides a people faster than any enemy' },
  { key: /trains?|arms|spear|bow|sword|rifle/i, when: (s) => s.threat, fit: (s) => 1.5 + (1 - s.a.traits.agreeableness), text: (s) => `Every able adult trains with the ${militaryTier(s.known).weapon}; the old and the young shelter by the hearth when enemies come.`, why: 'our enemies are close' },
  { key: /tree|forest/i, when: (s) => s.members > 12 && s.era >= 1, fit: (s) => 0.6 + s.a.traits.conscientiousness, text: () => pick(['For every tree we fell, we plant two.', 'The old forest is not to be cut; timber comes from the young woods only.']), why: 'the forests will not last forever' },
  { key: /letters|school|learn/i, when: (s) => s.known.has('writing'), fit: (s) => 0.5 + s.a.traits.openness * 1.5, text: () => pick(['Every child learns their letters and their numbers before they learn a trade.', 'Whoever can read must teach one who cannot.']), why: 'knowledge is what lifts us' },
  { key: /blood|kill/i, when: (s) => s.members > 8, fit: () => 0.8, text: () => pick(['Whoever spills the blood of their own people is cast out.', 'A life taken is paid for with a life of service to the dead one\'s family.']), why: 'we cannot live in fear of each other' },
  { key: /stranger|foreign/i, when: (s) => s.w.tribes.some((o) => o.alive && o.id !== s.a.tribeId && met(s.w, s.a.tribeId, o.id)), fit: () => 0.9, text: (s) => s.a.traits.agreeableness > 0.5 ? 'A stranger who comes in peace is given water, food and a place by the fire.' : 'No stranger crosses our land without leave, and none carries a weapon among us.', why: 'we must decide how we meet other peoples' },
  { key: /sick|fever|healer/i, when: (s) => s.w.living.some((o) => o.tribeId === s.a.tribeId && o.sick > 0.5), fit: () => 0.9, text: () => 'The sick are kept apart and tended by healers until the fever breaks.', why: 'fever spreads from hand to hand' },
  { key: /tenth|tithe/i, when: (s) => s.era >= 1, fit: (s) => 0.4 + s.a.traits.conscientiousness * 0.6 + (s.w.tribeBuildings(s.a.tribeId, 'temple').length ? 0.6 : 0), text: (s) => `A tenth of every harvest and every catch belongs to the ${s.w.tribeBuildings(s.a.tribeId, 'temple').length ? 'temple' : 'common stores'}.`, why: 'the common good needs a common purse' },
  { key: /word of the leader|defies/i, when: (s) => s.members > 10, fit: (s) => (1 - s.a.traits.agreeableness) * 1.6 + s.a.traits.extraversion * 0.4 - 0.4, text: () => 'The word of the leader is law; whoever defies it works the fields until they learn obedience.', why: 'a people pulling in every direction goes nowhere' },
  { key: /council/i, when: (s) => s.members > 10, fit: (s) => s.a.traits.agreeableness * 1.2 + s.a.traits.openness * 0.5 - 0.3, text: () => 'Before any great decision, every adult may speak at the council fire.', why: 'many minds see further than one' },
  { key: /herd|young or the mothers/i, when: (s) => s.known.has('hunting'), fit: (s) => 0.3 + s.a.traits.openness * 0.7, text: () => 'No one hunts the young, or the mothers of the herd in spring.', why: 'the herds must still be here for our grandchildren' },
  { key: /spawning|nets/i, when: (s) => s.known.has('fishing') && s.w.tribeBuildings(s.a.tribeId).some((b) => b.type === 'dock' || b.type === 'harbour'), fit: () => 0.6, text: () => 'No nets are cast in the spawning season.', why: 'an empty sea feeds no one' },
  { key: /partners are bound|belong to the whole/i, when: (s) => s.members > 8, fit: (s) => 0.3 + s.a.traits.conscientiousness * 0.6, text: () => pick(['Partners are bound for life, and their children belong to the whole people.', 'Any partner may leave a cruel partner, and take half of what they own.']), why: 'families are the bones of a people' },
  { key: /one day in four|common works/i, when: (s) => s.era >= 1 && s.members > 12, fit: (s) => s.a.traits.conscientiousness * 1.2, text: () => 'Every adult gives one day in four to the common works: roads, walls and granaries.', why: 'no one builds a road alone' },
  { key: /weights and measures/i, when: (s) => s.known.has('currency') || s.known.has('mathematics'), fit: (s) => 0.4 + s.a.traits.extraversion * 0.5, text: () => 'Weights and measures are fixed and the same for all, stranger and kin alike.', why: 'honest trade needs honest scales' },
  { key: /seventh day|day of rest/i, when: (s) => s.known.has('calendar'), fit: (s) => 0.4 + s.a.traits.agreeableness * 0.5, text: () => 'Every seventh day is a day of rest, for people and animals alike.', why: 'even the land rests in winter' },
  { key: /own hands|is theirs/i, when: (s) => s.era >= 1, fit: (s) => 0.3 + (1 - s.a.traits.agreeableness) * 0.6 + s.a.traits.extraversion * 0.3, text: () => 'What a person makes with their own hands is theirs to keep, trade or give.', why: 'people work harder for what is their own' },
]

const VALUES: [(s: Sit) => number, string][] = [
  [(s) => s.a.traits.agreeableness * 2, 'Share what we have, honour our elders, and leave no one out in the cold.'],
  [(s) => s.a.traits.agreeableness * 1.5 + s.a.traits.openness * 0.5, 'Kindness first; a people is judged by how it treats the weakest.'],
  [(s) => s.a.traits.openness * 2, 'Ask, try, learn: the world rewards those who look closely.'],
  [(s) => s.a.traits.openness * 1.2 + s.a.traits.extraversion, 'Go further than our parents did — over every hill, across every sea.'],
  [(s) => s.a.traits.conscientiousness * 2, 'Work hard, keep your word, and finish what you begin.'],
  [(s) => s.a.traits.conscientiousness * 1.4 + (1 - s.a.traits.agreeableness) * 0.6, 'Order, discipline and duty — the strong survive the winter.'],
  [(s) => s.a.traits.extraversion * 1.6 + (1 - s.a.traits.agreeableness) * 0.8, 'Be bold, be proud, and let the world know our name.'],
  [(s) => (1 - s.a.traits.agreeableness) * 1.8, 'Strength protects; weakness invites the wolf. We will never be weak.'],
  [(s) => 0.6 + s.a.traits.neuroticism, 'Stand by your kin, and never forget where we came from.'],
  [(s) => 0.5 + s.a.traits.neuroticism * 0.8 + s.a.traits.conscientiousness * 0.5, 'Waste nothing, fear the lean years, and always keep a store for winter.'],
]

function beliefFor(s: Sit): string {
  const { w, t, a } = s
  const hub = w.tribeHub(t!.id)
  const near = (pred: (i: number) => boolean) => w.nearestTile(hub.x, hub.y, 12, pred) >= 0
  const pool: [number, string][] = [
    [near((i) => w.biome[i] <= 2) ? 2 : 0, 'The sea gives and the sea takes; we thank it for every catch and fear it in every storm.'],
    [near((i) => w.biome[i] === 4) ? 1.8 : 0, 'The river is our mother: she brought us here, she feeds our fields, and one day she will carry us home.'],
    [near((i) => w.elev[i] > 0.62) ? 1.6 : 0, 'The mountains are the bones of the first ancestors; their spirits watch from the high places.'],
    [near((i) => w.biome[i] === 7 || w.biome[i] === 8) ? 1.4 : 0, 'Every tree has a spirit; we take wood only after asking leave.'],
    [w.season === 3 ? 1.2 : 0.5, 'The sun dies each winter and is reborn each spring, and so do we; the dead wait for us in the warm country.'],
    [0.6 + a.traits.openness * 0.6, 'Our ancestors watch over us from the stars; we must live so they are proud.'],
    [0.5 + a.traits.neuroticism * 0.8, 'Unseen powers punish the careless; we keep the old rites so that luck stays with us.'],
    [0.4 + a.traits.conscientiousness * 0.6, 'The first fire was a gift, and the hearth is sacred — it must never go out.'],
    [(1 - a.traits.agreeableness) * 1.2, 'The Sky Father favours the strong; victory is a sign of his love.'],
    [a.traits.openness > 0.7 ? 1 : 0.2, 'There are no gods — only the world, and what we make of it.'],
  ]
  return weighted(pool)
}

function weighted(pool: [number, string][]): string {
  const total = pool.reduce((n, [v]) => n + Math.max(0, v), 0)
  let r = rnd() * total
  for (const [v, x] of pool) { r -= Math.max(0, v); if (r <= 0) return x }
  return pool[0][1]
}

function decrees(s: Sit): Project | null {
  const { w, t } = s
  if (!t || !s.leader) return null
  if (w.time - (lastDecree.get(t.id) ?? -1e9) < 4 * DAY) return null
  const done = (p: Project) => { lastDecree.set(t.id, w.time); return p }
  if (!t.values) return done({ id: 'decree', score: 5, steps: [S('decree', 'values', 0, weighted(VALUES.map(([f, v]) => [f(s), v])))], why: 'a people needs to know what it stands for', tell: 'Listen, all of you — I have something to say about who we are.' })
  if (!t.beliefs && (s.known.has('mysticism') || w.tribeBuildings(t.id, 'temple').length || (w.time > YEAR && chance(0.25))))
    return done({ id: 'decree', score: 4, steps: [S('decree', 'beliefs', 0, beliefFor(s))], why: 'people need something to hold on to in the dark', tell: 'Gather close. I want to speak of the things we believe.' })
  if (t.laws.length < 6 && chance(0.5)) {
    const ok = LAWS.filter((L) => L.when(s) && !t.laws.some((l) => L.key.test(l)) && L.fit(s) > 0)
    if (ok.length) {
      const pickL = ok.map((x) => [x.fit(s) * (0.5 + rnd()), x] as const).sort((p, q) => q[0] - p[0])[0][1]
      return done({ id: 'decree', score: 4.2, steps: [S('decree', 'law', 0, pickL.text(s))], why: pickL.why, tell: 'Hear me — there will be a new law.' })
    }
  }
  if (!t.laws.length) return null
  lastDecree.set(t.id, w.time - 2 * DAY)
  return null
}

function writing(s: Sit): Project | null {
  const { w, t, a } = s
  if (!t || !s.known.has('writing')) return null
  if (a.traits.intelligence + a.traits.openness < 1.05) return null
  if (t.works.some((x) => x.author === a.name && w.time - x.t < 4 * DAY)) return null
  const mine = t.works.filter((x) => x.author === a.name).length
  const kinds: (() => { title: string; text: string; why: string })[] = [
    () => {
      const hist = t.history.slice(-6).map((h) => h.text.replace(/\s+/g, ' ')).join(' ')
      return { title: `The Story of the ${t.name}`, text: `We were few once, and we had nothing but our hands. ${hist || 'Every winter took someone, and every spring we began again.'} I write this so that those who come after will know that they were not the first to be afraid, nor the first to hope.`, why: 'our story should outlive the people who remember it' }
    },
    () => {
      const known = [...s.known].map((k) => TECH_BY_ID[k]).filter((d) => d && d.era === s.era)
      const d = known.length ? pick(known) : TECH_BY_ID['fire']
      return { title: `On ${d.name}`, text: `${d.desc} This is how we came to know it, and how it should be taught: begin with what the hands can feel, then explain what the eyes cannot see. Whoever reads this should try it for themselves, and improve on it.`, why: `what we know of ${d.name.toLowerCase()} should not die with me` }
    },
    () => {
      const season = SEASONS[w.season].toLowerCase()
      const line = pick(['the river keeps its own counsel', 'the hills hold their breath', 'the smoke climbs straight to the stars', 'the wind carries the smell of rain'])
      const love = s.partner ? `and ${s.partner.name} sleeps beside the fire` : 'and I walk alone along the water'
      return { title: pick([`A ${cap(season)} Song`, 'Hearth', 'What the River Said', 'For Those Who Come After']), text: `In ${season}, ${line},\n${love}.\nWe were dust, and we will be dust,\nbut tonight the pots are full and the children laugh.`, why: 'some things can only be said in a song' }
    },
    () => ({ title: `The Laws of the ${t.name}`, text: (t.laws.length ? t.laws : ['Share food.', 'Keep the fire.']).map((l, i) => `${i + 1}. ${l}`).join('\n') + `\nWritten down so that no one may say they did not know.`, why: 'a law that is not written is only a wish' }),
  ]
  const k = kinds[(mine + Math.floor(rnd() * 2)) % kinds.length]()
  return { id: 'write', score: 1.2 + a.traits.openness * 1.2 + (mine === 0 ? 0.8 : 0), steps: [S('write', k.title, 0, k.text)], why: k.why, tell: `I am going to write something down — ${k.title}.` }
}

function social(s: Sit): Project | null {
  const { w, a } = s
  const age = w.age(a)
  // courtship
  if (!a.partnerId && age >= 16 && age <= 48) {
    const cands = w.living.filter((o) => o !== a && !o.partnerId && w.isAdult(o) && o.sex !== a.sex && Math.abs(w.age(o) - age) < 15 && Math.hypot(o.x - a.x, o.y - a.y) < 40)
    cands.sort((p, q) => (a.relations[q.id]?.affinity ?? 0) - (a.relations[p.id]?.affinity ?? 0))
    const c = cands[0]
    if (c) {
      const rel = w.relation(a, c)
      if (rel.affinity > 28 && rel.familiarity > 20)
        return { id: 'court', score: 3 + a.traits.extraversion, steps: [S('court', c.name, 0, pick([`${c.name}, I think of you when I wake and before I sleep.`, `Walk with me tonight, ${c.name}? I'd like to build a life with you.`, `I've made up my mind, ${c.name}. I want it to be us.`]))], why: `I can't stop thinking about ${c.name}` }
      if (rel.affinity > 5 || chance(0.5)) return { id: 'befriend', score: 1.5 + a.traits.extraversion, steps: [S('talk', c.name, 0, pick([`${c.name}! Sit with me a while — tell me how your day went.`, `I saw what you did today, ${c.name}. You've good hands.`, `${c.name}, would you show me how you do that?`]))], why: `I'd like to know ${c.name} better` }
    }
  }
  // feed someone hungry nearby
  const hungry = s.near.find((o) => o.hunger > 60 && o.tribeId === a.tribeId)
  const mine = FOOD_ITEMS.find((f) => invCount(a.inv, f) >= 2)
  if (hungry && mine) return { id: 'give', score: 4 * (0.5 + a.traits.agreeableness), steps: [S('give', hungry.name, 3, mine)], why: `${hungry.name} looks half-starved`, tell: `Here, ${hungry.name}. Eat.` }
  // pass on a craft
  let bestSk: string | null = null
  const young = w.living.find((o) => o.tribeId === a.tribeId && w.isAdult(o) && w.age(o) < 24 && Math.hypot(o.x - a.x, o.y - a.y) < 25 && Object.entries(a.skills).some(([k, v]) => { if (v - (o.skills as Record<string, number>)[k] > 25) { bestSk = k; return true } return false }))
  if (young && bestSk && w.age(a) > 30) return { id: 'teach', score: 1.6 + a.traits.agreeableness + a.skills.social / 60, steps: [S('teach', young.name)], why: `${young.name} should learn ${bestSk} from someone who knows it`, tell: `${young.name}, come — I'll show you what my hands know.` }
  // just company
  if (a.social < 45) {
    const f = w.living.filter((o) => o !== a && w.isAdult(o) && Math.hypot(o.x - a.x, o.y - a.y) < 25).sort((p, q) => (a.relations[q.id]?.affinity ?? 0) - (a.relations[p.id]?.affinity ?? 0))[0]
    if (f) return { id: 'company', score: 1.2 + (1 - a.social / 100) * 2 * (0.5 + a.traits.extraversion), steps: [S('talk', f.name, 0, chat(s, f))], why: `I miss talking to ${f.name}` }
  }
  return null
}

function chooseProjects(s: Sit): Project[] {
  const { w, a, t, known, members } = s
  const T = a.traits
  const P: Project[] = []
  if (!t) return [{ id: 'forage', score: 1, steps: [S('gather', 'berries', 10)], why: 'I have to look after myself now' }]
  // the people must eat
  const famine = s.perHead < 4, lean = s.perHead < 10
  if (lean) {
    const steps = [foodStep(w, a), foodStep(w, a), S('store')]
    P.push({ id: 'food', score: (famine ? 8 : 4) * (0.7 + T.conscientiousness * 0.4 + T.agreeableness * 0.2), steps, why: famine ? 'the stores are nearly bare and the children are hungry' : 'the stores are thinner than I like', tell: famine ? 'The stores are almost empty. Every hand to finding food today!' : undefined })
  }
  if (s.site) P.push({ id: 'site', score: 3.2 + T.conscientiousness, steps: [S('build', s.site.type), S('build', s.site.type)], why: `the ${bname(s.site.type)} we started still isn't finished`, tell: `Help me finish the ${bname(s.site.type)}?` })
  if (s.homeless) {
    const home: BuildingType = known.has('masonry') || known.has('carpentry') ? 'house' : known.has('shelter') ? 'hut' : 'lean_to'
    const ok = !BUILDINGS[home].tech || known.has(BUILDINGS[home].tech!)
    if (ok) P.push({ id: 'home', score: 4.5 + (s.partner ? 1.5 : 0) + a.children.length * 0.6, steps: buildPlan(s, home), why: `${s.partner ? 'my family' : 'I'} need${s.partner ? 's' : ''} a roof — ${costWords(s, home)}raise a ${bname(home)}` })
  }
  // tools and clothes
  if (a.toolTier === 0 && known.has('stone_tools')) {
    const miss = w.missing(a, RECIPES.find((x) => x.id === 'stone_tools')!.inputs)
    P.push({ id: 'tools', score: 3.5, steps: miss.length ? [gatherFor(w, a, miss), S('craft', 'stone_tools')] : [S('craft', 'stone_tools')], why: 'bare hands are slow; I need proper tools' })
  }
  if (a.toolTier < 2 && known.has('bronze_working') && invCount(t.storage, 'bronze') + invCount(a.inv, 'bronze') >= 2) P.push({ id: 'tools', score: 2.6, steps: [S('craft', 'bronze_tools')], why: 'bronze tools would double my work' })
  if (a.toolTier < 3 && known.has('iron_working') && invCount(t.storage, 'iron') + invCount(a.inv, 'iron') >= 2) P.push({ id: 'tools', score: 2.6, steps: [S('craft', 'iron_tools')], why: 'iron holds an edge far better than bronze' })
  if (a.clothing < 30 && w.season >= 2 && known.has('clothing')) P.push({ id: 'clothes', score: 3, steps: invCount(t.storage, 'hide') + invCount(a.inv, 'hide') >= 2 ? [S('craft', 'clothes')] : [S('hunt'), S('craft', 'clothes')], why: 'my clothes are in rags and winter is coming' })
  // build the next thing the people need
  const nb = nextBuilding(s)
  if (nb) P.push({ id: 'build', score: 2.2 + T.conscientiousness * 1.2 + a.skills.building / 80, steps: [...buildPlan(s, nb)], why: `we need a ${bname(nb)} — ${costWords(s, nb)}get it built`, tell: `I'm going to build a ${bname(nb)}. ${pick(['It is time.', 'We have waited long enough.', 'Who will help?'])}` })
  // knowledge
  const tf = techFor(s)
  if (tf) {
    const d = TECH_BY_ID[tf.id]
    const era = eraOf(known)
    P.push({ id: 'research', score: (0.6 + T.intelligence * 1.1 + T.openness * 0.8 + a.skills.research / 70) * (1 + era * 0.12) * (lean ? 0.45 : 1), steps: [S('research', tf.id, 4), S('research', tf.id, 3)], why: `I want to work out ${d.name.toLowerCase()}, ${tf.why}`, tell: chance(0.2) ? `I've been thinking about ${d.name.toLowerCase()}. I think I'm close to something.` : undefined })
  }
  // underground wealth: prospect, then mine
  if (known.has('mining') && !lean) {
    const step = instinctMineral(s)
    if (step) P.push({ id: 'mine', score: 1.8 + T.openness * 0.8 + T.conscientiousness * 0.5, steps: step.steps, why: step.why })
  }
  // fleet
  if (known.has('fishing') && w.tribeBuildings(t.id).some((b) => b.type === 'dock' || b.type === 'harbour') && s.perHead < 30) P.push({ id: 'fish', score: 1.8 + (lean ? 1.5 : 0), steps: [S('fish', '', 12), S('store')], why: 'the boats are idle and the sea is full of fish' })
  // fields in season
  if (w.tribeBuildings(t.id, 'farm').length && w.season <= 2) P.push({ id: 'farm', score: 1.6 + (lean ? 2 : 0) + a.skills.farming / 60, steps: [S('farm', '', 3), S('store')], why: 'the fields need tending' })
  // the leader's work
  const st = statecraft(s); if (st) P.push(st)
  const dc = decrees(s); if (dc) P.push(dc)
  // a book, a song
  const wr = writing(s); if (wr) P.push(wr)
  // people
  const so = social(s); if (so) P.push(so)
  // beyond the horizon
  if (w.season !== 3 && !famine) P.push({ id: 'explore', score: 0.6 + T.openness * 1.8 + (members > 20 ? 0.4 : 0), steps: [S('explore', exploreDir(s)), S('go_home')], why: pick(['I want to see what lies past the hills', 'there must be better land out there', 'no one has walked that way yet']) })
  // a feast
  if (a.mood < 45 && s.perHead > 10 && w.tribeBuildings(t.id).length > 2) P.push({ id: 'feast', score: 1.4 + T.extraversion * 1.4, steps: [S('celebrate', '', 0, occasion(s))], why: 'everyone is low; a feast would lift us', tell: `Tonight we feast — ${occasion(s)}!` })
  // break away
  if (!s.leader && members > 18 && T.extraversion > 0.65 && T.agreeableness < 0.42 && w.age(a) > 22) {
    const leader = w.agentById.get(t.leaderId)
    if (leader && (a.relations[leader.id]?.affinity ?? 0) < -30 && chance(0.15)) P.push({ id: 'split', score: 5, steps: [S('found_tribe', newTribeName(a))], why: `I will not live another year under ${leader.name}`, tell: `I'm leaving. Whoever is tired of ${leader.name}, come with me.` })
  }
  // too much in my pack
  if (invTotal(a.inv) > CARRY_CAP * 0.7) P.push({ id: 'store', score: 3, steps: [S('store')], why: 'my pack is too heavy' })
  return P
}

function instinctMineral(s: Sit): { steps: PlanStep[]; why: string } | null {
  const { w, a, t, known } = s
  if (!t) return null
  const need: Item[] = ['copper', 'tin', 'iron_ore', 'coal']
  if (known.has('oil_drilling')) need.push('oil')
  if (known.has('aluminium_smelting')) need.push('bauxite')
  if (known.has('nuclear_power')) need.push('uranium')
  if (known.has('lithium_batteries')) need.push('lithium')
  const wanted = need.filter((i) => invCount(t.storage, i) < 20)
  if (!wanted.length) return null
  const item = pick(wanted)
  const st = resolve(w, a, item, 10, 0)
  if (!st) return null
  if (st.action === 'prospect') return { steps: [st, S('prospect', '', 3)], why: `there must be ${itemName(item)} in these rocks somewhere` }
  if (st.action === 'build') return { steps: buildPlan(s, st.target as BuildingType), why: `there is ${itemName(item)} underground waiting for a ${itemName(st.target)}` }
  return { steps: [st, S('store')], why: `we're short of ${itemName(item)}` }
}

function occasion(s: Sit): string {
  const r = s.recent?.text ?? ''
  if (/born|baby|child/i.test(r)) return 'for the new child'
  if (/discover|learn|understand|invent/i.test(r)) return 'for what we have learned'
  if (/victory|won|peace/i.test(r)) return 'for peace'
  if (s.w.season === 2) return 'for the harvest'
  if (s.w.season === 0) return 'for the first day of spring'
  return 'because we are still here'
}

function newTribeName(a: Agent): string {
  const roots = ['Ash', 'Fen', 'Kor', 'Tal', 'Mir', 'Var', 'Esh', 'Lun', 'Dra', 'Oro', 'Sel', 'Hak']
  const ends = ['ani', 'ren', 'ith', 'ost', 'ara', 'un', 'el', 'osi', 'ka', 'mar']
  return pick(roots) + pick(ends) + (a.name.length % 2 ? '' : 'i')
}

// ------------------------------------------------------------------ words

function chat(s: Sit, to: Agent): string {
  const { w, a, t } = s
  const v = s.voice
  const r = w.relation(a, to)
  if (to.tribeId !== a.tribeId) {
    const o = w.tribeById(to.tribeId)
    const rel = t && o ? w.tribeRelation(t.id, o.id) : 0
    if (rel < -30) return pick([`Your people have no business here, ${to.name}.`, `Tell your leader the ${t?.name} remember what was done.`])
    return pick([`Welcome, friend of the ${o?.name ?? 'far lands'}. What do your people make that we don't?`, `How are things among the ${o?.name ?? 'your people'}, ${to.name}?`, 'Perhaps our peoples could learn from each other.'])
  }
  if (s.recent && chance(0.5)) {
    const m = s.recent.text
    if (/died|killed|death/i.test(m)) return pick([`I keep thinking about the ones we lost, ${to.name}.`, 'It is strange how quiet the camp feels now.'])
    if (/war/i.test(m)) return pick(['Do you think they will come here?', `If it comes to fighting, ${to.name}, stay close to me.`])
    if (/discover|understand|learn/i.test(m)) return pick(['Did you hear what we worked out? Imagine what comes next.', 'Every day we know a little more.'])
  }
  if (r.affinity > 55) return pick([`You always know how to make me laugh, ${to.name}.`, `I'm glad you're here, ${to.name}.`, `Come and eat with us tonight, ${to.name}.`])
  if (r.affinity < -20) return v === 'blunt' ? `We need to talk, ${to.name}. You and I keep crossing.` : `Can we start again, ${to.name}? I don't want bad blood between us.`
  const lines: Record<Voice, string[]> = {
    warm: [`How are the little ones, ${to.name}?`, 'Have you eaten? There is stew by the fire.', `It's good work we're doing, ${to.name}. Together.`],
    blunt: ['Too many hands idle around here.', `You look tired, ${to.name}. Sleep, then work.`, 'Talk is cheap. Let\'s build something.'],
    anxious: ['Do you think the stores will last the winter?', 'I had a bad dream last night. Fire, and running.', `Stay close tonight, ${to.name}.`],
    dreamer: ['Have you ever wondered what the stars are made of?', 'One day our grandchildren will laugh at how little we knew.', `What do you think is past the far hills, ${to.name}?`],
    steady: [`Good day for it, ${to.name}.`, 'One more season and we\'ll be in better shape.', 'Let\'s keep at it.'],
  }
  return pick(lines[v])
}

/** A reply to whatever was just said to me, if anything. */
function reply(s: Sit): { say: string; to: string; extra?: PlanStep; heard: string } | null {
  const { w, a, t } = s
  const h = s.heard
  if (!h) return null
  const from = w.agentById.get(h.fromId)
  if (!from || !from.alive || Math.hypot(from.x - a.x, from.y - a.y) > 10) return null
  const q = h.text.toLowerCase()
  const n = from.name
  const v = s.voice
  // words thrown to the whole group get an answer from one or two, not from everyone
  const direct = from.speech?.to === a.id
  if (!direct && !chance(0.22)) return null
  // a suggestion for the leader
  const prop = /^i propose:\s*(.+)$/i.exec(h.text)
  if (prop && s.leader && t) {
    const affinity = a.relations[from.id]?.affinity ?? 0
    if (affinity > -10 && (a.traits.agreeableness > 0.4 || chance(0.4)) && t.laws.length < 6) {
      return { say: `A good thought, ${n}. So be it.`, to: n, extra: S('decree', 'law', 0, prop[1].slice(0, 200)), heard: h.text }
    }
    return { say: v === 'blunt' ? `No, ${n}. Not while I lead.` : `I hear you, ${n}, but not yet.`, to: n, heard: h.text }
  }
  if (/would you trade/.test(q)) return null // the sim settles trades
  if (/marry|be with me|build a life|walk with me tonight|think of you/.test(q)) {
    const aff = a.relations[from.id]?.affinity ?? 0
    return { say: aff > 30 ? pick([`Yes, ${n}. I'd hoped you would ask.`, `I feel it too, ${n}.`]) : pick([`You're kind, ${n}, but my heart is elsewhere.`, `I'm not ready for that, ${n}.`]), to: n, heard: h.text }
  }
  if (/food|eat|hungry|stores?\b/.test(q) && q.includes('?')) {
    const msg = s.perHead < 4 ? 'The stores are almost empty. We have to find food, now.' : s.perHead < 10 ? 'There is a little, but not enough for winter.' : 'The stores are full enough. No one need go hungry.'
    return { say: msg, to: n, heard: h.text }
  }
  if (/war|fight|enemy|attack|spear/.test(q)) {
    const e = s.wars[0]
    const o = e ? w.tribeById(e.a === a.tribeId ? e.b : e.a) : undefined
    return { say: o ? (v === 'anxious' ? `I'm frightened of the ${o.name}, ${n}. I won't pretend otherwise.` : `Let the ${o.name} come. We'll be ready.`) : 'Fighting? I would rather we never have to.', to: n, heard: h.text }
  }
  if (/sick|fever|unwell|ill\b/.test(q)) return { say: `Lie down, ${n}. I'll bring water.`, to: n, extra: from.sick > 0.5 ? S('heal', n) : undefined, heard: h.text }
  if (/cold|freez|firewood/.test(q)) return { say: 'I\'ll fetch more wood for the fire.', to: n, extra: S('gather', 'wood', 8), heard: h.text }
  if (/help me|help\?|who will help|help finish/.test(q)) {
    const site = s.site
    if (site && chance(0.4 + a.traits.agreeableness * 0.5)) return { say: pick([`I'm with you, ${n}.`, 'Show me what to carry.']), to: n, extra: S('build', site.type), heard: h.text }
    return { say: `Not now, ${n} — maybe later.`, to: n, heard: h.text }
  }
  if (/teach|show me/.test(q)) return { say: `Watch closely, ${n}.`, to: n, extra: S('teach', n), heard: h.text }
  if (/how are|how goes|how was|tell me/.test(q)) return { say: `${feelingLine(s)} ${pick(['And you?', 'What about you?', ''])}`.trim(), to: n, heard: h.text }
  if (/new law|decreed|hear me/.test(q)) return null
  if (q.endsWith('?')) {
    return { say: pick(v === 'dreamer' ? ['I wonder that too.', 'Perhaps. Ask me again under the stars.'] : v === 'blunt' ? ['Who knows. Back to work.', 'Does it matter?'] : ['I think so, yes.', 'I hope so.', 'Hard to say.']), to: n, heard: h.text }
  }
  if (chance(0.45)) return { say: pick(v === 'warm' ? [`You're right, ${n}.`, `I'm glad you told me, ${n}.`] : v === 'blunt' ? ['Hm.', 'If you say so.'] : ['True enough.', 'Mm, I was thinking the same.']), to: n, heard: h.text }
  return null
}

function feelingLine(s: Sit): string {
  const a = s.a
  if (a.sick > 0.4) return 'I feel wretched — the fever.'
  if (a.hunger > 55) return 'Hungry, mostly.'
  if (s.wars.length) return 'Uneasy. The war is on everyone\'s mind.'
  if (a.mood > 70) return 'I feel good, truly.'
  if (a.mood < 35) return 'Not well, to tell the truth. Heavy-hearted.'
  return 'I\'m well enough.'
}

function feelingWord(s: Sit): string {
  const a = s.a
  if (a.sick > 0.5) return 'feverish'
  if (s.recent && /died|killed/i.test(s.recent.text)) return a.traits.neuroticism > 0.5 ? 'grieving' : 'sombre'
  if (s.wars.length) return a.traits.agreeableness < 0.4 ? 'fierce' : 'afraid'
  if (a.hunger > 60) return 'hungry'
  if (a.energy < 25) return 'weary'
  if (a.warmth < 40) return 'cold'
  if (a.social < 25) return 'lonely'
  if (s.recent && /discover|learn|understand|born/i.test(s.recent.text)) return 'joyful'
  if (a.mood > 75) return pick(['content', 'hopeful', 'proud'])
  if (a.mood < 35) return pick(['low', 'restless', 'anxious'])
  return pick(['steady', 'determined', 'curious', 'calm'])
}

function observation(s: Sit): string {
  const { w, a, t } = s
  if (a.sick > 0.5) return 'My skin burns and my head swims.'
  if (s.recent) {
    const m = s.recent.text.replace(/\s+/g, ' ')
    return m.length < 160 ? m : m.slice(0, 157) + '…'
  }
  if (s.wars.length && t) {
    const e = s.wars[0]
    const o = w.tribeById(e.a === t.id ? e.b : e.a)
    return `We are at war with the ${o?.name ?? 'enemy'}, and everyone feels it.`
  }
  if (s.perHead < 4) return 'The stores are almost bare.'
  if (a.warmth < 45) return 'The cold is in my bones.'
  if (isNight(w.time)) return pick(['The fire has burned low.', 'The stars are out and the camp is quiet.'])
  const se = SEASONS[w.season].toLowerCase()
  return pick([`It is ${se}, and ${w.weather === 'rain' ? 'the rain will not stop' : w.weather === 'snow' ? 'snow covers the ground' : 'the air is clear'}.`, `The ${t?.name ?? 'camp'} is ${s.members > 20 ? 'busy and loud' : 'small but alive'} today.`, `There is always more to do than hands to do it.`])
}

function coda(s: Sit): string {
  const T = s.a.traits
  if (s.voice === 'anxious') return pick(['I hope it is enough.', 'I wish I could stop worrying.', ''])
  if (s.voice === 'dreamer') return pick(['One day all this will seem small.', 'I wonder what our grandchildren will make of us.', ''])
  if (s.voice === 'warm') return pick(['We look after each other — that is what matters.', ''])
  if (s.voice === 'blunt') return pick(['No one else will do it.', 'Talking won\'t fill the stores.', ''])
  return T.conscientiousness > 0.6 ? pick(['One thing at a time.', '']) : ''
}

const DEFAULT_GOAL = 'Find my place in the tribe.'

function lifeGoal(s: Sit): string {
  const { a, t } = s
  const T = a.traits
  const tn = t?.name ?? 'my people'
  if (s.leader) return T.agreeableness > 0.55 ? `Keep the ${tn} fed, safe and at peace.` : T.extraversion > 0.6 ? `Make the ${tn} the greatest people under the sky.` : `Lead the ${tn} through whatever comes.`
  const best = (Object.entries(a.skills) as [string, number][]).sort((x, y) => y[1] - x[1])[0]?.[0] ?? 'gathering'
  if (T.intelligence > 0.7 && T.openness > 0.6) return 'Understand how the world works, and write it down for those who come after.'
  if (T.openness > 0.75) return 'See the far side of every horizon.'
  if (s.partner || a.children.length) return 'Raise my children well and give them a better life than mine.'
  if (T.extraversion > 0.7 && T.agreeableness < 0.45) return `Become someone the ${tn} listen to.`
  if (T.agreeableness > 0.7) return `Make sure no one among the ${tn} is left behind.`
  const craft: Record<string, string> = {
    building: 'Build things that will stand long after I am gone.', farming: 'Make the fields feed everyone, even in the worst winter.',
    crafting: 'Master my craft until my tools are the best anyone has seen.', research: 'Discover something no one has known before.',
    hunting: 'Be the hunter my people rely on.', social: 'Bring people together and keep the peace.', gathering: 'Know every plant, stone and stream in our lands.',
  }
  return craft[best] ?? DEFAULT_GOAL
}

// ------------------------------------------------------------------ the decision

/** Think: one full decision — thought, feeling, goal, speech and a plan — from the world as it is. */
export function nativeDecide(w: World, a: Agent): Decision {
  const s = situation(w, a)
  let goal = a.goal
  if (!goal || goal === DEFAULT_GOAL || (s.leader && !/lead|people|keep the|greatest/i.test(goal)) || chance(0.01)) goal = lifeGoal(s)

  // the body comes first
  const body: PlanStep[] = []
  if (a.thirst > 50) body.push(S('drink'))
  if (a.hunger > 50) body.push(S('eat'))
  const night = isNight(w.time) && (hourOf(w.time) >= 21 || hourOf(w.time) < 5)
  if ((night && a.energy < 70) || a.energy < 22) {
    const plan = [...body, S('sleep')]
    const thought = `${observation(s)} ${pick(['Time to rest; tomorrow is another long day.', 'My eyes are closing on their own.', 'Sleep now — the work will wait.'])}`
    return { thought, feeling: a.energy < 22 ? 'exhausted' : 'drowsy', goal, say: s.partner && Math.hypot(s.partner.x - a.x, s.partner.y - a.y) < 6 && chance(0.3) ? `Goodnight, ${s.partner.name}.` : '', say_to: s.partner?.name ?? '', plan }
  }
  if (s.campaign) {
    // under orders: eat and drink if need be, then back to the column
    const c = w.dip.campaigns.find((x) => x.units.includes(a.id))!
    const back = c.phase === 'return' ? [] : [S('war', w.tribeById(c.enemy)?.name ?? '', 0, c.kind)]
    const doing = c.kind === 'defend' ? 'I stand with the others to defend our home.' : c.phase === 'return' ? 'We are going home.' : 'I march with the others.'
    return { thought: `${observation(s)} ${doing} ${pick(['I will not shame my people.', 'I only want to come home.', 'Let it be quick.'])}`, feeling: a.traits.neuroticism > 0.5 ? 'afraid' : 'grim', goal, say: '', say_to: '', plan: [...body, ...back] }
  }

  const P = chooseProjects(s)
  P.sort((x, y) => y.score - x.score)
  // a little randomness between close options, so no two lives run the same
  let chosen = P[0]
  if (P.length > 1 && P[1].score > P[0].score * 0.85 && chance(0.35)) chosen = P[1]
  if (!chosen) chosen = { id: 'instinct', score: 0, steps: [instinctStep(w, a)], why: 'I\'ll keep busy' }

  const plan: PlanStep[] = [...body]
  const rep = reply(s)
  if (rep?.extra) plan.push(rep.extra)
  plan.push(...chosen.steps)
  // a second, smaller project if the first is short
  if (plan.length < 3 && P.length > 1) {
    const second = P.find((p) => p !== chosen && p.id !== 'statecraft' && p.id !== 'decree' && p.id !== 'split' && p.id !== 'write')
    if (second) plan.push(...second.steps.slice(0, 2))
  }
  const steps = plan.slice(0, 6)

  // what to say aloud
  let say = '', sayTo = ''
  if (rep) { say = rep.say; sayTo = rep.to }
  else if (chosen.tell && (chosen.id === 'statecraft' || chosen.id === 'decree' || chosen.id === 'feast' || chosen.id === 'split' || s.near.length) && chance(0.35 + a.traits.extraversion * 0.5)) {
    say = chosen.tell
    sayTo = s.near.length > 1 || chosen.id === 'decree' || chosen.id === 'statecraft' || chosen.id === 'food' ? 'everyone' : s.near[0]?.name ?? ''
  } else if (s.near.length && chance(0.1 + a.traits.extraversion * 0.25) && chosen.id !== 'company') {
    const o = pick(s.near)
    say = chat(s, o); sayTo = o.name
  }

  const why = chosen.why
  const intent = chosen.id === 'statecraft' || chosen.id === 'decree' ? `As their leader I must act: ${why}.` : `${cap(why)}${/[.!?]$/.test(why) ? '' : '.'}`
  const c = coda(s)
  const heardBit = rep && rep.heard.length < 90 ? `${s.heard?.from ?? 'Someone'} said, “${rep.heard.replace(/[“”"]/g, '')}” ` : ''
  const thought = `${heardBit}${observation(s)} ${intent}${c ? ' ' + c : ''}`.replace(/\s+/g, ' ').trim()
  return { thought: thought.slice(0, 400), feeling: feelingWord(s), goal, say, say_to: sayTo, plan: steps }
}

// ------------------------------------------------------------------ reflection

export function nativeReflect(w: World, a: Agent): Reflection {
  const s = situation(w, a)
  const mem = a.memories.map((m) => m.text).join(' \n ')
  const count = (re: RegExp) => (mem.match(re) ?? []).length
  const T = a.traits
  const ins: [number, string][] = []
  if (count(/hungry|starv|no food|empty/gi) >= 2) ins.push([count(/hungry|starv/gi) * 2, T.conscientiousness > 0.5 ? 'An empty store is the worst enemy we have. Food first, always — then everything else.' : 'Hunger makes fools and thieves of us all. Fields are worth more than spears.'])
  if (count(/died|death|killed|buried/gi) >= 1) ins.push([4 + count(/died|killed/gi), T.neuroticism > 0.55 ? 'Anyone can be gone by morning. I should say what I feel while I still can.' : 'The dead are gone, but what they taught us is not. I will carry it.'])
  if (count(/war|battle|raid|march/gi) >= 2) ins.push([5, T.agreeableness > 0.5 ? 'War takes the best of us and gives nothing back. Peace is worth almost any price.' : 'Only the strong are left alone. We must never be weak again.'])
  if (count(/discover|learned|understand|invent|figured/gi) >= 1) ins.push([3, 'Every new idea makes the next one easier to reach. Knowledge grows like a fire.'])
  if (count(/taught me|showed me/gi) >= 1) ins.push([3, 'What others gave me freely, I must pass on.'])
  if (count(/traded|gift|gave me/gi) >= 1) ins.push([3, 'A gift or a fair trade turns a stranger into a friend.'])
  if (count(/refused|couldn't|could not|failed|nothing to/gi) >= 2) ins.push([3, 'Too often I begin before I am ready. I should gather what I need first.'])
  if (count(/cold|freez|snow/gi) >= 2) ins.push([3, 'Winter is patient and it always comes. Wood and walls before it does.'])
  if (s.partner && count(new RegExp(s.partner.name, 'g')) >= 2) ins.push([4, `${s.partner.name} is the steady centre of my days.`])
  if (a.children.length) ins.push([2, 'My children are the part of me that will see what comes next.'])
  if (!ins.length) ins.push([1, pick(['The days blur together, but the work adds up.', 'I am stronger than I was a year ago.', 'Small kindnesses hold a people together.'])])
  ins.sort((x, y) => y[0] - x[0])
  const best = (Object.entries(a.skills) as [string, number][]).sort((x, y) => y[1] - x[1])[0]
  const role: Record<string, string> = { building: 'builder', farming: 'farmer', crafting: 'maker', research: 'thinker', hunting: 'hunter', social: 'peacemaker', gathering: 'forager' }
  const adj = T.agreeableness > 0.62 ? 'kind' : T.agreeableness < 0.38 ? 'hard' : T.openness > 0.65 ? 'curious' : T.conscientiousness > 0.62 ? 'dependable' : T.neuroticism > 0.65 ? 'restless' : 'steady'
  const self = `${s.leader ? `The leader of the ${s.t?.name}, ` : ''}a ${adj} ${role[best?.[0] ?? 'gathering']} of the ${s.t?.name ?? 'wilds'}${s.partner ? `, partner of ${s.partner.name}` : ''}${a.children.length ? `, parent of ${a.children.length}` : ''}.`
  const beliefs = s.t?.beliefs && T.agreeableness > 0.4 ? s.t.beliefs
    : T.openness > 0.65 ? 'The world can be understood if we look closely enough and are honest about what we see.'
    : T.neuroticism > 0.6 ? 'Luck turns fast. Prepare for the worst and be grateful for the rest.'
    : 'We are only as strong as the people beside us.'
  return { insights: ins.slice(0, 2).map((x) => x[1]), goal: a.goal || lifeGoal(s), self_image: self, beliefs }
}

// ------------------------------------------------------------------ interview

export function nativeInterview(w: World, a: Agent, question: string): string {
  const s = situation(w, a)
  const q = question.toLowerCase()
  const t = s.t
  const tn = t?.name ?? 'my people'
  const parts: string[] = []
  const lastRefl = a.reflections[a.reflections.length - 1]
  if (/who are you|yourself|your name/.test(q)) parts.push(`I am ${a.name}${t ? ` of the ${tn}` : ''}, ${Math.floor(w.age(a))} years old. ${a.selfImage || ''}`.trim())
  if (/feel|happy|sad|mood/.test(q)) parts.push(`${feelingLine(s)} ${a.thought ? `Lately: ${a.thought}` : ''}`.trim())
  if (/want|goal|dream|hope|wish|ambition/.test(q)) parts.push(`What I want? ${a.goal || lifeGoal(s)} ${s.voice === 'dreamer' ? 'And I dream of things I don\'t yet have words for.' : ''}`.trim())
  if (/fear|afraid|scare|worr/.test(q)) parts.push(s.wars.length ? 'The war. Every night I listen for them.' : s.perHead < 8 ? 'Winter, and empty stores.' : a.traits.neuroticism > 0.6 ? 'Losing the people I love. It keeps me awake.' : 'Not much, these days. Fire, maybe. Fever.')
  if (/believe|god|spirit|faith|relig/.test(q)) parts.push(a.beliefs || t?.beliefs || 'I believe in what I can see and touch — and in my people.')
  if (/love|partner|family|child/.test(q)) parts.push(s.partner ? `${s.partner.name} is my partner${a.children.length ? `, and we have ${a.children.length} child${a.children.length > 1 ? 'ren' : ''}` : ''}. They are everything.` : 'I have no partner yet. Perhaps one day.')
  if (/leader|chief/.test(q) && t) { const l = w.agentById.get(t.leaderId); parts.push(s.leader ? `I lead the ${tn}. It is heavier than it looks.` : l ? `${l.name} leads us. ${(a.relations[l.id]?.affinity ?? 0) > 20 ? 'I trust them.' : (a.relations[l.id]?.affinity ?? 0) < -20 ? 'Between us — I would do it differently.' : 'They do well enough.'}` : 'No one leads us now.') }
  for (const o of w.tribes) if (o.alive && o.id !== a.tribeId && q.includes(o.name.toLowerCase())) {
    const r = t ? w.tribeRelation(t.id, o.id) : 0
    parts.push(`The ${o.name}? ${warBetween(w, a.tribeId, o.id) ? 'We are at war. I try not to hate them, but it is hard.' : r > 25 ? 'Good people. Friends.' : r < -25 ? 'I don\'t trust them, and they don\'t trust us.' : 'We know little of them yet.'}`)
  }
  for (const o of w.living) if (o !== a && o.name.length > 2 && q.includes(o.name.toLowerCase())) {
    const r = a.relations[o.id]
    parts.push(`${o.name}? ${!r ? 'I barely know them.' : r.affinity > 50 ? 'One of the best people I know.' : r.affinity > 15 ? 'I like them.' : r.affinity < -25 ? 'We do not get along.' : 'We get by.'}`)
    break
  }
  if (/do|doing|work|plan|today/.test(q) || !parts.length) {
    const tk = a.task?.label || 'resting'
    const next = a.plan.slice(0, 3).map((p) => `${p.action}${p.target ? ' ' + itemName(p.target) : ''}`).join(', then ')
    parts.push(`Right now I'm ${tk.toLowerCase()}.${next ? ` After that: ${next}.` : ''}`)
  }
  if (/learn|lesson|wisdom|advice|why/.test(q) && lastRefl) parts.push(`Something I've come to understand: ${lastRefl}`)
  return parts.join(' ').replace(/\s+/g, ' ').trim().slice(0, 900)
}

// ------------------------------------------------------------------ the historian

export function nativeChronicle(w: World, year: number): { title: string; text: string } | null {
  const from = (year - 1) * YEAR, to = year * YEAR
  const E = w.chronicle.filter((e) => e.t >= from && e.t < to && e.kind !== 'speech')
  if (E.length < 3) return null
  const by = (re: RegExp) => E.filter((e) => re.test(e.text))
  const wars = by(/\bwar\b|battle|march on|raid/i).filter((e) => !/agree to trade|peace/i.test(e.text)), peace = by(/peace/i), techs = E.filter((e) => e.kind === 'tech')
  const deaths = E.filter((e) => e.kind === 'death'), births = E.filter((e) => e.kind === 'birth'), builds = E.filter((e) => e.kind === 'build')
  const pacts = by(/alliance|agree to trade|tribute/i), splits = by(/founded|broke away|new people/i), events = E.filter((e) => e.kind === 'event' && !/begins|returns|arrives/i.test(e.text))
  const live = w.tribes.filter((t) => t.alive)
  const clean = (s: string) => s.replace(/\s+/g, ' ').replace(/[“”]/g, '"').trim().replace(/([^.!?])$/, '$1.')
  const p: string[] = []
  const season = (t: number) => SEASONS[Math.floor((t % YEAR) / (YEAR / 4))]?.toLowerCase() ?? 'that season'
  p.push(`In the ${year}${['th', 'st', 'nd', 'rd'][year % 10 > 3 || Math.floor(year / 10) % 10 === 1 ? 0 : year % 10]} year, ${live.length === 1 ? `the ${live[0].name} were alone in the world` : `${live.length} peoples shared the world — ${listing(live.map((t) => `the ${t.name} (${w.members(t.id).length})`))}`}.`)
  if (events.length) p.push(`It was a year marked by ${listing(events.slice(0, 2).map((e) => clean(e.text).replace(/\.$/, '').toLowerCase()))}.`)
  if (techs.length) p.push(`Knowledge grew: ${listing(techs.slice(0, 4).map((e) => clean(e.text).replace(/\.$/, '')))}${techs.length > 4 ? `, and ${techs.length - 4} discoveries more` : ''}.`)
  if (builds.length) p.push(`${builds.length} works were raised${builds.length > 2 ? `, ${clean(builds[builds.length - 1].text).replace(/\.$/, '').toLowerCase()} among them` : ''}.`)
  if (pacts.length) p.push(pacts.slice(0, 2).map((e) => clean(e.text)).join(' '))
  if (wars.length) p.push(`In ${season(wars[0].t)}, ${clean(wars[0].text).replace(/^./, (c) => c.toLowerCase())} ${wars.length > 2 ? `Blood was shed in ${wars.length - 1} more clashes.` : ''}`)
  if (peace.length) p.push(clean(peace[peace.length - 1].text))
  if (splits.length) p.push(clean(splits[0].text))
  if (births.length || deaths.length) p.push(`${births.length} children were born${deaths.length ? ` and ${deaths.length} ${deaths.length === 1 ? 'soul was' : 'souls were'} lost${deaths[0] ? ` — among them, ${clean(deaths[0].text).replace(/\.$/, '').replace(/^./, (c) => c.toLowerCase())}` : ''}` : ''}.`)
  const era = Math.max(...live.map((t) => eraOf(w.techs(t.id))), 0)
  p.push(pick([`So the world turned, and the ${ERAS[era].name} went on.`, 'And the people, as people do, went on.', `By year's end, the world was a little older and a little wiser.`]))
  const title = wars.length ? pick(['The Year of Spears', 'Blood on the Border', 'Fire and Iron']) : techs.length > 2 ? pick(['The Year of Discoveries', 'New Light', 'What the Wise Found']) : deaths.length > births.length + 2 ? pick(['The Hard Year', 'The Year of Mourning']) : pacts.length ? pick(['Hands Across the River', 'The Year of Envoys']) : pick(['The Quiet Year', 'Seeds and Seasons', 'Hearths and Harvests'])
  return { title, text: p.join(' ').replace(/\s+/g, ' ').slice(0, 2400) }
}

