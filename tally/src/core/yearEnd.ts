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
// The VAT lines in particular are an estimate from takings and purchases, which
// is a useful thing to hand an accountant and is not a thing to file. Saying so
// once in a comment would not be enough; it is printed on the document.
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
  /** Net of VAT at the rate given, which is what an accountant works in. */
  netPence: number
  vatPence: number
}

/** Takings by month, which is the spine of the whole pack. */
export function monthlyTakings(days: readonly DayStats[], vatBp: number): MonthRow[] {
  const byMonth = new Map<string, MonthRow>()

  for (const day of days) {
    if (day.takingsPence === null) continue
    const key = day.date.slice(0, 7)
    const date = fromDateKey(day.date)
    const label = `${MONTHS[date.getMonth()]} ${date.getFullYear()}`
    const row =
      byMonth.get(key) ??
      { key, label, nights: 0, takingsPence: 0, cashPence: 0, cardPence: 0, netPence: 0, vatPence: 0 }

    row.nights++
    row.takingsPence += day.takingsPence
    row.cashPence += day.cashPence ?? 0
    row.cardPence += day.cardPence ?? 0
    byMonth.set(key, row)
  }

  return [...byMonth.values()]
    .map((row) => {
      // The VAT inside a gross figure, not added on top of it.
      const netPence = Math.round((row.takingsPence * 10000) / (10000 + vatBp))
      return { ...row, netPence, vatPence: row.takingsPence - netPence }
    })
    .sort((a, b) => a.key.localeCompare(b.key))
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
  vatBp: number
  /** What was bought in over the year, at cost, when it is known. */
  purchasesPence?: number | null
  pubName?: string
}

function pad(label: string, value: string, width = 26): string {
  return `${label.padEnd(width, ' ')}${value}`
}

/** The whole pack, as plain text an accountant can be sent. */
export function yearEndPack(input: YearEndInput): string {
  const { from, to, days, vatBp } = input
  const inRange = days.filter((d) => d.date >= from && d.date <= to)
  const months = monthlyTakings(inRange, vatBp)
  const wages = wageBill(input.shifts, input.people, from, to)

  const takings = months.reduce((a, m) => a + m.takingsPence, 0)
  const net = months.reduce((a, m) => a + m.netPence, 0)
  const outputVat = months.reduce((a, m) => a + m.vatPence, 0)
  const cash = months.reduce((a, m) => a + m.cashPence, 0)
  const card = months.reduce((a, m) => a + m.cardPence, 0)
  const nights = months.reduce((a, m) => a + m.nights, 0)

  const out: string[] = []
  out.push(input.pubName ? `${input.pubName} — takings ${from} to ${to}` : `Takings ${from} to ${to}`)
  out.push('')
  out.push('WORKING FIGURES, NOT A RETURN. Everything below is drawn from the')
  out.push('till roll and the app’s own records. The VAT lines are an estimate to')
  out.push('work from, not a computed liability.')
  out.push('')

  out.push('SUMMARY')
  out.push(pad('  Nights traded', String(nights)))
  out.push(pad('  Takings (gross)', formatMoney(takings)))
  out.push(pad(`  Net of VAT at ${(vatBp / 100).toFixed(1)}%`, formatMoney(net)))
  out.push(pad('  VAT on takings', formatMoney(outputVat)))
  out.push(pad('  Of which cash', formatMoney(cash)))
  out.push(pad('  Of which card', formatMoney(card)))
  out.push('')

  out.push('BY MONTH')
  out.push(pad('  Month', 'Nights      Gross         Net         VAT', 12))
  for (const m of months) {
    out.push(
      `  ${m.label.padEnd(16, ' ')}${String(m.nights).padStart(4, ' ')}  ${formatMoney(m.takingsPence).padStart(11, ' ')} ${formatMoney(m.netPence).padStart(11, ' ')} ${formatMoney(m.vatPence).padStart(11, ' ')}`,
    )
  }
  out.push(
    `  ${'Total'.padEnd(16, ' ')}${String(nights).padStart(4, ' ')}  ${formatMoney(takings).padStart(11, ' ')} ${formatMoney(net).padStart(11, ' ')} ${formatMoney(outputVat).padStart(11, ' ')}`,
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

  out.push('VAT, ROUGHLY')
  out.push(pad('  On takings (output)', formatMoney(outputVat)))
  if (input.purchasesPence !== null && input.purchasesPence !== undefined) {
    const inputVat = Math.round((input.purchasesPence * vatBp) / 10000)
    out.push(pad('  On stock bought (input)', formatMoney(inputVat)))
    out.push(pad('  Difference', formatMoney(outputVat - inputVat)))
    out.push('')
    out.push('  Input VAT is estimated from stock booked in at the costs entered,')
    out.push('  and takes no account of anything else the pub buys. The real')
    out.push('  figure comes off the purchase invoices.')
  } else {
    out.push('  No purchase figures entered, so only the output side is shown.')
  }
  out.push('')
  out.push('Prepared by Tally from the nightly till reads.')

  return out.join('\n')
}

/** The monthly table as a spreadsheet, for the detail behind the summary. */
export function monthlyCsv(months: readonly MonthRow[]): string {
  const rows = [['Month', 'Nights', 'Gross', 'Net', 'VAT', 'Cash', 'Card']]
  for (const m of months) {
    rows.push([
      m.label,
      String(m.nights),
      (m.takingsPence / 100).toFixed(2),
      (m.netPence / 100).toFixed(2),
      (m.vatPence / 100).toFixed(2),
      (m.cashPence / 100).toFixed(2),
      (m.cardPence / 100).toFixed(2),
    ])
  }
  return rows.map((r) => r.join(',')).join('\n')
}
