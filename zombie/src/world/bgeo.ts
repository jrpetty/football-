// Geometry accumulation helpers for procedural architecture (metre-scaled UVs, merged per material).
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { getMaterial, getTintedMaterial, MaterialName } from '../render/materials';

/** Box with UVs in metres (1 uv = 1 m) on every face. */
export function mBox(w: number, h: number, d: number, uvScale = 1): THREE.BufferGeometry {
  const g = new THREE.BoxGeometry(w, h, d);
  const uv = g.attributes.uv as THREE.BufferAttribute; const n = g.attributes.normal as THREE.BufferAttribute;
  for (let i = 0; i < uv.count; i++) {
    const nx = Math.abs(n.getX(i)), ny = Math.abs(n.getY(i));
    let su: number, sv: number;
    if (nx > 0.5) { su = d; sv = h; } else if (ny > 0.5) { su = w; sv = d; } else { su = w; sv = h; }
    uv.setXY(i, uv.getX(i) * su * uvScale, uv.getY(i) * sv * uvScale);
  }
  return g;
}

export class GeoBag {
  parts = new Map<string, THREE.BufferGeometry[]>();
  private m = new THREE.Matrix4(); private q = new THREE.Quaternion(); private e = new THREE.Euler(); private s = new THREE.Vector3(1, 1, 1); private p = new THREE.Vector3();
  add(mat: MaterialName | string, g: THREE.BufferGeometry, x = 0, y = 0, z = 0, ry = 0, rx = 0, rz = 0) {
    this.e.set(rx, ry, rz); this.q.setFromEuler(this.e); this.p.set(x, y, z);
    this.m.compose(this.p, this.q, this.s);
    const c = g.index ? g.toNonIndexed() : g.clone();
    c.applyMatrix4(this.m);
    if (!c.attributes.uv) c.setAttribute('uv', new THREE.Float32BufferAttribute(new Float32Array(c.attributes.position.count * 2), 2));
    for (const k of Object.keys(c.attributes)) if (k !== 'position' && k !== 'normal' && k !== 'uv') c.deleteAttribute(k);
    let a = this.parts.get(mat); if (!a) { a = []; this.parts.set(mat, a); } a.push(c);
  }
  box(mat: MaterialName | string, cx: number, cy: number, cz: number, w: number, h: number, d: number, ry = 0) {
    this.add(mat, mBox(w, h, d), cx, cy, cz, ry);
  }
  build(castShadow = true, receiveShadow = true, matOverride?: (name: string) => THREE.Material): THREE.Group {
    const g = new THREE.Group();
    for (const [name, list] of this.parts) {
      if (!list.length) continue;
      const geo = mergeGeometries(list, false);
      if (!geo) continue;
      geo.computeBoundingSphere();
      const mat = matOverride ? matOverride(name) : name.includes(':') ? getTintedMaterial(name.split(':')[0] as MaterialName, parseInt(name.split(':')[1], 16)) : getMaterial(name as MaterialName);
      const mesh = new THREE.Mesh(geo, mat);
      mesh.castShadow = castShadow && !(mat as any).transparent; mesh.receiveShadow = receiveShadow;
      g.add(mesh);
    }
    return g;
  }
}

/** Triangular prism for gable ends (base width w along X, height h, depth d). */
export function gableGeo(w: number, h: number, d: number) {
  const s = new THREE.Shape(); s.moveTo(-w / 2, 0); s.lineTo(w / 2, 0); s.lineTo(0, h); s.lineTo(-w / 2, 0);
  const g = new THREE.ExtrudeGeometry(s, { depth: d, bevelEnabled: false });
  g.translate(0, 0, -d / 2);
  const uv = g.attributes.uv as THREE.BufferAttribute; const pos = g.attributes.position as THREE.BufferAttribute;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, pos.getX(i), pos.getY(i));
  return g;
}
