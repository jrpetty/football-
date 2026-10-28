// The vision recogniser's contract: what we ask a model to report about one
// frame, and how its answer is checked before it becomes Observations.
//
// The model only *describes the frame* into a fixed shape. The validator here
// is the gate: unknown keys and event types are dropped, numbers outside the
// pack's ranges are dropped, enum values must be listed, zones must exist,
// confidence is clamped, and a response that claims nothing is fine. This code
// is shared by the main process (prompt) and the worker (validation), and has
// no dependencies.

import { STANDARD_STATE_KEYS, type GameProfile, type VisionStateSpec } from '../profile/schema.ts'
import type { GamePhase, Observation } from '../types.ts'
import { SCREEN_KINDS } from './screens.ts'

export const PHASES: readonly GamePhase[] = ['menu', 'round', 'combat', 'round_end', 'match_end', 'unknown']
export const OUTCOMES = ['success', 'failure', 'unknown'] as const

/** The response shape (also expressed as a Zod schema in the main process). */
export interface VisionResponse {
  readonly phase: string
  readonly phaseConfidence: number
  readonly visibleOutcome: string | null
  readonly visibleHealth: number | null
  readonly state: readonly { readonly key: string; readonly value: string; readonly confidence: number }[]
  readonly events: readonly {
    readonly type: string
    readonly zone: string | null
    readonly attributes: readonly { readonly key: string; readonly value: string }[]
    readonly confidence: number
    readonly evidence: string
  }[]
  /** What could not be read, in a few words (for diagnostics). */
  readonly unreadable: string
  /** What screen this is (menu, loading, scoreboard, gameplay…), so new menus are understood and remembered. */
  readonly screen?: { readonly name: string; readonly kind: string; readonly summary: string } | null
}

/** Extra, slowly changing context for the recogniser: the game knowledge base and the screens already learned. */
export interface PromptExtras {
  readonly knowledge?: string
  readonly screens?: string
}

/** The fixed part of the prompt: cacheable, one per pack. */
export function visionSystemPrompt(profile: GameProfile, extras: PromptExtras = {}): string {
  const v = profile.vision
  if (!v) throw new Error(`${profile.id} has no vision schema`)
  const zones = new Map((profile.zones ?? []).map((z) => [z.id, z]))
  const lines: string[] = [
    `You are the vision recogniser of a screen-only gameplay coach. You see one frame captured from the player's own screen in ${profile.game.title}.`,
    'Describe ONLY what is plainly visible in this frame. Never infer hidden information (fog of war, off-screen positions, opponents\' inputs or private data). Never guess a number you cannot read: leave it out.',
    'Confidence is your probability (0 to 1) that the reading is correct. Use low confidence for blurry, partial or ambiguous elements.',
    'Respond only in the required JSON shape. "state" lists only the keys you could read. "events" lists only things clearly happening in this frame; an empty list is a good answer.',
    '',
    `What to look at: ${v.purpose}`,
    '',
    'phase: one of menu (menus, lobby, loading), round (normal play), combat (fighting), round_end (a round/half ended: result banner or half-time), match_end (final result or post-match statistics), unknown.',
    'visibleOutcome: success or failure only if a result banner or screen shows the player won or lost; otherwise null.',
    'visibleHealth: the player\'s own health as a fraction 0-1 only if a health bar is clearly visible; otherwise null.',
    '',
    'State keys:',
    ...v.state.map((s) => `- ${s.key} (${describeType(s)}): ${s.description}`),
  ]
  if (v.events.length) {
    lines.push('', 'Event types:')
    for (const e of v.events) {
      lines.push(`- ${e.type}: ${e.description}`)
      if (e.zoneSet) {
        const z = zones.get(e.zoneSet)!
        lines.push(`  zone (${z.label}): ${z.description} Cells: ${z.cells.map((c) => `${c.id} = ${c.label}`).join('; ')}. Use null if unclear.`)
      }
      for (const a of e.attributes ?? []) lines.push(`  attribute ${a.key} (one of ${a.options.join(', ')}): ${a.description}`)
    }
  } else lines.push('', 'Event types: none for this game; always return an empty events list.')
  if (v.crops?.length) lines.push('', `The image is a vertical stack of crops, top to bottom: ${v.crops.map((c) => c.label).join(', ')}.`)
  lines.push('', 'For every event, "evidence" names the visible cue in a few words (e.g. "GOAL graphic", "replay of shot from left of box").')
  lines.push(
    '',
    'screen: always say what screen this is. name: what the game calls it, or a short plain name (e.g. "Main menu", "Loadout editor", "Post-match scoreboard", "Loading screen", "In match"). ' +
      `kind: one of ${SCREEN_KINDS.join(', ')}. summary: one sentence — for a menu, what it is for and the key options or information on it; for gameplay, what is happening. ` +
      'If it matches a known screen below, reuse that exact name.',
  )
  if (extras.screens) lines.push('', 'Screens already known in this game:', extras.screens)
  if (extras.knowledge) lines.push('', 'Game reference (background knowledge to help you recognise what you see; never report anything that is not visible in this frame):', extras.knowledge)
  return lines.join('\n')
}

/** The per-frame part of the prompt: player context the recogniser needs (e.g. which side is the player's). */
export function visionUserText(profile: GameProfile, context: Readonly<Record<string, string | number>>): string {
  const ctx = (profile.playerContext ?? [])
    .filter((c) => context[c.id] !== undefined && context[c.id] !== '')
    .map((c) => `${c.label}: ${context[c.id]}`)
  return ctx.length ? `Player context. ${ctx.join('. ')}.\nDescribe this frame.` : 'Describe this frame.'
}

function describeType(s: VisionStateSpec): string {
  if (s.type === 'enum') return `one of ${s.options!.join(', ')}`
  if (s.type === 'number') return `number${s.min !== undefined ? ` ${s.min}-${s.max}` : ''}`
  return s.type
}

export interface ValidationStats {
  readonly accepted: number
  readonly dropped: readonly string[]
}

/**
 * Turn an untrusted model response into Observations. `frameAt` is the capture
 * time of the frame; `arrivedAt` when the answer reached us.
 */
export function validateVisionResponse(
  raw: unknown,
  profile: GameProfile,
  frameAt: number,
  arrivedAt: number,
  idPrefix: string,
): { observations: Observation[]; stats: ValidationStats } {
  const dropped: string[] = []
  const out: Observation[] = []
  const v = profile.vision
  const r = raw as Partial<VisionResponse> | null
  if (!v || !r || typeof r !== 'object') return { observations: [], stats: { accepted: 0, dropped: ['response is not an object'] } }
  let n = 0
  const base = (kind: 'state' | 'event', eventType: string, confidence: number, evidence: string[]) => ({
    id: `${idPrefix}-${++n}`,
    timestampMs: frameAt,
    gameProfileId: profile.id,
    sourceRegion: v.crops?.length ? 'crops' : 'full-frame',
    kind,
    eventType,
    confidence: clamp01(confidence),
    freshnessMs: Math.max(0, arrivedAt - frameAt),
    evidence: ['vision-model', ...evidence],
    source: 'vision-model' as const,
  })

  if (typeof r.phase === 'string' && (PHASES as readonly string[]).includes(r.phase) && r.phase !== 'unknown') {
    out.push({ ...base('state', 'phase', num(r.phaseConfidence) ?? 0.5, []), value: r.phase })
  } else if (r.phase !== undefined && r.phase !== 'unknown') dropped.push(`phase "${String(r.phase)}"`)
  if (typeof r.visibleOutcome === 'string' && (OUTCOMES as readonly string[]).includes(r.visibleOutcome)) {
    out.push({ ...base('state', 'visibleOutcome', num(r.phaseConfidence) ?? 0.5, []), value: r.visibleOutcome })
  }
  const health = num(r.visibleHealth)
  if (health !== null && health >= 0 && health <= 1) out.push({ ...base('state', 'visibleHealth', 0.7, []), value: health })

  const specs = new Map(v.state.map((s) => [s.key, s]))
  for (const s of Array.isArray(r.state) ? r.state : []) {
    if (!s || typeof s !== 'object' || typeof s.key !== 'string') continue
    if ((STANDARD_STATE_KEYS as readonly string[]).includes(s.key)) continue
    const spec = specs.get(s.key)
    if (!spec) {
      dropped.push(`unknown state "${s.key}"`)
      continue
    }
    const value = parseValue(spec, s.value)
    if (value === null) {
      dropped.push(`${s.key}="${String(s.value)}" out of range`)
      continue
    }
    out.push({ ...base('state', s.key, num(s.confidence) ?? 0.5, []), value })
  }

  const eventSpecs = new Map(v.events.map((e) => [e.type, e]))
  const zones = new Map((profile.zones ?? []).map((z) => [z.id, new Set(z.cells.map((c) => c.id))]))
  for (const e of Array.isArray(r.events) ? r.events : []) {
    if (!e || typeof e !== 'object' || typeof e.type !== 'string') continue
    const spec = eventSpecs.get(e.type)
    if (!spec) {
      dropped.push(`unknown event "${e.type}"`)
      continue
    }
    const zone = typeof e.zone === 'string' && spec.zoneSet && zones.get(spec.zoneSet)?.has(e.zone) ? e.zone : undefined
    if (typeof e.zone === 'string' && !zone) dropped.push(`zone "${e.zone}" for ${e.type}`)
    const attributes: Record<string, string> = {}
    for (const a of Array.isArray(e.attributes) ? e.attributes : []) {
      const aspec = spec.attributes?.find((x) => x.key === a?.key)
      if (aspec && typeof a.value === 'string' && aspec.options.includes(a.value)) attributes[a.key] = a.value
      else if (a?.key) dropped.push(`attribute ${String(a.key)}="${String(a?.value)}"`)
    }
    const evidence = typeof e.evidence === 'string' ? [e.evidence.slice(0, 120)] : []
    out.push({
      ...base('event', e.type, num(e.confidence) ?? 0.5, evidence),
      ...(zone ? { zone } : {}),
      ...(Object.keys(attributes).length ? { attributes } : {}),
    })
  }
  return { observations: out, stats: { accepted: out.length, dropped } }
}

function parseValue(spec: VisionStateSpec, raw: unknown): string | number | boolean | null {
  const text = typeof raw === 'number' ? String(raw) : typeof raw === 'string' ? raw.trim() : null
  if (text === null || text === '') return null
  switch (spec.type) {
    case 'number': {
      const v = Number(text.replace(/[,\s%]/g, ''))
      if (!Number.isFinite(v)) return null
      if (spec.min !== undefined && v < spec.min) return null
      if (spec.max !== undefined && v > spec.max) return null
      return v
    }
    case 'boolean':
      return text === 'true' ? true : text === 'false' ? false : null
    case 'enum':
      return spec.options!.includes(text.toLowerCase()) ? text.toLowerCase() : null
    case 'string':
      return text.slice(0, spec.maxLength ?? 80)
  }
}

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

function clamp01(v: number): number {
  return Math.min(1, Math.max(0, v))
}
