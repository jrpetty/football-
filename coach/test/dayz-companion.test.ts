// The DayZ companion: replies with cards for its screen, follow-ups on the last
// topic, the tools Claude can call, the conversation loop, and the main-process
// call with its fixed tools and cached instructions.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CloudService, type CloudClient } from '../electron/cloud/service.ts'
import { DEFAULT_CLOUD } from '../shared/cloud.ts'
import { COMPANION_SYSTEM, COMPANION_TOOLS, companionTurn, localReply, runTool, type Card, type CompanionContext, type ChatBlock, type ChatMessage, type Topic } from '../src/core/dayz/companion.ts'
import { parseSetSpot } from '../src/core/dayz/assistant.ts'
import { answerLocally, classify } from '../src/core/qa/ask.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { engineFor, settings } from './helpers.ts'

const dayz = builtInProfile('dayz')
const turovo = parseSetSpot("I'm at Turovo")!
const ctx: CompanionContext = { spot: turovo, kit: [], map: 'chernarus' }
const report = engineFor(dayz, settings({ profileId: 'dayz', profileModeId: 'offline' })).report()
/** As the session does: the local answer, then the companion's reply with its card. */
function reply(q: string, topic: Topic = null, c: CompanionContext = ctx) {
  const r = { ...report, context: { ...report.context, ...(c.spot ? { spotX: c.spot.x, spotY: c.spot.y, spotLabel: c.spot.label } : {}), kit: c.kit.join(',') } }
  return localReply(q, c, dayz, topic, answerLocally(classify(q, dayz), dayz, r, []))
}
const type = (c: Card | null | undefined) => c?.type ?? null

test('every kind of answer brings its card: place, gun, comparison, item, plan, advice, kit, map, help', () => {
  const place = reply('where is typobo')!
  assert.equal(type(place.card), 'place')
  assert.equal(place.card!.type === 'place' && place.card.title, 'Turovo')
  assert.deepEqual(place.topic && place.topic.kind, 'place')
  const gun = reply('mosin range')!
  assert.equal(type(gun.card), 'gun')
  assert.ok(gun.card!.type === 'gun' && gun.card.range.some((r) => r.m === 300 && /cm high/.test(r.hold)))
  assert.equal(type(reply('mosin vs svd')!.card), 'compare')
  const item = reply('where do I find a canteen')!
  assert.equal(type(item.card), 'item')
  assert.ok(item.card!.type === 'item' && item.card.markers.length > 0 && item.card.from?.label === 'Turovo')
  const plan = reply('what now?')!
  assert.equal(type(plan.card), 'plan')
  assert.ok(plan.card!.type === 'plan' && plan.card.steps[0].title === 'Water pump' && plan.card.route.length === plan.card.steps.length + 1)
  const advice = reply('how do I treat cholera')!
  assert.equal(type(advice.card), 'advice')
  assert.ok(advice.card!.type === 'advice' && advice.card.fix)
  assert.equal(type(reply('what do I have?')!.card), 'kit')
  assert.equal(type(reply('show me the map')!.card), 'map')
  assert.equal(type(reply('help')!.card), 'help')
  const route = reply('take me to the nearest hospital')!
  assert.ok(route.card!.type === 'map' && route.card.route!.length === 2)
  const run = reply('loot run from Berezino hitting police and military')!
  assert.ok(run.card!.type === 'map' && run.card.route!.length >= 3)
  for (const r of [place, gun, item, plan, advice]) assert.doesNotMatch(r.text, BANNED_CLAIMS)
})

test('follow-ups on the last topic: take me there, show it on the map, where does it spawn', () => {
  const place = reply('where is berezino')!
  const there = reply('take me there', place.topic)!
  assert.match(there.text, /^Berezino: 4\.\d km south from Turovo\.$/)
  assert.ok(there.card!.type === 'map' && there.card.route!.length === 2)
  const shown = reply('show it on the map', place.topic)!
  assert.ok(shown.card!.type === 'map' && shown.card.focus?.label === 'Berezino')
  const item = reply('where do I find a compass')!
  const again = reply('where does it spawn', item.topic)!
  assert.match(again.text, /^Compass: spawns in/)
  // Without a spot, "take me there" asks where you are.
  assert.match(reply('take me there', place.topic, { ...ctx, spot: null })!.text, /^Where are you\?/)
  // An open question is left for Claude.
  assert.equal(reply('is it worth going to NWAF on a full server at night?'), null)
})

test('the tools: each answers from the app data, puts a card up, and set_spot / update_kit change what the app knows', () => {
  const effects = { spot: null as unknown, kit: [] as unknown[] }
  const fx = { setSpot: (s: unknown) => (effects.spot = s), setKit: (h: readonly string[], l: readonly string[]) => effects.kit.push([h, l]) }
  const run = (name: string, input: Record<string, unknown> = {}, c: CompanionContext = ctx) => runTool(name, input, c, dayz, fx)
  assert.match(run('player_state').text, /"label":"Turovo"/)
  const set = run('set_spot', { where: 'Berezino' }, { ...ctx, spot: null })
  assert.equal((effects.spot as { label: string }).label, 'Berezino')
  assert.equal(set.ctx?.spot?.label, 'Berezino')
  assert.equal(run('set_spot', { where: '135 012' }).ctx?.spot?.label, 'grid 135 012')
  assert.equal(run('update_kit', { have: ['knife'] }).card?.type, 'kit')
  assert.deepEqual(effects.kit[0], [['knife'], []])
  assert.equal(run('find_place', { name: 'typobo' }).card?.type, 'place')
  assert.equal(run('route', { to: 'Berezino' }).card?.type, 'map')
  assert.equal(run('route', { from: 'Cherno', to: 'NWAF' }).card?.type, 'map')
  assert.match(run('nearest', { kind: 'hospital' }).text, /^Hospital in Novodmitrovsk/)
  assert.equal(run('item_spawns', { item: 'morphine' }).card?.type, 'item')
  assert.match(run('item_spawns', { item: '7.62x39 ammo' }).text, /^7\.62x39mm rounds, box of 20/)
  assert.match(run('what_spawns_in', { building: 'Police' }).text, /^Most common in police stations/)
  assert.equal(run('gun_info', { gun: 'akm' }).card?.type, 'gun')
  assert.equal(run('compare_guns', { a: 'mosin', b: 'svd' }).card?.type, 'compare')
  assert.match(run('best_guns', { kind: 'sniper' }).text, /^Hardest-hitting long range/)
  assert.equal(run('survival_advice', { topic: 'cholera' }).card?.type, 'advice')
  assert.equal(run('plan_next_steps').card?.type, 'plan')
  assert.equal(run('loot_run', { request: 'from Berezino hitting a hospital and police and back' }).card?.type, 'map')
  assert.equal(run('show_map', { place: 'Elektro' }).card?.type, 'map')
  assert.match(run('plan_next_steps', {}, { ...ctx, spot: null }).text, /has not said where they are/)
  // Every tool Claude is offered has a runner.
  for (const t of COMPANION_TOOLS) assert.doesNotMatch(run(t.name, {}).text, /^Unknown tool/, t.name)
})

test('the conversation loop: Claude asks for a tool, the app runs it and shows its card, then Claude answers', async () => {
  const seen: (readonly ChatMessage[])[] = []
  const cards: Card[] = []
  const replies: { content: ChatBlock[]; stop: string }[] = [
    { content: [{ type: 'text', text: 'Let me check.' }, { type: 'tool_use', id: 't1', name: 'item_spawns', input: { item: 'canteen' } }], stop: 'tool_use' },
    { content: [{ type: 'text', text: 'Try the **hunting stands** in Turovo first.' }], stop: 'end_turn' },
  ]
  const r = await companionTurn({
    question: 'I need water storage, any ideas?',
    history: [{ question: "I'm at Turovo", answer: "Got it: you're at Turovo." }],
    ctx,
    topic: null,
    profile: dayz,
    effects: { setSpot: () => undefined, setKit: () => undefined },
    chat: async (m) => {
      seen.push([...m])
      const next = replies.shift()!
      return { ok: true, content: next.content, stop: next.stop, costUsd: 0.002 }
    },
    onCard: (c) => cards.push(c),
  })
  assert.ok(r.ok)
  assert.equal(r.ok && r.text, 'Try the hunting stands in Turovo first.')
  assert.equal(r.ok && r.costUsd, 0.004)
  assert.deepEqual(r.ok && r.tools, ['item_spawns'])
  assert.equal(cards[0].type, 'item')
  assert.equal(r.ok && r.topic?.kind, 'item')
  // The history, then the question with what the app knows; then the tool result.
  assert.equal(seen[0][0].content, "I'm at Turovo")
  assert.match(String(seen[0][2].content), /^I need water storage, any ideas\?\n\n\(App: The player said they are at Turovo\. Map: chernarus\.\)$/)
  const result = (seen[1].at(-1)!.content as ChatBlock[])[0]
  assert.ok(result.type === 'tool_result' && result.tool_use_id === 't1' && /^Canteen: spawns in/.test(result.content))
  // Errors come back as errors; a loop that never ends stops.
  const failed = await companionTurn({ question: 'hi', history: [], ctx, topic: null, profile: dayz, effects: { setSpot: () => undefined, setKit: () => undefined }, chat: async () => ({ ok: false, error: 'Rate limited by the API.' }), onCard: () => undefined })
  assert.deepEqual(failed, { ok: false, error: 'Rate limited by the API.' })
  const looping = await companionTurn({ question: 'hi', history: [], ctx, topic: null, profile: dayz, effects: { setSpot: () => undefined, setKit: () => undefined }, rounds: 3, chat: async () => ({ ok: true, content: [{ type: 'tool_use', id: 'x', name: 'player_state', input: {} }], stop: 'tool_use', costUsd: 0 }), onCard: () => undefined })
  assert.ok(!looping.ok && /too many steps/.test(looping.error))
})

test('the main-process call: fixed tools, the companion instructions and the pack, both cached; tool calls come back', async () => {
  const calls: Record<string, unknown>[] = []
  const usage = { input_tokens: 1000, output_tokens: 100, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 }
  const client = {
    beta: {
      messages: {
        create: async (p: Record<string, unknown>) => {
          calls.push(p)
          return { usage, stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 'a', name: 'find_place', input: { name: 'Typobo' } }] }
        },
      },
    },
    models: {},
  } as unknown as CloudClient
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => ({ ...DEFAULT_CLOUD, enabled: true }), () => client)
  const r = await svc.companionChat(dayz, [{ role: 'user', content: 'where is typobo' }], 'reference text')
  assert.ok(r.ok)
  assert.deepEqual(r.ok && r.content, [{ type: 'tool_use', id: 'a', name: 'find_place', input: { name: 'Typobo' } }])
  assert.equal(r.ok && r.stop, 'tool_use')
  const req = calls[0] as { tools: { name: string; cache_control?: unknown }[]; system: { text: string; cache_control?: unknown }[] }
  assert.deepEqual(req.tools.map((t) => t.name), COMPANION_TOOLS.map((t) => t.name))
  assert.ok(req.tools.at(-1)!.cache_control && req.system[0].cache_control)
  assert.ok(req.system[0].text.startsWith(COMPANION_SYSTEM.slice(0, 60)))
  // Switched off: no call.
  const off = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => ({ ...DEFAULT_CLOUD, enabled: false }), () => client)
  assert.ok(!(await off.companionChat(dayz, [{ role: 'user', content: 'hi' }], '')).ok)
  assert.equal(calls.length, 1)
})
