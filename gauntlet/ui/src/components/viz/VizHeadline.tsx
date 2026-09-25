/**
 * The one-glance story of a moment: a small eyebrow ("Q7 · 2-hop chain · 64% in"),
 * a big plain-English headline coloured good / bad / warn / neutral, and an
 * optional huge key number on the right. Shared by the long-context, drawing
 * and picture visuals.
 */
import type { ReactNode } from 'react';
import { cx } from '../ui.tsx';
import type { Tone } from './vizModel.ts';
import { ToneIcon } from './VizLegend.tsx';
import { Icon } from '../icons.tsx';
import './viz.css';

export function VizHeadline({ eyebrow, title, tone = 'neutral', big, bigSub, className }: { eyebrow?: ReactNode; title: ReactNode; tone?: Tone; big?: ReactNode; bigSub?: ReactNode; className?: string }) {
  return (
    <div className={cx('vz-head', `tone-${tone}`, className)} role="status">
      <ToneIcon tone={tone} className="vz-head-ic" />
      <div className="vz-head-t">
        {eyebrow && <div className="vz-eyebrow">{eyebrow}</div>}
        <div className="vz-title">{title}</div>
      </div>
      {big !== undefined && (
        <div className="vz-big tnum">
          {big}
          {bigSub && <small>{bigSub}</small>}
        </div>
      )}
    </div>
  );
}

/** Model's answer next to the truth, each in its own card. */
export function AnswerVsTruth({ answer, truth, correct, answerLabel = 'Model’s answer', truthLabel = 'Correct answer', note, strike = true }: { answer: ReactNode; truth: ReactNode; correct: boolean | null; answerLabel?: string; truthLabel?: string; note?: ReactNode; strike?: boolean }) {
  return (
    <div className="vz-avt">
      <div className={cx('vz-avt-card', correct === true ? 'good' : correct === false ? 'bad' : '', !strike && 'no-strike')}>
        <div className="vz-k">
          {correct !== null && <ToneIcon tone={correct ? 'good' : 'bad'} />}
          {answerLabel}
        </div>
        <div className="vz-v">{answer}</div>
      </div>
      <div className="vz-avt-card truth">
        <div className="vz-k">
          <Icon.Key className="vz-ic" aria-hidden="true" />
          {truthLabel}
        </div>
        <div className="vz-v">{truth}</div>
      </div>
      {note && <div className="vz-avt-note">{note}</div>}
    </div>
  );
}

/** Words used against a limit, e.g. "97 / 120 words". */
export function WordMeter({ words, limit, label = 'words' }: { words: number; limit: number | null; label?: string }) {
  if (!limit) {
    return (
      <div className="vz-meter">
        <div className="vz-meter-h">
          <span>{label}</span>
          <b className="tnum">{words}</b>
          <span className="muted">limit not recorded</span>
        </div>
      </div>
    );
  }
  const over = words > limit;
  const pct = Math.min(100, (words / limit) * 100);
  return (
    <div className={cx('vz-meter', over && 'over')}>
      <div className="vz-meter-h">
        <span>{label}</span>
        <b className="tnum">
          {words} / {limit}
        </b>
        {over && <span className="vz-meter-warn">over the limit: cut to {limit}</span>}
      </div>
      <div className="vz-meter-track" role="meter" aria-valuemin={0} aria-valuemax={limit} aria-valuenow={Math.min(words, limit)} aria-label={`${words} of ${limit} ${label}`}>
        <i style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

/** A labelled 0–1 bar list (judge scores, score components, found-rate by position). */
export function ScoreBars({ rows, max = 1, unit = '%', scale = 100 }: { rows: Array<{ label: ReactNode; value: number | null; tone?: Tone; note?: ReactNode; right?: ReactNode }>; max?: number; unit?: string; scale?: number }) {
  return (
    <div className="vz-bars">
      {rows.map((r, i) => (
        <div key={i} className={cx('vz-bar', r.tone && `tone-${r.tone}`)}>
          <span className="vz-bar-l">{r.label}</span>
          <span className="vz-bar-t">
            <i style={{ width: `${r.value === null ? 0 : Math.max(0, Math.min(1, r.value / max)) * 100}%` }} />
          </span>
          <b className="vz-bar-v tnum">{r.right ?? (r.value === null ? '—' : `${Math.round(r.value * scale * 10) / 10}${unit}`)}</b>
          {r.note && <span className="vz-bar-n">{r.note}</span>}
        </div>
      ))}
    </div>
  );
}
