/**
 * `node src/cli.ts newmodel` — the New Model Day wizard for the terminal.
 * Interactive by default; every question can be answered with a flag.
 * Never spends money without showing the estimate and getting a "yes"
 * (or --yes). In a non-interactive shell without --yes it stops before the
 * first paid call.
 */
import { createInterface } from 'node:readline/promises';
import { getProvider, loadProviders } from '../core/config.ts';
import { startRun, subscribe, waitForRun } from '../engine/runner.ts';
import { readResults } from '../engine/store.ts';
import { discoverModels } from '../providers/index.ts';
import { newModelCosts, newModelHeadline, pingModel, prepareNewModel } from './newmodel.ts';
import type { NewModelHeadline, NewModelInput } from './types.ts';

export interface WizardIO {
  interactive: boolean;
  ask(question: string, def?: string): Promise<string>;
  confirm(question: string): Promise<boolean>;
  log(line: string): void;
}

export function terminalIO(): WizardIO {
  const interactive = !!process.stdin.isTTY;
  return {
    interactive,
    async ask(question, def) {
      if (!interactive) return def ?? '';
      const rl = createInterface({ input: process.stdin, output: process.stdout });
      const a = (await rl.question(`${question}${def ? ` [${def}]` : ''}: `)).trim();
      rl.close();
      return a || def || '';
    },
    async confirm(question) {
      if (!interactive) return false;
      const rl = createInterface({ input: process.stdin, output: process.stdout });
      const a = (await rl.question(`${question} [y/N] `)).trim().toLowerCase();
      rl.close();
      return a === 'y' || a === 'yes';
    },
    log: (line) => console.log(line),
  };
}

const money = (v: number) => (v === 0 ? '$0' : v < 0.01 ? `$${v.toFixed(4)}` : `$${v.toFixed(2)}`);
const str = (v: string | boolean | undefined) => (typeof v === 'string' ? v : undefined);
const numFlag = (v: string | boolean | undefined) => (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : undefined);

export interface WizardOutcome {
  contestantId?: string;
  runIds: string[];
  spentUsd: number;
  headlines: NewModelHeadline[];
  /** Why the wizard stopped early, if it did. */
  stopped?: string;
}

export async function runNewModelWizard(flags: Record<string, string | boolean>, io: WizardIO = terminalIO()): Promise<WizardOutcome> {
  const yes = flags.yes === true;
  const out: WizardOutcome = { runIds: [], spentUsd: 0, headlines: [] };
  const stop = (why: string) => {
    io.log(why);
    out.stopped = why;
    return out;
  };
  io.log('NEW MODEL DAY — add a model, check it works, price it, run it, rank it.\n');

  // 1. Provider + model id
  const providers = loadProviders().filter((p) => p.type !== 'mock');
  let provider = str(flags.provider);
  if (!provider) {
    io.log(`Providers: ${providers.map((p) => p.id).join(', ')}`);
    provider = await io.ask('Provider id', 'openai');
  }
  if (!providers.some((p) => p.id === provider)) return stop(`Unknown provider "${provider}". Choose one of: ${providers.map((p) => p.id).join(', ')}`);
  let model = str(flags.model);
  if (!model) {
    if (io.interactive) {
      try {
        const ids = await discoverModels(provider!);
        if (ids.length) io.log(`Models your key can use on ${getProvider(provider!).label} (newest names usually last):\n  ${ids.slice(-30).join('\n  ')}`);
      } catch (err) {
        io.log(`(Model discovery unavailable: ${(err as Error).message})`);
      }
    }
    model = await io.ask('Model id exactly as the API expects it');
  }
  if (!model) return stop('No model id given (use --model).');
  const label = str(flags.label) ?? (await io.ask('Display name', str(flags.label) ?? ''));

  // 2. Prices
  let inputPerM = numFlag(flags['input-price']);
  let outputPerM = numFlag(flags['output-price']);
  if (inputPerM === undefined) inputPerM = numFlag(await io.ask('Input price, USD per 1M tokens (0 = free/local)'));
  if (outputPerM === undefined) outputPerM = numFlag(await io.ask('Output price, USD per 1M tokens'));
  if (inputPerM === undefined || outputPerM === undefined) return stop('Prices are required: pass --input-price and --output-price (USD per 1M tokens).');
  const input: NewModelInput = {
    provider: provider!,
    model,
    label: label || undefined,
    id: str(flags.id),
    vendor: str(flags.vendor),
    family: str(flags.family),
    tier: (['flagship', 'mid', 'small'] as const).find((t) => t === flags.tier),
    releaseDate: str(flags['release-date']) ?? new Date().toISOString().slice(0, 10),
    inputPerM,
    outputPerM,
    cachedInputPerM: numFlag(flags['cached-price']),
    pricesVerified: flags['prices-verified'] === true,
  };

  // 3. Add to config/models.json (+ discovery check, free)
  const prep = await prepareNewModel(input);
  const c = prep.contestant;
  out.contestantId = c.id;
  io.log(`\n${prep.created ? 'Added' : 'Updated'} ${c.label} (id ${c.id}) · ${c.vendor} · ${c.family ?? ''} ${c.tier ?? ''} · $${c.pricing.inputPerM}/$${c.pricing.outputPerM} per 1M tokens${c.pricing.verifiedAt ? '' : ' (UNVERIFIED)'}`);
  io.log(prep.discovered === true ? `✓ "${c.model}" is listed by ${getProvider(c.provider).label}.` : prep.discovered === false ? `⚠ "${c.model}" is not in the provider's model list.` : `(Could not check the model list${prep.discoverError ? `: ${prep.discoverError}` : ''})`);
  for (const w of prep.warnings) io.log(`⚠ ${w}`);

  // 4. Ping (tiny paid call)
  if (flags['no-ping'] !== true) {
    const ok = yes || (await io.confirm('\nSend a one-word test message to check it works (costs < $0.01)?'));
    if (!ok) return stop(io.interactive ? 'Stopped before the test message. Nothing was spent.' : 'Not a terminal: pass --yes to continue. Nothing was spent.');
    const p = await pingModel(c.id);
    if (!p.ok) return stop(`✗ The model did not answer: ${p.error}\nCheck the model id and your API key, then run the wizard again.`);
    out.spentUsd += p.costUsd ?? 0;
    io.log(`✓ It answered "${(p.text ?? '').trim().slice(0, 40)}" in ${((p.totalMs ?? 0) / 1000).toFixed(1)} s (${money(p.costUsd ?? 0)}; served by ${p.servedModel}).`);
  }

  // 5. Costs of quick / core / frontier
  const repeats = numFlag(flags.repeats);
  const costs = await newModelCosts(c.id, ['quick', 'core', 'frontier'], repeats);
  io.log('\nWhat each suite would cost for this model:');
  for (const s of costs) io.log(`  ${s.suiteId.padEnd(10)} ${String(s.tests).padStart(3)} tests ${String(s.cases).padStart(5)} cases   ~${money(s.estimate.estCostUsd).padStart(8)}   (up to ${money(s.estimate.estCostUsdHigh)})`);

  // 6. Which suites, and the cap
  let suiteArg = str(flags.suites) ?? str(flags.suite);
  if (!suiteArg) suiteArg = io.interactive ? await io.ask('Suites to run (comma-separated, or "none")', 'quick') : 'quick';
  if (suiteArg === 'none') return stop('No suites chosen. The model is saved; run it later from New Run.');
  const chosen = suiteArg.split(',').map((s) => s.trim()).filter(Boolean);
  const plan = chosen.map((id) => costs.find((x) => x.suiteId === id));
  if (plan.some((p) => !p)) return stop(`Unknown suite in "${suiteArg}". Choose from: ${costs.map((x) => x.suiteId).join(', ')}`);
  const estimate = plan.reduce((s, p) => s + p!.estimate.estCostUsd, 0);
  const high = plan.reduce((s, p) => s + p!.estimate.estCostUsdHigh, 0);
  const cap = numFlag(flags['max-cost']) ?? Math.max(0.05, Math.ceil(high * 1.1 * 100) / 100);
  io.log(`\nPlan: ${chosen.join(' + ')} · estimated ${money(estimate)} (up to ${money(high)}) · hard spending cap ${money(cap)}`);
  for (const w of new Set(plan.flatMap((p) => p!.estimate.warnings))) io.log(`⚠ ${w}`);
  const go = yes || (await io.confirm(`Spend up to ${money(cap)} on this?`));
  if (!go) return stop(io.interactive ? 'Aborted. Nothing more was spent.' : 'Not a terminal: pass --yes to start the runs. Nothing more was spent.');

  // 7. Run each suite under the remaining cap
  let remaining = cap;
  for (const suiteId of chosen) {
    if (remaining <= 0) {
      io.log(`Spending cap reached: skipping ${suiteId}.`);
      break;
    }
    const runId = await startRun({ suiteId, contestantIds: [c.id], repeats, maxCostUsd: remaining, name: `New model day: ${c.label} (${suiteId})` });
    out.runIds.push(runId);
    io.log(`\nRun ${runId} started (${suiteId})…`);
    const unsub = subscribe(runId, (e) => {
      if (e.type === 'run.progress' && (e.completed % 10 === 0 || e.completed === e.total)) io.log(`  ${e.completed}/${e.total} · spent ${money(e.costUsd)}`);
    });
    await waitForRun(runId);
    unsub();
    const spent = readResults(runId).reduce((s, r) => s + r.metrics.costUsd + r.metrics.judgeCostUsd, 0);
    out.spentUsd += spent;
    remaining -= spent;
  }

  // 8. Headline + rank + titles
  for (const suiteId of chosen) {
    const h = newModelHeadline(c.id, suiteId);
    out.headlines.push(h);
    io.log(`\n━━ ${h.suiteName} ━━\n${h.headline}`);
    if (h.rank !== null) {
      for (const a of h.above) io.log(`   ↑ ${a.label} ${a.index?.toFixed(1)}`);
      io.log(`   → ${h.label} ${h.index?.toFixed(1)}  (#${h.rank} of ${h.of})`);
      for (const b of h.below) io.log(`   ↓ ${b.label} ${b.index?.toFixed(1)}`);
      if (h.bestCategory) io.log(`   Best at ${h.bestCategory.name} (${Math.round(h.bestCategory.score * 100)}), weakest at ${h.worstCategory?.name ?? '—'} (${h.worstCategory ? Math.round(h.worstCategory.score * 100) : '—'}).`);
      if (h.titles.length) io.log(`   Video title ideas:\n${h.titles.map((t) => `     • ${t}`).join('\n')}`);
    }
  }
  io.log(`\nTotal spent: ${money(out.spentUsd)}. Open the dashboard (Leaderboard / Presenter) to see the full results.`);
  return out;
}
