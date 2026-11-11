// Railways: grid track with auto curves and junctions, freight stations, locomotives + wagons on schedules.
import * as THREE from 'three';
import { GRID, tileOf } from '../core/util';
import { World } from '../world/world';
import { Factory, Machine } from './factory';
import { Inventory } from '../items/inventory';
import { item } from '../items/items';
import { buildLocomotive, buildWagon, trackGeometries } from '../models/trains';
import { audio } from '../audio/Audio';
import { bus } from '../core/util';
import type { Zombies } from '../ai/zombies';
import type { Particles } from '../render/particles';

const tkey = (tx: number, tz: number) => (tx + 1000) * 4096 + (tz + 1000);
const DIRS: [number, number][] = [[0, 1], [1, 0], [0, -1], [-1, 0]]; // +Z, +X, -Z, -X

export interface Car { kind: 'loco' | 'wagon'; obj: THREE.Group; inv: Inventory | null; pos: THREE.Vector3; yaw: number; len: number }
export interface Train {
  id: number; name: string; cars: Car[]; schedule: number[]; idx: number; state: 'idle' | 'moving' | 'dwell' | 'stuck';
  path: THREE.Vector3[]; seg: number; segT: number; speed: number; dwell: number; fuel: Inventory; burn: number;
  trail: THREE.Vector3[]; auto: boolean; msg: string; kind: 'train'; noiseT: number;
}

export class Railways {
  rails = new Map<number, { tx: number; tz: number; y: number }>();
  trains: Train[] = [];
  private meshes: Record<string, THREE.InstancedMesh> = {};
  private nextId = 1;
  group = new THREE.Group();
  private dirty = true;

  constructor(private world: World, private factory: Factory, scene: THREE.Scene) {
    scene.add(this.group);
    const T = trackGeometries();
    const mk = (name: string, geo: THREE.BufferGeometry, mat: THREE.Material) => { const im = new THREE.InstancedMesh(geo, mat, 6000); im.count = 0; im.castShadow = true; im.receiveShadow = true; im.frustumCulled = false; this.group.add(im); this.meshes[name] = im; };
    for (const k of ['ballast', 'sleepers', 'rails'] as const) { mk('arm_' + k, T.arm[k], T.mats[k]); mk('curve_' + k, T.curve[k], T.mats[k]); }
    mk('stop', T.stop, T.mats.stop);
  }

  // ------------------------------------------------------------------ track
  has(tx: number, tz: number) { return this.rails.has(tkey(tx, tz)); }
  canPlace(tx: number, tz: number) {
    if (this.has(tx, tz)) return { ok: false, reason: 'Track already here' };
    if (this.factory.machineAtTile(tx, tz)) return { ok: false, reason: 'Blocked by a machine' };
    if (this.factory.pipes.has(tkey(tx, tz))) return { ok: false, reason: 'Blocked by a pipe' };
    const cx = (tx + 0.5) * GRID, cz = (tz + 0.5) * GRID;
    if (this.world.town.isOccupied(cx, cz, -0.5)) return { ok: false, reason: 'Inside a building' };
    if (this.world.waterDepth(cx, cz) > 0.3) return { ok: false, reason: 'In water' };
    return { ok: true };
  }
  add(tx: number, tz: number) { this.rails.set(tkey(tx, tz), { tx, tz, y: this.world.groundY((tx + 0.5) * GRID, (tz + 0.5) * GRID) }); this.dirty = true; }
  remove(tx: number, tz: number) { if (this.rails.delete(tkey(tx, tz))) this.dirty = true; }
  railAt(x: number, z: number) { const tx = tileOf(x), tz = tileOf(z); return this.has(tx, tz) ? { kind: 'rail' as const, tx, tz, cx: (tx + 0.5) * GRID, cz: (tz + 0.5) * GRID } : null; }
  private conns(tx: number, tz: number) { return DIRS.map(([dx, dz]) => this.has(tx + dx, tz + dz)); }

  private rebuildTrack() {
    this.dirty = false;
    const M = new THREE.Matrix4(), Q = new THREE.Quaternion(), V = new THREE.Vector3(), S = new THREE.Vector3(1, 1, 1);
    const counts: Record<string, number> = {};
    const put = (name: string, m: THREE.Matrix4) => { const im = this.meshes[name]; const i = counts[name] ?? 0; if (i < 6000) { im.setMatrixAt(i, m); counts[name] = i + 1; } };
    for (const r of this.rails.values()) {
      const c = this.conns(r.tx, r.tz); const n = c.filter(Boolean).length;
      V.set((r.tx + 0.5) * GRID, r.y, (r.tz + 0.5) * GRID);
      // curve when exactly two perpendicular neighbours
      let curveRot = -1;
      if (n === 2) for (let d = 0; d < 4; d++) if (c[d] && c[(d + 1) % 4]) curveRot = d;
      if (curveRot >= 0) {
        // base curve links +Z (d=0) and +X (d=1); rotate by -90° per step
        Q.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, -curveRot * Math.PI / 2); M.compose(V, Q, S);
        for (const k of ['ballast', 'sleepers', 'rails']) put('curve_' + k, M);
        continue;
      }
      const arms = n === 0 ? [0, 2] : c.map((v, d) => v ? d : -1).filter(d => d >= 0);
      if (n === 1) arms.push((arms[0] + 2) % 4); // straight through with a buffer stop
      for (const d of arms) {
        Q.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, Math.atan2(DIRS[d][0], DIRS[d][1])); M.compose(V, Q, S);
        for (const k of ['ballast', 'sleepers', 'rails']) put('arm_' + k, M);
      }
      if (n === 1) { const d = c.findIndex(Boolean); Q.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, Math.atan2(DIRS[d][0], DIRS[d][1])); M.compose(V, Q, S); put('stop', M); }
    }
    for (const [k, im] of Object.entries(this.meshes)) { im.count = counts[k] ?? 0; im.instanceMatrix.needsUpdate = true; }
  }

  // ------------------------------------------------------------------ routing
  private route(fromTx: number, fromTz: number, toTx: number, toTz: number): [number, number][] | null {
    const start = tkey(fromTx, fromTz), goal = tkey(toTx, toTz);
    if (!this.rails.has(start) || !this.rails.has(goal)) return null;
    const prev = new Map<number, number>(); prev.set(start, -1);
    const q = [start];
    while (q.length) {
      const k = q.shift()!; if (k === goal) break;
      const r = this.rails.get(k)!;
      for (const [dx, dz] of DIRS) { const nk = tkey(r.tx + dx, r.tz + dz); if (this.rails.has(nk) && !prev.has(nk)) { prev.set(nk, k); q.push(nk); } }
    }
    if (!prev.has(goal)) return null;
    const out: [number, number][] = []; let k = goal;
    while (k !== -1) { const r = this.rails.get(k)!; out.push([r.tx, r.tz]); k = prev.get(k)!; }
    return out.reverse();
  }
  /** smooth polyline through the tile route: arcs on 90° turns */
  private polyline(tiles: [number, number][]): THREE.Vector3[] {
    const pts: THREE.Vector3[] = [];
    const Y = (tx: number, tz: number) => this.rails.get(tkey(tx, tz))?.y ?? 0;
    for (let i = 0; i < tiles.length; i++) {
      const [tx, tz] = tiles[i]; const c = new THREE.Vector3((tx + 0.5) * GRID, Y(tx, tz) + 0.4, (tz + 0.5) * GRID);
      if (i > 0 && i < tiles.length - 1) {
        const inD = [tx - tiles[i - 1][0], tz - tiles[i - 1][1]], outD = [tiles[i + 1][0] - tx, tiles[i + 1][1] - tz];
        if (inD[0] !== outD[0] || inD[1] !== outD[1]) {
          const h = GRID / 2;
          const pivot = new THREE.Vector3(c.x - inD[0] * h + outD[0] * h, c.y, c.z - inD[1] * h + outD[1] * h);
          for (let k = 0; k <= 4; k++) { const a = k / 4 * Math.PI / 2; pts.push(new THREE.Vector3(pivot.x - outD[0] * h * Math.cos(a) + inD[0] * h * Math.sin(a), c.y, pivot.z - outD[1] * h * Math.cos(a) + inD[1] * h * Math.sin(a))); }
          continue;
        }
      }
      pts.push(c);
    }
    return pts;
  }

  // ------------------------------------------------------------------ rolling stock
  stopTile(st: Machine): [number, number] | null {
    let best: [number, number] | null = null, bd = 1e9;
    for (const k of st.tiles) {
      const tx = Math.floor(k / 4096) - 1000, tz = (k % 4096) - 1000;
      for (const [dx, dz] of DIRS) if (this.has(tx + dx, tz + dz)) { const d = Math.hypot(tx + dx - tileOf(st.cx), tz + dz - tileOf(st.cz)); if (d < bd) { bd = d; best = [tx + dx, tz + dz]; } }
    }
    return best;
  }
  placeCar(kind: 'loco' | 'wagon', tx: number, tz: number, rot: number): Train | null {
    if (!this.has(tx, tz)) return null;
    const obj = kind === 'loco' ? buildLocomotive() : buildWagon();
    this.group.add(obj);
    const car: Car = { kind, obj, inv: kind === 'wagon' ? new Inventory(600) : null, pos: new THREE.Vector3((tx + 0.5) * GRID, this.rails.get(tkey(tx, tz))!.y + 0.4, (tz + 0.5) * GRID), yaw: rot * Math.PI / 2, len: obj.userData.length };
    // couple to the nearest train end within reach
    for (const t of this.trains) {
      const last = t.cars[t.cars.length - 1];
      if (last.pos.distanceTo(car.pos) < (last.len + car.len) / 2 + 4) {
        t.cars.push(car); this.extendTrail(t); this.layout(t); bus.emit('notify', `🚃 Coupled to ${t.name} (${t.cars.length} cars).`, 'good'); return t;
      }
    }
    const t: Train = { id: this.nextId++, name: `Train ${this.nextId - 1}`, cars: [car], schedule: [], idx: 0, state: 'idle', path: [], seg: 0, segT: 0, speed: 0, dwell: 0, fuel: new Inventory(80), burn: 0, trail: [], auto: true, msg: 'No schedule', kind: 'train', noiseT: 0 };
    if (kind === 'loco') t.fuel.add('coal', 12); // starter bunker
    // seed the trail along the track behind the car
    const fwd = new THREE.Vector3(Math.sin(car.yaw), 0, Math.cos(car.yaw));
    for (let d = 0; d <= 40; d += 0.5) t.trail.push(car.pos.clone().addScaledVector(fwd, -d));
    this.trains.push(t); this.layout(t);
    return t;
  }
  private extendTrail(t: Train) {
    const need = t.cars.reduce((a, c) => a + c.len + 1, 0) + 10;
    let have = 0; for (let i = 1; i < t.trail.length; i++) have += t.trail[i].distanceTo(t.trail[i - 1]);
    const tail = t.trail[t.trail.length - 1], prev = t.trail[t.trail.length - 2] ?? tail.clone().add(new THREE.Vector3(0, 0, 1));
    const dir = tail.clone().sub(prev).normalize();
    for (let d = 0.5; have + d <= need; d += 0.5) t.trail.push(tail.clone().addScaledVector(dir, d));
  }
  /** place each car along the head trail */
  private layout(t: Train) {
    let dist = 0;
    t.cars.forEach((c, i) => {
      const center = dist + c.len / 2;
      const p = this.trailAt(t, center), q = this.trailAt(t, center + 1);
      c.pos.copy(p);
      if (p.distanceToSquared(q) > 1e-6) c.yaw = Math.atan2(p.x - q.x, p.z - q.z) + (i === 0 || c.kind === 'wagon' ? 0 : 0);
      c.obj.position.copy(p).setY(p.y - 0.4); c.obj.rotation.set(0, c.yaw, 0);
      dist += c.len + 1;
    });
  }
  private trailAt(t: Train, d: number): THREE.Vector3 {
    let acc = 0;
    for (let i = 1; i < t.trail.length; i++) {
      const a = t.trail[i - 1], b = t.trail[i]; const L = a.distanceTo(b);
      if (acc + L >= d) return a.clone().lerp(b, (d - acc) / Math.max(L, 1e-6));
      acc += L;
    }
    return t.trail[t.trail.length - 1].clone();
  }
  /** reverse the consist so the train can head the other way (push-pull) */
  private reverse(t: Train) {
    const tailDist = t.cars.reduce((a, c) => a + c.len + 1, 0) - 1;
    const pts: THREE.Vector3[] = []; for (let d = tailDist; d >= 0; d -= 0.5) pts.push(this.trailAt(t, d));
    t.trail = pts; t.cars.reverse(); this.extendTrail(t);
  }
  locoOf(t: Train) { return t.cars.find(c => c.kind === 'loco') ?? null; }
  headTile(t: Train): [number, number] {
    for (let i = 0; i < Math.min(t.trail.length, 40); i++) { const h = t.trail[i]; const tx = tileOf(h.x), tz = tileOf(h.z); if (this.has(tx, tz)) return [tx, tz]; }
    const h = t.trail[0]; return [tileOf(h.x), tileOf(h.z)];
  }
  stations() { return this.factory.machines.filter(m => m.type === 'train_station'); }

  depart(t: Train) {
    const sts = this.stations();
    t.schedule = t.schedule.filter(id => sts.some(s => s.id === id));
    if (!t.schedule.length) { t.state = 'idle'; t.msg = 'No schedule'; return; }
    if (!this.locoOf(t)) { t.state = 'idle'; t.msg = 'Needs a locomotive'; return; }
    const st = sts.find(s => s.id === t.schedule[t.idx % t.schedule.length])!;
    const target = this.stopTile(st);
    if (!target) { t.state = 'stuck'; t.msg = `${(st as any).stName ?? 'Station'} is not next to track`; return; }
    const [hx, hz] = this.headTile(t);
    let tiles = this.route(hx, hz, target[0], target[1]);
    if (!tiles) { t.state = 'stuck'; t.msg = 'No route to ' + ((st as any).stName ?? 'station'); return; }
    // if the first step points back along the consist, reverse (push-pull)
    if (tiles.length > 1 && t.trail.length > 4) {
      const back = t.trail[4]; const step = new THREE.Vector3((tiles[1][0] + 0.5) * GRID, 0, (tiles[1][1] + 0.5) * GRID);
      const head = t.trail[0];
      if (step.clone().sub(head).setY(0).dot(back.clone().sub(head).setY(0)) > 0) { this.reverse(t); const [ax, az] = this.headTile(t); tiles = this.route(ax, az, target[0], target[1]) ?? tiles; }
    }
    t.path = [t.trail[0].clone(), ...this.polyline(tiles).slice(1)];
    t.seg = 0; t.segT = 0; t.state = 'moving'; t.msg = `→ ${(st as any).stName ?? 'Station'}`;
  }

  private arrive(t: Train) {
    t.state = 'dwell'; t.dwell = 6; t.speed = 0;
    const st = this.stations().find(s => s.id === t.schedule[t.idx % t.schedule.length]); if (!st || !st.inv) return;
    const mode = (st as any).stMode ?? 'unload';
    const wagons = t.cars.filter(c => c.inv);
    let moved = 0;
    if (mode === 'load') {
      for (const s of [...st.inv.stacks]) { if (s.id === 'coal' && this.locoOf(t)) continue; for (const w of wagons) { const k = Math.min(s.n, Math.floor((w.inv!.capacity - w.inv!.weight()) / Math.max(0.001, item(s.id).weight))); if (k > 0) { st.inv.remove(s.id, k); w.inv!.add(s.id, k, s); moved += k; } if (!st.inv.byUid(s.uid)) break; } }
    } else {
      for (const w of wagons) for (const s of [...w.inv!.stacks]) { const k = Math.min(s.n, Math.floor((st.inv.capacity - st.inv.weight()) / Math.max(0.001, item(s.id).weight))); if (k > 0) { w.inv!.remove(s.id, k); st.inv.add(s.id, k, s); moved += k; } }
    }
    // refuel from coal stored at the station
    const loco = this.locoOf(t);
    if (loco) { const need = 40 - t.fuel.count('coal'); const k = Math.min(need, st.inv.count('coal')); if (k > 0) { st.inv.remove('coal', k); t.fuel.add('coal', k); } }
    t.msg = `${mode === 'load' ? 'Loading' : 'Unloading'} at ${(st as any).stName ?? 'station'} (${moved} items)`;
    audio.play('machine_place', { pos: t.cars[0].pos, volume: 0.5 });
  }

  update(dt: number, ctx: { zombies: Zombies; particles: Particles; player: THREE.Vector3; onPlayerHit: (speed: number) => void }) {
    if (this.dirty) this.rebuildTrack();
    for (const t of this.trains) {
      if (t.state === 'idle' || t.state === 'stuck') { if (t.auto && t.schedule.length && Math.random() < dt) this.depart(t); }
      else if (t.state === 'dwell') { t.dwell -= dt; if (t.dwell <= 0) { t.idx = (t.idx + 1) % Math.max(1, t.schedule.length); if (t.auto) this.depart(t); else { t.state = 'idle'; t.msg = 'Holding — press Depart now'; } } }
      else if (t.state === 'moving') {
        // fuel
        if (t.burn <= 0) { if (t.fuel.count('coal') > 0) { t.fuel.remove('coal', 1); t.burn = 45; } else if (t.fuel.count('f_fuel') || t.fuel.count('gas_can')) { t.fuel.remove(t.fuel.count('gas_can') ? 'gas_can' : 'f_fuel', 1); t.burn = 90; } else if (this.refuelHere(t)) { /* topped up from a station beside the track */ } else { t.state = 'stuck'; t.msg = 'Out of coal — load coal into the locomotive or a station'; t.speed = 0; continue; } }
        t.burn -= dt * (0.3 + t.speed / 14);
        // remaining distance for braking
        let remain = 0; for (let i = t.seg; i < t.path.length - 1; i++) remain += t.path[i].distanceTo(t.path[i + 1]);
        remain -= t.segT;
        const maxV = Math.min(14, Math.sqrt(Math.max(0, remain) * 2 * 1.6) + 0.5);
        t.speed += Math.sign(maxV - t.speed) * Math.min(Math.abs(maxV - t.speed), dt * (maxV > t.speed ? 1.6 : 3));
        let move = t.speed * dt;
        while (move > 0 && t.seg < t.path.length - 1) {
          const a = t.path[t.seg], b = t.path[t.seg + 1]; const L = a.distanceTo(b);
          const left = L - t.segT;
          if (move < left) { t.segT += move; move = 0; } else { move -= left; t.seg++; t.segT = 0; }
        }
        const head = t.seg >= t.path.length - 1 ? t.path[t.path.length - 1].clone() : t.path[t.seg].clone().lerp(t.path[t.seg + 1], t.segT / Math.max(1e-6, t.path[t.seg].distanceTo(t.path[t.seg + 1])));
        if (head.distanceTo(t.trail[0]) > 0.25) { t.trail.unshift(head); if (t.trail.length > 400) t.trail.length = 400; }
        else t.trail[0].copy(head);
        if (t.seg >= t.path.length - 1 && t.speed < 0.6) this.arrive(t);
        // horn & noise
        t.noiseT -= dt; if (t.noiseT <= 0) { t.noiseT = 1; ctx.zombies.noise(head, 30); }
        // run down zombies; warn/hit the player
        for (const z of ctx.zombies.list) {
          if (z.state === 'dead') continue;
          if (z.pos.distanceToSquared(head) < 4 && t.speed > 2) { ctx.zombies.damage(z, 400, new THREE.Vector3(Math.sin(t.cars[0].yaw), 0, Math.cos(t.cars[0].yaw)), false, 6, false); ctx.particles.blood(z.pos.clone().setY(z.pos.y + 1), new THREE.Vector3(0, 1, 0), 1.5); }
        }
        if (ctx.player.distanceToSquared(head) < 3 && t.speed > 3) ctx.onPlayerHit(t.speed);
      }
      this.layout(t);
      // wheels & cargo visuals
      for (const c of t.cars) {
        for (const w of (c.obj.userData.wheels as THREE.Object3D[])) w.rotation.x += t.speed * dt / 0.42;
        if (c.inv) { const cargo = c.obj.userData.cargo as THREE.Mesh; cargo.scale.y = Math.max(0.01, Math.min(1, c.inv.weight() / 600)); cargo.position.y = 1.2 + cargo.scale.y * 0.5; }
      }
    }
  }

  /** take coal from any station whose stop tile the train is standing on */
  private refuelHere(t: Train) {
    const [hx, hz] = this.headTile(t);
    for (const st of this.stations()) {
      const s = this.stopTile(st); if (!s || Math.abs(s[0] - hx) + Math.abs(s[1] - hz) > 2 || !st.inv) continue;
      const k = Math.min(40 - t.fuel.count('coal'), st.inv.count('coal')); if (k > 0) { st.inv.remove('coal', k); t.fuel.add('coal', k); return true; }
    }
    return false;
  }
  nearestCar(p: THREE.Vector3, r: number): { train: Train; car: Car } | null {
    let best: { train: Train; car: Car } | null = null, bd = r;
    for (const t of this.trains) for (const c of t.cars) { const d = c.pos.distanceTo(p) - c.len / 2; if (d < bd) { bd = d; best = { train: t, car: c }; } }
    return best;
  }
  removeTrain(t: Train) { for (const c of t.cars) this.group.remove(c.obj); this.trains.splice(this.trains.indexOf(t), 1); }

  toJSON() {
    return {
      rails: [...this.rails.values()].map(r => [r.tx, r.tz]),
      trains: this.trains.map(t => ({ name: t.name, schedule: t.schedule, idx: t.idx, fuel: t.fuel.stacks, auto: t.auto, trail: t.trail.slice(0, 120).map(p => [p.x, p.y, p.z]), cars: t.cars.map(c => ({ kind: c.kind, inv: c.inv?.stacks ?? null })) })),
    };
  }
  load(o: any) {
    if (!o) return;
    for (const [tx, tz] of o.rails ?? []) this.add(tx, tz);
    for (const s of o.trains ?? []) {
      const t: Train = { id: this.nextId++, name: s.name, cars: [], schedule: s.schedule ?? [], idx: s.idx ?? 0, state: 'idle', path: [], seg: 0, segT: 0, speed: 0, dwell: 0, fuel: new Inventory(80), burn: 0, trail: (s.trail ?? []).map((a: number[]) => new THREE.Vector3(a[0], a[1], a[2])), auto: s.auto ?? true, msg: '', kind: 'train', noiseT: 0 };
      t.fuel.load(s.fuel ?? []);
      for (const c of s.cars) { const obj = c.kind === 'loco' ? buildLocomotive() : buildWagon(); this.group.add(obj); const car: Car = { kind: c.kind, obj, inv: c.kind === 'wagon' ? new Inventory(600) : null, pos: new THREE.Vector3(), yaw: 0, len: obj.userData.length }; if (c.inv && car.inv) car.inv.load(c.inv); t.cars.push(car); }
      if (t.trail.length < 2) continue;
      this.extendTrail(t); this.trains.push(t); this.layout(t);
    }
  }
}
