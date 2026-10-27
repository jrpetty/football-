// Screen memory: the menus, loading screens and scoreboards of a game, learned
// as the vision model meets them. Each screen is stored as a 64-bit perceptual
// fingerprint (difference hash, 16 hex characters) plus a short name and
// summary — about 200 bytes per screen, never an image. When a frame matches
// a known screen, the coach recognises it on this computer without sending
// anything; gameplay frames are never cached (they are never the same twice).

import type { Frame } from '../types.ts'

export const SCREEN_KINDS = ['gameplay', 'menu', 'loading', 'scoreboard', 'map', 'cutscene', 'other'] as const
export type ScreenKind = (typeof SCREEN_KINDS)[number]

export interface ScreenInfo {
  readonly name: string
  readonly kind: ScreenKind
  readonly summary: string
}

export interface ScreenEntry extends ScreenInfo {
  /** 64-bit difference hash, hex. */
  readonly h: string
  /** Times seen. */
  readonly n: number
  /** Last seen, ISO date (day precision is enough for eviction). */
  readonly t: string
}

export interface ScreenCatalog {
  readonly v: 1
  readonly packId: string
  readonly screens: readonly ScreenEntry[]
}

/** Most screens a game keeps; the least used are evicted first. */
export const SCREEN_CAP = 400
/** Hamming distance (of 64 bits) within which two frames are the same screen. */
export const SAME_SCREEN_BITS = 8

const MAX_NAME = 60
/** Luminance difference (0–255) below which two neighbouring cells count as equal. */
const FLAT = 6
const MAX_SUMMARY = 240

/**
 * Difference hash: the frame scaled to 9×8 luminance; each bit says whether a
 * cell is brighter than its right neighbour. Robust to scaling, compression
 * and small animations; different screens differ in many bits. Neighbours
 * within a few levels count as equal, so flat areas do not flip bits at random.
 */
export function dHash(frame: Frame): string {
  const W = 9
  const H = 8
  const sums = new Float64Array(W * H)
  const counts = new Uint32Array(W * H)
  const stepX = Math.max(1, Math.floor(frame.width / 64))
  const stepY = Math.max(1, Math.floor(frame.height / 48))
  for (let y = 0; y < frame.height; y += stepY) {
    const cy = Math.min(H - 1, Math.floor((y * H) / frame.height))
    for (let x = 0; x < frame.width; x += stepX) {
      const cx = Math.min(W - 1, Math.floor((x * W) / frame.width))
      const i = (y * frame.width + x) * 4
      sums[cy * W + cx] += 0.299 * frame.data[i] + 0.587 * frame.data[i + 1] + 0.114 * frame.data[i + 2]
      counts[cy * W + cx]++
    }
  }
  let hex = ''
  for (let y = 0; y < H; y++) {
    let byte = 0
    for (let x = 0; x < W - 1; x++) {
      const a = sums[y * W + x] / (counts[y * W + x] || 1)
      const b = sums[y * W + x + 1] / (counts[y * W + x + 1] || 1)
      byte = (byte << 1) | (a - b > FLAT ? 1 : 0)
    }
    hex += byte.toString(16).padStart(2, '0')
  }
  return hex
}

export function hamming(a: string, b: string): number {
  let d = 0
  for (let i = 0; i < 16; i += 2) {
    let x = Number.parseInt(a.slice(i, i + 2), 16) ^ Number.parseInt(b.slice(i, i + 2), 16)
    while (x) {
      d += x & 1
      x >>= 1
    }
  }
  return d
}

/** Clean an untrusted screen description from the model; null if unusable. */
export function cleanScreen(raw: unknown): ScreenInfo | null {
  const r = raw as Partial<ScreenInfo> | null
  if (!r || typeof r !== 'object' || typeof r.name !== 'string' || typeof r.kind !== 'string') return null
  const name = r.name.replace(/\s+/g, ' ').trim().slice(0, MAX_NAME)
  if (!name || !(SCREEN_KINDS as readonly string[]).includes(r.kind)) return null
  const summary = typeof r.summary === 'string' ? r.summary.replace(/\s+/g, ' ').trim().slice(0, MAX_SUMMARY) : ''
  return { name, kind: r.kind as ScreenKind, summary }
}

export class ScreenMemory {
  private list: ScreenEntry[]
  readonly packId: string

  constructor(packId: string, catalog: ScreenCatalog | null = null) {
    this.packId = packId
    this.list = catalog && catalog.packId === packId ? catalog.screens.filter(isEntry).slice(0, SCREEN_CAP) : []
  }

  /** The known (non-gameplay) screen this fingerprint matches, if any. */
  match(hash: string): ScreenEntry | null {
    let best: ScreenEntry | null = null
    let bestD = SAME_SCREEN_BITS + 1
    for (const e of this.list) {
      const d = hamming(hash, e.h)
      if (d < bestD) {
        best = e
        bestD = d
      }
    }
    return best
  }

  /**
   * Record what the model said a frame was. Only non-gameplay screens are
   * kept. Returns the entry when the catalog changed in a way worth saving.
   */
  learn(hash: string, info: ScreenInfo, now: Date): { entry: ScreenEntry; isNew: boolean } | null {
    if (info.kind === 'gameplay') return null
    const day = now.toISOString().slice(0, 10)
    const i = this.list.findIndex((e) => hamming(hash, e.h) <= SAME_SCREEN_BITS)
    if (i >= 0) {
      const old = this.list[i]
      // Keep the first name (stable), but let a richer summary replace a thin one.
      const entry: ScreenEntry = { ...old, summary: old.summary.length >= info.summary.length ? old.summary : info.summary, n: old.n + 1, t: day }
      this.list[i] = entry
      return old.t !== day || entry.summary !== old.summary ? { entry, isNew: false } : null
    }
    const entry: ScreenEntry = { h: hash, ...info, n: 1, t: day }
    this.list.push(entry)
    if (this.list.length > SCREEN_CAP) {
      // Evict the least useful: seen least, then longest ago.
      this.list.sort((a, b) => b.n - a.n || b.t.localeCompare(a.t))
      this.list.length = SCREEN_CAP
    }
    return { entry, isNew: true }
  }

  /** Count a local recognition (no request was sent). */
  seen(entry: ScreenEntry, now: Date): void {
    const i = this.list.indexOf(entry)
    if (i >= 0) this.list[i] = { ...entry, n: entry.n + 1, t: now.toISOString().slice(0, 10) }
  }

  get catalog(): ScreenCatalog {
    return { v: 1, packId: this.packId, screens: [...this.list] }
  }
}

function isEntry(e: unknown): e is ScreenEntry {
  const x = e as ScreenEntry
  return (
    !!x &&
    typeof x.h === 'string' &&
    /^[0-9a-f]{16}$/.test(x.h) &&
    cleanScreen(x) !== null &&
    typeof x.n === 'number' &&
    typeof x.t === 'string'
  )
}

export function isScreenCatalog(v: unknown): v is ScreenCatalog {
  const c = v as ScreenCatalog
  return !!c && c.v === 1 && typeof c.packId === 'string' && Array.isArray(c.screens) && c.screens.length <= SCREEN_CAP && c.screens.every(isEntry)
}

/** Known screens as a short list for the model's context. */
export function screensForPrompt(c: ScreenCatalog | null, limit = 60): string {
  if (!c?.screens.length) return ''
  return [...c.screens]
    .sort((a, b) => b.n - a.n)
    .slice(0, limit)
    .map((s) => `- ${s.name} (${s.kind})${s.summary ? `: ${s.summary}` : ''}`)
    .join('\n')
}
