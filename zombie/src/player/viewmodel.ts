// First-person arms & held item: spring-physics rig with keyframed swing patterns, staged reloads,
// equip/holster, breathing, sprint figure-eights, landing/jump/recoil/hurt kicks and camera feel (roll, dips, flinches).
import * as THREE from 'three';
import { buildItemModel, HeldModelId } from '../models/items';
import { getMaterial, getFlatMaterial } from '../render/materials';
import { damp, clamp } from '../core/util';

/** critically-tunable damped spring on a vector */
class Spring3 {
  x = new THREE.Vector3(); v = new THREE.Vector3();
  constructor(public k = 140, public c = 14) {}
  kick(dx: number, dy: number, dz: number) { this.v.x += dx; this.v.y += dy; this.v.z += dz; }
  step(dt: number) {
    const n = Math.max(1, Math.ceil(dt / 0.008)); const h = dt / n;
    for (let i = 0; i < n; i++) { this.v.addScaledVector(this.x, -this.k * h).multiplyScalar(Math.max(0, 1 - this.c * h)); this.x.addScaledVector(this.v, h); }
  }
}
interface Key { t: number; p: [number, number, number]; r: [number, number, number] }
const ease = (x: number) => x * x * (3 - 2 * x);
// swing patterns (offsets from the rest pose); impact happens at t≈0.42 (matches game timing)
const SWINGS: Record<string, Key[]> = {
  forehand: [{ t: 0, p: [0, 0, 0], r: [0, 0, 0] }, { t: 0.28, p: [0.13, 0.17, 0.1], r: [0.95, -0.45, 0.65] }, { t: 0.42, p: [-0.22, -0.12, -0.2], r: [-1.1, 0.55, -0.75] }, { t: 0.56, p: [-0.3, -0.2, -0.08], r: [-1.35, 0.75, -0.95] }, { t: 1, p: [0, 0, 0], r: [0, 0, 0] }],
  backhand: [{ t: 0, p: [0, 0, 0], r: [0, 0, 0] }, { t: 0.28, p: [-0.22, 0.12, 0.06], r: [0.75, 0.65, -0.85] }, { t: 0.42, p: [0.2, -0.1, -0.2], r: [-0.95, -0.45, 0.65] }, { t: 0.56, p: [0.26, -0.18, -0.06], r: [-1.2, -0.6, 0.85] }, { t: 1, p: [0, 0, 0], r: [0, 0, 0] }],
  overhead: [{ t: 0, p: [0, 0, 0], r: [0, 0, 0] }, { t: 0.3, p: [-0.04, 0.32, 0.16], r: [1.7, 0.05, 0.12] }, { t: 0.42, p: [-0.02, -0.16, -0.26], r: [-1.25, 0, 0] }, { t: 0.6, p: [0, -0.26, -0.12], r: [-1.55, 0, 0] }, { t: 1, p: [0, 0, 0], r: [0, 0, 0] }],
  stab: [{ t: 0, p: [0, 0, 0], r: [0, 0, 0] }, { t: 0.3, p: [0.02, -0.02, 0.12], r: [0.25, 0.1, 0] }, { t: 0.42, p: [-0.04, 0.04, -0.32], r: [-0.35, -0.1, 0] }, { t: 0.55, p: [-0.03, 0.03, -0.28], r: [-0.3, -0.1, 0] }, { t: 1, p: [0, 0, 0], r: [0, 0, 0] }],
};
function sample(keys: Key[], t: number, outP: THREE.Vector3, outR: THREE.Euler) {
  let i = 0; while (i < keys.length - 2 && t > keys[i + 1].t) i++;
  const a = keys[i], b = keys[i + 1]; const k = ease(clamp((t - a.t) / (b.t - a.t), 0, 1));
  outP.set(a.p[0] + (b.p[0] - a.p[0]) * k, a.p[1] + (b.p[1] - a.p[1]) * k, a.p[2] + (b.p[2] - a.p[2]) * k);
  outR.set(a.r[0] + (b.r[0] - a.r[0]) * k, a.r[1] + (b.r[1] - a.r[1]) * k, a.r[2] + (b.r[2] - a.r[2]) * k);
}

export class ViewModel {
  scene = new THREE.Scene();
  camera: THREE.PerspectiveCamera;
  root = new THREE.Group();
  private sway = new THREE.Group();
  private rightArm = new THREE.Group(); private leftArm = new THREE.Group();
  private held: THREE.Object3D | null = null;
  heldId: HeldModelId | null = null;
  private kind: 'melee' | 'gun' | 'tool' | 'none' = 'none';
  private twoHanded = false;
  private sun: THREE.DirectionalLight; private hemi: THREE.HemisphereLight;
  flash: THREE.PointLight;
  private swingT = -1; private swingDur = 0.6; private swingName = 'forehand'; private swingCount = 0;
  private reloadT = -1; private reloadDur = 1; private useT = -1; private useDur = 1; private equipT = 1;
  private bobT = 0; private lagX = 0; private lagY = 0; ads = 0; private shove = -1;
  private posS = new Spring3(170, 13); private rotS = new Spring3(150, 12);
  private camS = new Spring3(90, 11); private camR = new Spring3(110, 12);
  /** camera feel, applied by the game each frame */
  camOffset = new THREE.Vector3(); camRot = new THREE.Vector3();
  private roll = 0; private lastStepSign = 0; private sYaw = 0; private sRoll = 0;
  muzzle = new THREE.Vector3();
  private tp = new THREE.Vector3(); private tr = new THREE.Euler();

  constructor(fov: number) {
    this.camera = new THREE.PerspectiveCamera(fov, innerWidth / innerHeight, 0.01, 20);
    this.scene.add(this.root); this.root.add(this.sway);
    this.sun = new THREE.DirectionalLight(0xffffff, 2); this.hemi = new THREE.HemisphereLight(0xffffff, 0x444444, 0.6);
    this.scene.add(this.sun, this.hemi, this.sun.target);
    this.flash = new THREE.PointLight(0xffb060, 0, 3, 2); this.sway.add(this.flash);
    this.buildArms();
  }

  private buildArms() {
    const sleeve = getMaterial('clothDark'), cuffM = getMaterial('fabric'), skin = getFlatMaterial(0xc29a80, 0.75), skinD = getFlatMaterial(0xa8826a, 0.8);
    const mkArm = (g: THREE.Group, mirror: number) => {
      const fore = new THREE.Mesh(new THREE.CylinderGeometry(0.044, 0.054, 0.42, 7), sleeve);
      fore.rotation.x = Math.PI / 2; fore.position.set(0, -0.02, 0.24); g.add(fore);
      const cuff = new THREE.Mesh(new THREE.CylinderGeometry(0.048, 0.046, 0.05, 7), cuffM);
      cuff.rotation.x = Math.PI / 2; cuff.position.set(0, -0.02, 0.04); g.add(cuff);
      const wrist = new THREE.Mesh(new THREE.CylinderGeometry(0.032, 0.036, 0.05, 6), skin); wrist.rotation.x = Math.PI / 2; wrist.position.set(0, -0.02, 0.0); g.add(wrist);
      const palm = new THREE.Mesh(new THREE.BoxGeometry(0.085, 0.038, 0.095), skin); palm.position.set(0, -0.02, -0.045); g.add(palm);
      // segmented, curled fingers gripping
      for (let i = 0; i < 4; i++) {
        const f1 = new THREE.Mesh(new THREE.BoxGeometry(0.017, 0.02, 0.038), skin); f1.position.set(-0.03 + i * 0.02, -0.022, -0.1); f1.rotation.x = 0.5; g.add(f1);
        const f2 = new THREE.Mesh(new THREE.BoxGeometry(0.016, 0.018, 0.03), skinD); f2.position.set(-0.03 + i * 0.02, -0.042, -0.118); f2.rotation.x = 1.4; g.add(f2);
      }
      const th = new THREE.Mesh(new THREE.BoxGeometry(0.02, 0.022, 0.05), skin); th.position.set(mirror * 0.05, -0.0, -0.07); th.rotation.set(0.2, mirror * 0.7, 0); g.add(th);
      const k = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.012, 0.02), skinD); k.position.set(0, -0.001, -0.085); g.add(k);
    };
    mkArm(this.rightArm, -1); mkArm(this.leftArm, 1);
    this.sway.add(this.rightArm, this.leftArm);
  }

  setItem(id: HeldModelId | null, kind: 'melee' | 'gun' | 'tool' | 'none', twoHanded: boolean) {
    if (id === this.heldId && kind === this.kind) return;
    if (this.held) this.rightArm.remove(this.held);
    this.held = null; this.heldId = id; this.kind = kind; this.twoHanded = twoHanded;
    if (id) {
      const m = buildItemModel(id);
      m.traverse(o => { if ((o as THREE.Mesh).isMesh) { o.castShadow = false; o.receiveShadow = false; } });
      if (kind === 'gun') m.position.set(0, -0.01, -0.02);
      else if (id === 'flashlight') m.position.set(0, -0.015, -0.03);
      else { m.position.set(0, -0.02, -0.04); m.rotation.set(-1.15, 0, 0.1); }
      this.rightArm.add(m); this.held = m;
    }
    this.equipT = 0; this.swingT = -1; this.reloadT = -1;
    this.rotS.kick(-2, 0.5, 0);
  }
  swing(dur: number) {
    this.swingT = 0; this.swingDur = dur; this.swingCount++;
    const id = this.heldId;
    if (id === 'knife') this.swingName = 'stab';
    else if (this.twoHanded && (id === 'sledgehammer' || id === 'axe' || id === 'pickaxe')) this.swingName = this.swingCount % 3 === 0 ? 'forehand' : 'overhead';
    else this.swingName = ['forehand', 'backhand', 'forehand', 'overhead'][this.swingCount % 4];
  }
  /** melee connected: jolt */
  impact(strength = 1) { this.rotS.kick(1.6 * strength, (Math.random() - 0.5) * 2, (Math.random() - 0.5) * 2); this.posS.kick(0, 0, 0.25 * strength); this.camR.kick(0.25 * strength, (Math.random() - 0.5) * 0.3, 0); }
  recoil(k: number) {
    this.posS.kick(0, 0.08 * k, 0.6 * k); this.rotS.kick(3.2 * k, (Math.random() - 0.5) * 1.6 * k, (Math.random() - 0.5) * 1.2 * k);
    this.camR.kick(0.6 * k, (Math.random() - 0.5) * 0.4 * k, 0); this.camS.kick(0, 0, 0.08 * k);
  }
  land(impact: number) { const k = clamp(impact / 8, 0.2, 1.5); this.posS.kick(0, -0.9 * k, 0); this.rotS.kick(-1.4 * k, 0, 0); this.camS.kick(0, -1.6 * k, 0); this.camR.kick(-0.4 * k, 0, 0); }
  jump() { this.posS.kick(0, -0.4, 0); this.rotS.kick(0.9, 0, 0); this.camS.kick(0, 0.3, 0); }
  hurt() { this.camR.kick((Math.random() - 0.5) * 2.2, (Math.random() - 0.5) * 2.2, (Math.random() - 0.5) * 3); this.rotS.kick((Math.random() - 0.5) * 3, (Math.random() - 0.5) * 3, 0); }
  footstep() { this.lastStepSign = -this.lastStepSign || 1; this.camS.kick(0, -0.12, 0); this.camR.kick(0, 0, 0.05 * this.lastStepSign); }
  reload(dur: number) { this.reloadT = 0; this.reloadDur = dur; }
  use(dur: number) { this.useT = 0; this.useDur = dur; }
  cancelUse() { this.useT = -1; }
  push() { this.shove = 0; this.camR.kick(-0.3, 0, 0); }
  get busy() { return this.swingT >= 0 || this.reloadT >= 0; }

  update(dt: number, cam: THREE.Camera, o: { speed: number; sprint: boolean; crouch: boolean; lookDX: number; lookDY: number; aim: boolean; sun: THREE.DirectionalLight; hemi: THREE.HemisphereLight; env: THREE.Texture | null; envI: number; sway: number; time: number; fov: number; strafe?: number }) {
    this.root.position.copy(cam.position); this.root.quaternion.copy(cam.quaternion);
    this.camera.position.copy(cam.position); this.camera.quaternion.copy(cam.quaternion);
    this.camera.fov = o.fov; this.camera.aspect = innerWidth / innerHeight; this.camera.updateProjectionMatrix();
    this.sun.color.copy(o.sun.color); this.sun.intensity = o.sun.intensity * 0.9;
    this.sun.position.copy(cam.position).add(o.sun.position.clone().sub(o.sun.target.position).normalize());
    this.sun.target.position.copy(cam.position);
    this.hemi.color.copy(o.hemi.color); this.hemi.groundColor.copy(o.hemi.groundColor); this.hemi.intensity = o.hemi.intensity;
    this.scene.environment = o.env; (this.scene as any).environmentIntensity = o.envI;
    this.flash.intensity = Math.max(0, this.flash.intensity - dt * 120);

    if (this.swingT >= 0) { this.swingT += dt / this.swingDur; if (this.swingT >= 1) this.swingT = -1; }
    if (this.reloadT >= 0) { this.reloadT += dt / this.reloadDur; if (this.reloadT >= 1) this.reloadT = -1; }
    if (this.useT >= 0) { this.useT += dt / this.useDur; if (this.useT >= 1) this.useT = -1; }
    if (this.shove >= 0) { this.shove += dt / 0.35; if (this.shove >= 1) this.shove = -1; }
    this.equipT = Math.min(1, this.equipT + dt * 2.8);
    this.ads = damp(this.ads, o.aim && this.kind === 'gun' && this.reloadT < 0 ? 1 : 0, 14, dt);
    for (const s of [this.posS, this.rotS, this.camS, this.camR]) s.step(dt);

    // bob: figure-eight that grows with speed; sprint is a wide, loping arc
    const sp = Math.min(1, o.speed / 4);
    this.bobT += dt * o.speed * (o.sprint ? 1.55 : 2.15);
    const bobA = sp * (1 - this.ads * 0.85);
    const bx = Math.sin(this.bobT) * (o.sprint ? 0.028 : 0.013) * bobA;
    const by = -Math.abs(Math.cos(this.bobT)) * (o.sprint ? 0.03 : 0.013) * bobA;
    const breathe = Math.sin(o.time * 1.6) * 0.004 * (1 - sp) * (1 - this.ads * 0.7);
    this.lagX = damp(this.lagX, clamp(-o.lookDX * 0.0011, -0.06, 0.06), 9, dt); this.lagY = damp(this.lagY, clamp(o.lookDY * 0.0011, -0.06, 0.06), 9, dt);
    const sprintPose = o.sprint && this.swingT < 0 && this.reloadT < 0 ? 1 : 0;
    const eq = ease(this.equipT);
    this.sway.position.set(bx + this.lagX + this.posS.x.x * 0.05, by + breathe + this.lagY - (1 - eq) * 0.32 + this.posS.x.y * 0.05 - (o.crouch ? 0.012 : 0), this.posS.x.z * 0.05);
    this.sway.rotation.set(this.lagY * 2.2 + Math.sin(o.time * 1.3) * o.sway * 0.5 - (1 - eq) * 0.9 + this.rotS.x.x * 0.05 + breathe * 1.5,
      this.lagX * 2.2 + Math.cos(o.time * 1.1) * o.sway * 0.5 + this.rotS.x.y * 0.05,
      sprintPose * 0.28 + Math.sin(this.bobT) * 0.018 * bobA + this.rotS.x.z * 0.05 + this.lagX * -1.5);

    const R = this.rightArm, L = this.leftArm;
    const rp = new THREE.Vector3(0.2, -0.2, -0.38), rr = new THREE.Euler(0, 0, 0);
    const lp = new THREE.Vector3(-0.32, -0.42, -0.2), lr = new THREE.Euler(0, 0, 0); let showL = false;
    if (this.kind === 'gun') {
      rp.set(0.14, -0.15, -0.34).lerp(new THREE.Vector3(0.0, -0.088, -0.28), this.ads);
      if (this.twoHanded) { showL = true; lp.set(0.06, -0.17, -0.58).lerp(new THREE.Vector3(-0.02, -0.11, -0.52), this.ads); lr.set(0.1, 0.3, 0.2); }
      if (sprintPose) { rr.set(0.45, 0.75, 0.25); rp.add(new THREE.Vector3(0.05, -0.06, 0.06)); }
      if (this.reloadT >= 0) {
        const t = this.reloadT; showL = true;
        const tilt = ease(clamp(t / 0.2, 0, 1)) * (1 - ease(clamp((t - 0.85) / 0.15, 0, 1)));
        rr.x -= tilt * 0.5; rr.z += tilt * 0.65; rp.y -= tilt * 0.06; rp.x -= tilt * 0.04;
        // magazine out (hand drops), new magazine in (hand rises and slaps), then rack
        const magDown = t < 0.45 ? ease(clamp((t - 0.15) / 0.15, 0, 1)) : 1 - ease(clamp((t - 0.45) / 0.15, 0, 1));
        lp.set(0.04, -0.24 - magDown * 0.22, -0.38); lr.set(0.3, 0, 0);
        if (t > 0.58 && t < 0.66) this.posS.kick(0, 0.4 * dt * 60, 0);
        if (t > 0.68 && t < 0.84) { const k = Math.sin((t - 0.68) / 0.16 * Math.PI); lp.set(0.0, -0.1, -0.42 + k * 0.08); rp.z += k * 0.03; }
      }
    } else if (this.kind === 'melee' || this.kind === 'tool') {
      rp.set(0.24, -0.26, -0.4); rr.set(0.1, 0, 0.05);
      if (this.twoHanded) { showL = true; lp.set(0.12, -0.32, -0.32); lr.set(0.3, 0, -0.4); }
      if (sprintPose) { rr.x += 0.45; rp.y -= 0.05; rr.z += 0.15; }
      if (this.swingT >= 0) {
        sample(SWINGS[this.swingName], this.swingT, this.tp, this.tr);
        rp.add(this.tp); rr.x += this.tr.x; rr.y += this.tr.y; rr.z += this.tr.z;
        if (this.twoHanded) { lp.copy(rp).add(new THREE.Vector3(-0.1, -0.08, 0.12)); lr.set(rr.x, rr.y, rr.z - 0.3); }
        // camera follows the weight of the swing
        const w = this.swingName === 'overhead' ? 0.035 : 0.02;
        this.sYaw = -this.tr.y * w; this.sRoll = this.tr.z * w * 0.6;
      }
    } else rp.set(0.22, -0.32, -0.35);
    if (this.useT >= 0) { const k = Math.sin(Math.min(1, this.useT * 1.2) * Math.PI); rp.lerp(new THREE.Vector3(0.05, -0.12, -0.22), k); rr.x += k * 0.8; }
    if (this.shove >= 0) { const k = Math.sin(this.shove * Math.PI); showL = true; lp.set(-0.08, -0.18 + k * 0.05, -0.3 - k * 0.28); lr.set(1.2 * k, 0, 0); rp.z -= k * 0.15; }
    const follow = this.swingT >= 0 ? 40 : 22;
    R.position.lerp(rp, Math.min(1, dt * follow));
    R.rotation.set(damp(R.rotation.x, rr.x, follow, dt), damp(R.rotation.y, rr.y, follow, dt), damp(R.rotation.z, rr.z, follow, dt));
    L.visible = showL || this.kind === 'none';
    L.position.lerp(showL ? lp : new THREE.Vector3(-0.3, -0.55, -0.25), Math.min(1, dt * 20));
    L.rotation.set(damp(L.rotation.x, lr.x, 20, dt), damp(L.rotation.y, lr.y, 20, dt), damp(L.rotation.z, lr.z, 20, dt));
    if (this.kind === 'none') { L.visible = false; R.visible = this.useT >= 0; } else R.visible = true;

    // camera feel: strafe roll, springs (landing dips, recoil climb, flinches), swing weight
    this.roll = damp(this.roll, clamp(-(o.strafe ?? 0) * 0.011, -0.03, 0.03), 6, dt);
    if (this.swingT < 0) { this.sYaw = damp(this.sYaw, 0, 10, dt); this.sRoll = damp(this.sRoll, 0, 10, dt); }
    this.camOffset.set(0, this.camS.x.y * 0.04, this.camS.x.z * 0.04);
    this.camRot.set(this.camR.x.x * 0.03, this.sYaw + this.camR.x.y * 0.015, this.sRoll + this.roll + this.camR.x.z * 0.03);

    const mz = this.held?.userData.muzzle as THREE.Object3D | undefined;
    this.root.updateMatrixWorld(true);
    if (mz) mz.getWorldPosition(this.muzzle); else R.getWorldPosition(this.muzzle);
    if (this.flash.intensity > 0) this.flash.position.copy(this.sway.worldToLocal(this.muzzle.clone()));
  }
  muzzleFlash() { this.flash.intensity = 6; }
  lensWorld(out: THREE.Vector3) { const l = this.held?.userData.lens as THREE.Object3D | undefined; if (l) l.getWorldPosition(out); else this.rightArm.getWorldPosition(out); return out; }
}
