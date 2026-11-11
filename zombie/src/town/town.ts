// The settlement: residents move in, get homes and jobs, eat at the mess hall, need water, and stay
// only while they're fed, housed, safe and reasonably happy. Drives settlement-level requirements.
import * as THREE from 'three';
import type { Factory, Machine } from '../factory/factory';
import type { Base, Piece } from '../build/base';
import type { Survivors, Survivor, Job, TownGoal } from '../ai/survivors';
import type { World } from '../world/world';
import { Inventory } from '../items/inventory';
import { item } from '../items/items';
import { RECIPES } from '../items/recipes';
import { CROPS } from '../items/content';
import { RNG } from '../core/util';
import { audio } from '../audio/Audio';
import { ACT, TOOL, JOB_SKILL, jobScore, workRate, lvl, has, maxHp, think, moodSum, background } from '../ai/people';

/** what each town building offers */
export const TOWN: Record<string, { beds?: number; job?: Job; slots?: number; amenity?: number }> = {
  shelter_house: { beds: 4 },
  bunkhouse: { beds: 10 },
  hub: { beds: 2 },
  mess_hall: { job: 'cook', slots: 1, amenity: 8 },
  water_tower: { amenity: 4 },
  guard_post: { job: 'guard', slots: 1 },
  lumber_yard: { job: 'lumberjack', slots: 2 },
  workshop: { job: 'mechanic', slots: 1, amenity: 2 },
  lab: { job: 'medic', slots: 1, amenity: 6 },
};
export const JOB_INFO: Record<Job, { name: string; icon: string; desc: string }> = {
  idle: { name: 'Odd jobs', icon: '🧺', desc: 'Hangs around the hall and helps where they can.' },
  farmer: { name: 'Farmer', icon: '🌾', desc: 'Tends 4–9 farm plots depending on skill: waters, harvests and replants. Crops go to storage.' },
  cook: { name: 'Cook', icon: '🍲', desc: 'Cooks meals at the mess hall from ingredients stored there. Hot meals keep people happy.' },
  guard: { name: 'Guard', icon: '🛡️', desc: 'Mans a guard post and shoots zombies. Keep ammo in the post.' },
  lumberjack: { name: 'Lumberjack', icon: '🪓', desc: 'Fells trees and saws planks at the lumber yard.' },
  mechanic: { name: 'Mechanic', icon: '🔧', desc: 'Repairs damaged walls, doors and machines around town.' },
  medic: { name: 'Medic', icon: '🩺', desc: 'Treats the sick and wounded at the clinic; makes bandages from rags.' },
};
export const LEVEL_NAMES = ['Camp', 'Outpost', 'Settlement', 'Village', 'Town', 'Railhead', 'Lights On', 'City'];

export interface LevelReq { residents?: number; beds?: number; food?: number; defence?: number; power?: number; happiness?: number; grid?: boolean; water?: number }

const ARRIVAL_LINES = ['saw the smoke from your chimneys', 'heard about you on the radio', 'followed the lights', 'were told there was food here', 'walked for days to find people', 'heard the bell from the ridge'];

export class Town {
  private rng = new RNG(9191);
  private tick = 0;
  private lastArrivalDay = -1;
  private lastNeedsDay = -1;
  private attackT = 0; // recent zombie attack inside town (game minutes)
  stats = { residents: 0, beds: 0, food: 0, water: 0, defence: 0, power: 0, happiness: 0, homeless: 0, jobs: {} as Record<string, number> };
  log: string[] = [];

  constructor(private world: World, private factory: Factory, private base: Base, private survivors: Survivors, private notify: (m: string, k?: string) => void) {}

  residents() { return this.survivors.list.filter(s => s.recruited && !s.dead); }
  townsfolk() { return this.residents().filter(s => s.order === 'work' || s.order === 'guard'); }
  private hub() { return this.factory.hub; }
  private near(x: number, z: number, r = 70) { const h = this.hub(); return !!h && Math.hypot(x - h.cx, z - h.cz) < r; }
  private byId(id: number) { return this.factory.machines.find(m => m.id === id) ?? null; }
  buildings(type?: string) { return this.factory.machines.filter(m => TOWN[m.type] && (!type || m.type === type) && this.near(m.cx, m.cz)); }

  /** all storage inventories in town (for food, water bottles, seeds, ammo) */
  stores(): Inventory[] {
    const out: Inventory[] = [];
    for (const m of this.factory.machines) if (m.inv && (m.type === 'storage' || m.type === 'mess_hall' || m.type === 'train_station' || m.type === 'lumber_yard') && this.near(m.cx, m.cz)) out.push(m.inv);
    for (const pc of this.base.pieces) if (pc.container && this.near(pc.cx, pc.cz)) out.push(pc.container.inv);
    return out;
  }
  private takeFrom(invs: Inventory[], pred: (id: string) => boolean): string | null {
    for (const inv of invs) { const s = inv.stacks.find(x => pred(x.id)); if (s) { inv.removeUid(s.uid, 1); return s.id; } }
    return null;
  }
  /** where produce goes: the workplace first, then sheds/crates/stations (food may also go to the mess hall) */
  private deposit(id: string, n: number, prefer?: Machine | null) {
    let left = n;
    if (prefer?.inv) left = prefer.inv.add(id, left);
    const food = this.isFood(id) || !!item(id).cookTo;
    const targets: Inventory[] = [];
    for (const m of this.factory.machines) if (m.inv && (m.type === 'storage' || m.type === 'train_station' || (food && m.type === 'mess_hall')) && this.near(m.cx, m.cz)) targets.push(m.inv);
    for (const pc of this.base.pieces) if (pc.container && this.near(pc.cx, pc.cz)) targets.push(pc.container.inv);
    for (const inv of targets) { if (left <= 0) break; left = inv.add(id, left); }
    if (left > 0) { const h = this.hub(); if (h) { const inv = new Inventory(); inv.add(id, left); this.world.dropItems(new THREE.Vector3(h.cx + 4, h.y, h.cz + 4), inv); } }
  }
  isFood(id: string) { const d = item(id); return d.cat === 'food' && id !== 'rotten_food' && !d.cookTo && !(d.sick && d.sick > 0.2); }
  isMeal(id: string) { return RECIPES.some(r => (r.station === 'stove' || r.station === 'campfire') && r.out[0] === id) && this.isFood(id); }

  // ------------------------------------------------------------------ assignment
  private assign() {
    const folk = this.townsfolk();
    // homes
    const homes = this.buildings().filter(m => TOWN[m.type].beds);
    const used = new Map<number, number>();
    for (const s of folk) { const h = s.home >= 0 ? this.byId(s.home) : null; if (h && homes.includes(h)) used.set(h.id, (used.get(h.id) ?? 0) + 1); else s.home = -1; }
    for (const s of folk) {
      if (s.home >= 0) continue;
      const open = homes.filter(m => (used.get(m.id) ?? 0) < TOWN[m.type].beds!);
      // prefer a house a friend already lives in, then proper houses over the hall floor
      const friendHome = open.find(m => folk.some(o => o.home === m.id && (s.p.bonds[o.id] ?? 0) > 40));
      const h = friendHome ?? open.find(m => m.type !== 'hub' || homes.length === 1) ?? open[0];
      if (h) { s.home = h.id; used.set(h.id, (used.get(h.id) ?? 0) + 1); }
    }
    // jobs: drop invalid workplaces
    const slots = new Map<number, number>();
    for (const s of folk) {
      if (s.job === 'farmer' || s.job === 'idle') { s.work = -1; continue; }
      const w = s.work >= 0 ? this.byId(s.work) : null;
      if (w && TOWN[w.type]?.job === s.job && this.near(w.cx, w.cz) && (slots.get(w.id) ?? 0) < (TOWN[w.type].slots ?? 1)) slots.set(w.id, (slots.get(w.id) ?? 0) + 1);
      else { s.work = -1; const free = this.buildings().find(m => TOWN[m.type].job === s.job && (slots.get(m.id) ?? 0) < (TOWN[m.type].slots ?? 1)); if (free) { s.work = free.id; slots.set(free.id, (slots.get(free.id) ?? 0) + 1); } else if (!s.jobLock) s.job = 'idle'; }
    }
    // open posts go to whoever suits them best (unless the player pinned their job)
    const plots = this.base.pieces.filter(pc => pc.farm && this.near(pc.cx, pc.cz)).length;
    const farmers = folk.filter(s => s.job === 'farmer');
    let needFarmers = Math.max(0, Math.ceil((plots - farmers.reduce((a, s) => a + this.plotsFor(s), 0)) / 6));
    if (!plots) for (const s of farmers) if (!s.jobLock) s.job = 'idle';
    type Open = { job: Job; w: Machine | null };
    const open: Open[] = [];
    for (const m of this.buildings()) { const j = TOWN[m.type].job; if (!j) continue; for (let k = slots.get(m.id) ?? 0; k < (TOWN[m.type].slots ?? 1); k++) open.push({ job: j, w: m }); }
    for (let k = 0; k < needFarmers; k++) open.push({ job: 'farmer', w: null });
    const prio: Record<string, number> = { guard: 3, cook: 2.5, farmer: 2.5, medic: 2, lumberjack: 1, mechanic: 1 };
    while (open.length) {
      const idle = folk.filter(s => s.job === 'idle' && !s.jobLock);
      if (!idle.length) break;
      let best: { s: Survivor; o: Open; v: number } | null = null;
      for (const o of open) for (const s of idle) { const v = jobScore(s.p, o.job) + prio[o.job]; if (!best || v > best.v) best = { s, o, v }; }
      if (!best) break;
      best.s.job = best.o.job; best.s.work = best.o.w?.id ?? -1; best.s.task = null;
      open.splice(open.indexOf(best.o), 1);
    }
    // someone clearly better suited who is kicking their heels takes over the post
    for (const s of folk) {
      if (s.job === 'idle' || s.jobLock) continue;
      const cur = jobScore(s.p, s.job);
      const better = folk.filter(o => o.job === 'idle' && !o.jobLock && jobScore(o.p, s.job) >= cur + 5).sort((a, b) => jobScore(b.p, s.job) - jobScore(a.p, s.job))[0];
      if (better) { better.job = s.job; better.work = s.work; better.task = null; s.job = 'idle'; s.work = -1; s.task = null; break; }
    }
    // watches: with two or more guards, half take the night
    const guards = folk.filter(s => s.job === 'guard').sort((a, b) => Number(has(b.p, 'nightowl')) - Number(has(a.p, 'nightowl')) || a.id - b.id);
    guards.forEach((s, i) => { s.nightShift = guards.length > 1 ? i % 2 === 0 : has(s.p, 'nightowl'); });
    for (const s of folk) if (s.job !== 'guard') s.nightShift = false;
    for (const m of this.buildings()) (m as any).staffed = folk.some(s => s.work === m.id || (TOWN[m.type].beds && s.home === m.id));
    void needFarmers;
  }
  /** plots one farmer can keep up with */
  private plotsFor(s: Survivor) { return 4 + Math.floor(lvl(s.p, 'farming') / 2) + (has(s.p, 'greenthumb') ? 1 : 0); }
  setJob(s: Survivor, j: Job) { s.job = j; s.work = -1; s.task = null; s.jobLock = j !== 'idle'; if (s.order !== 'work') s.order = 'work'; this.assign(); }
  /** will they stand and fight when the bell rings? */
  private fighter(s: Survivor) { return s.job === 'guard' || has(s.p, 'brave') || (!has(s.p, 'coward') && (lvl(s.p, 'melee') >= 4 || (s.role === 'shooter' && s.ammo > 0))); }

  // ------------------------------------------------------------------ daily routine → movement goals
  private asleep(s: Survivor, hours: number) {
    if (s.nightShift) return hours >= 8 && hours < 15.5;
    const { wake, bed } = s.p; const b = bed >= 24 ? bed - 24 : bed;
    return bed >= 24 ? (hours >= b && hours < wake) : (hours >= bed || hours < wake);
  }
  /** what should this resident be doing right now, and where? */
  goal(s: Survivor, hours: number): TownGoal | null {
    const h = this.hub(); if (!h) return null;
    const p = s.p;
    const home = s.home >= 0 ? this.byId(s.home) : null;
    const work = s.work >= 0 ? this.byId(s.work) : null;
    const centre = (m: Machine) => new THREE.Vector3(m.cx, m.y, m.cz);
    const front = (m: Machine, d = 0.6) => { const W = (m.rot % 2 ? m.def.size[1] : m.def.size[0]) * 2, D = (m.rot % 2 ? m.def.size[0] : m.def.size[1]) * 2; const [fx, fz] = m.dir; return new THREE.Vector3(m.cx + fx * (Math.abs(fx) ? W / 2 + d : 0), m.y, m.cz + fz * (Math.abs(fz) ? D / 2 + d : 0)); };
    const jitter = (v: THREE.Vector3, r: number, salt = 0) => { const a = ((s.id + salt) * 2.39996) % 6.283, k = (((s.id + salt) * 7) % 10) / 10 * r; return v.clone().add(new THREE.Vector3(Math.cos(a) * k, 0, Math.sin(a) * k)); };
    const faceTo = (from: THREE.Vector3, to: THREE.Vector3) => Math.atan2(to.x - from.x, to.z - from.z);
    const goHome = (doing: string, want = 2.4): TownGoal => {
      if (home) { const f = front(home); return Math.hypot(s.pos.x - f.x, s.pos.z - f.z) < 2 ? { pos: centre(home), want: 0, hide: true, doing } : { pos: f, want, doing: 'Heading home', arrive: 1 }; }
      return { pos: jitter(front(h, 2), 4), want: Math.min(want, 2), act: ACT.lie, doing: doing === 'Sleeping' ? 'Sleeping rough by the hall' : doing };
    };
    // ---- danger
    if (s.mode === 'flee' || (this.attackT > 0 && !this.fighter(s))) {
      if (home) { const f = front(home); return Math.hypot(s.pos.x - f.x, s.pos.z - f.z) < 2 ? { pos: centre(home), want: 0, hide: true, doing: 'Sheltering' } : { pos: f, want: 5.2, doing: 'Running for cover', arrive: 1 }; }
      return { pos: jitter(front(h, 1), 2.5), want: 5.2, act: ACT.cower, doing: 'Sheltering' };
    }
    if (s.job === 'guard' && work && !this.asleep(s, hours)) { const top = (work.obj?.userData.standY as number | undefined) ?? 2.2; return { pos: new THREE.Vector3(work.cx, work.y + top, work.cz), want: this.attackT > 0 ? 5 : 2.6, perch: true, face: Math.atan2(work.cx - h.cx, work.cz - h.cz), tool: TOOL.gun, doing: s.nightShift ? 'On night watch' : 'On watch' }; }
    if (this.attackT > 0) { const to = this.alarmPos.clone(); const d = Math.hypot(to.x - h.cx, to.z - h.cz); if (d > 34) to.set(h.cx + (to.x - h.cx) / d * 34, to.y, h.cz + (to.z - h.cz) / d * 34); return { pos: to, want: 4.4, arrive: 7, tool: s.role === 'shooter' && s.ammo > 0 ? TOOL.gun : TOOL.bat, doing: 'Defending the town' }; }
    // ---- sick or hurt
    const clinic = this.buildings('lab')[0];
    if (s.infect > 0.75) return { pos: jitter(front(clinic ?? home ?? h, 1.2), 1.5), want: 1.4, act: ACT.lie, doing: 'Delirious with fever' };
    if (s.infect > 0.35 || s.hp < maxHp(p) * 0.4) {
      if (clinic && this.townsfolk().some(o => o.job === 'medic' && o !== s)) return { pos: jitter(front(clinic, 1.2), 1.5), want: 1.6, act: ACT.sit, doing: 'Waiting for the medic' };
      return goHome(s.infect > 0.35 ? 'In bed with fever' : 'Resting up');
    }
    // ---- sleep
    if (this.asleep(s, hours)) return goHome('Sleeping');
    const mess = this.buildings('mess_hall')[0];
    const commons = () => { const m = mess ?? h; return centre(m).add(front(m).sub(centre(m)).multiplyScalar(1.35)); };
    // ---- meals and time off
    if (hours >= 18 && hours < 19.3) return { pos: jitter(commons(), 3.2), want: 2.2, act: (s.id + Math.floor(hours * 5)) % 3 ? ACT.eat : ACT.sit, social: true, doing: 'Having dinner', arrive: 1.2 };
    if (hours >= 19.3 || hours < p.wake) {
      if (this.raining > 0.5) return goHome('Sheltering from the rain');
      if (has(p, 'loner')) return { pos: jitter(home ? front(home, 1.6) : front(h, 3), 1.5, 3), want: 1.4, act: ACT.sit, doing: 'Sitting alone' };
      const fire = this.base.pieces.find(pc => pc.fire?.lit && this.near(pc.cx, pc.cz, 45));
      const spot = fire ? new THREE.Vector3(fire.cx, fire.y, fire.cz) : commons();
      return { pos: jitter(spot, fire ? 2.2 : 3.5, 5), want: 1.6, act: (s.id + Math.floor(hours * 3)) % 2 ? ACT.sit : ACT.none, social: true, doing: fire ? 'Sitting by the fire' : 'Relaxing', arrive: 1.2, face: faceTo(s.pos, spot) };
    }
    const lunchEnd = has(p, 'lazy') ? 13.4 : has(p, 'hardworker') ? 12.3 : 12.75;
    if (hours >= 12 && hours < lunchEnd) return { pos: jitter(commons(), 3.5, 9), want: 1.8, act: ACT.none, social: true, doing: 'On a break', arrive: 1.2 };
    // ---- work
    const now = this.clock;
    switch (s.job) {
      case 'farmer': {
        const plots = this.base.pieces.filter(pc => pc.farm && this.near(pc.cx, pc.cz));
        if (!plots.length) break;
        const pc = plots[(Math.floor(now / 18) + s.id * 3) % plots.length];
        const ripe = pc.farm!.crop && pc.farm!.growth >= 1;
        return { pos: new THREE.Vector3(pc.cx + 1.15, pc.y, pc.cz), want: 1.7, face: -Math.PI / 2, act: ACT.tend, tool: TOOL.hoe, doing: ripe ? 'Harvesting' : pc.farm!.crop ? 'Tending the crops' : 'Sowing', arrive: 0.5 };
      }
      case 'lumberjack': {
        if (!work) break;
        const yard = front(work, 0.9);
        let t = s.task && s.task.kind === 'chop' ? s.task : null;
        if (!t) {
          const a = ((s.id * 1.7 + Math.floor(now / 90)) % 6.283), r = 12 + (s.id * 5) % 14;
          // a real tree: one near this trip's bearing, else the nearest standing anywhere in reach; with no timber
          // left to fell they stay at the yard and work through the log pile
          const tr = this.world.veg.nearest(work.cx + Math.cos(a) * r, work.cz + Math.sin(a) * r, 26, ['tree']) ?? this.world.veg.nearest(work.cx, work.cz, 75, ['tree']);
          if (!tr) t = s.task = { kind: 'chop', x: yard.x, z: yard.z, t: now + 40, phase: 3, ref: 0 };
          else {
            const dx = yard.x - tr.x, dz = yard.z - tr.z, d = Math.hypot(dx, dz) || 1;
            t = s.task = { kind: 'chop', x: tr.x + dx / d * 1.3, z: tr.z + dz / d * 1.3, t: 0, phase: 0, ref: 1 };
          }
        }
        const tree = new THREE.Vector3(t.x, s.pos.y, t.z);
        if (t.phase === 0) { if (Math.hypot(s.pos.x - t.x, s.pos.z - t.z) < 1.2) { t.phase = 1; t.t = now + 26; } return { pos: tree, want: 2, tool: TOOL.axe, doing: 'Walking to the timber', arrive: 0.8 }; }
        if (t.phase === 1) { if (now > t.t) t.phase = 2; return { pos: tree, want: 2, act: ACT.swing, tool: TOOL.axe, doing: 'Felling timber', arrive: 1.4, face: faceTo(s.pos, new THREE.Vector3(t.x - (yard.x - t.x), 0, t.z - (yard.z - t.z))) }; }
        if (t.phase === 2) { if (Math.hypot(s.pos.x - yard.x, s.pos.z - yard.z) < 1.6) { t.phase = 3; t.t = now + 12; } return { pos: yard, want: 1.5, act: ACT.carry, doing: 'Hauling logs to the yard', arrive: 1 }; }
        if (now > t.t) s.task = null;
        return { pos: yard, want: 1.5, act: ACT.swing, tool: TOOL.axe, doing: 'Sawing planks', face: faceTo(yard, centre(work)), arrive: 1.2 };
      }
      case 'cook': if (work) { const f = jitter(front(work, 0.7), 0.6); return { pos: f, want: 1.8, act: ACT.stir, tool: TOOL.ladle, face: faceTo(f, centre(work)), doing: 'Cooking', arrive: 0.7 }; } break;
      case 'mechanic': {
        const tgt = this.repairAt;
        if (tgt && work) return { pos: tgt, want: 2.2, act: ACT.swing, tool: TOOL.wrench, doing: 'Mending ' + this.repairWhat, arrive: 1.7, face: faceTo(s.pos, tgt) };
        if (work) { const f = jitter(front(work, 0.7), 0.8); return { pos: f, want: 1.8, act: Math.floor(now / 25 + s.id) % 2 ? ACT.swing : ACT.none, tool: TOOL.wrench, face: faceTo(f, centre(work)), doing: 'Tinkering at the bench', arrive: 0.8 }; }
        break;
      }
      case 'medic': {
        const pat = this.patient(s);
        if (pat && !pat.hidden) return { pos: pat.pos.clone(), want: 2.6, act: ACT.tend, tool: TOOL.medbag, doing: `Treating ${pat.name}`, arrive: 1.3, face: faceTo(s.pos, pat.pos) };
        if (work) { const f = jitter(front(work, 0.7), 0.8); return { pos: f, want: 1.8, tool: TOOL.medbag, face: faceTo(f, centre(work)) + Math.PI, doing: 'Minding the clinic', arrive: 0.8 }; }
        break;
      }
    }
    // ---- odd jobs: haul between the hall and the stores, sweep up, look busy
    const stores = this.factory.machines.filter(m => (m.type === 'storage' || m.type === 'mess_hall' || m.type === 'lumber_yard') && this.near(m.cx, m.cz, 50));
    const cyc = Math.floor(now / 40 + s.id * 0.37);
    if (stores.length && cyc % 3 !== 2 && !has(p, 'lazy')) {
      const a = front(h, 1), b = front(stores[(cyc + s.id) % stores.length], 0.9);
      let t = s.task && s.task.kind === 'haul' && s.task.ref === cyc ? s.task : null;
      if (!t) t = s.task = { kind: 'haul', x: b.x, z: b.z, t: 0, phase: 0, ref: cyc };
      if (t.phase === 0) { if (Math.hypot(s.pos.x - a.x, s.pos.z - a.z) < 1.6) t.phase = 1; return { pos: a, want: 1.7, doing: 'Fetching a load', arrive: 1 }; }
      if (t.phase === 1 && Math.hypot(s.pos.x - b.x, s.pos.z - b.z) < 1.6) t.phase = 2;
      if (t.phase === 1) return { pos: b, want: 1.4, act: ACT.carry, doing: 'Hauling supplies', arrive: 1 };
      return { pos: b, want: 1.4, act: ACT.tend, doing: 'Stacking the stores', arrive: 1.2 };
    }
    const spot = jitter(front(h, 3), 7, cyc);
    return { pos: spot, want: 1.3, act: cyc % 2 ? ACT.sit : ACT.none, social: true, doing: s.job === 'idle' ? 'Looking for something to do' : 'Waiting for work', arrive: 1 };
  }
  /** who most needs the medic */
  private patient(medic: Survivor) {
    let best: Survivor | null = null, bv = 0;
    for (const o of this.townsfolk()) { if (o === medic || o.dead || o.away > 0) continue; const v = (o.infect > 0 ? 1 + o.infect : 0) + Math.max(0, 0.7 - o.hp / maxHp(o.p)); if (v > bv && Math.hypot(o.pos.x - medic.pos.x, o.pos.z - medic.pos.z) < 70) { bv = v; best = o; } }
    return bv > 0.05 ? best : null;
  }

  // ------------------------------------------------------------------ simulation
  private clock = 0; private raining = 0; private now = 0;
  private alarmPos = new THREE.Vector3();
  private repairAt: THREE.Vector3 | null = null; private repairWhat = 'a wall';
  update(dtMin: number, ctx: { hours: number; day: number; raining: number; radio: boolean; lit: boolean; now?: number }) {
    if (!this.hub()) return;
    this.clock += dtMin; this.raining = ctx.raining; this.now = ctx.now ?? this.clock;
    this.attackT = Math.max(0, this.attackT - dtMin);
    this.tick += dtMin;
    if (this.tick >= 30) { const n = Math.floor(this.tick / 30); this.tick -= n * 30; for (let i = 0; i < Math.min(n, 4); i++) this.halfHour(ctx); }
    // the day's needs at 18:00 and newcomers at 09:00
    if (ctx.hours >= 18 && ctx.day > this.lastNeedsDay) { this.lastNeedsDay = ctx.day; this.dailyNeeds(ctx); }
    if (ctx.hours >= 9 && ctx.hours < 18 && ctx.day > this.lastArrivalDay) { this.lastArrivalDay = ctx.day; this.arrivals(ctx); }
  }
  /** the dead are inside town */
  alarm(at?: THREE.Vector3) { if (this.attackT <= 0) audio.play('zombie_alert', { volume: 0.25 }); if (at) this.alarmPos.copy(at); else { const h = this.hub(); if (h && this.attackT <= 0) this.alarmPos.set(h.cx, h.y, h.cz); } this.attackT = 40; }
  get underAttack() { return this.attackT > 0; }

  private halfHour(ctx: { hours: number; raining: number; lit: boolean; day: number }) {
    this.assign();
    const folk = this.townsfolk();
    const note = { notify: this.notify };
    // water tower: rain and pipes fill it
    for (const t of this.buildings('water_tower')) {
      const tw = t as any; tw.water = Math.min(400, (tw.water ?? 0) + ctx.raining * 25);
      const got = this.factory.takeFluid(t, 'f_water', Math.min(30, 400 - tw.water)); tw.water += got;
    }
    this.findRepair();
    for (const s of folk) {
      if (s.away > 0 || s.dead) continue;
      s.p.daysHere = Math.max(0, ctx.day - s.p.joinedDay);
      if (this.asleep(s, ctx.hours) || s.infect > 0.35 || s.mode !== 'routine' || this.attackT > 0) continue;
      const lunch = ctx.hours >= 12 && ctx.hours < 12.5, evening = ctx.hours >= 18;
      if ((lunch || evening) && s.job !== 'guard') continue;
      const w = s.work >= 0 ? this.byId(s.work) : null;
      const sk = JOB_SKILL[s.job];
      const rate = workRate(s.p, sk, s.happy);
      if (w && Math.hypot(s.pos.x - w.cx, s.pos.z - w.cz) > 34 && s.job !== 'mechanic' && s.job !== 'medic') continue; // not at work yet
      // how the job sits with them
      if (s.job !== 'idle') {
        if (s.p.loves.includes(sk)) think(s.p, this.now, 'calling', `Enjoys the work (${JOB_INFO[s.job].name.toLowerCase()})`, 5, 3);
        if (s.p.hates === s.job) think(s.p, this.now, 'hatesjob', `Resents being made ${JOB_INFO[s.job].name.toLowerCase()}`, -7, 3);
      } else if (has(s.p, 'hardworker')) think(s.p, this.now, 'bored', 'Wants proper work to do', -3, 3);
      switch (s.job) {
        case 'farmer': this.farm(s, note); break;
        case 'cook': if (w) this.cook(s, w, rate, note); break;
        case 'lumberjack': if (w?.inv && w.inv.weight() < w.inv.capacity - 10 && this.rng.chance(Math.min(1, rate))) { this.deposit(this.rng.chance(0.6) ? 'plank' : 'log', this.rng.int(2, 3) + Math.floor(lvl(s.p, 'labour') / 4), w); this.survivors.xp(s, 'labour', 7, note); } break;
        case 'mechanic': if (this.repairAt && Math.hypot(s.pos.x - this.repairAt.x, s.pos.z - this.repairAt.z) < 8 && this.repair(70 * rate)) { s.p.repairs++; this.survivors.xp(s, 'crafting', 7, note); } else this.survivors.xp(s, 'crafting', 1.5, note); break;
        case 'medic': this.medic(s, w, rate, note); break;
        case 'guard': if (w?.inv) { for (const id of ['ammo_9mm', 'ammo_rifle']) { const k = Math.min(36 - s.ammo, w.inv.count(id)); if (k > 0) { w.inv.remove(id, k); s.ammo += k; } } } this.survivors.xp(s, 'shooting', 2.5, note); break;
        case 'idle': if (this.rng.chance(0.08 * rate)) this.deposit(this.rng.pick(['rag', 'stone', 'log', 'nails', 'scrap_metal']), 1, null); this.survivors.xp(s, 'labour', 1.5, note); break;
      }
    }
    // happiness drifts toward what their life adds up to
    const st = this.computeStats();
    const amen = Math.min(12, this.amenities());
    for (const s of folk) {
      const p = s.p;
      let t = 45 + (has(p, 'cheerful') ? 8 : has(p, 'gloomy') ? -8 : 0);
      t += s.home >= 0 ? 8 : -16;
      t -= s.hungry * 14 + s.thirsty * 16;
      t += amen + (ctx.lit ? 3 : -2);
      t += st.defence >= Math.ceil(st.residents / 3) ? 5 : -5;
      if (this.attackT > 0) t -= 6;
      if (s.hp < maxHp(p) * 0.5) t -= 6;
      let friends = 0, rivals = 0; for (const o of folk) { const b = p.bonds[o.id] ?? 0; if (b > 40) friends++; else if (b < -30) rivals++; }
      t += Math.min(8, friends * 3) - Math.min(8, rivals * 3);
      if (has(p, 'sociable') && st.residents < 4) t -= 5;
      if (has(p, 'loner') && st.residents > 12) t -= 6;
      t += moodSum(p);
      s.happy += (Math.max(0, Math.min(100, t)) - s.happy) * 0.07;
      s.gloom = 0;
    }
  }
  private amenities() { let a = 0; const seen = new Set<string>(); for (const m of this.buildings()) { const am = TOWN[m.type].amenity ?? 0; if (am && !seen.has(m.type)) { seen.add(m.type); a += am; } } return a; }

  private farm(s: Survivor, note: { notify: (m: string, k?: string) => void }) {
    const plots = this.base.pieces.filter(pc => pc.farm && this.near(pc.cx, pc.cz));
    const farmers = this.townsfolk().filter(q => q.job === 'farmer');
    const idx = Math.max(0, farmers.indexOf(s));
    const mine = plots.filter((_, i) => i % Math.max(1, farmers.length) === idx).slice(0, this.plotsFor(s));
    const stores = this.stores();
    const sk = lvl(s.p, 'farming'); let xp = 0;
    for (const pc of mine) {
      const f = pc.farm!;
      if (f.water < 0.4) { const tower = this.buildings('water_tower').find(t => ((t as any).water ?? 0) > 5); if (tower) { (tower as any).water -= 5; f.water = 1; } else if (this.takeFrom(stores, id => id === 'dirty_water' || id === 'water_bottle')) f.water = 1; else f.water = Math.max(f.water, 0.45); xp += 1; }
      if (f.crop && f.growth >= 1) {
        const c = CROPS[f.crop]; const n = Math.round(this.rng.int(c?.yield[0] ?? 3, c?.yield[1] ?? 6) * (f.fert > 0 ? 1.5 : 1) * (1 + sk * 0.04 + (has(s.p, 'greenthumb') ? 0.2 : 0)));
        this.deposit(f.crop, n); if (this.rng.chance(Math.min(0.98, (c?.seedBack ?? 0.6) + sk * 0.03))) this.deposit('seeds_' + f.crop, this.rng.int(1, 2));
        f.crop = null; f.growth = 0; f.fert = 0; if (f.plant) { this.base.group.remove(f.plant); f.plant = null; }
        s.p.harvests++; xp += 8;
      }
      if (!f.crop) { const seed = this.takeFrom(stores, id => !!item(id).crop); if (seed) { f.crop = item(seed).crop!; f.growth = 0; xp += 3; } }
      if (f.crop && f.fert <= 0 && this.takeFrom(stores, id => id === 'fertilizer')) f.fert = 3;
    }
    this.survivors.xp(s, 'farming', xp + 1, note);
  }
  private cook(s: Survivor, mess: Machine, rate: number, note: { notify: (m: string, k?: string) => void }) {
    if (!mess.inv) return;
    const inv = mess.inv; const sk = lvl(s.p, 'cooking');
    const tries = Math.floor(rate * 1.5) + (this.rng.chance((rate * 1.5) % 1) ? 1 : 0);
    let made = 0;
    for (let k = 0; k < Math.max(1, tries); k++) {
      // best meal whose ingredients are all in the mess hall (water/bottles allowed from the hall too)
      const r = RECIPES.filter(r => (r.station === 'stove' || r.station === 'campfire') && this.isFood(r.out[0]) && Object.entries(r.inp).every(([id, n]) => inv.count(id) >= n))
        .sort((a, b) => (item(b.out[0]).hunger ?? 0) * b.out[1] - (item(a.out[0]).hunger ?? 0) * a.out[1])[0];
      if (!r) break;
      for (const [id, n] of Object.entries(r.inp)) inv.remove(id, n);
      for (const id of r.keep ?? []) inv.add(id, 1);
      inv.add(r.out[0], r.out[1] + (this.rng.chance(sk * 0.06) ? 1 : 0)); // a good cook stretches it
      const e = item(r.inp && Object.keys(r.inp)[0]).empties; if (e) inv.add(e, 1);
      made++;
    }
    if (made) { s.p.meals += made; this.survivors.xp(s, 'cooking', 9 * made, note); } else this.survivors.xp(s, 'cooking', 1, note);
  }
  /** the most battered thing in town, for the mechanic to walk to */
  private repairRef: { hp: number; maxHp: number } | null = null;
  private findRepair() {
    let best: { hp: number; maxHp: number } | null = null, worst = 0.97; let at: THREE.Vector3 | null = null, what = 'a wall';
    for (const pc of this.base.pieces) if (this.near(pc.cx, pc.cz) && pc.hp / pc.maxHp < worst) { worst = pc.hp / pc.maxHp; best = pc; at = new THREE.Vector3(pc.cx, pc.y, pc.cz); what = `the ${pc.def.name.toLowerCase()}`; }
    for (const m of this.factory.machines) if (this.near(m.cx, m.cz) && m.hp / m.def.hp < worst) { worst = m.hp / m.def.hp; best = { get hp() { return m.hp; }, set hp(v) { m.hp = v; }, maxHp: m.def.hp } as any; const r = Math.max(m.def.size[0], m.def.size[1]) + 0.9; at = new THREE.Vector3(m.cx + m.dir[0] * r, m.y, m.cz + m.dir[1] * r); what = `the ${m.def.name.toLowerCase()}`; }
    this.repairRef = best; this.repairAt = at; this.repairWhat = what;
  }
  private repair(hp: number) {
    const best = this.repairRef; if (!best) return false;
    best.hp = Math.min(best.maxHp, best.hp + hp);
    return true;
  }
  private medic(s: Survivor, clinic: Machine | null, rate: number, note: { notify: (m: string, k?: string) => void }) {
    const sk = lvl(s.p, 'medicine'); let xp = 1;
    for (const o of this.residents()) {
      if (o === s || o.away > 0) continue;
      const mh = maxHp(o.p);
      if (o.hp < mh) { o.hp = Math.min(mh, o.hp + 9 * rate * (1 + sk * 0.12)); xp += 3; if (o.hp >= mh) s.p.healed++; think(o.p, this.now, 'tended', `${s.name} is looking after them`, 3, 6); }
      // the bite: serum cures, antibiotics hold it back
      if (o.infect > 0 && clinic?.inv) {
        if (clinic.inv.count('serum') > 0) { clinic.inv.remove('serum', 1); this.survivors.cure(o); s.p.healed++; xp += 40; this.notify(`🧪 ${s.name} gave ${o.name} the serum in time. The fever has broken.`, 'good'); this.survivors.bond(s, o, 25); }
        else if (o.antibio <= 0 && clinic.inv.count('antibiotics') > 0) { clinic.inv.remove('antibiotics', 1); o.antibio = 18 + sk * 2; xp += 10; this.notify(`💉 ${s.name} is holding back ${o.name}'s fever with antibiotics. It won't cure a bite — only serum will.`, ''); }
      }
    }
    if (s.hp < maxHp(s.p)) s.hp = Math.min(maxHp(s.p), s.hp + 4);
    if (clinic?.inv && clinic.inv.count('rag') >= 2 && this.rng.chance(Math.min(0.9, 0.3 * rate))) { clinic.inv.remove('rag', 2); clinic.inv.add('bandage', 1); xp += 4; }
    this.survivors.xp(s, 'medicine', xp, note);
  }

  private dailyNeeds(ctx: { day: number }) {
    const folk = this.townsfolk(); if (!folk.length) return;
    const stores = this.stores();
    const mess = this.buildings('mess_hall').map(m => m.inv!).filter(Boolean);
    const towers = this.buildings('water_tower');
    const cookLvl = Math.max(0, ...folk.filter(s => s.job === 'cook').map(s => lvl(s.p, 'cooking')));
    let hungry = 0, thirsty = 0;
    for (const s of folk) {
      if (s.away > 0) continue;
      // food: hot meal from the mess hall first
      const skip = has(s.p, 'frugal') && (ctx.day + s.id) % 2 === 0 && s.hungry === 0;
      if (!skip) {
        let ate = this.takeFrom(mess, id => this.isMeal(id));
        s.meal = !!ate;
        if (!ate) ate = this.takeFrom([...mess, ...stores], id => this.isFood(id));
        if (ate) { s.hungry = 0; if (s.meal) think(s.p, this.now, 'meal', cookLvl >= 6 ? 'Ate a superb hot meal' : 'Ate a hot meal', cookLvl >= 6 ? 12 : 8, 22); else think(s.p, this.now, 'meal', 'Cold rations again', -1, 22); }
        else { s.hungry++; hungry++; think(s.p, this.now, 'meal', 'Went to bed hungry', -6, 22); }
        if (ate && has(s.p, 'glutton') && !this.takeFrom([...mess, ...stores], id => this.isFood(id))) think(s.p, this.now, 'seconds', 'Still hungry after dinner', -4, 22);
      }
      // water
      const tw = towers.find(t => ((t as any).water ?? 0) >= 4);
      if (tw) { (tw as any).water -= 4; s.thirsty = 0; }
      else if (this.takeFrom(stores, id => id === 'water_bottle' || id === 'soda')) s.thirsty = 0;
      else { s.thirsty++; thirsty++; }
      if (s.home < 0) think(s.p, this.now, 'nobed', 'Slept on the ground', -4, 22); else if (this.byId(s.home)?.type === 'hub') think(s.p, this.now, 'nobed', 'Slept on the hall floor', -1, 22);
    }
    if (hungry) this.notify(`🍽 ${hungry} resident${hungry > 1 ? 's' : ''} went to bed hungry. Stock food in storage or the mess hall.`, 'bad');
    if (thirsty) this.notify(`💧 ${thirsty} resident${thirsty > 1 ? 's' : ''} had no clean water. Build a water tower or store bottled water.`, 'bad');
    // people leave when it's unbearable
    for (const s of folk) {
      if (s.hungry >= 3 || s.thirsty >= 2 || s.happy < 18) {
        s.recruited = false; s.order = 'wait'; s.home = -1; s.work = -1; s.anchor.copy(s.pos).add(new THREE.Vector3(60, 0, 60));
        this.notify(`🚶 ${s.p.first} ${s.p.last} has left town — ${s.hungry >= 3 ? 'there was no food' : s.thirsty >= 2 ? 'there was no clean water' : 'they were miserable here'}.`, 'bad');
        for (const o of folk) if (o !== s) think(o.p, this.now, 'left' + s.id, `${s.name} gave up and left`, (o.p.bonds[s.id] ?? 0) > 40 ? -8 : -3, 48);
      }
    }
  }

  private arrivals(ctx: { radio: boolean; day: number }) {
    const st = this.computeStats();
    const free = st.beds - st.residents;
    if (free <= 0 || st.residents < 1) return;
    if (st.food < Math.max(4, st.residents * 2)) { this.notify('🏚 Travellers passed by the gates but moved on — your stores look too empty to feed more mouths.', ''); return; }
    if (st.residents > 2 && st.happiness < 40) { this.notify('🏚 Word is your town is a miserable place. Nobody new came today.', ''); return; }
    let n = 1 + (ctx.radio ? 1 : 0) + (st.happiness > 70 ? 1 : 0) + (this.rng.chance(0.3) ? 1 : 0);
    n = Math.min(n, free);
    const h = this.hub()!; const a = this.rng.range(0, Math.PI * 2);
    const who: Survivor[] = [];
    for (let i = 0; i < n; i++) {
      const x = h.cx + Math.cos(a) * 55 + this.rng.range(-3, 3), z = h.cz + Math.sin(a) * 55 + this.rng.range(-3, 3);
      const s = this.survivors.spawn(x, z, true); s.order = 'work'; s.happy = 60; s.p.joinedDay = ctx.day; who.push(s);
      think(s.p, this.now, 'arrived', 'Found people. Found walls', 10, 72);
    }
    // people who walked in together already trust each other
    for (let i = 1; i < who.length; i++) this.survivors.bond(who[0], who[i], 45 + this.rng.range(0, 25));
    if (who.length > 1 && this.rng.chance(0.5)) for (let i = 1; i < who.length; i++) who[i].p.last = who[0].p.last; // family
    this.assign();
    const desc = who.map(s => `${s.p.first} ${s.p.last} (${background(s.p.bg).name.toLowerCase()})`);
    const names = desc.length > 1 ? `${desc.slice(0, -1).join(', ')} and ${desc[desc.length - 1]}` : desc[0];
    this.notify(`👥 Newcomers! ${names} ${this.rng.pick(ARRIVAL_LINES)} and ${n > 1 ? 'have' : 'has'} moved in.`, 'good');
    audio.play('objective');
  }

  computeStats() {
    const folk = this.residents();
    const st = this.stats;
    st.residents = folk.length;
    st.beds = this.buildings().reduce((a, m) => a + (TOWN[m.type].beds ?? 0), 0);
    st.homeless = this.townsfolk().filter(s => s.home < 0).length;
    st.food = this.stores().reduce((a, inv) => a + inv.stacks.reduce((b, s) => b + (this.isFood(s.id) ? s.n : 0), 0), 0);
    st.water = this.buildings('water_tower').reduce((a, t) => a + ((t as any).water ?? 0), 0) / 4 + this.stores().reduce((a, inv) => a + inv.count('water_bottle'), 0);
    st.defence = this.factory.machines.filter(m => this.near(m.cx, m.cz) && (m.type === 'turret' || (m.type === 'guard_post' && this.townsfolk().some(s => s.work === m.id)))).length + this.base.pieces.filter(pc => pc.def.kind === 'tower' && this.near(pc.cx, pc.cz)).length;
    st.power = this.factory.nets.reduce((a, n) => a + n.supply, 0);
    st.happiness = folk.length ? folk.reduce((a, s) => a + s.happy, 0) / folk.length : 0;
    st.jobs = {}; for (const s of this.townsfolk()) st.jobs[s.job] = (st.jobs[s.job] ?? 0) + 1;
    return st;
  }
  /** settlement-level requirements still missing */
  missing(req: LevelReq | undefined): string[] {
    if (!req) return [];
    const st = this.computeStats(); const out: string[] = [];
    if (req.residents && st.residents < req.residents) out.push(`${req.residents} residents (${st.residents})`);
    if (req.beds && st.beds < req.beds) out.push(`${req.beds} beds (${st.beds})`);
    if (req.food && st.food < req.food) out.push(`${req.food} food in stores (${st.food})`);
    if (req.water && st.water < req.water) out.push(`${req.water} days of water (${Math.floor(st.water)})`);
    if (req.defence && st.defence < req.defence) out.push(`${req.defence} defences (${st.defence})`);
    if (req.power && st.power < req.power) out.push(`${req.power} MW power (${Math.round(st.power)})`);
    if (req.happiness && st.happiness < req.happiness) out.push(`${req.happiness}% happiness (${Math.round(st.happiness)}%)`);
    if (req.grid && !this.factory.townPowered) out.push('the town grid restored');
    return out;
  }

  toJSON() { return { la: this.lastArrivalDay, ln: this.lastNeedsDay, water: this.buildings('water_tower').map(t => [t.cx, t.cz, (t as any).water ?? 0]) }; }
  load(o: any) {
    if (!o) return;
    this.lastArrivalDay = o.la ?? -1; this.lastNeedsDay = o.ln ?? -1;
    for (const [x, z, w] of o.water ?? []) { const t = this.buildings('water_tower').find(m => Math.abs(m.cx - x) < 0.1 && Math.abs(m.cz - z) < 0.1); if (t) (t as any).water = w; }
  }
}
export type { Piece };
