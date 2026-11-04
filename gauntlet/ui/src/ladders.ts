/**
 * The difficulty ladders (config/ladders.json) for the UI: which tests are ladders, their pass marks, what grows
 * and each rung's growth label. The config is bundled, so it is the same in live and demo mode; the rules that
 * turn results into heights are src/core/ladders.ts (shared with the server).
 */
import ladderJson from '../../config/ladders.json';
import type { LaddersConfig, LadderRule, LadderTestSpec } from '../../src/core/ladders.ts';
import { LADDER_SUITE_IDS } from '../../src/core/areas.ts';

export const LADDERS = ladderJson as unknown as LaddersConfig;

const BY_ID = new Map(LADDERS.tests.map((t) => [t.id, t]));

export function ladderSpecOf(testId: string | null | undefined): LadderTestSpec | undefined {
  return testId ? BY_ID.get(testId) : undefined;
}

/** A difficulty-ladder test (built or planned). */
export function isLadderTest(testId: string | null | undefined): boolean {
  return !!ladderSpecOf(testId);
}

export function ladderRuleOf(testId: string): LadderRule {
  const s = BY_ID.get(testId);
  return { passMark: s?.passMark ?? 1, levels: s?.rungs?.length ?? LADDERS.levels };
}

/** A ladder suite (Horizon, Frontier Ladders, Business Ladders): its leaderboard leads with heights. */
export function isLadderSuiteId(suiteId: string | null | undefined): boolean {
  return !!suiteId && LADDER_SUITE_IDS.includes(suiteId);
}

/** "Full marks to pass a rung" / "90% of a rung’s marks to pass". */
export function passMarkText(passMark: number): string {
  return passMark >= 1 ? 'Full marks to pass a rung' : `${Math.round(passMark * 100)}% of a rung’s marks to pass`;
}

/** The calibration band a level was built for (METHODOLOGY "Difficulty ladders"). */
export function levelBand(level: number): { id: 'small' | 'mid' | 'top' | 'future'; label: string; short: string } {
  if (level <= 2) return { id: 'small', label: 'Small models', short: 'Small' };
  if (level <= 4) return { id: 'mid', label: 'Mid-sized models', short: 'Mid' };
  if (level <= 7) return { id: 'top', label: 'Today’s top models', short: 'Top today' };
  return { id: 'future', label: 'Beyond any model today', short: 'Future' };
}
