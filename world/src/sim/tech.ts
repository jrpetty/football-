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
  // Stone Age
  { id: 'foraging', name: 'Foraging', era: 0, cost: 0, req: [], desc: 'Knowing which plants are safe to eat.' },
  { id: 'fire', name: 'Fire', era: 0, cost: 10, req: [], desc: 'Campfires for warmth, light, cooking and gathering.' },
  { id: 'stone_tools', name: 'Stone Tools', era: 0, cost: 12, req: [], desc: 'Knapped flint tools: faster gathering.' },
  { id: 'shelter', name: 'Shelter', era: 0, cost: 12, req: [], desc: 'Build huts to sleep warm and dry.' },
  { id: 'hunting', name: 'Hunting', era: 0, cost: 16, req: ['stone_tools'], desc: 'Spears and tracking: meat and hides from wild game.' },
  { id: 'fishing', name: 'Fishing', era: 0, cost: 16, req: ['stone_tools'], desc: 'Nets and hooks: fish from rivers, lakes and coasts.' },
  { id: 'clothing', name: 'Clothing', era: 0, cost: 16, req: ['hunting'], desc: 'Sew hides into warm clothes.' },
  { id: 'herbalism', name: 'Herbalism', era: 0, cost: 20, req: ['foraging'], desc: 'Brew medicine from plants to heal the sick.' },
  { id: 'carpentry', name: 'Carpentry', era: 0, cost: 26, req: ['stone_tools'], desc: 'Workshops and sawn planks.' },
  { id: 'pottery', name: 'Pottery', era: 0, cost: 28, req: ['fire'], desc: 'Kilns, pots, granaries and baked bread.' },
  { id: 'weaving', name: 'Weaving', era: 0, cost: 24, req: ['clothing'], desc: 'Spin fibre into cloth and better clothes.' },
  { id: 'agriculture', name: 'Agriculture', era: 0, cost: 34, req: ['stone_tools', 'foraging'], desc: 'Plant and harvest grain fields — the Neolithic revolution.' },
  { id: 'mysticism', name: 'Mysticism', era: 0, cost: 30, req: ['fire'], desc: 'Shared beliefs, rituals and temples.' },
  { id: 'animal_husbandry', name: 'Animal Husbandry', era: 0, cost: 40, req: ['agriculture', 'hunting'], desc: 'Pastures of domesticated animals.' },
  // Bronze Age
  { id: 'mining', name: 'Mining', era: 1, cost: 50, req: ['stone_tools', 'carpentry'], desc: 'Dig ore: copper, tin, iron, coal and gold.' },
  { id: 'masonry', name: 'Masonry', era: 1, cost: 55, req: ['carpentry', 'stone_tools'], desc: 'Stone houses, wells and walls.' },
  { id: 'bronze_working', name: 'Bronze Working', era: 1, cost: 70, req: ['mining', 'pottery'], desc: 'Alloy copper and tin. Smithies and bronze tools.' },
  { id: 'the_wheel', name: 'The Wheel', era: 1, cost: 60, req: ['carpentry'], desc: 'Carts and roads: much faster travel.' },
  { id: 'writing', name: 'Writing', era: 1, cost: 75, req: ['pottery'], desc: 'Record knowledge. Libraries and paper.' },
  { id: 'sailing', name: 'Sailing', era: 1, cost: 70, req: ['fishing', 'carpentry'], desc: 'Boats cross water. Docks for deep-water fishing.' },
  // Iron Age
  { id: 'iron_working', name: 'Iron Working', era: 2, cost: 120, req: ['bronze_working'], desc: 'Smelt iron for hard tools.' },
  { id: 'mathematics', name: 'Mathematics', era: 2, cost: 120, req: ['writing'], desc: 'Numbers, geometry and measurement.' },
  { id: 'code_of_laws', name: 'Code of Laws', era: 2, cost: 110, req: ['writing'], desc: 'Written law and town halls.' },
  { id: 'currency', name: 'Currency', era: 2, cost: 130, req: ['bronze_working', 'writing'], desc: 'Coins and markets for trade.' },
  // Classical
  { id: 'philosophy', name: 'Philosophy', era: 3, cost: 190, req: ['mysticism', 'code_of_laws'], desc: 'Systematic thought: +10% research.' },
  { id: 'engineering', name: 'Engineering', era: 3, cost: 210, req: ['mathematics', 'iron_working'], desc: 'Mills, aqueducts and Roman concrete.' },
  { id: 'medicine', name: 'Medicine', era: 3, cost: 200, req: ['herbalism', 'writing'], desc: 'Hospitals and longer lives.' },
  { id: 'glassmaking', name: 'Glassmaking', era: 3, cost: 180, req: ['pottery', 'mathematics'], desc: 'Melt sand into glass.' },
  { id: 'astronomy', name: 'Astronomy', era: 3, cost: 230, req: ['mathematics', 'mysticism'], desc: 'Calendars and celestial navigation.' },
  // Medieval
  { id: 'education', name: 'Education', era: 4, cost: 360, req: ['philosophy'], desc: 'Universities: a big boost to research.' },
  { id: 'steel', name: 'Steel', era: 4, cost: 390, req: ['iron_working', 'engineering'], desc: 'Carbon steel for strong tools and structures.' },
  { id: 'banking', name: 'Banking', era: 4, cost: 370, req: ['currency', 'education'], desc: 'Credit and finance: markets thrive.' },
  { id: 'navigation', name: 'Navigation', era: 4, cost: 340, req: ['sailing', 'astronomy'], desc: 'Ocean voyages to distant lands.' },
  // Renaissance
  { id: 'printing_press', name: 'Printing Press', era: 5, cost: 560, req: ['education', 'engineering'], desc: 'Mass knowledge: +20% research.' },
  { id: 'physics', name: 'Physics', era: 5, cost: 620, req: ['education', 'astronomy'], desc: 'Laws of motion and mechanics.' },
  { id: 'chemistry', name: 'Chemistry', era: 5, cost: 640, req: ['glassmaking', 'education'], desc: 'Elements, reactions and compounds.' },
  { id: 'gunpowder', name: 'Gunpowder', era: 5, cost: 520, req: ['chemistry'], desc: 'Blasting powder: faster mining.' },
  // Industrial
  { id: 'steam_power', name: 'Steam Power', era: 6, cost: 1000, req: ['physics', 'steel'], desc: 'Engines that work without muscle.' },
  { id: 'industrialization', name: 'Industrialization', era: 6, cost: 1100, req: ['steam_power'], desc: 'Factories and machinery.' },
  { id: 'railways', name: 'Railways', era: 6, cost: 1100, req: ['steam_power'], desc: 'Roads become rail: very fast travel.' },
  { id: 'refining', name: 'Oil Refining', era: 6, cost: 1200, req: ['chemistry', 'industrialization'], desc: 'Drill oil and refine fuel.' },
  { id: 'electricity', name: 'Electricity', era: 6, cost: 1400, req: ['physics', 'industrialization'], desc: 'Power plants, electric light, power tools.' },
  // Modern
  { id: 'combustion', name: 'Combustion Engine', era: 7, cost: 1600, req: ['refining'], desc: 'Engines burning fuel.' },
  { id: 'electronics', name: 'Electronics', era: 7, cost: 1800, req: ['electricity'], desc: 'Circuits, radio and electronics.' },
  { id: 'research_labs', name: 'Scientific Method', era: 7, cost: 1800, req: ['electricity', 'chemistry'], desc: 'Laboratories: huge research boost.' },
  { id: 'urban_planning', name: 'Urban Planning', era: 7, cost: 1800, req: ['industrialization', 'engineering'], desc: 'Modern concrete and apartment blocks.' },
  { id: 'antibiotics', name: 'Antibiotics', era: 7, cost: 2000, req: ['medicine', 'chemistry'], desc: 'Disease is rarely fatal anymore.' },
  { id: 'flight', name: 'Flight', era: 7, cost: 2200, req: ['combustion', 'physics'], desc: 'Powered flight.' },
  // Information
  { id: 'computing', name: 'Computing', era: 8, cost: 3000, req: ['electronics', 'research_labs'], desc: 'Computers and data centres.' },
  { id: 'skyscrapers', name: 'Skyscrapers', era: 8, cost: 2800, req: ['urban_planning', 'steel'], desc: 'Towers of steel and glass.' },
  { id: 'internet', name: 'The Internet', era: 8, cost: 3800, req: ['computing'], desc: 'Global knowledge network: +30% research.' },
  { id: 'robotics', name: 'Robotics', era: 8, cost: 4200, req: ['computing', 'industrialization'], desc: 'Automation: everyone works faster.' },
  // Space
  { id: 'rocketry', name: 'Rocketry', era: 9, cost: 5200, req: ['flight', 'computing'], desc: 'Build a launch pad and reach orbit.' },
]

/** Research costs are scaled so a civilisation's rise takes hours, not minutes. */
export const RESEARCH_SCALE = 3.2
export const TECHS: TechDef[] = RAW_TECHS.map((t) => ({ ...t, cost: Math.round(t.cost * RESEARCH_SCALE) }))

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

/** Research speed multiplier contributed by known techs. */
export function techResearchBonus(known: Set<string>): number {
  let m = 1
  if (known.has('philosophy')) m += 0.1
  if (known.has('printing_press')) m += 0.2
  if (known.has('internet')) m += 0.3
  return m
}

/** Work-speed multiplier from tribe-wide technology. */
export function techWorkBonus(known: Set<string>): number {
  let m = 1
  if (known.has('robotics')) m += 0.4
  return m
}
