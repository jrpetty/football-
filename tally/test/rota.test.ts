import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import {
  crewFor,
  crewStats,
  formatHours,
  formatTime,
  hoursFor,
  labourShareBp,
  parseTime,
  shiftCostPence,
  shiftAt,
  shiftMinutes,
  weekDays,
  weekStart,
  DEFAULT_SHIFT,
  type Person,
  type Shift,
} from '../src/core/rota.ts'

/** 18:00 to 23:30 — the hours these tests put people on for. Hours belong to
    the night now, so every shift here says which ones it means. */
const EVENING = { startMin: 1080, endMin: 1410 }
const on = (person: Person, date: string, hours = EVENING) => shiftAt(person.id, date, hours)
/** 18:00 to 00:30 — six and a half hours, over midnight. */
const LATE = { startMin: 1080, endMin: 30 }


const kelly: Person = { id: 'k', name: 'Kelly', slot: 1 }
const dave: Person = { id: 'd', name: 'Dave', slot: 2, ratePencePerHour: 1221 }

test('a shift that ends after midnight is not negative', () => {
  // Six until half twelve is six and a half hours, not minus seventeen.
  assert.equal(shiftMinutes({ startMin: 18 * 60, endMin: 30 }), 390)
  assert.equal(formatHours(390), '6h 30m')
})

test('an ordinary evening shift is measured the plain way', () => {
  assert.equal(shiftMinutes({ startMin: 1080, endMin: 1410 }), 330)
  assert.equal(formatHours(330), '5h 30m')
  assert.equal(formatHours(120), '2h')
  assert.equal(formatHours(45), '45m')
})

test('times survive a round trip', () => {
  assert.equal(formatTime(1080), '18:00')
  assert.equal(formatTime(30), '00:30')
  assert.equal(parseTime('18:00'), 1080)
  assert.equal(parseTime('00:30'), 30)
  assert.equal(parseTime('half six'), null)
  assert.equal(parseTime('25:00'), null)
  assert.equal(parseTime('18:70'), null)
})

test('the week runs Monday to Sunday', () => {
  // 2026-08-23 is a Sunday, so its week began on the 17th.
  assert.equal(weekStart('2026-08-23'), '2026-08-17')
  assert.equal(weekStart('2026-08-17'), '2026-08-17')
  assert.equal(weekStart('2026-08-18'), '2026-08-17')
  const days = weekDays('2026-08-17')
  assert.equal(days.length, 7)
  assert.equal(days[0], '2026-08-17')
  assert.equal(days[6], '2026-08-23')
})

test('a shift costs its hours at the rate, and nothing without one', () => {
  const withRate = on(dave, '2026-08-23', LATE)
  // 18:00 to 00:30 is 6.5 hours at £12.21 = £79.37 (rounded from 79.365).
  assert.equal(shiftCostPence(withRate, dave), 7937)
  assert.equal(shiftCostPence(on(kelly, '2026-08-23'), kelly), null)
})

test('a night with nobody priced reports an unknown cost, not a free night', () => {
  const shifts = [on(kelly, '2026-08-23')]
  const night = crewFor('2026-08-23', shifts, [kelly])
  assert.equal(night.costPence, null)
  assert.equal(night.minutes, 330)
})

test('a night mixes priced and unpriced people without inventing a rate', () => {
  const shifts = [on(kelly, '2026-08-23'), on(dave, '2026-08-23', LATE)]
  const night = crewFor('2026-08-23', shifts, [kelly, dave])
  assert.equal(night.shifts.length, 2)
  assert.equal(night.minutes, 330 + 390)
  // Only Dave has a rate, so the cost is Dave's alone — stated, not guessed at.
  assert.equal(night.costPence, 7937)
})

// --- where the hours come from ------------------------------------------------

test('a night already being worked sets the hours for whoever is added next', () => {
  const shifts = [on(kelly, '2026-08-23', { startMin: 12 * 60, endMin: 17 * 60 })]
  assert.deepEqual(hoursFor('2026-08-23', shifts), { startMin: 720, endMin: 1020 })
})

test('an empty night starts at the hours last put on, not at a person’s habit', () => {
  const shifts = [
    on(kelly, '2026-08-20', { startMin: 11 * 60, endMin: 15 * 60 }),
    on(dave, '2026-08-22', { startMin: 16 * 60, endMin: 23 * 60 }),
  ]
  // The 22nd is the latest night rostered, so a fresh night starts there.
  assert.deepEqual(hoursFor('2026-08-25', shifts), { startMin: 960, endMin: 1380 })
})

test('with nothing rostered at all it falls back to six until close', () => {
  assert.deepEqual(hoursFor('2026-08-23', []), DEFAULT_SHIFT)
  assert.equal(formatTime(DEFAULT_SHIFT.startMin), '18:00')
  assert.equal(formatTime(DEFAULT_SHIFT.endMin), '23:30')
})

test('labour share is basis points of takings', () => {
  assert.equal(labourShareBp(20000, 200000), 1000) // £200 of £2,000 is 10%
  assert.equal(labourShareBp(100, 0), null)
})

// --- the comparison ----------------------------------------------------------

function nights(spec: Array<[string, number | null]>) {
  return spec.map(([date, variancePence]) => ({ date, variancePence, takingsPence: 200000 }))
}

test('a night with no rota is left out rather than counted as a night off', () => {
  // Kelly worked one night. The other nine have no rota at all: if those were
  // treated as nights she was off, she would be compared against noise.
  const shifts = [on(kelly, '2026-08-01')]
  const stats = crewStats(
    nights([['2026-08-01', -1000], ...Array.from({ length: 9 }, (_, i) => [`2026-08-${String(i + 2).padStart(2, '0')}`, -5000] as [string, number])]),
    shifts,
    [kelly],
  )
  const k = stats[0]!
  assert.equal(k.comparedOn, 1)
  assert.equal(k.comparedOff, 0, 'nights with no rota must not count as nights off')
  assert.equal(k.differencePence, null)
  assert.equal(k.meaningful, false)
})

test('an unfinished night cannot be compared', () => {
  const shifts = [on(kelly, '2026-08-01'), on(dave, '2026-08-01', LATE)]
  const stats = crewStats(nights([['2026-08-01', null]]), shifts, [kelly, dave])
  assert.equal(stats[0]!.comparedOn, 0)
})

test('a real difference shows once both sides are big enough', () => {
  // Kelly works the first six nights, Dave the last six. Kelly's are £10 short,
  // Dave's balance — so Kelly's nights run £10 worse than the nights she is off.
  const dates = Array.from({ length: 12 }, (_, i) => `2026-08-${String(i + 1).padStart(2, '0')}`)
  const shifts: Shift[] = [
    ...dates.slice(0, 6).map((d) => on(kelly, d)),
    ...dates.slice(6).map((d) => on(dave, d, LATE)),
  ]
  const stats = crewStats(
    nights(dates.map((d, i) => [d, i < 6 ? -1000 : 0])),
    shifts,
    [kelly, dave],
  )
  const k = stats.find((s) => s.personId === 'k')!
  assert.equal(k.comparedOn, 6)
  assert.equal(k.comparedOff, 6)
  assert.equal(k.avgVarianceOnPence, -1000)
  assert.equal(k.avgVarianceOffPence, 0)
  assert.equal(k.differencePence, -1000)
  assert.equal(k.meaningful, true)

  const d = stats.find((s) => s.personId === 'd')!
  assert.equal(d.differencePence, 1000, 'the other side of the same comparison')
})

test('too few nights withholds the comparison rather than implying one', () => {
  const dates = ['2026-08-01', '2026-08-02', '2026-08-03', '2026-08-04']
  const shifts = [on(kelly, dates[0]!), on(dave, dates[1]!, LATE), on(dave, dates[2]!, LATE), on(dave, dates[3]!, LATE)]
  const stats = crewStats(nights(dates.map((d) => [d, -500])), shifts, [kelly, dave])
  assert.equal(stats.every((s) => !s.meaningful), true)
  // The averages are still computed — they are simply not called meaningful.
  assert.equal(stats.find((s) => s.personId === 'k')!.avgVarianceOnPence, -500)
})

test('someone who has left keeps the nights they worked', () => {
  const gone: Person = { ...kelly, archived: true }
  const shifts = [on(gone, '2026-08-01')]
  const stats = crewStats(nights([['2026-08-01', -1000]]), shifts, [gone])
  assert.equal(stats.length, 1, 'an archived person with shifts in the window still reports')
  assert.equal(stats[0]!.nightsOn, 1)
})

test('someone who left and worked nothing in the window drops out', () => {
  const gone: Person = { ...kelly, archived: true }
  const stats = crewStats(nights([['2026-08-01', -1000]]), [], [gone])
  assert.equal(stats.length, 0)
})

test('hours and wages accumulate per person across the window', () => {
  const shifts = [on(dave, '2026-08-01', LATE), on(dave, '2026-08-02', LATE)]
  const stats = crewStats(nights([['2026-08-01', 0], ['2026-08-02', 0]]), shifts, [dave])
  assert.equal(stats[0]!.minutes, 780)
  assert.equal(stats[0]!.costPence, 15873) // 13 hours at £12.21
})

// --- a person's own record ----------------------------------------------------

import { crewRanking } from '../src/core/rota.ts'


test("a person's nights are counted as balanced, short or over against the tolerance", () => {
  const dates = ['2026-08-01', '2026-08-02', '2026-08-03']
  const shifts = dates.map((d) => on(kelly, d))
  const stats = crewStats(
    [
      { date: dates[0]!, variancePence: -2, takingsPence: 200000 },   // within 5p
      { date: dates[1]!, variancePence: -1000, takingsPence: 200000 },
      { date: dates[2]!, variancePence: 800, takingsPence: 200000 },
    ],
    shifts,
    [kelly],
    5,
  )
  const k = stats[0]!
  assert.equal(k.balancedNights, 1)
  assert.equal(k.shortNights, 1)
  assert.equal(k.overNights, 1)
  assert.equal(k.worstNightPence, -1000)
  assert.equal(k.worstNightDate, '2026-08-02')
})

test('the ranking goes on how often nights balance, not the size of one bad one', () => {
  // Kelly: five nights, four balanced, one dreadful. Dave: five nights, two
  // balanced, three small shorts. Kelly's average is far worse; her record is
  // better, and the record is what is ranked.
  const dates = Array.from({ length: 10 }, (_, i) => `2026-09-${String(i + 1).padStart(2, '0')}`)
  const shifts = [
    ...dates.slice(0, 5).map((d) => on(kelly, d)),
    ...dates.slice(5).map((d) => on(dave, d, LATE)),
  ]
  const nights = [
    ...dates.slice(0, 5).map((d, i) => ({ date: d, variancePence: i === 0 ? -8000 : 0, takingsPence: 200000 })),
    ...dates.slice(5).map((d, i) => ({ date: d, variancePence: i < 3 ? -300 : 0, takingsPence: 200000 })),
  ]
  const ranked = crewRanking(crewStats(nights, shifts, [kelly, dave], 5))
  assert.equal(ranked[0]!.stat.name, 'Kelly')
  assert.equal(ranked[0]!.place, 1)
  assert.equal(ranked[0]!.balancedBp, 8000) // four nights in five
  assert.equal(ranked[1]!.balancedBp, 4000)
})

test('someone with too few nights is unranked rather than bottom', () => {
  const stats = crewStats(
    [{ date: '2026-08-01', variancePence: -5000, takingsPence: 200000 }],
    [on(kelly, '2026-08-01')],
    [kelly],
    5,
  )
  const ranked = crewRanking(stats)
  assert.equal(ranked[0]!.place, null, 'no record yet is not a bad record')
  assert.equal(ranked[0]!.balancedBp, 0, 'the rate is still reported, it is just not ranked')
})
