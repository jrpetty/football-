/**
 * The Home page's "Getting started" checklist, worked out from the real state of the app:
 *   1. Add an API key          done when any key is saved
 *   2. Run your first test     done when any run exists
 *   3. See how the models did  done once a finished run's results have been opened
 *   4. Make your first slides  done once the Presenter has been opened
 * Pure (no React, no network), so test/simple-mode.test.ts can check it.
 */

export type StepId = 'key' | 'run' | 'results' | 'video';

export interface ChecklistInput {
  /** Any API key saved (null: still loading). */
  anyKey: boolean | null;
  /** Runs, newest first or in any order (null: still loading). */
  runs: Array<{ id: string; status: string; createdAt: string; completedJobs: number }> | null;
  seenResults: boolean;
  seenPresenter: boolean;
}

export interface ChecklistStep {
  id: StepId;
  n: number;
  title: string;
  /** One sentence: why this step matters. */
  why: string;
  cta: { label: string; to: string };
  done: boolean;
}

export interface Checklist {
  steps: ChecklistStep[];
  /** The first step not done yet (null when everything is done). */
  next: ChecklistStep | null;
  doneCount: number;
  allDone: boolean;
  /** Still waiting for the server. */
  loading: boolean;
}

/** The newest run that has at least one finished result (the one to look at or present). */
export function latestFinishedRun<R extends { status: string; createdAt: string; completedJobs: number }>(runs: R[] | null): R | null {
  if (!runs) return null;
  const done = runs.filter((r) => r.completedJobs > 0 && r.status !== 'running' && r.status !== 'queued');
  done.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return done[0] ?? null;
}

export function deriveChecklist(i: ChecklistInput): Checklist {
  const hasKey = i.anyKey === true;
  const hasRun = !!i.runs && i.runs.length > 0;
  const finished = latestFinishedRun(i.runs);
  const steps: ChecklistStep[] = [
    {
      id: 'key',
      n: 1,
      title: 'Add an API key',
      why: 'A key is how Gauntlet talks to an AI company’s models. You paste it once and it stays on this computer.',
      cta: { label: 'Add a key', to: '/keys' },
      done: hasKey,
    },
    {
      id: 'run',
      n: 2,
      title: 'Run your first test',
      why: 'Start with the Quick Check: five short questions that cost about 2p, just to see it all working.',
      cta: { label: 'Run the 2p Quick Check', to: '/run/new?suite=quick-check' },
      done: hasRun,
    },
    {
      id: 'results',
      n: 3,
      title: 'See how the models did',
      why: 'Open a finished run to see every score, every answer and who won.',
      cta: { label: 'See the results', to: finished ? `/runs/${encodeURIComponent(finished.id)}` : '/runs' },
      done: i.seenResults && hasRun,
    },
    {
      id: 'video',
      n: 4,
      title: 'Make your first video slides',
      why: 'The Presenter turns a run into full-screen slides that explain themselves, ready to record.',
      cta: { label: 'Open the Presenter', to: finished ? `/present/${encodeURIComponent(finished.id)}` : '/present' },
      done: i.seenPresenter && hasRun,
    },
  ];
  const next = steps.find((s) => !s.done) ?? null;
  const doneCount = steps.filter((s) => s.done).length;
  return { steps, next, doneCount, allDone: !next, loading: i.anyKey === null || i.runs === null };
}
