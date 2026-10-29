// The HUD numbers read on this computer (see glyphs.ts), field by field. The
// player marks where each number is (Settings → Read numbers on this PC).
// Every frame sent to the cloud model is also read here; when the model's
// answer comes back, the two readings of that same frame are compared, and the
// model's value labels the digits. A field's local reading is used only once
// it agrees with the model nearly every time (fail closed): until then the
// coach keeps relying on the model alone.

import type { NormRect, Frame, Observation } from '../types.ts'
import type { HudNumberSpec } from '../profile/schema.ts'
import { clockRenderings, GlyphBook, numberRenderings, parseClock, parseNumber, segment, type Glyph, type GlyphBookData } from './glyphs.ts'

export type HudFieldKind = 'number' | 'fraction' | 'clock'

export interface HudField {
  /** The reading (state key), e.g. "food"; for a fraction the first number ("population"). */
  readonly key: string
  readonly kind: HudFieldKind
  /** A fraction's second number, e.g. "populationCap". */
  readonly key2?: string
  readonly box: NormRect
}

/** Characters whose shapes must all be known before a field of that kind is read. */
const NEEDS: Record<HudFieldKind, string> = { number: '0123456789', fraction: '0123456789/', clock: '0123456789:' }

/** Checks a field needs before its local reading is used… */
export const TRUST_MIN_CHECKS = 8
/** …and the share of the last checks that must agree. */
export const TRUST_AGREEMENT = 0.95
const RECENT_CHECKS = 30
/** Segmented frames kept for answers still on their way back. */
const KEEP_FRAMES = 8

export interface HudFieldStats {
  readonly key: string
  readonly key2?: string
  readonly checks: number
  readonly agreed: number
  /** Agreement over the last checks (0..1), or null before any. */
  readonly recent: number | null
  readonly trusted: boolean
  /** The last value read here, as text. */
  readonly last: string | null
}

export interface HudStatus {
  readonly fields: readonly HudFieldStats[]
  /** Characters whose shape is known. */
  readonly known: readonly string[]
  /** Local readings made (all fields). */
  readonly reads: number
}

type FieldState = { checks: number; agreed: number; history: boolean[]; last: string | null }

export class HudReader {
  private readonly fields: readonly HudField[]
  private readonly book: GlyphBook
  private readonly state = new Map<string, FieldState>()
  private readonly frames = new Map<number, Map<string, Glyph[] | null>>()
  private reads = 0
  private changed = false

  constructor(fields: readonly HudField[], book?: GlyphBookData | null) {
    this.fields = fields
    this.book = new GlyphBook(book)
    for (const f of fields) this.state.set(f.key, { checks: 0, agreed: 0, history: [], last: null })
  }

  get configured(): boolean {
    return this.fields.length > 0
  }

  /** Segment every field of a frame (kept a short while for the model's answer about it). */
  private glyphsOf(frame: Frame): Map<string, Glyph[] | null> {
    let g = this.frames.get(frame.timestamp)
    if (g) return g
    g = new Map(this.fields.map((f) => [f.key, segment(frame, f.box)]))
    this.frames.set(frame.timestamp, g)
    while (this.frames.size > KEEP_FRAMES) this.frames.delete(this.frames.keys().next().value!)
    return g
  }

  /** Keep this frame's glyphs: it was sent to the model, and its answer will label them. */
  remember(frame: Frame): void {
    this.glyphsOf(frame)
  }

  /** Read the trusted fields of a frame, as observations for the engine. */
  read(frame: Frame, profileId: string): Observation[] {
    const glyphs = this.glyphsOf(frame)
    const out: Observation[] = []
    for (const f of this.fields) {
      const g = glyphs.get(f.key)
      const text = g ? this.book.read(g, NEEDS[f.kind]) : null
      if (text === null) continue
      this.reads++
      this.state.get(f.key)!.last = text
      if (!this.isTrusted(f.key)) continue
      for (const [key, value] of valuesOf(f, text)) {
        out.push({
          id: `hud-${key}-${frame.timestamp}`,
          timestampMs: frame.timestamp,
          gameProfileId: profileId,
          sourceRegion: `hud:${f.key}`,
          kind: 'state',
          eventType: key,
          value,
          confidence: 0.9,
          freshnessMs: 0,
          evidence: [`read on this PC from the HUD (“${text}”)`],
          source: 'pixel',
        })
      }
    }
    return out
  }

  /**
   * The model's readings of a frame this reader kept: compare them with this computer's reading of
   * the same frame, then learn the digits from them.
   */
  check(frameAt: number, observations: readonly Observation[]): void {
    const glyphs = this.frames.get(frameAt)
    if (!glyphs) return
    const model = new Map<string, number>()
    for (const o of observations) if (o.kind === 'state' && typeof o.value === 'number') model.set(o.eventType, o.value)
    for (const f of this.fields) {
      const g = glyphs.get(f.key)
      const v1 = model.get(f.key)
      const v2 = f.key2 ? model.get(f.key2) : undefined
      if (!g || v1 === undefined || (f.kind === 'fraction' && v2 === undefined)) continue
      const renderings = f.kind === 'clock' ? clockRenderings(v1) : f.kind === 'fraction' ? fractionRenderings(v1, v2!) : numberRenderings(v1)
      // Compare first (with what was known before this answer), then learn.
      const mine = this.book.read(g, NEEDS[f.kind])
      if (mine !== null) {
        const s = this.state.get(f.key)!
        const agree = valuesOf(f, mine).every(([k, v]) => (k === f.key ? v === v1 : v === v2))
        s.checks++
        if (agree) s.agreed++
        s.history = [...s.history.slice(-(RECENT_CHECKS - 1)), agree]
        this.changed = true
      }
      if (this.book.learn(g, renderings)) this.changed = true
    }
  }

  private isTrusted(key: string): boolean {
    const s = this.state.get(key)
    if (!s || s.history.length < TRUST_MIN_CHECKS) return false
    return s.history.filter(Boolean).length / s.history.length >= TRUST_AGREEMENT
  }

  status(): HudStatus {
    return {
      fields: this.fields.map((f) => {
        const s = this.state.get(f.key)!
        return {
          key: f.key,
          ...(f.key2 ? { key2: f.key2 } : {}),
          checks: s.checks,
          agreed: s.agreed,
          recent: s.history.length ? s.history.filter(Boolean).length / s.history.length : null,
          trusted: this.isTrusted(f.key),
          last: s.last,
        }
      }),
      known: this.book.known,
      reads: this.reads,
    }
  }

  /** The share of fields read locally now (0..1). */
  get trustedShare(): number {
    return this.fields.length ? this.fields.filter((f) => this.isTrusted(f.key)).length / this.fields.length : 0
  }

  /** The learned shapes when they changed since the last call (to keep between sessions), else null. */
  takeBook(): GlyphBookData | null {
    if (!this.changed) return null
    this.changed = false
    return this.book.toJSON()
  }
}

function fractionRenderings(a: number, b: number): string[] {
  return numberRenderings(a).flatMap((x) => numberRenderings(b).map((y) => `${x}/${y}`))
}

/** The values a field's text stands for. */
function valuesOf(f: HudField, text: string): [string, number][] {
  if (f.kind === 'clock') {
    const v = parseClock(text)
    return v === null ? [] : [[f.key, v]]
  }
  if (f.kind === 'fraction') {
    const [a, b] = text.split('/')
    const x = a !== undefined ? parseNumber(a) : null
    const y = b !== undefined ? parseNumber(b) : null
    return x === null || y === null || !f.key2 ? [] : [
      [f.key, x],
      [f.key2, y],
    ]
  }
  const v = parseNumber(text)
  return v === null ? [] : [[f.key, v]]
}

/** The fields to read: the pack's HUD numbers the player has marked on their screen. */
export function hudFieldsFor(specs: readonly HudNumberSpec[] | undefined, boxes: Readonly<Record<string, NormRect>> | undefined): HudField[] {
  if (!specs || !boxes) return []
  return specs.flatMap((s) => {
    const box = boxes[s.key]
    return box ? [{ key: s.key, kind: s.kind, ...(s.key2 ? { key2: s.key2 } : {}), box }] : []
  })
}
