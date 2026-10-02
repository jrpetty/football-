// Hearing the world: a sound comes from where its source is on screen (left
// or right), fades with distance from whoever would hear it, and is muffled
// through walls. On a run the listeners are the squad, so infected around a
// corner are heard before they are seen.
import * as THREE from 'three'
import { view } from '../render/view.js'
import { sfxAt } from '../core/audio.js'

const _right = new THREE.Vector3()
// for tests: the last sounds placed, with where they came from
export const soundLog = { on: false, list: [] }
// how far each kind of sound carries, in metres
const RANGE = { groan: 22, zdie: 26, hit: 30, swing: 18, hurt: 30, down: 40, scream: 60, glass: 45, dismantle: 34, search: 16, pistol: 70, smg: 70, shotgun: 80, rifle: 90, crossbow: 22, boom: 120, fire: 30 }

// world: anything with listener(pos) -> {x, z} and, optionally,
// wallBetween(a, b) -> bool and hearingK() (a multiplier on range)
export function soundAt(world, id, pos, { throttle = 40, pitch = 1, loud = 1, range = null } = {}) {
  const L = world.listener?.(pos) || view.rig.target
  const R = (range ?? RANGE[id] ?? 30) * (world.hearingK?.() ?? 1)
  // storeys count too: a sound two floors up is further off than it looks
  const d = Math.hypot(pos.x - L.x, pos.z - L.z, ((pos.y || 0) - (L.y || 0)) * 1.6)
  if (d > R) return
  // left or right of the middle of the screen
  _right.setFromMatrixColumn(view.camera.matrixWorld, 0)
  const rl = Math.hypot(_right.x, _right.z) || 1
  const sx = pos.x - view.rig.target.x
  const sz = pos.z - view.rig.target.z
  const off = (sx * _right.x + sz * _right.z) / rl
  const pan = Math.max(-0.9, Math.min(0.9, off / Math.max(8, view.rig.dist * 0.45)))
  // softer with distance, duller far off, and much duller through a wall
  let gain = loud * Math.pow(1 - d / R, 1.3)
  let muffle = (d / R) * 0.3
  if (d > 1.5 && world.wallBetween?.(L, pos)) {
    gain *= 0.6
    muffle = Math.max(muffle, 0.82)
  }
  if (gain < 0.04) return
  if (soundLog.on) soundLog.list.push({ id, pan: +pan.toFixed(2), gain: +gain.toFixed(2), muffle: +muffle.toFixed(2), d: +d.toFixed(1) })
  sfxAt(id, { pan, gain: Math.min(1, gain), muffle, pitch }, throttle)
}
