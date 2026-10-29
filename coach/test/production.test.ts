// Town Center idle time from the villager counter, with its exceptions:
// full population, housed (only a villager waiting counts), lost villagers,
// gaps and misreads.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ProductionTracker, productionLines, summarise, type ProductionReading } from '../src/core/aoe4/production.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'

/** A game read every `every` seconds: villagers appear at `made`, lost at `lost`; population = villagers + army, cap from `cap(t)`. */
function game(opts: { until: number; made: number[]; lost?: number[]; army?: (t: number) => number; cap?: (t: number) => number; every?: number; misread?: Record<number, number> }): ProductionReading[] {
  const out: ProductionReading[] = []
  for (let t = 0; t <= opts.until; t += opts.every ?? 1) {
    const villagers = 6 + opts.made.filter((m) => m <= t).length - (opts.lost ?? []).filter((m) => m <= t).length
    const population = villagers + (opts.army?.(t) ?? 0)
    out.push({ t, villagers: opts.misread?.[t] ?? villagers, population, populationCap: opts.cap?.(t) ?? 200 })
  }
  return out
}

const every20 = (from: number, to: number) => Array.from({ length: Math.floor((to - from) / 20) + 1 }, (_, i) => from + i * 20)

function run(readings: ProductionReading[], trainSec = 20) {
  const tr = new ProductionTracker(trainSec)
  for (const r of readings) tr.observe(r)
  return tr
}

test('a Town Center that never stops: no idle time', () => {
  const s = run(game({ until: 301, made: every20(20, 300) })).summary()!
  assert.equal(s.idleSec, 0)
  assert.equal(s.villagersMade, 15)
  assert.equal(productionLines(s)[0], 'Town Center: no idle time seen between 0:00 and 5:01 (15 villagers made).')
})

test('an idle stretch: the time past the next villager being due, with when it started', () => {
  // Villagers at 20, 40, 60, then nothing until 120: idle from 80 (60 + 20) to 120.
  const s = run(game({ until: 200, made: [20, 40, 60, ...every20(120, 200)] })).summary()!
  assert.deepEqual(s.stretches, [{ at: 80, sec: 40 }])
  assert.equal(s.idleSec, 40)
  const lines = productionLines(s)
  assert.equal(lines[0], 'Town Center idle 40 s in total, the longest 40 s at 1:20.')
  assert.doesNotMatch(lines.join(' '), BANNED_CLAIMS)
})

test('housed with a villager waiting: counted on its own, from when it was due until space was added', () => {
  // Villagers at 20, 40; housed at 50 (population 30 of 30, army 22); a house at 90 and the waiting villager appears at 90.
  const s = run(game({ until: 200, made: [20, 40, ...every20(90, 200)], army: () => 22, cap: (t) => (t < 90 ? 30 : 40) })).summary()!
  assert.equal(s.idleSec, 0, 'housed time is not idle time')
  assert.deepEqual(s.housedWaits, [{ at: 60, sec: 30 }])
  assert.equal(s.housedWaitSec, 30)
  assert.ok(productionLines(s).some((l) => l === 'Housed with a villager waiting: 30 s at 1:00.'))
})

test('housed with nothing waiting: not counted at all; idle before and after it still is', () => {
  // Villagers at 20, 40; the Town Center stops (idle from 60); housed 70–100 by the army; next villager at 130.
  const army = (t: number) => (t < 70 ? 10 : 22)
  const s = run(game({ until: 240, made: [20, 40, ...every20(130, 240)], army, cap: (t) => (t < 100 ? 30 : 40) })).summary()!
  assert.equal(s.housedWaitSec, 0)
  assert.deepEqual(s.stretches, [{ at: 60, sec: 40 }], '60→130 is 70 s, less 30 s housed')
  assert.equal(s.excluded.housedSec, 30)
  assert.ok(productionLines(s).some((l) => /^Not counted: 30 s housed with no villager waiting\.$/.test(l)))
})

test('at full population nothing is counted', () => {
  // 200 of 200 from 100 to 300; no villagers made then.
  const s = run(game({ until: 320, made: [...every20(20, 100), 320], army: (t) => (t >= 100 ? 200 - 11 : 0), cap: () => 200 })).summary()!
  assert.equal(s.idleSec, 0)
  assert.ok(s.excluded.fullSec >= 195, String(s.excluded.fullSec))
})

test('villagers lost during a stretch: it cannot be judged (a loss hides one made)', () => {
  const s = run(game({ until: 200, made: [20, 40, 60, ...every20(140, 200)], lost: [90, 91] })).summary()!
  assert.equal(s.idleSec, 0)
  assert.ok(s.excluded.unclearSec >= 50, String(s.excluded.unclearSec))
})

test('one misread number makes neither a villager nor an idle stretch; readings every 3 s still work', () => {
  const s = run(game({ until: 201, made: every20(20, 200), misread: { 50: 66, 51: 6 } })).summary()!
  assert.equal(s.idleSec, 0)
  assert.equal(s.villagersMade, 10)
  const slow = run(game({ until: 300, made: [20, 40, 60, ...every20(150, 300)], every: 3 })).summary()!
  assert.equal(slow.stretches.length, 1)
  assert.ok(Math.abs(slow.stretches[0].sec - 70) <= 3, String(slow.stretches[0].sec))
  assert.equal(slow.readEverySec, 3)
})

test('from 100 villagers on nothing is judged', () => {
  const made = Array.from({ length: 100 }, (_, i) => 20 + i * 20)
  const s = run(game({ until: 2400, made })).summary()!
  assert.ok(s.reached100)
  assert.equal(s.idleSec, 0, 'no new villagers after 100 is not idle')
  assert.ok(productionLines(s).some((l) => /everything from 100 villagers on/.test(l)))
})

test('the live alert: once a villager is 15 s overdue (plus two reading intervals), once per stretch, never while housed', () => {
  const tr = new ProductionTracker(20)
  const alerts: string[] = []
  for (const r of game({ until: 160, made: [20, 40, ...every20(140, 160)] })) {
    tr.observe(r)
    const a = tr.alert(r.t)
    if (a) alerts.push(`${r.t}|${a.text}`)
  }
  // Due at 60; 15 s plus two 1-second readings overdue at 77.
  assert.deepEqual(alerts, ['77|No new villager for 37 seconds. Keep the Town Center working.'])
  const housed = new ProductionTracker(20)
  let any = false
  for (const r of game({ until: 160, made: [20, 40], army: () => 22, cap: () => 30 })) {
    housed.observe(r)
    any ||= !!housed.alert(r.t)
  }
  assert.ok(!any, 'housed from the start: the housed tip covers it')
})

test('summarise directly: an unknown civilisation time judges nothing', () => {
  assert.equal(new ProductionTracker(null).summary(), null)
  const s = summarise({ trainSec: 23, start: 0, end: 100, made: [23, 46, 100], housed: [], full: [], unclear: [], stopAt: null, readEverySec: 1 })
  assert.deepEqual(s.stretches, [{ at: 69, sec: 31 }])
})
