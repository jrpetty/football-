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
  const path = cachePath(url)

  const cached = await readCache(path, ttlMs)
  if (cached !== null) return cached

  let lastError: unknown = null
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, {
        headers: { 'user-agent': 'pl-predictor/1.0 (+github actions)', ...options.headers },
      })
      if (res.status === 404) return null
      if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`)
      const text = await res.text()
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
  // Stale cache beats no data: a weekly run should not fail outright because
  // one source blipped.
  const stale = await readCache(path, Infinity)
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
