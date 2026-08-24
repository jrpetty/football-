// ---------------------------------------------------------------------------
// Getting the data back out.
//
// The brief rules out a sync service, and rightly — but "no cloud" cannot mean
// "one dropped phone and the year is gone". This is the whole backup story: a
// file she can mail to herself, open in a spreadsheet, or hand to the
// accountant. It is also the thing that makes the app safe to try, because
// nothing is trapped inside it.
// ---------------------------------------------------------------------------

import type { DayRecord } from '../core/types.ts'
import { reconcileDay, verdictHeadline } from '../core/reconcile.ts'

const HEADERS = [
  'Date',
  'Weekday',
  'Till roll',
  'Card',
  'Cash counted',
  'Counted total',
  'Variance',
  'Result',
  'Till from',
  'Card from',
  'Note',
] as const

function pounds(pence: number | null): string {
  return pence === null ? '' : (pence / 100).toFixed(2)
}

function provenance(source: string, edited: boolean): string {
  if (source === 'manual') return 'typed'
  const engine = source === 'vision' ? 'Claude' : 'on-device'
  return edited ? `${engine}, corrected` : engine
}

/**
 * Quote a field for CSV.
 *
 * The leading apostrophe on anything starting with =, +, - or @ is deliberate:
 * a spreadsheet treats those as formulas, so a note reading "-5 in the till"
 * would open as a broken calculation, and a maliciously crafted one would open
 * as something worse. Text that came from a person stays text.
 */
function csvField(value: string): string {
  const needsGuard = /^[=+\-@\t\r]/.test(value)
  const text = needsGuard ? `'${value}` : value
  return `"${text.replace(/"/g, '""')}"`
}

export function toCsv(days: readonly DayRecord[], tolerancePence?: number): string {
  const lines = [HEADERS.map(csvField).join(',')]
  for (const day of days) {
    const r = reconcileDay(day, tolerancePence)
    const counted =
      day.card.pence !== null && day.cashPence !== null ? day.card.pence + day.cashPence : null
    lines.push(
      [
        day.date,
        new Date(`${day.date}T00:00:00`).toLocaleDateString('en-GB', { weekday: 'long' }),
        pounds(day.till.pence),
        pounds(day.card.pence),
        pounds(day.cashPence),
        pounds(counted),
        r.complete ? pounds(r.variancePence) : '',
        r.complete ? verdictHeadline(r) : 'Not finished',
        provenance(day.till.source, day.till.edited),
        provenance(day.card.source, day.card.edited),
        day.note,
      ]
        .map(csvField)
        .join(','),
    )
  }
  return lines.join('\r\n')
}

/** A full-fidelity copy, for restoring rather than reading. */
export function toJson(days: readonly DayRecord[]): string {
  return JSON.stringify({ app: 'tally', version: 1, exportedAt: new Date().toISOString(), days }, null, 2)
}

/** Read a backup back, rejecting anything that is not one. */
export function parseBackup(text: string): DayRecord[] {
  const parsed: unknown = JSON.parse(text)
  if (!parsed || typeof parsed !== 'object') throw new Error('That file is not a Tally backup.')
  const days = (parsed as { days?: unknown }).days
  if (!Array.isArray(days)) throw new Error('That file has no days in it.')
  return days.filter((d): d is DayRecord => {
    if (!d || typeof d !== 'object') return false
    const day = d as Partial<DayRecord>
    return typeof day.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day.date) && !!day.till && !!day.card
  })
}

/**
 * Hand the file to the browser.
 *
 * An object URL rather than a data URL: a year of records exceeds what some
 * mobile browsers will accept in a URL, and would fail silently at exactly the
 * point she was trying to make a backup.
 */
export function downloadFile(filename: string, contents: string, mime: string): void {
  const blob = new Blob([contents], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // Revoked on a timer: revoking immediately races the download on Safari.
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}
