/** "How it's scored" as a strip of icon + short phrase steps (from a test explainer). */
import type { ExplainStep } from '../../types.ts';
import { cx } from '../ui.tsx';
import { ExplainIcon } from './ExplainIcon.tsx';
import '../../styles/explain.css';

export function HowScoredStrip({ steps, size = 'md', title = 'How it’s scored' }: { steps: ExplainStep[]; size?: 'md' | 'slide' | 'sm'; title?: string | null }) {
  if (!steps.length) return null;
  return (
    <div className={cx('hstrip', `hstrip-${size}`)}>
      {title && <div className="hs-title">{title}</div>}
      <ol className="hs-steps" style={{ ['--n' as string]: steps.length }}>
        {steps.map((s, i) => (
          <li key={i} style={{ ['--i' as string]: i }} className={cx(`hs-${s.icon}`)}>
            <span className="hs-ic">
              <ExplainIcon name={s.icon} />
            </span>
            <span className="hs-tx">{s.text}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}
