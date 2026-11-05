/**
 * Building blocks of the Level close-up: the three-beat layout (question · answer · result), the
 * "full prompt" / "raw reply" toggles and the verdict block. Every beat carries a text alternative for its
 * picture, read by screen readers in place of the drawing.
 */
import { useId, useState } from 'react';
import type { ReactNode } from 'react';
import type { LevelVerdict } from '../../../../../src/presenter/visuals/closeup.ts';
import { CopyButton, cx } from '../../ui.tsx';

export interface Beat {
  /** The picture. */
  node: ReactNode;
  /** One-paragraph text alternative of the picture. */
  alt: string;
  /** Small line under the beat title ("9 houses · 5 categories · 46 clues"). */
  sub?: ReactNode;
}

/** ✓ / ✕ / half-filled circle / dash, drawn (the half-circle glyph is missing from many fonts). */
export function Mark({ tone }: { tone: 'pass' | 'fail' | 'partial' | 'some' | 'none' }) {
  return (
    <svg className={cx('lc-mark', `m-${tone}`)} viewBox="0 0 24 24" aria-hidden="true">
      {tone === 'pass' && <path d="M4.5 12.5l4.8 4.8L19.5 7" />}
      {tone === 'fail' && <path d="M6 6l12 12M18 6L6 18" />}
      {(tone === 'partial' || tone === 'some') && (
        <>
          <circle cx="12" cy="12" r="8" />
          <path className="fill" d="M12 4a8 8 0 0 1 0 16z" />
        </>
      )}
      {tone === 'none' && <path d="M6 12h12" />}
    </svg>
  );
}

/** A labelled chip ("9 houses"). */
export function Chip({ children, tone, title }: { children: ReactNode; tone?: 'new' | 'good' | 'bad' | 'warn' | 'muted' | 'accent'; title?: string }) {
  return (
    <span className={cx('lc-chip', tone && `t-${tone}`)} title={title}>
      {children}
    </span>
  );
}

/** Pre-wrapped, scrollable, copyable text behind a button (the only monospace in the close-up). */
export function TextToggle({ label, hideLabel, text, note }: { label: string; hideLabel: string; text: string; note?: string }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  if (!text.trim()) return null;
  return (
    <div className="lc-toggle">
      <button type="button" className="btn xs ghost lc-toggle-btn" aria-expanded={open} aria-controls={id} onClick={() => setOpen((o) => !o)}>
        <span aria-hidden="true">{open ? '▾' : '▸'}</span> {open ? hideLabel : label}
        <span className="lc-toggle-n tnum">{text.length.toLocaleString('en-GB')} characters</span>
      </button>
      {open && (
        <div className="lc-raw" id={id}>
          <div className="lc-raw-bar">
            {note && <span className="muted">{note}</span>}
            <span className="spacer" />
            <CopyButton text={text} label="Copy" />
          </div>
          <pre tabIndex={0} aria-label={label.replace(/^Show /, '')}>{text}</pre>
        </div>
      )}
    </div>
  );
}

export function VerdictBlock({ verdict, sentence, extra }: { verdict: LevelVerdict; sentence: string | null; extra?: ReactNode }) {
  return (
    <div className={cx('lc-verdict', `v-${verdict.tone}`)}>
      <div className="lc-verdict-big" role="status">
        <span className="lc-verdict-mark" aria-hidden="true">
          <Mark tone={verdict.tone} />
        </span>
        <span className="lc-verdict-t">
          <b>{verdict.title}</b>
          {verdict.scoreText && <small>{verdict.scoreText}</small>}
        </span>
        {extra}
      </div>
      {sentence && <p className="lc-sentence">{sentence}</p>}
    </div>
  );
}

function BeatCard({ n, title, beat, className, head, foot }: { n: number; title: string; beat: Beat; className?: string; head?: ReactNode; foot?: ReactNode }) {
  const titleId = useId();
  return (
    <section className={cx('lc-beat', className)} aria-labelledby={titleId}>
      <header className="lc-beat-head">
        <span className="lc-beat-n" aria-hidden="true">
          {n}
        </span>
        <div className="lc-beat-t">
          <h3 id={titleId}>{title}</h3>
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
      </div>
      {foot && <div className="lc-beat-foot">{foot}</div>}
    </section>
  );
}

/**
 * The three beats. With no answer (question-only), the second beat is the answer key behind a
 * "Reveal the answer" button and there is no result beat.
 */
export function BeatLayout({
  question,
  prompt,
  answer,
  reply,
  result,
  verdict,
  sentence,
  answerHead,
  keyBeat,
  interactiveAnswer,
  interactiveResult,
  interactiveKey,
}: {
  question: Beat;
  /** The exact prompt the model saw. */
  prompt: string;
  answer: Beat | null;
  reply: string | null;
  result: Beat | null;
  verdict: LevelVerdict | null;
  sentence: string | null;
  /** Attempt tabs, shown in the answer beat's header. */
  answerHead?: ReactNode;
  /** The correct answer, for the question-only view. */
  keyBeat: Beat | null;
  /** Controls that must stay focusable (a step player, a code expander): rendered outside the picture. */
  interactiveAnswer?: ReactNode;
  interactiveResult?: ReactNode;
  interactiveKey?: ReactNode;
}) {
  const [revealed, setRevealed] = useState(false);
  const questionOnly = answer === null;
  return (
    <div className={cx('lc-beats', questionOnly && 'q-only')}>
      <BeatCard n={1} title="The question" beat={question} className="b-question" foot={<TextToggle label="Show the full prompt" hideLabel="Hide the full prompt" text={prompt} note="Exactly what the model was given" />} />
      {!questionOnly ? (
        <BeatCard
          n={2}
          title="The model’s answer"
          beat={answer}
          className="b-answer"
          head={answerHead}
          foot={
            <>
              {interactiveAnswer}
              <TextToggle label="Show the raw reply" hideLabel="Hide the raw reply" text={reply ?? ''} note="The model’s whole reply, as recorded" />
            </>
          }
        />
      ) : (
        <section className="lc-beat b-answer b-key" aria-label="The correct answer">
          <header className="lc-beat-head">
            <span className="lc-beat-n" aria-hidden="true">
              2
            </span>
            <div className="lc-beat-t">
              <h3>The correct answer</h3>
              <div className="lc-beat-sub">Hidden so you can try it first</div>
            </div>
          </header>
          <div className="lc-beat-body">
            {!keyBeat ? (
              <p className="lc-empty">The answer key for this level could not be drawn.</p>
            ) : revealed ? (
              <>
                <div className="lc-pic" role="img" aria-label={keyBeat.alt}>
                  <div aria-hidden="true" className="lc-pic-inner">
                    {keyBeat.node}
                  </div>
                </div>
                {interactiveKey}
              </>
            ) : (
              <button type="button" className="btn primary lc-reveal" onClick={() => setRevealed(true)}>
                Reveal the answer
              </button>
            )}
          </div>
        </section>
      )}
      {!questionOnly && verdict && (
        <section className={cx('lc-beat b-result', `v-${verdict.tone}`)} aria-label="The result">
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
            {result && (
              <div className="lc-pic" role="img" aria-label={result.alt}>
                <div aria-hidden="true" className="lc-pic-inner">
                  {result.node}
                </div>
              </div>
            )}
            {interactiveResult}
          </div>
        </section>
      )}
    </div>
  );
}

/** "+ 120 more" tail for lists that show only their first few items. */
export function More({ n, noun }: { n: number; noun: string }) {
  if (n <= 0) return null;
  return <div className="lc-more">+ {n.toLocaleString('en-GB')} more {noun}</div>;
}
