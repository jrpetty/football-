// The official overhead map: its playable area, walls, solid structures and
// floors; the stitched minimap placed on it while playing (scale and
// position found on their own); re-finds after a respawn checked against it,
// so a map with two identical wings never puts the player in the wrong one.
// Synthetic maps stand in for the real images (which are not in the repo).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { analyzeOfficial, placePieces, KIND_FLOOR, KIND_INDOOR, KIND_OUT, KIND_SOLID, KIND_WALL, toOfficial, type OfficialMap } from '../src/core/map/official.ts'
import { cleanMosaic, fromBase64, MinimapMosaic, MM, readWindow, type StoredMosaic } from '../src/core/map/minimap.ts'
import { mapLayers, officialHeights, officialPoint } from '../src/core/map/view.ts'
import { mulberry32 } from '../src/sim/syntheticArena.ts'

/**
 * A synthetic official map, `size` pixels square: warm noise out of bounds, a bright boundary,
 * dark open ground, lighter rooms with bright walls, hatched solid blocks. `mirror`: the right
 * half repeats the left half (two identical wings).
 */
function officialImage(size: number, seed: number, mirror = false): Uint8ClampedArray {
  const rnd = mulberry32(seed)
  const img = new Uint8ClampedArray(size * size * 4)
  const set = (x: number, y: number, r: number, g: number, b: number) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return
    const o = (Math.round(y) * size + Math.round(x)) * 4
    img[o] = r
    img[o + 1] = g
    img[o + 2] = b
    img[o + 3] = 255
  }
  // Out of bounds: warm diagonal hatching, as on the real maps.
  for (let y = 0; y < size; y++)
    for (let x = 0; x < size; x++) {
      const on = (x + y) % 9 < 3
      set(x, y, (on ? 110 : 48) + rnd() * 10, (on ? 64 : 28) + rnd() * 6, (on ? 38 : 20) + rnd() * 4)
    }
  const b0 = Math.round(size * 0.12)
  const b1 = Math.round(size * 0.88)
  for (let y = b0; y < b1; y++) for (let x = b0; x < b1; x++) set(x, y, 18 + rnd() * 6, 28 + rnd() * 6, 42 + rnd() * 6)
  const rect = (x0: number, y0: number, w: number, h: number, fill: (x: number, y: number) => [number, number, number] | null, wall: boolean) => {
    for (let y = y0; y < y0 + h; y++)
      for (let x = x0; x < x0 + w; x++) {
        const edge = wall && (x === x0 || y === y0 || x === x0 + w - 1 || y === y0 + h - 1)
        const c = edge ? ([150, 220, 245] as [number, number, number]) : fill(x, y)
        if (c) set(x, y, ...c)
      }
  }
  const half = mirror ? (b0 + b1) / 2 : b1
  for (let i = 0; i < (mirror ? 14 : 26); i++) {
    const w = 20 + rnd() * 60
    const h = 20 + rnd() * 60
    const x = b0 + 6 + rnd() * (half - b0 - w - 12)
    const y = b0 + 6 + rnd() * (b1 - b0 - h - 12)
    const kind = rnd()
    const fill = (xx: number, yy: number): [number, number, number] =>
      kind < 0.3 ? ((xx + yy) % 6 < 2 ? [190, 110, 60] : [40, 30, 32]) : kind < 0.7 ? [60, 75, 95] : [24, 34, 50]
    rect(Math.round(x), Math.round(y), Math.round(w), Math.round(h), fill, true)
    if (kind >= 0.3 && kind < 0.7) rect(Math.round(x + w / 2), Math.round(y), 2, Math.round(h * 0.6), () => [150, 220, 245], false)
  }
  // Line work about as dense as on the real maps (a tenth of the playable area or so).
  for (let i = 0; i < 220; i++) rect(Math.round(b0 + 4 + rnd() * (half - b0 - 20)), Math.round(b0 + 4 + rnd() * (b1 - b0 - 20)), 6 + Math.round(rnd() * 8), 4, () => [150, 220, 245], false)
  if (mirror) {
    const shift = Math.round(half - b0)
    for (let y = b0; y < b1; y++)
      for (let x = b0; x < half; x++) {
        const o = (y * size + x) * 4
        const t = (y * size + x + shift) * 4
        img[t] = img[o]
        img[t + 1] = img[o + 1]
        img[t + 2] = img[o + 2]
      }
  }
  // The boundary.
  for (let i = b0; i < b1; i++) for (const [x, y] of [[i, b0], [i, b1 - 1], [b0, i], [b1 - 1, i]]) set(x, y, 200, 235, 255)
  return img
}

test('an official map: the playable area inside the boundary, walls, solid hatched blocks, floors; a photo is not a map', () => {
  const size = 600
  const off = analyzeOfficial(officialImage(size, 3), size, size)
  // The real maps score 0.88–1 (a screenshot of the game 0.33).
  assert.ok(off.score >= 0.85, `score ${off.score}`)
  assert.ok(Math.abs(off.playable - 0.76 * 0.76) < 0.03, `playable ${off.playable}`)
  assert.ok(Math.abs(off.box.x0 - 72) <= 2 && Math.abs(off.box.x1 - 527) <= 2, JSON.stringify(off.box))
  const share = (k: number) => off.kind.filter((x) => x === k).length / off.kind.length
  assert.ok(share(KIND_OUT) > 0.4)
  for (const k of [KIND_FLOOR, KIND_INDOOR, KIND_WALL, KIND_SOLID]) assert.ok(share(k) > 0.005, `kind ${k}: ${share(k)}`)
  // Noise with no boundary.
  const rnd = mulberry32(9)
  const noise = new Uint8ClampedArray(300 * 300 * 4).map((_, i) => (i % 4 === 3 ? 255 : rnd() * 255))
  assert.ok(analyzeOfficial(noise, 300, 300).score < 0.7)
})

/** A walking player's minimap cut from the official image (filtered, with the arrow). */
function play(off: OfficialMap, rgba: Uint8ClampedArray, size: number, opts: { lives: { x: number; y: number }[]; frames: number; seed: number; official?: boolean }) {
  const rnd = mulberry32(opts.seed)
  const FW = 144
  const PX = 130
  const region = { x: 7 / FW, y: 7 / FW, w: PX / FW, h: PX / FW }
  const span = 0.35 * size * 0.76
  const sample = (x: number, y: number, c: number) => rgba[(Math.max(0, Math.min(size - 1, Math.round(y))) * size + Math.max(0, Math.min(size - 1, Math.round(x)))) * 4 + c]
  const inBounds = (x: number, y: number) => off.kind[Math.round(y * off.k) * off.w + Math.round(x * off.k)] !== KIND_OUT
  const mosaic = new MinimapMosaic('Synthetic')
  if (opts.official !== false) mosaic.setOfficial(off)
  const truth = new Map<number, [number, number]>()
  let t = 0
  for (const start of opts.lives) {
    mosaic.lose()
    let { x, y } = start
    let a = rnd() * 6.28
    for (let f = 0; f < opts.frames; f++) {
      a += (rnd() - 0.5) * 0.5
      const nx = x + Math.cos(a) * 3
      const ny = y + Math.sin(a) * 3
      if (inBounds(nx, ny)) [x, y] = [nx, ny]
      else a += Math.PI * (0.5 + rnd())
      t += 200
      truth.set(t, [x, y])
      const data = new Uint8ClampedArray(FW * FW * 4).fill(20)
      for (let yy = 0; yy < PX; yy++)
        for (let xx = 0; xx < PX; xx++) {
          const sx = x + (xx - PX / 2 + 0.5) * (span / PX)
          const sy = y + (yy - PX / 2 + 0.5) * (span / PX)
          const o = ((7 + yy) * FW + 7 + xx) * 4
          // Filtered, as the game draws it: four samples over the pixel's footprint.
          const d = 0.35 * (span / PX)
          for (let c = 0; c < 3; c++) data[o + c] = (sample(sx - d, sy - d, c) + sample(sx + d, sy - d, c) + sample(sx - d, sy + d, c) + sample(sx + d, sy + d, c)) / 4
          data[o + 3] = 255
        }
      for (let yy = -5; yy <= 4; yy++) for (let xx = -6; xx <= 6; xx++) if (Math.abs(xx) <= (yy + 5) * 0.45) data.set([245, 240, 215], ((7 + PX / 2 + yy) * FW + 7 + PX / 2 + xx) * 4)
      mosaic.track(readWindow({ width: FW, height: FW, data, timestamp: t }, region), t)
    }
  }
  // Where each path point lands on the official image, against where the player was.
  const errs: number[] = []
  for (const p of mosaic.trail) {
    const r = mosaic.resolve(p.piece, p.x, p.y)
    const al = mosaic.alignmentFor(r.piece)
    const g = truth.get(p.t)
    if (!al || !g) continue
    const o = toOfficial(al, r.x, r.y)
    errs.push(Math.hypot(o.x - g[0], o.y - g[1]))
  }
  return { mosaic, errs: errs.sort((p, q) => p - q), points: mosaic.trail.length, scale: mosaic.knownScale(), span, truth }
}

test('the stitched minimap is placed on the official map while playing: scale and position found, every placed point within a few pixels', () => {
  const size = 600
  const rgba = officialImage(size, 5)
  const off = analyzeOfficial(rgba, size, size)
  const r = play(off, rgba, size, { lives: [{ x: 300, y: 300 }, { x: 150, y: 420 }, { x: 430, y: 160 }], frames: 120, seed: 2 })
  assert.ok(r.scale && Math.abs(r.scale / (r.span / MM) - 1) < 0.02, `scale ${r.scale} vs ${r.span / MM}`)
  // The second life hugs a hatched corner where following slides before anything is placed: that
  // piece stays off the official map (never drawn in the wrong place); the rest is placed.
  assert.ok(r.errs.length >= r.points * 0.6, `${r.errs.length} of ${r.points} placed`)
  assert.ok(r.errs[Math.floor(r.errs.length * 0.95)] <= 4, `p95 ${r.errs[Math.floor(r.errs.length * 0.95)]}`)
  assert.ok(r.errs[r.errs.length - 1] <= 12, `max ${r.errs[r.errs.length - 1]}`)
  // The placements are kept with the map.
  const saved = cleanMosaic(JSON.parse(JSON.stringify(r.mosaic.save())))!
  assert.ok(saved.aligned && Object.keys(saved.aligned).length >= 1)
  assert.deepEqual(saved.alignedTo, { w: size, h: size, id: off.id })
  // Placements made on another picture are not carried over to a new one.
  const again = new MinimapMosaic('Synthetic', saved)
  assert.ok(again.alignmentFor(Number(Object.keys(saved.aligned!)[0])))
  again.setOfficial(analyzeOfficial(officialImage(size, 6), size, size))
  assert.equal(again.alignmentFor(Number(Object.keys(saved.aligned!)[0])), null)
})

test('two identical wings: a respawn in the other wing is never put in the wrong one', () => {
  const size = 600
  const rgba = officialImage(size, 8, true)
  const off = analyzeOfficial(rgba, size, size)
  // Explore the left wing, then respawn in the right wing's identical spot.
  const r = play(off, rgba, size, { lives: [{ x: 180, y: 300 }, { x: 180, y: 250 }, { x: 180 + 228, y: 300 }, { x: 180 + 228, y: 260 }], frames: 110, seed: 4 })
  const wrong = r.errs.filter((e) => e > 40).length
  assert.equal(wrong, 0, `${wrong} points in the wrong wing (p95 ${r.errs[Math.floor(r.errs.length * 0.95)]})`)
  assert.ok(r.errs.length >= r.points * 0.5, `${r.errs.length} of ${r.points} placed`)
})

/** The path a mosaic recorded, as a match's pins would carry it. */
function matchOf(m: MinimapMosaic) {
  return { map: m.map, pins: [], trail: m.trail.map((p) => [p.piece, p.x, p.y, p.t / 1000, 0] as const), enemies: {} }
}

test('the views draw on the official map: every placed point where the player was, out of bounds sunk in 3D', () => {
  const size = 600
  const rgba = officialImage(size, 5)
  const off = analyzeOfficial(rgba, size, size)
  const r = play(off, rgba, size, { lives: [{ x: 300, y: 300 }, { x: 430, y: 160 }], frames: 100, seed: 3 })
  const saved = cleanMosaic(JSON.parse(JSON.stringify(r.mosaic.save())))!
  const layers = mapLayers(saved, [matchOf(r.mosaic)], off)!
  assert.ok(layers.official && layers.official.placed > 0)
  assert.equal(layers.image.w, off.w)
  const errs: number[] = []
  for (const p of r.mosaic.trail) {
    const at = officialPoint(saved, off, p.piece, p.x, p.y)
    const g = r.truth.get(p.t)
    if (at && g) errs.push(Math.hypot(at.x / off.k - g[0], at.y / off.k - g[1]))
  }
  errs.sort((a, b) => a - b)
  assert.ok(errs.length >= r.points * 0.8, `${errs.length} of ${r.points}`)
  assert.ok(errs[Math.floor(errs.length * 0.95)] <= 4, `p95 ${errs[Math.floor(errs.length * 0.95)]}`)
  // A picture the placements were not made on: nothing is drawn on it.
  const other = analyzeOfficial(officialImage(size, 6), size, size)
  assert.equal(mapLayers(saved, [matchOf(r.mosaic)], other)!.official!.placed, 0)
  // 3D: walls and solid blocks stand up, out of bounds is lower than the floor.
  const hts = officialHeights(layers)
  let wall = 0
  let out = 0
  for (let i = 0; i < hts.length; i++) {
    if (off.kind[i] === KIND_WALL) wall = Math.max(wall, hts[i])
    if (off.kind[i] === KIND_OUT) out = Math.min(out, hts[i])
  }
  assert.ok(wall > 0.4 && out < 0)
})

test('a map stitched before its official map was kept is placed on it afterwards', () => {
  const size = 600
  const rgba = officialImage(size, 5)
  const off = analyzeOfficial(rgba, size, size)
  const r = play(off, rgba, size, { lives: [{ x: 300, y: 300 }], frames: 140, seed: 5, official: false })
  const stored = cleanMosaic(JSON.parse(JSON.stringify(r.mosaic.save())))!
  assert.equal(stored.aligned, undefined)
  const aligned = placePieces(
    off,
    stored.pieces.map((p) => ({ piece: p.id, x0: p.x0, y0: p.y0, w: p.w, h: p.h, lum: fromBase64(p.mean), weight: fromBase64(p.weight) })),
  )
  assert.ok(Object.keys(aligned).length >= 1)
  const placed: StoredMosaic = { ...stored, aligned, alignedTo: { w: off.srcW, h: off.srcH, id: off.id } }
  const errs: number[] = []
  for (const p of r.mosaic.trail) {
    const at = officialPoint(placed, off, p.piece, p.x, p.y)
    const g = r.truth.get(p.t)
    if (at && g) errs.push(Math.hypot(at.x / off.k - g[0], at.y / off.k - g[1]))
  }
  errs.sort((a, b) => a - b)
  // Without frames to pin it down, the stitched map's small drift shows: still within a few pixels.
  assert.ok(errs.length >= r.points * 0.8, `${errs.length} of ${r.points}`)
  assert.ok(errs[Math.floor(errs.length * 0.95)] <= 8, `p95 ${errs[Math.floor(errs.length * 0.95)]}`)
})
