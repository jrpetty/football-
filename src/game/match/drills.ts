import { BALL, DUMMY, FIELD, PLAYER, SIM } from '../config'
import type { World } from './world'
import * as F from './field'
import { store } from '../core/store'

// The training gauntlet.
//
// One drill teaches one thing. The game asks you to cushion a dropping ball,
// volley it, drive it flat along the floor, bend it round a body and finish
// under time pressure — so there is a drill for each, every one of them scored,
// because a skill you can't see yourself getting better at isn't learnable.
//
// Nothing here is a minigame bolted on the side: every drill is the ordinary
// simulation with some apparatus on the pitch and a server firing balls at you.
// If you get better at these you have got better at the game.

export type DrillId = 'targets' | 'cushion' | 'volley' | 'gates' | 'curl' | 'finish'

export const DRILL_ORDER: DrillId[] = ['targets', 'cushion', 'volley', 'gates', 'curl', 'finish']

export const DRILL_INFO: Record<DrillId, { name: string; brief: string }> = {
  targets: { name: 'TARGET PRACTICE', brief: 'Strike the four hoops in the goal' },
  cushion: { name: 'FIRST TOUCH', brief: 'Right click to kill it dead — the softer the better' },
  volley: { name: 'VOLLEYS', brief: 'Left click it out of the air before it lands' },
  gates: { name: 'DRIVEN PASSES', brief: 'Flick down and drive it through the gates on the floor' },
  curl: { name: 'AROUND THE WALL', brief: 'Bend it round the wall and into the goal' },
  finish: { name: 'TOUCH & FINISH', brief: 'Take it down, then score — before the clock runs out' },
}

export interface Target {
  x: number // on the goal line
  y: number
  z: number
  radius: number
  hit: boolean
  hitAt: number // seconds since it was struck, for the fade-out
}

// A pair of cones on the floor. A ball driven between them counts; a ball lofted
// over them does not, which is the entire point of the drill.
export interface Gate {
  x: number
  y: number
  width: number
  angle: number // orientation on the pitch plane
  maxHeight: number
  passed: boolean
  passedAt: number
}

// A defensive wall to bend the ball around. Physically solid, so going through
// it isn't an option.
export interface WallMan {
  x: number
  y: number
}

// Apparatus that lives on the training pitch permanently, rather than being set
// out for one drill and cleared away after it. Two kinds, because they teach
// different things and want to look different:
//
//   'slalom' — a line of mannequins along the touchline to dribble through.
//     Close control against something that doesn't move but doesn't give way
//     either, which is the one thing an empty pitch can't offer.
//
//   'wall'   — a free-kick wall standing the regulation ten yards from a spot,
//     on the line to the goal, so the straight ball is genuinely unavailable.
//     The curling drill sets one out too; this one is simply always there.
export interface Dummy {
  x: number
  y: number
  kind: 'slalom' | 'wall'
  // How far it is currently rocked back, and which way. A dummy is foam on a
  // sprung base: hit it and it leans away, then rights itself.
  lean: number
  lx: number
  ly: number
}

const MAX_TRAIL = 400

export interface TrailPoint {
  x: number
  y: number
  z: number
}

// How a served ball is thrown at you.
interface Serve {
  from: { x: number; y: number; z: number }
  speed: number
  loft: number // launch angle, radians
  vSpin: number
  label: string
}

export class Drills {
  current: DrillId = 'targets'

  targets: Target[] = []
  gates: Gate[] = []
  wall: WallMan[] = []
  // Where the ball is placed for drills that are struck from a set position.
  // The curling drill only means anything if the wall is genuinely between you
  // and the goal, which it can't be if you get to choose where to stand.
  spot: { x: number; y: number } | null = null
  trail: TrailPoint[] = []
  trailAge = 0

  // Permanent furniture, set out once and never cleared by select(). It lives
  // down one touchline so it is out of the way of every drill's own apparatus,
  // which all works through the middle of the pitch toward a goal.
  dummies: Dummy[] = []
  // The spot the free-kick wall is standing ten yards from, so you can put a
  // ball down and bend one whenever you like rather than only in the drill
  // that sets a wall out for you.
  wallSpot: { x: number; y: number } | null = null

  // Scoring, per drill, so switching away and back doesn't wipe your record.
  scores: Record<DrillId, { attempts: number; scored: number; points: number; best: number; streak: number }> =
    {
      targets: blank(),
      cushion: blank(),
      volley: blank(),
      gates: blank(),
      curl: blank(),
      finish: blank(),
    }

  // What just happened, shown briefly on the HUD. This is the feedback loop.
  verdict: { text: string; sub: string; good: boolean; t: number } | null = null

  // Serving state for the drills that fire balls at you.
  serveTimer = 0
  serveLive = false // a ball is in the air / in play for this attempt
  attemptClock = 0
  serveLabel = ''

  private recording = false
  private registeredThisShot = false
  private sampleTimer = 0
  private lastBallSpeed = 0
  private touched = false // the player has made contact this attempt
  private touchSpeed = 0 // ball speed just after that contact
  private servedAt = 0
  private seed = 1

  constructor() {
    this.buildGround()
    this.select('targets')
  }

  // Lay out the permanent apparatus. Everything sits between the touchline and
  // a third of the way across, so it is plainly a training area off to one side
  // rather than obstacles scattered across the middle of a pitch you are also
  // trying to shoot on.
  private buildGround() {
    const lane = 6.5 // metres in from the touchline
    const d = (x: number, y: number, kind: Dummy['kind']): Dummy => ({ x, y, kind, lean: 0, lx: 0, ly: 0 })

    // A slalom to dribble. Spacing tightens as you go, so the first gate is
    // generous and the last one asks for a proper touch.
    const slalom: Dummy[] = []
    let x = 12
    for (let i = 0; i < 6; i++) {
      slalom.push(d(x, lane + (i % 2 === 0 ? -0.9 : 0.9), 'slalom'))
      x += 4.2 - i * 0.35
    }

    // A free-kick wall, standing where a real one would: ten yards from the
    // spot, square across the line between the spot and the middle of the goal.
    // Put anywhere else it is scenery — the only thing that makes a wall worth
    // bending round is that it is genuinely in the way.
    const spot = { x: 33, y: lane + 1 }
    const goal = { x: FIELD.length, y: FIELD.width / 2 }
    const dx = goal.x - spot.x
    const dy = goal.y - spot.y
    const len = Math.hypot(dx, dy) || 1
    const ux = dx / len
    const uy = dy / len
    const cx = spot.x + ux * DUMMY.wallDistance
    const cy = spot.y + uy * DUMMY.wallDistance
    const wall: Dummy[] = []
    for (let i = 0; i < DUMMY.wallMen; i++) {
      const off = (i - (DUMMY.wallMen - 1) / 2) * DUMMY.wallSpacing
      // Perpendicular to the line of the shot, so the wall faces the kicker.
      wall.push(d(cx - uy * off, cy + ux * off, 'wall'))
    }

    this.dummies = [...slalom, ...wall]
    this.wallSpot = spot
  }

  // Called when the ball or a body knocks one. The lean is a direction and a
  // magnitude; update() rights it again.
  rock(dummy: Dummy, nx: number, ny: number, impact: number) {
    const lean = Math.min(DUMMY.maxRock, impact * DUMMY.rock)
    if (lean <= dummy.lean) return
    dummy.lean = lean
    dummy.lx = nx
    dummy.ly = ny
  }

  get score() {
    return this.scores[this.current]
  }

  // Deterministic-ish variety without Math.random, so a drill can be replayed.
  private rand(): number {
    this.seed = (this.seed * 1664525 + 1013904223) % 4294967296
    return this.seed / 4294967296
  }

  select(id: DrillId) {
    this.current = id
    this.targets = []
    this.gates = []
    this.wall = []
    this.spot = null
    this.serveLive = false
    this.serveTimer = 0.8
    this.touched = false
    this.verdict = null
    this.trail = []

    if (id === 'targets' || id === 'volley' || id === 'curl' || id === 'finish') this.resetTargets()
    if (id === 'gates') {
      this.resetGates()
      this.spot = { x: 26, y: FIELD.width / 2 - 6 }
    }
    if (id === 'curl') {
      this.spot = { x: FIELD.length - 22, y: FIELD.width / 2 }
      this.resetWall()
    }
  }

  next(): DrillId {
    const i = DRILL_ORDER.indexOf(this.current)
    this.select(DRILL_ORDER[(i + 1) % DRILL_ORDER.length])
    return this.current
  }

  // Four hoops in the corners of the goal — the two at the bottom being the
  // hardest and the most useful to drill.
  resetTargets() {
    const [a, b] = F.goalPostYs()
    const inset = 0.85
    const gx = FIELD.length
    const high = FIELD.goalHeight - 0.55
    const low = 0.5
    this.targets = [
      { x: gx, y: a + inset, z: high, radius: 0.55, hit: false, hitAt: 0 },
      { x: gx, y: b - inset, z: high, radius: 0.55, hit: false, hitAt: 0 },
      { x: gx, y: a + inset, z: low, radius: 0.55, hit: false, hitAt: 0 },
      { x: gx, y: b - inset, z: low, radius: 0.55, hit: false, hitAt: 0 },
    ]
  }

  // Gates spread downfield, progressively further and narrower.
  resetGates() {
    const cy = FIELD.width / 2
    this.gates = [
      { x: 30, y: cy - 6, width: 2.4, angle: 0, maxHeight: 0.45, passed: false, passedAt: 0 },
      { x: 34, y: cy + 5, width: 2.0, angle: 0, maxHeight: 0.45, passed: false, passedAt: 0 },
      { x: 40, y: cy - 2, width: 1.7, angle: 0, maxHeight: 0.45, passed: false, passedAt: 0 },
      { x: 46, y: cy + 3, width: 1.4, angle: 0, maxHeight: 0.45, passed: false, passedAt: 0 },
    ]
  }

  // A four-man wall six metres in front of the spot, dead on the line to the
  // middle of the goal — so the straight ball is simply not available and the
  // only way in is round one side or the other.
  resetWall() {
    const cy = FIELD.width / 2
    // The regulation distance, and it has to be: any closer and the angle needed
    // to clear the wall takes you so wide of the goal that no amount of bend
    // brings the ball back, which makes the drill unsolvable rather than hard.
    const wx = (this.spot?.x ?? FIELD.length - 22) + 9.15
    this.wall = []
    // Shoulder to shoulder. At 0.9 m apart, bodies 0.34 m across leave a 0.22 m
    // gap — which is exactly the diameter of a football, so the wall was a sieve
    // and the straight ball went through the middle of it.
    const n = 5
    const gap = 0.58
    for (let i = 0; i < n; i++) this.wall.push({ x: wx, y: cy + (i - (n - 1) / 2) * gap })
  }

  // ---- serving --------------------------------------------------------------

  // Where the next ball comes from, and how. Varying pace and height is the
  // whole point: a drill that always serves the same ball teaches you one touch.
  private nextServe(world: World): Serve | null {
    const p = world.getControlledPlayer()
    if (!p) return null
    const r = this.rand()
    const side = this.rand() > 0.5 ? 1 : -1
    const dist = 14 + r * 10

    if (this.current === 'cushion') {
      const high = r > 0.5
      return {
        from: { x: p.x + dist, y: p.y + side * (2 + r * 6), z: 0 },
        speed: high ? 12 + r * 6 : 9 + r * 9,
        loft: high ? 0.5 + r * 0.28 : 0.12 + r * 0.16,
        vSpin: -2 - r * 3,
        label: high ? 'DROPPING' : 'FIRM',
      }
    }
    if (this.current === 'volley' || this.current === 'finish') {
      return {
        from: { x: p.x + dist * 0.7, y: p.y + side * (4 + r * 5), z: 0 },
        speed: 12 + r * 5,
        loft: 0.55 + r * 0.25,
        vSpin: -2 - r * 2,
        label: this.current === 'volley' ? 'ON THE VOLLEY' : 'TAKE IT DOWN',
      }
    }
    return null
  }

  private serve(world: World) {
    const s = this.nextServe(world)
    if (!s) return
    const p = world.getControlledPlayer()!
    const dx = p.x - s.from.x
    const dy = p.y - s.from.y
    const d = Math.hypot(dx, dy) || 1
    const horiz = Math.cos(s.loft) * s.speed
    world.ball.setPos(s.from.x, s.from.y, 0.2)
    world.ball.spin = 0
    world.ball.launch(
      (dx / d) * horiz,
      (dy / d) * horiz,
      Math.sin(s.loft) * s.speed,
      0,
      'away',
      -99,
      s.vSpin,
    )
    this.serveLive = true
    this.touched = false
    this.touchSpeed = 0
    this.attemptClock = 0
    this.servedAt = 0
    this.serveLabel = s.label
    this.score.attempts++
  }

  // A drill best that survives a refresh. Without this the whole gauntlet was
  // an exercise you could never see yourself improve at — the point of scoring
  // a drill is the number you had last time.
  private saveBest() {
    const s = this.score
    if (store.recordBest(this.current, s.points, `${s.scored}/${s.attempts} · streak ${s.best}`)) {
      this.newBest = 1.6
    }
  }
  // Counts down while "NEW BEST" is on screen.
  newBest = 0

  best(id: DrillId = this.current) {
    return store.best(id)
  }

  private say(text: string, sub: string, good: boolean, points = 0) {
    this.verdict = { text, sub, good, t: 0 }
    if (good) {
      this.score.scored++
      this.score.streak++
      this.score.best = Math.max(this.score.best, this.score.streak)
      this.score.points += points
      this.saveBest()
    } else {
      this.score.streak = 0
    }
  }

  private endAttempt(delay = 1.4) {
    this.serveLive = false
    this.serveTimer = delay
  }

  // ---- per physics step -----------------------------------------------------

  // Called once per *physics* step. Detection has to live here rather than once
  // a frame: a struck ball covers a third of a metre per step, so a frame-rate
  // check would sail straight through a target or a gate without noticing.
  step(world: World) {
    const ball = world.ball
    const p = world.getControlledPlayer()

    // A sudden jump in speed means the ball has just been struck away.
    const sp = ball.speed
    const struck = sp > this.lastBallSpeed + 2.5 && sp > 6
    if (struck) {
      this.recording = true
      this.trail = []
      this.trailAge = 0
      this.registeredThisShot = false
      if (this.current === 'targets' || this.current === 'gates' || this.current === 'curl') {
        this.score.attempts++
        this.resetGatesFlags()
      }
    }
    this.lastBallSpeed = sp

    if (this.recording) {
      this.sampleTimer += SIM.dt
      if (this.sampleTimer >= 0.02) {
        this.sampleTimer = 0
        this.trail.push({ x: ball.x, y: ball.y, z: ball.z })
        if (this.trail.length > MAX_TRAIL) this.trail.shift()
      }
      if (sp < 1.5 || world.possessorId !== null) this.recording = false
    }

    // The ball is repositioned instantly after a goal or a serve; that jump
    // isn't travel, so ignore any step where it moved further than it could.
    const stepDist = Math.hypot(ball.x - ball.px, ball.y - ball.py, ball.z - ball.pz)
    if (stepDist > 1) return

    this.checkTargets(world)
    if (this.current === 'gates') this.checkGates(world)
    if (p) this.checkContact(world, p)
  }

  private resetGatesFlags() {
    for (const g of this.gates) {
      g.passed = false
      g.passedAt = 0
    }
  }

  // Did this step carry the ball through the plane the hoops hang in?
  private checkTargets(world: World) {
    const ball = world.ball
    for (const t of this.targets) {
      if (t.hit || this.registeredThisShot) continue
      const before = ball.px - t.x
      const after = ball.x - t.x
      if (before >= 0 || after < 0) continue
      const f = before === after ? 0 : before / (before - after)
      const cy = ball.py + (ball.y - ball.py) * f
      const cz = ball.pz + (ball.z - ball.pz) * f
      if (Math.hypot(cy - t.y, cz - t.z) < t.radius) {
        t.hit = true
        t.hitAt = 0
        this.registeredThisShot = true
        if (this.current === 'targets') this.say('BULLSEYE', 'top corner', true, 3)
      }
    }
  }

  // A gate is passed only by a ball that goes *between* the cones and stays
  // below their height. Chip it over and you get nothing, which is what makes
  // this a driven-pass drill rather than a lofted-pass drill.
  private checkGates(world: World) {
    const ball = world.ball
    for (const g of this.gates) {
      if (g.passed) continue
      const before = ball.px - g.x
      const after = ball.x - g.x
      if (before >= 0 || after < 0) continue
      const f = before === after ? 0 : before / (before - after)
      const cy = ball.py + (ball.y - ball.py) * f
      const cz = ball.pz + (ball.z - ball.pz) * f
      if (Math.abs(cy - g.y) > g.width / 2) continue
      if (cz > g.maxHeight) {
        this.say('OVER THE TOP', 'flick DOWN to keep it on the floor', false)
        continue
      }
      g.passed = true
      g.passedAt = 0
      const count = this.gates.filter((x) => x.passed).length
      this.say(count > 1 ? `${count} GATES` : 'THROUGH', 'driven flat', true, count)
    }
  }

  // Everything that depends on the player actually touching the ball.
  private checkContact(world: World, p: { id: number; x: number; y: number }) {
    const ball = world.ball
    const mine = ball.lastTouchId === p.id

    if (this.serveLive) {
      this.attemptClock += SIM.dt
      this.servedAt += SIM.dt
    }

    // --- cushion: score how dead you killed it -----------------------------
    if (this.current === 'cushion' && this.serveLive) {
      if (!this.touched && mine) {
        this.touched = true
        this.touchSpeed = ball.speed
        const dist = Math.hypot(ball.x - p.x, ball.y - p.y)
        const inControl = dist < p_reach()
        if (!inControl) {
          this.say('LOOSE', `${dist.toFixed(1)} m away — too far to play`, false)
        } else if (this.touchSpeed < 1.2) {
          this.say('PERFECT', `dead at ${this.touchSpeed.toFixed(1)} m/s`, true, 3)
        } else if (this.touchSpeed < 2.6) {
          this.say('GOOD TOUCH', `${this.touchSpeed.toFixed(1)} m/s`, true, 2)
        } else if (this.touchSpeed < 4.5) {
          this.say('HEAVY', `${this.touchSpeed.toFixed(1)} m/s — softer`, true, 1)
        } else {
          this.say('BOUNCED OFF', `${this.touchSpeed.toFixed(1)} m/s`, false)
        }
        this.endAttempt()
      } else if (this.servedAt > 6) {
        this.say('MISSED IT', 'get in the way of it', false)
        this.endAttempt()
      }
    }

    // --- volley: it has to be struck before it lands ------------------------
    if (this.current === 'volley' && this.serveLive) {
      if (!this.touched && mine) {
        this.touched = true
        // Contact height is recorded by the world when the kick fires; a volley
        // leaves the ball moving fast and off the deck.
        const fast = ball.speed > 14
        if (!fast) {
          this.say('NO CONTACT', 'meet it cleanly on the drop', false)
          this.endAttempt()
        }
      } else if (this.touched && this.registeredThisShot) {
        this.say('VOLLEYED IN', 'off the boot, out of the air', true, 3)
        this.endAttempt(1.6)
        this.registeredThisShot = false
      } else if (this.touched && this.servedAt > 4) {
        this.say('OFF TARGET', 'struck it, missed the goal', false)
        this.endAttempt()
      } else if (this.servedAt > 6) {
        this.say('LET IT BOUNCE', 'strike it before it lands', false)
        this.endAttempt()
      }
    }

    // --- touch & finish: take it down, then score on the clock ---------------
    if (this.current === 'finish' && this.serveLive) {
      if (!this.touched && mine) {
        this.touched = true
        this.attemptClock = 0
      }
      if (this.touched) {
        if (this.registeredThisShot) {
          this.say('FINISHED', `${this.attemptClock.toFixed(1)}s after your touch`, true, 3)
          this.endAttempt(1.6)
          this.registeredThisShot = false
        } else if (this.attemptClock > 4) {
          this.say('TOO SLOW', 'four seconds from touch to finish', false)
          this.endAttempt()
        }
      } else if (this.servedAt > 6) {
        this.say('MISSED IT', 'take it down first', false)
        this.endAttempt()
      }
    }
  }

  // Called by the world when a goal is scored in training.
  goal(world: World) {
    const ball = world.ball
    if (this.current === 'curl') {
      // Round the wall means the ball crossed the wall's line outside the bodies
      // — which it must have, since they're solid — and did it with real bend.
      const bend = Math.abs(ball.spin)
      if (bend > 2.5) this.say('BENT IT IN', `${bend.toFixed(1)} m/s of side-spin`, true, 3)
      else this.say('IN, BUT STRAIGHT', 'flick sideways to bend it', true, 1)
      this.score.attempts = Math.max(this.score.attempts, this.score.scored)
    } else if (this.current === 'volley' || this.current === 'finish') {
      this.registeredThisShot = true
    } else if (this.current === 'targets') {
      if (!this.registeredThisShot) this.say('IN', 'but not through a hoop', true, 1)
    }
  }

  // ---- per frame ------------------------------------------------------------

  update(world: World, dt: number) {
    for (const t of this.targets) {
      if (!t.hit) continue
      t.hitAt += dt
      if (t.hitAt > 1.2) {
        t.hit = false
        t.hitAt = 0
      }
    }
    for (const g of this.gates) {
      if (g.passed) g.passedAt += dt
    }
    if (this.newBest > 0) this.newBest -= dt
    // Rocked dummies right themselves.
    for (const m of this.dummies) {
      if (m.lean > 1e-4) m.lean = Math.max(0, m.lean - m.lean * DUMMY.settle * dt - 0.02 * dt)
    }
    if (this.verdict) {
      this.verdict.t += dt
      if (this.verdict.t > 2.4) this.verdict = null
    }
    if (!this.recording && this.trail.length) {
      this.trailAge += dt
      if (this.trailAge > 8) this.trail = []
    }

    // Serve the next ball for the drills that feed you one.
    if (this.servesBalls()) {
      if (!this.serveLive) {
        this.serveTimer -= dt
        if (this.serveTimer <= 0) this.serve(world)
      }
    }
  }

  servesBalls(): boolean {
    return this.current === 'cushion' || this.current === 'volley' || this.current === 'finish'
  }

  // Called when a shot clearly missed everything, to break the streak.
  missed() {
    if (this.current === 'targets' || this.current === 'gates' || this.current === 'curl') {
      this.score.streak = 0
    }
  }

  get accuracy(): number {
    const s = this.score
    return s.attempts ? s.scored / s.attempts : 0
  }

  reset() {
    for (const k of DRILL_ORDER) this.scores[k] = blank()
    this.trail = []
    this.select(this.current)
  }
}

const blank = () => ({ attempts: 0, scored: 0, points: 0, best: 0, streak: 0 })
const p_reach = () => PLAYER.radius + BALL.radius + PLAYER.reach
