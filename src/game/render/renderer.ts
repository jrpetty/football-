import { BALL, DUMMY, FIELD, KITS, PLAYER } from '../config'
import { clamp01 } from '../core/math'
import type { Player } from '../entities/player'
import type { World } from '../match/world'
import * as F from '../match/field'
import type { Camera } from './camera'

// All the pixels. Pure function of world + camera state — it reads, never writes
// the simulation. Top-down pitch with a faux-3D ball (shadow + height offset).
export class Renderer {
  // Interpolation factor between the last two physics steps, refreshed each draw.
  private alpha = 0

  constructor(private ctx: CanvasRenderingContext2D) {}

  draw(world: World, cam: Camera) {
    const ctx = this.ctx
    this.alpha = world.renderAlpha
    ctx.save()
    this.drawSurround(cam)
    this.drawPitch(cam)
    this.drawDrills(world, cam)
    this.drawEffectsUnder(world, cam)
    // Ball shadow first so players can overlap it.
    this.drawBallShadow(world, cam)
    // Depth-sort players by world-y so lower players draw on top.
    const sorted = [...world.players].sort((a, b) => a.y - b.y)
    for (const p of sorted) this.drawPlayer(p, world, cam)
    this.drawBall(world, cam)
    this.drawEffectsOver(world, cam)
    ctx.restore()
  }

  // Training apparatus. The 2D view never drew any of this, which meant anyone
  // playing the gauntlet from up here was aiming at gates and a wall they could
  // not see.
  private drawDrills(world: World, cam: Camera) {
    const d = world.drills
    if (!d) return
    const ctx = this.ctx

    // The spot a set-piece drill is struck from.
    if (d.spot) {
      const s = cam.worldToScreen(d.spot.x, d.spot.y)
      ctx.strokeStyle = 'rgba(255,255,255,0.5)'
      ctx.setLineDash([4, 4])
      ctx.lineWidth = 1.5
      ctx.beginPath()
      ctx.arc(s.x, s.y, cam.ppm * 0.6, 0, Math.PI * 2)
      ctx.stroke()
      ctx.setLineDash([])
    }

    // Gates: a pair of cones with the lane between them shaded, so it's obvious
    // where the ball has to go.
    for (const g of d.gates) {
      const a = cam.worldToScreen(g.x, g.y - g.width / 2)
      const b = cam.worldToScreen(g.x, g.y + g.width / 2)
      ctx.strokeStyle = g.passed ? 'rgba(141,255,168,0.85)' : 'rgba(255,157,61,0.55)'
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.moveTo(a.x, a.y)
      ctx.lineTo(b.x, b.y)
      ctx.stroke()
      ctx.fillStyle = g.passed ? '#8dffa8' : '#ff9d3d'
      for (const c of [a, b]) {
        ctx.beginPath()
        ctx.arc(c.x, c.y, Math.max(3, cam.ppm * 0.16), 0, Math.PI * 2)
        ctx.fill()
      }
    }

    // The permanent apparatus: the slalom down the touchline and the free-kick
    // wall standing off to one side of it.
    for (const m of d.dummies) {
      const s = cam.worldToScreen(m.x, m.y)
      const r = Math.max(2.5, cam.ppm * DUMMY.radius)
      if (m.kind === 'slalom') {
        ctx.strokeStyle = '#ff9d3d'
        ctx.lineWidth = Math.max(2, r * 0.7)
        ctx.beginPath()
        ctx.arc(s.x, s.y, r * 0.55, 0, Math.PI * 2)
        ctx.stroke()
      } else {
        ctx.fillStyle = '#39424f'
        ctx.beginPath()
        ctx.arc(s.x, s.y, r, 0, Math.PI * 2)
        ctx.fill()
        ctx.strokeStyle = 'rgba(255,255,255,0.25)'
        ctx.lineWidth = 1
        ctx.stroke()
      }
      // A knocked dummy leans, and from above that reads as a shift off its
      // base — so the base stays put and the body slides.
      if (m.lean > 0.01) {
        ctx.strokeStyle = 'rgba(255,255,255,0.35)'
        ctx.lineWidth = 1
        ctx.beginPath()
        ctx.moveTo(s.x, s.y)
        ctx.lineTo(s.x + m.lx * m.lean * cam.ppm * 2, s.y + m.ly * m.lean * cam.ppm * 2)
        ctx.stroke()
      }
    }

    // The spot the free-kick wall is standing ten yards from.
    if (d.wallSpot) {
      const s = cam.worldToScreen(d.wallSpot.x, d.wallSpot.y)
      ctx.strokeStyle = 'rgba(255,255,255,0.35)'
      ctx.setLineDash([3, 3])
      ctx.lineWidth = 1.5
      ctx.beginPath()
      ctx.arc(s.x, s.y, cam.ppm * 0.5, 0, Math.PI * 2)
      ctx.stroke()
      ctx.setLineDash([])
    }

    // The wall.
    for (const m of d.wall) {
      const s = cam.worldToScreen(m.x, m.y)
      ctx.fillStyle = '#2a3340'
      ctx.beginPath()
      ctx.arc(s.x, s.y, Math.max(4, cam.ppm * 0.34), 0, Math.PI * 2)
      ctx.fill()
      ctx.strokeStyle = 'rgba(255,255,255,0.18)'
      ctx.lineWidth = 1
      ctx.stroke()
    }

    // Hoops in the goal, drawn from above as marks on the line.
    for (const t of d.targets) {
      const s = cam.worldToScreen(t.x, t.y)
      ctx.strokeStyle = t.hit ? 'rgba(141,255,168,0.95)' : 'rgba(255,216,94,0.7)'
      ctx.lineWidth = 3
      ctx.beginPath()
      ctx.arc(s.x, s.y, Math.max(3, cam.ppm * t.radius * 0.6), 0, Math.PI * 2)
      ctx.stroke()
    }
  }

  private drawSurround(cam: Camera) {
    const ctx = this.ctx
    const g = ctx.createLinearGradient(0, 0, 0, cam.height)
    g.addColorStop(0, '#0d2417')
    g.addColorStop(1, '#08160f')
    ctx.fillStyle = g
    ctx.fillRect(0, 0, cam.width, cam.height)
  }

  private drawPitch(cam: Camera) {
    const ctx = this.ctx
    const tl = cam.worldToScreen(0, 0)
    const ppm = (cam.worldToScreen(1, 0).x - tl.x)

    // Mowing stripes down the length of the pitch.
    const stripes = 12
    const stripeW = FIELD.length / stripes
    for (let i = 0; i < stripes; i++) {
      const a = cam.worldToScreen(i * stripeW, 0)
      ctx.fillStyle = i % 2 === 0 ? '#1f7a3d' : '#1b6e37'
      ctx.fillRect(a.x, a.y, stripeW * ppm + 1, FIELD.width * ppm)
    }

    // Soft inner vignette on the turf.
    const cx = cam.worldToScreen(FIELD.length / 2, FIELD.width / 2)
    const rg = ctx.createRadialGradient(cx.x, cx.y, ppm * 6, cx.x, cx.y, ppm * FIELD.length * 0.7)
    rg.addColorStop(0, 'rgba(255,255,255,0.04)')
    rg.addColorStop(1, 'rgba(0,0,0,0.22)')
    ctx.fillStyle = rg
    ctx.fillRect(tl.x, tl.y, FIELD.length * ppm, FIELD.width * ppm)

    // Markings.
    ctx.strokeStyle = 'rgba(255,255,255,0.72)'
    ctx.lineWidth = Math.max(1.5, ppm * 0.12)
    ctx.lineCap = 'round'

    // Outer boundary.
    this.strokeRectW(cam, 0, 0, FIELD.length, FIELD.width)
    // Halfway line.
    this.lineW(cam, FIELD.length / 2, 0, FIELD.length / 2, FIELD.width)
    // Centre circle + spot.
    this.circleW(cam, FIELD.length / 2, FIELD.width / 2, FIELD.centerRadius)
    this.dotW(cam, FIELD.length / 2, FIELD.width / 2, ppm * 0.2)

    // Penalty areas.
    const yMin = (FIELD.width - FIELD.boxWidth) / 2
    this.strokeRectW(cam, 0, yMin, FIELD.boxDepth, FIELD.boxWidth)
    this.strokeRectW(cam, FIELD.length - FIELD.boxDepth, yMin, FIELD.boxDepth, FIELD.boxWidth)

    // Goals (posts + net hatch).
    this.drawGoal(cam, 'home')
    this.drawGoal(cam, 'away')
  }

  private drawGoal(cam: Camera, team: 'home' | 'away') {
    const ctx = this.ctx
    const goalX = F.ownGoalLineX(team)
    const [a, b] = F.goalPostYs()
    const depth = team === 'home' ? -FIELD.goalDepth : FIELD.goalDepth
    // Net box behind the line.
    const p1 = cam.worldToScreen(goalX, a)
    const p2 = cam.worldToScreen(goalX + depth, b)
    ctx.fillStyle = 'rgba(255,255,255,0.06)'
    ctx.fillRect(Math.min(p1.x, p2.x), Math.min(p1.y, p2.y), Math.abs(p2.x - p1.x), Math.abs(p2.y - p1.y))
    ctx.strokeStyle = 'rgba(255,255,255,0.35)'
    ctx.lineWidth = 1.5
    ctx.strokeRect(Math.min(p1.x, p2.x), Math.min(p1.y, p2.y), Math.abs(p2.x - p1.x), Math.abs(p2.y - p1.y))
    // Posts.
    ctx.fillStyle = '#ffffff'
    for (const py of [a, b]) {
      const s = cam.worldToScreen(goalX, py)
      ctx.beginPath()
      ctx.arc(s.x, s.y, Math.max(2.5, cam.ppm * 0.16), 0, Math.PI * 2)
      ctx.fill()
    }
  }

  // A real football is only 22cm across, which is barely a pixel or two from a
  // top-down camera. Nudge it up just enough to stay visible while keeping it
  // honestly small next to a player — the simulation always uses the true size.
  private ballScreenRadius(cam: Camera): number {
    return Math.max(2.6, cam.ppm * BALL.radius * 1.25)
  }

  private drawBallShadow(world: World, cam: Camera) {
    const ctx = this.ctx
    const b = world.ball
    const bp = b.renderPos(this.alpha)
    const s = cam.worldToScreen(bp.x, bp.y, 0)
    const lift = clamp01(bp.z / 6)
    const r = this.ballScreenRadius(cam) * (1 + lift * 0.6)
    ctx.fillStyle = `rgba(0,0,0,${0.28 * (1 - lift * 0.5)})`
    ctx.beginPath()
    ctx.ellipse(s.x, s.y, r, r * 0.6, 0, 0, Math.PI * 2)
    ctx.fill()
  }

  private drawBall(world: World, cam: Camera) {
    const ctx = this.ctx
    const b = world.ball
    const bp = b.renderPos(this.alpha)
    const s = cam.worldToScreen(bp.x, bp.y, bp.z)
    const r = this.ballScreenRadius(cam) * (1 + clamp01(bp.z / 8) * 0.35)

    // Motion streak for fast shots.
    const spd = b.horizontalSpeed
    if (spd > 12) {
      const back = cam.worldToScreen(bp.x - b.vx * 0.03, bp.y - b.vy * 0.03, bp.z)
      ctx.strokeStyle = 'rgba(255,255,255,0.35)'
      ctx.lineWidth = r * 1.2
      ctx.lineCap = 'round'
      ctx.beginPath()
      ctx.moveTo(back.x, back.y)
      ctx.lineTo(s.x, s.y)
      ctx.stroke()
    }

    const g = ctx.createRadialGradient(s.x - r * 0.3, s.y - r * 0.3, r * 0.1, s.x, s.y, r)
    g.addColorStop(0, '#ffffff')
    g.addColorStop(1, '#c9ccd2')
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(s.x, s.y, r, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = 'rgba(0,0,0,0.25)'
    ctx.lineWidth = 1
    ctx.stroke()
    // Pentagon dots hint at spin direction subtly.
    ctx.fillStyle = 'rgba(20,20,25,0.7)'
    ctx.beginPath()
    ctx.arc(s.x, s.y, r * 0.28, 0, Math.PI * 2)
    ctx.fill()
  }

  private drawPlayer(p: Player, world: World, cam: Camera) {
    const ctx = this.ctx
    const kit = KITS[p.team]
    const rp = p.renderPos(this.alpha)
    // The shadow stays on the turf; the body lifts off it. From directly above
    // a jump would be invisible otherwise.
    const ground = cam.worldToScreen(rp.x, rp.y, 0)
    const s = cam.worldToScreen(rp.x, rp.y, rp.z)
    const r = cam.ppm * PLAYER.radius
    const controlled = world.getControlledPlayer()?.id === p.id
    const isGk = p.role === 'GK'

    // Shadow (offset if sliding, to sell the lunge). It shrinks and fades as
    // you rise, which is what reads as height from above.
    const lift = Math.max(0, rp.z)
    const k = 1 / (1 + lift * 0.8)
    ctx.fillStyle = `rgba(0,0,0,${(0.3 * k).toFixed(3)})`
    ctx.beginPath()
    ctx.ellipse(ground.x, ground.y + r * 0.15, r * 1.05 * k, r * 0.6 * k, 0, 0, Math.PI * 2)
    ctx.fill()

    // Controlled highlight ring.
    if (controlled) {
      ctx.strokeStyle = 'rgba(255,255,255,0.9)'
      ctx.lineWidth = 2.5
      ctx.beginPath()
      ctx.arc(s.x, s.y, r + 4, 0, Math.PI * 2)
      ctx.stroke()
    }

    // Body.
    const body = isGk ? kit.gk : kit.primary
    const g = ctx.createRadialGradient(s.x - r * 0.35, s.y - r * 0.35, r * 0.2, s.x, s.y, r)
    g.addColorStop(0, this.lighten(body, 0.25))
    g.addColorStop(1, body)
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(s.x, s.y, r, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = kit.accent
    ctx.lineWidth = 2
    ctx.stroke()

    // Facing wedge.
    const f = p.facing
    ctx.fillStyle = kit.secondary
    ctx.beginPath()
    ctx.moveTo(s.x + f.x * r * 1.5, s.y + f.y * r * 1.5)
    const lx = -f.y
    const ly = f.x
    ctx.lineTo(s.x + lx * r * 0.55, s.y + ly * r * 0.55)
    ctx.lineTo(s.x - lx * r * 0.55, s.y - ly * r * 0.55)
    ctx.closePath()
    ctx.fill()

    // Number.
    ctx.fillStyle = kit.secondary
    ctx.font = `${Math.max(8, r * 0.9)}px system-ui, sans-serif`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(String(p.number), s.x, s.y)

    // Stamina arc for the controlled player.
    if (controlled) {
      const frac = p.energy
      ctx.strokeStyle = frac > 0.35 ? '#8ef58a' : '#f5b14a'
      ctx.lineWidth = 3
      ctx.beginPath()
      ctx.arc(s.x, s.y, r + 8, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * frac)
      ctx.stroke()
    }

    // Who that is. From above a shirt number tells you nothing about whether
    // anybody is behind it, and in a game where unclaimed shirts just stand
    // there that is the thing you most need to know. Skipped when zoomed far
    // enough out that the labels would be a wall of text.
    if (r > 5) {
      const name = world.nameFor(p)
      const claimed = world.isClaimed(p)
      ctx.font = `${Math.max(9, r * 0.85)}px system-ui, sans-serif`
      ctx.textAlign = 'center'
      ctx.textBaseline = 'bottom'
      const ty = s.y - r - 5
      if (claimed) {
        const w = ctx.measureText(name).width + 8
        ctx.fillStyle = 'rgba(8,12,20,0.55)'
        roundRect(ctx, s.x - w / 2, ty - r * 0.95, w, r * 1.05, 3)
        ctx.fill()
        ctx.fillStyle = p.team === 'home' ? '#bfe0ff' : '#ffd2d2'
      } else {
        ctx.fillStyle = 'rgba(255,255,255,0.42)'
      }
      ctx.fillText(name, s.x, ty)
    }
  }

  private drawEffectsUnder(world: World, cam: Camera) {
    // Reserved for ground-level marks; effects currently render over.
    void world
    void cam
  }

  private drawEffectsOver(world: World, cam: Camera) {
    const ctx = this.ctx
    for (const e of world.effects) {
      const t = e.t / e.life
      const s = cam.worldToScreen(e.x, e.y, 0)
      if (e.type === 'kick' || e.type === 'whistle' || e.type === 'spawn') {
        ctx.strokeStyle = `rgba(255,255,255,${0.6 * (1 - t)})`
        ctx.lineWidth = 2
        ctx.beginPath()
        ctx.arc(s.x, s.y, cam.ppm * (0.4 + t * 1.6), 0, Math.PI * 2)
        ctx.stroke()
      } else if (e.type === 'save') {
        ctx.strokeStyle = `rgba(120,220,255,${0.8 * (1 - t)})`
        ctx.lineWidth = 3
        ctx.beginPath()
        ctx.arc(s.x, s.y, cam.ppm * (0.6 + t * 2.4), 0, Math.PI * 2)
        ctx.stroke()
      } else if (e.type === 'tackle') {
        ctx.fillStyle = `rgba(220,200,150,${0.5 * (1 - t)})`
        for (let i = 0; i < 5; i++) {
          const ang = (i / 5) * Math.PI * 2
          const rr = cam.ppm * (0.3 + t * 1.4)
          ctx.beginPath()
          ctx.arc(s.x + Math.cos(ang) * rr, s.y + Math.sin(ang) * rr, 2, 0, Math.PI * 2)
          ctx.fill()
        }
      } else if (e.type === 'post') {
        ctx.fillStyle = `rgba(255,255,255,${0.8 * (1 - t)})`
        ctx.beginPath()
        ctx.arc(s.x, s.y, cam.ppm * 0.3 * (1 - t) + 2, 0, Math.PI * 2)
        ctx.fill()
      } else if (e.type === 'goal') {
        ctx.strokeStyle = `rgba(255,230,120,${0.9 * (1 - t)})`
        ctx.lineWidth = 4
        ctx.beginPath()
        ctx.arc(s.x, s.y, cam.ppm * (1 + t * 6), 0, Math.PI * 2)
        ctx.stroke()
      }
    }
  }

  // ---- world-space primitive helpers ----

  private lineW(cam: Camera, x1: number, y1: number, x2: number, y2: number) {
    const a = cam.worldToScreen(x1, y1)
    const b = cam.worldToScreen(x2, y2)
    this.ctx.beginPath()
    this.ctx.moveTo(a.x, a.y)
    this.ctx.lineTo(b.x, b.y)
    this.ctx.stroke()
  }
  private strokeRectW(cam: Camera, x: number, y: number, w: number, h: number) {
    const a = cam.worldToScreen(x, y)
    const b = cam.worldToScreen(x + w, y + h)
    this.ctx.strokeRect(a.x, a.y, b.x - a.x, b.y - a.y)
  }
  private circleW(cam: Camera, x: number, y: number, radius: number) {
    const c = cam.worldToScreen(x, y)
    this.ctx.beginPath()
    this.ctx.arc(c.x, c.y, radius * cam.ppm, 0, Math.PI * 2)
    this.ctx.stroke()
  }
  private dotW(cam: Camera, x: number, y: number, r: number) {
    const c = cam.worldToScreen(x, y)
    this.ctx.fillStyle = 'rgba(255,255,255,0.72)'
    this.ctx.beginPath()
    this.ctx.arc(c.x, c.y, r, 0, Math.PI * 2)
    this.ctx.fill()
  }

  private lighten(hex: string, amt: number): string {
    const c = hex.replace('#', '')
    const n = parseInt(c.length === 3 ? c.replace(/./g, '$&$&') : c, 16)
    const r = Math.min(255, ((n >> 16) & 255) + amt * 255)
    const g = Math.min(255, ((n >> 8) & 255) + amt * 255)
    const b = Math.min(255, (n & 255) + amt * 255)
    return `rgb(${r | 0},${g | 0},${b | 0})`
  }
}

// A rounded rectangle path, for the plate behind a name tag.
function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}
