/** One 30-word performance summary line, labelled with where it came from (built from the results, or written by an AI judge). */
import { cx } from '../components/ui.tsx';
import { money } from '../money.ts';
import type { ShownSummary } from './useSummaries.ts';
import './grading.css';

export function SummaryGlyph() {
  return (
    <svg viewBox="0 0 20 20" className="ps-glyph" aria-hidden="true">
      <path d="M4 5h12M4 9h12M4 13h8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" fill="none" />
      <circle cx="15.5" cy="14.5" r="2.5" fill="currentColor" />
    </svg>
  );
}

export function PerformanceSummary({ s, variant = 'row', label, color }: { s: ShownSummary | null; variant?: 'row' | 'card' | 'slide'; label?: string; color?: string }) {
  if (!s) return null;
  return (
    <div className={cx('ps', `ps-${variant}`)} style={color ? { ['--c' as string]: color } : undefined}>
      {variant !== 'slide' && <SummaryGlyph />}
      <div className="ps-body">
        {label && <span className="ps-who">{label}</span>}
        <span className="ps-text">{s.text}</span>
        {variant !== 'slide' && (
          <span className={cx('ps-src', s.source)} title={s.source === 'ai' ? `Written by ${s.writer ?? 'an AI judge'} from the recorded results${s.costUsd ? ` (${money(s.costUsd)})` : ''}` : 'Built automatically from the recorded scores, checks and judge notes (no AI, free)'}>
            {s.source === 'ai' ? `AI summary · ${s.writer ?? 'judge'}` : '30-word summary · from the results'}
          </span>
        )}
      </div>
    </div>
  );
}
