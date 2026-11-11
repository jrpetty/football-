// Machine, recipe, buildable and milestone definitions.
import type { MachineModelId } from '../models/machines';
import type { BuildPieceId } from '../models/buildpieces';
import type { PropId } from '../models/props';
import type { SkillId } from '../player/skills';

export interface MRecipe { id: string; machine: string[]; inp: Record<string, number>; out: [string, number]; time: number }
export const MRECIPES: MRecipe[] = [
  { id: 'smelt_iron', machine: ['smelter'], inp: { iron_ore: 1 }, out: ['iron_ingot', 1], time: 2 },
  { id: 'smelt_copper', machine: ['smelter'], inp: { copper_ore: 1 }, out: ['copper_ingot', 1], time: 2 },
  { id: 'smelt_scrap', machine: ['smelter'], inp: { scrap_metal: 2 }, out: ['iron_ingot', 1], time: 3 },
  { id: 'steel', machine: ['foundry'], inp: { iron_ore: 3, coal: 3 }, out: ['steel_ingot', 3], time: 4 },
  { id: 'steel_ingot2', machine: ['foundry'], inp: { iron_ingot: 2, coal: 2 }, out: ['steel_ingot', 2], time: 4 },
  { id: 'iron_plate', machine: ['constructor'], inp: { iron_ingot: 3 }, out: ['iron_plate', 2], time: 6 },
  { id: 'iron_rod', machine: ['constructor'], inp: { iron_ingot: 1 }, out: ['iron_rod', 1], time: 4 },
  { id: 'screws', machine: ['constructor'], inp: { iron_rod: 1 }, out: ['screws', 4], time: 6 },
  { id: 'wire', machine: ['constructor'], inp: { copper_ingot: 1 }, out: ['wire', 2], time: 4 },
  { id: 'cable', machine: ['constructor'], inp: { wire: 2 }, out: ['cable', 1], time: 2 },
  { id: 'concrete', machine: ['constructor'], inp: { stone: 3 }, out: ['concrete', 1], time: 4 },
  { id: 'nails_c', machine: ['constructor'], inp: { iron_rod: 1 }, out: ['nails', 10], time: 4 },
  { id: 'sheet_metal_c', machine: ['constructor'], inp: { iron_plate: 2 }, out: ['sheet_metal', 1], time: 6 },
  { id: 'reinforced_plate', machine: ['assembler'], inp: { iron_plate: 3, screws: 12 }, out: ['reinforced_plate', 1], time: 12 },
  { id: 'rotor', machine: ['assembler'], inp: { iron_rod: 5, screws: 25 }, out: ['rotor', 1], time: 15 },
  { id: 'circuit', machine: ['assembler'], inp: { wire: 6, iron_plate: 1 }, out: ['circuit', 1], time: 10 },
  { id: 'gunpowder', machine: ['assembler'], inp: { coal: 1, sulfur: 1 }, out: ['gunpowder', 2], time: 4 },
  { id: 'modular_frame', machine: ['assembler'], inp: { reinforced_plate: 2, iron_rod: 6 }, out: ['modular_frame', 1], time: 20 },
  { id: 'ammo_9mm_a', machine: ['assembler'], inp: { gunpowder: 1, iron_plate: 1 }, out: ['ammo_9mm', 20], time: 8 },
  { id: 'ammo_shells_a', machine: ['assembler'], inp: { gunpowder: 2, iron_plate: 1 }, out: ['ammo_shells', 8], time: 8 },
  { id: 'ammo_rifle_a', machine: ['assembler'], inp: { gunpowder: 2, steel_ingot: 1 }, out: ['ammo_rifle', 10], time: 8 },
  { id: 'radio_parts_a', machine: ['assembler'], inp: { circuit: 1, cable: 2 }, out: ['radio_parts', 1], time: 15 },
  { id: 'antibiotics', machine: ['lab'], inp: { sulfur: 2, coal: 1 }, out: ['antibiotics', 2], time: 12 },
  { id: 'bandage_lab', machine: ['lab'], inp: { wire: 2, coal: 1 }, out: ['bandage', 4], time: 8 },
  { id: 'serum', machine: ['lab'], inp: { zombie_tissue: 3, antibiotics: 1 }, out: ['serum', 1], time: 30 },
  { id: 'grow_carrot', machine: ['greenhouse'], inp: {}, out: ['carrot', 2], time: 30 },
  { id: 'grow_potato', machine: ['greenhouse'], inp: {}, out: ['potato', 2], time: 30 },
  { id: 'grow_corn', machine: ['greenhouse'], inp: {}, out: ['corn', 2], time: 30 },
];

export type MachineType = MachineModelId;
export interface MachineDef {
  type: MachineType; name: string; icon: string; desc: string; size: [number, number]; cost: Record<string, number>;
  power: number; tier: number; inputs: number[]; outputs: number[]; cat: 'production' | 'logistics' | 'power' | 'defense' | 'special';
  hp: number; heat: number; height: number; skill?: [SkillId, number];
}
// inputs/outputs: tile index along back/front edge
export const MACHINES: Record<string, MachineDef> = {
  hub: { type: 'hub', name: 'Survivor Outpost', icon: '🏚️', desc: 'Your HQ. Deliver parts here to complete milestones and unlock new technology. Defines your base.', size: [3, 3], cost: { plank: 10, nails: 20, scrap_metal: 8 }, power: 0, tier: 0, inputs: [1], outputs: [], cat: 'special', hp: 1500, heat: 0, height: 3.5 },
  miner: { type: 'miner', name: 'Miner Mk.1', icon: '⛏️', desc: 'Extracts ore from a deposit. 30/min × purity.', size: [3, 3], cost: { scrap_metal: 12, electronics: 3, nails: 20 }, power: 5, tier: 1, inputs: [], outputs: [1], cat: 'production', hp: 600, heat: 3, height: 6 },
  miner2: { type: 'miner2', name: 'Miner Mk.2', icon: '⛏️', desc: 'Extracts ore at 60/min × purity.', size: [3, 3], cost: { reinforced_plate: 8, rotor: 4, cable: 10 }, power: 12, tier: 4, inputs: [], outputs: [1], cat: 'production', hp: 900, heat: 4, height: 7 },
  smelter: { type: 'smelter', name: 'Smelter', icon: '🔥', desc: 'Smelts ore into ingots.', size: [3, 3], cost: { stone: 12, scrap_metal: 8 }, power: 4, tier: 1, inputs: [1], outputs: [1], cat: 'production', hp: 600, heat: 2, height: 5 },
  foundry: { type: 'foundry', name: 'Foundry', icon: '🏭', desc: 'Alloys iron and coal into steel.', size: [3, 3], cost: { iron_plate: 20, concrete: 20 }, power: 16, tier: 3, inputs: [0, 2], outputs: [1], cat: 'production', hp: 900, heat: 4, height: 6 },
  ['constructor' as string]: { type: 'constructor', name: 'Constructor', icon: '⚙️', desc: 'Crafts one input into parts: plates, rods, screws, wire, concrete, nails.', size: [3, 3], cost: { iron_ingot: 20, scrap_metal: 10 }, power: 4, tier: 2, inputs: [1], outputs: [1], cat: 'production', hp: 700, heat: 2, height: 4.5 },
  assembler: { type: 'assembler', name: 'Assembler', icon: '🦾', desc: 'Combines two inputs: reinforced plates, rotors, circuits, gunpowder, ammunition.', size: [3, 3], cost: { iron_plate: 20, screws: 60, wire: 20 }, power: 15, tier: 3, inputs: [0, 2], outputs: [1], cat: 'production', hp: 800, heat: 3, height: 5 },
  lab: { type: 'lab', name: 'Medical Lab', icon: '🧪', desc: 'Synthesises antibiotics, bandages — and from infected tissue, the cure.', size: [3, 3], cost: { modular_frame: 6, circuit: 20, steel_ingot: 20 }, power: 30, tier: 5, inputs: [0, 2], outputs: [1], cat: 'production', hp: 800, heat: 1, height: 4.5 },
  greenhouse: { type: 'greenhouse', name: 'Hydroponic Greenhouse', icon: '🌿', desc: 'Grows vegetables automatically. Food security.', size: [3, 3], cost: { reinforced_plate: 4, iron_plate: 10, cable: 5 }, power: 8, tier: 4, inputs: [1], outputs: [1], cat: 'production', hp: 500, heat: 0, height: 4 },
  storage: { type: 'storage', name: 'Storage Container', icon: '📦', desc: 'Stores 200 kg. Belt in at back, out at front.', size: [1, 1], cost: { scrap_metal: 6, plank: 4 }, power: 0, tier: 1, inputs: [0], outputs: [0], cat: 'logistics', hp: 500, heat: 0, height: 2 },
  belt: { type: 'belt', name: 'Conveyor Belt Mk.1', icon: '➡️', desc: '60 items/min. Click & drag to lay a line.', size: [1, 1], cost: { scrap_metal: 1 }, power: 0, tier: 1, inputs: [0], outputs: [0], cat: 'logistics', hp: 150, heat: 0, height: 0.7 },
  belt2: { type: 'belt2', name: 'Conveyor Belt Mk.2', icon: '⏩', desc: '120 items/min.', size: [1, 1], cost: { iron_plate: 1, screws: 2 }, power: 0, tier: 4, inputs: [0], outputs: [0], cat: 'logistics', hp: 250, heat: 0, height: 0.7 },
  splitter: { type: 'splitter', name: 'Splitter', icon: '🔀', desc: 'Splits one belt into up to three.', size: [1, 1], cost: { iron_ingot: 3 }, power: 0, tier: 2, inputs: [0], outputs: [0], cat: 'logistics', hp: 300, heat: 0, height: 1.2 },
  merger: { type: 'merger', name: 'Merger', icon: '🔁', desc: 'Merges up to three belts into one.', size: [1, 1], cost: { iron_ingot: 3 }, power: 0, tier: 2, inputs: [0], outputs: [0], cat: 'logistics', hp: 300, heat: 0, height: 1.2 },
  biomass: { type: 'biomass', name: 'Biomass Burner', icon: '🪵', desc: '10 MW. Hand-feed logs, planks or biomass.', size: [2, 2], cost: { scrap_metal: 8, stone: 6 }, power: -10, tier: 1, inputs: [], outputs: [], cat: 'power', hp: 500, heat: 1, height: 3 },
  coal_gen: { type: 'coal_gen', name: 'Coal Generator', icon: '🏭', desc: '50 MW. Burns coal from a belt or by hand.', size: [3, 3], cost: { iron_ingot: 30, copper_ingot: 20 }, power: -50, tier: 2, inputs: [1], outputs: [], cat: 'power', hp: 900, heat: 5, height: 6 },
  solar: { type: 'solar', name: 'Solar Array', icon: '☀️', desc: 'Up to 8 MW in daylight. Silent.', size: [2, 2], cost: { iron_plate: 6, wire: 20, electronics: 4 }, power: -8, tier: 3, inputs: [], outputs: [], cat: 'power', hp: 300, heat: 0, height: 3 },
  wind_turbine: { type: 'wind_turbine', name: 'Wind Turbine', icon: '🌬️', desc: '5–25 MW depending on wind.', size: [1, 1], cost: { rotor: 6, iron_plate: 20, cable: 10 }, power: -15, tier: 4, inputs: [], outputs: [], cat: 'power', hp: 400, heat: 0, height: 16 },
  battery: { type: 'battery', name: 'Battery Bank', icon: '🔋', desc: 'Stores 4000 MJ of surplus power.', size: [2, 2], cost: { iron_plate: 10, wire: 40, cable: 10 }, power: 0, tier: 3, inputs: [], outputs: [], cat: 'power', hp: 400, heat: 0, height: 2.5 },
  power_pole: { type: 'power_pole', name: 'Power Pole', icon: '🗼', desc: 'Connects machines within 10 m and poles within 16 m.', size: [1, 1], cost: { plank: 2, scrap_metal: 1 }, power: 0, tier: 1, inputs: [], outputs: [], cat: 'power', hp: 200, heat: 0, height: 6 },
  floodlight: { type: 'floodlight', name: 'Floodlight', icon: '💡', desc: 'Lights your base at night. 2 MW.', size: [1, 1], cost: { scrap_metal: 4, electronics: 2 }, power: 2, tier: 1, inputs: [], outputs: [], cat: 'defense', hp: 250, heat: 0, height: 7 },
  turret: { type: 'turret', name: 'Auto Turret', icon: '🎯', desc: 'Shoots zombies within 28 m. Feed ammo by hand or belt. 6 MW. Loud.', size: [1, 1], cost: { iron_plate: 10, screws: 40, wire: 15, electronics: 4 }, power: 6, tier: 3, inputs: [0], outputs: [], cat: 'defense', hp: 500, heat: 2, height: 2.2 },
  electric_fence: { type: 'electric_fence', name: 'Electric Fence', icon: '⚡', desc: 'Blocks and electrocutes zombies. 3 MW.', size: [1, 1], cost: { copper_ingot: 4, iron_ingot: 2 }, power: 3, tier: 2, inputs: [], outputs: [], cat: 'defense', hp: 500, heat: 0, height: 2.2 },
  spike_trap: { type: 'spike_trap', name: 'Spike Trap', icon: '🔺', desc: 'Damages and slows zombies that walk over it.', size: [1, 1], cost: { plank: 3, nails: 6 }, power: 0, tier: 0, inputs: [], outputs: [], cat: 'defense', hp: 200, heat: 0, height: 0.6 },
  radio_tower: { type: 'radio_tower', name: 'Radio Tower', icon: '📡', desc: 'Contacts other survivors: daily supply drops. Needed to broadcast the cure. 20 MW.', size: [2, 2], cost: { reinforced_plate: 10, circuit: 10, cable: 20, radio_parts: 2 }, power: 20, tier: 4, inputs: [], outputs: [], cat: 'special', hp: 800, heat: 1, height: 25 },
  uplink: { type: 'uplink', name: 'Grid Uplink', icon: '🔌', desc: 'Restores power to the old town grid. The lights come back on. 40 MW.', size: [3, 3], cost: { modular_frame: 10, cable: 60, steel_ingot: 40 }, power: 40, tier: 5, inputs: [], outputs: [], cat: 'special', hp: 1200, heat: 2, height: 9 },
};

// ---- player construction pieces (carpentry) & stations
export type PieceKind = 'foundation' | 'wall' | 'door_wall' | 'window_wall' | 'stairs' | 'roof' | 'gate' | 'tower' | 'station';
export interface PieceDef { id: string; name: string; icon: string; desc: string; kind: PieceKind; model?: BuildPieceId; prop?: PropId; cost: Record<string, number>; tier: number; skill?: [SkillId, number]; hp: number; station?: string; size?: [number, number] }
export const PIECES: Record<string, PieceDef> = {
  wood_foundation: { id: 'wood_foundation', name: 'Wooden Foundation', icon: '🟫', desc: 'A raised 2×2 m floor.', kind: 'foundation', model: 'wood_foundation', cost: { plank: 4, nails: 6 }, tier: 0, hp: 400 },
  wood_wall: { id: 'wood_wall', name: 'Wooden Wall', icon: '🧱', desc: 'Snaps to tile edges.', kind: 'wall', model: 'wood_wall', cost: { plank: 3, nails: 4 }, tier: 0, hp: 350 },
  wood_wall_window: { id: 'wood_wall_window', name: 'Wooden Window Wall', icon: '🪟', desc: 'Wall with a window.', kind: 'window_wall', model: 'wood_wall_window', cost: { plank: 3, nails: 4 }, tier: 0, hp: 300 },
  wood_wall_door: { id: 'wood_wall_door', name: 'Wooden Door', icon: '🚪', desc: 'Wall with an opening door.', kind: 'door_wall', model: 'wood_wall_door', cost: { plank: 5, nails: 8 }, tier: 0, hp: 300 },
  wood_stairs: { id: 'wood_stairs', name: 'Wooden Stairs', icon: '🪜', desc: 'Climb to the roof.', kind: 'stairs', model: 'wood_stairs', cost: { plank: 4, nails: 6 }, tier: 0, skill: ['carpentry', 2], hp: 300 },
  wood_roof: { id: 'wood_roof', name: 'Wooden Roof', icon: '⬛', desc: 'Roof / upper floor at wall height.', kind: 'roof', model: 'wood_roof', cost: { plank: 3, nails: 4 }, tier: 0, skill: ['carpentry', 1], hp: 300 },
  metal_foundation: { id: 'metal_foundation', name: 'Metal Foundation', icon: '⬜', desc: 'Sturdy metal floor.', kind: 'foundation', model: 'metal_foundation', cost: { sheet_metal: 2, nails: 4 }, tier: 2, hp: 900 },
  metal_wall: { id: 'metal_wall', name: 'Metal Wall', icon: '🔲', desc: 'Corrugated steel wall.', kind: 'wall', model: 'metal_wall', cost: { sheet_metal: 2, nails: 6 }, tier: 2, skill: ['carpentry', 2], hp: 900 },
  metal_wall_door: { id: 'metal_wall_door', name: 'Metal Door', icon: '🚪', desc: 'Steel door.', kind: 'door_wall', model: 'metal_wall_door', cost: { sheet_metal: 3, nails: 8 }, tier: 2, skill: ['carpentry', 2], hp: 800 },
  concrete_wall: { id: 'concrete_wall', name: 'Concrete Wall', icon: '🧊', desc: 'Nearly unbreakable.', kind: 'wall', model: 'concrete_wall', cost: { concrete: 4 }, tier: 3, skill: ['carpentry', 3], hp: 2200 },
  gate: { id: 'gate', name: 'Gate', icon: '⛩️', desc: '4 m double gate across two tile edges.', kind: 'gate', model: 'gate', cost: { plank: 8, nails: 10, sheet_metal: 2 }, tier: 2, skill: ['carpentry', 2], hp: 900 },
  watchtower: { id: 'watchtower', name: 'Watchtower', icon: '🗼', desc: '5 m lookout platform. Climb the ladder (E).', kind: 'tower', model: 'watchtower', cost: { plank: 12, nails: 16 }, tier: 2, skill: ['carpentry', 3], hp: 600 },
  workbench: { id: 'workbench', name: 'Workbench', icon: '🛠️', desc: 'Crafting station for tools, metalwork and ammo.', kind: 'station', prop: 'workbench', cost: { plank: 6, nails: 8 }, tier: 0, hp: 300, station: 'workbench' },
  campfire: { id: 'campfire', name: 'Campfire', icon: '🔥', desc: 'Cook, boil water, warm up. Needs fuel; light with a lighter.', kind: 'station', prop: 'campfire', cost: { log: 2, stone: 3 }, tier: 0, hp: 150, station: 'campfire' },
  crate: { id: 'crate', name: 'Wooden Crate', icon: '📦', desc: 'Storage, 60 kg.', kind: 'station', prop: 'wood_crate_storage', cost: { plank: 4, nails: 4 }, tier: 0, hp: 200, station: 'crate' },
  bed: { id: 'bed', name: 'Bedroll', icon: '🛏️', desc: 'Sleep to pass the night and recover. Sets your respawn... no — death is permanent.', kind: 'station', prop: 'sleeping_bag', cost: { rag: 6 }, tier: 0, hp: 100, station: 'bed' },
  rain_collector: { id: 'rain_collector', name: 'Rain Collector', icon: '🛢️', desc: 'Collects clean rainwater. Fill bottles (E).', kind: 'station', prop: 'rain_collector', cost: { plank: 4, nails: 4, rag: 2 }, tier: 0, hp: 150, station: 'rain' },
  farm_plot: { id: 'farm_plot', name: 'Farm Plot', icon: '🌱', desc: 'Plant seeds; harvest in a few days. Rain waters it.', kind: 'station', model: 'farm_plot', cost: { plank: 2, nails: 2 }, tier: 0, hp: 100, station: 'farm' },
};

export interface Milestone { id: string; name: string; desc: string; cost: Record<string, number>; unlocks: string[] }
export const MILESTONES: Milestone[] = [
  { id: 'm1', name: 'Foothold', desc: 'Scavenge the town to bootstrap power and smelting.', cost: { plank: 30, nails: 60, scrap_metal: 20, electronics: 6 }, unlocks: ['biomass', 'power_pole', 'miner', 'smelter', 'belt', 'storage', 'floodlight'] },
  { id: 'm2', name: 'Industry', desc: 'Refined metal opens real manufacturing.', cost: { iron_ingot: 60, copper_ingot: 30, sheet_metal: 6 }, unlocks: ['constructor', 'coal_gen', 'splitter', 'merger', 'electric_fence', 'metal_foundation', 'metal_wall', 'metal_wall_door', 'gate', 'watchtower'] },
  { id: 'm3', name: 'Fortify', desc: 'Automated defences and complex parts.', cost: { iron_plate: 80, screws: 200, wire: 80, cable: 30 }, unlocks: ['assembler', 'turret', 'solar', 'foundry', 'battery', 'concrete_wall'] },
  { id: 'm4', name: 'Automation', desc: 'Scale up. Reach out to other survivors.', cost: { reinforced_plate: 30, rotor: 20, circuit: 20, concrete: 60 }, unlocks: ['belt2', 'miner2', 'wind_turbine', 'greenhouse', 'radio_tower'] },
  { id: 'm5', name: 'Rekindle', desc: 'Restore the grid. Begin the search for a cure.', cost: { modular_frame: 20, steel_ingot: 80, circuit: 50, radio_parts: 4 }, unlocks: ['lab', 'uplink'] },
  { id: 'm6', name: 'The Cure', desc: 'Synthesise the antiviral serum and broadcast the formula to the world.', cost: { serum: 10 }, unlocks: [] },
];
