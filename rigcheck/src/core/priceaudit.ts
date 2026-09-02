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
