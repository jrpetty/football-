/**
 * Builds the sanitised data behind the public website.
 *
 * Privacy rules (tested in test/channel.test.ts):
 *  - Held-out tests (tests/private/) are never named, described or quoted. They
 *    appear only as "Held-out test N" so the published Index stays honest.
 *  - Answer keys, case notes and per-case summaries (which can quote the
 *    expected answer) are never published for any test.
 *  - Example prompts are withheld when the test or any published suite
 *    containing it sets "publishPrompts": false.
 *  - Model notes and provider settings are never published.
 */
import { loadContestants, loadProviders } from '../core/config.ts';
import { fingerprint, getSuite, loadTests, renderCase, resolveTests, selectedCaseIds, type LoadedTest } from '../core/registry.ts';
import type { Contestant, Leaderboard, TestDefinition } from '../core/types.ts';
import { HARNESS_VERSION, PROTOCOL_VERSION } from '../core/version.ts';
import { combinedLeaderboard } from '../engine/leaderboards.ts';
import { PROGRAMS } from '../programs/index.ts';
import { buildHistory, simulatedIds } from './history.ts';
import { loadSiteConfig } from './site-config.ts';
import type { PublicData, PublicModel, PublicSuite, PublicTest, SiteConfig } from './types.ts';

const MAX_PROMPT_CHARS = 6000;

/** Plain-English "how it's scored" (mirrors the Presenter's wording). */
export function scoringText(def: TestDefinition): string {
  if (def.kind === 'program') return PROGRAMS[def.program]?.scoring || 'Scored by the simulation itself: the same world for every model.';
  const sc = def.scorer;
  switch (sc.type) {
    case 'code-js':
      return 'The code each model writes is run against hidden unit tests. Only passing tests count.';
    case 'exact':
    case 'number':
    case 'choice':
    case 'regex':
      return 'One correct answer per question: right or wrong. Hedged answers are marked wrong.';
    case 'contains':
      return 'Checked for the key facts a correct answer has to mention.';
    case 'constraints':
      return sc.allOrNothing ? 'Every rule is checked automatically; break a single one and the answer scores zero.' : 'Every rule in the instructions is checked automatically: points for each rule followed.';
    case 'json':
      return sc.allOrNothing ? 'Every field of the JSON answer must match the answer key; one wrong field scores zero.' : 'Points for every JSON field that matches the answer key.';
    case 'judge':
    case 'judge-classify':
      return 'Graded by a panel of AI judges from other companies, blind to which model wrote the answer.';
    case 'artifact':
      return `The ${sc.format === 'html' ? 'web page' : 'drawing'} each model builds is opened and checked automatically${sc.rubric ? ', then rated by a judge panel' : ''}.`;
    case 'human':
      return 'Rated blind by people who never see which model made which answer.';
    default:
      return 'Scored automatically by the Gauntlet harness.';
  }
}


export interface BuildPublicOptions {
  suites?: string[];
  site?: SiteConfig;
  /** Injected for tests; defaults to the combined leaderboard of every run. */
  leaderboardFor?: (suiteId: string) => Leaderboard;
}

export function buildPublicData(opts: BuildPublicOptions = {}): { data: PublicData; warnings: string[] } {
  const site = opts.site ?? loadSiteConfig();
  const warnings: string[] = [];
  const all = loadTests();
  const suiteIds = (opts.suites?.length ? opts.suites : site.suites).filter((id, i, arr) => arr.indexOf(id) === i);
  const leaderboardFor = opts.leaderboardFor ?? combinedLeaderboard;
  const contestants = loadContestants();
  const providers = loadProviders();
  const typeOf = (c: Contestant) => providers.find((p) => p.id === c.provider)?.type;

  // Held-out tests get stable anonymous ids in order of first appearance.
  const heldOutAlias = new Map<string, string>();
  const heldOutName = new Map<string, string>();
  const withhold = new Set<string>();
  const testsInSuites = new Map<string, LoadedTest & { caseFilter?: string[] }>();

  const suites: PublicSuite[] = [];
  for (const id of suiteIds) {
    const suite = getSuite(id);
    if (!suite) {
      warnings.push(`Suite "${id}" does not exist; skipped.`);
      continue;
    }
    const resolved = resolveTests({ suiteId: id }, all);
    for (const t of resolved) {
      const tid = t.definition.id;
      if (!testsInSuites.has(tid)) testsInSuites.set(tid, t);
      if (t.source === 'private' && !heldOutAlias.has(tid)) {
        const n = heldOutAlias.size + 1;
        heldOutAlias.set(tid, `heldout-${n}`);
        heldOutName.set(tid, `Held-out test ${n}`);
      }
      if (suite.publishPrompts === false || t.definition.publishPrompts === false) withhold.add(tid);
    }
    const board = sanitizeBoard(leaderboardFor(id), heldOutAlias, heldOutName);
    if (!board.rows.length) warnings.push(`Suite "${suite.name}" has no results yet: its leaderboard will be empty.`);
    suites.push({ id: suite.id, name: suite.name, description: suite.description, version: suite.version, fingerprint: fingerprint(resolved), leaderboard: board });
  }

  const tests: PublicTest[] = [];
  for (const [tid, t] of testsInSuites) {
    const d = t.definition;
    const cat = d.category;
    if (t.source === 'private') {
      tests.push({
        id: heldOutAlias.get(tid)!,
        name: heldOutName.get(tid)!,
        category: cat,
        difficulty: d.difficulty,
        description: 'A held-out test kept private so no model can have trained on it. Its prompts and answers are never published.',
        version: d.version,
        hash: t.hash,
        kind: d.kind,
        cases: selectedCaseIds(t).length,
        scoring: scoringText(d),
        heldOut: true,
        promptWithheld: true,
      });
      continue;
    }
    const entry: PublicTest = {
      id: d.id,
      name: d.name,
      category: cat,
      difficulty: d.difficulty,
      description: d.description,
      hook: d.hook,
      version: d.version,
      hash: t.hash,
      kind: d.kind,
      cases: selectedCaseIds(t).length,
      scoring: scoringText(d),
      heldOut: false,
      promptWithheld: withhold.has(tid),
    };
    if (d.kind === 'prompt' && !withhold.has(tid)) {
      const ids = selectedCaseIds(t);
      const first = d.cases.find((c) => ids.includes(c.id)) ?? d.cases[0];
      if (first) {
        const r = renderCase(d, first);
        let text = r.turns[0] ?? '';
        if (r.turns.length > 1) text += `\n\n[…${r.turns.length - 1} more turn${r.turns.length > 2 ? 's' : ''} follow in the same conversation]`;
        if (text.length > MAX_PROMPT_CHARS) text = `${text.slice(0, MAX_PROMPT_CHARS).trimEnd()}\n[…truncated]`;
        entry.examplePrompt = text;
        if (r.system) entry.exampleSystem = r.system.length > MAX_PROMPT_CHARS ? `${r.system.slice(0, MAX_PROMPT_CHARS)}…` : r.system;
      }
    }
    tests.push(entry);
  }

  const onBoard = new Set(suites.flatMap((s) => s.leaderboard.rows.map((r) => r.contestantId)));
  const models: PublicModel[] = contestants
    .filter((c) => onBoard.has(c.id))
    .map((c) => ({
      id: c.id,
      label: c.label,
      vendor: c.vendor,
      color: c.color,
      family: c.family,
      releaseDate: c.releaseDate,
      tier: c.tier,
      model: c.model,
      manual: typeOf(c) === 'manual',
      baseline: typeOf(c) === 'mock',
      pricing: {
        inputPerM: c.pricing.inputPerM,
        outputPerM: c.pricing.outputPerM,
        cachedInputPerM: c.pricing.cachedInputPerM,
        verifiedAt: c.pricing.verifiedAt ?? null,
        source: c.pricing.source,
      },
    }));
  const unverified = models.filter((m) => !m.baseline && !m.manual && !m.pricing.verifiedAt);
  if (unverified.length) warnings.push(`Prices not verified for ${unverified.map((m) => m.label).join(', ')}: the site marks them "unverified".`);

  const historySuite = suites[0];
  const history = buildHistory(historySuite?.leaderboard ?? emptyBoard(), contestants, { exclude: simulatedIds(), suiteId: historySuite?.id ?? 'core' });

  return {
    data: {
      generatedAt: new Date().toISOString(),
      harnessVersion: HARNESS_VERSION,
      protocolVersion: PROTOCOL_VERSION,
      site,
      suites,
      models,
      tests,
      history,
    },
    warnings,
  };
}

function emptyBoard(): Leaderboard {
  return { generatedAt: new Date().toISOString(), scope: { kind: 'combined', suiteId: 'core' }, fingerprint: '', categories: [], categoryWeights: {}, tests: [], rows: [], medals: [], staleExcluded: 0 };
}

/** Remove per-case summaries and anonymise held-out tests in a leaderboard (returns a copy). */
export function sanitizeBoard(board: Leaderboard, alias: Map<string, string>, names: Map<string, string>): Leaderboard {
  const b = structuredClone(board);
  const mapId = (id: string) => alias.get(id) ?? id;
  b.tests = b.tests.map((t) => ({ ...t, id: mapId(t.id), name: names.get(t.id) ?? t.name }));
  b.medals = b.medals.map((m) => ({ ...m, testId: mapId(m.testId) }));
  for (const row of b.rows) {
    const tests: typeof row.tests = {};
    for (const [tid, agg] of Object.entries(row.tests)) {
      const { summary: _summary, ...rest } = agg;
      tests[mapId(tid)] = { ...rest, testId: mapId(tid) };
    }
    row.tests = tests;
  }
  return b;
}
