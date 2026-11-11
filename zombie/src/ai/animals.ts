// Wildlife: deer and rabbits that graze and flee, wolf packs that stalk and attack. Low-poly bodies with animated legs.
import * as THREE from 'three';
import { RNG, angleDiff, clamp, HALF_WORLD } from '../core/util';
import { World } from '../world/world';
import { getFlatMaterial } from '../render/materials';
import { TOWN } from '../world/layout';
import { audio } from '../audio/Audio';

export type AnimalType = 'deer' | 'rabbit' | 'wolf';
export interface Animal {
  id: number; type: AnimalType; pos: THREE.Vector3; yaw: number; speed: number; hp: number; maxHp: number;
  state: 'graze' | 'walk' | 'flee' | 'stalk' | 'charge' | 'attack' | 'dead'; t: number; target: THREE.Vector3; phase: number;
  obj: THREE.Group; legs: THREE.Object3D[]; pack: number; atkT: number; butchered: boolean; deadT: number; scale: number;
}

const SPEC: Record<AnimalType, { hp: number; walk: number; run: number; radius: number; meat: number; sense: number }> = {
  deer: { hp: 90, walk: 1.4, run: 9, radius: 0.45, meat: 4, sense: 32 },
  rabbit: { hp: 12, walk: 1, run: 7, radius: 0.18, meat: 1, sense: 14 },
  wolf: { hp: 110, walk: 1.6, run: 8.5, radius: 0.4, meat: 2, sense: 45 },
};

function body(type: AnimalType): { g: THREE.Group; legs: THREE.Object3D[] } {
  const g = new THREE.Group(); const legs: THREE.Object3D[] = [];
  const col = type === 'deer' ? 0x8a6040 : type === 'rabbit' ? 0x8a7a68 : 0x5c5a58;
  const dark = type === 'deer' ? 0x5a3e28 : type === 'rabbit' ? 0x6a5c4e : 0x3a3836;
  const M = (h: number) => getFlatMaterial(h, 0.95);
  const add = (geo: THREE.BufferGeometry, h: number, x: number, y: number, z: number, rx = 0, parent: THREE.Object3D = g) => { const m = new THREE.Mesh(geo, M(h)); m.position.set(x, y, z); m.rotation.x = rx; m.castShadow = true; parent.add(m); return m; };
  if (type === 'rabbit') {
    add(new THREE.IcosahedronGeometry(0.16, 0).scale(1, 0.85, 1.3), col, 0, 0.17, 0);
    add(new THREE.IcosahedronGeometry(0.09, 0), col, 0, 0.28, 0.17);
    add(new THREE.BoxGeometry(0.03, 0.14, 0.05), dark, -0.03, 0.4, 0.15); add(new THREE.BoxGeometry(0.03, 0.14, 0.05), dark, 0.03, 0.4, 0.15);
    add(new THREE.IcosahedronGeometry(0.04, 0), 0xe8e4dc, 0, 0.2, -0.2);
    for (const [x, z] of [[-0.07, 0.1], [0.07, 0.1], [-0.08, -0.1], [0.08, -0.1]]) { const p = new THREE.Group(); p.position.set(x, 0.12, z); g.add(p); add(new THREE.BoxGeometry(0.04, 0.12, 0.05), dark, 0, -0.06, 0, 0, p); legs.push(p); }
    return { g, legs };
  }
  const L = type === 'deer' ? 1.0 : 0.62, H = type === 'deer' ? 0.95 : 0.6, W = type === 'deer' ? 0.36 : 0.3;
  add(new THREE.BoxGeometry(W, H * 0.42, L * 1.15).translate(0, 0, 0), col, 0, H + 0.08, 0);
  add(new THREE.BoxGeometry(W * 0.9, H * 0.38, 0.3), col, 0, H + 0.12, L * 0.5);
  const neck = add(new THREE.BoxGeometry(W * 0.55, H * 0.5, 0.2), col, 0, H + H * 0.32, L * 0.62, -0.5);
  void neck;
  const head = add(new THREE.BoxGeometry(W * 0.6, 0.2, type === 'deer' ? 0.42 : 0.38), col, 0, H + H * 0.55, L * 0.8);
  add(new THREE.BoxGeometry(W * 0.4, 0.12, 0.14), dark, 0, H + H * 0.5, L * 0.8 + (type === 'deer' ? 0.25 : 0.22));
  for (const s of [-1, 1]) add(new THREE.ConeGeometry(0.05, type === 'deer' ? 0.16 : 0.12, 4), dark, s * W * 0.25, H + H * 0.55 + 0.14, L * 0.74);
  if (type === 'deer') for (const s of [-1, 1]) { const a = add(new THREE.CylinderGeometry(0.015, 0.025, 0.45, 4), 0xc8b89a, s * 0.12, H + H * 0.55 + 0.32, L * 0.76); a.rotation.z = s * 0.4; const b = add(new THREE.CylinderGeometry(0.012, 0.015, 0.22, 4), 0xc8b89a, s * 0.2, H + H * 0.55 + 0.42, L * 0.83); b.rotation.x = 0.8; }
  const tail = add(new THREE.BoxGeometry(0.08, 0.08, type === 'wolf' ? 0.42 : 0.12), type === 'deer' ? 0xe8e0d0 : dark, 0, H + 0.08, -L * 0.62, type === 'wolf' ? 0.5 : -0.4);
  void tail; void head;
  for (const [x, z] of [[-W * 0.35, L * 0.42], [W * 0.35, L * 0.42], [-W * 0.35, -L * 0.42], [W * 0.35, -L * 0.42]]) {
    const p = new THREE.Group(); p.position.set(x, H, z); g.add(p);
    add(new THREE.BoxGeometry(0.08, H * 0.55, 0.1), col, 0, -H * 0.27, 0, 0, p);
    add(new THREE.BoxGeometry(0.06, H * 0.5, 0.07), dark, 0, -H * 0.75, 0, 0, p);
    legs.push(p);
  }
  return { g, legs };
}

export class Animals {
  list: Animal[] = [];
  private rng: RNG; private nextId = 1; private respawnT = 0; private nextPack = 1;
  group = new THREE.Group();
  constructor(private world: World, scene: THREE.Scene, seed: number) { this.rng = new RNG(seed + 909); scene.add(this.group); }

  private okSpot(x: number, z: number) {
    if (!this.world.inBounds(x, z)) return false;
    if (x > TOWN.x0 - 30 && x < TOWN.x1 + 30 && z > TOWN.z0 - 30 && z < TOWN.z1 + 30) return false;
    if (this.world.waterDepth(x, z) > 0.1) return false;
    return this.world.terrain.slopeAt(x, z) < 0.4;
  }
  spawn(type: AnimalType, x: number, z: number, pack = 0) {
    const { g, legs } = body(type);
    const s = SPEC[type];
    const a: Animal = { id: this.nextId++, type, pos: new THREE.Vector3(x, this.world.groundY(x, z), z), yaw: this.rng.range(0, 6.28), speed: 0, hp: s.hp, maxHp: s.hp, state: 'graze', t: this.rng.range(0, 5), target: new THREE.Vector3(x, 0, z), phase: 0, obj: g, legs, pack, atkT: 0, butchered: false, deadT: 0, scale: this.rng.range(0.9, 1.1) };
    g.scale.setScalar(a.scale);
    this.group.add(g); this.list.push(a);
    return a;
  }
  populate() {
    for (let i = 0; i < 90 && this.list.length < 60; i++) {
      const x = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40), z = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40);
      if (!this.okSpot(x, z) || Math.hypot(x + 170, z + 200) < 50) continue;
      const r = this.rng.next();
      if (r < 0.42) { for (let k = 0; k < this.rng.int(1, 3); k++) this.spawn('deer', x + this.rng.range(-4, 4), z + this.rng.range(-4, 4)); }
      else if (r < 0.9) this.spawn('rabbit', x, z);
      else if (this.list.filter(a => a.type === 'wolf').length < 9) { const pk = this.nextPack++; for (let k = 0; k < 3; k++) this.spawn('wolf', x + this.rng.range(-3, 3), z + this.rng.range(-3, 3), pk); }
    }
  }

  damage(a: Animal, dmg: number, from: THREE.Vector3) {
    if (a.state === 'dead') return false;
    a.hp -= dmg;
    if (a.hp <= 0) { a.state = 'dead'; a.deadT = 0; a.speed = 0; audio.play(a.type === 'wolf' ? 'zombie_death' : 'hit_flesh', { pos: a.pos, pitch: a.type === 'wolf' ? 1.5 : 1.3 }); return true; }
    if (a.type === 'wolf') { a.state = 'charge'; a.t = 0; for (const o of this.list) if (o.pack === a.pack && o.state !== 'dead') o.state = 'charge'; }
    else { a.state = 'flee'; a.t = 0; a.target.copy(a.pos).add(a.pos.clone().sub(from).setY(0).normalize().multiplyScalar(60)); }
    return false;
  }
  raycast(o: THREE.Vector3, d: THREE.Vector3, maxT: number): { a: Animal; t: number } | null {
    let best: { a: Animal; t: number } | null = null;
    for (const a of this.list) {
      if (a.state === 'dead') continue;
      const s = SPEC[a.type]; const h = a.type === 'rabbit' ? 0.2 : a.type === 'deer' ? 1.15 : 0.75;
      const c = a.pos.clone().setY(a.pos.y + h * a.scale);
      const oc = o.clone().sub(c); const b = oc.dot(d); const cc = oc.lengthSq() - (s.radius * 1.4) ** 2; const disc = b * b - cc;
      if (disc < 0) continue; const t = -b - Math.sqrt(disc);
      if (t > 0 && t < maxT && (!best || t < best.t)) best = { a, t };
    }
    return best;
  }
  inArc(o: THREE.Vector3, dir: THREE.Vector3, range: number) {
    return this.list.filter(a => a.state !== 'dead' && Math.hypot(a.pos.x - o.x, a.pos.z - o.z) < range + SPEC[a.type].radius && ((a.pos.x - o.x) * dir.x + (a.pos.z - o.z) * dir.z) > 0);
  }
  carcassNear(p: THREE.Vector3, r: number) { return this.list.find(a => a.state === 'dead' && !a.butchered && a.pos.distanceTo(p) < r) ?? null; }
  meat(a: Animal) { return SPEC[a.type].meat; }
  remove(a: Animal) { this.group.remove(a.obj); this.list.splice(this.list.indexOf(a), 1); }

  update(dt: number, player: { pos: THREE.Vector3; noise: number; crouch: boolean; alive: boolean }, night: number, onBite: (a: Animal) => void) {
    const P = player.pos;
    for (let i = this.list.length - 1; i >= 0; i--) {
      const a = this.list[i]; const s = SPEC[a.type];
      const dP = Math.hypot(a.pos.x - P.x, a.pos.z - P.z);
      const far = dP > 200;
      a.obj.visible = dP < 160;
      if (a.state === 'dead') {
        a.deadT += dt;
        a.obj.rotation.z = Math.min(Math.PI / 2, a.obj.rotation.z + dt * 4);
        if (a.butchered || a.deadT > 600) { if (a.butchered && a.deadT > 30 || a.deadT > 600) this.remove(a); }
        continue;
      }
      if (far) continue;
      a.t += dt;
      let want = 0; let goal: THREE.Vector3 | null = null;
      const heard = player.alive && dP < Math.max(s.sense * (player.crouch ? 0.45 : 1) * (a.type === 'wolf' ? 1 + night : 1), player.noise * 1.5);
      switch (a.state) {
        case 'graze': if (a.t > 4 + this.rng.next() * 6) { a.state = 'walk'; a.t = 0; a.target.set(a.pos.x + this.rng.range(-15, 15), 0, a.pos.z + this.rng.range(-15, 15)); } if (heard) this.alarm(a, P); break;
        case 'walk': goal = a.target; want = s.walk; if (a.t > 12 || Math.hypot(a.target.x - a.pos.x, a.target.z - a.pos.z) < 1) { a.state = 'graze'; a.t = 0; } if (heard) this.alarm(a, P); break;
        case 'flee': goal = a.target; want = s.run; if (a.t > 8) { a.state = 'graze'; a.t = 0; } break;
        case 'stalk': goal = P; want = s.walk * 1.4; if (dP < 18 || a.t > 20) { a.state = 'charge'; a.t = 0; audio.play('zombie_alert', { pos: a.pos, pitch: 1.6, volume: 0.8 }); } if (!player.alive) a.state = 'walk'; break;
        case 'charge': goal = P; want = s.run; if (dP < 1.6) { a.state = 'attack'; a.atkT = 0.3; } if (a.t > 25 || !player.alive) { a.state = 'walk'; a.t = 0; } break;
        case 'attack':
          a.yaw += angleDiff(a.yaw, Math.atan2(P.x - a.pos.x, P.z - a.pos.z)) * Math.min(1, dt * 8);
          a.atkT -= dt; if (a.atkT <= 0) { a.atkT = 1.1; if (dP < 2) onBite(a); }
          if (dP > 2.4) a.state = 'charge';
          // wolves give up when badly hurt
          if (a.hp < a.maxHp * 0.3) { a.state = 'flee'; a.t = 0; a.target.copy(a.pos).add(a.pos.clone().sub(P).setY(0).normalize().multiplyScalar(80)); }
          break;
      }
      if (goal) {
        const ty = Math.atan2(goal.x - a.pos.x, goal.z - a.pos.z);
        a.yaw += angleDiff(a.yaw, ty) * Math.min(1, dt * 5);
      }
      a.speed += (want - a.speed) * Math.min(1, dt * 4);
      a.pos.x += Math.sin(a.yaw) * a.speed * dt; a.pos.z += Math.cos(a.yaw) * a.speed * dt;
      const r = this.world.col.resolveCylinder(a.pos, s.radius, 1, 0.5);
      if (r.hit && a.state === 'walk') { a.state = 'graze'; a.t = 0; }
      if (this.world.waterDepth(a.pos.x, a.pos.z) > 0.3 || !this.world.inBounds(a.pos.x, a.pos.z)) { a.yaw += Math.PI; a.pos.x += Math.sin(a.yaw) * 1; a.pos.z += Math.cos(a.yaw) * 1; }
      a.pos.y += (this.world.groundY(a.pos.x, a.pos.z, a.pos.y) - a.pos.y) * Math.min(1, dt * 10);
      // animate
      a.phase += dt * a.speed * (a.type === 'rabbit' ? 6 : 3.2);
      const amp = clamp(a.speed / 3, 0, 1) * (a.type === 'rabbit' ? 0.9 : 0.6);
      a.legs.forEach((l, k) => { l.rotation.x = Math.sin(a.phase + (k === 0 || k === 3 ? 0 : Math.PI)) * amp; });
      const hop = a.type === 'rabbit' ? Math.abs(Math.sin(a.phase)) * 0.12 * clamp(a.speed, 0, 1) : 0;
      a.obj.position.set(a.pos.x, a.pos.y + hop, a.pos.z); a.obj.rotation.y = a.yaw;
    }
    // respawn far from the player
    this.respawnT -= dt;
    if (this.respawnT <= 0) { this.respawnT = 20; if (this.list.filter(a => a.state !== 'dead').length < 45) { const x = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40), z = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40); if (this.okSpot(x, z) && Math.hypot(x - P.x, z - P.z) > 150) { if (this.rng.chance(0.15 + night * 0.2)) { const pk = this.nextPack++; for (let k = 0; k < 3; k++) this.spawn('wolf', x + k, z, pk); } else this.spawn(this.rng.chance(0.5) ? 'deer' : 'rabbit', x, z); } } }
  }
  private alarm(a: Animal, P: THREE.Vector3) {
    if (a.type === 'wolf') { a.state = 'stalk'; a.t = 0; return; }
    a.state = 'flee'; a.t = 0;
    a.target.copy(a.pos).add(a.pos.clone().sub(P).setY(0).normalize().multiplyScalar(70));
    for (const o of this.list) if (o !== a && o.type === a.type && o.state !== 'dead' && o.pos.distanceTo(a.pos) < 12) { o.state = 'flee'; o.t = 0; o.target.copy(a.target); }
  }
}
