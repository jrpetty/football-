// Forests (instanced trees with impostor LOD + wind), bushes, rocks, and an infinite GPU grass field.
import * as THREE from 'three';
import { HALF_WORLD, WORLD_SIZE, hash2, RNG, clamp } from '../core/util';
import type { TreeModel } from '../models/vegetation';
import { buildLowPolyTreeModels as buildTreeModels } from '../models/lowpoly';
import { Terrain, T_N, T_RES } from './terrain';
import { CollisionWorld, Circle } from './collision';
import { LAKE, TOWN } from './layout';

export interface TreeInst { x: number; y: number; z: number; s: number; r: number; v: number; kind: 'tree' | 'bush' | 'rock' | 'log'; alive: boolean; hp: number; col?: Circle; idx: number }

const windUniforms = { uTime: { value: 0 }, uWind: { value: 0.3 } };

function addWind(mat: THREE.MeshStandardMaterial, strength: number, key: string) {
  const m = mat.clone();
  if (strength > 0.2 && m.alphaTest > 0) m.color.multiply(new THREE.Color(0.62, 0.7, 0.6));
  m.onBeforeCompile = (sh) => {
    sh.uniforms.uTime = windUniforms.uTime; sh.uniforms.uWind = windUniforms.uWind;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nattribute float windWeight;\nuniform float uTime; uniform float uWind;')
      .replace('#include <begin_vertex>', `#include <begin_vertex>
      #ifdef USE_INSTANCING
        vec3 ip = vec3(instanceMatrix[3][0], instanceMatrix[3][1], instanceMatrix[3][2]);
      #else
        vec3 ip = vec3(0.0);
      #endif
        float ph = ip.x * 0.13 + ip.z * 0.17;
        float w = windWeight * windWeight * ${strength.toFixed(3)} * (0.4 + uWind);
        transformed.x += (sin(uTime * 1.3 + ph) * 0.6 + sin(uTime * 3.1 + ph * 2.0 + position.y) * 0.25) * w;
        transformed.z += (cos(uTime * 1.1 + ph * 1.3) * 0.4 + sin(uTime * 2.7 + position.x * 2.0) * 0.2) * w;`);
  };
  m.customProgramCacheKey = () => 'wind-' + key + '-' + mat.uuid;
  return m;
}

class Variant {
  near: THREE.InstancedMesh[] = [];
  far: THREE.InstancedMesh | null = null;
  constructor(public model: TreeModel, public kind: TreeInst['kind'], group: THREE.Group, maxNear: number, maxFar: number, impostor: THREE.Texture | null, windStrength: number, key: string) {
    for (const p of model.parts) {
      const mat = windStrength > 0 ? addWind(p.material, windStrength, key) : p.material;
      const im = new THREE.InstancedMesh(p.geometry, mat, maxNear);
      im.count = 0; im.castShadow = true; im.receiveShadow = true; im.frustumCulled = false;
      group.add(im); this.near.push(im);
    }
    if (impostor) {
      const h = model.height, w = model.height * 0.55;
      const g1 = new THREE.PlaneGeometry(w, h); g1.translate(0, h / 2, 0);
      const g2 = g1.clone().rotateY(Math.PI / 2);
      const geo = mergeTwo(g1, g2);
      const mat = new THREE.MeshStandardMaterial({ map: impostor, alphaTest: 0.45, side: THREE.DoubleSide, roughness: 0.95 });
      // impostor normals point up-ish so they're lit like canopies
      const n = geo.attributes.normal as THREE.BufferAttribute;
      for (let i = 0; i < n.count; i++) { n.setXYZ(i, n.getX(i) * 0.4, 0.9, n.getZ(i) * 0.4); }
      this.far = new THREE.InstancedMesh(geo, mat, maxFar);
      this.far.count = 0; this.far.castShadow = false; this.far.receiveShadow = false; this.far.frustumCulled = false;
      group.add(this.far);
    }
  }
}
function mergeTwo(a: THREE.BufferGeometry, b: THREE.BufferGeometry) {
  const g = new THREE.BufferGeometry();
  for (const name of ['position', 'normal', 'uv']) {
    const A = a.attributes[name] as THREE.BufferAttribute, B = b.attributes[name] as THREE.BufferAttribute;
    const arr = new Float32Array(A.array.length + B.array.length); arr.set(A.array as Float32Array); arr.set(B.array as Float32Array, A.array.length);
    g.setAttribute(name, new THREE.BufferAttribute(arr, A.itemSize));
  }
  const ia = a.index!.array, ib = b.index!.array, off = a.attributes.position.count;
  const idx: number[] = []; for (const i of ia) idx.push(i); for (const i of ib) idx.push(i + off);
  g.setIndex(idx); return g;
}

export class Vegetation {
  group = new THREE.Group();
  insts: TreeInst[] = [];
  private variants: Variant[] = [];
  private cells = new Map<number, TreeInst[]>();
  private lastCenter = new THREE.Vector2(1e9, 1e9);
  grass!: THREE.Mesh;
  private grassMat!: THREE.MeshStandardMaterial;
  nearDist = 140;
  private tmpM = new THREE.Matrix4(); private tmpQ = new THREE.Quaternion(); private tmpS = new THREE.Vector3(); private tmpP = new THREE.Vector3();
  removed = new Set<number>();

  constructor(private terrain: Terrain, private col: CollisionWorld, private renderer: THREE.WebGLRenderer, seed: number, density: number, private grassDensity: number, blocked: (x: number, z: number) => boolean) {
    const models = buildTreeModels();
    const groups: [TreeModel[], TreeInst['kind'], number, boolean][] = [
      [models.pines, 'tree', 0.35, true], [models.firs, 'tree', 0.35, true], [models.birches, 'tree', 0.5, true], [models.dead, 'tree', 0.15, true],
      [models.bushes, 'bush', 0.6, false], [models.rocks, 'rock', 0, false], [models.logs, 'log', 0, false],
    ];
    const vIndex: Record<string, number[]> = { pine: [], fir: [], birch: [], dead: [], bush: [], rock: [], log: [] };
    const keys = ['pine', 'fir', 'birch', 'dead', 'bush', 'rock', 'log'];
    groups.forEach(([list, kind, wind, imp], gi) => {
      list.forEach((m, mi) => {
        const tex = imp ? this.renderImpostor(m) : null;
        const v = new Variant(m, kind, this.group, kind === 'tree' ? 900 : 1500, kind === 'tree' ? 9000 : 0, tex, wind, keys[gi] + mi);
        vIndex[keys[gi]].push(this.variants.length); this.variants.push(v);
      });
    });

    // scatter
    const rng = new RNG(seed + 99);
    const step = 8.5 / Math.sqrt(density);
    for (let z = -HALF_WORLD + 4; z < HALF_WORLD - 4; z += step) for (let x = -HALF_WORLD + 4; x < HALF_WORLD - 4; x += step) {
      const jx = x + (hash2(x * 7, z * 3, seed) - 0.5) * step * 0.9, jz = z + (hash2(x * 5, z * 11, seed) - 0.5) * step * 0.9;
      const f = terrain.forestDensity(jx, jz);
      const r = hash2(jx * 13, jz * 17, seed + 1);
      if (blocked(jx, jz)) continue;
      if (Math.hypot(jx - LAKE.x, jz - LAKE.z) < LAKE.r + 4) continue;
      const sp = terrain.splatAt(jx, jz); if (sp[0] > 40 || sp[1] > 160) continue;
      const slope = terrain.slopeAt(jx, jz);
      const inTown = jx > TOWN.x0 && jx < TOWN.x1 && jz > TOWN.z0 && jz < TOWN.z1;
      const y = terrain.heightAt(jx, jz);
      if (r < f * 0.92 && slope < 0.45) {
        const pick = rng.next();
        const set = slope > 0.25 || jx > 150 ? (pick < 0.7 ? 'fir' : 'pine') : pick < 0.4 ? 'pine' : pick < 0.75 ? 'fir' : pick < 0.93 ? 'birch' : 'dead';
        const vi = rng.pick(vIndex[set]);
        this.insts.push({ x: jx, y, z: jz, s: rng.range(0.75, 1.25), r: rng.range(0, Math.PI * 2), v: vi, kind: 'tree', alive: true, hp: 100, idx: this.insts.length });
      } else if (r < f * 1.6 + 0.08 && rng.chance(0.55)) {
        const vi = rng.pick(vIndex.bush);
        this.insts.push({ x: jx, y, z: jz, s: rng.range(0.7, 1.4), r: rng.range(0, 6.28), v: vi, kind: 'bush', alive: true, hp: 20, idx: this.insts.length });
      } else if (!inTown && rng.chance(0.035 + slope * 0.4)) {
        const vi = rng.pick(vIndex.rock);
        this.insts.push({ x: jx, y: y - 0.2, z: jz, s: rng.range(0.5, 1.15), r: rng.range(0, 6.28), v: vi, kind: 'rock', alive: true, hp: 200, idx: this.insts.length });
      } else if (f > 0.5 && rng.chance(0.03)) {
        const vi = rng.pick(vIndex.log);
        this.insts.push({ x: jx, y, z: jz, s: rng.range(0.8, 1.2), r: rng.range(0, 6.28), v: vi, kind: 'log', alive: true, hp: 60, idx: this.insts.length });
      }
    }
    for (const t of this.insts) {
      const k = this.cellKey(t.x, t.z); let a = this.cells.get(k); if (!a) { a = []; this.cells.set(k, a); } a.push(t);
      const m = this.variants[t.v].model;
      if (t.kind === 'tree') t.col = col.addCircle(t.x, t.z, Math.max(0.25, m.trunkRadius * t.s), 30, t);
      else if (t.kind === 'rock') t.col = col.addCircle(t.x, t.z, 0.9 * t.s, 2.5 * t.s, t);
    }
    this.buildGrass();
  }

  private cellKey(x: number, z: number) { return Math.floor((x + HALF_WORLD) / 32) * 1000 + Math.floor((z + HALF_WORLD) / 32); }

  private renderImpostor(m: TreeModel): THREE.Texture {
    const W = 128, H = 256;
    const rt = new THREE.WebGLRenderTarget(W, H, { colorSpace: THREE.SRGBColorSpace } as any);
    const scene = new THREE.Scene();
    scene.add(new THREE.AmbientLight(0xffffff, 1.6));
    const dl = new THREE.DirectionalLight(0xffffff, 1.2); dl.position.set(0.3, 1, 1); scene.add(dl);
    for (const p of m.parts) scene.add(new THREE.Mesh(p.geometry, p.material));
    const h = m.height, w = h * 0.55;
    const cam = new THREE.OrthographicCamera(-w / 2, w / 2, h, 0, -50, 50);
    cam.position.set(0, 0, 10); cam.lookAt(0, 0, 0);
    const r = this.renderer;
    const prevT = r.toneMapping; const prevC = r.getClearColor(new THREE.Color()); const prevA = r.getClearAlpha();
    r.toneMapping = THREE.NoToneMapping;
    r.setRenderTarget(rt); r.setClearColor(0x000000, 0); r.clear(); r.render(scene, cam);
    r.setRenderTarget(null); r.setClearColor(prevC, prevA); r.toneMapping = prevT;
    rt.texture.colorSpace = THREE.SRGBColorSpace;
    return rt.texture;
  }

  /** rebuild near/far instance buffers around the player */
  update(pos: THREE.Vector3, time: number, wind: number, far: number) {
    windUniforms.uTime.value = time; windUniforms.uWind.value = wind;
    if (this.grassMat?.userData.shader) {
      const u = this.grassMat.userData.shader.uniforms; u.uTime.value = time; u.uWind.value = wind; u.uCam.value.copy(pos);
    }
    if (Math.hypot(pos.x - this.lastCenter.x, pos.z - this.lastCenter.y) < 6) return;
    this.lastCenter.set(pos.x, pos.z);
    this.rebuild(pos, far);
  }
  forceRebuild(pos: THREE.Vector3, far: number) { this.rebuild(pos, far); }
  private rebuild(pos: THREE.Vector3, far: number) {
    for (const v of this.variants) { for (const n of v.near) n.count = 0; if (v.far) v.far.count = 0; }
    const near2 = this.nearDist * this.nearDist, far2 = far * far, bushFar2 = 120 * 120;
    for (const t of this.insts) {
      if (!t.alive) continue;
      const dx = t.x - pos.x, dz = t.z - pos.z; const d2 = dx * dx + dz * dz;
      const v = this.variants[t.v];
      this.tmpQ.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, t.r);
      this.tmpS.setScalar(t.s); this.tmpP.set(t.x, t.y, t.z);
      this.tmpM.compose(this.tmpP, this.tmpQ, this.tmpS);
      if (t.kind === 'tree') {
        if (d2 < near2) { for (const n of v.near) { if (n.count < n.instanceMatrix.count) n.setMatrixAt(n.count++, this.tmpM); } }
        else if (d2 < far2 && v.far && v.far.count < v.far.instanceMatrix.count) {
          // impostor faces are fixed; rotate randomly is fine
          v.far.setMatrixAt(v.far.count++, this.tmpM);
        }
      } else {
        const lim = t.kind === 'bush' ? bushFar2 : far2 * 0.5;
        if (d2 < lim) for (const n of v.near) { if (n.count < n.instanceMatrix.count) n.setMatrixAt(n.count++, this.tmpM); }
      }
    }
    for (const v of this.variants) { for (const n of v.near) n.instanceMatrix.needsUpdate = true; if (v.far) v.far.instanceMatrix.needsUpdate = true; }
  }

  /** nearest live vegetation instance of kind within radius of point */
  nearest(x: number, z: number, r: number, kinds: TreeInst['kind'][]) {
    let best: TreeInst | null = null, bd = r * r;
    const cx = Math.floor((x + HALF_WORLD) / 32), cz = Math.floor((z + HALF_WORLD) / 32);
    for (let i = cx - 1; i <= cx + 1; i++) for (let j = cz - 1; j <= cz + 1; j++) {
      const a = this.cells.get(i * 1000 + j); if (!a) continue;
      for (const t of a) { if (!t.alive || !kinds.includes(t.kind)) continue; const d = (t.x - x) ** 2 + (t.z - z) ** 2; if (d < bd) { bd = d; best = t; } }
    }
    return best;
  }
  kill(t: TreeInst, pos: THREE.Vector3) {
    t.alive = false; this.removed.add(t.idx);
    if (t.col) this.col.removeCircle(t.col);
    this.rebuild(pos, this.farDist);
  }
  farDist = 600;
  setFar(f: number) { this.farDist = f; }
  /** temporary falling-tree animation mesh */
  fallingTree(t: TreeInst, dirX: number, dirZ: number, scene: THREE.Scene): (dt: number) => boolean {
    const g = new THREE.Group();
    for (const p of this.variants[t.v].model.parts) { const m = new THREE.Mesh(p.geometry, p.material); m.castShadow = true; g.add(m); }
    g.position.set(t.x, t.y, t.z); g.scale.setScalar(t.s); g.rotation.y = t.r;
    const pivot = new THREE.Group(); pivot.position.set(t.x, t.y, t.z); scene.add(pivot);
    g.position.set(0, 0, 0); pivot.add(g);
    const axis = new THREE.Vector3(dirZ, 0, -dirX).normalize();
    let ang = 0, vel = 0.05, life = 0;
    return (dt: number) => {
      life += dt;
      if (ang < Math.PI / 2 - 0.05) { vel += dt * 1.6; ang = Math.min(Math.PI / 2 - 0.05, ang + vel * dt); pivot.quaternion.setFromAxisAngle(axis, ang); }
      if (life > 6) { pivot.position.y -= dt * 0.5; }
      if (life > 9) { scene.remove(pivot); return false; }
      return true;
    };
  }

  private buildGrass() {
    const S = 70; // patch size (m)
    const count = Math.floor(70000 * this.grassDensity);
    // blade: 3 segments tapered
    const bw = 0.12, bh = 0.8;
    const pos = [-bw / 2, 0, 0, bw / 2, 0, 0, -bw * 0.38, bh * 0.4, 0.0, bw * 0.38, bh * 0.4, 0.0, -bw * 0.2, bh * 0.75, 0.0, bw * 0.2, bh * 0.75, 0.0, 0, bh, 0];
    const idx = [0, 1, 2, 1, 3, 2, 2, 3, 4, 3, 5, 4, 4, 5, 6];
    const base = new THREE.BufferGeometry();
    base.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    base.setAttribute('normal', new THREE.Float32BufferAttribute(new Array(7).fill(0).flatMap(() => [0, 1, 0]), 3));
    base.setAttribute('uv', new THREE.Float32BufferAttribute([0, 0, 1, 0, 0, 0.4, 1, 0.4, 0, 0.75, 1, 0.75, 0.5, 1], 2));
    base.setIndex(idx);
    const g = new THREE.InstancedBufferGeometry();
    g.index = base.index; g.attributes.position = base.attributes.position; g.attributes.normal = base.attributes.normal; g.attributes.uv = base.attributes.uv;
    const off = new Float32Array(count * 4);
    for (let i = 0; i < count; i++) { off[i * 4] = Math.random() * S; off[i * 4 + 1] = Math.random() * S; off[i * 4 + 2] = Math.random(); off[i * 4 + 3] = Math.random(); }
    g.setAttribute('aOff', new THREE.InstancedBufferAttribute(off, 4));
    g.instanceCount = count;
    // terrain height texture
    const N = T_N + 1;
    const ht = new THREE.DataTexture(this.terrain.heights, N, N, THREE.RedFormat, THREE.FloatType);
    ht.needsUpdate = true; ht.magFilter = THREE.NearestFilter; ht.minFilter = THREE.NearestFilter;
    const mat = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.85, side: THREE.DoubleSide });
    mat.onBeforeCompile = (sh) => {
      sh.uniforms.uTime = { value: 0 }; sh.uniforms.uWind = { value: 0.3 }; sh.uniforms.uCam = { value: new THREE.Vector3() };
      sh.uniforms.tHeight = { value: ht }; sh.uniforms.tSplat = { value: this.terrain.splat };
      sh.uniforms.uPush = { value: new THREE.Vector3() };
      mat.userData.shader = sh;
      sh.vertexShader = sh.vertexShader
        .replace('#include <common>', `#include <common>
attribute vec4 aOff; uniform float uTime, uWind; uniform vec3 uCam; uniform sampler2D tHeight; uniform sampler2D tSplat;
varying float vH; varying float vShade; varying vec3 vGCol;
float hAt(vec2 p){ vec2 f = (p + ${HALF_WORLD.toFixed(1)}) / ${T_RES.toFixed(2)}; vec2 i = floor(f); vec2 u = f - i;
  float h00 = texture2D(tHeight, (i + 0.5) / ${N.toFixed(1)}).r; float h10 = texture2D(tHeight, (i + vec2(1.5,0.5)) / ${N.toFixed(1)}).r;
  float h01 = texture2D(tHeight, (i + vec2(0.5,1.5)) / ${N.toFixed(1)}).r; float h11 = texture2D(tHeight, (i + 1.5) / ${N.toFixed(1)}).r;
  if (u.x + u.y < 1.0) return h00 + (h10-h00)*u.x + (h01-h00)*u.y; return h11 + (h01-h11)*(1.0-u.x) + (h10-h11)*(1.0-u.y); }`)
        .replace('#include <begin_vertex>', `
vec2 wp = mod(aOff.xy - uCam.xz + ${(S / 2).toFixed(1)}, ${S.toFixed(1)}) - ${(S / 2).toFixed(1)} + uCam.xz;
vec4 sp = texture2D(tSplat, (wp + ${HALF_WORLD.toFixed(1)}) / ${WORLD_SIZE.toFixed(1)});
float dist = length(wp - uCam.xz);
float mask = (1.0 - sp.r) * (1.0 - smoothstep(0.25, 0.6, sp.g)) * (1.0 - sp.b * 0.75) * (1.0 - sp.a);
float fade = 1.0 - smoothstep(${(S * 0.3).toFixed(1)}, ${(S * 0.5).toFixed(1)}, dist);
float hgt = (0.22 + aOff.z * 0.3) * mask * fade * step(0.15, mask) * smoothstep(0.8, 2.5, dist);
float ang = aOff.w * 6.2831;
vec3 transformed = vec3(position.x * (0.7 + aOff.z*0.6), position.y * hgt, 0.0);
float ca = cos(ang), sa = sin(ang);
transformed = vec3(transformed.x * ca, transformed.y, transformed.x * sa);
float t = position.y; float bend = t * t;
float ph = wp.x * 0.35 + wp.y * 0.27;
vec2 wind = vec2(sin(uTime * 1.6 + ph) + 0.5 * sin(uTime * 3.7 + ph * 1.7), cos(uTime * 1.3 + ph * 0.8)) * (0.12 + uWind * 0.35);
// lean away from the player
vec2 away = wp - uCam.xz; float ad = length(away); vec2 push = ad < 1.4 ? normalize(away + 0.001) * (1.4 - ad) * 0.6 : vec2(0.0);
transformed.xz += (wind + push + vec2(aOff.z - 0.5, aOff.w - 0.5) * 0.3) * bend * hgt;
transformed.y -= length(push) * bend * hgt * 0.4;
transformed += vec3(wp.x, hAt(wp), wp.y);
vH = t; vShade = aOff.z;
vGCol = mix(vec3(0.27,0.36,0.15), vec3(0.40,0.42,0.19), step(0.75, aOff.z + sp.g * 0.4)) * (0.92 + aOff.w * 0.12);
`)
        .replace('#include <beginnormal_vertex>', '#include <beginnormal_vertex>\nobjectNormal = vec3(0.0, 1.0, 0.0);');
      sh.fragmentShader = sh.fragmentShader
        .replace('#include <common>', '#include <common>\nvarying float vH; varying float vShade; varying vec3 vGCol;')
        .replace('#include <map_fragment>', `#include <map_fragment>
diffuseColor.rgb *= mix(vGCol * 0.95, vGCol * 1.18, smoothstep(0.0, 0.7, vH));`);
    };
    mat.customProgramCacheKey = () => 'grass-v1';
    this.grassMat = mat;
    this.grass = new THREE.Mesh(g, mat);
    this.grass.frustumCulled = false; this.grass.receiveShadow = true; this.grass.castShadow = false;
    this.group.add(this.grass);
  }
}
export { clamp };
