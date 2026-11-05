/**
 * The paperwork kit of the people-and-money visuals: a sheet of paper, a rubber stamp, a highlighter mark with a
 * numbered margin marker, ticks and crosses, a business-impact line, and the three-beat layout these documents
 * use in the close-up (the question across the top, the model's answer and the result side by side below it).
 */
import { useId, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { TextToggle, VerdictBlock } from '../../../components/viz/closeup/parts.tsx';
import type { LevelVerdict } from '../../../../../src/presenter/visuals/closeup.ts';
import './docs.css';

export type Tone = 'good' | 'bad' | 'warn' | 'key' | 'muted' | 'info';

/** A sheet of paper (stays paper-coloured in the dark theme too, like a real document on a desk). */
export function Paper({ children, className, label, style, tilt }: { children: ReactNode; className?: string; label?: string; style?: CSSProperties; tilt?: number }) {
  return (
    <div className={cx('pm-paper', className)} aria-label={label} style={{ ...style, ...(tilt ? { ['--tilt' as string]: `${tilt}deg` } : null) }}>
      {children}
    </div>
  );
}

/** A rubber stamp: "SHORTLIST ✓", "PAY £1,284.00", "HOLD — price". */
export function Stamp({ tone, children, sub, small, className }: { tone: Tone; children: ReactNode; sub?: ReactNode; small?: boolean; className?: string }) {
  return (
    <span className={cx('pm-stamp', `t-${tone}`, small && 'sm', className)}>
      <b>{children}</b>
      {sub && <small>{sub}</small>}
    </span>
  );
}

/** Highlighter over a piece of a document, with an optional numbered marker. */
export function Hl({ tone, n, children, title }: { tone: Tone; n?: number | string; children: ReactNode; title?: string }) {
  return (
    <mark className={cx('pm-hl', `t-${tone}`)} title={title}>
      {n !== undefined && <i className={cx('pm-pin', `t-${tone}`)}>{n}</i>}
      {children}
    </mark>
  );
}

/** A numbered pin (the same number as the form field it belongs to). */
export function Pin({ tone, n }: { tone: Tone; n: number | string }) {
  return <i className={cx('pm-pin', `t-${tone}`)}>{n}</i>;
}

/** ✓ / ✕ / – drawn (fonts lack some glyphs). */
export function Tick({ ok, size }: { ok: boolean | null; size?: 'sm' | 'lg' }) {
  return (
    <svg className={cx('pm-tick', ok === null ? 't-muted' : ok ? 't-good' : 't-bad', size)} viewBox="0 0 24 24" aria-hidden="true">
      {ok === null ? <path d="M6 12h12" /> : ok ? <path d="M4.5 12.5l4.8 4.8L19.5 7" /> : <path d="M6 6l12 12M18 6L6 18" />}
    </svg>
  );
}

/** "Would have paid £277.71 on a duplicate invoice": the money (or people) consequence, from the case data only. */
export function Impact({ tone, children }: { tone: 'good' | 'bad' | 'warn'; children: ReactNode }) {
  return (
    <p className={cx('pm-impact', `t-${tone}`)}>
      <svg viewBox="0 0 24 24" aria-hidden="true">
        {tone === 'good' ? <path d="M4.5 12.5l4.8 4.8L19.5 7" /> : <path d="M12 3l10 18H2zM12 10v5M12 18v.5" />}
      </svg>
      <span>{children}</span>
    </p>
  );
}

/** A small labelled count ("3 of 12 shortlisted"). */
export function Fact({ k, v, tone }: { k: ReactNode; v: ReactNode; tone?: Tone }) {
  return (
    <span className={cx('pm-fact', tone && `t-${tone}`)}>
      <b>{v}</b>
      <span>{k}</span>
    </span>
  );
}

/** One beat of the close-up: a picture with its text alternative, and focusable controls outside the picture. */
export interface DocBeat {
  title: string;
  sub?: ReactNode;
  node: ReactNode;
  alt: string;
  /** Controls (pickers, "open this document") that must stay focusable: drawn under the picture. */
  controls?: ReactNode;
}

function Card({ n, beat, className, head, foot }: { n: number; beat: DocBeat; className?: string; head?: ReactNode; foot?: ReactNode }) {
  const id = useId();
  return (
    <section className={cx('lc-beat pm-beat', className)} aria-labelledby={id}>
      <header className="lc-beat-head">
        <span className="lc-beat-n" aria-hidden="true">
          {n}
        </span>
        <div className="lc-beat-t">
          <h3 id={id}>{beat.title}</h3>
          {beat.sub && <div className="lc-beat-sub">{beat.sub}</div>}
        </div>
        {head}
      </header>
      <div className="lc-beat-body">
        <div className="lc-pic" role="img" aria-label={beat.alt}>
          <div aria-hidden="true" className="lc-pic-inner">
            {beat.node}
          </div>
        </div>
        {beat.controls}
      </div>
      {foot && <div className="lc-beat-foot">{foot}</div>}
    </section>
  );
}

/**
 * The close-up for a document case: the paperwork across the top; below it the model's answer drawn on the same
 * paperwork and the result. With no answer chosen, the correct answer waits behind "Reveal the answer".
 */
export function DocBeats({
  question,
  answer,
  result,
  keyBeat,
  verdict,
  sentence,
  impact,
  prompt,
  reply,
  answerHead,
  wideAnswer,
}: {
  question: DocBeat;
  answer: DocBeat | null;
  result: DocBeat | null;
  keyBeat: DocBeat | null;
  verdict: LevelVerdict | null;
  sentence: string | null;
  impact?: ReactNode;
  prompt: string;
  reply: string | null;
  answerHead?: ReactNode;
  /** The answer is a wide sheet: it spans the full width, with the result below it. */
  wideAnswer?: boolean;
}) {
  const [revealed, setRevealed] = useState(false);
  const qOnly = answer === null;
  return (
    <div className={cx('pm-beats', qOnly && 'q-only')}>
      <Card n={1} beat={question} className="b-question pm-wide" foot={<TextToggle label="Show the full prompt" hideLabel="Hide the full prompt" text={prompt} note="Exactly what the model was given" />} />
      {!qOnly ? (
        <>
          <Card n={2} beat={answer} className={cx('b-answer', wideAnswer && 'pm-wide')} head={answerHead} foot={<TextToggle label="Show the raw reply" hideLabel="Hide the raw reply" text={reply ?? ''} note="The model’s whole reply, as recorded" />} />
          {verdict && (
            <section className={cx('lc-beat b-result pm-beat', `v-${verdict.tone}`, wideAnswer && 'pm-wide pm-result-wide')} aria-label="The result">
              <header className="lc-beat-head">
                <span className="lc-beat-n" aria-hidden="true">
                  3
                </span>
                <div className="lc-beat-t">
                  <h3>The result</h3>
                </div>
              </header>
              <div className="lc-beat-body">
                <VerdictBlock verdict={verdict} sentence={sentence} />
                {impact}
                {result && (
                  <>
                    <div className="lc-pic" role="img" aria-label={result.alt}>
                      <div aria-hidden="true" className="lc-pic-inner">
                        {result.node}
                      </div>
                    </div>
                    {result.controls}
                  </>
                )}
              </div>
            </section>
          )}
        </>
      ) : (
        <section className="lc-beat b-answer b-key pm-beat pm-wide" aria-label="The correct answer">
          <header className="lc-beat-head">
            <span className="lc-beat-n" aria-hidden="true">
              2
            </span>
            <div className="lc-beat-t">
              <h3>{keyBeat?.title ?? 'The correct answer'}</h3>
              <div className="lc-beat-sub">{revealed ? keyBeat?.sub : 'Hidden so you can try it first'}</div>
            </div>
          </header>
          <div className="lc-beat-body">
            {!keyBeat ? (
              <p className="lc-empty">The answer key for this case could not be drawn.</p>
            ) : revealed ? (
              <>
                <div className="lc-pic" role="img" aria-label={keyBeat.alt}>
                  <div aria-hidden="true" className="lc-pic-inner">
                    {keyBeat.node}
                  </div>
                </div>
                {keyBeat.controls}
              </>
            ) : (
              <button type="button" className="btn primary lc-reveal" onClick={() => setRevealed(true)}>
                Reveal the answer
              </button>
            )}
          </div>
        </section>
      )}
    </div>
  );
}

/** A row of small buttons that pick one document of a pile ("C01 Pooja", "DOC-30212"): tone right/wrong. */
export function DocPicker<T extends string>({ items, value, onPick, label }: { items: Array<{ id: T; label: ReactNode; tone?: Tone; title?: string }>; value: T | null; onPick: (id: T) => void; label: string }) {
  return (
    <div className="pm-picker" role="group" aria-label={label}>
      {items.map((it) => (
        <button key={it.id} type="button" className={cx('pm-pick', it.tone && `t-${it.tone}`, value === it.id && 'on')} aria-pressed={value === it.id} title={it.title} onClick={() => onPick(it.id)}>
          {it.label}
        </button>
      ))}
    </div>
  );
}

/** "Demo data — not real results" for the inspector pictures in demo mode. */
export function DemoNote({ show }: { show: boolean }) {
  if (!show) return null;
  return <p className="pm-demo">Demo data — not real results: the documents and answer key are the real ones; the model’s answer is invented.</p>;
}
