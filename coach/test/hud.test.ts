// Reading the HUD's numbers on this computer: digits learned from the model's
// readings of the player's own screen, used only once they agree with it.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { Frame, Observation } from '../src/core/types.ts'
import { GlyphBook, clockRenderings, numberRenderings, parseClock, segment } from '../src/core/hud/glyphs.ts'
import { HudReader, TRUST_MIN_CHECKS, type HudField } from '../src/core/hud/reader.ts'

// A 5 × 7 pixel font, drawn larger with soft edges and noise, like a game's HUD after scaling.
const FONT: Record<string, string[]> = {
  '0': ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
  '1': ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
  '2': ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
  '3': ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
  '4': ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
  '5': ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
  '6': ['00110', '01000', '10000', '11110', '10001', '10001', '01110'],
  '7': ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
  '8': ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
  '9': ['01110', '10001', '10001', '01111', '00001', '00010', '01100'],
  '/': ['00001', '00010', '00010', '00100', '01000', '01000', '10000'],
  ':': ['00000', '01100', '01100', '00000', '01100', '01100', '00000'],
  ',': ['00000', '00000', '00000', '00000', '00110', '00110', '00100'],
}

/** A frame with texts drawn at given boxes (pixel scale 3, light text on a dark textured bar). */
export function hudFrame(texts: { x: number; y: number; text: string }[], seed: number, w = 400, h = 60, timestamp = 0): Frame {
  const data = new Uint8ClampedArray(w * h * 4)
  let s = seed
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647)
  for (let i = 0; i < w * h; i++) {
    const g = 30 + rnd() * 18
    data.set([g, g + 4, g + 10, 255], i * 4)
  }
  const ink = new Float32Array(w * h)
  const scale = 3
  for (const t of texts) {
    let cx = t.x + Math.floor(rnd() * 2)
    for (const ch of t.text) {
      const rows = FONT[ch]
      for (let r = 0; r < 7; r++)
        for (let c = 0; c < 5; c++)
          if (rows[r][c] === '1')
            for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) ink[(t.y + r * scale + dy) * w + cx + c * scale + dx] = 1
      cx += 6 * scale
    }
  }
  // Soft edges (a box blur), then light text over the bar.
  for (let y = 1; y < h - 1; y++)
    for (let x = 1; x < w - 1; x++) {
      let a = 0
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) a += ink[(y + dy) * w + x + dx]
      a = (a / 9) * 0.85 + ink[y * w + x] * 0.15
      if (!a) continue
      const i = (y * w + x) * 4
      for (let k = 0; k < 3; k++) data[i + k] = data[i + k] * (1 - a) + 235 * a
    }
  return { width: w, height: h, data, timestamp }
}

const FIELDS: HudField[] = [
  { key: 'food', kind: 'number', box: { x: 0.0, y: 0.0, w: 0.25, h: 0.5 } },
  { key: 'population', key2: 'populationCap', kind: 'fraction', box: { x: 0.3, y: 0.0, w: 0.4, h: 0.5 } },
  { key: 'gameTimeSec', kind: 'clock', box: { x: 0.0, y: 0.5, w: 0.3, h: 0.5 } },
]

function frameFor(food: number, pop: number, cap: number, sec: number, seed: number, timestamp: number): Frame {
  const clock = clockRenderings(sec)[0]
  return hudFrame(
    [
      { x: 6, y: 4, text: String(food) },
      { x: 126, y: 4, text: `${pop}/${cap}` },
      { x: 6, y: 34, text: clock },
    ],
    seed,
    400,
    60,
    timestamp,
  )
}

function modelSays(food: number, pop: number, cap: number, sec: number, at: number): Observation[] {
  const o = (key: string, value: number): Observation => ({ id: `${key}-${at}`, timestampMs: at, gameProfileId: 'aoe4', sourceRegion: 'full-frame', kind: 'state', eventType: key, value, confidence: 0.85, freshnessMs: 0, evidence: [], source: 'vision-model' })
  return [o('food', food), o('population', pop), o('populationCap', cap), o('gameTimeSec', sec)]
}

test('glyphs: a number box is cut into one glyph per character, punctuation included', () => {
  const f = hudFrame([{ x: 6, y: 4, text: '45/120' }], 3)
  assert.equal(segment(f, { x: 0, y: 0, w: 0.5, h: 0.5 })!.length, 6)
  assert.equal(segment(f, { x: 0.6, y: 0.5, w: 0.3, h: 0.4 }), null, 'an empty box')
  assert.deepEqual(numberRenderings(1200), ['1200', '1,200', '1.200'])
  assert.deepEqual(clockRenderings(245), ['4:05', '04:05'])
  assert.equal(parseClock('1:02:03'), 3723)
})

test('glyphs: digits learned from labelled boxes are read back on new numbers, and unknown digits are not guessed', () => {
  const book = new GlyphBook()
  const box = { x: 0, y: 0, w: 0.5, h: 0.5 }
  // Learn 0–8 only: nothing is read while a digit is missing (a 9 would be taken for an 8).
  for (const [i, n] of [1234, 5678, 8012, 3456, 7801].entries()) assert.ok(book.learn(segment(hudFrame([{ x: 6, y: 4, text: String(n) }], 10 + i), box)!, [String(n)]))
  assert.equal(book.read(segment(hudFrame([{ x: 9, y: 5, text: '2871' }], 99), box)!), null, 'a 9 was never seen')
  assert.equal(book.read(segment(hudFrame([{ x: 9, y: 5, text: '2871' }], 99), box)!, '012345678'), '2871')
  for (const [i, n] of [9090, 1999].entries()) book.learn(segment(hudFrame([{ x: 6, y: 4, text: String(n) }], 20 + i), box)!, [String(n)])
  assert.equal(book.read(segment(hudFrame([{ x: 9, y: 5, text: '1929' }], 98), box)!), '1929')
  // One misread by the model is not learned: a shape needs two labels the same way.
  const once = new GlyphBook()
  once.learn(segment(hudFrame([{ x: 6, y: 4, text: '1234567890' }], 30), box)!, ['9876543210'])
  assert.deepEqual(once.known, [])
  // Kept between sessions.
  const again = new GlyphBook(JSON.parse(JSON.stringify(book.toJSON())))
  assert.equal(again.read(segment(hudFrame([{ x: 7, y: 4, text: '6043' }], 97), box)!), '6043')
})

test('reader: local readings are used only after they agree with the model, then every frame', () => {
  const reader = new HudReader(FIELDS)
  let s = 1
  const rnd = () => ((s = (s * 48271) % 2147483647) / 2147483647)
  const sample = () => {
    const pop = 10 + Math.floor(rnd() * 180)
    return [Math.floor(rnd() * 3000), pop, Math.min(200, pop + Math.floor(rnd() * 30)), Math.floor(rnd() * 3000)] as const
  }
  let usedBefore = 0
  for (let i = 0; i < 40; i++) {
    const v = sample()
    const f = frameFor(...v, 100 + i, i * 3000)
    if (reader.read(f, 'aoe4').length) usedBefore++
    reader.remember(f)
    reader.check(f.timestamp, modelSays(...v, f.timestamp))
  }
  const st = reader.status()
  for (const f of st.fields) {
    assert.ok(f.trusted, `${f.key}: ${f.agreed}/${f.checks}`)
    assert.ok(f.checks >= TRUST_MIN_CHECKS)
    assert.ok((f.recent ?? 0) >= 0.95)
  }
  assert.ok(usedBefore < 40, 'not used from the start')
  // Now new frames are read on this computer, correctly.
  let right = 0
  let total = 0
  for (let i = 0; i < 50; i++) {
    const [food, pop, cap, sec] = sample()
    const obs = reader.read(frameFor(food, pop, cap, sec, 500 + i, 200_000 + i * 1000), 'aoe4')
    const got = Object.fromEntries(obs.map((o) => [o.eventType, o.value]))
    total += 4
    right += Number(got.food === food) + Number(got.population === pop) + Number(got.populationCap === cap) + Number(got.gameTimeSec === sec)
    for (const o of obs) assert.equal(o.source, 'pixel')
  }
  assert.ok(right / total >= 0.97, `${right}/${total} values read right`)
})

test('reader: a field that keeps disagreeing with the model is never used', () => {
  const reader = new HudReader([FIELDS[0]])
  for (let i = 0; i < 30; i++) {
    const food = 100 + i * 37
    const f = hudFrame([{ x: 6, y: 4, text: String(food) }], 300 + i, 400, 60, i * 1000)
    reader.remember(f)
    // The model says something else every time (e.g. the box is on the wrong number).
    reader.check(f.timestamp, [{ id: 'x', timestampMs: f.timestamp, gameProfileId: 'aoe4', sourceRegion: 'full-frame', kind: 'state', eventType: 'food', value: food + 1000, confidence: 0.8, freshnessMs: 0, evidence: [], source: 'vision-model' }])
  }
  assert.equal(reader.status().fields[0].trusted, false)
  assert.equal(reader.read(hudFrame([{ x: 6, y: 4, text: '777' }], 1, 400, 60, 99_000), 'aoe4').length, 0)
})
