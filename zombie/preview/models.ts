// Preview gallery for src/models. Query params:
//   set=items|props|cars|trees   ids=a,b,c   view=front|side|iso|top   cell=metres   cols=n   zoom=f   info=0
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import type { HeldModelId } from '../src/models/items';
import type { PropId, CarVariant } from '../src/models/props';
import type { TreeModel } from '../src/models/vegetation';
import { triCount } from '../src/models/geom';

const q = new URLSearchParams(location.search);
const set = q.get('set') ?? 'items';
const idsParam = q.get('ids');
const info: string[] = [];

const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1);
renderer.setSize(innerWidth, innerHeight);
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.0;
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0xa9b8bf);
scene.environment = new THREE.PMREMGenerator(renderer).fromScene(new RoomEnvironment(), 0.04).texture;
scene.environmentIntensity = Number(q.get('env') ?? 1.0);
const camera = new THREE.PerspectiveCamera(40, innerWidth / innerHeight, 0.01, 2000);
scene.add(new THREE.HemisphereLight(0xcfdde6, 0x4a4436, 1.2));
const sun = new THREE.DirectionalLight(0xfff1dc, 2.6);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
sun.shadow.bias = -0.0004;
sun.shadow.normalBias = 0.02;
scene.add(sun, sun.target);

function label(text: string, size: number): THREE.Sprite {
  const cv = document.createElement('canvas'); cv.width = 512; cv.height = 64;
  const g = cv.getContext('2d')!;
  g.font = 'bold 36px monospace'; g.fillStyle = '#fff'; g.strokeStyle = '#000'; g.lineWidth = 5; g.textAlign = 'center';
  g.strokeText(text, 256, 46); g.fillText(text, 256, 46);
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(cv), depthTest: false }));
  s.scale.set(size * 8, size, 1);
  return s;
}

if (q.get('stub') !== '1') {
  try { const t = await import('../src/render/textures'); await t.initTextures((q.get('q') ?? 'low') as 'low'); } catch (e) { console.warn('initTextures failed', e); }
}
const objects: { obj: THREE.Object3D; name: string }[] = [];
let ground = true;
let view = q.get('view') ?? 'iso';

if (set === 'items') {
  const { buildItemModel } = await import('../src/models/items');
  const all: HeldModelId[] = ['bat', 'bat_nails', 'crowbar', 'axe', 'knife', 'machete', 'hammer', 'pipe', 'sledgehammer', 'pickaxe', 'pistol', 'shotgun', 'rifle', 'flashlight', 'generic'];
  for (const id of (idsParam ? idsParam.split(',') as HeldModelId[] : all)) {
    const m = buildItemModel(id);
    objects.push({ obj: m, name: `${id} ${triCount(m)}t` });
  }
  ground = false; view = q.get('view') ?? 'side';
} else if (set === 'props') {
  const { buildProp, PROP_SIZES } = await import('../src/models/props');
  const all = Object.keys(PROP_SIZES) as PropId[];
  for (const id of (idsParam ? idsParam.split(',') as PropId[] : all)) {
    const m = buildProp(id);
    const bb = new THREE.Box3().setFromObject(m);
    const s = PROP_SIZES[id];
    const sz = bb.getSize(new THREE.Vector3());
    const e = 0.005;
    const over = bb.max.y > s[1] + e || bb.min.y < -e || bb.min.x < -s[0] / 2 - e || bb.max.x > s[0] / 2 + e || bb.min.z < -s[2] / 2 - e || bb.max.z > s[2] / 2 + e;
    void sz;
    if (over) info.push(`OVERSIZE ${id}: bb ${bb.min.toArray().map((v) => v.toFixed(2))} .. ${bb.max.toArray().map((v) => v.toFixed(2))} vs ${s}`);
    objects.push({ obj: m, name: `${id} ${triCount(m)}t` });
  }
} else if (set === 'cars') {
  const { buildCar } = await import('../src/models/props');
  const all: CarVariant[] = ['sedan', 'hatchback', 'pickup', 'van', 'police', 'wreck'];
  const paints = [0x6a2a22, 0x3a4a5a, 0x5a5a4a, 0xb8b4a8, 0x151515, 0x555555];
  (idsParam ? idsParam.split(',') as CarVariant[] : all).forEach((v, i) => {
    const c = buildCar(v, paints[i % paints.length]);
    objects.push({ obj: c.object, name: `${v} ${triCount(c.object)}t ${c.size.map((n) => n.toFixed(2)).join('x')}` });
  });
} else if (set === 'trees') {
  const { buildTreeModels } = await import('../src/models/vegetation');
  const t = buildTreeModels();
  const groups = (q.get('kinds') ?? 'pines,firs,birches,dead,bushes,rocks,logs').split(',') as (keyof typeof t)[];
  for (const k of groups) t[k].forEach((tm: TreeModel, i: number) => {
    const g = new THREE.Group();
    for (const p of tm.parts) { const m = new THREE.Mesh(p.geometry, p.material); m.castShadow = m.receiveShadow = true; g.add(m); }
    const tris = tm.parts.reduce((a, p) => a + triCount(p.geometry), 0);
    objects.push({ obj: g, name: `${k}${i} ${tris}t h${tm.height.toFixed(1)}` });
  });
}

// layout
if (q.get('list') === '1') for (const o of objects) info.push(o.name);
const cols = Number(q.get('cols') ?? Math.ceil(Math.sqrt(objects.length)));
const sizes = objects.map((o) => new THREE.Box3().setFromObject(o.obj).getSize(new THREE.Vector3()));
const cell = Number(q.get('cell') ?? Math.max(...sizes.map((s) => Math.max(s.x, s.z, set === 'items' ? s.y : 0))) * 1.25);
const all = new THREE.Box3();
objects.forEach((o, i) => {
  const c = i % cols, r = Math.floor(i / cols);
  if (set === 'items') {
    o.obj.position.set(0, -r * cell, c * cell);
  } else {
    o.obj.position.set(c * cell, 0, r * cell);
  }
  scene.add(o.obj);
  o.obj.traverse((m) => { if ((m as THREE.Mesh).isMesh) { m.castShadow = true; m.receiveShadow = true; } });
  const bb = new THREE.Box3().setFromObject(o.obj);
  all.union(bb);
  if (q.get('labels') !== '0') {
    const l = label(o.name, cell * 0.06);
    if (set === 'items') l.position.set(0.2, bb.min.y - cell * 0.05, o.obj.position.z);
    else l.position.set(o.obj.position.x, bb.max.y + cell * 0.06, o.obj.position.z);
    scene.add(l);
  }
});
const centre = all.getCenter(new THREE.Vector3());
const size = all.getSize(new THREE.Vector3());
if (ground) {
  const gm = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshStandardMaterial({ color: 0x55573f, roughness: 1 }));
  gm.rotation.x = -Math.PI / 2; gm.scale.set(size.x + cell * 4, size.z + cell * 4, 1);
  gm.position.set(centre.x, 0, centre.z); gm.receiveShadow = true; scene.add(gm);
}
const radius = size.length() * 0.5;
const zoom = Number(q.get('zoom') ?? 1);
const dist = (radius / Math.sin(THREE.MathUtils.degToRad(20))) * 0.85 / zoom;
const dirs: Record<string, THREE.Vector3> = {
  front: new THREE.Vector3(0, 0.25, 1), side: new THREE.Vector3(1, 0.1, 0), iso: new THREE.Vector3(0.7, 0.55, 1),
  top: new THREE.Vector3(0, 1, 0.01), q: new THREE.Vector3(1, 0.35, -0.7), q2: new THREE.Vector3(1, 0.4, 0.5), back: new THREE.Vector3(-0.6, 0.4, -1), low: new THREE.Vector3(0.5, 0.12, 1),
};
const dir = (dirs[view] ?? dirs.iso).clone().normalize();
camera.position.copy(centre).addScaledVector(dir, dist);
camera.near = dist / 200; camera.far = dist * 10; camera.updateProjectionMatrix();
camera.lookAt(centre);
const controls = new OrbitControls(camera, renderer.domElement);
controls.target.copy(centre);
sun.position.copy(centre).add(new THREE.Vector3(radius * 0.8, radius * 1.6, radius * 0.9));
sun.target.position.copy(centre);
const sc = sun.shadow.camera as THREE.OrthographicCamera;
sc.left = sc.bottom = -radius * 1.2; sc.right = sc.top = radius * 1.2; sc.near = 0.1; sc.far = radius * 5; sc.updateProjectionMatrix();
scene.fog = new THREE.Fog(0xa9b8bf, dist * 2, dist * 6);

if (q.get('info') !== '0') (document.getElementById('info')!).textContent = info.join('\n');
console.log(info.join('\n'));
let frames = 0;
function loop() {
  controls.update();
  renderer.render(scene, camera);
  if (++frames === 3) (window as unknown as { __ready: boolean }).__ready = true;
  requestAnimationFrame(loop);
}
loop();
