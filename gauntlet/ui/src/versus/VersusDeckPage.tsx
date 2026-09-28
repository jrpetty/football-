/**
 * Head to Head Presenter deck — #/present/versus?a=<model>&b=<model>[&run=<runId>][&s=<slide>]
 *
 * Same 1920×1080 stage, chrome and keys as the episode Presenter (PresentPage):
 * face-off intro → one slide per round with a live running score → final result.
 * Keys: → / Space next · ← back · Home / End · F full screen · A auto · ? help · Esc exit.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useAsync, useHotkeys, useInterval, usePrefersReducedMotion } from '../hooks.ts';
import { navigate, setQuery, useRoute } from '../router.tsx';
import { cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { BrandMark } from '../components/Brand.tsx';
import { nameOf, resultHeadline, roundVerdict } from '../../../src/versus/words.ts';
import { versusApi, type VersusData } from './client.ts';
import { DecisiveMoment, FaceOff, FacingBars, RoundHead, RoundStrip, Scoreboard, TaleOfTape, WinnerBadge, useCorners, type Corners } from './parts.tsx';
import './versus.css';

const W = 1920;
const H = 1080;
const AUTO_ADVANCE_MS = 9000;

type Slide = { kind: 'intro' } | { kind: 'round'; i: number } | { kind: 'final' };

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

/** Rounds won by each side after round `i` (inclusive; -1 = before the first). */
function scoreAfter(d: VersusData, i: number): { a: number; b: number } {
  const done = d.rounds.slice(0, i + 1);
  return { a: done.filter((r) => r.winner === 'a').length, b: done.filter((r) => r.winner === 'b').length };
}

// ───────────────────────────── Slides ─────────────────────────────

function IntroSlide({ d, colors }: { d: VersusData; colors: Corners }) {
  const n = d.rounds.length;
  return (
    <div className="vxd-intro">
      <div className="p-eyebrow">Head to head{d.scope.kind === 'run' ? ` · ${d.scope.runName}` : ''}</div>
      <FaceOff d={d} colors={colors} />
      <TaleOfTape d={d} colors={colors} />
      <div className="vxd-rule">
        <b>{n}</b> {n === 1 ? 'round' : 'rounds'}: one per test both models took. Most rounds wins.
      </div>
    </div>
  );
}

function RunningScore({ d, colors, i }: { d: VersusData; colors: Corners; i: number }) {
  const before = scoreAfter(d, i - 1);
  const after = scoreAfter(d, i);
  const reduced = usePrefersReducedMotion();
  const [shown, setShown] = useState(reduced ? after : before);
  useEffect(() => {
    if (reduced) {
      setShown(after);
      return;
    }
    setShown(before);
    const t = window.setTimeout(() => setShown(after), 1300);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [i, reduced]);
  const w = d.rounds[i]!.winner;
  const bumped = shown.a === after.a && shown.b === after.b && w !== 'tie';
  return (
    <div className="vxd-running" aria-label={`Score so far: ${nameOf(d.a)} ${after.a}, ${nameOf(d.b)} ${after.b}`}>
      <span className="vxd-running-k">Score so far</span>
      <div className="vxd-running-row">
        <span className="vxd-rn" style={{ color: colors.a }}>
          {nameOf(d.a)}
        </span>
        <b className={cx('vxd-rv', bumped && w === 'a' && 'bump')} style={{ color: colors.a }}>
          {shown.a}
        </b>
        <i>–</i>
        <b className={cx('vxd-rv', bumped && w === 'b' && 'bump')} style={{ color: colors.b }}>
          {shown.b}
        </b>
        <span className="vxd-rn" style={{ color: colors.b }}>
          {nameOf(d.b)}
        </span>
      </div>
      <RoundStrip d={d} colors={colors} upto={i} />
    </div>
  );
}

function RoundSlide({ d, colors, i }: { d: VersusData; colors: Corners; i: number }) {
  const r = d.rounds[i]!;
  return (
    <div className="vxd-round">
      <div className="vxd-round-top">
        <RoundHead round={r} index={i} total={d.rounds.length} />
        <RunningScore d={d} colors={colors} i={i} />
      </div>
      <div className="vxd-bars">
        <div className="vxd-bars-names">
          <span style={{ color: colors.a }}>{nameOf(d.a)}</span>
          <span className="vxd-out">score out of 100</span>
          <span style={{ color: colors.b }}>{nameOf(d.b)}</span>
        </div>
        <FacingBars round={r} colors={colors} big />
        <div className="vxd-verdict">
          <WinnerBadge round={r} d={d} colors={colors} />
        </div>
      </div>
      <DecisiveMoment round={r} d={d} colors={colors} compact />
    </div>
  );
}

function FinalSlide({ d, colors }: { d: VersusData; colors: Corners }) {
  return (
    <div className="vxd-final">
      <Scoreboard d={d} colors={colors} size="lg" />
      <RoundStrip d={d} colors={colors} />
    </div>
  );
}

function captionFor(s: Slide, d: VersusData): { text: string; fine?: string } {
  if (s.kind === 'intro') {
    return {
      text: 'Two AI models, the same tests, one round per test. The table shows what each one costs and how much text it can read at once.',
      fine: 'Prices in US dollars per million tokens (a token is about ¾ of a word)',
    };
  }
  if (s.kind === 'round') {
    const r = d.rounds[s.i]!;
    return {
      text: `${r.testName}: ${roundVerdict(r, d)}.${r.moment ? ' Below: one question where one model was right and the other wrong, with both answers.' : ''}`,
      fine: `Scores out of 100 over the ${r.cases} ${r.cases === 1 ? 'question' : 'questions'} both answered · draw = less than ${d.tieMargin} points apart`,
    };
  }
  return {
    text: d.rounds.length ? `${resultHeadline(d).length > 32 ? 'The final result.' : `${resultHeadline(d)}.`} Most rounds won decides it; the table adds average score, cost, speed and value for money.` : 'These two models have no test in common yet.',
    fine: 'Value = average score points per US dollar · costs are for running these tests once',
  };
}

// ───────────────────────────── Page ─────────────────────────────

const KEYS: Array<[string, string]> = [
  ['→  Space', 'Next slide'],
  ['←', 'Back'],
  ['Home  End', 'First / last slide'],
  ['A', 'Auto: advance every 9 s'],
  ['F', 'Full screen'],
  ['?', 'Show or hide this help'],
  ['Esc', 'Leave the presenter'],
];

export default function VersusDeckPage() {
  const { query } = useRoute();
  const a = query.get('a') ?? '';
  const b = query.get('b') ?? '';
  const run = query.get('run') ?? '';
  const scale = useStageScale();
  const state = useAsync(() => versusApi.get(a, b, run || undefined), [a, b, run]);
  const d = state.data;

  const slides = useMemo<Slide[]>(() => (d ? [{ kind: 'intro' }, ...d.rounds.map((_, i) => ({ kind: 'round' as const, i })), { kind: 'final' }] : []), [d]);
  const requested = Math.max(1, Number(query.get('s')) || 1) - 1;
  const idx = slides.length ? Math.min(requested, slides.length - 1) : 0;
  const slide = slides[idx];

  const [auto, setAuto] = useState(false);
  const [help, setHelp] = useState(false);
  const [hint, setHint] = useState(true);
  const [ctrl, setCtrl] = useState(false);
  const dirRef = useRef<1 | -1>(1);
  const ctrlTimer = useRef<number | undefined>(undefined);

  const go = useCallback(
    (i: number) => {
      if (!slides.length) return;
      const next = Math.max(0, Math.min(slides.length - 1, i));
      if (next === idx) return;
      dirRef.current = next > idx ? 1 : -1;
      setQuery({ s: next + 1 });
    },
    [slides.length, idx],
  );
  const next = useCallback(() => {
    setHint(false);
    if (idx >= slides.length - 1) setAuto(false);
    else go(idx + 1);
  }, [idx, slides.length, go]);
  const prev = useCallback(() => {
    setHint(false);
    go(idx - 1);
  }, [idx, go]);
  const toggleFs = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void document.documentElement.requestFullscreen?.().catch(() => undefined);
  }, []);
  const exit = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen();
    navigate('/versus', { a, b, run: run || undefined });
  }, [a, b, run]);

  useHotkeys({
    ArrowRight: (e) => (e.preventDefault(), next()),
    ArrowDown: (e) => (e.preventDefault(), next()),
    ' ': (e) => (e.preventDefault(), next()),
    PageDown: (e) => (e.preventDefault(), next()),
    ArrowLeft: (e) => (e.preventDefault(), prev()),
    ArrowUp: (e) => (e.preventDefault(), prev()),
    PageUp: (e) => (e.preventDefault(), prev()),
    Backspace: (e) => (e.preventDefault(), prev()),
    Home: (e) => (e.preventDefault(), go(0)),
    End: (e) => (e.preventDefault(), go(slides.length - 1)),
    f: () => toggleFs(),
    a: () => setAuto((x) => !x),
    '?': () => setHelp((h) => !h),
    h: () => setHelp((h) => !h),
    Escape: () => {
      if (help) setHelp(false);
      else if (!document.fullscreenElement) exit();
    },
  });

  const stepRef = useRef({ idx, at: Date.now() });
  if (stepRef.current.idx !== idx) stepRef.current = { idx, at: Date.now() };
  useInterval(
    () => {
      if (Date.now() - stepRef.current.at >= AUTO_ADVANCE_MS) next();
    },
    auto ? 200 : null,
  );

  useEffect(() => {
    const t = window.setTimeout(() => setHint(false), 4500);
    const show = () => {
      setCtrl(true);
      window.clearTimeout(ctrlTimer.current);
      ctrlTimer.current = window.setTimeout(() => setCtrl(false), 2200);
    };
    window.addEventListener('pointermove', show);
    return () => {
      window.clearTimeout(t);
      window.removeEventListener('pointermove', show);
      window.clearTimeout(ctrlTimer.current);
    };
  }, []);

  useEffect(() => {
    if (d) document.title = `${nameOf(d.a)} vs ${nameOf(d.b)} · Presenter · Gauntlet`;
  }, [d]);

  if (!d || !slide) {
    return (
      <div className="deck deck-msg">
        {state.error || (!state.loading && (!a || !b)) ? (
          <div className="deck-msg-box">
            <Icon.Alert />
            <h2>Couldn’t load this head to head</h2>
            <p>{state.error?.message ?? 'Pick two models on the Head to Head page first.'}</p>
            <div className="row" style={{ gap: 10, justifyContent: 'center' }}>
              <a className="btn" href="#/versus">
                Choose two models
              </a>
            </div>
          </div>
        ) : (
          <div className="deck-msg-box">
            <BrandMark className="deck-load-mark" />
            <p>Preparing the match…</p>
          </div>
        )}
      </div>
    );
  }

  return <DeckView d={d} slide={slide} idx={idx} total={slides.length} dir={dirRef.current} auto={auto} ctrl={ctrl} hint={hint} help={help} setHelp={setHelp} prev={prev} next={next} setAuto={setAuto} toggleFs={toggleFs} exit={exit} scale={scale} />;
}

function DeckView(p: {
  d: VersusData;
  slide: Slide;
  idx: number;
  total: number;
  dir: 1 | -1;
  auto: boolean;
  ctrl: boolean;
  hint: boolean;
  help: boolean;
  setHelp: (f: (h: boolean) => boolean) => void;
  prev: () => void;
  next: () => void;
  setAuto: (f: (x: boolean) => boolean) => void;
  toggleFs: () => void;
  exit: () => void;
  scale: number;
}) {
  const { d, slide, idx, total } = p;
  const colors = useCorners(d);
  const cap = captionFor(slide, d);
  const section = slide.kind === 'intro' ? 'Face-off' : slide.kind === 'round' ? `Round ${slide.i + 1}` : 'Final result';
  return (
    <div className={cx('deck vxd', !p.ctrl && 'hide-cursor')}>
      <div className="deck-stage" style={{ transform: `translate(-50%, -50%) scale(${p.scale})`, ['--vx-a' as string]: colors.a, ['--vx-b' as string]: colors.b }} data-slide={`versus-${slide.kind}`}>
        <div className="deck-bg vxd-bg" aria-hidden="true">
          <div className="glow a" />
          <div className="glow b" />
          <div className="grid" />
        </div>
        <header className="d-top">
          <span className="d-brand">
            <BrandMark className="d-mark" />
            <span className="d-word">GAUNTLET</span>
          </span>
          <span className="d-run">
            {nameOf(d.a)} vs {nameOf(d.b)}
          </span>
          <span className="d-spacer" />
          <span className="d-section">{section}</span>
          <span className="d-prog" aria-label={`Slide ${idx + 1} of ${total}`}>
            <span className="tnum">
              {idx + 1} / {total}
            </span>
            <i>
              <b style={{ width: `${((idx + 1) / total) * 100}%` }} />
            </i>
            {p.auto && <em>AUTO</em>}
          </span>
        </header>
        <main className="d-body vxd-body" key={idx} data-dir={p.dir > 0 ? 'fwd' : 'back'} aria-live="polite">
          {slide.kind === 'intro' && <IntroSlide d={d} colors={colors} />}
          {slide.kind === 'round' && <RoundSlide d={d} colors={colors} i={slide.i} />}
          {slide.kind === 'final' && <FinalSlide d={d} colors={colors} />}
        </main>
        <footer className="d-cap" key={`cap-${idx}`}>
          <span className="d-cap-k">What you’re seeing</span>
          <span className="d-cap-t">{cap.text}</span>
          {cap.fine && <span className="d-cap-f">{cap.fine}</span>}
        </footer>
      </div>

      <div className={cx('deck-ctrl', p.ctrl && 'visible')}>
        <button className="btn sm icon" onClick={p.prev} aria-label="Previous slide" disabled={idx === 0}>
          <Icon.StepBack />
        </button>
        <button className="btn sm icon" onClick={p.next} aria-label="Next slide">
          <Icon.StepFwd />
        </button>
        <button className={cx('btn sm', p.auto && 'primary')} onClick={() => p.setAuto((x) => !x)} aria-pressed={p.auto} title="Auto (A)">
          {p.auto ? <Icon.Pause /> : <Icon.Play />} Auto
        </button>
        <button className="btn sm icon" onClick={p.toggleFs} aria-label="Full screen" title="Full screen (F)">
          <Icon.Maximize />
        </button>
        <button className="btn sm icon" onClick={() => p.setHelp((h) => !h)} aria-label="Keyboard shortcuts" title="Shortcuts (?)">
          <Icon.Keyboard />
        </button>
        <button className="btn sm" onClick={p.exit} title="Leave the presenter (Esc)">
          <Icon.X /> Exit
        </button>
      </div>

      {p.hint && !p.help && (
        <div className="deck-hint" aria-hidden="true">
          <kbd>→</kbd> next · <kbd>←</kbd> back · <kbd>F</kbd> full screen · <kbd>A</kbd> auto · <kbd>?</kbd> keys
        </div>
      )}

      {p.help && (
        <div className="deck-help" role="dialog" aria-label="Presenter shortcuts" onClick={() => p.setHelp(() => false)}>
          <div className="deck-help-box" onClick={(e) => e.stopPropagation()}>
            <h2>Presenter shortcuts</h2>
            <dl>
              {KEYS.map(([k, v]) => (
                <div key={k}>
                  <dt>
                    {k.split('  ').map((x) => (
                      <kbd key={x}>{x}</kbd>
                    ))}
                  </dt>
                  <dd>{v}</dd>
                </div>
              ))}
            </dl>
            <p className="muted">Tip: add ?s=3 to the address to open a specific slide.</p>
          </div>
        </div>
      )}
    </div>
  );
}
