import type { Command } from '../types'

// The wire format.
//
// The architecture the game already had is the reason this is tractable: the
// simulation is fixed-step, and humans and AI both feed it identical Command
// objects. So a remote player is not a special case — it is a Command arriving
// from somewhere else. That is the whole integration.
//
// The topology is host-authoritative. One peer runs the simulation and is the
// truth; everyone else sends their Commands to it and renders the snapshots it
// sends back, predicting their own player locally so their own input feels
// instant. Nobody has to trust anybody's physics but the host's.

export const NET = {
  protocol: 4, // bumped whenever the wire format changes
  snapshotHz: 20, // how often the host broadcasts the world
  inputHz: 60, // how often a client sends its Command
  // How far behind the newest snapshot a client renders remote players. One
  // snapshot interval plus a little: enough to always have two snapshots to
  // interpolate between, so other players move smoothly instead of stepping.
  interpDelay: 0.085,
  // If nothing has been heard in this long, treat the link as gone — but see
  // `grace`: gone is not the same as over.
  timeout: 8,
  // How long a dropped player keeps their shirt. Long enough to walk through a
  // dead tunnel or for a router to sort itself out; short enough that a seat
  // isn't held all match by somebody who closed the tab.
  grace: 45,
  // A full world state, sent often enough that a client which joined or missed
  // one is never stranded, and rarely enough that it isn't the bulk of the
  // traffic. Everything between these is a delta.
  keyframeEvery: 40, // in snapshots — 2 s at 20 Hz
}

export type Msg =
  | { t: 'hello'; name: string; protocol: number; token?: string; spectate?: boolean }
  | {
      t: 'welcome'
      seat: number
      playerId: number // −1 for a spectator: connected, watching, holding no shirt
      config: unknown
      protocol: number
      resumed?: boolean // you got your old shirt back rather than a new one
    }
  | { t: 'reject'; why: string }
  // `ack` is the newest snapshot tick this client has seen. It costs one number
  // and gives the host a round-trip time without a single extra packet.
  | { t: 'input'; seq: number; ack: number; cmd: WireCommand }
  | { t: 'snap'; tick: number; time: number; ack: number; s?: Snapshot; d?: SnapDelta }
  // Who is who. Sent only when it changes, which is why names are not in every
  // snapshot twenty times a second.
  | { t: 'roster'; who: [number, string][]; pings?: [number, number][] }
  | { t: 'bye' }

// A Command, minus the things that never cross the wire.
export interface WireCommand {
  mx: number
  my: number
  ax: number
  ay: number
  f: number // bit flags: sprint | walk | shield | slide
  k: WireKick | null
}

export interface WireKick {
  ty: string
  p: number
  ax: number
  ay: number
  l: number
  s: number
  sk?: string
}

export interface SnapPlayer {
  id: number
  x: number
  y: number
  h: number // heading
  st: number // state flags: sprinting | sliding | diving | shielding | recovering
  kt: number // kick timer
  kk: number // kick kind index
  kp: number // kick power
  kl: number // kick anim length
  leg: number
  en: number // energy 0..1
}

export interface Snapshot {
  b: { x: number; y: number; z: number; vx: number; vy: number; vz: number; sp: number; vs: number }
  p: SnapPlayer[]
  sc: [number, number]
  ph: string
  cl: number
  pos: number | null // possessor id
}

// A snapshot as the difference from the one before it. See delta.ts — the
// shape lives there, but the message type needs to name it.
export interface SnapDelta {
  f: number // the tick this is a difference from
  b: number[] // [mask, ...changed ball fields]
  p: number[][] // [[id, mask, ...changed fields], ...] — unchanged players absent
  g: number // mask for the match-level fields below
  sc?: [number, number]
  ph?: string
  cl?: number
  pos?: number | null
  gone?: number[] // ids that have left
}

const F_SPRINT = 1
const F_WALK = 2
const F_SHIELD = 4
const F_SLIDE = 8
const F_JUMP = 16

export function packCommand(c: Command): WireCommand {
  let f = 0
  if (c.sprint) f |= F_SPRINT
  if (c.walk) f |= F_WALK
  if (c.shield) f |= F_SHIELD
  if (c.slide) f |= F_SLIDE
  if (c.jump) f |= F_JUMP
  return {
    mx: r3(c.move.x),
    my: r3(c.move.y),
    ax: r3(c.aim.x),
    ay: r3(c.aim.y),
    f,
    k: c.kick
      ? {
          ty: c.kick.type,
          p: r3(c.kick.power),
          ax: r3(c.kick.aim.x),
          ay: r3(c.kick.aim.y),
          l: r3(c.kick.loft),
          s: r3(c.kick.spin),
          sk: c.kick.skill,
        }
      : null,
  }
}

export function unpackCommand(w: WireCommand): Command {
  return {
    move: { x: w.mx, y: w.my },
    aim: { x: w.ax, y: w.ay },
    sprint: !!(w.f & F_SPRINT),
    walk: !!(w.f & F_WALK),
    shield: !!(w.f & F_SHIELD),
    slide: !!(w.f & F_SLIDE),
    jump: !!(w.f & F_JUMP),
    chargePass: false,
    chargeShot: false,
    kick: w.k
      ? {
          type: w.k.ty as Command['kick'] extends null ? never : NonNullable<Command['kick']>['type'],
          power: w.k.p,
          aim: { x: w.k.ax, y: w.k.ay },
          loft: w.k.l,
          spin: w.k.s,
          skill: w.k.sk as NonNullable<Command['kick']>['skill'],
        }
      : null,
  }
}

// Three decimal places is a millimetre. Nobody can see the difference and it
// roughly halves the size of a snapshot on the wire.
const r3 = (n: number) => Math.round(n * 1000) / 1000
export const round3 = r3
