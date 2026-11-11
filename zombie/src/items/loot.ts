import { RNG } from '../core/util';
import { Inventory } from './inventory';
import { item } from './items';

export type ContainerKind =
  'fridge' | 'counter' | 'wardrobe' | 'dresser' | 'medicine' | 'toolbox' | 'garage' | 'shop_shelf' | 'gun_locker' |
  'police_locker' | 'crate' | 'car_trunk' | 'dumpster' | 'desk' | 'hospital' | 'corpse' | 'storage' | 'cabinet_filing' | 'bookshelf' | 'pharmacy';

type Entry = [string, number, number?, number?]; // id, weight, min, max
const T: Record<ContainerKind, { rolls: [number, number]; cap: number; entries: Entry[] }> = {
  fridge: { rolls: [1, 4], cap: 30, entries: [['water_bottle', 6], ['soda', 6], ['apple', 5], ['bread', 3], ['raw_meat', 4], ['rotten_food', 4], ['carrot', 2], ['potato', 2], ['corn', 2]] },
  counter: { rolls: [1, 4], cap: 20, entries: [['canned_beans', 6], ['canned_soup', 6], ['chips', 4], ['knife', 3], ['rag', 5], ['lighter', 2], ['empty_bottle', 4], ['chocolate', 3], ['battery', 2], ['duct_tape', 1], ['disinfectant', 1], ['car_keys', 1.2]] },
  wardrobe: { rolls: [1, 3], cap: 25, entries: [['hoodie', 5], ['jeans', 5], ['leather_jacket', 2], ['raincoat', 2], ['beanie', 3], ['sneakers', 3], ['boots', 2], ['school_bag', 2], ['duffel', 1], ['cargo_pants', 2], ['gloves', 1], ['rag', 3]] },
  dresser: { rolls: [1, 3], cap: 15, entries: [['rag', 5], ['battery', 3], ['painkillers', 2], ['book_carpentry', 1], ['book_scavenging', 1], ['book_firstaid', 1], ['chocolate', 2], ['beanie', 2], ['lighter', 2], ['flashlight', 1], ['car_keys', 1]] },
  medicine: { rolls: [1, 3], cap: 6, entries: [['bandage', 5], ['disinfectant', 4], ['painkillers', 5], ['antibiotics', 1], ['rag', 3], ['suture_kit', 1]] },
  toolbox: { rolls: [1, 3], cap: 20, entries: [['hammer', 3], ['saw', 3], ['screwdriver', 3], ['wrench', 2], ['nails', 6, 10, 40], ['duct_tape', 3], ['electronics', 2, 1, 3], ['crowbar', 1], ['pickaxe', 1]] },
  garage: { rolls: [2, 4], cap: 60, entries: [['plank', 5, 2, 8], ['nails', 5, 10, 30], ['gas_can', 1], ['gas_can_empty', 1.5], ['scrap_metal', 4, 1, 5], ['rope', 2], ['axe', 1], ['pipe', 2], ['hammer', 1], ['seeds_carrot', 1, 2, 6], ['seeds_potato', 1, 2, 6], ['tire_iron', 0]] },
  shop_shelf: { rolls: [2, 5], cap: 40, entries: [['canned_beans', 6, 1, 3], ['canned_soup', 6, 1, 3], ['chips', 6, 1, 3], ['chocolate', 4, 1, 3], ['soda', 5, 1, 3], ['water_bottle', 6, 1, 3], ['battery', 3, 1, 4], ['bread', 3], ['rotten_food', 2], ['seeds_corn', 1, 2, 6], ['seeds_carrot', 1, 2, 6]] },
  gun_locker: { rolls: [1, 3], cap: 40, entries: [['pistol', 3], ['shotgun', 2], ['rifle', 1], ['ammo_9mm', 5, 8, 30], ['ammo_shells', 4, 4, 12], ['ammo_rifle', 2, 3, 10], ['book_firearms', 1]] },
  police_locker: { rolls: [1, 3], cap: 30, entries: [['police_vest', 3], ['helmet', 2], ['pistol', 2], ['ammo_9mm', 5, 6, 20], ['flashlight', 3], ['battery', 2, 1, 4], ['bandage', 2], ['boots', 2]] },
  crate: { rolls: [1, 4], cap: 50, entries: [['scrap_metal', 5, 1, 6], ['nails', 4, 10, 30], ['electronics', 3, 1, 4], ['rope', 3], ['plank', 3, 1, 5], ['duct_tape', 2], ['sheet_metal', 1, 1, 2], ['radio_parts', 0.4]] },
  car_trunk: { rolls: [0, 3], cap: 40, entries: [['gas_can', 2], ['scrap_metal', 3, 1, 3], ['water_bottle', 3], ['duffel', 1], ['wrench', 2], ['rag', 3], ['chips', 2], ['flashlight', 1], ['electronics', 2, 1, 2], ['ammo_9mm', 0.5, 4, 12], ['gas_can_empty', 1.5]] },
  dumpster: { rolls: [1, 3], cap: 60, entries: [['rag', 5], ['scrap_metal', 4, 1, 3], ['empty_bottle', 5], ['rotten_food', 5], ['electronics', 2], ['plank', 2, 1, 3], ['pipe', 1], ['nails', 2, 4, 12]] },
  desk: { rolls: [1, 3], cap: 15, entries: [['electronics', 4, 1, 3], ['battery', 3, 1, 3], ['book_engineering', 1], ['book_scavenging', 1], ['chocolate', 2], ['screwdriver', 2], ['painkillers', 1]] },
  hospital: { rolls: [2, 5], cap: 30, entries: [['bandage', 6, 1, 4], ['disinfectant', 5], ['painkillers', 5, 1, 2], ['antibiotics', 3], ['suture_kit', 3], ['splint', 3], ['book_firstaid', 1], ['rag', 3]] },
  pharmacy: { rolls: [2, 4], cap: 20, entries: [['painkillers', 6, 1, 3], ['antibiotics', 3], ['bandage', 5, 1, 4], ['disinfectant', 4], ['chocolate', 2], ['water_bottle', 2]] },
  corpse: { rolls: [0, 2], cap: 20, entries: [['rag', 5], ['chips', 1], ['lighter', 1], ['battery', 1], ['painkillers', 1], ['ammo_9mm', 0.5, 2, 8], ['zombie_tissue', 2.5], ['water_bottle', 1], ['knife', 0.4]] },
  storage: { rolls: [0, 0], cap: 200, entries: [] },
  cabinet_filing: { rolls: [0, 2], cap: 20, entries: [['book_engineering', 1], ['electronics', 2], ['battery', 1], ['book_carpentry', 1]] },
  bookshelf: { rolls: [0, 2], cap: 20, entries: [['book_carpentry', 2], ['book_engineering', 2], ['book_firstaid', 2], ['book_firearms', 1], ['book_scavenging', 2]] },
};

export const CONTAINER_LABEL: Record<ContainerKind, string> = {
  fridge: 'Fridge', counter: 'Kitchen Cabinet', wardrobe: 'Wardrobe', dresser: 'Dresser', medicine: 'Medicine Cabinet',
  toolbox: 'Tool Box', garage: 'Garage Shelf', shop_shelf: 'Store Shelf', gun_locker: 'Gun Locker', police_locker: 'Police Locker',
  crate: 'Crate', car_trunk: 'Car Trunk', dumpster: 'Dumpster', desk: 'Desk', hospital: 'Medical Supplies', corpse: 'Corpse',
  storage: 'Storage', cabinet_filing: 'Filing Cabinet', bookshelf: 'Bookshelf', pharmacy: 'Pharmacy Shelf',
};
export function containerCapacity(kind: ContainerKind) { return T[kind].cap; }

/** Fill a container with loot. `luck` 0..1+ from the scavenging skill & lucky trait. */
export function rollLoot(kind: ContainerKind, rng: RNG, luck: number, inv: Inventory) {
  const t = T[kind];
  const entries = t.entries.filter(e => e[1] > 0 && item(e[0]).name !== e[0]);
  if (!entries.length) return;
  let rolls = rng.int(t.rolls[0], t.rolls[1]);
  if (rng.chance(luck * 0.35)) rolls++;
  // looted-world feel: some containers are already empty
  if (rng.chance(0.18 - luck * 0.08)) rolls = 0;
  for (let i = 0; i < rolls; i++) {
    const e = rng.weighted(entries.map(x => [x, x[1]] as [Entry, number]));
    const n = e[2] !== undefined ? rng.int(e[2], e[3] ?? e[2]) : 1;
    const d = item(e[0]);
    const proto: { dur?: number; ammo?: number; age?: number } = {};
    if (d.weapon) { proto.dur = Math.round(d.weapon.durability * rng.range(0.35, 1)); if (d.weapon.ammo) proto.ammo = rng.int(0, Math.floor((d.weapon.mag ?? 0) * 0.6)); }
    if (d.spoilDays) proto.age = rng.range(0, d.spoilDays * 0.8);
    inv.add(e[0], n, proto);
  }
}
