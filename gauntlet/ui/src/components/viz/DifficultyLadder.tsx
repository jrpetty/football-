/**
 * The difficulty ladder: one view for every ladder test (Horizon, Frontier Ladders, Business Ladders).
 *
 * It leads with a picture, the climb (ClimbScene.tsx): ten ledges up a mountain, every model a token standing on
 * the ledge of its HEIGHT (every level up to it passed reliably; src/core/ladders.ts), the wall nobody climbed past,
 * and what the blind test measured as three climbers (small, mid, top model). One sentence above it says the
 * comparison in plain English. The precise record, every attempt on every rung for every model, is one click away:
 * "Every attempt", or a model's token for that model alone.
 *
 * Modes: "card" (pages and the inspector), "slide" (the Presenter: big type, fills the stage, the climb only).
 */
import { useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Seg, cx } from '../ui.tsx';
import {
  calibrationClimbText,
  calibrationSummitText,
  heightsNote,
  rungSizes,
  type LadderCalibration,
  type LadderHeight,
  type RungResult,
} from '../../../../src/core/ladders.ts';
import { passMarkText } from '../../ladders.ts';
import { ClimbScene, tokenInitials } from './ClimbScene.tsx';
import { LevelCloseUp } from './LevelCloseUp.tsx';
import './difficulty-ladder.css';

export { initials } from './ClimbScene.tsx';

export interface LadderClimber {
  id: string;
  label: string;
  color: string;
  /** The Random Baseline: drawn, never compared. */
  baseline?: boolean;
  climb: LadderHeight;
}

export type DifficultyLadderMode = 'card' | 'slide';

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

function ChipKey() {
  return (
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
        <i className="dl-key-climb" /> climbed (every level below passed reliably)
      </li>
      <li>
        <i className="dl-key-ghost" /> passed once, above its height (does not count)
      </li>
    </ul>
  );
}

function HeightTags({ c, levels }: { c: LadderClimber; levels: number }) {
  const h = c.climb;
  const tags = [];
  if (h.levelsRun === 0) tags.push(<span key="nr">not run</span>);
  if (h.onceSolved.length) tags.push(<span key="once" className="t-once">once solved L{h.onceSolved[h.onceSolved.length - 1]}</span>);
  if (h.borderline) tags.push(<span key="b" className="t-warn" title="The height rests on a rung passed by some attempts only, or within 5 points of the pass mark">borderline</span>);
  else if (h.justShort && h.height < levels) tags.push(<span key="j" className="t-warn" title="The next rung up was nearly passed">just short of L{h.height + 1}</span>);
  return tags.length ? <span className="dl-tags">{tags}</span> : null;
}

/** Every attempt on every rung, one lane per model (the precise view behind "Every attempt"). */
function AttemptGrid({ lanes, levels, rungs, highlight, onOpen }: { lanes: LadderClimber[]; levels: number; rungs: Array<string | null | undefined>; highlight?: number; onOpen?: (level: number, contestantId?: string) => void }) {
  const real = lanes.filter((c) => !c.baseline);
  const topHeight = Math.max(0, ...real.map((c) => c.climb.height));
  const maxChips = Math.max(1, ...lanes.flatMap((c) => c.climb.rungs.map((r) => r.attempts)));
  const chipCap = 5;
  const order = Array.from({ length: levels }, (_, i) => levels - i);
  const tokens = tokenInitials(lanes.map((c) => c.label));
  const style = { ['--lanes' as string]: Math.max(1, lanes.length), ['--chips' as string]: Math.min(maxChips, chipCap) } as CSSProperties;
  return (
    <div className="dl-scroll">
      <div className="dl-grid" role="table" aria-label="Every attempt on every level" style={style}>
        <div className="dl-row dl-names" role="row">
          <div className="dl-axis dl-axis-h" role="columnheader">
            Level
          </div>
          {lanes.map((c) => (
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
          ))}
        </div>
        {order.map((level) => (
          <div key={level} className={cx('dl-row', 'dl-rung', highlight === level && 'hl')} role="row">
            <div className="dl-axis" role="rowheader">
              {onOpen ? (
                <button type="button" className="dl-lv dl-lv-btn" onClick={() => onOpen(level)} aria-label={`Level ${level}${rungs[level - 1] ? ` (${rungs[level - 1]})` : ''}: see the question and how each model answered`}>
                  <b>L{level}</b>
                  {rungs[level - 1] && <small>{rungs[level - 1]}</small>}
                </button>
              ) : (
                <span className="dl-lv">
                  <b>L{level}</b>
                  {rungs[level - 1] && <small>{rungs[level - 1]}</small>}
                </span>
              )}
            </div>
            {lanes.map((c, i) => {
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
                  {onOpen && r && r.attempts > 0 && (
                    <button type="button" className="dl-cell-btn" onClick={() => onOpen(level, c.id)} aria-label={`${c.baseline ? 'Random guessing' : c.label}, level ${level}: see its answer`} title="See the question and this answer" />
                  )}
                  {top && !c.baseline && (
                    <span className="dl-flag" aria-hidden="true">
                      {tokens[i]}
                    </span>
                  )}
                  {ghost && (
                    <span className="dl-ghost" aria-hidden="true">
                      once
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

/** One model's attempts, level by level (opened by clicking its token). */
function ModelAttempts({ c, levels, rungs, onClose, id, onOpen }: { c: LadderClimber; levels: number; rungs: Array<string | null | undefined>; onClose: () => void; id: string; onOpen?: (level: number, contestantId?: string) => void }) {
  const h = c.climb;
  return (
    <section className="dl-model" id={id} style={{ ['--c' as string]: c.color } as CSSProperties} aria-label={`${c.label}: every attempt`}>
      <header>
        <i className="dl-sw" aria-hidden="true" />
        <b>{c.baseline ? 'Random guessing' : c.label}</b>
        <span className="dl-model-h">
          height <b>L{h.height}</b> of {levels}
        </span>
        <HeightTags c={c} levels={levels} />
        <button type="button" className="btn ghost sm dl-model-x" onClick={onClose} aria-label={`Close ${c.label}'s attempts`}>
          Close
        </button>
      </header>
      <ol className="dl-model-rungs">
        {Array.from({ length: levels }, (_, i) => i + 1).map((lv) => {
          const r = h.rungs[lv - 1];
          const body = (
            <>
              <b>L{lv}</b>
              {r ? <AttemptChips r={r} max={5} /> : <span className="dl-none" aria-label="not run" />}
              <small>{!r || !r.attempts ? 'not run' : `${r.passes}/${r.attempts}`}</small>
            </>
          );
          return (
            <li key={lv} className={cx(lv <= h.height && 'climbed', lv === h.height && 'top', h.onceSolved.includes(lv) && 'ghost')} title={rungs[lv - 1] ?? undefined}>
              {onOpen ? (
                <button type="button" onClick={() => onOpen(lv, c.id)} aria-label={`Level ${lv}${rungs[lv - 1] ? ` (${rungs[lv - 1]})` : ''}: see ${c.baseline ? 'random guessing' : c.label}'s answer`}>
                  {body}
                </button>
              ) : (
                body
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

/** Under the climb: the blind test in one line, the summit flag for a ladder today's top model cleared, the method. */
function BlindTestNote({ calibration, mode, method }: { calibration?: LadderCalibration | null; mode: DifficultyLadderMode; method?: string }) {
  if (!calibration)
    return (
      <p className="dl-bt none">
        <span className="dl-bt-ico" aria-hidden="true">
          ?
        </span>
        <span>
          <b>Not blind-tested yet.</b> No model of each size has been tried on this ladder yet.
        </span>
      </p>
    );
  const summit = calibrationSummitText(calibration);
  return (
    <div className="dl-bt" title={method}>
      <span className="dl-bt-ico" aria-hidden="true">
        <svg viewBox="0 0 20 26">
          <circle cx="10" cy="5.5" r="4.5" />
          <path d="M2.5 25v-6.5C2.5 14 6 11.5 10 11.5s7.5 2.5 7.5 7V25z" />
        </svg>
      </span>
      <p>
        <b>{calibrationClimbText(calibration)}.</b>
        {summit && <span className="dl-bt-summit"> ⚑ {summit.charAt(0).toUpperCase() + summit.slice(1)}.</span>}{' '}
        {mode === 'card' && <small>One model of each size, one attempt per level: a check of the ladder, not a result.{calibration.note ? ` ${calibration.note}` : ''}</small>}
      </p>
    </div>
  );
}

/** The few marks the climb uses, next to what they mean (only the ones that appear). */
function SceneKey({ lanes, levels }: { lanes: LadderClimber[]; levels: number }) {
  const real = lanes.filter((c) => !c.baseline);
  const once = real.some((c) => c.climb.onceSolved.length);
  const border = real.some((c) => c.climb.borderline);
  const near = real.some((c) => !c.climb.borderline && c.climb.justShort && c.climb.height < levels);
  const top = Math.max(0, ...real.map((c) => c.climb.height));
  return (
    <ul className="dl-skey" aria-label="Key">
      <li>
        <i className="k-token" /> a model, on the highest level it passed reliably with every level below it
      </li>
      {real.length > 0 && top < levels && (
        <li>
          <i className="k-crack" /> the wall: nobody climbed to it
        </li>
      )}
      {once && (
        <li>
          <i className="k-ghost" /> passed once higher up (does not count)
        </li>
      )}
      {border && (
        <li>
          <i className="k-badge warn">~</i> borderline
        </li>
      )}
      {near && (
        <li>
          <i className="k-badge near">↑</i> nearly passed the next level
        </li>
      )}
    </ul>
  );
}

/** The heights as a plain table, for screen readers (the climb is a picture). */
function HeightsTable({ lanes, levels }: { lanes: LadderClimber[]; levels: number }) {
  return (
    <div className="sr-only">
    <table>
      <caption>Height reached by each model (of {levels} levels)</caption>
      <thead>
        <tr>
          <th scope="col">Model</th>
          <th scope="col">Height</th>
          <th scope="col">Notes</th>
        </tr>
      </thead>
      <tbody>
        {lanes.map((c) => {
          const h = c.climb;
          const notes = [h.levelsRun === 0 ? 'not run' : '', h.onceSolved.length ? `passed level ${h.onceSolved.join(', ')} once, above its height` : '', h.borderline ? 'borderline' : '', !h.borderline && h.justShort && h.height < levels ? `just short of level ${h.height + 1}` : ''].filter(Boolean);
          return (
            <tr key={c.id}>
              <th scope="row">{c.baseline ? 'Random guessing' : c.label}</th>
              <td>Level {h.height}</td>
              <td>{notes.join('; ')}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
    </div>
  );
}

/**
 * A side sheet over the page (full screen on a phone) for one level up close: Esc or the backdrop closes it, Tab
 * stays inside it, and focus goes back to what opened it.
 */
function CloseUpSheet({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const prev = document.activeElement as HTMLElement | null;
    const focusables = () =>
      [...el.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), select, textarea, summary, [tabindex]:not([tabindex="-1"])')].filter((x) => x.getClientRects().length > 0);
    (focusables()[0] ?? el).focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        close.current();
        return;
      }
      if (e.key !== 'Tab') return;
      const f = focusables();
      if (!f.length) {
        e.preventDefault();
        el.focus();
        return;
      }
      const first = f[0]!;
      const last = f[f.length - 1]!;
      const at = document.activeElement;
      if (e.shiftKey && (at === first || !el.contains(at))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (at === last || !el.contains(at))) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey, true);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey, true);
      document.body.style.overflow = overflow;
      prev?.focus?.({ preventScroll: true });
    };
  }, []);
  return createPortal(
    <div className="dl-sheet-root" onMouseDown={(e) => e.target === e.currentTarget && close.current()}>
      <div ref={ref} className="dl-sheet" role="dialog" aria-modal="true" aria-label={label} tabIndex={-1}>
        {children}
      </div>
    </div>,
    document.body,
  );
}

export function DifficultyLadder({
  testId,
  climbers,
  levels: given,
  rungs = [],
  sizes: sizesGiven,
  title,
  grows,
  passMark = 1,
  mode = 'card',
  highlight,
  comingSoon,
  note: noteGiven,
  eyebrow = 'Difficulty ladder',
  calibration,
  verdict,
  calibrationMethod,
  demo,
  runId,
  scope,
}: {
  /** The ladder test (for the drawing of each level, and each level up close). */
  testId?: string;
  /** The run the heights come from (each level up close shows its answers); null: every recorded run. */
  runId?: string | null;
  /** Which results the level close-up draws on (passed through to it). */
  scope?: string;
  /** What a blind test measured (config/ladders.json): drawn as three climbers beside the mountain. */
  calibration?: LadderCalibration | null;
  /** The one-line blind-test verdict, shown under the title. */
  verdict?: string | null;
  /** How the blind test was done (the blind-test line's tooltip). */
  calibrationMethod?: string;
  climbers: LadderClimber[];
  levels?: number;
  /** The growth parameter of each rung (index 0 = level 1), e.g. "3×3 · 20 moves". */
  rungs?: Array<string | null | undefined>;
  /** How big each level is (index 0 = level 1); default: read from the rung labels (none when they do not say). */
  sizes?: { values: number[]; unit: string } | null;
  title?: string;
  /** One line: what grows from rung to rung. */
  grows?: string;
  passMark?: number;
  mode?: DifficultyLadderMode;
  /** A level to highlight (the case open in the inspector). */
  highlight?: number;
  /** A planned test: the levels are drawn, the models are not ("coming soon"). */
  comingSoon?: boolean;
  /** Replace the plain-English comparison (null hides it). */
  note?: string | null;
  eyebrow?: string;
  /** Demo mode: say on the ladder that the heights are not real results. */
  demo?: boolean;
}) {
  const levels = Math.max(given ?? 10, rungs.length, ...climbers.map((c) => c.climb.levels));
  const lanes = comingSoon ? [] : climbers;
  const [view, setView] = useState<'climb' | 'attempts'>('climb');
  const [picked, setPicked] = useState<string | null>(null);
  const [closeUp, setCloseUp] = useState<{ level: number; contestantId?: string } | null>(null);
  const canOpen = mode === 'card' && !!testId && !comingSoon;
  const open = canOpen ? (level: number, contestantId?: string) => setCloseUp({ level, contestantId }) : undefined;
  const panelId = useId();
  const sizes = sizesGiven !== undefined ? sizesGiven : rungSizes(rungs.slice(0, levels));
  const note = noteGiven !== undefined ? noteGiven : heightsNote(lanes.map((c) => ({ label: c.label, height: c.climb.height, borderline: c.climb.borderline, justShort: c.climb.justShort, baseline: c.baseline })));
  const real = lanes.filter((c) => !c.baseline);
  const label = `${title ?? 'Difficulty ladder'}: ${comingSoon ? 'coming soon' : real.map((c) => `${c.label} reached level ${c.climb.height} of ${levels}`).join('; ') || 'no results yet'}`;
  const pickedClimber = lanes.find((c) => c.id === picked);
  const empty = comingSoon
    ? { title: 'Coming soon', text: 'This ladder is being built. Heights appear here once models have climbed it.' }
    : lanes.length === 0
      ? { title: 'No results yet', text: 'Run this test to see how high each model climbs.' }
      : null;
  const showAttempts = mode === 'card' && lanes.length > 0 && view === 'attempts';

  return (
    <figure className={cx('dl', `dl-${mode}`, comingSoon && 'dl-soon')} aria-label={label}>
      {(title || grows || verdict || demo) && (
        <figcaption className="dl-head">
          <div className="dl-titles">
            <span className="dl-eyebrow">{eyebrow}</span>
            {title && <h3>{title}</h3>}
            {grows && <p>{grows}</p>}
          </div>
          <span className="dl-head-side">
            {demo && <span className="dl-demo">Demo data — not real results</span>}
            <span className="dl-pass">{passMarkText(passMark)}</span>
          </span>
          {verdict && (
            <p className="dl-verdict">
              <span className="dl-verdict-ico" aria-hidden="true">
                ✓
              </span>
              {verdict}
            </p>
          )}
        </figcaption>
      )}
      {mode === 'card' && (note || lanes.length > 0) && (
        <div className="dl-lead">
          {note && <p className="dl-note">{note}</p>}
          {mode === 'card' && lanes.length > 0 && (
            <Seg
              small
              label="How to show the ladder"
              value={view}
              onChange={(v) => {
                setView(v);
                setPicked(null);
              }}
              options={[
                { value: 'climb', label: 'The climb' },
                { value: 'attempts', label: 'Every attempt', title: 'Every attempt on every level, for every model' },
              ]}
            />
          )}
        </div>
      )}
      {showAttempts ? (
        <>
          <AttemptGrid lanes={lanes} levels={levels} rungs={rungs} highlight={highlight} onOpen={open} />
          <ChipKey />
        </>
      ) : (
        <>
          <div className="dl-scene" role="group" aria-label={`The climb. ${label}`}>
            <ClimbScene
              testId={testId}
              climbers={lanes}
              levels={levels}
              rungs={rungs}
              sizes={sizes}
              calibration={calibration}
              mode={mode}
              highlight={highlight}
              empty={empty}
              picked={picked}
              onPick={
                mode === 'card'
                  ? (id) => {
                      // A token opens that model's climb: its answer on the level it stands on, and every attempt below the picture.
                      setPicked(id);
                      const c = lanes.find((x) => x.id === id);
                      if (c && open) open(Math.max(1, Math.min(levels, c.climb.height)), id);
                    }
                  : undefined
              }
              onLevel={open ? (lv) => open(lv) : undefined}
            />
            {lanes.length > 0 && <HeightsTable lanes={lanes} levels={levels} />}
          </div>
          {pickedClimber && <ModelAttempts id={panelId} c={pickedClimber} levels={levels} rungs={rungs} onClose={() => setPicked(null)} onOpen={open} />}
          <footer className="dl-foot">
            <BlindTestNote calibration={calibration} mode={mode} method={calibrationMethod} />
            {lanes.length > 0 && mode === 'card' && <SceneKey lanes={lanes} levels={levels} />}
            {mode === 'card' && (canOpen || lanes.length > 0) && (
              <p className="dl-tip">
                {canOpen ? 'Tap any level to see the question and how each model answered' : ''}
                {canOpen && lanes.length > 0 ? ' · tap a model to see its answer and every attempt it made' : lanes.length > 0 ? 'Tap a model to see every attempt it made' : ''}
              </p>
            )}
          </footer>
        </>
      )}
      {closeUp && testId && (
        <CloseUpSheet label={`Level ${closeUp.level} up close`} onClose={() => setCloseUp(null)}>
          <LevelCloseUp testId={testId} level={closeUp.level} contestantId={closeUp.contestantId} runId={runId ?? null} scope={scope} mode="panel" onClose={() => setCloseUp(null)} />
        </CloseUpSheet>
      )}
    </figure>
  );
}

/**
 * A small ten-step staircase of one height (for tables): the steps up to the height filled in the model's colour,
 * a dot for the model on its top step, a dashed step for a "once solved" level. `height` may be a mean (e.g. 4.2):
 * that step fills partly.
 */
export function RungMeter({ height, levels = 10, onceSolved = [], color, label, size = 'sm' }: { height: number | null; levels?: number; onceSolved?: number[]; color?: string; label?: string; size?: 'sm' | 'md' }) {
  const W = 7;
  const H = 26;
  const stepH = (i: number) => 4 + ((i + 1) / levels) * (H - 10);
  const whole = height === null ? 0 : Math.floor(height + 1e-9);
  return (
    <svg
      className={cx('dl-meter', size === 'md' && 'md')}
      viewBox={`0 0 ${levels * W} ${H}`}
      style={{ ['--c' as string]: color ?? 'var(--accent)' } as CSSProperties}
      role="img"
      aria-label={label ?? (height === null ? 'not run' : `height ${height} of ${levels}`)}
    >
      {Array.from({ length: levels }, (_, i) => {
        const h = stepH(i);
        const fill = height === null ? 0 : Math.max(0, Math.min(1, height - i));
        const once = onceSolved.includes(i + 1);
        return (
          <g key={i}>
            <rect className={cx('st', once && 'once')} x={i * W + 0.5} y={H - h} width={W - 1.5} height={h} rx={1} />
            {fill > 0 && <rect className="st on" x={i * W + 0.5} y={H - h * fill} width={W - 1.5} height={h * fill} rx={1} />}
          </g>
        );
      })}
      {height !== null && whole > 0 && <circle className="who" cx={(whole - 1) * W + (W - 1) / 2 + 0.25} cy={H - stepH(whole - 1) - 3.2} r={2.6} />}
    </svg>
  );
}
