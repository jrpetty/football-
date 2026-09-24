/**
 * Chain of Whispers — seeded source stories and their 12 checkable facts.
 *
 * Each fact is a set of keyword groups that must ALL appear in the same
 * sentence within a small word window ("Quenby" near "lighthouse keeper",
 * "37" near "passengers"), so a fact only survives with its meaning attached.
 * Aliases absorb harmless paraphrase; numbers match digits or number words.
 */
import type { Rng } from '../../core/types.ts';
import { normalizeText } from './needle-haystack-normalize.ts';

export interface FactSpec {
  id: string;
  /** Short human label, e.g. "rope length". */
  label: string;
  /** The fact as stated in the source (shown in results; never sent to the model). */
  canonical: string;
  /** Every group must match (any alias per group) inside one sentence... */
  groups: string[][];
  /** ...with all matches spanning at most this many words. */
  window: number;
}

export interface SourceStory {
  id: StoryId;
  title: string;
  text: string;
  facts: FactSpec[];
}

export type StoryId = 'lighthouse' | 'museum' | 'expedition';
export const STORY_IDS: readonly StoryId[] = ['lighthouse', 'museum', 'expedition'];

const WOMEN = ['Marisol', 'Ottilie', 'Brenna', 'Ines', 'Agathe', 'Solveig', 'Cordelia', 'Linnea', 'Pernille', 'Rosalba'];
const MEN = ['Tobiah', 'Casimir', 'Anders', 'Emrys', 'Jonas', 'Lucan', 'Matthias', 'Oskar', 'Rafferty', 'Benedek'];
const SURNAMES = ['Quenby', 'Hallorann', 'Ormsby', 'Thistlewood', 'Krauss', 'Pemberton', 'Ashgrove', 'Delacroix', 'Fairweather', 'Lindqvist', 'Moraine', 'Varga', 'Castellane', 'Brodsky'];
const MONTHS = ['January', 'February', 'March', 'April', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const TOWNS = ['Wexcombe', 'Porthallow', 'Saltmere', 'Kittering', 'Dunmarrow', 'Gullhaven'];
const STORMS: Array<[string, string[]]> = [
  ['hailstorm', ['hailstorm', 'hail', 'hailstones']],
  ['blizzard', ['blizzard', 'snowstorm', 'snow storm']],
  ['waterspout', ['waterspout', 'waterspouts', 'water spout']],
];
const SHIP_ADJ = ['Patient', 'Gilded', 'Crooked', 'Laughing', 'Faithful', 'Restless'];
const SHIP_NOUN = ['Otter', 'Heron', 'Swallow', 'Pilgrim', 'Duchess', 'Lantern'];
const SHIP_TYPES = ['schooner', 'brigantine', 'packet steamer'];
const BREEDS: Array<[string, string]> = [
  ['wire-haired terrier', 'terrier'],
  ['one-eyed spaniel', 'spaniel'],
  ['shaggy sheepdog', 'sheepdog'],
  ['greyhound', 'greyhound'],
];
const DOGS = ['Pepper', 'Admiral', 'Biscuit', 'Nutmeg', 'Captain Flint', 'Mustard'];
const COLOURS = ['green', 'yellow', 'violet', 'orange', 'scarlet'];
const ANIMALS = ['heron', 'fox', 'owl', 'seahorse', 'dragon', 'hare'];
const JOBS = ['fisherman', 'boatbuilder', 'net-mender', 'ferryman'];
const CITIES = ['Ostrava Nova', 'Belmarsh', 'Castelbrück', 'Vennholm', 'Marisca', 'Lowenburg'];
const PAINT_ADJ = ['Orange', 'Weeping', 'Sleeping', 'Crimson', 'Silent', 'Upside-Down'];
const PAINT_NOUN = ['Gondolier', 'Astronomer', 'Lighthouse', 'Harlequin', 'Beekeeper', 'Violinist'];
const FRUITS = ['quince', 'damson', 'gooseberry', 'rhubarb', 'apricot', 'medlar'];
const VEHICLES: Array<[string, string[]]> = [
  ['tandem bicycle', ['tandem']],
  ['motor tricycle', ['tricycle', 'trike']],
  ['sidecar motorcycle', ['sidecar']],
  ['pony cart', ['pony cart', 'pony', 'cart']],
];
const CHURCHES: Array<[string, string]> = [
  ["St. Oda's chapel", 'oda'],
  ["St. Brannoc's church", 'brannoc'],
  ["St. Wendel's church", 'wendel'],
  ["St. Ursula's chapel", 'ursula'],
];
const MOUNTAINS = ['Karvel', 'Ostrander', 'Mirelle', 'Vashti', 'Tamborine', 'Gallowcap'];
const VILLAGES = ['Hollin Ghyll', 'Brisk', 'Upper Tarrow', 'Sennick', 'Dorn', 'Lesser Vey'];
const FOODS: Array<[string, string[]]> = [
  ['dried apricots', ['apricot', 'apricots']],
  ['dried figs', ['fig', 'figs']],
  ['prunes', ['prune', 'prunes']],
  ['dried cranberries', ['cranberry', 'cranberries']],
];
const GOATS = ['Bartholomew', 'Clementine', 'Old Nick', 'Duchess', 'Humphrey', 'Mabel'];

function fact(id: string, label: string, canonical: string, groups: Array<string | string[]>, window: number): FactSpec {
  return { id, label, canonical, groups: groups.map((g) => (Array.isArray(g) ? g : [g])), window };
}

/** Draws `count` distinct integers from [min, max] that avoid `taken` (keeps every numeric fact unique). */
function distinctInts(rng: Rng, count: number, min: number, max: number, taken: Set<number>): number[] {
  const out: number[] = [];
  while (out.length < count) {
    const n = rng.int(min, max);
    if (!taken.has(n)) {
      taken.add(n);
      out.push(n);
    }
  }
  return out;
}

function commas(n: number): string {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function lighthouse(rng: Rng): SourceStory {
  const kf = rng.pick(WOMEN);
  const kl = rng.pick(SURNAMES);
  const bro = rng.pick(MEN);
  const broJob = rng.pick(JOBS);
  const town = rng.pick(TOWNS);
  const month = rng.pick(MONTHS);
  const year = rng.int(1880, 1912);
  const taken = new Set<number>([year]);
  const [day] = distinctInts(rng, 1, 2, 27, taken) as [number];
  const [p] = distinctInts(rng, 1, 23, 58, taken) as [number];
  const [rope] = distinctInts(rng, 1, 90, 180, taken) as [number];
  const [reward] = distinctInts(rng, 1, 150, 600, taken) as [number];
  const [storm, stormAliases] = rng.pick(STORMS);
  const ship = `the ${rng.pick(SHIP_ADJ)} ${rng.pick(SHIP_NOUN)}`;
  const shipType = rng.pick(SHIP_TYPES);
  const [breed, breedCore] = rng.pick(BREEDS);
  const dog = rng.pick(DOGS);
  const colour = rng.pick(COLOURS);
  const animal = rng.pick(ANIMALS);
  const mayor = `${rng.pick(MEN)} ${rng.pick(SURNAMES.filter((s) => s !== kl))}`;
  const Ship = ship.charAt(0).toUpperCase() + ship.slice(1);

  const text = [
    `On the night of ${day} ${month} ${year}, a ${storm} swept in from the sea without warning. In the harbour town of ${town}, most people bolted their shutters and went to bed early. ${kf} ${kl}, the lighthouse keeper, did not. She climbed the spiral stairs of the tower with her dog, a ${breed} called ${dog}, trotting ahead of her as it always did, and she trimmed the wick until the lamp burned as brightly as it could.`,
    `A little after midnight she saw a ship in trouble beyond the reef. It was ${ship}, a ${shipType} bound for the southern ports, carrying ${p} passengers. Its mainmast had snapped, and the wind was pushing it steadily towards the rocks. ${kf} knew that the lifeboat crew could never launch in such a sea.`,
    `She ran down to the cottage of her brother, ${bro}, a ${broJob} who owned the only boat in ${town} heavy enough to survive the waves. Together they hauled ${rope} metres of rope out of the storehouse and carried it to the end of the stone pier. ${kf} also brought her strangest possession: a ${colour} umbrella with a handle carved in the shape of a ${animal}, which she swore had brought her luck since childhood. Her neighbours had laughed at it for years. That night nobody laughed.`,
    `${bro} rowed while ${kf} steered. When they reached ${ship}, they tied the rope to the stump of the mast and ran it back to the pier, where half the town had gathered with lanterns. One by one the passengers were pulled along the rope to safety, clinging to it with frozen hands. The last to leave was the captain, who refused to go until everyone else had crossed.`,
    `By dawn the storm had blown itself out. Every one of the ${p} passengers was alive, wrapped in blankets in the church hall and drinking tea that the baker had brewed in his largest pot. ${Ship} broke apart on the reef later that morning.`,
    `The shipping company offered a reward of ${reward} guineas to whoever had saved its passengers. ${kf} gave half of it to the lifeboat fund and spent the rest on a new lens for the lamp. The mayor, ${mayor}, made a long speech that nobody remembered afterwards. What people did remember was the vote at the next town meeting: from that day on, the lighthouse was renamed the ${kf} Light, and the ${animal}-handled umbrella was hung above its door.`,
  ].join('\n\n');

  const shipCore = ship.replace(/^the /, '');
  return {
    id: 'lighthouse',
    title: `The Keeper of ${town}`,
    text,
    facts: [
      fact('keeper', "keeper's name", `${kf} ${kl} was the lighthouse keeper`, [[kl, kf], ['lighthouse', 'keeper']], 12),
      fact('town', 'harbour town', `The harbour town was ${town}`, [town], 0),
      fact('date', 'date of the storm', `It happened on ${day} ${month}`, [String(day), month], 3),
      fact('storm', 'kind of storm', `The storm was a ${storm}`, [stormAliases], 0),
      fact('ship', "ship's name", `The ship was ${ship}`, [shipCore], 0),
      fact('passengers', 'passenger count', `The ship carried ${p} passengers`, [String(p), ['passengers', 'passenger', 'people', 'souls', 'travellers']], 4),
      fact('brother', "brother's name", `Her brother was ${bro}`, [bro, 'brother'], 8),
      fact('umbrella', 'lucky umbrella', `She carried a ${colour} umbrella with a ${animal} handle`, [['umbrella', 'parasol'], animal], 10),
      fact('rope', 'rope length', `They used ${rope} metres of rope`, [String(rope), ['metres', 'meters', 'metre', 'meter', 'm']], 2),
      fact('reward', 'reward', `The reward was ${reward} guineas`, [String(reward), ['guineas', 'guinea']], 2),
      fact('dog', "dog's name", `Her dog was ${dog}, a ${breedCore}`, [dog, ['dog', breedCore, 'hound', 'puppy']], 10),
      fact('renamed', 'lighthouse renamed', `The lighthouse was renamed the ${kf} Light`, [['renamed', 'rename', 'renaming', 'named', 'name'], `${kf} light`], 10),
    ],
  };
}

function museum(rng: Rng): SourceStory {
  const cf = rng.pick(WOMEN);
  const cl = rng.pick(SURNAMES);
  const pf = rng.pick(MEN);
  const pl = rng.pick(SURNAMES.filter((s) => s !== cl));
  const gf = rng.pick(MEN.filter((m) => m !== pf));
  const gl = rng.pick(SURNAMES.filter((s) => s !== cl && s !== pl));
  const niece = rng.pick(WOMEN.filter((w) => w !== cf));
  const city = rng.pick(CITIES);
  const month = rng.pick(MONTHS);
  const year = rng.int(1895, 1935);
  const taken = new Set<number>([year, 3]);
  const [day] = distinctInts(rng, 1, 2, 27, taken) as [number];
  const [minutes] = distinctInts(rng, 1, 11, 47, taken) as [number];
  const value = rng.int(12, 96) * 100;
  const painting = `The ${rng.pick(PAINT_ADJ)} ${rng.pick(PAINT_NOUN)}`;
  const fruit = rng.pick(FRUITS);
  const colour = rng.pick(COLOURS);
  const [vehicle, vehicleAliases] = rng.pick(VEHICLES);
  const [church, churchCore] = rng.pick(CHURCHES);

  const text = [
    `On the morning of ${day} ${month} ${year}, ${cf} ${cl}, the curator of the civic museum in ${city}, unlocked the east gallery and found an empty frame where the museum's most famous painting should have been. The missing picture was ${painting}, a small and rather strange canvas by the painter ${pf} ${pl}, and it was insured for ${commas(value)} crowns.`,
    `The night watchman, ${gf} ${gl}, swore that he had heard nothing. He admitted that he had dozed in his chair, but only, he insisted, for a moment. The museum's clockwork alarm told a different story: its log showed that the gallery door had stood open for ${minutes} minutes shortly before dawn, and no bell had rung.`,
    `The thief had been careful everywhere except in one respect. On the floor beneath the empty frame sat a jar of ${fruit} jam, unopened, with a paper label written in a neat, looping hand. Nobody in the museum could explain it. The police sergeant tasted a spoonful, declared it excellent, and filed it as evidence.`,
    `For a week there were no leads at all. The newspapers printed rumours that the painting had been smuggled out on a fishing boat, or cut into pieces, or sold to a collector overseas. Then a baker on the market square mentioned that he had seen a ${colour} ${vehicle} racing out of town in the grey light of that morning, ridden by two people in long coats, with a flat parcel strapped behind them.`,
    `The curator's niece, ${niece}, who spent her school holidays helping in the museum, took the clue seriously when the adults did not. She cycled from village to village asking about the ${colour} ${vehicle}, and at last she found it leaning against the wall of ${church}. Inside, wrapped in sacking and hidden in the bell tower, was ${painting}, completely unharmed.`,
    `The thieves were never caught. The jar of ${fruit} jam stayed on display beside the painting for many years, with a small card explaining how it came to be there, and it became almost as famous as the picture itself. ${cf} ${cl} gave ${niece} a key to the museum and a promise that she could curate an exhibition of her own when she was old enough.`,
  ].join('\n\n');

  const paintingCore = painting.replace(/^The /, '').toLowerCase();
  return {
    id: 'museum',
    title: `The Vanishing of ${painting}`,
    text,
    facts: [
      fact('curator', "curator's name", `${cf} ${cl} was the museum curator`, [[cl, cf], ['curator']], 12),
      fact('city', 'city', `The museum was in ${city}`, [city], 0),
      fact('date', 'date of the theft', `The theft was discovered on ${day} ${month}`, [String(day), month], 3),
      fact('painting', "painting's title", `The stolen painting was ${painting}`, [paintingCore], 0),
      fact('painter', "painter's name", `It was painted by ${pf} ${pl}`, [pl, ['painter', 'painted', 'artist', 'paint', 'painting', 'canvas']], 8),
      fact('value', 'insured value', `It was insured for ${commas(value)} crowns`, [String(value), ['crowns', 'crown']], 2),
      fact('watchman', "watchman's name", `The night watchman was ${gf} ${gl}`, [[gl, gf], ['watchman', 'guard', 'watch']], 10),
      fact('jam', 'jar of jam', `The thief left a jar of ${fruit} jam`, [['jam', 'preserve', 'preserves', 'marmalade', 'jelly'], fruit], 4),
      fact('minutes', 'door open time', `The door was open for ${minutes} minutes`, [String(minutes), ['minutes', 'minute']], 2),
      fact('vehicle', 'getaway vehicle', `The thieves fled on a ${colour} ${vehicle}`, [colour, vehicleAliases], 3),
      fact('church', 'where it was found', `The painting was found at ${church}`, [churchCore], 0),
      fact('niece', "niece's name", `The curator's niece ${niece} found it`, [niece, 'niece'], 8),
    ],
  };
}

function expedition(rng: Rng): SourceStory {
  const lf = rng.pick(WOMEN);
  const ll = rng.pick(SURNAMES);
  const guf = rng.pick(MEN);
  const gul = rng.pick(SURNAMES.filter((s) => s !== ll));
  const mountain = rng.pick(MOUNTAINS);
  const village = rng.pick(VILLAGES);
  const month = rng.pick(MONTHS);
  const year = rng.int(1905, 1938);
  const taken = new Set<number>([year]);
  const [day] = distinctInts(rng, 1, 2, 27, taken) as [number];
  const [n] = distinctInts(rng, 1, 5, 14, taken) as [number];
  const [days] = distinctInts(rng, 1, 4, 12, taken) as [number];
  const height = rng.int(3200, 6400);
  const goat = rng.pick(GOATS);
  const [food, foodAliases] = rng.pick(FOODS);

  const text = [
    `On ${day} ${month} ${year}, an expedition of ${n} climbers set out from the village of ${village} to attempt the unclimbed north face of Mount ${mountain}. Their leader was ${lf} ${ll}, a geologist who had spent most of her life studying the mountain from below and who was determined, at last, to see it from above.`,
    `The team's guide was ${guf} ${gul}, who had grown up in ${village} and knew every path on the lower slopes. For luck, the climbers brought a goat named ${goat}, which carried the heaviest packs without complaint and seemed to enjoy the climb more than anyone. ${lf} carried a brass telescope engraved with the names of her grandparents, and each evening she used it to study the ridges ahead.`,
    `For the first week the weather was kind. They made steady progress and set up their highest camp at ${commas(height)} metres, on a narrow shelf of rock below the summit ridge. Then the wind changed. A storm settled over the mountain and refused to move, and the climbers were trapped in their tents for ${days} days, unable to climb higher or to retreat.`,
    `Their food ran low. By the end they were living on nothing but ${food} and melted snow, rationed out by ${guf} one handful at a time. ${goat} the goat, sheltering behind the largest tent, seemed the least troubled of them all.`,
    `When the storm finally lifted, ${ll} made a decision that surprised everyone: rather than push for the summit, they would go down while they still could. On the way down they found that a rockfall had opened a cave that had not been there before. Inside, the walls glittered with blue crystals that ${lf} recognised at once as something no geologist had ever described.`,
    `The expedition never reached the summit, and for years afterwards its critics called it a failure. But the samples ${lf} carried home in her rucksack made her famous, the crystal cave was named after the village of ${village}, and the climbers always insisted that the real hero of the journey had been a goat called ${goat}.`,
  ].join('\n\n');

  return {
    id: 'expedition',
    title: `The ${mountain} Expedition`,
    text,
    facts: [
      fact('leader', "leader's name", `${lf} ${ll} led the expedition`, [[ll, lf], ['leader', 'led', 'leading', 'geologist']], 12),
      fact('mountain', 'mountain', `They climbed Mount ${mountain}`, [mountain], 0),
      fact('date', 'departure date', `They set out on ${day} ${month}`, [String(day), month], 3),
      fact('team', 'team size', `There were ${n} climbers`, [String(n), ['climbers', 'climber', 'people', 'members', 'mountaineers', 'men and women']], 4),
      fact('altitude', 'highest camp', `Their highest camp was at ${commas(height)} metres`, [String(height), ['metres', 'meters', 'm']], 2),
      fact('guide', "guide's name", `Their guide was ${guf} ${gul}`, [[gul, guf], ['guide']], 10),
      fact('goat', "goat's name", `Their goat was called ${goat}`, [goat, 'goat'], 6),
      fact('telescope', 'brass telescope', `The leader carried a brass telescope`, ['telescope', 'brass'], 5),
      fact('trapped', 'days trapped', `They were trapped for ${days} days`, [String(days), ['days', 'day']], 2),
      fact('food', 'last food', `They lived on ${food}`, [foodAliases], 0),
      fact('village', 'home village', `They set out from the village of ${village}`, [village], 0),
      fact('cave', 'crystal cave', `They discovered a cave of blue crystals`, [['crystal', 'crystals'], ['cave', 'cavern', 'caves']], 8),
    ],
  };
}

const BUILDERS: Record<StoryId, (rng: Rng) => SourceStory> = { lighthouse, museum, expedition };

export function generateStory(rng: Rng, which: StoryId | 'auto' = 'auto'): SourceStory {
  const id = which === 'auto' ? rng.pick(STORY_IDS) : which;
  return BUILDERS[id](rng);
}

// ─────────────────────────────────────────────────────────────────────────────
// Fact matching
// ─────────────────────────────────────────────────────────────────────────────

export function splitSentences(text: string): string[] {
  return text
    .split(/(?<=[.!?…])["'”’)]*\s+|\n+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

function positions(tokens: string[], alias: string): Array<[number, number]> {
  const a = normalizeText(alias).split(' ').filter(Boolean);
  const out: Array<[number, number]> = [];
  if (a.length === 0) return out;
  for (let i = 0; i + a.length <= tokens.length; i++) {
    let ok = true;
    for (let j = 0; j < a.length; j++) {
      if (tokens[i + j] !== a[j]) {
        ok = false;
        break;
      }
    }
    if (ok) out.push([i, i + a.length - 1]);
  }
  return out;
}

/** True when every group matches inside one sentence, all within the fact's word window. */
export function factPresent(text: string, f: FactSpec): boolean {
  for (const sentence of splitSentences(text)) {
    const tokens = normalizeText(sentence).split(' ').filter(Boolean);
    const perGroup = f.groups.map((g) => g.flatMap((alias) => positions(tokens, alias)));
    if (perGroup.some((p) => p.length === 0)) continue;
    // Brute force over one match per group (groups and matches per sentence are tiny).
    const search = (gi: number, lo: number, hi: number): boolean => {
      if (gi === perGroup.length) return hi - lo <= Math.max(f.window, 0) + (hi - lo === 0 ? 0 : 0);
      for (const [s, e] of perGroup[gi]!) {
        const nlo = Math.min(lo, s);
        const nhi = Math.max(hi, e);
        if (nhi - nlo <= f.window || perGroup.length === 1) {
          if (search(gi + 1, nlo, nhi)) return true;
        }
      }
      return false;
    };
    if (search(0, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY)) return true;
  }
  return false;
}

/** The ids of facts present in a text. */
export function survivingFacts(text: string, facts: readonly FactSpec[]): string[] {
  return facts.filter((f) => factPresent(text, f)).map((f) => f.id);
}
