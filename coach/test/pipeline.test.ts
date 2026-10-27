// The worker host and the bounded frame queue.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { BoundedFrameQueue } from '../src/core/queue.ts'
import { renderHud, simulateFrames } from '../src/sim/syntheticArena.ts'
import { PipelineHost, QUEUE_CAPACITY } from '../src/worker/host.ts'
import type { FromWorker, ToWorker } from '../src/worker/protocol.ts'
import { arena, policyFor, settings } from './helpers.ts'

function start(): Extract<ToWorker, { type: 'start' }> {
  return { type: 'start', profile: arena, settings: settings(), policy: policyFor(arena, 'ranked'), history: null, matchHistory: [], context: {}, vision: { enabled: false, intervalMs: 3000 }, screens: null, now: 0 }
}

test('the queue drops and releases the oldest items beyond capacity', () => {
  const released: number[] = []
  const q = new BoundedFrameQueue<number>(2, (n) => released.push(n))
  for (const n of [1, 2, 3, 4]) q.push(n)
  assert.deepEqual(released, [1, 2])
  assert.equal(q.shift(), 3)
  q.clear()
  assert.deepEqual(released, [1, 2, 4])
  assert.deepEqual(q.stats, { enqueued: 4, dropped: 2, depth: 0, capacity: 2 })
  assert.throws(() => new BoundedFrameQueue(0))
})

function fakeBitmap(closed: Set<object>) {
  const b = { width: 256, height: 144, close: () => closed.add(b) }
  return b as unknown as ImageBitmap
}

test('every bitmap is closed, whether analysed or dropped, and bursts are bounded', () => {
  const posted: FromWorker[] = []
  const tasks: (() => void)[] = []
  const closed = new Set<object>()
  const host = new PipelineHost({
    post: (m) => posted.push(m),
    schedule: (fn) => tasks.push(fn),
    now: () => 0,
    encodeJpeg: async () => new ArrayBuffer(8), decode: (_b, t) => renderHud({ health: 1, abilityReady: true, roundLive: true, eliminated: false, banner: null }, 256, 144, t),
  })
  host.handle(start())
  const bitmaps = Array.from({ length: 6 }, () => fakeBitmap(closed))
  bitmaps.forEach((bitmap, i) => host.handle({ type: 'bitmap', bitmap, timestamp: i * 100 }))
  for (const t of tasks.splice(0)) t()
  assert.equal(closed.size, 6, 'all bitmaps released')
  const done = posted.filter((m) => m.type === 'output' && m.frameDone).length
  assert.equal(done, 6, 'each frame completes exactly once (analysed or dropped)')
  const analysed = posted.filter((m) => m.type === 'output' && m.frameDone && m.status).length
  assert.equal(analysed, QUEUE_CAPACITY)
})

test('frames after stop are ignored, and stop returns a snapshot', () => {
  const posted: FromWorker[] = []
  const closed = new Set<object>()
  const host = new PipelineHost({ post: (m) => posted.push(m), schedule: (fn) => fn(), now: () => 0, encodeJpeg: async () => new ArrayBuffer(8), decode: () => { throw new Error('unused') } })
  host.handle(start())
  for (const frame of simulateFrames({ rounds: 1, seed: 1, fps: 4 })) host.handle({ type: 'pixels', frame })
  host.handle({ type: 'stop', now: 1e6 })
  const stopped = posted.find((m) => m.type === 'stopped')
  assert.ok(stopped && stopped.type === 'stopped' && stopped.snapshot.profileId === arena.id)
  const b = fakeBitmap(closed)
  host.handle({ type: 'bitmap', bitmap: b, timestamp: 0 })
  assert.ok(closed.has(b), 'a late frame is released, not analysed')
})

test('engine errors are reported, not thrown across the worker boundary', () => {
  const posted: FromWorker[] = []
  const tasks: (() => void)[] = []
  const host = new PipelineHost({ post: (m) => posted.push(m), schedule: (fn) => tasks.push(fn), now: () => 0, encodeJpeg: async () => new ArrayBuffer(8), decode: () => { throw new Error('decode failed') } })
  host.handle(start())
  host.handle({ type: 'bitmap', bitmap: fakeBitmap(new Set()), timestamp: 0 })
  // Runs later, as setTimeout would in the worker: errors must not escape.
  assert.doesNotThrow(() => tasks.forEach((t) => t()))
  assert.ok(posted.some((m) => m.type === 'error' && /decode failed/.test(m.message)))
  assert.ok(posted.some((m) => m.type === 'output' && m.frameDone), 'flow control is released')
})

test('with cloud vision on, frames are sampled, answers validated, and only declared observations reach the engine', async () => {
  const { builtInProfile } = await import('../src/profiles/index.ts')
  const aoe = builtInProfile('aoe2-de')
  const posted: FromWorker[] = []
  let t = 0
  const host = new PipelineHost({
    post: (m) => posted.push(m), schedule: (fn) => fn(), now: () => 0, encodeJpeg: async () => new ArrayBuffer(16),
    decode: () => { throw new Error('unused') },
  })
  host.handle({ ...start(), profile: aoe, settings: settings({ profileId: 'aoe2-de', profileModeId: 'skirmish-vs-ai', mode: 'training' }), policy: policyFor(aoe, 'skirmish-vs-ai'), vision: { enabled: true, intervalMs: 3000 } })
  const frame = (ts: number, shade: number) => ({ width: 64, height: 36, data: new Uint8ClampedArray(64 * 36 * 4).fill(shade), timestamp: ts })
  for (; t <= 9000; t += 500) host.handle({ type: 'pixels', frame: frame(t, 40 + (t % 3000 === 0 ? t / 100 : 0)) })
  await new Promise((r) => setTimeout(r, 0))
  const frames = posted.filter((m) => m.type === 'vision-frame')
  assert.equal(frames.length, 1, 'one request in flight at a time')
  const first = frames[0] as Extract<FromWorker, { type: 'vision-frame' }>
  host.handle({
    type: 'vision-result', frameAt: first.frameAt, arrivedAt: first.frameAt + 1500, error: null,
    response: { phase: 'round', phaseConfidence: 0.9, visibleOutcome: null, visibleHealth: null, unreadable: '',
      state: [{ key: 'population', value: '30', confidence: 0.9 }, { key: 'populationCap', value: '30', confidence: 0.9 }, { key: 'enemyArmy', value: '40', confidence: 0.9 }],
      events: [] },
  })
  const status = posted.filter((m) => m.type === 'output' && m.status).at(-1) as Extract<FromWorker, { type: 'output' }>
  assert.equal(status.status!.visible.values.population.value, 30)
  assert.equal(status.status!.visible.values.enemyArmy, undefined)
  assert.equal(status.status!.vision.dropped, 1)
  assert.equal(status.status!.vision.latencyMs, 1500)

  // A fatal error (no key, spend cap) pauses sampling and is reported once.
  host.handle({ type: 'vision-result', frameAt: 9000, arrivedAt: 9100, error: 'Session spend cap reached', response: null, fatal: true })
  const before = posted.filter((m) => m.type === 'vision-frame').length
  for (t = 20_000; t <= 40_000; t += 1000) host.handle({ type: 'pixels', frame: frame(t, (t / 1000) % 200) })
  assert.equal(posted.filter((m) => m.type === 'vision-frame').length, before)
  assert.ok(posted.some((m) => m.type === 'output' && m.entries.some((e) => e.type === 'notice' && /spend cap/.test(e.text))))
})

// --- Screen memory ------------------------------------------------------------------------------

test('a menu learned once is recognised locally afterwards: no request, phase from memory', async () => {
  const { builtInProfile } = await import('../src/profiles/index.ts')
  const aoe = builtInProfile('aoe2-de')
  const posted: FromWorker[] = []
  const host = new PipelineHost({ post: (m) => posted.push(m), schedule: (fn) => fn(), now: () => 0, encodeJpeg: async () => new ArrayBuffer(16), decode: () => { throw new Error('unused') } })
  host.handle({ ...start(), profile: aoe, settings: settings({ profileId: 'aoe2-de', profileModeId: 'skirmish-vs-ai' }), policy: policyFor(aoe, 'skirmish-vs-ai'), vision: { enabled: true, intervalMs: 3000 } })
  // A "menu": left half bright, right half dark, with a stripe that moves a little (animation).
  const menu = (ts: number) => {
    const w = 96, h = 54, data = new Uint8ClampedArray(w * h * 4)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4, v = x < w / 2 ? 200 - y : 30 + ((x + ts / 1000) % 7)
      data[i] = data[i + 1] = data[i + 2] = v; data[i + 3] = 255
    }
    return { width: w, height: h, data, timestamp: ts }
  }
  host.handle({ type: 'pixels', frame: menu(0) })
  await new Promise((r) => setTimeout(r, 0))
  const sent = posted.filter((m) => m.type === 'vision-frame')
  assert.equal(sent.length, 1)
  host.handle({ type: 'vision-result', frameAt: 0, arrivedAt: 1500, error: null,
    response: { phase: 'menu', phaseConfidence: 0.9, visibleOutcome: null, visibleHealth: null, unreadable: '', state: [], events: [],
      screen: { name: 'Skirmish setup', kind: 'menu', summary: 'Choose civilisation, map and AI difficulty before a game against the AI.' } } })
  const saved = posted.find((m) => m.type === 'screens') as Extract<FromWorker, { type: 'screens' }> | undefined
  assert.ok(saved, 'the new screen is handed to the renderer to save')
  assert.equal(saved!.catalog.screens[0].name, 'Skirmish setup')
  assert.match(saved!.catalog.screens[0].h, /^[0-9a-f]{16}$/)
  assert.ok(JSON.stringify(saved!.catalog).length < 400, 'a screen costs a few hundred bytes, no image')

  // The same menu later: recognised from memory, nothing sent.
  for (let t = 4000; t <= 30_000; t += 4000) host.handle({ type: 'pixels', frame: menu(t) })
  await new Promise((r) => setTimeout(r, 0))
  assert.deepEqual(posted.filter((m) => m.type === 'vision-frame').map((m) => (m as { frameAt: number }).frameAt), [0], 'no further requests for a known menu')
  const status = posted.filter((m) => m.type === 'output' && m.status).at(-1) as Extract<FromWorker, { type: 'output' }>
  assert.equal(status.status!.vision.screen?.name, 'Skirmish setup')
  assert.equal(status.status!.vision.screen?.from, 'memory')
  const s = status.status!.vision.sampler
  assert.ok(s.knownScreens >= 1 && s.knownScreens + s.skippedUnchanged >= 6, 'every later frame was handled without a request')
  assert.equal(status.status!.visible.phase, 'menu')

  // Snapshots for questions and marked moments come from the latest frame.
  host.handle({ type: 'snapshot', requestId: 7, maxWidth: 1280 })
  await new Promise((r) => setTimeout(r, 0))
  const snap = posted.find((m) => m.type === 'snapshot') as Extract<FromWorker, { type: 'snapshot' }>
  assert.equal(snap.requestId, 7)
  assert.ok(snap.jpeg && snap.width === 96)
})
