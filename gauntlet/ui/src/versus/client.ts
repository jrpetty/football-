/**
 * Head to Head API client (docs/API.md → "Head to Head").
 * With `?mock=1` every call is served by ui/src/mock/versusMock.ts.
 */
import { request } from '../api.ts';
import type { VersusData, VersusOptions } from '../../../src/versus/types.ts';

export type * from '../../../src/versus/types.ts';

const enc = encodeURIComponent;
const qs = (p: Record<string, string | undefined>) =>
  Object.entries(p)
    .filter(([, v]) => v)
    .map(([k, v]) => `${k}=${enc(v!)}`)
    .join('&');

export const versusApi = {
  get: (a: string, b: string, run?: string) => request<VersusData>('GET', `/api/versus?${qs({ a, b, run })}`),
  options: (run?: string) => request<VersusOptions>('GET', `/api/versus/options${run ? `?${qs({ run })}` : ''}`),
  /** The Shorts card as a PNG from the server's headless Chrome (409 → render in the browser instead). */
  render: (a: string, b: string, run?: string) => request<{ png: string; width: number; height: number; fileName: string }>('POST', '/api/versus/render', { a, b, run }),
};
