import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { buildAskPack, MAX_PACK_NIGHTS, type AskData } from '../src/core/askContext.ts'
import type { DayStats } from '../src/core/analytics.ts'
import { weekdayOf, addDays } from '../src/core/date.ts'
import { shiftAt, type Person } from '../src/core/rota.ts'
import type { StockItem } from '../src/core/stock.ts'
import { cellarHealth } from '../src/core/stock.ts'
import { costOf } from '../src/core/margin.ts'

/** 18:00 to 23:30 — the hours these tests put people on for. Hours belong to
    the night now, so every shift here says which ones it means. */
const EVENING = { startMin: 1080, endMin: 1410 }
const on = (person: Person, date: string, hours = EVENING) => shiftAt(person.id, date, hours)


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
  // Still exactly twelve fields on the line.
  const line = pack.text.split('\n').find((l) => l.startsWith('2026-08-21|'))
  assert.equal(line?.split('|').length, 12)
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
  // Nothing has been poured of it, so there is no rate and the days-left
  // column is blank rather than a guess.
  assert.ok(pack.text.includes('Taddy Lager|72 pints||£95.00'))
  assert.ok(pack.text.includes('Total value at cost, where a cost is set: £95.00'))
})

test('the cellar says how many nights each line has left, and owns up when it cannot know', () => {
  const taddy: StockItem = {
    id: 't', name: 'Taddy Lager', kind: 'liquid', servingBaseUnits: 568, servingName: 'pint',
    container: { name: 'firkin', baseUnits: 72 * 568 },
    cost: { pence: 9500, baseUnits: 72 * 568 },
  }
  // Opens at 300 pints; two nights read between them, 200 pints out — so a
  // hundred a night, a hundred left, one more night in it.
  const cellar = cellarHealth({
    items: [taddy],
    pours: [{ itemCode: '1', itemName: 'PINT TADDY LAGER', stockItemId: 't', baseUnits: 568 }],
    counts: [{ date: '2026-08-10', lines: [{ stockItemId: 't', baseUnits: 300 * 568 }] }],
    deliveries: [],
    days: [
      { date: '2026-08-15', items: [{ code: '1', name: 'PINT TADDY LAGER', qtyMilli: 200_000 }] },
      // A line the cellar knows nothing about: nothing comes off for it.
      { date: '2026-08-16', items: [{ code: '9', name: 'PINT GUINNESS', qtyMilli: 40_000 }] },
    ],
    today: '2026-08-30',
    costOfServing: costOf,
  })
  const pack = buildAskPack(base([night('2026-08-21', 100)], { cellar }))
  assert.ok(pack.text.includes('Taddy Lager|100 pints|1|'), pack.text.slice(pack.text.indexOf('CELLAR'), pack.text.indexOf('CELLAR') + 300))
  assert.match(pack.text, /over the 2 nights of this window whose receipt has been read/)
  assert.match(pack.text, /WARNING: the till sold 1 line with no cellar pour set \(PINT GUINNESS\)/)
})

test('staff hours and rates are included, and an unset rate says so', () => {
  const kelly: Person = { id: 'k', name: 'Kelly', slot: 1, ratePencePerHour: 1221 }
  const dave: Person = { id: 'd', name: 'Dave', slot: 2 }
  const pack = buildAskPack(
    base([night('2026-08-21', 100)], {
      people: [kelly, dave],
      shifts: [on(kelly, '2026-08-21'), on(kelly, '2026-08-22'), on(dave, '2026-08-21')],
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

test('a night with a cellar count carries its gap at cost; one without is blank', () => {
  const pack = buildAskPack(
    base([night('2026-08-21', 100), night('2026-08-22', 100)], {
      nightGaps: new Map([['2026-08-21', -1250], ['2026-08-22', 0]]),
    }),
  )
  assert.ok(pack.text.includes('2026-08-21|Fri|') && pack.text.split('\n').some((l) => l.startsWith('2026-08-21|') && l.endsWith('|−£12.50')))
  assert.ok(pack.text.split('\n').some((l) => l.startsWith('2026-08-22|') && l.endsWith('|£0')))
  const blank = buildAskPack(base([night('2026-08-23', 100)]))
  assert.ok(blank.text.split('\n').some((l) => l.startsWith('2026-08-23|') && l.endsWith('|')))
})


// --- week by week, and what is moving ---------------------------------------------

test('the pack carries each category week by week, and marks a part week as part', () => {
  const week = (monday: string, nights: number, pounds: number) =>
    Array.from({ length: nights }, (_, i) => {
      const d = new Date(`${monday}T00:00:00`)
      d.setDate(d.getDate() + i)
      const date = d.toISOString().slice(0, 10)
      const n = night(date, pounds)
      return {
        ...n,
        departments: [{ code: 'D03', label: 'WINE', pence: pounds * 100, qtyMilli: 40_000 }],
        items: [{ code: '1', name: 'GLASS HOUSE WHITE', qtyMilli: 40_000, pence: pounds * 100 }],
      }
    })
  const pack = buildAskPack(
    base([...week('2026-08-31', 5, 200), ...week('2026-09-07', 5, 300), ...week('2026-09-14', 2, 300)], { today: '2026-09-17' }),
  )
  assert.match(pack.text, /WEEK BY WEEK/)
  assert.ok(pack.text.includes('2026-08-31|Wine|£1,000.00'), pack.text.slice(pack.text.indexOf('WEEK BY WEEK'), pack.text.indexOf('WEEK BY WEEK') + 400))
  assert.ok(pack.text.includes('2026-09-14|Wine|£600.00|80|2|part'), 'the week we are in is still filling')
  assert.ok(pack.text.includes('|EVERYTHING|'), 'the pub as a whole, to read the categories against')
})

test('the pack says what is rising and falling, never inventing a baseline', () => {
  const run = (from: string, nights: number, lines: Array<{ code: string; name: string; pounds: number }>) =>
    Array.from({ length: nights }, (_, i) => {
      const d = new Date(`${from}T00:00:00`)
      d.setDate(d.getDate() + i)
      const date = d.toISOString().slice(0, 10)
      const n = night(date, 100)
      return {
        ...n,
        items: lines.map((l) => ({ code: l.code, name: l.name, qtyMilli: 40_000, pence: l.pounds * 100 })),
      }
    })
  const pack = buildAskPack(
    base(
      [
        ...run('2026-07-01', 24, [{ code: '1', name: 'PINT ALPINE', pounds: 200 }]),
        ...run('2026-07-25', 24, [
          { code: '1', name: 'PINT ALPINE', pounds: 100 },
          { code: '2', name: 'PINT GUINNESS', pounds: 150 },
        ]),
      ],
      { today: '2026-08-20' },
    ),
  )
  assert.match(pack.text, /RISING AND FALLING/)
  assert.ok(pack.text.includes('PINT ALPINE|item|-50%|£200.00|£100.00|24/24'))
  assert.ok(!pack.text.includes('PINT GUINNESS|item'), 'new, so there is nothing to compare it against')
})
