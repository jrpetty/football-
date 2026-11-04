// Canvas renderer. Terrain is painted per pixel into 32×32-tile chunks (soft
// bilinear blending, hill-shading, coastal foam, seasonal colour and snow) with
// trees and rocks baked in; chunks are cached and repainted only when touched.
// Buildings, wildlife and people are drawn live on top, then a lightmap for
// night, glowing windows and fires, and weather.

import { B, BIOMES, F, FEATURES } from '../sim/data.ts'
import { BUILDINGS } from '../sim/buildings.ts'
import { eraOf } from '../sim/tech.ts'
import { daylight, hourOf } from '../sim/time.ts'
import { hash2, clamp, smoothstep, Noise2D } from '../sim/rng.ts'
import type { World } from '../sim/world.ts'
import type { Agent, Building } from '../sim/types.ts'
import { featureSprite, buildingSprite, SPR } from './sprites.ts'
import { drawPerson, drawCreature } from './people.ts'

export const TP = 16 // screen px per tile at zoom 1
const CHUNK = 32

export type Overlay = 'none' | 'territory' | 'fertility' | 'temperature' | 'explored'

export interface Selection {
  kind: 'agent' | 'building' | 'tile' | 'tribe' | null
  id: number
}

interface Chunk {
  canvas: HTMLCanvasElement
  stamp: number
}

const warpNoise = new Noise2D(9001)
const edgeNoise = new Noise2D(4242)


export class Renderer {
  canvas: HTMLCanvasElement
  ctx: CanvasRenderingContext2D
  world: World
  cam = { x: 0, y: 0, zoom: 2.3 }
  target: { x: number; y: number; zoom: number } | null = null
  dpr = 1
  vw = 0
  vh = 0
  selection: Selection = { kind: null, id: 0 }
  hover: { x: number; y: number } | null = null
  follow = false
  /** title-screen mode: the camera drifts slowly over the living world */
  cinematic = false
  private drift = Math.random() * 1000
  overlay: Overlay = 'territory'
  showNames = true
  private chunks = new Map<number, Chunk>()
  private chunkSeason = -1
  private terr: HTMLCanvasElement | null = null
  private terrT = 0
  private light: HTMLCanvasElement
  private lctx: CanvasRenderingContext2D
  private particles: { x: number; y: number; vx: number; vy: number; life: number; kind: number }[] = []
  private rain: { x: number; y: number; s: number }[] = []
  private clouds: { x: number; y: number; r: number; s: number }[] = []
  private frame = 0
  private lastT = 0
  private minimapBase: ImageData | null = null
  private minimapSeason = -1

  constructor(canvas: HTMLCanvasElement, world: World) {
    this.canvas = canvas
    this.ctx = canvas.getContext('2d', { alpha: false })!
    this.world = world
    this.light = document.createElement('canvas')
    this.lctx = this.light.getContext('2d')!
    const t = world.tribes[0]
    this.cam.x = t ? t.center.x + 0.5 : world.W / 2
    this.cam.y = t ? t.center.y + 0.5 : world.H / 2
    for (let k = 0; k < 14; k++) this.clouds.push({ x: Math.random() * world.W, y: Math.random() * world.H, r: 8 + Math.random() * 16, s: 0.4 + Math.random() * 0.6 })
    for (let k = 0; k < 420; k++) this.rain.push({ x: Math.random(), y: Math.random(), s: 0.6 + Math.random() * 0.8 })
  }

  setWorld(w: World) {
    this.world = w
    this.chunks.clear()
    this.terr = null
    this.minimapBase = null
    this.selection = { kind: null, id: 0 }
    const t = w.tribes.find((x) => x.alive)
    this.cam.x = t ? t.center.x + 0.5 : w.W / 2
    this.cam.y = t ? t.center.y + 0.5 : w.H / 2
  }

  resize(w: number, h: number, dpr: number) {
    this.dpr = dpr
    this.vw = w
    this.vh = h
    this.canvas.width = Math.round(w * dpr)
    this.canvas.height = Math.round(h * dpr)
    this.light.width = Math.ceil((w * dpr) / 2)
    this.light.height = Math.ceil((h * dpr) / 2)
  }

  // ---------------------------------------------------------------- coordinates

  get scale(): number {
    return TP * this.cam.zoom
  }
  screenToWorld(sx: number, sy: number): { x: number; y: number } {
    return { x: this.cam.x + (sx - this.vw / 2) / this.scale, y: this.cam.y + (sy - this.vh / 2) / this.scale }
  }
  worldToScreen(x: number, y: number): { x: number; y: number } {
    return { x: (x - this.cam.x) * this.scale + this.vw / 2, y: (y - this.cam.y) * this.scale + this.vh / 2 }
  }
  zoomAt(sx: number, sy: number, factor: number) {
    const before = this.screenToWorld(sx, sy)
    this.cam.zoom = clamp(this.cam.zoom * factor, 0.3, 4.5)
    const after = this.screenToWorld(sx, sy)
    this.cam.x += before.x - after.x
    this.cam.y += before.y - after.y
    this.target = null
  }
  pan(dx: number, dy: number) {
    this.cam.x -= dx / this.scale
    this.cam.y -= dy / this.scale
    this.follow = false
    this.target = null
    this.clampCam()
  }
  flyTo(x: number, y: number, zoom?: number) {
    this.target = { x, y, zoom: zoom ?? Math.max(this.cam.zoom, 2.4) }
  }
  private clampCam() {
    this.cam.x = clamp(this.cam.x, 0, this.world.W)
    this.cam.y = clamp(this.cam.y, 0, this.world.H)
  }

  pick(sx: number, sy: number): Selection {
    const p = this.screenToWorld(sx, sy)
    const w = this.world
    let best: Agent | null = null
    let bd = 0.9
    for (const a of w.agents) {
      if (!a.alive) continue
      const d = Math.hypot(a.x - p.x, a.y - 0.25 - p.y)
      if (d < bd) { bd = d; best = a }
    }
    if (best) return { kind: 'agent', id: best.id }
    const tx = Math.floor(p.x), ty = Math.floor(p.y)
    if (!w.inBounds(tx, ty)) return { kind: null, id: 0 }
    const o = w.occ[w.idx(tx, ty)]
    if (o) return { kind: 'building', id: o - 1 }
    return { kind: 'tile', id: w.idx(tx, ty) }
  }

  // ---------------------------------------------------------------- terrain

  private tileColor(i: number, season: number): [number, number, number, number] {
    const w = this.world
    const b = w.biome[i]
    const bd = BIOMES[b]
    let [r, g, bl] = bd.color
    const v = (hash2(i % w.W, (i / w.W) | 0, 7) - 0.5) * 10
    r += v; g += v; bl += v
    let snow = 0
    if (!bd.water) {
      const m = w.moist[i]
      if (b === B.GRASSLAND || b === B.SAVANNA) { g += (m - 0.5) * 30; r -= (m - 0.5) * 18 }
      if (season === 0 && (b === B.GRASSLAND || b === B.FOREST)) { g += 10; r -= 4 }
      if (season === 1 && (b === B.GRASSLAND || b === B.SAVANNA)) { r += 10; g += 4; bl -= 6 }
      if (season === 2) {
        if (b === B.FOREST) { r = r * 0.45 + 196 * 0.55; g = g * 0.45 + 120 * 0.55; bl = bl * 0.5 + 40 * 0.5 }
        else if (b === B.GRASSLAND) { r += 26; g += 4; bl -= 10 }
      }
      const wt = w.temp[i] - (season === 3 ? 0.3 : season === 2 ? 0.08 : season === 0 ? 0.02 : -0.1)
      snow = clamp(((season === 3 ? 0.27 : 0.16) - wt) / 0.12, 0, 1)
      if (b === B.SNOW || b === B.PEAK) snow = 1
      if (b === B.MOUNTAIN) snow = Math.max(snow, smoothstep(0.82, 0.9, w.elev[i]))
      if (b === B.DESERT || b === B.BEACH) snow *= 0.4
    } else {
      const e = w.elev[i]
      if (b !== B.RIVER && b !== B.LAKE) {
        const depth = clamp(-e, 0, 1)
        r = 70 - depth * 55; g = 150 - depth * 100; bl = 190 - depth * 90
      }
      if (season === 3 && w.temp[i] < 0.3 && (b === B.LAKE || b === B.RIVER)) { r = 190; g = 214; bl = 230 }
    }
    return [r, g, bl, snow]
  }

  private renderChunk(cx: number, cy: number, live = false): HTMLCanvasElement {
    // close up, terrain is painted at double density so it stays crisp
    const tp = live ? TP * 2 : TP
    const w = this.world
    const season = w.season
    const PX = CHUNK * tp
    const c = document.createElement('canvas')
    c.width = PX
    c.height = PX
    const ctx = c.getContext('2d')!
    const M = CHUNK + 2
    const col = new Float32Array(M * M * 4)
    const shadeT = new Float32Array(M * M)
    const water = new Uint8Array(M * M)
    const x0 = cx * CHUNK - 1, y0 = cy * CHUNK - 1
    const el = (x: number, y: number) => {
      const xx = clamp(x, 0, w.W - 1), yy = clamp(y, 0, w.H - 1)
      return Math.max(0, w.elev[yy * w.W + xx])
    }
    for (let ty = 0; ty < M; ty++) for (let tx = 0; tx < M; tx++) {
      const gx = clamp(x0 + tx, 0, w.W - 1), gy = clamp(y0 + ty, 0, w.H - 1)
      const i = gy * w.W + gx
      const [r, g, b, s] = this.tileColor(i, season)
      const k = (ty * M + tx) * 4
      col[k] = r; col[k + 1] = g; col[k + 2] = b; col[k + 3] = s
      const isW = BIOMES[w.biome[i]].water
      water[ty * M + tx] = isW ? 1 : 0
      if (!isW) {
        const dx = el(gx + 1, gy) - el(gx - 1, gy)
        const dy = el(gx, gy + 1) - el(gx, gy - 1)
        shadeT[ty * M + tx] = clamp(1 - (dx + dy) * 2.6, 0.62, 1.32)
      } else shadeT[ty * M + tx] = 1
    }
    // Low-frequency domain warp + edge noise, sampled on a coarse grid in world
    // pixel space (continuous across chunks) and interpolated per pixel. This
    // turns the tile grid into organic biome borders and ragged coastlines.
    const G = 4, GN = PX / G + 1
    const warpX = new Float32Array(GN * GN), warpY = new Float32Array(GN * GN), edgeN = new Float32Array(GN * GN)
    for (let gy = 0; gy < GN; gy++) for (let gx = 0; gx < GN; gx++) {
      const wx = cx * PX + gx * G, wy = cy * PX + gy * G
      const k = gy * GN + gx
      warpX[k] = warpNoise.fbm(wx / 46, wy / 46, 3) * 0.6
      warpY[k] = warpNoise.fbm(wx / 46 + 91, wy / 46 + 37, 3) * 0.6
      edgeN[k] = edgeNoise.fbm(wx / 13, wy / 13, 2) * 0.24
    }
    const img = ctx.createImageData(PX, PX)
    const d = img.data
    const ww = [0, 0, 0, 0], wt = [0, 0, 0, 0], idx = [0, 0, 0, 0]
    for (let py = 0; py < PX; py++) {
      const gyf = py / G, gy0 = Math.min(GN - 2, Math.floor(gyf)), gfy = gyf - gy0
      for (let px = 0; px < PX; px++) {
        const gxf = px / G, gx0 = Math.min(GN - 2, Math.floor(gxf)), gfx = gxf - gx0
        const g00 = gy0 * GN + gx0, g10 = g00 + 1, g01 = g00 + GN, g11 = g01 + 1
        const b00 = (1 - gfx) * (1 - gfy), b10 = gfx * (1 - gfy), b01 = (1 - gfx) * gfy, b11 = gfx * gfy
        const dxw = warpX[g00] * b00 + warpX[g10] * b10 + warpX[g01] * b01 + warpX[g11] * b11
        const dyw = warpY[g00] * b00 + warpY[g10] * b10 + warpY[g01] * b01 + warpY[g11] * b11
        const en = edgeN[g00] * b00 + edgeN[g10] * b10 + edgeN[g01] * b01 + edgeN[g11] * b11
        const vx = clamp((px + 0.5) / tp + 0.5 + dxw, 0, M - 1.001)
        const vy = clamp((py + 0.5) / tp + 0.5 + dyw, 0, M - 1.001)
        const tx0 = Math.floor(vx), ty0 = Math.floor(vy)
        const fx = vx - tx0, fy = vy - ty0
        const a = ty0 * M + tx0
        idx[0] = a; idx[1] = a + 1; idx[2] = a + M; idx[3] = a + M + 1
        wt[0] = (1 - fx) * (1 - fy); wt[1] = fx * (1 - fy); wt[2] = (1 - fx) * fy; wt[3] = fx * fy
        let wWeight = 0
        for (let q = 0; q < 4; q++) { ww[q] = water[idx[q]]; wWeight += ww[q] * wt[q] }
        const mixed = wWeight > 0 && wWeight < 1
        const level = wWeight + (mixed ? en : 0)
        const isWater = mixed ? level > 0.5 : wWeight >= 0.5
        // blend only tiles of the same kind (water with water, land with land)
        let r = 0, g = 0, b = 0, snow = 0, sh = 0, tot = 0
        for (let q = 0; q < 4; q++) {
          if ((ww[q] === 1) !== isWater) continue
          const wq = wt[q], kq = idx[q] * 4
          r += col[kq] * wq; g += col[kq + 1] * wq; b += col[kq + 2] * wq; snow += col[kq + 3] * wq; sh += shadeT[idx[q]] * wq
          tot += wq
        }
        if (tot <= 0) { const kq = idx[0] * 4; r = col[kq]; g = col[kq + 1]; b = col[kq + 2]; snow = col[kq + 3]; sh = 1; tot = 1 }
        r /= tot; g /= tot; b /= tot; snow /= tot; sh /= tot
        const gxp = cx * PX + px, gyp = cy * PX + py
        const n = hash2(gxp, gyp, 3) - 0.5
        const edge = Math.abs(level - 0.5)
        if (isWater) {
          const ripple = Math.sin(gxp * 0.09 + Math.sin(gyp * 0.05) * 2) * Math.sin(gyp * 0.07) * 5
          r += ripple + n * 4; g += ripple + n * 4; b += ripple + n * 5
          if (mixed && edge < 0.08) { const f = (1 - edge / 0.08) * 0.65; r += (228 - r) * f; g += (238 - g) * f; b += (240 - b) * f }
          else if (mixed && edge < 0.2) { const f = (1 - edge / 0.2) * 0.25; r += (120 - r) * f; g += (200 - g) * f; b += (210 - b) * f }
        } else {
          r *= sh; g *= sh; b *= sh
          r += n * 9; g += n * 9; b += n * 7
          if (snow > 0.02) {
            const s2 = clamp(snow + n * 0.3, 0, 1)
            r += (236 - r) * s2; g += (242 - g) * s2; b += (250 - b) * s2
          }
          if (mixed && edge < 0.1) { const f = (1 - edge / 0.1) * 0.45; r += (206 - r) * f; g += (190 - g) * f; b += (148 - b) * f }
        }
        const o = (py * PX + px) * 4
        d[o] = r; d[o + 1] = g; d[o + 2] = b; d[o + 3] = 255
      }
    }
    ctx.putImageData(img, 0, 0)

    // roads
    for (let ty = -1; ty <= CHUNK; ty++) for (let tx = -1; tx <= CHUNK; tx++) {
      const gx = cx * CHUNK + tx, gy = cy * CHUNK + ty
      if (!w.inBounds(gx, gy)) continue
      const rv = w.road[gx + gy * w.W]
      if (!rv) continue
      const px = (tx + 0.5) * tp, py = (ty + 0.5) * tp
      ctx.strokeStyle = rv === 2 ? '#5b5650' : '#a8906a'
      ctx.lineWidth = rv === 2 ? 7 : 6
      ctx.lineCap = 'round'
      let any = false
      for (const [dx, dy] of [[1, 0], [0, 1], [1, 1], [1, -1]]) {
        const nx = gx + dx, ny = gy + dy
        if (w.inBounds(nx, ny) && w.road[nx + ny * w.W]) {
          ctx.beginPath(); ctx.moveTo(px, py); ctx.lineTo(px + dx * tp, py + dy * tp); ctx.stroke()
          any = true
        }
      }
      for (const [dx, dy] of [[-1, 0], [0, -1], [-1, -1], [-1, 1]]) {
        const nx = gx + dx, ny = gy + dy
        if (w.inBounds(nx, ny) && w.road[nx + ny * w.W]) any = true
      }
      if (!any) { ctx.beginPath(); ctx.arc(px, py, 3, 0, Math.PI * 2); ctx.fillStyle = ctx.strokeStyle; ctx.fill() }
    }

    // painted ground detail: grass tufts, pebbles, sand ripples, snow glints
    for (let ty = 0; ty < CHUNK; ty++) for (let tx = 0; tx < CHUNK; tx++) {
      const gx = cx * CHUNK + tx, gy = cy * CHUNK + ty
      if (!w.inBounds(gx, gy)) continue
      const i = gx + gy * w.W
      const b = w.biome[i]
      if (BIOMES[b].water || w.road[i] || (w.occ[i] && w.buildingById.get(w.occ[i] - 1)?.type !== 'mine')) continue
      const snowy = (season === 3 && w.temp[i] < 0.42) || b === B.SNOW || b === B.PEAK
      const n = live ? 7 : 4
      for (let k = 0; k < n; k++) {
        const hx = hash2(gx * 7 + k, gy * 13, 21), hy = hash2(gx * 11, gy * 5 + k, 22)
        const px = (tx + hx) * tp, py = (ty + hy) * tp
        if (snowy) {
          if (k < 2) { ctx.fillStyle = 'rgba(255,255,255,0.7)'; ctx.fillRect(px, py, tp * 0.06, tp * 0.06) }
          continue
        }
        if (b === B.DESERT || b === B.BEACH) {
          if (k < 2) { ctx.strokeStyle = 'rgba(160,120,70,0.25)'; ctx.lineWidth = tp * 0.04; ctx.beginPath(); ctx.moveTo(px, py); ctx.quadraticCurveTo(px + tp * 0.2, py - tp * 0.06, px + tp * 0.4, py); ctx.stroke() }
          continue
        }
        if (b === B.MOUNTAIN || b === B.HILLS || b === B.TUNDRA) {
          if (k < 3) { ctx.fillStyle = k % 2 ? 'rgba(70,64,58,0.35)' : 'rgba(230,226,215,0.3)'; ctx.beginPath(); ctx.ellipse(px, py, tp * 0.07, tp * 0.045, 0, 0, Math.PI * 2); ctx.fill() }
          if (b !== B.HILLS) continue
        }
        // a little tuft of grass, darker at the root, catching light at the tips
        const autumn = season === 2
        const base = b === B.SAVANNA ? [150, 140, 60] : b === B.TAIGA ? [40, 80, 60] : autumn ? [140, 130, 60] : [60, 120, 50]
        const len = tp * (0.12 + hx * 0.1)
        ctx.lineWidth = Math.max(1, tp * 0.035)
        for (let bl = -1; bl <= 1; bl++) {
          ctx.strokeStyle = `rgba(${base[0] + bl * 12},${base[1] + 20 + bl * 10},${base[2]},${bl === 0 ? 0.55 : 0.4})`
          ctx.beginPath(); ctx.moveTo(px + bl * tp * 0.03, py); ctx.quadraticCurveTo(px + bl * tp * 0.06, py - len * 0.6, px + bl * tp * 0.09 + hy * tp * 0.04, py - len); ctx.stroke()
        }
      }
    }

    // features (sorted by row so lower sprites overlap upper ones). Close up,
    // they are drawn live instead — crisp, swaying, and sorted with people.
    if (live) return c
    ctx.imageSmoothingEnabled = true
    for (let ty = -1; ty <= CHUNK + 1; ty++) for (let tx = -1; tx <= CHUNK; tx++) {
      const gx = cx * CHUNK + tx, gy = cy * CHUNK + ty
      if (!w.inBounds(gx, gy)) continue
      const i = gx + gy * w.W
      const f = w.feature[i]
      if (!f) continue
      if (w.occ[i]) {
        const bb = w.buildingById.get(w.occ[i] - 1)
        if (bb && bb.type !== 'mine') continue
      }
      const v = Math.floor(hash2(gx, gy, 11) * 4)
      const empty = (f === F.BUSH && w.amount[i] === 0) || (f === F.FIBER && w.amount[i] === 0)
      const s = featureSprite(f, v, season, empty)
      if (!s) continue
      const jx = (hash2(gx, gy, 5) - 0.5) * 5, jy = (hash2(gx, gy, 6) - 0.5) * 3
      const k = tp / SPR
      ctx.drawImage(s.img, tx * tp + s.ox * k + jx, ty * tp + s.oy * k + jy, (s.img.width / s.res) * k, (s.img.height / s.res) * k)
    }
    return c
  }

  private stale = new Set<number>()
  private getChunk(cx: number, cy: number, budget: { n: number }, live = false): HTMLCanvasElement | null {
    const base = (cy << 8) | cx
    if (this.world.dirtyChunks.has(base)) {
      this.world.dirtyChunks.delete(base)
      this.stale.add(base * 2)
      this.stale.add(base * 2 + 1)
    }
    const key = base * 2 + (live ? 1 : 0)
    const ch = this.chunks.get(key)
    if (ch && !this.stale.has(key)) { ch.stamp = this.frame; return ch.canvas }
    if (budget.n <= 0) return ch ? ch.canvas : null
    budget.n--
    const c = this.renderChunk(cx, cy, live)
    this.stale.delete(key)
    this.chunks.set(key, { canvas: c, stamp: this.frame })
    if (this.chunks.size > (live ? 40 : 80)) {
      let oldK = -1, oldS = Infinity
      for (const [k, v] of this.chunks) if (v.stamp < oldS && k !== key) { oldS = v.stamp; oldK = k }
      if (oldK >= 0) this.chunks.delete(oldK)
    }
    return c
  }

  // ---------------------------------------------------------------- territory

  private renderTerritory() {
    const w = this.world
    const R = 4
    const own = new Int32Array(w.W * w.H).fill(-1)
    const dist = new Float32Array(w.W * w.H).fill(1e9)
    for (const b of w.buildings) {
      if (!b.done || b.type === 'road') continue
      const rad = b.type === 'town_hall' ? 11 : BUILDINGS[b.type].size === 2 ? 7 : b.type === 'farm' || b.type === 'pasture' ? 3 : 5
      for (let dy = -rad; dy <= rad; dy++) for (let dx = -rad; dx <= rad; dx++) {
        const x = b.x + dx, y = b.y + dy
        if (!w.inBounds(x, y)) continue
        const dd = Math.hypot(dx, dy)
        if (dd > rad) continue
        const i = w.idx(x, y)
        if (BIOMES[w.biome[i]].water && w.biome[i] !== B.RIVER) continue
        if (dd < dist[i]) { dist[i] = dd; own[i] = b.tribeId }
      }
    }
    const c = this.terr ?? document.createElement('canvas')
    c.width = w.W * R
    c.height = w.H * R
    const ctx = c.getContext('2d')!
    ctx.clearRect(0, 0, c.width, c.height)
    const colors = new Map<number, string>()
    for (const t of w.tribes) colors.set(t.id, t.color)
    for (let y = 0; y < w.H; y++) for (let x = 0; x < w.W; x++) {
      const o = own[y * w.W + x]
      if (o < 0) continue
      const colr = colors.get(o) ?? '#fff'
      ctx.globalAlpha = 0.1
      ctx.fillStyle = colr
      ctx.fillRect(x * R, y * R, R, R)
      ctx.globalAlpha = 0.6
      if (x === 0 || own[y * w.W + x - 1] !== o) ctx.fillRect(x * R, y * R, 1, R)
      if (x === w.W - 1 || own[y * w.W + x + 1] !== o) ctx.fillRect(x * R + R - 1, y * R, 1, R)
      if (y === 0 || own[(y - 1) * w.W + x] !== o) ctx.fillRect(x * R, y * R, R, 1)
      if (y === w.H - 1 || own[(y + 1) * w.W + x] !== o) ctx.fillRect(x * R, y * R + R - 1, R, 1)
    }
    ctx.globalAlpha = 1
    this.terr = c
  }

  private renderDataOverlay(kind: Overlay): HTMLCanvasElement {
    const w = this.world
    const c = document.createElement('canvas')
    c.width = w.W
    c.height = w.H
    const ctx = c.getContext('2d')!
    const img = ctx.createImageData(w.W, w.H)
    const sel = this.selectedTribeId()
    const ex = sel ? w.explored.get(sel) : undefined
    for (let i = 0; i < w.W * w.H; i++) {
      let r = 0, g = 0, b = 0, a = 0
      if (kind === 'fertility') {
        const f = w.fertility[i]
        if (!BIOMES[w.biome[i]].water) { r = 255 * (1 - f); g = 220 * f + 30; b = 40; a = 120 }
      } else if (kind === 'temperature') {
        const t = w.temp[i] + [0, 0.14, -0.04, -0.28][w.season]
        r = clamp(t * 2, 0, 1) * 255; b = clamp(1 - t * 1.6, 0, 1) * 255; g = 60; a = 120
      } else if (kind === 'explored' && ex) {
        if (!ex[i]) { r = 8; g = 10; b = 20; a = 200 }
      }
      img.data[i * 4] = r; img.data[i * 4 + 1] = g; img.data[i * 4 + 2] = b; img.data[i * 4 + 3] = a
    }
    ctx.putImageData(img, 0, 0)
    return c
  }

  selectedTribeId(): number {
    const s = this.selection
    const w = this.world
    if (s.kind === 'agent') return w.agentById.get(s.id)?.tribeId ?? 0
    if (s.kind === 'building') return w.buildingById.get(s.id)?.tribeId ?? 0
    if (s.kind === 'tribe') return s.id
    return w.tribes[0]?.id ?? 0
  }

  private dataOverlayCache: { kind: Overlay; t: number; c: HTMLCanvasElement; sel: number } | null = null

  // ---------------------------------------------------------------- frame

  render(now: number) {
    const w = this.world
    const ctx = this.ctx
    const dt = this.lastT ? Math.min(0.1, (now - this.lastT) / 1000) : 0.016
    this.lastT = now
    this.frame++

    // camera easing / follow
    if (this.cinematic) {
      this.drift += dt * 0.05
      const w0 = this.world
      const tribe = w0.tribes.find((t) => t.alive)
      const cx = tribe ? tribe.center.x : w0.W / 2, cy = tribe ? tribe.center.y : w0.H / 2
      const tx = cx + Math.cos(this.drift) * 14, ty = cy + Math.sin(this.drift * 1.3) * 8
      this.cam.x += (tx - this.cam.x) * Math.min(1, dt * 0.5)
      this.cam.y += (ty - this.cam.y) * Math.min(1, dt * 0.5)
      this.cam.zoom += (1.7 - this.cam.zoom) * Math.min(1, dt * 0.5)
    } else if (this.follow && this.selection.kind === 'agent') {
      const a = w.agentById.get(this.selection.id)
      if (a && a.alive) { this.cam.x += (a.x - this.cam.x) * Math.min(1, dt * 4); this.cam.y += (a.y - this.cam.y) * Math.min(1, dt * 4) }
    } else if (this.target) {
      const k = Math.min(1, dt * 4)
      this.cam.x += (this.target.x - this.cam.x) * k
      this.cam.y += (this.target.y - this.cam.y) * k
      this.cam.zoom += (this.target.zoom - this.cam.zoom) * k
      if (Math.abs(this.target.x - this.cam.x) < 0.05 && Math.abs(this.target.y - this.cam.y) < 0.05) this.target = null
    }

    if (w.season !== this.chunkSeason) {
      this.chunkSeason = w.season
      // keep old chunks visible until repainted (dirty flags were set by the world)
    }
    const S = this.scale
    const dpr = this.dpr
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.fillStyle = '#10325f'
    ctx.fillRect(0, 0, this.vw, this.vh)

    const left = this.cam.x - this.vw / 2 / S, top = this.cam.y - this.vh / 2 / S
    const right = left + this.vw / S, bottom = top + this.vh / S
    const ox = this.vw / 2 - this.cam.x * S, oy = this.vh / 2 - this.cam.y * S

    // terrain chunks
    ctx.imageSmoothingEnabled = this.cam.zoom < 2.2
    const live = S >= 26
    const budget = { n: live ? 1 : 2 }
    const cx0 = Math.max(0, Math.floor(left / CHUNK)), cx1 = Math.min(Math.ceil(w.W / CHUNK) - 1, Math.floor(right / CHUNK))
    const cy0 = Math.max(0, Math.floor(top / CHUNK)), cy1 = Math.min(Math.ceil(w.H / CHUNK) - 1, Math.floor(bottom / CHUNK))
    // nearest chunks first so the centre paints before the edges
    const order: [number, number][] = []
    for (let cy = cy0; cy <= cy1; cy++) for (let cx = cx0; cx <= cx1; cx++) order.push([cx, cy])
    const ccx = this.cam.x / CHUNK - 0.5, ccy = this.cam.y / CHUNK - 0.5
    order.sort((p, q) => Math.hypot(p[0] - ccx, p[1] - ccy) - Math.hypot(q[0] - ccx, q[1] - ccy))
    for (const [cx, cy] of order) {
      const c = this.getChunk(cx, cy, budget, live) ?? (live ? this.chunks.get(((cy << 8) | cx) * 2)?.canvas ?? null : null)
      const sz = CHUNK * S
      if (c) ctx.drawImage(c, Math.floor(ox + cx * sz), Math.floor(oy + cy * sz), Math.ceil(sz) + 1, Math.ceil(sz) + 1)
      else {
        ctx.fillStyle = '#1d3a2a'
        ctx.fillRect(ox + cx * sz, oy + cy * sz, sz, sz)
      }
    }
    // keep a little prefetch going for neighbours
    if (budget.n > 0) {
      for (const [dx, dy] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) {
        const cx = Math.floor(this.cam.x / CHUNK) + dx * 2, cy = Math.floor(this.cam.y / CHUNK) + dy * 2
        if (cx >= 0 && cy >= 0 && cx * CHUNK < w.W && cy * CHUNK < w.H && budget.n > 0) this.getChunk(cx, cy, budget, live)
      }
    }

    // fade the edges of the world into open ocean rather than a hard line
    {
      const fade = 5 * S
      const edges: [number, number, number, number, number, number, number, number][] = [
        [ox, oy, w.W * S, fade, ox, oy, ox, oy + fade],
        [ox, oy + w.H * S - fade, w.W * S, fade, ox, oy + w.H * S, ox, oy + w.H * S - fade],
        [ox, oy, fade, w.H * S, ox, oy, ox + fade, oy],
        [ox + w.W * S - fade, oy, fade, w.H * S, ox + w.W * S, oy, ox + w.W * S - fade, oy],
      ]
      for (const [x, y, ww, hh, x0, y0, x1, y1] of edges) {
        if (x > this.vw || y > this.vh || x + ww < 0 || y + hh < 0) continue
        const g = ctx.createLinearGradient(x0, y0, x1, y1)
        g.addColorStop(0, '#10325f')
        g.addColorStop(1, 'rgba(16,50,95,0)')
        ctx.fillStyle = g
        ctx.fillRect(x, y, ww, hh)
      }
    }

    // water sparkles
    if (this.cam.zoom > 0.9) {
      const t = now / 1000
      ctx.fillStyle = 'rgba(255,255,255,0.55)'
      const x0 = Math.max(0, Math.floor(left)), x1 = Math.min(w.W - 1, Math.ceil(right))
      const y0 = Math.max(0, Math.floor(top)), y1 = Math.min(w.H - 1, Math.ceil(bottom))
      for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
        const h = hash2(x, y, 9)
        if (h > 0.05) continue
        const i = y * w.W + x
        if (!BIOMES[w.biome[i]].water) continue
        const ph = (t * 0.6 + h * 40) % 3
        if (ph > 1) continue
        const a = Math.sin(ph * Math.PI)
        ctx.globalAlpha = a * 0.7
        ctx.fillRect(ox + (x + 0.3 + h * 6) * S, oy + (y + 0.5) * S, S * 0.32, Math.max(1, S * 0.06))
      }
      ctx.globalAlpha = 1
    }

    // overlays
    if (this.overlay === 'territory') {
      if (!this.terr || (w.territoryDirty && now - this.terrT > 800)) {
        this.renderTerritory()
        w.territoryDirty = false
        this.terrT = now
      }
      if (this.terr) {
        ctx.imageSmoothingEnabled = true
        ctx.globalAlpha = clamp(1.35 - this.cam.zoom * 0.28, 0.22, 1)
        ctx.drawImage(this.terr, ox, oy, w.W * S, w.H * S)
        ctx.globalAlpha = 1
      }
    } else if (this.overlay !== 'none') {
      const sel = this.selectedTribeId()
      const cache = this.dataOverlayCache
      if (!cache || cache.kind !== this.overlay || now - cache.t > 2000 || cache.sel !== sel) this.dataOverlayCache = { kind: this.overlay, t: now, c: this.renderDataOverlay(this.overlay), sel }
      ctx.imageSmoothingEnabled = this.overlay !== 'explored'
      ctx.drawImage(this.dataOverlayCache!.c, ox, oy, w.W * S, w.H * S)
    }

    // cloud shadows
    const cloudy = w.weather === 'cloudy' || w.weather === 'rain' || w.weather === 'storm' || w.weather === 'snow'
    for (const cl of this.clouds) {
      cl.x += dt * cl.s * 0.25
      if (cl.x > w.W + 30) cl.x = -30
      if (!cloudy && cl.s < 0.75) continue
      const p = this.worldToScreen(cl.x, cl.y)
      const r = cl.r * S
      if (p.x + r < 0 || p.x - r > this.vw || p.y + r < 0 || p.y - r > this.vh) continue
      const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, r)
      g.addColorStop(0, `rgba(10,20,30,${cloudy ? 0.2 : 0.08})`)
      g.addColorStop(1, 'rgba(10,20,30,0)')
      ctx.fillStyle = g
      ctx.fillRect(p.x - r, p.y - r, r * 2, r * 2)
    }

    // hover tile
    if (this.hover && this.cam.zoom > 0.7) {
      ctx.strokeStyle = 'rgba(255,255,255,0.5)'
      ctx.lineWidth = 1
      ctx.strokeRect(ox + this.hover.x * S + 0.5, oy + this.hover.y * S + 0.5, S - 1, S - 1)
    }

    // --- entities, y-sorted
    type Drawable = { y: number; draw: () => void }
    const list: Drawable[] = []
    const pad = 4
    for (const b of w.buildings) {
      if (b.x + 2 < left - pad || b.x > right + pad || b.y + 2 < top - pad || b.y > bottom + pad) continue
      list.push({ y: b.y + BUILDINGS[b.type].size - (b.type === 'farm' || b.type === 'pasture' || b.type === 'road' ? 2 : 0), draw: () => this.drawBuilding(b, ox, oy, S, now) })
    }
    if (live) {
      const tsec = now / 1000
      const wind = w.weather === 'storm' ? 3 : w.weather === 'rain' || w.weather === 'snow' ? 1.6 : 1
      const x0 = Math.max(0, Math.floor(left) - 1), x1 = Math.min(w.W - 1, Math.ceil(right) + 1)
      const y0 = Math.max(0, Math.floor(top) - 1), y1 = Math.min(w.H - 1, Math.ceil(bottom) + 2)
      const season = w.season
      for (let ty = y0; ty <= y1; ty++) for (let tx = x0; tx <= x1; tx++) {
        const i = ty * w.W + tx
        const f = w.feature[i]
        if (!f) continue
        if (w.occ[i]) { const bb = w.buildingById.get(w.occ[i] - 1); if (bb && bb.type !== 'mine') continue }
        const v = Math.floor(hash2(tx, ty, 11) * 4)
        const empty = (f === F.BUSH || f === F.FIBER) && w.amount[i] === 0
        const spr = featureSprite(f, v, season, empty, 3)
        if (!spr) continue
        const tree = f >= F.TREE && f <= F.JUNGLE
        const jx = (hash2(tx, ty, 5) - 0.5) * 5 / TP, jy = (hash2(tx, ty, 6) - 0.5) * 3 / TP
        list.push({ y: ty + 0.82, draw: () => {
          const k = S / SPR
          const dw = (spr.img.width / spr.res) * k, dh = (spr.img.height / spr.res) * k
          const px = ox + (tx + jx) * S + spr.ox * k, py = oy + (ty + jy) * S + spr.oy * k
          const sway = tree || f === F.FIBER ? Math.sin(tsec * (1.1 + hash2(tx, ty, 3) * 0.6) + tx * 0.3 + ty * 0.2) * 0.03 * wind : 0
          if (sway) {
            ctx.save()
            ctx.translate(px + dw / 2, py + dh * 0.92)
            ctx.rotate(sway)
            ctx.drawImage(spr.img, -dw / 2, -dh * 0.92, dw, dh)
            ctx.restore()
          } else ctx.drawImage(spr.img, px, py, dw, dh)
        } })
      }
    }
    for (const an of w.animals) {
      if (!an.alive || an.x < left - 1 || an.x > right + 1 || an.y < top - 1 || an.y > bottom + 1) continue
      list.push({ y: an.y, draw: () => this.drawAnimal(an, ox, oy, S, now) })
    }
    for (const a of w.agents) {
      if (!a.alive || a.x < left - 1 || a.x > right + 1 || a.y < top - 1 || a.y > bottom + 2) continue
      list.push({ y: a.y, draw: () => this.drawAgent(a, ox, oy, S, now) })
    }
    list.sort((p, q) => p.y - q.y)
    for (const d of list) d.draw()

    // selected agent: path + ring
    this.drawSelection(ox, oy, S, now)

    // particles (smoke, chips, sparks, leaves) and ambient life
    this.updateParticles(dt, ox, oy, S)
    this.drawAmbient(dt, ox, oy, S, now, left, right, top, bottom)

    // --- lighting
    this.drawLighting(ox, oy, S, now, left, right, top, bottom)

    // --- weather
    this.drawWeather(dt)

    // --- a gentle cinematic grade: vignette
    const vg = ctx.createRadialGradient(this.vw / 2, this.vh / 2, Math.min(this.vw, this.vh) * 0.35, this.vw / 2, this.vh / 2, Math.max(this.vw, this.vh) * 0.75)
    vg.addColorStop(0, 'rgba(0,0,0,0)')
    vg.addColorStop(1, 'rgba(5,8,20,0.42)')
    ctx.fillStyle = vg
    ctx.fillRect(0, 0, this.vw, this.vh)

    // --- labels & speech on top of everything
    this.drawLabels(ox, oy, S, now, left, right, top, bottom)
  }

  private drawBuilding(b: Building, ox: number, oy: number, S: number, now: number) {
    const w = this.world
    const ctx = this.ctx
    const def = BUILDINGS[b.type]
    const t = w.tribeById(b.tribeId)
    const color = t?.color ?? '#ccc'
    const x = ox + b.x * S, y = oy + b.y * S
    const T = def.size * S
    if (b.type === 'farm') {
      ctx.fillStyle = b.planted ? '#6e5236' : '#7d5f40'
      ctx.fillRect(x + 1, y + 1, T - 2, T - 2)
      const rows = 4
      for (let r = 0; r < rows; r++) {
        const yy = y + (r + 0.5) * (T / rows)
        ctx.fillStyle = 'rgba(40,25,10,0.35)'
        ctx.fillRect(x + 2, yy + 1, T - 4, Math.max(1, S * 0.06))
        if (b.planted) {
          const g = b.crop
          ctx.fillStyle = g >= 1 ? '#e2c44a' : `rgb(${Math.round(90 + g * 80)},${Math.round(150 + g * 40)},${Math.round(60 - g * 20)})`
          const h = Math.max(1, S * (0.06 + g * 0.18))
          for (let k = 0; k < 5; k++) ctx.fillRect(x + 2 + (k + 0.25) * ((T - 4) / 5), yy - h + 1, Math.max(1, S * 0.08), h)
        }
      }
      if (!b.done) this.drawScaffold(b, x, y, T, S)
      return
    }
    if (b.type === 'pasture') {
      ctx.fillStyle = '#7aa456'
      ctx.fillRect(x + 1, y + 1, T - 2, T - 2)
      ctx.strokeStyle = '#7a5530'
      ctx.lineWidth = Math.max(1, S * 0.07)
      ctx.strokeRect(x + 2, y + 2, T - 4, T - 4)
      const n = Math.min(6, Math.ceil(b.crop / 2))
      for (let k = 0; k < n; k++) {
        const px = x + T * (0.25 + ((k * 37 + Math.floor(now / 3000)) % 50) / 100), py = y + T * (0.3 + ((k * 23) % 45) / 100)
        ctx.fillStyle = k % 3 === 0 ? '#5a4030' : '#f2efe6'
        ctx.beginPath(); ctx.ellipse(px, py, S * 0.12, S * 0.08, 0, 0, Math.PI * 2); ctx.fill()
      }
      if (!b.done) this.drawScaffold(b, x, y, T, S)
      return
    }
    const era = t ? eraOf(w.techs(t.id)) : 0
    const spr = buildingSprite(b.type, color, def.size, era)
    const k = S / SPR
    if (!b.done) {
      ctx.globalAlpha = 0.35 + 0.5 * (b.progress / def.hours)
      ctx.drawImage(spr.img, x + spr.ox * k, y + spr.oy * k, (spr.img.width / spr.res) * k, (spr.img.height / spr.res) * k)
      ctx.globalAlpha = 1
      this.drawScaffold(b, x, y, T, S)
      return
    }
    ctx.drawImage(spr.img, x + spr.ox * k, y + spr.oy * k, (spr.img.width / spr.res) * k, (spr.img.height / spr.res) * k)
    // live details
    const tsec = now / 1000
    if (b.type === 'campfire') {
      const fl = 0.75 + Math.sin(tsec * 13 + b.id) * 0.12 + Math.sin(tsec * 7.3) * 0.1
      const fx = x + T / 2, fy = y + T * 0.72
      ctx.fillStyle = '#ff8a1e'
      ctx.beginPath(); ctx.moveTo(fx - S * 0.18, fy); ctx.quadraticCurveTo(fx, fy - S * 0.75 * fl, fx + S * 0.18, fy); ctx.fill()
      ctx.fillStyle = '#ffd34a'
      ctx.beginPath(); ctx.moveTo(fx - S * 0.09, fy); ctx.quadraticCurveTo(fx, fy - S * 0.45 * fl, fx + S * 0.09, fy); ctx.fill()
      if (Math.random() < 0.05) this.particles.push({ x: b.x + 0.5, y: b.y + 0.4, vx: (Math.random() - 0.5) * 0.2, vy: -0.6, life: 1.4, kind: 1 })
    } else if (b.type === 'mill') {
      const cxm = x + T / 2, cym = y - T * 0.12
      const rot = tsec * 1.4
      ctx.fillStyle = '#efe7d6'
      for (let q = 0; q < 4; q++) {
        const a = rot + (q * Math.PI) / 2
        ctx.save()
        ctx.translate(cxm, cym)
        ctx.rotate(a)
        ctx.fillRect(S * 0.05, -S * 0.06, S * 0.5, S * 0.12)
        ctx.restore()
      }
      ctx.fillStyle = '#6b4a2b'
      ctx.beginPath(); ctx.arc(cxm, cym, S * 0.06, 0, Math.PI * 2); ctx.fill()
    } else if ((b.type === 'hut' || b.type === 'house') && (w.season >= 2 || hourOf(w.time) > 17 || hourOf(w.time) < 8) && Math.random() < 0.05) {
      this.particles.push({ x: b.x + 0.65, y: b.y - 0.5, vx: 0.1 + Math.random() * 0.08, vy: -0.3, life: 3, kind: 0 })
    } else if ((b.type === 'factory' || b.type === 'smithy' || b.type === 'power_plant') && Math.random() < 0.12) {
      const sx = b.type === 'factory' ? b.x + def.size - 0.35 : b.type === 'power_plant' ? b.x + 0.6 : b.x + 0.85
      const sy = b.type === 'factory' ? b.y - 1.1 : b.type === 'power_plant' ? b.y - 0.65 : b.y - 0.35
      this.particles.push({ x: sx, y: sy, vx: 0.15 + Math.random() * 0.1, vy: -0.35, life: 3, kind: b.type === 'power_plant' ? 2 : 0 })
    }
    if (b.hp < 60) {
      ctx.fillStyle = 'rgba(40,20,10,0.35)'
      ctx.fillRect(x, y, T, T)
    }
  }

  private drawScaffold(b: Building, x: number, y: number, T: number, S: number) {
    const ctx = this.ctx
    const def = BUILDINGS[b.type]
    const p = clamp(b.progress / def.hours, 0, 1)
    ctx.strokeStyle = '#c9a36a'
    ctx.lineWidth = Math.max(1, S * 0.05)
    ctx.strokeRect(x + 2, y + 2, T - 4, T - 4)
    ctx.beginPath(); ctx.moveTo(x + 2, y + 2); ctx.lineTo(x + T - 2, y + T - 2); ctx.stroke()
    if (S > 10) {
      ctx.fillStyle = 'rgba(0,0,0,0.55)'
      ctx.fillRect(x + 2, y + T - S * 0.18, T - 4, S * 0.12)
      ctx.fillStyle = '#7ee0a0'
      ctx.fillRect(x + 2, y + T - S * 0.18, (T - 4) * p, S * 0.12)
    }
  }

  private drawAnimal(an: World['animals'][0], ox: number, oy: number, S: number, now: number) {
    const moving = Math.hypot(an.tx + 0.5 - an.x, an.ty + 0.5 - an.y) > 0.25
    drawCreature(this.ctx, an, ox + an.x * S, oy + an.y * S, S, now / 1000, moving)
  }

  private drawAgent(a: Agent, ox: number, oy: number, S: number, now: number) {
    const w = this.world
    const ctx = this.ctx
    const t = w.tribeById(a.tribeId)
    const x = ox + a.x * S, y = oy + a.y * S + S * 0.3
    const r = drawPerson(ctx, {
      x, y, h: S * 1.45, t: now / 1000, a, era: t ? eraOf(w.techs(t.id)) : 0, tribeColor: t?.color ?? '#ccc',
      leader: t?.leaderId === a.id, age: w.age(a), toolTier: a.toolTier,
    })
    if (r.impact && Math.random() < 0.7) {
      const item = a.task?.target ?? ''
      const kind = item === 'wood' ? 3 : a.task?.action === 'build' || a.task?.action === 'craft' ? 6 : 4
      for (let k = 0; k < 4; k++) this.particles.push({ x: (r.impact.x - ox) / S, y: (r.impact.y - oy) / S, vx: (Math.random() - 0.5) * 1.6, vy: -0.8 - Math.random() * 1.2, life: 0.6 + Math.random() * 0.3, kind })
    }
    // a thinking mind
    if (a.aiPending) {
      const p = 0.5 + 0.5 * Math.sin(now / 200 + a.id)
      const hy = r.headY - S * 0.18
      ctx.fillStyle = `rgba(186,160,255,${0.25 * p})`
      ctx.beginPath(); ctx.arc(x, hy, S * 0.22, 0, Math.PI * 2); ctx.fill()
      ctx.fillStyle = `rgba(206,190,255,${0.6 + p * 0.4})`
      ctx.beginPath(); ctx.arc(x, hy, S * (0.07 + p * 0.03), 0, Math.PI * 2); ctx.fill()
    }
    // feelings show
    let icon = a.emote && now < a.emote.until ? a.emote.icon : ''
    if (!icon && a.sick > 0.5) icon = '🤒'
    if (!icon && a.hunger > 85) icon = '🍖'
    if (!icon && a.warmth < 25) icon = '🥶'
    if (icon && S > 9 && !a.aiPending) {
      const fresh = a.emote && now < a.emote.until ? Math.min(1, (a.emote.until - now) / 500) : 0.85
      const pop = a.emote && now < a.emote.until ? 1 + Math.max(0, Math.min(1, 1 - (now - a.emote.start) / 300)) * 0.4 : 1
      const fs = Math.round(Math.min(34, Math.max(12, S * 0.5)) * pop)
      const by = r.headY - S * 0.2 - Math.sin(now / 300 + a.id) * S * 0.03
      ctx.globalAlpha = fresh
      ctx.fillStyle = 'rgba(255,255,255,0.92)'
      ctx.beginPath(); ctx.arc(x + S * 0.38, by - fs * 0.35, fs * 0.68, 0, Math.PI * 2); ctx.fill()
      ctx.beginPath(); ctx.moveTo(x + S * 0.16, by + fs * 0.05); ctx.lineTo(x + S * 0.26, by - fs * 0.1); ctx.lineTo(x + S * 0.34, by + fs * 0.12); ctx.fill()
      ctx.font = `${fs}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText(icon, x + S * 0.38, by - fs * 0.32)
      ctx.textBaseline = 'alphabetic'
      ctx.globalAlpha = 1
    }
  }

  private drawSelection(ox: number, oy: number, S: number, now: number) {
    const w = this.world
    const ctx = this.ctx
    const sel = this.selection
    if (sel.kind === 'agent') {
      const a = w.agentById.get(sel.id)
      if (!a || !a.alive) return
      const t = w.tribeById(a.tribeId)
      const p = a.task?.path
      if (p && a.task && a.task.pathIdx < p.length) {
        ctx.strokeStyle = t?.color ?? '#fff'
        ctx.globalAlpha = 0.85
        ctx.lineWidth = Math.max(1.5, S * 0.08)
        ctx.setLineDash([S * 0.25, S * 0.2])
        ctx.lineDashOffset = -now / 40
        ctx.beginPath()
        ctx.moveTo(ox + a.x * S, oy + a.y * S)
        for (let k = a.task.pathIdx; k < p.length; k++) ctx.lineTo(ox + ((p[k] % w.W) + 0.5) * S, oy + (Math.floor(p[k] / w.W) + 0.5) * S)
        ctx.stroke()
        ctx.setLineDash([])
        ctx.globalAlpha = 1
        const last = p[p.length - 1]
        ctx.strokeStyle = '#fff'
        ctx.lineWidth = 1.5
        ctx.strokeRect(ox + (last % w.W) * S + 2, oy + Math.floor(last / w.W) * S + 2, S - 4, S - 4)
      }
      const r = S * 0.5 + Math.sin(now / 250) * S * 0.04
      ctx.strokeStyle = '#ffffff'
      ctx.lineWidth = 2
      ctx.beginPath(); ctx.ellipse(ox + a.x * S, oy + a.y * S + S * 0.15, r, r * 0.4, 0, 0, Math.PI * 2); ctx.stroke()
    } else if (sel.kind === 'building') {
      const b = w.buildingById.get(sel.id)
      if (!b) return
      const T = BUILDINGS[b.type].size * S
      ctx.strokeStyle = '#fff'
      ctx.lineWidth = 2
      ctx.setLineDash([6, 4])
      ctx.strokeRect(ox + b.x * S, oy + b.y * S, T, T)
      ctx.setLineDash([])
    } else if (sel.kind === 'tile') {
      const x = sel.id % w.W, y = Math.floor(sel.id / w.W)
      ctx.strokeStyle = '#fff'
      ctx.lineWidth = 2
      ctx.strokeRect(ox + x * S, oy + y * S, S, S)
    }
  }

  private updateParticles(dt: number, ox: number, oy: number, S: number) {
    const ctx = this.ctx
    const keep: typeof this.particles = []
    for (const p of this.particles) {
      p.life -= dt
      if (p.life <= 0) continue
      p.x += p.vx * dt
      p.y += p.vy * dt
      if (p.kind >= 3 && p.kind <= 6) p.vy += 4.5 * dt // gravity for chips, sparks, dust
      if (p.kind === 7) { p.vx += Math.sin(p.life * 3 + p.y) * dt * 0.6 } // falling leaf flutter
      keep.push(p)
      const x = ox + p.x * S, y = oy + p.y * S
      const sz = Math.max(1.5, S * 0.06)
      switch (p.kind) {
        case 1:
          ctx.fillStyle = `rgba(255,${150 + Math.round(p.life * 60)},60,${Math.min(1, p.life)})`
          ctx.fillRect(x, y, sz, sz)
          break
        case 3: ctx.fillStyle = `rgba(196,150,90,${Math.min(1, p.life * 2)})`; ctx.fillRect(x, y, sz * 1.4, sz * 0.8); break
        case 4: ctx.fillStyle = `rgba(255,${200 + Math.round(p.life * 50)},120,${Math.min(1, p.life * 2)})`; ctx.fillRect(x, y, sz, sz); break
        case 6: ctx.fillStyle = `rgba(200,190,170,${Math.min(0.7, p.life)})`; ctx.beginPath(); ctx.arc(x, y, sz * 1.2, 0, Math.PI * 2); ctx.fill(); break
        case 7: ctx.save(); ctx.translate(x, y); ctx.rotate(p.life * 4); ctx.fillStyle = `rgba(${200 + (p.vx > 0 ? 30 : 0)},${110 + Math.round(p.life * 20)},40,${Math.min(1, p.life)})`; ctx.beginPath(); ctx.ellipse(0, 0, sz * 1.4, sz * 0.7, 0, 0, Math.PI * 2); ctx.fill(); ctx.restore(); break
        default: {
          const r = S * (0.15 + (3 - p.life) * 0.18)
          ctx.fillStyle = p.kind === 2 ? `rgba(240,240,245,${p.life * 0.12})` : `rgba(80,80,85,${p.life * 0.14})`
          ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill()
        }
      }
    }
    this.particles = keep.length > 600 ? keep.slice(-600) : keep
  }

  // ---------------------------------------------------------------- ambient life

  private birds: { x: number; y: number; vx: number; vy: number; n: number; ph: number }[] = []
  private bugs: { x: number; y: number; ph: number; kind: number }[] = []

  private drawAmbient(dt: number, ox: number, oy: number, S: number, now: number, left: number, right: number, top: number, bottom: number) {
    const w = this.world
    const ctx = this.ctx
    const t = now / 1000
    const day = daylight(w.time)
    const s = w.season
    // flocks of birds crossing the sky, with shadows on the ground
    if (this.birds.length < 3 && Math.random() < dt * 0.15 && day > 0.4 && s !== 3) {
      const fromLeft = Math.random() < 0.5
      this.birds.push({ x: fromLeft ? left - 4 : right + 4, y: top + Math.random() * (bottom - top), vx: (fromLeft ? 1 : -1) * (3 + Math.random() * 2), vy: (Math.random() - 0.5) * 1.2, n: 3 + Math.floor(Math.random() * 6), ph: Math.random() * 10 })
    }
    this.birds = this.birds.filter((b) => b.x > left - 8 && b.x < right + 8)
    for (const b of this.birds) {
      b.x += b.vx * dt
      b.y += b.vy * dt
      for (let k = 0; k < b.n; k++) {
        const row = Math.ceil(k / 2), side = k % 2 ? 1 : -1
        const bx = b.x - Math.sign(b.vx) * row * 0.7, by = b.y + side * row * 0.5
        const flap = Math.sin(t * 9 + b.ph + k) * 0.5
        // shadow
        ctx.fillStyle = 'rgba(0,0,0,0.12)'
        ctx.beginPath(); ctx.ellipse(ox + (bx + 1.5) * S, oy + (by + 3) * S, S * 0.12, S * 0.05, 0, 0, Math.PI * 2); ctx.fill()
        ctx.strokeStyle = 'rgba(30,30,40,0.85)'
        ctx.lineWidth = Math.max(1.2, S * 0.05)
        const X = ox + bx * S, Y = oy + by * S
        ctx.beginPath(); ctx.moveTo(X - S * 0.22, Y - flap * S * 0.18); ctx.quadraticCurveTo(X - S * 0.08, Y - S * 0.06, X, Y); ctx.quadraticCurveTo(X + S * 0.08, Y - S * 0.06, X + S * 0.22, Y - flap * S * 0.18); ctx.stroke()
      }
    }
    if (S < 22) return
    // butterflies by day in spring and summer; fireflies on summer nights
    const want = s <= 1 ? 14 : 0
    while (this.bugs.length < want) this.bugs.push({ x: left + Math.random() * (right - left), y: top + Math.random() * (bottom - top), ph: Math.random() * 100, kind: Math.floor(Math.random() * 4) })
    if (this.bugs.length > want) this.bugs.length = want
    for (const b of this.bugs) {
      b.x += Math.sin(t * 0.7 + b.ph) * dt * 0.8
      b.y += Math.cos(t * 0.9 + b.ph * 1.3) * dt * 0.6
      if (b.x < left - 2 || b.x > right + 2 || b.y < top - 2 || b.y > bottom + 2) { b.x = left + Math.random() * (right - left); b.y = top + Math.random() * (bottom - top) }
      const X = ox + b.x * S, Y = oy + b.y * S
      if (day < 0.35) {
        if (s !== 1) continue
        const glow = 0.5 + 0.5 * Math.sin(t * 3 + b.ph)
        ctx.fillStyle = `rgba(220,255,120,${0.2 * glow})`
        ctx.beginPath(); ctx.arc(X, Y, S * 0.25, 0, Math.PI * 2); ctx.fill()
        ctx.fillStyle = `rgba(240,255,160,${0.6 + glow * 0.4})`
        ctx.beginPath(); ctx.arc(X, Y, S * 0.05, 0, Math.PI * 2); ctx.fill()
      } else {
        const flap = Math.abs(Math.sin(t * 14 + b.ph))
        ctx.fillStyle = ['#ffd54f', '#f48fb1', '#ffffff', '#81d4fa'][b.kind]
        ctx.beginPath(); ctx.ellipse(X - S * 0.06 * flap, Y, S * 0.08 * flap + 0.5, S * 0.06, -0.3, 0, Math.PI * 2); ctx.fill()
        ctx.beginPath(); ctx.ellipse(X + S * 0.06 * flap, Y, S * 0.08 * flap + 0.5, S * 0.06, 0.3, 0, Math.PI * 2); ctx.fill()
      }
    }
    // autumn leaves drift down from trees in view
    if (s === 2 && Math.random() < dt * 6) this.particles.push({ x: left + Math.random() * (right - left), y: top + Math.random() * (bottom - top) * 0.7, vx: 0.4 + Math.random() * 0.3, vy: 0.5 + Math.random() * 0.4, life: 3 + Math.random() * 2, kind: 7 })
  }

  private drawLighting(ox: number, oy: number, S: number, now: number, left: number, right: number, top: number, bottom: number) {
    const w = this.world
    const day = daylight(w.time)
    const h = hourOf(w.time)
    const ctx = this.ctx
    // dawn / dusk warmth
    const golden = Math.max(0, 1 - Math.abs(h - 6.5) / 1.6) + Math.max(0, 1 - Math.abs(h - 19) / 1.8)
    if (golden > 0) {
      ctx.fillStyle = `rgba(255,140,60,${golden * 0.13})`
      ctx.fillRect(0, 0, this.vw, this.vh)
    }
    const dark = clamp((0.72 - day) / 0.72, 0, 1) * 0.64
    if (dark < 0.02) return
    const L = this.lctx
    const lw = this.light.width, lh = this.light.height
    const k = this.dpr / 2
    L.globalCompositeOperation = 'source-over'
    L.clearRect(0, 0, lw, lh)
    L.fillStyle = `rgba(6,12,34,${dark})`
    L.fillRect(0, 0, lw, lh)
    L.globalCompositeOperation = 'destination-out'
    const lights: [number, number, number, number][] = [] // x, y, radius(tiles), warmth
    const electric = new Set<number>()
    for (const t of w.tribes) if (w.techs(t.id).has('electricity')) electric.add(t.id)
    for (const b of w.buildings) {
      if (!b.done) continue
      const def = BUILDINGS[b.type]
      let r = def.light ?? 0
      if (electric.has(b.tribeId) && b.type !== 'farm' && b.type !== 'pasture') r = Math.max(r, 2.5) + 1
      if (!r) continue
      if (b.x + r < left || b.x - r > right || b.y + r < top || b.y - r > bottom) continue
      const flick = b.type === 'campfire' ? 0.9 + Math.sin(now / 90 + b.id) * 0.06 + Math.sin(now / 37) * 0.04 : 1
      lights.push([b.x + def.size / 2, b.y + def.size / 2, r * flick, b.type === 'campfire' || b.type === 'kiln' || b.type === 'smithy' ? 1 : electric.has(b.tribeId) ? 0.2 : 0.6])
    }
    // street lights along electrified roads
    if (electric.size) {
      for (let y = Math.max(0, Math.floor(top)); y < Math.min(w.H, Math.ceil(bottom)); y++) for (let x = Math.max(0, Math.floor(left)); x < Math.min(w.W, Math.ceil(right)); x++) {
        if (w.road[y * w.W + x] && (x + y) % 4 === 0) lights.push([x + 0.5, y + 0.5, 2.2, 0.15])
      }
    }
    for (const [lx, ly, r] of lights) {
      const px = (ox + lx * S) * k, py = (oy + ly * S) * k, rr = r * S * k
      const g = L.createRadialGradient(px, py, 0, px, py, rr)
      g.addColorStop(0, 'rgba(0,0,0,1)')
      g.addColorStop(0.5, 'rgba(0,0,0,0.6)')
      g.addColorStop(1, 'rgba(0,0,0,0)')
      L.fillStyle = g
      L.fillRect(px - rr, py - rr, rr * 2, rr * 2)
    }
    ctx.save()
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.imageSmoothingEnabled = true
    ctx.drawImage(this.light, 0, 0, this.canvas.width, this.canvas.height)
    ctx.restore()
    // warm additive glow
    ctx.globalCompositeOperation = 'lighter'
    for (const [lx, ly, r, warm] of lights) {
      const px = ox + lx * S, py = oy + ly * S, rr = r * S * 0.7
      const g = ctx.createRadialGradient(px, py, 0, px, py, rr)
      const c = warm > 0.5 ? '255,150,60' : warm > 0.3 ? '255,200,120' : '200,220,255'
      g.addColorStop(0, `rgba(${c},${0.32 * dark})`)
      g.addColorStop(1, `rgba(${c},0)`)
      ctx.fillStyle = g
      ctx.fillRect(px - rr, py - rr, rr * 2, rr * 2)
    }
    ctx.globalCompositeOperation = 'source-over'
  }

  private drawWeather(dt: number) {
    const w = this.world
    const ctx = this.ctx
    const kind = w.weather
    if (kind === 'fog') {
      ctx.fillStyle = 'rgba(220,226,232,0.28)'
      ctx.fillRect(0, 0, this.vw, this.vh)
      return
    }
    if (kind === 'cloudy') {
      ctx.fillStyle = 'rgba(40,50,60,0.08)'
      ctx.fillRect(0, 0, this.vw, this.vh)
      return
    }
    if (kind !== 'rain' && kind !== 'storm' && kind !== 'snow') return
    const n = kind === 'storm' ? 420 : kind === 'rain' ? 240 : 200
    ctx.fillStyle = kind === 'snow' ? 'rgba(30,40,60,0.06)' : 'rgba(20,30,45,0.16)'
    ctx.fillRect(0, 0, this.vw, this.vh)
    if (kind === 'storm' && Math.random() < 0.004) {
      ctx.fillStyle = 'rgba(255,255,255,0.35)'
      ctx.fillRect(0, 0, this.vw, this.vh)
    }
    ctx.strokeStyle = kind === 'snow' ? 'rgba(255,255,255,0.85)' : 'rgba(190,210,235,0.45)'
    ctx.fillStyle = 'rgba(255,255,255,0.85)'
    ctx.lineWidth = 1
    ctx.beginPath()
    for (let k = 0; k < n; k++) {
      const p = this.rain[k]
      if (kind === 'snow') {
        p.y += dt * 0.08 * p.s
        p.x += dt * 0.02 * Math.sin(p.y * 20 + k)
      } else {
        p.y += dt * (kind === 'storm' ? 1.6 : 1.1) * p.s
        p.x += dt * 0.15
      }
      if (p.y > 1) { p.y -= 1; p.x = Math.random() }
      if (p.x > 1) p.x -= 1
      const x = p.x * this.vw, y = p.y * this.vh
      if (kind === 'snow') ctx.rect(x, y, 2 * p.s, 2 * p.s)
      else { ctx.moveTo(x, y); ctx.lineTo(x - 3, y + 12 * p.s) }
    }
    if (kind === 'snow') ctx.fill()
    else ctx.stroke()
  }

  private drawLabels(ox: number, oy: number, S: number, now: number, left: number, right: number, top: number, bottom: number) {
    const w = this.world
    const ctx = this.ctx
    const showNames = this.showNames && this.cam.zoom >= 1.25
    ctx.textAlign = 'center'
    ctx.textBaseline = 'alphabetic'
    // tribe banners at their hubs when zoomed out
    if (this.cam.zoom < 1.1) {
      ctx.font = '600 12px Inter, sans-serif'
      for (const t of w.tribes) {
        if (!t.alive) continue
        const p = this.worldToScreen(t.center.x + 0.5, t.center.y - 0.5)
        if (p.x < -100 || p.x > this.vw + 100 || p.y < -40 || p.y > this.vh + 40) continue
        const label = `${t.emblem} ${t.name}`
        const tw = ctx.measureText(label).width + 16
        ctx.fillStyle = 'rgba(8,12,22,0.72)'
        roundRect(ctx, p.x - tw / 2, p.y - 26, tw, 20, 10)
        ctx.fill()
        ctx.fillStyle = t.color
        ctx.fillRect(p.x - tw / 2 + 8, p.y - 9, tw - 16, 1.5)
        ctx.fillStyle = '#fff'
        ctx.fillText(label, p.x, p.y - 12)
      }
    }
    const placed: [number, number, number, number][] = []
    const free = (x: number, y: number, wdt: number, h: number) => {
      for (const [px, py, pw, ph] of placed) if (x < px + pw && x + wdt > px && y < py + ph && y + h > py) return false
      return true
    }
    const visible = w.agents.filter((a) => a.alive && a.x >= left - 1 && a.x <= right + 1 && a.y >= top - 1 && a.y <= bottom + 2)
    // selected and speaking people get first claim on label space
    visible.sort((p, q) => {
      const sp = (this.selection.kind === 'agent' && this.selection.id === p.id ? 2 : 0) + (p.speech && now < p.speech.until ? 1 : 0)
      const sq = (this.selection.kind === 'agent' && this.selection.id === q.id ? 2 : 0) + (q.speech && now < q.speech.until ? 1 : 0)
      return sq - sp
    })
    for (const a of visible) {
      const x = ox + a.x * S, y = oy + a.y * S
      const selected = this.selection.kind === 'agent' && this.selection.id === a.id
      const sp = a.speech
      if (sp && now < sp.until && this.cam.zoom > 0.55) {
        const fade = Math.min(1, (sp.until - now) / 600)
        const box = this.bubble(x, y - S * 0.95, sp.text, fade, w.tribeById(a.tribeId)?.color ?? '#fff', selected, selected ? null : free)
        if (box) { placed.push(box); continue }
      }
      if (showNames || selected) {
        ctx.font = `${selected ? 700 : 600} 11px Inter, sans-serif`
        const tw = ctx.measureText(a.name).width
        if (!selected && !free(x - tw / 2, y - S * 0.8 - 11, tw, 13)) continue
        placed.push([x - tw / 2, y - S * 0.8 - 11, tw, 13])
        ctx.lineWidth = 3
        ctx.strokeStyle = 'rgba(0,0,0,0.65)'
        ctx.strokeText(a.name, x, y - S * 0.8)
        ctx.fillStyle = selected ? '#fff' : 'rgba(255,255,255,0.9)'
        ctx.fillText(a.name, x, y - S * 0.8)
      }
    }
  }

  private bubble(x: number, y: number, text: string, alpha: number, color: string, big: boolean, free: ((x: number, y: number, w: number, h: number) => boolean) | null): [number, number, number, number] | null {
    const ctx = this.ctx
    ctx.font = `500 ${big ? 12.5 : 11.5}px Inter, sans-serif`
    const maxW = big ? 240 : 190
    const words = text.split(/\s+/)
    const lines: string[] = []
    let cur = ''
    for (const wd of words) {
      const test = cur ? cur + ' ' + wd : wd
      if (ctx.measureText(test).width > maxW && cur) { lines.push(cur); cur = wd } else cur = test
      if (lines.length >= (big ? 5 : 3)) break
    }
    if (cur && lines.length < (big ? 5 : 3)) lines.push(cur)
    else if (lines.length >= (big ? 5 : 3)) lines[lines.length - 1] += '…'
    const lh = big ? 16 : 14.5
    let wmax = 0
    for (const l of lines) wmax = Math.max(wmax, ctx.measureText(l).width)
    const bw = wmax + 18, bh = lines.length * lh + 10
    const bx = x - bw / 2, by = y - bh - 8
    if (free && !free(bx, by, bw, bh + 8)) return null
    ctx.globalAlpha = alpha
    ctx.fillStyle = 'rgba(250,250,252,0.96)'
    ctx.shadowColor = 'rgba(0,0,0,0.3)'
    ctx.shadowBlur = 8
    roundRect(ctx, bx, by, bw, bh, 9)
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.beginPath(); ctx.moveTo(x - 6, by + bh - 0.5); ctx.lineTo(x, by + bh + 7); ctx.lineTo(x + 6, by + bh - 0.5); ctx.closePath(); ctx.fill()
    ctx.fillStyle = color
    ctx.fillRect(bx + 8, by + 3, bw - 16, 2)
    ctx.fillStyle = '#1c2230'
    ctx.textAlign = 'center'
    lines.forEach((l, k) => ctx.fillText(l, x, by + 8 + lh * (k + 0.78)))
    ctx.globalAlpha = 1
    return [bx, by, bw, bh + 8]
  }

  // ---------------------------------------------------------------- minimap

  renderMinimap(c: HTMLCanvasElement) {
    const w = this.world
    if (c.width !== w.W || c.height !== w.H) { c.width = w.W; c.height = w.H }
    const ctx = c.getContext('2d')!
    if (!this.minimapBase || this.minimapSeason !== w.season) {
      const img = ctx.createImageData(w.W, w.H)
      for (let i = 0; i < w.W * w.H; i++) {
        const [r, g, b, s] = this.tileColor(i, w.season)
        const f = w.feature[i]
        const tree = f >= F.TREE && f <= F.JUNGLE ? 0.82 : 1
        img.data[i * 4] = (r + (236 - r) * s) * tree
        img.data[i * 4 + 1] = (g + (242 - g) * s) * tree
        img.data[i * 4 + 2] = (b + (250 - b) * s) * tree
        img.data[i * 4 + 3] = 255
      }
      this.minimapBase = img
      this.minimapSeason = w.season
    }
    ctx.putImageData(this.minimapBase, 0, 0)
    for (let i = 0; i < w.W * w.H; i++) if (w.road[i]) { ctx.fillStyle = '#b29a72'; ctx.fillRect(i % w.W, Math.floor(i / w.W), 1, 1) }
    for (const b of w.buildings) {
      const t = w.tribeById(b.tribeId)
      ctx.fillStyle = t?.color ?? '#fff'
      const s = BUILDINGS[b.type].size
      ctx.fillRect(b.x - 0.5, b.y - 0.5, s + 1, s + 1)
    }
    ctx.fillStyle = '#fff'
    for (const a of w.agents) if (a.alive) ctx.fillRect(Math.floor(a.x), Math.floor(a.y), 1, 1)
    const S = this.scale
    const vw = this.vw / S, vh = this.vh / S
    ctx.strokeStyle = 'rgba(255,255,255,0.9)'
    ctx.lineWidth = Math.max(1, w.W / 140)
    ctx.strokeRect(this.cam.x - vw / 2, this.cam.y - vh / 2, vw, vh)
  }
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

export { FEATURES }
