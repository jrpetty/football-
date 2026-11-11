// Low-poly models for the fluid industry: pumps, pumpjack, refinery, fuel generator, tank, packager, pipes.
import * as THREE from 'three';
import { getFlatMaterial, getMaterial } from '../render/materials';

export type FluidModelId = 'water_pump' | 'oil_pump' | 'refinery' | 'fuel_gen' | 'fluid_tank' | 'packager';
const FOOT: Record<FluidModelId, [number, number]> = { water_pump: [2, 2], oil_pump: [3, 3], refinery: [3, 3], fuel_gen: [2, 2], fluid_tank: [2, 2], packager: [2, 2] };

function mk(g: THREE.Object3D, geo: THREE.BufferGeometry, hex: number, x: number, y: number, z: number, rough = 0.7, metal = 0.2) {
  const m = new THREE.Mesh(geo, getFlatMaterial(hex, rough, metal)); m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true; g.add(m); return m;
}
function statusLight(g: THREE.Group, x: number, y: number, z: number) {
  const m = new THREE.Mesh(new THREE.SphereGeometry(0.08, 6, 4), getMaterial('emissiveGreen').clone()); m.position.set(x, y, z); g.add(m);
  (g.userData.statusLights as THREE.Mesh[]).push(m);
}
const ORANGE = 0xd8641e, GREY = 0x6a6e70, DARK = 0x3a3e42, PIPE = 0x8a8e72, STEEL = 0xa8acb0, YEL = 0xd8b030;

export function buildFluidModel(id: FluidModelId): THREE.Group {
  const g = new THREE.Group(); g.userData.statusLights = []; g.userData.footprint = FOOT[id];
  let anim: (dt: number, run: boolean, t: number) => void = () => {};
  const W = FOOT[id][0] * 2, D = FOOT[id][1] * 2;
  mk(g, new THREE.BoxGeometry(W - 0.1, 0.25, D - 0.1), 0x8a8780, 0, 0.12, 0, 0.9, 0); // pad
  if (id === 'water_pump') {
    mk(g, new THREE.BoxGeometry(2.2, 1.6, 1.8), ORANGE, 0, 1.05, -0.4);
    mk(g, new THREE.CylinderGeometry(0.35, 0.35, 2.4, 8), STEEL, 0.9, 0.2, 1.1, 0.4, 0.5);
    const imp = mk(g, new THREE.CylinderGeometry(0.5, 0.5, 0.3, 8), DARK, -0.5, 2.0, -0.4); imp.rotation.x = Math.PI / 2;
    mk(g, new THREE.BoxGeometry(0.6, 0.4, 0.6), GREY, 0.6, 2.05, -0.4);
    statusLight(g, 1.12, 1.4, 0.52);
    anim = (dt, run) => { if (run) imp.rotation.y += dt * 8; };
  } else if (id === 'oil_pump') {
    // pumpjack: A-frame, walking beam, horse head, counterweight
    for (const s of [-0.5, 0.5]) { const leg = mk(g, new THREE.BoxGeometry(0.15, 3.4, 0.15), DARK, s, 1.8, -0.2); leg.rotation.z = -s * 0.25; }
    const pivot = new THREE.Group(); pivot.position.set(0, 3.4, -0.2); g.add(pivot);
    mk(pivot, new THREE.BoxGeometry(0.3, 0.35, 4.6), YEL, 0, 0, 0.3);
    mk(pivot, new THREE.BoxGeometry(0.5, 1.2, 0.5), YEL, 0, -0.35, 2.55);
    mk(pivot, new THREE.CylinderGeometry(0.7, 0.7, 0.3, 10), DARK, 0, -0.9, -2.0).rotation.z = Math.PI / 2;
    mk(g, new THREE.BoxGeometry(1.3, 1.0, 1.0), GREY, 0, 0.75, -2.0);
    mk(g, new THREE.CylinderGeometry(0.15, 0.15, 1.6, 6), STEEL, 0, 0.9, 2.3, 0.4, 0.5);
    mk(g, new THREE.CylinderGeometry(0.4, 0.4, 0.5, 8), DARK, 0, 0.45, 2.3);
    statusLight(g, 0.66, 1.1, -1.5);
    let t0 = 0; anim = (dt, run) => { if (run) t0 += dt * 1.6; pivot.rotation.x = Math.sin(t0) * 0.28; };
  } else if (id === 'refinery') {
    mk(g, new THREE.BoxGeometry(5.4, 1.8, 3), GREY, 0, 1.15, 1.2);
    for (const [x, h, r] of [[-1.6, 7, 0.6], [0, 9, 0.75], [1.6, 6, 0.55]] as [number, number, number][]) {
      mk(g, new THREE.CylinderGeometry(r, r * 1.05, h, 10), STEEL, x, h / 2 + 0.25, -1.2, 0.45, 0.45);
      for (let k = 1; k < h; k += 1.6) mk(g, new THREE.TorusGeometry(r + 0.04, 0.05, 4, 10).rotateX(Math.PI / 2), DARK, x, k, -1.2);
    }
    mk(g, new THREE.CylinderGeometry(0.12, 0.12, 4.4, 6), PIPE, 0, 2.4, 0.3, 0.6, 0.3).rotation.z = Math.PI / 2;
    const flare = mk(g, new THREE.ConeGeometry(0.25, 0.6, 6), 0xffa040, 0, 9.6, -1.2, 0.4, 0);
    (flare.material as THREE.MeshStandardMaterial) = getMaterial('emissiveWarm').clone();
    statusLight(g, 2.72, 1.5, 2.72);
    anim = (_dt, run, t) => { flare.visible = run; flare.scale.setScalar(1 + Math.sin(t * 12) * 0.15); };
  } else if (id === 'fuel_gen') {
    mk(g, new THREE.BoxGeometry(3.4, 2.0, 2.6), 0x5a6a5a, 0, 1.25, 0);
    for (let i = -1; i <= 1; i++) mk(g, new THREE.BoxGeometry(0.05, 1.2, 2.2), DARK, i * 1.0, 1.4, 0);
    const stack = mk(g, new THREE.CylinderGeometry(0.22, 0.25, 2.2, 8), DARK, 1.2, 3.2, -0.8);
    void stack;
    mk(g, new THREE.BoxGeometry(0.8, 0.8, 0.2), YEL, -1.2, 1.4, 1.32);
    statusLight(g, -0.8, 2.0, 1.32);
    anim = () => {};
  } else if (id === 'fluid_tank') {
    mk(g, new THREE.CylinderGeometry(1.7, 1.7, 3.6, 14), 0xc8c4b8, 0, 2.05, 0, 0.6, 0.15);
    mk(g, new THREE.CylinderGeometry(1.75, 1.75, 0.12, 14), DARK, 0, 3.9, 0);
    mk(g, new THREE.CylinderGeometry(1.75, 1.75, 0.12, 14), DARK, 0, 1.0, 0);
    mk(g, new THREE.BoxGeometry(0.1, 3.4, 0.1), DARK, 1.72, 2.0, 0);
    statusLight(g, 1.0, 1.4, 1.45);
  } else if (id === 'packager') {
    mk(g, new THREE.BoxGeometry(3.2, 2.2, 3.2), 0x4a7a8a, 0, 1.35, 0);
    mk(g, new THREE.BoxGeometry(1.2, 1.0, 0.2), DARK, 0, 1.0, 1.62);
    mk(g, new THREE.BoxGeometry(1.2, 1.0, 0.2), DARK, 0, 1.0, -1.62);
    mk(g, new THREE.CylinderGeometry(0.3, 0.3, 0.8, 8), STEEL, 1.0, 2.8, 0);
    const arm = mk(g, new THREE.BoxGeometry(0.2, 0.6, 0.2), ORANGE, -0.8, 2.75, 0);
    statusLight(g, 1.62, 2.0, 1.0);
    let t0 = 0; anim = (dt, run) => { if (run) t0 += dt * 5; arm.position.y = 2.75 + Math.abs(Math.sin(t0)) * 0.3; };
  }
  g.userData.animate = anim;
  return g;
}

/** pipe pieces for instancing: a junction node and an arm reaching +Z to the tile edge */
export function pipeGeometries() {
  const node = new THREE.CylinderGeometry(0.28, 0.28, 0.5, 8).rotateX(Math.PI / 2);
  node.translate(0, 1.1, 0);
  const post = new THREE.BoxGeometry(0.12, 1.0, 0.12); post.translate(0, 0.5, 0);
  const arm = new THREE.CylinderGeometry(0.18, 0.18, 1.0, 8).rotateX(Math.PI / 2); arm.translate(0, 1.1, 0.5);
  return { node, post, arm, mat: getFlatMaterial(PIPE, 0.55, 0.35), postMat: getFlatMaterial(DARK, 0.8, 0.2) };
}
