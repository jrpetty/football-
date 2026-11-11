// Instanced rendering of thousands of static props with distance-based visibility.
import * as THREE from 'three';

interface Sub { geo: THREE.BufferGeometry; mat: THREE.Material; local: THREE.Matrix4; colorable: boolean; shadow: boolean }
interface Entry { tpl: string; m: THREE.Matrix4; x: number; z: number; maxD2: number; color?: THREE.Color; alive: boolean }

export class PropBatcher {
  group = new THREE.Group();
  private templates = new Map<string, Sub[]>();
  private entries: Entry[] = [];
  private meshes = new Map<string, THREE.InstancedMesh[]>();
  private counts = new Map<string, number>();
  private last = new THREE.Vector2(1e9, 1e9);
  private tmp = new THREE.Matrix4();
  private white = new THREE.Color(1, 1, 1);

  register(key: string, obj: THREE.Object3D, colorMat?: THREE.Material, shadow = true) {
    if (this.templates.has(key)) return;
    obj.updateMatrixWorld(true);
    const subs: Sub[] = [];
    obj.traverse(o => {
      const m = o as THREE.Mesh;
      if (!m.isMesh) return;
      const mats = Array.isArray(m.material) ? m.material : [m.material];
      subs.push({ geo: m.geometry, mat: mats[0], local: m.matrixWorld.clone(), colorable: !!colorMat && mats[0] === colorMat, shadow: shadow && m.castShadow !== false });
    });
    this.templates.set(key, subs);
  }
  has(key: string) { return this.templates.has(key); }
  add(key: string, x: number, y: number, z: number, ry: number, maxDist = 120, color?: number, scale = 1) {
    const m = new THREE.Matrix4().compose(new THREE.Vector3(x, y, z), new THREE.Quaternion().setFromAxisAngle(THREE.Object3D.DEFAULT_UP, ry), new THREE.Vector3(scale, scale, scale));
    const e: Entry = { tpl: key, m, x, z, maxD2: maxDist * maxDist, color: color !== undefined ? new THREE.Color(color) : undefined, alive: true };
    this.entries.push(e);
    this.counts.set(key, (this.counts.get(key) ?? 0) + 1);
    return e;
  }
  remove(e: Entry) { e.alive = false; this.last.set(1e9, 1e9); }

  finalize() {
    for (const [key, subs] of this.templates) {
      const n = this.counts.get(key) ?? 0; if (!n) continue;
      const list: THREE.InstancedMesh[] = [];
      for (const s of subs) {
        const im = new THREE.InstancedMesh(s.geo, s.mat, n);
        im.count = 0; im.castShadow = s.shadow; im.receiveShadow = true; im.frustumCulled = false;
        if (s.colorable) im.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(n * 3).fill(1), 3);
        (im as any)._local = s.local; (im as any)._colorable = s.colorable;
        this.group.add(im); list.push(im);
      }
      this.meshes.set(key, list);
    }
  }

  update(pos: THREE.Vector3, force = false) {
    if (!force && Math.hypot(pos.x - this.last.x, pos.z - this.last.y) < 8) return;
    this.last.set(pos.x, pos.z);
    for (const list of this.meshes.values()) for (const im of list) im.count = 0;
    for (const e of this.entries) {
      if (!e.alive) continue;
      const d2 = (e.x - pos.x) ** 2 + (e.z - pos.z) ** 2;
      if (d2 > e.maxD2) continue;
      const list = this.meshes.get(e.tpl); if (!list) continue;
      for (const im of list) {
        const i = im.count++;
        this.tmp.multiplyMatrices(e.m, (im as any)._local);
        im.setMatrixAt(i, this.tmp);
        if ((im as any)._colorable) im.setColorAt(i, e.color ?? this.white);
      }
    }
    for (const list of this.meshes.values()) for (const im of list) { im.instanceMatrix.needsUpdate = true; if (im.instanceColor) im.instanceColor.needsUpdate = true; }
  }
}
