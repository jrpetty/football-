/**
 * First-person held item models (real-world scale, metres).
 *
 * Grip point (where the right hand closes) is the origin.
 *  - Melee weapons / tools extend along +Y; heads/blades with the striking edge facing −Z.
 *  - Guns: barrel toward −Z, pistol grip hangs toward −Y, sights on +Y.
 *    userData.muzzle (Object3D at muzzle tip); shotgun/rifle also userData.foreGrip.
 *  - Flashlight: lens toward −Z, userData.lens at the lens.
 */
import * as THREE from 'three';
import {
  PartBuilder, type Part, type Vec2, type Vec3, alignY, anchor, bevelBox, box, cylinder, displace, extrude,
  lathe, partsToGroup, polyShape, rng, sphere, tinted, torus, tube, xf,
} from './geom';

export type HeldModelId =
  | 'bat' | 'bat_nails' | 'crowbar' | 'axe' | 'knife' | 'machete' | 'hammer' | 'pipe'
  | 'sledgehammer' | 'pickaxe' | 'pistol' | 'shotgun' | 'rifle' | 'flashlight' | 'generic';

interface ItemDef { parts: Part[]; anchors: { name: string; key: string; p: Vec3 }[] }
const cache = new Map<HeldModelId, ItemDef>();

/** Rotate a Y-built geometry so +Y points to −Z (for gun barrels / flashlight bodies). */
const toNegZ = <T extends THREE.BufferGeometry>(g: T): T => xf(g, 0, 0, 0, -Math.PI / 2);

const gripTape = () => tinted('rubber', 0x2a2724);
const redPaint = () => tinted('metal', 0x7c2318);
const hickory = () => tinted('wood', 0xc49a6c);
const polymer = () => tinted('plasticBlack', 0x262626);

/** Ridged grip sleeve along Y (lathe), radius r, from y0 to y1 with n ridges. */
function ridgedSleeve(r: number, y0: number, y1: number, n: number, depth = 0.0012, seg = 12): THREE.BufferGeometry {
  const prof: Vec2[] = [[r * 0.85, y0], [r, y0 + 0.002]];
  const step = (y1 - y0 - 0.004) / n;
  for (let i = 0; i < n; i++) {
    const y = y0 + 0.002 + i * step;
    prof.push([r, y + step * 0.15], [r + depth, y + step * 0.35], [r + depth, y + step * 0.65], [r, y + step * 0.85]);
  }
  prof.push([r, y1 - 0.002], [r * 0.85, y1]);
  return lathe(prof, seg);
}

/** A blade extruded from an outline in (z, y), thickness along X, tapered toward the edge (−Z side). */
function blade(outline: Vec2[], thick: number, edgeZ: (y: number) => number, spineZ: (y: number) => number, bevel = 0.0008): THREE.BufferGeometry {
  const g = extrude(polyShape(outline), thick, { bevel, curveSegments: 4 });
  xf(g, 0, 0, 0, 0, -Math.PI / 2); // shape x → z, extrusion → −x
  displace(g, (v) => {
    const e = edgeZ(v.y), s = spineZ(v.y);
    const t = THREE.MathUtils.clamp((v.z - e) / Math.max(s - e, 1e-4), 0, 1);
    v.x *= 0.08 + 0.92 * Math.pow(t, 0.55);
  }, true);
  return g;
}

function batParts(pb: PartBuilder, nails: boolean): void {
  const prof: Vec2[] = [
    [0, -0.096], [0.02, -0.095], [0.029, -0.09], [0.031, -0.082], [0.027, -0.073], [0.016, -0.066],
    [0.0138, -0.04], [0.0136, 0.1], [0.014, 0.2], [0.0158, 0.28], [0.02, 0.36], [0.0255, 0.44],
    [0.0305, 0.53], [0.033, 0.61], [0.0337, 0.69], [0.0328, 0.725], [0.028, 0.742], [0.016, 0.751], [0, 0.753],
  ];
  pb.add(nails ? tinted('wood', 0x8f6e4c) : hickory(), lathe(prof, 16));
  // grip tape sleeve with overlapping wraps
  const tape: Vec2[] = [[0.0137, -0.064], [0.0152, -0.062]];
  for (let i = 0; i < 11; i++) {
    const y = -0.06 + i * 0.024;
    tape.push([0.0152, y + 0.002], [0.0158, y + 0.004], [0.0153, y + 0.022]);
  }
  tape.push([0.0154, 0.205], [0.0142, 0.207]);
  pb.add(gripTape(), lathe(tape, 14));
  if (nails) {
    const r = rng(77);
    const nailG: THREE.BufferGeometry[] = [];
    for (let i = 0; i < 16; i++) {
      const y = 0.43 + r() * 0.29;
      const a = r() * Math.PI * 2;
      const rad = 0.02 + (y - 0.36) / 0.33 * 0.012;
      const dir = new THREE.Vector3(Math.cos(a), (r() - 0.5) * 0.5, Math.sin(a)).normalize();
      const base = new THREE.Vector3(Math.cos(a) * rad * 0.7, y, Math.sin(a) * rad * 0.7);
      const len = 0.045 + r() * 0.03;
      const bent = r() < 0.3;
      const mid = base.clone().addScaledVector(dir, rad * 0.3 + len * (bent ? 0.6 : 1));
      nailG.push(alignY(cylinder(0.0017, 0.0019, base.distanceTo(mid), 4), base.toArray() as Vec3, mid.toArray() as Vec3));
      let tip = mid;
      if (bent) {
        const d2 = dir.clone().add(new THREE.Vector3(0, r() < 0.5 ? 0.9 : -0.9, 0)).normalize();
        tip = mid.clone().addScaledVector(d2, len * 0.4);
        nailG.push(alignY(cylinder(0.0016, 0.0017, mid.distanceTo(tip), 4), mid.toArray() as Vec3, tip.toArray() as Vec3));
      }
      // nail head on the surface for some (driven in from this side)
      if (r() < 0.25) {
        const hp = new THREE.Vector3(Math.cos(a + 1.3) * rad, y + 0.01, Math.sin(a + 1.3) * rad);
        nailG.push(alignY(cylinder(0.0042, 0.0042, 0.0016, 6), hp.toArray() as Vec3,
          hp.clone().add(new THREE.Vector3(Math.cos(a + 1.3), 0, Math.sin(a + 1.3)).multiplyScalar(0.0016)).toArray() as Vec3));
      }
    }
    pb.add('metalRust', ...nailG);
    // wire binding around the barrel
    pb.add('metalDark', xf(torus(0.0322, 0.0012, 4, 20), 0, 0.62, 0, Math.PI / 2), xf(torus(0.031, 0.0012, 4, 20), 0, 0.535, 0, Math.PI / 2));
  }
}

function crowbarParts(pb: PartBuilder): void {
  const path: Vec3[] = [
    [0, -0.22, 0.0], [0, -0.1, 0], [0, 0.2, 0], [0, 0.44, 0], [0, 0.5, -0.006], [0, 0.545, -0.03],
    [0, 0.56, -0.065], [0, 0.545, -0.098], [0, 0.515, -0.112],
  ];
  const g = tube(path, (t) => (t > 0.86 ? 0.0115 - (t - 0.86) / 0.14 * 0.004 : 0.0115), 40, 6, { start: true, end: true });
  displace(g, (v) => {
    if (v.y < -0.15) { // flattened chisel end
      const t = Math.min(1, (-0.15 - v.y) / 0.07);
      v.x *= 1 + t * 0.9; v.z *= 1 - t * 0.78;
    }
    if (v.z < -0.08) { // flattened claw
      const t = Math.min(1, (-0.08 - v.z) / 0.03);
      v.x *= 1 + t * 0.7;
    }
  }, true);
  pb.add(redPaint(), g);
  // claw prongs (split tip)
  for (const sx of [-1, 1]) {
    pb.add('metalDark', alignY(bevelBox(0.007, 0.022, 0.006, 0.0015), [sx * 0.0055, 0.512, -0.113], [sx * 0.0065, 0.494, -0.108]));
  }
  // bare worn steel at the chisel
  pb.add('steelBrushed', xf(bevelBox(0.036, 0.016, 0.006, 0.0015), 0, -0.224, 0));
}

function axeParts(pb: PartBuilder): void {
  // Curved hickory handle with oval section and swell at the bottom
  const hp: Vec3[] = [[0, -0.13, 0.004], [0, -0.05, 0.0], [0, 0.15, -0.004], [0, 0.4, 0.002], [0, 0.6, 0.0], [0, 0.67, 0]];
  const h = tube(hp, (t) => (t < 0.06 ? 0.019 - t * 0.04 : 0.0165 - t * 0.002), 24, 10, { start: true, end: true });
  displace(h, (v) => { v.z *= 1.35; }, false);
  pb.add(hickory(), h);
  pb.add(gripTape(), xf(ridgedSleeve(0.0172, -0.06, 0.12, 8, 0.0008, 12), 0, 0, 0, 0, 0, 0, 1, 1, 1.32));
  // Head: profile in (z, y), poll at +Z, bit at −Z
  const top = (z: number) => (z > -0.04 ? 0.03 : 0.03 + ((-0.04 - z) / 0.085) * 0.022);
  const bot = (z: number) => (z > -0.04 ? -0.03 : -0.03 - ((-0.04 - z) / 0.085) * 0.04);
  const zs = [0.045, 0.02, 0, -0.02, -0.04, -0.06, -0.08, -0.098];
  const main: Vec2[] = [...zs.map((z) => [z, bot(z)] as Vec2), ...[...zs].reverse().map((z) => [z, top(z)] as Vec2)];
  const edge: Vec2[] = [[-0.098, bot(-0.098)], [-0.112, bot(-0.112) + 0.002], [-0.123, -0.03], [-0.127, 0.0], [-0.122, 0.035],
    [-0.112, top(-0.112) - 0.001], [-0.098, top(-0.098)]];
  const taper = (g: THREE.BufferGeometry) => displace(g, (v) => {
    const t = THREE.MathUtils.clamp((-v.z - 0.025) / 0.1, 0, 1);
    v.x *= 1 - 0.88 * Math.pow(t, 0.8);
    v.x *= 1.1; v.y *= 1.3; v.z *= 1.3;
    v.y += 0.62;
  }, true);
  const mk = (pts: Vec2[], bev: number) => xf(extrude(polyShape(pts), 0.032, { bevel: bev }), 0, 0, 0, 0, -Math.PI / 2);
  pb.add(tinted('metalDark', 0x4a4846), taper(mk(main, 0.003)));
  pb.add('steelBrushed', taper(mk(edge, 0.0015)));
  // handle top + wedge visible through the eye
  pb.add('woodDark', xf(bevelBox(0.02, 0.008, 0.034, 0.002), 0, 0.664, 0.004));
  pb.add('metalDark', xf(box(0.003, 0.002, 0.03), 0, 0.669, 0.004));
}

function knifeParts(pb: PartBuilder): void {
  // Handle with finger grooves (oval lathe)
  const hp: Vec2[] = [[0.0, -0.075], [0.012, -0.075], [0.0135, -0.07], [0.0128, -0.05], [0.0115, -0.035], [0.0128, -0.022],
    [0.0115, -0.008], [0.0128, 0.006], [0.0115, 0.02], [0.0125, 0.035], [0.012, 0.05], [0.0105, 0.058], [0, 0.06]];
  pb.add(tinted('rubber', 0x2d2b26), xf(lathe(hp, 14), 0, 0, 0, 0, 0, 0, 0.72, 1, 1));
  pb.add('metalDark', xf(bevelBox(0.016, 0.01, 0.034, 0.002), 0, -0.078, 0)); // pommel
  pb.add('metalDark', xf(bevelBox(0.012, 0.008, 0.056, 0.0025), 0, 0.064, -0.004)); // guard
  const outline: Vec2[] = [[0.009, 0.068], [0.01, 0.17], [0.006, 0.205], [-0.003, 0.245], [-0.0115, 0.225],
    [-0.0155, 0.19], [-0.016, 0.12], [-0.014, 0.08], [-0.011, 0.068]];
  const edgeZ = (y: number) => (y < 0.19 ? -0.016 : -0.016 + (y - 0.19) / 0.055 * 0.013);
  const spineZ = (y: number) => (y < 0.17 ? 0.01 : 0.01 - (y - 0.17) / 0.075 * 0.013);
  pb.add('steelBrushed', blade(outline, 0.0052, edgeZ, spineZ));
  // fuller groove (dark inlay strip) on both sides
  for (const sx of [-1, 1]) pb.add('metalDark', xf(box(0.0004, 0.08, 0.004), sx * 0.0021, 0.13, 0.004));
}

function macheteParts(pb: PartBuilder): void {
  const hp: Vec2[] = [[0, -0.085], [0.013, -0.085], [0.0155, -0.078], [0.0145, -0.05], [0.0135, 0.0], [0.0142, 0.05], [0.0128, 0.065], [0, 0.066]];
  pb.add(polymer(), xf(lathe(hp, 12), 0, 0, 0, 0, 0, 0, 0.62, 1, 1.25));
  for (const y of [-0.05, 0.0, 0.045]) pb.add('steelBrushed', xf(cylinder(0.0035, 0.0035, 0.021, 8), 0, y, 0, 0, 0, Math.PI / 2));
  // long blade, slight belly toward the tip, edge to −Z
  const outline: Vec2[] = [[0.012, 0.06], [0.013, 0.3], [0.011, 0.47], [0.002, 0.515], [-0.018, 0.525], [-0.034, 0.5],
    [-0.035, 0.4], [-0.028, 0.2], [-0.02, 0.09], [-0.015, 0.06]];
  const edgeZ = (y: number) => (y < 0.09 ? -0.015 : y < 0.4 ? -0.02 - (y - 0.09) / 0.31 * 0.015 : -0.035 + Math.max(0, y - 0.48) * 0.4);
  const spineZ = (y: number) => (y < 0.47 ? 0.0125 : 0.0125 - (y - 0.47) / 0.05 * 0.03);
  pb.add('steelBrushed', blade(outline, 0.0038, edgeZ, spineZ, 0.0006));
  // tang plate
  pb.add('metalDark', xf(bevelBox(0.008, 0.012, 0.036, 0.002), 0, 0.064, -0.002));
}

function hammerParts(pb: PartBuilder): void {
  const hp: Vec2[] = [[0, -0.045], [0.014, -0.045], [0.0165, -0.035], [0.015, 0.05], [0.0125, 0.15], [0.011, 0.3], [0.0105, 0.33], [0, 0.331]];
  pb.add(hickory(), xf(lathe(hp, 12), 0, 0, 0, 0, 0, 0, 0.7, 1, 1));
  pb.add(tinted('rubber', 0x1c1c1c), xf(ridgedSleeve(0.0158, -0.044, 0.1, 10, 0.001, 12), 0, 0, 0, 0, 0, 0, 0.74, 1, 1.02));
  const y = 0.315;
  const steel = tinted('metalDark', 0x55555a);
  pb.add(steel, xf(bevelBox(0.024, 0.04, 0.038, 0.004), 0, y, 0.002));
  // neck + striking face toward −Z
  pb.add(steel, xf(cylinder(0.0125, 0.0145, 0.04, 12), 0, y, -0.035, Math.PI / 2));
  pb.add('steelBrushed', xf(cylinder(0.0155, 0.0155, 0.012, 14), 0, y, -0.061, Math.PI / 2));
  // claw toward +Z curving down, split in two prongs
  for (const sx of [-1, 1]) {
    const c = tube([[sx * 0.005, y + 0.008, 0.015], [sx * 0.006, y + 0.01, 0.045], [sx * 0.007, y - 0.002, 0.075], [sx * 0.008, y - 0.022, 0.092]],
      (t) => 0.0085 * (1 - t * 0.7), 10, 6, { end: true });
    displace(c, (v) => { v.x = sx * 0.005 + (v.x - sx * 0.005) * 0.55; }, true);
    pb.add(steel, c);
  }
}

function pipeParts(pb: PartBuilder): void {
  pb.add('metalRust', xf(cylinder(0.0167, 0.0167, 0.56, 14, { open: false }), 0, 0.16, 0));
  // threaded bottom end
  const th: Vec2[] = [[0.0, -0.13], [0.015, -0.13]];
  for (let i = 0; i < 8; i++) th.push([0.0168, -0.128 + i * 0.0025], [0.0156, -0.1268 + i * 0.0025]);
  th.push([0.0167, -0.108]);
  pb.add('metalDark', lathe(th, 12));
  // coupling + elbow fitting at the top
  pb.add('metalDark', xf(cylinder(0.021, 0.021, 0.042, 8), 0, 0.455, 0));
  pb.add('metalDark', xf(torus(0.032, 0.02, 8, 8, Math.PI / 2), -0.032, 0.476, 0, 0, 0, 0));
  pb.add('metalDark', xf(cylinder(0.022, 0.022, 0.02, 8), -0.036, 0.508, 0, 0, 0, Math.PI / 2));
  // cloth tape wrap for grip
  pb.add(tinted('fabric', 0x3d3a33), ridgedSleeve(0.0175, -0.08, 0.13, 7, 0.0008, 12));
}

function sledgeParts(pb: PartBuilder): void {
  const hp: Vec2[] = [[0, -0.1], [0.017, -0.1], [0.02, -0.09], [0.018, 0.0], [0.016, 0.4], [0.0155, 0.82], [0, 0.83]];
  pb.add(hickory(), xf(lathe(hp, 12), 0, 0, 0, 0, 0, 0, 0.72, 1, 1));
  pb.add(gripTape(), xf(ridgedSleeve(0.019, -0.09, 0.2, 12, 0.001, 12), 0, 0, 0, 0, 0, 0, 0.74, 1, 1));
  const steel = tinted('metalDark', 0x46464a);
  const y = 0.8;
  pb.add(steel, xf(bevelBox(0.064, 0.064, 0.15, 0.008), 0, y, 0));
  for (const sz of [-1, 1]) {
    pb.add('steelBrushed', xf(bevelBox(0.058, 0.058, 0.018, 0.006), 0, y, sz * 0.082));
  }
  pb.add('metalRust', xf(box(0.0005, 0.03, 0.06), 0.0322, y + 0.01, -0.02));
  pb.add('woodDark', xf(bevelBox(0.024, 0.008, 0.03, 0.002), 0, y + 0.034, 0));
}

function pickaxeParts(pb: PartBuilder): void {
  const hp: Vec2[] = [[0, -0.1], [0.018, -0.1], [0.021, -0.09], [0.019, 0.0], [0.017, 0.4], [0.019, 0.72], [0.02, 0.8], [0, 0.81]];
  pb.add(hickory(), xf(lathe(hp, 12), 0, 0, 0, 0, 0, 0, 0.72, 1, 1));
  pb.add(gripTape(), xf(ridgedSleeve(0.0198, -0.09, 0.16, 10, 0.001, 12), 0, 0, 0, 0, 0, 0, 0.74, 1, 1));
  const y = 0.77;
  const steel = tinted('metalDark', 0x4c4a48);
  const arc: Vec3[] = [[0, y - 0.05, -0.3], [0, y - 0.005, -0.18], [0, y + 0.018, -0.05], [0, y + 0.02, 0.0], [0, y + 0.018, 0.05], [0, y - 0.005, 0.15], [0, y - 0.04, 0.26]];
  const head = tube(arc, (t) => {
    const d = Math.abs(t - 0.5) * 2;
    return t < 0.5 ? 0.02 * (1 - Math.pow(d, 1.4) * 0.88) : 0.02 * (1 - Math.pow(d, 2) * 0.3);
  }, 28, 8, { start: true, end: true });
  displace(head, (v) => {
    if (v.z > 0.12) { // adze: flatten into a chisel
      const t = Math.min(1, (v.z - 0.12) / 0.14);
      const cy = y - 0.005 - (v.z - 0.15) * 0.3;
      v.x *= 1 + t * 0.8;
      v.y = cy + (v.y - cy) * (1 - t * 0.7);
    }
  }, true);
  pb.add(steel, head);
  pb.add(steel, xf(bevelBox(0.05, 0.06, 0.06, 0.006), 0, y + 0.005, 0));
  pb.add('steelBrushed', xf(bevelBox(0.034, 0.006, 0.01, 0.002), 0, y - 0.04, 0.262, 0.4));
}

// --------------------------------------------------------------------------- guns

function pistolParts(pb: PartBuilder, anchors: ItemDef['anchors']): void {
  const frame = polymer();
  const slide = tinted('metalDark', 0x323234);
  // grip (raked) with magazine baseplate
  pb.add(frame, xf(bevelBox(0.03, 0.118, 0.05, 0.006), 0, -0.022, 0.006, -0.3));
  // stipple panels (slightly raised, rougher look)
  for (const sx of [-1, 1]) pb.add(tinted('rubber', 0x202020), xf(bevelBox(0.002, 0.07, 0.034, 0.0008), sx * 0.0152, -0.022, 0.008, -0.3));
  pb.add(slide, xf(bevelBox(0.032, 0.008, 0.054, 0.003), 0, -0.081, 0.024, -0.3)); // mag baseplate
  // dust cover / frame upper with rail
  pb.add(frame, xf(bevelBox(0.024, 0.026, 0.16, 0.004), 0, 0.035, -0.05));
  for (let i = 0; i < 3; i++) pb.add(frame, xf(box(0.022, 0.003, 0.006), 0, 0.022, -0.09 - i * 0.012));
  // trigger guard
  pb.add(frame, tube([[0, 0.026, -0.012], [0, 0.0, -0.016], [0, -0.012, -0.04], [0, -0.008, -0.068], [0, 0.024, -0.08]], 0.0035, 14, 5));
  pb.add(frame, xf(box(0.008, 0.012, 0.008), 0, 0.019, -0.079));
  // trigger
  pb.add(slide, tube([[0, 0.026, -0.04], [0, 0.012, -0.044], [0, 0.002, -0.04]], 0.0022, 6, 4));
  // slide
  pb.add(slide, xf(bevelBox(0.026, 0.03, 0.19, 0.0045), 0, 0.061, -0.058));
  // slide serrations (rear), both sides
  for (const sx of [-1, 1]) for (let i = 0; i < 8; i++) {
    pb.add(frame, xf(box(0.0008, 0.021, 0.0016), sx * 0.0131, 0.059, 0.031 - i * 0.0035));
  }
  // ejection port + barrel hood
  pb.add(frame, xf(box(0.013, 0.0012, 0.032), 0.002, 0.0762, -0.02));
  pb.add('steelBrushed', xf(box(0.011, 0.0016, 0.028), 0.002, 0.0764, -0.022));
  // muzzle: barrel crown + bore
  pb.add('steelBrushed', xf(cylinder(0.0066, 0.0066, 0.004, 12), 0, 0.061, -0.1545, Math.PI / 2));
  pb.add('plasticBlack', xf(cylinder(0.0046, 0.0046, 0.0048, 10), 0, 0.061, -0.1548, Math.PI / 2));
  // sights with white dots
  pb.add(slide, xf(bevelBox(0.0035, 0.006, 0.007, 0.001), 0, 0.0785, -0.144));
  for (const sx of [-1, 1]) pb.add(slide, xf(bevelBox(0.007, 0.0065, 0.008, 0.001), sx * 0.0058, 0.0787, 0.028));
  pb.add('plasticWhite', xf(box(0.0012, 0.0012, 0.0004), 0, 0.08, -0.1476));
  for (const sx of [-1, 1]) pb.add('plasticWhite', xf(box(0.0012, 0.0012, 0.0004), sx * 0.0058, 0.0795, 0.0238));
  // slide stop, takedown, mag release
  pb.add(slide, xf(bevelBox(0.002, 0.004, 0.022, 0.0008), -0.0145, 0.044, -0.03));
  pb.add(slide, xf(bevelBox(0.0022, 0.005, 0.008, 0.0008), -0.0145, 0.036, -0.062));
  pb.add(frame, xf(bevelBox(0.032, 0.007, 0.007, 0.0015), 0, 0.016, -0.007));
  // rear striker plate
  pb.add(frame, xf(box(0.018, 0.02, 0.0012), 0, 0.06, 0.0372));
  anchors.push({ name: 'muzzle', key: 'muzzle', p: [0, 0.061, -0.157] });
}

function shotgunParts(pb: PartBuilder, anchors: ItemDef['anchors']): void {
  const steel = tinted('metalDark', 0x2e2e30);
  const walnut = tinted('woodDark', 0x7a5236);
  // receiver
  pb.add(steel, xf(bevelBox(0.034, 0.064, 0.21, 0.005), 0, 0.034, -0.125));
  pb.add(polymer(), xf(box(0.0012, 0.026, 0.07), 0.0172, 0.046, -0.11)); // ejection port
  pb.add('steelBrushed', xf(box(0.001, 0.02, 0.06), 0.0168, 0.046, -0.11)); // bolt face
  pb.add(polymer(), xf(box(0.022, 0.0012, 0.08), 0, 0.0018, -0.13)); // loading port
  // barrel + vent rib + bead
  const bz0 = -0.23, bz1 = -0.735;
  pb.add(steel, xf(cylinder(0.0118, 0.0124, bz0 - bz1, 14), 0, 0.05, (bz0 + bz1) / 2, Math.PI / 2));
  pb.add(steel, xf(box(0.0065, 0.004, bz0 - bz1 - 0.01), 0, 0.0635, (bz0 + bz1) / 2));
  for (let i = 0; i < 14; i++) pb.add(polymer(), xf(box(0.0066, 0.0028, 0.003), 0, 0.0635, bz0 - 0.02 - i * 0.035));
  pb.add('chrome', xf(sphere(0.0022, 8, 6), 0, 0.0675, bz1 + 0.006));
  pb.add('plasticBlack', xf(cylinder(0.0095, 0.0095, 0.002, 12), 0, 0.05, bz1 - 0.0005, Math.PI / 2));
  // magazine tube, cap, barrel band
  pb.add(steel, xf(cylinder(0.0108, 0.0108, 0.43, 12), 0, 0.019, -0.445, Math.PI / 2));
  pb.add(steel, xf(cylinder(0.0118, 0.0118, 0.022, 12), 0, 0.019, -0.668, Math.PI / 2));
  pb.add(steel, xf(bevelBox(0.026, 0.05, 0.014, 0.004), 0, 0.034, -0.64));
  // ribbed pump forend around the mag tube
  const fp: Vec2[] = [[0.016, 0], [0.021, 0.004]];
  for (let i = 0; i < 9; i++) { const y = 0.012 + i * 0.019; fp.push([0.0215, y], [0.0195, y + 0.003], [0.0195, y + 0.007], [0.0215, y + 0.01]); }
  fp.push([0.021, 0.196], [0.016, 0.2]);
  pb.add(walnut, xf(lathe(fp, 14), 0, 0.02, -0.36, -Math.PI / 2, 0, 0, 1.08, 1, 1.18));
  for (const sx of [-1, 1]) pb.add(steel, xf(box(0.002, 0.006, 0.14), sx * 0.012, 0.02, -0.29));
  // trigger guard + trigger + safety
  pb.add(steel, tube([[0, 0.003, -0.015], [0, -0.02, -0.022], [0, -0.026, -0.05], [0, -0.016, -0.078], [0, 0.003, -0.085]], 0.004, 12, 5));
  pb.add(steel, tube([[0, 0.002, -0.052], [0, -0.01, -0.056], [0, -0.018, -0.051]], 0.0024, 6, 4));
  pb.add('steelBrushed', xf(cylinder(0.003, 0.003, 0.038, 8), 0, 0.008, -0.022, 0, 0, Math.PI / 2));
  // stock (side profile extrude with soft bevel)
  const sp: Vec2[] = [[-0.02, 0.062], [0.06, 0.05], [0.2, 0.04], [0.37, 0.036], [0.38, 0.032], [0.39, -0.11], [0.375, -0.122],
    [0.24, -0.075], [0.12, -0.035], [0.05, -0.022], [0.0, -0.016], [-0.02, 0.004]];
  const stock = xf(extrude(polyShape(sp), 0.042, { bevel: 0.009, bevelSegments: 2 }), 0, 0, 0, 0, -Math.PI / 2);
  displace(stock, (v) => { if (v.z < 0.12) v.x *= 0.82 + 0.18 * (v.z + 0.02) / 0.14; }, true);
  pb.add(walnut, stock);
  pb.add('rubber', xf(bevelBox(0.044, 0.15, 0.016, 0.004), 0, -0.044, 0.392, 0.07));
  anchors.push({ name: 'muzzle', key: 'muzzle', p: [0, 0.05, bz1 - 0.002] });
  anchors.push({ name: 'foreGrip', key: 'foreGrip', p: [0, 0.0, -0.46] });
}

function rifleParts(pb: PartBuilder, anchors: ItemDef['anchors']): void {
  const steel = tinted('metalDark', 0x2c2c2e);
  const walnut = tinted('wood', 0x8a5a36);
  // one-piece stock with pistol grip
  const sp: Vec2[] = [[-0.63, 0.038], [-0.2, 0.04], [0.04, 0.042], [0.09, 0.055], [0.38, 0.058], [0.4, 0.056], [0.41, -0.1],
    [0.395, -0.112], [0.15, -0.035], [0.095, -0.04], [0.065, -0.088], [0.03, -0.092], [0.012, -0.02], [-0.02, -0.012],
    [-0.09, -0.012], [-0.3, 0.004], [-0.62, 0.016], [-0.635, 0.026]];
  const stock = xf(extrude(polyShape(sp), 0.044, { bevel: 0.01, bevelSegments: 2 }), 0, 0, 0, 0, -Math.PI / 2);
  displace(stock, (v) => {
    if (v.z < -0.1) v.x *= 0.78; // slimmer forend
    else if (v.z < 0.0) v.x *= 0.78 + 0.22 * (v.z + 0.1) / 0.1;
    if (v.y > 0.03 && v.z < 0.05) v.x *= 0.85; // channel edges
  }, true);
  pb.add(walnut, stock);
  // checkering panels on grip/forend (darker inlays)
  for (const sx of [-1, 1]) {
    pb.add('woodDark', xf(box(0.0008, 0.04, 0.05), sx * 0.0175, -0.045, 0.06, -0.55));
    pb.add('woodDark', xf(box(0.0008, 0.025, 0.14), sx * 0.0172, 0.02, -0.33));
  }
  pb.add('rubber', xf(bevelBox(0.046, 0.165, 0.016, 0.004), 0, -0.022, 0.412, 0.04));
  pb.add('plasticBlack', xf(box(0.047, 0.163, 0.002), 0, -0.022, 0.403, 0.04)); // spacer
  // action + barrel
  pb.add(steel, xf(cylinder(0.016, 0.016, 0.22, 14), 0, 0.056, -0.06, Math.PI / 2));
  pb.add(steel, xf(cylinder(0.008, 0.0128, 0.52, 14), 0, 0.056, -0.43, Math.PI / 2));
  pb.add('plasticBlack', xf(cylinder(0.0045, 0.0045, 0.002, 10), 0, 0.056, -0.6905, Math.PI / 2));
  // bolt + handle (right side)
  pb.add('steelBrushed', xf(cylinder(0.0095, 0.0095, 0.09, 12), 0.004, 0.06, 0.02, Math.PI / 2));
  pb.add('steelBrushed', tube([[0.012, 0.06, 0.03], [0.03, 0.05, 0.034], [0.042, 0.036, 0.04]], 0.0035, 6, 6));
  pb.add(steel, xf(sphere(0.0085, 10, 8), 0.045, 0.032, 0.041));
  // trigger guard / floorplate / trigger
  pb.add(steel, xf(bevelBox(0.02, 0.006, 0.09, 0.002), 0, -0.012, -0.065));
  pb.add(steel, tube([[0, -0.012, -0.01], [0, -0.035, -0.012], [0, -0.04, -0.03], [0, -0.03, -0.055], [0, -0.012, -0.06]], 0.0035, 10, 5));
  pb.add(steel, tube([[0, -0.012, -0.028], [0, -0.022, -0.031], [0, -0.03, -0.027]], 0.0022, 6, 4));
  // scope
  const sy = 0.105;
  pb.add(steel, xf(cylinder(0.0127, 0.0127, 0.2, 16), 0, sy, -0.02, Math.PI / 2));
  const obj: Vec2[] = [[0.0127, 0], [0.0135, 0.02], [0.022, 0.055], [0.0245, 0.062], [0.0245, 0.085], [0.0225, 0.09]];
  pb.add(steel, xf(lathe(obj, 18), 0, sy, -0.12, -Math.PI / 2));
  const ocu: Vec2[] = [[0.0127, 0], [0.0135, 0.015], [0.0195, 0.03], [0.0205, 0.07], [0.0195, 0.075]];
  pb.add(steel, xf(lathe(ocu, 16), 0, sy, 0.08, Math.PI / 2));
  pb.add(polymer(), xf(ridgedSleeve(0.0206, 0.035, 0.065, 5, 0.001, 16), 0, sy, 0.08, Math.PI / 2));
  pb.add('glass', xf(cylinder(0.0225, 0.0225, 0.002, 18), 0, sy, -0.2085, Math.PI / 2));
  pb.add('glass', xf(cylinder(0.0185, 0.0185, 0.002, 16), 0, sy, 0.1535, Math.PI / 2));
  pb.add(steel, xf(cylinder(0.0105, 0.0105, 0.02, 12), 0, sy + 0.018, -0.02));
  pb.add(steel, xf(cylinder(0.0105, 0.0105, 0.02, 12), 0.018, sy, -0.02, 0, 0, Math.PI / 2));
  pb.add(polymer(), xf(cylinder(0.011, 0.011, 0.006, 12), 0, sy + 0.03, -0.02));
  for (const z of [-0.07, 0.04]) {
    pb.add(steel, xf(cylinder(0.0155, 0.0155, 0.012, 14), 0, sy, z, Math.PI / 2));
    pb.add(steel, xf(bevelBox(0.016, 0.03, 0.014, 0.003), 0, sy - 0.026, z));
  }
  // sling swivels
  pb.add('steelBrushed', xf(torus(0.007, 0.0015, 4, 10), 0, -0.013, -0.5, 0, Math.PI / 2));
  pb.add('steelBrushed', xf(torus(0.007, 0.0015, 4, 10), 0, -0.088, 0.3, 0, Math.PI / 2));
  anchors.push({ name: 'muzzle', key: 'muzzle', p: [0, 0.056, -0.692] });
  anchors.push({ name: 'foreGrip', key: 'foreGrip', p: [0, 0.005, -0.36] });
}

function flashlightParts(pb: PartBuilder, anchors: ItemDef['anchors']): void {
  const alu = tinted('metalDark', 0x8a8a90);
  // body (+Z tail → −Z head); build along +Y then rotate to −Z
  const body: Vec2[] = [[0, -0.135], [0.012, -0.135], [0.0165, -0.13], [0.0172, -0.11], [0.0168, -0.104], [0.0168, -0.075]];
  for (let i = 0; i < 14; i++) { const y = -0.07 + i * 0.0076; body.push([0.0168, y], [0.0174, y + 0.0015], [0.0174, y + 0.006], [0.0168, y + 0.0075]); }
  body.push([0.0168, 0.0365]);
  body.push([0.0168, 0.04]);
  body.push([0.0168, 0.078], [0.0185, 0.085], [0.0235, 0.115], [0.0255, 0.118], [0.0255, 0.135], [0.0235, 0.137]);
  pb.add(alu, toNegZ(lathe(body, 14)));
  pb.add('chrome', toNegZ(lathe([[0.004, 0.105], [0.012, 0.115], [0.021, 0.131]], 18)));
  pb.add('emissiveWarm', toNegZ(xf(sphere(0.003, 8, 6), 0, 0.108, 0)));
  pb.add('glass', toNegZ(xf(cylinder(0.0225, 0.0225, 0.0015, 18), 0, 0.134, 0)));
  pb.add('rubber', xf(bevelBox(0.009, 0.004, 0.016, 0.0015), 0, 0.018, -0.045)); // switch
  pb.add(alu, xf(torus(0.004, 0.0012, 4, 10), 0, 0, 0.138, 0, 0, 0)); // lanyard loop
  anchors.push({ name: 'lens', key: 'lens', p: [0, 0, -0.136] });
}

function genericParts(pb: PartBuilder): void {
  // cloth-wrapped scavenged bundle tied with twine
  const g = bevelBox(0.09, 0.11, 0.07, 0.018);
  displace(g, (v) => { v.x += Math.sin(v.y * 60) * 0.002; v.z += Math.cos(v.x * 70) * 0.002; });
  pb.add(tinted('fabric', 0x7a6e58), g);
  const twine = tinted('fabric', 0xa08c60);
  pb.add(twine, xf(bevelBox(0.094, 0.006, 0.074, 0.002), 0, 0.02, 0), xf(bevelBox(0.094, 0.006, 0.074, 0.002), 0, -0.025, 0));
  pb.add(twine, xf(bevelBox(0.006, 0.114, 0.074, 0.002), 0.01, 0, 0));
  pb.add(twine, xf(sphere(0.008, 6, 4), 0.01, 0.058, 0));
}

function define(id: HeldModelId): ItemDef {
  const pb = new PartBuilder();
  const anchors: ItemDef['anchors'] = [];
  switch (id) {
    case 'bat': batParts(pb, false); break;
    case 'bat_nails': batParts(pb, true); break;
    case 'crowbar': crowbarParts(pb); break;
    case 'axe': axeParts(pb); break;
    case 'knife': knifeParts(pb); break;
    case 'machete': macheteParts(pb); break;
    case 'hammer': hammerParts(pb); break;
    case 'pipe': pipeParts(pb); break;
    case 'sledgehammer': sledgeParts(pb); break;
    case 'pickaxe': pickaxeParts(pb); break;
    case 'pistol': pistolParts(pb, anchors); break;
    case 'shotgun': shotgunParts(pb, anchors); break;
    case 'rifle': rifleParts(pb, anchors); break;
    case 'flashlight': flashlightParts(pb, anchors); break;
    default: genericParts(pb); break;
  }
  return { parts: pb.build(), anchors };
}

/** Build a held item model. Geometry/materials are cached and shared between calls. */
export function buildItemModel(id: HeldModelId): THREE.Group {
  let def = cache.get(id);
  if (!def) { def = define(id); cache.set(id, def); }
  const g = partsToGroup(def.parts, true, true);
  g.name = `item:${id}`;
  for (const a of def.anchors) {
    const o = anchor(a.name, a.p[0], a.p[1], a.p[2]);
    g.add(o);
    g.userData[a.key] = o;
  }
  return g;
}
