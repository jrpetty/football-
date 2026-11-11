// One GPU-instanced body for every zombie and human. The skeleton is posed on the CPU (bodyRig.ts) and each
// body's bone transforms + look are written to one row of a float texture; the vertex shader skins from it.
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { LOWPOLY } from '../render/materials';
import { BONE as B, FEAT as F, PIV, NB, ROW, ROW_TEXELS, LOOK_AT, BodyAnim, BodyDef, poseBody } from './bodyRig';

// regions (what a surface is made of; the shader colours by region + per-body look)
const R = { skin: 0, shirt: 1, pants: 2, shoe: 3, hair: 4, eye: 5, sleeve: 6, leg: 7, bone: 8, gore: 9, hat: 10, metal: 11, wood: 12, dark: 13, red: 14, growth: 15, club: 16 } as const;

type G = THREE.BufferGeometry;
function tag(geo: G, bone: number, region: number, feat = 0): G {
  const n = geo.attributes.position.count;
  const a = new Float32Array(n * 4);
  for (let i = 0; i < n; i++) { a[i * 4] = bone; a[i * 4 + 1] = region; a[i * 4 + 2] = feat; }
  geo.setAttribute('aPart', new THREE.Float32BufferAttribute(a, 4));
  geo.deleteAttribute('uv');
  return geo;
}
const Q = () => LOWPOLY;
/** sphere / ellipsoid */
function ball(r: number, x: number, y: number, z: number, sx = 1, sy = 1, sz = 1, w = 10, h = 7, top = Math.PI): G {
  const g = Q() ? new THREE.SphereGeometry(r, Math.max(5, Math.round(w * 0.55)), Math.max(3, Math.round(h * 0.55)), 0, Math.PI * 2, 0, top) : new THREE.SphereGeometry(r, w, h, 0, Math.PI * 2, 0, top);
  g.scale(sx, sy, sz); g.translate(x, y, z); return g;
}
/** tapered tube from yb (radius rb) up to yt (radius rt); open ends are hidden inside joints */
function tube(rt: number, rb: number, yb: number, yt: number, x: number, z: number, seg = 8, sx = 1, sz = 1, open = true, hs = 1): G {
  const g = new THREE.CylinderGeometry(rt, rb, yt - yb, Q() ? Math.max(5, Math.round(seg * 0.6)) : seg, Q() ? 1 : hs, open);
  g.scale(sx, 1, sz); g.translate(x, (yb + yt) / 2, z); return g;
}
function box(w: number, h: number, d: number, x: number, y: number, z: number, rx = 0, ry = 0, rz = 0): G {
  const g = new THREE.BoxGeometry(w, h, d);
  if (rx) g.rotateX(rx); if (ry) g.rotateY(ry); if (rz) g.rotateZ(rz);
  g.translate(x, y, z); return g;
}
function warp(g: G, f: (v: THREE.Vector3) => void): G {
  const p = g.attributes.position as THREE.BufferAttribute; const v = new THREE.Vector3();
  for (let i = 0; i < p.count; i++) { v.fromBufferAttribute(p, i); f(v); p.setXYZ(i, v.x, v.y, v.z); }
  g.computeVertexNormals(); return g;
}

export function buildZombieGeometry(): THREE.BufferGeometry {
  const P: G[] = [];
  const add = (g: G, bone: number, region: number, feat = 0) => { P.push(tag(g, bone, region, feat)); };
  // ---- trunk
  add(ball(1, 0, 0.98, 0, 0.165, 0.135, 0.122, 12, 8), B.HIP, R.pants);
  add(tube(0.176, 0.152, 0.99, 1.33, 0, 0, 12, 1, 0.68, true, 2), B.SPINE, R.shirt);
  add(warp(ball(1, 0, 1.365, 0, 0.192, 0.155, 0.126, 12, 8), v => { if (v.y > 1.4) { const k = (v.y - 1.4) / 0.12; v.x *= 1 - k * 0.12; } if (v.z < 0) v.z *= 0.92; }), B.CHEST, R.shirt);
  // ---- neck, head, face
  add(tube(0.05, 0.056, 1.46, 1.61, 0, -0.008, 8), B.HEAD, R.skin);
  add(warp(ball(1, 0, 1.68, 0.01, 0.098, 0.118, 0.11, 14, 10), v => { if (v.y < 1.68) { const k = (1.68 - v.y) / 0.118; v.x *= 1 - k * 0.22; if (v.z > 0.01) v.z = 0.01 + (v.z - 0.01) * (1 - k * 0.1); } }), B.HEAD, R.skin);
  add(warp(box(0.108, 0.058, 0.108, 0, 1.592, 0.047), v => { if (v.z > 0.05) v.x *= 0.72; if (v.y < 1.58) { v.x *= 0.86; if (v.z > 0.05) v.z -= 0.012; } }), B.JAW, R.skin);
  add(box(0.066, 0.03, 0.02, 0, 1.607, 0.086), B.HEAD, R.gore);
  add(box(0.058, 0.012, 0.012, 0, 1.617, 0.093), B.HEAD, R.bone);
  add(box(0.052, 0.012, 0.012, 0, 1.622, 0.089), B.JAW, R.bone);
  add(warp(box(0.026, 0.046, 0.03, 0, 1.662, 0.118), v => { if (v.y > 1.662) { v.z -= 0.014; v.x *= 0.6; } }), B.HEAD, R.skin);
  add(box(0.112, 0.016, 0.022, 0, 1.704, 0.11), B.HEAD, R.skin);
  for (const s of [1, -1]) {
    add(ball(0.0165, s * 0.037, 1.686, 0.099, 1, 1, 1, 6, 4), B.HEAD, R.eye);
    add(ball(1, s * 0.098, 1.672, 0.0, 0.012, 0.03, 0.02, 5, 4), B.HEAD, R.skin);
  }
  // ---- hair & hats (features)
  add(ball(1, 0, 1.713, -0.008, 0.104, 0.098, 0.116, 12, 6, Math.PI * 0.64), B.HEAD, R.hair, F.hair);
  add(warp(ball(1, 0, 1.6, -0.068, 0.11, 0.165, 0.078, 9, 6), v => { if (v.z > -0.03 && Math.abs(v.x) < 0.07) v.z = -0.03; }), B.HEAD, R.hair, F.hairLong);
  add(ball(1, 0, 1.724, -0.004, 0.107, 0.1, 0.119, 12, 6, Math.PI * 0.58), B.HEAD, R.hat, F.beanie);
  add(tube(0.109, 0.109, 1.722, 1.756, 0, -0.004, 12, 1, 1.11), B.HEAD, R.hat, F.beanie);
  add(box(0.13, 0.012, 0.085, 0, 1.728, 0.15, 0.12), B.HEAD, R.hat, F.brim);
  add(ball(0.042, 0, 1.712, -0.128, 1, 1, 1, 6, 4), B.HEAD, R.hair, F.bun);
  add(ball(1, 0, 1.63, -0.122, 0.026, 0.06, 0.026, 5, 4), B.HEAD, R.hair, F.bun);
  add(ball(1, 0, 1.584, 0.052, 0.061, 0.052, 0.06, 8, 5), B.JAW, R.hair, F.beard);
  // ---- arms (left = +x)
  for (const s of [1, -1]) {
    const L = s > 0; const x = s * PIV[B.UAL * 3];
    const ua = L ? B.UAL : B.UAR, fa = L ? B.FAL : B.FAR, hn = L ? B.HNL : B.HNR, fore = L ? F.foreL : F.foreR, stump = L ? F.stumpL : F.stumpR;
    add(ball(0.063, x, 1.44, 0, 1, 1, 1, 8, 6), ua, R.sleeve);
    add(tube(0.052, 0.044, 1.16, 1.44, x, 0, 8), ua, R.sleeve);
    add(ball(0.044, x, 1.16, 0, 1, 1, 1, 7, 5), fa, R.sleeve, fore);
    add(tube(0.043, 0.032, 0.9, 1.16, x, 0, 8), fa, R.sleeve, fore);
    add(ball(0.031, x, 0.9, 0, 1, 1, 1, 6, 4), hn, R.skin, fore);
    add(box(0.028, 0.092, 0.076, x, 0.856, 0.004), hn, R.skin, fore);
    add(box(0.024, 0.078, 0.07, x - s * 0.012, 0.778, 0.012, -0.22, 0, -s * 0.3), hn, R.skin, fore);
    add(box(0.02, 0.052, 0.022, x - s * 0.012, 0.85, 0.05, -0.5), hn, R.skin, fore);
    // stump: ragged flesh cap + a spur of bone
    add(ball(0.045, x, 1.165, 0, 1, 0.7, 1, 6, 4), ua, R.gore, stump);
    add(tube(0.011, 0.016, 1.07, 1.16, x, 0, 5, 1, 1, false), ua, R.bone, stump);
  }
  // ---- legs
  for (const s of [1, -1]) {
    const L = s > 0; const x = s * PIV[B.THL * 3];
    const th = L ? B.THL : B.THR, sh = L ? B.SHL : B.SHR, ft = L ? B.FTL : B.FTR;
    add(ball(0.088, x, 0.93, 0, 1, 1, 1, 10, 7), th, R.pants);
    add(tube(0.086, 0.06, 0.5, 0.93, x, 0, 10), th, R.leg);
    add(ball(0.059, x, 0.5, 0, 1, 1, 1, 8, 6), sh, R.leg);
    add(warp(tube(0.057, 0.039, 0.08, 0.5, x, 0, 9, 1, 1, true, 2), v => { if (v.y > 0.25 && v.y < 0.35 && v.z < 0) v.z *= 1.22; }), sh, R.leg);
    add(ball(0.041, x, 0.08, 0, 1, 1, 1, 6, 4), ft, R.shoe);
    add(warp(box(0.09, 0.07, 0.2, x, 0.035, 0.035), v => { if (v.z < 0 && v.y > 0.04) v.y -= 0.008; }), ft, R.shoe);
    add(warp(box(0.086, 0.05, 0.08, x, 0.025, 0.17), v => { if (v.z > 0.18) { v.y = Math.min(v.y, 0.036); v.x = x + (v.x - x) * 0.82; } }), ft, R.shoe);
  }
  // ---- tools, held in the right hand (grip at the palm; long tools run forward, guns along the forearm)
  {
    const x = -PIV[B.UAL * 3], y = 0.835, hn = B.HNR, f0 = F.tool0;
    add(box(0.03, 0.21, 0.04, x, 0.752, 0.036), hn, R.dark, f0 + 1);                 // pistol slide
    add(box(0.027, 0.05, 0.075, x, 0.838, -0.004), hn, R.dark, f0 + 1);              //   grip
    add(box(0.026, 0.028, 0.56, x, y, 0.23), hn, R.wood, f0 + 2);                    // axe haft
    add(warp(box(0.018, 0.13, 0.085, x, y - 0.04, 0.47), v => { if (v.y < y - 0.06) { v.z = 0.47 + (v.z - 0.47) * 1.5; v.x = x + (v.x - x) * 0.3; } }), hn, R.metal, f0 + 2);
    add(box(0.024, 0.026, 1.05, x, y, 0.38), hn, R.wood, f0 + 3);                    // hoe
    add(box(0.11, 0.085, 0.012, x, y - 0.045, 0.9, 0.25), hn, R.metal, f0 + 3);
    add(box(0.018, 0.024, 0.2, x, y, 0.08), hn, R.metal, f0 + 4);                    // wrench
    add(box(0.018, 0.055, 0.05, x, y, 0.19), hn, R.metal, f0 + 4);
    { const g = new THREE.CylinderGeometry(0.034, 0.018, 0.66, Q() ? 5 : 7, 1); g.rotateX(Math.PI / 2); g.translate(x, y, 0.27); add(g, hn, R.wood, f0 + 5); }   // bat
    add(box(0.11, 0.15, 0.22, x, 0.685, 0.01), hn, R.red, f0 + 6);                   // medic bag
    add(box(0.114, 0.07, 0.022, x, 0.69, 0.01), hn, R.bone, f0 + 6);
    add(box(0.114, 0.022, 0.07, x, 0.69, 0.01), hn, R.bone, f0 + 6);
    add(box(0.012, 0.07, 0.012, x, 0.79, 0.01), hn, R.dark, f0 + 6);
    add(box(0.014, 0.014, 0.3, x, y, 0.15), hn, R.metal, f0 + 7);                    // ladle
    add(ball(0.034, x, y - 0.01, 0.31, 1, 0.8, 1, 6, 4), hn, R.metal, f0 + 7);
  }
  // ---- special growths
  {
    const ax = PIV[B.AUX * 3], ay = PIV[B.AUX * 3 + 1], az = PIV[B.AUX * 3 + 2];
    // bloat: distended gut and gas sacs
    add(ball(0.2, ax, ay, az, 1, 1, 1, 12, 8), B.AUX, R.growth, F.bloat);
    add(ball(0.06, ax + 0.11, ay + 0.09, az + 0.14, 1, 1, 1, 6, 4), B.AUX, R.growth, F.bloat);
    add(ball(0.05, ax - 0.1, ay - 0.06, az + 0.16, 1, 1, 1, 6, 4), B.AUX, R.growth, F.bloat);
    add(ball(0.075, 0.1, 1.43, -0.11, 1, 1, 1, 6, 4), B.CHEST, R.growth, F.bloat);
    add(ball(0.06, -0.09, 1.34, -0.125, 1, 1, 1, 6, 4), B.CHEST, R.growth, F.bloat);
    add(ball(0.07, 0.025, 1.5, 0.05, 1.2, 0.9, 1, 6, 4), B.CHEST, R.growth, F.bloat);
    // brute: a hump of muscle across the shoulders
    add(ball(1, 0, 1.46, -0.055, 0.17, 0.1, 0.12, 9, 6), B.CHEST, R.shirt, F.hump);
    // breaker: the right arm is a club of fused bone
    const cx = -PIV[B.UAL * 3];
    add(ball(1, cx, 0.82, 0.0, 0.045, 0.085, 0.05, 8, 6), B.HNR, R.club, F.club);
    for (const [dx, dy, dz, rz, rx] of [[-0.05, 1.06, 0, 1.2, 0], [0, 1.0, 0.045, 0, 1.2], [-0.03, 0.96, -0.04, 0.9, -1.0], [0.04, 1.1, 0.0, -1.1, 0]] as number[][]) {
      const g = new THREE.ConeGeometry(0.018, 0.07, Q() ? 4 : 5, 1); g.rotateX(rx); g.rotateZ(rz); g.translate(cx + dx, dy, dz); add(g, B.FAR, R.bone, F.club);
    }
    // howler: the throat sac it screams with
    add(ball(0.055, ax, ay, az, 1, 1, 1, 8, 6), B.AUX, R.growth, F.throat);
  }
  const g = mergeGeometries(P, false)!;
  g.computeBoundingSphere();
  g.boundingSphere!.radius = 2.6;
  return g;
}

const PIV_GLSL = (() => { const a: string[] = []; for (let b = 0; b < NB; b++) a.push(`vec3(${PIV[b * 3].toFixed(4)}, ${PIV[b * 3 + 1].toFixed(4)}, ${PIV[b * 3 + 2].toFixed(4)})`); return `const vec3 ZPIV[${NB}] = vec3[${NB}](${a.join(', ')});`; })();

const VERT_DECL = `
attribute vec4 aPart; attribute float aSlot;
uniform highp sampler2D uZBones;
${PIV_GLSL}
vec3 zqrot(vec4 q, vec3 v){ return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v); }
`;
const VERT_VARY = `
flat varying vec4 vZCol; flat varying vec4 vZSkin; flat varying vec4 vZFx; flat varying vec4 vZMisc; varying vec3 vZPos;
`;
// skinning: p' = R * (S * (p - pivot)) + pivotWorld, all per bone from the body's texture row
const VERT_SKIN = `
  int zrow = int(aSlot + 0.5);
  int zb = int(aPart.x + 0.5);
  vec4 zq = texelFetch(uZBones, ivec2(zb * 3, zrow), 0);
  vec3 zt = texelFetch(uZBones, ivec2(zb * 3 + 1, zrow), 0).xyz;
  vec3 zs = texelFetch(uZBones, ivec2(zb * 3 + 2, zrow), 0).xyz;
  vec4 zL0 = texelFetch(uZBones, ivec2(${NB * 3}, zrow), 0);
  int zfeat = int(aPart.z + 0.5);
  if (zfeat > 0 && ((int(zL0.w + 0.5) >> zfeat) & 1) == 0) zs = vec3(0.0);
  vec3 transformed = zqrot(zq, zs * (position - ZPIV[zb])) + zt;
  objectNormal = normalize(zqrot(zq, objectNormal / max(zs, vec3(0.001))));
`;
const VERT_LOOK = `
  {
    vec4 L1 = texelFetch(uZBones, ivec2(${NB * 3 + 1}, zrow), 0);
    vec4 L2 = texelFetch(uZBones, ivec2(${NB * 3 + 2}, zrow), 0);
    vec4 L3 = texelFetch(uZBones, ivec2(${NB * 3 + 3}, zrow), 0);
    vec4 L4 = texelFetch(uZBones, ivec2(${NB * 3 + 4}, zrow), 0);
    vec4 L5 = texelFetch(uZBones, ivec2(${NB * 3 + 5}, zrow), 0);
    int rg = int(aPart.y + 0.5);
    int kf = int(L4.z + 0.5); int kind = kf & 31; bool bareT = (kf & 32) != 0; bool bareF = (kf & 64) != 0;
    float human = kind >= 10 ? 1.0 : 0.0;
    vec3 skin = zL0.rgb; vec3 col = skin; float cut = -1.0; float reg = float(rg);
    if (rg == 1) { col = bareT ? skin : L1.rgb; reg = bareT ? 17.0 : 1.0; }
    else if (rg == 2) col = L2.rgb;
    else if (rg == 3) { col = bareF ? skin : mix(vec3(0.035, 0.03, 0.026), L2.rgb * 0.35, 0.3); reg = bareF ? 0.0 : 3.0; }
    else if (rg == 4) col = L3.rgb;
    else if (rg == 5) col = human > 0.5 ? vec3(0.04, 0.03, 0.025) : vec3(0.62, 0.6, 0.46);
    else if (rg == 6) { col = bareT ? skin : L1.rgb; cut = position.x > 0.0 ? L1.w : L2.w; if (bareT) cut = 9.0; }
    else if (rg == 7) { col = L2.rgb; cut = position.x > 0.0 ? L3.w : L4.w; }
    else if (rg == 8) col = vec3(0.72, 0.68, 0.55);
    else if (rg == 9) col = vec3(0.16, 0.015, 0.012);
    else if (rg == 10) { float hc = L5.w; float r = fract(hc); float gq = floor(hc / 4.0); float gg = mod(gq, 256.0) / 255.0; float bb = floor(hc / 1024.0) / 255.0; col = vec3(r, gg, bb); }
    else if (rg == 11) col = vec3(0.32, 0.33, 0.35);
    else if (rg == 12) col = vec3(0.2, 0.12, 0.06);
    else if (rg == 13) col = vec3(0.012, 0.012, 0.014);
    else if (rg == 14) col = vec3(0.45, 0.03, 0.03);
    else if (rg == 15) col = mix(skin, vec3(0.5, 0.46, 0.16), 0.55);
    else if (rg == 16) col = mix(skin, vec3(0.5, 0.46, 0.36), 0.6);
    vZCol = vec4(col, reg); vZSkin = vec4(skin, cut);
    vZFx = vec4(L5.x, L5.y, L4.x, L4.y);
    vZMisc = vec4(human, fract(L5.z), floor(L5.z) / 8.0, float(kind));
    vZPos = position;
  }
`;
const FRAG_DECL = `
uniform float uZNight;
float zn(vec3 p){ vec3 i = floor(p); vec3 f = fract(p); f = f*f*(3.0-2.0*f);
  float n = dot(i, vec3(1.0, 57.0, 113.0));
  return mix(mix(mix(fract(sin(n)*43758.5), fract(sin(n+1.0)*43758.5), f.x), mix(fract(sin(n+57.0)*43758.5), fract(sin(n+58.0)*43758.5), f.x), f.y),
             mix(mix(fract(sin(n+113.0)*43758.5), fract(sin(n+114.0)*43758.5), f.x), mix(fract(sin(n+170.0)*43758.5), fract(sin(n+171.0)*43758.5), f.x), f.y), f.z); }
float zh1(float n){ return fract(sin(n * 91.3458) * 47453.5453); }
`;
const FRAG_COLOR = `
  {
    vec3 zc = vZCol.rgb; float reg = vZCol.w; vec3 skin = vZSkin.rgb;
    float human = vZMisc.x, rag = vZMisc.y, wound = vZMisc.z, kind = vZMisc.w;
    float blood = vZFx.x, grime = vZFx.y, decay = vZFx.z, seed = vZFx.w;
    vec3 np = vZPos + vec3(seed * 1.7, seed * 0.31, seed * 2.9);
    float n1 = zn(np * ZF1), n2 = zn(np * ZF2), n3 = zn(np * ZF3 + 5.1);
    float isSkin = (reg < 0.5 || reg > 16.5) ? 1.0 : 0.0;
    bool cloth = reg > 0.5 && reg < 2.5;
    if (reg > 5.5 && reg < 7.5) {
      // sleeves and trouser legs end at a per-body height; rot leaves the hem ragged
      float cut = vZSkin.w + (n2 - 0.5) * rag * 1.6 + (n1 - 0.5) * rag;
      if (vZPos.y < cut) { zc = skin; isSkin = 1.0; } else { cloth = true; if (vZPos.y < cut + 0.012) zc *= 0.7; }
    }
    if (isSkin > 0.5) {
      zc *= mix(0.86, 1.06, n1) * mix(1.0, mix(0.8, 1.05, n2), 1.0 - human);
      // rot: dark mottling, and ribs showing through where the shirt is gone
      zc = mix(zc, zc * vec3(0.42, 0.44, 0.36), (1.0 - human) * smoothstep(0.52, 0.75, n3) * (0.35 + decay * 0.55));
      if (reg > 16.5) {
        float rib = smoothstep(0.55, 0.95, sin(vZPos.y * 95.0 + abs(vZPos.x) * 14.0)) * smoothstep(1.2, 1.26, vZPos.y) * (1.0 - smoothstep(1.4, 1.46, vZPos.y)) * step(0.0, vZPos.z) * smoothstep(0.02, 0.05, abs(vZPos.x));
        zc *= 1.0 - rib * (0.12 + 0.3 * decay) * (1.0 - human);
        zc *= 1.0 + 0.1 * smoothstep(0.0, 0.02, 0.02 - abs(vZPos.x)) * (1.0 - human) * 0.0;
      }
    } else if (cloth) {
      zc *= mix(0.9, 1.04, n2) * mix(1.0, mix(0.72, 1.05, n1), (1.0 - human) * 0.8);
    } else if (reg > 3.5 && reg < 4.5) {
      zc *= mix(0.8, 1.15, n2);
      if (human < 0.5 && n1 > 1.25 - decay) zc = skin * 0.8;   // hair falling out in patches
    } else if (reg > 14.5 && reg < 15.5) {
      // bloated tissue: stretched, veined, weeping
      float vein = smoothstep(0.46, 0.5, n1) * (1.0 - smoothstep(0.5, 0.54, n1));
      zc = mix(zc * mix(0.85, 1.15, n2), vec3(0.1, 0.13, 0.05), vein * 0.75);
      zc = mix(zc, vec3(0.62, 0.55, 0.2), smoothstep(0.6, 0.8, n3) * 0.5);
    } else if (reg > 15.5 && reg < 16.5) {
      zc = mix(zc * 0.7, vec3(0.6, 0.56, 0.44), smoothstep(0.4, 0.62, n1));
    } else if (reg > 7.5 && reg < 8.5) zc *= mix(0.75, 1.05, n2);
    if (human < 0.5 && reg < 9.5 || (human < 0.5 && reg > 14.5)) {
      if (reg < 4.5 || (reg > 5.5 && reg < 8.5) || reg > 14.5) {
        // grime, heaviest on the legs
        float gq = grime * (0.7 + 0.6 * smoothstep(0.7, 0.1, vZPos.y));
        zc = mix(zc, zc * vec3(0.4, 0.36, 0.28) + vec3(0.012, 0.01, 0.006), gq * smoothstep(0.38, 0.72, n1 * 0.6 + n3 * 0.4));
        // old blood: splashes, plus the mouth and hands that did the feeding
        float bz = zn(np * ZF4 + 3.7);
        float bl = smoothstep(0.02, 0.08, bz - mix(0.86, 0.5, blood));
        float mouth = smoothstep(1.66, 1.6, vZPos.y) * smoothstep(1.45, 1.52, vZPos.y) * smoothstep(-0.02, 0.05, vZPos.z) * step(abs(vZPos.x), 0.09);
        float hands = smoothstep(0.98, 0.86, vZPos.y) * step(0.16, abs(vZPos.x)) * step(0.7, vZPos.y);
        float chest = smoothstep(1.5, 1.4, vZPos.y) * smoothstep(1.2, 1.3, vZPos.y) * smoothstep(0.03, 0.1, vZPos.z) * step(abs(vZPos.x), 0.16) * step(reg, 1.5);
        bl = max(bl, clamp((mouth + hands + chest * 0.8) * (0.4 + blood) * smoothstep(0.25, 0.6, n1 + n2 * 0.4), 0.0, 1.0));
        zc = mix(zc, mix(vec3(0.2, 0.012, 0.01), vec3(0.07, 0.014, 0.01), decay * 0.8 + n2 * 0.2), bl * 0.9);
        // wounds: torn flesh, bone showing in the old ones
        for (int i = 0; i < 3; i++) {
          float fi = float(i) * 7.31 + seed;
          vec3 wp = vec3((zh1(fi) - 0.5) * 0.44, 0.45 + zh1(fi + 1.7) * 1.05, (zh1(fi + 3.1) - 0.5) * 0.22);
          float wr = (0.035 + 0.05 * zh1(fi + 5.3)) * wound;
          float dq = length((vZPos - wp) * vec3(1.0, 0.7, 1.0)) + (n2 - 0.5) * 0.02;
          if (dq < wr) { zc = mix(vec3(0.17, 0.014, 0.012), vec3(0.05, 0.01, 0.008), smoothstep(wr, wr * 0.4, dq)); if (dq < wr * 0.42 && decay > 0.35) zc = vec3(0.62, 0.58, 0.46) * mix(0.8, 1.0, n2); }
        }
      }
    }
    if (reg > 4.5 && reg < 5.5 && human > 0.5) zc = mix(vec3(0.5, 0.48, 0.45), vec3(0.03, 0.02, 0.02), step(0.094, vZPos.z) * step(abs(abs(vZPos.x) - 0.037), 0.009) * step(abs(vZPos.y - 1.686), 0.009));
    diffuseColor.rgb = zc;
  }
`;
const FRAG_EMIT = `
  if (vZCol.w > 4.5 && vZCol.w < 5.5 && vZMisc.x < 0.5) totalEmissiveRadiance += (vZMisc.w > 2.5 && vZMisc.w < 3.5 ? vec3(0.5, 0.12, 0.05) : vec3(0.3, 0.28, 0.16)) * (0.06 + 0.5 * uZNight);
  if (vZCol.w > 14.5 && vZCol.w < 15.5) totalEmissiveRadiance += vec3(0.1, 0.11, 0.02) * (0.15 + 0.5 * uZNight) * smoothstep(0.55, 0.8, zn(vZPos * 14.0 + vZFx.w));
`;

export function makeZombieMaterials(bones: { value: THREE.Texture | null }, night: { value: number }) {
  const mat = new THREE.MeshStandardMaterial({ roughness: 0.88, metalness: 0, flatShading: LOWPOLY });
  const defs: Record<string, string> = LOWPOLY ? { FACET_VARIATION: '', ZF1: '5.0', ZF2: '9.0', ZF3: '3.0', ZF4: '3.4' } : { ZF1: '9.0', ZF2: '34.0', ZF3: '4.2', ZF4: '5.0' };
  (mat as any).defines = defs;
  mat.onBeforeCompile = (sh) => {
    sh.uniforms.uZBones = bones; sh.uniforms.uZNight = night;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + VERT_DECL + VERT_VARY)
      .replace('#include <beginnormal_vertex>', '#include <beginnormal_vertex>\n' + VERT_SKIN)
      .replace('#include <begin_vertex>', VERT_LOOK);
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\n' + VERT_VARY + FRAG_DECL)
      .replace('#include <color_fragment>', '#include <color_fragment>\n' + FRAG_COLOR)
      .replace('#include <emissivemap_fragment>', '#include <emissivemap_fragment>\n' + FRAG_EMIT);
  };
  mat.customProgramCacheKey = () => 'body-v1-' + (LOWPOLY ? 'f' : 's');

  const depth = new THREE.MeshDepthMaterial({ depthPacking: THREE.RGBADepthPacking });
  depth.onBeforeCompile = (sh) => {
    sh.uniforms.uZBones = bones;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + VERT_DECL)
      .replace('#include <begin_vertex>', 'vec3 objectNormal = vec3(0.0, 1.0, 0.0);\n' + VERT_SKIN);
  };
  depth.customProgramCacheKey = () => 'body-depth-v1';
  return { mat, depth };
}

/** Owns the instanced mesh + bone texture. Each frame: begin(), add() per visible body, end(). */
export class BodyRenderer {
  mesh: THREE.InstancedMesh;
  readonly max: number;
  private data: Float32Array; private tex: THREE.DataTexture; private slot: THREE.InstancedBufferAttribute;
  private n = 0;
  night = { value: 0 };
  private tmpM = new THREE.Matrix4(); private tmpQ = new THREE.Quaternion(); private tmpS = new THREE.Vector3(); private tmpP = new THREE.Vector3();
  /** main camera seen on the previous frame (for cheap CPU culling); null until the first draw */
  cam: THREE.Camera | null = null;

  constructor(scene: THREE.Object3D, max: number) {
    this.max = max;
    const geo = buildZombieGeometry();
    this.data = new Float32Array(max * ROW);
    this.tex = new THREE.DataTexture(this.data, ROW_TEXELS, max, THREE.RGBAFormat, THREE.FloatType);
    this.tex.magFilter = this.tex.minFilter = THREE.NearestFilter; this.tex.generateMipmaps = false; this.tex.needsUpdate = true;
    const slots = new Float32Array(max); for (let i = 0; i < max; i++) slots[i] = i;
    this.slot = new THREE.InstancedBufferAttribute(slots, 1);
    geo.setAttribute('aSlot', this.slot);
    const { mat, depth } = makeZombieMaterials({ value: this.tex }, this.night);
    this.mesh = new THREE.InstancedMesh(geo, mat, max);
    this.mesh.customDepthMaterial = depth;
    this.mesh.castShadow = true; this.mesh.receiveShadow = true; this.mesh.frustumCulled = false;
    this.mesh.count = 0;
    this.mesh.onBeforeRender = (_r, _s, camera) => { if ((camera as THREE.PerspectiveCamera).isPerspectiveCamera) this.cam = camera; };
    scene.add(this.mesh);
  }
  get vertexCount() { return this.mesh.geometry.attributes.position.count; }
  begin() { this.n = 0; }
  get count() { return this.n; }
  full() { return this.n >= this.max; }
  add(a: BodyAnim, d: BodyDef, x: number, y: number, z: number, yaw: number, scale: number) {
    const n = this.n; if (n >= this.max) return;
    this.tmpQ.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, yaw);
    this.tmpM.compose(this.tmpP.set(x, y, z), this.tmpQ, this.tmpS.set(scale, scale, scale));
    this.mesh.setMatrixAt(n, this.tmpM);
    const o = n * ROW;
    poseBody(a, d, this.data, o);
    this.data.set(d.look, o + LOOK_AT);
    this.n = n + 1;
  }
  end() {
    this.mesh.count = this.n;
    this.mesh.instanceMatrix.needsUpdate = true;
    this.mesh.instanceMatrix.clearUpdateRanges(); this.mesh.instanceMatrix.addUpdateRange(0, this.n * 16);
    this.tex.needsUpdate = true;
  }
}
