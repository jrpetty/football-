// ---------------------------------------------------------------------------
// A night, written out for somebody else.
//
// The accountant does not want the app, a login, or a CSV of every PLU. They
// want the four figures and what explains them, in something they can paste
// into an email. So this produces plain text — no format to open, no encoding
// to get wrong, readable in the message itself — and says the same things the
// screen says, in the same words.
//
// Deliberately not a spreadsheet. The month-end export already exists in
// Settings for that; this is for "here is Saturday, the drawer was twelve pounds
// light, here is why we think so".
// ---------------------------------------------------------------------------

import { formatLong } from './date.ts'
import { formatMoney, formatSigned } from './money.ts'
import type { DayReconciliation } from './reconcile.ts'
import { formatHours, formatTime, shiftMinutes, type Person, type Shift } from './rota.ts'
import type { DayRecord } from './types.ts'
import { departmentLabel } from './departments.ts'

function line(label: string, value: string): string {
  // Padded so the figures form a column in a monospaced mail client and still
  // read fine in one that is not.
  return `${label.padEnd(18, ' ')}${value}`
}

export interface SummaryInput {
  day: DayRecord
  reconciliation: DayReconciliation
  people: readonly Person[]
  shifts: readonly Shift[]
  pubName?: string
}

/** One night as plain text, ready to send. */
export function nightSummary({ day, reconciliation, people, shifts, pubName }: SummaryInput): string {
  const r = reconciliation.overall
  const z = day.zRead
  const out: string[] = []

  out.push(pubName ? `${pubName} — ${formatLong(day.date)}` : formatLong(day.date))
  if (z?.header.zNumber !== undefined) out.push(`Z read ${z.header.zNumber}`)
  out.push('')

  out.push(line('Till roll', day.till.pence === null ? '—' : formatMoney(day.till.pence)))
  out.push(line('Card machine', day.card.pence === null ? '—' : formatMoney(day.card.pence)))
  out.push(line('Cash counted', day.cashPence === null ? '—' : formatMoney(day.cashPence)))
  if (day.card.pence !== null && day.cashPence !== null) {
    out.push(line('Card + cash', formatMoney(day.card.pence + day.cashPence)))
  }
  out.push(line('Variance', r.complete ? formatSigned(r.variancePence) : '—'))
  out.push('')

  out.push(
    r.verdict === 'balanced'
      ? r.variancePence === 0
        ? 'BALANCED — the money and the till agree exactly.'
        : `BALANCED — out by ${formatMoney(Math.abs(r.variancePence))}, within tolerance.`
      : r.verdict === 'short'
        ? `SHORT by ${formatMoney(Math.abs(r.variancePence))} — less money than the till says was taken.`
        : r.verdict === 'over'
          ? `OVER by ${formatMoney(Math.abs(r.variancePence))} — more money than the till says was taken.`
          : 'NOT FINISHED — some figures are still missing.',
  )

  // Which leg the difference is on, when the roll said what to expect. This is
  // the part that saves the accountant asking.
  if (reconciliation.itemised) {
    out.push('')
    for (const [name, leg] of [['Drawer', reconciliation.cash], ['Card machine', reconciliation.card]] as const) {
      if (!leg) continue
      out.push(
        line(
          name,
          `till says ${formatMoney(leg.expectedPence)}, counted ${formatMoney(leg.countedPence)} (${formatSigned(leg.variancePence)})`,
        ),
      )
    }
  }

  if (z && z.departments.length > 0) {
    out.push('')
    out.push('What sold')
    const total = z.deptTotal?.pence ?? z.departments.reduce((a, d) => a + d.pence, 0)
    for (const d of z.departments) {
      const share = total > 0 ? ` (${((d.pence / total) * 100).toFixed(1)}%)` : ''
      out.push(line(`  ${departmentLabel(d.code, d.name)}`, `${formatMoney(d.pence)}${share}`))
    }
    if (z.deptTotal) out.push(line('  Total', formatMoney(z.deptTotal.pence)))
  }

  const t = z?.transaction
  if (t) {
    out.push('')
    if (t.guestCount !== undefined) out.push(line('Sales', String(t.guestCount)))
    if (t.avePence !== undefined) out.push(line('Average sale', formatMoney(t.avePence)))
    if (t.voidCount) out.push(line('Voids', `${t.voidCount}${t.voidPence !== undefined ? ` (${formatMoney(t.voidPence)})` : ''}`))
    if (t.noSaleCount) out.push(line('No sales', String(t.noSaleCount)))
  }

  const onTonight = shifts.filter((s) => s.date === day.date)
  if (onTonight.length > 0) {
    out.push('')
    out.push('Who was on')
    for (const s of onTonight) {
      const person = people.find((p) => p.id === s.personId)
      out.push(
        line(`  ${person?.name ?? 'Someone'}`, `${formatTime(s.startMin)}–${formatTime(s.endMin)} (${formatHours(shiftMinutes(s))})`),
      )
    }
  }

  if (day.note.trim()) {
    out.push('')
    out.push('Note')
    out.push(`  ${day.note.trim()}`)
  }

  out.push('')
  out.push(`Counted with Tally. Figures from the till roll${day.till.source === 'manual' ? ', typed in' : ', read from the photograph'}.`)

  return out.join('\n')
}

/** A filename an accountant will not have to rename. */
export function summaryFilename(date: string): string {
  return `takings-${date}.txt`
}
