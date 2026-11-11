// Zombie director & AI: senses (sight/hearing), flow-field pursuit, hordes, structure bashing, combat interface.
import * as THREE from 'three';
import { RNG, clamp, angleDiff, HALF_WORLD } from '../core/util';
import { World } from '../world/world';
import { NAV_N, NAV_SOLID, NAV_FREE } from '../world/collision';
import { buildZombieGeometry, makeZombieMaterials } from './zombieMesh';
import { TOWN } from '../world/layout';
import { Inventory } from '../items/inventory';
import { rollLoot } from '../items/loot';
import { audio } from '../audio/Audio';

export type ZType = 0 | 1 | 2; // walker, runner, brute
export type ZState = 'idle' | 'wander' | 'investigate' | 'chase' | 'attack' | 'bash' | 'stagger' | 'dead';

export interface Zombie {
  id: number; type: ZType; pos: THREE.Vector3; vel: THREE.Vector3; yaw: number; hp: number; maxHp: number;
  state: ZState; target: THREE.Vector3; stateT: number; phase: number; move: number; attackT: number; atkBlend: number;
  hit: number; arms: number; death: number; deathDir: number; seed: number; scale: number; lean: number; limp: number;
  speed: number; runSpeed: number; senseT: number; alerted: number; lastSeen: THREE.Vector3; horde: number; groanT: number;
  bashRef: any; corpse: Inventory | null; corpseAge: number; frozen: boolean; stuckT: number; lastPos: THREE.Vector3; knock: THREE.Vector3;
}
export interface HumanPose { pos: THREE.Vector3; yaw: number; move: number; phase: number; arms: number; atk: number; seed: number; type: 3 | 4; hit: number; visible: boolean }
export interface Horde { id: number; target: THREE.Vector3; members: number; t: number; toBase: boolean }

const MAX = 700;
const FIELD = 112;          // flow field window (cells, 1 m)

export interface ZombieHost {
  playerPos: THREE.Vector3; playerEye: THREE.Vector3; playerNoise: number; playerVisibility: number; playerAlive: boolean; playerInside: boolean;
  onPlayerAttacked(z: Zombie): void;
  onStructureHit(ref: any, dmg: number, z: Zombie): void;
  onKill(z: Zombie, byPlayer: boolean): void;
  baseCenter: THREE.Vector3 | null; baseHeat: number; daylight: number; hours: number; day: number; luck: number;
}

export class Zombies {
  list: Zombie[] = [];
  hordes: Horde[] = [];
  mesh: THREE.InstancedMesh;
  private aAnim: THREE.InstancedBufferAttribute; private aAnim2: THREE.InstancedBufferAttribute; private aVar: THREE.InstancedBufferAttribute;
  private time = { value: 0 };
  private rng: RNG;
  private nextId = 1;
  private grid = new Map<number, Zombie[]>();
  // flow field
  private dist = new Uint16Array(FIELD * FIELD);
  private fieldOX = 0; private fieldOZ = 0; private fieldT = 0;
  private fieldValid = false;
  private hordeT = 0; private respawnT = 0; private nextHordeId = 1;
  kills = 0;
  private tmpM = new THREE.Matrix4(); private tmpQ = new THREE.Quaternion(); private tmpV = new THREE.Vector3(); private one = new THREE.Vector3(1, 1, 1);
  noiseEvents: { pos: THREE.Vector3; r: number }[] = [];
  /** non-zombie humans drawn with the same GPU body (player avatar in third person, survivors) */
  humans: HumanPose[] = [];

  constructor(scene: THREE.Scene, private world: World, seed: number) {
    this.rng = new RNG(seed + 31);
    const geo = buildZombieGeometry();
    this.aAnim = new THREE.InstancedBufferAttribute(new Float32Array(MAX * 4), 4).setUsage(THREE.DynamicDrawUsage) as THREE.InstancedBufferAttribute;
    this.aAnim2 = new THREE.InstancedBufferAttribute(new Float32Array(MAX * 4), 4).setUsage(THREE.DynamicDrawUsage) as THREE.InstancedBufferAttribute;
    this.aVar = new THREE.InstancedBufferAttribute(new Float32Array(MAX * 4), 4).setUsage(THREE.DynamicDrawUsage) as THREE.InstancedBufferAttribute;
    geo.setAttribute('aAnim', this.aAnim); geo.setAttribute('aAnim2', this.aAnim2); geo.setAttribute('aVar', this.aVar);
    const { mat, depth } = makeZombieMaterials(this.time);
    this.mesh = new THREE.InstancedMesh(geo, mat, MAX);
    this.mesh.customDepthMaterial = depth;
    this.mesh.castShadow = true; this.mesh.receiveShadow = true; this.mesh.frustumCulled = false;
    this.mesh.count = 0;
    scene.add(this.mesh);
  }

  // ------------------------------------------------------------------ spawning
  populate() {
    const rng = this.rng;
    // town population
    for (const b of this.world.town.buildings) {
      const inTown = b.x > TOWN.x0 && b.x < TOWN.x1 && b.z > TOWN.z0 && b.z < TOWN.z1;
      if (b.kind === 'cabin' && Math.hypot(b.x + 176, b.z + 206) < 10) continue; // keep the spawn cabin clear
      for (const s of b.zombieSpawns) if (rng.chance(inTown ? 0.55 : 0.3)) this.spawn(s.x + rng.range(-1, 1), s.z + rng.range(-1, 1), rng.chance(0.08) ? 1 : 0);
    }
    for (let i = 0; i < 170; i++) {
      const x = rng.range(TOWN.x0, TOWN.x1), z = rng.range(TOWN.z0, TOWN.z1);
      if (this.world.town.isOccupied(x, z)) continue;
      this.spawn(x, z, rng.chance(0.1) ? 1 : rng.chance(0.03) ? 2 : 0);
    }
    // wilderness stragglers
    for (let i = 0; i < 110; i++) {
      const x = rng.range(-HALF_WORLD + 40, HALF_WORLD - 40), z = rng.range(-HALF_WORLD + 40, HALF_WORLD - 40);
      if (Math.hypot(x + 170, z + 200) < 60) continue;
      this.spawn(x, z, rng.chance(0.15) ? 1 : 0);
    }
  }

  spawn(x: number, z: number, type: ZType, horde = 0): Zombie | null {
    if (this.list.length >= MAX - 5) return null;
    const rng = this.rng;
    const y = this.world.groundY(x, z);
    const maxHp = type === 2 ? 420 : type === 1 ? 85 : rng.range(90, 130);
    const zb: Zombie = {
      id: this.nextId++, type, pos: new THREE.Vector3(x, y, z), vel: new THREE.Vector3(), yaw: rng.range(0, 6.28), hp: maxHp, maxHp,
      state: 'idle', target: new THREE.Vector3(x, y, z), stateT: rng.range(0, 5), phase: rng.range(0, 6.28), move: 0, attackT: 0, atkBlend: 0,
      hit: 0, arms: 0, death: 0, deathDir: 1, seed: rng.next() * 100, scale: type === 2 ? 1.28 : rng.range(0.9, 1.06), lean: rng.next(), limp: rng.chance(0.3) ? rng.range(0.4, 1) : 0,
      speed: type === 2 ? 1.0 : rng.range(0.7, 1.3), runSpeed: type === 1 ? rng.range(4.6, 5.6) : type === 2 ? 2.6 : rng.range(1.7, 2.6),
      senseT: rng.range(0, 0.5), alerted: 0, lastSeen: new THREE.Vector3(), horde, groanT: rng.range(2, 12), bashRef: null, corpse: null, corpseAge: 0,
      frozen: false, stuckT: 0, lastPos: new THREE.Vector3(x, y, z), knock: new THREE.Vector3(),
    };
    this.list.push(zb);
    return zb;
  }

  spawnHorde(cx: number, cz: number, n: number, target: THREE.Vector3, toBase = false) {
    const h: Horde = { id: this.nextHordeId++, target: target.clone(), members: 0, t: 0, toBase };
    for (let i = 0; i < n; i++) {
      const a = this.rng.range(0, 6.28), r = Math.sqrt(this.rng.next()) * (6 + n * 0.12);
      const x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
      if (!this.world.inBounds(x, z)) continue;
      const zb = this.spawn(x, z, this.rng.chance(0.12) ? 1 : this.rng.chance(0.04) ? 2 : 0, h.id);
      if (zb) { zb.state = 'investigate'; zb.target.copy(target).add(new THREE.Vector3(this.rng.range(-8, 8), 0, this.rng.range(-8, 8))); h.members++; }
    }
    this.hordes.push(h);
    return h;
  }

  /** emit a sound that zombies may hear */
  noise(pos: THREE.Vector3, radius: number) { if (radius > 0.5) this.noiseEvents.push({ pos: pos.clone(), r: radius }); }

  // ------------------------------------------------------------------ flow field
  private buildField(px: number, pz: number) {
    const col = this.world.col;
    const ox = Math.floor(px + HALF_WORLD) - FIELD / 2, oz = Math.floor(pz + HALF_WORLD) - FIELD / 2;
    this.fieldOX = ox; this.fieldOZ = oz;
    const dist = this.dist; dist.fill(65535);
    // Dial's algorithm with small integer costs
    const buckets: number[][] = []; const maxCost = 64;
    for (let i = 0; i <= maxCost; i++) buckets.push([]);
    const sx = Math.floor(px + HALF_WORLD) - ox, sz = Math.floor(pz + HALF_WORLD) - oz;
    if (sx < 0 || sz < 0 || sx >= FIELD || sz >= FIELD) return;
    dist[sz * FIELD + sx] = 0; buckets[0].push(sz * FIELD + sx);
    let cur = 0, processed = 0, empty = 0;
    const dx = [1, -1, 0, 0, 1, 1, -1, -1], dz = [0, 0, 1, -1, 1, -1, 1, -1], dc = [10, 10, 10, 10, 14, 14, 14, 14];
    while (empty <= maxCost && processed < FIELD * FIELD * 2) {
      const bk = buckets[cur % (maxCost + 1)];
      if (!bk.length) { empty++; cur++; continue; }
      empty = 0;
      const i = bk.pop()!; processed++;
      const d = dist[i]; if (d !== cur) continue;
      const cx = i % FIELD, cz = (i / FIELD) | 0;
      for (let k = 0; k < 8; k++) {
        const nx = cx + dx[k], nz = cz + dz[k];
        if (nx < 0 || nz < 0 || nx >= FIELD || nz >= FIELD) continue;
        const gx = ox + nx, gz = oz + nz; if (gx < 0 || gz < 0 || gx >= NAV_N || gz >= NAV_N) continue;
        const nv = col.nav[gz * NAV_N + gx];
        if (nv === NAV_SOLID) continue;
        if (k >= 4) { // no corner cutting
          if (col.nav[(oz + cz) * NAV_N + gx] === NAV_SOLID || col.nav[gz * NAV_N + ox + cx] === NAV_SOLID) continue;
        }
        const step = Math.round((dc[k] + (nv === NAV_FREE ? 0 : nv * 3)) / 10);
        const nd = d + Math.max(1, step);
        const ni = nz * FIELD + nx;
        if (nd < dist[ni]) { dist[ni] = nd; buckets[nd % (maxCost + 1)].push(ni); }
      }
    }
    this.fieldValid = true;
  }
  /** desired direction from flow field, or null */
  private flowDir(x: number, z: number, out: THREE.Vector3): { ok: boolean; blockRef: any } {
    if (!this.fieldValid) return { ok: false, blockRef: null };
    const gx = Math.floor(x + HALF_WORLD), gz = Math.floor(z + HALF_WORLD);
    const cx = gx - this.fieldOX, cz = gz - this.fieldOZ;
    if (cx < 1 || cz < 1 || cx >= FIELD - 1 || cz >= FIELD - 1) return { ok: false, blockRef: null };
    const here = this.dist[cz * FIELD + cx]; if (here === 65535) return { ok: false, blockRef: null };
    let best = here, bx = 0, bz = 0;
    for (let k = -1; k <= 1; k++) for (let j = -1; j <= 1; j++) {
      if (!k && !j) continue;
      const d = this.dist[(cz + j) * FIELD + cx + k];
      if (d < best) { best = d; bx = k; bz = j; }
    }
    if (bx === 0 && bz === 0) return { ok: false, blockRef: null };
    out.set(bx, 0, bz).normalize();
    // is the next cell a destructible?
    const ng = (gz + bz) * NAV_N + gx + bx;
    const v = this.world.col.nav[ng];
    const ref = v !== NAV_FREE && v !== NAV_SOLID ? this.world.col.navRef.get(ng) : null;
    return { ok: true, blockRef: ref };
  }

  // ------------------------------------------------------------------ queries for combat
  /** hitscan against zombie capsules */
  raycast(o: THREE.Vector3, d: THREE.Vector3, maxT: number): { z: Zombie; t: number; head: boolean } | null {
    let best: { z: Zombie; t: number; head: boolean } | null = null;
    for (const z of this.list) {
      if (z.state === 'dead' || z.frozen) continue;
      const s = z.scale;
      // head sphere
      const hx = z.pos.x + Math.sin(z.yaw) * 0.1 * s, hy = z.pos.y + 1.62 * s, hz = z.pos.z + Math.cos(z.yaw) * 0.1 * s;
      const th = raySphere(o, d, hx, hy, hz, 0.15 * s);
      if (th > 0 && th < maxT && (!best || th < best.t)) best = { z, t: th, head: true };
      // body: two spheres + legs approximation (capsule via spheres)
      for (const [yy, r] of [[1.25, 0.26], [0.95, 0.22], [0.55, 0.18], [0.25, 0.16]] as [number, number][]) {
        const t = raySphere(o, d, z.pos.x + Math.sin(z.yaw) * 0.08 * s, z.pos.y + yy * s, z.pos.z + Math.cos(z.yaw) * 0.08 * s, r * s);
        if (t > 0 && t < maxT && (!best || t < best.t - 0.05)) best = { z, t, head: false };
      }
    }
    return best;
  }
  /** zombies in a melee arc */
  inArc(o: THREE.Vector3, dir: THREE.Vector3, range: number, cos: number): Zombie[] {
    const out: Zombie[] = [];
    for (const z of this.list) {
      if (z.state === 'dead') continue;
      this.tmpV.set(z.pos.x - o.x, 0, z.pos.z - o.z);
      const dist = this.tmpV.length(); if (dist > range + 0.35 * z.scale) continue;
      if (Math.abs(z.pos.y + 1 - o.y) > 1.6) continue;
      this.tmpV.normalize();
      const fd = this.tmpV.x * dir.x + this.tmpV.z * dir.z;
      if (fd < cos && dist > 0.6) continue;
      out.push(z);
    }
    out.sort((a, b) => a.pos.distanceToSquared(o) - b.pos.distanceToSquared(o));
    return out;
  }
  nearest(p: THREE.Vector3, r: number) { let best: Zombie | null = null, bd = r * r; for (const z of this.list) { if (z.state === 'dead') continue; const d = z.pos.distanceToSquared(p); if (d < bd) { bd = d; best = z; } } return best; }
  corpseNear(p: THREE.Vector3, r: number) { let best: Zombie | null = null, bd = r * r; for (const z of this.list) { if (z.state !== 'dead' || !z.corpse) continue; const d = z.pos.distanceToSquared(p); if (d < bd) { bd = d; best = z; } } return best; }

  damage(z: Zombie, dmg: number, dir: THREE.Vector3, head: boolean, knock: number, byPlayer = true) {
    if (z.state === 'dead') return false;
    z.hp -= dmg * (head ? 3 : 1);
    z.hit = 1;
    z.knock.set(dir.x, 0, dir.z).normalize().multiplyScalar(knock * (z.type === 2 ? 0.35 : 1));
    z.alerted = 8;
    if (z.state !== 'chase' && z.state !== 'attack') { z.state = 'chase'; z.stateT = 0; }
    if (knock > 1.2 && z.type !== 2) { z.state = 'stagger'; z.stateT = 0.5 + knock * 0.15; }
    if (z.hp <= 0) { this.kill(z, dir, byPlayer); return true; }
    return false;
  }
  kill(z: Zombie, dir: THREE.Vector3, byPlayer: boolean) {
    z.state = 'dead'; z.stateT = 0; z.hp = 0;
    const fwd = Math.sin(z.yaw) * dir.x + Math.cos(z.yaw) * dir.z;
    z.deathDir = fwd > 0 ? 1 : -1; // pushed backwards = fall back
    z.deathDir = -z.deathDir;
    z.corpse = new Inventory(20);
    rollLoot('corpse', this.rng, this.host?.luck ?? 0, z.corpse);
    audio.play('zombie_death', { pos: z.pos });
    this.kills += byPlayer ? 1 : 0;
    this.host?.onKill(z, byPlayer);
    // cap corpses
    const corpses = this.list.filter(q => q.state === 'dead');
    if (corpses.length > 70) { corpses.sort((a, b) => b.corpseAge - a.corpseAge); this.remove(corpses[0]); }
  }
  remove(z: Zombie) { const i = this.list.indexOf(z); if (i >= 0) this.list.splice(i, 1); }

  host: ZombieHost | null = null;

  // ------------------------------------------------------------------ update
  update(dt: number, dtGameMin: number, host: ZombieHost, camPos: THREE.Vector3) {
    this.host = host;
    this.time.value += dt;
    const P = host.playerPos;
    // flow field refresh
    this.fieldT -= dt;
    if (this.fieldT <= 0) { this.fieldT = 0.35; this.buildField(P.x, P.z); }
    // spatial hash for separation
    this.grid.clear();
    for (const z of this.list) { if (z.state === 'dead' || z.frozen) continue; const k = (Math.floor(z.pos.x / 2) + 1000) * 4096 + Math.floor(z.pos.z / 2) + 1000; let a = this.grid.get(k); if (!a) { a = []; this.grid.set(k, a); } a.push(z); }

    const night = 1 - host.daylight;
    const sightBase = host.playerInside ? 12 : 26;
    let chasing = 0, nearCount = 0, seenCount = 0;

    // noise events → investigate
    for (const ev of this.noiseEvents) {
      for (const z of this.list) {
        if (z.state === 'dead' || z.state === 'chase' || z.state === 'attack') continue;
        const d = z.pos.distanceTo(ev.pos);
        if (d < ev.r) { z.state = 'investigate'; z.target.copy(ev.pos).add(new THREE.Vector3(this.rng.range(-3, 3), 0, this.rng.range(-3, 3))); z.stateT = 0; z.alerted = Math.max(z.alerted, 2); }
      }
    }
    this.noiseEvents.length = 0;

    for (const z of this.list) {
      const dP = Math.hypot(z.pos.x - P.x, z.pos.z - P.z);
      z.frozen = dP > 230;
      if (z.state === 'dead') {
        z.death = Math.min(1, z.death + dt * 2.2); z.corpseAge += dtGameMin;
        z.hit = Math.max(0, z.hit - dt * 3);
        if (z.corpseAge > 60 * 24 * 2) this.remove(z);
        continue;
      }
      if (z.frozen) continue;
      if (dP < 25) nearCount++;
      z.stateT += dt;
      z.hit = Math.max(0, z.hit - dt * 3);
      z.alerted = Math.max(0, z.alerted - dt);
      // ---- senses
      z.senseT -= dt;
      if (z.senseT <= 0 && host.playerAlive) {
        z.senseT = 0.25 + this.rng.next() * 0.35;
        const sight = sightBase * (0.35 + 0.65 * host.daylight) * (0.5 + host.playerVisibility) * (z.state === 'chase' ? 1.6 : 1);
        if (dP < sight) {
          const toP = Math.atan2(P.x - z.pos.x, P.z - z.pos.z);
          const fov = Math.abs(angleDiff(z.yaw, toP)) < (z.state === 'chase' ? 3.2 : 1.1) || dP < 3;
          if (fov && !this.world.col.blocked(z.pos.x, z.pos.y + 1.6, z.pos.z, host.playerEye.x, host.playerEye.y, host.playerEye.z)) {
            if (z.state !== 'chase' && z.state !== 'attack') { z.state = 'chase'; z.stateT = 0; if (this.rng.chance(0.6)) audio.play('zombie_alert', { pos: z.pos }); this.alertNeighbors(z); }
            z.lastSeen.copy(P); z.alerted = 6;
          }
        }
        // hearing the player's own movement noise
        if (dP < host.playerNoise && z.state !== 'chase' && z.state !== 'attack') { z.state = 'investigate'; z.target.copy(P); z.stateT = 0; }
      }
      if (z.state === 'chase' && z.alerted > 0 && dP < 40) seenCount++;

      // ---- state machine
      let want = 0; // desired speed
      const dir = this.tmpV.set(0, 0, 0);
      let atkArms = 0;
      switch (z.state) {
        case 'idle':
          if (z.stateT > 4 + (z.seed % 6)) {
            if (z.horde) { const h = this.hordes.find(q => q.id === z.horde); if (h) { z.state = 'investigate'; z.target.copy(h.target).add(new THREE.Vector3(this.rng.range(-10, 10), 0, this.rng.range(-10, 10))); break; } }
            z.state = 'wander'; z.stateT = 0;
            const a = this.rng.range(0, 6.28), r = this.rng.range(3, 14);
            z.target.set(z.pos.x + Math.cos(a) * r, 0, z.pos.z + Math.sin(a) * r);
          }
          break;
        case 'wander': case 'investigate': {
          dir.set(z.target.x - z.pos.x, 0, z.target.z - z.pos.z);
          const d = dir.length();
          if (d < 1.2 || z.stateT > 40) { z.state = 'idle'; z.stateT = 0; break; }
          dir.divideScalar(d);
          want = z.speed * (z.state === 'investigate' ? 1.25 : 0.7) * (1 + night * 0.25);
          break;
        }
        case 'chase': {
          chasing++;
          atkArms = 1;
          if (!host.playerAlive) { z.state = 'wander'; break; }
          const tgt = z.alerted > 0 ? P : z.lastSeen;
          const d = Math.hypot(tgt.x - z.pos.x, tgt.z - z.pos.z);
          if (z.alerted <= 0 && d < 1.5) { z.state = 'idle'; z.stateT = 0; break; }
          const fd = this.flowDir(z.pos.x, z.pos.z, dir);
          if (d < 2.2 || !fd.ok || (z.alerted > 0 && dP < 3)) dir.set(tgt.x - z.pos.x, 0, tgt.z - z.pos.z).normalize();
          else if (fd.blockRef && !(fd.blockRef.broken === true && fd.blockRef.barricade <= 0)) { z.state = 'bash'; z.bashRef = fd.blockRef; z.stateT = 0; z.attackT = 0.6; }
          want = dP > 30 ? z.speed * 1.4 : z.runSpeed * (1 + night * 0.15);
          if (dP < 1.25 + 0.3 * z.scale && Math.abs(z.pos.y - P.y) < 1.4) { z.state = 'attack'; z.stateT = 0; z.attackT = 0.35 + this.rng.next() * 0.3; }
          break;
        }
        case 'attack': {
          atkArms = 1;
          z.yaw += angleDiff(z.yaw, Math.atan2(P.x - z.pos.x, P.z - z.pos.z)) * Math.min(1, dt * 8);
          z.attackT -= dt;
          z.atkBlend = Math.min(1, z.atkBlend + dt * 5);
          if (z.attackT <= 0) {
            z.attackT = z.type === 2 ? 1.6 : 1.15 + this.rng.next() * 0.4;
            if (dP < 1.6 * z.scale && host.playerAlive) { host.onPlayerAttacked(z); audio.play('zombie_attack', { pos: z.pos }); }
          }
          if (dP > 1.9 * z.scale) { z.state = 'chase'; z.stateT = 0; }
          break;
        }
        case 'bash': {
          atkArms = 1;
          z.atkBlend = Math.min(1, z.atkBlend + dt * 4);
          const ref = z.bashRef;
          if (!ref || ref.destroyed || (ref.open === true) || (ref.broken === true && (ref.barricade ?? 0) <= 0 && ref.hp === undefined) || z.stateT > 20) { z.state = 'chase'; z.bashRef = null; z.stateT = 0; break; }
          // face it
          const rx = ref.cx ?? ref.x ?? z.pos.x, rz = ref.cz ?? ref.z ?? z.pos.z;
          z.yaw += angleDiff(z.yaw, Math.atan2(rx - z.pos.x, rz - z.pos.z)) * Math.min(1, dt * 6);
          z.attackT -= dt;
          if (z.attackT <= 0) {
            z.attackT = z.type === 2 ? 1.1 : 1.4 + this.rng.next() * 0.5;
            host.onStructureHit(ref, z.type === 2 ? 45 : 9, z);
            audio.play('zombie_bang', { pos: z.pos, volume: 0.8 });
          }
          if (dP < 1.5) { z.state = 'attack'; z.stateT = 0; }
          break;
        }
        case 'stagger':
          if (z.stateT > 0.7) { z.state = 'chase'; z.stateT = 0; }
          break;
      }
      if (z.state !== 'attack' && z.state !== 'bash') z.atkBlend = Math.max(0, z.atkBlend - dt * 3);
      z.arms += (atkArms - z.arms) * Math.min(1, dt * 3);

      // ---- movement
      if (want > 0 && (dir.x || dir.z)) {
        const tyaw = Math.atan2(dir.x, dir.z);
        z.yaw += angleDiff(z.yaw, tyaw) * Math.min(1, dt * (z.state === 'chase' ? 6 : 2.5));
        const fwd = Math.max(0, Math.cos(angleDiff(z.yaw, tyaw)));
        const sp = want * (0.3 + 0.7 * fwd) * (z.limp ? 1 - z.limp * 0.3 : 1);
        z.vel.x += (Math.sin(z.yaw) * sp - z.vel.x) * Math.min(1, dt * 4);
        z.vel.z += (Math.cos(z.yaw) * sp - z.vel.z) * Math.min(1, dt * 4);
      } else { z.vel.x *= Math.exp(-dt * 6); z.vel.z *= Math.exp(-dt * 6); }
      // separation
      const gx = Math.floor(z.pos.x / 2), gz = Math.floor(z.pos.z / 2);
      for (let i = -1; i <= 1; i++) for (let j = -1; j <= 1; j++) {
        const a = this.grid.get((gx + i + 1000) * 4096 + gz + j + 1000); if (!a) continue;
        for (const o of a) {
          if (o === z) continue;
          const ddx = z.pos.x - o.pos.x, ddz = z.pos.z - o.pos.z; const d2 = ddx * ddx + ddz * ddz;
          const rr = 0.32 * (z.scale + o.scale);
          if (d2 < rr * rr && d2 > 1e-6) { const d = Math.sqrt(d2); const push = (rr - d) * 0.5; z.pos.x += ddx / d * push; z.pos.z += ddz / d * push; }
        }
      }
      // player body block
      const pdx = z.pos.x - P.x, pdz = z.pos.z - P.z, pd = Math.hypot(pdx, pdz);
      if (pd < 0.6 && pd > 1e-4) { z.pos.x = P.x + pdx / pd * 0.6; z.pos.z = P.z + pdz / pd * 0.6; }

      const kn = z.knock;
      z.pos.x += (z.vel.x + kn.x) * dt; z.pos.z += (z.vel.z + kn.z) * dt;
      kn.multiplyScalar(Math.exp(-dt * 6));
      const res = this.world.col.resolveCylinder(z.pos, 0.3 * z.scale, 1.7, 0.45, 'window_broken');
      void res;
      z.pos.y += (this.world.groundY(z.pos.x, z.pos.z, z.pos.y) - z.pos.y) * Math.min(1, dt * 12);
      const speedNow = Math.hypot(z.vel.x, z.vel.z);
      z.move = speedNow < 0.15 ? speedNow / 0.15 * 0.2 : speedNow < 2.2 ? 1 : 1 + clamp((speedNow - 2.2) / 2.5, 0, 1);
      z.phase += dt * (speedNow * (speedNow > 2.5 ? 2.6 : 3.4) + 0.1);
      // stuck detection: wander off
      if (z.state === 'wander' || z.state === 'investigate') {
        if (z.pos.distanceToSquared(z.lastPos) < 0.0004 && want > 0) { z.stuckT += dt; if (z.stuckT > 2) { z.state = 'idle'; z.stuckT = 0; } } else z.stuckT = 0;
      }
      z.lastPos.copy(z.pos);
      // groans
      z.groanT -= dt;
      if (z.groanT <= 0) { z.groanT = this.rng.range(4, 14) * (z.state === 'chase' ? 0.5 : 1); if (dP < 35) audio.play('zombie_groan', { pos: this.tmpV.copy(z.pos).setY(z.pos.y + 1.6), volume: z.type === 2 ? 1.2 : 0.8, pitch: z.type === 2 ? 0.7 : z.type === 1 ? 1.15 : 1 }); }
    }

    this.director(dt, dtGameMin, host);
    this.render(camPos);
    return { chasing, nearCount, seenCount };
  }

  private alertNeighbors(z: Zombie) {
    for (const o of this.list) {
      if (o === z || o.state === 'dead' || o.state === 'chase') continue;
      if (o.pos.distanceToSquared(z.pos) < 18 * 18) { o.state = 'investigate'; o.target.copy(this.host!.playerPos); o.stateT = 0; }
    }
  }

  // hordes & respawn
  private director(dt: number, dtGameMin: number, host: ZombieHost) {
    this.hordeT -= dtGameMin;
    const alive = this.list.filter(z => z.state !== 'dead').length;
    const night = host.hours >= 20 || host.hours < 5;
    if (this.hordeT <= 0) {
      this.hordeT = this.rng.range(60 * 5, 60 * 10) / (1 + host.baseHeat * 0.02);
      if ((night || host.baseHeat > 30) && alive < 520) {
        // horde from the forest heading to the player's base (or a random town point)
        const toBase = !!host.baseCenter && host.baseHeat > 10 && this.rng.chance(0.4 + Math.min(0.5, host.baseHeat / 200));
        const target = toBase ? host.baseCenter!.clone() : new THREE.Vector3(this.rng.range(-120, 120), 0, this.rng.range(-110, 110));
        const a = this.rng.range(0, 6.28); const r = 170;
        const cx = THREE.MathUtils.clamp(target.x + Math.cos(a) * r, -HALF_WORLD + 40, HALF_WORLD - 40), cz = THREE.MathUtils.clamp(target.z + Math.sin(a) * r, -HALF_WORLD + 40, HALF_WORLD - 40);
        const n = Math.floor(clamp(18 + host.day * 4 + host.baseHeat * 0.6, 18, 110));
        this.spawnHorde(cx, cz, n, target, toBase);
        if (this.onHorde) this.onHorde(n, toBase);
      }
    }
    // respawn stragglers out of sight
    this.respawnT -= dtGameMin;
    if (this.respawnT <= 0) {
      this.respawnT = 30;
      if (alive < 300) {
        for (let i = 0; i < 6; i++) {
          const x = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40), z = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40);
          const d = Math.hypot(x - host.playerPos.x, z - host.playerPos.z);
          if (d < 120) continue;
          if (host.baseCenter && Math.hypot(x - host.baseCenter.x, z - host.baseCenter.z) < 80) continue;
          if (this.world.town.isOccupied(x, z)) continue;
          this.spawn(x, z, this.rng.chance(0.15) ? 1 : 0);
        }
      }
    }
    for (let i = this.hordes.length - 1; i >= 0; i--) { this.hordes[i].t += dtGameMin; if (this.hordes[i].t > 60 * 12) this.hordes.splice(i, 1); }
  }
  onHorde: ((n: number, toBase: boolean) => void) | null = null;

  private render(cam: THREE.Vector3) {
    let n = 0;
    const A = this.aAnim.array as Float32Array, B = this.aAnim2.array as Float32Array, V = this.aVar.array as Float32Array;
    for (const z of this.list) {
      if (n >= MAX) break;
      if (z.frozen) continue;
      if (Math.abs(z.pos.x - cam.x) > 170 || Math.abs(z.pos.z - cam.z) > 170) continue;
      this.tmpQ.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, z.yaw);
      this.tmpM.compose(z.pos, this.tmpQ, this.one);
      this.mesh.setMatrixAt(n, this.tmpM);
      A[n * 4] = z.phase; A[n * 4 + 1] = z.state === 'dead' ? 0 : z.move; A[n * 4 + 2] = z.atkBlend; A[n * 4 + 3] = z.death;
      B[n * 4] = z.hit; B[n * 4 + 1] = z.arms; B[n * 4 + 2] = z.limp; B[n * 4 + 3] = z.deathDir;
      V[n * 4] = z.seed; V[n * 4 + 1] = z.scale; V[n * 4 + 2] = z.lean; V[n * 4 + 3] = z.type;
      n++;
    }
    for (const h of this.humans) {
      if (n >= MAX || !h.visible) continue;
      this.tmpQ.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, h.yaw);
      this.tmpM.compose(h.pos, this.tmpQ, this.one);
      this.mesh.setMatrixAt(n, this.tmpM);
      A[n * 4] = h.phase; A[n * 4 + 1] = h.move; A[n * 4 + 2] = h.atk; A[n * 4 + 3] = 0;
      B[n * 4] = h.hit; B[n * 4 + 1] = h.arms; B[n * 4 + 2] = 0; B[n * 4 + 3] = 1;
      V[n * 4] = h.seed; V[n * 4 + 1] = 1; V[n * 4 + 2] = 0.2; V[n * 4 + 3] = h.type;
      n++;
    }
    this.mesh.count = n;
    this.mesh.instanceMatrix.needsUpdate = true;
    this.aAnim.needsUpdate = true; this.aAnim2.needsUpdate = true; this.aVar.needsUpdate = true;
    this.aAnim.addUpdateRange(0, n * 4); this.aAnim2.addUpdateRange(0, n * 4); this.aVar.addUpdateRange(0, n * 4);
  }

  toJSON() {
    return this.list.filter(z => z.state !== 'dead').map(z => [Math.round(z.pos.x * 10) / 10, Math.round(z.pos.z * 10) / 10, z.type, Math.round(z.hp)]);
  }
  load(arr: [number, number, ZType, number][]) {
    this.list = [];
    for (const a of arr) { const z = this.spawn(a[0], a[1], a[2]); if (z) z.hp = a[3]; }
  }
}

function raySphere(o: THREE.Vector3, d: THREE.Vector3, cx: number, cy: number, cz: number, r: number) {
  const ox = o.x - cx, oy = o.y - cy, oz = o.z - cz;
  const b = ox * d.x + oy * d.y + oz * d.z; const c = ox * ox + oy * oy + oz * oz - r * r;
  const disc = b * b - c; if (disc < 0) return -1;
  return -b - Math.sqrt(disc);
}
