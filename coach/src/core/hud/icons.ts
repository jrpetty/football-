// Status icons read by their colour on this computer, for free (no cloud
// look): DayZ's water, energy, health and blood icons go white → yellow →
// orange → red as they drop, the thermometer runs dark blue → light blue →
// white → orange → red, and some badges (bleeding) only show when they apply.
// The player marks a tight box around each icon (Settings).
//
// The icons sit over the game world, so a box also holds bits of sky, snow or
// walls behind the icon. Those change as the player moves; the icon does not.
// Each box keeps a running mean and spread of every sampled pixel's colour,
// and only pixels that stay steady (the icon) are read. Fail closed: no
// reading until the box has been watched for a while, when too few steady
// icon pixels are found, or when no colour clearly wins. When the cloud model
// does look (a question, or a look every few seconds), its reading of the
// same frame is compared with this one, and a field that disagrees too often
// stops being used.

import type { Frame, NormRect, Observation } from '../types.ts'

export type IconKind = 'level' | 'temperature' | 'presence'

export interface IconField {
  /** The reading (state key), e.g. "water". */
  readonly key: string
  readonly kind: IconKind
  readonly box: NormRect
  /** presence: the value while the icon shows (e.g. 1 bleeding wound), and 0 while it does not. */
  readonly present?: number
}

export type IconColour = 'white' | 'yellow' | 'orange' | 'red' | 'lightBlue' | 'darkBlue'

const LEVEL: Partial<Record<IconColour, string>> = { white: 'ok', yellow: 'low', orange: 'very_low', red: 'critical' }
const TEMPERATURE: Partial<Record<IconColour, string>> = { darkBlue: 'very_cold', lightBlue: 'cold', white: 'normal', yellow: 'hot', orange: 'hot', red: 'very_hot' }

/** Frames a box is watched before it is read. */
export const ICON_WARMUP = 8
/** Steady icon pixels needed, as a share of the box's samples. */
const MIN_ICON_SHARE = 0.04
/** The winning colour's share of the icon pixels. */
const MIN_WIN_SHARE = 0.6
/** A pixel is steady when its brightness varies less than this (0..1). */
const STEADY_SD = 0.07
/** Running averages: weight of the newest frame. */
const ALPHA = 0.15
/** Samples across and down a box at most. */
const GRID = 24
/** Checks against the model before a field can be stopped, and the agreement it must keep. */
export const ICON_CHECKS_MIN = 5
export const ICON_AGREEMENT = 0.7
const KEEP_FRAMES = 8

export function rgbToHsv(r: number, g: number, b: number): { h: number; s: number; v: number } {
  const R = r / 255
  const G = g / 255
  const B = b / 255
  const max = Math.max(R, G, B)
  const min = Math.min(R, G, B)
  const d = max - min
  let h = 0
  if (d > 0) {
    if (max === R) h = ((G - B) / d) % 6
    else if (max === G) h = (B - R) / d + 2
    else h = (R - G) / d + 4
    h *= 60
    if (h < 0) h += 360
  }
  return { h, s: max === 0 ? 0 : d / max, v: max }
}

/** The icon colour of one pixel, or null for the dark or dull pixels around an icon. */
export function colourOf(r: number, g: number, b: number): IconColour | null {
  const { h, s, v } = rgbToHsv(r, g, b)
  if (s < 0.22) return v >= 0.7 ? 'white' : null
  if (v < 0.45) return null
  if (h < 15 || h >= 340) return 'red'
  if (h < 40) return 'orange'
  if (h < 70) return 'yellow'
  if (h >= 170 && h < 260) return s >= 0.6 && v < 0.85 ? 'darkBlue' : 'lightBlue'
  return null
}

export function valueFor(kind: IconKind, colour: IconColour): string | null {
  if (kind === 'level') return LEVEL[colour] ?? null
  if (kind === 'temperature') return TEMPERATURE[colour] ?? null
  return null
}

type BoxState = { frames: number; mean: Float32Array; m2: Float32Array; n: number }

export interface IconReading {
  readonly key: string
  readonly value: string | number
  readonly colour: IconColour | null
  /** The winning colour's share of the icon pixels (presence: the red share of the box). */
  readonly confidence: number
}

export interface IconFieldStats {
  readonly key: string
  readonly last: string | null
  readonly colour: IconColour | null
  readonly checks: number
  readonly agreed: number
  /** Read and used: watched long enough and not stopped for disagreeing with the model. */
  readonly usable: boolean
  readonly stopped: boolean
}

type FieldStats = { last: string | null; colour: IconColour | null; checks: number; agreed: number; history: boolean[] }

export class IconReader {
  private readonly fields: readonly IconField[]
  private readonly boxes = new Map<string, BoxState>()
  private readonly stats = new Map<string, FieldStats>()
  private readonly frames = new Map<number, Map<string, string | number>>()

  constructor(fields: readonly IconField[]) {
    this.fields = fields
    for (const f of fields) this.stats.set(f.key, { last: null, colour: null, checks: 0, agreed: 0, history: [] })
  }

  get configured(): boolean {
    return this.fields.length > 0
  }

  /** Read every marked icon in a frame; observations only for the readings that pass. */
  read(frame: Frame, profileId: string): Observation[] {
    const out: Observation[] = []
    const seen = new Map<string, string | number>()
    for (const f of this.fields) {
      const r = this.readField(frame, f)
      if (!r) continue
      const s = this.stats.get(f.key)!
      s.last = String(r.value)
      s.colour = r.colour
      seen.set(f.key, r.value)
      if (this.stopped(f.key)) continue
      out.push({
        id: `icon-${f.key}-${frame.timestamp}`,
        timestampMs: frame.timestamp,
        gameProfileId: profileId,
        sourceRegion: `icon:${f.key}`,
        kind: 'state',
        eventType: f.key,
        value: r.value,
        confidence: Math.min(0.85, 0.5 + r.confidence / 2),
        freshnessMs: 0,
        evidence: [r.colour ? `the ${f.key} icon is ${COLOUR_WORDS[r.colour]} (read on this PC)` : `no ${f.key} icon showing (read on this PC)`],
        source: 'pixel',
      })
    }
    // A status icon read means normal play: the HUD's icons are hidden in menus, the inventory and the death screen.
    if (out.some((o) => this.fields.find((f) => f.key === o.eventType)?.kind !== 'presence')) {
      out.push({
        id: `icon-phase-${frame.timestamp}`,
        timestampMs: frame.timestamp,
        gameProfileId: profileId,
        sourceRegion: 'icon:phase',
        kind: 'state',
        eventType: 'phase',
        value: 'round',
        confidence: 0.6,
        freshnessMs: 0,
        evidence: ['status icons showing (read on this PC): in play'],
        source: 'pixel',
      })
    }
    this.frames.set(frame.timestamp, seen)
    while (this.frames.size > KEEP_FRAMES) this.frames.delete(this.frames.keys().next().value!)
    return out
  }

  /**
   * The model's readings of a frame read here: compare. A field whose readings disagree with the
   * model's too often is no longer used.
   */
  check(frameAt: number, observations: readonly Observation[]): void {
    // Icons are read about once a second: compare with the reading nearest the model's frame (within 1.5 s).
    const near = [...this.frames.keys()].filter((t) => Math.abs(t - frameAt) <= 1500).sort((a, b) => Math.abs(a - frameAt) - Math.abs(b - frameAt))[0]
    const mine = near === undefined ? undefined : this.frames.get(near)
    if (!mine) return
    for (const f of this.fields) {
      const o = observations.find((x) => x.kind === 'state' && x.eventType === f.key)
      const v = mine.get(f.key)
      if (!o || v === undefined) continue
      const s = this.stats.get(f.key)!
      const agree = f.kind === 'presence' ? (Number(o.value) > 0) === (Number(v) > 0) : String(o.value) === String(v)
      s.checks++
      if (agree) s.agreed++
      s.history = [...s.history.slice(-29), agree]
    }
  }

  private stopped(key: string): boolean {
    const s = this.stats.get(key)!
    return s.history.length >= ICON_CHECKS_MIN && s.history.filter(Boolean).length / s.history.length < ICON_AGREEMENT
  }

  status(): IconFieldStats[] {
    return this.fields.map((f) => {
      const s = this.stats.get(f.key)!
      const b = this.boxes.get(f.key)
      const stopped = this.stopped(f.key)
      return { key: f.key, last: s.last, colour: s.colour, checks: s.checks, agreed: s.agreed, usable: !stopped && (b?.frames ?? 0) >= ICON_WARMUP && s.last !== null, stopped }
    })
  }

  private readField(frame: Frame, f: IconField): IconReading | null {
    const samples = sampleBox(frame, f.box)
    if (!samples) return null
    // Presence (a badge that comes and goes): enough bright red in the box means it shows.
    if (f.kind === 'presence') {
      let red = 0
      for (let i = 0; i < samples.n; i++) if (colourOf(samples.r[i], samples.g[i], samples.b[i]) === 'red') red++
      const share = red / samples.n
      return share >= 0.08 ? { key: f.key, value: f.present ?? 1, colour: 'red', confidence: Math.min(1, share * 4) } : share <= 0.01 ? { key: f.key, value: 0, colour: null, confidence: 1 } : null
    }
    const st = this.track(f.key, samples)
    if (st.frames < ICON_WARMUP) return null
    const counts = new Map<IconColour, number>()
    let icon = 0
    for (let i = 0; i < samples.n; i++) {
      const sd = Math.sqrt(st.m2[i])
      if (sd > STEADY_SD) continue
      const c = colourOf(samples.r[i], samples.g[i], samples.b[i])
      if (!c) continue
      icon++
      counts.set(c, (counts.get(c) ?? 0) + 1)
    }
    if (icon < Math.max(4, samples.n * MIN_ICON_SHARE)) return null
    const [colour, n] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]
    const share = n / icon
    if (share < MIN_WIN_SHARE) return null
    const value = valueFor(f.kind, colour)
    return value === null ? null : { key: f.key, value, colour, confidence: share }
  }

  /** Running mean and spread of each sample's brightness. */
  private track(key: string, s: Samples): BoxState {
    let st = this.boxes.get(key)
    if (!st || st.n !== s.n) {
      st = { frames: 0, mean: new Float32Array(s.n), m2: new Float32Array(s.n), n: s.n }
      for (let i = 0; i < s.n; i++) st.mean[i] = s.v[i]
      this.boxes.set(key, st)
    }
    for (let i = 0; i < s.n; i++) {
      const d = s.v[i] - st.mean[i]
      st.mean[i] += ALPHA * d
      st.m2[i] = (1 - ALPHA) * (st.m2[i] + ALPHA * d * d)
    }
    st.frames++
    return st
  }
}

const COLOUR_WORDS: Readonly<Record<IconColour, string>> = { white: 'white', yellow: 'yellow', orange: 'orange', red: 'red', lightBlue: 'light blue', darkBlue: 'dark blue' }

type Samples = { n: number; r: Uint8Array; g: Uint8Array; b: Uint8Array; v: Float32Array }

/** Up to GRID × GRID evenly spread samples of a box. */
export function sampleBox(frame: Frame, box: NormRect): Samples | null {
  const x0 = Math.floor(box.x * frame.width)
  const y0 = Math.floor(box.y * frame.height)
  const w = Math.max(1, Math.floor(box.w * frame.width))
  const h = Math.max(1, Math.floor(box.h * frame.height))
  if (x0 < 0 || y0 < 0 || x0 + w > frame.width || y0 + h > frame.height || w < 2 || h < 2) return null
  const cols = Math.min(GRID, w)
  const rows = Math.min(GRID, h)
  const n = cols * rows
  const s: Samples = { n, r: new Uint8Array(n), g: new Uint8Array(n), b: new Uint8Array(n), v: new Float32Array(n) }
  let k = 0
  for (let j = 0; j < rows; j++) {
    const y = y0 + Math.floor(((j + 0.5) * h) / rows)
    for (let i = 0; i < cols; i++) {
      const x = x0 + Math.floor(((i + 0.5) * w) / cols)
      const p = (y * frame.width + x) * 4
      const r = frame.data[p]
      const g = frame.data[p + 1]
      const b = frame.data[p + 2]
      s.r[k] = r
      s.g[k] = g
      s.b[k] = b
      s.v[k] = Math.max(r, g, b) / 255
      k++
    }
  }
  return s
}

/** One look at a box in a single picture (for marking): the colour most of its icon pixels have. */
export function probeIcon(frame: Frame, box: NormRect, kind: IconKind): { colour: IconColour | null; value: string | null; share: number } {
  const s = sampleBox(frame, box)
  if (!s) return { colour: null, value: null, share: 0 }
  const counts = new Map<IconColour, number>()
  let icon = 0
  for (let i = 0; i < s.n; i++) {
    const c = colourOf(s.r[i], s.g[i], s.b[i])
    if (!c) continue
    icon++
    counts.set(c, (counts.get(c) ?? 0) + 1)
  }
  if (!icon) return { colour: null, value: null, share: 0 }
  const [colour, n] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]
  return { colour, value: kind === 'presence' ? (colour === 'red' ? 'showing' : null) : valueFor(kind, colour), share: n / icon }
}

export function colourWord(c: IconColour | null): string {
  return c ? COLOUR_WORDS[c] : 'none'
}

/** The fields to read: the pack's status icons the player has marked. */
export function iconFieldsFor(specs: readonly { readonly key: string; readonly kind: IconKind; readonly present?: number }[] | undefined, boxes: Readonly<Record<string, NormRect>> | undefined): IconField[] {
  if (!specs || !boxes) return []
  return specs.flatMap((s) => {
    const box = boxes[s.key]
    return box ? [{ key: s.key, kind: s.kind, box, ...(s.present !== undefined ? { present: s.present } : {}) }] : []
  })
}
