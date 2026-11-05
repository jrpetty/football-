/** Client for the Business area (GET /api/business-ladder and /api/business-jobs; docs/API.md). */
import { request } from '../api.ts';
import type { BusinessLadderView } from '../../../src/core/business-ladder.ts';
import type { BusinessJobsView } from '../../../src/core/business-jobs.ts';

export type * from '../../../src/core/business-ladder.ts';
export type { PairedResult, PairedVerdict } from '../../../src/core/paired.ts';

export const businessApi = {
  /** The ladder for every model with results; `models` (2–4 ids) adds paired comparisons between them. */
  ladder: (models: string[] = [], suite?: string) => {
    const q = new URLSearchParams();
    if (models.length) q.set('models', models.join(','));
    if (suite) q.set('suite', suite);
    const qs = q.toString();
    return request<BusinessLadderView>('GET', `/api/business-ladder${qs ? `?${qs}` : ''}`);
  },
  /** "Which model for this job?": every model's verdict, cost, speed and common mistakes per business job. */
  jobs: () => request<BusinessJobsView>('GET', '/api/business-jobs'),
};
