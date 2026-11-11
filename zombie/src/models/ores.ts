/**
 * REKINDLE — mineral deposits and ore veins.
 *
 * One procedural "ore surface" shader (patched into MeshStandardMaterial) draws each mineral's look in
 * world space: banded iron, malachite staining, coal seams, limestone bedding, sulphur crust, quartz veins,
 * pisolitic bauxite, gold-bearing quartz, tar. Veins get their own albedo / roughness / metalness and a
 * small derivative bump so they read as real mineral bands rather than tints.
 *
 * - getOreMaterial(type): shared per type, used by a deposit's host rocks, loose chunks and pebbles.
 *   Per-vertex `aOre` carries richness (0..1 host rock, 2 = ore chunk, 3 = pure nugget/lump).
 * - getOreVeinInstancedMaterial(base): one material per wild-boulder base material; the mineral and
 *   richness come from the boulder's instanceColor (r = type index, g = richness).
 * - buildOreDeposit(): merged geometry per material (rock+chunks, crystals, ground stain, oil pool).
 */
import * as THREE from 'three';
import { mergeVertices } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { getMaterial, LOWPOLY, facet } from '../render/materials';
import { boxProjectUV, fbm3, merge, noise3 } from './geom';

export type OreType = 'iron_ore' | 'copper_ore' | 'coal' | 'stone' | 'sulfur' | 'f_crude' | 'quartz' | 'bauxite' | 'gold_ore';
/** shader index of each mineral (also the instanceColor.r code for boulder veins) */
export const ORE_INDEX: Record<OreType, number> = { iron_ore: 0, copper_ore: 1, coal: 2, stone: 3, sulfur: 4, quartz: 5, bauxite: 6, gold_ore: 7, f_crude: 8 };

let snowU: { value: number } = { value: 0 };
/** share the vegetation snow uniform so deposits and veins whiten in winter like other rocks */
export function setOreSnowUniform(u: { value: number }) { snowU = u; }

// ---------------------------------------------------------------------------
// GLSL
// ---------------------------------------------------------------------------

const NOISE_GLSL = /* glsl */`
vec3 oHash3(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx) * p.zyx); }
float oHash1(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }
vec3 oMod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 oMod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 oPerm(vec4 x) { return oMod289(((x * 34.0) + 10.0) * x); }
// simplex noise 3D (Ashima / Gustavson), range ~[-1, 1]
float oNoise(vec3 v) {
  const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
  vec3 i = floor(v + dot(v, C.yyy));
  vec3 x0 = v - i + dot(i, C.xxx);
  vec3 g = step(x0.yzx, x0.xyz);
  vec3 l = 1.0 - g;
  vec3 i1 = min(g.xyz, l.zxy);
  vec3 i2 = max(g.xyz, l.zxy);
  vec3 x1 = x0 - i1 + C.xxx;
  vec3 x2 = x0 - i2 + C.yyy;
  vec3 x3 = x0 - 0.5;
  i = oMod289(i);
  vec4 p = oPerm(oPerm(oPerm(i.z + vec4(0.0, i1.z, i2.z, 1.0)) + i.y + vec4(0.0, i1.y, i2.y, 1.0)) + i.x + vec4(0.0, i1.x, i2.x, 1.0));
  vec4 j = p - 49.0 * floor(p * (1.0 / 49.0));
  vec4 x_ = floor(j * (1.0 / 7.0));
  vec4 y_ = floor(j - 7.0 * x_);
  vec4 x = (x_ * 2.0 + 0.5) / 7.0 - 1.0;
  vec4 y = (y_ * 2.0 + 0.5) / 7.0 - 1.0;
  vec4 h = 1.0 - abs(x) - abs(y);
  vec4 b0 = vec4(x.xy, y.xy);
  vec4 b1 = vec4(x.zw, y.zw);
  vec4 s0 = floor(b0) * 2.0 + 1.0;
  vec4 s1 = floor(b1) * 2.0 + 1.0;
  vec4 sh = -step(h, vec4(0.0));
  vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
  vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
  vec3 g0 = vec3(a0.xy, h.x); vec3 g1 = vec3(a0.zw, h.y); vec3 g2 = vec3(a1.xy, h.z); vec3 g3 = vec3(a1.zw, h.w);
  vec4 norm = 1.79284291400159 - 0.85373472095314 * vec4(dot(g0, g0), dot(g1, g1), dot(g2, g2), dot(g3, g3));
  g0 *= norm.x; g1 *= norm.y; g2 *= norm.z; g3 *= norm.w;
  vec4 m = max(0.5 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
  m = m * m;
  return 105.0 * dot(m * m, vec4(dot(g0, x0), dot(g1, x1), dot(g2, x2), dot(g3, x3)));
}
float oFbm(vec3 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 4; i++) { s += a * oNoise(p); p = p * 2.03 + 17.1; a *= 0.5; } return s; }
// cellular noise: x = distance to nearest feature point, y = that cell's random id
vec2 oCell(vec3 p) {
  vec3 i = floor(p), f = fract(p); float d = 8.0, id = 0.0;
  for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
    vec3 g = vec3(float(x), float(y), float(z));
    vec3 r = g + oHash3(i + g) - f; float dd = dot(r, r);
    if (dd < d) { d = dd; id = oHash1(i + g + 7.7); }
  }
  return vec2(sqrt(d), id);
}
// thin sheets of noise cut by the surface -> branching vein lines; w = half-width in noise units
float oVein(vec3 p, float f, float w, float aa) {
  vec3 q = p * f; q += vec3(oNoise(q * 0.45 + 3.1), oNoise(q * 0.45 + 7.7), oNoise(q * 0.45 + 1.3)) * 0.7;
  float v = abs(oNoise(q));
  float e = max(w * 0.45, aa * f * 1.5);
  return 1.0 - smoothstep(w - e, w + e * 0.3, v);
}
`;

const ORE_FRAG_GLSL = /* glsl */`
uniform float uOreSnow; uniform float uOreBase;
varying vec3 vOreP; varying vec3 vOreN; varying vec3 vOreSeed; varying float vOre;
#ifdef ORE_MULTI
varying float vOreType;
#endif
struct OreS { vec3 alb; float rough; float metal; float w; float h; vec3 em; };
${NOISE_GLSL}
vec3 oHost(vec3 c, vec3 base, float tex) {
#ifdef ORE_MULTI
  return mix(base, c * tex, 0.3);
#else
  return c * tex;
#endif
}
OreS oNew(vec3 alb, float rough) { OreS o; o.alb = alb; o.rough = rough; o.metal = 0.0; o.w = 1.0; o.h = 0.0; o.em = vec3(0.0); return o; }
// fade high-frequency features out before they alias
float oFade(float f, float aa) { return clamp(1.6 - aa * f * 3.0, 0.0, 1.0); }

// ---- iron: banded iron formation, red jasper + steel-grey haematite with specular flakes, rust bloom
OreS oreIron(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa) {
  float rich = min(r, 1.0), chunk = step(1.5, r), nug = step(2.5, r);
  vec3 host = oHost(vec3(0.30, 0.17, 0.11), base, tex);
  host = mix(host, oHost(vec3(0.55, 0.33, 0.12), base, tex), smoothstep(-0.1, 0.5, oFbm(p * 1.3 + 3.0)) * 0.55);
  float warp = oNoise(p * 0.55) * 0.3 + oNoise(p * 2.2) * 0.05;
  float b = (dot(p, sd) + warp) * 6.5;
  float s = sin(b * 3.14159);
  float fine = sin(b * 11.0 + oNoise(p * 4.0) * 2.0);
  float zone = smoothstep(0.78 - rich * 0.75, 0.92 - rich * 0.75, oNoise(p * 0.4 + sd * 3.0) * 0.5 + 0.5);
  zone = max(zone, chunk);
  float hem = max(smoothstep(0.05, 0.35, s) * zone, nug);
  float jas = smoothstep(-0.1, -0.45, s) * zone * (0.65 + 0.35 * smoothstep(-0.4, 0.4, fine)) * (1.0 - nug);
  vec3 alb = mix(host, vec3(0.36, 0.06, 0.035) * tex, jas);
  alb = mix(alb, vec3(0.16, 0.155, 0.16) * (0.75 + 0.35 * tex), hem);
  float rust = smoothstep(0.25, 0.75, oFbm(p * 3.0 + 9.0)) * (1.0 - nug * 0.7);
  alb = mix(alb, vec3(0.34, 0.11, 0.03) * tex, rust * hem * 0.55);
  vec2 c = oCell(p * 34.0);
  float gl = smoothstep(0.34, 0.16, c.x) * step(0.5, c.y) * hem * oFade(34.0, aa);
  OreS o = oNew(alb + gl * vec3(0.12), mix(0.86, 0.38, hem) - gl * 0.22);
  o.metal = hem * (0.7 - rust * 0.45) + gl * 0.3;
  o.h = hem * 0.012 - jas * 0.003 + gl * 0.002;
  return o;
}

// ---- copper: green malachite / blue azurite staining, native-copper veins and nuggets
OreS oreCopper(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa) {
  float rich = min(r, 1.0), chunk = step(1.5, r), nug = step(2.5, r);
  vec3 host = oHost(vec3(0.26, 0.27, 0.25), base, tex);
  float st = oFbm(p * 1.5 + 5.0) * 0.5 + 0.5 - n.y * 0.06;
  float stain = max(smoothstep(0.6 - rich * 0.3, 0.7 - rich * 0.3, st), chunk * 0.85);
  float rings = sin(oNoise(p * 2.6) * 16.0) * 0.5 + 0.5;
  vec3 mal = mix(vec3(0.02, 0.22, 0.10), vec3(0.14, 0.55, 0.32), rings);
  float azu = smoothstep(0.3, 0.6, oNoise(p * 2.0 + 21.0)) * stain;
  vec3 alb = mix(host, mal * (0.75 + 0.3 * tex), stain);
  alb = mix(alb, vec3(0.03, 0.10, 0.48) * (0.75 + 0.3 * tex), azu * 0.85);
  float vein = oVein(p, 1.6, 0.05 + rich * 0.06, aa);
  vec2 c = oCell(p * 20.0);
  float nugF = smoothstep(0.3, 0.12, c.x) * step(0.72 - rich * 0.25, c.y) * max(stain * 0.7, vein) * oFade(20.0, aa);
  float cuW = max(max(vein * 0.9, nugF), nug);
  float patina = smoothstep(0.25, 0.6, oNoise(p * 9.0 + 2.0)) * 0.7;
  vec3 cu = mix(vec3(0.92, 0.50, 0.30), vec3(0.55, 0.26, 0.14), smoothstep(0.0, 0.6, oNoise(p * 14.0)));
  alb = mix(alb, mix(cu, mal, patina * (1.0 - vein * 0.5)), cuW);
  OreS o = oNew(alb, mix(mix(0.85, 0.6, stain), 0.33, cuW * (1.0 - patina * 0.6)));
  o.metal = cuW * (1.0 - patina * 0.8);
  o.h = stain * 0.004 + cuW * 0.008 + azu * 0.004;
  return o;
}

// ---- coal: grey shale with thick black glossy seams following the bedding
OreS oreCoal(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa) {
  float rich = min(r, 1.0), chunk = step(1.5, r);
  float lam = sin(dot(p, sd) * 42.0 + oNoise(p * 3.0) * 2.0);
  vec3 host = oHost(vec3(0.30, 0.29, 0.275), base, tex) * (0.93 + 0.07 * lam);
  float ph = dot(p, sd) + oNoise(p * 0.45) * 0.22 + oNoise(p * 2.0) * 0.025;
  float s = abs(fract(ph * 1.35) - 0.5) * 2.0;
  float width = 0.2 + rich * 0.4;
  float seam = max(smoothstep(width + 0.05, width - 0.03, s), chunk);
  // soot washed down from seams
  float soot = smoothstep(width + 0.4, width, s) * 0.35 * (1.0 - seam);
  vec2 c = oCell(p * 13.0);
  vec3 coal = vec3(0.022, 0.022, 0.025) * (0.8 + 0.5 * c.y);
  vec3 alb = mix(host * (1.0 - soot), coal, seam);
  // pyrite specks in the coal
  vec2 c2 = oCell(p * 40.0);
  float py = smoothstep(0.22, 0.1, c2.x) * step(0.93, c2.y) * seam * oFade(40.0, aa);
  alb = mix(alb, vec3(0.75, 0.6, 0.3), py);
  OreS o = oNew(alb, mix(0.88, 0.16 + c.y * 0.22, seam));
  o.metal = seam * 0.12 + py * 0.9;
  o.h = seam * (0.006 + (0.5 - c.x) * 0.012) * (1.0 - chunk * 0.5) - lam * 0.0015;
  return o;
}

// ---- limestone: pale chalky blocks with recessed bedding planes, pitting and faint iron streaks
OreS oreLime(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa) {
  vec3 host = oHost(vec3(0.78, 0.75, 0.66), base, tex * 0.4 + 0.6);
  host = mix(host, vec3(0.62, 0.62, 0.6) * tex, smoothstep(0.1, 0.7, oFbm(p * 0.9 + 2.0)) * 0.45);
  float bed = fract(dot(p, sd) * 2.4 + oNoise(p * 0.7) * 0.15);
  float line = smoothstep(0.07, 0.0, abs(bed - 0.5));
  float streak = smoothstep(0.35, 0.8, oNoise(vec3(p.x * 3.0, p.y * 0.35, p.z * 3.0))) * 0.4;
  vec2 c = oCell(p * 26.0);
  float pit = smoothstep(0.25, 0.12, c.x) * step(0.6, c.y) * oFade(26.0, aa);
  vec2 l = oCell(p * 5.0);
  float lichen = smoothstep(0.3, 0.2, l.x + oNoise(p * 9.0) * 0.06) * step(0.86, l.y) * smoothstep(-0.2, 0.5, n.y);
  vec3 alb = mix(host, vec3(0.70, 0.52, 0.32) * tex, streak);
  alb *= 1.0 - line * 0.35 - pit * 0.25;
  alb = mix(alb, vec3(0.42, 0.44, 0.34), lichen * 0.8);
  OreS o = oNew(alb, 0.95);
  o.h = -line * 0.012 - pit * 0.003;
  return o;
}

// ---- sulphur: bleached altered rock under a crust of granular yellow crystals, faint glow
OreS oreSulfur(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa) {
  float rich = min(r, 1.0), chunk = step(1.5, r);
  vec3 host = oHost(vec3(0.60, 0.58, 0.52), base, tex);
  float crust = smoothstep(0.42 - rich * 0.42, 0.6 - rich * 0.42, n.y * 0.55 + oFbm(p * 2.0 + 1.0) * 0.6 + 0.12);
  crust = max(crust, max(chunk, oVein(p, 1.4, 0.1, aa) * 0.9));
  vec2 c = oCell(p * 22.0);
  float fa = oFade(22.0, aa);
  vec3 sC = mix(vec3(0.95, 0.80, 0.10), mix(vec3(0.75, 0.58, 0.03), vec3(1.0, 0.93, 0.32), c.y), fa);
  sC = mix(sC, vec3(0.85, 0.42, 0.04), smoothstep(0.45, 0.8, oNoise(p * 1.4 + 40.0)) * 0.6);
  float rim = smoothstep(0.0, 0.25, crust) * (1.0 - smoothstep(0.25, 0.6, crust));
  vec3 alb = mix(host, sC, crust);
  alb = mix(alb, vec3(0.9, 0.88, 0.8), rim * 0.5);
  OreS o = oNew(alb, mix(0.9, 0.3 + c.y * 0.3 * fa, crust));
  o.h = crust * (0.008 + (0.5 - c.x) * 0.012 * fa);
  o.em = sC * crust * 0.07;
  return o;
}

// ---- quartz: speckled granite cut by thick milky-white quartz veins
OreS oreQuartz(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa) {
  float rich = min(r, 1.0), chunk = step(1.5, r);
  vec2 c = oCell(p * 28.0);
  float fa = oFade(28.0, aa);
  vec3 host = oHost(vec3(0.44, 0.43, 0.44), base, tex);
  host = mix(host, vec3(0.08, 0.08, 0.09), step(c.y, 0.22) * fa * 0.85);
  host = mix(host, vec3(0.62, 0.46, 0.40) * tex, step(0.82, c.y) * fa * 0.6);
  float v = max(oVein(p, 0.8, 0.08 + rich * 0.1, aa), chunk);
  vec3 q = mix(vec3(0.80, 0.80, 0.83), vec3(0.93, 0.92, 0.95), oNoise(p * 6.0) * 0.5 + 0.5);
  vec3 alb = mix(host, q, v);
  OreS o = oNew(alb, mix(0.8, 0.2, v));
  o.h = v * 0.01;
  return o;
}

// ---- bauxite: rust-orange clay matrix packed with dark rounded pisoliths
OreS oreBauxite(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa) {
  float rich = min(r, 1.0), chunk = step(1.5, r);
  vec3 host = oHost(vec3(0.60, 0.29, 0.12), base, tex * 0.5 + 0.5);
  host = mix(host, vec3(0.78, 0.56, 0.34) * (tex * 0.5 + 0.5), smoothstep(0.2, 0.7, oFbm(p * 1.2 + 8.0)) * 0.5);
  vec2 c = oCell(p * 8.0);
  float rr = 0.3 + 0.12 * c.y;
  float on = step(0.5 - rich * 0.45 - chunk * 0.3, fract(c.y * 7.31));
  float pis = smoothstep(rr, rr - 0.05, c.x) * on * oFade(8.0, aa);
  float ring = sin(c.x * 55.0) * 0.5 + 0.5;
  vec3 pc = mix(vec3(0.30, 0.10, 0.05), vec3(0.48, 0.19, 0.08), ring) * (0.8 + 0.4 * fract(c.y * 3.7));
  vec3 alb = mix(host, pc, pis);
  OreS o = oNew(alb, mix(0.9, 0.62, pis));
  o.h = pis * max(rr - c.x, 0.0) * 0.06;
  return o;
}

// ---- gold: dark schist with white quartz veins carrying bright gold flecks, limonite halo
OreS oreGold(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa) {
  float rich = min(r, 1.0), chunk = step(1.5, r), nug = step(2.5, r);
  vec3 host = oHost(vec3(0.24, 0.255, 0.24), base, tex) * (0.92 + 0.08 * sin(dot(p, sd) * 30.0 + oNoise(p * 2.0) * 3.0));
  float w = 0.07 + rich * 0.09;
  float v = max(oVein(p, 1.0, w, aa), chunk);
  float halo = clamp(oVein(p, 1.0, w * 2.6, aa) - v, 0.0, 1.0);
  vec3 alb = mix(host, vec3(0.45, 0.28, 0.10) * tex, halo * 0.7);
  alb = mix(alb, vec3(0.86, 0.84, 0.79), v);
  vec2 c = oCell(p * 24.0);
  vec2 c2 = oCell(p * 8.0 + 3.0);
  float fleck = smoothstep(0.32, 0.12, c.x) * step(0.8 - rich * 0.3, c.y) * oFade(24.0, aa);
  fleck = max(fleck, smoothstep(0.22, 0.12, c2.x) * step(0.88, c2.y));
  fleck = max(fleck * v, nug);
  vec3 au = vec3(1.0, 0.72, 0.28) * (0.85 + 0.25 * oNoise(p * 30.0));
  alb = mix(alb, au, fleck);
  OreS o = oNew(alb, mix(mix(0.85, 0.3, v), 0.16, fleck));
  o.metal = fleck;
  o.h = v * 0.008 + fleck * 0.004;
  return o;
}

// ---- crude seep: dark oil-sand rock coated in wet tar from the base up
OreS oreOil(vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa, float hgt) {
  float rich = min(r, 1.0), chunk = step(1.5, r);
  vec3 host = oHost(vec3(0.26, 0.23, 0.2), base, tex);
  float tar = max(smoothstep(0.35, 0.6, oFbm(p * 1.6) * 0.8 + (0.5 - hgt) * 1.1 + rich * 0.35), chunk);
  float drip = smoothstep(0.55, 0.8, oNoise(vec3(p.x * 6.0, p.y * 0.8, p.z * 6.0))) * smoothstep(1.4, 0.2, hgt);
  tar = max(tar, drip);
  vec3 alb = mix(host, vec3(0.01, 0.009, 0.008), tar);
  OreS o = oNew(alb, mix(0.85, 0.07, tar));
  o.h = tar * 0.004;
  return o;
}

OreS oreSurface(int t, vec3 p, vec3 n, float r, vec3 base, float tex, vec3 sd, float aa, float hgt) {
#if defined(ORE_MULTI) || ORE_TYPE == 0
  if (t == 0) return oreIron(p, n, r, base, tex, sd, aa);
#endif
#if defined(ORE_MULTI) || ORE_TYPE == 1
  if (t == 1) return oreCopper(p, n, r, base, tex, sd, aa);
#endif
#if defined(ORE_MULTI) || ORE_TYPE == 2
  if (t == 2) return oreCoal(p, n, r, base, tex, sd, aa);
#endif
#if defined(ORE_MULTI) || ORE_TYPE == 3
  if (t == 3) return oreLime(p, n, r, base, tex, sd, aa);
#endif
#if defined(ORE_MULTI) || ORE_TYPE == 4
  if (t == 4) return oreSulfur(p, n, r, base, tex, sd, aa);
#endif
#if defined(ORE_MULTI) || ORE_TYPE == 5
  if (t == 5) return oreQuartz(p, n, r, base, tex, sd, aa);
#endif
#if defined(ORE_MULTI) || ORE_TYPE == 6
  if (t == 6) return oreBauxite(p, n, r, base, tex, sd, aa);
#endif
#if defined(ORE_MULTI) || ORE_TYPE == 7
  if (t == 7) return oreGold(p, n, r, base, tex, sd, aa);
#endif
#if defined(ORE_MULTI) || ORE_TYPE == 8
  if (t == 8) return oreOil(p, n, r, base, tex, sd, aa, hgt);
#endif
  return oNew(base, 0.9);
}
`;

const ORE_VERT_PARS = /* glsl */`
varying vec3 vOreP; varying vec3 vOreN; varying vec3 vOreSeed; varying float vOre;
#ifdef ORE_MULTI
varying float vOreType;
#else
attribute float aOre;
#endif
`;
const ORE_VERT_MAIN = /* glsl */`
{
  vec4 oWP = vec4(transformed, 1.0);
  vec3 oWN = objectNormal;
  vec4 oOrg = vec4(0.0, 0.0, 0.0, 1.0);
#ifdef USE_INSTANCING
  oWP = instanceMatrix * oWP; oWN = mat3(instanceMatrix) * oWN; oOrg = instanceMatrix * oOrg;
#endif
  vOreP = (modelMatrix * oWP).xyz;
  vOreN = normalize(mat3(modelMatrix) * oWN);
  vOreSeed = (modelMatrix * oOrg).xyz;
#ifdef ORE_MULTI
  vOreType = instanceColor.r; vOre = instanceColor.g;
#else
  vOre = aOre;
#endif
}
`;

function patchOre(m: THREE.MeshStandardMaterial, key: string, baseAvg: number) {
  const uniforms = { uOreSnow: snowU, uOreBase: { value: baseAvg } };
  m.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, uniforms);
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + ORE_VERT_PARS)
      .replace('#include <color_vertex>', `#include <color_vertex>
#ifdef ORE_MULTI
  vColor = vec4(1.0);
  #ifdef USE_COLOR
  vColor.rgb *= color;
  #endif
#endif`)
      .replace('#include <project_vertex>', '#include <project_vertex>\n' + ORE_VERT_MAIN);
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\n' + ORE_FRAG_GLSL)
      .replace('#include <color_fragment>', `#include <color_fragment>
  float oAA = length(fwidth(vOreP));
  vec3 oN = normalize(vOreN);
  float oSeed = dot(floor(vOreSeed * 0.5), vec3(0.137, 0.0, 0.291));
  vec3 oSD = normalize(vec3(sin(oSeed * 17.0) * 0.45, 1.0, cos(oSeed * 23.0) * 0.45));
  float oTex = clamp(dot(diffuseColor.rgb, vec3(0.3333)) / uOreBase, 0.55, 1.5);
#ifdef ORE_MULTI
  int oT = int(vOreType + 0.5);
#else
  const int oT = ORE_TYPE;
#endif
  OreS oreS = oreSurface(oT, vOreP, oN, vOre, diffuseColor.rgb, oTex, oSD, oAA, vOreP.y - vOreSeed.y);
  float oSnow = uOreSnow * smoothstep(0.35, 0.75, oN.y + oNoise(vOreP * 2.0) * 0.15);
  diffuseColor.rgb = mix(oreS.alb, vec3(0.92, 0.94, 0.97), oSnow);`)
      .replace('#include <metalnessmap_fragment>', `#include <metalnessmap_fragment>
  roughnessFactor = mix(mix(roughnessFactor, oreS.rough, oreS.w), 0.8, oSnow);
  metalnessFactor = mix(mix(metalnessFactor, oreS.metal, oreS.w), 0.0, oSnow);`)
      .replace('#include <normal_fragment_maps>', `#include <normal_fragment_maps>
  {
    float oH = oreS.h * (1.0 - oSnow);
    vec3 oDx = dFdx(-vViewPosition), oDy = dFdy(-vViewPosition);
    float oHx = dFdx(oH), oHy = dFdy(oH);
    vec3 oR1 = cross(oDy, normal), oR2 = cross(normal, oDx);
    float oDet = dot(oDx, oR1);
    vec3 oG = sign(oDet) * (oHx * oR1 + oHy * oR2);
    normal = normalize(abs(oDet) * normal - oG);
  }`)
      .replace('#include <emissivemap_fragment>', '#include <emissivemap_fragment>\n  totalEmissiveRadiance += oreS.em * (1.0 - oSnow);');
  };
  m.customProgramCacheKey = () => key;
}

/** average linear brightness of the base material's albedo, used to extract texture detail */
function baseAverage(base: THREE.MeshStandardMaterial) {
  const c = base.color; const l = (c.r + c.g + c.b) / 3;
  return Math.max(0.02, base.map ? l * 0.075 : l);
}

const cache = new Map<string, THREE.Material>();

/** host rock + loose chunk material for a deposit type (shared by every node of that type) */
export function getOreMaterial(type: OreType): THREE.MeshStandardMaterial {
  const key = 'ore:' + type;
  let m = cache.get(key) as THREE.MeshStandardMaterial | undefined;
  if (!m) {
    const base = getMaterial('rock');
    m = base.clone();
    m.name = 'ore_' + type;
    m.defines = { ...(m.defines ?? {}), ORE_TYPE: ORE_INDEX[type] };
    facet(m);
    patchOre(m, `ore-${type}-${LOWPOLY ? 'lp' : 'r'}`, baseAverage(base));
    cache.set(key, m);
  }
  return m;
}

/** wild-boulder vein material: mineral from instanceColor (r = ORE_INDEX, g = richness) */
export function getOreVeinInstancedMaterial(base: THREE.MeshStandardMaterial): THREE.MeshStandardMaterial {
  const key = 'vein:' + base.uuid;
  let m = cache.get(key) as THREE.MeshStandardMaterial | undefined;
  if (!m) {
    m = base.clone();
    m.name = base.name + '_veins';
    m.defines = { ...(m.defines ?? {}), ORE_MULTI: '', ORE_TYPE: -1 };
    if (LOWPOLY) facet(m);
    patchOre(m, `ore-multi-${LOWPOLY ? 'lp' : 'r'}-${base.vertexColors ? 'vc' : ''}`, baseAverage(base));
    cache.set(key, m);
  }
  return m;
}

function crystalMaterial(type: OreType): THREE.MeshStandardMaterial | null {
  const key = 'cry:' + type;
  let m = cache.get(key) as THREE.MeshStandardMaterial | undefined;
  if (m) return m;
  if (type === 'quartz') {
    m = new THREE.MeshStandardMaterial({ name: 'quartz_crystal', vertexColors: true, roughness: 0.06, metalness: 0, envMapIntensity: 1.8, flatShading: true });
    // fake translucency: light leaks through thin edges, brighter where the view grazes the facets
    m.onBeforeCompile = (sh) => {
      sh.fragmentShader = sh.fragmentShader.replace('#include <emissivemap_fragment>', `#include <emissivemap_fragment>
  { float nv = clamp(dot(normal, normalize(vViewPosition)), 0.0, 1.0); totalEmissiveRadiance += diffuseColor.rgb * (0.05 + 0.1 * (1.0 - nv)); }`);
    };
    m.customProgramCacheKey = () => 'ore-quartz-crystal';
  } else if (type === 'sulfur') {
    m = new THREE.MeshStandardMaterial({ name: 'sulfur_crystal', vertexColors: true, roughness: 0.28, metalness: 0, emissive: 0x3a2c00, emissiveIntensity: 0.6, flatShading: true });
  } else return null;
  facet(m);
  cache.set(key, m);
  return m;
}

const STAIN: Partial<Record<OreType, [number, number, number, number]>> = {
  // [colour a, colour b, roughness, strength]
  iron_ore: [0x6a2e14, 0x8a5a2a, 0.95, 0.8],
  copper_ore: [0x3a4a3a, 0x2f6a4a, 0.95, 0.6],
  coal: [0x0c0c0d, 0x222220, 0.9, 0.85],
  stone: [0xbcb6a6, 0x9c968a, 0.95, 0.65],
  sulfur: [0xc8b040, 0xe8e2c8, 0.9, 0.7],
  quartz: [0xa8a6a2, 0xd8d6d4, 0.9, 0.55],
  bauxite: [0x8a3412, 0xb0602a, 0.95, 0.85],
  gold_ore: [0x8a7448, 0x5a4a30, 0.95, 0.6],
  f_crude: [0x060504, 0x1a1510, 0.18, 0.95],
};

function stainMaterial(type: OreType): THREE.MeshStandardMaterial {
  const key = 'stain:' + type;
  let m = cache.get(key) as THREE.MeshStandardMaterial | undefined;
  if (m) return m;
  const s = STAIN[type]!;
  m = new THREE.MeshStandardMaterial({ name: 'ore_stain_' + type, color: 0xffffff, roughness: s[2], metalness: 0, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -4 });
  const u = { uC1: { value: new THREE.Color(s[0]) }, uC2: { value: new THREE.Color(s[1]) }, uStr: { value: s[3] }, uOreSnow: snowU };
  m.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, u);
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nattribute float aR; varying float vOreR; varying vec3 vOreP;')
      .replace('#include <project_vertex>', '#include <project_vertex>\nvOreR = aR; vOreP = (modelMatrix * vec4(transformed, 1.0)).xyz;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\nuniform vec3 uC1; uniform vec3 uC2; uniform float uStr; uniform float uOreSnow; varying float vOreR; varying vec3 vOreP;\n' + NOISE_GLSL)
      .replace('#include <color_fragment>', `#include <color_fragment>
  float sE = vOreR + oFbm(vOreP * 0.6) * 0.32;
  float sA = 1.0 - smoothstep(0.42, 1.0, sE);
  float sPatch = smoothstep(-0.4, 0.3, oFbm(vOreP * 2.0 + 4.0));
  vec2 sC = oCell(vOreP * 9.0);
  vec3 sCol = mix(uC1, uC2, smoothstep(-0.35, 0.45, oNoise(vOreP * 1.2 + 2.0)));
  sCol *= 0.8 + 0.35 * sC.y * step(sC.x, 0.38);
  diffuseColor.rgb = sCol;
  diffuseColor.a = sA * (0.5 + 0.5 * sPatch) * uStr * (1.0 - uOreSnow * 0.8);`)
      .replace('#include <roughnessmap_fragment>', `#include <roughnessmap_fragment>
  roughnessFactor = mix(roughnessFactor, 0.95, smoothstep(0.2, 0.8, vOreR));`);
  };
  m.customProgramCacheKey = () => 'ore-stain';
  cache.set(key, m);
  return m;
}

function oilPoolMaterial(): THREE.MeshStandardMaterial {
  let m = cache.get('oilpool') as THREE.MeshStandardMaterial | undefined;
  if (m) return m;
  const p = new THREE.MeshPhysicalMaterial({
    name: 'crude_pool', color: 0x030303, roughness: 0.04, metalness: 0, envMapIntensity: 1.4,
    iridescence: 1, iridescenceIOR: 1.5, iridescenceThicknessRange: [140, 560],
  });
  p.onBeforeCompile = (sh) => {
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vOreP;')
      .replace('#include <project_vertex>', '#include <project_vertex>\nvOreP = (modelMatrix * vec4(transformed, 1.0)).xyz;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vOreP;\n' + NOISE_GLSL)
      .replace('#include <lights_physical_fragment>', `#include <lights_physical_fragment>
  #ifdef USE_IRIDESCENCE
  material.iridescenceThickness = mix(140.0, 560.0, oFbm(vOreP * 0.8) * 0.5 + 0.5);
  material.iridescence *= smoothstep(-0.25, 0.35, oNoise(vOreP * 0.55 + 5.0)) * 0.85;
  #endif`);
  };
  p.customProgramCacheKey = () => 'ore-oilpool';
  m = p;
  cache.set('oilpool', m);
  return m;
}

// ---------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------

type Rand = () => number;
const range = (r: Rand, a: number, b: number) => a + (b - a) * r();

/** chiselled boulder: displaced icosphere, smooth+flat blended normals, box-projected UVs */
function boulderGeo(r: Rand, detail: number, sx: number, sy: number, sz: number, rough = 0.32): THREE.BufferGeometry {
  let g: THREE.BufferGeometry = new THREE.IcosahedronGeometry(1, detail);
  g.deleteAttribute('uv'); g.deleteAttribute('normal');
  g = mergeVertices(g);
  const off = r() * 100;
  const p = g.getAttribute('position') as THREE.BufferAttribute;
  const v = new THREE.Vector3();
  for (let i = 0; i < p.count; i++) {
    v.fromBufferAttribute(p, i);
    const n = fbm3(v.x * 1.3 + off, v.y * 1.3, v.z * 1.3 - off, 4);
    const q = Math.round(n * 4) / 4;
    const k = 1 + n * rough + (q - n) * 0.3;
    v.multiplyScalar(k);
    v.set(v.x * sx, v.y * sy, v.z * sz);
    if (v.y < -sy * 0.3) v.y = -sy * 0.3 + (v.y + sy * 0.3) * 0.25;
    p.setXYZ(i, v.x, v.y, v.z);
  }
  return finishRock(g);
}

/** stratified slab for ledges (coal, iron): flat, slightly wavy plate */
function slabGeo(r: Rand, sx: number, sy: number, sz: number): THREE.BufferGeometry {
  let g: THREE.BufferGeometry = new THREE.BoxGeometry(1, 1, 1, 5, 2, 5);
  g.deleteAttribute('uv'); g.deleteAttribute('normal');
  g = mergeVertices(g);
  const off = r() * 100;
  const p = g.getAttribute('position') as THREE.BufferAttribute;
  for (let i = 0; i < p.count; i++) {
    let x = p.getX(i), y = p.getY(i), z = p.getZ(i);
    // round the plan outline, keep top/bottom flat-ish
    const rr = Math.hypot(x, z) || 1, m = Math.max(Math.abs(x), Math.abs(z));
    x = x * (m / rr * 0.55 + 0.45); z = z * (m / rr * 0.55 + 0.45);
    const n = noise3(x * 2.2 + off, y, z * 2.2 - off);
    const edge = Math.max(Math.abs(x), Math.abs(z)) * 2;
    x *= 1 + n * 0.25; z *= 1 + n * 0.25;
    y = y * (1 - edge * 0.25 * (y > 0 ? 1 : 0)) + noise3(x * 3 - off, 0, z * 3) * 0.12;
    p.setXYZ(i, x * sx, y * sy, z * sz);
  }
  return finishRock(g);
}

/** rounded block (limestone) */
function blockGeo(r: Rand, sx: number, sy: number, sz: number): THREE.BufferGeometry {
  let g: THREE.BufferGeometry = new THREE.BoxGeometry(1, 1, 1, 4, 4, 4);
  g.deleteAttribute('uv'); g.deleteAttribute('normal');
  g = mergeVertices(g);
  const off = r() * 100, rb = 0.12;
  const p = g.getAttribute('position') as THREE.BufferAttribute;
  const v = new THREE.Vector3(), q = new THREE.Vector3();
  for (let i = 0; i < p.count; i++) {
    v.fromBufferAttribute(p, i);
    q.set(THREE.MathUtils.clamp(v.x, -0.5 + rb, 0.5 - rb), THREE.MathUtils.clamp(v.y, -0.5 + rb, 0.5 - rb), THREE.MathUtils.clamp(v.z, -0.5 + rb, 0.5 - rb));
    const d = v.clone().sub(q); if (d.lengthSq() > 1e-8) d.setLength(rb);
    v.copy(q).add(d);
    const n = noise3(v.x * 3 + off, v.y * 3, v.z * 3 - off);
    v.multiplyScalar(1 + n * 0.07);
    p.setXYZ(i, v.x * sx, v.y * sy, v.z * sz);
  }
  return finishRock(g, 0.7);
}

function finishRock(g: THREE.BufferGeometry, flatMix = 0.5): THREE.BufferGeometry {
  g.computeVertexNormals();
  const smooth = g.toNonIndexed();
  const flat = smooth.clone(); flat.computeVertexNormals();
  const sn = smooth.getAttribute('normal') as THREE.BufferAttribute, fn = flat.getAttribute('normal');
  for (let i = 0; i < sn.count; i++) {
    const nx = sn.getX(i) * (1 - flatMix) + fn.getX(i) * flatMix, ny = sn.getY(i) * (1 - flatMix) + fn.getY(i) * flatMix, nz = sn.getZ(i) * (1 - flatMix) + fn.getZ(i) * flatMix;
    const l = Math.hypot(nx, ny, nz) || 1;
    sn.setXYZ(i, nx / l, ny / l, nz / l);
  }
  return smooth;
}

/** hexagonal quartz prism with pyramidal termination; milky base fading to a clear tip */
function hexCrystal(rad: number, len: number, smoky: number): THREE.BufferGeometry {
  const pts = [new THREE.Vector2(0.0001, -len * 0.15), new THREE.Vector2(rad * 0.95, -len * 0.15), new THREE.Vector2(rad, len * 0.72), new THREE.Vector2(0.0001, len)];
  const g = new THREE.LatheGeometry(pts, 6).toNonIndexed();
  g.deleteAttribute('uv');
  g.computeVertexNormals();
  const p = g.getAttribute('position');
  const col = new Float32Array(p.count * 3);
  for (let i = 0; i < p.count; i++) {
    const t = THREE.MathUtils.clamp(p.getY(i) / len, 0, 1);
    const milky = 0.92 - t * 0.42;
    col[i * 3] = milky * (1 - smoky * 0.35); col[i * 3 + 1] = milky * (1 - smoky * 0.38); col[i * 3 + 2] = Math.min(1, milky * 1.04 * (1 - smoky * 0.3));
  }
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return g;
}

/** orthorhombic sulphur bipyramid */
function sulfurCrystal(s: number, shade: number): THREE.BufferGeometry {
  const g = new THREE.OctahedronGeometry(1, 0); g.deleteAttribute('uv');
  g.scale(0.55 * s, s, 0.38 * s);
  g.computeVertexNormals();
  const p = g.getAttribute('position');
  const col = new Float32Array(p.count * 3);
  for (let i = 0; i < p.count; i++) { col[i * 3] = 1.0 * shade; col[i * 3 + 1] = (0.82 + 0.1 * shade) * shade; col[i * 3 + 2] = 0.12 * shade; }
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return g;
}

function setOre(g: THREE.BufferGeometry, v: number) {
  const n = g.getAttribute('position').count;
  g.setAttribute('aOre', new THREE.BufferAttribute(new Float32Array(n).fill(v), 1));
  return g;
}

function place(g: THREE.BufferGeometry, x: number, y: number, z: number, ry: number, rx = 0, rz = 0, s = 1) {
  const m = new THREE.Matrix4().compose(new THREE.Vector3(x, y, z), new THREE.Quaternion().setFromEuler(new THREE.Euler(rx, ry, rz)), new THREE.Vector3(s, s, s));
  g.applyMatrix4(m);
  return g;
}

/** highest vertex of a placed geometry (for perching crystals on top of a rock) */
function topOf(g: THREE.BufferGeometry) {
  const p = g.getAttribute('position'); let best = 0;
  for (let i = 1; i < p.count; i++) if (p.getY(i) > p.getY(best)) best = i;
  return new THREE.Vector3(p.getX(best), p.getY(best), p.getZ(best));
}

/** conforming disc on the terrain for the stained ground around a deposit */
function stainDisc(R: number, ground: (x: number, z: number) => number, lift: number): THREE.BufferGeometry {
  const rings = 9, segs = 30;
  const pos: number[] = [0, ground(0, 0) + lift, 0], ar: number[] = [0], idx: number[] = [];
  for (let i = 1; i <= rings; i++) for (let j = 0; j < segs; j++) {
    const rr = (i / rings) * R, a = (j / segs) * Math.PI * 2;
    const x = Math.cos(a) * rr, z = Math.sin(a) * rr;
    pos.push(x, ground(x, z) + lift, z); ar.push(i / rings);
  }
  for (let j = 0; j < segs; j++) idx.push(0, 1 + ((j + 1) % segs), 1 + j);
  for (let i = 1; i < rings; i++) for (let j = 0; j < segs; j++) {
    const a = 1 + (i - 1) * segs + j, b = 1 + (i - 1) * segs + (j + 1) % segs, c = 1 + i * segs + j, d = 1 + i * segs + (j + 1) % segs;
    idx.push(a, b, c, b, d, c);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('aR', new THREE.Float32BufferAttribute(ar, 1));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

/** irregular conforming pool of crude with a few tar bubbles */
function oilPool(r: Rand, R: number, ground: (x: number, z: number) => number): THREE.BufferGeometry {
  const segs = 28, rings = 4, off = r() * 50;
  const edge: number[] = [];
  for (let j = 0; j < segs; j++) { const a = (j / segs) * Math.PI * 2; edge.push(R * (0.75 + 0.35 * (noise3(Math.cos(a) * 1.4 + off, 0, Math.sin(a) * 1.4) * 0.5 + 0.5))); }
  const pos: number[] = [0, ground(0, 0) + 0.035, 0], idx: number[] = [];
  for (let i = 1; i <= rings; i++) for (let j = 0; j < segs; j++) {
    const a = (j / segs) * Math.PI * 2, rr = edge[j] * (i / rings);
    const x = Math.cos(a) * rr, z = Math.sin(a) * rr;
    pos.push(x, ground(x, z) + 0.035 + (i === rings ? -0.01 : 0), z);
  }
  for (let j = 0; j < segs; j++) idx.push(0, 1 + ((j + 1) % segs), 1 + j);
  for (let i = 1; i < rings; i++) for (let j = 0; j < segs; j++) {
    const a = 1 + (i - 1) * segs + j, b = 1 + (i - 1) * segs + (j + 1) % segs, c = 1 + i * segs + j, d = 1 + i * segs + (j + 1) % segs;
    idx.push(a, b, c, b, d, c);
  }
  let g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setIndex(idx);
  g.computeVertexNormals();
  const parts = [g];
  for (let i = 0; i < 4; i++) {
    const a = r() * Math.PI * 2, rr = r() * R * 0.5, s = range(r, 0.05, 0.14);
    const b = new THREE.SphereGeometry(s, 10, 5, 0, Math.PI * 2, 0, Math.PI / 2); b.deleteAttribute('uv');
    b.scale(1, 0.6, 1);
    const x = Math.cos(a) * rr, z = Math.sin(a) * rr;
    parts.push(place(b, x, ground(x, z) + 0.03, z, 0) as THREE.BufferGeometry);
  }
  g = merge(parts);
  return g;
}

export interface DepositOpts { type: OreType; purity: number; rand: Rand; ground: (x: number, z: number) => number }

/**
 * Build one deposit, centred on (0, 0) in local space with y = 0 at the node's terrain height.
 * `ground(x, z)` returns the local terrain height. Footprint ~4–6 m, top below ~1.5 m so a miner fits.
 */
export function buildOreDeposit(o: DepositOpts): THREE.Group {
  const { type, purity, rand: r, ground } = o;
  const size = purity >= 2 ? 1.25 : purity < 1 ? 0.78 : 1;
  const rich = purity >= 2 ? 1 : purity < 1 ? 0.3 : 0.62;
  const grp = new THREE.Group();
  const rockG: THREE.BufferGeometry[] = [], cryG: THREE.BufferGeometry[] = [];
  const style = type === 'coal' || type === 'iron_ore' ? 'ledge' : type === 'stone' ? 'block' : 'boulder';
  const tilt = range(r, -0.28, 0.28), tiltAz = r() * Math.PI * 2;
  const host = (g: THREE.BufferGeometry) => { boxProjectUV(g, 0.5); setOre(g, rich); rockG.push(g); return g; };
  const ore = (g: THREE.BufferGeometry, v = 2) => { boxProjectUV(g, 0.5); setOre(g, v); rockG.push(g); return g; };
  const tops: THREE.Vector3[] = [];

  if (type === 'f_crude') {
    // seep: a glossy pool in a tar-stained hollow, ringed by low oil-sand rocks
    const R = 1.5 * size;
    const pool = new THREE.Mesh(oilPool(r, R, ground), oilPoolMaterial());
    pool.receiveShadow = true; grp.add(pool);
    const n = 5 + Math.round(purity * 2);
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2 + range(r, -0.3, 0.3), d = R * range(r, 1.0, 1.45);
      const x = Math.cos(a) * d, z = Math.sin(a) * d, s = range(r, 0.25, 0.55) * size;
      tops.push(topOf(host(place(boulderGeo(r, 1, s * 1.2, s * 0.6, s), x, ground(x, z) - s * 0.15, z, r() * 6))));
    }
    for (let i = 0; i < 6 + purity * 4; i++) {
      const a = r() * Math.PI * 2, d = R * range(r, 0.9, 1.9), x = Math.cos(a) * d, z = Math.sin(a) * d, s = range(r, 0.05, 0.14);
      ore(place(boulderGeo(r, 0, s, s * 0.7, s * 0.9, 0.4), x, ground(x, z), z, r() * 6));
    }
  } else {
    // --- host outcrop
    if (style === 'ledge') {
      // dipping strata: stacked slabs stepping down from the centre, all sharing the node's tilt
      const layers = 3;
      for (let i = 0; i < layers; i++) {
        const w = (2.3 - i * 0.45) * size, d = (1.8 - i * 0.3) * size, h = (0.42 + r() * 0.1) * size;
        const ox = range(r, -0.3, 0.3) * size, oz = range(r, -0.3, 0.3) * size;
        const y = ground(ox, oz) + h * 0.15 + i * h * 0.8;
        const g = place(slabGeo(r, w, h, d), ox, y, oz, tiltAz + i * 0.25, Math.cos(tiltAz) * tilt, Math.sin(tiltAz) * tilt);
        host(g); if (i === layers - 1) tops.push(topOf(g));
      }
      for (let i = 0; i < 3 + purity; i++) {
        const a = r() * Math.PI * 2, dd = range(r, 1.6, 2.4) * size, x = Math.cos(a) * dd, z = Math.sin(a) * dd;
        const s = range(r, 0.35, 0.6) * size;
        tops.push(topOf(host(place(slabGeo(r, s * 1.8, s * 0.55, s * 1.3), x, ground(x, z) - s * 0.12, z, r() * 6, Math.cos(tiltAz) * tilt, Math.sin(tiltAz) * tilt))));
      }
    } else if (style === 'block') {
      const n = 5 + Math.round(purity * 2);
      for (let i = 0; i < n; i++) {
        const a = r() * Math.PI * 2, dd = i === 0 ? 0 : range(r, 0.9, 2.2) * size, x = Math.cos(a) * dd, z = Math.sin(a) * dd;
        const s = (i === 0 ? 1.15 : range(r, 0.45, 0.85)) * size;
        const g = place(blockGeo(r, s * range(r, 1.0, 1.5), s * range(r, 0.55, 0.8), s * range(r, 0.8, 1.2)), x, ground(x, z) + s * 0.18, z, r() * 6, range(r, -0.12, 0.12), range(r, -0.12, 0.12));
        tops.push(topOf(host(g)));
      }
      // a stacked block on the main one: a little ruined-wall outcrop
      const t0 = tops[0];
      host(place(blockGeo(r, 0.7 * size, 0.4 * size, 0.55 * size), t0.x * 0.5, t0.y + 0.08, t0.z * 0.5, r() * 6, 0.05, -0.05));
    } else {
      const n = 4 + Math.round(purity * 1.5);
      for (let i = 0; i < n; i++) {
        const a = r() * Math.PI * 2, dd = i === 0 ? range(r, 0, 0.3) : range(r, 1.0, 2.2) * size, x = Math.cos(a) * dd, z = Math.sin(a) * dd;
        const s = (i === 0 ? 1.15 : range(r, 0.45, 0.8)) * size;
        const sy = (i === 0 ? 0.82 : 0.7) * s;
        const g = place(boulderGeo(r, i === 0 ? 3 : 2, s * range(r, 1.0, 1.3), sy, s * range(r, 0.85, 1.1)), x, ground(x, z) + sy * 0.12, z, r() * 6);
        tops.push(topOf(host(g)));
      }
    }

    // --- loose ore chunks heaped around the outcrop
    const chunks = Math.round(5 + purity * 5);
    for (let i = 0; i < chunks; i++) {
      const a = r() * Math.PI * 2, dd = range(r, 1.0, 2.7) * size, x = Math.cos(a) * dd, z = Math.sin(a) * dd;
      const s = range(r, 0.14, 0.32) * (0.8 + size * 0.2);
      const g = style === 'block' ? blockGeo(r, s * 1.3, s * 0.8, s) : boulderGeo(r, 1, s * 1.1, s * 0.75, s, 0.45);
      ore(place(g, x, ground(x, z) + s * 0.25, z, r() * 6, r(), r()));
    }
    // --- pebbles and grit spread over the stain
    const pebbles = Math.round(14 + purity * 10);
    for (let i = 0; i < pebbles; i++) {
      const a = r() * Math.PI * 2, dd = Math.sqrt(r()) * 3.2 * size, x = Math.cos(a) * dd, z = Math.sin(a) * dd;
      const s = range(r, 0.035, 0.1);
      ore(place(boulderGeo(r, 0, s, s * 0.6, s * 0.85, 0.5), x, ground(x, z) + s * 0.15, z, r() * 6), r() < 0.6 ? 2 : rich);
    }
    // --- pure metal: nuggets / haematite lumps / copper
    if (type === 'gold_ore' || type === 'copper_ore' || type === 'iron_ore') {
      for (let i = 0; i < 2 + purity * 2; i++) {
        const a = r() * Math.PI * 2, dd = range(r, 0.8, 2.4) * size, x = Math.cos(a) * dd, z = Math.sin(a) * dd;
        const s = type === 'gold_ore' ? range(r, 0.03, 0.06) : range(r, 0.06, 0.12);
        ore(place(boulderGeo(r, 1, s * 1.3, s * 0.7, s, 0.6), x, ground(x, z) + s * 0.3, z, r() * 6), 3);
      }
    }

    // --- crystals
    if (type === 'quartz') {
      const clusters = 2 + Math.round(purity * 2);
      for (let c = 0; c < clusters; c++) {
        let cx: number, cy: number, cz: number;
        if (c < tops.length && c < 2) { const t = tops[c]; cx = t.x; cy = t.y - 0.08; cz = t.z; }
        else { const a = r() * Math.PI * 2, dd = range(r, 0.9, 2.2) * size; cx = Math.cos(a) * dd; cz = Math.sin(a) * dd; cy = ground(cx, cz) - 0.03; }
        const n = 5 + Math.round(r() * 6 + purity * 2);
        const smoky = r() < 0.3 ? r() : 0;
        for (let i = 0; i < n; i++) {
          const len = range(r, 0.18, 0.55) * size * (i === 0 ? 1.4 : 1);
          const g = hexCrystal(len * range(r, 0.13, 0.2), len, smoky);
          const tiltA = i === 0 ? range(r, 0, 0.15) : range(r, 0.2, 0.75), az = r() * Math.PI * 2;
          cryG.push(place(g, cx + range(r, -0.08, 0.08), cy, cz + range(r, -0.08, 0.08), r() * 6, Math.cos(az) * tiltA, Math.sin(az) * tiltA));
        }
      }
    } else if (type === 'sulfur') {
      const clusters = 3 + Math.round(purity * 2);
      for (let c = 0; c < clusters; c++) {
        let cx: number, cy: number, cz: number;
        if (c < tops.length && c < 2) { const t = tops[c]; cx = t.x; cy = t.y - 0.05; cz = t.z; }
        else { const a = r() * Math.PI * 2, dd = range(r, 0.6, 2.3) * size; cx = Math.cos(a) * dd; cz = Math.sin(a) * dd; cy = ground(cx, cz); }
        const n = 6 + Math.round(r() * 6 + purity * 3);
        for (let i = 0; i < n; i++) {
          const s = range(r, 0.04, 0.11) * (0.8 + size * 0.3);
          const a = r() * Math.PI * 2, dd = r() * 0.3;
          cryG.push(place(sulfurCrystal(s, range(r, 0.8, 1.05)), cx + Math.cos(a) * dd, cy + s * 0.4, cz + Math.sin(a) * dd, r() * 6, range(r, -0.6, 0.6), range(r, -0.6, 0.6)));
        }
      }
    }
  }

  const rockMesh = new THREE.Mesh(merge(rockG), getOreMaterial(type));
  rockMesh.castShadow = true; rockMesh.receiveShadow = true; grp.add(rockMesh);
  const cm = crystalMaterial(type);
  if (cm && cryG.length) {
    const cmesh = new THREE.Mesh(merge(cryG), cm);
    cmesh.castShadow = true; cmesh.receiveShadow = true; grp.add(cmesh);
  }
  const stain = new THREE.Mesh(stainDisc((type === 'f_crude' ? 2.9 : 3.6) * size, ground, 0.05), stainMaterial(type));
  stain.receiveShadow = true; stain.renderOrder = -1; grp.add(stain);
  return grp;
}
