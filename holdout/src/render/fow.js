// Fog of war, applied as a full-screen pass. Each pixel's world position is
// rebuilt from the depth buffer and looked up in the vision texture
// (world/vision.js):
//   visible  -> the scene as rendered
//   explored -> a cold, dim, desaturated memory of it
//   unseen   -> drifting dark fog
//   sensed   -> a cyan scan (a scout feeling through a wall)
// It runs first in the main effect pass, on linear HDR colour, so hidden
// fires don't bloom and the grade still applies on top.
import * as THREE from 'three'
import { Effect, EffectAttribute } from 'postprocessing'

const frag = /* glsl */ `
uniform sampler2D tFow;
uniform float uOn;
uniform vec2 uOrigin;
uniform vec2 uSize;
uniform mat4 uProjInv;
uniform mat4 uViewInv;
uniform float uTime;
uniform vec3 uFog;
uniform float uMem;
uniform float uNight;

float fowHash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
float fowNoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = fowHash(i);
  float b = fowHash(i + vec2(1.0, 0.0));
  float c = fowHash(i + vec2(0.0, 1.0));
  float d = fowHash(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void mainImage(const in vec4 inputColor, const in vec2 uv, const in float depth, out vec4 outputColor) {
  if (uOn < 0.5 || depth >= 0.99999) {
    outputColor = inputColor;
    return;
  }
  // creases and silhouettes from the depth buffer: remembered places keep
  // faint blueprint outlines, so the layout reads even in the dark
  float z0 = getViewZ(depth);
  float zA = getViewZ(readDepth(uv + vec2(texelSize.x, 0.0)));
  float zB = getViewZ(readDepth(uv - vec2(texelSize.x, 0.0)));
  float zC = getViewZ(readDepth(uv + vec2(0.0, texelSize.y)));
  float zD = getViewZ(readDepth(uv - vec2(0.0, texelSize.y)));
  float lap = abs(zA + zB + zC + zD - 4.0 * z0) / max(-z0, 1.0);
  float edge = smoothstep(0.012, 0.05, lap);
  vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
  vec4 vp = uProjInv * clip;
  vp /= vp.w;
  vec3 wp = (uViewInv * vp).xyz;
  vec2 fuv = (wp.xz - uOrigin) / uSize;
  vec4 f = vec4(0.0, 1.0, 0.0, 0.0);
  float inside = step(0.0, fuv.x) * step(0.0, fuv.y) * step(fuv.x, 1.0) * step(fuv.y, 1.0);
  if (inside > 0.5) f = texture2D(tFow, fuv);
  float vis = f.r;
  float mem = max(f.g, vis);
  float sen = f.b * (1.0 - vis);
  vec3 c = inputColor.rgb;
  float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
  // memory: what you saw, cold and dim; the world beyond the play area a little brighter
  float memK = mix(uMem * 1.35, uMem, inside);
  vec3 memc = mix(vec3(lum), c, 0.28) * vec3(0.7, 0.78, 0.9) * memK;
  // unseen: slow drifting smoke over near-black
  vec2 q = wp.xz * 0.22;
  float n = fowNoise(q + vec2(uTime * 0.035, uTime * 0.021)) * 0.62 + fowNoise(q * 2.7 - vec2(uTime * 0.05, -uTime * 0.03)) * 0.38;
  vec3 fogc = uFog * (0.3 + 1.5 * n * n);
  vec3 hid = mix(fogc, memc, mem);
  hid += edge * mem * vec3(0.022, 0.032, 0.045) * (1.0 + uNight * 1.5);
  hid += mem * vec3(0.004, 0.006, 0.009) * uNight;
  // sensed through a wall: a cyan scan with soft bands climbing the walls
  float band = 0.8 + 0.2 * sin(wp.y * 22.0 - uTime * 5.0);
  vec3 scan = vec3(0.16, 0.66, 0.92) * (0.05 + lum * 1.25 + 0.04 * uNight) * band;
  hid = mix(hid, scan, clamp(sen, 0.0, 1.0) * 0.92);
  // a faint warm rim where sight meets the dark
  float rim = smoothstep(0.05, 0.4, vis) * (1.0 - smoothstep(0.4, 0.85, vis)) * mem;
  vec3 col = mix(hid, c, vis) + rim * vec3(0.012, 0.009, 0.005);
  outputColor = vec4(col, inputColor.a);
}`

export class FowEffect extends Effect {
  // `u` holds shared uniform objects, so a rebuilt effect picks up the same state.
  constructor(u) {
    super('FowEffect', frag, {
      attributes: EffectAttribute.DEPTH,
      uniforms: new Map([
        ['tFow', u.tFow],
        ['uOn', u.uOn],
        ['uOrigin', u.uOrigin],
        ['uSize', u.uSize],
        ['uProjInv', u.uProjInv],
        ['uViewInv', u.uViewInv],
        ['uTime', u.uTime],
        ['uFog', u.uFog],
        ['uMem', u.uMem],
        ['uNight', u.uNight],
      ]),
    })
    this.u = u
    this.cam = null
  }
  set mainCamera(c) {
    this.cam = c
  }
  get mainCamera() {
    return this.cam
  }
  update(renderer, inputBuffer, dt) {
    const c = this.cam
    if (!c) return
    this.u.uProjInv.value.copy(c.projectionMatrixInverse)
    this.u.uViewInv.value.copy(c.matrixWorld)
    this.u.uTime.value += dt || 0
  }
}

export function fowUniforms() {
  return {
    tFow: new THREE.Uniform(null),
    uOn: new THREE.Uniform(0),
    uOrigin: new THREE.Uniform(new THREE.Vector2()),
    uSize: new THREE.Uniform(new THREE.Vector2(1, 1)),
    uProjInv: new THREE.Uniform(new THREE.Matrix4()),
    uViewInv: new THREE.Uniform(new THREE.Matrix4()),
    uTime: new THREE.Uniform(0),
    uFog: new THREE.Uniform(new THREE.Vector3(0.016, 0.019, 0.025)),
    uMem: new THREE.Uniform(0.48),
    uNight: new THREE.Uniform(0),
  }
}
