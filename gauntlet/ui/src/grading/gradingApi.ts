/** Typed client for the Grading Station routes (docs/API.md → "Grading Station"). Mock mode is served by mock/gradingMock.ts. */
import { request } from '../api.ts';
import type { CaseResultLite } from '../types.ts';
import type { GradingSpec } from '../../../src/grading/spec.ts';
import type {
  AiEstimate,
  AiGradeOutcome,
  AiSummary,
  ArenaPending,
  GradingRunInfo,
  HumanGradeInput,
  OfficialPolicy,
  QueueItem,
  RunSummaries,
  StationItem,
  SummaryEstimate,
} from '../../../src/grading/types.ts';

export type * from '../../../src/grading/types.ts';
export type { GradingSpec };

const enc = encodeURIComponent;

export const gradingApi = {
  settings: () => request<{ official: OfficialPolicy; policies: OfficialPolicy[] }>('GET', '/api/grading/settings'),
  setPolicy: (official: OfficialPolicy) => request<{ official: OfficialPolicy }>('PUT', '/api/grading/settings', { official }),
  runs: () => request<{ runs: GradingRunInfo[]; arena: ArenaPending[]; official: OfficialPolicy }>('GET', '/api/grading/runs'),
  queue: (runId: string) => request<{ items: QueueItem[]; official: OfficialPolicy }>('GET', `/api/grading/queue?runId=${enc(runId)}`),
  item: (runId: string, key: string) => request<StationItem>('GET', `/api/grading/item/${enc(runId)}/${enc(key)}`),
  spec: (testId: string, caseId?: string) => request<GradingSpec>('GET', `/api/grading/spec/${enc(testId)}${caseId ? `?case=${enc(caseId)}` : ''}`),
  human: (body: HumanGradeInput) => request<CaseResultLite>('POST', '/api/grading/human', body),
  dispute: (body: { runId: string; key: string; rater: string; note: string }) => request<CaseResultLite>('POST', '/api/grading/dispute', body),
  aiEstimate: (runId: string, keys: string[]) => request<AiEstimate>('POST', '/api/grading/ai/estimate', { runId, keys }),
  aiGrade: (runId: string, keys: string[], confirmCostUsd: number) => request<{ outcomes: AiGradeOutcome[]; costUsd: number }>('POST', '/api/grading/ai/grade', { runId, keys, confirmCostUsd }),
  reapply: (runId: string) => request<{ updated: number }>('POST', `/api/grading/runs/${enc(runId)}/reapply`),
  summaries: (runId: string) => request<RunSummaries>('GET', `/api/grading/runs/${enc(runId)}/summaries`),
  summaryEstimate: (runId: string, pairs?: string[]) => request<SummaryEstimate>('POST', `/api/grading/runs/${enc(runId)}/summaries/estimate`, { pairs }),
  summaryGenerate: (runId: string, pairs: string[] | undefined, confirmCostUsd: number) =>
    request<{ summaries: Record<string, AiSummary>; costUsd: number; errors: Record<string, string> }>('POST', `/api/grading/runs/${enc(runId)}/summaries`, { pairs, confirmCostUsd }),
};
