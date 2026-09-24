/**
 * "Can It Be Fooled?" slide for the Episode Presenter: the trick question in
 * big type, then (reveal 1) what every model answered, then (reveal 2) the
 * tempting wrong answer vs the correct one and a ✓ / ✕ per model.
 *
 * Works on the 1920×1080 stage and on the 1080×1920 Shorts stage
 * (`vertical`); the layout switches from two columns to one.
 */
import { useLayoutEffect, useRef } from 'react';
import type { CSSProperties } from 'react';
import type { CategoryInfo } from '../../types.ts';
import type { TrickHighlight, TrickModelLine } from '../../../../src/presenter/trick-highlights.ts';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { fmtMs } from '../../format.ts';
import '../../styles/trick.css';

/** Reveal steps on a trick slide: 0 question · 1 answers · 2 verdicts. */
export const TRICK_REVEAL_STEPS = 2;

function questionSize(q: string, vertical: boolean): number {
  const n = q.length + (q.split('\n').length - 1) * 30;
  if (vertical) return n <= 110 ? 68 : n <= 220 ? 50 : n <= 360 ? 42 : n <= 520 ? 35 : 31;
  return n <= 110 ? 68 : n <= 220 ? 54 : n <= 360 ? 42 : n <= 520 ? 34 : 30;
}

/** Answer-card type size: long answers ("5 minutes (the classic answer)") step down so they fit on two lines. */
function cardSize(text: string): string {
  const n = text.length;
  return `${n <= 16 ? 40 : n <= 28 ? 32 : n <= 44 ? 27 : 23}px`;
}

function Verdict({ m, show }: { m: TrickModelLine; show: boolean }) {
  if (!show) return <span className="tk-v pending" aria-hidden="true" />;
  if (m.verdict === 'correct')
    return (
      <span className="tk-v good" role="img" aria-label="Correct">
        ✓
      </span>
    );
  if (m.verdict === 'out-of-time')
    return (
      <span className="tk-v late" role="img" aria-label="Out of time">
        <Icon.Clock />
      </span>
    );
  return (
    <span className="tk-v bad" role="img" aria-label={m.verdict === 'no-answer' ? 'No answer' : 'Fooled'}>
      ✕
    </span>
  );
}

function ModelRow({ m, reveal, i }: { m: TrickModelLine; reveal: number; i: number }) {
  const showAnswer = reveal >= 1;
  const showVerdict = reveal >= 2;
  const mixed = m.attempts > 1 && m.correct > 0 && m.correct < m.attempts;
  const answer =
    m.verdict === 'out-of-time' ? 'Out of time' : m.verdict === 'no-answer' ? 'No answer' : m.answer || (m.note === 'refused' ? 'Refused' : '—');
  return (
    <li
      className={cx('tk-m', showVerdict && `is-${m.verdict}`, m.baseline && 'is-base')}
      style={{ ['--c' as string]: m.baseline ? 'var(--text-3)' : m.color, ['--i' as string]: i } as CSSProperties}
    >
      <span className="tk-sw" aria-hidden="true" />
      <span className="tk-who">
        <b>{m.baseline ? 'Random guessing' : m.label}</b>
        <small className="tnum">
          {m.responseMs !== null ? fmtMs(m.responseMs) : '—'}
          {mixed && showVerdict ? ` · ${m.correct}/${m.attempts} right` : ''}
        </small>
      </span>
      <span className={cx('tk-ans', !showAnswer && 'hidden', m.verdict === 'out-of-time' && 'late')}>
        {showAnswer ? (
          <>
            <span className="tk-ans-t">{answer}</span>
            {showVerdict && m.tookBait && <em className="tk-bait">took the bait</em>}
            {showVerdict && m.note && m.note !== 'refused' && <em className="tk-bait">{m.note}</em>}
          </>
        ) : (
          <span className="tk-dots" aria-label="answer hidden">
            <i />
            <i />
            <i />
          </span>
        )}
      </span>
      <Verdict m={m} show={showVerdict} />
    </li>
  );
}

export function TrickSlide({ h, cat, reveal, index, total, vertical }: { h: TrickHighlight; cat: CategoryInfo; reveal: number; index: number; total: number; vertical: boolean }) {
  const models = [...h.models].sort((a, b) => Number(a.baseline) - Number(b.baseline));
  const real = models.filter((m) => !m.baseline && m.attempts > 0);
  const size = questionSize(h.question, vertical);
  const cardRef = useRef<HTMLDivElement>(null);
  const qRef = useRef<HTMLParagraphElement>(null);
  // Shrink the question until it fits its card (the card's size does not change between reveal steps).
  useLayoutEffect(() => {
    const card = cardRef.current;
    const q = qRef.current;
    if (!card || !q) return;
    let px = size;
    q.style.fontSize = `${px}px`;
    while (card.scrollHeight > card.clientHeight + 1 && px > 22) {
      px -= 2;
      q.style.fontSize = `${px}px`;
    }
  }, [h.question, size, vertical]);
  const showCards = reveal >= 2;
  const fooledShare = real.length ? h.fooled / real.length : 0;
  return (
    <div className={cx('s-trick', vertical && 'vert', models.length > 7 && 'dense')}>
      <div className="tk-top">
        <span className="pcat" style={{ ['--cc' as string]: cat.color } as CSSProperties}>
          <i />
          {cat.name}
        </span>
        <span className="tk-test">
          {h.testName} · trap {index} of {total}
        </span>
        {h.answerWithinSec !== null && (
          <span className="tk-clock">
            <Icon.Clock /> {h.answerWithinSec} s to answer
          </span>
        )}
      </div>

      <div className="tk-grid">
        <div className="tk-q-col">
          <div className="tk-q-card" ref={cardRef}>
            <div className="tk-q-k">The question</div>
            <p className="tk-q" ref={qRef} style={{ fontSize: size }}>
              {h.question}
            </p>
            {h.rule && <div className="tk-rule">{h.rule}</div>}
          </div>

          <div className={cx('tk-cards', !showCards && 'waiting')}>
            {showCards ? (
              <>
                {h.lure && (
                  <div className="tk-card lure" style={{ ['--vs' as string]: cardSize(h.lure) } as CSSProperties}>
                    <div className="k">
                      <span aria-hidden="true">✕</span> Tempting answer
                    </div>
                    <div className="v">{h.lure}</div>
                  </div>
                )}
                <div className="tk-card right" style={{ ['--vs' as string]: cardSize(h.correct) } as CSSProperties}>
                  <div className="k">
                    <span aria-hidden="true">✓</span> Correct answer
                  </div>
                  <div className="v">{h.correct}</div>
                </div>
              </>
            ) : (
              <div className="tk-think">
                <Icon.Target />
                {reveal === 0 ? 'What would you answer?' : 'Who fell for it?'}
              </div>
            )}
          </div>
        </div>

        <div className="tk-m-col">
          <div className="tk-m-head">
            <span>{reveal >= 1 ? 'What each model answered' : 'The models'}</span>
            {showCards && real.length > 0 && (
              <span className={cx('tk-tally', fooledShare >= 0.5 ? 'bad' : 'good')}>
                {h.fooled === 0 ? 'Nobody fooled' : `${h.fooled} of ${real.length} fooled`}
              </span>
            )}
          </div>
          <ol className="tk-models">
            {models.map((m, i) => (
              <ModelRow key={m.id} m={m} reveal={reveal} i={i} />
            ))}
          </ol>
        </div>
      </div>
    </div>
  );
}

/** Shown in Shorts mode when the run has no trick results. */
export function TrickEmptySlide() {
  return (
    <div className="s-trick vert-empty">
      <div className="p-empty">
        <Icon.Target />
        <h2>No “Can It Be Fooled?” results in this run</h2>
        <p>
          Run the “Can It Be Fooled?” suite, then open its Presenter with <code className="nowrap">?vertical=1</code> again.
        </p>
      </div>
    </div>
  );
}
