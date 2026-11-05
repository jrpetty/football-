/**
 * The climb: the picture every difficulty ladder leads with (DifficultyLadder.tsx).
 *
 * A mountain of ten ledges, level 1 at the bottom, the summit (the top level) above. Every model is a token in its
 * own colour standing on the ledge of its HEIGHT (the highest level passed reliably with every level below it;
 * src/core/ladders.ts); models at the same height stand side by side. The ledge above the leader is cracked: the
 * wall nobody climbed past, with the levels beyond it in fog. The Random Baseline stands on the ground. A pass
 * higher up that does not count is a dashed "once" ghost on that ledge; borderline and just-short heights carry a
 * small badge on the token.
 *
 * Down the right: what each level is (its drawing when there is one, its label, a bar for how big it is, read
 * from the labels; no bar when the labels do not say), then the blind test as three climbers (small, mid, top
 * model) standing where they got to in the blind test, with a ✕ where each one fell.
 *
 * On first view the tokens climb ledge by ledge to their heights (about two seconds; Replay runs it again). With
 * prefers-reduced-motion they are placed at their heights. Every number is the one the rules computed.
 */
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from 'react';
import { cx } from '../ui.tsx';
import { usePrefersReducedMotion } from '../../hooks.ts';
import { CALIBRATION_TIERS, calibrationClimb, calibrationMonth, DESIGN_TARGET_TEXT, type CalibrationClimb, type CalibrationTier, type LadderCalibration } from '../../../../src/core/ladders.ts';
import { RungThumb, hasRungThumb } from './rung-thumbs/index.tsx';
import type { LadderClimber } from './DifficultyLadder.tsx';

export type SceneMode = 'card' | 'slide';

const TIER_NAME: Record<CalibrationTier, string> = { small: 'Small', mid: 'Mid', top: 'Top' };

// ───────────────────────────── small helpers ─────────────────────────────

/** "Kite 2.5 Reasoner" → "K2.5", "Sable Large" → "SL". */
export function initials(label: string): string {
  const words = label.replace(/[()]/g, ' ').split(/[\s-]+/).filter(Boolean);
  const pick = words.find((w) => /\d/.test(w));
  const first = words[0]?.[0]?.toUpperCase() ?? '?';
  return pick ? `${first}${pick.replace(/[^\d.]/g, '').slice(0, 3)}` : words.slice(0, 2).map((w) => w[0]!.toUpperCase()).join('');
}

/** Token text per label, unique within one picture: "Atlas-4 Ultra" and "Atlas-4 Mini" become "A4U" and "A4M". */
export function tokenInitials(labels: readonly string[]): string[] {
  const base = labels.map(initials);
  return base.map((b, i) => {
    if (base.filter((x) => x === b).length < 2) return b;
    const words = labels[i]!.split(/[\s-]+/).filter(Boolean);
    const extra = words.length > 1 ? words[words.length - 1]![0]!.toUpperCase() : String(i + 1);
    return `${b}${extra}`;
  });
}

/** Dark or light ink on a model colour (hex); light when the colour cannot be read. */
export function inkOn(color: string): string {
  const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(color.trim());
  if (!m) return '#fff';
  const h = m[1]!.length === 3 ? [...m[1]!].map((c) => c + c).join('') : m[1]!;
  const [r, g, b] = [0, 2, 4].map((i) => {
    const v = parseInt(h.slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  });
  const lum = 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
  return lum > 0.32 ? '#0b1220' : '#fff';
}

/** How the token's height reads in its name tag. */
function heightWords(level: number, levels: number, baseline: boolean, notRun: boolean): string {
  if (notRun) return 'not run';
  if (level === 0) return baseline ? 'never left the ground' : 'did not pass L1';
  return level >= levels ? `L${level} · the summit` : `L${level}`;
}

// ───────────────────────────── the climb animation ─────────────────────────────

const PER_RUNG_MS = 190;
const STAGGER_MS = 150;

/**
 * Moves every token from the ground to its height, ledge by ledge (a pull-up per ledge), by writing a transform on
 * each token element directly (no re-render per frame). Starts when the scene is first a quarter visible (slides:
 * at once), again on `replay`; does nothing under reduced motion.
 */
function useClimb(sceneRef: RefObject<HTMLDivElement>,run: number, enabled: boolean, immediate: boolean) {
  const [visible, setVisible] = useState(immediate);
  const [playing, setPlaying] = useState(false);
  useEffect(() => {
    if (!enabled || visible) return;
    const el = sceneRef.current;
    if (!el || typeof IntersectionObserver !== 'function') {
      setVisible(true);
      return;
    }
    const io = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && setVisible(true), { threshold: 0.25 });
    io.observe(el);
    return () => io.disconnect();
  }, [enabled, visible, sceneRef]);

  // Before the climb starts, the tokens wait on the ground (set before paint, so nothing jumps). Without motion
  // they stand at their heights.
  useLayoutEffect(() => {
    const el = sceneRef.current;
    if (!el) return;
    const tokens = [...el.querySelectorAll<HTMLElement>('[data-climb]')];
    const band = bandPx(el);
    for (const t of tokens) {
      t.style.transform = enabled ? `translateY(${Number(t.dataset.climb) * band}px)` : '';
      t.dataset.arrived = enabled ? 'no' : 'yes';
    }
    el.dataset.climbing = enabled ? 'yes' : 'no';
  }, [enabled, run, sceneRef]);

  useEffect(() => {
    const el = sceneRef.current;
    if (!el || !enabled || !visible) return;
    const tokens = [...el.querySelectorAll<HTMLElement>('[data-climb]')].map((t, i) => ({ t, lv: Number(t.dataset.climb), delay: Number(t.dataset.order ?? i) * STAGGER_MS }));
    const start = performance.now();
    let raf = 0;
    setPlaying(true);
    const ease = (x: number) => x * x * (3 - 2 * x);
    const frame = (now: number) => {
      const band = bandPx(el);
      let busy = false;
      for (const { t, lv, delay } of tokens) {
        const p = Math.max(0, Math.min(lv, (now - start - delay) / PER_RUNG_MS));
        const done = p >= lv;
        const at = done ? lv : Math.floor(p) + ease(p - Math.floor(p));
        t.style.transform = done ? '' : `translateY(${(lv - at) * band}px)`;
        if (done && t.dataset.arrived !== 'yes') t.dataset.arrived = 'yes';
        busy ||= !done;
      }
      if (busy) raf = requestAnimationFrame(frame);
      else {
        el.dataset.climbing = 'no';
        setPlaying(false);
      }
    };
    raf = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(raf);
  }, [enabled, visible, run, sceneRef]);
  return playing;
}

/** The height of one level band in px (the scene's climb area). */
function bandPx(scene: HTMLElement): number {
  const area = scene.querySelector<HTMLElement>('.cs-climb');
  if (!area) return 0;
  const cs = getComputedStyle(area);
  const sky = parseFloat(cs.getPropertyValue('--sky-px')) || 0;
  const base = parseFloat(cs.getPropertyValue('--base-px')) || 0;
  const bands = parseFloat(cs.getPropertyValue('--bands')) || 11;
  return (area.clientHeight - sky - base) / bands;
}

// ───────────────────────────── pieces ─────────────────────────────

function Flag({ reached }: { reached: boolean }) {
  return (
    <svg className={cx('cs-flag', reached && 'reached')} viewBox="0 0 24 30" aria-hidden="true">
      <path className="pole" d="M4 2v27" />
      <path className="cloth" d="M5 3h15l-4 5 4 5H5z" />
    </svg>
  );
}

function ClimberFigure() {
  return (
    <svg className="cs-fig" viewBox="0 0 20 26" aria-hidden="true">
      <circle cx="10" cy="5.5" r="4.5" />
      <path d="M2.5 25v-6.5C2.5 14 6 11.5 10 11.5s7.5 2.5 7.5 7V25z" />
    </svg>
  );
}

/** One blind-tested model size as a climber: a rope up to the highest level it passed, ✕ where it failed. */
function BlindLane({ climb, levels, i }: { climb: CalibrationClimb | null; levels: number; i: number }) {
  const style = { ['--k' as string]: i } as CSSProperties;
  if (!climb) return <div className="cs-bl-lane none" style={style} />;
  const lv = (l: number) => ({ ['--lv' as string]: l }) as CSSProperties;
  return (
    <div className={cx('cs-bl-lane', `t-${climb.tier}`)} style={style}>
      <span className="cs-bl-rope" style={lv(climb.best)} />
      {climb.passed
        .filter((l) => l !== climb.best && l <= levels)
        .map((l) => (
          <i key={`p${l}`} className="cs-bl-mark ok" style={lv(l)} />
        ))}
      {climb.failed
        .filter((l) => l <= levels)
        .map((l) => (
          <i key={`f${l}`} className={cx('cs-bl-mark x', l === climb.fell && 'fell')} style={lv(l)}>
            ✕
          </i>
        ))}
      {climb.pending
        .filter((l) => l <= levels)
        .map((l) => (
          <i key={`q${l}`} className="cs-bl-mark q" style={lv(l)}>
            ?
          </i>
        ))}
      <span className="cs-bl-who" style={lv(climb.best)}>
        {climb.tier === 'top' && climb.clearedAll && (
          <span className="cs-bl-summit">
            <Flag reached />
          </span>
        )}
        <ClimberFigure />
      </span>
    </div>
  );
}

interface Token {
  c: LadderClimber;
  text: string;
  ink: string;
  order: number;
}

function TokenBall({ t, size, ghost, interactive, picked, onPick, levels }: { t: Token; size: 'card' | 'slide'; ghost?: number; interactive: boolean; picked: boolean; onPick?: (id: string) => void; levels: number }) {
  const h = t.c.climb;
  const badges: ReactNode[] = [];
  if (!ghost && !t.c.baseline) {
    if (h.borderline) badges.push(<i key="b" className="cs-badge warn" title="Borderline: the height rests on a rung passed by some attempts only, or within 5 points of the pass mark">~</i>);
    else if (h.justShort && h.height < levels) badges.push(<i key="j" className="cs-badge near" title={`Nearly: the next level up (L${h.height + 1}) was nearly passed`}>↑</i>);
  }
  const style = { ['--c' as string]: t.c.color, ['--ink' as string]: t.ink } as CSSProperties;
  const body = (
    <>
      <span className="cs-ball-t">{t.c.baseline ? '?' : t.text}</span>
      {badges}
    </>
  );
  const cls = cx('cs-ball', size === 'slide' && 'big', !!ghost && 'ghost', t.c.baseline && 'baseline', picked && 'picked');
  if (interactive && !ghost && onPick)
    return (
      <button
        type="button"
        className={cls}
        style={style}
        aria-pressed={picked}
        aria-label={`${t.c.baseline ? 'Random guessing' : t.c.label}: level ${h.height} of ${levels}${h.borderline ? ', borderline' : ''}${h.onceSolved.length ? `, once solved L${h.onceSolved[h.onceSolved.length - 1]}` : ''}. ${picked ? 'Hide' : 'Show'} every attempt`}
        title={`${t.c.label}: show every attempt`}
        onClick={() => onPick(t.c.id)}
      >
        {body}
      </button>
    );
  return (
    <span className={cls} style={style} title={ghost ? `${t.c.label} passed L${ghost} once, above its height: it does not count` : t.c.label} aria-hidden="true">
      {body}
    </span>
  );
}

// ───────────────────────────── the scene ─────────────────────────────

export function ClimbScene({
  testId,
  climbers,
  levels,
  rungs,
  sizes,
  calibration,
  mode,
  highlight,
  empty,
  picked,
  onPick,
  onLevel,
  replayable = true,
}: {
  testId?: string;
  climbers: LadderClimber[];
  levels: number;
  rungs: Array<string | null | undefined>;
  /** How big each level is (index 0 = level 1), or null for no bar. */
  sizes: { values: number[]; unit: string } | null;
  calibration?: LadderCalibration | null;
  mode: SceneMode;
  highlight?: number;
  /** Shown on the mountain instead of tokens ("Coming soon", "No results yet"). */
  empty?: { title: string; text: string } | null;
  picked?: string | null;
  onPick?: (id: string) => void;
  /** Open one level up close (its question, and how each model answered). */
  onLevel?: (level: number) => void;
  replayable?: boolean;
}) {
  const sceneRef = useRef<HTMLDivElement>(null);
  const reduced = usePrefersReducedMotion();
  const [run, setRun] = useState(0);
  const motion = !reduced && climbers.length > 0;
  const playing = useClimb(sceneRef, run, motion, mode === 'slide');
  const replay = useCallback(() => setRun((r) => r + 1), []);

  const tokens: Token[] = useMemo(() => {
    const txt = tokenInitials(climbers.map((c) => c.label));
    // The lowest set off first and the leader last, so the climb ends on the highest token arriving.
    const rank = climbers.map((_, i) => i).sort((a, b) => climbers[a]!.climb.height - climbers[b]!.climb.height || b - a);
    return climbers.map((c, i) => ({ c, text: txt[i]!, ink: inkOn(c.color), order: rank.indexOf(i) }));
  }, [climbers]);
  const real = tokens.filter((t) => !t.c.baseline);
  const top = Math.max(0, ...real.map((t) => t.c.climb.height));
  const wall = real.length && top < levels ? top + 1 : null;
  const summit = real.some((t) => t.c.climb.height >= levels);

  // Models at the same height stand together (the Random Baseline last, in grey). A "once" ghost stands after the
  // name tag of the models on its ledge, or on its own at the far end of an empty ledge.
  const byLevel = new Map<number, Token[]>();
  for (const t of [...real, ...tokens.filter((x) => x.c.baseline)]) byLevel.set(t.c.climb.height, [...(byLevel.get(t.c.climb.height) ?? []), t]);
  const groups = [...byLevel].sort((a, b) => b[0] - a[0]);
  const ghostsAt = new Map<number, Token[]>();
  for (const t of real) for (const lv of t.c.climb.onceSolved) if (lv <= levels) ghostsAt.set(lv, [...(ghostsAt.get(lv) ?? []), t]);
  const ghosts = [...ghostsAt].filter(([lv]) => !byLevel.has(lv));

  const thumbs = !!testId && hasRungThumb(testId);
  const maxSize = sizes ? Math.max(...sizes.values) : 0;
  const order = Array.from({ length: levels + 1 }, (_, i) => levels - i);
  const interactive = mode === 'card' && !!onPick;
  const levelClicks = mode === 'card' && !!onLevel;
  const tiers = calibration ? CALIBRATION_TIERS.map((t) => calibrationClimb(calibration, t)) : [];
  const style = { ['--levels' as string]: levels, ['--bands' as string]: levels + 1 } as CSSProperties;
  const row = (lv: number) => 3 + (levels - lv);

  return (
    <div ref={sceneRef} className={cx('cs', `cs-${mode}`, thumbs && 'has-thumbs', !sizes && 'no-sizes', !calibration && 'no-cal', playing && 'is-playing')} style={style}>
      {/* column heads */}
      <div className="cs-h cs-h-lv" style={{ gridRow: 1 }} aria-hidden="true">
        <span>Level</span>
        <small>↑ harder</small>
      </div>
      <div className="cs-h cs-h-climb" style={{ gridRow: 1 }} />
      <div className="cs-h cs-h-slot" style={{ gridRow: 1 }} aria-hidden="true">
        <span>What each level is</span>
        {sizes && <small>bar: number of {sizes.unit}s</small>}
      </div>
      <div className="cs-h cs-h-bl" style={{ gridRow: 1 }} aria-hidden="true">
        {calibration ? (
          <>
            <span>Blind test</span>
            <small>{calibrationMonth(calibration.date)}</small>
            <span className="cs-bl-names">
              {CALIBRATION_TIERS.map((t) => (
                <b key={t}>
                  <span className="full">{TIER_NAME[t]}</span>
                  <span className="abbr">{TIER_NAME[t][0]}</span>
                </b>
              ))}
            </span>
          </>
        ) : (
          <span>Blind test</span>
        )}
      </div>

      {/* the level numbers (left) and what each level is (right), one band per level, the ground at the bottom */}
      {order.map((lv) => {
        const label = lv > 0 ? rungs[lv - 1] : null;
        const size = lv > 0 && sizes ? sizes.values[lv - 1] : undefined;
        const beyond = wall !== null && lv > wall;
        const bar = size !== undefined && maxSize > 0 && (
          <span className="cs-bar" title={label ?? undefined}>
            <i style={{ width: `${Math.max(3, (size / maxSize) * 100)}%` }} />
          </span>
        );
        const lvClass = cx('cs-lv', lv === 0 && 'ground', highlight === lv && 'hl', beyond && 'beyond', lv === wall && 'wall', lv === levels && 'top');
        const lvBody = (
          <>
            <b>{lv === 0 ? 'Start' : `L${lv}`}</b>
            {label && <small className="cs-lv-label">{label}</small>}
            {bar && <span className="cs-lv-bar">{bar}</span>}
          </>
        );
        return [
          levelClicks && lv > 0 ? (
            <button
              key={`lv${lv}`}
              type="button"
              className={cx(lvClass, 'cs-pick')}
              style={{ gridRow: row(lv) }}
              data-lv={lv}
              onClick={() => onLevel!(lv)}
              aria-label={`Level ${lv}${label ? ` (${label})` : ''}: see the question and how each model answered`}
            >
              {lvBody}
            </button>
          ) : (
            <div key={`lv${lv}`} className={lvClass} style={{ gridRow: row(lv) }} aria-hidden="true">
              {lvBody}
            </div>
          ),
          <div
            key={`sl${lv}`}
            className={cx('cs-slot', lv === 0 && 'ground', highlight === lv && 'hl', beyond && 'beyond', levelClicks && lv > 0 && 'cs-pick')}
            style={{ gridRow: row(lv) }}
            aria-hidden="true"
            onClick={levelClicks && lv > 0 ? () => onLevel!(lv) : undefined}
            title={levelClicks && lv > 0 ? `Level ${lv}: see the question and how each model answered` : undefined}
          >
            {lv > 0 && thumbs && (
              <span className="cs-thumb">
                <RungThumb testId={testId!} level={lv} size={mode === 'slide' ? 52 : 44} tone={mode === 'slide' ? 'broadcast' : 'default'} />
              </span>
            )}
            {lv > 0 && (
              <span className="cs-slot-t">
                {label ? <span className="cs-slot-label">{label}</span> : <span className="cs-slot-label muted">Level {lv}</span>}
                {bar}
              </span>
            )}
          </div>,
        ];
      })}

      {/* the mountain */}
      <div className="cs-climb" style={{ gridRow: `2 / span ${levels + 3}` }}>
        <div className="cs-mtn" aria-hidden="true" />
        <div className="cs-ground-strip" aria-hidden="true" />
        {highlight && highlight <= levels ? <div className="cs-hl" style={{ ['--lv' as string]: highlight } as CSSProperties} aria-hidden="true" /> : null}
        {/* every level's band is a target: a tap opens that level up close (the level number is the keyboard way in) */}
        {levelClicks &&
          Array.from({ length: levels }, (_, i) => i + 1).map((lv) => (
            <i key={`hit${lv}`} className="cs-hit" style={{ ['--lv' as string]: lv } as CSSProperties} onClick={() => onLevel!(lv)} title={`Level ${lv}: see the question and how each model answered`} aria-hidden="true" />
          ))}
        {Array.from({ length: levels }, (_, i) => i + 1).map((lv) => (
          <div key={lv} className={cx('cs-ledge', lv === wall && 'wall', lv === levels && 'summit', lv === levels && summit && 'reached')} style={{ ['--lv' as string]: lv } as CSSProperties} aria-hidden="true">
            {lv === wall && <i className="cs-crack" />}
          </div>
        ))}
        {wall !== null && !empty && <div className="cs-fog" style={{ ['--lv' as string]: wall } as CSSProperties} aria-hidden="true" />}
        <div className={cx('cs-summit', summit && 'reached')} aria-hidden="true">
          <Flag reached={summit} />
          <span>{summit ? 'Summit reached' : `Summit · L${levels}`}</span>
        </div>
        {wall !== null && !empty && (
          <div className="cs-wall-sign" style={{ ['--lv' as string]: wall } as CSSProperties} aria-hidden="true">
            <svg viewBox="0 0 16 16" aria-hidden="true">
              <path d="M8 1 6 6l3 2-2 4 1 3" />
            </svg>
            <b>The wall</b>
            <span>nobody climbed to L{wall}</span>
          </div>
        )}
        {empty ? (
          <div className="cs-empty">
            <b>{empty.title}</b>
            <span>{empty.text}</span>
          </div>
        ) : (
          <>
            {ghosts.map(([lv, ts]) => (
              <div key={`g${lv}`} className="cs-group ghosts" style={{ ['--lv' as string]: lv } as CSSProperties} aria-hidden="true">
                {ts.map((t) => (
                  <TokenBall key={t.c.id} t={t} size={mode} ghost={lv} interactive={false} picked={false} levels={levels} />
                ))}
                <span className="cs-tag ghost">once</span>
              </div>
            ))}
            {groups.map(([lv, ts]) => {
              const all = ts.every((t) => t.c.baseline);
              const words = lv === 0 && ts.some((t) => t.c.baseline) ? 'never left the ground' : heightWords(lv, levels, all, ts.every((t) => t.c.climb.levelsRun === 0));
              const extra = ghostsAt.get(lv) ?? [];
              return (
                <div key={`h${lv}`} className={cx('cs-group', lv === top && top > 0 && 'lead', all && 'base', ts.length > 2 && 'crowd')} style={{ ['--lv' as string]: lv } as CSSProperties}>
                  <span className="cs-balls">
                    {ts.map((t) => (
                      <span key={t.c.id} className="cs-climber" data-climb={lv} data-order={t.order}>
                        <TokenBall t={t} size={mode} interactive={interactive} picked={picked === t.c.id} onPick={onPick} levels={levels} />
                      </span>
                    ))}
                  </span>
                  <span className="cs-tag" style={{ ['--c' as string]: all ? undefined : ts[0]!.c.color } as CSSProperties} aria-hidden="true">
                    {ts.map((t, i) => (
                      <span key={t.c.id} className="cs-tag-n">
                        {i > 0 && <span className="cs-tag-sep">{i === ts.length - 1 ? ' & ' : ', '}</span>}
                        <b>{t.c.baseline ? (ts.length > 1 ? 'random guessing' : 'Random guessing') : t.c.label}</b>
                      </span>
                    ))}
                    <span className="cs-tag-h">
                      {lv === 0 && ts.length > 1 ? (
                        <>
                          <span className="cs-tag-long">{words}</span>
                          <span className="cs-tag-short">L0</span>
                        </>
                      ) : (
                        words.replace(' · the summit', '')
                      )}
                      {words.includes('the summit') && <span className="cs-tag-sum"> · the summit</span>}
                    </span>
                  </span>
                  {extra.length > 0 && (
                    <span className="cs-group-ghosts" aria-hidden="true">
                      {extra.map((t) => (
                        <TokenBall key={t.c.id} t={t} size={mode} ghost={lv} interactive={false} picked={false} levels={levels} />
                      ))}
                      <span className="cs-tag ghost">once</span>
                    </span>
                  )}
                </div>
              );
            })}
          </>
        )}
        {replayable && motion && (
          <button type="button" className="cs-replay" onClick={replay} aria-label="Replay the climb">
            <svg viewBox="0 0 16 16" aria-hidden="true">
              <path d="M3 8a5 5 0 1 0 1.6-3.7" />
              <path d="M2.5 2.5v3h3" />
            </svg>
            <span className="cs-replay-t">Replay</span>
          </button>
        )}
      </div>

      {/* the blind test, as three climbers */}
      <div className="cs-bl" style={{ gridRow: `2 / span ${levels + 3}` }} aria-hidden="true" title={calibration ? undefined : `Not blind-tested yet. ${DESIGN_TARGET_TEXT}`}>
        {Array.from({ length: levels }, (_, i) => (
          <i key={i} className="cs-bl-line" style={{ ['--lv' as string]: i + 1 } as CSSProperties} />
        ))}
        {calibration ? (
          tiers.map((c, i) => <BlindLane key={i} climb={c} levels={levels} i={i} />)
        ) : (
          <span className="cs-bl-none">Not blind-tested yet</span>
        )}
      </div>
    </div>
  );
}

/**
 * The blind test as a small picture (lists of ladders): ten rungs, the three model sizes standing on the highest
 * level each passed, a rope up from the ground, a ✕ where each one failed, a flag when the top model cleared every
 * level it was tested on.
 */
export function BlindTestMini({ calibration, levels = 10 }: { calibration: LadderCalibration; levels?: number }) {
  const R = 8.4;
  const H = levels * R + 14;
  const y = (lv: number) => H - 5 - lv * R;
  const xs = [15, 34, 53];
  const climbs = CALIBRATION_TIERS.map((t) => calibrationClimb(calibration, t));
  const top = climbs[2];
  return (
    <svg className="cs-mini" viewBox={`0 0 68 ${H}`} aria-hidden="true">
      <path className="rail" d={`M5 ${y(0)}V${y(levels) - 3}M63 ${y(0)}V${y(levels) - 3}`} />
      {Array.from({ length: levels }, (_, i) => (
        <path key={i} className="rung" d={`M5 ${y(i + 1)}H63`} />
      ))}
      <path className="ground" d={`M2 ${y(0)}H66`} />
      {climbs.map((c, i) =>
        c ? (
          <g key={i} className={`t-${c.tier}`}>
            <path className="rope" d={`M${xs[i]} ${y(0)}V${y(c.best)}`} />
            {c.failed
              .filter((l) => l <= levels)
              .map((l) => (
                <text key={l} className={cx('x', l === c.fell && 'fell')} x={xs[i]} y={y(l - 0.5) + 2.6}>
                  ✕
                </text>
              ))}
            <circle className="who" cx={xs[i]} cy={y(c.best) - 4.6} r={4.6} />
            <text className="who-t" x={xs[i]} y={y(c.best) - 2.5}>
              {TIER_NAME[c.tier][0]}
            </text>
          </g>
        ) : null,
      )}
      {top?.clearedAll && <path className="flag" d={`M${xs[2]} ${y(top.best) - 9.5}v-9h7l-2 2.2 2 2.3h-7`} />}
    </svg>
  );
}
