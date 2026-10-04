// First-person rules shared by runs and the camp: walking a body through the
// tile grid without passing through anything solid, and shots and swings
// that land where the player aims. The world supplies who can be hit and
// what stops a bullet; damage, kills, noise, wear and experience go through
// the same calls the AI uses (zombie.hurt, world.noise, wear, gainXP).
import { magOf, setMag, reserveOf } from './mag.js'
import * as THREE from 'three'
import { wear, gainXP } from '../game/state.js'
import { rand, clamp } from '../core/util.js'

const _p = new THREE.Vector3()

// Move a point by (dx, dz), sliding along blocked tiles. open(i, j) says
// whether a tile can be stood in; the body is a square of half-size r.
// Returns the distance actually moved.
export function slideMove(open, pos, dx, dz, r = 0.22) {
  const ok = (x, z) => open(Math.floor(x - r), Math.floor(z - r)) && open(Math.floor(x + r), Math.floor(z - r)) && open(Math.floor(x - r), Math.floor(z + r)) && open(Math.floor(x + r), Math.floor(z + r))
  const x0 = pos.x
  const z0 = pos.z
  // long steps (a hitch) are cut up so nothing is tunnelled through
  const n = Math.max(1, Math.ceil(Math.hypot(dx, dz) / 0.15))
  for (let s = 0; s < n; s++) {
    const nx = pos.x + dx / n
    if (ok(nx, pos.z)) pos.x = nx
    else {
      // up against it: slide to just short of the blocked tile
      const edge = dx > 0 ? Math.floor(pos.x + r) + 1 - r - 1e-3 : Math.floor(pos.x - r) + r + 1e-3
      if (ok(edge, pos.z) && Math.abs(edge - pos.x) < Math.abs(dx / n) + 1e-3) pos.x = edge
    }
    const nz = pos.z + dz / n
    if (ok(pos.x, nz)) pos.z = nz
    else {
      const edge = dz > 0 ? Math.floor(pos.z + r) + 1 - r - 1e-3 : Math.floor(pos.z - r) + r + 1e-3
      if (ok(pos.x, edge) && Math.abs(edge - pos.z) < Math.abs(dz / n) + 1e-3) pos.z = edge
    }
  }
  return { x: pos.x - x0, z: pos.z - z0 }
}

// A direction nudged at random within a cone of the given half-angle.
export function jitter(dir, spread) {
  if (spread <= 0) return dir.clone()
  const a = Math.random() * Math.PI * 2
  const r = Math.sqrt(Math.random()) * spread
  const up = Math.abs(dir.y) > 0.95 ? new THREE.Vector3(1, 0, 0) : new THREE.Vector3(0, 1, 0)
  const u = new THREE.Vector3().crossVectors(dir, up).normalize()
  const v = new THREE.Vector3().crossVectors(u, dir).normalize()
  return dir.clone().addScaledVector(u, Math.cos(a) * r).addScaledVector(v, Math.sin(a) * r).normalize()
}

// Where a zombie can be hit: an upright cylinder round its drawn position
// (crawlers lie flat: low and wide). Returns [tEnter, height above its feet].
function hitZombie(z, o, d, maxT) {
  const s = z.def?.scale ?? 1
  const crawl = !!z.def?.crawl
  const R = crawl ? 0.55 : 0.34 * s
  const H = crawl ? 0.55 : 1.82 * s
  const c = z.rpos
  const ox = o.x - c.x
  const oz = o.z - c.z
  const a = d.x * d.x + d.z * d.z
  const b = 2 * (ox * d.x + oz * d.z)
  const cc = ox * ox + oz * oz - R * R
  let t0, t1
  if (a < 1e-9) {
    if (cc > 0) return null
    t0 = -Infinity
    t1 = Infinity
  } else {
    const disc = b * b - 4 * a * cc
    if (disc < 0) return null
    const q = Math.sqrt(disc)
    t0 = (-b - q) / (2 * a)
    t1 = (-b + q) / (2 * a)
  }
  // the slab between its feet and the top of its head
  const y0 = c.y
  const y1 = c.y + H
  if (Math.abs(d.y) < 1e-9) {
    if (o.y < y0 || o.y > y1) return null
  } else {
    let ta = (y0 - o.y) / d.y
    let tb = (y1 - o.y) / d.y
    if (ta > tb) [ta, tb] = [tb, ta]
    t0 = Math.max(t0, ta)
    t1 = Math.min(t1, tb)
  }
  if (t1 < t0 || t1 < 0 || t0 > maxT) return null
  const t = Math.max(0, t0)
  return [t, o.y + d.y * t - y0, H]
}

// Trace a shot: the nearest zombie it meets, or what stops it first.
// W.fpSolid(x, y, z) says whether a point is inside something solid.
export function traceShot(W, o, d, maxD) {
  let best = null
  for (const z of W.zombies) {
    if (z.dead || !z.root?.visible) continue
    const h = hitZombie(z, o, d, maxD)
    if (h && (!best || h[0] < best.t)) best = { z, t: h[0], head: !z.def?.crawl && h[1] > h[2] * 0.8 }
  }
  const lim = best ? best.t : maxD
  // march to the first solid point (or the ground)
  const step = 0.08
  for (let t = 0.12; t < lim; t += step) {
    _p.copy(o).addScaledVector(d, t)
    if (W.fpSolid(_p.x, _p.y, _p.z)) {
      return { t, point: _p.clone(), wall: true }
    }
  }
  if (best) return { ...best, point: o.clone().addScaledVector(d, best.t) }
  return null
}

const SOUND = { shotgun: 'shotgun', rifle: 'rifle', smg: 'smg', ar: 'smg', crossbow: 'crossbow' }
const RECOIL = { pistol: 0.6, revolver: 1.1, smg: 0.32, ar: 0.42, shotgun: 1.6, rifle: 1.4, crossbow: 0.5 }

// A shot from the eye along dir. o: { spread, muzzle (world), onHit(kill,
// head), dmgMul }. Returns what the view model needs: the rate, recoil,
// whether it is automatic, or { empty } when out of ammo.
export function fpShoot(a, W, origin, dir, o = {}) {
  const st = a.st
  const id = st.weaponId
  // a round from the magazine; empty, it says whether there is more to load
  const m = magOf(a)
  if (m.n <= 0) return { empty: true, reload: reserveOf(a, W) > 0 }
  setMag(a, m.n - 1)
  a.cool = st.rate
  const pellets = st.weapon.falloff ? 8 : 1
  const maxD = pellets > 1 ? 30 : 80
  // aimed fire carries further than the AI's careful range, losing punch
  const far = st.range * 1.6
  let kill = false
  let head = false
  let hitAny = false
  const muzzle = o.muzzle || origin
  for (let i = 0; i < pellets; i++) {
    const d = jitter(dir, (o.spread ?? 0.01) + (pellets > 1 ? 0.05 : 0))
    const hit = traceShot(W, origin, d, maxD)
    const end = hit ? hit.point : origin.clone().addScaledVector(d, maxD)
    // the tracer starts a little out from the muzzle, not in the eye
    if (i < 3) {
      const from = muzzle.clone().addScaledVector(d, 0.9)
      if (from.distanceTo(end) > 1) W.fx.tracer(from, end, id === 'crossbow' ? '#c8b080' : '#ffd890')
    }
    if (hit?.z) {
      const dist = hit.t
      let dmg = st.dmg * rand(0.9, 1.1)
      if (pellets > 1) dmg *= clamp(1.3 - dist / (st.range * 1.4), 0.15, 1.1) / 4.6
      else if (dist > far) dmg *= clamp(1 - (dist - far) / (far * 2.5), 0.35, 1)
      if (hit.head) dmg *= 2
      dmg *= (W.dmgBonus?.(a) ?? 1) * (o.dmgMul ?? 1)
      const z = hit.z
      z.hurt(dmg, a)
      hitAny = true
      if (z.dead) kill = true
      if (hit.head) head = true
    } else if (hit?.wall) {
      W.fx.sparks(hit.point, pellets > 1 ? 2 : 5, '#ffd8a0')
      W.fx.dust?.(hit.point, 3, '#8a8478')
    }
  }
  W.fx.flash(muzzle, '#ffb060', 10, 0.06, 8)
  a.shots = (a.shots || 0) + 1
  a.play(SOUND[id] || 'pistol', 30)
  W.noise?.(a.pos.x, a.pos.z, st.noise * st.noiseMult)
  if (st.weaponItem && wear(st.weaponItem, 1)) a.weaponBroke()
  gainXP(a.data, 'ranged', 0.8)
  if (hitAny) o.onHit?.(kill, head)
  return { kind: 'gun', rate: st.rate, auto: id === 'smg' || id === 'ar', recoil: (RECOIL[id] ?? 0.6) * (st.recoil ?? 1), cycle: (id === 'shotgun' || id === 'rifle' || id === 'crossbow') && m.n > 1, last: m.n <= 1 }
}

// A swing (or a punch, or a gun used as a club) at whatever is in front.
// The blow lands partway through the swing: o.delay seconds.
export function fpMelee(a, W, origin, dir, o = {}) {
  const st = a.st
  const armed = !st.gun
  a.cool = armed ? st.rate : 0.8
  a.swing = 1
  a.play('swing', 60)
  const base = armed ? st.dmg : 7
  const reach = (armed ? st.range : 1.3) + 0.5
  const knock = armed && st.knock
  const fx = dir.x
  const fz = dir.z
  const fl = Math.hypot(fx, fz) || 1
  setTimeout(() => {
    if (a.downed || a.dead) return
    let best = null
    let bs = Infinity
    for (const z of W.zombies) {
      if (z.dead) continue
      const dx = z.rpos.x - a.rpos.x
      const dz = z.rpos.z - a.rpos.z
      const dy = z.rpos.y - a.rpos.y
      const d = Math.hypot(dx, dz)
      if (d > reach + (z.radius || 0.3) || Math.abs(dy) > 1.4) continue
      const cos = d < 0.05 ? 1 : (dx * fx + dz * fz) / (d * fl)
      if (cos < 0.55) continue
      // nearest and most nearly ahead first
      const score = d * (2 - cos)
      if (score < bs) {
        bs = score
        best = z
      }
    }
    if (!best) return
    const dmg = base * rand(0.88, 1.12) * (W.dmgBonus?.(a) ?? 1)
    best.hurt(dmg, a, { knock })
    best.play('hit', 40)
    if (armed && st.weaponItem && wear(st.weaponItem, 1)) a.weaponBroke()
    o.onHit?.(best.dead, false)
  }, (o.delay ?? 0.16) * 1000)
  W.noise?.(a.pos.x, a.pos.z, 2 * st.noiseMult)
  gainXP(a.data, 'melee', 0.8)
  return { kind: 'melee', rate: a.cool }
}

// A silent takedown: crouched, close behind a zombie that hasn't noticed
// you, and looking at it. Brutes are too big to get an arm round and a
// bloater would burst over you.
const NO_TAKEDOWN = new Set(['brute', 'bloater'])
const AWARE = new Set(['chase', 'stalk', 'bash', 'fence'])
export function takedownTarget(a, W, dir) {
  if (!a?.fpCrouch || a.downed || a.dead) return null
  const fl = Math.hypot(dir.x, dir.z) || 1
  let best = null
  let bd = 1.75
  for (const z of W.zombies) {
    if (z.dead || NO_TAKEDOWN.has(z.type) || z.takenDown) continue
    if (AWARE.has(z.state)) continue
    const dx = z.rpos.x - a.rpos.x
    const dz = z.rpos.z - a.rpos.z
    const d = Math.hypot(dx, dz)
    if (d > bd || d < 0.05 || Math.abs(z.rpos.y - a.rpos.y) > 1) continue
    // in front of you...
    if ((dx * dir.x + dz * dir.z) / (d * fl) < 0.6) continue
    // ...and it faces away: its back is to you
    if ((dx * Math.sin(z.heading) + dz * Math.cos(z.heading)) / d < 0.3) continue
    best = z
    bd = d
  }
  return best
}
// Do it: the zombie is held still, then dropped without a sound to carry.
export function takedown(a, W, z, onDone) {
  z.takenDown = true
  z.stun = Math.max(z.stun || 0, 1.2)
  z.path = null
  z.state = 'idle'
  a.cool = Math.max(a.cool || 0, 0.9)
  a.play('takedown', 18)
  setTimeout(() => {
    if (z.dead || a.downed || a.dead) {
      z.takenDown = false
      return
    }
    z.quiet = true
    W.fx?.blood?.(z.chestPos ? z.chestPos(1.35) : z.rpos.clone().setY(z.rpos.y + 1.4))
    z.hp = 0
    z.die(a)
    gainXP(a.data, 'melee', 1.6)
    onDone?.(z)
  }, 330)
}
