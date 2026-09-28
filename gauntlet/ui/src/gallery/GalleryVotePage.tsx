/**
 * Blind vote — the paintings of one commission, anonymised as A, B, C…, for the owner or a live audience to
 * pick favourites. Click a painting (or press 1 for A, 2 for B…) to add a vote, − to take one back. "Reveal" shows the
 * artists; "Save votes" stores each painting's share of the votes as a human score (rater "Blind vote"). Votes
 * are recorded and shown as the People's choice; they never change the judged score.
 */
import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.ts';
import { useHotkeys } from '../hooks.ts';
import { href, pathOf, setQuery, useRoute } from '../router.tsx';
import { useToast, useViewerCaption } from '../context.tsx';
import { ErrorState, LoadingPage, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { GalleryFrame, GalleryRosette } from '../components/viz/GalleryFrame.tsx';
import { MuseumPlacard } from '../components/viz/MuseumPlacard.tsx';
import { frameFor, type WallEntry } from '../components/viz/galleryModel.ts';
import { wallColumns } from '../components/viz/GalleryWall.tsx';
import { modeOf, useGalleryRun } from './useGalleryRun.ts';
import '../components/viz/gallery.css';
import './gallery-pages.css';

const LETTERS = 'ABCDEFGHIJKL';
const RATER = 'Blind vote';

/** A stable shuffle for this run and commission, so the letters never follow the run's model order. */
function shuffleFor(entries: WallEntry[], seed: string): WallEntry[] {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  let s = h >>> 0;
  const rand = () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const a = entries.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [a[i], a[j]] = [a[j]!, a[i]!];
  }
  return a;
}

export default function GalleryVotePage({ runId }: { runId: string }) {
  const { query } = useRoute();
  const toast = useToast();
  const { run, loading, error, reload } = useGalleryRun(runId);
  const testId = (run?.tests.find((t) => t.id === query.get('test')) ?? run?.tests[0])?.id ?? '';
  const briefs = run && testId ? run.briefs(testId) : [];
  const caseId = briefs.find((b) => b.caseId === query.get('brief'))?.caseId ?? briefs[0]?.caseId ?? '';
  const idx = briefs.findIndex((b) => b.caseId === caseId);
  const brief = briefs[idx]?.brief;
  const mode = modeOf(testId);
  const paintings = useMemo(() => (run && testId && caseId ? shuffleFor(run.wall(testId, caseId).filter((e) => e.state === 'painting' || e.state === 'awaiting'), `${runId}|${testId}|${caseId}`) : []), [run, testId, caseId, runId]);
  const [votes, setVotes] = useState<Record<string, number>>({});
  const [revealed, setRevealed] = useState(false);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setVotes({});
    setRevealed(false);
    setSaved(false);
  }, [caseId, testId]);

  const total = Object.values(votes).reduce((s, v) => s + v, 0);
  const max = Math.max(0, ...Object.values(votes));
  const add = (key: string, d: number) => {
    if (revealed) return;
    setVotes((v) => ({ ...v, [key]: Math.max(0, (v[key] ?? 0) + d) }));
  };
  const go = (d: number) => {
    if (!briefs.length) return;
    const next = briefs[(idx + d + briefs.length) % briefs.length]!;
    setQuery({ brief: next.caseId });
  };
  const save = async () => {
    if (!total || saved) return;
    setBusy(true);
    try {
      const ranked = paintings.slice().sort((a, b) => (votes[b.key] ?? 0) - (votes[a.key] ?? 0));
      for (const e of paintings) {
        const v = votes[e.key] ?? 0;
        const place = ranked.findIndex((x) => (votes[x.key] ?? 0) === v) + 1;
        await api.reviewScore({ runId, key: e.key, score: max ? v / max : 0, rater: RATER, note: `${v} of ${total} votes · place ${place} of ${paintings.length}` });
      }
      setSaved(true);
      toast.success('Stored as human scores (rater “Blind vote”). They are shown as the People’s choice and never change the judged score.', 'Votes saved');
    } catch (e) {
      toast.error(e, 'Could not save the votes');
    } finally {
      setBusy(false);
    }
  };

  const keys: Record<string, () => void> = { r: () => setRevealed(true), ArrowRight: () => go(1), ArrowLeft: () => go(-1) };
  // Number keys, not letters: B, C and F already toggle Broadcast mode, captions and full screen.
  paintings.slice(0, 9).forEach((e, i) => (keys[String(i + 1)] = () => add(e.key, 1)));
  useHotkeys(keys, !!run);

  useViewerCaption(
    brief ? (revealed ? `The reveal: who painted each picture of “${brief.title}”, and how the votes fell.` : `Blind vote: which painting of “${brief.title}” is best? The artists are hidden behind letters until the reveal.`) : null,
    'Votes are stored as human scores; they never change the judged score',
  );

  if (loading && !run) return <LoadingPage />;
  if (error) return <ErrorState error={error} onRetry={reload} title="Couldn’t load this run" />;
  if (!run || !brief) return <ErrorState error={new Error('This run has no Gallery paintings')} />;

  // Up to five paintings hang in one row so a live audience sees them all at once.
  const cols = paintings.length <= 5 ? Math.max(1, paintings.length) : wallColumns(paintings.length);
  const leaders = paintings.filter((e) => max > 0 && (votes[e.key] ?? 0) === max);
  return (
    <div className="page gallery-page vote-page">
      <section className="gal-room gal-hall vote-hall">
        <header className="gal-room-head">
          <div className="grh-eyebrow">
            Blind vote · Commission No. {brief.n} · {mode === 'code' ? 'painted in SVG code' : 'painted by image generators'}
          </div>
          <h2 className="grh-title">{brief.title}</h2>
          <div className="grh-medium">{revealed ? 'The artists, revealed' : 'Which painting is the masterpiece? Click a painting to vote (or press 1 for A, 2 for B…).'}</div>
        </header>
        <div className={cx('gal-wall is-page vote-wall', `cols-${cols}`)} style={{ ['--cols' as string]: String(cols) }}>
          {paintings.map((e, i) => {
            const v = votes[e.key] ?? 0;
            const lead = revealed && leaders.some((x) => x.key === e.key);
            return (
              <div key={e.key} className={cx('gal-slot vote-slot', lead && 'is-lead')}>
                <div className="vote-letter" aria-hidden="true">
                  {LETTERS[i]}
                </div>
                <GalleryFrame
                  src={e.url}
                  alt={revealed ? `${brief.title}, painted by ${e.label}` : `Painting ${LETTERS[i]}`}
                  width={e.width}
                  height={e.height}
                  frame={frameFor(brief.n)}
                  state={e.state}
                  mode={mode}
                  onClick={() => add(e.key, 1)}
                  badge={lead ? <GalleryRosette label="People’s choice" tone="blue" size={44} /> : undefined}
                />
                <div className="vote-bar" aria-label={`Painting ${LETTERS[i]}: ${v} votes`}>
                  <button type="button" className="vote-btn no-broadcast" onClick={() => add(e.key, -1)} disabled={revealed || v === 0} aria-label={`Remove a vote from ${LETTERS[i]}`}>
                    −
                  </button>
                  <span className="vote-meter">
                    <span className="vote-fill" style={{ width: `${total ? (v / total) * 100 : 0}%` }} />
                  </span>
                  <b className="vote-count tnum">{v}</b>
                  <button type="button" className="vote-btn no-broadcast" onClick={() => add(e.key, 1)} disabled={revealed} aria-label={`Add a vote for ${LETTERS[i]}`}>
                    +
                  </button>
                </div>
                {revealed ? (
                  <MuseumPlacard title={brief.title} artist={e.label} color={e.color} medium={brief.medium} style={brief.style} detail={e.detail} state={e.state} score={e.score} costUsd={e.costUsd} manual={e.manual} baseline={e.baseline} mode={mode} size="sm" />
                ) : (
                  <MuseumPlacard title={`Painting ${LETTERS[i]}`} artist="" medium={brief.medium} style={brief.style} detail={null} state={e.state} score={null} costUsd={null} mode={mode} size="sm" anonymous />
                )}
              </div>
            );
          })}
        </div>
        <div className="vote-foot">
          <span className="vote-total tnum">
            {total} {total === 1 ? 'vote' : 'votes'}
          </span>
          <span className="spacer" />
          <div className="row wrap no-broadcast" style={{ gap: 8 }}>
            <button type="button" className="gal-toggle" onClick={() => go(-1)}>
              ← Previous commission
            </button>
            <button type="button" className="gal-toggle" onClick={() => go(1)}>
              Next commission →
            </button>
            {!revealed ? (
              <button type="button" className="btn primary" onClick={() => setRevealed(true)}>
                <Icon.Eye /> Reveal the artists <kbd>R</kbd>
              </button>
            ) : (
              <button type="button" className="btn primary" onClick={save} disabled={!total || saved || busy}>
                <Icon.Check /> {saved ? 'Votes saved' : busy ? 'Saving…' : 'Save votes as human scores'}
              </button>
            )}
            <a className="btn" href={href(pathOf('gallery', runId), { test: testId, brief: caseId })}>
              Back to the Gallery
            </a>
          </div>
        </div>
        <p className="gal-hall-legend">
          Each painting’s share of the votes is saved as a human score (rater “Blind vote”): it shows as the People’s choice and never changes the judged score. To change the score itself, rate the artistry in Blind Review.
        </p>
      </section>
    </div>
  );
}
