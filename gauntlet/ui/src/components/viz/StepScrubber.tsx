/**
 * Step player for case visuals that animate (e.g. an optimal plan): step
 * back / forward, play at 0.5× / 1× / 2× / 4×, scrub, Home / End, and F for
 * full screen on the element that owns the keyboard handler. Uses the same
 * controls styling as the replay player.
 */
import { useCallback, useEffect, useState } from 'react';
import type { KeyboardEvent as RKeyboardEvent, RefObject } from 'react';
import { Icon } from '../icons.tsx';
import { usePrefersReducedMotion } from '../../hooks.ts';

const SPEEDS = [0.5, 1, 2, 4];

export interface StepPlayer {
  idx: number;
  count: number;
  playing: boolean;
  speed: number;
  setIdx: (i: number) => void;
  setSpeed: (s: number) => void;
  toggle: () => void;
  onKeyDown: (e: RKeyboardEvent<HTMLElement>) => void;
  isFs: boolean;
  toggleFs: () => void;
}

export function useStepPlayer(count: number, rootRef: RefObject<HTMLElement>, opts: { baseMs?: number; autoPlay?: boolean } = {}): StepPlayer {
  const base = opts.baseMs ?? 1100;
  const reduced = usePrefersReducedMotion();
  const [idx, setIdxRaw] = useState(0);
  const [playing, setPlaying] = useState(!!opts.autoPlay && count > 1 && !reduced);
  const [speed, setSpeed] = useState(1);
  const [isFs, setIsFs] = useState(false);
  const setIdx = useCallback((i: number) => setIdxRaw(Math.max(0, Math.min(count - 1, i))), [count]);

  useEffect(() => {
    if (!playing) return;
    if (idx >= count - 1) {
      setPlaying(false);
      return;
    }
    const t = window.setTimeout(() => setIdxRaw((i) => Math.min(count - 1, i + 1)), base / speed);
    return () => window.clearTimeout(t);
  }, [playing, idx, count, speed, base]);

  useEffect(() => {
    const on = () => setIsFs(!!rootRef.current && document.fullscreenElement === rootRef.current);
    document.addEventListener('fullscreenchange', on);
    return () => document.removeEventListener('fullscreenchange', on);
  }, [rootRef]);

  const toggle = useCallback(() => {
    if (!playing && idx >= count - 1) setIdxRaw(0);
    setPlaying(!playing);
  }, [playing, idx, count]);

  const toggleFs = useCallback(() => {
    const el = rootRef.current;
    if (!el) return;
    if (document.fullscreenElement) void document.exitFullscreen();
    else void el.requestFullscreen?.().catch(() => undefined);
  }, [rootRef]);

  const onKeyDown = (e: RKeyboardEvent<HTMLElement>) => {
    const tag = (e.target as HTMLElement).tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
    if (e.key === ' ' || e.key === 'k') {
      e.preventDefault();
      toggle();
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      setPlaying(false);
      setIdx(idx + 1);
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      setPlaying(false);
      setIdx(idx - 1);
    } else if (e.key === 'Home') setIdx(0);
    else if (e.key === 'End') setIdx(count - 1);
    else if (e.key === 'f' || e.key === 'F') {
      e.preventDefault();
      e.stopPropagation();
      toggleFs();
    }
  };

  return { idx, count, playing, speed, setIdx: (i) => (setPlaying(false), setIdx(i)), setSpeed, toggle, onKeyDown, isFs, toggleFs };
}

export function StepControls({ p, tones, onFullscreen }: { p: StepPlayer; tones?: Array<'good' | 'bad' | 'warn' | null>; onFullscreen?: () => void }) {
  const pct = p.count > 1 ? (p.idx / (p.count - 1)) * 100 : 100;
  return (
    <div className="replay-controls vz-steps">
      <button type="button" className="btn icon sm" onClick={() => p.setIdx(p.idx - 1)} aria-label="Previous step" disabled={p.idx === 0}>
        <Icon.StepBack />
      </button>
      <button type="button" className="btn icon primary play-btn" onClick={p.toggle} aria-label={p.playing ? 'Pause' : 'Play'} disabled={p.count < 2}>
        {p.playing ? <Icon.Pause /> : <Icon.Play />}
      </button>
      <button type="button" className="btn icon sm" onClick={() => p.setIdx(p.idx + 1)} aria-label="Next step" disabled={p.idx >= p.count - 1}>
        <Icon.StepFwd />
      </button>
      <div className="scrub">
        {tones && (
          <div className="scrub-ticks" aria-hidden="true">
            {tones.map((t, i) => (
              <i key={i} className={t ? `t-${t}` : undefined} style={{ left: `${p.count > 1 ? (i / (p.count - 1)) * 100 : 0}%` }} />
            ))}
          </div>
        )}
        <input type="range" min={0} max={Math.max(0, p.count - 1)} value={p.idx} onChange={(e) => p.setIdx(Number(e.target.value))} aria-label="Scrub steps" style={{ ['--pct' as string]: `${pct}%` }} />
      </div>
      <span className="vz-step-count tnum">
        {p.idx}/{p.count - 1}
      </span>
      <select className="select sm" style={{ width: 84 }} value={p.speed} onChange={(e) => p.setSpeed(Number(e.target.value))} aria-label="Playback speed">
        {SPEEDS.map((s) => (
          <option key={s} value={s}>
            {s}×
          </option>
        ))}
      </select>
      {onFullscreen && (
        <button type="button" className="btn icon sm" onClick={onFullscreen} aria-label={p.isFs ? 'Exit full screen' : 'Full screen'} title="Full screen (F)">
          <Icon.Maximize />
        </button>
      )}
    </div>
  );
}
