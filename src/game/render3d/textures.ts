import { FIELD } from '../config'
import { DISPLAY } from '../ui/fonts'

const DISPLAY_STACK = DISPLAY

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

  ctx.fillStyle = '#2f8a45'
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

  // Big, slow variation first. The turf swatch beneath repeats every few metres,
  // and on a flat pitch the eye finds a repeat instantly; broad soft blotches
  // laid over the whole surface, at a scale no tile could carry, break it up.
  {
    const rnd = rng(7311)
    for (let i = 0; i < 260; i++) {
      const x = rnd() * c.width
      const y = rnd() * c.height
      const r = (1.4 + rnd() * 4.2) * s
      const light = rnd() > 0.5
      const g = ctx.createRadialGradient(x, y, 0, x, y, r)
      g.addColorStop(0, light ? 'rgba(170,240,150,0.10)' : 'rgba(0,30,10,0.13)')
      g.addColorStop(1, 'rgba(0,0,0,0)')
      ctx.fillStyle = g
      ctx.fillRect(x - r, y - r, r * 2, r * 2)
    }
  }

  // Mowing bands, alternating light and dark. A real pitch is striped by the
  // lay of the blades — grass leaning away from you catches the light, grass
  // leaning towards you doesn't — so it is a clear, broad difference in value
  // with a soft edge, not a faint tint.
  const stripes = 16
  const bw = c.width / stripes
  for (let i = 0; i < stripes; i++) {
    const g = ctx.createLinearGradient(i * bw, 0, (i + 1) * bw, 0)
    const a = i % 2 === 0 ? 'rgba(214,255,200,0.24)' : 'rgba(0,28,6,0.30)'
    g.addColorStop(0, 'rgba(0,0,0,0)')
    g.addColorStop(0.1, a)
    g.addColorStop(0.9, a)
    g.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = g
    ctx.fillRect(i * bw, 0, bw + 1, c.height)
  }

  // Wear where the game is played: scuffed goalmouths, penalty spots, the
  // centre circle. Small, and low in contrast, but it is the difference between
  // a pitch and a texture of one.
  {
    const rnd = rng(9021)
    const scuff = (cx: number, cy: number, rx: number, ry: number, a: number) => {
      ctx.save()
      ctx.translate(mx(cx), my(cy))
      ctx.scale(rx * s, ry * s)
      const g = ctx.createRadialGradient(0, 0, 0, 0, 0, 1)
      g.addColorStop(0, `rgba(92,70,36,${a})`)
      g.addColorStop(0.55, `rgba(92,70,36,${a * 0.45})`)
      g.addColorStop(1, 'rgba(92,70,36,0)')
      ctx.fillStyle = g
      ctx.beginPath()
      ctx.arc(0, 0, 1, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()
    }
    for (const gx of [1.6, L - 1.6]) scuff(gx, W / 2, 2.6, 4.2, 0.34)
    for (const px of [FIELD.boxDepth * 0.62, L - FIELD.boxDepth * 0.62]) scuff(px, W / 2, 0.9, 0.9, 0.22)
    scuff(L / 2, W / 2, 1.1, 1.1, 0.16)
    for (let i = 0; i < 40; i++) scuff(rnd() * L, rnd() * W, 0.5 + rnd() * 1.2, 0.4 + rnd() * 0.8, 0.05 + rnd() * 0.06)
  }

  // Light falls off towards the edges of the ground, the way it does under
  // floodlights: the middle of the pitch is where the game is lit for.
  {
    ctx.save()
    ctx.translate(c.width / 2, c.height / 2)
    ctx.scale(c.width / 2, c.height / 2)
    const g = ctx.createRadialGradient(0, 0, 0.45, 0, 0, 1.15)
    g.addColorStop(0, 'rgba(0,10,20,0)')
    g.addColorStop(1, 'rgba(0,10,20,0.34)')
    ctx.fillStyle = g
    ctx.fillRect(-1.2, -1.2, 2.4, 2.4)
    ctx.restore()
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

export type CrowdKind = 'home' | 'away' | 'mixed'

// Tiered seating seen from the pitch.
//
// The old crowd was a flat scatter of fully-saturated shirts, and at the
// distance the stands are seen from that is exactly what it read as: coloured
// static that shimmered as you moved. A crowd isn't bright, it is dark with
// light in it — seats, shadow under every row, a lot of muted clothing and a
// minority of colour — so that is what is painted: a stepped bowl in deep
// shadow, aisles between the blocks, most of the people in dull colours and the
// team colours concentrated where each set of fans would actually be, and a
// scattering of phone lights, which is what a night crowd actually looks like.
export function crowdTexture(kind: CrowdKind = 'mixed'): HTMLCanvasElement {
  const [c, ctx] = canvas(1024, 512)
  const rnd = rng(kind === 'home' ? 111 : kind === 'away' ? 222 : 333)
  ctx.fillStyle = '#0d1119'
  ctx.fillRect(0, 0, c.width, c.height)

  const dull = ['#252c39', '#2d3546', '#3a4354', '#1d2330', '#4a5466', '#2a2f3b', '#515a6b']
  const pale = ['#8b94a8', '#b4bccb', '#d6dae3']
  const blue = ['#2b62d1', '#3f7be8', '#78a9ff']
  const red = ['#c93434', '#e04b4b', '#ff8a80']
  const gold = ['#e6b93c']
  const skins = ['#e8b48c', '#c98f63', '#8d5a3b', '#6b4028', '#f0c9a8']

  // The chance of a spectator wearing each kind of colour, per stand.
  const mix =
    kind === 'home' ? { team: blue, other: red, pTeam: 0.62, pOther: 0.02, pPale: 0.12, pGold: 0.02 }
    : kind === 'away' ? { team: red, other: blue, pTeam: 0.62, pOther: 0.02, pPale: 0.12, pGold: 0.02 }
    : { team: blue, other: red, pTeam: 0.24, pOther: 0.24, pPale: 0.14, pGold: 0.04 }

  const rows = 26
  const rowH = c.height / rows
  const perRow = 34
  const gap = c.width / perRow
  const blockW = c.width / 8

  for (let r = 0; r < rows; r++) {
    const y = r * rowH
    // The row behind is higher, so its floor is in shadow behind the one in front.
    ctx.fillStyle = r % 2 === 0 ? '#151b26' : '#121722'
    ctx.fillRect(0, y, c.width, rowH)
    // Concrete step edge, catching a little light.
    ctx.fillStyle = 'rgba(120,132,152,0.16)'
    ctx.fillRect(0, y + rowH * 0.86, c.width, rowH * 0.05)
    ctx.fillStyle = 'rgba(0,0,0,0.5)'
    ctx.fillRect(0, y + rowH * 0.91, c.width, rowH * 0.09)

    const offset = (r % 2) * gap * 0.5
    for (let i = 0; i < perRow + 1; i++) {
      const x = i * gap + offset + (rnd() - 0.5) * gap * 0.16
      // Aisles: a gap between blocks of seating.
      const inBlock = x % blockW
      if (inBlock < gap * 0.55) continue
      if (rnd() < 0.09) continue // empty seat

      const roll = rnd()
      let col: string
      if (roll < mix.pTeam) col = mix.team[(rnd() * mix.team.length) | 0]
      else if (roll < mix.pTeam + mix.pOther) col = mix.other[(rnd() * mix.other.length) | 0]
      else if (roll < mix.pTeam + mix.pOther + mix.pPale) col = pale[(rnd() * pale.length) | 0]
      else if (roll < mix.pTeam + mix.pOther + mix.pPale + mix.pGold) col = gold[0]
      else col = dull[(rnd() * dull.length) | 0]

      const bodyW = gap * 0.7
      const bodyH = rowH * 0.62
      const by = y + rowH * 0.3
      ctx.globalAlpha = 0.82 + rnd() * 0.18
      ctx.fillStyle = col
      ctx.beginPath()
      ctx.moveTo(x - bodyW / 2, by + bodyH)
      ctx.quadraticCurveTo(x - bodyW / 2, by, x, by)
      ctx.quadraticCurveTo(x + bodyW / 2, by, x + bodyW / 2, by + bodyH)
      ctx.closePath()
      ctx.fill()
      // Skin is dimmer than it is in daylight, and small.
      ctx.fillStyle = skins[(rnd() * skins.length) | 0]
      ctx.globalAlpha = 0.72
      ctx.beginPath()
      ctx.arc(x, by - rowH * 0.06, rowH * 0.17, 0, Math.PI * 2)
      ctx.fill()
      // Some of them have their arms up.
      if (rnd() < 0.06) {
        ctx.globalAlpha = 0.85
        ctx.strokeStyle = col
        ctx.lineWidth = 2
        ctx.beginPath()
        ctx.moveTo(x - bodyW * 0.35, by + bodyH * 0.3)
        ctx.lineTo(x - bodyW * 0.55, by - rowH * 0.55)
        ctx.moveTo(x + bodyW * 0.35, by + bodyH * 0.3)
        ctx.lineTo(x + bodyW * 0.55, by - rowH * 0.55)
        ctx.stroke()
      }
      ctx.globalAlpha = 1
    }
  }

  // Phone lights. Small, hard-edged and bright — a handful is a night match.
  for (let i = 0; i < 110; i++) {
    const x = rnd() * c.width
    const y = rnd() * c.height
    const g = ctx.createRadialGradient(x, y, 0, x, y, 5)
    g.addColorStop(0, 'rgba(255,255,255,0.95)')
    g.addColorStop(0.35, 'rgba(210,230,255,0.4)')
    g.addColorStop(1, 'rgba(210,230,255,0)')
    ctx.fillStyle = g
    ctx.fillRect(x - 5, y - 5, 10, 10)
  }

  // The nearer rows catch the light, the far ones fall away into the dark.
  const shade = ctx.createLinearGradient(0, 0, 0, c.height)
  shade.addColorStop(0, 'rgba(4,8,16,0.55)')
  shade.addColorStop(0.5, 'rgba(4,8,16,0.18)')
  shade.addColorStop(1, 'rgba(4,8,16,0)')
  ctx.fillStyle = shade
  ctx.fillRect(0, 0, c.width, c.height)
  return c
}

// ------------------------------------------------------------ ad boards ----

// A strip of LED sponsor panels. They are lit from inside, so they are drawn
// bright and saturated with a dark scan structure over them — legible from
// across the pitch and reading as a screen, not a poster.
export function adTexture(): HTMLCanvasElement {
  const [c, ctx] = canvas(2048, 256)
  const panels: [string, string, string][] = [
    ['#0c6b3a', '#eafff1', 'OPEN PITCH'],
    ['#1746a8', '#ffffff', 'NORTHSIDE'],
    ['#b42a2a', '#fff1f1', 'REDLINE'],
    ['#f2f3f7', '#12233f', 'RIVERSIDE FC'],
    ['#f2b705', '#151515', 'APEX BOOTS'],
    ['#12805a', '#ffffff', 'PLAY FAIR'],
    ['#6a2fa0', '#f3e8ff', 'MERIDIAN'],
    ['#101318', '#ff5a3c', 'LAKESIDE'],
  ]
  const pw = c.width / panels.length
  panels.forEach(([bg, fg, text], i) => {
    const x = i * pw
    const g = ctx.createLinearGradient(x, 0, x, c.height)
    g.addColorStop(0, bg)
    g.addColorStop(1, bg)
    ctx.fillStyle = g
    ctx.fillRect(x, 0, pw - 6, c.height)
    ctx.fillStyle = fg
    ctx.font = `900 118px ${DISPLAY_STACK}`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(text, x + pw / 2 - 3, c.height / 2 + 8, pw - 40)
    // A soft sheen along the top of each panel.
    const sheen = ctx.createLinearGradient(x, 0, x, c.height * 0.5)
    sheen.addColorStop(0, 'rgba(255,255,255,0.22)')
    sheen.addColorStop(1, 'rgba(255,255,255,0)')
    ctx.fillStyle = sheen
    ctx.fillRect(x, 0, pw - 6, c.height * 0.5)
  })
  // LED structure.
  ctx.fillStyle = 'rgba(0,0,0,0.22)'
  for (let y = 0; y < c.height; y += 4) ctx.fillRect(0, y, c.width, 1.5)
  for (let x = 0; x < c.width; x += 4) ctx.fillRect(x, 0, 1.5, c.height)
  return c
}

// A bank of floodlamps, seen face on: a dark housing with a grid of lit lenses.
export function floodlightTexture(): HTMLCanvasElement {
  const [c, ctx] = canvas(256, 128)
  ctx.fillStyle = '#1a1e26'
  ctx.fillRect(0, 0, c.width, c.height)
  const cols = 8
  const rows = 4
  const cw = c.width / cols
  const ch = c.height / rows
  for (let r = 0; r < rows; r++) {
    for (let i = 0; i < cols; i++) {
      const cx = i * cw + cw / 2
      const cy = r * ch + ch / 2
      const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, cw * 0.5)
      g.addColorStop(0, '#ffffff')
      g.addColorStop(0.5, '#fff2c8')
      g.addColorStop(1, '#8a7a4a')
      ctx.fillStyle = g
      ctx.beginPath()
      ctx.arc(cx, cy, cw * 0.4, 0, Math.PI * 2)
      ctx.fill()
    }
  }
  return c
}

// A soft round glow, for the halo round a lamp.
export function glowTexture(): HTMLCanvasElement {
  const [c, ctx] = canvas(128)
  const g = ctx.createRadialGradient(64, 64, 0, 64, 64, 64)
  g.addColorStop(0, 'rgba(255,248,224,1)')
  g.addColorStop(0.12, 'rgba(255,238,196,0.62)')
  g.addColorStop(0.4, 'rgba(255,225,170,0.18)')
  g.addColorStop(1, 'rgba(255,225,170,0)')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, 128, 128)
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
  ctx.font = `800 64px ${DISPLAY_STACK}`
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

// The classic ball: twelve black pentagons and the seams that join them.
//
// This is a truncated icosahedron, and it is computed as one rather than
// sketched. That matters for more than looks. Spin is what this game is about
// and the only way to *see* spin is to have something on the ball that is
// unmistakably turning — a scatter of soft curves reads as a white ball
// whichever way it is going, where twelve hard black patches spinning at
// forty turns a second read as spin at a glance.
//
// Every pentagon is the five points a third of the way along the edges out of
// one icosahedron vertex, pushed out to the sphere; the seam between two
// hexagons is the middle third of the edge between two vertices.
export function ballTexture(): HTMLCanvasElement {
  const W = 1024
  const H = 512
  const [c, ctx] = canvas(W, H)
  const rnd = rng(90210)

  const phi = (1 + Math.sqrt(5)) / 2
  const verts: [number, number, number][] = []
  for (const a of [-1, 1]) {
    for (const b of [-phi, phi]) {
      verts.push([0, a, b], [a, b, 0], [b, 0, a])
    }
  }
  const dist = (p: number[], q: number[]) => Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2])
  const norm = (p: number[]): [number, number, number] => {
    const l = Math.hypot(p[0], p[1], p[2])
    return [p[0] / l, p[1] / l, p[2] / l]
  }
  const lerp3 = (p: number[], q: number[], t: number) => [p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t]

  // Sphere → texture: longitude along x, latitude down y (north at the top).
  const toPx = (p: number[], lon0: number): [number, number, number] => {
    let lon = Math.atan2(p[2], p[0])
    // Keep a shape's points on one side of the seam so its edges don't fly across the map.
    while (lon - lon0 > Math.PI) lon -= Math.PI * 2
    while (lon - lon0 < -Math.PI) lon += Math.PI * 2
    const lat = Math.asin(Math.max(-1, Math.min(1, p[1])))
    return [((lon / (Math.PI * 2)) + 0.5) * W, (0.5 - lat / Math.PI) * H, lon]
  }
  // A great-circle arc between two points, subdivided so it bends properly.
  const arc = (p: number[], q: number[], steps = 8): number[][] => {
    const out: number[][] = []
    for (let i = 0; i <= steps; i++) out.push(norm(lerp3(p, q, i / steps)))
    return out
  }

  // Leather: a warm white with soft variation across the panels.
  ctx.fillStyle = '#f4f3ee'
  ctx.fillRect(0, 0, W, H)
  for (let i = 0; i < 90; i++) {
    const x = rnd() * W
    const y = rnd() * H
    const r = 30 + rnd() * 70
    const g = ctx.createRadialGradient(x, y, 0, x, y, r)
    g.addColorStop(0, rnd() > 0.5 ? 'rgba(255,255,255,0.35)' : 'rgba(170,175,185,0.22)')
    g.addColorStop(1, 'rgba(255,255,255,0)')
    ctx.fillStyle = g
    ctx.fillRect(x - r, y - r, r * 2, r * 2)
  }

  const drawShape = (pts: number[][], fill: string | null, stroke: string | null, width: number) => {
    const [, , lon0] = toPx(pts[0], 0)
    const px = pts.map((p) => toPx(p, lon0))
    for (const shift of [-W, 0, W]) {
      ctx.beginPath()
      px.forEach(([x, y], i) => (i ? ctx.lineTo(x + shift, y) : ctx.moveTo(x + shift, y)))
      if (fill) {
        ctx.closePath()
        ctx.fillStyle = fill
        ctx.fill()
      }
      if (stroke) {
        ctx.strokeStyle = stroke
        ctx.lineWidth = width
        ctx.lineJoin = 'round'
        ctx.lineCap = 'round'
        ctx.stroke()
      }
    }
  }

  // Seams first, under the patches.
  for (let i = 0; i < verts.length; i++) {
    for (let j = i + 1; j < verts.length; j++) {
      if (Math.abs(dist(verts[i], verts[j]) - 2) > 0.01) continue
      drawShape(arc(lerp3(verts[i], verts[j], 1 / 3), lerp3(verts[i], verts[j], 2 / 3), 6), null, '#9aa1ae', 4)
    }
  }

  // Patches.
  for (const A of verts) {
    const nb = verts.filter((q) => Math.abs(dist(A, q) - 2) < 0.01)
    // Order the five neighbours cyclically around A.
    const n = norm(A)
    const ref = Math.abs(n[1]) < 0.9 ? [0, 1, 0] : [1, 0, 0]
    const e1 = norm([ref[1] * n[2] - ref[2] * n[1], ref[2] * n[0] - ref[0] * n[2], ref[0] * n[1] - ref[1] * n[0]])
    const e2 = [n[1] * e1[2] - n[2] * e1[1], n[2] * e1[0] - n[0] * e1[2], n[0] * e1[1] - n[1] * e1[0]]
    const key = (q: number[]) => {
      const d = [q[0] - A[0], q[1] - A[1], q[2] - A[2]]
      return Math.atan2(d[0] * e2[0] + d[1] * e2[1] + d[2] * e2[2], d[0] * e1[0] + d[1] * e1[1] + d[2] * e1[2])
    }
    nb.sort((p, q) => key(p) - key(q))
    const corners = nb.map((q) => norm(lerp3(A, q, 1 / 3)))
    const outline: number[][] = []
    for (let k = 0; k < corners.length; k++) {
      const seg = arc(corners[k], corners[(k + 1) % corners.length], 6)
      seg.pop()
      outline.push(...seg)
    }
    drawShape(outline, '#171a20', '#171a20', 3)
  }

  // Fine grain, for the surface.
  ctx.globalAlpha = 0.05
  for (let i = 0; i < 7000; i++) {
    ctx.fillStyle = rnd() > 0.5 ? '#000' : '#fff'
    ctx.fillRect(rnd() * W, rnd() * H, 2, 2)
  }
  ctx.globalAlpha = 1
  return c
}

// --------------------------------------------------------------- number ----

export function numberTexture(num: number, color: string): HTMLCanvasElement {
  const [c, ctx] = canvas(256)
  ctx.clearRect(0, 0, c.width, c.height)
  ctx.fillStyle = color
  ctx.font = `900 200px ${DISPLAY_STACK}`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.lineWidth = 10
  ctx.strokeStyle = 'rgba(0,0,0,0.35)'
  ctx.strokeText(String(num), 128, 140)
  ctx.fillText(String(num), 128, 140)
  return c
}
