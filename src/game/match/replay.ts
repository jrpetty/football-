import type { World } from './world'
import { captureSnapshot, applySnapshot } from '../net/session'
import type { Snapshot } from '../net/protocol'

// Replays.
//
// A physics game's payoff is a ball bending into a top corner, and until now
// you never saw it: you are behind your own player, facing the wrong way, and
// the moment is over before the camera could get anywhere useful.
//
// This costs almost nothing because the hard part was already built for online
// play. A snapshot of the whole world — every player, the ball, its spin — is
// exactly what the host broadcasts twenty times a second, and applying one is
// exactly what a client already does. A replay is that same pair of functions
// pointed at a ring buffer instead of a socket.
//
// Recording runs the whole time. Nothing is decided in advance about what is
// worth keeping, because you cannot know a shot was worth watching until after
// it has gone in.

export const REPLAY = {
  hz: 30, // samples a second — smoother than the network rate, since it's free
  seconds: 8, // how far back you can always see
  lead: 6, // how much of it a goal replay actually shows
  speed: 0.45, // played back slower than life, because that's the point
}

interface Frame {
  t: number // seconds into the recording
  s: Snapshot
}

export class Replay {
  private buf: Frame[] = []
  private clock = 0
  private sample = 0

  // Playback state. Null when we're just recording.
  playing = false
  private head = 0 // where in the recording we are, in seconds
  private from = 0 // where this playback started
  private saved: Snapshot | null = null // the live world, to put back afterwards
  label = ''

  get available(): number {
    return this.buf.length ? this.clock - this.buf[0].t : 0
  }

  // Called every frame with the live world. Cheap: one snapshot every 33ms.
  record(world: World, dt: number) {
    if (this.playing) return
    this.clock += dt
    this.sample += dt
    const step = 1 / REPLAY.hz
    if (this.sample < step) return
    this.sample = 0
    this.buf.push({ t: this.clock, s: captureSnapshot(world) })
    const cutoff = this.clock - REPLAY.seconds
    while (this.buf.length && this.buf[0].t < cutoff) this.buf.shift()
  }

  // Start playing back the last `seconds` of the recording. Returns false if
  // there isn't enough history yet — right after a kickoff, say.
  start(world: World, label = 'REPLAY', seconds = REPLAY.lead): boolean {
    if (this.playing || this.available < 1) return false
    this.saved = captureSnapshot(world)
    this.from = Math.max(this.buf[0].t, this.clock - seconds)
    this.head = this.from
    this.playing = true
    this.label = label
    return true
  }

  // Put the live world back exactly as it was and carry on.
  stop(world: World) {
    if (!this.playing) return
    if (this.saved) applySnapshot(world, this.saved, this.saved, 1, -1)
    this.saved = null
    this.playing = false
  }

  // How far through the replay we are, 0..1 — for a progress bar.
  get progress(): number {
    const span = this.clock - this.from
    return span > 1e-3 ? Math.min(1, (this.head - this.from) / span) : 1
  }

  // Drive playback. Returns false when it has run out, so the caller can stop.
  update(world: World, dt: number): boolean {
    if (!this.playing) return false
    this.head += dt * REPLAY.speed
    if (this.head >= this.clock) return false

    // Find the pair of samples straddling where we are and blend between them,
    // so playback is smooth rather than stepping at the sample rate.
    let a = this.buf[0]
    let b = this.buf[Math.min(1, this.buf.length - 1)]
    for (let i = 0; i < this.buf.length - 1; i++) {
      if (this.buf[i].t <= this.head && this.buf[i + 1].t >= this.head) {
        a = this.buf[i]
        b = this.buf[i + 1]
        break
      }
    }
    const span = b.t - a.t
    const f = span > 1e-4 ? Math.min(1, Math.max(0, (this.head - a.t) / span)) : 1
    // -1 as the "own player" means nothing is predicted: every player, including
    // yours, is played back exactly as it happened.
    applySnapshot(world, a.s, b.s, f, -1)
    return true
  }

  // Where the camera should look: the ball, since that is what you came to see.
  focus(world: World): { x: number; y: number; z: number } {
    return { x: world.ball.x, y: world.ball.y, z: world.ball.z }
  }

  clear() {
    this.buf = []
    this.clock = 0
    this.playing = false
    this.saved = null
  }
}
