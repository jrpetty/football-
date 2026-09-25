/**
 * Maths: the problem as a clean card (or a receipt / payslip / bill for money
 * word problems), the model's final answer vs the key as big numbers, and
 * the model's working, collapsible.
 */
import { useCountUp } from '../../hooks.ts';
import { mathsHeadline, type MathsVisual } from '../../../../src/presenter/visuals/maths.ts';
import { cx } from '../ui.tsx';
import { ToneMark, VizFrame, type VizMode } from './VizFrame.tsx';
import { TypesetMath } from './TypesetMath.tsx';

const PAPER_TITLE = { receipt: 'Receipt', payslip: 'Payslip', bill: 'Bill' } as const;

function BigNumber({ value, text }: { value: number | null; text: string }) {
  // Count up only for plain numbers the model wrote as digits; otherwise show the text as given.
  const plain = value !== null && Number.isFinite(value) && /^-?[\d,]*\.?\d+$/.test(text.replace(/[$£€\s]/g, ''));
  const decimals = plain ? (text.split('.')[1]?.replace(/\D/g, '').length ?? 0) : 0;
  const shown = useCountUp(plain ? value! : 0, 900);
  return <b className="tnum">{plain ? shown.toFixed(decimals) : text || '—'}</b>;
}

export function MathProblemCard({ v, mode }: { v: MathsVisual; mode: VizMode }) {
  const tone = v.ok ? 'good' : 'bad';
  const paper = v.paper;
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow={paper ? `Word problem · ${PAPER_TITLE[paper].toLowerCase()}` : 'Maths problem'}
      headline={mathsHeadline(v)}
      legend={[
        { tone: 'good', label: 'Answer key' },
        { tone: tone === 'good' ? 'good' : 'bad', label: 'Model’s final answer' },
        ...(paper ? [{ tone: 'count', label: 'Amounts in the problem' }] : []),
      ]}
    >
      <div className={cx('vz-math', paper && 'paper')}>
        <article className={cx('vz-problem', paper && `paper-${paper}`)}>
          {paper && <div className="vz-paper-title">{PAPER_TITLE[paper]}</div>}
          {v.paragraphs.map((p, i) => (
            <p key={i} className={cx(i === v.askIndex && 'ask')}>
              <TypesetMath text={p} money={!!paper} />
            </p>
          ))}
        </article>
        <div className="vz-answers">
          <div className={cx('vz-ans', v.ok ? 'good' : 'bad')}>
            <span>Model’s final answer</span>
            <BigNumber value={v.givenValue} text={v.given ?? ''} />
            <ToneMark tone={v.ok ? 'good' : 'bad'} />
          </div>
          <div className="vz-ans key">
            <span>Answer key</span>
            <BigNumber value={v.key} text={v.keyText} />
          </div>
        </div>
      </div>
      {v.working && mode === 'inspector' && (
        <details className="vz-working">
          <summary>Show the model’s working ({v.working.length.toLocaleString()} characters)</summary>
          <pre>{v.working}</pre>
        </details>
      )}
    </VizFrame>
  );
}
