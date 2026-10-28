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

type Tags = Record<string, string>

/**
 * A Call of Duty: Modern Warfare 4 Hardpoint match (~10 minutes compressed
 * into `realMinutes`). Every death and kill carries the fight details a
 * vision model would read from the killcam or the player's own view. The
 * scripted player dies in the open a lot, loses long-range fights, wins close
 * ones, and five times dies within seconds of their own kill.
 */
export function mw4MatchReplay(opts: { seed: number; startAt?: number; intervalMs?: number; realMinutes?: number } = { seed: 1 }): VisionReplay {
  const rnd = mulberry32(opts.seed)
  const b = new Builder('cod-mw4', rnd)
  const interval = opts.intervalMs ?? 2500
  const start = opts.startAt ?? 0
  const samples = Math.round(((opts.realMinutes ?? 6) * 60_000) / interval)

  const deaths: Tags[] = [
    { exposure: 'in_open', range: 'long', movement: 'walking', support: 'alone', engagement: 'front', killedBy: 'assault_rifle' },
    { exposure: 'in_open', range: 'long', movement: 'sprinting', support: 'alone', engagement: 'side', killedBy: 'sniper' },
    { exposure: 'partial_cover', range: 'mid', movement: 'aiming_still', support: 'teammate_near', engagement: 'front', killedBy: 'assault_rifle' },
    { exposure: 'in_open', range: 'long', movement: 'sprinting', support: 'alone', engagement: 'front', killedBy: 'assault_rifle' },
    { exposure: 'in_open', range: 'mid', movement: 'walking', support: 'unknown', engagement: 'behind', killedBy: 'smg', action: 'reloading' },
    { exposure: 'behind_cover', range: 'close', movement: 'aiming_still', support: 'teammate_near', engagement: 'front', killedBy: 'shotgun' },
    { exposure: 'in_open', range: 'long', movement: 'sprinting', support: 'alone', engagement: 'front', killedBy: 'marksman' },
    { exposure: 'partial_cover', range: 'mid', movement: 'unknown', support: 'alone', engagement: 'front', killedBy: 'assault_rifle' },
    { exposure: 'in_open', range: 'long', movement: 'walking', support: 'alone', engagement: 'side', killedBy: 'assault_rifle' },
    { exposure: 'unknown', range: 'unknown', movement: 'unknown', support: 'unknown', engagement: 'unknown', killedBy: 'unknown' },
    { exposure: 'in_open', range: 'long', movement: 'sprinting', support: 'alone', engagement: 'front', killedBy: 'sniper' },
    { exposure: 'partial_cover', range: 'mid', movement: 'aiming_still', support: 'teammate_near', engagement: 'front', killedBy: 'lmg' },
    { exposure: 'in_open', range: 'close', movement: 'walking', support: 'teammate_near', engagement: 'front', killedBy: 'smg' },
  ]
  const kills: Tags[] = [
    ...Array.from({ length: 8 }, () => ({ range: 'close', movement: 'walking' })),
    ...Array.from({ length: 5 }, () => ({ range: 'mid', movement: 'aiming_still' })),
    { range: 'long', movement: 'aiming_still' },
  ]
  // Sample index → fight. Deaths at 5 s after a kill: indices k and k + 2.
  const fights = new Map<number, { type: 'death' | 'kill'; tags: Tags }>()
  let k = 0
  let d = 0
  const afterKill = new Set([1, 3, 6, 8, 11, 12]) // deaths that come right after a kill
  for (let i = 4; i < samples - 4 && (k < kills.length || d < deaths.length); i += 5) {
    if (k < kills.length && (d >= deaths.length || k <= d)) {
      fights.set(i, { type: 'kill', tags: kills[k++] })
      if (afterKill.has(d) && d < deaths.length) fights.set(i + 2, { type: 'death', tags: deaths[d++] })
    } else if (d < deaths.length) {
      fights.set(i, { type: 'death', tags: deaths[d++] })
    }
  }

  let player = 0
  let enemy = 0
  for (let i = 0; i <= samples + 4; i++) {
    const t = start + i * interval
    if (i > samples) {
      b.batch(t, { phase: 'match_end', visibleOutcome: player >= enemy ? 'success' : 'failure', playerScore: player, opponentScore: enemy })
      continue
    }
    player += rnd() < 0.3 ? 1 : 0
    enemy += rnd() < 0.36 ? 1 : 0
    const f = fights.get(i)
    b.batch(
      t,
      { phase: 'round', playerScore: player, opponentScore: enemy, matchTimeLeftSec: Math.max(0, 600 - Math.round((i / samples) * 600)), mapName: 'Synthetic Yard', modeName: 'Hardpoint' },
      f ? [{ type: f.type, ...(f.type === 'death' ? { zone: ['n', 'c', 'e', 'c', 'w'][i % 5] } : {}), attributes: f.tags, evidence: f.type === 'death' ? 'killcam' : 'kill marker' }] : [],
    )
  }
  const tagged = (key: string, value: string, list: Tags[]) => list.filter((x) => x[key] === value).length
  return {
    profileId: 'cod-mw4',
    batches: b.batches,
    truth: {
      deaths: deaths.length,
      kills: kills.length,
      inOpen: tagged('exposure', 'in_open', deaths),
      exposureKnown: deaths.filter((x) => x.exposure !== 'unknown').length,
      longLost: tagged('range', 'long', deaths),
      longWon: tagged('range', 'long', kills),
      closeWon: tagged('range', 'close', kills),
      closeLost: tagged('range', 'close', deaths),
      afterKill: afterKill.size,
    },
    endsAt: start + (samples + 5) * interval,
  }
}

/**
 * An Age of Empires IV game (~16 game minutes compressed into `realMinutes`).
 * The scripted player's villager count falls behind the one-Town-Center curve
 * after the Feudal Age (a long idle Town Center), and recovers a little. From
 * 5:00 they fight six times: the three fights taken near the enemy's defences
 * are lost (two clumped under Mangonel fire, one fought until the army was
 * nearly gone); the three near home or in the open are won.
 */
export function aoe4GameReplay(opts: { seed: number; startAt?: number; intervalMs?: number; realMinutes?: number } = { seed: 1 }): VisionReplay {
  const rnd = mulberry32(opts.seed)
  const b = new Builder('aoe4', rnd)
  const interval = opts.intervalMs ?? 4000
  const start = opts.startAt ?? 0
  const samples = Math.round(((opts.realMinutes ?? 5) * 60_000) / interval)
  const gameSecAt = (i: number) => Math.round((i / samples) * 960)
  // Villagers: on the curve until 4:00, then 150 s with no production, then back to 3 a minute.
  const villagersAt = (sec: number) => (sec <= 240 ? 6 + Math.floor(sec / 20) : sec <= 390 ? 18 : 18 + Math.floor((sec - 390) / 20))

  type Fight = { won: boolean; frames: number; keep: number; trend: string[]; tags: Record<string, string> }
  const near = { location: 'near_enemy_defenses', numbers: 'even', ranged: 'screened', enemySiege: 'none', reinforcing: 'grouped' }
  const home = { location: 'near_own_defenses', numbers: 'even', ranged: 'screened', splash: 'no_enemy_siege', enemySiege: 'none', reinforcing: 'grouped' }
  const fights: Fight[] = [
    { won: false, frames: 3, keep: 0.15, trend: ['even', 'losing', 'losing'], tags: { ...near, matchup: 'mixed', splash: 'no_enemy_siege' } },
    { won: true, frames: 3, keep: 0.85, trend: ['even', 'winning', 'winning'], tags: { ...home, matchup: 'you_counter' } },
    { won: false, frames: 3, keep: 0.5, trend: ['even', 'losing', 'losing'], tags: { ...near, matchup: 'they_counter', splash: 'clumped_under_siege', enemySiege: 'ignored' } },
    { won: true, frames: 3, keep: 0.8, trend: ['winning', 'winning', 'winning'], tags: { ...home, matchup: 'mixed' } },
    { won: false, frames: 4, keep: 0.45, trend: ['even', 'even', 'losing', 'losing'], tags: { ...near, matchup: 'mixed', splash: 'clumped_under_siege', enemySiege: 'ignored' } },
    { won: true, frames: 3, keep: 0.9, trend: ['even', 'winning', 'winning'], tags: { ...home, location: 'open_field', matchup: 'you_counter' } },
  ]
  // Fight k starts at sample fightAt[k]; samples between fights exceed the 20 s gap.
  const first = Math.round(samples * 0.32)
  const fightAt = fights.map((_, k) => first + k * 9)
  let army = 0
  let fightArmy = 0
  for (let i = 0; i <= samples + 4; i++) {
    const t = start + i * interval
    if (i > samples) {
      b.batch(t, { phase: 'match_end', visibleOutcome: 'failure' })
      continue
    }
    const sec = gameSecAt(i)
    const vills = villagersAt(sec)
    if (sec > 240) army += 2 // production between fights
    const k = fightAt.findIndex((at, j) => i >= at && i < at + fights[j].frames)
    const events: { type: string; attributes: Record<string, string>; evidence: string }[] = []
    if (k >= 0) {
      const f = fights[k]
      const frame = i - fightAt[k]
      if (frame === 0) fightArmy = army
      // The army shrinks across the fight's frames to its final share.
      army = Math.round(fightArmy * (1 - ((1 - f.keep) * (frame + 1)) / f.frames))
      events.push({ type: 'fight', attributes: { trend: f.trend[frame], ...f.tags }, evidence: 'army fighting on screen' })
    }
    b.batch(
      t,
      {
        phase: 'round',
        food: 150 + Math.round(rnd() * 200),
        wood: 100 + Math.round(rnd() * 200),
        gold: 50 + Math.round(rnd() * 150),
        stone: 0,
        villagers: vills,
        idleVillagers: 0,
        population: vills + army,
        populationCap: vills + army + 8,
        age: sec < 270 ? 'dark' : 'feudal',
        gameTimeSec: sec,
        ...(i === 1 ? { mapName: 'Dry Arabia', opponentCiv: 'English' } : {}),
        // The scout reaches their base, then their army shows up in the fights.
        ...(i >= Math.round(samples * 0.2) && i < Math.round(samples * 0.2) + 2 ? { enemyBuildings: 'Archery Range, 2 Barracks' } : {}),
        ...(k >= 0 ? { enemyUnits: k % 2 === 0 ? '10 Longbowmen, 4 Spearmen' : '6 Longbowmen' } : {}),
      },
      events,
    )
  }
  return {
    profileId: 'aoe4',
    batches: b.batches,
    // Expected on the curve: 21 at 5:00, 36 at 10:00, 51 at 15:00.
    truth: {
      at300: villagersAt(300),
      at600: villagersAt(600),
      at900: villagersAt(900),
      gapAt600: 36 - villagersAt(600),
      fightsWon: fights.filter((f) => f.won).length,
      fightsLost: fights.filter((f) => !f.won).length,
      lostNearDefenses: fights.filter((f) => !f.won && f.tags.location === 'near_enemy_defenses').length,
      lostClumped: fights.filter((f) => !f.won && f.tags.splash === 'clumped_under_siege').length,
      opponent: 'English',
      mostSeen: 'Longbowman',
    },
    endsAt: start + (samples + 5) * interval,
  }
}
