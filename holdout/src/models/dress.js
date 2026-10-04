// Dressing a figure: skin, clothes, face, hair, hats, footwear and kit, all
// laid over the lofted body from body.js so every piece fits the same shape.
// Clothes are lofts with thickness, hems, folds and woven-in patterns; small
// hardware (buttons, buckles, pouches, badges) is rigid and sits on the
// outermost cloth surface, oriented to it.
import * as THREE from 'three'
import { Part, Sink, lay, flushSink, anatomy, nosePart, hairline, footPart, smooth, vn3, fbm3 } from './body.js'

const TAU = Math.PI * 2
const PI = Math.PI
// angle away from the front of a part: 0 at the front centre, pi at the back
const af = (th) => Math.abs(Math.atan2(Math.cos(th), Math.sin(th)))
const C = (h) => new THREE.Color(h)
const lerpC = (o, a, b, t) => {
  o.r = a.r + (b.r - a.r) * t
  o.g = a.g + (b.g - a.g) * t
  o.b = a.b + (b.b - a.b) * t
  return o
}
const BLOOD = C('#3a0906')
const BLOOD2 = C('#5c140d')
const DIRT = C('#4a3e30')
const BRUISE = C('#4a3a48')
const NECK = 1.487
const hex = (c) => '#' + c.getHexString()
const tint = (h, k) => hex(C(h).multiplyScalar(k))
const mixHex = (a, b2, t) => hex(C(a).lerp(C(b2), t))

// ---------------------------------------------------------------- colours
// Cloth: base tone with mottling plus optional plaid, camo, denim wash, side
// stripes, grime creeping up from the ground and (on the dead) blood.
function fabric(h, o = {}) {
  const base = C(h)
  const alt = o.check ? C(o.check) : null
  const stripe = o.stripe ? C(o.stripe) : null
  const camoC = o.camo ? [base.clone().multiplyScalar(0.6), base.clone().multiplyScalar(1.25), C('#3a3424'), C('#6a5a3a')] : null
  const sd = o.seed || 0
  const blood = o.blood || 0
  const dirt = o.dirt ?? 0.25
  const grease = o.grease ? C('#22201c') : null
  return (x) => {
    const c = x.c.copy(base)
    if (alt) {
      const p = o.period || 0.085
      const a = 0.5 + 0.5 * Math.cos((x.yy / p) * TAU)
      const bnd = 0.5 + 0.5 * Math.cos(((x.th * x.part.rad) / p) * TAU)
      const k = Math.min(0.9, Math.pow(a, 4) * 0.55 + Math.pow(bnd, 4) * 0.55)
      lerpC(c, c, alt, k)
      // the light thread between the dark bands
      const l = Math.pow(0.5 + 0.5 * Math.cos((x.yy / p + 0.5) * TAU), 14) + Math.pow(0.5 + 0.5 * Math.cos(((x.th * x.part.rad) / p + 0.5) * TAU), 14)
      c.multiplyScalar(1 + Math.min(1, l) * 0.18)
    }
    if (camoC) {
      const n1 = fbm3(x.x * 7 + 11, x.yy * 7, x.z * 7)
      const n2 = fbm3(x.x * 8 + 37, x.yy * 8 + 5, x.z * 8)
      const n3 = fbm3(x.x * 9 + 71, x.yy * 9 + 9, x.z * 9)
      lerpC(c, c, camoC[0], smooth(0.55, 0.6, n1))
      lerpC(c, c, camoC[3], smooth(0.57, 0.62, n2))
      lerpC(c, c, camoC[2], smooth(0.62, 0.66, n3))
      lerpC(c, c, camoC[1], smooth(0.41, 0.36, n1) * 0.8)
    }
    if (o.denim) {
      // fading where denim wears: thigh fronts, knees and the seat
      const front = smooth(0.15, 0.95, x.lz)
      const wear = Math.min(1, Math.exp(-(((x.y - 0.7) / 0.12) ** 2)) * 0.9 + Math.exp(-(((x.y - 0.5) / 0.04) ** 2)) * 0.8)
      const seat = x.part.limb ? 0 : smooth(-0.3, -0.8, x.lz) * Math.exp(-(((x.yy - 0.9) / 0.05) ** 2))
      c.multiplyScalar(1 + 0.34 * front * wear + 0.22 * seat)
      if (x.part.limb && x.lx > 0 && Math.abs(x.lz) < 0.07) c.multiplyScalar(0.8)
    }
    if (stripe && x.part.limb && x.lx > 0 && Math.abs(x.lz) < 0.2) lerpC(c, c, stripe, smooth(0.2, 0.12, Math.abs(x.lz)))
    const n = vn3(x.x * 11 + sd, x.yy * 11, x.z * 11)
    c.multiplyScalar(0.93 + n * 0.12)
    if (dirt) {
      const g = fbm3(x.x * 6 + sd * 3, x.yy * 6, x.z * 6)
      const k = (1 - smooth(0.04, 0.32, x.yy)) * 0.55 + smooth(0.56, 0.8, g) * 0.35
      lerpC(c, c, DIRT, Math.min(1, k * dirt))
    }
    if (grease) {
      const g = fbm3(x.x * 9 + 5, x.yy * 9 + 1, x.z * 9)
      lerpC(c, c, grease, smooth(0.62, 0.7, g) * 0.7)
    }
    if (blood) {
      const g = fbm3(x.x * 5.5 + sd * 7, x.yy * 5.5 + 3, x.z * 5.5)
      lerpC(c, c, g > 0.7 ? BLOOD : BLOOD2, smooth(0.55, 0.65, g) * blood)
    }
    // white cloth in hard sun blooms; keep albedo below that
    const lum = c.r * 0.3 + c.g * 0.59 + c.b * 0.11
    if (lum > 0.5) c.multiplyScalar(0.5 / lum)
  }
}
// Skin with pores-scale mottling; on the dead, bruising, veins and gore.
function skinFn(h, Z, sd, o = {}) {
  const base = C(h)
  const tat = o.tattoo ? C('#2a3a46') : null
  return (x) => {
    const c = x.c.copy(base)
    c.multiplyScalar(0.95 + vn3(x.x * 26 + sd, x.yy * 26, x.z * 26) * 0.09)
    if (tat && x.part.limb && x.yy > 0.98 && x.yy < 1.38) {
      const t = fbm3(x.x * 30 + 3, x.yy * 30, x.z * 30)
      const band = Math.abs(Math.sin(x.yy * 60 + x.th * 2)) < 0.25 ? 1 : 0
      lerpC(c, c, tat, Math.max(smooth(0.6, 0.64, t), band * smooth(0.4, 0.5, t)) * 0.85)
    }
    if (Z) {
      const m = fbm3(x.x * 8 + sd, x.yy * 8, x.z * 8)
      lerpC(c, c, BRUISE, smooth(0.5, 0.7, m) * 0.6)
      const v = vn3(x.x * 70 + sd, x.yy * 70, x.z * 70)
      if (Math.abs(v - 0.5) < 0.035) c.multiplyScalar(0.78)
      const g = fbm3(x.x * 5 + sd * 3, x.yy * 5, x.z * 5)
      lerpC(c, c, BLOOD2, smooth(0.63, 0.72, g) * 0.85)
    }
  }
}
// The face: blushed cheeks and nose, shadowed sockets, beard shadow; on the
// dead, dark sunken sockets and a bloody mouth.
function faceFn(h, spec, head, sd) {
  const base = C(h)
  const blushC = base.clone().lerp(C('#d0605a'), 0.4)
  const light = Math.min(1, base.getHSL({}).l * 1.6)
  const shadowC = base.clone().multiplyScalar(0.72)
  const hairC = C(spec.hair?.color || '#2a2018')
  const Y = head.Y
  const Z = spec.zombie
  const stub = !spec.female && (Z || ['beard', 'goatee', 'mustache', 'stubble'].includes(spec.face?.beard) || (spec.seed || 0) % 3 === 0)
  return (x) => {
    const c = x.c.copy(base)
    c.multiplyScalar(0.96 + vn3(x.x * 40 + sd, x.yy * 40, x.z * 40) * 0.07)
    const front = smooth(0.05, 0.8, x.lz)
    const ax = Math.abs(x.lx)
    const bl = Math.exp(-(((x.y - Y(1.633)) / 0.017) ** 2) - (((ax - 0.054) / 0.021) ** 2)) * front
    lerpC(c, c, blushC, bl * (spec.female ? 0.55 : 0.32) * light)
    const so = Math.exp(-(((x.y - Y(1.669)) / 0.011) ** 2) - (((ax - 0.036) / 0.019) ** 2)) * front
    lerpC(c, c, shadowC, so * (Z ? 1 : 0.3))
    if (stub) {
      const a = af(x.th)
      const jaw = smooth(Y(1.64), Y(1.605), x.y) * smooth(1.55, 1.15, a) * (1 - Math.exp(-(((x.y - Y(1.612)) / 0.008) ** 2) - ((ax / 0.022) ** 2)) * 0.8)
      lerpC(c, c, hairC, jaw * (Z ? 0.18 : 0.26))
    }
    if (Z) {
      const m = fbm3(x.x * 10 + sd, x.yy * 10, x.z * 10)
      lerpC(c, c, BRUISE, smooth(0.5, 0.7, m) * 0.6)
      const mouth = Math.exp(-(((x.y - Y(1.585)) / 0.03) ** 2) - ((ax / 0.035) ** 2)) * front
      lerpC(c, c, BLOOD, Math.min(1, mouth * 1.3) * (0.4 + vn3(x.x * 50, x.yy * 50, 3) * 0.6))
    }
  }
}

// ---------------------------------------------------------------- garments
// hem: bottom of the torso garment; neck: neckline style; off: thickness;
// open: half-angle of an open front; skirt: continues below the waist.
const TOPS = {
  tee: { hem: 0.952, neck: 'crew', off: 0.008, loose: 0.005 },
  polo: { hem: 0.958, neck: 'polo', off: 0.008, loose: 0.004, placket: 2 },
  shirt: { hem: 0.995, neck: 'collar', off: 0.006, tuck: true, placket: 6 },
  uniform: { hem: 0.995, neck: 'collar', off: 0.007, tuck: true, placket: 6, pockets: 2, epaulettes: true },
  flannel: { hem: 0.948, neck: 'collar', off: 0.01, loose: 0.005, placket: 6, pockets: 2 },
  hoodie: { hem: 0.915, neck: 'hood', off: 0.016, loose: 0.008, band: 0.032, cuffs: true },
  jacket: { hem: 0.94, neck: 'lapel', off: 0.022, open: 0.2, band: 0.026, inner: true, cuffs: true },
  coat: { hem: 0.6, neck: 'lapel', off: 0.02, open: 0.15, skirt: true, inner: true, buttons: true },
  cardigan: { hem: 0.925, neck: 'v', v: 0.13, off: 0.016, open: 0.1, inner: true, buttons: true, band: 0.022, cuffs: true },
  scrubs: { hem: 0.94, neck: 'v', off: 0.012, loose: 0.007, pockets: 1 },
  coveralls: { hem: 0.995, neck: 'collar', off: 0.012, zip: true },
  chef: { hem: 0.84, neck: 'mandarin', off: 0.014, skirt: true, double: true },
  turnout: { hem: 0.79, neck: 'stand', off: 0.03, skirt: true, closures: true, cuffs: true },
  track: { hem: 0.94, neck: 'stand', off: 0.012, zip: true, band: 0.026, cuffs: true },
  tank: { hem: 0.955, neck: 'tank', off: 0.005 },
  gown: { hem: 0.56, neck: 'crew', off: 0.012, skirt: true, backOpen: 0.22 },
}
function neckline(S) {
  switch (S.neck) {
    case 'crew':
      return (th) => NECK - 0.013 * smooth(0.75, 0, af(th))
    case 'v':
      return (th) => NECK - (S.v ?? 0.085) * Math.max(0, 1 - af(th) / 0.5)
    case 'lapel':
      return (th) => NECK + 0.004 - (S.v ?? 0.15) * Math.max(0, 1 - af(th) / 0.6)
    case 'tank':
      return (th) => {
        const a = af(th)
        let y = 1.398 + (1.338 - 1.398) * smooth(0.36, 0.5, a)
        y += (1.432 - 1.338) * smooth(2.5, 2.7, a)
        const strap = Math.max(Math.exp(-(((a - 0.66) / 0.17) ** 2)), Math.exp(-(((a - 2.52) / 0.17) ** 2)))
        return y + (NECK + 0.002 - y) * smooth(0.25, 0.75, strap)
      }
    default:
      return (th) => NECK + 0.002 - 0.006 * smooth(0.5, 0, af(th))
  }
}
// A skirt part hanging from the waist, clear of both legs: coats, gowns.
function skirtPart(A, flare = 1) {
  const t = A.torso
  const keys = []
  for (const y of [1.2, 1.16, 1.1, 1.05, 1.0, 0.95]) {
    const q = t.at(y, {})
    keys.push({ y, x: 0, z: q.z, w: q.w + 0.002, f: q.f + 0.002, k: q.k + 0.003, n: q.n, nb: q.nb })
  }
  const lo = (y) => {
    const q = A.legL.at(y, {})
    return Math.abs(q.x) + q.w + 0.014
  }
  const q9 = t.at(0.9, {})
  keys.push({ y: 0.89, x: 0, z: -0.004, w: Math.max(q9.w + 0.006, lo(0.89)), f: q9.f + 0.006, k: q9.k + 0.01, n: 2.3, nb: 2.4 })
  const w8 = lo(0.8) + 0.008
  keys.push({ y: 0.8, x: 0, z: 0, w: w8, f: 0.106 * A.B, k: 0.12 * A.B, n: 2.2, nb: 2.2 })
  for (const y of [0.68, 0.56, 0.44, 0.3]) keys.push({ y, x: 0, z: 0.002, w: w8 + (0.8 - y) * 0.085 * flare, f: (0.108 + (0.8 - y) * 0.05 * flare) * A.B, k: (0.122 + (0.8 - y) * 0.05 * flare) * A.B, n: 2.2, nb: 2.2 })
  const p = new Part(keys, { bumps: A.torso.bumps.filter((b) => b.y > 0.8 && b.y < 1.25) })
  p.rad = 0.17
  return p
}
// skirts follow the hips at the waist and partly each thigh lower down
function skirtW(y, lx, lz, acc, x) {
  const H = 1
  const SP = 2
  const TL = 14
  const TR = 17
  if (y > 1.0) {
    const s = smooth(1.0, 1.12, y)
    acc[SP] = s
    acc[H] = 1 - s
    return
  }
  const d = Math.min(1, Math.max(0, (0.96 - y) / 0.42)) * 0.72
  const sl = smooth(-0.07, 0.07, x)
  acc[H] = 1 - d
  acc[TL] = d * sl
  acc[TR] = d * (1 - sl)
}

// ---------------------------------------------------------------- main
export function dress(b, spec, rnd) {
  const sink = new Sink()
  const A = anatomy(spec)
  const { torso, armL, armR, legL, legR, head } = A
  torso.rad = 0.14
  armL.rad = armR.rad = 0.045
  legL.rad = legR.rad = 0.065
  armL.limb = armR.limb = legL.limb = legR.limb = true
  head.rad = 0.09
  const Z = spec.zombie
  const res = spec.res ?? (Z ? 0.72 : 1)
  const O = spec.outfit
  const sd = (spec.seed || 1) % 997
  const top = O.top || { kind: 'tee', color: '#777777', sleeves: 'short' }
  const legs = O.legs || { kind: 'jeans', color: '#334455' }
  const shoes = O.shoes || { kind: 'boots', color: '#2a2420' }
  const extras = O.extras || []
  const has = (e) => extras.includes(e)
  const crawler = Z?.kind === 'crawler'
  const skinHex = spec.skin || '#d39d76'
  const P = { torso, armL, armR, legL, legR }
  const LAY = { torso: [], armL: [], armR: [], legL: [], legR: [] }
  const COLS = { torso: 38, armL: 16, armR: 16, legL: 20, legR: 20 }
  const DY = { torso: 0.0135, armL: 0.015, armR: 0.015, legL: 0.017, legR: 0.017 }
  const both = (a, L) => {
    LAY[a + 'L'].push(L)
    LAY[a + 'R'].push({ ...L })
  }
  const rag = (amp, f = 3, o = 0) => (Z ? (th) => (vn3(Math.cos(th) * f + o + sd, Math.sin(th) * f, sd * 0.37) - 0.35) * amp : () => 0)

  // ---- legwear
  const lk = legs.kind
  const tucked = shoes.kind === 'boots' && ['cargo', 'coveralls'].includes(lk)
  const POFF = { jeans: 0.008, cargo: 0.011, slacks: 0.006, scrubs: 0.012, overalls: 0.011, coveralls: 0.012, track: 0.01, shorts: 0.01, bare: 0.0025 }[lk] ?? 0.008
  const pMat = lk === 'jeans' ? 'denim' : 'cloth'
  const pCol = fabric(legs.color || '#334455', { denim: lk === 'jeans', camo: legs.camo, stripe: legs.stripe, blood: Z ? 0.55 : 0, dirt: Z ? 0.6 : 0.28, grease: has('grease'), seed: sd + 1 })
  const waistTop = lk === 'overalls' || lk === 'coveralls' ? 1.06 : 1.035
  if (lk === 'bare') {
    const bc = fabric('#d6d0c2', { dirt: 0.6, blood: Z ? 0.4 : 0, seed: sd })
    LAY.torso.push({ mat: 'cloth', col: bc, y0: torso.y0, y1: 0.93, off: 0.0025, capLo: true, hemHi: { t: 0.002, h: 0.01 } })
    both('leg', { mat: 'cloth', col: bc, y0: 0.865, y1: legL.y1, off: 0.0025, hemLo: { t: 0.002, h: 0.01 } })
  } else {
    LAY.torso.push({ mat: pMat, col: pCol, y0: torso.y0, y1: waistTop, off: (y) => POFF + 0.0015 * smooth(waistTop - 0.04, waistTop - 0.03, y), capLo: true, hemHi: { t: 0.0025, h: 0.034 } })
    const overBoots = shoes.kind === 'boots' && !tucked
    const hemY = lk === 'shorts' ? 0.56 : tucked ? 0.16 : overBoots ? 0.105 : 0.066
    const r1 = rag(0.1)
    const bag = { cargo: 0.006, scrubs: 0.009, track: 0.004, coveralls: 0.005, overalls: 0.004 }[lk] ?? 0
    const brk = lk === 'shorts' || tucked ? 0 : overBoots ? 0.026 : 0.017
    both('leg', {
      mat: pMat,
      col: pCol,
      y0: (th) => hemY + (lk === 'shorts' || tucked ? 0 : 0.016 * Math.max(0, Math.sin(th))) + Math.abs(r1(th)),
      y1: legL.y1,
      off: (y, th, lx, lz) => {
        let o = POFF + bag * smooth(0.95, 0.65, y) * smooth(0.18, 0.42, y)
        o += brk * (1 - smooth(hemY, hemY + 0.11, y))
        if (tucked) o += 0.009 * (1 - smooth(hemY, hemY + 0.07, y))
        o += 0.0024 * Math.sin(y * 140) * Math.exp(-(((y - 0.47) / 0.03) ** 2)) * (lz < 0 ? 1 : 0.3)
        o += 0.002 * Math.sin(y * 110 + lx * 40) * (1 - smooth(hemY, hemY + 0.15, y))
        if (lk === 'slacks') o += 0.0018 * Math.exp(-((lx / 0.008) ** 2)) * smooth(0.3, 0.9, Math.abs(lz)) // pressed crease
        return o
      },
      hemLo: { t: lk === 'track' ? 0.004 : 0.003, h: lk === 'track' ? 0.025 : 0.014 },
    })
  }

  // ---- top
  const tk = top.kind
  const S = { ...(TOPS[tk] || TOPS.tee) }
  if (tk === 'coat' && !top.open) S.open = 0
  const torn = Z && rnd() < 0.35 && !['coat', 'gown', 'chef', 'turnout'].includes(tk)
  let sleeves = top.sleeves || 'short'
  if (Z && sleeves === 'long' && rnd() < 0.4) sleeves = 'torn'
  if (tk === 'gown') sleeves = 'cap'
  const tCol = fabric(top.color || '#777777', { check: top.check, camo: top.camo, blood: Z ? 0.65 : 0, dirt: Z ? 0.5 : 0.12, grease: has('grease'), seed: sd + 2 })
  const tMat = 'cloth'
  const skirt = S.skirt ? skirtPart(A, tk === 'gown' ? 0.6 : 1) : null
  const neckY = neckline(S)
  const openA = S.open || 0
  const sheet = openA ? { th0: PI / 2 + openA, th1: PI / 2 - openA + TAU } : S.backOpen ? { th0: -PI / 2 + S.backOpen, th1: -PI / 2 - S.backOpen + TAU } : {}
  const hemRag = rag(0.09, 4, 1)
  const hem0 = torn ? (th) => 1.16 + Math.abs(hemRag(th)) : S.skirt ? 1.12 : (th) => S.hem + Math.abs(hemRag(th))
  const tuckFold = (y) => (S.tuck ? 0.0022 * Math.sin(y * 170) * Math.exp(-(((y - 1.045) / 0.028) ** 2)) : 0)
  // inner shirt shows inside open jackets, coats and cardigans
  if (S.inner) {
    const ic = fabric(top.inner || '#d8d4ca', { blood: Z ? 0.5 : 0, dirt: 0.1, seed: sd + 5 })
    LAY.torso.push({ mat: 'cloth', col: ic, y0: 0.99, y1: (th) => NECK - (top.collar ? 0 : 0.012) * smooth(0.75, 0, af(th)), off: 0.006, hemHi: { t: 0.003, h: 0.012 } })
    S.innerCol = ic
  }
  if (tk !== 'bare') {
    const loose = S.loose || 0
    LAY.torso.push({
      mat: openA ? 'clothDS' : tMat,
      col: tCol,
      y0: hem0,
      y1: neckY,
      ...sheet,
      off: (y, th, lx, lz) => {
        let o = S.off + loose * (1 - smooth(S.hem, S.hem + 0.14, y)) + tuckFold(y)
        // drape folds pulling from the chest down to the hem
        o += 0.0016 * Math.sin(th * 9 + y * 20) * smooth(1.25, 1.0, y)
        if (S.band) o += 0.004 * (1 - smooth(S.hem + S.band - 0.004, S.hem + S.band, y))
        // over the trouser waistband (or tucked under it)
        if (y < waistTop + 0.012 && lk !== 'bare') o = S.tuck ? Math.min(o, POFF - 0.002) : Math.max(o, POFF + 0.0085)
        return o
      },
      hemLo: S.skirt ? null : { t: S.band ? 0.003 : 0.0025, h: S.band ? 0.01 : 0.014 },
      hemHi: { t: 0.003, h: 0.012 },
    })
  }
  if (skirt) {
    const hemS = (th) => S.hem + Math.abs(hemRag(th)) + (tk === 'coat' ? 0.012 * Math.sin(th * 3) : 0)
    lay(sink, skirt, {
      mat: openA || S.backOpen ? 'clothDS' : tMat,
      col: tCol,
      y0: hemS,
      y1: 1.17,
      ...sheet,
      off: (y, th) => S.off + 0.004 * Math.sin(th * 7 + 1) * smooth(0.95, 0.7, y),
      hemLo: { t: 0.003, h: 0.014 },
      wfn: skirtW,
      cols: 36,
      dy: 0.02,
    }, res)
  }
  // ---- sleeves
  const sleeveRag = rag(0.12, 3, 4)
  if (tk !== 'tank' && tk !== 'bare' && sleeves !== 'none') {
    const rolled = top.rolled && sleeves === 'long' && !Z
    const sy0 = sleeves === 'short' ? 1.3 : sleeves === 'cap' ? 1.37 : sleeves === 'torn' ? 1.12 : rolled ? 1.085 : 0.932
    const cuffs = S.cuffs && sleeves === 'long' && !rolled
    both('arm', {
      mat: tMat,
      col: rolled
        ? (x) => {
          tCol(x)
          if (x.y < sy0 + 0.045) x.c.multiplyScalar(0.88)
        }
        : tCol,
      y0: (th) => sy0 + Math.abs(sleeveRag(th)),
      y1: armL.y1,
      off: (y, th, lx, lz) => {
        let o = S.off * 0.85 + 0.002
        if (sleeves === 'short' || sleeves === 'cap') o += 0.004 * (1 - smooth(sy0, sy0 + 0.07, y))
        if (sleeves === 'long' && !rolled) {
          o += 0.0028 * Math.sin(y * 150 + lx * 30) * Math.exp(-(((y - 1.16) / 0.04) ** 2)) * (lz > 0 ? 1 : 0.35)
          o += 0.004 * Math.exp(-(((y - 0.99) / 0.03) ** 2))
          if (cuffs) o -= 0.003 * (1 - smooth(0.932, 0.97, y))
        }
        if (rolled) o += 0.007 * (1 - smooth(sy0, sy0 + 0.045, y))
        return o
      },
      hemLo: { t: rolled ? 0.004 : cuffs ? 0.002 : 0.0025, h: rolled ? 0.04 : cuffs ? 0.035 : 0.012 },
      capHi: true,
    })
  }
  // ---- crawler: the legs end in stumps
  if (crawler) {
    for (const k of ['legL', 'legR']) LAY[k] = LAY[k].map((L) => ({ ...L, y0: 0.66 }))
  }

  // ---- skin wherever nothing covers it
  const skCol = skinFn(skinHex, Z, sd, { tattoo: has('tattoos') })
  for (const k of Object.keys(P)) {
    const part = P[k]
    let lo = crawler && k.startsWith('leg') ? 0.62 : part.y0
    const hi = part.y1
    const cover = []
    for (const L of LAY[k]) {
      if (L.th0 != null) continue
      let a = -Infinity
      let z = Infinity
      for (let i = 0; i < 48; i++) {
        const th = (i / 48) * TAU
        a = Math.max(a, typeof L.y0 === 'function' ? L.y0(th) : L.y0)
        z = Math.min(z, typeof L.y1 === 'function' ? L.y1(th) : L.y1)
      }
      if (z > a) cover.push([a <= part.y0 + 1e-3 ? a : a + 0.014, z >= part.y1 - 1e-3 ? z : z - 0.014])
    }
    cover.sort((p, q) => p[0] - q[0])
    const spans = []
    let cur = lo
    for (const [a, z] of cover) {
      if (a > cur + 0.004) spans.push([cur, a])
      cur = Math.max(cur, z)
    }
    if (hi > cur + 0.004) spans.push([cur, hi])
    for (const [a, z] of spans) {
      // leg skin entirely inside a shoe isn't worth drawing
      if (k.startsWith('leg') && z < 0.1 && !crawler) continue
      lay(sink, part, { mat: 'skin', col: skCol, y0: a, y1: z, cols: COLS[k], dy: DY[k], capLo: k === 'torso' && a <= part.y0 + 1e-3, capHi: k.startsWith('arm') && z >= hi - 1e-3, shell: false }, res)
    }
  }
  for (const k of Object.keys(P)) for (const L of LAY[k]) lay(sink, P[k], { cols: COLS[k], dy: DY[k], ...L }, res)
  if (crawler) {
    for (const p of [legL, legR]) lay(sink, p, { mat: 'gloss', col: '#4a0e0a', y0: 0.62, y1: 0.66, off: 0.002, capLo: true, cols: 14, dy: 0.01, shell: false }, res)
  }

  // ---- details on the clothes
  const ctx = { b, sink, A, spec, rnd, res, Z, sd, has, top, legs, S, tCol, pCol, POFF, waistTop, skirt, neckY, sleeves, torn }
  collars(ctx)
  closures(ctx)
  pantsDetail(ctx)
  if (torn) {
    b.rigid('spine')
    for (let i = 0; i < 4; i++) {
      for (const sx of [1, -1]) {
        onSurf(b, torso, 1.075 + i * 0.024, sx * 0.05, 1, 0.001, () => b.box(0.05, 0.007, 0.006, { mat: 'plain', color: '#cfc2a4', rz: sx * 0.25 }))
      }
    }
    onSurf(b, torso, 1.1, 0, 1, 0.0005, () => b.box(0.1, 0.09, 0.004, { mat: 'gloss', color: '#4a0e0a', r: 0.002 }))
  }

  // ---- head and face
  face(ctx)
  hairDo(ctx)
  beardDo(ctx)
  if (O.hat) hat(ctx, O.hat)

  // ---- hands and feet
  const glove = has('gloves') ? '#3a2e24' : null
  for (const side of ['L', 'R']) {
    hand(b, A, side, { skin: skinHex, glove, Z })
    if (!crawler) footwear(ctx, side)
  }

  // ---- kit
  for (const e of extras) extra(ctx, e)
  if (spec.armor || O.armor) armor(ctx, spec.armor || O.armor)
  if (spec.armorMods?.length) armorMods(ctx, spec.armorMods)
  if (spec.gear) gearLook(ctx, spec.gear)
  if (spec.pack) backpack(ctx, spec.pack === 'large')
  if (Z) gore(ctx)
  flushSink(sink, b)
}

// sample a colour function at the front of a part (for trims and patches)
function colAt(fn, part, y, lz = 1) {
  const q = { c: new THREE.Color(), x: 0, yy: y, z: 0.1 * lz, th: PI / 2, lx: 0.02, lz, y, part, edge: 1 }
  fn(q)
  return q.c
}
// Place a rigid piece on a part's outer surface with local +z along the
// surface normal (local x across, local y up the surface).
function onSurf(b, part, y, lx, side, extra, fn) {
  const s = part.surf(y, lx, side, extra)
  const [nx, ny, nz] = s.n
  b.at({ x: s.p[0], y: s.p[1], z: s.p[2], rx: -Math.asin(Math.max(-1, Math.min(1, ny))), ry: Math.atan2(nx, nz), order: 'YXZ' }, fn)
  return s
}
// a surface point (array) carrying its outward normal as .n
const sp = (part, y, lx, side, extra = 0) => {
  const s = part.surf(y, lx, side, extra)
  const p = s.p
  p.n = s.n
  return p
}
// a point on top of the shoulder line, between the front and back surfaces
function topPt(part, y, lx, lift = 0) {
  const f = sp(part, y, lx, 1)
  const k = sp(part, y, lx, -1)
  const p = [(f[0] + k[0]) / 2, Math.max(f[1], k[1]) + lift, (f[2] + k[2]) / 2]
  const n = [f.n[0] + k.n[0], f.n[1] + k.n[1] + 1.6, f.n[2] + k.n[2]]
  const l = Math.hypot(...n)
  p.n = n.map((v) => v / l)
  return p
}
const sub3 = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
const crs3 = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
const nrm3 = (a) => {
  const l = Math.hypot(a[0], a[1], a[2]) || 1
  return [a[0] / l, a[1] / l, a[2] / l]
}
// A flat strap lying on the body through surface points: its width runs
// across the surface, its thickness along the surface normal.
function strap(b, pts, w, t, o) {
  const N = pts.length
  if (N < 2) return
  const fr = pts.map((p, i) => {
    const d = nrm3(sub3(pts[Math.min(N - 1, i + 1)], pts[Math.max(0, i - 1)]))
    const n0 = p.n || nrm3([p[0], 0, p[2]])
    const sd = nrm3(crs3(d, n0))
    const n = nrm3(crs3(sd, d))
    return { p, sd, n }
  })
  const pos = []
  const nor = []
  const uv = []
  const idx = []
  // four faces: top, bottom and the two edges
  const faces = [[1, 1, 0], [-1, -1, 0], [1, 0, 1], [-1, 0, -1]]
  let len = 0
  for (const [k, cn, cs] of faces) {
    const base = pos.length / 3
    len = 0
    for (let i = 0; i < N; i++) {
      const { p, sd, n } = fr[i]
      if (i) len += Math.hypot(...sub3(p, fr[i - 1].p))
      const fn = cn ? n.map((v) => v * cn) : sd.map((v) => v * cs)
      for (const e of [-1, 1]) {
        // corners: across (e) and through (k) the strap
        const ax = cn ? e : k
        const tz = cn ? k : e
        pos.push(p[0] + sd[0] * ax * w * 0.5 + n[0] * tz * t * 0.5, p[1] + sd[1] * ax * w * 0.5 + n[1] * tz * t * 0.5, p[2] + sd[2] * ax * w * 0.5 + n[2] * tz * t * 0.5)
        nor.push(fn[0], fn[1], fn[2])
        uv.push(e * w, len)
      }
      if (i) {
        const a = base + (i - 1) * 2
        const q = [a, a + 1, a + 3, a, a + 3, a + 2]
        // face winding along the facing normal
        const A = pos.slice(q[0] * 3, q[0] * 3 + 3)
        const B = pos.slice(q[1] * 3, q[1] * 3 + 3)
        const Cc = pos.slice(q[2] * 3, q[2] * 3 + 3)
        const f = crs3(sub3(B, A), sub3(Cc, A))
        if (f[0] * fn[0] + f[1] * fn[1] + f[2] * fn[2] < 0) idx.push(q[0], q[2], q[1], q[3], q[5], q[4])
        else idx.push(...q)
      }
    }
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3))
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
  g.setIndex(idx)
  b.add(g, o)
}

// ---------------------------------------------------------------- collars
function collars(c) {
  const { b, sink, A, S, tCol, res, top } = c
  const T = A.torso
  const tk = top.kind
  if (tk === 'bare') return
  const NK = (o) => lay(sink, T, o, res)
  if (S.neck === 'collar' || S.neck === 'polo' || S.neck === 'mandarin' || S.neck === 'stand') {
    const hgt = S.neck === 'stand' ? 0.058 : S.neck === 'mandarin' ? 0.038 : S.neck === 'polo' ? 0.04 : 0.046
    const gap = S.neck === 'mandarin' || S.neck === 'stand' ? 0.09 : 0.2
    NK({
      mat: 'cloth',
      col: (x) => {
        tCol(x)
        x.c.multiplyScalar(1.04)
      },
      y0: 1.472,
      y1: (th) => 1.472 + hgt - 0.012 * smooth(0.7, 0, af(th)),
      th0: PI / 2 + gap,
      th1: PI / 2 - gap + TAU,
      off: (y) => S.off * 0.5 + 0.003 + (S.neck === 'stand' ? 0.005 : 0) + 0.002 * smooth(1.48, 1.5, y),
      hemHi: { t: 0.0025, h: 0.01 },
      cols: 30,
      dy: 0.008,
      shell: false,
    })
    if (S.neck === 'collar' || S.neck === 'polo') {
      // collar points folded down onto the chest
      b.rigid('chest')
      for (const sx of [1, -1]) {
        const cc = hex(colAt(tCol, T, 1.47).multiplyScalar(1.04))
        onSurf(b, T, 1.47, sx * 0.03, 1, 0.0035, () => {
          const pts = [[-sx * 0.006, 0.014], [sx * 0.03, 0.008], [sx * 0.012, -0.036]]
          b.extrude(sx > 0 ? pts : pts.reverse(), 0.0022, { mat: 'cloth', color: cc, rx: -0.12 })
          b.extrude(sx > 0 ? pts : pts.reverse(), 0.001, { mat: 'cloth', color: hex(C(cc).multiplyScalar(0.55)), z: -0.0018, s: 1.06 })
        })
      }
    }
  }
  if (S.neck === 'lapel') {
    // fold-over collar behind the neck and lapels down the front
    NK({ mat: 'cloth', col: tCol, y0: 1.465, y1: (th) => 1.52 - 0.02 * smooth(1.6, 0.9, af(th)), th0: PI / 2 + 0.6, th1: PI / 2 - 0.6 + TAU, off: S.off + 0.008, hemHi: { t: 0.004, h: 0.012 }, cols: 30, dy: 0.008, shell: false })
    const edge = S.open || 0.02
    for (const sx of [1, -1]) {
      // left lapel spans angles just outside the opening on +x, right on -x
      const a0 = sx > 0 ? PI / 2 - edge - 0.62 : PI / 2 + edge
      const a1 = sx > 0 ? PI / 2 - edge : PI / 2 + edge + 0.62
      NK({
        mat: 'cloth',
        col: (x) => {
          tCol(x)
          x.c.multiplyScalar(0.92)
        },
        th0: a0,
        th1: a1,
        // the lapel lies folded back along the coat's V edge
        y0: (th) => c.neckY(th) - 0.012 - 0.07 * smooth(0.62, 0.25, af(th)),
        y1: (th) => c.neckY(th) + 0.002,
        off: S.off + 0.0045,
        hemLo: { t: 0.0015, h: 0.004 },
        hemHi: { t: 0.0015, h: 0.004 },
        cols: 10,
        dy: 0.008,
        shell: false,
      })
    }
  }
  if (S.neck === 'hood') {
    // the hood bunched behind the neck and its rim round to the front
    NK({
      mat: 'cloth',
      col: (x) => {
        tCol(x)
        x.c.multiplyScalar(0.94)
      },
      y0: 1.448,
      y1: (th) => 1.53 + 0.05 * smooth(1.4, 3.0, af(th)),
      th0: PI / 2 + 0.55,
      th1: PI / 2 - 0.55 + TAU,
      off: (y, th) => S.off + 0.006 + 0.034 * smooth(1.0, 2.9, af(th)) * Math.sin(Math.min(1, Math.max(0, (y - 1.448) / 0.1)) * PI),
      hemHi: { t: 0.004, h: 0.012 },
      hemLo: { t: 0.002, h: 0.01 },
      cols: 30,
      dy: 0.008,
      shell: false,
    })
    // drawstrings
    b.rigid('chest')
    for (const sx of [1, -1]) {
      const p0 = sp(T, 1.462, sx * 0.032, 1, 0.004)
      const p1 = sp(T, 1.36, sx * 0.03, 1, 0.003)
      b.tube([p0, [(p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2, (p0[2] + p1[2]) / 2 + 0.003], p1], 0.0028, { mat: 'plain', color: '#e8e4dc', seg: 5 })
      b.cyl(0.0042, 0.0042, 0.012, { mat: 'plain', color: '#c8c4bc', x: p1[0], y: p1[1] - 0.004, z: p1[2] })
    }
  }
  if (S.neck === 'crew' && top.kind !== 'gown') {
    // ribbed crew collar
    NK({ mat: 'cloth', col: (x) => { tCol(x); x.c.multiplyScalar(0.9) }, y0: (th) => NECK - 0.03 - 0.013 * smooth(0.75, 0, af(th)), y1: (th) => NECK + 0.001 - 0.013 * smooth(0.75, 0, af(th)), off: S.off + 0.002, hemHi: { t: 0.002, h: 0.008 }, cols: 36, dy: 0.008, shell: false })
  }
  if (tk === 'gown') {
    // ties at the back of the neck
    b.rigid('chest')
    const p = sp(T, 1.47, 0, -1, 0.004)
    b.box(0.03, 0.012, 0.006, { mat: 'cloth', color: '#a8c0c8', x: p[0], y: p[1], z: p[2] })
    b.box(0.006, 0.05, 0.004, { mat: 'cloth', color: '#a8c0c8', x: p[0] + 0.006, y: p[1] - 0.028, z: p[2] - 0.002, rz: 0.2 })
  }
}

// ---------------------------------------------------------------- closures
function closures(c) {
  const { b, A, S, top, torn, tCol } = c
  const T = A.torso
  if (top.kind === 'bare') return
  const yTop = c.neckY(PI / 2) - 0.012
  const yBot = (S.skirt ? Math.max(S.hem + 0.06, 0.62) : S.hem + 0.03) + (torn ? 0.2 : 0)
  b.rigid('chest')
  const btn = top.kind === 'coat' || top.kind === 'cardigan' ? { r: 0.0062, c: '#2a2420' } : { r: 0.0042, c: '#bdb6aa' }
  const col = (y) => colAt(tCol, T, y)
  const onFront = (y, lx, fn) => {
    const part = y < 1.12 && c.skirt ? c.skirt : T
    b.rigid(y > 1.25 ? 'chest' : y > 1.02 ? 'spine' : 'hips')
    return onSurf(b, part, y, lx, 1, 0.0015, fn)
  }
  if (S.placket && !S.open) {
    // a placket strip with buttons down the front
    const pts = []
    for (let y = yBot; y <= yTop + 1e-4; y += (yTop - yBot) / 8) pts.push(sp(y < 1.12 && c.skirt ? c.skirt : T, y, 0, 1, 0.0012))
    b.rigid('spine')
    strap(b, pts, 0.026, 0.002, { mat: 'cloth', color: hex(col(1.3).multiplyScalar(0.96)) })
    const n = S.placket
    for (let i = 0; i < n; i++) {
      const y = yTop - 0.012 - i * (S.placket > 3 ? 0.07 : 0.035)
      if (y < yBot) break
      onFront(y, 0, () => b.sphere(btn.r, { mat: 'plastic', color: btn.c, sz: 0.45, ws: 8, hs: 6 }))
    }
  }
  if (S.zip) {
    const pts = []
    for (let y = yBot; y <= yTop + 1e-4; y += (yTop - yBot) / 10) pts.push(sp(T, y, 0, 1, 0.002))
    b.rigid('spine')
    strap(b, pts, 0.007, 0.002, { mat: 'steel', color: '#9a9a96' })
    onFront(yTop - 0.03, 0.006, () => b.box(0.008, 0.022, 0.004, { mat: 'steel', color: '#c8c8c4' }))
  }
  if (S.buttons && !S.open) {
    for (let i = 0; i < 4; i++) {
      const y = c.neckY(PI / 2) - 0.02 - i * 0.085
      if (y < yBot) break
      onFront(y, 0.012, () => b.sphere(btn.r, { mat: 'plastic', color: btn.c, sz: 0.45, ws: 8, hs: 6 }))
    }
  }
  if (S.buttons && S.open) {
    for (let i = 0; i < 4; i++) onFront(1.38 - i * 0.085, -(S.open > 0.12 ? 0.055 : 0.035), () => b.sphere(btn.r, { mat: 'plastic', color: btn.c, sz: 0.45, ws: 8, hs: 6 }))
  }
  if (S.double) {
    // double-breasted chef's jacket
    for (let i = 0; i < 4; i++) for (const sx of [1, -1]) onFront(1.4 - i * 0.075, sx * 0.05, () => b.sphere(0.0055, { mat: 'gloss', color: '#e8e4dc', sz: 0.5, ws: 8, hs: 6 }))
    b.rigid('chest')
    const pts = [1.46, 1.38, 1.3, 1.22, 1.14].map((y) => sp(T, y, 0.03, 1, 0.0015))
    strap(b, pts, 0.004, 0.002, { mat: 'cloth', color: hex(col(1.3).multiplyScalar(0.85)) })
  }
  if (S.closures) {
    // turnout coat: hook-and-dee closures and reflective trim
    for (let i = 0; i < 5; i++) onFront(1.42 - i * 0.12, 0.01, () => b.box(0.03, 0.01, 0.006, { mat: 'steel', color: '#2a2a2a' }))
  }
  if (S.pockets) {
    for (const sx of S.pockets === 2 ? [1, -1] : [1]) {
      onFront(1.33, sx * 0.072, () => {
        const pc = hex(col(1.33).multiplyScalar(0.94))
        b.box(0.068, 0.075, 0.003, { mat: 'cloth', color: pc, y: -0.008 })
        b.box(0.072, 0.024, 0.005, { mat: 'cloth', color: pc, y: 0.03, z: 0.002 })
        if (top.kind === 'uniform' || top.kind === 'flannel') b.sphere(0.0035, { mat: 'gloss', color: '#d8d0c0', y: 0.026, z: 0.005, sz: 0.5, ws: 6, hs: 5 })
      })
    }
  }
  if (S.epaulettes) {
    b.rigid('chest')
    for (const sx of [1, -1]) {
      const a = topPt(T, 1.484, sx * 0.07, 0.006)
      const e = topPt(T, 1.448, sx * 0.165, 0.008)
      b.beam(a, e, 0.04, 0.005, { mat: 'cloth', color: hex(col(1.3).multiplyScalar(0.9)) })
      b.sphere(0.004, { mat: 'gloss', color: '#d8c890', x: a[0] + (e[0] - a[0]) * 0.2, y: a[1] + 0.003, z: a[2] })
    }
  }
  if (top.kind === 'hoodie') {
    // kangaroo pocket
    onFront(1.09, 0, () => {
      b.box(0.17, 0.085, 0.006, { mat: 'cloth', color: hex(col(1.1).multiplyScalar(0.9)), r: 0.002 })
      for (const sx of [1, -1]) b.box(0.006, 0.08, 0.007, { mat: 'cloth', color: hex(col(1.1).multiplyScalar(0.8)), x: sx * 0.082, rz: sx * 0.35 })
    })
  }
  if (top.kind === 'track' && top.stripe) {
    // stripes down the jacket's shoulders
    b.rigid('chest')
    for (const sx of [1, -1]) {
      const a = topPt(T, 1.47, sx * 0.1, 0.004)
      const e = topPt(T, 1.44, sx * 0.17, 0.004)
      b.beam(a, e, 0.012, 0.004, { mat: 'cloth', color: top.stripe })
    }
  }
  if (top.kind === 'scrubs') {
    // V-neck trim
    b.rigid('chest')
    for (const sx of [1, -1]) strap(b, [sp(T, NECK - 0.002, sx * 0.04, 1, 0.003), sp(T, NECK - 0.085, 0, 1, 0.003)], 0.01, 0.002, { mat: 'cloth', color: hex(col(1.3).multiplyScalar(0.85)) })
  }
}

// ---------------------------------------------------------------- legwear details
function pantsDetail(c) {
  const { b, sink, A, legs, has, top, S, res, POFF, waistTop, pCol } = c
  const T = A.torso
  const lk = legs.kind
  if (lk === 'bare') return
  const pc = (y, lz = 1) => colAt(pCol, T, y, lz)
  const covered = S.hem < 0.99 && !S.tuck
  // belt
  const beltC = lk === 'slacks' || top.kind === 'uniform' ? '#151312' : lk === 'jeans' || lk === 'cargo' ? '#3a2618' : null
  if (beltC && !covered && !has('dutybelt') && !has('belt') && !has('toolbelt')) {
    lay(sink, T, { mat: 'leather', col: beltC, y0: waistTop - 0.042, y1: waistTop - 0.006, off: POFF + 0.0055, hemLo: { t: 0.001, h: 0.004 }, hemHi: { t: 0.001, h: 0.004 }, cols: 36, dy: 0.008, shell: false }, res)
    b.rigid('hips')
    onSurf(b, T, waistTop - 0.024, 0, 1, POFF + 0.007, () => {
      b.box(0.042, 0.032, 0.005, { mat: 'steel', color: lk === 'slacks' ? '#c8c4b8' : '#a89a70' })
      b.box(0.03, 0.02, 0.006, { mat: 'leather', color: beltC })
    })
  }
  b.rigid('hips')
  if ((lk === 'jeans' || lk === 'cargo') && !covered) {
    // belt loops
    for (const a of [0.45, 0.9, 1.35]) for (const sx of [1, -1]) {
      const lx = sx * T.at(waistTop - 0.02, {}).w * Math.cos(a) * 0.98
      onSurf(b, T, waistTop - 0.02, lx, 1, POFF + 0.006, () => b.box(0.009, 0.036, 0.004, { mat: lk === 'jeans' ? 'denim' : 'cloth', color: hex(pc(1, 1).multiplyScalar(0.92)) }))
    }
  }
  if (lk === 'jeans' || lk === 'slacks' || lk === 'cargo') {
    // back pockets
    for (const sx of [1, -1]) {
      onSurf(b, T, 0.935, sx * 0.068, -1, 0.0008, () => {
        b.box(0.07, 0.075, 0.003, { mat: lk === 'jeans' ? 'denim' : 'cloth', color: hex(pc(0.93, -1).multiplyScalar(0.9)), r: 0.001 })
        if (lk === 'jeans') b.box(0.05, 0.003, 0.004, { mat: 'plain', color: '#b08840', y: 0.01 })
      })
    }
  }
  if (lk === 'cargo') {
    // thigh cargo pockets with flaps
    for (const side of ['L', 'R']) {
      const p = side === 'L' ? A.legL : A.legR
      b.rigid('thigh' + side)
      onSurf(b, p, 0.68, 0.062, 1, 0.012 + 0.004, () => {
        const pc2 = hex(pc(0.7).multiplyScalar(0.92))
        b.box(0.075, 0.1, 0.014, { mat: 'cloth', color: pc2, r: 0.004 })
        b.box(0.08, 0.03, 0.016, { mat: 'cloth', color: hex(pc(0.7).multiplyScalar(0.84)), y: 0.048, z: 0.002, r: 0.003 })
      })
    }
  }
  if (lk === 'overalls') overalls(c)
  if (lk === 'jeans') {
    // coin pocket rivets
    b.rigid('hips')
    for (const sx of [1, -1]) onSurf(b, T, 0.995, sx * 0.1, 1, POFF + 0.001, () => b.sphere(0.003, { mat: 'steel', color: '#c0a060', sz: 0.4, ws: 6, hs: 5 }))
  }
  if (legs.check) {
    // chef's check trousers: a pale band at the waist
    lay(sink, T, { mat: 'cloth', col: '#d8d8d0', y0: waistTop - 0.03, y1: waistTop - 0.002, off: POFF + 0.004, cols: 36, dy: 0.01, shell: false }, res)
  }
}
function overalls(c) {
  const { b, sink, A, legs, res, POFF } = c
  const T = A.torso
  const lc = legs.color
  const oc = fabric(lc, { seed: c.sd + 9, dirt: 0.2 })
  // bib and back panel
  lay(sink, T, { mat: 'cloth', col: oc, y0: 1.0, y1: (th) => 1.345 - 0.01 * Math.cos(th * 2), th0: PI / 2 - 0.52, th1: PI / 2 + 0.52, off: 0.016, hemHi: { t: 0.003, h: 0.012 }, cols: 16, dy: 0.014, side: 1 }, res)
  lay(sink, T, { mat: 'cloth', col: oc, y0: 1.0, y1: 1.17, th0: -PI / 2 - 0.5, th1: -PI / 2 + 0.5, off: 0.016, hemHi: { t: 0.003, h: 0.012 }, cols: 14, dy: 0.014, side: -1 }, res)
  b.rigid('chest')
  for (const sx of [1, -1]) {
    const pts = [sp(T, 1.335, sx * 0.075, 1, 0.003), sp(T, 1.42, sx * 0.085, 1, 0.003), topPt(T, 1.47, sx * 0.1, 0.006), sp(T, 1.43, sx * 0.075, -1, 0.004), sp(T, 1.3, sx * 0.03, -1, 0.004), sp(T, 1.17, -sx * 0.06, -1, 0.004)]
    strap(b, pts, 0.034, 0.004, { mat: 'cloth', color: lc })
    const q = sp(T, 1.335, sx * 0.075, 1, 0.006)
    b.box(0.03, 0.022, 0.006, { mat: 'steel', color: '#c8b888', x: q[0], y: q[1], z: q[2] })
  }
  // bib pocket
  b.rigid('chest')
  onSurf(b, T, 1.25, 0, 1, 0.018, () => b.box(0.09, 0.07, 0.004, { mat: 'cloth', color: tint(lc, 0.9) }))
}

// ---------------------------------------------------------------- face
function face(c) {
  const { b, sink, A, spec, res, Z, sd } = c
  const H = A.head
  const Y = H.Y
  const X = (v) => v * H.hs
  const skin = spec.skin || '#d39d76'
  lay(sink, H, { mat: 'skin', col: faceFn(skin, spec, H, sd), y0: H.y0, y1: H.y1, cols: 36, dy: 0.0072, capLo: true, capHi: true, shell: false }, res)
  // nose
  const nose = nosePart(spec, H)
  const nc = C(skin).lerp(C('#c8605a'), 0.12)
  lay(sink, nose, { mat: 'skin', col: (x) => x.c.copy(nc).multiplyScalar(0.97 + 0.05 * Math.sin(x.y * 300)), y0: nose.y0, y1: nose.y1, cols: 12, dy: 0.0045, capLo: true, capHi: true, shell: false }, Math.max(0.8, res))
  b.rigid('head')
  for (const sx of [1, -1]) b.sphere(0.0034, { mat: 'plain', color: tint(skin, 0.3), x: sx * X(0.0075), y: Y(1.6225), z: X(0.1), sz: 0.5, sy: 0.6, ws: 6, hs: 5 })
  // eyes in their sockets
  const iris = ['#3a2a1e', '#2a4a6a', '#3a5a3a', '#5a4030', '#202020', '#4a6a7a'][sd % 6]
  const re = 0.0118
  for (const sx of [1, -1]) {
    const s = H.surf(Y(1.671), sx * X(0.036), 1)
    const ex = s.p[0]
    const ey = s.p[1]
    const ez = s.p[2] - re + 0.0062
    b.sphere(re, { mat: 'gloss', color: Z ? '#a8a070' : '#e2dbd0', x: ex, y: ey, z: ez, ws: 14, hs: 10 })
    if (Z) {
      b.sphere(0.0058, { mat: 'glowRed', color: '#ffffff', x: ex, y: ey, z: ez + re - 0.001, sz: 0.4, ws: 8, hs: 6 })
    } else {
      // iris, a darker ring round it, the pupil, and a catchlight: what
      // makes eyes read as looking at something at a few metres
      b.sphere(0.0076, { mat: 'gloss', color: tint(iris, 0.55), x: ex, y: ey, z: ez + re - 0.0024, sz: 0.4, ws: 12, hs: 8 })
      b.sphere(0.0071, { mat: 'gloss', color: iris, x: ex, y: ey, z: ez + re - 0.0019, sz: 0.4, ws: 12, hs: 8 })
      b.sphere(0.0032, { mat: 'gloss', color: '#080808', x: ex, y: ey, z: ez + re - 0.0001, sz: 0.4, ws: 8, hs: 6 })
      b.sphere(0.0011, { mat: 'plain', color: '#ffffff', x: ex + 0.0022, y: ey + 0.0024, z: ez + re + 0.0003, sz: 0.5, ws: 6, hs: 4, ao: 0 })
    }
    // lids: an upper lid shell, a dark lash line along its edge, a lower lid
    const lid = tint(skin, Z ? 0.7 : 0.9)
    const tl = PI * (Z ? 0.36 : 0.25)
    b.sphere(re + 0.0011, { mat: 'skin', color: lid, x: ex, y: ey, z: ez, ts: 0, tl, rx: 0.5, ws: 14, hs: 6 })
    b.sphere(re + 0.0014, { mat: 'plain', color: spec.female ? '#140e0c' : '#2a1e18', x: ex, y: ey, z: ez, ts: tl - (spec.female ? 0.13 : 0.08), tl: spec.female ? 0.13 : 0.08, rx: 0.5, ws: 14, hs: 2 })
    b.sphere(re + 0.0008, { mat: 'skin', color: lid, x: ex, y: ey, z: ez, ts: 0, tl: PI * (Z ? 0.17 : 0.14), rx: PI - 0.3, ws: 14, hs: 4 })
    // brows
    const bc = spec.hair?.style === 'bald' && !spec.hair?.color ? '#2a2018' : spec.hair?.color || '#2a2018'
    const bw = spec.female ? 0.0025 : 0.0037
    const pts = [[0.013, 1.6895], [0.03, 1.6965 + (spec.female ? 0.002 : 0)], [0.05, 1.6955], [0.06, 1.69]].map(([x, y]) => {
      const q = H.surf(Y(y), sx * X(x), 1, 0.0012).p
      return q
    })
    b.tube(pts, bw, { mat: 'hair', color: tint(bc, 0.85), seg: 5, tseg: 8 })
  }
  // lips and mouth
  const lipC = mixHex(skin, spec.female ? '#a8464a' : '#8a4442', spec.female ? 0.48 : 0.3)
  const yu = Y(1.6165)
  const yl = Y(1.6062)
  const zu = H.surf(yu, 0, 1).p[2]
  const zl = H.surf(yl, 0, 1).p[2]
  if (Z) {
    // slack open mouth with teeth
    const ym = Y(1.6085)
    b.sphere(0.017, { mat: 'plain', color: '#1a0606', x: 0, y: ym, z: zu - 0.006, sx: 1.3, sy: 0.75, sz: 0.5, ws: 10, hs: 7 })
    for (let i = -2; i <= 2; i++) b.box(0.0055, 0.0065, 0.004, { mat: 'gloss', color: i % 2 ? '#c8b890' : '#d8cca8', x: i * 0.0062, y: ym + 0.006, z: zu - 0.002, rz: i * 0.08 })
    b.capsule(0.0038, 0.03, { mat: 'skin', color: tint(lipC, 0.7), x: 0, y: yu + 0.003, z: zu - 0.0012, rz: PI / 2, seg: 8, cs: 2 })
    b.capsule(0.0045, 0.026, { mat: 'skin', color: tint(lipC, 0.7), x: 0, y: yl - 0.006, z: zl - 0.002, rz: PI / 2, seg: 8, cs: 2 })
  } else {
    b.capsule(0.0038, 0.025, { mat: 'skin', color: lipC, x: 0, y: yu, z: zu - 0.0019, rz: PI / 2, sz: 0.8, seg: 8, cs: 2 })
    b.capsule(0.0047, 0.02, { mat: 'skin', color: tint(lipC, 1.05), x: 0, y: yl, z: zl - 0.0024, rz: PI / 2, sz: 0.8, seg: 8, cs: 2 })
    b.box(0.031, 0.0014, 0.004, { mat: 'plain', color: tint(lipC, 0.45), x: 0, y: (yu + yl) / 2 + 0.0004, z: Math.max(zu, zl) - 0.0005 })
  }
  // ears (hidden by long hair)
  if (spec.hair?.style !== 'long') {
    for (const sx of [1, -1]) {
      const R = H.at(Y(1.652), {})
      const x = sx * (R.w + 0.003)
      const z = R.z - 0.008
      b.sphere(0.026, { mat: 'skin', color: tint(skin, 0.98), x, y: Y(1.652), z, sx: 0.36, sz: 0.68, ry: sx * 0.35, ws: 12, hs: 9 })
      b.sphere(0.016, { mat: 'skin', color: tint(skin, 0.74), x: x + sx * 0.0045, y: Y(1.654), z: z + 0.002, sx: 0.26, sy: 0.85, sz: 0.55, ry: sx * 0.35, ws: 10, hs: 7 })
      b.sphere(0.008, { mat: 'skin', color: tint(skin, 0.97), x: x + sx * 0.001, y: Y(1.632), z: z + 0.004, sx: 0.6, ws: 8, hs: 6 })
    }
  }
}

// ---------------------------------------------------------------- hair
const HAIR = {
  buzz: { t: 0.0026, top: 0, lump: 0.0004, skin: 0.35 },
  short: { t: 0.0055, top: 0.009, lump: 0.0016, fringe: 0.006 },
  side: { t: 0.0055, top: 0.011, lump: 0.0016, part: true, fringe: 0.004 },
  curly: { t: 0.011, top: 0.02, lump: 0.003, curl: 0.0045 },
  long: { t: 0.0065, top: 0.008, lump: 0.0014, part: true },
  ponytail: { t: 0.0042, top: 0.005, lump: 0.0012 },
  bun: { t: 0.0042, top: 0.005, lump: 0.0012 },
  mohawk: { t: 0.0022, top: 0, lump: 0.0004, ridge: 0.034, skin: 0.45 },
}
function hairCol(h, skinHex, mixSkin = 0) {
  const base = C(h).lerp(C(skinHex), mixSkin)
  return (x) => {
    const n1 = vn3(Math.cos(x.th) * 9, x.y * 16, Math.sin(x.th) * 9)
    const n2 = vn3(Math.cos(x.th) * 32, x.y * 30, Math.sin(x.th) * 32 + 5)
    x.c.copy(base).multiplyScalar(0.8 + n1 * 0.24 + n2 * 0.12)
  }
}
function hairDo(c) {
  const { b, sink, A, spec, res } = c
  const Hs = spec.hair
  const H = A.head
  const Y = H.Y
  if (!Hs || Hs.style === 'bald') {
    if (c.Z && c.rnd() < 0.5 && Hs?.color) {
      // patchy remains
      lay(sink, H, { mat: 'hair', col: hairCol(Hs.color, spec.skin, 0.2), y0: Y(1.62), y1: Y(1.71), th0: -PI / 2 - 1.2, th1: -PI / 2 + 1.1, off: 0.003, cols: 16, dy: 0.008, shell: false }, res)
    }
    return
  }
  const st = HAIR[Hs.style] ? Hs.style : 'short'
  const cfg = HAIR[st]
  const hatOn = !!c.spec.outfit?.hat && !['headband', 'bandana'].includes(c.spec.outfit.hat.kind)
  const hl = hairline(st, spec.female, H)
  const off = (y, th, lx, lz) => {
    const e = y - hl(th)
    let o = cfg.t * (0.3 + 0.7 * smooth(0, 0.016, e)) + cfg.top * smooth(Y(1.69), Y(1.765), y)
    o += cfg.lump * (vn3(Math.cos(th) * 6, y * 70, Math.sin(th) * 6) - 0.5) * 2
    if (cfg.curl) o += cfg.curl * (vn3(Math.cos(th) * 26, y * 85, Math.sin(th) * 26) - 0.5) * 2 * smooth(0, 0.015, e)
    if (cfg.fringe) o += cfg.fringe * Math.exp(-(((y - Y(1.736)) / 0.011) ** 2)) * smooth(0.3, 0.95, lz)
    if (cfg.part) o -= 0.0035 * Math.exp(-(((lx - 0.028) / 0.0045) ** 2)) * smooth(Y(1.715), Y(1.745), y) * smooth(-0.2, 0.3, lz)
    if (cfg.ridge) o += cfg.ridge * Math.exp(-((lx / 0.013) ** 2)) * smooth(Y(1.67), Y(1.73), y) * (1 - smooth(0.6, 1, lz) * 0.3)
    if (hatOn) o = Math.min(o, cfg.t + 0.002)
    return o
  }
  const hc = hairCol(Hs.color, spec.skin, cfg.skin || 0)
  lay(sink, H, { mat: 'hair', col: hc, y0: hl, y1: H.y1, off, cols: 38, dy: 0.007, capHi: true, shell: false, bump: 1 }, res)
  H.hairTop = cfg.t + cfg.top + (cfg.curl || 0)
  const rr = c.rnd
  const X = (v) => v * H.hs
  if (!hatOn && (st === 'short' || st === 'side' || st === 'long' || st === 'curly')) {
    const L = []
    const sweep = st === 'side' ? 0.012 : 0
    // fringe falling over the forehead
    for (let i = 0; i < (st === 'long' ? 5 : 7); i++) {
      const lx = X(-0.05 + (i / 6) * 0.1 + (rr() - 0.5) * 0.01)
      L.push({ y: Y(1.765), lx: lx * 0.6, dy: -0.011 - rr() * 0.004, dx: lx * 0.12 + sweep, r: 0.009 + rr() * 0.003, rise: 0.003 })
    }
    // crown tufts lying back
    for (let i = 0; i < 6; i++) {
      const lx = X((rr() - 0.5) * 0.09)
      L.push({ y: Y(1.772), lx, side: -1, dy: -0.014, dx: lx * 0.1, r: 0.01, rise: 0.002 })
    }
    // over the ears
    if (st !== 'long') for (const sx of [1, -1]) L.push({ y: Y(1.72), lx: sx * X(0.08), dy: -0.012, dx: sx * 0.004, r: 0.008, side: 1 })
    else for (const sx of [1, -1]) for (let k = 0; k < 3; k++) L.push({ y: Y(1.72 - k * 0.012), lx: sx * X(0.085 - k * 0.004), dy: -0.03, dx: sx * 0.004, r: 0.011, side: 1 })
    locks(c, H, L, cfg.t + cfg.top * 0.5, Hs.color, rr)
  }
  if (st === 'mohawk') {
    b.rigid('head')
    for (let i = 0; i < 7; i++) {
      const y = Y(1.735 + Math.sin((i / 6) * Math.PI) * 0.04)
      const side = i < 3 ? 1 : -1
      const p = H.surf(y, 0, side, cfg.t).p
      b.cone(0.012, 0.05, { mat: 'hair', color: tint(Hs.color, 0.9 + rr() * 0.2), x: p[0], y: p[1] + 0.012, z: p[2], rx: side * (0.3 + (i % 3) * 0.2), seg: 6 })
    }
  }
  const hw = { head: 1 }
  if (st === 'long') {
    // hair falling down the back and over the shoulders
    const k = (y, z, w, f, kk, wt) => ({ y, z, w, f, k: kk, wt })
    const curtain = new Part([
      k(Y(1.735), 0.004, 0.09, 0.07, 0.108, hw),
      k(Y(1.7), 0.006, 0.101, 0.086, 0.117, hw),
      k(Y(1.65), 0.008, 0.104, 0.09, 0.122, hw),
      k(1.6, 0.004, 0.1, 0.088, 0.119, { head: 0.7, neck: 0.3 }),
      k(1.545, -0.006, 0.096, 0.08, 0.104, { neck: 0.7, head: 0.3 }),
      k(1.49, -0.012, 0.112, 0.08, 0.096, { neck: 0.4, chest: 0.6 }),
      k(1.44, -0.018, 0.128, 0.08, 0.096, { chest: 1 }),
      k(1.39, -0.022, 0.132, 0.08, 0.098, { chest: 1 }),
    ])
    curtain.rad = 0.11
    const rr = (th) => 1.4 + (vn3(Math.cos(th) * 5, Math.sin(th) * 5, 3) - 0.5) * 0.05 + 0.04 * smooth(1.9, 1.2, af(th))
    lay(sink, curtain, { mat: 'hair', col: hc, y0: rr, y1: Y(1.735), th0: -PI / 2 - 2.05, th1: -PI / 2 + 2.05, off: (y, th) => 0.004 + 0.002 * Math.sin(th * 23 + y * 4), cols: 30, dy: 0.014, shell: false, bump: 0 }, res)
  }
  if (st === 'ponytail') {
    const tail = new Part([
      { y: 1.44, z: -0.108, w: 0.004, f: 0.004, k: 0.004, wt: { neck: 0.6, chest: 0.4 } },
      { y: 1.47, z: -0.112, w: 0.013, f: 0.012, k: 0.013, wt: { neck: 1 } },
      { y: 1.53, z: -0.121, w: 0.021, f: 0.019, k: 0.021, wt: { neck: 0.6, head: 0.4 } },
      { y: Y(1.6), z: -0.129, w: 0.027, f: 0.024, k: 0.027, wt: { head: 1 } },
      { y: Y(1.665), z: -0.128, w: 0.026, f: 0.024, k: 0.026, wt: hw },
      { y: Y(1.7), z: -0.116, w: 0.021, f: 0.02, k: 0.02, wt: hw },
      { y: Y(1.718), z: -0.104, w: 0.014, f: 0.014, k: 0.014, wt: hw },
    ])
    tail.rad = 0.02
    lay(sink, tail, { mat: 'hair', col: hc, y0: tail.y0, y1: tail.y1, cols: 12, dy: 0.012, capLo: true, capHi: true, off: (y, th) => 0.0015 * Math.sin(th * 7 + y * 30), shell: false }, res)
    b.rigid('head')
    b.torus(0.015, 0.004, { mat: 'plain', color: '#2a2a3a', x: 0, y: Y(1.708), z: -0.108, rx: PI / 2 - 0.6, rs: 6, ts2: 12 })
  }
  if (st === 'bun') {
    b.rigid('head')
    b.ico(0.038, { mat: 'hair', color: tint(Hs.color, 0.95), x: 0, y: Y(1.748), z: -0.088, detail: 2, noise: 0.12 })
    b.torus(0.026, 0.004, { mat: 'plain', color: '#2a2a3a', x: 0, y: Y(1.735), z: -0.075, rx: PI / 2 + 0.7, rs: 6, ts2: 12 })
  }
}
// A tapered, slightly flattened lock of hair through points (with normals).
function lock(b, pts, r0, r1, o) {
  const curve = new THREE.CatmullRomCurve3(pts.map((p) => new THREE.Vector3(...p)), false, 'catmullrom', 0.5)
  const M = 8
  const S = 5
  const fr = curve.computeFrenetFrames(M, false)
  const pos = []
  const nor = []
  const uv = []
  const idx = []
  const up = pts[0].n ? new THREE.Vector3(...pts[0].n) : new THREE.Vector3(0, 1, 0)
  const v = new THREE.Vector3()
  const side = new THREE.Vector3()
  const nn = new THREE.Vector3()
  for (let i = 0; i <= M; i++) {
    const t = i / M
    const P = curve.getPointAt(t)
    const T = curve.getTangentAt(t)
    side.crossVectors(T, up).normalize()
    nn.crossVectors(side, T).normalize()
    const r = r0 + (r1 - r0) * t
    for (let j = 0; j < S; j++) {
      const a = (j / S) * Math.PI * 2
      // flattened against the head: wide across, thin along the normal
      v.copy(side).multiplyScalar(Math.cos(a) * r).addScaledVector(nn, Math.sin(a) * r * 0.45)
      pos.push(P.x + v.x, P.y + v.y, P.z + v.z)
      const n = side.clone().multiplyScalar(Math.cos(a) * 0.45).addScaledVector(nn, Math.sin(a)).normalize()
      nor.push(n.x, n.y, n.z)
      uv.push(j / S, t * 0.3)
    }
  }
  for (let i = 0; i < M; i++) for (let j = 0; j < S; j++) {
    const a = i * S + j
    const b2 = i * S + ((j + 1) % S)
    idx.push(a, a + S, b2, b2, a + S, b2 + S)
  }
  void fr
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3))
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
  g.setIndex(idx)
  b.add(g, o)
}
// Locks flowing over the cap from root (y, lx) by steps of (dy, dlx), lifted
// off the hair surface; they break up the helmet silhouette of the cap.
function locks(c, H, list, lift, col, rr) {
  const { b } = c
  b.rigid('head')
  for (const L of list) {
    const pts = []
    for (let k = 0; k < 4; k++) {
      const y = L.y + L.dy * k
      const lx = L.lx + L.dx * k
      const side = L.side ?? 1
      const p = H.surf(y, lx, side, lift + (L.rise || 0) * Math.sin((k / 3) * Math.PI)).p
      p.n = H.surf(y, lx, side).n
      pts.push(p)
    }
    lock(b, pts, L.r || 0.008, 0.0015, { mat: 'hair', color: tint(col, 0.86 + rr() * 0.3) })
  }
}
function beardDo(c) {
  const { b, sink, A, spec, res } = c
  const kind = spec.face?.beard
  if (!kind || spec.female) return
  const H = A.head
  const Y = H.Y
  const X = (v) => v * H.hs
  const hcol = spec.hair?.color || '#2a2018'
  const hc = hairCol(hcol, spec.skin, 0)
  if (kind === 'beard' || kind === 'goatee') {
    const wide = kind === 'beard'
    lay(sink, H, {
      mat: 'hair',
      col: hc,
      y0: H.y0,
      y1: (th) => {
        const a = Math.abs(th - PI / 2)
        return wide ? Y(1.6 + 0.062 * smooth(0.22, 1.25, a)) : Y(1.598)
      },
      th0: PI / 2 - (wide ? 1.42 : 0.5),
      th1: PI / 2 + (wide ? 1.42 : 0.5),
      off: (y, th, lx) => 0.0035 + (wide ? 0.008 : 0.006) * smooth(Y(1.625), Y(1.56), y) + 0.0015 * (vn3(lx * 90, y * 90, 2) - 0.5),
      cols: 22,
      dy: 0.006,
      shell: false,
      bump: 1,
    }, res)
  }
  if (kind === 'beard' || kind === 'mustache' || kind === 'goatee') {
    b.rigid('head')
    const pts = [[-0.027, 1.6115], [-0.014, 1.6195], [0, 1.6215], [0.014, 1.6195], [0.027, 1.6115]].map(([x, y]) => {
      const p = H.surf(Y(y), X(x), 1, 0.0032).p
      return p
    })
    b.tube(pts, 0.0042, { mat: 'hair', color: tint(hcol, 0.9), seg: 6, tseg: 10 })
  }
}

// ---------------------------------------------------------------- hats
function hat(c, Hs) {
  const { b, sink, A, res } = c
  const H = A.head
  const Y = H.Y
  const col = Hs.color
  const ht = (H.hairTop || 0) * 0.6
  const shell = (o) => lay(sink, H, { cols: 36, dy: 0.007, capHi: true, shell: false, bump: 0.6, ...o }, res)
  const crownLine = (front, side, back) => (th) => {
    const a = af(th)
    return a < PI / 2 ? Y(front + (side - front) * smooth(0, PI / 2, a)) : Y(side + (back - side) * smooth(PI / 2, PI, a))
  }
  const brim = (len, wid, y, tilt, m = 'cloth', cc = col) => {
    b.rigid('head')
    const zf = H.surf(Y(y), 0, 1, ht + 0.004).p[2]
    const pts = []
    for (let i = 0; i <= 12; i++) {
      const a = (i / 12) * PI
      pts.push([Math.cos(a) * wid, Math.sin(a) * len])
    }
    b.at({ x: 0, y: Y(y), z: zf - 0.03, rx: PI / 2 + tilt }, () => b.extrude(pts.map(([x, z]) => [x, z]), 0.006, { mat: m, color: cc, bevel: 0.002, bevelSeg: 1, curve: 4 }))
  }
  switch (Hs.kind) {
    case 'cap': {
      shell({ mat: 'cloth', col: (x) => { x.c.set(col); if (Math.abs(Math.sin(x.th * 3)) < 0.05) x.c.multiplyScalar(0.8) }, y0: crownLine(1.708, 1.686, 1.676), y1: H.y1, off: ht + 0.007, hemLo: { t: 0.002, h: 0.008 } })
      brim(0.085, 0.082, 1.708, 0.16)
      b.sphere(0.0075, { mat: 'cloth', color: col, x: 0, y: Y(1.776) + ht + 0.008, z: -0.01, sy: 0.6 })
      break
    }
    case 'patrolcap': {
      shell({ mat: 'cloth', col: fabric(col, { camo: true, dirt: 0.1 }), y0: crownLine(1.706, 1.684, 1.676), y1: H.y1, off: (y) => ht + 0.008 + 0.012 * smooth(Y(1.73), Y(1.77), y), hemLo: { t: 0.002, h: 0.008 } })
      brim(0.07, 0.078, 1.706, 0.1)
      break
    }
    case 'policecap': {
      b.rigid('head')
      const yb = Y(1.704)
      const zc = -0.006
      b.lathe([[0.104, 0], [0.106, 0.02], [0.112, 0.045], [0.128, 0.064], [0.13, 0.07], [0.12, 0.076], [0.001, 0.078]], { mat: 'cloth', color: col, y: yb + ht, z: zc, seg: 24, sz: 1.08 })
      b.cyl(0.107, 0.105, 0.022, { mat: 'gloss', color: '#141414', y: yb + ht + 0.012, z: zc, sz: 1.08, seg: 24 })
      brim(0.075, 0.08, 1.7, 0.42, 'gloss', '#101010')
      b.box(0.024, 0.028, 0.004, { mat: 'steel', color: '#d8b84a', x: 0, y: yb + ht + 0.035, z: zc + 0.122 })
      break
    }
    case 'hardhat': {
      shell({ mat: 'gloss', col, y0: crownLine(1.712, 1.69, 1.68), y1: H.y1, off: (y) => ht + 0.02 + 0.006 * smooth(Y(1.72), Y(1.77), y), hemLo: { t: 0.003, h: 0.006 }, bump: 0 })
      b.rigid('head')
      b.cyl(0.128, 0.13, 0.008, { mat: 'gloss', color: col, x: 0, y: Y(1.69) + ht, z: 0.006, sz: 1.12, seg: 28 })
      b.box(0.022, 0.03, 0.2, { mat: 'gloss', color: tint(col, 0.92), x: 0, y: Y(1.776) + ht + 0.022, z: -0.004, r: 0.008 })
      break
    }
    case 'helmet': {
      shell({ mat: 'paint', col: fabric(col, { camo: true, dirt: 0.2 }), y0: crownLine(1.7, 1.664, 1.648), y1: H.y1, off: ht + 0.024, hemLo: { t: 0.004, h: 0.01 }, bump: 0.3 })
      b.rigid('head')
      for (const sx of [1, -1]) {
        const R = H.at(Y(1.62), {})
        b.box(0.008, 0.06, 0.004, { mat: 'cloth', color: '#2a2a22', x: sx * (R.w + 0.004), y: Y(1.635), z: R.z + 0.02, rz: sx * 0.1 })
      }
      break
    }
    case 'beanie': {
      shell({ mat: 'knit', col: (x) => { x.c.set(col); if (x.y < Y(1.71)) x.c.multiplyScalar(0.9) }, y0: crownLine(1.698, 1.67, 1.655), y1: H.y1, off: (y, th) => ht + 0.008 + 0.005 * (1 - smooth(Y(1.7), Y(1.71), y)) + 0.012 * smooth(Y(1.74), Y(1.776), y), hemLo: { t: 0.003, h: 0.008 } })
      break
    }
    case 'straw': {
      b.rigid('head')
      const yb = Y(1.715) + ht
      b.lathe([[0.002, 0.098], [0.05, 0.097], [0.085, 0.088], [0.1, 0.068], [0.106, 0.03], [0.11, 0.006], [0.15, 0.002], [0.195, 0.012], [0.21, 0.026], [0.214, 0.016], [0.196, 0.001], [0.15, -0.006], [0.112, -0.004], [0.104, 0.0]].reverse(), { mat: 'cloth', color: col, y: yb, seg: 28, sz: 1.06 })
      b.cyl(0.108, 0.108, 0.02, { mat: 'cloth', color: '#5a3a24', y: yb + 0.016, seg: 24, sz: 1.06 })
      break
    }
    case 'toque': {
      b.rigid('head')
      const yb = Y(1.71) + ht
      const g = new THREE.LatheGeometry([[0.108, 0], [0.11, 0.05], [0.13, 0.085], [0.14, 0.12], [0.128, 0.148], [0.07, 0.162], [0.001, 0.164]].map(([r, y]) => new THREE.Vector2(r, y)), 32)
      const p = g.attributes.position
      for (let i = 0; i < p.count; i++) {
        const y = p.getY(i)
        const a = Math.atan2(p.getZ(i), p.getX(i))
        const k = 1 + 0.045 * Math.cos(a * 14) * smooth(0.04, 0.09, y) * (1 - smooth(0.13, 0.16, y))
        p.setX(i, p.getX(i) * k)
        p.setZ(i, p.getZ(i) * k)
      }
      g.computeVertexNormals()
      b.add(g, { mat: 'cloth', color: col, y: yb })
      break
    }
    case 'headband': {
      shell({ mat: 'knit', col, y0: Y(1.704), y1: Y(1.728), off: ht + 0.004, capHi: false, hemLo: { t: 0.001, h: 0.004 }, hemHi: { t: 0.001, h: 0.004 } })
      break
    }
    case 'bandana': {
      shell({ mat: 'cloth', col: fabric(col, { dirt: 0.1 }), y0: crownLine(1.712, 1.69, 1.672), y1: H.y1, off: ht + 0.005, hemLo: { t: 0.002, h: 0.006 } })
      b.rigid('head')
      const p = H.surf(Y(1.69), 0, -1, ht + 0.008).p
      b.ico(0.014, { mat: 'cloth', color: col, x: p[0], y: p[1], z: p[2], detail: 1, noise: 0.2 })
      for (const sx of [1, -1]) b.box(0.016, 0.06, 0.004, { mat: 'cloth', color: col, x: p[0] + sx * 0.012, y: p[1] - 0.032, z: p[2] - 0.004, rz: sx * 0.3, rx: -0.2 })
      break
    }
  }
}

// ---------------------------------------------------------------- hands
function hand(b, A, side, o) {
  const mir = side === 'L' ? 1 : -1
  const part = side === 'L' ? A.armL : A.armR
  const hx = part.keys[0].x
  b.rigid('hand' + side)
  const m = o.glove ? { mat: 'leather', color: o.glove } : { mat: 'skin', color: o.skin }
  const kn = o.glove ? m : { mat: 'skin', color: tint(o.skin, 0.94) }
  b.box(0.027, 0.074, 0.064, { ...m, x: hx, y: 0.858, z: 0.004, r: 0.0115 })
  b.box(0.022, 0.03, 0.06, { ...kn, x: hx - mir * 0.002, y: 0.826, z: 0.002, r: 0.0095 })
  const fz = [0.0215, 0.0072, -0.0072, -0.0205]
  const fl = [0.046, 0.05, 0.047, 0.037]
  fz.forEach((z, i) => {
    const r = i === 3 ? 0.0062 : 0.007
    const l = fl[i]
    b.capsule(r, l * 0.42, { ...m, x: hx - mir * 0.0035, y: 0.81 - l * 0.24, z, rz: -mir * 0.2, seg: 7, cs: 2 })
    b.capsule(r * 0.9, l * 0.34, { ...m, x: hx - mir * 0.011, y: 0.81 - l * 0.66, z: z + 0.001, rz: -mir * 0.62, seg: 7, cs: 2 })
  })
  b.capsule(0.0082, 0.026, { ...m, x: hx - mir * 0.01, y: 0.848, z: 0.037, rx: 0.55, rz: -mir * 0.32, seg: 7, cs: 2 })
  b.sphere(0.011, { ...m, x: hx - mir * 0.006, y: 0.868, z: 0.03, sx: 0.8, ws: 8, hs: 6 })
}

// ---------------------------------------------------------------- footwear
const SHOE = {
  sneakers: [[-0.084, 0.05, 0.018], [-0.077, 0.084, 0.033], [-0.062, 0.1, 0.041], [-0.036, 0.106, 0.044], [-0.006, 0.099, 0.045], [0.03, 0.085, 0.047], [0.07, 0.069, 0.049], [0.11, 0.057, 0.047], [0.14, 0.049, 0.042], [0.16, 0.042, 0.033], [0.173, 0.033, 0.018]],
  shoes: [[-0.08, 0.05, 0.017], [-0.073, 0.078, 0.031], [-0.058, 0.09, 0.038], [-0.034, 0.096, 0.041], [-0.006, 0.09, 0.042], [0.03, 0.077, 0.044], [0.07, 0.062, 0.045], [0.11, 0.051, 0.043], [0.142, 0.043, 0.037], [0.165, 0.036, 0.026], [0.178, 0.028, 0.012]],
  boots: [[-0.086, 0.06, 0.02], [-0.079, 0.1, 0.036], [-0.064, 0.13, 0.044], [-0.038, 0.14, 0.047], [-0.006, 0.13, 0.048], [0.03, 0.098, 0.05], [0.07, 0.076, 0.052], [0.11, 0.064, 0.05], [0.14, 0.058, 0.046], [0.16, 0.052, 0.038], [0.175, 0.042, 0.022]],
}
function footwear(c, side) {
  const { b, sink, A, spec, res, Z, legs } = c
  const sh = c.spec.outfit?.shoes || { kind: 'boots', color: '#2a2420' }
  const mir = side === 'L' ? 1 : -1
  const leg = side === 'L' ? A.legL : A.legR
  const x = Math.abs(leg.keys[0].x)
  const wt = { foot: 1 }
  if (sh.kind === 'bare') {
    const foot = footPart([[-0.07, 0.05, 0.02], [-0.058, 0.075, 0.03], [-0.035, 0.088, 0.034], [0, 0.08, 0.036], [0.04, 0.06, 0.04], [0.08, 0.042, 0.043], [0.11, 0.032, 0.042], [0.125, 0.026, 0.036], [0.13, 0.018, 0.02]], { x, mir, bot: 0.004, n: 2.2, nb: 3, wt })
    lay(sink, foot, { mat: 'skin', col: skinFn(spec.skin || '#9a9e8a', Z, c.sd), y0: foot.y0, y1: foot.y1, cols: 14, dy: 0.012, capLo: true, capHi: true, shell: false }, res)
    b.rigid('foot' + side)
    for (let i = 0; i < 5; i++) b.sphere(0.0085 - i * 0.0008, { mat: 'skin', color: spec.skin || '#9a9e8a', x: mir * (x - 0.03 + i * 0.014) - (mir < 0 ? 0 : 0) , y: 0.012, z: 0.136 - i * 0.006, sy: 0.75, ws: 6, hs: 5 })
    return
  }
  const kind = SHOE[sh.kind] ? sh.kind : 'shoes'
  const tops = SHOE[kind]
  const upMat = kind === 'sneakers' ? 'cloth' : 'leather'
  const uc = fabric(sh.color, { dirt: Z ? 0.7 : 0.35, blood: Z ? 0.4 : 0, seed: c.sd + 7 })
  const upper = footPart(tops, { x, mir, bot: kind === 'boots' ? 0.026 : 0.018, wt })
  upper.rad = 0.05
  lay(sink, upper, { mat: upMat, col: uc, y0: upper.y0, y1: upper.y1, cols: 18, dy: 0.012, capLo: true, capHi: true, shell: false }, res)
  // sole: sneakers get a thick pale midsole, boots a lugged heel
  const soleH = kind === 'boots' ? 0.03 : kind === 'sneakers' ? 0.024 : 0.016
  const sole = footPart(tops.map(([z, , w]) => [z + (z > 0 ? 0.004 : -0.004), soleH, w + 0.005]), { x, mir, bot: 0, n: 4, nb: 6, wt })
  lay(sink, sole, { mat: 'rubber', col: kind === 'sneakers' ? '#ece8e0' : '#1c1a18', y0: sole.y0, y1: sole.y1, cols: 16, dy: 0.012, capLo: true, capHi: true, shell: false }, res)
  b.rigid('foot' + side)
  const topAt = (z) => {
    for (let i = 1; i < tops.length; i++) if (z <= tops[i][0]) return tops[i - 1][1] + ((tops[i][1] - tops[i - 1][1]) * (z - tops[i - 1][0])) / (tops[i][0] - tops[i - 1][0])
    return tops[tops.length - 1][1]
  }
  if (kind !== 'shoes') {
    // laces across the instep
    const n = kind === 'boots' ? 4 : 4
    for (let i = 0; i < n; i++) {
      const z = 0.0 + i * 0.018
      const y = topAt(z)
      const slope = Math.atan2(topAt(z + 0.01) - topAt(z - 0.01), 0.02)
      b.box(0.034, 0.003, 0.006, { mat: 'plain', color: kind === 'sneakers' ? '#f2f0ea' : '#3a3026', x: mir * x, y: y + 0.0035, z, rx: -slope })
    }
    if (kind === 'sneakers') {
      b.box(0.003, 0.016, 0.07, { mat: 'plain', color: sh.color === '#f0f0f0' || sh.color === '#e8e8e4' ? '#3a5a9a' : '#f0f0ea', x: mir * (x + 0.047), y: 0.046, z: 0.035, rx: 0.15 })
    }
  } else {
    // dress shoe: a heel block and a toe seam
    b.box(0.07, 0.014, 0.05, { mat: 'rubber', color: '#141210', x: mir * x, y: 0.008, z: -0.055 })
  }
  if (kind === 'boots') {
    // shaft around the ankle with a padded collar; laces up the front
    const shaft = new Part([
      { y: 0.05, x, z: 0.004, w: 0.045, f: 0.048, k: 0.05, wt: { foot: 1 } },
      { y: 0.12, x, z: 0.002, w: 0.044, f: 0.046, k: 0.049, wt: { foot: 0.8, shin: 0.2 } },
      { y: 0.2, x, z: 0.0, w: 0.046, f: 0.047, k: 0.049, wt: { foot: 0.4, shin: 0.6 } },
    ], { mir })
    shaft.rad = 0.05
    lay(sink, shaft, { mat: 'leather', col: uc, y0: 0.05, y1: 0.2, off: 0.002, hemHi: { t: 0.004, h: 0.014 }, cols: 18, dy: 0.012, shell: false }, res)
    for (let i = 0; i < 4; i++) {
      const y = 0.095 + i * 0.026
      b.box(0.03, 0.003, 0.005, { mat: 'plain', color: '#3a3026', x: mir * x, y, z: 0.052 })
    }
  }
}

// ---------------------------------------------------------------- kit
function band(c, part, y0, y1, off, colr, mat = 'cloth', o = {}) {
  lay(c.sink, part, { mat, col: colr, y0, y1, off: (y) => part.shellAt((y0 + y1) / 2) + off, hemLo: { t: 0.001, h: 0.004 }, hemHi: { t: 0.001, h: 0.004 }, cols: part === c.A.torso ? 36 : 16, dy: 0.008, shell: false, ...o }, c.res)
}
function vest(c, colr, o = {}) {
  const T = c.A.torso
  const base = T.shellAt(1.2)
  const yTop = (th) => {
    const a = af(th)
    let y = 1.36 + (1.335 - 1.36) * smooth(0.4, 0.6, a)
    y += (1.45 - 1.335) * smooth(2.4, 2.7, a)
    const strap = Math.max(Math.exp(-(((a - 0.72) / 0.22) ** 2)), Math.exp(-(((a - 2.42) / 0.24) ** 2)))
    return y + (NECK - 0.006 - y) * smooth(0.2, 0.7, strap)
  }
  lay(c.sink, T, { mat: o.mat || 'cloth', col: colr, y0: o.y0 ?? 0.97, y1: yTop, off: (y) => Math.max(base, T.shellAt(y)) + (o.off ?? 0.007), th0: PI / 2 + (o.gap ?? 0.04), th1: PI / 2 - (o.gap ?? 0.04) + TAU, hemLo: { t: 0.002, h: 0.008 }, hemHi: { t: 0.002, h: 0.008 }, cols: 44, dy: 0.012, shell: false }, c.res)
  T.shells.push({ y0: o.y0 ?? 0.97, y1: 1.45, side: null, off: () => base + (o.off ?? 0.007) + 0.002 })
}
function extra(c, e) {
  const { b, A, sink, res } = c
  const T = A.torso
  switch (e) {
    case 'stethoscope': {
      b.rigid('chest')
      const pts = [sp(T, 1.33, 0.05, 1, 0.006), sp(T, 1.42, 0.07, 1, 0.006), topPt(T, 1.474, 0.068, 0.008), sp(T, 1.47, 0.02, -1, 0.008), sp(T, 1.47, -0.03, -1, 0.008), topPt(T, 1.474, -0.068, 0.008), sp(T, 1.41, -0.075, 1, 0.006), sp(T, 1.35, -0.06, 1, 0.006)]
      b.tube(pts, 0.0042, { mat: 'rubber', color: '#1a1a1a', seg: 6, tseg: 30 })
      const p = sp(T, 1.31, 0.048, 1, 0.008)
      b.cyl(0.015, 0.015, 0.008, { mat: 'chrome', color: '#d0d0d0', x: p[0], y: p[1], z: p[2], rx: PI / 2 })
      break
    }
    case 'badge':
      b.rigid('chest')
      onSurf(b, T, 1.36, 0.085, 1, 0.002, () => b.extrude([[0, 0.02], [0.016, 0.012], [0.014, -0.01], [0, -0.02], [-0.014, -0.01], [-0.016, 0.012]], 0.003, { mat: 'steel', color: '#d8b84a' }))
      break
    case 'nametag':
      b.rigid('chest')
      onSurf(b, T, 1.35, -0.078, 1, 0.002, () => {
        b.box(0.062, 0.018, 0.003, { mat: 'plain', color: '#cfcfc8' })
        b.box(0.04, 0.004, 0.004, { mat: 'plain', color: '#3a3a3a' })
      })
      break
    case 'radio':
      b.rigid('chest')
      onSurf(b, T, 1.4, -0.105, 1, 0.012, () => {
        b.box(0.036, 0.062, 0.022, { mat: 'plastic', color: '#1a1a1a', r: 0.004 })
        b.cyl(0.003, 0.003, 0.05, { mat: 'plastic', color: '#1a1a1a', x: 0.01, y: 0.05 })
      })
      break
    case 'dutybelt':
    case 'belt': {
      const y = c.waistTop - 0.03
      band(c, T, y - 0.022, y + 0.022, 0.006, '#141414', 'leather')
      b.rigid('hips')
      onSurf(b, T, y, 0, 1, 0.008, () => b.box(0.04, 0.032, 0.006, { mat: 'steel', color: '#b8b8b0' }))
      if (e === 'dutybelt') {
        // holster, cuffs, magazine pouch
        onSurf(b, T, y - 0.04, -0.15, 1, 0.022, () => b.box(0.05, 0.12, 0.045, { mat: 'leather', color: '#141414', r: 0.008, rz: 0.1 }))
        onSurf(b, T, y, 0.12, 1, 0.02, () => b.box(0.045, 0.045, 0.03, { mat: 'leather', color: '#141414', r: 0.006 }))
        onSurf(b, T, y, 0.06, 1, 0.014, () => b.box(0.03, 0.05, 0.022, { mat: 'leather', color: '#141414', r: 0.004 }))
        onSurf(b, T, y, -0.07, -1, 0.012, () => b.torus(0.018, 0.004, { mat: 'steel', color: '#c0c0c0' }))
      }
      break
    }
    case 'pouches':
      b.rigid('hips')
      for (const x of [-0.12, -0.05, 0.05, 0.12]) onSurf(b, T, c.waistTop - 0.03, x, 1, 0.022, () => b.box(0.048, 0.06, 0.036, { mat: 'cloth', color: '#4a5038', r: 0.006 }))
      break
    case 'stripesArms':
      for (const [p, bn] of [[A.armL, 'L'], [A.armR, 'R']]) {
        band(c, p, 1.0, 1.024, 0.003, '#c8c8a0', 'glowWhite')
        band(c, p, 1.22, 1.244, 0.003, '#c8c8a0', 'glowWhite')
      }
      break
    case 'stripesLegs':
      for (const p of [A.legL, A.legR]) {
        band(c, p, 0.2, 0.226, 0.003, '#c8c8a0', 'glowWhite')
        band(c, p, 0.3, 0.326, 0.003, '#c8c8a0', 'glowWhite')
      }
      break
    case 'stripesChest':
      band(c, T, 1.12, 1.146, 0.003, '#c8c8a0', 'glowWhite')
      band(c, T, 1.3, 1.326, 0.003, '#c8c8a0', 'glowWhite', { th0: PI / 2 + 0.06, th1: PI / 2 - 0.06 + TAU })
      if (c.skirt) band(c, c.skirt, 0.84, 0.866, 0.003, '#c8c8a0', 'glowWhite', { th0: PI / 2 + 0.06, th1: PI / 2 - 0.06 + TAU, wfn: skirtW, off: c.S.off + 0.004 })
      break
    case 'suspenders':
      b.rigid('chest')
      for (const sx of [1, -1]) strap(b, [sp(T, 1.0, sx * 0.07, 1, 0.006), sp(T, 1.2, sx * 0.08, 1, 0.006), sp(T, 1.4, sx * 0.085, 1, 0.006), topPt(T, 1.47, sx * 0.1, 0.007), sp(T, 1.4, sx * 0.07, -1, 0.006), sp(T, 1.2, sx * 0.0, -1, 0.006), sp(T, 1.0, -sx * 0.06, -1, 0.006)], 0.026, 0.003, { mat: 'cloth', color: '#a83020' })
      break
    case 'apron':
    case 'leatherApron': {
      const lth = e === 'leatherApron'
      const ac = lth ? '#6a4228' : '#f2f0ea'
      const m = lth ? 'leather' : 'cloth'
      const sk = c.skirt || skirtPart(A)
      lay(sink, T, { mat: m, col: fabric(ac, { dirt: lth ? 0.3 : 0.2, seed: c.sd + 3 }), y0: 1.0, y1: (th) => 1.355 - 0.004 * Math.abs(Math.cos(th)), th0: PI / 2 - 0.62, th1: PI / 2 + 0.62, off: (y) => T.shellAt(y) + 0.006, hemHi: { t: 0.002, h: 0.008 }, cols: 18, dy: 0.014, shell: false }, res)
      lay(sink, sk, { mat: m, col: fabric(ac, { dirt: lth ? 0.3 : 0.25, seed: c.sd + 4 }), y0: lth ? 0.58 : 0.62, y1: 1.06, th0: PI / 2 - 0.95, th1: PI / 2 + 0.95, off: 0.012 + (c.S.skirt ? c.S.off : 0), hemLo: { t: 0.002, h: 0.008 }, wfn: skirtW, cols: 20, dy: 0.02, shell: false }, res)
      b.rigid('chest')
      for (const sx of [1, -1]) strap(b, [sp(T, 1.35, sx * 0.07, 1, 0.009), topPt(T, 1.48, sx * 0.05, 0.006), sp(T, 1.47, 0, -1, 0.006)], 0.016, 0.003, { mat: m, color: ac })
      band(c, T, 1.02, 1.04, 0.009, ac, m)
      if (lth) {
        b.rigid('hips')
        onSurf(b, sk, 0.86, 0.06, 1, 0.016, () => b.box(0.08, 0.06, 0.006, { mat: 'leather', color: '#4a2a18' }))
      }
      break
    }
    case 'neckerchief':
      band(c, T, 1.468, 1.5, 0.01, '#c83a2a', 'cloth', { th0: PI / 2 + 0.25, th1: PI / 2 - 0.25 + TAU })
      b.rigid('chest')
      onSurf(b, T, 1.452, 0, 1, 0.012, () => b.extrude([[-0.03, 0.01], [0.03, 0.01], [0, -0.045]], 0.008, { mat: 'cloth', color: '#c83a2a', bevel: 0.003 }))
      break
    case 'toolbelt': {
      const y = c.waistTop - 0.03
      band(c, T, y - 0.024, y + 0.024, 0.006, '#7a5a38', 'leather')
      b.rigid('hips')
      for (const sx of [1, -1]) {
        onSurf(b, T, y - 0.06, sx * 0.13, 1, 0.03, () => {
          b.box(0.075, 0.1, 0.05, { mat: 'leather', color: '#8a6a44', r: 0.008 })
          b.box(0.012, 0.08, 0.012, { mat: 'steel', color: '#9a9a9a', x: 0.018, y: 0.06 })
          b.cyl(0.006, 0.006, 0.07, { mat: 'wood', color: '#a07a50', x: -0.02, y: 0.06 })
        })
      }
      onSurf(b, T, y - 0.08, 0.17, 1, 0.012, () => b.box(0.02, 0.14, 0.02, { mat: 'wood', color: '#a07a50', ry: 0.4 }))
      break
    }
    case 'hivis':
    case 'securityvest':
    case 'huntvest': {
      const vc = e === 'hivis' ? '#c8dc28' : e === 'huntvest' ? '#e8661a' : '#1a1a1a'
      vest(c, fabric(vc, { dirt: 0.2, seed: c.sd + 11 }), { gap: e === 'securityvest' ? 0.06 : 0.04 })
      if (e === 'hivis') {
        band(c, T, 1.05, 1.075, 0.002, '#b0b0a0', 'glowWhite', { th0: PI / 2 + 0.07, th1: PI / 2 - 0.07 + TAU })
        b.rigid('chest')
        for (const sx of [1, -1]) strap(b, [sp(T, 1.08, sx * 0.08, 1, 0.003), sp(T, 1.3, sx * 0.085, 1, 0.003), topPt(T, 1.47, sx * 0.1, 0.004), sp(T, 1.3, sx * 0.085, -1, 0.003), sp(T, 1.08, sx * 0.08, -1, 0.003)], 0.024, 0.003, { mat: 'glowWhite', color: '#b0b0a0' })
      }
      if (e === 'securityvest') {
        b.rigid('chest')
        onSurf(b, T, 1.33, 0, -1, 0.004, () => b.box(0.16, 0.034, 0.003, { mat: 'plain', color: '#e8e8e0' }))
      }
      break
    }
    case 'waistcoat': {
      vest(c, fabric('#4a3a4a', { seed: c.sd + 13, dirt: 0.05 }), { gap: 0.02, off: 0.006 })
      b.rigid('spine')
      for (let i = 0; i < 4; i++) onSurf(b, T, 1.08 + i * 0.065, 0.01, 1, 0.002, () => b.sphere(0.0045, { mat: 'gloss', color: '#2a2020', sz: 0.5, ws: 6, hs: 5 }))
      break
    }
    case 'tape':
      b.rigid('chest')
      for (const sx of [1, -1]) strap(b, [topPt(T, 1.48, sx * 0.06, 0.004), sp(T, 1.42, sx * 0.07, 1, 0.003), sp(T, 1.3, sx * 0.07, 1, 0.003), sp(T, 1.18, sx * 0.068, 1, 0.003)], 0.013, 0.0015, { mat: 'plain', color: '#e8c830' })
      break
    case 'glasses': {
      const H = A.head
      const Y = H.Y
      b.rigid('head')
      for (const sx of [1, -1]) {
        const s = H.surf(Y(1.671), sx * 0.036, 1)
        b.torus(0.0175, 0.0018, { mat: 'plain', color: '#1a1a1a', x: s.p[0], y: s.p[1], z: s.p[2] + 0.006, rs: 5, ts2: 16 })
        const R = H.at(Y(1.672), {})
        b.beam([sx * 0.052, Y(1.673), s.p[2] + 0.004], [sx * (R.w + 0.002), Y(1.668), R.z - 0.01], 0.003, 0.003, { mat: 'plain', color: '#1a1a1a' })
      }
      const zb = H.surf(Y(1.675), 0, 1).p[2]
      b.box(0.018, 0.003, 0.003, { mat: 'plain', color: '#1a1a1a', x: 0, y: Y(1.675), z: zb + 0.005 })
      break
    }
    case 'goggles': {
      const H = A.head
      const Y = H.Y
      lay(sink, H, { mat: 'rubber', col: '#2a2a2a', y0: Y(1.73), y1: Y(1.75), off: (H.hairTop || 0) * 0.7 + 0.004, cols: 34, dy: 0.006, shell: false }, res)
      b.rigid('head')
      for (const sx of [1, -1]) {
        const p = H.surf(Y(1.742), sx * 0.034, 1, (H.hairTop || 0) * 0.5 + 0.008).p
        b.cyl(0.021, 0.023, 0.018, { mat: 'rubber', color: '#2a2a2a', x: p[0], y: p[1], z: p[2], rx: PI / 2 - 0.3 })
        b.cyl(0.018, 0.018, 0.004, { mat: 'glass', color: '#8aa8b8', x: p[0], y: p[1] + 0.003, z: p[2] + 0.009, rx: PI / 2 - 0.3 })
      }
      break
    }
    case 'tie':
      b.rigid('chest')
      onSurf(b, T, 1.462, 0, 1, 0.006, () => b.box(0.018, 0.016, 0.01, { mat: 'cloth', color: '#7a2a2a', r: 0.003 }))
      strap(b, [sp(T, 1.455, 0, 1, 0.005), sp(T, 1.36, 0, 1, 0.005), sp(T, 1.24, 0, 1, 0.005), sp(T, 1.12, 0, 1, 0.005)], 0.032, 0.003, { mat: 'cloth', color: '#7a2a2a' })
      onSurf(b, T, 1.11, 0, 1, 0.005, () => b.extrude([[-0.02, 0.01], [0.02, 0.01], [0, -0.016]], 0.003, { mat: 'cloth', color: '#7a2a2a' }))
      break
    case 'scarf': {
      lay(sink, T, { mat: 'knit', col: fabric('#7a3a2a', { dirt: 0.1 }), y0: 1.455, y1: 1.535, off: (y, th) => T.shellAt(1.47) + 0.018 + 0.006 * Math.sin(th * 5 + y * 40), hemLo: { t: 0.003, h: 0.01 }, hemHi: { t: 0.003, h: 0.01 }, cols: 30, dy: 0.01, shell: false }, res)
      lay(sink, T, { mat: 'knit', col: fabric('#7a3a2a', { dirt: 0.1 }), th0: PI / 2 - 0.62, th1: PI / 2 - 0.2, y0: (th) => 1.2 + 0.02 * Math.sin(th * 9), y1: 1.47, off: (y) => T.shellAt(y) + 0.012 + 0.012 * smooth(1.3, 1.46, y), hemLo: { t: 0.004, h: 0.012 }, cols: 8, dy: 0.016, shell: false }, res)
      break
    }
    case 'headband': {
      const H = A.head
      const Y = H.Y
      lay(sink, H, { mat: 'knit', col: '#e8e8e8', y0: Y(1.706), y1: Y(1.73), off: (H.hairTop || 0) * 0.6 + 0.004, hemLo: { t: 0.001, h: 0.004 }, hemHi: { t: 0.001, h: 0.004 }, cols: 34, dy: 0.006, shell: false }, res)
      break
    }
    case 'bandana':
      hat(c, { kind: 'bandana', color: '#a82a2a' })
      break
    case 'rag':
      b.rigid('hips')
      onSurf(b, T, 0.93, -0.07, -1, 0.004, () => b.box(0.05, 0.16, 0.004, { mat: 'cloth', color: '#a82a2a', y: -0.06, rz: 0.15 }))
      break
    case 'pens':
      b.rigid('chest')
      for (let i = 0; i < 3; i++) onSurf(b, T, 1.355, 0.06 + i * 0.012, 1, 0.006, () => b.cyl(0.0035, 0.0035, 0.06, { mat: 'plastic', color: ['#1a3a8a', '#1a1a1a', '#a82a2a'][i] }))
      break
    case 'pencil': {
      const H = A.head
      const R = H.at(H.Y(1.665), {})
      b.rigid('head')
      b.cyl(0.0035, 0.0035, 0.08, { mat: 'plain', color: '#e8b830', x: R.w + 0.012, y: H.Y(1.672), z: R.z + 0.005, rx: PI / 2, rz: 0.2 })
      break
    }
    case 'wrench':
      b.rigid('thighR')
      onSurf(b, A.legR, 0.7, 0.07, 1, 0.014, () => b.box(0.014, 0.2, 0.008, { mat: 'steel', color: '#a8a8a8', y: -0.03 }))
      break
    case 'backpackSmall':
      backpack(c, false)
      break
  }
}
function backpack(c, large) {
  const { b, A } = c
  const T = A.torso
  const h = large ? 0.46 : 0.33
  const col = large ? '#4a5038' : '#3a4a6a'
  const yc = large ? 1.2 : 1.27
  const back = T.surf(yc, 0, -1, 0)
  const d = large ? 0.16 : 0.12
  b.rigid('chest')
  const z = back.p[2] - d / 2 + 0.004
  b.box(0.28, h, d, { mat: 'cloth', color: col, x: 0, y: yc, z, r: 0.045 })
  b.box(0.27, 0.07, d + 0.012, { mat: 'cloth', color: tint(col, 0.9), x: 0, y: yc + h / 2 - 0.03, z: z + 0.002, r: 0.03 })
  b.box(0.2, h * 0.4, 0.05, { mat: 'cloth', color: tint(col, 0.88), x: 0, y: yc - h * 0.18, z: z - d / 2 - 0.018, r: 0.018 })
  for (const sx of [1, -1]) b.box(0.05, h * 0.5, 0.06, { mat: 'cloth', color: tint(col, 0.92), x: sx * 0.15, y: yc - 0.04, z, r: 0.015 })
  b.box(0.04, 0.012, 0.012, { mat: 'steel', color: '#9a9a9a', x: 0, y: yc + h / 2 - 0.06, z: z + d / 2 + 0.004 })
  if (large) {
    b.cyl(0.055, 0.055, 0.32, { mat: 'cloth', color: '#6a5a3a', x: 0, y: yc + h / 2 + 0.04, z, rz: PI / 2, seg: 14 })
    for (const sx of [1, -1]) b.cyl(0.058, 0.058, 0.012, { mat: 'leather', color: '#2a2018', x: sx * 0.1, y: yc + h / 2 + 0.04, z, rz: PI / 2, seg: 14 })
  }
  for (const sx of [1, -1]) strap(b, [sp(T, yc + h / 2 - 0.08, sx * 0.08, -1, 0.004), topPt(T, 1.47, sx * 0.1, 0.008), sp(T, 1.42, sx * 0.09, 1, 0.006), sp(T, 1.3, sx * 0.095, 1, 0.006), sp(T, 1.18, sx * 0.13, 1, 0.006)], 0.04, 0.006, { mat: 'cloth', color: tint(col, 0.75) })
}
function armor(c, kind) {
  const { b, A, sink, res } = c
  const T = A.torso
  if (kind === 'padded') {
    // a quilted jacket: thick, the stuffing pressed into channels
    const fab = fabric('#3e4a5a', { dirt: 0.12, seed: c.sd + 23 })
    const quilt = (x) => {
      fab(x)
      const q = (((x.y - 0.9) / 0.052) % 1 + 1) % 1
      if (q > 0.86) x.c.multiplyScalar(0.62)
      else x.c.multiplyScalar(0.92 + 0.12 * Math.sin(q * Math.PI))
    }
    lay(sink, T, { mat: 'cloth', col: quilt, y0: 0.93, y1: (th) => NECK + 0.01 - 0.04 * Math.max(0, 1 - af(th) / 0.4), off: (y) => T.shellAt(y) + 0.024, hemLo: { t: 0.006, h: 0.03 }, hemHi: { t: 0.008, h: 0.02 }, cols: 38, dy: 0.0135, shell: false }, res)
    for (const p of [A.armL, A.armR]) lay(sink, p, { mat: 'cloth', col: quilt, y0: 0.94, y1: p.y1, off: (y) => p.shellAt(y) + 0.02, hemLo: { t: 0.005, h: 0.03 }, capHi: true, cols: 16, dy: 0.015, shell: false }, res)
    T.shells.push({ y0: 0.93, y1: 1.46, side: null, off: (y) => T.shellAt(y) + 0.026 })
    // a zip up the front and a collar turned up
    b.rigid('spine')
    strap(b, [0.98, 1.08, 1.18, 1.28, 1.38, 1.45].map((y) => sp(T, y, 0, 1, 0.026)), 0.008, 0.003, { mat: 'steel', color: '#9a9a96' })
    return
  }
  if (kind === 'jacket') {
    const lc = '#3a2a1e'
    lay(sink, T, { mat: 'leather', col: fabric(lc, { dirt: 0.15 }), y0: 0.95, y1: (th) => NECK + 0.004 - 0.05 * Math.max(0, 1 - af(th) / 0.4), off: (y) => T.shellAt(y) + 0.01, hemLo: { t: 0.004, h: 0.03 }, hemHi: { t: 0.003, h: 0.012 }, cols: 38, dy: 0.0135, shell: false }, res)
    for (const p of [A.armL, A.armR]) lay(sink, p, { mat: 'leather', col: fabric(lc, { dirt: 0.15 }), y0: 0.935, y1: p.y1, off: (y) => p.shellAt(y) + 0.008, hemLo: { t: 0.003, h: 0.03 }, capHi: true, cols: 16, dy: 0.015, shell: false }, res)
    b.rigid('spine')
    const pts = [1.0, 1.1, 1.2, 1.3, 1.42].map((y) => sp(T, y, 0.02, 1, 0.012))
    strap(b, pts, 0.006, 0.003, { mat: 'steel', color: '#8a8a86' })
    b.rigid('chest')
    for (const sx of [1, -1]) strap(b, [topPt(T, 1.482, sx * 0.06, 0.014), sp(T, 1.42, sx * 0.07, 1, 0.016), sp(T, 1.36, sx * 0.04, 1, 0.016)], 0.05, 0.005, { mat: 'leather', color: '#2e2016' })
    return
  }
  if (kind === 'vest' || kind === 'military') {
    const vc = kind === 'vest' ? '#2a3028' : '#4e5438'
    const base = (y) => T.shellAt(y)
    for (const side of [1, -1]) {
      lay(sink, T, { mat: 'cloth', col: fabric(vc, { camo: kind === 'military', dirt: 0.25, seed: c.sd + 17 }), y0: 1.06, y1: side > 0 ? 1.41 : 1.44, th0: side > 0 ? PI / 2 - 1.18 : -PI / 2 - 1.18, th1: side > 0 ? PI / 2 + 1.18 : -PI / 2 + 1.18, off: (y) => base(y) + 0.026, hemLo: { t: 0.004, h: 0.012 }, hemHi: { t: 0.004, h: 0.012 }, cols: 22, dy: 0.016, shell: false }, res)
    }
    T.shells.push({ y0: 1.06, y1: 1.44, side: null, off: (y) => base(y) + 0.03 })
    b.rigid('chest')
    for (const sx of [1, -1]) strap(b, [sp(T, 1.4, sx * 0.08, 1, 0.002), topPt(T, 1.47, sx * 0.11, 0.012), sp(T, 1.42, sx * 0.08, -1, 0.002)], 0.05, 0.012, { mat: 'cloth', color: tint(vc, 0.9) })
    b.rigid('spine')
    for (const x of [-0.1, 0, 0.1]) onSurf(b, T, 1.14, x, 1, 0.016, () => {
      b.box(0.064, 0.085, 0.034, { mat: 'cloth', color: tint(vc, 0.86), r: 0.006 })
      b.box(0.066, 0.026, 0.038, { mat: 'cloth', color: tint(vc, 0.78), y: 0.036, r: 0.005 })
    })
    if (kind === 'military') {
      b.rigid('chest')
      onSurf(b, T, 1.33, 0.09, 1, 0.014, () => b.box(0.06, 0.08, 0.03, { mat: 'cloth', color: tint(vc, 0.8), r: 0.006 }))
      if (!c.spec.outfit?.hat) hat(c, { kind: 'helmet', color: '#4e5438' })
      for (const side of ['L', 'R']) {
        const p = side === 'L' ? A.legL : A.legR
        lay(sink, p, { mat: 'paint', col: '#3a3e2a', y0: 0.455, y1: 0.555, th0: PI / 2 - 1.0, th1: PI / 2 + 1.0, off: (y) => p.shellAt(y) + 0.012, hemLo: { t: 0.003, h: 0.008 }, hemHi: { t: 0.003, h: 0.008 }, cols: 12, dy: 0.012, shell: false }, res)
      }
    } else {
      b.rigid('chest')
      onSurf(b, T, 1.34, 0, 1, 0.028, () => b.box(0.13, 0.032, 0.003, { mat: 'plain', color: '#e8e8e0' }))
    }
    return
  }
  if (kind === 'riot') {
    const rc = '#1c2026'
    lay(sink, T, { mat: 'paint', col: fabric(rc, { dirt: 0.2, seed: c.sd + 19 }), y0: 1.05, y1: (th) => 1.44 + 0.02 * smooth(1.2, 2.2, af(th)), off: (y) => T.shellAt(y) + 0.03, hemLo: { t: 0.004, h: 0.012 }, hemHi: { t: 0.004, h: 0.012 }, cols: 36, dy: 0.016, shell: false }, res)
    for (const side of ['L', 'R']) {
      const a = side === 'L' ? A.armL : A.armR
      const l = side === 'L' ? A.legL : A.legR
      lay(sink, a, { mat: 'paint', col: rc, y0: 1.34, y1: a.y1, off: (y) => a.shellAt(y) + 0.02, hemLo: { t: 0.004, h: 0.01 }, capHi: true, cols: 16, dy: 0.012, shell: false }, res)
      lay(sink, a, { mat: 'paint', col: rc, y0: 0.96, y1: 1.12, th0: PI / 2 - 1.4, th1: PI / 2 + 1.4, off: (y) => a.shellAt(y) + 0.012, hemLo: { t: 0.003, h: 0.008 }, hemHi: { t: 0.003, h: 0.008 }, cols: 12, dy: 0.012, shell: false }, res)
      lay(sink, l, { mat: 'paint', col: rc, y0: 0.15, y1: 0.54, th0: PI / 2 - 1.1, th1: PI / 2 + 1.1, off: (y) => l.shellAt(y) + 0.016, hemLo: { t: 0.003, h: 0.008 }, hemHi: { t: 0.003, h: 0.008 }, cols: 12, dy: 0.016, shell: false }, res)
      lay(sink, l, { mat: 'paint', col: rc, y0: 0.6, y1: 0.82, th0: PI / 2 - 1.0, th1: PI / 2 + 1.0, off: (y) => l.shellAt(y) + 0.012, hemLo: { t: 0.003, h: 0.008 }, hemHi: { t: 0.003, h: 0.008 }, cols: 12, dy: 0.016, shell: false }, res)
    }
    const H = A.head
    lay(sink, H, { mat: 'paint', col: rc, y0: (th) => H.Y(1.64 + 0.05 * smooth(1.1, 0.5, af(th))), y1: H.y1, off: (H.hairTop || 0) * 0.6 + 0.026, hemLo: { t: 0.004, h: 0.008 }, capHi: true, cols: 36, dy: 0.008, shell: false }, res)
    b.rigid('head')
    b.sphere(0.13, { mat: 'glass', color: '#9ab0c0', x: 0, y: H.Y(1.655), z: 0.012, ts: PI * 0.36, tl: PI * 0.34, ps: PI * 0.12, pl: PI * 0.76, sx: 0.98, sz: 1.04 })
    return
  }
  if (kind === 'ghillie') {
    const cols = ['#4a5a30', '#5a5a38', '#3e4a28', '#6a6440']
    const rr = c.rnd
    for (const [p, bn, y0, y1] of [[T, 'chest', 1.1, 1.46], [T, 'spine', 0.95, 1.2], [A.armL, 'upperArmL', 1.2, 1.45], [A.armR, 'upperArmR', 1.2, 1.45], [A.legL, 'thighL', 0.55, 0.9], [A.legR, 'thighR', 0.55, 0.9]]) {
      b.rigid(bn)
      for (let i = 0; i < 16; i++) {
        const y = y0 + rr() * (y1 - y0)
        const q = p.at(y, {})
        onSurf(b, p, y, (rr() - 0.5) * q.w * 1.6, rr() < 0.5 ? 1 : -1, 0.01, () => b.box(0.02, 0.13, 0.008, { mat: 'cloth', color: cols[i % 4], y: -0.04, rz: (rr() - 0.5) * 0.8, rx: (rr() - 0.5) * 0.5 }))
      }
    }
  }
}
// What's been fitted to the armour: steel plates on the chest and back,
// quilted padding at the shoulders, scraps of camouflage.
function armorMods(c, mods) {
  const { b, A, rnd } = c
  const T = A.torso
  if (mods.includes('plates')) {
    b.rigid('chest')
    for (const side of [1, -1]) onSurf(b, T, 1.3, 0, side, 0.05, () => {
      b.box(0.2, 0.16, 0.012, { mat: 'steel', color: '#6e7276', r: 0.012 })
      for (const sx of [-0.085, 0.085]) for (const sy of [-0.065, 0.065]) b.cyl(0.006, 0.006, 0.006, { mat: 'steel', color: '#9a9ea2', x: sx, y: sy, z: 0.007, rx: Math.PI / 2 })
    })
    b.rigid('spine')
    onSurf(b, T, 1.1, 0, 1, 0.045, () => b.box(0.18, 0.1, 0.01, { mat: 'steel', color: '#62666a', r: 0.01 }))
  }
  if (mods.includes('padding')) {
    for (const [p, bn] of [[A.armL, 'upperArmL'], [A.armR, 'upperArmR']]) {
      b.rigid(bn)
      onSurf(b, p, 1.38, 0, 1, 0.022, () => {
        b.box(0.075, 0.09, 0.026, { mat: 'cloth', color: '#5a5a48', r: 0.012 })
        for (const y of [-0.02, 0.02]) b.box(0.078, 0.004, 0.028, { mat: 'cloth', color: '#3e3e30', y })
      })
    }
  }
  if (mods.includes('camo')) {
    const cols = ['#4a5a30', '#5a5a38', '#3e4a28', '#6a6440']
    for (const [p, bn, y0, y1] of [[T, 'chest', 1.3, 1.46], [A.armL, 'upperArmL', 1.3, 1.45], [A.armR, 'upperArmR', 1.3, 1.45]]) {
      b.rigid(bn)
      for (let i = 0; i < 6; i++) {
        const y = y0 + rnd() * (y1 - y0)
        const q = p.at(y, {})
        onSurf(b, p, y, (rnd() - 0.5) * q.w * 1.5, rnd() < 0.5 ? 1 : -1, 0.03, () => b.box(0.018, 0.1, 0.006, { mat: 'cloth', color: cols[i % 4], y: -0.03, rz: (rnd() - 0.5) * 0.8 }))
      }
    }
  }
}
// Gear worn where it shows: a gas mask, a helmet, goggles pushed up, field
// glasses on the chest, a torch and a radio clipped to the straps.
function gearLook(c, kind) {
  const { b, A, sink, res } = c
  const H = A.head
  const T = A.torso
  const Y = H.Y
  const ht = (H.hairTop || 0) * 0.6
  if (kind === 'gasmask') {
    lay(sink, H, { mat: 'rubber', col: '#26282a', y0: Y(1.588), y1: Y(1.702), th0: Math.PI / 2 - 1.3, th1: Math.PI / 2 + 1.3, off: 0.011, hemLo: { t: 0.004, h: 0.008 }, hemHi: { t: 0.004, h: 0.008 }, cols: 22, dy: 0.008, shell: false }, res)
    lay(sink, H, { mat: 'rubber', col: '#1c1d1e', y0: Y(1.684), y1: Y(1.702), off: ht + 0.007, cols: 30, dy: 0.008, shell: false }, res)
    b.rigid('head')
    for (const sx of [1, -1]) onSurf(b, H, Y(1.668), sx * 0.034, 1, 0.016, () => {
      b.cyl(0.021, 0.021, 0.012, { mat: 'glass', color: '#5a7080', rx: Math.PI / 2, seg: 14 })
      b.torus(0.021, 0.004, { mat: 'rubber', color: '#141516', rs: 5, ts2: 16 })
    })
    onSurf(b, H, Y(1.604), 0, 1, 0.018, () => {
      b.cyl(0.026, 0.029, 0.05, { mat: 'metal', color: '#4a4e44', rx: Math.PI / 2, z: 0.026, seg: 14 })
      b.cyl(0.03, 0.03, 0.008, { mat: 'metal', color: '#3a3e36', rx: Math.PI / 2, z: 0.052, seg: 14 })
    })
    return
  }
  if (kind === 'helmet') {
    if (!c.spec.outfit?.hat) hat(c, { kind: 'helmet', color: '#2e3236' })
    return
  }
  if (kind === 'nvg') {
    b.rigid('head')
    onSurf(b, H, Y(1.735), 0, 1, ht + 0.02, () => {
      b.box(0.05, 0.026, 0.03, { mat: 'paint', color: '#1e2220', r: 0.006 })
      for (const sx of [-0.022, 0.022]) b.cyl(0.013, 0.015, 0.05, { mat: 'paint', color: '#262a28', x: sx, y: 0.012, z: 0.03, rx: Math.PI / 2 - 0.6, seg: 10 })
    })
    lay(sink, H, { mat: 'cloth', col: '#1e2220', y0: Y(1.715), y1: Y(1.73), off: ht + 0.008, cols: 30, dy: 0.008, shell: false }, res)
    return
  }
  b.rigid('chest')
  if (kind === 'binoculars') {
    onSurf(b, T, 1.28, 0, 1, 0.045, () => {
      for (const sx of [-0.03, 0.03]) b.cyl(0.022, 0.024, 0.1, { mat: 'rubber', color: '#1e1f20', x: sx, seg: 12 })
      b.box(0.04, 0.03, 0.02, { mat: 'paint', color: '#2a2b2c', y: 0.03 })
    })
    for (const sx of [1, -1]) strap(b, [sp(T, 1.32, sx * 0.05, 1, 0.03), topPt(T, 1.47, sx * 0.05, 0.01), sp(T, 1.42, sx * 0.05, -1, 0.004)], 0.008, 0.002, { mat: 'cloth', color: '#1a1a1a' })
  } else if (kind === 'torch') {
    onSurf(b, T, 1.37, -0.09, 1, 0.022, () => {
      b.cyl(0.013, 0.017, 0.11, { mat: 'metal', color: '#2a2a2c', rx: -0.3, seg: 10 })
      b.cyl(0.018, 0.018, 0.006, { mat: 'glass', color: '#f0ead0', y: 0.056, z: -0.017, rx: -0.3 })
    })
  } else if (kind === 'walkie') {
    onSurf(b, T, 1.36, 0.09, 1, 0.02, () => {
      b.box(0.045, 0.085, 0.026, { mat: 'paint', color: '#1e2022', r: 0.006 })
      b.cyl(0.004, 0.004, 0.08, { mat: 'rubber', color: '#141414', x: 0.013, y: 0.08 })
      b.box(0.03, 0.02, 0.004, { mat: 'glass', color: '#6a8a6a', y: 0.018, z: 0.014 })
    })
  }
}
function gore(c) {
  const { b, A, rnd } = c
  const spots = [[A.torso, 'chest', 1.25, 1.42], [A.torso, 'spine', 1.05, 1.22], [A.armL, 'upperArmL', 1.2, 1.4], [A.armR, 'upperArmR', 1.2, 1.4], [A.armL, 'foreArmL', 0.95, 1.1], [A.legL, 'thighL', 0.6, 0.85], [A.legR, 'thighR', 0.6, 0.85]]
  const n = 3 + Math.floor(rnd() * 4)
  for (let i = 0; i < n; i++) {
    const [p, bn, y0, y1] = spots[Math.floor(rnd() * spots.length)]
    const y = y0 + rnd() * (y1 - y0)
    const q = p.at(y, {})
    b.rigid(bn)
    onSurf(b, p, y, (rnd() - 0.5) * q.w * 1.2, rnd() < 0.65 ? 1 : -1, 0.0008, () => {
      const big = p === A.torso
      const r0 = big ? 0.026 : 0.016
      b.ico(r0, { mat: 'gloss', color: ['#4a0e0a', '#561410', '#3a0a08'][i % 3], sz: 0.18, sx: 1 + rnd() * 0.7, rz: rnd() * 3, detail: 1, noise: 0.5 })
      b.ico(r0 * 0.55, { mat: 'gloss', color: '#240504', z: 0.0015, sz: 0.2, sx: 1.3, rz: rnd() * 3, detail: 1, noise: 0.4 })
    })
  }
}
