/**
 * Presenter slides for the channel tools, on the same fixed 1920×1080 stage as
 * the episode Presenter:
 *   #/slides/history?suite=core&metric=index&tiers=flagship  — "Family history"
 *   #/slides/challenge?season=2026-s1                       — "Viewer challenge: submitted by @name"
 * Keys: → / Space next (reveals the answer first) · ← back · F full screen · Esc exit.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useAsync, useHotkeys } from '../hooks.ts';
import { navigate, setQuery, useRoute } from '../router.tsx';
import { useMeta } from '../context.tsx';
import { BrandMark } from '../components/Brand.tsx';
import { Icon } from '../components/icons.tsx';
import { cx } from '../components/ui.tsx';
import { channelApi, type ChallengeSlide, type HistoryData } from './channelApi.ts';
import { HistoryChart } from './HistoryChart.tsx';
import './channel.css';

const W = 1920;
const H = 1080;

function useStageScale(): number {
  const calc = () => Math.min(window.innerWidth / W, window.innerHeight / H);
  const [k, setK] = useState(calc);
  useEffect(() => {
    const on = () => setK(calc());
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  return k;
}

function Stage({ section, title, idx, total, caption, fine, children }: { section: string; title: string; idx: number; total: number; caption: string; fine?: string; children: ReactNode }) {
  const scale = useStageScale();
  return (
    <div className="deck">
      <div className="deck-stage" style={{ transform: `translate(-50%, -50%) scale(${scale})` }}>
        <div className="deck-bg" aria-hidden="true">
          <div className="glow a" />
          <div className="glow b" />
          <div className="grid" />
        </div>
        <header className="d-top">
          <span className="d-brand">
            <BrandMark className="d-mark" />
            <span className="d-word">GAUNTLET</span>
          </span>
          <span className="d-run">{title}</span>
          <span className="d-spacer" />
          <span className="d-section">{section}</span>
          {total > 1 && (
            <span className="d-prog">
              <span className="tnum">
                {idx + 1} / {total}
              </span>
              <i>
                <b style={{ width: `${((idx + 1) / total) * 100}%` }} />
              </i>
            </span>
          )}
        </header>
        <main className="d-body cs-body" key={idx}>
          {children}
        </main>
        <footer className="d-cap">
          <span className="d-cap-k">What you’re seeing</span>
          <span className="d-cap-t">{caption}</span>
          {fine && <span className="d-cap-f">{fine}</span>}
        </footer>
      </div>
    </div>
  );
}

function HistorySlide() {
  const { query } = useRoute();
  const { categories } = useMeta();
  const suite = query.get('suite') || 'core';
  const metric = query.get('metric') || 'index';
  const tiers = (query.get('tiers') ?? '').split(',').filter(Boolean);
  const state = useAsync<HistoryData>(() => channelApi.history(suite, metric, tiers), [suite, metric, tiers.join(',')]);
  const metricLabel = metric === 'index' ? 'Gauntlet Index' : categories.find((c) => c.id === metric)?.name ?? metric;
  useHotkeys({ Escape: () => navigate('/history', { suite, metric: metric === 'index' ? undefined : metric, tiers: tiers.join(',') || undefined }) });
  const h = state.data;
  const jump = h?.jumps[0];
  return (
    <Stage section="Family history" title={`${metricLabel} over time`} idx={0} total={1} caption="Every dot is an AI model at its release date; each line follows one company’s top models over time. Higher means it scored better on the same tests." fine={h ? `${h.suiteId} suite · ${tiers.length ? `${tiers.join(', ')} models` : 'all tiers'} · models without a release date are not shown` : undefined}>
      <div className="cs-history">
        <div className="cs-h-title">
          <h1>Is AI getting better?</h1>
          {jump && (
            <div className="cs-jump" style={{ ['--c' as string]: h!.families.find((f) => f.family === jump.family)?.color }}>
              <span className="k">Biggest jump</span>
              <span className="v">+{jump.delta.toFixed(1)}</span>
              <span className="t">
                {jump.from.label} → <b>{jump.to.label}</b>
              </span>
            </div>
          )}
        </div>
        {h ? <HistoryChart data={h} metricLabel={metricLabel} big height={600} /> : <div className="cs-loading">{state.error ? state.error.message : 'Loading…'}</div>}
      </div>
    </Stage>
  );
}

function ChallengeSlides() {
  const { query } = useRoute();
  const season = query.get('season') || '';
  const state = useAsync<{ slides: ChallengeSlide[]; season: string }>(async () => {
    const s = season || (await channelApi.challenge()).queue.season;
    return { slides: await channelApi.challengeSlides(s), season: s };
  }, [season]);
  const slides = state.data?.slides ?? [];
  const idx = Math.min(Math.max(0, Number(query.get('s') ?? 1) - 1), Math.max(0, slides.length - 1));
  const [reveal, setReveal] = useState(false);
  useEffect(() => setReveal(false), [idx]);
  const go = useCallback((i: number) => setQuery({ s: i + 1 }), []);
  const next = () => (!reveal ? setReveal(true) : idx < slides.length - 1 ? go(idx + 1) : undefined);
  const prev = () => (reveal ? setReveal(false) : idx > 0 ? go(idx - 1) : undefined);
  useHotkeys({
    ArrowRight: (e) => (e.preventDefault(), next()),
    ' ': (e) => (e.preventDefault(), next()),
    PageDown: (e) => (e.preventDefault(), next()),
    ArrowLeft: (e) => (e.preventDefault(), prev()),
    PageUp: (e) => (e.preventDefault(), prev()),
    f: () => (document.fullscreenElement ? void document.exitFullscreen() : void document.documentElement.requestFullscreen?.().catch(() => undefined)),
    Escape: () => navigate('/challenge', { season: season || undefined }),
  });
  const s = slides[idx];
  const right = useMemo(() => (s ? s.outcomes.filter((o) => o.passed).length : 0), [s]);
  if (!s)
    return (
      <Stage section="Viewer challenge" title="Viewer challenge" idx={0} total={1} caption="Questions written by viewers, kept private so no AI can have seen them before.">
        <div className="cs-loading">{state.loading ? 'Loading…' : state.error ? state.error.message : 'No approved questions yet: approve some in Viewer Challenge.'}</div>
      </Stage>
    );
  return (
    <Stage
      section="Viewer challenge"
      title={`Season ${state.data?.season}`}
      idx={idx}
      total={slides.length}
      caption={reveal ? (s.outcomes.length ? `${right} of ${s.outcomes.length} models got this viewer’s question right.` : 'The answer, checked by the channel before the question was accepted.') : 'A question sent in by a viewer. No AI has seen it before: it is kept in a private test that is never published.'}
      fine={reveal ? (s.outcomes.length ? 'Every model got the same question in a fresh chat · answers graded automatically' : undefined) : 'Press → to reveal the answer'}
    >
      <div className="cs-challenge">
        <div className="cs-credit">
          <span className="cs-tag">Viewer challenge</span>
          <span>
            submitted by <b>{s.credit ?? 'a viewer'}</b>
          </span>
        </div>
        <blockquote className={cx('cs-question', (s.question.length > 260 || s.question.split('\n').length > 4) && 'long')}>{s.question}</blockquote>
        <div className={cx('cs-reveal', reveal && 'on')} aria-hidden={!reveal}>
          <div className="cs-answer">
            <span className="k">Answer</span>
            <span className="v">{s.answer}</span>
          </div>
          {s.outcomes.length > 0 ? (
            <div className="cs-outcomes">
              {s.outcomes.map((o) => (
                <span key={o.contestantId} className={cx('cs-out', o.passed ? 'ok' : 'no')} style={{ ['--c' as string]: o.color }}>
                  {o.passed ? <Icon.Check /> : <Icon.X />}
                  {o.label}
                </span>
              ))}
            </div>
          ) : (
            <div className="cs-pending">Not run yet: results appear here after a run that includes this test.</div>
          )}
        </div>
      </div>
    </Stage>
  );
}

export default function ChannelSlidesPage({ kind }: { kind: string }) {
  useEffect(() => {
    document.title = `${kind === 'history' ? 'Family history' : 'Viewer challenge'} · Presenter · Gauntlet`;
  }, [kind]);
  return kind === 'history' ? <HistorySlide /> : <ChallengeSlides />;
}
