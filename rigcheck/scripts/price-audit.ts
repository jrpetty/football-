/**
 * npm run prices:audit
 *
 * For every priced part, and every part the published posts name: is the
 * figure on file a NEW price or a USED one, where did it come from, how old is
 * the part, and what should be checked on a marketplace next. Writes
 * data/pricing/PRICE-AUDIT.md and prints the checklist.
 *
 * It does not fetch anything. Marketplaces are read by a person, sold listings
 * not asking prices, and recorded with `npm run price` — this is the list of
 * what to read, in the order it matters.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { STATUS_ADVICE, STATUS_PRIORITY, driftCaveat, familyDrift, priceInversions, priceStatus, type PriceStatus } from '../src/core/priceaudit.ts';
import { ANCHOR_RAM, buildEngineData } from '../src/core/catalogue.ts';
import { applyCpuWeights, deriveCpuIndex, deriveGpuIndex } from '../src/core/indices.ts';
import { CPU_WEIGHTS } from '../src/core/constants.ts';
import { allowanceKeys } from '../src/core/components.ts';
import type { ComponentPrices } from '../src/core/planner.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const read = (p: string) => readFileSync(join(ROOT, p), 'utf8');

interface Rec { id: string; fullName: string; brand: string; variant?: string | null; launchDate?: string | null }
const gpus = (load('data/catalogue/gpus.json').records as Rec[]).map((r) => ({ ...r, kind: 'gpu' as const }));
const cpus = (load('data/catalogue/cpus.json').records as Rec[]).map((r) => ({ ...r, kind: 'cpu' as const }));
const parts = [...gpus, ...cpus];
const seedNew = load('data/pricing/gbp-new.json') as { updated: string; prices: Record<string, number> };
const seedUsed = load('data/pricing/gbp-used.json') as { updated: string; prices: Record<string, number> };
const observed = (load('data/pricing/observed.json').prices ?? []) as { partId: string; condition: 'new' | 'used'; price: number; newestDate: string; totalSamples: number; sources: string[]; series: unknown[] }[];

// Parts the published content names, by brand string, so a post can never
// quote a part the app cannot price without this report saying so.
const corpus = ['marketing/instagram.md', 'marketing/cards.json', 'marketing/builds.json', 'marketing/bottleneck.json', 'marketing/pillars.json']
  .map((f) => { try { return read(f); } catch { return ''; } }).join('\n');
// Longest brand wins at each position, and a match must stand alone:
// "Ryzen 7 5800" is not named by a post about the 5800X3D, and "GTX 1080" is
// not named by one about the 1080 Ti. Variants that share a brand string (the
// RX 580 4GB and 8GB) both count as named, which is what a post about "the
// RX 580" means.
const mentioned = new Set<string>();
{
  const esc = (t: string) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const brands = [...new Set(parts.map((r) => r.brand).filter(Boolean))].sort((a, b) => b.length - a.length);
  let masked = corpus;
  for (const b of brands) {
    const re = new RegExp(`(?<![\\w-])${esc(b)}(?![\\w-])`, 'g');
    if (re.test(masked)) {
      for (const r of parts) if (r.brand === b) mentioned.add(r.id);
      masked = masked.replace(re, (m) => '\u0000'.repeat(m.length));
    }
  }
}

const comp = load('data/pricing/components-gbp.json') as ComponentPrices & { asOf: string };
const allow = allowanceKeys(comp);
const today = new Date();
const year = (r: Rec) => { const y = Number(String(r.launchDate ?? '').slice(0, 4)); return Number.isFinite(y) && y > 1990 ? y : null; };

interface Row { id: string; name: string; kind: 'gpu' | 'cpu'; year: number | null; age: number | null; seedNew: number | null; seedUsed: number | null; obs: typeof observed; status: PriceStatus; mentioned: boolean }
const ids = new Set([...Object.keys(seedNew.prices), ...Object.keys(seedUsed.prices), ...observed.map((o) => o.partId), ...mentioned]);
const rows: Row[] = [...ids].map((id) => {
  const r = parts.find((p) => p.id === id);
  const obs = observed.filter((o) => o.partId === id);
  const y = r ? year(r) : null;
  const sn = seedNew.prices[id] ?? null, su = seedUsed.prices[id] ?? null;
  return {
    id, name: r?.fullName ?? id, kind: r?.kind ?? 'gpu', year: y, age: y ? today.getUTCFullYear() - y : null,
    seedNew: sn, seedUsed: su, obs,
    status: priceStatus({ launchYear: y, seedNew: sn, seedUsed: su, observedNew: obs.some((o) => o.condition === 'new'), observedUsed: obs.some((o) => o.condition === 'used') }, today),
    mentioned: mentioned.has(id),
  };
}).sort((a, b) => STATUS_PRIORITY[a.status] - STATUS_PRIORITY[b.status] || Number(b.mentioned) - Number(a.mentioned) || (b.age ?? 0) - (a.age ?? 0) || a.name.localeCompare(b.name));

const by = (s: PriceStatus) => rows.filter((r) => r.status === s);
const short = (r: Row) => parts.find((p) => p.id === r.id)?.brand ?? r.name;
const cmd = (r: Row, cond: 'new' | 'used') => `npm run price -- "${short(r)}" ${cond} <£> --source ebay-uk --basis sold --n <sales>`;
const describe = (r: Row) => {
  const bits: string[] = [];
  for (const o of r.obs) bits.push(`£${o.price} ${o.condition}, sourced ${o.newestDate}, ${o.totalSamples} sale${o.totalSamples === 1 ? '' : 's'}${o.series.length > 1 ? `, ${o.series.length} snapshots` : ''}`);
  if (r.seedUsed != null && !r.obs.some((o) => o.condition === 'used')) bits.push(`£${r.seedUsed} used, recalled (seed ${seedUsed.updated})`);
  if (r.seedNew != null && !r.obs.some((o) => o.condition === 'new')) bits.push(`£${r.seedNew} new, recalled (seed ${seedNew.updated})`);
  return bits.join('; ') || 'no price';
};

const md: string[] = [];
md.push(`# Price audit — ${today.toISOString().slice(0, 10)}`, '');
md.push(`Every priced part, and every part the published posts name, with whether the figure on file is a **new** price or a **used** one, where it came from, and what to check next. Generated by \`npm run prices:audit\`; nothing here was fetched. Marketplaces are read by a person — sold listings, not asking prices — and recorded with \`npm run price\`.`, '');
md.push('| status | parts | meaning |', '|---|---|---|');
for (const s of ['none', 'recalled-new-old-part', 'recalled-used', 'recalled-new', 'sourced'] as PriceStatus[]) md.push(`| ${s} | ${by(s).length} | ${STATUS_ADVICE[s]} |`);
// Inversions first: a price that contradicts the performance ordering is
// almost always a data error, and it is the only check here that can catch a
// mis-read figure before it reaches a build sheet.
{
  const data = buildEngineData({
    gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
    games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
  });
  const priced: { id: string; name: string; kind: 'gpu' | 'cpu'; index: number; price: number; condition: 'new' | 'used' }[] = [];
  for (const o of observed) {
    const g = data.gpus.get(o.partId), c = data.cpus.get(o.partId);
    if (g) priced.push({ id: g.id, name: g.fullName, kind: 'gpu', index: deriveGpuIndex(g, data.anchorGpu, ANCHOR_RAM).index.raster, price: o.price, condition: o.condition });
    // A CPU index is a vector, not a scalar: throughput, cache, latency and
    // thread capacity are weighted differently per game archetype. For an
    // ordering check any consistent weighting will do, so use the aaa-raster
    // profile — the closest thing to a general-purpose gaming weighting.
    else if (c) priced.push({ id: c.id, name: c.fullName, kind: 'cpu', index: applyCpuWeights(deriveCpuIndex(c, ANCHOR_RAM, data.anchorCpu, ANCHOR_RAM).index, CPU_WEIGHTS['aaa-raster']), price: o.price, condition: o.condition });
  }
  const inv = priceInversions(priced);
  if (inv.length) {
    md.push(`## Prices that contradict the performance ordering (${inv.length})`, '');
    md.push('A much slower part priced above a much faster one. Nearly always a data error — a mis-read figure, a bundle listed as one card, the wrong variant. Scarcity can genuinely do this to a discontinued part, so these are warnings and not rejections; check each against a listing.', '');
    md.push('| kind | slower part | faster part | premium | speed gap |', '|---|---|---|---|---|');
    for (const v of inv) md.push(`| ${v.kind} | ${v.slower.name} £${v.slower.price} (idx ${v.slower.index}) | ${v.faster.name} £${v.faster.price} (idx ${v.faster.index}) | **${v.premium}x dearer** | ${v.speedGap}x faster |`);
    md.push('');
  } else {
    md.push('## Prices that contradict the performance ordering', '', `None. All ${priced.length} sourced part price(s) sit in an order consistent with their derived performance index, compared within graphics cards and within processors.`, '');
  }
}

// Drift next: a family where one class was repriced and the rest were not is
// wrong in a way no single figure shows, and it is the most misleading state
// the price data can be in.
{
  const drifts = familyDrift(allow, observed);
  if (drifts.length) {
    md.push(`## Seed figures a sibling has overtaken (${drifts.length})`, '');
    md.push('One class in each family below was repriced from real listings and moved a long way; its siblings are still on the recalled figure and nobody has checked them. A build using an unchecked sibling is cheaper on paper than in a shop, and the published copy says so with the multiple.', '');
    md.push('| family | repriced | moved | still recalled | check |', '|---|---|---|---|---|');
    for (const d of drifts) {
      md.push(`| ${d.category} | \`${d.sourcedKey}\` £${d.seedPrice} → £${d.sourcedPrice} | **${d.factor}x** | ${d.unsourced.map((k) => `\`${k}\``).join(', ')} | \`npm run price -- --id ${d.unsourced[0]} new <£> --basis retail --source <shop>\` |`);
    }
    md.push('', `Caveat currently required in published copy: *${driftCaveat(drifts[0], (k) => allow.find((x) => x.key === k)?.label ?? k)}*`, '');
  }
}

md.push('', `Parts named in published posts: ${rows.filter((r) => r.mentioned).length}. Of those with no price at all: ${rows.filter((r) => r.mentioned && r.status === 'none').length}.`, '');

const section = (title: string, list: Row[], note: string, cond: (r: Row) => 'new' | 'used') => {
  if (!list.length) return;
  md.push(`## ${title} (${list.length})`, '', note, '', '| part | launched | on file | check |', '|---|---|---|---|');
  for (const r of list) md.push(`| ${r.name}${r.mentioned ? ' **·in posts**' : ''} | ${r.year ?? '?'}${r.age != null ? ` (${r.age}y)` : ''} | ${describe(r)} | \`${cmd(r, cond(r))}\` |`);
  md.push('');
};
section('No price at all', by('none'), 'The planner cannot consider these and the posts cannot quote them. The ones marked **in posts** already appear in published content, so a follower can ask what one costs and the app has no answer.', (r) => (r.age != null && r.age >= 4 ? 'used' : 'new'));
section('Recalled NEW price on an old part', by('recalled-new-old-part'), 'A launch-era number on a part that sells used. Under the resale-only rule a used question about these gets "no resale price recorded" until a real one is entered — which is the truth, and also the reason to record one.', () => 'used');
section('Recalled USED price', by('recalled-used'), 'These are the figures most likely to be wrong by the most: undated, recalled, on parts whose value only falls. Read sold listings, take the median, record how many sales. Oldest parts first.', () => 'used');
section('Recalled NEW price on a recent part', by('recalled-new'), 'Plausible, unsourced, undated. A retail price with a date replaces each one; a sold-listing used price beside it is what the upgrade advisor actually wants.', () => 'new');
section('Sourced', by('sourced'), 'On record from a real observation. A second snapshot on a later date is what turns each into a trend.', (r) => (r.obs[0]?.condition ?? 'used'));

const obsFor = (id: string) => observed.filter((o) => o.partId === id);
md.push(`## Component allowances (${allow.length})`, '');
md.push(`Motherboards, memory, storage, power supplies, cases and coolers are not modelled part by part. The planner budgets a class — "a competent AM4 board", "a 650W Gold supply" — from this table, recalled in ${comp.asOf}. A price recorded against the key replaces the recalled figure for the whole class, which is the grain the planner works at. Cases here are airflow tiers; the ${(load('data/catalogue/cases.json').records as unknown[]).length} named cases in the catalogue carry fit data only and no price.`, '');
md.push('| key | class | on file | check |', '|---|---|---|---|');
for (const k of allow) {
  const o = obsFor(k.key);
  const onFile = o.length ? o.map((x) => `£${x.price} ${x.condition}, sourced ${x.newestDate}`).join('; ') : `£${k.price} new, recalled (seed ${comp.asOf})`;
  md.push(`| \`${k.key}\` | ${k.label} | ${onFile} | \`npm run price -- --id ${k.key} new <£> --basis retail --source <shop>\` |`);
}
md.push('');
const monitors = load('data/catalogue/monitors.json').records as { id: string; fullName: string; typicalPriceGBP: number | null; releaseYear: number | null }[];
md.push(`## Monitors (${monitors.length})`, '');
md.push('Each monitor record carries a recalled typical price. None is sourced. Record a retail price against the monitor id and it will be reported here as sourced.', '');
md.push('| monitor | released | on file | check |', '|---|---|---|---|');
for (const m of monitors) {
  const o = obsFor(m.id);
  const onFile = o.length ? o.map((x) => `£${x.price} ${x.condition}, sourced ${x.newestDate}`).join('; ') : m.typicalPriceGBP != null ? `£${m.typicalPriceGBP} new, recalled` : 'no price';
  md.push(`| ${m.fullName} | ${m.releaseYear ?? '?'} | ${onFile} | \`npm run price -- --id ${m.id} new <£> --basis retail --source <shop>\` |`);
}
md.push('');
const sourcedAllow = allow.filter((k) => obsFor(k.key).length).length, sourcedMon = monitors.filter((m) => obsFor(m.id).length).length;
md.splice(md.indexOf('') + 1, 0); // no-op keeps structure explicit
md[3] = md[3]; // summary table stays as is

writeFileSync(join(ROOT, 'data/pricing/PRICE-AUDIT.md'), md.join('\n') + '\n');

// --- terminal --------------------------------------------------------------
console.log(`price audit — ${rows.length} parts\n`);
for (const s of ['none', 'recalled-new-old-part', 'recalled-used', 'recalled-new', 'sourced'] as PriceStatus[]) console.log(`  ${String(by(s).length).padStart(4)}  ${s.padEnd(24)} ${STATUS_ADVICE[s]}`);
const first = rows.filter((r) => r.status !== 'sourced').slice(0, 12);
console.log(`\nstart here — the ${first.length} that matter most:\n`);
for (const r of first) console.log(`  ${r.name.padEnd(34)} ${String(r.year ?? '?').padEnd(6)} ${describe(r)}${r.mentioned ? '   ← in posts' : ''}`);
console.log(`\ncomponent allowances: ${sourcedAllow} of ${allow.length} sourced · monitors: ${sourcedMon} of ${monitors.length} sourced · cases: ${(load('data/catalogue/cases.json').records as unknown[]).length} in the catalogue, priced by airflow tier`);
console.log(`\nfull list with commands: data/pricing/PRICE-AUDIT.md`);
