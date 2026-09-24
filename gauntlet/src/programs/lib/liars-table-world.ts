/**
 * The Liar's Table — seeded world generation, deterministic testimony and a
 * brute-force consistency solver that proves every generated case has exactly
 * one viable culprit.
 *
 * World model: three (or four) half-hour slots from 8:30. Every suspect is in
 * exactly one room per slot. The thief is alone in the object room during the
 * theft slot and claims to have been somewhere else. Innocents tell the truth;
 * some innocents misremember (and hedge) a room at a slot that is not the
 * theft slot. Hard cases add suspects, a fourth slot, and a door log with a
 * gap, so the theft slot itself must be deduced.
 */
import type { Rng } from '../../core/types.ts';

/** Slot labels; a world uses the first `slotCount` of them. */
export const SLOTS = ['8:30', '9:00', '9:30', '10:00'] as const;
export const SLOT_START_MIN = [20 * 60 + 30, 21 * 60, 21 * 60 + 30, 22 * 60];

export type LieVariant = 'companion' | 'cctv' | 'witness' | 'receipt';
export const LIE_VARIANTS: readonly LieVariant[] = ['companion', 'cctv', 'witness', 'receipt'];

export type EvidenceId = 'DOOR LOG' | 'WITNESS' | 'RECEIPT' | 'CCTV';
export const EVIDENCE_IDS: readonly EvidenceId[] = ['DOOR LOG', 'WITNESS', 'RECEIPT', 'CCTV'];

export interface Suspect {
  name: string;
  blurb: string;
}

export interface Purchase {
  name: string;
  slot: number;
  minute: number;
  item: string;
}

export interface Mistake {
  name: string;
  slot: number;
  claimedRoom: string;
  trueRoom: string;
}

export interface LtWorld {
  host: string;
  hostShort: string;
  venue: string;
  object: string;
  /** Topic keyword, e.g. "BROOCH" (asked as "THE BROOCH"). */
  objectKey: string;
  objectRoom: string;
  /** Number of half-hour slots in the theft window (3 or 4). */
  slotCount: number;
  /** The five social rooms. */
  rooms: string[];
  roles: { adjacent: string; bar: string; witness: string; cam1: string; cam2: string };
  staff: { title: string; name: string };
  suspects: Suspect[];
  culprit: string;
  theftSlot: number;
  variant: LieVariant;
  /** Where the thief claims to have been during the theft slot. */
  claimedRoom: string;
  /** Ground truth: room per slot for every suspect. */
  truth: Record<string, string[]>;
  /** What each suspect says: room per slot. */
  claims: Record<string, string[]>;
  /** Slots where a suspect is honestly unsure (and says so). */
  hedged: Record<string, boolean[]>;
  purchases: Purchase[];
  /** Drink the thief claims to have bought (receipt variant only). */
  fakePurchase: string | null;
  cctvGap: { room: string; slot: number };
  doorMinute: number;
  /** When set, the door log is offline for these consecutive slots (one of them is the theft slot). */
  doorGap: { from: number; to: number } | null;
  /** First honest mistake (kept for reporting compatibility). */
  mistaken: Mistake | null;
  /** Every honest (hedged) mistake. */
  mistakes: Mistake[];
  /** The contradiction that exposes the thief. Sources are suspect names and/or evidence ids. */
  contradiction: { slot: number; claimedRoom: string; sources: string[] };
}

const HOSTS: Array<[string, string]> = [
  ['Lady Honoria Harrow', 'Lady Harrow'],
  ['Sir Mortimer Vane', 'Sir Mortimer'],
  ['Countess Ilse Albescu', 'the Countess'],
  ['Mr Oswin Thorne', 'Mr Thorne'],
  ['Dame Cressida Lark', 'Dame Cressida'],
];
const VENUES = ['Harrowgate Hall', 'Wyvern Manor', 'Blackmere House', 'Ashcombe Lodge', 'Greystone Abbey'];
const OBJECTS: Array<[string, string]> = [
  ['the Sapphire Brooch', 'BROOCH'],
  ['the Gold Pocket Watch', 'WATCH'],
  ['the Jade Figurine', 'FIGURINE'],
  ['the Ruby Necklace', 'NECKLACE'],
  ['the First-Edition Atlas', 'ATLAS'],
  ['the Silver Chalice', 'CHALICE'],
];
const OBJECT_ROOMS = ['Study', 'Map Room', 'Trophy Room'];
/** Each social room has one distinctive keyword (used to check accusation reasons). */
export const ROOM_KEYWORDS: Record<string, string> = {
  Library: 'library',
  Conservatory: 'conservatory',
  'Billiard Room': 'billiard',
  Gallery: 'gallery',
  'Music Room': 'music',
  Terrace: 'terrace',
  'Drawing Room': 'drawing',
  Kitchen: 'kitchen',
  'Wine Cellar': 'cellar',
  Orangery: 'orangery',
};
const SOCIAL_ROOMS = Object.keys(ROOM_KEYWORDS);
const NAMES = [
  'Ada', 'Victor', 'Beatrix', 'Cyril', 'Delphine', 'Edmund', 'Felicity', 'Gideon', 'Harriet', 'Ignatius',
  'Juno', 'Lionel', 'Marguerite', 'Nigel', 'Odette', 'Percival', 'Rosalind', 'Silas', 'Theodora', 'Wendell',
];
const BLURBS = [
  "the host's niece", 'a retired colonel', 'a jazz pianist', "the host's business partner", 'a society columnist',
  'a jeweller from the city', 'the family solicitor', 'a visiting archaeologist', 'a racing driver', 'a portrait painter',
];
const STAFF: Array<[string, string]> = [
  ['the butler', 'Mr Pryce'],
  ['the maid', 'Elsie'],
  ['the footman', 'Tobias'],
  ['the housekeeper', 'Mrs Dunmore'],
];
const DRINKS = ['gin fizz', 'brandy', 'sherry', 'champagne cocktail', 'whisky soda', 'port', 'sidecar', 'lemonade'];

export function roomPhrase(room: string): string {
  return room === 'Terrace' ? 'on the Terrace' : `in the ${room}`;
}

export function slotTime(slot: number, minute = 0): string {
  const total = SLOT_START_MIN[0]! + 30 * slot + minute;
  const h = Math.floor(total / 60) - 12;
  const m = total % 60;
  return `${h}:${String(m).padStart(2, '0')}`;
}

/** Slot labels of this world. */
export function slotsOf(w: Pick<LtWorld, 'slotCount'>): string[] {
  return SLOTS.slice(0, w.slotCount);
}

/** When the theft was discovered (end of the window), e.g. "10:00". */
export function endTime(w: Pick<LtWorld, 'slotCount'>): string {
  return slotTime(w.slotCount);
}

function listNames(names: string[]): string {
  if (names.length === 0) return '';
  if (names.length === 1) return names[0]!;
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

export interface WorldOptions {
  /** Force a lie variant (default: seeded). */
  variant?: LieVariant;
  /** Seeded choice restricted to these variants (default: all four). */
  variantPool?: readonly LieVariant[];
  /** true / false / 'auto' (seeded, ~50%). Ignored when mistakenCount is set. */
  mistakenWitness?: boolean | 'auto';
  /** Exact number of hedged honest mistakes (overrides mistakenWitness). */
  mistakenCount?: number;
  /** Number of suspects (default 5). */
  suspects?: number;
  /** Number of half-hour slots, 3 or 4 (default 3). */
  slots?: number;
  /** The door log is offline across two slots, one of them the theft slot (default false). */
  doorLogGap?: boolean;
}

/** Generates a world with a provably unique culprit (regenerates on the rare failure). */
export function generateWorld(rng: Rng, opts: WorldOptions = {}): LtWorld {
  for (let attempt = 0; attempt < 200; attempt++) {
    const world = tryGenerate(rng.fork(`world-${attempt}`), opts);
    const viable = viableCulprits(world);
    if (viable.length === 1 && viable[0] === world.culprit) return world;
  }
  throw new Error('liars-table: could not generate a uniquely solvable world');
}

function tryGenerate(rng: Rng, opts: WorldOptions): LtWorld {
  // NOTE: the order of rng draws for the default options (5 suspects, 3 slots, no gap)
  // must never change, so published worlds stay identical. Extra draws go at the end.
  const nSuspects = Math.max(3, Math.min(NAMES.length, opts.suspects ?? 5));
  const slotCount = opts.slots === 4 ? 4 : 3;
  const slotIdx = Array.from({ length: slotCount }, (_, i) => i);
  const [host, hostShort] = rng.pick(HOSTS);
  const venue = rng.pick(VENUES);
  const [object, objectKey] = rng.pick(OBJECTS);
  const objectRoom = rng.pick(OBJECT_ROOMS);
  const rooms = rng.shuffle(SOCIAL_ROOMS).slice(0, 5);
  const [adjacent, bar, witness, cam1, cam2] = rng.shuffle(rooms) as [string, string, string, string, string];
  const [staffTitle, staffName] = rng.pick(STAFF);
  const names = rng.shuffle(NAMES).slice(0, nSuspects);
  const blurbs = rng.shuffle(BLURBS);
  const suspects = names.map((name, i) => ({ name, blurb: blurbs[i % blurbs.length]! }));
  const culprit = rng.pick(names);
  const innocents = names.filter((n) => n !== culprit);
  const theftSlot = rng.int(0, slotCount - 1);
  // Hard cases only (extra draw): the door log is offline across two slots including the theft slot.
  const gapFrom = opts.doorLogGap
    ? theftSlot === 0
      ? 0
      : theftSlot === slotCount - 1
        ? theftSlot - 1
        : rng.chance(0.5)
          ? theftSlot - 1
          : theftSlot
    : null;
  const variant = opts.variant ?? rng.pick(opts.variantPool && opts.variantPool.length > 0 ? opts.variantPool : LIE_VARIANTS);
  const claimedRoom =
    variant === 'companion' ? rng.pick([bar, cam2]) : variant === 'cctv' ? cam1 : variant === 'witness' ? witness : bar;

  // Ground truth.
  const truth: Record<string, string[]> = {};
  for (const n of names) truth[n] = slotIdx.map(() => rng.pick(rooms));
  truth[culprit]![theftSlot] = objectRoom;
  const inRoom = (room: string, slot: number, who: readonly string[]) => who.filter((n) => truth[n]![slot] === room);
  if (variant === 'companion' && inRoom(claimedRoom, theftSlot, innocents).length === 0) {
    truth[rng.pick(innocents)]![theftSlot] = claimedRoom;
  }
  if (variant === 'cctv') {
    for (const n of inRoom(cam1, theftSlot, innocents)) truth[n]![theftSlot] = rng.pick(rooms.filter((r) => r !== cam1));
  }

  const claims: Record<string, string[]> = {};
  const hedged: Record<string, boolean[]> = {};
  for (const n of names) {
    claims[n] = truth[n]!.slice();
    hedged[n] = slotIdx.map(() => false);
  }
  claims[culprit]![theftSlot] = claimedRoom;

  // Honest mistakes: an innocent misremembers (and hedges) a room at a non-theft slot.
  const mistakes: Mistake[] = [];
  const addMistake = (name: string, decoySlot?: number) => {
    const slot = decoySlot ?? rng.pick(slotIdx.filter((s) => s !== theftSlot));
    const trueRoom = truth[name]![slot]!;
    // A decoy claims a room where evidence or other guests visibly contradict it.
    const exposed = rooms.filter((r) => r !== trueRoom && (r === witness || r === cam1 || names.some((n) => n !== name && truth[n]![slot] === r)));
    const wrong = rng.pick(decoySlot !== undefined && exposed.length > 0 ? exposed : rooms.filter((r) => r !== trueRoom));
    claims[name]![slot] = wrong;
    hedged[name]![slot] = true;
    mistakes.push({ name, slot, claimedRoom: wrong, trueRoom });
  };
  if (opts.mistakenCount === undefined) {
    const wantMistake = opts.mistakenWitness === 'auto' || opts.mistakenWitness === undefined ? rng.chance(0.5) : opts.mistakenWitness;
    if (wantMistake) addMistake(rng.pick(innocents));
  } else {
    const pool = rng.shuffle(innocents);
    // With a door-log gap, the first mistake is a decoy in the other candidate theft slot.
    const decoySlot = gapFrom === null ? undefined : gapFrom === theftSlot ? theftSlot + 1 : gapFrom;
    for (let i = 0; i < Math.min(opts.mistakenCount, pool.length); i++) addMistake(pool[i]!, i === 0 ? decoySlot : undefined);
  }

  // Bar receipts (true purchases only).
  const purchases: Purchase[] = [];
  for (let slot = 0; slot < slotCount; slot++) {
    for (const n of names) {
      if (truth[n]![slot] === bar && rng.chance(0.6)) {
        purchases.push({ name: n, slot, minute: rng.int(2, 26), item: rng.pick(DRINKS) });
      }
    }
  }
  purchases.sort((a, b) => a.slot - b.slot || a.minute - b.minute);
  const fakePurchase = variant === 'receipt' ? rng.pick(DRINKS) : null;

  const cctvGap = { room: cam2, slot: theftSlot };
  const doorMinute = rng.int(3, 21);

  // Hard cases: the door log is offline across two slots, and the camera gap sits in either of them.
  let doorGap: LtWorld['doorGap'] = null;
  if (gapFrom !== null) {
    doorGap = { from: gapFrom, to: gapFrom + 1 };
    cctvGap.slot = rng.pick([gapFrom, gapFrom + 1]);
  }

  const sources: string[] = inRoom(claimedRoom, theftSlot, innocents);
  if ((claimedRoom === cam1 || claimedRoom === cam2) && !(cctvGap.room === claimedRoom && cctvGap.slot === theftSlot)) {
    sources.push('CCTV');
  }
  if (claimedRoom === witness) sources.push('WITNESS');
  if (fakePurchase) sources.push('RECEIPT');

  return {
    host,
    hostShort,
    venue,
    object,
    objectKey,
    objectRoom,
    slotCount,
    rooms,
    roles: { adjacent, bar, witness, cam1, cam2 },
    staff: { title: staffTitle, name: staffName },
    suspects,
    culprit,
    theftSlot,
    variant,
    claimedRoom,
    truth,
    claims,
    hedged,
    purchases,
    fakePurchase,
    cctvGap,
    doorMinute,
    doorGap,
    mistaken: mistakes[0] ?? null,
    mistakes,
    contradiction: { slot: theftSlot, claimedRoom, sources },
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Testimony
// ─────────────────────────────────────────────────────────────────────────────

/** Who a suspect SAYS was with them at a slot (null when they honestly don't remember). */
export function claimedCompanions(w: LtWorld, who: string, slot: number): string[] | null {
  if (w.hedged[who]![slot]) return null;
  const room = w.claims[who]![slot]!;
  const others = w.suspects.map((s) => s.name).filter((n) => n !== who);
  if (who === w.culprit && slot === w.theftSlot) {
    // The thief names the people who really were in the room they claim.
    return others.filter((n) => w.truth[n]![slot] === room);
  }
  return others.filter((n) => w.truth[n]![slot] === room && !(n === w.culprit && slot === w.theftSlot));
}

function hedgeText(w: LtWorld, who: string, slot: number): string {
  return `At ${slotTime(slot)} I believe I was ${roomPhrase(w.claims[who]![slot]!)} — though honestly I may be muddling the times there, so don't hold me to it. I couldn't tell you who else was about.`;
}

export function answerAlibi(w: LtWorld, who: string): string {
  const parts = slotsOf(w).map((_, slot) => {
    const phrase = `at ${slotTime(slot)} ${roomPhrase(w.claims[who]![slot]!)}`;
    return w.hedged[who]![slot] ? `${phrase} (I think — I may be muddling that one)` : phrase;
  });
  return `"After the tour I was ${parts.slice(0, -1).join(', ')}, and ${parts[parts.length - 1]}."`;
}

/** Slots at which a suspect says they signed for a drink at the bar. */
export function claimedPurchaseSlots(w: LtWorld, who: string): number[] {
  const slots = w.purchases.filter((p) => p.name === who).map((p) => p.slot);
  if (who === w.culprit && w.fakePurchase) slots.push(w.theftSlot);
  return slots;
}

export function answerTime(w: LtWorld, who: string, slot: number): string {
  if (w.hedged[who]![slot]) return `"${hedgeText(w, who, slot)}"`;
  const room = w.claims[who]![slot]!;
  const comp = claimedCompanions(w, who, slot) ?? [];
  let s = `At ${slotTime(slot)} I was ${roomPhrase(room)}`;
  s += comp.length > 0 ? ` with ${listNames(comp)}.` : '. Nobody else was there.';
  const bought = w.purchases.find((p) => p.name === who && p.slot === slot);
  if (bought) s += ` I signed for a ${bought.item} at the bar.`;
  if (who === w.culprit && slot === w.theftSlot && w.fakePurchase) s += ` I signed for a ${w.fakePurchase} at the bar.`;
  if (who !== w.culprit && slot === w.theftSlot && room === w.roles.adjacent) {
    s += ` Around then I heard the ${w.objectRoom} door open and shut next door.`;
  }
  return `"${s}"`;
}

/** Slots at which `who` says they saw `other`. */
export function claimedSightings(w: LtWorld, who: string, other: string): number[] {
  const out: number[] = [];
  for (let slot = 0; slot < w.slotCount; slot++) {
    const comp = claimedCompanions(w, who, slot);
    if (comp && comp.includes(other)) out.push(slot);
  }
  return out;
}

export function answerAbout(w: LtWorld, who: string, other: string): string {
  const seen = claimedSightings(w, who, other);
  if (seen.length === 0) return `"I didn't see ${other} at all between 8:30 and ${endTime(w)}."`;
  const parts = seen.map((slot) => `${roomPhrase(w.claims[who]![slot]!)} at ${slotTime(slot)}`);
  const missing = slotsOf(w).map((_, i) => i).filter((i) => !seen.includes(i) && !w.hedged[who]![i]);
  const tail = missing.length > 0 ? ` I didn't see ${other} at ${missing.map((i) => slotTime(i)).join(' or ')}.` : '';
  return `"I saw ${other} ${listNames(parts)}.${tail}"`;
}

export function answerObject(w: LtWorld, who: string): string {
  let s = `I saw ${w.object} at the 8:00 tour of the ${w.objectRoom}, like everyone, and I never went back in there.`;
  const slot = w.theftSlot;
  if (who !== w.culprit && w.truth[who]![slot] === w.roles.adjacent && !w.hedged[who]![slot]) {
    s += ` I was ${roomPhrase(w.roles.adjacent)} at ${slotTime(slot)} and heard the ${w.objectRoom} door open and shut next door.`;
  }
  return `"${s}"`;
}

function occupants(w: LtWorld, room: string, slot: number): string[] {
  return w.suspects.map((s) => s.name).filter((n) => w.truth[n]![slot] === room);
}

function occupantText(names: string[]): string {
  return names.length === 0 ? 'nobody' : listNames(names);
}

export function evidenceText(w: LtWorld, id: EvidenceId): string {
  switch (id) {
    case 'DOOR LOG': {
      const found = `${slotTime(w.slotCount, 2)} pm opened by ${w.hostShort} (theft discovered)`;
      if (w.doorGap) {
        const off = slotTime(w.doorGap.from, -4);
        const on = slotTime(w.doorGap.to, 28);
        return `${w.objectRoom} door keypad log: 8:10 pm locked after the tour · LOG OFFLINE from ${off} pm to ${on} pm (power cut; the door was opened and shut once during the outage) · ${found}. No other entries.`;
      }
      const open = slotTime(w.theftSlot, w.doorMinute);
      const close = slotTime(w.theftSlot, w.doorMinute + 3);
      return `${w.objectRoom} door keypad log: 8:10 pm locked after the tour · ${open} pm opened · ${close} pm closed · ${found}. No other entries.`;
    }
    case 'WITNESS': {
      const room = w.roles.witness;
      const lines = slotsOf(w).map((_, s) => `${slotTime(s)} — ${occupantText(occupants(w, room, s))}`).join('; ');
      return `Statement of ${w.staff.title}, ${w.staff.name}, who served drinks ${roomPhrase(room)} from 8:30 to ${endTime(w)} without leaving it. Guests present: ${lines}.`;
    }
    case 'RECEIPT': {
      const bar = w.roles.bar;
      if (w.purchases.length === 0) return `Bar receipts (${bar} bar, every drink is signed for): no drinks were signed for between 8:30 and ${endTime(w)}.`;
      const items = w.purchases.map((p) => `${slotTime(p.slot, p.minute)} pm ${p.item} — signed ${p.name}`).join(' · ');
      return `Bar receipts (${bar} bar, every drink is signed for): ${items}. No other purchases.`;
    }
    case 'CCTV': {
      const cam = (room: string) =>
        `${room} camera — ` +
        slotsOf(w).map((_, s) => {
          if (w.cctvGap.room === room && w.cctvGap.slot === s) {
            return `${slotTime(s)}: NO FOOTAGE (camera offline ${slotTime(s, -8)}–${slotTime(s, 26)})`;
          }
          const occ = occupants(w, room, s);
          return `${slotTime(s)}: ${occ.length === 0 ? 'empty' : listNames(occ)}`;
        }).join(' · ');
      return `CCTV stills (each slot shows everyone in the room): ${cam(w.roles.cam1)}. ${cam(w.roles.cam2)}. No other rooms have cameras.`;
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Solver: which suspects could be the (single, lying) thief?
// ─────────────────────────────────────────────────────────────────────────────

interface Fact {
  who: string;
  slot: number;
  room: string;
  present: boolean;
}

/** Slots the theft could have happened in, given the door log. */
export function candidateTheftSlots(w: LtWorld): number[] {
  if (!w.doorGap) return [w.theftSlot];
  const out: number[] = [];
  for (let s = w.doorGap.from; s <= w.doorGap.to; s++) out.push(s);
  return out;
}

/** Slots at which a suspect says they heard the object-room door (only innocents next door at the theft). */
export function heardDoorSlots(w: LtWorld, who: string): number[] {
  const s = w.theftSlot;
  return who !== w.culprit && w.truth[who]![s] === w.roles.adjacent && !w.hedged[who]![s] ? [s] : [];
}

/**
 * For each hypothesis "h is the thief, at candidate slot t": take every other
 * suspect's un-hedged statements plus all evidence as true, and check that
 * (a) they are mutually consistent, (b) nothing places h anywhere but the
 * object room at t, and (c) nobody heard the door at a different slot.
 * Returns every h that is viable for at least one candidate slot.
 */
export function viableCulprits(w: LtWorld): string[] {
  const names = w.suspects.map((s) => s.name);
  const evidence: Fact[] = [];
  const exact = (room: string, slot: number) => {
    const occ = occupants(w, room, slot);
    for (const n of names) evidence.push({ who: n, slot, room, present: occ.includes(n) });
  };
  for (let s = 0; s < w.slotCount; s++) {
    exact(w.roles.witness, s);
    for (const cam of [w.roles.cam1, w.roles.cam2]) {
      if (!(w.cctvGap.room === cam && w.cctvGap.slot === s)) exact(cam, s);
    }
  }
  for (const p of w.purchases) evidence.push({ who: p.name, slot: p.slot, room: w.roles.bar, present: true });

  const viable: string[] = [];
  for (const h of names) {
    const facts = evidence.slice();
    const heard = new Set<number>();
    for (const who of names) {
      if (who === h) continue;
      for (let s = 0; s < w.slotCount; s++) {
        const comp = claimedCompanions(w, who, s);
        if (comp === null) continue; // hedged: not a reliable statement
        const room = w.claims[who]![s]!;
        facts.push({ who, slot: s, room, present: true });
        for (const other of names) {
          if (other !== who) facts.push({ who: other, slot: s, room, present: comp.includes(other) });
        }
      }
      // A claimed purchase puts the speaker at the bar; the receipts list every purchase.
      for (const s of claimedPurchaseSlots(w, who)) {
        facts.push({ who, slot: s, room: w.roles.bar, present: true });
        if (!w.purchases.some((p) => p.name === who && p.slot === s)) {
          facts.push({ who, slot: s, room: w.roles.bar, present: false });
        }
      }
      for (const s of heardDoorSlots(w, who)) heard.add(s);
    }
    const ok = candidateTheftSlots(w).some((t) => {
      if ([...heard].some((s) => s !== t)) return false;
      // The thief was in the object room during the theft slot.
      return consistent([...facts, { who: h, slot: t, room: w.objectRoom, present: true }]);
    });
    if (ok) viable.push(h);
  }
  return viable;
}

function consistent(facts: Fact[]): boolean {
  const at = new Map<string, string>();
  const absent = new Set<string>();
  for (const f of facts) {
    if (!f.present) absent.add(`${f.who}|${f.slot}|${f.room}`);
  }
  for (const f of facts) {
    if (!f.present) continue;
    const key = `${f.who}|${f.slot}`;
    const prev = at.get(key);
    if (prev !== undefined && prev !== f.room) return false;
    at.set(key, f.room);
    if (absent.has(`${f.who}|${f.slot}|${f.room}`)) return false;
  }
  return true;
}
