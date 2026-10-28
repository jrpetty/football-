// What the radar says about a player's football, in plain lines with the
// playbook entry that goes with each: how many players were behind the ball
// when goals went in, how high the back line held, how stretched the team
// was without the ball, where the ball was lost and how quickly it came back,
// width with the ball, and where shots came from. Only with enough evidence,
// only about the player's own team (when it is known), never a guess about
// what the radar did not show.

import { PITCH_LENGTH as L, PITCH_WIDTH as WD } from './radar.ts'
import type { PitchMatchRecord, ShapeStats } from './tracker.ts'

export interface PitchInsight {
  readonly id: string
  readonly text: string
  readonly playbook?: string
  readonly tags: readonly string[]
}

/** Block heights by the back line's distance from its own goal (metres), as analysts use them. */
export const BLOCKS = { low: 25, high: 40 } as const

const pct = (k: number, n: number) => `${Math.round((100 * k) / Math.max(1, n))}%`
const m = (v: number) => `${Math.round(v)} m`

/** Lines for one match or several (oldest first); `focus`: only that one. */
export function pitchInsights(records: readonly PitchMatchRecord[], focus: number | null = null, max = 6): PitchInsight[] {
  const rs = (focus === null ? records : records.slice(focus, focus + 1)).filter((r) => r.sides === 'known')
  if (!rs.length) return []
  const out: PitchInsight[] = []
  const goalsAgainst = rs.flatMap((r) => r.goals.filter((g) => !g.for && g.defending))
  // Players behind the ball when goals went in.
  if (goalsAgainst.length >= 2) {
    const few = goalsAgainst.filter((g) => g.defending!.behindBall <= 4)
    if (few.length >= 2 && few.length / goalsAgainst.length >= 0.5) {
      out.push({
        id: 'behind-ball',
        text: `On ${few.length} of the ${goalsAgainst.length} goals you conceded, 4 or fewer of your outfield players were behind the ball at the shot. Keep five or more back when you lose the ball high up.`,
        playbook: 'dont-break-the-line',
        tags: ['defending', 'shape', 'conceding'],
      })
    }
    // A line much higher than usual on the goals against.
    const out0 = mergeShape(rs.map((r) => r.shape.out))
    if (out0) {
      const lines = goalsAgainst.map((g) => g.defending!.line)
      const avg = lines.reduce((s, v) => s + v, 0) / lines.length
      if (avg - out0.line >= 8)
        out.push({
          id: 'line-on-goals',
          text: `Your back line was ${m(avg)} from your goal on the goals against, ${m(avg - out0.line)} higher than it usually held without the ball (${m(out0.line)}). Drop deeper when they win it in midfield.`,
          playbook: 'line-height-tuning',
          tags: ['defending', 'line', 'conceding'],
        })
    }
  }
  const outShape = mergeShape(rs.map((r) => r.shape.out))
  const inShape = mergeShape(rs.map((r) => r.shape.in))
  if (outShape && outShape.frames >= 60) {
    const block = outShape.line < BLOCKS.low ? 'a low block' : outShape.line > BLOCKS.high ? 'a high line' : 'a mid block'
    out.push({
      id: 'block',
      text: `Without the ball your back line held about ${m(outShape.line)} from your goal (${block}), and the team was ${m(outShape.length)} long and ${m(outShape.width)} wide${outShape.formation ? `, in a ${outShape.formation} shape` : ''}.`,
      tags: ['defending', 'shape'],
    })
    if (outShape.length >= 45)
      out.push({
        id: 'stretched',
        text: `Without the ball your team was stretched: ${m(outShape.length)} from the deepest to the highest outfield player. Good defensive blocks stay around 35 m long, so the gaps between the lines stay small.`,
        playbook: 'dont-break-the-line',
        tags: ['defending', 'compactness'],
      })
  }
  if (inShape && inShape.frames >= 60 && inShape.width < 38)
    out.push({
      id: 'narrow',
      text: `With the ball your team was only ${m(inShape.width)} wide. Keep a player on each touchline to stretch them and open the middle.`,
      playbook: 'width-and-switch',
      tags: ['attacking', 'width'],
    })
  // Where the ball was lost, and what came of it.
  const lost = rs.flatMap((r, i) => r.events.filter((e) => e.kind === 'turnover' && e.team === 'ours' && !e.out).map((e) => ({ ...e, match: i })))
  const ownThird = lost.filter((e) => e.x < L / 3)
  if (ownThird.length >= 3) {
    const punished = goalsAgainst.length
      ? rs.reduce((n, r) => n + r.goals.filter((g) => !g.for && r.events.some((e) => e.kind === 'turnover' && e.team === 'ours' && !e.out && e.x < L / 3 && g.t - e.t >= 0 && g.t - e.t <= 20)).length, 0)
      : 0
    out.push({
      id: 'own-third',
      text: `You lost the ball ${ownThird.length} times in your own third${punished ? `; ${punished} of the goals against came within 20 s of one` : ''}. Play the safe pass out of your third, or clear it.`,
      playbook: 'safe-vs-risky-zones',
      tags: ['possession', 'turnovers'],
    })
  }
  if (lost.length >= 8) {
    const quick = lost.filter((e) => e.regainSec !== undefined && e.regainSec <= 5).length
    out.push({
      id: 'regain',
      text: `You won the ball back within 5 seconds after ${pct(quick, lost.length)} of the times you lost it.`,
      playbook: 'press-dont-spam',
      tags: ['pressing', 'transition'],
    })
  }
  // Possession and territory as the radar saw them.
  const ours = rs.reduce((s, r) => s + r.possession.ours, 0)
  const theirs = rs.reduce((s, r) => s + r.possession.theirs, 0)
  const terr = rs.reduce((a, r) => [a[0] + r.territory[0], a[1] + r.territory[1], a[2] + r.territory[2]], [0, 0, 0])
  if (ours + theirs >= 60)
    out.push({
      id: 'possession',
      text: `On the radar you had the ball ${pct(ours, ours + theirs)} of the time; it spent ${pct(terr[2], terr[0] + terr[1] + terr[2])} of the match in their third and ${pct(terr[0], terr[0] + terr[1] + terr[2])} in yours.`,
      tags: ['possession', 'territory'],
    })
  // Shots by distance.
  const shots = rs.flatMap((r) => r.events.filter((e) => e.kind === 'shot' && e.team === 'ours'))
  if (shots.length >= 5) {
    const outside = shots.filter((e) => L - e.x > 16.5 || Math.abs(e.y - WD / 2) > 20.16).length
    if (outside / shots.length >= 0.5)
      out.push({
        id: 'shot-distance',
        text: `${outside} of your ${shots.length} shots on the radar came from outside the box. Work it into the box or to a cutback before shooting.`,
        playbook: 'shot-selection',
        tags: ['attacking', 'shooting'],
      })
  }
  return out.slice(0, max)
}

/** Several matches' shapes averaged by their frames. */
export function mergeShape(list: readonly (ShapeStats | null)[]): ShapeStats | null {
  const xs = list.filter((x): x is ShapeStats => !!x && x.frames > 0)
  if (!xs.length) return null
  if (xs.length === 1) return xs[0]
  const n = xs.reduce((s, x) => s + x.frames, 0)
  const avg = (k: 'line' | 'backLine' | 'length' | 'width' | 'compact' | 'behindBall') => Math.round((xs.reduce((s, x) => s + x[k] * x.frames, 0) / n) * 10) / 10
  const last = xs[xs.length - 1]
  return {
    frames: n,
    line: avg('line'),
    backLine: avg('backLine'),
    length: avg('length'),
    width: avg('width'),
    compact: avg('compact'),
    behindBall: avg('behindBall'),
    lineHist: last.lineHist.map((_, i) => xs.reduce((s, x) => s + (x.lineHist[i] ?? 0), 0)),
    slots: last.slots,
    formation: last.formation,
  }
}
