// Painterly backdrop: layered low-poly mountain silhouettes (atmospheric perspective) and puffy low-poly cloud clusters.
import * as THREE from 'three';
import { Simplex, RNG, lerp, smoothstep } from '../core/util';

export class DistantMountains {
  group = new THREE.Group();
  private mats: THREE.ShaderMaterial[] = [];
  constructor(scene: THREE.Scene, seed: number) {
    const noise = new Simplex(seed + 3);
    const layers = [{ r: 900, h: 120, seg: 120, d: 0.35 }, { r: 1350, h: 210, seg: 140, d: 0.62 }, { r: 1900, h: 330, seg: 160, d: 0.85 }];
    layers.forEach((L, li) => {
      const pos: number[] = [], hgt: number[] = [];
      const ring: [number, number, number][] = [];
      for (let i = 0; i <= L.seg; i++) {
        const a = i / L.seg * Math.PI * 2;
        let h = (noise.fbm(Math.cos(a) * 2.5 + li * 7, Math.sin(a) * 2.5 + li * 3, 4) * 0.5 + 0.5);
        h = Math.pow(h, 1.6) * L.h + L.h * 0.15;
        // jagged peaks
        h += Math.abs(noise.noise(a * 9 + li, li)) * L.h * 0.25;
        ring.push([Math.cos(a) * L.r, h, Math.sin(a) * L.r]);
      }
      for (let i = 0; i < L.seg; i++) {
        const [x0, h0, z0] = ring[i], [x1, h1, z1] = ring[i + 1];
        const mx = (x0 + x1) / 2, mz = (z0 + z1) / 2, mh = Math.max(h0, h1) * (0.92 + ((i * 7919) % 13) / 100);
        // two-triangle facets with a ridge midpoint so silhouettes read as faceted rock
        pos.push(x0, -60, z0, x1, -60, z1, mx, mh, mz); hgt.push(0, 0, mh / L.h);
        pos.push(x0, -60, z0, mx, mh, mz, x0, h0, z0); hgt.push(0, mh / L.h, h0 / L.h);
        pos.push(x1, -60, z1, x1, h1, z1, mx, mh, mz); hgt.push(0, h1 / L.h, mh / L.h);
      }
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
      g.setAttribute('aH', new THREE.Float32BufferAttribute(hgt, 1));
      const mat = new THREE.ShaderMaterial({
        fog: false, side: THREE.DoubleSide,
        uniforms: { uBase: { value: new THREE.Color() }, uHaze: { value: new THREE.Color() }, uSnow: { value: new THREE.Color() }, uDepth: { value: L.d }, uSun: { value: new THREE.Vector3() }, uSnowLine: { value: li === 2 ? 0.62 : li === 1 ? 0.82 : 2.0 } },
        vertexShader: `attribute float aH; varying float vH; varying vec3 vW; void main(){ vH = aH; vec4 w = modelMatrix * vec4(position,1.0); vW = w.xyz; gl_Position = projectionMatrix * viewMatrix * w; }`,
        fragmentShader: `uniform vec3 uBase, uHaze, uSnow, uSun; uniform float uDepth, uSnowLine; varying float vH; varying vec3 vW;
          void main(){
            vec3 n = normalize(cross(dFdx(vW), dFdy(vW)));
            float lit = clamp(dot(n, normalize(uSun)) * 0.5 + 0.5, 0.0, 1.0);
            vec3 col = uBase * mix(0.78, 1.12, lit);
            col = mix(col, uSnow * mix(0.85, 1.1, lit), smoothstep(uSnowLine, uSnowLine + 0.05, vH));
            // atmospheric perspective: farther layers and lower slopes melt into the haze
            float haze = clamp(uDepth + (1.0 - smoothstep(0.0, 0.7, vH)) * 0.35, 0.0, 1.0);
            gl_FragColor = vec4(mix(col, uHaze, haze), 1.0);
          }`,
      });
      this.mats.push(mat);
      const m = new THREE.Mesh(g, mat); m.frustumCulled = false; m.renderOrder = -2;
      this.group.add(m);
    });
    scene.add(this.group);
  }
  update(cam: THREE.Vector3, fog: THREE.Color, sunDir: THREE.Vector3, night: number, snow: number) {
    this.group.position.set(cam.x, 0, cam.z);
    const base = new THREE.Color(0x46525c).lerp(new THREE.Color(0x0c1018), night);
    const snowC = new THREE.Color(0xe6ebf0).lerp(new THREE.Color(0x2a3240), night);
    for (const m of this.mats) {
      m.uniforms.uBase.value.copy(base).lerp(snowC, snow * 0.5);
      m.uniforms.uHaze.value.copy(fog);
      m.uniforms.uSnow.value.copy(snowC);
      m.uniforms.uSun.value.copy(sunDir);
    }
  }
}

export class CloudClusters {
  group = new THREE.Group();
  private clouds: { m: THREE.Mesh; base: THREE.Vector3; speed: number; k: number }[] = [];
  private mat: THREE.MeshStandardMaterial;
  constructor(scene: THREE.Scene, seed: number) {
    const rng = new RNG(seed + 77);
    this.mat = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 1, metalness: 0, flatShading: true, fog: false, emissive: 0x9aa4b0, emissiveIntensity: 0.35 });
    const protos: THREE.BufferGeometry[] = [];
    for (let p = 0; p < 5; p++) {
      const parts: THREE.BufferGeometry[] = [];
      const n = 6 + rng.int(0, 5);
      for (let i = 0; i < n; i++) {
        const r = rng.range(14, 30) * (i === 0 ? 1.3 : 1);
        const g = new THREE.IcosahedronGeometry(r, 1);
        const ps = g.attributes.position as THREE.BufferAttribute;
        for (let k = 0; k < ps.count; k++) { const y = ps.getY(k); ps.setY(k, y < 0 ? y * 0.35 : y); }
        g.translate(rng.range(-50, 50), rng.range(0, 12), rng.range(-22, 22));
        parts.push(g);
      }
      const merged = mergeAll(parts); merged.computeVertexNormals(); protos.push(merged);
    }
    for (let i = 0; i < 46; i++) {
      const m = new THREE.Mesh(protos[i % protos.length], this.mat);
      const a = rng.range(0, Math.PI * 2), r = rng.range(250, 1500);
      const base = new THREE.Vector3(Math.cos(a) * r, rng.range(230, 380), Math.sin(a) * r);
      m.scale.set(rng.range(0.8, 1.6), rng.range(0.6, 1.0), rng.range(0.8, 1.4)); m.rotation.y = rng.range(0, 6.28);
      m.castShadow = false; m.receiveShadow = false; m.frustumCulled = false;
      this.group.add(m); this.clouds.push({ m, base, speed: rng.range(2, 5), k: i / 46 });
    }
    scene.add(this.group);
  }
  update(cam: THREE.Vector3, time: number, cover: number, sunCol: THREE.Color, daylight: number, night: number, wind: THREE.Vector2) {
    for (const c of this.clouds) {
      const visible = c.k < cover * 1.1 && cover < 0.92;
      c.m.visible = visible;
      if (!visible) continue;
      const span = 3200;
      let x = c.base.x + wind.x * time * c.speed, z = c.base.z + wind.y * time * c.speed;
      x = ((x + span / 2) % span + span) % span - span / 2; z = ((z + span / 2) % span + span) % span - span / 2;
      c.m.position.set(cam.x + x, c.base.y, cam.z + z);
    }
    this.mat.color.setRGB(1, 1, 1).lerp(new THREE.Color(0x1a2030), night);
    this.mat.emissive.copy(sunCol).multiplyScalar(0.25 * daylight).add(new THREE.Color(0x6a7686).multiplyScalar(lerp(0.05, 0.35, daylight)));
    void smoothstep;
  }
}

function mergeAll(list: THREE.BufferGeometry[]) {
  let n = 0; for (const g of list) n += g.attributes.position.count;
  const pos = new Float32Array(n * 3); let o = 0;
  for (const g of list) { pos.set(g.attributes.position.array as Float32Array, o); o += g.attributes.position.array.length; }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); return g;
}
