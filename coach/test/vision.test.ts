// Pixel detectors, the visible-state tracker, and event rules.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { inHsvRange, rgbToHsv } from '../src/core/frame.ts'
import { correlation, readBar, readCoverage, readSignature } from '../src/core/detectors/detectors.ts'
import { EventDetector } from '../src/core/events.ts'
import type { BarSignalSpec, CoverageSignalSpec, EventRuleSpec, SignatureSignalSpec } from '../src/core/profile/schema.ts'
import { STALE_AFTER_MS, VisibleStateTracker } from '../src/core/tracker.ts'
import type { SignalState } from '../src/core/types.ts'
import { renderHud } from '../src/sim/syntheticArena.ts'
import { arena, frame } from './helpers.ts'

const GREEN = [52, 205, 84] as const
const TRACK = [44, 46, 52] as const
const BG = [30, 36, 44] as const

const bar: BarSignalSpec = {
  id: 'hp',
  label: 'HP',
  kind: 'bar',
  region: { x: 0.1, y: 0.1, w: 0.5, h: 0.1 },
  axis: 'horizontal',
  fillFrom: 'start',
  fill: { h: [95, 145], s: [0.45, 1], v: [0.45, 1] },
  track: { h: [0, 360], s: [0, 0.2], v: [0.08, 0.32] },
}

function barFrame(fill: number, t = 0) {
  return frame(200, 100, BG, [
    { r: bar.region, c: TRACK },
    ...(fill > 0 ? [{ r: { ...bar.region, w: bar.region.w * fill }, c: GREEN }] : []),
  ], t)
}

test('hsv conversion and hue ranges that wrap through red', () => {
  const red = rgbToHsv(220, 20, 30)
  assert.ok(red.h > 350 || red.h < 5)
  assert.ok(inHsvRange(red, { h: [350, 10], s: [0.5, 1], v: [0.5, 1] }))
  assert.ok(!inHsvRange(rgbToHsv(20, 220, 30), { h: [350, 10], s: [0.5, 1], v: [0.5, 1] }))
})

test('bar fill is measured within a few percent at any level', () => {
  for (const fill of [0, 0.1, 0.25, 0.5, 0.73, 1]) {
    const r = readBar(barFrame(fill), bar)
    assert.equal(r.kind, 'number')
    assert.ok(Math.abs(r.value - fill) < 0.03, `fill ${fill} read as ${r.value}`)
    assert.ok(r.confidence > 0.8, `fill ${fill} confidence ${r.confidence}`)
  }
})

test('a missing bar is not read as an empty bar', () => {
  // Same region, but no bar on screen at all (a menu, a death cam).
  const r = readBar(frame(200, 100, [120, 120, 200]), bar)
  assert.ok(r.confidence < 0.5, `confidence ${r.confidence}`)
  // Without a track colour, "0%" can never be confident.
  const { track: _ignored, ...noTrack } = bar
  const r2 = readBar(barFrame(0), noTrack)
  assert.ok(r2.confidence < 0.5)
})

test('stray fill-coloured pixels beyond the bar lower confidence', () => {
  const f = frame(200, 100, BG, [
    { r: bar.region, c: TRACK },
    { r: { ...bar.region, w: bar.region.w * 0.3 }, c: GREEN },
    { r: { x: 0.45, y: 0.1, w: 0.1, h: 0.1 }, c: GREEN }, // e.g. a green particle over the bar
  ])
  assert.ok(readBar(f, bar).confidence < 0.7)
})

test('coverage reads banners with hysteresis-aware confidence', () => {
  const spec = arena.signals.find((s) => s.id === 'victory-banner') as CoverageSignalSpec
  const on = renderHud({ health: null, abilityReady: null, roundLive: false, eliminated: false, banner: 'victory' }, 256, 144, 0)
  const off = renderHud({ health: null, abilityReady: null, roundLive: false, eliminated: false, banner: 'defeat' }, 256, 144, 0)
  const a = readCoverage(on, spec)
  const b = readCoverage(off, spec)
  assert.equal(a.kind === 'boolean' && a.value, true)
  assert.equal(b.kind === 'boolean' && b.value, false)
  assert.ok(a.confidence >= 0.5 && b.confidence >= 0.5)
})

test('signature matching finds a stored icon and rejects flat or different regions', () => {
  const region = { x: 0.2, y: 0.2, w: 0.2, h: 0.2 }
  const icon = frame(100, 100, [0, 0, 0], [
    { r: { x: 0.2, y: 0.2, w: 0.1, h: 0.2 }, c: [255, 255, 255] },
    { r: { x: 0.3, y: 0.3, w: 0.1, h: 0.1 }, c: [128, 128, 128] },
  ])
  // Build the template from the icon itself (as profile tooling would).
  const values: number[] = []
  for (let r = 0; r < 4; r++) for (let c = 0; c < 4; c++) {
    const x = 20 + c * 5 + 2
    const y = 20 + r * 5 + 2
    values.push(icon.data[(y * 100 + x) * 4])
  }
  const spec: SignatureSignalSpec = { id: 's', label: 'icon', kind: 'signature', region, template: { cols: 4, rows: 4, values }, on: 0.8, off: 0.5 }
  const hit = readSignature(icon, spec)
  assert.ok(hit.kind === 'boolean' && hit.value && hit.score > 0.95)
  const flat = readSignature(frame(100, 100, [90, 90, 90]), spec)
  assert.ok(flat.kind === 'boolean' && !flat.value)
  assert.equal(correlation([1, 1, 1], [1, 2, 3]), 0)
})

test('tracker smooths, then marks a signal unknown after the stale grace period', () => {
  const tracker = new VisibleStateTracker([bar])
  tracker.update(barFrame(0.8, 0))
  tracker.update(barFrame(0.8, 100))
  const spike = tracker.update(barFrame(0.2, 200)).get('hp')! // one-frame glitch
  assert.ok(spike.known && spike.kind === 'number' && spike.value > 0.7, 'median filter rejects a single outlier')

  const hidden = frame(200, 100, [120, 120, 200])
  const soon = tracker.update({ ...hidden, timestamp: 300 }).get('hp')!
  assert.ok(soon.known, 'within grace period the last value is kept')
  const later = tracker.update({ ...hidden, timestamp: 300 + STALE_AFTER_MS + 100 }).get('hp')!
  assert.equal(later.known, false)
})

function num(value: number, t: number, confidence = 0.9): SignalState {
  return { id: 'hp', known: true, kind: 'number', value, confidence, observedAt: t }
}

function bool(value: boolean, t: number): SignalState {
  return { id: 'flag', known: true, kind: 'boolean', value, confidence: 0.9, observedAt: t }
}

test('cross rules fire once per crossing and must be armed by a visible safe value first', () => {
  const rule: EventRuleSpec = { id: 'low', label: 'Low', category: 'context', signal: 'hp', type: 'below', value: 0.3, rearm: 0.5 }
  const d = new EventDetector([rule], [bar])
  const fired = (v: number, t: number) => d.update(new Map([['hp', num(v, t)]]), t).length
  assert.equal(fired(0.2, 0), 0, 'already low when capture starts is not an event')
  assert.equal(fired(0.6, 1000), 0)
  assert.equal(fired(0.25, 2000), 1)
  assert.equal(fired(0.2, 3000), 0, 'stays low: no repeat')
  assert.equal(fired(0.4, 4000), 0)
  assert.equal(fired(0.2, 5000), 0, 'not re-armed until it passes 0.5')
  assert.equal(fired(0.55, 6000), 0)
  assert.equal(fired(0.1, 7000), 1)
})

test('drop rules measure change within the window and describe their evidence', () => {
  const rule: EventRuleSpec = { id: 'hit', label: 'Hit', category: 'context', signal: 'hp', type: 'drop', delta: 0.25, withinMs: 1000 }
  const d = new EventDetector([rule], [bar])
  assert.equal(d.update(new Map([['hp', num(0.9, 0)]]), 0).length, 0)
  const ev = d.update(new Map([['hp', num(0.5, 600)]]), 600)
  assert.equal(ev.length, 1)
  assert.match(ev[0].evidence, /HP 90% → 50% in 0\.6s/)
  // A slow bleed over several seconds is not a heavy hit.
  const slow = new EventDetector([rule], [bar])
  let n = 0
  for (let t = 0, v = 0.9; t <= 5000; t += 500, v -= 0.05) n += slow.update(new Map([['hp', num(v, t)]]), t).length
  assert.equal(n, 0)
})

test('edge rules ignore the first sighting, honour minHold, and skip unknown readings', () => {
  const rule: EventRuleSpec = { id: 'on', label: 'On', category: 'outcome', signal: 'flag', type: 'becomes-true', minHoldMs: 200 }
  const d = new EventDetector([rule], [])
  const step = (s: SignalState, t: number) => d.update(new Map([['flag', s]]), t).length
  assert.equal(step(bool(true, 0), 0), 0, 'first sighting is not a transition')
  assert.equal(step(bool(false, 100), 100), 0)
  assert.equal(step(bool(true, 200), 200), 0, 'not held long enough yet')
  assert.equal(step({ id: 'flag', known: false, lastKnownAt: 200 }, 300), 0)
  assert.equal(step(bool(true, 450), 450), 1)
  assert.equal(step(bool(true, 600), 600), 0)
})
