/**
 * The three areas of Gauntlet, kept clearly separate (pure; shared by the server and the UI):
 *
 *  - benchmark: everything that measures raw ability (the Gauntlet Index, Standard/Frontier/Horizon, Trick,
 *    Vision, Art, Games and the Arena);
 *  - business: every `work.*` test, organised as the Business Ladder;
 *  - life: every `life.*` test (Everyday Life).
 *
 * Business and Everyday Life have their own suites and their own results, so they never feed the Gauntlet
 * Index. The suite ids live here so the suites can be wired in one place.
 */
export type AreaId = 'benchmark' | 'business' | 'life';

export interface AreaInfo {
  id: AreaId;
  name: string;
  /** One line for menus and headers. */
  blurb: string;
  /** The suite whose leaderboard is this area's results (null: the benchmark uses its usual suites). */
  suiteId: string | null;
  /** Categories that belong to the area (benchmark: every other category). */
  categories: string[];
}

/** Suite ids for the two non-benchmark areas (the suites themselves are added in suites/). */
export const BUSINESS_SUITE_ID = 'business';
export const LIFE_SUITE_ID = 'everyday-life';

export const AREAS: readonly AreaInfo[] = [
  { id: 'benchmark', name: 'Benchmark', blurb: 'Raw ability: the Gauntlet Index, the hard tiers, the Arena and the games.', suiteId: null, categories: [] },
  { id: 'business', name: 'Business', blurb: 'The jobs companies hand to AI, from reading one invoice to running a department.', suiteId: BUSINESS_SUITE_ID, categories: ['work'] },
  { id: 'life', name: 'Everyday Life', blurb: 'Personal admin people ask an AI for help with: trips, bills, tariffs and recipes.', suiteId: LIFE_SUITE_ID, categories: ['life'] },
];

/** The area a category belongs to (anything not business or life is the benchmark). */
export function areaOfCategory(category: string | null | undefined): AreaId {
  for (const a of AREAS) if (a.categories.includes(category ?? '')) return a.id;
  return 'benchmark';
}

/** The area of a test: by its category, or by its id prefix ("work." / "life.") when the category is missing. */
export function areaOfTest(t: { id: string; category?: string | null }): AreaId {
  if (t.category) return areaOfCategory(t.category);
  return areaOfCategory(t.id.split('.')[0]);
}

/** The area a suite belongs to: its own id if it is an area suite, else by its tests (all business → business). */
export function areaOfSuite(suite: { id: string; tests?: ReadonlyArray<{ id: string }> }): AreaId {
  const own = AREAS.find((a) => a.suiteId === suite.id);
  if (own) return own.id;
  const ids = (suite.tests ?? []).map((t) => t.id).filter((id) => id !== '*');
  if (ids.length) {
    const kinds = new Set(ids.map((id) => areaOfTest({ id })));
    if (kinds.size === 1) return [...kinds][0]!;
  }
  return 'benchmark';
}

export function areaInfo(id: AreaId): AreaInfo {
  return AREAS.find((a) => a.id === id)!;
}
