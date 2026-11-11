// The shared character mesh: lofted, continuous surfaces (torso, limbs, head, hands, boots) with two-bone
// blending near joints, plus optional "feature" parts (hair, hats, jackets, packs, tools, growths) that a body
// switches on through its feature mask. Built once; skinned on the GPU from the rig's bone rows.
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { LOWPOLY } from '../render/materials';
import { BONE as B, FEAT as F, PIV } from './bodyRig';

/** surface regions: the shader decides colour (and clothing layers) per region from the body's look */
export const REG = { head: 0, torso: 1, arm: 2, hand: 3, leg: 4, foot: 5, hair: 6, eye: 7, bone: 8, gore: 9, hat: 10, metal: 11, wood: 12, dark: 13, red: 14, growth: 15, club: 16, jacket: 17, pack: 18, face: 19, belly: 20 } as const;

type G = THREE.BufferGeometry;
interface V { x: number; y: number; z: number; a: number; b?: number; w?: number; f?: number }
const lp = () => LOWPOLY;

/**
 * Parametric surface: cols x rows vertices from fn(i, j), wrapped in i when `wrap`. Triangles wind so that
 * a tube running up +y with i going front -> left faces outward. Optional fan caps at the first / last row.
 */
function surf(cols: number, rows: number, wrap: boolean, fn: (i: number, j: number) => V, region: number, feat = 0, capA = false, capB = false): G {
  const pos: number[] = [], part: number[] = [], idx: number[] = [];
  const rf = region + 64 * feat;
  const push = (v: V) => {
    let a = v.a, b = v.b ?? v.a, w = v.w ?? 0;
    if (w > 0.5) { const t = a; a = b; b = t; w = 1 - w; }
    if (b === a) w = 0;
    pos.push(v.x, v.y, v.z); part.push(a, b, w, v.f ? region + 64 * v.f : rf);
  };
  for (let j = 0; j < rows; j++) for (let i = 0; i < cols; i++) push(fn(i, j));
  const at = (i: number, j: number) => j * cols + (i % cols);
  const ni = wrap ? cols : cols - 1;
  for (let j = 0; j < rows - 1; j++) for (let i = 0; i < ni; i++) {
    const a = at(i, j), b = at(i + 1, j), c = at(i + 1, j + 1), d = at(i, j + 1);
    idx.push(a, b, c, a, c, d);
  }
  const cap = (j: number, flip: boolean) => {
    let cx = 0, cy = 0, cz = 0; const first = fn(0, j); let a = first.a;
    for (let i = 0; i < cols; i++) { const v = fn(i, j); cx += v.x; cy += v.y; cz += v.z; }
    const n = pos.length / 3; push({ x: cx / cols, y: cy / cols, z: cz / cols, a, b: first.b, w: first.w, f: first.f });
    for (let i = 0; i < ni; i++) { if (flip) idx.push(n, at(i, j), at(i + 1, j)); else idx.push(n, at(i + 1, j), at(i, j)); }
  };
  if (capA) cap(0, false); if (capB) cap(rows - 1, true);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('aPart', new THREE.Float32BufferAttribute(part, 4));
  g.setIndex(idx); g.computeVertexNormals();
  return g;
}
/** primitive (box / sphere / cylinder) rigidly bound to one bone */
function rigid(geo: G, bone: number, region: number, feat = 0): G {
  const n = geo.attributes.position.count; const a = new Float32Array(n * 4);
  for (let i = 0; i < n; i++) { a[i * 4] = bone; a[i * 4 + 1] = bone; a[i * 4 + 3] = region + 64 * feat; }
  geo.setAttribute('aPart', new THREE.Float32BufferAttribute(a, 4)); geo.deleteAttribute('uv');
  return geo;
}
function ball(r: number, x: number, y: number, z: number, sx = 1, sy = 1, sz = 1, w = 8, h = 6, top = Math.PI): G {
  const g = lp() ? new THREE.SphereGeometry(r, Math.max(5, Math.round(w * 0.6)), Math.max(3, Math.round(h * 0.6)), 0, Math.PI * 2, 0, top) : new THREE.SphereGeometry(r, w, h, 0, Math.PI * 2, 0, top);
  g.scale(sx, sy, sz); g.translate(x, y, z); return g;
}
function box(w: number, h: number, d: number, x: number, y: number, z: number, rx = 0, ry = 0, rz = 0): G {
  const g = new THREE.BoxGeometry(w, h, d); if (rx) g.rotateX(rx); if (ry) g.rotateY(ry); if (rz) g.rotateZ(rz); g.translate(x, y, z); return g;
}
function warp(g: G, f: (v: THREE.Vector3) => void): G {
  const p = g.attributes.position as THREE.BufferAttribute; const v = new THREE.Vector3();
  for (let i = 0; i < p.count; i++) { v.fromBufferAttribute(p, i); f(v); p.setXYZ(i, v.x, v.y, v.z); }
  g.computeVertexNormals(); return g;
}
function sgnPow(v: number, e: number) { return v < 0 ? -Math.pow(-v, e) : Math.pow(v, e); }
function sst(a: number, b: number, x: number) { const t = x <= a ? 0 : x >= b ? 1 : (x - a) / (b - a); return t * t * (3 - 2 * t); }

/** one cross-section of a loft: half-width rx, front / back depths rf / rb about (cx, cz), squareness n */
interface Ring { y: number; rx: number; rf: number; rb: number; cx?: number; cz?: number; n?: number; a: number; b?: number; w?: number; d?: boolean; f?: number }
function ringPoint(r: Ring, th: number, x0: number): V {
  const e = 2 / (r.n ?? 2), s = sgnPow(Math.sin(th), e), c = sgnPow(Math.cos(th), e);
  return { x: x0 + (r.cx ?? 0) + r.rx * s, y: r.y, z: (r.cz ?? 0) + (c > 0 ? r.rf : r.rb) * c, a: r.a, b: r.b, w: r.w, f: r.f };
}
/** loft rings bottom -> top (or top -> bottom) into one skin */
function loft(rings: Ring[], sides: number, region: number, o: { x?: number; feat?: number; capA?: boolean; capB?: boolean } = {}): G {
  let rs = rings; if (lp()) { rs = rings.filter(r => !r.d); sides = Math.max(5, Math.round(sides * 0.6)); }
  const up = rs[rs.length - 1].y > rs[0].y;
  // keep outward winding whichever way the rings run
  return surf(sides, rs.length, true, (i, j) => ringPoint(rs[up ? j : rs.length - 1 - j], i / sides * Math.PI * 2, o.x ?? 0), region, o.feat ?? 0, up ? !!o.capA : !!o.capB, up ? !!o.capB : !!o.capA);
}

// ------------------------------------------------------------------ torso profile (also used by jackets)
const HIP = B.HIP, SPN = B.SPINE, CHT = B.CHEST, HD = B.HEAD;
const TORSO: Ring[] = [
  { y: 0.99, rx: 0.12, rf: 0.085, rb: 0.085, a: HIP, b: SPN, w: 0.2 },
  { y: 1.045, rx: 0.14, rf: 0.094, rb: 0.088, a: HIP, b: SPN, w: 0.5, n: 2.3 },
  { y: 1.066, rx: 0.159, rf: 0.111, rb: 0.103, a: SPN, b: HIP, w: 0.4, n: 2.3 },      // shirt hem hangs over the belt
  { y: 1.11, rx: 0.148, rf: 0.107, rb: 0.094, a: SPN, b: HIP, w: 0.15, n: 2.3 },
  { y: 1.17, rx: 0.142, rf: 0.104, rb: 0.09, a: SPN, n: 2.3 },
  { y: 1.24, rx: 0.148, rf: 0.108, rb: 0.096, a: SPN, b: CHT, w: 0.35, n: 2.4 },
  { y: 1.3, rx: 0.157, rf: 0.116, rb: 0.102, a: CHT, b: SPN, w: 0.3, n: 2.5 },
  { y: 1.36, rx: 0.164, rf: 0.121, rb: 0.106, a: CHT, n: 2.6 },
  { y: 1.42, rx: 0.172, rf: 0.116, rb: 0.106, a: CHT, n: 2.5 },
  // the shoulder girdle runs out over the top of each arm, so the deltoid sits under the cloth instead of
  // standing proud of it like a puffed sleeve; the trapezius slopes up into a neck with some weight to it
  { y: 1.452, rx: 0.19, rf: 0.1, rb: 0.102, a: CHT, n: 2.25 },
  { y: 1.478, rx: 0.178, rf: 0.084, rb: 0.094, a: CHT, n: 2.0 },
  { y: 1.497, rx: 0.132, rf: 0.07, rb: 0.086, a: CHT, n: 2.0 },
  { y: 1.515, rx: 0.086, rf: 0.062, rb: 0.074, a: CHT, b: HD, w: 0.3 },
  { y: 1.535, rx: 0.062, rf: 0.057, rb: 0.066, a: HD, b: CHT, w: 0.4 },
  { y: 1.575, rx: 0.056, rf: 0.054, rb: 0.061, a: HD },
  { y: 1.625, rx: 0.054, rf: 0.047, rb: 0.06, a: HD },
];
function torsoAt(y: number): Ring {
  const T = TORSO; if (y <= T[0].y) return T[0];
  for (let i = 1; i < T.length; i++) if (y <= T[i].y) {
    const p = T[i - 1], q = T[i], k = (y - p.y) / (q.y - p.y), near = k < 0.5 ? p : q;
    return { y, rx: p.rx + (q.rx - p.rx) * k, rf: p.rf + (q.rf - p.rf) * k, rb: p.rb + (q.rb - p.rb) * k, n: near.n, a: near.a, b: near.b, w: near.w };
  }
  return T[T.length - 1];
}

// ------------------------------------------------------------------ head: one function describes the skull
const HEAD_ROWS: [number, number, number, number][] = [
  // y, half-width, front z, back z
  [1.55, 0.02, 0.07, 0.03], [1.562, 0.036, 0.09, 0.006], [1.582, 0.05, 0.097, -0.028], [1.6055, 0.0605, 0.1005, -0.055], [1.6125, 0.062, 0.1015, -0.06],
  [1.634, 0.068, 0.1, -0.076], [1.658, 0.0735, 0.0985, -0.09], [1.684, 0.076, 0.0975, -0.098], [1.707, 0.077, 0.103, -0.102], [1.733, 0.076, 0.1, -0.102],
  [1.758, 0.0705, 0.088, -0.095], [1.778, 0.056, 0.064, -0.074], [1.791, 0.032, 0.034, -0.042],
];
const HEAD_TOP = 1.797;
function headProf(y: number): [number, number, number] {
  const R = HEAD_ROWS; if (y <= R[0][0]) return [R[0][1], R[0][2], R[0][3]];
  for (let i = 1; i < R.length; i++) if (y <= R[i][0]) { const p = R[i - 1], q = R[i], k = (y - p[0]) / (q[0] - p[0]); return [p[1] + (q[1] - p[1]) * k, p[2] + (q[2] - p[2]) * k, p[3] + (q[3] - p[3]) * k]; }
  const k = Math.max(0, 1 - (y - R[R.length - 1][0]) / (HEAD_TOP - R[R.length - 1][0])); const l = R[R.length - 1];
  return [l[1] * k, -0.004 + (l[2] + 0.004) * k, -0.004 + (l[3] + 0.004) * k];
}
/** point on the head at angle th (0 = face, + toward the left ear) and height y, pushed out by `off` */
function headPoint(th: number, y: number, off = 0): V {
  const [w, zf, zb] = headProf(y);
  const zc = (zf + zb) / 2 + (y < 1.66 ? (1.66 - y) * 0.12 : 0), s = Math.sin(th), c = Math.cos(th);
  const e = c > 0 ? 2 / 2.5 : 2 / 2.2;
  let x = w * sgnPow(s, e), z = zc + (c > 0 ? zf - zc : zc - zb) * sgnPow(c, e);
  // eye sockets, temples and the hollow under the cheekbones
  const ax = Math.abs(x);
  if (z > 0.04) {
    const so = Math.exp(-((ax - 0.032) * (ax - 0.032)) / 0.00022 - ((y - 1.685) * (y - 1.685)) / 0.00016); z -= so * 0.0085;
    const ck = Math.exp(-((ax - 0.045) * (ax - 0.045)) / 0.0004 - ((y - 1.625) * (y - 1.625)) / 0.0005); z -= ck * 0.003; x -= Math.sign(x) * ck * 0.003;
  }
  if (off) { const dx = x, dz = z - zc, l = Math.hypot(dx, dz) || 1; x += dx / l * off; z += dz / l * off; }
  // the jaw swings: lower front of the face follows the jaw bone, fading out toward the ears
  let wj = 0; if (y < 1.609) wj = sst(-0.035, 0.03, z) * sst(1.6095, 1.606, y);
  return { x, y: y + (off && y > 1.76 ? off * (y - 1.76) / 0.037 : 0), z, a: HD, b: B.JAW, w: wj };
}
/** a shell lying on the head between a per-angle lower line and the crown (hair, hats) */
function headShell(cols: number, steps: number, line: (th: number) => number, off: (v: number, th: number) => number, region: number, feat: number, top = HEAD_TOP, th0 = 0, th1 = Math.PI * 2): G {
  if (lp()) { cols = Math.max(7, Math.round(cols * 0.65)); steps = Math.max(3, steps - 1); }
  const wrapd = th1 - th0 >= Math.PI * 2 - 1e-6;
  return surf(cols, steps, wrapd, (i, j) => {
    const th = th0 + (th1 - th0) * i / (wrapd ? cols : cols - 1), v = j / (steps - 1);
    const lo = line(th), y = lo + (top - lo) * Math.pow(v, 0.85);
    return headPoint(th, Math.min(y, HEAD_TOP - 0.0005), off(v, th));
  }, region, feat, false, wrapd);
}
function lineOf(pts: [number, number][]) {
  return (th: number) => {
    let a = Math.abs(((th + Math.PI) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2) - Math.PI) * 180 / Math.PI;
    for (let i = 1; i < pts.length; i++) if (a <= pts[i][0]) { const p = pts[i - 1], q = pts[i], k = (a - p[0]) / (q[0] - p[0]); const s = k * k * (3 - 2 * k); return p[1] + (q[1] - p[1]) * s; }
    return pts[pts.length - 1][1];
  };
}

export function buildBodyGeometry(): THREE.BufferGeometry {
  const P: G[] = [];
  const R = REG;
  // ================= torso + neck: one skin from crotch to under the jaw
  P.push(loft(TORSO, 14, R.torso, { capA: true, capB: true }));
  // paunch for heavy builds (and the bloat's gut): rides the aux bone in front of the spine
  { const ax = PIV[B.AUX * 3], ay = PIV[B.AUX * 3 + 1], az = PIV[B.AUX * 3 + 2]; P.push(rigid(ball(1, ax, ay, az, 0.14, 0.13, 0.1, 10, 7), B.AUX, R.belly, F.paunch)); }

  // ================= head
  {
    const rows = HEAD_ROWS.map(r => r[0]); const cols = lp() ? 10 : 16;
    const ys = (lp() ? rows.filter((_, i) => i !== 1 && i !== 9 && i !== 11) : rows).concat([HEAD_TOP - 0.0012]);
    P.push(surf(cols, ys.length, true, (i, j) => headPoint(i / cols * Math.PI * 2, ys[j]), R.head, 0, true, true));
    // nose: bridge, tip, nostril base
    const nose: Ring[] = [
      { y: 1.631, rx: 0.012, rf: 0.008, rb: 0.012, cz: 0.098, a: HD }, { y: 1.641, rx: 0.0165, rf: 0.019, rb: 0.012, cz: 0.1, a: HD },
      { y: 1.662, rx: 0.0115, rf: 0.014, rb: 0.012, cz: 0.099, a: HD }, { y: 1.69, rx: 0.0085, rf: 0.008, rb: 0.012, cz: 0.095, a: HD }, { y: 1.705, rx: 0.008, rf: 0.004, rb: 0.012, cz: 0.096, a: HD },
    ];
    P.push(loft(nose, 6, R.face, { capA: true }));
    for (const s of [1, -1]) {
      P.push(rigid(ball(0.0142, s * 0.0315, 1.6845, 0.0768, 1, 1, 1, 7, 5), HD, R.eye));
      // ear: a tilted shallow dish
      P.push(rigid(warp(ball(1, 0, 0, 0, 0.007, 0.03, 0.017, 6, 4), v => { v.x = (v.x - v.z * 0.45) * s + s * 0.079; v.y += 1.675; v.z -= 0.012; }), HD, R.face));
    }
  }
  // ================= hair, hats, beards (shells on the skull)
  {
    const crop = lineOf([[0, 1.752], [40, 1.75], [62, 1.728], [80, 1.69], [96, 1.668], [112, 1.64], [150, 1.612], [180, 1.606]]);
    P.push(headShell(16, 6, crop, (v) => 0.004 + 0.011 * Math.min(1, v * 3) + 0.004 * Math.sin(v * Math.PI), R.hair, F.hair, HEAD_TOP + 0.004));
    // long hair: the fall behind the ears to the shoulders
    {
      const cols = lp() ? 7 : 11, rows = [1.465, 1.5, 1.55, 1.61, 1.67, 1.72];
      P.push(surf(cols, rows.length, false, (i, j) => {
        const th = (66 + (360 - 132) * i / (cols - 1)) * Math.PI / 180, y = rows[j];
        if (y > 1.63) return headPoint(th, y, 0.017);
        const k = (1.63 - y) / 0.165, w = 0.092 + 0.012 * k - 0.02 * k * k * k, d = 0.108 + 0.004 * k - 0.03 * k * k * k;
        return { x: w * Math.sin(th), y, z: -0.012 + d * Math.cos(th) * (Math.cos(th) > 0 ? 0.5 : 1), a: HD, b: CHT, w: y < 1.52 ? 0.35 : 0 };
      }, R.hair, F.hairLong));
    }
    // tied back: a short tail
    P.push(loft([{ y: 1.71, rx: 0.02, rf: 0.02, rb: 0.03, cz: -0.1, a: HD }, { y: 1.675, rx: 0.026, rf: 0.02, rb: 0.03, cz: -0.118, a: HD }, { y: 1.62, rx: 0.022, rf: 0.016, rb: 0.022, cz: -0.122, a: HD }, { y: 1.565, rx: 0.012, rf: 0.01, rb: 0.012, cz: -0.112, a: HD }], 6, R.hair, { feat: F.bun, capA: true, capB: true }));
    // beanie with a turned-up cuff; cap with a brim
    const hatLine = lineOf([[0, 1.728], [60, 1.718], [90, 1.698], [130, 1.672], [180, 1.655]]);
    P.push(headShell(16, 6, hatLine, (v) => v < 0.001 ? 0.006 : v < 0.26 ? 0.021 : 0.014 + 0.006 * Math.sin(v * Math.PI), R.hat, F.beanie, HEAD_TOP + 0.012));
    P.push(rigid(warp(ball(1, 0, 0, 0, 0.074, 0.007, 0.062, 8, 4), v => { v.y -= v.z * 0.16 + (v.x * v.x) * 1.6; v.y += 1.728; v.z += 0.128; }), HD, R.hat, F.brim));
    // beard: follows the jaw from the sideburns round the chin
    const beardTop = lineOf([[0, 1.6035], [20, 1.604], [40, 1.622], [70, 1.655], [86, 1.675], [100, 1.64], [112, 1.6]]);
    {
      const cols = lp() ? 9 : 15, steps = 4;
      P.push(surf(cols, steps, false, (i, j) => {
        const th = (-112 + 224 * i / (cols - 1)) * Math.PI / 180, v = j / (steps - 1), hi = beardTop(th), y = 1.553 + (hi - 1.553) * v;
        const p = headPoint(th, y, 0.003 + 0.0085 * Math.sin(Math.min(1, v * 1.4) * Math.PI * 0.75) * (1 - Math.abs(th) / 2.6)); p.y -= (1 - v) * 0.012; return p;
      }, R.hair, F.beard));
    }
  }
  // ================= arms and hands (left = +x)
  for (const s of [1, -1]) {
    const L = s > 0, x0 = s * PIV[B.UAL * 3];
    const UA = L ? B.UAL : B.UAR, FA = L ? B.FAL : B.FAR, HN = L ? B.HNL : B.HNR, fore = L ? F.foreL : F.foreR, stump = L ? F.stumpL : F.stumpR;
    const arm: Ring[] = [
      { y: 1.478, rx: 0.012, rf: 0.014, rb: 0.014, cx: -s * 0.03, a: CHT, b: UA, w: 0.4 },
      { y: 1.472, rx: 0.03, rf: 0.034, rb: 0.034, cx: -s * 0.02, a: CHT, b: UA, w: 0.5 },
      { y: 1.455, rx: 0.044, rf: 0.048, rb: 0.047, cx: -s * 0.008, a: UA, b: CHT, w: 0.3 },
      { y: 1.425, rx: 0.049, rf: 0.054, rb: 0.052, cx: 0, a: UA, b: CHT, w: 0.1 },
      { y: 1.38, rx: 0.047, rf: 0.053, rb: 0.052, a: UA, d: true },
      { y: 1.31, rx: 0.045, rf: 0.051, rb: 0.049, a: UA },
      { y: 1.24, rx: 0.042, rf: 0.046, rb: 0.046, a: UA, d: true },
      { y: 1.195, rx: 0.04, rf: 0.042, rb: 0.044, a: UA, b: FA, w: 0.15 },
      { y: 1.16, rx: 0.039, rf: 0.04, rb: 0.044, a: UA, b: FA, w: 0.5 },
      { y: 1.125, rx: 0.041, rf: 0.043, rb: 0.042, a: FA, b: UA, w: 0.15, f: fore },
      { y: 1.07, rx: 0.041, rf: 0.045, rb: 0.04, a: FA, f: fore },
      { y: 1.0, rx: 0.036, rf: 0.039, rb: 0.036, a: FA, d: true, f: fore },
      { y: 0.945, rx: 0.031, rf: 0.034, rb: 0.032, a: FA, f: fore },
      { y: 0.917, rx: 0.032, rf: 0.035, rb: 0.033, a: FA, f: fore },
      { y: 0.913, rx: 0.026, rf: 0.029, rb: 0.027, a: FA, f: fore },
    ];
    P.push(loft(arm, 10, R.arm, { x: x0, capA: true, capB: true }));
    // hand: palm toward the thigh, fingers curled in as one mitt, thumb forward
    const hand: Ring[] = [
      { y: 0.925, rx: 0.02, rf: 0.026, rb: 0.024, a: FA, b: HN, w: 0.5, n: 2.6 },
      { y: 0.893, rx: 0.0165, rf: 0.036, rb: 0.032, a: HN, n: 3 },
      { y: 0.858, rx: 0.016, rf: 0.044, rb: 0.039, a: HN, n: 3 },
      { y: 0.822, rx: 0.0155, rf: 0.043, rb: 0.039, a: HN, n: 3 },
      { y: 0.792, rx: 0.015, rf: 0.041, rb: 0.037, cx: -s * 0.008, a: HN, n: 3 },
      { y: 0.768, rx: 0.017, rf: 0.039, rb: 0.035, cx: -s * 0.024, a: HN, n: 3 },
      { y: 0.752, rx: 0.019, rf: 0.035, rb: 0.031, cx: -s * 0.042, a: HN, n: 3 },
      { y: 0.746, rx: 0.011, rf: 0.027, rb: 0.024, cx: -s * 0.056, a: HN, n: 3 },
    ];
    P.push(loft(hand, 8, R.hand, { x: x0, feat: fore, capB: true }));
    P.push(loft([{ y: 0.882, rx: 0.012, rf: 0.013, rb: 0.012, cx: -s * 0.004, cz: 0.034, a: HN }, { y: 0.845, rx: 0.0125, rf: 0.013, rb: 0.012, cx: -s * 0.012, cz: 0.052, a: HN }, { y: 0.808, rx: 0.0105, rf: 0.011, rb: 0.01, cx: -s * 0.024, cz: 0.058, a: HN }, { y: 0.795, rx: 0.006, rf: 0.006, rb: 0.006, cx: -s * 0.03, cz: 0.057, a: HN }], 6, R.hand, { x: x0, feat: fore, capA: true, capB: true }));
    // stump: ragged flesh cap + a spur of bone
    P.push(rigid(ball(0.043, x0, 1.165, 0, 1, 0.6, 1.05, 7, 4), UA, R.gore, stump));
    { const g = new THREE.CylinderGeometry(0.01, 0.015, 0.1, lp() ? 4 : 6, 1, false); g.translate(x0, 1.115, 0); P.push(rigid(g, UA, R.bone, stump)); }
  }
  // ================= legs and boots
  for (const s of [1, -1]) {
    const L = s > 0, x0 = s * PIV[B.THL * 3];
    const TH = L ? B.THL : B.THR, SH = L ? B.SHL : B.SHR, FT = L ? B.FTL : B.FTR;
    const leg: Ring[] = [
      { y: 1.058, rx: 0.0955, rf: 0.104, rb: 0.097, cx: -s * 0.0325, a: HIP, b: SPN, w: 0.45, n: 2.8 },
      { y: 1.02, rx: 0.099, rf: 0.103, rb: 0.105, cx: -s * 0.029, a: HIP, b: SPN, w: 0.2, n: 2.8 },
      { y: 0.96, rx: 0.103, rf: 0.1, rb: 0.119, cx: -s * 0.025, a: HIP, n: 2.8 },
      { y: 0.905, rx: 0.093, rf: 0.097, rb: 0.118, cx: -s * 0.015, a: HIP, b: TH, w: 0.35, n: 2.5 },
      { y: 0.862, rx: 0.085, rf: 0.094, rb: 0.102, cx: -s * 0.003, a: TH, b: HIP, w: 0.2 },
      { y: 0.82, rx: 0.086, rf: 0.092, rb: 0.094, cx: s * 0.002, a: TH },
      { y: 0.76, rx: 0.08, rf: 0.086, rb: 0.086, cx: s * 0.001, a: TH, d: true },
      { y: 0.67, rx: 0.072, rf: 0.078, rb: 0.076, a: TH },
      { y: 0.585, rx: 0.065, rf: 0.07, rb: 0.066, a: TH, d: true },
      { y: 0.535, rx: 0.06, rf: 0.066, rb: 0.06, a: TH, b: SH, w: 0.15 },
      { y: 0.5, rx: 0.057, rf: 0.064, rb: 0.057, a: TH, b: SH, w: 0.5 },
      { y: 0.465, rx: 0.055, rf: 0.059, rb: 0.058, a: SH, b: TH, w: 0.15 },
      { y: 0.41, rx: 0.058, rf: 0.056, rb: 0.07, a: SH },
      { y: 0.34, rx: 0.057, rf: 0.054, rb: 0.068, a: SH, d: true },
      { y: 0.26, rx: 0.053, rf: 0.052, rb: 0.059, a: SH },
      { y: 0.19, rx: 0.052, rf: 0.054, rb: 0.055, a: SH, d: true },
      { y: 0.132, rx: 0.055, rf: 0.059, rb: 0.057, a: SH },
      { y: 0.126, rx: 0.04, rf: 0.044, rb: 0.042, a: SH },
    ];
    P.push(loft(leg, 12, R.leg, { x: x0, capA: true, capB: true }));
    // boot: shaft under the trouser cuff, then the foot lofted heel -> toe
    P.push(loft([{ y: 0.2, rx: 0.039, rf: 0.042, rb: 0.042, a: SH }, { y: 0.14, rx: 0.042, rf: 0.047, rb: 0.045, a: SH }, { y: 0.1, rx: 0.043, rf: 0.05, rb: 0.047, a: SH, b: FT, w: 0.5 }, { y: 0.06, rx: 0.044, rf: 0.052, rb: 0.05, a: FT }], 8, R.foot, { x: x0 }));
    const sec: [number, number, number][] = [[-0.072, 0.026, 0.045], [-0.06, 0.04, 0.084], [-0.015, 0.046, 0.108], [0.045, 0.049, 0.09], [0.1, 0.052, 0.068], [0.15, 0.051, 0.054], [0.185, 0.044, 0.046], [0.2, 0.03, 0.032]];
    const zs = lp() ? sec.filter((_, i) => i !== 3 && i !== 5) : sec; const fc = lp() ? 6 : 8;
    P.push(surf(fc, zs.length, true, (i, j) => {
      const [z, w, h] = zs[j], th = i / fc * Math.PI * 2, sx = sgnPow(Math.sin(th), 2 / 3), cy = sgnPow(Math.cos(th), 2 / 3);
      return { x: x0 + s * 0.002 - w * sx, y: Math.max(0.001, h * 0.5 + h * 0.5 * cy * (cy < 0 ? 1.06 : 1)), z, a: FT };
    }, R.foot, 0, true, true));
  }
  // ================= clothing layers and kit
  {
    // open jacket / gilet: a shell over the torso, split down the front, with a stand collar
    const ys = lp() ? [1.0, 1.1, 1.24, 1.36, 1.44, 1.485, 1.52] : [0.995, 1.04, 1.1, 1.17, 1.24, 1.3, 1.36, 1.42, 1.462, 1.492, 1.522];
    const cols = lp() ? 9 : 15, gap = 0.2;
    P.push(surf(cols, ys.length, false, (i, j) => {
      const y = ys[j], r = torsoAt(Math.min(y, 1.5)), th = gap + (Math.PI * 2 - 2 * gap) * i / (cols - 1);
      const top = y > 1.5, o = top ? 0.02 : y < 1.02 ? 0.02 : 0.014, edge = (i === 0 || i === cols - 1) ? 0.008 : 0;
      const p = ringPoint({ ...r, rx: r.rx + o + (y < 1.07 ? 0.006 : 0), rf: r.rf + o + edge + (y < 1.07 ? 0.006 : 0), rb: r.rb + o + (y < 1.07 ? 0.006 : 0) }, th, 0);
      p.y = y; if (y < 1.07) { p.a = SPN; p.b = HIP; p.w = 0.3; }
      return p;
    }, R.jacket, F.jacket));
    // backpack
    P.push(loft([{ y: 1.13, rx: 0.1, rf: 0.05, rb: 0.055, cz: -0.135, n: 4, a: CHT, b: SPN, w: 0.4 }, { y: 1.17, rx: 0.118, rf: 0.07, rb: 0.075, cz: -0.15, n: 4, a: CHT, b: SPN, w: 0.3 }, { y: 1.32, rx: 0.12, rf: 0.07, rb: 0.08, cz: -0.158, n: 4, a: CHT }, { y: 1.43, rx: 0.112, rf: 0.065, rb: 0.07, cz: -0.154, n: 4, a: CHT }, { y: 1.475, rx: 0.085, rf: 0.045, rb: 0.05, cz: -0.142, n: 3, a: CHT }], 10, R.pack, { feat: F.pack, capA: true, capB: true }));
    P.push(rigid(box(0.15, 0.1, 0.035, 0, 1.24, -0.248), CHT, R.pack, F.pack));
    // tool belt pouches
    P.push(rigid(box(0.05, 0.075, 0.07, 0.165, 1.0, 0.03, 0, 0, -0.08), HIP, R.pack, F.belt));
    P.push(rigid(box(0.045, 0.06, 0.06, -0.168, 1.005, -0.02, 0, 0, 0.08), HIP, R.pack, F.belt));
    P.push(rigid(box(0.07, 0.065, 0.035, -0.07, 1.005, 0.118, 0.1), HIP, R.pack, F.belt));
  }
  // ================= tools, held in the right fist (grip in the hook of the fingers; long tools run forward)
  {
    const hx = -PIV[B.UAL * 3], x = hx + 0.03, y = 0.79, hn = B.HNR, f0 = F.tool0;
    const add = (g: G, region: number, f: number) => P.push(rigid(g, hn, region, f));
    add(box(0.028, 0.2, 0.04, x, 0.725, 0.04), R.dark, f0 + 1);                 // pistol: slide along the forearm
    add(box(0.026, 0.06, 0.07, x, 0.8, 0.0), R.dark, f0 + 1);                   //   grip
    add(box(0.026, 0.03, 0.56, x, y, 0.22), R.wood, f0 + 2);                    // axe haft
    add(warp(box(0.018, 0.13, 0.085, x, y - 0.04, 0.46), v => { if (v.y < y - 0.06) { v.z = 0.46 + (v.z - 0.46) * 1.5; v.x = x + (v.x - x) * 0.3; } }), R.metal, f0 + 2);
    add(box(0.024, 0.028, 1.05, x, y, 0.37), R.wood, f0 + 3);                   // hoe
    add(box(0.11, 0.085, 0.012, x, y - 0.045, 0.89, 0.25), R.metal, f0 + 3);
    add(box(0.018, 0.026, 0.2, x, y, 0.07), R.metal, f0 + 4);                   // wrench
    add(box(0.018, 0.055, 0.05, x, y, 0.18), R.metal, f0 + 4);
    { const g = new THREE.CylinderGeometry(0.034, 0.017, 0.66, lp() ? 5 : 7, 1); g.rotateX(Math.PI / 2); g.translate(x, y, 0.26); add(g, R.wood, f0 + 5); }   // bat
    add(box(0.11, 0.15, 0.22, hx + 0.005, 0.655, 0.0), R.red, f0 + 6);          // medic bag
    add(box(0.114, 0.07, 0.022, hx + 0.005, 0.66, 0.0), R.bone, f0 + 6);
    add(box(0.114, 0.022, 0.07, hx + 0.005, 0.66, 0.0), R.bone, f0 + 6);
    add(box(0.014, 0.06, 0.014, x - 0.01, 0.755, 0.0), R.dark, f0 + 6);
    add(box(0.014, 0.016, 0.3, x, y, 0.14), R.metal, f0 + 7);                   // ladle
    add(ball(0.034, x, y - 0.012, 0.3, 1, 0.8, 1, 6, 4), R.metal, f0 + 7);
  }
  // ================= what the sickness grows
  {
    const ax = PIV[B.AUX * 3], ay = PIV[B.AUX * 3 + 1], az = PIV[B.AUX * 3 + 2];
    // bloat: gas sacs over the gut and back, a goitre at the throat
    P.push(rigid(ball(0.058, ax + 0.085, ay + 0.07, az + 0.06, 1, 1, 1, 6, 4), B.AUX, R.growth, F.bloat));
    P.push(rigid(ball(0.048, ax - 0.08, ay - 0.05, az + 0.07, 1, 1, 1, 6, 4), B.AUX, R.growth, F.bloat));
    P.push(rigid(ball(0.072, 0.09, 1.42, -0.1, 1, 1, 1, 6, 4), CHT, R.growth, F.bloat));
    P.push(rigid(ball(0.058, -0.08, 1.33, -0.115, 1, 1, 1, 6, 4), CHT, R.growth, F.bloat));
    P.push(rigid(ball(0.062, 0.02, 1.5, 0.05, 1.2, 0.9, 1, 6, 4), CHT, R.growth, F.bloat));
    // brute: a hump of muscle across the shoulders
    P.push(rigid(ball(1, 0, 1.455, -0.055, 0.15, 0.095, 0.11, 9, 6), CHT, R.torso, F.hump));
    // breaker: the right arm is a club of fused bone
    const cx = -PIV[B.UAL * 3];
    P.push(rigid(ball(1, cx - 0.01, 0.8, 0.0, 0.045, 0.085, 0.05, 8, 6), B.HNR, R.club, F.club));
    for (const [dx, dy, dz, rz, rx] of [[-0.045, 1.06, 0, 1.2, 0], [0, 1.0, 0.045, 0, 1.2], [-0.03, 0.96, -0.04, 0.9, -1.0], [0.04, 1.1, 0.0, -1.1, 0]] as number[][]) {
      const g = new THREE.ConeGeometry(0.018, 0.07, lp() ? 4 : 5, 1); g.rotateX(rx); g.rotateZ(rz); g.translate(cx + dx, dy, dz); P.push(rigid(g, B.FAR, R.bone, F.club));
    }
    // howler: the throat sac it screams with
    P.push(rigid(ball(0.055, ax, ay, az, 1, 1, 1, 8, 6), B.AUX, R.growth, F.throat));
  }
  for (const g of P) if (!g.attributes.normal) g.computeVertexNormals();
  const g = mergeGeometries(P, false)!;
  g.computeBoundingSphere(); g.boundingSphere!.radius = 2.6;
  return g;
}
