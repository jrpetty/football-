import { AERIAL, BALL, CONTROL, DEFEND, FIELD, GK, KICK, MATCH, PLAYER, SHIELD, SIM, WALL } from '../config'
import { clamp } from '../core/math'
import * as V from '../core/vec'
import type { Vec2 } from '../core/vec'
import { Ball } from '../physics/ball'
import { Player } from '../entities/player'
import { computeAiCommands } from '../ai/director'
import { emptyCommand, emptyStats } from '../types'
import type { Command, KickRequest, KickType, MatchConfig, Restart, Role, Skill, Stats, Team } from '../types'
import { sfx } from '../audio/sfx'
import { Drills } from './drills'
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

// A ball is only "at your feet" — dribbleable — below this. Keepers can gather
// it from higher because they use their hands.
const controlHeight = (role: string): number =>
  role === 'GK' ? 2.2 : PLAYER.controlHeight
// How high a player can still *reach* the ball to play it out of the air.
const aerialReach = (role: string): number => (role === 'GK' ? 2.6 : PLAYER.aerialReach)

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
  // Practice apparatus — only present in training.
  drills: Drills | null = null
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
      this.drills = new Drills()
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
    this.drills?.update(this, dt)
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
          sfx.whistle()
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
      // Shielding only means anything when the ball is actually yours to shield.
      p.shielding = cmd.shield && !p.sliding && this.ballWithinReach(p)
      p.steer(cmd.move, V.len(cmd.move), cmd.sprint, dt, cmd.walk)
      if (p.shielding) this.faceSideOn(p, cmd, dt)
      else p.faceDirection(V.len(cmd.move) > 0.05 ? cmd.move : cmd.aim, dt)
    }

    // 2. Tackles / slides (only when the ball is live).
    if (live) this.resolveSlides(commands, dt)

    // 3. Possession & first touch.
    this.resolvePossession(dt)

    // 4. Kicks (live only). A kick clears possession.
    if (live) {
      for (const p of this.players) {
        const cmd = commands.get(p.id)
        // A human keeper's left click launches a dive rather than a strike —
        // unless they already have the ball, in which case it's distribution.
        if (
          cmd?.kick &&
          cmd.kick.type === 'strike' &&
          p.role === 'GK' &&
          this.isHumanDriven(p) &&
          this.possessorId !== p.id &&
          !p.diving &&
          p.diveRecover <= 0 &&
          p.tackleCooldown <= 0
        ) {
          const k = cmd.kick
          const dir = V.len(k.aim) > 0.01 ? V.normalize(k.aim) : p.facing
          p.startDive(dir, k.power, k.loft)
          p.tackleCooldown = GK.diveCooldown
          p.kickKind = 'strike'
          this.pushEffect('save', p.x, p.y)
          sfx.tackle()
          cmd.kick = null
          continue
        }
        if (cmd?.kick && this.canKick(p, cmd.kick.type)) {
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
    const preBounceVz = this.ball.vz
    this.ball.integrate()
    this.ball.clampSpin()
    if (this.ball.justBounced) sfx.bounce(Math.min(1, Math.abs(preBounceVz) / 12))
    // Training targets are tested here, on the step the ball actually moves —
    // after this the goal logic may reposition it and the crossing would be lost.
    this.drills?.step(this)

    // 7. Collisions & saves.
    this.resolveGkSaves(live)
    this.resolveBodyCollisions()
    this.resolveDrillWall()
    this.resolvePosts()

    // 8. Ball leaving play.
    this.resolveWallsAndGoals(live)

    // 10. Possession stat.
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
      // A human keeper does not gain the ball by standing near it. Everything a
      // keeper gets, they get by diving for it or gathering it — otherwise the
      // whole role collapses back into the automatic saving it replaced.
      // ...but once it's in their hands it stays there until they play it.
      if (p.role === 'GK' && this.isHumanDriven(p) && this.possessorId !== p.id) continue
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

  // Is the ball close enough to be worth shielding?
  private ballWithinReach(p: Player): boolean {
    if (this.ball.z > PLAYER.controlHeight * 1.6) return false
    return V.dist(p.pos, this.ball.pos) < p.radius + BALL.radius + PLAYER.reach * 1.3
  }

  // Turn side-on. You point your shoulder where you're aiming — at the defender
  // — so your body ends up across the line between them and the ball. Which of
  // the two perpendiculars you take is decided by where the ball actually is, so
  // the body always turns the way that puts it behind you rather than in front.
  private faceSideOn(p: Player, cmd: Command, dt: number) {
    const aim = V.len(cmd.aim) > 0.01 ? V.normalize(cmd.aim) : p.facing
    const toBall = V.sub(this.ball.pos, p.pos)
    const side = Math.sign(V.cross(aim, toBall)) || 1
    // aim rotated a quarter turn toward the ball's side.
    const want = { x: -aim.y * side, y: aim.x * side }
    p.faceDirection(want, dt * (SHIELD.turnRate / PLAYER.turnRate))
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
    // Nothing is ever glued to a human's feet. A ball arriving at you is just a
    // ball arriving at you: it bounces off your body unless you actually play
    // it, which means a right click to take the pace off or a left click to hit
    // it. That is the whole game — the ball only ever goes where you tell it to,
    // and never where the simulation decided it should. The AI still gets an
    // automatic trap, because it has no hands on a mouse to do it with.
    if (this.isHumanDriven(p)) return
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

  private canKick(p: Player, kind?: KickType): boolean {
    // You cannot play the ball off the floor. A slide is a commitment, and being
    // out of the play until you're upright again is what it costs.
    if (p.sliding || p.slideRecover > 0 || p.kickCooldown > 0) return false
    if (p.diving || p.diveRecover > 0) return false
    // Shielding is a trade: the ball is safe behind your body, and while it's
    // back there you have no way to hit it. Touches still work, so you can knock
    // it out of the shield and go — that release is the whole point.
    if (p.shielding && kind !== 'touch') return false
    // ...and you cannot play a ball someone has their body in front of. This is
    // geometry, not a rule: their shoulder is between your boot and the ball.
    if (this.shieldedFrom(p)) return false
    // You can play a ball anywhere within reach — at your feet, off your thigh,
    // or with a header at full stretch.
    if (this.ball.z > aerialReach(p.role)) return false
    const d = V.dist(p.pos, this.ball.pos)
    return d < p.radius + BALL.radius + PLAYER.reach * 1.25
  }

  // Is an opponent's body between this player and the ball? Measured as how far
  // the shielder's centre sits from the straight line from you to the ball, and
  // only counting them when they are genuinely in between rather than behind it.
  private shieldedFrom(p: Player): boolean {
    for (const s of this.players) {
      if (!s.shielding || s.team === p.team || s.id === p.id) continue
      const ax = this.ball.x - p.x
      const ay = this.ball.y - p.y
      const len = Math.hypot(ax, ay)
      if (len < 1e-3) continue
      const t = ((s.x - p.x) * ax + (s.y - p.y) * ay) / (len * len)
      if (t <= 0 || t >= 1) continue // beside or beyond, not in the way
      const cx = p.x + ax * t
      const cy = p.y + ay * t
      if (Math.hypot(s.x - cx, s.y - cy) < SHIELD.block) return true
    }
    return false
  }

  // Last kick the simulation actually received — surfaced for debugging/tuning.
  lastKickDebug: { type: string; power: number; loft: number; spin: number } | null = null

  private executeKick(p: Player, cmd: Command) {
    const kick = cmd.kick!
    // Taking the ball off someone is now just playing it while they had it —
    // there is no tackle button to count instead, so the stat is measured from
    // what actually happened.
    const held = this.possessorId != null ? this.player(this.possessorId) : null
    if (held && held.team !== p.team && held.id !== p.id && p.tackleCredit <= 0) {
      this.stats[p.team].tackles++
      p.tackleCredit = 1.2
    }
    this.lastKickDebug = { type: kick.type, power: kick.power, loft: kick.loft, spin: kick.spin }
    let aim = V.len(kick.aim) > 0.01 ? V.normalize(kick.aim) : p.facing
    const power = clamp(kick.power, 0, 1)
    const loft = clamp(kick.loft, -1, 1)

    // Playing the ball out of the air is a different act from playing it off the
    // turf. A soft touch *cushions* it — kills the pace and drops it dead at your
    // feet — while a strike becomes a volley or, higher still, a header: more
    // power, far less control. This is the half of football the ground-only
    // model was missing.
    const airborne = this.ball.z > PLAYER.controlHeight
    if (airborne && p.role !== 'GK') {
      if (kick.type === 'touch') {
        this.cushion(p, kick, power)
        return
      }
      if (kick.type === 'strike') {
        this.aerialStrike(p, kick, aim, power)
        return
      }
    }

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
    // Side-spin, as the speed of the ball's surface: mostly the player's
    // deliberate sideways flick, plus a little from striking across the body,
    // plus a touch of natural imperfection. Scaled by the ball's own pace,
    // because that is what spin is — you cannot put 8 m/s of surface on a ball
    // you have rolled 4 m.
    let spin = kick.spin * KICK.sideSpin * speed
    spin +=
      V.cross(velDir, aim) *
      KICK.sideSpin *
      KICK.sideSpinAcrossBody *
      (0.4 + 0.6 * power) *
      speed
    spin += (Math.random() * 2 - 1) * 0.12

    // Loft, and the spin that comes with the technique. Getting under the ball
    // lifts it and leaves backspin on it, so a chip floats and hangs; coming
    // over the top drives it flat with topspin, so it dips and then skids on.
    // Launch angle. A neutral strike gets the natural lift of a ball coming off
    // the laces; flicking up climbs from there toward a full lofted ball, and
    // flicking down takes it away — reaching the floor well before the flick is
    // maxed out, so a low driven pass is a normal thing to play rather than a
    // perfectly executed one.
    const natural = CONTROL.naturalLoft * power
    const driven = loft < 0 ? clamp(loft / CONTROL.driveLoft, 0, 1) : 0
    const maxLoft =
      CONTROL.loftAngleSoft - (CONTROL.loftAngleSoft - CONTROL.loftAngleHard) * power
    const angle = loft >= 0 ? natural + loft * (maxLoft - natural) : natural * (1 - driven)
    // Struck through the middle rather than under it, so more of it is pace.
    speed *= 1 + CONTROL.driveBonus * driven
    const vz = Math.sin(angle) * speed
    const horiz = Math.cos(angle) * speed
    // How much spin the technique leaves on the ball, as the speed of its own
    // surface. Coming over the top puts topspin on, so the ball arrives at the
    // turf already close to rolling and skids on. Getting under it leaves
    // backspin — but that's a scooping motion, so a delicate chip floats and
    // checks while a full-blooded long ball is struck through and carries less.
    const vSpin =
      loft > 0
        ? -loft * CONTROL.backspinFromLoft * (1 - 0.55 * power) * speed
        : driven * CONTROL.topspinFromLoft * speed

    // A close-control move reshapes where the touch goes.
    if (kick.type === 'touch' && kick.skill) {
      // kick.spin carries the sign of the sideways flick, which is what tells a
      // roll which way to go.
      this.applySkill(p, kick.skill, aim, power, Math.sign(kick.spin))
      return
    }

    // Place the ball just ahead so it doesn't instantly re-collide with the kicker.
    this.ball.x = p.x + aim.x * (p.radius + BALL.radius + 0.05)
    this.ball.y = p.y + aim.y * (p.radius + BALL.radius + 0.05)
    this.ball.z = vz > 0 ? 0.15 : 0
    this.ball.launch(aim.x * horiz, aim.y * horiz, vz, spin, p.team, p.id, vSpin)

    // A touch keeps the ball yours: no release cooldown beyond a beat, so you can
    // keep knocking it forward and running onto it.
    p.kickCooldown = kick.type === 'touch' ? 0.1 : 0.26
    // Swing the leg nearest the ball, so the strike reads as coming off the
    // right boot rather than always the same one.
    p.startKick(kick.type === 'touch' ? 'touch' : 'strike', power)
    p.kickLeg = V.cross(p.facing, V.sub(this.ball.pos, p.pos)) > 0 ? 0 : 1
    this.possessorId = null
    if (p.id === this.keeperHoldId) {
      this.keeperHold = 0
      this.keeperHoldId = -1
    }
    this.pushEffect('kick', this.ball.x, this.ball.y)
    if (kick.type === 'touch') sfx.touch(power)
    else sfx.strike(power)

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

  // Take the ball out of the air and kill it dead at your feet. The harder it
  // arrived, the more it squirms away from you — a well-weighted cushion on a
  // dropping ball is the most satisfying piece of control in football, and it
  // should be a skill rather than a guarantee.
  private cushion(p: Player, kick: KickRequest, power: number) {
    const incoming = this.ball.speed
    const f = p.facing
    // Flick up as you take it and you cushion it *upward* instead — the ball
    // stays in the air off your thigh or chest, ready to be volleyed. Flick
    // down and you kill it stone dead under you.
    const pop = kick.loft > 0.25 ? 2.6 + kick.loft * 2.4 : 0
    // A firmer press of the button pushes it a little further out in front;
    // a tap deadens it right under you.
    const set = 0.35 + power * 0.75
    const err = AERIAL.cushionError * (0.25 + incoming * 0.055) * (p.sprinting ? 1.5 : 1)
    this.ball.setPos(
      p.x + f.x * set + (Math.random() * 2 - 1) * err,
      p.y + f.y * set + (Math.random() * 2 - 1) * err,
      pop > 0 ? Math.max(this.ball.z * 0.5, 0.3) : 0,
    )
    // Most of the pace is absorbed; what's left carries your own momentum.
    this.ball.vx = this.ball.vx * AERIAL.cushionKeep + p.vx * 0.35
    this.ball.vy = this.ball.vy * AERIAL.cushionKeep + p.vy * 0.35
    this.ball.vz = pop
    this.ball.spin *= 0.2
    this.ball.vSpin = 0
    this.ball.lastTouchTeam = p.team
    this.ball.lastTouchId = p.id
    p.kickCooldown = 0.12
    p.startKick('cushion', power)
    this.possessorId = null
    sfx.touch(0.35)
    this.pushEffect('kick', this.ball.x, this.ball.y)
  }

  // Strike a ball that hasn't landed. Off the boot it's a volley; above head
  // height it's a header, powered by your run rather than your leg.
  private aerialStrike(p: Player, kick: KickRequest, aim: Vec2, power: number) {
    const header = this.ball.z > PLAYER.headerHeight
    let speed: number
    let spreadDeg: number
    let angle: number // launch pitch, radians — negative aims it into the turf

    if (header) {
      // A header takes its pace from how hard you attacked the ball, and its
      // angle entirely from the flick: down to bury it, up to loop it.
      speed = AERIAL.headerPower + p.speed * AERIAL.headerRunBonus + power * 4
      spreadDeg = AERIAL.headerSpread
      angle = AERIAL.headerAngle + kick.loft * AERIAL.headerAngleRange
    } else {
      speed =
        (KICK.strikeMin + (KICK.strikeMax - KICK.strikeMin) * power) * AERIAL.volleyBonus
      spreadDeg = AERIAL.volleySpread * (0.4 + power * 0.6)
      // A volley is struck out of the air, so the same rule applies: the harder
      // you go through it, the flatter it comes off.
      angle =
        (kick.loft * 0.7 + 0.1) *
        (CONTROL.loftAngleSoft - (CONTROL.loftAngleSoft - CONTROL.loftAngleHard) * power)
    }

    // Meeting a moving ball cleanly is hard; that's the trade for the power.
    const rad = ((spreadDeg + (1 - p.energy) * 1.5) * Math.PI) / 180
    const dir = V.rotate(aim, (Math.random() * 2 - 1) * rad)
    angle = clamp(angle, -0.35, 0.85)
    const horiz = Math.cos(angle) * speed
    // A downward contact only has somewhere to go if the ball is off the deck.
    const vz = this.ball.z > 0.3 ? Math.sin(angle) * speed : Math.max(0, Math.sin(angle) * speed)

    this.ball.setPos(
      p.x + dir.x * (p.radius + BALL.radius + 0.08),
      p.y + dir.y * (p.radius + BALL.radius + 0.08),
      Math.max(this.ball.z, 0.2),
    )
    this.ball.launch(
      dir.x * horiz,
      dir.y * horiz,
      vz,
      kick.spin * KICK.sideSpin * 0.7 * speed,
      p.team,
      p.id,
      // Same technique spin as a grounded strike, in the same unit: a fraction
      // of the ball's own speed off the boot or the head.
      -kick.loft * CONTROL.backspinFromLoft * 0.7 * speed,
    )
    p.kickCooldown = 0.26
    p.startKick(header ? 'header' : 'strike', Math.max(power, 0.7))
    p.kickLeg = V.cross(p.facing, V.sub(this.ball.pos, p.pos)) > 0 ? 0 : 1
    this.possessorId = null
    sfx.strike(header ? 0.5 : Math.max(0.5, power))
    this.pushEffect('kick', this.ball.x, this.ball.y)
    this.stats[p.team].shots++
    if (this.shotOnTarget(p, dir, speed, vz)) this.stats[p.team].onTarget++
  }

  // Close control shaped by the flick: drag it back out of a challenge, roll it
  // across your body, or lift it over a defender's leg.
  private applySkill(p: Player, skill: Skill, aim: Vec2, power: number, flickSide: number) {
    const f = p.facing
    const right = { x: -f.y, y: f.x }
    let dir: Vec2 = aim
    let speed = KICK.touchMin + (KICK.touchMax - KICK.touchMin) * power * 0.55
    let vz = 0

    if (skill === 'drag') {
      // Pull it back behind you and turn out — the classic escape.
      dir = { x: -f.x, y: -f.y }
      speed *= 0.7
    } else if (skill === 'roll') {
      // Across the body, to whichever side you flicked. The side has to come
      // from the flick itself: when you're aiming straight ahead your aim and
      // your facing are the same vector, so asking which side the aim is on
      // would be a coin toss.
      const side = flickSide || 1
      dir = V.normalize({ x: f.x * 0.35 + right.x * side, y: f.y * 0.35 + right.y * side })
    } else {
      // Lift it up and over a leg, landing just beyond.
      dir = aim
      speed *= 0.85
      vz = 4.2 + power * 2.2
    }

    this.ball.setPos(
      p.x + dir.x * (p.radius + BALL.radius + 0.06),
      p.y + dir.y * (p.radius + BALL.radius + 0.06),
      vz > 0 ? 0.15 : 0,
    )
    this.ball.launch(dir.x * speed, dir.y * speed, vz, 0, p.team, p.id, vz > 0 ? -2 : 0)
    p.kickCooldown = 0.12
    p.startKick('touch', power)
    p.kickLeg = skill === 'roll' && flickSide > 0 ? 0 : 1
    this.possessorId = null
    sfx.touch(0.5)
    this.pushEffect('kick', this.ball.x, this.ball.y)
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

  // Sliding. There is no tackle button and no challenge roll: you commit your
  // body along the ground and either it is where the ball is or it isn't.
  private resolveSlides(commands: Map<number, Command>, dt: number) {
    for (const p of this.players) {
      const cmd = commands.get(p.id)
      if (cmd?.slide && !p.sliding && p.tackleCooldown <= 0 && p.slideRecover <= 0) {
        const dir = V.len(cmd.move) > 0.05 ? cmd.move : V.len(cmd.aim) > 0.01 ? cmd.aim : p.facing
        p.startSlide(dir, p.speed + DEFEND.slideBoost)
        this.pushEffect('tackle', p.x, p.y)
        p.tackleCooldown = DEFEND.slideCooldown
        sfx.tackle()
      }
      if (p.sliding) this.slideContact(p)
      else p.slideRecover = Math.max(0, p.slideRecover - dt)
    }
  }

  // Does this sliding body actually reach the ball? The body is a capsule lying
  // on the turf: from where the player is, back along the way they came, the
  // length of a person. Nothing here searches for the ball or bends toward it —
  // the only question asked is whether the ball is inside that shape.
  private slideContact(p: Player) {
    if (p.slideWon || this.ball.z > DEFEND.slideBallHeight) return
    const dir = V.len(p.slideVel) > 0.01 ? V.normalize(p.slideVel) : p.facing
    // Nearest point on the body segment to the ball.
    const bx = this.ball.x - p.x
    const by = this.ball.y - p.y
    const along = clamp(bx * dir.x + by * dir.y, -DEFEND.slideBodyLength, 0.35)
    const cx = p.x + dir.x * along
    const cy = p.y + dir.y * along
    if (Math.hypot(this.ball.x - cx, this.ball.y - cy) > p.radius + BALL.radius) return

    // Contact. The ball is swept away along the slide, not gifted to anyone —
    // you've won it, you haven't gained possession of it.
    p.slideWon = true
    // Timed it right, so you're back up faster than someone who dived at air.
    p.slideRecover *= DEFEND.slideWonRecovery
    this.stats[p.team].tackles++
    const pace = DEFEND.slideClear * (0.6 + 0.4 * clamp(p.speed / PLAYER.sprintSpeed, 0, 1))
    this.ball.setPos(cx + dir.x * 0.3, cy + dir.y * 0.3, 0.05)
    this.ball.launch(dir.x * pace, dir.y * pace, pace * 0.12, 0, p.team, p.id, 0)
    this.possessorId = null
    this.lastPass = null
    sfx.tackle()
    this.pushEffect('tackle', this.ball.x, this.ball.y)
  }

  // ---- collisions --------------------------------------------------------

  private resolveGkSaves(live: boolean) {
    for (const gk of this.players) {
      if (gk.role !== 'GK') continue
      // Don't let the keeper re-gather the ball it just distributed.
      if (gk.kickCooldown > 0) continue
      if (this.possessorId === gk.id) continue

      // How far this keeper can actually reach the ball, and how high.
      //
      // An AI keeper keeps the old generous body radius: it has no hands on a
      // mouse and cannot be asked to time a dive. A human keeper gets nothing
      // for free — their reach is their body, or the swept line of a dive they
      // committed to, and if the ball isn't inside that it goes in.
      const human = this.isHumanDriven(gk)
      let reach: number
      let maxZ: number
      let cx = gk.x
      let cy = gk.y
      if (human) {
        if (gk.diving) {
          if (gk.diveWon) continue
          // Nearest point on the line from where the dive launched to here.
          const ax = gk.x - gk.diveFrom.x
          const ay = gk.y - gk.diveFrom.y
          const len2 = ax * ax + ay * ay
          const t =
            len2 < 1e-6
              ? 0
              : clamp(((this.ball.x - gk.diveFrom.x) * ax + (this.ball.y - gk.diveFrom.y) * ay) / len2, 0, 1)
          cx = gk.diveFrom.x + ax * t
          cy = gk.diveFrom.y + ay * t
          reach = GK.diveArm + BALL.radius
          maxZ = gk.diveHeight
        } else if (gk.diveRecover > 0) {
          continue // on the floor
        } else {
          // Standing: your body, and your hands as high as you can hold them.
          reach = gk.radius + BALL.radius + 0.2
          maxZ = 2.0
        }
      } else {
        reach = gk.radius + BALL.radius + 0.35
        maxZ = 2.6
      }
      const d = Math.hypot(this.ball.x - cx, this.ball.y - cy)
      if (d > reach || this.ball.z > maxZ) continue
      if (human && gk.diving) gk.diveWon = true

      const power = this.ball.horizontalSpeed
      const towardOwnGoal =
        Math.sign(this.ball.vx) === -F.attackDir(gk.team) && Math.abs(this.ball.vx) > 2
      const wasShot = this.ball.lastTouchTeam != null && this.ball.lastTouchTeam !== gk.team && power > 7
      const countSave = live && gk.saveCooldown <= 0 && (wasShot || towardOwnGoal)

      const holdLimit = human ? GK.handlePower : GK.catchPower
      if (power < holdLimit && this.ball.z < 2.2) {
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
          sfx.save()
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
          sfx.save()
          this.pushEffect('save', gk.x, gk.y)
          gk.saveCooldown = 0.7
        }
      }
    }
  }

  private resolveBodyCollisions() {
    for (const p of this.players) {
      // An AI dribbler is placing the ball deliberately, so it passes through
      // them. A human's isn't attached to anything, so their body is solid — the
      // ball rebounds off their shins exactly like it rebounds off the boards.
      if (p.id === this.possessorId && !this.isHumanDriven(p)) continue
      if (p.role === 'GK') continue // handled by saves
      if (p.kickCooldown > 0) continue
      if (this.ball.z > 1.9) continue
      const dx = this.ball.x - p.x
      const dy = this.ball.y - p.y
      const dist = Math.hypot(dx, dy)
      // Shielding turns your body from the hard surface a ball pings off into a
      // wide soft one it dies against, and takes your movement with it. That is
      // the entire mechanic — no attachment, no assist, just a different body.
      const shield = p.shielding
      const min = (shield ? SHIELD.bodyRadius : p.radius) + BALL.radius
      if (dist < min && dist > 1e-4) {
        const nx = dx / dist
        const ny = dy / dist
        // Push out of overlap.
        this.ball.x = p.x + nx * min
        this.ball.y = p.y + ny * min
        // Reflect + inherit a little of the body's momentum.
        this.ball.reflect(nx, ny, shield ? SHIELD.keep : 0.45)
        const carry = shield ? SHIELD.carry : 0.35
        this.ball.vx += p.vx * carry
        this.ball.vy += p.vy * carry
        this.ball.lastTouchTeam = p.team
        this.ball.lastTouchId = p.id
      }
    }
  }

  // The wall in the curling drill is made of real obstacles. Going through it is
  // not an option, which is what makes bending it round the only way.
  private resolveDrillWall() {
    const wall = this.drills?.wall
    if (!wall || !wall.length) return
    const r = 0.34
    for (const m of wall) {
      const dx = this.ball.x - m.x
      const dy = this.ball.y - m.y
      const d = Math.hypot(dx, dy)
      const min = r + BALL.radius
      if (d >= min || d < 1e-4) continue
      if (this.ball.z > 1.85) continue // over their heads is fair
      const nx = dx / d
      const ny = dy / d
      this.ball.x = m.x + nx * min
      this.ball.y = m.y + ny * min
      this.ball.reflect(nx, ny, 0.4)
      this.pushEffect('post', this.ball.x, this.ball.y)
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
          sfx.post()
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
    sfx.wall(Math.min(1, Math.abs(vn) / 22))
    this.pushEffect('post', b.x, b.y)
  }

  // Put the ball back on the centre spot for another rep, leaving you wherever
  // you are — a practice session shouldn't teleport you back to a formation.
  private resetTrainingBall() {
    // Drills struck from a set position put the ball back on their own spot, so
    // the apparatus stays meaningful shot after shot.
    const spot = this.drills?.spot ?? F.centerSpot()
    this.ball.setPos(spot.x, spot.y, 0)
    this.ball.stop()
    this.ball.lastTouchTeam = null
    this.possessorId = null
  }

  private scoreGoal(scorer: Team) {
    this.score[scorer]++
    if (this.config.mode === 'training') {
      this.stats[scorer].goals++
      // The drill needs to see the ball as it went in — spin, position and all —
      // so it is told before anything gets repositioned.
      this.drills?.goal(this)
      this.setAnnounce('GOAL!', undefined, 1.2)
      sfx.goal()
      this.pushEffect('goal', this.ball.x, this.ball.y)
      // A drill that serves you the next ball puts it where it wants it.
      if (!this.drills?.servesBalls()) this.resetTrainingBall()
      else this.ball.setPos(FIELD.length / 2, FIELD.width / 2, 0)
      this.ball.stop()
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
    sfx.goal()
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

  setAnnounce(text: string, sub: string | undefined, life: number) {
    this.announce = { text, sub, t: 0, life }
  }

  private updateAnnounce(dt: number) {
    if (this.announce) {
      this.announce.t += dt
      if (this.announce.t >= this.announce.life) this.announce = null
    }
  }
}
