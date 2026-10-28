// Live commentary: short spoken lines about what the coach just saw — a death
// and how it happened, a fight starting and how it ended, a goal, a scouted
// production building, a benchmark checkpoint. Lines are built only from the
// readings (never invented), kept to about 20 words, and say nothing the
// player's own screen did not show. Whether they are spoken is the
// governor's call (src/core/policy/governor.ts, decideCommentary).

import { fitWords, humanize, WORD_LIMITS } from './language.ts'
import type { BenchmarkSpec, GameProfile } from './profile/schema.ts'
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
  return fitWords([text], WORD_LIMITS.commentary)
}

/** A line for an event the pack comments on, or null. */
export function eventLine(profile: GameProfile, ev: GameEvent): string | null {
  const lead = profile.commentary?.[ev.ruleId]
  if (!lead) return null
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
