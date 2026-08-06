import { NET, packCommand, unpackCommand, round3 } from './protocol'
import type { Msg, Snapshot, SnapPlayer } from './protocol'
import { toFrame, fromFrame, diff, patch, emptyFrame } from './delta'
import type { Frame } from './delta'
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

interface Peer {
  seat: number
  playerId: number // −1 for a spectator
  name: string
  token: string
  cmd: Command
  lastSeq: number
  seen: number // seconds since we last heard from them
  ping: number // milliseconds, round trip
  sentAt: Map<number, number> // snapshot tick → when it went out
  away: boolean // timed out, seat held, may yet come back
}

export class HostSession {
  peers = new Map<string, Peer>()
  private acc = 0
  private tick = 0
  // Baseline the deltas are differences from. One shared baseline is safe
  // because the link is ordered and reliable and every peer sees the same
  // broadcast stream — see delta.ts.
  private baseline: Frame = emptyFrame()
  private baseTick = 0
  private sinceKey = 1e9 // force a keyframe on the first send
  private rosterDirty = true
  private sinceRoster = 0

  constructor(
    private world: World,
    private transport: Transport,
    // The clock, injectable. Latency is a property of time passing, so a test
    // that cannot control the clock cannot test it — and a fake link that
    // "delays" packets by simulated time would measure a real one at zero.
    private now: () => number = () => performance.now(),
  ) {
    transport.onMessage = (m, from) => this.receive(m, from)
  }

  private receive(m: Msg, from: string) {
    if (m.t === 'hello') {
      if (m.protocol !== NET.protocol) {
        this.transport.send({ t: 'reject', why: `version mismatch (host is v${NET.protocol})` })
        return
      }
      // Joining is retried, so the same peer may say hello more than once. Give
      // them the seat they already have rather than a second one.
      const already = this.peers.get(from) ?? this.byToken(m.token)
      if (already) {
        const resumed = already.away
        already.away = false
        already.seen = 0
        already.name = m.name || already.name
        // A peer that dropped and came back may be a different transport
        // identity — the same person behind a new socket. Move them across.
        if (!this.peers.has(from)) {
          for (const [k, v] of this.peers) if (v === already) this.peers.delete(k)
          this.peers.set(from, already)
        }
        if (already.playerId >= 0) this.world.setName(already.playerId, already.name)
        this.rosterDirty = true
        this.sinceKey = 1e9 // they need a whole world, not a difference
        this.transport.send({
          t: 'welcome',
          seat: already.seat,
          playerId: already.playerId,
          config: this.world.config,
          protocol: NET.protocol,
          resumed,
        })
        return
      }
      // A spectator takes no shirt, so a full pitch never turns one away.
      const playerId = m.spectate ? -1 : this.world.claimSeat()
      if (playerId < 0 && !m.spectate) {
        this.transport.send({ t: 'reject', why: 'no free positions on this pitch' })
        return
      }
      const seat = this.peers.size + 1
      const name = (m.name || `PLAYER ${seat}`).slice(0, 14)
      this.peers.set(from, {
        seat, playerId, name, token: m.token ?? '',
        cmd: emptyCommand(), lastSeq: 0, seen: 0, ping: 0, sentAt: new Map(), away: false,
      })
      if (playerId >= 0) this.world.setName(playerId, name)
      this.rosterDirty = true
      this.sinceKey = 1e9
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
      p.seen = 0
      if (p.away) {
        // They are back before we even noticed they were gone.
        p.away = false
        this.rosterDirty = true
      }
      // The tick they acked tells us how long the round trip took, for free.
      const sent = p.sentAt.get(m.ack)
      if (sent !== undefined) {
        const rtt = this.now() - sent
        p.ping = p.ping ? p.ping + (rtt - p.ping) * 0.25 : rtt
        for (const k of p.sentAt.keys()) if (k <= m.ack) p.sentAt.delete(k)
      }
      // Late packets are dropped rather than applied out of order.
      if (m.seq <= p.lastSeq) return
      p.lastSeq = m.seq
      p.cmd = unpackCommand(m.cmd)
    } else if (m.t === 'bye') {
      // An explicit goodbye is not a dropout: give the shirt straight back.
      const p = this.peers.get(from)
      if (p && p.playerId >= 0) {
        this.world.releaseSeat(p.playerId)
        this.world.setName(p.playerId, '')
      }
      this.peers.delete(from)
      this.rosterDirty = true
    }
  }

  private byToken(token?: string): Peer | undefined {
    if (!token) return undefined
    for (const p of this.peers.values()) if (p.token === token) return p
    return undefined
  }

  // Called once a frame, alongside the host's own update.
  update(dt: number) {
    for (const [id, p] of this.peers) {
      p.seen += dt
      // A player who has gone quiet keeps their shirt but stops running about:
      // their last command would otherwise have them sprinting into a corner
      // for the whole grace period.
      if (p.playerId >= 0) this.world.setRemoteCommand(p.playerId, p.away ? emptyCommand() : p.cmd)
      if (p.seen > NET.timeout && !p.away) {
        p.away = true
        this.rosterDirty = true
      }
      if (p.seen > NET.grace) {
        if (p.playerId >= 0) {
          this.world.releaseSeat(p.playerId)
          this.world.setName(p.playerId, '')
        }
        this.peers.delete(id)
        this.rosterDirty = true
      }
    }

    this.sinceRoster += dt
    if (this.peers.size && (this.rosterDirty || this.sinceRoster > 1)) {
      this.rosterDirty = false
      this.sinceRoster = 0
      this.transport.send({
        t: 'roster',
        who: [...this.peers.values()].filter((p) => p.playerId >= 0).map((p) => [p.playerId, p.name] as [number, string]),
        pings: [...this.peers.values()].filter((p) => p.playerId >= 0).map((p) => [p.playerId, Math.round(p.ping)] as [number, number]),
      })
    }

    this.acc += dt
    const step = 1 / NET.snapshotHz
    if (this.acc < step) return
    this.acc = 0
    this.tick++
    const now = this.now()
    const frame = toFrame(captureSnapshot(this.world))
    const key = this.sinceKey >= NET.keyframeEvery
    if (key) {
      this.sinceKey = 0
      const snap = fromFrame(frame)
      for (const [, p] of this.peers) {
        p.sentAt.set(this.tick, now)
        this.transport.send({ t: 'snap', tick: this.tick, time: now / 1000, ack: p.lastSeq, s: snap })
      }
    } else {
      this.sinceKey++
      const d = diff(this.baseline, frame, this.baseTick)
      for (const [, p] of this.peers) {
        p.sentAt.set(this.tick, now)
        this.transport.send({ t: 'snap', tick: this.tick, time: now / 1000, ack: p.lastSeq, d })
      }
    }
    this.baseline = frame
    this.baseTick = this.tick
  }

  // What the host's own HUD shows: who is on, how far away they are.
  roster(): { name: string; ping: number; away: boolean; spectator: boolean }[] {
    return [...this.peers.values()].map((p) => ({
      name: p.name,
      ping: Math.round(p.ping),
      away: p.away,
      spectator: p.playerId < 0,
    }))
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
  // A hard no from the host — wrong version, full pitch. Not the same as a
  // dropped link, and the only one of the two that ends the match.
  error: string | null = null
  // Set while the link is down but the seat is still ours. The game keeps
  // running; the HUD says so.
  reconnecting = false
  ping = 0
  spectator = false

  private buf: { time: number; s: Snapshot }[] = []
  private seq = 0
  private acc = 0
  private sinceSnap = 0
  private helloTimer = 0
  private lastTick = 0
  private sentAt = new Map<number, number>()
  // Baseline the incoming deltas apply to. Null until the first keyframe, and
  // deltas before that are ignored rather than guessed at.
  private base: Frame | null = null
  private baseTick = -1

  constructor(
    private world: World,
    private transport: Transport,
    private name: string,
    private token = '',
    spectate = false,
    private now: () => number = () => performance.now(),
  ) {
    this.spectator = spectate
    transport.onMessage = (m) => this.receive(m)
    transport.onOpen = () => {
      // A transport that redialled is a fresh socket on an existing match: say
      // hello again with our token and the host gives the same shirt back.
      this.base = null
      this.hello()
    }
    // The lobby only hands the session over once the link is up, so by the time
    // we get here the channel is usually already open and onOpen will never
    // fire again. Say hello immediately in that case — otherwise the two sides
    // sit there connected and nothing ever joins.
    if (transport.open) this.hello()
    transport.onClose = (why) => {
      // A closed link is not a closed match. If the transport can redial we sit
      // in `reconnecting` and keep playing locally; if it can't, that is what
      // makes it fatal.
      if (this.transport.canReconnect) {
        this.reconnecting = true
        this.base = null
      } else {
        this.error = why
        this.joined = false
      }
    }
  }

  private receive(m: Msg) {
    if (m.t === 'welcome') {
      this.seat = m.seat
      this.playerId = m.playerId
      this.spectator = m.playerId < 0
      this.joined = true
      this.reconnecting = false
      if (m.playerId >= 0) this.world.takeControl(m.playerId)
    } else if (m.t === 'reject') {
      this.error = m.why
    } else if (m.t === 'roster') {
      for (const [id, name] of m.who) this.world.setName(id, name)
      // Our own ping, as the host measured it. It knows better than we do —
      // it is the one timing the round trip against its own clock.
      for (const [id, ping] of m.pings ?? []) if (id === this.playerId) this.ping = ping
    } else if (m.t === 'snap') {
      this.sinceSnap = 0
      this.reconnecting = false
      this.lastTick = m.tick
      let frame: Frame | null = null
      if (m.s) {
        frame = toFrame(m.s)
      } else if (m.d) {
        // A difference is only meaningful against the frame it was made from.
        if (!this.base || m.d.f !== this.baseTick) return
        frame = patch(this.base, m.d)
      }
      if (!frame) return
      this.base = frame
      this.baseTick = m.tick
      this.buf.push({ time: this.now() / 1000, s: fromFrame(frame) })
      // Two seconds of history is far more than the interpolation delay needs.
      while (this.buf.length > NET.snapshotHz * 2) this.buf.shift()
    }
  }

  private hello() {
    this.transport.send({
      t: 'hello',
      name: this.name,
      protocol: NET.protocol,
      token: this.token,
      spectate: this.spectator,
    })
  }

  // Send our input, and pull everyone else toward where the host says they are.
  update(dt: number, cmd: Command) {
    // Keep asking to join until we're in. A single hello can be lost — the data
    // channel is unreliable by design, because a dropped input packet should be
    // skipped rather than resent — and losing that one packet would otherwise
    // leave both sides connected with nothing ever happening.
    if ((!this.joined || this.reconnecting) && !this.error) {
      this.helloTimer -= dt
      if (this.helloTimer <= 0) {
        this.helloTimer = 0.5
        if (this.transport.open) this.hello()
      }
    }
    this.sinceSnap += dt
    if (this.sinceSnap > NET.timeout && this.joined) {
      // Silence from the host. Everything keeps running on our own copy of the
      // simulation while the transport tries to get back — the seat is held for
      // NET.grace, so there is time.
      this.reconnecting = true
      if (this.sinceSnap > NET.grace) {
        this.error = 'lost the host'
        this.joined = false
        this.reconnecting = false
      }
    }
    if (!this.joined) return

    this.acc += dt
    const step = 1 / NET.inputHz
    if (this.acc >= step) {
      this.acc = 0
      this.seq++
      this.sentAt.set(this.seq, this.now())
      // A spectator holds no shirt, so it sends an empty command: it is on the
      // wire only to be counted, acked and timed.
      this.transport.send({
        t: 'input',
        seq: this.seq,
        ack: this.lastTick,
        cmd: packCommand(this.spectator ? emptyCommand() : cmd),
      })
      while (this.sentAt.size > 240) this.sentAt.delete(this.sentAt.keys().next().value as number)
    }
    this.applySnapshots()
  }

  // Everyone except you is drawn a fraction of a second in the past, between
  // the two snapshots that straddle that moment. That delay is the entire
  // reason remote players glide instead of stuttering: there is always a next
  // snapshot to head toward, rather than a guess about one that hasn't arrived.
  private applySnapshots() {
    if (this.buf.length < 2) return
    const target = this.now() / 1000 - NET.interpDelay
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
    applySnapshot(this.world, a.s, b.s, f, this.playerId)
  }

  close() {
    this.transport.send({ t: 'bye' })
    this.transport.close()
  }
}

// ---- snapshot capture / apply ----------------------------------------------


// Exported so replays can use them: a replay is these same two functions
// pointed at a ring buffer instead of a socket.
export function captureSnapshot(w: World): Snapshot {
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

export function applySnapshot(w: World, a: Snapshot, b: Snapshot, f: number, ownId: number) {
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
