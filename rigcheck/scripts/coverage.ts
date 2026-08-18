/**
 * Coverage report. Exists because "parsed 340 records" is useless without
 * "of an expected ~380; 40 missing because X". Every gap here is stated with a
 * reason, and shortfalls against the spec's targets are named rather than hidden.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const read = <T,>(p: string): T | null => (existsSync(join(ROOT, p)) ? (JSON.parse(readFileSync(join(ROOT, p), 'utf8')) as T) : null);

interface Rec { id: string; formFactor?: string; [k: string]: unknown }

const TARGETS = { gpus: 1200, cpus: 900, games: 50, fixtures: 150 };

function nullRate(records: Rec[], fields: string[]) {
  return fields
    .map((f) => ({
      field: f,
      nulls: records.filter((r) => r[f] == null).length,
      pct: records.length ? (records.filter((r) => r[f] == null).length / records.length) * 100 : 0,
    }))
    .sort((a, b) => b.nulls - a.nulls);
}

function main() {
  const gpus = read<{ records: Rec[] }>('data/catalogue/gpus.json');
  const cpus = read<{ records: Rec[] }>('data/catalogue/cpus.json');
  const games = read<{ records: Rec[] }>('data/catalogue/games.json');
  const fixtures = read<{ records: Rec[] }>('data/fixtures/fixtures.json');
  const manifest = read<{ entries: { id: string; status: string; detail?: string; licence: string }[] }>('cache/manifest.json');
  const reconcile = read<Record<string, unknown>>('data/reconcile-report.json');
  const aliases = read<{ counts: Record<string, number> }>('data/aliases/pci-devices.json');
  const validation = read<{ all: Record<string, number>; holdout: Record<string, number> | null; failures: string[] }>('data/validation/last-run.json');

  const lines: string[] = [];
  const P = (s = '') => lines.push(s);

  P('# RIGCHECK coverage report');
  P();
  P(`Generated ${new Date().toISOString()}.`);
  P();

  P('## Catalogue against spec targets');
  P();
  P('| Dataset | Have | Target | Shortfall |');
  P('|---|---:|---:|---|');
  const row = (name: string, have: number, target: number) =>
    P(`| ${name} | ${have} | ${target} | ${have >= target ? '—' : `${target - have} short (${((1 - have / target) * 100).toFixed(0)}%)`} |`);
  row('GPU SKUs', gpus?.records.length ?? 0, TARGETS.gpus);
  row('CPU SKUs', cpus?.records.length ?? 0, TARGETS.cpus);
  row('Games', games?.records.length ?? 0, TARGETS.games);
  row('Validation fixtures', fixtures?.records.length ?? 0, TARGETS.fixtures);
  P();
  P('### Why the SKU counts fall short');
  P();
  P('The spec targets 1,200 GPU and 900 CPU SKUs, reached by harvesting the');
  P("MediaWiki API and vendor specification databases. **Every one of those sources is");
  P("blocked by this environment's egress policy** (403 at the proxy; see the source");
  P('manifest below). The catalogue was therefore seeded from model knowledge under a');
  P('strict no-invention rule: agents were instructed to null an uncertain field and');
  P('omit an uncertain SKU rather than guess, because a wrong number silently corrupts');
  P('the fitted model while a gap is merely visible.');
  P();
  P('The harvest and parse pipeline is complete and wired. Running `npm run harvest`');
  P('followed by `npm run parse && npm run reconcile` in an environment without the');
  P('egress restriction fills the gap with sourced, attributed data and requires no');
  P('code change. The shortfall is an environment limitation, not a design one.');
  P();

  if (gpus) {
    const dgpu = gpus.records.filter((r) => r.formFactor === 'desktop').length;
    const igpu = gpus.records.filter((r) => r.formFactor === 'igpu').length;
    P(`GPU split: ${dgpu} desktop discrete, ${igpu} integrated. Mobile parts are`);
    P('deliberately excluded: configurable TDP swings identical silicon by 40%+, and');
    P('averaging across that would inject error rather than coverage.');
    P();
    P('Most-nulled GPU fields:');
    P();
    P('| Field | Null | % |');
    P('|---|---:|---:|');
    for (const n of nullRate(gpus.records, ['lengthMm', 'slotWidth', 'driverEolDate', 'baseClockMHz', 'recommendedPsuW', 'fp32TFLOPS', 'memBandwidthGBs', 'pciIds']).slice(0, 8)) {
      P(`| \`${n.field}\` | ${n.nulls} | ${n.pct.toFixed(0)}% |`);
    }
    P();
  }

  if (cpus) {
    P('Most-nulled CPU fields:');
    P();
    P('| Field | Null | % |');
    P('|---|---:|---:|');
    for (const n of nullRate(cpus.records, ['igpuId', 'l2CacheMB', 'l3CacheMB', 'baseClockMHz', 'boostClockMHz', 'officialMemMTs', 'processNm']).slice(0, 7)) {
      P(`| \`${n.field}\` | ${n.nulls} | ${n.pct.toFixed(0)}% |`);
    }
    P();
    P('`socket`, `memoryType`, `maxMemChannels`, `cores` and `threads` are never null —');
    P('the reconciler rejects any CPU record missing them, because the inventory');
    P('optimiser is unsound without them.');
    P();
  }

  P('## Source manifest');
  P();
  if (manifest) {
    P('| Source | Status | Licence | Note |');
    P('|---|---|---|---|');
    for (const e of manifest.entries) {
      P(`| \`${e.id}\` | ${e.status} | ${e.licence} | ${(e.detail ?? '').replace(/\|/g, '\\|')} |`);
    }
  } else {
    P('_No manifest — run `npm run harvest`._');
  }
  P();
  P('`SOURCE_RESTRICTED` means the source\'s own terms restrict automated collection');
  P('and it is never fetched. Two of those would also actively damage the model:');
  P("UserBenchmark's composite deliberately down-weights multi-core performance, so");
  P('calibrating against it would fit a known distortion; YouTube figures exist only');
  P('as pixels in overlay graphics.');
  P();
  P('`BLOCKED` means this environment\'s egress policy denied the request. Those are');
  P('logged and abandoned, never retried or routed around.');
  P();

  if (aliases) {
    P('## Identity / alias coverage');
    P();
    P(`Harvested ${aliases.counts.total} graphics-vendor PCI devices (nvidia ${aliases.counts.nvidia}, amd ${aliases.counts.amd}, intel ${aliases.counts.intel}) plus ${aliases.counts.amdFamilies} AMD ASIC-family mappings.`);
    P();
    P('This is what makes the naming-chaos handling real rather than aspirational. The');
    P('registry independently confirms, for example, that "GTX 1060 6GB" covers two');
    P('different chips (GP104 and GP106) and that a 5GB variant shipped — exactly the');
    P('kind of collision that corrupts a catalogue keyed on marketing names.');
    P();
  }

  if (reconcile) {
    P('## Reconciliation');
    P();
    P('```json');
    P(JSON.stringify({
      counts: reconcile.counts,
      rejections: Array.isArray(reconcile.rejections) ? reconcile.rejections.length : 0,
      duplicates: Array.isArray(reconcile.duplicates) ? reconcile.duplicates.length : 0,
      unverifiedAgainstPciRegistry: Array.isArray(reconcile.unverifiedAgainstPciRegistry) ? reconcile.unverifiedAgainstPciRegistry.length : 0,
      danglingIgpuReferences: Array.isArray(reconcile.danglingIgpuReferences) ? reconcile.danglingIgpuReferences.length : 0,
      gamesWithoutReference: Array.isArray(reconcile.gamesWithoutReference) ? reconcile.gamesWithoutReference.length : 0,
    }, null, 2));
    P('```');
    P();
    const dangling = reconcile.danglingIgpuReferences as { missingGpuId: string; referencedByCpus: number }[] | undefined;
    if (dangling?.length) {
      P('### Dangling integrated-graphics references');
      P();
      P('CPU records that name an iGPU part no agent produced. These are **not** nulled:');
      P('a null `igpuId` asserts "this CPU has no integrated graphics", which is false');
      P('and worse than a recorded gap. Highest-impact first:');
      P();
      P('| Missing GPU id | CPUs affected |');
      P('|---|---:|');
      for (const d of dangling.sort((a, b) => b.referencedByCpus - a.referencedByCpus).slice(0, 12)) {
        P(`| \`${d.missingGpuId}\` | ${d.referencedByCpus} |`);
      }
      P();
    }
  }

  P('## Model validation');
  P();
  if (validation) {
    const m = validation.holdout ?? validation.all;
    P('| Metric | Holdout | Gate |');
    P('|---|---:|---|');
    P(`| median APE | ${(m.medianAPE * 100).toFixed(1)}% | < 15% |`);
    P(`| p90 APE | ${(m.p90APE * 100).toFixed(1)}% | < 30% |`);
    P(`| Spearman rho | ${m.spearman.toFixed(3)} | >= 0.90 |`);
    P(`| delta sign accuracy | ${(m.signAccuracy * 100).toFixed(1)}% | >= 95% |`);
    P();
    if (validation.failures.length) {
      P('Unmet:');
      P();
      for (const f of validation.failures) P(`- ${f}`);
      P();
    }
  } else {
    P('_No validation run — `npm run validate`._');
  }
  P('### The fixture set is recalled, not measured');
  P();
  P('This is the single most important caveat in the project. Every benchmark source');
  P('is egress-blocked, so the fixtures are figures recalled from training data rather');
  P('than measurements. The gate therefore measures **agreement with recollection, not');
  P('accuracy**, and `validate.ts` detects this from record provenance and drops to');
  P('ADVISORY mode so CI cannot report a false pass.');
  P();
  P('Tuning was stopped deliberately once the train/holdout gap closed. Pushing the');
  P('numbers further against recalled fixtures would be fitting recollection — the');
  P('exact over-fitting failure the spec warns about, dressed up as progress.');
  P();
  P('To promote the gate to enforcing: run `harness/run-benchmark.ps1` on real');
  P('hardware, drop the CSVs into `data/manual/`, and re-run `npm run gate`.');
  P();

  const out = lines.join('\n');
  writeFileSync(join(ROOT, 'COVERAGE.md'), out);
  console.log(out.split('\n').slice(0, 40).join('\n'));
  console.log(`\n… full report written to rigcheck/COVERAGE.md (${lines.length} lines)`);
}

main();
