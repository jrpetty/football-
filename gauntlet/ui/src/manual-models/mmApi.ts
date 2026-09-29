/** Typed client for the copy & paste model routes (src/manual-models/routes.ts; mock: ui/src/mock/manualModelsMock.ts). */
import { request } from '../api.ts';
import type { ContestantView, ManualModelInfo } from '../types.ts';
import type { CatalogModel, ModelCatalog, ReassignOutcome, UnspecifiedGroup } from '../../../src/manual-models/identity.ts';
import type { BestData } from '../../../src/manual-models/best-rank.ts';

export type { BestData, CatalogModel, ModelCatalog, ReassignOutcome, UnspecifiedGroup };

export type ModelChoice = Omit<ManualModelInfo, 'note'> & { note?: string };

export interface PendingChoiceView {
  runId: string;
  caseKey: string;
  contestantId: string;
  contestantLabel: string;
  info: ManualModelInfo;
  at: string;
  locked?: boolean;
}

const enc = encodeURIComponent;

export const mmApi = {
  catalog: () => request<ModelCatalog & { problems: string[] }>('GET', '/api/manual-models/catalog'),
  suggestModel: (m: { label: string; vendor: string; released: string; notes?: string }) => request<CatalogModel>('POST', '/api/manual-models/catalog', m),
  makeContestants: (models: ModelChoice[]) => request<ContestantView[]>('POST', '/api/manual-models/contestants', { models }),
  choices: () => request<PendingChoiceView[]>('GET', '/api/manual-models/choices'),
  choose: (requestId: string, choice: ModelChoice) => request<{ choice: PendingChoiceView; contestant: ContestantView }>('POST', '/api/manual-models/choice', { requestId, choice }),
  unchoose: (requestId: string) => request<{ ok: boolean }>('DELETE', `/api/manual-models/choice/${enc(requestId)}`),
  unspecified: () => request<UnspecifiedGroup[]>('GET', '/api/manual-models/unspecified'),
  reassign: (body: { runId: string; keys: string[]; to: ModelChoice; note?: string }) => request<ReassignOutcome>('POST', '/api/manual-models/reassign', { ...body, confirm: true }),
  best: () => request<BestData>('GET', '/api/best-per-test'),
};

const LAST_KEY = 'gauntlet.manualModels.last';

/** The last model + interface + settings the owner picked (remembered in this browser). */
export function lastChoice(): ModelChoice | null {
  try {
    const v = JSON.parse(window.localStorage.getItem(LAST_KEY) ?? 'null') as ModelChoice | null;
    return v && typeof v.catalogId === 'string' ? v : null;
  } catch {
    return null;
  }
}

export function rememberChoice(c: ModelChoice): void {
  try {
    window.localStorage.setItem(LAST_KEY, JSON.stringify(c));
  } catch {
    /* private window: nothing to remember */
  }
}
