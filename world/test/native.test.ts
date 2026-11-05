// The built-in Genesis Mind: with no key and no network, it must steer people
// to survive and grow, speak and plan in words, lead, and answer questions.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { World, DEFAULT_CONFIG } from '../src/sim/world.ts'
import { YEAR } from '../src/sim/time.ts'
import { MindManager } from '../src/ai/mind.ts'
import { NATIVE_ID } from '../src/ai/providers.ts'
import { nativeDecide, nativeReflect, nativeInterview } from '../src/ai/native.ts'
import { ACTION_KINDS } from '../src/sim/types.ts'

test('a decision is a full thought, feeling, goal and a valid multi-step plan', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 7, size: 96 })
  for (const a of w.living.filter((x) => w.isAdult(x)).slice(0, 8)) {
    const d = nativeDecide(w, a)
    assert.ok(d.thought.length > 10, 'thinks in words')
    assert.ok(d.feeling.length > 0)
    assert.ok(d.goal.length > 5, 'has a life goal')
    assert.ok(d.plan.length >= 1 && d.plan.length <= 6)
    for (const s of d.plan) assert.ok((ACTION_KINDS as string[]).includes(s.action), `valid action ${s.action}`)
  }
})

test('a world run entirely by the Genesis Mind thrives, talks, governs and keeps history', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 9, tribes: 3, popCap: 100 })
  const m = new MindManager()
  m.update({ enabled: true, nativeCrowd: true, model: NATIVE_ID, chronicler: true })
  m.attach(w)
  const start = w.living.length
  const end = w.time + 8 * YEAR
  while (w.time < end) { w.tick(360); m.tick() }
  assert.ok(m.stats.native > 1000, `many decisions (${m.stats.native})`)
  assert.ok(w.living.length >= start, `the people grew: ${start} → ${w.living.length}`)
  const live = w.tribes.filter((t) => t.alive)
  assert.ok(live.some((t) => t.laws.length > 0 || t.values), 'leaders made decrees')
  assert.ok(live.every((t) => w.techs(t.id).size >= 10), 'every people advanced')
  assert.ok(w.chronicle.some((e) => e.kind === 'speech'), 'people spoke')
  assert.ok(w.chapters.length >= 5, 'the historian wrote chapters')
  const a = w.living.find((x) => w.isAdult(x))!
  assert.ok(nativeInterview(w, a, 'What do you fear?').length > 10)
  assert.ok(nativeReflect(w, a).insights.length >= 1)
  assert.equal(m.mindOf(a), NATIVE_ID)
})
