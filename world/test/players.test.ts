// Players' own AIs: briefs become preferences, every people learns from its
// mistakes, a player's AI carries those lessons into the next world, and once
// a world begins nobody can steer it.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { World, DEFAULT_CONFIG } from '../src/sim/world.ts'
import { YEAR, DAY } from '../src/sim/time.ts'
import { briefToPrefs, yearlyReview, ensureAI } from '../src/sim/learning.ts'
import { newPlayer, upsertPlayer, seatPlayers, keepLesson, loadPlayers } from '../src/ai/players.ts'
import { nativeDecide } from '../src/ai/native.ts'
import { MindManager } from '../src/ai/mind.ts'
import { NATIVE_ID } from '../src/ai/providers.ts'

test('a brief is read for what the player wants more or less of', () => {
  const peace = briefToPrefs('Be peaceful and curious. Trade with everyone, never start a war, and push for science until we reach the stars.')
  assert.ok(peace.war < 0, 'peaceful')
  assert.ok(peace.science > 0 && peace.trade > 0)
  const war = briefToPrefs('Build an empire. Conquer the weak and make them pay.')
  assert.ok(war.war > 0.5, 'warlike')
})

test('players take a people each, the brief is locked in, and the world goes hands-off', () => {
  const a = newPlayer('Ada'); a.brief = 'Science above all.'; upsertPlayer(a)
  const b = newPlayer('Bren', 'claude-sonnet-5-5'); upsertPlayer(b)
  const w = new World({ ...DEFAULT_CONFIG, seed: 5, tribes: 3, size: 112 })
  seatPlayers(w, [a.id, b.id])
  assert.equal(w.cfg.handsOff, true)
  assert.equal(w.tribes[0].ai?.player, a.id)
  assert.equal(w.tribes[0].ai?.brief, 'Science above all.')
  assert.ok((w.tribes[0].ai?.prefs.science ?? 0) > 0)
  assert.equal(w.tribes[1].model, 'claude-sonnet-5-5')
  assert.equal(w.tribes[2].ai, undefined, 'the third people belongs to the world')
  // nobody can switch the minds off mid-game
  const m = new MindManager()
  m.update({ enabled: false, nativeCrowd: true })
  m.attach(w)
  const adult = w.members(w.tribes[0].id).find((x) => w.isAdult(x))!
  w.tribes[0].model = NATIVE_ID
  assert.equal(m.mindOf(adult), NATIVE_ID, 'a player’s AI keeps playing even with minds switched off')
})

test('a famine becomes a lesson, and a player’s AI carries it into the next world', () => {
  const p = newPlayer('Cass'); upsertPlayer(p)
  const w = new World({ ...DEFAULT_CONFIG, seed: 6, tribes: 2, size: 112 })
  seatPlayers(w, [p.id])
  w.onLesson = (t, l) => keepLesson(t, l)
  const t = w.tribes[0]
  yearlyReview(w) // first look: takes a snapshot
  w.time += 30
  const victim = w.members(t.id).find((x) => w.isAdult(x))!
  w.kill(victim, 'starvation')
  w.time = Math.ceil(w.time / YEAR) * YEAR
  for (const { tribe, lesson } of yearlyReview(w)) w.onLesson?.(tribe, lesson)
  const ai = ensureAI(t)
  assert.ok(ai.learned.food > 1, `food matters more now (${ai.learned.food})`)
  assert.ok(ai.lessons.some((l) => /starved/.test(l.text)))
  const saved = loadPlayers().find((x) => x.id === p.id)!
  assert.ok(saved.learned.food > 1 && saved.lessons.some((l) => /starved/.test(l.text)), 'kept for good')
  // next world: the AI starts out wiser
  const w2 = new World({ ...DEFAULT_CONFIG, seed: 7, tribes: 2, size: 112 })
  seatPlayers(w2, [p.id])
  assert.ok((w2.tribes[0].ai?.learned.food ?? 1) > 1)
  assert.equal(loadPlayers().find((x) => x.id === p.id)!.games.length, 2)
})

test('the Genesis Mind reasons out loud and steps around what just failed', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 8, size: 112 })
  const a = w.living.find((x) => w.isAdult(x))!
  const d1 = nativeDecide(w, a)
  assert.match(d1.reasoning ?? '', /Weighed: .*Chose to/)
  w.time += 60
  w.remember(a, "I couldn't find the stone I needed.", 4)
  const d2 = nativeDecide(w, a)
  assert.match(d2.reasoning ?? '', /failed/)
})

test('a creed changes what people choose', () => {
  const count = (science: number) => {
    const w = new World({ ...DEFAULT_CONFIG, seed: 9, tribes: 1, perTribe: 10, size: 112, fixedHistory: true })
    for (let d = 0; d < 3; d++) w.tick(DAY)
    for (const t of w.tribes) { const ai = ensureAI(t); ai.prefs.science = science; t.storage.berries = 400 }
    let n = 0
    for (let k = 0; k < 12; k++) for (const a of w.living.filter((x) => w.isAdult(x))) if (nativeDecide(w, a).plan.some((s) => s.action === 'research')) n++
    return n
  }
  const scholars = count(1), traditional = count(-1)
  assert.ok(scholars > traditional * 1.3, `a science creed researches more (${scholars} vs ${traditional})`)
})
