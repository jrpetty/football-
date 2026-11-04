// Articulated, animated people and animals for the world map.
//
// Each person is a small rig — hips, knees, shoulders, elbows, head — posed
// every frame from what they are doing: a proper walk cycle, idle breathing
// and blinking, and distinct work animations (chopping, mining, picking,
// fishing, reading, eating, talking, cheering, kneeling, sleeping). Drawn in
// unit space (feet at 0, head-top near -1) and scaled to the zoom, with
// outlines, two-tone shading, era-appropriate clothes and the right tool.

import type { Agent, Animal } from '../sim/types.ts'

export const SKIN = ['#f3cfae', '#e3b089', '#c98d5e', '#a06b44', '#7c4f31', '#5a3923']
export const HAIR = ['#2b1d14', '#4d3020', '#7d4b25', '#b57c3d', '#dcb56b', '#161616', '#8f3b22']
const INK = 'rgba(24,16,12,0.78)'

export function shadeHex(hex: string, f: number): string {
  const n = parseInt(hex.slice(1), 16)
  let r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255
  if (f >= 0) { r += (255 - r) * f; g += (255 - g) * f; b += (255 - b) * f }
  else { r *= 1 + f; g *= 1 + f; b *= 1 + f }
  return `rgb(${r | 0},${g | 0},${b | 0})`
}

export function hash(n: number): number {
  let h = Math.imul(n ^ 0x9e3779b9, 0x85ebca6b)
  h ^= h >>> 13
  h = Math.imul(h, 0xc2b2ae35)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}

export type Anim = 'idle' | 'walk' | 'chop' | 'mine' | 'pick' | 'fish' | 'read' | 'craft' | 'eat' | 'talk' | 'cheer' | 'kneel' | 'hunt' | 'sleep' | 'carry'

/** What animation a person's current activity calls for. */
export type ToolKind = 'axe' | 'pick' | 'hammer' | 'spear' | 'rod' | 'book' | 'hoe' | 'staff' | 'sword' | 'bow' | 'rifle' | 'flag' | 'club'
export function animFor(a: Agent): { anim: Anim; tool: ToolKind | null } {
  const t = a.task
  if (!t) return { anim: 'idle', tool: null }
  if (t.action === 'sleep' && t.phase === 'work') return { anim: 'sleep', tool: null }
  if (t.action === 'war') {
    // warriors carry their era's weapon; the first of a column carries the banner
    const tool = (['sword', 'bow', 'rifle', 'flag', 'club', 'spear'].includes(t.text) ? t.text : 'spear') as ToolKind
    if (t.phase === 'travel' || a.moving) return { anim: 'walk', tool }
    return { anim: t.label.startsWith('Fighting') && tool !== 'flag' ? 'hunt' : 'idle', tool }
  }
  if (t.phase === 'travel' || a.moving) {
    if (t.action === 'hunt') return { anim: 'walk', tool: 'spear' }
    if (t.action === 'explore') return { anim: 'walk', tool: 'staff' }
    return { anim: Object.keys(a.inv).length ? 'carry' : 'walk', tool: null }
  }
  switch (t.action) {
    case 'gather': {
      const item = t.target
      if (item === 'wood') return { anim: 'chop', tool: 'axe' }
      if (item === 'stone' || item === 'copper' || item === 'tin' || item === 'iron_ore' || item === 'coal' || item === 'gold') return { anim: 'mine', tool: 'pick' }
      return { anim: 'pick', tool: null }
    }
    case 'build': return { anim: 'chop', tool: 'hammer' }
    case 'farm': return { anim: t.label.startsWith('Harvest') || t.label.startsWith('Plant') ? 'pick' : 'chop', tool: t.label.startsWith('Tend') ? 'hoe' : null }
    case 'hunt': return { anim: 'hunt', tool: 'spear' }
    case 'fish': return { anim: 'fish', tool: 'rod' }
    case 'research': case 'write': case 'teach': return { anim: 'read', tool: 'book' }
    case 'craft': return { anim: 'craft', tool: 'hammer' }
    case 'eat': case 'drink': return { anim: 'eat', tool: null }
    case 'talk': case 'court': case 'trade': case 'give': case 'decree': case 'join_tribe': case 'diplomacy': return { anim: 'talk', tool: null }
    case 'celebrate': return { anim: 'cheer', tool: null }
    case 'heal': return { anim: 'kneel', tool: null }
    case 'attack': return { anim: 'hunt', tool: 'spear' }
    default: return { anim: 'idle', tool: null }
  }
}

export interface Pose {
  lean: number
  bob: number
  hipDrop: number
  legs: [number, number][] // [thigh, knee bend] back, front
  arms: [number, number][] // [upper, elbow] back, front
  head: number
  mouth: number
  impact: boolean
  hop: number
}

function ease(x: number) {
  return x * x * (3 - 2 * x)
}

export function pose(anim: Anim, t: number, seed: number): Pose {
  const P: Pose = { lean: 0, bob: 0, hipDrop: 0, legs: [[0.03, 0.02], [-0.03, 0.02]], arms: [[0.1, 0.18], [-0.05, 0.22]], head: 0, mouth: 0, impact: false, hop: 0 }
  const breathe = Math.sin(t * 2.2 + seed * 6) * 0.006
  switch (anim) {
    case 'walk':
    case 'carry': {
      const p = t * 8.5 + seed * 10
      for (let i = 0; i < 2; i++) {
        const ph = p + i * Math.PI
        P.legs[i] = [Math.sin(ph) * 0.5, Math.max(0, Math.sin(ph + 1.3)) * 0.95]
        if (anim === 'walk') P.arms[i] = [-Math.sin(ph) * 0.45, 0.25 + Math.max(0, -Math.sin(ph)) * 0.35]
      }
      if (anim === 'carry') P.arms = [[-0.6, 1.9], [-0.5, 1.8]]
      P.bob = Math.abs(Math.cos(p)) * 0.03
      P.lean = anim === 'carry' ? 0.14 : 0.07
      break
    }
    case 'idle': {
      const shift = Math.sin(t * 0.6 + seed * 9)
      P.legs = [[0.05 + shift * 0.03, 0.02], [-0.06 + shift * 0.03, 0.03]]
      P.arms = [[0.08 + breathe * 2, 0.15], [-0.04 - breathe * 2, 0.2]]
      P.head = Math.sin(t * 0.37 + seed * 5) > 0.7 ? 0.12 : Math.sin(t * 0.41 + seed * 3) < -0.85 ? -0.1 : 0
      P.bob = breathe
      break
    }
    case 'chop':
    case 'mine': {
      const c = (t * (anim === 'mine' ? 1.25 : 1.45) + seed) % 1
      // slow wind-up over the head, fast strike down and forward
      const up = 3.4, down = anim === 'mine' ? 1.0 : 0.85
      const a = c < 0.62 ? down + (up - down) * ease(c / 0.62) : c < 0.76 ? up - (up - down) * ease((c - 0.62) / 0.14) : down
      P.arms = [[a - 0.15, 0.25], [a, 0.18]]
      P.lean = c > 0.62 && c < 0.9 ? 0.22 : 0.02
      P.legs = [[0.22, 0.12], [-0.18, 0.15]]
      P.hipDrop = c > 0.7 && c < 0.85 ? 0.02 : 0
      P.impact = c > 0.75 && c < 0.8
      break
    }
    case 'pick': {
      const r = t * 3 + seed * 7
      P.lean = 0.5
      P.hipDrop = 0.05
      P.legs = [[0.32, 0.5], [0.12, 0.4]]
      P.arms = [[0.3 + Math.sin(r) * 0.22, 0.15], [0.42 + Math.sin(r + 2) * 0.25, 0.12]]
      P.head = 0.2
      break
    }
    case 'fish': {
      const flick = Math.max(0, Math.sin(t * 0.9 + seed * 4) - 0.85) * 6
      P.arms = [[0.9, 0.9], [1.25 + flick * 0.3, 0.6 - flick * 0.2]]
      P.legs = [[0.12, 0.05], [-0.12, 0.05]]
      P.lean = -0.05
      P.bob = breathe
      break
    }
    case 'read': {
      P.arms = [[0.55, 1.35], [0.45, 1.5]]
      P.head = 0.22 + Math.sin(t * 0.8 + seed) * 0.05
      P.bob = breathe
      if (Math.sin(t * 0.33 + seed * 11) > 0.93) { P.arms[1] = [2.6, 0.4]; P.head = -0.1 } // a thought strikes
      break
    }
    case 'craft': {
      const c = (t * 2.4 + seed) % 1
      P.lean = 0.35
      P.hipDrop = 0.12
      P.legs = [[0.75, 1.4], [0.55, 1.2]]
      P.arms = [[0.7, 0.9], [c < 0.5 ? 0.9 + c * 1.4 : 1.6 - (c - 0.5) * 1.4, 0.7]]
      P.impact = c > 0.95
      P.head = 0.3
      break
    }
    case 'eat': {
      const c = (Math.sin(t * 2.2 + seed * 5) + 1) / 2
      P.arms = [[0.2, 0.3], [0.35 + c * 0.25, 1.0 + c * 1.6]]
      P.mouth = c > 0.8 ? 1 : 0
      P.bob = breathe
      break
    }
    case 'talk': {
      const g = Math.sin(t * 3.1 + seed * 8)
      const g2 = Math.sin(t * 2.3 + seed * 3)
      P.arms = [[0.15 + Math.max(0, g2) * 0.6, 0.4 + Math.max(0, g2) * 0.8], [0.5 + g * 0.45, 0.9 + g * 0.6]]
      P.mouth = (Math.sin(t * 13 + seed * 2) + 1) / 2
      P.head = Math.sin(t * 1.7 + seed) * 0.08
      P.bob = Math.abs(Math.sin(t * 3.1)) * 0.01
      break
    }
    case 'cheer': {
      const j = Math.abs(Math.sin(t * 5 + seed * 4))
      P.hop = j * 0.12
      P.arms = [[2.7 + Math.sin(t * 9) * 0.2, 0.3], [2.9 + Math.sin(t * 9 + 1) * 0.2, 0.3]]
      P.legs = [[0.15 * j, 0.4 * j], [-0.1 * j, 0.5 * j]]
      P.mouth = 1
      P.head = -0.1
      break
    }
    case 'kneel': {
      P.hipDrop = 0.16
      P.legs = [[0.9, 1.9], [0.15, 1.2]]
      P.lean = 0.3
      P.arms = [[0.9 + Math.sin(t * 2) * 0.1, 0.5], [1.0 + Math.sin(t * 2 + 1) * 0.1, 0.4]]
      P.head = 0.3
      break
    }
    case 'hunt': {
      const c = (t * 1.6 + seed) % 1
      const thrust = c > 0.7 && c < 0.85
      P.lean = thrust ? 0.3 : 0.18
      P.hipDrop = 0.06
      P.legs = [[0.45, 0.6], [-0.35, 0.3]]
      P.arms = [[1.3, thrust ? 0.1 : 0.9], [1.4, thrust ? 0.05 : 0.8]]
      P.impact = c > 0.78 && c < 0.82
      break
    }
    case 'sleep':
      break
  }
  return P
}

export interface DrawPersonOpts {
  x: number
  y: number
  h: number // pixel height of an adult standing
  t: number // seconds
  a: Agent
  era: number
  tribeColor: string
  leader: boolean
  age: number
  toolTier: number
}

/** Returns a world-space point where impact particles should spawn, if any. */
export function drawPerson(ctx: CanvasRenderingContext2D, o: DrawPersonOpts): { impact: { x: number; y: number } | null; headY: number } {
  const { a, era, age } = o
  const L = a.look
  const child = age < 14
  const elder = age > 60
  const grow = child ? 0.5 + (age / 14) * 0.38 : 1
  const H = o.h * L.height * grow
  const { anim, tool } = animFor(a)
  const seed = hash(a.id)
  const P = pose(anim, o.t, seed)
  if (elder && anim !== 'sleep') P.lean += 0.1
  const dir = a.dir < 0 ? -1 : 1
  const skin = SKIN[L.skin % SKIN.length]
  const hair = elder ? '#d6d6d6' : age > 48 ? '#8f8a84' : HAIR[L.hair % HAIR.length]
  const outfit = clothesFor(era, a, o.tribeColor, child)

  ctx.save()
  ctx.translate(o.x, o.y)
  // ground shadow
  ctx.fillStyle = 'rgba(10,14,8,0.3)'
  ctx.beginPath()
  ctx.ellipse(0, 0, H * (anim === 'sleep' ? 0.42 : 0.17) * L.build, H * 0.055, 0, 0, Math.PI * 2)
  ctx.fill()
  if (anim === 'sleep') {
    drawSleeper(ctx, H, o.t, seed, skin, hair, outfit, dir)
    ctx.restore()
    return { impact: null, headY: o.y - H * 0.2 }
  }
  ctx.scale(H * dir, H)
  ctx.translate(0, -P.hop)
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  const lw = 0.03
  const bw = L.build
  const headR = child ? 0.135 : 0.105
  const hipY = -0.47 + P.hipDrop - P.bob
  const sh = { x: Math.sin(P.lean) * 0.3, y: hipY - Math.cos(P.lean) * 0.29 } // shoulders
  const thigh = 0.245, shin = 0.235, upper = 0.165, fore = 0.155

  const limb = (x: number, y: number, a1: number, l1: number, bend: number, l2: number, w: number, col: string, out = true) => {
    const kx = x + Math.sin(a1) * l1, ky = y + Math.cos(a1) * l1
    const a2 = a1 + bend
    const fx = kx + Math.sin(a2) * l2, fy = ky + Math.cos(a2) * l2
    if (out) {
      ctx.strokeStyle = INK
      ctx.lineWidth = w + lw
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(kx, ky); ctx.lineTo(fx, fy); ctx.stroke()
    }
    ctx.strokeStyle = col
    ctx.lineWidth = w
    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(kx, ky); ctx.lineTo(fx, fy); ctx.stroke()
    return { kx, ky, fx, fy, a2 }
  }
  const leg = (i: number, shade: number) => {
    const [ta, kb] = P.legs[i]
    const r = limb(0.012 * (i ? 1 : -1), hipY, ta, thigh, -kb, shin, 0.085 * bw, shadeHex(outfit.legs, shade))
    // foot
    ctx.fillStyle = shadeHex(outfit.shoes, shade)
    ctx.strokeStyle = INK
    ctx.lineWidth = lw * 0.8
    ctx.beginPath()
    ctx.ellipse(r.fx + 0.035, r.fy + 0.008, 0.055, 0.028, 0, 0, Math.PI * 2)
    ctx.fill(); ctx.stroke()
  }
  const hands: { x: number; y: number; a: number }[] = []
  const arm = (i: number, shade: number) => {
    const [ua, eb] = P.arms[i]
    const sx = sh.x + (i ? 0.02 : -0.02), sy = sh.y + 0.02
    const r = limb(sx, sy, ua, upper, eb, fore, 0.068 * bw, shadeHex(outfit.sleeve, shade))
    // hand
    ctx.fillStyle = shadeHex(skin, shade)
    ctx.strokeStyle = INK
    ctx.lineWidth = lw * 0.7
    ctx.beginPath(); ctx.arc(r.fx, r.fy, 0.032, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    hands[i] = { x: r.fx, y: r.fy, a: r.a2 }
  }

  // carried load behind the body
  if (anim === 'carry') drawLoad(ctx, sh.x - 0.1, sh.y + 0.05, a)
  if (elder && !tool && anim !== 'pick' && anim !== 'kneel' && anim !== 'craft') {
    ctx.strokeStyle = '#6b4a2b'; ctx.lineWidth = 0.025
    ctx.beginPath(); ctx.moveTo(0.2, -0.45); ctx.lineTo(0.24, 0); ctx.stroke()
  }

  arm(0, -0.25)
  leg(0, -0.22)
  // torso
  const tw = 0.115 * bw, ww = 0.1 * bw
  const hem = outfit.long ? -0.17 : outfit.short ? -0.42 : -0.32
  ctx.save()
  ctx.beginPath()
  ctx.moveTo(sh.x - tw, sh.y + 0.01)
  ctx.quadraticCurveTo(sh.x, sh.y - 0.035, sh.x + tw, sh.y + 0.01)
  ctx.lineTo(ww + (outfit.long ? 0.06 : 0.015), hipY + (hem - hipY) * 1 + 0.0)
  ctx.lineTo(-ww - (outfit.long ? 0.05 : 0.01), hem)
  ctx.closePath()
  const g = ctx.createLinearGradient(-tw, 0, tw, 0)
  g.addColorStop(0, shadeHex(outfit.body, -0.28))
  g.addColorStop(0.55, outfit.body)
  g.addColorStop(1, shadeHex(outfit.body, 0.18))
  ctx.fillStyle = g
  ctx.fill()
  ctx.strokeStyle = INK
  ctx.lineWidth = lw
  ctx.stroke()
  ctx.clip()
  // belt / trim / details
  ctx.fillStyle = outfit.trim
  if (outfit.belt) ctx.fillRect(-0.2, hipY - 0.03, 0.4, 0.035)
  if (outfit.fur) { ctx.fillStyle = '#d9c4a0'; for (let k = -2; k <= 2; k++) { ctx.beginPath(); ctx.arc(sh.x + k * 0.045, sh.y + 0.01, 0.03, 0, Math.PI * 2); ctx.fill() } }
  if (outfit.jacket) { ctx.fillStyle = outfit.trim; ctx.fillRect(sh.x - 0.015, sh.y, 0.03, 0.3); ctx.fillStyle = 'rgba(255,255,255,0.85)'; ctx.beginPath(); ctx.moveTo(sh.x - 0.04, sh.y); ctx.lineTo(sh.x, sh.y + 0.08); ctx.lineTo(sh.x + 0.04, sh.y); ctx.fill() }
  ctx.restore()
  if (outfit.cape) {
    ctx.fillStyle = shadeHex(outfit.trim, -0.2)
    ctx.beginPath(); ctx.moveTo(sh.x - tw, sh.y); ctx.quadraticCurveTo(-0.22, -0.3, -0.16 - P.lean * 0.1, -0.16); ctx.lineTo(-0.04, -0.2); ctx.closePath(); ctx.fill()
    ctx.strokeStyle = INK; ctx.lineWidth = lw * 0.7; ctx.stroke()
  }
  leg(1, 0)

  // head
  const neckX = sh.x + Math.sin(P.lean) * 0.03, neckY = sh.y - 0.02
  const hx = neckX + 0.012 + Math.sin(P.lean + P.head) * 0.11, hy = neckY - Math.cos(P.lean + P.head) * 0.105
  ctx.save()
  ctx.translate(hx, hy)
  ctx.rotate(P.head * 0.6 + P.lean * 0.3)
  // long hair falls behind
  ctx.fillStyle = hair
  if (L.style === 1) { ctx.beginPath(); ctx.ellipse(-0.035, 0.07, headR * 0.95, headR * 1.35, 0.15, 0, Math.PI * 2); ctx.fill() }
  if (L.style === 3) { ctx.strokeStyle = hair; ctx.lineWidth = 0.035; ctx.beginPath(); ctx.moveTo(-0.07, 0); ctx.quadraticCurveTo(-0.11, 0.1, -0.06, 0.2); ctx.stroke() }
  // face
  ctx.fillStyle = skin
  ctx.strokeStyle = INK
  ctx.lineWidth = lw
  ctx.beginPath(); ctx.ellipse(0, 0, headR, headR * 1.06, 0, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
  ctx.fillStyle = 'rgba(0,0,0,0.13)'
  ctx.beginPath(); ctx.ellipse(-headR * 0.35, 0.01, headR * 0.6, headR * 0.98, 0, Math.PI * 0.5, Math.PI * 1.5); ctx.fill()
  // ear
  ctx.fillStyle = shadeHex(skin, -0.12)
  ctx.beginPath(); ctx.ellipse(-0.02, 0.01, 0.022, 0.03, 0, 0, Math.PI * 2); ctx.fill()
  // hair on top
  ctx.fillStyle = hair
  drawHair(ctx, L.style, headR, hair)
  if (L.beard && !child) {
    ctx.fillStyle = hair
    ctx.beginPath(); ctx.moveTo(-0.01, 0.02); ctx.quadraticCurveTo(0.03, 0.15, headR * 0.95, 0.03); ctx.quadraticCurveTo(0.06, 0.06, -0.01, 0.02); ctx.fill()
  }
  // eyes (blink every few seconds), brows, mouth
  const blink = ((o.t + seed * 7) % 3.7) < 0.12
  ctx.fillStyle = '#1b130e'
  if (blink) { ctx.fillRect(0.035, -0.012, 0.03, 0.008); ctx.fillRect(0.075, -0.012, 0.022, 0.008) }
  else {
    ctx.beginPath(); ctx.ellipse(0.05, -0.01, 0.012, 0.017, 0, 0, Math.PI * 2); ctx.fill()
    ctx.beginPath(); ctx.ellipse(0.088, -0.01, 0.01, 0.016, 0, 0, Math.PI * 2); ctx.fill()
  }
  ctx.strokeStyle = shadeHex(hair, -0.2)
  ctx.lineWidth = 0.012
  const cross = a.mood < 35 ? 0.012 : 0
  ctx.beginPath(); ctx.moveTo(0.035, -0.04 + cross); ctx.lineTo(0.065, -0.045 - cross); ctx.moveTo(0.078, -0.045 - cross); ctx.lineTo(0.1, -0.04 + cross); ctx.stroke()
  ctx.strokeStyle = '#6b2c24'
  ctx.lineWidth = 0.013
  if (P.mouth > 0.4) { ctx.fillStyle = '#5e231c'; ctx.beginPath(); ctx.ellipse(0.07, 0.05, 0.016, 0.01 + P.mouth * 0.012, 0, 0, Math.PI * 2); ctx.fill() }
  else { const sm = (a.mood - 50) / 50; ctx.beginPath(); ctx.moveTo(0.05, 0.048 - sm * 0.006); ctx.quadraticCurveTo(0.07, 0.05 + sm * 0.02, 0.09, 0.046 - sm * 0.006); ctx.stroke() }
  if (L.marks === 1) { ctx.fillStyle = o.tribeColor; ctx.fillRect(0.03, 0.012, 0.075, 0.012) }
  if (L.marks === 3) { ctx.strokeStyle = 'rgba(130,40,40,0.8)'; ctx.lineWidth = 0.008; ctx.beginPath(); ctx.moveTo(0.06, -0.05); ctx.lineTo(0.085, 0.02); ctx.stroke() }
  if (o.leader) {
    ctx.fillStyle = '#ffd34a'; ctx.strokeStyle = '#8a6410'; ctx.lineWidth = 0.008
    ctx.beginPath(); ctx.moveTo(-0.09, -0.07); ctx.lineTo(-0.08, -0.13); ctx.lineTo(-0.04, -0.1); ctx.lineTo(0, -0.15); ctx.lineTo(0.04, -0.1); ctx.lineTo(0.08, -0.13); ctx.lineTo(0.09, -0.07); ctx.closePath(); ctx.fill(); ctx.stroke()
  }
  if (outfit.hat) { ctx.fillStyle = outfit.hat; ctx.beginPath(); ctx.ellipse(0, -0.075, 0.13, 0.025, 0, 0, Math.PI * 2); ctx.fill(); ctx.fillRect(-0.07, -0.15, 0.14, 0.08) }
  ctx.restore()

  // tool in the front hand, then the front arm over the body
  arm(1, 0.05)
  let impact: { x: number; y: number } | null = null
  const hd = hands[1]
  if (tool && hd) {
    const tip = drawTool(ctx, tool, hd.x, hd.y, hd.a, o.toolTier, era, o.t, seed)
    if (P.impact && tip) impact = { x: o.x + tip.x * H * dir, y: o.y + tip.y * H }
  }
  ctx.restore()
  return { impact, headY: o.y + (hy - headR - P.hop) * H }
}

export interface Outfit { body: string; sleeve: string; legs: string; shoes: string; trim: string; belt: boolean; long: boolean; short: boolean; fur: boolean; cape: boolean; jacket: boolean; hat: string | null }

export function clothesFor(era: number, a: Agent, tribe: string, child: boolean): Outfit {
  const L = a.look
  const skin = SKIN[L.skin % SKIN.length]
  const dress = a.sex === 'female' && hash(a.id * 7) < 0.6
  if (era === 0) {
    const hide = a.clothing > 20 ? ['#8a6a44', '#7a5a3a', '#9a7650'][a.id % 3] : '#a8906d'
    return { body: hide, sleeve: skin, legs: skin, shoes: '#5a4030', trim: L.accent, belt: true, long: false, short: child || a.clothing < 20, fur: a.clothing > 50, cape: false, jacket: false, hat: null }
  }
  if (era <= 3) {
    const body = shadeHex(tribe, -0.08)
    return { body, sleeve: skin, legs: skin, shoes: '#6b4a2b', trim: L.accent, belt: true, long: dress, short: child, fur: false, cape: false, jacket: false, hat: null }
  }
  if (era <= 5) {
    const body = dress ? shadeHex(L.accent, 0.05) : shadeHex(tribe, -0.02)
    return { body, sleeve: body, legs: '#7a6650', shoes: '#4a3424', trim: dress ? shadeHex(tribe, -0.1) : L.accent, belt: !dress, long: dress, short: child, fur: false, cape: !child && hash(a.id * 3) < 0.35, jacket: false, hat: null }
  }
  if (era <= 6) {
    return { body: dress ? shadeHex(L.accent, -0.05) : '#ece7dc', sleeve: dress ? shadeHex(L.accent, -0.05) : '#ece7dc', legs: '#4a4542', shoes: '#2a201a', trim: dress ? '#f2efe6' : shadeHex(tribe, -0.3), belt: !dress, long: dress, short: false, fur: false, cape: false, jacket: !dress, hat: !child && hash(a.id * 5) < 0.3 ? '#2e2a28' : null }
  }
  const jacket = shadeHex(tribe, -0.1)
  return { body: jacket, sleeve: jacket, legs: '#3a5a8c', shoes: '#f2f2f2', trim: L.accent, belt: false, long: false, short: false, fur: false, cape: false, jacket: true, hat: null }
}

function drawHair(ctx: CanvasRenderingContext2D, style: number, r: number, hair: string) {
  ctx.beginPath()
  switch (style) {
    case 4: // shaved
      ctx.globalAlpha = 0.45
      ctx.ellipse(-0.01, -0.035, r * 0.98, r * 0.62, 0, Math.PI, Math.PI * 2)
      ctx.fill()
      ctx.globalAlpha = 1
      return
    case 5: // curly
      for (let k = 0; k < 6; k++) { const an = Math.PI + (k / 5) * Math.PI; ctx.moveTo(Math.cos(an) * r * 0.9, -0.02 + Math.sin(an) * r * 0.85); ctx.arc(Math.cos(an) * r * 0.9, -0.02 + Math.sin(an) * r * 0.85, r * 0.38, 0, Math.PI * 2) }
      ctx.fill()
      return
    default:
      ctx.moveTo(-r * 1.02, 0.01)
      ctx.quadraticCurveTo(-r * 1.1, -r * 1.25, r * 0.2, -r * 1.12)
      ctx.quadraticCurveTo(r * 1.1, -r * 0.95, r * 0.95, -r * 0.25)
      ctx.quadraticCurveTo(r * 0.3, -r * 0.55, -r * 0.35, -r * 0.35)
      ctx.quadraticCurveTo(-r * 0.6, -r * 0.1, -r * 1.02, 0.01)
      ctx.fill()
      ctx.strokeStyle = INK
      ctx.lineWidth = 0.02
      ctx.stroke()
      if (style === 2) { ctx.beginPath(); ctx.arc(-r * 0.55, -r * 0.95, r * 0.42, 0, Math.PI * 2); ctx.fillStyle = hair; ctx.fill(); ctx.stroke() }
  }
}

function drawLoad(ctx: CanvasRenderingContext2D, x: number, y: number, a: Agent) {
  const wood = (a.inv.wood ?? 0) > 0
  if (wood) {
    ctx.fillStyle = '#7a5530'
    ctx.strokeStyle = INK
    ctx.lineWidth = 0.02
    for (let k = 0; k < 3; k++) { ctx.beginPath(); ctx.ellipse(x - 0.02, y + k * 0.045, 0.14, 0.024, -0.2, 0, Math.PI * 2); ctx.fill(); ctx.stroke() }
  } else {
    ctx.fillStyle = '#b08a5a'
    ctx.strokeStyle = INK
    ctx.lineWidth = 0.022
    ctx.beginPath(); ctx.ellipse(x, y + 0.05, 0.1, 0.12, 0.2, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    ctx.fillStyle = '#8a6a44'; ctx.fillRect(x - 0.05, y - 0.05, 0.1, 0.03)
  }
}

const METAL = ['#8d8f93', '#8d8f93', '#c27a3a', '#9aa3ad', '#c9d1d9', '#f2c94c']

function drawTool(ctx: CanvasRenderingContext2D, tool: string, x: number, y: number, a: number, tier: number, era: number, t: number, seed: number): { x: number; y: number } | null {
  const dx = Math.sin(a), dy = Math.cos(a)
  const head = METAL[Math.min(5, tier)] ?? '#8d8f93'
  ctx.lineCap = 'round'
  const shaft = (len: number, col = '#7a5230', w = 0.022) => {
    ctx.strokeStyle = INK; ctx.lineWidth = w + 0.018
    ctx.beginPath(); ctx.moveTo(x - dx * 0.05, y - dy * 0.05); ctx.lineTo(x + dx * len, y + dy * len); ctx.stroke()
    ctx.strokeStyle = col; ctx.lineWidth = w
    ctx.beginPath(); ctx.moveTo(x - dx * 0.05, y - dy * 0.05); ctx.lineTo(x + dx * len, y + dy * len); ctx.stroke()
    return { x: x + dx * len, y: y + dy * len }
  }
  switch (tool) {
    case 'axe': case 'pick': case 'hammer': case 'hoe': {
      const tip = shaft(0.3)
      const px = -dy, py = dx // perpendicular
      ctx.fillStyle = tool === 'hoe' ? '#8d8f93' : head
      ctx.strokeStyle = INK
      ctx.lineWidth = 0.018
      ctx.beginPath()
      if (tool === 'axe') { ctx.moveTo(tip.x, tip.y); ctx.lineTo(tip.x + px * 0.09 - dx * 0.04, tip.y + py * 0.09 - dy * 0.04); ctx.lineTo(tip.x + px * 0.1 + dx * 0.05, tip.y + py * 0.1 + dy * 0.05); ctx.lineTo(tip.x - dx * 0.0, tip.y + dy * 0.03) }
      else if (tool === 'pick') { ctx.moveTo(tip.x - px * 0.12 - dx * 0.03, tip.y - py * 0.12 - dy * 0.03); ctx.quadraticCurveTo(tip.x + dx * 0.04, tip.y + dy * 0.04, tip.x + px * 0.12 - dx * 0.03, tip.y + py * 0.12 - dy * 0.03); ctx.lineTo(tip.x, tip.y) }
      else if (tool === 'hammer') { ctx.rect(tip.x - 0.045, tip.y - 0.03, 0.09, 0.06) }
      else { ctx.moveTo(tip.x, tip.y); ctx.lineTo(tip.x + px * 0.08, tip.y + py * 0.08 + 0.02); ctx.lineTo(tip.x + px * 0.02, tip.y + py * 0.02 + 0.04) }
      ctx.closePath(); ctx.fill(); ctx.stroke()
      return tip
    }
    case 'spear': {
      const tip = shaft(0.55, '#7a5230', 0.018)
      ctx.fillStyle = head; ctx.strokeStyle = INK; ctx.lineWidth = 0.012
      ctx.beginPath(); ctx.moveTo(tip.x + dx * 0.08, tip.y + dy * 0.08); ctx.lineTo(tip.x - dy * 0.03, tip.y + dx * 0.03); ctx.lineTo(tip.x + dy * 0.03, tip.y - dx * 0.03); ctx.closePath(); ctx.fill(); ctx.stroke()
      return { x: tip.x + dx * 0.08, y: tip.y + dy * 0.08 }
    }
    case 'staff':
      shaft(0.5, '#6b4a2b', 0.02)
      return null
    case 'rod': {
      const tip = shaft(0.75, era >= 6 ? '#2d2d2d' : '#9a7a4a', 0.012)
      // line and bobbing float
      const bob = Math.sin(t * 2.5 + seed * 8) * 0.012
      ctx.strokeStyle = 'rgba(240,240,240,0.7)'; ctx.lineWidth = 0.006
      ctx.beginPath(); ctx.moveTo(tip.x, tip.y); ctx.quadraticCurveTo(tip.x + 0.2, tip.y + 0.2, tip.x + 0.32, -0.02 + bob); ctx.stroke()
      ctx.fillStyle = '#e53935'; ctx.beginPath(); ctx.arc(tip.x + 0.32, -0.03 + bob, 0.018, 0, Math.PI * 2); ctx.fill()
      return null
    }
    case 'book': {
      ctx.save()
      ctx.translate(x + 0.02, y - 0.02)
      ctx.fillStyle = era >= 2 ? '#7b3f2a' : '#d9c79a'
      ctx.strokeStyle = INK; ctx.lineWidth = 0.014
      ctx.beginPath(); ctx.rect(-0.07, -0.05, 0.14, 0.09); ctx.fill(); ctx.stroke()
      ctx.fillStyle = '#f4ead2'; ctx.fillRect(-0.06, -0.045, 0.055, 0.075); ctx.fillRect(0.005, -0.045, 0.055, 0.075)
      ctx.restore()
      return null
    }
  }
  return null
}

function drawSleeper(ctx: CanvasRenderingContext2D, H: number, t: number, seed: number, skin: string, hair: string, o: Outfit, dir: number) {
  const breathe = 1 + Math.sin(t * 1.6 + seed * 9) * 0.04
  ctx.scale(H * dir, H)
  ctx.lineWidth = 0.025
  ctx.strokeStyle = INK
  // bedroll and blanket
  ctx.fillStyle = '#6d5a44'
  ctx.beginPath(); ctx.ellipse(0, -0.05, 0.46, 0.09, 0, 0, Math.PI * 2); ctx.fill()
  ctx.fillStyle = shadeHex(o.trim, -0.1)
  ctx.beginPath(); ctx.ellipse(0.04, -0.09, 0.36, 0.1 * breathe, 0, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
  ctx.strokeStyle = 'rgba(255,255,255,0.25)'
  for (let k = -2; k <= 2; k++) { ctx.beginPath(); ctx.moveTo(0.04 + k * 0.1, -0.17); ctx.lineTo(0.04 + k * 0.1, -0.02); ctx.stroke() }
  // head on a pillow
  ctx.fillStyle = '#eee6d6'
  ctx.beginPath(); ctx.ellipse(-0.36, -0.08, 0.09, 0.06, 0, 0, Math.PI * 2); ctx.fill()
  ctx.fillStyle = skin; ctx.strokeStyle = INK
  ctx.beginPath(); ctx.arc(-0.35, -0.13, 0.09, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
  ctx.fillStyle = hair
  ctx.beginPath(); ctx.arc(-0.4, -0.15, 0.08, Math.PI * 0.6, Math.PI * 1.7); ctx.fill()
  ctx.strokeStyle = '#1b130e'; ctx.lineWidth = 0.012
  ctx.beginPath(); ctx.moveTo(-0.33, -0.15); ctx.lineTo(-0.3, -0.15); ctx.stroke()
  // drifting Zs
  const z = (t * 0.6 + seed) % 1
  ctx.fillStyle = `rgba(255,255,255,${1 - z})`
  ctx.font = '700 0.16px Inter, sans-serif'
  ctx.save(); ctx.scale(dir, 1)
  ctx.fillText('z', (-0.25 + z * 0.15) * dir, -0.3 - z * 0.3)
  ctx.restore()
}

// ------------------------------------------------------------------ animals

export function drawCreature(ctx: CanvasRenderingContext2D, an: Animal, x: number, y: number, S: number, t: number, moving: boolean) {
  const seed = hash(an.id)
  const dir = an.dir < 0 ? -1 : 1
  ctx.save()
  ctx.translate(x, y)
  ctx.fillStyle = 'rgba(10,14,8,0.28)'
  const size = S * (an.kind === 'rabbit' ? 0.32 : an.kind === 'boar' ? 0.62 : 0.8)
  ctx.beginPath(); ctx.ellipse(0, 0, size * 0.42, size * 0.09, 0, 0, Math.PI * 2); ctx.fill()
  ctx.scale(size * dir, size)
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  const g = t * (an.kind === 'rabbit' ? 9 : 11) + seed * 10
  if (an.kind === 'rabbit') {
    const hop = moving ? Math.abs(Math.sin(g)) * 0.35 : 0
    ctx.translate(0, -hop)
    ctx.fillStyle = '#b9a78f'; ctx.strokeStyle = INK; ctx.lineWidth = 0.05
    ctx.beginPath(); ctx.ellipse(0, -0.28, 0.32, 0.24, 0, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    ctx.beginPath(); ctx.ellipse(0.27, -0.45, 0.16, 0.14, 0, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    ctx.beginPath(); ctx.ellipse(0.24, -0.72, 0.05, 0.18, -0.2, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    ctx.beginPath(); ctx.ellipse(0.31, -0.7, 0.05, 0.17, 0.15, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(-0.32, -0.3, 0.08, 0, Math.PI * 2); ctx.fill()
    ctx.fillStyle = '#111'; ctx.beginPath(); ctx.arc(0.33, -0.48, 0.03, 0, Math.PI * 2); ctx.fill()
    ctx.restore()
    return
  }
  const deer = an.kind === 'deer'
  const body = deer ? '#a8693a' : '#4c3a2e'
  const legLen = deer ? 0.42 : 0.24
  const bodyY = -(legLen + (deer ? 0.16 : 0.18))
  const graze = !moving && Math.sin(t * 0.5 + seed * 20) > 0.2
  // legs: diagonal pairs move together
  const legs: [number, number][] = [[-0.25, 0], [0.25, Math.PI], [-0.2, Math.PI], [0.3, 0]]
  legs.forEach(([lx, ph], k) => {
    const a = moving ? Math.sin(g + ph) * 0.55 : 0
    const knee = moving ? Math.max(0, Math.sin(g + ph + 1.2)) * 0.7 : 0
    const sx = lx, sy = bodyY + 0.08
    const kx = sx + Math.sin(a) * legLen * 0.5, ky = sy + Math.cos(a) * legLen * 0.5
    const fx = kx + Math.sin(a - knee) * legLen * 0.55, fy = ky + Math.cos(a - knee) * legLen * 0.55
    ctx.strokeStyle = INK; ctx.lineWidth = 0.1
    ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(kx, ky); ctx.lineTo(fx, fy); ctx.stroke()
    ctx.strokeStyle = k < 2 ? shadeHex(body, -0.25) : body; ctx.lineWidth = 0.065
    ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(kx, ky); ctx.lineTo(fx, fy); ctx.stroke()
  })
  const bob = moving ? Math.abs(Math.sin(g)) * 0.03 : 0
  ctx.translate(0, -bob)
  ctx.fillStyle = body; ctx.strokeStyle = INK; ctx.lineWidth = 0.05
  ctx.beginPath(); ctx.ellipse(0, bodyY, deer ? 0.42 : 0.46, deer ? 0.17 : 0.22, 0, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
  ctx.fillStyle = shadeHex(body, 0.25)
  ctx.beginPath(); ctx.ellipse(0.02, bodyY + 0.06, 0.3, 0.06, 0, 0, Math.PI * 2); ctx.fill()
  if (deer) {
    ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.ellipse(-0.42, bodyY - 0.04, 0.06, 0.07, 0, 0, Math.PI * 2); ctx.fill()
    for (let k = 0; k < 5; k++) { ctx.beginPath(); ctx.arc(-0.2 + k * 0.1, bodyY - 0.06 + (k % 2) * 0.04, 0.018, 0, Math.PI * 2); ctx.fill() }
    // neck and head (lowered when grazing)
    const hx = graze ? 0.5 : 0.48, hy = graze ? bodyY + 0.32 : bodyY - 0.34
    ctx.strokeStyle = INK; ctx.lineWidth = 0.15
    ctx.beginPath(); ctx.moveTo(0.3, bodyY - 0.05); ctx.lineTo(hx - 0.05, hy + 0.04); ctx.stroke()
    ctx.strokeStyle = body; ctx.lineWidth = 0.11
    ctx.beginPath(); ctx.moveTo(0.3, bodyY - 0.05); ctx.lineTo(hx - 0.05, hy + 0.04); ctx.stroke()
    ctx.fillStyle = body; ctx.strokeStyle = INK; ctx.lineWidth = 0.04
    ctx.beginPath(); ctx.ellipse(hx + 0.05, hy, 0.14, 0.08, graze ? 0.8 : 0.25, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    ctx.beginPath(); ctx.ellipse(hx - 0.04, hy - 0.08, 0.04, 0.07, -0.5, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    if (seed > 0.45) {
      ctx.strokeStyle = '#6b4a2b'; ctx.lineWidth = 0.03
      ctx.beginPath(); ctx.moveTo(hx, hy - 0.06); ctx.lineTo(hx - 0.04, hy - 0.3); ctx.moveTo(hx - 0.02, hy - 0.18); ctx.lineTo(hx + 0.07, hy - 0.26); ctx.moveTo(hx - 0.04, hy - 0.3); ctx.lineTo(hx - 0.13, hy - 0.36); ctx.stroke()
    }
    ctx.fillStyle = '#111'; ctx.beginPath(); ctx.arc(hx + 0.06, hy - 0.02, 0.02, 0, Math.PI * 2); ctx.fill()
  } else {
    ctx.fillStyle = body; ctx.strokeStyle = INK; ctx.lineWidth = 0.05
    ctx.beginPath(); ctx.ellipse(0.46, bodyY + 0.04, 0.18, 0.14, 0.3, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
    ctx.fillStyle = '#c9a28a'; ctx.beginPath(); ctx.ellipse(0.62, bodyY + 0.1, 0.05, 0.045, 0, 0, Math.PI * 2); ctx.fill()
    ctx.strokeStyle = '#f2ead8'; ctx.lineWidth = 0.025
    ctx.beginPath(); ctx.moveTo(0.55, bodyY + 0.13); ctx.quadraticCurveTo(0.6, bodyY + 0.06, 0.58, bodyY + 0.02); ctx.stroke()
    ctx.strokeStyle = INK; ctx.lineWidth = 0.03
    ctx.beginPath(); ctx.moveTo(-0.15, bodyY - 0.2); ctx.lineTo(0.1, bodyY - 0.24); ctx.lineTo(0.3, bodyY - 0.2); ctx.stroke()
    ctx.fillStyle = '#111'; ctx.beginPath(); ctx.arc(0.5, bodyY - 0.01, 0.02, 0, Math.PI * 2); ctx.fill()
  }
  ctx.restore()
}
