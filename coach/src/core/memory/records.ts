// PatternRecords: how patterns are shown to the player, with their evidence,
// counterexamples and the player's own verdict. Built from the engine's
// insights (session + opt-in history) and the stored snapshot.

import type { InsightView } from '../engine.ts'
import { responseLabel } from '../language.ts'
import type { PatternSnapshot } from '../patterns.ts'
import type { GameProfile } from '../profile/schema.ts'
import type { PatternRecord } from '../types.ts'

export function toPatternRecords(
  profile: GameProfile,
  insights: readonly InsightView[],
  snapshot: PatternSnapshot | null,
  mode: string | null,
  now: Date,
): PatternRecord[] {
  return insights.map((view) => {
    const i = view.insight
    const spec = profile.episodes.find((e) => e.id === i.episodeId)!
    const trigger = profile.events.find((e) => e.id === spec.trigger)?.label ?? spec.trigger
    const stored = snapshot?.segments[mode ?? 'unspecified']?.[i.episodeId]
    const last = stored ? Object.values(stored).map((c) => c.lastObservedAt).sort().at(-1) : undefined
    return {
      id: i.episodeId,
      gameProfileId: profile.id,
      mode: mode ?? 'unspecified',
      contextSignature: `${profile.id}:${mode ?? 'unspecified'}:${i.episodeId}`,
      visibleSequence: [trigger, responseLabel(spec, i.worse.response), spec.phrases.negativeShort],
      observedOutcome: 'failure',
      // Supporting: the worse response was followed by the bad outcome. Counter: it was not.
      supportingInstances: i.worse.counts.negative,
      counterInstances: i.worse.counts.n - i.worse.counts.negative,
      confidence: i.probability,
      grade: i.grade,
      lastObservedAt: last ?? now.toISOString(),
      explanation: view.statement,
      status: view.verdict ?? 'active',
    }
  })
}
