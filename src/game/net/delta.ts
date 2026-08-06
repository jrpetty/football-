import type { Snapshot, SnapDelta, SnapPlayer } from './protocol'

// Snapshots as differences.
//
// The host was sending the entire world twenty times a second: every player's
// position, heading, six pieces of animation state and their stamina, whether
// any of it had changed or not, as JSON floats with three decimal places. At
// eight players that is around 39 KB/s, and almost all of it is bytes the
// receiver already had.
//
// Two changes, in this order, because the second depends on the first:
//
//   Quantise. Positions to the centimetre, angles to a milliradian, everything
//     else to a thousandth. Nobody can see finer than that, and "29.123" is six
//     characters where 2912 is four. More importantly it makes values *stable*:
//     a standing player's floating-point x jitters in the seventh decimal
//     forever, so it never compares equal and can never be omitted.
//
//   Difference. Send only the fields that changed, only for the players that
//     changed anything, plus a bitmask saying which. A player standing still
//     disappears from the wire entirely; a player running produces three
//     numbers instead of eleven, because their stamina and animation state are
//     the same as they were a twentieth of a second ago.
//
// Deltas chain, so they need something to chain from: every NET.keyframeEvery
// snapshots the host sends a whole world instead, which is also what a client
// joining mid-match waits for. The link underneath is ordered and reliable
// (see transport.ts), so a client that has a keyframe can trust that the
// deltas after it arrive in order and none are missing.

// The flat, quantised form. Index order is fixed and shared by both sides — it
// is the wire format, so it doesn't change without a protocol bump.
export const BALL_FIELDS = 8 // x y z vx vy vz spin vSpin
export const PLAYER_FIELDS = 10 // x y h st kt kk kp kl leg en

export interface Frame {
  b: number[]
  p: Map<number, number[]>
  sc: [number, number]
  ph: string
  cl: number
  pos: number | null
}

// Scale per field, so unpacking is the same table read backwards. Centimetres
// for anything in metres, milliradians for the heading, thousandths for the
// timers and stamina, and 1 for the things that are already whole numbers.
const BALL_SCALE = [100, 100, 100, 100, 100, 100, 100, 100]
const PLAYER_SCALE = [100, 100, 1000, 1, 1000, 1, 1000, 1000, 1, 1000]

const G_SCORE = 1
const G_PHASE = 2
const G_CLOCK = 4
const G_POS = 8

export function toFrame(s: Snapshot): Frame {
  const b = [s.b.x, s.b.y, s.b.z, s.b.vx, s.b.vy, s.b.vz, s.b.sp, s.b.vs].map((v, i) =>
    Math.round(v * BALL_SCALE[i]),
  )
  const p = new Map<number, number[]>()
  for (const q of s.p) {
    p.set(
      q.id,
      [q.x, q.y, q.h, q.st, q.kt, q.kk, q.kp, q.kl, q.leg, q.en].map((v, i) =>
        Math.round(v * PLAYER_SCALE[i]),
      ),
    )
  }
  return { b, p, sc: [s.sc[0], s.sc[1]], ph: s.ph, cl: Math.round(s.cl * 1000), pos: s.pos }
}

export function fromFrame(f: Frame): Snapshot {
  const b = f.b.map((v, i) => v / BALL_SCALE[i])
  const p: SnapPlayer[] = []
  for (const [id, q] of f.p) {
    const v = q.map((n, i) => n / PLAYER_SCALE[i])
    p.push({
      id,
      x: v[0], y: v[1], h: v[2],
      st: v[3], kt: v[4], kk: v[5], kp: v[6], kl: v[7], leg: v[8], en: v[9],
    })
  }
  return {
    b: { x: b[0], y: b[1], z: b[2], vx: b[3], vy: b[4], vz: b[5], sp: b[6], vs: b[7] },
    p,
    sc: [f.sc[0], f.sc[1]],
    ph: f.ph,
    cl: f.cl / 1000,
    pos: f.pos,
  }
}

// What changed between two frames. `from` is the tick the receiver is expected
// to already hold.
export function diff(prev: Frame, next: Frame, fromTick: number): SnapDelta {
  const b: number[] = [0]
  for (let i = 0; i < BALL_FIELDS; i++) {
    if (next.b[i] !== prev.b[i]) {
      b[0] |= 1 << i
      b.push(next.b[i])
    }
  }

  const p: number[][] = []
  for (const [id, cur] of next.p) {
    const was = prev.p.get(id)
    // A player the receiver has never seen gets every field, not a difference
    // from a baseline that doesn't exist.
    const row: number[] = [id, 0]
    for (let i = 0; i < PLAYER_FIELDS; i++) {
      if (!was || cur[i] !== was[i]) {
        row[1] |= 1 << i
        row.push(cur[i])
      }
    }
    if (row[1] !== 0) p.push(row)
  }

  let g = 0
  const out: SnapDelta = { f: fromTick, b, p, g: 0 }
  if (next.sc[0] !== prev.sc[0] || next.sc[1] !== prev.sc[1]) {
    g |= G_SCORE
    out.sc = [next.sc[0], next.sc[1]]
  }
  if (next.ph !== prev.ph) {
    g |= G_PHASE
    out.ph = next.ph
  }
  if (next.cl !== prev.cl) {
    g |= G_CLOCK
    out.cl = next.cl
  }
  if (next.pos !== prev.pos) {
    g |= G_POS
    out.pos = next.pos
  }
  out.g = g

  const gone: number[] = []
  for (const id of prev.p.keys()) if (!next.p.has(id)) gone.push(id)
  if (gone.length) out.gone = gone
  return out
}

// Apply a difference. Returns a new frame rather than mutating, so the caller's
// baseline is never half-updated by a delta that turns out to be malformed.
export function patch(prev: Frame, d: SnapDelta): Frame {
  const b = prev.b.slice()
  let k = 1
  for (let i = 0; i < BALL_FIELDS; i++) if (d.b[0] & (1 << i)) b[i] = d.b[k++]

  const p = new Map<number, number[]>()
  for (const [id, v] of prev.p) p.set(id, v.slice())
  for (const row of d.p) {
    const id = row[0]
    const mask = row[1]
    const cur = p.get(id) ?? new Array<number>(PLAYER_FIELDS).fill(0)
    let j = 2
    for (let i = 0; i < PLAYER_FIELDS; i++) if (mask & (1 << i)) cur[i] = row[j++]
    p.set(id, cur)
  }
  for (const id of d.gone ?? []) p.delete(id)

  return {
    b,
    p,
    sc: d.g & G_SCORE && d.sc ? [d.sc[0], d.sc[1]] : [prev.sc[0], prev.sc[1]],
    ph: d.g & G_PHASE && d.ph !== undefined ? d.ph : prev.ph,
    cl: d.g & G_CLOCK && d.cl !== undefined ? d.cl : prev.cl,
    pos: d.g & G_POS ? (d.pos ?? null) : prev.pos,
  }
}

// A frame nothing has ever matched, so the first diff against it is a full
// world. Used as the baseline for a keyframe so one code path produces both.
export function emptyFrame(): Frame {
  return { b: new Array<number>(BALL_FIELDS).fill(0), p: new Map(), sc: [0, 0], ph: '', cl: 0, pos: null }
}
