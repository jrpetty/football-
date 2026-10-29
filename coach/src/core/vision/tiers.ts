// Smart reading: two models instead of one for screen readings. Every look is
// read by the fast, inexpensive model (Haiku 5.5, no thinking); the same frame
// goes to the player's chosen model only when it holds something that needs
// its care:
// - an event with details to read (a fight, a death and its killcam);
// - names that must match the reference exactly (enemy units and buildings,
//   the opponent's civilisation, the map) or a killer's spot and weapon;
// - a result banner, or a menu or loading screen not seen before;
// - and every so often, to check the fast model against it.
// Where both read the same frame, their numbers are compared; if the fast
// model disagrees too often, every look goes to the chosen model for the rest
// of the session (fail closed), and the app says so.

import type { GameProfile } from '../profile/schema.ts'
import type { VisionResponse } from './contract.ts'

export const FAST_VISION_MODEL = 'claude-haiku-5-5'
/** One look in this many goes to both models, to check the fast one. */
export const AUDIT_EVERY = 25
/** Readings compared before the fast model can be judged… */
export const AGREEMENT_MIN_CHECKED = 20
/** …and the share that must agree, else every look goes to the chosen model. */
export const AGREEMENT_MIN = 0.8
/** Assumed share of looks sent on to the chosen model, until this session measures it. */
export const DETAIL_SHARE_ASSUMED = 0.2

export interface TierStats {
  /** Looks read by the fast model. */
  readonly fast: number
  /** Looks also read by the chosen model, and why. */
  readonly detail: number
  readonly reasons: Readonly<Record<string, number>>
  /** Numbers and options both models read on the same frame, and how many agreed. */
  readonly checked: number
  readonly agreed: number
  /** Set once the fast model disagreed too often: every look then goes to the chosen model. */
  readonly off: string | null
}

export const NO_TIER_STATS: TierStats = { fast: 0, detail: 0, reasons: {}, checked: 0, agreed: 0, off: null }

/** The event types and readings that send a look on to the chosen model, for a pack. */
export function detailSpec(profile: GameProfile): { events: Set<string>; keys: Set<string> } {
  const v = profile.vision
  const d = v?.detail
  return {
    events: new Set(d?.events ?? (v?.events ?? []).filter((e) => (e.attributes ?? []).length > 0).map((e) => e.type)),
    keys: new Set(d?.keys ?? []),
  }
}

/** Names of screens already known (from the prompt's list "- Name (kind): summary"). */
export function knownScreenNames(screensText: string | undefined): Set<string> {
  const out = new Set<string>()
  for (const line of (screensText ?? '').split('\n')) {
    const m = /^- (.+?) \((\w+)\)/.exec(line)
    if (m) out.add(m[1].toLowerCase())
  }
  return out
}

/** A name the chosen model confirmed is not sent on again while it stays the same; a changed one at most this often. */
export const RECHECK_KEY_MS = 20_000

export const normReading = (v: unknown) => String(v).trim().toLowerCase().replace(/\s+/g, ' ')

/** Why this look should also be read by the chosen model, or null. `confirmed` skips names it already read the same. */
export function needsDetail(
  profile: GameProfile,
  r: VisionResponse,
  opts: { readonly known: Set<string>; readonly audit: boolean; readonly confirmed?: (key: string, value: string) => boolean },
): string | null {
  const spec = detailSpec(profile)
  const ev = r.events.find((e) => spec.events.has(e.type))
  if (ev) return `event:${ev.type}`
  const key = r.state.find((s) => spec.keys.has(s.key) && String(s.value).trim() !== '' && !opts.confirmed?.(s.key, s.value))
  if (key) return `reading:${key.key}`
  if (r.phase === 'match_end' || r.phase === 'round_end' || r.visibleOutcome === 'success' || r.visibleOutcome === 'failure') return 'result'
  if (r.screen && r.screen.kind !== 'gameplay' && !opts.known.has(r.screen.name.trim().toLowerCase())) return 'new screen'
  if (opts.audit) return 'check'
  return null
}

/** Numbers and options both models read on the same frame: how many there were, and how many agreed. */
export function compareReadings(profile: GameProfile, fast: VisionResponse, detail: VisionResponse): { checked: number; agreed: number } {
  const types = new Map((profile.vision?.state ?? []).map((s) => [s.key, s.type]))
  const norm = (v: unknown) => String(v).trim().toLowerCase().replace(/,/g, '')
  const d = new Map(detail.state.map((s) => [s.key, s.value]))
  let checked = 0
  let agreed = 0
  for (const s of fast.state) {
    const t = types.get(s.key)
    if ((t !== 'number' && t !== 'enum') || !d.has(s.key)) continue
    checked++
    if (norm(s.value) === norm(d.get(s.key))) agreed++
  }
  return { checked, agreed }
}

export function addTierStats(t: TierStats, fast: boolean, detail: string | null, compared?: { checked: number; agreed: number }): TierStats {
  const checked = t.checked + (compared?.checked ?? 0)
  const agreed = t.agreed + (compared?.agreed ?? 0)
  const off =
    t.off ??
    (checked >= AGREEMENT_MIN_CHECKED && agreed / checked < AGREEMENT_MIN
      ? `The fast model agreed with ${Math.round((agreed / checked) * 100)}% of ${checked} readings checked (under ${AGREEMENT_MIN * 100}%): every look now goes to the chosen model.`
      : null)
  return {
    fast: t.fast + (fast ? 1 : 0),
    detail: t.detail + (detail ? 1 : 0),
    reasons: detail ? { ...t.reasons, [detail.split(':')[0]]: (t.reasons[detail.split(':')[0]] ?? 0) + 1 } : t.reasons,
    checked,
    agreed,
    off,
  }
}
