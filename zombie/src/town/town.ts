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
  farmer: { name: 'Farmer', icon: '🌾', desc: 'Tends up to 6 farm plots: waters, harvests and replants. Crops go to storage.' },
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
      const h = homes.filter(m => m.type !== 'hub' || homes.length === 1).find(m => (used.get(m.id) ?? 0) < TOWN[m.type].beds!) ?? homes.find(m => (used.get(m.id) ?? 0) < TOWN[m.type].beds!);
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
    // fill open workplaces with idle residents (unless the player pinned their job)
    const markStaff = () => { for (const m of this.buildings()) (m as any).staffed = folk.some(s => s.work === m.id || (TOWN[m.type].beds && s.home === m.id)); };
    const plots = this.base.pieces.filter(pc => pc.farm && this.near(pc.cx, pc.cz)).length;
    const wantFarmers = Math.ceil(plots / 6);
    let farmers = folk.filter(s => s.job === 'farmer').length;
    for (const s of folk) {
      if (s.job !== 'idle' || s.jobLock) continue;
      const order: Job[] = ['guard', 'cook', 'medic', 'lumberjack', 'mechanic'];
      if (farmers < wantFarmers) { s.job = 'farmer'; farmers++; continue; }
      for (const j of order) {
        const w = this.buildings().find(m => TOWN[m.type].job === j && (slots.get(m.id) ?? 0) < (TOWN[m.type].slots ?? 1));
        if (w) { s.job = j; s.work = w.id; slots.set(w.id, (slots.get(w.id) ?? 0) + 1); break; }
      }
    }
    markStaff();
  }
  setJob(s: Survivor, j: Job) { s.job = j; s.work = -1; s.jobLock = j !== 'idle'; if (s.order !== 'work') s.order = 'work'; this.assign(); }

  // ------------------------------------------------------------------ daily routine → movement goals
  goal(s: Survivor, hours: number): TownGoal | null {
    const h = this.hub(); if (!h) return null;
    const home = s.home >= 0 ? this.byId(s.home) : null;
    const work = s.work >= 0 ? this.byId(s.work) : null;
    const front = (m: Machine, d = 0.6) => { const W = (m.rot % 2 ? m.def.size[1] : m.def.size[0]) * 2, D = (m.rot % 2 ? m.def.size[0] : m.def.size[1]) * 2; const [fx, fz] = m.dir; return new THREE.Vector3(m.cx + fx * (Math.abs(fx) ? W / 2 + d : 0), m.y, m.cz + fz * (Math.abs(fz) ? D / 2 + d : 0)); };
    const jitter = (v: THREE.Vector3, r: number) => { const a = (s.id * 2.39996) % 6.283, k = ((s.id * 7) % 10) / 10 * r; return v.clone().add(new THREE.Vector3(Math.cos(a) * k, 0, Math.sin(a) * k)); };
    const night = hours >= 22 || hours < 6;
    if (this.attackT > 0 && s.job !== 'guard') { /* under attack: shelter at home or rally at the hall */ if (home) return { pos: front(home), want: 5, hide: s.pos.distanceTo(front(home)) < 2 }; return { pos: jitter(front(h), 3), want: 5 }; }
    if (night && s.job !== 'guard') { if (home) { const p = front(home); return s.pos.distanceTo(p) < 2 ? { pos: new THREE.Vector3(home.cx, home.y, home.cz), want: 0, hide: true } : { pos: p, want: 2.4 }; } return { pos: jitter(front(h, 2), 4), want: 1.6 }; }
    if (hours >= 18) { const mess = this.buildings('mess_hall')[0]; const m = mess ?? h; return { pos: jitter(new THREE.Vector3(m.cx, m.y, m.cz).add(front(m).sub(new THREE.Vector3(m.cx, m.y, m.cz)).multiplyScalar(1.3)), 3), want: 2.2 }; }
    if (s.job === 'guard' && work) { const top = (work.obj?.userData.standY as number | undefined) ?? 2.2; return { pos: new THREE.Vector3(work.cx, work.y + top, work.cz), want: 3, perch: true, face: Math.atan2(work.cx - h.cx, work.cz - h.cz) }; }
    if (s.job === 'farmer') {
      const plots = this.base.pieces.filter(pc => pc.farm && this.near(pc.cx, pc.cz));
      if (plots.length) { const i = Math.floor(hours * 2 + s.id) % plots.length; const pc = plots[i]; return { pos: new THREE.Vector3(pc.cx + 1.1, pc.y, pc.cz), want: 1.6, face: -Math.PI / 2 }; }
    }
    if (work) return { pos: jitter(front(work), 1.2), want: 1.8, face: Math.atan2(work.cx - front(work).x, work.cz - front(work).z) };
    return { pos: jitter(front(h, 3), 6), want: 1.4 };
  }

  // ------------------------------------------------------------------ simulation
  update(dtMin: number, ctx: { hours: number; day: number; raining: number; radio: boolean; lit: boolean }) {
    if (!this.hub()) return;
    this.attackT = Math.max(0, this.attackT - dtMin);
    this.tick += dtMin;
    if (this.tick >= 30) { const n = Math.floor(this.tick / 30); this.tick -= n * 30; for (let i = 0; i < Math.min(n, 4); i++) this.halfHour(ctx); }
    // the day's needs at 18:00 and newcomers at 09:00
    if (ctx.hours >= 18 && ctx.day > this.lastNeedsDay) { this.lastNeedsDay = ctx.day; this.dailyNeeds(ctx); }
    if (ctx.hours >= 9 && ctx.hours < 18 && ctx.day > this.lastArrivalDay) { this.lastArrivalDay = ctx.day; this.arrivals(ctx); }
  }
  /** zombies hit something inside town */
  alarm() { if (this.attackT <= 0) audio.play('zombie_alert', { volume: 0.25 }); this.attackT = 40; }

  private halfHour(ctx: { hours: number; raining: number; lit: boolean }) {
    this.assign();
    const folk = this.townsfolk();
    const day = ctx.hours >= 7 && ctx.hours < 18;
    // water tower: rain and pipes fill it
    for (const t of this.buildings('water_tower')) {
      const tw = t as any; tw.water = Math.min(400, (tw.water ?? 0) + ctx.raining * 25);
      const got = this.factory.takeFluid(t, 'f_water', Math.min(30, 400 - tw.water)); tw.water += got;
    }
    if (day) for (const s of folk) {
      const w = s.work >= 0 ? this.byId(s.work) : null;
      const eff = 0.6 + s.happy / 250; // happy people work harder
      if (s.pos.distanceTo(w ? new THREE.Vector3(w.cx, s.pos.y, w.cz) : s.pos) > 9 && s.job !== 'farmer') continue; // not there yet
      switch (s.job) {
        case 'farmer': this.farm(s); break;
        case 'cook': if (w) this.cook(w, eff); break;
        case 'lumberjack': if (w?.inv && w.inv.weight() < w.inv.capacity - 10 && this.rng.chance(eff)) this.deposit(this.rng.chance(0.6) ? 'plank' : 'log', this.rng.int(2, 3), w); break;
        case 'mechanic': this.repair(80 * eff); break;
        case 'medic': this.medic(w, eff); break;
        case 'guard': if (w?.inv) { const ids = ['ammo_9mm', 'ammo_rifle']; for (const id of ids) { const k = Math.min(30 - s.ammo, w.inv.count(id)); if (k > 0) { w.inv.remove(id, k); s.ammo += k; } } } break;
        case 'idle': if (this.rng.chance(0.08)) { const id = this.rng.pick(['rag', 'stone', 'log', 'nails', 'scrap_metal']); this.deposit(id, 1, null); } break;
      }
    }
    // happiness drifts toward its target
    const st = this.computeStats();
    for (const s of folk) {
      let t = 50;
      t += s.home >= 0 ? 12 : -18;
      t += s.meal ? 10 : 0;
      t -= s.hungry * 14 + s.thirsty * 16;
      t += Math.min(18, this.amenities());
      if (ctx.lit) t += 4;
      if (st.defence >= Math.ceil(st.residents / 3)) t += 6; else t -= 6;
      t -= s.gloom;
      if (this.attackT > 0) t -= 8;
      if (s.hp < 50) t -= 8;
      s.happy += (Math.max(0, Math.min(100, t)) - s.happy) * 0.06;
      s.gloom = Math.max(0, s.gloom - 0.4);
    }
  }
  private amenities() { let a = 0; const seen = new Set<string>(); for (const m of this.buildings()) { const am = TOWN[m.type].amenity ?? 0; if (am && !seen.has(m.type)) { seen.add(m.type); a += am; } } return a; }

  private farm(s: Survivor) {
    const plots = this.base.pieces.filter(pc => pc.farm && this.near(pc.cx, pc.cz));
    const mine = plots.filter((_, i) => i % Math.max(1, this.townsfolk().filter(q => q.job === 'farmer').length) === this.townsfolk().filter(q => q.job === 'farmer').indexOf(s) % Math.max(1, plots.length)).slice(0, 6);
    const stores = this.stores();
    for (const pc of mine.length ? mine : plots.slice(0, 6)) {
      const f = pc.farm!;
      if (f.water < 0.4) { const tower = this.buildings('water_tower').find(t => ((t as any).water ?? 0) > 5); if (tower) { (tower as any).water -= 5; f.water = 1; } else if (this.takeFrom(stores, id => id === 'dirty_water' || id === 'water_bottle')) f.water = 1; else f.water = Math.max(f.water, 0.45); }
      if (f.crop && f.growth >= 1) {
        const c = CROPS[f.crop]; const n = Math.round(this.rng.int(c?.yield[0] ?? 3, c?.yield[1] ?? 6) * (f.fert > 0 ? 1.5 : 1));
        this.deposit(f.crop, n); if (this.rng.chance(c?.seedBack ?? 0.6)) this.deposit('seeds_' + f.crop, this.rng.int(1, 2));
        const crop = f.crop; f.crop = null; f.growth = 0; f.fert = 0; if (f.plant) { this.base.group.remove(f.plant); f.plant = null; }
        void crop;
      }
      if (!f.crop) { const seed = this.takeFrom(stores, id => !!item(id).crop); if (seed) { f.crop = item(seed).crop!; f.growth = 0; } }
      if (f.crop && f.fert <= 0 && this.takeFrom(stores, id => id === 'fertilizer')) f.fert = 3;
    }
  }
  private cook(mess: Machine, eff: number) {
    if (!mess.inv) return;
    const inv = mess.inv;
    const tries = this.rng.chance(eff) ? 2 : 1;
    for (let k = 0; k < tries; k++) {
      // best meal whose ingredients are all in the mess hall (water/bottles allowed from the hall too)
      const r = RECIPES.filter(r => (r.station === 'stove' || r.station === 'campfire') && this.isFood(r.out[0]) && Object.entries(r.inp).every(([id, n]) => inv.count(id) >= n))
        .sort((a, b) => (item(b.out[0]).hunger ?? 0) * b.out[1] - (item(a.out[0]).hunger ?? 0) * a.out[1])[0];
      if (!r) return;
      for (const [id, n] of Object.entries(r.inp)) inv.remove(id, n);
      for (const id of r.keep ?? []) inv.add(id, 1);
      inv.add(r.out[0], r.out[1]);
      const e = item(r.inp && Object.keys(r.inp)[0]).empties; if (e) inv.add(e, 1);
    }
  }
  private repair(hp: number) {
    let best: { hp: number; maxHp: number } | null = null, worst = 0.98;
    for (const pc of this.base.pieces) if (this.near(pc.cx, pc.cz) && pc.hp / pc.maxHp < worst) { worst = pc.hp / pc.maxHp; best = pc; }
    for (const m of this.factory.machines) if (this.near(m.cx, m.cz) && m.hp / m.def.hp < worst) { worst = m.hp / m.def.hp; best = { get hp() { return m.hp; }, set hp(v) { m.hp = v; }, maxHp: m.def.hp } as any; }
    if (best) best.hp = Math.min(best.maxHp, best.hp + hp);
  }
  private medic(clinic: Machine | null, eff: number) {
    for (const s of this.residents()) if (s.hp < 100) s.hp = Math.min(100, s.hp + 12 * eff);
    if (clinic?.inv && clinic.inv.count('rag') >= 2 && this.rng.chance(0.3 * eff)) { clinic.inv.remove('rag', 2); clinic.inv.add('bandage', 1); }
  }

  private dailyNeeds(ctx: { day: number }) {
    const folk = this.townsfolk(); if (!folk.length) return;
    const stores = this.stores();
    const mess = this.buildings('mess_hall').map(m => m.inv!).filter(Boolean);
    const towers = this.buildings('water_tower');
    let hungry = 0, thirsty = 0;
    for (const s of folk) {
      // food: hot meal from the mess hall first
      let ate = this.takeFrom(mess, id => this.isMeal(id));
      s.meal = !!ate;
      if (!ate) ate = this.takeFrom([...mess, ...stores], id => this.isFood(id));
      if (ate) s.hungry = 0; else { s.hungry++; hungry++; }
      // water
      const tw = towers.find(t => ((t as any).water ?? 0) >= 4);
      if (tw) { (tw as any).water -= 4; s.thirsty = 0; }
      else if (this.takeFrom(stores, id => id === 'water_bottle' || id === 'soda')) s.thirsty = 0;
      else { s.thirsty++; thirsty++; }
    }
    if (hungry) this.notify(`🍽 ${hungry} resident${hungry > 1 ? 's' : ''} went to bed hungry. Stock food in storage or the mess hall.`, 'bad');
    if (thirsty) this.notify(`💧 ${thirsty} resident${thirsty > 1 ? 's' : ''} had no clean water. Build a water tower or store bottled water.`, 'bad');
    // people leave when it's unbearable
    for (const s of folk) {
      if (s.hungry >= 3 || s.thirsty >= 2 || s.happy < 18) {
        s.recruited = false; s.order = 'wait'; s.home = -1; s.work = -1; s.anchor.copy(s.pos).add(new THREE.Vector3(60, 0, 60));
        this.notify(`🚶 ${s.name} has left town — ${s.hungry >= 3 ? 'there was no food' : s.thirsty >= 2 ? 'there was no clean water' : 'they were miserable here'}.`, 'bad');
        for (const o of folk) o.gloom += 4;
      }
    }
    void ctx;
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
    const names: string[] = [];
    for (let i = 0; i < n; i++) {
      const x = h.cx + Math.cos(a) * 55 + this.rng.range(-3, 3), z = h.cz + Math.sin(a) * 55 + this.rng.range(-3, 3);
      const s = this.survivors.spawn(x, z, true); s.order = 'work'; s.happy = 60; names.push(s.name);
    }
    this.assign();
    const who = names.length > 1 ? `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}` : names[0];
    this.notify(`👥 Newcomers! ${who} ${this.rng.pick(ARRIVAL_LINES)} and ${n > 1 ? 'have' : 'has'} moved in.`, 'good');
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
