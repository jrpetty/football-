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
const VILLAGES = ['Hollin Ghyll', 'Briskow', 'Upper Tarrow', 'Sennick', 'Dorn', 'Lesser Vey'];
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

function lighthouse(rng: Rng, extended = false): SourceStory {
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

  const paras = [
    `On the night of ${day} ${month} ${year}, a ${storm} swept in from the sea without warning. In the harbour town of ${town}, most people bolted their shutters and went to bed early. ${kf} ${kl}, the lighthouse keeper, did not. She climbed the spiral stairs of the tower with her dog, a ${breed} called ${dog}, trotting ahead of her as it always did, and she trimmed the wick until the lamp burned as brightly as it could.`,
    `A little after midnight she saw a ship in trouble beyond the reef. It was ${ship}, a ${shipType} bound for the southern ports, carrying ${p} passengers. Its mainmast had snapped, and the wind was pushing it steadily towards the rocks. ${kf} knew that the lifeboat crew could never launch in such a sea.`,
    `She ran down to the cottage of her brother, ${bro}, a ${broJob} who owned the only boat in ${town} heavy enough to survive the waves. Together they hauled ${rope} metres of rope out of the storehouse and carried it to the end of the stone pier. ${kf} also brought her strangest possession: a ${colour} umbrella with a handle carved in the shape of a ${animal}, which she swore had brought her luck since childhood. Her neighbours had laughed at it for years. That night nobody laughed.`,
    `${bro} rowed while ${kf} steered. When they reached ${ship}, they tied the rope to the stump of the mast and ran it back to the pier, where half the town had gathered with lanterns. One by one the passengers were pulled along the rope to safety, clinging to it with frozen hands. The last to leave was the captain, who refused to go until everyone else had crossed.`,
    `The crossing took most of the night. Twice the rope went slack and ${kf} had to wade into the surf to haul it tight again, and once a wave tore the lantern out of her hand. The people on the pier took turns at the rope, the blacksmith beside the schoolteacher and the fishwives beside the vicar, and nobody went home until the work was done.`,
    `By dawn the storm had blown itself out. Every one of the ${p} passengers was alive, wrapped in blankets in the church hall and drinking tea that the baker had brewed in his largest pot. ${Ship} broke apart on the reef later that morning.`,
    `The shipping company offered a reward of ${reward} guineas to whoever had saved its passengers. ${kf} gave half of it to the lifeboat fund and spent the rest on a new lens for the lamp. The mayor, ${mayor}, made a long speech that nobody remembered afterwards. What people did remember was the vote at the next town meeting: from that day on, the lighthouse was renamed the ${kf} Light, and the ${animal}-handled umbrella was hung above its door.`,
  ];

  const shipCore = ship.replace(/^the /, '');
  const extra = extended ? lighthouseExtra(rng.fork('extended'), { ship, town, kf, kl, bro, mayor, taken }) : null;
  if (extra) paras.splice(extra.at ?? paras.length, 0, extra.paragraph);
  return {
    id: 'lighthouse',
    title: `The Keeper of ${town}`,
    text: paras.join('\n\n'),
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
      ...(extra?.facts ?? []),
    ],
  };
}

function museum(rng: Rng, extended = false): SourceStory {
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

  const paras = [
    `On the morning of ${day} ${month} ${year}, ${cf} ${cl}, the curator of the civic museum in ${city}, unlocked the east gallery and found an empty frame where the museum's most famous painting should have been. The missing picture was ${painting}, a small and rather strange canvas by the painter ${pf} ${pl}, and it was insured for ${commas(value)} crowns.`,
    `The night watchman, ${gf} ${gl}, swore that he had heard nothing. He admitted that he had dozed in his chair, but only, he insisted, for a moment. The museum's clockwork alarm told a different story: its log showed that the gallery door had stood open for ${minutes} minutes shortly before dawn, and no bell had rung.`,
    `The mayor of ${city} demanded an arrest within the week. Detectives from the capital arrived on the evening train, measured every window, photographed every footprint and questioned every cleaner, porter and ticket-seller in the building. They found nothing useful. The only thing anyone agreed on was that the thief must have known the museum very well indeed, because nothing else in the gallery had been touched and not a single display case had been scratched.`,
    `The thief had been careful everywhere except in one respect. On the floor beneath the empty frame sat a jar of ${fruit} jam, unopened, with a paper label written in a neat, looping hand. Nobody in the museum could explain it. The police sergeant tasted a spoonful, declared it excellent, and filed it as evidence.`,
    `For a week there were no leads at all. The newspapers printed rumours that the painting had been smuggled out on a fishing boat, or cut into pieces, or sold to a collector overseas. Then a baker on the market square mentioned that he had seen a ${colour} ${vehicle} racing out of town in the grey light of that morning, ridden by two people in long coats, with a flat parcel strapped behind them.`,
    `The curator's niece, ${niece}, who spent her school holidays helping in the museum, took the clue seriously when the adults did not. She cycled from village to village asking about the ${colour} ${vehicle}, and at last she found it leaning against the wall of ${church}. Inside, wrapped in sacking and hidden in the bell tower, was ${painting}, completely unharmed.`,
    `The thieves were never caught. The jar of ${fruit} jam stayed on display beside the painting for many years, with a small card explaining how it came to be there, and it became almost as famous as the picture itself. Visitors still ask to see the jar before they ask to see the painting. ${cf} ${cl} gave ${niece} a key to the museum and a promise that she could curate an exhibition of her own when she was old enough.`,
  ];

  const paintingCore = painting.replace(/^The /, '').toLowerCase();
  const extra = extended ? museumExtra(rng.fork('extended'), { painting, pf, pl, cl, gf, gl, taken }) : null;
  if (extra) paras.splice(extra.at ?? paras.length, 0, extra.paragraph);
  return {
    id: 'museum',
    title: `The Vanishing of ${painting}`,
    text: paras.join('\n\n'),
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
      ...(extra?.facts ?? []),
    ],
  };
}

function expedition(rng: Rng, extended = false): SourceStory {
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

  const paras = [
    `On ${day} ${month} ${year}, an expedition of ${n} climbers set out from the village of ${village} to attempt the unclimbed north face of Mount ${mountain}. Their leader was ${lf} ${ll}, a geologist who had spent most of her life studying the mountain from below and who was determined, at last, to see it from above.`,
    `The team's guide was ${guf} ${gul}, who had grown up in ${village} and knew every path on the lower slopes. For luck, the climbers brought a goat named ${goat}, which carried the heaviest packs without complaint and seemed to enjoy the climb more than anyone. ${lf} carried a brass telescope engraved with the names of her grandparents, and each evening she used it to study the ridges ahead.`,
    `They climbed slowly, roped together in pairs, cutting steps into the ice wherever the old snow had hardened. At night they huddled around a small stove and argued cheerfully about the route, the weather and whose turn it was to fetch water. The guide taught them songs from the valley, and the leader taught them the names of the rocks beneath their boots, which most of them had forgotten by morning.`,
    `For the first week the weather was kind. They made steady progress and set up their highest camp at ${commas(height)} metres, on a narrow shelf of rock below the summit ridge. Then the wind changed. A storm settled over the mountain and refused to move, and the climbers were trapped in their tents for ${days} days, unable to climb higher or to retreat.`,
    `Their food ran low. By the end they were living on nothing but ${food} and melted snow, rationed out by ${guf} one handful at a time. ${goat} the goat, sheltering behind the largest tent, seemed the least troubled of them all. Every morning the climbers looked up at the ridge, and every morning the cloud sat on it like a lid. Nobody complained, but nobody laughed very much either.`,
    `When the storm finally lifted, ${ll} made a decision that surprised everyone: rather than push for the summit, they would go down while they still could. On the way down they found that a rockfall had opened a cave that had not been there before. Inside, the walls glittered with blue crystals that ${lf} recognised at once as something no geologist had ever described.`,
    `The expedition never reached the summit, and for years afterwards its critics called it a failure. But the samples ${lf} carried home in her rucksack made her famous, the crystal cave was named after the village of ${village}, and the climbers always insisted that the real hero of the journey had been a goat called ${goat}.`,
  ];

  const extra = extended ? expeditionExtra(rng.fork('extended'), { lf, ll, guf, gul, taken }) : null;
  if (extra) paras.splice(extra.at ?? paras.length, 0, extra.paragraph);
  return {
    id: 'expedition',
    title: `The ${mountain} Expedition`,
    text: paras.join('\n\n'),
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
      ...(extra?.facts ?? []),
    ],
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Extended stories (hard tier): one more paragraph and 8 more facts, drawn
// from a forked rng so the standard 12-fact stories are unchanged.
// ─────────────────────────────────────────────────────────────────────────────

interface Extra {
  paragraph: string;
  /** Paragraph index to insert at (default: the end). */
  at?: number;
  facts: FactSpec[];
}

const PORTS = ['Port Lorrin', 'Kestermouth', 'Ambersea', 'Vallory', 'Grisholm'];
const CARGOES: Array<[string, string[]]> = [
  ['crates of oranges', ['oranges', 'orange']],
  ['barrels of molasses', ['molasses']],
  ['bolts of silk', ['silk']],
  ['upright pianos', ['piano', 'pianos']],
];
const LENS_CITIES = ['Ostenmark', 'Brellin', 'Castora', 'Vandermeer'];
const PAINTER_TOWNS = ['Kellby', 'Saltash Minor', 'Pennwick', 'Morrowdale'];
const CURIOS: Array<[string, string[]]> = [
  ['brass hourglass', ['hourglass']],
  ['ivory chess knight', ['chess knight', 'chess piece', 'knight']],
  ['stuffed kingfisher', ['kingfisher']],
  ['silver thimble', ['thimble']],
];
const LAKES: Array<[string, string]> = [
  ['Lake Morrow', 'morrow'],
  ['the Glass Tarn', 'glass tarn'],
  ['Lake Ivvel', 'ivvel'],
  ['the Black Tarn', 'black tarn'],
];
const INSTRUMENTS: Array<[string, string[]]> = [
  ['concertina', ['concertina']],
  ['tin whistle', ['tin whistle', 'whistle']],
  ['fiddle', ['fiddle', 'violin']],
  ['banjo', ['banjo']],
];

function lighthouseExtra(x: Rng, v: { ship: string; town: string; kf: string; kl: string; bro: string; mayor: string; taken: Set<number> }): Extra {
  const [mayorF, mayorL] = v.mayor.split(' ') as [string, string];
  const capF = x.pick(MEN.filter((m) => m !== v.bro && m !== mayorF));
  const capL = x.pick(SURNAMES.filter((n) => n !== v.kl && n !== mayorL));
  const port = x.pick(PORTS);
  const [cargo, cargoAliases] = x.pick(CARGOES);
  const lens = x.pick(LENS_CITIES);
  const [age] = distinctInts(x, 1, 29, 61, v.taken) as [number];
  const [lanterns] = distinctInts(x, 1, 60, 140, v.taken) as [number];
  return {
    paragraph: `The captain of ${v.ship}, ${capF} ${capL}, later wrote that he owed his life to a lighthouse keeper and an umbrella. His ship had sailed from the port of ${port} a few days earlier with a cargo of ${cargo}, most of which washed up on the beaches of ${v.town} for weeks afterwards. ${v.kf} was ${age} years old that winter, and she had never been out in a boat at night before. The new lens was ground by an optician in ${lens}, and on the night it was first lit, ${lanterns} lanterns were hung along the harbour wall to celebrate.`,
    facts: [
      fact('captain', "captain's name", `The ship's captain was ${capF} ${capL}`, [[capL, capF], ['captain']], 10),
      fact('port', 'port of departure', `The ship had sailed from ${port}`, [port], 0),
      fact('cargo', 'cargo', `It carried a cargo of ${cargo}`, [cargoAliases], 0),
      fact('age', "keeper's age", `${v.kf} was ${age} years old`, [String(age), ['years', 'year', 'aged']], 3),
      fact('lens', 'where the lens was made', `The new lens was made in ${lens}`, [lens], 0),
      fact('lanterns', 'lanterns lit', `${lanterns} lanterns were hung along the harbour wall`, [String(lanterns), ['lanterns', 'lantern', 'lamps']], 2),
      fact('mayor', "mayor's name", `The mayor was ${v.mayor}`, [[mayorL, mayorF], ['mayor']], 8),
      fact('lifeboat', 'lifeboat fund', 'Half the reward went to the lifeboat fund', ['lifeboat', ['fund', 'half']], 6),
    ],
  };
}

function museumExtra(x: Rng, v: { painting: string; pf: string; pl: string; cl: string; gf: string; gl: string; taken: Set<number> }): Extra {
  const sgtF = x.pick(MEN.filter((m) => m !== v.pf && m !== v.gf));
  const sgtL = x.pick(SURNAMES.filter((n) => ![v.cl, v.pl, v.gl].includes(n)));
  const [paintYear] = distinctInts(x, 1, 1850, 1890, v.taken) as [number];
  const town = x.pick(PAINTER_TOWNS);
  const price = x.int(18, 95) * 10;
  const [curio, curioAliases] = x.pick(CURIOS);
  return {
    paragraph: `The police sergeant in charge of the case was ${sgtF} ${sgtL}, a patient man who kept bees in his spare time. He worked out that the thieves had climbed onto the roof from the chemist's shop next door and lowered themselves through a skylight. ${v.painting} had been painted in ${paintYear}, when ${v.pf} ${v.pl} was living in the fishing town of ${town}, and the museum had bought it at auction for just ${price} crowns. The only other thing missing from the gallery was a small ${curio}, which the thieves seemed to have taken as a souvenir.`,
    at: 4,
    facts: [
      fact('sergeant', "sergeant's name", `The police sergeant was ${sgtF} ${sgtL}`, [[sgtL, sgtF], ['sergeant', 'police', 'policeman', 'officer', 'detective']], 10),
      fact('skylight', 'way in', 'The thieves came in through a skylight', [['skylight', 'roof window']], 0),
      fact('chemist', 'neighbouring shop', "They climbed onto the roof from the chemist's shop", [['chemist', 'pharmacy', 'pharmacist', 'apothecary']], 0),
      fact('painted', 'year painted', `The painting was painted in ${paintYear}`, [String(paintYear), ['painted', 'paint', 'painting', 'completed', 'finished', 'created', 'canvas']], 8),
      fact('painterTown', "painter's town", `The painter lived in ${town}`, [town], 0),
      fact('price', 'auction price', `The museum paid ${price} crowns at auction`, [String(price), ['crowns', 'crown']], 2),
      fact('curio', 'other missing object', `A small ${curio} was also taken`, [curioAliases], 0),
      fact('detectives', 'detectives from the capital', 'Detectives came from the capital', [['detectives', 'detective', 'investigators', 'police'], 'capital'], 8),
    ],
  };
}

function expeditionExtra(x: Rng, v: { lf: string; ll: string; guf: string; gul: string; taken: Set<number> }): Extra {
  const [lake, lakeCore] = x.pick(LAKES);
  const docF = x.pick(MEN.filter((m) => m !== v.guf));
  const docL = x.pick(SURNAMES.filter((n) => n !== v.ll && n !== v.gul));
  const [instrument, instrumentAliases] = x.pick(INSTRUMENTS);
  const oldF = x.pick([...WOMEN, ...MEN].filter((n) => n !== v.lf && n !== v.guf && n !== docF));
  const oldL = x.pick(SURNAMES.filter((n) => ![v.ll, v.gul, docL].includes(n)));
  const [oldAge] = distinctInts(x, 1, 55, 71, v.taken) as [number];
  const [mules] = distinctInts(x, 1, 15, 40, v.taken) as [number];
  return {
    paragraph: `Their base camp stood beside a frozen lake that the villagers called ${lake}. The expedition's doctor, ${docF} ${docL}, carried a medicine chest and a ${instrument}, which he played badly every night. The oldest climber was ${oldF} ${oldL}, who was ${oldAge} years old and had climbed with the leader's father. The supplies had been carried up from the valley by ${mules} mules.`,
    at: 3,
    facts: [
      fact('lake', 'base-camp lake', `Base camp was beside ${lake}`, [lakeCore], 0),
      fact('doctor', "doctor's name", `The doctor was ${docF} ${docL}`, [[docL, docF], ['doctor', 'physician', 'medic']], 10),
      fact('instrument', "doctor's instrument", `The doctor played a ${instrument}`, [instrumentAliases], 0),
      fact('oldest', 'oldest climber', `The oldest climber was ${oldF} ${oldL}`, [[oldL, oldF], ['oldest', 'eldest']], 10),
      fact('oldAge', "oldest climber's age", `The oldest climber was ${oldAge} years old`, [String(oldAge), ['years', 'year', 'aged']], 3),
      fact('mules', 'pack mules', `Supplies came up on ${mules} mules`, [String(mules), ['mules', 'mule']], 2),
      fact('rockfall', 'what opened the cave', 'A rockfall opened the cave', [['rockfall', 'rock fall', 'rockslide', 'landslide', 'avalanche']], 0),
      fact('samples', 'samples carried home', 'She carried samples home in her rucksack', [['samples', 'sample'], ['rucksack', 'backpack', 'pack']], 8),
    ],
  };
}

const BUILDERS: Record<StoryId, (rng: Rng, extended: boolean) => SourceStory> = { lighthouse, museum, expedition };

/** 12 facts (standard) or 20 facts (hard tier: an extra paragraph with 8 more). */
export function generateStory(rng: Rng, which: StoryId | 'auto' = 'auto', facts: 12 | 20 = 12): SourceStory {
  const id = which === 'auto' ? rng.pick(STORY_IDS) : which;
  return BUILDERS[id](rng, facts === 20);
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
    if (perGroup.length === 1) return true;
    // Choose one match per group so that all of them fit inside the window.
    const search = (gi: number, lo: number, hi: number): boolean => {
      if (gi === perGroup.length) return true;
      for (const [s, e] of perGroup[gi]!) {
        const nlo = Math.min(lo, s);
        const nhi = Math.max(hi, e);
        if (nhi - nlo <= f.window && search(gi + 1, nlo, nhi)) return true;
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
