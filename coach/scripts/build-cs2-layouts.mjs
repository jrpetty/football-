// Builds src/profiles/layouts/cs2/<map>.json: each Active Duty map's real layout from the game's
// nav mesh (the walkable floor the game uses for bots), as polygons in the radar overview's pixels
// (the pack's radar transform) with heights. No game art is used.
//
//   node scripts/build-cs2-layouts.mjs <folder with de_<map>.nav files> "<game build and date>"
//
// The nav files are in the game's pak01 (maps/de_<map>.nav); the awpy-data project publishes them
// per game build.
import { readFileSync, writeFileSync } from 'node:fs'
import { readNav } from './cs2-nav.mjs'

const [navDir, build = 'unknown build'] = process.argv.slice(2)
if (!navDir) throw new Error('usage: node scripts/build-cs2-layouts.mjs <nav folder> "<build>"')
const root = new URL('..', import.meta.url).pathname
const pack = JSON.parse(readFileSync(`${root}src/profiles/cs2.json`, 'utf8'))
for (const m of pack.knowledge.maps) {
  const r = m.radar
  const nav = readNav(readFileSync(`${navDir}/de_${m.id}.nav`))
  const verts = []
  const index = new Map()
  const vert = (x, y, z) => {
    const px = Math.round(((x - r.posX) / r.scale) * 4)
    const py = Math.round(((r.posY - y) / r.scale) * 4)
    const pz = Math.round(z)
    const k = `${px},${py},${pz}`
    let i = index.get(k)
    if (i === undefined) {
      i = verts.length / 3
      index.set(k, i)
      verts.push(px, py, pz)
    }
    return i
  }
  const polys = []
  const levels = []
  let lo = Infinity
  let hi = -Infinity
  let n = 0
  for (const a of nav.areas) {
    if (a.poly.length < 3) continue
    const ids = a.poly.map(([x, y, z]) => vert(x, y, z))
    polys.push(ids.length, ...ids)
    const cz = a.poly.reduce((s, p) => s + p[2], 0) / a.poly.length
    levels.push(typeof r.lowerBelowZ === 'number' && cz < r.lowerBelowZ ? 1 : 0)
    for (const p of a.poly) {
      lo = Math.min(lo, p[2])
      hi = Math.max(hi, p[2])
    }
    n++
  }
  const out = {
    v: 1,
    map: m.id,
    name: m.name,
    source: `The game's nav mesh (de_${m.id}.nav), ${build}`,
    size: r.imageSize ?? 1024,
    unitsPerPx: r.scale,
    z: [Math.floor(lo), Math.ceil(hi)],
    ...(typeof r.lowerBelowZ === 'number' ? { lowerBelowZ: r.lowerBelowZ } : {}),
    areas: n,
    verts,
    polys,
    ...(levels.some(Boolean) ? { levels } : {}),
  }
  writeFileSync(`${root}src/profiles/layouts/cs2/${m.id}.json`, JSON.stringify(out))
  console.log(m.id, n, 'areas')
}
