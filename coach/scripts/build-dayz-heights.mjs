// Builds a height map of each DayZ map for hill-aware routes and times
// (src/profiles/dayz/heights.<map>.json) from Bohemia Interactive's DayZ
// Central Economy files (ADPL-SA). Every building in mapgrouppos.xml and every
// fruit tree, bush and other object in mapgroupcluster*.xml has a position with
// its height above sea level ("x height z"): hundreds of thousands of points
// across towns, fields and forests. They are averaged into a 128 × 128 grid
// (120 m squares on Chernarus); squares with no points take the distance-
// weighted mean of the nearest ones, and the open sea (read from the terrain
// grid, connected to the map edge) is sea level.
//
// The error is measured by leaving out one point in twenty and comparing it
// with the grid; it is printed, and written into the file.
//
//   node scripts/build-dayz-heights.mjs <central-economy-repo>

import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const [ceDir] = process.argv.slice(2)
if (!ceDir) {
  console.error('usage: node scripts/build-dayz-heights.mjs <central-economy-repo>')
  process.exit(1)
}
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'profiles', 'dayz')
const MAPS = [
  { id: 'chernarus', folder: 'dayzOffline.chernarusplus' },
  { id: 'livonia', folder: 'dayzOffline.enoch' },
  { id: 'sakhal', folder: 'dayzOffline.sakhal' },
]
const G = 128

function points(folder) {
  const dir = join(ceDir, folder)
  const files = readdirSync(dir).filter((f) => /^mapgroup(pos|cluster\d*)\.xml$/i.test(f))
  const out = []
  for (const f of files) {
    const xml = readFileSync(join(dir, f), 'utf8')
    for (const m of xml.matchAll(/pos="([-\d.]+) ([-\d.]+) ([-\d.]+)"/g)) out.push([+m[1], +m[3], +m[2]])
  }
  return { files, pts: out }
}

/** Open-sea cells of the terrain grid (water connected to the edge), at the height grid's size. */
function seaMask(id) {
  const t = JSON.parse(readFileSync(join(ROOT, `terrain.${id}.json`), 'utf8'))
  const n = t.size
  const runs = Buffer.from(t.rle, 'base64')
  const cells = new Uint8Array(n * n)
  let k = 0
  for (let i = 0; i + 1 < runs.length; i += 2) {
    cells.fill(runs[i], k, k + runs[i + 1])
    k += runs[i + 1]
  }
  const sea = new Uint8Array(n * n)
  const stack = []
  for (let i = 0; i < n; i++) for (const c of [i, (n - 1) * n + i, i * n, i * n + n - 1]) if (cells[c] === 4 && !sea[c]) (sea[c] = 1), stack.push(c)
  while (stack.length) {
    const c = stack.pop()
    const x = c % n
    const y = (c / n) | 0
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = x + dx
      const ny = y + dy
      if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
      const j = ny * n + nx
      if (!sea[j] && cells[j] === 4) (sea[j] = 1), stack.push(j)
    }
  }
  // Down to G × G: a height cell is sea when most of it is open sea.
  const f = n / G
  const out = new Uint8Array(G * G)
  for (let gy = 0; gy < G; gy++)
    for (let gx = 0; gx < G; gx++) {
      let s = 0
      for (let y = gy * f; y < (gy + 1) * f; y++) for (let x = gx * f; x < (gx + 1) * f; x++) s += sea[y * n + x]
      out[gy * G + gx] = s > (f * f) / 2 ? 1 : 0
    }
  return { worldSize: t.worldSize, sea: out }
}

function grid(pts, worldSize, sea, skip) {
  const cell = worldSize / G
  const sum = new Float64Array(G * G)
  const cnt = new Uint32Array(G * G)
  pts.forEach((p, i) => {
    if (skip && i % 20 === 0) return
    const gx = Math.min(G - 1, Math.max(0, Math.floor(p[0] / cell)))
    const gy = Math.min(G - 1, Math.max(0, Math.floor((worldSize - p[1]) / cell)))
    sum[gy * G + gx] += p[2]
    cnt[gy * G + gx]++
  })
  const h = new Float32Array(G * G).fill(NaN)
  for (let i = 0; i < G * G; i++) {
    if (cnt[i]) h[i] = sum[i] / cnt[i]
    else if (sea[i]) h[i] = 0
  }
  // Empty squares: the distance-weighted mean of known squares within 8 squares.
  const filled = Float32Array.from(h)
  for (let y = 0; y < G; y++)
    for (let x = 0; x < G; x++) {
      if (!Number.isNaN(h[y * G + x])) continue
      let ws = 0
      let vs = 0
      for (let r = 1; r <= 8 && ws === 0; r++)
        for (let dy = -r; dy <= r; dy++)
          for (let dx = -r; dx <= r; dx++) {
            const nx = x + dx
            const ny = y + dy
            if (nx < 0 || ny < 0 || nx >= G || ny >= G) continue
            const v = h[ny * G + nx]
            if (Number.isNaN(v)) continue
            const w = 1 / (dx * dx + dy * dy)
            ws += w
            vs += w * v
          }
      filled[y * G + x] = ws ? vs / ws : 0
    }
  return filled
}

/** Height at a world point, bilinear between square centres. */
function sample(h, worldSize, x, y) {
  const cell = worldSize / G
  const fx = Math.min(G - 1.001, Math.max(0, x / cell - 0.5))
  const fy = Math.min(G - 1.001, Math.max(0, (worldSize - y) / cell - 0.5))
  const x0 = Math.floor(fx)
  const y0 = Math.floor(fy)
  const tx = fx - x0
  const ty = fy - y0
  const a = h[y0 * G + x0]
  const b = h[y0 * G + x0 + 1]
  const c = h[(y0 + 1) * G + x0]
  const d = h[(y0 + 1) * G + x0 + 1]
  return a * (1 - tx) * (1 - ty) + b * tx * (1 - ty) + c * (1 - tx) * ty + d * tx * ty
}

for (const m of MAPS) {
  const { files, pts } = points(m.folder)
  const { worldSize, sea } = seaMask(m.id)
  // The error on points left out.
  const test = grid(pts, worldSize, sea, true)
  const errs = pts.filter((_, i) => i % 20 === 0).map((p) => Math.abs(sample(test, worldSize, p[0], p[1]) - p[2])).sort((a, b) => a - b)
  const median = errs[Math.floor(errs.length / 2)]
  const p90 = errs[Math.floor(errs.length * 0.9)]
  const h = grid(pts, worldSize, sea, false)
  const i16 = Int16Array.from(h, (v) => Math.round(v))
  let lo = Infinity
  let hi = -Infinity
  for (const v of i16) (lo = Math.min(lo, v)), (hi = Math.max(hi, v))
  const out = {
    source: `Heights of ${pts.length} buildings, trees and objects in Bohemia Interactive's DayZ Central Economy files (${files.join(', ')}), ADPL-SA, averaged into ${G} × ${G} squares; empty squares from the nearest ones; the open sea at 0 m. Built by scripts/build-dayz-heights.mjs.`,
    licence: 'ADPL-SA: non-commercial, DayZ/Arma only, share alike.',
    map: m.id,
    worldSize,
    size: G,
    points: pts.length,
    errorM: { median: Math.round(median * 10) / 10, p90: Math.round(p90 * 10) / 10 },
    rangeM: [lo, hi],
    b64: Buffer.from(i16.buffer).toString('base64'),
  }
  writeFileSync(join(ROOT, `heights.${m.id}.json`), JSON.stringify(out))
  console.log(`${m.id}: ${pts.length} points, heights ${lo}–${hi} m, error on left-out points: median ${out.errorM.median} m, 90% under ${out.errorM.p90} m`)
}
