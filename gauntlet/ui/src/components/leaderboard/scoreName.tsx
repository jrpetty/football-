/**
 * The headline score's name for the leaderboard being shown: "Gauntlet Index" for the benchmark,
 * "Business Score" / "Everyday Life Score" for those areas (one rule: scoreNameFor in src/core/areas.ts).
 * LeaderboardView provides it; the table, panels, charts and "Why these scores?" read it.
 */
import { createContext, useContext } from 'react';
import { SCORE_NAMES } from '../../../../src/core/areas.ts';

export const ScoreNameContext = createContext<string>(SCORE_NAMES.benchmark);

export function useScoreName(): string {
  return useContext(ScoreNameContext);
}
