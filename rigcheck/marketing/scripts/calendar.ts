/**
 * npm run marketing:calendar [-- --only YYYY-MM-DD]
 *
 * Four weeks of posts from rotation.json into dated folders under
 * marketing/calendar/, each holding the image(s), the story image(s), the
 * caption and a meta.json naming what the image shows. Existing posts are
 * copied from the rendered set; builds, versus and the poll are generated
 * against the data as it is today. Every folder is checked before the run is
 * called done, and the caption verifier runs last.
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { expandRotation, parsePosts, rotating, type Rotation } from './lib/rotation.ts';
import { loadPlanContext, planTier } from './plans.ts';
import { versusData } from './lib/versus.ts';
import { rigData } from './lib/rig.ts';
import { buildCaption, memoryCaveat, pollCaption, rigCaption, versusCaption, DISCLAIMER } from './lib/captions.ts';
import { allowanceKeys } from '../../src/core/components.ts';
import { buildCard, pollCard, renderCards, rigCard, unesc, versusCard } from './cardlib.mjs';
import type { RamConfig, Resolution } from '../../src/core/types.ts';

const args = process.argv.slice(2);
const only = args.includes('--only') ? args[args.indexOf('--only') + 1] : undefined;
const OUT = 'marketing/calendar';
const json = (f: string) => JSON.parse(readFileSync(f, 'utf8'));
const rot = json('marketing/rotation.json') as Rotation;
const manifest = json('marketing/cards.json') as { name: string; file: string; story: string; subject: string }[];
const posts = parsePosts(readFileSync('marketing/instagram.md', 'utf8'));
const requests = json('data/catalogue/requests.json');
const observedPrices = (json('data/pricing/observed.json').prices ?? []) as { partId: string; price: number }[];
const memCaveat = memoryCaveat(allowanceKeys(json('data/pricing/components-gbp.json')), observedPrices);
const ctx = loadPlanContext();

interface Meta { date: string; kind: string; title: string; subjects: string[]; images: string[]; stories: string[] }
const problems: string[] = [];
const days = expandRotation(rot).filter((d) => !only || d.date === only);
if (only && !days.length) { console.error(`${only} is not in the rotation (${rot.start} + ${rot.weeks} weeks)`); process.exit(1); }

for (const d of days) {
  const dir = `${OUT}/${d.date}`;
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  const meta: Meta = { date: d.date, kind: d.kind, title: '', subjects: [], images: [], stories: [] };
  let caption = '';
  const subj = (name: string) => manifest.find((m) => m.name === name)?.subject ?? '';

  if (d.kind === 'post') {
    const id = rotating(d.slot.rotate, d.index)!;
    const p = posts.find((x) => x.id === id);
    if (!p) { problems.push(`${d.date}: ${id} is not in instagram.md`); continue; }
    meta.title = `${p.id} — ${p.title}`;
    p.images.forEach((img, i) => {
      const name = img.replace(/\.png$/, '');
      const src = `marketing/images/${img}`, story = `marketing/images/story/${img}`;
      if (!existsSync(src)) { problems.push(`${d.date}: ${src} has not been rendered`); return; }
      copyFileSync(src, `${dir}/${String(i + 1).padStart(2, '0')}.png`); meta.images.push(`${String(i + 1).padStart(2, '0')}.png`);
      if (existsSync(story)) { copyFileSync(story, `${dir}/story-${String(i + 1).padStart(2, '0')}.png`); meta.stories.push(`story-${String(i + 1).padStart(2, '0')}.png`); }
      meta.subjects.push(subj(name));
    });
    caption = `${p.caption}\n\n${p.hashtags}`;
  } else if (d.kind === 'story') {
    const name = rotating(d.slot.rotate, d.index)!;
    const story = `marketing/images/story/${name}.png`;
    if (!existsSync(story)) { problems.push(`${d.date}: ${story} has not been rendered`); continue; }
    copyFileSync(story, `${dir}/story-01.png`); meta.stories.push('story-01.png'); meta.subjects.push(subj(name));
    meta.title = `story — ${name}`;
    const owner = posts.find((p) => p.images.includes(`${name}.png`));
    caption = owner ? `${owner.caption}\n\n${owner.hashtags}` : `${subj(name)}\n\n${DISCLAIMER}`;
  } else if (d.kind === 'build') {
    const budget = rotating(d.slot.budgets, d.index)!;
    const resolution = (Array.isArray(d.slot.resolution) ? rotating(d.slot.resolution, d.index) : d.slot.resolution) as Resolution;
    const refreshHz = rotating(d.slot.refreshHz, d.index) ?? 144;
    const b = planTier({ name: `The £${budget.toLocaleString('en-GB')} one`, budget, resolution, refreshHz }, ctx);
    if (!b) { problems.push(`${d.date}: the planner could not fill £${budget} at ${resolution}`); continue; }
    const card = buildCard(b);
    await renderCards([{ name: '01', ...card }], { dir, format: 'post' });
    await renderCards([{ name: 'story-01', ...card }], { dir, format: 'story' });
    meta.title = `build of the week — £${b.total}`; meta.images.push('01.png'); meta.stories.push('story-01.png'); meta.subjects.push(unesc(card.subject));
    writeFileSync(`${dir}/data.json`, JSON.stringify(b, null, 2));
    caption = buildCaption(b, memCaveat);
  } else if (d.kind === 'versus') {
    const pair = rotating(d.slot.pairs, d.index)!;
    let v;
    try { v = versusData(pair[0], pair[1], ctx.data, { resolution: d.slot.resolution as Resolution | undefined }); }
    catch (e) { problems.push(`${d.date}: ${(e as Error).message}`); continue; }
    const post = versusCard(v), story = versusCard(v, { format: 'story' });
    await renderCards([{ name: '01', ...post }], { dir, format: 'post' });
    await renderCards([{ name: 'story-01', ...story }], { dir, format: 'story' });
    meta.title = `versus — ${v.a.short} vs ${v.b.short}`; meta.images.push('01.png'); meta.stories.push('story-01.png'); meta.subjects.push(unesc(post.subject));
    writeFileSync(`${dir}/data.json`, JSON.stringify(v, null, 2));
    caption = versusCaption(v);
  } else if (d.kind === 'rig') {
    /* A real machine somebody owns, run through the same pairing analysis the
       `npm run analyse` command uses. The rotation seeds it with common older
       pairings; in practice the week's entry gets replaced by whatever spec
       turned up in the comments, which is the whole point of the format. */
    const spec = rotating(d.slot.rigs, d.index)!;
    const ramType = (spec.ramType ?? 'DDR4') as RamConfig['type'];
    const ram: RamConfig = { totalGB: spec.ram ?? 16, channels: 2, speedMTs: ramType === 'DDR5' ? 6000 : 3200, type: ramType };
    let r;
    try { r = rigData(spec.cpu, spec.gpu, ctx.data, { resolution: (d.slot.resolution as Resolution) ?? '1080p', ram }); }
    catch (e) { problems.push(`${d.date}: ${(e as Error).message}`); continue; }
    const post = rigCard(r), story = rigCard(r, { format: 'story' });
    await renderCards([{ name: '01', ...post }], { dir, format: 'post' });
    await renderCards([{ name: 'story-01', ...story }], { dir, format: 'story' });
    meta.title = `check my rig — ${r.cpu} + ${r.gpu}`; meta.images.push('01.png'); meta.stories.push('story-01.png'); meta.subjects.push(unesc(post.subject));
    writeFileSync(`${dir}/data.json`, JSON.stringify(r, null, 2));
    caption = rigCaption(r);
  } else if (d.kind === 'poll') {
    const card = pollCard(requests, { asOf: d.date });
    await renderCards([{ name: '01', ...card }], { dir, format: 'post' });
    await renderCards([{ name: 'story-01', ...card }], { dir, format: 'story' });
    const open = [...requests.requests].filter((r: { status: string }) => r.status === 'open').sort((a: { votes: number }, b: { votes: number }) => b.votes - a.votes).slice(0, 6);
    meta.title = 'which game next'; meta.images.push('01.png'); meta.stories.push('story-01.png'); meta.subjects.push(unesc(card.subject));
    caption = pollCaption(open, d.date);
  }

  // The image travels with its subject: the caption carries the sentence
  // printed on the card, after the caption proper.
  caption = `${caption.trim()}\n\n—\n${meta.subjects.map((s) => `shows: ${s}`).join('\n')}\n`;
  writeFileSync(`${dir}/caption.txt`, caption);
  writeFileSync(`${dir}/meta.json`, JSON.stringify(meta, null, 2) + '\n');
}

// --- checks --------------------------------------------------------------------
for (const d of days) {
  const dir = `${OUT}/${d.date}`;
  if (!existsSync(`${dir}/meta.json`)) continue;
  const meta = json(`${dir}/meta.json`) as Meta;
  const cap = readFileSync(`${dir}/caption.txt`, 'utf8');
  for (const f of [...meta.images, ...meta.stories]) if (!existsSync(`${dir}/${f}`)) problems.push(`${d.date}: ${f} listed but missing`);
  if (!meta.images.length && !meta.stories.length) problems.push(`${d.date}: nothing to post`);
  for (const s of meta.subjects) if (!cap.includes(`shows: ${s}`)) problems.push(`${d.date}: caption does not carry the card's subject`);
  if (/\b(undefined|null|NaN)\b/.test(cap)) problems.push(`${d.date}: caption contains undefined/null/NaN`);
  if (existsSync(`${dir}/data.json`)) {
    const data = json(`${dir}/data.json`);
    const rows: { game: string; fps?: number | null; a?: number | null; b?: number | null }[] = data.rows ?? [];
    for (const m of cap.matchAll(/^· (.+?) — (\d+)fps$/gm)) {
      if (!rows.some((r) => r.game === m[1] && r.fps === Number(m[2]))) problems.push(`${d.date}: "${m[0]}" is not in data.json`);
    }
    for (const m of cap.matchAll(/^· (.+?) — (\d+) vs (\d+)fps/gm)) {
      if (!rows.some((r) => r.game === m[1] && r.a === Number(m[2]) && r.b === Number(m[3]))) problems.push(`${d.date}: "${m[0]}" is not in data.json`);
    }
    // A rig caption reads "· Game — 42 → 88fps (+110%)". Both ends and the
    // percentage are checked, because the percentage is the number that gets
    // quoted back and it is the easiest one to leave stale.
    for (const m of cap.matchAll(/^· (.+?) — (\d+) → (\d+)fps \(([-+]\d+)%\)$/gm)) {
      const row = (rows as unknown as { game: string; before?: number; after?: number; gainPct?: number }[])
        .find((r) => r.game === m[1]);
      if (!row || row.before !== Number(m[2]) || row.after !== Number(m[3]) || row.gainPct !== Number(m[4])) {
        problems.push(`${d.date}: "${m[0]}" is not in data.json`);
      }
    }
  }
}

console.log(`\n${days.length} day(s) written under ${OUT}/`);
for (const d of days) if (existsSync(`${OUT}/${d.date}/meta.json`)) { const m = json(`${OUT}/${d.date}/meta.json`) as Meta; console.log(`  ${m.date}  ${d.weekday}  ${m.kind.padEnd(7)} ${m.title}`); }
if (problems.length) { console.log(`\n${problems.length} problem(s):`); for (const p of problems) console.log(`  ${p}`); }
try { execFileSync('npx', ['tsx', 'marketing/scripts/verify.mjs'], { stdio: 'pipe' }); console.log('\ncaption verifier: PASS'); }
catch (e) { problems.push('caption verifier failed'); console.log(`\ncaption verifier: FAIL\n${String((e as { stdout?: Buffer }).stdout ?? '')}`); }
process.exit(problems.length ? 1 : 0);
