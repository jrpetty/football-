// A material's shader is compiled the first time something wearing it is
// drawn, and that compile holds up the frame: the first farm you placed, or
// the first face in a panel, froze the game for a moment. While the camp sits
// idle after loading, this builds every station once and compiles its
// materials in the background (in parallel where the browser can), the same
// way the camp view will draw them, plus the ghosts, blueprints and a
// portrait.
import * as THREE from 'three'
import { viewModel, fpLook } from '../models/viewmodel.js'
import { OUTFITS } from '../models/character.js'
import { STATIONS } from '../game/data.js'
import { S } from '../game/state.js'
import { stationModel } from '../models/stations.js'
import { GHOST_OK, GHOST_BAD } from '../scenes/base.js'
import { BLUEPRINT } from '../scenes/basestation.js'
import { batchedCopies } from '../scenes/basebatch.js'
import { view } from './view.js'

// Compile everything `obj` wears against `scene`'s lights, without holding up
// frames. Scenes draw into the composer's render target, and shaders are
// keyed on that, so the compile happens with a render target bound too.
let warmRT = null
export function compileFor(renderer, obj, camera, scene = null) {
  if (!renderer?.compileAsync) return Promise.resolve()
  warmRT ??= new THREE.WebGLRenderTarget(1, 1, { type: THREE.HalfFloatType })
  const prev = renderer.getRenderTarget()
  renderer.setRenderTarget(warmRT)
  // without parallel compiling the browser still starts the work now, well
  // ahead of the first draw that needs it
  const parallel = renderer.extensions.has('KHR_parallel_shader_compile')
  let p
  try {
    p = parallel ? renderer.compileAsync(obj, camera, scene) : renderer.compile(obj, camera, scene)
  } catch {
    p = null
  } finally {
    renderer.setRenderTarget(prev)
  }
  return Promise.resolve(p).catch(() => {})
}

// Draw a scene once, out of sight, so its geometry and textures upload (and
// its shadow shaders compile) before the first frame anyone sees.
export function preRender(renderer, scene, camera) {
  if (!renderer) return
  warmRT ??= new THREE.WebGLRenderTarget(1, 1, { type: THREE.HalfFloatType })
  const prev = renderer.getRenderTarget()
  try {
    renderer.setRenderTarget(warmRT)
    renderer.render(scene, camera)
  } catch {
    // only a head start
  } finally {
    renderer.setRenderTarget(prev)
  }
}

const idle = (fn) => (window.requestIdleCallback ? requestIdleCallback(fn, { timeout: 1200 }) : setTimeout(fn, 50))

export function warmUp(game) {
  const r = game.pipe?.renderer
  if (!r?.compileAsync || game.warming) return
  game.warming = true
  // a portrait, so the first panel of faces has its shaders ready
  if (S.survivors[0]) game.portrait(S.survivors[0])
  // stations at level 1 (what gets placed), then the next level of each one
  // already standing (what gets upgraded)
  const jobs = Object.keys(STATIONS).map((t) => [t, 1])
  for (const st of S.stations) if (st.level >= 1 && st.level < (STATIONS[st.type]?.levels || 1)) jobs.push([st.type, st.level + 1])
  jobs.push(['ghosts', 0])
  // first person: the arms and a weapon, and the sky
  jobs.push(['fp', 0])
  // whatever in camp is hidden for now (the placement cursor, raid gear)
  jobs.unshift(['scene', 0])
  const compile = (obj) => compileFor(r, obj, view.camera, game.base.scene)
  const step = () => {
    // only in camp: the camp's lights decide which shaders are needed
    if (game.scene !== game.base || !game.base?.scene) return setTimeout(() => idle(step), 3000)
    const job = jobs.shift()
    if (!job) {
      game.warmed = true
      return
    }
    let obj
    let tmp = null
    try {
      if (job[0] === 'scene') obj = game.base.scene
      else if (job[0] === 'fp') {
        const fp = game.fp
        const s = S.survivors[0]
        if (!fp || !s) return idle(step)
        const vm = viewModel('pistol', [], fpLook(s, OUTFITS[s.occ] || OUTFITS.drifter))
        const melee = viewModel('bat', [], fpLook(s, OUTFITS[s.occ] || OUTFITS.drifter))
        fp.layer.root.add(vm.rig, melee.rig)
        compileFor(r, fp.layer.scene, fp.layer.camera, fp.layer.scene).then(() => {
          fp.layer.root.remove(vm.rig, melee.rig)
          const dome = game.base.atmo.dome.mesh
          const was = dome.visible
          dome.visible = true
          compile(dome).then(() => {
            dome.visible = was
            idle(step)
          })
        })
        return
      } else if (job[0] === 'ghosts') {
        // a placing ghost or a blueprint wears one material over a station's
        // own meshes (instanced or not): dress every station that way
        obj = new THREE.Group()
        for (const m of [GHOST_OK, GHOST_BAD, BLUEPRINT])
          for (const t of Object.keys(STATIONS)) {
            let g
            try {
              g = stationModel(t, 1)
            } catch {
              continue
            }
            g.traverse((o) => {
              if (!o.isMesh) return
              o.material = m
              o.castShadow = o.receiveShadow = false
            })
            obj.add(g)
          }
      } else {
        // as the camp draws it: still parts batched, moving parts on their own
        const m = stationModel(job[0], job[1])
        tmp = batchedCopies(m)
        obj = new THREE.Group().add(m, tmp)
      }
    } catch {
      return idle(step)
    }
    compile(obj).then(() => {
      if (tmp) for (const b of tmp.children) b.dispose()
      idle(step)
    })
  }
  idle(step)
}
