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
  assert.match(tripTime(4000), /^about \d+ min jogging, \d+ min by car$/)
  assert.match(tripTime(30000), /^about 2 h( \d+ min)? jogging, \d+ min by car$/)
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
  assert.match(routeLine(findPlace('Chernogorsk')!, findPlace('Elektrozavodsk')!), /; about \d+ min jogging, \d+ min by car\.$/)
})

test('"how long to get to" and "how far to" count as asking the way', () => {
  assert.ok(isTakeMe('how long does it take to get to Berezino'))
  assert.ok(isTakeMe('how long to walk to the nearest hospital'))
  assert.ok(isTakeMe('how far to Zelenogorsk'))
  assert.ok(!isTakeMe('how far does the mosin shoot'))
})
