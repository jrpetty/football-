/**
 * "Can It Be Fooled?" in the Result Inspector: the question, the tempting
 * wrong answer vs the correct one (the Presenter's trick-slide cards), and
 * this model's answer with its verdict and answer time.
 */
import type { CSSProperties } from 'react';
import { trickHeadline, type TrickVisual } from '../../../../src/presenter/visuals/trick.ts';
import { fmtMs } from '../../format.ts';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { VizFrame, type VizMode } from './VizFrame.tsx';
import '../../styles/trick.css';

function cardSize(text: string): string {
  const n = text.length;
  return `${n <= 16 ? 2.2 : n <= 28 ? 1.8 : n <= 44 ? 1.5 : 1.25}em`;
}

export function TrickAnswerCard({ v, mode }: { v: TrickVisual; mode: VizMode }) {
  const tone = v.verdict === 'correct' ? 'good' : 'bad';
  const late = v.verdict === 'out-of-time';
  const answerText = late ? 'Out of time' : v.answer || 'No answer';
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow={`Can it be fooled?${v.timeLimitSec ? ` · ${v.timeLimitSec} s to answer` : ''}`}
      headline={trickHeadline(v)}
      legend={[
        { tone: 'good', label: 'Correct answer' },
        ...(v.lure ? [{ tone: 'bad', label: 'Tempting wrong answer' }] : []),
      ]}
    >
      <div className="vz-trick">
        <div className="vz-trick-q">
          <div className="vz-card-k">The question</div>
          <p className="vz-q">{v.question}</p>
        </div>
        <div className="vz-trick-cards">
          <div className={cx('vz-trick-model', `v-${v.verdict}`)}>
            <div className="vz-card-k">
              This model answered
              {v.responseMs !== null && (
                <span className="vz-trick-time tnum">
                  <Icon.Clock /> {fmtMs(v.responseMs)}
                </span>
              )}
            </div>
            <div className="vz-trick-ans" style={{ fontSize: cardSize(answerText) } as CSSProperties}>
              {answerText}
            </div>
            {v.tookBait && <em className="tk-bait">took the bait</em>}
            {v.oneLineBroken && <em className="tk-bait">reply was not one line</em>}
          </div>
          {v.lure && (
            <div className="tk-card lure" style={{ ['--vs' as string]: cardSize(v.lure) } as CSSProperties}>
              <div className="k">
                <span aria-hidden="true">✕</span> Tempting answer
              </div>
              <div className="v">{v.lure}</div>
            </div>
          )}
          <div className="tk-card right" style={{ ['--vs' as string]: cardSize(v.correct) } as CSSProperties}>
            <div className="k">
              <span aria-hidden="true">✓</span> Correct answer
            </div>
            <div className="v">{v.correct}</div>
          </div>
        </div>
      </div>
    </VizFrame>
  );
}
