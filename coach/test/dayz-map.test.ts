// The DayZ map's arithmetic: the view, zooming about a point, the viewBox,
// the point under the cursor, labels by zoom, positions the player types, and
// what is at a clicked point.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, statSync } from 'node:fs'
import { DAYZ_MAPS, findPlace, gridRef } from '../src/core/dayz/places.ts'
import { MIN_SPAN, clampView, focusOn, fullView, gridLines, labelled, parsePosition, pointAt, pointInfo, viewBox, zoomAt } from '../src/core/dayz/mapView.ts'

const cher = DAYZ_MAPS[0]

test('the view: whole map, zoom about the cursor, limits', () => {
  const v = fullView(cher)
  assert.deepEqual(v, { cx: 7680, cy: 7680, span: 15360 })
  // The point under the cursor stays put when zooming.
  const at = { x: 13550, y: 14110 }
  const z = zoomAt(cher, v, 4, at)
  assert.equal(z.span, 3840)
  const box = { width: 1000, height: 1000 }
  const px = ((at.x - viewBox(cher, v, 1).x) / v.span) * 1000
  const py = ((cher.worldSize - at.y - viewBox(cher, v, 1).y) / v.span) * 1000
  const back = pointAt(cher, z, box, px, py)
  assert.ok(Math.abs(back.x - at.x) < 1 && Math.abs(back.y - at.y) < 1, JSON.stringify(back))
  assert.equal(zoomAt(cher, v, 1000).span, MIN_SPAN)
  assert.equal(clampView(cher, { cx: -500, cy: 99999, span: 10 }).cx, 0)
  assert.equal(focusOn(cher, at).span, 2000)
})

test('the viewBox: north up, SVG y counted from the north edge', () => {
  const vb = viewBox(cher, { cx: 7680, cy: 12000, span: 2000 }, 1.5)
  assert.deepEqual(vb, { x: 7680 - 1500, y: 15360 - 12000 - 1000, w: 3000, h: 2000 })
  // The top-left pixel is the north-west of the view.
  const nw = pointAt(cher, { cx: 7680, cy: 12000, span: 2000 }, { width: 1500, height: 1000 }, 0, 0)
  assert.deepEqual(nw, { x: 6180, y: 13000 })
})

test('labels by zoom: cities always, villages close in, small places closest', () => {
  const kinds = (span: number) => new Set(cher.places.filter((p) => labelled(p, span, cher.worldSize)).map((p) => p.kind))
  assert.ok(kinds(15360).has('city') && !kinds(15360).has('village'))
  assert.ok(kinds(4000).has('village'))
  assert.ok(cher.places.filter((p) => labelled(p, 15360, cher.worldSize)).length < cher.places.filter((p) => labelled(p, 1000, cher.worldSize)).length)
})

test('positions the player types: a 100 m grid, a 1 km grid, a town in any spelling', () => {
  const g = parsePosition(cher, '135 012')!
  assert.equal(gridRef(cher, g.x, g.y), '135 012')
  assert.deepEqual([g.x, g.y], [13550, 15360 - 1250])
  assert.equal(gridRef(cher, parsePosition(cher, '135012')!.x, parsePosition(cher, '135012')!.y), '135 012')
  const km = parsePosition(cher, '13 01')!
  assert.deepEqual([km.x, km.y], [13500, 15360 - 1500])
  const t = parsePosition(cher, 'Typobo')!
  assert.equal(t.label, 'Turovo')
  assert.equal(parsePosition(cher, '999 999'), null)
  assert.equal(parsePosition(cher, 'Nadbór'), null, 'a Livonia town is not on Chernarus')
})

test('a clicked point: grid, region, the town it is in or near, water', () => {
  const turovo = findPlace('Turovo')!.place
  assert.match(pointInfo(cher, turovo).line, /^Grid 135 012, the far north-east of Chernarus\. (In Turovo|1\.0 km north-west of Svetloyarsk)\. Nearest water pump \d+ m [a-z-]+\.$/)
  const p = pointInfo(cher, { x: turovo.x, y: turovo.y - 1500 })
  assert.match(p.line, /^Grid 135 027, the far north-east of Chernarus\. \d+ m [a-z-]+ of Olsha\. Nearest water pump/)
  assert.equal(gridLines(cher).length, 16)
  assert.deepEqual(gridLines(cher).slice(0, 2).map((l) => l.label), ['000', '010'])
})

test('the map images are built and line up with the world size', () => {
  for (const m of DAYZ_MAPS) {
    const f = `src/ui/assets/dayz/${m.id}.jpg`
    assert.ok(existsSync(f), f)
    assert.ok(statSync(f).size > 200_000 && statSync(f).size < 4_000_000, `${f}: ${statSync(f).size}`)
  }
})
