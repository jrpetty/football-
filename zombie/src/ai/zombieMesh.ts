// Procedural zombie body with per-vertex bone ids, animated entirely on the GPU (one draw call for the whole horde).
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';

// bone ids
const HIP = 0, SPINE = 1, HEAD = 2, UAL = 3, FAL = 4, UAR = 5, FAR = 6, THL = 7, SHL = 8, THR = 9, SHR = 10;
// regions: 0 skin, 1 shirt, 2 pants, 3 shoes, 4 hair, 5 eye
function part(g: THREE.BufferGeometry, bone: number, region: number, x: number, y: number, z: number, sx = 1, sy = 1, sz = 1, rx = 0, rz = 0) {
  const geo = g.index ? g.toNonIndexed() : g;
  geo.scale(sx, sy, sz);
  if (rx) geo.rotateX(rx); if (rz) geo.rotateZ(rz);
  geo.translate(x, y, z);
  const n = geo.attributes.position.count;
  geo.setAttribute('aBone', new THREE.Float32BufferAttribute(new Float32Array(n).fill(bone), 1));
  geo.setAttribute('aRegion', new THREE.Float32BufferAttribute(new Float32Array(n).fill(region), 1));
  geo.deleteAttribute('uv');
  return geo;
}
const cyl = (rt: number, rb: number, h: number, seg = 8) => new THREE.CylinderGeometry(rt, rb, h, seg, 2);
const sph = (r: number, w = 10, h = 8) => new THREE.SphereGeometry(r, w, h);
const box = (w: number, h: number, d: number) => new THREE.BoxGeometry(w, h, d, 1, 1, 1);

export function buildZombieGeometry(): THREE.BufferGeometry {
  const P: THREE.BufferGeometry[] = [];
  // pelvis / hips
  P.push(part(cyl(0.16, 0.15, 0.2, 10), HIP, 2, 0, 0.93, 0, 1.15, 1, 0.8));
  // torso (shirt) - tapered, slightly hunched
  P.push(part(cyl(0.2, 0.16, 0.36, 10), SPINE, 1, 0, 1.2, 0, 1.15, 1, 0.75));
  P.push(part(cyl(0.21, 0.2, 0.14, 10), SPINE, 1, 0, 1.43, 0, 1.18, 1, 0.78));
  P.push(part(sph(0.1, 8, 6), SPINE, 1, 0.19, 1.46, 0, 1, 0.8, 0.9)); // shoulders
  P.push(part(sph(0.1, 8, 6), SPINE, 1, -0.19, 1.46, 0, 1, 0.8, 0.9));
  // exposed ribs / torn shirt patch
  P.push(part(box(0.16, 0.12, 0.04), SPINE, 0, 0.05, 1.18, 0.135));
  // neck & head
  P.push(part(cyl(0.05, 0.06, 0.1, 8), HEAD, 0, 0, 1.54, 0));
  P.push(part(sph(0.115, 12, 10), HEAD, 0, 0, 1.67, 0.01, 0.92, 1.08, 1.0));
  P.push(part(box(0.13, 0.06, 0.1), HEAD, 0, 0, 1.585, 0.04)); // jaw hanging
  P.push(part(sph(0.118, 10, 6), HEAD, 4, 0, 1.71, -0.015, 0.95, 0.75, 1.0)); // hair cap
  P.push(part(sph(0.022, 6, 4), HEAD, 5, 0.04, 1.685, 0.1));
  P.push(part(sph(0.022, 6, 4), HEAD, 5, -0.04, 1.685, 0.1));
  P.push(part(box(0.03, 0.04, 0.03), HEAD, 0, 0, 1.66, 0.115)); // nose
  // arms
  for (const s of [1, -1]) {
    const ua = s > 0 ? UAL : UAR, fa = s > 0 ? FAL : FAR;
    P.push(part(cyl(0.055, 0.048, 0.3, 8), ua, 1, s * 0.22, 1.31, 0));
    P.push(part(cyl(0.046, 0.038, 0.27, 8), fa, 0, s * 0.22, 1.03, 0));
    P.push(part(box(0.07, 0.11, 0.035), fa, 0, s * 0.22, 0.84, 0.01));
    P.push(part(box(0.02, 0.07, 0.02), fa, 0, s * 0.22 + s * 0.03, 0.77, 0.02)); // fingers
    P.push(part(box(0.045, 0.07, 0.02), fa, 0, s * 0.215, 0.77, 0.005));
  }
  // legs
  for (const s of [1, -1]) {
    const th = s > 0 ? THL : THR, sh = s > 0 ? SHL : SHR;
    P.push(part(cyl(0.08, 0.06, 0.44, 8), th, 2, s * 0.095, 0.7, 0));
    P.push(part(cyl(0.06, 0.045, 0.42, 8), sh, 2, s * 0.095, 0.28, 0));
    P.push(part(box(0.1, 0.07, 0.24), sh, 3, s * 0.095, 0.035, 0.05));
  }
  const g = mergeGeometries(P, false)!;
  g.computeBoundingSphere();
  g.boundingSphere!.radius = 2.2;
  return g;
}

export const ZOMBIE_VERTEX_DECL = `
attribute float aBone; attribute float aRegion;
attribute vec4 aAnim;  // phase, move(0 idle,1 walk,2 run), attack, death
attribute vec4 aAnim2; // hit, armsUp, limp, deathDir
attribute vec4 aVar;   // seed, scale, lean, type
uniform float uZTime;
varying vec3 vZCol; varying vec3 vZPos; varying float vZRegion;
vec3 zrx(vec3 v, float a){ float c=cos(a), s=sin(a); return vec3(v.x, c*v.y - s*v.z, s*v.y + c*v.z); }
vec3 zrz(vec3 v, float a){ float c=cos(a), s=sin(a); return vec3(c*v.x - s*v.y, s*v.x + c*v.y, v.z); }
vec3 zry(vec3 v, float a){ float c=cos(a), s=sin(a); return vec3(c*v.x + s*v.z, v.y, -s*v.x + c*v.z); }
float zh(float n){ return fract(sin(n)*43758.5453); }
`;

// shared vertex transform used by colour and depth materials
export const ZOMBIE_VERTEX_BODY = `
{
  int b = int(aBone + 0.5);
  float ph = aAnim.x; float mv = aAnim.y; float atk = aAnim.z; float death = aAnim.w;
  float hit = aAnim2.x; float arms = aAnim2.y; float limp = aAnim2.z; float ddir = aAnim2.w;
  float seed = aVar.x; float lean = aVar.z;
  float walk = clamp(mv, 0.0, 1.0); float run = clamp(mv - 1.0, 0.0, 1.0);
  float idle = 1.0 - walk;
  float t = uZTime + seed * 10.0;
  float stride = mix(0.45, 0.85, run);
  float sL = sin(ph), sR = sin(ph + 3.14159);
  vec3 p = transformed; vec3 n = objectNormal;
  // ---- head
  if (b == 2) {
    vec3 pv = vec3(0.0, 1.53, 0.0);
    float tilt = (zh(seed*3.1) - 0.5) * 0.9 + sin(t*0.7)*0.08*idle;
    float nod = -0.25 * hit + sin(t*1.3)*0.06 + 0.15 * run - atk*0.2;
    p = zrx(zrz(p - pv, tilt), nod) + pv; n = zrx(zrz(n, tilt), nod);
  }
  // ---- forearms
  if (b == 4 || b == 6) {
    float s = b == 4 ? 1.0 : -1.0;
    vec3 pv = vec3(s*0.22, 1.17, 0.0);
    float bend = -0.35 - 0.4 * atk - 0.6 * run * (b == 4 ? max(0.0, sL) : max(0.0, sR)) - sin(t*2.0 + s)*0.05;
    p = zrx(p - pv, bend) + pv; n = zrx(n, bend);
  }
  // ---- upper arms (zombie reach)
  if (b >= 3 && b <= 6) {
    float s = (b == 3 || b == 4) ? 1.0 : -1.0;
    vec3 pv = vec3(s*0.22, 1.45, 0.0);
    float reach = mix(-0.25, -1.35, arms);
    float swing = (b <= 4 ? sR : sL) * mix(0.25, 0.9, run) * walk * (1.0 - arms*0.6);
    float a = reach + swing - atk * 1.1 * (0.6 + 0.4*sin(t*14.0 + s)) + sin(t*1.7 + s*2.0)*0.05;
    float splay = s * (0.08 + 0.1 * zh(seed + s));
    p = zrz(zrx(p - pv, a), splay) + pv; n = zrz(zrx(n, a), splay);
  }
  // ---- spine (hunch, lean, flinch, attack lunge)
  if (b >= 1 && b <= 6) {
    vec3 pv = vec3(0.0, 1.02, 0.0);
    float hunch = 0.22 + lean * 0.25 + run * 0.25 + atk * 0.35 - hit * 0.5;
    float twist = sin(ph) * 0.12 * walk;
    float side = (lean - 0.5) * 0.25 + sin(t*0.9)*0.04;
    p = zry(zrz(zrx(p - pv, hunch), side), twist) + pv; n = zry(zrz(zrx(n, hunch), side), twist);
  }
  // ---- shins
  if (b == 8 || b == 10) {
    float s = b == 8 ? sL : sR;
    vec3 pv = vec3(b == 8 ? 0.095 : -0.095, 0.5, 0.0);
    float k = max(0.0, -cos(ph + (b == 8 ? 0.0 : 3.14159))) * mix(0.6, 1.3, run) * walk + 0.05;
    if (b == 10) k *= (1.0 - limp*0.7);
    p = zrx(p - pv, k) + pv; n = zrx(n, k);
  }
  // ---- thighs
  if (b >= 7 && b <= 10) {
    float s = b <= 8 ? sL : sR;
    vec3 pv = vec3(b <= 8 ? 0.095 : -0.095, 0.92, 0.0);
    float a = -s * stride * walk;
    if (b >= 9) a *= (1.0 - limp*0.6);
    a -= 0.12 * run;
    p = zrx(p - pv, a) + pv; n = zrx(n, a);
  }
  // body bob and sway
  float bob = abs(sin(ph)) * mix(0.03, 0.07, run) * walk;
  p.y += bob - 0.02 * walk;
  p.x += sin(ph * 0.5) * 0.04 * walk * (1.0 + limp);
  // death fall: rotate about the feet
  if (death > 0.0) {
    float fall = death * 1.5708 * ddir;
    p = zrx(p, -fall); n = zrx(n, -fall);
    p.y += death * 0.12;
  }
  p *= aVar.y;
  transformed = p; objectNormal = n;
}
`;

export function makeZombieMaterials(timeUniform: { value: number }) {
  const mat = new THREE.MeshStandardMaterial({ roughness: 0.82, metalness: 0 });
  mat.onBeforeCompile = (sh) => {
    sh.uniforms.uZTime = timeUniform;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + ZOMBIE_VERTEX_DECL)
      .replace('#include <beginnormal_vertex>', '#include <beginnormal_vertex>\nvec3 transformed = vec3(position);\n' + ZOMBIE_VERTEX_BODY)
      .replace('#include <begin_vertex>', `
      // colour by region & instance seed
      float sd = aVar.x;
      vec3 skin = mix(vec3(0.42,0.44,0.36), vec3(0.55,0.5,0.45), zh(sd*7.0)) * mix(0.75, 1.0, zh(sd*2.0));
      vec3 shirtPal[6]; shirtPal[0]=vec3(0.32,0.12,0.1); shirtPal[1]=vec3(0.15,0.2,0.3); shirtPal[2]=vec3(0.38,0.36,0.3); shirtPal[3]=vec3(0.12,0.12,0.12); shirtPal[4]=vec3(0.25,0.3,0.18); shirtPal[5]=vec3(0.45,0.42,0.38);
      vec3 pantsPal[4]; pantsPal[0]=vec3(0.13,0.16,0.24); pantsPal[1]=vec3(0.2,0.18,0.14); pantsPal[2]=vec3(0.09,0.09,0.09); pantsPal[3]=vec3(0.3,0.27,0.2);
      vec3 shirt = shirtPal[int(zh(sd*3.3)*5.99)];
      vec3 pants = pantsPal[int(zh(sd*5.7)*3.99)];
      if (aVar.w > 1.5) { shirt = vec3(0.2,0.22,0.16); pants = vec3(0.16,0.16,0.12); skin *= vec3(1.05,0.95,0.9); }
      if (aVar.w > 0.5 && aVar.w < 1.5) { skin *= vec3(0.9,0.85,0.85); }
      vec3 hair = mix(vec3(0.06,0.05,0.04), vec3(0.35,0.3,0.22), zh(sd*9.1));
      int rg = int(aRegion + 0.5);
      vZCol = rg == 0 ? skin : rg == 1 ? shirt : rg == 2 ? pants : rg == 3 ? vec3(0.08,0.07,0.06) : rg == 4 ? hair : vec3(0.9,0.85,0.6);
      vZRegion = aRegion; vZPos = position;
      `);
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', `#include <common>
varying vec3 vZCol; varying vec3 vZPos; varying float vZRegion;
float zn(vec3 p){ vec3 i = floor(p); vec3 f = fract(p); f = f*f*(3.0-2.0*f);
  float n = dot(i, vec3(1.0, 57.0, 113.0));
  return mix(mix(mix(fract(sin(n)*43758.5), fract(sin(n+1.0)*43758.5), f.x), mix(fract(sin(n+57.0)*43758.5), fract(sin(n+58.0)*43758.5), f.x), f.y),
             mix(mix(fract(sin(n+113.0)*43758.5), fract(sin(n+114.0)*43758.5), f.x), mix(fract(sin(n+170.0)*43758.5), fract(sin(n+171.0)*43758.5), f.x), f.y), f.z); }`)
      .replace('#include <color_fragment>', `#include <color_fragment>
      vec3 zc = vZCol;
      float grime = zn(vZPos * 14.0) * 0.6 + zn(vZPos * 41.0) * 0.4;
      zc *= mix(0.6, 1.05, grime);
      float blood = smoothstep(0.62, 0.8, zn(vZPos * 6.0 + 3.7) * 0.7 + zn(vZPos * 23.0) * 0.3);
      if (vZRegion > 4.5) { diffuseColor.rgb = zc; }
      else { diffuseColor.rgb = mix(zc, vec3(0.22, 0.02, 0.015), blood * 0.85); }
      `)
      .replace('#include <emissivemap_fragment>', `#include <emissivemap_fragment>
      if (vZRegion > 4.5) totalEmissiveRadiance += vec3(0.25, 0.22, 0.12);`);
  };
  mat.customProgramCacheKey = () => 'zombie-v3';

  const depth = new THREE.MeshDepthMaterial({ depthPacking: THREE.RGBADepthPacking });
  depth.onBeforeCompile = (sh) => {
    sh.uniforms.uZTime = timeUniform;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + ZOMBIE_VERTEX_DECL)
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvec3 objectNormal = vec3(0.0,1.0,0.0);\n' + ZOMBIE_VERTEX_BODY);
  };
  depth.customProgramCacheKey = () => 'zombie-depth-v3';
  return { mat, depth };
}
