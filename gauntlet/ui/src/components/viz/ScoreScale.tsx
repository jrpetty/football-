/**
 * "What does this score mean?" — a 0–100 bar with markers for the model's score,
 * random guessing (only when the run includes the Random Baseline) and other
 * recorded reference points, plus the test's own plain-English "good score" line.
 *
 * Values are 0..1 (as stored in results). Only recorded numbers go in: a marker
 * whose value is missing is simply not drawn.
 */
import { cx } from '../ui.tsx';
import '../../styles/explain.css';

export interface ScoreMarker {
  /** 0..1 */
  value: number;
  label: string;
  /** model: a model's score (pin above the bar) · random: random guessing · average / best: reference ticks under the bar. */
  kind: 'model' | 'random' | 'average' | 'best';
  color?: string;
}

const pct = (v: number) => Math.max(0, Math.min(100, v * 100));

export function ScoreScale({
  markers,
  goodScore,
  size = 'md',
  className,
  title = 'What the score means',
}: {
  markers: ScoreMarker[];
  goodScore?: string;
  size?: 'md' | 'slide';
  className?: string;
  title?: string | null;
}) {
  const shown = markers.filter((m) => Number.isFinite(m.value));
  const pins = shown.filter((m) => m.kind === 'model');
  // Reference ticks under the bar: alternate rows when two labels would collide.
  const ticks = shown.filter((m) => m.kind !== 'model').sort((a, b) => a.value - b.value);
  const rowOf: number[] = [];
  ticks.forEach((m, i) => {
    const prev = i > 0 ? ticks[i - 1]! : null;
    rowOf.push(prev && pct(m.value) - pct(prev.value) < 22 ? (rowOf[i - 1]! + 1) % 2 : 0);
  });
  const single = pins.length === 1 ? pins[0]! : null;
  const edge = (v: number) => (pct(v) < 12 ? 'left' : pct(v) > 88 ? 'right' : 'mid');
  return (
    <div className={cx('sscale', `sscale-${size}`, className)} role="group" aria-label={title ?? 'Score scale'}>
      {title && <div className="ss-title">{title}</div>}
      <div className={cx('ss-bar-wrap', pins.length > 0 && 'has-pins', ticks.length > 0 && 'has-ticks', rowOf.includes(1) && 'two-rows')}>
        {pins.map((m) => (
          <div key={`p-${m.label}`} className={cx('ss-pin', `at-${edge(m.value)}`)} style={{ left: `${pct(m.value)}%`, ['--mc' as string]: m.color ?? 'var(--accent)' }}>
            <span className="ss-pin-lbl">
              <b className="tnum">{Math.round(pct(m.value))}</b> {m.label}
            </span>
            <i aria-hidden="true" />
          </div>
        ))}
        <div className="ss-track" aria-hidden="true">
          {single && <div className="ss-fill" style={{ width: `${pct(single.value)}%`, ['--mc' as string]: single.color ?? 'var(--accent)' }} />}
          {[25, 50, 75].map((g) => (
            <span key={g} className="ss-grid" style={{ left: `${g}%` }} />
          ))}
        </div>
        {ticks.map((m, i) => (
          <div key={`t-${m.kind}-${m.label}`} className={cx('ss-tick', `k-${m.kind}`, `row-${rowOf[i]}`, `at-${edge(m.value)}`)} style={{ left: `${pct(m.value)}%` }}>
            <i aria-hidden="true" />
            <span>
              {m.label} <b className="tnum">{Math.round(pct(m.value))}</b>
            </span>
          </div>
        ))}
        <div className="ss-axis" aria-hidden="true">
          {[0, 50, 100].map((a) => (
            // An axis number right under a marker would collide with it: the marker's own label says the value.
            <span key={a} style={{ visibility: shown.some((m) => Math.abs(pct(m.value) - a) < 6) ? 'hidden' : undefined }}>
              {a}
            </span>
          ))}
        </div>
      </div>
      {goodScore && <p className="ss-good">{goodScore}</p>}
    </div>
  );
}
