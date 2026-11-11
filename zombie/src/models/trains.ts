// Railway models: diesel-electric locomotive, freight wagon (animated wheels), and track pieces for instancing.
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { getMaterial, MaterialName } from '../render/materials';

function box(g: THREE.Object3D, mat: MaterialName, w: number, h: number, d: number, x: number, y: number, z: number) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), getMaterial(mat)); m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true; g.add(m); return m;
}
function wheelset(g: THREE.Object3D, z: number, wheels: THREE.Object3D[]) {
  const axle = new THREE.Group(); axle.position.set(0, 0.42, z); g.add(axle);
  for (const s of [-0.55, 0.55]) {
    const w = new THREE.Mesh(new THREE.CylinderGeometry(0.42, 0.42, 0.12, 14), getMaterial('metalDark')); w.rotation.z = Math.PI / 2; w.position.x = s; w.castShadow = true; axle.add(w);
    const hub = new THREE.Mesh(new THREE.CylinderGeometry(0.14, 0.14, 0.14, 8), getMaterial('steelBrushed')); hub.rotation.z = Math.PI / 2; hub.position.x = s; axle.add(hub);
    const spoke = new THREE.Mesh(new THREE.BoxGeometry(0.13, 0.7, 0.08), getMaterial('metalRust')); spoke.position.x = s; axle.add(spoke);
  }
  wheels.push(axle);
}
function bogie(g: THREE.Object3D, z: number, wheels: THREE.Object3D[]) {
  box(g, 'metalDark', 1.5, 0.35, 2.6, 0, 0.6, z);
  wheelset(g, z - 0.75, wheels); wheelset(g, z + 0.75, wheels);
  for (const s of [-0.82, 0.82]) { const spring = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.1, 0.3, 8), getMaterial('metal')); spring.position.set(s, 0.7, z); g.add(spring); }
}

/** 10 m locomotive, front toward +Z. userData.wheels: axle groups to spin; userData.light: Object3D at the headlamp. */
export function buildLocomotive(): THREE.Group {
  const g = new THREE.Group(); const wheels: THREE.Object3D[] = [];
  bogie(g, 3, wheels); bogie(g, -3, wheels);
  box(g, 'metalDark', 2.4, 0.3, 9.6, 0, 0.95, 0);                       // frame
  box(g, 'hazard', 2.5, 0.12, 0.3, 0, 0.9, 4.85); box(g, 'hazard', 2.5, 0.12, 0.3, 0, 0.9, -4.85);
  box(g, 'factoryOrange', 2.0, 1.9, 5.8, 0, 2.05, -1.4);                // long hood
  for (let i = 0; i < 6; i++) box(g, 'grating', 0.02, 0.9, 0.7, 1.01, 2.1, -3.8 + i * 0.85);
  for (let i = 0; i < 6; i++) box(g, 'grating', 0.02, 0.9, 0.7, -1.01, 2.1, -3.8 + i * 0.85);
  box(g, 'factoryOrange', 2.4, 2.5, 2.3, 0, 2.35, 2.6);                  // cab
  box(g, 'roofMetal', 2.6, 0.15, 2.6, 0, 3.66, 2.6);
  box(g, 'glassDirty', 2.0, 0.8, 0.05, 0, 2.95, 3.78);                   // windscreen
  for (const s of [-1.21, 1.21]) box(g, 'glassDirty', 0.05, 0.7, 1.2, s, 2.95, 2.6);
  box(g, 'factoryOrange', 1.8, 1.0, 1.0, 0, 1.6, 4.2);                   // short hood / nose
  const lamp = box(g, 'emissiveWarm', 0.35, 0.25, 0.06, 0, 2.2, 4.72); lamp.name = 'headlamp';
  box(g, 'metalDark', 0.45, 0.5, 0.45, 0.5, 3.15, -3.4); box(g, 'metalDark', 0.45, 0.5, 0.45, -0.5, 3.15, -3.4); // exhaust stacks
  for (const s of [-1.3, 1.3]) { box(g, 'metal', 0.06, 0.06, 9.4, s, 1.5, 0); for (let i = -4; i <= 4; i += 2) box(g, 'metal', 0.05, 0.6, 0.05, s, 1.25, i); } // handrails
  box(g, 'metalDark', 0.4, 0.3, 0.4, 0, 0.95, 5.0); box(g, 'metalDark', 0.4, 0.3, 0.4, 0, 0.95, -5.0); // couplers
  // number board
  box(g, 'plasticWhite', 0.9, 0.25, 0.04, 0, 3.35, 3.78);
  const lightAnchor = new THREE.Object3D(); lightAnchor.position.set(0, 2.2, 5); g.add(lightAnchor);
  g.userData.wheels = wheels; g.userData.light = lightAnchor; g.userData.length = 10;
  return g;
}

/** 9 m open freight wagon with a visible cargo heap (userData.cargo scales with load). */
export function buildWagon(): THREE.Group {
  const g = new THREE.Group(); const wheels: THREE.Object3D[] = [];
  bogie(g, 2.8, wheels); bogie(g, -2.8, wheels);
  box(g, 'metalDark', 2.4, 0.3, 8.8, 0, 0.95, 0);
  box(g, 'metalRust', 2.5, 0.12, 8.6, 0, 1.15, 0);                       // floor
  for (const s of [-1.22, 1.22]) { box(g, 'metalRust', 0.08, 1.4, 8.6, s, 1.9, 0); for (let i = -4; i <= 4; i += 1) box(g, 'metalDark', 0.12, 1.45, 0.1, s * 1.03, 1.9, i); }
  for (const s of [-4.28, 4.28]) box(g, 'metalRust', 2.5, 1.4, 0.08, 0, 1.9, s);
  box(g, 'metalDark', 0.4, 0.3, 0.4, 0, 0.95, 4.55); box(g, 'metalDark', 0.4, 0.3, 0.4, 0, 0.95, -4.55);
  const cargo = new THREE.Mesh(new THREE.BoxGeometry(2.3, 1, 8.3), getMaterial('dirt')); cargo.position.set(0, 1.25, 0); cargo.scale.y = 0.01; cargo.name = 'cargo'; g.add(cargo);
  g.userData.wheels = wheels; g.userData.cargo = cargo; g.userData.length = 9;
  return g;
}

/** Track geometry: a straight half-tile arm (centre → +Z edge) and a quarter-circle curve linking +Z and +X edges. */
export function trackGeometries() {
  const G = 2, gauge = 0.55;
  const arm = (): Record<string, THREE.BufferGeometry> => {
    const ballast = new THREE.BoxGeometry(2.0, 0.18, 1.0); ballast.translate(0, 0.09, 0.5);
    const sl: THREE.BufferGeometry[] = [];
    for (const z of [0.25, 0.75]) { const s = new THREE.BoxGeometry(1.8, 0.1, 0.22); s.translate(0, 0.22, z); sl.push(s); }
    const rails: THREE.BufferGeometry[] = [];
    for (const x of [-gauge, gauge]) { const r = new THREE.BoxGeometry(0.08, 0.12, 1.0); r.translate(x, 0.33, 0.5); rails.push(r); }
    return { ballast, sleepers: mergeGeometries(sl)!, rails: mergeGeometries(rails)! };
  };
  const curve = (): Record<string, THREE.BufferGeometry> => {
    // arc centred on the tile corner (+1,+1) with radius 1 (tile-centre units)
    const pivot = new THREE.Vector2(G / 2, G / 2); const N = 8;
    const ring = (rIn: number, rOut: number, y0: number, y1: number) => {
      const parts: THREE.BufferGeometry[] = [];
      for (let i = 0; i < N; i++) {
        const a0 = Math.PI + (i / N) * Math.PI / 2, a1 = Math.PI + ((i + 1) / N) * Math.PI / 2;
        const am = (a0 + a1) / 2; const rm = (rIn + rOut) / 2; const len = rm * (a1 - a0) + 0.02;
        const b = new THREE.BoxGeometry(rOut - rIn, y1 - y0, len);
        b.rotateY(-am); b.translate(pivot.x + Math.cos(am) * rm, (y0 + y1) / 2, pivot.y + Math.sin(am) * rm);
        // box local z along tangent: rotateY(-am) orients x radially
        parts.push(b);
      }
      return mergeGeometries(parts)!;
    };
    const ballast = ring(1 - 1.0, 1 + 1.0, 0, 0.18);
    const rails = mergeGeometries([ring(1 - gauge - 0.04, 1 - gauge + 0.04, 0.27, 0.39), ring(1 + gauge - 0.04, 1 + gauge + 0.04, 0.27, 0.39)])!;
    const sl: THREE.BufferGeometry[] = [];
    for (let i = 0; i < 4; i++) { const a = Math.PI + ((i + 0.5) / 4) * Math.PI / 2; const s = new THREE.BoxGeometry(1.8, 0.1, 0.22); s.rotateY(-a); s.translate(pivot.x + Math.cos(a), 0.22, pivot.y + Math.sin(a)); sl.push(s); }
    return { ballast, sleepers: mergeGeometries(sl)!, rails };
  };
  const stop = new THREE.BoxGeometry(1.6, 0.8, 0.3); stop.translate(0, 0.6, -0.6);
  return { arm: arm(), curve: curve(), stop, mats: { ballast: getMaterial('rock'), sleepers: getMaterial('woodDark'), rails: getMaterial('steelBrushed'), stop: getMaterial('hazard') } };
}

/** 4×4 m freight station: loading platform, canopy, gantry hopper and crates. Front (+Z) faces the track side. */
export function buildStation(): THREE.Group {
  const g = new THREE.Group(); g.userData.statusLights = []; g.userData.footprint = [2, 2];
  box(g, 'concrete', 3.9, 0.9, 3.9, 0, 0.45, 0);                         // platform
  box(g, 'hazard', 3.9, 0.06, 0.25, 0, 0.92, 1.85);                      // edge stripe
  for (const [x, z] of [[-1.7, -1.7], [1.7, -1.7], [-1.7, 1.2], [1.7, 1.2]]) box(g, 'metalDark', 0.18, 3.2, 0.18, x, 2.5, z);
  const roof = box(g, 'roofMetal', 4.3, 0.14, 4.4, 0, 4.15, -0.1); roof.rotation.x = 0.06;
  box(g, 'factoryOrange', 1.6, 1.4, 1.6, -0.8, 2.9, 0.2);                // hopper
  const chute = new THREE.Mesh(new THREE.CylinderGeometry(0.2, 0.35, 1.2, 8), getMaterial('metalRust')); chute.position.set(-0.8, 1.7, 0.9); chute.rotation.x = 0.5; g.add(chute);
  for (const [x, z, s] of [[1.1, -1.0, 0.8], [1.3, 0.0, 0.6], [0.4, -1.3, 0.7]] as [number, number, number][]) box(g, 'wood', s, s, s, x, 0.9 + s / 2, z);
  box(g, 'metalDark', 1.4, 0.5, 0.06, 0, 3.6, 1.95);                     // sign board
  const sign = box(g, 'emissiveWarm', 1.2, 0.08, 0.02, 0, 3.4, 1.99); sign.name = 'sign';
  const m = new THREE.Mesh(new THREE.SphereGeometry(0.08, 6, 4), getMaterial('emissiveGreen').clone()); m.position.set(1.7, 2.0, 1.32); g.add(m); (g.userData.statusLights as THREE.Mesh[]).push(m);
  g.userData.animate = () => {};
  return g;
}
