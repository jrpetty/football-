/**
 * The Gallery — every artist's painting of the same museum commission, hung side by side on a museum wall,
 * with placards (brief followed, artistry, cost), the brief's checklist and the judges' reasons. One room per
 * commission; ←/→ walk between rooms, T shows the tick marks, F goes full screen.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.ts';
import { useAsync, useHotkeys, useLocalStorage } from '../hooks.ts';
import { Link, href, navigate, pathOf, setQuery, useRoute } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { Empty, ErrorState, LoadingPage, PageHead, Seg, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { GalleryWall } from '../components/viz/GalleryWall.tsx';
import { GalleryResultPanel } from '../components/viz/GalleryResultPanel.tsx';
import { GalleryFrame, GalleryRosette } from '../components/viz/GalleryFrame.tsx';
import { bestOf, fmtArtistry, frameFor, isGalleryTest, mastersOf, type Brief, type WallEntry } from '../components/viz/galleryModel.ts';
import type { CaseResult, RunDetail } from '../types.ts';
import { modeOf, useGalleryRun, type GalleryRun } from './useGalleryRun.ts';
import '../components/viz/gallery.css';
import './gallery-pages.css';

export function RoomHeader({ brief, mode, n, of }: { brief: Brief; mode: 'image' | 'code'; n: number; of: number }) {
  const masters = mastersOf(brief.style);
  return (
    <header className="gal-room-head">
      <div className="grh-eyebrow">
        Room {n} of {of} · Commission No. {brief.n} · {mode === 'code' ? 'painted in SVG code' : 'painted by image generators'}
      </div>
      <h2 className="grh-title">{brief.title}</h2>
      <div className="grh-medium">
        {brief.medium}, {brief.style.split(', in the manner of')[0]}
        {masters ? <span> · in the manner of {masters}</span> : null}
      </div>
      <ol className="grh-elements" aria-label="What the brief requires">
        {brief.elements.map((e) => (
          <li key={e.id}>{e.text}</li>
        ))}
      </ol>
    </header>
  );
}

/** The best painting of a Gallery test across every commission (the Masterpiece of the Show). */
export function masterpieceOf(run: GalleryRun, testId: string): { entry: WallEntry; brief: Brief; caseId: string } | null {
  let best: { entry: WallEntry; brief: Brief; caseId: string } | null = null;
  for (const { caseId, brief } of run.briefs(testId)) {
    const e = bestOf(run.wall(testId, caseId));
    if (e && (!best || e.score! > best.entry.score! || (e.score === best.entry.score && (e.detail?.artistry ?? 0) > (best.entry.detail?.artistry ?? 0)))) best = { entry: e, brief, caseId };
  }
  return best;
}

function GalleryPicker() {
  const runs = useAsync(async () => {
    const list = await api.runs();
    const recent = list.slice(0, 30);
    const details = await Promise.all(recent.map((r) => api.run(r.id).catch(() => null)));
    return details.filter((d): d is RunDetail => !!d && d.manifest.tests.some((t) => isGalleryTest(t.id)));
  }, []);
  useViewerCaption('The Gallery: pick a run to walk through its paintings.');
  return (
    <div className="page gallery-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Image style={{ width: 14, height: 14 }} /> Art
          </span>
        }
        title="The Gallery"
        sub="Every model’s painting of the same museum commission, hung side by side with its placard, the judges’ checklist and their reasons."
      />
      {runs.loading && !runs.data ? (
        <LoadingPage />
      ) : runs.error ? (
        <ErrorState error={runs.error} onRetry={runs.reload} />
      ) : !runs.data?.length ? (
        <div className="card">
          <Empty
            icon={<Icon.Image />}
            title="No paintings yet"
            actions={
              <Link to="/run/new" className="btn primary">
                <Icon.Rocket /> Start an Art run
              </Link>
            }
          >
            Run the <b>Art</b> suite (The Gallery Masterpiece and Painted in Code) and the paintings appear here.
          </Empty>
        </div>
      ) : (
        <div className="gal-picker">
          {runs.data.map((d) => {
            const gt = d.manifest.tests.filter((t) => isGalleryTest(t.id));
            const first = (d.results ?? []).find((r) => isGalleryTest(r.testId) && r.artifacts?.some((a) => /^painting\.(png|jpg)$/.test(a.name)));
            return (
              <button key={d.manifest.id} type="button" className="gal-pick" onClick={() => navigate(pathOf('gallery', d.manifest.id))}>
                <span className="gal-pick-name">{d.manifest.name}</span>
                <span className="gal-pick-meta">
                  {gt.map((t) => t.name).join(' · ')} · {d.manifest.contestants.length} artists · {new Date(d.manifest.createdAt).toLocaleDateString()}
                </span>
                {!first && <span className="gal-pick-meta">No paintings recorded yet</span>}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default function GalleryPage({ runId }: { runId?: string }) {
  if (!runId) return <GalleryPicker />;
  return <GalleryRunView runId={runId} />;
}

function GalleryRunView({ runId }: { runId: string }) {
  const { query } = useRoute();
  const { run, loading, error, reload } = useGalleryRun(runId);
  const [ticks, setTicks] = useLocalStorage('gauntlet.gallery.ticks', true);
  const [full, setFull] = useState(false);
  const room = useRef<HTMLElement>(null);
  const testId = (run?.tests.find((t) => t.id === query.get('test')) ?? run?.tests[0])?.id ?? '';
  const briefs = run && testId ? run.briefs(testId) : [];
  const caseId = briefs.find((b) => b.caseId === query.get('brief'))?.caseId ?? briefs[0]?.caseId ?? '';
  const idx = briefs.findIndex((b) => b.caseId === caseId);
  const brief = briefs[idx]?.brief;
  const entries = useMemo(() => (run && testId && caseId ? run.wall(testId, caseId) : []), [run, testId, caseId]);
  const skipped = useMemo(() => (run && testId && caseId ? run.wall(testId, caseId, true).filter((e) => e.state === 'no-output') : []), [run, testId, caseId]);
  const selectedKey = query.get('p');
  const selected = entries.find((e) => e.key === selectedKey) ?? null;
  const mode = modeOf(testId);
  const master = run && testId ? masterpieceOf(run, testId) : null;
  const names = useMemo(() => new Map([...(run?.detail.manifest.judges ?? []), ...(run?.detail.manifest.contestants ?? [])].flatMap((c) => [[c.id, c.label] as [string, string], [`${c.id}@judge`, c.label] as [string, string]])), [run]);

  useViewerCaption(
    brief
      ? `The Gallery, room ${idx + 1}: every AI’s painting of “${brief.title}”. Each placard shows how much of the brief it followed and its artistry out of 10, judged by AI judges from other companies.`
      : 'The Gallery: every AI’s painting of the same museum commission, hung side by side.',
    'Brief followed = checklist lines the judges saw · Artistry = median judge score, 1–10 · artistry is a judgement, not a measurement',
  );

  const go = (d: number) => {
    if (!briefs.length) return;
    const next = briefs[(idx + d + briefs.length) % briefs.length]!;
    setQuery({ brief: next.caseId, p: null });
  };
  const toggleFull = () => {
    const el = room.current;
    if (!el) return;
    if (document.fullscreenElement) void document.exitFullscreen();
    else void el.requestFullscreen?.().catch(() => setFull((f) => !f));
  };
  useEffect(() => {
    const on = () => setFull(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', on);
    return () => document.removeEventListener('fullscreenchange', on);
  }, []);
  useHotkeys({ ArrowRight: () => go(1), ArrowLeft: () => go(-1), t: () => setTicks(!ticks), f: toggleFull }, !!run);

  if (loading && !run) return <LoadingPage />;
  if (error) return <ErrorState error={error} onRetry={reload} title="Couldn’t load this run" />;
  if (!run) return null;
  if (!run.tests.length || !brief) {
    return (
      <div className="page gallery-page">
        <div className="card">
          <Empty icon={<Icon.Image />} title="No Gallery paintings in this run" actions={<Link to="/gallery" className="btn">All Gallery runs</Link>}>
            This run has no results for The Gallery Masterpiece or Painted in Code.
          </Empty>
        </div>
      </div>
    );
  }

  return (
    <div className="page gallery-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Image style={{ width: 14, height: 14 }} /> Art · {run.detail.manifest.name}
          </span>
        }
        title="The Gallery"
        sub="The same museum commission, painted by every model. Click a painting for the judges’ checklist and reasons."
        actions={
          <div className="row wrap no-broadcast" style={{ gap: 8 }}>
            {run.tests.length > 1 && (
              <Seg
                label="Which test"
                value={testId}
                onChange={(v) => setQuery({ test: v, brief: null, p: null })}
                options={run.tests.map((t) => ({ value: t.id, label: modeOf(t.id) === 'code' ? 'Painted in Code' : 'Image generators' }))}
              />
            )}
            <a className="btn" href={href(pathOf('gallery', runId, 'vote'), { test: testId, brief: caseId })}>
              <Icon.Eye /> Blind vote
            </a>
            <a className="btn" href={href(pathOf('present', runId))}>
              <Icon.Present /> Present
            </a>
          </div>
        }
      />

      <nav className="gal-rooms no-broadcast" aria-label="Commissions">
        {briefs.map((b, i) => (
          <button key={b.caseId} type="button" className={cx('gal-room-tab', b.caseId === caseId && 'active')} onClick={() => setQuery({ brief: b.caseId, p: null })} aria-current={b.caseId === caseId}>
            <span className="grt-n tnum">{i + 1}</span>
            <span className="grt-t">{b.brief.title}</span>
          </button>
        ))}
      </nav>

      <section ref={room} className={cx('gal-room gal-hall', full && 'is-full')} aria-label={`Room ${idx + 1}: ${brief.title}`}>
        <div className="gal-hall-tools no-broadcast">
          <button type="button" className="gal-toggle" aria-pressed={ticks} onClick={() => setTicks(!ticks)} title="T">
            {ticks ? 'Hide' : 'Show'} brief ticks <kbd>T</kbd>
          </button>
          <button type="button" className="gal-toggle" onClick={toggleFull} title="F">
            {full ? 'Exit full screen' : 'Full screen'} <kbd>F</kbd>
          </button>
          <button type="button" className="gal-toggle" onClick={() => go(-1)} aria-label="Previous room">
            ←
          </button>
          <button type="button" className="gal-toggle" onClick={() => go(1)} aria-label="Next room">
            →
          </button>
        </div>
        <RoomHeader brief={brief} mode={mode} n={idx + 1} of={briefs.length} />
        {entries.length ? (
          <GalleryWall brief={brief} entries={entries} mode={mode} ribbons={ticks} selectedKey={selectedKey} onSelect={(e) => setQuery({ p: e.key === selectedKey ? null : e.key })} />
        ) : (
          <p className="gal-muted" style={{ textAlign: 'center', padding: 40 }}>
            No painting for this commission yet.
          </p>
        )}
        {skipped.length > 0 && (
          <p className="gal-hall-note">
            Not on the wall: {skipped.map((e) => e.label).join(', ')} {skipped.length === 1 ? 'has' : 'have'} no image output, so {skipped.length === 1 ? 'it was' : 'they were'} not asked (not scored as 0).
          </p>
        )}
        <p className="gal-hall-legend">
          <b>Brief followed</b> = lines of the brief the judges saw (6 required elements + 3 rules) · <b>Artistry</b> = median of the judges’ scores for composition, light, colour, craft, style and gallery-worthiness, 1–10 · judges never
          come from the artist’s own company · artistry is a judgement, not a measurement
        </p>
      </section>

      {selected?.result && (
        <div className="gal-detail" id="gal-detail">
          <GalleryResultPanel res={selected.result as unknown as CaseResult} modelLabel={selected.label} modelColor={selected.color} names={names} manual={selected.manual} />
          <div className="row no-broadcast" style={{ justifyContent: 'flex-end', gap: 8, marginTop: 10 }}>
            <a className="btn sm" href={href(pathOf('runs', runId), { tab: 'matrix', test: testId, c: selected.contestantId, key: selected.key })}>
              <Icon.External /> Open in the run (transcript and judges’ full replies)
            </a>
          </div>
        </div>
      )}

      {master && (
        <section className="gal-room gal-best">
          <div className="gal-best-art">
            <GalleryFrame src={master.entry.url} alt={`${master.brief.title}, painted by ${master.entry.label}`} width={master.entry.width} height={master.entry.height} frame={frameFor(master.brief.n)} size="md" badge={<GalleryRosette label="Masterpiece of the Show" size={48} />} />
          </div>
          <div className="gal-best-text">
            <div className="grh-eyebrow">Masterpiece of the Show</div>
            <h3 className="grh-title">{master.brief.title}</h3>
            <p className="gal-best-by">
              by <b>{master.entry.label}</b> · brief followed {master.entry.detail?.followed}/{master.entry.detail?.total} · artistry {fmtArtistry(master.entry.detail?.artistry)}/10 · score {Math.round((master.entry.score ?? 0) * 100)}/100
            </p>
            <p className="gal-muted">The highest score in this test across all {briefs.length} commissions (ties go to the higher artistry).</p>
            <button type="button" className="btn sm no-broadcast" onClick={() => setQuery({ brief: master.caseId, p: master.entry.key })}>
              Visit its room
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
