// ---------------------------------------------------------------------------
// The week's wages, written out for whoever runs payroll.
//
// Payroll does not want the app. It wants hours per person for a known week,
// in something that can be pasted into a message or opened in a spreadsheet —
// and it wants the gaps stated, because a person with no rate set is missing
// money, not working free. The arithmetic is wageBill's, the same one the
// year-end pack uses, so the weekly figures and the annual ones can never
// disagree about what an hour cost.
// ---------------------------------------------------------------------------

import { formatLong } from './date.ts'
import { formatMoney } from './money.ts'
import { addDays } from './date.ts'
import { formatHours, weekDays, type Person, type Shift } from './rota.ts'
import { wageBill, type WageRow } from './yearEnd.ts'

export interface WeekWages {
  monday: string
  sunday: string
  rows: WageRow[]
  totalMinutes: number
  /** Null when nobody on the week has a rate — unknown, not free. */
  totalPence: number | null
  anyUnpriced: boolean
}

export function weekWages(monday: string, shifts: readonly Shift[], people: readonly Person[]): WeekWages {
  const sunday = addDays(monday, 6)
  const rows = wageBill(shifts, people, monday, sunday)
  const totalMinutes = rows.reduce((a, r) => a + r.minutes, 0)
  const priced = rows.filter((r) => r.costPence !== null)
  return {
    monday,
    sunday,
    rows,
    totalMinutes,
    totalPence: priced.length > 0 ? priced.reduce((a, r) => a + (r.costPence ?? 0), 0) : null,
    anyUnpriced: rows.some((r) => r.costPence === null),
  }
}

function pad(label: string, value: string): string {
  return `${label.padEnd(22, ' ')}${value}`
}

/** The week as plain text, ready to send. */
export function wagesSummary(week: WeekWages): string {
  const out: string[] = []
  out.push(`Wages — week beginning ${formatLong(week.monday)}`)
  out.push(`Monday ${week.monday} to Sunday ${week.sunday}`)
  out.push('')

  if (week.rows.length === 0) {
    out.push('Nobody was rostered this week.')
    return out.join('\n')
  }

  for (const row of week.rows) {
    out.push(
      pad(
        row.name,
        `${formatHours(row.minutes)} over ${row.shifts} shift${row.shifts === 1 ? '' : 's'}${
          row.costPence === null ? ' — no rate set' : ` · ${formatMoney(row.costPence)}`
        }`,
      ),
    )
  }

  out.push('')
  out.push(pad('Hours in total', formatHours(week.totalMinutes)))
  if (week.totalPence !== null) out.push(pad('At the rates set', formatMoney(week.totalPence)))
  if (week.anyUnpriced) {
    out.push('')
    out.push('Someone above has no hourly rate set, so the money total is short')
    out.push('of their hours. Rates live on the Rota, under Who works here.')
  }
  out.push('')
  out.push('Rostered hours from Tally — a cross-check for payroll, not the payroll itself.')
  return out.join('\n')
}

/** The same week as a spreadsheet: one row per person, hours as decimals. */
export function wagesCsv(week: WeekWages): string {
  const rows = [['Name', 'Shifts', 'Hours', 'Rate set', 'Amount']]
  for (const row of week.rows) {
    rows.push([
      row.name,
      String(row.shifts),
      (row.minutes / 60).toFixed(2),
      row.costPence === null ? 'no' : 'yes',
      row.costPence === null ? '' : (row.costPence / 100).toFixed(2),
    ])
  }
  rows.push(['Total', '', (week.totalMinutes / 60).toFixed(2), '', week.totalPence === null ? '' : (week.totalPence / 100).toFixed(2)])
  // Quoted minimally: names are the only free text and none of ours need commas,
  // but a person called "Smith, D" must not split a row.
  return rows.map((r) => r.map((cell) => (cell.includes(',') ? `"${cell}"` : cell)).join(',')).join('\n')
}

export { weekDays }
