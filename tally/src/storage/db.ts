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
import { normaliseItems } from '../core/stock.ts'
import { blobToBase64, mergeStockConfig, type BackupPhoto } from './export.ts'
import type { Delivery, Pour, StockCount, StockItem } from '../core/stock.ts'
import type { Person, Shift } from '../core/rota.ts'
import type { DayWeather } from '../core/forecast.ts'

const DB_NAME = 'tally'
/**
 * v2 added the price book, v3 the cellar, v4 the rota, v5 the weather, v6 the
 * weekly digest. Nothing already stored changes shape on any of them.
 */
const DB_VERSION = 6
const DAYS = 'days'
const PHOTOS = 'photos'
const PRICES = 'prices'
const STOCK = 'stock'
const DELIVERIES = 'deliveries'
const COUNTS = 'stockcounts'
const PEOPLE = 'people'
const SHIFTS = 'shifts'
const WEATHER = 'weather'
const DIGEST = 'digest'

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
      // One record. The page writes what it last worked out; the service
      // worker reads it when the browser wakes it up, because a worker cannot
      // run the analytics itself.
      if (!db.objectStoreNames.contains(DIGEST)) db.createObjectStore(DIGEST, { keyPath: 'id' })
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

/** Every photograph a night points at: the roll, the card slip, the old single. */
export function photoIdsOf(day: DayRecord): string[] {
  return [...(day.zPhotoIds ?? []), day.till.photoId, day.card.photoId].filter(
    (id): id is string => !!id,
  )
}

export async function deleteDay(date: string): Promise<void> {
  const day = await getDay(date)
  // Every one of them, the roll included. A night deleted used to leave its
  // roll photographs behind for good — nothing referenced them afterwards, so
  // nothing would ever clear them.
  if (day) for (const id of photoIdsOf(day)) await deletePhoto(id)
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

/** Put a photograph back under the id a restored night already points at. */
export function putPhoto(id: string, blob: Blob, savedAt: number): Promise<unknown> {
  return run(PHOTOS, 'readwrite', (s) => s.put({ id, blob, savedAt }))
}

/**
 * Every receipt in the app, encoded for a backup file.
 *
 * Only the ones a night still points at. Anything else is a leftover from an
 * older version or an interrupted save, and there is no sense carrying it into
 * a file whose whole problem is its size.
 */
export async function collectBackupPhotos(): Promise<BackupPhoto[]> {
  const days = await listDays()
  const wanted = new Set(days.flatMap(photoIdsOf))
  if (wanted.size === 0) return []

  const rows = await run<Array<{ id: string; blob: Blob; savedAt?: number }>>(
    PHOTOS,
    'readonly',
    (s) => s.getAll(),
  )
  const out: BackupPhoto[] = []
  for (const row of rows) {
    if (!wanted.has(row.id) || !row.blob) continue
    out.push({
      id: row.id,
      savedAt: row.savedAt ?? Date.now(),
      type: row.blob.type || 'image/jpeg',
      data: await blobToBase64(row.blob),
    })
  }
  return out
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
  // photograph that is no longer there. The roll's photographs are the ones
  // that matter most now, and they are the ones there are most of.
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
    if (day.zPhotoIds?.some((id) => removedIds.has(id))) {
      const left = day.zPhotoIds.filter((id) => !removedIds.has(id))
      if (left.length) day.zPhotoIds = left
      else delete day.zPhotoIds
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
  /**
   * Till lines that deliberately come off nothing — coffee, room hire, a
   * bag of ice. Held so they stop being reported as stock that walked.
   */
  notStock?: string[]
}

export const EMPTY_STOCK: StockConfig = { items: [], pours: [], mlPerShot: 30 }

export async function loadStockConfig(): Promise<StockConfig> {
  const row = await run<(StockConfig & { id: string }) | undefined>(STOCK, 'readonly', (s) => s.get(STOCK_ID)).catch(
    () => undefined,
  )
  if (!row) return { ...EMPTY_STOCK }
  // Lines saved by an older copy are brought onto the four ways of counting as
  // they are read. Everything is held in base units, so this changes what a
  // line is spoken in and never how much of it there is.
  return { items: normaliseItems(row.items ?? []), pours: row.pours ?? [], mlPerShot: row.mlPerShot ?? 30 }
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

/** A night's count taken back off, when its sheet was cleared on a correction. */
export function deleteStockCount(date: string): Promise<unknown> {
  return run(COUNTS, 'readwrite', (s) => s.delete(date))
}

/** The count taken on one trading day, if there was one — the night's own. */
export function getStockCount(date: string): Promise<StockCount | undefined> {
  return run<StockCount | undefined>(COUNTS, 'readonly', (s) => s.get(date))
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


// --- the weekly digest --------------------------------------------------------

export interface Digest {
  id: 'weekly'
  /** The one line a notification has room for. */
  summary: string
  count: number
  updatedAt: number
}

export async function loadDigest(): Promise<Digest | null> {
  const row = await run<Digest | undefined>(DIGEST, 'readonly', (s) => s.get('weekly'))
  return row ?? null
}

export function saveDigest(summary: string, count: number): Promise<unknown> {
  return run(DIGEST, 'readwrite', (s) => s.put({ id: 'weekly', summary, count, updatedAt: Date.now() }))
}


// --- the whole app, out and back in -------------------------------------------

/**
 * Everything worth keeping, gathered for a backup.
 *
 * Photographs are deliberately left out: they are an audit trail rather than
 * data, and a year of them would make a file too big to email — which would
 * mean no backup at all rather than a bigger one.
 */
export async function collectBackup(): Promise<{
  days: DayRecord[]
  prices: PriceBookEntry[]
  stock: StockConfig
  deliveries: Delivery[]
  stockCounts: StockCount[]
  people: Person[]
  shifts: Shift[]
  weather: DayWeather[]
}> {
  const [days, prices, stock, deliveries, stockCounts, people, shifts, weather] = await Promise.all([
    listDays(),
    loadPriceBook(),
    loadStockConfig(),
    listDeliveries(),
    listStockCounts(),
    listPeople(),
    listShifts(),
    listWeather(),
  ])
  return { days, prices, stock, deliveries, stockCounts, people, shifts, weather }
}

/**
 * Every store there is, so a wipe cannot quietly miss one added later.
 *
 * Listed once here rather than at each call site: the last thing a "clear
 * everything" button should do is leave something behind.
 */
const ALL_STORES = [DAYS, PHOTOS, PRICES, STOCK, DELIVERIES, COUNTS, PEOPLE, SHIFTS, WEATHER, DIGEST]

export interface StoredCounts {
  days: number
  photos: number
  cellarLines: number
  deliveries: number
  stockCounts: number
  people: number
  shifts: number
  prices: number
}

/** What is on this device now, for telling somebody what they are about to lose. */
export async function countEverything(): Promise<StoredCounts> {
  const [days, prices, stock, deliveries, stockCounts, people, shifts, photos] = await Promise.all([
    run<number>(DAYS, 'readonly', (s) => s.count()),
    loadPriceBook(),
    loadStockConfig(),
    run<number>(DELIVERIES, 'readonly', (s) => s.count()),
    run<number>(COUNTS, 'readonly', (s) => s.count()),
    run<number>(PEOPLE, 'readonly', (s) => s.count()),
    run<number>(SHIFTS, 'readonly', (s) => s.count()),
    run<number>(PHOTOS, 'readonly', (s) => s.count()),
  ])
  return {
    days,
    photos,
    cellarLines: stock.items.length,
    deliveries,
    stockCounts,
    people,
    shifts,
    prices: prices.length,
  }
}

/**
 * Empty every store: the nights, the photographs, the prices, the cellar, the
 * deliveries, the counts, the people, the rota, the weather and the digest.
 *
 * One transaction across all of them, so it either all goes or none of it does
 * — a half-cleared app, with a cellar but no nights to judge it against, would
 * be worse than either. Settings and the API key live outside the database and
 * are deliberately left alone.
 */
export async function clearEverything(): Promise<StoredCounts> {
  const before = await countEverything()
  const db = await open()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(ALL_STORES, 'readwrite')
    for (const name of ALL_STORES) tx.objectStore(name).clear()
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error ?? new Error('The database would not clear.'))
    tx.onabort = () => reject(tx.error ?? new Error('Clearing was aborted.'))
  })
  return before
}

export interface RestoreCounts {
  days: number
  prices: number
  cellarLines: number
  deliveries: number
  stockCounts: number
  people: number
  shifts: number
  weather: number
  photos: number
}

/**
 * Put a backup back.
 *
 * Records are merged by their own key rather than the store being emptied
 * first: restoring a file onto an app that already has something in it adds to
 * it, and a night with the same date is replaced by the file's version. That is
 * the behaviour that cannot lose anything by surprise — and it is what moving
 * to a new copy of the app wants anyway, since the new copy is empty.
 */
export async function restoreBackup(bundle: {
  days?: readonly DayRecord[]
  prices?: readonly PriceBookEntry[]
  stock?: StockConfig | null
  deliveries?: readonly Delivery[]
  stockCounts?: readonly StockCount[]
  people?: readonly Person[]
  shifts?: readonly Shift[]
  weather?: readonly DayWeather[]
  photos?: ReadonlyArray<{ id: string; savedAt: number; blob: Blob }>
}): Promise<RestoreCounts> {
  // The receipts first, so no night is ever briefly pointing at a picture that
  // is not there yet.
  for (const photo of bundle.photos ?? []) await putPhoto(photo.id, photo.blob, photo.savedAt)
  for (const day of bundle.days ?? []) await saveDay(day)
  if (bundle.prices && bundle.prices.length > 0) await savePriceBook(bundle.prices)
  // Merged, not swapped: a file with six lines in it must not take the other
  // twenty away, nor the pours that tell the till what to subtract.
  if (bundle.stock) await saveStockConfig(mergeStockConfig(await loadStockConfig(), bundle.stock))
  for (const delivery of bundle.deliveries ?? []) await saveDelivery(delivery)
  for (const count of bundle.stockCounts ?? []) await saveStockCount(count)
  for (const person of bundle.people ?? []) await savePerson(person)
  for (const shift of bundle.shifts ?? []) await saveShift(shift)
  if (bundle.weather && bundle.weather.length > 0) await saveWeather(bundle.weather)

  return {
    days: bundle.days?.length ?? 0,
    prices: bundle.prices?.length ?? 0,
    cellarLines: bundle.stock?.items.length ?? 0,
    deliveries: bundle.deliveries?.length ?? 0,
    stockCounts: bundle.stockCounts?.length ?? 0,
    people: bundle.people?.length ?? 0,
    shifts: bundle.shifts?.length ?? 0,
    weather: bundle.weather?.length ?? 0,
    photos: bundle.photos?.length ?? 0,
  }
}
