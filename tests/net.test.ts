// Online play, end to end, with no browser involved.
//
// The browser test can establish a connection but cannot prove the important
// half: a headless page can't take pointer lock, so the guest's controller
// never produces a Command and the host correctly receives zeros. That looks
// identical to a broken input path and tells you nothing.
//
// So this wires a HostSession and a ClientSession together through a pair of
// in-memory transports and drives them by hand. It exercises exactly the chain
// that matters — pack, wire, unpack, seat, simulate — deterministically.
import { World } from '../src/game/match/world'
import { HostSession, ClientSession } from '../src/game/net/session'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, FIELD } from '../src/game/config'
import type { Msg } from '../src/game/net/protocol'
import type { Transport } from '../src/game/net/transport'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(42)} ${detail}`)

// A transport that hands messages straight to its twin. Delivery is deferred by
// one call so nothing recurses, which is also roughly what a real link does.
class Loop implements Transport {
  readonly kind = 'rtc' as const
  onMessage: (m: Msg, from: string) => void = () => {}
  onOpen: () => void = () => {}
  onClose: (why: string) => void = () => {}
  open = true
  peer: Loop | null = null
  private inbox: { m: Msg; from: string }[] = []
  // Throw away the next N things this end sends. Deterministic on purpose: a
  // random fraction makes a retry test that sometimes passes for the wrong
  // reason, which is worse than no test.
  dropNext = 0
  dropped = 0

  constructor(private name: string) {}

  send(m: Msg) {
    if (!this.peer || !this.open) return
    if (this.dropNext > 0) {
      this.dropNext--
      this.dropped++
      return
    }
    // Round-trip through JSON, so anything that doesn't survive serialisation
    // fails here rather than mysteriously in a real game.
    this.peer.inbox.push({ m: JSON.parse(JSON.stringify(m)) as Msg, from: this.name })
  }

  pump() {
    const q = this.inbox
    this.inbox = []
    for (const e of q) this.onMessage(e.m, e.from)
  }

  close() {
    this.open = false
  }
}

const cfg = {
  teamSize: 4,
  halfLength: 6000,
  mode: 'match' as const,
  singleKeeper: false,
  view: '3d' as const,
  quality: 'medium' as const,
  position: 'MID' as const,
  heightSens: 1.6,
  curveSens: 1.15,
  humanControlled: true,
}

// `dropGuest` bins that many of the guest's first packets. It has to be set
// before the session exists, because the very first hello goes out of the
// ClientSession constructor — setting it afterwards would let that one through
// and the retry would never be tested at all.
function link(dropGuest = 0) {
  const hostWorld = new World(cfg)
  const clientWorld = new World(cfg)
  hostWorld.phase = 'playing'
  clientWorld.phase = 'playing'
  const a = new Loop('host')
  const b = new Loop('guest')
  a.peer = b
  b.peer = a
  b.dropNext = dropGuest
  const host = new HostSession(hostWorld, a)
  const client = new ClientSession(clientWorld, b, 'guest')
  return { hostWorld, clientWorld, host, client, a, b }
}

// One frame on both ends, with the wire pumped in between.
function frame(
  s: ReturnType<typeof link>,
  dt: number,
  hostCmd: Command = emptyCommand(),
  clientCmd: Command = emptyCommand(),
) {
  s.a.pump()
  s.hostWorld.update(dt, hostCmd)
  s.host.update(dt)
  s.b.pump()
  s.clientWorld.update(dt, clientCmd)
  s.client.update(dt, clientCmd)
}

const run = (s: ReturnType<typeof link>, seconds: number, hc?: Command, cc?: Command) => {
  const n = Math.round(seconds / (1 / 60))
  for (let i = 0; i < n; i++) frame(s, 1 / 60, hc, cc)
}

const drive = (x: number, y: number, sprint = true): Command => {
  const c = emptyCommand()
  c.move = { x, y }
  c.aim = { x, y }
  c.sprint = sprint
  return c
}

// ---- 1. joining -------------------------------------------------------------
{
  const s = link()
  run(s, 0.5)
  ok(
    'a guest joins and is given a shirt',
    s.client.joined && s.client.playerId >= 0 && s.hostWorld.netSeats.has(s.client.playerId),
    `seat ${s.client.playerId}, host is holding ${s.hostWorld.netSeats.size} network seat(s)`,
  )
}

// ---- 2. the guest's input moves their player ON THE HOST --------------------
{
  const s = link()
  run(s, 0.5)
  const id = s.client.playerId
  const p = s.hostWorld.player(id)!
  const from = { x: p.x, y: p.y }
  run(s, 2, emptyCommand(), drive(1, 0))
  const moved = Math.hypot(p.x - from.x, p.y - from.y)
  ok(
    "the guest's input moves their player on the host",
    moved > 4,
    `held forward for 2s: their shirt travelled ${moved.toFixed(2)} m on the host's pitch`,
  )
}

// ---- 3. ...and the host is the one who decides -------------------------------
{
  const s = link()
  run(s, 0.5)
  const id = s.client.playerId
  const hp = s.hostWorld.player(id)!
  run(s, 1.5, emptyCommand(), drive(0, 1))
  const cp = s.clientWorld.player(id)!
  const gap = Math.hypot(hp.x - cp.x, hp.y - cp.y)
  ok(
    'the client stays in step with the host',
    gap < 1.5,
    `after 1.5s of movement the two worlds differ by ${gap.toFixed(2)} m`,
  )
}

// ---- 4. the host's ball reaches the client ----------------------------------
{
  const s = link()
  run(s, 0.5)
  s.hostWorld.ball.setPos(41, 9, 0)
  s.hostWorld.ball.stop()
  run(s, 0.6)
  const b = s.clientWorld.ball
  ok(
    "the host's ball is the one everybody sees",
    Math.hypot(b.x - 41, b.y - 9) < 1.5,
    `host put it at 41,9 — client has it at ${b.x.toFixed(1)},${b.y.toFixed(1)}`,
  )
}

// ---- 5. a kick sent over the wire is played by the host ----------------------
{
  const s = link()
  run(s, 0.5)
  const id = s.client.playerId
  const p = s.hostWorld.player(id)!
  s.hostWorld.ball.setPos(p.x + 0.5, p.y, 0)
  s.hostWorld.ball.stop()
  s.hostWorld.ball.lastTouchId = -1
  p.kickCooldown = 0
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.kick = makeKick('strike', 0.9, { x: 1, y: 0 }, { loft: 0.3, spin: 0.5 })
  // One frame with the kick, then let it fly.
  frame(s, 1 / 60, emptyCommand(), c)
  frame(s, 1 / 60, emptyCommand(), emptyCommand())
  frame(s, 1 / 60, emptyCommand(), emptyCommand())
  const speed = s.hostWorld.ball.speed
  ok(
    'a kick survives the wire intact',
    speed > 18,
    `the guest's strike left the host's ball at ${speed.toFixed(1)} m/s`,
  )
}

// ---- 6. joining survives a lost packet ---------------------------------------
{
  // Bin the opening hello and the first two retries behind it.
  const s = link(3)
  run(s, 0.9)
  const stuck = s.client.joined
  run(s, 1.2)
  ok(
    'a dropped hello does not wedge the join',
    !stuck && s.client.joined,
    `${s.b.dropped} packets binned, join still failed at first; the retry got it in`,
  )
}

// ---- 7. leaving frees the seat ------------------------------------------------
{
  const s = link()
  run(s, 0.5)
  const had = s.hostWorld.netSeats.size
  s.client.close()
  s.a.pump()
  s.host.update(1 / 60)
  ok(
    'leaving gives the shirt back',
    had === 1 && s.hostWorld.netSeats.size === 0,
    `${had} seat taken while connected, ${s.hostWorld.netSeats.size} after they left`,
  )
}

// ---- 8. the AI leaves a networked player alone --------------------------------
{
  const s = link()
  run(s, 0.5)
  const id = s.client.playerId
  const p = s.hostWorld.player(id)!
  // Sit perfectly still for two seconds. If the AI were still driving this
  // shirt it would go chasing the ball.
  run(s, 2, emptyCommand(), emptyCommand())
  ok(
    'the AI does not drive a networked shirt',
    p.speed < 0.2,
    `the guest sent nothing, so their player stood still (${p.speed.toFixed(2)} m/s)`,
  )
  void FIELD
  void SIM
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
