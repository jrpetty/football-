// Players and their AIs. Each player owns an AI: it has a name, a mind (the
// built-in Genesis Mind or any model), a brief written before the game, and
// preference sliders. When a world begins, each player's AI takes one people
// and plays it alone — from then on nobody may steer it. Whatever it learns
// the hard way is kept, so the same AI plays better in the next world.

import type { Lesson, Learned, Prefs, Tribe } from '../sim/types.ts'
import { FRESH_LEARNED, NEUTRAL_PREFS, clampLearned, mergePrefs } from '../sim/learning.ts'
import { YEAR } from '../sim/time.ts'
import type { World } from '../sim/world.ts'

export interface GameRecord {
  world: string
  tribe: string
  started: number // real ms
  years: number
  pop: number
  techs: number
  alive: boolean
}

export interface PlayerAI {
  id: string
  name: string
  mind: string // mind profile id (genesis-mind, a Claude model, or a custom profile)
  brief: string
  sliders: Prefs
  learned: Learned
  lessons: Lesson[]
  games: GameRecord[]
}

const LS = 'genesis.players'
let memory: PlayerAI[] | null = null // used when there is no localStorage (tests, node)

export function loadPlayers(): PlayerAI[] {
  try {
    if (typeof localStorage !== 'undefined') {
      const raw = localStorage.getItem(LS)
      if (raw) return (JSON.parse(raw) as PlayerAI[]).map(fill)
      return []
    }
  } catch { /* unavailable */ }
  return (memory ??= []).map(fill)
}
export function savePlayers(list: PlayerAI[]) {
  try {
    if (typeof localStorage !== 'undefined') { localStorage.setItem(LS, JSON.stringify(list)); return }
  } catch { /* unavailable */ }
  memory = list.map((p) => structuredClone(p))
}
function fill(p: PlayerAI): PlayerAI {
  return { ...p, sliders: { ...NEUTRAL_PREFS, ...p.sliders }, learned: { ...FRESH_LEARNED, ...p.learned }, lessons: p.lessons ?? [], games: p.games ?? [] }
}

export function newPlayer(name: string, mind = 'genesis-mind'): PlayerAI {
  return { id: `pl_${Math.random().toString(36).slice(2, 9)}`, name, mind, brief: '', sliders: { ...NEUTRAL_PREFS }, learned: { ...FRESH_LEARNED }, lessons: [], games: [] }
}
export function upsertPlayer(p: PlayerAI) {
  const list = loadPlayers()
  const i = list.findIndex((x) => x.id === p.id)
  if (i >= 0) list[i] = p; else list.push(p)
  savePlayers(list)
}
export function removePlayer(id: string) {
  savePlayers(loadPlayers().filter((x) => x.id !== id))
}
export function forgetLessons(id: string) {
  const list = loadPlayers()
  const p = list.find((x) => x.id === id)
  if (!p) return
  p.learned = { ...FRESH_LEARNED }
  p.lessons = []
  savePlayers(list)
}

/**
 * Seat the chosen players' AIs at the start of a world: one people each. The
 * brief and preferences are copied in and locked; the AI brings everything it
 * learned in earlier worlds. Peoples nobody claimed are played by the world's own AI.
 */
export function seatPlayers(w: World, ids: string[]) {
  const all = loadPlayers()
  const chosen = ids.map((id) => all.find((p) => p.id === id)).filter((p): p is PlayerAI => !!p)
  w.tribes.forEach((t, i) => {
    const p = chosen[i]
    if (!p) return
    t.model = p.mind
    t.ai = { player: p.id, playerName: p.name, brief: p.brief.trim().slice(0, 1200), prefs: mergePrefs(p.sliders, p.brief), learned: { ...p.learned }, lessons: [] }
    w.log('ai', `${p.name}'s AI takes up the ${t.name}${p.games.length ? `, bringing what it learned in ${p.games.length} earlier world${p.games.length > 1 ? 's' : ''}` : ''}.`, '🧑', undefined, t.id)
    p.games.push({ world: w.cfg.name, tribe: t.name, started: Date.now(), years: 0, pop: w.members(t.id).length, techs: w.techs(t.id).size, alive: true })
    if (p.games.length > 30) p.games.shift()
  })
  if (chosen.length) w.cfg.handsOff = true
  savePlayers(all)
}

/** A people learned something: its player's AI keeps it for good (a little damped, so one bad year doesn't define it). */
export function keepLesson(t: Tribe, l: Lesson) {
  const id = t.ai?.player
  if (!id) return
  const list = loadPlayers()
  const p = list.find((x) => x.id === id)
  if (!p) return
  p.learned[l.key] = clampLearned(l.key, p.learned[l.key] + l.delta * 0.6)
  p.lessons.push(l)
  if (p.lessons.length > 40) p.lessons.shift()
  savePlayers(list)
}

/** Keep each player's record of the current world up to date. */
export function recordProgress(w: World) {
  const list = loadPlayers()
  let changed = false
  for (const t of w.tribes) {
    const p = t.ai?.player ? list.find((x) => x.id === t.ai!.player) : undefined
    if (!p) continue
    const g = [...p.games].reverse().find((x) => x.world === w.cfg.name && x.tribe === t.name) ?? p.games.find((x) => x.world === w.cfg.name)
    if (!g) continue
    g.years = Math.floor(w.time / YEAR)
    g.pop = w.members(t.id).length
    g.techs = w.techs(t.id).size
    g.alive = t.alive
    changed = true
  }
  if (changed) savePlayers(list)
}
