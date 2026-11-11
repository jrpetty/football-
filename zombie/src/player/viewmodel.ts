// First-person arms & held item, rendered in a separate overlay scene with procedural animation.
import * as THREE from 'three';
import { buildItemModel, HeldModelId } from '../models/items';
import { getMaterial } from '../render/materials';
import { damp } from '../core/util';

export class ViewModel {
  scene = new THREE.Scene();
  camera: THREE.PerspectiveCamera;
  root = new THREE.Group();     // follows main camera
  private sway = new THREE.Group(); // lag / bob
  private rightArm = new THREE.Group(); private leftArm = new THREE.Group();
  private held: THREE.Object3D | null = null;
  heldId: HeldModelId | null = null;
  private kind: 'melee' | 'gun' | 'tool' | 'none' = 'none';
  private twoHanded = false;
  private sun: THREE.DirectionalLight; private hemi: THREE.HemisphereLight;
  flash: THREE.PointLight;
  // anim state
  private swingT = -1; private swingDur = 0.6; private swingVar = 0;
  private recoilT = 0; private reloadT = -1; private reloadDur = 1; private useT = -1; private useDur = 1; private equipT = 1;
  private bobT = 0; private lagX = 0; private lagY = 0; ads = 0; private shove = -1;
  muzzle = new THREE.Vector3();

  constructor(fov: number) {
    this.camera = new THREE.PerspectiveCamera(fov, innerWidth / innerHeight, 0.01, 20);
    this.scene.add(this.root); this.root.add(this.sway);
    this.sun = new THREE.DirectionalLight(0xffffff, 2); this.hemi = new THREE.HemisphereLight(0xffffff, 0x444444, 0.6);
    this.scene.add(this.sun, this.hemi, this.sun.target);
    this.flash = new THREE.PointLight(0xffb060, 0, 3, 2); this.sway.add(this.flash);
    this.buildArms();
  }

  private buildArms() {
    const sleeve = getMaterial('clothDark'), skin = new THREE.MeshStandardMaterial({ color: 0xc8a088, roughness: 0.7 });
    const mkArm = (g: THREE.Group, mirror: number) => {
      const fore = new THREE.Mesh(new THREE.CylinderGeometry(0.045, 0.052, 0.42, 10), sleeve);
      fore.rotation.x = Math.PI / 2; fore.position.set(0, -0.02, 0.24); g.add(fore);
      const cuff = new THREE.Mesh(new THREE.CylinderGeometry(0.047, 0.047, 0.05, 10), getMaterial('fabric'));
      cuff.rotation.x = Math.PI / 2; cuff.position.set(0, -0.02, 0.04); g.add(cuff);
      const palm = new THREE.Mesh(new THREE.BoxGeometry(0.085, 0.04, 0.1), skin); palm.position.set(0, -0.02, -0.03); g.add(palm);
      for (let i = 0; i < 4; i++) { const f = new THREE.Mesh(new THREE.BoxGeometry(0.018, 0.022, 0.06), skin); f.position.set(-0.03 + i * 0.02, -0.035, -0.085); f.rotation.x = 0.9; g.add(f); }
      const th = new THREE.Mesh(new THREE.BoxGeometry(0.02, 0.022, 0.055), skin); th.position.set(mirror * 0.05, -0.005, -0.06); th.rotation.y = mirror * 0.6; g.add(th);
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
      if (kind === 'gun') { m.position.set(0, -0.01, -0.02); m.rotation.set(0, 0, 0); }
      else if (id === 'flashlight') { m.position.set(0, -0.015, -0.03); m.rotation.set(0, 0, 0); }
      else { m.position.set(0, -0.02, -0.03); m.rotation.set(-1.15, 0, 0.1); }
      this.rightArm.add(m); this.held = m;
    }
    this.equipT = 0; this.swingT = -1; this.reloadT = -1;
  }
  swing(dur: number) { this.swingT = 0; this.swingDur = dur; this.swingVar = (this.swingVar + 1) % 2; }
  recoil(k: number) { this.recoilT = Math.min(1.5, this.recoilT + k); }
  reload(dur: number) { this.reloadT = 0; this.reloadDur = dur; }
  use(dur: number) { this.useT = 0; this.useDur = dur; }
  cancelUse() { this.useT = -1; }
  push() { this.shove = 0; }
  get busy() { return this.swingT >= 0 || this.reloadT >= 0; }

  update(dt: number, cam: THREE.Camera, o: { speed: number; sprint: boolean; crouch: boolean; lookDX: number; lookDY: number; aim: boolean; sun: THREE.DirectionalLight; hemi: THREE.HemisphereLight; env: THREE.Texture | null; envI: number; sway: number; time: number; fov: number }) {
    // follow camera
    this.root.position.copy(cam.position); this.root.quaternion.copy(cam.quaternion);
    this.camera.position.copy(cam.position); this.camera.quaternion.copy(cam.quaternion);
    this.camera.fov = o.fov; this.camera.aspect = innerWidth / innerHeight; this.camera.updateProjectionMatrix();
    // lights mirror the world
    this.sun.color.copy(o.sun.color); this.sun.intensity = o.sun.intensity * 0.9;
    this.sun.position.copy(cam.position).add(o.sun.position.clone().sub(o.sun.target.position).normalize());
    this.sun.target.position.copy(cam.position);
    this.hemi.color.copy(o.hemi.color); this.hemi.groundColor.copy(o.hemi.groundColor); this.hemi.intensity = o.hemi.intensity;
    this.scene.environment = o.env; (this.scene as any).environmentIntensity = o.envI;
    this.flash.intensity = Math.max(0, this.flash.intensity - dt * 120);

    // timers
    if (this.swingT >= 0) { this.swingT += dt / this.swingDur; if (this.swingT >= 1) this.swingT = -1; }
    if (this.reloadT >= 0) { this.reloadT += dt / this.reloadDur; if (this.reloadT >= 1) this.reloadT = -1; }
    if (this.useT >= 0) { this.useT += dt / this.useDur; if (this.useT >= 1) this.useT = -1; }
    if (this.shove >= 0) { this.shove += dt / 0.35; if (this.shove >= 1) this.shove = -1; }
    this.equipT = Math.min(1, this.equipT + dt * 3);
    this.recoilT = Math.max(0, this.recoilT - dt * 6);
    this.ads = damp(this.ads, o.aim && this.kind === 'gun' && this.reloadT < 0 ? 1 : 0, 14, dt);

    // bob & lag
    this.bobT += dt * o.speed * (o.sprint ? 1.6 : 2.2);
    const bobA = Math.min(1, o.speed / 4) * (1 - this.ads * 0.85);
    this.lagX = damp(this.lagX, -o.lookDX * 0.0009, 10, dt); this.lagY = damp(this.lagY, o.lookDY * 0.0009, 10, dt);
    const sprintPose = o.sprint && this.swingT < 0 ? 1 : 0;
    this.sway.position.set(Math.sin(this.bobT) * 0.012 * bobA + this.lagX, -Math.abs(Math.cos(this.bobT)) * 0.012 * bobA + this.lagY - (1 - this.equipT) * 0.25, 0);
    this.sway.rotation.set(this.lagY * 2 + Math.sin(o.time * 1.3) * o.sway * 0.5, this.lagX * 2 + Math.cos(o.time * 1.1) * o.sway * 0.5, sprintPose * 0.25 + Math.sin(this.bobT) * 0.01 * bobA);

    // right arm pose
    const R = this.rightArm, L = this.leftArm;
    let rp = new THREE.Vector3(0.2, -0.2, -0.38), rr = new THREE.Euler(0, 0, 0);
    let lp = new THREE.Vector3(-0.32, -0.42, -0.2), lr = new THREE.Euler(0, 0, 0); let showL = false;
    if (this.kind === 'gun') {
      rp.set(0.14, -0.15, -0.34).lerp(new THREE.Vector3(0.0, -0.088, -0.28), this.ads);
      if (this.twoHanded) { showL = true; lp.set(0.06, -0.17, -0.58).lerp(new THREE.Vector3(-0.02, -0.11, -0.52), this.ads); lr.set(0.1, 0.3, 0.2); }
      rp.z += this.recoilT * 0.06; rr.x += this.recoilT * 0.18;
      if (sprintPose) { rr.set(0.4, 0.7, 0.2); rp.add(new THREE.Vector3(0.05, -0.05, 0.05)); }
      if (this.reloadT >= 0) { const k = Math.sin(this.reloadT * Math.PI); rr.x -= k * 0.6; rr.z += k * 0.5; rp.y -= k * 0.08; showL = true; lp.set(0.06 - k * 0.04, -0.28 + k * 0.08, -0.4); }
    } else if (this.kind === 'melee' || this.kind === 'tool') {
      rp.set(0.24, -0.26, -0.4); rr.set(0.1, 0, 0.05);
      if (this.twoHanded) { showL = true; lp.set(0.12, -0.32, -0.32); lr.set(0.3, 0, -0.4); }
      if (sprintPose) { rr.x += 0.4; rp.y -= 0.04; }
      if (this.swingT >= 0) {
        const t = this.swingT; const s = this.swingVar ? -1 : 1;
        const ease = (x: number) => x * x * (3 - 2 * x);
        if (t < 0.3) { const k = ease(t / 0.3); rp.add(new THREE.Vector3(0.08 * k * s, 0.14 * k, 0.08 * k)); rr.x += 0.7 * k; rr.z += 0.5 * k * s; rr.y -= 0.3 * k * s; }
        else if (t < 0.5) { const k = ease((t - 0.3) / 0.2); rp.add(new THREE.Vector3(0.08 * s - 0.3 * k * s, 0.14 - 0.3 * k, 0.08 - 0.25 * k)); rr.x += 0.7 - 1.9 * k; rr.z += (0.5 - 1.2 * k) * s; rr.y -= (0.3 - 0.6 * k) * s; }
        else { const k = ease((t - 0.5) / 0.5); rp.add(new THREE.Vector3((-0.22 * s) * (1 - k), -0.16 * (1 - k), -0.17 * (1 - k))); rr.x += -1.2 * (1 - k); rr.z += -0.7 * s * (1 - k); rr.y += 0.3 * s * (1 - k); }
        if (this.twoHanded) { lp.copy(rp).add(new THREE.Vector3(-0.1, -0.08, 0.12)); lr.copy(rr); }
      }
    } else {
      rp.set(0.22, -0.32, -0.35);
    }
    if (this.useT >= 0) { const k = Math.sin(Math.min(1, this.useT * 1.2) * Math.PI); rp.lerp(new THREE.Vector3(0.05, -0.12, -0.22), k); rr.x += k * 0.8; }
    if (this.shove >= 0) { const k = Math.sin(this.shove * Math.PI); showL = true; lp.set(-0.08, -0.18 + k * 0.05, -0.3 - k * 0.25); lr.set(1.2 * k, 0, 0); rp.z -= k * 0.15; }
    R.position.lerp(rp, Math.min(1, dt * 25)); R.rotation.set(damp(R.rotation.x, rr.x, 25, dt), damp(R.rotation.y, rr.y, 25, dt), damp(R.rotation.z, rr.z, 25, dt));
    L.visible = showL || this.kind === 'none';
    if (!L.visible) L.position.set(-0.3, -0.6, -0.2);
    L.position.lerp(showL ? lp : new THREE.Vector3(-0.3, -0.5, -0.25), Math.min(1, dt * 20));
    L.rotation.set(damp(L.rotation.x, lr.x, 20, dt), damp(L.rotation.y, lr.y, 20, dt), damp(L.rotation.z, lr.z, 20, dt));
    if (this.kind === 'none') { L.visible = false; R.visible = this.useT >= 0; } else R.visible = true;

    // muzzle world position
    const mz = this.held?.userData.muzzle as THREE.Object3D | undefined;
    this.root.updateMatrixWorld(true);
    if (mz) mz.getWorldPosition(this.muzzle); else R.getWorldPosition(this.muzzle);
    if (this.flash.intensity > 0) this.flash.position.copy(this.sway.worldToLocal(this.muzzle.clone()));
  }
  muzzleFlash() { this.flash.intensity = 6; }
  lensWorld(out: THREE.Vector3) { const l = this.held?.userData.lens as THREE.Object3D | undefined; if (l) l.getWorldPosition(out); else this.rightArm.getWorldPosition(out); return out; }
}
