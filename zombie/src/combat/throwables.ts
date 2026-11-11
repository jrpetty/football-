// Thrown weapons: ballistic projectiles that burst into fire (molotov) or explode (pipe bomb).
import * as THREE from 'three';
import { World } from '../world/world';
import type { Zombies } from '../ai/zombies';
import type { Animals } from '../ai/animals';
import type { Particles } from '../render/particles';
import type { LightPool } from '../render/lights';
import { audio } from '../audio/Audio';
import { getFlatMaterial } from '../render/materials';

interface Proj { kind: 'molotov' | 'pipe_bomb'; pos: THREE.Vector3; vel: THREE.Vector3; mesh: THREE.Object3D; t: number }
interface Fire { pos: THREE.Vector3; t: number; r: number }

export class Throwables {
  private projs: Proj[] = [];
  fires: Fire[] = [];
  constructor(private scene: THREE.Scene, private world: World) {}

  throw(kind: Proj['kind'], from: THREE.Vector3, dir: THREE.Vector3, strength: number) {
    const g = new THREE.Group();
    if (kind === 'molotov') {
      const b = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.04, 0.22, 7), getFlatMaterial(0x3a6a3a, 0.2, 0.1)); g.add(b);
      const r = new THREE.Mesh(new THREE.ConeGeometry(0.03, 0.08, 5), getFlatMaterial(0xd8c8a0, 0.9)); r.position.y = 0.15; g.add(r);
    } else {
      const b = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.04, 0.22, 8), getFlatMaterial(0x6a6e70, 0.5, 0.6)); b.rotation.z = Math.PI / 2; g.add(b);
      const t = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.06, 0.09), getFlatMaterial(0x9a9a9a, 0.8)); g.add(t);
    }
    g.position.copy(from); this.scene.add(g);
    const vel = dir.clone().multiplyScalar(14 * strength).add(new THREE.Vector3(0, 3.5, 0));
    this.projs.push({ kind, pos: from.clone(), vel, mesh: g, t: 0 });
  }

  update(dt: number, ctx: { zombies: Zombies; animals: Animals; particles: Particles; lights: LightPool; player: THREE.Vector3; onPlayerHit: (dmg: number, burn: boolean) => void; onShake: (k: number) => void }) {
    for (let i = this.projs.length - 1; i >= 0; i--) {
      const p = this.projs[i];
      p.t += dt; p.vel.y -= 9.8 * dt;
      const next = p.pos.clone().addScaledVector(p.vel, dt);
      const dir = next.clone().sub(p.pos); const len = dir.length(); dir.normalize();
      const hit = this.world.col.raycast(p.pos, dir, len + 0.1);
      const gy = this.world.groundY(next.x, next.z, next.y + 1);
      let impact: THREE.Vector3 | null = null;
      if (hit.t < len + 0.1) impact = p.pos.clone().addScaledVector(dir, Math.max(0, hit.t - 0.1));
      else if (next.y <= gy + 0.05) impact = new THREE.Vector3(next.x, gy + 0.05, next.z);
      else if (p.t > 6) impact = next;
      if (!impact) { p.pos.copy(next); p.mesh.position.copy(next); p.mesh.rotation.x += dt * 10; p.mesh.rotation.z += dt * 6; continue; }
      this.scene.remove(p.mesh); this.projs.splice(i, 1);
      if (p.kind === 'molotov') {
        audio.play('glass_break', { pos: impact }); audio.play('fire_ignite', { pos: impact, volume: 1.4 });
        this.fires.push({ pos: impact.clone(), t: 10, r: 3.2 });
        ctx.zombies.noise(impact, 25);
      } else {
        // explosion
        audio.play('gunshot_shotgun', { pos: impact, volume: 1.8, pitch: 0.55 }); audio.play('thunder', { pos: impact, volume: 0.8 });
        ctx.zombies.noise(impact, 150);
        for (let k = 0; k < 4; k++) ctx.particles.sparks(impact, 20);
        for (let k = 0; k < 14; k++) ctx.particles.smoke(impact.clone().add(new THREE.Vector3((Math.random() - 0.5) * 3, Math.random() * 1.5, (Math.random() - 0.5) * 3)), 0.12);
        ctx.particles.dust(impact, 30, [0.35, 0.32, 0.28]);
        ctx.lights.flash(impact.clone().setY(impact.y + 1), 0xffb060, 900, 30, 0.25);
        const R = 7.5;
        for (const z of ctx.zombies.list) {
          if (z.state === 'dead') continue;
          const d = z.pos.distanceTo(impact); if (d > R) continue;
          const k = 1 - d / R; const away = z.pos.clone().sub(impact).setY(0).normalize();
          ctx.zombies.damage(z, 260 * k, away, false, 6 * k, true);
          if (k > 0.3) ctx.particles.blood(z.pos.clone().setY(z.pos.y + 1), away, 1.5);
        }
        for (const a of ctx.animals.list) { const d = a.pos.distanceTo(impact); if (d < R) ctx.animals.damage(a, 200 * (1 - d / R), impact); }
        const pd = ctx.player.distanceTo(impact);
        if (pd < R) ctx.onPlayerHit(80 * (1 - pd / R), false);
        ctx.onShake(Math.max(0, 1.5 - pd / 15));
      }
    }
    // burning areas
    for (let i = this.fires.length - 1; i >= 0; i--) {
      const f = this.fires[i]; f.t -= dt;
      if (f.t <= 0) { this.fires.splice(i, 1); continue; }
      for (let k = 0; k < 6; k++) { const a = Math.random() * 6.28, r = Math.sqrt(Math.random()) * f.r; ctx.particles.fire(new THREE.Vector3(f.pos.x + Math.cos(a) * r, f.pos.y, f.pos.z + Math.sin(a) * r), 1); }
      if (Math.random() < dt * 4) ctx.lights.flash(f.pos.clone().setY(f.pos.y + 1), 0xff7a30, 200, 12, 0.3);
      for (const z of ctx.zombies.list) { if (z.state === 'dead') continue; if (z.pos.distanceTo(f.pos) < f.r) ctx.zombies.damage(z, 28 * dt, new THREE.Vector3(0, 0, 1), false, 0, true); }
      for (const a of ctx.animals.list) if (a.state !== 'dead' && a.pos.distanceTo(f.pos) < f.r) ctx.animals.damage(a, 25 * dt, f.pos);
      if (ctx.player.distanceTo(f.pos) < f.r) ctx.onPlayerHit(12 * dt, true);
    }
  }
}
