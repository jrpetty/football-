/**
 * Head to Head building blocks, shared by the page (/versus) and the Presenter
 * deck (/present/versus): the fighter badge, the face-off with its tale of the
 * tape, facing score bars, round cards, the decisive moment and the scoreboard.
 * Everything shown comes from GET /api/versus; missing data says "not recorded".
 */
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { CategoryGlyph } from '../components/viz/CategoryGlyph.tsx';
import { TrophySvg } from '../components/viz/TrophySvg.tsx';
import { fmtCost, fmtMs } from '../format.ts';
import { perM } from '../../../src/media/common.ts';
import { useCountUp } from '../hooks.ts';
import { cornerColors, inkOn } from '../../../src/versus/colors.ts';
import { contextWords, momentLine, nameOf, resultHeadline, resultSub, roundVerdict } from '../../../src/versus/words.ts';
import type { VersusAnswer, VersusData, VersusFighter, VersusRound, VersusTotals } from './client.ts';

export type Side = 'a' | 'b';
export interface Corners {
  a: string;
  b: string;
}

export function useCorners(d: VersusData): Corners {
  return cornerColors(d.a.color, d.b.color);
}

/** Up to two initials for the badge plate: "Nova 3 Pro" → "N3", "GPT-5.6 Sol" → "GS". */
export function monogram(f: VersusFighter): string {
  if (f.baseline) return '?';
  const words = nameOf(f)
    .replace(/[()[\]]/g, ' ')
    .split(/[\s-]+/)
    .filter(Boolean);
  const pick = words.slice(0, 2).map((w) => w[0]!.toUpperCase());
  return pick.join('') || '·';
}

/** Counts up from 0 when it appears (the final value at once under reduced motion). */
export function CountUp({ value, ms = 900, delay = 0 }: { value: number; ms?: number; delay?: number }) {
  return <>{Math.round(useCountUp(value, ms, delay))}</>;
}

const sideVars = (color: string): CSSProperties => ({ ['--c' as string]: color, ['--ink' as string]: inkOn(color) });

// ───────────────────────────── Fighter badge ─────────────────────────────

export function FighterBadge({ f, color, side, state, size = 'md' }: { f: VersusFighter; color: string; side: Side; state?: 'won' | 'lost' | 'draw'; size?: 'sm' | 'md' | 'lg' }) {
  return (
    <div className={cx('vx-badge', `vx-${side}`, `vx-${size}`, state && `is-${state}`)} style={sideVars(color)}>
      <div className="vx-plate" aria-hidden="true">
        <span>{monogram(f)}</span>
        {state === 'won' && <TrophySvg className="vx-plate-trophy" />}
      </div>
      <div className="vx-id">
        <div className="vx-name" title={nameOf(f)}>
          {nameOf(f)}
        </div>
        <div className="vx-vendor">{f.baseline ? 'Reference player: answers at random' : f.manual ? `${f.vendor || 'Chatbot'} · pasted by hand` : f.vendor || 'Vendor not recorded'}</div>
      </div>
    </div>
  );
}

// ───────────────────────────── Face-off + tale of the tape ─────────────────────────────

function priceText(f: VersusFighter, which: 'inputPerM' | 'outputPerM'): string {
  if (f.baseline) return 'free (no model)';
  if (f.manual) return 'not recorded';
  return f.pricing ? perM(f.pricing[which]) : 'not recorded';
}

export function TaleOfTape({ d, colors }: { d: VersusData; colors: Corners }) {
  const rows: Array<{ label: string; hint: string; a: string; b: string; better?: Side | null }> = [
    { label: 'Made by', hint: 'the company behind the model', a: d.a.baseline ? 'Gauntlet' : d.a.vendor || 'not recorded', b: d.b.baseline ? 'Gauntlet' : d.b.vendor || 'not recorded' },
    { label: 'Price to read', hint: 'US dollars per million tokens sent in', a: priceText(d.a, 'inputPerM'), b: priceText(d.b, 'inputPerM'), better: cheaper(d.a.pricing?.inputPerM, d.b.pricing?.inputPerM) },
    { label: 'Price to write', hint: 'US dollars per million tokens written', a: priceText(d.a, 'outputPerM'), b: priceText(d.b, 'outputPerM'), better: cheaper(d.a.pricing?.outputPerM, d.b.pricing?.outputPerM) },
    { label: 'Memory', hint: 'the most text it can read at once', a: contextWords(d.a.contextWindow), b: contextWords(d.b.contextWindow), better: bigger(d.a.contextWindow, d.b.contextWindow) },
  ];
  return (
    <div className="vx-tape" aria-label="Tale of the tape">
      <div className="vx-tape-h">
        <span style={{ color: colors.a }}>{nameOf(d.a)}</span>
        <b>Tale of the tape</b>
        <span style={{ color: colors.b }}>{nameOf(d.b)}</span>
      </div>
      {rows.map((r, i) => (
        <div key={r.label} className="vx-tape-row" style={{ ['--i' as string]: i }}>
          <span className={cx('vx-tape-v', 'l', r.better === 'a' && 'best', /not recorded/.test(r.a) && 'nr')} title={r.a}>
            {r.a}
          </span>
          <span className="vx-tape-k">
            {r.label}
            <small>{r.hint}</small>
          </span>
          <span className={cx('vx-tape-v', 'r', r.better === 'b' && 'best', /not recorded/.test(r.b) && 'nr')} title={r.b}>
            {r.b}
          </span>
        </div>
      ))}
      <div className="vx-tape-note">
        <span aria-hidden="true">★</span> = the better of the two (cheaper, or reads more)
      </div>
    </div>
  );
}

function cheaper(a: number | undefined, b: number | undefined): Side | null {
  if (a === undefined || b === undefined || a === b) return null;
  return a < b ? 'a' : 'b';
}
function bigger(a: number | null, b: number | null): Side | null {
  if (!a || !b || a === b) return null;
  return a > b ? 'a' : 'b';
}

export function FaceOff({ d, colors, size = 'lg' }: { d: VersusData; colors: Corners; size?: 'md' | 'lg' }) {
  return (
    <div className="vx-face">
      <div className="vx-face-side vx-a" style={sideVars(colors.a)}>
        <FighterBadge f={d.a} color={colors.a} side="a" size={size} />
      </div>
      <div className="vx-vs" aria-label="versus">
        <span>VS</span>
      </div>
      <div className="vx-face-side vx-b" style={sideVars(colors.b)}>
        <FighterBadge f={d.b} color={colors.b} side="b" size={size} />
      </div>
    </div>
  );
}

// ───────────────────────────── Score bars ─────────────────────────────

/** Two bars growing out from the middle towards each model; the loser's bar is dimmed. */
export function FacingBars({ round, colors, big }: { round: VersusRound; colors: Corners; big?: boolean }) {
  const sa = round.a.score;
  const sb = round.b.score;
  return (
    <div className={cx('vx-bars', big && 'big')} role="img" aria-label={`Scores out of 100: ${sa === null ? 'not recorded' : Math.round(sa * 100)} versus ${sb === null ? 'not recorded' : Math.round(sb * 100)}`}>
      <span className={cx('vx-num l', round.winner === 'a' && 'win', round.winner === 'b' && 'lose')}>
        {sa === null ? '—' : <CountUp value={sa * 100} ms={900} />}
      </span>
      <div className="vx-track l">
        <div className={cx('vx-fill', round.winner === 'b' && 'dim')} style={{ width: `${Math.max(1, (sa ?? 0) * 100)}%`, background: colors.a }} />
      </div>
      <div className="vx-mid" aria-hidden="true" />
      <div className="vx-track r">
        <div className={cx('vx-fill', round.winner === 'a' && 'dim')} style={{ width: `${Math.max(1, (sb ?? 0) * 100)}%`, background: colors.b }} />
      </div>
      <span className={cx('vx-num r', round.winner === 'b' && 'win', round.winner === 'a' && 'lose')}>
        {sb === null ? '—' : <CountUp value={sb * 100} ms={900} />}
      </span>
    </div>
  );
}

// ───────────────────────────── Winner badge ─────────────────────────────

export function WinnerBadge({ round, d, colors }: { round: VersusRound; d: VersusData; colors: Corners }) {
  if (round.winner === 'tie') {
    return (
      <span className="vx-winner tie">
        <Icon.Target /> {roundVerdict(round, d)}
      </span>
    );
  }
  const c = colors[round.winner];
  return (
    <span className="vx-winner" style={sideVars(c)}>
      <TrophySvg className="vx-winner-ico" /> {roundVerdict(round, d)}
    </span>
  );
}

// ───────────────────────────── Decisive moment ─────────────────────────────

/** The end of a quote (where the answer is), on one flowing line, for tight layouts. */
function tail(text: string, max: number): { text: string; cut: boolean } {
  const flat = text.replace(/\s*\n+\s*/g, ' ').trim();
  if (flat.length <= max) return { text: flat, cut: false };
  const s = flat.slice(flat.length - max);
  const sp = s.indexOf(' ');
  return { text: sp >= 0 && sp < 30 ? s.slice(sp + 1) : s, cut: true };
}

function AnswerBox({ who, ans, right, color, compact }: { who: string; ans: VersusAnswer; right: boolean; color: string; compact?: boolean }) {
  // Keep the end of the reply (where the answer is); a line clamp would cut the answer off instead.
  const q = tail(ans.quote, compact ? 150 : 200);
  return (
    <div className={cx('vx-ans', right ? 'right' : 'wrong')} style={sideVars(color)}>
      <div className="vx-ans-h">
        <span className="vx-ans-who">
          <i aria-hidden="true" />
          {who}
        </span>
        <span className="vx-ans-v">
          {right ? <Icon.Check /> : <Icon.X />}
          {right ? 'Right' : 'Wrong'}
        </span>
      </div>
      {ans.extracted && (
        <div className="vx-ans-final">
          <span>Final answer</span>
          <b>{ans.extracted}</b>
        </div>
      )}
      {ans.quote ? (
        <blockquote className="vx-ans-q">
          {(ans.truncated || q.cut) && <span className="vx-ell">…</span>}
          {q.text}
        </blockquote>
      ) : (
        <p className="vx-ans-q nr">Reply not recorded</p>
      )}
    </div>
  );
}

export function DecisiveMoment({ round, d, colors, compact }: { round: VersusRound; d: VersusData; colors: Corners; compact?: boolean }) {
  const m = round.moment;
  if (!m) {
    return (
      <div className="vx-moment none">
        <Icon.Info />
        <span>No single question split them: on every question they both answered, they were both right, both wrong, or earned partial credit.</span>
      </div>
    );
  }
  return (
    <div className={cx('vx-moment', compact && 'compact')}>
      <div className="vx-moment-h">
        <span className="vx-moment-k">
          <Icon.Zap /> Decisive moment
        </span>
        <span className="vx-moment-line">{momentLine(round, d)}</span>
      </div>
      {(m.question || m.expected) && (
        <div className="vx-moment-q">
          {m.question && (
            <p>
              <span>Asked</span> {m.question}
            </p>
          )}
          {m.expected && (
            <p className="vx-key">
              <span>Correct answer</span> <b>{m.expected}</b>
            </p>
          )}
        </div>
      )}
      <div className="vx-moment-cols">
        <AnswerBox who={nameOf(d.a)} ans={m.a} right={m.right === 'a'} color={colors.a} compact={compact} />
        <AnswerBox who={nameOf(d.b)} ans={m.b} right={m.right === 'b'} color={colors.b} compact={compact} />
      </div>
      <div className="vx-legend" aria-label="Colour key">
        <span className="ok">
          <i /> green = got it right
        </span>
        <span className="bad">
          <i /> red = got it wrong
        </span>
        <span>Answers quoted from the recorded replies{m.a.truncated || m.b.truncated || m.a.quote.length > 150 || m.b.quote.length > 150 ? ', shortened (…)' : ''}</span>
      </div>
    </div>
  );
}

// ───────────────────────────── Round card ─────────────────────────────

export function RoundHead({ round, index, total }: { round: VersusRound; index: number; total: number }) {
  return (
    <div className="vx-round-h">
      <span className="vx-cat" style={{ ['--cc' as string]: round.categoryColor }}>
        <CategoryGlyph category={round.category} />
      </span>
      <div className="vx-round-t">
        <div className="vx-round-k">
          Round {index + 1} of {total} · {round.categoryName}
        </div>
        <h3 className="vx-round-name">{round.testName}</h3>
        {round.hook && <div className="vx-round-hook">{round.hook}</div>}
      </div>
    </div>
  );
}

export function RoundCard({ round, index, d, colors }: { round: VersusRound; index: number; d: VersusData; colors: Corners }) {
  return (
    <article className={cx('vx-round', `won-${round.winner}`)} style={{ ['--i' as string]: index } as CSSProperties}>
      <div className="vx-round-top">
        <RoundHead round={round} index={index} total={d.rounds.length} />
        <WinnerBadge round={round} d={d} colors={colors} />
      </div>
      <FacingBars round={round} colors={colors} />
      <div className="vx-round-meta">
        <span>
          {round.cases} {round.cases === 1 ? 'question' : 'questions'} both answered
        </span>
        <span>
          Cost {costText(round.a.costUsd, d.a)} vs {costText(round.b.costUsd, d.b)}
        </span>
        <span>
          Time {fmtMs(round.a.timeMs)} vs {fmtMs(round.b.timeMs)}
        </span>
      </div>
      <DecisiveMoment round={round} d={d} colors={colors} compact />
    </article>
  );
}

export function costText(usd: number, f: VersusFighter): string {
  if (usd > 0) return fmtCost(usd);
  return f.baseline ? 'free' : 'not recorded';
}

// ───────────────────────────── Scoreboard ─────────────────────────────

interface StatRow {
  label: string;
  hint: string;
  a: ReactNode;
  b: ReactNode;
  better: Side | null;
}

function cmp(a: number | null, b: number | null, higherIsBetter: boolean): Side | null {
  if (a === null || b === null || Math.abs(a - b) < 1e-9) return null;
  return a > b === higherIsBetter ? 'a' : 'b';
}

export function statRows(d: VersusData): StatRow[] {
  const ta = d.totals.a;
  const tb = d.totals.b;
  const cost = (t: VersusTotals, f: VersusFighter) => (t.costUsd > 0 ? t.costUsd : null) ?? (f.baseline ? 0 : null);
  const ca = cost(ta, d.a);
  const cb = cost(tb, d.b);
  return [
    { label: 'Rounds won', hint: `out of ${d.rounds.length}${d.ties ? `, ${d.ties} drawn` : ''}`, a: ta.roundsWon, b: tb.roundsWon, better: cmp(ta.roundsWon, tb.roundsWon, true) },
    { label: 'Average score', hint: 'out of 100, across these tests', a: ta.avgScore === null ? 'not recorded' : Math.round(ta.avgScore), b: tb.avgScore === null ? 'not recorded' : Math.round(tb.avgScore), better: cmp(ta.avgScore, tb.avgScore, true) },
    { label: 'Total cost', hint: 'to run these tests once', a: ca === null ? 'not recorded' : ca === 0 ? 'free' : fmtCost(ca), b: cb === null ? 'not recorded' : cb === 0 ? 'free' : fmtCost(cb), better: ca && cb ? cmp(ca, cb, false) : null },
    { label: 'Total time', hint: 'one question after another', a: fmtMs(ta.timeMs), b: fmtMs(tb.timeMs), better: cmp(ta.timeMs, tb.timeMs, false) },
    { label: 'Writing speed', hint: 'tokens written per second', a: ta.tokensPerSec === null ? 'not recorded' : Math.round(ta.tokensPerSec), b: tb.tokensPerSec === null ? 'not recorded' : Math.round(tb.tokensPerSec), better: cmp(ta.tokensPerSec, tb.tokensPerSec, true) },
    { label: 'Value', hint: 'score points per dollar', a: ta.value === null ? 'not recorded' : Math.round(ta.value).toLocaleString('en-US'), b: tb.value === null ? 'not recorded' : Math.round(tb.value).toLocaleString('en-US'), better: cmp(ta.value, tb.value, true) },
  ];
}

export function Scoreboard({ d, colors, size = 'md' }: { d: VersusData; colors: Corners; size?: 'md' | 'lg' }) {
  const win = d.winner;
  return (
    <div className={cx('vx-board', `vx-${size}`, `won-${win}`)} style={{ ['--vx-a' as string]: colors.a, ['--vx-b' as string]: colors.b }}>
      <div className="vx-board-top">
        <FighterBadge f={d.a} color={colors.a} side="a" size={size === 'lg' ? 'md' : 'sm'} state={win === 'tie' ? 'draw' : win === 'a' ? 'won' : 'lost'} />
        <div className="vx-board-score" aria-label={`Rounds won: ${d.totals.a.roundsWon} to ${d.totals.b.roundsWon}`}>
          <span style={{ color: colors.a }} className={cx(win === 'b' && 'lose')}>
            <CountUp value={d.totals.a.roundsWon} ms={1200} delay={300} />
          </span>
          <i>–</i>
          <span style={{ color: colors.b }} className={cx(win === 'a' && 'lose')}>
            <CountUp value={d.totals.b.roundsWon} ms={1200} delay={300} />
          </span>
        </div>
        <FighterBadge f={d.b} color={colors.b} side="b" size={size === 'lg' ? 'md' : 'sm'} state={win === 'tie' ? 'draw' : win === 'b' ? 'won' : 'lost'} />
      </div>
      <div className="vx-board-head">
        {win !== 'tie' && <TrophySvg className="vx-board-trophy" />}
        <h2 className={cx(resultHeadline(d).length > 32 && 'long')}>{resultHeadline(d)}</h2>
        <p>{resultSub(d)}</p>
      </div>
      <div className="vx-stats">
        {statRows(d).map((r, i) => (
          <div key={r.label} className="vx-stat" style={{ ['--i' as string]: i }}>
            <span className={cx('vx-stat-v l', r.better === 'a' && 'best', r.a === 'not recorded' && 'nr')}>{r.a}</span>
            <span className="vx-stat-k">
              {r.label}
              <small>{r.hint}</small>
            </span>
            <span className={cx('vx-stat-v r', r.better === 'b' && 'best', r.b === 'not recorded' && 'nr')}>{r.b}</span>
          </div>
        ))}
        <div className="vx-tape-note">
          <span aria-hidden="true">★</span> = the better of the two on that line
        </div>
      </div>
    </div>
  );
}

/** A strip of small dots, one per round, coloured by who won it. */
export function RoundStrip({ d, colors, upto }: { d: VersusData; colors: Corners; upto?: number }) {
  return (
    <div className="vx-strip" aria-label="Who won each round">
      {d.rounds.map((r, i) => (
        <span
          key={r.testId}
          className={cx('vx-dot', r.winner === 'tie' && 'tie', upto !== undefined && i > upto && 'future', upto === i && 'now')}
          style={r.winner === 'tie' ? undefined : { background: colors[r.winner] }}
          title={`${r.testName}: ${r.winner === 'tie' ? 'draw' : `${nameOf(d[r.winner])} won`}`}
        />
      ))}
    </div>
  );
}
