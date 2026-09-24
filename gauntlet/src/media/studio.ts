/**
 * Studio — server side: loads a run into a StudioInput, renders cards to PNG
 * with the shared headless Chromium, exports a run's video kit to its folder,
 * polishes the script with a model (after showing the cost), and registers
 * the /api/studio and /api/overlay routes.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { getProvider, hasApiKey, loadCategories, loadContestants, loadProviders } from '../core/config.ts';
import { computeCost } from '../core/cost.ts';
import { loadTests, summarize } from '../core/registry.ts';
import type { Contestant } from '../core/types.ts';
import { runLeaderboard } from '../engine/leaderboards.ts';
import { callWithRetry } from '../engine/recorder.ts';
import { isActive } from '../engine/runner.ts';
import { Semaphore } from '../engine/semaphore.ts';
import { listRunIds, readManifest, readResults, runDir } from '../engine/store.ts';
import { createAdapter } from '../providers/index.ts';
import { browserUnavailableReason, getBrowser } from '../scoring/browser.ts';
import { cardData, cardFileName, cardSize, defaultCards, renderCardHtml } from './cards.ts';
import { findHighlights } from './highlights.ts';
import { overlayData } from './overlay.ts';
import { allowedNumbers, buildScript, scriptToMarkdown, scriptToText, unverifiedNumbers } from './script.ts';
import { presenterSlides } from './slides.ts';
import type { CardSpec, CardStyle, ExportResult, PolishEstimate, PolishResult, StudioInput, StudioPayload, StudioResult, StudioTestInfo } from './types.ts';

// ─────────────────────────────── Loading ───────────────────────────────

/** "latest" → the run in progress, else the newest run. */
export function resolveRunId(id: string): string | null {
  if (id !== 'latest') return readManifest(id) ? id : null;
  const ids = listRunIds();
  return ids.find((x) => isActive(x)) ?? ids[0] ?? null;
}

function testInfos(ids: string[]): StudioTestInfo[] {
  const lib = new Map(loadTests().map((t) => [t.definition.id, t]));
  const out: StudioTestInfo[] = [];
  for (const id of ids) {
    const t = lib.get(id);
    if (!t) continue;
    const d = t.definition;
    out.push({
      id: d.id,
      name: d.name,
      category: d.category,
      kind: d.kind,
      hook: d.hook,
      description: d.description,
      program: d.kind === 'program' ? d.program : undefined,
      scorerType: summarize(t).scorerType,
    });
  }
  return out;
}

export function loadStudioInput(runId: string): StudioInput | null {
  const manifest = readManifest(runId);
  if (!manifest) return null;
  const results: StudioResult[] = readResults(runId).map(({ transcript: _t, ...rest }) => rest);
  const manualProviders = new Set(loadProviders().filter((p) => p.type === 'manual').map((p) => p.id));
  return {
    manifest,
    leaderboard: runLeaderboard(runId),
    results,
    tests: testInfos(manifest.tests.map((t) => t.id)),
    categories: loadCategories(),
    manualIds: manifest.contestants.filter((c) => manualProviders.has(c.provider)).map((c) => c.id),
  };
}

export function studioPayload(input: StudioInput) {
  const slides = presenterSlides(input);
  const highlights = findHighlights(input, slides);
  const script = buildScript(input, highlights, slides);
  return {
    runId: input.manifest.id,
    runName: input.manifest.name || input.manifest.id,
    status: input.manifest.status,
    createdAt: input.manifest.createdAt,
    highlights,
    slides,
    script,
    markdown: scriptToMarkdown(script),
    text: scriptToText(script),
    cardData: cardData(input, highlights),
    allowedNumbers: [...allowedNumbers(input, highlights, slides)].sort((a, b) => Number(a) - Number(b)),
  };
}

// ─────────────────────────────── PNG rendering ───────────────────────────────

export async function renderPng(html: string, width: number, height: number): Promise<Buffer | null> {
  const browser = await getBrowser();
  if (!browser) return null;
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  try {
    const page = await context.newPage();
    // Cards are self-contained: refuse any network access.
    await page.route('**/*', (route) => (route.request().url().startsWith('data:') ? route.continue() : route.abort()));
    await page.setContent(html, { waitUntil: 'load', timeout: 15_000 });
    return await page.screenshot({ type: 'png', clip: { x: 0, y: 0, width, height } });
  } finally {
    await context.close().catch(() => {});
  }
}

export function browserHint(): string {
  return `${browserUnavailableReason() ?? 'No browser found'}. Install Google Chrome or Microsoft Edge, or set GAUNTLET_CHROMIUM to a Chrome/Edge/Chromium executable. Until then, PNGs are rendered in your browser instead.`;
}

/** Render every default card (plus the script) into <run>/studio/. */
export async function exportKit(input: StudioInput, opts: { style?: CardStyle; headline?: string; markdown?: string; text?: string }): Promise<ExportResult> {
  const payload = studioPayload(input);
  const dir = join(runDir(input.manifest.id), 'studio');
  mkdirSync(dir, { recursive: true });
  const files: string[] = [];
  const specs = defaultCards(payload.cardData, opts.style ?? 'versus', opts.headline);
  const browser = await getBrowser();
  if (browser) {
    for (const [i, spec] of specs.entries()) {
      const { width, height } = cardSize(spec.kind);
      const png = await renderPng(renderCardHtml(payload.cardData, spec), width, height);
      if (!png) break;
      const name = cardFileName(spec, i);
      writeFileSync(join(dir, name), png);
      files.push(name);
    }
  }
  writeFileSync(join(dir, 'script.md'), opts.markdown ?? payload.markdown);
  writeFileSync(join(dir, 'script.txt'), opts.text ?? payload.text);
  writeFileSync(join(dir, 'highlights.json'), JSON.stringify(payload.highlights, null, 2));
  files.push('script.md', 'script.txt', 'highlights.json');
  return { folder: dir, files, pngs: browser ? specs.length : 0, browser: !!browser, hint: browser ? undefined : browserHint() };
}

// ─────────────────────────────── Polish with AI ───────────────────────────────

export const POLISH_SYSTEM = [
  'You are the script editor for a YouTube channel that benchmarks AI models.',
  'Rewrite the draft narration so it sounds natural when read aloud: punchy, clear, friendly, no jargon.',
  'Hard rules:',
  '1. Keep every cue in square brackets (e.g. [Slide 7], [Replay: Escape Room, Model, step 14]) exactly as written, at the start of the line it belongs to.',
  '2. Do not add, remove, round or change ANY number. Only use numbers that appear in the draft.',
  '3. Keep the section headings and their order. Do not invent facts, results, prices or model names.',
  'Return only the rewritten script in the same Markdown format.',
].join('\n');

function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

export function polishEstimate(c: Contestant, draft: string): PolishEstimate {
  const inputTokens = estimateTokens(POLISH_SYSTEM) + estimateTokens(draft) + 20;
  const outputTokens = Math.ceil(estimateTokens(draft) * 1.15);
  const thinking = c.options?.effort && !['none', 'minimal', 'low'].includes(c.options.effort) ? 2 : 1;
  const usage = (out: number) => ({ inputTokens, outputTokens: out, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 });
  return {
    modelId: c.id,
    modelLabel: c.label,
    inputTokens,
    outputTokens,
    costUsd: computeCost(usage(outputTokens * thinking), c.pricing),
    /** Upper bound: long reasoning and a verbose rewrite. */
    costUsdHigh: computeCost(usage(outputTokens * thinking * 2.5), c.pricing),
    maxOutputTokens: Math.min(32_000, Math.max(4000, outputTokens * thinking * 3)),
  };
}

export function polishModel(id: string): Contestant {
  const c = loadContestants().find((x) => x.id === id);
  if (!c) throw new Error(`Unknown model "${id}"`);
  const p = getProvider(c.provider);
  if (p.type === 'manual' || p.type === 'mock') throw new Error('Pick an API model: copy & paste and baseline models cannot polish a script.');
  if (!hasApiKey(p)) throw new Error(`No API key for ${p.label}: set ${p.apiKeyEnv} first.`);
  return c;
}

export async function polishScript(c: Contestant, draft: string, allowed: Set<string>): Promise<PolishResult> {
  const est = polishEstimate(c, draft);
  const target = { contestant: c, adapter: createAdapter(c, getProvider(c.provider)), semaphore: new Semaphore(1) };
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 300_000);
  try {
    const r = await callWithRetry(
      target,
      { system: POLISH_SYSTEM, messages: [{ role: 'user', content: `Draft script:\n\n${draft}` }], maxOutputTokens: est.maxOutputTokens, temperature: 0.4 },
      { maxRetries: 2, temperature: 0.4, defaultMaxOutputTokens: est.maxOutputTokens },
      ctrl.signal,
    );
    const text = r.text.trim();
    return { text, costUsd: computeCost(r.usage, c.pricing), unverified: unverifiedNumbers(text, allowed), stopReason: r.stopReason };
  } finally {
    clearTimeout(timer);
  }
}

// ─────────────────────────────── Routes ───────────────────────────────

type RouteFn = (
  method: string,
  path: string,
  handler: (ctx: { params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown,
) => void;
type Fail = (status: number, message: string, details?: unknown) => Error;

export function registerStudioRoutes(route: RouteFn, fail: Fail): void {
  const input = (id: string) => {
    const runId = resolveRunId(decodeURIComponent(id));
    const inp = runId ? loadStudioInput(runId) : null;
    if (!inp) throw fail(404, id === 'latest' ? 'There are no runs yet' : 'Run not found');
    return inp;
  };

  route('GET', '/api/studio/:id', async ({ params }): Promise<StudioPayload> => {
    const payload = studioPayload(input(params.id!));
    const browser = !!(await getBrowser());
    return { ...payload, browser: { available: browser, hint: browser ? undefined : browserHint() }, exportDir: join(runDir(payload.runId), 'studio') };
  });

  route('POST', '/api/studio/:id/polish/estimate', async ({ params, body }) => {
    input(params.id!);
    const b = (await body()) as { modelId?: string; text?: string };
    if (!b.text?.trim()) throw fail(400, 'Nothing to polish: the script is empty');
    try {
      return polishEstimate(polishModel(String(b.modelId ?? '')), b.text);
    } catch (e) {
      throw fail(400, (e as Error).message);
    }
  });

  route('POST', '/api/studio/:id/polish', async ({ params, body }) => {
    const inp = input(params.id!);
    const b = (await body()) as { modelId?: string; text?: string; confirmCostUsd?: number };
    if (!b.text?.trim()) throw fail(400, 'Nothing to polish: the script is empty');
    let c: Contestant;
    try {
      c = polishModel(String(b.modelId ?? ''));
    } catch (e) {
      throw fail(400, (e as Error).message);
    }
    // The UI must have shown the estimate first: refuse when the confirmed amount is missing or too low.
    const est = polishEstimate(c, b.text);
    if (typeof b.confirmCostUsd !== 'number' || b.confirmCostUsd + 1e-9 < est.costUsdHigh) throw fail(409, 'Confirm the estimated cost first', est);
    const payload = studioPayload(inp);
    try {
      return await polishScript(c, b.text, new Set(payload.allowedNumbers));
    } catch (e) {
      throw fail(502, `Polishing failed: ${(e as Error).message}`);
    }
  });

  route('POST', '/api/studio/:id/render', async ({ params, body }) => {
    const inp = input(params.id!);
    const b = (await body()) as { spec?: CardSpec };
    if (!b.spec?.kind) throw fail(400, 'Missing card spec');
    const payload = studioPayload(inp);
    const { width, height } = cardSize(b.spec.kind);
    const png = await renderPng(renderCardHtml(payload.cardData, b.spec), width, height);
    if (!png) throw fail(409, browserHint(), { fallback: 'browser' });
    return { png: `data:image/png;base64,${png.toString('base64')}`, width, height, fileName: cardFileName(b.spec) };
  });

  route('POST', '/api/studio/:id/export', async ({ params, body }) => {
    const b = ((await body()) ?? {}) as { style?: CardStyle; headline?: string; markdown?: string; text?: string };
    return exportKit(input(params.id!), b);
  });

  route('GET', '/api/overlay/:id', ({ params }) => {
    const runId = resolveRunId(decodeURIComponent(params.id!));
    const manifest = runId ? readManifest(runId) : null;
    if (!manifest) throw fail(404, params.id === 'latest' ? 'There are no runs yet' : 'Run not found');
    const active = isActive(manifest.id);
    return overlayData(manifest, readResults(manifest.id), active ? null : runLeaderboard(manifest.id), testInfos(manifest.tests.map((t) => t.id)), active);
  });
}

/** `/overlay/<runId>?view=…` (the address pasted into OBS) → the dashboard's hash route. */
export function overlayRedirect(pathname: string, search: string): string | null {
  const m = pathname.match(/^\/overlay\/([A-Za-z0-9_-]+)\/?$/);
  if (!m) return null;
  return `/#/overlay/${m[1]}${search}`;
}
