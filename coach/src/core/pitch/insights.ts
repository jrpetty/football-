// What the radar says about a player's football, in plain lines with the
// playbook entry that goes with each: how many players were behind the ball
// when goals went in, how high the back line held, how long and how stretched
// between the lines the team was without the ball, how often someone was
// pressing, where the ball was lost and how quickly it came back, width with
// the ball, where shots came from, and the shapes both teams played with what
// the pack knows about beating them. The yardsticks are real-football tracking
// figures (FC publishes none), so they are context, not grades. Only with
// enough evidence, only about the player's own team and the opponent's shape
// (when the teams are known), never a guess about what the radar did not show.

import { PITCH_LENGTH as L, PITCH_WIDTH as WD } from './radar.ts'
import { shapesIn } from './formations.ts'
import type { PitchMatchRecord, ShapeStats } from './tracker.ts'
import type { FormationEntry } from '../profile/schema.ts'

export interface PitchInsight {
  readonly id: string
  readonly text: string
  readonly playbook?: string
  /** The pack's formation entry the line is about. */
  readonly formation?: string
  readonly tags: readonly string[]
}

/**
 * Block heights by the deepest outfield player's distance from their own goal (metres): low
 * blocks sit inside about 35 m, mid blocks 35 to 45 m, higher is a high line.
 */
export const BLOCKS = { low: 35, high: 45 } as const
/** Team length (deepest to highest outfield player) across phases in professional matches: 31 to 46 m. */
export const TEAM_LENGTH = { min: 31, max: 46 } as const
/** Team width across phases: 35 to 48 m (wider with the ball). */
export const TEAM_WIDTH = { min: 35, max: 48 } as const
/** Back line to midfield line when defending: about 10 m in the Bundesliga. */
export const LINE_GAP = 10

const pct = (k: number, n: number) => `${Math.round((100 * k) / Math.max(1, n))}%`
const m = (v: number) => `${Math.round(v)} m`
/** A list item as part of a sentence: no closing stop, and lower case unless it starts with a name or a number. */
const clause = (t: string) => {
  const x = t.trim().replace(/[.;]+$/, '')
  return /^[A-Z][a-z]/.test(x) && !/^(I|FC|EA|CPU)\b/.test(x) ? x[0].toLowerCase() + x.slice(1) : x
}

/** Rough expected goals for a shot from a place (own goal at x = 0, attacking x = 105), by real-football zone averages. */
export function zoneXg(x: number, y: number): number {
  const dx = L - x
  const dy = Math.abs(y - WD / 2)
  if (dx <= 5.5 && dy <= 9.16) return 0.35
  if (dx <= 11 && dy <= 9.16) return 0.2
  if (dx <= 16.5 && dy <= 20.16) return dy <= 9.16 ? 0.12 : 0.06
  if (dx <= 25 && dy <= 20.16) return 0.05
  return 0.03
}

/** The pack's entries for a shape read from the radar (an entry naming several shapes counts for each). */
export function formationEntries(shape: string, formations: readonly FormationEntry[] = []): FormationEntry[] {
  return shape ? formations.filter((f) => shapesIn(f.formation).includes(shape)) : []
}

/** Lines for one match or several (oldest first); `focus`: only that one. */
export function pitchInsights(records: readonly PitchMatchRecord[], focus: number | null = null, max = 8, formations: readonly FormationEntry[] = []): PitchInsight[] {
  const rs = (focus === null ? records : records.slice(focus, focus + 1)).filter((r) => r.sides === 'known')
  if (!rs.length) return []
  const out: PitchInsight[] = []
  const goalsAgainst = rs.flatMap((r) => r.goals.filter((g) => !g.for && g.defending))
  const outShape = mergeShape(rs.map((r) => r.shape.out))
  const inShape = mergeShape(rs.map((r) => r.shape.in))
  // Players behind the ball when goals went in.
  if (goalsAgainst.length >= 2) {
    const few = goalsAgainst.filter((g) => g.defending!.behindBall <= 4)
    if (few.length >= 2 && few.length / goalsAgainst.length >= 0.5) {
      out.push({
        id: 'behind-ball',
        text: `On ${few.length} of the ${goalsAgainst.length} goals you conceded, 4 or fewer of your outfield players were behind the ball at the shot. While you attack, keep one more player back than they have forwards up the pitch.`,
        playbook: 'rest-defence-plus-one',
        tags: ['defending', 'shape', 'conceding', 'transition'],
      })
    }
    // A line much higher than usual on the goals against.
    if (outShape) {
      const lines = goalsAgainst.map((g) => g.defending!.line)
      const avg = lines.reduce((s, v) => s + v, 0) / lines.length
      if (avg - outShape.line >= 8)
        out.push({
          id: 'line-on-goals',
          text: `Your back line was ${m(avg)} from your goal on the goals against, ${m(avg - outShape.line)} higher than it usually held without the ball (${m(outShape.line)}). Drop deeper when they win it in midfield.`,
          playbook: 'line-height-tuning',
          tags: ['defending', 'line', 'conceding'],
        })
    }
  }
  if (outShape && outShape.frames >= 60) {
    const block = outShape.line < BLOCKS.low ? 'a low block' : outShape.line > BLOCKS.high ? 'a high line' : 'a mid block'
    const press = outShape.press !== undefined ? ` Someone was within 5 m of the ball ${pct(outShape.press, 1)} of the time they had it.` : ''
    out.push({
      id: 'block',
      text: `Without the ball your deepest outfield player held about ${m(outShape.line)} from your goal (${block}), and the team was ${m(outShape.length)} long and ${m(outShape.width)} wide${outShape.formation ? `, in a ${outShape.formation} shape` : ''}.${press}`,
      tags: ['defending', 'shape'],
    })
    if (outShape.length > TEAM_LENGTH.max)
      out.push({
        id: 'stretched',
        text: `Without the ball your team was stretched: ${m(outShape.length)} from the deepest to the highest outfield player. Professional teams stay about ${TEAM_LENGTH.min} to ${TEAM_LENGTH.max} m long, so the gaps between the lines stay small.`,
        playbook: 'compact-block-distances',
        tags: ['defending', 'compactness'],
      })
    else if (outShape.gap !== undefined && outShape.gap >= LINE_GAP + 5)
      out.push({
        id: 'line-gap',
        text: `Without the ball your midfield sat about ${m(outShape.gap)} in front of your back line. Well-organised teams keep that gap near ${LINE_GAP} m, so there is little room to pass into between the lines.`,
        playbook: 'compact-block-distances',
        tags: ['defending', 'compactness'],
      })
    if (outShape.press !== undefined && outShape.press < 0.2 && outShape.line > BLOCKS.low)
      out.push({
        id: 'passive',
        text: `When they had the ball, none of your players was within 5 m of it ${pct(1 - outShape.press, 1)} of the time, even with your line up at ${m(outShape.line)}. A high line without pressure leaves time for the ball over the top: step up on a back pass or a heavy touch.`,
        playbook: 'press-on-triggers',
        tags: ['pressing', 'defending'],
      })
  }
  if (inShape && inShape.frames >= 60 && inShape.width < TEAM_WIDTH.min)
    out.push({
      id: 'narrow',
      text: `With the ball your team was only ${m(inShape.width)} wide; teams usually spread ${TEAM_WIDTH.min} to ${TEAM_WIDTH.max} m. Keep a player on each touchline to stretch them and open the middle.`,
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
      playbook: 'counter-press-then-recover',
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
  // Shots by distance, with a rough value by zone.
  const shots = rs.flatMap((r) => r.events.filter((e) => e.kind === 'shot' && e.team === 'ours'))
  if (shots.length >= 5) {
    const outside = shots.filter((e) => L - e.x > 16.5 || Math.abs(e.y - WD / 2) > 20.16).length
    const xg = shots.reduce((s, e) => s + zoneXg(e.x, e.y), 0)
    if (outside / shots.length >= 0.5)
      out.push({
        id: 'shot-distance',
        text: `${outside} of your ${shots.length} shots on the radar came from outside the box. By real-football averages for where they were taken, those ${shots.length} shots were worth about ${xg.toFixed(1)} goals; work it into the box or to a cutback first.`,
        playbook: 'long-shot-discipline',
        tags: ['attacking', 'shooting'],
      })
  }
  // The opponent's shape, and what the pack knows about beating it.
  const theirOut = mergeShape(rs.map((r) => r.theirShape.out))
  if (theirOut && theirOut.frames >= 60 && theirOut.formation) {
    const entry = formationEntries(theirOut.formation, formations)[0]
    const tips = entry ? entry.counters.slice(0, 2).map(clause).join('; ') : ''
    out.push({
      id: 'their-shape',
      text: `They defended in a ${theirOut.formation} shape with their deepest player about ${m(theirOut.line)} from their goal${theirOut.line > BLOCKS.high ? ' (a high line: space in behind)' : ''}.${tips ? ` Against it: ${tips}.` : ''}`,
      ...(entry ? { formation: entry.id } : {}),
      ...(theirOut.line > BLOCKS.high ? { playbook: 'beat-the-high-line' } : {}),
      tags: ['opponent', 'shape', 'attacking'],
    })
  }
  // The player's own defending shape, when goals went in: what the pack says it is weak against.
  if (outShape?.formation && goalsAgainst.length >= 2) {
    const entry = formationEntries(outShape.formation, formations)[0]
    if (entry && entry.weaknesses.length)
      out.push({
        id: 'our-shape',
        text: `You defended in a ${outShape.formation} shape. Its known weak spots: ${entry.weaknesses.slice(0, 2).map(clause).join('; ')}. Check whether the goals against came that way.`,
        formation: entry.id,
        tags: ['shape', 'defending'],
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
  const opt = (k: 'gap' | 'press') => {
    const has = xs.filter((x) => x[k] !== undefined)
    const hn = has.reduce((s, x) => s + x.frames, 0)
    return hn ? Math.round((has.reduce((s, x) => s + x[k]! * x.frames, 0) / hn) * 100) / 100 : undefined
  }
  const last = xs[xs.length - 1]
  const gap = opt('gap')
  const press = opt('press')
  return {
    frames: n,
    line: avg('line'),
    backLine: avg('backLine'),
    length: avg('length'),
    width: avg('width'),
    compact: avg('compact'),
    behindBall: avg('behindBall'),
    ...(gap !== undefined ? { gap } : {}),
    ...(press !== undefined ? { press } : {}),
    lineHist: last.lineHist.map((_, i) => xs.reduce((s, x) => s + (x.lineHist[i] ?? 0), 0)),
    slots: last.slots,
    formation: last.formation,
  }
}
