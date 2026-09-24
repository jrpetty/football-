/** Drawer listing one contestant's cases for one test; clicking a case loads the full result. */
import { useEffect, useMemo, useState } from 'react';
import { api, artifactUrl } from '../api.ts';
import type { ArtifactRef, CaseResult, CaseResultLite } from '../types.ts';
import { fmtBytes, fmtCost, fmtMs, fmtRate, fmtScore, fmtTokens, prettyJson } from '../format.ts';
import { CopyButton, Drawer, ErrorState, ModelChip, ResultStatusBadge, ScorePill, SkeletonRows, Tabs, cx } from './ui.tsx';
import { ReplayPlayer } from './ReplayPlayer.tsx';
import { TranscriptView } from './Transcript.tsx';
import { Icon } from './icons.tsx';

export interface InspectorTarget {
  testId: string;
  testName: string;
  contestantId: string;
  contestantLabel: string;
  contestantColor: string;
}

type Tab = 'overview' | 'transcript' | 'artifacts' | 'replay';

export function ArtifactView({ runId, art, height = 420 }: { runId: string; art: ArtifactRef; height?: number }) {
  const url = artifactUrl(runId, art.file);
  return (
    <figure className="artifact">
      <figcaption>
        <span className="badge outline">{art.kind.toUpperCase()}</span>
        <span className="ellipsis" style={{ fontWeight: 600 }}>
          {art.name}
        </span>
        <span className="muted">{fmtBytes(art.bytes)}</span>
        <span className="spacer" />
        {url && (
          <a className="btn xs" href={url} target="_blank" rel="noreferrer noopener">
            <Icon.External /> Open
          </a>
        )}
      </figcaption>
      {!url ? (
        <div className="chart-empty">Artifact unavailable.</div>
      ) : art.kind === 'html' ? (
        <iframe title={art.name} src={url} sandbox="allow-scripts" style={{ height }} loading="lazy" referrerPolicy="no-referrer" />
      ) : art.kind === 'svg' || art.kind === 'png' ? (
        <div className="artifact-img">
          <img src={url} alt={art.name} loading="lazy" />
        </div>
      ) : (
        <div className="chart-empty">
          <a href={url} target="_blank" rel="noreferrer noopener">
            View {art.kind} file
          </a>
        </div>
      )}
    </figure>
  );
}

function Breakdown({ r }: { r: CaseResult }) {
  const d = r.scoreDetail ?? {};
  const known = new Set(['extracted', 'expected', 'formatOk', 'items', 'judge', 'notes']);
  const extra = Object.entries(d).filter(([k]) => !known.has(k));
  return (
    <div className="stack loose">
      {(d.extracted !== undefined || d.expected !== undefined) && (
        <div className="grid cols-2 answer-compare">
          <div>
            <div className="mini-title">Extracted answer</div>
            <pre className={cx('code', r.passed ? 'ok-border' : 'bad-border')}>{d.extracted ?? '—'}</pre>
            {d.formatOk === false && <div className="warn-text" style={{ fontSize: '0.8rem', marginTop: 6 }}>⚠ Answer format not followed (fallback extraction used)</div>}
          </div>
          <div>
            <div className="mini-title">Expected</div>
            <pre className="code">{prettyJson(d.expected) || '—'}</pre>
          </div>
        </div>
      )}
      {d.items && d.items.length > 0 && (
        <div>
          <div className="mini-title">
            Checks · {d.items.filter((i) => i.passed).length}/{d.items.length} passed
          </div>
          <ul className="checklist">
            {d.items.map((it, i) => (
              <li key={i} className={it.passed ? 'pass' : 'fail'}>
                <span className="ck" aria-label={it.passed ? 'passed' : 'failed'}>
                  {it.passed ? '✓' : '✕'}
                </span>
                <span className="ck-label">{it.label}</span>
                {typeof it.score === 'number' && <span className="muted tnum">{fmtScore(it.score)}</span>}
                {it.detail && <span className="ck-detail">{it.detail}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {d.judge && d.judge.length > 0 && (
        <div>
          <div className="mini-title">Judge panel</div>
          <div className="stack tight">
            {d.judge.map((j, i) => (
              <div key={i} className="judge-card">
                <div className="row">
                  <span className="mono" style={{ fontSize: '0.8rem' }}>
                    {j.contestantId}
                  </span>
                  {j.label && <span className="badge outline">{j.label}</span>}
                  <span className="spacer" />
                  <ScorePill score={j.score} />
                </div>
                <p>{j.rationale}</p>
              </div>
            ))}
          </div>
        </div>
      )}
      {r.humanScores && r.humanScores.length > 0 && (
        <div>
          <div className="mini-title">Human scores</div>
          <div className="chip-list">
            {r.humanScores.map((h, i) => (
              <span key={i} className="chip pill" title={h.note}>
                {h.rater}: {(h.score * 10).toFixed(1)}/10
              </span>
            ))}
          </div>
        </div>
      )}
      {d.notes && (
        <div className="callout plain">
          <Icon.Info />
          <div>{d.notes}</div>
        </div>
      )}
      {extra.length > 0 && (
        <div>
          <div className="mini-title">Details</div>
          <dl className="kv">
            {extra.map(([k, v]) => (
              <div key={k} style={{ display: 'contents' }}>
                <dt>{k}</dt>
                <dd className="mono">{typeof v === 'object' ? JSON.stringify(v) : String(v)}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </div>
  );
}

function Metrics({ r }: { r: CaseResult }) {
  const m = r.metrics;
  const items: Array<[string, string]> = [
    ['Wall time', fmtMs(m?.wallMs)],
    ['Time to first token', fmtMs(m?.ttftMs)],
    ['API calls', String(m?.apiCalls ?? '—')],
    ['Input tokens', fmtTokens(m?.inputTokens)],
    ['Output tokens', fmtTokens(m?.outputTokens)],
    ['Reasoning tokens', fmtTokens(m?.reasoningTokens)],
    ['Cached input', fmtTokens(m?.cachedInputTokens)],
    ['Cost', fmtCost(m?.costUsd)],
    ['Judge cost', fmtCost(m?.judgeCostUsd)],
    ['Output speed', fmtRate(m?.outputTokensPerSec)],
    ['Retries', String(m?.retries ?? 0)],
    ['Response length', `${(m?.responseChars ?? 0).toLocaleString()} chars`],
  ];
  return (
    <div className="metric-grid">
      {items.map(([k, v]) => (
        <div key={k} className="metric">
          <span>{k}</span>
          <b className="tnum">{v}</b>
        </div>
      ))}
    </div>
  );
}

function ResultDetail({ runId, lite }: { runId: string; lite: CaseResultLite }) {
  const [res, setRes] = useState<CaseResult | null>(null);
  const [err, setErr] = useState<Error | null>(null);
  const [tab, setTab] = useState<Tab>('overview');
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    let alive = true;
    setRes(null);
    setErr(null);
    api
      .result(runId, lite.key)
      .then((r) => alive && setRes(r))
      .catch((e: unknown) => alive && setErr(e instanceof Error ? e : new Error(String(e))));
    return () => {
      alive = false;
    };
  }, [runId, lite.key, nonce]);

  useEffect(() => {
    setTab(lite.hasReplay ? 'replay' : 'overview');
  }, [lite.key, lite.hasReplay]);

  if (err) return <ErrorState error={err} onRetry={() => setNonce((n) => n + 1)} title="Couldn’t load this result" />;
  if (!res) return <SkeletonRows rows={8} h={34} />;

  const arts = res.artifacts ?? [];
  return (
    <div className="stack loose">
      <div className="result-head">
        <ScorePill score={res.score} status={res.status} mode="pct" />
        <ResultStatusBadge status={res.status} />
        <span className="result-summary">{res.summary || '—'}</span>
        <span className="spacer" />
        <CopyButton text={res.key} label="Copy key" />
      </div>
      {res.error && (
        <div className="callout bad">
          <Icon.Alert />
          <div>
            <strong>Error:</strong> {res.error}
          </div>
        </div>
      )}
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'overview', label: 'Score' },
          { id: 'transcript', label: 'Transcript', count: res.transcript?.length ?? 0 },
          { id: 'artifacts', label: 'Artifacts', count: arts.length, hidden: arts.length === 0 },
          { id: 'replay', label: 'Replay', hidden: !res.replay },
        ]}
      />
      {tab === 'overview' && (
        <>
          <Breakdown r={res} />
          <div>
            <div className="mini-title">Metrics</div>
            <Metrics r={res} />
          </div>
          <div className="muted mono" style={{ fontSize: '0.74rem' }}>
            test {res.testId}@{res.testVersion} · hash {res.testHash?.slice(0, 12)} · model config {res.contestantHash?.slice(0, 12)}
            {res.seed !== undefined ? ` · seed ${res.seed}` : ''}
          </div>
        </>
      )}
      {tab === 'transcript' && <TranscriptView entries={res.transcript ?? []} />}
      {tab === 'artifacts' && (
        <div className="stack loose">
          {arts.map((a) => (
            <ArtifactView key={a.file} runId={runId} art={a} />
          ))}
        </div>
      )}
      {tab === 'replay' && res.replay && <ReplayPlayer replay={res.replay} />}
    </div>
  );
}

export function ResultInspector({
  runId,
  target,
  results,
  initialKey,
  onClose,
  onSelectKey,
}: {
  runId: string;
  target: InspectorTarget | null;
  results: CaseResultLite[];
  initialKey?: string | null;
  onClose: () => void;
  onSelectKey?: (key: string | null) => void;
}) {
  const rows = useMemo(
    () => (target ? results.filter((r) => r.testId === target.testId && r.contestantId === target.contestantId).sort((a, b) => a.caseId.localeCompare(b.caseId) || a.repeat - b.repeat) : []),
    [results, target],
  );
  const [key, setKey] = useState<string | null>(initialKey ?? null);
  useEffect(() => {
    setKey(initialKey && rows.some((r) => r.key === initialKey) ? initialKey : rows[0]?.key ?? null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.testId, target?.contestantId]);

  const selected = rows.find((r) => r.key === key) ?? null;
  const scored = rows.filter((r) => typeof r.score === 'number');
  const mean = scored.length ? scored.reduce((s, r) => s + (r.score as number), 0) / scored.length : null;

  return (
    <Drawer
      open={!!target}
      onClose={onClose}
      width={1240}
      title={
        <span className="row" style={{ gap: 10, flexWrap: 'wrap' }}>
          {target?.testName}
          {target && <ModelChip label={target.contestantLabel} color={target.contestantColor} pill />}
        </span>
      }
      sub={
        target && (
          <>
            <span className="mono">{target.testId}</span> · {rows.length} case run{rows.length === 1 ? '' : 's'} · mean {fmtScore(mean, 'pct')}
          </>
        )
      }
    >
      <div className="inspector">
        <div className="insp-list" role="listbox" aria-label="Cases">
          {rows.length === 0 && <div className="chart-empty">No results for this cell.</div>}
          {rows.map((r) => (
            <button
              key={r.key}
              type="button"
              role="option"
              aria-selected={r.key === key}
              className={cx('insp-item', r.key === key && 'active')}
              onClick={() => {
                setKey(r.key);
                onSelectKey?.(r.key);
              }}
            >
              <div className="row" style={{ gap: 8 }}>
                <span className="mono insp-case">{r.caseId}</span>
                <span className="muted" style={{ fontSize: '0.76rem' }}>
                  r{r.repeat + 1}
                </span>
                <span className="spacer" />
                {r.hasReplay && <span className="badge outline" title="Has replay">▶</span>}
                <ScorePill score={r.score} status={r.status} />
              </div>
              <div className="insp-sum">{r.summary || '—'}</div>
              <div className="insp-meta tnum">
                {fmtMs(r.metrics?.wallMs)} · {fmtCost(r.metrics?.costUsd)}
              </div>
            </button>
          ))}
        </div>
        <div className="insp-detail">{selected ? <ResultDetail key={selected.key} runId={runId} lite={selected} /> : <div className="chart-empty">Select a case.</div>}</div>
      </div>
    </Drawer>
  );
}
