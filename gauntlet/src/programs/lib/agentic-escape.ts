/**
 * The Escape Room — seeded puzzle generator, deterministic engine and
 * optimal-solution planner.
 *
 * A world is three rooms in a row. Each room's exit is locked; every lock
 * is the root of a small dependency tree built from puzzle modules: keys
 * hidden in furniture, tools that open fixtures, items that must be
 * combined, and code / word / colour locks whose answers are spread over
 * clue objects with a seeded encoding (Roman numerals, stopped clocks,
 * coloured counts, book spines, A1Z26, Caesar ciphers, acrostics, colour
 * riddles…). The seed changes the modules, their order, the encodings,
 * object names and placements, so the reasoning chain itself differs from
 * seed to seed — nothing can be memorised.
 *
 * The optimal move count is computed from the generated world: it is the
 * number of commands a perfect first-time solver needs, i.e. every lock
 * action, every TAKE of a needed item, every EXAMINE of a hiding place or
 * of a clue it must read (including the lock's own inscription), and the
 * three GO moves — no guessing, no wasted moves.
 */
import type { ReplayFrame, Rng } from '../../core/types.ts';
import { truncate } from './agentic-common.ts';

export type LockKind = 'key' | 'code' | 'word' | 'colour' | 'tool' | 'combo';

export interface EscLock {
  kind: LockKind;
  /** Normalised answer for code / word / colour locks (never shown to the model). */
  answer?: string;
  /** Item that opens key / tool / combo locks. */
  item?: string;
  /** Objects a first-time solver must examine before it can solve this lock without guessing. */
  needs: string[];
  /** Short state tag for the room listing, e.g. "4-digit dial". */
  tag: string;
  openText: string;
  /** Keys stay in their lock. */
  consumes: boolean;
}

export interface EscObject {
  id: string;
  /** Canonical upper-case name used in commands. */
  name: string;
  kind: 'fixture' | 'item' | 'door';
  /** Rooms where a fixture / door stands; for items the room it starts in (-1 = crafted). */
  rooms: number[];
  /** Not visible until revealed (inside a container, in a hiding place, or crafted). */
  hidden: boolean;
  /** Where a loose item lies, for the listing ("on the floor", "in the SAFE"). */
  place: string;
  desc: string;
  openDesc?: string;
  lock?: EscLock;
  /** Items revealed when the lock opens. */
  contains: string[];
  /** Items revealed when examined (hiding places). */
  hides: string[];
  foundText?: string;
  /** Doors: where they lead from each side. */
  door?: { leadsTo: Record<number, number | 'exit'> };
}

export interface EscCombo {
  a: string;
  b: string;
  result: string;
  text: string;
}

export interface EscRoom {
  name: string;
  intro: string;
}

export interface EscWorld {
  rooms: EscRoom[];
  objects: Record<string, EscObject>;
  /** Object ids in generation order (stable listing order). */
  order: string[];
  combos: EscCombo[];
  /** Ids of every object with a lock (the milestones). */
  locks: string[];
  moveBudget: number;
  /** The optimal command sequence and its length. */
  plan: string[];
  optimal: number;
}

export interface EscState {
  room: number;
  moves: number;
  inventory: string[];
  /** Current room of each loose item that is not carried. */
  location: Record<string, number>;
  revealed: string[];
  unlocked: string[];
  examined: string[];
  gone: string[];
  visited: number[];
  escaped: boolean;
  invalid: number;
  failed: number;
  wrongEntries: number;
  /** Move number at which each lock opened. */
  lockLog: Array<{ id: string; move: number }>;
}

export interface EscStep {
  move: number;
  action: string;
  outcome: string;
  valid: boolean;
  tone: 'good' | 'bad' | 'neutral';
}

// ─────────────────────────────────────────────────────────────────────────────
// Content pools
// ─────────────────────────────────────────────────────────────────────────────

const THEMES: EscRoom[] = [
  { name: 'The Study', intro: 'A cramped study that smells of pipe smoke and old paper.' },
  { name: 'The Library', intro: 'Floor-to-ceiling shelves sag under the weight of dusty books.' },
  { name: 'The Workshop', intro: 'Sawdust covers every surface of this cluttered workshop.' },
  { name: 'The Observatory', intro: 'A domed room with a star map painted across the ceiling.' },
  { name: 'The Wine Cellar', intro: 'Cold stone walls, damp air and racks of forgotten wine.' },
  { name: "The Captain's Cabin", intro: 'A ship’s cabin, strangely built on dry land, lit by a swaying lamp.' },
  { name: 'The Greenhouse', intro: 'Humid air, cracked glass panes and overgrown planters.' },
  { name: 'The Old Nursery', intro: 'Faded wallpaper, a rocking horse, and toys watching from the shelves.' },
];

const DOOR_NAMES = ['OAK DOOR', 'RED DOOR', 'ARCHED DOOR', 'GREEN DOOR', 'STEEL DOOR', 'TRAPDOOR'];
const KEY_METALS = ['BRASS', 'IRON', 'SILVER', 'COPPER', 'BONE', 'GOLD'];
const KEY_BOXES = ['CABINET', 'CHEST', 'WARDROBE', 'DESK DRAWER', 'TRUNK', 'CUPBOARD', 'JEWELLERY BOX', 'FOOTLOCKER'];
const DIAL_BOXES = ['SAFE', 'LOCKBOX', 'STRONGBOX', 'CASH BOX', 'BRIEFCASE', 'PUZZLE BOX', 'DISPLAY CASE', 'MUSIC BOX'];
const LOOSE_PLACES = ['on the floor', 'on a shelf', 'on the table', 'on the windowsill', 'under a chair'];

interface HideSpot {
  name: string;
  desc: string;
  found: string;
}
const HIDE_SPOTS: HideSpot[] = [
  { name: 'RUG', desc: 'A threadbare rug.', found: 'Lifting a corner of the rug, you find' },
  { name: 'COAT', desc: 'An old wool coat hangs on a peg.', found: 'Searching the coat pockets, you find' },
  { name: 'FLOWERPOT', desc: 'A flowerpot with a dead fern in it.', found: 'Digging in the dry soil of the flowerpot, you find' },
  { name: 'UMBRELLA STAND', desc: 'A brass umbrella stand.', found: 'At the bottom of the umbrella stand you find' },
  { name: 'WASTEBASKET', desc: 'A wicker wastebasket full of crumpled paper.', found: 'Rummaging through the wastebasket, you find' },
  { name: 'BOOTS', desc: 'A pair of muddy riding boots.', found: 'Inside the left boot you find' },
  { name: 'TEAPOT', desc: 'A chipped porcelain teapot.', found: 'Lifting the lid of the teapot, you find' },
  { name: 'PILLOW', desc: 'A lumpy embroidered pillow.', found: 'Inside the pillowcase you find' },
  { name: 'HATBOX', desc: 'A round striped hatbox.', found: 'Under the hat in the hatbox you find' },
  { name: 'FISHBOWL', desc: 'An empty fishbowl with coloured gravel.', found: 'Buried in the gravel of the fishbowl you find' },
];

const DECOYS: Array<{ name: string; desc: string }> = [
  { name: 'GLOBE', desc: 'A dusty globe. Someone has circled Madagascar in pencil.' },
  { name: 'TYPEWRITER', desc: 'An old typewriter with a blank sheet rolled in. The ribbon is dry.' },
  { name: 'GRAMOPHONE', desc: 'A gramophone with a cracked record still on the turntable.' },
  { name: 'STUFFED OWL', desc: 'A stuffed owl stares at you with glass eyes.' },
  { name: 'CHESSBOARD', desc: 'A chessboard with a game abandoned mid-play. White is losing badly.' },
  { name: 'ROCKING CHAIR', desc: 'A rocking chair that creaks when you touch it.' },
  { name: 'BAROMETER', desc: 'A barometer whose needle points to "Change".' },
  { name: 'CANDELABRA', desc: 'A tarnished silver candelabra with melted-down candles.' },
  { name: 'SHIP MODEL', desc: 'A detailed model of a three-masted ship. Its sails are yellowed with age.' },
  { name: 'HOURGLASS', desc: 'An hourglass. The sand has long since run out.' },
];

interface ToolGate {
  tool: string;
  toolDesc: string;
  target: string;
  tag: string;
  desc: string;
  openDesc: string;
  open: string;
}
const TOOL_GATES: ToolGate[] = [
  {
    tool: 'SCREWDRIVER',
    toolDesc: 'A flat-head screwdriver.',
    target: 'VENT',
    tag: 'screwed shut',
    desc: 'A metal vent cover held on by four screws. Something rattles behind it.',
    openDesc: 'An open vent; the cover lies on the floor.',
    open: 'You unscrew the four screws and the vent cover drops away.',
  },
  {
    tool: 'CROWBAR',
    toolDesc: 'A heavy iron crowbar.',
    target: 'CRATE',
    tag: 'nailed shut',
    desc: 'A wooden crate, nailed firmly shut.',
    openDesc: 'A crate with its lid prised off.',
    open: 'You lever the lid off the crate with a screech of nails.',
  },
  {
    tool: 'HAMMER',
    toolDesc: 'A claw hammer.',
    target: 'PLASTER WALL',
    tag: 'sounds hollow',
    desc: 'A patch of fresh plaster on the wall. When you knock on it, it sounds hollow.',
    openDesc: 'A hole smashed through the plaster.',
    open: 'You smash through the plaster, revealing a small cavity.',
  },
  {
    tool: 'KNIFE',
    toolDesc: 'A small, sharp kitchen knife.',
    target: 'ARMCHAIR',
    tag: 'something sewn inside',
    desc: 'A worn armchair. Something hard has been sewn inside the seat cushion.',
    openDesc: 'An armchair with its cushion slit open.',
    open: 'You slit the seam of the cushion open.',
  },
  {
    tool: 'PLIERS',
    toolDesc: 'A pair of heavy pliers.',
    target: 'BIRDCAGE',
    tag: 'wired shut',
    desc: 'An ornate birdcage. Its little door is twisted shut with thick wire.',
    openDesc: 'A birdcage with its door hanging open.',
    open: 'You cut through the wire and swing the cage door open.',
  },
];

interface ComboGate {
  a: string;
  aDesc: string;
  b: string;
  bDesc: string;
  result: string;
  resultDesc: string;
  combine: string;
  target: string;
  tag: string;
  desc: string;
  openDesc: string;
  open: string;
}
const COMBO_GATES: ComboGate[] = [
  {
    a: 'MAGNET',
    aDesc: 'A strong horseshoe magnet.',
    b: 'STRING',
    bDesc: 'A long ball of strong string.',
    result: 'MAGNET LINE',
    resultDesc: 'A magnet tied to a long string, handy for fishing metal things out of tight spots.',
    combine: 'You tie the string to the magnet, making a MAGNET LINE.',
    target: 'DRAIN',
    tag: 'something glints far inside',
    desc: 'A narrow floor drain. Far below the grate something small and metal glints, much too deep to reach with your fingers.',
    openDesc: 'A narrow floor drain, now empty.',
    open: 'You lower the magnet line through the grate and fish out the metal object.',
  },
  {
    a: 'TORCH',
    aDesc: 'An electric torch. It has no batteries in it.',
    b: 'BATTERIES',
    bDesc: 'A pair of batteries.',
    result: 'WORKING TORCH',
    resultDesc: 'A torch with fresh batteries. It shines brightly.',
    combine: 'You slot the batteries into the torch. It works: you now have a WORKING TORCH.',
    target: 'FIREPLACE',
    tag: 'pitch dark inside',
    desc: 'A large cold fireplace. The chimney above is pitch dark; you cannot see anything inside it.',
    openDesc: 'A cold fireplace with a small ledge inside the chimney.',
    open: 'You shine the torch up the chimney and spot a small ledge with something on it.',
  },
  {
    a: 'POLE',
    aDesc: 'A long wooden pole.',
    b: 'HOOK',
    bDesc: 'A metal hook with a threaded screw end.',
    result: 'HOOKED POLE',
    resultDesc: 'A long pole with a hook screwed onto its end.',
    combine: 'You screw the hook onto the end of the pole, making a HOOKED POLE.',
    target: 'HIGH SHELF',
    tag: 'out of reach',
    desc: 'A shelf just below the ceiling, far out of reach. Something sits on it.',
    openDesc: 'A high shelf, now empty.',
    open: 'You hook the object on the high shelf and bring it down.',
  },
];

const COLOURS = ['RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE', 'PURPLE', 'WHITE', 'BLACK'];
const RAINBOW = ['RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE', 'PURPLE'];
const COLOUR_PHRASES: Record<string, string[]> = {
  RED: ['ripe cherries', 'a fire engine'],
  ORANGE: ['a pumpkin', 'a carrot'],
  YELLOW: ['a lemon', 'a sunflower'],
  GREEN: ['fresh grass', 'an emerald'],
  BLUE: ['a clear summer sky', 'a sapphire'],
  PURPLE: ['lavender', 'an amethyst'],
  WHITE: ['fresh snow', 'milk'],
  BLACK: ['coal', 'a raven’s wing'],
};
const NUMBER_WORDS = ['ZERO', 'ONE', 'TWO', 'THREE', 'FOUR', 'FIVE', 'SIX', 'SEVEN', 'EIGHT', 'NINE'];
const SMALL_WORDS = ['no', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine'];
const CIPHER_WORDS = ['ANCHOR', 'RAVEN', 'LANTERN', 'ORCHID', 'MARBLE', 'FALCON', 'COMPASS', 'HARBOR', 'EMBER', 'TIMBER', 'PARROT', 'CANDLE', 'WALNUT', 'GARNET', 'BEACON', 'CASTLE', 'MEADOW'];
const A1Z26_WORDS = ['HIDE', 'ACHE', 'CAGE', 'DICE', 'FACE', 'BEAD', 'HEAD', 'FADE', 'DEAF', 'CHEF', 'BIDE', 'AIDE', 'IDEA', 'EDGE', 'CAFE', 'BEEF'];
const BOOK_TITLES = ['Moby Dick', 'Walden', 'Dracula', 'Emma', 'Ivanhoe', 'Beowulf', 'Frankenstein', 'Middlemarch', 'Kidnapped', 'Persuasion'];
const PORTRAIT_PEOPLE = ['Admiral Elias Thorne', 'Lady Ada Merrow', 'Bishop Anselm Grey', 'Countess Irina Vasko', 'Sir Tobias Wren'];

/** Two opening lines per letter for acrostic poems. */
const ACROSTIC_LINES: Record<string, string[]> = {
  A: ['All the clocks have stopped their ticking,', 'Ashes settle on the sill,'],
  B: ['Beneath the stair the shadows gather,', 'Bells are ringing far away,'],
  C: ['Candles gutter in the hallway,', 'Cold winds whisper through the crack,'],
  D: ['Dust lies thick on every letter,', 'Doors that creak but never close,'],
  E: ['Echoes wander down the corridor,', 'Every window fogged with frost,'],
  F: ['Far below the cellar murmurs,', 'Footsteps fade upon the stair,'],
  G: ['Grey the morning, grey the evening,', 'Ghosts of music fill the air,'],
  H: ['Hollow knocking from the rafters,', 'Hands of ivy grip the wall,'],
  I: ['In the hall a mirror whispers,', 'Ink has faded from the page,'],
  L: ['Lamps are low and nights are long,', 'Leaves are turning in the yard,'],
  M: ['Moonlight silvers every doorway,', 'Many keys but only one,'],
  N: ['Nothing stirs inside the parlour,', 'Night comes early to this house,'],
  O: ['Over rooftops ravens circle,', 'Old wood groans beneath your feet,'],
  P: ['Portraits watch with painted eyes,', 'Pages rustle, all alone,'],
  R: ['Rain is drumming on the shutters,', 'Rust has crept along the gate,'],
  S: ['Silence settles like a blanket,', 'Stairs lead up to empty rooms,'],
  T: ['Time runs slowly in this place,', 'Tapestries hang torn and faded,'],
  U: ['Under floorboards secrets slumber,', 'Upward climbs the twisting vine,'],
  W: ['Winter lingers in the chimney,', 'Whispers drift from room to room,'],
};

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function toRoman(n: number): string {
  const table: Array<[number, string]> = [
    [1000, 'M'],
    [900, 'CM'],
    [500, 'D'],
    [400, 'CD'],
    [100, 'C'],
    [90, 'XC'],
    [50, 'L'],
    [40, 'XL'],
    [10, 'X'],
    [9, 'IX'],
    [5, 'V'],
    [4, 'IV'],
    [1, 'I'],
  ];
  let out = '';
  for (const [v, s] of table) {
    while (n >= v) {
      out += s;
      n -= v;
    }
  }
  return out;
}

function caesar(text: string, k: number): string {
  return text.replace(/[A-Z]/g, (c) => String.fromCharCode(((c.charCodeAt(0) - 65 + k) % 26) + 65));
}

function withArticle(phrase: string): string {
  return `${/^[aeiou]/i.test(phrase) ? 'an' : 'a'} ${phrase}`;
}

function listWords(items: string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** Normalise a free-text object reference: upper-case, no articles / punctuation. */
export function normName(text: string): string {
  return text
    .toUpperCase()
    .replace(/[^A-Z0-9' ]/g, ' ')
    .replace(/'/g, '')
    .replace(/\b(THE|A|AN|MY)\b/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

const COLOUR_SYNONYMS: Record<string, string> = { VIOLET: 'PURPLE', GREY: 'WHITE', GRAY: 'WHITE' };

/** Normalise an ENTER value for a lock kind. */
export function normAnswer(kind: LockKind, value: string): string {
  const v = value.toUpperCase();
  if (kind === 'code') return v.replace(/[^0-9]/g, '');
  if (kind === 'word') return v.replace(/[^A-Z]/g, '');
  return v
    .split(/[^A-Z]+/)
    .filter(Boolean)
    .map((c) => COLOUR_SYNONYMS[c] ?? c)
    .join(' ');
}

// ─────────────────────────────────────────────────────────────────────────────
// Generator
// ─────────────────────────────────────────────────────────────────────────────

interface Gen {
  rng: Rng;
  objects: Record<string, EscObject>;
  order: string[];
  combos: EscCombo[];
  used: Set<string>;
  keyMetals: string[];
  keyBoxes: string[];
  dialBoxes: string[];
  hides: HideSpot[];
  decoys: Array<{ name: string; desc: string }>;
  tools: ToolGate[];
  comboGates: ComboGate[];
  shift: { id: string; k: number; room: number } | null;
}

function slug(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-');
}

function addObject(g: Gen, o: Omit<EscObject, 'id' | 'contains' | 'hides' | 'place'> & Partial<Pick<EscObject, 'contains' | 'hides' | 'place'>>): EscObject {
  let id = slug(o.name);
  if (g.objects[id]) throw new Error(`Escape Room generator: duplicate object ${o.name}`);
  const obj: EscObject = { contains: [], hides: [], place: '', ...o, id };
  id = obj.id;
  g.objects[id] = obj;
  g.order.push(id);
  return obj;
}

function nameFree(g: Gen, name: string): boolean {
  return !g.objects[slug(name)];
}

function takeFrom<T>(pool: T[], pred: (t: T) => boolean = () => true): T | null {
  const idx = pool.findIndex(pred);
  if (idx < 0) return null;
  return pool.splice(idx, 1)[0]!;
}

interface Encoded {
  answer: string;
  tag: string;
  hint: string;
  /** Clue objects that must be examined (besides the lock itself). */
  needs: string[];
  /** Clue items that still need placing (returned to the caller). */
  items: string[];
}

type Encoder = (g: Gen, room: number, wantItem: boolean) => Encoded | null;

/** Creates a clue object: a fixture in `room`, or an item the caller places. */
function clueObject(g: Gen, room: number, asItem: boolean, name: string, desc: string): EscObject {
  return addObject(g, { name, kind: asItem ? 'item' : 'fixture', rooms: [room], hidden: asItem, desc });
}

function shiftSource(g: Gen, room: number): { id: string; k: number } {
  if (g.shift) return g.shift;
  const k = g.rng.int(2, 9);
  // Half the time the shift is given in an earlier room: it must be remembered.
  const where = room > 0 && g.rng.chance(0.5) ? g.rng.int(0, room - 1) : room;
  const obj = g.rng.chance(0.5)
    ? addObject(g, {
        name: 'CIPHER WHEEL',
        kind: 'fixture',
        rooms: [where],
        hidden: false,
        desc: `A brass cipher wheel with two rings of letters. The inner ring is turned so that the letter A sits under the letter ${String.fromCharCode(65 + k)}.`,
      })
    : addObject(g, {
        name: 'PLAQUE',
        kind: 'fixture',
        rooms: [where],
        hidden: false,
        desc: `A small engraved plaque: "In this house every secret letter walks ${SMALL_WORDS[k]} steps forward in the alphabet."`,
      });
  g.shift = { id: obj.id, k, room: where };
  return g.shift;
}

const ENCODERS: Record<string, { kind: 'code' | 'word' | 'colour'; make: Encoder }> = {
  roman: {
    kind: 'code',
    make(g, room, wantItem) {
      const year = g.rng.int(1150, 1899);
      const r = toRoman(year);
      if (wantItem) {
        const o = clueObject(g, room, true, 'PHOTOGRAPH', `A faded photograph of a lighthouse. On the back, in brown ink: "Built ${r}."`);
        return { answer: String(year), tag: '4-digit dial', hint: 'Scratched beside it: "the year the lighthouse was built".', needs: [o.id], items: [o.id] };
      }
      if (g.rng.chance(0.5)) {
        const who = g.rng.pick(PORTRAIT_PEOPLE);
        const o = clueObject(g, room, false, 'PORTRAIT', `An oil portrait of ${who}. A small plate beneath it reads: "${who}, painted anno ${r}."`);
        return { answer: String(year), tag: '4-digit dial', hint: 'Scratched beside it: "the year of the portrait".', needs: [o.id], items: [] };
      }
      const o = clueObject(g, room, false, 'CORNERSTONE', `A carved stone set into the wall. Chiselled into it: "THIS HOUSE WAS FOUNDED ${r}".`);
      return { answer: String(year), tag: '4-digit dial', hint: 'Scratched beside it: "the year the house was founded".', needs: [o.id], items: [] };
    },
  },
  clock: {
    kind: 'code',
    make(g, room, wantItem) {
      const h = g.rng.int(1, 12);
      const m5 = g.rng.int(1, 11);
      const answer = `${h}${String(m5 * 5).padStart(2, '0')}`;
      const romanFace = g.rng.chance(0.5);
      const face = romanFace
        ? `Its face uses Roman numerals: the short hand points exactly at ${toRoman(h)}, the long hand exactly at ${toRoman(m5)}.`
        : `The short hand points exactly at the ${h}, the long hand exactly at the ${m5}.`;
      const hint = 'A tag hangs from it: "when time stopped — the hour, then the minutes".';
      const tag = `${answer.length}-digit dial`;
      if (wantItem) {
        const o = clueObject(g, room, true, 'POCKET WATCH', `A silver pocket watch that has stopped. ${face}`);
        return { answer, tag, hint: hint.replace('when time stopped', 'when the pocket watch stopped'), needs: [o.id], items: [o.id] };
      }
      const name = nameFree(g, 'GRANDFATHER CLOCK') && g.rng.chance(0.5) ? 'GRANDFATHER CLOCK' : 'WALL CLOCK';
      const o = clueObject(g, room, false, name, `A ${name.toLowerCase()} that has stopped. ${face}`);
      return { answer, tag, hint: hint.replace('when time stopped', `when the ${name.toLowerCase()} stopped`), needs: [o.id], items: [] };
    },
  },
  counts: {
    kind: 'code',
    make(g, room, wantItem) {
      const colours = g.rng.shuffle(COLOURS).slice(0, 3);
      const counts = g.rng.shuffle([1, 2, 3, 4, 5, 6, 7, 8, 9]).slice(0, 3);
      const order = g.rng.shuffle([0, 1, 2]);
      const answer = order.map((i) => String(counts[i])).join('');
      const noun = wantItem ? ['marble', 'marbles'] : g.rng.pick([['jar', 'jars'], ['bottle', 'bottles'], ['candle', 'candles']]);
      const parts = g.rng.shuffle([0, 1, 2]).map((i) => `${SMALL_WORDS[counts[i]!]} ${colours[i]!.toLowerCase()} ${counts[i] === 1 ? noun[0] : noun[1]}`);
      const dots = order.map((i) => colours[i]!.toLowerCase());
      const hint = `Above its three digits are three painted dots: ${dots[0]}, then ${dots[1]}, then ${dots[2]}. Scratched below: "count the ${noun[1]}".`;
      if (wantItem) {
        const o = clueObject(g, room, true, 'BAG OF MARBLES', `A cloth bag of marbles. You tip them out and count: ${listWords(parts)}.`);
        return { answer, tag: '3-digit dial', hint, needs: [o.id], items: [o.id] };
      }
      const name = noun[0] === 'jar' ? 'SPICE RACK' : noun[0] === 'bottle' ? 'BOTTLE RACK' : 'CANDLE SHELF';
      const o = clueObject(g, room, false, name, `A ${name.toLowerCase()} holding ${listWords(parts)}.`);
      return { answer, tag: '3-digit dial', hint, needs: [o.id], items: [] };
    },
  },
  books: {
    kind: 'code',
    make(g, room, wantItem) {
      if (!nameFree(g, 'BOOKSHELF')) return null;
      const titles = g.rng.shuffle(BOOK_TITLES).slice(0, 4);
      const colours = g.rng.shuffle(['red', 'green', 'blue', 'black', 'yellow', 'white']).slice(0, 4);
      const numbers = g.rng.shuffle(Array.from({ length: 800 }, (_, i) => 101 + i)).slice(0, 4);
      const target = g.rng.int(0, 3);
      const shelf = clueObject(
        g,
        room,
        false,
        'BOOKSHELF',
        `A bookshelf with four books, each with a number on its spine: ${listWords(titles.map((t, i) => `${withArticle(colours[i]!)} copy of '${t}' (No. ${numbers[i]})`))}.`,
      );
      const byColour = g.rng.chance(0.5);
      const ref = byColour ? `the ${colours[target]} book` : `'${titles[target]}'`;
      const text = `"Borrowed numbers open borrowed doors: read the spine number of ${ref}."`;
      const note = wantItem
        ? clueObject(g, room, true, 'BOOKMARK', `A leather bookmark. Written on it: ${text}`)
        : clueObject(g, room, false, 'CHALKBOARD', `A chalkboard. Someone has written: ${text}`);
      return {
        answer: String(numbers[target]),
        tag: '3-digit dial',
        hint: `Engraved: "the ${wantItem ? 'bookmark' : 'chalkboard'} knows which book".`,
        needs: [shelf.id, note.id],
        items: wantItem ? [note.id] : [],
      };
    },
  },
  letters: {
    kind: 'code',
    make(g, room, wantItem) {
      const word = g.rng.pick(A1Z26_WORDS);
      const answer = [...word].map((c) => String(c.charCodeAt(0) - 64)).join('');
      const hint = `Each button also shows a letter: 1 = A, 2 = B, 3 = C … 9 = I. Engraved: "the ${wantItem ? 'postcard' : 'mirror'} spells it".`;
      const o = wantItem
        ? clueObject(g, room, true, 'POSTCARD', `A postcard of a seaside town. On the back, a single word, underlined twice: ${word}.`)
        : clueObject(g, room, false, 'MIRROR', `A tall mirror. Someone has written on it in red lipstick: ${word}.`);
      return { answer, tag: `${answer.length}-digit keypad`, hint, needs: [o.id], items: wantItem ? [o.id] : [] };
    },
  },
  cipherDigits: {
    kind: 'code',
    make(g, room, wantItem) {
      const src = shiftSource(g, room);
      const digits = [g.rng.int(1, 9), g.rng.int(0, 9), g.rng.int(0, 9)];
      const coded = digits.map((d) => caesar(NUMBER_WORDS[d]!, src.k)).join(' - ');
      const o = wantItem
        ? clueObject(g, room, true, 'CIPHER NOTE', `A note written in capitals that make no sense: "${coded}".`)
        : clueObject(g, room, false, 'SLATE', `A school slate. Chalked on it in capitals that make no sense: "${coded}".`);
      return {
        answer: digits.join(''),
        tag: '3-digit dial',
        hint: `Engraved: "the ${wantItem ? 'cipher note' : 'slate'} hides three numbers — walk its letters back".`,
        needs: [o.id, src.id],
        items: wantItem ? [o.id] : [],
      };
    },
  },
  cipherWord: {
    kind: 'word',
    make(g, room, wantItem) {
      const src = shiftSource(g, room);
      const word = g.rng.pick(CIPHER_WORDS);
      const coded = caesar(word, src.k);
      const o = wantItem
        ? clueObject(g, room, true, 'TORN PAGE', `A page torn from a diary. One word is circled: "${coded}".`)
        : clueObject(g, room, false, 'SCRAWLED WALL', `Someone has scrawled a single word across the wallpaper: "${coded}".`);
      return {
        answer: word,
        tag: `${word.length}-letter dial`,
        hint: `Engraved: "the word on the ${wantItem ? 'torn page' : 'scrawled wall'}, walked back".`,
        needs: [o.id, src.id],
        items: wantItem ? [o.id] : [],
      };
    },
  },
  acrostic: {
    kind: 'word',
    make(g, room, wantItem) {
      const word = g.rng.pick(CIPHER_WORDS.filter((w) => [...w].every((c) => ACROSTIC_LINES[c])));
      const seen: Record<string, number> = {};
      const lines = [...word].map((c) => {
        const i = seen[c] ?? g.rng.int(0, 1);
        seen[c] = (i + 1) % 2;
        return ACROSTIC_LINES[c]![i]!;
      });
      const poem = lines.join(' / ').replace(/,$/, '.');
      const o = wantItem
        ? clueObject(g, room, true, 'POEM', `A poem copied out on yellowed paper: "${poem}"`)
        : clueObject(g, room, false, 'SAMPLER', `An embroidered sampler in a frame, stitched with a poem: "${poem}"`);
      return {
        answer: word,
        tag: `${word.length}-letter dial`,
        hint: `Engraved: "the ${wantItem ? 'poem' : 'sampler'} begins the answer, line by line".`,
        needs: [o.id],
        items: wantItem ? [o.id] : [],
      };
    },
  },
  numbered: {
    kind: 'colour',
    make(g, room, wantItem) {
      const n = g.rng.int(3, 4);
      const colours = g.rng.shuffle(COLOURS).slice(0, n);
      const roman = g.rng.chance(0.5);
      const mark = (i: number): string => (roman ? toRoman(i + 1) : String(i + 1));
      const noun = wantItem ? ['block', 'blocks'] : g.rng.pick([['vase', 'vases'], ['flag', 'flags'], ['bottle', 'bottles']]);
      const parts = g.rng.shuffle(colours.map((c, i) => ({ c, i }))).map(({ c, i }) => `${withArticle(`${c.toLowerCase()} ${noun[0]}`)} marked ${mark(i)}`);
      const text = `${SMALL_WORDS[n]![0]!.toUpperCase()}${SMALL_WORDS[n]!.slice(1)} ${noun[1]}: ${listWords(parts)}.`;
      const hint = `Label: "press them in the order the ${noun[1]} count".`;
      const name = wantItem ? 'WOODEN BLOCKS' : noun[0] === 'vase' ? 'VASES' : noun[0] === 'flag' ? 'FLAGS' : 'GLASS BOTTLES';
      const o = clueObject(g, room, wantItem, name, text);
      return { answer: colours.join(' '), tag: `${n}-colour panel`, hint, needs: [o.id], items: wantItem ? [o.id] : [] };
    },
  },
  riddle: {
    kind: 'colour',
    make(g, room, wantItem) {
      const n = g.rng.int(3, 4);
      const colours = g.rng.shuffle(COLOURS).slice(0, n);
      const ords = ['First', 'then', 'then', 'last'];
      const phr = colours.map((c, i) => `${i === n - 1 ? 'last' : i === 0 ? ords[0] : 'then'}, the colour of ${g.rng.pick(COLOUR_PHRASES[c]!)}`);
      const text = `"${phr.join('; ')}."`;
      const o = wantItem
        ? clueObject(g, room, true, 'RIDDLE CARD', `A card with a riddle in neat handwriting: ${text}`)
        : clueObject(g, room, false, 'INSCRIPTION', `An inscription painted above the fireplace mantel: ${text}`);
      return {
        answer: colours.join(' '),
        tag: `${n}-colour panel`,
        hint: `Label: "the ${wantItem ? 'riddle card' : 'inscription'} tells the order".`,
        needs: [o.id],
        items: wantItem ? [o.id] : [],
      };
    },
  },
  rainbow: {
    kind: 'colour',
    make(g, room, wantItem) {
      const n = g.rng.int(3, 4);
      const picked = g.rng.shuffle(RAINBOW).slice(0, n);
      const sorted = RAINBOW.filter((c) => picked.includes(c));
      const shown = g.rng.shuffle(picked).map((c) => (c === 'PURPLE' ? 'violet' : c.toLowerCase()));
      const o = wantItem
        ? clueObject(g, room, true, 'GLASS PANES', `A bundle of loose coloured glass panes: ${listWords(shown)}.`)
        : clueObject(g, room, false, 'STAINED GLASS WINDOW', `A stained glass window with ${SMALL_WORDS[n]} coloured panes, jumbled: ${listWords(shown)}.`);
      return {
        answer: sorted.join(' '),
        tag: `${n}-colour panel`,
        hint: `Label: "order the ${wantItem ? 'glass panes' : 'window’s colours'} as the rainbow does".`,
        needs: [o.id],
        items: wantItem ? [o.id] : [],
      };
    },
  },
};

type GateKind = LockKind;

/** Puts a lock of `kind` on `target`. Returns the item ids the solver will need (to be placed). */
function lockWith(g: Gen, room: number, target: EscObject, kind: 'key' | 'code' | 'word' | 'colour', wantItem: boolean): string[] | null {
  if (kind === 'key') {
    const metal = takeFrom(g.keyMetals);
    if (!metal) return null;
    const key = addObject(g, { name: `${metal} KEY`, kind: 'item', rooms: [room], hidden: true, desc: `A small ${metal.toLowerCase()} key.` });
    const ring = metal.toLowerCase();
    target.lock = {
      kind: 'key',
      item: key.id,
      needs: [],
      tag: `keyhole ringed with ${ring}`,
      openText: `The ${key.name} turns with a satisfying click and stays in the lock. The ${target.name} is unlocked.`,
      consumes: true,
    };
    target.desc += ` It is locked; the keyhole is ringed with ${ring}.`;
    return [key.id];
  }
  const options = Object.entries(ENCODERS).filter(([name, e]) => e.kind === kind && !g.used.has(name));
  for (const [name, enc] of g.rng.shuffle(options)) {
    const res = enc.make(g, room, wantItem);
    if (!res) continue;
    g.used.add(name);
    const tag = target.kind === 'door' && kind === 'code' ? res.tag.replace('dial', 'keypad') : res.tag;
    const what = kind === 'code' ? `a ${tag}` : kind === 'word' ? `a ${tag} (${res.answer.length} letter wheels)` : `a ${tag} with buttons marked ${COLOURS.join(', ')}; it takes a sequence of ${res.answer.split(' ').length} colours`;
    target.lock = {
      kind,
      answer: res.answer,
      needs: [...res.needs, target.id],
      tag,
      openText: `Click! The ${target.name} unlocks.`,
      consumes: false,
    };
    target.desc += ` It is locked with ${what}. ${res.hint}`;
    return res.items;
  }
  return null;
}

/** A new container in `room` holding `contents`, locked by a gate of `kind`. */
function makeContainer(g: Gen, room: number, kind: GateKind, contents: string[], wantItem: boolean): string[] | null {
  if (kind === 'tool') {
    const t = takeFrom(g.tools);
    if (!t) return null;
    const tool = addObject(g, { name: t.tool, kind: 'item', rooms: [room], hidden: true, desc: t.toolDesc });
    addObject(g, {
      name: t.target,
      kind: 'fixture',
      rooms: [room],
      hidden: false,
      desc: t.desc,
      openDesc: t.openDesc,
      contains: contents,
      lock: { kind: 'tool', item: tool.id, needs: [], tag: t.tag, openText: t.open, consumes: false },
    });
    return [tool.id];
  }
  if (kind === 'combo') {
    const c = takeFrom(g.comboGates);
    if (!c) return null;
    const a = addObject(g, { name: c.a, kind: 'item', rooms: [room], hidden: true, desc: c.aDesc });
    const b = addObject(g, { name: c.b, kind: 'item', rooms: [room], hidden: true, desc: c.bDesc });
    const result = addObject(g, { name: c.result, kind: 'item', rooms: [-1], hidden: true, desc: c.resultDesc });
    g.combos.push({ a: a.id, b: b.id, result: result.id, text: c.combine });
    addObject(g, {
      name: c.target,
      kind: 'fixture',
      rooms: [room],
      hidden: false,
      desc: c.desc,
      openDesc: c.openDesc,
      contains: contents,
      lock: { kind: 'combo', item: result.id, needs: [], tag: c.tag, openText: c.open, consumes: false },
    });
    return [a.id, b.id];
  }
  const pool = kind === 'key' ? g.keyBoxes : g.dialBoxes;
  const name = takeFrom(pool);
  if (!name) return null;
  const box = addObject(g, { name, kind: 'fixture', rooms: [room], hidden: false, desc: `A sturdy ${name.toLowerCase()}.`, contains: contents });
  box.openDesc = `The ${name.toLowerCase()} stands open.`;
  const needs = lockWith(g, room, box, kind, wantItem);
  if (needs === null) {
    delete g.objects[box.id];
    g.order = g.order.filter((id) => id !== box.id);
    pool.push(name);
    return null;
  }
  return needs;
}

/** Leave an item loose (visible) or tucked into a hiding place (revealed by EXAMINE). */
function placeLoose(g: Gen, room: number, itemId: string): void {
  const item = g.objects[itemId]!;
  item.rooms = [room];
  const spot = g.rng.chance(0.6) ? takeFrom(g.hides) : null;
  if (spot) {
    item.hidden = true;
    const obj = addObject(g, { name: spot.name, kind: 'fixture', rooms: [room], hidden: false, desc: spot.desc, hides: [itemId] });
    obj.foundText = spot.found;
  } else {
    item.hidden = false;
    item.place = g.rng.pick(LOOSE_PLACES);
  }
}

export interface EscapeConfig {
  locksPerRoom: number[];
  moveBudget: number;
}

export function generateEscape(root: Rng, cfg: EscapeConfig): EscWorld {
  for (let attempt = 0; attempt < 50; attempt++) {
    const w = tryGenerate(root.fork(`escape:${attempt}`), cfg);
    if (w) return w;
  }
  throw new Error('Escape Room: could not generate a world for this seed');
}

function tryGenerate(rng: Rng, cfg: EscapeConfig): EscWorld | null {
  const g: Gen = {
    rng,
    objects: {},
    order: [],
    combos: [],
    used: new Set(),
    keyMetals: rng.shuffle(KEY_METALS),
    keyBoxes: rng.shuffle(KEY_BOXES),
    dialBoxes: rng.shuffle(DIAL_BOXES),
    hides: rng.shuffle(HIDE_SPOTS),
    decoys: rng.shuffle(DECOYS),
    tools: rng.shuffle(TOOL_GATES),
    comboGates: rng.shuffle(COMBO_GATES),
    shift: null,
  };
  const rooms = rng.shuffle(THEMES).slice(0, 3);
  const doorNames = rng.shuffle(DOOR_NAMES);
  const doorKinds = rng.shuffle(['key', 'code', 'word', 'colour'] as const).slice(0, 3);

  for (let r = 0; r < 3; r++) {
    const exit = r === 2;
    const doorName = exit ? 'EXIT DOOR' : doorNames[r]!;
    const door = addObject(g, {
      name: doorName,
      kind: 'door',
      rooms: exit ? [r] : [r, r + 1],
      hidden: false,
      desc: exit ? 'A heavy steel door with daylight showing around its edges: the way out.' : `The ${doorName.toLowerCase()} leads further into the house.`,
      door: { leadsTo: exit ? { [r]: 'exit' } : { [r]: r + 1, [r + 1]: r } },
    });
    const locks = Math.max(1, cfg.locksPerRoom[r] ?? 2);
    let containersLeft = locks - 1;
    const queue = lockWith(g, r, door, doorKinds[r]!, containersLeft > 0);
    if (!queue) return null;

    while (containersLeft > 0 && queue.length > 0) {
      const itemId = queue.splice(rng.int(0, queue.length - 1), 1)[0]!;
      const wantItem = containersLeft > 1;
      const kinds = rng.shuffle(['key', 'key', 'code', 'code', 'word', 'colour', 'tool', 'tool', 'combo'] as GateKind[]);
      let needs: string[] | null = null;
      for (const kind of kinds) {
        needs = makeContainer(g, r, kind, [itemId], wantItem);
        if (needs) break;
      }
      if (!needs) return null;
      g.objects[itemId]!.hidden = true;
      g.objects[itemId]!.rooms = [r];
      queue.push(...needs);
      containersLeft--;
    }
    if (containersLeft > 0) return null;
    // Whatever still needs a home is left loose — sometimes in an earlier room, to be carried forward.
    for (const itemId of queue) {
      const home = r > 0 && rng.chance(0.25) ? r - 1 : r;
      placeLoose(g, home, itemId);
    }
    // One or two harmless decoys per room.
    for (let i = 0; i < rng.int(1, 2); i++) {
      const d = takeFrom(g.decoys);
      if (d) addObject(g, { name: d.name, kind: 'fixture', rooms: [r], hidden: false, desc: d.desc });
    }
  }

  const world: EscWorld = {
    rooms,
    objects: g.objects,
    order: g.order,
    combos: g.combos,
    locks: g.order.filter((id) => g.objects[id]!.lock),
    moveBudget: cfg.moveBudget,
    plan: [],
    optimal: 0,
  };
  const plan = planOptimal(world);
  if (!plan) return null;
  world.plan = plan;
  world.optimal = plan.length;
  return world;
}

// ─────────────────────────────────────────────────────────────────────────────
// Engine
// ─────────────────────────────────────────────────────────────────────────────

export function newEscState(world: EscWorld): EscState {
  const location: Record<string, number> = {};
  for (const id of world.order) {
    const o = world.objects[id]!;
    if (o.kind === 'item' && o.rooms[0]! >= 0) location[id] = o.rooms[0]!;
  }
  return {
    room: 0,
    moves: 0,
    inventory: [],
    location,
    revealed: [],
    unlocked: [],
    examined: [],
    gone: [],
    visited: [0],
    escaped: false,
    invalid: 0,
    failed: 0,
    wrongEntries: 0,
    lockLog: [],
  };
}

export function cloneEscState(s: EscState): EscState {
  return JSON.parse(JSON.stringify(s)) as EscState;
}

function isVisible(o: EscObject, s: EscState): boolean {
  return !o.hidden || s.revealed.includes(o.id);
}

/** Objects the player can see in the current room (fixtures, doors and loose items). */
export function roomObjects(world: EscWorld, s: EscState): EscObject[] {
  return world.order
    .map((id) => world.objects[id]!)
    .filter((o) => {
      if (!isVisible(o, s) || s.gone.includes(o.id)) return false;
      if (o.kind === 'item') return s.location[o.id] === s.room && !s.inventory.includes(o.id);
      return o.rooms.includes(s.room);
    });
}

function accessible(world: EscWorld, s: EscState): EscObject[] {
  return [...roomObjects(world, s), ...s.inventory.map((id) => world.objects[id]!)];
}

type Resolved = { obj: EscObject } | { error: string };

function resolve(world: EscWorld, s: EscState, text: string, pool: EscObject[] = accessible(world, s)): Resolved {
  const want = normName(text);
  if (!want) return { error: 'You need to say which object.' };
  const exact = pool.filter((o) => normName(o.name) === want);
  if (exact.length === 1) return { obj: exact[0]! };
  const words = want.split(' ');
  const partial = pool.filter((o) => {
    const n = normName(o.name);
    return n.endsWith(` ${want}`) || n.startsWith(`${want} `) || (words.length > 1 && n.includes(want));
  });
  if (partial.length === 1) return { obj: partial[0]! };
  if (partial.length > 1) return { error: `Which one do you mean: ${partial.map((o) => o.name).join(' or ')}?` };
  return { error: `You don't see any "${text.trim().toUpperCase()}" here.` };
}

function objTag(world: EscWorld, s: EscState, o: EscObject): string {
  if (o.kind === 'door') {
    const to = o.door!.leadsTo[s.room];
    const where = to === 'exit' ? 'the way out' : `to ${world.rooms[to as number]!.name}`;
    const state = s.unlocked.includes(o.id) ? 'open' : `locked · ${o.lock!.tag}`;
    return `(door · ${state}) — ${where}`;
  }
  if (o.kind === 'item') {
    const container = world.order.map((id) => world.objects[id]!).find((c) => c.contains.includes(o.id) || c.hides.includes(o.id));
    return `(item, ${container ? `from the ${container.name}` : o.place || 'here'})`;
  }
  if (o.lock) return s.unlocked.includes(o.id) ? '(open)' : `(locked · ${o.lock.tag})`;
  return '';
}

export function describeRoom(world: EscWorld, s: EscState): string {
  const room = world.rooms[s.room]!;
  const lines = [`ROOM ${s.room + 1} of 3: ${room.name.toUpperCase()} — ${room.intro}`, 'You see:'];
  for (const o of roomObjects(world, s)) lines.push(`- ${o.name} ${objTag(world, s, o)}`.trimEnd());
  return lines.join('\n');
}

function inventoryText(world: EscWorld, s: EscState): string {
  return s.inventory.length ? s.inventory.map((id) => world.objects[id]!.name).join(', ') : 'nothing';
}

export type EscCommand =
  | { kind: 'look' }
  | { kind: 'inventory' }
  | { kind: 'examine'; target: string }
  | { kind: 'take'; target: string }
  | { kind: 'use'; item: string; target: string }
  | { kind: 'enter'; value: string; target: string }
  | { kind: 'go'; target: string };

export function parseEscCommand(raw: string): EscCommand | null {
  const s = raw.toUpperCase().replace(/[“”"]/g, '').replace(/\s+/g, ' ').trim();
  let m: RegExpMatchArray | null;
  if (/^(LOOK|L|LOOK AROUND)$/.test(s)) return { kind: 'look' };
  if (/^(INVENTORY|INV|I)$/.test(s)) return { kind: 'inventory' };
  if ((m = s.match(/^(?:EXAMINE|X|INSPECT|READ|SEARCH|CHECK|OPEN|LOOK AT|LOOK IN|LOOK UNDER) (.+)$/))) return { kind: 'examine', target: m[1]! };
  if ((m = s.match(/^(?:TAKE|GET|GRAB|PICK UP) (.+)$/))) return { kind: 'take', target: m[1]! };
  if ((m = s.match(/^(?:USE|PUT|COMBINE|ATTACH|INSERT) (.+?) (?:ON|WITH|IN|INTO|TO) (.+)$/))) return { kind: 'use', item: m[1]!, target: m[2]! };
  if ((m = s.match(/^UNLOCK (.+?) WITH (.+)$/))) return { kind: 'use', item: m[2]!, target: m[1]! };
  if ((m = s.match(/^(?:ENTER|TYPE|DIAL|INPUT|PRESS|SET) (.+?) (?:ON|INTO|IN|AT) (.+)$/))) return { kind: 'enter', value: m[1]!, target: m[2]! };
  if ((m = s.match(/^(?:GO|WALK|EXIT|LEAVE)(?: THROUGH| TO| INTO)? (.+)$/))) return { kind: 'go', target: m[1]! };
  if ((m = s.match(/^ENTER (.+)$/))) return { kind: 'go', target: m[1]! };
  if (/^(EXIT|LEAVE|ESCAPE|GO OUT)$/.test(s)) return { kind: 'go', target: 'OUT' };
  return null;
}

function reveal(world: EscWorld, s: EscState, ids: string[]): string[] {
  const names: string[] = [];
  for (const id of ids) {
    if (!s.revealed.includes(id)) s.revealed.push(id);
    names.push(world.objects[id]!.name);
  }
  return names;
}

function unlock(world: EscWorld, s: EscState, o: EscObject): string {
  s.unlocked.push(o.id);
  s.lockLog.push({ id: o.id, move: s.moves });
  const found = reveal(world, s, o.contains);
  return found.length ? ` Inside you find: ${found.join(', ')}.` : '';
}

/** Apply one command. Every command (valid or not) costs one move. */
export function stepEscape(world: EscWorld, s: EscState, command: string | null, failure: string | null = null): EscStep {
  s.moves++;
  const move = s.moves;
  const bad = (text: string, invalid = false): EscStep => {
    if (invalid) s.invalid++;
    else s.failed++;
    return { move, action: command ?? '(none)', outcome: text, valid: !invalid, tone: 'bad' };
  };
  if (failure) return { ...bad(`${failure} The move is wasted.`, true), action: '(no action)' };
  if (!command) return bad('No ACTION line found in your reply — the move is wasted.', true);
  const cmd = parseEscCommand(command);
  if (!cmd) return bad(`Unrecognised action "${truncate(command, 60)}" — the move is wasted. Use LOOK, EXAMINE, TAKE, USE … ON …, ENTER … ON …, GO or INVENTORY.`, true);

  const ok = (text: string, tone: 'good' | 'neutral' = 'neutral'): EscStep => ({ move, action: command, outcome: text, valid: true, tone });

  switch (cmd.kind) {
    case 'look':
      return ok(describeRoom(world, s).replace(/\n/g, ' '));
    case 'inventory':
      return ok(`You are carrying: ${inventoryText(world, s)}.`);
    case 'examine': {
      const r = resolve(world, s, cmd.target);
      if ('error' in r) return bad(r.error);
      const o = r.obj;
      if (!s.examined.includes(o.id)) s.examined.push(o.id);
      const opened = s.unlocked.includes(o.id);
      let text = opened && o.openDesc ? o.openDesc : o.desc;
      if (o.kind === 'door' && opened) text = `${o.desc} It stands open.`;
      const hidden = o.hides.filter((id) => !s.revealed.includes(id));
      if (hidden.length) {
        const names = reveal(world, s, hidden);
        return ok(`${text} ${o.foundText ?? 'You find'}: ${names.join(', ')}!`, 'good');
      }
      if (opened) {
        const left = o.contains.filter((id) => s.location[id] === s.room && !s.inventory.includes(id) && !s.gone.includes(id));
        if (left.length) text += ` Inside: ${left.map((id) => world.objects[id]!.name).join(', ')}.`;
      }
      return ok(text);
    }
    case 'take': {
      const r = resolve(world, s, cmd.target);
      if ('error' in r) return bad(r.error);
      const o = r.obj;
      if (s.inventory.includes(o.id)) return bad(`You already have the ${o.name}.`);
      if (o.kind !== 'item') return bad(`You can't carry the ${o.name}.`);
      s.inventory.push(o.id);
      delete s.location[o.id];
      return ok(`You take the ${o.name}.`);
    }
    case 'use': {
      const ri = resolve(world, s, cmd.item);
      if ('error' in ri) return bad(ri.error);
      const item = ri.obj;
      if (!s.inventory.includes(item.id)) return bad(`You need to be holding the ${item.name} to use it (TAKE it first).`);
      const rt = resolve(world, s, cmd.target);
      if ('error' in rt) return bad(rt.error);
      const target = rt.obj;
      if (target.id === item.id) return bad('You cannot use something on itself.');
      const combo = world.combos.find((c) => (c.a === item.id && c.b === target.id) || (c.b === item.id && c.a === target.id));
      if (combo) {
        if (!s.inventory.includes(target.id)) return bad(`You need to be holding the ${target.name} as well (TAKE it first).`);
        s.inventory = s.inventory.filter((id) => id !== combo.a && id !== combo.b);
        s.gone.push(combo.a, combo.b);
        s.inventory.push(combo.result);
        if (!s.revealed.includes(combo.result)) s.revealed.push(combo.result);
        return ok(combo.text, 'good');
      }
      const lock = target.lock;
      if (lock && !s.unlocked.includes(target.id)) {
        if ((lock.kind === 'key' || lock.kind === 'tool' || lock.kind === 'combo') && lock.item === item.id) {
          if (lock.consumes) {
            s.inventory = s.inventory.filter((id) => id !== item.id);
            s.gone.push(item.id);
          }
          return ok(`${lock.openText}${unlock(world, s, target)}`, 'good');
        }
        if (lock.kind === 'key' && item.name.endsWith(' KEY')) return bad(`The ${item.name} doesn't fit the ${target.name}.`);
      }
      return bad(`Nothing happens when you use the ${item.name} on the ${target.name}.`);
    }
    case 'enter': {
      const rt = resolve(world, s, cmd.target);
      if ('error' in rt) return bad(rt.error);
      const target = rt.obj;
      const lock = target.lock;
      if (!lock || !(lock.kind === 'code' || lock.kind === 'word' || lock.kind === 'colour'))
        return bad(`The ${target.name} has nothing to enter a code into.`);
      if (s.unlocked.includes(target.id)) return bad(`The ${target.name} is already open.`);
      const value = normAnswer(lock.kind, cmd.value);
      if (value && value === lock.answer) return ok(`${lock.openText}${unlock(world, s, target)}`, 'good');
      s.wrongEntries++;
      return bad(`You enter ${value || cmd.value} on the ${target.name}. Nothing happens — wrong combination.`);
    }
    case 'go': {
      const doors = roomObjects(world, s).filter((o) => o.kind === 'door');
      const r = resolve(world, s, cmd.target, doors);
      let door: EscObject | undefined = 'obj' in r ? r.obj : undefined;
      if (!door) {
        // Also accept the destination room's name ("GO THE LIBRARY") or BACK / FORWARD / OUT.
        const want = normName(cmd.target);
        door = doors.find((d) => {
          const to = d.door!.leadsTo[s.room];
          if (to === 'exit') return want === 'OUT' || want === 'OUTSIDE' || want === 'EXIT';
          if (want === 'BACK') return (to as number) < s.room;
          if (want === 'FORWARD' || want === 'ON' || want === 'ONWARD') return (to as number) > s.room;
          return normName(world.rooms[to as number]!.name) === want;
        });
      }
      if (!door) return bad('error' in r ? r.error.replace("You don't see any", 'There is no door called') : 'There is no such door here.');
      if (!s.unlocked.includes(door.id)) return bad(`The ${door.name} is locked.`);
      const to = door.door!.leadsTo[s.room]!;
      if (to === 'exit') {
        s.escaped = true;
        return ok(`You step through the ${door.name} into the fresh air. YOU ESCAPED!`, 'good');
      }
      s.room = to;
      if (!s.visited.includes(to)) s.visited.push(to);
      return ok(`You go through the ${door.name} into ${world.rooms[to]!.name}.`, 'good');
    }
  }
}

/** Commands that make sense right now (for the "Available actions" list). */
export function escapeActions(world: EscWorld, s: EscState): string[] {
  const here = roomObjects(world, s);
  const out: string[] = ['LOOK', 'INVENTORY'];
  for (const o of here) out.push(`EXAMINE ${o.name}`);
  for (const id of s.inventory) out.push(`EXAMINE ${world.objects[id]!.name}`);
  for (const o of here) if (o.kind === 'item') out.push(`TAKE ${o.name}`);
  const lockedHere = here.filter((o) => o.lock && !s.unlocked.includes(o.id));
  for (const id of s.inventory) {
    const item = world.objects[id]!;
    for (const t of lockedHere) if (!(t.lock!.kind === 'code' || t.lock!.kind === 'word' || t.lock!.kind === 'colour')) out.push(`USE ${item.name} ON ${t.name}`);
    for (const other of s.inventory) if (other !== id) out.push(`USE ${item.name} ON ${world.objects[other]!.name}`);
  }
  for (const d of here) if (d.kind === 'door' && s.unlocked.includes(d.id)) out.push(`GO ${d.name}`);
  return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// Optimal planner
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Executes the necessary-actions-only strategy on a scratch state: every
 * action it takes is required exactly once, and nothing forces backtracking
 * (clues and items for a room always sit in that room or an earlier one),
 * so the resulting plan is a shortest solution for a first-time solver.
 */
export function planOptimal(world: EscWorld): string[] | null {
  const s = newEscState(world);
  const plan: string[] = [];
  const objs = world.order.map((id) => world.objects[id]!);
  const neededItems = new Set<string>();
  const neededClues = new Set<string>();
  for (const o of objs) {
    if (!o.lock) continue;
    if (o.lock.item) neededItems.add(o.lock.item);
    for (const n of o.lock.needs) neededClues.add(n);
  }
  for (const c of world.combos) {
    if (neededItems.has(c.result)) {
      neededItems.add(c.a);
      neededItems.add(c.b);
    }
  }
  const run = (cmd: string): void => {
    const r = stepEscape(world, s, cmd);
    if (!r.valid || r.tone === 'bad') throw new Error(`planner command failed: ${cmd} → ${r.outcome}`);
    plan.push(cmd);
  };
  try {
    for (let guard = 0; guard < 200 && !s.escaped; guard++) {
      const here = roomObjects(world, s);
      const inv = new Set(s.inventory);
      // 1. Open anything openable.
      const lockable = here.find((o) => {
        if (!o.lock || s.unlocked.includes(o.id)) return false;
        if (o.lock.item) return inv.has(o.lock.item);
        return o.lock.needs.every((n) => s.examined.includes(n));
      });
      if (lockable) {
        const l = lockable.lock!;
        run(l.item ? `USE ${world.objects[l.item]!.name} ON ${lockable.name}` : `ENTER ${l.answer} ON ${lockable.name}`);
        continue;
      }
      // 2. Combine parts that are both in hand.
      const combo = world.combos.find((c) => inv.has(c.a) && inv.has(c.b) && neededItems.has(c.result));
      if (combo) {
        run(`USE ${world.objects[combo.a]!.name} ON ${world.objects[combo.b]!.name}`);
        continue;
      }
      // 3. Take needed items.
      const item = here.find((o) => o.kind === 'item' && neededItems.has(o.id));
      if (item) {
        run(`TAKE ${item.name}`);
        continue;
      }
      // 4. Search hiding places that still hide something.
      const spot = here.find((o) => o.hides.some((id) => !s.revealed.includes(id)));
      if (spot) {
        run(`EXAMINE ${spot.name}`);
        continue;
      }
      // 5. Read clues that some lock needs.
      const clue = [...here, ...s.inventory.map((id) => world.objects[id]!)].find((o) => neededClues.has(o.id) && !s.examined.includes(o.id));
      if (clue) {
        run(`EXAMINE ${clue.name}`);
        continue;
      }
      // 6. Move on.
      const door = here.find((o) => o.kind === 'door' && s.unlocked.includes(o.id) && (o.door!.leadsTo[s.room] === 'exit' || (o.door!.leadsTo[s.room] as number) > s.room));
      if (!door) return null;
      run(`GO ${door.name}`);
    }
  } catch {
    return null;
  }
  return s.escaped ? plan : null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Observation & replay
// ─────────────────────────────────────────────────────────────────────────────

export function escapeObservation(world: EscWorld, s: EscState, noteLine: string, recent: readonly string[]): string {
  const left = world.moveBudget - s.moves;
  const lines: string[] = [];
  lines.push(
    `THE ESCAPE ROOM — Move ${s.moves + 1} of ${world.moveBudget} (${left} left including this one) · Locks opened so far: ${s.unlocked.length}`,
  );
  lines.push(describeRoom(world, s));
  lines.push(`Inventory: ${inventoryText(world, s)}`);
  lines.push('Recent events:');
  if (recent.length === 0) lines.push('- You wake up on the floor of a locked room. Somewhere, a clock you cannot see is ticking.');
  for (const e of recent) lines.push(`- ${e}`);
  lines.push(noteLine);
  lines.push(`Available actions: ${escapeActions(world, s).map((c) => `\`${c}\``).join(', ')}`);
  lines.push('To try a code, word or colour sequence: ENTER <digits / letters / colours> ON <object>.');
  lines.push('Reply with brief reasoning if you like, then end with exactly one line: ACTION: <command> (optionally a NOTE: <memo> line just before it).');
  return lines.join('\n');
}

export const ESCAPE_LEGEND: Record<string, { label: string; color?: string; emoji?: string }> = {
  '@': { label: 'You', color: '#EF4444', emoji: '🧍' },
  o: { label: 'Visited room', color: '#10B981', emoji: '🟩' },
  '.': { label: 'Unexplored room', color: '#374151', emoji: '⬛' },
  '#': { label: 'Locked door', color: '#B45309', emoji: '🔒' },
  '/': { label: 'Open door', color: '#22C55E', emoji: '🔓' },
  E: { label: 'Exit', color: '#F59E0B', emoji: '🌅' },
};

export function escapeGrid(world: EscWorld, s: EscState): { rows: string[]; legend: typeof ESCAPE_LEGEND } {
  const doors = world.order.map((id) => world.objects[id]!).filter((o) => o.kind === 'door');
  let row = '';
  for (let r = 0; r < 3; r++) {
    row += !s.escaped && s.room === r ? '@' : s.visited.includes(r) ? 'o' : '.';
    row += s.unlocked.includes(doors[r]!.id) ? '/' : '#';
  }
  row += s.escaped ? '@' : 'E';
  const used = new Set(row);
  return { rows: [row], legend: Object.fromEntries(Object.entries(ESCAPE_LEGEND).filter(([k]) => used.has(k))) };
}

export function escapeFrame(world: EscWorld, s: EscState, step: number, frame: Omit<ReplayFrame, 'step' | 'stats' | 'grid'>): ReplayFrame {
  const total = world.locks.length;
  return {
    step,
    ...frame,
    stats: {
      progress: Math.round((100 * s.unlocked.length) / total),
      budget: Math.max(0, Math.round((100 * (world.moveBudget - s.moves)) / world.moveBudget)),
      locks: `${s.unlocked.length}/${total}`,
      move: s.moves,
      room: world.rooms[s.room]!.name,
      inventory: inventoryText(world, s),
    },
    grid: escapeGrid(world, s),
  };
}
