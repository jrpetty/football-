import { DEFEND, FIELD, GK } from '../config'
import { clamp, clamp01 } from '../core/math'
import * as V from '../core/vec'
import type { Vec2 } from '../core/vec'
import type { Player } from '../entities/player'
import type { World } from '../match/world'
import { emptyCommand } from '../types'
import type { Command, Team } from '../types'
import * as F from '../match/field'

// The team brains. Every AI player produces exactly the same Command a human
// would, so the simulation treats them identically. Coordination is emergent:
// per team we pick the single player nearest the ball as the "chaser" and give
// everyone else positional roles that slide with the ball.

let prevPossessor = -999

export function computeAiCommands(world: World, dt = 1 / 60): Map<number, Command> {
  const cmds = new Map<number, Command>()
  const owner = world.possessorId != null ? world.player(world.possessorId) : null
  const ownerTeam: Team | null = owner ? owner.team : null

  // Reset a carrier's decision clock the moment possession changes hands.
  if (world.possessorId !== prevPossessor) {
    prevPossessor = world.possessorId ?? -999
    if (owner) owner.aiDecide = 0.35 + owner.aiSeed * 0.6
  }

  for (const team of ['home', 'away'] as Team[]) {
    const mates = world.teammates(team)
    const opponents = world.teammates(world.otherTeam(team))
    const attacking = ownerTeam === team
    // The human's player is never assigned an AI job — including chasing the
    // ball — so a team-mate always steps up to press instead of the team
    // standing around waiting for a player the AI doesn't control.
    const outfield = mates.filter((p) => p.role !== 'GK' && !world.isHumanDriven(p))
    const chaser = nearest(outfield, world.ball.pos)

    for (const p of mates) {
      const cmd = emptyCommand()
      if (p.role === 'GK') {
        goalkeeper(world, p, cmd, owner, dt)
      } else if (owner && owner.id === p.id) {
        carrier(p, cmd, mates, opponents, dt)
      } else if (chaser && chaser.id === p.id && (!attacking || !owner)) {
        // Give the ball room during restarts and while the other keeper holds it.
        let standoff = 0
        if (world.restartProtect > 0 && team !== world.restartTeam) standoff = 3.4
        else if (world.keeperHeldByTeam() === world.otherTeam(team)) standoff = 3.2
        presser(world, p, cmd, owner, standoff)
      } else {
        support(world, p, cmd, attacking, opponents)
      }
      cmds.set(p.id, cmd)
    }
  }
  return cmds
}

// ---- goalkeeper -----------------------------------------------------------

function goalkeeper(world: World, p: Player, cmd: Command, owner: Player | null, dt: number) {
  const team = p.team
  const goal = F.ownGoalCenter(team)
  const goalX = F.ownGoalLineX(team)
  const [postA, postB] = F.goalPostYs()
  const b = world.ball
  const dxBall = Math.abs(b.x - goalX)

  cmd.aim = { x: F.attackDir(team), y: 0 }

  // Distribution: if the keeper actually has the ball, play it out.
  if (world.possessorId === p.id) {
    p.aiDecide -= dt
    if (p.aiDecide <= 0) {
      const mate = bestOutletForKeeper(world, p)
      const aim = mate ? V.dir(p.pos, mate.pos) : { x: F.attackDir(team), y: 0 }
      const dist = mate ? V.dist(p.pos, mate.pos) : 30
      cmd.aim = aim
      cmd.kick = {
        type: mate ? 'pass' : 'clear',
        power: clamp01(dist / 30 + 0.35),
        aim,
        loft: dist > 16 || !mate ? 0.75 : 0.15,
        spin: 0,
      }
    }
    return
  }

  // Angle-narrowing position on the line between ball and goal centre.
  let standoff = GK.lineDepth
  if (dxBall < 24) standoff += (1 - dxBall / 24) * 4.5
  const central = Math.abs(b.y - goal.y) < FIELD.goalWidth * 1.3
  const danger = owner != null && owner.team !== team && dxBall < 16 && central
  if (danger) standoff += (1 - dxBall / 16) * 5

  const shotComing =
    Math.sign(b.vx) === -F.attackDir(team) && Math.abs(b.vx) > 8 && dxBall < 22

  let target: Vec2
  if (shotComing && Math.abs(b.vx) > 0.5) {
    const t = (goalX - b.x) / b.vx
    const yCross = t > 0 ? b.y + b.vy * t : b.y
    target = { x: goalX + F.attackDir(team) * GK.lineDepth * 0.5, y: clamp(yCross, postA - 0.2, postB + 0.2) }
    cmd.sprint = true
  } else {
    const ty = clamp(goal.y + (b.y - goal.y) * 0.7, postA - 0.5, postB + 0.5)
    target = { x: goalX + F.attackDir(team) * standoff, y: ty }
  }

  const to = V.sub(target, p.pos)
  const d = V.len(to)
  cmd.move = d > 0.05 ? V.scale(V.normalize(to), clamp01(d / 1.2)) : { x: 0, y: 0 }
  if (d > 3 || shotComing || danger) cmd.sprint = true
}

function bestOutletForKeeper(world: World, gk: Player): Player | null {
  const dir = F.attackDir(gk.team)
  let best: Player | null = null
  let bestScore = -Infinity
  for (const m of world.teammates(gk.team)) {
    if (m.id === gk.id || m.role === 'GK') continue
    const ahead = (m.x - gk.x) * dir
    const opp = nearest(world.teammates(world.otherTeam(gk.team)), m.pos)
    const open = opp ? V.dist(opp.pos, m.pos) : 12
    const score = ahead * 0.4 + open
    if (score > bestScore) {
      bestScore = score
      best = m
    }
  }
  return best
}

// ---- ball carrier ---------------------------------------------------------

function carrier(
  p: Player,
  cmd: Command,
  mates: Player[],
  opponents: Player[],
  dt: number,
) {
  const team = p.team
  const goal = F.targetGoalCenter(team)
  const goalX = F.targetGoalLineX(team)
  const distGoal = V.dist(p.pos, goal)
  const outfieldOpp = opponents.filter((o) => o.role !== 'GK')
  const nearestOpp = nearest(outfieldOpp, p.pos)
  const pressure = nearestOpp ? V.dist(nearestOpp.pos, p.pos) : 99
  const pressured = pressure < 2.4
  p.aiDecide -= dt

  cmd.aim = V.dir(p.pos, goal)

  // Shoot on sight: in range with a clear lane, take it — don't dawdle.
  const keeper = opponents.find((o) => o.role === 'GK') ?? null
  if (distGoal < 20) {
    const aimPt = pickShotTarget(goalX, keeper)
    if (Math.sign(aimPt.x - p.x) === F.attackDir(team) && laneClear(p.pos, aimPt, outfieldOpp, 0.85)) {
      cmd.aim = V.dir(p.pos, aimPt)
      cmd.kick = {
        type: 'shot',
        power: clamp01(0.5 + distGoal / 34),
        aim: cmd.aim,
        // Chip the keeper if it has rushed off its line; otherwise keep it down.
        loft: keeper != null && Math.abs(keeper.x - goalX) > 4.5 && distGoal > 8 ? 0.9 : -0.2,
        spin: (p.aiSeed - 0.5) * 0.5,
      }
      return
    }
  }

  // Pass only when it genuinely helps: forward progress, or a safe out under
  // pressure. This stops the team knocking it sideways forever.
  const option = bestPassOption(p, mates, opponents, !pressured)
  if (option && (pressured || option.progress > 3 || p.aiDecide <= 0)) {
    const to = V.sub(option.target, p.pos)
    const dd = V.len(to)
    cmd.aim = V.normalize(to)
    cmd.kick = {
      type: option.through ? 'through' : 'pass',
      power: clamp01(dd / 27 + 0.22),
      aim: cmd.aim,
      loft: option.lofted ? 0.7 : 0,
      spin: 0,
    }
    p.aiDecide = 0.45
    return
  }

  // Otherwise drive at goal, veering off the nearest defender and the walls.
  let dir = V.dir(p.pos, goal)
  if (nearestOpp && pressure < 4.5) {
    const away = V.normalize(V.sub(p.pos, nearestOpp.pos))
    dir = V.normalize(V.add(dir, V.scale(away, 1.1)))
  }
  dir = avoidWalls(p, dir)
  cmd.move = dir
  cmd.sprint = distGoal > 8 && p.stamina > 12
  cmd.aim = V.dir(p.pos, goal)
  if (p.aiDecide <= 0) p.aiDecide = 0.3
}

interface PassOption {
  target: Vec2
  through: boolean
  lofted: boolean
  progress: number
}

function bestPassOption(
  p: Player,
  mates: Player[],
  opponents: Player[],
  requireProgress: boolean,
): PassOption | null {
  const team = p.team
  const goal = F.targetGoalCenter(team)
  const myGoalDist = V.dist(p.pos, goal)
  let best: PassOption | null = null
  let bestScore = 1.0
  for (const m of mates) {
    if (m.id === p.id || m.role === 'GK') continue
    const dd = V.dist(p.pos, m.pos)
    if (dd < 4 || dd > 32) continue
    const opp = nearest(opponents, m.pos)
    const open = opp ? V.dist(opp.pos, m.pos) : 12
    if (open < 2) continue
    if (!laneClear(p.pos, m.pos, opponents, 0.9)) continue

    const progress = myGoalDist - V.dist(m.pos, goal)
    if (requireProgress && progress < 2) continue
    // Progress dominates; openness is a tie-breaker. No more sideways shuffling.
    const score = progress * 1.4 + open * 0.35 - dd * 0.03
    if (score > bestScore) {
      bestScore = score
      const ahead = V.dist(m.pos, goal) < myGoalDist - 3
      const through = ahead && open > 3.5 && m.speed > 1.5
      const lead = through
        ? V.add(m.pos, V.add(V.scale(m.vel, 0.45), V.scale(V.dir(m.pos, goal), 3.5)))
        : m.pos
      best = { target: lead, through, lofted: dd > 18 && !through, progress }
    }
  }
  return best
}

// ---- pressing / chasing ---------------------------------------------------

function presser(world: World, p: Player, cmd: Command, owner: Player | null, standoff = 0) {
  const b = world.ball
  cmd.aim = { x: F.attackDir(p.team), y: 0 }

  if (standoff > 0) {
    // Hold position off the ball (restart spacing) — no tackling.
    const fromBall = V.normalize(V.sub(p.pos, b.pos))
    const target = V.add(b.pos, V.scale(fromBall, standoff))
    const to = V.sub(target, p.pos)
    const d = V.len(to)
    cmd.move = d > 0.15 ? V.scale(V.normalize(to), Math.min(1, d)) : { x: 0, y: 0 }
    return
  }

  const lead = V.add(b.pos, V.scale({ x: b.vx, y: b.vy }, 0.14))
  const to = V.sub(lead, p.pos)
  const d = V.len(to)
  cmd.move = d > 0.05 ? V.normalize(to) : { x: 0, y: 0 }
  cmd.sprint = d > 2 && p.stamina > 6

  const ballDist = V.dist(p.pos, b.pos)
  if (owner && owner.team !== p.team && ballDist < DEFEND.tackleRange * 0.95) {
    cmd.tackle = true
  }
}

// ---- off-ball positioning -------------------------------------------------

function support(
  world: World,
  p: Player,
  cmd: Command,
  attacking: boolean,
  opponents: Player[],
) {
  const team = p.team
  const dir = F.attackDir(team)
  const b = world.ball
  let target: Vec2

  if (attacking) {
    // Push up and offer an option; forwards make the deepest runs.
    const push = p.role === 'FWD' ? 16 : p.role === 'MID' ? 9 : 4
    let ax = p.homePos.x + dir * push
    ax += (b.x - ax) * 0.15
    let ay = p.homePos.y + (b.y - FIELD.width / 2) * 0.25
    target = { x: ax, y: ay }
  } else {
    if (p.role === 'DEF' || p.role === 'MID') {
      // Man-mark the nearest dangerous opponent, staying goal-side.
      const threats = opponents.filter((o) => o.role !== 'GK')
      const mark = nearest(threats, p.homePos)
      const ownGoal = F.ownGoalCenter(team)
      if (mark) {
        const gside = V.normalize(V.sub(ownGoal, mark.pos))
        target = V.add(mark.pos, V.scale(gside, 1.8))
      } else {
        target = { x: p.homePos.x - dir * 5, y: p.homePos.y }
      }
    } else {
      // Forwards drop a little and stay outletted for the counter.
      target = { x: p.homePos.x - dir * 3, y: p.homePos.y + (b.y - FIELD.width / 2) * 0.2 }
    }
  }

  target = {
    x: clamp(target.x, 1.5, FIELD.length - 1.5),
    y: clamp(target.y, 1.5, FIELD.width - 1.5),
  }
  const to = V.sub(target, p.pos)
  const d = V.len(to)
  cmd.move = d > 0.1 ? V.scale(V.normalize(to), clamp01(d / 2)) : { x: 0, y: 0 }
  cmd.sprint = d > 7 && p.stamina > 20
  cmd.aim = { x: dir, y: 0 }
}

// ---- helpers --------------------------------------------------------------

function nearest(list: Player[], to: Vec2): Player | null {
  let best: Player | null = null
  let bd = Infinity
  for (const p of list) {
    const d = V.dist2(p.pos, to)
    if (d < bd) {
      bd = d
      best = p
    }
  }
  return best
}

// Distance from point c to segment a→b.
function pointSegDist(c: Vec2, a: Vec2, b: Vec2): number {
  const abx = b.x - a.x
  const aby = b.y - a.y
  const l2 = abx * abx + aby * aby
  if (l2 < 1e-6) return V.dist(c, a)
  let t = ((c.x - a.x) * abx + (c.y - a.y) * aby) / l2
  t = clamp(t, 0, 1)
  return Math.hypot(c.x - (a.x + abx * t), c.y - (a.y + aby * t))
}

function laneClear(a: Vec2, b: Vec2, opponents: Player[], radius: number): boolean {
  for (const o of opponents) {
    // Only defenders standing *between* a and b matter.
    if (pointSegDist(o.pos, a, b) < radius + o.radius) return false
  }
  return true
}

function pickShotTarget(goalX: number, keeper: Player | null): Vec2 {
  const [a, b] = F.goalPostYs()
  const inset = 0.55
  let y = (a + b) / 2
  if (keeper) y = keeper.y > (a + b) / 2 ? a + inset : b - inset
  return { x: goalX, y }
}

function avoidWalls(p: Player, dir: Vec2): Vec2 {
  const m = 3.5
  const d = { x: dir.x, y: dir.y }
  if (p.y < m && d.y < 0) d.y += (m - p.y) / m
  if (p.y > FIELD.width - m && d.y > 0) d.y -= (p.y - (FIELD.width - m)) / m
  return V.normalize(d)
}
