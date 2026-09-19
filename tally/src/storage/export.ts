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
  notStock?: string[]
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
 * The API key is the one deliberate omission, because a backup gets emailed and
 * a key in an inbox is a key in the wrong place.
 *
 * The photographs used to be left out too, on the grounds that they were an
 * audit trail rather than data. That stopped being true the night the receipt
 * became the whole record: a backup of the figures without the receipts they
 * were read off is a backup of somebody's word for it. So they can go in — but
 * in their own file, because a year of them is far too big to email and the
 * small file is the one that has to stay easy to send.
 */
export interface Backup {
  app: 'tally'
  version: 3
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
  /** The receipts themselves, when the bigger backup was asked for. */
  photos?: BackupPhoto[]
}

/** One photograph, carried as text because JSON cannot hold anything else. */
export interface BackupPhoto {
  id: string
  savedAt: number
  /** The image's media type, so it comes back as the same kind of file. */
  type: string
  /** Base64, without the `data:` prefix. */
  data: string
}

export function toJson(backup: Omit<Backup, 'app' | 'version' | 'exportedAt'>): string {
  return JSON.stringify(
    { app: 'tally', version: 3, exportedAt: new Date().toISOString(), ...backup },
    null,
    2,
  )
}

/**
 * The backup as pieces of a file rather than one string.
 *
 * A year of receipts runs to tens of megabytes. Built the obvious way that is a
 * single JavaScript string of the whole thing, plus another copy inside the
 * Blob — which is how a backup button becomes a crash on the phone it matters
 * most on. Handing the browser the pieces lets it write them out one at a time.
 */
export function backupParts(
  bundle: Omit<Backup, 'app' | 'version' | 'exportedAt' | 'photos'>,
  photos: readonly BackupPhoto[],
): BlobPart[] {
  const head = toJson(bundle)
  if (photos.length === 0) return [head]

  const close = head.lastIndexOf('}')
  // Only ever untrue for `{}`, which cannot happen — the app, version and date
  // are always written. Checked anyway, because silently producing a broken
  // backup is the one failure that is not noticed until it is needed.
  if (close < 1) throw new Error('That backup could not be assembled.')

  const parts: BlobPart[] = [`${head.slice(0, close).replace(/\s+$/, '')},\n  "photos": [`]
  photos.forEach((photo, i) => parts.push(`${i ? ',' : ''}\n    ${JSON.stringify(photo)}`))
  parts.push('\n  ]\n}')
  return parts
}

/** A photograph as text. Chunked, because spreading a megabyte blows the stack. */
export async function blobToBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}

/** And back again. */
export function base64ToBlob(data: string, type: string): Blob {
  const binary = atob(data)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return new Blob([bytes], { type: type || 'image/jpeg' })
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
  /** Decoded and ready to store under the ids the nights already point at. */
  photos: Array<{ id: string; savedAt: number; blob: Blob }>
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
/**
 * A cellar restored onto a cellar, merged rather than swapped.
 *
 * Everything else in a restore adds to what is already there — that is the
 * promise on the button — and the cellar has to behave the same way. A file
 * carrying six lines must not take the other twenty away, and above all must
 * not take the pours away: those are what tell the till what to subtract, and
 * losing them is silent. So a line the file names replaces what the file says
 * about that line and leaves the rest of it alone, which means a file with no
 * costs in it cannot wipe the costs.
 *
 * The house measure is only taken from the file when there is no cellar yet:
 * restoring a whole backup onto an empty app should bring it, and merging a
 * few lines into a working cellar should not quietly re-pour every spirit.
 */
export function mergeStockConfig(current: StockConfig, incoming: StockConfig): StockConfig {
  const items = new Map(current.items.map((i) => [i.id, i]))
  for (const item of incoming.items) {
    const existing = items.get(item.id)
    items.set(item.id, existing ? { ...existing, ...item } : item)
  }
  const pours = new Map(current.pours.map((p) => [p.itemCode, p]))
  for (const pour of incoming.pours) pours.set(pour.itemCode, pour)
  // Added to rather than swapped, like everything else here: a file that says
  // the coffee is not stock must not un-say it about the room hire.
  const notStock = [...new Set([...(current.notStock ?? []), ...(incoming.notStock ?? [])])]

  return {
    items: [...items.values()].sort((a, b) => a.name.localeCompare(b.name)),
    pours: [...pours.values()],
    mlPerShot: current.items.length === 0 ? incoming.mlPerShot : current.mlPerShot,
    ...(notStock.length > 0 ? { notStock } : {}),
  }
}

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
          ...(Array.isArray(stockRaw.notStock)
            ? { notStock: stockRaw.notStock.filter((c): c is string => typeof c === 'string') }
            : {}),
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
    // Under their original ids, so the nights that reference them still find
    // them. One unreadable photograph costs that photograph, not the restore.
    photos: arrayOf<BackupPhoto>(
      bundle.photos,
      (row) =>
        !!row &&
        typeof row === 'object' &&
        typeof (row as BackupPhoto).id === 'string' &&
        typeof (row as BackupPhoto).data === 'string',
    ).flatMap((p) => {
      try {
        return [{ id: p.id, savedAt: typeof p.savedAt === 'number' ? p.savedAt : Date.now(), blob: base64ToBlob(p.data, p.type) }]
      } catch {
        return []
      }
    }),
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
  add(r.photos.length, 'receipt')
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
/**
 * Whether to hand a file to the share sheet rather than download it.
 *
 * An iPhone is the reason this exists. A download link works in Safari, but
 * inside an app added to the home screen — which is how this is meant to be
 * used — it can quietly do nothing at all, and a backup that silently fails is
 * worse than no backup button. The share sheet is the way a file leaves an
 * iPhone: Files, Mail, WhatsApp, AirDrop.
 *
 * Taken apart from the browser so it can be tested: an iPad has called itself
 * a Macintosh since iPadOS 13, and is told apart by having a touch screen.
 */
export function prefersShareSheet(userAgent: string, maxTouchPoints: number): boolean {
  if (/iPhone|iPad|iPod/.test(userAgent)) return true
  return /Macintosh/.test(userAgent) && maxTouchPoints > 1
}

/**
 * One file, sent the way this device sends files.
 *
 * Contents may be a Blob as well as a string: a backup carrying a year of
 * receipts is assembled in pieces and never exists as one string.
 */
export function saveFile(
  filename: string,
  contents: string | Blob,
  mime: string,
): Promise<'shared' | 'downloaded'> {
  return saveFiles([{ filename, contents, mime }])
}

/**
 * Files out of the app: the share sheet where that is how it works, a download
 * everywhere else, and a download anyway if sharing will not have them.
 *
 * Several at once go in one share, so a year-end pack is one tap rather than
 * two sheets in a row.
 */
export async function saveFiles(
  files: ReadonlyArray<{ filename: string; contents: string | Blob; mime: string }>,
): Promise<'shared' | 'downloaded'> {
  const nav = typeof navigator === 'undefined' ? undefined : navigator
  if (nav && prefersShareSheet(nav.userAgent, nav.maxTouchPoints ?? 0) && typeof nav.canShare === 'function') {
    const payload = files.map((f) => new File([f.contents], f.filename, { type: f.mime }))
    if (nav.canShare({ files: payload })) {
      try {
        await nav.share({ files: payload, title: files[0]?.filename })
        return 'shared'
      } catch (err) {
        // Thinking better of it is not a failure to route around: offering the
        // file again as a download would be the app arguing with her.
        if (err instanceof DOMException && err.name === 'AbortError') return 'shared'
      }
    }
  }
  for (const f of files) downloadFile(f.filename, f.contents, f.mime)
  return 'downloaded'
}

export function downloadFile(filename: string, contents: string | Blob, mime: string): void {
  // A Blob already knows its own type; only text needs the encoding stated.
  const blob =
    typeof contents === 'string' ? new Blob([contents], { type: `${mime};charset=utf-8` }) : contents
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
