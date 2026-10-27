// The outcome evaluator. An episode opens on a profile-defined trigger event
// ("health got low"), records the first visible player response before the
// outcome ("patched up", or nothing), and closes on the first visible outcome
// ("eliminated") or when its window expires (neutral).
//
// Episodes that could not be observed properly are kept for the timeline but
// marked excluded, so they never count as evidence: ones cut short by a round
// ending, ones where the HUD was unreadable for much of the window, and ones
// still open when the session stops.

import type { EpisodeSpec } from './profile/schema.ts'
import { NO_RESPONSE, type Episode, type EpisodeExclusion, type GameEvent, type SignalState } from './types.ts'

/** Excluded when the relevant HUD was unreadable for more than this share of the episode. */
const MAX_UNREADABLE_SHARE = 0.4

interface OpenEpisode {
  readonly spec: EpisodeSpec
  readonly trigger: GameEvent
  response: string | null
  responseConfidence: number
  unreadableMs: number
  lastT: number
}

export class EpisodeEvaluator {
  private readonly specs: readonly EpisodeSpec[]
  private open: OpenEpisode[] = []
  private seq = 0

  constructor(specs: readonly EpisodeSpec[]) {
    this.specs = specs
  }

  /** Feed the events detected at time `t` (in order) plus the current signal states. */
  update(t: number, events: readonly GameEvent[], signals: ReadonlyMap<string, SignalState>): Episode[] {
    const closed: Episode[] = []

    for (const ep of this.open) {
      const dt = Math.max(0, t - ep.lastT)
      if (ep.spec.visibility.some((id) => !signals.get(id)?.known)) ep.unreadableMs += dt
      ep.lastT = t
    }

    // Outcomes first, then responses, then boundaries: an elimination and the
    // round-end banner it causes often land on the same frame.
    const ordered = [...events].sort((a, b) => rank(a) - rank(b))
    for (const ev of ordered) {
      for (const ep of [...this.open]) {
        if (ev.at < ep.trigger.at || ev.uid === ep.trigger.uid) continue
        if (ep.spec.negativeOutcomes.includes(ev.ruleId)) closed.push(this.close(ep, t, 'negative', ev, null))
        else if (ep.spec.positiveOutcomes?.includes(ev.ruleId)) closed.push(this.close(ep, t, 'positive', ev, null))
        else if (ev.boundary) closed.push(this.close(ep, t, 'neutral', null, 'truncated-by-boundary'))
        else if (ep.response === null) {
          const r = ep.spec.responses.find((x) => x.event === ev.ruleId)
          if (r) {
            ep.response = r.id
            ep.responseConfidence = ev.confidence
          }
        }
      }
    }

    for (const ep of [...this.open]) {
      if (t - ep.trigger.at >= ep.spec.windowMs) closed.push(this.close(ep, t, 'neutral', null, null))
    }

    // Open new episodes. One open episode per spec at a time: a second
    // "health low" while the first is still being observed is the same situation.
    for (const ev of events) {
      for (const spec of this.specs) {
        if (spec.trigger !== ev.ruleId) continue
        if (this.open.some((o) => o.spec.id === spec.id)) continue
        this.open.push({ spec, trigger: ev, response: null, responseConfidence: 1, unreadableMs: 0, lastT: t })
      }
    }
    return closed
  }

  /** Close everything still open, e.g. when capture stops or the user ends the round by hand. */
  closeAll(t: number, reason: EpisodeExclusion): Episode[] {
    return [...this.open].map((ep) => this.close(ep, t, 'neutral', null, reason))
  }

  get openCount(): number {
    return this.open.length
  }

  private close(
    ep: OpenEpisode,
    t: number,
    outcome: Episode['outcome'],
    outcomeEvent: GameEvent | null,
    exclusion: EpisodeExclusion | null,
  ): Episode {
    this.open = this.open.filter((o) => o !== ep)
    const duration = Math.max(1, t - ep.trigger.at)
    const unreadable = ep.unreadableMs / duration > MAX_UNREADABLE_SHARE
    const confidence = Math.min(
      ep.trigger.confidence,
      ep.response ? ep.responseConfidence : 1,
      outcomeEvent ? outcomeEvent.confidence : 1,
    )
    return {
      uid: `${ep.spec.id}#${++this.seq}`,
      episodeId: ep.spec.id,
      triggerUid: ep.trigger.uid,
      startedAt: ep.trigger.at,
      endedAt: t,
      response: ep.response ?? NO_RESPONSE,
      outcome,
      outcomeEventUid: outcomeEvent?.uid ?? null,
      confidence,
      excluded: exclusion ?? (unreadable ? 'low-visibility' : null),
    }
  }
}

function rank(e: GameEvent): number {
  if (e.category === 'outcome') return 0
  if (e.category === 'boundary') return 2
  return 1
}
