// Shared character rig for zombies and the living: an 18-bone skeleton posed on the CPU (layered procedural
// animation + two-bone leg IK so feet never skate) and written out as one row of bone transforms per body.
// zombieMesh.ts skins a single instanced mesh from those rows.

export const NB = 18;
export const BONE = { HIP: 0, SPINE: 1, CHEST: 2, HEAD: 3, JAW: 4, UAL: 5, FAL: 6, HNL: 7, UAR: 8, FAR: 9, HNR: 10, THL: 11, SHL: 12, FTL: 13, THR: 14, SHR: 15, FTR: 16, AUX: 17 } as const;
/** floats per body row: 18 bones x (quat, pos, scale) + 6 look texels, padded to 64 texels */
export const ROW_TEXELS = 64;
export const ROW = ROW_TEXELS * 4;
export const LOOK_AT = NB * 12;

const SHX = 0.225, SHY = 1.44, ELY = 1.16, WRY = 0.90, HPX = 0.09, HPY = 0.93, KNY = 0.50, ANK = 0.08;
/** rest-pose pivots (metres; +x is the body's left, +z forward) */
export const PIV: number[] = [
  0, 0.95, 0,   0, 1.06, 0,   0, 1.27, 0,   0, 1.50, -0.005,   0, 1.645, -0.02,
  SHX, SHY, 0,  SHX, ELY, 0,  SHX, WRY, 0,
  -SHX, SHY, 0, -SHX, ELY, 0, -SHX, WRY, 0,
  HPX, HPY, 0,  HPX, KNY, 0,  HPX, ANK, 0,
  -HPX, HPY, 0, -HPX, KNY, 0, -HPX, ANK, 0,
  0, 1.12, 0.06,
];
const L1 = HPY - KNY, L2 = KNY - ANK;

// ------------------------------------------------------------------ kinds, acts, tools
export const KIND = { walker: 0, runner: 1, brute: 2, howler: 3, bloat: 4, breaker: 5, human: 10 } as const;
export const ACT = { none: 0, swing: 1, tend: 2, sit: 3, cower: 4, talk: 5, carry: 6, lie: 7, wave: 8, stir: 9, eat: 10 } as const;
export const TOOL = { none: 0, gun: 1, axe: 2, hoe: 3, wrench: 4, bat: 5, medbag: 6, ladle: 7 } as const;
/** zombie-only poses, driven through the same act channel */
export const ZACT = { feed: 20, slump: 21, sniff: 22, paw: 23, scream: 24 } as const;
/** feature bits: optional geometry that is collapsed unless the body's mask has the bit */
export const FEAT = { hair: 1, hairLong: 2, beanie: 3, bun: 4, beard: 5, foreL: 6, foreR: 7, stumpL: 8, stumpR: 9, tool0: 9 /* +TOOL id */, bloat: 17, hump: 18, club: 19, throat: 20, brim: 21 } as const;

export interface HumanLook { skin: number; shirt: number; pants: number; hair: number; build: number; height: number; hairStyle: number }

/** Static description of one body: who they are and what they look like. */
export interface BodyDef {
  kind: number; human: boolean; seed: number;
  skin: number; shirt: number; pants: number; hair: number;
  build: number; height: number; hairStyle: number;
  lean: number;       // 0..1 habitual hunch
  decay: number;      // zombies: 0 fresh .. 1 husk
  sick: number;       // humans: 0..1 infection
  armLossL: boolean; armLossR: boolean;
  gait: number;       // individual walking style (zombies)
  tool: number;
  shape: Float32Array; look: Float32Array; stamp: number;
}

/** Per-frame animation inputs. */
export interface BodyAnim {
  t: number; phase: number; speed: number; move: number; turn: number;
  arms: number; atk: number; lunge: number; swing: number;
  hit: number; hitSide: number; hitFwd: number;
  lookYaw: number; lookPitch: number;
  crawl: number; limp: number;
  down: number; downMode: number; downFace: number;
  death: number; deathStyle: number; deathDir: number;
  act: number; actW: number; act2: number; act2W: number;
  aux: number; jaw: number;
}
export function newAnim(): BodyAnim {
  return { t: 0, phase: 0, speed: 0, move: 0, turn: 0, arms: 0, atk: 0, lunge: 0, swing: 0, hit: 0, hitSide: 0, hitFwd: 0, lookYaw: 0, lookPitch: 0, crawl: 0, limp: 0,
    down: 0, downMode: 0, downFace: 1, death: 0, deathStyle: 0, deathDir: 1, act: 0, actW: 0, act2: 0, act2W: 0, aux: 0, jaw: 0 };
}

// ------------------------------------------------------------------ small maths
function fr(x: number) { return x - Math.floor(x); }
export function hash1(n: number) { return fr(Math.sin(n * 127.1 + 311.7) * 43758.5453); }
function sst(a: number, b: number, x: number) { const t = x <= a ? 0 : x >= b ? 1 : (x - a) / (b - a); return t * t * (3 - 2 * t); }
function cl(v: number, a: number, b: number) { return v < a ? a : v > b ? b : v; }
function srgb(c: number) { return Math.pow(c / 255, 2.2); }
function hexLin(hex: number, out: number[]) { out[0] = srgb((hex >> 16) & 255); out[1] = srgb((hex >> 8) & 255); out[2] = srgb(hex & 255); }

// ------------------------------------------------------------------ looks
const H_SKIN = [0xf0c9a5, 0xe4b48c, 0xd29b72, 0xb97f55, 0x94603c, 0x6f4429, 0x513220];
const H_HAIR = [0x1c1611, 0x2e2118, 0x4a3220, 0x6b4a2a, 0x8f6a3a, 0xb8925a, 0x7a2f1a, 0x9a9690, 0xc4c0b8];
const H_PANTS = [0x2f3b55, 0x3a4660, 0x4a4234, 0x2a2a2a, 0x5c5340, 0x39473a, 0x4f4f57];
const H_SHIRT = [0x8a3f33, 0x3f6b8a, 0x5b7a4a, 0xb89a4a, 0x7a5a8a, 0x8a8a84, 0x3a5a5a, 0xb86a3a, 0x4a4a6a, 0xa85a6a, 0xc9c2b0, 0x2f2f33];
const HAIR_W: number[] = [0, 0, 0, 1, 2, 2, 3, 4, 5, 5];

/** A random living person's appearance. */
export function randomLook(rng: { next(): number }): HumanLook {
  const pick = (a: number[]) => a[Math.floor(rng.next() * a.length) % a.length];
  const b = rng.next();
  return { skin: pick(H_SKIN), shirt: pick(H_SHIRT), pants: pick(H_PANTS), hair: pick(H_HAIR), build: 0.85 + b * b * 0.4 + rng.next() * 0.08, height: 0.92 + rng.next() * 0.16, hairStyle: HAIR_W[Math.floor(rng.next() * HAIR_W.length) % HAIR_W.length] };
}
/** the stable look a seed implies (used for humans that were not given one) */
export function lookFromSeed(seed: number): HumanLook {
  let k = 0; return randomLook({ next: () => hash1(seed * 3.7 + (k++) * 17.13 + 0.5) });
}

export function makeDef(kind: number, seed: number, look?: HumanLook | null): BodyDef {
  const L = look ?? lookFromSeed(seed);
  const d: BodyDef = {
    kind, human: kind === KIND.human, seed, skin: L.skin, shirt: L.shirt, pants: L.pants, hair: L.hair,
    build: cl(L.build, 0.7, 1.4), height: cl(L.height, 0.8, 1.25), hairStyle: L.hairStyle | 0,
    lean: hash1(seed * 1.31), decay: 0, sick: 0, armLossL: false, armLossR: false, gait: 0, tool: 0,
    shape: new Float32Array(NB * 3), look: new Float32Array(24), stamp: -1,
  };
  refreshDef(d);
  return d;
}

const DECAY_TINT = [[0.6, 0.6, 0.52], [0.36, 0.42, 0.3], [0.2, 0.185, 0.14]];
const TYPE_TINT: Record<number, [number, number, number, number]> = {
  1: [0.5, 0.36, 0.34, 0.25], 2: [0.34, 0.27, 0.25, 0.45], 3: [0.62, 0.66, 0.7, 0.8], 4: [0.5, 0.52, 0.22, 0.7], 5: [0.36, 0.33, 0.42, 0.55],
};
const c3 = [0, 0, 0], c4 = [0, 0, 0];
function setS(S: Float32Array, b: number, x: number, y: number, z: number) { S[b * 3] = x; S[b * 3 + 1] = y; S[b * 3 + 2] = z; }

/** Recompute the bone scales (silhouette) and the shader look block. Call after changing decay / limbs / look. */
export function refreshDef(d: BodyDef) {
  const S = d.shape, K = d.kind, sd = d.seed;
  const h1 = hash1(sd * 2.17), h2 = hash1(sd * 4.91), h3 = hash1(sd * 7.77), h4 = hash1(sd * 9.3);
  // ---- silhouette: every bone has its own (x, y, z) scale about its pivot
  const husk = d.human ? 0 : sst(0.6, 1, d.decay);
  let bw = d.build * (1 - husk * 0.16);                    // overall width
  const limb = Math.pow(bw, 0.75) * (0.96 + h2 * 0.08);
  const sh = 0.96 + h1 * 0.1;                               // shoulder breadth
  for (let b = 0; b < NB; b++) setS(S, b, 1, 1, 1);
  setS(S, 0, bw * (0.98 + h3 * 0.06), 1, 0.9 + bw * 0.1 + (bw > 1.1 ? (bw - 1.1) * 0.6 : 0));
  setS(S, 1, bw, 1, bw * (bw > 1.08 ? 1 + (bw - 1.08) * 1.2 : 1));
  setS(S, 2, bw * sh, 1, 0.92 + bw * 0.08);
  setS(S, 3, 0.97 + h4 * 0.06, 0.97 + h1 * 0.06, 0.97 + h2 * 0.06);
  for (const b of [5, 6, 8, 9]) setS(S, b, limb, 1, limb);
  for (const b of [11, 12, 14, 15]) setS(S, b, limb * (bw > 1.1 ? 1.06 : 1), 1, limb * (bw > 1.1 ? 1.06 : 1));
  setS(S, 17, 0, 0, 0);
  if (K === KIND.runner) {
    setS(S, 1, bw * 0.9, 1, bw * 0.9); setS(S, 2, bw * sh * 0.95, 1, 0.92);
    for (const b of [5, 6, 8, 9, 11, 12, 14, 15]) setS(S, b, limb * 0.9, 1, limb * 0.9);
  } else if (K === KIND.brute) {
    setS(S, 0, 1.32, 1.05, 1.32); setS(S, 1, 1.42, 1.1, 1.5); setS(S, 2, 1.58, 1.16, 1.5); setS(S, 3, 1.06, 1.02, 1.08); setS(S, 4, 1.25, 1.2, 1.15);
    setS(S, 5, 1.75, 1.14, 1.75); setS(S, 8, 1.75, 1.14, 1.75); setS(S, 6, 1.85, 1.16, 1.85); setS(S, 9, 1.85, 1.16, 1.85); setS(S, 7, 1.7, 1.4, 1.7); setS(S, 10, 1.7, 1.4, 1.7);
    setS(S, 11, 1.4, 0.98, 1.4); setS(S, 14, 1.4, 0.98, 1.4); setS(S, 12, 1.4, 0.98, 1.4); setS(S, 15, 1.4, 0.98, 1.4); setS(S, 13, 1.3, 1.2, 1.2); setS(S, 16, 1.3, 1.2, 1.2);
  } else if (K === KIND.howler) {
    setS(S, 0, 0.84, 1, 0.84); setS(S, 1, 0.76, 1.1, 0.74); setS(S, 2, 0.9, 1.08, 0.8); setS(S, 3, 0.9, 1.16, 0.96); setS(S, 4, 1, 1.55, 1.12);
    for (const b of [5, 8]) setS(S, b, 0.74, 1.16, 0.74); for (const b of [6, 9]) setS(S, b, 0.72, 1.24, 0.72); setS(S, 7, 0.9, 1.45, 0.9); setS(S, 10, 0.9, 1.45, 0.9);
    for (const b of [11, 14]) setS(S, b, 0.76, 1.06, 0.76); for (const b of [12, 15]) setS(S, b, 0.76, 1.06, 0.76);
    setS(S, 17, 1, 1, 1);
  } else if (K === KIND.bloat) {
    setS(S, 0, 1.5, 1.08, 1.6); setS(S, 1, 1.7, 1.06, 1.85); setS(S, 2, 1.38, 1.02, 1.5); setS(S, 3, 1.08, 0.98, 1.1);
    for (const b of [5, 8]) setS(S, b, 1.35, 0.96, 1.35); for (const b of [6, 9]) setS(S, b, 1.2, 0.94, 1.2);
    for (const b of [11, 14]) setS(S, b, 1.42, 0.94, 1.42); for (const b of [12, 15]) setS(S, b, 1.25, 0.94, 1.25);
    setS(S, 17, 1.62, 1.42, 1.62);
  } else if (K === KIND.breaker) {
    setS(S, 0, 1.12, 1, 1.12); setS(S, 1, 1.15, 1.02, 1.15); setS(S, 2, 1.3, 1.06, 1.25); setS(S, 3, 1, 0.98, 1);
    setS(S, 5, 0.74, 0.92, 0.74); setS(S, 6, 0.68, 0.9, 0.68); setS(S, 7, 0.85, 0.9, 0.85);
    setS(S, 8, 1.6, 1.12, 1.6); setS(S, 9, 2.5, 1.3, 2.5); setS(S, 10, 2.3, 1.7, 2.3);
    for (const b of [11, 14, 12, 15]) setS(S, b, 1.15, 1, 1.15);
  }
  // ---- look block: 6 texels
  const Lk = d.look;
  hexLin(d.skin, c3);
  let grime = 0, blood = 0, rag = 0, wound = 0;
  let shirt = [0, 0, 0], pants = [0, 0, 0], hair = [0, 0, 0];
  hexLin(d.shirt, shirt); hexLin(d.pants, pants); hexLin(d.hair, hair);
  // clothing cut: sleeves end at sleeveY, trouser legs at legY (rest-pose heights). Derived from the seed so a
  // turned person keeps the clothes they died in.
  const longSleeve = hash1(sd * 11.3) < 0.55, shorts = hash1(sd * 13.7) < 0.12;
  let slL = longSleeve ? 0.905 : 1.3, slR = slL, lgL = shorts ? 0.56 : 0.075, lgR = lgL;
  let bareTorso = 0, bareFeet = 0;
  if (!d.human) {
    const dc = d.decay;
    // skin: the person's own tone drained toward grey-green, then dark and leathery
    const a = dc < 0.5 ? DECAY_TINT[0] : DECAY_TINT[1], b = dc < 0.5 ? DECAY_TINT[1] : DECAY_TINT[2], t = dc < 0.5 ? dc * 2 : dc * 2 - 1;
    const keep = 0.55 * (1 - sst(0, 0.5, dc)) + 0.1;
    for (let i = 0; i < 3; i++) { const tint = a[i] + (b[i] - a[i]) * t; c3[i] = c3[i] * keep + tint * tint * (1 - keep) * (0.85 + h1 * 0.3); }
    const tt = TYPE_TINT[K];
    if (tt) for (let i = 0; i < 3; i++) c3[i] = c3[i] * (1 - tt[3]) + tt[i] * tt[i] * tt[3];
    const dirt = 0.25 + dc * 0.6;
    for (let i = 0; i < 3; i++) {
      shirt[i] = shirt[i] * (1 - dirt * 0.75) + 0.018 * dirt; pants[i] = pants[i] * (1 - dirt * 0.7) + 0.015 * dirt;
      hair[i] = hair[i] * (1 - dc * 0.5) + 0.03 * dc;
    }
    grime = 0.25 + dc * 0.75; blood = 0.3 + h3 * 0.5 + (dc < 0.2 ? 0.2 : 0); rag = 0.04 + dc * 0.1; wound = 0.3 + dc * 0.7;
    // rot eats the clothes
    const tear = (s: number) => hash1(sd * s);
    if (tear(21.1) < 0.25 + dc * 0.6) slL = Math.max(slL, 1.05 + tear(22.2) * 0.4);
    if (tear(23.3) < 0.25 + dc * 0.6) slR = Math.max(slR, 1.05 + tear(24.4) * 0.4);
    if (tear(25.5) < 0.15 + dc * 0.65) lgL = Math.max(lgL, 0.2 + tear(26.6) * 0.45);
    if (tear(27.7) < 0.15 + dc * 0.65) lgR = Math.max(lgR, 0.2 + tear(28.8) * 0.45);
    if (tear(29.9) < dc * dc * 0.75 - 0.04 || K === KIND.howler || K === KIND.bloat || (K === KIND.brute && tear(30.3) < 0.6)) bareTorso = 1;
    if (tear(31.1) < 0.1 + dc * 0.7) bareFeet = 1;
    if (K === KIND.bloat) { slL = slR = 1.5; }
    if (K === KIND.howler) { slL = slR = 1.5; lgL = Math.max(lgL, 0.45); lgR = Math.max(lgR, 0.4); bareFeet = 1; }
  } else {
    // the living: pallor and sweat as the fever climbs
    const s = d.sick;
    for (let i = 0; i < 3; i++) c3[i] = c3[i] * (1 - s * 0.45) + [0.5, 0.52, 0.42][i] * s * 0.45;
    grime = 0.05 + s * 0.1; blood = 0;
  }
  // feature mask
  let mask = 0; const bit = (f: number) => { mask |= 1 << f; };
  const hs = d.hairStyle;
  const bald = !d.human && (hash1(sd * 33.3) < d.decay * 0.9 - 0.15 || K === KIND.bloat || K === KIND.howler);
  if (hs !== 1 && !bald) bit(FEAT.hair);
  if (hs === 2 && !bald) bit(FEAT.hairLong);
  if (hs === 3 && (d.human || d.decay < 0.45)) { bit(FEAT.beanie); if (hash1(sd * 35.1) < 0.45) bit(FEAT.brim); }
  if (hs === 4 && !bald) bit(FEAT.bun);
  if (hs === 5 && !bald) bit(FEAT.beard);
  if (d.armLossL) bit(FEAT.stumpL); else bit(FEAT.foreL);
  if (d.armLossR) bit(FEAT.stumpR); else bit(FEAT.foreR);
  if (d.tool > 0 && d.tool <= 7) bit(FEAT.tool0 + d.tool);
  if (K === KIND.bloat) bit(FEAT.bloat);
  if (K === KIND.brute) bit(FEAT.hump);
  if (K === KIND.breaker) bit(FEAT.club);
  if (K === KIND.howler) bit(FEAT.throat);
  // hat colour
  hexLin([0x7a2a2a, 0x2a3a5a, 0x4a4a4a, 0x4a5a3a, 0x8a6a3a][Math.floor(hash1(sd * 37.9) * 5) % 5], c4);
  Lk[0] = c3[0]; Lk[1] = c3[1]; Lk[2] = c3[2]; Lk[3] = mask;
  Lk[4] = shirt[0]; Lk[5] = shirt[1]; Lk[6] = shirt[2]; Lk[7] = slL;
  Lk[8] = pants[0]; Lk[9] = pants[1]; Lk[10] = pants[2]; Lk[11] = slR;
  Lk[12] = hair[0]; Lk[13] = hair[1]; Lk[14] = hair[2]; Lk[15] = lgL;
  Lk[16] = d.human ? d.sick * 0.25 : d.decay; Lk[17] = fr(sd * 0.137) * 40; Lk[18] = K + (bareTorso ? 32 : 0) + (bareFeet ? 64 : 0); Lk[19] = lgR;
  Lk[20] = blood; Lk[21] = grime; Lk[22] = rag + wound * 8; Lk[23] = c4[0] + Math.floor(c4[1] * 255) * 4 + Math.floor(c4[2] * 255) * 1024;
  d.stamp = d.decay + d.sick * 3 + d.tool * 7 + (d.armLossL ? 100 : 0) + (d.armLossR ? 200 : 0);
}
/** cheap change check for callers that mutate decay / sick / tool / limbs */
export function defStamp(d: BodyDef) { return d.decay + d.sick * 3 + d.tool * 7 + (d.armLossL ? 100 : 0) + (d.armLossR ? 200 : 0); }

// ------------------------------------------------------------------ pose channels
const CH: Record<string, number> = {
  rx: 0, ry: 1, rz: 2, rp: 3, rw: 4, rr: 5, spx: 6, spy: 7, spz: 8, chx: 9, chy: 10, chz: 11, hdx: 12, hdy: 13, hdz: 14, jaw: 15,
  alp: 16, ala: 17, alt: 18, ale: 19, alw: 20, arp: 21, ara: 22, art: 23, are: 24, arw: 25,
  llp: 26, lla: 27, llt: 28, llk: 29, llf: 30, lrp: 31, lra: 32, lrt: 33, lrk: 34, lrf: 35,
};
const NCH = 36, LEG0 = 26;
const RX = 0, RY = 1, RZ = 2, RP = 3, RW = 4, RR = 5, SPX = 6, SPY = 7, SPZ = 8, CHX = 9, CHY = 10, CHZ = 11, HDX = 12, HDY = 13, HDZ = 14, JAW = 15,
  ALP = 16, ALA = 17, ALT = 18, ALE = 19, ALW = 20, ARP = 21, ARA = 22, ART = 23, ARE = 24, ARW = 25;
// both-sides shorthands for authoring
const BOTH: Record<string, [string, string]> = { ap: ['alp', 'arp'], aa: ['ala', 'ara'], at: ['alt', 'art'], ae: ['ale', 'are'], aw: ['alw', 'arw'], lp: ['llp', 'lrp'], la: ['lla', 'lra'], lt: ['llt', 'lrt'], lk: ['llk', 'lrk'], lf: ['llf', 'lrf'] };
interface KP { idx: number[]; val: number[]; legs: boolean; leg: Float64Array }
/** A key pose: only the listed channels are driven. If any leg channel is given the legs become FK for this pose. */
function kp(o: Record<string, number>, full = false): KP {
  const m = new Map<number, number>();
  if (full) for (let i = 0; i < LEG0; i++) m.set(i, 0);
  for (const k in o) { if (BOTH[k]) { m.set(CH[BOTH[k][0]], o[k]); m.set(CH[BOTH[k][1]], o[k]); } }
  for (const k in o) { if (!BOTH[k]) { if (CH[k] === undefined) throw new Error('bad channel ' + k); m.set(CH[k], o[k]); } }
  const K: KP = { idx: [], val: [], legs: false, leg: new Float64Array(10) };
  for (const [i, v] of m) { if (i >= LEG0) { K.legs = true; K.leg[i - LEG0] = v; } else { K.idx.push(i); K.val.push(v); } }
  return K;
}
const P = new Float64Array(NCH);
const LG = new Float64Array(10); let fkW = 0;
function over(K: KP, w: number) {
  if (w <= 0.0005) return;
  if (w > 1) w = 1;
  const idx = K.idx, val = K.val;
  for (let i = 0; i < idx.length; i++) { const c = idx[i]; P[c] += (val[i] - P[c]) * w; }
  if (K.legs) {
    const nf = fkW + (1 - fkW) * w, k = w / nf;
    for (let i = 0; i < 10; i++) LG[i] += (K.leg[i] - LG[i]) * k;
    fkW = nf;
  }
}
/** blend three keys along u in 0..1..2 */
function over3(A: KP, B: KP, C: KP, u: number, w: number) {
  if (u <= 1) { over(A, w); over(B, w * sst(0, 1, u)); } else { over(B, w); over(C, w * sst(1, 2, u)); }
}

// ------------------------------------------------------------------ key poses
const HP = Math.PI / 2;
// lying
const K_SUPINE = kp({ ry: -0.835, rp: -HP, hdx: -0.05, hdy: 0.35, hdz: 0.1, alp: 0.12, arp: -0.05, ala: 0.42, ara: 0.3, alt: -0.6, art: -0.4, ale: 0.35, are: 0.6, aw: 0,
  llp: 0.06, lrp: 0.02, lla: 0.14, lra: 0.2, llt: 0.5, lrt: 0.7, llk: 0.08, lrk: 0.22, llf: 0.5, lrf: 0.5 }, true);
const K_PRONE = kp({ ry: -0.84, rp: HP, spx: -0.05, chx: -0.08, hdx: -0.25, hdy: 1.15, alp: 2.5, arp: 0.5, ala: 0.75, ara: 0.5, alt: 0.3, art: 0.2, ale: 1.1, are: 0.5,
  llp: 0.03, lrp: 0.0, lla: 0.14, lra: 0.24, llt: 0.4, lrt: 0.9, llk: 0.1, lrk: 0.45, llf: 0.9, lrf: 0.9 }, true);
const K_SIDE = kp({ ry: -0.8, rp: HP * 0.2, rr: HP * 0.94, rw: 0.3, spx: 0.35, chx: 0.25, hdx: 0.2, hdz: -0.3, alp: 0.9, arp: 0.4, ala: 0.1, ara: 0.2, ale: 0.9, are: 0.6,
  llp: 0.7, lrp: 0.25, lla: 0.0, lra: 0.0, llk: 1.0, lrk: 0.5, llf: 0.5, lrf: 0.5 }, true);
// getting up from the back: sit up, roll to hands and a knee, stand
const K_SITUP = kp({ ry: -0.8, rz: 0.02, rp: -0.35, spx: 0.3, chx: 0.3, hdx: 0.25, alp: -0.75, arp: -0.6, ala: 0.3, ara: 0.25, ae: 0.25, aw: -0.6,
  llp: 1.2, lrp: 0.5, la: 0.2, llk: 1.5, lrk: 0.5, llf: 0.2, lrf: 0.5 }, true);
const K_KNEEL1 = kp({ ry: -0.44, rz: 0.1, rp: 0.5, spx: 0.3, chx: 0.25, hdx: -0.35, alp: 1.0, arp: 0.9, aa: 0.15, ale: 0.25, are: 0.4, aw: -0.7,
  llp: 1.55, lrp: 0.15, la: 0.12, llk: 1.75, lrk: 1.6, llf: 0.0, lrf: 0.9 }, true);
// getting up from the front: push up, all fours, one knee
const K_PUSHUP = kp({ ry: -0.6, rz: -0.1, rp: 1.05, spx: -0.3, chx: -0.25, hdx: -0.5, ap: 1.2, aa: 0.3, ae: 0.35, aw: -1.2,
  llp: 0.25, lrp: 0.2, la: 0.12, llk: 0.25, lrk: 0.3, lf: 0.9 }, true);
const K_ALL4 = kp({ ry: -0.47, rp: 1.25, spx: -0.1, chx: -0.1, hdx: -0.6, ap: 1.35, aa: 0.12, ae: 0.12, aw: -1.3,
  llp: 1.35, lrp: 1.2, la: 0.1, llk: 1.6, lrk: 1.55, lf: 0.9 }, true);
// crawler: prone, dragging itself along (arms animated on top)
const K_CRAWL = kp({ ry: -0.8, rp: 1.42, spx: -0.2, chx: -0.25, hdx: -0.85, ap: 2.6, aa: 0.35, ae: 0.5, aw: -0.4,
  llp: 0.02, lrp: -0.05, lla: 0.12, lra: 0.22, llt: 0.4, lrt: 0.8, llk: 0.12, lrk: 0.3, lf: 0.9 }, true);
// falling
const K_FALLBACK = kp({ ry: -0.25, rz: -0.3, rp: -0.75, spx: -0.25, chx: -0.2, hdx: 0.35, ap: 1.1, aa: 0.7, ae: 0.5, jaw: 0.5,
  llp: 0.9, lrp: 0.5, la: 0.2, llk: 1.2, lrk: 0.9, lf: 0.3 }, true);
const K_FALLFWD = kp({ ry: -0.3, rz: 0.3, rp: 0.8, spx: 0.1, chx: 0.0, hdx: -0.4, ap: 1.7, aa: 0.4, ae: 0.7, jaw: 0.4,
  llp: 0.5, lrp: 0.1, la: 0.1, llk: 1.3, lrk: 0.8, lf: 0.6 }, true);
const K_KNEES = kp({ ry: -0.47, rz: 0.06, rp: 0.08, spx: 0.2, chx: 0.25, hdx: 0.5, ap: 0.1, aa: 0.12, ae: 0.2,
  llp: 0.15, lrp: 0.05, la: 0.1, llk: 1.7, lrk: 1.6, lf: 0.9 }, true);
// zombie acts
const K_FEED = kp({ ry: -0.6, rz: -0.05, rp: 0.55, spx: 0.5, chx: 0.45, hdx: 0.35, ap: 1.0, aa: 0.1, at: 0.4, ae: 0.9, aw: 0.2,
  llp: 1.75, lrp: 1.6, la: 0.22, llk: 2.5, lrk: 2.45, lf: 0.9 }, true);
const K_SLUMP = kp({ ry: -0.03, spx: 0.22, chx: 0.3, hdx: 0.65, hdz: 0.12, ap: 0.12, aa: 0.03, ae: 0.1, aw: 0, jaw: 0.25 });
const K_SNIFF = kp({ spx: -0.05, chx: -0.12, hdx: -0.55, ap: 0.05, ae: 0.35, jaw: 0.3 });
const K_PAW = kp({ spx: 0.12, chx: 0.1, hdx: -0.05, ap: 1.5, aa: 0.12, ae: 0.5, aw: -0.5 });
const K_SCREAM = kp({ ry: -0.06, spx: -0.22, chx: -0.3, hdx: -0.75, jaw: 1.0, ap: -0.35, aa: 0.75, at: -0.5, ae: 0.5, aw: 0.3 });
const K_REACH = kp({ ap: 1.32, aa: 0.1, ae: 0.3, aw: 0.35 });
const K_WINDUP = kp({ spx: -0.2, chx: -0.22, hdx: -0.25, alp: 2.0, arp: 1.7, aa: 0.5, ale: 1.3, are: 1.1, jaw: 0.7 });
const K_STRIKE = kp({ rz: 0.12, ry: -0.06, spx: 0.38, chx: 0.36, hdx: -0.2, alp: 0.95, arp: 1.1, aa: -0.12, ale: 0.55, are: 0.7, aw: 0.5, jaw: 0.9 });
const K_BSLAM_UP = kp({ spx: -0.3, chx: -0.3, hdx: -0.2, ap: 2.75, aa: 0.25, ae: 0.6, jaw: 0.6 });
const K_BSLAM_DN = kp({ ry: -0.12, spx: 0.5, chx: 0.42, hdx: -0.3, ap: 0.8, aa: 0.1, ae: 0.25, jaw: 0.5 });
const K_CLUB_UP = kp({ spx: -0.2, chx: -0.25, chy: 0.5, spz: 0, arp: 2.6, ara: 0.5, are: 0.5, alp: -0.3, ale: 1.2 });
const K_CLUB_DN = kp({ ry: -0.1, spx: 0.5, chx: 0.4, chy: -0.45, arp: 0.7, ara: 0.0, are: 0.15, alp: 0.4, ale: 1.0 });
// human acts
const K_AIM = kp({ chx: 0.05, alp: 1.38, arp: 1.45, ala: 0.0, ara: 0.02, alt: 0.55, art: 0.3, ale: 0.55, are: 0.22, aw: 0 });
const K_SWING_UP = kp({ spx: -0.12, chx: -0.12, chy: 0.25, arp: 2.75, ara: 0.25, art: 0.2, are: 1.2, arw: -0.5, alp: 0.5, ale: 0.9, hdx: -0.1 });
const K_SWING_DN = kp({ ry: -0.05, spx: 0.3, chx: 0.25, chy: -0.2, arp: 0.95, ara: 0.05, art: 0.2, are: 0.45, arw: 0.5, alp: 0.3, ale: 0.9, hdx: 0.15 });
const K_TEND = kp({ ry: -0.47, rz: -0.1, rp: 0.38, spx: 0.42, chx: 0.36, hdx: -0.25, ap: 0.72, aa: 0.08, at: 0.25, ae: 0.35, aw: 0.1 });
const K_SIT = kp({ ry: -0.83, rp: -0.22, spx: 0.3, chx: 0.25, hdx: -0.05, ap: 0.95, aa: 0.25, at: 0.5, ae: 0.75,
  llp: 1.6, lrp: 1.5, lla: 0.22, lra: 0.28, lk: 1.95, llk: 1.9, lrk: 2.05, lf: 0.25 }, true);
const K_COWER = kp({ ry: -0.5, rz: -0.12, rp: 0.3, spx: 0.5, chx: 0.45, hdx: 0.45, ap: 2.3, aa: 0.2, at: 1.1, ae: 2.2, aw: 0.3 });
const K_TALK = kp({ alp: 0.25, ale: 1.1, alt: 0.4, arp: 0.3, are: 0.9, art: 0.3 });
const K_CARRY = kp({ spx: -0.06, chx: -0.06, ap: 0.5, aa: 0.1, at: 0.45, ae: 1.25, aw: 0.2 });
const K_WAVE = kp({ arp: 0.3, ara: 2.5, art: -1.2, are: 0.5, chz: 0.06, hdx: -0.08 });
const K_STIR = kp({ spx: 0.14, chx: 0.12, hdx: 0.25, arp: 0.75, are: 0.75, art: 0.15, alp: 0.35, ale: 1.0, alt: 0.5 });
const K_EAT = kp({ hdx: 0.15, arp: 0.9, art: 0.75, are: 2.25, ara: 0.1, alp: 0.45, ale: 1.2, alt: 0.6 });

// ------------------------------------------------------------------ gaits
interface Gait { s0: number; s1: number; duty: number; lift: number; stance: number; bob: number; sway: number; roll: number; lean: number; arm: number; drop: number }
const G_HUMAN: Gait = { s0: 1.96, s1: 0, duty: 0.6, lift: 0.1, stance: 0.0, bob: 0.02, sway: 0.015, roll: 0.03, lean: 0.03, arm: 0.42, drop: 0.012 };
const GAITS: Gait[] = [
  /* 0 shamble   */ { s0: 0.5, s1: 0.36, duty: 0.66, lift: 0.045, stance: 0.03, bob: 0.014, sway: 0.045, roll: 0.07, lean: 0.06, arm: 0.16, drop: 0.035 },
  /* 1 lurch     */ { s0: 0.6, s1: 0.36, duty: 0.6, lift: 0.07, stance: 0.02, bob: 0.03, sway: 0.03, roll: 0.05, lean: 0.14, arm: 0.26, drop: 0.045 },
  /* 2 leg-drag  */ { s0: 0.46, s1: 0.34, duty: 0.66, lift: 0.06, stance: 0.04, bob: 0.03, sway: 0.06, roll: 0.1, lean: 0.1, arm: 0.2, drop: 0.04 },
  /* 3 stiff-arm */ { s0: 0.44, s1: 0.36, duty: 0.7, lift: 0.035, stance: 0.05, bob: 0.008, sway: 0.05, roll: 0.11, lean: -0.02, arm: 0.03, drop: 0.012 },
  /* 4 head-loll */ { s0: 0.52, s1: 0.36, duty: 0.64, lift: 0.04, stance: 0.01, bob: 0.02, sway: 0.035, roll: 0.06, lean: 0.02, arm: 0.34, drop: 0.03 },
  /* 5 sprint    */ { s0: 0.8, s1: 0.36, duty: 0.56, lift: 0.1, stance: 0.0, bob: 0.03, sway: 0.02, roll: 0.04, lean: 0.12, arm: 0.5, drop: 0.03 },
  /* 6 stomp     */ { s0: 0.8, s1: 0.3, duty: 0.66, lift: 0.1, stance: 0.1, bob: 0.045, sway: 0.07, roll: 0.09, lean: 0.12, arm: 0.2, drop: 0.07 },
  /* 7 stalk     */ { s0: 0.8, s1: 0.4, duty: 0.62, lift: 0.09, stance: -0.01, bob: 0.02, sway: 0.02, roll: 0.03, lean: 0.0, arm: 0.1, drop: 0.03 },
  /* 8 waddle    */ { s0: 0.42, s1: 0.3, duty: 0.7, lift: 0.04, stance: 0.13, bob: 0.02, sway: 0.075, roll: 0.14, lean: -0.1, arm: 0.1, drop: 0.035 },
  /* 9 lopsided  */ { s0: 0.6, s1: 0.34, duty: 0.64, lift: 0.07, stance: 0.06, bob: 0.035, sway: 0.05, roll: 0.08, lean: 0.14, arm: 0.12, drop: 0.05 },
];
export function gaitFor(kind: number, seed: number): number {
  if (kind === KIND.runner) return 5; if (kind === KIND.brute) return 6; if (kind === KIND.howler) return 7; if (kind === KIND.bloat) return 8; if (kind === KIND.breaker) return 9;
  const h = hash1(seed * 6.13);
  return h < 0.34 ? 0 : h < 0.54 ? 1 : h < 0.7 ? 2 : h < 0.84 ? 3 : 4;
}
/** ground covered per full gait cycle (two steps) at a forward speed; the AI advances phase by 2π·v/stride */
export function strideLen(d: BodyDef, speed: number): number {
  if (d.human) return G_HUMAN.s0;
  const g = GAITS[d.gait] ?? GAITS[0];
  const v = Math.abs(speed);
  return (g.s0 + g.s1 * Math.min(v, 6.5)) * (0.9 + 0.1 * d.shape[34]) * (d.gait === 2 ? 0.9 : 1);
}

// ------------------------------------------------------------------ forward kinematics
const WQ = new Float64Array(NB * 4), WP = new Float64Array(NB * 3);
let qx = 0, qy = 0, qz = 0, qw = 1;
function eYXZ(x: number, y: number, z: number) {
  const c1 = Math.cos(x * 0.5), c2 = Math.cos(y * 0.5), c3 = Math.cos(z * 0.5), s1 = Math.sin(x * 0.5), s2 = Math.sin(y * 0.5), s3 = Math.sin(z * 0.5);
  qx = s1 * c2 * c3 + c1 * s2 * s3; qy = c1 * s2 * c3 - s1 * c2 * s3; qz = c1 * c2 * s3 - s1 * s2 * c3; qw = c1 * c2 * c3 + s1 * s2 * s3;
}
function eZXY(x: number, y: number, z: number) {
  const c1 = Math.cos(x * 0.5), c2 = Math.cos(y * 0.5), c3 = Math.cos(z * 0.5), s1 = Math.sin(x * 0.5), s2 = Math.sin(y * 0.5), s3 = Math.sin(z * 0.5);
  qx = s1 * c2 * c3 - c1 * s2 * s3; qy = c1 * s2 * c3 + s1 * c2 * s3; qz = c1 * c2 * s3 + s1 * s2 * c3; qw = c1 * c2 * c3 - s1 * s2 * s3;
}
function eX(x: number) { qx = Math.sin(x * 0.5); qy = 0; qz = 0; qw = Math.cos(x * 0.5); }
let SH: Float32Array;
/** place bone b under parent p using the local rotation in (qx,qy,qz,qw); ox,oy,oz = rest offset from the parent's pivot */
function bone(b: number, p: number, ox: number, oy: number, oz: number) {
  const pi = p * 4, bi = b * 4;
  const ax = WQ[pi], ay = WQ[pi + 1], az = WQ[pi + 2], aw = WQ[pi + 3];
  WQ[bi] = aw * qx + ax * qw + ay * qz - az * qy;
  WQ[bi + 1] = aw * qy - ax * qz + ay * qw + az * qx;
  WQ[bi + 2] = aw * qz + ax * qy - ay * qx + az * qw;
  WQ[bi + 3] = aw * qw - ax * qx - ay * qy - az * qz;
  const vx = ox * SH[p * 3], vy = oy * SH[p * 3 + 1], vz = oz * SH[p * 3 + 2];
  const tx = 2 * (ay * vz - az * vy), ty = 2 * (az * vx - ax * vz), tz = 2 * (ax * vy - ay * vx);
  WP[b * 3] = WP[p * 3] + vx + aw * tx + (ay * tz - az * ty);
  WP[b * 3 + 1] = WP[p * 3 + 1] + vy + aw * ty + (az * tx - ax * tz);
  WP[b * 3 + 2] = WP[p * 3 + 2] + vz + aw * tz + (ax * ty - ay * tx);
}
const OFF = new Float64Array(NB * 3);
const PARENT = [0, 0, 1, 2, 3, 2, 5, 6, 2, 8, 9, 0, 11, 12, 0, 14, 15, 1];
for (let b = 1; b < NB; b++) for (let k = 0; k < 3; k++) OFF[b * 3 + k] = PIV[b * 3 + k] - PIV[PARENT[b] * 3 + k];

// leg IK scratch: desired ankle targets in body space
const FT = new Float64Array(8); // per leg: x, y(lift), z, footPitch
function legIK(side: number, o: number, rootY: number) {
  const s = side === 0 ? 1 : -1, c = LEG0 + side * 5;
  const l1 = L1 * SH[(11 + side * 3) * 3 + 1], l2 = L2 * SH[(12 + side * 3) * 3 + 1];
  const hx = P[RX] + s * HPX * SH[0], hy = rootY - 0.02 * SH[1], hz = P[RZ];
  const dx = FT[o] - hx, dy = FT[o + 1] + ANK - hy, dz = FT[o + 2] - hz;
  const lat = Math.atan2(dx, -dy);
  const dyl = -Math.sqrt(dx * dx + dy * dy);
  let d = Math.sqrt(dz * dz + dyl * dyl);
  const mx = (l1 + l2) * 0.9985; if (d > mx) d = mx; if (d < 0.2) d = 0.2;
  const ck = cl((l1 * l1 + l2 * l2 - d * d) / (2 * l1 * l2), -1, 1);
  const knee = Math.PI - Math.acos(ck);
  const pw = Math.atan2(dz, -dyl) + Math.acos(cl((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d), -1, 1));
  P[c] = pw + P[RP]; P[c + 1] = s * lat - s * P[RR]; P[c + 3] = knee; P[c + 4] = FT[o + 3] + pw - knee;
}

function gaitFoot(o: number, u: number, S: number, duty: number, lift: number, x: number, drag: number, w: number, tw: number) {
  // u: 0..1 cycle position for this foot; stance first
  let z: number, y: number, pitch = 0;
  const Ss = S * duty;
  if (u < duty) { const k = u / duty; z = Ss * (0.5 - k); y = 0; pitch = k > 0.75 ? (k - 0.75) * 1.6 : k < 0.12 ? -(0.12 - k) * 2.0 : 0; }
  else {
    const k = (u - duty) / (1 - duty); const e = k * k * (3 - 2 * k);
    z = Ss * (-0.5 + e); const sn = Math.sin(Math.PI * Math.pow(k, 0.8)); y = lift * sn * (1 - drag) + Math.min(0.12, S * 0.05) * Math.sin(Math.PI * k) * (1 - drag) * (lift > 0.09 ? 1 : 0.3);
    pitch = (1 - k) * 0.55 * (1 - drag) - k * 0.25;
  }
  if (drag > 0) { z = z * (1 - drag * 0.45) - drag * 0.12 * Math.min(1, S); pitch = 0.25 * drag; }
  const tl = tw * Math.max(0, Math.sin(u * Math.PI * 2)) * 0.07;   // stepping on the spot while turning
  FT[o] = x; FT[o + 1] = y * w + tl; FT[o + 2] = z * w; FT[o + 3] = pitch * w;
}

/**
 * Evaluate the layered pose for one body and write its bone transforms (18 x [quat, pos, scale]) at out[o..].
 * Everything is in body space: +z forward, origin between the feet on the ground.
 */
export function poseBody(a: BodyAnim, d: BodyDef, out: Float32Array, o: number) {
  SH = d.shape;
  P.fill(0); fkW = 0;
  const zomb = !d.human, K = d.kind, sd = d.seed, t = a.t + sd * 7.3;
  const g = d.human ? G_HUMAN : (GAITS[d.gait] ?? GAITS[0]);
  const alive = 1 - sst(0, 0.3, a.death);
  const v = Math.abs(a.speed);
  const walk = d.human ? cl(a.move, 0, 1) : sst(0.04, 0.3, v);
  const run = d.human ? cl(a.move - 1, 0, 1) : sst(2.4, 4.2, v);
  const idle = 1 - walk;
  const ph = a.phase;
  const TWO = Math.PI * 2;
  let gph = ph;
  if (d.gait === 1) gph = ph + 0.55 * Math.sin(ph);                    // lurch: one long step, one snatched
  const cs = Math.cos(gph), sn = Math.sin(gph);
  const sick = d.human ? d.sick : 0;
  const husk = zomb ? sst(0.6, 1, d.decay) : 0, fresh = zomb ? 1 - sst(0.05, 0.3, d.decay) : 0;

  // ---------------- 1. standing base + idle life
  if (zomb) {
    const hunch = 0.1 + d.lean * 0.16 + husk * 0.1;
    P[SPX] = hunch + Math.sin(t * 0.6) * 0.025 * idle; P[CHX] = hunch * 0.9 + Math.sin(t * 0.47 + 1) * 0.02;
    P[SPZ] = (d.lean - 0.5) * 0.12 + Math.sin(t * 0.43) * 0.035 * idle; P[CHZ] = (hash1(sd * 3.3) - 0.5) * 0.16;
    P[HDX] = 0.12 + Math.sin(t * 0.9) * 0.04; P[HDZ] = (hash1(sd * 3.1) - 0.5) * 0.5 + Math.sin(t * 0.7) * 0.05 * idle;
    P[HDY] = Math.sin(t * 0.31 + sd) * 0.18 * idle;
    P[JAW] = 0.18 + husk * 0.25 + Math.max(0, Math.sin(t * 0.8 + sd * 3)) * 0.12;
    P[ALP] = 0.1; P[ARP] = 0.06; P[ALA] = 0.07 + hash1(sd + 1) * 0.08; P[ARA] = 0.07 + hash1(sd + 2) * 0.08;
    P[ALE] = 0.2 + hash1(sd * 1.7) * 0.35; P[ARE] = 0.2 + hash1(sd * 2.7) * 0.35; P[ALW] = 0.25; P[ARW] = 0.25;
    P[ALT] = 0.25; P[ART] = 0.25;
    P[RY] = -0.02 - husk * 0.02;
    // a violent tic every so often
    const tw = Math.pow(Math.max(0, Math.sin(t * 0.73 + sd * 13)), 60);
    P[HDZ] += tw * 0.5 * Math.sin(t * 38); P[CHX] += tw * 0.12; P[ALE] += tw * 0.5 * Math.sin(t * 31);
    if (K === KIND.brute) { P[SPX] += 0.12; P[CHX] += 0.16; P[HDX] -= 0.2; P[ALP] = 0.22; P[ARP] = 0.22; P[ALA] = 0.34; P[ARA] = 0.34; P[ALE] = 0.3; P[ARE] = 0.3; P[ALT] = 0.5; P[ART] = 0.5; P[RY] -= 0.05; }
    else if (K === KIND.bloat) { P[SPX] = -0.08; P[CHX] = -0.04; P[HDX] = 0.2; P[ALA] = 0.62; P[ARA] = 0.62; P[ALP] = 0.0; P[ARP] = 0.0; P[ALE] = 0.3; P[ARE] = 0.3; P[RR] += Math.sin(t * 0.5) * 0.02; }
    else if (K === KIND.howler) { P[SPX] = 0.06; P[CHX] = 0.12; P[HDX] = -0.12 + Math.sin(t * 0.5) * 0.06; P[ALE] = 0.06; P[ARE] = 0.06; P[ALP] = 0.03; P[ARP] = 0.03; P[ALA] = 0.05; P[ARA] = 0.05; P[JAW] = 0.45 + Math.sin(t * 1.1) * 0.08; }
    else if (K === KIND.breaker) { P[SPZ] = -0.2; P[CHZ] = -0.16; P[SPX] = 0.22; P[CHX] = 0.2; P[HDZ] = 0.3; P[HDX] = -0.1; P[ARP] = 0.12; P[ARA] = 0.16; P[ARE] = 0.14; P[ALP] = 0.4; P[ALE] = 1.5; P[ALA] = 0.05; P[ALT] = 0.8; P[RY] -= 0.04; }
    else if (K === KIND.runner) { P[SPX] += 0.06; P[HDX] -= 0.1; P[ALE] += 0.3; P[ARE] += 0.3; P[HDY] += Math.sin(t * 2.3) * 0.12 * idle; }
    if (d.gait === 3) { P[SPX] = 0.03; P[CHX] = 0.03; }
    if (d.gait === 4) { P[HDX] = -0.3; P[HDZ] = 0.45 * Math.sin(t * 0.4 + sd); }
  } else {
    const br = Math.sin(t * 1.4);
    P[SPX] = 0.02 + sick * 0.14; P[CHX] = 0.02 + br * 0.008 + sick * 0.16; P[HDX] = sick * 0.3;
    P[ALA] = 0.07; P[ARA] = 0.07; P[ALE] = 0.16; P[ARE] = 0.16; P[ALT] = 0.3; P[ART] = 0.3; P[ALP] = 0.02; P[ARP] = 0.02;
    P[SPZ] = Math.sin(t * 0.37) * (0.012 + sick * 0.05) * idle; P[RX] = Math.sin(t * 0.37) * (0.006 + sick * 0.03) * idle;
    P[HDY] = Math.sin(t * 0.23 + sd) * 0.06 * idle;
  }

  // ---------------- 2. locomotion: pelvis motion + upper-body counter swing (feet come from IK below)
  const lw = Math.max(walk, cl(a.turn, 0, 1) * 0.6);
  let S = strideLen(d, a.speed), duty = g.duty + (0.4 - g.duty) * run, lift = g.lift + (0.3 - g.lift) * run;
  let bob = g.bob + (0.05 - g.bob) * run, sway = g.sway * (1 - run * 0.7), roll = g.roll * (1 - run * 0.6);
  if (d.human) { S = G_HUMAN.s0 * (0.72 + 0.28 * Math.min(1, a.move)) ; }
  if (walk > 0.001 || a.turn > 0.01) {
    P[RY] += (-g.drop * (1 - run * 0.5) - bob * (0.5 + 0.5 * Math.cos(2 * gph + 0.5))) * walk;
    P[RX] += sway * sn * walk; P[RR] += roll * sn * walk * (zomb ? 1 : -0.6); P[RW] += 0.09 * cs * walk * (1 + run);
    P[SPY] -= 0.1 * cs * walk * (1 + run * 0.6); P[CHY] -= 0.08 * cs * walk * (1 + run);
    const leanF = (g.lean + (zomb ? 0.34 : 0.2) * run) * walk;
    P[SPX] += leanF * 0.6; P[CHX] += leanF * 0.5; P[HDX] -= leanF * (zomb ? 0.5 : 0.7);
    P[SPZ] -= roll * 0.8 * sn * walk; P[RZ] += -0.03 * run;
    if (d.human) {
      const am = (g.arm + 0.45 * run) * walk;
      P[ALP] += -am * cs; P[ARP] += am * cs;
      P[ALE] += (0.15 + 1.25 * run) * walk + Math.max(0, -cs) * 0.25 * walk; P[ARE] += (0.15 + 1.25 * run) * walk + Math.max(0, cs) * 0.25 * walk;
      P[HDX] += Math.sin(gph * 2) * 0.02 * walk;
    } else {
      // dead arms: pendulums that lag the stride
      const am = g.arm * walk * (1 + run * 0.8), lag = 0.7;
      P[ALP] += -am * Math.cos(gph - lag); P[ARP] += am * Math.cos(gph - lag);
      P[HDX] += Math.sin(gph * 2 + 0.6) * 0.04 * walk; P[HDZ] += sn * 0.05 * walk;
      if (d.gait === 1) { const pulse = Math.max(0, Math.sin(gph)); P[SPX] += 0.2 * pulse * walk; P[CHZ] += 0.14 * walk; P[HDX] += 0.12 * pulse * walk; }
      if (d.gait === 2) { P[SPZ] += 0.14 * walk; P[RX] += 0.03 * walk; P[ALA] += 0.35 * walk * (0.6 + 0.4 * sn); P[ALP] += 0.2 * walk; }
      if (d.gait === 3) { P[ALP] += 1.25 * walk; P[ARP] += 1.2 * walk; P[ALE] *= 1 - walk * 0.8; P[ARE] *= 1 - walk * 0.8; P[ALW] += 0.3 * walk; P[ARW] += 0.3 * walk; }
      if (d.gait === 4) { P[HDZ] += 0.3 * Math.sin(gph * 0.5 + sd) * walk; P[HDX] -= 0.1 * walk; }
      if (d.gait === 6) { P[CHY] -= 0.12 * cs * walk; P[SPX] += 0.06 * Math.abs(sn) * walk; }
      if (d.gait === 8) { P[ALP] += 0.12 * cs * walk; P[ARP] -= 0.12 * cs * walk; }
      if (d.gait === 9) { P[ARP] += 0.3 * Math.cos(gph - 1.2) * walk; P[SPZ] -= 0.05 * walk; }
      if (run > 0) {
        // sprinting dead: arms thrash, elbows loose, head thrust forward
        const fl = run * (K === KIND.runner ? 1 : 0.55);
        P[ALP] += (0.5 + 0.6 * Math.sin(t * 11 + sd)) * fl - 0.5 * cs * fl; P[ARP] += (0.5 + 0.6 * Math.sin(t * 12.3 + sd * 2)) * fl + 0.5 * cs * fl;
        P[ALE] += (0.8 + 0.5 * Math.sin(t * 9 + 1)) * fl; P[ARE] += (0.8 + 0.5 * Math.sin(t * 10 + 2)) * fl;
        P[ALA] += 0.2 * fl; P[ARA] += 0.2 * fl; P[HDX] -= 0.15 * fl; P[JAW] += 0.4 * fl;
      }
    }
  }
  if (sick > 0) { P[RX] += Math.sin(gph * 0.5) * 0.04 * sick * walk; P[HDZ] += Math.sin(t * 0.8) * 0.08 * sick; }

  // ---------------- 3. arms: reaching for prey / aiming
  if (zomb) {
    const r = a.arms * alive * (K === KIND.bloat ? 0.55 : K === KIND.howler ? 0.8 : 1);
    if (r > 0.001) {
      over(K_REACH, r);
      P[ALP] += Math.sin(t * 2.3 + sd) * 0.1 * r; P[ARP] += Math.sin(t * 2.9 + sd * 2) * 0.1 * r; P[ALE] += Math.sin(t * 3.7) * 0.12 * r; P[ARE] += Math.sin(t * 4.1 + 1) * 0.12 * r;
      P[SPX] += 0.06 * r; P[JAW] += 0.3 * r;
      if (K === KIND.breaker) { P[ARP] -= 0.9 * r; P[ARE] -= 0.1 * r; }
    }
  } else if (a.arms > 0.001) over(K_AIM, a.arms);

  // ---------------- 4. acts (cross-faded pair)
  for (let i = 0; i < 2; i++) {
    const act = i === 0 ? a.act2 : a.act, w = (i === 0 ? a.act2W : a.actW) * alive;
    if (w <= 0.001 || act === 0) continue;
    switch (act) {
      case ACT.swing: { const u = fr(t * 0.8), k = u < 0.55 ? sst(0, 0.55, u) : 1 - sst(0.55, 0.72, u); over(K_SWING_DN, w); over(K_SWING_UP, w * k); break; }
      case ACT.tend: over(K_TEND, w); P[ALP] += Math.sin(t * 3.1) * 0.14 * w; P[ARP] += Math.sin(t * 3.1 + 2) * 0.14 * w; P[ALE] += Math.sin(t * 3.1 + 1) * 0.12 * w; P[ARE] += Math.cos(t * 3.1) * 0.12 * w; break;
      case ACT.sit: over(K_SIT, w); P[HDY] += Math.sin(t * 0.4 + sd) * 0.2 * w; break;
      case ACT.cower: over(K_COWER, w); P[SPX] += Math.sin(t * 17) * 0.012 * w; P[HDY] += Math.sin(t * 2.2) * 0.1 * w; break;
      case ACT.talk: over(K_TALK, w); P[ALP] += Math.sin(t * 2.6 + sd) * 0.16 * w; P[ALE] += Math.sin(t * 3.4) * 0.22 * w; P[ARP] += Math.sin(t * 1.9 + 2) * 0.12 * w; P[ARE] += Math.sin(t * 2.7 + sd) * 0.2 * w; P[HDX] += Math.sin(t * 2.1) * 0.05 * w; P[HDZ] += Math.sin(t * 1.3) * 0.05 * w; P[JAW] += Math.max(0, Math.sin(t * 9)) * 0.12 * w; break;
      case ACT.carry: over(K_CARRY, w); break;
      case ACT.lie: over(K_SUPINE, w); P[CHX] += Math.sin(t * 1.1) * 0.01 * w; break;
      case ACT.wave: over(K_WAVE, w); P[ARE] += Math.sin(t * 7) * 0.4 * w; break;
      case ACT.stir: over(K_STIR, w); P[ARP] += Math.sin(t * 3.4) * 0.09 * w; P[ARA] += Math.cos(t * 3.4) * 0.1 * w; P[ART] += Math.cos(t * 3.4) * 0.15 * w; break;
      case ACT.eat: { over(K_EAT, w); const b2 = 0.5 + 0.5 * Math.sin(t * 2.2); P[ARE] -= b2 * 0.9 * w; P[ARP] -= b2 * 0.3 * w; P[JAW] += (1 - b2) * 0.2 * w; break; }
      case ZACT.feed: over(K_FEED, w); P[HDX] += Math.sin(t * 7.3) * 0.14 * w; P[HDZ] += Math.sin(t * 5.1) * 0.12 * w; P[CHX] += Math.sin(t * 3.6) * 0.05 * w; P[ALP] += Math.sin(t * 4.7) * 0.2 * w; P[ARP] += Math.sin(t * 4.1 + 2) * 0.2 * w; P[ALE] += Math.sin(t * 4.7 + 1) * 0.2 * w; P[JAW] = 0.3 + 0.3 * Math.sin(t * 9); break;
      case ZACT.slump: over(K_SLUMP, w); P[SPZ] += Math.sin(t * 0.33 + sd) * 0.04 * w; break;
      case ZACT.sniff: over(K_SNIFF, w); P[HDX] += Math.max(0, Math.sin(t * 5.5)) * -0.08 * w; P[HDY] += Math.sin(t * 0.9) * 0.4 * w; break;
      case ZACT.paw: over(K_PAW, w); P[ALP] += Math.sin(t * 2.2) * 0.35 * w; P[ARP] += Math.sin(t * 2.2 + 3.1) * 0.35 * w; P[ALE] += Math.sin(t * 2.2 + 1) * 0.25 * w; P[ARE] += Math.sin(t * 2.2 + 4) * 0.25 * w; break;
      case ZACT.scream: over(K_SCREAM, w); P[HDZ] += Math.sin(t * 23) * 0.04 * w; P[CHX] += Math.sin(t * 31) * 0.015 * w; break;
    }
  }

  // ---------------- 5. attacks
  if (zomb) {
    const at = a.atk * alive;
    if (at > 0.001) {
      const lu = a.lunge;
      if (K === KIND.brute) { over(K_BSLAM_DN, at * (0.25 + 0.75 * Math.max(0, lu))); over(K_BSLAM_UP, at * Math.max(0, -lu)); }
      else if (K === KIND.breaker) { over(K_CLUB_DN, at * (0.2 + 0.8 * Math.max(0, lu))); over(K_CLUB_UP, at * Math.max(0, -lu)); }
      else {
        over(K_STRIKE, at * (0.3 + 0.7 * Math.max(0, lu))); over(K_WINDUP, at * Math.max(0, -lu));
        // clawing between bites
        const cw = at * (1 - Math.abs(lu)) * 0.5;
        P[ALP] += Math.sin(t * 13) * 0.35 * cw; P[ARP] += Math.sin(t * 13 + 2.6) * 0.35 * cw;
      }
    }
  } else if (a.swing > 0.001 || a.atk > 0.001) {
    // melee: the old one-shot swing (atk counts 1 -> 0)
    const k = Math.sin((1 - a.atk) * Math.PI) * (a.atk > 0.001 ? 1 : 0);
    if (k > 0) { P[ARP] += 2.3 * k; P[ARA] += 0.3 * k; P[ARE] += 0.5 * k * (1 - a.atk); P[CHY] += (a.atk - 0.5) * 0.9 * k; P[SPX] += 0.15 * k * (1 - a.atk); }
  }

  // ---------------- 6. hit reactions and the head
  if (a.hit > 0.001) {
    const h = a.hit * alive, hs = a.hitSide, hf = a.hitFwd;
    P[SPX] -= 0.4 * h * hf; P[CHX] -= 0.3 * h * hf; P[CHY] += 0.55 * h * hs; P[SPY] += 0.3 * h * hs; P[SPZ] -= 0.2 * h * hs;
    P[HDX] -= 0.45 * h * hf; P[HDY] += 0.4 * h * hs; P[HDZ] -= 0.25 * h * hs;
    P[ALA] += 0.5 * h; P[ARA] += 0.5 * h; P[ALP] += 0.3 * h * hf; P[ARP] += 0.3 * h * hf; P[RZ] -= 0.05 * h * hf; P[JAW] += 0.3 * h;
  }
  P[HDY] += cl(a.lookYaw, -1.2, 1.2) * 0.8 * alive; P[HDX] -= a.lookPitch * 0.7 * alive;
  P[CHY] += cl(a.lookYaw, -1.2, 1.2) * 0.15 * alive;
  P[JAW] += a.jaw;

  // ---------------- 7. crawling
  if (a.crawl > 0.001) {
    const c = a.crawl * alive;
    over(K_CRAWL, c);
    const pl = Math.sin(ph), pr = Math.sin(ph + Math.PI);
    P[ALP] += (pl * 0.45) * c; P[ARP] += (pr * 0.45) * c; P[ALE] += (0.35 - pl * 0.5) * c; P[ARE] += (0.35 - pr * 0.5) * c;
    P[RR] += pl * 0.12 * c; P[CHY] += pl * 0.15 * c; P[RY] += Math.abs(pl) * 0.02 * c;
    P[HDY] += cl(a.lookYaw, -1, 1) * 0.3 * c; P[HDX] += Math.sin(t * 2) * 0.06 * c;
  }

  // ---------------- 8. knocked down / rising
  if (a.down > 0.001) {
    const dn = a.down, sup = a.downFace >= 0;
    if (a.downMode < 0.5) {           // falling: stand -> stumble -> floor
      if (sup) over3(K_FALLBACK, K_FALLBACK, K_SUPINE, dn * 2, Math.min(1, dn * 3)); else over3(K_FALLFWD, K_FALLFWD, K_PRONE, dn * 2, Math.min(1, dn * 3));
      if (dn > 0.5) P[RZ] += (sup ? -0.55 : 0.55) * sst(0.5, 1, dn);
    } else {                          // rising: floor -> prop up -> knees -> stand
      const sh = Math.sin(t * 9) * 0.03 * Math.sin(dn * Math.PI);
      if (sup) {
        if (dn > 0.62) { over(K_SITUP, 1); over(K_SUPINE, sst(0.62, 1, dn)); }
        else if (dn > 0.3) { over(K_KNEEL1, 1); over(K_SITUP, sst(0.3, 0.62, dn)); }
        else over(K_KNEEL1, sst(0, 0.3, dn));
      } else {
        if (dn > 0.7) { over(K_PUSHUP, 1); over(K_PRONE, sst(0.7, 1, dn)); }
        else if (dn > 0.45) { over(K_ALL4, 1); over(K_PUSHUP, sst(0.45, 0.7, dn)); }
        else if (dn > 0.22) { over(K_KNEEL1, 1); over(K_ALL4, sst(0.22, 0.45, dn)); }
        else over(K_KNEEL1, sst(0, 0.22, dn));
      }
      P[CHX] += sh; P[HDZ] += sh * 2;
      P[RZ] += (sup ? -0.55 : 0.55) * sst(0.3, 0.9, dn);
    }
  }

  // ---------------- 9. death: five ways to fall
  if (a.death > 0.001) {
    const dt = a.death, st = a.deathStyle | 0;
    const e = sst(0, 1, dt);
    if (st === 0) {            // knees give way, pitch onto the face
      over3(K_KNEES, K_KNEES, K_PRONE, dt < 0.4 ? dt / 0.4 : 1 + (dt - 0.4) / 0.6, Math.min(1, dt * 4)); P[RZ] += 0.5 * sst(0.4, 1, dt);
    } else if (st === 1) {     // thrown onto the back
      over3(K_FALLBACK, K_FALLBACK, K_SUPINE, dt < 0.45 ? dt / 0.45 : 1 + (dt - 0.45) / 0.55, Math.min(1, dt * 4)); P[RZ] -= 0.75 * sst(0.2, 1, dt);
    } else if (st === 2) {     // spun round and dropped on its side
      over(K_SIDE, e); P[RW] += (a.hitSide >= 0 ? 1 : -1) * 1.3 * e; P[RX] += (a.hitSide >= 0 ? -0.3 : 0.3) * e;
      if (a.hitSide < 0) { P[RR] = -P[RR]; P[RW] -= 0.6 * e; }
    } else if (st === 3) {     // stiff as a board, face first
      over(K_PRONE, sst(0.55, 1, dt));
      const th = sst(0, 0.85, dt) * HP; P[RP] = th * (1 - sst(0.55, 1, dt)) + P[RP] * sst(0.55, 1, dt);
      const lv = 1 - sst(0.55, 1, dt); P[RY] += (0.9 * Math.cos(th) - 0.9) * lv; P[RZ] += 0.9 * Math.sin(th) * lv + 0.9 * (1 - lv);
    } else {                   // drops to its knees, sways, folds backward
      over3(K_KNEES, K_KNEES, K_SUPINE, dt < 0.5 ? dt / 0.5 : 1 + (dt - 0.5) / 0.5, Math.min(1, dt * 4));
      if (dt > 0.5) { const k2 = sst(0.5, 1, dt); LG[3] += (2.3 - LG[3]) * k2 * 0.8; LG[8] += (2.2 - LG[8]) * k2 * 0.8; LG[0] += (-0.1 - LG[0]) * k2; LG[5] += (-0.15 - LG[5]) * k2; P[RZ] -= 0.35 * k2; }
    }
    P[JAW] = 0.5;
  }

  // ---------------- 10. legs: planted feet by IK, blended with any FK pose that owns them
  const rootY = ANK + L2 * SH[37] + L1 * SH[34] + 0.02 * SH[1] + P[RY];
  if (fkW < 0.999) {
    const stx = HPX * SH[0] + g.stance + (d.human ? 0.012 : 0.02);
    const drag = (d.gait === 2 ? 0.85 : 0) + (zomb ? a.limp * 0.5 * (d.gait === 2 ? 0 : 1) : 0);
    const uL = fr(gph / TWO), uR = fr(gph / TWO + 0.5);
    gaitFoot(0, uL, S, duty, lift, stx, 0, lw, a.turn);
    gaitFoot(4, uR, S, duty, lift, -stx, cl(drag, 0, 1), lw, a.turn);
    if (d.gait === 3) { FT[3] *= 0.3; FT[7] *= 0.3; }
    if (run > 0) { FT[2] -= 0.1 * run; FT[6] -= 0.1 * run; }
    legIK(0, 0, rootY); legIK(1, 4, rootY);
    const tout = zomb ? 0.1 + hash1(sd * 8.1) * 0.25 : 0.14;
    P[LEG0 + 2] = tout; P[LEG0 + 7] = tout + (d.gait === 2 ? 0.5 : 0);
    if (fkW > 0) for (let i = 0; i < 10; i++) P[LEG0 + i] += (LG[i] - P[LEG0 + i]) * fkW;
  } else for (let i = 0; i < 10; i++) P[LEG0 + i] = LG[i];

  // ---------------- 11. forward kinematics
  eYXZ(P[RP], P[RW], P[RR]);
  WQ[0] = qx; WQ[1] = qy; WQ[2] = qz; WQ[3] = qw; WP[0] = P[RX]; WP[1] = rootY; WP[2] = P[RZ];
  eYXZ(P[SPX], P[SPY], P[SPZ]); bone(1, 0, OFF[3], OFF[4], OFF[5]);
  eYXZ(P[CHX], P[CHY], P[CHZ]); bone(2, 1, OFF[6], OFF[7], OFF[8]);
  eYXZ(P[HDX], P[HDY], P[HDZ]); bone(3, 2, OFF[9], OFF[10], OFF[11]);
  eX(P[JAW] * 0.55); bone(4, 3, OFF[12], OFF[13], OFF[14]);
  eZXY(-P[ALP], -P[ALT], P[ALA]); bone(5, 2, OFF[15], OFF[16], OFF[17]);
  eX(-P[ALE]); bone(6, 5, OFF[18], OFF[19], OFF[20]);
  eX(P[ALW]); bone(7, 6, OFF[21], OFF[22], OFF[23]);
  eZXY(-P[ARP], P[ART], -P[ARA]); bone(8, 2, OFF[24], OFF[25], OFF[26]);
  eX(-P[ARE]); bone(9, 8, OFF[27], OFF[28], OFF[29]);
  eX(P[ARW]); bone(10, 9, OFF[30], OFF[31], OFF[32]);
  eZXY(-P[26], P[28], P[27]); bone(11, 0, OFF[33], OFF[34], OFF[35]);
  eX(P[29]); bone(12, 11, OFF[36], OFF[37], OFF[38]);
  eX(P[30]); bone(13, 12, OFF[39], OFF[40], OFF[41]);
  eZXY(-P[31], -P[33], -P[32]); bone(14, 0, OFF[42], OFF[43], OFF[44]);
  eX(P[34]); bone(15, 14, OFF[45], OFF[46], OFF[47]);
  eX(P[35]); bone(16, 15, OFF[48], OFF[49], OFF[50]);
  // aux: the bloat's gut hangs off the spine; the howler's throat sac off the head
  qx = 0; qy = 0; qz = 0; qw = 1;
  let auxS = 1;
  if (K === KIND.howler) { bone(17, 3, 0, 0.055, 0.05); auxS = 0.55 + a.aux * 1.1 + Math.sin(t * 3) * 0.04; }
  else { bone(17, 1, OFF[51], OFF[52], OFF[53]); auxS = 1 + a.aux * 0.28 + Math.sin(t * 2.1) * 0.025 + (walk > 0 ? Math.sin(gph * 2) * 0.03 * walk : 0); }

  for (let b = 0; b < NB; b++) {
    const k = o + b * 12, q = b * 4, p = b * 3;
    out[k] = WQ[q]; out[k + 1] = WQ[q + 1]; out[k + 2] = WQ[q + 2]; out[k + 3] = WQ[q + 3];
    out[k + 4] = WP[p]; out[k + 5] = WP[p + 1]; out[k + 6] = WP[p + 2]; out[k + 7] = 0;
    out[k + 8] = SH[p]; out[k + 9] = SH[p + 1]; out[k + 10] = SH[p + 2]; out[k + 11] = 0;
  }
  const k17 = o + 17 * 12 + 8; out[k17] *= auxS; out[k17 + 1] *= auxS; out[k17 + 2] *= auxS;
  // missing forearms are collapsed by the feature mask; nothing to do here
}
