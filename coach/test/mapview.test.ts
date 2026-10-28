// What the map views draw and say: points recorded on pieces joined later
// land on the main piece, paths split by life, heights and callouts placed,
// and the coaching lines from the pins (the spot, the direction of fire).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { toBase64, type StoredMosaic } from '../src/core/map/minimap.ts'
import type { MatchPins } from '../src/core/map/locator.ts'
import { bearing, heightField, mapInsights, mapLayers } from '../src/core/map/view.ts'

const W = 120
const H = 100
const lum = new Uint8Array(W * H)
const weight = new Uint8Array(W * H).fill(3)
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) lum[y * W + x] = x > 40 && x < 70 && y > 30 && y < 60 ? 200 : 40

const mosaic: StoredMosaic = {
  version: 1,
  map: 'Yard',
  updatedAt: '2026-10-28T00:00:00Z',
  pieces: [{ id: 2, x0: 400, y0: 300, w: W, h: H, mean: toBase64(lum), weight: toBase64(weight) }],
  // Piece 1 was joined into piece 2, 10 px right and 5 px down.
  joined: { '1': [2, 10, 5] },
  nextId: 3,
  callouts: { Market: { piece: 2, x: 460, y: 350, n: 6 }, Tower: { piece: 2, x: 460, y: 310, n: 4 } },
  heights: { '2:57:43': [0, 1, 6, 0] },
}

const death = (t: number, x: number, y: number, life: number, killer: { x: number; y: number } | null, piece = 2) => ({
  kind: 'death' as const,
  piece,
  x,
  y,
  t,
  life,
  area: 'Market',
  killer: killer ? { piece: 2, ...killer, from: 'killcam' as const, spot: 'Tower' } : null,
  weapon: 'M4',
  range: 'mid',
})

const match: MatchPins = {
  map: 'Yard',
  pins: [
    death(20, 462, 352, 0, { x: 461, y: 312 }),
    death(50, 458, 349, 1, { x: 459, y: 311 }),
    // Recorded on piece 1 before the join: lands at 455+10, 345+5.
    death(80, 455, 345, 2, { x: 462, y: 309 }, 1),
    { kind: 'kill', piece: 2, x: 420, y: 380, t: 30, life: 0 },
  ],
  trail: [
    [2, 410, 390, 0, 0],
    [2, 440, 370, 10, 0],
    [2, 462, 352, 19, 0],
    [2, 410, 390, 25, 1],
    [2, 458, 349, 49, 1],
    [1, 400, 385, 60, 2],
    [1, 455, 345, 79, 2],
  ],
  enemies: { '2:57:39': 4, '1:50:40': 1 },
}

test('pins, paths, pings, callouts and heights land on the main piece (joined pieces followed)', () => {
  const l = mapLayers(mosaic, [match])!
  assert.equal(l.image.piece, 2)
  const d = l.pins.filter((p) => p.kind === 'death')
  assert.deepEqual([d[2].px, d[2].py], [465 - 400, 350 - 300])
  assert.deepEqual(l.paths.map((p) => p.life), [0, 1, 2])
  assert.deepEqual(l.paths[2].points[0], [410 - 400, 390 - 300, 60])
  assert.equal(l.callouts.find((c) => c.name === 'Market')!.x, 60)
  assert.equal(l.heights[0].level, 1, 'upper floor')
  assert.equal(l.enemies.length, 2)
  assert.equal(l.matches, 1)
  // Heights: the light block stands up; unseen pixels are -1.
  const f = heightField(l)
  assert.ok(f[45 * W + 55] > 0.8)
  assert.ok(f[10 * W + 10] < 0.2)
})

test('what the pins say: the spot where deaths cluster, the direction the shots come from, where enemy pings show up', () => {
  assert.equal(bearing({ x: 0, y: 0 }, { x: 0, y: -10 }), 'north')
  assert.equal(bearing({ x: 0, y: 0 }, { x: 10, y: 10 }), 'south-east')
  const lines = mapInsights(mapLayers(mosaic, [match])!)
  assert.match(lines[0], /^Most deaths in one spot: 3 of 3 within a few steps of each other near Market, 3 of them shot from the north \(Tower\)\. Pre-aim or smoke the north before you cross it\.$/)
  assert.ok(lines.some((l) => /Enemy pings show up most near Tower \(4 times/.test(l)), lines.join('\n'))
})
