// Axis-aligned collision world + a 1m occupancy grid for zombie navigation.
import * as THREE from 'three';
import { HALF_WORLD, WORLD_SIZE } from '../core/util';

export interface Box { minX: number; minY: number; minZ: number; maxX: number; maxY: number; maxZ: number; id: number; tag?: string; ref?: any; solid: boolean; }
export interface Circle { x: number; z: number; r: number; h: number; id: number; ref?: any }

const CELL = 8;
const NAV = 1; // nav grid resolution (m)
export const NAV_N = WORLD_SIZE / NAV;

export const NAV_FREE = 0, NAV_SOLID = 255; // 1..254 = destructible (cost)

export class CollisionWorld {
  private boxes = new Map<number, Box>();
  private circles = new Map<number, Circle>();
  private cells = new Map<number, number[]>();
  private nextId = 1;
  /** static nav occupancy: 0 free, 255 blocked, other = destructible structure cost */
  nav = new Uint8Array(NAV_N * NAV_N);
  /** structure id occupying nav cell (for zombies to attack) */
  navRef = new Map<number, any>();

  private key(cx: number, cz: number) { return (cx + 1000) * 4096 + (cz + 1000); }

  addBox(minX: number, minY: number, minZ: number, maxX: number, maxY: number, maxZ: number, opts: { tag?: string; ref?: any; nav?: boolean; navCost?: number; solid?: boolean } = {}): Box {
    const b: Box = { minX, minY, minZ, maxX, maxY, maxZ, id: this.nextId++, tag: opts.tag, ref: opts.ref, solid: opts.solid ?? true };
    this.boxes.set(b.id, b);
    this.forCells(minX, minZ, maxX, maxZ, k => { let a = this.cells.get(k); if (!a) { a = []; this.cells.set(k, a); } a.push(b.id); });
    if (opts.nav !== false && maxY > 0.6 && minY < 1.6) this.rasterNav(b, opts.navCost ?? NAV_SOLID, opts.ref);
    return b;
  }
  removeBox(b: Box) {
    if (!this.boxes.has(b.id)) return;
    this.boxes.delete(b.id);
    this.forCells(b.minX, b.minZ, b.maxX, b.maxZ, k => { const a = this.cells.get(k); if (a) { const i = a.indexOf(b.id); if (i >= 0) a.splice(i, 1); } });
    this.rasterNav(b, NAV_FREE, undefined, true);
  }
  addCircle(x: number, z: number, r: number, h: number, ref?: any): Circle {
    const c: Circle = { x, z, r, h, id: this.nextId++, ref };
    this.circles.set(c.id, c);
    this.forCells(x - r, z - r, x + r, z + r, k => { let a = this.cells.get(k); if (!a) { a = []; this.cells.set(k, a); } a.push(c.id); });
    if (r > 0.25) this.rasterCircle(c, NAV_SOLID);
    return c;
  }
  removeCircle(c: Circle) {
    if (!this.circles.has(c.id)) return;
    this.circles.delete(c.id);
    this.forCells(c.x - c.r, c.z - c.r, c.x + c.r, c.z + c.r, k => { const a = this.cells.get(k); if (a) { const i = a.indexOf(c.id); if (i >= 0) a.splice(i, 1); } });
    if (c.r > 0.25) this.rasterCircle(c, NAV_FREE);
  }
  setSolid(b: Box, solid: boolean, navCost = NAV_SOLID) {
    b.solid = solid;
    this.rasterNav(b, solid ? navCost : NAV_FREE, solid ? b.ref : undefined, !solid);
  }

  private forCells(minX: number, minZ: number, maxX: number, maxZ: number, f: (k: number) => void) {
    const x0 = Math.floor(minX / CELL), x1 = Math.floor(maxX / CELL), z0 = Math.floor(minZ / CELL), z1 = Math.floor(maxZ / CELL);
    for (let x = x0; x <= x1; x++) for (let z = z0; z <= z1; z++) f(this.key(x, z));
  }
  private rasterNav(b: Box, v: number, ref: any, clear = false) {
    const x0 = Math.floor((b.minX + HALF_WORLD) / NAV), x1 = Math.floor((b.maxX - 0.001 + HALF_WORLD) / NAV);
    const z0 = Math.floor((b.minZ + HALF_WORLD) / NAV), z1 = Math.floor((b.maxZ - 0.001 + HALF_WORLD) / NAV);
    for (let x = Math.max(0, x0); x <= Math.min(NAV_N - 1, x1); x++) for (let z = Math.max(0, z0); z <= Math.min(NAV_N - 1, z1); z++) {
      const i = z * NAV_N + x;
      if (clear) { this.nav[i] = NAV_FREE; this.navRef.delete(i); }
      else { if (this.nav[i] === NAV_SOLID && v !== NAV_SOLID) continue; this.nav[i] = v; if (ref && v !== NAV_SOLID) this.navRef.set(i, ref); }
    }
  }
  private rasterCircle(c: Circle, v: number) {
    const x0 = Math.floor((c.x - c.r * 0.7 + HALF_WORLD) / NAV), x1 = Math.floor((c.x + c.r * 0.7 + HALF_WORLD) / NAV);
    const z0 = Math.floor((c.z - c.r * 0.7 + HALF_WORLD) / NAV), z1 = Math.floor((c.z + c.r * 0.7 + HALF_WORLD) / NAV);
    for (let x = Math.max(0, x0); x <= Math.min(NAV_N - 1, x1); x++) for (let z = Math.max(0, z0); z <= Math.min(NAV_N - 1, z1); z++) this.nav[z * NAV_N + x] = v;
  }
  navIndex(x: number, z: number) {
    const ix = Math.floor((x + HALF_WORLD) / NAV), iz = Math.floor((z + HALF_WORLD) / NAV);
    if (ix < 0 || iz < 0 || ix >= NAV_N || iz >= NAV_N) return -1;
    return iz * NAV_N + ix;
  }

  private tmp: number[] = [];
  private stamp = 0;
  private seen = new Map<number, number>();
  query(minX: number, minZ: number, maxX: number, maxZ: number, out: (Box | Circle)[]) {
    out.length = 0; this.stamp++;
    this.forCells(minX, minZ, maxX, maxZ, k => {
      const a = this.cells.get(k); if (!a) return;
      for (const id of a) {
        if (this.seen.get(id) === this.stamp) continue; this.seen.set(id, this.stamp);
        const b = this.boxes.get(id); if (b) { if (b.solid && b.maxX > minX && b.minX < maxX && b.maxZ > minZ && b.minZ < maxZ) out.push(b); continue; }
        const c = this.circles.get(id); if (c && c.x + c.r > minX && c.x - c.r < maxX && c.z + c.r > minZ && c.z - c.r < maxZ) out.push(c);
      }
    });
    return out;
  }

  private qbuf: (Box | Circle)[] = [];
  /** Resolve a vertical cylinder (feet at y, height h, radius r) against the world. Mutates pos. Returns true if collided. */
  resolveCylinder(pos: THREE.Vector3, r: number, h: number, stepHeight = 0.45, ignoreTag?: string): { hit: boolean; groundY: number } {
    const res = { hit: false, groundY: -Infinity };
    const list = this.query(pos.x - r - 0.1, pos.z - r - 0.1, pos.x + r + 0.1, pos.z + r + 0.1, this.qbuf);
    for (let iter = 0; iter < 2; iter++) {
      for (const o of list) {
        if ('minX' in o) {
          if (ignoreTag && o.tag === ignoreTag) continue;
          if (pos.y + h <= o.minY || pos.y >= o.maxY) continue;
          // standable top (stairs, foundations, low props)
          if (o.maxY - pos.y <= stepHeight && pos.x > o.minX - r * 0.5 && pos.x < o.maxX + r * 0.5 && pos.z > o.minZ - r * 0.5 && pos.z < o.maxZ + r * 0.5) {
            if (o.maxY > res.groundY) res.groundY = o.maxY; continue;
          }
          const cx = Math.max(o.minX, Math.min(pos.x, o.maxX)), cz = Math.max(o.minZ, Math.min(pos.z, o.maxZ));
          let dx = pos.x - cx, dz = pos.z - cz;
          const d2 = dx * dx + dz * dz;
          if (d2 >= r * r) continue;
          if (d2 < 1e-8) {
            // centre inside box: push out along smallest axis
            const pl = pos.x - o.minX, pr = o.maxX - pos.x, pb = pos.z - o.minZ, pf = o.maxZ - pos.z;
            const m = Math.min(pl, pr, pb, pf);
            if (m === pl) pos.x = o.minX - r; else if (m === pr) pos.x = o.maxX + r; else if (m === pb) pos.z = o.minZ - r; else pos.z = o.maxZ + r;
          } else {
            const d = Math.sqrt(d2); const push = r - d; dx /= d; dz /= d;
            pos.x += dx * push; pos.z += dz * push;
          }
          res.hit = true;
        } else {
          if (pos.y > o.h) continue;
          const dx = pos.x - o.x, dz = pos.z - o.z; const d2 = dx * dx + dz * dz; const rr = r + o.r;
          if (d2 >= rr * rr || d2 < 1e-8) continue;
          const d = Math.sqrt(d2); const push = rr - d;
          pos.x += dx / d * push; pos.z += dz / d * push; res.hit = true;
        }
      }
    }
    return res;
  }

  /** Ray vs boxes/circles within maxDist. Returns distance or Infinity. */
  raycast(origin: THREE.Vector3, dir: THREE.Vector3, maxDist: number, filter?: (o: Box | Circle) => boolean): { t: number; obj: Box | Circle | null; normal: THREE.Vector3 } {
    const end = origin.clone().addScaledVector(dir, maxDist);
    const list = this.query(Math.min(origin.x, end.x) - 0.5, Math.min(origin.z, end.z) - 0.5, Math.max(origin.x, end.x) + 0.5, Math.max(origin.z, end.z) + 0.5, []);
    let best = Infinity, bestObj: Box | Circle | null = null; const n = new THREE.Vector3();
    for (const o of list) {
      if (filter && !filter(o)) continue;
      if ('minX' in o) {
        let tmin = 0, tmax = maxDist; let axis = -1, sign = 0;
        const mins = [o.minX, o.minY, o.minZ], maxs = [o.maxX, o.maxY, o.maxZ], org = [origin.x, origin.y, origin.z], d = [dir.x, dir.y, dir.z];
        let ok = true;
        for (let a = 0; a < 3; a++) {
          if (Math.abs(d[a]) < 1e-9) { if (org[a] < mins[a] || org[a] > maxs[a]) { ok = false; break; } continue; }
          let t1 = (mins[a] - org[a]) / d[a], t2 = (maxs[a] - org[a]) / d[a]; let s = -1;
          if (t1 > t2) { const t = t1; t1 = t2; t2 = t; s = 1; }
          if (t1 > tmin) { tmin = t1; axis = a; sign = s; }
          if (t2 < tmax) tmax = t2;
          if (tmin > tmax) { ok = false; break; }
        }
        if (ok && tmin < best && tmin > 0) { best = tmin; bestObj = o; n.set(0, 0, 0); if (axis >= 0) (n as any).setComponent(axis, sign); }
      } else {
        const ox = origin.x - o.x, oz = origin.z - o.z; const a = dir.x * dir.x + dir.z * dir.z; if (a < 1e-9) continue;
        const b = 2 * (ox * dir.x + oz * dir.z), c = ox * ox + oz * oz - o.r * o.r; const disc = b * b - 4 * a * c; if (disc < 0) continue;
        const t = (-b - Math.sqrt(disc)) / (2 * a);
        if (t > 0 && t < best && origin.y + dir.y * t < o.h) { best = t; bestObj = o; n.set(ox + dir.x * t, 0, oz + dir.z * t).normalize(); }
      }
    }
    return { t: best, obj: bestObj, normal: n };
  }

  /** line-of-sight test (for zombie vision); y ignored except boxes above head */
  blocked(ax: number, ay: number, az: number, bx: number, by: number, bz: number) {
    const o = new THREE.Vector3(ax, ay, az); const d = new THREE.Vector3(bx - ax, by - ay, bz - az); const L = d.length(); if (L < 0.01) return false; d.divideScalar(L);
    return this.raycast(o, d, L, ob => 'minX' in ob && (ob.maxY - ob.minY) > 1.2).t < L;
  }
}
