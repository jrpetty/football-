// How a people's AI learns. Every spring it looks back on the year: who died and
// of what, how wars it chose went, whether it fell behind in knowledge, whether
// its people slept under roofs. Each hard lesson nudges what it values (keep
// fuller stores, build walls, never again start a war against an equal…). The
// lessons belong to the people for the rest of the game, and a player's own AI
// carries them into the next world too.

import type { Lesson, Learned, Prefs, Tribe, TribeAI } from './types.ts'
import { BUILDINGS } from './buildings.ts'
import { FOOD_ITEMS, invCount } from './data.ts'
import { YEAR } from './time.ts'
import type { World } from './world.ts'

export const NEUTRAL_PREFS: Prefs = { war: 0, science: 0, expand: 0, trade: 0, faith: 0, growth: 0 }
export const FRESH_LEARNED: Learned = { food: 1, warmth: 1, housing: 1, defence: 1, warCaution: 1, research: 1, trade: 1 }
const LIMITS: Record<keyof Learned, [number, number]> = {
  food: [0.6, 2.2], warmth: [0.6, 2], housing: [0.6, 2], defence: [0.5, 2.5], warCaution: [0.6, 2.2], research: [0.6, 2], trade: [0.5, 2],
}
export const LEARNED_NAMES: Record<keyof Learned, string> = {
  food: 'Food reserves', warmth: 'Winter readiness', housing: 'Housing', defence: 'Defence', warCaution: 'Caution before war', research: 'Research', trade: 'Trade & alliances',
}

export const clampLearned = (k: keyof Learned, v: number) => Math.max(LIMITS[k][0], Math.min(LIMITS[k][1], v))

/** What a written brief asks for, read word by word (added to the sliders). */
export function briefToPrefs(brief: string): Prefs {
  const b = brief.toLowerCase()
  const p = { ...NEUTRAL_PREFS }
  const hit = (re: RegExp) => (b.match(re) ?? []).length
  const neg = /\b(no|never|avoid|don'?t|do not|without|hate|against)\s+(\w+\s+){0,2}/.source
  const score = (pos: RegExp, words: string) => {
    const avoid = hit(new RegExp(`${neg}(${words})`, 'g'))
    return hit(pos) - avoid * 2
  }
  p.war += 0.35 * score(/\b(war|wars|conquer|conquest|aggressive|dominate|attack|raid|empire|military|army|fight|strong)\b/g, 'war|wars|fight|fighting|conquest|attack|violence') - 0.35 * hit(/\b(peace|peaceful|pacifist|gentle|kind|harmony)\b/g)
  p.science += 0.35 * hit(/\b(science|research|knowledge|technology|tech|invent|learn|discover|space|rocket|universit|scholar)\w*/g) - 0.3 * hit(/\b(tradition|old ways|simple)\b/g)
  p.expand += 0.35 * hit(/\b(expand|explore|settle|spread|territory|land|frontier|colon)\w*/g) - 0.3 * hit(/\b(stay|home|small|isolat)\w*/g)
  p.trade += 0.35 * score(/\b(trade|trading|ally|alliance|allies|friend|friendly|diplomac|cooperat|merchant)\w*/g, 'trade|trading|allies|alliances') - 0.3 * hit(/\b(alone|isolat|distrust|xenophob)\w*/g)
  p.faith += 0.35 * hit(/\b(faith|religio|temple|god|gods|spirit|art|culture|poet|song|beauty|monument|tradition)\w*/g)
  p.growth += 0.35 * hit(/\b(family|families|children|population|big|grow|growth|many)\w*/g) - 0.3 * hit(/\b(careful|cautious|few)\b/g)
  for (const k of Object.keys(p) as (keyof Prefs)[]) p[k] = Math.max(-1, Math.min(1, p[k]))
  return p
}

export function mergePrefs(sliders: Prefs, brief: string): Prefs {
  const b = briefToPrefs(brief)
  const out = { ...NEUTRAL_PREFS }
  for (const k of Object.keys(out) as (keyof Prefs)[]) out[k] = Math.max(-1, Math.min(1, sliders[k] + b[k]))
  return out
}

/** Make sure a people has an AI record (unclaimed peoples get the world's own, with no preferences). */
export function ensureAI(t: Tribe): TribeAI {
  if (!t.ai) t.ai = { player: '', playerName: '', brief: '', prefs: { ...NEUTRAL_PREFS }, learned: { ...FRESH_LEARNED }, lessons: [] }
  return t.ai
}

function stockOf(t: Tribe): number {
  let f = 0
  for (const it of FOOD_ITEMS) f += invCount(t.storage, it)
  return f
}

/**
 * Look back on the year that just ended, for every living people. Returns the
 * lessons drawn so a player's AI can keep them for future worlds.
 */
export function yearlyReview(w: World): { tribe: Tribe; lesson: Lesson }[] {
  const out: { tribe: Tribe; lesson: Lesson }[] = []
  const now = w.time
  const year = Math.max(1, Math.round(now / YEAR))
  const live = w.tribes.filter((t) => t.alive)
  const avgTech = live.reduce((s, t) => s + w.techs(t.id).size, 0) / Math.max(1, live.length)
  for (const t of live) {
    const ai = ensureAI(t)
    const snap = ai.snap
    const members = w.members(t.id)
    ai.snap = { t: now, pop: members.length, techs: w.techs(t.id).size, wars: w.dip.wars.filter((x) => x.a === t.id || x.b === t.id).map((x) => x.id) }
    if (!snap) continue
    const learn = (key: keyof Learned, delta: number, text: string) => {
      const before = ai.learned[key]
      // the same lesson again so soon only deepens it quietly
      const again = ai.lessons.some((l) => l.key === key && Math.sign(l.delta) === Math.sign(delta) && now - l.t < (delta < 0 ? 6 : 2.5) * YEAR)
      ai.learned[key] = clampLearned(key, before + (again ? delta * 0.5 : delta))
      if (Math.abs(ai.learned[key] - before) < 0.01 || again) return
      const lesson: Lesson = { t: now, year, world: w.cfg.name, tribe: t.name, text, key, delta }
      ai.lessons.push(lesson)
      if (ai.lessons.length > 24) ai.lessons.shift()
      out.push({ tribe: t, lesson })
      w.log('ai', `The ${t.name} learned a lesson: ${text}`, '🧠', undefined, t.id)
      for (const m of members) if (w.rng.chance(0.35)) w.remember(m, `We learned the hard way: ${text}`, 6)
    }
    // who died this year, and of what
    const deaths = w.chronicle.filter((e) => e.kind === 'death' && e.tribeId === t.id && e.t >= snap.t)
    const of = (re: RegExp) => deaths.filter((e) => re.test(e.text)).length
    const starved = of(/starvation|thirst/), froze = of(/the cold/), fell = of(/battle|fight/), sick = of(/illness/)
    const food = stockOf(t) / Math.max(1, members.length)
    if (starved > 0) learn('food', 0.12 + starved * 0.06, `${starved} of us starved. Fuller stores before anything else.`)
    else if (food > 40 && ai.learned.food > 1.15) learn('food', -0.05, 'Our stores overflow; we can spare hands for other work.')
    if (froze > 0) learn('warmth', 0.12 + froze * 0.06, `${froze} froze to death. Homes, firewood and clothes before winter.`)
    const housing = w.tribeBuildings(t.id).reduce((s, b) => s + (BUILDINGS[b.type].housing ?? 0), 0)
    if (members.length > housing + 3) learn('housing', 0.1, `${members.length - housing} of us sleep under the sky. Build more homes.`)
    if (sick > 1 && !w.techs(t.id).has('medicine')) learn('research', 0.06, 'Fever took our people. Knowledge of healing matters.')
    // wars that ended this year
    for (const war of w.dip.wars) {
      if (!war.ended || war.ended < snap.t || (war.a !== t.id && war.b !== t.id)) continue
      const mine = war.a === t.id ? war.score : -war.score
      const started = war.a === t.id && war.parent === undefined
      const dead = war.dead[t.id] ?? 0
      const foe = w.tribeById(war.a === t.id ? war.b : war.a)?.name ?? 'enemy'
      if (started && mine < -15) learn('warCaution', 0.25, `We started a war with the ${foe} and lost ${dead}. Never strike unless far stronger.`)
      else if (started && mine > 30) learn('warCaution', -0.1, `Our war on the ${foe} paid off. Strength used well wins.`)
      if (!started && mine < -15) learn('defence', 0.25, `The ${foe} attacked us and we could not hold them. Walls and warriors.`)
      else if (!started && mine >= 0 && dead === 0) learn('defence', -0.05, `We turned back the ${foe} without loss.`)
    }
    // raided while unprepared
    const raided = w.chronicle.some((e) => e.t >= snap.t && e.tribeId !== t.id && /burn|plunder|carry off/i.test(e.text) && e.text.includes(t.name))
    if (raided && !w.tribeBuildings(t.id).some((b) => b.type === 'walls' || b.type === 'barracks')) learn('defence', 0.2, 'Raiders burned our homes while we had no walls. Never again.')
    // falling behind in knowledge
    const myTech = w.techs(t.id).size
    if (live.length > 1 && myTech < avgTech - 5 && myTech - snap.techs < 3) learn('research', 0.12, 'Our neighbours know far more than we do. More time for study.')
    // trade made us richer
    const pacts = w.dip.pacts.filter((p) => p.kind === 'trade' && (p.a === t.id || p.b === t.id) && p.since >= snap.t).length
    if (pacts && myTech - snap.techs >= 3) learn('trade', 0.08, 'Trade brought us new ideas. Friends are worth more than gold.')
    // a people that is shrinking
    if (members.length < snap.pop - 3 && starved === 0 && fell === 0) learn('food', 0.06, 'We grow fewer each year. Feed the young and build homes.')
  }
  return out
}

/** A short line for each lesson a people has learned, newest first, for prompts and panels. */
export function lessonLines(t: Tribe, n = 6): string[] {
  return (t.ai?.lessons ?? []).slice(-n).reverse().map((l) => `Year ${l.year}${l.world && l.world !== '' ? '' : ''}: ${l.text}`)
}
