// Renderer and post-processing: ambient occlusion (N8AO), mipmap bloom,
// AgX tone mapping, colour grading, vignette, film grain, tilt-shift focus
// and SMAA, with quality presets tuned for desktop GPUs.
import * as THREE from 'three'
import {
  EffectComposer, RenderPass, EffectPass, Pass, BloomEffect, SMAAEffect, SMAAPreset, ToneMappingEffect, ToneMappingMode,
  VignetteEffect, NoiseEffect, TiltShiftEffect, BlendFunction, Effect, KernelSize,
} from 'postprocessing'
import { N8AOPostPass } from 'n8ao'
import { FowEffect } from './fow.js'

const exposureFrag = /* glsl */ `
uniform float exposure;
void mainImage(const in vec4 inputColor, const in vec2 uv, out vec4 outputColor) {
  outputColor = vec4(inputColor.rgb * exposure, inputColor.a);
}`
class ExposureEffect extends Effect {
  constructor(exposure = 1) {
    super('ExposureEffect', exposureFrag, { uniforms: new Map([['exposure', new THREE.Uniform(exposure)]]) })
  }
}

// Film-style grade after tone mapping: cool shadows, warm highlights,
// a touch of contrast and controllable saturation.
const gradeFrag = /* glsl */ `
uniform float saturation;
uniform float contrast;
uniform float warmth;
uniform float coolShadows;
uniform vec3 tint;
void mainImage(const in vec4 inputColor, const in vec2 uv, out vec4 outputColor) {
  vec3 c = inputColor.rgb;
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(c, c * vec3(0.92, 0.97, 1.08), (1.0 - smoothstep(0.0, 0.45, l)) * coolShadows);
  c = mix(c, c * vec3(1.06, 1.0, 0.9), smoothstep(0.35, 1.0, l) * warmth);
  c *= tint;
  c = mix(vec3(l), c, saturation);
  // contrast pivots around mid-grey in perceptual space so shadows keep detail
  c = pow(max(c, 0.0), vec3(1.0 / 2.2));
  c = (c - 0.45) * contrast + 0.45;
  c = pow(max(c, 0.0), vec3(2.2));
  outputColor = vec4(clamp(c, 0.0, 1.0), inputColor.a);
}`
export class GradeEffect extends Effect {
  constructor() {
    super('GradeEffect', gradeFrag, {
      uniforms: new Map([
        ['saturation', new THREE.Uniform(1.1)],
        ['contrast', new THREE.Uniform(1.14)],
        ['warmth', new THREE.Uniform(0.45)],
        ['coolShadows', new THREE.Uniform(0.5)],
        ['tint', new THREE.Uniform(new THREE.Vector3(1, 1, 1))],
      ]),
    })
  }
  set(k, v) {
    const u = this.uniforms.get(k)
    if (u.value?.isVector3) u.value.copy(v)
    else u.value = v
  }
}

// Draws the first-person arms and weapon (a VMLayer) over the world, with
// the depth cleared so they never sink into a wall. It sits before the
// effect pass, so bloom, tone mapping and the grade treat them like
// everything else.
class OverlayPass extends Pass {
  constructor(pipe) {
    super('OverlayPass')
    this.pipe = pipe
    this.needsSwap = false
  }
  render(renderer, inputBuffer) {
    const o = this.pipe.overlay
    if (!o || !o.visible) return
    renderer.setRenderTarget(this.renderToScreen ? null : inputBuffer)
    renderer.clearDepth()
    const auto = renderer.shadowMap.autoUpdate
    renderer.shadowMap.autoUpdate = false
    renderer.render(o.scene, o.camera)
    renderer.shadowMap.autoUpdate = auto
  }
}

export const QUALITY = {
  low: { label: 'Low', dpr: 1, shadow: 1024, ao: null, bloom: false, smaa: SMAAPreset.LOW, tilt: false, grain: false },
  medium: { label: 'Medium', dpr: 1, shadow: 1024, ao: 'Low', aoHalf: true, bloom: true, smaa: SMAAPreset.MEDIUM, tilt: false, grain: true },
  high: { label: 'High', dpr: 1.5, shadow: 1536, ao: 'Medium', aoHalf: true, bloom: true, smaa: SMAAPreset.HIGH, tilt: true, grain: true },
  ultra: { label: 'Ultra', dpr: 2, shadow: 4096, ao: 'High', aoHalf: false, bloom: true, smaa: SMAAPreset.ULTRA, tilt: true, grain: true },
}

export class Pipeline {
  constructor(canvas) {
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false, stencil: false, powerPreference: 'high-performance' })
    const r = this.renderer
    r.outputColorSpace = THREE.SRGBColorSpace
    r.toneMapping = THREE.NoToneMapping
    r.shadowMap.enabled = true
    r.shadowMap.type = THREE.PCFShadowMap
    // checking every new shader for errors waits on the graphics card each
    // time, a visible hitch; ?shaders turns the check back on for debugging
    r.debug.checkShaderErrors = /[?&]shaders\b/.test(location.search)
    this.quality = 'high'
    this.tiltShift = true
    this.composers = new Map()
    // the first-person arms and weapon, when shown (see vmlayer.js)
    this.overlay = null
    // first person: no tilt-shift (it blurs what is right in front of you)
    this.firstPerson = false
    this.exposure = 1
    this.size = { w: 1, h: 1 }
    // automatic resolution: a slow machine trades a little sharpness for a
    // steady frame rate, and gets it back when frames are quick again
    this.dyn = { on: true, scale: 1, ema: 1 / 60, t: 0 }
  }
  setQuality(q) {
    this.quality = QUALITY[q] ? q : 'high'
    const Q = QUALITY[this.quality]
    this.renderer.setPixelRatio(this.ratio())
    this.renderer.shadowMap.enabled = true
    for (const c of this.composers.values()) c.composer.dispose()
    this.composers.clear()
    this.resize(this.size.w, this.size.h)
  }
  get Q() {
    return QUALITY[this.quality]
  }
  ratio() {
    return Math.min(window.devicePixelRatio || 1, this.Q.dpr) * (this.dyn.on ? this.dyn.scale : 1)
  }
  setAutoRes(on) {
    if (this.dyn.on === on) return
    this.dyn.on = on
    this.dyn.scale = 1
    this.renderer.setPixelRatio(this.ratio())
    this.resize(this.size.w, this.size.h)
  }
  // called once per drawn frame
  adapt(dt) {
    const d = this.dyn
    // hitches (a loading card, a tab switch) say nothing about the steady rate
    if (!d.on || !(dt > 0) || dt > 0.25) return
    d.ema += (dt - d.ema) * 0.05
    d.t += dt
    if (d.t < 2.5) return
    d.t = 0
    let k = d.scale
    if (d.ema > 1 / 42 && k > 0.6) k = Math.max(0.6, k - 0.1)
    else if (d.ema < 1 / 56 && k < 1) k = Math.min(1, k + 0.1)
    if (k === d.scale) return
    d.scale = Math.round(k * 10) / 10
    this.renderer.setPixelRatio(this.ratio())
    this.resize(this.size.w, this.size.h)
  }
  resize(w, h) {
    this.size = { w, h }
    this.renderer.setSize(w, h, false)
    for (const c of this.composers.values()) {
      c.composer.setSize(w, h)
      c.ao?.setSize?.(w, h)
    }
  }
  composerFor(scene, camera) {
    let c = this.composers.get(scene)
    if (c) return c
    const Q = this.Q
    const composer = new EffectComposer(this.renderer, { frameBufferType: THREE.HalfFloatType, multisampling: 0 })
    composer.addPass(new RenderPass(scene, camera))
    let ao = null
    if (Q.ao) {
      ao = new N8AOPostPass(scene, camera, this.size.w, this.size.h)
      ao.setQualityMode(Q.ao)
      ao.configuration.aoRadius = 1.8
      ao.configuration.distanceFalloff = 1.0
      ao.configuration.intensity = 3.2
      ao.configuration.color = new THREE.Color('#1c140c')
      ao.configuration.halfRes = !!Q.aoHalf
      ao.configuration.gammaCorrection = false
      // left to itself N8AO spots any transparent material (glass, smoke,
      // roofs fading out) and draws the whole scene twice more every frame,
      // shadow maps and all, to keep occlusion off it: a third of the frame
      // for a difference you can't see from a camp camera
      ao.autoDetectTransparency = false
      ao.configuration.transparencyAware = false
      composer.addPass(ao)
    }
    // fog of war for scenes that ask for it (supply runs): its own pass on HDR
    // colour, so bloom and the grade only ever see what the squad can see
    let fow = null
    if (scene.userData.fowUniforms) {
      fow = new FowEffect(scene.userData.fowUniforms)
      fow.mainCamera = camera
      composer.addPass(new EffectPass(camera, fow))
    }
    composer.addPass(new OverlayPass(this))
    const exposure = new ExposureEffect(this.exposure)
    const effects = [exposure]
    let bloom = null
    if (Q.bloom) {
      bloom = new BloomEffect({ mipmapBlur: true, luminanceThreshold: 1.6, luminanceSmoothing: 0.4, intensity: 0.9, radius: 0.72, levels: 7 })
      effects.push(bloom)
    }
    const tone = new ToneMappingEffect({ mode: ToneMappingMode.ACES_FILMIC })
    const grade = new GradeEffect()
    const vignette = new VignetteEffect({ offset: 0.3, darkness: 0.55 })
    effects.push(tone, grade, vignette)
    if (Q.grain) {
      const noise = new NoiseEffect({ premultiply: true, blendFunction: BlendFunction.SCREEN })
      noise.blendMode.opacity.value = 0.035
      effects.push(noise)
    }
    composer.addPass(new EffectPass(camera, ...effects))
    let tilt = null
    let tiltPass = null
    if (Q.tilt && this.tiltShift) {
      tilt = new TiltShiftEffect({ offset: 0.0, rotation: 0, focusArea: 0.9, feather: 0.42, kernelSize: KernelSize.SMALL, resolutionScale: 0.5 })
      // kept here, not on the effect: a pass held by its own effect would
      // make dispose() chase itself round in circles
      tiltPass = new EffectPass(camera, tilt)
      composer.addPass(tiltPass)
    }
    composer.addPass(new EffectPass(camera, new SMAAEffect({ preset: Q.smaa })))
    composer.setSize(this.size.w, this.size.h)
    c = { composer, ao, bloom, grade, exposure, tilt, tiltPass, tone, fow }
    this.composers.set(scene, c)
    return c
  }
  render(scene, camera, dt) {
    const c = this.composerFor(scene, camera)
    if (c.tiltPass) c.tiltPass.enabled = !this.firstPerson
    // up close a sunlit white wall fills the view: only lights should glow
    if (c.bloom) {
      const lm = c.bloom.luminanceMaterial
      const t = this.firstPerson ? 2.6 : 1.6
      if (lm.threshold !== t) lm.threshold = t
    }
    c.exposure.uniforms.get('exposure').value = this.exposure * (this.exposureMul ?? 1)
    c.composer.render(dt)
  }
  // Called by scenes to tune the look per time of day.
  grade(scene, { saturation, contrast, warmth, tint, bloom }) {
    const c = this.composers.get(scene)
    if (!c) return
    if (saturation != null) c.grade.set('saturation', saturation)
    if (contrast != null) c.grade.set('contrast', contrast)
    if (warmth != null) c.grade.set('warmth', warmth)
    if (tint) c.grade.set('tint', tint)
    if (bloom != null && c.bloom) c.bloom.intensity = bloom
  }
  forget(scene) {
    const c = this.composers.get(scene)
    if (c) {
      c.composer.dispose()
      this.composers.delete(scene)
    }
  }
}
