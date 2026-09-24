/**
 * Needle in a Haystack — procedural "chronicle of an invented city".
 *
 * Produces chapters of readable, loosely coherent prose (a recurring cast,
 * districts, guilds and years) from a seeded Rng. Deliberately never touches
 * the subjects the needles are about (lighthouses, aqueducts, bells, banners,
 * mines, the clock tower, ...), so every needle and distractor is the only
 * sentence in the document about its subject.
 */
import type { Rng } from '../../core/types.ts';

export interface Person {
  first: string;
  last: string;
  full: string;
}

export interface Sentence {
  text: string;
  /** Set for planted needle parts / distractors. */
  tag?: string;
}

export interface Chapter {
  title: string;
  paragraphs: Sentence[][];
}

const FIRST_NAMES = [
  'Orsolya', 'Tamsin', 'Idris', 'Maren', 'Kasimir', 'Berin', 'Ysolde', 'Anselm', 'Brisa', 'Corvin', 'Dagny', 'Elowen',
  'Fenwick', 'Gisela', 'Halvard', 'Isolde', 'Jorund', 'Katrin', 'Leopold', 'Mireille', 'Nils', 'Oriel', 'Petra',
  'Quillon', 'Rurik', 'Sabine', 'Tobiah', 'Ulla', 'Valdis', 'Wenna', 'Yannick', 'Aurel', 'Bettany', 'Doria', 'Emeric',
  'Florin', 'Greta', 'Hesper', 'Ivo', 'Jessamy', 'Konrad', 'Lisbet', 'Magnus', 'Nerys', 'Osric', 'Philippa',
  'Roswitha', 'Soren', 'Thea', 'Ulric', 'Vesna', 'Wilmot', 'Ysbel', 'Agnetha', 'Bartholo', 'Cressen', 'Dunstan',
  'Edda', 'Faramond', 'Gunnhild', 'Hildred', 'Imre', 'Jolenta', 'Lothar', 'Minna', 'Odo', 'Perrin', 'Runa', 'Sigrun',
];
const SURNAME_HEADS = [
  'Ash', 'Bram', 'Cor', 'Dask', 'Eld', 'Fen', 'Gar', 'Hal', 'Iver', 'Jarn', 'Kess', 'Lorr', 'Mord', 'Nark', 'Orr',
  'Pell', 'Quen', 'Rask', 'Sorr', 'Tamm', 'Ull', 'Vey', 'Wick', 'Yarr', 'Brun', 'Carr', 'Dunn', 'Gael', 'Hess', 'Mall',
];
const SURNAME_TAILS = ['by', 'well', 'mere', 'holt', 'wick', 'ford', 'ley', 'ton', 'ric', 'vane', 'dal', 'stead', 'grave', 'more', 'ling', 'ett'];
const NEAR_MISS_INITIAL: Record<string, string> = {
  B: 'P', P: 'B', C: 'K', K: 'C', D: 'T', T: 'D', F: 'V', V: 'F', G: 'K', H: 'W', W: 'H', M: 'N', N: 'M', R: 'L', L: 'R',
  S: 'Z', Q: 'K', J: 'Y', Y: 'J', I: 'E', E: 'I', O: 'A', A: 'O', U: 'O',
};
const CITY_HEADS = ['Kar', 'Ost', 'Vel', 'Mar', 'Tir', 'Hal', 'Esk', 'Bry', 'Sol', 'Dun', 'Cael', 'Ves'];
const CITY_TAILS = ['vessa', 'mere', 'moor', 'dun', 'holm', 'gard', 'ane', 'ora', 'wick', 'port'];

export const STREET_CORES = [
  'Copper', 'Tallow', 'Lantern', 'Rope', 'Weaver', 'Chandler', 'Mill', 'Cooper', 'Gilt', 'Quill', 'Brine', 'Ember',
  'Flint', 'Pewter', 'Thistle', 'Wharf', 'Candle', 'Salter', 'Button', 'Anchor', 'Lark', 'Needle', 'Barrow', 'Sparrow',
];
const STREET_SUFFIXES = ['Lane', 'Street', 'Row', 'Way', 'Walk', 'Yard'];
export const DISTRICTS = [
  "the Tanners' Quarter", 'Lowbridge', 'the Old Harbour', 'Highmarket', 'the Glass Steps', 'Saltings', 'Kingsgate',
  'Rookhill', 'the Narrows', 'Fishergate', 'the Upper Terraces', 'Mudwharf',
];
export const GUILDS = [
  "the Glassblowers' Guild", "the Chandlers' Guild", "the Ropemakers' Guild", "the Vintners' Guild", "the Masons' Guild",
  "the Bookbinders' Guild", "the Clockmakers' Guild", "the Dyers' Guild", "the Coopers' Guild", "the Fishwives' Guild",
  "the Apothecaries' Guild", "the Tanners' Guild",
];
export const TEMPLES = [
  "St. Ilse's", "St. Bertram's", 'the Chapel of the Tides', 'the Temple of the Lamp', "St. Ormund's", 'the Grey Minster',
  "St. Wendreda's", 'the Shrine of the Nine Sisters',
];
export const VILLAGES = [
  'Hollowmere', 'Crickstone', 'Wrenfold', 'Tallowdene', 'Brackwater', 'Nettlebed', 'Fernhollow', 'Marrowby', 'Skelling',
  'Oddcombe', 'Thornwick', 'Pellow Cross', 'Grisby', 'Hemlock Vale',
];
const PORTS = ['Ysse', 'Carrowmouth', 'the Amber Coast', 'Qadir', 'Lornholm', 'the Southern Isles', 'Merevale', 'Tolbrook'];
const OCCUPATIONS = [
  'cooper', 'chandler', 'ferryman', 'glassblower', 'scribe', 'midwife', 'bargemaster', 'stonemason', 'apothecary',
  'ropemaker', 'dyer', 'bookbinder', 'fishmonger', 'clockmaker', 'vintner', 'tanner', 'weaver', 'lamplighter',
];
const COMMODITIES = ['amber', 'wool', 'dyed cloth', 'pepper', 'timber', 'tin', 'herring', 'wine', 'olive oil', 'hides', 'glass beads', 'barley'];
const COINS = ['silver marks', 'copper pennies', 'gold crowns'];
const MEASURES = ['barrel', 'bale', 'sack', 'crate'];
const SEASONS = ['spring', 'summer', 'autumn', 'winter'];
const FESTIVALS = ['the Lantern Fair', 'the Feast of Brine', "Founders' Day", 'the Midsummer Regatta', 'the Apple Wake', 'the Tide Festival'];
const BRIDGES = ['the Old Bridge', "the Cooper's Bridge", 'the Salt Bridge', 'the Swing Bridge'];
const GATES = ['the Sea Gate', "the Drovers' Gate", 'the North Gate', 'the Hollow Gate'];
const SHOPS = ['bakery', 'bookshop', 'pie shop', 'barber-surgery', 'coffee house', 'hat shop', 'pawnbroker', 'spice stall'];
const TAVERNS = ['the Drowned Lantern', 'the Three Herrings', 'the Crooked Oar', 'the Blind Owl', 'the Salt and Anchor', 'the Weeping Gull'];
const DISASTERS = ['great fire', 'flood of the spring tides', 'long storm', 'riots'];
const MENAGERIE = ['bear', 'camel', 'peacock', 'porcupine', 'leopard'];
const DEEDS = [
  'refusing to pay the bridge toll for eleven years',
  'rescuing a drowning ox from the canal',
  'writing a satirical song about the council',
  'feeding half the poor of {D} through a hard winter',
  'challenging an alderman to a rowing race and winning it',
  'keeping the only accurate almanac in the city',
  'marrying three times, each time on the same day of the year',
  'teaching the orphans of {D} to read',
];
const TITLE_NOUNS = [
  'Long Winter', 'Salt Riots', 'Two Aldermen', 'Red Tide', 'Great Fog', 'Broken Oars', 'Plenty', 'Locusts', 'Comet',
  'Floods', 'Tall Ships', 'Empty Granaries', 'Masked Players', 'Green Slate', 'Quiet Harbour', 'Strikes',
];

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Tracks every name used in a document so needles and distractors stay unique. */
export class NameForge {
  private readonly rng: Rng;
  private readonly usedFull = new Set<string>();
  private readonly usedLast = new Set<string>();
  private readonly usedCities = new Set<string>();

  constructor(rng: Rng) {
    this.rng = rng;
  }

  person(): Person {
    for (let i = 0; i < 500; i++) {
      const first = this.rng.pick(FIRST_NAMES);
      const last = this.rng.pick(SURNAME_HEADS) + this.rng.pick(SURNAME_TAILS);
      if (this.usedLast.has(last) || this.usedLast.has(this.nearMissWord(last))) continue;
      return this.claim({ first, last, full: `${first} ${last}` });
    }
    throw new Error('NameForge: out of names');
  }

  /** Same first name, surname one letter away (Daskwell → Taskwell). */
  nearMiss(p: Person): Person {
    const last = this.nearMissWord(p.last);
    if (this.usedLast.has(last)) return this.person();
    return this.claim({ first: p.first, last, full: `${p.first} ${last}` });
  }

  nearMissWord(word: string): string {
    const swap = NEAR_MISS_INITIAL[word.charAt(0)];
    if (swap) return swap + word.slice(1);
    return word.slice(0, -1) + (word.endsWith('e') ? 'a' : 'e');
  }

  city(): string {
    for (let i = 0; i < 200; i++) {
      const name = this.rng.pick(CITY_HEADS) + this.rng.pick(CITY_TAILS);
      if (!this.usedCities.has(name)) {
        this.usedCities.add(name);
        return name;
      }
    }
    throw new Error('NameForge: out of city names');
  }

  private claim(p: Person): Person {
    this.usedFull.add(p.full);
    this.usedLast.add(p.last);
    return p;
  }
}

export interface CorpusWorld {
  city: string;
  startYear: number;
  cast: Array<Person & { occ: string; district: string }>;
  streets: string[];
  ships: string[];
  /** Villages the filler may mention (needle villages are removed from this list). */
  villages: string[];
}

export function makeStreets(rng: Rng, count: number): string[] {
  return rng.shuffle(STREET_CORES).slice(0, count).map((core) => `${core} ${rng.pick(STREET_SUFFIXES)}`);
}

const SHIP_ADJ = ['Gilded', 'Patient', 'Silver', 'Crooked', 'Northern', 'Laughing', 'Salt', 'Midnight', 'Faithful', 'Restless'];
const SHIP_NOUN = ['Gull', 'Promise', 'Otter', 'Widow', 'Anchor', 'Crown', 'Lantern', 'Swallow', 'Duchess', 'Pilgrim'];
export function makeShips(rng: Rng, count: number): string[] {
  const all: string[] = [];
  for (const a of SHIP_ADJ) for (const n of SHIP_NOUN) all.push(`the ${a} ${n}`);
  return rng.shuffle(all).slice(0, count);
}

type Filler = (g: Gen) => string;

/** Sentence generator bound to one chapter's focus entities. */
class Gen {
  readonly rng: Rng;
  readonly w: CorpusWorld;
  year: number;
  focusPerson: CorpusWorld['cast'][number];
  focusDistrict: string;
  focusGuild: string;

  constructor(rng: Rng, w: CorpusWorld) {
    this.rng = rng;
    this.w = w;
    this.year = w.startYear;
    this.focusPerson = w.cast[0]!;
    this.focusDistrict = DISTRICTS[0]!;
    this.focusGuild = GUILDS[0]!;
  }

  refocus(): void {
    this.focusPerson = this.rng.pick(this.w.cast);
    this.focusDistrict = this.rng.pick(DISTRICTS);
    this.focusGuild = this.rng.pick(GUILDS);
  }

  P(): string {
    return (this.rng.chance(0.45) ? this.focusPerson : this.rng.pick(this.w.cast)).full;
  }
  other(): CorpusWorld['cast'][number] {
    return this.rng.pick(this.w.cast);
  }
  D(): string {
    return this.rng.chance(0.5) ? this.focusDistrict : this.rng.pick(DISTRICTS);
  }
  G(): string {
    return this.rng.chance(0.5) ? this.focusGuild : this.rng.pick(GUILDS);
  }
  G2(g: string): string {
    return this.rng.pick(GUILDS.filter((x) => x !== g));
  }
  ST(): string {
    return this.rng.pick(this.w.streets);
  }
  N(min = 2, max = 60): number {
    return this.rng.int(min, max);
  }
  pick<T>(items: readonly T[]): T {
    return this.rng.pick(items);
  }
}

const FILLERS: Filler[] = [
  (g) => `In the ${g.pick(SEASONS)} of ${g.year}, ${g.G()} petitioned the council for the right to hold a market in ${g.D()}.`,
  (g) => {
    const p = g.P();
    const who = g.w.cast.find((c) => c.full === p)!;
    return `${p}, a ${who.occ} from ${who.district}, was remembered long afterwards for ${g.pick(DEEDS).replace('{D}', who.district)}.`;
  },
  (g) => `The price of ${g.pick(COMMODITIES)} rose to ${g.N()} ${g.pick(COINS)} a ${g.pick(MEASURES)}, and the people of ${g.D()} grumbled about it in every tavern.`,
  (g) => `Ships from ${g.pick(PORTS)} crowded the harbour; ${g.pick(g.w.ships)} alone carried ${g.N(20, 400)} bales of ${g.pick(COMMODITIES)}.`,
  (g) => `That winter the river froze as far as ${g.pick(BRIDGES)}, and children skated where the barges usually moored.`,
  (g) => `The council met ${g.N(4, 40)} times that year, mostly to argue about the drains of ${g.D()}.`,
  (g) => `${g.P()} opened a ${g.pick(SHOPS)} on ${g.ST()}, and within a month the queue reached the corner.`,
  (g) => `A fire broke out in a ${g.pick(OCCUPATIONS)}'s workshop on ${g.ST()}, but it was put out before it could spread to ${g.D()}.`,
  (g) => `${capitalize(g.pick(FESTIVALS))} was kept with unusual splendour, and ${g.N(40, 900)} paper lanterns were floated down the river.`,
  (g) => `Pilgrims came to ${g.pick(TEMPLES)} in great numbers, many of them from the hill villages around ${g.pick(g.w.villages)}.`,
  (g) => `${g.P()} was elected alderman of ${g.D()} by a margin of ${g.N(3, 90)} votes.`,
  (g) => {
    const a = g.G();
    return `${capitalize(a)} quarrelled with ${g.G2(a)} over the use of the old weighhouse, and the matter went before the magistrates.`;
  },
  (g) => `Rain fell for ${g.N(3, 30)} days without stopping, and the lower streets of ${g.D()} stood knee-deep in water.`,
  (g) => `A travelling company of players performed on ${g.ST()}, and one of their comedies was promptly banned by the aldermen.`,
  (g) => `The magistrates fined ${g.P()} ${g.N(2, 80)} ${g.pick(COINS)} for selling short measures of ${g.pick(COMMODITIES)}.`,
  (g) => `${g.P()}, who kept a tavern called ${g.pick(TAVERNS)} near ${g.ST()}, was said to know every secret in ${g.w.city}.`,
  (g) => `The road to ${g.pick(g.w.villages)} was repaved with river stone, a task that kept ${g.N(12, 200)} labourers busy most of the summer.`,
  (g) => `In ${g.D()} a new well was dug, and its water was declared the sweetest in the city.`,
  (g) => `An envoy from ${g.pick(PORTS)} arrived with gifts of ${g.pick(COMMODITIES)} and a letter that nobody at the council could read.`,
  (g) => `The harvest in the valley was poor, and bread was rationed in ${g.D()} until the ${g.pick(SEASONS)}.`,
  (g) => {
    const a = g.other();
    const b = g.other();
    return a === b
      ? `${a.full} gave a feast for the whole of ${a.district}, and it was talked about for years.`
      : `${a.full} married ${b.full} at ${g.pick(TEMPLES)}, and the wedding feast lasted until dawn.`;
  },
  (g) => `The chronicler notes, with some disapproval, that ${g.G()} spent ${g.N(50, 700)} ${g.pick(COINS)} on a single banquet that year.`,
  (g) => `Wolves were seen on the hills above ${g.pick(g.w.villages)}, and the shepherds kept watch in pairs.`,
  (g) => `A scholar named ${g.P()} began a catalogue of the city's books, which was still unfinished forty years later.`,
  (g) => `The market in ${g.D()} moved from ${g.ST()} to the square in front of ${g.pick(TEMPLES)}.`,
  (g) => `Many of the older houses on ${g.ST()} were rebuilt in brick after the ${g.pick(DISASTERS)}.`,
  (g) => `The ferry across the river was run that year by ${g.P()}, who charged ${g.N(1, 9)} copper pennies a crossing.`,
  (g) => `Nothing of great note happened in ${g.D()} that ${g.pick(SEASONS)}, except that a ${g.pick(MENAGERIE)} escaped from the menagerie and was found asleep in ${g.pick(TEMPLES)}.`,
  (g) => `${g.P()} was accused of smuggling ${g.pick(COMMODITIES)} past the customs house on ${g.ST()}, but the charge was quietly dropped.`,
  (g) => `The city walls were patched along ${g.N(20, 300)} yards of their length near ${g.pick(GATES)}.`,
  (g) => `${capitalize(g.G())} took on ${g.N(4, 40)} new apprentices, more than in any year the older members could remember.`,
  (g) => `A comet hung over the harbour for ${g.N(3, 20)} nights, and the astrologers of ${g.D()} were much consulted.`,
  (g) => `${g.P()} left a bequest of ${g.N(20, 900)} ${g.pick(COINS)} to the almshouse on ${g.ST()}.`,
  (g) => `The fishing fleet came home early after a storm; ${g.N(2, 14)} boats were lost off the headland.`,
  (g) => `Fever was feared in ${g.D()}, but the physicians' precautions held and the season passed quietly.`,
  (g) => `${capitalize(g.G())} built a new hall on ${g.ST()} with a roof of green slate.`,
  (g) => {
    const p = g.other();
    return `The ${p.last} family of ${p.district} sent their youngest son to be apprenticed to a ${g.pick(OCCUPATIONS)} in ${g.D()}.`;
  },
  (g) => `The toll-keepers of ${g.pick(BRIDGES)} went on strike, and for a week nobody could cross without paying twice.`,
  (g) => `Merchants from ${g.pick(PORTS)} brought news of war in the south, and prices rose accordingly.`,
  (g) => `${g.P()} was appointed keeper of the city granary and held the post for ${g.N(2, 25)} years.`,
  (g) => `It was, by all accounts, a quiet ${g.pick(SEASONS)} in ${g.D()}.`,
  (g) => `The old men of ${g.D()} still spoke of that ${g.pick(SEASONS)} decades later.`,
  (g) => `A dispute over a stolen ${g.pick(['goat', 'rowing boat', 'ledger', 'cask of wine', 'weathervane'])} between two families of ${g.D()} occupied the magistrates for ${g.N(2, 9)} weeks.`,
  (g) => `${g.P()} and ${g.P()} were seen arguing on ${g.ST()} about the price of ${g.pick(COMMODITIES)}, to the delight of the passers-by.`,
  (g) => `The ${g.pick(OCCUPATIONS)}s of ${g.D()} complained that the new regulations of ${g.G()} were written only to ruin them.`,
];

const CONNECTORS = ['Meanwhile, ', 'Later that year, ', 'In the same season, ', 'It is also recorded that ', 'Around this time, ', 'According to the guild rolls, '];

function lowerLead(s: string): string {
  return /^(The|A|An|In|That|It|Rain|Nothing|Wolves|Fever|Pilgrims|Ships|Merchants|Many)\b/.test(s) ? s.charAt(0).toLowerCase() + s.slice(1) : s;
}

function chapterTitle(g: Gen): string {
  const r = g.rng.int(0, 4);
  if (r === 0) return `How ${g.focusDistrict} Was Rebuilt`;
  if (r === 1) return `${g.focusPerson.full} and ${g.focusGuild}`;
  if (r === 2) return `Of ${g.pick(['Tolls and Taxes', 'Ships and Storms', 'Feasts and Fines', 'Bridges and Barges', 'Guilds and Grudges'])}`;
  return `The Year of the ${g.pick(TITLE_NOUNS)}`;
}

/** Word count of one sentence (split on whitespace). */
export function sentenceWords(s: string): number {
  return s.split(/\s+/).filter(Boolean).length;
}

/** Generates chapters until the target word count is reached. */
export function generateChapters(rng: Rng, w: CorpusWorld, targetWords: number): Chapter[] {
  const g = new Gen(rng, w);
  const chapters: Chapter[] = [];
  let words = 0;
  while (words < targetWords) {
    g.refocus();
    g.year += rng.int(1, 3);
    const title = `Chapter ${chapters.length + 1}: ${chapterTitle(g)} (${g.year})`;
    const paragraphs: Sentence[][] = [];
    const nPara = rng.int(4, 6);
    for (let p = 0; p < nPara && words < targetWords; p++) {
      const sentences: Sentence[] = [];
      const nSent = rng.int(4, 7);
      for (let s = 0; s < nSent; s++) {
        let text = rng.pick(FILLERS)(g);
        if (s > 0 && rng.chance(0.2)) text = rng.pick(CONNECTORS) + lowerLead(text);
        if (p === 0 && s === 0) text = `In the year ${g.year}, ${lowerLead(text)}`;
        sentences.push({ text });
        words += sentenceWords(text);
      }
      paragraphs.push(sentences);
    }
    chapters.push({ title, paragraphs });
  }
  return chapters;
}

export function buildCorpusWorld(rng: Rng, forge: NameForge, reservedVillages: readonly string[]): CorpusWorld {
  const city = forge.city();
  const cast = Array.from({ length: 36 }, () => ({ ...forge.person(), occ: rng.pick(OCCUPATIONS), district: rng.pick(DISTRICTS) }));
  return {
    city,
    startYear: rng.int(300, 700),
    cast,
    streets: makeStreets(rng, 14),
    ships: makeShips(rng, 10),
    villages: VILLAGES.filter((v) => !reservedVillages.includes(v)),
  };
}
