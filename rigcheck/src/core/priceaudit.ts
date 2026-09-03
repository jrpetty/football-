/**
 * What kind of price a part has on file, and what to do about it.
 *
 * The answer to "is this a new price or a used one, and can I trust it" for
 * every priced part, in one place, so the audit report and any screen agree.
 */
import { RESALE_ONLY_AFTER_YEARS } from '../ui/pricing.ts';

export type PriceStatus =
  | 'sourced'                 // at least one market observation on record
  | 'recalled-used'           // a recalled used figure, undated — check sold listings
  | 'recalled-new-old-part'   // a recalled NEW figure on a part old enough that new is not what it sells for
  | 'recalled-new'            // a recalled new figure on a recent part — plausible, unsourced
  | 'none';

export interface PricePartInput {
  launchYear?: number | null;
  seedNew?: number | null;
  seedUsed?: number | null;
  observedNew?: boolean;
  observedUsed?: boolean;
}

export function priceStatus(p: PricePartInput, today = new Date()): PriceStatus {
  if (p.observedNew || p.observedUsed) return 'sourced';
  if (p.seedUsed != null) return 'recalled-used';
  if (p.seedNew != null) {
    const old = p.launchYear != null && today.getUTCFullYear() - p.launchYear >= RESALE_ONLY_AFTER_YEARS;
    return old ? 'recalled-new-old-part' : 'recalled-new';
  }
  return 'none';
}

/** What the status means for the operator, in one line. */
export const STATUS_ADVICE: Record<PriceStatus, string> = {
  sourced: 'sourced from a market observation — keep it fresh with a new snapshot',
  'recalled-used': 'a recalled used figure with no date behind it — read sold listings and record what it actually goes for',
  'recalled-new-old-part': 'a recalled NEW figure on an old part — that is a launch-era number, and the part sells used; record a resale price',
  'recalled-new': 'a recalled new figure on a recent part — plausible, but nobody looked it up; a retail price with a date would replace it',
  none: 'no price at all — the planner cannot consider it and the posts cannot quote it',
};

/** Order in which the operator should spend their eBay time. Lower first. */
export const STATUS_PRIORITY: Record<PriceStatus, number> = {
  none: 0, 'recalled-new-old-part': 1, 'recalled-used': 2, 'recalled-new': 3, sourced: 4,
};

/**
 * A seed figure its own sibling has overtaken.
 *
 * When one class in a family is repriced from real listings and the others are
 * not, the family becomes internally inconsistent in a way no single figure
 * reveals: a DDR4 build sitting next to a DDR5 one looks cheap because its
 * memory line is six months older, not because DDR4 is cheap. This finds those
 * cases — a category where something was sourced and moved by at least
 * `threshold`, leaving its unsourced siblings on the old figure — so the audit
 * can rank them and the published copy can say the multiple out loud.
 *
 * Deliberately category-level and deliberately blunt. It does not claim the
 * siblings moved by the same factor; it claims nobody has checked, and states
 * how far the one that was checked travelled.
 */
export interface FamilyDrift {
  /** The allowance category: memory, psu, storage, motherboard, case, cooler. */
  category: string;
  /** The sourced key that moved, and by how much against its own seed. */
  sourcedKey: string;
  seedPrice: number;
  sourcedPrice: number;
  /** sourcedPrice / seedPrice, rounded to one decimal. */
  factor: number;
  /** Sibling keys in the same category still on a recalled figure. */
  unsourced: string[];
}

export function familyDrift(
  allowances: { key: string; price: number }[],
  observed: { partId: string; price: number }[],
  threshold = 1.5,
): FamilyDrift[] {
  const seed = new Map(allowances.map((a) => [a.key, a.price]));
  const obs = new Map(observed.map((o) => [o.partId, o.price]));
  const byCategory = new Map<string, string[]>();
  for (const a of allowances) {
    const cat = a.key.split('.')[0];
    (byCategory.get(cat) ?? byCategory.set(cat, []).get(cat)!).push(a.key);
  }
  const out: FamilyDrift[] = [];
  for (const [category, keys] of byCategory) {
    const unsourced = keys.filter((k) => !obs.has(k));
    if (!unsourced.length) continue;
    // The sourced sibling that moved furthest is the one worth naming.
    let worst: FamilyDrift | null = null;
    for (const k of keys) {
      const o = obs.get(k), sd = seed.get(k);
      if (o == null || sd == null || sd <= 0) continue;
      const factor = Math.round((o / sd) * 10) / 10;
      if (factor < threshold) continue;
      if (!worst || factor > worst.factor) worst = { category, sourcedKey: k, seedPrice: sd, sourcedPrice: o, factor, unsourced };
    }
    if (worst) out.push(worst);
  }
  return out.sort((a, b) => b.factor - a.factor);
}

/** The sentence published copy must carry while a family is inconsistent. */
export function driftCaveat(d: FamilyDrift, label: (key: string) => string): string {
  return `${label(d.sourcedKey)} is priced from current listings at ${d.factor}x its recalled figure. ` +
    `The other ${d.category} figures here are still the recalled ones and nobody has checked them, so a build using one is very likely cheaper on paper than in a shop.`;
}
