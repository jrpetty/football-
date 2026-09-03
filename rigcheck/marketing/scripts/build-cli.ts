/**
 * npm run marketing:build -- --budget 900 [--resolution 1440p] [--refresh 144] [--games id,id]
 * Plans one build and renders its card in both formats, with a caption.
 */
import { mkdirSync, readFileSync } from 'node:fs';
import { loadPlanContext, planTier } from './plans.ts';
import { buildCaption } from './lib/captions.ts';
import { renderCards, buildCard, unesc } from './cardlib.mjs';
import type { Resolution } from '../../src/core/types.ts';

const args = process.argv.slice(2);
const flags: Record<string, string> = {};
for (let i = 0; i < args.length; i++) if (args[i].startsWith('--')) { flags[args[i].slice(2)] = args[i + 1] ?? ''; i++; }
const budget = Number(flags.budget);
if (!Number.isFinite(budget) || budget < 300) { console.error('usage: npm run marketing:build -- --budget 900 [--resolution 1440p] [--refresh 144] [--games id,id]'); process.exit(1); }
const ctx = loadPlanContext();
const b = planTier({ name: `The £${budget.toLocaleString('en-GB')} one`, budget, resolution: (flags.resolution as Resolution) ?? '1440p', refreshHz: Number(flags.refresh ?? 144), games: flags.games?.split(',') }, ctx);
if (!b) { console.error(`the planner could not fill £${budget} at those settings`); process.exit(1); }
const name = `build-${budget}`;
mkdirSync('marketing/images/story', { recursive: true });
await renderCards([{ name, ...buildCard(b) }], { dir: 'marketing/images', format: 'post' });
await renderCards([{ name, ...buildCard(b) }], { dir: 'marketing/images/story', format: 'story' });
console.log(`marketing/images/${name}.png\nmarketing/images/story/${name}.png\n`);
console.log(`shows: ${unesc(buildCard(b).subject)}\n`);
console.log(buildCaption(b, new Set<string>((JSON.parse(readFileSync('data/pricing/observed.json', 'utf8')).prices ?? []).map((o: { partId: string }) => o.partId))));
