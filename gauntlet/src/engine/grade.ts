import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { hasApiKey, loadContestants, loadProviders, loadSettings, snapshotContestant } from '../core/config.ts';
import { DATA_DIR } from '../core/paths.ts';
import { caseScorer, getTest, renderCase, type RenderedCase } from '../core/registry.ts';
import type { ArtifactKind, ArtifactRef, Contestant, TranscriptEntry } from '../core/types.ts';
import { createAdapter } from '../providers/index.ts';
import { scoreResponse, type ScoringOutcome } from '../scoring/index.ts';
import { createJudgePanel, selectJudges } from './runner.ts';
import { Semaphore } from './semaphore.ts';
import type { CallTarget } from './recorder.ts';

export interface GradeRequest {
  testId: string;
  caseId: string;
  /** The model's reply, pasted exactly as produced. */
  response: string;
  /** Vendor of the model that produced the reply (keeps judges from grading their own vendor). */
  vendor?: string;
  /** Override the judge panel (defaults to settings.judges with available keys). */
  judgeIds?: string[];
}

export interface GradeResult {
  outcome: ScoringOutcome;
  rendered: RenderedCase;
  judgeCostUsd: number;
  judgeTranscript: TranscriptEntry[];
  artifacts: ArtifactRef[];
  gradeId: string;
}

/**
 * Grade a pasted reply with exactly the scorer the harness uses in runs.
 * Useful for chat-only models or for checking a single answer; nothing is
 * added to the leaderboard (use a manual contestant in a run for that).
 */
export async function gradePasted(req: GradeRequest): Promise<GradeResult> {
  const test = getTest(req.testId);
  if (!test) throw new Error(`Unknown test "${req.testId}"`);
  const d = test.definition;
  if (d.kind !== 'prompt') throw new Error('Simulation tests are interactive: add a manual contestant to a run and answer each turn in the Manual Inbox.');
  const c = d.cases.find((x) => x.id === req.caseId);
  if (!c) throw new Error(`Unknown case "${req.caseId}" in ${d.id}`);
  if (typeof req.response !== 'string' || !req.response.trim()) throw new Error('Paste the model reply into "response"');

  const settings = loadSettings();
  const providers = loadProviders();
  const all = loadContestants();
  const judgeIds = req.judgeIds ?? settings.judges;
  const judges: Contestant[] = judgeIds
    .map((id) => all.find((m) => m.id === id))
    .filter((m): m is Contestant => Boolean(m))
    .filter((m) => {
      const p = providers.find((x) => x.id === m.provider);
      return p ? hasApiKey(p) && p.type !== 'manual' : false;
    });
  const pseudo: Contestant = { id: 'pasted', label: 'Pasted reply', vendor: req.vendor ?? '', provider: 'manual', model: 'pasted', color: '#000000', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } };
  const panelJudges = selectJudges(judges.map(snapshotContestant), pseudo, settings.judgeExcludeSameVendor);

  const targets = new Map<string, CallTarget>();
  const targetFor = (m: Contestant): CallTarget => {
    let t = targets.get(m.id);
    if (!t) {
      const p = providers.find((x) => x.id === m.provider)!;
      t = { contestant: m, adapter: createAdapter(m, p), semaphore: new Semaphore(p.maxConcurrency ?? 4) };
      targets.set(m.id, t);
    }
    return t;
  };
  const judgeTranscript: TranscriptEntry[] = [];
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10 * 60 * 1000);
  const gradeId = randomUUID();
  const artifacts: ArtifactRef[] = [];
  const dir = join(DATA_DIR, 'graded', gradeId);
  const rendered = renderCase(d, c);
  try {
    const outcome = await scoreResponse({
      scorer: caseScorer(d, c),
      expected: c.expected,
      response: req.response,
      stopReason: 'end',
      taskText: [rendered.system ? `[System prompt]\n${rendered.system}` : '', ...rendered.turns.map((t) => `[User]\n${t}`)].filter(Boolean).join('\n\n'),
      judges: createJudgePanel({
        judges: panelJudges,
        targetFor,
        policy: { maxRetries: settings.maxRetries, temperature: 0, defaultMaxOutputTokens: 16000 },
        signal: controller.signal,
        record: (e) => judgeTranscript.push(e),
      }),
      saveArtifact: (name: string, kind: ArtifactKind, content: string | Buffer) => {
        mkdirSync(dir, { recursive: true });
        const safe = name.replace(/[^A-Za-z0-9._-]/g, '_');
        writeFileSync(join(dir, safe), content);
        const ref = { name: safe, kind, file: `${gradeId}/${safe}`, bytes: typeof content === 'string' ? Buffer.byteLength(content) : content.length };
        artifacts.push(ref);
        return ref;
      },
      signal: controller.signal,
    });
    return { outcome, rendered, judgeCostUsd: judgeTranscript.reduce((s, e) => s + e.costUsd, 0), judgeTranscript, artifacts, gradeId };
  } finally {
    clearTimeout(timer);
  }
}
