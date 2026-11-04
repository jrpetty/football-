/**
 * Weekend Away (life.trip-planner): replays an itinerary against the timetable, the connection rules, the
 * railcard rules, the hotels' rules and the traveller's fixed appointment, and prices it.
 *
 * Times are minutes from 00:00 on day 0 of the trip (day 0 is the first day in `days`). Money is in pence.
 */
import type { PlanCheck, PlanVerifier, VerifierItem } from './types.ts';
import { field, gbp, readId, readList, readPence } from './common.ts';

export type TripMode = 'train' | 'sleeper' | 'coach' | 'flight' | 'ferry' | 'bus';

export interface TripService {
  id: string;
  mode: TripMode;
  from: string;
  to: string;
  dep: number;
  arr: number;
  /** Fare in pence (railcard discount applies to it when allowed). */
  fare: number;
  /** Sleeper berth or other supplement in pence: never discounted. */
  supplement?: number;
}

export interface TripTransfer {
  id: string;
  from: string;
  to: string;
  mins: number;
  cost: number;
  label: string;
}

export interface TripHotel {
  id: string;
  name: string;
  node: string;
  /** Price in pence for each night it can be booked, by night name ("Fri", "Sat", …). Missing = full. */
  prices: Record<string, number>;
  /** Latest check-in, minutes after midnight on the first night's date (may exceed 1440, e.g. 1500 = 01:00). */
  latestCheckIn: number;
  /** A Saturday night can only be booked together with the night before or after it. */
  satNeedsTwoNights?: boolean;
}

export interface TripWorld {
  home: string;
  nodes: Record<string, { name: string; minConnect: number; airport?: boolean }>;
  /** Display names of the days, day 0 first, e.g. ["Fri 12 Jun", "Sat 13 Jun", …]. */
  days: string[];
  /** Short night names, night d = the night after day d (checked at 03:00 on day d + 1). */
  nights: string[];
  /** Day indexes that are Monday–Friday. */
  weekdays: number[];
  services: TripService[];
  transfers: TripTransfer[];
  hotels: TripHotel[];
  railcard: null | {
    /** Modes the discount applies to. */
    modes: TripMode[];
    /** No discount on weekday departures before this many minutes after midnight (e.g. 570 = 09:30). */
    weekdayNotBefore: number;
    /** Valid only for departures before this absolute minute (railcard expiry), when set. */
    validBefore?: number;
  };
  /** Minutes needed at an airport before a flight leaves, and after one lands before anything else. */
  flightCheckIn: number;
  flightExit: number;
  /** Leave home no earlier than this. */
  leaveFrom: number;
  /** Be back home by this. */
  homeBy: number;
  /** The appointment: be at `node` from `arriveBy` until `leaveAfter`. */
  event: { node: string; arriveBy: number; leaveAfter: number; label: string };
}

export const NIGHT_CHECK = 180; // 03:00

/** Fare for a service under the railcard rules (pence). */
export function tripFare(w: TripWorld, s: TripService): { cost: number; discounted: boolean } {
  const rc = w.railcard;
  const day = Math.floor(s.dep / 1440);
  const tod = s.dep - day * 1440;
  let fare = s.fare;
  let discounted = false;
  if (rc && rc.modes.includes(s.mode) && !(w.weekdays.includes(day) && tod < rc.weekdayNotBefore) && (rc.validBefore === undefined || s.dep < rc.validBefore)) {
    fare = Math.round((s.fare * 2) / 3);
    discounted = true;
  }
  return { cost: fare + (s.supplement ?? 0), discounted };
}

export function clock(t: number, days: string[]): string {
  const d = Math.floor(t / 1440);
  const m = t - d * 1440;
  return `${days[d]?.slice(0, 3) ?? `day ${d}`} ${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

export type Leg = { kind: 'service'; s: TripService } | { kind: 'transfer'; t: TripTransfer };

/** A stay at a place: from `at` (arrival) to `until` (departure, or Infinity at the end). */
interface Stay {
  node: string;
  at: number;
  until: number;
}

/** The 03:00 check time of night d (the night after day d). */
export function nightCheck(d: number): number {
  return (d + 1) * 1440 + NIGHT_CHECK;
}

function readNight(v: unknown, nights: string[]): string | null {
  const s = typeof v === 'string' ? v.trim().toLowerCase() : '';
  const hits = nights.filter((n) => s.startsWith(n.toLowerCase().slice(0, 3)));
  return hits.length === 1 ? hits[0]! : null;
}

/**
 * When a taxi, walk or tube ride leaving `node` starts (the rule stated in the prompt): straight away; but at the
 * appointment not before it ends; from home just in time for your next timed step; and where you have a hotel
 * booked for the coming night, the next morning, just in time for your next timed step.
 */
export function transferStart(w: TripWorld, legs: Leg[], i: number, node: string, at: number, flight: boolean, booked: Map<string, string[]>): number {
  let base = flight ? at + w.flightExit : at;
  if (node === w.event.node && at <= w.event.arriveBy) base = Math.max(base, w.event.leaveAfter);
  if (node === w.home) {
    const jit = justInTime(w, legs, i, base);
    return jit !== null && jit > base ? jit : base;
  }
  let d = 0;
  while (d < w.nights.length && nightCheck(d) <= base) d++;
  const night = w.nights[d];
  if (night && (booked.get(night) ?? []).some((id) => w.hotels.find((h) => h.id === id)?.node === node)) {
    const jit = justInTime(w, legs, i, base);
    if (jit !== null && jit > nightCheck(d)) return jit;
  }
  return base;
}

/** Latest start of the transfers from leg i on that still reaches the next timed step (or the appointment); null if none follows. */
function justInTime(w: TripWorld, legs: Leg[], i: number, base: number): number | null {
  let mins = 0;
  for (let j = i; j < legs.length; j++) {
    const leg = legs[j]!;
    if (leg.kind === 'service') return leg.s.dep - (leg.s.mode === 'flight' ? w.flightCheckIn : 0) - mins;
    mins += leg.t.mins;
    if (leg.t.to === w.event.node && w.event.arriveBy > base) return w.event.arriveBy - mins;
  }
  return null;
}

export function checkTrip(w: TripWorld, answer: Record<string, unknown>): PlanCheck {
  const items: VerifierItem[] = [];
  const fail = (label: string, detail?: string) => items.push({ label, passed: false, hard: true, detail });
  const ok = (label: string, detail?: string) => items.push({ label, passed: true, hard: true, detail });
  const place = (n: string) => w.nodes[n]?.name ?? n;
  const ids = [...w.services.map((s) => s.id), ...w.transfers.map((t) => t.id)];
  const byId = new Map<string, Leg>([...w.services.map((s) => [s.id, { kind: 'service', s }] as const), ...w.transfers.map((t) => [t.id, { kind: 'transfer', t }] as const)]);
  const stated = readPence(field(answer, 'total', 'totalcost', 'totalgbp', 'cost', 'totalprice'));

  // ── Read the journey ──
  const rawLegs = readList(field(answer, 'journey', 'legs', 'itinerary'));
  const legs: Leg[] = [];
  if (!rawLegs || rawLegs.length === 0) {
    fail('the journey lists the services and transfers in order', 'no journey given');
    return { feasible: false, items, stated };
  }
  for (const raw of rawLegs) {
    const id = readId(raw, ids);
    if (!id) {
      fail('every journey step is a listed service or transfer', `unknown or ambiguous step ${JSON.stringify(raw).slice(0, 40)}`);
      return { feasible: false, items, stated };
    }
    legs.push(byId.get(id)!);
  }

  // ── Read the hotel bookings ──
  const hotelIds = w.hotels.map((h) => h.id);
  const hotelNames = new Map(w.hotels.map((h) => [h.id, h.name]));
  const booked = new Map<string, string[]>(); // night → hotel ids
  const rawHotels = field(answer, 'hotels', 'hotel', 'accommodation');
  const hotelList = rawHotels === undefined || rawHotels === null ? [] : Array.isArray(rawHotels) ? rawHotels : [rawHotels];
  for (const h of hotelList) {
    if (typeof h === 'string' && /^\s*(none|no hotels?|n\/a|-)?\s*$/i.test(h)) continue;
    const id = readId(field(h, 'hotel', 'id', 'name') ?? h, hotelIds, hotelNames);
    const rawNights = readList(field(h, 'nights', 'night'));
    const nights = (rawNights ?? []).map((n) => readNight(n, w.nights));
    if (!id || !rawNights || rawNights.length === 0 || nights.some((n) => !n)) {
      fail('every hotel booking names a listed hotel and its nights', JSON.stringify(h).slice(0, 60));
      return { feasible: false, items, stated };
    }
    for (const n of new Set(nights as string[])) booked.set(n, [...(booked.get(n) ?? []), id]);
  }

  // ── Replay ──
  let node = w.home;
  let at = w.leaveFrom;
  let via: 'start' | 'service' | 'flight' | 'transfer' = 'start';
  const stays: Stay[] = [];
  const rides: Array<{ id: string; from: number; to: number; overnight: boolean }> = [];
  let cost = 0;
  for (let i = 0; i < legs.length; i++) {
    const leg = legs[i]!;
    const id = leg.kind === 'service' ? leg.s.id : leg.t.id;
    const from = leg.kind === 'service' ? leg.s.from : leg.t.from;
    if (from !== node) {
      fail('each step starts where the previous one ended', `${id} leaves from ${place(from)}, but you are at ${place(node)}`);
      return { feasible: false, items, stated };
    }
    if (leg.kind === 'service') {
      const s = leg.s;
      let earliest = at;
      let why = '';
      if (via === 'service' || via === 'flight') {
        earliest = at + (w.nodes[node]?.minConnect ?? 0);
        why = `${w.nodes[node]?.minConnect ?? 0} min to change at ${place(node)}`;
      }
      if (via === 'flight' && at + w.flightExit > earliest) {
        earliest = at + w.flightExit;
        why = `${w.flightExit} min to get through the airport after landing`;
      }
      if (s.mode === 'flight' && at + w.flightCheckIn > earliest) {
        earliest = at + w.flightCheckIn;
        why = `at the airport ${w.flightCheckIn} min before a flight`;
      }
      if (s.dep < earliest) {
        fail('every connection can be made', `${s.id} leaves ${clock(s.dep, w.days)}, but you can only board from ${clock(earliest, w.days)}${why ? ` (${why})` : ''}`);
        return { feasible: false, items, stated };
      }
      stays.push({ node, at, until: s.dep });
      rides.push({ id: s.id, from: s.dep, to: s.arr, overnight: true });
      cost += tripFare(w, s).cost;
      node = s.to;
      at = s.arr;
      via = s.mode === 'flight' ? 'flight' : 'service';
    } else {
      const t = leg.t;
      const start = transferStart(w, legs, i, node, at, via === 'flight', booked);
      stays.push({ node, at, until: start });
      rides.push({ id: t.id, from: start, to: start + t.mins, overnight: false });
      cost += t.cost;
      node = t.to;
      at = start + t.mins;
      via = 'transfer';
    }
  }
  stays.push({ node, at, until: Infinity });
  ok('every connection can be made');

  // ── Home in time ──
  if (node !== w.home) fail('the journey ends at home', `it ends at ${place(node)}`);
  else if (at > w.homeBy) fail('home in time', `home ${clock(at, w.days)}, needed by ${clock(w.homeBy, w.days)}`);
  else ok('home in time', `home ${clock(at, w.days)}`);

  // ── The appointment ──
  const ev = w.event;
  if (stays.some((s) => s.node === ev.node && s.at <= ev.arriveBy && s.until >= ev.leaveAfter)) ok(`at ${ev.label} on time and for the whole of it`);
  else {
    const visit = stays.find((s) => s.node === ev.node);
    fail(`at ${ev.label} on time and for the whole of it`, visit ? `there ${clock(visit.at, w.days)}–${Number.isFinite(visit.until) ? clock(visit.until, w.days) : 'the end'}, needed ${clock(ev.arriveBy, w.days)}–${clock(ev.leaveAfter, w.days)}` : 'never gets there');
  }

  // ── Nights ──
  for (let d = 0; d < w.nights.length; d++) {
    const night = w.nights[d]!;
    const c = nightCheck(d);
    const mine = booked.get(night) ?? [];
    const ride = rides.find((r) => r.from < c && r.to > c);
    const stay = stays.find((s) => s.at <= c && s.until > c);
    if (ride && !ride.overnight) {
      fail('a bed (or an overnight service) every night away from home', `you would be on ${ride.id} at 03:00 on ${w.days[d + 1]}`);
      continue;
    }
    if (ride || !stay || stay.node === w.home) {
      if (mine.length) fail('no hotel night booked that you do not sleep in', `${mine.join(', ')} on ${night} night`);
      continue;
    }
    if (mine.length > 1) {
      fail('no hotel night booked that you do not sleep in', `${mine.length} hotels booked for ${night} night`);
      continue;
    }
    const hotel = w.hotels.find((h) => h.id === mine[0]);
    if (!hotel || hotel.node !== stay.node) {
      fail('a bed (or an overnight service) every night away from home', `${night} night at ${place(stay.node)} with no hotel booked there`);
      continue;
    }
    if (hotel.prices[night] === undefined) {
      fail('every booked hotel night is available', `${hotel.name} is full on ${night} night`);
      continue;
    }
    const prev = w.nights[d - 1];
    const continuing = prev !== undefined && (booked.get(prev) ?? []).includes(hotel.id);
    if (!continuing && stay.at > d * 1440 + hotel.latestCheckIn) {
      fail(`check in at ${hotel.name} in time`, `arrive ${clock(stay.at, w.days)}, latest check-in ${clock(d * 1440 + hotel.latestCheckIn, w.days)}`);
      continue;
    }
    if (hotel.satNeedsTwoNights && night === 'Sat' && ![w.nights[d - 1], w.nights[d + 1]].some((n) => n !== undefined && (booked.get(n) ?? []).includes(hotel.id))) {
      fail(`${hotel.name}: a Saturday night needs a two-night stay`, 'only Saturday booked');
      continue;
    }
    ok(`${night} night: ${hotel.name}`);
  }
  for (const [night, list] of booked) {
    if (!w.nights.includes(night)) continue;
    for (const id of list) cost += w.hotels.find((h) => h.id === id)!.prices[night] ?? 0;
  }

  const feasible = items.every((i) => i.passed || !i.hard);
  return { feasible, value: cost, stated, items };
}

export const trip: PlanVerifier<TripWorld> = {
  kind: 'plan',
  name: 'trip',
  noun: 'itinerary',
  objective: 'min',
  answerKeys: ['journey', 'legs', 'itinerary'],
  quotesTotal: true,
  marking: [
    'The itinerary is replayed step by step against the timetable: every train, coach or flight must exist, start where the last step ended and be catchable under the connection rules.',
    'It must reach the appointment in time and stay for all of it, find a bed (or an overnight service) every night away from home, keep every hotel rule and get home by the deadline. Breaking any rule scores 0.',
    'The trip is priced exactly (railcard discounts only where allowed). The cheapest possible trip, proven by a computer search of every itinerary, scores 100; a workable but dearer trip scores 50 × (cheapest ÷ its cost)², always under 50.',
    'The total quoted must be the real total: a missing or wrong total halves the score.',
  ],
  formatValue: gbp,
  check: checkTrip,
};
