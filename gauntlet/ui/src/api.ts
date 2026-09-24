/**
 * Typed client for the Gauntlet HTTP API (docs/API.md).
 *
 * Every network call in the UI goes through this module. Add `?mock=1` to the
 * page URL to serve bundled fixtures instead of the network (for demos and
 * screenshots without a server).
 */
import type {
  CaseResult,
  CaseResultLite,
  Contestant,
  ContestantView,
  EstimateResult,
  Leaderboard,
  Meta,
  PingResult,
  ReviewItem,
  ReviewScoreRequest,
  RunDetail,
  RunEvent,
  RunListItem,
  RunRequest,
  SuiteView,
  TestDefinition,
  TestDetail,
  TestSummary,
  ValidateResult,
} from './types.ts';
import { mockArtifactUrls } from './mock/registry.ts';

export class ApiError extends Error {
  status: number;
  details?: unknown;
  constructor(message: string, status: number, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.details = details;
  }
}

function detectMock(): boolean {
  try {
    const v = new URLSearchParams(window.location.search).get('mock');
    return v !== null && v !== '0' && v !== 'false';
  } catch {
    return false;
  }
}

/** True when the UI is serving fixtures (`?mock=1`). */
export const MOCK = detectMock();

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

const loadMock = () => import('./mock/mockServer.ts');

async function request<T>(method: Method, path: string, body?: unknown): Promise<T> {
  if (MOCK) {
    const mock = await loadMock();
    return (await mock.handle(method, path, body)) as T;
  }
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers: body !== undefined ? { 'content-type': 'application/json', accept: 'application/json' } : { accept: 'application/json' },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch (err) {
    throw new ApiError(`Cannot reach the Gauntlet server (${(err as Error).message || 'network error'})`, 0);
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    const obj = data && typeof data === 'object' ? (data as { error?: unknown; details?: unknown }) : null;
    const msg = obj && typeof obj.error === 'string' ? obj.error : `${res.status} ${res.statusText || 'Request failed'}`;
    throw new ApiError(msg, res.status, obj?.details);
  }
  return data as T;
}

const enc = encodeURIComponent;

export const api = {
  // Meta
  meta: () => request<Meta>('GET', '/api/meta'),

  // Contestants
  contestants: () => request<ContestantView[]>('GET', '/api/contestants'),
  saveContestant: (c: Contestant) => request<ContestantView>('PUT', `/api/contestants/${enc(c.id)}`, c),
  deleteContestant: (id: string) => request<{ ok: true }>('DELETE', `/api/contestants/${enc(id)}`),
  ping: (id: string) => request<PingResult>('POST', `/api/contestants/${enc(id)}/ping`),
  providerModels: (providerId: string) => request<{ models: string[] }>('GET', `/api/providers/${enc(providerId)}/models`),

  // Tests & suites
  tests: () => request<TestSummary[]>('GET', '/api/tests'),
  test: (id: string) => request<TestDetail>('GET', `/api/tests/${enc(id)}`),
  validateTest: (definition: TestDefinition) => request<ValidateResult>('POST', '/api/tests/validate', { definition }),
  createTest: (definition: TestDefinition) => request<TestSummary>('POST', '/api/tests', { definition }),
  updateTest: (id: string, definition: TestDefinition) => request<TestSummary>('PUT', `/api/tests/${enc(id)}`, { definition }),
  deleteTest: (id: string) => request<{ ok: true }>('DELETE', `/api/tests/${enc(id)}`),
  suites: () => request<SuiteView[]>('GET', '/api/suites'),

  // Runs
  estimate: (req: RunRequest) => request<EstimateResult>('POST', '/api/estimate', req),
  startRun: (req: RunRequest) => request<{ runId: string }>('POST', '/api/runs', req),
  runs: () => request<RunListItem[]>('GET', '/api/runs'),
  run: (id: string) => request<RunDetail>('GET', `/api/runs/${enc(id)}`),
  result: (runId: string, key: string) => request<CaseResult>('GET', `/api/runs/${enc(runId)}/results/${enc(key)}`),
  cancelRun: (id: string) => request<{ ok: true }>('POST', `/api/runs/${enc(id)}/cancel`),
  resumeRun: (id: string) => request<{ ok: true }>('POST', `/api/runs/${enc(id)}/resume`),
  deleteRun: (id: string) => request<{ ok: true }>('DELETE', `/api/runs/${enc(id)}`),

  // Leaderboard
  leaderboard: (suiteId: string) => request<Leaderboard>('GET', `/api/leaderboard?suite=${enc(suiteId)}`),

  // Human review
  reviewQueue: (testId?: string) => request<ReviewItem[]>('GET', `/api/review/queue${testId ? `?testId=${enc(testId)}` : ''}`),
  reviewScore: (body: ReviewScoreRequest) => request<CaseResultLite>('POST', '/api/review/score', body),
};

/** Download link for a run export. */
export function exportUrl(runId: string, format: 'csv' | 'json'): string {
  if (MOCK) return mockArtifactUrls.get(`export:${runId}:${format}`) ?? '#';
  return `/api/runs/${enc(runId)}/export.${format}`;
}

/** URL of a stored artifact (HTML/SVG/PNG/…) for iframes and images. */
export function artifactUrl(runId: string, file: string): string {
  if (MOCK) return mockArtifactUrls.get(`${runId}/${file}`) ?? '';
  return `/api/runs/${enc(runId)}/artifacts/${enc(file)}`;
}

// ─────────────────────────────── Server-Sent Events ───────────────────────────────

export interface RunStreamHandlers {
  onEvent: (event: RunEvent) => void;
  /** Called on every (re)connection — resync state here. */
  onOpen?: (reconnected: boolean) => void;
  /** Called when the connection drops (the helper keeps retrying). */
  onDisconnect?: () => void;
}

const EVENT_TYPES: RunEvent['type'][] = ['run.status', 'run.progress', 'job.started', 'job.delta', 'job.step', 'job.finished', 'log'];

/**
 * Subscribe to `/api/runs/:id/events`. Reconnects automatically (EventSource
 * retry, plus a manual back-off if the browser gives up). Returns an
 * unsubscribe function.
 */
export function subscribeRun(runId: string, handlers: RunStreamHandlers): () => void {
  let closed = false;

  if (MOCK) {
    let unsub: (() => void) | null = null;
    void loadMock().then((m) => {
      if (!closed) unsub = m.subscribe(runId, handlers);
    });
    return () => {
      closed = true;
      unsub?.();
    };
  }

  let es: EventSource | null = null;
  let opens = 0;
  let retryTimer: number | undefined;
  let backoff = 1000;

  const handleMessage = (msg: MessageEvent<string>) => {
    if (!msg.data) return;
    try {
      const parsed = JSON.parse(msg.data) as RunEvent;
      if (parsed && typeof parsed === 'object' && 'type' in parsed) handlers.onEvent(parsed);
    } catch {
      /* ignore malformed frames (e.g. keep-alive comments) */
    }
  };

  const connect = () => {
    if (closed) return;
    es = new EventSource(`/api/runs/${enc(runId)}/events`);
    es.onmessage = handleMessage;
    // Also accept named events, in case the server tags frames with `event:`.
    for (const t of EVENT_TYPES) es.addEventListener(t, handleMessage as EventListener);
    es.onopen = () => {
      backoff = 1000;
      handlers.onOpen?.(opens > 0);
      opens += 1;
    };
    es.onerror = () => {
      handlers.onDisconnect?.();
      if (es && es.readyState === EventSource.CLOSED) {
        es.close();
        es = null;
        retryTimer = window.setTimeout(connect, backoff);
        backoff = Math.min(backoff * 2, 15000);
      }
    };
  };
  connect();

  return () => {
    closed = true;
    window.clearTimeout(retryTimer);
    es?.close();
  };
}
