// A model-driven leader conducts real diplomacy. The whole pipeline runs as it
// does with Claude, DeepSeek or a local model — mind selection, the prompt, the
// request, parsing, planning, the "diplomacy" action and its consequences —
// with only the network replaced by a stand-in that answers like a model.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { World, DEFAULT_CONFIG } from '../src/sim/world.ts'
import { HOUR } from '../src/sim/time.ts'
import { MindManager } from '../src/ai/mind.ts'
import { NATIVE_ID, type MindProfile } from '../src/ai/providers.ts'
import { pactBetween, warBetween } from '../src/sim/diplomacy.ts'

const MOCK: MindProfile = {
  id: 'p_mock', label: 'Stand-in model', kind: 'openai', vendor: 'custom', baseUrl: 'http://model.test/v1', apiKey: 'test-key',
  model: 'stand-in', jsonMode: true, useProxy: false, priceIn: 1, priceOut: 2,
}

function setup(seed: number) {
  const w = new World({ ...DEFAULT_CONFIG, seed, tribes: 3 })
  const [A, B] = w.tribes
  const k = A.id < B.id ? `${A.id}|${B.id}` : `${B.id}|${A.id}`
  w.dip.contact[k] = w.time
  A.model = MOCK.id // this people is led by the model; everyone else thinks with the Genesis Mind
  const m = new MindManager()
  m.update({ enabled: true, nativeCrowd: true, model: NATIVE_ID, profiles: [MOCK], maxMinds: 3, maxConcurrent: 4, maxPerMinute: 200, budgetUsd: 5 })
  m.attach(w)
  const leader = w.agentById.get(A.leaderId)!
  return { w, m, A, B, leader }
}

/** Answer every request like a model would: the leader conducts diplomacy, anyone else rests. */
function mockFetch(leaderName: string, diplomacy: string, sent: string[]) {
  return (async (_url: string, init: { body: string }) => {
    const body = JSON.parse(init.body) as { messages: { role: string; content: string }[]; response_format?: unknown }
    const user = body.messages[1].content
    sent.push(user)
    const isLeader = user.includes(`# You are ${leaderName}`)
    const decision = isLeader
      ? { thought: 'Our neighbours matter more than our pride.', feeling: 'resolute', goal: 'Keep my people safe.', say: 'Hear me, all of you.', say_to: 'everyone', plan: [{ action: 'diplomacy', target: diplomacy.split('|')[0], amount: 0, text: diplomacy.split('|')[1] }] }
      : { thought: 'A quiet hour.', feeling: 'calm', goal: 'Live well.', say: '', say_to: '', plan: [{ action: 'rest', target: '', amount: 1, text: '' }] }
    return {
      ok: true, status: 200,
      json: async () => ({ choices: [{ message: { content: JSON.stringify(decision) }, finish_reason: 'stop' }], usage: { prompt_tokens: 2400, completion_tokens: 300 } }),
    }
  }) as unknown as typeof fetch
}

const settle = () => new Promise((r) => setTimeout(r, 20))

for (const [kind, words, check] of [
  ['alliance', 'alliance: Let our peoples stand as one.', 'alliance'],
  ['war', 'war: They have stolen from us for the last time.', 'war'],
] as const) {
  test(`a model-led people can make ${kind}`, async () => {
    const { w, m, A, B, leader } = setup(kind === 'war' ? 31 : 29)
    if (kind === 'alliance') { A.relations[B.id] = 80; B.relations[A.id] = 80 } // close friends, beyond the reach of a stray border quarrel
    const sent: string[] = []
    const real = globalThis.fetch
    globalThis.fetch = mockFetch(leader.name, `${B.name}|${words}`, sent)
    try {
      m.tick()
      await settle()
      assert.equal(m.mindOf(leader), MOCK.id, 'the leader thinks with the model')
      assert.ok(w.aiLeads(leader), 'the sim leaves this leader’s statecraft to the model')
      const other = w.members(B.id).find((x) => w.isAdult(x))!
      assert.equal(m.mindOf(other), NATIVE_ID, 'everyone else thinks with the Genesis Mind')
      const prompt = sent.find((u) => u.includes(`# You are ${leader.name}`))
      assert.ok(prompt, 'the leader was asked to think')
      assert.match(prompt!, /Other peoples you know:/)
      assert.match(prompt!, new RegExp(B.name))
      assert.match(prompt!, /You lead your people/)
      assert.ok(leader.plan.some((s) => s.action === 'diplomacy') || leader.task?.action === 'diplomacy', 'the reply became a plan')
      assert.ok(m.stats.ok >= 1 && m.stats.cost > 0, 'the call was counted and costed')
      for (let h = 0; h < 36 && !(check === 'alliance' ? pactBetween(w, A.id, B.id, 'alliance') : warBetween(w, A.id, B.id)); h++) { w.tick(HOUR); m.tick(); await settle() }
      if (check === 'alliance') assert.ok(pactBetween(w, A.id, B.id, 'alliance'), 'the alliance was sworn')
      else assert.ok(warBetween(w, A.id, B.id), 'war was declared')
      assert.ok(w.chronicle.some((e) => e.text.includes(words.split(': ')[1].slice(0, 20))), 'the leader’s own words went into the record')
    } finally {
      globalThis.fetch = real
    }
  })
}
