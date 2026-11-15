import { test } from 'node:test'
import assert from 'node:assert/strict'
import { duration, groundTime, seconds, SPEED, straightSeconds, tripShort, tripTime } from '../src/core/dayz/travel.ts'
import { takeMeTo, whatNow, isTakeMe } from '../src/core/dayz/assistant.ts'
import { routeLine, findPlace } from '../src/core/dayz/places.ts'

test('durations read like a person says them', () => {
  assert.equal(duration(20), 'under a minute')
  assert.equal(duration(6 * 60), 'about 6 min')
  assert.equal(duration(80 * 60), 'about 1 h 20 min')
  assert.equal(duration(120 * 60), 'about 2 h')
})

test('on foot is jogging pace, water is swum, a car is faster on roads than off them', () => {
  assert.equal(Math.round(seconds({ lengthM: 1000, road: 0 }, 'foot')), Math.round(1000 / SPEED.jog))
  assert.ok(seconds({ lengthM: 1000, road: 0, water: 0.5 }, 'foot') > seconds({ lengthM: 1000, road: 0 }, 'foot') * 2)
  assert.ok(seconds({ lengthM: 5000, road: 1 }, 'car') < seconds({ lengthM: 5000, road: 0 }, 'car') / 2)
  // Silly input does not break it.
  assert.ok(Number.isFinite(seconds({ lengthM: 1000, road: Number.NaN }, 'car')))
})

test('a straight line adds a detour; short trips do not mention a car', () => {
  assert.ok(straightSeconds(1000, 'foot') > 1000 / SPEED.jog)
  assert.equal(tripTime(800), `${duration(straightSeconds(800, 'foot'))} jogging`)
  assert.match(tripTime(4000), /^about \d+ min jogging(?: \(\d+ with sprints\))?, \d+ min by car$/)
  assert.match(tripTime(30000), /^about 2 h( \d+ min)? jogging \(1 h \d+ min with sprints\), \d+ min by car$/)
  assert.match(tripShort(2000), /^\d+ min on foot$/)
  assert.match(groundTime({ lengthM: 9000, road: 0.8 }, 'car'), /^about \d+ min by car$/)
})

test('every going-somewhere answer says how long it takes', () => {
  const spot = { x: 8960, y: 13550, label: 'Turovo' }
  assert.match(takeMeTo('take me to Berezino', { x: 13000, y: 9800, label: 'grid 130 056' })!.text, /min jogging/)
  assert.match(takeMeTo('take me to the nearest police station', spot)!.text, /: about \d+ min jogging/)
  const plan = whatNow({ x: 7000, y: 3000, label: 'Chernogorsk' }, new Set())
  assert.ok(plan.steps.every((s) => /\(\d+ min on foot\)|under a minute/.test(s.line) || /min on foot/.test(s.line)))
  assert.match(plan.text, /All of it: about \d+ (min|h)/)
  assert.match(routeLine(findPlace('Chernogorsk')!, findPlace('Elektrozavodsk')!), /; about \d+ min jogging(?: \(\d+ with sprints\))?, \d+ min by car(?:; [^.]+)?\.$/)
})

test('"how long to get to" and "how far to" count as asking the way', () => {
  assert.ok(isTakeMe('how long does it take to get to Berezino'))
  assert.ok(isTakeMe('how long to walk to the nearest hospital'))
  assert.ok(isTakeMe('how far to Zelenogorsk'))
  assert.ok(!isTakeMe('how far does the mosin shoot'))
})

test('sprints are generalised: faster than jogging, shown only when they save a minute', () => {
  assert.ok(seconds({ lengthM: 3000, road: 0 }, 'foot', true) < seconds({ lengthM: 3000, road: 0 }, 'foot'))
  assert.match(tripTime(4000), /^about (\d+) min jogging \((\d+) with sprints\)/)
  const [, jog, sprint] = tripTime(4000).match(/^about (\d+) min jogging \((\d+) with sprints\)/)!
  assert.ok(Number(sprint) < Number(jog))
  assert.doesNotMatch(tripTime(300), /sprints/)
  assert.match(groundTime({ lengthM: 3000, road: 0.5 }, 'foot'), /^about \d+ min jogging \(\d+ with sprints\)$/)
  // Water is swum either way.
  assert.equal(Math.round(seconds({ lengthM: 1000, road: 0, water: 1 }, 'foot', true)), Math.round(1000 / SPEED.swim))
})

test('hills: heights from Bohemia’s files, slower uphill, climbs said, routes weigh them', async () => {
  const { heightsFor, heightAt, climbAlong, climbWords, slopeFactor, tripBetween } = await import('../src/core/dayz/heights.ts')
  const { findPlace } = await import('../src/core/dayz/places.ts')
  const H = heightsFor('chernarus')
  const cherno = findPlace('Chernogorsk', 'chernarus')!.place
  const nwaf = findPlace('NWAF', 'chernarus')!.place
  assert.ok(heightAt(H, cherno.x, cherno.y) < 40, 'Cherno is on the coast')
  assert.ok(heightAt(H, nwaf.x, nwaf.y) > 250, 'NWAF is high up')
  assert.ok(slopeFactor(0.15, 'foot') > 1.5 && slopeFactor(-0.05, 'foot') < 1 && slopeFactor(0, 'car') === 1)
  const up = climbAlong(H, [cherno, nwaf], 'foot')
  const down = climbAlong(H, [nwaf, cherno], 'foot')
  assert.ok(up.upM > 250 && up.factor > down.factor)
  assert.match(climbWords(up)!, /^mostly uphill, about \d+ m of climbing$/)
  assert.match(tripBetween('chernarus', cherno, nwaf), /jogging .* by car; mostly uphill/)
  // A route over a terrain with a hill in the middle goes round it when that is cheaper.
  const { withHeights, groundPath } = await import('../src/core/dayz/terrain.ts')
  const n = 64
  const flat = { worldSize: 6400, size: n, cells: new Uint8Array(n * n).fill(2) }
  const hill = withHeights(flat, (x, y) => Math.max(0, 300 - Math.hypot(x - 3200, y - 3200) / 3))
  const straight = groundPath(flat, { x: 400, y: 3200 }, { x: 6000, y: 3200 })!
  const round = groundPath(hill, { x: 400, y: 3200 }, { x: 6000, y: 3200 })!
  assert.equal(straight.upM, 0)
  assert.ok(round.upM < 250, `climbed ${round.upM} m`)
  assert.ok(round.hill >= 1)
})
