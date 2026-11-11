// Drivable vehicles: arcade car physics on the heightfield, collisions, running down zombies, fuel, headlights.
import * as THREE from 'three';
import { clamp, damp, angleDiff } from '../core/util';
import { World } from '../world/world';
import { TownCar, Container } from '../world/town';
import { Box } from '../world/collision';
import { buildCar, CarVariant } from '../models/props';
import { Inventory } from '../items/inventory';
import { audio } from '../audio/Audio';
import type { Zombies } from '../ai/zombies';
import type { Particles } from '../render/particles';

export interface Vehicle {
  id: number; variant: CarVariant; color: number; obj: THREE.Object3D; pos: THREE.Vector3; yaw: number; speed: number; steer: number;
  fuel: number; condition: number; keys: boolean; hotwired: boolean; size: [number, number, number]; box: Box | null; container: Container;
  pitch: number; roll: number; townId: number; kind: 'vehicle'; noiseT: number; lights: boolean; cx: number; cz: number;
}

export interface DriveInput { throttle: number; steer: number; brake: boolean }

const MAX_SPEED: Record<CarVariant, number> = { sedan: 26, hatchback: 24, pickup: 24, van: 20, police: 30, wreck: 0 };

export class Vehicles {
  list: Vehicle[] = [];
  private nextId = 1;
  private tmp = new THREE.Vector3();
  headlight: THREE.SpotLight;

  constructor(private world: World, private scene: THREE.Scene) {
    this.headlight = new THREE.SpotLight(0xfff0d0, 0, 60, 0.55, 0.45, 1.6);
    this.headlight.castShadow = false;
    scene.add(this.headlight); scene.add(this.headlight.target);
  }

  /** turn a parked town car into a dynamic vehicle (removes the instanced prop and static collider) */
  fromTown(c: TownCar): Vehicle {
    c.taken = true;
    this.world.town.props.remove(c.entry);
    this.world.col.removeBox(c.box);
    return this.spawn(c.variant, c.color, c.pos.x, c.pos.z, c.yaw, { fuel: c.fuel, condition: c.condition, keys: c.keys, container: c.container, townId: c.id });
  }

  spawn(variant: CarVariant, color: number, x: number, z: number, yaw: number, o: { fuel?: number; condition?: number; keys?: boolean; hotwired?: boolean; container?: Container; townId?: number } = {}): Vehicle {
    const { object, size } = buildCar(variant, color);
    object.traverse(m => { if ((m as THREE.Mesh).isMesh) { m.castShadow = true; m.receiveShadow = true; } });
    this.scene.add(object);
    const container: Container = o.container ?? { id: 900000 + this.nextId, kind: 'car_trunk', label: 'Car Trunk', pos: new THREE.Vector3(), inv: new Inventory(40), generated: true, half: new THREE.Vector3(0.8, 0.5, 0.8), building: null };
    const v: Vehicle = {
      id: this.nextId++, variant, color, obj: object, pos: new THREE.Vector3(x, this.world.groundY(x, z), z), yaw, speed: 0, steer: 0,
      fuel: o.fuel ?? 0.3, condition: o.condition ?? 60, keys: o.keys ?? false, hotwired: o.hotwired ?? false, size, box: null, container,
      pitch: 0, roll: 0, townId: o.townId ?? -1, kind: 'vehicle', noiseT: 0, lights: false, cx: x, cz: z,
    };
    this.list.push(v);
    this.settle(v);
    this.park(v);
    return v;
  }

  canStart(v: Vehicle) { return (v.keys || v.hotwired) && v.fuel > 0 && v.condition > 0 && v.variant !== 'wreck'; }

  forward(v: Vehicle, out = new THREE.Vector3()) { return out.set(Math.sin(v.yaw), 0, Math.cos(v.yaw)); }

  /** add a static collider while parked */
  park(v: Vehicle) {
    if (v.box) this.world.col.removeBox(v.box);
    const c = Math.abs(Math.cos(v.yaw)), s = Math.abs(Math.sin(v.yaw));
    const hx = (v.size[0] * c + v.size[2] * s) / 2 * 0.8, hz = (v.size[0] * s + v.size[2] * c) / 2 * 0.8;
    v.box = this.world.col.addBox(v.pos.x - hx, v.pos.y, v.pos.z - hz, v.pos.x + hx, v.pos.y + v.size[1] * 0.85, v.pos.z + hz, { tag: 'car', ref: v });
    this.syncTrunk(v);
  }
  unpark(v: Vehicle) { if (v.box) { this.world.col.removeBox(v.box); v.box = null; } }

  private syncTrunk(v: Vehicle) {
    const f = this.forward(v);
    v.container.pos.set(v.pos.x - f.x * v.size[2] * 0.45, v.pos.y + 0.8, v.pos.z - f.z * v.size[2] * 0.45);
    v.cx = v.pos.x; v.cz = v.pos.z;
  }

  /** ground-follow: height and tilt from four wheel contact points */
  private settle(v: Vehicle) {
    const f = this.forward(v), r = this.tmp.set(f.z, 0, -f.x);
    const hl = v.size[2] * 0.38, hw = v.size[0] * 0.38;
    const g = (dx: number, dz: number) => this.world.groundY(v.pos.x + dx, v.pos.z + dz, v.pos.y + 1);
    const fr = g(f.x * hl + r.x * hw, f.z * hl + r.z * hw), fl = g(f.x * hl - r.x * hw, f.z * hl - r.z * hw);
    const br = g(-f.x * hl + r.x * hw, -f.z * hl + r.z * hw), bl = g(-f.x * hl - r.x * hw, -f.z * hl - r.z * hw);
    const y = (fr + fl + br + bl) / 4;
    v.pos.y = y;
    v.pitch = Math.atan2(((fr + fl) - (br + bl)) / 2, hl * 2);
    v.roll = Math.atan2(((fl + bl) - (fr + br)) / 2, hw * 2);
    v.obj.position.copy(v.pos);
    v.obj.rotation.set(0, 0, 0);
    v.obj.rotateY(v.yaw); v.obj.rotateX(-v.pitch); v.obj.rotateZ(v.roll);
  }

  /** returns impact speed if crashed this frame */
  drive(v: Vehicle, dt: number, inp: DriveInput, zombies: Zombies, particles: Particles, night: boolean): number {
    const running = this.canStart(v);
    const max = MAX_SPEED[v.variant] * (0.55 + 0.45 * v.condition / 100);
    const onRoad = this.world.terrain.isRoad(v.pos.x, v.pos.z) || this.world.terrain.splatAt(v.pos.x, v.pos.z)[1] > 120;
    const terrainMax = onRoad ? max : max * 0.55;
    // throttle / brake
    let accel = 0;
    if (running) {
      if (inp.throttle > 0) accel = v.speed < -0.5 ? 14 : 7 * (1 - Math.max(0, v.speed) / terrainMax);
      else if (inp.throttle < 0) accel = v.speed > 0.5 ? -14 : -4 * (1 - Math.max(0, -v.speed) / 7);
    }
    v.speed += accel * dt;
    if (inp.brake) v.speed = damp(v.speed, 0, 3, dt);
    v.speed = damp(v.speed, 0, onRoad ? 0.25 : 0.7, dt); // rolling resistance
    if (v.speed > terrainMax) v.speed = damp(v.speed, terrainMax, 2, dt);
    // slopes
    v.speed -= Math.sin(v.pitch) * 9.8 * dt * 0.6;
    // steering
    const steerTarget = inp.steer * clamp(0.6 - Math.abs(v.speed) * 0.012, 0.18, 0.6);
    v.steer = damp(v.steer, steerTarget, 6, dt);
    const wheelbase = v.size[2] * 0.6;
    v.yaw += (v.speed / wheelbase) * Math.tan(v.steer) * dt * (inp.brake && Math.abs(v.speed) > 6 ? 1.5 : 1);
    // move
    const f = this.forward(v);
    const prev = v.pos.clone();
    v.pos.addScaledVector(f, v.speed * dt);
    // collisions: front and rear probe circles
    let impact = 0;
    const rad = v.size[0] * 0.5;
    for (const k of [0.32, -0.32]) {
      const probe = new THREE.Vector3(v.pos.x + f.x * v.size[2] * k, v.pos.y + 0.3, v.pos.z + f.z * v.size[2] * k);
      const before = probe.clone();
      const r = this.world.col.resolveCylinder(probe, rad, 1.2, 0.6, 'window_broken');
      if (r.hit) {
        v.pos.x += probe.x - before.x; v.pos.z += probe.z - before.z;
        impact = Math.max(impact, Math.abs(v.speed));
      }
    }
    if (impact > 0) {
      if (impact > 5) { v.condition = Math.max(0, v.condition - (impact - 5) * 1.6); audio.play('hit_metal', { pos: v.pos, volume: Math.min(1.5, impact / 12) }); particles.sparks(v.pos.clone().addScaledVector(f, v.size[2] * 0.5).setY(v.pos.y + 0.6), 10); }
      v.speed *= -0.25;
    }
    if (!this.world.inBounds(v.pos.x, v.pos.z)) { v.pos.copy(prev); v.speed = 0; }
    // run down zombies
    const hl = v.size[2] / 2 + 0.4, hw = v.size[0] / 2 + 0.3;
    for (const z of zombies.list) {
      if (z.state === 'dead' || z.frozen) continue;
      const dx = z.pos.x - v.pos.x, dz = z.pos.z - v.pos.z;
      if (dx * dx + dz * dz > (hl + 1) * (hl + 1)) continue;
      const lz = dx * f.x + dz * f.z, lx = dx * f.z - dz * f.x;
      if (Math.abs(lz) > hl || Math.abs(lx) > hw) continue;
      const sp = Math.abs(v.speed);
      if (sp > 3) {
        const dir = new THREE.Vector3(f.x * Math.sign(v.speed) + lx * 0.1, 0, f.z * Math.sign(v.speed));
        zombies.damage(z, sp * 9, dir, false, Math.min(6, sp * 0.4), true);
        particles.blood(z.pos.clone().setY(z.pos.y + 1), dir, 1.4);
        audio.play('hit_flesh', { pos: z.pos, volume: 1.2 });
        v.condition = Math.max(0, v.condition - (z.type === 2 ? 4 : 0.8) * sp / 10);
        v.speed *= z.type === 2 ? 0.55 : 0.93;
      } else {
        // push out sideways
        const push = (hw - Math.abs(lx)) * Math.sign(lx || 1);
        z.pos.x += f.z * push; z.pos.z -= f.x * push;
      }
    }
    // fuel & noise
    if (running) v.fuel = Math.max(0, v.fuel - dt * (0.00025 + Math.abs(inp.throttle) * 0.0011 * (Math.abs(v.speed) / 20 + 0.3)));
    v.noiseT -= dt;
    if (running && v.noiseT <= 0) { v.noiseT = 0.6; zombies.noise(v.pos, 22 + Math.abs(v.speed) * 1.6); }
    this.settle(v);
    this.syncTrunk(v);
    // headlights
    v.lights = running && night;
    if (v.lights) {
      this.headlight.intensity = 900;
      this.headlight.position.copy(v.pos).addScaledVector(f, v.size[2] * 0.5).setY(v.pos.y + 0.8);
      this.headlight.target.position.copy(v.pos).addScaledVector(f, 25).setY(v.pos.y - 1);
      this.headlight.target.updateMatrixWorld();
    } else this.headlight.intensity = 0;
    // engine sound
    if (running) audio.updateLoop('engine', { pos: v.pos, volume: 0.35 + Math.min(0.65, Math.abs(v.speed) / 25) });
    return impact;
  }

  /** where the player gets out (left side, falling back to the right) */
  exitPoint(v: Vehicle): THREE.Vector3 {
    const f = this.forward(v);
    for (const side of [-1, 1]) {
      const p = new THREE.Vector3(v.pos.x - f.z * side * (v.size[0] / 2 + 0.8), v.pos.y, v.pos.z + f.x * side * (v.size[0] / 2 + 0.8));
      const test = p.clone(); const r = this.world.col.resolveCylinder(test, 0.35, 1.7);
      if (!r.hit) { p.y = this.world.groundY(p.x, p.z, p.y + 1); return p; }
    }
    const p = v.pos.clone().addScaledVector(f, -(v.size[2] / 2 + 1)); p.y = this.world.groundY(p.x, p.z, p.y + 1); return p;
  }

  nearest(p: THREE.Vector3, r: number) { let best: Vehicle | null = null, bd = r * r; for (const v of this.list) { const d = v.pos.distanceToSquared(p); if (d < bd) { bd = d; best = v; } } return best; }

  steerToward(v: Vehicle, yaw: number) { return clamp(angleDiff(v.yaw, yaw), -1, 1); }

  toJSON() { return this.list.map(v => ({ variant: v.variant, color: v.color, x: v.pos.x, z: v.pos.z, yaw: v.yaw, fuel: v.fuel, condition: v.condition, keys: v.keys, hotwired: v.hotwired, townId: v.townId, inv: v.container.inv.stacks })); }
  load(arr: any[]) {
    for (const s of arr ?? []) {
      let v: Vehicle;
      const tc = s.townId >= 0 ? this.world.town.carBoxes[s.townId] : undefined;
      if (tc && !tc.taken) { v = this.fromTown(tc); this.unpark(v); v.pos.set(s.x, 0, s.z); v.yaw = s.yaw; }
      else v = this.spawn(s.variant, s.color, s.x, s.z, s.yaw);
      v.pos.set(s.x, this.world.groundY(s.x, s.z), s.z); v.yaw = s.yaw;
      v.fuel = s.fuel; v.condition = s.condition; v.keys = s.keys; v.hotwired = s.hotwired;
      if (s.inv) v.container.inv.load(s.inv);
      this.settle(v); this.park(v);
    }
  }
}
