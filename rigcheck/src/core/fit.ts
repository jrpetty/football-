/**
 * Physical fit and display matching.
 *
 * Two questions the performance model cannot answer but every real build has to:
 * will the parts physically go in the box, and does the machine suit the screen
 * it will be plugged into.
 *
 * The case catalogue nulls clearance figures wherever they were uncertain, so
 * this module treats "unknown" as a first-class answer. Reporting "fits" from a
 * missing measurement would be worse than useless — someone would buy on it.
 */
import type { Build, CpuRecord, GpuRecord, Resolution } from './types.ts';

export interface CaseRecord {
  id: string;
  brand: string;
  name: string;
  fullName: string;
  formFactor: string;
  maxGpuLengthMm: number | null;
  maxCoolerHeightMm: number | null;
  maxPsuLengthMm: number | null;
  radiatorSupport: string[] | null;
  includedFans: number | null;
  airflowTier: 'restricted' | 'moderate' | 'good' | 'excellent';
  noiseTier: 'loud' | 'moderate' | 'quiet' | 'silent';
  releaseYear: number | null;
  notes: string | null;
}

export interface MonitorRecord {
  id: string;
  brand: string;
  name: string;
  fullName: string;
  resolution: Resolution;
  refreshHz: number;
  panelType: string;
  sizeInches: number;
  responseMs: number | null;
  adaptiveSync: string;
  hdr: string | null;
  releaseYear: number | null;
  typicalPriceGBP: number | null;
  notes: string | null;
}

export type FitVerdict = 'fits' | 'tight' | 'does-not-fit' | 'unknown';

export interface FitCheck {
  aspect: string;
  verdict: FitVerdict;
  detail: string;
}

/** Approximate cooler heights by tier, for the clearance check. */
const COOLER_HEIGHT_MM: Record<string, number> = {
  stock: 55,
  'budget-tower': 155,
  'premium-air': 165,
  aio: 55, // The block is short; the radiator is the constraint, checked separately.
};

export function checkFit(build: Build, gpu: GpuRecord, cpu: CpuRecord, pcCase: CaseRecord): FitCheck[] {
  const checks: FitCheck[] = [];

  // GPU length.
  if (gpu.lengthMm == null) {
    checks.push({
      aspect: 'GPU length',
      verdict: 'unknown',
      detail: `No length recorded for ${gpu.fullName}. Board partners vary this by 60mm or more on the same chip, so measure the actual card rather than trusting a model number.`,
    });
  } else if (pcCase.maxGpuLengthMm == null) {
    checks.push({
      aspect: 'GPU length',
      verdict: 'unknown',
      detail: `${gpu.lengthMm}mm card, but no clearance figure recorded for the ${pcCase.fullName}. Check the manufacturer's spec.`,
    });
  } else {
    const slack = pcCase.maxGpuLengthMm - gpu.lengthMm;
    checks.push({
      aspect: 'GPU length',
      verdict: slack < 0 ? 'does-not-fit' : slack < 15 ? 'tight' : 'fits',
      detail:
        slack < 0
          ? `${gpu.lengthMm}mm card in a ${pcCase.maxGpuLengthMm}mm case — ${-slack}mm too long.`
          : `${gpu.lengthMm}mm card, ${pcCase.maxGpuLengthMm}mm clearance: ${slack}mm spare${slack < 15 ? ' — tight enough that cable routing at the front may interfere' : ''}.`,
    });
  }

  // CPU cooler height.
  const coolerH = COOLER_HEIGHT_MM[build.cooling ?? 'budget-tower'];
  if (pcCase.maxCoolerHeightMm == null) {
    checks.push({
      aspect: 'CPU cooler',
      verdict: 'unknown',
      detail: `No cooler clearance recorded for the ${pcCase.fullName}.`,
    });
  } else if (build.cooling === 'aio') {
    checks.push({
      aspect: 'CPU cooler',
      verdict: pcCase.radiatorSupport?.length ? 'fits' : 'unknown',
      detail: pcCase.radiatorSupport?.length
        ? `AIO: this case takes ${pcCase.radiatorSupport.join(', ')}. Check your radiator is one of those sizes.`
        : 'AIO selected but no radiator support recorded for this case.',
    });
  } else {
    const slack = pcCase.maxCoolerHeightMm - coolerH;
    checks.push({
      aspect: 'CPU cooler',
      verdict: slack < 0 ? 'does-not-fit' : slack < 8 ? 'tight' : 'fits',
      detail: `A ${build.cooling ?? 'budget-tower'} cooler is roughly ${coolerH}mm against ${pcCase.maxCoolerHeightMm}mm clearance (${slack}mm spare). Heights vary by model — confirm against the specific cooler.`,
    });
  }

  // Thermal suitability, which is a fit question even when everything physically goes in.
  const heat = (gpu.tdpW ?? 150) + (cpu.tdpW ?? 65) * 0.42;
  if (pcCase.airflowTier === 'restricted' && heat > 300) {
    checks.push({
      aspect: 'Heat load',
      verdict: 'tight',
      detail: `${Math.round(heat)}W of heat in a restricted-airflow case. It will fit and it will run, but expect throttling and noise — the Machine Report quantifies both.`,
    });
  }

  return checks;
}

// ---------------------------------------------------------------------------
// Monitor matching
// ---------------------------------------------------------------------------

export interface MonitorMatch {
  monitor: MonitorRecord;
  /** Fraction of the panel's refresh the build can actually feed. */
  refreshUtilisation: number;
  verdict: 'well matched' | 'build is overkill' | 'build cannot feed it' | 'good';
  detail: string;
}

/**
 * Match a build's achievable frame rate to a panel.
 *
 * Both mismatches cost money in opposite directions: a 240Hz panel fed 70fps is
 * mostly wasted, and a strong build on a 60Hz screen is throwing away frames it
 * already paid for. Neither is visible from a frame-rate number alone.
 */
export function matchMonitors(
  achievableFps: number,
  monitors: MonitorRecord[],
  targetResolution?: Resolution,
): MonitorMatch[] {
  return monitors
    .filter((m) => !targetResolution || m.resolution === targetResolution)
    .map((m) => {
      const utilisation = achievableFps / m.refreshHz;
      let verdict: MonitorMatch['verdict'];
      let detail: string;
      if (utilisation >= 0.95) {
        verdict = m.refreshHz <= 75 ? 'build is overkill' : 'well matched';
        detail =
          m.refreshHz <= 75
            ? `The build makes ${achievableFps.toFixed(0)}fps but this panel only shows ${m.refreshHz}. You are paying for frames you cannot see — a faster panel would convert that into something visible.`
            : `${achievableFps.toFixed(0)}fps against ${m.refreshHz}Hz — the build saturates the panel.`;
      } else if (utilisation >= 0.7) {
        verdict = 'good';
        detail = `${achievableFps.toFixed(0)}fps feeds ${(utilisation * 100).toFixed(0)}% of ${m.refreshHz}Hz. With adaptive sync (${m.adaptiveSync}) this is a comfortable pairing.`;
      } else {
        verdict = 'build cannot feed it';
        detail = `${achievableFps.toFixed(0)}fps against ${m.refreshHz}Hz — only ${(utilisation * 100).toFixed(0)}% utilised. The panel's speed is largely wasted on this build.`;
      }
      return { monitor: m, refreshUtilisation: utilisation, verdict, detail };
    })
    .sort((a, b) => Math.abs(1 - a.refreshUtilisation) - Math.abs(1 - b.refreshUtilisation));
}
