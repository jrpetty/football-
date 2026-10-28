// The real layouts of the Counter-Strike 2 maps (from the game's nav meshes):
// every Active Duty map has one, in the overview's own pixels, so the pack's
// callouts and Valve's grenade spots all sit on its floor; Nuke's two levels
// are told apart; the 3D heights follow the real floors.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { builtInProfile } from '../src/profiles/index.ts'
import { distanceToFloor, floorAt, hasLowerLevel, heightGrid, isMapLayout, layoutPolygons, type MapLayout } from '../src/core/map/layout.ts'

const cs2 = builtInProfile('cs2')
const layout = (id: string): MapLayout => {
  const l: unknown = JSON.parse(readFileSync(new URL(`../src/profiles/layouts/cs2/${id}.json`, import.meta.url), 'utf8'))
  assert.ok(isMapLayout(l), `${id} layout is well formed`)
  return l
}

test('every Active Duty map has its real layout, in the overview frame', () => {
  for (const m of cs2.knowledge.maps!) {
    const l = layout(m.id)
    assert.equal(l.size, m.radar!.imageSize ?? 1024)
    assert.equal(l.unitsPerPx, m.radar!.scale)
    assert.ok(l.areas > 1500, `${m.name}: ${l.areas} floor areas`)
    const polys = layoutPolygons(l)
    assert.equal(polys.length, l.areas)
    // The floor lies on the overview.
    for (const p of polys) for (const [x, y] of p.pts) assert.ok(x > -1 && y > -1 && x < l.size + 1 && y < l.size + 1, `${m.name} off the overview`)
  }
})

test("the pack's callouts and Valve's grenade spots stand on the real floor", () => {
  for (const m of cs2.knowledge.maps!) {
    const polys = layoutPolygons(layout(m.id))
    for (const [name, [x, y]] of Object.entries(m.radar!.calloutPx!)) assert.ok(distanceToFloor(polys, x, y) <= 2.5, `${m.name}: ${name} is off the floor`)
    for (const l of m.utility!.filter((u) => u.official)) {
      assert.ok(distanceToFloor(polys, l.standPx![0], l.standPx![1]) <= 3, `${m.name}: ${l.name} stand`)
      if (l.landPx) assert.ok(distanceToFloor(polys, l.landPx[0], l.landPx[1]) <= 6, `${m.name}: ${l.name} landing`)
    }
  }
})

test("Nuke's two levels, and real heights for the 3D model", () => {
  const nuke = layout('nuke')
  assert.ok(hasLowerLevel(nuke))
  const lower = layoutPolygons(nuke, 'lower')
  const upper = layoutPolygons(nuke, 'upper')
  assert.ok(lower.length > 300 && upper.length > 1500)
  assert.ok(lower.every((p) => p.z < nuke.lowerBelowZ!) && upper.every((p) => p.z >= nuke.lowerBelowZ!))
  // B site lies under A site: two floors at the same spot.
  const b = cs2.knowledge.maps!.find((m) => m.id === 'nuke')!.radar!.calloutPx!['B Site']
  const floors = floorAt([...lower, ...upper], b[0], b[1])
  assert.ok(floors.length >= 2 && floors[0] - floors[floors.length - 1] > 200, `floors at B: ${floors.join(', ')}`)
  for (const id of ['dust2', 'mirage', 'inferno']) assert.ok(!hasLowerLevel(layout(id)), id)
  // The height grid: real floor heights where there is floor, nothing elsewhere.
  const d2 = layout('dust2')
  const g = heightGrid(d2, 256, 256)
  const set = [...g].filter((v) => !Number.isNaN(v))
  assert.ok(set.length > 256 * 256 * 0.2 && set.length < 256 * 256 * 0.8)
  assert.ok(Math.min(...set) >= d2.z[0] - 1 && Math.max(...set) <= d2.z[1] + 1)
})
