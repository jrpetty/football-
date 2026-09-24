/**
 * New Model Day — add a just-released model, check it works, price the suites,
 * run them under a spending cap and turn the result into a headline.
 *
 * Nothing here spends money on its own: callers (CLI wizard, dashboard) must
 * show the estimate and get an explicit yes before `ping` or a run.
 */
import { getContestant, getProvider, loadContestants, loadProviders, upsertContestant, validateContestant } from '../core/config.ts';
import { computeCost } from '../core/cost.ts';
import { getSuite, resolveTests, selectedCaseIds } from '../core/registry.ts';
import type { Contestant } from '../core/types.ts';
import { combinedLeaderboard } from '../engine/leaderboards.ts';
import { callWithRetry } from '../engine/recorder.ts';
import { estimateRun } from '../engine/runner.ts';
import { Semaphore } from '../engine/semaphore.ts';
import { createAdapter, discoverModels } from '../providers/index.ts';
import type { NewModelHeadline, NewModelInput, NewModelPrepared, NewModelSuiteCost } from './types.ts';

const VENDOR_COLORS: Record<string, string> = {
  anthropic: '#D97757',
  openai: '#10A37F',
  google: '#4285F4',
  xai: '#6B7280',
  deepseek: '#4D6BFE',
  mistral: '#FA520F',
  meta: '#0866FF',
  qwen: '#7C3AED',
};

/** Contestant id from a model id: lowercase, safe characters only. */
export function slugId(model: string): string {
  const s = model
    .toLowerCase()
    .replace(/^[a-z0-9-]+\//, '') // "anthropic/claude-x" (OpenRouter style) -> "claude-x"
    .replace(/[^a-z0-9._-]+/g, '-')
    .replace(/^[^a-z0-9]+|[-_.]+$/g, '')
    .slice(0, 64);
  return s || 'new-model';
}

/** "gpt-6-mini" -> "GPT 6 mini"-ish label when none is given. */
export function labelFromModel(model: string): string {
  const base = model.replace(/^[a-z0-9-]+\//i, '');
  return base
    .split(/[-_]/)
    .filter(Boolean)
    .map((w) => (/^(gpt|glm|llm)$/i.test(w) ? w.toUpperCase() : w.charAt(0).toUpperCase() + w.slice(1)))
    .join(' ');
}

/** First word of the label: "Claude Opus 6" -> "Claude", "GPT-6" -> "GPT". */
export function guessFamily(label: string): string {
  return label.match(/^[A-Za-z]+/)?.[0] ?? label.split(/\s/)[0] ?? label;
}

export function guessTier(text: string): 'flagship' | 'mid' | 'small' {
  const t = text.toLowerCase();
  if (/\b(mini|nano|lite|haiku|small|tiny|8b|7b|3b|1b)\b|flash-lite|-mini|-nano/.test(t)) return 'small';
  if (/\b(flash|sonnet|medium|mid|turbo|air)\b/.test(t)) return 'mid';
  return 'flagship';
}

function colorFor(vendor: string, seed: string): string {
  const v = VENDOR_COLORS[vendor.toLowerCase()];
  if (v) return v;
  let h = 0;
  for (const ch of seed) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  const hue = h % 360;
  // HSL(hue, 60%, 50%) -> hex
  const f = (n: number) => {
    const k = (n + hue / 30) % 12;
    const c = 0.5 - 0.3 * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return Math.round(c * 255).toString(16).padStart(2, '0');
  };
  return `#${f(0)}${f(8)}${f(4)}`;
}

/** Build the contestant a New Model Day input describes (pure; does not save). */
export function contestantFromInput(input: NewModelInput, today = new Date().toISOString().slice(0, 10)): Contestant {
  const provider = getProvider(input.provider);
  const label = input.label?.trim() || labelFromModel(input.model);
  const aggregator = ['openrouter', 'together', 'groq', 'ollama'].includes(provider.id);
  const prefix = input.model.includes('/') ? input.model.split('/')[0]! : '';
  const vendor = input.vendor?.trim() || (aggregator && prefix ? prefix.charAt(0).toUpperCase() + prefix.slice(1) : provider.label);
  const c: Contestant = {
    id: input.id?.trim() || slugId(input.model),
    label,
    vendor,
    provider: provider.id,
    model: input.model.trim(),
    color: input.color && /^#[0-9a-fA-F]{6}$/.test(input.color) ? input.color : colorFor(vendor, input.model),
    enabled: true,
    pricing: {
      inputPerM: input.inputPerM ?? 0,
      outputPerM: input.outputPerM ?? 0,
      ...(input.cachedInputPerM !== undefined ? { cachedInputPerM: input.cachedInputPerM } : {}),
      source: input.pricesVerified ? `Provider price page (checked ${today})` : 'Entered on New Model Day; not yet verified',
      verifiedAt: input.pricesVerified ? today : null,
    },
    family: input.family?.trim() || guessFamily(label),
    tier: input.tier ?? guessTier(`${label} ${input.model}`),
    ...(input.releaseDate ? { releaseDate: input.releaseDate } : {}),
    ...(provider.type === 'anthropic' || provider.type === 'gemini' ? { options: { effort: input.effort ?? 'high', supportsTemperature: false } } : input.effort ? { options: { effort: input.effort } } : {}),
    notes: `Added with New Model Day on ${today}.`,
  };
  return c;
}

/** Add (or refresh) the contestant and check the model id against the provider's list. Free: no model call. */
export async function prepareNewModel(input: NewModelInput, opts: { discover?: boolean } = {}): Promise<NewModelPrepared> {
  if (!input.provider) throw new Error('Choose a provider');
  if (!input.model?.trim()) throw new Error('Enter the model id (e.g. gpt-6)');
  if (!loadProviders().some((p) => p.id === input.provider)) throw new Error(`Unknown provider "${input.provider}". Configured: ${loadProviders().map((p) => p.id).join(', ')}`);
  for (const [k, v] of [['inputPerM', input.inputPerM], ['outputPerM', input.outputPerM]] as const) {
    if (v === undefined || !Number.isFinite(v) || v < 0) throw new Error(`${k === 'inputPerM' ? 'Input' : 'Output'} price per 1M tokens is required (0 for free/local models)`);
  }
  const warnings: string[] = [];
  const draft = contestantFromInput(input);
  const existing = loadContestants().find((c) => c.id === draft.id);
  let contestant = draft;
  let created = true;
  if (existing) {
    if (existing.provider !== draft.provider || existing.model !== draft.model)
      throw new Error(`A different model already uses the id "${draft.id}" (${existing.label}). Pick another id.`);
    // Same model: keep its colour/options, refresh prices and metadata from this wizard.
    created = false;
    contestant = { ...existing, label: draft.label, pricing: draft.pricing, family: draft.family, tier: draft.tier, enabled: true, ...(draft.releaseDate ? { releaseDate: draft.releaseDate } : {}) };
    warnings.push(`"${existing.label}" was already configured: prices and details were updated.`);
  }
  const errors = validateContestant(contestant);
  if (errors.length) throw new Error(errors.join('; '));
  if (!contestant.pricing.verifiedAt) warnings.push('Prices are marked unverified until you check them on the provider’s price page (Models → edit → verified date).');

  let discovered: boolean | null = null;
  let discoverError: string | undefined;
  let suggestions: string[] = [];
  const type = getProvider(contestant.provider).type;
  if (opts.discover !== false && type !== 'manual' && type !== 'mock') {
    try {
      const ids = await discoverModels(contestant.provider);
      discovered = ids.includes(contestant.model);
      if (!discovered) {
        const needle = contestant.model.toLowerCase().replace(/[^a-z0-9]/g, '');
        suggestions = ids.filter((id) => id.toLowerCase().replace(/[^a-z0-9]/g, '').includes(needle.slice(0, Math.max(3, needle.length - 3)))).slice(0, 8);
        warnings.push(`"${contestant.model}" is not in ${getProvider(contestant.provider).label}'s model list for your key.${suggestions.length ? ` Did you mean: ${suggestions.join(', ')}?` : ''}`);
      }
    } catch (err) {
      discoverError = (err as Error).message;
    }
  }
  upsertContestant(contestant);
  return { contestant, created, discovered, discoverError, suggestions, warnings };
}

/** One-word request to check the model answers (costs a fraction of a cent). */
export async function pingModel(id: string): Promise<{ ok: boolean; text?: string; totalMs?: number; costUsd?: number; servedModel?: string; error?: string }> {
  try {
    const c = getContestant(id);
    const target = { contestant: c, adapter: createAdapter(c, getProvider(c.provider)), semaphore: new Semaphore(1) };
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 120_000);
    const r = await callWithRetry(target, { messages: [{ role: 'user', content: 'Reply with exactly one word: pong' }], maxOutputTokens: 2000, temperature: 0 }, { maxRetries: 1, temperature: 0, defaultMaxOutputTokens: 2000 }, ctrl.signal).finally(() => clearTimeout(timer));
    return { ok: true, text: r.text.slice(0, 200), totalMs: r.totalMs, costUsd: computeCost(r.usage, c.pricing), servedModel: r.servedModel };
  } catch (err) {
    return { ok: false, error: (err as Error).message };
  }
}

/** Estimated cost of each suite for this model alone (free: no model calls). */
export async function newModelCosts(id: string, suites = ['quick', 'core', 'frontier'], repeats?: number): Promise<NewModelSuiteCost[]> {
  const out: NewModelSuiteCost[] = [];
  for (const suiteId of suites) {
    const suite = getSuite(suiteId);
    if (!suite) continue;
    const r = repeats ?? suite.repeats ?? 1;
    const est = await estimateRun({ suiteId, contestantIds: [id], repeats: r });
    const tests = resolveTests({ suiteId });
    out.push({
      suiteId,
      name: suite.name,
      tests: tests.length,
      cases: tests.reduce((s, t) => s + selectedCaseIds(t).length, 0) * r,
      estimate: { jobs: est.jobs, calls: est.calls, judgeCostUsd: est.judgeCostUsd, estCostUsd: est.estCostUsd, estCostUsdHigh: est.estCostUsdHigh, fingerprint: est.fingerprint, warnings: est.warnings },
    });
  }
  return out;
}

const isBaselineId = (id: string, label: string) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(id) || /random baseline/i.test(label);

/** Where the model lands on the combined leaderboard, plus suggested video titles. */
export function newModelHeadline(id: string, suiteId: string, board = combinedLeaderboard(suiteId)): NewModelHeadline {
  const suite = getSuite(suiteId);
  const rows = board.rows.filter((r) => r.index !== null && !isBaselineId(r.contestantId, r.label)).sort((a, b) => b.index! - a.index!);
  const i = rows.findIndex((r) => r.contestantId === id);
  const row = board.rows.find((r) => r.contestantId === id);
  const label = row?.label ?? loadContestants().find((c) => c.id === id)?.label ?? id;
  const cats = board.categories.filter((c) => row && typeof row.categoryScores[c.id] === 'number');
  const sortedCats = cats.map((c) => ({ id: c.id, name: c.name, score: row!.categoryScores[c.id] as number })).sort((a, b) => b.score - a.score);
  const rank = i >= 0 ? i + 1 : null;
  const above = i > 0 ? rows[i - 1]! : undefined;
  const tiedWithAbove = !!(above && row?.indexCi95 && above.indexCi95 && row.indexCi95[1] >= above.indexCi95[0]);
  const idx = row?.index ?? null;
  const suiteName = suite?.name ?? suiteId;
  const n = rows.length;
  let headline: string;
  if (rank === null || idx === null) headline = `${label} has no scored results on ${suiteName} yet.`;
  else if (rank === 1) headline = `${label} takes #1 on ${suiteName} with a Gauntlet Index of ${idx.toFixed(1)}${rows[1] ? `, ahead of ${rows[1].label} (${rows[1].index!.toFixed(1)})` : ''}.`;
  else headline = `${label} scores ${idx.toFixed(1)} and ranks #${rank} of ${n} on ${suiteName}${above ? `, ${tiedWithAbove ? 'statistically tied with' : 'behind'} ${above.label} (${above.index!.toFixed(1)})` : ''}.`;
  const best = sortedCats[0] ?? null;
  const worst = sortedCats.length > 1 ? sortedCats[sortedCats.length - 1]! : null;
  const titles: string[] = [];
  if (rank !== null && idx !== null) {
    if (rank === 1) titles.push(`${label} Just Took #1. I Tested It On ${board.tests.length} Brutal Tests.`);
    else if (rank <= 3) titles.push(`${label} Is Top ${rank === 2 ? '2' : '3'} — But It Didn't Beat ${rows[0]!.label}`);
    else titles.push(`I Tested ${label} On ${board.tests.length} Brutal Tests. It Ranks #${rank}.`);
    if (above) titles.push(`${label} vs ${above.label}: ${tiedWithAbove ? 'Too Close To Call?' : 'Not Even Close?'}`);
    else if (rows[1]) titles.push(`${label} vs ${rows[1].label}: The New King?`);
    if (best && best.score >= 0.7) titles.push(`${label} Is Scary Good At ${best.name}`);
    if (row && row.costPerPoint !== null && !row.manual) titles.push(`Is ${label} Worth The Money? ($${row.costPerPoint < 0.01 ? row.costPerPoint.toFixed(4) : row.costPerPoint.toFixed(2)} per point)`);
    if (worst && worst.score < 0.4) titles.push(`${label}'s Big Weakness: ${worst.name}`);
  }
  return {
    contestantId: id,
    label,
    suiteId,
    suiteName,
    index: idx,
    indexCi95: row?.indexCi95 ?? null,
    rank,
    of: n,
    above: i > 0 ? rows.slice(Math.max(0, i - 2), i).map((r) => ({ label: r.label, index: r.index })) : [],
    below: i >= 0 ? rows.slice(i + 1, i + 3).map((r) => ({ label: r.label, index: r.index })) : [],
    bestCategory: best,
    worstCategory: worst,
    costUsd: row?.totals.costUsd ?? 0,
    tiedWithAbove,
    headline,
    titles: titles.slice(0, 5),
  };
}
