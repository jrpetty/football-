/**
 * Client for the channel-tools API (/api/channel/*, docs/API.md → "Channel tools").
 * In mock mode (?mock=1) requests are answered by ./channelMock.ts.
 */
import { ApiError, MOCK, api } from '../api.ts';
import type { PingResult, RunRequest } from '../types.ts';
import type {
  ChallengeImportResult,
  ChallengeItem,
  ChallengeQueue,
  ChallengeSlide,
  ChallengeWriteResult,
  HistoryData,
  NewModelHeadline,
  NewModelInput,
  NewModelPrepared,
  NewModelSuiteCost,
  PublishResult,
  SiteConfig,
} from '../../../src/channel/types.ts';

export type * from '../../../src/channel/types.ts';

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

async function request<T>(method: Method, path: string, body?: unknown): Promise<T> {
  if (MOCK) {
    const m = await import('./channelMock.ts');
    return (await m.handleChannel(method, path, body)) as T;
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
    throw new ApiError(obj && typeof obj.error === 'string' ? obj.error : `${res.status} ${res.statusText || 'Request failed'}`, res.status, obj?.details);
  }
  return data as T;
}

const enc = encodeURIComponent;

export type ChallengePatch = Partial<Pick<ChallengeItem, 'question' | 'answer' | 'answerType' | 'alternatives' | 'viewerName' | 'viewerHandle' | 'notes' | 'status' | 'credit'>>;

export const channelApi = {
  site: () => request<SiteConfig>('GET', '/api/channel/site'),
  saveSite: (patch: Partial<SiteConfig>) => request<SiteConfig>('PUT', '/api/channel/site', patch),
  publish: (suites: string[], zip: boolean) => request<PublishResult>('POST', '/api/channel/publish', { suites, zip }),

  prepareModel: (input: NewModelInput) => request<NewModelPrepared>('POST', '/api/channel/newmodel/prepare', input),
  modelCosts: (id: string, repeats?: number) => request<NewModelSuiteCost[]>('GET', `/api/channel/newmodel/${enc(id)}/costs${repeats ? `?repeats=${repeats}` : ''}`),
  headline: (id: string, suite: string) => request<NewModelHeadline>('GET', `/api/channel/newmodel/${enc(id)}/headline?suite=${enc(suite)}`),
  ping: (id: string): Promise<PingResult & { servedModel?: string }> => (MOCK ? request('POST', `/api/channel/mock-ping/${enc(id)}`) : api.ping(id)),
  startRun: (req: RunRequest): Promise<{ runId: string }> => (MOCK ? request('POST', '/api/channel/mock-run', req) : api.startRun(req)),

  history: (suite: string, metric: string, tiers: string[]) => request<HistoryData>('GET', `/api/channel/history?suite=${enc(suite)}&metric=${enc(metric)}&tiers=${tiers.map(enc).join(',')}`),

  challenge: (season?: string) => request<{ seasons: string[]; queue: ChallengeQueue }>('GET', `/api/channel/challenge${season ? `?season=${enc(season)}` : ''}`),
  importChallenge: (season: string, text: string) => request<ChallengeImportResult>('POST', `/api/channel/challenge/${enc(season)}/import`, { text, format: 'auto' }),
  updateChallenge: (season: string, id: string, patch: ChallengePatch) => request<ChallengeQueue>('PUT', `/api/channel/challenge/${enc(season)}/items/${enc(id)}`, patch),
  deleteChallenge: (season: string, id: string) => request<ChallengeQueue>('DELETE', `/api/channel/challenge/${enc(season)}/items/${enc(id)}`),
  writeChallenge: (season: string, category?: string) => request<ChallengeWriteResult>('POST', `/api/channel/challenge/${enc(season)}/write`, { category }),
  challengeSlides: (season: string) => request<ChallengeSlide[]>('GET', `/api/channel/challenge/${enc(season)}/slides`),
};

/** Where the last export can be previewed (served by the Gauntlet server). */
export function previewUrl(page = 'index.html'): string {
  return MOCK ? '#/publish' : `/api/channel/preview/${page}`;
}

export function zipUrl(suites: string[]): string {
  return MOCK ? '#/publish' : `/api/channel/publish.zip?suites=${suites.map(enc).join(',')}`;
}
