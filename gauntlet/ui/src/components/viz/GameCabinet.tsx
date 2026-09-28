/**
 * An arcade cabinet drawn in CSS: a glowing marquee with the genre, a bezel whose screen plays the recorded
 * playtest screenshots in order (a flip-book of what the scripted player saw), a control deck and a score plate.
 * Broken games get a plain stamp over the screen ("FROZE", "RAN OUT OF SPACE"…). Only recorded screenshots are
 * shown; with prefers-reduced-motion the screen holds one frame.
 */
import { useEffect, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../ui.tsx';
import { STATE_WORDS, type JamEntry } from './gameJamModel.ts';
import './game-jam.css';

function useReducedMotion(): boolean {
  const [r, setR] = useState(() => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches);
  useEffect(() => {
    if (typeof matchMedia !== 'function') return;
    const m = matchMedia('(prefers-reduced-motion: reduce)');
    const on = () => setR(m.matches);
    m.addEventListener?.('change', on);
    return () => m.removeEventListener?.('change', on);
  }, []);
  return r;
}

/** Index of the frame to show: cycles through the filmstrip, or holds the most telling one. */
export function useFlipbook(n: number, playing: boolean, ms = 1100, hold = 3): number {
  const reduced = useReducedMotion();
  const [i, setI] = useState(Math.min(hold, Math.max(0, n - 1)));
  useEffect(() => {
    if (!playing || reduced || n < 2) {
      setI(Math.min(hold, Math.max(0, n - 1)));
      return;
    }
    const t = window.setInterval(() => setI((x) => (x + 1) % n), ms);
    return () => window.clearInterval(t);
  }, [n, playing, reduced, ms, hold]);
  return i;
}

export function GameCabinet({
  entry,
  urlFor,
  marquee,
  model,
  color,
  size = 'md',
  winner,
  playing = true,
  footer,
  onOpen,
  delayMs = 0,
}: {
  entry: JamEntry | null;
  urlFor: (file: string) => string;
  /** Big glowing word on top, e.g. "FLAP". */
  marquee: string;
  /** Name under the marquee (the model, or the genre on a model's shelf). */
  model: ReactNode;
  color: string;
  size?: 'sm' | 'md' | 'lg';
  winner?: boolean;
  playing?: boolean;
  footer?: ReactNode;
  onOpen?: () => void;
  delayMs?: number;
}) {
  const frames = entry?.frames ?? [];
  const i = useFlipbook(frames.length, playing, 1100 + (delayMs % 400));
  const f = frames[i];
  const state = entry?.state ?? 'no-game';
  const stamp = entry ? STATE_WORDS[state].stamp : 'NO ENTRY';
  const score = entry?.score;
  const tone = score === null || score === undefined ? 'none' : score >= 0.7 ? 'good' : score >= 0.4 ? 'warn' : 'bad';
  const Tag = onOpen ? 'button' : 'div';
  return (
    <Tag
      type={onOpen ? 'button' : undefined}
      className={cx('jam-cab', `jam-cab-${size}`, winner && 'is-winner', `st-${state}`, onOpen && 'is-clickable')}
      style={{ ['--cab' as string]: color, animationDelay: `${delayMs}ms` } as CSSProperties}
      onClick={onOpen}
      aria-label={onOpen ? `Open ${typeof model === 'string' ? model : 'this'} game` : undefined}
    >
      {winner && (
        <span className="jam-crown" aria-label="Best in this genre">
          <svg viewBox="0 0 24 16" aria-hidden="true">
            <path d="M2 14 L4 4 L9 9 L12 2 L15 9 L20 4 L22 14 Z" />
          </svg>
        </span>
      )}
      <span className="jam-cab-marquee">
        <b>{marquee}</b>
      </span>
      <span className="jam-cab-bezel">
        <span className="jam-cab-screen">
          {f ? <img key={f.art.file} src={urlFor(f.art.file)} alt={`Playtest screenshot at ${f.label}`} /> : <span className="jam-static" aria-hidden="true" />}
          <span className="jam-scan" aria-hidden="true" />
          {stamp && <span className="jam-stamp">{stamp}</span>}
          {f && (
            <span className="jam-clock tnum" aria-hidden="true">
              {f.label.replace('Before any input · ', '')}
            </span>
          )}
          {frames.length > 1 && (
            <span className="jam-dots" aria-hidden="true">
              {frames.map((x, k) => (
                <i key={x.art.file} className={cx(k === i && 'on')} />
              ))}
            </span>
          )}
        </span>
      </span>
      <span className="jam-cab-deck" aria-hidden="true">
        <span className="jam-stick" />
        <span className="jam-btns">
          <i />
          <i />
          <i />
        </span>
      </span>
      <span className="jam-cab-plate">
        <span className="jam-cab-name">{model}</span>
        <span className={cx('jam-cab-score tnum', `t-${tone}`)}>{score === null || score === undefined ? '—' : Math.round(score * 100)}</span>
      </span>
      {footer && <span className="jam-cab-foot">{footer}</span>}
    </Tag>
  );
}
