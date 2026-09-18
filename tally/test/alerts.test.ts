import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { alertSummary, MAX_ALERTS, weeklyAlerts, type AlertInput } from '../src/core/alerts.ts'
import type { DayStats, LikeForLike } from '../src/core/analytics.ts'
import { ML_PER_PINT, type DeadStockLine, type StockItem } from '../src/core/stock.ts'
import { margin } from '../src/core/margin.ts'

function night(date: string, variancePence: number | null, verdict: DayStats['verdict']): DayStats {
  return {
    date, weekday: 'Friday', takingsPence: 200000, cashPence: null, cardPence: null,
    guestCount: null, avePence: null, departments: [], variancePence,
    cashVariancePence: null, cardVariancePence: null, verdict,
    hasZRead: false, voidCount: null, voidPence: null, noSaleCount: null, clerks: [], items: [],
  }
}

const nothing: AlertInput = { recent: [] }

test('a quiet week says nothing at all', () => {
  assert.deepEqual(weeklyAlerts(nothing), [])
  assert.match(alertSummary([]), /Nothing worth bothering you about/)
})

// --- a weekday falling away ------------------------------------------------------

function yoy(changeBp: number, matchedNights: number): LikeForLike {
  return {
    nights: matchedNights, takingsPence: 100000,
    lastYearNights: matchedNights, lastYearTakingsPence: 100000,
    matchedNights, matchedPence: 88000, matchedLastYearPence: 100000,
    changeBp, comparable: true,
  }
}

test('a weekday well down on last year is worth saying', () => {
  const alerts = weeklyAlerts({ ...nothing, weekdayYoY: [{ weekday: 'Friday', change: yoy(-1200, 6) }] })
  assert.equal(alerts.length, 1)
  assert.match(alerts[0]!.headline, /Fridays down 12% on last year/)
  assert.equal(alerts[0]!.screen, 'trade')
})

test('a weekday barely down is not', () => {
  // The threshold exists so the list stays worth reading.
  assert.equal(weeklyAlerts({ ...nothing, weekdayYoY: [{ weekday: 'Friday', change: yoy(-400, 6) }] }).length, 0)
})

test('a weekday with too few nights to judge is not reported', () => {
  assert.equal(weeklyAlerts({ ...nothing, weekdayYoY: [{ weekday: 'Friday', change: yoy(-3000, 2) }] }).length, 0)
})

test('a weekday that is up is never an alert', () => {
  assert.equal(weeklyAlerts({ ...nothing, weekdayYoY: [{ weekday: 'Friday', change: yoy(1500, 6) }] }).length, 0)
})

// --- margin ----------------------------------------------------------------------

function gpReport(costPence: number) {
  const m = margin(400, costPence)
  return {
    lines: [{ code: '1', name: 'PINT TADDY LAGER', qtyMilli: 120_000, margin: m, periodProfitPence: 18000, missing: null }],
    profitPence: 18000, netSalesPence: 40000, blendedGpBp: m.gpBp, costedCount: 1, uncostedCount: 0,
  }
}

test('a line under the floor is flagged, with the figures in it', () => {
  // £4.00 costing £1.80 is 55% — under the 60% floor, but not yet alarming.
  const alerts = weeklyAlerts({ ...nothing, gp: gpReport(180) })
  assert.equal(alerts.length, 1)
  assert.match(alerts[0]!.headline, /PINT TADDY LAGER is making 55%/)
  assert.match(alerts[0]!.detail, /Selling at £4\.00 and costing £1\.80/)
  assert.equal(alerts[0]!.level, 'warn')
})

test('a line well under it is reported more urgently', () => {
  // £2.20 of cost on a £4.00 pint is 45%, which is a problem rather than a note.
  const alerts = weeklyAlerts({ ...nothing, gp: gpReport(220) })
  assert.equal(alerts[0]!.level, 'bad')
  assert.match(alerts[0]!.headline, /making 45%/)
})

test('a healthy line is left alone', () => {
  const alerts = weeklyAlerts({
    ...nothing,
    gp: {
      lines: [{ code: '1', name: 'PINT TADDY LAGER', qtyMilli: 120_000, margin: margin(400, 120), periodProfitPence: 25000, missing: null }],
      profitPence: 25000, netSalesPence: 40000, blendedGpBp: 6400, costedCount: 1, uncostedCount: 0,
    },
  })
  assert.equal(alerts.length, 0)
})

test('a thin margin on something that barely sells is not worth a alert', () => {
  const alerts = weeklyAlerts({
    ...nothing,
    gp: {
      lines: [{ code: '9', name: 'ODDITY', qtyMilli: 3_000, margin: margin(400, 300), periodProfitPence: 100, missing: null }],
      profitPence: 100, netSalesPence: 1000, blendedGpBp: 1000, costedCount: 1, uncostedCount: 0,
    },
  })
  assert.equal(alerts.length, 0, 'three of them went out; it is not the problem')
})

test('a cost rise nobody passed on is named with both figures', () => {
  const alerts = weeklyAlerts({
    ...nothing,
    moves: [{
      code: '1', name: 'PINT TADDY LAGER', fromDate: '2026-01-01', toDate: '2026-06-01',
      costThenPence: 132, costNowPence: 150, priceThenPence: 400, priceNowPence: 400,
      then: margin(400, 132), now: margin(400, 150),
      gpChangeBp: -540, verdict: 'squeezed',
    }],
  })
  assert.match(alerts[0]!.headline, /costs £1\.50 now, up from £1\.32/)
  assert.match(alerts[0]!.detail, /board still says £4\.00/)
})

test('a cost rise that was passed on is not an alert', () => {
  const alerts = weeklyAlerts({
    ...nothing,
    moves: [{
      code: '1', name: 'PINT TADDY LAGER', fromDate: '2026-01-01', toDate: '2026-06-01',
      costThenPence: 132, costNowPence: 150, priceThenPence: 400, priceNowPence: 455,
      then: margin(400, 132), now: margin(455, 150),
      gpChangeBp: 20, verdict: 'kept up',
    }],
  })
  assert.equal(alerts.length, 0)
})

// --- the cellar and the drawer -----------------------------------------------------

test('a cellar well out is reported with the direction named', () => {
  const light = weeklyAlerts({ ...nothing, cellarGapPence: -60000 })
  assert.match(light[0]!.headline, /cellar is £600\.00 light/)
  assert.equal(light[0]!.level, 'bad')

  const heavy = weeklyAlerts({ ...nothing, cellarGapPence: 60000 })
  assert.match(heavy[0]!.headline, /£600\.00 heavy/)
  assert.match(heavy[0]!.detail, /booked in twice/)
})

test('a cellar close enough is not mentioned', () => {
  assert.equal(weeklyAlerts({ ...nothing, cellarGapPence: -5000 }).length, 0)
  assert.equal(weeklyAlerts({ ...nothing, cellarGapPence: null }).length, 0)
})

test('a run of short nights is a pattern; one short night is not', () => {
  const run = [
    night('2026-08-23', -1200, 'short'), night('2026-08-22', -800, 'short'),
    night('2026-08-21', -1500, 'short'), night('2026-08-20', -900, 'short'),
    night('2026-08-19', 0, 'balanced'),
  ]
  const alerts = weeklyAlerts({ recent: run })
  assert.equal(alerts.length, 1)
  assert.match(alerts[0]!.headline, /short 4 of the last 5/)
  assert.match(alerts[0]!.detail, /£44\.00/)

  const one = [
    night('2026-08-23', -1200, 'short'), night('2026-08-22', 0, 'balanced'),
    night('2026-08-21', 0, 'balanced'), night('2026-08-20', 0, 'balanced'),
    night('2026-08-19', 0, 'balanced'),
  ]
  assert.equal(weeklyAlerts({ recent: one }).length, 0)
})

test('unfinished nights are not counted as balanced ones', () => {
  const nights = [
    night('2026-08-23', -1200, 'short'), night('2026-08-22', -800, 'short'),
    night('2026-08-21', -1500, 'short'), night('2026-08-20', -900, 'short'),
    night('2026-08-19', null, 'incomplete'), night('2026-08-18', null, 'incomplete'),
  ]
  // Four short of four judged — but four is under the five-night floor.
  assert.equal(weeklyAlerts({ recent: nights }).length, 0)
})

// --- stock and housekeeping ---------------------------------------------------------

const slowLine = (name: string, tiedUpPence: number): DeadStockLine => ({
  item: { id: name, name, kind: 'liquid', servingBaseUnits: ML_PER_PINT, servingName: 'pint' } as StockItem,
  onHandBaseUnits: 40 * ML_PER_PINT, perWeek: 1, weeksOfCover: 40, tiedUpPence, reason: 'not selling',
})

test('money sitting in slow lines is worth knowing about', () => {
  const alerts = weeklyAlerts({ ...nothing, deadStock: [slowLine('Obscure Porter', 30000), slowLine('Old Ale', 8000)] })
  assert.match(alerts[0]!.headline, /£380\.00 sitting in lines that barely sell/)
  assert.match(alerts[0]!.detail, /Obscure Porter/)
})

test('a little slow stock is not worth an alert', () => {
  assert.equal(weeklyAlerts({ ...nothing, deadStock: [slowLine('Old Ale', 5000)] }).length, 0)
})

test('a half-finished price list is mentioned once it is really half-finished', () => {
  assert.equal(weeklyAlerts({ ...nothing, unpricedCount: 4 }).length, 0)
  assert.match(weeklyAlerts({ ...nothing, unpricedCount: 14 })[0]!.headline, /14 lines still have no price/)
})

// --- keeping it readable --------------------------------------------------------------

test('the worst things come first and the list is capped', () => {
  const alerts = weeklyAlerts({
    recent: [
      night('2026-08-23', -1200, 'short'), night('2026-08-22', -800, 'short'),
      night('2026-08-21', -1500, 'short'), night('2026-08-20', -900, 'short'),
      night('2026-08-19', 0, 'balanced'),
    ],
    cellarGapPence: -60000,
    unpricedCount: 30,
    deadStock: [slowLine('Obscure Porter', 30000)],
    weekdayYoY: [
      { weekday: 'Friday', change: yoy(-1200, 6) },
      { weekday: 'Tuesday', change: yoy(-1800, 6) },
      { weekday: 'Sunday', change: yoy(-2000, 6) },
    ],
  })
  assert.equal(alerts.length, MAX_ALERTS, 'a list nobody finishes is a list nobody reads')
  assert.equal(alerts[0]!.level, 'bad')
  assert.equal(alerts.every((a, i) => i === 0 || alerts[i - 1]!.level <= a.level), true)
})

test('the one-line summary names the worst and counts the rest', () => {
  const alerts = weeklyAlerts({ ...nothing, cellarGapPence: -60000, unpricedCount: 30 })
  assert.match(alertSummary(alerts), /cellar is £600\.00 light — and 1 other thing\./)
})

test('every alert says where to look', () => {
  const alerts = weeklyAlerts({ ...nothing, cellarGapPence: -60000, unpricedCount: 30 })
  assert.equal(alerts.every((a) => ['trade', 'cellar', 'rota', 'nights'].includes(a.screen)), true)
})

test('the unpriced alert counts only lines actually missing a price', () => {
  // uncostedCount also counts lines that have a price but no cost, which is a
  // different job entirely — the alert must not claim the wrong one.
  const alerts = weeklyAlerts({ ...nothing, unpricedCount: 12 })
  assert.match(alerts[0]!.headline, /^12 lines still have no price set$/)
})


test('an old stock take stops being news', () => {
  // Without the cut-off a January variance tops the digest in the present
  // tense until somebody counts again in August.
  const fresh = weeklyAlerts({ ...nothing, cellarGapPence: -60000, cellarCountAgeDays: 10 })
  assert.equal(fresh.length, 1)

  const stale = weeklyAlerts({ ...nothing, cellarGapPence: -60000, cellarCountAgeDays: 200 })
  assert.equal(stale.length, 0, 'seven months old is history, not a finding')

  const unknownAge = weeklyAlerts({ ...nothing, cellarGapPence: -60000 })
  assert.equal(unknownAge.length, 1, 'no age given keeps the old behaviour')
})

test('a reconciled cellar (£0) is never an alert', () => {
  assert.equal(weeklyAlerts({ ...nothing, cellarGapPence: 0, cellarCountAgeDays: 5 }).length, 0)
})


// --- about to run out ------------------------------------------------------------
//
// The point of taking the till off the stock is not a tidier ledger, it is
// being told to order the Taddy before Thursday. So the rule has to fire on a
// line that is nearly gone, and stay quiet on everything that only looks like
// one.

const taddy: StockItem = {
  id: 'taddy', name: 'Taddy Lager', kind: 'liquid',
  servingBaseUnits: ML_PER_PINT, servingName: 'pint',
  container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT },
}
const crisps: StockItem = { id: 'crisps', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each' }

const lasts = (item: StockItem, servings: number, nightsLeft: number) => ({
  item,
  leftBaseUnits: servings * item.servingBaseUnits,
  perNightBaseUnits: nightsLeft > 0 ? (servings * item.servingBaseUnits) / nightsLeft : 0,
  nightsLeft,
})

test('a line with a night or two left in it is worth ordering', () => {
  const [alert] = weeklyAlerts({ recent: [], runway: [lasts(taddy, 24, 3)] })
  assert.ok(alert)
  assert.match(alert.headline, /Taddy Lager is nearly out/)
  assert.match(alert.detail, /24 pints/)
  assert.match(alert.detail, /about 3 more nights/)
  assert.equal(alert.screen, 'cellar')
})

test('a comfortable line says nothing', () => {
  assert.deepEqual(weeklyAlerts({ recent: [], runway: [lasts(taddy, 200, 25)] }), [])
})

test('a line already empty is not an alert, it is a reproach', () => {
  assert.deepEqual(weeklyAlerts({ recent: [], runway: [lasts(taddy, 0, 0)] }), [])
})

test('several short lines are one alert naming the worst, not one each', () => {
  const alerts = weeklyAlerts({
    recent: [],
    runway: [lasts(taddy, 24, 3), lasts(crisps, 9, 1)],
  })
  assert.equal(alerts.length, 1)
  assert.match(alerts[0]!.headline, /Crisps and 1 other line are nearly out/)
  assert.equal(alerts[0]!.level, 'warn', 'one night left is worth a warning, not a note')
})


// --- the weekly take, and the mix changing -----------------------------------------

test('a stock take a few days late is worth a nudge, not a telling-off', () => {
  assert.deepEqual(weeklyAlerts({ recent: [], takeOverdueDays: 2 }), [], 'two days is slippage, not news')
  const [alert] = weeklyAlerts({ recent: [], takeOverdueDays: 4 })
  assert.match(alert!.headline, /4 days overdue/)
  assert.equal(alert!.level, 'info')
  assert.equal(alert!.screen, 'cellar')
})

test('a fortnight late is a warning, because nothing can be judged until it is done', () => {
  assert.equal(weeklyAlerts({ recent: [], takeOverdueDays: 15 })[0]?.level, 'warn')
})

test('a line falling away over a month is worth a decision', () => {
  const [alert] = weeklyAlerts({
    recent: [],
    falling: [{
      kind: 'item', key: '1', label: 'PINT ALPINE', changeBp: -4200,
      nowPencePerNight: 5800, beforePencePerNight: 10000,
      nowQtyMilliPerNight: 11000, beforeQtyMilliPerNight: 20000,
      nights: 24, beforeNights: 24, recentPence: 139200,
    }],
  })
  assert.match(alert!.headline, /PINT ALPINE is down 42% a night/)
  assert.match(alert!.detail, /£100\.00 a night over the 24 nights before/)
  assert.equal(alert!.screen, 'trade')
})

test('a wobble is not a line falling away', () => {
  assert.deepEqual(
    weeklyAlerts({
      recent: [],
      falling: [{
        kind: 'item', key: '1', label: 'PINT ALPINE', changeBp: -1800,
        nowPencePerNight: 8200, beforePencePerNight: 10000,
        nowQtyMilliPerNight: 16000, beforeQtyMilliPerNight: 20000,
        nights: 24, beforeNights: 24, recentPence: 196800,
      }],
    }),
    [],
  )
})
