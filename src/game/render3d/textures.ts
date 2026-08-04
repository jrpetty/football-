import { FIELD } from '../config'

// Procedural texture generation. Everything the 3D scene paints with is drawn
// here into canvases — no external image assets — so the game stays a single
// self-contained bundle. Each generator is deterministic (seeded) so the look is
// identical every run.

const rng = (seed: number) => () =>
  ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff)

const canvas = (w: number, h = w): [HTMLCanvasElement, CanvasRenderingContext2D] => {
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  return [c, c.getContext('2d')!]
}

// ---------------------------------------------------------------- grass ----

// A seamlessly tiling turf swatch: mottled base, thousands of individual blades,
// and a few worn patches. Tiled densely across the pitch it reads as real grass
// instead of flat green.
export function grassTexture(): HTMLCanvasElement {
  const S = 512
  const [c, ctx] = canvas(S)
  const rnd = rng(20240817)

  ctx.fillStyle = '#2c7a41'
  ctx.fillRect(0, 0, S, S)

  // Soft tonal patches so the turf isn't uniform.
  for (let i = 0; i < 260; i++) {
    const x = rnd() * S
    const y = rnd() * S
    const r = 12 + rnd() * 54
    const g = ctx.createRadialGradient(x, y, 0, x, y, r)
    const light = rnd() > 0.5
    g.addColorStop(0, light ? 'rgba(120,190,120,0.10)' : 'rgba(12,58,28,0.12)')
    g.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(x, y, r, 0, Math.PI * 2)
    ctx.fill()
  }

  // Individual blades. Near an edge the blade is redrawn wrapped so the swatch
  // tiles without a visible seam.
  ctx.lineCap = 'round'
  const blade = (x: number, y: number, len: number, ang: number, style: string, w: number) => {
    ctx.strokeStyle = style
    ctx.lineWidth = w
    ctx.beginPath()
    ctx.moveTo(x, y)
    ctx.quadraticCurveTo(
      x + Math.cos(ang) * len * 0.5,
      y + Math.sin(ang) * len * 0.5,
      x + Math.cos(ang) * len,
      y + Math.sin(ang) * len,
    )
    ctx.stroke()
  }
  for (let i = 0; i < 16000; i++) {
    const x = rnd() * S
    const y = rnd() * S
    const len = 3 + rnd() * 6
    const ang = -Math.PI / 2 + (rnd() - 0.5) * 1.1
    const g = 96 + rnd() * 78
    const style = `rgba(${26 + rnd() * 34 | 0},${g | 0},${44 + rnd() * 34 | 0},${(0.3 + rnd() * 0.5).toFixed(2)})`
    const w = 0.7 + rnd() * 0.9
    blade(x, y, len, ang, style, w)
    const near = len + 2
    if (x < near) blade(x + S, y, len, ang, style, w)
    if (x > S - near) blade(x - S, y, len, ang, style, w)
    if (y < near) blade(x, y + S, len, ang, style, w)
    if (y > S - near) blade(x, y - S, len, ang, style, w)
  }

  return c
}

// Derive a tangent-space normal map from a texture's luminance, treating bright
// pixels as raised. Gives the turf real relief under the stadium lighting.
export function normalFromCanvas(src: HTMLCanvasElement, strength = 2.6): HTMLCanvasElement {
  const S = src.width
  const img = src.getContext('2d')!.getImageData(0, 0, S, S).data
  const [out, octx] = canvas(S)
  const dst = octx.createImageData(S, S)
  const h = (x: number, y: number) => {
    const xi = ((x % S) + S) % S
    const yi = ((y % S) + S) % S
    const i = (yi * S + xi) * 4
    return (img[i] * 0.299 + img[i + 1] * 0.587 + img[i + 2] * 0.114) / 255
  }
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const dx = (h(x + 1, y) - h(x - 1, y)) * strength
      const dy = (h(x, y + 1) - h(x, y - 1)) * strength
      let nx = -dx
      let ny = dy
      let nz = 1
      const l = Math.hypot(nx, ny, nz)
      nx /= l
      ny /= l
      nz /= l
      const i = (y * S + x) * 4
      dst.data[i] = (nx * 0.5 + 0.5) * 255
      dst.data[i + 1] = (ny * 0.5 + 0.5) * 255
      dst.data[i + 2] = (nz * 0.5 + 0.5) * 255
      dst.data[i + 3] = 255
    }
  }
  octx.putImageData(dst, 0, 0)
  return out
}

// Transparent decal laid over the turf: mowing stripes plus every pitch marking,
// drawn at high resolution so the lines stay crisp underfoot.
export function pitchOverlayTexture(): HTMLCanvasElement {
  const L = FIELD.length
  const W = FIELD.width
  const s = 36
  const [c, ctx] = canvas(Math.round(L * s), Math.round(W * s))
  const mx = (x: number) => x * s
  const my = (y: number) => y * s

  // Mowing bands, alternating light/dark, with a soft edge between them.
  const stripes = 14
  const bw = c.width / stripes
  for (let i = 0; i < stripes; i++) {
    const g = ctx.createLinearGradient(i * bw, 0, (i + 1) * bw, 0)
    const a = i % 2 === 0 ? 'rgba(226,255,226,0.20)' : 'rgba(0,26,0,0.24)'
    g.addColorStop(0, 'rgba(0,0,0,0)')
    g.addColorStop(0.12, a)
    g.addColorStop(0.88, a)
    g.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = g
    ctx.fillRect(i * bw, 0, bw + 1, c.height)
  }

  ctx.strokeStyle = 'rgba(255,255,255,0.94)'
  ctx.fillStyle = 'rgba(255,255,255,0.94)'
  ctx.lineWidth = 0.13 * s
  ctx.lineCap = 'butt'

  const inset = 0.25
  ctx.strokeRect(mx(inset), my(inset), mx(L - inset * 2), my(W - inset * 2))
  ctx.beginPath()
  ctx.moveTo(mx(L / 2), my(inset))
  ctx.lineTo(mx(L / 2), my(W - inset))
  ctx.stroke()
  ctx.beginPath()
  ctx.arc(mx(L / 2), my(W / 2), FIELD.centerRadius * s, 0, Math.PI * 2)
  ctx.stroke()
  ctx.beginPath()
  ctx.arc(mx(L / 2), my(W / 2), 0.26 * s, 0, Math.PI * 2)
  ctx.fill()

  const yMin = (W - FIELD.boxWidth) / 2
  const sixW = FIELD.boxWidth * 0.44
  const sixD = FIELD.boxDepth * 0.36
  ctx.strokeRect(mx(inset), my(yMin), mx(FIELD.boxDepth), FIELD.boxWidth * s)
  ctx.strokeRect(mx(L - FIELD.boxDepth - inset), my(yMin), FIELD.boxDepth * s, FIELD.boxWidth * s)
  ctx.strokeRect(mx(inset), my((W - sixW) / 2), mx(sixD), sixW * s)
  ctx.strokeRect(mx(L - sixD - inset), my((W - sixW) / 2), sixD * s, sixW * s)

  // Penalty spots and the arc outside each box.
  for (const side of [0, 1]) {
    const px = side === 0 ? FIELD.boxDepth * 0.62 : L - FIELD.boxDepth * 0.62
    ctx.beginPath()
    ctx.arc(mx(px), my(W / 2), 0.22 * s, 0, Math.PI * 2)
    ctx.fill()
    ctx.beginPath()
    const a0 = side === 0 ? -Math.PI / 2.6 : Math.PI - Math.PI / 2.6
    const a1 = side === 0 ? Math.PI / 2.6 : Math.PI + Math.PI / 2.6
    ctx.arc(mx(px), my(W / 2), FIELD.centerRadius * 0.8 * s, a0, a1, side === 1)
    ctx.stroke()
  }

  // Corner arcs.
  for (const [cxm, cym, a0] of [
    [inset, inset, 0],
    [L - inset, inset, Math.PI / 2],
    [L - inset, W - inset, Math.PI],
    [inset, W - inset, -Math.PI / 2],
  ] as const) {
    ctx.beginPath()
    ctx.arc(mx(cxm), my(cym), FIELD.cornerRadius * s, a0, a0 + Math.PI / 2)
    ctx.stroke()
  }

  return c
}

// ---------------------------------------------------------------- crowd ----

// Tiered seating seen from the pitch: dark seat rows with staggered spectators,
// so the stands read as people rather than colour noise.
export function crowdTexture(): HTMLCanvasElement {
  const [c, ctx] = canvas(512, 512)
  const rnd = rng(555123)
  ctx.fillStyle = '#161d29'
  ctx.fillRect(0, 0, c.width, c.height)

  const shirts = ['#e9ecf2', '#cf4b4b', '#4b7fe0', '#e5bd4e', '#57a866', '#a97fd0', '#d98551', '#2f3846', '#8fa2bb']
  const skins = ['#e8b48c', '#c98f63', '#8d5a3b', '#6b4028', '#f0c9a8']
  const rows = 22
  const rowH = c.height / rows

  for (let r = 0; r < rows; r++) {
    const y = r * rowH
    // Seat backs behind each row.
    ctx.fillStyle = r % 2 === 0 ? '#1d2634' : '#19212d'
    ctx.fillRect(0, y, c.width, rowH)
    ctx.fillStyle = 'rgba(0,0,0,0.35)'
    ctx.fillRect(0, y + rowH * 0.82, c.width, rowH * 0.18)

    const perRow = 26
    const gap = c.width / perRow
    const offset = (r % 2) * gap * 0.5
    for (let i = 0; i < perRow; i++) {
      if (rnd() < 0.12) continue // empty seat
      const x = i * gap + offset + (rnd() - 0.5) * gap * 0.18
      const bodyW = gap * 0.62
      const bodyH = rowH * 0.6
      const by = y + rowH * 0.34
      ctx.fillStyle = shirts[(rnd() * shirts.length) | 0]
      ctx.globalAlpha = 0.85 + rnd() * 0.15
      ctx.beginPath()
      ctx.moveTo(x - bodyW / 2, by + bodyH)
      ctx.quadraticCurveTo(x - bodyW / 2, by, x, by)
      ctx.quadraticCurveTo(x + bodyW / 2, by, x + bodyW / 2, by + bodyH)
      ctx.closePath()
      ctx.fill()
      ctx.fillStyle = skins[(rnd() * skins.length) | 0]
      ctx.beginPath()
      ctx.arc(x, by - rowH * 0.1, rowH * 0.15, 0, Math.PI * 2)
      ctx.fill()
      ctx.globalAlpha = 1
    }
  }

  // Gentle depth shading: rows higher up the stand sit a little deeper in
  // shadow, without crushing the crowd to black.
  const shade = ctx.createLinearGradient(0, 0, 0, c.height)
  shade.addColorStop(0, 'rgba(0,0,0,0.22)')
  shade.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = shade
  ctx.fillRect(0, 0, c.width, c.height)
  return c
}

// ------------------------------------------------------------ ad boards ----

// A strip of distinct sponsor panels rather than one repeated word.
export function adTexture(): HTMLCanvasElement {
  const [c, ctx] = canvas(2048, 192)
  const panels: [string, string, string][] = [
    ['#0f2f1c', '#7dffab', 'OPEN PITCH'],
    ['#12233f', '#ffffff', 'NORTHSIDE'],
    ['#3a0f16', '#ffd7d7', 'REDLINE'],
    ['#2f7df6', '#ffffff', 'RIVERSIDE FC'],
    ['#141821', '#f5c542', 'APEX BOOTS'],
    ['#1b4d2e', '#ffffff', 'PLAY FAIR'],
    ['#40204f', '#e9d5ff', 'MERIDIAN'],
    ['#7a1414', '#ffffff', 'LAKESIDE'],
  ]
  const pw = c.width / panels.length
  panels.forEach(([bg, fg, text], i) => {
    const x = i * pw
    const g = ctx.createLinearGradient(x, 0, x, c.height)
    g.addColorStop(0, bg)
    g.addColorStop(1, 'rgba(0,0,0,0.85)')
    ctx.fillStyle = g
    ctx.fillRect(x, 0, pw - 4, c.height)
    ctx.fillStyle = fg
    ctx.font = '800 58px system-ui, sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(text, x + pw / 2 - 2, c.height / 2 + 4)
    ctx.fillStyle = 'rgba(255,255,255,0.16)'
    ctx.fillRect(x, 0, pw - 4, 5)
  })
  return c
}

// ------------------------------------------------------------------ kit ----

// Jersey fabric: team colours with a stripe/hoop pattern, collar, sponsor band
// and a woven texture. Symmetric so it reads correctly from any angle on the
// capsule torso.
export function kitTexture(primary: string, secondary: string, accent: string, style: 'stripes' | 'hoops'): HTMLCanvasElement {
  const [c, ctx] = canvas(512, 512)
  const rnd = rng(4242)
  ctx.fillStyle = primary
  ctx.fillRect(0, 0, c.width, c.height)

  ctx.fillStyle = secondary
  ctx.globalAlpha = 0.9
  if (style === 'stripes') {
    const n = 8
    const w = c.width / n
    for (let i = 0; i < n; i += 2) ctx.fillRect(i * w, 0, w, c.height)
  } else {
    ctx.fillRect(0, c.height * 0.42, c.width, c.height * 0.16)
    ctx.fillRect(0, c.height * 0.68, c.width, c.height * 0.07)
  }
  ctx.globalAlpha = 1

  // Collar (v≈1 is the top of the capsule).
  ctx.fillStyle = accent
  ctx.fillRect(0, 0, c.width, c.height * 0.09)
  ctx.fillRect(0, c.height * 0.93, c.width, c.height * 0.07)

  // Sponsor band across the chest.
  ctx.fillStyle = 'rgba(255,255,255,0.92)'
  ctx.font = '800 54px system-ui, sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  for (const cx of [c.width * 0.25, c.width * 0.75]) {
    ctx.fillText('OPEN', cx, c.height * 0.55)
  }

  // Woven fabric grain.
  ctx.globalAlpha = 0.06
  for (let i = 0; i < 9000; i++) {
    ctx.fillStyle = rnd() > 0.5 ? '#ffffff' : '#000000'
    ctx.fillRect(rnd() * c.width, rnd() * c.height, 2, 1)
  }
  ctx.globalAlpha = 1
  return c
}

// ----------------------------------------------------------------- ball ----

// A modern match ball: white panels with curved seams, accent flashes and a
// subtle speckle, rather than a flat pentagon stamp.
export function ballTexture(primary = '#12331f', accent = '#2f7df6'): HTMLCanvasElement {
  const [c, ctx] = canvas(512, 256)
  const rnd = rng(90210)
  ctx.fillStyle = '#f7f9fc'
  ctx.fillRect(0, 0, c.width, c.height)

  // Soft grey panel shading.
  for (let i = 0; i < 5; i++) {
    const x = (i / 5) * c.width
    const g = ctx.createLinearGradient(x, 0, x + c.width / 5, 0)
    g.addColorStop(0, 'rgba(190,198,210,0.0)')
    g.addColorStop(0.5, 'rgba(190,198,210,0.28)')
    g.addColorStop(1, 'rgba(190,198,210,0.0)')
    ctx.fillStyle = g
    ctx.fillRect(x, 0, c.width / 5, c.height)
  }

  // Curved seams running around the ball.
  ctx.strokeStyle = primary
  ctx.lineWidth = 7
  ctx.lineCap = 'round'
  for (let i = 0; i < 5; i++) {
    const x = (i / 5) * c.width + c.width / 10
    ctx.beginPath()
    ctx.moveTo(x - 46, 0)
    ctx.bezierCurveTo(x + 30, c.height * 0.3, x - 30, c.height * 0.7, x + 46, c.height)
    ctx.stroke()
  }
  ctx.lineWidth = 5
  ctx.beginPath()
  ctx.moveTo(0, c.height * 0.5)
  for (let x = 0; x <= c.width; x += 16) {
    ctx.lineTo(x, c.height * 0.5 + Math.sin((x / c.width) * Math.PI * 6) * 16)
  }
  ctx.stroke()

  // Accent flashes.
  ctx.fillStyle = accent
  for (let i = 0; i < 5; i++) {
    const x = (i / 5) * c.width + c.width / 10
    ctx.beginPath()
    ctx.ellipse(x, c.height * 0.28, 20, 8, -0.5, 0, Math.PI * 2)
    ctx.fill()
  }

  // Fine speckle for surface texture.
  ctx.globalAlpha = 0.05
  for (let i = 0; i < 5000; i++) {
    ctx.fillStyle = rnd() > 0.5 ? '#000' : '#fff'
    ctx.fillRect(rnd() * c.width, rnd() * c.height, 2, 2)
  }
  ctx.globalAlpha = 1
  return c
}

// --------------------------------------------------------------- number ----

export function numberTexture(num: number, color: string): HTMLCanvasElement {
  const [c, ctx] = canvas(256)
  ctx.clearRect(0, 0, c.width, c.height)
  ctx.fillStyle = color
  ctx.font = '800 176px system-ui, sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.lineWidth = 10
  ctx.strokeStyle = 'rgba(0,0,0,0.35)'
  ctx.strokeText(String(num), 128, 140)
  ctx.fillText(String(num), 128, 140)
  return c
}
