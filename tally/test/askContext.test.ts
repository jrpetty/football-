import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { buildAskPack, MAX_PACK_NIGHTS, type AskData } from '../src/core/askContext.ts'
import type { DayStats } from '../src/core/analytics.ts'
import { weekdayOf, addDays } from '../src/core/date.ts'
import { shiftFor, type Person } from '../src/core/rota.ts'
import type { StockItem } from '../src/core/stock.ts'
import { cellarHealth } from '../src/core/stock.ts'
import { costOf } from '../src/core/margin.ts'

function night(date: string, takingsPence: number, extra: Partial<DayStats> = {}): DayStats {
  return {
    date, weekday: weekdayOf(date), takingsPence, cashPence: 20000, cardPence: takingsPence - 20000,
    guestCount: 120, avePence: null, departments: [], variancePence: -320,
    cashVariancePence: null, cardVariancePence: null, verdict: 'short',
    hasZRead: true, voidCount: 2, voidPence: 840, noSaleCount: null, clerks: [],
    items: [], ...extra,
  }
}

function base(days: DayStats[], extra: Partial<AskData> = {}): AskData {
  return { days, book: [], cellar: null, people: [], shifts: [], weather: [], today: '2026-08-26', ...extra }
}

// --- the nights --------------------------------------------------------------

test('each night is one line with its figures, verdict and note', () => {
  const pack = buildAskPack(
    base([night('2026-08-21', 149225)], { notes: new Map([['2026-08-21', 'band on till late']]) }),
  )
  assert.ok(pack.text.includes('2026-08-21|Fri|£1,492.25|£200.00|£1,292.25|−£3.20|short|120|2 (£8.40)||band on till late'))
  assert.equal(pack.nightCount, 1)
})

test('the weather a night had is folded into its line', () => {
  const pack = buildAskPack(base([night('2026-08-21', 149225)], { weather: [{ date: '2026-08-21', tempC: 24, rainMm: 0 }] }))
  assert.ok(pack.text.includes('|24C 0mm|'))
})

test('nights come out oldest first whatever order they arrive in', () => {
  const pack = buildAskPack(base([night('2026-08-22', 100), night('2026-08-20', 100), night('2026-08-21', 100)]))
  const first = pack.text.indexOf('2026-08-20')
  const last = pack.text.indexOf('2026-08-22')
  assert.ok(first !== -1 && last !== -1 && first < last)
})

test('the pack is bounded and says what it left out', () => {
  const days: DayStats[] = []
  for (let i = 0; i < MAX_PACK_NIGHTS + 30; i++) days.push(night(addDays('2024-01-01', i), 100000))
  const pack = buildAskPack(base(days))
  assert.equal(pack.nightCount, MAX_PACK_NIGHTS)
  assert.ok(pack.text.includes('the 30 oldest are not in this pack'))
  // The oldest kept night is present; the one before it is not.
  assert.ok(pack.text.includes(addDays('2024-01-01', 30)))
  assert.ok(!pack.text.includes(`${addDays('2024-01-01', 29)}|`))
})

test('an empty app says so instead of sending headings over nothing', () => {
  const pack = buildAskPack(base([]))
  assert.ok(pack.text.includes('NIGHTS: none saved yet.'))
  assert.equal(pack.nightCount, 0)
})

test('a note cannot break the line format', () => {
  const pack = buildAskPack(
    base([night('2026-08-21', 100)], { notes: new Map([['2026-08-21', 'pipe | in\nthe note']]) }),
  )
  assert.ok(pack.text.includes('pipe   in the note'))
  // Still exactly eleven fields on the line.
  const line = pack.text.split('\n').find((l) => l.startsWith('2026-08-21|'))
  assert.equal(line?.split('|').length, 11)
})

// --- items and departments ---------------------------------------------------

test('item sales are totalled across nights under the code', () => {
  const days = [
    night('2026-08-20', 100, { items: [{ code: '0021', name: 'TADDY LAGER', qtyMilli: 10000, pence: 4200 }] }),
    night('2026-08-21', 100, { items: [{ code: '0021', name: 'TADDY LAGER', qtyMilli: 4500, pence: 1890 }] }),
  ]
  const pack = buildAskPack(base(days))
  assert.ok(pack.text.includes('0021|TADDY LAGER|14.5|£60.90|2'))
})

test('department totals are included', () => {
  const days = [
    night('2026-08-21', 100, { departments: [{ code: 'D01', label: 'Draught beers', pence: 149225, qtyMilli: 406000 }] }),
  ]
  const pack = buildAskPack(base(days))
  assert.ok(pack.text.includes('Draught beers|406|£1,492.25'))
})

// --- the board, the cellar, the staff ---------------------------------------

test('the price board is listed', () => {
  const pack = buildAskPack(base([night('2026-08-21', 100)], { book: [{ name: 'Taddy Lager', pence: 420 }] }))
  assert.ok(pack.text.includes('PRICE BOARD'))
  assert.ok(pack.text.includes('Taddy Lager|£4.20'))
})

test('the cellar lists what is on hand in servings, valued at cost', () => {
  const taddy: StockItem = {
    id: 't', name: 'Taddy Lager', kind: 'liquid', servingBaseUnits: 568, servingName: 'pint',
    container: { name: 'firkin', baseUnits: 72 * 568 },
    cost: { pence: 9500, baseUnits: 72 * 568 },
  }
  const cellar = cellarHealth({
    items: [taddy],
    pours: [],
    counts: [{ date: '2026-08-20', lines: [{ stockItemId: 't', baseUnits: 72 * 568 }] }],
    deliveries: [],
    days: [],
    today: '2026-08-26',
    costOfServing: costOf,
  })
  const pack = buildAskPack(base([night('2026-08-21', 100)], { cellar }))
  assert.ok(pack.text.includes('CELLAR'))
  assert.ok(pack.text.includes('Taddy Lager|72 pints|£95.00'))
  assert.ok(pack.text.includes('Total value at cost, where a cost is set: £95.00'))
})

test('staff hours and rates are included, and an unset rate says so', () => {
  const kelly: Person = { id: 'k', name: 'Kelly', slot: 1, defaultStartMin: 1080, defaultEndMin: 1410, ratePencePerHour: 1221 }
  const dave: Person = { id: 'd', name: 'Dave', slot: 2, defaultStartMin: 1080, defaultEndMin: 1410 }
  const pack = buildAskPack(
    base([night('2026-08-21', 100)], {
      people: [kelly, dave],
      shifts: [shiftFor(kelly, '2026-08-21'), shiftFor(kelly, '2026-08-22'), shiftFor(dave, '2026-08-21')],
    }),
  )
  assert.ok(pack.text.includes('Kelly|£12.21/h|2|11h|2026-08-22'))
  assert.ok(pack.text.includes('Dave|not set|1|5h 30m|2026-08-21'))
})

// --- what must never be in it ------------------------------------------------

test('nothing shaped like an API key can be in the pack', () => {
  // The builder takes no settings at all, so this is belt and braces — but the
  // belt is worth testing: a pack goes over the network on every question.
  const pack = buildAskPack(
    base([night('2026-08-21', 100)], { notes: new Map([['2026-08-21', 'normal note']]) }),
  )
  assert.ok(!pack.text.includes('sk-ant'))
  assert.ok(!/api.?key/i.test(pack.text))
})

test('the pack states its own limits so the model can respect them', () => {
  const pack = buildAskPack(base([night('2026-08-21', 100)]))
  assert.ok(pack.text.includes('END OF PACK. Nothing outside this pack is known about this pub.'))
})
