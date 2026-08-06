import { NET, packCommand, unpackCommand, round3 } from './protocol'
import type { Msg, Snapshot, SnapPlayer } from './protocol'
import type { Transport } from './transport'
import type { World } from '../match/world'
import type { Command } from '../types'
import { emptyCommand } from '../types'
import { KICK_ANIMS } from '../entities/player'

// Host and client halves of a match.
//
// The host runs the real simulation and is the only authority on it. Clients
// send their Command sixty times a second and render the twenty-per-second
// snapshots that come back — but they also run the *same* simulation locally
// and predict their own player from their own input, so their own movement is
// never waiting on a round trip. When a snapshot arrives, their own player is
// snapped back onto the host's version if the two have drifted apart; everyone
// else is simply interpolated between the last two snapshots, deliberately a
// fraction of a second in the past so the motion is smooth rather than jumpy.
//
// The reason any of this fits in one file is that the simulation was already
// built for it: fixed-step, and driven entirely by Command objects that don't
// care whether a human, an AI or a network packet produced them.

export class HostSession {
  peers = new Map<string, { seat: number; playerId: number; cmd: Command; lastSeq: number; seen: number }>()
  private acc = 0
  private tick = 0

  constructor(
    private world: World,
    private transport: Transport,
  ) {
    transport.onMessage = (m, from) => this.receive(m, from)
  }

  private receive(m: Msg, from: string) {
    if (m.t === 'hello') {
      // Joining is retried, so the same peer may say hello more than once. Give
      // them the seat they already have rather than a second one.
      const already = this.peers.get(from)
      if (already) {
        this.transport.send({
          t: 'welcome',
          seat: already.seat,
          playerId: already.playerId,
          config: this.world.config,
          protocol: NET.protocol,
        })
        return
      }
      if (m.protocol !== NET.protocol) {
        this.transport.send({ t: 'reject', why: `version mismatch (host is v${NET.protocol})` })
        return
      }
      const seat = this.peers.size + 1
      const playerId = this.world.claimSeat()
      if (playerId < 0) {
        this.transport.send({ t: 'reject', why: 'no free positions on this pitch' })
        return
      }
      this.peers.set(from, { seat, playerId, cmd: emptyCommand(), lastSeq: 0, seen: 0 })
      this.transport.send({
        t: 'welcome',
        seat,
        playerId,
        config: this.world.config,
        protocol: NET.protocol,
      })
    } else if (m.t === 'input') {
      const p = this.peers.get(from)
      if (!p) return
      // Late packets are dropped rather than applied out of order.
      if (m.seq <= p.lastSeq) return
      p.lastSeq = m.seq
      p.seen = 0
      p.cmd = unpackCommand(m.cmd)
    } else if (m.t === 'bye') {
      const p = this.peers.get(from)
      if (p) this.world.releaseSeat(p.playerId)
      this.peers.delete(from)
    }
  }

  // Called once a frame, alongside the host's own update.
  update(dt: number) {
    for (const [id, p] of this.peers) {
      p.seen += dt
      this.world.setRemoteCommand(p.playerId, p.cmd)
      if (p.seen > NET.timeout) {
        this.world.releaseSeat(p.playerId)
        this.peers.delete(id)
      }
    }
    this.acc += dt
    const step = 1 / NET.snapshotHz
    if (this.acc < step) return
    this.acc = 0
    this.tick++
    const snap = capture(this.world)
    for (const [, p] of this.peers) {
      this.transport.send({ t: 'snap', tick: this.tick, time: performance.now() / 1000, ack: p.lastSeq, s: snap })
    }
  }

  close() {
    this.transport.send({ t: 'bye' })
    this.transport.close()
  }
}

export class ClientSession {
  playerId = -1
  seat = 0
  joined = false
  error: string | null = null
  latency = 0

  private buf: { time: number; s: Snapshot }[] = []
  private seq = 0
  private acc = 0
  private sinceSnap = 0
  private helloTimer = 0

  constructor(
    private world: World,
    private transport: Transport,
    private name: string,
  ) {
    transport.onMessage = (m) => this.receive(m)
    transport.onOpen = () => this.hello()
    // The lobby only hands the session over once the link is up, so by the time
    // we get here the channel is usually already open and onOpen will never
    // fire again. Say hello immediately in that case — otherwise the two sides
    // sit there connected and nothing ever joins.
    if (transport.open) this.hello()
    transport.onClose = (why) => {
      this.error = why
      this.joined = false
    }
  }

  private receive(m: Msg) {
    if (m.t === 'welcome') {
      this.seat = m.seat
      this.playerId = m.playerId
      this.joined = true
      this.world.takeControl(m.playerId)
    } else if (m.t === 'reject') {
      this.error = m.why
    } else if (m.t === 'snap') {
      this.sinceSnap = 0
      this.buf.push({ time: performance.now() / 1000, s: m.s })
      // Two seconds of history is far more than the interpolation delay needs.
      while (this.buf.length > NET.snapshotHz * 2) this.buf.shift()
    }
  }

  private hello() {
    this.transport.send({ t: 'hello', name: this.name, protocol: NET.protocol })
  }

  // Send our input, and pull everyone else toward where the host says they are.
  update(dt: number, cmd: Command) {
    // Keep asking to join until we're in. A single hello can be lost — the data
    // channel is unreliable by design, because a dropped input packet should be
    // skipped rather than resent — and losing that one packet would otherwise
    // leave both sides connected with nothing ever happening.
    if (!this.joined && !this.error) {
      this.helloTimer -= dt
      if (this.helloTimer <= 0) {
        this.helloTimer = 0.5
        this.hello()
      }
    }
    this.sinceSnap += dt
    if (this.sinceSnap > NET.timeout && this.joined) {
      this.error = 'lost the host'
      this.joined = false
    }
    if (!this.joined) return

    this.acc += dt
    const step = 1 / NET.inputHz
    if (this.acc >= step) {
      this.acc = 0
      this.seq++
      this.transport.send({ t: 'input', seq: this.seq, cmd: packCommand(cmd) })
    }
    this.applySnapshots()
  }

  // Everyone except you is drawn a fraction of a second in the past, between
  // the two snapshots that straddle that moment. That delay is the entire
  // reason remote players glide instead of stuttering: there is always a next
  // snapshot to head toward, rather than a guess about one that hasn't arrived.
  private applySnapshots() {
    if (this.buf.length < 2) return
    const target = performance.now() / 1000 - NET.interpDelay
    let a = this.buf[0]
    let b = this.buf[1]
    for (let i = 0; i < this.buf.length - 1; i++) {
      if (this.buf[i].time <= target && this.buf[i + 1].time >= target) {
        a = this.buf[i]
        b = this.buf[i + 1]
        break
      }
      a = this.buf[this.buf.length - 2]
      b = this.buf[this.buf.length - 1]
    }
    const span = b.time - a.time
    const f = span > 1e-4 ? Math.min(1, Math.max(0, (target - a.time) / span)) : 1
    apply(this.world, a.s, b.s, f, this.playerId)
  }

  close() {
    this.transport.send({ t: 'bye' })
    this.transport.close()
  }
}

// ---- snapshot capture / apply ----------------------------------------------

function capture(w: World): Snapshot {
  const b = w.ball
  return {
    b: {
      x: round3(b.x),
      y: round3(b.y),
      z: round3(b.z),
      vx: round3(b.vx),
      vy: round3(b.vy),
      vz: round3(b.vz),
      sp: round3(b.spin),
      vs: round3(b.vSpin),
    },
    p: w.players.map((p) => ({
      id: p.id,
      x: round3(p.x),
      y: round3(p.y),
      h: round3(p.heading),
      st:
        (p.sprinting ? 1 : 0) |
        (p.sliding ? 2 : 0) |
        (p.diving ? 4 : 0) |
        (p.shielding ? 8 : 0) |
        (p.slideRecover > 0 ? 16 : 0) |
        (p.diveRecover > 0 ? 32 : 0),
      kt: round3(p.kickTimer),
      kk: KICK_ANIMS.indexOf(p.kickKind),
      kp: round3(p.kickPower),
      kl: round3(p.kickAnimLen),
      leg: p.kickLeg,
      en: round3(p.energy),
    })),
    sc: [w.score.home, w.score.away],
    ph: w.phase,
    cl: round3(w.clock),
    pos: w.possessorId,
  }
}

function apply(w: World, a: Snapshot, b: Snapshot, f: number, ownId: number) {
  const lerp = (x: number, y: number) => x + (y - x) * f
  // The ball is the one thing everybody has to agree on exactly, so it is taken
  // from the host verbatim — interpolated in position, but never predicted.
  w.ball.px = w.ball.x
  w.ball.py = w.ball.y
  w.ball.pz = w.ball.z
  w.ball.x = lerp(a.b.x, b.b.x)
  w.ball.y = lerp(a.b.y, b.b.y)
  w.ball.z = lerp(a.b.z, b.b.z)
  w.ball.vx = b.b.vx
  w.ball.vy = b.b.vy
  w.ball.vz = b.b.vz
  w.ball.spin = b.b.sp
  w.ball.vSpin = b.b.vs

  const byId = new Map<number, SnapPlayer>()
  for (const s of b.p) byId.set(s.id, s)
  for (const sa of a.p) {
    const sb = byId.get(sa.id)
    const p = w.player(sa.id)
    if (!p || !sb) continue
    if (p.id === ownId) {
      // Your own player is predicted locally. Only correct it when the host and
      // your prediction have genuinely parted company — snapping every frame
      // would undo the whole point of predicting.
      const drift = Math.hypot(p.x - sb.x, p.y - sb.y)
      if (drift > 1.2) {
        p.x = sb.x
        p.y = sb.y
      }
      continue
    }
    p.px = p.x
    p.py = p.y
    p.x = lerp(sa.x, sb.x)
    p.y = lerp(sa.y, sb.y)
    p.heading = lerpAngle(sa.h, sb.h, f)
    p.sprinting = !!(sb.st & 1)
    p.slideTimer = sb.st & 2 ? 0.1 : 0
    p.diveTimer = sb.st & 4 ? 0.1 : 0
    p.shielding = !!(sb.st & 8)
    p.slideRecover = sb.st & 16 ? 0.1 : 0
    p.diveRecover = sb.st & 32 ? 0.1 : 0
    p.kickTimer = sb.kt
    p.kickKind = KICK_ANIMS[sb.kk] ?? 'strike'
    p.kickPower = sb.kp
    p.kickAnimLen = sb.kl || 0.34
    p.kickLeg = sb.leg
    p.stamina = sb.en * 100
  }
  w.score.home = b.sc[0]
  w.score.away = b.sc[1]
  w.clock = b.cl
  w.possessorId = b.pos
}

// Headings wrap, so a naive lerp spins the player the long way round at ±π.
function lerpAngle(a: number, b: number, f: number): number {
  let d = ((b - a + Math.PI) % (Math.PI * 2)) - Math.PI
  if (d < -Math.PI) d += Math.PI * 2
  return a + d * f
}
