import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize, sep } from 'node:path';
import {
  deleteContestant,
  getContestant,
  getProvider,
  hasApiKey,
  loadCategories,
  loadContestants,
  loadProviders,
  loadSettings,
  toView,
  upsertContestant,
  validateContestant,
} from '../core/config.ts';
import { computeCost } from '../core/cost.ts';
import { canonicalJson } from '../core/hash.ts';
import { UI_DIST_DIR } from '../core/paths.ts';
import {
  bumpPatch,
  computeTestHash,
  customTestPath,
  deleteCustomTest,
  fingerprint,
  getTest,
  loadSuites,
  loadTests,
  renderCase,
  resolveTests,
  saveCustomTest,
  summarize,
  validateTest,
} from '../core/registry.ts';
import type { CaseResult, RunEvent, RunRequest, TestDefinition } from '../core/types.ts';
import { HARNESS_VERSION, PROTOCOL_VERSION } from '../core/version.ts';
import { combinedLeaderboard, runLeaderboard } from '../engine/leaderboards.ts';
import { activeProgress, cancelRun, estimateRun, isActive, recoverInterruptedRuns, resumeRun, startRun, subscribe } from '../engine/runner.ts';
import { reviewQueue, submitHumanScore } from '../engine/review.ts';
import { artifactPath, deleteRun, listRunIds, listRuns, readManifest, readResults, toLite } from '../engine/store.ts';
import { PROGRAMS } from '../programs/index.ts';
import { failManual, listManualRequests, submitManual } from '../providers/manual.ts';
import { gradePasted } from '../engine/grade.ts';
import { DATA_DIR } from '../core/paths.ts';
import { browserAvailable } from '../scoring/browser.ts';
import { createAdapter, discoverModels } from '../providers/index.ts';
import { callWithRetry } from '../engine/recorder.ts';
import { Semaphore } from '../engine/semaphore.ts';
import { registerChannelRoutes } from '../channel/routes.ts';

class HttpError extends Error {
  status: number;
  details?: unknown;
  constructor(status: number, message: string, details?: unknown) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Route {
  method: string;
  pattern: RegExp;
  keys: string[];
  handler: Handler;
}

const routes: Route[] = [];
function route(method: string, path: string, handler: Handler): void {
  const keys: string[] = [];
  const pattern = new RegExp('^' + path.replace(/:(\w+)/g, (_, k: string) => (keys.push(k), '([^/]+)')) + '$');
  routes.push({ method, pattern, keys, handler });
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(text);
}

async function readBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > 5 * 1024 * 1024) throw new HttpError(413, 'Request body too large');
    chunks.push(chunk as Buffer);
  }
  const text = Buffer.concat(chunks).toString('utf8');
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, 'Body must be valid JSON');
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Meta
// ─────────────────────────────────────────────────────────────────────────────

let browserCheck: Promise<boolean> | null = null;

route('GET', '/api/meta', async () => {
  browserCheck ??= browserAvailable();
  return {
    harnessVersion: HARNESS_VERSION,
    protocolVersion: PROTOCOL_VERSION,
    categories: loadCategories(),
    providers: loadProviders().map((p) => ({ ...p, hasKey: hasApiKey(p) })),
    settings: loadSettings(),
    programs: Object.values(PROGRAMS).map((p) => ({ id: p.id, name: p.name, description: p.description, scoring: p.scoring })),
    browserChecks: await browserCheck,
  };
});

// ─────────────────────────────────────────────────────────────────────────────
// Contestants
// ─────────────────────────────────────────────────────────────────────────────

route('GET', '/api/contestants', () => {
  const providers = loadProviders();
  return loadContestants().map((c) => toView(c, providers));
});

route('PUT', '/api/contestants/:id', async ({ params, body }) => {
  const c = (await body()) as Parameters<typeof upsertContestant>[0];
  if (c.id !== params.id) throw new HttpError(400, 'Body id must match the URL id');
  const errors = validateContestant(c);
  if (errors.length) throw new HttpError(400, 'Invalid model', errors);
  upsertContestant(c);
  return toView(c);
});

route('DELETE', '/api/contestants/:id', ({ params }) => {
  if (!deleteContestant(params.id!)) throw new HttpError(404, 'Model not found');
  return { ok: true };
});

route('POST', '/api/contestants/:id/ping', async ({ params }) => {
  let c;
  try {
    c = getContestant(params.id!);
  } catch {
    throw new HttpError(404, 'Model not found');
  }
  try {
    const provider = getProvider(c.provider);
    const target = { contestant: c, adapter: createAdapter(c, provider), semaphore: new Semaphore(1) };
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 120_000);
    const r = await callWithRetry(
      target,
      { messages: [{ role: 'user', content: 'Reply with exactly one word: pong' }], maxOutputTokens: 2000, temperature: 0 },
      { maxRetries: 1, temperature: 0, defaultMaxOutputTokens: 2000 },
      ctrl.signal,
    ).finally(() => clearTimeout(timer));
    return { ok: true, text: r.text.slice(0, 200), totalMs: r.totalMs, ttftMs: r.ttftMs, usage: r.usage, costUsd: computeCost(r.usage, c.pricing), servedModel: r.servedModel };
  } catch (err) {
    return { ok: false, error: (err as Error).message };
  }
});

route('GET', '/api/providers/:id/models', async ({ params }) => {
  try {
    return { models: await discoverModels(params.id!) };
  } catch (err) {
    throw new HttpError(502, (err as Error).message);
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// Tests & suites
// ─────────────────────────────────────────────────────────────────────────────

route('GET', '/api/tests', () => loadTests().map(summarize));

route('GET', '/api/tests/:id', ({ params }) => {
  const t = getTest(decodeURIComponent(params.id!));
  if (!t) throw new HttpError(404, 'Test not found');
  const d = t.definition;
  const rendered =
    d.kind === 'prompt'
      ? d.cases.map((c) => renderCase(d, c))
      : d.seeds.map((s) => ({ caseId: `seed-${s}`, turns: [] as string[], notes: `World generated from seed ${s}; prompts are produced by the "${d.program}" program at run time.` }));
  const program = d.kind === 'program' ? PROGRAMS[d.program] : undefined;
  return {
    definition: d,
    summary: summarize(t),
    rendered,
    program: program ? { id: program.id, name: program.name, description: program.description, scoring: program.scoring } : undefined,
  };
});

route('POST', '/api/tests/validate', async ({ body }) => {
  const { definition } = (await body()) as { definition: TestDefinition };
  const all = loadTests();
  const existing = all.find((t) => t.definition.id === definition?.id);
  const errors = validateTest(definition, all, { selfFile: existing?.source === 'custom' ? existing.file : undefined });
  return { ok: errors.length === 0, errors, hash: errors.length ? undefined : computeTestHash(definition) };
});

route('POST', '/api/tests', async ({ body }) => {
  const { definition } = (await body()) as { definition: TestDefinition };
  const all = loadTests();
  if (all.some((t) => t.definition.id === definition?.id)) throw new HttpError(409, `A test with id "${definition.id}" already exists`);
  const errors = validateTest(definition, all);
  if (errors.length) throw new HttpError(400, 'Invalid test', errors);
  return summarize(saveCustomTest({ ...definition, createdAt: definition.createdAt ?? new Date().toISOString().slice(0, 10) }));
});

route('PUT', '/api/tests/:id', async ({ params, body }) => {
  const id = decodeURIComponent(params.id!);
  const existing = getTest(id);
  if (!existing) throw new HttpError(404, 'Test not found');
  if (existing.source !== 'custom') throw new HttpError(403, 'Built-in tests are read-only. Duplicate it to create a custom variant.');
  const { definition } = (await body()) as { definition: TestDefinition };
  if (definition.id !== id) throw new HttpError(400, 'Changing a test id is not allowed; duplicate it instead');
  const next = { ...definition };
  const contentChanged = canonicalJson({ ...next, version: '' }) !== canonicalJson({ ...existing.definition, version: '' });
  if (contentChanged && next.version === existing.definition.version) next.version = bumpPatch(existing.definition.version);
  const errors = validateTest(next, loadTests(), { selfFile: existing.file });
  if (errors.length) throw new HttpError(400, 'Invalid test', errors);
  return summarize(saveCustomTest(next));
});

route('DELETE', '/api/tests/:id', ({ params }) => {
  const id = decodeURIComponent(params.id!);
  const existing = getTest(id);
  if (!existing) throw new HttpError(404, 'Test not found');
  if (existing.source !== 'custom' || !existsSync(customTestPath(id))) throw new HttpError(403, 'Only custom tests can be deleted');
  deleteCustomTest(id);
  return { ok: true };
});

route('GET', '/api/suites', () => {
  const all = loadTests();
  return loadSuites().map((s) => {
    const tests = resolveTests({ suiteId: s.id }, all);
    return { ...s, fingerprint: fingerprint(tests), testCount: tests.length };
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Runs
// ─────────────────────────────────────────────────────────────────────────────

route('POST', '/api/estimate', async ({ body }) => {
  try {
    return await estimateRun((await body()) as RunRequest);
  } catch (err) {
    if (err instanceof HttpError) throw err;
    throw new HttpError(400, (err as Error).message);
  }
});

route('POST', '/api/runs', async ({ body }) => {
  try {
    return { runId: await startRun((await body()) as RunRequest) };
  } catch (err) {
    if (err instanceof HttpError) throw err;
    throw new HttpError(400, (err as Error).message);
  }
});

route('GET', '/api/runs', () => listRuns());

function requireRun(id: string) {
  const manifest = readManifest(id);
  if (!manifest) throw new HttpError(404, 'Run not found');
  return manifest;
}

route('GET', '/api/runs/:id', ({ params }) => {
  const manifest = requireRun(params.id!);
  const results = readResults(manifest.id);
  const live = activeProgress(manifest.id);
  const progress = live ?? {
    completed: results.filter((r) => r.status !== 'error' && r.status !== 'cancelled').length,
    total: manifest.totalJobs,
    costUsd: results.reduce((s, r) => s + r.metrics.costUsd + r.metrics.judgeCostUsd, 0),
  };
  return { manifest, leaderboard: runLeaderboard(manifest.id), results: results.map(toLite), progress, active: isActive(manifest.id) };
});

route('GET', '/api/runs/:id/results/:key', ({ params }) => {
  const manifest = requireRun(params.id!);
  const key = decodeURIComponent(params.key!);
  const r = readResults(manifest.id).find((x) => x.key === key);
  if (!r) throw new HttpError(404, 'Result not found');
  return r;
});

route('POST', '/api/runs/:id/cancel', ({ params }) => {
  requireRun(params.id!);
  if (!cancelRun(params.id!)) throw new HttpError(409, 'Run is not active');
  return { ok: true };
});

route('POST', '/api/runs/:id/resume', async ({ params, body }) => {
  requireRun(params.id!);
  const { maxCostUsd } = ((await body()) ?? {}) as { maxCostUsd?: number | null };
  if (maxCostUsd !== undefined && maxCostUsd !== null && !(maxCostUsd > 0)) throw new HttpError(400, 'maxCostUsd must be a positive number or null');
  try {
    resumeRun(params.id!, { maxCostUsd });
  } catch (err) {
    throw new HttpError(409, (err as Error).message);
  }
  return { ok: true };
});

route('DELETE', '/api/runs/:id', ({ params }) => {
  requireRun(params.id!);
  if (isActive(params.id!)) throw new HttpError(409, 'Cancel the run before deleting it');
  deleteRun(params.id!);
  return { ok: true };
});

route('GET', '/api/runs/:id/events', ({ req, res, params }) => {
  const manifest = requireRun(params.id!);
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-store',
    connection: 'keep-alive',
    'x-accel-buffering': 'no',
  });
  const send = (e: RunEvent) => res.write(`data: ${JSON.stringify(e)}\n\n`);
  const progress = activeProgress(manifest.id);
  const results = progress ? null : readResults(manifest.id);
  send({
    type: 'run.progress',
    runId: manifest.id,
    completed: progress?.completed ?? results!.filter((r) => r.status !== 'error' && r.status !== 'cancelled').length,
    total: progress?.total ?? manifest.totalJobs,
    costUsd: progress?.costUsd ?? results!.reduce((s, r) => s + r.metrics.costUsd + r.metrics.judgeCostUsd, 0),
    at: new Date().toISOString(),
  });
  send({ type: 'run.status', runId: manifest.id, status: isActive(manifest.id) ? 'running' : manifest.status, at: new Date().toISOString() });
  const unsubscribe = subscribe(manifest.id, send);
  const heartbeat = setInterval(() => res.write(': ping\n\n'), 15_000);
  req.on('close', () => {
    clearInterval(heartbeat);
    unsubscribe();
  });
  return STREAMING;
});

const CSV_COLUMNS = [
  'run_id', 'contestant', 'test', 'test_version', 'case', 'repeat', 'status', 'score', 'passed', 'wall_ms', 'ttft_ms', 'api_calls',
  'input_tokens', 'output_tokens', 'reasoning_tokens', 'cached_input_tokens', 'cost_usd', 'judge_cost_usd', 'output_tokens_per_sec', 'retries', 'summary',
];

function csvCell(v: unknown): string {
  const s = v === null || v === undefined ? '' : String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function toCsv(results: CaseResult[]): string {
  const rows = results.map((r) =>
    [
      r.runId, r.contestantId, r.testId, r.testVersion, r.caseId, r.repeat, r.status, r.score, r.passed, r.metrics.wallMs, r.metrics.ttftMs, r.metrics.apiCalls,
      r.metrics.inputTokens, r.metrics.outputTokens, r.metrics.reasoningTokens, r.metrics.cachedInputTokens, r.metrics.costUsd, r.metrics.judgeCostUsd,
      r.metrics.outputTokensPerSec, r.metrics.retries, r.summary,
    ].map(csvCell).join(','),
  );
  return [CSV_COLUMNS.join(','), ...rows].join('\n') + '\n';
}

route('GET', '/api/runs/:id/export.csv', ({ res, params }) => {
  const manifest = requireRun(params.id!);
  res.writeHead(200, { 'content-type': 'text/csv; charset=utf-8', 'content-disposition': `attachment; filename="gauntlet-${manifest.id}.csv"` });
  res.end(toCsv(readResults(manifest.id)));
  return STREAMING;
});

route('GET', '/api/runs/:id/export.json', ({ res, params }) => {
  const manifest = requireRun(params.id!);
  res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'content-disposition': `attachment; filename="gauntlet-${manifest.id}.json"` });
  res.end(JSON.stringify({ manifest, leaderboard: runLeaderboard(manifest.id), results: readResults(manifest.id) }, null, 2));
  return STREAMING;
});

const ARTIFACT_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json; charset=utf-8',
  '.js': 'text/plain; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
};

route('GET', '/api/runs/:id/artifacts/(.+)', ({ res, params }) => {
  requireRun(params.id!);
  const file = decodeURIComponent(params['1'] ?? '');
  const full = artifactPath(params.id!, file);
  if (!full) throw new HttpError(404, 'Artifact not found');
  res.writeHead(200, {
    'content-type': ARTIFACT_TYPES[extname(full).toLowerCase()] ?? 'application/octet-stream',
    // Model-generated content is untrusted: opaque origin, no network, no forms, no navigation.
    'content-security-policy': "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' data: blob:; style-src 'unsafe-inline' data:; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'",
    'x-content-type-options': 'nosniff',
    'cache-control': 'no-store',
  });
  createReadStream(full).pipe(res);
  return STREAMING;
});

// ─────────────────────────────────────────────────────────────────────────────
// Manual (copy & paste) contestants and pasted-reply grading
// ─────────────────────────────────────────────────────────────────────────────

route('GET', '/api/manual', ({ query }) => listManualRequests(query.get('runId') || undefined));

route('POST', '/api/manual/:id', async ({ params, body }) => {
  const b = (await body()) as { text?: unknown; inputTokens?: unknown; outputTokens?: unknown; reasoningTokens?: unknown; costUsd?: unknown };
  if (typeof b.text !== 'string') throw new HttpError(400, 'Paste the model reply into "text"');
  const optNum = (v: unknown, name: string) => {
    if (v === undefined || v === null || v === '') return undefined;
    const n = Number(v);
    if (!Number.isFinite(n) || n < 0) throw new HttpError(400, `${name} must be a non-negative number`);
    return n;
  };
  const ok = submitManual(decodeURIComponent(params.id!), {
    text: b.text,
    inputTokens: optNum(b.inputTokens, 'inputTokens'),
    outputTokens: optNum(b.outputTokens, 'outputTokens'),
    reasoningTokens: optNum(b.reasoningTokens, 'reasoningTokens'),
    costUsd: optNum(b.costUsd, 'costUsd'),
  });
  if (!ok) throw new HttpError(404, 'This request is no longer waiting (answered, cancelled or timed out)');
  return { ok: true };
});

route('POST', '/api/manual/:id/fail', async ({ params, body }) => {
  const { reason } = ((await body()) ?? {}) as { reason?: string };
  if (!failManual(decodeURIComponent(params.id!), String(reason ?? ''))) throw new HttpError(404, 'This request is no longer waiting');
  return { ok: true };
});

route('POST', '/api/grade', async ({ body }) => {
  try {
    return await gradePasted((await body()) as Parameters<typeof gradePasted>[0]);
  } catch (err) {
    throw new HttpError(400, (err as Error).message);
  }
});

route('GET', '/api/graded/(.+)', ({ res, params }) => {
  const rel = decodeURIComponent(params['1'] ?? '');
  const base = join(DATA_DIR, 'graded');
  const full = normalize(join(base, rel));
  if (!full.startsWith(base + sep) || !existsSync(full)) throw new HttpError(404, 'Artifact not found');
  res.writeHead(200, {
    'content-type': ARTIFACT_TYPES[extname(full).toLowerCase()] ?? 'application/octet-stream',
    'content-security-policy': "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' data: blob:; style-src 'unsafe-inline' data:; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'",
    'x-content-type-options': 'nosniff',
    'cache-control': 'no-store',
  });
  createReadStream(full).pipe(res);
  return STREAMING;
});

// ─────────────────────────────────────────────────────────────────────────────
// Cost planning
// ─────────────────────────────────────────────────────────────────────────────

route('GET', '/api/costs', async ({ query }) => {
  const providers = loadProviders();
  const requested = (query.get('models') ?? '').split(',').map((x) => x.trim()).filter(Boolean);
  const contestantIds = requested.length
    ? requested
    : loadContestants()
        .filter((c) => c.enabled && providers.find((p) => p.id === c.provider)?.type !== 'manual' && providers.find((p) => p.id === c.provider)?.type !== 'mock')
        .map((c) => c.id);
  const repeats = Number(query.get('repeats') ?? 1);
  try {
    return await estimateRun({ suiteId: query.get('suite') || 'core', contestantIds, repeats: Number.isFinite(repeats) && repeats > 0 ? repeats : 1 });
  } catch (err) {
    throw new HttpError(400, (err as Error).message);
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// Leaderboard & review
// ─────────────────────────────────────────────────────────────────────────────

route('GET', '/api/leaderboard', ({ query }) => combinedLeaderboard(query.get('suite') || 'core'));

route('GET', '/api/review/queue', ({ query }) => reviewQueue(query.get('testId') || undefined));

route('POST', '/api/review/score', async ({ body }) => {
  try {
    return submitHumanScore((await body()) as Parameters<typeof submitHumanScore>[0]);
  } catch (err) {
    throw new HttpError(400, (err as Error).message);
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// Static UI
// ─────────────────────────────────────────────────────────────────────────────

const STREAMING = Symbol('streaming');

const STATIC_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json',
  '.woff2': 'font/woff2',
  '.webmanifest': 'application/manifest+json',
};

function serveStatic(pathname: string, res: ServerResponse): void {
  if (!existsSync(UI_DIST_DIR)) {
    res.writeHead(503, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('The dashboard has not been built yet. Run `npm run build:ui` (or `npm start`), then reload.');
    return;
  }
  const rel = normalize(decodeURIComponent(pathname)).replace(/^([/\\])+/, '');
  let file = join(UI_DIST_DIR, rel);
  if (!file.startsWith(UI_DIST_DIR + sep) && file !== UI_DIST_DIR) file = join(UI_DIST_DIR, 'index.html');
  if (!existsSync(file) || statSync(file).isDirectory()) file = join(UI_DIST_DIR, 'index.html');
  const type = STATIC_TYPES[extname(file).toLowerCase()] ?? 'application/octet-stream';
  const immutable = file.includes(`${sep}assets${sep}`);
  res.writeHead(200, { 'content-type': type, 'cache-control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache' });
  res.end(readFileSync(file));
}

// Channel tools: public site, New Model Day, history, viewer challenge (src/channel/).
registerChannelRoutes(route, (status, message, details) => new HttpError(status, message, details), STREAMING);

// ─────────────────────────────────────────────────────────────────────────────

export function startServer(opts: { port: number; host: string }): Promise<{ url: string; close: () => Promise<void> }> {
  recoverInterruptedRuns(listRunIds());
  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    const method = req.method ?? 'GET';
    try {
      if (!url.pathname.startsWith('/api/')) {
        if (method !== 'GET' && method !== 'HEAD') throw new HttpError(405, 'Method not allowed');
        serveStatic(url.pathname, res);
        return;
      }
      // Basic CSRF defence: state-changing requests must come from this origin (or a tool without an Origin header).
      if (method !== 'GET' && req.headers.origin) {
        const origin = new URL(req.headers.origin);
        if (origin.host !== req.headers.host && !/^localhost:5173$|^127\.0\.0\.1:5173$/.test(origin.host)) throw new HttpError(403, 'Cross-origin request refused');
      }
      for (const r of routes) {
        if (r.method !== method) continue;
        const m = url.pathname.match(r.pattern);
        if (!m) continue;
        const params: Record<string, string> = {};
        r.keys.forEach((k, i) => (params[k] = m[i + 1]!));
        if (r.keys.length === 0 || m.length > r.keys.length + 1) m.slice(r.keys.length + 1).forEach((v, i) => (params[String(i + 1)] = v));
        const result = await r.handler({ req, res, params, query: url.searchParams, body: () => readBody(req) });
        if (result !== STREAMING) sendJson(res, 200, result ?? { ok: true });
        return;
      }
      throw new HttpError(404, `No route for ${method} ${url.pathname}`);
    } catch (err) {
      if (res.headersSent) {
        res.end();
        return;
      }
      if (err instanceof HttpError) sendJson(res, err.status, { error: err.message, details: err.details });
      else {
        console.error('[gauntlet] Unhandled server error:', err);
        sendJson(res, 500, { error: (err as Error).message ?? 'Internal error' });
      }
    }
  });
  return new Promise((resolve) => {
    server.listen(opts.port, opts.host, () => {
      const url = `http://${opts.host === '0.0.0.0' ? 'localhost' : opts.host}:${opts.port}`;
      resolve({
        url,
        close: () => new Promise<void>((r) => server.close(() => r())),
      });
    });
  });
}
