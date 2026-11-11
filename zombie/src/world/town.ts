// Procedural town: enterable buildings with rooms, doors, windows, furniture & loot containers, plus street dressing.
import * as THREE from 'three';
import { RNG } from '../core/util';
import { Layout, Plot, distToSeg } from './layout';
import { Terrain } from './terrain';
import { CollisionWorld, Box, NAV_SOLID } from './collision';
import { GeoBag, mBox, gableGeo } from './bgeo';
import { PropBatcher } from './propbatch';
import { buildProp, PropId, PROP_SIZES, buildCar, CarVariant } from '../models/props';
import { getMaterial, getCarPaint, MaterialName } from '../render/materials';
import { Inventory } from '../items/inventory';
import { ContainerKind, CONTAINER_LABEL, containerCapacity } from '../items/loot';

export interface Door {
  id: number; pivot: THREE.Group; open: boolean; angle: number; target: number; box: Box; hp: number; maxHp: number;
  barricade: number; barricadeMesh: THREE.Object3D | null; axis: 'x' | 'z'; cx: number; cz: number; y: number; width: number; building: Building | null; locked: boolean; dir: number;
}
export interface Win {
  id: number; glass: THREE.Mesh; broken: boolean; box: Box; barricade: number; barricadeMesh: THREE.Object3D | null; axis: 'x' | 'z'; cx: number; cz: number; y: number; building: Building | null;
}
export interface Container { id: number; kind: ContainerKind; label: string; pos: THREE.Vector3; inv: Inventory; generated: boolean; half: THREE.Vector3; building: Building | null; searched?: boolean }
export interface TownCar { id: number; variant: CarVariant; color: number; yaw: number; pos: THREE.Vector3; size: [number, number, number]; box: Box; container: Container; entry: any; fuel: number; keys: boolean; condition: number; taken: boolean; kind: 'towncar' }
export interface Building { id: number; kind: string; x: number; z: number; w: number; d: number; floorY: number; ceilY: number; group: THREE.Group; interior: THREE.Group; doors: Door[]; windows: Win[]; containers: Container[]; roof: THREE.Object3D | null; zombieSpawns: THREE.Vector3[]; lamps: THREE.Vector3[] }

const WALL_H = 2.9, WT = 0.2;
interface Opening { at: number; w: number; bottom: number; top: number; type: 'door' | 'window' | 'gap' }

export class Town {
  buildings: Building[] = [];
  doors: Door[] = [];
  windows: Win[] = [];
  containers: Container[] = [];
  streetLamps: THREE.Vector3[] = [];
  group = new THREE.Group();
  props = new PropBatcher();
  carBoxes: TownCar[] = [];
  private rng: RNG;
  private nextId = 1;
  private occupied: { x0: number; z0: number; x1: number; z1: number }[] = [];

  constructor(private layout: Layout, private terrain: Terrain, private col: CollisionWorld, seed: number) {
    this.rng = new RNG(seed + 7);
    this.group.add(this.props.group);
    for (const p of layout.plots) this.occupied.push({ x0: p.x - p.w / 2 - 1, z0: p.z - p.d / 2 - 1, x1: p.x + p.w / 2 + 1, z1: p.z + p.d / 2 + 1 });
    for (const p of layout.plots) this.buildPlot(p);
    this.dressStreets();
    this.props.finalize();
  }

  isOccupied(x: number, z: number, m = 0) { return this.occupied.some(o => x > o.x0 - m && x < o.x1 + m && z > o.z0 - m && z < o.z1 + m); }

  /** building whose footprint contains point */
  buildingAt(x: number, z: number): Building | null {
    for (const b of this.buildings) if (Math.abs(x - b.x) < b.w / 2 && Math.abs(z - b.z) < b.d / 2) return b;
    return null;
  }

  private prop(id: PropId, x: number, y: number, z: number, ry: number, maxDist = 70, shadow = true) {
    const key = 'p_' + id;
    if (!this.props.has(key)) this.props.register(key, buildProp(id), undefined, shadow);
    this.props.add(key, x, y, z, ry, maxDist);
  }

  private addContainer(kind: ContainerKind, x: number, y: number, z: number, hx: number, hy: number, hz: number, b: Building | null) {
    const c: Container = { id: this.nextId++, kind, label: CONTAINER_LABEL[kind], pos: new THREE.Vector3(x, y, z), inv: new Inventory(containerCapacity(kind)), generated: false, half: new THREE.Vector3(hx, hy, hz), building: b };
    this.containers.push(c); b?.containers.push(c);
    return c;
  }

  // ---------------------------------------------------------------- buildings
  private buildPlot(p: Plot) {
    const rng = this.rng;
    // ground height: max over the footprint
    let gmax = -Infinity;
    for (let i = -1; i <= 1; i++) for (let j = -1; j <= 1; j++) gmax = Math.max(gmax, this.terrain.heightAt(p.x + i * p.w / 2, p.z + j * p.d / 2));
    const commercial = !['house', 'house_l', 'cabin', 'barn'].includes(p.kind);
    const floorY = gmax + (commercial ? 0.15 : 0.45);
    const b: Building = { id: this.nextId++, kind: p.kind, x: p.x, z: p.z, w: p.w, d: p.d, floorY, ceilY: floorY + WALL_H, group: new THREE.Group(), interior: new THREE.Group(), doors: [], windows: [], containers: [], roof: null, zombieSpawns: [], lamps: [] };
    this.buildings.push(b);
    const ext = new GeoBag(), int = new GeoBag();
    // pick style
    let extMat: MaterialName;
    if (p.kind === 'cabin' || p.kind === 'barn') extMat = p.kind === 'barn' ? 'woodDark' : 'wood';
    else if (commercial) extMat = rng.pick(['brick', 'brickRed', 'concrete', 'brick'] as MaterialName[]);
    else extMat = rng.pick(['sidingWhite', 'sidingBlue', 'sidingGreen', 'sidingYellow', 'sidingWhite', 'brickRed'] as MaterialName[]);
    const intMat0: MaterialName = p.kind === 'cabin' || p.kind === 'barn' ? 'wood' : p.kind === 'warehouse' || p.kind === 'warehouse_small' || p.kind === 'garage_shop' ? 'concrete' : 'plaster';
    const intMat = (intMat0 === 'plaster' ? 'plaster:' + rng.pick(['d8ccb0', 'c9cfc0', 'd8c4b4', 'bfc6cc', 'cdbf9a']) : intMat0) as MaterialName;
    const floorMat: MaterialName = ['warehouse', 'warehouse_small', 'garage_shop', 'barn'].includes(p.kind) ? 'concrete' : commercial ? 'tile' : 'woodFloor';
    const x0 = p.x - p.w / 2, x1 = p.x + p.w / 2, z0 = p.z - p.d / 2, z1 = p.z + p.d / 2;
    const H = p.kind === 'warehouse' || p.kind === 'barn' || p.kind === 'school' || p.kind === 'church' ? 5.5 : p.kind === 'supermarket' ? 4.2 : WALL_H;
    b.ceilY = floorY + H;

    // foundation + floor
    const fh = floorY - (gmax - 1.2);
    ext.box('concrete', p.x, floorY - fh / 2 - 0.05, p.z, p.w + 0.1, fh, p.d + 0.1);
    int.box(floorMat, p.x, floorY - 0.04, p.z, p.w - 0.2, 0.08, p.d - 0.2);
    // ceiling
    int.box(intMat === 'concrete' ? 'metalDark' : 'plaster:e8e4dc', p.x, floorY + H + 0.03, p.z, p.w - 0.2, 0.06, p.d - 0.2);

    // exterior walls with openings
    const face = p.face;
    const frontAxisLen = face === 0 || face === 2 ? p.w : p.d;
    const doorOff = commercial ? 0 : (rng.chance(0.5) ? -1 : 1) * Math.min(frontAxisLen / 2 - 1.6, frontAxisLen * 0.22);
    const wide = p.kind === 'warehouse' || p.kind === 'garage_shop' || p.kind === 'barn';
    const edges: { a: [number, number]; b: [number, number]; side: 0 | 1 | 2 | 3 }[] = [
      { a: [x0, z1], b: [x1, z1], side: 0 }, { a: [x1, z0], b: [x1, z1], side: 1 }, { a: [x0, z0], b: [x1, z0], side: 2 }, { a: [x0, z0], b: [x0, z1], side: 3 },
    ];
    for (const e of edges) {
      const len = Math.hypot(e.b[0] - e.a[0], e.b[1] - e.a[1]);
      const ops: Opening[] = [];
      const isFront = e.side === face, isBack = e.side === (face + 2) % 4;
      if (isFront) {
        if (wide) ops.push({ at: len / 2, w: 4.2, bottom: 0, top: 3.6, type: 'gap' });
        else ops.push({ at: len / 2 + doorOff, w: commercial ? 1.8 : 1.05, bottom: 0, top: 2.2, type: 'door' });
      }
      if (isBack && (commercial || rng.chance(0.6)) && len > 6) ops.push({ at: len * (rng.chance(0.5) ? 0.3 : 0.7), w: 1.0, bottom: 0, top: 2.15, type: 'door' });
      // windows
      const storefront = isFront && (p.kind === 'supermarket' || p.kind === 'pharmacy' || p.kind === 'diner' || p.kind === 'hardware' || p.kind === 'gas_station');
      const spacing = storefront ? 3.2 : 3.4;
      for (let t = 1.8; t < len - 1.2; t += spacing) {
        if (ops.some(o => Math.abs(o.at - t) < o.w / 2 + 1.1)) continue;
        if (p.kind === 'warehouse' && rng.chance(0.5)) continue;
        const ww = storefront ? 2.6 : 1.1;
        if (storefront && t + ww / 2 > len - 0.4) continue;
        ops.push({ at: t, w: ww, bottom: storefront ? 0.5 : 0.95, top: storefront ? 2.6 : 2.15, type: 'window' });
      }
      this.wall(ext, int, b, e.a, e.b, ops, floorY, H, extMat, intMat, true);
    }

    // interior layout
    this.interior(b, p, int, floorY, H, intMat);

    // roof
    const roofG = new GeoBag();
    if (commercial && p.kind !== 'church' && p.kind !== 'barn') {
      roofG.box('roofMetal', p.x, floorY + H + 0.15, p.z, p.w + 0.3, 0.3, p.d + 0.3);
      roofG.box(extMat, p.x, floorY + H + 0.5, p.z + p.d / 2 + 0.05, p.w + 0.3, 0.7, 0.25);
      roofG.box(extMat, p.x, floorY + H + 0.5, p.z - p.d / 2 - 0.05, p.w + 0.3, 0.7, 0.25);
      roofG.box(extMat, p.x + p.w / 2 + 0.05, floorY + H + 0.5, p.z, 0.25, 0.7, p.d + 0.3);
      roofG.box(extMat, p.x - p.w / 2 - 0.05, floorY + H + 0.5, p.z, 0.25, 0.7, p.d + 0.3);
      // rooftop HVAC
      if (p.w > 14) { roofG.box('metal', p.x + 2, floorY + H + 0.9, p.z - 1, 2, 1.2, 1.5); roofG.box('metalDark', p.x - 4, floorY + H + 0.6, p.z + 2, 1, 0.6, 1); }
      // sign over storefront
      const sz = face === 0 ? z1 + 0.15 : face === 2 ? z0 - 0.15 : p.z, sx = face === 1 ? x1 + 0.15 : face === 3 ? x0 - 0.15 : p.x;
      roofG.box('factoryGrey', sx, floorY + H - 0.35, sz, face === 0 || face === 2 ? Math.min(p.w * 0.6, 10) : 0.2, 0.8, face === 0 || face === 2 ? 0.2 : Math.min(p.d * 0.6, 10));
    } else {
      // gable roof along longer axis
      const alongX = p.w >= p.d;
      const span = alongX ? p.d : p.w, len = (alongX ? p.w : p.d) + 0.8;
      const rise = span * (p.kind === 'barn' ? 0.45 : 0.32);
      const slopeLen = Math.hypot(span / 2 + 0.45, rise);
      const ang = Math.atan2(rise, span / 2 + 0.45);
      const roofMat: MaterialName = p.kind === 'barn' || p.kind === 'cabin' ? 'roofMetal' : 'roofShingle';
      const top = floorY + H;
      for (const s of [-1, 1]) {
        const g = mBox(alongX ? len : slopeLen, 0.14, alongX ? slopeLen : len);
        const off = (span / 2 + 0.45) / 2;
        if (alongX) roofG.add(roofMat, g, p.x, top + rise / 2 + 0.05, p.z + s * off, 0, s * ang);
        else roofG.add(roofMat, g, p.x + s * off, top + rise / 2 + 0.05, p.z, 0, 0, -s * ang);
      }
      // gable ends
      const gab = gableGeo(span, rise, 0.18);
      for (const s of [-1, 1]) {
        if (alongX) roofG.add(extMat, gab, p.x + s * (p.w / 2 - 0.05), top, p.z, Math.PI / 2);
        else roofG.add(extMat, gab, p.x, top, p.z + s * (p.d / 2 - 0.05), 0);
      }
      // chimney
      if (p.kind !== 'barn' && rng.chance(0.7)) roofG.box('brickRed', p.x + (alongX ? p.w * 0.25 : 0.6), top + rise * 0.7, p.z + (alongX ? 0.5 : p.d * 0.25), 0.7, rise * 1.2 + 0.8, 0.7);
      // fascia trim
      roofG.box('woodDark', p.x, top - 0.05, p.z + p.d / 2 + 0.06, p.w + 0.2, 0.18, 0.06);
      roofG.box('woodDark', p.x, top - 0.05, p.z - p.d / 2 - 0.06, p.w + 0.2, 0.18, 0.06);
    }
    // porch / steps in front of door
    if (!commercial || p.kind === 'diner') {
      const fd = face === 0 ? [0, 1] : face === 2 ? [0, -1] : face === 1 ? [1, 0] : [-1, 0];
      const along = face === 0 || face === 2;
      const px = p.x + (along ? doorOff : fd[0] * (p.w / 2 + 1.0)), pz = p.z + (along ? fd[1] * (p.d / 2 + 1.0) : doorOff);
      const deckH = floorY - gmax;
      ext.box('wood', px, gmax + deckH / 2 - 0.05, pz, along ? 3 : 2, deckH, along ? 2 : 3);
      // porch roof posts
      if (p.kind !== 'cabin') {
        for (const s of [-1, 1]) ext.box('woodDark', px + (along ? s * 1.4 : fd[0] * 0.9), floorY + 1.2, pz + (along ? fd[1] * 0.9 : s * 1.4), 0.12, 2.4, 0.12);
        ext.box('roofShingle', px, floorY + 2.45, pz, along ? 3.4 : 2.2, 0.1, along ? 2.2 : 3.4);
      }
    }

    const exterior = ext.build(true, true);
    const interior = int.build(false, true);
    const roof = roofG.build(true, true);
    b.group.add(exterior); b.group.add(roof); b.roof = roof;
    b.interior.add(interior);
    b.group.add(b.interior);
    this.group.add(b.group);
    // whole-footprint collider for things above wall height is not needed; floor height handled by groundY
  }

  private wall(ext: GeoBag, int: GeoBag, b: Building, a: [number, number], bb: [number, number], ops: Opening[], y0: number, H: number, extMat: MaterialName, intMat: MaterialName, exterior: boolean) {
    const alongX = Math.abs(bb[1] - a[1]) < 0.01;
    const len = alongX ? bb[0] - a[0] : bb[1] - a[1];
    ops.sort((p, q) => p.at - q.at);
    // outward normal for exterior walls
    const cx = b.x, cz = b.z;
    const nx = alongX ? 0 : Math.sign(a[0] - cx), nz = alongX ? Math.sign(a[1] - cz) : 0;
    const seg = (t0: number, t1: number, yb: number, yt: number) => {
      if (t1 - t0 < 0.01 || yt - yb < 0.01) return;
      const mid = (t0 + t1) / 2, L = t1 - t0, h = yt - yb;
      const px = alongX ? a[0] + mid : a[0], pz = alongX ? a[1] : a[1] + mid;
      if (exterior) {
        ext.box(extMat, px + nx * 0.05, y0 + yb + h / 2, pz + nz * 0.05, alongX ? L + (t0 === 0 || t1 === len ? 0.1 : 0) : 0.1, h, alongX ? 0.1 : L + (t0 === 0 || t1 === len ? 0.1 : 0));
        int.box(intMat, px - nx * 0.05, y0 + yb + h / 2, pz - nz * 0.05, alongX ? L : 0.1, h, alongX ? 0.1 : L);
      } else {
        int.box(intMat, px, y0 + yb + h / 2, pz, alongX ? L : WT * 0.6, h, alongX ? WT * 0.6 : L);
      }
      if (yb < 1.8) {
        const hw = alongX ? L / 2 : WT / 2, hd = alongX ? WT / 2 : L / 2;
        this.col.addBox(px - hw, y0 + yb, pz - hd, px + hw, y0 + yt, pz + hd, { tag: 'wall' });
      }
    };
    let t = 0;
    for (const o of ops) {
      const s = o.at - o.w / 2, e = o.at + o.w / 2;
      seg(t, s, 0, H);
      seg(s, e, o.top, H);
      if (o.bottom > 0) seg(s, e, 0, o.bottom);
      const px = alongX ? a[0] + o.at : a[0], pz = alongX ? a[1] : a[1] + o.at;
      if (o.type === 'door') this.makeDoor(b, px, pz, y0, o.w, alongX ? 'x' : 'z', exterior ? (nx || nz) : 1, ext, exterior);
      else if (o.type === 'window') this.makeWindow(b, px, pz, y0 + o.bottom, o.w, o.top - o.bottom, alongX ? 'x' : 'z', ext);
      t = e;
    }
    seg(t, len, 0, H);
  }

  private makeDoor(b: Building, x: number, z: number, y: number, w: number, axis: 'x' | 'z', outward: number, ext: GeoBag, exterior: boolean) {
    this.gaps.push({ x, z });
    // frame
    const fw = 0.08;
    if (exterior) {
      for (const s of [-1, 1]) ext.box('woodDark', x + (axis === 'x' ? s * (w / 2 + fw / 2) : 0), y + 1.1, z + (axis === 'z' ? s * (w / 2 + fw / 2) : 0), axis === 'x' ? fw : 0.26, 2.2, axis === 'x' ? 0.26 : fw);
      ext.box('woodDark', x, y + 2.24, z, axis === 'x' ? w + 0.24 : 0.26, 0.08, axis === 'x' ? 0.26 : w + 0.24);
    }
    if (!exterior && this.rng.chance(0.5)) return; // open doorway
    const pivot = new THREE.Group();
    const leafW = w > 1.4 ? w : w - 0.04;
    const leafMat = w > 1.4 ? 'glassDirty' : exterior ? 'woodDark' : 'wood';
    const leaf = new THREE.Mesh(mBox(leafW, 2.15, 0.06), getMaterial(leafMat as MaterialName));
    leaf.castShadow = true; leaf.receiveShadow = true;
    leaf.position.set(leafW / 2, 1.08, 0);
    pivot.add(leaf);
    if (w <= 1.4) {
      const knob = new THREE.Mesh(new THREE.SphereGeometry(0.035, 8, 6), getMaterial('chrome'));
      knob.position.set(leafW - 0.1, 1.0, 0.05); pivot.add(knob);
      const knob2 = knob.clone(); knob2.position.z = -0.05; pivot.add(knob2);
    } else {
      const fr = new THREE.Mesh(mBox(leafW, 2.15, 0.08), getMaterial('metalDark')); fr.scale.set(1.0, 1.0, 0.5); fr.position.copy(leaf.position); fr.position.y = 0.06; fr.scale.y = 0.05; pivot.add(fr);
    }
    // hinge on the negative end of the opening
    if (axis === 'x') { pivot.position.set(x - w / 2, y, z); pivot.rotation.y = 0; }
    else { pivot.position.set(x, y, z - w / 2); pivot.rotation.y = -Math.PI / 2; }
    b.group.add(pivot);
    const hw = axis === 'x' ? w / 2 : 0.08, hd = axis === 'x' ? 0.08 : w / 2;
    const d: Door = { id: this.nextId++, pivot, open: false, angle: 0, target: 0, box: null as any, hp: exterior ? 220 : 120, maxHp: exterior ? 220 : 120, barricade: 0, barricadeMesh: null, axis, cx: x, cz: z, y, width: w, building: b, locked: exterior && this.rng.chance(0.15), dir: outward };
    d.box = this.col.addBox(x - hw, y, z - hd, x + hw, y + 2.2, z + hd, { tag: 'door', ref: d, navCost: 30 });
    if (!exterior && this.rng.chance(0.4)) { d.open = true; d.target = d.angle = Math.PI / 2 * 0.9; this.col.setSolid(d.box, false); this.applyDoor(d); }
    b.doors.push(d); this.doors.push(d);
  }
  applyDoor(d: Door) {
    const base = d.axis === 'x' ? 0 : -Math.PI / 2;
    d.pivot.rotation.y = base + d.angle * d.dir;
  }

  private makeWindow(b: Building, x: number, z: number, y: number, w: number, h: number, axis: 'x' | 'z', ext: GeoBag) {
    // frame & sill
    const t = 0.24;
    ext.box('plasticWhite', x, y - 0.04, z, axis === 'x' ? w + 0.2 : t + 0.08, 0.08, axis === 'x' ? t + 0.08 : w + 0.2);
    ext.box('plasticWhite', x, y + h + 0.03, z, axis === 'x' ? w + 0.12 : t, 0.06, axis === 'x' ? t : w + 0.12);
    for (const s of [-1, 1]) ext.box('plasticWhite', x + (axis === 'x' ? s * (w / 2 + 0.03) : 0), y + h / 2, z + (axis === 'z' ? s * (w / 2 + 0.03) : 0), axis === 'x' ? 0.06 : t, h, axis === 'x' ? t : 0.06);
    if (w < 2) ext.box('plasticWhite', x, y + h / 2, z, axis === 'x' ? 0.05 : 0.06, h, axis === 'x' ? 0.06 : 0.05);
    const glass = new THREE.Mesh(new THREE.PlaneGeometry(w, h), getMaterial('glass'));
    glass.position.set(x, y + h / 2, z); if (axis === 'z') glass.rotation.y = Math.PI / 2;
    b.group.add(glass);
    const broken = this.rng.chance(0.18);
    const hw = axis === 'x' ? w / 2 : 0.1, hd = axis === 'x' ? 0.1 : w / 2;
    const win: Win = { id: this.nextId++, glass, broken, box: null as any, barricade: 0, barricadeMesh: null, axis, cx: x, cz: z, y, building: b };
    win.box = this.col.addBox(x - hw, b.floorY, z - hd, x + hw, y + h, z + hd, { tag: broken ? 'window_broken' : 'window', ref: win, navCost: broken ? 12 : 40 });
    if (broken) glass.visible = false;
    b.windows.push(win); this.windows.push(win);
  }

  // ---------------------------------------------------------------- interiors
  private interior(b: Building, p: Plot, int: GeoBag, y: number, H: number, intMat: MaterialName) {
    const rng = this.rng;
    const x0 = p.x - p.w / 2 + WT, x1 = p.x + p.w / 2 - WT, z0 = p.z - p.d / 2 + WT, z1 = p.z + p.d / 2 - WT;
    type Room = { x0: number; x1: number; z0: number; z1: number; type: string };
    const rooms: Room[] = [];
    const frontIsZ = p.face === 0 || p.face === 2;
    const frontSign = p.face === 0 || p.face === 1 ? 1 : -1;
    const partition = (r: Room, alongX: boolean, at: number, doorAt: number) => {
      // wall parallel to X (alongX) at z=at, from r.x0..r.x1
      const ops: Opening[] = [{ at: doorAt, w: 1.0, bottom: 0, top: 2.15, type: 'door' }];
      if (alongX) this.wall(int, int, b, [r.x0, at], [r.x1, at], ops, y, H, intMat, intMat, false);
      else this.wall(int, int, b, [at, r.z0], [at, r.z1], ops, y, H, intMat, intMat, false);
    };
    const k = p.kind;
    if (k === 'house' || k === 'house_l') {
      // split front/back
      if (frontIsZ) {
        const mid = p.z + (rng.range(-0.1, 0.15)) * p.d;
        const front: Room = { x0, x1, z0: frontSign > 0 ? mid : z0, z1: frontSign > 0 ? z1 : mid, type: 'living' };
        const back: Room = { x0, x1, z0: frontSign > 0 ? z0 : mid, z1: frontSign > 0 ? mid : z1, type: 'back' };
        partition({ x0, x1, z0, z1, type: '' }, true, mid, (x1 - x0) * rng.range(0.3, 0.7));
        const midX = p.x + rng.range(-0.15, 0.15) * p.w;
        partition({ x0, x1: x1, z0: back.z0, z1: back.z1, type: '' }, false, midX, (back.z1 - back.z0) / 2);
        rooms.push(front, { ...back, x1: midX, type: 'kitchen' }, { ...back, x0: midX, type: rng.chance(0.5) ? 'bedroom' : 'bath_bed' });
        if (k === 'house_l') { front.x1 = p.x; partition({ x0, x1, z0: front.z0, z1: front.z1, type: '' }, false, p.x, (front.z1 - front.z0) / 2); rooms.push({ ...front, x0: p.x, x1, type: 'bedroom' }); }
      } else {
        const mid = p.x + rng.range(-0.1, 0.15) * p.w;
        const front: Room = { x0: frontSign > 0 ? mid : x0, x1: frontSign > 0 ? x1 : mid, z0, z1, type: 'living' };
        const back: Room = { x0: frontSign > 0 ? x0 : mid, x1: frontSign > 0 ? mid : x1, z0, z1, type: 'back' };
        partition({ x0, x1, z0, z1, type: '' }, false, mid, (z1 - z0) * rng.range(0.3, 0.7));
        const midZ = p.z;
        partition({ x0: back.x0, x1: back.x1, z0, z1, type: '' }, true, midZ, (back.x1 - back.x0) / 2);
        rooms.push(front, { ...back, z1: midZ, type: 'kitchen' }, { ...back, z0: midZ, type: 'bedroom' });
      }
    } else if (k === 'cabin') {
      rooms.push({ x0, x1, z0, z1, type: 'cabin' });
    } else {
      rooms.push({ x0, x1, z0, z1, type: k });
    }
    for (const r of rooms) this.furnish(b, r, y, p);
    // zombie spawn points inside
    for (const r of rooms) b.zombieSpawns.push(new THREE.Vector3((r.x0 + r.x1) / 2, y, (r.z0 + r.z1) / 2));
    // ceiling lamp positions (lit when grid restored)
    for (const r of rooms) b.lamps.push(new THREE.Vector3((r.x0 + r.x1) / 2, y + H - 0.3, (r.z0 + r.z1) / 2));
  }

  /** place a prop against a wall of room r */
  private placeAgainst(b: Building, r: { x0: number; x1: number; z0: number; z1: number }, id: PropId, y: number, placed: { x0: number; x1: number; z0: number; z1: number }[], container?: ContainerKind, wallPref?: number): boolean {
    const [w, h, d] = PROP_SIZES[id];
    for (let attempt = 0; attempt < 14; attempt++) {
      const wall = wallPref ?? this.rng.int(0, 3);
      // wall 0: z1 (face -z), 1: x1 (face -x), 2: z0 (face +z), 3: x0 (face +x)
      let cx: number, cz: number, ry: number, hw: number, hd: number;
      if (wall === 0 || wall === 2) {
        if (r.x1 - r.x0 < w + 0.4) continue;
        cx = this.rng.range(r.x0 + w / 2 + 0.1, r.x1 - w / 2 - 0.1); cz = wall === 0 ? r.z1 - d / 2 - 0.12 : r.z0 + d / 2 + 0.12; ry = wall === 0 ? Math.PI : 0; hw = w / 2; hd = d / 2;
      } else {
        if (r.z1 - r.z0 < w + 0.4) continue;
        cz = this.rng.range(r.z0 + w / 2 + 0.1, r.z1 - w / 2 - 0.1); cx = wall === 1 ? r.x1 - d / 2 - 0.12 : r.x0 + d / 2 + 0.12; ry = wall === 1 ? -Math.PI / 2 : Math.PI / 2; hw = d / 2; hd = w / 2;
      }
      const bb = { x0: cx - hw, x1: cx + hw, z0: cz - hd, z1: cz + hd };
      if (placed.some(o => bb.x0 < o.x1 && bb.x1 > o.x0 && bb.z0 < o.z1 && bb.z1 > o.z0)) continue;
      // keep doorways clear
      if (b.doors.some(dd => Math.abs(dd.cx - cx) < dd.width / 2 + hw + 0.8 && Math.abs(dd.cz - cz) < dd.width / 2 + hd + 0.8) ) continue;
      if (this.doorGapNear(cx, cz, hw, hd)) continue;
      placed.push({ x0: bb.x0 - 0.15, x1: bb.x1 + 0.15, z0: bb.z0 - 0.15, z1: bb.z1 + 0.15 });
      this.prop(id, cx, y, cz, ry, 55, false);
      if (h > 0.5) this.col.addBox(bb.x0, y, bb.z0, bb.x1, y + h, bb.z1, { tag: 'furniture' });
      else this.col.addBox(bb.x0, y, bb.z0, bb.x1, y + h, bb.z1, { tag: 'furniture', nav: false });
      if (container) this.addContainer(container, cx, y + h / 2, cz, hw + 0.05, Math.max(0.3, h / 2), hd + 0.05, b);
      return true;
    }
    return false;
  }
  private gaps: { x: number; z: number }[] = [];
  private doorGapNear(cx: number, cz: number, hw: number, hd: number) {
    return this.gaps.some(g => Math.abs(g.x - cx) < hw + 1.2 && Math.abs(g.z - cz) < hd + 1.2);
  }
  private placeFree(b: Building, r: { x0: number; x1: number; z0: number; z1: number }, id: PropId, y: number, placed: { x0: number; x1: number; z0: number; z1: number }[], cx: number, cz: number, ry: number, container?: ContainerKind) {
    const [w, h, d] = PROP_SIZES[id];
    const rot = Math.abs(Math.sin(ry)) > 0.5;
    const hw = (rot ? d : w) / 2, hd = (rot ? w : d) / 2;
    const bb = { x0: cx - hw, x1: cx + hw, z0: cz - hd, z1: cz + hd };
    if (bb.x0 < r.x0 || bb.x1 > r.x1 || bb.z0 < r.z0 || bb.z1 > r.z1) return false;
    if (placed.some(o => bb.x0 < o.x1 && bb.x1 > o.x0 && bb.z0 < o.z1 && bb.z1 > o.z0)) return false;
    if (b.doors.some(dd => Math.abs(dd.cx - cx) < dd.width / 2 + hw + 0.9 && Math.abs(dd.cz - cz) < dd.width / 2 + hd + 0.9)) return false;
    placed.push({ x0: bb.x0 - 0.3, x1: bb.x1 + 0.3, z0: bb.z0 - 0.3, z1: bb.z1 + 0.3 });
    this.prop(id, cx, y, cz, ry, 55, false);
    this.col.addBox(bb.x0, y, bb.z0, bb.x1, y + h, bb.z1, { tag: 'furniture', nav: h > 0.5 });
    if (container) this.addContainer(container, cx, y + h / 2, cz, hw + 0.05, Math.max(0.3, h / 2), hd + 0.05, b);
    return true;
  }

  private furnish(b: Building, r: { x0: number; x1: number; z0: number; z1: number; type: string }, y: number, p: Plot) {
    const placed: { x0: number; x1: number; z0: number; z1: number }[] = [];
    const A = (id: PropId, c?: ContainerKind, wall?: number) => this.placeAgainst(b, r, id, y, placed, c, wall);
    const rng = this.rng;
    const cx = (r.x0 + r.x1) / 2, cz = (r.z0 + r.z1) / 2;
    switch (r.type) {
      case 'living':
        A('sofa'); A('tv_stand'); if (rng.chance(0.6)) A('armchair'); if (rng.chance(0.7)) A('bookshelf', 'bookshelf'); if (rng.chance(0.5)) A('dresser', 'dresser');
        this.placeFree(b, r, 'table', y, placed, cx + rng.range(-0.5, 0.5), cz + rng.range(-0.5, 0.5), 0);
        break;
      case 'kitchen':
        A('fridge', 'fridge'); A('counter', 'counter'); A('stove', 'counter'); A('sink_counter', 'counter'); if (rng.chance(0.5)) A('counter', 'counter');
        if (this.placeFree(b, r, 'table', y, placed, cx, cz, 0)) { this.placeFree(b, r, 'chair', y, placed, cx + 0.9, cz, -Math.PI / 2); this.placeFree(b, r, 'chair', y, placed, cx - 0.9, cz, Math.PI / 2); }
        break;
      case 'bedroom':
        A('bed'); A('wardrobe', 'wardrobe'); A('dresser', 'dresser'); if (rng.chance(0.4)) A('desk', 'desk');
        break;
      case 'bath_bed':
        A('bed'); A('wardrobe', 'wardrobe'); A('toilet'); A('medical_cabinet', 'medicine');
        break;
      case 'cabin':
        A('bed'); A('stove', 'counter'); A('counter', 'counter'); A('wardrobe', 'wardrobe'); A('tool_rack', 'toolbox'); A('wood_crate_storage', 'crate');
        this.placeFree(b, r, 'table', y, placed, cx, cz, 0);
        break;
      case 'barn':
        for (let i = 0; i < 4; i++) A('pallet'); A('tool_rack', 'toolbox'); A('wood_crate_storage', 'garage'); A('workbench', 'toolbox'); A('barrel'); A('tire_pile');
        break;
      case 'supermarket': {
        A('fridge', 'fridge', 0); A('fridge', 'fridge', 0); A('fridge', 'fridge', 0);
        for (let row = 0; row < 4; row++) {
          const rz = r.z0 + 3 + row * ((r.z1 - r.z0 - 6) / 3);
          for (let col = -1; col <= 1; col++) this.placeFree(b, r, 'shop_shelf', y, placed, cx + col * 5, rz, 0, 'shop_shelf');
        }
        A('checkout', 'counter'); A('checkout'); A('cardboard_boxes', 'crate');
        break;
      }
      case 'pharmacy':
        A('shop_shelf', 'pharmacy'); A('shop_shelf', 'pharmacy'); A('shop_shelf', 'pharmacy'); A('medical_cabinet', 'medicine'); A('medical_cabinet', 'medicine'); A('checkout', 'counter'); A('filing_cabinet', 'cabinet_filing');
        this.placeFree(b, r, 'shop_shelf', y, placed, cx, cz, 0, 'pharmacy');
        break;
      case 'police':
        A('gun_locker', 'gun_locker'); A('locker', 'police_locker'); A('locker', 'police_locker'); A('locker', 'police_locker'); A('filing_cabinet', 'cabinet_filing');
        this.placeFree(b, r, 'desk', y, placed, cx - 3, cz, 0, 'desk'); this.placeFree(b, r, 'desk', y, placed, cx + 3, cz, 0, 'desk'); this.placeFree(b, r, 'desk', y, placed, cx, cz + 3, 0, 'desk');
        A('sandbags'); A('bench');
        break;
      case 'clinic':
        for (let i = 0; i < 5; i++) A('hospital_bed');
        A('medical_cabinet', 'hospital'); A('medical_cabinet', 'hospital'); A('medical_cabinet', 'hospital'); A('desk', 'desk'); A('filing_cabinet', 'cabinet_filing'); A('locker', 'hospital');
        break;
      case 'hardware':
        A('tool_rack', 'toolbox'); A('tool_rack', 'toolbox'); A('shop_shelf', 'garage'); A('shop_shelf', 'garage'); A('workbench', 'toolbox'); A('checkout', 'counter');
        this.placeFree(b, r, 'shop_shelf', y, placed, cx - 3, cz, 0, 'toolbox'); this.placeFree(b, r, 'shop_shelf', y, placed, cx + 3, cz, 0, 'garage');
        break;
      case 'gas_station':
        A('shop_shelf', 'shop_shelf'); A('shop_shelf', 'shop_shelf'); A('fridge', 'fridge'); A('checkout', 'counter'); A('cardboard_boxes', 'crate');
        break;
      case 'diner':
        A('fridge', 'fridge'); A('stove', 'counter'); A('counter', 'counter'); A('counter', 'counter'); A('sink_counter', 'counter');
        for (let i = -1; i <= 1; i++) { if (this.placeFree(b, r, 'table', y, placed, cx + i * 3, cz, 0)) { this.placeFree(b, r, 'chair', y, placed, cx + i * 3 + 0.9, cz, -Math.PI / 2); } }
        break;
      case 'warehouse': case 'warehouse_small': case 'garage_shop': {
        for (let i = 0; i < 6; i++) A('shop_shelf', i % 2 ? 'crate' : 'garage');
        for (let i = 0; i < 5; i++) A('wood_crate_storage', 'crate');
        A('workbench', 'toolbox'); A('pallet'); A('pallet'); A('barrel'); A('tire_pile'); A('cardboard_boxes', 'crate');
        if (r.type === 'garage_shop') { A('tool_rack', 'toolbox'); A('tool_rack', 'toolbox'); }
        this.placeFree(b, r, 'pallet', y, placed, cx, cz, 0);
        break;
      }
      case 'school':
        for (let i = 0; i < 4; i++) for (let j = 0; j < 3; j++) this.placeFree(b, r, 'desk', y, placed, cx - 6 + i * 4, cz - 4 + j * 3.5, 0, rng.chance(0.4) ? 'desk' : undefined);
        A('bookshelf', 'bookshelf'); A('bookshelf', 'bookshelf'); A('locker', 'dresser'); A('locker', 'dresser'); A('filing_cabinet', 'cabinet_filing');
        break;
      case 'church':
        for (let j = 0; j < 5; j++) for (const s of [-1, 1]) this.placeFree(b, r, 'bench', y, placed, cx + s * 2.5, r.z0 + 3 + j * 2.2, 0);
        A('table'); A('cardboard_boxes', 'crate'); A('bookshelf', 'bookshelf');
        break;
      default:
        A('wood_crate_storage', 'crate'); A('shelf', 'garage');
    }
  }

  // ---------------------------------------------------------------- streets
  private dressStreets() {
    const rng = this.rng;
    const roads = this.layout.roads;
    const carVariants: CarVariant[] = ['sedan', 'hatchback', 'pickup', 'van', 'sedan', 'wreck', 'police'];
    const paints = [0x7a1f1a, 0x1f3a5a, 0xc9c4b8, 0x2b2b2b, 0x3e5a3a, 0x8a7a55, 0x5a5f66, 0x9a4a1a, 0xe8e2d0];
    const carTpl = new Map<string, { size: [number, number, number] }>();
    const white = getCarPaint(0xffffff);
    for (const v of carVariants) {
      if (carTpl.has(v)) continue;
      const c = buildCar(v, 0xffffff);
      this.props.register('car_' + v, c.object, white, true);
      carTpl.set(v, { size: c.size });
    }
    for (const r of roads) {
      const L = Math.hypot(r.bx - r.ax, r.bz - r.az); const dx = (r.bx - r.ax) / L, dz = (r.bz - r.az) / L;
      const nx = -dz, nz = dx;
      // street lights & utility poles
      if (r.kind !== 'dirt') {
        for (let t = 15; t < L - 5; t += 32) {
          for (const s of [-1, 1]) {
            if (r.kind === 'highway' && s === 1) continue;
            const px = r.ax + dx * t + nx * s * (r.w / 2 + 1.2), pz = r.az + dz * t + nz * s * (r.w / 2 + 1.2);
            if (this.isOccupied(px, pz) || Math.abs(px) > 380 || Math.abs(pz) > 380) continue;
            const inTown = Math.abs(px) < 160 && Math.abs(pz) < 130;
            if (!inTown && r.kind !== 'highway') continue;
            const py = this.terrain.heightAt(px, pz);
            if (inTown) {
              this.prop('streetlight', px, py, pz, Math.atan2(-nx * s, -nz * s), 260);
              this.col.addCircle(px, pz, 0.15, 8);
              this.streetLamps.push(new THREE.Vector3(px - nx * s * 1.6, py + 7.2, pz - nz * s * 1.6));
            } else {
              this.prop('utility_pole', px, py, pz, Math.atan2(dx, dz), 300);
              this.col.addCircle(px, pz, 0.2, 9);
            }
          }
        }
      }
      // abandoned cars
      const carChance = r.kind === 'highway' ? 0.35 : r.kind === 'street' ? 0.3 : 0.08;
      for (let t = 8; t < L - 8; t += rng.range(9, 22)) {
        if (!rng.chance(carChance)) continue;
        const lane = rng.pick([-1, 1]) * (r.w / 4) * rng.range(0.6, 1.3);
        const px = r.ax + dx * t + nx * lane, pz = r.az + dz * t + nz * lane;
        if (Math.abs(px) > 360 || Math.abs(pz) > 360) continue;
        // skip intersections
        if (roads.some(o => o !== r && distToSeg(px, pz, o) < o.w / 2 + 3)) continue;
        const v = rng.pick(carVariants);
        const yaw = Math.atan2(dx, dz) + (lane > 0 ? Math.PI : 0) + rng.range(-0.35, 0.35);
        const py = this.terrain.heightAt(px, pz);
        const color = v === 'police' ? 0xffffff : v === 'wreck' ? 0x3a3530 : rng.pick(paints);
        const entry = this.props.add('car_' + v, px, py, pz, yaw, 260, color);
        const sz = carTpl.get(v)!.size;
        // approximate oriented car by an AABB of its rotated footprint (shrunk)
        const c = Math.abs(Math.cos(yaw)), s = Math.abs(Math.sin(yaw));
        const hx = (sz[0] * c + sz[2] * s) / 2 * 0.85, hz = (sz[0] * s + sz[2] * c) / 2 * 0.85;
        const box = this.col.addBox(px - hx, py, pz - hz, px + hx, py + sz[1] * 0.85, pz + hz, { tag: 'car' });
        const cont = this.addContainer('car_trunk', px - Math.sin(yaw) * sz[2] * 0.45, py + 0.8, pz - Math.cos(yaw) * sz[2] * 0.45, 0.8, 0.5, 0.8, null);
        cont.label = v === 'police' ? 'Police Cruiser Trunk' : 'Car Trunk';
        if (v === 'police') cont.kind = 'police_locker';
        const rec: TownCar = { id: this.carBoxes.length, variant: v, color, yaw, pos: new THREE.Vector3(px, py, pz), size: sz, box, container: cont, entry, fuel: v === 'wreck' ? 0 : rng.range(0, 0.55), keys: rng.chance(0.25), condition: v === 'wreck' ? 0 : rng.range(30, 90), taken: false, kind: 'towncar' };
        box.ref = rec;
        this.carBoxes.push(rec);
      }
    }
    // yard dressing: fences, mailboxes, trash, dumpsters behind commercial
    for (const b of this.buildings) {
      const fx = b.kind === 'house' || b.kind === 'house_l';
      const gy = this.terrain.heightAt(b.x, b.z);
      if (fx) {
        const back = b.z > 0 === (b.doors[0] ? b.doors[0].cz > b.z : true) ? -1 : 1;
        void back;
        if (rng.chance(0.7)) { const mx = b.x + rng.range(-b.w / 3, b.w / 3), mz = b.z + (b.doors[0] && b.doors[0].cz > b.z ? b.d / 2 + 4.5 : -b.d / 2 - 4.5); this.prop('mailbox', mx, this.terrain.heightAt(mx, mz), mz, 0, 120); }
        if (rng.chance(0.5)) { const tx = b.x + b.w / 2 + 1.2, tz = b.z; this.prop('trash_bags', tx, this.terrain.heightAt(tx, tz), tz, rng.range(0, 6), 80); }
        // wooden fence on the back side
        const doorZ = b.doors[0]?.cz ?? b.z;
        const bz = doorZ > b.z ? b.z - b.d / 2 - 3.5 : b.z + b.d / 2 + 3.5;
        if (rng.chance(0.65)) {
          for (let x = b.x - b.w / 2 - 1; x < b.x + b.w / 2 + 1; x += 2) {
            const fy = this.terrain.heightAt(x + 1, bz);
            this.prop('fence_wood', x + 1, fy, bz, 0, 140);
            this.col.addBox(x, fy, bz - 0.06, x + 2, fy + 1.8, bz + 0.06, { tag: 'fence' });
          }
        }
      } else if (b.kind !== 'cabin') {
        const doorZ = b.doors[0]?.cz ?? b.z;
        const bz = doorZ > b.z ? b.z - b.d / 2 - 2.4 : b.z + b.d / 2 + 2.4;
        const dxp = b.x + rng.range(-b.w / 3, b.w / 3);
        this.prop('dumpster', dxp, this.terrain.heightAt(dxp, bz), bz, 0, 160);
        const sz = PROP_SIZES.dumpster;
        this.col.addBox(dxp - sz[0] / 2, gy, bz - sz[2] / 2, dxp + sz[0] / 2, gy + sz[1], bz + sz[2] / 2, { tag: 'prop' });
        this.addContainer('dumpster', dxp, gy + 1, bz, sz[0] / 2 + 0.1, 0.8, sz[2] / 2 + 0.1, null);
        if (b.kind === 'gas_station') {
          // canopy + pumps toward street
          const sz2 = doorZ > b.z ? b.z + b.d / 2 + 8 : b.z - b.d / 2 - 8;
          const cg = new GeoBag();
          cg.box('factoryGrey', b.x, b.floorY + 4.6, sz2, 14, 0.6, 9);
          for (const sx of [-5.5, 5.5]) for (const sz3 of [-3, 3]) { cg.box('metal', b.x + sx, b.floorY + 2.2, sz2 + sz3, 0.35, 4.4, 0.35); this.col.addCircle(b.x + sx, sz2 + sz3, 0.25, 5); }
          const canopy = cg.build(); this.group.add(canopy);
          for (const sx of [-2.5, 2.5]) { this.prop('gas_pump', b.x + sx, b.floorY, sz2, 0, 160); this.col.addBox(b.x + sx - 0.5, b.floorY, sz2 - 0.35, b.x + sx + 0.5, b.floorY + 1.8, sz2 + 0.35, { tag: 'prop', ref: { kind: 'pump', cx: b.x + sx, cz: sz2 } }); }
          b.lamps.push(new THREE.Vector3(b.x, b.floorY + 4.2, sz2));
        }
      }
    }
    // military checkpoint at the east and west town entrances
    for (const ex of [-168, 168]) {
      const y = this.terrain.heightAt(ex, 0);
      for (let z = -6; z <= 6; z += 3) { if (Math.abs(z) < 2) continue; this.prop('sandbags', ex, this.terrain.heightAt(ex, z), z, Math.PI / 2, 200); this.col.addBox(ex - 0.6, y, z - 1.2, ex + 0.6, y + 1.0, z + 1.2, { tag: 'prop' }); }
      this.prop('road_barrier', ex + 3, y, 0, Math.PI / 2, 200);
      for (let i = 0; i < 2; i++) { const cz = 9 + i * 2; this.prop('wood_crate_storage', ex + 4, this.terrain.heightAt(ex + 4, cz), cz, 0, 120); this.addContainer('gun_locker', ex + 4, y + 0.5, cz, 0.7, 0.5, 0.6, null).label = 'Military Crate'; this.col.addBox(ex + 3.4, y, cz - 0.6, ex + 4.6, y + 1, cz + 0.6, { tag: 'prop' }); }
      for (let i = 0; i < 6; i++) this.prop('traffic_cone', ex + 6 + i * 0.3, this.terrain.heightAt(ex + 6, -4 + i * 1.6), -4 + i * 1.6, 0, 100);
    }
  }
}
export { NAV_SOLID };
