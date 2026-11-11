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
  rig: Rig; graze: number; alert: number; look: number;
}

const SPEC: Record<AnimalType, { hp: number; walk: number; run: number; radius: number; meat: number; sense: number }> = {
  deer: { hp: 90, walk: 1.4, run: 9, radius: 0.45, meat: 4, sense: 32 },
  rabbit: { hp: 12, walk: 1, run: 7, radius: 0.18, meat: 1, sense: 14 },
  wolf: { hp: 110, walk: 1.6, run: 8.5, radius: 0.4, meat: 2, sense: 45 },
};

interface Rig { g: THREE.Group; legs: THREE.Object3D[]; lower: THREE.Object3D[]; neck: THREE.Object3D; head: THREE.Object3D; tail: THREE.Object3D; torso: THREE.Object3D }
function body(type: AnimalType): Rig {
  const g = new THREE.Group(); const legs: THREE.Object3D[] = []; const lower: THREE.Object3D[] = [];
  const col = type === 'deer' ? 0x8a6040 : type === 'rabbit' ? 0x8a7a68 : 0x5c5a58;
  const dark = type === 'deer' ? 0x5a3e28 : type === 'rabbit' ? 0x6a5c4e : 0x3a3836;
  const belly = type === 'deer' ? 0xd8c8a8 : type === 'rabbit' ? 0xe0d8cc : 0x8a8682;
  const M = (h: number) => getFlatMaterial(h, 0.95);
  const add = (geo: THREE.BufferGeometry, h: number, x: number, y: number, z: number, parent: THREE.Object3D, rx = 0) => { const m = new THREE.Mesh(geo, M(h)); m.position.set(x, y, z); m.rotation.x = rx; m.castShadow = true; parent.add(m); return m; };
  const torso = new THREE.Group(); g.add(torso);
  if (type === 'rabbit') {
    torso.position.set(0, 0.17, 0);
    add(new THREE.IcosahedronGeometry(0.16, 0).scale(1, 0.85, 1.3), col, 0, 0, 0, torso);
    add(new THREE.IcosahedronGeometry(0.1, 0).scale(1, 0.6, 1.2), belly, 0, -0.06, 0.02, torso);
    const neck = new THREE.Group(); neck.position.set(0, 0.08, 0.13); torso.add(neck);
    const head = new THREE.Group(); neck.add(head);
    add(new THREE.IcosahedronGeometry(0.09, 0), col, 0, 0.04, 0.05, head);
    for (const sx of [-0.03, 0.03]) { const e = add(new THREE.BoxGeometry(0.03, 0.15, 0.05), dark, sx, 0.17, 0.02, head); e.rotation.x = -0.25; }
    add(new THREE.IcosahedronGeometry(0.02, 0), 0x1a1210, 0, 0.03, 0.14, head);
    const tail = new THREE.Group(); tail.position.set(0, 0.02, -0.2); torso.add(tail);
    add(new THREE.IcosahedronGeometry(0.045, 0), 0xf0ece4, 0, 0, 0, tail);
    for (const [x, z, big] of [[-0.07, 0.1, 0], [0.07, 0.1, 0], [-0.08, -0.1, 1], [0.08, -0.1, 1]] as [number, number, number][]) {
      const p = new THREE.Group(); p.position.set(x, -0.05, z); torso.add(p);
      add(new THREE.BoxGeometry(0.045, big ? 0.1 : 0.12, big ? 0.13 : 0.05), dark, 0, -0.06, big ? -0.02 : 0, p);
      legs.push(p);
    }
    return { g, legs, lower, neck, head, tail, torso };
  }
  const L = type === 'deer' ? 1.0 : 0.62, H = type === 'deer' ? 0.95 : 0.6, W = type === 'deer' ? 0.36 : 0.3;
  torso.position.set(0, H + 0.08, 0);
  add(new THREE.BoxGeometry(W, H * 0.42, L * 1.15), col, 0, 0, 0, torso);
  add(new THREE.BoxGeometry(W * 0.8, H * 0.12, L * 0.9), belly, 0, -H * 0.2, 0, torso);
  add(new THREE.BoxGeometry(W * 0.92, H * 0.4, 0.32), col, 0, 0.04, L * 0.5, torso);
  if (type === 'wolf') add(new THREE.BoxGeometry(W * 1.05, H * 0.46, 0.34), dark, 0, 0.06, L * 0.36, torso); // ruff
  const neck = new THREE.Group(); neck.position.set(0, H * 0.12, L * 0.6); torso.add(neck);
  add(new THREE.BoxGeometry(W * 0.5, H * 0.55, 0.2), col, 0, H * 0.22, 0.06, neck, -0.45);
  const head = new THREE.Group(); head.position.set(0, H * 0.48, 0.2); neck.add(head);
  add(new THREE.BoxGeometry(W * 0.6, 0.2, type === 'deer' ? 0.38 : 0.34), col, 0, 0, 0.08, head);
  add(new THREE.BoxGeometry(W * 0.42, 0.13, 0.16), dark, 0, -0.03, type === 'deer' ? 0.33 : 0.3, head);
  add(new THREE.BoxGeometry(0.05, 0.04, 0.03), 0x111111, 0, 0.0, type === 'deer' ? 0.42 : 0.39, head);
  for (const sx of [-1, 1]) { const e = add(new THREE.ConeGeometry(0.05, type === 'deer' ? 0.17 : 0.13, 4), dark, sx * W * 0.24, 0.15, -0.02, head); e.rotation.z = -sx * (type === 'deer' ? 0.6 : 0.15); }
  if (type === 'deer') for (const sx of [-1, 1]) {
    const a = add(new THREE.CylinderGeometry(0.014, 0.024, 0.46, 4), 0xc8b89a, sx * 0.1, 0.32, 0.0, head); a.rotation.z = sx * 0.35;
    const b = add(new THREE.CylinderGeometry(0.01, 0.015, 0.2, 4), 0xc8b89a, sx * 0.18, 0.44, 0.08, head); b.rotation.x = 0.8;
    const c = add(new THREE.CylinderGeometry(0.01, 0.014, 0.18, 4), 0xc8b89a, sx * 0.16, 0.36, -0.06, head); c.rotation.x = -0.6;
  }
  const tail = new THREE.Group(); tail.position.set(0, 0.02, -L * 0.58); torso.add(tail);
  add(new THREE.BoxGeometry(type === 'wolf' ? 0.1 : 0.09, 0.08, type === 'wolf' ? 0.44 : 0.13), type === 'deer' ? 0xeee6d6 : dark, 0, 0, type === 'wolf' ? -0.2 : -0.05, tail);
  for (const [x, z] of [[-W * 0.35, L * 0.42], [W * 0.35, L * 0.42], [-W * 0.35, -L * 0.42], [W * 0.35, -L * 0.42]]) {
    const p = new THREE.Group(); p.position.set(x, -0.04, z); torso.add(p);
    add(new THREE.BoxGeometry(0.09, H * 0.5, 0.11), col, 0, -H * 0.24, 0, p);
    const lo = new THREE.Group(); lo.position.set(0, -H * 0.48, 0); p.add(lo);
    add(new THREE.BoxGeometry(0.06, H * 0.52, 0.07), dark, 0, -H * 0.25, 0, lo);
    add(new THREE.BoxGeometry(0.075, 0.06, 0.1), 0x222018, 0, -H * 0.52, 0.01, lo);
    legs.push(p); lower.push(lo);
  }
  return { g, legs, lower, neck, head, tail, torso };
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
    const rig = body(type); const g = rig.g, legs = rig.legs;
    const s = SPEC[type];
    const a: Animal = { id: this.nextId++, type, pos: new THREE.Vector3(x, this.world.groundY(x, z), z), yaw: this.rng.range(0, 6.28), speed: 0, hp: s.hp, maxHp: s.hp, state: 'graze', t: this.rng.range(0, 5), target: new THREE.Vector3(x, 0, z), phase: 0, obj: g, legs, pack, atkT: 0, butchered: false, deadT: 0, scale: this.rng.range(0.9, 1.1), rig, graze: 0, alert: 0, look: 0 };
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
      // animate: gait selection, spine flex, head/neck, tail
      this.animate(a, dt, P, heard);
    }
    // respawn far from the player
    this.respawnT -= dt;
    if (this.respawnT <= 0) { this.respawnT = 20; if (this.list.filter(a => a.state !== 'dead').length < 45) { const x = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40), z = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40); if (this.okSpot(x, z) && Math.hypot(x - P.x, z - P.z) > 150) { if (this.rng.chance(0.15 + night * 0.2)) { const pk = this.nextPack++; for (let k = 0; k < 3; k++) this.spawn('wolf', x + k, z, pk); } else this.spawn(this.rng.chance(0.5) ? 'deer' : 'rabbit', x, z); } } }
  }
  private animate(a: Animal, dt: number, P: THREE.Vector3, heard: boolean) {
    const R = a.rig; const sp = a.speed;
    const rabbit = a.type === 'rabbit';
    const gallop = !rabbit && sp > 5, trot = !rabbit && sp > 2.4 && !gallop;
    a.phase += dt * (rabbit ? sp * 5.5 + 0.2 : gallop ? sp * 1.25 : trot ? sp * 2.4 : sp * 3.4);
    const ph = a.phase;
    const amp = clamp(sp / 2, 0, 1) * (gallop ? 0.95 : trot ? 0.6 : 0.42);
    let bob = 0, pitch = 0;
    if (rabbit) {
      // bound: hind legs push together, fore legs reach together; body arcs
      const hop = Math.max(0, Math.sin(ph)); const on = clamp(sp, 0, 1);
      R.legs.forEach((l, k) => { l.rotation.x = (k < 2 ? -1 : 1) * Math.sin(ph) * 0.9 * on; });
      bob = hop * 0.14 * on; pitch = Math.cos(ph) * 0.35 * on;
    } else {
      R.legs.forEach((l, k) => {
        const front = k < 2, left = k % 2 === 0;
        // walk/trot: diagonal pairs; gallop: fore pair and hind pair, offset
        const off = gallop ? (front ? 0 : 2.2) + (left ? 0 : 0.35) : (front === left ? 0 : Math.PI);
        const sw = Math.sin(ph + off);
        l.rotation.x = sw * amp;
        R.lower[k].rotation.x = Math.max(0, -Math.cos(ph + off)) * amp * (front ? -1.1 : 1.2);
      });
      if (gallop) { bob = Math.abs(Math.sin(ph)) * 0.16; pitch = Math.sin(ph + 0.6) * 0.14; }
      else bob = Math.abs(Math.sin(ph * 2)) * 0.02 * amp;
    }
    R.torso.rotation.x = pitch;
    // grazing / alertness / look-at
    const grazing = a.state === 'graze' && !heard;
    a.graze += ((grazing ? (Math.sin(a.t * 0.6 + a.id) > -0.4 ? 1 : 0) : 0) - a.graze) * Math.min(1, dt * 2.5);
    a.alert += ((heard || a.state === 'stalk' ? 1 : 0) - a.alert) * Math.min(1, dt * 4);
    const toP = Math.atan2(P.x - a.pos.x, P.z - a.pos.z);
    const lookTarget = a.alert > 0.3 && a.state !== 'flee' ? clamp(Math.atan2(Math.sin(toP - a.yaw), Math.cos(toP - a.yaw)), -1, 1) : 0;
    a.look += (lookTarget - a.look) * Math.min(1, dt * 5);
    const chew = a.graze * Math.sin(a.t * 9) * 0.06;
    R.neck.rotation.x = (rabbit ? 0.5 : 1.15) * a.graze - a.alert * 0.25 + (gallop ? Math.sin(ph) * 0.12 : 0) + (a.state === 'charge' || a.state === 'attack' ? 0.35 : 0);
    R.neck.rotation.y = a.look * 0.7;
    R.head.rotation.x = chew + (a.state === 'attack' ? Math.sin(a.t * 14) * 0.25 : 0);
    if (rabbit) R.head.rotation.z = Math.sin(a.t * 25) * 0.03 * (1 - clamp(sp, 0, 1));
    // tails: deer flash white when fleeing, wolves wag when stalking and tuck when fleeing
    if (a.type === 'deer') R.tail.rotation.x = a.state === 'flee' ? -1.2 : Math.sin(a.t * 3) * 0.15;
    else if (a.type === 'wolf') { R.tail.rotation.x = a.state === 'flee' ? 0.9 : a.state === 'charge' ? -0.3 : 0.2; R.tail.rotation.y = a.state === 'stalk' ? Math.sin(a.t * 8) * 0.5 : Math.sin(a.t * 2) * 0.1; }
    else R.tail.rotation.x = Math.sin(a.t * 5) * 0.1;
    a.obj.position.set(a.pos.x, a.pos.y + bob, a.pos.z); a.obj.rotation.y = a.yaw;
  }
  private alarm(a: Animal, P: THREE.Vector3) {
    if (a.type === 'wolf') { a.state = 'stalk'; a.t = 0; return; }
    a.state = 'flee'; a.t = 0;
    a.target.copy(a.pos).add(a.pos.clone().sub(P).setY(0).normalize().multiplyScalar(70));
    for (const o of this.list) if (o !== a && o.type === a.type && o.state !== 'dead' && o.pos.distanceTo(a.pos) < 12) { o.state = 'flee'; o.t = 0; o.target.copy(a.target); }
  }
}
