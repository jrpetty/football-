// Synthetic vision replays: deterministic streams of the Observations a vision
// recogniser would produce for an EA SPORTS FC match or an Age of Empires II
// game, with known ground truth. They are fixtures, not recordings of real
// games: they let the fusion, analyses, policy and coaching be tested and
// demonstrated end to end without an API key or a game. The UI labels them as
// simulations.

import type { Observation } from '../core/types.ts'
import { mulberry32 } from './syntheticArena.ts'

export interface ObservationBatch {
  /** Capture time of the sampled frame. */
  readonly frameAt: number
  /** When the recogniser's answer arrives (frameAt + latency). */
  readonly arrivesAt: number
  readonly observations: Observation[]
}

export interface VisionReplay {
  readonly profileId: string
  readonly batches: ObservationBatch[]
  readonly truth: Record<string, unknown>
  readonly endsAt: number
}

type Value = string | number | boolean

class Builder {
  private seq = 0
  readonly batches: ObservationBatch[] = []
  private readonly profileId: string
  private readonly rnd: () => number

  constructor(profileId: string, rnd: () => number) {
    this.profileId = profileId
    this.rnd = rnd
  }

  batch(frameAt: number, state: Record<string, Value>, events: { type: string; zone?: string; attributes?: Record<string, Value>; evidence: string }[] = []): void {
    const latency = 1200 + Math.round(this.rnd() * 1500)
    const conf = () => 0.82 + this.rnd() * 0.15
    const observations: Observation[] = []
    for (const [key, value] of Object.entries(state)) {
      observations.push(this.obs(frameAt, latency, 'state', key, conf(), { value }))
    }
    for (const e of events) {
      observations.push(this.obs(frameAt, latency, 'event', e.type, conf(), { zone: e.zone, attributes: e.attributes, evidence: e.evidence }))
    }
    this.batches.push({ frameAt, arrivesAt: frameAt + latency, observations })
  }

  private obs(
    t: number,
    latency: number,
    kind: 'state' | 'event',
    eventType: string,
    confidence: number,
    extra: { value?: Value; zone?: string; attributes?: Record<string, Value>; evidence?: string },
  ): Observation {
    return {
      id: `sim-${++this.seq}`,
      timestampMs: t,
      gameProfileId: this.profileId,
      sourceRegion: 'full-frame',
      kind,
      eventType,
      ...(extra.value !== undefined ? { value: extra.value } : {}),
      ...(extra.zone ? { zone: extra.zone } : {}),
      ...(extra.attributes ? { attributes: extra.attributes } : {}),
      confidence,
      freshnessMs: latency,
      evidence: ['synthetic replay', ...(extra.evidence ? [extra.evidence] : [])],
      source: 'vision-model',
    }
  }
}

/**
 * An FC match, 90 match minutes compressed into `minutes` of real time.
 * The scripted player loses the ball most often on the left of their own
 * third, and concedes mostly from cutbacks into the left of the box.
 */
export function fcMatchReplay(opts: { seed: number; startAt?: number; intervalMs?: number; realMinutes?: number } = { seed: 1 }): VisionReplay {
  const rnd = mulberry32(opts.seed)
  const b = new Builder('ea-fc', rnd)
  const interval = opts.intervalMs ?? 2500
  const start = opts.startAt ?? 0
  const samples = Math.round(((opts.realMinutes ?? 6) * 60_000) / interval)
  const minuteAt = (i: number) => Math.min(90, Math.floor((i / samples) * 90))

  // Scripted moments: sample index → what happens.
  const conceded: { i: number; zone: string; buildUp: string }[] = [
    { i: Math.round(samples * 0.18), zone: 'box-left', buildUp: 'cutback' },
    { i: Math.round(samples * 0.47), zone: 'box-left', buildUp: 'cutback' },
    { i: Math.round(samples * 0.71), zone: 'outside-centre', buildUp: 'long_shot' },
    { i: Math.round(samples * 0.86), zone: 'box-left', buildUp: 'cross' },
  ]
  const scored: { i: number; zone: string; buildUp: string }[] = [
    { i: Math.round(samples * 0.33), zone: 'box-centre', buildUp: 'through_ball' },
    { i: Math.round(samples * 0.62), zone: 'box-right', buildUp: 'cross' },
  ]
  const halfTime = Math.round(samples * 0.5)
  const turnovers = new Map<number, string>()
  for (const g of conceded.slice(0, 3)) turnovers.set(g.i - 4, 'def-left') // lost the ball deep, conceded ~10 s later
  for (let i = 3; i < samples - 3; i++) {
    if (turnovers.has(i) || rnd() > 0.09) continue
    const r = rnd()
    turnovers.set(i, r < 0.35 ? 'def-left' : r < 0.5 ? 'mid-left' : r < 0.65 ? 'mid-centre' : r < 0.8 ? 'att-centre' : r < 0.9 ? 'att-right' : 'def-centre')
  }

  let home = 0
  let away = 0
  let misreadDone = false
  const goalEvents: { at: number; scorer: string; zone: string }[] = []
  for (let i = 0; i <= samples + 6; i++) {
    const t = start + i * interval
    if (i > samples) {
      // Full time: the statistics screen.
      b.batch(t, {
        phase: 'match_end',
        homeScore: home,
        awayScore: away,
        possessionPct: 46,
        shots: 9,
        shotsOnTarget: 4,
        passAccuracyPct: 74,
        expectedGoals: 1.4,
        visibleOutcome: home > away ? 'success' : home < away ? 'failure' : 'unknown',
      })
      continue
    }
    const inBreak = i >= halfTime && i < halfTime + 4
    const events: Parameters<Builder['batch']>[2] = []
    const goalIn = conceded.find((g) => g.i === i)
    const goalFor = scored.find((g) => g.i === i)
    if (goalIn) away++
    if (goalFor) home++
    // The replay is seen a few seconds after the scoreboard changes.
    const replayIn = conceded.find((g) => g.i + 2 === i)
    const replayFor = scored.find((g) => g.i + 2 === i)
    if (replayIn) {
      events.push({ type: 'goal', zone: replayIn.zone, attributes: { scorer: 'opponent', buildUp: replayIn.buildUp }, evidence: 'goal replay on screen' })
      goalEvents.push({ at: t, scorer: 'opponent', zone: replayIn.zone })
    }
    if (replayFor) {
      events.push({ type: 'goal', zone: replayFor.zone, attributes: { scorer: 'player', buildUp: replayFor.buildUp }, evidence: 'goal replay on screen' })
      goalEvents.push({ at: t, scorer: 'player', zone: replayFor.zone })
    }
    const lost = turnovers.get(i)
    if (lost && !inBreak) events.push({ type: 'turnover', zone: lost, attributes: { cause: rnd() < 0.6 ? 'misplaced_pass' : 'intercepted' }, evidence: 'ball lost to an opponent' })
    // One deliberate misread of the scoreboard (an 8 for a 0) that must not become a goal.
    const misread = !misreadDone && i === Math.round(samples * 0.1)
    if (misread) misreadDone = true
    b.batch(t, { phase: inBreak ? 'round_end' : 'round', homeScore: misread ? 8 : home, awayScore: away, matchMinute: minuteAt(i) }, events)
  }
  return {
    profileId: 'ea-fc',
    batches: b.batches,
    truth: { conceded: conceded.length, scored: scored.length, finalScore: { home, away }, concededZones: conceded.map((g) => g.zone), turnovers: turnovers.size, goalEvents },
    endsAt: start + (samples + 7) * interval,
  }
}

/**
 * An Age of Empires II game, ~26 game minutes compressed into `realMinutes`.
 * The scripted player gets housed twice, leaves villagers idle once, floats a
 * big bank once, and reaches Feudal late (12:30 vs an 11:00 target).
 */
export function aoe2GameReplay(opts: { seed: number; startAt?: number; intervalMs?: number; realMinutes?: number } = { seed: 1 }): VisionReplay {
  const rnd = mulberry32(opts.seed)
  const b = new Builder('aoe2-de', rnd)
  const interval = opts.intervalMs ?? 4000
  const start = opts.startAt ?? 0
  const samples = Math.round(((opts.realMinutes ?? 7) * 60_000) / interval)
  const gameSecAt = (i: number) => Math.round((i / samples) * 1560)
  const housedAt = [Math.round(samples * 0.15), Math.round(samples * 0.55)]
  const idleAt = Math.round(samples * 0.3)
  const floatAt = Math.round(samples * 0.7)

  for (let i = 0; i <= samples + 4; i++) {
    const t = start + i * interval
    if (i > samples) {
      b.batch(t, { phase: 'match_end', visibleOutcome: 'success' })
      continue
    }
    const gameSec = gameSecAt(i)
    const pop = Math.min(120, 4 + Math.round(i * (116 / samples)))
    const housed = housedAt.some((h) => i >= h && i < h + 4) // 4 samples = 16 s at the cap
    const cap = housed ? pop : Math.min(200, pop + 5 + Math.round(rnd() * 5))
    const idle = i >= idleAt && i < idleAt + 7 ? 3 : 0 // 28 s idle
    const floating = i >= floatAt && i < floatAt + 18 // 72 s banked
    const age = gameSec < 750 ? 'dark' : gameSec < 1200 ? 'feudal' : 'castle'
    b.batch(t, {
      phase: 'round',
      food: floating ? 900 : 150 + Math.round(rnd() * 150),
      wood: floating ? 700 : 100 + Math.round(rnd() * 150),
      gold: floating ? 300 : 50 + Math.round(rnd() * 100),
      stone: 100,
      population: pop,
      populationCap: cap,
      idleVillagers: idle,
      age,
      gameTimeSec: gameSec,
    })
  }
  return {
    profileId: 'aoe2-de',
    batches: b.batches,
    truth: { housed: 2, idle: 1, floating: 1, lateFeudal: true, lateCastle: false },
    endsAt: start + (samples + 5) * interval,
  }
}
