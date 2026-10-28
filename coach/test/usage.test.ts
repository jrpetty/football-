// What the cloud costs: published prices per model (cache writes and reads,
// Haiku's long-prompt rate, web searches, the model that actually served a
// call), image tokens per 28×28 patch, the usage history kept across
// sessions, the measured cost of a look, the spending rate and the monthly
// budget.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CloudService, type CloudClient } from '../electron/cloud/service.ts'
import { DEFAULT_CLOUD } from '../shared/cloud.ts'
import { cleanLedger, dayOf, emptyLedger, measuredLook, recordCall, summarizeUsage, type UsageCall } from '../src/core/usage.ts'
import { costOf, estimateRange, imageTokens, servedPrice } from '../src/core/vision/cost.ts'
import { builtInProfile } from '../src/profiles/index.ts'

const M = 1_000_000
const close = (a: number, b: number) => assert.ok(Math.abs(a - b) < 1e-9, `${a} vs ${b}`)

test('prices: input, output, cache reads and 5-minute and 1-hour cache writes, per model', () => {
  close(costOf('claude-opus-5-5', { input_tokens: M, output_tokens: 0 }), 4)
  close(costOf('claude-opus-5-5', { input_tokens: 0, output_tokens: M }), 20)
  close(costOf('claude-opus-5-5', { input_tokens: 0, output_tokens: 0, cache_read_input_tokens: M }), 0.2)
  close(costOf('claude-opus-5-5', { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: M }), 5)
  close(costOf('claude-opus-5-5', { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: M, cache_creation: { ephemeral_1h_input_tokens: M } }), 8)
  close(costOf('claude-sonnet-5-5', { input_tokens: M, output_tokens: M, cache_read_input_tokens: M, cache_creation_input_tokens: M }), 2 + 10 + 0.2 + 2.5)
  close(costOf('claude-haiku-5-5', { input_tokens: M / 20, output_tokens: M / 20 }), (0.1 + 0.5) / 20)
})

test('Haiku 5.5 bills its higher rate when the prompt is over 100K tokens; web searches are $10 per 1,000', () => {
  close(costOf('claude-haiku-5-5', { input_tokens: 90_000, output_tokens: 1000 }), (90_000 * 0.1 + 1000 * 0.5) / M)
  close(costOf('claude-haiku-5-5', { input_tokens: 60_000, cache_read_input_tokens: 50_000, output_tokens: 1000 }), (60_000 * 0.5 + 50_000 * 0.05 + 1000 * 2.5) / M)
  close(costOf('claude-sonnet-5-5', { input_tokens: 0, output_tokens: 0, server_tool_use: { web_search_requests: 7 } }), 0.07)
})

test('the model that served a call is priced, dated ids included; image tokens per 28×28 patch', () => {
  assert.equal(servedPrice('claude-sonnet-5-5-20261001')?.id, 'claude-sonnet-5-5')
  assert.equal(servedPrice('claude-opus-5')?.output, 25)
  assert.equal(servedPrice('claude-opus-5-5')?.output, 20, 'not mistaken for Opus 5')
  assert.equal(servedPrice('something-else'), null)
  assert.equal(imageTokens(1280, 720), 46 * 26)
  assert.equal(imageTokens(1024, 576), 37 * 21)
  // An hour at a look every 3 s on Opus: about 1,200 image tokens, 5,000 cached, 350 written per look.
  const r = estimateRange('claude-opus-5-5', { intervalMs: 3000, width: 1280, height: 720, cachedTokens: 5000, thinkingOff: false })
  assert.equal(r.looksPerHour, 1200)
  close(r.perLook, ((1196 + 150) * 4 + 5000 * 0.2 + 350 * 20) / M)
  assert.ok(r.high > r.low, 'thinking widens the range')
  const off = estimateRange('claude-sonnet-5-5', { intervalMs: 3000, width: 1280, height: 720, cachedTokens: 5000, thinkingOff: true })
  close(off.high, off.low)
})

const call = (at: string, over: Partial<UsageCall> = {}): UsageCall => ({
  at: new Date(at),
  feature: 'vision',
  model: 'claude-sonnet-5-5',
  pack: 'cod-mw4',
  usd: 0.01,
  input: 1300,
  cacheRead: 8000,
  cacheWrite: 0,
  output: 400,
  thinking: 50,
  webSearches: 0,
  ...over,
})

test('the usage history: today, this week, this month with a projection, all time, by feature, model and game, per look', () => {
  const l = emptyLedger()
  for (let i = 0; i < 30; i++) recordCall(l, call('2026-10-28T15:00:00'))
  recordCall(l, call('2026-10-27T15:00:00', { feature: 'answer', model: 'claude-opus-5-5', usd: 0.05, pack: 'aoe4' }))
  recordCall(l, call('2026-10-02T15:00:00', { feature: 'research', usd: 0.4, pack: null, webSearches: 12 }))
  recordCall(l, call('2026-09-20T15:00:00', { usd: 1 }))
  const s = summarizeUsage(l, new Date('2026-10-28T18:00:00'))
  close(s.today.usd, 0.3)
  assert.equal(s.today.calls, 30)
  close(s.last7.usd, 0.35)
  close(s.thisMonth.usd, 0.75)
  close(s.allTime.usd, 1.75)
  assert.equal(s.thisMonth.webSearches, 12)
  // The month's end at the last 7 days' daily average: 3 days left.
  close(s.monthProjection, 0.75 + (0.35 / 7) * 3)
  assert.deepEqual(
    s.byFeature.map((f) => f.feature),
    ['research', 'vision', 'answer'],
  )
  assert.deepEqual(s.byPack.map((p) => p.pack), ['cod-mw4', 'aoe4'])
  assert.equal(s.daily.length, 30)
  assert.equal(s.daily.at(-1)!.day, '2026-10-28')
  close(s.daily.at(-1)!.byFeature.vision!, 0.3)
  const look = measuredLook(s, 'cod-mw4', 'claude-sonnet-5-5')!
  assert.equal(look.looks, 31)
  close(look.usdPerLook, 1.3 / 31)
  assert.equal(look.thinking, 50)
  assert.equal(measuredLook(s, 'aoe4', 'claude-sonnet-5-5'), null, 'answers are not looks')
  // Stored and read back; junk is dropped.
  assert.deepEqual(cleanLedger(JSON.parse(JSON.stringify(l))), l)
  assert.deepEqual(cleanLedger({ version: 2 }), emptyLedger())
  assert.deepEqual(cleanLedger({ version: 1, days: { nope: {}, '2026-10-01': { 'vision|m|p': { usd: -3, calls: 'x' } } } }).days, {
    '2026-10-01': { 'vision|m|p': { calls: 0, usd: 0, input: 0, cacheRead: 0, cacheWrite: 0, output: 0, thinking: 0, webSearches: 0 } },
  })
  assert.equal(dayOf(new Date(2026, 0, 5)), '2026-01-05')
})

test('the service bills the model that served each call, records it, measures the rate, and stops at the monthly budget when asked', async () => {
  const mw4 = builtInProfile('cod-mw4')
  const calls: UsageCall[] = []
  let now = 1_000_000
  let served = 'claude-sonnet-5-5'
  const usage = { input_tokens: 1400, output_tokens: 400, cache_read_input_tokens: 9000, cache_creation_input_tokens: 0 }
  const client = {
    beta: {
      messages: {
        parse: async () => ({ model: served, usage, stop_reason: 'end_turn', parsed_output: { phase: 'round', phaseConfidence: 0.9, visibleOutcome: null, visibleHealth: null, state: [], events: [], unreadable: '' } }),
      },
    },
  } as unknown as CloudClient
  let month = 0
  let cloud = { ...DEFAULT_CLOUD, enabled: true, visionModel: 'claude-sonnet-5-5', monthlyBudgetUsd: 5, stopAtBudget: true }
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => cloud, () => client, {
    onCall: (c) => calls.push(c),
    monthSpend: () => month,
    now: () => now,
  })
  const img = { base64: 'AAAA', mediaType: 'image/jpeg' as const }
  for (let i = 0; i < 4; i++) {
    await svc.analyzeFrame(mw4, img, {})
    now += 30_000
  }
  assert.equal(calls.length, 4)
  assert.deepEqual([calls[0].feature, calls[0].model, calls[0].pack], ['vision', 'claude-sonnet-5-5', 'cod-mw4'])
  const one = costOf('claude-sonnet-5-5', usage)
  close(calls[0].usd, one)
  // 4 calls over 2 minutes (counted from the first call to now).
  close(svc.spend.ratePerHour!, ((4 * one) / 120_000) * 3_600_000)
  // A server-side fallback to Opus 5 is billed at Opus 5's price.
  served = 'claude-opus-5'
  const r = await svc.analyzeFrame(mw4, img, {})
  assert.ok(r.ok)
  close(r.costUsd, costOf('claude-opus-5', usage))
  assert.equal(calls.at(-1)!.model, 'claude-opus-5')
  // The monthly budget.
  month = 5
  const stopped = await svc.analyzeFrame(mw4, img, {})
  assert.ok(!stopped.ok && /Monthly budget of \$5\.00 reached/.test(stopped.error))
  cloud = { ...cloud, stopAtBudget: false }
  assert.ok((await svc.analyzeFrame(mw4, img, {})).ok, 'warn only: calls go on')
  assert.equal(svc.spend.monthUsd, 5)
})
