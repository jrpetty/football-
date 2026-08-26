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
import type { PriceBookEntry } from '../core/priceBook.ts'
import type { Delivery, Pour, StockCount, StockItem } from '../core/stock.ts'
import type { Person, Shift } from '../core/rota.ts'
import type { DayWeather } from '../core/forecast.ts'

/** Mirrors the stored shape; imported as a type would be a cycle through db. */
interface StockConfig {
  items: StockItem[]
  pours: Pour[]
  mlPerShot: number
}
import { reconcileDay, verdictHeadline } from '../core/reconcile.ts'
import { DEPARTMENTS, departmentLabel } from '../core/departments.ts'
import { formatQty } from '../core/zread.ts'

const CORE_HEADERS = [
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

/**
 * The till's own figures, then one column per department.
 *
 * Fixed columns rather than only the departments seen, so a fortnight where
 * nobody bought a bottled beer still lines up with one where they did — a
 * spreadsheet with a shifting column order is worse than no spreadsheet.
 */
const TILL_HEADERS = [
  'Z number',
  'Sales',
  'Items sold',
  'Average spend',
  'Till cash',
  'Till card',
  'Cash in drawer',
  'Voids',
  'Void value',
  'No sales',
] as const

/** Money per department, then items per department — Q on a department line. */
const DEPT_HEADERS = DEPARTMENTS.map((d) => departmentLabel(d.code, d.printed))
const DEPT_QTY_HEADERS = DEPARTMENTS.map((d) => `${departmentLabel(d.code, d.printed)} (sold)`)

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
  const lines = [
    [...CORE_HEADERS, ...TILL_HEADERS, ...DEPT_HEADERS, ...DEPT_QTY_HEADERS].map(csvField).join(','),
  ]
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
        // The till's own account of the night, where the roll was captured.
        day.zRead?.header.zNumber === undefined ? '' : String(day.zRead.header.zNumber),
        day.zRead?.transaction.guestCount === undefined ? '' : String(day.zRead.transaction.guestCount),
        day.zRead?.deptTotal?.qtyMilli === undefined ? '' : formatQty(day.zRead.deptTotal.qtyMilli),
        pounds(day.zRead?.transaction.avePence ?? null),
        pounds(day.zRead?.transaction.cashPence ?? null),
        pounds(day.zRead?.transaction.cardPence ?? null),
        pounds(day.zRead?.transaction.cidPence ?? null),
        day.zRead?.transaction.voidCount === undefined ? '' : String(day.zRead.transaction.voidCount),
        pounds(day.zRead?.transaction.voidPence ?? null),
        day.zRead?.transaction.noSaleCount === undefined ? '' : String(day.zRead.transaction.noSaleCount),
        ...DEPARTMENTS.map((meta) =>
          pounds(day.zRead?.departments.find((d) => d.code === meta.code)?.pence ?? null),
        ),
        ...DEPARTMENTS.map((meta) => {
          const qty = day.zRead?.departments.find((d) => d.code === meta.code)?.qtyMilli
          return qty === undefined ? '' : formatQty(qty)
        }),
      ]
        .map(csvField)
        .join(','),
    )
  }
  return lines.join('\r\n')
}

// --- the whole app in one file -------------------------------------------------

/**
 * Everything worth keeping.
 *
 * The first version of this saved only the nights, which was quietly the worst
 * kind of bug: the backup appeared to work, restored without complaint, and
 * lost the price list, the cellar, every barrel cost, the rota and everyone on
 * it. A backup that is missing most of the work is more dangerous than no
 * backup at all, because it is trusted.
 *
 * Two deliberate omissions, both stated in the interface rather than only here:
 * the photographs, which are an audit trail rather than data and would make the
 * file enormous; and the API key, because a backup gets emailed and a key in an
 * inbox is a key in the wrong place.
 */
export interface Backup {
  app: 'tally'
  version: 2
  exportedAt: string
  days: DayRecord[]
  prices: PriceBookEntry[]
  stock: StockConfig
  deliveries: Delivery[]
  stockCounts: StockCount[]
  people: Person[]
  shifts: Shift[]
  weather: DayWeather[]
  /** Everything but the key. */
  settings: Record<string, unknown>
}

export function toJson(backup: Omit<Backup, 'app' | 'version' | 'exportedAt'>): string {
  return JSON.stringify(
    { app: 'tally', version: 2, exportedAt: new Date().toISOString(), ...backup },
    null,
    2,
  )
}

/** What a restore actually found, so it can say rather than guess. */
export interface Restored {
  days: DayRecord[]
  prices: PriceBookEntry[]
  stock: StockConfig | null
  deliveries: Delivery[]
  stockCounts: StockCount[]
  people: Person[]
  shifts: Shift[]
  weather: DayWeather[]
  settings: Record<string, unknown> | null
  /** True for a backup written before this file saved anything but nights. */
  nightsOnly: boolean
}

function arrayOf<T>(value: unknown, keep: (row: unknown) => boolean): T[] {
  return Array.isArray(value) ? (value.filter(keep) as T[]) : []
}

const hasId = (row: unknown): boolean =>
  !!row && typeof row === 'object' && typeof (row as { id?: unknown }).id === 'string'

const hasDate = (row: unknown): boolean =>
  !!row && typeof row === 'object' && /^\d{4}-\d{2}-\d{2}$/.test(String((row as { date?: unknown }).date))

/**
 * Read a backup back.
 *
 * Every section is optional and validated on its own, so a file written by an
 * older version restores what it has rather than refusing, and one section
 * being malformed costs that section rather than the whole restore. Only a file
 * that is not a Tally backup at all is rejected outright.
 */
export function parseBackup(text: string): Restored {
  const parsed: unknown = JSON.parse(text)
  if (!parsed || typeof parsed !== 'object') throw new Error('That file is not a Tally backup.')
  const bundle = parsed as Record<string, unknown>
  if (bundle.app !== 'tally' && !Array.isArray(bundle.days)) {
    throw new Error('That file is not a Tally backup.')
  }

  const days = arrayOf<DayRecord>(bundle.days, (d) => {
    if (!d || typeof d !== 'object') return false
    const day = d as Partial<DayRecord>
    return typeof day.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day.date) && !!day.till && !!day.card
  })

  const stockRaw = bundle.stock as Partial<StockConfig> | undefined
  const stock: StockConfig | null =
    stockRaw && Array.isArray(stockRaw.items) && Array.isArray(stockRaw.pours)
      ? {
          items: stockRaw.items,
          pours: stockRaw.pours,
          mlPerShot: typeof stockRaw.mlPerShot === 'number' ? stockRaw.mlPerShot : 30,
        }
      : null

  const restored: Restored = {
    days,
    prices: arrayOf<PriceBookEntry>(
      bundle.prices,
      (p) => !!p && typeof p === 'object' && typeof (p as { pence?: unknown }).pence === 'number',
    ),
    stock,
    deliveries: arrayOf<Delivery>(bundle.deliveries, hasId),
    stockCounts: arrayOf<StockCount>(bundle.stockCounts, hasDate),
    people: arrayOf<Person>(bundle.people, hasId),
    shifts: arrayOf<Shift>(bundle.shifts, hasId),
    weather: arrayOf<DayWeather>(bundle.weather, hasDate),
    settings:
      bundle.settings && typeof bundle.settings === 'object'
        ? (bundle.settings as Record<string, unknown>)
        : null,
    nightsOnly: false,
  }

  restored.nightsOnly =
    restored.prices.length === 0 &&
    restored.stock === null &&
    restored.people.length === 0 &&
    restored.shifts.length === 0

  if (days.length === 0 && restored.nightsOnly) throw new Error('That file has nothing in it.')
  return restored
}

/** What came back, in words, so a restore is never a silent success. */
export function describeRestored(r: Restored): string {
  const bits: string[] = []
  const add = (n: number, one: string, many = `${one}s`) => {
    if (n > 0) bits.push(`${n} ${n === 1 ? one : many}`)
  }
  add(r.days.length, 'night')
  add(r.prices.length, 'price')
  add(r.stock?.items.length ?? 0, 'cellar line')
  add(r.people.length, 'person', 'people')
  add(r.shifts.length, 'shift')
  add(r.deliveries.length, 'delivery', 'deliveries')
  add(r.stockCounts.length, 'stock take')
  if (bits.length === 0) return 'Nothing was in that file.'
  return `Restored ${bits.slice(0, -1).join(', ')}${bits.length > 1 ? ' and ' : ''}${bits[bits.length - 1]}.`
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
