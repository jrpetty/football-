// The map stitched from the minimap: following the player to the pixel,
// finding them again after a respawn, starting a new piece on unexplored
// ground and joining it to the rest, enemy pings placed on the map, and the
// learned map kept and found again next match.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanMosaic, MinimapMosaic, MM, readWindow, type MinimapRegion } from '../src/core/map/minimap.ts'
import type { Frame } from '../src/core/types.ts'
import { drawMinimap, tacMap, type TacMap } from '../src/sim/tacMap.ts'

const W = 960
const H = 540
const REGION: MinimapRegion = { x: 0.012, y: 0.022, w: 0.135, h: 0.24 }
const PX = Math.round(REGION.w * W)
const SCALE = 1.4
/** Map pixels per stitched pixel. */
const K = (PX / MM) * SCALE

const map = tacMap(3)

function frame(m: TacMap, x: number, y: number, t: number, extra: { enemies?: { x: number; y: number }[]; heading?: number } = {}): Frame {
  const data = new Uint8ClampedArray(W * H * 4)
  // The game around the minimap: changing dark noise.
  let s = (t * 7919) | 0
  for (let i = 0; i < data.length; i += 4) {
    s = (s * 1103515245 + 12345) & 0x7fffffff
    const v = 20 + (s % 40)
    data[i] = v
    data[i + 1] = v
    data[i + 2] = v + 6
    data[i + 3] = 255
  }
  drawMinimap(m, x, y, PX, { scale: SCALE, heading: extra.heading ?? 0, enemies: extra.enemies, noise: 0.03, seed: t }, data, W, Math.round(REGION.x * W), Math.round(REGION.y * H))
  return { width: W, height: H, data, timestamp: t }
}

/** A smooth walk from (x, y): about 5 map px per frame, turning slowly, kept inside the map. */
function walk(from: [number, number], frames: number, seed: number): [number, number][] {
  let [x, y] = from
  let a = seed
  const out: [number, number][] = []
  for (let i = 0; i < frames; i++) {
    a += Math.sin(i / 9 + seed) * 0.25
    x += Math.cos(a) * 5
    y += Math.sin(a) * 5
    if (x < 140 || x > 580) a = Math.PI - a
    if (y < 140 || y > 580) a = -a
    x = Math.max(130, Math.min(590, x))
    y = Math.max(130, Math.min(590, y))
    out.push([x, y])
  }
  return out
}

test('follows the player to about a pixel and stitches the map as they move', () => {
  const mosaic = new MinimapMosaic('Test Map')
  const path = walk([360, 360], 260, 1)
  let t = 0
  const got: { x: number; y: number; gx: number; gy: number }[] = []
  for (const [gx, gy] of path) {
    t += 200
    const r = mosaic.track(readWindow(frame(map, gx, gy, t, { heading: (t / 20) % 360 }), REGION), t)
    assert.notEqual(r.state, 'lost', `lost at ${t}`)
    if (r.point) got.push({ x: r.point.x, y: r.point.y, gx, gy })
  }
  assert.equal(mosaic.pieceCount, 1)
  const [o] = got
  const errs = got.map((p) => Math.hypot(p.x - o.x - (p.gx - o.gx) / K, p.y - o.y - (p.gy - o.gy) / K))
  const sorted = [...errs].sort((a, b) => a - b)
  assert.ok(sorted[Math.floor(sorted.length * 0.95)] <= 2.5, `95th percentile error ${sorted[Math.floor(sorted.length * 0.95)]}`)
  // Explored: much more than one window.
  assert.ok(mosaic.seenArea > MM * MM * 2, `seen ${mosaic.seenArea}`)
})

test('finds the player again after a respawn on known ground, and starts a new piece on unexplored ground that joins up later', () => {
  const mosaic = new MinimapMosaic('Test Map')
  const first = walk([360, 360], 200, 2)
  let t = 0
  const track = (gx: number, gy: number) => {
    t += 200
    return mosaic.track(readWindow(frame(map, gx, gy, t), REGION), t)
  }
  let origin: { piece: number; x: number; y: number; gx: number; gy: number } | null = null
  for (const [gx, gy] of first) {
    const r = track(gx, gy)
    if (!origin && r.point) origin = { piece: r.point.piece, x: r.point.x, y: r.point.y, gx, gy }
  }
  const o = origin!
  // Where a map point should be, on whichever piece the first one ended up in.
  const expect = (gx: number, gy: number) => mosaic.resolve(o.piece, o.x + (gx - o.gx) / K, o.y + (gy - o.gy) / K)
  // A death: a few frames without a minimap, then a respawn where the player has been.
  mosaic.lose()
  const [sx, sy] = first[60]
  let found = null
  for (let i = 0; i < 4 && !found; i++) {
    const r = track(sx, sy)
    if (r.state === 'found') found = r.point
  }
  assert.ok(found, 'found again')
  const e = expect(sx, sy)
  assert.ok(Math.hypot(found!.x - e.x, found!.y - e.y) <= 3, `respawn placed ${found!.x},${found!.y} vs ${e.x},${e.y}`)
  // A respawn somewhere never seen: a new piece after a few frames.
  mosaic.lose()
  const far: [number, number] = [600, 140]
  let state = ''
  for (let i = 0; i < 5 && state !== 'new'; i++) state = track(far[0], far[1]).state
  assert.equal(state, 'new')
  assert.equal(mosaic.pieceCount >= 1, true)
  // Walk from there back across the explored area: the two pieces join.
  const back: [number, number][] = []
  for (let i = 1; i <= 90; i++) back.push([far[0] + ((360 - far[0]) * i) / 90, far[1] + ((360 - far[1]) * i) / 90])
  let last = null
  for (const [gx, gy] of back) last = track(gx, gy).point
  assert.equal(mosaic.pieceCount, 1, 'joined into one piece')
  const here = mosaic.here()!
  const e2 = expect(back.at(-1)![0], back.at(-1)![1])
  assert.ok(Math.hypot(here.x - e2.x, here.y - e2.y) <= 3, `after joining ${here.x},${here.y} vs ${e2.x},${e2.y}`)
  // The respawn point, recorded on the new piece, now resolves onto the main one.
  const firstNew = mosaic.trail.find((p) => p.jump && p.piece !== o.piece)!
  const r = mosaic.resolve(firstNew.piece, firstNew.x, firstNew.y)
  const e3 = expect(far[0], far[1])
  assert.ok(Math.hypot(r.x - e3.x, r.y - e3.y) <= 4, `respawn on the joined piece ${r.x},${r.y} vs ${e3.x},${e3.y}`)
  assert.ok(last)
})

test('enemy pings are found on the minimap and placed relative to the player', () => {
  const w = readWindow(frame(map, 360, 360, 1000, { enemies: [{ x: 360 + 40, y: 360 - 25 }] }), REGION)
  assert.equal(w.enemies.length, 1)
  assert.ok(Math.abs(w.enemies[0].dx - 40 / K) <= 2 && Math.abs(w.enemies[0].dy + 25 / K) <= 2, JSON.stringify(w.enemies))
  // Icons and the arrow are not stitched.
  const c = (MM / 2) * MM + MM / 2
  assert.equal(w.mask[c], 0)
})

test('the learned map is kept and the player is found on it next match; a still screen is never kept', () => {
  const mosaic = new MinimapMosaic('Test Map')
  const path = walk([300, 420], 160, 4)
  let t = 0
  for (const [gx, gy] of path) {
    t += 200
    mosaic.track(readWindow(frame(map, gx, gy, t), REGION), t)
  }
  mosaic.learnCallout('Market', mosaic.here()!)
  mosaic.learnHeight('upper', mosaic.here()!)
  const saved = JSON.parse(JSON.stringify(mosaic.save(new Date('2026-10-28T12:00:00Z'))))
  const clean = cleanMosaic(saved)!
  assert.ok(clean)
  assert.equal(clean.pieces.length, 1)
  assert.ok(clean.callouts.Market)
  assert.equal(Object.keys(clean.heights).length, 1)
  const next = new MinimapMosaic('Test Map', clean)
  const [gx, gy] = path[80]
  let r = next.track(readWindow(frame(map, gx, gy, 1), REGION), 1)
  for (let i = 2; i < 5 && r.state !== 'found'; i++) r = next.track(readWindow(frame(map, gx, gy, i), REGION), i)
  assert.equal(r.state, 'found')
  // A menu: the same picture every frame. It "tracks" without moving, so nothing is kept.
  const menu = new MinimapMosaic('Menu')
  for (let i = 0; i < 40; i++) menu.track(readWindow(frame(map, 200, 200, 5), REGION), i * 200)
  assert.equal(menu.save().pieces.length, 0)
  assert.equal(cleanMosaic({ version: 1, map: 'x', nextId: 1, pieces: [{ id: 1, x0: -1, y0: 0, w: 1, h: 1, mean: '', weight: '' }] }), null)
})
