// Live commentary: short spoken lines about what the coach just saw — a death
// and how it happened, a fight starting and how it ended, a goal, a scouted
// production building, a benchmark checkpoint. Lines are built only from the
// readings (never invented), kept to about 20 words, and say nothing the
// player's own screen did not show. Whether they are spoken is the
// governor's call (src/core/policy/governor.ts, decideCommentary).

import { fitWords, humanize, WORD_LIMITS } from './language.ts'
import type { ResolvedPolicy } from './policy/registry.ts'
import type { BenchmarkSpec, GameProfile } from './profile/schema.ts'
import type { CoachSettings } from './settings.ts'
import type { GameEvent } from './types.ts'

/** Phrases for an event's attributes, from the pack's factors (avoidable circumstances first). */
function phrases(profile: GameProfile, ev: GameEvent, max: number): string[] {
  const out: { text: string; avoidable: boolean }[] = []
  for (const f of profile.factors ?? []) {
    if (f.lossEvent !== ev.ruleId && f.winEvent !== ev.ruleId) continue
    if (!f.attribute) continue
    const raw = ev.attributes?.[f.attribute]
    const v = typeof raw === 'string' ? f.values.find((x) => x.value === raw) : undefined
    if (v) out.push({ text: v.phrase, avoidable: !!v.avoidable })
  }
  return out
    .sort((a, b) => Number(b.avoidable) - Number(a.avoidable))
    .slice(0, max)
    .map((p) => p.text)
}

function zoneLabel(profile: GameProfile, ev: GameEvent): string | null {
  if (!ev.zone) return null
  for (const z of profile.zones ?? []) {
    const cell = z.cells.find((c) => c.id === ev.zone)
    if (cell) return cell.label
  }
  return null
}

function finish(text: string): string {
  return fitCommentary(text)
}

/** Whole sentences up to the commentary limit; a single long first sentence is cut at the limit rather than dropped. */
export function fitCommentary(text: string): string {
  const sentences = text.split(/(?<=[.!?])\s+/)
  const fitted = fitWords(sentences, WORD_LIMITS.commentary)
  if (fitted) return fitted
  const words = sentences[0].split(/\s+/).slice(0, WORD_LIMITS.commentary)
  return `${words.join(' ').replace(/[,;:]$/, '')}.`
}

/** A reading as it is said: numbers with separators, "1:05" for a clock, enum values as words. */
export type ReadingLookup = (key: string) => string | number | boolean | null

/**
 * Fill "{key}" placeholders from the current readings ("{gameTimeSec|clock}" for a clock).
 * Null when a reading is missing, so a line never says a number the screen did not show.
 */
export function fillReadings(template: string, read: ReadingLookup): string | null {
  let missing = false
  const out = template.replace(/\{([a-zA-Z][a-zA-Z0-9_]*)(\|clock)?\}/g, (_m, key: string, clockFmt?: string) => {
    const v = read(key)
    if (v === null || v === '' || typeof v === 'boolean') {
      missing = true
      return ''
    }
    if (typeof v === 'number') return clockFmt ? gameClock(v) : Math.round(v).toLocaleString('en-GB')
    const words = humanize(v)
    return words[0].toUpperCase() + words.slice(1)
  })
  return missing ? null : out
}

function gameClock(sec: number): string {
  const s = Math.max(0, Math.round(sec))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** A line for an event the pack comments on, or null. */
export function eventLine(profile: GameProfile, ev: GameEvent, read: ReadingLookup = () => null): string | null {
  const template = profile.commentary?.[ev.ruleId]
  if (!template) return null
  // Readings in the line ("Housed at {population} of {populationCap}"): without them, the event's own name.
  const lead = template.includes('{') ? (fillReadings(template, read) ?? humanize(ev.label)) : template
  const parts = phrases(profile, ev, 2)
  const zone = zoneLabel(profile, ev)
  let line = lead
  if (parts.length) line += ` ${parts.join(', ')}`
  if (zone) line += `${parts.length ? ',' : ''} in ${zone}`
  // Fights: the army change is the clearest number.
  const army = /army (\d+) → (\d+)/.exec(ev.evidence)
  line += '.'
  if (army) line += ` Army ${army[1]} to ${army[2]}.`
  return finish(line)
}

/** "Fight starting near their defences, while outnumbered." from the first fight frame. */
export function fightStartLine(profile: GameProfile, attributes: Readonly<Record<string, string>>): string {
  const lost = profile.battles?.lostEvent
  const bits: string[] = []
  for (const key of ['location', 'numbers', 'matchup']) {
    const f = profile.factors?.find((x) => x.attribute === key && x.lossEvent === lost)
    const v = f?.values.find((x) => x.value === attributes[key])
    if (v) bits.push(v.phrase)
  }
  return finish(`Fight starting${bits.length ? ` ${bits.slice(0, 2).join(', ')}` : ''}.`)
}

/** "5:00 — 18 villagers; one Town Center that never stops has 21." */
export function checkpointLine(spec: BenchmarkSpec, atSec: number, actual: number, expected: number): string {
  const m = Math.floor(atSec / 60)
  const s = String(Math.round(atSec % 60)).padStart(2, '0')
  const gap = expected - actual
  const verdict = gap > 0 ? `${gap} behind ${spec.expectedPhrase}` : `on track with ${spec.expectedPhrase}`
  return finish(`${m}:${s}, ${actual} ${spec.noun}: ${verdict}.`)
}

/** A plain description when there is nothing better: "Under attack." */
export function fallbackLine(ev: GameEvent): string {
  return finish(`${humanize(ev.label)}.`)
}

/**
 * Said once when capture starts with commentary on: that it will talk, or why it will stay quiet
 * during play (so silence is never taken for a fault), and where it can talk instead. A status
 * line about the app, not coaching.
 */
export function commentaryStartLine(profile: GameProfile, settings: Pick<CoachSettings, 'mode' | 'profileModeId' | 'tournament'>, policy: ResolvedPolicy, liveModeLabels: readonly string[]): string {
  if (settings.tournament) return 'Commentary is on, but tournament mode keeps me silent.'
  if (policy.scope.has('live')) {
    if (settings.mode === 'silent') return 'Commentary is on, but silent tracker mode keeps me quiet. Change the coaching mode in Settings to hear it.'
    return 'Commentary on. I will call out what I see.'
  }
  const where = liveModeLabels.length ? ` I can talk during ${listOf(liveModeLabels.map(shortMode))}.` : ''
  const mode = profile.modes.find((m) => m.id === settings.profileModeId)
  if (!mode) return `Commentary is on, but the game mode is not set, so I stay quiet during play. Pick the mode on the Coach screen.${where}`
  return `Commentary is on, but in ${shortMode(mode.label)} I stay quiet until the match ends, then you get the review.${where}`
}

/** "Skirmish vs AI (single-player)" → "Skirmish vs AI". */
function shortMode(label: string): string {
  return label.replace(/\s*\([^)]*\)\s*$/, '')
}

function listOf(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

/**
 * A status line from the current readings, when commentary has been quiet for a while: the pack's
 * pieces in order, each kept only when every reading in it is on screen now. Null with too few.
 */
export function statusLine(parts: readonly string[], minParts: number, read: ReadingLookup): string | null {
  const kept = parts.map((p) => fillReadings(p, read)).filter((x): x is string => !!x)
  if (kept.length < minParts) return null
  return finish(`${kept.join(', ')}.`)
}
