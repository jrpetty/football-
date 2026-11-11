// Runs inside node (bundled by characters.mjs). Builds the body mesh with the game's own code, bakes every
// animation clip by calling the game's poseBody(), and turns the web shader's look code into a Godot shader.
import fs from 'node:fs';
import path from 'node:path';
import { buildBodyGeometry } from '../../src/ai/bodyGeo';
import { NB, PIV, KIND, ACT, TOOL, ZACT, makeDef, refreshDef, newAnim, poseBody, strideLen, lookFromSeed, hash1, gaitFor } from '../../src/ai/bodyRig';
import type { BodyDef, BodyAnim } from '../../src/ai/bodyRig';

const ROWF = NB * 12;            // floats poseBody writes per body
const COLS = NB * 2;             // texels per baked frame: (quat, pos + spare) per bone
const BLOCK_H = 2048;            // rows per atlas column

type Row = Float32Array;         // COLS * 4 floats
interface Clip { name: string; frames: Row[]; fps: number; loop: boolean; stride?: number; hit?: number; x?: number; y?: number }

const tmp = new Float32Array(256);
function pose(a: BodyAnim, d: BodyDef): Row {
  poseBody(a, d, tmp, 0);
  const r = new Float32Array(COLS * 4);
  for (let b = 0; b < NB; b++) {
    const k = b * 12, o = b * 8;
    r[o] = tmp[k]; r[o + 1] = tmp[k + 1]; r[o + 2] = tmp[k + 2]; r[o + 3] = tmp[k + 3];
    r[o + 4] = tmp[k + 4]; r[o + 5] = tmp[k + 5]; r[o + 6] = tmp[k + 6]; r[o + 7] = tmp[k + 7];
  }
  // the aux bone's animated swell (bloat gut, howler throat) rides in its spare float; the jaw's opening in the jaw's
  const s = d.shape[17 * 3] || 1; r[17 * 8 + 7] = d.shape[17 * 3] > 0 ? tmp[17 * 12 + 8] / s : 1;
  r[3 * 8 + 7] = 0;
  return r;
}
function mixRow(a: Row, b: Row, w: number): Row {
  const r = new Float32Array(a.length);
  for (let bn = 0; bn < NB; bn++) {
    const o = bn * 8; let dot = 0; for (let i = 0; i < 4; i++) dot += a[o + i] * b[o + i];
    const sg = dot < 0 ? -1 : 1; let l = 0;
    for (let i = 0; i < 4; i++) { r[o + i] = a[o + i] * (1 - w) + b[o + i] * sg * w; l += r[o + i] * r[o + i]; }
    l = Math.sqrt(l) || 1; for (let i = 0; i < 4; i++) r[o + i] /= l;
    for (let i = 4; i < 8; i++) r[o + i] = a[o + i] * (1 - w) + b[o + i] * w;
  }
  return r;
}
/** keep each bone's quaternion in the same hemisphere from frame to frame so the GPU can lerp between rows */
function continuous(fr: Row[], loop: boolean) {
  for (let i = 1; i < fr.length; i++) for (let b = 0; b < NB; b++) {
    const o = b * 8; let dot = 0; for (let k = 0; k < 4; k++) dot += fr[i][o + k] * fr[i - 1][o + k];
    if (dot < 0) for (let k = 0; k < 4; k++) fr[i][o + k] = -fr[i][o + k];
  }
  void loop;
}

/**
 * Sample a clip. f(a, u, t) fills the anim for cycle position u (0..1) at time t seconds. Looping clips are
 * made seamless by cross-fading their tail with the lead-in (same u, time shifted one period back).
 */
function bake(name: string, d: BodyDef, dur: number, fps: number, loop: boolean, f: (a: BodyAnim, u: number, t: number) => void, extra: Partial<Clip> = {}): Clip {
  const n = Math.max(2, Math.round(dur * fps)); const frames: Row[] = [];
  const at = (i: number, shift: number) => { const a = newAnim(); const u = loop ? i / n : i / (n - 1); const t = u * dur + shift; a.t = t - d.seed * 7.3; a.stance = 0; f(a, u, t); return pose(a, d); };
  for (let i = 0; i < n; i++) {
    let r = at(i, 0);
    if (loop) { const u = i / n, b = 0.22; if (u > 1 - b) r = mixRow(r, at(i, -dur), (u - (1 - b)) / b); }
    frames.push(r);
  }
  continuous(frames, loop);
  return { name, frames, fps: loop ? n / dur : (n - 1) / dur, loop, ...extra };
}
const TWO = Math.PI * 2;
const sm = (a: number, b: number, x: number) => { const t = x <= a ? 0 : x >= b ? 1 : (x - a) / (b - a); return t * t * (3 - 2 * t); };

// attack timing per kind: wind-up seconds and full period (same table the AI uses)
const WIND = [0.42, 0.3, 0.62, 0.4, 0.5, 0.75], PERIOD = [1.3, 1.1, 1.6, 1.4, 1.7, 1.9];
const WALK_V = [0.9, 1.2, 1.0, 1.1, 0.6, 0.9], CHASE_V = [2.2, 5.0, 2.6, 3.0, 1.3, 2.3];

function canon(d: BodyDef) { d.lean = 0.5; for (let i = 0; i < 8; i++) d.rnd[i] = 0.5; }

function zombieRig(kind: number, gait: number): { def: BodyDef; clips: Clip[] } {
  const seed = 2.5 + kind * 1.3 + gait * 0.7;
  const d = makeDef(kind, seed, { ...lookFromSeed(seed), build: 1, height: 1 });
  d.decay = 0.45; d.gait = gait; canon(d); refreshDef(d);
  const C: Clip[] = [];
  const cyc = (name: string, v: number, arms: number, fr = 24) => {
    const S = strideLen(d, v);
    C.push(bake(name, d, S / v, fr / (S / v), true, (a, u) => { a.speed = v; a.phase = u * TWO; a.arms = arms; a.move = 1; }, { stride: S }));
  };
  C.push(bake('idle', d, 8, 8, true, () => {}));
  cyc('walk', WALK_V[kind], 0);
  cyc('chase', CHASE_V[kind], 1, kind === 1 ? 16 : 24);
  if (kind === 0) cyc('run', 4.3, 1, 16);
  C.push(bake('turn', d, 1.1, 14, true, (a, u) => { a.turn = 1; a.phase = u * TWO; }));
  {
    const w = WIND[kind], P = PERIOD[kind];
    C.push(bake('attack', d, P, 20, true, (a, u, t) => {
      a.arms = 1; a.atk = 1;
      a.lunge = t < w ? -sm(0, w, t) : t < w + 0.1 ? -1 + 2 * (t - w) / 0.1 : t < w + 0.3 ? 1 : Math.max(0, 1 - (t - w - 0.3) / 0.35);
    }, { hit: w + 0.07 }));
  }
  for (const [nm, hs, hf] of [['hit_f', 0, 1], ['hit_b', 0, -1], ['hit_l', 1, 0.3], ['hit_r', -1, 0.3]] as [string, number, number][])
    C.push(bake(nm, d, 0.5, 20, false, (a, u, t) => { a.arms = 0.5; a.hit = Math.max(0, 1 - t * 2.6); a.hitSide = hs; a.hitFwd = hf; }));
  for (const [sfx, face] of [['back', 1], ['front', -1]] as [string, number][]) {
    for (const [tag, off] of [['', face > 0 ? -0.55 : 0.55], ['0', 0]] as [string, number][]) {
      const fall = (a: BodyAnim, dn: number, mode: number) => { a.down = dn; a.downMode = mode; a.downFace = face; a.downOff = off; };
      C.push(bake((tag ? 'drop_' : 'fall_') + sfx + tag, d, tag ? 1.1 : 0.55, tag ? 12 : 20, false, (a, u) => fall(a, u, 0)));
      C.push(bake('lie_' + sfx + tag, d, 0.5, 4, false, (a) => fall(a, 1, 1)));
      C.push(bake('rise_' + sfx + tag, d, 2.0, 12, false, (a, u) => fall(a, 1 - u, 1)));
    }
  }
  C.push(bake('crawl', d, 1.55, 13, true, (a, u) => { a.crawl = 1; a.phase = u * TWO; a.speed = 0.45; }, { stride: TWO / 9 }));
  C.push(bake('death_crawl', d, 0.8, 10, false, (a, u) => { a.crawl = 1; a.phase = 1.0; a.death = u; a.deathStyle = 5; }));
  C.push(bake('feed', d, 4, 10, true, (a) => { a.act = ZACT.feed; a.actW = 1; }));
  C.push(bake('slump', d, 6, 6, true, (a) => { a.act = ZACT.slump; a.actW = 1; }));
  C.push(bake('sniff', d, 4, 10, true, (a) => { a.act = ZACT.sniff; a.actW = 1; a.arms = 0.2; }));
  C.push(bake('paw', d, TWO / 2.2, 10, true, (a) => { a.act = ZACT.paw; a.actW = 1; }));
  {
    // the scream: head back, arms flung wide, the sac swelling — simulated at 60 Hz the way the AI ramps it
    const N = 24, dur = 1.9, fr: Row[] = []; let w = 0, aux = 0, k = 0;
    for (let i = 0; i <= Math.round(dur * 60); i++) {
      const t = i / 60;
      if (i > 0) { w = Math.min(1, w + 2.5 / 60); aux += (1 - aux) * Math.min(1, 3.5 / 60); }
      if (t >= k * dur / (N - 1) - 1e-6 && k < N) { const a = newAnim(); a.t = t - d.seed * 7.3; a.act = ZACT.scream; a.actW = w; a.aux = aux; a.arms = 0.3 * (1 - w); fr.push(pose(a, d)); k++; }
    }
    continuous(fr, false); C.push({ name: 'scream', frames: fr, fps: (N - 1) / dur, loop: false });
  }
  for (let st = 0; st < 5; st++) {
    const dur = st === 3 ? 1.0 : st === 4 ? 1.25 : 0.87;
    C.push(bake('death_' + st, d, dur, 20, false, (a, u) => { a.death = u; a.deathStyle = st; a.hitSide = 1; a.arms = 0.5; }));
  }
  return { def: d, clips: C };
}

const STANCE_NAMES: Record<number, string> = { 1: 'idle_hips', 2: 'idle_cross', 3: 'idle_belt', 4: 'idle_onehip', 8: 'idle_hipr' };

function humanRig(tool: number): { def: BodyDef; clips: Clip[] } {
  const seed = 4.25;
  const d = makeDef(KIND.human, seed, { ...lookFromSeed(seed), build: 1, height: 1 });
  d.tool = tool; canon(d); refreshDef(d);
  const C: Clip[] = [];
  const S = 1.96;
  const cyc = (name: string, move: number, f: (a: BodyAnim) => void = () => {}, fr = 24) => {
    const v = move > 1 ? 4.6 : 1.5, T = S / v;
    C.push(bake(name, d, T, fr / T, true, (a, u) => { a.move = move; a.phase = u * TWO; f(a); }, { stride: S }));
  };
  C.push(bake('idle', d, 8, 8, true, () => {}));
  if (tool <= 1) {
    // the resting stances: find, for this seed, a stretch of time in which the rig settles into each one
    const span = 17 + d.rnd[2] * 12;
    for (const key of Object.keys(STANCE_NAMES)) {
      const want = +key; let slot = -1;
      for (let n = 2; n < 4000 && slot < 0; n++) if (Math.floor(hash1(n * 12.9898 + seed * 4.1) * 10) % 10 === want) slot = n;
      if (slot < 0) throw new Error('no slot for stance ' + key);
      const t0 = (slot - seed * 3.7 + 0.5) * span;
      C.push(bake(STANCE_NAMES[want], d, 8, 6, true, (a, u, t) => { a.t = t0 + t - d.seed * 7.3; a.stance = 1; }));
    }
    d.sick = 0.8; refreshDef(d);
    C.push(bake('idle_sick', d, 8, 8, true, () => {}));
    { const T = S / 1.1; C.push(bake('walk_sick', d, T, 24 / T, true, (a, u) => { a.move = 1; a.phase = u * TWO; }, { stride: S })); }
    d.sick = 0; refreshDef(d);
  }
  cyc('walk', 1);
  cyc('run', 1.7, () => {}, 16);
  C.push(bake('aim', d, 4, 6, true, (a) => { a.arms = 1; }));
  cyc('walk_aim', 1, (a) => { a.arms = 1; });
  C.push(bake('melee', d, 0.36, 30, false, (a, u, t) => { a.atk = Math.max(0.0001, 1 - t * 3); }));
  C.push(bake('hit', d, 0.4, 20, false, (a, u, t) => { a.hit = Math.max(0, 1 - t * 3); a.hitFwd = 1; }));
  const act = (name: string, k: number, dur: number, fps: number) => C.push(bake(name, d, dur, fps, true, (a) => { a.act = k; a.actW = 1; }));
  act('swing', ACT.swing, 1.25, 16); act('tend', ACT.tend, TWO / 3.1, 10); act('sit', ACT.sit, 6, 6); act('cower', ACT.cower, 4, 10);
  act('talk', ACT.talk, 5, 10); act('carry', ACT.carry, 4, 6); act('lie', ACT.lie, 4, 4); act('wave', ACT.wave, TWO / 7 * 2, 15);
  act('stir', ACT.stir, TWO / 3.4, 12); act('eat', ACT.eat, TWO / 2.2, 10);
  cyc('walk_carry', 1, (a) => { a.act = ACT.carry; a.actW = 1; });
  return { def: d, clips: C };
}

// ------------------------------------------------------------------ shader generation
function grab(src: string, name: string): string {
  const m = new RegExp('const ' + name + ' = `([\\s\\S]*?)`;').exec(src);
  if (!m) throw new Error('shader block ' + name + ' not found in zombieMesh.ts');
  return m[1];
}
/** find every call fn(...) (innermost first) and let edit() replace it; returning null keeps the call */
function rewriteCalls(code: string, fn: string, edit: (args: string[]) => string | null): string {
  let out = '', i = 0;
  for (;;) {
    let k = code.indexOf(fn + '(', i);
    while (k > 0 && /[A-Za-z0-9_]/.test(code[k - 1])) k = code.indexOf(fn + '(', k + 1);
    if (k < 0) { out += code.slice(i); break; }
    let depth = 0, j = k + fn.length, start = j + 1; const args: string[] = [];
    for (; j < code.length; j++) {
      const c = code[j];
      if (c === '(') depth++;
      else if (c === ')') { depth--; if (depth === 0) { args.push(code.slice(start, j)); break; } }
      else if (c === ',' && depth === 1) { args.push(code.slice(start, j)); start = j + 1; }
    }
    const rec = args.map(a => rewriteCalls(a, fn, edit));
    out += code.slice(i, k) + (edit(rec.map(a => a.trim())) ?? fn + '(' + rec.join(',') + ')');
    i = j + 1;
  }
  return out;
}
function portLook(src: string): { decl: string; color: string; emit: string } {
  let decl = grab(src, 'FRAG_DECL'), color = grab(src, 'FRAG_COLOR'), emit = grab(src, 'FRAG_EMIT');
  // helper functions only (uniforms, globals and the texel macro are declared by the template)
  decl = decl.split('\n').filter(l => !/^\s*(uniform |float zSkinF|#define ZL)/.test(l)).join('\n');
  const fix = (c: string) => {
    c = c.replace(/texelFetch\(uZBones, ivec2\(13, zrow\), 0\)\.w/g, 'z_jaw').replace(/texelFetch\(uZBones, ivec2\(10, zrow\), 0\)\.w/g, 'z_blink');
    if (/uZBones/.test(c)) throw new Error('unported bone-texture read in the look shader');
    c = c.replace(/diffuseColor\.rgb = zc \* ZALB \* \(isSkin > 0\.5 \? ZWARM : vec3\(1\.0\)\);/, 'z_albedo = zc;');
    if (/diffuseColor/.test(c)) throw new Error('unported diffuseColor write');
    c = c.replace(/totalEmissiveRadiance \+=/g, 'z_emit +=').replace(/uZNight/g, 'night');
    c = rewriteCalls(c, 'pow', a => a.length === 2 && a[1] === '2.0' ? 'zsq(' + a[0] + ')' : null);
    c = c.replace(/\bsmoothstep\(/g, 'zsm(');
    return c;
  };
  return { decl: fix(decl), color: fix(color), emit: fix(emit) };
}

export async function run(o: { root: string; outDir: string; template: string }) {
  const dataDir = path.join(o.outDir, 'data'); fs.mkdirSync(dataDir, { recursive: true });
  // ---- mesh
  const g = buildBodyGeometry();
  const pos = g.attributes.position.array as Float32Array, nor = g.attributes.normal.array as Float32Array, part = g.attributes.aPart.array as Float32Array;
  const idx = Uint32Array.from(g.index!.array as ArrayLike<number>);
  for (let i = 0; i < idx.length; i += 3) { const t = idx[i + 1]; idx[i + 1] = idx[i + 2]; idx[i + 2] = t; }   // Godot's front faces wind clockwise
  const nv = pos.length / 3;
  const meshBuf = Buffer.concat([Buffer.from(pos.buffer, pos.byteOffset, pos.byteLength), Buffer.from(nor.buffer, nor.byteOffset, nor.byteLength), Buffer.from(part.buffer, part.byteOffset, part.byteLength), Buffer.from(idx.buffer)]);
  fs.writeFileSync(path.join(dataDir, 'body_mesh.bin'), meshBuf);

  // ---- clips
  const rigs: Record<string, { def: BodyDef; clips: Clip[] }> = {};
  for (let gt = 0; gt < 5; gt++) rigs['w' + gt] = zombieRig(0, gt);
  for (const [nm, k] of [['runner', 1], ['brute', 2], ['howler', 3], ['bloat', 4], ['breaker', 5]] as [string, number][]) rigs[nm] = zombieRig(k, gaitFor(k, 1));
  for (const [nm, tl] of [['h0', 0], ['h1', TOOL.wrench], ['h2', TOOL.axe], ['h3', TOOL.hoe]] as [string, number][]) rigs[nm] = humanRig(tl);
  // pack: each clip is a run of rows (+1 pad row so the GPU's linear filter can interpolate past the last frame)
  let bx = 0, by = 0; const all: Clip[] = [];
  for (const r of Object.values(rigs)) for (const c of r.clips) {
    const need = c.frames.length + 1; if (by + need > BLOCK_H) { bx++; by = 0; }
    c.x = bx * COLS; c.y = by; by += need; all.push(c);
  }
  const W = (bx + 1) * COLS, H = BLOCK_H;
  const tex = new Float32Array(W * H * 4);
  for (const c of all) for (let i = 0; i <= c.frames.length; i++) {
    const src = i < c.frames.length ? c.frames[i] : c.loop ? c.frames[0] : c.frames[c.frames.length - 1];
    let row = src;
    if (i === c.frames.length && c.loop) { row = Float32Array.from(src); const last = c.frames[c.frames.length - 1]; for (let b = 0; b < NB; b++) { const q = b * 8; let dot = 0; for (let k = 0; k < 4; k++) dot += row[q + k] * last[q + k]; if (dot < 0) for (let k = 0; k < 4; k++) row[q + k] = -row[q + k]; } }
    tex.set(row, ((c.y! + i) * W + c.x!) * 4);
  }
  fs.writeFileSync(path.join(dataDir, 'body_clips.bin'), Buffer.from(tex.buffer));

  // ---- reference values: looks and shapes for a few bodies, so the GDScript port of refreshDef can be checked
  const tests: any[] = [];
  const tcase = (kind: number, seed: number, st: Partial<BodyDef> = {}, look: any = null) => { const d = makeDef(kind, seed, look); Object.assign(d, st); if (kind !== KIND.human) d.gait = gaitFor(kind, seed); refreshDef(d); tests.push({ kind, seed, look, state: st, gait: d.gait, height: d.height, shape: Array.from(d.shape), out: Array.from(d.look) }); };
  for (let k = 0; k < 6; k++) { tcase(k, 10 + k * 3.7, { decay: 0.4 }); tcase(k, 61.5 + k, { decay: 0.92, armLossL: k % 2 === 0, armLossR: k === 3 }); }
  for (let i = 0; i < 8; i++) tcase(KIND.human, 3 + i * 5.1, { tool: i, sick: i === 5 ? 0.7 : 0 });
  tcase(KIND.human, 13.2, {}, { skin: 0xb97f55, shirt: 0x3f6b8a, pants: 0x4a4234, hair: 0x1c1611, build: 1.22, height: 1.04, hairStyle: 5, outfit: 1, jacket: 0x6a4a2a, gear: 3 });
  tcase(0, 13.2, { decay: 0 }, { skin: 0xb97f55, shirt: 0x3f6b8a, pants: 0x4a4234, hair: 0x1c1611, build: 1.22, height: 1.04, hairStyle: 5, outfit: 1, jacket: 0x6a4a2a, gear: 3 });

  const meta = {
    note: 'generated by scripts/godot-export/characters.mjs — do not edit',
    mesh: { verts: nv, indices: idx.length },
    bones: NB, piv: PIV, cols: COLS, clipTex: { w: W, h: H },
    rigs: Object.fromEntries(Object.entries(rigs).map(([nm, r]) => [nm, {
      chestSx: r.def.shape[6], hipSx: r.def.shape[0],
      clips: Object.fromEntries(r.clips.map(c => [c.name, { x: c.x, y: c.y, n: c.frames.length, fps: +c.fps.toFixed(4), loop: c.loop, ...(c.stride ? { stride: +c.stride.toFixed(4) } : {}), ...(c.hit ? { hit: +c.hit.toFixed(3) } : {}) }])),
    }])),
    tests,
  };
  fs.writeFileSync(path.join(dataDir, 'body.json'), JSON.stringify(meta));

  // ---- shader
  const src = fs.readFileSync(path.join(o.root, 'src/ai/zombieMesh.ts'), 'utf8');
  const lk = portLook(src);
  const piv: string[] = []; for (let b = 0; b < NB; b++) piv.push(`vec3(${PIV[b * 3].toFixed(4)}, ${PIV[b * 3 + 1].toFixed(4)}, ${PIV[b * 3 + 2].toFixed(4)})`);
  let sh = fs.readFileSync(o.template, 'utf8');
  sh = sh.replace('/*%PIV%*/', `const vec3 ZPIV[${NB}] = {${piv.join(', ')}};`).replace('/*%CLIP_SIZE%*/', `const vec2 CLIP_SIZE = vec2(${W}.0, ${H}.0);`)
    .replace('/*%LOOK_DECL%*/', lk.decl).replace('/*%LOOK_COLOR%*/', lk.color).replace('/*%LOOK_EMIT%*/', lk.emit);
  fs.writeFileSync(path.join(o.outDir, 'body.gdshader'), sh);
  const frames = all.reduce((s, c) => s + c.frames.length, 0);
  console.log(`body: ${nv} verts, ${idx.length / 3} tris; ${Object.keys(rigs).length} rigs, ${all.length} clips, ${frames} frames -> ${W}x${H} texture; ${tests.length} reference bodies`);
}
