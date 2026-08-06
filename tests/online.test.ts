// The parts of online play that are not "does a packet arrive".
//
// Reconnecting, spectating, ping and names are all things you cannot see by
// looking at the code — they are behaviours over time, across a link that
// misbehaves. So this drives two real sessions through a transport that can be
// cut and restored on command, and checks what actually happened to the seat.
import { World } from '../src/game/match/world'
import { HostSession, ClientSession } from '../src/game/net/session'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { NET } from '../src/game/net/protocol'
import type { Msg } from '../src/game/net/protocol'
import type { Transport } from '../src/game/net/transport'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(46)} ${detail}`)

// A link that can be cut and brought back, and that delivers with a delay so
// there is a real round trip to measure.
class Wire implements Transport {
  readonly kind = 'ws' as const
  canReconnect = true
  onMessage: (m: Msg, from: string) => void = () => {}
  onOpen: () => void = () => {}
  onClose: (why: string) => void = () => {}
  open = true
  peer: Wire | null = null
  private queue: { m: Msg; from: string; at: number }[] = []
  private clock = 0
  // One-way delay in seconds. Half the round trip.
  delay = 0

  constructor(private name: string) {}

  send(m: Msg) {
    if (!this.peer || !this.open || !this.peer.open) return
    this.peer.queue.push({
      m: JSON.parse(JSON.stringify(m)) as Msg,
      from: this.name,
      at: this.peer.clock + this.delay,
    })
  }

  pump(dt: number) {
    this.clock += dt
    const ready = this.queue.filter((e) => e.at <= this.clock)
    this.queue = this.queue.filter((e) => e.at > this.clock)
    for (const e of ready) this.onMessage(e.m, e.from)
  }

  // Pull the plug. Nothing is delivered and nothing new is accepted.
  cut() {
    this.open = false
    this.queue = []
    this.onClose('relay disconnected')
  }

  restore() {
    this.open = true
    this.onOpen()
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

// One clock for the whole rig, advanced by the frame loop. Both sessions read
// it, so the fake link's delay is latency they can actually measure.
function link(opts: { name?: string; token?: string; spectate?: boolean; delay?: number } = {}) {
  const clock = { ms: 0 }
  const now = () => clock.ms
  const hostWorld = new World(cfg)
  const clientWorld = new World(cfg)
  hostWorld.phase = 'playing'
  clientWorld.phase = 'playing'
  const a = new Wire('host')
  const b = new Wire('guest')
  a.peer = b
  b.peer = a
  a.delay = b.delay = opts.delay ?? 0
  const host = new HostSession(hostWorld, a, now)
  const client = new ClientSession(
    clientWorld,
    b,
    opts.name ?? 'ALEX',
    opts.token ?? 'tok-1',
    opts.spectate ?? false,
    now,
  )
  return { hostWorld, clientWorld, host, client, a, b, clock }
}

type Link = ReturnType<typeof link>

function frame(s: Link, dt: number, hostCmd: Command = emptyCommand(), clientCmd: Command = emptyCommand()) {
  s.clock.ms += dt * 1000
  s.a.pump(dt)
  s.hostWorld.update(dt, hostCmd)
  s.host.update(dt)
  s.b.pump(dt)
  s.clientWorld.update(dt, clientCmd)
  s.client.update(dt, clientCmd)
}

const run = (s: Link, seconds: number, hc?: Command, cc?: Command) => {
  const n = Math.round(seconds / (1 / 60))
  for (let i = 0; i < n; i++) frame(s, 1 / 60, hc, cc)
}

// ---- 1. names ---------------------------------------------------------------
{
  const s = link({ name: 'ALEX' })
  run(s, 1)
  const id = s.client.playerId
  ok(
    'the name you chose reaches everyone',
    s.hostWorld.nameFor(s.hostWorld.player(id)!) === 'ALEX' &&
      s.clientWorld.nameFor(s.clientWorld.player(id)!) === 'ALEX',
    `shirt ${id} is "${s.hostWorld.nameFor(s.hostWorld.player(id)!)}" on both machines`,
  )
}

{
  const s = link()
  run(s, 0.5)
  // A shirt nobody has taken says what it is rather than pretending to be a
  // person — which is the thing you need to know at a glance.
  const free = s.hostWorld.players.find((p) => !s.hostWorld.isClaimed(p))!
  ok(
    'an unclaimed shirt is labelled as one',
    !s.hostWorld.isClaimed(free) && /^(GK|DEF|MID|FWD) \d+$/.test(s.hostWorld.nameFor(free)),
    `"${s.hostWorld.nameFor(free)}" — nobody is in it, and it says so`,
  )
}

{
  // Both people in a match are people. The roster used to carry only the
  // peers, which left whoever was running it anonymous to everybody in it.
  const s = link({ name: 'ALEX' })
  s.hostWorld.setName(s.hostWorld.controlledId, 'SAM')
  run(s, 1.5)
  const hostShirt = s.clientWorld.player(s.hostWorld.controlledId)!
  ok(
    "and so does the host's",
    s.clientWorld.nameFor(hostShirt) === 'SAM',
    `the guest sees the host as "${s.clientWorld.nameFor(hostShirt)}"`,
  )
}

{
  // You are seated by the host, which is rarely the shirt you were put on
  // when the world was built. Your name has to come with you, or it stays
  // behind on a shirt somebody else is about to take.
  const s = link({ name: 'ALEX' })
  const before = s.clientWorld.controlledId
  s.clientWorld.setName(before, 'ALEX')
  run(s, 1.5)
  const seat = s.client.playerId
  const wearing = s.clientWorld.players.filter((p) => s.clientWorld.nameFor(p) === 'ALEX')
  ok(
    'your name is on exactly one shirt',
    wearing.length === 1 && wearing[0].id === seat && seat !== before,
    `started on shirt ${before}, seated at ${seat}; "ALEX" appears ${wearing.length}×`,
  )
}

// ---- 2. ping ----------------------------------------------------------------
{
  // 40 ms each way is 80 ms round trip.
  const s = link({ delay: 0.04 })
  run(s, 3)
  const peer = s.host.roster()[0]
  ok(
    'the host times the round trip',
    peer && Math.abs(peer.ping - 80) < 25,
    `80 ms of real latency measured as ${peer?.ping ?? -1} ms`,
  )
}

{
  const s = link({ delay: 0.04 })
  run(s, 3)
  ok(
    'and tells the client its own',
    Math.abs(s.client.ping - 80) < 25,
    `the guest is shown ${s.client.ping} ms`,
  )
}

// ---- 3. reconnect -----------------------------------------------------------
{
  const s = link({ token: 'same-person' })
  run(s, 1)
  const seat = s.client.playerId
  s.b.cut()
  // Long enough to time out, nowhere near long enough to lose the seat.
  run(s, NET.timeout + 2)
  const heldDuring = s.hostWorld.netSeats.has(seat)
  s.b.restore()
  run(s, 1)
  ok(
    'a dropped player keeps their shirt',
    heldDuring && s.client.playerId === seat && s.client.joined,
    `timed out, seat ${seat} held, same seat given back on return`,
  )
}

{
  const s = link({ token: 'same-person' })
  run(s, 1)
  const seat = s.client.playerId
  s.b.cut()
  run(s, 2)
  ok(
    'and the match carries on without them',
    s.client.reconnecting && !s.client.error,
    `the guest is reconnecting, not ejected (error: ${s.client.error ?? 'none'})`,
  )
  void seat
}

{
  const s = link({ token: 'gone-for-good' })
  run(s, 1)
  const seat = s.client.playerId
  s.b.cut()
  // Past the grace period: the seat has to come back, or one closed tab holds
  // a shirt for the rest of the match.
  run(s, NET.grace + 3)
  ok(
    'but not for ever',
    !s.hostWorld.netSeats.has(seat),
    `after ${NET.grace}s the shirt goes back on the peg`,
  )
}

{
  const s = link()
  run(s, 1)
  const seat = s.client.playerId
  const p = s.hostWorld.player(seat)!
  // Sprint, then vanish mid-stride.
  const sprint = emptyCommand()
  sprint.move = { x: 1, y: 0 }
  sprint.aim = { x: 1, y: 0 }
  sprint.sprint = true
  run(s, 1, emptyCommand(), sprint)
  s.b.cut()
  run(s, NET.timeout + 3)
  ok(
    'a dropped player stops running',
    p.speed < 0.5,
    `last command was a sprint; ${(NET.timeout + 3).toFixed(0)}s later they are doing ${p.speed.toFixed(2)} m/s`,
  )
}

// ---- 3b. jumps cross the wire -----------------------------------------------
{
  const s = link()
  run(s, 1)
  const seat = s.client.playerId
  // Somebody else on the pitch leaves the ground. The snapshot carried no
  // height at all, so every jump — and every header taken off one — happened
  // with both feet on the floor as far as anybody else could see.
  const other = s.hostWorld.players.find((p) => p.id !== seat)!
  other.jump()
  let hostPeak = 0
  let seenPeak = 0
  for (let i = 0; i < 45; i++) {
    frame(s, 1 / 60)
    hostPeak = Math.max(hostPeak, other.z)
    seenPeak = Math.max(seenPeak, s.clientWorld.player(other.id)!.z)
  }
  ok(
    'a jump is visible to everyone else',
    seenPeak > hostPeak * 0.8,
    `host reached ${hostPeak.toFixed(2)} m; the guest saw ${seenPeak.toFixed(2)} m`,
  )
}

{
  const s = link()
  run(s, 1)
  const seat = s.client.playerId
  const other = s.hostWorld.players.find((p) => p.id !== seat)!
  other.jump()
  for (let i = 0; i < 90; i++) frame(s, 1 / 60)
  const mirror = s.clientWorld.player(other.id)!
  // Height is the host's to decide. Integrating it locally as well ran the
  // local vz away to −6 m/s against a height that kept being reset, and left
  // `airborne` latched on for ever because it also tests vz.
  ok(
    'and the client does not fight the host over it',
    mirror.z === 0 && Math.abs(mirror.vz) < 0.01 && !mirror.airborne,
    `after landing: z ${mirror.z.toFixed(2)}, vz ${mirror.vz.toFixed(2)}, airborne ${mirror.airborne}`,
  )
}

// ---- 4. spectators ----------------------------------------------------------
{
  const s = link({ spectate: true })
  run(s, 1)
  ok(
    'a spectator takes no shirt',
    s.client.joined && s.client.playerId === -1 && s.hostWorld.netSeats.size === 0,
    `joined, holding ${s.hostWorld.netSeats.size} seats`,
  )
}

{
  // Fill every seat, then have somebody turn up to watch.
  const s = link({ spectate: true })
  while (s.hostWorld.claimSeat() >= 0) { /* take them all */ }
  run(s, 1)
  ok(
    'and can watch a pitch that is full',
    s.client.joined && !s.client.error,
    `every shirt taken, and the spectator still got in`,
  )
}

{
  const s = link({ spectate: true })
  const had = s.clientWorld.controlledId
  s.clientWorld.setName(had, 'WATCHER')
  run(s, 1.5)
  ok(
    'and holds no shirt locally either',
    s.clientWorld.controlledId === -1 &&
      s.clientWorld.getControlledPlayer() === null &&
      !s.clientWorld.players.some((p) => s.clientWorld.nameFor(p) === 'WATCHER'),
    `was on shirt ${had} from the menu; now owns none, and their name is on nobody`,
  )
}

{
  const s = link({ spectate: true })
  run(s, 1)
  const before = s.hostWorld.players.map((p) => ({ x: p.x, y: p.y }))
  const sprint = emptyCommand()
  sprint.move = { x: 1, y: 0 }
  sprint.sprint = true
  run(s, 2, emptyCommand(), sprint)
  const moved = s.hostWorld.players.some((p, i) => Math.hypot(p.x - before[i].x, p.y - before[i].y) > 0.2)
  ok(
    'and moves nobody by pressing keys',
    !moved,
    `two seconds of a spectator holding sprint: nothing on the pitch moved`,
  )
}

// ---- 5. a full pitch still turns players away --------------------------------
{
  const s = link()
  while (s.hostWorld.claimSeat() >= 0) { /* take them all */ }
  run(s, 1)
  ok(
    'a player is told when there is no room',
    !!s.client.error && !s.client.joined,
    `"${s.client.error}"`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
