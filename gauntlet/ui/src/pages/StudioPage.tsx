/**
 * Studio — turn a run into video material: ranked highlights with deep links,
 * a narration script with Presenter cues, thumbnails / Shorts cards, and OBS
 * overlay URLs. Everything is derived from stored results (GET /api/studio/:id).
 */
import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, navigate, pathOf, setQuery, useRoute } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { CopyButton, Empty, ErrorState, LoadingPage, PageHead, RunStatusBadge, Tabs, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { ScriptPanel } from '../components/studio/ScriptPanel.tsx';
import { CardsPanel } from '../components/studio/CardsPanel.tsx';
import { OverlayPanel } from '../components/studio/OverlayPanel.tsx';
import { HIGHLIGHT_LABELS } from '../../../src/media/highlights.ts';
import type { Highlight, HighlightType, RunListItem, StudioPayload } from '../types.ts';
import '../styles/studio.css';

type Tab = 'highlights' | 'script' | 'cards' | 'overlay';

const HIGHLIGHT_COLORS: Record<HighlightType, string> = {
  catastrophe: '#ef4444',
  upset: '#f59e0b',
  'close-race': '#22d3ee',
  'clean-sweep': '#f2c14e',
  'confident-wrong': '#ec4899',
  'fastest-correct': '#22c55e',
  'expensive-wrong': '#f97316',
  'best-value': '#14b8a6',
  inconsistent: '#a78bfa',
  'big-win': '#94a3b8',
};

function HighlightCard({ h, rank, onCard }: { h: Highlight; rank: number; onCard: () => void }) {
  const replay = h.replayStep !== undefined;
  return (
    <article className="hl-card" style={{ '--hl': HIGHLIGHT_COLORS[h.type] } as CSSProperties}>
      <div className="hl-rank tnum" aria-label={`Rank ${rank}`}>
        {rank}
      </div>
      <div className="hl-main">
        <div className="hl-top">
          <span className="hl-type">{HIGHLIGHT_LABELS[h.type]}</span>
          <span className="hl-meta" title="How dramatic the moment is, 0–100 (used for ranking)">
            <Icon.Zap /> {h.drama}
          </span>
          <span className="hl-meta" title="Suggested clip length">
            <Icon.Clock /> ~{h.clipSec} s clip
          </span>
        </div>
        <h3 className="hl-title">{h.title}</h3>
        <p className="hl-why">{h.why}</p>
        <div className="hl-evidence" aria-label="The numbers behind this pick">
          {h.evidence.map((e) => (
            <span key={e.label} className="hl-ev">
              <b className="tnum">{e.display}</b>
              <span>{e.label}</span>
            </span>
          ))}
        </div>
        <details className="hl-rule no-broadcast">
          <summary>Why this was picked</summary>
          <p>{h.rule}</p>
        </details>
      </div>
      <div className="hl-actions no-broadcast">
        <a className="btn sm primary" href={h.link}>
          {replay ? <Icon.Play /> : h.link.includes('/present/') ? <Icon.Present /> : <Icon.Eye />}
          {replay ? `Replay at step ${h.replayStep}` : h.link.includes('/present/') ? 'Open slide' : 'Open case'}
        </a>
        <button type="button" className="btn sm" onClick={onCard}>
          <Icon.Image /> Make a card
        </button>
        <div className="hl-cue">
          <code title="Paste into your edit notes">{h.cue}</code>
          <CopyButton text={h.cue} label="Copy cue" iconOnly />
        </div>
      </div>
    </article>
  );
}

function HighlightsPanel({ s }: { s: StudioPayload }) {
  const [filter, setFilter] = useState<HighlightType | 'all'>('all');
  const counts = useMemo(() => {
    const m = new Map<HighlightType, number>();
    for (const h of s.highlights) m.set(h.type, (m.get(h.type) ?? 0) + 1);
    return m;
  }, [s.highlights]);
  const list = s.highlights.filter((h) => filter === 'all' || h.type === filter);
  if (!s.highlights.length) {
    return (
      <Empty icon={<Icon.Sparkles />} title="No highlights yet">
        Highlights appear once the run has scored results.
      </Empty>
    );
  }
  return (
    <div className="stack loose">
      <div className="hl-filters no-broadcast" role="group" aria-label="Filter highlights by type">
        <button type="button" className={cx('hl-filter', filter === 'all' && 'on')} onClick={() => setFilter('all')}>
          All <span className="tnum">{s.highlights.length}</span>
        </button>
        {[...counts.entries()].map(([t, n]) => (
          <button key={t} type="button" className={cx('hl-filter', filter === t && 'on')} style={{ '--hl': HIGHLIGHT_COLORS[t] } as CSSProperties} onClick={() => setFilter(t)}>
            <i />
            {HIGHLIGHT_LABELS[t]} <span className="tnum">{n}</span>
          </button>
        ))}
      </div>
      <div className="hl-list">
        {list.map((h) => (
          <HighlightCard key={h.id} h={h} rank={s.highlights.indexOf(h) + 1} onCard={() => setQuery({ tab: 'cards', h: h.id })} />
        ))}
      </div>
    </div>
  );
}

/** /studio without a run: jump to the newest run that has results. */
function StudioPicker() {
  const runs = useAsync<RunListItem[]>(() => api.runs(), []);
  const pick = useMemo(
    () => (runs.data ?? []).filter((r) => r.completedJobs > 0).sort((a, b) => Number(b.status === 'completed') - Number(a.status === 'completed') || b.createdAt.localeCompare(a.createdAt))[0],
    [runs.data],
  );
  useEffect(() => {
    if (pick) navigate(pathOf('studio', pick.id), undefined, true);
  }, [pick]);
  if (runs.error) return <ErrorState error={runs.error} onRetry={runs.reload} title="Couldn’t load runs" />;
  if (runs.loading || pick) return <LoadingPage />;
  return (
    <div className="page">
      <PageHead eyebrow="Studio" title="Make the video" />
      <Empty
        icon={<Icon.Clapper />}
        title="Nothing to edit yet"
        actions={
          <Link to="/run/new" className="btn primary">
            <Icon.Rocket /> Start a run
          </Link>
        }
      >
        The Studio turns a finished run into highlights, a narration script, thumbnails and live overlays. Run something first.
      </Empty>
    </div>
  );
}

export default function StudioPage({ runId }: { runId?: string }) {
  if (!runId) return <StudioPicker />;
  return <Studio runId={runId} />;
}

function Studio({ runId }: { runId: string }) {
  const { query } = useRoute();
  const tab = (['highlights', 'script', 'cards', 'overlay'].includes(query.get('tab') ?? '') ? query.get('tab') : 'highlights') as Tab;
  const state = useAsync<StudioPayload>(() => api.studio(runId), [runId]);
  const runs = useAsync<RunListItem[]>(() => api.runs(), []);
  const s = state.data;

  useViewerCaption(
    !s
      ? null
      : tab === 'highlights'
        ? `The most dramatic moments of “${s.runName}”, found automatically from the stored results and ranked. Every pick shows the numbers behind it.`
        : tab === 'script'
          ? 'A narration script written from the results. Every number in it comes straight from the run data.'
          : tab === 'cards'
            ? 'Thumbnails and vertical Shorts cards generated from the results.'
            : 'Overlays for live streams: add the address as a browser source in OBS.',
    s ? `${s.highlights.length} highlights · ${s.script.sections.length} script sections` : undefined,
  );

  if (state.loading && !s) return <LoadingPage />;
  if (state.error && !s) return <ErrorState error={state.error} onRetry={state.reload} title="Couldn’t open this run in the Studio" />;
  if (!s) return null;

  const others = (runs.data ?? []).filter((r) => r.completedJobs > 0);
  return (
    <div className="page studio">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Clapper style={{ width: 14, height: 14 }} /> Studio <Icon.ChevronRight style={{ width: 12, height: 12 }} />
            <Link to={pathOf('runs', s.runId)} className="mono">
              {s.runId}
            </Link>
          </span>
        }
        title={
          <span className="row wrap" style={{ gap: 14 }}>
            {s.runName} <RunStatusBadge status={s.status} lg />
          </span>
        }
        sub="Everything you need to make the video, generated from this run’s results: the moments worth showing, a script to read, thumbnails, Shorts cards and live overlays."
        actions={
          <>
            {others.length > 1 && (
              <select className="select no-broadcast" style={{ width: 280 }} aria-label="Switch run" value={s.runId} onChange={(e) => navigate(pathOf('studio', e.target.value), { tab })}>
                {others.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name || r.id}
                  </option>
                ))}
              </select>
            )}
            <Link to={pathOf('present', s.runId)} className="btn">
              <Icon.Present /> Presenter
            </Link>
          </>
        }
      />
      <Tabs
        value={tab}
        onChange={(t) => setQuery({ tab: t, h: null })}
        tabs={[
          { id: 'highlights', label: 'Highlights', count: s.highlights.length },
          { id: 'script', label: 'Video script' },
          { id: 'cards', label: 'Thumbnails & Shorts' },
          { id: 'overlay', label: 'OBS overlays' },
        ]}
      />
      <div className="studio-body">
        {tab === 'highlights' && <HighlightsPanel s={s} />}
        {tab === 'script' && <ScriptPanel s={s} />}
        {tab === 'cards' && <CardsPanel s={s} highlightId={query.get('h') ?? undefined} />}
        {tab === 'overlay' && <OverlayPanel s={s} />}
      </div>
    </div>
  );
}
