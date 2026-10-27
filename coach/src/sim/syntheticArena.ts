// The Synthetic Arena: a tiny fictional game HUD drawn into RGBA frames, with a
// scripted player whose tendencies are known in advance. It is a test
// fixture and a developer tool — it lets the full pixels-to-speech pipeline be
// exercised, and its conclusions checked against ground truth, without a real
// game. It is always labelled as a simulation in the UI.

import type { Frame } from '../core/types.ts'

export interface PlayerTendencies {
  /** Chance the player heals after their health gets low. */
  readonly healWhenLow: number
  /** Elimination chance after low health, when they healed / did not. */
  readonly eliminatedIfHealed: number
  readonly eliminatedIfNotHealed: number
  /** Chance of using the escape ability after a heavy hit. */
  readonly escapeAfterHeavyHit: number
  /** Chance of winning a round the player survives. */
  readonly winIfSurvived: number
}

export const DEFAULT_TENDENCIES: PlayerTendencies = {
  healWhenLow: 0.45,
  eliminatedIfHealed: 0.15,
  eliminatedIfNotHealed: 0.75,
  escapeAfterHeavyHit: 0.5,
  winIfSurvived: 0.7,
}

export interface HudState {
  /** null = HUD hidden (intermission, death cam). */
  health: number | null
  abilityReady: boolean | null
  roundLive: boolean
  eliminated: boolean
  banner: 'victory' | 'defeat' | 'match' | null
}

/** What actually happened, for checking the coach's conclusions in tests. */
export interface GroundTruth {
  lowHealth: { at: number; healed: boolean; eliminated: boolean }[]
  rounds: { result: 'win' | 'loss' }[]
}

export function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

interface Keyframe {
  at: number
  state: HudState
}

/**
 * Script a match as HUD keyframes. Health changes are emitted as short ramps
 * so detectors see them over several frames, as they would on a real HUD.
 */
export function scriptMatch(opts: {
  rounds: number
  seed: number
  tendencies?: PlayerTendencies
  startAt?: number
}): { keyframes: Keyframe[]; truth: GroundTruth; endsAt: number } {
  const p = opts.tendencies ?? DEFAULT_TENDENCIES
  const rnd = mulberry32(opts.seed)
  const between = (lo: number, hi: number) => lo + (hi - lo) * rnd()
  const keyframes: Keyframe[] = []
  const truth: GroundTruth = { lowHealth: [], rounds: [] }
  let t = opts.startAt ?? 0
  const hidden: HudState = { health: null, abilityReady: null, roundLive: false, eliminated: false, banner: null }
  const push = (state: HudState) => keyframes.push({ at: Math.round(t), state: { ...state } })
  const ramp = (s: HudState, to: number, ms: number) => {
    const from = s.health ?? 1
    const steps = Math.max(2, Math.round(ms / 100))
    for (let i = 1; i <= steps; i++) {
      t += ms / steps
      s.health = from + ((to - from) * i) / steps
      push(s)
    }
  }

  push(hidden)
  t += 1500
  for (let r = 0; r < opts.rounds; r++) {
    const s: HudState = { health: 1, abilityReady: true, roundLive: true, eliminated: false, banner: null }
    push(s)
    let eliminated = false
    let abilityBackAt = 0
    const roundEnds = t + between(22_000, 30_000)

    while (t < roundEnds && !eliminated) {
      t += between(1800, 4200)
      if (!s.abilityReady && t >= abilityBackAt) {
        s.abilityReady = true
        push(s)
      }
      const before = s.health ?? 1
      const heavy = rnd() < 0.35
      const hit = heavy ? between(0.27, 0.4) : between(0.06, 0.16)
      ramp(s, Math.max(0.04, before - hit), heavy ? 400 : 300)

      if (heavy && s.abilityReady && rnd() < p.escapeAfterHeavyHit) {
        t += between(300, 900)
        s.abilityReady = false
        abilityBackAt = t + 8000
        push(s)
      }

      if ((s.health ?? 1) < 0.3 && before >= 0.3) {
        const lowAt = t
        const healed = rnd() < p.healWhenLow
        if (healed) {
          t += between(500, 1200)
          ramp(s, Math.min(1, (s.health ?? 0) + between(0.35, 0.5)), 800)
        }
        const dies = rnd() < (healed ? p.eliminatedIfHealed : p.eliminatedIfNotHealed)
        truth.lowHealth.push({ at: lowAt, healed, eliminated: dies })
        if (dies) {
          t = lowAt + between(2500, 4500)
          eliminated = true
        } else {
          // Survived the situation; get some health back before the next exchange.
          t = Math.max(t, lowAt + 6500)
          if ((s.health ?? 0) < 0.55) ramp(s, 0.7, 900)
        }
      }
    }

    // Round end. The death cam hides the HUD the instant the player is eliminated.
    const won = !eliminated && rnd() < p.winIfSurvived
    if (eliminated) {
      push({ ...hidden, roundLive: true, eliminated: true })
      t += 2000
    }
    t += 300
    push({ ...hidden, banner: won ? 'victory' : 'defeat' })
    truth.rounds.push({ result: won ? 'win' : 'loss' })
    t += 3000
    push(hidden)
    t += 2000
  }
  push({ ...hidden, banner: 'match' })
  t += 4000
  push(hidden)
  t += 1000
  return { keyframes, truth, endsAt: t }
}

/** The HUD state at time t (keyframes hold until the next one). */
export function stateAt(keyframes: readonly Keyframe[], t: number): HudState {
  let lo = 0
  let hi = keyframes.length - 1
  if (t < keyframes[0].at) return keyframes[0].state
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (keyframes[mid].at <= t) lo = mid
    else hi = mid - 1
  }
  return keyframes[lo].state
}

// --- Rendering -----------------------------------------------------------------

type Rgb = readonly [number, number, number]

const COLORS = {
  healthFill: [52, 205, 84] as Rgb,
  healthTrack: [44, 46, 52] as Rgb,
  ability: [40, 200, 235] as Rgb,
  abilityUsed: [70, 72, 78] as Rgb,
  timer: [242, 244, 246] as Rgb,
  eliminated: [214, 36, 40] as Rgb,
  victory: [245, 196, 40] as Rgb,
  defeat: [150, 60, 205] as Rgb,
  match: [40, 100, 225] as Rgb,
  letters: [18, 18, 22] as Rgb,
}

/** Static per-size scenery and a noise table, so rendering stays cheap in long test runs. */
const sceneryCache = new Map<string, { background: Uint8ClampedArray; noise: Int8Array }>()

function scenery(width: number, height: number) {
  const key = `${width}x${height}`
  let cached = sceneryCache.get(key)
  if (!cached) {
    const background = new Uint8ClampedArray(width * height * 4)
    for (let y = 0; y < height; y++) {
      const base = 30 + 25 * (y / height)
      for (let x = 0; x < width; x++) {
        const i = (y * width + x) * 4
        background[i] = base
        background[i + 1] = base + 6
        background[i + 2] = base + 14
        background[i + 3] = 255
      }
    }
    const rnd = mulberry32(0x5bd1e995)
    const noise = new Int8Array(width * height * 2)
    for (let i = 0; i < noise.length; i++) noise[i] = Math.round((rnd() - 0.5) * 127)
    cached = { background, noise }
    sceneryCache.set(key, cached)
  }
  return cached
}

/** Render a frame of the synthetic HUD. `noise` adds per-pixel jitter (peak-to-peak, 0..255). */
export function renderHud(state: HudState, width: number, height: number, timestamp: number, noise = 6): Frame {
  const { background, noise: table } = scenery(width, height)
  const data = new Uint8ClampedArray(background)

  // A slowly moving lighter shape, so the scene is never static.
  const cx = Math.round((0.5 + 0.35 * Math.sin(timestamp / 3000)) * width)
  const cy = Math.round(height * 0.6)
  const rad = Math.round(height * 0.15)
  for (let y = Math.max(0, cy - rad); y < Math.min(height, cy + rad); y++) {
    for (let x = Math.max(0, cx - rad); x < Math.min(width, cx + rad); x++) {
      if ((x - cx) ** 2 + (y - cy) ** 2 >= rad * rad) continue
      const i = (y * width + x) * 4
      data[i] += 25
      data[i + 1] += 25
      data[i + 2] += 25
    }
  }

  const rect = (x: number, y: number, w: number, h: number, c: Rgb) => {
    const x0 = Math.round(x * width)
    const y0 = Math.round(y * height)
    const x1 = Math.round((x + w) * width)
    const y1 = Math.round((y + h) * height)
    for (let yy = y0; yy < y1; yy++) {
      for (let xx = x0; xx < x1; xx++) {
        const i = (yy * width + xx) * 4
        data[i] = c[0]
        data[i + 1] = c[1]
        data[i + 2] = c[2]
      }
    }
  }
  // A banner with dark "lettering" stripes, so coverage is realistic (~70%), not 100%.
  const banner = (x: number, y: number, w: number, h: number, c: Rgb) => {
    rect(x, y, w, h, c)
    for (let k = 0; k < 9; k++) rect(x + w * (0.12 + k * 0.085), y + h * 0.25, w * 0.03, h * 0.5, COLORS.letters)
  }

  if (state.health !== null) {
    rect(0.03, 0.9, 0.25, 0.04, COLORS.healthTrack)
    if (state.health > 0) rect(0.03, 0.9, 0.25 * Math.min(1, state.health), 0.04, COLORS.healthFill)
  }
  if (state.abilityReady !== null) rect(0.3, 0.88, 0.04, 0.07, state.abilityReady ? COLORS.ability : COLORS.abilityUsed)
  if (state.roundLive) rect(0.46, 0.02, 0.08, 0.04, COLORS.timer)
  if (state.eliminated) banner(0.3, 0.45, 0.4, 0.1, COLORS.eliminated)
  if (state.banner === 'victory') banner(0.25, 0.12, 0.5, 0.1, COLORS.victory)
  if (state.banner === 'defeat') banner(0.25, 0.12, 0.5, 0.1, COLORS.defeat)
  if (state.banner === 'match') banner(0.1, 0.3, 0.8, 0.1, COLORS.match)

  if (noise > 0) {
    // A different window into the noise table each frame.
    const scale = noise / 127
    const offset = Math.floor(timestamp * 7919) % (width * height)
    for (let p = 0, i = 0; i < data.length; p++, i += 4) {
      const n = table[p + offset] * scale
      data[i] += n
      data[i + 1] += n
      data[i + 2] += n
    }
  }
  return { width, height, data, timestamp }
}

/** Iterate frames of a scripted match at a given frame rate. */
export function* simulateFrames(opts: {
  rounds: number
  seed: number
  fps: number
  width?: number
  height?: number
  tendencies?: PlayerTendencies
  startAt?: number
}): Generator<Frame, GroundTruth> {
  const { keyframes, truth, endsAt } = scriptMatch(opts)
  const step = 1000 / opts.fps
  const width = opts.width ?? 256
  const height = opts.height ?? 144
  for (let t = opts.startAt ?? 0; t <= endsAt; t += step) {
    yield renderHud(stateAt(keyframes, t), width, height, t)
  }
  return truth
}
