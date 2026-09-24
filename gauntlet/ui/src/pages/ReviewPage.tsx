/** Blind Review — rate anonymised outputs side by side; identities revealed after scoring. */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, artifactUrl } from '../api.ts';
import { useAsync, useHotkeys, useLocalStorage } from '../hooks.ts';
import { Link, setQuery, useRoute } from '../router.tsx';
import { useToast, useViewerCaption } from '../context.tsx';
import { Callout, Empty, ErrorState, ModelChip, PageHead, ScorePill, SkeletonRows, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import type { CaseResult, ContestantView, ReviewItem, ReviewReason, TestDetail, TestSummary } from '../types.ts';

const REASONS: Record<ReviewReason, { label: string; long: string; tone: string }> = {
  'judge-disagreement': { label: 'Judges disagreed', long: 'Judges disagreed — your rating becomes the final score', tone: 'warn' },
  'human-scored': { label: 'Human-scored', long: 'Human-scored test — only people grade this one', tone: 'info' },
  'second-opinion': { label: 'Second opinion', long: 'Second opinion on an automatically judged build', tone: 'accent' },
};
const PRIORITY: Record<string, number> = { 'judge-disagreement': 0, 'human-scored': 1, 'second-opinion': 2 };
const reasonOf = (i: ReviewItem): ReviewReason => i.reason ?? 'human-scored';

interface Side {
  item: ReviewItem;
  score: number;
}

function BlindOutput({ result }: { result: CaseResult | null | 'error' }) {
  if (result === 'error') return <div className="chart-empty">Couldn’t load this output.</div>;
  if (!result) return <SkeletonRows rows={5} h={28} />;
  const art = result.artifacts?.find((a) => a.kind === 'html' || a.kind === 'svg' || a.kind === 'png');
  if (art) {
    const url = artifactUrl(result.runId, art.file);
    return art.kind === 'html' ? (
      <iframe className="blind-frame" title="Anonymised output" src={url} sandbox="allow-scripts" referrerPolicy="no-referrer" />
    ) : (
      <div className="artifact-img blind-img">
        <img src={url} alt="Anonymised output" />
      </div>
    );
  }
  const answer = [...(result.transcript ?? [])].reverse().find((t) => !t.judge);
  return <pre className="blind-text">{answer?.response || result.summary || '(empty response)'}</pre>;
}

export default function ReviewPage() {
  useViewerCaption('Blind review: people score answers without knowing which model wrote them, so nobody’s favourite gets a head start.');
  const { query } = useRoute();
  const toast = useToast();
  const reasonF = (query.get('reason') ?? 'all') as 'all' | ReviewReason;
  const testF = query.get('test') ?? '';
  const [rater, setRater] = useLocalStorage('gauntlet.rater', '');
  const data = useAsync<[ReviewItem[], TestSummary[], ContestantView[]]>(() => Promise.all([api.reviewQueue(), api.tests().catch(() => []), api.contestants().catch(() => [])]), []);
  const [queue, tests, cons] = data.data ?? [[], [], []];
  const [pairKey, setPairKey] = useState<string | null>(null);
  const [sides, setSides] = useState<Side[]>([]);
  const [results, setResults] = useState<Record<string, CaseResult | 'error'>>({});
  const [note, setNote] = useState('');
  const [revealed, setRevealed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<Set<string>>(new Set());
  const [testDetail, setTestDetail] = useState<TestDetail | null>(null);

  const testName = useMemo(() => new Map(tests.map((t) => [t.id, t.name])), [tests]);
  const conById = useMemo(() => new Map(cons.map((c) => [c.id, c])), [cons]);

  const pending = useMemo(
    () =>
      queue
        .filter((i) => !done.has(i.key) && i.status !== 'error')
        .filter((i) => (reasonF === 'all' || reasonOf(i) === reasonF) && (!testF || i.testId === testF))
        .sort((a, b) => PRIORITY[reasonOf(a)] - PRIORITY[reasonOf(b)] || (a.humanScores?.length ?? 0) - (b.humanScores?.length ?? 0)),
    [queue, done, reasonF, testF],
  );

  const counts = useMemo(() => {
    const c: Record<string, number> = { all: 0 };
    for (const i of queue) {
      if (done.has(i.key)) continue;
      c.all++;
      c[reasonOf(i)] = (c[reasonOf(i)] ?? 0) + 1;
    }
    return c;
  }, [queue, done]);

  const testsInQueue = useMemo(() => [...new Set(queue.map((i) => i.testId))], [queue]);

  const pickNext = useCallback(() => {
    setRevealed(false);
    setNote('');
    const first = pending[0];
    if (!first) {
      setSides([]);
      setPairKey(null);
      return;
    }
    const partners = pending.filter((i) => i.testId === first.testId && i.caseId === first.caseId && i.contestantId !== first.contestantId);
    const partner = partners.length ? partners[Math.floor(Math.random() * partners.length)] : null;
    const pair = partner ? [first, partner] : [first];
    if (Math.random() < 0.5) pair.reverse();
    setSides(pair.map((item) => ({ item, score: 5 })));
    setPairKey(pair.map((p) => p.key).join('|'));
  }, [pending]);

  // Pick the first pair when data or filters change and nothing is on screen.
  useEffect(() => {
    if (data.data) pickNext();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data.data, reasonF, testF]);

  // Load full results + the test's prompt for the current pair.
  useEffect(() => {
    for (const s of sides) {
      if (results[s.item.key]) continue;
      api
        .result(s.item.runId, s.item.key)
        .then((r) => setResults((x) => ({ ...x, [s.item.key]: r })))
        .catch(() => setResults((x) => ({ ...x, [s.item.key]: 'error' })));
    }
    const tid = sides[0]?.item.testId;
    if (tid && testDetail?.definition.id !== tid) {
      api
        .test(tid)
        .then(setTestDetail)
        .catch(() => setTestDetail(null));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pairKey]);

  const adjust = (i: number, delta: number) => setSides((ss) => ss.map((s, j) => (j === i ? { ...s, score: Math.max(0, Math.min(10, s.score + delta)) } : s)));

  const submit = async () => {
    if (revealed || !sides.length) return;
    if (!rater.trim()) {
      toast.error('Enter your rater name first — it is stored with every score.');
      document.getElementById('rater')?.focus();
      return;
    }
    setBusy(true);
    try {
      for (const s of sides) await api.reviewScore({ runId: s.item.runId, key: s.item.key, score: s.score / 10, rater: rater.trim(), note: note.trim() || undefined });
      setDone((d) => new Set([...d, ...sides.map((s) => s.item.key)]));
      setRevealed(true);
    } catch (e) {
      toast.error(e, 'Could not submit scores');
    } finally {
      setBusy(false);
    }
  };

  useHotkeys(
    {
      q: () => adjust(0, -0.5),
      w: () => adjust(0, 0.5),
      o: () => adjust(1, -0.5),
      p: () => adjust(1, 0.5),
      Enter: () => (revealed ? pickNext() : void submit()),
      n: () => revealed && pickNext(),
    },
    sides.length > 0,
  );

  const rendered = testDetail?.rendered.find((r) => r.caseId === sides[0]?.item.caseId);
  const def = testDetail?.definition;
  const rubric = def && def.kind === 'prompt' ? ('rubric' in def.scorer ? def.scorer.rubric : 'instructions' in def.scorer ? def.scorer.instructions : undefined) : undefined;
  const reason = sides[0] ? reasonOf(sides[0].item) : null;

  return (
    <div className="page review-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.EyeOff style={{ width: 14, height: 14 }} /> Blind review
          </span>
        }
        title="Blind Review"
        sub="Rate anonymised outputs side by side. Identities stay hidden until you submit — so the score reflects the work, not the brand."
        actions={
          <label className="field" style={{ minWidth: 200 }}>
            <span className="label">Your rater name</span>
            <input id="rater" className="input" placeholder="e.g. mika" value={rater} onChange={(e) => setRater(e.target.value)} />
          </label>
        }
      />

      <div className="filter-bar">
        <div className="seg" role="group" aria-label="Filter by reason">
          {(['all', 'judge-disagreement', 'human-scored', 'second-opinion'] as const).map((r) => (
            <button key={r} type="button" aria-pressed={reasonF === r} onClick={() => setQuery({ reason: r === 'all' ? null : r })}>
              {r === 'all' ? 'All' : REASONS[r].label}
              <span className="count-pill">{counts[r] ?? 0}</span>
            </button>
          ))}
        </div>
        <select className="select" style={{ width: 240 }} value={testF} onChange={(e) => setQuery({ test: e.target.value || null })} aria-label="Filter by test">
          <option value="">All tests</option>
          {testsInQueue.map((id) => (
            <option key={id} value={id}>
              {testName.get(id) ?? id}
            </option>
          ))}
        </select>
        <span className="spacer" />
        <span className="muted hide-mobile" style={{ fontSize: '0.8rem' }}>
          <kbd>Q</kbd>/<kbd>W</kbd> Model A · <kbd>O</kbd>/<kbd>P</kbd> Model B · <kbd>Enter</kbd> submit · <kbd>N</kbd> next
        </span>
      </div>

      {data.loading && !data.data ? (
        <div className="card pad">
          <SkeletonRows rows={6} h={50} />
        </div>
      ) : data.error && !data.data ? (
        <ErrorState error={data.error} onRetry={data.reload} title="Couldn’t load the review queue" />
      ) : sides.length === 0 ? (
        <div className="card">
          <Empty
            icon={<Icon.Eye />}
            title={queue.length ? 'Nothing left in this view' : 'The review queue is empty'}
            actions={
              queue.length ? (
                <button className="btn" onClick={() => setQuery({ reason: null, test: null })}>
                  Show everything
                </button>
              ) : (
                <Link to="/run/new" className="btn primary">
                  <Icon.Rocket /> Run a creative suite
                </Link>
              )
            }
          >
            {queue.length ? 'Try another filter.' : 'Human-scored tests, artifact builds and cases where the judges disagreed land here after a run.'}
          </Empty>
        </div>
      ) : (
        <>
          <section className="card review-context">
            <div className="card-body stack">
              <div className="row wrap" style={{ gap: 10 }}>
                {reason && <span className={cx('badge lg', REASONS[reason].tone)}>{REASONS[reason].long}</span>}
                <strong style={{ fontSize: '1.05rem' }}>{testName.get(sides[0].item.testId) ?? 'Test'}</strong>
                <span className="muted">· case {sides[0].item.caseId}</span>
                <span className="spacer" />
                <span className="muted" style={{ fontSize: '0.8rem' }}>
                  {pending.length} waiting
                </span>
              </div>
              {rubric && (
                <div className="callout plain">
                  <Icon.Target />
                  <div>
                    <strong>Rubric:</strong> {rubric}
                  </div>
                </div>
              )}
              {rendered && (
                <details className="collapse">
                  <summary>The prompt both models received</summary>
                  <div className="inner">
                    <pre className="code">{rendered.turns.join('\n\n— next turn —\n\n')}</pre>
                  </div>
                </details>
              )}
            </div>
          </section>

          <div className={cx('review-grid', sides.length === 1 && 'single')}>
            {sides.map((s, i) => {
              const letter = i === 0 ? 'A' : 'B';
              const con = conById.get(s.item.contestantId);
              const res = results[s.item.key];
              return (
                <section key={s.item.key} className={cx('card review-side', revealed && 'revealed')} style={revealed && con ? { ['--c' as string]: con.color } : undefined}>
                  <header className="review-head">
                    <span className="model-letter">{letter}</span>
                    {revealed ? (
                      <div className="reveal-in stack tight">
                        <ModelChip label={con?.label ?? s.item.contestantId} color={con?.color} />
                        <span className="muted" style={{ fontSize: '0.78rem' }}>
                          {con?.vendor} · automated score <ScorePill score={s.item.score} />
                        </span>
                      </div>
                    ) : (
                      <strong>Model {letter}</strong>
                    )}
                    <span className="spacer" />
                    <span className="big-score tnum">{s.score.toFixed(1)}</span>
                  </header>
                  <div className="review-output">
                    <BlindOutput result={res ?? null} />
                  </div>
                  <div className="review-slider">
                    <input
                      type="range"
                      min={0}
                      max={10}
                      step={0.5}
                      value={s.score}
                      disabled={revealed}
                      onChange={(e) => setSides((ss) => ss.map((x, j) => (j === i ? { ...x, score: Number(e.target.value) } : x)))}
                      aria-label={`Score for model ${letter}, 0 to 10`}
                    />
                    <div className="slider-scale" aria-hidden="true">
                      <span>0 broken</span>
                      <span>5 okay</span>
                      <span>10 would ship</span>
                    </div>
                  </div>
                </section>
              );
            })}
          </div>

          <section className="card">
            <div className="card-body row wrap" style={{ gap: 12 }}>
              <input className="input" style={{ flex: 1, minWidth: 220 }} placeholder="Optional note (shared with both scores)" value={note} onChange={(e) => setNote(e.target.value)} disabled={revealed} aria-label="Note" />
              {revealed ? (
                <>
                  <Callout tone="info" icon={<Icon.Check />}>
                    Scores saved. {reason === 'judge-disagreement' ? 'Your rating is now the final score for these results.' : 'Thanks — identities are revealed above.'}
                  </Callout>
                  <button className="btn primary lg" onClick={pickNext}>
                    Next pair <kbd>N</kbd>
                  </button>
                </>
              ) : (
                <button className="btn primary lg" onClick={submit} disabled={busy}>
                  <Icon.Check /> {busy ? 'Saving…' : `Submit ${sides.length === 2 ? 'both scores' : 'score'} & reveal`}
                </button>
              )}
            </div>
          </section>
        </>
      )}
    </div>
  );
}
