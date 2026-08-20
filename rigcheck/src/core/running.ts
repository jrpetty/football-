/**
 * What the machine costs to own, rather than to buy.
 *
 * A build's price is the number everyone compares. It is not the number that
 * decides what the machine costs: a 450W gaming rig run four hours a day is
 * roughly 160 kWh a year, which at UK prices is a meaningful fraction of a
 * mid-range graphics card every three years. Two builds within 50 of each
 * other on price can be 200 apart over their life, and nothing in this tool
 * said so until now.
 *
 * Two things this gets right that a back-of-envelope calculation usually does
 * not:
 *
 * **The wall draw is not the component draw.** A power supply is not free. At
 * 400W of components, an 80+ Bronze unit pulls about 470W from the wall and a
 * Titanium one about 425W — a 45W difference, permanently, for as long as the
 * machine is on. That is the entire reason efficiency ratings exist and it is
 * routinely left out of "how much does my PC cost to run" arithmetic.
 *
 * **Efficiency depends on load, not just on the badge.** The 80 Plus tiers
 * specify efficiency at 20%, 50% and 100% of rated output, and the curve sags
 * at both ends. An oversized supply spends its life at 15% load, where it is
 * measurably worse than a right-sized one — which is the honest counterweight
 * to this project's own advice to buy headroom for transients.
 */

export type PsuTier = 'none' | 'bronze' | 'silver' | 'gold' | 'platinum' | 'titanium';

/**
 * 80 Plus efficiency at 20%, 50% and 100% load, 115V internal ratings.
 *
 * `none` is a plausible figure for an unrated unit rather than a specified
 * one — there is no standard to quote, which is rather the point of buying a
 * rated supply.
 */
export const PSU_EFFICIENCY: Record<PsuTier, { at20: number; at50: number; at100: number; label: string }> = {
  none: { at20: 0.72, at50: 0.76, at100: 0.73, label: 'unrated' },
  bronze: { at20: 0.82, at50: 0.85, at100: 0.82, label: '80+ Bronze' },
  silver: { at20: 0.85, at50: 0.88, at100: 0.85, label: '80+ Silver' },
  gold: { at20: 0.87, at50: 0.9, at100: 0.87, label: '80+ Gold' },
  platinum: { at20: 0.89, at50: 0.92, at100: 0.89, label: '80+ Platinum' },
  titanium: { at20: 0.9, at50: 0.94, at100: 0.91, label: '80+ Titanium' },
};

/**
 * Efficiency at an arbitrary load, interpolated across the three specified
 * points and extrapolated conservatively below 20%.
 *
 * Below 20% load the standard says nothing and real units fall off sharply, so
 * the curve is taken down rather than held flat — holding it flat would make a
 * wildly oversized supply look free, which is the opposite of true.
 */
export function psuEfficiency(tier: PsuTier, loadFraction: number): number {
  const c = PSU_EFFICIENCY[tier] ?? PSU_EFFICIENCY.gold;
  const f = Math.max(0.01, Math.min(1.2, loadFraction));
  if (f <= 0.2) {
    // Linear fall below the lowest specified point, reaching roughly 10 points
    // down at near-zero load. Real curves are worse than this; it is deliberately
    // not a cliff, because the exact shape varies by unit and inventing a sharp
    // one would overstate a difference nobody can measure at home.
    const drop = (0.2 - f) / 0.2;
    return Math.max(0.55, c.at20 - drop * 0.1);
  }
  if (f <= 0.5) return c.at20 + ((f - 0.2) / 0.3) * (c.at50 - c.at20);
  if (f <= 1.0) return c.at50 + ((f - 0.5) / 0.5) * (c.at100 - c.at50);
  // Above rated output a supply is out of spec; hold at the 100% figure rather
  // than pretending to model a condition it is not designed for.
  return c.at100;
}

export interface UsageProfile {
  /** Hours a week under a gaming load. */
  gamingHoursPerWeek: number;
  /** Hours a week powered on but idle or doing light work. */
  idleHoursPerWeek: number;
  /** Electricity price in pence per kWh, including standing-charge-free unit rate. */
  pencePerKwh: number;
  /** How long the machine is expected to be kept, in years. */
  ownershipYears: number;
}

export const DEFAULT_USAGE: UsageProfile = {
  gamingHoursPerWeek: 15,
  idleHoursPerWeek: 25,
  pencePerKwh: 24.5,
  ownershipYears: 4,
};

export interface RunningCost {
  /** Component draw under load, before the power supply's own losses. */
  loadComponentW: number;
  /** What the wall actually sees under load. */
  loadWallW: number;
  idleComponentW: number;
  idleWallW: number;
  /** Efficiency at the gaming load point. */
  efficiencyAtLoad: number;
  efficiencyAtIdle: number;
  /** The supply's utilisation under load, which is what sets its efficiency. */
  loadFraction: number;
  kwhPerYear: number;
  costPerYear: number;
  costOverOwnership: number;
  /** Losses in the PSU alone, over the ownership period. */
  psuWasteCostOverOwnership: number;
  notes: string[];
}

/**
 * Idle draw.
 *
 * Not derivable from TDP — a modern desktop idles far below its rated power,
 * and the floor is set by the board, drives and fans rather than by the size of
 * the graphics card. A rough constant plus a small term for the GPU is closer
 * than scaling the load figure, which would have a 4090 idling at 90W when the
 * real figure is nearer 20.
 */
function idleDraw(loadComponentW: number, systemBaseW: number): number {
  return Math.round(systemBaseW * 0.75 + loadComponentW * 0.06);
}

export function runningCost(
  loadComponentW: number,
  psuWatts: number,
  tier: PsuTier,
  usage: UsageProfile,
  systemBaseW = 55,
): RunningCost {
  const notes: string[] = [];
  const loadFraction = psuWatts > 0 ? loadComponentW / psuWatts : 0.5;
  const effLoad = psuEfficiency(tier, loadFraction);
  const idleComponentW = idleDraw(loadComponentW, systemBaseW);
  const idleFraction = psuWatts > 0 ? idleComponentW / psuWatts : 0.15;
  const effIdle = psuEfficiency(tier, idleFraction);

  const loadWallW = loadComponentW / effLoad;
  const idleWallW = idleComponentW / effIdle;

  const kwhPerYear =
    ((loadWallW * usage.gamingHoursPerWeek + idleWallW * usage.idleHoursPerWeek) * 52) / 1000;
  const costPerYear = (kwhPerYear * usage.pencePerKwh) / 100;

  // The PSU's own losses, separated out — this is the number that makes an
  // efficiency tier a purchase decision rather than a specification.
  const wasteKwhPerYear =
    (((loadWallW - loadComponentW) * usage.gamingHoursPerWeek +
      (idleWallW - idleComponentW) * usage.idleHoursPerWeek) *
      52) /
    1000;

  if (loadFraction < 0.25) {
    notes.push(
      `The supply runs at ${(loadFraction * 100).toFixed(0)}% of its rating under load, which is below the point 80 Plus certifies and where efficiency falls away. Headroom for transient spikes is worth having; this much of it costs a little every hour the machine is on.`,
    );
  }
  if (loadFraction > 0.85) {
    notes.push(
      `The supply runs at ${(loadFraction * 100).toFixed(0)}% of its rating under sustained load, before transient spikes are counted. Efficiency is past its peak here and the fan will be audible.`,
    );
  }
  if (tier === 'none') {
    notes.push('No efficiency rating assumed, so these figures use a pessimistic curve. An unrated supply is the one component where the specification sheet genuinely tells you nothing.');
  }

  return {
    loadComponentW,
    loadWallW,
    idleComponentW,
    idleWallW,
    efficiencyAtLoad: effLoad,
    efficiencyAtIdle: effIdle,
    loadFraction,
    kwhPerYear,
    costPerYear,
    costOverOwnership: costPerYear * usage.ownershipYears,
    psuWasteCostOverOwnership: ((wasteKwhPerYear * usage.pencePerKwh) / 100) * usage.ownershipYears,
    notes,
  };
}

export interface OwnershipCost {
  purchase: number;
  running: RunningCost;
  /** Purchase plus electricity across the ownership period. */
  total: number;
  /** Running cost as a share of the total. */
  runningShare: number;
  verdict: string;
}

export function totalCostOfOwnership(purchase: number, running: RunningCost, usage: UsageProfile): OwnershipCost {
  const total = purchase + running.costOverOwnership;
  const runningShare = total > 0 ? running.costOverOwnership / total : 0;
  const yrs = usage.ownershipYears;
  const verdict =
    `About £${running.costPerYear.toFixed(0)} a year to run at ${usage.gamingHoursPerWeek}h a week of gaming and ${usage.idleHoursPerWeek}h idle, ` +
    `so £${running.costOverOwnership.toFixed(0)} over ${yrs} year${yrs === 1 ? '' : 's'} — ` +
    `${(runningShare * 100).toFixed(0)}% of what the machine actually costs you. ` +
    (running.psuWasteCostOverOwnership > 40
      ? `£${running.psuWasteCostOverOwnership.toFixed(0)} of that is lost in the power supply alone, which is what a better efficiency tier buys back.`
      : `Only £${running.psuWasteCostOverOwnership.toFixed(0)} of it is lost in the power supply, so a higher efficiency tier would not pay for itself here.`);
  return { purchase, running, total, runningShare, verdict };
}

/** Would a better power supply pay for itself? Compares two tiers over the period. */
export function efficiencyPayback(
  loadComponentW: number,
  psuWatts: number,
  from: PsuTier,
  to: PsuTier,
  extraCost: number,
  usage: UsageProfile,
): { savingOverOwnership: number; paybackYears: number | null; worthIt: boolean; detail: string } {
  const a = runningCost(loadComponentW, psuWatts, from, usage);
  const b = runningCost(loadComponentW, psuWatts, to, usage);
  const savingPerYear = a.costPerYear - b.costPerYear;
  const savingOverOwnership = savingPerYear * usage.ownershipYears;
  const paybackYears = savingPerYear > 0 ? extraCost / savingPerYear : null;
  const worthIt = paybackYears != null && paybackYears < usage.ownershipYears;
  return {
    savingOverOwnership,
    paybackYears,
    worthIt,
    detail:
      paybackYears == null
        ? `Moving from ${PSU_EFFICIENCY[from].label} to ${PSU_EFFICIENCY[to].label} saves nothing measurable at this load.`
        : worthIt
          ? `${PSU_EFFICIENCY[to].label} costs £${extraCost} more and saves £${savingPerYear.toFixed(0)} a year, paying for itself in ${paybackYears.toFixed(1)} years — inside the ${usage.ownershipYears} you plan to keep it.`
          : `${PSU_EFFICIENCY[to].label} costs £${extraCost} more and saves £${savingPerYear.toFixed(0)} a year, which takes ${paybackYears.toFixed(1)} years to pay back — longer than the ${usage.ownershipYears} you plan to keep it. Buy it for the build quality if you want it, not for the electricity.`,
  };
}
