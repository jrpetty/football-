// A* over the 1 m collision nav grid for people (residents open doors, never smash through walls).
import { HALF_WORLD } from '../core/util';
import { CollisionWorld, NAV_N, NAV_SOLID, NAV_FREE } from '../world/collision';

const W = 128;                       // search window (cells)
const g = new Float32Array(W * W);
const from = new Int32Array(W * W);
const stamp = new Uint32Array(W * W);
const closed = new Uint32Array(W * W);
const heap: number[] = [];
const fval = new Float32Array(W * W);
let gen = 1;

function push(i: number) { heap.push(i); let c = heap.length - 1; while (c > 0) { const p = (c - 1) >> 1; if (fval[heap[p]] <= fval[heap[c]]) break; const t = heap[p]; heap[p] = heap[c]; heap[c] = t; c = p; } }
function pop() {
  const top = heap[0]; const last = heap.pop()!;
  if (heap.length) { heap[0] = last; let p = 0; for (;;) { const a = p * 2 + 1, b = a + 1; let m = p; if (a < heap.length && fval[heap[a]] < fval[heap[m]]) m = a; if (b < heap.length && fval[heap[b]] < fval[heap[m]]) m = b; if (m === p) break; const t = heap[p]; heap[p] = heap[m]; heap[m] = t; p = m; } }
  return top;
}

/** can a person walk through this nav cell? doors yes (at a cost), walls no */
function cost(col: CollisionWorld, gx: number, gz: number): number {
  if (gx < 0 || gz < 0 || gx >= NAV_N || gz >= NAV_N) return -1;
  const i = gz * NAV_N + gx; const v = col.nav[i];
  if (v === NAV_FREE) return 0;
  if (v === NAV_SOLID) return -1;
  const ref = col.navRef.get(i);
  if (ref && (ref.door || ref.open !== undefined)) return 2; // a door: open it
  return -1;
}
function freeNear(col: CollisionWorld, gx: number, gz: number): [number, number] | null {
  if (cost(col, gx, gz) >= 0) return [gx, gz];
  for (let r = 1; r <= 4; r++) for (let dx = -r; dx <= r; dx++) for (let dz = -r; dz <= r; dz++) {
    if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
    if (cost(col, gx + dx, gz + dz) >= 0) return [gx + dx, gz + dz];
  }
  return null;
}
/** straight walkable line between two cells (with a little clearance) */
function clear(col: CollisionWorld, x0: number, z0: number, x1: number, z1: number) {
  const dx = x1 - x0, dz = z1 - z0; const n = Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) * 2);
  for (let i = 0; i <= n; i++) {
    const t = n ? i / n : 0; const x = x0 + dx * t, z = z0 + dz * t;
    if (cost(col, Math.floor(x), Math.floor(z)) !== 0) return false;
    if (cost(col, Math.floor(x + 0.35), Math.floor(z)) < 0 || cost(col, Math.floor(x - 0.35), Math.floor(z)) < 0 || cost(col, Math.floor(x), Math.floor(z + 0.35)) < 0 || cost(col, Math.floor(x), Math.floor(z - 0.35)) < 0) return false;
  }
  return true;
}
export function straight(col: CollisionWorld, ax: number, az: number, bx: number, bz: number) { return clear(col, ax + HALF_WORLD, az + HALF_WORLD, bx + HALF_WORLD, bz + HALF_WORLD); }

/** returns waypoints [x0,z0,x1,z1,…] in world space (excluding the start), or null if unreachable */
export function findPath(col: CollisionWorld, ax: number, az: number, bx: number, bz: number): number[] | null {
  const s = freeNear(col, Math.floor(ax + HALF_WORLD), Math.floor(az + HALF_WORLD));
  const e = freeNear(col, Math.floor(bx + HALF_WORLD), Math.floor(bz + HALF_WORLD));
  if (!s || !e) return null;
  // window centred between the two ends
  const ox = Math.floor((s[0] + e[0]) / 2) - W / 2, oz = Math.floor((s[1] + e[1]) / 2) - W / 2;
  const sx = s[0] - ox, sz = s[1] - oz; let ex = e[0] - ox, ez = e[1] - oz;
  if (sx < 0 || sz < 0 || sx >= W || sz >= W) return null;
  ex = Math.max(1, Math.min(W - 2, ex)); ez = Math.max(1, Math.min(W - 2, ez));
  gen++; heap.length = 0;
  const si = sz * W + sx, ei = ez * W + ex;
  g[si] = 0; stamp[si] = gen; from[si] = -1; fval[si] = Math.hypot(ex - sx, ez - sz); push(si);
  let best = si, bestH = fval[si], iter = 0;
  while (heap.length && iter++ < 7000) {
    const i = pop(); if (closed[i] === gen) continue; closed[i] = gen;
    if (i === ei) { best = i; break; }
    const cx = i % W, cz = (i / W) | 0;
    for (let k = 0; k < 8; k++) {
      const dx = k < 4 ? (k === 0 ? 1 : k === 1 ? -1 : 0) : (k < 6 ? 1 : -1), dz = k < 4 ? (k === 2 ? 1 : k === 3 ? -1 : 0) : (k % 2 ? -1 : 1);
      const nx = cx + dx, nz = cz + dz; if (nx < 0 || nz < 0 || nx >= W || nz >= W) continue;
      const c = cost(col, ox + nx, oz + nz); if (c < 0) continue;
      if (k >= 4 && (cost(col, ox + nx, oz + cz) < 0 || cost(col, ox + cx, oz + nz) < 0)) continue;
      // hug-the-wall penalty keeps people off corners
      let pen = 0;
      if (cost(col, ox + nx + 1, oz + nz) < 0 || cost(col, ox + nx - 1, oz + nz) < 0 || cost(col, ox + nx, oz + nz + 1) < 0 || cost(col, ox + nx, oz + nz - 1) < 0) pen = 0.6;
      const ni = nz * W + nx; const ng = g[i] + (k < 4 ? 1 : 1.4142) + c + pen;
      if (stamp[ni] === gen && ng >= g[ni]) continue;
      g[ni] = ng; stamp[ni] = gen; from[ni] = i;
      const h = Math.hypot(ex - nx, ez - nz); fval[ni] = ng + h * 1.05;
      if (h < bestH) { bestH = h; best = ni; }
      push(ni);
    }
  }
  if (best === si) return null;
  // walk back, then pull the string tight
  const cells: number[] = [];
  for (let i = best; i !== -1; i = from[i]) cells.push(i);
  cells.reverse();
  const out: number[] = [];
  let a = 0;
  while (a < cells.length - 1) {
    let b = cells.length - 1;
    const axc = cells[a] % W + ox + 0.5, azc = ((cells[a] / W) | 0) + oz + 0.5;
    for (; b > a + 1; b--) { if (clear(col, axc, azc, cells[b] % W + ox + 0.5, ((cells[b] / W) | 0) + oz + 0.5)) break; }
    out.push(cells[b] % W + ox + 0.5 - HALF_WORLD, ((cells[b] / W) | 0) + oz + 0.5 - HALF_WORLD);
    a = b;
  }
  return out;
}
