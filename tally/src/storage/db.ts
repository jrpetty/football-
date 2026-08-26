// ---------------------------------------------------------------------------
// The database.
//
// IndexedDB, on the phone, with nothing behind it. Pub wifi is unreliable and
// the reconciliation happens whether or not the signal is up, so there is no
// point in the night's work depending on a network round trip. The backup
// story is the export in settings rather than a sync service — see the README
// for why that is the right shape for one pub and the wrong one for fifty.
//
// Written directly against the IndexedDB API rather than pulling in a wrapper:
// it is two object stores and six operations, and the dependency would be
// larger than the code.
// ---------------------------------------------------------------------------

import type { DayRecord } from '../core/types.ts'
import type { PriceBookEntry } from '../core/priceBook.ts'
import type { Delivery, Pour, StockCount, StockItem } from '../core/stock.ts'
import type { Person, Shift } from '../core/rota.ts'
import type { DayWeather } from '../core/forecast.ts'

const DB_NAME = 'tally'
/**
 * v2 added the price book, v3 the cellar, v4 the rota, v5 the weather. Nothing
 * already stored changes shape on any of them.
 */
const DB_VERSION = 5
const DAYS = 'days'
const PHOTOS = 'photos'
const PRICES = 'prices'
const STOCK = 'stock'
const DELIVERIES = 'deliveries'
const COUNTS = 'stockcounts'
const PEOPLE = 'people'
const SHIFTS = 'shifts'
const WEATHER = 'weather'

let dbPromise: Promise<IDBDatabase> | null = null

function open(): Promise<IDBDatabase> {
  const existing = dbPromise
  if (existing) return existing

  // Held in a local as well as the module slot: the failure handler clears the
  // slot so a later call can retry, which means the slot itself is not a safe
  // thing to return.
  const created = new Promise<IDBDatabase>((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      // Keyed by date: one record per trading day, so saving the same night
      // twice corrects it rather than duplicating it.
      // Guarded individually rather than by version number, so upgrading from
      // either version — or from none — creates exactly what is missing.
      if (!db.objectStoreNames.contains(DAYS)) db.createObjectStore(DAYS, { keyPath: 'date' })
      if (!db.objectStoreNames.contains(PHOTOS)) db.createObjectStore(PHOTOS, { keyPath: 'id' })
      if (!db.objectStoreNames.contains(PRICES)) db.createObjectStore(PRICES, { keyPath: 'id' })
      if (!db.objectStoreNames.contains(STOCK)) db.createObjectStore(STOCK, { keyPath: 'id' })
      if (!db.objectStoreNames.contains(DELIVERIES)) db.createObjectStore(DELIVERIES, { keyPath: 'id' })
      // Keyed by date: one stock take a day, and re-counting corrects it.
      if (!db.objectStoreNames.contains(COUNTS)) db.createObjectStore(COUNTS, { keyPath: 'date' })
      if (!db.objectStoreNames.contains(PEOPLE)) db.createObjectStore(PEOPLE, { keyPath: 'id' })
      // Keyed `date:personId`, so putting someone on a day they are already on
      // corrects the hours rather than rostering them twice.
      if (!db.objectStoreNames.contains(SHIFTS)) db.createObjectStore(SHIFTS, { keyPath: 'id' })
      // Keyed by date, so re-fetching a day corrects it rather than storing it
      // twice — and so a forecast is replaced by the actual weather once the
      // day has been and gone.
      if (!db.objectStoreNames.contains(WEATHER)) db.createObjectStore(WEATHER, { keyPath: 'date' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('Could not open the database.'))
    req.onblocked = () => reject(new Error('The database is open in another tab.'))
  }).catch((err: unknown) => {
    dbPromise = null
    throw err
  })

  dbPromise = created
  return created
}

function run<T>(store: string, mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return open().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const tx = db.transaction(store, mode)
        const req = fn(tx.objectStore(store))
        // Resolve on the transaction, not the request: on a write, the request
        // succeeds before the data is durable, and a quota failure surfaces
        // here rather than there.
        tx.oncomplete = () => resolve(req.result)
        tx.onerror = () => reject(tx.error ?? new Error('The database refused that write.'))
        tx.onabort = () => reject(tx.error ?? new Error('That write was aborted — the phone may be out of space.'))
      }),
  )
}

export function saveDay(day: DayRecord): Promise<unknown> {
  return run(DAYS, 'readwrite', (s) => s.put({ ...day, updatedAt: Date.now() }))
}

export function getDay(date: string): Promise<DayRecord | undefined> {
  return run<DayRecord | undefined>(DAYS, 'readonly', (s) => s.get(date))
}

/** Every night, most recent first — the order the history is read in. */
export async function listDays(): Promise<DayRecord[]> {
  const all = await run<DayRecord[]>(DAYS, 'readonly', (s) => s.getAll())
  return all.sort((a, b) => b.date.localeCompare(a.date))
}

export async function deleteDay(date: string): Promise<void> {
  const day = await getDay(date)
  if (day) {
    for (const id of [day.till.photoId, day.card.photoId]) {
      if (id) await deletePhoto(id)
    }
  }
  await run(DAYS, 'readwrite', (s) => s.delete(date))
}

let photoCounter = 0

export async function savePhoto(blob: Blob): Promise<string> {
  // Time plus a counter: unique without needing crypto.randomUUID, which is
  // absent in a non-secure context and would fail exactly where a plain HTTP
  // preview is being used.
  const id = `${Date.now().toString(36)}-${(photoCounter++).toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`
  await run(PHOTOS, 'readwrite', (s) => s.put({ id, blob, savedAt: Date.now() }))
  return id
}

export async function getPhoto(id: string): Promise<Blob | undefined> {
  const row = await run<{ id: string; blob: Blob } | undefined>(PHOTOS, 'readonly', (s) => s.get(id))
  return row?.blob
}

export function deletePhoto(id: string): Promise<unknown> {
  return run(PHOTOS, 'readwrite', (s) => s.delete(id))
}

/**
 * Drop photographs older than a cutoff, leaving the figures untouched.
 *
 * The numbers are tiny and worth keeping forever. The photographs are not, and
 * a phone that fills up is a phone the app stops working on.
 */
export async function prunePhotosBefore(cutoffMs: number): Promise<number> {
  const rows = await run<Array<{ id: string; savedAt: number }>>(PHOTOS, 'readonly', (s) => s.getAll())

  const removedIds = new Set<string>()
  for (const row of rows) {
    if (row.savedAt >= cutoffMs) continue
    await deletePhoto(row.id)
    removedIds.add(row.id)
  }
  if (removedIds.size === 0) return 0

  // Clear the now-dangling references, so the day detail never offers a
  // photograph that is no longer there.
  for (const day of await listDays()) {
    let touched = false
    if (day.till.photoId && removedIds.has(day.till.photoId)) {
      delete day.till.photoId
      touched = true
    }
    if (day.card.photoId && removedIds.has(day.card.photoId)) {
      delete day.card.photoId
      touched = true
    }
    if (touched) await saveDay(day)
  }

  return removedIds.size
}

/** Roughly how much room the app is taking, when the browser will say. */
export async function estimateUsage(): Promise<{ usedBytes: number; quotaBytes: number } | null> {
  if (!navigator.storage?.estimate) return null
  const e = await navigator.storage.estimate()
  return { usedBytes: e.usage ?? 0, quotaBytes: e.quota ?? 0 }
}

/**
 * Ask the browser not to evict the data under storage pressure.
 *
 * Without this, IndexedDB is "best effort" and a phone short on space may clear
 * it. Silently ignored where unsupported.
 */
export async function requestPersistence(): Promise<boolean> {
  try {
    if (!navigator.storage?.persist) return false
    if (await navigator.storage.persisted?.()) return true
    return await navigator.storage.persist()
  } catch {
    return false
  }
}


// --- the price book ----------------------------------------------------------

/**
 * Stored whole rather than an entry per row.
 *
 * A pub's list runs to a hundred lines at most, it is read all at once and
 * written all at once, and one record means no key scheme to get wrong when an
 * item is renamed.
 */
const BOOK_ID = 'book'

export async function loadPriceBook(): Promise<PriceBookEntry[]> {
  const row = await run<{ id: string; entries: PriceBookEntry[] } | undefined>(
    PRICES,
    'readonly',
    (s) => s.get(BOOK_ID),
  ).catch(() => undefined)
  return row?.entries ?? []
}

export function savePriceBook(entries: readonly PriceBookEntry[]): Promise<unknown> {
  return run(PRICES, 'readwrite', (s) => s.put({ id: BOOK_ID, entries, updatedAt: Date.now() }))
}


// --- the cellar --------------------------------------------------------------

const STOCK_ID = 'config'

export interface StockConfig {
  items: StockItem[]
  pours: Pour[]
  /** The house measure, so changing it moves every spirit at once. */
  mlPerShot: number
}

export const EMPTY_STOCK: StockConfig = { items: [], pours: [], mlPerShot: 30 }

export async function loadStockConfig(): Promise<StockConfig> {
  const row = await run<(StockConfig & { id: string }) | undefined>(STOCK, 'readonly', (s) => s.get(STOCK_ID)).catch(
    () => undefined,
  )
  if (!row) return { ...EMPTY_STOCK }
  return { items: row.items ?? [], pours: row.pours ?? [], mlPerShot: row.mlPerShot ?? 30 }
}

export function saveStockConfig(config: StockConfig): Promise<unknown> {
  return run(STOCK, 'readwrite', (s) => s.put({ id: STOCK_ID, ...config, updatedAt: Date.now() }))
}

export async function listDeliveries(): Promise<Delivery[]> {
  const all = await run<Delivery[]>(DELIVERIES, 'readonly', (s) => s.getAll()).catch(() => [])
  return all.sort((a, b) => b.date.localeCompare(a.date))
}

export function saveDelivery(delivery: Delivery): Promise<unknown> {
  return run(DELIVERIES, 'readwrite', (s) => s.put(delivery))
}

export function deleteDelivery(id: string): Promise<unknown> {
  return run(DELIVERIES, 'readwrite', (s) => s.delete(id))
}

export async function listStockCounts(): Promise<StockCount[]> {
  const all = await run<StockCount[]>(COUNTS, 'readonly', (s) => s.getAll()).catch(() => [])
  return all.sort((a, b) => b.date.localeCompare(a.date))
}

export function saveStockCount(count: StockCount): Promise<unknown> {
  return run(COUNTS, 'readwrite', (s) => s.put(count))
}


// --- the rota ----------------------------------------------------------------

/** Everyone, in the order they were added, so the week grid is stable. */
export function listPeople(): Promise<Person[]> {
  return run<Person[]>(PEOPLE, 'readonly', (s) => s.getAll())
}

export function savePerson(person: Person): Promise<unknown> {
  return run(PEOPLE, 'readwrite', (s) => s.put(person))
}

/**
 * People are archived rather than deleted.
 *
 * Their shifts are what makes a past night's crew readable, and a barman who
 * left in March must not quietly empty out every night he worked.
 */
export async function archivePerson(id: string): Promise<void> {
  const person = await run<Person | undefined>(PEOPLE, 'readonly', (s) => s.get(id))
  if (person) await savePerson({ ...person, archived: true })
}

export function listShifts(): Promise<Shift[]> {
  return run<Shift[]>(SHIFTS, 'readonly', (s) => s.getAll())
}

export function saveShift(shift: Shift): Promise<unknown> {
  return run(SHIFTS, 'readwrite', (s) => s.put(shift))
}

export function deleteShift(id: string): Promise<unknown> {
  return run(SHIFTS, 'readwrite', (s) => s.delete(id))
}


// --- the weather -------------------------------------------------------------

export function listWeather(): Promise<DayWeather[]> {
  return run<DayWeather[]>(WEATHER, 'readonly', (s) => s.getAll())
}

export async function saveWeather(days: readonly DayWeather[]): Promise<void> {
  for (const day of days) await run(WEATHER, 'readwrite', (s) => s.put(day))
}
