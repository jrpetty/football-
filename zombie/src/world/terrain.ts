// Heightfield terrain with splat-mapped PBR shading (grass / dirt / rock / forest floor / asphalt / sand).
import * as THREE from 'three';
import { HALF_WORLD, WORLD_SIZE, Simplex, smoothstep, clamp, lerp } from '../core/util';
import { Layout, TOWN, LAKE, distToSeg } from './layout';
import { getCloudNoiseTexture } from '../render/textures';
import { facet } from '../render/materials';

export const T_RES = 2.5;
export const T_N = WORLD_SIZE / T_RES; // segments per side
const SPLAT_N = 1024;

export class Terrain {
  heights = new Float32Array((T_N + 1) * (T_N + 1));
  splat!: THREE.DataTexture;      // R road, G dirt, B forest floor, A sand/mud
  splatData = new Uint8Array(SPLAT_N * SPLAT_N * 4);
  forestDensity!: (x: number, z: number) => number;
  group = new THREE.Group();
  material!: THREE.MeshStandardMaterial;
  private noise: Simplex;
  private noise2: Simplex;

  constructor(public layout: Layout, seed: number) {
    this.noise = new Simplex(seed);
    this.noise2 = new Simplex(seed + 17);
    this.generate();
  }

  private rawHeight(x: number, z: number) {
    const n = this.noise;
    let h = n.fbm(x * 0.0035, z * 0.0035, 5) * 22;
    h += n.fbm(x * 0.012 + 50, z * 0.012, 3) * 4;
    // eastern / north-eastern highlands with ridges (quarry & ore country)
    const east = smoothstep(120, 330, x * 0.8 + z * 0.4);
    const ridge = 1 - Math.abs(this.noise2.fbm(x * 0.006, z * 0.006, 4));
    h += east * (ridge * ridge * 55 + 10);
    // western rolling hills
    const west = smoothstep(-180, -380, x);
    h += west * Math.abs(this.noise2.fbm(x * 0.01 + 9, z * 0.01, 3)) * 18;
    // world-edge mountains (natural boundary)
    const edge = Math.max(Math.abs(x), Math.abs(z));
    h += smoothstep(330, 400, edge) * 60;
    return h;
  }

  private generate() {
    const N = T_N + 1;
    const roads = this.layout.roads;
    const townBlend = (x: number, z: number) => {
      const dx = Math.max(TOWN.x0 - x, 0, x - TOWN.x1), dz = Math.max(TOWN.z0 - z, 0, z - TOWN.z1);
      return smoothstep(0, 60, Math.sqrt(dx * dx + dz * dz));
    };
    const plotH = this.layout.plots.map(p => lerp(TOWN.h, this.rawHeight(p.x, p.z), townBlend(p.x, p.z)));
    for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
      const x = -HALF_WORLD + i * T_RES, z = -HALF_WORLD + j * T_RES;
      let h = this.rawHeight(x, z);
      // town plateau
      h = lerp(TOWN.h, h, townBlend(x, z));
      // building plots outside town get flattened locally
      for (let pi = 0; pi < this.layout.plots.length; pi++) {
        const p = this.layout.plots[pi];
        const px = Math.max(Math.abs(x - p.x) - p.w / 2 - 3, 0), pz = Math.max(Math.abs(z - p.z) - p.d / 2 - 3, 0);
        if (px > 18 || pz > 18) continue;
        const dp = Math.sqrt(px * px + pz * pz);
        if (dp < 18) h = lerp(plotH[pi], h, smoothstep(0, 18, dp));
      }
      // lake bowl
      const dl = Math.hypot(x - LAKE.x, z - LAKE.z);
      if (dl < LAKE.r + 40) {
        const bowl = LAKE.level - 5 * (1 - smoothstep(0, LAKE.r, dl)) - 0.6;
        h = lerp(Math.min(h, bowl), h, smoothstep(LAKE.r - 6, LAKE.r + 40, dl));
      }
      this.heights[j * N + i] = h;
    }
    // roads: flatten toward a smoothed profile
    const smooth = (x: number, z: number) => this.sampleGrid(x, z);
    const roadH: number[] = [];
    for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
      const x = -HALF_WORLD + i * T_RES, z = -HALF_WORLD + j * T_RES;
      let best = Infinity, br = -1;
      for (let r = 0; r < roads.length; r++) { const d = distToSeg(x, z, roads[r]) - roads[r].w / 2; if (d < best) { best = d; br = r; } }
      if (best < 10) {
        // profile height = average of the terrain at a few points along the road around this location
        const r = roads[br]; const dxr = r.bx - r.ax, dzr = r.bz - r.az; const L = Math.hypot(dxr, dzr);
        const t = clamp(((x - r.ax) * dxr + (z - r.az) * dzr) / (L * L), 0, 1);
        const cx = r.ax + dxr * t, cz = r.az + dzr * t;
        let s = 0, c = 0;
        for (let k = -3; k <= 3; k++) { const tt = clamp(t + k * 6 / L, 0, 1); s += smooth(r.ax + dxr * tt, r.az + dzr * tt); c++; }
        const target = s / c + (r.kind === 'dirt' ? 0 : 0.08);
        void cx; void cz;
        roadH.push(j * N + i, lerp(target, this.heights[j * N + i], smoothstep(0, 9, best)));
      }
    }
    for (let k = 0; k < roadH.length; k += 2) this.heights[roadH[k]] = roadH[k + 1];

    // forest density
    this.forestDensity = (x: number, z: number) => {
      let f = this.noise2.fbm(x * 0.008 + 100, z * 0.008 - 40, 3) * 0.5 + 0.55;
      const dx = Math.max(TOWN.x0 - x, 0, x - TOWN.x1), dz = Math.max(TOWN.z0 - z, 0, z - TOWN.z1);
      f *= smoothstep(10, 50, Math.sqrt(dx * dx + dz * dz));
      return clamp(f, 0, 1);
    };
    this.paintSplat();
  }

  private sampleGrid(x: number, z: number) {
    const N = T_N + 1;
    const fx = clamp((x + HALF_WORLD) / T_RES, 0, T_N - 0.0001), fz = clamp((z + HALF_WORLD) / T_RES, 0, T_N - 0.0001);
    const i = Math.floor(fx), j = Math.floor(fz), u = fx - i, v = fz - j;
    const h00 = this.heights[j * N + i], h10 = this.heights[j * N + i + 1], h01 = this.heights[(j + 1) * N + i], h11 = this.heights[(j + 1) * N + i + 1];
    return lerp(lerp(h00, h10, u), lerp(h01, h11, u), v);
  }

  /** Exact height matching mesh triangulation. */
  heightAt(x: number, z: number) {
    const N = T_N + 1;
    const fx = clamp((x + HALF_WORLD) / T_RES, 0, T_N - 0.0001), fz = clamp((z + HALF_WORLD) / T_RES, 0, T_N - 0.0001);
    const i = Math.floor(fx), j = Math.floor(fz), u = fx - i, v = fz - j;
    const h00 = this.heights[j * N + i], h10 = this.heights[j * N + i + 1], h01 = this.heights[(j + 1) * N + i], h11 = this.heights[(j + 1) * N + i + 1];
    // triangles: (00,01,10) and (10,01,11)
    if (u + v < 1) return h00 + (h10 - h00) * u + (h01 - h00) * v;
    return h11 + (h01 - h11) * (1 - u) + (h10 - h11) * (1 - v);
  }
  normalAt(x: number, z: number, out = new THREE.Vector3()) {
    const e = 1.0;
    return out.set(this.heightAt(x - e, z) - this.heightAt(x + e, z), 2 * e, this.heightAt(x, z - e) - this.heightAt(x, z + e)).normalize();
  }
  slopeAt(x: number, z: number) { return 1 - this.normalAt(x, z).y; }
  splatAt(x: number, z: number) {
    const i = clamp(Math.floor((x + HALF_WORLD) / WORLD_SIZE * SPLAT_N), 0, SPLAT_N - 1), j = clamp(Math.floor((z + HALF_WORLD) / WORLD_SIZE * SPLAT_N), 0, SPLAT_N - 1);
    const o = (j * SPLAT_N + i) * 4; return [this.splatData[o], this.splatData[o + 1], this.splatData[o + 2], this.splatData[o + 3]];
  }
  isRoad(x: number, z: number) { return this.splatAt(x, z)[0] > 100; }

  private paintSplat() {
    const d = this.splatData; const roads = this.layout.roads;
    const px = WORLD_SIZE / SPLAT_N;
    for (let j = 0; j < SPLAT_N; j++) for (let i = 0; i < SPLAT_N; i++) {
      const x = -HALF_WORLD + (i + 0.5) * px, z = -HALF_WORLD + (j + 0.5) * px;
      let road = 0, dirt = 0;
      for (const r of roads) {
        if (x < Math.min(r.ax, r.bx) - 12 || x > Math.max(r.ax, r.bx) + 12 || z < Math.min(r.az, r.bz) - 12 || z > Math.max(r.az, r.bz) + 12) continue;
        const dd = distToSeg(x, z, r);
        if (r.kind === 'dirt') { dirt = Math.max(dirt, 1 - smoothstep(r.w / 2 - 1.2, r.w / 2 + 1.2, dd + this.noise.noise(x * 0.3, z * 0.3) * 0.6)); }
        else {
          road = Math.max(road, 1 - smoothstep(r.w / 2 - 0.3, r.w / 2 + 0.3, dd));
          dirt = Math.max(dirt, (1 - smoothstep(r.w / 2, r.w / 2 + 2.5, dd + this.noise.noise(x * 0.4, z * 0.4))) * 0.6);
        }
      }
      // inside town: sidewalks / yards get worn dirt patches
      const wear = this.noise2.fbm(x * 0.05, z * 0.05, 3);
      dirt = Math.max(dirt, smoothstep(0.25, 0.6, wear) * 0.7);
      const forest = this.forestDensity(x, z);
      let floor = smoothstep(0.45, 0.8, forest + this.noise.noise(x * 0.04, z * 0.04) * 0.15);
      // lake shore sand/mud
      const dl = Math.hypot(x - LAKE.x, z - LAKE.z);
      const sand = 1 - smoothstep(LAKE.r - 2, LAKE.r + 8 + this.noise.noise(x * 0.1, z * 0.1) * 4, dl);
      const o = (j * SPLAT_N + i) * 4;
      d[o] = road * 255; d[o + 1] = dirt * 255; d[o + 2] = floor * 255; d[o + 3] = sand * 255;
    }
    for (const p of this.layout.plots) {
      const i0 = Math.floor((p.x - p.w / 2 - 1.5 + HALF_WORLD) / px), i1 = Math.ceil((p.x + p.w / 2 + 1.5 + HALF_WORLD) / px);
      const j0 = Math.floor((p.z - p.d / 2 - 1.5 + HALF_WORLD) / px), j1 = Math.ceil((p.z + p.d / 2 + 1.5 + HALF_WORLD) / px);
      for (let j = Math.max(0, j0); j < Math.min(SPLAT_N, j1); j++) for (let i = Math.max(0, i0); i < Math.min(SPLAT_N, i1); i++) {
        const o = (j * SPLAT_N + i) * 4; d[o + 1] = Math.max(d[o + 1], 215); d[o + 2] = 0;
      }
    }
    this.splat = new THREE.DataTexture(d, SPLAT_N, SPLAT_N, THREE.RGBAFormat);
    this.splat.magFilter = THREE.LinearFilter; this.splat.minFilter = THREE.LinearMipmapLinearFilter; this.splat.generateMipmaps = true;
    this.splat.needsUpdate = true;
  }

  /** Paint dirt where the player builds (foundations wear grass away). */
  paintDirt(x: number, z: number, r: number) {
    const px = WORLD_SIZE / SPLAT_N; const ci = Math.floor((x + HALF_WORLD) / px), cj = Math.floor((z + HALF_WORLD) / px); const R = Math.ceil(r / px);
    for (let j = cj - R; j <= cj + R; j++) for (let i = ci - R; i <= ci + R; i++) {
      if (i < 0 || j < 0 || i >= SPLAT_N || j >= SPLAT_N) continue;
      const dd = Math.hypot(i - ci, j - cj) / R; if (dd > 1) continue;
      const o = (j * SPLAT_N + i) * 4; this.splatData[o + 1] = Math.max(this.splatData[o + 1], (1 - dd) * 255); this.splatData[o + 2] = 0;
    }
    this.splat.needsUpdate = true;
  }

  buildMesh() {
    const CH = 16; // chunks per side
    const per = T_N / CH;
    const N = T_N + 1;
    this.material = facet(this.makeMaterial());
    for (let cz = 0; cz < CH; cz++) for (let cx = 0; cx < CH; cx++) {
      const verts = (per + 1) * (per + 1);
      const pos = new Float32Array(verts * 3), nor = new Float32Array(verts * 3);
      let k = 0;
      for (let j = 0; j <= per; j++) for (let i = 0; i <= per; i++) {
        const gi = cx * per + i, gj = cz * per + j;
        const x = -HALF_WORLD + gi * T_RES, z = -HALF_WORLD + gj * T_RES;
        const h = this.heights[gj * N + gi];
        pos[k * 3] = x; pos[k * 3 + 1] = h; pos[k * 3 + 2] = z;
        const hl = this.heights[gj * N + Math.max(0, gi - 1)], hr = this.heights[gj * N + Math.min(T_N, gi + 1)];
        const hd = this.heights[Math.max(0, gj - 1) * N + gi], hu = this.heights[Math.min(T_N, gj + 1) * N + gi];
        const nx = hl - hr, ny = 2 * T_RES, nz = hd - hu; const l = Math.hypot(nx, ny, nz);
        nor[k * 3] = nx / l; nor[k * 3 + 1] = ny / l; nor[k * 3 + 2] = nz / l;
        k++;
      }
      const idx: number[] = [];
      for (let j = 0; j < per; j++) for (let i = 0; i < per; i++) {
        const a = j * (per + 1) + i, b = a + 1, c = a + (per + 1), d = c + 1;
        idx.push(a, c, b, b, c, d);
      }
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
      g.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
      g.setIndex(idx);
      g.computeBoundingSphere(); g.computeBoundingBox();
      const m = new THREE.Mesh(g, this.material);
      m.receiveShadow = true; m.castShadow = true;
      m.matrixAutoUpdate = false; m.updateMatrix();
      this.group.add(m);
    }
    return this.group;
  }

  private makeMaterial() {
    // Low-poly look: faceted (flat-shaded) terrain painted with flat colours from the splat map.
    const mat = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.95, metalness: 0, flatShading: true });
    const uniforms: Record<string, THREE.IUniform> = {
      tSplat: { value: this.splat },
      tNoise: { value: getCloudNoiseTexture() },
      uWet: { value: 0 },
      uSnow: { value: 0 },
    };
    mat.userData.uniforms = uniforms;
    mat.onBeforeCompile = (sh) => {
      Object.assign(sh.uniforms, uniforms);
      sh.vertexShader = sh.vertexShader
        .replace('#include <common>', '#include <common>\nvarying vec3 vWPos;')
        .replace('#include <worldpos_vertex>', '#include <worldpos_vertex>\nvWPos = (modelMatrix * vec4(transformed,1.0)).xyz;');
      sh.fragmentShader = sh.fragmentShader
        .replace('#include <common>', `#include <common>
varying vec3 vWPos;
uniform sampler2D tSplat; uniform sampler2D tNoise; uniform float uWet; uniform float uSnow;
`)
        .replace('#include <map_fragment>', `
{
  vec3 fn = normalize(cross(dFdx(vWPos), dFdy(vWPos)));
  if (fn.y < 0.0) fn = -fn;
  vec2 suv = (vWPos.xz + ${HALF_WORLD.toFixed(1)}) / ${WORLD_SIZE.toFixed(1)};
  vec4 sp = texture2D(tSplat, suv);
  float macro = texture2D(tNoise, vWPos.xz * 0.003).r;
  float macro2 = texture2D(tNoise, vWPos.xz * 0.017 + 0.37).r;
  float slope = 1.0 - clamp(fn.y, 0.0, 1.0);
  float rockW = smoothstep(0.2, 0.3, slope + (macro2 - 0.5) * 0.12);
  vec3 grassA = vec3(0.27, 0.36, 0.16), grassB = vec3(0.38, 0.40, 0.19);
  vec3 alb = mix(grassA, grassB, smoothstep(0.45, 0.7, macro));
  alb = mix(alb, vec3(0.30, 0.25, 0.17), smoothstep(0.3, 0.6, sp.b));            // forest floor
  alb = mix(alb, vec3(0.40, 0.32, 0.23), smoothstep(0.45, 0.7, sp.g + (macro2 - 0.5) * 0.3)); // dirt
  alb = mix(alb, vec3(0.62, 0.56, 0.42), smoothstep(0.3, 0.6, sp.a));            // sand
  alb = mix(alb, vec3(0.50, 0.49, 0.46) * mix(0.9, 1.08, macro2), rockW);          // rock faces
  alb = mix(alb, vec3(0.22, 0.225, 0.235), smoothstep(0.4, 0.6, sp.r));          // asphalt
  alb *= mix(0.92, 1.06, macro);
  alb *= mix(1.0, 0.62, uWet * (1.0 - rockW * 0.3));
  alb = mix(alb, vec3(0.9, 0.92, 0.95), uSnow * smoothstep(0.3, 0.0, slope));
  diffuseColor.rgb *= alb;
}
`)
        .replace('#include <roughnessmap_fragment>', `float roughnessFactor = mix(roughness, 0.35, uWet);`);
    };
    mat.customProgramCacheKey = () => 'terrain-lowpoly-v1';
    return mat;
  }
}
