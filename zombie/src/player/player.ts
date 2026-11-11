// Player: first-person movement & collision, inventory/equipment, survival stats, skills, traits.
import * as THREE from 'three';
import { clamp, damp } from '../core/util';
import { Input } from '../core/input';
import { World } from '../world/world';
import { Inventory, Stack } from '../items/inventory';
import { item, ClothSlot } from '../items/items';
import { Stats } from './stats';
import { Skills, SkillId } from './skills';
import { audio, SfxName } from '../audio/Audio';

export class Player {
  pos = new THREE.Vector3();
  vel = new THREE.Vector3();
  yaw = 0; pitch = 0;
  onGround = true; crouch = false; crouchT = 0; sprinting = false; moving = false; speed = 0;
  inv = new Inventory();
  equipped: number | null = null;
  hotbar: (number | null)[] = [null, null, null, null, null, null, null, null];
  worn: Record<ClothSlot, Stack | null> = { head: null, torso: null, legs: null, feet: null, hands: null, back: null };
  stats = new Stats();
  skills = new Skills();
  traits = new Set<string>();
  occupation = 'unemployed';
  name = 'Survivor';
  flashlightOn = false;
  noise = 0;           // radius zombies can hear movement
  visibility = 0.5;    // 0..1
  private stepDist = 0; private bob = 0;
  headBob = new THREE.Vector3();
  fallVel = 0;
  sleeping = false;
  kills = 0;
  inside = false;
  bloody = 0;
  recoilPitch = 0;
  camShake = 0;
  dtStamp = 0;

  eye(out = new THREE.Vector3()) { return out.set(this.pos.x, this.pos.y + (1.62 - this.crouchT * 0.6), this.pos.z); }
  forward(out = new THREE.Vector3()) { return out.set(-Math.sin(this.yaw) * Math.cos(this.pitch), Math.sin(this.pitch), -Math.cos(this.yaw) * Math.cos(this.pitch)); }
  capacity() {
    let c = 14 + this.skills.level('strength') * 1.6;
    if (this.traits.has('strong')) c += 3; if (this.traits.has('weak')) c -= 3;
    for (const s of Object.values(this.worn)) if (s) c += item(s.id).cloth?.capacity ?? 0;
    return c;
  }
  weight() { let w = this.inv.weight(); for (const s of Object.values(this.worn)) if (s) w += item(s.id).weight * 0.3; return w; }
  weightRatio() { return this.weight() / this.capacity(); }
  equippedStack() { return this.equipped !== null ? this.inv.byUid(this.equipped) ?? null : null; }
  insulation() { let i = 0; for (const s of Object.values(this.worn)) if (s) i += item(s.id).cloth?.insul ?? 0; return clamp(i, 0, 1.5); }
  rainProtect() { let r = 0; for (const s of Object.values(this.worn)) if (s) r = Math.max(r, item(s.id).cloth?.rain ?? 0); return r; }
  protection(slot: ClothSlot) { const s = this.worn[slot]; const c = s ? item(s.id).cloth : undefined; return { bite: c?.bite ?? 0, scratch: c?.scratch ?? 0 }; }

  gainXP(id: SkillId, amt: number, now: number) { this.skills.gain(id, amt, now); }

  update(dt: number, input: Input | null, world: World, now: number, uiOpen: boolean) {
    const st = this.stats;
    const wr = this.weightRatio();
    // look
    if (input && !uiOpen) {
      this.yaw -= input.mouseDX * input.sensitivity;
      this.pitch -= input.mouseDY * input.sensitivity;
    }
    this.pitch += this.recoilPitch * dt * 12; this.recoilPitch = damp(this.recoilPitch, 0, 10, dt);
    this.pitch = clamp(this.pitch, -1.5, 1.5);
    // move intent
    let fx = 0, fz = 0;
    if (input && !uiOpen && !this.sleeping) {
      if (input.down('KeyW')) fz -= 1; if (input.down('KeyS')) fz += 1;
      if (input.down('KeyA')) fx -= 1; if (input.down('KeyD')) fx += 1;
      if (input.hit('KeyC') || input.hit('ControlLeft')) this.crouch = !this.crouch;
    }
    const len = Math.hypot(fx, fz); if (len > 0) { fx /= len; fz /= len; }
    const wantSprint = !!input && input.down('ShiftLeft') && fz < 0 && !this.crouch && st.canSprint(wr) && !uiOpen;
    this.sprinting = wantSprint && len > 0;
    const fit = this.skills.level('fitness'), spr = this.skills.level('sprinting');
    let base = this.crouch ? 1.6 : this.sprinting ? 6.0 + spr * 0.12 + (this.traits.has('athletic') ? 0.4 : 0) : 3.3;
    if (this.traits.has('obese')) base *= 0.93;
    base *= st.speedMult(wr);
    const cs = Math.cos(this.yaw), sn = Math.sin(this.yaw);
    const wx = fx * cs + fz * sn, wz = -fx * sn + fz * cs;
    const accel = this.onGround ? 12 : 2;
    this.vel.x = damp(this.vel.x, wx * base, accel, dt);
    this.vel.z = damp(this.vel.z, wz * base, accel, dt);
    // jump
    if (input && input.hit('Space') && this.onGround && !uiOpen && st.stamina > 8 && !this.crouch) { this.fallVel = 4.4; this.onGround = false; st.stamina -= 6; }
    this.fallVel -= 16 * dt;
    // integrate
    const prev = this.pos.clone();
    this.pos.x += this.vel.x * dt; this.pos.z += this.vel.z * dt; this.pos.y += this.fallVel * dt;
    const h = 1.8 - this.crouchT * 0.6;
    const res = world.col.resolveCylinder(this.pos, 0.32, h, 0.45);
    let gy = world.groundY(this.pos.x, this.pos.z, this.pos.y);
    if (res.groundY > gy) gy = res.groundY;
    // world bounds
    this.pos.x = clamp(this.pos.x, -380, 380); this.pos.z = clamp(this.pos.z, -380, 380);
    // water slows
    const wd = world.waterDepth(this.pos.x, this.pos.z);
    if (wd > 0.4) { this.vel.multiplyScalar(Math.exp(-dt * 3)); gy = Math.max(gy, world.terrain.heightAt(this.pos.x, this.pos.z)); }
    if (this.pos.y <= gy + 0.02) {
      if (!this.onGround && this.fallVel < -9) { const dmg = (-this.fallVel - 9) * 6; this.stats.addWound(Math.random() < 0.5 ? 'legL' : 'legR', dmg > 25 ? 'fracture' : 'scratch', dmg); audio.play('bone_crack'); this.camShake = 0.5; }
      if (!this.onGround && this.fallVel < -3) this.footstep(world, 1.2);
      this.pos.y = gy; this.fallVel = Math.max(0, this.fallVel); this.onGround = true;
    } else if (this.pos.y > gy + 0.35) this.onGround = false;
    else if (this.fallVel <= 0) { this.pos.y = damp(this.pos.y, gy, 20, dt); this.onGround = true; this.fallVel = 0; }
    const moved = Math.hypot(this.pos.x - prev.x, this.pos.z - prev.z);
    this.speed = moved / Math.max(dt, 1e-4);
    this.moving = this.speed > 0.3;
    this.crouchT = damp(this.crouchT, this.crouch ? 1 : 0, 10, dt);
    // footsteps & noise
    if (this.onGround && this.moving) {
      this.stepDist += moved;
      const stride = this.sprinting ? 1.9 : this.crouch ? 0.9 : 1.45;
      if (this.stepDist > stride) { this.stepDist = 0; this.footstep(world, this.crouch ? 0.35 : this.sprinting ? 1 : 0.65); }
    }
    const lf = this.skills.level('lightfoot');
    let noise = this.moving ? (this.sprinting ? 16 : this.crouch ? 2 : 6.5) : 0;
    noise *= 1 - lf * 0.06; if (this.traits.has('clumsy')) noise *= 1.5;
    this.noise = noise;
    this.visibility = clamp((this.crouch ? 0.45 : 0.85) + (this.flashlightOn ? 0.6 : 0) + (this.sprinting ? 0.2 : 0), 0, 1.4);
    // head bob
    this.bob += moved * (this.sprinting ? 1.6 : 2.2);
    const amp = Math.min(1, this.speed / 5) * (this.onGround ? 1 : 0);
    this.headBob.set(Math.sin(this.bob) * 0.03 * amp, Math.abs(Math.cos(this.bob)) * 0.05 * amp, 0);
    this.camShake = Math.max(0, this.camShake - dt * 2);
    this.inside = world.isInside(this.pos.x, this.pos.z);
    // xp for fitness/sprint
    if (this.sprinting) { this.gainXP('sprinting', dt * 0.8, now); this.gainXP('fitness', dt * 0.5, now); }
    if (this.crouch && this.moving) this.gainXP('lightfoot', dt * 0.5, now);
    if (wr > 1 && this.moving) this.gainXP('strength', dt * 0.3, now);
  }

  private footstep(world: World, vol: number) {
    const b = world.town.buildingAt(this.pos.x, this.pos.z);
    let s: SfxName = 'footstep_grass';
    if (b) s = 'footstep_wood';
    else {
      const f = world.floors.find(f => this.pos.x > f.x0 && this.pos.x < f.x1 && this.pos.z > f.z0 && this.pos.z < f.z1 && Math.abs(f.y - this.pos.y) < 0.3);
      if (f) s = (f.ref as any)?.def?.id?.startsWith('metal') ? 'footstep_metal' : 'footstep_wood';
      else if (world.terrain.isRoad(this.pos.x, this.pos.z)) s = 'footstep_road';
    }
    audio.play(s, { volume: vol * (this.traits.has('clumsy') ? 1.3 : 1) });
  }

  toJSON() {
    return { pos: this.pos.toArray(), yaw: this.yaw, pitch: this.pitch, inv: this.inv.stacks, equipped: this.equipped, hotbar: this.hotbar, worn: this.worn, stats: this.stats.toJSON(), xp: this.skills.xp, bonus: this.skills.bonus, books: this.skills.bookUntil, traits: [...this.traits], occupation: this.occupation, name: this.name, kills: this.kills };
  }
  load(o: any) {
    this.pos.fromArray(o.pos); this.yaw = o.yaw; this.pitch = o.pitch; this.inv.load(o.inv); this.equipped = o.equipped; this.hotbar = o.hotbar; this.worn = o.worn;
    this.stats.load(o.stats); this.skills.xp = o.xp; this.skills.bonus = o.bonus; this.skills.bookUntil = o.books ?? {}; this.traits = new Set(o.traits); this.occupation = o.occupation; this.name = o.name; this.kills = o.kills ?? 0;
  }
}
