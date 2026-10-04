// Time of day and weather: a physical sky drives image-based lighting, the
// sun (or moon) casts soft shadows, fog colour follows the sky.
import * as THREE from 'three'
import { Sky } from 'three/addons/objects/Sky.js'
import { clamp, lerp } from '../core/util.js'
import { view } from './view.js'

// The sky you see in first person (the top-down camera never sees the sky,
// just a fog-coloured ground fading out). It meets the fog exactly at the
// horizon, so the far ground melts into it, and deepens overhead; the sun
// (or the moon) glows through drifting clouds, and the stars come out at
// night. Drawn after everything solid, only where nothing else is.
const SKY_VERT = /* glsl */ `
varying vec3 vDir;
void main() {
  vDir = position;
  vec4 p = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  gl_Position = p.xyww;
}`
const SKY_FRAG = /* glsl */ `
uniform vec3 uHorizon;
uniform vec3 uZenith;
uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform float uNight;
uniform float uCloud;
uniform float uTime;
varying vec3 vDir;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p) {
  float v = 0.0;
  float a = 0.5;
  for (int i = 0; i < 5; i++) {
    v += vnoise(p) * a;
    p = p * 2.03 + 7.1;
    a *= 0.5;
  }
  return v;
}
void main() {
  vec3 d = normalize(vDir);
  float h = d.y;
  float up = clamp(h, 0.0, 1.0);
  vec3 col = mix(uHorizon, uZenith, pow(up, 0.5));
  // a band of haze where the sky meets the ground
  col = mix(col, uHorizon, exp(-up * 14.0) * 0.6);
  float sd = max(dot(d, uSunDir), 0.0);
  float day = 1.0 - uNight;
  // glow round the sun, a disc; at night a pale moon
  col += uSunCol * (pow(sd, 6.0) * 0.18 + pow(sd, 48.0) * 0.5) * day * (1.0 - uCloud * 0.6);
  col += uSunCol * smoothstep(0.99945, 0.9997, sd) * 6.0 * day * (1.0 - uCloud * 0.85);
  col += vec3(0.75, 0.8, 0.9) * smoothstep(0.99955, 0.9998, sd) * 1.6 * uNight * (1.0 - uCloud * 0.7);
  col += vec3(0.25, 0.3, 0.42) * pow(sd, 24.0) * 0.25 * uNight;
  // stars
  if (uNight > 0.01 && h > 0.02) {
    vec2 g = vec2(atan(d.z, d.x) * 120.0, h * 240.0);
    float s = hash(floor(g));
    float tw = 0.7 + 0.3 * sin(uTime * 2.0 + s * 40.0);
    col += vec3(0.9, 0.92, 1.0) * step(0.9975, s) * tw * uNight * smoothstep(0.02, 0.3, h) * (1.0 - uCloud) * 1.4;
  }
  // clouds, laid on a high flat sheet and drifting
  if (h > 0.0) {
    vec2 uv = d.xz / (h + 0.12) * 0.55 + vec2(uTime * 0.006, uTime * 0.002);
    float n = fbm(uv * 1.3);
    float cov = smoothstep(0.62 - uCloud * 0.4, 0.92 - uCloud * 0.3, n) * smoothstep(0.0, 0.18, h);
    vec3 lit = mix(uHorizon * 1.12 + uSunCol * 0.08 * day, uHorizon * 0.55, smoothstep(0.5, 1.0, n) * 0.6);
    lit = mix(lit, uHorizon * 0.35, uNight * 0.6);
    lit += uSunCol * pow(sd, 10.0) * 0.25 * day;
    col = mix(col, lit, cov * 0.9);
  }
  gl_FragColor = vec4(col, 1.0);
}`
export class SkyDome {
  constructor() {
    this.uniforms = {
      uHorizon: { value: new THREE.Color() },
      uZenith: { value: new THREE.Color() },
      uSunDir: { value: new THREE.Vector3(0, 1, 0) },
      uSunCol: { value: new THREE.Color() },
      uNight: { value: 0 },
      uCloud: { value: 0 },
      uTime: { value: 0 },
    }
    const m = new THREE.ShaderMaterial({ uniforms: this.uniforms, vertexShader: SKY_VERT, fragmentShader: SKY_FRAG, side: THREE.BackSide, depthWrite: false, fog: false })
    this.mesh = new THREE.Mesh(new THREE.SphereGeometry(300, 32, 16), m)
    this.mesh.frustumCulled = false
    // after everything solid, so it only fills what is left
    this.mesh.renderOrder = 100000
    this.mesh.visible = false
  }
}

// hour → [fog/background, sunColour, sunIntensity, hemiSky, hemiGround, hemiInt, envInt, exposure]
const KEYS = [
  [0, '#141e34', '#9cb0e0', 3.4, '#5a70a8', '#20232c', 2.2, 0.2, 1.15],
  [4.6, '#1a253c', '#a0b2dc', 3.3, '#5e74aa', '#22252e', 2.1, 0.22, 1.15],
  [5.8, '#c48a68', '#ffae74', 3.6, '#b8a08e', '#4a3c2e', 0.9, 0.4, 0.76],
  [7.2, '#a9b8c0', '#fff0da', 6.2, '#d0dae0', '#5c5242', 0.55, 0.62, 0.56],
  [13, '#a8b8c0', '#fff2e0', 7.0, '#d8dcda', '#605646', 0.55, 0.58, 0.56],
  [17.4, '#bba78e', '#ffd7a2', 6.0, '#d0c0aa', '#56493a', 0.55, 0.62, 0.58],
  [19.4, '#b06648', '#ff8a4e', 3.6, '#9c7c78', '#382c24', 0.6, 0.4, 0.7],
  [20.8, '#2c2c48', '#98a0d4', 3.0, '#50578c', '#1e1e28', 1.6, 0.2, 1.06],
  [24, '#141e34', '#9cb0e0', 3.4, '#5a70a8', '#20232c', 2.2, 0.2, 1.15],
]
const _a = new THREE.Color()
const _b = new THREE.Color()
function keyAt(hour) {
  let i = 0
  while (i < KEYS.length - 2 && KEYS[i + 1][0] <= hour) i++
  const A = KEYS[i]
  const B = KEYS[i + 1]
  const t = clamp((hour - A[0]) / (B[0] - A[0]), 0, 1)
  const c = (j) => _a.set(A[j]).lerp(_b.set(B[j]), t).clone()
  return { fog: c(1), sun: c(2), sunI: lerp(A[3], B[3], t), hs: c(4), hg: c(5), hi: lerp(A[6], B[6], t), env: lerp(A[7], B[7], t), exp: lerp(A[8], B[8], t) }
}
export const isNight = (h) => h >= 20.5 || h < 5.5
export const nightFactor = (h) => {
  if (h >= 21 || h < 4.6) return 1
  if (h >= 19 && h < 21) return (h - 19) / 2
  if (h >= 4.6 && h < 6.6) return 1 - (h - 4.6) / 2
  return 0
}

export const WEATHER = {
  clear: { name: 'Clear', sun: 1, fog: 1, sat: 1.08, cloud: 0 },
  hazy: { name: 'Hazy', sun: 0.8, fog: 0.75, sat: 0.98, cloud: 0.3 },
  overcast: { name: 'Overcast', sun: 0.45, fog: 0.7, sat: 0.88, cloud: 0.8 },
  rain: { name: 'Rain', sun: 0.3, fog: 0.55, sat: 0.82, cloud: 1 },
  fog: { name: 'Fog', sun: 0.4, fog: 0.32, sat: 0.8, cloud: 0.6 },
  snow: { name: 'Snow', sun: 0.5, fog: 0.6, sat: 0.74, cloud: 0.85 },
}

export class Atmosphere {
  constructor(scene, renderer, { shadowSize = 2048, shadowRange = [16, 60], sunDist = 70, shadowFar = 160 } = {}) {
    this.scene = scene
    this.renderer = renderer
    this.shadowRange = shadowRange
    this.sunDist = sunDist
    this.hemi = new THREE.HemisphereLight('#dce4e8', '#5e5444', 0.9)
    scene.add(this.hemi)
    this.sun = new THREE.DirectionalLight('#fff6e8', 3)
    this.sun.castShadow = true
    this.setShadowSize(shadowSize)
    const s = this.sun.shadow
    s.bias = -0.0004
    s.normalBias = 0.035
    s.radius = 2.5
    s.camera.near = 1
    s.camera.far = shadowFar
    scene.add(this.sun, this.sun.target)
    scene.fog = new THREE.Fog('#b4c3cc', 40, 120)
    scene.background = new THREE.Color('#b4c3cc')
    // physical sky only feeds the environment map
    this.sky = new Sky()
    this.sky.scale.setScalar(1000)
    const u = this.sky.material.uniforms
    u.turbidity.value = 6
    u.rayleigh.value = 1.6
    u.mieCoefficient.value = 0.006
    u.mieDirectionalG.value = 0.82
    // our directional light is the sun; a baked-in disc would flood rough surfaces
    if (u.showSunDisc) u.showSunDisc.value = 0
    this.skyScene = new THREE.Scene()
    this.skyScene.add(this.sky)
    this.pmrem = new THREE.PMREMGenerator(renderer)
    this.envRT = null
    this.lastEnvSun = new THREE.Vector3(9, 9, 9)
    this.weather = 'clear'
    this.weatherMix = { ...WEATHER.clear }
    this.dirV = new THREE.Vector3()
    // first person: a real sky, fog by eye-level distances, shadows ahead
    this.fp = false
    this.fpFog = [40, 200]
    this.dome = new SkyDome()
    scene.add(this.dome.mesh)
  }
  setShadowSize(n) {
    if (this.sun.shadow.mapSize.x === n) return
    this.sun.shadow.mapSize.set(n, n)
    this.sun.shadow.map?.dispose()
    this.sun.shadow.map = null
  }
  setWeather(w) {
    this.weather = WEATHER[w] ? w : 'clear'
  }
  // Called every frame with the in-game hour and the camera focus.
  update(hour, focus, dist, dt = 0.016) {
    const k = keyAt(hour)
    // ease weather parameters
    const W = WEATHER[this.weather]
    const e = 1 - Math.exp(-dt * 0.6)
    for (const key of ['sun', 'fog', 'sat', 'cloud']) this.weatherMix[key] += (W[key] - this.weatherMix[key]) * e
    const wm = this.weatherMix
    const grey = new THREE.Color('#8a9096')
    const fogC = k.fog.clone().lerp(grey.clone().multiplyScalar(isNight(hour) ? 0.25 : 1), wm.cloud * 0.55)
    this.scene.background.copy(fogC)
    this.scene.fog.color.copy(fogC)
    if (this.fp) {
      this.scene.fog.near = this.fpFog[0] * wm.fog
      this.scene.fog.far = this.fpFog[1] * (0.5 + wm.fog * 0.5)
    } else {
      this.scene.fog.near = Math.max(30, dist * 1.7) * wm.fog
      this.scene.fog.far = Math.max(90, dist * 5) * (0.5 + wm.fog * 0.5)
    }
    this.hemi.color.copy(k.hs)
    this.hemi.groundColor.copy(k.hg)
    this.hemi.intensity = k.hi * (1 + wm.cloud * 0.25)
    this.sun.color.copy(k.sun)
    this.sun.intensity = k.sunI * wm.sun
    // sun arcs east→west by day; the moon takes over at night
    const day = hour >= 5.4 && hour <= 20.6
    const t = day ? (hour - 5.4) / 15.2 : ((hour + 24 - 20.6) % 24) / 8.8
    const az = lerp(-1.25, 1.95, t)
    const el = day ? 0.22 + Math.sin(t * Math.PI) * 0.85 : 0.75 + Math.sin(t * Math.PI) * 0.25
    const dir = this.dirV.set(Math.cos(az) * Math.cos(el), Math.sin(el), Math.sin(az) * Math.cos(el))
    // first person: the shadow box sits ahead of the eye, where the shadows
    // are looked at, and is a little smaller for crisper ones
    if (this.fp) {
      const cam = view.camera
      const f = new THREE.Vector3(0, 0, -1).applyQuaternion(cam.quaternion).setY(0)
      if (f.lengthSq() < 1e-4) f.set(0, 0, -1)
      f.normalize()
      focus = { x: cam.position.x + f.x * 15, z: cam.position.z + f.z * 15 }
      dist = 25
    }
    const ext = clamp(dist * 0.95, this.shadowRange[0], this.shadowRange[1])
    const sc = this.sun.shadow.camera
    if (sc.right !== ext) {
      sc.left = -ext
      sc.right = ext
      sc.top = ext
      sc.bottom = -ext
      sc.updateProjectionMatrix()
    }
    const texel = (ext * 2) / this.sun.shadow.mapSize.x
    const fx = Math.round(focus.x / texel) * texel
    const fz = Math.round(focus.z / texel) * texel
    this.sun.target.position.set(fx, 0, fz)
    const sd = this.sunDist
    this.sun.position.set(fx + dir.x * sd, dir.y * sd, fz + dir.z * sd)
    // environment lighting from the physical sky (re-baked when the sun moves)
    const sunForSky = day ? dir : new THREE.Vector3(dir.x, -0.15, dir.z)
    if (sunForSky.distanceTo(this.lastEnvSun) > 0.05 || !this.envRT) {
      this.lastEnvSun.copy(sunForSky)
      this.sky.material.uniforms.sunPosition.value.copy(sunForSky)
      this.sky.material.uniforms.turbidity.value = 5 + wm.cloud * 8
      const rt = this.pmrem.fromScene(this.skyScene, 0, 1, 2000)
      this.envRT?.dispose()
      this.envRT = rt
      this.scene.environment = rt.texture
    }
    this.scene.environmentIntensity = k.env * (0.75 + wm.sun * 0.25)
    const D = this.dome
    D.mesh.visible = this.fp
    if (this.fp) {
      const U = D.uniforms
      const nf = nightFactor(hour)
      U.uHorizon.value.copy(fogC)
      // overhead: deeper and bluer by day, darker still by night
      U.uZenith.value.copy(fogC).multiply(_a.setRGB(0.52, 0.66, 1.0)).lerp(_b.copy(fogC).multiplyScalar(0.45), nf * 0.7)
      U.uZenith.value.lerp(fogC, wm.cloud * 0.6)
      U.uSunDir.value.copy(dir)
      U.uSunCol.value.copy(k.sun).multiplyScalar(clamp(k.sunI / 7, 0.3, 1))
      U.uNight.value = nf
      U.uCloud.value = clamp(0.18 + wm.cloud * 0.8, 0, 1)
      U.uTime.value = performance.now() / 1000
      D.mesh.position.copy(view.camera.position)
    }
    return { exposure: k.exp, saturation: wm.sat, night: nightFactor(hour) }
  }
  dispose() {
    this.envRT?.dispose()
    this.pmrem.dispose()
  }
}
