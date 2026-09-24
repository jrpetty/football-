/**
 * Mock-mode Studio and overlay endpoints. Runs the real (pure) highlight
 * finder, script generator and card data builder from src/media on the
 * bundled fixture runs, so the Studio can be demoed with no server.
 */
import type { ContestantView, ExportResult, OverlayData, PolishEstimate, PolishResult, StudioInput, StudioPayload, StudioResult, TestDefinition } from '../types.ts';
import { ApiError } from '../api.ts';
import { findHighlights } from '../../../src/media/highlights.ts';
import { presenterSlides } from '../../../src/media/slides.ts';
import { allowedNumbers, buildScript, scriptToMarkdown, scriptToText, unverifiedNumbers } from '../../../src/media/script.ts';
import { cardData, defaultCards } from '../../../src/media/cards.ts';
import { overlayData } from '../../../src/media/overlay.ts';
import { META, buildLeaderboard, detailFor } from './fixtures.ts';
import type { MockRun } from './fixtures.ts';

const JUDGED = new Set(['judge', 'judge-classify', 'artifact']);

function infos(run: MockRun, tests: TestDefinition[]) {
  return run.manifest.tests
    .map((s) => tests.find((t) => t.id === s.id))
    .filter((t): t is TestDefinition => !!t)
    .map((t) => ({
      id: t.id,
      name: t.name,
      category: t.category,
      kind: t.kind,
      hook: t.hook,
      description: t.description,
      program: t.kind === 'program' ? t.program : undefined,
      scorerType: t.kind === 'program' ? `program:${t.program}` : t.scorer.type,
    }));
}

const cache = new Map<string, { n: number; payload: StudioPayload }>();

export function mockStudioInput(run: MockRun, tests: TestDefinition[], contestants: ContestantView[]): StudioInput {
  const byId = new Map(tests.map((t) => [t.id, t]));
  const results: StudioResult[] = run.results.map((lite) => {
    const t = byId.get(lite.testId);
    const needsDetail = t && (t.kind === 'program' || JUDGED.has(t.scorer.type) || t.scorer.type === 'exact' || t.scorer.type === 'number');
    if (!needsDetail) return lite;
    const { transcript: _t, ...full } = detailFor(lite);
    return full;
  });
  return {
    manifest: run.manifest,
    leaderboard: buildLeaderboard(run.manifest, run.results, { kind: 'run', runId: run.manifest.id }),
    results,
    tests: infos(run, tests),
    categories: META.categories,
    manualIds: contestants.filter((c) => c.providerType === 'manual').map((c) => c.id),
  };
}

export function mockStudio(run: MockRun, tests: TestDefinition[], contestants: ContestantView[]): StudioPayload {
  const hit = cache.get(run.manifest.id);
  if (hit && hit.n === run.results.length) return hit.payload;
  const input = mockStudioInput(run, tests, contestants);
  const slides = presenterSlides(input);
  const highlights = findHighlights(input, slides);
  const script = buildScript(input, highlights, slides);
  const payload: StudioPayload = {
    runId: run.manifest.id,
    runName: run.manifest.name,
    status: run.manifest.status,
    createdAt: run.manifest.createdAt,
    highlights,
    slides,
    script,
    markdown: scriptToMarkdown(script),
    text: scriptToText(script),
    cardData: cardData(input, highlights),
    allowedNumbers: [...allowedNumbers(input, highlights, slides)],
    browser: { available: false, hint: 'Demo mode: PNGs are rendered in your browser.' },
    exportDir: `data/runs/${run.manifest.id}/studio`,
  };
  cache.set(run.manifest.id, { n: run.results.length, payload });
  return payload;
}

function polishModel(contestants: ContestantView[], id: string): ContestantView {
  const c = contestants.find((x) => x.id === id);
  if (!c) throw new ApiError(`Unknown model "${id}"`, 400);
  if (c.providerType === 'manual' || c.providerType === 'mock') throw new ApiError('Pick an API model: copy & paste and baseline models cannot polish a script.', 400);
  if (!c.hasKey) throw new ApiError(`No API key for ${c.providerLabel}.`, 400);
  return c;
}

export function mockPolishEstimate(contestants: ContestantView[], modelId: string, text: string): PolishEstimate {
  const c = polishModel(contestants, modelId);
  const inputTokens = Math.ceil(text.length / 4) + 180;
  const outputTokens = Math.ceil((text.length / 4) * 1.15);
  const cost = (out: number) => (inputTokens * c.pricing.inputPerM + out * c.pricing.outputPerM) / 1e6;
  return { modelId: c.id, modelLabel: c.label, inputTokens, outputTokens, costUsd: cost(outputTokens), costUsdHigh: cost(outputTokens * 2.5), maxOutputTokens: outputTokens * 3 };
}

/** A deterministic "polish" for demos: friendlier phrasing, every cue and number kept. */
export function mockPolish(contestants: ContestantView[], payload: StudioPayload, modelId: string, text: string): PolishResult {
  const est = mockPolishEstimate(contestants, modelId, text);
  const polished = text
    .replace(/Here are today’s contestants:/g, 'Let’s meet today’s contestants!')
    .replace(/takes it with/g, 'wins it with')
    .replace(/Bottom of the table:/g, 'And propping up the table:')
    .replace(/How it works:/g, 'Quick rules before we start:')
    .replace(/That’s the Gauntlet\./g, 'And that’s the Gauntlet!');
  return { text: polished, costUsd: est.costUsd, unverified: unverifiedNumbers(polished, new Set(payload.allowedNumbers)), stopReason: 'end' };
}

export function mockExport(payload: StudioPayload): ExportResult {
  const specs = defaultCards(payload.cardData);
  return { folder: payload.exportDir, files: ['script.md', 'script.txt', 'highlights.json'], pngs: 0, browser: false, hint: `Demo mode: ${specs.length} PNGs are rendered in your browser instead.` };
}

export function mockOverlay(run: MockRun, tests: TestDefinition[], active: boolean): OverlayData {
  const lb = active ? null : buildLeaderboard(run.manifest, run.results, { kind: 'run', runId: run.manifest.id });
  return overlayData(run.manifest, run.results, lb, infos(run, tests), active);
}
