// Fixed-size pool of point lights assigned to the nearest emitters each frame (keeps shader light count constant).
import * as THREE from 'three';

export interface Emitter { pos: THREE.Vector3; color: THREE.Color; intensity: number; distance: number; flicker?: number; on: boolean; temp?: number }

export class LightPool {
  lights: THREE.PointLight[] = [];
  emitters = new Set<Emitter>();
  private t = 0;
  constructor(scene: THREE.Scene, n = 8) {
    for (let i = 0; i < n; i++) {
      const l = new THREE.PointLight(0xffffff, 0, 10, 2);
      l.castShadow = false;
      scene.add(l); this.lights.push(l);
    }
  }
  add(e: Emitter) { this.emitters.add(e); return e; }
  remove(e: Emitter) { this.emitters.delete(e); }
  flash(pos: THREE.Vector3, color: number, intensity: number, distance: number, dur: number) {
    this.add({ pos: pos.clone(), color: new THREE.Color(color), intensity, distance, on: true, temp: dur });
  }
  update(dt: number, cam: THREE.Vector3) {
    this.t += dt;
    const list: [Emitter, number][] = [];
    for (const e of this.emitters) {
      if (e.temp !== undefined) { e.temp -= dt; if (e.temp <= 0) { this.emitters.delete(e); continue; } }
      if (!e.on) continue;
      const d = e.pos.distanceToSquared(cam);
      if (d > 90 * 90) continue;
      list.push([e, d - (e.temp !== undefined ? 1e6 : 0)]);
    }
    list.sort((a, b) => a[1] - b[1]);
    for (let i = 0; i < this.lights.length; i++) {
      const l = this.lights[i]; const it = list[i];
      if (!it) { l.intensity = 0; continue; }
      const e = it[0];
      l.position.copy(e.pos); l.color.copy(e.color); l.distance = e.distance;
      let f = 1;
      if (e.flicker) f = 1 - e.flicker * (0.5 + 0.5 * Math.sin(this.t * 17 + e.pos.x) * Math.sin(this.t * 7.3 + e.pos.z));
      const fade = Math.min(1, Math.max(0, 1 - (Math.sqrt(it[1] + (e.temp !== undefined ? 1e6 : 0)) - 70) / 20));
      l.intensity = e.intensity * f * fade;
    }
  }
}
