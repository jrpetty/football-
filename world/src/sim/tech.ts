// The technology tree — a compressed walk through real human history, from
// controlling fire to leaving the planet. Knowledge belongs to a tribe: any
// member's research adds to the tribe's pool, and contact between tribes lets
// ideas diffuse (cultural transmission), just as it did historically.

export const ERAS = [
  { name: 'Stone Age', color: '#b7a58a' },
  { name: 'Bronze Age', color: '#d0894b' },
  { name: 'Iron Age', color: '#9aa3ad' },
  { name: 'Classical Era', color: '#e2c35b' },
  { name: 'Medieval Era', color: '#a07ad6' },
  { name: 'Renaissance', color: '#e07a9a' },
  { name: 'Industrial Age', color: '#c96b4b' },
  { name: 'Modern Age', color: '#4fb3d9' },
  { name: 'Information Age', color: '#53d7a8' },
  { name: 'Space Age', color: '#9b8cff' },
]

export interface TechDef {
  id: string
  name: string
  era: number
  cost: number
  req: string[]
  desc: string
}

const RAW_TECHS: TechDef[] = [
  // ---------------------------------------------------------------- Stone Age
  { id: 'foraging', name: 'Foraging', era: 0, cost: 0, req: [], desc: 'Knowing which plants are safe to eat.' },
  { id: 'fire', name: 'Fire', era: 0, cost: 10, req: [], desc: 'Campfires for warmth, light, cooking and gathering.' },
  { id: 'stone_tools', name: 'Stone Tools', era: 0, cost: 12, req: [], desc: 'Knapped flint tools: faster gathering.' },
  { id: 'shelter', name: 'Shelter', era: 0, cost: 12, req: [], desc: 'Build huts to sleep warm and dry.' },
  { id: 'cordage', name: 'Cordage', era: 0, cost: 12, req: [], desc: 'Twisting plant fibre into string and rope. Fibre is gathered faster.' },
  { id: 'cooking', name: 'Cooking', era: 0, cost: 14, req: ['fire'], desc: 'Cooked food is more nourishing and carries less disease.' },
  { id: 'hunting', name: 'Hunting', era: 0, cost: 16, req: ['stone_tools'], desc: 'Spears and tracking: meat and hides from wild game.' },
  { id: 'dog_domestication', name: 'Domesticated Dogs', era: 0, cost: 20, req: ['hunting'], desc: 'Wolves that follow the camp become hunting partners and guards.' },
  { id: 'bow_and_arrow', name: 'Bow and Arrow', era: 0, cost: 24, req: ['hunting', 'cordage'], desc: 'Kill at a distance: far more hunts succeed.' },
  { id: 'fishing', name: 'Fishing', era: 0, cost: 16, req: ['stone_tools', 'cordage'], desc: 'Bone hooks, lines, weirs and nets: fish from rivers, lakes and coasts.' },
  { id: 'boats', name: 'Dugout Canoes', era: 0, cost: 30, req: ['stone_tools', 'fishing'], desc: 'Hollowed logs cross rivers and lakes and fish the calm coastal shallows.' },
  { id: 'clothing', name: 'Clothing', era: 0, cost: 16, req: ['hunting'], desc: 'Sew hides into warm clothes.' },
  { id: 'tanning', name: 'Tanning', era: 0, cost: 18, req: ['clothing'], desc: 'Bark-tanned leather lasts for years.' },
  { id: 'food_preservation', name: 'Smoking & Drying', era: 0, cost: 22, req: ['cooking'], desc: 'Smoked fish and dried meat keep through the winter.' },
  { id: 'herbalism', name: 'Herbalism', era: 0, cost: 20, req: ['foraging'], desc: 'Brew medicine from plants to heal the sick.' },
  { id: 'carpentry', name: 'Carpentry', era: 0, cost: 26, req: ['stone_tools'], desc: 'Workshops and sawn planks.' },
  { id: 'pottery', name: 'Pottery', era: 0, cost: 28, req: ['fire'], desc: 'Kilns, pots, granaries and baked bread.' },
  { id: 'weaving', name: 'Weaving', era: 0, cost: 24, req: ['clothing', 'cordage'], desc: 'Spin fibre into cloth and better clothes.' },
  { id: 'agriculture', name: 'Agriculture', era: 0, cost: 34, req: ['stone_tools', 'foraging'], desc: 'Plant and harvest grain fields — the Neolithic revolution.' },
  { id: 'mysticism', name: 'Mysticism', era: 0, cost: 30, req: ['fire'], desc: 'Shared beliefs, rituals and temples.' },
  { id: 'animal_husbandry', name: 'Animal Husbandry', era: 0, cost: 40, req: ['agriculture', 'hunting'], desc: 'Pastures of domesticated animals.' },
  // ---------------------------------------------------------------- Bronze Age
  { id: 'mining', name: 'Mining', era: 1, cost: 50, req: ['stone_tools', 'carpentry'], desc: 'Pits and short tunnels: dig ore from outcrops and shallow bodies.' },
  { id: 'prospecting', name: 'Prospecting', era: 1, cost: 55, req: ['mining'], desc: 'Read the land — stained rock, odd plants, heavy stream gravel — to find hidden ore bodies near the surface.' },
  { id: 'copper_smelting', name: 'Copper Smelting', era: 1, cost: 55, req: ['mining', 'pottery'], desc: 'Charcoal fires hot enough to melt copper from its ore.' },
  { id: 'masonry', name: 'Masonry', era: 1, cost: 55, req: ['carpentry', 'stone_tools'], desc: 'Stone houses, wells and walls.' },
  { id: 'bronze_working', name: 'Bronze Working', era: 1, cost: 70, req: ['copper_smelting'], desc: 'Alloy copper and tin. Smithies and bronze tools.' },
  { id: 'the_wheel', name: 'The Wheel', era: 1, cost: 60, req: ['carpentry'], desc: 'Carts and roads: much faster travel.' },
  { id: 'writing', name: 'Writing', era: 1, cost: 75, req: ['pottery'], desc: 'Record knowledge. Libraries and paper.' },
  { id: 'sailing', name: 'Sailing', era: 1, cost: 70, req: ['boats', 'carpentry'], desc: 'Sailing boats work the open sea. Docks and fishing fleets.' },
  { id: 'irrigation', name: 'Irrigation', era: 1, cost: 65, req: ['agriculture', 'masonry'], desc: 'Canals water the fields: crops grow faster and resist drought.' },
  { id: 'plough', name: 'The Plough', era: 1, cost: 70, req: ['agriculture', 'animal_husbandry', 'carpentry'], desc: 'Oxen turn the soil: much bigger harvests.' },
  { id: 'calendar', name: 'Calendar', era: 1, cost: 60, req: ['agriculture', 'mysticism'], desc: 'Tracking the seasons: better-timed planting.' },
  { id: 'brewing', name: 'Brewing', era: 1, cost: 55, req: ['agriculture', 'pottery'], desc: 'Beer and wine: safer than the water, and a reason to gather.' },
  { id: 'horseback_riding', name: 'Horseback Riding', era: 1, cost: 65, req: ['animal_husbandry'], desc: 'Riders travel much faster.' },
  // ---------------------------------------------------------------- Iron Age
  { id: 'iron_working', name: 'Iron Working', era: 2, cost: 120, req: ['bronze_working'], desc: 'Bloomery furnaces smelt iron for hard tools.' },
  { id: 'alphabet', name: 'Alphabet', era: 2, cost: 100, req: ['writing'], desc: 'A few dozen letters instead of thousands of signs: many more can read.' },
  { id: 'mathematics', name: 'Mathematics', era: 2, cost: 120, req: ['writing'], desc: 'Numbers, geometry and measurement.' },
  { id: 'code_of_laws', name: 'Code of Laws', era: 2, cost: 110, req: ['writing'], desc: 'Written law and town halls.' },
  { id: 'currency', name: 'Currency', era: 2, cost: 130, req: ['bronze_working', 'writing'], desc: 'Coins and markets for trade.' },
  { id: 'paved_roads', name: 'Paved Roads', era: 2, cost: 120, req: ['the_wheel', 'masonry'], desc: 'Stone-paved roads carry carts in any weather.' },
  // ---------------------------------------------------------------- Classical
  { id: 'philosophy', name: 'Philosophy', era: 3, cost: 190, req: ['mysticism', 'code_of_laws'], desc: 'Systematic thought: +10% research.' },
  { id: 'engineering', name: 'Engineering', era: 3, cost: 210, req: ['mathematics', 'iron_working'], desc: 'Cranes, arches and Roman concrete.' },
  { id: 'aqueducts', name: 'Aqueducts', era: 3, cost: 220, req: ['engineering', 'masonry'], desc: 'Clean water carried for miles: less disease.' },
  { id: 'medicine', name: 'Medicine', era: 3, cost: 200, req: ['herbalism', 'writing'], desc: 'Hospitals and longer lives.' },
  { id: 'glassmaking', name: 'Glassmaking', era: 3, cost: 180, req: ['pottery', 'mathematics'], desc: 'Melt sand into glass.' },
  { id: 'astronomy', name: 'Astronomy', era: 3, cost: 230, req: ['mathematics', 'mysticism'], desc: 'Calendars and celestial navigation.' },
  { id: 'cartography', name: 'Cartography', era: 3, cost: 200, req: ['mathematics', 'writing'], desc: 'Maps: travellers and sailors find their way.' },
  // ---------------------------------------------------------------- Medieval
  { id: 'education', name: 'Education', era: 4, cost: 360, req: ['philosophy'], desc: 'Universities: a big boost to research.' },
  { id: 'watermills', name: 'Water & Windmills', era: 4, cost: 330, req: ['engineering'], desc: 'Rivers and wind grind grain and saw timber.' },
  { id: 'heavy_plough', name: 'Heavy Plough', era: 4, cost: 340, req: ['plough', 'iron_working'], desc: 'Iron mouldboards break heavy clay soils.' },
  { id: 'horse_collar', name: 'Horse Collar', era: 4, cost: 320, req: ['horseback_riding', 'engineering'], desc: 'Horses pull ploughs and wagons without choking.' },
  { id: 'crop_rotation', name: 'Crop Rotation', era: 4, cost: 360, req: ['heavy_plough', 'calendar'], desc: 'Three fields in turn keep the soil fertile.' },
  { id: 'compass', name: 'Magnetic Compass', era: 4, cost: 300, req: ['sailing', 'iron_working'], desc: 'A needle that always points north, even under cloud.' },
  { id: 'mechanical_clock', name: 'Mechanical Clock', era: 4, cost: 350, req: ['engineering', 'mathematics'], desc: 'Time measured in hours: organised work.' },
  { id: 'spectacles', name: 'Spectacles', era: 4, cost: 300, req: ['glassmaking'], desc: 'Old scholars can keep reading.' },
  { id: 'blast_furnace', name: 'Blast Furnace', era: 4, cost: 380, req: ['iron_working', 'engineering'], desc: 'Bellows-driven furnaces pour liquid iron.' },
  { id: 'shaft_mining', name: 'Shaft Mining', era: 4, cost: 360, req: ['prospecting', 'engineering'], desc: 'Timbered shafts, windlasses and drainage adits reach ore 40–300 m down.' },
  { id: 'steel', name: 'Steel', era: 4, cost: 390, req: ['blast_furnace'], desc: 'Carbon steel for strong tools and structures.' },
  { id: 'banking', name: 'Banking', era: 4, cost: 370, req: ['currency', 'education'], desc: 'Credit and finance: markets thrive.' },
  { id: 'navigation', name: 'Navigation', era: 4, cost: 340, req: ['compass', 'astronomy'], desc: 'Deep-ocean voyages to distant lands.' },
  { id: 'sanitation', name: 'Sanitation', era: 4, cost: 330, req: ['aqueducts', 'medicine'], desc: 'Sewers and clean water halve disease.' },
  // ---------------------------------------------------------------- Renaissance
  { id: 'printing_press', name: 'Printing Press', era: 5, cost: 560, req: ['education', 'engineering'], desc: 'Mass knowledge: +20% research.' },
  { id: 'optics', name: 'Optics', era: 5, cost: 560, req: ['spectacles', 'education'], desc: 'Telescopes and microscopes reveal the very far and the very small.' },
  { id: 'physics', name: 'Physics', era: 5, cost: 620, req: ['education', 'astronomy'], desc: 'Laws of motion and mechanics.' },
  { id: 'scientific_method', name: 'Scientific Method', era: 5, cost: 600, req: ['optics', 'printing_press'], desc: 'Hypothesis, experiment, publication.' },
  { id: 'chemistry', name: 'Chemistry', era: 5, cost: 640, req: ['glassmaking', 'education'], desc: 'Elements, reactions and compounds.' },
  { id: 'geology', name: 'Geology', era: 5, cost: 620, req: ['chemistry', 'prospecting'], desc: 'Strata, faults and minerals: prospectors find deep ore bodies over wide areas.' },
  { id: 'shipbuilding', name: 'Ocean Shipbuilding', era: 5, cost: 560, req: ['navigation', 'carpentry'], desc: 'Carracks and galleons: big hulls for long voyages and deep-sea fishing.' },
  { id: 'gunpowder', name: 'Gunpowder', era: 5, cost: 520, req: ['chemistry'], desc: 'Blasting powder: faster mining.' },
  // ---------------------------------------------------------------- Industrial
  { id: 'steam_power', name: 'Steam Power', era: 6, cost: 1000, req: ['physics', 'steel'], desc: 'Engines that work without muscle — first used to pump water out of mines.' },
  { id: 'deep_mining', name: 'Deep Mining', era: 6, cost: 1050, req: ['steam_power', 'shaft_mining'], desc: 'Steam pumps and winding engines reach ore and coal hundreds of metres down.' },
  { id: 'coke_smelting', name: 'Coke Smelting', era: 6, cost: 950, req: ['blast_furnace', 'chemistry'], desc: 'Coal baked into coke smelts iron by the ton.' },
  { id: 'industrialization', name: 'Industrialization', era: 6, cost: 1100, req: ['steam_power'], desc: 'Factories and machinery.' },
  { id: 'textile_mills', name: 'Textile Mills', era: 6, cost: 1050, req: ['industrialization', 'weaving'], desc: 'Spinning frames and power looms.' },
  { id: 'railways', name: 'Railways', era: 6, cost: 1100, req: ['steam_power'], desc: 'Roads become rail: very fast travel.' },
  { id: 'steamships', name: 'Steamships & Trawlers', era: 6, cost: 1150, req: ['steam_power', 'shipbuilding'], desc: 'Steam trawlers drag great nets: far bigger catches, far from shore.' },
  { id: 'telegraph', name: 'Telegraph', era: 6, cost: 1200, req: ['electricity'], desc: 'Messages across the land in seconds.' },
  { id: 'germ_theory', name: 'Germ Theory', era: 6, cost: 1100, req: ['optics', 'medicine'], desc: 'Microbes cause disease: wash hands, boil water.' },
  { id: 'anaesthesia', name: 'Anaesthesia', era: 6, cost: 1100, req: ['chemistry', 'medicine'], desc: 'Surgery without agony.' },
  { id: 'canning', name: 'Canning', era: 6, cost: 1000, req: ['food_preservation', 'industrialization'], desc: 'Sealed tins keep food for years.' },
  { id: 'dynamite', name: 'Dynamite', era: 6, cost: 1150, req: ['gunpowder', 'chemistry'], desc: 'Safe, powerful explosives for mines and quarries.' },
  { id: 'oil_drilling', name: 'Oil Drilling', era: 6, cost: 1150, req: ['steam_power', 'geology'], desc: 'Drilled wells tap oil reservoirs deep underground.' },
  { id: 'refining', name: 'Oil Refining', era: 6, cost: 1200, req: ['chemistry', 'oil_drilling'], desc: 'Distil crude oil into fuels.' },
  { id: 'fertilizer', name: 'Chemical Fertiliser', era: 6, cost: 1200, req: ['chemistry', 'crop_rotation'], desc: 'Superphosphates feed the soil.' },
  { id: 'electricity', name: 'Electricity', era: 6, cost: 1400, req: ['physics', 'industrialization'], desc: 'Power plants, electric light, power tools.' },
  { id: 'vaccination', name: 'Vaccination', era: 6, cost: 1150, req: ['germ_theory', 'chemistry'], desc: 'Immunity before infection: plagues lose their terror.' },
  // ---------------------------------------------------------------- Modern
  { id: 'combustion', name: 'Combustion Engine', era: 7, cost: 1600, req: ['refining'], desc: 'Engines burning petrol and diesel.' },
  { id: 'automobiles', name: 'Automobiles', era: 7, cost: 1800, req: ['combustion', 'paved_roads'], desc: 'Cars and lorries on every road.' },
  { id: 'tractors', name: 'Tractors', era: 7, cost: 1900, req: ['combustion', 'crop_rotation'], desc: 'One farmer works what took twenty.' },
  { id: 'telephone', name: 'Telephone', era: 7, cost: 1700, req: ['telegraph'], desc: 'Voices over wires.' },
  { id: 'electronics', name: 'Electronics', era: 7, cost: 1800, req: ['electricity'], desc: 'Vacuum tubes, circuits and radio.' },
  { id: 'radio', name: 'Radio & Television', era: 7, cost: 1800, req: ['electronics', 'telephone'], desc: 'Broadcasts reach every home.' },
  { id: 'research_labs', name: 'Research Laboratories', era: 7, cost: 1800, req: ['electricity', 'scientific_method'], desc: 'Laboratories: huge research boost.' },
  { id: 'urban_planning', name: 'Urban Planning', era: 7, cost: 1800, req: ['industrialization', 'engineering'], desc: 'Modern concrete and apartment blocks.' },
  { id: 'electric_grid', name: 'Electric Grid', era: 7, cost: 1900, req: ['electricity', 'urban_planning'], desc: 'Power stations wired to every building.' },
  { id: 'antibiotics', name: 'Antibiotics', era: 7, cost: 2000, req: ['germ_theory', 'chemistry'], desc: 'Penicillin: infection is rarely fatal.' },
  { id: 'refrigeration', name: 'Refrigeration', era: 7, cost: 1700, req: ['electricity', 'canning'], desc: 'Cold stores and fridges: food keeps fresh.' },
  { id: 'plastics', name: 'Plastics', era: 7, cost: 1900, req: ['refining'], desc: 'Polymers moulded into anything.' },
  { id: 'aluminium_smelting', name: 'Aluminium Smelting', era: 7, cost: 1900, req: ['electricity', 'chemistry'], desc: 'Electrolysis turns bauxite into light, strong aluminium.' },
  { id: 'haber_bosch', name: 'Synthetic Fertiliser', era: 7, cost: 2100, req: ['fertilizer', 'electricity'], desc: 'Nitrogen from the air: harvests that feed billions.' },
  { id: 'seismic_survey', name: 'Seismic Surveying', era: 7, cost: 2000, req: ['geology', 'electronics'], desc: 'Sound waves map rock kilometres down — and under the sea.' },
  { id: 'offshore_drilling', name: 'Offshore Drilling', era: 7, cost: 2400, req: ['oil_drilling', 'seismic_survey', 'steamships'], desc: 'Platforms drill oil from beneath the sea floor.' },
  { id: 'diesel_trawlers', name: 'Diesel Trawlers & Sonar', era: 7, cost: 2100, req: ['combustion', 'steamships', 'refrigeration'], desc: 'Sonar finds the shoals, freezer ships keep the catch: and stocks begin to collapse.' },
  { id: 'flight', name: 'Flight', era: 7, cost: 2200, req: ['combustion', 'physics'], desc: 'Powered flight and airfields.' },
  { id: 'nuclear_power', name: 'Nuclear Power', era: 7, cost: 2800, req: ['physics', 'research_labs', 'electric_grid'], desc: 'Fission reactors: vast power from a little uranium.' },
  { id: 'green_revolution', name: 'Green Revolution', era: 7, cost: 2400, req: ['haber_bosch', 'tractors'], desc: 'High-yield crops, irrigation and fertiliser.' },
  // ---------------------------------------------------------------- Information
  { id: 'transistor', name: 'Transistor', era: 8, cost: 2600, req: ['electronics', 'research_labs'], desc: 'Solid-state switches: electronics shrink a thousandfold.' },
  { id: 'computing', name: 'Computing', era: 8, cost: 3000, req: ['transistor'], desc: 'Computers and data centres.' },
  { id: 'jet_engines', name: 'Jet Engines', era: 8, cost: 2800, req: ['flight', 'steel'], desc: 'Fast, high flight: the world shrinks.' },
  { id: 'skyscrapers', name: 'Skyscrapers', era: 8, cost: 2800, req: ['urban_planning', 'steel'], desc: 'Towers of steel and glass.' },
  { id: 'internet', name: 'The Internet', era: 8, cost: 3800, req: ['computing', 'telephone'], desc: 'Global knowledge network: +30% research.' },
  { id: 'mobile_phones', name: 'Mobile Phones', era: 8, cost: 4000, req: ['internet', 'lithium_batteries'], desc: 'Everyone carries the network in their pocket.' },
  { id: 'robotics', name: 'Robotics', era: 8, cost: 4200, req: ['computing', 'industrialization'], desc: 'Automation: everyone works faster.' },
  { id: 'wind_power', name: 'Wind Turbines', era: 8, cost: 3400, req: ['electric_grid', 'plastics'], desc: 'Clean power from the wind.' },
  { id: 'solar_power', name: 'Solar Power', era: 8, cost: 3600, req: ['transistor', 'aluminium_smelting'], desc: 'Photovoltaic panels turn sunlight into electricity.' },
  { id: 'lithium_batteries', name: 'Lithium Batteries', era: 8, cost: 3600, req: ['chemistry', 'electronics'], desc: 'Light, dense energy storage.' },
  { id: 'electric_vehicles', name: 'Electric Vehicles', era: 8, cost: 4400, req: ['lithium_batteries', 'automobiles'], desc: 'Quiet cars running on stored power.' },
  { id: 'genetic_engineering', name: 'Genetic Engineering', era: 8, cost: 4200, req: ['antibiotics', 'computing'], desc: 'Reading and editing DNA: hardier crops, new medicines.' },
  { id: 'aquaculture', name: 'Aquaculture', era: 8, cost: 3200, req: ['diesel_trawlers', 'genetic_engineering'], desc: 'Fish farms: seafood without emptying the sea.' },
  { id: 'mrna_vaccines', name: 'mRNA Vaccines', era: 8, cost: 4600, req: ['genetic_engineering', 'vaccination'], desc: 'New vaccines designed in days.' },
  { id: 'artificial_intelligence', name: 'Artificial Intelligence', era: 8, cost: 5000, req: ['internet', 'robotics'], desc: 'Machines that learn: research and work transformed.' },
  // ---------------------------------------------------------------- Space
  { id: 'rocketry', name: 'Rocketry', era: 9, cost: 5200, req: ['jet_engines', 'computing'], desc: 'Build a launch pad and reach orbit.' },
  { id: 'satellites', name: 'Satellites', era: 9, cost: 5400, req: ['rocketry', 'electronics'], desc: 'Eyes and relays in orbit: weather, mapping, communication.' },
  { id: 'gps', name: 'Satellite Navigation', era: 9, cost: 5600, req: ['satellites', 'computing'], desc: 'Everyone knows exactly where they are.' },
  { id: 'space_station', name: 'Space Station', era: 9, cost: 6200, req: ['satellites', 'robotics'], desc: 'People living in orbit.' },
  { id: 'reusable_rockets', name: 'Reusable Rockets', era: 9, cost: 6600, req: ['rocketry', 'artificial_intelligence'], desc: 'Boosters that land and fly again: space becomes cheap.' },
]

/** Ideas a people reaches for first when choosing what to study: survival before luxury. */
export const RESEARCH_PRIORITY: Record<string, number> = {
  fire: 1.5, stone_tools: 1.5, shelter: 1.3, cooking: 1.3, hunting: 1.4, fishing: 1.5, agriculture: 1.7, clothing: 1.2,
  pottery: 1.2, carpentry: 1.2, animal_husbandry: 1.3, mining: 1.3, writing: 1.4, irrigation: 1.2, plough: 1.3,
  tanning: 0.6, brewing: 0.6, dog_domestication: 0.8, food_preservation: 0.9, mysticism: 0.8, spectacles: 0.7, alphabet: 0.9,
}

/** Research costs are scaled so a civilisation's rise takes hours, not minutes. */
// Each era is harder to reach than the last — the stone age passes quickly in
// game time, but industry and computing take generations.
export const RESEARCH_SCALE = 3.5
export const ERA_SCALE = 2.2
export const TECHS: TechDef[] = RAW_TECHS.map((t) => ({ ...t, cost: Math.round(t.cost * (RESEARCH_SCALE + t.era * ERA_SCALE * 0.7 + t.era * t.era * 0.35)) }))

export const TECH_BY_ID: Record<string, TechDef> = Object.fromEntries(TECHS.map((t) => [t.id, t]))

export function availableTechs(known: Set<string>): TechDef[] {
  return TECHS.filter((t) => !known.has(t.id) && t.req.every((r) => known.has(r)))
}

export function eraOf(known: Set<string>): number {
  let era = 0
  for (const id of known) {
    const t = TECH_BY_ID[id]
    if (t && t.era > era) era = t.era
  }
  return era
}

// ------------------------------------------------------------------ what each idea does
//
// Every technology changes how the world works for the people who know it.
// Effects are multipliers on broad kinds of work (stacking as ideas pile up).

export type FxKey = 'research' | 'work' | 'farm' | 'fish' | 'mine' | 'hunt' | 'health' | 'travel' | 'build' | 'craft'

export const TECH_FX: Record<string, Partial<Record<FxKey, number>>> = {
  stone_tools: { work: 0.05 }, cooking: { health: 0.25 }, dog_domestication: { hunt: 0.15 }, bow_and_arrow: { hunt: 0.3 },
  fishing: { fish: 0.2 }, tanning: { craft: 0.05 }, food_preservation: { health: 0.05, farm: 0.05, fish: 0.05 },
  agriculture: { farm: 0.05 }, irrigation: { farm: 0.2 }, plough: { farm: 0.3 }, calendar: { farm: 0.08 }, brewing: { health: 0.05 },
  horseback_riding: { travel: 0.15 }, the_wheel: { travel: 0.05, build: 0.05 }, prospecting: { mine: 0.1 },
  copper_smelting: { craft: 0.05 }, bronze_working: { work: 0.05 }, iron_working: { work: 0.05, mine: 0.1 },
  alphabet: { research: 0.05 }, mathematics: { research: 0.05, build: 0.05 }, paved_roads: { travel: 0.1 },
  philosophy: { research: 0.1 }, engineering: { build: 0.15 }, aqueducts: { health: 0.15 }, medicine: { health: 0.15 },
  cartography: { travel: 0.05, fish: 0.05 }, education: { research: 0.05 }, watermills: { craft: 0.1, farm: 0.1 },
  heavy_plough: { farm: 0.2 }, horse_collar: { farm: 0.1, travel: 0.05 }, crop_rotation: { farm: 0.2 }, compass: { fish: 0.1 },
  mechanical_clock: { work: 0.05 }, spectacles: { research: 0.05 }, blast_furnace: { craft: 0.15 }, shaft_mining: { mine: 0.15 },
  sanitation: { health: 0.3 }, printing_press: { research: 0.2 }, optics: { research: 0.05 }, scientific_method: { research: 0.15 },
  chemistry: { craft: 0.05 }, geology: { mine: 0.1 }, shipbuilding: { fish: 0.25 }, gunpowder: { mine: 0.4 },
  steam_power: { work: 0.1, mine: 0.15 }, deep_mining: { mine: 0.25 }, coke_smelting: { craft: 0.2 }, industrialization: { craft: 0.2 },
  textile_mills: { craft: 0.1 }, railways: { travel: 0.1 }, steamships: { fish: 0.5 }, telegraph: { research: 0.05 },
  germ_theory: { health: 0.3 }, anaesthesia: { health: 0.1 }, canning: { health: 0.05, farm: 0.05 }, dynamite: { mine: 0.4, build: 0.1 },
  fertilizer: { farm: 0.25 }, electricity: { work: 0.1 }, vaccination: { health: 0.3 }, combustion: { work: 0.05 },
  automobiles: { travel: 0.25 }, tractors: { farm: 0.4 }, telephone: { research: 0.05 }, radio: { research: 0.05 },
  research_labs: { research: 0.1 }, electric_grid: { work: 0.1 }, antibiotics: { health: 0.4 }, refrigeration: { health: 0.05, farm: 0.05, fish: 0.1 },
  haber_bosch: { farm: 0.4 }, seismic_survey: { mine: 0.1 }, diesel_trawlers: { fish: 0.6 }, flight: { travel: 0.05 },
  green_revolution: { farm: 0.4 }, transistor: { research: 0.05 }, computing: { research: 0.1, work: 0.05 }, jet_engines: { travel: 0.05 },
  internet: { research: 0.3 }, mobile_phones: { research: 0.1, work: 0.05 }, robotics: { work: 0.4, mine: 0.2, build: 0.2 },
  electric_vehicles: { travel: 0.1 }, genetic_engineering: { farm: 0.2, health: 0.2 }, mrna_vaccines: { health: 0.25 },
  artificial_intelligence: { research: 0.4, work: 0.2 }, satellites: { research: 0.1, fish: 0.1 }, gps: { travel: 0.1, fish: 0.1 },
  space_station: { research: 0.15 }, reusable_rockets: { research: 0.05 },
}

const fxCache = new WeakMap<Set<string>, { size: number; v: Partial<Record<FxKey, number>> }>()
/** The combined multiplier a people's knowledge gives to one kind of work. */
export function techFx(known: Set<string>, key: FxKey): number {
  let c = fxCache.get(known)
  if (!c || c.size !== known.size) { c = { size: known.size, v: {} }; fxCache.set(known, c) }
  const hit = c.v[key]
  if (hit !== undefined) return hit
  let m = 1
  for (const id of known) { const f = TECH_FX[id]?.[key]; if (f) m *= 1 + f }
  c.v[key] = m
  return m
}

/** Research speed multiplier contributed by known techs. */
export function techResearchBonus(known: Set<string>): number {
  return techFx(known, 'research')
}

/** Work-speed multiplier from tribe-wide technology. */
export function techWorkBonus(known: Set<string>): number {
  return techFx(known, 'work')
}

// ------------------------------------------------------------------ learning by doing
//
// Most early knowledge wasn't "researched" — it was noticed. People who work
// clay by a fire discover pottery; people who watch seeds sprout discover
// farming. Every hour of relevant practice adds insight to the techs listed
// here (activity keys are prefixes: "gather" matches "gather:clay").

export const INSPIRE: Record<string, string[]> = {
  fire: ['gather:wood', 'gather:stone', 'cold'], stone_tools: ['gather:stone', 'hunt'], shelter: ['gather:wood', 'gather:fiber', 'cold', 'sleep'],
  cordage: ['gather:fiber', 'build'], cooking: ['eat', 'build:campfire', 'hunt'], hunting: ['hunt', 'gather:stone'],
  fishing: ['fish', 'gather:fiber', 'drink'], boats: ['gather:wood', 'fish', 'explore'], clothing: ['hunt', 'cold'],
  herbalism: ['gather:berries', 'gather:fiber', 'sick', 'heal'], carpentry: ['gather:wood', 'build'], pottery: ['gather:clay', 'build:campfire', 'eat'],
  weaving: ['gather:fiber', 'craft'], agriculture: ['gather:berries', 'gather:fiber', 'farm'], mysticism: ['celebrate', 'grief', 'talk', 'sleep'],
  animal_husbandry: ['hunt', 'farm'], mining: ['gather:stone', 'gather:clay'], masonry: ['gather:stone', 'build'],
  bronze_working: ['gather:copper', 'gather:tin', 'craft', 'build:kiln'], the_wheel: ['craft:pottery', 'build', 'trade'],
  writing: ['trade', 'talk', 'craft:pottery', 'decree'], sailing: ['fish', 'explore', 'gather:wood'], irrigation: ['farm', 'drink'],
  plough: ['farm'], calendar: ['farm', 'celebrate', 'sleep'], horseback_riding: ['farm:pasture', 'hunt', 'explore'],
  iron_working: ['gather:iron_ore', 'craft:bronze', 'build:smithy'], mathematics: ['trade', 'build'],
  code_of_laws: ['decree', 'talk', 'trade'], currency: ['trade', 'gather:gold'], philosophy: ['write', 'talk', 'celebrate'],
  engineering: ['build'], medicine: ['heal', 'sick'], glassmaking: ['gather:sand', 'craft:pottery', 'craft'],
  astronomy: ['explore', 'sleep'], education: ['teach', 'write'], steel: ['craft:iron', 'craft'], banking: ['trade'],
  navigation: ['explore', 'fish'], sanitation: ['sick', 'build', 'drink'], printing_press: ['write', 'craft:paper'],
  physics: ['build'], chemistry: ['craft'], gunpowder: ['craft', 'gather:coal'], steam_power: ['craft', 'gather:coal'],
  industrialization: ['craft', 'build'], railways: ['build:road'], refining: ['gather:oil'],
  electricity: ['craft'], vaccination: ['heal', 'sick'], combustion: ['craft:fuel'],
  electronics: ['craft'], research_labs: ['craft'], urban_planning: ['build'], antibiotics: ['heal', 'sick'],
  flight: ['explore'], computing: ['craft:electronics'], skyscrapers: ['build'],
  internet: ['talk'], robotics: ['craft'], rocketry: ['craft:fuel'],
  dog_domestication: ['hunt', 'eat'], bow_and_arrow: ['hunt', 'gather:fiber', 'gather:wood'], tanning: ['hunt', 'craft'],
  food_preservation: ['fish', 'hunt', 'build:campfire'], prospecting: ['gather:copper', 'gather:tin', 'gather:stone', 'explore', 'prospect'],
  copper_smelting: ['gather:copper', 'build:kiln', 'craft:pottery'], brewing: ['farm', 'craft:pottery'], alphabet: ['write', 'trade'],
  paved_roads: ['build:road', 'gather:stone'], aqueducts: ['build', 'drink'], cartography: ['explore', 'prospect'],
  watermills: ['farm', 'build'], heavy_plough: ['farm'], horse_collar: ['farm:pasture', 'farm'], crop_rotation: ['farm'],
  compass: ['fish', 'explore'], mechanical_clock: ['craft'], spectacles: ['research', 'write'], blast_furnace: ['craft:iron', 'build:smithy'],
  shaft_mining: ['gather:copper', 'gather:iron_ore', 'gather:coal', 'prospect'], optics: ['craft:glass', 'research'],
  scientific_method: ['research', 'write'], geology: ['prospect', 'gather:coal', 'gather:iron_ore'], shipbuilding: ['fish', 'build:dock'],
  deep_mining: ['gather:coal', 'gather:iron_ore', 'prospect'], coke_smelting: ['gather:coal', 'craft:iron'], textile_mills: ['craft:cloth'],
  steamships: ['fish'], telegraph: ['talk'], germ_theory: ['heal', 'sick'], anaesthesia: ['heal'], canning: ['craft'],
  dynamite: ['gather:stone', 'craft'], oil_drilling: ['gather:oil', 'prospect'], fertilizer: ['farm'], automobiles: ['craft:fuel'],
  tractors: ['farm'], telephone: ['talk'], radio: ['talk'], electric_grid: ['build'], refrigeration: ['craft'], plastics: ['craft:fuel'],
  aluminium_smelting: ['gather:bauxite', 'craft'], haber_bosch: ['farm'], seismic_survey: ['prospect'], offshore_drilling: ['gather:oil', 'fish'],
  diesel_trawlers: ['fish'], nuclear_power: ['gather:uranium', 'craft'], green_revolution: ['farm'], transistor: ['craft:electronics'],
  jet_engines: ['craft'], mobile_phones: ['talk'], wind_power: ['build'], solar_power: ['build'], lithium_batteries: ['gather:lithium', 'craft'],
  electric_vehicles: ['craft'], genetic_engineering: ['research', 'farm'], aquaculture: ['fish'], mrna_vaccines: ['heal'],
  artificial_intelligence: ['research'], satellites: ['craft:fuel'], gps: ['explore'], space_station: ['craft'], reusable_rockets: ['craft:fuel'],
}

export function inspires(tech: string, key: string): boolean {
  const list = INSPIRE[tech]
  if (!list) return false
  for (const e of list) if (key === e || key.startsWith(e + ':')) return true
  return false
}

/** What a tribe's lands offer — geography shapes which ideas come easily. */
export interface Landscape {
  coast: number; river: number; forest: number; fertile: number; cold: number; game: number
  copper: number; tin: number; iron: number; coal: number; gold: number; oil: number; sand: number; clay: number
  bauxite: number; uranium: number; lithium: number
  /** hills and mountains explored: where people expect to find ore */
  hills: number
}

/** Research multiplier from geography (Guns, Germs and Steel in miniature). */
export function geographyAffinity(tech: string, l: Landscape): number {
  const has = (v: number, k = 1) => (v > 0 ? 1 + 0.35 * k : 1 - 0.3 * k)
  switch (tech) {
    case 'fishing': return has(l.coast + l.river)
    case 'boats': return has(l.coast + l.river)
    case 'sailing': case 'navigation': return has(l.coast, 1.4)
    case 'agriculture': case 'irrigation': case 'calendar': return has(l.fertile > 20 ? 1 : 0) * (l.river > 0 ? 1.15 : 0.95)
    case 'plough': return has(l.fertile > 20 ? 1 : 0)
    case 'animal_husbandry': case 'horseback_riding': return has(l.game)
    case 'clothing': case 'shelter': return l.cold > 0.3 ? 1.5 : 0.9
    case 'pottery': return has(l.clay)
    case 'mining': return has(l.copper + l.tin + l.iron + l.coal + l.gold + l.hills)
    case 'bronze_working': return has(l.copper + l.hills, 0.6) * has(l.tin + l.hills, 0.4)
    case 'iron_working': case 'steel': return has(l.iron + l.hills, 0.7)
    case 'currency': return has(l.gold, 0.6)
    case 'glassmaking': return has(l.sand)
    case 'steam_power': case 'gunpowder': return has(l.coal)
    case 'refining': case 'combustion': case 'oil_drilling': case 'offshore_drilling': return has(l.oil, 1.4)
    case 'prospecting': case 'shaft_mining': case 'geology': case 'deep_mining': return has(l.copper + l.tin + l.iron + l.coal + l.gold + l.hills)
    case 'copper_smelting': return has(l.copper + l.hills, 0.5)
    case 'coke_smelting': return has(l.coal)
    case 'shipbuilding': case 'steamships': case 'diesel_trawlers': case 'compass': return has(l.coast, 1.2)
    case 'aluminium_smelting': return has(l.bauxite, 0.8)
    case 'nuclear_power': return has(l.uranium, 0.6)
    case 'lithium_batteries': return has(l.lithium, 0.5)
    case 'carpentry': return has(l.forest > 30 ? 1 : 0, 0.6)
    default: return 1
  }
}
