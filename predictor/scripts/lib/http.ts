// ---------------------------------------------------------------------------
// Cached HTTP for the pipeline.
//
// Historical seasons never change, so they are cached forever; the current
// season gets a short TTL. This keeps repeat runs (and the backtest, which
// re-reads ten seasons) fast and keeps us from hammering the sources.
//
// Node only — never imported by src/core.
// ---------------------------------------------------------------------------

import { mkdir, readFile, writeFile, stat } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
export const CACHE_DIR = join(HERE, '..', '..', '.cache', 'http')

export interface FetchOptions {
  /** Cache lifetime in ms. Infinity for immutable historical data. */
  ttlMs?: number
  /** Extra request headers (the live FPL API wants a browser-ish UA). */
  headers?: Record<string, string>
  /** Attempts before giving up, with exponential backoff. */
  retries?: number
  /** Abandon a single request after this long. */
  timeoutMs?: number
  /**
   * Reject an implausible 200 before it reaches the cache.
   *
   * A source that answers 200 with an empty body, an HTML error page or a
   * truncated file is worse than one that fails outright: the response looks
   * fine, gets cached, and — for the historical seasons, which are cached
   * forever and now carried between CI runs — stays wrong until someone
   * notices the model is off. Return false and the response is treated as a
   * failed attempt: retried, and never written to the cache.
   */
  accept?: (text: string) => boolean
}

function cachePath(url: string): string {
  const hash = createHash('sha256').update(url).digest('hex').slice(0, 20)
  return join(CACHE_DIR, `${hash}.cache`)
}

async function readCache(path: string, ttlMs: number): Promise<string | null> {
  try {
    if (ttlMs !== Infinity) {
      const s = await stat(path)
      if (Date.now() - s.mtimeMs > ttlMs) return null
    }
    return await readFile(path, 'utf8')
  } catch {
    return null
  }
}

/**
 * Fetch text with caching and retries.
 *
 * Network failures are retried with backoff; a 404 is returned as `null`
 * immediately, since it means "this season has no data yet" far more often
 * than it means a transient fault, and retrying it just wastes time.
 */
export async function fetchText(url: string, options: FetchOptions = {}): Promise<string | null> {
  const ttlMs = options.ttlMs ?? 30 * 60 * 1000
  const retries = options.retries ?? 3
  const timeoutMs = options.timeoutMs ?? 30000
  const accept = options.accept ?? ((t: string) => t.trim().length > 0)
  const path = cachePath(url)

  const cached = await readCache(path, ttlMs)
  if (cached !== null) return cached

  // Whether we have ever successfully held this URL, regardless of TTL. It
  // decides how to read a 404 below.
  const everSeen = await readCache(path, Infinity)

  let lastError: unknown = null
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, {
        headers: { 'user-agent': 'pl-predictor/1.0 (+github actions)', ...options.headers },
        // Without this a black-holed source holds the runner open indefinitely
        // and the daily job never finishes, rather than failing and retrying.
        signal: AbortSignal.timeout(timeoutMs),
      })
      if (res.status === 404) {
        // A 404 usually means "this season has no data yet", which is a real
        // answer and not worth retrying. But if we have successfully fetched
        // this exact URL before, the data cannot have stopped existing — that
        // is a blip at the source, and treating it as absence would quietly
        // drop a whole season out of the training corpus.
        if (everSeen === null) return null
        throw new Error('HTTP 404 for a URL that has previously returned data')
      }
      if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`)
      const text = await res.text()
      if (!accept(text)) {
        throw new Error(`response rejected as implausible (${text.length} bytes)`)
      }
      await mkdir(dirname(path), { recursive: true })
      await writeFile(path, text, 'utf8')
      return text
    } catch (err) {
      lastError = err
      if (attempt < retries) {
        const wait = 500 * Math.pow(2, attempt)
        await new Promise((r) => setTimeout(r, wait))
      }
    }
  }
  // Stale cache beats no data: a daily run should not fail outright because
  // one source blipped.
  const stale = everSeen
  if (stale !== null) {
    console.warn(`  ! ${url} unreachable, using stale cache (${String(lastError)})`)
    return stale
  }
  throw new Error(`Failed to fetch ${url}: ${String(lastError)}`)
}

/** Fetch and JSON-parse, or null on 404. */
export async function fetchJson<T>(url: string, options: FetchOptions = {}): Promise<T | null> {
  const text = await fetchText(url, options)
  return text === null ? null : (JSON.parse(text) as T)
}
