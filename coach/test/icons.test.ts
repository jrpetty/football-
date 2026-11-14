// Status icons read by their colour on this PC: steady icon pixels only (not
// the world behind them), nothing until a box has been watched, nothing when
// unsure, presence badges, agreement with the model, and the whole path from
// frames to a live tip with no cloud look at all.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { colourOf, IconReader, iconFieldsFor, ICON_WARMUP, probeIcon } from '../src/core/hud/icons.ts'
import { PipelineHost } from '../src/worker/host.ts'
import type { FromWorker, ToWorker } from '../src/worker/protocol.ts'
import type { Frame, Observation } from '../src/core/types.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { policyFor, settings } from './helpers.ts'

const W = 320
const H = 180
const BOX = { x: 0.9, y: 0.85, w: 0.05, h: 0.08 }
const COLOURS: Record<string, [number, number, number]> = { white: [240, 240, 240], yellow: [235, 210, 40], orange: [240, 140, 30], red: [225, 30, 30], lightBlue: [150, 210, 255], darkBlue: [30, 70, 200] }

/** A frame with a noisy, changing world and an icon (a filled square) in the box. */
function frame(t: number, icon: keyof typeof COLOURS | null, seed = t): Frame {
  const data = new Uint8ClampedArray(W * H * 4)
  let s = seed * 9301 + 49297
  for (let i = 0; i < W * H; i++) {
    s = (s * 233280 + 12345) % 2147483647
    const v = 40 + (s % 120)
    data[i * 4] = v
    data[i * 4 + 1] = v + 10
    data[i * 4 + 2] = v - 10
    data[i * 4 + 3] = 255
  }
  if (icon) {
    const [r, g, b] = COLOURS[icon]
    const x0 = Math.floor((BOX.x + BOX.w * 0.25) * W)
    const y0 = Math.floor((BOX.y + BOX.h * 0.25) * H)
    for (let y = y0; y < y0 + Math.floor(BOX.h * H * 0.5); y++)
      for (let x = x0; x < x0 + Math.floor(BOX.w * W * 0.5); x++) {
        const p = (y * W + x) * 4
        data[p] = r
        data[p + 1] = g
        data[p + 2] = b
      }
  }
  return { width: W, height: H, data, timestamp: t }
}

test('pixel colours: white, yellow, orange, red, light and dark blue; dark and dull pixels are not icon', () => {
  for (const [name, [r, g, b]] of Object.entries(COLOURS)) assert.equal(colourOf(r, g, b), name, name)
  assert.equal(colourOf(40, 50, 30), null)
  assert.equal(colourOf(120, 120, 120), null)
})

test('a level icon over a changing world: read once watched, by its steady pixels; nothing before, nothing without an icon', () => {
  const reader = new IconReader([{ key: 'water', kind: 'level', box: BOX }])
  for (let i = 0; i < ICON_WARMUP - 1; i++) assert.equal(reader.read(frame(i * 1000, 'red'), 'dayz').length, 0, 'not before it has been watched')
  let obs: Observation[] = []
  for (let i = ICON_WARMUP - 1; i < ICON_WARMUP + 4; i++) obs = reader.read(frame(i * 1000, 'red'), 'dayz')
  assert.equal(obs.length, 2, 'the reading, and play (icons showing)')
  assert.equal(obs[0].value, 'critical')
  assert.equal(obs[1].eventType, 'phase')
  assert.equal(obs[0].source, 'pixel')
  assert.match(obs[0].evidence[0], /water icon is red/)
  // The level changes: a few seconds later it reads the new colour.
  for (let i = 20; i < 40; i++) obs = reader.read(frame(i * 1000, 'yellow'), 'dayz')
  assert.equal(obs[0]?.value, 'low')
  // No icon, only the world: nothing (fail closed).
  const empty = new IconReader([{ key: 'water', kind: 'level', box: BOX }])
  let none: Observation[] = []
  for (let i = 0; i < 30; i++) none = empty.read(frame(i * 1000, null), 'dayz')
  assert.equal(none.length, 0)
})

test('temperature by its blues; a presence badge shows or not', () => {
  const t = new IconReader([{ key: 'temperature', kind: 'temperature', box: BOX }])
  let obs: Observation[] = []
  for (let i = 0; i < 15; i++) obs = t.read(frame(i * 1000, 'darkBlue'), 'dayz')
  assert.equal(obs[0]?.value, 'very_cold')
  const b = new IconReader([{ key: 'bleeding', kind: 'presence', box: BOX, present: 1 }])
  assert.equal(b.read(frame(0, 'red'), 'dayz')[0]?.value, 1)
  assert.equal(b.read(frame(1000, null), 'dayz')[0]?.value, 0)
  assert.deepEqual(probeIcon(frame(0, 'orange'), BOX, 'level'), { colour: 'orange', value: 'very_low', share: 1 })
})

test('when the model looks and keeps disagreeing, the icon stops being used', () => {
  const reader = new IconReader([{ key: 'water', kind: 'level', box: BOX }])
  for (let i = 0; i < 20; i++) reader.read(frame(i * 1000, 'white'), 'dayz')
  const model = (v: string): Observation[] => [{ id: 'm', timestampMs: 0, gameProfileId: 'dayz', sourceRegion: 'full-frame', kind: 'state', eventType: 'water', value: v, confidence: 0.9, freshnessMs: 0, evidence: [], source: 'vision-model' }]
  for (let i = 0; i < 6; i++) {
    reader.read(frame(100_000 + i * 1000, 'white'), 'dayz')
    reader.check(100_000 + i * 1000, model('low'))
  }
  const s = reader.status()[0]
  assert.equal(s.checks, 6)
  assert.ok(s.stopped)
  assert.equal(reader.read(frame(200_000, 'white'), 'dayz').length, 0, 'no longer used')
})

test('offline with no cloud at all: a red water icon read on this PC becomes "Drink now"', () => {
  const dayz = builtInProfile('dayz')
  const posted: FromWorker[] = []
  const host = new PipelineHost({ post: (m) => posted.push(m), schedule: (fn) => fn(), now: () => 0, encodeJpeg: async () => new ArrayBuffer(8), decode: () => { throw new Error('unused') } })
  const s = settings({ profileId: 'dayz', profileModeId: 'offline', mode: 'training' })
  const start: Extract<ToWorker, { type: 'start' }> = {
    type: 'start', profile: dayz, settings: s, policy: policyFor(dayz, 'offline'), history: null, matchHistory: [], context: {}, screens: null, now: 0,
    vision: { enabled: false, intervalMs: 4000 },
    icons: iconFieldsFor(dayz.hudIcons, { water: BOX }),
  }
  host.handle(start)
  for (let t = 0; t <= 40_000; t += 500) host.handle({ type: 'pixels', frame: frame(t, 'red') })
  const said = posted.flatMap((m) => (m.type === 'output' ? m.utterances.map((u) => u.text) : []))
  assert.ok(said.some((x) => /^Drink now/.test(x)), said.join(' | '))
  assert.ok(!posted.some((m) => m.type === 'vision-frame'), 'no cloud look')
  const status = posted.filter((m) => m.type === 'output' && m.status).at(-1) as Extract<FromWorker, { type: 'output' }>
  assert.equal(status.status!.vision.icons![0].last, 'critical')
})
