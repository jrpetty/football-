// Grid-based factory simulation: machines, conveyor belts, splitters/mergers, power grids, turrets, milestones.
import * as THREE from 'three';
import { GRID, tileOf, clamp, RNG } from '../core/util';
import { MACHINES, MRECIPES, MachineDef, MILESTONES, MRecipe } from './defs';
import { buildMachineModel } from '../models/machines';
import { World, OreNode } from '../world/world';
import { Inventory } from '../items/inventory';
import { item } from '../items/items';
import { getMaterial } from '../render/materials';
import { LightPool, Emitter } from '../render/lights';
import { audio } from '../audio/Audio';
import type { Box } from '../world/collision';
import { bus } from '../core/util';

export interface BeltItem { id: string; p: number }
export interface Machine {
  id: number; type: string; def: MachineDef; cx: number; cz: number; rot: number; y: number; tiles: number[];
  obj: THREE.Object3D | null; hp: number; recipe: string | null; inBuf: Record<string, number>; outBuf: Record<string, number>;
  progress: number; powered: boolean; running: boolean; fuel: number; inv: Inventory | null; net: number;
  items: BeltItem[]; dir: [number, number]; rr: number; node: OreNode | null; box: Box | null; emitter: Emitter | null;
  yaw: number; pitch: number; cooldown: number; target: any; senseT: number; x: number; z: number; destroyed?: boolean; tripReset?: boolean; status: string; plinth: THREE.Object3D | null; loopId: string | null;
}
interface Net { id: number; supply: number; demand: number; tripped: boolean; battery: number; batteryCap: number; members: Machine[] }

const ITEM_COLOR: Record<string, number> = {
  iron_ore: 0x8a4a3a, copper_ore: 0xc07040, coal: 0x1a1a1a, stone: 0xb8b0a0, sulfur: 0xd8c030, iron_ingot: 0x9aa0a8, copper_ingot: 0xd08048,
  steel_ingot: 0xc8ccd2, iron_plate: 0xa8acb2, iron_rod: 0x8a8e94, screws: 0x707478, wire: 0xd88a4a, cable: 0x222222, concrete: 0xc8c4bc,
  reinforced_plate: 0x6a7078, rotor: 0x8890a0, circuit: 0x2a7a3a, gunpowder: 0x333333, modular_frame: 0x8a8a8a, ammo_9mm: 0xc8a040,
  ammo_shells: 0xb03020, ammo_rifle: 0xa07040, antibiotics: 0xffffff, serum: 0x40c0ff, nails: 0x909090, sheet_metal: 0x9a9ea4, zombie_tissue: 0x6a2a2a,
  carrot: 0xe07020, potato: 0xb09060, corn: 0xe0c040, radio_parts: 0x404850, bandage: 0xeeeeee,
};
function shapeOf(id: string) {
  if (id.endsWith('_ore') || id === 'coal' || id === 'stone' || id === 'sulfur') return 0;
  if (id.endsWith('_ingot')) return 1;
  if (id.endsWith('plate') || id === 'sheet_metal' || id === 'circuit' || id === 'modular_frame') return 2;
  if (id === 'iron_rod' || id === 'cable' || id === 'wire') return 3;
  return 4;
}

const tkey = (tx: number, tz: number) => (tx + 1000) * 4096 + (tz + 1000);

export class Factory {
  machines: Machine[] = [];
  tiles = new Map<number, Machine>();
  nets: Net[] = [];
  group = new THREE.Group();
  hub: Machine | null = null;
  tier = 0;
  milestoneProgress: Record<string, number> = {};
  unlocked = new Set<string>();
  heat = 0;
  townPowered = false;
  victory = false;
  private nextId = 1;
  private beltMeshes = new Map<string, THREE.InstancedMesh[]>();
  private beltSurfaceMats: THREE.MeshStandardMaterial[] = [];
  private itemMeshes: THREE.InstancedMesh[] = [];
  private cables: THREE.LineSegments;
  private dirtyPower = true; private dirtyBelts = true;
  private tmpM = new THREE.Matrix4(); private tmpQ = new THREE.Quaternion(); private tmpV = new THREE.Vector3(); private tmpS = new THREE.Vector3(1, 1, 1);
  private rng = new RNG(4242);
  engineering = 0; // skill level, speeds machines
  private dropDay = -1;

  constructor(private world: World, private lights: LightPool, scene: THREE.Scene) {
    scene.add(this.group);
    // belt instancing templates
    for (const t of ['belt', 'belt2']) {
      const tpl = buildMachineModel(t as any); tpl.updateMatrixWorld(true);
      const list: THREE.InstancedMesh[] = [];
      tpl.traverse(o => {
        const m = o as THREE.Mesh; if (!m.isMesh) return;
        let mat = m.material as THREE.MeshStandardMaterial;
        if (m.name === 'beltSurface') { this.beltSurfaceMats.push(mat); }
        const im = new THREE.InstancedMesh(m.geometry, mat, 2000);
        im.count = 0; im.castShadow = true; im.receiveShadow = true; im.frustumCulled = false;
        (im as any)._local = m.matrixWorld.clone();
        this.group.add(im); list.push(im);
      });
      this.beltMeshes.set(t, list);
    }
    // item shapes on belts
    const geos = [new THREE.IcosahedronGeometry(0.16, 0), new THREE.BoxGeometry(0.36, 0.14, 0.18), new THREE.BoxGeometry(0.4, 0.05, 0.4), new THREE.CylinderGeometry(0.04, 0.04, 0.45, 6).rotateZ(Math.PI / 2), new THREE.BoxGeometry(0.24, 0.2, 0.24)];
    for (const g of geos) {
      const im = new THREE.InstancedMesh(g, new THREE.MeshStandardMaterial({ roughness: 0.5, metalness: 0.4 }), 4000);
      im.count = 0; im.castShadow = true; im.frustumCulled = false;
      im.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(4000 * 3), 3);
      this.group.add(im); this.itemMeshes.push(im);
    }
    this.cables = new THREE.LineSegments(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: 0x111111 }));
    this.cables.frustumCulled = false;
    this.group.add(this.cables);
    for (const k of ['wood_foundation', 'wood_wall', 'wood_wall_window', 'wood_wall_door', 'wood_stairs', 'wood_roof', 'workbench', 'campfire', 'crate', 'bed', 'rain_collector', 'farm_plot', 'hub', 'spike_trap', 'generator']) this.unlocked.add(k);
  }

  // ------------------------------------------------------------------ geometry helpers
  static rotVec(lx: number, lz: number, rot: number): [number, number] {
    const c = [1, 0, -1, 0][rot & 3], s = [0, 1, 0, -1][rot & 3];
    return [lx * c + lz * s, -lx * s + lz * c];
  }
  static centerFor(type: string, tx: number, tz: number, rot: number): [number, number] {
    // tx,tz = tile under cursor; machines centred so that odd sizes centre on that tile, even sizes on its +x/+z corner
    const def = MACHINES[type]; const [W, D] = def.size;
    const w = rot % 2 ? D : W, d = rot % 2 ? W : D;
    const cx = (tx + 0.5) * GRID + (w % 2 ? 0 : GRID / 2), cz = (tz + 0.5) * GRID + (d % 2 ? 0 : GRID / 2);
    return [cx, cz];
  }
  tileWorld(m: { cx: number; cz: number; rot: number; def: MachineDef }, i: number, j: number): [number, number] {
    const [W, D] = m.def.size;
    const lx = (i - (W - 1) / 2) * GRID, lz = (j - (D - 1) / 2) * GRID;
    const [rx, rz] = Factory.rotVec(lx, lz, m.rot);
    return [tileOf(m.cx + rx), tileOf(m.cz + rz)];
  }
  footprint(type: string, cx: number, cz: number, rot: number): number[] {
    const def = MACHINES[type]; const out: number[] = [];
    for (let i = 0; i < def.size[0]; i++) for (let j = 0; j < def.size[1]; j++) { const [tx, tz] = this.tileWorld({ cx, cz, rot, def }, i, j); out.push(tkey(tx, tz)); }
    return out;
  }
  frontDir(rot: number): [number, number] { const [x, z] = Factory.rotVec(0, 1, rot); return [Math.round(x), Math.round(z)]; }
  machineAtTile(tx: number, tz: number) { return this.tiles.get(tkey(tx, tz)) ?? null; }
  machineAtPos(x: number, z: number) { return this.machineAtTile(tileOf(x), tileOf(z)); }

  canPlace(type: string, cx: number, cz: number, rot: number): { ok: boolean; reason?: string; node?: OreNode } {
    const def = MACHINES[type];
    for (const k of this.footprint(type, cx, cz, rot)) if (this.tiles.has(k)) return { ok: false, reason: 'Blocked by another machine' };
    const half = Math.max(def.size[0], def.size[1]) * GRID / 2;
    if (type === 'miner' || type === 'miner2') {
      const node = this.world.ores.find(o => Math.abs(o.x - cx) < 0.5 && Math.abs(o.z - cz) < 0.5);
      if (!node) return { ok: false, reason: 'Miners must be centred on an ore deposit' };
      if (node.occupied) return { ok: false, reason: 'Deposit already has a miner' };
      return { ok: true, node };
    }
    if (this.world.ores.some(o => Math.abs(o.x - cx) < half + 1.5 && Math.abs(o.z - cz) < half + 1.5)) return { ok: false, reason: 'Too close to an ore deposit' };
    if (this.world.town.isOccupied(cx, cz, half - 1)) return { ok: false, reason: 'Inside a building' };
    if (this.world.waterDepth(cx, cz) > 0.3) return { ok: false, reason: 'In water' };
    if (type === 'hub' && this.hub) return { ok: false, reason: 'You already have an Outpost' };
    return { ok: true };
  }

  // ------------------------------------------------------------------ add/remove
  add(type: string, cx: number, cz: number, rot: number, opts: Partial<Machine> = {}): Machine {
    const def = MACHINES[type];
    const tiles = this.footprint(type, cx, cz, rot);
    const W = (rot % 2 ? def.size[1] : def.size[0]) * GRID, D = (rot % 2 ? def.size[0] : def.size[1]) * GRID;
    let maxY = -Infinity, minY = Infinity;
    for (const [sx, sz] of [[-0.45, -0.45], [0.45, -0.45], [-0.45, 0.45], [0.45, 0.45], [0, 0]]) {
      const h = this.world.groundY(cx + sx * W, cz + sz * D); maxY = Math.max(maxY, h); minY = Math.min(minY, h);
    }
    const isBelt = type === 'belt' || type === 'belt2';
    const y = isBelt ? this.world.groundY(cx, cz) : maxY;
    const m: Machine = {
      id: this.nextId++, type, def, cx, cz, rot, y, tiles, obj: null, hp: def.hp, recipe: null, inBuf: {}, outBuf: {}, progress: 0, powered: false, running: false,
      fuel: 0, inv: null, net: -1, items: [], dir: this.frontDir(rot), rr: 0, node: null, box: null, emitter: null, yaw: 0, pitch: 0, cooldown: 0, target: null, senseT: 0,
      x: cx, z: cz, status: '', plinth: null, loopId: null, ...opts,
    };
    if (type === 'storage') m.inv = new Inventory(200);
    if (type === 'turret') m.inv = new Inventory(30);
    if (type === 'biomass' || type === 'coal_gen') m.inv = new Inventory(80);
    if (type === 'smelter') m.recipe = m.recipe ?? null;
    if (type === 'greenhouse') m.recipe = m.recipe ?? 'grow_potato';
    for (const k of tiles) this.tiles.set(k, m);
    if (!isBelt) {
      const obj = buildMachineModel(type as any);
      obj.position.set(cx, y, cz); obj.rotation.y = rot * Math.PI / 2;
      // clone status light materials per machine (models already clone but be safe)
      this.group.add(obj); m.obj = obj;
      if (maxY - minY > 0.05 && type !== 'power_pole' && type !== 'wind_turbine' && type !== 'floodlight') {
        const h = maxY - minY + 0.3;
        const pl = new THREE.Mesh(new THREE.BoxGeometry(W - 0.1, h, D - 0.1), getMaterial('concrete'));
        pl.position.set(cx, maxY - h / 2 + 0.02, cz); pl.receiveShadow = true; pl.castShadow = true;
        this.group.add(pl); m.plinth = pl;
      }
      const solid = !['spike_trap', 'splitter', 'merger'].includes(type);
      if (solid) {
        const hw = type === 'power_pole' || type === 'floodlight' || type === 'wind_turbine' ? 0.3 : type === 'electric_fence' ? (rot % 2 ? 0.12 : 1) : W / 2 - 0.05;
        const hd = type === 'power_pole' || type === 'floodlight' || type === 'wind_turbine' ? 0.3 : type === 'electric_fence' ? (rot % 2 ? 1 : 0.12) : D / 2 - 0.05;
        m.box = this.world.col.addBox(cx - hw, y - 0.5, cz - hd, cx + hw, y + def.height, cz + hd, { tag: 'machine', ref: m, navCost: type === 'electric_fence' ? 60 : 40 });
      }
      if (type === 'floodlight') {
        const anchor = (obj.userData.lightAnchor as THREE.Object3D | undefined);
        const p = new THREE.Vector3(); if (anchor) { obj.updateMatrixWorld(true); anchor.getWorldPosition(p); } else p.set(cx, y + 6.5, cz);
        const [fx, fz] = m.dir; p.x += fx * 5; p.z += fz * 5; p.y -= 2.5;
        m.emitter = this.lights.add({ pos: p, color: new THREE.Color(0xfff2d8), intensity: 0, distance: 28, on: false });
      }
    } else this.dirtyBelts = true;
    if (opts.node === undefined && (type === 'miner' || type === 'miner2')) {
      const node = this.world.ores.find(o => Math.abs(o.x - cx) < 0.5 && Math.abs(o.z - cz) < 0.5) ?? null;
      m.node = node; if (node) node.occupied = true;
    }
    if (type === 'hub') this.hub = m;
    this.machines.push(m);
    this.dirtyPower = true;
    return m;
  }
  remove(m: Machine) {
    m.destroyed = true;
    for (const k of m.tiles) if (this.tiles.get(k) === m) this.tiles.delete(k);
    if (m.obj) this.group.remove(m.obj);
    if (m.plinth) this.group.remove(m.plinth);
    if (m.box) this.world.col.removeBox(m.box);
    if (m.emitter) this.lights.remove(m.emitter);
    if (m.node) m.node.occupied = false;
    if (m.loopId) audio.stopLoop(m.loopId);
    if (m === this.hub) this.hub = null;
    this.machines.splice(this.machines.indexOf(m), 1);
    this.dirtyPower = true; if (m.type.startsWith('belt')) this.dirtyBelts = true;
  }
  /** all items held by a machine (for refunds/drops) */
  contents(m: Machine): Inventory {
    const inv = new Inventory();
    for (const k in m.inBuf) if (m.inBuf[k] > 0) inv.add(k, m.inBuf[k]);
    for (const k in m.outBuf) if (m.outBuf[k] > 0) inv.add(k, m.outBuf[k]);
    if (m.inv) for (const s of m.inv.stacks) inv.addStack(s);
    for (const it of m.items) inv.add(it.id, 1);
    return inv;
  }

  // ------------------------------------------------------------------ recipes
  recipesFor(type: string): MRecipe[] { return MRECIPES.filter(r => r.machine.includes(type)); }
  recipe(m: Machine) { return m.recipe ? MRECIPES.find(r => r.id === m.recipe) ?? null : null; }
  setRecipe(m: Machine, id: string | null) {
    if (m.recipe === id) return;
    m.recipe = id; m.progress = 0;
    return { ...m.inBuf };
  }
  private accepts(m: Machine, id: string): boolean {
    const t = m.type;
    if (t === 'storage') return !!m.inv && m.inv.weight() + item(id).weight <= m.inv.capacity;
    if (t === 'turret') return (id === 'ammo_9mm' || id === 'ammo_rifle' || id === 'ammo_shells') && m.inv!.weight() < 25;
    if (t === 'coal_gen') return (id === 'coal' || id === 'gas_can') && m.inv!.count(id) < 50;
    if (t === 'hub') { const ms = MILESTONES[this.tier]; return !!ms && (ms.cost[id] ?? 0) > (this.milestoneProgress[id] ?? 0); }
    if (t === 'splitter' || t === 'merger') return !(m.inBuf.__n > 0);
    let r = this.recipe(m);
    if (!r && t === 'smelter') { r = MRECIPES.find(q => q.machine.includes('smelter') && q.inp[id]) ?? null; if (r) m.recipe = r.id; }
    if (!r && t === 'foundry') { r = MRECIPES.find(q => q.machine.includes('foundry') && q.inp[id]) ?? null; if (r) m.recipe = r.id; }
    if (!r || !r.inp[id]) return false;
    return (m.inBuf[id] ?? 0) < r.inp[id] * 4;
  }
  private insert(m: Machine, id: string): boolean {
    if (!this.accepts(m, id)) return false;
    const t = m.type;
    if (t === 'storage' || t === 'turret' || t === 'coal_gen') { m.inv!.add(id, 1); return true; }
    if (t === 'hub') { this.milestoneProgress[id] = (this.milestoneProgress[id] ?? 0) + 1; this.checkMilestone(); return true; }
    if (t === 'splitter' || t === 'merger') { m.inBuf = { __n: 1 }; (m as any).held = id; return true; }
    m.inBuf[id] = (m.inBuf[id] ?? 0) + 1; return true;
  }
  /** does a belt at tile (tx,tz) moving in dir feed machine m through a valid input? */
  private feeds(m: Machine, tx: number, tz: number, dir: [number, number]): boolean {
    const t = m.type;
    if (t === 'splitter') { const f = m.dir; return !(dir[0] === -f[0] && dir[1] === -f[1]) && this.isBehind(m, tx, tz, dir, [[0, -1]]); }
    if (t === 'merger') return this.isBehind(m, tx, tz, dir, [[0, -1], [-1, 0], [1, 0]]);
    for (const i of m.def.inputs) {
      const [px, pz] = this.tileWorld(m, i, -1);
      if (px === tx && pz === tz && dir[0] === m.dir[0] && dir[1] === m.dir[1]) return true;
    }
    return false;
  }
  private isBehind(m: Machine, tx: number, tz: number, dir: [number, number], locals: [number, number][]) {
    const mx = tileOf(m.cx), mz = tileOf(m.cz);
    for (const [lx, lz] of locals) {
      const [wx, wz] = Factory.rotVec(lx, lz, m.rot);
      if (mx + Math.round(wx) === tx && mz + Math.round(wz) === tz && tx + dir[0] === mx && tz + dir[1] === mz) return true;
    }
    return false;
  }
  private pushOut(m: Machine, id: string, tx: number, tz: number, dir: [number, number]): boolean {
    const b = this.machineAtTile(tx + dir[0], tz + dir[1]);
    if (!b || b === m) return false;
    if (b.type === 'belt' || b.type === 'belt2') {
      if (b.dir[0] === -dir[0] && b.dir[1] === -dir[1]) return false;
      const last = b.items[b.items.length - 1];
      if (last && last.p < 0.5) return false;
      b.items.push({ id, p: 0 }); return true;
    }
    if (this.feeds(b, tx, tz, dir)) return this.insert(b, id);
    return false;
  }

  // ------------------------------------------------------------------ power
  private rebuildPower() {
    this.dirtyPower = false;
    const nodes = this.machines.filter(m => m.def.power !== 0 || m.type === 'power_pole' || m.type === 'battery');
    const parent = new Map<Machine, Machine>();
    const find = (a: Machine): Machine => { let p = parent.get(a) ?? a; if (p !== a) { p = find(p); parent.set(a, p); } return p; };
    const union = (a: Machine, b: Machine) => { const ra = find(a), rb = find(b); if (ra !== rb) parent.set(ra, rb); };
    const segs: number[] = [];
    const poles = nodes.filter(n => n.type === 'power_pole');
    const anchor = (m: Machine) => m.type === 'power_pole' ? new THREE.Vector3(m.cx, m.y + 5.7, m.cz) : new THREE.Vector3(m.cx, m.y + Math.min(m.def.height * 0.6, 3), m.cz);
    const addCable = (a: Machine, b: Machine) => {
      const A = anchor(a), B = anchor(b); const N = 8; const L = A.distanceTo(B);
      for (let i = 0; i < N; i++) {
        const t0 = i / N, t1 = (i + 1) / N;
        const p0 = A.clone().lerp(B, t0); p0.y -= Math.sin(t0 * Math.PI) * L * 0.04;
        const p1 = A.clone().lerp(B, t1); p1.y -= Math.sin(t1 * Math.PI) * L * 0.04;
        segs.push(p0.x, p0.y, p0.z, p1.x, p1.y, p1.z);
      }
    };
    for (let i = 0; i < poles.length; i++) for (let j = i + 1; j < poles.length; j++) {
      const a = poles[i], b = poles[j];
      if (Math.hypot(a.cx - b.cx, a.cz - b.cz) <= 16.5) { union(a, b); addCable(a, b); }
    }
    for (const n of nodes) {
      if (n.type === 'power_pole') continue;
      const half = Math.max(n.def.size[0], n.def.size[1]) * GRID / 2;
      let best: Machine | null = null, bd = 1e9;
      for (const p of poles) { const d = Math.hypot(p.cx - n.cx, p.cz - n.cz) - half; if (d < 10 && d < bd) { bd = d; best = p; } }
      if (best) { union(n, best); addCable(n, best); }
    }
    const nets = new Map<Machine, Net>();
    this.nets = [];
    for (const n of nodes) {
      const r = find(n); let net = nets.get(r);
      if (!net) { net = { id: this.nets.length, supply: 0, demand: 0, tripped: false, battery: 0, batteryCap: 0, members: [] }; nets.set(r, net); this.nets.push(net); }
      net.members.push(n); n.net = net.id;
    }
    for (const m of this.machines) if (!nodes.includes(m)) m.net = -1;
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(segs, 3));
    this.cables.geometry.dispose(); this.cables.geometry = g;
  }

  resetFuse(m: Machine) { const n = this.nets[m.net]; if (n && n.tripped) { n.tripped = false; audio.play('power_on', { pos: new THREE.Vector3(m.cx, m.y + 1, m.cz) }); return true; } return false; }

  // ------------------------------------------------------------------ update
  update(dt: number, ctx: { daylight: number; overcast: number; wind: number; playerPos: THREE.Vector3; zombies: any; particles: any; hours: number; day: number; onDrop: (p: THREE.Vector3) => void }) {
    if (this.dirtyPower) this.rebuildPower();
    const speedMul = 1 + this.engineering * 0.04;
    // ---- power accounting
    let batteryStore = 0;
    for (const net of this.nets) {
      let supply = 0, demand = 0, cap = 0;
      for (const m of net.members) {
        const p = m.def.power;
        if (p < 0) {
          if (m.type === 'solar') supply += -p * ctx.daylight * (1 - ctx.overcast * 0.6);
          else if (m.type === 'wind_turbine') supply += clamp(5 + ctx.wind * 25, 5, 25);
          else if (m.fuel > 0 || this.refuel(m)) supply += -p;
        } else if (p > 0) {
          const wants = m.type === 'floodlight' ? ctx.daylight < 0.5 : m.type === 'electric_fence' || m.type === 'radio_tower' || m.type === 'uplink' || m.type === 'turret' ? true : m.running || this.canRun(m);
          demand += wants ? p : p * 0.05;
        }
        if (m.type === 'battery') cap += 4000;
      }
      net.batteryCap = cap; net.battery = Math.min(net.battery, cap);
      net.supply = supply; net.demand = demand;
      let deficit = demand - supply;
      if (deficit > 0 && net.battery > 0) { const draw = Math.min(net.battery, deficit * dt); net.battery -= draw; deficit -= draw / Math.max(dt, 1e-4); }
      else if (deficit < 0 && cap > 0) net.battery = Math.min(cap, net.battery - deficit * dt);
      if (deficit > 0.01 && !net.tripped && demand > 0) {
        net.tripped = true; audio.play('fuse_blow', { pos: new THREE.Vector3(net.members[0].cx, net.members[0].y + 2, net.members[0].cz) });
        bus.emit('notify', '⚡ Power fuse tripped! Not enough generation. Reset it at any power pole or generator (E).', 'bad');
      }
      // burn fuel proportional to load
      const load = supply > 0 ? clamp(demand / supply, 0, 1) : 0;
      for (const m of net.members) if (m.def.power < 0 && m.fuel > 0) m.fuel = Math.max(0, m.fuel - (-m.def.power) * load * dt);
      batteryStore += net.battery;
      for (const m of net.members) m.powered = !net.tripped && (m.def.power <= 0 || supply > 0 || net.battery > 0);
    }
    void batteryStore;
    // generators not on a net still burn nothing
    // ---- machines
    let heat = 0;
    const hub = this.hub;
    for (const m of this.machines) {
      if (m.net === -1 && m.def.power > 0) m.powered = false;
      const t = m.type;
      if (t === 'belt' || t === 'belt2') continue;
      let running = false;
      if (t === 'miner' || t === 'miner2') {
        if (m.powered && m.node) {
          const tot = Object.values(m.outBuf).reduce((a, b) => a + b, 0);
          if (tot < 40) { running = true; m.progress += dt * (t === 'miner2' ? 1 : 0.5) * m.node.purity * speedMul; while (m.progress >= 1) { m.progress -= 1; m.outBuf[m.node.type] = (m.outBuf[m.node.type] ?? 0) + 1; } }
        }
      } else if (['smelter', 'constructor', 'assembler', 'foundry', 'lab', 'greenhouse'].includes(t)) {
        const r = this.recipe(m);
        if (r && m.powered && this.canRun(m)) {
          running = true;
          m.progress += dt * speedMul / r.time;
          if (m.progress >= 1) { m.progress = 0; for (const k in r.inp) m.inBuf[k] -= r.inp[k]; m.outBuf[r.out[0]] = (m.outBuf[r.out[0]] ?? 0) + r.out[1]; }
        }
      } else if (t === 'splitter' || t === 'merger') {
        if ((m as any).held) {
          const dirs: [number, number][] = t === 'splitter' ? [[0, 1], [-1, 0], [1, 0]] : [[0, 1]];
          for (let k = 0; k < dirs.length; k++) {
            const idx = (m.rr + k) % dirs.length;
            const [wx, wz] = Factory.rotVec(dirs[idx][0], dirs[idx][1], m.rot);
            if (this.pushOut(m, (m as any).held, tileOf(m.cx), tileOf(m.cz), [Math.round(wx), Math.round(wz)])) { (m as any).held = null; m.inBuf = {}; m.rr = idx + 1; break; }
          }
        }
      } else if (t === 'storage') {
        const s = m.inv!.stacks[0];
        if (s) { const [tx, tz] = this.tileWorld(m, 0, 0); if (this.pushOut(m, s.id, tx, tz, m.dir)) m.inv!.remove(s.id, 1); }
      } else if (t === 'turret') {
        running = this.turret(m, dt, ctx);
      } else if (t === 'floodlight') {
        running = m.powered && ctx.daylight < 0.5;
        if (m.emitter) { m.emitter.on = running; m.emitter.intensity = running ? 380 : 0; }
      } else if (t === 'electric_fence') {
        running = m.powered;
        if (running) this.zap(m, dt, ctx);
      } else if (t === 'spike_trap') {
        this.spikes(m, dt, ctx);
      } else if (t === 'biomass' || t === 'coal_gen') {
        running = m.fuel > 0 && this.nets[m.net]?.demand > 0;
        if (running && ctx.particles && Math.random() < dt * 4) {
          const top = new THREE.Vector3(m.cx, m.y + m.def.height + 0.3, m.cz); ctx.particles.smoke(top, t === 'coal_gen' ? 0.15 : 0.35);
        }
      } else if (t === 'radio_tower') {
        running = m.powered;
        if (running && ctx.hours >= 12 && this.dropDay !== ctx.day) { this.dropDay = ctx.day; ctx.onDrop(new THREE.Vector3(m.cx + 8, m.y, m.cz + 8)); }
      } else if (t === 'uplink') { running = m.powered; }
      else if (t === 'solar' || t === 'wind_turbine') running = true;
      // outputs to belts
      const outs = Object.keys(m.outBuf).filter(k => m.outBuf[k] > 0);
      if (outs.length && m.def.outputs.length) {
        for (const i of m.def.outputs) {
          const id = outs[(m.rr++) % outs.length];
          const [tx, tz] = this.tileWorld(m, i, m.def.size[1] - 1);
          if (this.pushOut(m, id, tx, tz, m.dir)) m.outBuf[id]--;
        }
      }
      m.running = running;
      if (running && hub && Math.hypot(m.cx - hub.cx, m.cz - hub.cz) < 90) heat += m.def.heat;
      // visuals
      if (m.obj) {
        const anim = m.obj.userData.animate as ((dt: number, r: boolean, t: number) => void) | undefined;
        if (anim && Math.abs(m.cx - ctx.playerPos.x) < 90 && Math.abs(m.cz - ctx.playerPos.z) < 90) anim(dt, running, performance.now() / 1000);
        const st = !m.powered && m.def.power > 0 ? 'off' : running ? 'run' : 'idle';
        if (st !== m.status) {
          m.status = st;
          const lights = (m.obj.userData.statusLights as THREE.Mesh[] | undefined) ?? [];
          const c = st === 'run' ? 0x30ff60 : st === 'idle' ? 0xffaa20 : 0xff2a20;
          for (const l of lights) { const mm = l.material as THREE.MeshStandardMaterial; mm.color.setHex(c); mm.emissive.setHex(c); }
        }
      }
      // machine sound loops
      const loopName = t === 'miner' || t === 'miner2' ? 'drill' : t === 'smelter' || t === 'foundry' ? 'smelter' : t === 'coal_gen' || t === 'biomass' ? 'generator' : t === 'wind_turbine' ? 'turbine' : t === 'electric_fence' ? 'fence_buzz' : ['constructor', 'assembler', 'lab', 'greenhouse'].includes(t) ? 'machine_hum' : null;
      if (loopName) {
        const want = running;
        if (want && !m.loopId) { m.loopId = 'm' + m.id; audio.startLoop(m.loopId, loopName as any, { pos: new THREE.Vector3(m.cx, m.y + 1.5, m.cz), volume: 0.6 }); }
        else if (!want && m.loopId) { audio.stopLoop(m.loopId); m.loopId = null; }
      }
    }
    this.heat = this.heat + (heat - this.heat) * Math.min(1, dt * 0.2);
    this.townPowered = this.machines.some(m => m.type === 'uplink' && m.running);

    // ---- belts
    this.updateBelts(dt);
    this.renderBelts(ctx.playerPos, dt);
  }

  private refuel(m: Machine): boolean {
    if (!m.inv) return false;
    for (const s of m.inv.stacks) {
      const f = item(s.id).fuel; if (!f) continue;
      if (m.type === 'coal_gen' && s.id !== 'coal' && s.id !== 'gas_can') continue;
      m.inv.remove(s.id, 1); m.fuel += f * (m.type === 'coal_gen' ? 30 : 30); return true;
    }
    return false;
  }
  private canRun(m: Machine) {
    const r = this.recipe(m); if (!r) return false;
    for (const k in r.inp) if ((m.inBuf[k] ?? 0) < r.inp[k]) return false;
    return (m.outBuf[r.out[0]] ?? 0) < 60;
  }
  private turret(m: Machine, dt: number, ctx: any): boolean {
    if (!m.powered || !m.inv) return false;
    const head = m.obj?.getObjectByName('head'), barrel = m.obj?.getObjectByName('barrel');
    m.senseT -= dt; m.cooldown -= dt;
    const pos = new THREE.Vector3(m.cx, m.y + 1.6, m.cz);
    if (m.senseT <= 0) {
      m.senseT = 0.3; m.target = null; let bd = 28 * 28;
      for (const z of ctx.zombies.list) {
        if (z.state === 'dead' || z.frozen) continue;
        const d = z.pos.distanceToSquared(pos); if (d > bd) continue;
        if (this.world.col.blocked(pos.x, pos.y, pos.z, z.pos.x, z.pos.y + 1.3, z.pos.z)) continue;
        bd = d; m.target = z;
      }
    }
    const z = m.target;
    if (!z || z.state === 'dead') { if (head) head.rotation.y += dt * 0.4; return false; }
    const aim = new THREE.Vector3(z.pos.x, z.pos.y + 1.3 * z.scale, z.pos.z);
    const dx = aim.x - pos.x, dz = aim.z - pos.z, dy = aim.y - pos.y;
    const want = Math.atan2(dx, dz) - m.rot * Math.PI / 2;
    m.yaw += Math.atan2(Math.sin(want - m.yaw), Math.cos(want - m.yaw)) * Math.min(1, dt * 8);
    m.pitch = -Math.atan2(dy, Math.hypot(dx, dz));
    if (head) head.rotation.y = m.yaw;
    if (barrel) barrel.rotation.x = m.pitch;
    const ammo = m.inv.find('ammo_rifle') ?? m.inv.find('ammo_9mm') ?? m.inv.find('ammo_shells');
    if (!ammo) return false;
    if (m.cooldown <= 0) {
      const kind = ammo.id; m.inv.remove(kind, 1);
      m.cooldown = kind === 'ammo_rifle' ? 0.6 : kind === 'ammo_shells' ? 0.9 : 0.22;
      const dir = aim.clone().sub(pos).normalize();
      const dmg = kind === 'ammo_rifle' ? 95 : kind === 'ammo_shells' ? 110 : 34;
      const head2 = this.rng.chance(0.18);
      ctx.zombies.damage(z, dmg * (0.85 + this.rng.next() * 0.3), dir, head2, kind === 'ammo_shells' ? 2 : 0.8, false);
      ctx.zombies.noise(pos, 55);
      audio.play('turret_fire', { pos });
      const muzzle = pos.clone().addScaledVector(dir, 0.9);
      ctx.particles?.muzzle(muzzle, dir);
      ctx.particles?.blood(aim, dir, 0.5);
      this.lights.flash(muzzle, 0xffc070, 90, 12, 0.06);
    }
    return true;
  }
  private zap(m: Machine, dt: number, ctx: any) {
    for (const z of ctx.zombies.list) {
      if (z.state === 'dead' || z.frozen) continue;
      if (Math.abs(z.pos.x - m.cx) < 1.6 && Math.abs(z.pos.z - m.cz) < 1.6) {
        if (Math.random() < dt * 3) { ctx.zombies.damage(z, 25, new THREE.Vector3(z.pos.x - m.cx, 0, z.pos.z - m.cz), false, 1.6, false); ctx.particles?.electric(z.pos); audio.play('zap', { pos: z.pos }); }
      }
    }
  }
  private spikes(m: Machine, dt: number, ctx: any) {
    for (const z of ctx.zombies.list) {
      if (z.state === 'dead' || z.frozen) continue;
      if (Math.abs(z.pos.x - m.cx) < 1 && Math.abs(z.pos.z - m.cz) < 1) {
        z.vel.multiplyScalar(Math.exp(-dt * 6));
        if (Math.random() < dt * 2) { ctx.zombies.damage(z, 18, new THREE.Vector3(0, 0, 1), false, 0, false); m.hp -= 4; }
      }
    }
    if (m.hp <= 0) this.remove(m);
  }

  private updateBelts(dt: number) {
    for (const m of this.machines) {
      if (m.type !== 'belt' && m.type !== 'belt2') continue;
      const speed = m.type === 'belt2' ? 1.0 : 0.5; // tiles per second
      const its = m.items;
      for (let i = 0; i < its.length; i++) {
        const lim = i === 0 ? 1 : its[i - 1].p - 0.5;
        its[i].p = Math.min(lim, its[i].p + speed * dt);
      }
      if (its.length && its[0].p >= 1) {
        const tx = tileOf(m.cx), tz = tileOf(m.cz);
        if (this.pushOut(m, its[0].id, tx, tz, m.dir)) its.shift();
      }
    }
  }
  private renderBelts(cam: THREE.Vector3, dt: number) {
    for (const mat of this.beltSurfaceMats) { if (mat.map) mat.map.offset.y -= dt * 1.0; if (mat.normalMap) mat.normalMap.offset.y -= dt * 1.0; }
    if (this.dirtyBelts) {
      this.dirtyBelts = false;
      for (const [t, list] of this.beltMeshes) {
        let n = 0;
        for (const m of this.machines) {
          if (m.type !== t) continue;
          this.tmpQ.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, m.rot * Math.PI / 2);
          this.tmpV.set(m.cx, m.y, m.cz);
          this.tmpM.compose(this.tmpV, this.tmpQ, this.tmpS);
          for (const im of list) { const mm = new THREE.Matrix4().multiplyMatrices(this.tmpM, (im as any)._local); im.setMatrixAt(n, mm); }
          n++;
        }
        for (const im of list) { im.count = n; im.instanceMatrix.needsUpdate = true; }
      }
    }
    const counts = [0, 0, 0, 0, 0]; const col = new THREE.Color();
    for (const m of this.machines) {
      if (m.type !== 'belt' && m.type !== 'belt2') continue;
      if (Math.abs(m.cx - cam.x) > 80 || Math.abs(m.cz - cam.z) > 80) continue;
      for (const it of m.items) {
        const s = shapeOf(it.id); const im = this.itemMeshes[s]; const i = counts[s]++;
        if (i >= 4000) continue;
        this.tmpV.set(m.cx + m.dir[0] * (it.p - 0.5) * GRID, m.y + 0.7, m.cz + m.dir[1] * (it.p - 0.5) * GRID);
        this.tmpQ.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, Math.atan2(m.dir[0], m.dir[1]));
        this.tmpM.compose(this.tmpV, this.tmpQ, this.tmpS);
        im.setMatrixAt(i, this.tmpM); im.setColorAt(i, col.setHex(ITEM_COLOR[it.id] ?? 0x888888));
      }
    }
    this.itemMeshes.forEach((im, k) => { im.count = Math.min(4000, counts[k]); im.instanceMatrix.needsUpdate = true; if (im.instanceColor) im.instanceColor.needsUpdate = true; });
  }

  // ------------------------------------------------------------------ milestones
  depositToHub(id: string, n: number) {
    const ms = MILESTONES[this.tier]; if (!ms) return 0;
    const need = (ms.cost[id] ?? 0) - (this.milestoneProgress[id] ?? 0);
    const k = Math.max(0, Math.min(need, n));
    if (k > 0) { this.milestoneProgress[id] = (this.milestoneProgress[id] ?? 0) + k; this.checkMilestone(); }
    return k;
  }
  private checkMilestone() {
    const ms = MILESTONES[this.tier]; if (!ms) return;
    for (const k in ms.cost) if ((this.milestoneProgress[k] ?? 0) < ms.cost[k]) return;
    this.tier++; this.milestoneProgress = {};
    for (const u of ms.unlocks) this.unlocked.add(u);
    audio.play('milestone');
    if (ms.id === 'm6') { this.victory = true; bus.emit('victory'); }
    bus.emit('milestone', ms);
  }

  damageMachine(m: Machine, dmg: number) {
    m.hp -= dmg;
    if (m.hp <= 0 && !m.destroyed) {
      const inv = this.contents(m);
      this.world.dropItems(new THREE.Vector3(m.cx, m.y, m.cz), inv);
      this.remove(m);
      audio.play('wood_break', { pos: new THREE.Vector3(m.cx, m.y + 1, m.cz) });
      bus.emit('notify', `💥 Your ${m.def.name} was destroyed!`, 'bad');
    }
  }

  netOf(m: Machine) { return m.net >= 0 ? this.nets[m.net] : null; }

  toJSON() {
    return {
      tier: this.tier, progress: this.milestoneProgress, unlocked: [...this.unlocked], dropDay: this.dropDay, nets: this.nets.map(n => n.battery),
      machines: this.machines.map(m => ({ type: m.type, cx: m.cx, cz: m.cz, rot: m.rot, hp: m.hp, recipe: m.recipe, inBuf: m.inBuf, outBuf: m.outBuf, fuel: m.fuel, inv: m.inv?.stacks ?? null, items: m.items, held: (m as any).held ?? null })),
    };
  }
  load(o: any) {
    for (const m of [...this.machines]) this.remove(m);
    this.tier = o.tier; this.milestoneProgress = o.progress ?? {}; this.dropDay = o.dropDay ?? -1;
    for (const u of o.unlocked ?? []) this.unlocked.add(u);
    for (const s of o.machines) {
      const m = this.add(s.type, s.cx, s.cz, s.rot);
      m.hp = s.hp; m.recipe = s.recipe; m.inBuf = s.inBuf ?? {}; m.outBuf = s.outBuf ?? {}; m.fuel = s.fuel ?? 0; m.items = s.items ?? [];
      if (s.inv && m.inv) m.inv.load(s.inv);
      if (s.held) (m as any).held = s.held;
    }
    this.rebuildPower();
    (o.nets ?? []).forEach((b: number, i: number) => { if (this.nets[i]) this.nets[i].battery = b; });
  }
}
