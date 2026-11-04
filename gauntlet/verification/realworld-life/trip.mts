/**
 * Weekend Away (life.trip-planner): world generator + exact solver.
 *
 * The solver is written independently of the app's checker (src/scoring/verifiers/trip.ts): it enumerates every
 * itinerary that the timetable allows (depth-first over services and transfers, earliest timing), then every
 * combination of hotel nights, and prices and checks each one from the rules as stated in the prompt. The cheapest
 * workable plan is the proven optimum. `relax` switches single rules off to find what a careless planner would
 * pick (used to prove that each trap bites, and for the scripted "careless" answers in the spread report).
 */
import { Rng, hhmm, mins, money, pounds, ONE_ANSWER } from './common.mts';
import type { TripHotel, TripMode, TripService, TripTransfer, TripWorld } from '../../src/scoring/verifiers/trip.ts';

export type Relax = Partial<Record<'connections' | 'railcardTimes' | 'checkIn' | 'flightTimes' | 'minStay' | 'expiry', boolean>>;

type Step = { kind: 'service'; s: TripService } | { kind: 'transfer'; t: TripTransfer };

export interface TripPlan {
  steps: Step[];
  /** Hotel id per night (null = none). */
  hotels: Array<string | null>;
  cost: number;
}

const DAY = 1440;
const CHECK = 180;

// ───────────────────────────── Rules (solver side) ─────────────────────────────

function fareOf(w: TripWorld, s: TripService, relax: Relax): number {
  const rc = w.railcard;
  if (!rc || !rc.modes.includes(s.mode)) return s.fare + (s.supplement ?? 0);
  const day = Math.floor(s.dep / DAY);
  const early = w.weekdays.includes(day) && s.dep % DAY < rc.weekdayNotBefore;
  const expired = rc.validBefore !== undefined && s.dep >= rc.validBefore;
  if ((early && !relax.railcardTimes) || (expired && !relax.expiry)) return s.fare + (s.supplement ?? 0);
  return Math.round((s.fare * 2) / 3) + (s.supplement ?? 0);
}

/** Earliest time a service can be boarded, given how and when you reached its station. */
function boardFrom(w: TripWorld, arrivedAt: number, how: 'home' | 'service' | 'flight' | 'transfer', s: TripService, relax: Relax): number {
  const gaps = [0];
  if ((how === 'service' || how === 'flight') && !relax.connections) gaps.push(w.nodes[s.from]!.minConnect);
  if (how === 'flight' && !relax.flightTimes) gaps.push(w.flightExit);
  if (s.mode === 'flight' && !relax.flightTimes) gaps.push(w.flightCheckIn);
  return arrivedAt + Math.max(...gaps);
}

interface Seg {
  where: string; // a node, or "ride:<id>"
  from: number;
  to: number;
  overnight?: boolean;
}

/** Check and price one plan. Returns null when a rule is broken. */
export function evaluate(w: TripWorld, steps: Step[], hotels: Array<string | null>, relax: Relax = {}): number | null {
  const nightOf = (h: string) => w.hotels.find((x) => x.id === h)!;
  // Latest start for the transfer chain beginning at step i (backwards from the next timed thing).
  const latestStart = (i: number, after: number): number | null => {
    let need = 0;
    for (let j = i; j < steps.length; j++) {
      const st = steps[j]!;
      if (st.kind === 'service') return st.s.dep - (st.s.mode === 'flight' && !relax.flightTimes ? w.flightCheckIn : 0) - need;
      need += st.t.mins;
      if (st.t.to === w.event.node && w.event.arriveBy > after) return w.event.arriveBy - need;
    }
    return null;
  };
  const segs: Seg[] = [];
  let where = w.home;
  let since = w.leaveFrom;
  let how: 'home' | 'service' | 'flight' | 'transfer' = 'home';
  let cost = 0;
  for (let i = 0; i < steps.length; i++) {
    const st = steps[i]!;
    if (st.kind === 'service') {
      const s = st.s;
      if (s.from !== where) return null;
      if (s.dep < boardFrom(w, since, how, s, relax)) return null;
      segs.push({ where, from: since, to: s.dep }, { where: `ride:${s.id}`, from: s.dep, to: s.arr, overnight: true });
      cost += fareOf(w, s, relax);
      where = s.to;
      since = s.arr;
      how = s.mode === 'flight' ? 'flight' : 'service';
    } else {
      const t = st.t;
      if (t.from !== where) return null;
      let go = since + (how === 'flight' && !relax.flightTimes ? w.flightExit : 0);
      if (where === w.event.node && since <= w.event.arriveBy) go = Math.max(go, w.event.leaveAfter);
      // Leaving home: go when you need to.
      const fromHome = where === w.home ? latestStart(i, go) : null;
      if (fromHome !== null && fromHome > go) go = fromHome;
      // Staying the night here? (a hotel booked here for the next night to come)
      let n = 0;
      while (n < w.nights.length && (n + 1) * DAY + CHECK <= go) n++;
      if (where !== w.home && n < w.nights.length && hotels[n] && nightOf(hotels[n]!).node === where) {
        const jit = latestStart(i, go);
        if (jit !== null && jit > (n + 1) * DAY + CHECK) go = jit;
      }
      segs.push({ where, from: since, to: go }, { where: `ride:${t.id}`, from: go, to: go + t.mins, overnight: false });
      cost += t.cost;
      where = t.to;
      since = go + t.mins;
      how = 'transfer';
    }
  }
  segs.push({ where, from: since, to: Number.POSITIVE_INFINITY });
  if (where !== w.home || since > w.homeBy) return null;
  // The appointment.
  if (!segs.some((g) => g.where === w.event.node && g.from <= w.event.arriveBy && g.to >= w.event.leaveAfter)) return null;
  // Nights.
  for (let n = 0; n < w.nights.length; n++) {
    const c = (n + 1) * DAY + CHECK;
    const g = segs.find((x) => x.from <= c && x.to > c && !(x.from === c && x.where.startsWith('ride:') && x.to === c));
    const h = hotels[n];
    const atHomeOrRiding = !g || g.where === w.home || (g.where.startsWith('ride:') && g.overnight);
    if (g && g.where.startsWith('ride:') && !g.overnight) return null;
    if (atHomeOrRiding) {
      if (h) return null; // a night booked but not slept in
      continue;
    }
    if (!h) return null;
    const hotel = nightOf(h);
    if (hotel.node !== g!.where) return null;
    const price = hotel.prices[w.nights[n]!];
    if (price === undefined) return null;
    const firstNight = n === 0 || hotels[n - 1] !== h;
    if (firstNight && !relax.checkIn && g!.from > n * DAY + hotel.latestCheckIn) return null;
    if (hotel.satNeedsTwoNights && !relax.minStay && w.nights[n] === 'Sat' && hotels[n - 1] !== h && hotels[n + 1] !== h) return null;
    cost += price;
  }
  return cost;
}

/** Every leg sequence the timetable allows from home back to home (earliest timing; superset of workable plans). */
export function sequences(w: TripWorld, relax: Relax = {}, maxSteps = 10, cap = Number.POSITIVE_INFINITY): Step[][] {
  const out: Step[][] = [];
  const seen = new Set<string>();
  const steps: Step[] = [];
  const hotelNodes = new Set(w.hotels.map((h) => h.node));
  /**
   * Depth-first over the timetable with earliest timing. At a place with a hotel you may also "sleep here" (time
   * jumps to that night's 03:00 check), which is the only reason to come straight back the way you came: a transfer
   * immediately followed by its reverse is skipped unless you slept in between or were at the venue.
   */
  const walk = (where: string, since: number, how: 'home' | 'service' | 'flight' | 'transfer', attended: boolean, lastTransfer: TripTransfer | null, sleptHere: boolean) => {
    if (since > w.homeBy || out.length >= cap) return;
    if (!attended && since > w.event.arriveBy) return;
    if (where === w.home && attended && steps.length) {
      const key = steps.map((x) => (x.kind === 'service' ? x.s.id : x.t.id)).join(' ');
      if (!seen.has(key)) {
        seen.add(key);
        out.push(steps.slice());
      }
      return;
    }
    if (where !== w.home && hotelNodes.has(where)) {
      let n = 0;
      while (n < w.nights.length && (n + 1) * DAY + CHECK <= since) n++;
      const c = (n + 1) * DAY + CHECK;
      if (n < w.nights.length && c <= w.homeBy && (attended || c <= w.event.arriveBy)) walk(where, c, 'transfer', attended, lastTransfer, true);
    }
    if (steps.length >= maxSteps) return;
    for (const s of w.services) {
      if (s.from !== where || s.dep < boardFrom(w, since, how, s, relax)) continue;
      if (where === w.event.node && since <= w.event.arriveBy && s.dep < w.event.leaveAfter) continue;
      steps.push({ kind: 'service', s });
      walk(s.to, s.arr, s.mode === 'flight' ? 'flight' : 'service', attended || (s.to === w.event.node && s.arr <= w.event.arriveBy), null, false);
      steps.pop();
    }
    for (const t of w.transfers) {
      if (t.from !== where) continue;
      if (lastTransfer && lastTransfer.to === t.from && lastTransfer.from === t.to && where !== w.event.node && !sleptHere) continue;
      let go = since + (how === 'flight' && !relax.flightTimes ? w.flightExit : 0);
      if (where === w.event.node && since <= w.event.arriveBy) go = Math.max(go, w.event.leaveAfter);
      const arr = go + t.mins;
      steps.push({ kind: 'transfer', t });
      walk(t.to, arr, 'transfer', attended || (t.to === w.event.node && arr <= w.event.arriveBy), t, false);
      steps.pop();
    }
  };
  walk(w.home, w.leaveFrom, 'home', false, null, false);
  return out;
}

/**
 * The exact optimum: depth-first branch and bound over every itinerary and hotel choice. Same moves as
 * `sequences`, plus "sleep here in hotel H" at any place with a hotel (one branch per hotel with a room that night).
 * Every complete candidate is checked and priced by `evaluate`; a branch is cut only when its cost so far already
 * exceeds the best workable plan found (costs never go down), so ties are kept and counted.
 */
export function solveTrip(w: TripWorld, relax: Relax = {}, maxSteps = 24): { best: TripPlan; optimum: number; optimalPlans: number; candidates: number } {
  let best: TripPlan | null = null;
  let bestCost = Number.POSITIVE_INFINITY;
  const optimal = new Set<string>();
  const done = new Set<string>();
  let candidates = 0;
  const steps: Step[] = [];
  const hotels: Array<string | null> = w.nights.map(() => null);
  // Admissible lower bound on the cost still to come: cheapest fares between places, ignoring the clock.
  const nodes = Object.keys(w.nodes);
  const cheap: Record<string, Record<string, number>> = {};
  for (const a of nodes) {
    cheap[a] = {};
    for (const b of nodes) cheap[a]![b] = a === b ? 0 : Number.POSITIVE_INFINITY;
  }
  for (const s of w.services) cheap[s.from]![s.to] = Math.min(cheap[s.from]![s.to]!, Math.min(fareOf(w, s, relax), Math.round((s.fare * 2) / 3) + (s.supplement ?? 0)));
  for (const t of w.transfers) cheap[t.from]![t.to] = Math.min(cheap[t.from]![t.to]!, t.cost);
  for (const k of nodes) for (const a of nodes) for (const b of nodes) if (cheap[a]![k]! + cheap[k]![b]! < cheap[a]![b]!) cheap[a]![b] = cheap[a]![k]! + cheap[k]![b]!;
  const hotelNode = new Map(w.hotels.map((h) => [h.id, h.node]));
  /** Waiting at `where` from `from` until `to` is only allowed through a night if a hotel is booked there for it. */
  const nightsOk = (where: string, from: number, to: number) => {
    if (where === w.home) return true;
    for (let n = 0; n < w.nights.length; n++) {
      const c = (n + 1) * DAY + CHECK;
      if (from <= c && c < to && hotelNode.get(hotels[n] ?? '') !== where) return false;
    }
    return true;
  };
  /** Only walks/taxis since leaving home: their timing is flexible (you leave home when you need to), so no night check yet. */
  const stillFromHome = () => steps.every((x) => x.kind === 'transfer');
  const still = (where: string, attended: boolean) => (attended ? cheap[where]![w.home]! : cheap[where]![w.event.node]! + cheap[w.event.node]![w.home]!);
  const walk = (where: string, since: number, how: 'home' | 'service' | 'flight' | 'transfer', attended: boolean, lastTransfer: TripTransfer | null, sleptHere: boolean, cost: number) => {
    if (cost + still(where, attended) > bestCost || since > w.homeBy) return;
    if (!attended && since > w.event.arriveBy) return;
    if (where === w.home && attended && steps.length) {
      const key = steps.map((x) => (x.kind === 'service' ? x.s.id : x.t.id)).join(' ') + ' | ' + hotels.join(',');
      if (done.has(key)) return;
      done.add(key);
      candidates++;
      const c = evaluate(w, steps, hotels, relax);
      if (c === null) return;
      if (c < bestCost) {
        bestCost = c;
        best = { steps: steps.slice(), hotels: hotels.slice(), cost: c };
        optimal.clear();
      }
      if (c === bestCost) {
        optimal.add(key);
        // Among equally cheap plans, show the one with the fewest steps (then the earliest found).
        if (best && steps.length < best.steps.length) best = { steps: steps.slice(), hotels: hotels.slice(), cost: c };
      }
      return;
    }
    if (where !== w.home) {
      let n = 0;
      while (n < w.nights.length && (n + 1) * DAY + CHECK <= since) n++;
      const c = (n + 1) * DAY + CHECK;
      if (n < w.nights.length && c <= w.homeBy && (attended || c <= w.event.arriveBy)) {
        for (const h of w.hotels) {
          const price = h.prices[w.nights[n]!];
          if (h.node !== where || price === undefined) continue;
          // Earliest arrival here is a lower bound on the real one: too late for check-in now means too late for good.
          if (!relax.checkIn && hotels[n - 1] !== h.id && since > n * DAY + h.latestCheckIn) continue;
          hotels[n] = h.id;
          walk(where, c, 'transfer', attended, lastTransfer, true, cost + price);
          hotels[n] = null;
        }
      }
    }
    if (steps.length >= maxSteps) return;
    // Try the cheapest-looking moves first, so a good plan is found early and the bound cuts more.
    const moves: Array<{ bound: number; go: () => void }> = [];
    for (const s of w.services) {
      if (s.from !== where || s.dep < boardFrom(w, since, how, s, relax)) continue;
      if (where === w.event.node && since <= w.event.arriveBy && s.dep < w.event.leaveAfter) continue;
      if (!stillFromHome() && !nightsOk(where, since, s.dep)) continue;
      const att = attended || (s.to === w.event.node && s.arr <= w.event.arriveBy);
      const c = cost + fareOf(w, s, relax);
      moves.push({
        bound: c + still(s.to, att),
        go: () => {
          steps.push({ kind: 'service', s });
          walk(s.to, s.arr, s.mode === 'flight' ? 'flight' : 'service', att, null, false, c);
          steps.pop();
        },
      });
    }
    for (const t of w.transfers) {
      if (t.from !== where) continue;
      if (lastTransfer && lastTransfer.to === t.from && lastTransfer.from === t.to && where !== w.event.node && !sleptHere) continue;
      let go = since + (how === 'flight' && !relax.flightTimes ? w.flightExit : 0);
      if (where === w.event.node && since <= w.event.arriveBy) go = Math.max(go, w.event.leaveAfter);
      const arr = go + t.mins;
      if (where !== w.home && !stillFromHome() && !nightsOk(where, since, go)) continue;
      const att = attended || (t.to === w.event.node && arr <= w.event.arriveBy);
      const c = cost + t.cost;
      moves.push({
        bound: c + still(t.to, att),
        go: () => {
          steps.push({ kind: 'transfer', t });
          walk(t.to, arr, 'transfer', att, t, false, c);
          steps.pop();
        },
      });
    }
    moves.sort((x, y) => x.bound - y.bound);
    for (const mv of moves) if (mv.bound <= bestCost) mv.go();
  };
  walk(w.home, w.leaveFrom, 'home', false, null, false, 0);
  if (!best) throw new Error('no workable plan');
  const b = best as TripPlan;
  return { best: b, optimum: b.cost, optimalPlans: optimal.size, candidates };
}

/** A plan as the JSON answer a model would write. */
export function planAnswer(w: TripWorld, p: TripPlan, total = p.cost): Record<string, unknown> {
  const stays: Array<{ hotel: string; nights: string[] }> = [];
  p.hotels.forEach((h, n) => {
    if (!h) return;
    const last = stays[stays.length - 1];
    if (last && last.hotel === h && p.hotels[n - 1] === h) last.nights.push(w.nights[n]!);
    else stays.push({ hotel: h, nights: [w.nights[n]!] });
  });
  return { journey: p.steps.map((s) => (s.kind === 'service' ? s.s.id : s.t.id)), hotels: stays, total: pounds(total) };
}

// ───────────────────────────── Scenario builder ─────────────────────────────

const MODE_PREFIX: Record<TripMode, string> = { train: 'T', sleeper: 'S', coach: 'C', flight: 'F', ferry: 'X', bus: 'B' };
const MODE_WORD: Record<TripMode, string> = { train: 'Train', sleeper: 'Sleeper train', coach: 'Coach', flight: 'Flight', ferry: 'Ferry', bus: 'Bus' };

class Scenario {
  readonly rng: Rng;
  readonly w: TripWorld;
  private raw: Array<Omit<TripService, 'id'>> = [];
  private tIndex = 0;
  constructor(seed: number, days: string[], weekdays: number[]) {
    this.rng = new Rng(seed);
    const nights = days.slice(0, -1).map((d) => d.slice(0, 3));
    this.w = {
      home: '',
      nodes: {},
      days,
      nights,
      weekdays,
      services: [],
      transfers: [],
      hotels: [],
      railcard: null,
      flightCheckIn: 60,
      flightExit: 30,
      leaveFrom: 0,
      homeBy: 0,
      event: { node: '', arriveBy: 0, leaveAfter: 0, label: '' },
    };
  }
  at(day: number, t: string): number {
    return day * DAY + mins(t);
  }
  node(code: string, name: string, minConnect: number, airport = false): this {
    this.w.nodes[code] = { name, minConnect, ...(airport ? { airport: true } : {}) };
    return this;
  }
  /** A fare in pence: base × (weekday peak 1.45) × a random factor, to the nearest 10p. */
  fare(base: number, day: number, dep: string, spread = 0.25): number {
    const m = mins(dep);
    const peak = this.w.weekdays.includes(day) && ((m >= 390 && m < 570) || (m >= 960 && m < 1140)) ? 1.45 : 1;
    const f = 1 - spread + this.rng.next() * 2 * spread;
    return Math.max(100, Math.round((base * peak * f) / 10) * 10);
  }
  /** One service. `fare` in pence (exact) or omitted for a generated one from `base`. */
  svc(mode: TripMode, from: string, to: string, day: number, dep: string, dur: number, fare: number, supplement?: number): this {
    const d = this.at(day, dep);
    this.raw.push({ mode, from, to, dep: d, arr: d + dur, fare, ...(supplement ? { supplement } : {}) });
    return this;
  }
  /** Several departures with jittered minutes and generated fares. */
  run(mode: TripMode, from: string, to: string, day: number, deps: string[], dur: number, base: number, jitter = 6): this {
    for (const dep of deps) {
      const j = this.rng.int(-jitter, jitter);
      const t = hhmm(mins(dep) + j);
      this.svc(mode, from, to, day, t, dur + this.rng.int(-2, 3), this.fare(base, day, t));
    }
    return this;
  }
  transfer(from: string, to: string, minutes: number, cost: number, label: string, both = true): this {
    this.w.transfers.push({ id: `W${++this.tIndex}`, from, to, mins: minutes, cost, label });
    if (both) this.w.transfers.push({ id: `W${++this.tIndex}`, from: to, to: from, mins: minutes, cost, label });
    return this;
  }
  hotel(name: string, node: string, prices: Record<string, number>, latest: string, extra: Partial<TripHotel> = {}): this {
    const lm = mins(latest);
    this.w.hotels.push({ id: `H${this.w.hotels.length + 1}`, name, node, prices, latestCheckIn: lm < 360 ? lm + DAY : lm, ...extra });
    return this;
  }
  finish(): TripWorld {
    const sorted = this.raw.slice().sort((a, b) => a.dep - b.dep || a.from.localeCompare(b.from) || a.arr - b.arr);
    const counters: Record<string, number> = {};
    this.w.services = sorted.map((s) => {
      const p = MODE_PREFIX[s.mode];
      counters[p] = (counters[p] ?? 0) + 1;
      return { id: `${p}${String(counters[p]).padStart(2, '0')}`, ...s };
    });
    return this.w;
  }
}

// ───────────────────────────── Prompt ─────────────────────────────

function when(w: TripWorld, t: number): string {
  const d = Math.floor(t / DAY);
  return `${w.days[d]!.slice(0, 3)} ${hhmm(t)}`;
}

function arrText(w: TripWorld, s: TripService): string {
  const dd = Math.floor(s.arr / DAY) - Math.floor(s.dep / DAY);
  return dd > 0 ? `${w.days[Math.floor(s.arr / DAY)]!.slice(0, 3)} ${hhmm(s.arr)}` : hhmm(s.arr);
}

export interface TripStory {
  intro: string;
  extra?: string[];
  /** Text after the tables (late corrections). */
  late?: string;
  railcardText?: string;
  /** Prices shown in the hotel table that a later message withdraws (the world has the true availability). */
  staleRooms?: Record<string, Record<string, number>>;
}

export function tripPrompt(w: TripWorld, story: TripStory): string {
  const name = (n: string) => w.nodes[n]!.name;
  const L: string[] = [story.intro.trim(), ''];
  if (story.extra?.length) L.push(...story.extra, '');
  L.push('TIMETABLE (prices are the single fare per person, before any railcard discount)');
  L.push('ID | Type | From | To | Departs | Arrives | Fare');
  for (const s of w.services) L.push(`${s.id} | ${MODE_WORD[s.mode]} | ${name(s.from)} | ${name(s.to)} | ${when(w, s.dep)} | ${arrText(w, s)} | ${money(s.fare)}${s.supplement ? ` + berth ${money(s.supplement)}` : ''}`);
  L.push('', 'TRANSFERS (available at any time, in the direction shown)');
  L.push('ID | From | To | How | Minutes | Cost');
  for (const t of w.transfers) L.push(`${t.id} | ${name(t.from)} | ${name(t.to)} | ${t.label} | ${t.mins} | ${t.cost ? money(t.cost) : 'free'}`);
  L.push('', 'HOTELS (price per room per night; "full" means no rooms that night)');
  L.push(`ID | Hotel | Where | ${w.nights.map((n) => `${n} night`).join(' | ')} | Latest check-in | Notes`);
  for (const h of w.hotels) {
    const notes = [h.satNeedsTwoNights ? 'Saturday night only as part of a stay of 2+ nights' : ''].filter(Boolean).join('; ');
    const shown = { ...h.prices, ...(story.staleRooms?.[h.id] ?? {}) };
    L.push(`${h.id} | ${h.name} | ${name(h.node)} | ${w.nights.map((n) => (shown[n] === undefined ? 'full' : money(shown[n]!))).join(' | ')} | ${hhmm(h.latestCheckIn)} | ${notes || '-'}`);
  }
  const mc = Object.entries(w.nodes)
    .filter(([, n]) => n.minConnect > 0)
    .map(([, n]) => `${n.name} ${n.minConnect} min`)
    .join(', ');
  L.push('', 'RULES');
  L.push(`- Leave home (${name(w.home)}) no earlier than ${when(w, w.leaveFrom)} and be back home by ${when(w, w.homeBy)}.`);
  L.push(`- Be at ${w.event.label} by ${when(w, w.event.arriveBy)} and stay until ${when(w, w.event.leaveAfter)}.`);
  L.push(`- Changing from one train, coach, bus, ferry or flight to another at the same place needs at least the minimum connection time: ${mc}. There is no minimum when you start from home or after a transfer.`);
  if (w.services.some((s) => s.mode === 'flight'))
    L.push(`- Flights: you must be at the departure airport at least ${w.flightCheckIn} minutes before the flight leaves (however you got there), and after landing you need ${w.flightExit} minutes before you can take anything else (including a transfer).`);
  L.push('- Transfers (walks, taxis, the tube, local buses) run whenever you need them and take the minutes shown. You set off straight away, except: from home you set off in time for your next timed service; from the venue you leave when the reception ends; and from a place where you have a hotel booked for the coming night, you set off the next morning, in time for your next timed service (or the wedding).');
  L.push('- Every night away from home you need either a hotel room booked in the place where you are, or to be on an overnight service (one still travelling at 03:00). On the first night of a hotel stay you must arrive in that place by the hotel’s latest check-in time. Only book nights you actually sleep there.');
  if (w.railcard) {
    const modes = w.railcard.modes.map((m) => (m === 'train' ? 'trains' : m === 'sleeper' ? 'sleeper fares' : `${m}es`)).join(' and ');
    L.push(`- ${story.railcardText ?? 'My railcard'} takes a third off ${modes} (the discounted fare is two thirds of the fare, rounded to the nearest penny). It is not valid on trains leaving before ${hhmm(w.railcard.weekdayNotBefore)} on a Monday to Friday, and it never applies to coaches, buses, ferries or flights${w.services.some((s) => s.supplement) ? ', or to sleeper berth supplements' : ''}.`);
  }
  L.push('- The total is every fare (after any railcard discount), every transfer and every hotel night.');
  if (story.late) L.push('', story.late.trim());
  L.push(
    '',
    'Find the cheapest trip that keeps every rule. Answer with the whole trip in order (every service and transfer by ID, including walks and taxis), the hotel nights you book, and the total in pounds, in this JSON format:',
    '```json',
    '{"journey": ["<ID>", "<ID>"], "hotels": [{"hotel": "<hotel ID>", "nights": ["<night>"]}], "total": 0.00}',
    '```',
    `Nights are named by the evening they start: ${w.nights.map((n) => `"${n}"`).join(', ')}. Use "hotels": [] if you book none. ${ONE_ANSWER}`,
  );
  return L.join('\n');
}

// ───────────────────────────── The ten trips ─────────────────────────────

export interface TripCase {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  world: TripWorld;
  prompt: string;
  notes: string;
  /** Longest itinerary (in steps) the solver searches; every workable plan here is shorter. */
  maxSteps: number;
  reference: Record<string, unknown>;
  optimum: number;
  optimalPlans: number;
  careless: CarelessPlan[];
}

const RC = { modes: ['train', 'sleeper'] as TripMode[], weekdayNotBefore: 570 };

function hourly(from: string, to: string, stepMin = 60): string[] {
  const out: string[] = [];
  for (let m = mins(from); m <= mins(to); m += stepMin) out.push(hhmm(m));
  return out;
}

type Builder = () => { w: TripWorld; story: TripStory; difficulty: 'easy' | 'medium' | 'hard'; maxSteps?: number; missedLate?: TripWorld };

export const BUILDERS_FOR_DEBUG: Record<string, Builder> = {
  // 1. Leeds → York, a day trip is possible if you watch the walk after the reception.
  t01: () => {
    const s = new Scenario(4101, ['Fri 12 Jun', 'Sat 13 Jun', 'Sun 14 Jun'], [0]);
    s.node('LDS', 'Leeds', 5).node('YRK', 'York', 5).node('VEN', 'Mickle Hall (venue)', 0);
    s.w.home = 'LDS';
    s.run('train', 'LDS', 'YRK', 0, ['17:40', '18:40', '19:40', '20:40'], 27, 1450, 4);
    s.run('train', 'LDS', 'YRK', 1, hourly('07:10', '12:10'), 27, 1200, 4);
    s.svc('train', 'LDS', 'YRK', 1, '12:58', 27, 680);
    s.svc('coach', 'LDS', 'YRK', 1, '10:25', 70, 650);
    s.svc('coach', 'LDS', 'YRK', 1, '12:15', 70, 450);
    s.svc('train', 'YRK', 'LDS', 1, '22:20', 27, 950).svc('train', 'YRK', 'LDS', 1, '23:10', 27, 830).svc('train', 'YRK', 'LDS', 1, '23:32', 29, 1120);
    s.run('train', 'YRK', 'LDS', 2, hourly('08:15', '17:15', 180), 27, 1100, 4);
    s.svc('coach', 'YRK', 'LDS', 2, '09:40', 75, 500);
    s.transfer('YRK', 'VEN', 18, 0, 'Walk');
    s.hotel('Ouse Bridge Inn', 'YRK', { Fri: 8900, Sat: 11400 }, '23:00').hotel('Minster Lodge', 'YRK', { Fri: 7200, Sat: 9800 }, '22:00');
    s.w.railcard = { ...RC };
    s.w.leaveFrom = s.at(0, '17:30');
    s.w.homeBy = s.at(2, '20:00');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '13:40'), leaveAfter: s.at(1, '23:00'), label: 'Mickle Hall (the venue)' };
    return {
      w: s.finish(),
      difficulty: 'easy',
      story: {
        intro:
          'My cousin Amara is getting married at Mickle Hall in York on Saturday 13 June. The ceremony is at 14:00 and they want guests there by 13:40; the reception finishes at 23:00. I live in Leeds and finish work at 17:00 on Friday, and I must be home by 20:00 on Sunday. I have a 16-25 Railcard. Money is tight, so I want the cheapest way to do it.',
      },
    };
  },
  // 2. Manchester → Bath: the cheapest hotel closes its doors before the reception ends.
  t02: () => {
    const s = new Scenario(4202, ['Fri 19 Jun', 'Sat 20 Jun', 'Sun 21 Jun'], [0]);
    s.node('MAN', 'Manchester Piccadilly', 10).node('BRI', 'Bristol Temple Meads', 8).node('BTH', 'Bath Spa', 5).node('VEN', 'Prior Park Orangery (venue)', 0);
    s.w.home = 'MAN';
    s.run('train', 'MAN', 'BRI', 0, ['16:27', '17:27', '18:27'], 184, 5200, 3);
    s.run('train', 'MAN', 'BRI', 1, ['06:27', '07:27', '08:27', '09:27'], 184, 4600, 3);
    s.run('train', 'BRI', 'BTH', 0, ['19:40', '20:10', '20:40', '21:10', '21:40', '22:10'], 13, 890, 2);
    s.run('train', 'BRI', 'BTH', 1, ['09:40', '10:10', '10:40', '11:10', '11:40', '12:40', '13:10'], 13, 890, 2);
    s.svc('coach', 'MAN', 'BTH', 0, '23:30', 375, 2300);
    s.svc('coach', 'MAN', 'BTH', 1, '07:05', 345, 1900);
    s.run('train', 'BTH', 'BRI', 2, ['09:05', '10:05', '11:05', '12:05', '14:05'], 13, 890, 2);
    s.run('train', 'BRI', 'MAN', 2, ['09:30', '10:30', '11:30', '12:30', '14:30', '16:30'], 186, 4300, 3);
    s.svc('coach', 'BTH', 'MAN', 2, '10:15', 360, 2100);
    s.transfer('BTH', 'VEN', 15, 1200, 'Taxi');
    s.hotel('Bath Spa Rooms', 'BTH', { Fri: 7900, Sat: 8500 }, '22:00')
      .hotel('Pulteney Guest House', 'BTH', { Fri: 9900, Sat: 12400 }, '23:59')
      .hotel('Orangery Courtyard Rooms', 'VEN', { Sat: 16500 }, '01:00');
    s.w.railcard = { ...RC };
    s.w.leaveFrom = s.at(0, '16:00');
    s.w.homeBy = s.at(2, '21:00');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '13:30'), leaveAfter: s.at(1, '23:00'), label: 'Prior Park Orangery (the venue)' };
    return {
      w: s.finish(),
      difficulty: 'easy',
      story: {
        intro:
          'Help! My best friend Femi is getting married at the Prior Park Orangery just outside Bath on Saturday 20 June. Guests need to be there by 13:30 and the party ends at 23:00. I live in Manchester, I can leave from 16:00 on Friday, and I need to be home by 21:00 on Sunday because I work Monday. There are no trains back to Manchester after the party, so I will need somewhere to stay. I have a 26-30 Railcard. What is the cheapest way to do the whole weekend?',
      },
    };
  },
  // 3. Birmingham → Cardiff: a cheap coach that arrives too late, a night coach home, a bus or a taxi.
  t03: () => {
    const s = new Scenario(4303, ['Fri 3 Jul', 'Sat 4 Jul', 'Sun 5 Jul'], [0]);
    s.node('BHM', 'Birmingham', 10).node('CDF', 'Cardiff Central', 6).node('CDC', 'Cardiff coach station', 0).node('VEN', 'Llandaff Barn (venue)', 0);
    s.w.home = 'BHM';
    s.run('train', 'BHM', 'CDF', 1, ['07:12', '08:12', '09:12', '10:12', '11:12', '12:12'], 118, 3100, 4);
    s.run('train', 'BHM', 'CDF', 0, ['18:12', '19:12', '20:12'], 118, 3800, 4);
    s.svc('coach', 'BHM', 'CDC', 1, '09:00', 170, 1150).svc('coach', 'BHM', 'CDC', 1, '11:15', 165, 790).svc('coach', 'BHM', 'CDC', 0, '18:30', 165, 1250);
    s.run('train', 'CDF', 'BHM', 1, ['21:45', '22:52'], 120, 2900, 2);
    s.svc('coach', 'CDC', 'BHM', 1, '23:55', 160, 1450);
    s.run('train', 'CDF', 'BHM', 2, ['09:45', '11:45', '13:45', '15:45'], 120, 2700, 4);
    s.svc('coach', 'CDC', 'BHM', 2, '10:30', 170, 950);
    s.transfer('CDF', 'VEN', 20, 1500, 'Taxi').transfer('CDF', 'VEN', 35, 250, 'Bus 63').transfer('CDC', 'CDF', 5, 0, 'Walk');
    s.hotel('Taff Embankment Hotel', 'CDF', { Fri: 6900, Sat: 8900 }, '23:30').hotel('Castle Quarter Inn', 'CDF', { Fri: 8400, Sat: 7600 }, '23:59');
    s.w.railcard = null;
    s.w.leaveFrom = s.at(0, '17:45');
    s.w.homeBy = s.at(2, '18:00');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '14:30'), leaveAfter: s.at(1, '22:30'), label: 'Llandaff Barn (the venue)' };
    return {
      w: s.finish(),
      difficulty: 'easy',
      story: {
        intro:
          'My colleague Rhys has invited me to his wedding at Llandaff Barn in Cardiff on Saturday 4 July: be there by 14:30, and the evening do ends at 22:30. I live in Birmingham. I can leave after 17:45 on Friday and must be home by 18:00 on Sunday. I don’t have a railcard. I’m happy to take the coach or the bus if it’s cheaper. What’s the cheapest plan?',
      },
    };
  },
  // 4. London → Edinburgh: trains, a sleeper with a berth supplement, flights with airport time, a late flight home that is too late.
  t04: () => {
    const s = new Scenario(4404, ['Fri 7 Aug', 'Sat 8 Aug', 'Sun 9 Aug'], [0]);
    s.node('HOM', 'Home (Camden)', 0).node('KGX', 'London King’s Cross', 10).node('EUS', 'London Euston', 15).node('LTN', 'Luton Airport', 0, true)
      .node('EDB', 'Edinburgh Waverley', 10).node('EDA', 'Edinburgh Airport', 0, true).node('VEN', 'Leith Assembly Rooms (venue)', 0);
    s.w.home = 'HOM';
    s.run('train', 'KGX', 'EDB', 0, ['12:00', '13:00', '14:00', '16:00', '18:00'], 262, 8900, 3);
    s.run('train', 'KGX', 'EDB', 1, ['06:15', '07:00', '08:00'], 262, 7400, 3);
    s.svc('sleeper', 'EUS', 'EDB', 0, '23:50', 430, 6000, 9500);
    s.svc('flight', 'LTN', 'EDA', 0, '18:40', 75, 4900).svc('flight', 'LTN', 'EDA', 1, '06:35', 75, 5600).svc('flight', 'LTN', 'EDA', 1, '09:25', 75, 3300);
    s.run('train', 'EDB', 'KGX', 2, ['09:30', '11:00', '13:00', '15:00'], 265, 7900, 3);
    s.svc('flight', 'EDA', 'LTN', 2, '16:55', 80, 4400).svc('flight', 'EDA', 'LTN', 2, '19:20', 80, 2700);
    s.transfer('HOM', 'KGX', 20, 0, 'Walk').transfer('HOM', 'EUS', 15, 0, 'Walk').transfer('HOM', 'LTN', 65, 1100, 'Coach (turn up and go)')
      .transfer('EDA', 'EDB', 30, 750, 'Tram').transfer('EDB', 'VEN', 15, 1400, 'Taxi').transfer('EDB', 'VEN', 25, 200, 'Bus 22');
    s.hotel('Cowgate Hostel (private room)', 'EDB', { Fri: 7400, Sat: 9600 }, '22:00')
      .hotel('Waverley Steps Hotel', 'EDB', { Fri: 11800, Sat: 13900 }, '01:00')
      .hotel('Shore Street Inn', 'VEN', { Fri: 9000, Sat: 10500 }, '23:30', { satNeedsTwoNights: true });
    s.w.railcard = { ...RC };
    s.w.leaveFrom = s.at(0, '11:30');
    s.w.homeBy = s.at(2, '22:00');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '13:15'), leaveAfter: s.at(1, '23:30'), label: 'Leith Assembly Rooms (the venue)' };
    return {
      w: s.finish(),
      difficulty: 'medium',
      story: {
        intro:
          'I’ve been asked to be an usher at Hana and Callum’s wedding at the Leith Assembly Rooms in Edinburgh on Saturday 8 August. Ushers must be there by 13:15 and the ceilidh finishes at 23:30. I live in Camden in London and can leave from 11:30 on Friday (half day). I have to be home by 22:00 on Sunday. I’ve got a Two Together-style railcard of my own that works when I travel alone. Trains, the sleeper or flying are all fine by me, as long as it’s the cheapest option that actually works.',
      },
    };
  },
  // 5. Bristol → Norwich across London: a tube transfer between terminals, a Friday railcard window.
  t05: () => {
    const s = new Scenario(4505, ['Fri 11 Sep', 'Sat 12 Sep', 'Sun 13 Sep'], [0]);
    s.node('BRI', 'Bristol Temple Meads', 8).node('PAD', 'London Paddington', 10).node('LST', 'London Liverpool Street', 10).node('NRW', 'Norwich', 5).node('VEN', 'Earlham Hall (venue)', 0);
    s.w.home = 'BRI';
    s.run('train', 'BRI', 'PAD', 0, ['08:00', '08:30', '09:00', '09:30', '10:00', '11:00', '13:00', '17:00'], 100, 5600, 2);
    s.run('train', 'BRI', 'PAD', 1, ['05:30', '06:00', '06:30', '07:00'], 102, 4100, 2);
    s.run('train', 'LST', 'NRW', 0, ['10:30', '11:00', '11:30', '12:30', '13:30', '14:30', '15:30', '19:30'], 112, 3400, 3);
    s.run('train', 'LST', 'NRW', 1, ['07:30', '08:00', '08:30', '09:00'], 112, 2900, 3);
    s.svc('coach', 'BRI', 'NRW', 0, '07:15', 455, 2900);
    s.run('train', 'NRW', 'LST', 2, ['09:00', '10:00', '11:00', '13:00', '15:00'], 112, 3100, 3);
    s.run('train', 'PAD', 'BRI', 2, ['11:30', '12:30', '13:30', '15:00', '17:00', '19:00'], 100, 4700, 3);
    s.svc('coach', 'NRW', 'BRI', 2, '08:45', 460, 3200);
    s.transfer('PAD', 'LST', 32, 290, 'Tube (Elizabeth line)').transfer('NRW', 'VEN', 22, 1600, 'Taxi');
    s.hotel('Prince of Wales Road Lodge', 'NRW', { Fri: 6200, Sat: 9900 }, '23:00').hotel('Tombland Hotel', 'NRW', { Fri: 8800, Sat: 8800 }, '23:59', { satNeedsTwoNights: true }).hotel('Earlham Hall Rooms', 'VEN', { Fri: 14000, Sat: 12000 }, '23:00');
    s.w.railcard = { ...RC };
    s.w.leaveFrom = s.at(0, '07:00');
    s.w.homeBy = s.at(2, '23:00');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '11:30'), leaveAfter: s.at(1, '23:00'), label: 'Earlham Hall (the venue)' };
    return {
      w: s.finish(),
      difficulty: 'medium',
      story: {
        intro:
          'Okay, planning help needed. My sister Leah’s wedding is at Earlham Hall near Norwich on Saturday 12 September and it starts early: we have to be there by 11:30. Dancing finishes at 23:00. I live in Bristol. I’ve booked Friday off, so I can leave from 07:00 on Friday, and I need to be back in Bristol by 23:00 on Sunday. I have a Railcard. Coming through London means getting from Paddington to Liverpool Street.',
      },
    };
  },
  // 6. Newcastle → Exeter: the railcard runs out on the Saturday, and the cheap hotel wants two nights.
  t06: () => {
    const s = new Scenario(4606, ['Fri 2 Oct', 'Sat 3 Oct', 'Sun 4 Oct'], [0]);
    s.node('NCL', 'Newcastle', 7).node('BHM', 'Birmingham New Street', 12).node('EXD', 'Exeter St Davids', 6).node('VEN', 'Topsham Quay Barn (venue)', 0);
    s.w.home = 'NCL';
    s.run('train', 'NCL', 'BHM', 0, ['08:20', '09:20', '10:20', '13:20', '16:20'], 200, 6200, 3);
    s.run('train', 'BHM', 'EXD', 0, ['12:12', '13:12', '14:12', '17:12', '20:12'], 154, 4400, 3);
    s.run('train', 'NCL', 'BHM', 1, ['05:45', '06:20'], 200, 5400, 2);
    s.run('train', 'BHM', 'EXD', 1, ['09:12', '09:42', '10:12'], 154, 3900, 2);
    s.svc('coach', 'NCL', 'EXD', 0, '22:00', 650, 3600);
    s.run('train', 'EXD', 'BHM', 2, ['08:50', '09:50', '10:50', '12:50', '14:50'], 156, 4200, 3);
    s.run('train', 'BHM', 'NCL', 2, ['11:45', '12:45', '13:45', '15:45', '17:45', '19:45'], 200, 5800, 3);
    s.svc('coach', 'EXD', 'NCL', 2, '07:30', 660, 6900).svc('coach', 'EXD', 'NCL', 2, '10:00', 640, 7400);
    s.transfer('EXD', 'VEN', 25, 1800, 'Taxi').transfer('EXD', 'VEN', 40, 340, 'Train + walk (turn up and go)');
    s.hotel('Exe Riverside B&B', 'EXD', { Fri: 6900, Sat: 7400 }, '23:59', { satNeedsTwoNights: true }).hotel('Cathedral Green Hotel', 'EXD', { Fri: 9800, Sat: 13600 }, '01:00').hotel('Quay Barn Cottages', 'VEN', { Sat: 11900 }, '23:59');
    s.w.railcard = { ...RC, validBefore: s.at(2, '00:00') };
    s.w.leaveFrom = s.at(0, '08:00');
    s.w.homeBy = s.at(2, '23:59');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '13:00'), leaveAfter: s.at(1, '23:30'), label: 'Topsham Quay Barn (the venue)' };
    return {
      w: s.finish(),
      difficulty: 'medium',
      story: {
        intro:
          'I’m going to Kofi and Ines’s wedding at Topsham Quay Barn, near Exeter, on Saturday 3 October (arrive by 13:00, finishes 23:30). I live in Newcastle, I can set off from 08:00 on Friday and I need to be home before midnight on Sunday. One complication: my 16-25 Railcard expires at the end of Saturday 3 October, so it is only valid for journeys that start on or before that day.',
        railcardText: 'My railcard (valid only for services leaving before Sun 00:00)',
      },
    };
  },
  // 7. Glasgow → St Ives: sleepers both ways, Monday railcard window, a ceremony moved earlier in a late message.
  t07: () => {
    const s = new Scenario(4707, ['Fri 15 May', 'Sat 16 May', 'Sun 17 May', 'Mon 18 May'], [0, 3]);
    s.node('GLC', 'Glasgow Central', 10).node('GLA', 'Glasgow Airport', 0, true).node('EUS', 'London Euston', 15).node('PAD', 'London Paddington', 10)
      .node('PNZ', 'Penzance', 5).node('SER', 'St Erth', 4).node('SIV', 'St Ives', 0).node('NQY', 'Newquay Airport', 0, true).node('VEN', 'Porthmeor Studios (venue)', 0);
    s.w.home = 'GLC';
    s.run('train', 'GLC', 'EUS', 0, ['07:30', '08:30', '09:40', '10:40'], 272, 9800, 3);
    s.svc('train', 'PAD', 'SER', 0, '12:58', 300, 6100).svc('train', 'PAD', 'SER', 0, '15:04', 302, 5200).svc('train', 'PAD', 'SER', 0, '17:03', 299, 5800);
    s.svc('sleeper', 'PAD', 'PNZ', 0, '23:45', 485, 5400, 6400);
    s.svc('flight', 'GLA', 'NQY', 0, '17:20', 90, 6900).svc('flight', 'GLA', 'NQY', 1, '09:05', 90, 5900);
    s.run('train', 'PNZ', 'SER', 1, ['08:10', '09:10', '10:10', '11:10', '12:10'], 9, 420, 2);
    s.run('train', 'SER', 'SIV', 0, ['18:20', '20:20', '21:20', '22:20'], 12, 380, 3);
    s.run('train', 'SER', 'SIV', 1, ['08:25', '09:25', '10:25', '11:25', '12:25', '13:25'], 12, 380, 3);
    s.run('train', 'SIV', 'SER', 2, ['08:00', '09:00', '10:00'], 12, 380, 3);
    s.run('train', 'SIV', 'SER', 3, ['06:05', '08:30', '09:30'], 12, 380, 2);
    s.svc('train', 'SER', 'PAD', 2, '08:40', 305, 5900).svc('train', 'SER', 'PAD', 2, '10:44', 303, 4400).svc('train', 'SER', 'PAD', 3, '06:20', 300, 6700).svc('train', 'SER', 'PAD', 3, '09:48', 301, 5300);
    s.svc('sleeper', 'PNZ', 'PAD', 2, '21:45', 455, 5400, 6400);
    s.run('train', 'EUS', 'GLC', 2, ['15:30', '16:30', '17:30'], 275, 9100, 3);
    s.run('train', 'EUS', 'GLC', 3, ['06:30', '07:30', '09:30', '10:30', '15:30'], 275, 9600, 3);
    s.svc('flight', 'NQY', 'GLA', 2, '18:10', 95, 7400).svc('flight', 'NQY', 'GLA', 3, '07:15', 95, 4300);
    s.transfer('GLC', 'GLA', 25, 1000, 'Airport bus').transfer('EUS', 'PAD', 22, 280, 'Tube')
      .transfer('NQY', 'SIV', 45, 5600, 'Taxi').transfer('NQY', 'SER', 70, 650, 'Bus (turn up and go)').transfer('SIV', 'VEN', 10, 0, 'Walk').transfer('SIV', 'PNZ', 25, 3500, 'Taxi');
    s.hotel('Harbour View Hotel', 'SIV', { Fri: 13500, Sat: 15500, Sun: 9500 }, '21:00')
      .hotel('Smeaton’s Pier B&B', 'SIV', { Fri: 11000, Sat: 14000, Sun: 8000 }, '23:00', { satNeedsTwoNights: true })
      .hotel('Penzance Station Hotel', 'PNZ', { Fri: 6900, Sat: 8900, Sun: 5900 }, '01:30')
      .hotel('Paddington Pod Rooms', 'PAD', { Fri: 7900, Sun: 7200 }, '01:00');
    s.w.railcard = { ...RC };
    s.w.leaveFrom = s.at(0, '06:00');
    s.w.homeBy = s.at(3, '23:00');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '12:30'), leaveAfter: s.at(1, '23:59'), label: 'Porthmeor Studios (the venue)' };
    const w = s.finish();
    return {
      w,
      missedLate: { ...w, event: { ...w.event, arriveBy: s.at(1, '13:30') } },
      difficulty: 'hard',
      story: {
        intro:
          'Big trip! Wen and Oskar are getting married at Porthmeor Studios in St Ives, Cornwall, on Saturday 16 May. The invite says arrive by 13:30, and the party ends at 23:59. I live in Glasgow (I go from Glasgow Central). I have Friday and Monday off, so I can leave from 06:00 on Friday and need to be home by 23:00 on Monday. I have a 26-30 Railcard. Overnight sleepers are fine, and so is flying.',
        late:
          'UPDATE (Wen, Thursday): "Change of plan, the registrar can only do 13:00, so please be at the venue by 12:30!" Use 12:30 instead of the time in the invite. Everything else stays the same.',
      },
    };
  },
  // 8. Belfast → Windermere: an overnight ferry, flights, a Monday-morning deadline.
  t08: () => {
    const s = new Scenario(4808, ['Fri 26 Jun', 'Sat 27 Jun', 'Sun 28 Jun', 'Mon 29 Jun'], [0, 3]);
    s.node('HOM', 'Home (Belfast city centre)', 0).node('BHD', 'Belfast City Airport', 0, true).node('BFT', 'Belfast ferry terminal', 0).node('LIV', 'Liverpool ferry terminal', 0)
      .node('LIM', 'Liverpool Lime Street', 8).node('MIA', 'Manchester Airport', 8, true).node('PRE', 'Preston', 6).node('OXN', 'Oxenholme', 5).node('WDM', 'Windermere', 0).node('VEN', 'Low Wray Boathouse (venue)', 0);
    s.w.home = 'HOM';
    s.svc('ferry', 'BFT', 'LIV', 0, '22:30', 480, 5500).svc('ferry', 'LIV', 'BFT', 2, '22:30', 480, 5900);
    s.svc('flight', 'BHD', 'MIA', 0, '17:05', 60, 4500).svc('flight', 'BHD', 'MIA', 1, '06:50', 60, 6200).svc('flight', 'BHD', 'MIA', 1, '08:40', 60, 7900);
    s.svc('flight', 'MIA', 'BHD', 2, '20:35', 60, 3800).svc('flight', 'MIA', 'BHD', 3, '06:55', 60, 5200);
    s.run('train', 'LIM', 'PRE', 1, ['07:20', '08:20', '09:20'], 52, 1400, 3);
    s.run('train', 'MIA', 'PRE', 0, ['18:40', '19:40', '20:40'], 70, 2100, 3);
    s.run('train', 'MIA', 'PRE', 1, ['08:15', '09:15', '10:15'], 70, 1800, 3);
    s.run('train', 'PRE', 'OXN', 0, ['19:58', '20:58', '21:58', '22:30'], 25, 1300, 2);
    s.run('train', 'PRE', 'OXN', 1, ['08:25', '09:25', '10:25', '11:25'], 25, 1200, 2);
    s.run('train', 'OXN', 'WDM', 0, ['20:35', '21:35', '22:40', '23:15'], 20, 650, 2);
    s.run('train', 'OXN', 'WDM', 1, ['08:55', '09:55', '10:55', '11:55', '12:25'], 20, 650, 2);
    s.run('train', 'WDM', 'OXN', 2, ['09:10', '11:10', '13:10', '15:10', '17:10'], 20, 650, 2);
    s.run('train', 'WDM', 'OXN', 3, ['05:20'], 20, 650, 2);
    s.run('train', 'OXN', 'PRE', 2, ['09:45', '11:45', '13:45', '15:45', '17:45'], 25, 1200, 2);
    s.run('train', 'OXN', 'PRE', 3, ['05:50'], 25, 1300, 2);
    s.run('train', 'PRE', 'LIM', 2, ['12:30', '14:30', '16:30', '18:30', '19:30'], 52, 1400, 3);
    s.run('train', 'PRE', 'MIA', 2, ['12:10', '14:10', '16:10', '17:40'], 70, 1900, 3);
    s.run('train', 'PRE', 'MIA', 3, ['06:20'], 70, 2400, 2);
    s.transfer('HOM', 'BHD', 20, 900, 'Taxi').transfer('HOM', 'BFT', 25, 1100, 'Taxi').transfer('LIV', 'LIM', 20, 0, 'Shuttle bus')
      .transfer('WDM', 'VEN', 20, 2400, 'Taxi').transfer('WDM', 'VEN', 45, 450, 'Lakeside bus + walk');
    s.hotel('Lakes Hostel (private room)', 'WDM', { Fri: 6800, Sat: 9200, Sun: 6400 }, '22:30')
      .hotel('Bowness Bay Guest House', 'WDM', { Fri: 8800, Sat: 9800, Sun: 7800 }, '23:59', { satNeedsTwoNights: true })
      .hotel('Boathouse Cottages', 'VEN', { Sat: 15000, Sun: 11000 }, '23:59')
      .hotel('Preston Station Inn', 'PRE', { Fri: 5900, Sat: 6900, Sun: 5600 }, '23:59');
    s.w.railcard = { ...RC };
    s.w.leaveFrom = s.at(0, '15:00');
    s.w.homeBy = s.at(3, '09:00');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '13:30'), leaveAfter: s.at(1, '23:00'), label: 'Low Wray Boathouse (the venue)' };
    return {
      w: s.finish(),
      difficulty: 'hard',
      story: {
        intro:
          'My flatmate Bongani’s wedding is at Low Wray Boathouse on Windermere in the Lake District, Saturday 27 June: be there by 13:30, finishes at 23:00. I live in Belfast city centre and can leave from 15:00 on Friday. I start work at 09:30 on Monday, so I must be back home by 09:00 on Monday morning at the latest. I have a 16-25 Railcard. I can fly, or take the overnight ferry to Liverpool and the train.',
      },
    };
  },
  // 9. Norwich → Isle of Wight: London terminals, a ferry the railcard doesn't cover, a hotel that closes, a check-in trap in Portsmouth.
  t09: () => {
    const s = new Scenario(4909, ['Fri 21 Aug', 'Sat 22 Aug', 'Sun 23 Aug'], [0]);
    s.node('NRW', 'Norwich', 5).node('LST', 'London Liverpool Street', 10).node('VIC', 'London Victoria Coach Station', 0).node('WAT', 'London Waterloo', 10)
      .node('PMH', 'Portsmouth Harbour', 5).node('RYD', 'Ryde Pier Head', 5).node('VEN', 'Appley Tower Pavilion (venue)', 0);
    s.w.home = 'NRW';
    s.run('train', 'NRW', 'LST', 0, ['12:30', '14:00', '16:00', '18:00'], 113, 3600, 3);
    s.run('train', 'NRW', 'LST', 1, ['06:00', '07:00', '08:00', '09:00'], 113, 2800, 3);
    s.svc('coach', 'NRW', 'VIC', 1, '06:30', 205, 1200).svc('coach', 'NRW', 'VIC', 0, '13:00', 210, 1500);
    s.run('train', 'WAT', 'PMH', 0, ['15:00', '16:30', '18:00', '20:00', '21:30'], 98, 3100, 3);
    s.run('train', 'WAT', 'PMH', 1, ['08:30', '09:00', '09:30', '10:00', '10:30', '11:00'], 98, 2600, 3);
    s.run('ferry', 'PMH', 'RYD', 0, hourly('17:15', '23:15'), 22, 1050, 0);
    s.run('ferry', 'PMH', 'RYD', 1, hourly('10:15', '13:15'), 22, 1050, 0);
    s.run('ferry', 'RYD', 'PMH', 1, ['22:45', '23:15'], 22, 1050, 0);
    s.run('ferry', 'RYD', 'PMH', 2, hourly('08:45', '15:45'), 22, 1050, 0);
    s.run('train', 'PMH', 'WAT', 2, ['08:15', '09:45', '11:15', '12:45', '14:15'], 98, 2800, 3);
    s.run('train', 'LST', 'NRW', 2, ['11:30', '13:00', '14:30', '16:30', '18:30'], 113, 3300, 3);
    s.svc('coach', 'VIC', 'NRW', 2, '14:00', 215, 1300).svc('coach', 'VIC', 'NRW', 2, '17:30', 210, 1600);
    s.transfer('LST', 'WAT', 25, 290, 'Tube').transfer('WAT', 'VIC', 20, 290, 'Tube').transfer('RYD', 'VEN', 15, 0, 'Walk along the seafront');
    s.hotel('The Seaview Rooms', 'RYD', {}, '23:59')
      .hotel('Ryde Esplanade Hotel', 'RYD', { Fri: 9800, Sat: 12600 }, '23:30')
      .hotel('Gunwharf Lodge', 'PMH', { Fri: 6200, Sat: 6500 }, '23:30')
      .hotel('Harbour Lights Hotel', 'PMH', { Fri: 7900, Sat: 8400 }, '01:00')
      .hotel('Waterloo Budget Rooms', 'WAT', { Fri: 7400, Sat: 8900 }, '01:00');
    s.w.railcard = { ...RC };
    s.w.leaveFrom = s.at(0, '12:00');
    s.w.homeBy = s.at(2, '22:30');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '13:30'), leaveAfter: s.at(1, '22:30'), label: 'Appley Tower Pavilion (the venue)' };
    const w = s.finish();
    return {
      w,
      missedLate: { ...w, hotels: w.hotels.map((h) => (h.id === 'H1' ? { ...h, prices: { Fri: 5500, Sat: 5500 } } : h)) },
      difficulty: 'hard',
      story: {
        intro:
          'I need to get to Malik and Zara’s wedding at Appley Tower Pavilion in Ryde on the Isle of Wight on Saturday 22 August. Arrive by 13:30, and it finishes at 22:30. I live in Norwich and can leave from 12:00 on Friday. I need to be home by 22:30 on Sunday. I have a Railcard. The couple got a guest rate of £55 a night at The Seaview Rooms in Ryde.',
        extra: ['The fast catamaran between Portsmouth Harbour and Ryde Pier Head counts as a ferry.'],
        staleRooms: { H1: { Fri: 5500, Sat: 5500 } },
        late: 'P.S. The Seaview Rooms just emailed: they have closed for refurbishment and cannot take any bookings that weekend, so that hotel is out. The other hotels are fine.',
      },
    };
  },
  // 10. Aberdeen → Canterbury over a long weekend: sleepers, flights, Monday peak times, a hotel that is full after all.
  t10: () => {
    const s = new Scenario(5010, ['Fri 28 Aug', 'Sat 29 Aug', 'Sun 30 Aug', 'Mon 31 Aug'], [0, 3]);
    s.node('ABD', 'Aberdeen', 8).node('ABZ', 'Aberdeen Airport', 0, true).node('EUS', 'London Euston', 15).node('STP', 'London St Pancras', 10)
      .node('LGW', 'Gatwick Airport', 8, true).node('CBW', 'Canterbury West', 5).node('VEN', 'Westgate Hall (venue)', 0);
    s.w.home = 'ABD';
    s.svc('sleeper', 'ABD', 'EUS', 0, '21:43', 595, 6200, 11500).svc('sleeper', 'EUS', 'ABD', 2, '21:15', 600, 6200, 11500);
    s.run('train', 'ABD', 'EUS', 0, ['07:52', '09:52', '11:52'], 445, 11900, 3);
    s.svc('flight', 'ABZ', 'LGW', 0, '18:05', 100, 6900).svc('flight', 'ABZ', 'LGW', 1, '06:20', 100, 8900).svc('flight', 'ABZ', 'LGW', 1, '08:00', 100, 5400);
    s.svc('flight', 'LGW', 'ABZ', 2, '19:10', 100, 7600).svc('flight', 'LGW', 'ABZ', 3, '07:30', 100, 5900).svc('flight', 'LGW', 'ABZ', 3, '17:45', 100, 4800);
    s.run('train', 'LGW', 'STP', 0, ['20:30', '21:00', '21:30'], 48, 1700, 2);
    s.run('train', 'LGW', 'STP', 1, ['08:15', '08:45', '09:15', '10:15', '10:45'], 48, 1600, 2);
    s.run('train', 'STP', 'CBW', 0, ['15:12', '17:12', '19:12', '21:12', '22:12'], 56, 4100, 2);
    s.run('train', 'STP', 'CBW', 1, ['07:42', '08:12', '09:12', '10:12', '10:42'], 56, 3500, 2);
    s.run('train', 'CBW', 'STP', 2, ['09:30', '12:30', '15:30', '17:30', '19:00'], 56, 3600, 2);
    s.run('train', 'CBW', 'STP', 3, ['06:15', '07:15', '09:45', '13:45', '16:00'], 56, 3900, 2);
    s.run('train', 'STP', 'LGW', 2, ['13:40', '16:10'], 48, 1700, 2);
    s.run('train', 'STP', 'LGW', 3, ['05:20', '07:40', '14:40', '15:40'], 48, 1900, 2);
    s.run('train', 'EUS', 'ABD', 2, ['10:00', '12:00'], 450, 11200, 3);
    s.run('train', 'EUS', 'ABD', 3, ['08:00', '10:00', '12:00'], 450, 11500, 3);
    s.transfer('ABD', 'ABZ', 30, 450, 'Airport bus').transfer('EUS', 'STP', 8, 0, 'Walk').transfer('CBW', 'VEN', 10, 0, 'Walk');
    s.hotel('Cathedral Lodge', 'CBW', { Fri: 9400, Sun: 7900 }, '23:00')
      .hotel('Westgate Gardens B&B', 'CBW', { Fri: 8600, Sat: 11800, Sun: 6900 }, '23:59', { satNeedsTwoNights: true })
      .hotel('Stour Riverside Rooms', 'CBW', { Fri: 10500, Sat: 13800, Sun: 9900 }, '01:00')
      .hotel('King’s Cross Capsule Hotel', 'STP', { Fri: 6900, Sat: 7900, Sun: 6400 }, '01:00');
    s.w.railcard = { ...RC };
    s.w.leaveFrom = s.at(0, '07:30');
    s.w.homeBy = s.at(3, '22:00');
    s.w.event = { node: 'VEN', arriveBy: s.at(1, '12:00'), leaveAfter: s.at(1, '23:00'), label: 'Westgate Hall (the venue)' };
    const w = s.finish();
    return {
      w,
      missedLate: { ...w, hotels: w.hotels.map((h) => (h.id === 'H1' ? { ...h, prices: { ...h.prices, Sat: 10900 } } : h)) },
      difficulty: 'hard',
      story: {
        intro:
          'Planning help please. My old school friend Talia is getting married at Westgate Hall in Canterbury on Saturday 29 August. It’s an early one: guests by 12:00, and the party ends at 23:00. I live in Aberdeen. It’s a long weekend for me: I can leave from 07:30 on Friday and must be home by 22:00 on Monday. I have a 26-30 Railcard. I’d consider the sleeper, normal trains or flying.',
        staleRooms: { H1: { Sat: 10900 } },
        late: 'Oh, and Cathedral Lodge rang back: there was a mix-up with a cancellation and they are FULL on Saturday night after all, so ignore their Saturday price in the table. Friday and Sunday nights there are still available.',
      },
    };
  },
};

/** What a careless planner would book: the cheapest plan when one rule is ignored or a late message is missed. */
export interface CarelessPlan {
  label: string;
  answer: Record<string, unknown>;
}

export function buildTripCases(): TripCase[] {
  return Object.entries(BUILDERS_FOR_DEBUG).map(([id, b]) => {
    const built = b();
    const { w, story, difficulty } = built;
    const maxSteps = built.maxSteps ?? 24;
    const sol = solveTrip(w, {}, maxSteps);
    const traps: string[] = [];
    const careless: CarelessPlan[] = [];
    for (const r of ['connections', 'railcardTimes', 'checkIn', 'flightTimes', 'minStay', 'expiry'] as const) {
      try {
        const relaxed = solveTrip(w, { [r]: true }, maxSteps);
        if (relaxed.optimum < sol.optimum) {
          traps.push(`${r} (careless optimum ${money(relaxed.optimum)})`);
          careless.push({ label: `ignores the ${r} rule`, answer: planAnswer(w, relaxed.best) });
        }
      } catch {
        /* no plan */
      }
    }
    if (built.missedLate) {
      const wrong = solveTrip(built.missedLate, {}, maxSteps);
      if (wrong.optimum !== sol.optimum || JSON.stringify(planAnswer(w, wrong.best)) !== JSON.stringify(planAnswer(w, sol.best))) {
        traps.push(`the late message (careless optimum ${money(wrong.optimum)})`);
        careless.push({ label: 'misses the late message', answer: planAnswer(w, wrong.best) });
      }
    }
    const ref = planAnswer(w, sol.best);
    // A typical arithmetic slip: the right trip, but the total quoted at full fare (railcard discount forgotten).
    const fullFare = evaluate({ ...w, railcard: null }, sol.best.steps, sol.best.hotels);
    // A workable but dearer trip: planned as if there were no railcard (then priced properly).
    if (w.railcard) {
      const noRc = solveTrip({ ...w, railcard: null }, {}, maxSteps);
      const real = evaluate(w, noRc.best.steps, noRc.best.hotels);
      if (real !== null && real !== sol.optimum) careless.push({ label: 'plans as if there were no railcard (workable, dearer)', answer: planAnswer(w, noRc.best, real) });
    }
    if (fullFare !== null && fullFare !== sol.optimum) careless.push({ label: 'right trip, total quoted without the railcard discount', answer: planAnswer(w, sol.best, fullFare) });
    return {
      id,
      difficulty,
      world: w,
      prompt: tripPrompt(w, story),
      maxSteps,
      reference: ref,
      optimum: sol.optimum,
      optimalPlans: sol.optimalPlans,
      careless,
      notes: `[${difficulty}] Cheapest workable trip ${money(sol.optimum)}, proven by an exhaustive branch-and-bound search over every itinerary and hotel choice (${sol.candidates} complete plans checked, ${sol.optimalPlans} optimal). One optimal plan: ${JSON.stringify(ref)}. Traps that change the answer if missed: ${traps.join('; ') || 'none'}.`,
    };
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  for (const c of buildTripCases()) {
    console.log(c.id, c.notes);
    if (process.argv.includes('--prompt')) console.log(c.prompt);
  }
}
