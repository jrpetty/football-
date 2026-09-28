/**
 * Grading Station — grade any result yourself, with AI judges, or both.
 *
 * Left: the queue (what still needs a grade). Centre: the brief, the model's
 * output in the universal viewer, the machine's checks and the answer key.
 * Right: the test's own rubric (Human), the judge panel and its verdicts (AI),
 * and how the two agree (Both). Built for the keyboard: 1–9/0 score, ↑↓ move,
 * J/K next/previous, P play, F full screen, R reveal names, Enter save.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.ts';
import { useAsync, useLocalStorage, isTypingTarget } from '../hooks.ts';
import { Link, pathOf, setQuery, useRoute } from '../router.tsx';
import { useToast, useViewerCaption } from '../context.tsx';
import { Empty, ErrorState, PageHead, Seg, SkeletonRows, Switch, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtCost } from '../format.ts';
import { ModelBadge } from '../components/viz/ModelBadge.tsx';
import { sendViewerCommand } from '../components/viewer/commands.ts';
import type { ContestantView } from '../types.ts';
import { gradingApi, type AiEstimate, type GradingNeed, type OfficialPolicy, type QueueItem, type StationItem } from './gradingApi.ts';
import { HumanPanel } from './HumanPanel.tsx';
import { Agreement, CostDialog, Verdicts } from './AiPanel.tsx';
import { Brief, ChecksAndKey, OutputViewer } from './ItemView.tsx';
import { applyKey, draftScore, emptyDraft, isStopDone, nudge, stopsFor, type RubricDraft } from './rubricKeys.ts';
import { PerformanceSummary } from './PerformanceSummary.tsx';
import { invalidateSummaries, summaryFor, useRunSummaries } from './useSummaries.ts';
import { caseName } from '../../../src/grading/summary.ts';
import './grading.css';

type Mode = 'human' | 'ai' | 'both';
type Show = 'todo' | 'gradable' | 'all' | 'disputed';

const NEED: Record<GradingNeed, { label: string; tone: string; long: string }> = {
  grade: { label: 'Needs a grade', tone: 'warn', long: 'Only people grade this test: it has no score until someone does.' },
  'judge-failed': { label: 'Judges missing', tone: 'bad', long: 'No AI judge could grade this during the run. Grade it yourself or ask the AI judges now.' },
  arbitrate: { label: 'Judges split', tone: 'warn', long: 'The run’s judges disagreed by more than 3 points: your grade decides it.' },
  'second-opinion': { label: 'Second opinion', tone: 'accent', long: 'Already graded by the run’s judges. Your grade is stored next to theirs.' },
  review: { label: 'Machine-scored', tone: 'info', long: 'Scored by machine. You can check it and flag a dispute; you can’t overwrite the key.' },
};

const POLICY_TEXT: Record<OfficialPolicy, string> = {
  methodology: 'Methodology (default)',
  human: 'Human first',
  ai: 'AI first',
  average: 'Average of both',
};

const POLICY_HELP: Record<OfficialPolicy, string> = {
  methodology: 'The run’s AI judges count. People decide human-scored tests and answers where the judges split. Answer keys always win.',
  human: 'Your grade, when there is one, replaces the judges’ part of the score. Answer keys always win.',
  ai: 'AI judges count wherever they graded; people count only where no AI grade exists. Answer keys always win.',
  average: 'The mean of your grade and the AI’s. Answer keys always win.',
};

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

export default function GradingStationPage() {
  const { query } = useRoute();
  const toast = useToast();
  const [rater, setRater] = useLocalStorage('gauntlet.rater', '');
  const [blind, setBlind] = useLocalStorage('gauntlet.grading.blind', true);
  const [modes, setModes] = useLocalStorage<Record<string, { run: Mode; tests: Record<string, Mode> }>>('gauntlet.grading.modes', {});
  const runsState = useAsync(() => Promise.all([gradingApi.runs(), api.contestants().catch(() => [] as ContestantView[])]), []);
  const [runsData, cons] = runsState.data ?? [null, [] as ContestantView[]];
  const runs = runsData?.runs ?? [];
  const runId = query.get('run') ?? runs.find((r) => r.todo > 0)?.id ?? runs[0]?.id ?? '';
  const show = (query.get('show') as Show | null) ?? null;
  const testF = query.get('test') ?? '';
  const keyQ = query.get('key');

  const [queue, setQueue] = useState<QueueItem[] | null>(null);
  const [queueErr, setQueueErr] = useState<Error | null>(null);
  const [policy, setPolicy] = useState<OfficialPolicy>('methodology');
  const [item, setItem] = useState<StationItem | null>(null);
  const [itemErr, setItemErr] = useState<Error | null>(null);
  const [draft, setDraft] = useState<RubricDraft>(emptyDraft());
  const [note, setNote] = useState('');
  const [focus, setFocus] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [disputeOpen, setDisputeOpen] = useState(false);
  const [disputeText, setDisputeText] = useState('');
  const [cost, setCost] = useState<{ open: boolean; est: AiEstimate | null; keys: string[]; title: string }>({ open: false, est: null, keys: [], title: '' });
  const [aiBusy, setAiBusy] = useState(false);
  const [help, setHelp] = useState(false);
  const noteRef = useRef<HTMLTextAreaElement>(null);
  const disputeRef = useRef<HTMLTextAreaElement>(null);
  const summaries = useRunSummaries(runId || null);

  useViewerCaption('The grading station: every answer the models gave, graded against the test’s own rubric by a person, by AI judges from other companies, or both. Model names stay hidden while grading.');

  const conById = useMemo(() => new Map(cons.map((c) => [c.id, c])), [cons]);
  const names = useMemo(() => new Map(cons.map((c) => [c.id, c.label])), [cons]);

  const loadQueue = useCallback(async () => {
    if (!runId) return;
    try {
      const q = await gradingApi.queue(runId);
      setQueue(q.items);
      setPolicy(q.official);
      setQueueErr(null);
    } catch (e) {
      setQueueErr(e as Error);
    }
  }, [runId]);
  useEffect(() => {
    setQueue(null);
    void loadQueue();
  }, [loadQueue]);

  // Blind letters: a stable shuffle per run, so "Model B" is the same model all session.
  const letters = useMemo(() => {
    const ids = [...new Set((queue ?? []).map((i) => i.contestantId))].sort((a, b) => hash(runId + a) - hash(runId + b));
    return new Map(ids.map((id, i) => [id, String.fromCharCode(65 + (i % 26))]));
  }, [queue, runId]);

  const counts = useMemo(() => {
    const q = queue ?? [];
    return { todo: q.filter((i) => i.todo).length, gradable: q.filter((i) => i.humanRole !== 'dispute').length, all: q.length, disputed: q.filter((i) => i.disputes > 0).length };
  }, [queue]);
  const effShow: Show = show ?? (counts.todo ? 'todo' : counts.gradable ? 'gradable' : 'all');
  const visible = useMemo(
    () =>
      (queue ?? [])
        .filter((i) => (effShow === 'todo' ? i.todo : effShow === 'gradable' ? i.humanRole !== 'dispute' : effShow === 'disputed' ? i.disputes > 0 : true))
        .filter((i) => !testF || i.testId === testF),
    [queue, effShow, testF],
  );
  const tests = useMemo(() => [...new Map((queue ?? []).map((i) => [i.testId, i.testName])).entries()], [queue]);
  const current = visible.find((i) => i.key === keyQ) ?? (queue ?? []).find((i) => i.key === keyQ) ?? visible[0] ?? null;
  const curIdx = current ? visible.findIndex((i) => i.key === current.key) : -1;

  const modeOf = (testId: string): Mode => modes[runId]?.tests[testId] ?? modes[runId]?.run ?? 'human';
  const mode: Mode = current ? modeOf(current.testId) : modes[runId]?.run ?? 'human';
  const setMode = (m: Mode, scope: 'run' | 'test') => {
    const cur = modes[runId] ?? { run: 'human' as Mode, tests: {} };
    const next = scope === 'run' ? { run: m, tests: {} } : { ...cur, tests: { ...cur.tests, [current?.testId ?? '']: m } };
    setModes({ ...modes, [runId]: next });
  };
  const perTest = !!(current && modes[runId]?.tests[current.testId]);

  // Load the current item.
  useEffect(() => {
    if (!current) {
      setItem(null);
      return;
    }
    let alive = true;
    setItemErr(null);
    setRevealed(false);
    setDisputeOpen(false);
    setDisputeText('');
    gradingApi
      .item(current.runId, current.key)
      .then((it) => {
        if (!alive) return;
        setItem(it);
        const mine = (it.result.humanScores ?? []).find((h) => h.rater === rater.trim());
        const d: RubricDraft = { criteria: {}, requirements: { ...(mine?.requirements ?? {}) }, label: mine?.label };
        for (const c of it.spec.criteria) if (!c.requirements && typeof mine?.criteria?.[c.id] === 'number') d.criteria[c.id] = mine.criteria[c.id]!;
        setDraft(d);
        setNote(mine?.note ?? '');
        const stops = stopsFor(it.spec);
        const firstOpen = stops.findIndex((s) => !isStopDone(it.spec, d, s));
        setFocus(firstOpen < 0 ? 0 : firstOpen);
      })
      .catch((e: unknown) => alive && setItemErr(e as Error));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.key, current?.runId]);

  const stops = useMemo(() => (item ? stopsFor(item.spec) : []), [item]);
  const score = item ? draftScore(item.spec, draft) : null;
  const canHuman = !!item && item.spec.humanRole !== 'dispute';

  const go = (delta: number) => {
    if (!visible.length) return;
    const i = curIdx < 0 ? 0 : Math.max(0, Math.min(visible.length - 1, curIdx + delta));
    setQuery({ key: visible[i]!.key });
  };
  const goNextOpen = (afterKey: string, list: QueueItem[]) => {
    const idx = list.findIndex((i) => i.key === afterKey);
    const next = list.slice(idx + 1).find((i) => (effShow === 'todo' ? i.todo : true)) ?? list.find((i) => i.key !== afterKey && (effShow === 'todo' ? i.todo : false));
    if (next) setQuery({ key: next.key });
  };

  const refreshAfter = async (key: string) => {
    invalidateSummaries(runId);
    const q = await gradingApi.queue(runId).catch(() => null);
    const list = q?.items ?? queue ?? [];
    if (q) setQueue(q.items);
    const filtered = list.filter((i) => (effShow === 'todo' ? i.todo || i.key === key : true)).filter((i) => !testF || i.testId === testF);
    goNextOpen(key, filtered);
    if (!filtered.some((i) => i.key !== key && (effShow !== 'todo' || i.todo))) {
      const it = await gradingApi.item(runId, key).catch(() => null);
      if (it) setItem(it);
    }
  };

  const save = async () => {
    if (!item || !current || saving) return;
    if (!canHuman) {
      go(1);
      return;
    }
    if (!rater.trim()) {
      toast.error('Type your rater name (top right) first: it is stored with every grade.');
      document.getElementById('gs-rater')?.focus();
      return;
    }
    if (score === null) {
      toast.error(item.spec.labels?.length ? 'Pick a label first (keys 1–9).' : 'Grade every line of the rubric first.');
      return;
    }
    setSaving(true);
    try {
      const lite = await gradingApi.human({ runId, key: current.key, rater: rater.trim(), criteria: draft.criteria, requirements: draft.requirements, label: draft.label, note: note.trim() || undefined, blind: blind && !revealed });
      toast.success(`${(score * 10).toFixed(1)}/10 saved · official score now ${lite.score === null ? '—' : Math.round(lite.score * 100)}/100`, 'Grade saved');
      await refreshAfter(current.key);
    } catch (e) {
      toast.error(e, 'Could not save the grade');
    } finally {
      setSaving(false);
    }
  };

  const saveDispute = async () => {
    if (!current || !disputeText.trim()) return;
    if (!rater.trim()) {
      toast.error('Type your rater name first.');
      return;
    }
    try {
      await gradingApi.dispute({ runId, key: current.key, rater: rater.trim(), note: disputeText.trim() });
      toast.success('Shown in the result inspector next to the score. The score itself is unchanged.', 'Dispute flagged');
      setDisputeOpen(false);
      setDisputeText('');
      await loadQueue();
      const it = await gradingApi.item(runId, current.key);
      setItem(it);
    } catch (e) {
      toast.error(e, 'Could not flag the dispute');
    }
  };

  const askAi = async (keys: string[], title: string) => {
    if (!keys.length) return;
    setCost({ open: true, est: null, keys, title });
    try {
      const est = await gradingApi.aiEstimate(runId, keys);
      setCost({ open: true, est, keys, title });
    } catch (e) {
      setCost({ open: false, est: null, keys: [], title: '' });
      toast.error(e, 'Could not estimate the cost');
    }
  };
  const confirmAi = async () => {
    if (!cost.est) return;
    setAiBusy(true);
    try {
      const r = await gradingApi.aiGrade(runId, cost.keys, cost.est.totalUsd);
      const ok = r.outcomes.filter((o) => o.ok).length;
      const failed = r.outcomes.filter((o) => !o.ok);
      if (ok) toast.success(`${ok} graded by AI judges for ${fmtCost(r.costUsd)}.${failed.length ? ` ${failed.length} could not be graded.` : ''}`, 'AI grades saved');
      else toast.error(failed[0]?.error ?? 'No judge verdicts', 'AI grading failed');
      setCost({ open: false, est: null, keys: [], title: '' });
      invalidateSummaries(runId);
      await loadQueue();
      if (current) setItem(await gradingApi.item(runId, current.key));
    } catch (e) {
      toast.error(e, 'AI grading failed');
    } finally {
      setAiBusy(false);
    }
  };

  const changePolicy = async (p: OfficialPolicy) => {
    try {
      await gradingApi.setPolicy(p);
      setPolicy(p);
      const r = await gradingApi.reapply(runId);
      toast.info(`${r.updated} graded result${r.updated === 1 ? '' : 's'} in this run re-scored under “${p}”.`, 'Official-score rule changed');
      invalidateSummaries(runId);
      await loadQueue();
      if (current) setItem(await gradingApi.item(runId, current.key));
    } catch (e) {
      toast.error(e, 'Could not change the rule');
    }
  };

  // Keyboard.
  const keyRef = useRef<(e: KeyboardEvent) => void>(() => {});
  keyRef.current = (e: KeyboardEvent) => {
    if (e.metaKey || e.altKey) return;
    if (document.querySelector('.modal-root, .drawer-root')) return;
    const typing = isTypingTarget(e.target);
    if (typing) {
      if (e.key === 'Escape') (e.target as HTMLElement).blur();
      if (e.key === 'Enter' && e.ctrlKey) {
        e.preventDefault();
        if (disputeOpen) void saveDispute();
        else void save();
      }
      return;
    }
    if (e.ctrlKey) return;
    const k = e.key;
    if (k === '?') return setHelp((h) => !h);
    if (k === 'j' || k === 'J') return go(1);
    if (k === 'k' || k === 'K') return go(-1);
    if (k === 'p' || k === 'P') return sendViewerCommand('play');
    if (k === 'f' || k === 'F') return sendViewerCommand('fullscreen');
    if (k === 'r' || k === 'R') return setRevealed((v) => !v);
    if (!item) return;
    if ((k === 'a' || k === 'A') && mode !== 'human' && item.aiPanel.ok) return void askAi([item.result.key], 'Grade this answer with AI judges?');
    if ((k === 'd' || k === 'D') && !canHuman) {
      e.preventDefault();
      setDisputeOpen(true);
      window.setTimeout(() => disputeRef.current?.focus(), 30);
      return;
    }
    if (k === 'Enter') {
      e.preventDefault();
      if (mode === 'ai') {
        if (item.aiPanel.ok) void askAi([item.result.key], 'Grade this answer with AI judges?');
        else go(1);
      } else void save();
      return;
    }
    if (!canHuman || mode === 'ai') return;
    if (k === 'n' || k === 'N') {
      e.preventDefault();
      noteRef.current?.focus();
      return;
    }
    if (k === 'ArrowDown' || k === 'ArrowUp') {
      e.preventDefault();
      setFocus((f) => Math.max(0, Math.min(stops.length - 1, f + (k === 'ArrowDown' ? 1 : -1))));
      return;
    }
    if (k === 'ArrowLeft' || k === 'ArrowRight') {
      e.preventDefault();
      setDraft((d) => nudge(item.spec, d, stops[focus], k === 'ArrowRight' ? 1 : -1));
      return;
    }
    const next = applyKey(item.spec, draft, stops[focus], k);
    if (next) {
      e.preventDefault();
      setDraft(next);
      setFocus((f) => Math.min(stops.length - 1, f + 1));
    }
  };
  useEffect(() => {
    const h = (e: KeyboardEvent) => keyRef.current(e);
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, []);

  // Keep the focused rubric line in view.
  useEffect(() => {
    // Scroll only the grading column, never the page (the viewer stays where it is).
    const el = document.querySelector<HTMLElement>('.hp .focus');
    const box = document.querySelector<HTMLElement>('.gs-side');
    if (!el || !box) return;
    const top = el.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop;
    const bottom = top + el.offsetHeight;
    if (top < box.scrollTop + 8) box.scrollTo({ top: top - 60, behavior: 'smooth' });
    else if (bottom > box.scrollTop + box.clientHeight - 8) box.scrollTo({ top: bottom - box.clientHeight + 80, behavior: 'smooth' });
  }, [focus]);

  const who = (id: string, full = false) => {
    const c = conById.get(id);
    if (blind && !revealed) return { label: `Model ${letters.get(id) ?? '?'}`, color: 'var(--text-3)', hidden: true };
    return { label: c?.label ?? id, color: c?.color ?? 'var(--accent)', hidden: false, vendor: full ? c?.vendor : undefined };
  };

  if (runsState.loading && !runsData) {
    return (
      <div className="page">
        <SkeletonRows rows={8} h={44} />
      </div>
    );
  }
  if (runsState.error && !runsData) return <ErrorState error={runsState.error} onRetry={runsState.reload} title="Couldn’t load the grading station" />;

  const model = current ? who(current.contestantId, true) : null;
  const need = current ? NEED[current.need] : null;
  const shown = current ? summaryFor(summaries, current.contestantId, current.testId) : null;
  const testKeys = current ? (queue ?? []).filter((i) => i.testId === current.testId && i.aiRole !== 'none').map((i) => i.key) : [];

  return (
    <div className="page gs-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Target style={{ width: 14, height: 14 }} /> Grading station
          </span>
        }
        title="Grade every answer: you, the AI, or both"
        sub="Each answer is shown with its exact brief, everything it produced and what the test is really graded on. Model names stay hidden until you reveal them."
        actions={
          <div className="gs-head-actions">
            <label className="field" style={{ minWidth: 170 }}>
              <span className="label">Your rater name</span>
              <input id="gs-rater" className="input" placeholder="e.g. mika" value={rater} onChange={(e) => setRater(e.target.value)} />
            </label>
            <div className="field">
              <span className="label">Graded by {perTest ? '(this test)' : '(this run)'}</span>
              <Seg
                label="Grading mode"
                value={mode}
                onChange={(m) => setMode(m, perTest ? 'test' : 'run')}
                options={[
                  { value: 'human', label: 'Human', title: 'You grade with the rubric' },
                  { value: 'ai', label: 'AI', title: 'AI judges from other vendors grade (cost shown first)' },
                  { value: 'both', label: 'Both', title: 'You and the AI, side by side' },
                ]}
              />
            </div>
            <div className="field">
              <span className="label">Blind</span>
              <span className="row" style={{ gap: 8, height: 36 }}>
                <Switch checked={blind} onChange={setBlind} label="Hide model names" />
                <span className="muted" style={{ fontSize: '0.8rem' }}>
                  {blind ? 'names hidden' : 'names shown'}
                </span>
              </span>
            </div>
          </div>
        }
      />

      <div className="filter-bar gs-filter">
        <select className="select" style={{ width: 340 }} value={runId} onChange={(e) => setQuery({ run: e.target.value, key: null, test: null, show: null })} aria-label="Run">
          {runs.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name}
              {r.todo ? ` · ${r.todo} to grade` : ''}
            </option>
          ))}
        </select>
        <div className="seg" role="group" aria-label="Which answers">
          {(
            [
              ['todo', 'To grade'],
              ['gradable', 'Can grade'],
              ['all', 'Everything'],
              ['disputed', 'Disputed'],
            ] as const
          ).map(([k, l]) => (
            <button key={k} type="button" aria-pressed={effShow === k} onClick={() => setQuery({ show: k, key: null })}>
              {l}
              <span className="count-pill">{counts[k]}</span>
            </button>
          ))}
        </div>
        <select className="select" style={{ width: 230 }} value={testF} onChange={(e) => setQuery({ test: e.target.value || null, key: null })} aria-label="Test">
          <option value="">All tests</option>
          {tests.map(([id, name]) => (
            <option key={id} value={id}>
              {name}
            </option>
          ))}
        </select>
        <span className="spacer" />
        <button type="button" className="btn sm ghost" onClick={() => setHelp(!help)} aria-expanded={help}>
          <Icon.Keyboard /> Shortcuts <kbd>?</kbd>
        </button>
      </div>

      {help && (
        <div className="gs-help card">
          {[
            ['1–9, 0', 'score the highlighted line (0 = 10 on a 0–10 scale, X = zero)'],
            ['1 / 2 / 0', 'requirement met / half met / missed'],
            ['↑ ↓', 'move between rubric lines'],
            ['← →', 'nudge the score (half points)'],
            ['Enter', 'save and go to the next answer (AI mode: ask the judges)'],
            ['J / K', 'next / previous answer'],
            ['P', 'play the game or media'],
            ['F', 'full screen'],
            ['R', 'reveal / hide model names'],
            ['N', 'write a note (Ctrl+Enter saves)'],
            ['A', 'ask the AI judges (cost shown first)'],
            ['D', 'dispute a machine score'],
          ].map(([k, t]) => (
            <div key={k} className="gs-help-row">
              <kbd>{k}</kbd>
              <span>{t}</span>
            </div>
          ))}
        </div>
      )}

      {!runs.length ? (
        <div className="card">
          <Empty icon={<Icon.Target />} title="Nothing to grade yet" actions={<Link to="/run/new" className="btn primary"><Icon.Rocket /> Start a run</Link>}>
            Results from your runs appear here: answers only a person can grade, answers the judges disagreed on, and everything else for a second look.
          </Empty>
        </div>
      ) : queueErr ? (
        <ErrorState error={queueErr} onRetry={() => void loadQueue()} title="Couldn’t load this run’s answers" />
      ) : (
        <div className="gs-grid">
          <aside className="gs-queue card" aria-label="Answers to grade">
            {(runsData?.arena.length ?? 0) > 0 && (
              <div className="gs-arena">
                <div className="gs-card-k">Arena games waiting for a judge</div>
                {runsData!.arena.map((a) => (
                  <Link key={a.id} to={pathOf('arena', a.id, 'judge')} className="gs-arena-row">
                    <span>{a.gameName}</span>
                    <span className="muted ellipsis">{a.name}</span>
                    <span className="count-pill">{a.pending}</span>
                  </Link>
                ))}
              </div>
            )}
            {queue === null ? (
              <SkeletonRows rows={8} h={52} />
            ) : visible.length === 0 ? (
              <div className="gs-queue-empty">
                <Icon.Check />
                <b>{effShow === 'todo' ? 'All graded' : 'Nothing here'}</b>
                <span className="muted">{effShow === 'todo' ? 'Every answer that needed a person has a grade.' : 'Try another filter.'}</span>
              </div>
            ) : (
              <div className="gs-queue-list" role="listbox" aria-label="Answers">
                {visible.map((i, n) => {
                  const w = who(i.contestantId);
                  const header = n === 0 || visible[n - 1]!.testId !== i.testId;
                  return (
                    <div key={i.key} style={{ display: 'contents' }}>
                      {header && <div className="gs-q-test">{i.testName}</div>}
                      <button type="button" role="option" aria-selected={current?.key === i.key} className={cx('gs-q', current?.key === i.key && 'on', !i.todo && (i.humans > 0 || i.ais > 0) && 'graded')} onClick={() => setQuery({ key: i.key })}>
                        <span className="gs-q-dot" style={{ background: w.color }} />
                        <span className="gs-q-main">
                          <span className="gs-q-who">{w.label}</span>
                          <span className="gs-q-case">
                            {caseName(i.caseId, 'question')}
                            {i.repeat > 0 ? ` · try ${i.repeat + 1}` : ''}
                          </span>
                        </span>
                        <span className={cx('gs-need', NEED[i.need].tone)}>{i.humans > 0 ? `✓ ${i.humans}` : NEED[i.need].label}</span>
                      </button>
                    </div>
                  );
                })}
              </div>
            )}
          </aside>

          <main className="gs-main" aria-live="polite">
            {!current ? (
              <div className="card">
                <Empty icon={<Icon.Check />} title="Pick an answer on the left" />
              </div>
            ) : itemErr ? (
              <ErrorState error={itemErr} onRetry={() => setQuery({ key: current.key })} title="Couldn’t load this answer" />
            ) : !item || item.result.key !== current.key ? (
              <div className="card pad">
                <SkeletonRows rows={6} h={48} />
              </div>
            ) : (
              <>
                <section className="gs-item-head card">
                  <div className="gs-ih-top">
                    <span className="gs-ih-model">
                      {model?.hidden ? (
                        <span className="gs-blind-chip">
                          <Icon.EyeOff /> {model.label}
                        </span>
                      ) : (
                        <ModelBadge label={model?.label ?? ''} color={model?.color} size="md" />
                      )}
                    </span>
                    <div className="gs-ih-t">
                      <h2>{item.test?.name ?? current.testName}</h2>
                      <span className="muted">
                        {caseName(current.caseId, item.spec.unit)}
                        {current.repeat > 0 ? ` · try ${current.repeat + 1}` : ''} · answer {curIdx + 1} of {visible.length}
                      </span>
                    </div>
                    <span className="spacer" />
                    <div className="gs-ih-score">
                      <span className="k">Official score</span>
                      <span className="v tnum">{item.result.score === null ? '—' : Math.round(item.result.score * 100)}</span>
                      <span className="s">{item.official.source ? `from ${item.official.source === 'auto' ? 'the machine' : item.official.source === 'ai' ? 'AI judges' : item.official.source === 'arbitration' ? 'human arbitration' : item.official.source}` : item.result.status === 'pending-human' ? 'waiting for a grade' : 'as recorded'}</span>
                    </div>
                  </div>
                  <div className="gs-graded-on">
                    <span className={cx('badge lg', need?.tone)}>{need?.label}</span>
                    <span>
                      <b>Graded on:</b> {item.spec.gradedOn}
                    </span>
                  </div>
                  {item.spec.howScored.length > 0 && (
                    <ol className="gs-steps">
                      {item.spec.howScored.map((s, i) => (
                        <li key={i}>{s.text}</li>
                      ))}
                    </ol>
                  )}
                  {shown && <PerformanceSummary s={shown} variant="card" label={model?.hidden ? `${model.label} on this test` : `${model?.label} on this test`} />}
                </section>

                <OutputViewer key={item.result.key} item={item} height={560} modelLabel={model?.label ?? ''} />
                <Brief key={`b-${item.result.key}`} item={item} />
                <ChecksAndKey item={item} />
                {(item.result.disputes?.length ?? 0) > 0 && (
                  <section className="gs-card gs-disputes">
                    <div className="gs-card-k">Disputes</div>
                    {item.result.disputes!.map((d, i) => (
                      <div key={i} className="gs-dispute">
                        <Icon.Flag /> <b>{d.rater}</b>: {d.note}
                      </div>
                    ))}
                  </section>
                )}
              </>
            )}
          </main>

          <aside className="gs-side" aria-label="Grading">
            {item && current && item.result.key === current.key && (
              <>
                {canHuman && mode !== 'ai' && (
                  <section className="card gs-panel">
                    <div className="gs-panel-head">
                      <h3>Your grade</h3>
                      <span className="muted">{item.spec.labels?.length ? 'pick one label' : `${item.spec.criteria.length} rubric line${item.spec.criteria.length === 1 ? '' : 's'} · ${item.spec.scaleMax} points`}</span>
                    </div>
                    {item.spec.humanRole === 'second-opinion' && <div className="gs-small muted" style={{ marginBottom: 8 }}>Second opinion: stored and shown next to the machine score, which stays official.</div>}
                    <HumanPanel ref={noteRef} spec={item.spec} draft={draft} stops={stops} focus={focus} onFocus={setFocus} onChange={setDraft} note={note} onNote={setNote} />
                    <button type="button" className="btn primary lg gs-save" onClick={() => void save()} disabled={saving || score === null}>
                      <Icon.Check /> {saving ? 'Saving…' : 'Save & next'} <kbd>Enter</kbd>
                    </button>
                  </section>
                )}
                {!canHuman && (
                  <section className="card gs-panel">
                    <div className="gs-panel-head">
                      <h3>Scored by machine</h3>
                    </div>
                    <p className="gs-small">
                      This test is checked {item.spec.kind === 'simulation' ? 'by the simulation’s formula' : 'against an answer key'}, so nobody — not you, not an AI — can change its score. If the key or the check looks wrong, flag a dispute: it appears in the result inspector.
                    </p>
                    {disputeOpen ? (
                      <div className="stack tight">
                        <textarea ref={disputeRef} className="textarea" rows={3} placeholder="What looks wrong? e.g. “The key says 42 but the puzzle allows 41.”" value={disputeText} onChange={(e) => setDisputeText(e.target.value)} />
                        <div className="row" style={{ gap: 8 }}>
                          <button className="btn ghost sm" onClick={() => setDisputeOpen(false)}>
                            Cancel
                          </button>
                          <button className="btn primary sm" onClick={() => void saveDispute()} disabled={!disputeText.trim()}>
                            <Icon.Flag /> Flag dispute <kbd>Ctrl+Enter</kbd>
                          </button>
                        </div>
                      </div>
                    ) : (
                      <div className="row" style={{ gap: 8 }}>
                        <button className="btn sm" onClick={() => setDisputeOpen(true)}>
                          <Icon.Flag /> Dispute <kbd>D</kbd>
                        </button>
                        <button className="btn sm primary" onClick={() => go(1)}>
                          Looks right, next <kbd>Enter</kbd>
                        </button>
                      </div>
                    )}
                  </section>
                )}
                {mode !== 'human' && item.spec.aiRole !== 'none' && (
                  <section className="card gs-panel">
                    <div className="gs-panel-head">
                      <h3>AI judges</h3>
                      <span className="muted">{item.spec.aiRole === 'second-opinion' ? 'second opinion' : 'same rubric as you'}</span>
                    </div>
                    <div className="ap-panel">
                      {item.aiPanel.judges.map((j) => (
                        <span key={j.id} className="ap-judge">
                          <b>{j.label}</b> <span className="muted">{j.vendor}</span>
                          {j.vision && <Icon.Eye />}
                        </span>
                      ))}
                    </div>
                    {!item.aiPanel.ok && <div className="callout warn gs-small">{item.aiPanel.reason}</div>}
                    <Verdicts item={item} names={names} />
                    <div className="row wrap" style={{ gap: 8, marginTop: 10 }}>
                      <button className="btn primary sm" disabled={!item.aiPanel.ok} onClick={() => void askAi([item.result.key], 'Grade this answer with AI judges?')}>
                        <Icon.Sparkles /> Ask the judges <kbd>A</kbd>
                      </button>
                      {testKeys.length > 1 && (
                        <button className="btn sm" disabled={!item.aiPanel.ok} onClick={() => void askAi(testKeys, `Grade all ${testKeys.length} answers on “${item.test?.name}” with AI judges?`)}>
                          Whole test ({testKeys.length})
                        </button>
                      )}
                    </div>
                  </section>
                )}
                {mode !== 'human' && item.spec.aiRole === 'none' && canHuman && (
                  <section className="card gs-panel">
                    <div className="gs-small muted">This test has no rubric for AI judges; the machine checks are its score.</div>
                  </section>
                )}
                {mode === 'both' && canHuman && item.spec.aiRole !== 'none' && (
                  <section className="card gs-panel">
                    <Agreement human={score ?? item.humanScore} ai={item.aiScore} level={score !== null && item.aiScore !== null ? (Math.abs(score - item.aiScore) <= 0.1 ? 'agree' : Math.abs(score - item.aiScore) <= 0.3 ? 'close' : 'disagree') : item.agreement.level} />
                  </section>
                )}
                {canHuman && (
                  <section className="card gs-panel gs-policy">
                    <div className="gs-card-k">Which grade counts</div>
                    <select className="select" value={policy} onChange={(e) => void changePolicy(e.target.value as OfficialPolicy)} aria-label="Official score rule">
                      {(Object.keys(POLICY_TEXT) as OfficialPolicy[]).map((p) => (
                        <option key={p} value={p}>
                          {POLICY_TEXT[p]}
                        </option>
                      ))}
                    </select>
                    <div className="gs-small muted">{POLICY_HELP[policy]}</div>
                    {item.official.why && <div className="gs-small">This answer: {item.official.why}</div>}
                  </section>
                )}
              </>
            )}
          </aside>
        </div>
      )}

      <CostDialog open={cost.open} est={cost.est} busy={aiBusy} title={cost.title} onCancel={() => !aiBusy && setCost({ open: false, est: null, keys: [], title: '' })} onConfirm={() => void confirmAi()} />
    </div>
  );
}
