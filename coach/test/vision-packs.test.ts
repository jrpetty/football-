// The vision path end to end: synthetic recogniser observations for EA SPORTS
// FC and Age of Empires II → fusion → analyses → policy → messages, checked
// against the replays' ground truth.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { CoachEngine } from '../src/core/engine.ts'
import type { MatchRecord } from '../src/core/analyses/matchRecorder.ts'
import type { Observation, TimelineEntry, Utterance } from '../src/core/types.ts'
import { VisibleStateFuser } from '../src/core/visibleState.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe2GameReplay, fcMatchReplay, type VisionReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'

const fc = builtInProfile('ea-fc')
const aoe = builtInProfile('aoe2-de')

function play(engine: CoachEngine, replay: VisionReplay) {
  const entries: TimelineEntry[] = [...engine.startNotices(0)]
  const utterances: Utterance[] = []
  const matches: MatchRecord[] = []
  let now = 0
  for (const b of replay.batches) {
    now = Math.max(now, b.arrivesAt) // one request in flight: answers arrive in order
    const out = engine.ingestObservations(b.observations, now)
    entries.push(...out.entries)
    utterances.push(...out.utterances)
    matches.push(...out.matches)
  }
  return { entries, utterances, matches }
}

const count = (entries: TimelineEntry[], id: string) => entries.filter((e) => e.type === 'event' && e.event.ruleId === id).length

// --- EA SPORTS FC ------------------------------------------------------------------------

test('FC: goals come from scoreboard changes, a misread is not a goal, and replays add zones', () => {
  const replay = fcMatchReplay({ seed: 3 })
  const engine = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'division-rivals', mode: 'post-match' }))
  const { entries, matches } = play(engine, replay)
  const truth = replay.truth as { conceded: number; scored: number; finalScore: { home: number; away: number } }

  assert.equal(count(entries, 'goal_conceded'), truth.conceded)
  assert.equal(count(entries, 'goal_scored'), truth.scored)
  assert.ok(entries.some((e) => e.type === 'notice' && /treated as a correction/.test(e.text)) || count(entries, 'goal_scored') === truth.scored)

  const conceded = engine.report().heatmaps.find((h) => h.spec.id === 'conceded')!
  assert.equal(conceded.total, truth.conceded)
  assert.equal(conceded.located, truth.conceded, 'every conceded goal got its zone from a replay')
  assert.equal(conceded.cells['box-left'], 3)
  assert.equal(conceded.claim?.cell, 'box-left')
  assert.equal(conceded.topBreakdown?.value, 'cutback')

  assert.equal(matches.length, 1)
  assert.deepEqual(matches[0].score, { player: truth.finalScore.home, opponent: truth.finalScore.away })
  assert.equal(matches[0].result, 'loss')
  assert.equal(matches[0].stats.passAccuracyPct, 74)
})

test('FC: two goals by the same side close together are both placed (banner dedupe resets on a score change)', () => {
  // A shorter replay squeezes goals closer together than the 40 s banner window.
  const replay = fcMatchReplay({ seed: 1, realMinutes: 4 })
  const engine = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'kickoff-vs-cpu', mode: 'silent' }))
  play(engine, replay)
  const conceded = engine.report().heatmaps.find((h) => h.spec.id === 'conceded')!
  assert.equal(conceded.total, 4)
  assert.equal(conceded.located, 4)
  assert.equal(conceded.cells['box-left'], 3)
})

test('FC: online modes never speak during play; the post-match summary names the hot spot', () => {
  const engine = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'division-rivals', mode: 'training' }))
  const { entries, utterances } = play(engine, fcMatchReplay({ seed: 3 }))
  assert.ok(entries.some((e) => e.type === 'notice' && /needs publisher review/.test(e.text)))
  assert.equal(utterances.length, 1)
  assert.equal(utterances[0].category, 'post_event')
  assert.match(utterances[0].text, /Final score 2–4, a loss\./)
  assert.match(utterances[0].text, /inside the box, left side/)
})

test('FC: losing the ball in your own third is compared with losing it further up', () => {
  const engine = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'kickoff-vs-cpu', mode: 'silent' }))
  const { entries } = play(engine, fcMatchReplay({ seed: 3 }))
  const eps = entries.flatMap((e) => (e.type === 'episode' && !e.episode.excluded ? [e.episode] : []))
  const own = eps.filter((e) => e.response === 'own-third')
  assert.ok(own.length >= 3)
  assert.ok(own.filter((e) => e.outcome === 'negative').length >= 3, 'the scripted deep turnovers led to goals')
  assert.ok(eps.some((e) => e.response === 'none'), 'turnovers further up are the comparison group')
})

test('FC: cross-match history turns statistics into trends', () => {
  const engine = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'kickoff-vs-cpu', mode: 'post-match', verbosity: 'detailed' }), {
    matchHistory: [80, 82, 84].map((p, i) => ({
      schema: 1, id: `m${i}`, gameProfileId: 'ea-fc', profileVersion: '1.0.0', modeId: 'kickoff-vs-cpu', endedAt: '2026-10-20T00:00:00Z',
      durationSec: 600, result: 'win', score: null, rounds: { won: 0, lost: 0, drawn: 0, unread: 0 },
      stats: { passAccuracyPct: p, shots: 9 }, mistakes: {}, zones: {}, endedManually: false,
    })),
  })
  const { utterances } = play(engine, fcMatchReplay({ seed: 3 }))
  assert.match(utterances[0].text, /Pass accuracy 74%, down from your recent 82%\./)
})

// --- Age of Empires II -------------------------------------------------------------------

test('AoE2: housed, idle villagers, floating resources and a late Feudal are counted from visible numbers', () => {
  const replay = aoe2GameReplay({ seed: 5 })
  const engine = engineFor(aoe, settings({ profileId: 'aoe2-de', profileModeId: 'ranked-1v1', mode: 'post-match' }))
  const { entries, matches } = play(engine, replay)
  assert.equal(count(entries, 'housed'), 2)
  assert.equal(count(entries, 'idle-villagers'), 1)
  assert.equal(count(entries, 'floating-resources'), 1)
  assert.equal(count(entries, 'late-feudal'), 1)
  assert.equal(count(entries, 'late-castle'), 0)
  const m = matches[0]
  assert.equal(m.mistakes.housed.count, 2)
  assert.ok(m.mistakes.housed.totalSec >= 20, `housed for ${m.mistakes.housed.totalSec}s`)
  assert.equal(m.result, 'win')
})

test('AoE2 vs AI in training mode: live tips for visible mistakes, paced by the cooldown', () => {
  const engine = engineFor(aoe, settings({ profileId: 'aoe2-de', profileModeId: 'skirmish-vs-ai', mode: 'training', cooldownSec: 20 }))
  const { utterances, entries } = play(engine, aoe2GameReplay({ seed: 5 }))
  const tips = utterances.filter((u) => u.category === 'training')
  assert.ok(tips.some((u) => u.text === "You're housed. Build a house."))
  assert.ok(tips.some((u) => u.text === 'Idle villagers. Put them to work.'))
  const spoken = entries.flatMap((e) => (e.type === 'message' && e.decision.decision === 'spoken' && e.message.category === 'training' ? [e] : []))
  for (let i = 1; i < spoken.length; i++) assert.ok(spoken[i].at - spoken[i - 1].at >= 20_000)
})

test('AoE2 ranked: the same mistakes are held for the post-match review', () => {
  const engine = engineFor(aoe, settings({ profileId: 'aoe2-de', profileModeId: 'ranked-1v1', mode: 'training' }))
  const { utterances, entries } = play(engine, aoe2GameReplay({ seed: 5 }))
  assert.ok(utterances.every((u) => u.category === 'post_event'))
  const tips = entries.flatMap((e) => (e.type === 'message' && e.message.category === 'training' ? [e] : []))
  assert.ok(tips.length >= 4 && tips.every((t) => t.decision.decision === 'queued'))
  assert.match(utterances[0].text, /Housed twice/)
})

test('AoE2: when the numbers stop being readable, no mistakes are inferred', () => {
  const replay = aoe2GameReplay({ seed: 5 })
  // Drop every state reading except the phase: the HUD "cannot be read".
  const blind = { ...replay, batches: replay.batches.map((b) => ({ ...b, observations: b.observations.filter((o) => o.eventType === 'phase') })) }
  const engine = engineFor(aoe, settings({ profileId: 'aoe2-de', profileModeId: 'skirmish-vs-ai', mode: 'training' }))
  const { entries } = play(engine, blind)
  assert.equal(entries.filter((e) => e.type === 'event' && e.event.category === 'mistake').length, 0)
})

// --- Fusion details ---------------------------------------------------------------------------

function obs(eventType: string, value: number | string, timestampMs: number, source: Observation['source'] = 'vision-model', confidence = 0.9): Observation {
  return { id: `${eventType}-${timestampMs}`, timestampMs, gameProfileId: 'x', sourceRegion: 'full-frame', kind: 'state', eventType, value, confidence, freshnessMs: 0, evidence: [], source }
}

test('fusion: values expire, late arrivals do not overwrite, and disagreeing sources are flagged', () => {
  const f = new VisibleStateFuser(() => 5000)
  f.ingest([obs('population', 20, 1000)])
  f.ingest([obs('population', 18, 500)]) // older reading arriving late
  assert.equal(f.snapshot(2000).values.population.value, 20)
  assert.equal(f.snapshot(7000).values.population, undefined, 'expired values are absent, not zero')
  assert.equal(f.snapshot(7000).stale, true)

  f.ingest([obs('health', 0.5, 10_000, 'pixel'), obs('health', 0.9, 10_500, 'vision-model')])
  const s = f.snapshot(11_000)
  assert.equal(s.values.health.conflicted, true)
  assert.ok(s.values.health.confidence < 0.5)
  assert.match(s.staleReasons.join(' '), /disagree about health/)

  f.setStale('capture', 'Capture paused')
  assert.equal(f.snapshot(11_000).stale, true)
  assert.equal(f.get('health', 11_000), null, 'nothing is readable while the capture is untrusted')
})

test('packs are suggested from the window title, and only suggested', async () => {
  const { suggestProfile } = await import('../src/core/profile/schema.ts')
  const { BUILT_IN_PROFILES } = await import('../src/profiles/index.ts')
  assert.equal(suggestProfile(BUILT_IN_PROFILES, 'EA SPORTS FC™ 26')?.id, 'ea-fc')
  assert.equal(suggestProfile(BUILT_IN_PROFILES, 'Age of Empires II: Definitive Edition')?.id, 'aoe2-de')
  assert.equal(suggestProfile(BUILT_IN_PROFILES, 'Untitled - Notepad'), null)
})
