// A synthetic football match for the EA SPORTS FC demo and tests: 22 players
// in two formations that shift with the ball, spells of possession with
// passes forward, turnovers, shots, goals at the times the scoreboard says,
// kick-offs after them, the radar hidden while a goal is celebrated, and the
// teams changing ends at half-time. Team 0 is the player's. Before the goals
// it concedes, its team is pushed up with few players behind the ball (as a
// coach would want the radar to show). Not a real match.

import { PITCH_LENGTH as L, PITCH_WIDTH as WD } from '../core/pitch/radar.ts'
import { mulberry32 } from './syntheticArena.ts'
import { drawRadar, type RadarScene } from './fcRadar.ts'

type P = [number, number]

/** Formations, own goal on the left (x = 0), goalkeeper first. */
const FORMATIONS: Record<string, P[]> = {
  '4-2-3-1': [
    [4, 34],
    [18, 10], [16, 26], [16, 42], [18, 58],
    [32, 26], [32, 42],
    [50, 12], [48, 34], [50, 56],
    [64, 34],
  ],
  '4-3-3': [
    [4, 34],
    [18, 10], [16, 26], [16, 42], [18, 58],
    [34, 20], [30, 34], [34, 48],
    [58, 12], [64, 34], [58, 56],
  ],
}

export interface FcMatchScript {
  readonly seed: number
  /** Real ms of each goal going in, and who scored (team 0 = the player). */
  readonly goals: readonly { readonly at: number; readonly team: 0 | 1 }[]
  readonly halfTimeAt: number
  readonly secondHalfAt: number
  readonly endAt: number
}

export interface FcMatchSim {
  /** The radar at a time (null while it is hidden: before kick-off, celebrations, half-time, after the end). */
  sceneAt(t: number): RadarScene | null
  /** Which end team 0 defends at a time (radar left or right). */
  defends(t: number): 'left' | 'right'
  /** The team on the ball at a time (truth, for tests). */
  ownerAt(t: number): 0 | 1 | null
}

const STEP = 100

export function fcMatchSim(script: FcMatchScript): FcMatchSim {
  const rnd = mulberry32(script.seed ^ 0xf00d)
  const base = [FORMATIONS['4-2-3-1'], FORMATIONS['4-3-3']]
  // Positions in each team's own frame: own goal at x = 0, attacking right.
  const pos: P[][] = base.map((f) => f.map((p) => [p[0] * 0.5, p[1]] as P))
  let ball: P = [L / 2, WD / 2]
  let target: P | null = null
  let owner = { team: 0 as 0 | 1, player: 10 }
  let spellEnds = 8000
  let nextPass = 1500
  const scenes: (RadarScene | null)[] = []
  const flips: boolean[] = []
  const owners: (0 | 1)[] = []
  const goals = [...script.goals].sort((a, b) => a.at - b.at)
  // Team frame → radar frame: team 0 defends left in the first half.
  const firstHalf = (t: number) => t < script.halfTimeAt
  const toRadar = (team: 0 | 1, p: P, t: number): P => {
    const t0Left = firstHalf(t)
    const leftTeam = t0Left ? 0 : 1
    return team === leftTeam ? [p[0], p[1]] : [L - p[0], WD - p[1]]
  }
  const fromRadar = (team: 0 | 1, p: P, t: number): P => toRadar(team, p, t)
  let hiddenUntil = 0
  let kickoffAt = 0
  let lastGoal = -1
  for (let t = 0; t <= script.endAt; t += STEP) {
    // Hidden: before kick-off, celebrations and replays, half-time, after the end.
    const g = goals.findIndex((x, i) => i > lastGoal && t >= x.at)
    if (g >= 0) {
      lastGoal = g
      hiddenUntil = goals[g].at + 6000
      kickoffAt = hiddenUntil + 1800
      owner = { team: goals[g].team === 0 ? 1 : 0, player: 10 }
    }
    if (t >= script.halfTimeAt && t < script.secondHalfAt) {
      scenes.push(null)
      flips.push(false)
      kickoffAt = script.secondHalfAt + 1800
      hiddenUntil = script.secondHalfAt
      owner = { team: 1, player: 10 }
      continue
    }
    if (t < hiddenUntil || t > script.endAt - 500) {
      scenes.push(null)
      flips.push(false)
      continue
    }
    const goalNext = goals.find((x) => x.at > t)
    const toGoal = goalNext ? goalNext.at - t : Infinity
    if (t < kickoffAt || (t < 2000 && kickoffAt === 0)) {
      // Kick-off: everyone in their own half, the ball still on the spot.
      for (const team of [0, 1] as const)
        base[team].forEach((b, i) => {
          pos[team][i] = [Math.min(b[0] * 0.75, L / 2 - 2), b[1]]
        })
      pos[owner.team][10] = [L / 2 - 1.2, WD / 2]
      ball = [L / 2, WD / 2]
      target = null
      spellEnds = t + 6000 + rnd() * 6000
      nextPass = t + 2000
    } else {
      // A scripted goal: the scorer has the ball from 10 s before, attacks fast, shoots 1.2 s before.
      if (toGoal <= 10_000 && goalNext) {
        if (owner.team !== goalNext.team) {
          // The scoring team wins the ball where it is (its nearest player).
          const b = fromRadar(goalNext.team, ball, t)
          let best = 6
          let bd = Infinity
          pos[goalNext.team].forEach((p, i) => {
            const d = i ? Math.hypot(p[0] - b[0], p[1] - b[1]) : Infinity
            if (d < bd) {
              bd = d
              best = i
            }
          })
          pos[goalNext.team][best] = [b[0] - 0.6, b[1]]
          owner = { team: goalNext.team, player: best }
          target = null
        }
        spellEnds = goalNext.at + 1000
        nextPass = goalNext.at + 5000
      }
      // The ball, in the owner's frame.
      let own = fromRadar(owner.team, ball, t)
      if (target) {
        const dx = target[0] - own[0]
        const dy = target[1] - own[1]
        const d = Math.hypot(dx, dy)
        const speed = toGoal <= 1400 ? 26 : 16
        const step = (speed * STEP) / 1000
        if (d <= step) {
          own = target
          target = null
        } else own = [own[0] + (dx / d) * step, own[1] + (dy / d) * step]
      } else {
        // Dribbling forward with the carrier.
        const c = pos[owner.team][owner.player]
        own = [c[0] + 0.8, c[1]]
      }
      if (goalNext && toGoal <= 1400 && toGoal > 1300 && !target) {
        // The shot.
        target = [L + 1, WD / 2 + (rnd() - 0.5) * 5]
      } else if (!target && t >= nextPass && toGoal > 1400) {
        // A pass to a teammate ahead (or sideways).
        const mates = pos[owner.team].map((p, i) => ({ p, i })).filter((m) => m.i !== owner.player && m.i !== 0)
        const ahead = mates.filter((m) => m.p[0] > own[0] - 6)
        const pick = (ahead.length ? ahead : mates)[Math.floor(rnd() * (ahead.length ? ahead.length : mates.length))]
        target = [pick.p[0] + 2, pick.p[1]]
        owner = { team: owner.team, player: pick.i }
        nextPass = t + 1500 + rnd() * 2500
      }
      ball = toRadar(owner.team, own, t)
      // End of a spell: the nearest opponent wins it.
      if (t >= spellEnds && !target && toGoal > 10_000) {
        const opp: 0 | 1 = owner.team === 0 ? 1 : 0
        const ballOpp = fromRadar(opp, ball, t)
        let best = 1
        let bd = Infinity
        pos[opp].forEach((p, i) => {
          if (!i) return
          const d = Math.hypot(p[0] - ballOpp[0], p[1] - ballOpp[1])
          if (d < bd) {
            bd = d
            best = i
          }
        })
        // The winner steps onto the ball.
        pos[opp][best] = [ballOpp[0] - 0.6, ballOpp[1]]
        owner = { team: opp, player: best }
        spellEnds = t + 5000 + rnd() * 9000
        nextPass = t + 900
      }
      // Everyone moves towards their place in the shape around the ball.
      for (const team of [0, 1] as const) {
        const b = fromRadar(team, ball, t)
        const has = owner.team === team
        // Before a goal it concedes, the player's team is pushed up with few players behind the ball.
        const exposed = team === 0 && goalNext?.team === 1 && toGoal <= 10_000
        base[team].forEach((f, i) => {
          let tx: number
          let ty: number
          if (i === 0) {
            tx = 4 + Math.max(0, (b[0] - 50) * 0.12)
            ty = WD / 2 + (b[1] - WD / 2) * 0.15
          } else if (has) {
            tx = f[0] + (b[0] - 40) * 0.55 + 8
            ty = f[1] + (b[1] - WD / 2) * 0.25
          } else {
            tx = f[0] * 0.85 + (b[0] - 52) * 0.5 + (exposed ? 22 : 0)
            ty = WD / 2 + (f[1] - WD / 2) * 0.78 + (b[1] - WD / 2) * 0.3
          }
          if (has && i === owner.player && !target) {
            // The carrier runs at the box when a goal is coming, else forward.
            const attack = goalNext?.team === team && toGoal <= 10_000
            tx = attack ? L - 15 : pos[team][i][0] + 6
            ty = attack ? WD / 2 + (i % 2 ? 6 : -6) : pos[team][i][1]
          }
          tx = Math.max(1, Math.min(L - 1, tx))
          ty = Math.max(1, Math.min(WD - 1, ty))
          const cur = pos[team][i]
          const dx = tx - cur[0]
          const dy = ty - cur[1]
          const d = Math.hypot(dx, dy)
          const step = ((has && i === owner.player ? (goalNext?.team === team && toGoal <= 10_000 ? 8.5 : 6) : 7) * STEP) / 1000
          pos[team][i] = d <= step ? [tx, ty] : [cur[0] + (dx / d) * step, cur[1] + (dy / d) * step]
        })
      }
    }
    scenes.push({ players: ([0, 1] as const).flatMap((team) => pos[team].map((p) => ({ ...pointOf(toRadar(team, p, t)), team }))), ball: { x: ball[0], y: ball[1] } })
    owners[scenes.length - 1] = owner.team
    flips.push(!firstHalf(t))
  }
  return {
    sceneAt(t) {
      const i = Math.round(t / STEP)
      return i >= 0 && i < scenes.length ? scenes[i] : null
    },
    defends(t) {
      return firstHalf(t) ? 'left' : 'right'
    },
    ownerAt(t) {
      const i = Math.round(t / STEP)
      return scenes[i] ? (owners[i] ?? null) : null
    },
  }
}

function pointOf(p: P): { x: number; y: number } {
  return { x: p[0], y: p[1] }
}

/** The EA SPORTS FC demo's picture: a dark "match" behind the radar in its usual place, in step with the replay. */
export function fcDemoPicture(
  football: { readonly goals: readonly { readonly at: number; readonly team: number }[]; readonly halfTimeAt: number; readonly secondHalfAt: number; readonly endAt: number },
  opts: { readonly seed: number; readonly region: { readonly x: number; readonly y: number; readonly w: number; readonly h: number }; readonly colours: readonly [readonly [number, number, number], readonly [number, number, number]] },
): { frame(t: number, width?: number, height?: number): { width: number; height: number; data: Uint8ClampedArray; timestamp: number }; sim: FcMatchSim } {
  const sim = fcMatchSim({ seed: opts.seed, goals: football.goals.map((g) => ({ at: g.at, team: g.team === 0 ? 0 : 1 })), halfTimeAt: football.halfTimeAt, secondHalfAt: football.secondHalfAt, endAt: football.endAt })
  return {
    sim,
    frame(t, width = 1280, height = 720) {
      const data = new Uint8ClampedArray(width * height * 4)
      // "Gameplay": a grass-coloured scene that keeps changing.
      const shift = (t / 40) | 0
      for (let y = 0; y < height; y++)
        for (let x = 0; x < width; x++) {
          const o = (y * width + x) * 4
          const stripe = ((x + shift) >> 6) & 1
          const n = ((x * 7 + y * 13 + shift) & 15) - 8
          data[o] = 38 + n
          data[o + 1] = (stripe ? 96 : 84) + n
          data[o + 2] = 44 + n
          data[o + 3] = 255
        }
      const scene = sim.sceneAt(t)
      if (scene) {
        const box = { x: Math.round(opts.region.x * width), y: Math.round(opts.region.y * height), w: Math.round(opts.region.w * width), h: Math.round(opts.region.h * height) }
        drawRadar(data, width, box, scene, { colours: opts.colours })
      }
      return { width, height, data, timestamp: t }
    },
  }
}
