// CPU-simulated pooled particles rendered as soft GPU points + ground decals.
import * as THREE from 'three';
import { getBloodDecalTexture } from './textures';

interface P { x: number; y: number; z: number; vx: number; vy: number; vz: number; life: number; max: number; size: number; r: number; g: number; b: number; grav: number; drag: number; grow: number; alpha: number }

class Pool {
  ps: P[] = [];
  geo = new THREE.BufferGeometry();
  pos: Float32Array; col: Float32Array; siz: Float32Array;
  points: THREE.Points;
  constructor(public max: number, additive: boolean, scene: THREE.Scene) {
    this.pos = new Float32Array(max * 3); this.col = new Float32Array(max * 4); this.siz = new Float32Array(max);
    this.geo.setAttribute('position', new THREE.BufferAttribute(this.pos, 3).setUsage(THREE.DynamicDrawUsage));
    this.geo.setAttribute('color', new THREE.BufferAttribute(this.col, 4).setUsage(THREE.DynamicDrawUsage));
    this.geo.setAttribute('size', new THREE.BufferAttribute(this.siz, 1).setUsage(THREE.DynamicDrawUsage));
    const mat = new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, blending: additive ? THREE.AdditiveBlending : THREE.NormalBlending,
      uniforms: { uScale: { value: 600 } },
      vertexShader: `attribute float size; attribute vec4 color; varying vec4 vC; uniform float uScale;
        void main(){ vC = color; vec4 mv = modelViewMatrix * vec4(position,1.0); gl_PointSize = size * uScale / -mv.z; gl_Position = projectionMatrix * mv; }`,
      fragmentShader: `varying vec4 vC; void main(){ float d = length(gl_PointCoord-0.5); float a = smoothstep(0.5, 0.1, d); gl_FragColor = vec4(vC.rgb, vC.a * a); }`,
    });
    this.points = new THREE.Points(this.geo, mat);
    this.points.frustumCulled = false;
    scene.add(this.points);
  }
  spawn(p: P) { if (this.ps.length >= this.max) this.ps.shift(); this.ps.push(p); }
  update(dt: number) {
    let n = 0;
    for (let i = this.ps.length - 1; i >= 0; i--) {
      const p = this.ps[i]; p.life -= dt;
      if (p.life <= 0) { this.ps[i] = this.ps[this.ps.length - 1]; this.ps.pop(); continue; }
      p.vy -= p.grav * dt; const dr = Math.exp(-p.drag * dt); p.vx *= dr; p.vy *= dr; p.vz *= dr;
      p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt; p.size += p.grow * dt;
    }
    for (const p of this.ps) {
      const t = p.life / p.max;
      this.pos[n * 3] = p.x; this.pos[n * 3 + 1] = p.y; this.pos[n * 3 + 2] = p.z;
      this.col[n * 4] = p.r; this.col[n * 4 + 1] = p.g; this.col[n * 4 + 2] = p.b; this.col[n * 4 + 3] = p.alpha * Math.min(1, t * 3);
      this.siz[n] = p.size; n++;
    }
    this.geo.setDrawRange(0, n);
    (this.geo.attributes.position as THREE.BufferAttribute).needsUpdate = true;
    (this.geo.attributes.color as THREE.BufferAttribute).needsUpdate = true;
    (this.geo.attributes.size as THREE.BufferAttribute).needsUpdate = true;
  }
}

export class Particles {
  add: Pool; norm: Pool;
  decals: THREE.Mesh[] = [];
  private decalGeo = new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2);
  private decalMat: THREE.MeshStandardMaterial;
  constructor(private scene: THREE.Scene) {
    this.add = new Pool(2500, true, scene);
    this.norm = new Pool(2500, false, scene);
    this.decalMat = new THREE.MeshStandardMaterial({ map: getBloodDecalTexture(), transparent: true, depthWrite: false, roughness: 0.3, polygonOffset: true, polygonOffsetFactor: -4, color: 0x8a0d0d });
  }
  private P(o: Partial<P>): P { return { x: 0, y: 0, z: 0, vx: 0, vy: 0, vz: 0, life: 1, max: 1, size: 0.1, r: 1, g: 1, b: 1, grav: 0, drag: 0, grow: 0, alpha: 1, ...o }; }

  blood(at: THREE.Vector3, dir: THREE.Vector3, amount = 1) {
    for (let i = 0; i < 18 * amount; i++) {
      const s = 1 + Math.random() * 3;
      const life = 0.5 + Math.random() * 0.6;
      this.norm.spawn(this.P({ x: at.x, y: at.y, z: at.z, vx: dir.x * s + (Math.random() - 0.5) * 2.5, vy: dir.y * s + Math.random() * 2.5, vz: dir.z * s + (Math.random() - 0.5) * 2.5, life, max: life, size: 0.04 + Math.random() * 0.07, r: 0.35, g: 0.02, b: 0.02, grav: 9.8, drag: 1, alpha: 0.95 }));
    }
    for (let i = 0; i < 4 * amount; i++) {
      const life = 0.6 + Math.random() * 0.5;
      this.norm.spawn(this.P({ x: at.x, y: at.y, z: at.z, vx: dir.x * 0.6 + (Math.random() - 0.5), vy: Math.random() * 0.4, vz: dir.z * 0.6 + (Math.random() - 0.5), life, max: life, size: 0.15, grow: 0.8, r: 0.3, g: 0.02, b: 0.02, drag: 3, alpha: 0.5 }));
    }
  }
  sparks(at: THREE.Vector3, n = 12) {
    for (let i = 0; i < n; i++) {
      const life = 0.2 + Math.random() * 0.4;
      this.add.spawn(this.P({ x: at.x, y: at.y, z: at.z, vx: (Math.random() - 0.5) * 6, vy: Math.random() * 5, vz: (Math.random() - 0.5) * 6, life, max: life, size: 0.025, r: 1, g: 0.7, b: 0.3, grav: 9.8, drag: 0.5 }));
    }
  }
  dust(at: THREE.Vector3, n = 8, col = [0.45, 0.4, 0.33]) {
    for (let i = 0; i < n; i++) {
      const life = 0.8 + Math.random() * 0.8;
      this.norm.spawn(this.P({ x: at.x + (Math.random() - 0.5) * 0.3, y: at.y, z: at.z + (Math.random() - 0.5) * 0.3, vx: (Math.random() - 0.5) * 1.5, vy: Math.random() * 1.2, vz: (Math.random() - 0.5) * 1.5, life, max: life, size: 0.12, grow: 0.6, r: col[0], g: col[1], b: col[2], drag: 2, alpha: 0.45 }));
    }
  }
  muzzle(at: THREE.Vector3, dir: THREE.Vector3) {
    for (let i = 0; i < 6; i++) {
      const life = 0.05 + Math.random() * 0.04;
      const s = Math.random() * 3;
      this.add.spawn(this.P({ x: at.x, y: at.y, z: at.z, vx: dir.x * s * 4, vy: dir.y * s * 4, vz: dir.z * s * 4, life, max: life, size: 0.12 + Math.random() * 0.15, r: 1, g: 0.75, b: 0.35 }));
    }
    for (let i = 0; i < 5; i++) {
      const life = 0.8 + Math.random();
      this.norm.spawn(this.P({ x: at.x, y: at.y, z: at.z, vx: dir.x * 1.5 + (Math.random() - 0.5) * 0.4, vy: 0.3 + Math.random() * 0.3, vz: dir.z * 1.5 + (Math.random() - 0.5) * 0.4, life, max: life, size: 0.08, grow: 0.5, r: 0.6, g: 0.6, b: 0.6, drag: 3, alpha: 0.25 }));
    }
  }
  fire(at: THREE.Vector3, intensity = 1) {
    if (Math.random() < 0.8 * intensity) {
      const life = 0.4 + Math.random() * 0.5;
      this.add.spawn(this.P({ x: at.x + (Math.random() - 0.5) * 0.35, y: at.y, z: at.z + (Math.random() - 0.5) * 0.35, vx: (Math.random() - 0.5) * 0.3, vy: 1.2 + Math.random(), vz: (Math.random() - 0.5) * 0.3, life, max: life, size: 0.25 + Math.random() * 0.2, grow: -0.3, r: 1, g: 0.45 + Math.random() * 0.2, b: 0.12, drag: 1, alpha: 0.8 }));
    }
    if (Math.random() < 0.25 * intensity) {
      const life = 2 + Math.random() * 2;
      this.norm.spawn(this.P({ x: at.x, y: at.y + 0.6, z: at.z, vx: (Math.random() - 0.5) * 0.3, vy: 0.8, vz: (Math.random() - 0.5) * 0.3, life, max: life, size: 0.25, grow: 0.6, r: 0.25, g: 0.25, b: 0.25, drag: 0.4, alpha: 0.18 }));
    }
    if (Math.random() < 0.15 * intensity) this.add.spawn(this.P({ x: at.x, y: at.y + 0.2, z: at.z, vx: (Math.random() - 0.5), vy: 2 + Math.random() * 2, vz: (Math.random() - 0.5), life: 1.2, max: 1.2, size: 0.03, r: 1, g: 0.6, b: 0.2, drag: 0.6 }));
  }
  smoke(at: THREE.Vector3, dark = 0.3) {
    const life = 3 + Math.random() * 2;
    this.norm.spawn(this.P({ x: at.x + (Math.random() - 0.5) * 0.3, y: at.y, z: at.z + (Math.random() - 0.5) * 0.3, vx: (Math.random() - 0.5) * 0.4 + 0.3, vy: 1 + Math.random() * 0.5, vz: (Math.random() - 0.5) * 0.4, life, max: life, size: 0.5, grow: 1.2, r: dark, g: dark, b: dark, drag: 0.3, alpha: 0.22 }));
  }
  electric(at: THREE.Vector3) {
    for (let i = 0; i < 8; i++) {
      const life = 0.08 + Math.random() * 0.1;
      this.add.spawn(this.P({ x: at.x + (Math.random() - 0.5) * 0.8, y: at.y + Math.random() * 1.5, z: at.z + (Math.random() - 0.5) * 0.8, life, max: life, size: 0.08, r: 0.5, g: 0.7, b: 1 }));
    }
  }
  bloodDecal(x: number, y: number, z: number, size = 1) {
    const m = new THREE.Mesh(this.decalGeo, this.decalMat);
    m.position.set(x, y + 0.03, z); m.rotation.y = Math.random() * Math.PI * 2; m.scale.setScalar(size * (0.6 + Math.random() * 0.8));
    m.receiveShadow = true;
    this.scene.add(m); this.decals.push(m);
    if (this.decals.length > 140) { const o = this.decals.shift()!; this.scene.remove(o); }
  }
  update(dt: number) { this.add.update(dt); this.norm.update(dt); }
}
