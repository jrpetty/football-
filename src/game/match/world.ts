import { BALL, CONTROL, DEFEND, FIELD, GK, KICK, MATCH, PLAYER, SIM, WALL } from '../config'
import { clamp } from '../core/math'
import * as V from '../core/vec'
import type { Vec2 } from '../core/vec'
import { Ball } from '../physics/ball'
import { Player } from '../entities/player'
import { computeAiCommands } from '../ai/director'
import { emptyCommand, emptyStats } from '../types'
import type { Command, MatchConfig, Restart, Role, Stats, Team } from '../types'
import { formation } from './formation'
import * as F from './field'

export type GamePhase = 'kickoff' | 'playing' | 'goal' | 'halftime' | 'fulltime'

export interface Effect {
  type: 'kick' | 'save' | 'goal' | 'tackle' | 'post' | 'whistle' | 'spawn'
  x: number
  y: number
  t: number
  life: number
  color?: string
}

export interface Announce {
  text: string
  sub?: string
  t: number
  life: number
}

const CONTROL_HEIGHT: Record<string, number> = { GK: 2.7 }
const controlHeight = (role: string): number => CONTROL_HEIGHT[role] ?? 1.8

// The authoritative simulation. Fixed-step (SIM.dt) physics with a phase state
// machine on top for kickoffs, goals and half-time. Human and AI both feed it
// Commands; it never distinguishes them past that point.
export class World {
  ball = new Ball()
  players: Player[] = []
  config: MatchConfig
  score = { home: 0, away: 0 }
  stats: Record<Team, Stats> = { home: emptyStats(), away: emptyStats() }
  clock = 0
  half = 1
  phase: GamePhase = 'kickoff'
  phaseTimer = MATCH.kickoffDelay
  possessorId: number | null = null
  controlledId = -1
  kickoffTeam: Team = 'home'
  restartTeam: Team | null = null
  restartKind: Restart = 'kickoff'
  restartProtect = 0
  // After a keeper gathers the ball it holds cleanly for a beat; opponents back
  // off and can't nick it, giving the keeper time to distribute.
  keeperHold = 0
  keeperHoldId = -1
  effects: Effect[] = []
  announce: Announce | null = null
  lastGoalTeam: Team | null = null

  // How far we are between the last physics step and the next, 0..1. Renderers
  // interpolate with this so motion looks smooth at any frame rate rather than
  // stepping once per tick — which is what reads as a "laggy" ball.
  renderAlpha = 0

  private accumulator = 0
  private idCounter = 0
  private lastPass: { team: Team; fromId: number } | null = null

  constructor(config: MatchConfig) {
    this.config = config
    this.setupTeams()
    this.kickoffTeam = 'home'
    this.placeForKickoff('home')
    if (config.mode === 'training') {
      this.phase = 'playing'
      this.resetTrainingBall()
    }
  }

  // ---- setup -------------------------------------------------------------

  private setupTeams() {
    this.players = []

    // Training is a solo sandbox: just you, a ball and two empty goals. No
    // team-mates, no opponents, no keepers — nothing on the pitch is simulated
    // by an AI, so you can drill touches, strikes and flicks without
    // interference.
    if (this.config.mode === 'training') {
      const you = new Player(this.idCounter++, 'home', this.config.position, 10, {
        x: FIELD.length * 0.35,
        y: FIELD.width / 2,
      })
      this.players.push(you)
      this.controlledId = you.id
      return
    }

    const teams: Team[] = ['home', 'away']
    for (const team of teams) {
      const slots = formation(team, this.config.teamSize)
      let num = team === 'home' ? 1 : 1
      slots.forEach((slot) => {
        if (slot.role === 'GK' && this.config.singleKeeper && team === 'away') return
        const p = new Player(this.idCounter++, team, slot.role, num++, slot.base)
        this.players.push(p)
      })
    }
    this.controlledId = this.pickHumanPlayer(this.config.position)
  }

  // You are one player for the whole match — the same footballer every minute,
  // as in Pro Soccer Online. Pick whoever fills the chosen position; if this
  // team size doesn't field that role, take the closest thing to it.
  private pickHumanPlayer(position: Role): number {
    const home = this.players.filter((p) => p.team === 'home')
    const exact = home.filter((p) => p.role === position)
    if (exact.length) return exact[Math.floor(exact.length / 2)].id

    if (position === 'GK') {
      const gk = home.find((p) => p.role === 'GK')
      if (gk) return gk.id
    }
    // Fall back by how far up the pitch the role sits, nearest first.
    const order: Role[] = ['GK', 'DEF', 'MID', 'FWD']
    const want = order.indexOf(position)
    const outfield = home.filter((p) => p.role !== 'GK')
    const pool = outfield.length ? outfield : home
    let best = pool[0]
    let bestGap = Infinity
    for (const p of pool) {
      const gap = Math.abs(order.indexOf(p.role) - want)
      if (gap < bestGap) {
        bestGap = gap
        best = p
      }
    }
    return best.id
  }

  private placeForKickoff(kickTeam: Team) {
    for (const p of this.players) {
      // Reset to home positions, but push everyone into their own half.
      const base = { ...p.homePos }
      const ownHalf = p.team === 'home' ? base.x < FIELD.length / 2 : base.x > FIELD.length / 2
      if (!ownHalf && p.role !== 'GK') {
        base.x = p.team === 'home' ? FIELD.length / 2 - 3 : FIELD.length / 2 + 3
      }
      p.x = base.x
      p.y = base.y
      p.vx = p.vy = 0
      p.slideTimer = 0
      p.stamina = Math.min(PLAYER.staminaMax, p.stamina + 20)
    }
    // The kicking team gets one player onto the centre spot.
    const taker = this.nearestPlayer(F.centerSpot(), (p) => p.team === kickTeam && p.role !== 'GK')
    if (taker) {
      taker.x = F.centerSpot().x - F.attackDir(kickTeam) * 1.2
      taker.y = F.centerSpot().y
    }
    this.ball.setPos(F.centerSpot().x, F.centerSpot().y, 0)
    this.ball.stop()
    this.ball.lastTouchTeam = null
    this.possessorId = null
    this.restartProtect = 0
    this.restartKind = 'kickoff'
    this.restartTeam = kickTeam
  }

  // ---- main tick ---------------------------------------------------------

  update(frameDt: number, humanCmd: Command) {
    // Cap the frame delta so a stall can't make the physics explode, but keep the
    // cap generous: anything above ~10 fps should still run at true speed rather
    // than sliding into slow motion.
    const dt = Math.min(frameDt, SIM.maxFrameDt)
    this.updateEffects(dt)
    this.updateAnnounce(dt)
    const commands = this.buildCommands(humanCmd, dt)

    this.accumulator += dt
    let steps = 0
    while (this.accumulator >= SIM.dt && steps < SIM.maxStepsPerFrame) {
      this.step(commands)
      this.accumulator -= SIM.dt
      steps++
    }
    this.renderAlpha = this.accumulator / SIM.dt
  }

  private buildCommands(humanCmd: Command, dt: number): Map<number, Command> {
    const commands = computeAiCommands(this, dt)
    if (this.config.humanControlled !== false) {
      const controlled = this.players.find((p) => p.id === this.controlledId)
      if (controlled) commands.set(controlled.id, humanCmd)
    }
    return commands
  }

  private step(commands: Map<number, Command>) {
    const dt = SIM.dt
    switch (this.phase) {
      case 'kickoff':
        // Brief "get ready" freeze — players and ball hold their placed spots.
        this.phaseTimer -= dt
        if (this.phaseTimer <= 0) {
          this.phase = 'playing'
          this.setAnnounce('KICK OFF', undefined, 1.4)
        }
        return
      case 'goal':
        this.phaseTimer -= dt
        if (this.phaseTimer <= 0) {
          this.placeForKickoff(this.kickoffTeam)
          this.phase = 'kickoff'
          this.phaseTimer = MATCH.kickoffDelay
        }
        return
      case 'halftime':
        this.phaseTimer -= dt
        if (this.phaseTimer <= 0) this.startSecondHalf()
        return
      case 'fulltime':
        return
      case 'playing':
        this.simulate(commands, dt, true)
        this.clock += dt
        if (this.restartProtect > 0) this.restartProtect -= dt
        if (this.keeperHold > 0) this.keeperHold -= dt
        if (this.config.mode === 'match' && this.clock >= this.config.halfLength) {
          this.endHalf()
        }
        return
    }
  }

  private endHalf() {
    if (this.half === 1) {
      this.half = 2
      this.phase = 'halftime'
      this.phaseTimer = 2.4
      this.setAnnounce('HALF TIME', `${this.score.home} – ${this.score.away}`, 2.4)
    } else {
      this.phase = 'fulltime'
      const msg =
        this.score.home === this.score.away
          ? 'FULL TIME · DRAW'
          : `FULL TIME · ${this.score.home > this.score.away ? 'HOME' : 'AWAY'} WIN`
      this.setAnnounce(msg, `${this.score.home} – ${this.score.away}`, 6)
    }
  }

  private startSecondHalf() {
    this.clock = 0
    this.kickoffTeam = this.kickoffTeam === 'home' ? 'away' : 'home'
    this.placeForKickoff(this.kickoffTeam)
    this.phase = 'kickoff'
    this.phaseTimer = MATCH.kickoffDelay
  }

  // ---- the simulation ----------------------------------------------------

  private simulate(commands: Map<number, Command>, dt: number, live: boolean) {
    // 1. Movement, and where each player is looking. You face the way you're
    // running; standing still, you face where you're aiming. Turning is
    // rate-limited so direction changes read as a pivot, not a snap.
    for (const p of this.players) {
      const cmd = commands.get(p.id) ?? emptyCommand()
      p.steer(cmd.move, V.len(cmd.move), cmd.sprint, dt)
      p.faceDirection(V.len(cmd.move) > 0.05 ? cmd.move : cmd.aim, dt)
    }

    // 2. Tackles / slides (only when the ball is live).
    if (live) this.resolveTackles(commands, dt)

    // 3. Possession & first touch.
    this.resolvePossession(dt)

    // 4. Kicks (live only). A kick clears possession.
    if (live) {
      for (const p of this.players) {
        const cmd = commands.get(p.id)
        if (cmd?.kick && this.canKick(p)) {
          this.executeKick(p, cmd)
          cmd.kick = null // fire once
        }
      }
    }

    // 5. Dribbling. The AI shepherds the ball automatically, but the human's
    // close control is entirely manual — you push the ball with touches and run
    // onto it. Nothing is glued to your feet, which is the whole point of having
    // a dedicated touch button.
    if (this.possessorId != null) {
      const owner = this.player(this.possessorId)
      if (owner && owner.kickCooldown <= 0 && !this.isHumanDriven(owner)) this.dribble(owner)
    }

    // 6. Integrate bodies and ball.
    for (const p of this.players) p.integrate(dt)
    this.separatePlayers()
    this.keepPlayersInBounds()
    this.ball.integrate()
    this.ball.clampSpin()

    // 7. Collisions & saves.
    this.resolveGkSaves(live)
    this.resolveBodyCollisions()
    this.resolvePosts()

    // 8. Ball leaving play.
    this.resolveWallsAndGoals(live)

    // 9. Possession stat.
    if (this.possessorId != null && live) {
      const owner = this.player(this.possessorId)
      if (owner) this.stats[owner.team].possessionTicks++
    }
  }

  // ---- possession & dribbling -------------------------------------------

  private controlPoint(p: Player): Vec2 {
    const f = p.facing
    return { x: p.x + f.x * 0.25, y: p.y + f.y * 0.25 }
  }

  private resolvePossession(dt: number) {
    void dt
    // A keeper gathering the ball holds it cleanly until it distributes.
    if (this.keeperHold > 0 && this.keeperHoldId >= 0) {
      const gk = this.player(this.keeperHoldId)
      if (gk && V.dist(gk.pos, this.ball.pos) < gk.radius + BALL.radius + PLAYER.reach + 0.6) {
        this.possessorId = gk.id
        return
      }
      this.keeperHold = 0
      this.keeperHoldId = -1
    }

    let best: Player | null = null
    let bestScore = Infinity
    for (const p of this.players) {
      if (p.kickCooldown > 0 || p.sliding) continue
      if (this.ball.z > controlHeight(p.role)) continue
      const cp = this.controlPoint(p)
      const d = V.dist(cp, this.ball.pos)
      const reach = p.radius + BALL.radius + PLAYER.reach
      if (d < reach) {
        let score = d
        if (p.id === this.possessorId) score -= 0.6 // stickiness / hysteresis
        if (p.role === 'GK') score -= 0.2
        if (score < bestScore) {
          bestScore = score
          best = p
        }
      }
    }

    const prev = this.possessorId
    if (best) {
      if (best.id !== prev) {
        this.onPossessionGain(best, prev)
        this.firstTouch(best)
      }
      this.possessorId = best.id
    } else {
      this.possessorId = null
    }
  }

  private onPossessionGain(gainer: Player, prevId: number | null) {
    // Pass completion / turnover accounting.
    if (this.lastPass) {
      if (gainer.team === this.lastPass.team && gainer.id !== this.lastPass.fromId) {
        this.stats[gainer.team].passesCompleted++
      }
      this.lastPass = null
    }
    void prevId
  }

  // Taking a ball down out of play. This is for *receiving* — a pass, a clearance,
  // a loose ball. It must never fire on a ball you just played yourself, or every
  // deliberate touch would be snapped straight back to your feet and close
  // control would be impossible.
  private firstTouch(p: Player) {
    if (this.ball.lastTouchId === p.id) return
    const incoming = this.ball.horizontalSpeed
    if (incoming < 3) return
    // Trap the ball to the feet with an error that grows with pace and effort.
    const err = KICK.firstTouchError * (1 + incoming * 0.05) * (p.sprinting ? 1.5 : 1)
    const f = p.facing
    this.ball.z = 0
    this.ball.vz = 0
    this.ball.spin *= 0.3
    this.ball.x = p.x + f.x * (p.radius + BALL.radius) + (Math.random() * 2 - 1) * err
    this.ball.y = p.y + f.y * (p.radius + BALL.radius) + (Math.random() * 2 - 1) * err
    this.ball.vx = p.vx * 0.5
    this.ball.vy = p.vy * 0.5
  }

  private dribble(p: Player) {
    const moving = p.speed > 0.5
    const dirv = moving ? V.normalize(p.vel) : p.facing
    const touch =
      KICK.dribbleTouch + (p.sprinting ? KICK.sprintTouchBonus : 0) + p.speed * 0.04
    const reach = p.radius + BALL.radius + touch
    const target = { x: p.x + dirv.x * reach, y: p.y + dirv.y * reach }
    const k = 11
    let vx = (target.x - this.ball.x) * k
    let vy = (target.y - this.ball.y) * k
    const maxv = p.topSpeed(true) * 1.7 + 4
    const s = Math.hypot(vx, vy)
    if (s > maxv) {
      vx = (vx / s) * maxv
      vy = (vy / s) * maxv
    }
    this.ball.vx = vx
    this.ball.vy = vy
    this.ball.z = 0
    this.ball.vz = 0
    this.ball.spin *= 0.85
    this.ball.lastTouchTeam = p.team
    this.ball.lastTouchId = p.id
  }

  // ---- kicking -----------------------------------------------------------

  private canKick(p: Player): boolean {
    if (p.sliding || p.kickCooldown > 0) return false
    if (this.ball.z > controlHeight(p.role)) return false
    const d = V.dist(p.pos, this.ball.pos)
    return d < p.radius + BALL.radius + PLAYER.reach * 1.25
  }

  // Last kick the simulation actually received — surfaced for debugging/tuning.
  lastKickDebug: { type: string; power: number; loft: number; spin: number } | null = null

  private executeKick(p: Player, cmd: Command) {
    const kick = cmd.kick!
    this.lastKickDebug = { type: kick.type, power: kick.power, loft: kick.loft, spin: kick.spin }
    let aim = V.len(kick.aim) > 0.01 ? V.normalize(kick.aim) : p.facing
    const power = clamp(kick.power, 0, 1)
    const loft = clamp(kick.loft, -1, 1)

    let speed: number
    switch (kick.type) {
      case 'touch':
        speed = KICK.touchMin + (KICK.touchMax - KICK.touchMin) * power
        break
      case 'strike':
        speed = KICK.strikeMin + (KICK.strikeMax - KICK.strikeMin) * power
        break
      case 'shot':
        speed = KICK.shotMin + (KICK.shotMax - KICK.shotMin) * power
        break
      case 'through':
        speed = (KICK.passMin + (KICK.passMax - KICK.passMin) * power) * KICK.throughBias
        break
      case 'clear':
        speed = KICK.shotMax * 0.85
        break
      default:
        speed = KICK.passMin + (KICK.passMax - KICK.passMin) * power
    }

    // Striking is meant to be deterministic — where the ball goes is your aim and
    // your flick, not a dice roll. Only a whisper of scatter remains, growing
    // with power and fatigue, so a full-blooded strike is marginally less
    // precise than a measured one.
    const baseSpread = kick.type === 'touch' ? 0.15 : 0.3 + power * 0.9
    const spreadRad = ((baseSpread + (1 - p.energy) * 0.6) * Math.PI) / 180
    aim = V.rotate(aim, (Math.random() * 2 - 1) * spreadRad)

    // Curve: mostly the player's deliberate sideways flick, plus a little from
    // striking across the body, plus a touch of natural imperfection.
    const velDir = p.speed > 1 ? V.normalize(p.vel) : p.facing
    let spin = kick.spin * KICK.maxSpinFromAim
    spin += V.cross(velDir, aim) * KICK.maxSpinFromAim * 0.35 * (0.4 + 0.6 * power)
    spin += (Math.random() * 2 - 1) * 0.12

    // Loft: a positive flick lifts the ball, a negative one drives it low with
    // topspin so it dips rather than climbing.
    let horiz = speed
    let vz = 0
    if (loft > 0) {
      const angle = loft * CONTROL.maxLoftAngle
      vz = Math.sin(angle) * speed
      horiz = Math.cos(angle) * speed
    } else if (loft < 0) {
      vz = loft * CONTROL.driveDip * speed * 0.12 // slight downward bite
    }

    // Place the ball just ahead so it doesn't instantly re-collide with the kicker.
    this.ball.x = p.x + aim.x * (p.radius + BALL.radius + 0.05)
    this.ball.y = p.y + aim.y * (p.radius + BALL.radius + 0.05)
    this.ball.z = vz > 0 ? 0.15 : 0
    this.ball.launch(aim.x * horiz, aim.y * horiz, vz, spin, p.team, p.id)

    // A touch keeps the ball yours: no release cooldown beyond a beat, so you can
    // keep knocking it forward and running onto it.
    p.kickCooldown = kick.type === 'touch' ? 0.1 : 0.26
    this.possessorId = null
    if (p.id === this.keeperHoldId) {
      this.keeperHold = 0
      this.keeperHoldId = -1
    }
    this.pushEffect('kick', this.ball.x, this.ball.y)

    // Stats. A human 'strike' has no declared intent, so classify it: a firm
    // ball aimed at the opponent's goal from range counts as a shot.
    let kind: 'shot' | 'pass' | 'none' = 'none'
    if (kick.type === 'shot') kind = 'shot'
    else if (kick.type === 'pass' || kick.type === 'through') kind = 'pass'
    else if (kick.type === 'strike') {
      const goal = F.targetGoalCenter(p.team)
      const toGoal = V.dir(p.pos, goal)
      const aimedAtGoal = V.dot(toGoal, aim) > 0.8
      kind = aimedAtGoal && power > 0.45 && V.dist(p.pos, goal) < 30 ? 'shot' : 'pass'
    }
    if (kind === 'shot') {
      this.stats[p.team].shots++
      if (this.shotOnTarget(p, aim, speed, vz)) this.stats[p.team].onTarget++
      this.lastPass = null
    } else if (kind === 'pass') {
      this.stats[p.team].passes++
      this.lastPass = { team: p.team, fromId: p.id }
    }
  }

  // Rough check: would this shot cross the goal mouth if nobody intervened?
  private shotOnTarget(p: Player, aim: Vec2, speed: number, vz: number): boolean {
    const goalX = F.targetGoalLineX(p.team)
    if (Math.sign(aim.x) !== Math.sign(goalX - p.x)) return false
    const vxh = aim.x * speed
    if (Math.abs(vxh) < 1e-3) return false
    // Time to reach the goal line at the shot's horizontal speed.
    const tt = (goalX - this.ball.x) / vxh
    if (tt < 0) return false
    const yAt = this.ball.y + aim.y * speed * tt
    const [pa, pb] = F.goalPostYs()
    if (yAt < pa - 0.3 || yAt > pb + 0.3) return false
    // Height for lofted shots (gravity arc).
    const zAt = vz * tt - 0.5 * SIM.gravity * tt * tt
    return zAt < FIELD.goalHeight + 0.3
  }

  // ---- tackling ----------------------------------------------------------

  private resolveTackles(commands: Map<number, Command>, dt: number) {
    void dt
    for (const p of this.players) {
      const cmd = commands.get(p.id)
      if (!cmd) continue
      if (p.sliding || p.tackleCooldown > 0) continue

      if (cmd.slide) {
        const dir = V.len(cmd.aim) > 0.01 ? cmd.aim : p.facing
        p.startSlide(dir, Math.max(p.speed, PLAYER.runSpeed) + 3)
        this.pushEffect('tackle', p.x, p.y)
        p.tackleCooldown = 0.6
        continue
      }

      if (cmd.tackle) {
        p.tackleCooldown = 0.4
        const d = V.dist(p.pos, this.ball.pos)
        if (d < DEFEND.tackleRange && this.ball.z < 1.6) {
          const owner = this.possessorId != null ? this.player(this.possessorId) : null
          if (!owner || owner.team !== p.team) {
            this.winBall(p)
          }
        }
      }
    }
  }

  // A successful challenge pokes the ball to the tackler's feet, pointed upfield,
  // so possession changes cleanly instead of the ball squirting away loose.
  private winBall(tackler: Player) {
    this.stats[tackler.team].tackles++
    const fwd = tackler.facing
    this.ball.setPos(
      tackler.x + fwd.x * (tackler.radius + BALL.radius + 0.25),
      tackler.y + fwd.y * (tackler.radius + BALL.radius + 0.25),
      0,
    )
    this.ball.launch(fwd.x * 3, fwd.y * 3, 0, 0, tackler.team, tackler.id)
    this.possessorId = null
    this.lastPass = null
    this.pushEffect('tackle', this.ball.x, this.ball.y)
  }

  // ---- collisions --------------------------------------------------------

  private resolveGkSaves(live: boolean) {
    for (const gk of this.players) {
      if (gk.role !== 'GK') continue
      // Don't let the keeper re-gather the ball it just distributed.
      if (gk.kickCooldown > 0) continue
      const d = V.dist(gk.pos, this.ball.pos)
      const contact = gk.radius + BALL.radius + 0.35
      if (d > contact || this.ball.z > 2.6) continue
      if (this.possessorId === gk.id) continue

      const power = this.ball.horizontalSpeed
      const towardOwnGoal =
        Math.sign(this.ball.vx) === -F.attackDir(gk.team) && Math.abs(this.ball.vx) > 2
      const wasShot = this.ball.lastTouchTeam != null && this.ball.lastTouchTeam !== gk.team && power > 7
      const countSave = live && gk.saveCooldown <= 0 && (wasShot || towardOwnGoal)

      if (power < GK.catchPower && this.ball.z < 2.2) {
        // Clean catch → keeper gathers and holds the ball.
        this.ball.stop()
        this.ball.setPos(gk.x, gk.y, 0)
        this.possessorId = gk.id
        this.keeperHold = 1.0
        this.keeperHoldId = gk.id
        this.ball.lastTouchTeam = gk.team
        this.ball.lastTouchId = gk.id
        if (countSave) {
          this.stats[gk.team].saves++
          this.setAnnounce('SAVE!', undefined, 1.1)
          this.pushEffect('save', gk.x, gk.y)
          gk.saveCooldown = 0.7
        }
      } else {
        // Parry: deflect away from goal.
        const n = V.normalize(V.sub(this.ball.pos, gk.pos))
        this.ball.reflect(n.x, n.y, 0.4)
        this.ball.vx += F.attackDir(gk.team) * 6 // shove it back upfield
        this.ball.vz = Math.max(this.ball.vz, 2)
        this.ball.lastTouchTeam = gk.team
        this.ball.lastTouchId = gk.id
        if (countSave) {
          this.stats[gk.team].saves++
          this.setAnnounce('SAVE!', undefined, 1.0)
          this.pushEffect('save', gk.x, gk.y)
          gk.saveCooldown = 0.7
        }
      }
    }
  }

  private resolveBodyCollisions() {
    for (const p of this.players) {
      if (p.id === this.possessorId) continue
      if (p.role === 'GK') continue // handled by saves
      if (p.kickCooldown > 0) continue
      if (this.ball.z > 1.9) continue
      const dx = this.ball.x - p.x
      const dy = this.ball.y - p.y
      const dist = Math.hypot(dx, dy)
      const min = p.radius + BALL.radius
      if (dist < min && dist > 1e-4) {
        const nx = dx / dist
        const ny = dy / dist
        // Push out of overlap.
        this.ball.x = p.x + nx * min
        this.ball.y = p.y + ny * min
        // Reflect + inherit a little of the body's momentum.
        this.ball.reflect(nx, ny, 0.45)
        this.ball.vx += p.vx * 0.35
        this.ball.vy += p.vy * 0.35
        this.ball.lastTouchTeam = p.team
        this.ball.lastTouchId = p.id
      }
    }
  }

  private resolvePosts() {
    const postR = 0.11
    for (const team of ['home', 'away'] as Team[]) {
      const goalX = F.ownGoalLineX(team)
      const [pa, pb] = F.goalPostYs()
      for (const py of [pa, pb]) {
        const dx = this.ball.x - goalX
        const dy = this.ball.y - py
        const d = Math.hypot(dx, dy)
        if (d < postR + BALL.radius && d > 1e-4 && this.ball.z < FIELD.goalHeight) {
          const nx = dx / d
          const ny = dy / d
          this.ball.x = goalX + nx * (postR + BALL.radius)
          this.ball.y = py + ny * (postR + BALL.radius)
          this.ball.reflect(nx, ny, 0.55)
          this.pushEffect('post', this.ball.x, this.ball.y)
        }
      }
      // Crossbar: crossing the plane at bar height bounces down.
      if (
        Math.abs(this.ball.x - goalX) < 0.4 &&
        this.ball.y > pa &&
        this.ball.y < pb &&
        this.ball.z > FIELD.goalHeight - 0.12 &&
        this.ball.z < FIELD.goalHeight + 0.18 &&
        this.ball.vz > 0
      ) {
        this.ball.vz = -Math.abs(this.ball.vz) * 0.4
        this.ball.z = FIELD.goalHeight - 0.13
        this.pushEffect('post', goalX, this.ball.y)
      }
    }
  }

  // ---- ball leaving play -------------------------------------------------

  // The pitch is enclosed. A ball that isn't going in the goal rebounds off the
  // boards and stays live — no throw-ins, corners or goal kicks to break up play.
  private resolveWallsAndGoals(live: boolean) {
    const b = this.ball
    const r = BALL.radius

    // Touchlines.
    if (b.y - r < 0 && b.vy < 0) this.bounceOffWall(0, 1, r - b.y)
    else if (b.y + r > FIELD.width && b.vy > 0) this.bounceOffWall(0, -1, b.y + r - FIELD.width)

    // Goal lines. The mouth is an opening; everything else is a solid end wall.
    const throughMouth = F.inGoalMouthY(b.y) && b.z < FIELD.goalHeight
    if (!throughMouth) {
      if (b.x - r < 0 && b.vx < 0) this.bounceOffWall(1, 0, r - b.x)
      else if (b.x + r > FIELD.length && b.vx > 0) this.bounceOffWall(-1, 0, b.x + r - FIELD.length)
    } else if (live) {
      // Fully over the line inside the mouth — that's a goal.
      if (b.x < 0) this.scoreGoal('away')
      else if (b.x > FIELD.length) this.scoreGoal('home')
    }

    // A ball that somehow gets behind the goal is stopped by the back of the net.
    const back = FIELD.goalDepth
    if (b.x < -back) { b.x = -back; b.vx = Math.abs(b.vx) * 0.2 }
    if (b.x > FIELD.length + back) { b.x = FIELD.length + back; b.vx = -Math.abs(b.vx) * 0.2 }
  }

  // Rebound off a barrier with the given inward normal, pushing the ball clear
  // of the surface so it can't get stuck inside it.
  private bounceOffWall(nx: number, ny: number, overlap: number) {
    const b = this.ball
    b.x += nx * overlap
    b.y += ny * overlap
    const vn = b.vx * nx + b.vy * ny
    b.vx -= (1 + WALL.restitution) * vn * nx
    b.vy -= (1 + WALL.restitution) * vn * ny
    // Scrub a little pace along the wall, and knock the spin down.
    const tx = -ny
    const ty = nx
    const vt = b.vx * tx + b.vy * ty
    const loss = vt * (1 - WALL.friction)
    b.vx -= loss * tx
    b.vy -= loss * ty
    b.spin *= 0.6
    this.pushEffect('post', b.x, b.y)
  }

  // Put the ball back on the centre spot for another rep, leaving you wherever
  // you are — a practice session shouldn't teleport you back to a formation.
  private resetTrainingBall() {
    this.ball.setPos(F.centerSpot().x, F.centerSpot().y, 0)
    this.ball.stop()
    this.ball.lastTouchTeam = null
    this.possessorId = null
  }

  private scoreGoal(scorer: Team) {
    this.score[scorer]++
    if (this.config.mode === 'training') {
      this.stats[scorer].goals++
      this.setAnnounce('GOAL!', undefined, 1.2)
      this.pushEffect('goal', this.ball.x, this.ball.y)
      this.resetTrainingBall()
      return
    }
    this.stats[scorer].goals++
    this.lastGoalTeam = scorer
    this.kickoffTeam = this.otherTeam(scorer)
    this.phase = 'goal'
    this.phaseTimer = MATCH.afterGoalDelay
    this.possessorId = null
    this.ball.stop()
    this.setAnnounce('GOAL!', `${this.kitName('home')} ${this.score.home} – ${this.score.away} ${this.kitName('away')}`, MATCH.afterGoalDelay)
    this.pushEffect('goal', this.ball.x, this.ball.y)
  }

  // ---- helpers -----------------------------------------------------------

  getControlledPlayer(): Player | null {
    return this.player(this.controlledId)
  }

  // Is this the player a human is actually steering right now?
  isHumanDriven(p: Player): boolean {
    return this.config.humanControlled !== false && p.id === this.controlledId
  }

  // Which team's keeper (if any) is currently shielding a gathered ball.
  keeperHeldByTeam(): Team | null {
    if (this.keeperHold <= 0 || this.keeperHoldId < 0) return null
    return this.player(this.keeperHoldId)?.team ?? null
  }

  player(id: number): Player | null {
    return this.players.find((p) => p.id === id) ?? null
  }

  nearestPlayer(to: Vec2, filter: (p: Player) => boolean): Player | null {
    let best: Player | null = null
    let bd = Infinity
    for (const p of this.players) {
      if (!filter(p)) continue
      const d = V.dist2(p.pos, to)
      if (d < bd) {
        bd = d
        best = p
      }
    }
    return best
  }

  teammates(team: Team): Player[] {
    return this.players.filter((p) => p.team === team)
  }

  otherTeam(t: Team): Team {
    return t === 'home' ? 'away' : 'home'
  }

  kitName(t: Team): string {
    return t === 'home' ? 'HOME' : 'AWAY'
  }

  private separatePlayers() {
    for (let i = 0; i < this.players.length; i++) {
      for (let j = i + 1; j < this.players.length; j++) {
        const a = this.players[i]
        const b = this.players[j]
        const dx = b.x - a.x
        const dy = b.y - a.y
        const d = Math.hypot(dx, dy)
        const min = a.radius + b.radius
        if (d < min && d > 1e-4) {
          const overlap = (min - d) / 2
          const nx = dx / d
          const ny = dy / d
          // Sliding players barge through less; keepers hold their ground.
          const aw = a.role === 'GK' ? 0.2 : 1
          const bw = b.role === 'GK' ? 0.2 : 1
          const tot = aw + bw
          a.x -= nx * overlap * (bw / tot) * 2
          a.y -= ny * overlap * (bw / tot) * 2
          b.x += nx * overlap * (aw / tot) * 2
          b.y += ny * overlap * (aw / tot) * 2
        }
      }
    }
  }

  private keepPlayersInBounds() {
    const m = 0.4
    for (const p of this.players) {
      p.x = clamp(p.x, m, FIELD.length - m)
      p.y = clamp(p.y, m, FIELD.width - m)
    }
  }

  // ---- free play helper --------------------------------------------------

  spawnBallAt(pos: Vec2) {
    this.ball.setPos(clamp(pos.x, 1, FIELD.length - 1), clamp(pos.y, 1, FIELD.width - 1), 0)
    this.ball.stop()
    this.possessorId = null
    this.pushEffect('spawn', pos.x, pos.y)
  }

  // ---- fx & announcements ------------------------------------------------

  private pushEffect(type: Effect['type'], x: number, y: number) {
    const life = type === 'goal' ? 1.6 : type === 'save' ? 0.7 : 0.4
    this.effects.push({ type, x, y, t: 0, life })
    if (this.effects.length > 40) this.effects.shift()
  }

  private updateEffects(dt: number) {
    for (const e of this.effects) e.t += dt
    this.effects = this.effects.filter((e) => e.t < e.life)
  }

  private setAnnounce(text: string, sub: string | undefined, life: number) {
    this.announce = { text, sub, t: 0, life }
  }

  private updateAnnounce(dt: number) {
    if (this.announce) {
      this.announce.t += dt
      if (this.announce.t >= this.announce.life) this.announce = null
    }
  }
}
