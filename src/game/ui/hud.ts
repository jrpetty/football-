import { BALL, FIELD, KITS, PLAYER } from '../config'
import { clamp01 } from '../core/math'
import { store } from '../core/store'
import { DRILL_INFO } from '../match/drills'
import type { Effect, World } from '../match/world'
import { DISPLAY, UI } from './fonts'

// What the online half of the game wants shown. Absent entirely when playing
// on your own, which is why every field is optional in practice.
export interface NetInfo {
  role: 'host' | 'guest'
  ping: number // ms, round trip
  reconnecting: boolean
  spectating: boolean
  // Host only: everyone connected. A guest is only told its own ping.
  peers?: { name: string; ping: number; away: boolean; spectator: boolean }[]
}

export interface HudInfo {
  chargeType: 'touch' | 'strike' | null
  charge: number // 0..1
  loft: number // −1..1, live from the mouse flick
  spin: number // signed, live from the mouse flick
  fps: number
  mode: 'match' | 'training'
  zoomLabel: string
  net?: NetInfo | null
  // Where the ball is on the screen, when the view can say (3D). x and y are in
  // CSS pixels; `on` is false when it is off the edge, in which case the HUD
  // points at it instead. Absent in the 2D view, where the ball is always in it.
  ball?: { x: number; y: number; on: boolean; metres: number } | null
  // Anything else worth a line at the bottom-left: a tutorial's current step.
  coach?: { title: string; text: string; done: number; total: number } | null
}

// The minimap's width. Shared, because the connection panel has to sit above
// the minimap and needs to know how tall it is.
const MINIMAP_W = 200

const LIME = '#c4ff45'
const INK = 'rgba(6,10,17,0.8)'
const LINE = 'rgba(255,255,255,0.11)'
const TEXT = '#eef2f7'
const MUTED = '#8b9bb2'
const DIM = '#5d6b82'

const disp = (weight: number, px: number) => `${weight} ${px}px ${DISPLAY}`
const ui = (weight: number, px: number) => `${weight} ${px}px ${UI}`

// A panel with its top-right corner cut, the shape the menu's buttons have.
function panel(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, cut = 10, fill = INK) {
  ctx.beginPath()
  ctx.moveTo(x, y)
  ctx.lineTo(x + w - cut, y)
  ctx.lineTo(x + w, y + cut)
  ctx.lineTo(x + w, y + h)
  ctx.lineTo(x, y + h)
  ctx.closePath()
  ctx.fillStyle = fill
  ctx.fill()
  ctx.strokeStyle = LINE
  ctx.lineWidth = 1
  ctx.stroke()
}

function spaced(ctx: CanvasRenderingContext2D, px: number) {
  const c = ctx as CanvasRenderingContext2D & { letterSpacing?: string }
  if ('letterSpacing' in c) c.letterSpacing = `${px}px`
}

// On-screen furniture drawn on top of the rendered pitch. Reads world state only.
export class Hud {
  // The last strike you made, for the readout above the stamina bar.
  private lastShot: { speed: number; at: number } | null = null
  private seen = new WeakSet<Effect>()

  draw(ctx: CanvasRenderingContext2D, world: World, info: HudInfo, w: number, h: number) {
    // One number scales the lot. Everything below lays itself out in a viewport
    // that is `s` times smaller than the real one, so a bigger HUD stays pinned
    // to the same corners.
    const s = store.get('hudScale')
    ctx.save()
    ctx.scale(s, s)
    w /= s
    h /= s
    ctx.textBaseline = 'alphabetic'
    spaced(ctx, 0)

    this.watchShots(world)
    this.drawScoreboard(ctx, world, w)
    if (info.ball && !info.ball.on) this.drawBallPointer(ctx, info.ball, w, h)
    if (info.coach) this.drawCoach(ctx, info.coach, w)
    if (!info.net?.spectating) this.drawStamina(ctx, world, h)
    this.drawShot(ctx, world, h)
    this.drawContactCue(ctx, world, w, h)
    if (info.chargeType) this.drawPowerMeter(ctx, info, w, h)
    // The lesson has the top of the screen while it is talking.
    if (!info.coach) this.drawDrills(ctx, world, w)
    this.drawMinimap(ctx, world, w, h)
    this.drawAnnounce(ctx, world, w, h)
    this.drawZoomFps(ctx, info, w)
    if (info.net) this.drawNet(ctx, info.net, w, h)
    ctx.restore()
  }

  // ---- scoreboard --------------------------------------------------------------

  // Two team blocks either side of the clock, in the shape of a broadcast bug.
  private drawScoreboard(ctx: CanvasRenderingContext2D, world: World, w: number) {
    const cx = w / 2
    const H = 46
    const y = 14
    const nameW = 92
    const scoreW = 48
    const clockW = 104
    const total = 6 * 2 + nameW * 2 + scoreW * 2 + clockW
    const x = cx - total / 2

    ctx.fillStyle = 'rgba(6,10,17,0.86)'
    ctx.fillRect(x, y, total, H)
    ctx.strokeStyle = LINE
    ctx.strokeRect(x + 0.5, y + 0.5, total - 1, H - 1)

    // Colour bars on the outer edges.
    ctx.fillStyle = KITS.home.primary
    ctx.fillRect(x, y, 6, H)
    ctx.fillStyle = KITS.away.primary
    ctx.fillRect(x + total - 6, y, 6, H)

    // Score cells, a step lighter than the rest.
    ctx.fillStyle = 'rgba(255,255,255,0.08)'
    ctx.fillRect(x + 6 + nameW, y, scoreW, H)
    ctx.fillRect(x + total - 6 - nameW - scoreW, y, scoreW, H)

    ctx.textBaseline = 'middle'
    ctx.fillStyle = TEXT
    ctx.textAlign = 'right'
    ctx.font = disp(800, 21)
    spaced(ctx, 2)
    ctx.fillText('HOME', x + 6 + nameW - 12, y + H / 2 + 1)
    ctx.textAlign = 'left'
    ctx.fillText('AWAY', x + total - 6 - nameW + 12, y + H / 2 + 1)
    spaced(ctx, 0)

    ctx.textAlign = 'center'
    ctx.font = disp(900, 32)
    ctx.fillText(String(world.score.home), x + 6 + nameW + scoreW / 2, y + H / 2 + 2)
    ctx.fillText(String(world.score.away), x + total - 6 - nameW - scoreW / 2, y + H / 2 + 2)

    // Clock, with what part of the match it is above it.
    const training = world.config.mode === 'training'
    const top = training ? 'PRACTICE' : world.phase === 'fulltime' ? 'FULL TIME' : world.half === 1 ? 'FIRST HALF' : 'SECOND HALF'
    ctx.fillStyle = LIME
    ctx.font = disp(700, 11)
    spaced(ctx, 2.5)
    ctx.fillText(top, cx, y + 15)
    spaced(ctx, 0)
    ctx.fillStyle = TEXT
    ctx.font = disp(800, 22)
    ctx.fillText(training ? '—' : fmtClock(world.clock), cx, y + 34)
  }

  // ---- what you are doing --------------------------------------------------------

  private drawStamina(ctx: CanvasRenderingContext2D, world: World, h: number) {
    const p = world.getControlledPlayer()
    if (!p) return
    const x = 16
    const bw = 232
    const bh = 48
    const y = h - bh - 16
    panel(ctx, x, y, bw, bh, 10)

    // The shirt number, in the colour of the shirt.
    ctx.fillStyle = KITS[p.team].primary
    ctx.fillRect(x, y, 5, bh)
    ctx.textBaseline = 'middle'
    ctx.textAlign = 'left'
    ctx.fillStyle = TEXT
    ctx.font = disp(900, 30)
    ctx.fillText(`${p.number}`, x + 18, y + bh / 2 + 2)

    ctx.font = disp(700, 11)
    ctx.fillStyle = MUTED
    spaced(ctx, 2.2)
    ctx.textBaseline = 'alphabetic'
    ctx.fillText('STAMINA', x + 68, y + 20)
    spaced(ctx, 0)

    // A row of cells, so it reads at a glance and drains in visible steps.
    const cells = 20
    const cw = (bw - 68 - 16) / cells
    const frac = p.energy
    const low = frac <= 0.35
    for (let i = 0; i < cells; i++) {
      const on = (i + 0.5) / cells <= frac
      ctx.fillStyle = on ? (low ? '#ffb14a' : LIME) : 'rgba(255,255,255,0.12)'
      ctx.fillRect(x + 68 + i * cw, y + 28, cw - 2, 9)
    }
  }

  // How hard you hit it, for as long as the last one is worth remembering. The
  // number you would otherwise have to guess at is the one that tells you how
  // your wrist maps onto power.
  private watchShots(world: World) {
    const me = world.getControlledPlayer()
    for (const e of world.effects) {
      if (this.seen.has(e)) continue
      this.seen.add(e)
      if (e.type !== 'kick' || !me || e.speed < 4 || e.t > 0.2) continue
      if (Math.hypot(e.x - me.x, e.y - me.y) > 3) continue
      this.lastShot = { speed: e.speed, at: performance.now() }
    }
    if (this.lastShot && performance.now() - this.lastShot.at > 3200) this.lastShot = null
  }

  private drawShot(ctx: CanvasRenderingContext2D, world: World, h: number) {
    const s = this.lastShot
    if (!s || world.config.mode !== 'training') return
    const age = (performance.now() - s.at) / 1000
    const fade = clamp01((3.2 - age) / 0.8)
    const x = 16
    const y = h - 48 - 16 - 42
    ctx.save()
    ctx.globalAlpha = fade
    panel(ctx, x, y, 232, 34, 8)
    ctx.textBaseline = 'alphabetic'
    ctx.textAlign = 'left'
    ctx.fillStyle = MUTED
    ctx.font = disp(700, 11)
    spaced(ctx, 2.2)
    ctx.fillText('LAST STRIKE', x + 14, y + 21)
    spaced(ctx, 0)
    ctx.textAlign = 'right'
    ctx.fillStyle = TEXT
    ctx.font = disp(900, 22)
    ctx.fillText(`${Math.round(s.speed * 3.6)}`, x + 232 - 50, y + 26)
    ctx.fillStyle = MUTED
    ctx.font = ui(600, 12)
    ctx.textAlign = 'left'
    ctx.fillText('km/h', x + 232 - 44, y + 26)
    ctx.restore()
  }

  // When the ball is dropping into your area, say what a click would do with it
  // right now. Playing a ball out of the air is a snap decision and the height
  // is hard to read off a 3D scene, so this is the one piece of coaching the HUD
  // offers — it tells you what's available, never when to take it.
  private drawContactCue(ctx: CanvasRenderingContext2D, world: World, w: number, h: number) {
    const p = world.getControlledPlayer()
    if (!p) return
    const b = world.ball

    const pill = (text: string, colour: string, alpha = 1) => {
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.font = disp(800, 16)
      spaced(ctx, 2)
      const bw = ctx.measureText(text).width + 40
      const y = h - 96
      panel(ctx, w / 2 - bw / 2, y - 14, bw, 30, 8)
      ctx.globalAlpha = alpha
      ctx.fillStyle = colour
      ctx.fillText(text, w / 2, y + 2)
      ctx.globalAlpha = 1
      spaced(ctx, 0)
    }

    // A keeper's buttons mean something different, and there is nothing on
    // screen that would tell you so.
    if (p.role === 'GK' && world.possessorId !== p.id) {
      pill(
        p.diving ? 'DIVING' : p.diveRecover > 0 ? 'GETTING UP' : 'LEFT — DIVE  ·  RIGHT — GATHER',
        p.diving ? '#ffe28a' : '#9fd0ff',
      )
      return
    }

    // Shielding is a mode you're holding, and its whole cost is invisible from
    // behind the player, so say so.
    if (p.shielding) {
      pill('SHIELDING  ·  NO STRIKE', '#8cdcff')
      return
    }
    if (b.z <= PLAYER.controlHeight) return
    const d = Math.hypot(b.x - p.x, b.y - p.y)
    if (d > p.radius + BALL.radius + PLAYER.reach * 1.25) return
    if (b.z > PLAYER.aerialReach) return

    const header = b.z > PLAYER.headerHeight
    // Fade in as the ball drops into range, so it reads as a window opening.
    const near = clamp01((PLAYER.aerialReach - b.z) / 0.7)
    pill(`CUSHION  ·  ${header ? 'HEADER' : 'VOLLEY'}`, '#ffe28a', 0.45 + near * 0.55)
  }

  // The power bar, plus live readouts of the shaping the mouse flick is applying
  // right now — so you can see the ball being lifted, driven or bent before you
  // let go, rather than guessing.
  private drawPowerMeter(ctx: CanvasRenderingContext2D, info: HudInfo, w: number, h: number) {
    const barW = 300
    const bh = 60
    const x = w / 2 - barW / 2 - 16
    const y = h - bh - 16
    panel(ctx, x, y, barW + 32, bh, 12, 'rgba(6,10,17,0.86)')

    const isTouch = info.chargeType === 'touch'
    const c = clamp01(info.charge)

    ctx.textAlign = 'left'
    ctx.textBaseline = 'alphabetic'
    ctx.fillStyle = TEXT
    ctx.font = disp(900, 19)
    spaced(ctx, 2)
    ctx.fillText(isTouch ? 'TOUCH' : 'STRIKE', x + 16, y + 24)
    spaced(ctx, 0)

    // What the wrist is doing to it, right now.
    const tags: string[] = []
    if (info.loft > 0.12) tags.push('LIFTED')
    else if (info.loft < -0.12) tags.push('DRIVEN')
    if (Math.abs(info.spin) > 0.15) tags.push(info.spin > 0 ? 'CURVE →' : '← CURVE')
    ctx.textAlign = 'right'
    ctx.fillStyle = LIME
    ctx.font = disp(800, 14)
    spaced(ctx, 2)
    ctx.fillText(tags.join('  ·  '), x + barW + 16, y + 23)
    spaced(ctx, 0)

    // The bar, with a tick at each quarter so a half-power ball is a thing you
    // can aim for rather than a feeling.
    const bx = x + 16
    const by = y + 34
    ctx.fillStyle = 'rgba(255,255,255,0.12)'
    ctx.fillRect(bx, by, barW, 12)
    // Power ramps blue → amber → red as the strike gets heavier.
    ctx.fillStyle = isTouch ? '#59d8b0' : c > 0.8 ? '#ff5f5f' : c > 0.5 ? '#ffb03a' : '#6bb8ff'
    ctx.fillRect(bx, by, barW * c, 12)
    ctx.fillStyle = 'rgba(6,10,17,0.65)'
    for (const q of [0.25, 0.5, 0.75]) ctx.fillRect(bx + barW * q - 1, by, 2, 12)

    // Loft indicator: a marker riding above/below the bar with the flick.
    if (Math.abs(info.loft) > 0.05) {
      const ly = by + 6 - clamp01(Math.abs(info.loft)) * 15 * Math.sign(info.loft)
      ctx.fillStyle = info.loft > 0 ? '#ffe28a' : '#8ad0ff'
      ctx.beginPath()
      ctx.arc(bx + barW * c, ly, 4, 0, Math.PI * 2)
      ctx.fill()
    }
  }

  // ---- the ball, when you cannot see it ------------------------------------------

  // A chevron on the edge of the screen toward the ball, with how far it is.
  // In third person the ball spends a good part of the match behind your own
  // shoulder or off to the side, and finding it is not a skill this game is
  // about.
  private drawBallPointer(ctx: CanvasRenderingContext2D, b: NonNullable<HudInfo['ball']>, w: number, h: number) {
    const m = 46
    let x = Math.min(w - m, Math.max(m, b.x))
    // Keep clear of the bottom row (stamina, power) and the minimap, which is
    // where a ball behind you would otherwise be pointed at.
    let y = Math.min(h - 116, Math.max(m + 48, b.y))
    if (x > w - 250 && y > h - 200) y = h - 200
    x = Math.min(x, w - m)
    const ang = Math.atan2(b.y - y, b.x - x)
    ctx.save()
    ctx.translate(x, y)
    ctx.fillStyle = 'rgba(6,10,17,0.72)'
    ctx.beginPath()
    ctx.arc(0, 0, 21, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = LIME
    ctx.lineWidth = 2
    ctx.stroke()
    ctx.rotate(ang)
    ctx.fillStyle = LIME
    ctx.beginPath()
    ctx.moveTo(15, 0)
    ctx.lineTo(4, -8)
    ctx.lineTo(4, 8)
    ctx.closePath()
    ctx.fill()
    ctx.restore()
    ctx.textAlign = 'center'
    ctx.textBaseline = 'alphabetic'
    ctx.fillStyle = TEXT
    ctx.font = disp(800, 13)
    ctx.fillText(`${Math.round(b.metres)} m`, x, y + 36)
  }

  // ---- the tutorial's voice ------------------------------------------------------

  private drawCoach(ctx: CanvasRenderingContext2D, c: NonNullable<HudInfo['coach']>, w: number) {
    const bw = Math.min(480, w - 32)
    const x = w / 2 - bw / 2
    const y = 74
    ctx.font = ui(500, 14.5)
    const lines = wrap(ctx, c.text, bw - 40)
    const bh = 64 + lines.length * 19
    panel(ctx, x, y, bw, bh, 12, 'rgba(6,10,17,0.88)')
    ctx.fillStyle = LIME
    ctx.fillRect(x, y, 5, bh)
    ctx.textAlign = 'left'
    ctx.textBaseline = 'alphabetic'
    ctx.font = disp(700, 12)
    spaced(ctx, 2.6)
    ctx.fillStyle = LIME
    ctx.fillText(`STEP ${Math.min(c.done + 1, c.total)} OF ${c.total}`, x + 20, y + 22)
    spaced(ctx, 0)
    ctx.fillStyle = TEXT
    ctx.font = disp(900, 27)
    ctx.fillText(c.title.toUpperCase(), x + 20, y + 50)
    ctx.fillStyle = '#c5d0e0'
    ctx.font = ui(500, 14.5)
    lines.forEach((ln, i) => ctx.fillText(ln, x + 20, y + 72 + i * 19))
    // Progress: a pip per step.
    for (let i = 0; i < c.total; i++) {
      ctx.fillStyle = i < c.done ? LIME : 'rgba(255,255,255,0.18)'
      ctx.fillRect(x + bw - 18 - (c.total - i) * 12, y + 14, 8, 4)
    }
  }

  // ---- drills --------------------------------------------------------------------

  // The drill panel. A drill you can't see yourself getting better at isn't
  // teaching you anything, so this shows the score, the streak, what the drill
  // is asking of you, and — the important part — a verdict on the touch you
  // just played.
  private drawDrills(ctx: CanvasRenderingContext2D, world: World, w: number) {
    const d = world.drills
    if (!d) return
    const info = DRILL_INFO[d.current]
    const s = d.score
    const best = d.best()
    const bw = 262
    const x = w - bw - 16
    const y = 44
    const bh = best ? 142 : 116
    panel(ctx, x, y, bw, bh, 12)
    ctx.fillStyle = LIME
    ctx.fillRect(x, y, 4, bh)

    ctx.textAlign = 'left'
    ctx.textBaseline = 'alphabetic'
    ctx.fillStyle = TEXT
    ctx.font = disp(800, 15)
    spaced(ctx, 2.2)
    ctx.fillText(info.name.toUpperCase(), x + 18, y + 22)
    spaced(ctx, 0)
    ctx.textAlign = 'right'
    ctx.fillStyle = DIM
    ctx.font = disp(700, 11)
    spaced(ctx, 1.6)
    ctx.fillText('N · NEXT DRILL', x + bw - 14, y + 22)
    spaced(ctx, 0)

    ctx.textAlign = 'left'
    ctx.fillStyle = TEXT
    ctx.font = disp(900, 34)
    ctx.fillText(`${s.points}`, x + 18, y + 58)
    const pw = ctx.measureText(`${s.points}`).width
    ctx.fillStyle = MUTED
    ctx.font = ui(600, 12)
    ctx.fillText(`pts · ${s.scored}/${s.attempts}`, x + 24 + pw, y + 58)

    ctx.textAlign = 'right'
    ctx.fillStyle = s.streak > 0 ? LIME : MUTED
    ctx.font = disp(900, 26)
    ctx.fillText(`${s.streak}`, x + bw - 14, y + 54)
    ctx.fillStyle = DIM
    ctx.font = disp(700, 10)
    spaced(ctx, 1.4)
    ctx.fillText(`STREAK · BEST ${s.best}`, x + bw - 14, y + 68)
    spaced(ctx, 0)

    // What the drill wants, or what you just did about it.
    ctx.textAlign = 'left'
    const v = d.verdict
    if (v) {
      const fade = clamp01(1 - (v.t - 1.6) / 0.8)
      ctx.fillStyle = v.good ? `rgba(196,255,69,${fade})` : `rgba(255,140,120,${fade})`
      ctx.font = disp(800, 17)
      ctx.fillText(v.text.toUpperCase(), x + 18, y + 88)
      ctx.fillStyle = `rgba(197,208,224,${fade * 0.9})`
      ctx.font = ui(500, 12)
      ctx.fillText(v.sub, x + 18, y + 106, bw - 36)
    } else {
      ctx.fillStyle = '#a9b6ca'
      ctx.font = ui(500, 12)
      ctx.fillText(info.brief, x + 18, y + 90, bw - 36)
      if (d.servesBalls() && !d.serveLive) {
        ctx.fillStyle = DIM
        ctx.fillText(`next ball in ${Math.max(0, d.serveTimer).toFixed(1)}s`, x + 18, y + 108)
      }
    }

    // The number you came back to beat. It survives a refresh, which is the
    // only thing that makes a scored drill worth doing twice.
    if (best) {
      ctx.fillStyle = 'rgba(255,255,255,0.08)'
      ctx.fillRect(x + 18, y + bh - 24, bw - 36, 1)
      ctx.textAlign = 'left'
      if (d.newBest > 0) {
        ctx.fillStyle = '#ffd35a'
        ctx.font = disp(800, 13)
        spaced(ctx, 2)
        ctx.fillText('NEW BEST', x + 18, y + bh - 8)
        spaced(ctx, 0)
      } else {
        ctx.fillStyle = MUTED
        ctx.font = disp(700, 12)
        spaced(ctx, 1.6)
        ctx.fillText(`BEST ${best.score} PTS`, x + 18, y + bh - 8)
        spaced(ctx, 0)
      }
      ctx.textAlign = 'right'
      ctx.fillStyle = DIM
      ctx.font = ui(600, 11)
      ctx.fillText(best.detail, x + bw - 14, y + bh - 8)
      ctx.textAlign = 'left'
    }
  }

  // ---- minimap -------------------------------------------------------------------

  private drawMinimap(ctx: CanvasRenderingContext2D, world: World, w: number, h: number) {
    const mw = MINIMAP_W
    const mh = mw * (FIELD.width / FIELD.length)
    const x = w - mw - 16
    const y = h - mh - 16
    // The pitch, striped as the real one is.
    ctx.fillStyle = 'rgba(6,10,17,0.86)'
    ctx.fillRect(x - 6, y - 6, mw + 12, mh + 12)
    ctx.strokeStyle = LINE
    ctx.strokeRect(x - 5.5, y - 5.5, mw + 11, mh + 11)
    const stripes = 8
    for (let i = 0; i < stripes; i++) {
      ctx.fillStyle = i % 2 ? 'rgba(46,120,64,0.9)' : 'rgba(40,108,58,0.9)'
      ctx.fillRect(x + (mw / stripes) * i, y, mw / stripes + 0.5, mh)
    }
    const sx = mw / FIELD.length
    const sy = mh / FIELD.width
    ctx.strokeStyle = 'rgba(255,255,255,0.55)'
    ctx.lineWidth = 1
    ctx.strokeRect(x + 0.5, y + 0.5, mw - 1, mh - 1)
    ctx.beginPath()
    ctx.moveTo(x + mw / 2, y)
    ctx.lineTo(x + mw / 2, y + mh)
    ctx.stroke()
    ctx.beginPath()
    ctx.arc(x + mw / 2, y + mh / 2, FIELD.centerRadius * sx, 0, Math.PI * 2)
    ctx.stroke()
    const bx0 = (FIELD.width - FIELD.boxWidth) / 2
    ctx.strokeRect(x + 0.5, y + bx0 * sy, FIELD.boxDepth * sx, FIELD.boxWidth * sy)
    ctx.strokeRect(x + mw - FIELD.boxDepth * sx - 0.5, y + bx0 * sy, FIELD.boxDepth * sx, FIELD.boxWidth * sy)
    // Goals.
    const gy = (FIELD.width - FIELD.goalWidth) / 2
    ctx.fillStyle = 'rgba(255,255,255,0.9)'
    ctx.fillRect(x - 3, y + gy * sy, 3, FIELD.goalWidth * sy)
    ctx.fillRect(x + mw, y + gy * sy, 3, FIELD.goalWidth * sy)

    const me = world.getControlledPlayer()
    for (const p of world.players) {
      const px = x + p.x * sx
      const py = y + p.y * sy
      ctx.fillStyle = p.role === 'GK' ? KITS[p.team].gk : KITS[p.team].primary
      ctx.beginPath()
      ctx.arc(px, py, p === me ? 3.6 : 2.8, 0, Math.PI * 2)
      ctx.fill()
      if (p === me) {
        // You: ringed, with the way you are facing.
        ctx.strokeStyle = '#ffffff'
        ctx.lineWidth = 1.5
        ctx.stroke()
        ctx.beginPath()
        ctx.moveTo(px, py)
        ctx.lineTo(px + Math.cos(p.heading) * 9, py + Math.sin(p.heading) * 9)
        ctx.stroke()
      }
    }
    // The ball: white, outlined so it survives the stripes, and drawn last.
    const bx = x + world.ball.x * sx
    const by = y + world.ball.y * sy
    ctx.fillStyle = '#ffffff'
    ctx.beginPath()
    ctx.arc(bx, by, 3, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = '#05080e'
    ctx.lineWidth = 1.2
    ctx.stroke()
  }

  // ---- announcements ---------------------------------------------------------------

  private drawAnnounce(ctx: CanvasRenderingContext2D, world: World, w: number, h: number) {
    const a = world.announce
    if (!a) return
    const t = a.t / a.life
    const alpha = t < 0.12 ? t / 0.12 : t > 0.8 ? (1 - t) / 0.2 : 1
    const big = a.text === 'GOAL!'
    // It lands: a little larger, settling in the first moments of its life.
    const pop = 1 + Math.max(0, 0.16 - t) * 3.4
    const cy = h * 0.34
    ctx.save()
    ctx.globalAlpha = clamp01(alpha)
    ctx.translate(w / 2, cy)
    ctx.scale(pop, pop)
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    const size = big ? Math.min(170, w * 0.19) : 58
    const text = (big ? 'GOAL' : a.text).toUpperCase()
    ctx.font = disp(900, size)
    spaced(ctx, big ? 6 : 3)
    // A band of light behind it, so it reads over any part of the pitch.
    const tw = ctx.measureText(text).width
    const g = ctx.createLinearGradient(-tw / 2 - 120, 0, tw / 2 + 120, 0)
    g.addColorStop(0, 'rgba(6,10,17,0)')
    g.addColorStop(0.2, 'rgba(6,10,17,0.62)')
    g.addColorStop(0.8, 'rgba(6,10,17,0.62)')
    g.addColorStop(1, 'rgba(6,10,17,0)')
    ctx.fillStyle = g
    ctx.fillRect(-tw / 2 - 120, -size * 0.55, tw + 240, size * 1.1 + (a.sub ? size * 0.34 : 0))
    ctx.fillStyle = big ? '#ffd35a' : '#ffffff'
    ctx.shadowColor = big ? 'rgba(255,190,40,0.55)' : 'rgba(0,0,0,0.6)'
    ctx.shadowBlur = big ? 24 : 10
    ctx.fillText(text, 0, 0)
    ctx.shadowBlur = 0
    spaced(ctx, 0)
    if (a.sub) {
      ctx.font = ui(600, 19)
      ctx.fillStyle = '#dfe7f5'
      ctx.fillText(a.sub, 0, size * 0.66)
    }
    ctx.restore()
  }

  // ---- the corner ----------------------------------------------------------------

  private drawZoomFps(ctx: CanvasRenderingContext2D, info: HudInfo, w: number) {
    const fps = store.get('showFps') ? ` · ${Math.round(info.fps)} FPS` : ''
    ctx.textAlign = 'right'
    ctx.textBaseline = 'top'
    ctx.font = disp(700, 12)
    spaced(ctx, 1.8)
    const text = `${info.zoomLabel.toUpperCase()}${fps}`
    // Outlined, because the corner of the screen is the sky in one view and the
    // stands in another and no single colour is legible over both.
    ctx.lineWidth = 3
    ctx.strokeStyle = 'rgba(4,7,12,0.85)'
    ctx.lineJoin = 'round'
    ctx.strokeText(text, w - 16, 16)
    ctx.fillStyle = 'rgba(214,224,238,0.92)'
    ctx.fillText(text, w - 16, 16)
    spaced(ctx, 0)
  }

  // ---- connection ----------------------------------------------------------------

  // Connection state, bottom-right above the minimap. A number nobody looks at
  // until the game feels wrong, and then the first thing they look for.
  private drawNet(ctx: CanvasRenderingContext2D, net: NetInfo, w: number, h: number) {
    const rows: [string, string, string][] = []
    const grade = (ms: number) => (ms < 60 ? '#8ef58a' : ms < 130 ? '#ffd85e' : '#ff8a7a')

    if (net.peers) {
      for (const p of net.peers) {
        rows.push([
          p.spectator ? `${p.name} (watching)` : p.name,
          p.away ? 'dropped' : `${p.ping}ms`,
          p.away ? '#ff8a7a' : grade(p.ping),
        ])
      }
      if (!rows.length) rows.push(['waiting for players', '', MUTED])
    } else {
      rows.push(['ping', `${net.ping}ms`, grade(net.ping)])
    }

    const pad = 10
    const lh = 17
    const boxW = MINIMAP_W + 12
    const boxH = pad * 2 + rows.length * lh + 14
    const x = w - boxW - 10
    // Sit above the minimap. Its height is derived from the pitch's aspect, so
    // it is taken from the same expression rather than guessed at — a constant
    // here would overlap the moment the pitch or the map changed shape.
    const y = h - MINIMAP_W * (FIELD.width / FIELD.length) - 16 - 6 - boxH - 10

    panel(ctx, x, y, boxW, boxH, 8)

    ctx.textBaseline = 'middle'
    ctx.textAlign = 'left'
    ctx.font = disp(700, 11)
    ctx.fillStyle = LIME
    spaced(ctx, 2.4)
    ctx.fillText(net.role === 'host' ? 'HOSTING' : net.spectating ? 'SPECTATING' : 'CONNECTED', x + pad, y + pad + 4)
    spaced(ctx, 0)

    ctx.font = ui(600, 12)
    rows.forEach(([label, value, colour], i) => {
      const ry = y + pad + 20 + i * lh + 5
      ctx.fillStyle = '#dbe6f5'
      ctx.textAlign = 'left'
      ctx.fillText(label.length > 18 ? `${label.slice(0, 17)}…` : label, x + pad, ry)
      ctx.fillStyle = colour
      ctx.textAlign = 'right'
      ctx.fillText(value, x + boxW - pad, ry)
    })
    ctx.textAlign = 'left'

    // A drop is loud, because you need to know the last few seconds of play
    // were yours alone and are about to be overwritten.
    if (net.reconnecting) {
      const bw = 300
      const bx = w / 2 - bw / 2
      const by = h * 0.18
      panel(ctx, bx, by, bw, 38, 10, 'rgba(96,20,20,0.9)')
      ctx.fillStyle = '#ffd0c8'
      ctx.font = disp(800, 15)
      ctx.textAlign = 'center'
      spaced(ctx, 1.4)
      ctx.fillText('RECONNECTING — YOUR SHIRT IS BEING HELD', w / 2, by + 20)
      spaced(ctx, 0)
      ctx.textAlign = 'left'
    }
  }
}

// Break text into lines that fit. Canvas text does not wrap itself, and
// squeezing a sentence into a width makes it unreadably small.
function wrap(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const out: string[] = []
  let line = ''
  for (const word of text.split(/\s+/)) {
    const next = line ? `${line} ${word}` : word
    if (line && ctx.measureText(next).width > maxWidth) {
      out.push(line)
      line = word
    } else {
      line = next
    }
  }
  if (line) out.push(line)
  return out
}

function fmtClock(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
