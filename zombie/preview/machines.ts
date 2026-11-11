// Preview gallery for src/models/machines.ts + buildpieces.ts. Query params:
//   set=machines|pieces   ids=a,b   view=front|side|iso|top|back|low   cols=n   cell=m   zoom=f
//   t=seconds (pre-advance animation)   run=0|1   labels=0   q=low|medium
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import { countTris } from '../src/models/machinegeom';
import type { MachineModelId } from '../src/models/machines';
import type { BuildPieceId } from '../src/models/buildpieces';

const q = new URLSearchParams(location.search);
const set = q.get('set') ?? 'machines';
const idsParam = q.get('ids');
const info: string[] = [];

const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1);
renderer.setSize(innerWidth, innerHeight);
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0xa9b8bf);
scene.environment = new THREE.PMREMGenerator(renderer).fromScene(new RoomEnvironment(), 0.04).texture;
scene.environmentIntensity = Number(q.get('env') ?? 0.5);
const camera = new THREE.PerspectiveCamera(40, innerWidth / innerHeight, 0.01, 3000);
scene.add(new THREE.HemisphereLight(0xcfdde6, 0x4a4436, 1.1));
const sun = new THREE.DirectionalLight(0xfff1dc, 2.6);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
sun.shadow.bias = -0.0004;
sun.shadow.normalBias = 0.02;
scene.add(sun, sun.target);

function label(text: string, size: number): THREE.Sprite {
  const cv = document.createElement('canvas'); cv.width = 512; cv.height = 64;
  const g = cv.getContext('2d')!;
  g.font = 'bold 34px monospace'; g.fillStyle = '#fff'; g.strokeStyle = '#000'; g.lineWidth = 5; g.textAlign = 'center';
  g.strokeText(text, 256, 46); g.fillText(text, 256, 46);
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(cv), depthTest: false }));
  s.scale.set(size * 8, size, 1);
  return s;
}

const t = await import('../src/render/textures');
await t.initTextures((q.get('q') ?? 'low') as 'low');

const objects: { obj: THREE.Object3D; name: string; anim?: (dt: number, r: boolean, t: number) => void }[] = [];
if (set === 'machines') {
  const { buildMachineModel, MACHINE_MODEL_IDS } = await import('../src/models/machines');
  for (const id of (idsParam ? idsParam.split(',') as MachineModelId[] : MACHINE_MODEL_IDS)) {
    const t0 = performance.now();
    const m = buildMachineModel(id);
    const ms = performance.now() - t0;
    let draws = 0;
    m.traverse((o) => { if ((o as THREE.Mesh).isMesh) draws++; });
    const bb = new THREE.Box3().setFromObject(m);
    const fp = m.userData.footprint as [number, number];
    const over = bb.min.x < -fp[0] - 0.01 || bb.max.x > fp[0] + 0.01 || bb.min.z < -fp[1] - 0.01 || bb.max.z > fp[1] + 0.01 || bb.min.y < -0.01;
    info.push(`${id}: ${countTris(m)} tris, ${draws} meshes, ${(m.userData.statusLights as unknown[]).length} lights, ${ms.toFixed(0)}ms` +
      (over ? `  OUTSIDE fp: ${bb.min.toArray().map((v) => v.toFixed(2))} .. ${bb.max.toArray().map((v) => v.toFixed(2))}` : ''));
    objects.push({ obj: m, name: `${id} ${countTris(m)}t`, anim: m.userData.animate });
  }
} else {
  const { buildPiece, BUILD_PIECE_IDS } = await import('../src/models/buildpieces');
  for (const id of (idsParam ? idsParam.split(',') as BuildPieceId[] : BUILD_PIECE_IDS)) {
    const m = buildPiece(id);
    if (id.startsWith('crop_')) m.add(buildPiece('farm_plot'));
    const bb = new THREE.Box3().setFromObject(m);
    info.push(`${id}: ${countTris(m)} tris  bb ${bb.min.toArray().map((v) => v.toFixed(2))} .. ${bb.max.toArray().map((v) => v.toFixed(2))}`);
    objects.push({ obj: m, name: `${id} ${countTris(m)}t` });
  }
}

const cols = Number(q.get('cols') ?? Math.ceil(Math.sqrt(objects.length)));
const sizes = objects.map((o) => new THREE.Box3().setFromObject(o.obj).getSize(new THREE.Vector3()));
const cell = Number(q.get('cell') ?? Math.max(...sizes.map((s) => Math.max(s.x, s.z))) * 1.3);
const all = new THREE.Box3();
objects.forEach((o, i) => {
  const c = i % cols, r = Math.floor(i / cols);
  o.obj.position.set(c * cell, 0, r * cell);
  scene.add(o.obj);
  const bb = new THREE.Box3().setFromObject(o.obj);
  all.union(bb);
  if (q.get('labels') !== '0') {
    const l = label(o.name, Math.max(0.5, cell * 0.06));
    l.position.set(o.obj.position.x, bb.max.y + cell * 0.08, o.obj.position.z);
    scene.add(l);
  }
});
const centre = all.getCenter(new THREE.Vector3());
const size = all.getSize(new THREE.Vector3());
{
  const gm = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshStandardMaterial({ color: 0x5d5a45, roughness: 1 }));
  gm.rotation.x = -Math.PI / 2; gm.scale.set(size.x + cell * 4, size.z + cell * 4, 1);
  gm.position.set(centre.x, -0.002, centre.z); gm.receiveShadow = true; scene.add(gm);
  if (q.get('grid') !== '0') {
    const gh = new THREE.GridHelper(Math.ceil((Math.max(size.x, size.z) + cell * 2) / 2) * 2, Math.ceil((Math.max(size.x, size.z) + cell * 2) / 2), 0x333333, 0x444444);
    gh.position.set(Math.round(centre.x / 2) * 2 + 1, 0.003, Math.round(centre.z / 2) * 2 + 1);
    (gh.material as THREE.Material).opacity = 0.25; (gh.material as THREE.Material).transparent = true;
    scene.add(gh);
  }
}
const radius = size.length() * 0.5;
const zoom = Number(q.get('zoom') ?? 1);
const dist = (radius / Math.sin(THREE.MathUtils.degToRad(20))) * 0.85 / zoom;
const dirs: Record<string, THREE.Vector3> = {
  front: new THREE.Vector3(0, 0.25, 1), side: new THREE.Vector3(1, 0.15, 0), iso: new THREE.Vector3(0.7, 0.55, 1),
  top: new THREE.Vector3(0, 1, 0.01), back: new THREE.Vector3(-0.6, 0.4, -1), low: new THREE.Vector3(0.5, 0.12, 1),
  left: new THREE.Vector3(-1, 0.3, 0.4),
};
const dir = (dirs[q.get('view') ?? 'iso'] ?? dirs.iso).clone().normalize();
const ty = Number(q.get('ty') ?? 0);
centre.y += ty;
camera.position.copy(centre).addScaledVector(dir, dist);
camera.near = dist / 200; camera.far = dist * 10; camera.updateProjectionMatrix();
camera.lookAt(centre);
const controls = new OrbitControls(camera, renderer.domElement);
controls.target.copy(centre);
sun.position.copy(centre).add(new THREE.Vector3(radius * 0.8, radius * 1.6, radius * 0.9));
sun.target.position.copy(centre);
const sc = sun.shadow.camera as THREE.OrthographicCamera;
sc.left = sc.bottom = -radius * 1.2; sc.right = sc.top = radius * 1.2; sc.near = 0.1; sc.far = radius * 5; sc.updateProjectionMatrix();

if (q.get('info') !== '0') (document.getElementById('info')!).textContent = info.join('\n');
console.log(info.join('\n'));
const running = q.get('run') !== '0';
let time = Number(q.get('t') ?? 0);
for (let i = 0; i < Math.round(time * 30); i++) for (const o of objects) o.anim?.(1 / 30, running, i / 30);
let frames = 0;
let last = performance.now();
function loop(): void {
  const now = performance.now();
  const dt = Math.min(0.1, (now - last) / 1000);
  last = now;
  time += dt;
  for (const o of objects) o.anim?.(dt, running, time);
  controls.update();
  renderer.render(scene, camera);
  if (++frames === 3) (window as unknown as { __ready: boolean }).__ready = true;
  requestAnimationFrame(loop);
}
loop();
