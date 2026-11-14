// Routes over the ground: the terrain grids read from Bohemia's map pictures,
// and the cheapest paths across them on foot and by car.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { classAt, decodeTerrain, groundPath, groundRoute, simplify, FOREST, OPEN, ROAD, WATER } from '../src/core/dayz/terrain.ts'
import { findPlace, distance } from '../src/core/dayz/places.ts'
import cher from '../src/profiles/dayz/terrain.chernarus.json' with { type: 'json' }
import liv from '../src/profiles/dayz/terrain.livonia.json' with { type: 'json' }
import sak from '../src/profiles/dayz/terrain.sakhal.json' with { type: 'json' }

const T = decodeTerrain(cher)
const place = (n: string) => findPlace(n)!.place

test('the grids decode whole, and their ground is where it should be', () => {
  for (const d of [cher, liv, sak]) {
    const t = decodeTerrain(d)
    assert.equal(t.cells.length, 1024 * 1024)
    for (const c of t.cells.subarray(0, 5000)) assert.ok(c >= 1 && c <= 4)
  }
  assert.equal(classAt(T, { x: 9000, y: 800 }), WATER, 'the sea south of Chernarus')
  assert.equal(classAt(decodeTerrain(sak), { x: 1000, y: 1000 }), WATER, "Sakhal's sea")
  // Every town sits on land.
  for (const n of ['Chernogorsk', 'Elektrozavodsk', 'Berezino', 'Turovo', 'Vybor Air Base']) assert.notEqual(classAt(T, place(n)), WATER, n)
  const share = (c: number) => T.cells.filter((x) => x === c).length / T.cells.length
  assert.ok(share(ROAD) > 0.01 && share(OPEN) > 0.2 && share(FOREST) > 0.2, 'roads, fields and forest all found')
})

test('on foot: along the coast it keeps to land and mostly to roads; never shorter than the straight line', () => {
  const a = place('Elektrozavodsk')
  const b = place('Chernogorsk')
  const p = groundPath(T, a, b, 'foot')!
  assert.ok(p.lengthM >= distance(a, b) - 1)
  assert.ok(p.lengthM < distance(a, b) * 1.5, `${p.lengthM}`)
  assert.equal(p.water, 0)
  assert.ok(p.road > 0.4, `road share ${p.road}`)
  for (const q of p.points) assert.notEqual(classAt(T, q), WATER)
})

test('by car: more road than on foot, longer if it must be, and no way to an island', () => {
  const a = place('Chernogorsk')
  const b = place('Vybor Air Base')
  const foot = groundPath(T, a, b, 'foot')!
  const car = groundPath(T, a, b, 'car')!
  assert.ok(car.road > foot.road, `${car.road} vs ${foot.road}`)
  assert.ok(car.lengthM >= foot.lengthM)
  assert.equal(car.forest, 0)
  // Skalisty Island, off the south-east coast.
  assert.equal(groundPath(T, a, { x: 13600, y: 3100 }, 'car'), null)
  const r = groundRoute(T, [a, place('Novoselki'), b], 'foot')
  assert.equal(r.legs.length, 2)
  assert.ok(r.legs.every(Boolean))
})

test('simplify keeps the shape and drops the points that add nothing', () => {
  const line = Array.from({ length: 50 }, (_, i) => ({ x: i * 10, y: i < 25 ? 0 : (i - 24) * 10 }))
  const s = simplify(line, 1)
  assert.equal(s.length, 3)
  assert.deepEqual(s[0], line[0])
  assert.deepEqual(s.at(-1), line.at(-1))
})
