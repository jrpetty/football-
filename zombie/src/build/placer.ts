// Build mode: holographic ghost previews, grid snapping, rotation, belt dragging, dismantling.
import * as THREE from 'three';
import { GRID, tileOf } from '../core/util';
import { MACHINES, PIECES } from '../factory/defs';
import { Factory, Machine } from '../factory/factory';
import { Base, Piece } from './base';
import { World } from '../world/world';
import { buildMachineModel } from '../models/machines';
import { buildPiece } from '../models/buildpieces';
import { buildProp } from '../models/props';
import { buildGeneratorModel } from '../models/lowpoly';
import { buildFluidModel, pipeGeometries } from '../models/fluids';
import { Inventory } from '../items/inventory';
import { audio } from '../audio/Audio';

export class Placer {
  active = false;
  selected: string | null = null;
  dismantle = false;
  rot = 0;
  private ghost: THREE.Object3D | null = null;
  private ghostKey = '';
  private okMat = new THREE.MeshBasicMaterial({ color: 0x4dd2ff, transparent: true, opacity: 0.38, depthWrite: false });
  private badMat = new THREE.MeshBasicMaterial({ color: 0xff4040, transparent: true, opacity: 0.38, depthWrite: false });
  private dismMat = new THREE.MeshBasicMaterial({ color: 0xff7a20, transparent: true, opacity: 0.45, depthWrite: false });
  private grid: THREE.LineSegments;
  private ports = new THREE.Group();
  valid = false; reason = '';
  private target: { tx: number; tz: number; cx: number; cz: number; axis: 0 | 1 } | null = null;
  private dragStart: { tx: number; tz: number } | null = null;
  private beltGhosts: THREE.Object3D[] = [];
  hover: Machine | Piece | null = null;
  private hoverObj: THREE.Object3D | null = null;
  private hoverSaved = new Map<THREE.Mesh, THREE.Material | THREE.Material[]>();

  constructor(private scene: THREE.Scene, private world: World, private factory: Factory, private base: Base) {
    const g = new THREE.BufferGeometry(); const pts: number[] = [];
    for (let i = -6; i <= 6; i++) { pts.push(i * GRID, 0, -6 * GRID, i * GRID, 0, 6 * GRID, -6 * GRID, 0, i * GRID, 6 * GRID, 0, i * GRID); }
    g.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
    this.grid = new THREE.LineSegments(g, new THREE.LineBasicMaterial({ color: 0x9ad8ff, transparent: true, opacity: 0.18, depthWrite: false }));
    this.grid.visible = false; scene.add(this.grid); scene.add(this.ports);
  }

  select(id: string | null) { this.selected = id; this.dismantle = false; this.clearGhost(); this.dragStart = null; }
  setActive(a: boolean) { this.active = a; if (!a) { this.clearGhost(); this.grid.visible = false; this.clearHover(); this.clearBelts(); this.dragStart = null; } }

  private clearGhost() { if (this.ghost) this.scene.remove(this.ghost); this.ghost = null; this.ghostKey = ''; this.ports.clear(); }
  private clearBelts() { for (const b of this.beltGhosts) this.scene.remove(b); this.beltGhosts = []; }
  private clearHover() { for (const [m, mat] of this.hoverSaved) m.material = mat; this.hoverSaved.clear(); this.hoverObj = null; this.hover = null; }

  private makeGhost(id: string): THREE.Object3D {
    let o: THREE.Object3D;
    if (id === 'pipe') { const pg = pipeGeometries(); o = new THREE.Group(); o.add(new THREE.Mesh(pg.node, pg.mat), new THREE.Mesh(pg.post, pg.postMat)); }
    else if (['water_pump', 'oil_pump', 'refinery', 'fuel_gen', 'fluid_tank', 'packager', 'sprinkler'].includes(id)) o = buildFluidModel(id as any);
    else if (MACHINES[id]) o = buildMachineModel(id as any);
    else { const d = PIECES[id]; o = d.custom === 'generator' ? buildGeneratorModel() : d.prop ? buildProp(d.prop) : buildPiece(d.model!); }
    o.traverse(c => { const m = c as THREE.Mesh; if (m.isMesh) { m.material = this.okMat; m.castShadow = false; m.receiveShadow = false; } });
    return o;
  }
  private setGhostMat(o: THREE.Object3D, ok: boolean) { const mat = ok ? this.okMat : this.badMat; o.traverse(c => { const m = c as THREE.Mesh; if (m.isMesh) m.material = mat; }); }

  /** ray-march to find the aimed ground point */
  aimPoint(cam: THREE.Camera, maxD = 14): THREE.Vector3 | null {
    const o = new THREE.Vector3(), d = new THREE.Vector3();
    cam.getWorldPosition(o); cam.getWorldDirection(d);
    let prev = 0;
    for (let t = 0.5; t < maxD; t += 0.25) {
      const p = o.clone().addScaledVector(d, t);
      if (p.y <= this.world.groundY(p.x, p.z, p.y)) {
        let a = prev, b = t;
        for (let i = 0; i < 8; i++) { const m = (a + b) / 2; const q = o.clone().addScaledVector(d, m); if (q.y <= this.world.groundY(q.x, q.z, q.y)) b = m; else a = m; }
        return o.clone().addScaledVector(d, b);
      }
      prev = t;
    }
    return null;
  }

  update(cam: THREE.Camera, inv: Inventory, carpentry: number, engineering: number, creative: boolean) {
    if (!this.active) return;
    const p = this.aimPoint(cam, this.dismantle ? 10 : 16);
    this.grid.visible = !!p && !this.dismantle;
    if (p) this.grid.position.set(Math.round(p.x / GRID) * GRID, p.y + 0.03, Math.round(p.z / GRID) * GRID);
    // dismantle highlighting
    if (this.dismantle) {
      this.clearGhost();
      const o = new THREE.Vector3(), d = new THREE.Vector3(); cam.getWorldPosition(o); cam.getWorldDirection(d);
      let target: Machine | Piece | null = null;
      const hit = this.world.col.raycast(o, d, 10, b => 'minX' in b && (b.tag === 'machine' || b.tag === 'pwall' || b.tag === 'pdoor' || b.tag === 'station'));
      if (hit.obj && 'ref' in hit.obj && hit.obj.ref) target = hit.obj.ref;
      if (!target && p) {
        target = this.factory.machineAtPos(p.x, p.z);
        if (!target) { const pp = this.factory.pipeAt(p.x, p.z); if (pp) target = pp as any; }
        if (!target) target = this.base.pieces.find(q => Math.abs(q.cx - p.x) < 1 && Math.abs(q.cz - p.z) < 1 && (q.def.kind === 'foundation' || q.def.kind === 'station' || q.def.kind === 'stairs' || q.def.kind === 'roof')) ?? null;
      }
      if (target !== this.hover) {
        this.clearHover(); this.hover = target;
        const obj = target ? ('def' in target && (target as Machine).obj !== undefined && 'cx' in target ? ((target as Machine).obj ?? null) : (target as Piece).obj) : null;
        if (obj) { obj.traverse(c => { const m = c as THREE.Mesh; if (m.isMesh) { this.hoverSaved.set(m, m.material); m.material = this.dismMat; } }); this.hoverObj = obj; }
      }
      return;
    }
    const id = this.selected;
    if (!id || !p) { this.clearGhost(); this.valid = false; return; }
    if (this.ghostKey !== id) { this.clearGhost(); this.ghost = this.makeGhost(id); this.ghostKey = id; this.scene.add(this.ghost); }
    const g = this.ghost!;
    const cost = MACHINES[id]?.cost ?? PIECES[id].cost;
    const afford = creative || inv.hasAll(cost);
    let ok = true; this.reason = '';
    const def = PIECES[id];
    if (MACHINES[id]) {
      const tx = tileOf(p.x), tz = tileOf(p.z);
      let [cx, cz] = Factory.centerFor(id, tx, tz, this.rot);
      // snap miners to nearby deposits
      if (id === 'miner' || id === 'miner2' || id === 'oil_pump') { const node = this.world.ores.find(o => Math.hypot(o.x - p.x, o.z - p.z) < 4 && !o.occupied && (id === 'oil_pump') === (o.type === 'f_crude')); if (node) { cx = node.x; cz = node.z; } }
      const res = this.factory.canPlace(id, cx, cz, this.rot);
      if (!res.ok) { ok = false; this.reason = res.reason ?? ''; }
      const y = id === 'belt' || id === 'belt2' || id === 'pipe' ? this.world.groundY(cx, cz) : Math.max(...[[-1, -1], [1, -1], [-1, 1], [1, 1], [0, 0]].map(([a, b]) => this.world.groundY(cx + a * MACHINES[id].size[0] * 0.9, cz + b * MACHINES[id].size[1] * 0.9)));
      g.position.set(cx, y, cz); g.rotation.y = this.rot * Math.PI / 2;
      this.target = { tx: tileOf(cx), tz: tileOf(cz), cx, cz, axis: 0 };
      this.drawPorts(id, cx, cz, y);
      if (this.dragStart && (id === 'belt' || id === 'belt2' || id === 'pipe')) this.previewBelts(id, this.dragStart, { tx, tz });
    } else if (def.kind === 'wall' || def.kind === 'door_wall' || def.kind === 'window_wall' || def.kind === 'gate') {
      const e = Base.nearestEdge(p.x, p.z);
      let cx = e.cx, cz = e.cz;
      if (def.kind === 'gate') { if (e.axis === 0) cx += GRID / 2; else cz += GRID / 2; }
      const y = this.base.edgeBaseY({ cx, cz, axis: e.axis });
      g.position.set(cx, y, cz); g.rotation.y = e.axis === 1 ? Math.PI / 2 : 0;
      if (this.base.edgeTaken(e.tx, e.tz, e.axis) || (def.kind === 'gate' && this.base.edgeTaken(e.axis === 0 ? e.tx + 1 : e.tx, e.axis === 1 ? e.tz + 1 : e.tz, e.axis))) { ok = false; this.reason = 'Edge occupied'; }
      if (this.world.town.isOccupied(cx, cz, 0.5)) { ok = false; this.reason = 'Too close to a building'; }
      if (this.factory.machineAtPos(cx + (e.axis === 1 ? 0.5 : 0), cz + (e.axis === 0 ? 0.5 : 0)) && this.factory.machineAtPos(cx - (e.axis === 1 ? 0.5 : 0), cz - (e.axis === 0 ? 0.5 : 0))) { ok = false; this.reason = 'Blocked by machine'; }
      this.target = { tx: e.tx, tz: e.tz, cx, cz, axis: e.axis };
      this.ports.clear();
    } else {
      const tx = tileOf(p.x), tz = tileOf(p.z);
      const cx = (tx + 0.5) * GRID, cz = (tz + 0.5) * GRID;
      let y: number;
      if (def.kind === 'foundation') y = this.base.tileBaseY(tx, tz, false);
      else if (def.kind === 'roof') y = this.base.roofHeight(tx, tz);
      else y = this.base.tileBaseY(tx, tz);
      g.position.set(cx, y, cz); g.rotation.y = this.rot * Math.PI / 2;
      if (this.base.tileTaken(tx, tz, def.kind)) { ok = false; this.reason = 'Tile occupied'; }
      if (this.factory.machineAtTile(tx, tz)) { ok = false; this.reason = 'Blocked by machine'; }
      if (this.world.town.isOccupied(cx, cz, def.kind === 'station' ? -0.5 : 0.5) && def.kind !== 'station') { ok = false; this.reason = 'Too close to a building'; }
      if (this.world.waterDepth(cx, cz) > 0.4) { ok = false; this.reason = 'In water'; }
      this.target = { tx, tz, cx, cz, axis: 0 };
      this.ports.clear();
    }
    if (def?.skill && (def.skill[0] === 'engineering' ? engineering : carpentry) < def.skill[1]) { ok = false; this.reason = `Requires ${def.skill[0] === 'engineering' ? 'Engineering' : 'Carpentry'} ${def.skill[1]}`; }
    if (!afford) { ok = false; if (!this.reason) this.reason = 'Missing materials'; }
    void engineering;
    this.valid = ok;
    this.setGhostMat(g, ok);
  }

  private drawPorts(id: string, cx: number, cz: number, y: number) {
    this.ports.clear();
    const def = MACHINES[id];
    const arrow = (x: number, z: number, dx: number, dz: number, col: number) => {
      const s = new THREE.Shape(); s.moveTo(-0.35, -0.5); s.lineTo(0.35, -0.5); s.lineTo(0.35, 0.1); s.lineTo(0.7, 0.1); s.lineTo(0, 0.8); s.lineTo(-0.7, 0.1); s.lineTo(-0.35, 0.1);
      const m = new THREE.Mesh(new THREE.ShapeGeometry(s), new THREE.MeshBasicMaterial({ color: col, transparent: true, opacity: 0.7, side: THREE.DoubleSide, depthWrite: false }));
      m.rotation.x = -Math.PI / 2; m.rotation.z = -Math.atan2(dx, dz) + Math.PI;
      m.rotation.set(-Math.PI / 2, 0, Math.atan2(dx, -dz) + Math.PI);
      m.position.set(x, this.world.groundY(x, z) + 0.15, z);
      this.ports.add(m);
    };
    const m = { cx, cz, rot: this.rot, def };
    const [fx, fz] = this.factory.frontDir(this.rot);
    for (const i of def.inputs) { const [tx, tz] = this.factory.tileWorld(m, i, -1); arrow((tx + 0.5) * GRID, (tz + 0.5) * GRID, fx, fz, 0x3aa0ff); }
    for (const i of def.outputs) { const [tx, tz] = this.factory.tileWorld(m, i, def.size[1]); arrow((tx + 0.5) * GRID, (tz + 0.5) * GRID, fx, fz, 0xff9a2a); }
    if (id === 'belt' || id === 'belt2') arrow(cx, cz, fx, fz, 0xffffff);
    void y;
  }

  beltPath(a: { tx: number; tz: number }, b: { tx: number; tz: number }): { tx: number; tz: number; rot: number }[] {
    const out: { tx: number; tz: number; rot: number }[] = [];
    const dx = Math.sign(b.tx - a.tx), dz = Math.sign(b.tz - a.tz);
    const rotFor = (x: number, z: number) => x === 1 ? 1 : x === -1 ? 3 : z === 1 ? 0 : 2;
    let x = a.tx, z = a.tz;
    // horizontal first, then vertical
    if (dx === 0 && dz === 0) return [{ tx: x, tz: z, rot: this.rot }];
    while (x !== b.tx) { out.push({ tx: x, tz: z, rot: rotFor(dx, 0) }); x += dx; }
    while (z !== b.tz) { out.push({ tx: x, tz: z, rot: rotFor(0, dz) }); z += dz; }
    out.push({ tx: x, tz: z, rot: dz !== 0 ? rotFor(0, dz) : rotFor(dx, 0) });
    return out.slice(0, 60);
  }
  private previewBelts(id: string, a: { tx: number; tz: number }, b: { tx: number; tz: number }) {
    this.clearBelts();
    if (this.ghost) this.ghost.visible = false;
    for (const s of this.beltPath(a, b)) {
      const g = this.makeGhost(id);
      const cx = (s.tx + 0.5) * GRID, cz = (s.tz + 0.5) * GRID;
      g.position.set(cx, this.world.groundY(cx, cz), cz); g.rotation.y = s.rot * Math.PI / 2;
      this.setGhostMat(g, id === 'pipe' ? this.factory.canPlace('pipe', cx, cz, 0).ok : !this.factory.machineAtTile(s.tx, s.tz));
      this.scene.add(g); this.beltGhosts.push(g);
    }
  }

  /** LMB down */
  begin(): { kind: string; data?: any } | null {
    if (this.dismantle) { if (this.hover) { const h = this.hover; this.clearHover(); return { kind: 'dismantle', data: h }; } return null; }
    if (!this.selected || !this.target) return null;
    if (this.selected === 'belt' || this.selected === 'belt2' || this.selected === 'pipe') { this.dragStart = { tx: this.target.tx, tz: this.target.tz }; return { kind: 'drag' }; }
    if (!this.valid) { audio.play('ui_error'); return { kind: 'invalid', data: this.reason }; }
    return { kind: 'place', data: { id: this.selected, ...this.target, rot: this.rot } };
  }
  /** LMB up (belts) */
  end(): { tx: number; tz: number; rot: number }[] | null {
    if (!this.dragStart || !this.target) { this.dragStart = null; return null; }
    const path = this.beltPath(this.dragStart, { tx: this.target.tx, tz: this.target.tz });
    this.dragStart = null; this.clearBelts(); if (this.ghost) this.ghost.visible = true;
    if (this.selected === 'pipe') return path.filter(s => this.factory.canPlace('pipe', (s.tx + 0.5) * 2, (s.tz + 0.5) * 2, 0).ok);
    return path.filter(s => !this.factory.machineAtTile(s.tx, s.tz));
  }
}
