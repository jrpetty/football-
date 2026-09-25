/**
 * Optional visual data recorded by the agent & social simulations (Survival
 * Island, The Escape Room, The Startup, The Liar's Table) so the UI can draw
 * a purpose-built replay: an illustrated island, a floor plan, a business
 * dashboard, a table of suspects.
 *
 * Everything here is recorded AFTER the model's turn from the engine state;
 * none of it is ever shown to a model, and results recorded before these
 * fields existed simply lack them (the UI then falls back to the generic
 * replay). `ReplayData.sim` holds what never changes during a case,
 * `ReplayFrame.sim` what changes per step. Coordinates are [x, y].
 */

export type XY = [number, number];

// ─── Survival Island ────────────────────────────────────────────────────────

export type IslandWeather = 'clear' | 'cloudy' | 'hot' | 'rain' | 'storm';

/** Things worth a marker on the day timeline, derived from the engine state. */
export type IslandEventTag =
  | 'found-water'
  | 'drink'
  | 'eat'
  | 'poison'
  | 'fish'
  | 'spear'
  | 'campfire'
  | 'shelter'
  | 'signal-built'
  | 'signal-lit'
  | 'bottle'
  | 'ship-pass'
  | 'ship-ack'
  | 'rescued'
  | 'survived'
  | 'died'
  | 'invalid'
  | 'hurt';

export interface IslandSimWorld {
  kind: 'island';
  size: number;
  maxDays: number;
  /** Terrain letters per row, no overlays (~ sea · . beach · P palm · , grass · * bush · T forest · ^ rocks · A summit · S spring · C cave). */
  terrain: string[];
  start: XY;
  summit: XY;
  spring: XY;
  cave: XY;
  bottle: XY;
  /** Weather per day (index 1..maxDays; index 0 unused). */
  weather: IslandWeather[];
  /** Berry bushes with their colour and whether they are poisonous. */
  bushes: Array<{ at: XY; colour: string; poison: boolean }>;
  /** Days the ship actually passes (the truth; revealed by the UI only when seen or at the finale). */
  shipDays: number[];
  signalsNeeded: number;
  requireNightFire: boolean;
  inventoryCap: number;
}

export interface IslandSimFrame {
  kind: 'island';
  day: number;
  /** 0 Morning · 1 Afternoon · 2 Evening · 3 Night (-1 = dawn of day 1, before any action). */
  phase: number;
  pos: XY;
  /** One string per row, '1' = explored (visible to the castaway), '0' = fog. */
  explored: string[];
  inventory: Record<string, number>;
  spear: boolean;
  /** Burning campfires: x, y, nights of fuel left. */
  fires: Array<[number, number, number]>;
  shelters: XY[];
  signal: 'none' | 'built' | 'lit';
  bottleFound: boolean;
  /** Passes on which the crew counted the signal, so far. */
  sightings: number;
  alive: boolean;
  rescued: boolean;
  cause?: string | null;
  /** What happened this step. */
  events: IslandEventTag[];
}

// ─── The Escape Room ────────────────────────────────────────────────────────

export type EscapeLockKind = 'key' | 'code' | 'word' | 'colour' | 'tool' | 'combo';

export interface EscapeSimLock {
  id: string;
  name: string;
  /** Room index the lock stands in. */
  room: number;
  kind: EscapeLockKind;
  /** Short tag, e.g. "4-digit dial". */
  tag: string;
  door: boolean;
  /** Door destination (room index or 'exit'), doors only. */
  to?: number | 'exit';
  /** The correct entry for code / word / colour locks. */
  answer?: string;
  /** The item that opens key / tool / combo locks. */
  item?: string;
  /** Clue objects a first-time solver reads to solve it (ids into `clues`). */
  clues: string[];
}

export interface EscapeSimWorld {
  kind: 'escape';
  rooms: string[];
  locks: EscapeSimLock[];
  /** Clue texts by object id (as the model reads them when it EXAMINEs the object). */
  clues: Record<string, { name: string; text: string }>;
  budget: number;
  optimal: number;
  /** The optimal command sequence. */
  plan: string[];
}

export type EscapeEventType = 'start' | 'unlock' | 'wrong' | 'take' | 'combine' | 'go' | 'escape' | 'found' | 'examine' | 'look' | 'fail' | 'invalid';

export interface EscapeSimFrame {
  kind: 'escape';
  move: number;
  room: number;
  visited: number[];
  unlocked: string[];
  /** Names of carried items. */
  inventory: string[];
  /** Ids of clue objects examined so far. */
  examined: string[];
  escaped: boolean;
  event: {
    type: EscapeEventType;
    /** Lock / object id the event concerns, when known. */
    target?: string;
    /** Entered value (normalised) for code / word / colour attempts. */
    value?: string;
    /** Item names revealed or taken. */
    items?: string[];
  };
}

// ─── The Startup ────────────────────────────────────────────────────────────

export interface StartupSimMonth {
  month: number;
  cash: number;
  equity: number;
}

export interface StartupSimWorld {
  kind: 'startup';
  product: string;
  months: number;
  cash0: number;
  staff0: number;
  unitCost: number;
  capacityPerStaff: number;
  /** The launch plan (what the autopilot repeats). */
  opening: { price: number; produce: number; marketing: number; hire: number };
  oracle: StartupSimMonth[];
  autopilot: StartupSimMonth[];
  /** The oracle's decisions month by month (the reference to compare the model's with). */
  oraclePlan: Array<{ month: number; price: number; produce: number; marketing: number; hire: number; loan: boolean }>;
  oracleEquity: number;
  autopilotEquity: number;
  events: {
    priceWar: { start: number; months: number; factor: number };
    supplierSpike: { start: number; months: number; factor: number };
    viral: number;
    loan: number;
    shock: { month: number; factor: number } | null;
  };
}

export interface StartupSimFrame {
  kind: 'startup';
  month: number;
  calendar: string;
  decisions: { price: number; produce: number; marketing: number; hire: number; loan: boolean };
  /** What the model asked for before cash / capacity limits (when different). */
  requested?: { produce: number; marketing: number };
  demand: number;
  sold: number;
  missed: number;
  produced: number;
  capacity: number;
  inventory: number;
  staff: number;
  competitor: number;
  unitCost: number;
  revenue: number;
  net: number;
  cash: number;
  equity: number;
  awareness: number;
  bankrupt: boolean;
  /** Market events this month (price war, supplier spike, viral review, loan, shock). */
  news: Array<{ type: 'price-war' | 'price-war-end' | 'supplier-spike' | 'supplier-notice' | 'supplier-end' | 'viral' | 'viral-soon' | 'loan' | 'loan-taken' | 'shock'; text: string }>;
  /** Plan changes the engine made (cash or capacity limits). */
  adjustments: string[];
}

// ─── The Liar's Table ───────────────────────────────────────────────────────

/** One thing learned from an answer: `who` was (yes) / was not (no) in `room` at `slot`, according to `src`. */
export interface LiarsFact {
  who: string;
  slot: number;
  room: string;
  yes: boolean;
  /** Suspect name or evidence id (DOOR LOG, WITNESS, RECEIPT, CCTV). */
  src: string;
  /** The speaker said they might be misremembering. */
  hedged?: boolean;
  /** A claimed bar purchase (only receipts can confirm it). */
  bought?: boolean;
}

export interface LiarsSimWorld {
  kind: 'liars';
  host: string;
  venue: string;
  object: string;
  objectRoom: string;
  rooms: string[];
  bar: string;
  slots: string[];
  suspects: Array<{ name: string; blurb: string }>;
  budget: number;
  /** Ground truth, revealed by the UI only at the verdict. */
  culprit: string;
  theftSlot: number;
  claimedRoom: string;
  truth: Record<string, string[]>;
  contradictionSources: string[];
}

export interface LiarsSimFrame {
  kind: 'liars';
  /** Questions used so far. */
  used: number;
  ask?: { who: string; topic: string };
  check?: string;
  invalid?: boolean;
  accuse?: { who: string; reason: string; correct: boolean; reasonScore: number };
  /** Facts revealed by this answer. */
  facts: LiarsFact[];
  /** Slots the object-room door was opened in, as revealed this step (door log / heard it). */
  door?: number[];
  /** Bar purchases on the receipts, when checked this step. */
  receipts?: Array<{ who: string; slot: number }>;
}

export type SimWorld = IslandSimWorld | EscapeSimWorld | StartupSimWorld | LiarsSimWorld;
export type SimFrame = IslandSimFrame | EscapeSimFrame | StartupSimFrame | LiarsSimFrame;
