// Runs inside the exporter page. Builds the world and every model with the game's own generators and hands
// the results to node (window.__save) as files for the Godot project: .glb meshes (materials by name only),
// textures as .png, a material manifest, the world's layout / collision / nav / loot, and the data tables.
import * as THREE from 'three';
import { GLTFExporter } from 'three/examples/jsm/exporters/GLTFExporter.js';
import { mergeVertices } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { initTextures, getTexSet, getCloudNoiseTexture, getWaterNormalTexture } from '/src/render/textures';
import { QUALITY } from '/src/render/renderer';
import { World } from '/src/world/world';
import { LAKE, TOWN as TOWN_RECT, SPAWN, CABIN } from '/src/world/layout';
import { T_RES, T_N } from '/src/world/terrain';
import { NAV_N } from '/src/world/collision';
import { RNG, WORLD_SIZE } from '/src/core/util';
import { ITEMS } from '/src/items/items';
import { RECIPES } from '/src/items/recipes';
import { CROPS } from '/src/items/content';
import { rollLoot, CONTAINER_LABEL, containerCapacity } from '/src/items/loot';
import { SKILLS, TRAITS, OCCUPATIONS } from '/src/player/skills';
import { MACHINES, PIECES, MILESTONES, MRECIPES } from '/src/factory/defs';
import { buildItemModel } from '/src/models/items';
import { buildProp, PROP_SIZES, buildCar } from '/src/models/props';
import { buildMachineModel, MACHINE_MODEL_IDS, machineFootprint } from '/src/models/machines';
import { buildTownModel, TOWN_MODEL_IDS, TOWN_FOOT } from '/src/models/town';
import { buildPiece, BUILD_PIECE_IDS } from '/src/models/buildpieces';
import { buildFluidModel } from '/src/models/fluids';
import { buildLocomotive, buildWagon, buildStation } from '/src/models/trains';
import { buildGeneratorModel } from '/src/models/lowpoly';
import { SFX, INTERNAL } from '/src/audio/sfx';
import { LOOPS } from '/src/audio/loops';
import { finalize, makeLoopable } from '/src/audio/dsp';

const SEED = 20261111;      // the game's world seed (src/game.ts)
const W = window as any;
const log = (...a: any[]) => console.log('[export]', ...a);
const what = new Set((new URLSearchParams(location.search).get('what') || 'all').split(','));
const want = (k: string) => what.has('all') || what.has(k);

// ------------------------------------------------------------------ saving
function b64(u8: Uint8Array) { let s = ''; const CH = 0x8000; for (let i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH) as any); return btoa(s); }
async function save(rel: string, data: ArrayBuffer | Uint8Array | string) {
  const u8 = typeof data === 'string' ? new TextEncoder().encode(data) : data instanceof Uint8Array ? data : new Uint8Array(data);
  const CH = 6 * 1024 * 1024;
  for (let i = 0, first = true; i < u8.length || first; i += CH, first = false) await W.__save(rel, b64(u8.subarray(i, Math.min(u8.length, i + CH))), !first);
}
const saveJSON = (rel: string, o: any) => save(rel, JSON.stringify(o));
const r3 = (v: number) => Math.round(v * 1000) / 1000;

// ------------------------------------------------------------------ textures and materials
const texIds = new Map<string, string>();
const texJobs: Promise<void>[] = [];
function texRef(t: THREE.Texture | null | undefined, normal = false, lossless = false): string | null {
  if (!t || !t.image) return null;
  const key = t.source.uuid + (t.flipY ? 'f' : 'n') + (normal ? 'N' : '');
  let id = texIds.get(key);
  if (id) return id;
  id = 't' + texIds.size + (t.name ? '_' + t.name.replace(/[^a-zA-Z0-9]+/g, '_').slice(0, 24) : '');
  texIds.set(key, id);
  const img: any = t.image, w = img.width, h = img.height;
  const cv = document.createElement('canvas'); cv.width = w; cv.height = h; const cx = cv.getContext('2d')!;
  if (img.data) {
    const d = img.data as ArrayLike<number>; const id2 = cx.createImageData(w, h); const n = w * h; const ch = d.length / n;
    for (let i = 0; i < n; i++) { const o = i * 4; if (ch === 4) { id2.data[o] = d[o]; id2.data[o + 1] = d[o + 1]; id2.data[o + 2] = d[o + 2]; id2.data[o + 3] = d[o + 3]; } else { const v = d[i * ch]; id2.data[o] = v; id2.data[o + 1] = ch > 1 ? d[i * ch + 1] : v; id2.data[o + 2] = ch > 2 ? d[i * ch + 2] : v; id2.data[o + 3] = 255; } }
    cx.putImageData(id2, 0, 0);
  } else {
    // glTF samples images top-down; three's canvas textures are flipped on upload
    if (t.flipY) { cx.translate(0, h); cx.scale(1, -1); }
    cx.drawImage(img, 0, 0, w, h);
  }
  // three reads a normal map's green along +v; glTF and Godot read it along image-up, which is -v
  if (normal) { const d = cx.getImageData(0, 0, w, h); for (let i = 1; i < d.data.length; i += 4) d.data[i] = 255 - d.data[i]; cx.putImageData(d, 0, 0); }
  // data textures stay lossless; painted surfaces go out as WebP, which Godot imports directly
  const file = id + (lossless ? '.png' : '.webp');
  texIds.set(key, file);
  texJobs.push(new Promise<void>(res => cv.toBlob(async b => { await save(`assets/tex/${file}`, await b!.arrayBuffer()); res(); }, lossless ? 'image/png' : 'image/webp', 0.9)));
  return file;
}
const matIds = new Map<THREE.Material, string>();
const matList: Record<string, any> = {};
const stubs = new Map<string, THREE.MeshStandardMaterial>();
function matRef(m: THREE.Material): THREE.MeshStandardMaterial {
  let id = matIds.get(m);
  if (!id) {
    const s = m as THREE.MeshPhysicalMaterial;
    id = 'm' + matIds.size + (m.name ? '_' + m.name.replace(/[^a-zA-Z0-9]+/g, '_').slice(0, 28) : '');
    matIds.set(m, id);
    let custom = ''; try { custom = m.customProgramCacheKey ? String(m.customProgramCacheKey()) : ''; } catch { /* */ }
    if (custom.includes('onBeforeCompile') || custom.includes('function') || custom.includes('=>') || custom.length > 60) custom = '';
    const map = s.map ?? null;
    matList[id] = {
      name: m.name || '', type: m.type, color: s.color ? '#' + s.color.getHexString() : '#ffffff',
      roughness: s.roughness ?? 1, metalness: s.metalness ?? 0,
      emissive: s.emissive ? '#' + s.emissive.getHexString() : '#000000', emissiveIntensity: s.emissiveIntensity ?? 0,
      opacity: m.opacity, transparent: m.transparent, alphaTest: m.alphaTest, side: m.side, vertexColors: !!m.vertexColors, flat: !!(s as any).flatShading,
      depthWrite: m.depthWrite, clearcoat: s.clearcoat ?? 0,
      map: texRef(map), normalMap: texRef(s.normalMap, true), normalScale: s.normalScale ? s.normalScale.x : 1,
      roughnessMap: texRef(s.roughnessMap), metalnessMap: texRef(s.metalnessMap), emissiveMap: texRef(s.emissiveMap), alphaMap: texRef(s.alphaMap), aoMap: texRef(s.aoMap),
      repeat: map ? [map.repeat.x, map.repeat.y] : [1, 1], custom, userData: Object.fromEntries(Object.entries(m.userData ?? {}).filter(([, v]) => typeof v === 'number' || typeof v === 'string' || typeof v === 'boolean')),
    };
  }
  let st = stubs.get(id);
  if (!st) { st = new THREE.MeshStandardMaterial({ name: id }); stubs.set(id, st); }
  return st;
}

// ------------------------------------------------------------------ geometry -> glb
const cleanGeo = new Map<THREE.BufferGeometry, THREE.BufferGeometry>();
function geoRef(g: THREE.BufferGeometry) {
  let c = cleanGeo.get(g);
  if (!c) {
    c = new THREE.BufferGeometry();
    for (const n of ['position', 'normal', 'uv', 'color']) { const a = g.attributes[n] as THREE.BufferAttribute | undefined; if (a) c.setAttribute(n, (a as any).isInterleavedBufferAttribute ? (a as any).clone() : a); }
    if (!c.attributes.normal) c.computeVertexNormals();
    if (g.index) c.setIndex(g.index);
    for (const gr of g.groups) c.addGroup(gr.start, gr.count, gr.materialIndex);
    // big unindexed meshes (ore rock, mostly) share most of their vertices: weld them
    if (!g.index && !g.groups.length && c.attributes.position.count > 600) { const w = mergeVertices(c, 1e-4); if (w.attributes.position.count < c.attributes.position.count * 0.8) c = w; }
    cleanGeo.set(g, c);
  }
  return c;
}
interface Inst { mesh: string; m: number[][]; c?: number[][] }
interface Ctx { insts: Inst[]; lib: THREE.Group; libN: Map<string, string> }
const newCtx = (): Ctx => ({ insts: [], lib: new THREE.Group(), libN: new Map() });
/** unit mesh placed in the file's hidden library; returns its node name */
function libMesh(ctx: Ctx, geo: THREE.BufferGeometry, mat: THREE.Material | THREE.Material[], local?: THREE.Matrix4) {
  const key = geo.uuid + '|' + (Array.isArray(mat) ? mat.map(m => m.uuid).join(',') : mat.uuid) + '|' + (local ? local.elements.map(e => e.toFixed(4)).join(',') : '');
  let n = ctx.libN.get(key);
  if (!n) {
    n = 'lib' + ctx.libN.size; ctx.libN.set(key, n);
    const m = new THREE.Mesh(geoRef(geo), Array.isArray(mat) ? mat.map(matRef) : matRef(mat)); m.name = n;
    if (local) local.decompose(m.position, m.quaternion, m.scale);
    ctx.lib.add(m);
  }
  return n;
}
function cloneForExport(src: THREE.Object3D, ctx: Ctx, rootInv: THREE.Matrix4): THREE.Object3D | null {
  if (src.userData.noExport) return null;
  const any = src as any;
  if (any.isLight || any.isSprite || any.isPoints || any.isLine || any.isCamera) return null;
  if (any.isInstancedMesh) {
    const im = src as THREE.InstancedMesh; if (!im.count) return null;
    im.updateWorldMatrix(true, false);
    const base = new THREE.Matrix4().multiplyMatrices(rootInv, im.matrixWorld), mm = new THREE.Matrix4(), col = new THREE.Color();
    const inst: Inst = { mesh: libMesh(ctx, im.geometry, im.material), m: [] }; if (im.instanceColor) inst.c = [];
    for (let i = 0; i < im.count; i++) { im.getMatrixAt(i, mm); mm.premultiply(base); inst.m.push(mm.elements.map(r3)); if (im.instanceColor) { im.getColorAt(i, col); inst.c!.push([r3(col.r), r3(col.g), r3(col.b)]); } }
    ctx.insts.push(inst);
    return null;
  }
  let out: THREE.Object3D;
  if (any.isMesh) {
    const m = src as THREE.Mesh;
    if (!m.geometry.attributes.position || !m.geometry.attributes.position.count) return null;
    out = new THREE.Mesh(geoRef(m.geometry), Array.isArray(m.material) ? m.material.map(matRef) : matRef(m.material));
  } else out = new THREE.Object3D();
  out.name = src.name; out.position.copy(src.position); out.quaternion.copy(src.quaternion); out.scale.copy(src.scale);
  if (!src.visible) out.userData.hidden = true;
  for (const c of src.children) { const cc = cloneForExport(c, ctx, rootInv); if (cc) out.add(cc); }
  if (!any.isMesh && !out.children.length && !src.name) return null;
  return out;
}
const exporter = new GLTFExporter();
async function saveGLB(rel: string, root: THREE.Object3D, ctx?: Ctx) {
  const scene = new THREE.Scene(); scene.add(root);
  if (ctx && ctx.lib.children.length) { ctx.lib.name = '__lib'; scene.add(ctx.lib); }
  const buf = await exporter.parseAsync(scene, { binary: true, onlyVisible: false }) as ArrayBuffer;
  await save(rel, buf);
  return buf.byteLength;
}
/** export one object (a model or a whole subtree) as <rel>.glb, plus <rel>.json when it holds instanced meshes */
async function exportObject(rel: string, obj: THREE.Object3D, extra?: any) {
  obj.updateWorldMatrix(true, true);
  const ctx = newCtx();
  const inv = new THREE.Matrix4().copy(obj.matrixWorld).invert();
  const root = new THREE.Object3D(); root.name = 'root';
  for (const c of obj.children) { const cc = cloneForExport(c, ctx, inv); if (cc) root.add(cc); }
  if ((obj as any).isMesh) { const m = obj as THREE.Mesh; const mm = new THREE.Mesh(geoRef(m.geometry), Array.isArray(m.material) ? m.material.map(matRef) : matRef(m.material)); mm.name = 'self'; root.add(mm); }
  const n = await saveGLB(rel + '.glb', root, ctx);
  if (ctx.insts.length || extra) await saveJSON(rel + '.json', { instances: ctx.insts, ...(extra ?? {}) });
  return n;
}

// ------------------------------------------------------------------ run
async function run() {
  const renderer = new THREE.WebGLRenderer({ antialias: false });
  renderer.setSize(64, 64);
  const tq = (new URLSearchParams(location.search).get('tex') || 'medium') as any;
  log('textures', tq); await initTextures(tq);
  const sizes: Record<string, number> = {};

  // ---------- model library
  if (want('models')) {
    const each = async (cat: string, ids: readonly string[], build: (id: string) => THREE.Object3D, meta?: (id: string) => any) => {
      let total = 0; const index: Record<string, any> = {};
      for (const id of ids) { try { total += await exportObject(`assets/models/${cat}/${id}`, build(id)); index[id] = meta ? meta(id) : {}; } catch (e: any) { log('model failed', cat, id, e.message); } }
      await saveJSON(`assets/models/${cat}/index.json`, index); sizes[cat] = total; log(cat, ids.length, (total / 1e6).toFixed(1) + ' MB');
    };
    const held = ['bat', 'bat_nails', 'crowbar', 'axe', 'knife', 'machete', 'hammer', 'pipe', 'sledgehammer', 'pickaxe', 'pistol', 'shotgun', 'rifle', 'flashlight', 'generic'];
    await each('held', held, id => buildItemModel(id as any), id => { const g = buildItemModel(id as any); const a: any = {}; g.traverse(o => { if (o.name && !(o as any).isMesh) a[o.name] = o.position.toArray().map(r3); }); return { anchors: a }; });
    await each('props', Object.keys(PROP_SIZES), id => buildProp(id as any), id => ({ size: (PROP_SIZES as any)[id] }));
    await each('machines', MACHINE_MODEL_IDS, id => buildMachineModel(id as any), id => ({ foot: machineFootprint(id as any) }));
    await each('town', TOWN_MODEL_IDS, id => buildTownModel(id as any), id => ({ foot: (TOWN_FOOT as any)[id] }));
    await each('pieces', BUILD_PIECE_IDS, id => buildPiece(id as any));
    await each('fluids', ['water_pump', 'oil_pump', 'refinery', 'fuel_gen', 'fluid_tank', 'packager', 'sprinkler'], id => buildFluidModel(id as any));
    const cars = ['sedan', 'hatchback', 'pickup', 'van', 'police', 'wreck'];
    const carSize: Record<string, any> = {};
    await each('cars', cars, id => { const c = buildCar(id as any, 0x8a2f2f); carSize[id] = c.size; return c.object; }, id => ({ size: carSize[id] }));
    await each('trains', ['locomotive', 'wagon', 'station', 'generator'], id => id === 'locomotive' ? buildLocomotive() : id === 'wagon' ? buildWagon() : id === 'station' ? buildStation() : buildGeneratorModel());
  }

  // ---------- icons: every emoji the UI uses, drawn once so Godot needs no emoji font
  if (want('data')) {
    const glyphs: Record<string, string> = {};
    for (const id in ITEMS) glyphs['item_' + id] = (ITEMS as any)[id].icon;
    Object.assign(glyphs, { m_hunger: '🍽️', m_thirst: '💧', m_tired: '😴', m_endurance: '😮‍💨', m_bleeding: '🩸', m_pain: '⚡', m_injured: '🤕', m_panic: '😱', m_cold: '🥶', m_hot: '🥵', m_wet: '🌧️', m_sick: '🤢', m_infection: '🦠', m_heavy: '🎒', m_unhappy: '☹️', m_pk: '💊', m_full: '😋',
      ui_heart: '❤️', ui_bolt: '⚡', ui_food: '🍖', ui_water: '💧', ui_skull: '💀', ui_lock: '🔒', ui_door: '🚪', ui_box: '📦', ui_hand: '✋', ui_zombie: '🧟', ui_sun: '☀️', ui_moon: '🌙', ui_fire: '🔥', ui_save: '💾', ui_bandage: '🩹' });
    for (const sk of SKILLS as any[]) glyphs['skill_' + sk.id] = sk.icon;
    const cv = document.createElement('canvas'); cv.width = cv.height = 72; const cx = cv.getContext('2d')!;
    const seen = new Map<string, string>(); const index: Record<string, string> = {};
    for (const k in glyphs) {
      const g = glyphs[k]; let file = seen.get(g);
      if (!file) {
        file = 'i' + seen.size + '.png'; seen.set(g, file);
        cx.clearRect(0, 0, 72, 72); cx.font = '56px "Noto Color Emoji", "Apple Color Emoji", "Segoe UI Emoji", sans-serif'; cx.textAlign = 'center'; cx.textBaseline = 'middle'; cx.fillStyle = '#e8e2d0'; cx.fillText(g, 36, 40);
        const blob: Blob = await new Promise(r => cv.toBlob(b => r(b!), 'image/png'));
        await save('assets/icons/' + file, await blob.arrayBuffer());
      }
      index[k] = file;
    }
    await saveJSON('assets/icons/index.json', index);
    log('icons', seen.size);
  }

  // ---------- data tables
  if (want('data')) {
    await saveJSON('data/items.json', ITEMS);
    await saveJSON('data/recipes.json', RECIPES);
    await saveJSON('data/crops.json', CROPS);
    await saveJSON('data/skills.json', { skills: SKILLS, traits: TRAITS, occupations: OCCUPATIONS });
    await saveJSON('data/factory.json', { machines: MACHINES, pieces: PIECES, milestones: MILESTONES, recipes: MRECIPES });
    await saveJSON('data/containers.json', Object.fromEntries(Object.keys(CONTAINER_LABEL).map(k => [k, { label: (CONTAINER_LABEL as any)[k], cap: containerCapacity(k as any) }])));
  }

  // ---------- sound: every effect is a synthesis recipe; render each one to a .wav
  if (want('audio')) {
    const SR = 44100;
    const wav = (buf: AudioBuffer) => {
      const ch = buf.numberOfChannels, n = buf.length, out = new DataView(new ArrayBuffer(44 + n * ch * 2));
      const str = (o: number, s2: string) => { for (let i = 0; i < s2.length; i++) out.setUint8(o + i, s2.charCodeAt(i)); };
      str(0, 'RIFF'); out.setUint32(4, 36 + n * ch * 2, true); str(8, 'WAVEfmt '); out.setUint32(16, 16, true); out.setUint16(20, 1, true); out.setUint16(22, ch, true);
      out.setUint32(24, SR, true); out.setUint32(28, SR * ch * 2, true); out.setUint16(32, ch * 2, true); out.setUint16(34, 16, true); str(36, 'data'); out.setUint32(40, n * ch * 2, true);
      const data = []; for (let c = 0; c < ch; c++) data.push(buf.getChannelData(c));
      let o = 44; for (let i = 0; i < n; i++) for (let c = 0; c < ch; c++) { const v = Math.max(-1, Math.min(1, data[c][i])); out.setInt16(o, v < 0 ? v * 0x8000 : v * 0x7fff, true); o += 2; }
      return new Uint8Array(out.buffer);
    };
    const index: Record<string, any> = {}; let total = 0;
    const all: [string, any][] = [...Object.entries(SFX), ...Object.entries(INTERNAL)];
    for (const [name, def] of all) {
      const vars = Math.min(3, def.vars ?? 1);
      for (let v = 0; v < vars; v++) {
        const off = new OfflineAudioContext(def.stereo ? 2 : 1, Math.ceil(def.dur * SR), SR);
        const out = off.createGain(); out.connect(off.destination); def.build(off, out, v);
        const bytes = wav(finalize(await off.startRendering(), 0.89)); total += bytes.length;
        await save(`assets/sfx/${name}_${v}.wav`, bytes);
      }
      index[name] = { vars, vol: def.vol ?? 1, maxDist: def.maxDist ?? 0, jitter: def.jitter ?? 0 };
    }
    for (const [name, def] of Object.entries(LOOPS) as [string, any][]) {
      const off = new OfflineAudioContext(1, Math.ceil((def.len + 0.3) * SR), SR);
      const out = off.createGain(); out.connect(off.destination); def.build(off, out, 0);
      const lb = makeLoopable(await off.startRendering(), def.len);
      const d = lb.getChannelData(0); let pk = 0; for (let i = 0; i < d.length; i++) pk = Math.max(pk, Math.abs(d[i])); if (pk > 0) for (let i = 0; i < d.length; i++) d[i] *= 0.85 / pk;
      const bytes = wav(lb); total += bytes.length;
      await save(`assets/sfx/loop_${name}.wav`, bytes);
      index['loop_' + name] = { loop: true, vol: def.vol ?? 1, len: def.len };
    }
    await saveJSON('assets/sfx/index.json', index);
    log('audio', Object.keys(index).length, 'sounds', (total / 1e6).toFixed(1) + ' MB');
  }

  // ---------- the world
  if (want('world')) {
    const scene = new THREE.Scene();
    const world = new World(scene, renderer, SEED, QUALITY.high, (p, l) => log('world', p.toFixed(2), l));
    const terr: any = world.terrain, town: any = world.town, veg: any = world.veg, col: any = world.col;
    // terrain
    const N = T_N + 1; const hs = new Float32Array(N * N); hs.set(terr.heights);
    await save('data/height.bin', new Uint8Array(hs.buffer));
    const SN = Math.round(Math.sqrt(terr.splatData.length / 4));
    // raw bytes: a canvas would premultiply the alpha channel (sand) into the others
    await save('data/splat.bin', terr.splatData as Uint8Array);
    const splat = SN, noise = texRef(getCloudNoiseTexture()), waterN = texRef(getWaterNormalTexture(), true);
    const sets: Record<string, any> = {};
    for (const n of ['grass', 'dirt', 'rock', 'forestFloor', 'asphalt', 'sand'] as const) { const s = getTexSet(n as any); sets[n] = { map: texRef(s.map), normal: texRef(s.normalMap, true), rough: texRef(s.roughnessMap) }; }
    // forest density on a coarse grid (for grass and ambience)
    const FD = 201; const fd = new Uint8Array(FD * FD);
    for (let j = 0; j < FD; j++) for (let i = 0; i < FD; i++) fd[j * FD + i] = Math.max(0, Math.min(255, Math.round(terr.forestDensity(-400 + i * 4, -400 + j * 4) * 255)));
    await save('data/forest.bin', fd);

    // town: name the parts scripts need to find
    for (const b of town.buildings) { b.group.name = 'b' + b.id; if (b.interior) b.interior.name = 'b' + b.id + '_int'; if (b.roof) b.roof.name = 'b' + b.id + '_roof'; }
    for (const d of town.doors) d.pivot.name = 'door' + d.id;
    for (const w of town.windows) if (w.glass) w.glass.name = 'win' + w.id;
    town.props.group.userData.noExport = true;
    const v3 = (v: THREE.Vector3) => [r3(v.x), r3(v.y), r3(v.z)];
    // loot is rolled once, here, so both versions of the world hold the same things
    const lrng = new RNG(SEED + 4242);
    const contJSON = (c: any) => { if (!c.generated) { rollLoot(c.kind, lrng, 0, c.inv); c.generated = true; } return { id: c.id, kind: c.kind, label: c.label, pos: v3(c.pos), half: v3(c.half), building: c.building ? c.building.id : 0, stacks: c.inv.stacks.map((s: any) => { const o: any = { id: s.id, n: s.n }; if (s.dur !== undefined) o.dur = s.dur; if (s.ammo !== undefined) o.ammo = s.ammo; if (s.age !== undefined) o.age = r3(s.age); return o; }) }; };
    const P: any = town.props;
    const tplIndex: Record<string, any[]> = {}; const ctxP = newCtx();
    for (const [key, subs] of P.templates as Map<string, any[]>) tplIndex[key] = subs.map(s => ({ mesh: libMesh(ctxP, s.geo, s.mat, s.local), tint: !!s.colorable, shadow: !!s.shadow }));
    const entries = (P.entries as any[]).filter(e => e.alive).map(e => ({ t: e.tpl, m: (e.m.elements as number[]).map(r3), c: e.color ? [r3(e.color.r), r3(e.color.g), r3(e.color.b)] : undefined }));
    await saveGLB('assets/world/props.glb', new THREE.Object3D(), ctxP);
    await saveJSON('assets/world/props.json', { templates: tplIndex, entries });
    const townBytes = await exportObject('assets/world/town', town.group, {
      buildings: town.buildings.map((b: any) => ({ id: b.id, kind: b.kind, x: r3(b.x), z: r3(b.z), w: b.w, d: b.d, floorY: r3(b.floorY), ceilY: r3(b.ceilY), zombieSpawns: b.zombieSpawns.map(v3), lamps: b.lamps.map(v3) })),
      doors: town.doors.map((d: any) => ({ id: d.id, axis: d.axis, cx: r3(d.cx), cz: r3(d.cz), y: r3(d.y), width: d.width, dir: d.dir, locked: d.locked, hp: d.hp, open: d.open, box: d.box.id, building: d.building ? d.building.id : 0 })),
      windows: town.windows.map((w: any) => ({ id: w.id, axis: w.axis, cx: r3(w.cx), cz: r3(w.cz), y: r3(w.y), box: w.box.id, building: w.building ? w.building.id : 0 })),
      containers: town.containers.map(contJSON),
      cars: town.carBoxes.map((c: any) => ({ id: c.id, variant: c.variant, color: c.color, yaw: r3(c.yaw), pos: v3(c.pos), size: c.size, fuel: r3(c.fuel), keys: c.keys, condition: r3(c.condition), box: c.box.id, container: c.container.id })),
      streetLamps: town.streetLamps.map(v3),
    });
    log('town', (townBytes / 1e6).toFixed(1) + ' MB', town.buildings.length, 'buildings', entries.length, 'props');

    // vegetation: one library of models, and where each stands
    const ctxV = newCtx(); const vkeys: any[] = [];
    (veg.variants as any[]).forEach((v, i) => { vkeys.push({ kind: v.kind, height: r3(v.model.height), trunk: r3(v.model.trunkRadius), parts: v.model.parts.map((p: any) => libMesh(ctxV, p.geometry, p.material)) }); void i; });
    await saveGLB('assets/world/veg.glb', new THREE.Object3D(), ctxV);
    const vi = new Float32Array(veg.insts.length * 6); const vk: string[] = [];
    (veg.insts as any[]).forEach((t, i) => { vi.set([t.x, t.y, t.z, t.s, t.r, t.v], i * 6); vk.push(t.kind[0] + (t.ore ? ':' + t.ore : '')); });
    await save('data/veg.bin', new Uint8Array(vi.buffer));
    await saveJSON('assets/world/veg.json', { variants: vkeys, count: veg.insts.length, kinds: vk });
    log('veg', veg.insts.length, 'instances', vkeys.length, 'variants');

    // ore deposits
    const oreRoot = new THREE.Group();
    for (const o of world.ores) { o.mesh.name = 'ore' + o.id; }
    const oreGroup = new THREE.Group(); for (const o of world.ores) oreGroup.add(o.mesh);
    await exportObject('assets/world/ores', oreGroup, { ores: world.ores.map(o => ({ id: o.id, type: o.type, x: o.x, y: r3(o.y), z: o.z, purity: o.purity })) }); void oreRoot;

    // collision, nav, floors
    const boxes: any[] = []; for (const b of col.boxes.values()) boxes.push([r3(b.minX), r3(b.minY), r3(b.minZ), r3(b.maxX), r3(b.maxY), r3(b.maxZ), b.solid ? 1 : 0, b.tag ?? '', b.id]);
    const circles: any[] = []; for (const c of col.circles.values()) circles.push([r3(c.x), r3(c.z), r3(c.r), r3(c.h), c.ref && c.ref.kind ? c.ref.kind : c.ref && c.ref.type ? 'ore' : '', c.ref && c.ref.idx !== undefined ? c.ref.idx : c.ref && c.ref.id !== undefined ? c.ref.id : -1]);
    await save('data/nav.bin', col.nav as Uint8Array);
    const L: any = world.layout;
    await saveJSON('data/world.json', {
      seed: SEED, size: WORLD_SIZE, terrain: { n: N, res: T_RES, splatN: splat, noise, sets, forestN: FD }, water: { ...LAKE, normal: waterN },
      town: TOWN_RECT, spawn: SPAWN, cabin: CABIN, navN: NAV_N,
      roads: L.roads, plots: L.plots, ores: L.ores,
      floors: world.floors.map(f => ({ x0: r3(f.x0), z0: r3(f.z0), x1: r3(f.x1), z1: r3(f.z1), y: r3(f.y), b: f.ref ? f.ref.id : 0 })),
      boxes, circles,
    });
    log('collision', boxes.length, 'boxes', circles.length, 'circles');
  }

  await Promise.all(texJobs);
  await saveJSON(what.has("all") ? "assets/materials.json" : `assets/materials_${[...what].join("_")}.json`, { materials: matList });
  log('materials', Object.keys(matList).length, 'textures', texIds.size);
}
run().then(() => { W.__exportDone = true; }).catch(e => { console.error(e); W.__exportError = String(e && e.stack || e); });
