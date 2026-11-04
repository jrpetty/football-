// Procedurally painted sprites. Everything is drawn once into small offscreen
// canvases at 2× tile resolution and then blitted, which keeps frames cheap.

import { F } from '../sim/data.ts'
import type { BuildingType } from '../sim/buildings.ts'

export const SPR = 32 // sprite pixels per tile

type Ctx = CanvasRenderingContext2D

function canvas(w: number, h: number): [HTMLCanvasElement, Ctx] {
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  return [c, c.getContext('2d')!]
}

function shade(hex: string, f: number): string {
  const n = parseInt(hex.slice(1), 16)
  let r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255
  if (f >= 0) { r += (255 - r) * f; g += (255 - g) * f; b += (255 - b) * f }
  else { r *= 1 + f; g *= 1 + f; b *= 1 + f }
  return `rgb(${r | 0},${g | 0},${b | 0})`
}

function shadow(c: Ctx, x: number, y: number, rx: number, ry: number, a = 0.28) {
  c.fillStyle = `rgba(10,20,10,${a})`
  c.beginPath()
  c.ellipse(x, y, rx, ry, 0, 0, Math.PI * 2)
  c.fill()
}

function blob(c: Ctx, x: number, y: number, r: number, col: string) {
  c.fillStyle = col
  c.beginPath()
  c.arc(x, y, r, 0, Math.PI * 2)
  c.fill()
}

// ------------------------------------------------------------------ features

export interface FeatureSprite {
  img: HTMLCanvasElement
  ox: number // anchor offset (sprite px) from tile top-left
  oy: number
}

const featureCache = new Map<string, FeatureSprite>()

/** variant 0..3; season 0..3; empty = depleted bush */
export function featureSprite(f: number, variant: number, season: number, empty = false): FeatureSprite | null {
  const key = `${f}|${variant}|${season}|${empty ? 1 : 0}`
  const hit = featureCache.get(key)
  if (hit) return hit
  const s = makeFeature(f, variant, season, empty)
  if (s) featureCache.set(key, s)
  return s
}

function makeFeature(f: number, v: number, season: number, empty: boolean): FeatureSprite | null {
  const W = SPR * 1.6, H = SPR * 2
  const [cv, c] = canvas(W, H)
  const cx = W / 2, base = H - SPR * 0.3
  const autumn = season === 2, winter = season === 3
  const leafHues = autumn ? ['#c9692e', '#d99a35', '#b5462e', '#9b7a2a'][v] : ['#3f8a3a', '#4a9440', '#367c38', '#5a9e45'][v]
  switch (f) {
    case F.TREE: {
      shadow(c, cx, base, 11, 4)
      c.fillStyle = '#6b4a2b'
      c.fillRect(cx - 2.5, base - 16, 5, 16)
      if (winter) {
        c.strokeStyle = '#6b4a2b'
        c.lineWidth = 2
        for (let k = 0; k < 5; k++) {
          const a = -Math.PI / 2 + (k - 2) * 0.45
          c.beginPath()
          c.moveTo(cx, base - 14)
          c.lineTo(cx + Math.cos(a) * 13, base - 14 + Math.sin(a) * 13)
          c.stroke()
        }
        blob(c, cx - 4, base - 26, 3, 'rgba(240,245,255,0.9)')
        blob(c, cx + 5, base - 25, 2.5, 'rgba(240,245,255,0.9)')
      } else {
        const r = 10 + v
        blob(c, cx - 6, base - 20, r * 0.8, shade(leafHues, -0.25))
        blob(c, cx + 6, base - 21, r * 0.8, shade(leafHues, -0.2))
        blob(c, cx, base - 27, r, leafHues)
        blob(c, cx - 3, base - 30, r * 0.5, shade(leafHues, 0.18))
      }
      break
    }
    case F.PINE: {
      shadow(c, cx, base, 9, 3.5)
      c.fillStyle = '#5a3d24'
      c.fillRect(cx - 2, base - 8, 4, 8)
      const g = ['#2f5e40', '#2b5a3c', '#356848', '#2a5238'][v]
      for (let k = 0; k < 3; k++) {
        const y = base - 6 - k * 9
        const wdt = 13 - k * 3
        c.fillStyle = shade(g, k * 0.08)
        c.beginPath()
        c.moveTo(cx, y - 15)
        c.lineTo(cx + wdt, y)
        c.lineTo(cx - wdt, y)
        c.closePath()
        c.fill()
        if (winter || k === 2) {
          c.fillStyle = winter ? 'rgba(240,246,255,0.85)' : 'rgba(255,255,255,0.08)'
          c.beginPath()
          c.moveTo(cx, y - 15)
          c.lineTo(cx + wdt * 0.5, y - 7)
          c.lineTo(cx - wdt * 0.5, y - 7)
          c.closePath()
          c.fill()
        }
      }
      break
    }
    case F.PALM: {
      shadow(c, cx + 4, base, 10, 3)
      c.strokeStyle = '#8a6a3e'
      c.lineWidth = 3.5
      c.beginPath()
      c.moveTo(cx, base)
      c.quadraticCurveTo(cx + 6, base - 16, cx + 2, base - 30)
      c.stroke()
      c.strokeStyle = '#3e9248'
      c.lineWidth = 3
      for (let k = 0; k < 6; k++) {
        const a = (k / 6) * Math.PI * 2
        c.beginPath()
        c.moveTo(cx + 2, base - 30)
        c.quadraticCurveTo(cx + 2 + Math.cos(a) * 9, base - 34 + Math.sin(a) * 4, cx + 2 + Math.cos(a) * 14, base - 26 + Math.sin(a) * 6)
        c.stroke()
      }
      break
    }
    case F.JUNGLE: {
      shadow(c, cx, base, 13, 4.5)
      c.fillStyle = '#5a3f26'
      c.fillRect(cx - 3, base - 18, 6, 18)
      const g = ['#2a7a3c', '#24703a', '#2f8444', '#1f6634'][v]
      blob(c, cx - 8, base - 22, 10, shade(g, -0.2))
      blob(c, cx + 8, base - 23, 10, shade(g, -0.15))
      blob(c, cx, base - 31, 13, g)
      blob(c, cx + 4, base - 35, 6, shade(g, 0.2))
      break
    }
    case F.BUSH: {
      shadow(c, cx, base, 8, 3)
      const g = winter ? '#6f7b5e' : autumn ? '#7d8a3c' : '#3f8c3e'
      blob(c, cx - 4, base - 5, 6, shade(g, -0.15))
      blob(c, cx + 4, base - 5, 6, shade(g, -0.1))
      blob(c, cx, base - 9, 7, g)
      if (!empty && !winter) {
        const berry = ['#c2264a', '#6a2ca0', '#d33b3b', '#3949ab'][v]
        for (let k = 0; k < 7; k++) blob(c, cx - 7 + ((k * 37) % 14), base - 12 + ((k * 23) % 9), 1.6, berry)
      }
      break
    }
    case F.ROCK:
    case F.COPPER:
    case F.TIN:
    case F.IRON:
    case F.COAL:
    case F.GOLD: {
      shadow(c, cx, base, 11, 3.5)
      const g = f === F.COAL ? '#4a4a50' : f === F.IRON ? '#8a6e62' : '#8b8d92'
      c.fillStyle = shade(g, -0.2)
      c.beginPath()
      c.moveTo(cx - 12, base)
      c.lineTo(cx - 9, base - 9)
      c.lineTo(cx - 2, base - 14)
      c.lineTo(cx + 7, base - 11)
      c.lineTo(cx + 12, base - 2)
      c.lineTo(cx + 9, base + 1)
      c.closePath()
      c.fill()
      c.fillStyle = shade(g, 0.15)
      c.beginPath()
      c.moveTo(cx - 9, base - 9)
      c.lineTo(cx - 2, base - 14)
      c.lineTo(cx + 3, base - 9)
      c.lineTo(cx - 4, base - 6)
      c.closePath()
      c.fill()
      if (f !== F.ROCK) {
        const spec = f === F.COPPER ? '#e07b39' : f === F.TIN ? '#d8dde6' : f === F.IRON ? '#b5462e' : f === F.COAL ? '#111' : '#ffd23f'
        for (let k = 0; k < 6; k++) blob(c, cx - 7 + ((k * 29) % 15), base - 9 + ((k * 17) % 8), f === F.GOLD ? 1.8 : 1.5, spec)
      }
      break
    }
    case F.CLAY: {
      c.fillStyle = '#a5623f'
      c.beginPath()
      c.ellipse(cx, base - 3, 12, 5, 0, 0, Math.PI * 2)
      c.fill()
      c.fillStyle = '#c27b52'
      c.beginPath()
      c.ellipse(cx - 2, base - 5, 7, 3, 0, 0, Math.PI * 2)
      c.fill()
      break
    }
    case F.OIL: {
      c.fillStyle = '#16141a'
      c.beginPath()
      c.ellipse(cx, base - 2, 11, 5, 0, 0, Math.PI * 2)
      c.fill()
      const g = c.createLinearGradient(cx - 8, 0, cx + 8, 0)
      g.addColorStop(0, 'rgba(120,60,200,0.5)')
      g.addColorStop(0.5, 'rgba(60,200,160,0.4)')
      g.addColorStop(1, 'rgba(220,180,60,0.4)')
      c.fillStyle = g
      c.beginPath()
      c.ellipse(cx - 2, base - 3, 6, 2, 0, 0, Math.PI * 2)
      c.fill()
      break
    }
    case F.FIBER: {
      c.strokeStyle = winter ? '#a59c76' : autumn ? '#b8a64a' : '#6aa84f'
      c.lineWidth = 1.4
      for (let k = 0; k < 9; k++) {
        const x = cx - 9 + k * 2.2
        c.beginPath()
        c.moveTo(x, base)
        c.quadraticCurveTo(x + 2, base - 8, x + (k % 2 ? 3 : -2), base - 13 - (k % 3) * 2)
        c.stroke()
      }
      c.fillStyle = '#8a6a3e'
      for (let k = 0; k < 3; k++) c.fillRect(cx - 6 + k * 6, base - 17 - (k % 2) * 2, 1.6, 4)
      break
    }
    case F.CACTUS: {
      shadow(c, cx, base, 6, 2)
      c.fillStyle = '#4c8a4a'
      c.fillRect(cx - 2.5, base - 18, 5, 18)
      c.fillRect(cx - 8, base - 12, 3, 7)
      c.fillRect(cx - 8, base - 8, 6, 3)
      c.fillRect(cx + 5, base - 15, 3, 8)
      c.fillRect(cx + 2, base - 10, 6, 3)
      break
    }
    case F.SAPLING: {
      c.strokeStyle = '#6b4a2b'
      c.lineWidth = 1.5
      c.beginPath()
      c.moveTo(cx, base)
      c.lineTo(cx, base - 8)
      c.stroke()
      blob(c, cx, base - 9, 3.5, winter ? '#7d806a' : '#5aa64a')
      break
    }
    case F.FLOWERS: {
      if (winter) return null
      const cols = ['#f4d35e', '#ee6c9a', '#ffffff', '#b28dff']
      for (let k = 0; k < 7; k++) blob(c, cx - 9 + ((k * 31) % 18), base - 2 - ((k * 13) % 7), 1.6, cols[(k + v) % 4])
      break
    }
    default:
      return null
  }
  return { img: cv, ox: (SPR - W) / 2, oy: SPR - H + SPR * 0.15 }
}

// ------------------------------------------------------------------ buildings

export interface BuildingSprite {
  img: HTMLCanvasElement
  ox: number
  oy: number
}

const bCache = new Map<string, BuildingSprite>()

export function buildingSprite(type: BuildingType, color: string, size: number, era: number): BuildingSprite {
  const key = `${type}|${color}|${era > 5 ? 1 : 0}`
  const hit = bCache.get(key)
  if (hit) return hit
  const s = makeBuilding(type, color, size, era)
  bCache.set(key, s)
  return s
}

function roofed(c: Ctx, x: number, y: number, w: number, h: number, wall: string, roof: string, roofH: number) {
  // walls
  c.fillStyle = wall
  c.fillRect(x, y - h, w, h)
  c.fillStyle = shade(wall, -0.18)
  c.fillRect(x + w * 0.62, y - h, w * 0.38, h)
  // roof
  c.fillStyle = roof
  c.beginPath()
  c.moveTo(x - 3, y - h)
  c.lineTo(x + w / 2, y - h - roofH)
  c.lineTo(x + w + 3, y - h)
  c.closePath()
  c.fill()
  c.fillStyle = shade(roof, -0.22)
  c.beginPath()
  c.moveTo(x + w / 2, y - h - roofH)
  c.lineTo(x + w + 3, y - h)
  c.lineTo(x + w / 2 + 2, y - h)
  c.closePath()
  c.fill()
}

function door(c: Ctx, x: number, y: number, w = 5, h = 8) {
  c.fillStyle = '#3d2a1a'
  c.fillRect(x - w / 2, y - h, w, h)
}

function windows(c: Ctx, x: number, y: number, cols: number, rows: number, cw: number, ch: number, col = '#9fd3ff') {
  for (let r = 0; r < rows; r++) for (let k = 0; k < cols; k++) {
    c.fillStyle = col
    c.fillRect(x + k * cw, y + r * ch, cw * 0.55, ch * 0.55)
  }
}

function flag(c: Ctx, x: number, y: number, color: string) {
  c.strokeStyle = '#3b3b3b'
  c.lineWidth = 1.2
  c.beginPath()
  c.moveTo(x, y)
  c.lineTo(x, y - 14)
  c.stroke()
  c.fillStyle = color
  c.beginPath()
  c.moveTo(x, y - 14)
  c.lineTo(x + 9, y - 11)
  c.lineTo(x, y - 8)
  c.closePath()
  c.fill()
}

function makeBuilding(type: BuildingType, color: string, size: number, era: number): BuildingSprite {
  const T = SPR * size
  const W = T + SPR, H = T + SPR * (type === 'skyscraper' ? 4 : type === 'launch_pad' ? 3.2 : type === 'watchtower' || type === 'power_plant' || type === 'factory' ? 2 : 1.4)
  const [cv, c] = canvas(W, H)
  const x0 = SPR / 2, y1 = H - SPR * 0.2 // footprint left & bottom
  const cx = x0 + T / 2
  const modern = era > 5
  shadow(c, cx + 2, y1 - 2, T * 0.48, T * 0.16, 0.22)
  switch (type) {
    case 'campfire': {
      for (let k = 0; k < 8; k++) {
        const a = (k / 8) * Math.PI * 2
        blob(c, cx + Math.cos(a) * 7, y1 - 8 + Math.sin(a) * 3.5, 2.6, '#8f8f93')
      }
      c.fillStyle = '#5a3a20'
      c.fillRect(cx - 7, y1 - 9, 14, 2.4)
      c.save(); c.translate(cx, y1 - 8); c.rotate(0.7); c.fillRect(-7, -1.2, 14, 2.4); c.restore()
      break
    }
    case 'lean_to': {
      c.strokeStyle = '#7a5530'
      c.lineWidth = 2
      for (let k = 0; k < 6; k++) {
        c.beginPath()
        c.moveTo(x0 + 4 + k * 4, y1 - 3)
        c.lineTo(cx + 6, y1 - 22)
        c.stroke()
      }
      c.fillStyle = 'rgba(120,150,70,0.85)'
      c.beginPath()
      c.moveTo(x0 + 2, y1 - 3)
      c.lineTo(cx + 6, y1 - 23)
      c.lineTo(x0 + T - 2, y1 - 3)
      c.closePath()
      c.fill()
      break
    }
    case 'hut': {
      c.fillStyle = '#b08a5a'
      c.beginPath()
      c.ellipse(cx, y1 - 9, 12, 9, 0, 0, Math.PI)
      c.fillRect(cx - 12, y1 - 18, 24, 9)
      c.fill()
      c.fillStyle = '#c9a86a'
      c.beginPath()
      c.moveTo(cx - 15, y1 - 16)
      c.lineTo(cx, y1 - 34)
      c.lineTo(cx + 15, y1 - 16)
      c.closePath()
      c.fill()
      c.fillStyle = shade(color, -0.1)
      c.fillRect(cx - 15, y1 - 17.5, 30, 2.5)
      door(c, cx, y1 - 2, 6, 9)
      break
    }
    case 'house': {
      roofed(c, x0 + 3, y1 - 2, T - 6, 16, modern ? '#d9d4c9' : '#a9a49a', shade(color, -0.15), 12)
      door(c, x0 + 9, y1 - 2)
      windows(c, x0 + 15, y1 - 14, 2, 1, 5, 6, '#2a3540')
      break
    }
    case 'apartment': {
      c.fillStyle = '#c6c1b8'
      c.fillRect(x0 + 4, y1 - 2 - T * 1.2, T - 8, T * 1.2)
      c.fillStyle = '#a9a39a'
      c.fillRect(x0 + T * 0.62, y1 - 2 - T * 1.2, T * 0.38 - 4, T * 1.2)
      windows(c, x0 + 9, y1 - T * 1.2 + 4, 6, 7, 8, 10, '#2c3a4a')
      c.fillStyle = color
      c.fillRect(x0 + 4, y1 - 2 - T * 1.2, T - 8, 3)
      break
    }
    case 'skyscraper': {
      const h = T * 2.6
      const g = c.createLinearGradient(x0, 0, x0 + T, 0)
      g.addColorStop(0, '#5c7a99'); g.addColorStop(0.6, '#9cc0de'); g.addColorStop(1, '#46617c')
      c.fillStyle = g
      c.fillRect(x0 + 8, y1 - 2 - h, T - 16, h)
      c.fillStyle = 'rgba(255,255,255,0.25)'
      for (let y = y1 - h; y < y1 - 4; y += 6) c.fillRect(x0 + 8, y, T - 16, 1)
      c.fillStyle = color
      c.fillRect(x0 + 8, y1 - 2 - h, T - 16, 4)
      c.strokeStyle = '#ccc'
      c.beginPath(); c.moveTo(cx, y1 - 2 - h); c.lineTo(cx, y1 - 14 - h); c.stroke()
      break
    }
    case 'stockpile': {
      c.fillStyle = '#7a5530'
      for (let k = 0; k < 4; k++) { c.beginPath(); c.ellipse(x0 + 8 + k * 4, y1 - 5 - (k % 2) * 4, 3, 3, 0, 0, Math.PI * 2); c.fill() }
      c.fillStyle = '#a07a4a'
      c.fillRect(cx + 1, y1 - 13, 11, 11)
      c.strokeStyle = '#6a4a28'
      c.strokeRect(cx + 1, y1 - 13, 11, 11)
      c.beginPath(); c.moveTo(cx + 1, y1 - 13); c.lineTo(cx + 12, y1 - 2); c.stroke()
      break
    }
    case 'granary': {
      c.fillStyle = '#c79a66'
      c.fillRect(cx - 9, y1 - 22, 18, 20)
      c.beginPath(); c.ellipse(cx, y1 - 22, 9, 9, 0, Math.PI, 0); c.fill()
      c.fillStyle = shade('#c79a66', -0.2)
      c.fillRect(cx + 3, y1 - 22, 6, 20)
      c.fillStyle = color
      c.fillRect(cx - 9, y1 - 14, 18, 2)
      door(c, cx - 2, y1 - 2, 5, 7)
      break
    }
    case 'warehouse': {
      c.fillStyle = '#8a5a44'
      c.fillRect(x0 + 4, y1 - 26, T - 8, 24)
      c.fillStyle = '#6d7680'
      c.beginPath(); c.moveTo(x0, y1 - 26); c.lineTo(cx, y1 - 38); c.lineTo(x0 + T, y1 - 26); c.closePath(); c.fill()
      c.fillStyle = '#3d3d3d'
      c.fillRect(cx - 8, y1 - 16, 16, 14)
      break
    }
    case 'farm':
    case 'pasture':
      break // drawn dynamically (growth)
    case 'well': {
      c.fillStyle = '#8f8f93'
      c.beginPath(); c.ellipse(cx, y1 - 7, 9, 5, 0, 0, Math.PI * 2); c.fill()
      c.fillStyle = '#2a4c6e'
      c.beginPath(); c.ellipse(cx, y1 - 8, 6, 3, 0, 0, Math.PI * 2); c.fill()
      c.strokeStyle = '#6b4a2b'; c.lineWidth = 2
      c.beginPath(); c.moveTo(cx - 8, y1 - 6); c.lineTo(cx - 8, y1 - 22); c.moveTo(cx + 8, y1 - 6); c.lineTo(cx + 8, y1 - 22); c.stroke()
      c.fillStyle = shade(color, -0.2)
      c.beginPath(); c.moveTo(cx - 11, y1 - 21); c.lineTo(cx, y1 - 28); c.lineTo(cx + 11, y1 - 21); c.closePath(); c.fill()
      break
    }
    case 'workshop': {
      roofed(c, x0 + 3, y1 - 2, T - 6, 14, '#9c7044', '#6d4b2c', 9)
      c.fillStyle = '#c9c9c9'
      c.fillRect(x0 + T - 9, y1 - 8, 6, 2)
      door(c, x0 + 10, y1 - 2)
      break
    }
    case 'kiln': {
      c.fillStyle = '#a65f3e'
      c.beginPath(); c.ellipse(cx, y1 - 6, 12, 14, 0, Math.PI, 0); c.fill()
      c.fillRect(cx - 12, y1 - 6, 24, 4)
      c.fillStyle = '#ff9a3c'
      c.beginPath(); c.ellipse(cx, y1 - 5, 4, 4, 0, Math.PI, 0); c.fill()
      c.fillStyle = '#7a4430'
      c.fillRect(cx + 4, y1 - 22, 4, 8)
      break
    }
    case 'smithy': {
      roofed(c, x0 + 2, y1 - 2, T - 4, 15, '#8b8b8f', '#5a5a5e', 9)
      c.fillStyle = '#6f6f73'
      c.fillRect(x0 + T - 9, y1 - 34, 5, 12)
      c.fillStyle = '#ff7a2a'
      c.fillRect(x0 + 7, y1 - 10, 7, 6)
      break
    }
    case 'mine': {
      c.fillStyle = '#6d6a62'
      c.beginPath(); c.moveTo(x0, y1 - 2); c.lineTo(cx, y1 - 24); c.lineTo(x0 + T, y1 - 2); c.closePath(); c.fill()
      c.fillStyle = '#161616'
      c.beginPath(); c.ellipse(cx, y1 - 3, 6, 9, 0, Math.PI, 0); c.fill()
      c.strokeStyle = '#7a5530'; c.lineWidth = 2.5
      c.beginPath(); c.moveTo(cx - 7, y1 - 2); c.lineTo(cx - 7, y1 - 13); c.lineTo(cx + 7, y1 - 13); c.lineTo(cx + 7, y1 - 2); c.stroke()
      break
    }
    case 'library': {
      c.fillStyle = '#d8cfbd'
      c.fillRect(x0 + 2, y1 - 18, T - 4, 16)
      c.fillStyle = '#efe7d6'
      for (let k = 0; k < 4; k++) c.fillRect(x0 + 5 + k * 6, y1 - 17, 2.5, 14)
      c.fillStyle = shade(color, -0.1)
      c.beginPath(); c.moveTo(x0, y1 - 18); c.lineTo(cx, y1 - 27); c.lineTo(x0 + T, y1 - 18); c.closePath(); c.fill()
      break
    }
    case 'temple': {
      c.fillStyle = '#cdbf9f'
      c.fillRect(x0 + 1, y1 - 7, T - 2, 5)
      c.fillRect(x0 + 5, y1 - 14, T - 10, 7)
      c.fillRect(x0 + 9, y1 - 21, T - 18, 7)
      c.fillStyle = '#e8c547'
      c.beginPath(); c.moveTo(cx - 5, y1 - 21); c.lineTo(cx, y1 - 30); c.lineTo(cx + 5, y1 - 21); c.closePath(); c.fill()
      c.fillStyle = shade(color, -0.1)
      c.fillRect(cx - 2, y1 - 12, 4, 10)
      break
    }
    case 'market': {
      const cols = [color, '#f4d35e', '#ffffff']
      for (let k = 0; k < 3; k++) {
        const x = x0 + 2 + k * 9.5
        c.fillStyle = '#7a5530'
        c.fillRect(x + 1, y1 - 12, 1.5, 10)
        c.fillRect(x + 7, y1 - 12, 1.5, 10)
        c.fillStyle = cols[k]
        c.beginPath(); c.moveTo(x - 1, y1 - 12); c.lineTo(x + 4.5, y1 - 18); c.lineTo(x + 10, y1 - 12); c.closePath(); c.fill()
        c.fillStyle = ['#d65a31', '#7cb342', '#ffb300'][k]
        c.fillRect(x + 2, y1 - 6, 5, 3)
      }
      break
    }
    case 'town_hall': {
      c.fillStyle = modern ? '#e3ddd1' : '#bfb4a0'
      c.fillRect(x0 + 6, y1 - 30, T - 12, 28)
      c.fillStyle = shade(modern ? '#e3ddd1' : '#bfb4a0', -0.18)
      c.fillRect(x0 + T * 0.62, y1 - 30, T * 0.38 - 6, 28)
      c.fillStyle = '#efe7d6'
      for (let k = 0; k < 6; k++) c.fillRect(x0 + 10 + k * 8, y1 - 26, 3, 22)
      c.fillStyle = shade(color, -0.1)
      c.beginPath(); c.moveTo(x0 + 2, y1 - 30); c.lineTo(cx, y1 - 44); c.lineTo(x0 + T - 2, y1 - 30); c.closePath(); c.fill()
      c.fillStyle = '#d8b84a'
      c.beginPath(); c.ellipse(cx, y1 - 44, 7, 7, 0, Math.PI, 0); c.fill()
      flag(c, cx, y1 - 50, color)
      door(c, cx, y1 - 2, 7, 11)
      break
    }
    case 'watchtower': {
      c.fillStyle = '#8f8a80'
      c.fillRect(cx - 6, y1 - 46, 12, 44)
      c.fillStyle = shade('#8f8a80', -0.2)
      c.fillRect(cx + 1, y1 - 46, 5, 44)
      c.fillStyle = '#7a756c'
      for (let k = 0; k < 4; k++) c.fillRect(cx - 8 + k * 4.5, y1 - 51, 3, 5)
      flag(c, cx, y1 - 51, color)
      break
    }
    case 'dock': {
      c.fillStyle = '#8a6a44'
      c.fillRect(x0, y1 - 14, T, 10)
      c.strokeStyle = '#5a4026'
      for (let k = 0; k < 6; k++) { c.beginPath(); c.moveTo(x0 + k * 6, y1 - 14); c.lineTo(x0 + k * 6, y1 - 4); c.stroke() }
      c.fillStyle = '#6b4a2b'
      c.beginPath(); c.moveTo(x0 + 6, y1 - 18); c.lineTo(x0 + 26, y1 - 18); c.lineTo(x0 + 22, y1 - 14); c.lineTo(x0 + 10, y1 - 14); c.closePath(); c.fill()
      c.fillStyle = '#f2efe6'
      c.beginPath(); c.moveTo(x0 + 16, y1 - 32); c.lineTo(x0 + 16, y1 - 19); c.lineTo(x0 + 24, y1 - 19); c.closePath(); c.fill()
      break
    }
    case 'mill': {
      roofed(c, x0 + 6, y1 - 2, T - 12, 18, '#c9b48e', '#7a5530', 8)
      break // blades drawn dynamically
    }
    case 'university': {
      c.fillStyle = '#b5523b'
      c.fillRect(x0 + 4, y1 - 28, T - 8, 26)
      c.fillStyle = shade('#b5523b', -0.2)
      c.fillRect(x0 + T * 0.62, y1 - 28, T * 0.38 - 4, 26)
      windows(c, x0 + 8, y1 - 24, 7, 2, 7, 10, '#f3e3b3')
      c.fillStyle = '#d8d0c0'
      c.beginPath(); c.ellipse(cx, y1 - 28, 12, 12, 0, Math.PI, 0); c.fill()
      flag(c, cx, y1 - 40, color)
      break
    }
    case 'hospital': {
      c.fillStyle = '#f2f2ee'
      c.fillRect(x0 + 2, y1 - 22, T - 4, 20)
      c.fillStyle = '#d4d4d0'
      c.fillRect(x0 + T * 0.62, y1 - 22, T * 0.38 - 2, 20)
      c.fillStyle = '#e53935'
      c.fillRect(cx - 2, y1 - 19, 4, 11)
      c.fillRect(cx - 5.5, y1 - 15.5, 11, 4)
      break
    }
    case 'factory': {
      c.fillStyle = '#9c4f3a'
      c.fillRect(x0 + 2, y1 - 26, T - 4, 24)
      c.fillStyle = '#7a3e2e'
      for (let k = 0; k < 4; k++) { c.beginPath(); c.moveTo(x0 + 2 + k * 15, y1 - 26); c.lineTo(x0 + 2 + k * 15, y1 - 34); c.lineTo(x0 + 17 + k * 15, y1 - 26); c.closePath(); c.fill() }
      c.fillStyle = '#6a6a6a'
      c.fillRect(x0 + T - 14, y1 - 62, 6, 36)
      c.fillRect(x0 + T - 24, y1 - 54, 5, 28)
      windows(c, x0 + 6, y1 - 20, 6, 1, 9, 10, '#f6d27a')
      break
    }
    case 'power_plant': {
      c.fillStyle = '#b9bcc2'
      c.beginPath()
      c.moveTo(x0 + 6, y1 - 2); c.quadraticCurveTo(x0 + 14, y1 - 30, x0 + 8, y1 - 52); c.lineTo(x0 + 30, y1 - 52); c.quadraticCurveTo(x0 + 24, y1 - 30, x0 + 32, y1 - 2)
      c.closePath(); c.fill()
      c.fillStyle = '#9ea1a7'
      c.fillRect(x0 + 36, y1 - 22, T - 40, 20)
      c.fillStyle = color
      c.fillRect(x0 + 36, y1 - 22, T - 40, 3)
      c.fillStyle = '#ffeb3b'
      c.beginPath(); c.moveTo(x0 + 50, y1 - 18); c.lineTo(x0 + 46, y1 - 10); c.lineTo(x0 + 50, y1 - 10); c.lineTo(x0 + 47, y1 - 4); c.lineTo(x0 + 54, y1 - 13); c.lineTo(x0 + 50, y1 - 13); c.closePath(); c.fill()
      break
    }
    case 'lab': {
      c.fillStyle = '#eef1f4'
      c.fillRect(x0 + 2, y1 - 20, T - 4, 18)
      c.fillStyle = '#5fb4e6'
      c.fillRect(x0 + 4, y1 - 16, T - 8, 6)
      c.fillStyle = '#ccd3d9'
      c.beginPath(); c.ellipse(x0 + T - 9, y1 - 20, 6, 6, 0, Math.PI, 0); c.fill()
      break
    }
    case 'data_center': {
      c.fillStyle = '#2f3540'
      c.fillRect(x0 + 4, y1 - 24, T - 8, 22)
      c.fillStyle = '#232830'
      c.fillRect(x0 + T * 0.62, y1 - 24, T * 0.38 - 4, 22)
      for (let k = 0; k < 18; k++) { c.fillStyle = k % 3 ? '#4fc3f7' : '#69f0ae'; c.fillRect(x0 + 8 + (k % 9) * 6, y1 - 20 + Math.floor(k / 9) * 8, 2, 2) }
      c.fillStyle = color
      c.fillRect(x0 + 4, y1 - 24, T - 8, 2)
      break
    }
    case 'road':
      break
    case 'monument': {
      c.fillStyle = '#d9d3c4'
      c.fillRect(cx - 9, y1 - 6, 18, 4)
      c.fillStyle = '#e8e2d2'
      c.beginPath(); c.moveTo(cx - 5, y1 - 6); c.lineTo(cx - 2, y1 - 40); c.lineTo(cx + 2, y1 - 40); c.lineTo(cx + 5, y1 - 6); c.closePath(); c.fill()
      c.fillStyle = '#e8c547'
      c.beginPath(); c.moveTo(cx - 2, y1 - 40); c.lineTo(cx, y1 - 45); c.lineTo(cx + 2, y1 - 40); c.closePath(); c.fill()
      break
    }
    case 'launch_pad': {
      c.fillStyle = '#7d8187'
      c.fillRect(x0 + 2, y1 - 8, T - 4, 6)
      c.strokeStyle = '#c94a3a'; c.lineWidth = 2
      c.beginPath(); c.moveTo(x0 + 12, y1 - 8); c.lineTo(x0 + 12, y1 - 80); c.stroke()
      for (let y = y1 - 76; y < y1 - 10; y += 8) { c.beginPath(); c.moveTo(x0 + 12, y); c.lineTo(x0 + 20, y + 4); c.stroke() }
      c.fillStyle = '#f2f2f2'
      c.fillRect(cx - 4, y1 - 78, 8, 70)
      c.beginPath(); c.moveTo(cx - 4, y1 - 78); c.lineTo(cx, y1 - 90); c.lineTo(cx + 4, y1 - 78); c.closePath(); c.fill()
      c.fillStyle = color
      c.fillRect(cx - 4, y1 - 50, 8, 4)
      c.fillStyle = '#333'
      c.beginPath(); c.moveTo(cx - 4, y1 - 16); c.lineTo(cx - 9, y1 - 8); c.lineTo(cx - 4, y1 - 8); c.closePath(); c.fill()
      c.beginPath(); c.moveTo(cx + 4, y1 - 16); c.lineTo(cx + 9, y1 - 8); c.lineTo(cx + 4, y1 - 8); c.closePath(); c.fill()
      break
    }
  }
  return { img: cv, ox: -SPR / 2, oy: -(H - T - SPR * 0.2) }
}

export { shade }
