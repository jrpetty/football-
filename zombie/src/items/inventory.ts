import { item } from './items';

export interface Stack { uid: number; id: string; n: number; dur?: number; ammo?: number; age?: number; }
let UID = 1;
export function newUid() { return UID++; }
export function bumpUid(min: number) { if (UID <= min) UID = min + 1; }

export function makeStack(id: string, n = 1): Stack {
  const d = item(id);
  const s: Stack = { uid: newUid(), id, n };
  if (d.weapon) { s.dur = d.weapon.durability; if (d.weapon.ammo) s.ammo = 0; }
  if (d.spoilDays) s.age = 0;
  if (d.light) s.dur = 100; // battery charge
  return s;
}

export class Inventory {
  stacks: Stack[] = [];
  constructor(public capacity = Infinity) {}

  weight() { let w = 0; for (const s of this.stacks) w += item(s.id).weight * s.n; return w; }
  count(id: string) { let c = 0; for (const s of this.stacks) if (s.id === id) c += s.n; return c; }
  find(id: string) { return this.stacks.find(s => s.id === id); }
  byUid(uid: number) { return this.stacks.find(s => s.uid === uid); }
  hasTool(tag: string) { return this.stacks.some(s => item(s.id).tools?.includes(tag)); }

  /** Adds items; returns number that did not fit. */
  add(id: string, n = 1, proto?: Partial<Stack>): number {
    const d = item(id);
    if (this.capacity !== Infinity) {
      const free = this.capacity - this.weight();
      const fit = Math.max(0, Math.floor(free / Math.max(0.001, d.weight)));
      if (fit < n) { const left = n - fit; if (fit > 0) this.add(id, fit, proto); return left; }
    }
    let left = n;
    if (d.stack > 1 && !d.weapon && !d.light) {
      for (const s of this.stacks) {
        if (s.id !== id || s.n >= d.stack) continue;
        // only merge perishables of similar age
        if (d.spoilDays && Math.abs((s.age ?? 0) - (proto?.age ?? 0)) > 0.5) continue;
        const k = Math.min(left, d.stack - s.n); s.n += k; left -= k;
        if (!left) return 0;
      }
    }
    while (left > 0) {
      const k = Math.min(left, d.stack);
      const s = makeStack(id, k);
      if (proto) { if (proto.dur !== undefined) s.dur = proto.dur; if (proto.ammo !== undefined) s.ammo = proto.ammo; if (proto.age !== undefined) s.age = proto.age; }
      this.stacks.push(s); left -= k;
    }
    return 0;
  }
  addStack(st: Stack): boolean {
    const d = item(st.id);
    if (this.capacity !== Infinity && this.weight() + d.weight * st.n > this.capacity + 1e-6) return false;
    if (d.stack > 1 && !d.weapon && !d.light) { const left = this.add(st.id, st.n, st); return left === 0; }
    this.stacks.push(st); return true;
  }
  remove(id: string, n = 1): boolean {
    if (this.count(id) < n) return false;
    let left = n;
    for (let i = this.stacks.length - 1; i >= 0 && left > 0; i--) {
      const s = this.stacks[i]; if (s.id !== id) continue;
      const k = Math.min(left, s.n); s.n -= k; left -= k;
      if (s.n <= 0) this.stacks.splice(i, 1);
    }
    return true;
  }
  removeUid(uid: number, n = 1): Stack | null {
    const i = this.stacks.findIndex(s => s.uid === uid); if (i < 0) return null;
    const s = this.stacks[i];
    if (n >= s.n) { this.stacks.splice(i, 1); return s; }
    s.n -= n; const c = { ...s, uid: newUid(), n }; return c;
  }
  hasAll(cost: Record<string, number>) { for (const k in cost) if (this.count(k) < cost[k]) return false; return true; }
  takeAll(cost: Record<string, number>) { if (!this.hasAll(cost)) return false; for (const k in cost) this.remove(k, cost[k]); return true; }
  sort() {
    const order = ['weapon', 'tool', 'ammo', 'medical', 'food', 'drink', 'clothing', 'material', 'resource', 'part', 'seed', 'book', 'misc'];
    this.stacks.sort((a, b) => order.indexOf(item(a.id).cat) - order.indexOf(item(b.id).cat) || item(a.id).name.localeCompare(item(b.id).name));
  }
  toJSON() { return this.stacks; }
  load(st: Stack[]) { this.stacks = st.map(s => ({ ...s })); for (const s of this.stacks) bumpUid(s.uid); }
}
