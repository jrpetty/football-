// Player-built structures: foundations, walls, doors, stairs, roofs, stations (workbench, campfire, crates, beds, rain collectors, farms) and barricades.
import * as THREE from 'three';
import { GRID, tileOf } from '../core/util';
import { PIECES, PieceDef } from '../factory/defs';
import { buildPiece } from '../models/buildpieces';
import { buildProp, PROP_SIZES } from '../models/props';
import { buildGeneratorModel } from '../models/lowpoly';
import { World, Floor } from '../world/world';
import { Box } from '../world/collision';
import { Inventory } from '../items/inventory';
import { Container, Door, Win } from '../world/town';
import { LightPool, Emitter } from '../render/lights';
import { getMaterial } from '../render/materials';
import { audio } from '../audio/Audio';
import { bus } from '../core/util';

export interface Piece {
  id: number; def: PieceDef; tx: number; tz: number; axis: 0 | 1; rot: number; y: number; obj: THREE.Object3D; boxes: Box[]; hp: number; maxHp: number;
  floor: Floor | null; container: Container | null; door: { open: boolean; pivot: THREE.Object3D; angle: number; box: Box; width: number } | null;
  fire: { fuel: number; lit: boolean; emitter: Emitter; pos: THREE.Vector3 } | null; farm: { crop: string | null; growth: number; water: number; plant: THREE.Object3D | null } | null;
  rain: { water: number } | null; gen: { fuel: number; on: boolean } | null; cx: number; cz: number; destroyed?: boolean; barricade?: number;
}

export class Base {
  pieces: Piece[] = [];
  group = new THREE.Group();
  private nextId = 1;
  private edges = new Map<string, Piece>();
  private tilesMap = new Map<string, Piece[]>();
  carpentry = 0;

  constructor(private world: World, private lights: LightPool, scene: THREE.Scene) { scene.add(this.group); }

  static edgeKey(tx: number, tz: number, axis: number) { return `${tx},${tz},${axis}`; }
  /** convert a world point near a tile edge into a normalised edge (axis 0: edge along X at z=(tz+1)*G; axis 1: along Z at x=(tx+1)*G) */
  static nearestEdge(x: number, z: number): { tx: number; tz: number; axis: 0 | 1; cx: number; cz: number } {
    const tx = tileOf(x), tz = tileOf(z);
    const fx = x / GRID - tx, fz = z / GRID - tz;
    const d = [1 - fz, 1 - fx, fz, fx]; // +z, +x, -z, -x
    let s = 0; for (let i = 1; i < 4; i++) if (d[i] < d[s]) s = i;
    if (s === 0) return { tx, tz, axis: 0, cx: (tx + 0.5) * GRID, cz: (tz + 1) * GRID };
    if (s === 1) return { tx, tz, axis: 1, cx: (tx + 1) * GRID, cz: (tz + 0.5) * GRID };
    if (s === 2) return { tx, tz: tz - 1, axis: 0, cx: (tx + 0.5) * GRID, cz: tz * GRID };
    return { tx: tx - 1, tz, axis: 1, cx: tx * GRID, cz: (tz + 0.5) * GRID };
  }
  edgeTaken(tx: number, tz: number, axis: number) { return this.edges.has(Base.edgeKey(tx, tz, axis)); }
  tileTaken(tx: number, tz: number, kind: string) { return (this.tilesMap.get(`${tx},${tz}`) ?? []).some(p => p.def.kind === kind || (kind !== 'foundation' && p.def.kind !== 'foundation' && p.def.kind !== 'roof')); }

  edgeBaseY(e: { cx: number; cz: number; axis: number }) {
    const off = 0.5;
    const ax = e.axis === 1 ? off : 0, az = e.axis === 0 ? off : 0;
    return Math.max(this.world.groundY(e.cx + ax, e.cz + az, 50), this.world.groundY(e.cx - ax, e.cz - az, 50), this.world.terrain.heightAt(e.cx, e.cz));
  }
  tileBaseY(tx: number, tz: number, includeFloors = true) {
    const cx = (tx + 0.5) * GRID, cz = (tz + 0.5) * GRID;
    let m = -Infinity;
    for (const [sx, sz] of [[-0.9, -0.9], [0.9, -0.9], [-0.9, 0.9], [0.9, 0.9], [0, 0]]) m = Math.max(m, includeFloors ? this.world.groundY(cx + sx, cz + sz, 50) : this.world.terrain.heightAt(cx + sx, cz + sz));
    return m;
  }

  place(id: string, tx: number, tz: number, axis: 0 | 1, rot: number, load?: Partial<Piece>): Piece {
    const def = PIECES[id];
    const hpMul = 1 + this.carpentry * 0.08;
    let obj: THREE.Object3D; const boxes: Box[] = [];
    let cx = (tx + 0.5) * GRID, cz = (tz + 0.5) * GRID, y = 0;
    const p: Piece = { id: this.nextId++, def, tx, tz, axis, rot, y: 0, obj: null as any, boxes, hp: def.hp * hpMul, maxHp: def.hp * hpMul, floor: null, container: null, door: null, fire: null, farm: null, rain: null, gen: null, cx, cz };
    const col = this.world.col;
    if (def.kind === 'wall' || def.kind === 'door_wall' || def.kind === 'window_wall' || def.kind === 'gate') {
      cx = axis === 0 ? (tx + 0.5) * GRID : (tx + 1) * GRID; cz = axis === 0 ? (tz + 1) * GRID : (tz + 0.5) * GRID;
      if (def.kind === 'gate') { if (axis === 0) cx += GRID / 2; else cz += GRID / 2; }
      y = load?.y ?? this.edgeBaseY({ cx, cz, axis });
      obj = buildPiece(def.model!);
      obj.position.set(cx, y, cz); obj.rotation.y = axis === 1 ? Math.PI / 2 : 0;
      const W = def.kind === 'gate' ? 4 : 2;
      const hw = axis === 0 ? W / 2 : 0.12, hd = axis === 0 ? 0.12 : W / 2;
      if (def.kind === 'wall') boxes.push(col.addBox(cx - hw, y - 0.3, cz - hd, cx + hw, y + 3, cz + hd, { tag: 'pwall', ref: p, navCost: 25 }));
      else if (def.kind === 'window_wall') {
        boxes.push(col.addBox(cx - hw, y - 0.3, cz - hd, cx + hw, y + 3, cz + hd, { tag: 'pwall', ref: p, navCost: 25 }));
      } else {
        // door: side posts are solid, the leaf is a toggled box
        const leafW = def.kind === 'gate' ? 3.8 : 1.1;
        const side = (W - leafW) / 2;
        for (const s of [-1, 1]) {
          const ox = axis === 0 ? s * (W / 2 - side / 2) : 0, oz = axis === 1 ? s * (W / 2 - side / 2) : 0;
          const shw = axis === 0 ? side / 2 : 0.12, shd = axis === 0 ? 0.12 : side / 2;
          if (side > 0.05) boxes.push(col.addBox(cx + ox - shw, y - 0.3, cz + oz - shd, cx + ox + shw, y + 3, cz + oz + shd, { tag: 'pwall', ref: p, navCost: 25 }));
        }
        const lhw = axis === 0 ? leafW / 2 : 0.1, lhd = axis === 0 ? 0.1 : leafW / 2;
        const box = col.addBox(cx - lhw, y, cz - lhd, cx + lhw, y + 2.3, cz + lhd, { tag: 'pdoor', ref: p, navCost: 30 });
        let pivot: THREE.Object3D;
        if (def.kind === 'gate') { pivot = obj; }
        else {
          const leaf = buildPiece(id.startsWith('metal') ? 'metal_door' : 'wood_door');
          pivot = new THREE.Group(); pivot.add(leaf);
          pivot.position.set(axis === 0 ? cx - 0.55 : cx, y, axis === 1 ? cz - 0.55 : cz);
          pivot.rotation.y = axis === 1 ? -Math.PI / 2 : 0;
          this.group.add(pivot);
        }
        p.door = { open: false, pivot, angle: 0, box, width: leafW };
      }
      this.edges.set(Base.edgeKey(tx, tz, axis), p);
      if (def.kind === 'gate') this.edges.set(Base.edgeKey(axis === 0 ? tx + 1 : tx, axis === 1 ? tz + 1 : tz, axis), p);
      // support under wall on slopes
      const ground = Math.min(this.world.terrain.heightAt(cx - (axis === 0 ? 1 : 0), cz - (axis === 1 ? 1 : 0)), this.world.terrain.heightAt(cx + (axis === 0 ? 1 : 0), cz + (axis === 1 ? 1 : 0)));
      if (y - ground > 0.1) {
        const h = y - ground + 0.2; const sup = new THREE.Mesh(new THREE.BoxGeometry(axis === 0 ? W : 0.24, h, axis === 0 ? 0.24 : W), getMaterial(id.startsWith('metal') ? 'metalRust' : id.startsWith('concrete') ? 'concrete' : 'woodDark'));
        sup.position.set(cx, y - h / 2 + 0.05, cz); sup.castShadow = true; sup.receiveShadow = true; obj.userData.support = sup; this.group.add(sup);
      }
    } else if (def.kind === 'foundation' || def.kind === 'roof') {
      if (def.kind === 'foundation') y = load?.y ?? this.tileBaseY(tx, tz, false);
      else y = load?.y ?? this.roofHeight(tx, tz);
      obj = buildPiece(def.model!);
      obj.position.set(cx, y, cz);
      const top = def.kind === 'foundation' ? y + 0.3 : y + 0.15;
      if (def.kind === 'foundation') {
        const ground = Math.min(...[[-0.95, -0.95], [0.95, -0.95], [-0.95, 0.95], [0.95, 0.95]].map(([a, b]) => this.world.terrain.heightAt(cx + a, cz + b)));
        if (y - ground > 0.02) { const h = y - ground + 0.3; const sup = new THREE.Mesh(new THREE.BoxGeometry(1.96, h, 1.96), getMaterial(id.startsWith('metal') ? 'concrete' : 'woodDark')); sup.position.set(cx, y - h / 2 + 0.01, cz); sup.castShadow = sup.receiveShadow = true; obj.userData.support = sup; this.group.add(sup); }
        this.world.terrain.paintDirt(cx, cz, 1.6);
      }
      p.floor = { x0: cx - 1, z0: cz - 1, x1: cx + 1, z1: cz + 1, y: top, ref: p };
      this.world.floors.push(p.floor);
    } else if (def.kind === 'stairs') {
      y = load?.y ?? this.tileBaseY(tx, tz);
      obj = buildPiece(def.model!); obj.position.set(cx, y, cz); obj.rotation.y = rot * Math.PI / 2;
      const dir = [[0, 1], [1, 0], [0, -1], [-1, 0]][rot & 3];
      p.floor = { x0: cx - 1, z0: cz - 1, x1: cx + 1, z1: cz + 1, y: y + 3, ref: p, ramp: { axis: dir[0] !== 0 ? 'x' : 'z', dir: dir[0] + dir[1], y0: y, y1: y + 3.15 } };
      this.world.floors.push(p.floor);
    } else if (def.kind === 'tower') {
      y = load?.y ?? this.tileBaseY(tx, tz);
      obj = buildPiece(def.model!); obj.position.set(cx, y, cz); obj.rotation.y = rot * Math.PI / 2;
      for (const [ox, oz] of [[-0.9, -0.9], [0.9, -0.9], [-0.9, 0.9], [0.9, 0.9]]) boxes.push(col.addBox(cx + ox - 0.12, y, cz + oz - 0.12, cx + ox + 0.12, y + 5, cz + oz + 0.12, { tag: 'pwall', ref: p, navCost: 25 }));
      p.floor = { x0: cx - 1, z0: cz - 1, x1: cx + 1, z1: cz + 1, y: y + 5, ref: p };
      this.world.floors.push(p.floor);
    } else {
      // stations
      y = load?.y ?? this.tileBaseY(tx, tz);
      if (def.custom === 'generator') obj = buildGeneratorModel(); else if (def.prop) { obj = buildProp(def.prop); } else obj = buildPiece(def.model!);
      obj.position.set(cx, y, cz); obj.rotation.y = rot * Math.PI / 2;
      obj.traverse(o => { if ((o as THREE.Mesh).isMesh) { o.castShadow = true; o.receiveShadow = true; } });
      const sz = def.prop ? PROP_SIZES[def.prop] : def.custom === 'generator' ? [1, 0.7, 0.7] : [2, 0.4, 2];
      const r90 = rot % 2 === 1;
      const hw = (r90 ? sz[2] : sz[0]) / 2, hd = (r90 ? sz[0] : sz[2]) / 2;
      if (def.station !== 'farm' && def.station !== 'bed' && def.station !== 'campfire') boxes.push(col.addBox(cx - hw, y, cz - hd, cx + hw, y + sz[1], cz + hd, { tag: 'station', ref: p, navCost: 20 }));
      if (def.station === 'crate') {
        p.container = { id: 100000 + p.id, kind: 'storage', label: 'Wooden Crate', pos: new THREE.Vector3(cx, y + 0.5, cz), inv: new Inventory(60), generated: true, half: new THREE.Vector3(hw + 0.05, 0.6, hd + 0.05), building: null };
      }
      if (def.station === 'campfire') {
        const anchor = obj.userData.fireAnchor as THREE.Object3D | undefined;
        const fp = new THREE.Vector3(cx, y + 0.15, cz); if (anchor) { obj.updateMatrixWorld(true); anchor.getWorldPosition(fp); }
        p.fire = { fuel: 0, lit: false, emitter: this.lights.add({ pos: fp.clone().setY(fp.y + 0.6), color: new THREE.Color(0xff8a3a), intensity: 0, distance: 14, on: false, flicker: 0.35 }), pos: fp };
      }
      if (def.station === 'farm') p.farm = { crop: null, growth: 0, water: 0.5, plant: null };
      if (def.station === 'rain') p.rain = { water: 2 };
      if (def.station === 'generator') p.gen = { fuel: 0, on: false };
      this.tilesMap.set(`${tx},${tz}`, [...(this.tilesMap.get(`${tx},${tz}`) ?? []), p]);
    }
    if (def.kind === 'foundation' || def.kind === 'roof' || def.kind === 'stairs' || def.kind === 'tower') this.tilesMap.set(`${tx},${tz}`, [...(this.tilesMap.get(`${tx},${tz}`) ?? []), p]);
    obj!.traverse(o => { if ((o as THREE.Mesh).isMesh) { o.castShadow = true; o.receiveShadow = true; } });
    p.obj = obj!; p.y = y; p.cx = cx; p.cz = cz;
    if (load) { if (load.hp !== undefined) p.hp = load.hp; }
    this.group.add(obj!);
    this.pieces.push(p);
    return p;
  }

  roofHeight(tx: number, tz: number) {
    // roof sits on top of walls around this tile: wall base + 3
    let best = -Infinity;
    for (const [ttx, ttz, ax] of [[tx, tz, 0], [tx, tz - 1, 0], [tx, tz, 1], [tx - 1, tz, 1]] as [number, number, number][]) {
      const w = this.edges.get(Base.edgeKey(ttx, ttz, ax)); if (w) best = Math.max(best, w.y + 3);
    }
    if (best === -Infinity) best = this.tileBaseY(tx, tz) + 3;
    return best;
  }

  remove(p: Piece) {
    p.destroyed = true;
    this.group.remove(p.obj);
    if (p.obj.userData.support) this.group.remove(p.obj.userData.support);
    if (p.door && p.door.pivot !== p.obj) this.group.remove(p.door.pivot);
    for (const b of p.boxes) this.world.col.removeBox(b);
    if (p.door) this.world.col.removeBox(p.door.box);
    if (p.floor) this.world.floors.splice(this.world.floors.indexOf(p.floor), 1);
    if (p.fire) this.lights.remove(p.fire.emitter);
    if (p.farm?.plant) this.group.remove(p.farm.plant);
    for (const [k, v] of this.edges) if (v === p) this.edges.delete(k);
    const key = `${p.tx},${p.tz}`; const arr = this.tilesMap.get(key); if (arr) this.tilesMap.set(key, arr.filter(q => q !== p));
    this.pieces.splice(this.pieces.indexOf(p), 1);
  }

  toggleDoor(p: Piece) {
    if (!p.door) return;
    p.door.open = !p.door.open;
    this.world.col.setSolid(p.door.box, !p.door.open, 30);
    audio.play(p.door.open ? 'door_open' : 'door_close', { pos: new THREE.Vector3(p.cx, p.y + 1, p.cz) });
  }

  damage(p: Piece, dmg: number) {
    p.hp -= dmg;
    if (p.hp <= 0 && !p.destroyed) { audio.play('wood_break', { pos: new THREE.Vector3(p.cx, p.y + 1, p.cz) }); this.remove(p); bus.emit('notify', `💥 ${p.def.name} destroyed!`, 'bad'); }
  }

  update(dt: number, dtH: number, ctx: { raining: number; particles: any; playerPos: THREE.Vector3; temp?: number }) {
    for (const p of this.pieces) {
      if (p.door) {
        const target = p.door.open ? Math.PI / 2 : 0;
        p.door.angle += (target - p.door.angle) * Math.min(1, dt * 8);
        if (p.def.kind === 'gate') { const l = p.obj.getObjectByName('gateLeafL'), r = p.obj.getObjectByName('gateLeafR'); if (l) l.rotation.y = -p.door.angle; if (r) r.rotation.y = p.door.angle; }
        else p.door.pivot.rotation.y = (p.axis === 1 ? -Math.PI / 2 : 0) + p.door.angle;
      }
      if (p.fire) {
        if (p.fire.lit) {
          p.fire.fuel -= dtH * 60; // fuel in game-minutes
          if (p.fire.fuel <= 0) { p.fire.lit = false; p.fire.fuel = 0; }
          if (ctx.raining > 0.6 && Math.random() < dtH * 0.5) { p.fire.lit = false; bus.emit('notify', 'The rain put out your campfire.', 'bad'); }
          if (ctx.particles && Math.abs(p.cx - ctx.playerPos.x) < 70 && Math.abs(p.cz - ctx.playerPos.z) < 70) ctx.particles.fire(p.fire.pos, 1);
        }
        p.fire.emitter.on = p.fire.lit; p.fire.emitter.intensity = p.fire.lit ? 45 : 0;
      }
      if (p.rain) p.rain.water = Math.min(10, p.rain.water + dtH * ctx.raining * 2);
      if (p.gen) { if (p.gen.on) { p.gen.fuel = Math.max(0, p.gen.fuel - dtH); if (p.gen.fuel <= 0) p.gen.on = false; } const l = p.obj.getObjectByName('genLamp') as THREE.Mesh | undefined; if (l) (l.material as THREE.MeshStandardMaterial) = getMaterial(p.gen.on ? 'emissiveGreen' : 'emissiveRed'); }
      if (p.farm && p.farm.crop) {
        p.farm.water = Math.max(0, Math.min(1, p.farm.water + dtH * (ctx.raining * 0.5 - 0.02)));
        if (p.farm.water > 0.05 && (ctx.temp ?? 10) > 3) p.farm.growth = Math.min(1, p.farm.growth + dtH / 60);
        const stage = Math.min(3, Math.floor(p.farm.growth * 4));
        const want = `crop_stage${stage}` as any;
        if (!p.farm.plant || p.farm.plant.userData.stage !== stage) {
          if (p.farm.plant) this.group.remove(p.farm.plant);
          const pl = buildPiece(want); pl.position.set(p.cx, p.y + 0.3, p.cz); pl.userData.stage = stage; this.group.add(pl); p.farm.plant = pl;
        }
      }
    }
  }

  // ---- barricades on town doors / windows
  barricade(target: Door | Win, carpentry: number, scene: THREE.Group) {
    const add = 60 * (1 + carpentry * 0.15);
    target.barricade = Math.min(add * 4, target.barricade + add);
    const isDoor = 'pivot' in target;
    if (!target.barricadeMesh) {
      const m = buildPiece(isDoor ? 'barricade_door' : 'barricade_window');
      m.position.set(target.cx, isDoor ? target.y : (target as Win).y + 0.0, target.cz);
      if (!isDoor) m.position.y = (target as Win).y + 0.6 - 1.5;
      m.rotation.y = target.axis === 'z' ? Math.PI / 2 : 0;
      m.traverse(o => { if ((o as THREE.Mesh).isMesh) o.castShadow = true; });
      scene.add(m); target.barricadeMesh = m;
    }
    const lvl = Math.ceil(target.barricade / add);
    target.barricadeMesh.scale.setScalar(1);
    target.barricadeMesh.userData.level = lvl;
    // barricaded openings block zombies until destroyed
    this.world.col.setSolid(target.box, true, 45);
    target.box.tag = isDoor ? 'door' : 'window';
  }

  toJSON() {
    return this.pieces.map(p => ({ id: p.def.id, tx: p.tx, tz: p.tz, axis: p.axis, rot: p.rot, y: p.y, hp: p.hp, open: p.door?.open ?? false, inv: p.container?.inv.stacks ?? null, fire: p.fire ? [p.fire.fuel, p.fire.lit] : null, farm: p.farm ? [p.farm.crop, p.farm.growth, p.farm.water] : null, rain: p.rain?.water ?? null, gen: p.gen ? [p.gen.fuel, p.gen.on] : null }));
  }
  load(arr: any[]) {
    for (const p of [...this.pieces]) this.remove(p);
    // foundations first so walls sit on them
    const order = (k: string) => ['foundation', 'stairs', 'tower', 'wall', 'window_wall', 'door_wall', 'gate', 'station', 'roof'].indexOf(k);
    arr.sort((a, b) => order(PIECES[a.id]?.kind) - order(PIECES[b.id]?.kind));
    for (const s of arr) {
      if (!PIECES[s.id]) continue;
      const p = this.place(s.id, s.tx, s.tz, s.axis, s.rot, { y: s.y, hp: s.hp });
      if (s.open && p.door) this.toggleDoor(p);
      if (s.inv && p.container) p.container.inv.load(s.inv);
      if (s.fire && p.fire) { p.fire.fuel = s.fire[0]; p.fire.lit = s.fire[1]; }
      if (s.farm && p.farm) { p.farm.crop = s.farm[0]; p.farm.growth = s.farm[1]; p.farm.water = s.farm[2]; }
      if (s.rain !== null && p.rain) p.rain.water = s.rain;
      if (s.gen && p.gen) { p.gen.fuel = s.gen[0]; p.gen.on = s.gen[1]; }
    }
  }
}
