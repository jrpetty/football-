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
  let settings = { ...DEFAULT_CLOUD }
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
