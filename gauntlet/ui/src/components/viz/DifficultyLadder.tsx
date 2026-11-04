/**
 * The difficulty ladder: one picture for every ladder test (Horizon, Frontier Ladders, Business Ladders).
 *
 * Ten rungs, bottom (L1) to top (L10). Each model gets a lane: the rungs it climbed are filled in its colour
 * up to its HEIGHT (every level up to it passed reliably; src/core/ladders.ts), with a marker on the top one.
 * Every rung shows one chip per attempt (✓ passed · ✗ failed · ◐ some points, not enough). A pass higher up
 * that does not count is a dashed "once" ghost. The axis names each rung's growth parameter (from
 * config/ladders.json) and the calibration band it was built for. The header row puts the heights side by side
 * and the footer says the comparison in plain English.
 *
 * Modes: "card" (pages and the inspector), "slide" (the Presenter: big type, fills the stage). Phone: the lanes
 * scroll sideways inside the ladder with the axis pinned; the page itself never scrolls sideways.
 */
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../ui.tsx';
import { heightsNote, type LadderHeight, type RungResult } from '../../../../src/core/ladders.ts';
import { levelBand, passMarkText } from '../../ladders.ts';
import './difficulty-ladder.css';

export interface LadderClimber {
  id: string;
  label: string;
  color: string;
  /** The Random Baseline: drawn, never compared. */
  baseline?: boolean;
  climb: LadderHeight;
}

export type DifficultyLadderMode = 'card' | 'slide';

export function initials(label: string): string {
  const words = label.replace(/[()]/g, ' ').split(/[\s-]+/).filter(Boolean);
  const pick = words.find((w) => /\d/.test(w));
  const first = words[0]?.[0]?.toUpperCase() ?? '?';
  return pick ? `${first}${pick.replace(/[^\d.]/g, '').slice(0, 3)}` : words.slice(0, 2).map((w) => w[0]!.toUpperCase()).join('');
}

const CHIP: Record<'pass' | 'fail' | 'part', { glyph: string; word: string }> = {
  pass: { glyph: '✓', word: 'passed' },
  fail: { glyph: '✕', word: 'failed' },
  part: { glyph: '◐', word: 'some points, not enough to pass' },
};

function chipKind(r: RungResult, i: number): 'pass' | 'fail' | 'part' {
  if (r.passed[i]) return 'pass';
  return (r.scores[i] ?? 0) > 0 ? 'part' : 'fail';
}

function AttemptChips({ r, max }: { r: RungResult; max: number }) {
  if (!r.attempts) return <span className="dl-none" aria-label="not run" />;
  const shown = r.scores.slice(0, max);
  return (
    <span className="dl-chips" aria-label={`${r.passes} of ${r.attempts} attempt${r.attempts === 1 ? '' : 's'} passed`}>
      {shown.map((s, i) => {
        const k = chipKind(r, i);
        return (
          <i key={i} className={cx('dl-chip', `k-${k}`)} title={`Attempt ${i + 1}: ${Math.round(s * 100)} · ${CHIP[k].word}`}>
            {CHIP[k].glyph}
          </i>
        );
      })}
      {r.attempts > max && <small className="dl-more">+{r.attempts - max}</small>}
    </span>
  );
}

function HeightTags({ c, levels }: { c: LadderClimber; levels: number }) {
  const h = c.climb;
  const tags: ReactNode[] = [];
  if (h.levelsRun === 0) tags.push(<span key="nr">not run</span>);
  if (h.onceSolved.length) tags.push(<span key="once" className="t-once">once solved L{h.onceSolved[h.onceSolved.length - 1]}</span>);
  if (h.borderline) tags.push(<span key="b" className="t-warn" title="The height rests on a rung passed by some attempts only, or within 5 points of the pass mark">borderline</span>);
  else if (h.justShort && h.height < levels) tags.push(<span key="j" className="t-warn" title="The next rung up was nearly passed">just short of L{h.height + 1}</span>);
  return tags.length ? <span className="dl-tags">{tags}</span> : null;
}

export function DifficultyLadder({
  climbers,
  levels: given,
  rungs = [],
  title,
  grows,
  passMark = 1,
  mode = 'card',
  highlight,
  comingSoon,
  note: noteGiven,
  eyebrow = 'Difficulty ladder',
}: {
  climbers: LadderClimber[];
  levels?: number;
  /** The growth parameter of each rung (index 0 = level 1), e.g. "3×3 · 20 moves". */
  rungs?: Array<string | null | undefined>;
  title?: string;
  /** One line: what grows from rung to rung. */
  grows?: string;
  passMark?: number;
  mode?: DifficultyLadderMode;
  /** A level to highlight (the case open in the inspector). */
  highlight?: number;
  /** A planned test: the rungs are drawn, the lanes say "coming soon". */
  comingSoon?: boolean;
  /** Replace the plain-English comparison (null hides it). */
  note?: string | null;
  eyebrow?: string;
}) {
  const levels = Math.max(given ?? 10, rungs.length, ...climbers.map((c) => c.climb.levels));
  const lanes = comingSoon ? [] : climbers;
  const real = lanes.filter((c) => !c.baseline);
  const topHeight = Math.max(0, ...real.map((c) => c.climb.height));
  const note = noteGiven !== undefined ? noteGiven : heightsNote(lanes.map((c) => ({ label: c.label, height: c.climb.height, borderline: c.climb.borderline, justShort: c.climb.justShort, baseline: c.baseline })));
  const maxChips = Math.max(1, ...lanes.flatMap((c) => c.climb.rungs.map((r) => r.attempts)));
  const chipCap = mode === 'slide' ? 3 : 5;
  const order = Array.from({ length: levels }, (_, i) => levels - i);
  const bands: Array<{ id: string; label: string; short: string; top: number; bottom: number }> = [];
  for (const level of order) {
    const b = levelBand(level);
    const last = bands[bands.length - 1];
    if (last && last.id === b.id) last.bottom = level;
    else bands.push({ ...b, top: level, bottom: level });
  }
  const style = { ['--lanes' as string]: Math.max(1, lanes.length), ['--levels' as string]: levels, ['--chips' as string]: Math.min(maxChips, chipCap) } as CSSProperties;
  const label = `${title ?? 'Difficulty ladder'}: ${comingSoon ? 'coming soon' : real.map((c) => `${c.label} reached level ${c.climb.height} of ${levels}`).join('; ') || 'no results yet'}`;

  return (
    <figure className={cx('dl', `dl-${mode}`, comingSoon && 'dl-soon')} style={style} aria-label={label}>
      {(title || grows) && (
        <figcaption className="dl-head">
          <div className="dl-titles">
            <span className="dl-eyebrow">{eyebrow}</span>
            {title && <h3>{title}</h3>}
            {grows && <p>{grows}</p>}
          </div>
          <span className="dl-pass">{passMarkText(passMark)}</span>
        </figcaption>
      )}
      <div className="dl-scroll">
        <div className="dl-grid" role="table" aria-label={label}>
          {/* The calibration bands down the left edge: what each stretch of the ladder was built for. */}
          <span className="dl-bandcol dl-corner" style={{ gridRow: 1 }} aria-hidden="true" />
          {bands.map((b) => (
            <span key={b.id} className={cx('dl-bandcol', `b-${b.id}`)} style={{ gridRow: `${levels - b.top + 2} / ${levels - b.bottom + 3}` }} title={`${b.label}: levels ${b.bottom}–${b.top}`}>
              <span>{b.short}</span>
            </span>
          ))}
          <span className="dl-bandcol dl-corner" style={{ gridRow: levels + 2 }} aria-hidden="true" />
          <div className="dl-row dl-names" role="row">
            <div className="dl-axis dl-axis-h" role="columnheader">
              Level
            </div>
            {comingSoon ? (
              <div className="dl-lane-h dl-soon-h" role="columnheader">
                <b>Coming soon</b>
                <small>This ladder is being built. Heights appear here once models have climbed it.</small>
              </div>
            ) : lanes.length === 0 ? (
              <div className="dl-lane-h dl-soon-h" role="columnheader">
                <b>No results yet</b>
                <small>Run this test to see how high each model climbs.</small>
              </div>
            ) : (
              lanes.map((c) => (
                <div key={c.id} className={cx('dl-lane-h', c.baseline && 'baseline', !c.baseline && c.climb.height === topHeight && topHeight > 0 && 'lead')} role="columnheader" style={{ ['--c' as string]: c.color } as CSSProperties}>
                  <span className="dl-who">
                    <b title={c.label}>
                      <i className="dl-sw" aria-hidden="true" />
                      {c.baseline ? 'Random guessing' : c.label}
                    </b>
                  </span>
                  <span className="dl-h">
                    <b className="tnum">L{c.climb.height}</b>
                    <small>of {levels}</small>
                  </span>
                  <HeightTags c={c} levels={levels} />
                </div>
              ))
            )}
          </div>
          {order.map((level) => {
            const band = levelBand(level);
            return (
              <div key={level} className={cx('dl-row', 'dl-rung', highlight === level && 'hl', `b-${band.id}`)} role="row" style={{ ['--i' as string]: level } as CSSProperties}>
                <div className="dl-axis" role="rowheader">
                  <span className="dl-lv">
                    <b>L{level}</b>
                    {rungs[level - 1] && <small>{rungs[level - 1]}</small>}
                  </span>
                </div>
                {comingSoon || lanes.length === 0 ? (
                  <div className="dl-cell dl-empty" role="cell" />
                ) : (
                  lanes.map((c) => {
                    const r = c.climb.rungs[level - 1];
                    const climbed = level <= c.climb.height;
                    const top = level === c.climb.height;
                    const ghost = c.climb.onceSolved.includes(level);
                    return (
                      <div
                        key={c.id}
                        role="cell"
                        className={cx('dl-cell', climbed && 'climbed', top && 'top', ghost && 'ghost', c.baseline && 'baseline', r && r.attempts === 0 && 'not-run')}
                        style={{ ['--c' as string]: c.color } as CSSProperties}
                        aria-label={`${c.label}, level ${level}: ${!r || r.attempts === 0 ? 'not run' : `${r.passes} of ${r.attempts} passed`}${top ? ' (its height)' : ghost ? ' (once solved, above its height)' : ''}`}
                      >
                        {r && <AttemptChips r={r} max={chipCap} />}
                        {top && !c.baseline && (
                          <span className="dl-flag" aria-hidden="true">
                            {initials(c.label)}
                          </span>
                        )}
                        {ghost && (
                          <span className="dl-ghost" aria-hidden="true">
                            once
                          </span>
                        )}
                      </div>
                    );
                  })
                )}
              </div>
            );
          })}
          <div className="dl-row dl-ground" role="row">
            <div className="dl-axis" role="rowheader">
              <span className="dl-lv">
                <b>Start</b>
              </span>
            </div>
            {comingSoon || lanes.length === 0 ? (
              <div className="dl-cell dl-empty" role="cell" />
            ) : (
              lanes.map((c) => (
                <div key={c.id} role="cell" className={cx('dl-cell', 'dl-base', c.climb.height === 0 && 'stuck')} style={{ ['--c' as string]: c.color } as CSSProperties}>
                  {c.climb.height === 0 && c.climb.levelsRun > 0 && !c.baseline && <span className="dl-flag low">{initials(c.label)}</span>}
                </div>
              ))
            )}
          </div>
        </div>
      </div>
      {!comingSoon && lanes.length > 0 && (
        <footer className="dl-foot">
          {note && <p className="dl-note">{note}</p>}
          <ul className="dl-legend" aria-label="Key">
            <li>
              <i className="dl-chip k-pass">✓</i> passed
            </li>
            <li>
              <i className="dl-chip k-part">◐</i> some points, not enough
            </li>
            <li>
              <i className="dl-chip k-fail">✕</i> failed
            </li>
            <li>
              <i className="dl-key-climb" /> climbed (every rung below passed reliably)
            </li>
            <li>
              <i className="dl-key-ghost" /> passed once, above its height (does not count)
            </li>
          </ul>
        </footer>
      )}
    </figure>
  );
}

/**
 * A ten-segment meter of one height (for tables): filled to the height, a hollow tick for a "once solved" rung.
 * `height` may be a mean (e.g. 4.2): the last segment fills partly.
 */
export function RungMeter({ height, levels = 10, onceSolved = [], color, label }: { height: number | null; levels?: number; onceSolved?: number[]; color?: string; label?: string }) {
  return (
    <span className="dl-meter" style={{ ['--c' as string]: color ?? 'var(--accent)' } as CSSProperties} role="img" aria-label={label ?? (height === null ? 'not run' : `height ${height} of ${levels}`)}>
      {Array.from({ length: levels }, (_, i) => {
        const fill = height === null ? 0 : Math.max(0, Math.min(1, height - i));
        return <i key={i} className={cx(fill >= 1 && 'on', fill > 0 && fill < 1 && 'part', onceSolved.includes(i + 1) && 'once')} style={fill > 0 && fill < 1 ? ({ ['--f' as string]: `${Math.round(fill * 100)}%` } as CSSProperties) : undefined} />;
      })}
    </span>
  );
}
