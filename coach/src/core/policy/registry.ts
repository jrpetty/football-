// The publisher-policy registry. It answers one question: in this game and
// mode, *when* may the coach say anything? It fails closed: an unknown game,
// an unknown mode, a stale review, or the tournament switch all remove live
// output. Screen capture working is never treated as permission.

import type { PolicyStatus } from '../types.ts'

export type PolicyScope = 'live' | 'round_break' | 'post_match'

export interface PolicySource {
  readonly url: string
  readonly title: string
  readonly excerpt: string
  /** How the source was checked: read directly, seen as a search excerpt, or internal reasoning. */
  readonly verification: 'direct' | 'search-excerpt'
}

export interface PolicyEntry {
  readonly id: string
  readonly publisher: string
  readonly game: string
  /** Game ids this entry covers; "*" means any game (used with modeClass). */
  readonly gameIds: readonly string[]
  /** Mode ids; "*" means every mode of the game. */
  readonly modes: readonly string[]
  /** Matches by declared mode facts instead of mode id. */
  readonly modeClass?: 'offline-single-player'
  readonly status: PolicyStatus
  /** For approved_for_scope only: what is approved. */
  readonly scope?: readonly PolicyScope[]
  /** "publisher": based on published publisher material. "internal": Sightline's own judgement. */
  readonly basis: 'publisher' | 'internal'
  readonly sources: readonly PolicySource[]
  readonly rationale?: string
  readonly reviewedAt: string
  readonly reviewer: string
  readonly notes: string
}

export interface PolicyRegistry {
  readonly schemaVersion: 1
  readonly reviewMaxAgeDays: number
  readonly note: string
  readonly entries: readonly PolicyEntry[]
}

export interface ModeFacts {
  readonly multiplayer: boolean
  readonly competitive: boolean
}

export interface ResolvedPolicy {
  /** The status as recorded. */
  readonly recordedStatus: PolicyStatus | null
  /** The status actually applied after staleness, unknown-mode and tournament rules. */
  readonly status: PolicyStatus
  readonly scope: ReadonlySet<PolicyScope>
  readonly entry: PolicyEntry | null
  /** Plain-language explanation, first item is the deciding reason. */
  readonly reasons: readonly string[]
}

const STATUSES: readonly PolicyStatus[] = [
  'approved_for_scope',
  'post_match_only',
  'noncompetitive_only',
  'needs_publisher_review',
  'disabled',
]

export function validateRegistry(input: unknown): { ok: true; registry: PolicyRegistry } | { ok: false; errors: string[] } {
  const errors: string[] = []
  const o = input as Partial<PolicyRegistry> | null
  if (!o || typeof o !== 'object') return { ok: false, errors: ['registry: expected an object'] }
  if (o.schemaVersion !== 1) errors.push('schemaVersion: expected 1')
  if (typeof o.reviewMaxAgeDays !== 'number' || o.reviewMaxAgeDays < 1) errors.push('reviewMaxAgeDays: expected a positive number')
  if (!Array.isArray(o.entries)) return { ok: false, errors: [...errors, 'entries: expected an array'] }
  const ids = new Set<string>()
  o.entries.forEach((e, i) => {
    const p = `entries[${i}]`
    if (!e || typeof e !== 'object') return errors.push(`${p}: expected an object`)
    if (typeof e.id !== 'string' || !e.id) errors.push(`${p}.id: required`)
    else if (ids.has(e.id)) errors.push(`${p}.id: duplicate "${e.id}"`)
    else ids.add(e.id)
    if (!STATUSES.includes(e.status)) errors.push(`${p}.status: invalid`)
    if (!Array.isArray(e.gameIds) || e.gameIds.length === 0) errors.push(`${p}.gameIds: required`)
    if (!Array.isArray(e.modes)) errors.push(`${p}.modes: required (may be empty with modeClass)`)
    if (Array.isArray(e.modes) && e.modes.length === 0 && !e.modeClass) errors.push(`${p}: needs modes or a modeClass`)
    if (Number.isNaN(Date.parse(e.reviewedAt))) errors.push(`${p}.reviewedAt: expected a date`)
    if (typeof e.reviewer !== 'string' || !e.reviewer) errors.push(`${p}.reviewer: required`)
    if (e.basis !== 'publisher' && e.basis !== 'internal') errors.push(`${p}.basis: "publisher" or "internal"`)
    if (e.basis === 'publisher' && (!Array.isArray(e.sources) || e.sources.length === 0)) {
      errors.push(`${p}: a publisher-based entry must cite at least one source`)
    }
    if (e.basis === 'internal' && !e.rationale) errors.push(`${p}: an internal decision must state its rationale`)
    if (e.status === 'approved_for_scope') {
      if (!Array.isArray(e.scope) || e.scope.length === 0) errors.push(`${p}.scope: approved_for_scope must say what is approved`)
      // Live approval for a real publisher's game must rest on that publisher's material.
      if (e.scope?.includes('live') && e.basis === 'internal' && !e.publisher.includes('fictional')) {
        errors.push(`${p}: live approval for a real game cannot be an internal decision`)
      }
    }
    for (const [j, s] of (e.sources ?? []).entries()) {
      if (!/^https:\/\//.test(s.url)) errors.push(`${p}.sources[${j}].url: expected an https URL`)
      if (!s.excerpt) errors.push(`${p}.sources[${j}].excerpt: required`)
    }
  })
  return errors.length ? { ok: false, errors } : { ok: true, registry: o as PolicyRegistry }
}

const ALL: ReadonlySet<PolicyScope> = new Set(['live', 'round_break', 'post_match'])
const POST_ONLY: ReadonlySet<PolicyScope> = new Set(['post_match'])
const NONE: ReadonlySet<PolicyScope> = new Set()

export function isOfflineSinglePlayer(facts: ModeFacts | null): boolean {
  return !!facts && !facts.multiplayer && !facts.competitive
}

/**
 * Resolve the policy for a game and mode. Precedence: exact mode of the game,
 * then a mode class (e.g. offline single-player) for the game, then the
 * game's "*" entry, then a cross-game mode-class rule. No match fails closed.
 */
export function resolvePolicy(
  registry: PolicyRegistry,
  q: { gameId: string | null; modeId: string | null; modeFacts: ModeFacts | null; tournament: boolean; now: Date },
): ResolvedPolicy {
  if (q.tournament) {
    return {
      recordedStatus: null,
      status: 'disabled',
      scope: NONE,
      entry: null,
      reasons: [
        'Tournament / event play is switched on: all coaching output is off. Event rules can be stricter than publisher rules, and some forbid coaching software entirely.',
      ],
    }
  }
  const forGame = registry.entries.filter((e) => q.gameId !== null && e.gameIds.includes(q.gameId))
  const classMatch = (e: PolicyEntry) => e.modeClass === 'offline-single-player' && isOfflineSinglePlayer(q.modeFacts)
  const entry =
    forGame.find((e) => q.modeId !== null && e.modes.includes(q.modeId)) ??
    forGame.find(classMatch) ??
    forGame.find((e) => e.modes.includes('*')) ??
    registry.entries.find((e) => e.gameIds.includes('*') && classMatch(e)) ??
    null

  if (!entry) {
    return {
      recordedStatus: null,
      status: 'needs_publisher_review',
      scope: POST_ONLY,
      entry: null,
      reasons: [
        q.gameId
          ? `No reviewed policy for this game and mode, so live coaching is off (fail closed). Post-match review only.`
          : 'No game selected, so the mode is treated as competitive: post-match review only.',
      ],
    }
  }

  const ageDays = (q.now.getTime() - Date.parse(entry.reviewedAt)) / 86_400_000
  if (ageDays > registry.reviewMaxAgeDays) {
    return {
      recordedStatus: entry.status,
      status: 'needs_publisher_review',
      scope: POST_ONLY,
      entry,
      reasons: [
        `The policy record for ${entry.game} was last reviewed ${Math.floor(ageDays)} days ago (limit ${registry.reviewMaxAgeDays}). Until it is re-reviewed, live coaching is off.`,
      ],
    }
  }

  const label = `${entry.game}: ${statusLabel(entry.status)}${entry.basis === 'internal' ? ' (internal policy, not publisher approval)' : ''}`
  switch (entry.status) {
    case 'approved_for_scope':
      return { recordedStatus: entry.status, status: entry.status, scope: new Set(entry.scope ?? []), entry, reasons: [label] }
    case 'noncompetitive_only': {
      const offline = isOfflineSinglePlayer(q.modeFacts)
      return {
        recordedStatus: entry.status,
        status: entry.status,
        scope: offline ? ALL : POST_ONLY,
        entry,
        reasons: offline
          ? [label]
          : [`${label}. This mode is not declared offline single-player, so only post-match review is allowed.`],
      }
    }
    case 'post_match_only':
    case 'needs_publisher_review':
      return { recordedStatus: entry.status, status: entry.status, scope: POST_ONLY, entry, reasons: [label] }
    case 'disabled':
      return { recordedStatus: entry.status, status: entry.status, scope: NONE, entry, reasons: [label] }
  }
}

export function statusLabel(s: PolicyStatus): string {
  switch (s) {
    case 'approved_for_scope':
      return 'approved for a stated scope'
    case 'noncompetitive_only':
      return 'live tips in offline single-player only'
    case 'post_match_only':
      return 'post-match review only'
    case 'needs_publisher_review':
      return 'needs publisher review (post-match only until then)'
    case 'disabled':
      return 'coaching disabled'
  }
}
