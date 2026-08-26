import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { forecastWeek, MIN_FOR_WEATHER, type DayWeather, type TradedNight } from '../src/core/forecast.ts'
import { addDays, weekdayOf } from '../src/core/date.ts'

/** A pub that takes a fixed amount per weekday, so the baseline is knowable. */
const BY_WEEKDAY: Record<string, number> = {
  Monday: 60000, Tuesday: 55000, Wednesday: 70000, Thursday: 90000,
  Friday: 180000, Saturday: 220000, Sunday: 120000,
}

/** `count` nights back from a start date, taking exactly the weekday figure. */
function steadyTrade(start: string, count: number): TradedNight[] {
  return Array.from({ length: count }, (_, i) => {
    const date = addDays(start, -i)
    return { date, takingsPence: BY_WEEKDAY[weekdayOf(date)] as number }
  })
}

const nextWeek = (from: string) =>
  Array.from({ length: 7 }, (_, i) => ({ date: addDays(from, i + 1) }))

test('with no weather at all it still forecasts from the weekday pattern', () => {
  const history = steadyTrade('2026-08-23', 28)
  const f = forecastWeek(history, [], nextWeek('2026-08-23'))
  assert.equal(f.days.length, 7)
  assert.equal(f.weatherNights, 0)
  assert.equal(f.perDegreePence, null)
  // Every night lands exactly on its weekday figure, because that is all the
  // pub has ever taken on that day.
  for (const day of f.days) {
    assert.equal(day.estimatePence, BY_WEEKDAY[day.weekday])
    assert.equal(day.weatherApplied, false)
  }
})

test('a steady pub gets a tight range; a volatile one a wide one', () => {
  const steady = forecastWeek(steadyTrade('2026-08-23', 28), [], nextWeek('2026-08-23'))
  const noisy = forecastWeek(
    steadyTrade('2026-08-23', 28).map((n, i) => ({ ...n, takingsPence: n.takingsPence + (i % 2 ? 50000 : -50000) })),
    [],
    nextWeek('2026-08-23'),
  )
  assert.equal(steady.highPence - steady.lowPence, 0, 'nothing unexplained means no spread')
  assert.ok(noisy.highPence - noisy.lowPence > 100000, 'an unpredictable pub says so')
})

test('an unseen weekday falls back to the overall average rather than nothing', () => {
  // Only Fridays and Saturdays traded; the forecast still covers all seven.
  const history = steadyTrade('2026-08-23', 28).filter((n) => ['Friday', 'Saturday'].includes(weekdayOf(n.date)))
  const f = forecastWeek(history, [], nextWeek('2026-08-23'))
  const monday = f.days.find((d) => d.weekday === 'Monday')!
  assert.ok(monday.basePence > 0)
  assert.ok(monday.basePence > 150000, 'the average of Fri and Sat, having nothing better')
})

test('weather is ignored until there are enough nights of it', () => {
  const history = steadyTrade('2026-08-23', 28)
  // Only a handful of nights have weather — under the gate.
  const weather: DayWeather[] = history.slice(0, MIN_FOR_WEATHER - 1).map((n, i) => ({
    date: n.date, tempC: 15 + i, rainMm: 0,
  }))
  const f = forecastWeek(history, weather, nextWeek('2026-08-23').map((d) => ({ ...d, weather: { date: d.date, tempC: 28, rainMm: 0 } })))
  assert.equal(f.perDegreePence, null, 'no slope fitted from too little')
  assert.equal(f.days.every((d) => !d.weatherApplied), true)
})

test('a real warm-weather effect is found and applied', () => {
  // A pub that takes £30 more per degree above 15°C.
  const history = steadyTrade('2026-08-23', 40)
  const weather: DayWeather[] = history.map((n, i) => ({ date: n.date, tempC: 10 + (i % 15), rainMm: 0 }))
  const withEffect = history.map((n) => {
    const w = weather.find((x) => x.date === n.date)!
    return { ...n, takingsPence: n.takingsPence + (w.tempC - 15) * 3000 }
  })

  const f = forecastWeek(withEffect, weather, nextWeek('2026-08-23'))
  assert.ok(f.perDegreePence !== null)
  assert.ok(Math.abs((f.perDegreePence as number) - 3000) < 300, `got ${f.perDegreePence} a degree`)
})

test('rain pulls the estimate down, warmth pushes it up', () => {
  const history = steadyTrade('2026-08-23', 40)
  const weather: DayWeather[] = history.map((n, i) => ({ date: n.date, tempC: 10 + (i % 15), rainMm: i % 10 }))
  const withEffect = history.map((n) => {
    const w = weather.find((x) => x.date === n.date)!
    return { ...n, takingsPence: n.takingsPence + (w.tempC - 15) * 3000 - w.rainMm * 2000 }
  })

  const warmDry = forecastWeek(withEffect, weather, nextWeek('2026-08-23').map((d) => ({ ...d, weather: { date: d.date, tempC: 24, rainMm: 0 } })))
  const coldWet = forecastWeek(withEffect, weather, nextWeek('2026-08-23').map((d) => ({ ...d, weather: { date: d.date, tempC: 11, rainMm: 9 } })))
  assert.ok(warmDry.totalPence > coldWet.totalPence, 'a warm dry week beats a cold wet one')
  assert.ok((warmDry.perMmRainPence as number) < 0, 'rain reads as a negative')
})

test('a freak forecast cannot swing the estimate into nonsense', () => {
  const history = steadyTrade('2026-08-23', 40)
  const weather: DayWeather[] = history.map((n, i) => ({ date: n.date, tempC: 10 + (i % 15), rainMm: 0 }))
  const withEffect = history.map((n) => {
    const w = weather.find((x) => x.date === n.date)!
    return { ...n, takingsPence: n.takingsPence + (w.tempC - 15) * 3000 }
  })

  // Forty degrees is not a British pub garden; the cap must hold.
  const f = forecastWeek(withEffect, weather, nextWeek('2026-08-23').map((d) => ({ ...d, weather: { date: d.date, tempC: 40, rainMm: 0 } })))
  for (const day of f.days) {
    assert.ok(day.estimatePence <= day.basePence * 1.36, `${day.weekday} swung to ${day.estimatePence} from ${day.basePence}`)
  }
})

test('weather that never varied fits no slope rather than an invented one', () => {
  const history = steadyTrade('2026-08-23', 40)
  const weather: DayWeather[] = history.map((n) => ({ date: n.date, tempC: 18, rainMm: 0 }))
  const f = forecastWeek(history, weather, nextWeek('2026-08-23'))
  assert.equal(f.perDegreePence, null, 'a constant explains nothing')
})

test('a night with no forecast weather simply gets the baseline', () => {
  const history = steadyTrade('2026-08-23', 40)
  const weather: DayWeather[] = history.map((n, i) => ({ date: n.date, tempC: 10 + (i % 15), rainMm: 0 }))
  const upcoming = nextWeek('2026-08-23').map((d, i) => (i < 3 ? { ...d, weather: { date: d.date, tempC: 22, rainMm: 0 } } : d))
  const f = forecastWeek(history, weather, upcoming)
  assert.equal(f.days.filter((d) => d.weatherApplied).length, 3)
  assert.equal(f.days[6]!.estimatePence, f.days[6]!.basePence)
})

test('the week never estimates below nothing', () => {
  const history = steadyTrade('2026-08-23', 40).map((n) => ({ ...n, takingsPence: 100 }))
  const f = forecastWeek(history, [], nextWeek('2026-08-23'))
  assert.ok(f.lowPence >= 0)
  assert.ok(f.days.every((d) => d.lowPence >= 0))
})

test('an empty history forecasts nothing rather than dividing by zero', () => {
  const f = forecastWeek([], [], nextWeek('2026-08-23'))
  assert.equal(f.totalPence, 0)
  assert.equal(f.nightsUsed, 0)
  assert.equal(f.days.length, 7)
})

test('the weekly range is narrower than seven nightly ranges added up', () => {
  const noisy = steadyTrade('2026-08-23', 28).map((n, i) => ({ ...n, takingsPence: n.takingsPence + (i % 3) * 20000 }))
  const f = forecastWeek(noisy, [], nextWeek('2026-08-23'))
  const naive = f.days.reduce((a, d) => a + (d.highPence - d.estimatePence), 0)
  assert.ok(f.highPence - f.totalPence < naive, 'errors across nights partly cancel')
})

test('a dry fortnight still lets the temperature effect through', () => {
  // The regression that produced this test: with rain constant at zero the
  // two-variable system is singular, and an earlier version threw the
  // temperature fit away with it — disabling weather entirely for exactly the
  // spell when it matters most.
  const history = steadyTrade('2026-08-23', 40)
  const weather: DayWeather[] = history.map((n, i) => ({ date: n.date, tempC: 10 + (i % 15), rainMm: 0 }))
  const withEffect = history.map((n) => {
    const w = weather.find((x) => x.date === n.date)!
    return { ...n, takingsPence: n.takingsPence + (w.tempC - 15) * 3000 }
  })
  const f = forecastWeek(withEffect, weather, nextWeek('2026-08-23'))
  assert.ok(f.perDegreePence !== null, 'temperature is still fittable')
  assert.equal(f.perMmRainPence, 0, 'rain, having never varied, is flat')
})

test('a washout with steady temperature still lets rain through', () => {
  const history = steadyTrade('2026-08-23', 40)
  const weather: DayWeather[] = history.map((n, i) => ({ date: n.date, tempC: 16, rainMm: i % 12 }))
  const withEffect = history.map((n) => {
    const w = weather.find((x) => x.date === n.date)!
    return { ...n, takingsPence: n.takingsPence - w.rainMm * 2500 }
  })
  const f = forecastWeek(withEffect, weather, nextWeek('2026-08-23'))
  assert.ok((f.perMmRainPence as number) < -1000, `got ${f.perMmRainPence} per mm`)
})

// --- reading what the weather service sends back -------------------------------

import { readDaily } from '../src/weather/openMeteo.ts'

test('a normal response reads into day records', () => {
  const days = readDaily({
    daily: {
      time: ['2026-08-22', '2026-08-23'],
      temperature_2m_max: [21.4, 17.8],
      precipitation_sum: [0, 4.2],
    },
  })
  assert.equal(days.length, 2)
  assert.deepEqual(days[0], { date: '2026-08-22', tempC: 21, rainMm: 0 })
  assert.deepEqual(days[1], { date: '2026-08-23', tempC: 18, rainMm: 4 })
})

test('a day missing a figure is skipped, never defaulted to zero', () => {
  // A rainy day recorded as 0mm would quietly bias every fit that used it.
  const days = readDaily({
    daily: {
      time: ['2026-08-22', '2026-08-23'],
      temperature_2m_max: [21.4, null],
      precipitation_sum: [0, 4.2],
    },
  })
  assert.equal(days.length, 1)
  assert.equal(days[0]!.date, '2026-08-22')
})

test('a broken or changed response loses the weather rather than the app', () => {
  assert.deepEqual(readDaily(null), [])
  assert.deepEqual(readDaily({}), [])
  assert.deepEqual(readDaily({ daily: {} }), [])
  assert.deepEqual(readDaily({ daily: { time: 'tomorrow' } }), [])
  assert.deepEqual(readDaily('down for maintenance'), [])
})

test('a nonsense date is not taken as a date', () => {
  const days = readDaily({
    daily: { time: ['not-a-date'], temperature_2m_max: [20], precipitation_sum: [0] },
  })
  assert.equal(days.length, 0)
})
