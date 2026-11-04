// The AI layer: prompts describe the world truthfully, decisions become plans
// and speech, and loosely-worded model output still maps onto real actions.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { World, DEFAULT_CONFIG } from '../src/sim/world.ts'
import { DAY } from '../src/sim/time.ts'
import { buildContext, SYSTEM_PROMPT, DECISION_SCHEMA } from '../src/ai/prompts.ts'
import { MindManager } from '../src/ai/mind.ts'
import { normalizeAction, normalizeItem, normalizeBuilding, normalizeTech } from '../src/sim/actions.ts'
import { costOf, parseJson } from '../src/ai/client.ts'
import { serialize, deserialize } from '../src/sim/save.ts'

test('the system prompt is stable and the schema is strict', () => {
  assert.ok(SYSTEM_PROMPT.length > 8000, 'rich enough to be worth caching')
  assert.ok(!/\d{2}:\d{2}|Year \d/.test(SYSTEM_PROMPT.slice(0, 400)), 'no volatile values at the top of the cached prefix')
  const props = (DECISION_SCHEMA as { properties: Record<string, unknown>; required: string[]; additionalProperties: boolean })
  assert.equal(props.additionalProperties, false)
  assert.deepEqual([...props.required].sort(), Object.keys(props.properties).sort())
})

test('context describes the person, their tribe and surroundings', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 11, size: 112 })
  for (let d = 0; d < 6; d++) w.tick(DAY)
  const a = w.living.find((x) => w.isAdult(x))!
  const c = buildContext(w, a)
  assert.match(c, new RegExp(`# You are ${a.name}`))
  assert.match(c, /# Your tribe: the /)
  assert.match(c, /Ideas within reach:/)
  assert.ok(c.length < 12000, `context stays compact (${c.length} chars)`)
})

test('a decision becomes thought, speech and a plan', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 12, size: 112 })
  const m = new MindManager()
  m.attach(w)
  const [a, b] = w.living
  b.x = a.x + 1; b.y = a.y
  m.apply(w, a, {
    thought: 'We need fire before night.', feeling: 'urgent', goal: 'Keep everyone warm.',
    say: 'Help me find wood!', say_to: b.name,
    plan: [{ action: 'gather', target: 'wood', amount: 6, text: '' }, { action: 'build', target: 'campfire', amount: 0, text: '' }],
  }, 'claude-opus-5-5')
  assert.equal(a.thought, 'We need fire before night.')
  assert.equal(a.goal, 'Keep everyone warm.')
  assert.equal(a.plan.length, 2)
  assert.equal(a.speech?.text, 'Help me find wood!')
  assert.ok(b.heard.some((h) => h.text === 'Help me find wood!'), 'the listener heard it')
  assert.equal(m.feed.length, 1)
})

test('loosely worded model output maps onto real actions and things', () => {
  assert.equal(normalizeAction('Chop'), 'gather')
  assert.equal(normalizeAction('propose'), 'court')
  assert.equal(normalizeAction('nonsense'), 'rest')
  assert.equal(normalizeItem('logs'), 'wood')
  assert.equal(normalizeItem('iron', true), 'iron_ore')
  assert.equal(normalizeItem('Berries'), 'berries')
  assert.equal(normalizeBuilding('Fire pit'), 'campfire')
  assert.equal(normalizeBuilding('town center'), 'town_hall')
  assert.equal(normalizeTech('Bronze Working'), 'bronze_working')
  assert.equal(normalizeTech('pottery'), 'pottery')
})

test('cost accounting and tolerant JSON parsing', () => {
  const c = costOf('claude-opus-5-5', { input: 1_000_000, output: 0, cacheRead: 0, cacheWrite: 0 })
  assert.equal(c, 4)
  assert.deepEqual(parseJson('noise {"a":1} noise'), { a: 1 })
  assert.equal(parseJson('nope'), null)
})

test('worlds survive a save/load round trip', () => {
  const w = new World({ ...DEFAULT_CONFIG, seed: 13, size: 96 })
  for (let d = 0; d < 4; d++) w.tick(DAY)
  const w2 = deserialize(JSON.parse(JSON.stringify(serialize(w))))
  assert.equal(w2.living.length, w.living.length)
  assert.equal(w2.buildings.length, w.buildings.length)
  assert.deepEqual(Array.from(w2.biome.slice(0, 500)), Array.from(w.biome.slice(0, 500)))
  w2.tick(DAY) // and keeps running
  assert.ok(w2.time > w.time)
})
