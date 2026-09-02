/**
 * The parts the catalogue does not model individually — motherboards, memory,
 * storage, power supplies, cases, coolers — priced as class allowances, and
 * how a real observation replaces one.
 *
 * An allowance has a key that names the class exactly as the planner looks it
 * up: `motherboard.AM4.budget`, `memory.DDR5.32`, `storage.nvme-gen4.1000`,
 * `psu.650`, `case.good`, `cooler.budget-tower`. A price recorded against that
 * key in data/prices-observed/ overrides the recalled figure for the whole
 * class, which is the right grain: the planner never picks a specific board,
 * it budgets "a competent AM4 board", and that is what the observation prices.
 */
import type { ComponentPrices, BomLine } from './planner.ts';

export interface AllowanceKey { key: string; category: BomLine['category']; label: string; price: number }

/** Every priced allowance, with the key an observation must use. */
export function allowanceKeys(comp: ComponentPrices): AllowanceKey[] {
  const out: AllowanceKey[] = [];
  for (const [socket, mb] of Object.entries(comp.motherboard)) {
    for (const tier of ['budget', 'mid', 'high'] as const) {
      const price = (mb as Record<string, unknown>)[tier];
      if (typeof price === 'number') out.push({ key: `motherboard.${socket}.${tier}`, category: 'Motherboard', label: `${socket} motherboard, ${tier} tier (${mb.chipsets})`, price });
    }
  }
  for (const [type, row] of Object.entries(comp.memory)) {
    for (const [gb, price] of Object.entries(row as Record<string, unknown>)) {
      if (typeof price === 'number') out.push({ key: `memory.${type}.${gb}`, category: 'Memory', label: `${gb}GB ${type} kit, 2 sticks`, price });
    }
  }
  for (const [kind, row] of Object.entries(comp.storage)) {
    if (!row || typeof row !== 'object') continue;
    for (const [gb, price] of Object.entries(row as Record<string, unknown>)) {
      if (typeof price === 'number') out.push({ key: `storage.${kind}.${gb}`, category: 'Storage', label: `${Number(gb) >= 1000 ? `${Number(gb) / 1000}TB` : `${gb}GB`} ${kind} drive`, price });
    }
  }
  for (const p of comp.psu) out.push({ key: `psu.${p.watts}`, category: 'PSU', label: `${p.watts}W power supply, ${p.tier}`, price: p.price });
  for (const [tier, c] of Object.entries(comp.case)) out.push({ key: `case.${tier}`, category: 'Case', label: `case, ${c.label}`, price: c.price });
  for (const [tier, c] of Object.entries(comp.cooler)) if (c.price > 0) out.push({ key: `cooler.${tier}`, category: 'Cooler', label: `cooler, ${c.label}`, price: c.price });
  return out;
}

export const isAllowanceKey = (key: string, comp: ComponentPrices): boolean => allowanceKeys(comp).some((k) => k.key === key);

/**
 * The allowance table with observed prices written over the recalled ones.
 * The planner prices new builds, so only new-condition observations apply
 * unless asked otherwise. Unknown keys are ignored: the importer already
 * refused them.
 */
export function overlayComponents(
  comp: ComponentPrices,
  observed: { partId: string; condition: 'new' | 'used'; price: number }[],
  condition: 'new' | 'used' = 'new',
): ComponentPrices {
  const out = JSON.parse(JSON.stringify(comp)) as ComponentPrices;
  for (const o of observed) {
    if (o.condition !== condition) continue;
    const [cat, a, b] = o.partId.split('.');
    if (cat === 'motherboard' && out.motherboard[a] && b in out.motherboard[a]) (out.motherboard[a] as unknown as Record<string, number>)[b] = o.price;
    else if (cat === 'memory' && out.memory[a] && b in out.memory[a]) (out.memory[a] as unknown as Record<string, number>)[b] = o.price;
    else if (cat === 'storage' && out.storage[a] && typeof out.storage[a] === 'object' && b in (out.storage[a] as object)) (out.storage[a] as unknown as Record<string, number>)[b] = o.price;
    else if (cat === 'psu') { const p = out.psu.find((x) => String(x.watts) === a); if (p) p.price = o.price; }
    else if (cat === 'case' && out.case[a]) out.case[a].price = o.price;
    else if (cat === 'cooler' && out.cooler[a]) out.cooler[a].price = o.price;
  }
  return out;
}
