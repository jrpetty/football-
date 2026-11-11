import * as THREE from 'three';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import {
  initTextures, getTexSet, getPineNeedleTexture, getLeafTexture, getBloodDecalTexture,
  getWaterNormalTexture, getCloudNoiseTexture, TEX_NAMES, type TexQuality, type TexName,
} from '../src/render/textures';
import { getMaterial, getCarPaint, MATERIAL_NAMES, type MaterialName } from '../src/render/materials';

const q = new URLSearchParams(location.search);
const mode = q.get('mode') ?? 'spheres';
const quality = (q.get('q') ?? 'medium') as TexQuality;
const onlyRaw = q.get('m')?.split(',').filter(Boolean);
const only = onlyRaw && onlyRaw.length ? onlyRaw : undefined;
const info = document.getElementById('info')!;
const W = Number(q.get('w') ?? 1280), H = Number(q.get('h') ?? 800);

function texToCanvas(t: THREE.Texture, tiles: number, size: number, alphaBg = false): HTMLCanvasElement {
  const img = t.image as { data: Uint8Array; width: number; height: number };
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d')!;
  const src = document.createElement('canvas');
  src.width = img.width; src.height = img.height;
  const id = new ImageData(new Uint8ClampedArray(img.data), img.width, img.height);
  src.getContext('2d')!.putImageData(id, 0, 0);
  if (alphaBg) { g.fillStyle = '#7a8a96'; g.fillRect(0, 0, size, size); }
  const ts = size / tiles;
  for (let y = 0; y < tiles; y++) for (let x = 0; x < tiles; x++) g.drawImage(src, x * ts, y * ts, ts, ts);
  return c;
}

async function main(): Promise<void> {
  const t0 = performance.now();
  await initTextures(quality, (p, l) => { info.textContent = `${(p * 100) | 0}% ${l}`; });
  const dt = performance.now() - t0;
  info.textContent = `init ${quality}: ${dt.toFixed(0)} ms`;
  (window as unknown as { __initMs: number }).__initMs = dt;

  if (mode === 'flat') {
    const flat = document.getElementById('flat')!;
    flat.style.paddingTop = '24px';
    const names = (only ?? TEX_NAMES) as string[];
    const size = Number(q.get('s') ?? 256);
    const tiles = Number(q.get('t') ?? 2);
    const channels = (q.get('c') ?? 'a').split('');
    for (const n of names) {
      const d = document.createElement('div');
      d.className = 'flat';
      let set: { map: THREE.Texture; normalMap?: THREE.Texture; roughnessMap?: THREE.Texture };
      if (n === 'pine') set = { map: getPineNeedleTexture() };
      else if (n === 'leaf') set = { map: getLeafTexture() };
      else if (n === 'blood') set = { map: getBloodDecalTexture() };
      else if (n === 'water') set = { map: getWaterNormalTexture() };
      else if (n === 'cloud') set = { map: getCloudNoiseTexture() };
      else set = getTexSet(n as TexName);
      for (const ch of channels) {
        const t = ch === 'n' ? set.normalMap : ch === 'r' ? set.roughnessMap : set.map;
        if (t) d.appendChild(texToCanvas(t, ['pine', 'leaf', 'blood'].includes(n) ? 1 : tiles, size, true));
      }
      const l = document.createElement('div'); l.textContent = n; d.appendChild(l);
      flat.appendChild(d);
    }
    document.title = 'ready';
    return;
  }

  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(1);
  renderer.setSize(W, H);
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  document.body.appendChild(renderer.domElement);
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x8a9298);
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.35;
  scene.add(new THREE.HemisphereLight(0xb8c4cc, 0x4a4438, 1.2));
  const sun = new THREE.DirectionalLight(0xfff1e0, 2.2);
  sun.position.set(-4, 6, 5);
  scene.add(sun);

  const labels = document.getElementById('labels')!;
  const items: { name: string; mat: THREE.Material }[] = [];
  const names = (only ?? [...MATERIAL_NAMES, 'car1', 'car2']) as string[];
  for (const n of names) {
    let mat: THREE.Material;
    if (n === 'car1') mat = getCarPaint(0x7a1e1a);
    else if (n === 'car2') mat = getCarPaint(0x2c4a63);
    else mat = getMaterial(n as MaterialName);
    items.push({ name: n, mat });
  }

  let camera: THREE.PerspectiveCamera;
  if (mode === 'ground') {
    // long strips seen from eye height; tile = 2 m (ground) to judge repetition at distance
    camera = new THREE.PerspectiveCamera(60, W / H, 0.05, 300);
    const n = items.length, sw = 2.5;
    items.forEach((it, i) => {
      const len = 80;
      const geo = new THREE.PlaneGeometry(sw, len);
      const uv = geo.attributes.uv as THREE.BufferAttribute;
      const tile = Number(q.get('tile') ?? 2);
      for (let k = 0; k < uv.count; k++) uv.setXY(k, uv.getX(k) * sw / tile, uv.getY(k) * len / tile);
      const m = new THREE.Mesh(geo, it.mat);
      m.rotation.x = -Math.PI / 2;
      m.position.set((i - (n - 1) / 2) * sw, 0, -len / 2 + 2);
      scene.add(m);
    });
    camera.position.set(0, 1.7, 2.5);
    camera.lookAt(0, 0.2, -8);
    scene.fog = new THREE.Fog(0x8a9298, 20, 90);
    sun.position.set(-3, 5, -4);
  } else if (mode === 'plane') {
    // close-up flat panels tilted toward the camera, 1 tile = 1 m, 2x2 m each
    const cols = Math.min(items.length, Number(q.get('cols') ?? 3));
    const rows = Math.ceil(items.length / cols);
    camera = new THREE.PerspectiveCamera(40, W / H, 0.05, 100);
    const sp = 2.15;
    items.forEach((it, i) => {
      const geo = new THREE.PlaneGeometry(2, 2);
      geo.setAttribute('uv', new THREE.BufferAttribute(new Float32Array([0, 2, 2, 2, 0, 0, 2, 0]), 2));
      const m = new THREE.Mesh(geo, it.mat);
      const cx = (i % cols - (cols - 1) / 2) * sp, cy = -(Math.floor(i / cols) - (rows - 1) / 2) * sp;
      m.position.set(cx, cy, 0);
      scene.add(m);
      it.name && Object.assign(m.userData, { label: it.name });
    });
    const dist = Math.max(cols * sp / (2 * Math.tan(20 * Math.PI / 180) * (W / H)), rows * sp / (2 * Math.tan(20 * Math.PI / 180))) * 1.02;
    camera.position.set(0, 0, dist);
    sun.position.set(-3, 4, 2.5); // fairly grazing to show relief
  } else {
    const cols = Number(q.get('cols') ?? 9);
    const rows = Math.ceil(items.length / cols);
    camera = new THREE.PerspectiveCamera(30, W / H, 0.1, 200);
    const sp = 1.25;
    const sphere = new THREE.SphereGeometry(0.5, 48, 32);
    // scale sphere UVs so 1 tile ~ 1 m of surface
    const uv = sphere.attributes.uv as THREE.BufferAttribute;
    for (let i = 0; i < uv.count; i++) uv.setXY(i, uv.getX(i) * 3, uv.getY(i) * 1.5);
    items.forEach((it, i) => {
      const m = new THREE.Mesh(sphere, it.mat);
      m.position.set((i % cols - (cols - 1) / 2) * sp, -(Math.floor(i / cols) - (rows - 1) / 2) * sp * 1.1, 0);
      m.rotation.y = -0.4;
      scene.add(m);
    });
    camera.position.set(0, 0, Math.max(rows * sp * 1.1 / (2 * Math.tan(15 * Math.PI / 180)), cols * sp / (2 * Math.tan(15 * Math.PI / 180) * W / H)) * 1.05);
  }
  scene.updateMatrixWorld();
  renderer.render(scene, camera);
  // labels
  scene.children.filter((c) => c instanceof THREE.Mesh).forEach((m, i) => {
    const p = m.position.clone();
    if (mode === 'ground') { p.set(m.position.x, 0, 0.5); }
    else p.y -= mode === 'plane' ? 1 : 0.6;
    p.project(camera);
    const d = document.createElement('div');
    d.style.left = `${(p.x * 0.5 + 0.5) * W}px`;
    d.style.top = `${(-p.y * 0.5 + 0.5) * H - (mode === 'plane' ? 16 : 0)}px`;
    d.textContent = items[i].name;
    labels.appendChild(d);
  });
  document.title = 'ready';
}
main().catch((e) => { info.textContent = String(e?.stack ?? e); document.title = 'error'; console.error(e); });
