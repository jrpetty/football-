// Survivor NPCs: recruit them with food, give orders (follow / guard / scavenge / wait). They fight, eat, and can die.
import * as THREE from 'three';
import { RNG, angleDiff, clamp } from '../core/util';
import { World } from '../world/world';
import type { Zombies, HumanPose } from './zombies';
import { Inventory } from '../items/inventory';
import { rollLoot, ContainerKind } from '../items/loot';
import { audio } from '../audio/Audio';
import type { Particles } from '../render/particles';

export type Order = 'follow' | 'guard' | 'scavenge' | 'wait' | 'work';
export type Job = 'idle' | 'farmer' | 'cook' | 'guard' | 'lumberjack' | 'mechanic' | 'medic';
export interface Survivor {
  id: number; name: string; role: 'shooter' | 'brawler' | 'scavenger'; pos: THREE.Vector3; yaw: number; speed: number; hp: number;
  recruited: boolean; order: Order; ammo: number; hungry: number; away: number; pose: HumanPose; anchor: THREE.Vector3;
  target: any; fireT: number; senseT: number; dead: boolean; kind: 'survivor'; bark: number;
  /** town life */ job: Job; home: number; work: number; happy: number; thirsty: number; gloom: number; meal: boolean; jobLock: boolean;
}
export interface TownGoal { pos: THREE.Vector3; want: number; hide?: boolean; face?: number; perch?: boolean }

const NAMES = ['Marcus', 'Elena', 'Dwayne', 'Priya', 'Tom', 'Hannah', 'Luis', 'Grace', 'Owen', 'Mei', 'Abdul', 'Rosa', 'Pete', 'June', 'Kofi', 'Ava', 'Walt', 'Nadia', 'Bo', 'Sienna', 'Ray', 'Ingrid', 'Hector', 'Lena', 'Sam', 'Tasha', 'Ivan', 'Maggie', 'Earl', 'Yuki', 'Dev', 'Ruth', 'Cal', 'Fatima', 'Gus', 'Noor', 'Rhys', 'Dolores', 'Theo', 'Bea', 'Jonah', 'Carmen', 'Nils', 'Opal', 'Zeke', 'Wren', 'Arlo', 'Esther', 'Mack', 'Lucia', 'Silas', 'Kim', 'Reggie', 'Ada', 'Booker', 'Tilly', 'Ezra', 'Margo', 'Duke', 'Ines'];
const LINES = {
  idle: ['Quiet out here. Too quiet.', 'Got anything to eat?', 'I keep hearing them at night.', 'You really think we can rebuild?'],
  recruit: ['Food first, then we talk.', 'You got a safe place? I\'m done running.'],
  joined: ['Alright. I\'m with you.', 'Lead the way, boss.', 'Better than dying alone.'],
  hungry: ['I\'m starving here…', 'We need food in the storage. Soon.'],
};

export class Survivors {
  list: Survivor[] = [];
  private rng: RNG; private nextId = 1;
  constructor(private world: World, private zombies: Zombies, seed: number) { this.rng = new RNG(seed + 4141); }

  spawn(x: number, z: number, recruited = false): Survivor {
    const pose: HumanPose = { pos: new THREE.Vector3(x, this.world.groundY(x, z), z), yaw: 0, move: 0, phase: 0, arms: 0, atk: 0, seed: this.rng.next() * 100, type: 4, hit: 0, visible: true };
    this.zombies.humans.push(pose);
    const role = this.rng.pick(['shooter', 'brawler', 'scavenger'] as const);
    const s: Survivor = {
      id: this.nextId++, name: this.rng.pick(NAMES.filter(n => !this.list.some(o => o.name === n))) ?? 'Stranger', role, pos: pose.pos, yaw: 0, speed: 0, hp: 100,
      recruited, order: recruited ? 'follow' : 'wait', ammo: role === 'shooter' ? 30 : 0, hungry: 0, away: 0, pose, anchor: new THREE.Vector3(x, 0, z),
      target: null, fireT: 0, senseT: 0, dead: false, kind: 'survivor', bark: 0,
      job: 'idle', home: -1, work: -1, happy: 60, thirsty: 0, gloom: 0, meal: false, jobLock: false,
    };
    this.list.push(s);
    return s;
  }
  /** a few survivors holed up around the map */
  populate() {
    const spots = this.world.town.buildings.filter(b => ['police', 'clinic', 'barn', 'church', 'school', 'cabin'].includes(b.kind) && Math.hypot(b.x + 176, b.z + 206) > 20);
    for (let i = 0; i < Math.min(4, spots.length); i++) {
      const b = spots[(i * 3) % spots.length];
      const sp = b.zombieSpawns[0] ?? new THREE.Vector3(b.x, b.floorY, b.z);
      this.spawn(sp.x, sp.z);
      // clear zombies right on top of them
      for (const z of this.zombies.list) if (z.pos.distanceTo(sp) < 6) z.hp = 0, z.state = 'dead', z.death = 1, z.corpse = null;
    }
  }
  line(kind: keyof typeof LINES) { return this.rng.pick(LINES[kind]); }

  nearest(p: THREE.Vector3, r: number) { let best: Survivor | null = null, bd = r * r; for (const s of this.list) { if (s.dead || s.away > 0) continue; const d = s.pos.distanceToSquared(p); if (d < bd) { bd = d; best = s; } } return best; }

  update(dt: number, dtMin: number, ctx: { player: THREE.Vector3; hub: THREE.Vector3 | null; particles: Particles; notify: (m: string, k?: string) => void; deposit: (inv: Inventory) => void; townGoal?: (s: Survivor) => TownGoal | null }) {
    for (const s of this.list) {
      if (s.dead) { s.pose.visible = s.pose.hit < 5; continue; }
      // away scavenging
      if (s.away > 0) {
        s.away -= dtMin; s.pose.visible = false;
        if (s.away <= 0) {
          if (this.rng.chance(0.12)) { s.dead = true; ctx.notify(`📻 ${s.name} never came back from the scavenging run.`, 'bad'); continue; }
          const inv = new Inventory();
          for (const k of ['counter', 'shop_shelf', 'garage', 'medicine', 'crate'] as ContainerKind[]) rollLoot(k, this.rng, 0.4, inv);
          ctx.deposit(inv);
          s.pos.copy(ctx.hub ?? ctx.player).add(new THREE.Vector3(3, 0, 3)); s.order = ctx.hub ? 'work' : 'wait'; s.anchor.copy(s.pos); s.pose.visible = true;
          ctx.notify(`🎒 ${s.name} is back from scavenging with supplies (left at the Settlement Hall).`, 'good');
        }
        continue;
      }
      s.pose.visible = true;
      // pick a goal
      let goal: THREE.Vector3 | null = null, want = 0, face: number | undefined, perch: THREE.Vector3 | null = null;
      if (s.recruited && s.order === 'work' && ctx.townGoal) {
        const tg = ctx.townGoal(s);
        if (tg?.hide) { s.pose.visible = false; s.pos.copy(tg.pos); s.speed = 0; s.target = null; if (s.hp < 100) s.hp = Math.min(100, s.hp + dtMin * 0.15); continue; }
        if (tg) { const dh = Math.hypot(s.pos.x - tg.pos.x, s.pos.z - tg.pos.z); if (tg.perch && dh < 2.2) { perch = tg.pos; face = tg.face; } else if (dh > 1.2) { goal = tg.pos; want = tg.want; } else face = tg.face; }
      } else if (s.recruited) {
        if (s.order === 'follow') { const d = s.pos.distanceTo(ctx.player); if (d > 3.5) { goal = ctx.player; want = d > 10 ? 5.5 : 3; } }
        else if (s.order === 'guard') { const home = ctx.hub ?? s.anchor; if (s.pos.distanceTo(home) > 18) { goal = home; want = 3; } }
        else if (s.order === 'wait') { if (s.pos.distanceTo(s.anchor) > 3) { goal = s.anchor; want = 2; } }
      }
      // threats
      s.senseT -= dt;
      if (s.senseT <= 0) {
        s.senseT = 0.4; s.target = null;
        let bd = ((s.role === 'shooter' || s.job === 'guard') && s.ammo > 0 ? (s.job === 'guard' ? 32 : 20) : 4) ** 2;
        for (const z of this.zombies.list) { if (z.state === 'dead' || z.frozen) continue; const d = z.pos.distanceToSquared(s.pos); if (d < bd && !this.world.col.blocked(s.pos.x, s.pos.y + 1.5, s.pos.z, z.pos.x, z.pos.y + 1.4, z.pos.z)) { bd = d; s.target = z; } }
      }
      const z = s.target;
      s.fireT -= dt;
      if (z && z.state !== 'dead' && s.recruited) {
        s.yaw += angleDiff(s.yaw, Math.atan2(z.pos.x - s.pos.x, z.pos.z - s.pos.z)) * Math.min(1, dt * 8);
        const d = z.pos.distanceTo(s.pos);
        if ((s.role === 'shooter' || s.job === 'guard') && s.ammo > 0 && d > 2) {
          s.pose.arms = 1;
          if (s.fireT <= 0) {
            s.fireT = 0.7; s.ammo--;
            const dir = z.pos.clone().sub(s.pos).normalize();
            if (this.rng.chance(0.65)) { this.zombies.damage(z, 48, dir, this.rng.chance(0.2), 0.6, false); ctx.particles.blood(z.pos.clone().setY(z.pos.y + 1.3), dir, 0.6); }
            const mz = s.pos.clone().setY(s.pos.y + 1.4).addScaledVector(dir, 0.6); ctx.particles.muzzle(mz, dir);
            audio.play('gunshot_pistol', { pos: s.pos, volume: 0.8 }); this.zombies.noise(s.pos, 60);
            if (s.ammo === 0) ctx.notify(`${s.name}: "I'm out of ammo!"`, 'bad');
          }
          goal = null; want = 0;
        } else if (d < 1.9) {
          if (s.fireT <= 0) { s.fireT = 0.9; s.pose.atk = 1; const dir = z.pos.clone().sub(s.pos).normalize(); this.zombies.damage(z, s.role === 'brawler' ? 40 : 22, dir, this.rng.chance(0.15), 1.2, false); audio.play('hit_flesh', { pos: z.pos }); }
          goal = null; want = 0;
        } else if (s.role !== 'shooter' || s.ammo <= 0) { goal = z.pos; want = 3.5; }
      } else { s.pose.arms = 0; }
      if (!s.recruited && z && z.pos.distanceTo(s.pos) < 6) { goal = s.pos.clone().add(s.pos.clone().sub(z.pos).setY(0).normalize().multiplyScalar(5)); want = 4; }
      s.pose.atk = Math.max(0, s.pose.atk - dt * 3);
      s.pose.lookYaw = z && z.state !== 'dead' ? 0 : clamp(Math.atan2(Math.sin(Math.atan2(ctx.player.x - s.pos.x, ctx.player.z - s.pos.z) - s.yaw), Math.cos(Math.atan2(ctx.player.x - s.pos.x, ctx.player.z - s.pos.z) - s.yaw)), -1, 1) * (s.pos.distanceTo(ctx.player) < 6 ? 1 : 0);
      // zombies hurt survivors at close range
      for (const zz of this.zombies.list) {
        if (zz.state === 'dead' || zz.frozen) continue;
        if (zz.pos.distanceToSquared(s.pos) < 1.2 && this.rng.chance(dt * 0.9)) { s.hp -= zz.type === 2 ? 25 : 9; s.pose.hit = 1; audio.play('player_hurt', { pos: s.pos, pitch: 1.2, volume: 0.7 }); }
      }
      s.pose.hit = Math.max(0, s.pose.hit - dt * 3);
      if (s.hp <= 0) { s.dead = true; s.pose.visible = false; ctx.notify(`💀 ${s.name} has been killed.`, 'bad'); continue; }
      if (s.hp < 100) s.hp = Math.min(100, s.hp + dtMin * 0.05);
      // steer
      if (!goal && face !== undefined && !s.target) s.yaw += angleDiff(s.yaw, face) * Math.min(1, dt * 3);
      if (goal) s.yaw += angleDiff(s.yaw, Math.atan2(goal.x - s.pos.x, goal.z - s.pos.z)) * Math.min(1, dt * 6);
      s.speed += (want - s.speed) * Math.min(1, dt * 5);
      if (perch) { s.pos.copy(perch); s.speed = 0; }
      else {
      s.pos.x += Math.sin(s.yaw) * s.speed * dt; s.pos.z += Math.cos(s.yaw) * s.speed * dt;
      this.world.col.resolveCylinder(s.pos, 0.3, 1.7, 0.45);
      const pd = s.pos.distanceTo(ctx.player); if (pd < 0.7 && pd > 1e-3) s.pos.add(s.pos.clone().sub(ctx.player).setY(0).normalize().multiplyScalar(0.7 - pd));
      s.pos.y += (this.world.groundY(s.pos.x, s.pos.z, s.pos.y + 0.6) - s.pos.y) * Math.min(1, dt * 10);
      }
      // pose
      s.pose.yaw = s.yaw; s.pose.move = s.speed < 0.2 ? 0 : s.speed < 4 ? 1 : 1.6;
      s.pose.phase += dt * (s.speed * 3.2 + 0.1);
    }
  }

  /** daily food: each recruited survivor eats one food item from base storage */
  feed(take: () => boolean, notify: (m: string, k?: string) => void, town = false) {
    for (const s of this.list) {
      if (!s.recruited || s.dead || (town && (s.order === 'work' || s.order === 'guard'))) continue; // townsfolk are fed by the Town
      if (take()) s.hungry = 0;
      else {
        s.hungry++;
        notify(`${s.name}: "${this.line('hungry')}"`, 'bad');
        if (s.hungry >= 3) { s.recruited = false; s.order = 'wait'; s.anchor.copy(s.pos).add(new THREE.Vector3(40, 0, 40)); notify(`${s.name} left your group — there was no food.`, 'bad'); }
      }
    }
  }

  toJSON() { return this.list.filter(s => !s.dead).map(s => ({ name: s.name, role: s.role, x: s.pos.x, z: s.pos.z, hp: s.hp, recruited: s.recruited, order: s.order, ammo: s.ammo, hungry: s.hungry, away: s.away, ax: s.anchor.x, az: s.anchor.z, job: s.job, home: s.home, work: s.work, happy: s.happy, thirsty: s.thirsty, gloom: s.gloom, jobLock: s.jobLock })); }
  load(arr: any[]) {
    for (const s of this.list) s.pose.visible = false;
    this.list = [];
    this.zombies.humans = this.zombies.humans.filter(h => h.type !== 4);
    for (const o of arr ?? []) { const s = this.spawn(o.x, o.z, o.recruited); Object.assign(s, { name: o.name, role: o.role, hp: o.hp, order: o.order, ammo: o.ammo, hungry: o.hungry, away: o.away, job: o.job ?? 'idle', home: -1, work: -1, happy: o.happy ?? 60, thirsty: o.thirsty ?? 0, gloom: o.gloom ?? 0, jobLock: o.jobLock ?? false }); s.anchor.set(o.ax, 0, o.az); }
  }
  clamp = clamp;
}
