/**
 * Game requests: the queue followers vote on, and the path from a vote to a
 * catalogue record.
 *
 *   npm run game -- request "Helldivers 2" [--votes 3] [--by @handle]
 *   npm run game -- vote helldivers-2 [count]
 *   npm run game -- list
 *   npm run game -- scaffold helldivers-2      writes a skeleton record to fill in
 *   npm run game -- promote helldivers-2       checks it is complete and adds it
 *
 * The game record format is fixed, so adding a game is data entry. The
 * scaffold writes every field the engine needs with a null where a value must
 * go and a checklist of what each one means; promote refuses anything still
 * null, refuses a game with no reference figures (the engine would answer
 * NO_ESTIMATE for it), and only then appends to games.json and references.json.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const REQ = join(ROOT, 'data/catalogue/requests.json');
const PENDING = join(ROOT, 'data/manual/games-pending');

export interface GameRequest { slug: string; title: string; votes: number; requestedOn: string; by?: string; status: 'open' | 'scaffolded' | 'added' }
export interface Requests { note: string; requests: GameRequest[] }

export const slugify = (t: string) => t.toLowerCase().replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

export function addRequest(reqs: Requests, title: string, votes = 1, by?: string, today = new Date().toISOString().slice(0, 10)): Requests {
  const slug = slugify(title);
  if (!slug) throw new Error('a request needs a title');
  const existing = reqs.requests.find((r) => r.slug === slug);
  if (existing) { existing.votes += votes; return reqs; }
  reqs.requests.push({ slug, title: title.trim(), votes: Math.max(1, votes), requestedOn: today, by, status: 'open' });
  return reqs;
}

export function vote(reqs: Requests, slug: string, count = 1): Requests {
  const r = reqs.requests.find((x) => x.slug === slug);
  if (!r) throw new Error(`no request "${slug}" — run: npm run game -- list`);
  r.votes += Math.max(1, count);
  return reqs;
}

/** The record shape the engine reads, with a checklist where values go. */
export function scaffoldRecord(slug: string, title: string) {
  return {
    id: slug,
    name: title,
    year: null,
    engine: null,
    api: null,
    archetype: null,
    builtInBenchmark: null,
    coreLoop: true,
    fpsCap: null,
    requirements: { meshShaders: null, minDxFeatureLevel: null, minShaderModel: null, rayTracingRequired: null, minVramGB: null, minThreads: null, minCores: null, minSystemRamGB: null },
    vramDemandGB: { '1080p': null, '1440p': null, '2160p': null },
    upscalingSupport: null,
    notes: '',
    _prov: { '*': ['operator-entered'] },
    _checklist: {
      year: 'release year, a number',
      engine: 'engine name, free text',
      api: 'list, e.g. ["dx12"] or ["dx12","vulkan"]',
      archetype: 'one of: esports | sim-cpu | aaa-raster | aaa-rt',
      builtInBenchmark: 'true if the game ships a benchmark mode',
      fpsCap: 'a number if the engine caps frame rate; leave null for no cap',
      requirements: 'the PUBLISHED minimum spec: feature level like "12_0", shader model like "6_0", VRAM/threads/cores/system RAM as numbers. Leave a minimum null only when the publisher states none',
      vramDemandGB: 'observed VRAM use at high preset per resolution, in GB, decimals fine',
      upscalingSupport: 'list from: none | dlss | fsr | xess | tsr',
      references: 'fill the matching <slug>.references.json — the engine gives NO_ESTIMATE without it',
    },
  };
}

export function scaffoldReferences(slug: string) {
  return {
    gameId: slug,
    gpuBound: { '1080p': null, '1440p': null, '2160p': null },
    cpuBound: null,
    gpuScalingExponent: 0.65,
    confidence: 'operator-entered',
    _prov: { '*': ['operator-entered'] },
    _checklist: {
      gpuBound: 'average fps on the anchor graphics card at each resolution, high preset, with a processor that is not the limit',
      cpuBound: 'average fps on the anchor processor when the graphics card is not the limit',
      gpuScalingExponent: 'how fps scales with GPU throughput; 0.65 is the catalogue default, leave it unless you have a reason',
    },
  };
}

/**
 * Fields the catalogue itself leaves null, so a scaffold may too: no frame
 * cap, and minimums the publisher never stated. Measured from the real
 * records — 46 of 50 have no cap, 7 state no core or thread minimum.
 */
export const OPTIONAL_NULL = new Set(['fpsCap', 'requirements.minCores', 'requirements.minThreads', 'requirements.minVramGB', 'requirements.minShaderModel', 'requirements.minSystemRamGB']);

/** Every path whose value is still null and must not be. Empty means complete. */
export function missingFields(obj: unknown, path = ''): string[] {
  if (obj === null) return OPTIONAL_NULL.has(path) ? [] : [path || '(root)'];
  if (Array.isArray(obj)) return [];
  if (typeof obj === 'object') {
    return Object.entries(obj as Record<string, unknown>)
      .filter(([k]) => !k.startsWith('_'))
      .flatMap(([k, v]) => missingFields(v, path ? `${path}.${k}` : k));
  }
  return [];
}

const readJson = (p: string) => JSON.parse(readFileSync(p, 'utf8'));
const writeJson = (p: string, v: unknown) => writeFileSync(p, JSON.stringify(v, null, 2) + '\n');

function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  const flags: Record<string, string> = {}; const pos: string[] = [];
  for (let i = 0; i < rest.length; i++) { if (rest[i].startsWith('--')) { flags[rest[i].slice(2)] = rest[i + 1] ?? ''; i++; } else pos.push(rest[i]); }
  const reqs = readJson(REQ) as Requests;

  if (cmd === 'request') {
    const title = pos.join(' ');
    addRequest(reqs, title, Number(flags.votes ?? 1), flags.by);
    writeJson(REQ, reqs);
    const r = reqs.requests.find((x) => x.slug === slugify(title))!;
    console.log(`${r.title}  (${r.slug})  ${r.votes} vote${r.votes === 1 ? '' : 's'}`);
  } else if (cmd === 'vote') {
    vote(reqs, pos[0], Number(pos[1] ?? 1)); writeJson(REQ, reqs);
    console.log(reqs.requests.find((x) => x.slug === pos[0]));
  } else if (cmd === 'list') {
    const rows = [...reqs.requests].sort((a, b) => b.votes - a.votes);
    if (!rows.length) console.log('no requests yet — npm run game -- request "Title"');
    for (const r of rows) console.log(`${String(r.votes).padStart(4)}  ${r.slug.padEnd(32)} ${r.title}  [${r.status}]`);
  } else if (cmd === 'scaffold') {
    const slug = pos[0]; const r = reqs.requests.find((x) => x.slug === slug);
    if (!r) throw new Error(`no request "${slug}"`);
    const rec = join(PENDING, `${slug}.json`), ref = join(PENDING, `${slug}.references.json`);
    if (!existsSync(rec)) writeJson(rec, scaffoldRecord(slug, r.title));
    if (!existsSync(ref)) writeJson(ref, scaffoldReferences(slug));
    r.status = 'scaffolded'; writeJson(REQ, reqs);
    console.log(`wrote ${rec}\n      ${ref}\nFill every null, then: npm run game -- promote ${slug}`);
  } else if (cmd === 'promote') {
    const slug = pos[0];
    const rec = readJson(join(PENDING, `${slug}.json`)), ref = readJson(join(PENDING, `${slug}.references.json`));
    const missing = [...missingFields(rec).map((m) => `record: ${m}`), ...missingFields(ref).map((m) => `references: ${m}`)];
    if (missing.length) { console.error(`not complete — still null:\n  ${missing.join('\n  ')}`); process.exit(1); }
    delete rec._checklist; delete ref._checklist;
    const games = readJson(join(ROOT, 'data/catalogue/games.json')); const refs = readJson(join(ROOT, 'data/catalogue/references.json'));
    if (games.records.some((g: { id: string }) => g.id === slug)) throw new Error(`${slug} is already in the catalogue`);
    games.records.push(rec); refs.records.push(ref);
    writeJson(join(ROOT, 'data/catalogue/games.json'), games); writeJson(join(ROOT, 'data/catalogue/references.json'), refs);
    const r = reqs.requests.find((x) => x.slug === slug); if (r) r.status = 'added'; writeJson(REQ, reqs);
    console.log(`added ${slug} to the catalogue. Now run: npm run audit && npm run validate`);
  } else {
    console.error('usage: npm run game -- request "Title" [--votes N] [--by @handle] | vote <slug> [N] | list | scaffold <slug> | promote <slug>');
    process.exit(1);
  }
}
if (process.argv[1]?.endsWith('game.ts')) main();
