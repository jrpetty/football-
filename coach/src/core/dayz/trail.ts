// Where the player has been, life by life: the towns of the road signs read
// off the screen, in order, and where each life ended (the last place read,
// when it ended in death). Kept in memory for the session; shown on the map.

import { findPlace, type DayzMapId } from './places.ts'

export interface TrailStop {
  readonly name: string
  readonly x: number
  readonly y: number
  readonly at: number
}

export interface TrailLife {
  readonly id: string
  readonly map: DayzMapId
  readonly startedAt: number
  readonly stops: readonly TrailStop[]
  /** Set when the life is over: in death (near the last place read), or the session stopped. */
  readonly end: { readonly died: boolean; readonly near: string | null; readonly at: number } | null
}

/**
 * One step: a sign read (or the same one again), and whether a life just ended. A new sign adds its
 * town to the current life (a new life starts when there is none); the end of a match closes it.
 */
export function trailStep(lives: readonly TrailLife[], input: { readonly sign: string | null; readonly prefer: DayzMapId | null; readonly lifeEnded: { readonly died: boolean } | null; readonly now: number }): readonly TrailLife[] {
  let out = lives
  const open = out.at(-1)?.end === null ? out.at(-1)! : null
  if (input.sign) {
    const p = findPlace(input.sign, input.prefer)
    if (p) {
      const last = open?.stops.at(-1)
      // After a death the last sign read is still the old one: not the start of a new life.
      const stale = !open && out.at(-1)?.stops.at(-1)?.name === p.place.name
      if (!stale && (!open || open.map !== p.map.id)) {
        const closed = open ? out.slice(0, -1).concat({ ...open, end: { died: false, near: open.stops.at(-1)?.name ?? null, at: input.now } }) : out
        out = closed.concat({ id: `life-${input.now}`, map: p.map.id, startedAt: input.now, stops: [{ name: p.place.name, x: p.place.x, y: p.place.y, at: input.now }], end: null })
      } else if (!stale && open && last?.name !== p.place.name) {
        out = out.slice(0, -1).concat({ ...open, stops: [...open.stops, { name: p.place.name, x: p.place.x, y: p.place.y, at: input.now }] })
      }
    }
  }
  if (input.lifeEnded) {
    const cur = out.at(-1)?.end === null ? out.at(-1)! : null
    if (cur) out = out.slice(0, -1).concat({ ...cur, end: { died: input.lifeEnded.died, near: cur.stops.at(-1)?.name ?? null, at: input.now } })
  }
  return out
}

/** One line per life for the list under the map. */
export function lifeLine(life: TrailLife, index: number): string {
  const route = life.stops.map((s) => s.name).join(' → ')
  const end = life.end ? (life.end.died ? `, died near ${life.end.near ?? 'an unknown place'}` : '') : ' (this life)'
  return `Life ${index + 1}: ${route || 'no signs read'}${end}.`
}
