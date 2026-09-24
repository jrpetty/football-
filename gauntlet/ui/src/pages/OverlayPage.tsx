/**
 * OBS overlay — a transparent 1920×1080 page to add as an OBS "Browser" source.
 *
 *   /overlay/<runId|latest|demo>?view=scoreboard|ticker|lower-third|bracket-lite
 *                                &theme=glass|solid|light|minimal&pos=tl|tr|bl|br|top|bottom&safe=0&scale=1.2
 *
 * Live-updates from the run's Server-Sent Events stream; "latest" follows the
 * run in progress (or the newest run) so the URL never needs changing; "demo"
 * animates made-up data so the scene can be set up before a real run.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { api, subscribeRun } from '../api.ts';
import { useRoute } from '../router.tsx';
import { BrandMark } from '../components/Brand.tsx';
import { cx } from '../components/ui.tsx';
import type { OverlayData, RunEvent } from '../types.ts';
import { OVERLAY_VIEWS } from '../components/studio/overlayOptions.ts';
import type { OverlayPos, OverlayTheme, OverlayView } from '../components/studio/overlayOptions.ts';
import '../styles/overlay.css';

const W = 1920;
const H = 1080;

function useStageScale(): number {
  const calc = () => Math.min(window.innerWidth / W, window.innerHeight / H) || 1;
  const [k, setK] = useState(calc);
  useEffect(() => {
    const on = () => setK(calc());
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  return k;
}

// ───────────────────────────── Demo data ─────────────────────────────

const DEMO_MODELS = [
  { id: 'atlas', label: 'Meridian Atlas', color: '#22d3ee' },
  { id: 'kite', label: 'Kestrel Kite', color: '#f59e0b' },
  { id: 'nova', label: 'Helios Nova', color: '#a78bfa' },
  { id: 'sable', label: 'Obsidian Sable', color: '#34d399' },
];
const DEMO_TESTS = [
  { id: 'island', name: 'Survival Island', hook: 'Thirty days, one island, no help.' },
  { id: 'escape', name: 'Escape Room', hook: 'Locked in. Clues everywhere. How many moves?' },
  { id: 'honesty', name: 'The Honesty Trap', hook: 'Half these questions are lies. Will the model play along?' },
  { id: 'maths', name: 'Competition Maths', hook: 'Twenty contest problems. No partial credit.' },
  { id: 'liars', name: 'The Liar’s Table', hook: 'Four suspects. One liar. Find them.' },
];

function demoState(step: number): OverlayData {
  // Deterministic pseudo-random stream so every OBS reload looks the same.
  let s = 1234567;
  const rnd = () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const skill: Record<string, number> = { atlas: 0.82, kite: 0.74, nova: 0.66, sable: 0.55 };
  const perTest = 8;
  const results: Array<{ key: string; model: (typeof DEMO_MODELS)[0]; test: (typeof DEMO_TESTS)[0]; score: number; at: number }> = [];
  for (let i = 0; i < step; i++) {
    const test = DEMO_TESTS[Math.floor(i / perTest) % DEMO_TESTS.length]!;
    const model = DEMO_MODELS[i % DEMO_MODELS.length]!;
    const score = Math.max(0, Math.min(1, skill[model.id]! + (rnd() - 0.5) * 0.6));
    results.push({ key: `d${i}`, model, test, score: Math.round(score * 20) / 20, at: i });
  }
  const standings = DEMO_MODELS.map((m) => {
    const mine = results.filter((r) => r.model.id === m.id);
    const avg = mine.length ? (mine.reduce((a, r) => a + r.score, 0) / mine.length) * 100 : null;
    return { id: m.id, label: m.label, color: m.color, score: avg === null ? null : Math.round(avg * 10) / 10, done: mine.length, total: (perTest * DEMO_TESTS.length) / DEMO_MODELS.length };
  }).sort((a, b) => (b.score ?? -1) - (a.score ?? -1));
  const cur = DEMO_TESTS[Math.floor(step / perTest) % DEMO_TESTS.length]!;
  return {
    runId: 'demo',
    runName: 'Demo run — set up your scene',
    status: 'running',
    scoreKind: 'average',
    standings,
    ticker: results
      .slice(-12)
      .reverse()
      .map((r) => ({ key: r.key, contestantId: r.model.id, label: r.model.label, color: r.model.color, testName: r.test.name, score: r.score, status: 'ok', summary: '', at: String(r.at) })),
    now: { testId: cur.id, testName: cur.name, hook: cur.hook, contestants: DEMO_MODELS },
    tests: DEMO_TESTS.map((t) => {
      const rs = results.filter((r) => r.test.id === t.id);
      const by = new Map<string, number[]>();
      for (const r of rs) by.set(r.model.id, [...(by.get(r.model.id) ?? []), r.score]);
      const best = [...by].map(([id, xs]) => ({ id, v: xs.reduce((a, b) => a + b, 0) / xs.length })).sort((a, b) => b.v - a.v)[0];
      const m = best ? DEMO_MODELS.find((x) => x.id === best.id)! : null;
      return { id: t.id, name: t.name, winner: m && best ? { ...m, score: Math.round(best.v * 100) } : null, done: rs.length, total: perTest };
    }),
    progress: { completed: results.length, total: perTest * DEMO_TESTS.length },
  };
}

// ───────────────────────────── Data hook ─────────────────────────────

function useOverlayData(runId: string, demo: boolean): { data: OverlayData | null; error: string | null; flash: string | null } {
  const [data, setData] = useState<OverlayData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [step, setStep] = useState(9);
  const timer = useRef<number | undefined>(undefined);

  // Demo: one new result every 2.2 s, looping.
  useEffect(() => {
    if (!demo) return;
    const id = window.setInterval(() => setStep((s) => (s >= 40 ? 6 : s + 1)), 2200);
    return () => window.clearInterval(id);
  }, [demo]);
  useEffect(() => {
    if (!demo) return;
    const d = demoState(step);
    setData(d);
    setFlash(d.ticker[0]?.key ?? null);
  }, [demo, step]);

  const load = useCallback(async () => {
    try {
      const d = await api.overlay(runId);
      setData(d);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [runId]);

  useEffect(() => {
    if (demo) return;
    void load();
    // "latest" may move to a newer run: check now and then.
    const id = runId === 'latest' ? window.setInterval(load, 20_000) : undefined;
    return () => window.clearInterval(id);
  }, [demo, load, runId]);

  const resolved = data?.runId;
  useEffect(() => {
    if (demo || !resolved) return;
    const refetch = () => {
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(load, 700);
    };
    const unsub = subscribeRun(resolved, {
      onEvent: (e: RunEvent) => {
        if (e.type === 'job.started') {
          setData((d) => {
            if (!d) return d;
            const t = d.tests.find((x) => x.id === e.testId);
            if (!t || d.now?.testId === t.id) return d;
            return { ...d, now: { testId: t.id, testName: t.name, contestants: d.standings.map((s) => ({ id: s.id, label: s.label, color: s.color })) } };
          });
        } else if (e.type === 'job.finished') {
          setFlash(e.key);
          refetch();
        } else if (e.type === 'run.status') refetch();
      },
      onOpen: (reconnected) => {
        if (reconnected) refetch();
      },
    });
    return () => {
      unsub();
      window.clearTimeout(timer.current);
    };
  }, [demo, resolved, load]);

  return { data, error, flash };
}

// ───────────────────────────── Views ─────────────────────────────

function fmtScore(v: number | null): string {
  return v === null ? '—' : v >= 99.95 ? '100' : v.toFixed(1);
}

function Brand({ live }: { live: boolean }) {
  return (
    <div className="ov-brand">
      <BrandMark className="ov-mark" />
      <span className="ov-word">Gauntlet</span>
      {live && <span className="ov-live">Live</span>}
    </div>
  );
}

function Scoreboard({ d, flash }: { d: OverlayData; flash: string | null }) {
  const rows = d.standings.slice(0, 8);
  const flashed = d.ticker.find((t) => t.key === flash)?.contestantId;
  return (
    <div className="ov-panel ov-scoreboard">
      <div className="ov-head">
        <Brand live={d.status === 'running'} />
        <span className="ov-sub">{d.scoreKind === 'index' ? 'Gauntlet Index' : 'Average score so far'}</span>
      </div>
      <ol className="ov-rows">
        {rows.map((r, i) => (
          <li key={r.id} className={cx('ov-row', flashed === r.id && 'flash')} style={{ '--c': r.color } as CSSProperties}>
            <span className="ov-rank">{i + 1}</span>
            <span className="ov-sw" />
            <span className="ov-name">{r.label}</span>
            <span className="ov-bar">
              <i style={{ width: `${Math.max(2, r.score ?? 0)}%` }} />
            </span>
            <span className="ov-score tnum">{fmtScore(r.score)}</span>
          </li>
        ))}
      </ol>
      <div className="ov-foot">
        <span className="ov-run">{d.runName}</span>
        <span className="tnum">
          {d.progress.completed}/{d.progress.total} cases
        </span>
      </div>
    </div>
  );
}

function Ticker({ d, flash }: { d: OverlayData; flash: string | null }) {
  const items = d.ticker.length ? d.ticker : [];
  const loop = [...items, ...items];
  return (
    <div className="ov-panel ov-ticker">
      <div className="ov-ticker-label">
        <Brand live={d.status === 'running'} />
        <span>Latest results</span>
      </div>
      <div className="ov-ticker-track">
        {items.length === 0 ? (
          <span className="ov-ticker-empty">Waiting for the first result…</span>
        ) : (
          <div className="ov-ticker-move" style={{ animationDuration: `${Math.max(20, items.length * 6)}s` }}>
            {loop.map((t, i) => (
              <span key={`${t.key}-${i}`} className={cx('ov-tick', t.key === flash && 'flash')} style={{ '--c': t.color } as CSSProperties}>
                <span className="ov-sw" />
                <b>{t.label}</b>
                <span className="ov-dim">{t.testName}</span>
                <span className="ov-pts tnum">{t.score === null ? t.status : Math.round(t.score * 100)}</span>
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function LowerThird({ d }: { d: OverlayData }) {
  const now = d.now;
  if (!now) {
    const top = d.standings[0];
    return (
      <div className="ov-panel ov-lower">
        <div className="ov-eyebrow">{d.status === 'running' ? 'Up next' : 'Final result'}</div>
        <div className="ov-title">{d.runName}</div>
        {top && top.score !== null && (
          <div className="ov-vs">
            <span className="ov-chip" style={{ '--c': top.color } as CSSProperties}>
              <span className="ov-sw" />
              {top.label}
            </span>
            <span className="ov-dim">wins with {fmtScore(top.score)}</span>
          </div>
        )}
      </div>
    );
  }
  const cs = now.contestants.slice(0, 4);
  return (
    <div className="ov-panel ov-lower" key={now.testId}>
      <div className="ov-eyebrow">Now testing</div>
      <div className="ov-title">{now.testName}</div>
      {now.hook && <div className="ov-hook">{now.hook}</div>}
      <div className="ov-vs">
        {cs.map((c, i) => (
          <span key={c.id} className="ov-vs-item">
            {i > 0 && <span className="ov-vs-word">vs</span>}
            <span className="ov-chip" style={{ '--c': c.color } as CSSProperties}>
              <span className="ov-sw" />
              {c.label}
            </span>
          </span>
        ))}
        {now.contestants.length > cs.length && <span className="ov-dim">+{now.contestants.length - cs.length} more</span>}
      </div>
    </div>
  );
}

function TestBoard({ d }: { d: OverlayData }) {
  return (
    <div className="ov-panel ov-board">
      <div className="ov-head">
        <Brand live={d.status === 'running'} />
        <span className="ov-sub">Test by test</span>
      </div>
      <ol className="ov-rows">
        {d.tests.slice(0, 10).map((t) => (
          <li key={t.id} className={cx('ov-row', d.now?.testId === t.id && 'current')} style={{ '--c': t.winner?.color ?? 'transparent' } as CSSProperties}>
            <span className="ov-name">{t.name}</span>
            {t.winner ? (
              <span className="ov-chip sm">
                <span className="ov-sw" />
                {t.winner.label}
                <b className="tnum">{t.winner.score}</b>
              </span>
            ) : (
              <span className="ov-dim">{d.now?.testId === t.id ? 'playing now…' : 'not started'}</span>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}

// ───────────────────────────── Page ─────────────────────────────

export default function OverlayPage({ runId }: { runId: string }) {
  const { query } = useRoute();
  const view = (OVERLAY_VIEWS.find((v) => v.id === query.get('view'))?.id ?? 'scoreboard') as OverlayView;
  const theme = (['glass', 'solid', 'light', 'minimal'].includes(query.get('theme') ?? '') ? query.get('theme') : 'glass') as OverlayTheme;
  const pos = (['tl', 'tr', 'bl', 'br', 'top', 'bottom'].includes(query.get('pos') ?? '') ? query.get('pos') : OVERLAY_VIEWS.find((v) => v.id === view)!.pos) as OverlayPos;
  const safe = query.get('safe') !== '0';
  const size = Math.max(0.5, Math.min(2, Number(query.get('scale')) || 1));
  const demo = runId === 'demo' || query.get('demo') === '1';
  const scale = useStageScale();
  const { data, error, flash } = useOverlayData(runId, demo);

  // Transparent page: no app background, so OBS composites the overlay over the scene.
  useEffect(() => {
    document.documentElement.classList.add('overlay-mode');
    return () => document.documentElement.classList.remove('overlay-mode');
  }, []);

  const body = useMemo(() => {
    if (!data) return null;
    switch (view) {
      case 'ticker':
        return <Ticker d={data} flash={flash} />;
      case 'lower-third':
        return <LowerThird d={data} />;
      case 'bracket-lite':
        return <TestBoard d={data} />;
      default:
        return <Scoreboard d={data} flash={flash} />;
    }
  }, [data, view, flash]);

  const bar = pos === 'top' || pos === 'bottom';
  return (
    <div className="ov-root">
      <div className={cx('ov-stage', `ov-theme-${theme}`, safe && 'safe')} style={{ transform: `translate(-50%, -50%) scale(${scale})` }} data-view={view}>
        <div className={cx('ov-slot', `pos-${view === 'ticker' ? (pos === 'top' || pos === 'tl' || pos === 'tr' ? 'top' : 'bottom') : bar ? (pos === 'top' ? 'tl' : 'bl') : pos}`)} style={{ '--k': size } as CSSProperties}>
          {body}
          {!data && error && <div className="ov-panel ov-error">Overlay: {error}</div>}
        </div>
      </div>
    </div>
  );
}
