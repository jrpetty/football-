// ---------------------------------------------------------------------------
// JSON artifact IO.
//
// Artifacts are committed to git and re-read by CI every week, so byte-stable
// output matters: unstable key order or float dust would produce a diff on
// every run and bury real changes in noise. Keys are therefore sorted and
// numbers rounded before writing.
// ---------------------------------------------------------------------------

import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
export const ROOT = join(HERE, '..', '..')
export const DATA_DIR = join(ROOT, 'public', 'data')
export const CACHE_DIR = join(ROOT, '.cache')

/** Recursively sort object keys so serialisation is deterministic. */
function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys)
  if (value && typeof value === 'object') {
    const src = value as Record<string, unknown>
    const out: Record<string, unknown> = {}
    for (const k of Object.keys(src).sort()) out[k] = sortKeys(src[k])
    return out
  }
  // -0 and 0 serialise differently; normalise so they never churn a diff.
  if (typeof value === 'number' && value === 0) return 0
  return value
}

/** Write a JSON artifact deterministically, creating parent dirs. */
export async function writeJson(path: string, value: unknown, pretty = false): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  const sorted = sortKeys(value)
  const text = pretty ? JSON.stringify(sorted, null, 2) : JSON.stringify(sorted)
  await writeFile(path, text + '\n', 'utf8')
}

/** Read a JSON file, or null when it does not exist. */
export async function readJson<T>(path: string): Promise<T | null> {
  try {
    return JSON.parse(await readFile(path, 'utf8')) as T
  } catch {
    return null
  }
}

/** Path of an artifact inside public/data. */
export function dataPath(...parts: string[]): string {
  return join(DATA_DIR, ...parts)
}

/** Path inside the gitignored intermediate cache. */
export function cachePath(...parts: string[]): string {
  return join(CACHE_DIR, ...parts)
}
