// Cloud vision and conversation, without the network: the response contract
// and its validator, the sampler, cost estimates, the main-process service
// with a stand-in SDK client, and the key store.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, readdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import Anthropic from '@anthropic-ai/sdk'
import { KeyStore, type Cipher } from '../electron/cloud/keystore.ts'
import { CloudService, visionSchema, type CloudClient } from '../electron/cloud/service.ts'
import { DEFAULT_CLOUD } from '../shared/cloud.ts'
import { validateVisionResponse, visionSystemPrompt, visionUserText, type VisionResponse } from '../src/core/vision/contract.ts'
import { costOf, estimatePerHour } from '../src/core/vision/cost.ts'
import { VisionSampler } from '../src/core/vision/sampler.ts'
import { AGREEMENT_MIN_CHECKED, AUDIT_EVERY, FAST_VISION_MODEL } from '../src/core/vision/tiers.ts'
import { builtInProfile } from '../src/profiles/index.ts'

const fc = builtInProfile('ea-fc')
const aoe = builtInProfile('aoe2-de')

// --- Contract ------------------------------------------------------------------------------

test('the system prompt carries the pack schema, zones and honesty rules; context goes in the user turn', () => {
  const p = visionSystemPrompt(fc)
  assert.match(p, /Never infer hidden information/)
  assert.match(p, /homeScore/)
  assert.match(p, /box-left = inside the box, left side/)
  assert.match(p, /attribute buildUp \(one of cross, cutback/)
  assert.match(visionUserText(fc, { side: 'away', teamName: 'MCI' }), /Your side on the scoreboard: away\. Your team .*: MCI\./)
  assert.equal(visionUserText(fc, {}), 'Describe this frame.')
})

test('the validator keeps only what the pack declares, within range', () => {
  const raw: VisionResponse = {
    phase: 'round',
    phaseConfidence: 1.7, // clamped
    visibleOutcome: null,
    visibleHealth: null,
    state: [
      { key: 'homeScore', value: '2', confidence: 0.9 },
      { key: 'awayScore', value: '99', confidence: 0.9 }, // out of range (max 30)
      { key: 'enemyPositions', value: 'left', confidence: 0.9 }, // not in the pack: hidden info is not a key
      { key: 'passAccuracyPct', value: '81%', confidence: 0.8 },
    ],
    events: [
      { type: 'goal', zone: 'box-left', attributes: [{ key: 'scorer', value: 'opponent' }, { key: 'buildUp', value: 'telepathy' }], confidence: 0.8, evidence: 'GOAL graphic' },
      { type: 'goal', zone: 'penalty-spot', attributes: [], confidence: 0.8, evidence: '' }, // unknown zone dropped, event kept
      { type: 'wallhack', zone: null, attributes: [], confidence: 1, evidence: '' },
    ],
    unreadable: 'clock',
  }
  const { observations, stats } = validateVisionResponse(raw, fc, 1000, 2500, 't')
  const state = Object.fromEntries(observations.filter((o) => o.kind === 'state').map((o) => [o.eventType, o.value]))
  assert.deepEqual(state, { phase: 'round', homeScore: 2, passAccuracyPct: 81 })
  assert.equal(observations.find((o) => o.eventType === 'phase')!.confidence, 1)
  const events = observations.filter((o) => o.kind === 'event')
  assert.equal(events.length, 2)
  assert.equal(events[0].zone, 'box-left')
  assert.deepEqual(events[0].attributes, { scorer: 'opponent' })
  assert.equal(events[1].zone, undefined)
  assert.ok(observations.every((o) => o.freshnessMs === 1500 && o.timestampMs === 1000 && o.source === 'vision-model'))
  for (const d of ['awayScore="99"', 'unknown state "enemyPositions"', 'attribute buildUp="telepathy"', 'zone "penalty-spot"', 'unknown event "wallhack"']) {
    assert.ok(stats.dropped.some((x) => x.includes(d)), `dropped should mention ${d}: ${stats.dropped.join('; ')}`)
  }
})

test('the validator survives garbage', () => {
  for (const raw of [null, 42, 'text', { state: 'nope', events: {} }, { phase: 'boss-fight' }]) {
    const r = validateVisionResponse(raw, aoe, 0, 0, 'g')
    assert.ok(Array.isArray(r.observations))
  }
  const enumCheck = validateVisionResponse({ state: [{ key: 'age', value: 'Feudal', confidence: 0.9 }, { key: 'age', value: 'space', confidence: 0.9 }] }, aoe, 0, 0, 'e')
  assert.deepEqual(enumCheck.observations.map((o) => o.value), ['feudal'])
})

test('the structured-output schema uses the pack enums', () => {
  const schema = visionSchema(fc)
  assert.ok(schema.safeParse({ phase: 'round', phaseConfidence: 0.9, visibleOutcome: null, visibleHealth: null, state: [], events: [], unreadable: '', screen: null }).success)
  assert.ok(!schema.safeParse({ phase: 'round', phaseConfidence: 0.9, visibleOutcome: null, visibleHealth: null, state: [{ key: 'xray', value: '1', confidence: 1 }], events: [], unreadable: '' }).success)
})

// --- Sampler and cost ------------------------------------------------------------------------

test('the sampler keeps one request in flight, respects the interval, skips still frames and backs off', () => {
  const s = new VisionSampler({ intervalMs: 2500, minChange: 2, maxSkipMs: 10_000 })
  const a = new Float32Array(144).fill(50)
  const b = new Float32Array(144).fill(80)
  assert.equal(s.shouldSample(0, a), true)
  s.sending(0, a)
  assert.equal(s.shouldSample(5000, b), false, 'in flight')
  s.done(1000, true)
  assert.equal(s.shouldSample(2000, b), false, 'interval')
  assert.equal(s.shouldSample(3000, a), false, 'unchanged frame skipped')
  assert.equal(s.shouldSample(3000, b), true)
  s.sending(3000, b)
  s.done(4000, false)
  assert.equal(s.shouldSample(6000, a), false, 'backing off after an error')
  assert.equal(s.shouldSample(20_000, a), true)
  s.setPaused(true)
  assert.equal(s.shouldSample(60_000, a), false)
})

test('cost estimates reflect model prices', () => {
  const opus = estimatePerHour('claude-opus-5-5', 2500, 1024, 576)
  const haiku = estimatePerHour('claude-haiku-5-5', 2500, 1024, 576)
  assert.ok(opus > 5 && opus < 30, `opus ~$${opus.toFixed(2)}/h`)
  assert.ok(haiku < 1, `haiku ~$${haiku.toFixed(2)}/h`)
  assert.ok(Math.abs(costOf('claude-opus-5-5', { input_tokens: 1_000_000, output_tokens: 0 }) - 4) < 1e-9)
})

// --- Service with a stand-in client -------------------------------------------------------------

function fakeClient(behaviour: { parse?: () => unknown; create?: (p: { tools?: unknown[] }) => unknown }) {
  const calls: unknown[] = []
  const client = {
    beta: {
      messages: {
        parse: async (params: unknown) => {
          calls.push(params)
          if (behaviour.parse) return behaviour.parse()
          throw new Error('no parse')
        },
        create: async (params: { tools?: unknown[] }) => {
          calls.push(params)
          if (behaviour.create) return behaviour.create(params)
          throw new Error('no create')
        },
      },
    },
  } as unknown as CloudClient
  return { client, calls }
}

const usage = { input_tokens: 1000, output_tokens: 300, cache_read_input_tokens: 1500, cache_creation_input_tokens: 0 }
const parsed: VisionResponse = { phase: 'round', phaseConfidence: 0.9, visibleOutcome: null, visibleHealth: null, state: [], events: [], unreadable: '' }

test('the service refuses to call out when switched off, without a key, or over the cap', async () => {
  let settings = { ...DEFAULT_CLOUD, smartReading: false }
  let key: string | null = null
  const { client, calls } = fakeClient({ parse: () => ({ usage, stop_reason: 'end_turn', parsed_output: parsed }) })
  const svc = new CloudService(() => key, () => settings, () => client)
  const img = { base64: 'AAAA', mediaType: 'image/jpeg' as const }

  assert.match((await svc.analyzeFrame(fc, img, {})).ok === false ? 'off' : '', /off/)
  settings = { ...settings, enabled: true }
  const noKey = await svc.analyzeFrame(fc, img, {})
  assert.ok(!noKey.ok && /No API key/.test(noKey.error))
  key = 'sk-ant-test-0000000000000000000000'
  const ok = await svc.analyzeFrame(fc, img, { side: 'home' })
  assert.ok(ok.ok && ok.costUsd > 0)
  assert.equal(calls.length, 1)

  // The request: fixed model, low effort, cached system prompt, refusal fallback, image + context.
  const req = calls[0] as Record<string, unknown> & { messages: { content: { type: string }[] }[]; system: { cache_control?: unknown }[] }
  assert.equal(req.model, 'claude-opus-5-5')
  assert.deepEqual(req.betas, ['server-side-fallback-2026-07-01'])
  assert.equal(req.fallbacks, 'default')
  assert.equal((req.output_config as { effort: string }).effort, 'low')
  assert.ok(req.system[0].cache_control)
  assert.deepEqual(req.messages[0].content.map((c) => c.type), ['image', 'text'])

  settings = { ...settings, sessionCapUsd: 0.0001 }
  const capped = await svc.analyzeFrame(fc, img, {})
  assert.ok(!capped.ok && /cap/.test(capped.error))
  assert.equal(calls.length, 1, 'no call made over the cap')
})

test('Haiku requests carry no fallback parameters; refusals and rate limits are reported', async () => {
  const settings = { ...DEFAULT_CLOUD, enabled: true, visionModel: 'claude-haiku-5-5' }
  let mode: 'refuse' | 'limit' = 'refuse'
  const { client, calls } = fakeClient({
    parse: () => {
      if (mode === 'limit') throw new Anthropic.RateLimitError(429, {}, 'slow down', new Headers({ 'retry-after': '7' }))
      return { usage, stop_reason: 'refusal', parsed_output: null }
    },
  })
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => settings, () => client)
  const refused = await svc.analyzeFrame(aoe, { base64: 'AA', mediaType: 'image/jpeg' }, {})
  assert.ok(!refused.ok && /declined/.test(refused.error))
  assert.equal((calls[0] as { fallbacks?: unknown }).fallbacks, undefined)
  mode = 'limit'
  const limited = await svc.analyzeFrame(aoe, { base64: 'AA', mediaType: 'image/jpeg' }, {})
  assert.ok(!limited.ok && limited.retryAfterMs === 7000)
})

test('answers and research go through the same gate; research collects cited sources', async () => {
  const settings = { ...DEFAULT_CLOUD, enabled: true }
  const { client, calls } = fakeClient({
    create: (p) =>
      p.tools
        ? {
            usage,
            stop_reason: 'end_turn',
            content: [
              { type: 'web_search_tool_result', content: [{ type: 'web_search_result', url: 'https://example.org/guide', title: 'Guide' }] },
              { type: 'text', text: 'Fundamentals: keep villagers producing.' },
            ],
          }
        : { usage, stop_reason: 'end_turn', content: [{ type: 'text', text: 'In this session I saw three goals from the left.' }] },
  })
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => settings, () => client)
  const answer = await svc.ask(fc, 'Where do I concede?', '{"heatmaps":[]}')
  assert.ok(answer.ok && /three goals/.test(answer.text))
  const sys = (calls[0] as { system: { text: string }[] }).system[0].text
  assert.match(sys, /never invent statistics|Never invent statistics/)
  assert.match(sys, /Hold your defensive shape/)

  const research = await svc.research('Age of Empires II', 'build orders')
  assert.ok(research.ok)
  assert.deepEqual(research.brief.sources, [{ url: 'https://example.org/guide', title: 'Guide' }])
  assert.equal((calls[1] as { tools: { type: string }[] }).tools[0].type, 'web_search_20260209')
})

// --- Key store ------------------------------------------------------------------------------------

const fakeCipher = (available: boolean): Cipher => ({
  available,
  encrypt: (t) => Buffer.from(`ENC:${Buffer.from(t).toString('base64')}`),
  decrypt: (b) => Buffer.from(b.toString().slice(4), 'base64').toString(),
})

test('the API key is stored only encrypted, never in plaintext, and only its last 4 characters are reported', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'keys-'))
  const ks = new KeyStore(join(dir, 'secret.bin'), fakeCipher(true))
  await assert.rejects(ks.set('not-a-key'))
  const key = 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123'
  const status = await ks.set(key)
  assert.deepEqual(status, { set: true, last4: '0123', persisted: true, encryptionAvailable: true })
  assert.equal((await readFile(join(dir, 'secret.bin'), 'utf8')).includes(key), false)
  const again = new KeyStore(join(dir, 'secret.bin'), fakeCipher(true))
  await again.load()
  assert.equal(again.get(), key)

  const memOnly = new KeyStore(join(dir, 'other.bin'), fakeCipher(false))
  assert.equal((await memOnly.set(key)).persisted, false)
  assert.ok(!(await readdir(dir)).includes('other.bin'), 'no plaintext fallback file')
  await ks.set(null)
  assert.ok(!(await readdir(dir)).includes('secret.bin'))
})

test('the key check is a free model listing, works with cloud features off, and reports a rejected key', async () => {
  let listed = 0
  const good = { models: { list: async () => ((listed++), { data: [{ id: 'claude-opus-5-5' }, { id: 'claude-haiku-5-5' }] }) }, beta: {} } as unknown as CloudClient
  const s = new CloudService(() => 'sk-ant-test-0000', () => ({ ...DEFAULT_CLOUD, enabled: false }), () => good)
  assert.deepEqual(await s.testKey(), { ok: true, models: ['claude-opus-5-5', 'claude-haiku-5-5'] })
  assert.equal(listed, 1)
  assert.equal(s.spend.usd, 0, 'listing models costs nothing')
  assert.deepEqual(await new CloudService(() => null, () => DEFAULT_CLOUD, () => good).testKey(), { ok: false, error: 'No API key is set.' })
  const bad = { models: { list: async () => { throw new Error('boom') } }, beta: {} } as unknown as CloudClient
  assert.deepEqual(await new CloudService(() => 'sk-ant-x', () => DEFAULT_CLOUD, () => bad).testKey(), { ok: false, error: 'boom' })
})

test("the coach's review: structured output with the playbook in the cached system prompt", async () => {
  const mw4 = builtInProfile('cod-mw4')
  const review = { headline: 'Most deaths came in the open.', strengths: [], focus: [{ evidence: '8 of 12 deaths in the open.', principle: 'Fight from cover.', fix: 'Take fights from cover.', drill: null, playbook: 'fight-from-cover' }], nextMatch: 'Cover first.' }
  const { client, calls } = fakeClient({ parse: () => ({ usage, stop_reason: 'end_turn', parsed_output: review }) })
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => ({ ...DEFAULT_CLOUD, enabled: true }), () => client)
  const r = await svc.reviewMatch(mw4, '{"session":{}}', '')
  assert.ok(r.ok)
  assert.deepEqual(r.review, review)
  const req = calls[0] as { system: { text: string; cache_control?: unknown }[]; output_config: { format?: unknown; effort: string }; messages: { content: string }[] }
  assert.ok(req.system[0].cache_control)
  assert.match(req.system[0].text, /Coaching playbook/)
  assert.match(req.system[0].text, /\[fight-from-cover\] Fight from cover/)
  assert.ok(req.output_config.format, 'structured output')
  assert.match(req.messages[0].content, /probable, not certain/)
})

test('sampler: with two requests allowed, a frame goes out every interval even while an answer is slow', () => {
  const s = new VisionSampler({ intervalMs: 3000, minChange: 0, maxSkipMs: 12_000, maxInFlight: 2 })
  const g = (v: number) => new Float32Array(64).fill(v)
  assert.equal(s.shouldSample(0, g(1)), true)
  s.sending(0, g(1))
  assert.equal(s.shouldSample(1500, g(2)), false, 'the interval still applies')
  assert.equal(s.shouldSample(3000, g(2)), true, 'the first answer is still out')
  s.sending(3000, g(2))
  assert.equal(s.shouldSample(6000, g(3)), false, 'never more than two at once')
  s.done(6500, true)
  assert.equal(s.shouldSample(6500, g(3)), true)
})

// --- Smart reading ---------------------------------------------------------------------------

const aoe4 = builtInProfile('aoe4')
const reading = (over: Partial<VisionResponse> = {}): VisionResponse => ({
  phase: 'round',
  phaseConfidence: 0.9,
  visibleOutcome: null,
  visibleHealth: null,
  state: [
    { key: 'food', value: '200', confidence: 0.9 },
    { key: 'villagers', value: '21', confidence: 0.9 },
  ],
  events: [],
  unreadable: '',
  screen: { name: 'In match', kind: 'gameplay', summary: '' },
  ...over,
})

test('smart reading: the fast model reads every look; the chosen model only the looks that need it, its reference cached for an hour', async () => {
  const settings = { ...DEFAULT_CLOUD, enabled: true }
  let detailReply = reading({ state: [{ key: 'food', value: '200', confidence: 0.95 }, { key: 'villagers', value: '21', confidence: 0.95 }] })
  let fastReply = reading()
  const { client, calls } = fakeClient({
    parse: () => {
      const req = calls[calls.length - 1] as { model: string }
      return { usage, stop_reason: 'end_turn', parsed_output: req.model === FAST_VISION_MODEL ? fastReply : detailReply }
    },
  })
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => settings, () => client)
  const img = { base64: 'AAAA', mediaType: 'image/jpeg' as const }
  const known = { screens: '- In match (gameplay)\n- Main menu (menu): start here' }

  // An ordinary look: the fast model only, thinking off, no fallback parameters.
  const plain = await svc.analyzeFrame(aoe4, img, {}, known)
  assert.ok(plain.ok)
  assert.equal(calls.length, 1)
  const fastReq = calls[0] as { model: string; thinking?: { type: string }; betas?: unknown; system: { cache_control: { ttl?: string } }[] }
  assert.equal(fastReq.model, FAST_VISION_MODEL)
  assert.deepEqual(fastReq.thinking, { type: 'disabled' })
  assert.equal(fastReq.betas, undefined)
  assert.equal(fastReq.system[0].cache_control.ttl, undefined)

  // A fight: the same frame goes on to the chosen model; its answer is used.
  fastReply = reading({ events: [{ type: 'fight', zone: null, attributes: [], confidence: 0.8, evidence: 'armies clash' }] })
  detailReply = reading({ events: [{ type: 'fight', zone: null, attributes: [{ key: 'trend', value: 'losing' }], confidence: 0.9, evidence: 'army fighting' }] })
  const fight = await svc.analyzeFrame(aoe4, img, {}, known)
  assert.ok(fight.ok && fight.response.events[0].attributes[0].value === 'losing')
  assert.equal(calls.length, 3)
  const detailReq = calls[2] as { model: string; system: { cache_control: { ttl?: string } }[] }
  assert.equal(detailReq.model, 'claude-opus-5-5')
  assert.equal(detailReq.system[0].cache_control.ttl, '1h')

  // Enemy units seen, a new menu, a result: each sent on. A known screen is not.
  fastReply = reading({ state: [{ key: 'enemyUnits', value: '6 Longbowmen', confidence: 0.8 }] })
  await svc.analyzeFrame(aoe4, img, {}, known)
  fastReply = reading({ phase: 'menu', state: [], screen: { name: 'Civilisation select', kind: 'menu', summary: 'Pick a civilisation.' } })
  await svc.analyzeFrame(aoe4, img, {}, known)
  fastReply = reading({ phase: 'menu', state: [], screen: { name: 'Main menu', kind: 'menu', summary: '' } })
  await svc.analyzeFrame(aoe4, img, {}, known)
  fastReply = reading({ phase: 'match_end', visibleOutcome: 'success' })
  await svc.analyzeFrame(aoe4, img, {}, known)
  const tiers = svc.spend.vision!.tiers!
  assert.equal(tiers.fast, 6)
  assert.equal(tiers.detail, 4)
  assert.deepEqual(tiers.reasons, { event: 1, reading: 1, 'new screen': 1, result: 1 })
  assert.equal(tiers.off, null)
  assert.ok(tiers.checked > 0 && tiers.agreed === tiers.checked)

  // A regular check: one look in AUDIT_EVERY goes to both even when nothing needs it.
  fastReply = reading()
  const before = calls.length
  for (let i = tiers.fast; i < AUDIT_EVERY; i++) await svc.analyzeFrame(aoe4, img, {}, known)
  assert.equal(calls.length - before, AUDIT_EVERY - tiers.fast + 1, 'one extra call: the check')
  assert.equal(svc.spend.vision!.tiers!.reasons.check, 1)
})

test('smart reading fails closed: when the fast model disagrees too often, every look goes to the chosen model; a failed detail look keeps the fast one', async () => {
  const settings = { ...DEFAULT_CLOUD, enabled: true, visionModel: 'claude-sonnet-5-5' }
  let detailFails = false
  const { client, calls } = fakeClient({
    parse: () => {
      const req = calls[calls.length - 1] as { model: string }
      if (req.model !== FAST_VISION_MODEL && detailFails) throw new Anthropic.APIConnectionError({ message: 'down' })
      const fast = req.model === FAST_VISION_MODEL
      return {
        usage,
        stop_reason: 'end_turn',
        parsed_output: reading({ state: [{ key: 'food', value: fast ? '200' : '900', confidence: 0.9 }, { key: 'villagers', value: fast ? '21' : '31', confidence: 0.9 }], events: [{ type: 'fight', zone: null, attributes: [], confidence: 0.8, evidence: 'x' }] }),
      }
    },
  })
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => settings, () => client)
  const img = { base64: 'AAAA', mediaType: 'image/jpeg' as const }
  detailFails = true
  const kept = await svc.analyzeFrame(aoe4, img, {})
  assert.ok(kept.ok && kept.response.state[0].value === '200', 'the fast reading is kept')
  detailFails = false
  for (let i = 0; i < AGREEMENT_MIN_CHECKED / 2; i++) await svc.analyzeFrame(aoe4, img, {})
  const t = svc.spend.vision!.tiers!
  assert.match(t.off ?? '', /agreed with 0% of 20 readings checked/)
  const n = calls.length
  await svc.analyzeFrame(aoe4, img, {})
  assert.equal(calls.length, n + 1)
  assert.equal((calls[n] as { model: string }).model, 'claude-sonnet-5-5', 'one call, to the chosen model')
})

test('smart reading: a name the chosen model confirmed is not sent on again while it stays the same; a new one is, at most every 20 s', async () => {
  const settings = { ...DEFAULT_CLOUD, enabled: true }
  let t = 0
  let units = '6 Longbowmen'
  const { client, calls } = fakeClient({
    parse: () => ({ usage, stop_reason: 'end_turn', parsed_output: reading({ state: [{ key: 'enemyUnits', value: units, confidence: 0.9 }] }) }),
  })
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => settings, () => client, { now: () => t })
  const img = { base64: 'AAAA', mediaType: 'image/jpeg' as const }
  const detailCalls = () => calls.filter((c) => (c as { model: string }).model !== FAST_VISION_MODEL).length
  for (let i = 0; i < 7; i++) await svc.analyzeFrame(aoe4, img, {}, {}).then(() => (t += 3000))
  assert.equal(detailCalls(), 1, 'the same army for 21 s: checked once')
  units = '10 Longbowmen, 4 Spearmen'
  await svc.analyzeFrame(aoe4, img, {}, {})
  assert.equal(detailCalls(), 2, 'a different army, 21 s on: checked')
  t += 3000
  units = '12 Longbowmen'
  await svc.analyzeFrame(aoe4, img, {}, {})
  assert.equal(detailCalls(), 2, 'another change 3 s later: waits')
  t += 20_000
  await svc.analyzeFrame(aoe4, img, {}, {})
  assert.equal(detailCalls(), 3)
})
