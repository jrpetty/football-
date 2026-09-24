/**
 * Replay player for program results: play / pause / step / scrub over frames,
 * tile-map grid, animated gauges, stat chips, tone-coloured narration,
 * series chart and side-by-side SVG comparison. Full-screen ready for video.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent as RKeyboardEvent } from 'react';
import type { ReplayData, ReplayFrame } from '../types.ts';
import { Icon } from './icons.tsx';
import { cx } from './ui.tsx';
import { LineChart } from './charts/LineChart.tsx';

const SPEEDS = [0.5, 1, 2, 4];
const BASE_MS = 1100;

type Legend = NonNullable<ReplayFrame['grid']>['legend'];

function svgDataUrl(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

function readableOn(hex: string | undefined): string {
  if (!hex || !/^#?[0-9a-f]{6}$/i.test(hex.replace('#', ''))) return 'var(--text-1)';
  const h = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  return lum > 0.55 ? '#0b1220' : '#ffffff';
}

export function TileMap({ rows, legend, showLegend = true }: { rows: string[]; legend?: Legend; showLegend?: boolean }) {
  const cols = Math.max(1, ...rows.map((r) => Array.from(r).length));
  const used = useMemo(() => {
    const set = new Set<string>();
    for (const r of rows) for (const ch of Array.from(r)) set.add(ch);
    return set;
  }, [rows]);
  return (
    <div className="tilemap-wrap">
      <div className="tilemap" style={{ ['--cols' as string]: cols, maxWidth: cols * 60 }} role="img" aria-label={`Grid map, ${rows.length} by ${cols}`}>
        {rows.map((row, y) =>
          Array.from(row.padEnd(cols, ' ')).map((ch, x) => {
            const l = legend?.[ch];
            const bg = l?.color;
            return (
              <div
                key={`${y}-${x}`}
                className={cx('tile', !l && ch === ' ' && 'void', l?.emoji && 'has-emoji')}
                style={bg ? { background: l?.emoji ? `color-mix(in srgb, ${bg} 38%, var(--surface-2))` : bg, color: readableOn(bg) } : undefined}
                title={l ? l.label : ch.trim() ? ch : undefined}
              >
                {l?.emoji ? <span className="emoji">{l.emoji}</span> : l ? '' : ch}
              </div>
            );
          }),
        )}
      </div>
      {showLegend && legend && (
        <div className="tile-legend">
          {Object.entries(legend)
            .filter(([ch]) => used.has(ch))
            .map(([ch, l]) => (
              <span key={ch} className="lg-item">
                <span className="tile-key" style={{ background: l.color ?? 'var(--surface-3)' }}>
                  {l.emoji ? <span className="emoji">{l.emoji}</span> : null}
                </span>
                {l.label}
              </span>
            ))}
        </div>
      )}
    </div>
  );
}

export function Gauge({ label, value }: { label: string; value: number }) {
  const v = Math.max(0, Math.min(100, value));
  return (
    <div className="gauge">
      <div className="gauge-head">
        <span>{label}</span>
        <b className="tnum">{Math.round(value)}</b>
      </div>
      <div className="gauge-track">
        <div className={cx('gauge-fill', v <= 20 && 'low')} style={{ width: `${v}%` }} />
      </div>
    </div>
  );
}

export function FrameNarration({ frame, large }: { frame: ReplayFrame; large?: boolean }) {
  return (
    <div className={cx('narration', frame.tone && `tone-${frame.tone}`, large && 'large')}>
      {frame.observation && (
        <div className="nar-block">
          <span className="nar-k">Observation</span>
          <p>{frame.observation}</p>
        </div>
      )}
      {frame.action && (
        <div className="nar-block action">
          <span className="nar-k">Action</span>
          <p>{frame.action}</p>
        </div>
      )}
      {frame.outcome && (
        <div className="nar-block outcome">
          <span className="nar-k">
            Outcome
            {frame.tone === 'good' && <span className="tone-tag good">▲ good</span>}
            {frame.tone === 'bad' && <span className="tone-tag bad">▼ bad</span>}
          </span>
          <p>{frame.outcome}</p>
        </div>
      )}
    </div>
  );
}

export function ReplayPlayer({ replay, autoPlay = false }: { replay: ReplayData; autoPlay?: boolean }) {
  const frames = useMemo(() => replay.frames ?? [], [replay.frames]);
  const [idx, setIdx] = useState(0);
  const [playing, setPlaying] = useState(autoPlay && frames.length > 1);
  const [speed, setSpeed] = useState(1);
  const [isFs, setIsFs] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const frame: ReplayFrame | undefined = frames[Math.min(idx, frames.length - 1)];
  const hasGrid = frames.some((f) => f.grid?.rows?.length);
  const gauges = replay.gauges ?? [];

  useEffect(() => {
    if (!playing) return;
    if (idx >= frames.length - 1) {
      setPlaying(false);
      return;
    }
    const t = window.setTimeout(() => setIdx((i) => Math.min(frames.length - 1, i + 1)), BASE_MS / speed);
    return () => window.clearTimeout(t);
  }, [playing, idx, speed, frames.length]);

  useEffect(() => {
    const on = () => setIsFs(document.fullscreenElement === rootRef.current);
    document.addEventListener('fullscreenchange', on);
    return () => document.removeEventListener('fullscreenchange', on);
  }, []);

  const toggleFs = useCallback(() => {
    const el = rootRef.current;
    if (!el) return;
    if (document.fullscreenElement) void document.exitFullscreen();
    else void el.requestFullscreen?.().catch(() => undefined);
  }, []);

  const togglePlay = () => {
    if (!playing && idx >= frames.length - 1) setIdx(0);
    setPlaying((p) => !p);
  };

  const onKey = (e: RKeyboardEvent<HTMLDivElement>) => {
    const tag = (e.target as HTMLElement).tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
    if (e.key === ' ' || e.key === 'k') {
      e.preventDefault();
      togglePlay();
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      setPlaying(false);
      setIdx((i) => Math.min(frames.length - 1, i + 1));
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      setPlaying(false);
      setIdx((i) => Math.max(0, i - 1));
    } else if (e.key === 'Home') setIdx(0);
    else if (e.key === 'End') setIdx(frames.length - 1);
    else if (e.key === 'f') toggleFs();
  };

  const statChips = frame?.stats ? Object.entries(frame.stats).filter(([k, v]) => !(gauges.includes(k) && typeof v === 'number')) : [];
  const gaugeVals = gauges.map((g) => ({ k: g, v: frame?.stats?.[g] })).filter((x): x is { k: string; v: number } => typeof x.v === 'number');
  const pct = frames.length > 1 ? (idx / (frames.length - 1)) * 100 : 100;

  return (
    <div className={cx('replay', isFs && 'is-fs')} ref={rootRef} tabIndex={0} onKeyDown={onKey} aria-label={`Replay: ${replay.title}`}>
      <div className="replay-head">
        <div className="stack tight" style={{ minWidth: 0 }}>
          <span className="eyebrow">Replay</span>
          <h3 className="ellipsis">{replay.title}</h3>
        </div>
        <span className="spacer" />
        {frames.length > 0 && (
          <span className="badge outline tnum">
            Step {frame?.step ?? idx + 1} · {idx + 1}/{frames.length}
          </span>
        )}
        <button type="button" className="btn ghost icon sm" onClick={toggleFs} aria-label={isFs ? 'Exit full screen' : 'Full screen'} title="Full screen (F)">
          <Icon.Maximize />
        </button>
      </div>

      {frames.length === 0 && !replay.svgCompare && !replay.series?.length ? (
        <div className="chart-empty">This replay has no frames.</div>
      ) : null}

      {frame && (
        <div className={cx('replay-stage', hasGrid ? 'with-grid' : 'no-grid')}>
          {hasGrid && (
            <div className="stage-map">
              {frame.grid?.rows?.length ? <TileMap rows={frame.grid.rows} legend={frame.grid.legend} /> : <div className="chart-empty">No map this step.</div>}
            </div>
          )}
          <div className="stage-side">
            <div className="frame-title" key={idx}>
              {frame.label ?? `Step ${frame.step}`}
            </div>
            {gaugeVals.length > 0 && (
              <div className="gauges">
                {gaugeVals.map((g) => (
                  <Gauge key={g.k} label={g.k} value={g.v} />
                ))}
              </div>
            )}
            {statChips.length > 0 && (
              <div className="stat-chips">
                {statChips.map(([k, v]) => (
                  <span key={k} className="stat-chip">
                    <span>{k}</span>
                    <b>{String(v)}</b>
                  </span>
                ))}
              </div>
            )}
            <FrameNarration frame={frame} large={!hasGrid} />
          </div>
        </div>
      )}

      {frames.length > 0 && (
        <div className="replay-controls">
          <button type="button" className="btn icon sm" onClick={() => (setPlaying(false), setIdx((i) => Math.max(0, i - 1)))} aria-label="Previous step" disabled={idx === 0}>
            <Icon.StepBack />
          </button>
          <button type="button" className="btn icon primary play-btn" onClick={togglePlay} aria-label={playing ? 'Pause' : 'Play'} disabled={frames.length < 2}>
            {playing ? <Icon.Pause /> : <Icon.Play />}
          </button>
          <button type="button" className="btn icon sm" onClick={() => (setPlaying(false), setIdx((i) => Math.min(frames.length - 1, i + 1)))} aria-label="Next step" disabled={idx >= frames.length - 1}>
            <Icon.StepFwd />
          </button>
          <div className="scrub">
            <div className="scrub-ticks" aria-hidden="true">
              {frames.map((f, i) => (
                <i key={i} className={cx(f.tone && `t-${f.tone}`)} style={{ left: `${frames.length > 1 ? (i / (frames.length - 1)) * 100 : 0}%` }} />
              ))}
            </div>
            <input
              type="range"
              min={0}
              max={Math.max(0, frames.length - 1)}
              value={idx}
              onChange={(e) => {
                setPlaying(false);
                setIdx(Number(e.target.value));
              }}
              aria-label="Scrub timeline"
              style={{ ['--pct' as string]: `${pct}%` }}
            />
          </div>
          <select className="select sm" style={{ width: 84 }} value={speed} onChange={(e) => setSpeed(Number(e.target.value))} aria-label="Playback speed">
            {SPEEDS.map((s) => (
              <option key={s} value={s}>
                {s}×
              </option>
            ))}
          </select>
        </div>
      )}

      {(!!replay.svgCompare || !!replay.series?.length) && (
        <div className={cx('replay-extras', !!replay.svgCompare && !!replay.series?.length && 'two')}>
          {replay.svgCompare && (
            <div className="svg-compare">
              {[replay.svgCompare.left, replay.svgCompare.right].map((p, i) => (
                <figure key={i} className="svg-panel">
                  <img src={svgDataUrl(p.svg)} alt={p.title} />
                  <figcaption>{p.title}</figcaption>
                </figure>
              ))}
            </div>
          )}
          {replay.series && replay.series.length > 0 && (
            <div className="replay-series">
              <div className="mini-title">Over time</div>
              <LineChart title={`${replay.title} series`} series={replay.series} marker={frame?.step ?? null} xLabel="step" />
            </div>
          )}
        </div>
      )}
      <div className="replay-hint no-broadcast">
        <kbd>Space</kbd> play/pause <kbd>←</kbd>
        <kbd>→</kbd> step <kbd>F</kbd> full screen
      </div>
    </div>
  );
}
