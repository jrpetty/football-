/** Grader — score a pasted reply against any prompt test case, no run needed. */
import { useEffect, useMemo, useState } from 'react';
import { api, gradedUrl } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, pathOf, setQuery, useRoute } from '../router.tsx';
import { useMeta, useToast } from '../context.tsx';
import { Callout, CopyButton, Empty, ErrorState, Field, PageHead, ScorePill, Skeleton, SkeletonRows, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { ArtifactView, ScoreBreakdownView } from '../components/ResultInspector.tsx';
import { TranscriptView } from '../components/Transcript.tsx';
import { fmtCost, fmtPct } from '../format.ts';
import type { ContestantView, GradeResult, TestDetail, TestSummary } from '../types.ts';
import { VisionBadge, VisionImage } from '../components/VisionImage.tsx';
import { VisionResultPanel } from '../components/VisionResult.tsx';
import { testImageUrl } from '../vision.ts';

export default function GradePage() {
  const { query } = useRoute();
  const { cat, categories } = useMeta();
  const toast = useToast();
  const testId = query.get('test') ?? '';
  const caseId = query.get('case') ?? '';
  const lists = useAsync<[TestSummary[], ContestantView[]]>(() => Promise.all([api.tests(), api.contestants().catch(() => [])]), []);
  const [detail, setDetail] = useState<TestDetail | null>(null);
  const [detailErr, setDetailErr] = useState<Error | null>(null);
  const [response, setResponse] = useState('');
  const [vendor, setVendor] = useState('');
  const [grading, setGrading] = useState(false);
  const [result, setResult] = useState<GradeResult | null>(null);

  const promptTests = useMemo(() => (lists.data?.[0] ?? []).filter((t) => t.kind === 'prompt'), [lists.data]);
  const vendors = useMemo(() => [...new Set((lists.data?.[1] ?? []).map((c) => c.vendor).filter(Boolean))], [lists.data]);
  const grouped = useMemo(() => {
    const order = new Map(categories.map((c, i) => [c.id, i]));
    const m = new Map<string, TestSummary[]>();
    for (const t of promptTests) m.set(t.category, [...(m.get(t.category) ?? []), t]);
    return [...m.entries()].sort((a, b) => (order.get(a[0]) ?? 99) - (order.get(b[0]) ?? 99));
  }, [promptTests, categories]);

  useEffect(() => {
    if (!testId) {
      setDetail(null);
      return;
    }
    let alive = true;
    setDetail(null);
    setDetailErr(null);
    api
      .test(testId)
      .then((d) => {
        if (!alive) return;
        setDetail(d);
        if (!caseId && d.rendered[0]) setQuery({ case: d.rendered[0].caseId });
      })
      .catch((e: unknown) => alive && setDetailErr(e instanceof Error ? e : new Error(String(e))));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [testId]);

  useEffect(() => setResult(null), [testId, caseId]);

  const rendered = detail?.rendered.find((r) => r.caseId === caseId) ?? null;
  const fullPrompt = rendered ? [rendered.system ? `[System instructions]\n${rendered.system}` : '', ...rendered.turns].filter(Boolean).join('\n\n') : '';
  const scorerType = detail?.definition.kind === 'prompt' ? (detail.definition.cases.find((c) => c.id === caseId)?.scorer ?? detail.definition.scorer).type : null;

  const grade = async () => {
    if (!testId || !caseId || !response.trim()) return;
    setGrading(true);
    try {
      const r = await api.grade({ testId, caseId, response, vendor: vendor.trim() || undefined });
      setResult(r);
    } catch (e) {
      toast.error(e, 'Grading failed');
    } finally {
      setGrading(false);
    }
  };

  return (
    <div className="page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Target style={{ width: 14, height: 14 }} /> Lab
          </span>
        }
        title="Grader"
        sub="Paste any model’s reply to a test case and see exactly how Gauntlet would score it — same extraction, same checks, same judge panel."
      />
      <Callout tone="info">
        <strong>This doesn’t add to the leaderboard.</strong> It’s a sandbox for spot-checks. To record results, add a manual model (provider “Manual (copy &amp; paste)”) on the{' '}
        <Link to="/models">Models</Link> page and include it in a <Link to="/run/new">run</Link>.
      </Callout>

      {lists.error && !lists.data ? (
        <ErrorState error={lists.error} onRetry={lists.reload} />
      ) : (
        <div className="grid cols-2 grade-grid" style={{ alignItems: 'start' }}>
          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>1 · Pick a case</h2>
                <div className="desc">Prompt tests only — simulations need a live model.</div>
              </div>
            </div>
            <div className="card-body stack">
              <div className="form-grid" style={{ gridTemplateColumns: 'minmax(0, 2fr) minmax(0, 1fr)' }}>
                <Field label="Test" htmlFor="g-test">
                  <select id="g-test" className="select" value={testId} onChange={(e) => setQuery({ test: e.target.value || null, case: null })}>
                    <option value="">Choose a test…</option>
                    {grouped.map(([c, ts]) => (
                      <optgroup key={c} label={cat(c).name}>
                        {ts.map((t) => (
                          <option key={t.id} value={t.id}>
                            {t.name} ({t.scorerType})
                          </option>
                        ))}
                      </optgroup>
                    ))}
                  </select>
                </Field>
                <Field label="Case" htmlFor="g-case">
                  <select id="g-case" className="select" value={caseId} onChange={(e) => setQuery({ case: e.target.value || null })} disabled={!detail}>
                    {(detail?.rendered ?? []).map((r) => (
                      <option key={r.caseId} value={r.caseId}>
                        {r.caseId}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>
              {!testId ? (
                <Empty icon={<Icon.Target />} title="Choose a test to begin">
                  The exact prompt appears here with a copy button.
                </Empty>
              ) : detailErr ? (
                <Callout tone="bad">{detailErr.message}</Callout>
              ) : !rendered ? (
                <SkeletonRows rows={4} h={30} />
              ) : (
                <div className="stack">
                  <div className="row wrap" style={{ gap: 8 }}>
                    <span className="badge outline">scorer: {scorerType}</span>
                    {rendered.turns.length > 1 && <span className="badge info">{rendered.turns.length} turns — paste them one at a time in the same chat</span>}
                    {(rendered.images?.length ?? 0) > 0 && <VisionBadge label="Attach the image with the prompt" />}
                    <span className="spacer" />
                    <CopyButton text={rendered.turns.length > 1 ? rendered.turns[0] : fullPrompt} label={rendered.turns.length > 1 ? 'Copy turn 1' : 'Copy prompt'} small={false} />
                  </div>
                  {rendered.system && (
                    <details className="collapse">
                      <summary>System prompt (included when you copy)</summary>
                      <div className="inner">
                        <pre className="code">{rendered.system}</pre>
                      </div>
                    </details>
                  )}
                  {rendered.turns.map((turn, i) => (
                    <div key={i} className="code-wrap">
                      {rendered.turns.length > 1 && <div className="mini-title">Turn {i + 1}</div>}
                      {(rendered.images ?? [])
                        .filter((img) => img.turn === i)
                        .map((img) => (
                          <VisionImage key={img.file} src={img.path ? testImageUrl(img.path) : ''} name={img.file.split('/').pop() ?? img.file} size="fill" actions caption="Send this image to the model together with the text below." className="grade-image" />
                        ))}
                      <pre className="code prompt-pre">{turn}</pre>
                      {rendered.turns.length > 1 && (
                        <div className="copy">
                          <CopyButton text={turn} label={`Copy turn ${i + 1}`} />
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </section>

          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>2 · Paste the reply</h2>
                <div className="desc">Exactly as the model wrote it — don’t tidy it up.</div>
              </div>
            </div>
            <div className="card-body stack">
              <textarea
                className="textarea reply-box"
                placeholder="Paste the model’s full reply…"
                value={response}
                onChange={(e) => setResponse(e.target.value)}
                aria-label="Model reply"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                    e.preventDefault();
                    void grade();
                  }
                }}
              />
              <div className="row wrap" style={{ alignItems: 'flex-end' }}>
                <Field label="Model vendor (optional)" hint="Judges from the same company are excluded." className="grow" htmlFor="g-vendor">
                  <input id="g-vendor" className="input" list="g-vendors" placeholder="e.g. Orbit Labs" value={vendor} onChange={(e) => setVendor(e.target.value)} />
                  <datalist id="g-vendors">
                    {vendors.map((v) => (
                      <option key={v} value={v} />
                    ))}
                  </datalist>
                </Field>
                <button className="btn primary lg" onClick={grade} disabled={grading || !testId || !caseId || !response.trim()}>
                  <Icon.Target /> {grading ? 'Grading…' : 'Grade'}
                </button>
              </div>
            </div>
          </section>
        </div>
      )}

      {grading && !result && (
        <div className="card pad">
          <Skeleton h={60} />
        </div>
      )}
      {result && (
        <section className="card grade-result">
          <div className="grade-hero">
            <div className={cx('grade-score', result.outcome.score === null ? 'none' : result.outcome.passed ? 'pass' : 'fail')}>
              <span className="tnum">{result.outcome.score === null ? '—' : fmtPct(result.outcome.score)}</span>
              <small>{result.outcome.pendingHuman ? 'needs a human' : result.outcome.passed ? 'pass' : 'fail'}</small>
            </div>
            <div className="stack tight" style={{ minWidth: 0 }}>
              <div className="row wrap" style={{ gap: 8 }}>
                <ScorePill score={result.outcome.score} status={result.outcome.pendingHuman ? 'pending-human' : 'ok'} />
                <span className={cx('badge', result.outcome.passed ? 'good' : result.outcome.score === null ? '' : 'bad')}>
                  <span className="dot" />
                  {result.outcome.passed === null ? 'ungraded' : result.outcome.passed ? 'Passed' : 'Failed'}
                </span>
                <span className="badge outline">judge cost {fmtCost(result.judgeCostUsd)}</span>
              </div>
              <h2 style={{ fontSize: '1.3rem' }}>{result.outcome.summary || '—'}</h2>
              <span className="muted mono" style={{ fontSize: '0.76rem' }}>
                {testId} · {caseId} · grade {result.gradeId}
              </span>
            </div>
            <span className="spacer" />
            <Link to={pathOf('tests', testId)} className="btn sm">
              View test
            </Link>
          </div>
          <div className="card-body stack loose">
            {(result.rendered.images?.length ?? 0) > 0 && (
              <VisionResultPanel images={(result.rendered.images ?? []).map((img) => ({ name: img.file.split('/').pop() ?? img.file, mediaType: 'image/png', path: img.path }))} detail={result.outcome.detail ?? {}} passed={result.outcome.passed} />
            )}
            <ScoreBreakdownView
              d={(result.rendered.images?.length ?? 0) > 0 ? { ...result.outcome.detail, extracted: undefined, expected: undefined } : result.outcome.detail ?? {}}
              passed={result.outcome.passed}
              names={new Map((lists.data?.[1] ?? []).map((c) => [c.id, c.label]))}
            />
            {result.artifacts.length > 0 && (
              <div className="stack">
                <div className="mini-title">Artifacts</div>
                {result.artifacts.map((a) => (
                  <ArtifactView key={a.file} art={a} url={gradedUrl(a.file)} />
                ))}
              </div>
            )}
            {result.judgeTranscript.length > 0 && (
              <details className="collapse">
                <summary>Judge transcript · {result.judgeTranscript.length} call{result.judgeTranscript.length === 1 ? '' : 's'}</summary>
                <div className="inner">
                  <TranscriptView entries={result.judgeTranscript} />
                </div>
              </details>
            )}
          </div>
        </section>
      )}
    </div>
  );
}
