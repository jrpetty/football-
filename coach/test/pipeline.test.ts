// The worker host and the bounded frame queue.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { BoundedFrameQueue } from '../src/core/queue.ts'
import { renderHud, simulateFrames } from '../src/sim/syntheticArena.ts'
import { PipelineHost, QUEUE_CAPACITY } from '../src/worker/host.ts'
import type { FromWorker } from '../src/worker/protocol.ts'
import { arena, settings } from './helpers.ts'

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
    decode: (_b, t) => renderHud({ health: 1, abilityReady: true, roundLive: true, eliminated: false, banner: null }, 256, 144, t),
  })
  host.handle({ type: 'start', profile: arena, settings: settings(), history: null, now: 0 })
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
  const host = new PipelineHost({ post: (m) => posted.push(m), schedule: (fn) => fn(), now: () => 0, decode: () => { throw new Error('unused') } })
  host.handle({ type: 'start', profile: arena, settings: settings(), history: null, now: 0 })
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
  const host = new PipelineHost({ post: (m) => posted.push(m), schedule: (fn) => tasks.push(fn), now: () => 0, decode: () => { throw new Error('decode failed') } })
  host.handle({ type: 'start', profile: arena, settings: settings(), history: null, now: 0 })
  host.handle({ type: 'bitmap', bitmap: fakeBitmap(new Set()), timestamp: 0 })
  // Runs later, as setTimeout would in the worker: errors must not escape.
  assert.doesNotThrow(() => tasks.forEach((t) => t()))
  assert.ok(posted.some((m) => m.type === 'error' && /decode failed/.test(m.message)))
  assert.ok(posted.some((m) => m.type === 'output' && m.frameDone), 'flow control is released')
})
