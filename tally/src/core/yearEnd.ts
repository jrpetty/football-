// ---------------------------------------------------------------------------
// The pack the accountant asks for once a year.
//
// Everything in it already exists somewhere in the app. What has never existed
// is the assembling — and the assembling is the whole job, because the version
// that gets done at eleven o'clock the night before a deadline is the one that
// contains a mistake.
//
// One caution runs through this file and is repeated in the output itself: what
// comes out is a set of working figures drawn from the till, not a return.
// Deliberately no tax arithmetic anywhere in it — gross takings, costs as
// invoiced, hours as rostered. The tax treatment of those is the accountant's
// job, and this pack exists to hand them clean inputs, not conclusions.
// ---------------------------------------------------------------------------

import type { DayStats } from './analytics.ts'
import { formatMoney } from './money.ts'
import { fromDateKey } from './date.ts'
import type { CellarValue } from './margin.ts'
import { formatHours, shiftMinutes, type Person, type Shift } from './rota.ts'

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
]

export interface MonthRow {
  /** `YYYY-MM`. */
  key: string
  label: string
  nights: number
  takingsPence: number
  cashPence: number
  cardPence: number
}

/** Takings by month, which is the spine of the whole pack. */
export function monthlyTakings(days: readonly DayStats[]): MonthRow[] {
  const byMonth = new Map<string, MonthRow>()

  for (const day of days) {
    if (day.takingsPence === null) continue
    const key = day.date.slice(0, 7)
    const date = fromDateKey(day.date)
    const label = `${MONTHS[date.getMonth()]} ${date.getFullYear()}`
    const row =
      byMonth.get(key) ?? { key, label, nights: 0, takingsPence: 0, cashPence: 0, cardPence: 0 }

    row.nights++
    row.takingsPence += day.takingsPence
    row.cashPence += day.cashPence ?? 0
    row.cardPence += day.cardPence ?? 0
    byMonth.set(key, row)
  }

  return [...byMonth.values()].sort((a, b) => a.key.localeCompare(b.key))
}

export interface WageRow {
  name: string
  shifts: number
  minutes: number
  /** Null where no rate was ever set — an unknown cost, not a free one. */
  costPence: number | null
}

export function wageBill(shifts: readonly Shift[], people: readonly Person[], from: string, to: string): WageRow[] {
  const byId = new Map(people.map((p) => [p.id, p]))
  const rows = new Map<string, { shifts: number; minutes: number; ratePencePerHour?: number }>()

  for (const shift of shifts) {
    if (shift.date < from || shift.date > to) continue
    const person = byId.get(shift.personId)
    const name = person?.name ?? 'Someone no longer on the books'
    const row = rows.get(name) ?? {
      shifts: 0,
      minutes: 0,
      ...(person?.ratePencePerHour ? { ratePencePerHour: person.ratePencePerHour } : {}),
    }
    row.shifts++
    row.minutes += shiftMinutes(shift)
    rows.set(name, row)
  }

  return [...rows.entries()]
    .map(([name, row]) => ({
      name,
      shifts: row.shifts,
      minutes: row.minutes,
      // Costed once off the total, not shift by shift. Rounding each shift
      // separately drifts by a penny a time, and the same person's figure here
      // and on their profile would then disagree — which is precisely the sort
      // of difference an accountant stops to ask about.
      costPence: row.ratePencePerHour ? Math.round((row.minutes * row.ratePencePerHour) / 60) : null,
    }))
    .sort((a, b) => b.minutes - a.minutes)
}

export interface YearEndInput {
  from: string
  to: string
  days: readonly DayStats[]
  shifts: readonly Shift[]
  people: readonly Person[]
  cellar: CellarValue | null
  /** What was bought in over the year, at the costs entered, when known. */
  purchasesPence?: number | null
  pubName?: string
}

function pad(label: string, value: string, width = 26): string {
  return `${label.padEnd(width, ' ')}${value}`
}

/** The whole pack, as plain text an accountant can be sent. */
export function yearEndPack(input: YearEndInput): string {
  const { from, to, days } = input
  const inRange = days.filter((d) => d.date >= from && d.date <= to)
  const months = monthlyTakings(inRange)
  const wages = wageBill(input.shifts, input.people, from, to)

  const takings = months.reduce((a, m) => a + m.takingsPence, 0)
  const cash = months.reduce((a, m) => a + m.cashPence, 0)
  const card = months.reduce((a, m) => a + m.cardPence, 0)
  const nights = months.reduce((a, m) => a + m.nights, 0)

  const out: string[] = []
  out.push(input.pubName ? `${input.pubName} — takings ${from} to ${to}` : `Takings ${from} to ${to}`)
  out.push('')
  out.push('WORKING FIGURES, NOT A RETURN. Everything below is drawn from the')
  out.push('till roll and the app’s own records: takings as rung, costs as')
  out.push('invoiced, hours as rostered. The tax side is yours.')
  out.push('')

  out.push('SUMMARY')
  out.push(pad('  Nights traded', String(nights)))
  out.push(pad('  Takings', formatMoney(takings)))
  out.push(pad('  Of which cash', formatMoney(cash)))
  out.push(pad('  Of which card', formatMoney(card)))
  out.push('')

  out.push('BY MONTH')
  out.push(pad('  Month', 'Nights     Takings        Cash        Card', 12))
  for (const m of months) {
    out.push(
      `  ${m.label.padEnd(16, ' ')}${String(m.nights).padStart(4, ' ')}  ${formatMoney(m.takingsPence).padStart(11, ' ')} ${formatMoney(m.cashPence).padStart(11, ' ')} ${formatMoney(m.cardPence).padStart(11, ' ')}`,
    )
  }
  out.push(
    `  ${'Total'.padEnd(16, ' ')}${String(nights).padStart(4, ' ')}  ${formatMoney(takings).padStart(11, ' ')} ${formatMoney(cash).padStart(11, ' ')} ${formatMoney(card).padStart(11, ' ')}`,
  )
  out.push('')

  if (input.cellar) {
    out.push('STOCK AT COST')
    out.push(pad('  Value on hand', formatMoney(input.cellar.totalPence)))
    if (input.cellar.unvaluedCount > 0) {
      out.push(
        `  ${input.cellar.unvaluedCount} ${input.cellar.unvaluedCount === 1 ? 'line has' : 'lines have'} stock but no cost entered, so the real figure is higher.`,
      )
    }
    for (const line of input.cellar.lines.filter((l) => (l.pence ?? 0) > 0).slice(0, 12)) {
      out.push(pad(`  ${line.item.name}`, formatMoney(line.pence as number)))
    }
    out.push('')
  }

  if (wages.length > 0) {
    const totalMinutes = wages.reduce((a, w) => a + w.minutes, 0)
    const totalCost = wages.reduce((a, w) => a + (w.costPence ?? 0), 0)
    const anyUnpriced = wages.some((w) => w.costPence === null)
    out.push('WAGES')
    out.push(pad('  Hours rostered', formatHours(totalMinutes)))
    out.push(pad('  At the rates set', formatMoney(totalCost)))
    if (anyUnpriced) out.push('  Some people have no rate set, so the figure above is short of their hours.')
    for (const w of wages) {
      out.push(
        pad(`  ${w.name}`, `${formatHours(w.minutes)} over ${w.shifts} shifts${w.costPence === null ? '' : ` · ${formatMoney(w.costPence)}`}`),
      )
    }
    out.push('')
    out.push('  Rostered hours, not payroll. What was actually paid is the wage')
    out.push('  records, and this is a cross-check against them.')
    out.push('')
  }

  if (input.purchasesPence !== null && input.purchasesPence !== undefined) {
    out.push('STOCK BOUGHT IN')
    out.push(pad('  Over the period', formatMoney(input.purchasesPence)))
    out.push('  At the costs entered against deliveries — a cross-check against the')
    out.push('  purchase invoices, which remain the record.')
    out.push('')
  }
  out.push('Prepared by Tally from the nightly till reads.')

  return out.join('\n')
}

/** The monthly table as a spreadsheet, for the detail behind the summary. */
export function monthlyCsv(months: readonly MonthRow[]): string {
  const rows = [['Month', 'Nights', 'Takings', 'Cash', 'Card']]
  for (const m of months) {
    rows.push([
      m.label,
      String(m.nights),
      (m.takingsPence / 100).toFixed(2),
      (m.cashPence / 100).toFixed(2),
      (m.cardPence / 100).toFixed(2),
    ])
  }
  return rows.map((r) => r.join(',')).join('\n')
}
