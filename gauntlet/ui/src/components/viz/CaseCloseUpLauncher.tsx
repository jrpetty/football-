/**
 * "See an example" for a test that is not a ladder (every Business prompt test, say): opens the close-up
 * (LevelCloseUp in case mode) on one real case: the question as the model got it, the correct answer behind
 * "Reveal", and every model's result on that case. Deep-linkable: ?example=<test id>&case=<case id>&c=<model>.
 */
import { navigate, useRoute } from '../../router.tsx';
import { LevelCloseUp } from './LevelCloseUp.tsx';
import './closeup/closeup.css';

/** The example case shown first: about a third of the way in (cases run from easy to hard). */
export function exampleCaseIndex(cases: number): number {
  return Math.min(Math.max(0, cases - 1), Math.floor(cases * 0.3));
}

function useSetQuery() {
  const route = useRoute();
  return (patch: Record<string, string | null>) => {
    const q: Record<string, string | null> = {};
    for (const [k, v] of route.query) q[k] = v;
    navigate(route.path, { ...q, ...patch }, true);
  };
}

/** The button on a test card. */
export function CaseExampleButton({ testId, cases, label = 'See an example' }: { testId: string; cases: number; label?: string }) {
  const set = useSetQuery();
  return (
    <button type="button" className="btn sm lc-example-btn" onClick={() => set({ example: testId, case: null, n: String(exampleCaseIndex(cases) + 1), c: null, run: null })}>
      <svg viewBox="0 0 24 24" aria-hidden="true" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
        <circle cx="11" cy="11" r="6.5" />
        <path d="M16 16l4.5 4.5" />
      </svg>
      {label}
    </button>
  );
}

/** Renders the close-up while the page's query names one (place once per page). */
export function CaseCloseUpHost() {
  const route = useRoute();
  const set = useSetQuery();
  const testId = route.query.get('example');
  if (!testId) return null;
  const n = Number(route.query.get('n'));
  return (
    <LevelCloseUp
      testId={testId}
      level={Number.isInteger(n) && n >= 1 ? n : 1}
      caseId={route.query.get('case') ?? undefined}
      contestantId={route.query.get('c') ?? undefined}
      runId={route.query.get('run')}
      mode={route.query.get('slide') === '1' ? 'slide' : 'panel'}
      onClose={() => set({ example: null, case: null, n: null, c: null, run: null })}
    />
  );
}
