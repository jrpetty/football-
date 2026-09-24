/**
 * Needle in a Haystack — the needle bank.
 *
 * Every needle is a question with a single deterministic answer, the
 * sentence(s) that must be planted to make it answerable, and near-miss
 * distractor sentences (similar names / numbers about a sibling subject)
 * that are planted elsewhere to punish sloppy retrieval.
 */
import type { Rng } from '../../core/types.ts';
import { GUILDS, STREET_CORES, TEMPLES, VILLAGES, DISTRICTS, makeShips } from './needle-haystack-corpus.ts';
import type { NameForge, Person } from './needle-haystack-corpus.ts';

export type NeedleKind = 'single' | 'multi-hop' | 'three-hop' | 'aggregate' | 'superseded';

export interface NeedleSpec {
  kind: NeedleKind;
  /** Short label for tables, e.g. "lighthouse keeper". */
  topic: string;
  question: string;
  /** Canonical answer shown in results. */
  expected: string;
  /** Set for numeric answers. */
  numeric: number | null;
  /** Accepted aliases for text answers (matched as whole words after normalisation). */
  accept: string[];
  /** Distractor aliases: an answer containing any of these is wrong. */
  reject: string[];
  /** Distractor / superseded numbers (reported as traps when given). */
  rejectNumbers: number[];
  /** Sentences to plant, in the order they must appear in the document. */
  parts: string[];
  distractors: string[];
}

const DIRS = ['Northern', 'Southern', 'Eastern', 'Western'];
const BELLS: Array<[string, string]> = [
  ['Old Grumble', 'grumble'],
  ['Brother Thunder', 'thunder'],
  ["the Widow's Voice", 'widow'],
  ['Sweet Agathe', 'agathe'],
  ['Great Tom', 'tom'],
  ['Honest Margery', 'margery'],
  ['the Sleepless One', 'sleepless'],
  ['Mother Clang', 'clang'],
];
const ANIMALS = ['heron', 'lynx', 'otter', 'stag', 'salamander', 'pike', 'badger', 'kestrel', 'boar', 'swan', 'hare', 'crab'];
const COLOURS = ['ochre', 'indigo', 'white', 'black', 'green', 'scarlet', 'azure', 'violet'];
const PLACES = ["the Old Weighhouse", "the Founders' Arch", 'the Salt Steps', 'the Mint', 'the Fishmarket Cross', 'the Guildhall'];
const FESTIVALS = ['the Lantern Fair', 'the Feast of Brine', "Founders' Day", 'the Midsummer Regatta', 'the Apple Wake', 'the Tide Festival'];
const HORSES: Array<[string, string]> = [
  ['Moonwhistle', 'moonwhistle'],
  ['Saffron Dawn', 'saffron dawn'],
  ['Little Tempest', 'tempest'],
  ['Quicksilver Jenny', 'jenny'],
  ['Harrowby Lass', 'harrowby'],
  ['Cinderheel', 'cinderheel'],
  ['Starling', 'starling'],
  ['Duchess of Mists', 'mists'],
];
const WATCHWORDS = ['juniper', 'blackthorn', 'vesper', 'cobalt', 'marigold', 'tinderbox', 'larkspur', 'windlass', 'sable', 'quicksilver'];
const TREE_ADJ = ["Hangman's", 'Weeping', 'Crooked', 'Parliament', 'Lightning', "Bishop's"];
const SQUARES = ["Tanners' Square", 'the Haymarket', 'Candle Square', 'the Old Green', 'Gallows Square', 'the Corn Exchange Square'];
const PETS = ['ferret', 'raven', 'tortoise', 'goose', 'monkey', 'hedgehog'];
const PET_NAMES: Array<[string, string]> = [
  ['Pepperpot', 'pepperpot'],
  ['Admiral Crumb', 'crumb'],
  ['Biscuit', 'biscuit'],
  ['Lord Wobble', 'wobble'],
  ['Nettle', 'nettle'],
  ['Tuppence', 'tuppence'],
  ['Figaro', 'figaro'],
  ['Mistress Soot', 'soot'],
];
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const DISHES = ['Harbour Pie', 'Lantern Cake', "Widow's Stew", 'Brine Pudding', 'Salt-Crust Bream', 'Honey Loaf'];
const BUILDINGS = ['the Exchange', 'the Customs House', 'the Grand Library', 'the Opera House', "the Seamen's Hospital", 'the Water Stair'];
const DAUGHTERS = ['Wilhelmina', 'Clemence', 'Ottilie', 'Rosamund', 'Henrietta', 'Leocadia', 'Mathilde', 'Seraphine'];
const MINES: Array<[string, string]> = [
  ['Karsk', 'Karst'],
  ['Ulmfen', 'Ulmfell'],
  ['Brakka', 'Brakkan'],
  ['Dorrow', 'Darrow'],
  ['Tisk', 'Tusk'],
  ['Hollin', 'Hollins'],
];
const FORESTS: Array<[string, string]> = [
  ['Blackwold', 'Blackwood'],
  ['Ashenholt', 'Ashenhold'],
  ['Grimmerwood', 'Grimmwood'],
];
const STREET_SUFFIXES = ['Lane', 'Street', 'Row', 'Way', 'Walk', 'Yard'];

function cap(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function ordinal(n: number): string {
  const teen = n % 100 >= 11 && n % 100 <= 13;
  const suffix = teen ? 'th' : n % 10 === 1 ? 'st' : n % 10 === 2 ? 'nd' : n % 10 === 3 ? 'rd' : 'th';
  return `${n}${suffix}`;
}

/** Thousands separators without locale dependence. */
export function withCommas(n: number): string {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** A plausible near-miss of a number: swap the last two digits, else nudge. */
export function nearMissNumber(rng: Rng, n: number): number {
  const s = String(n);
  if (s.length >= 2 && s[s.length - 1] !== s[s.length - 2] && s[s.length - 2] !== '0') {
    const swapped = Number(s.slice(0, -2) + s[s.length - 1] + s[s.length - 2]);
    if (swapped !== n) return swapped;
  }
  return n + rng.pick([-1, 1]) * rng.int(3, 9);
}

/** Pools that must stay disjoint across needles (so no two needles share a subject). */
export class NeedleEnv {
  readonly rng: Rng;
  readonly forge: NameForge;
  readonly city: string;
  readonly startYear: number;
  private readonly pools = new Map<string, unknown[]>();

  constructor(rng: Rng, forge: NameForge, city: string, startYear: number, fillerStreetCores: readonly string[], fillerShips: readonly string[]) {
    this.rng = rng;
    this.forge = forge;
    this.city = city;
    this.startYear = startYear;
    this.pools.set('street', rng.shuffle(STREET_CORES.filter((c) => !fillerStreetCores.includes(c))));
    this.pools.set('ship', rng.shuffle(makeShips(rng, 100).filter((s) => !fillerShips.includes(s))));
  }

  /** Takes an item from a named pool without replacement. */
  take<T>(pool: string, source: readonly T[]): T {
    if (!this.pools.has(pool)) this.pools.set(pool, this.rng.shuffle(source));
    const items = this.pools.get(pool) as T[];
    const item = items.shift();
    if (item === undefined) throw new Error(`needle pool "${pool}" exhausted`);
    return item;
  }

  street(): { full: string; core: string } {
    const core = this.take<string>('street', []);
    return { full: `${core} ${this.rng.pick(STREET_SUFFIXES)}`, core };
  }

  person(): Person {
    return this.forge.person();
  }

  /** Villages used by needles (removed from the filler's vocabulary). */
  readonly usedVillages: string[] = [];
  village(): string {
    const v = this.take('village', VILLAGES);
    this.usedVillages.push(v);
    return v;
  }
}

type Builder = (e: NeedleEnv) => NeedleSpec;

function base(kind: NeedleKind, topic: string, question: string): Omit<NeedleSpec, 'expected' | 'parts'> {
  return { kind, topic, question, numeric: null, accept: [], reject: [], rejectNumbers: [], distractors: [] };
}

export const SINGLE_BUILDERS: Record<string, Builder> = {
  lighthouse(e) {
    const dir = e.take('dir', DIRS);
    const dir2 = e.take('dir', DIRS);
    const keeper = e.person();
    const miss = e.forge.nearMiss(keeper);
    return {
      ...base('single', 'lighthouse keeper', `Who kept the lighthouse on the ${dir} Mole?`),
      expected: keeper.full,
      accept: [keeper.full, keeper.last],
      reject: [miss.last],
      parts: [`In those years the lighthouse on the ${dir} Mole was kept by ${keeper.full}, who is said never to have let the lamp go out.`],
      distractors: [`The small beacon on the ${dir2} Mole was tended by ${miss.full}, a quiet man who also kept goats.`],
    };
  },
  aqueduct(e) {
    const n = e.rng.int(140, 480);
    const n2 = nearMissNumber(e.rng, n);
    return {
      ...base('single', 'aqueduct arches', 'On how many arches does the Great Aqueduct stand?'),
      expected: String(n),
      numeric: n,
      rejectNumbers: [n2],
      parts: [`When the Great Aqueduct was finally completed, it strode across the valley on exactly ${n} arches.`],
      distractors: [`The Lesser Aqueduct, built a generation later, needed only ${n2} arches.`],
    };
  },
  bell(e) {
    const temple = e.take('temple', TEMPLES);
    const temple2 = e.take('temple', TEMPLES);
    const [bell, core] = e.take('bell', BELLS);
    const [bell2, core2] = e.take('bell', BELLS);
    return {
      ...base('single', 'bell name', `What name did the bell-ringers give the great bell of ${temple}?`),
      expected: bell,
      accept: [bell, core],
      reject: [core2],
      parts: [`The great bell of ${temple} was hung that spring, and the bell-ringers named it ${bell}.`],
      distractors: [`The little bell of ${temple2}, cracked since the day it was cast, was known to everyone as ${bell2}.`],
    };
  },
  banner(e) {
    const guild = e.take('guild', GUILDS);
    const guild2 = e.take('guild', GUILDS);
    const animal = e.take('animal', ANIMALS);
    const animal2 = e.take('animal', ANIMALS);
    const colour = e.take('colour', COLOURS);
    const colour2 = e.take('colour', COLOURS);
    return {
      ...base('single', 'guild banner', `What animal appears on the banner of ${guild}?`),
      expected: animal,
      accept: [animal],
      reject: [animal2],
      parts: [`${cap(guild)} adopted a ${colour} banner bearing a ${animal}, and carried it in every procession thereafter.`],
      distractors: [`${cap(guild2)} marched under a ${colour2} banner showing a ${animal2}.`],
    };
  },
  press(e) {
    const printer = e.person();
    const st = e.street();
    const st2 = e.street();
    return {
      ...base('single', 'printing press', `On which street was the first printing press in ${e.city} set up?`),
      expected: st.full,
      accept: [st.full, st.core],
      reject: [st2.core],
      parts: [`The first printing press in ${e.city} was set up by ${printer.full} in a cellar on ${st.full}.`],
      distractors: [`A second, larger printing press was opened years later on ${st2.full}.`],
    };
  },
  chest(e) {
    const place = e.take('place', PLACES);
    const place2 = e.take('place', PLACES);
    const n = e.rng.int(120, 980);
    const n2 = nearMissNumber(e.rng, n);
    return {
      ...base('single', "founders' chest", `How many silver coins were found in the founders' chest beneath ${place}?`),
      expected: String(n),
      numeric: n,
      rejectNumbers: [n2],
      parts: [`The founders' chest buried beneath ${place} was opened before the whole council and found to hold ${n} silver coins.`],
      distractors: [`A second chest, dug up under ${place2}, held ${n2} copper coins and a broken key.`],
    };
  },
  mare(e) {
    const fest = e.take('festival', FESTIVALS);
    const [horse, core] = e.take('horse', HORSES);
    const [horse2, core2] = e.take('horse', HORSES);
    return {
      ...base('single', 'winning mare', `What was the name of the grey mare that won the races at ${fest}?`),
      expected: horse,
      accept: [horse, core],
      reject: [core2],
      parts: [`The races at ${fest} were won that year by a grey mare called ${horse}.`],
      distractors: [`A chestnut colt named ${horse2} came second in the races at ${fest}, to the fury of his owner.`],
    };
  },
  watchword(e) {
    const w1 = e.take('watchword', WATCHWORDS);
    const w2 = e.take('watchword', WATCHWORDS);
    return {
      ...base('single', 'watchword', 'What was the watchword of the Night Watch?'),
      expected: w1,
      accept: [w1],
      reject: [w2],
      parts: [`That year the watchword of the Night Watch was "${w1}", and anyone abroad after curfew had to give it.`],
      distractors: [`The Day Watch, never to be outdone, chose the word "${w2}".`],
    };
  },
  oak(e) {
    const oak = `the ${e.take('tree', TREE_ADJ)} Oak`;
    const elm = `the ${e.take('tree', TREE_ADJ)} Elm`;
    const square = e.take('square', SQUARES);
    const n = e.rng.int(18, 46);
    const n2 = n + e.rng.pick([-1, 1]) * e.rng.int(3, 9);
    return {
      ...base('single', 'oldest tree', `How many feet around the trunk was ${oak} measured at?`),
      expected: String(n),
      numeric: n,
      rejectNumbers: [n2],
      parts: [`${cap(oak)} in ${square}, the oldest tree in the city, was measured at ${n} feet around the trunk.`],
      distractors: [`${cap(elm)}, which stood not far away, measured ${n2} feet around.`],
    };
  },
  pet(e) {
    const mayor = e.person();
    const rival = e.person();
    const animal = e.take('pet', PETS);
    const [name, core] = e.take('petname', PET_NAMES);
    const [name2, core2] = e.take('petname', PET_NAMES);
    return {
      ...base('single', "mayor's pet", `What was the name of the tame ${animal} kept by Lady Mayor ${mayor.last}?`),
      expected: name,
      accept: [name, core],
      reject: [core2],
      parts: [`Lady Mayor ${mayor.full} kept a tame ${animal} named ${name}, which followed her into council meetings.`],
      distractors: [`Her rival ${rival.full} kept a parrot named ${name2}, which bit two aldermen.`],
    };
  },
  treaty(e) {
    const rival = e.forge.city();
    const rival2 = e.forge.city();
    const month = e.rng.pick(MONTHS);
    const day = e.rng.int(2, 28);
    let day2 = e.rng.int(2, 28);
    if (day2 === day) day2 = day === 28 ? 27 : day + 1;
    const guild = e.take('guild', GUILDS);
    return {
      ...base('single', 'treaty date', `On which day of ${month} was the treaty of friendship with ${rival} signed?`),
      expected: String(day),
      numeric: day,
      rejectNumbers: [day2],
      parts: [`The treaty of friendship with ${rival} was signed on the ${ordinal(day)} day of ${month}, in the hall of ${guild}.`],
      distractors: [`A trade agreement with ${rival2} followed on the ${ordinal(day2)} day of ${month}.`],
    };
  },
  saffron(e) {
    const dish = e.take('dish', DISHES);
    const dish2 = e.take('dish', DISHES);
    const district = e.rng.pick(DISTRICTS);
    const n = e.rng.int(7, 39);
    let n2 = e.rng.int(7, 39);
    if (n2 === n) n2 = n + 4;
    return {
      ...base('single', 'saffron recipe', `How many threads of saffron did the famous ${dish} call for?`),
      expected: String(n),
      numeric: n,
      rejectNumbers: [n2],
      parts: [`The famous ${dish} of ${district} called for exactly ${n} threads of saffron, no more and no fewer.`],
      distractors: [`${dish2}, its humbler cousin, used ${n2} cloves instead.`],
    };
  },
};

export const MULTI_BUILDERS: Record<string, Builder> = {
  foundry(e) {
    const temple = e.take('temple', TEMPLES);
    const founder = e.person();
    const founder2 = e.forge.nearMiss(founder);
    const st = e.street();
    const st2 = e.street();
    return {
      ...base('multi-hop', 'bell-founder → foundry', `On which street was the foundry of the bell-founder who cast the great bronze bell of ${temple}?`),
      expected: st.full,
      accept: [st.full, st.core],
      reject: [st2.core],
      parts: [
        `The great bronze bell of ${temple} was cast by the bell-founder ${founder.full}.`,
        `${founder.full} kept a foundry on ${st.full}, beside the old tannery.`,
      ],
      distractors: [`${founder2.full} kept a foundry on ${st2.full} and cast mostly cannon.`],
    };
  },
  captain(e) {
    const ship = e.take<string>('ship', []);
    const captain = e.person();
    const captain2 = e.forge.nearMiss(captain);
    const village = e.village();
    const village2 = e.village();
    return {
      ...base('multi-hop', 'captain → birthplace', `In which village was the captain of ${ship} on her last voyage born?`),
      expected: village,
      accept: [village],
      reject: [village2],
      parts: [
        `On her last voyage ${ship} was commanded by Captain ${captain.full}.`,
        `${captain.full} had been born in the hill village of ${village}, far from any sea.`,
      ],
      distractors: [`Captain ${captain2.full} was born in ${village2} and never once lost a ship.`],
    };
  },
  architect(e) {
    const building = e.take('building', BUILDINGS);
    const architect = e.person();
    const architect2 = e.forge.nearMiss(architect);
    const daughter = e.take('daughter', DAUGHTERS);
    const daughter2 = e.take('daughter', DAUGHTERS);
    return {
      ...base('multi-hop', 'architect → daughter', `What was the first name of the only daughter of the architect who designed ${building}?`),
      expected: daughter,
      accept: [daughter],
      reject: [daughter2],
      parts: [
        `${cap(building)} was designed by the architect ${architect.full}.`,
        `${architect.full}'s only daughter, ${daughter}, later became harbour master of ${e.city}.`,
      ],
      distractors: [`${architect2.full}'s only daughter, ${daughter2}, became a celebrated glassblower.`],
    };
  },
  guildLeader(e) {
    const guild = e.take('guild', GUILDS);
    const leader = e.person();
    const leader2 = e.forge.nearMiss(leader);
    const year = e.startYear + e.rng.int(20, 90);
    const year2 = year + e.rng.pick([-1, 1]) * e.rng.int(2, 9);
    const temple = e.take('temple', TEMPLES);
    return {
      ...base('multi-hop', 'guild leader → death', `In which year did the leader of ${guild} die?`),
      expected: String(year),
      numeric: year,
      rejectNumbers: [year2],
      parts: [
        `In those years ${guild} was led by ${leader.full}.`,
        `${leader.full} died of a fever in the year ${year} and was buried at ${temple}.`,
      ],
      distractors: [`${leader2.full}, a wealthy wine merchant, died in the year ${year2}.`],
    };
  },
};

export const AGGREGATE_BUILDERS: Record<string, Builder> = {
  salt(e) {
    const [mine, near] = e.take('mine', MINES);
    const [a, b, c, d] = e.rng.shuffle(Array.from({ length: 54 }, (_, i) => i + 11)).slice(0, 4) as [number, number, number, number];
    const total = a + b + c;
    return {
      ...base('aggregate', 'salt wagons (sum of 3)', `According to the chronicle, how many wagons of salt in total came from the mines of ${mine}?`),
      expected: String(total),
      numeric: total,
      rejectNumbers: [total + d],
      parts: [
        `That autumn ${a} wagons of salt came down from the mines of ${mine}.`,
        `Before the passes closed, the mines of ${mine} sent another ${b} wagons of salt to the city.`,
        `In the spring a further ${c} wagons of salt arrived from the mines of ${mine}.`,
      ],
      distractors: [`The mines of ${near} sent ${d} wagons of salt that year, most of it spoiled by rain.`],
    };
  },
  pitch(e) {
    const [forest, near] = e.take('forest', FORESTS);
    const [a, b, c, d] = e.rng.shuffle(Array.from({ length: 70 }, (_, i) => i + 15)).slice(0, 4) as [number, number, number, number];
    const total = a + b + c;
    return {
      ...base('aggregate', 'pitch barrels (sum of 3)', `According to the chronicle, how many barrels of pitch in total were brought from the forest of ${forest}?`),
      expected: String(total),
      numeric: total,
      rejectNumbers: [total + d],
      parts: [
        `The shipwrights bought ${a} barrels of pitch brought from the forest of ${forest}.`,
        `Charcoal-burners from the forest of ${forest} delivered ${b} more barrels of pitch before the rains.`,
        `A last load of ${c} barrels of pitch came from the forest of ${forest} in the autumn.`,
      ],
      distractors: [`The forest of ${near} supplied ${d} barrels of pitch, but the shipwrights judged it poor.`],
    };
  },
};

export const SUPERSEDED_BUILDERS: Record<string, Builder> = {
  clockTower(e) {
    const square = e.take('square', SQUARES);
    const h1 = e.rng.int(40, 90);
    const h2 = h1 + e.rng.pick([-1, 1]) * e.rng.int(3, 12);
    const surveyor = e.person();
    return {
      ...base('superseded', 'clock tower (corrected)', `How tall is the Clock Tower in ${square}, in cubits?`),
      expected: String(h2),
      numeric: h2,
      rejectNumbers: [h1],
      parts: [
        `The old charter records that the Clock Tower in ${square} stands ${h1} cubits high.`,
        `A careful survey by ${surveyor.full} corrected the old charter: the Clock Tower in ${square} actually stands ${h2} cubits high.`,
      ],
      distractors: [],
    };
  },
  census(e) {
    const year = e.startYear + e.rng.int(10, 60);
    const p1 = e.rng.int(2000, 9000);
    const p2 = p1 + e.rng.pick([-1, 1]) * e.rng.int(120, 900);
    return {
      ...base('superseded', 'census (corrected)', `According to the chronicle, how many households were there within the walls at the census of ${year}?`),
      expected: String(p2),
      numeric: p2,
      rejectNumbers: [p1],
      parts: [
        `The census of ${year} counted ${withCommas(p1)} households within the walls.`,
        `Clerks later found an error in the census of ${year}: the true count was ${withCommas(p2)} households within the walls.`,
      ],
      distractors: [],
    };
  },
};

/** Chooses the needle mix: 2 multi-hop, 1 aggregate, 1 superseded, the rest single facts. */
export function buildNeedles(e: NeedleEnv, count: number): NeedleSpec[] {
  const singles = e.rng.shuffle(Object.keys(SINGLE_BUILDERS)).slice(0, Math.max(0, count - 4));
  const multis = e.rng.shuffle(Object.keys(MULTI_BUILDERS)).slice(0, 2);
  const agg = e.rng.pick(Object.keys(AGGREGATE_BUILDERS));
  const sup = e.rng.pick(Object.keys(SUPERSEDED_BUILDERS));
  return [
    ...singles.map((k) => SINGLE_BUILDERS[k]!(e)),
    ...multis.map((k) => MULTI_BUILDERS[k]!(e)),
    AGGREGATE_BUILDERS[agg]!(e),
    SUPERSEDED_BUILDERS[sup]!(e),
  ];
}

// ─────────────────────────────────────────────────────────────────────────────
// Hard tier: 3-hop chains, five-place sums, superseded values with look-alikes.
// Every hop has a near-miss decoy (Daskwell vs Taskwell), so skimming fails.
// ─────────────────────────────────────────────────────────────────────────────

function distinctInts(e: NeedleEnv, count: number, min: number, max: number): number[] {
  return e.rng.shuffle(Array.from({ length: max - min + 1 }, (_, i) => i + min)).slice(0, count);
}

export const THREE_HOP_BUILDERS: Record<string, Builder> = {
  ship3(e) {
    const ship = e.take<string>('ship', []);
    const captain = e.person();
    const mate = e.person();
    const captainN = e.forge.nearMiss(captain);
    const mateN = e.forge.nearMiss(mate);
    const other = e.person();
    const [village, v2, v3] = [e.village(), e.village(), e.village()];
    return {
      ...base('three-hop', 'captain → first mate → birthplace', `In which village was the first mate of the captain of ${ship} on her maiden voyage born?`),
      expected: village,
      accept: [village],
      reject: [v2, v3],
      parts: [
        `On her maiden voyage ${ship} was commanded by Captain ${captain.full}.`,
        `Captain ${captain.full}'s first mate was ${mate.full}, a quiet sailor from the hills.`,
        `${mate.full} had been born in the village of ${village}.`,
      ],
      distractors: [
        `Captain ${captainN.full}'s first mate was ${other.full}, who never learned to swim.`,
        `${mateN.full} had been born in the village of ${v2}.`,
        `${other.full} was born in the village of ${v3}.`,
      ],
    };
  },
  architect3(e) {
    const building = e.take('building', BUILDINGS);
    const architect = e.person();
    const master = e.person();
    const architectN = e.forge.nearMiss(architect);
    const masterN = e.forge.nearMiss(master);
    const other = e.person();
    const [d1, d2, d3] = [e.take('daughter', DAUGHTERS), e.take('daughter', DAUGHTERS), e.take('daughter', DAUGHTERS)];
    const temple = e.take('temple', TEMPLES);
    return {
      ...base('three-hop', 'architect → master → daughter', `What was the first name of the only daughter of the master mason under whom the architect of ${building} trained?`),
      expected: d1,
      accept: [d1],
      reject: [d2, d3],
      parts: [
        `${cap(building)} was designed by the architect ${architect.full}.`,
        `As a young man ${architect.full} had trained under the master mason ${master.full}.`,
        `${master.full}'s only daughter, ${d1}, later kept the city archives.`,
      ],
      distractors: [
        `${architectN.full} trained under the master mason ${other.full}.`,
        `${other.full}'s only daughter, ${d2}, became a ferrywoman.`,
        `${masterN.full}'s only daughter, ${d3}, sang in the choir of ${temple}.`,
      ],
    };
  },
  guild3(e) {
    const guild = e.take('guild', GUILDS);
    const leader = e.person();
    const ally = e.person();
    const leaderN = e.forge.nearMiss(leader);
    const allyN = e.forge.nearMiss(ally);
    const other = e.person();
    const year = e.startYear + e.rng.int(20, 90);
    const [dy2, dy3] = distinctInts(e, 2, 2, 9);
    const year2 = year + dy2!;
    const year3 = year - dy3!;
    return {
      ...base('three-hop', 'guild leader → ally → death', `In which year did the closest ally of the leader of ${guild} die?`),
      expected: String(year),
      numeric: year,
      rejectNumbers: [year2, year3],
      parts: [
        `In those years ${guild} was led by ${leader.full}.`,
        `The closest ally of ${leader.full} on the council was the alderman ${ally.full}.`,
        `${ally.full} died of a fever in the year ${year}.`,
      ],
      distractors: [
        `The closest ally of ${leaderN.full} was the alderman ${other.full}.`,
        `${other.full} died in the year ${year2}.`,
        `${allyN.full} died in the year ${year3}.`,
      ],
    };
  },
};

export const SUM5_BUILDERS: Record<string, Builder> = {
  salt5(e) {
    const [mine, near] = e.take('mine', MINES);
    const [a, b, c, d, f, iron, other, promised] = distinctInts(e, 8, 11, 64) as [number, number, number, number, number, number, number, number];
    const total = a + b + c + d + f;
    return {
      ...base('aggregate', 'salt wagons (sum of 5)', `According to the chronicle, how many wagons of salt in total actually arrived from the mines of ${mine}?`),
      expected: String(total),
      numeric: total,
      rejectNumbers: [total + promised, total + iron, total + other],
      parts: [
        `That autumn ${a} wagons of salt came down from the mines of ${mine}.`,
        `Before the passes closed, the mines of ${mine} sent another ${b} wagons of salt to the city.`,
        `In the spring a further ${c} wagons of salt arrived from the mines of ${mine}.`,
        `The mines of ${mine} delivered ${d} wagons of salt in time for the herring season.`,
        `A late convoy brought ${f} wagons of salt down from the mines of ${mine} just before the first snow.`,
      ],
      distractors: [
        `The same year ${iron} wagons of iron came down from the mines of ${mine}.`,
        `The mines of ${near} sent ${other} wagons of salt, most of it spoiled by rain.`,
        `A further ${promised} wagons of salt were promised by the mines of ${mine}, but the road collapsed and they never arrived.`,
      ],
    };
  },
  pitch5(e) {
    const [forest, near] = e.take('forest', FORESTS);
    const [a, b, c, d, f, tar, other, cancelled] = distinctInts(e, 8, 15, 84) as [number, number, number, number, number, number, number, number];
    const total = a + b + c + d + f;
    return {
      ...base('aggregate', 'pitch barrels (sum of 5)', `According to the chronicle, how many barrels of pitch in total were actually delivered from the forest of ${forest}?`),
      expected: String(total),
      numeric: total,
      rejectNumbers: [total + cancelled, total + tar, total + other],
      parts: [
        `The shipwrights bought ${a} barrels of pitch brought from the forest of ${forest}.`,
        `Charcoal-burners from the forest of ${forest} delivered ${b} more barrels of pitch before the rains.`,
        `A load of ${c} barrels of pitch came from the forest of ${forest} in the autumn.`,
        `The navy yard took delivery of ${d} barrels of pitch from the forest of ${forest}.`,
        `The last carts of the year carried ${f} barrels of pitch in from the forest of ${forest}.`,
      ],
      distractors: [
        `The forest of ${forest} also sent ${tar} barrels of tar, which the ropemakers bought.`,
        `The forest of ${near} supplied ${other} barrels of pitch, but the shipwrights judged it poor.`,
        `The council ordered ${cancelled} more barrels of pitch from the forest of ${forest}, but the order was cancelled.`,
      ],
    };
  },
};

export const SUPERSEDED_HARD_BUILDERS: Record<string, Builder> = {
  clockTowerHard(e) {
    const square = e.take('square', SQUARES);
    const square2 = e.take('square', SQUARES);
    const h1 = e.rng.int(40, 90);
    const h2 = h1 + e.rng.pick([-1, 1]) * e.rng.int(3, 12);
    let h3 = e.rng.int(40, 90);
    if (h3 === h1 || h3 === h2) h3 += 17;
    const surveyor = e.person();
    return {
      ...base('superseded', 'clock tower (corrected)', `How tall is the Clock Tower in ${square}, in cubits?`),
      expected: String(h2),
      numeric: h2,
      rejectNumbers: [h1, h3],
      parts: [
        `The old charter records that the Clock Tower in ${square} stands ${h1} cubits high.`,
        `A careful survey by ${surveyor.full} corrected the old charter: the Clock Tower in ${square} actually stands ${h2} cubits high.`,
      ],
      distractors: [`The Clock Tower in ${square2} stands ${h3} cubits high, exactly as its builders recorded.`],
    };
  },
  censusHard(e) {
    const year = e.startYear + e.rng.int(10, 60);
    const year2 = year + e.rng.pick([-1, 1]) * e.rng.int(1, 4);
    const p1 = e.rng.int(2000, 9000);
    const p2 = p1 + e.rng.pick([-1, 1]) * e.rng.int(120, 900);
    const p3 = p2 + e.rng.pick([-1, 1]) * e.rng.int(40, 300);
    return {
      ...base('superseded', 'census (corrected)', `According to the chronicle, how many households were there within the walls at the census of ${year}?`),
      expected: String(p2),
      numeric: p2,
      rejectNumbers: [p1, p3],
      parts: [
        `The census of ${year} counted ${withCommas(p1)} households within the walls.`,
        `Clerks later found an error in the census of ${year}: the true count was ${withCommas(p2)} households within the walls.`,
      ],
      distractors: [`The census of ${year2} counted ${withCommas(p3)} households within the walls.`],
    };
  },
};

export interface NeedleMix {
  single: number;
  twoHop: number;
  threeHop: number;
  sum3: number;
  sum5: number;
  superseded: number;
}

/** Builds an explicit needle mix (used by the hard tier; the standard tier uses buildNeedles). */
export function buildNeedleMix(e: NeedleEnv, mix: NeedleMix): NeedleSpec[] {
  const pick = (builders: Record<string, Builder>, n: number) =>
    e.rng
      .shuffle(Object.keys(builders))
      .slice(0, Math.min(n, Object.keys(builders).length))
      .map((k) => builders[k]!(e));
  return [
    ...pick(SINGLE_BUILDERS, mix.single),
    ...pick(MULTI_BUILDERS, mix.twoHop),
    ...pick(THREE_HOP_BUILDERS, mix.threeHop),
    ...pick(AGGREGATE_BUILDERS, mix.sum3),
    ...pick(SUM5_BUILDERS, mix.sum5),
    ...pick(SUPERSEDED_HARD_BUILDERS, mix.superseded),
  ];
}
