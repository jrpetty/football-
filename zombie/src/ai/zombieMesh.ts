// One GPU-instanced body for every zombie and human. The skeleton is posed on the CPU (bodyRig.ts) and each
// body's bone transforms + look are written to one row of a float texture; the vertex shader skins the shared
// mesh (bodyGeo.ts) from it with two-bone blending, and the fragment shader dresses it from the look.
import * as THREE from 'three';
import { LOWPOLY } from '../render/materials';
import { PIV, NB, ROW, ROW_TEXELS, LOOK_AT, BodyAnim, BodyDef, poseBody } from './bodyRig';
import { buildBodyGeometry } from './bodyGeo';

export function buildZombieGeometry(): THREE.BufferGeometry { return buildBodyGeometry(); }

const PIV_GLSL = (() => { const a: string[] = []; for (let b = 0; b < NB; b++) a.push(`vec3(${PIV[b * 3].toFixed(4)}, ${PIV[b * 3 + 1].toFixed(4)}, ${PIV[b * 3 + 2].toFixed(4)})`); return `const vec3 ZPIV[${NB}] = vec3[${NB}](${a.join(', ')});`; })();
const LK = NB * 3;   // first look texel

const VERT_DECL = `
attribute vec4 aPart; attribute float aSlot;
uniform highp sampler2D uZBones;
${PIV_GLSL}
vec3 zqrot(vec4 q, vec3 v){ return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v); }
`;
const VARY = `
varying vec3 vZPos; flat varying vec2 vZInfo;
`;
// skinning: p' = R * (S * (p - pivot)) + pivotWorld per bone, blended between two bones near joints
const VERT_SKIN = `
  int zrow = int(aSlot + 0.5);
  int zba = int(aPart.x + 0.5);
  int zrf = int(aPart.w + 0.5); int zfeat = zrf >> 6;
  vec4 zqA = texelFetch(uZBones, ivec2(zba * 3, zrow), 0);
  vec3 ztA = texelFetch(uZBones, ivec2(zba * 3 + 1, zrow), 0).xyz;
  vec3 zsA = texelFetch(uZBones, ivec2(zba * 3 + 2, zrow), 0).xyz;
  bool zhide = false;
  if (zfeat > 0) {
    int zbits = zfeat < 16 ? int(texelFetch(uZBones, ivec2(${LK}, zrow), 0).w + 0.5) : int(texelFetch(uZBones, ivec2(${LK + 9}, zrow), 0).x + 0.5);
    zhide = ((zbits >> (zfeat < 16 ? zfeat : zfeat - 16)) & 1) == 0;
  }
  vec3 transformed = ztA;
  if (!zhide) {
    transformed = zqrot(zqA, zsA * (position - ZPIV[zba])) + ztA;
    vec3 znn = zqrot(zqA, objectNormal / max(zsA, vec3(0.001)));
    if (aPart.z > 0.001) {
      int zbb = int(aPart.y + 0.5);
      vec4 zqB = texelFetch(uZBones, ivec2(zbb * 3, zrow), 0);
      vec3 ztB = texelFetch(uZBones, ivec2(zbb * 3 + 1, zrow), 0).xyz;
      vec3 zsB = texelFetch(uZBones, ivec2(zbb * 3 + 2, zrow), 0).xyz;
      transformed = mix(transformed, zqrot(zqB, zsB * (position - ZPIV[zbb])) + ztB, aPart.z);
      znn = mix(znn, zqrot(zqB, objectNormal / max(zsB, vec3(0.001))), aPart.z);
    }
    objectNormal = normalize(znn);
  }
`;
const VERT_OUT = `
  vZPos = position; vZInfo = vec2(float(zrf & 63), float(zrow));
`;
const FRAG_DECL = `
uniform highp sampler2D uZBones; uniform float uZNight; uniform float uZAmb;
float zSkinF = 0.0; float zGlow = 0.0;
float zn(vec3 p){ vec3 i = floor(p); vec3 f = fract(p); f = f*f*(3.0-2.0*f);
  float n = dot(i, vec3(1.0, 57.0, 113.0));
  return mix(mix(mix(fract(sin(n)*43758.5), fract(sin(n+1.0)*43758.5), f.x), mix(fract(sin(n+57.0)*43758.5), fract(sin(n+58.0)*43758.5), f.x), f.y),
             mix(mix(fract(sin(n+113.0)*43758.5), fract(sin(n+114.0)*43758.5), f.x), mix(fract(sin(n+170.0)*43758.5), fract(sin(n+171.0)*43758.5), f.x), f.y), f.z); }
float zh1(float n){ return fract(sin(n * 91.3458) * 47453.5453); }
float zband(float a, float b, float e, float x){ return smoothstep(a - e, a + e, x) * (1.0 - smoothstep(b - e, b + e, x)); }
#define ZL(k) texelFetch(uZBones, ivec2(${LK} + k, zrow), 0)
`;
const FRAG_COLOR = `
  {
    int zrow = int(vZInfo.y + 0.5); int reg = int(vZInfo.x + 0.5);
    vec4 L0 = ZL(0), L1 = ZL(1), L2 = ZL(2), L3 = ZL(3), L4 = ZL(4), L5 = ZL(5), L6 = ZL(6), L7 = ZL(7), L8 = ZL(8);
    vec3 rp = vZPos; float ax = abs(rp.x);
    vec3 skin = L0.rgb, shirt = L1.rgb, pants = L2.rgb, hair = L3.rgb, jack = L6.rgb, kit = L7.rgb, boot = L8.rgb;
    float decay = L4.x, seed = L4.y; int kind = int(L4.z + 0.5); float human = kind >= 10 ? 1.0 : 0.0; float zomb = 1.0 - human;
    float blood = L5.x, grime = L5.y, rag = L5.z, wound = L5.w;
    int fl = int(L6.w + 0.5);
    bool fJacket = (fl & 1) != 0, fVest = (fl & 2) != 0, fRolled = (fl & 4) != 0, fPack = (fl & 8) != 0, bareT = (fl & 32) != 0, bareF = (fl & 64) != 0, stubble = (fl & 128) != 0;
    int style = int(L8.w + 0.5);
    // the world is lit hard (sun 3 + sky): albedo is compressed so pale skin and cloth keep their colour at noon
    vec3 skc = pow(skin, vec3(0.86)) * 0.86; skin = mix(skin, mix(vec3(dot(skc, vec3(0.3, 0.59, 0.11))), skc, 1.12), human);
    vec3 np = rp + vec3(seed * 1.7, seed * 0.31, seed * 2.9);
    float n1 = zn(np * ZF1), n2 = zn(np * ZF2), n3 = zn(np * ZF3 + 5.1);
    float ang = atan(rp.x, rp.z);                     // around the trunk
    vec3 zc = skin; float isSkin = 0.0, cloth = 0.0, prop = 0.0;
    float front = smoothstep(0.03, 0.06, rp.z);

    if (reg == 0 || reg == 19) {
      // ---------------- face
      isSkin = 1.0;
      if (reg == 0) {
        float lipU = zband(1.6125, 1.6195, 0.0012, rp.y), lipL = zband(1.5975, 1.6055, 0.0012, rp.y);
        float mw = 1.0 - smoothstep(0.019, 0.027, ax);
        zc = mix(zc, zc * vec3(0.8, 0.5, 0.5) + vec3(0.02, 0.003, 0.003), (lipU + lipL) * mw * front * mix(0.4, 0.9, human));
        float jaw = texelFetch(uZBones, ivec2(13, zrow), 0).w;
        float mouth = step(1.6055, rp.y) * (1.0 - step(1.6125, rp.y)) * (1.0 - smoothstep(0.022, 0.028, ax)) * front;
        float my = (rp.y - 1.6055) / 0.007;
        vec3 inner = mix(vec3(0.06, 0.016, 0.016), vec3(0.52, 0.48, 0.36), step(0.12, jaw) * (step(my, 0.24) + step(0.76, my)) * (1.0 - smoothstep(0.016, 0.02, ax)));
        zc = mix(zc, inner, mouth * mix(0.8, 1.0, clamp(jaw * 3.0, 0.0, 1.0)));
        float browY = rp.y + (ax - 0.03) * (ax - 0.03) * 3.0;
        float brow = zband(1.7015, 1.7105, 0.0012, browY) * zband(0.011, 0.05, 0.003, ax) * front;
        zc = mix(zc, hair * 0.75 + vec3(0.004), brow * mix(0.9, 0.5, zomb));
        float sock = exp(-pow((ax - 0.032) / 0.018, 2.0) - pow((rp.y - 1.685) / 0.011, 2.0)) * front;
        zc *= 1.0 - sock * mix(0.3, 0.6, zomb);
        // soft shading that gives the face form in any light: beside and under the nose, under the lip, the temples
        zc *= 1.0 - 0.16 * zband(1.622, 1.634, 0.003, rp.y) * (1.0 - smoothstep(0.012, 0.02, ax)) * front;
        zc *= 1.0 - 0.1 * zband(0.01, 0.021, 0.004, ax) * zband(1.64, 1.688, 0.008, rp.y) * front;
        zc *= 1.0 - 0.13 * zband(1.586, 1.597, 0.003, rp.y) * (1.0 - smoothstep(0.016, 0.026, ax)) * front;
        zc *= 1.0 - 0.1 * smoothstep(0.02, -0.03, rp.z) * zband(1.6, 1.7, 0.02, rp.y);
        float cheek = exp(-pow((ax - 0.047) / 0.02, 2.0) - pow((rp.y - 1.642) / 0.018, 2.0)) * front;
        zc = mix(zc, zc * vec3(1.1, 0.88, 0.86), cheek * 0.55 * human);
        if ((fl & 256) != 0) zc = mix(zc, hair * 0.8, zband(1.6135, 1.6245, 0.0015, rp.y + ax * 0.12) * (1.0 - smoothstep(0.024, 0.031, ax)) * front);   // moustache
        if (stubble && human > 0.5) { float sb = smoothstep(1.665, 1.625, rp.y) * smoothstep(-0.03, 0.02, rp.z) * (1.0 - mouth) * (1.0 - (lipU + lipL) * mw); zc = mix(zc, zc * 0.7 + hair * 0.12, sb * 0.5); }
        // neck shadow under the jaw
        zc *= 1.0 - 0.3 * smoothstep(1.58, 1.555, rp.y);
      }
    } else if (reg == 7) {
      // ---------------- eyes: whites, iris and pupil for the living; a dead milk-white for the rest
      float blink = texelFetch(uZBones, ivec2(10, zrow), 0).w;
      float r = length(vec2(ax - 0.0315, rp.y - 1.6845));
      float lid = rp.y - 1.6845; lid = lid > 0.0 ? lid / (0.0068 - abs(ax - 0.0315) * 0.12) : -lid / (0.0056 - abs(ax - 0.0315) * 0.1);
      if (rp.z < 0.081 || blink > 0.5 || lid > 1.0) { zc = skin * (lid > 1.0 && rp.y > 1.6845 ? 0.72 : 0.85); isSkin = 1.0; }
      else if (human > 0.5) {
        int ec = int(L7.w + 0.5);
        vec3 iris = ec < 6 ? vec3(0.13, 0.06, 0.025) : ec < 8 ? vec3(0.1, 0.2, 0.34) : ec < 9 ? vec3(0.12, 0.22, 0.1) : vec3(0.2, 0.22, 0.25);
        zc = mix(clamp(skin * 1.6 + 0.12, 0.0, 0.5) * vec3(0.95, 1.0, 1.02), iris, 1.0 - smoothstep(0.0062, 0.0072, r));
        zc = mix(zc, vec3(0.01), 1.0 - smoothstep(0.0026, 0.0034, r));
        zc *= mix(0.62, 1.0, smoothstep(1.0, 0.45, lid) + step(rp.y, 1.6845));
        if (lid > 0.72 && rp.y > 1.6845) zc = skin * 0.3;       // lash line
        prop = 1.0;
      } else {
        zc = mix(vec3(0.6, 0.58, 0.46), vec3(0.34, 0.36, 0.28), 1.0 - smoothstep(0.003, 0.0045, r));
        prop = 1.0; zGlow = kind == 3 ? 2.0 : 1.0;
      }
    } else if (reg == 6) {
      // ---------------- hair: combed strands
      float st = zn(vec3(rp.x * 120.0 + seed, rp.y * 16.0, rp.z * 120.0));
      zc = hair * mix(0.7, 1.25, st) * mix(0.9, 1.08, n3);
      if (zomb > 0.5 && n1 > 1.22 - decay * 1.1) discard;        // falling out in patches
    } else if (reg == 10) {
      zc = kit * (0.86 + 0.14 * step(0.5, fract(ang * 9.0))) * mix(0.92, 1.05, n2); cloth = 1.0;
    } else if (reg == 1 || reg == 20 || reg == 17) {
      // ---------------- trunk: shirt (four cuts), trousers and belt, optional jacket shell over it
      if (reg == 17) {
        zc = jack; cloth = 1.0;
        float edge = 1.0 - smoothstep(0.2, 0.3, abs(ang));
        zc *= 1.0 - 0.22 * edge * step(0.0, rp.z);                                  // facing / zip
        zc *= 1.0 - 0.18 * smoothstep(1.492, 1.5, rp.y);                            // collar
        zc *= 1.0 - 0.14 * (1.0 - smoothstep(1.0, 1.02, rp.y));                     // hem
        float pk = zband(1.075, 1.155, 0.003, rp.y) * zband(0.5, 1.15, 0.03, abs(ang)); float pin = zband(1.082, 1.148, 0.003, rp.y) * zband(0.56, 1.09, 0.03, abs(ang));
        zc *= 1.0 - 0.2 * (pk - pin);                                                // pocket welts
        if (fVest) zc *= 0.9 + 0.1 * smoothstep(0.1, 0.2, fract(rp.y * 16.0));       // quilting
        zc *= 1.0 - 0.1 * zband(1.28, 1.3, 0.004, rp.y) * step(rp.z, 0.0);          // back yoke
      } else {
        bool gut = reg == 20;
        float neck = 1.511 - (style == 1 ? 0.05 : 0.026) * (1.0 - smoothstep(0.0, 0.055, ax)) * step(0.0, rp.z);
        if (rp.y < 1.058 && !gut) {
          zc = pants; cloth = 1.0;
        } else if (bareT || rp.y > neck) {
          isSkin = 1.0;
          if (bareT) {
            float rib = smoothstep(0.55, 0.95, sin(rp.y * 95.0 + ax * 14.0)) * zband(1.2, 1.43, 0.03, rp.y) * step(0.0, rp.z) * smoothstep(0.02, 0.05, ax);
            zc *= 1.0 - rib * (0.1 + 0.3 * decay) * zomb;
            zc *= 1.0 - 0.1 * zband(1.385, 1.4, 0.006, rp.y) * step(0.0, rp.z) * zband(0.02, 0.13, 0.01, ax);   // chest line
            if (gut && kind == 4) {
              float vein = zband(0.46, 0.54, 0.02, n1);
              zc = mix(zc * mix(0.9, 1.12, n2), vec3(0.09, 0.12, 0.05), vein * 0.7);
              zc = mix(zc, vec3(0.6, 0.53, 0.2), smoothstep(0.6, 0.8, n3) * 0.45);
            }
          } else zc *= 1.0 - 0.22 * smoothstep(neck + 0.014, neck, rp.y) - 0.2 * smoothstep(1.56, 1.6, rp.y);     // shadow at the collar and under the jaw
        } else {
          zc = shirt; cloth = 1.0;
          float dn = neck - rp.y;
          if (style == 0) zc *= 1.0 - 0.2 * (1.0 - smoothstep(0.012, 0.016, dn));                         // tee: ribbed crew neck
          else if (style == 1) {
            // button shirt: placket, buttons, collar, breast pocket
            float pl = (1.0 - smoothstep(0.011, 0.013, ax)) * step(0.0, rp.z);
            zc *= 1.0 - 0.1 * pl;
            zc = mix(zc, vec3(0.5, 0.48, 0.42), pl * (1.0 - smoothstep(0.004, 0.006, length(vec2(ax, (fract((rp.y - 1.09) / 0.075) - 0.5) * 0.075)))));
            float col = (1.0 - smoothstep(0.03, 0.036, dn + ax * 0.25)) * step(0.0, rp.z + 0.03);
            zc = mix(zc, zc * 1.12 + 0.01, col); zc *= 1.0 - 0.25 * zband(0.03, 0.036, 0.002, dn + ax * 0.25) * step(0.0, rp.z + 0.03);
            float pk = zband(1.325, 1.395, 0.002, rp.y) * zband(0.045, 0.115, 0.002, rp.x) * step(0.0, rp.z), pin = zband(1.33, 1.39, 0.002, rp.y) * zband(0.05, 0.11, 0.002, rp.x);
            zc *= 1.0 - 0.15 * (pk - pk * pin);
          } else if (style == 2) {
            // jumper: knit rows, ribbed hem and neck
            zc *= 0.93 + 0.07 * sin(rp.y * 560.0);
            float rb = max(1.0 - smoothstep(1.085, 1.09, rp.y), 1.0 - smoothstep(0.018, 0.022, dn));
            zc *= 1.0 - rb * (0.1 + 0.12 * step(0.5, fract(ang * 16.0)));
          } else {
            // flannel check
            vec2 q = vec2(ang * 0.15, rp.y) / 0.05;
            float cx = step(0.5, fract(q.x)), cy = step(0.5, fract(q.y));
            zc = mix(zc, zc * 0.42, (cx + cy) * 0.36);
            zc = mix(zc, zc * 1.5 + 0.02, (zband(0.22, 0.28, 0.01, fract(q.x)) + zband(0.22, 0.28, 0.01, fract(q.y))) * 0.4);
            float pl = (1.0 - smoothstep(0.011, 0.013, ax)) * step(0.0, rp.z); zc *= 1.0 - 0.12 * pl;
          }
          zc *= 1.0 - 0.16 * (1.0 - smoothstep(1.066, 1.078, rp.y)) * (gut ? 0.0 : 1.0);                  // hem shadow
          zc *= 1.0 - 0.07 * (1.0 - smoothstep(0.0, 0.012, abs(abs(ang) - 1.5708))) * step(rp.y, 1.4);     // side seams
          // folds where the cloth gathers at the waist
          zc *= 1.0 - 0.07 * zband(1.1, 1.26, 0.03, rp.y) * smoothstep(0.5, 0.9, sin(ang * 9.0 + n3 * 6.0 + rp.y * 30.0));
        }
        if (fPack && rp.y > 1.2 && cloth > 0.5 && rp.y > 1.058) zc = mix(zc, kit * 0.55, zband(0.082, 0.118, 0.003, ax) * max(step(0.0, rp.z), step(1.44, rp.y)));   // pack straps
      }
    } else if (reg == 2) {
      // ---------------- arms: sleeve to a cut line, cuff, skin below
      float cut = (rp.x > 0.0 ? L1.w : L2.w) + ((n1 - 0.5) * 1.9 + (n2 - 0.5) * 0.35) * rag;
      bool bare = bareT && !fJacket;
      if (bare || rp.y < cut) { isSkin = 1.0; zc *= 1.0 - 0.1 * zband(1.145, 1.175, 0.008, rp.y) * step(0.0, rp.z); }
      else {
        zc = fJacket ? jack : shirt; cloth = 1.0;
        float dc = rp.y - cut;
        vec2 lq = vec2(atan(ax - 0.168, rp.z) * 0.045, rp.y);
        if (!fJacket) {
          if (style == 2) { zc *= 0.93 + 0.07 * sin(rp.y * 560.0); zc *= 1.0 - (1.0 - smoothstep(0.03, 0.036, dc)) * (0.1 + 0.12 * step(0.5, fract(lq.x * 220.0))); }
          else if (style == 3) { vec2 q = lq / 0.05; float cx = step(0.5, fract(q.x)), cy = step(0.5, fract(q.y)); zc = mix(zc, zc * 0.42, (cx + cy) * 0.36); zc = mix(zc, zc * 1.5 + 0.02, (zband(0.22, 0.28, 0.01, fract(q.x)) + zband(0.22, 0.28, 0.01, fract(q.y))) * 0.4); }
        }
        if (fRolled) zc = mix(zc, zc * 1.25 + 0.015, 1.0 - smoothstep(0.03, 0.034, dc));             // the roll shows the lining
        zc *= 1.0 - 0.22 * (1.0 - smoothstep(0.0, 0.006, dc)) - 0.12 * zband(0.03, 0.036, 0.002, dc) * (fRolled ? 1.0 : 0.6);   // cuff edge
        // creases inside the elbow and at the shoulder seam
        zc *= 1.0 - 0.1 * (1.0 - smoothstep(0.0, 0.05, abs(rp.y - 1.16))) * smoothstep(0.3, 0.9, sin(rp.y * 210.0 + n3 * 5.0));
        zc *= 1.0 - 0.1 * zband(1.455, 1.468, 0.004, rp.y - (ax - 0.168) * 0.5);
      }
    } else if (reg == 3) {
      isSkin = 1.0;
      float fz = fract((rp.z + 0.0365) / 0.0192);
      zc *= 1.0 - 0.3 * (1.0 - smoothstep(0.0, 0.1, min(fz, 1.0 - fz))) * (1.0 - smoothstep(0.8, 0.825, rp.y)) * zband(-0.03, 0.035, 0.004, rp.z);   // fingers
      zc *= 1.0 - 0.12 * zband(0.818, 0.828, 0.003, rp.y);                                             // knuckles
    } else if (reg == 4) {
      // ---------------- legs: trousers to a cut line (shorts / torn), seams, wear at the knee
      float cut = (rp.x > 0.0 ? L3.w : L4.w) + ((n1 - 0.5) * 1.9 + (n2 - 0.5) * 0.35) * rag;
      if (rp.y < cut) { isSkin = 1.0; zc *= 1.0 - 0.1 * zband(0.47, 0.5, 0.01, rp.y) * step(0.0, rp.z); }
      else {
        zc = pants; cloth = 1.0;
        float la = atan(ax - 0.088, rp.z);
        if (rp.y > 0.9) {
          // seat and waistband: belt, buckle, fly, back pockets
          float belt = zband(1.03, 1.06, 0.0015, rp.y);
          zc = mix(zc, vec3(0.045, 0.03, 0.02), belt);
          zc = mix(zc, vec3(0.42, 0.38, 0.28), belt * (1.0 - smoothstep(0.014, 0.018, ax)) * step(0.0, rp.z) * zband(1.034, 1.054, 0.001, rp.y));
          zc *= 1.0 - 0.12 * (1.0 - smoothstep(0.003, 0.007, ax)) * step(rp.y, 1.03);
          float bp = zband(0.925, 1.0, 0.003, rp.y) * zband(0.04, 0.125, 0.003, ax) * step(rp.z, -0.02), bi = zband(0.932, 0.993, 0.003, rp.y) * zband(0.047, 0.118, 0.003, ax);
          zc *= 1.0 - 0.16 * (bp - bp * bi);
          if ((fl & 16) != 0) zc = mix(zc, vec3(0.07, 0.045, 0.025), zband(1.0, 1.03, 0.002, rp.y));
          la = 0.0;
        }
        zc *= 1.0 - 0.1 * (1.0 - smoothstep(0.0, 0.07, abs(la - 1.5708))) * step(rp.y, 0.9);           // outer seam
        zc *= 1.0 - 0.1 * (1.0 - smoothstep(0.0, 0.07, abs(la + 1.5708))) * step(rp.y, 0.84);          // inseam
        float knee = exp(-pow((rp.y - 0.53) / 0.08, 2.0)) * step(0.0, rp.z), thigh = exp(-pow((rp.y - 0.78) / 0.1, 2.0)) * step(0.02, rp.z);
        zc = mix(zc, zc * 1.3 + 0.012, (knee * 0.4 + thigh * 0.2) * mix(0.5, 1.0, n3));                // fade where it rubs
        zc *= 1.0 - 0.1 * (1.0 - smoothstep(0.0, 0.05, abs(rp.y - 0.5))) * step(rp.z, 0.0) * smoothstep(0.2, 0.9, sin(rp.y * 190.0 + n3 * 5.0));   // creases behind the knee
        zc *= 1.0 - 0.09 * zband(0.86, 0.95, 0.02, rp.y) * step(0.0, rp.z) * smoothstep(0.4, 0.9, sin(rp.y * 120.0 + ax * 60.0));               // lap folds
        zc *= 1.0 - 0.2 * (1.0 - smoothstep(0.0, 0.008, rp.y - max(cut, 0.126))) - 0.1 * zband(0.16, 0.166, 0.002, rp.y) * step(cut, 0.1);     // hem
        // front pocket openings
      }
    } else if (reg == 5) {
      // ---------------- boots (or bare feet)
      if (bareF) { isSkin = 1.0; float tz = fract((ax - 0.04) / 0.02); zc *= 1.0 - 0.3 * (1.0 - smoothstep(0.0, 0.12, min(tz, 1.0 - tz))) * smoothstep(0.15, 0.17, rp.z); }
      else {
        zc = boot; cloth = 1.0;
        zc = mix(zc, vec3(0.012, 0.011, 0.01), 1.0 - smoothstep(0.02, 0.024, rp.y));                    // sole
        zc *= 1.0 + 0.25 * smoothstep(0.13, 0.17, rp.z) * step(0.024, rp.y);                            // scuffed toe
        float lace = zband(-0.01, 0.1, 0.01, rp.z) * (1.0 - smoothstep(0.012, 0.02, abs(ax - 0.088))) * step(0.06, rp.y);
        zc = mix(zc, zc * 0.5 + vec3(0.06, 0.055, 0.045) * step(0.55, fract(rp.z * 55.0 + rp.y * 30.0)), lace * 0.8);
        zc *= 1.0 - 0.2 * zband(0.19, 0.2, 0.003, rp.y);
      }
    } else if (reg == 18) {
      zc = rp.y < 1.1 ? vec3(0.075, 0.048, 0.026) : kit * 0.62; cloth = 1.0;
      zc *= 1.0 - 0.2 * zband(1.385, 1.395, 0.003, rp.y) * step(1.1, rp.y) - 0.12 * (1.0 - smoothstep(0.0, 0.008, abs(ax - 0.06))) * step(1.1, rp.y) * step(rp.z, -0.2);
    } else if (reg == 8) { zc = vec3(0.72, 0.68, 0.55) * mix(0.75, 1.05, n2); prop = human; }
    else if (reg == 9) { zc = vec3(0.16, 0.015, 0.012); prop = 1.0; }
    else if (reg == 11) { zc = vec3(0.32, 0.33, 0.35) * mix(0.85, 1.1, n2); prop = 1.0; }
    else if (reg == 12) { zc = vec3(0.2, 0.12, 0.06) * mix(0.8, 1.15, zn(vec3(rp.x * 60.0, rp.y * 60.0, rp.z * 6.0))); prop = 1.0; }
    else if (reg == 13) { zc = vec3(0.012, 0.012, 0.014); prop = 1.0; }
    else if (reg == 14) { zc = vec3(0.45, 0.03, 0.03); prop = 1.0; }
    else if (reg == 15) {
      // bloated tissue: stretched, veined, weeping
      zc = mix(skin, vec3(0.5, 0.46, 0.16), 0.55);
      zc = mix(zc * mix(0.85, 1.15, n2), vec3(0.1, 0.13, 0.05), zband(0.46, 0.54, 0.02, n1) * 0.75);
      zc = mix(zc, vec3(0.62, 0.55, 0.2), smoothstep(0.6, 0.8, n3) * 0.5); zGlow = 3.0;
    } else if (reg == 16) zc = mix(mix(skin, vec3(0.5, 0.46, 0.36), 0.6) * 0.7, vec3(0.6, 0.56, 0.44), smoothstep(0.4, 0.62, n1));

    if (isSkin > 0.5) {
      zc *= mix(0.93, 1.05, n1) * mix(1.0, mix(0.82, 1.05, n2), zomb);
      // rot: dark mottling
      zc = mix(zc, zc * vec3(0.5, 0.5, 0.42), zomb * smoothstep(0.55, 0.78, n3) * (0.3 + decay * 0.45));
    } else if (cloth > 0.5) {
      // weave, slow tonal drift, and wear
      zc *= mix(0.93, 1.05, n2) * mix(0.92, 1.06, n3) * mix(1.0, mix(0.72, 1.05, n1), zomb * 0.8);
    }
    if (zomb > 0.5 && prop < 0.5) {
      // grime, heaviest on the legs
      float gq = grime * (0.7 + 0.6 * smoothstep(0.7, 0.1, rp.y));
      zc = mix(zc, zc * vec3(0.5, 0.45, 0.36) + vec3(0.012, 0.01, 0.006), gq * smoothstep(0.42, 0.75, n1 * 0.6 + n3 * 0.4));
      // old blood: splashes, plus the mouth, hands and chest that did the feeding
      float bz = zn(np * ZF4 + 3.7);
      float bl = smoothstep(0.02, 0.08, bz - mix(0.92, 0.62, blood)) * step(0.12, rp.y);
      float mouthB = zband(1.5, 1.645, 0.02, rp.y) * smoothstep(0.0, 0.06, rp.z) * (1.0 - smoothstep(0.03, 0.07, ax)) * (reg == 0 || reg == 1 ? 1.0 : 0.0);
      float handsB = reg == 3 ? 1.0 : reg == 2 ? smoothstep(1.0, 0.92, rp.y) : 0.0;
      float chestB = reg == 1 ? zband(1.25, 1.47, 0.05, rp.y) * smoothstep(0.03, 0.1, rp.z) * smoothstep(0.13, 0.04, ax) * smoothstep(0.35, 0.7, n3) : 0.0;
      bl = max(bl, clamp((mouthB + handsB + chestB * 0.8) * (0.4 + blood) * smoothstep(0.25, 0.6, n1 + n2 * 0.4), 0.0, 1.0));
      zc = mix(zc, mix(vec3(0.2, 0.012, 0.01), vec3(0.07, 0.014, 0.01), decay * 0.8 + n2 * 0.2), bl * 0.9);
      // wounds: torn flesh, bone showing in the old ones
      for (int i = 0; i < 3; i++) {
        float fi = float(i) * 7.31 + seed;
        vec3 wp = vec3((zh1(fi) - 0.5) * 0.4, 0.45 + zh1(fi + 1.7) * 1.05, (zh1(fi + 3.1) - 0.5) * 0.22);
        float wr = (0.035 + 0.05 * zh1(fi + 5.3)) * wound;
        float dq = length((rp - wp) * vec3(1.0, 0.7, 1.0)) + (n2 - 0.5) * 0.02 + (n1 - 0.5) * 0.035;
        if (dq < wr) { zc = mix(vec3(0.17, 0.014, 0.012), vec3(0.05, 0.01, 0.008), smoothstep(wr, wr * 0.4, dq)); if (dq < wr * 0.42 && decay > 0.35) zc = vec3(0.62, 0.58, 0.46) * mix(0.8, 1.0, n2); }
      }
    }
    zSkinF = isSkin;
    // the realistic style lights with a very bright sky dome: surfaces need albedos far below their nominal colour
    // (as the world palette has) or everything bleaches to pastel; skin is warmed against the sky's blue cast
    diffuseColor.rgb = zc * ZALB * (isSkin > 0.5 ? ZWARM : vec3(1.0));
  }
`;
const FRAG_EMIT = `
  // what glows in the dark
  if (zGlow > 0.5) totalEmissiveRadiance += zGlow > 2.5 ? vec3(0.1, 0.11, 0.02) * (0.15 + 0.5 * uZNight) * smoothstep(0.55, 0.8, zn(vZPos * 14.0)) : (zGlow > 1.5 ? vec3(0.7, 0.16, 0.06) : vec3(0.42, 0.4, 0.22)) * (0.05 + 0.75 * uZNight);
`;

// The world is lit by a hard sun and a thin blue sky, which leaves anything in shade (a face turned from the
// sun, most of all) close to black. People get the bounce a body really gets from the ground and walls around
// it: warm, soft, strongest on what faces the viewer and what faces up, and only where the sun isn't already.
// Then edges turn away from the eye a little darker, which reads as form even at noon.
const FRAG_FORM = `
  {
    vec3 zN = normalize(normal); vec3 zV = normalize(vViewPosition); vec3 zUp = normalize((viewMatrix * vec4(0.0, 1.0, 0.0, 0.0)).xyz);
    float zfv = clamp(dot(zN, zV), 0.0, 1.0);
    float zlit = dot(reflectedLight.directDiffuse, vec3(0.3, 0.59, 0.11)) / max(dot(diffuseColor.rgb, vec3(0.3, 0.59, 0.11)), 0.0005);
    float zlift = 1.0 - smoothstep(0.0, 0.62, zlit);
    float zw = 0.45 + 0.3 * zfv + 0.25 * clamp(dot(zN, zUp) * 0.5 + 0.5, 0.0, 1.0);
    outgoingLight += diffuseColor.rgb * uZAmb * ZFILL * zw * (0.25 + 0.75 * zlift) * (zSkinF > 0.5 ? vec3(1.42, 1.3, 1.16) : vec3(0.95, 0.93, 0.9));
    outgoingLight *= mix(0.64, 1.0, smoothstep(0.0, 0.62, zfv));
  }
`;

export function makeZombieMaterials(bones: { value: THREE.Texture | null }, night: { value: number }, amb: { value: number }) {
  const mat = new THREE.MeshStandardMaterial({ roughness: 0.86, metalness: 0, flatShading: LOWPOLY });
  const defs: Record<string, string> = LOWPOLY ? { FACET_VARIATION: '', ZF1: '5.0', ZF2: '11.0', ZF3: '3.0', ZF4: '3.4', ZALB: '0.78', ZWARM: 'vec3(1.0)', ZFILL: '0.4' } : { ZF1: '9.0', ZF2: '70.0', ZF3: '4.2', ZF4: '5.0', ZALB: '0.36', ZWARM: 'vec3(1.1, 1.0, 0.84)', ZFILL: '1.0' };
  (mat as any).defines = defs;
  mat.onBeforeCompile = (sh) => {
    sh.uniforms.uZBones = bones; sh.uniforms.uZNight = night; sh.uniforms.uZAmb = amb;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + VERT_DECL + VARY)
      .replace('#include <beginnormal_vertex>', '#include <beginnormal_vertex>\n' + VERT_SKIN)
      .replace('#include <begin_vertex>', VERT_OUT);
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\n' + VARY + FRAG_DECL)
      .replace('#include <color_fragment>', '#include <color_fragment>\n' + FRAG_COLOR)
      .replace('#include <emissivemap_fragment>', '#include <emissivemap_fragment>\n' + FRAG_EMIT)
      .replace('#include <opaque_fragment>', FRAG_FORM + '\n#include <opaque_fragment>')
      // cloth and skin are matte: keep only a little of the sky's mirror image
      .replace('#include <lights_fragment_maps>', '#include <lights_fragment_maps>\n#if defined( RE_IndirectSpecular )\n  radiance *= 0.3;\n#endif\n#if defined( RE_IndirectDiffuse )\n  { float zli = dot(irradiance, vec3(0.3, 0.59, 0.11)); irradiance = mix(irradiance, vec3(zli) * vec3(1.05, 1.0, 0.93), 0.65); }\n  #if defined( USE_ENVMAP ) && defined( STANDARD )\n  { float zlb = dot(iblIrradiance, vec3(0.3, 0.59, 0.11)); iblIrradiance = mix(iblIrradiance, vec3(zlb) * vec3(1.05, 1.0, 0.93), 0.65); }\n  #endif\n#endif');
  };
  mat.customProgramCacheKey = () => 'body-v9-' + (LOWPOLY ? 'f' : 's');

  const depth = new THREE.MeshDepthMaterial({ depthPacking: THREE.RGBADepthPacking });
  depth.onBeforeCompile = (sh) => {
    sh.uniforms.uZBones = bones;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + VERT_DECL)
      .replace('#include <begin_vertex>', 'vec3 objectNormal = vec3(0.0, 1.0, 0.0);\n' + VERT_SKIN);
  };
  depth.customProgramCacheKey = () => 'body-depth-v2';
  return { mat, depth };
}

/** Owns the instanced mesh + bone texture. Each frame: begin(), add() per visible body, end(). */
export class BodyRenderer {
  mesh: THREE.InstancedMesh;
  readonly max: number;
  private data: Float32Array; private tex: THREE.DataTexture; private slot: THREE.InstancedBufferAttribute;
  private n = 0;
  night = { value: 0 };
  /** wrapped ambient on bodies; follows daylight */
  amb = { value: 0.09 };
  private tmpM = new THREE.Matrix4(); private tmpQ = new THREE.Quaternion(); private tmpS = new THREE.Vector3(); private tmpP = new THREE.Vector3();
  /** main camera seen on the previous frame (for cheap CPU culling); null until the first draw */
  cam: THREE.Camera | null = null;

  constructor(scene: THREE.Object3D, max: number) {
    this.max = max;
    const geo = buildZombieGeometry();
    this.data = new Float32Array(max * ROW);
    this.tex = new THREE.DataTexture(this.data, ROW_TEXELS, max, THREE.RGBAFormat, THREE.FloatType);
    this.tex.magFilter = this.tex.minFilter = THREE.NearestFilter; this.tex.generateMipmaps = false; this.tex.needsUpdate = true;
    const slots = new Float32Array(max);
    this.slot = new THREE.InstancedBufferAttribute(slots, 1).setUsage(THREE.DynamicDrawUsage) as THREE.InstancedBufferAttribute;
    this.used = new Int32Array(max);
    geo.setAttribute('aSlot', this.slot);
    const { mat, depth } = makeZombieMaterials({ value: this.tex }, this.night, this.amb);
    this.mesh = new THREE.InstancedMesh(geo, mat, max);
    this.mesh.customDepthMaterial = depth;
    this.mesh.castShadow = true; this.mesh.receiveShadow = true; this.mesh.frustumCulled = false;
    this.mesh.count = 0;
    this.mesh.onBeforeRender = (_r, _s, camera) => { if ((camera as THREE.PerspectiveCamera).isPerspectiveCamera) this.cam = camera; };
    scene.add(this.mesh);
  }
  get vertexCount() { return this.mesh.geometry.attributes.position.count; }
  private owner: (BodyDef | null)[] = []; private used: Int32Array; private frame = 2; private scan = 0;
  /** bodies whose pose was recomputed this frame (the rest reused last frame's row) */
  posed = 0;
  begin() { this.n = 0; this.posed = 0; this.frame++; }
  get count() { return this.n; }
  full() { return this.n >= this.max; }
  private alloc(): number {
    const f = this.frame, used = this.used, max = this.max;
    for (let pass = 0; pass < 2; pass++) for (let i = 0; i < max; i++) {
      const s = (this.scan + i) % max;
      if (used[s] < f - 1 + pass) { this.scan = (s + 1) % max; return s; }
    }
    return -1;
  }
  /**
   * Draw one body this frame. Every body keeps its own row in the bone texture, so far-off or motionless ones
   * can skip re-posing: lod 0 = pose every frame, k > 0 = every (k+1)th frame, -1 = only when new or dirty.
   */
  add(a: BodyAnim, d: BodyDef, x: number, y: number, z: number, yaw: number, scale: number, lod = 0) {
    const n = this.n; if (n >= this.max) return;
    let s = d.slot, fresh = false;
    if (s < 0 || this.owner[s] !== d) { s = this.alloc(); if (s < 0) return; this.owner[s] = d; d.slot = s; fresh = true; }
    this.used[s] = this.frame;
    (this.slot.array as Float32Array)[n] = s;
    this.tmpQ.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, yaw);
    this.tmpM.compose(this.tmpP.set(x, y, z), this.tmpQ, this.tmpS.set(scale, scale, scale));
    this.mesh.setMatrixAt(n, this.tmpM);
    const o = s * ROW;
    if (fresh || lod === 0 || (lod > 0 && (this.frame + s) % (lod + 1) === 0)) { poseBody(a, d, this.data, o); this.posed++; }
    if (fresh || d.dirty) { this.data.set(d.look, o + LOOK_AT); d.dirty = false; }
    this.n = n + 1;
  }
  end() {
    this.mesh.count = this.n;
    this.mesh.instanceMatrix.needsUpdate = true;
    this.mesh.instanceMatrix.clearUpdateRanges(); this.mesh.instanceMatrix.addUpdateRange(0, this.n * 16);
    this.slot.needsUpdate = true; this.slot.clearUpdateRanges(); this.slot.addUpdateRange(0, this.n);
    this.tex.needsUpdate = true;
  }
}
