// Every scenario must play through to an ending — on instinct alone and with
// (mock) AI minds — and never produce the same run twice.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { SCENARIOS } from '../src/engine/scenarios.ts'
import { makePerson } from '../src/engine/cast.ts'
import { Run, type MindHost } from '../src/engine/engine.ts'
import { systemFor, buildPersonPrompt } from '../src/engine/prompts.ts'

const instinct: MindHost = { canThink: () => false, think: async () => '', canDirect: () => false, direct: async () => '' }

function cast(sc: (typeof SCENARIOS)[0]) {
  const taken = new Set<string>()
  return sc.roles.map((r, i) => makePerson(i + 1, r, sc.places[0].id, taken))
}

test('scenario data is consistent', () => {
  assert.equal(SCENARIOS.length, 18)
  for (const sc of SCENARIOS) {
    assert.equal(sc.roles.length, 4, sc.id)
    const places = new Set(sc.places.map((p) => p.id))
    const stats = new Set(sc.stats.map((s) => s.id))
    for (const m of sc.moves) {
      for (const at of m.at ?? []) assert.ok(places.has(at), `${sc.id}.${m.id} at ${at}`)
      for (const k of Object.keys(m.effects)) assert.ok(stats.has(k), `${sc.id}.${m.id} effect ${k}`)
    }
    for (const e of sc.events) for (const k of Object.keys(e.effects ?? {})) assert.ok(stats.has(k), `${sc.id} event ${e.id} ${k}`)
    for (const k of Object.keys(sc.upkeep)) assert.ok(stats.has(k), `${sc.id} upkeep ${k}`)
    if (sc.win) assert.ok(stats.has(sc.win.stat))
  }
})

test('every scenario plays to an ending on instinct', async () => {
  for (const sc of SCENARIOS) {
    const run = new Run(sc, cast(sc))
    for (let k = 0; k < sc.beats + 2 && !run.over; k++) await run.step(instinct)
    assert.ok(run.over, `${sc.id} ended`)
    assert.ok(run.ending, `${sc.id} has an ending`)
    assert.ok(run.lines.length > sc.beats / 2, `${sc.id} produced a story`)
    assert.ok(run.script().includes(sc.title))
  }
})

test('mock AI minds drive speech, movement, opinions and stances', async () => {
  const sc = SCENARIOS.find((s) => s.id === 'jury')!
  const people = cast(sc)
  const host: MindHost = {
    canThink: () => true,
    think: async (p, system, user) => {
      assert.ok(system.includes('The Verdict'))
      assert.ok(user.includes(`# You are ${p.name}`))
      const other = people.find((o) => o !== p)!
      return JSON.stringify({ thought: 'The timestamp bothers me.', feeling: 'doubtful', say: `${other.name}, look at the timestamp.`, say_to: other.name, move: 'examine', go_to: '', detail: 'squinting at the stills', stance: 'Not guilty', opinions: [{ name: other.name, delta: 12, why: 'they listened' }] })
    },
    canDirect: () => true,
    direct: async () => JSON.stringify({ narration: 'Rain streaks the windows as four people stare at the same grainy photo.', adjustments: [{ stat: 'civility', delta: 3 }], personal: [] }),
  }
  const run = new Run(sc, people)
  await run.step(host)
  assert.ok(people.every((p) => p.place === 'evidence'), 'moved to do the move')
  assert.ok(run.lines.some((l) => l.kind === 'say'))
  assert.ok(run.lines.some((l) => l.kind === 'narration'))
  for (let k = 0; k < 4 && !run.over; k++) await run.step(host)
  assert.ok(run.over, 'unanimous verdict ends the deliberation')
  assert.equal(run.ending?.title, 'Unanimous: Not guilty.')
  assert.ok(Object.values(people[0].opinions).some((o) => o.trust > 10))
})

test('prompts are rich but compact', () => {
  for (const sc of SCENARIOS) {
    const run = new Run(sc, cast(sc))
    const u = buildPersonPrompt(run, run.people[0])
    assert.ok(systemFor(sc).length < 9000, sc.id)
    assert.ok(u.length < 6000, sc.id)
  }
})

test('casts differ every time', () => {
  const sc = SCENARIOS[0]
  const a = cast(sc).map((p) => p.name + p.age).join()
  const b = cast(sc).map((p) => p.name + p.age).join()
  const c = cast(sc).map((p) => p.name + p.age).join()
  assert.ok(new Set([a, b, c]).size > 1)
})
