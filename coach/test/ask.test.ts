// "Ask the coach": local answers from structured data, and the digest sent to the model.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { answerLocally, buildDigest, classify } from '../src/core/qa/ask.ts'
import { storedInsights, toPatternRecords } from '../src/core/memory/records.ts'
import { setVerdict } from '../src/core/patterns.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe2GameReplay, fcMatchReplay } from '../src/sim/visionReplay.ts'
import { arena, engineFor, settings } from './helpers.ts'
import { simulateFrames } from '../src/sim/syntheticArena.ts'

const fc = builtInProfile('ea-fc')
const aoe = builtInProfile('aoe2-de')

function fcReport() {
  const engine = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'kickoff-vs-cpu', mode: 'silent' }))
  let now = 0
  for (const b of fcMatchReplay({ seed: 3 }).batches) engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt)))
  return engine.report()
}

test('questions are routed to the right local answer', () => {
  assert.deepEqual(classify('Where do I concede the most?', fc), { kind: 'heatmap', heatmapId: 'conceded' })
  assert.deepEqual(classify('where am I losing the ball', fc), { kind: 'heatmap', heatmapId: 'turnovers' })
  assert.deepEqual(classify('Which passes are unsuccessful?', fc), { kind: 'heatmap', heatmapId: 'turnovers' })
  assert.deepEqual(classify('where do I die most', builtInProfile('generic')), { kind: 'heatmap', heatmapId: 'deaths' })
  assert.deepEqual(classify('what mistakes am I making', aoe), { kind: 'mistakes' })
  assert.deepEqual(classify('how is my pass accuracy trending', fc), { kind: 'stats' })
  assert.deepEqual(classify('should I play a 4-3-3 against this team?', fc), { kind: 'open' })
})

test('local answers quote the real counts and never claim causes', () => {
  const report = fcReport()
  const concede = answerLocally({ kind: 'heatmap', heatmapId: 'conceded' }, fc, report, [])!
  assert.match(concede, /Of 4 goals you conceded I saw, 4 could be placed\. Most came from inside the box, left side: 3\./)
  assert.match(concede, /mostly cutback/)
  assert.match(concede, /Zones are approximate/)
  const turnovers = answerLocally({ kind: 'heatmap', heatmapId: 'turnovers' }, fc, report, [])!
  assert.match(turnovers, /sampled frames/)
  for (const a of [concede, turnovers, answerLocally({ kind: 'summary' }, fc, report, [])!]) assert.doesNotMatch(a, BANNED_CLAIMS)
  assert.equal(answerLocally({ kind: 'open' }, fc, report, []), null)
})

test('AoE mistakes are answered from visible numbers', () => {
  const engine = engineFor(aoe, settings({ profileId: 'aoe2-de', profileModeId: 'skirmish-vs-ai', mode: 'silent' }))
  let now = 0
  for (const b of aoe2GameReplay({ seed: 5 }).batches) engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt)))
  const a = answerLocally({ kind: 'mistakes' }, aoe, engine.report(), [])!
  assert.match(a, /Housed twice/)
  assert.match(a, /Villagers sat idle once/)
})

test('the digest is compact, structured, and carries limits, notes and dated research', () => {
  const digest = buildDigest({
    profile: fc, modeLabel: 'Kick-Off vs CPU', policyLine: 'live tips in offline single-player only', report: fcReport(), history: [],
    notes: { goals: 'stop conceding cutbacks', notes: 'I play 4-2-3-1' },
    research: { text: 'Fundamentals…', createdAt: '2026-10-27T00:00:00Z', sources: [{ url: 'https://example.org', title: 'Guide' }] },
  })
  assert.ok(digest.length < 20_000)
  const d = JSON.parse(digest)
  assert.equal(d.session.zones[0].byZone['inside the box, left side'], 3)
  assert.equal(d.playerNotes.goals, 'stop conceding cutbacks')
  assert.equal(d.research.asOf, '2026-10-27T00:00:00Z')
  assert.ok(d.pack.limitations.length > 0)
})

test('pattern records carry supporting and counter instances', () => {
  const engine = engineFor(arena, settings({ mode: 'silent' }))
  for (const f of simulateFrames({ rounds: 12, seed: 7, fps: 5 })) engine.processFrame(f)
  const [rec] = toPatternRecords(arena, engine.insights(), null, 'ranked', new Date('2026-10-28'))
  assert.equal(rec.id, 'low-health')
  assert.deepEqual(rec.visibleSequence, ['Health got low', 'kept fighting', 'eliminated'])
  assert.ok(rec.supportingInstances >= 3 && rec.counterInstances >= 1)
  assert.equal(rec.status, 'active')
})

test('an answer during restricted play is held, then delivered when the match ends', () => {
  const engine = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'division-rivals', mode: 'training' }))
  const replay = fcMatchReplay({ seed: 3 })
  let now = 0
  const half = Math.floor(replay.batches.length / 2)
  replay.batches.slice(0, half).forEach((b) => engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt))))
  const asked = engine.answer('where do I concede', 'Mostly inside the box, left side.', 'template', now)
  const first = asked.entries.find((e) => e.type === 'message')!
  assert.ok(first.type === 'message' && first.decision.decision === 'queued')
  assert.equal(asked.utterances.length, 0)
  const spoken: string[] = []
  for (const b of replay.batches.slice(half)) spoken.push(...engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt))).utterances.map((u) => u.text))
  assert.ok(spoken.some((t) => t.startsWith('Earlier you asked: Mostly inside the box')), spoken.join(' | '))

  const practice = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'kickoff-vs-cpu', mode: 'training' }))
  assert.equal(practice.answer('where do I concede', 'Here is the answer.', 'template', 1000).utterances.length, 1, 'answered at once where live coaching is allowed')
})

test('a single located event is reported as one, not as a pattern', () => {
  const engine = engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'kickoff-vs-cpu', mode: 'silent' }))
  let now = 0
  // Stop the replay shortly after the first conceded goal and its replay.
  for (const b of fcMatchReplay({ seed: 3 }).batches.filter((b) => b.frameAt < 75_000)) engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt)))
  const answer = answerLocally({ kind: 'heatmap', heatmapId: 'conceded' }, fc, engine.report(), [])!
  assert.equal(answer, 'Where you concede from: so far just one I could place, inside the box, left side. One is not a pattern yet.')
})

test('stored patterns can be reviewed between sessions, with the same grading and the player\'s verdict', () => {
  const engine = engineFor(arena, settings({ mode: 'silent' }))
  for (const f of simulateFrames({ rounds: 12, seed: 7, fps: 5 })) engine.processFrame(f)
  const now = new Date('2026-10-28T12:00:00Z')
  const snapshot = engine.snapshot(now)
  const live = engine.insights().map((i) => [i.insight.episodeId, i.insight.grade])
  const stored = storedInsights(arena, snapshot, 'ranked', now).map((i) => [i.insight.episodeId, i.insight.grade])
  assert.deepEqual(stored, live)
  assert.deepEqual(storedInsights(arena, snapshot, 'practice', now), [], 'other modes are separate segments')
  const disputed = storedInsights(arena, setVerdict(snapshot, 'low-health', 'disputed', now), 'ranked', now)
  assert.equal(disputed.find((i) => i.insight.episodeId === 'low-health')?.verdict, 'disputed')
})
