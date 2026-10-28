// Following a football match on the radar, frame by frame: where all 22
// players and the ball are, which end each team defends, who has the ball,
// turnovers, shots and kick-offs, and every goal's last seconds. At the end
// (or any time, live) it gives the match on one pitch with the player's team
// attacking to the right: team shapes with and without the ball, the back
// line's height, length, width and compactness, average formations, heat,
// and each goal with the positions that led to it.
//
// Which team is the player's: from the colours they set in the game, or,
// when left at the default, worked out from the first goal (the scoreboard
// says who scored; the radar says at which end). Until then the two teams
// are only told apart, and the record says so.

import type { NormRect } from '../types.ts'
import { PITCH_LENGTH as L, PITCH_WIDTH as WD, readRadar, type RadarBall, type RadarColour } from './radar.ts'
import { assign } from './assign.ts'
import { formationOf } from './formations.ts'

export type Side = 'ours' | 'theirs'
type Label = 'A' | 'B'
type P = readonly [number, number]

interface Sample {
  readonly t: number
  readonly ball: P | null
  readonly a: readonly P[]
  readonly b: readonly P[]
  readonly owner: Label | null
  /** Which group defends the radar's left end then. */
  readonly leftIs: Label | null
}

/** Averages of a team's shape over a phase (with or without the ball). Metres, own goal at x = 0. */
export interface ShapeStats {
  readonly frames: number
  /** The deepest outfield player's distance from their own goal line (the back line's height). */
  readonly line: number
  /** The four deepest outfield players' average distance from their goal. */
  readonly backLine: number
  /** Deepest to highest outfield player. */
  readonly length: number
  /** Widest to widest outfield player. */
  readonly width: number
  /** Average distance of outfield players from their middle. */
  readonly compact: number
  /** Outfield players between the ball and their own goal. */
  readonly behindBall: number
  /** Back line to midfield line (metres); older records do not have it. */
  readonly gap?: number
  /** Share of frames with an outfield player within 5 m of the ball (pressure, as analysts count it). */
  readonly press?: number
  /** Back-line heights, frames per 5 m from the goal line. */
  readonly lineHist: readonly number[]
  /** Average positions of the ten outfield players, and the formation they make (e.g. "4-4-2"). */
  readonly slots: readonly P[]
  readonly formation: string
}

export interface PitchEvent {
  readonly kind: 'turnover' | 'shot' | 'kickoff'
  /** Seconds since the match started. */
  readonly t: number
  readonly x: number
  readonly y: number
  /** Turnover: the team that lost the ball; shot: the shooter's team; kick-off: the team kicking off. */
  readonly team: Side
  /** Turnover: the ball had just gone out of play (a throw-in or goal kick, not a lost duel). */
  readonly out?: boolean
  /** Turnover by the player's team: seconds until they won it back (absent: not within 20 s). */
  readonly regainSec?: number
}

export interface PitchFrame {
  readonly t: number
  readonly ball: P | null
  readonly ours: readonly P[]
  readonly theirs: readonly P[]
}

export interface PitchGoal {
  readonly t: number
  readonly for: boolean
  /** The ball when the goal went in (or its last sighting), and where the shot came from if seen. */
  readonly x: number
  readonly y: number
  readonly shot: P | null
  /** The last seconds before it (about 2.5 a second). */
  readonly frames: readonly PitchFrame[]
  /** The defending team at the shot (or a second before the goal): outfield players goal-side of the ball, the back line's height, the team's length. */
  readonly defending: { readonly behindBall: number; readonly line: number; readonly length: number; readonly width: number } | null
}

/** One match on the radar, the player's team attacking to the right (x = 0 their own goal line). */
export interface PitchMatchRecord {
  readonly version: 1
  /** "known": "ours" is the player's team; "unknown": the two teams were only told apart (no colours set, no goal seen). */
  readonly sides: 'known' | 'unknown'
  readonly colours: { readonly ours: P3; readonly theirs: P3 }
  readonly seconds: number
  /** Seconds each team had the ball (as the radar showed it). */
  readonly possession: { readonly ours: number; readonly theirs: number }
  /** Seconds the ball spent in the player's third, the middle third and the opponent's third. */
  readonly territory: readonly [number, number, number]
  readonly shape: { readonly in: ShapeStats | null; readonly out: ShapeStats | null }
  readonly theirShape: { readonly in: ShapeStats | null; readonly out: ShapeStats | null }
  /** Half-seconds per 5 m cell (21 × 14, row by row): the ball, each team's players. */
  readonly heat: { readonly ball: readonly number[]; readonly ours: readonly number[]; readonly theirs: readonly number[] }
  readonly events: readonly PitchEvent[]
  readonly goals: readonly PitchGoal[]
}
type P3 = readonly [number, number, number]

export interface PitchLive {
  readonly ok: boolean
  readonly ageMs: number
  readonly sides: 'known' | 'unknown'
  readonly ball: P | null
  readonly ours: readonly P[]
  readonly theirs: readonly P[]
  readonly owner: Side | null
  readonly possession: { readonly ours: number; readonly theirs: number }
}

export const HEAT_COLS = 21
export const HEAT_ROWS = 14
const SAMPLE_MS = 500
const BUFFER_MS = 30_000
const other = (l: Label): Label => (l === 'A' ? 'B' : 'A')

export class PitchTracker {
  private region: NormRect
  private ours: RadarColour | null
  private theirs: RadarColour | null
  private lineEma: Float32Array | null = null
  private lineMask: Uint8Array | null = null
  private lineFrames = 0
  private protos: (readonly number[])[] | null = null
  private buffer: Sample[] = []
  private timeline: Sample[] = []
  private lastSampleAt = -Infinity
  private lastValidAt = -Infinity
  private ends = 0
  private leftIs: Label | null = null
  private owner: Label | null = null
  private candidate: Label | null = null
  private candN = 0
  private ballOutAt = -Infinity
  private shotCool = -Infinity
  private stillSince = -Infinity
  private lastKickoff = -Infinity
  private oursVotes = 0
  private matchStart: number | null = null
  private events: { kind: PitchEvent['kind']; t: number; x: number; y: number; team: Label; out?: boolean }[] = []
  private goals: { t: number; for: boolean; end: 'left' | 'right' | null; x: number; y: number; shot: P | null; frames: Sample[] }[] = []
  private possession = { A: 0, B: 0 }
  private last: Sample | null = null

  constructor(region: NormRect, colours: { ours?: RadarColour | null; theirs?: RadarColour | null } = {}) {
    this.region = region
    this.ours = colours.ours ?? null
    this.theirs = colours.theirs ?? null
  }

  /** The radar box and the team colours set in the game (null: the default colours, told apart by the tracker). */
  configure(region: NormRect, colours: { ours?: RadarColour | null; theirs?: RadarColour | null }): void {
    if (region.x !== this.region.x || region.y !== this.region.y || region.w !== this.region.w || region.h !== this.region.h) {
      this.lineEma = null
      this.lineMask = null
      this.lineFrames = 0
    }
    this.region = region
    const known = (c: RadarColour | null | undefined) => c ?? null
    if (known(colours.ours) !== this.ours || known(colours.theirs) !== this.theirs) this.protos = null
    this.ours = known(colours.ours)
    this.theirs = known(colours.theirs)
  }

  private get coloursKnown(): boolean {
    return !!this.ours && !!this.theirs && this.ours !== this.theirs
  }

  /** The player's team: A (by colour, or worked out from goals), or not known yet. */
  private get oursLabel(): Label | null {
    if (this.coloursKnown) return 'A'
    return this.oursVotes > 0 ? 'A' : this.oursVotes < 0 ? 'B' : null
  }

  newMatch(t: number): void {
    this.matchStart = t
    this.timeline = []
    this.events = []
    this.goals = []
    this.possession = { A: 0, B: 0 }
    this.lastSampleAt = -Infinity
    if (!this.coloursKnown) this.oursVotes = 0
  }

  /** One frame. `skip` when a menu or results screen is showing. */
  frame(frame: { readonly width: number; readonly height: number; readonly data: Uint8ClampedArray; readonly timestamp: number }, skip: boolean): void {
    if (skip) return
    const t = frame.timestamp
    const r = readRadar(frame, this.region, { ours: this.ours, theirs: this.theirs, lines: this.lineFrames >= 40 ? this.lineMask : null })
    this.learnLines(r.white ?? null, r.size)
    if (!r.ok) return
    if (this.matchStart === null) this.matchStart = t
    // Groups A and B kept apart from frame to frame (by their colours).
    let swap = false
    if (!this.coloursKnown && r.groups.length === 2) {
      if (this.protos) {
        const same = dist3(r.groups[0], this.protos[0]) + dist3(r.groups[1], this.protos[1])
        const crossed = dist3(r.groups[0], this.protos[1]) + dist3(r.groups[1], this.protos[0])
        swap = crossed < same
      }
      const g = swap ? [r.groups[1], r.groups[0]] : r.groups
      this.protos = this.protos ? this.protos.map((p, i) => p.map((v, c) => v * 0.9 + g[i][c] * 0.1)) : g.map((x) => [...x])
    }
    const a: P[] = []
    const b: P[] = []
    for (const d of r.dots) {
      const isA = d.team === 'ours' || (d.team === 'A') !== swap
      ;(isA ? a : b).push([d.x, d.y])
    }
    const picked = this.pickBall(r.balls, r.ball, t)
    const ball = picked ? picked.p : null
    // The colour round the ball: the carrier's team.
    const on: Label | null = !picked?.on ? null : picked.on === 'ours' || (picked.on === 'A') !== swap ? 'A' : 'B'
    this.lastValidAt = t
    this.vote(a, b, ball, t)
    const s: Sample = { t, ball, a, b, owner: this.possess(a, b, ball, on, t), leftIs: this.leftIs }
    this.shots(s)
    this.buffer.push(s)
    while (this.buffer.length && this.buffer[0].t < t - BUFFER_MS) this.buffer.shift()
    if (t - this.lastSampleAt >= SAMPLE_MS) {
      this.timeline.push(s)
      this.lastSampleAt = t
      if (this.timeline.length > 20_000) this.timeline.splice(0, this.timeline.length - 20_000)
    }
    this.last = s
  }

  /** The pitch lines are drawn every frame: pixels bright on nearly every frame are never the ball. */
  private learnLines(white: Uint8Array | null, size: { w: number; h: number } | undefined): void {
    if (!white || !size) return
    if (!this.lineEma || this.lineEma.length !== white.length) {
      this.lineEma = new Float32Array(white.length)
      this.lineMask = new Uint8Array(white.length)
      this.lineFrames = 0
    }
    const e = this.lineEma
    const m = this.lineMask!
    for (let i = 0; i < white.length; i++) {
      e[i] = e[i] * 0.97 + white[i] * 0.03
      m[i] = e[i] > 0.6 ? 1 : 0
    }
    this.lineFrames++
  }

  /** The candidate nearest where the ball was heading (else the likeliest). */
  private pickBall(cands: readonly RadarBall[], best: RadarBall | null, t: number): { p: P; on: RadarBall['on'] } | null {
    const prev = this.last?.ball
    if (!cands.length) return null
    const out = (c: RadarBall | null) => (c ? { p: [c.x, c.y] as P, on: c.on } : null)
    if (!prev || !this.last || t - this.last.t > 1500) return out(best)
    const dt = (t - this.last.t) / 1000
    let pick: RadarBall | null = null
    let bd = Infinity
    for (const c of cands) {
      const dd = Math.hypot(c.x - prev[0], c.y - prev[1])
      if (dd < bd) {
        bd = dd
        pick = c
      }
    }
    // A ball moves at most ~40 m/s.
    return pick && bd <= 4 + 40 * dt ? out(pick) : out(best)
  }

  /** Which end each group defends: the goalkeepers (the deepest player at each end), and kick-offs. */
  private vote(a: readonly P[], b: readonly P[], ball: P | null, t: number): void {
    if (a.length < 7 || b.length < 7) return
    const minA = Math.min(...a.map((p) => p[0]))
    const minB = Math.min(...b.map((p) => p[0]))
    const maxA = Math.max(...a.map((p) => p[0]))
    const maxB = Math.max(...b.map((p) => p[0]))
    let v = 0
    if (Math.min(minA, minB) < 16) v += minA < minB ? 1 : -1
    if (Math.max(maxA, maxB) > L - 16) v += maxB > maxA ? 1 : -1
    if (v) this.ends = this.ends * 0.97 + 0.03 * Math.sign(v)
    // A kick-off: the ball still on the centre spot, each team in its own half.
    const still = !!ball && Math.hypot(ball[0] - L / 2, ball[1] - WD / 2) < 3.5 && (!this.last?.ball || Math.hypot(ball[0] - this.last.ball[0], ball[1] - this.last.ball[1]) < 0.6)
    if (still) {
      if (this.stillSince === -Infinity) this.stillSince = t
    } else this.stillSince = -Infinity
    if (still && t - this.stillSince >= 800) {
      const leftA = a.filter((p) => p[0] < L / 2 + 1).length / a.length
      const leftB = b.filter((p) => p[0] < L / 2 + 1).length / b.length
      if ((leftA > 0.85 && leftB < 0.25) || (leftB > 0.85 && leftA < 0.25)) {
        this.ends = leftA > leftB ? 1 : -1
        if (t - this.lastKickoff > 20_000) {
          // The kicking team has a player on the ball.
          const near = (ps: readonly P[]) => Math.min(...ps.map((p) => Math.hypot(p[0] - ball![0], p[1] - ball![1])))
          this.events.push({ kind: 'kickoff', t, x: ball![0], y: ball![1], team: near(a) <= near(b) ? 'A' : 'B' })
          this.lastKickoff = t
        }
      }
    }
    this.leftIs = this.ends > 0.25 ? 'A' : this.ends < -0.25 ? 'B' : this.leftIs
  }

  /** Who has the ball: the nearer team's player within 2 m, held for two frames. Turnovers on each change. */
  private possess(a: readonly P[], b: readonly P[], ball: P | null, on: Label | null, t: number): Label | null {
    if (!ball) return this.owner
    if (ball[0] < -0.5 || ball[0] > L + 0.5 || ball[1] < -0.5 || ball[1] > WD + 0.5) this.ballOutAt = t
    const near = (ps: readonly P[]) => (ps.length ? Math.min(...ps.map((p) => Math.hypot(p[0] - ball[0], p[1] - ball[1]))) : Infinity)
    const da = near(a)
    const db = near(b)
    // The ball sitting on a team's dot settles it; else the nearer team within 2 m.
    const cand: Label | null = on && (on === 'A' ? da : db) < 3 ? on : Math.min(da, db) < 2.2 && Math.abs(da - db) > 0.7 ? (da < db ? 'A' : 'B') : null
    if (cand === this.candidate) this.candN++
    else {
      this.candidate = cand
      this.candN = 1
    }
    if (cand && this.candN >= 2 && cand !== this.owner) {
      if (this.owner) this.events.push({ kind: 'turnover', t, x: ball[0], y: ball[1], team: this.owner, out: t - this.ballOutAt < 3000 })
      this.owner = cand
    }
    if (this.owner && this.last) this.possession[this.owner] += Math.min(500, t - this.last.t) / 1000
    return this.owner
  }

  /** A shot: the ball suddenly fast (over 18 m/s) towards the goal mouth it attacks, from within 40 m. */
  private shots(s: Sample): void {
    const p = this.last
    if (!s.ball || !p?.ball || s.t - p.t < 100 || s.t - p.t > 600 || s.t < this.shotCool) return
    const dt = (s.t - p.t) / 1000
    const vx = (s.ball[0] - p.ball[0]) / dt
    const vy = (s.ball[1] - p.ball[1]) / dt
    if (Math.hypot(vx, vy) < 18 || Math.abs(vx) < 6) return
    const endX = vx < 0 ? 0 : L
    const reach = (endX - p.ball[0]) / vx
    const yAt = p.ball[1] + vy * reach
    if (Math.abs(endX - p.ball[0]) > 40 || Math.abs(yAt - WD / 2) > 11) return
    // The team attacking that goal (the one defending the other end); a fast ball to their own
    // goal is a pass back to the keeper or a clearance, not a shot.
    const attacker: Label | null = this.leftIs ? (vx > 0 ? this.leftIs : other(this.leftIs)) : null
    if (attacker && p.owner && p.owner !== attacker) return
    const shooter = attacker ?? p.owner ?? (vx > 0 ? 'A' : 'B')
    this.events.push({ kind: 'shot', t: p.t, x: p.ball[0], y: p.ball[1], team: shooter })
    this.shotCool = s.t + 1500
  }

  /**
   * A goal the scoreboard just showed (`side`: for the player or against). Its last seconds are
   * taken from the radar; when the teams' colours were not set, it says which team is the player's.
   */
  goal(t: number, side: 'for' | 'against'): void {
    const recent = this.buffer.filter((s) => s.t <= t + 500 && s.t >= t - BUFFER_MS)
    if (!recent.length) return
    // The goal: the ball's last sighting in a goal area (else in a box, else its last sighting).
    const inGoal = (s: Sample, depth: number, half: number) => !!s.ball && (s.ball[0] < depth || s.ball[0] > L - depth) && Math.abs(s.ball[1] - WD / 2) < half
    const at = [...recent].reverse().find((s) => inGoal(s, 6, 10)) ?? [...recent].reverse().find((s) => inGoal(s, 16.5, 20.2)) ?? null
    const end: 'left' | 'right' | null = at?.ball ? (at.ball[0] < L / 2 ? 'left' : 'right') : null
    const gt = at?.t ?? recent[recent.length - 1].t
    // Which team is the player's: the scorer attacks that end.
    if (end && at!.leftIs && !this.coloursKnown) {
      const defendsEnd: Label = end === 'left' ? at!.leftIs : other(at!.leftIs)
      const oursIs = side === 'against' ? defendsEnd : other(defendsEnd)
      this.oursVotes += oursIs === 'A' ? 1 : -1
    }
    const shot = [...this.events].reverse().find((e) => e.kind === 'shot' && e.t <= gt && e.t >= gt - 4000)
    const frames = recent.filter((s) => s.t >= gt - 12_000 && s.t <= gt + 400).filter((_, i) => i % 2 === 0)
    if (this.goals.some((g) => Math.abs(g.t - gt) < 5000)) return
    this.goals.push({ t: gt, for: side === 'for', end, x: at?.ball?.[0] ?? NaN, y: at?.ball?.[1] ?? NaN, shot: shot ? [shot.x, shot.y] : null, frames })
  }

  /** Now, for the live mini pitch: everyone's position (the player's team attacking right when known). */
  status(now: number): PitchLive | null {
    const s = this.last
    if (!s) return null
    const ours = this.oursLabel ?? 'A'
    const n = this.normaliser(ours, s.leftIs)
    return {
      ok: now - this.lastValidAt < 3000,
      ageMs: now - this.lastValidAt,
      sides: this.oursLabel ? 'known' : 'unknown',
      ball: s.ball ? n(s.ball) : null,
      ours: (ours === 'A' ? s.a : s.b).map(n),
      theirs: (ours === 'A' ? s.b : s.a).map(n),
      owner: s.owner ? (s.owner === ours ? 'ours' : 'theirs') : null,
      possession: { ours: this.possession[ours], theirs: this.possession[other(ours)] },
    }
  }

  /** Points as the player's team attacking to the right (the pitch turned round when they defend the right end). */
  private normaliser(ours: Label, leftIs: Label | null): (p: P) => P {
    const flip = leftIs !== null && leftIs !== ours
    return (p) => (flip ? [round1(L - p[0]), round1(WD - p[1])] : [round1(p[0]), round1(p[1])])
  }

  /** The match so far (null before the radar was seen). */
  snapshot(): PitchMatchRecord | null {
    if (!this.timeline.length || this.matchStart === null) return null
    const ours = this.oursLabel ?? 'A'
    const start = this.matchStart
    const sec = (t: number) => Math.round((t - start) / 100) / 10
    const side = (l: Label): Side => (l === ours ? 'ours' : 'theirs')
    // Each sample on one pitch, the player's team attacking right.
    const norm = this.timeline.map((s) => {
      const n = this.normaliser(ours, s.leftIs)
      return { t: s.t, ball: s.ball ? n(s.ball) : null, ours: (ours === 'A' ? s.a : s.b).map(n), theirs: (ours === 'A' ? s.b : s.a).map(n), owner: s.owner ? side(s.owner) : null }
    })
    const territory: [number, number, number] = [0, 0, 0]
    const heat = { ball: new Array(HEAT_COLS * HEAT_ROWS).fill(0), ours: new Array(HEAT_COLS * HEAT_ROWS).fill(0), theirs: new Array(HEAT_COLS * HEAT_ROWS).fill(0) }
    const cell = (p: P) => Math.min(HEAT_ROWS - 1, Math.max(0, Math.floor(p[1] / 5))) * HEAT_COLS + Math.min(HEAT_COLS - 1, Math.max(0, Math.floor(p[0] / 5)))
    for (const s of norm) {
      if (s.ball) {
        territory[s.ball[0] < L / 3 ? 0 : s.ball[0] < (2 * L) / 3 ? 1 : 2] += SAMPLE_MS / 1000
        heat.ball[cell(s.ball)]++
      }
      for (const p of s.ours) heat.ours[cell(p)]++
      for (const p of s.theirs) heat.theirs[cell(p)]++
    }
    const shapes = (team: 'ours' | 'theirs') => {
      const own = (s: (typeof norm)[number]) => (team === 'ours' ? s.ours : s.theirs.map((p) => [L - p[0], WD - p[1]] as P))
      const ballOwn = (s: (typeof norm)[number]) => (s.ball ? (team === 'ours' ? s.ball : ([L - s.ball[0], WD - s.ball[1]] as P)) : null)
      const phase = (want: 'in' | 'out') => shapeStats(norm.filter((s) => s.owner === (want === 'in' ? team : team === 'ours' ? 'theirs' : 'ours')).map((s) => ({ players: own(s), ball: ballOwn(s) })))
      return { in: phase('in'), out: phase('out') }
    }
    const events: PitchEvent[] = this.events
      .filter((e) => e.t >= start)
      .map((e) => {
        const n = this.normaliser(ours, this.leftIsAt(e.t))
        const [x, y] = n([e.x, e.y])
        const team = side(e.team)
        const base = { kind: e.kind, t: sec(e.t), x, y, team, ...(e.out ? { out: true } : {}) }
        if (e.kind !== 'turnover' || team !== 'ours') return base
        // How long until the player's team had it back.
        const back = norm.find((s) => s.t > e.t && s.owner === 'ours')
        return back && back.t - e.t <= 20_000 ? { ...base, regainSec: Math.round((back.t - e.t) / 100) / 10 } : base
      })
    const goals: PitchGoal[] = this.goals.map((g) => {
      const frames: PitchFrame[] = g.frames.map((s) => {
        const n = this.normaliser(ours, s.leftIs)
        return { t: sec(s.t), ball: s.ball ? n(s.ball) : null, ours: (ours === 'A' ? s.a : s.b).map(n), theirs: (ours === 'A' ? s.b : s.a).map(n) }
      })
      const n = this.normaliser(ours, this.leftIsAt(g.t))
      const at = Number.isFinite(g.x) ? n([g.x, g.y]) : [NaN, NaN]
      // The defending team just before the shot (or a second before the goal).
      const shotT = g.shot ? g.t - 1000 : g.t - 1000
      const before = [...frames].reverse().find((f) => f.t <= sec(shotT)) ?? frames[0]
      let defending: PitchGoal['defending'] = null
      if (before?.ball) {
        const def = g.for ? before.theirs.map((p) => [L - p[0], WD - p[1]] as P) : before.ours
        const ball: P = g.for ? [L - before.ball[0], WD - before.ball[1]] : before.ball
        const m = teamMetrics(def, ball)
        if (m) defending = { behindBall: m.behindBall, line: round1(m.line), length: round1(m.length), width: round1(m.width) }
      }
      return { t: sec(g.t), for: g.for, x: round1(at[0]), y: round1(at[1]), shot: g.shot ? n(g.shot) : null, frames, defending }
    })
    const colours = this.coloursKnown || !this.protos ? null : this.protos
    const ourColour = (colours ? colours[ours === 'A' ? 0 : 1] : [45, 115, 235]) as number[]
    const theirColour = (colours ? colours[ours === 'A' ? 1 : 0] : [225, 45, 45]) as number[]
    return {
      version: 1,
      sides: this.oursLabel ? 'known' : 'unknown',
      colours: { ours: ourColour.map(Math.round) as unknown as P3, theirs: theirColour.map(Math.round) as unknown as P3 },
      seconds: Math.round((this.timeline[this.timeline.length - 1].t - start) / 1000),
      possession: { ours: Math.round(this.possession[ours]), theirs: Math.round(this.possession[other(ours)]) },
      territory: territory.map((v) => Math.round(v)) as unknown as readonly [number, number, number],
      shape: shapes('ours'),
      theirShape: shapes('theirs'),
      heat,
      events,
      goals,
    }
  }

  private leftIsAt(t: number): Label | null {
    let best: Sample | null = null
    for (const s of this.timeline) {
      if (s.t > t) break
      best = s
    }
    return (best ?? this.timeline[0])?.leftIs ?? null
  }

  finish(): PitchMatchRecord | null {
    const r = this.snapshot()
    this.timeline = []
    this.events = []
    this.goals = []
    this.matchStart = null
    return r
  }
}

function round1(v: number): number {
  return Math.round(v * 10) / 10
}

function dist3(a: readonly number[], b: readonly number[]): number {
  return Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2])
}

/** A team's shape on one frame, own goal at x = 0: outfield players only (the deepest player is the goalkeeper). */
export function teamMetrics(
  players: readonly P[],
  ball: P | null,
): { line: number; backLine: number; length: number; width: number; compact: number; behindBall: number; gap: number; near: number; outfield: P[] } | null {
  if (players.length < 8) return null
  const sorted = [...players].sort((a, b) => a[0] - b[0])
  // The goalkeeper: the deepest, when well behind the next.
  const outfield = sorted[0][0] < 20 && sorted[1][0] - sorted[0][0] > 3 ? sorted.slice(1) : sorted
  const xs = outfield.map((p) => p[0])
  const ys = outfield.map((p) => p[1])
  const cx = xs.reduce((s, v) => s + v, 0) / xs.length
  const cy = ys.reduce((s, v) => s + v, 0) / ys.length
  // The back line ends at the biggest step after the third to fifth deepest player; the next three are the midfield line.
  let k = 3
  for (const j of [4, 5]) if (j < xs.length && xs[j] - xs[j - 1] > xs[k] - xs[k - 1]) k = j
  const mean = (a: readonly number[]) => a.reduce((s, v) => s + v, 0) / Math.max(1, a.length)
  return {
    line: xs[0],
    backLine: xs.slice(0, 4).reduce((s, v) => s + v, 0) / Math.min(4, xs.length),
    length: xs[xs.length - 1] - xs[0],
    width: Math.max(...ys) - Math.min(...ys),
    compact: outfield.reduce((s, p) => s + Math.hypot(p[0] - cx, p[1] - cy), 0) / outfield.length,
    behindBall: ball ? outfield.filter((p) => p[0] < ball[0]).length : 0,
    gap: xs.length > k ? mean(xs.slice(k, k + 3)) - mean(xs.slice(0, k)) : 0,
    near: ball ? Math.min(...outfield.map((p) => Math.hypot(p[0] - ball[0], p[1] - ball[1]))) : Infinity,
    outfield,
  }
}

/** Averages of a team's shape over frames (own goal at x = 0), with the average formation. */
export function shapeStats(frames: readonly { players: readonly P[]; ball: P | null }[]): ShapeStats | null {
  let n = 0
  const sum = { line: 0, backLine: 0, length: 0, width: 0, compact: 0, behindBall: 0, gap: 0 }
  let withBall = 0
  let pressed = 0
  const hist = new Array(21).fill(0)
  let slots: [number, number][] | null = null
  const acc: [number, number, number][] = []
  for (const f of frames) {
    const m = teamMetrics(f.players, f.ball)
    if (!m) continue
    n++
    sum.line += m.line
    sum.backLine += m.backLine
    sum.length += m.length
    sum.width += m.width
    sum.compact += m.compact
    sum.behindBall += m.behindBall
    sum.gap += m.gap
    if (Number.isFinite(m.near)) {
      withBall++
      if (m.near <= 5) pressed++
    }
    hist[Math.min(20, Math.max(0, Math.floor(m.line / 5)))]++
    // The average formation: frames with all ten outfield players, each matched to the nearest slot.
    if (m.outfield.length !== 10) continue
    if (!slots) {
      slots = m.outfield.map((p) => [p[0], p[1]])
      for (const p of slots) acc.push([p[0], p[1], 1])
      continue
    }
    const cur: [number, number][] = acc.map((a) => [a[0] / a[2], a[1] / a[2]])
    const pick = assign(cur.map((s) => m.outfield.map((p) => Math.hypot(p[0] - s[0], p[1] - s[1]))))
    pick.forEach((j, i) => {
      acc[i][0] += m.outfield[j][0]
      acc[i][1] += m.outfield[j][1]
      acc[i][2]++
    })
  }
  if (!n) return null
  const avg: P[] = acc.map((a) => [round1(a[0] / a[2]), round1(a[1] / a[2])])
  return {
    frames: n,
    line: round1(sum.line / n),
    backLine: round1(sum.backLine / n),
    length: round1(sum.length / n),
    width: round1(sum.width / n),
    compact: round1(sum.compact / n),
    behindBall: round1(sum.behindBall / n),
    gap: round1(sum.gap / n),
    press: withBall ? Math.round((100 * pressed) / withBall) / 100 : 0,
    lineHist: hist,
    slots: avg,
    formation: formationOf(avg),
  }
}

/** Checks a stored pitch record's shape and size (renderer input is untrusted on the way to disk). */
export function isPitchRecord(v: unknown): v is PitchMatchRecord {
  if (!v || typeof v !== 'object') return false
  const o = v as Record<string, unknown>
  const num = (x: unknown) => typeof x === 'number' && Number.isFinite(x)
  const pt = (x: unknown) => Array.isArray(x) && x.length === 2 && num(x[0]) && num(x[1])
  const pts = (x: unknown, max: number) => Array.isArray(x) && x.length <= max && x.every(pt)
  const shape = (x: unknown) =>
    x === null ||
    (!!x &&
      typeof x === 'object' &&
      ['frames', 'line', 'backLine', 'length', 'width', 'compact', 'behindBall'].every((k) => num((x as Record<string, unknown>)[k])) &&
      ['gap', 'press'].every((k) => (x as Record<string, unknown>)[k] === undefined || num((x as Record<string, unknown>)[k])) &&
      Array.isArray((x as ShapeStats).lineHist) &&
      (x as ShapeStats).lineHist.length <= 21 &&
      pts((x as ShapeStats).slots, 10) &&
      typeof (x as ShapeStats).formation === 'string' &&
      (x as ShapeStats).formation.length <= 12)
  const heat = o.heat as Record<string, unknown> | undefined
  const cells = HEAT_COLS * HEAT_ROWS
  return (
    o.version === 1 &&
    (o.sides === 'known' || o.sides === 'unknown') &&
    num(o.seconds) &&
    !!o.possession &&
    num((o.possession as Record<string, unknown>).ours) &&
    num((o.possession as Record<string, unknown>).theirs) &&
    Array.isArray(o.territory) &&
    o.territory.length === 3 &&
    o.territory.every(num) &&
    !!o.shape &&
    shape((o.shape as Record<string, unknown>).in) &&
    shape((o.shape as Record<string, unknown>).out) &&
    !!o.theirShape &&
    shape((o.theirShape as Record<string, unknown>).in) &&
    shape((o.theirShape as Record<string, unknown>).out) &&
    !!heat &&
    ['ball', 'ours', 'theirs'].every((k) => Array.isArray(heat[k]) && (heat[k] as unknown[]).length === cells && (heat[k] as unknown[]).every(num)) &&
    Array.isArray(o.events) &&
    o.events.length <= 3000 &&
    o.events.every((e) => !!e && typeof e === 'object' && ['turnover', 'shot', 'kickoff'].includes((e as PitchEvent).kind) && num((e as PitchEvent).t) && num((e as PitchEvent).x) && num((e as PitchEvent).y)) &&
    Array.isArray(o.goals) &&
    o.goals.length <= 40 &&
    o.goals.every(
      (g) =>
        !!g &&
        typeof g === 'object' &&
        typeof (g as PitchGoal).for === 'boolean' &&
        Array.isArray((g as PitchGoal).frames) &&
        (g as PitchGoal).frames.length <= 80 &&
        (g as PitchGoal).frames.every((f) => !!f && num(f.t) && pts(f.ours, 16) && pts(f.theirs, 16) && (f.ball === null || pt(f.ball))),
    )
  )
}
