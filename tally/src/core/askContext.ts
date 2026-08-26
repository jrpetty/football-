// ---------------------------------------------------------------------------
// The data pack behind "Ask the till".
//
// A question box is only as honest as what it can see, so this assembles the
// app's records into one plain-text pack the model is told to answer from —
// and nothing else. All of it is the pub's own data, sent to the pub's own
// key. What the pack deliberately never contains: the API key, or anything
// else from Settings that is about the phone rather than the trade.
//
// Two disciplines throughout. Everything is bounded — a pack that grows a
// night at a time forever would quietly turn a penny question into a pound
// one — and every bound is stated inside the pack itself, so the model knows
// what it cannot see and can say so instead of guessing.
// ---------------------------------------------------------------------------

import type { DayStats } from './analytics.ts'
import type { PriceBookEntry } from './priceBook.ts'
import type { CellarHealth, StockItem } from './stock.ts'
import type { DayWeather } from './forecast.ts'
import { formatHours, shiftMinutes, type Person, type Shift } from './rota.ts'
import { formatMoney, formatSigned } from './money.ts'
import { costOf } from './margin.ts'

/** A year and a bit of nights — enough for any like-for-like question. */
export const MAX_PACK_NIGHTS = 400
export const MAX_PACK_ITEMS = 120
export const MAX_PACK_BOOK = 150

export interface AskData {
  /** Every saved night, any order. */
  days: readonly DayStats[]
  /** The night's note, by date — "band on", "quiz night". */
  notes?: ReadonlyMap<string, string>
  book: readonly PriceBookEntry[]
  cellar: CellarHealth | null
  people: readonly Person[]
  shifts: readonly Shift[]
  weather: readonly DayWeather[]
  today: string
}

export interface AskPack {
  text: string
  nightCount: number
}

/** Free text folded into a one-line field: no pipes, no newlines, not endless. */
function field(text: string, max = 80): string {
  const clean = text.replace(/[|\r\n]+/g, ' ').trim()
  return clean.length > max ? `${clean.slice(0, max - 1)}…` : clean
}

function money(pence: number | null | undefined): string {
  return pence === null || pence === undefined ? '' : formatMoney(pence)
}

function qty(qtyMilli: number): string {
  const n = qtyMilli / 1000
  return Number.isInteger(n) ? String(n) : n.toFixed(1)
}

/** How much stock is on hand, in the servings the bar thinks in. */
function servings(baseUnits: number, item: StockItem): string {
  if (item.servingBaseUnits <= 0) return String(baseUnits)
  const n = baseUnits / item.servingBaseUnits
  const rounded = Math.round(n * 10) / 10
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)} ${item.servingName}s`
}

export function buildAskPack(data: AskData): AskPack {
  const out: string[] = []
  const sorted = [...data.days].sort((a, b) => a.date.localeCompare(b.date))
  const weatherByDate = new Map(data.weather.map((w) => [w.date, w]))

  out.push(`TALLY DATA PACK — a British pub's own till records, built ${data.today}.`)
  out.push('All money is GBP. A trading day runs to 5am, so a Friday night is dated Friday.')
  out.push('Variance is what was counted minus what the till says: negative means the drawer was short.')
  out.push('')

  // --- every night, one line each -------------------------------------------
  const kept = sorted.slice(-MAX_PACK_NIGHTS)
  const omitted = sorted.length - kept.length
  if (kept.length === 0) {
    out.push('NIGHTS: none saved yet.')
  } else {
    out.push(
      `NIGHTS (${kept.length} of ${sorted.length} saved, ${kept[0]?.date} to ${kept[kept.length - 1]?.date}${
        omitted > 0 ? `; the ${omitted} oldest are not in this pack` : ''
      })`,
    )
    out.push('date|weekday|takings|cash|card|variance|verdict|sales|voids|weather|note')
    for (const d of kept) {
      const w = weatherByDate.get(d.date)
      out.push(
        [
          d.date,
          d.weekday.slice(0, 3),
          money(d.takingsPence),
          money(d.cashPence),
          money(d.cardPence),
          d.variancePence === null ? '' : formatSigned(d.variancePence),
          d.verdict,
          d.guestCount ?? '',
          d.voidCount ? `${d.voidCount} (${money(d.voidPence ?? 0)})` : '',
          w ? `${w.tempC}C ${w.rainMm}mm` : '',
          field(data.notes?.get(d.date) ?? ''),
        ].join('|'),
      )
    }
  }
  out.push('')

  // --- what actually sells ---------------------------------------------------
  const itemTotals = new Map<string, { name: string; qtyMilli: number; pence: number; nights: number }>()
  let itemNights = 0
  for (const d of sorted) {
    if (d.items.length === 0) continue
    itemNights++
    for (const it of d.items) {
      const key = it.code || it.name
      const row = itemTotals.get(key) ?? { name: it.name, qtyMilli: 0, pence: 0, nights: 0 }
      row.qtyMilli += it.qtyMilli
      row.pence += it.pence
      row.nights++
      if (it.name.length > row.name.length) row.name = it.name
      itemTotals.set(key, row)
    }
  }
  if (itemTotals.size > 0) {
    const items = [...itemTotals.entries()].sort((a, b) => b[1].pence - a[1].pence)
    const keptItems = items.slice(0, MAX_PACK_ITEMS)
    out.push(
      `ITEMS SOLD (totals across the ${itemNights} nights where the till's item list was captured${
        items.length > keptItems.length ? `; top ${keptItems.length} of ${items.length} by takings` : ''
      })`,
    )
    out.push('code|name|sold|takings|nights sold on')
    for (const [code, row] of keptItems) {
      out.push([code, field(row.name, 40), qty(row.qtyMilli), money(row.pence), row.nights].join('|'))
    }
    out.push('')
  }

  // --- departments -----------------------------------------------------------
  const deptTotals = new Map<string, { label: string; qtyMilli: number; pence: number }>()
  for (const d of sorted) {
    for (const dept of d.departments) {
      const row = deptTotals.get(dept.code) ?? { label: dept.label, qtyMilli: 0, pence: 0 }
      row.qtyMilli += dept.qtyMilli
      row.pence += dept.pence
      deptTotals.set(dept.code, row)
    }
  }
  if (deptTotals.size > 0) {
    out.push('DEPARTMENTS (totals across every night with a captured roll)')
    out.push('name|items sold|takings')
    for (const [, row] of [...deptTotals.entries()].sort((a, b) => b[1].pence - a[1].pence)) {
      out.push([field(row.label, 30), qty(row.qtyMilli), money(row.pence)].join('|'))
    }
    out.push('')
  }

  // --- the price board -------------------------------------------------------
  if (data.book.length > 0) {
    const keptBook = data.book.slice(0, MAX_PACK_BOOK)
    out.push(
      `PRICE BOARD (what each item goes for${data.book.length > keptBook.length ? `; first ${keptBook.length} of ${data.book.length}` : ''})`,
    )
    out.push('name|price' )
    for (const entry of keptBook) {
      out.push([field(entry.name, 40), money(entry.pence)].join('|'))
    }
    out.push('')
  }

  // --- the cellar ------------------------------------------------------------
  if (data.cellar && data.cellar.ledger.length > 0) {
    out.push(`CELLAR (expected on hand now, from the count on ${data.cellar.since} plus deliveries minus what the till sold)`)
    out.push('name|on hand|worth at cost')
    let worth = 0
    let valued = false
    for (const line of data.cellar.ledger) {
      const value = costOf(line.item, Math.max(0, line.expectedBaseUnits))
      if (value !== null) {
        worth += value
        valued = true
      }
      out.push(
        [field(line.item.name, 40), servings(Math.max(0, line.expectedBaseUnits), line.item), value === null ? 'no cost set' : money(value)].join('|'),
      )
    }
    if (valued) out.push(`Total value at cost, where a cost is set: ${money(worth)}`)
    if (data.cellar.gapPence !== null) {
      out.push(
        `Last stocktake window: the count disagreed with the till by ${money(Math.abs(data.cellar.gapPence))} at cost${
          data.cellar.gapPence === 0 ? ' — it reconciled' : data.cellar.gapPence < 0 ? ' (stock missing)' : ' (more than expected)'
        }.`,
      )
    }
    out.push('')
  }

  // --- who works here --------------------------------------------------------
  if (data.people.length > 0) {
    out.push('STAFF (from the rota; hours are rostered, not clocked)')
    out.push('name|hourly rate|nights on the rota|hours|last worked')
    for (const person of data.people.filter((p) => !p.archived)) {
      const mine = data.shifts.filter((s) => s.personId === person.id)
      const minutes = mine.reduce((a, s) => a + shiftMinutes(s), 0)
      const last = mine.reduce<string | null>((a, s) => (a === null || s.date > a ? s.date : a), null)
      out.push(
        [
          field(person.name, 30),
          person.ratePencePerHour ? `${money(person.ratePencePerHour)}/h` : 'not set',
          mine.length,
          formatHours(minutes),
          last ?? 'never',
        ].join('|'),
      )
    }
    out.push('')
  }

  out.push('END OF PACK. Nothing outside this pack is known about this pub.')

  return { text: out.join('\n'), nightCount: kept.length }
}
