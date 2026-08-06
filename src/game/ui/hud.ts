import { BALL, FIELD, KITS, PLAYER } from '../config'
import { clamp01 } from '../core/math'
import type { World } from '../match/world'

export interface HudInfo {
  chargeType: 'touch' | 'strike' | null
  charge: number // 0..1
  loft: number // −1..1, live from the mouse flick
  spin: number // signed, live from the mouse flick
  fps: number
  mode: 'match' | 'training'
  zoomLabel: string
}

// On-screen furniture drawn on top of the rendered pitch. Reads world state only.
export class Hud {
  draw(ctx: CanvasRenderingContext2D, world: World, info: HudInfo, w: number, h: number) {
    this.drawScoreboard(ctx, world, w)
    this.drawStamina(ctx, world, h)
    this.drawContactCue(ctx, world, w, h)
    if (info.chargeType) this.drawPowerMeter(ctx, info, w, h)
    this.drawDrills(ctx, world, w)
    this.drawMinimap(ctx, world, w, h)
    this.drawAnnounce(ctx, world, w, h)
    this.drawZoomFps(ctx, info, w)
  }

  private drawScoreboard(ctx: CanvasRenderingContext2D, world: World, w: number) {
    const cx = w / 2
    const boxW = 250
    const boxH = 46
    const x = cx - boxW / 2
    const y = 12
    roundRect(ctx, x, y, boxW, boxH, 8)
    ctx.fillStyle = 'rgba(10,16,28,0.82)'
    ctx.fill()
    ctx.strokeStyle = 'rgba(255,255,255,0.12)'
    ctx.lineWidth = 1
    ctx.stroke()

    // Team chips.
    ctx.fillStyle = KITS.home.primary
    roundRect(ctx, x + 8, y + 9, 12, 28, 3)
    ctx.fill()
    ctx.fillStyle = KITS.away.primary
    roundRect(ctx, x + boxW - 20, y + 9, 12, 28, 3)
    ctx.fill()

    ctx.fillStyle = '#eef3ff'
    ctx.textBaseline = 'middle'
    ctx.textAlign = 'left'
    ctx.font = '600 13px system-ui, sans-serif'
    ctx.fillText('HOME', x + 26, y + 16)
    ctx.textAlign = 'right'
    ctx.fillText('AWAY', x + boxW - 26, y + 16)

    ctx.textAlign = 'center'
    ctx.font = '700 22px system-ui, sans-serif'
    ctx.fillText(`${world.score.home} – ${world.score.away}`, cx, y + 17)

    // Clock / phase.
    ctx.font = '600 12px ui-monospace, monospace'
    ctx.fillStyle = '#9fb2d0'
    const label =
      world.config.mode === 'training'
        ? 'TRAINING'
        : world.phase === 'fulltime'
          ? 'FULL TIME'
          : `${world.half === 1 ? '1st' : '2nd'}  ${fmtClock(world.clock)}`
    ctx.fillText(label, cx, y + 36)
  }

  private drawStamina(ctx: CanvasRenderingContext2D, world: World, h: number) {
    const p = world.getControlledPlayer()
    if (!p) return
    const x = 16
    const y = h - 44
    const barW = 190
    ctx.fillStyle = 'rgba(10,16,28,0.7)'
    roundRect(ctx, x - 6, y - 20, barW + 12, 38, 8)
    ctx.fill()

    ctx.fillStyle = '#9fb2d0'
    ctx.font = '600 11px system-ui, sans-serif'
    ctx.textAlign = 'left'
    ctx.textBaseline = 'alphabetic'
    ctx.fillText(`#${p.number} STAMINA`, x, y - 6)

    ctx.fillStyle = 'rgba(255,255,255,0.12)'
    roundRect(ctx, x, y, barW, 10, 5)
    ctx.fill()
    const frac = p.energy
    ctx.fillStyle = frac > 0.35 ? '#57d764' : '#f5a742'
    roundRect(ctx, x, y, barW * frac, 10, 5)
    ctx.fill()
  }

  // When the ball is dropping into your area, say what a click would do with it
  // right now. Playing a ball out of the air is a snap decision and the height
  // is hard to read off a 3D scene, so this is the one piece of coaching the HUD
  // offers — it tells you what's available, never when to take it.
  private drawContactCue(ctx: CanvasRenderingContext2D, world: World, w: number, h: number) {
    const p = world.getControlledPlayer()
    if (!p) return
    const b = world.ball

    // Shielding is a mode you're holding, and its whole cost is invisible from
    // behind the player, so say so.
    if (p.shielding) {
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.font = '600 11px system-ui, sans-serif'
      const text = 'SHIELDING  ·  no strike'
      const bw = ctx.measureText(text).width + 30
      ctx.fillStyle = 'rgba(10,16,28,0.72)'
      roundRect(ctx, w / 2 - bw / 2, h - 104, bw, 24, 12)
      ctx.fill()
      ctx.fillStyle = 'rgba(140,220,255,0.95)'
      ctx.fillText(text, w / 2, h - 92)
      return
    }
    if (b.z <= PLAYER.controlHeight) return
    const d = Math.hypot(b.x - p.x, b.y - p.y)
    if (d > p.radius + BALL.radius + PLAYER.reach * 1.25) return
    if (b.z > PLAYER.aerialReach) return

    const header = b.z > PLAYER.headerHeight
    const label = header ? 'HEADER' : 'VOLLEY'
    const y = h - 92
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.font = '600 11px system-ui, sans-serif'
    const text = `CUSHION  ·  ${label}`
    const bw = ctx.measureText(text).width + 30
    ctx.fillStyle = 'rgba(10,16,28,0.72)'
    roundRect(ctx, w / 2 - bw / 2, y - 12, bw, 24, 12)
    ctx.fill()
    // Fade in as the ball drops into range, so it reads as a window opening.
    const near = clamp01((PLAYER.aerialReach - b.z) / 0.7)
    ctx.fillStyle = `rgba(255,226,138,${0.45 + near * 0.55})`
    ctx.fillText(text, w / 2, y)
  }

  // The power bar, plus live readouts of the shaping the mouse flick is applying
  // right now — so you can see the ball being lifted, driven or bent before you
  // let go, rather than guessing.
  private drawPowerMeter(ctx: CanvasRenderingContext2D, info: HudInfo, w: number, h: number) {
    const barW = 240
    const x = w / 2 - barW / 2
    const y = h - 44
    ctx.fillStyle = 'rgba(10,16,28,0.78)'
    roundRect(ctx, x - 12, y - 26, barW + 24, 50, 9)
    ctx.fill()

    const isTouch = info.chargeType === 'touch'
    ctx.fillStyle = '#cdd8ec'
    ctx.font = '600 11px system-ui, sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'alphabetic'
    const shape =
      info.loft > 0.12 ? ' · LIFTED' : info.loft < -0.12 ? ' · DRIVEN' : ''
    const bend = Math.abs(info.spin) > 0.15 ? (info.spin > 0 ? ' · CURVE →' : ' · CURVE ←') : ''
    ctx.fillText(`${isTouch ? 'TOUCH' : 'STRIKE'}${shape}${bend}`, w / 2, y - 12)

    ctx.fillStyle = 'rgba(255,255,255,0.12)'
    roundRect(ctx, x, y, barW, 11, 5)
    ctx.fill()
    const c = clamp01(info.charge)
    // Power ramps blue → amber → red as the strike gets heavier.
    const col = isTouch ? '#59d8b0' : c > 0.8 ? '#ff5f5f' : c > 0.5 ? '#ffb03a' : '#6bb8ff'
    ctx.fillStyle = col
    roundRect(ctx, x, y, barW * c, 11, 5)
    ctx.fill()

    // Loft indicator: a marker riding above/below the bar with the flick.
    if (Math.abs(info.loft) > 0.05) {
      const ly = y + 5.5 - clamp01(Math.abs(info.loft)) * 13 * Math.sign(info.loft)
      ctx.fillStyle = info.loft > 0 ? '#ffe28a' : '#8ad0ff'
      ctx.beginPath()
      ctx.arc(x + barW * c, ly, 3.5, 0, Math.PI * 2)
      ctx.fill()
    }
  }

  // Training scoreboard: how many of your strikes found a target.
  private drawDrills(ctx: CanvasRenderingContext2D, world: World, w: number) {
    const d = world.drills
    if (!d) return
    const bw = 190
    const x = w - bw - 16
    const y = 44
    ctx.fillStyle = 'rgba(10,16,28,0.78)'
    roundRect(ctx, x, y, bw, 62, 9)
    ctx.fill()
    ctx.strokeStyle = 'rgba(255,255,255,0.1)'
    ctx.lineWidth = 1
    ctx.stroke()

    ctx.textAlign = 'left'
    ctx.textBaseline = 'alphabetic'
    ctx.fillStyle = '#9fb2d0'
    ctx.font = '600 10px system-ui, sans-serif'
    ctx.fillText('TARGET PRACTICE', x + 12, y + 17)

    ctx.fillStyle = '#eef3ff'
    ctx.font = '700 20px system-ui, sans-serif'
    ctx.fillText(`${d.hits}`, x + 12, y + 42)
    ctx.fillStyle = '#8fa1bb'
    ctx.font = '600 12px system-ui, sans-serif'
    ctx.fillText(`/ ${d.shots} shots`, x + 12 + ctx.measureText(`${d.hits}`).width + 12, y + 42)

    ctx.textAlign = 'right'
    ctx.fillStyle = d.streak > 0 ? '#57d764' : '#8fa1bb'
    ctx.font = '700 15px system-ui, sans-serif'
    ctx.fillText(`${d.streak}`, x + bw - 12, y + 42)
    ctx.fillStyle = '#7d8ca3'
    ctx.font = '600 9px system-ui, sans-serif'
    ctx.fillText(`STREAK · BEST ${d.best}`, x + bw - 12, y + 54)
  }

  private drawMinimap(ctx: CanvasRenderingContext2D, world: World, w: number, h: number) {
    const mw = 168
    const mh = mw * (FIELD.width / FIELD.length)
    const x = w - mw - 16
    const y = h - mh - 16
    ctx.fillStyle = 'rgba(8,22,14,0.82)'
    roundRect(ctx, x - 6, y - 6, mw + 12, mh + 12, 8)
    ctx.fill()
    ctx.strokeStyle = 'rgba(255,255,255,0.18)'
    ctx.lineWidth = 1
    ctx.strokeRect(x, y, mw, mh)
    ctx.beginPath()
    ctx.moveTo(x + mw / 2, y)
    ctx.lineTo(x + mw / 2, y + mh)
    ctx.stroke()

    const sx = mw / FIELD.length
    const sy = mh / FIELD.width
    for (const p of world.players) {
      ctx.fillStyle = p.role === 'GK' ? KITS[p.team].gk : KITS[p.team].primary
      ctx.beginPath()
      ctx.arc(x + p.x * sx, y + p.y * sy, 2.6, 0, Math.PI * 2)
      ctx.fill()
    }
    ctx.fillStyle = '#ffffff'
    ctx.beginPath()
    ctx.arc(x + world.ball.x * sx, y + world.ball.y * sy, 2.2, 0, Math.PI * 2)
    ctx.fill()
  }

  private drawAnnounce(ctx: CanvasRenderingContext2D, world: World, w: number, h: number) {
    const a = world.announce
    if (!a) return
    const t = a.t / a.life
    const alpha = t < 0.15 ? t / 0.15 : t > 0.8 ? (1 - t) / 0.2 : 1
    const big = a.text === 'GOAL!'
    ctx.save()
    ctx.globalAlpha = clamp01(alpha)
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    const size = big ? Math.min(120, w * 0.14) : 40
    ctx.font = `800 ${size}px system-ui, sans-serif`
    ctx.fillStyle = big ? '#ffd84d' : '#ffffff'
    ctx.shadowColor = 'rgba(0,0,0,0.6)'
    ctx.shadowBlur = 12
    const cy = h * 0.36
    ctx.fillText(a.text, w / 2, cy)
    if (a.sub) {
      ctx.shadowBlur = 6
      ctx.font = '600 18px system-ui, sans-serif'
      ctx.fillStyle = '#dfe7f5'
      ctx.fillText(a.sub, w / 2, cy + size * 0.7)
    }
    ctx.restore()
  }

  private drawZoomFps(ctx: CanvasRenderingContext2D, info: HudInfo, w: number) {
    ctx.fillStyle = 'rgba(159,178,208,0.7)'
    ctx.font = '600 11px ui-monospace, monospace'
    ctx.textAlign = 'right'
    ctx.textBaseline = 'top'
    ctx.fillText(`${info.zoomLabel.toUpperCase()} · ${Math.round(info.fps)} FPS`, w - 16, 16)
  }
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  const rr = Math.min(r, w / 2, h / 2)
  ctx.beginPath()
  ctx.moveTo(x + rr, y)
  ctx.arcTo(x + w, y, x + w, y + h, rr)
  ctx.arcTo(x + w, y + h, x, y + h, rr)
  ctx.arcTo(x, y + h, x, y, rr)
  ctx.arcTo(x, y, x + w, y, rr)
  ctx.closePath()
}

function fmtClock(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
