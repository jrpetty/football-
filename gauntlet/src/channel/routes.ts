/** HTTP routes for the channel tools (registered from src/server/index.ts). See docs/API.md → "Channel tools". */
import type { IncomingMessage, ServerResponse } from 'node:http';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize, sep } from 'node:path';
import { challengeSlides, deleteItem, importSubmissions, listSeasons, loadQueue, updateItem, writeChallengeTest, type ChallengePatch } from './challenge.ts';
import { historyFor } from './history.ts';
import { newModelCosts, newModelHeadline, prepareNewModel } from './newmodel.ts';
import { loadSiteConfig, saveSiteConfig } from './site-config.ts';
import { buildSiteFiles, DEFAULT_SITE_DIR, exportSite, zipFiles } from './site-export.ts';
import type { NewModelInput, SiteConfig } from './types.ts';

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;
type RouteFn = (method: string, path: string, handler: Handler) => void;
type MakeError = (status: number, message: string, details?: unknown) => Error;

const TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
};

export function registerChannelRoutes(route: RouteFn, httpError: MakeError, streaming: symbol): void {
  const bad = async <T>(fn: () => T | Promise<T>, status = 400): Promise<T> => {
    try {
      return await fn();
    } catch (err) {
      throw httpError(status, (err as Error).message);
    }
  };

  // ── Public website ──
  route('GET', '/api/channel/site', () => loadSiteConfig());
  route('PUT', '/api/channel/site', async ({ body }) => bad(async () => saveSiteConfig((await body()) as Partial<SiteConfig>)));
  route('POST', '/api/channel/publish', async ({ body }) => {
    const b = ((await body()) ?? {}) as { suites?: string[]; zip?: boolean };
    return bad(() => exportSite({ suites: Array.isArray(b.suites) ? b.suites : undefined, zip: !!b.zip }));
  });
  route('GET', '/api/channel/publish.zip', ({ res, query }) => {
    const suites = (query.get('suites') ?? '').split(',').map((s) => s.trim()).filter(Boolean);
    const { files } = buildSiteFiles({ suites: suites.length ? suites : undefined });
    const zip = zipFiles(files);
    res.writeHead(200, { 'content-type': 'application/zip', 'content-disposition': 'attachment; filename="gauntlet-site.zip"', 'content-length': zip.length });
    res.end(zip);
    return streaming;
  });
  // Preview of the last export (relative links work because the path keeps the folder layout).
  route('GET', '/api/channel/preview/(.*)', ({ res, params }) => {
    const rel = decodeURIComponent(params['1'] ?? '') || 'index.html';
    const full = normalize(join(DEFAULT_SITE_DIR, ...rel.split('/')));
    if (!full.startsWith(DEFAULT_SITE_DIR + sep) || !existsSync(full) || statSync(full).isDirectory()) throw httpError(404, 'Not exported yet: press Publish first.');
    res.writeHead(200, { 'content-type': TYPES[extname(full).toLowerCase()] ?? 'application/octet-stream', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
    res.end(readFileSync(full));
    return streaming;
  });

  // ── New Model Day ──
  route('POST', '/api/channel/newmodel/prepare', async ({ body }) => bad(async () => prepareNewModel((await body()) as NewModelInput)));
  route('GET', '/api/channel/newmodel/:id/costs', async ({ params, query }) => {
    const r = Number(query.get('repeats'));
    return bad(() => newModelCosts(decodeURIComponent(params.id!), undefined, Number.isFinite(r) && r > 0 ? r : undefined));
  });
  route('GET', '/api/channel/newmodel/:id/headline', ({ params, query }) => bad(() => newModelHeadline(decodeURIComponent(params.id!), query.get('suite') || 'core')));

  // ── History ──
  route('GET', '/api/channel/history', ({ query }) =>
    bad(() => historyFor(query.get('suite') || 'core', { metric: query.get('metric') || 'index', tiers: (query.get('tiers') ?? '').split(',').filter(Boolean) })),
  );

  // ── Viewer challenge ──
  route('GET', '/api/channel/challenge', ({ query }) => {
    const season = query.get('season') || loadSiteConfig().season;
    return bad(() => ({ seasons: listSeasons(), queue: loadQueue(season) }));
  });
  route('POST', '/api/channel/challenge/:season/import', async ({ params, body }) => {
    const b = ((await body()) ?? {}) as { text?: string; format?: 'csv' | 'json' | 'auto' };
    if (typeof b.text !== 'string') throw httpError(400, 'Paste the CSV or JSON into "text"');
    return bad(() => importSubmissions(decodeURIComponent(params.season!), b.text!, b.format ?? 'auto'));
  });
  route('PUT', '/api/channel/challenge/:season/items/:item', async ({ params, body }) => bad(async () => updateItem(decodeURIComponent(params.season!), decodeURIComponent(params.item!), (await body()) as ChallengePatch)));
  route('DELETE', '/api/channel/challenge/:season/items/:item', ({ params }) => bad(() => deleteItem(decodeURIComponent(params.season!), decodeURIComponent(params.item!)), 404));
  route('POST', '/api/channel/challenge/:season/write', async ({ params, body }) => {
    const b = ((await body()) ?? {}) as { category?: string };
    return bad(() => writeChallengeTest(decodeURIComponent(params.season!), { category: b.category }));
  });
  route('GET', '/api/channel/challenge/:season/slides', ({ params }) => bad(() => challengeSlides(decodeURIComponent(params.season!))));
}
