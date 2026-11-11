// World orchestrator: terrain, town, vegetation, ore deposits, water, ground queries and loose item drops.
import * as THREE from 'three';
import { Water } from 'three/examples/jsm/objects/Water.js';
import { RNG, snapGrid, HALF_WORLD } from '../core/util';
import { buildLayout, Layout, LAKE } from './layout';
import { Terrain } from './terrain';
import { CollisionWorld } from './collision';
import { Town } from './town';
import { Vegetation } from './vegetation';
import { getMaterial, getTintedMaterial } from '../render/materials';
import { getWaterNormalTexture } from '../render/textures';
import { Inventory } from '../items/inventory';
import type { QualityCfg } from '../render/renderer';

export interface OreNode { id: number; type: 'iron_ore' | 'copper_ore' | 'coal' | 'stone' | 'sulfur'; x: number; z: number; y: number; purity: number; mesh: THREE.Object3D; occupied: boolean; manualLeft: number }
export interface Floor { x0: number; z0: number; x1: number; z1: number; y: number; ref?: any; ramp?: { axis: 'x' | 'z'; dir: number; y0: number; y1: number } }
export interface Drop { id: number; pos: THREE.Vector3; inv: Inventory; mesh: THREE.Object3D; age: number }

const ORE_COL: Record<OreNode['type'], number> = { iron_ore: 0x9a4a32, copper_ore: 0xc0703a, coal: 0x1c1c1e, stone: 0xb9b2a2, sulfur: 0xd8c33a };

export class World {
  layout: Layout;
  terrain: Terrain;
  col = new CollisionWorld();
  town: Town;
  veg: Vegetation;
  ores: OreNode[] = [];
  water: Water | null = null;
  floors: Floor[] = [];
  drops: Drop[] = [];
  group = new THREE.Group();
  private dropId = 1;

  constructor(public scene: THREE.Scene, renderer: THREE.WebGLRenderer, public seed: number, cfg: QualityCfg, progress: (p: number, l: string) => void) {
    this.layout = buildLayout(seed);
    // snap ore nodes to grid tile centres so 3x3 miners sit exactly on them
    for (const o of this.layout.ores) { o.x = snapGrid(o.x); o.z = snapGrid(o.z); }
    progress(0.05, 'Shaping terrain');
    this.terrain = new Terrain(this.layout, seed);
    this.group.add(this.terrain.buildMesh());
    progress(0.3, 'Building the town');
    this.town = new Town(this.layout, this.terrain, this.col, seed);
    this.group.add(this.town.group);
    for (const b of this.town.buildings) this.floors.push({ x0: b.x - b.w / 2, z0: b.z - b.d / 2, x1: b.x + b.w / 2, z1: b.z + b.d / 2, y: b.floorY, ref: b });
    progress(0.55, 'Growing forests');
    this.veg = new Vegetation(this.terrain, this.col, renderer, seed, cfg.trees, cfg.grass, (x, z) => this.town.isOccupied(x, z, 2) || this.layout.ores.some(o => Math.hypot(o.x - x, o.z - z) < 12));
    this.veg.setFar(cfg.far);
    this.group.add(this.veg.group);
    progress(0.75, 'Placing ore deposits');
    this.buildOres();
    this.buildWater();
    scene.add(this.group);
  }

  private buildOres() {
    const rng = new RNG(this.seed + 5);
    let id = 1;
    for (const o of this.layout.ores) {
      const y = this.terrain.heightAt(o.x, o.z);
      const g = new THREE.Group();
      const rockMat = getMaterial('rock');
      const oreMat = getTintedMaterial('rock', ORE_COL[o.type]);
      const crystalMat = new THREE.MeshStandardMaterial({ color: ORE_COL[o.type], roughness: o.type === 'coal' ? 0.6 : 0.35, metalness: o.type === 'iron_ore' || o.type === 'copper_ore' ? 0.6 : 0.1, emissive: o.type === 'sulfur' ? 0x332a00 : 0x000000 });
      for (let i = 0; i < 9; i++) {
        const geo = new THREE.IcosahedronGeometry(1, 1);
        const p = geo.attributes.position as THREE.BufferAttribute;
        for (let k = 0; k < p.count; k++) { const v = new THREE.Vector3().fromBufferAttribute(p, k); v.multiplyScalar(0.75 + rng.next() * 0.45); p.setXYZ(k, v.x, v.y * 0.7, v.z); }
        geo.computeVertexNormals();
        const m = new THREE.Mesh(geo, i < 4 ? rockMat : oreMat);
        const a = rng.next() * Math.PI * 2, r = i === 0 ? 0 : rng.range(0.8, 2.6);
        m.position.set(Math.cos(a) * r, -0.2, Math.sin(a) * r); m.scale.setScalar(i === 0 ? 1.6 : rng.range(0.6, 1.2)); m.rotation.set(rng.next(), rng.next() * 6, rng.next());
        m.castShadow = true; m.receiveShadow = true; g.add(m);
      }
      for (let i = 0; i < 10; i++) {
        const c = new THREE.Mesh(new THREE.OctahedronGeometry(0.3, 0), crystalMat);
        const a = rng.next() * Math.PI * 2, r = rng.range(0.3, 2.4);
        c.position.set(Math.cos(a) * r, rng.range(0.1, 0.9), Math.sin(a) * r); c.scale.set(0.6, rng.range(1, 2.2), 0.6); c.rotation.set(rng.range(-0.5, 0.5), rng.next() * 6, rng.range(-0.5, 0.5));
        c.castShadow = true; g.add(c);
      }
      g.position.set(o.x, y, o.z);
      this.group.add(g);
      const node: OreNode = { id: id++, type: o.type, x: o.x, z: o.z, y, purity: o.purity, mesh: g, occupied: false, manualLeft: 1e9 };
      this.ores.push(node);
      this.col.addCircle(o.x, o.z, 1.3, 1.6, node);
    }
  }

  private buildWater() {
    const geo = new THREE.CircleGeometry(LAKE.r + 14, 64);
    const water = new Water(geo, {
      textureWidth: 512, textureHeight: 512, waterNormals: getWaterNormalTexture(),
      sunDirection: new THREE.Vector3(0, 1, 0), sunColor: 0xffffff, waterColor: 0x0d1f22, distortionScale: 2.4, fog: true,
    });
    water.rotation.x = -Math.PI / 2;
    water.position.set(LAKE.x, LAKE.level, LAKE.z);
    (water.material as THREE.ShaderMaterial).uniforms.size.value = 3;
    this.water = water;
    this.group.add(water);
  }

  /** walkable ground height (terrain, building floors, player foundations) */
  groundY(x: number, z: number, feetY = Infinity) {
    let y = this.terrain.heightAt(x, z);
    for (const f of this.floors) {
      if (x > f.x0 && x < f.x1 && z > f.z0 && z < f.z1) {
        let fy = f.y;
        if (f.ramp) {
          const r = f.ramp; const t = r.axis === 'x' ? (x - f.x0) / (f.x1 - f.x0) : (z - f.z0) / (f.z1 - f.z0);
          fy = r.y0 + (r.y1 - r.y0) * (r.dir > 0 ? t : 1 - t);
        }
        if (fy > y && fy <= feetY + 0.55) y = fy;
      }
    }
    return y;
  }
  isInside(x: number, z: number) { return !!this.town.buildingAt(x, z); }
  waterDepth(x: number, z: number) { return Math.hypot(x - LAKE.x, z - LAKE.z) < LAKE.r + 12 ? Math.max(0, LAKE.level - this.terrain.heightAt(x, z)) : 0; }
  nearWater(x: number, z: number) { return Math.hypot(x - LAKE.x, z - LAKE.z) < LAKE.r + 6 && this.terrain.heightAt(x, z) < LAKE.level + 0.6; }

  update(dt: number, cam: THREE.Vector3, sunDir: THREE.Vector3, time: number, wind: number) {
    this.veg.update(cam, time, wind, this.veg.farDist);
    this.town.props.update(cam);
    if (this.water) {
      const d = Math.hypot(cam.x - LAKE.x, cam.z - LAKE.z);
      this.water.visible = d < 260;
      const u = (this.water.material as THREE.ShaderMaterial).uniforms;
      u.time.value = time * 0.6; u.sunDirection.value.copy(sunDir);
    }
    for (const d of this.drops) { d.age += dt; d.mesh.rotation.y += dt * 0.5; }
    // interiors far away hidden
    for (const b of this.town.buildings) {
      const dd = Math.hypot(b.x - cam.x, b.z - cam.z);
      b.interior.visible = dd < 70;
    }
  }

  dropItems(pos: THREE.Vector3, inv: Inventory) {
    if (!inv.stacks.length) return;
    // merge with a nearby drop
    const near = this.drops.find(d => d.pos.distanceTo(pos) < 1.2);
    if (near) { for (const s of inv.stacks) near.inv.addStack(s); return near; }
    const g = new THREE.Group();
    const bag = new THREE.Mesh(new THREE.DodecahedronGeometry(0.22, 0), getMaterial('clothDark'));
    bag.scale.set(1.2, 0.6, 0.9); bag.position.y = 0.12; bag.castShadow = true; g.add(bag);
    const y = this.groundY(pos.x, pos.z, pos.y + 1);
    g.position.set(pos.x, y, pos.z);
    this.group.add(g);
    const d: Drop = { id: this.dropId++, pos: new THREE.Vector3(pos.x, y, pos.z), inv, mesh: g, age: 0 };
    this.drops.push(d);
    return d;
  }
  removeDrop(d: Drop) { this.group.remove(d.mesh); this.drops.splice(this.drops.indexOf(d), 1); }
  inBounds(x: number, z: number) { return Math.abs(x) < HALF_WORLD - 30 && Math.abs(z) < HALF_WORLD - 30; }
}
