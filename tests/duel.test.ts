// Two bodies meeting. Until now players pushed apart by geometry alone, so a
// shoulder-to-shoulder race was decided by nothing at all. These check that the
// contest is settled by what your body is doing — pace into the contact, which
// way you were facing, whether you were set — and never by a button.
import { World } from '../src/game/match/world'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, FIELD } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const cfg = {
  teamSize: 3, halfLength: 6000, mode: 'match' as const, singleKeeper: false,
  view: '3d' as const,
  quality: 'medium' as const, position: 'MID' as const, heightSens: 1.6, curveSens: 1.15,
  humanControlled: true,
}
const results: string[] = []
const ok = (n: string, p: boolean, d: string) =>
  results.push(`${p ? 'PASS' : 'FAIL'}  ${n.padEnd(42)} ${d}`)

const CX = FIELD.length / 2, CY = FIELD.width / 2

// Put two outfield players in contact and run the sim, feeding each a command.
function duel(
  setup: (a: any, b: any) => void,
  cmdA: Command,
  cmdB: Command,
  seconds = 0.8,
  ballAt?: (a: any, b: any) => { x: number; y: number },
) {
  const w = new World(cfg)
  w.phase = 'playing'
  const me = w.getControlledPlayer()!
  const foe = w.players.find((p) => p.team === 'away' && p.role !== 'GK')!
  for (const p of w.players) { p.x = 2; p.y = 2; p.vx = p.vy = 0 }
  // Park the ball far away so nothing else is going on.
  w.ball.setPos(4, 4, 0); w.ball.stop()
  setup(me, foe)
  // Shielding is only real when there is a ball to shield, so a test that wants
  // one has to put it there — setting the flag by hand gets overwritten.
  if (ballAt) {
    const at = ballAt(me, foe)
    w.ball.setPos(at.x, at.y, 0)
    w.ball.stop()
  }
  const a0 = { x: me.x, y: me.y }
  const b0 = { x: foe.x, y: foe.y }
  // The foe is a network seat so their command is honoured like any other.
  w.netSeats.add(foe.id)
  const n = Math.round(seconds / (1 / 60))
  for (let i = 0; i < n; i++) {
    w.setRemoteCommand(foe.id, cmdB)
    w.update(1 / 60, cmdA)
  }
  return {
    me, foe, w,
    meMoved: Math.hypot(me.x - a0.x, me.y - a0.y),
    foeMoved: Math.hypot(foe.x - b0.x, foe.y - b0.y),
  }
}
const go = (x: number, y: number, o: Partial<Command> = {}): Command => ({
  ...emptyCommand(), move: { x, y }, aim: { x, y }, sprint: true, ...o,
})

// ---- 1. a shoulder charge actually moves someone ---------------------------
{
  // He is standing still, side-on; you arrive into him at pace.
  const r = duel((a, b) => {
    a.x = CX - 2.5; a.y = CY; a.heading = 0; a.vx = 7
    b.x = CX; b.y = CY; b.heading = Math.PI / 2
  }, go(1, 0), emptyCommand())
  ok(
    'running into someone knocks them off their line',
    r.foeMoved > 0.25,
    `stood still and got shifted ${r.foeMoved.toFixed(2)} m`,
  )
}

// ---- 2. ...and it costs the man who was charged his pace -------------------
{
  const clean = duel((a, b) => {
    a.x = CX - 20; a.y = CY; a.heading = 0
    b.x = CX; b.y = CY - 20; b.heading = 0
  }, go(1, 0), go(1, 0), 1.2)
  const bumped = duel((a, b) => {
    a.x = CX - 2.2; a.y = CY; a.heading = 0; a.vx = 8
    b.x = CX; b.y = CY; b.heading = 0; b.vx = 4
  }, go(1, 0), go(1, 0), 1.2)
  ok(
    'a body check takes pace out of the loser',
    bumped.foe.speed < clean.foe.speed - 0.5,
    `unbothered ${clean.foe.speed.toFixed(1)} m/s vs ${bumped.foe.speed.toFixed(1)} m/s after contact`,
  )
}

// ---- 3. shielding means you hold your ground -------------------------------
{
  const open = duel((a, b) => {
    a.x = CX - 2.5; a.y = CY; a.heading = 0; a.vx = 7
    b.x = CX; b.y = CY; b.heading = 0
  }, go(1, 0), emptyCommand(), 0.8, (_a, b) => ({ x: b.x + 0.7, y: b.y }))
  const set = duel((a, b) => {
    a.x = CX - 2.5; a.y = CY; a.heading = 0; a.vx = 7
    b.x = CX; b.y = CY; b.heading = 0
  }, go(1, 0), { ...emptyCommand(), aim: { x: -1, y: 0 }, shield: true }, 0.8,
     (_a, b) => ({ x: b.x + 0.7, y: b.y }))
  ok(
    'a set body is harder to move than an open one',
    set.foeMoved < open.foeMoved,
    `open ${open.foeMoved.toFixed(2)} m vs braced ${set.foeMoved.toFixed(2)} m`,
  )
}

// ---- 4. a jumping player has nothing to push against -----------------------
{
  const grounded = duel((a, b) => {
    a.x = CX - 2.5; a.y = CY; a.heading = 0; a.vx = 7
    b.x = CX; b.y = CY; b.heading = Math.PI
  }, go(1, 0), emptyCommand(), 0.5)
  const up = duel((a, b) => {
    a.x = CX - 2.5; a.y = CY; a.heading = 0; a.vx = 7
    b.x = CX; b.y = CY; b.heading = Math.PI; b.vz = 3.6; b.z = 0.3
  }, go(1, 0), emptyCommand(), 0.5)
  ok(
    'you cannot hold anyone off in mid-air',
    up.foeMoved > grounded.foeMoved,
    `grounded shifted ${grounded.foeMoved.toFixed(2)} m, airborne ${up.foeMoved.toFixed(2)} m`,
  )
}

// ---- 5. a gentle lean is not a challenge -----------------------------------
{
  const r = duel((a, b) => {
    a.x = CX - 0.75; a.y = CY; a.heading = 0; a.vx = 0.4
    b.x = CX; b.y = CY; b.heading = Math.PI
  }, emptyCommand(), emptyCommand(), 0.5)
  ok(
    'standing next to someone is not a shove',
    r.foeMoved < 0.25,
    `walking pace contact moved them ${r.foeMoved.toFixed(3)} m`,
  )
  void SIM
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
