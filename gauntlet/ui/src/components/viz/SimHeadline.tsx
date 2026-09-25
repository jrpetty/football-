/**
 * The big plain-English headline for a replay step ("Day 5 · Afternoon:
 * finds fresh water"), colour-coded good / bad / neutral, with the one-line
 * "What's happening" narration underneath.
 */
import { cx } from '../ui.tsx';
import type { StepStory } from './simStory.ts';

const TONE_LABEL = { good: 'Good move', bad: 'Setback', neutral: 'What’s happening' } as const;

export function SimHeadline({ story, stepKey }: { story: StepStory; stepKey: number | string }) {
  return (
    <div className={cx('sim-headline', `tone-${story.tone}`)} aria-live="polite">
      <div className="sh-top" key={stepKey}>
        <span className="sh-tag">
          {story.tone === 'good' ? '▲ ' : story.tone === 'bad' ? '▼ ' : ''}
          {TONE_LABEL[story.tone]}
        </span>
        <h4 className="sh-title">{story.headline}</h4>
      </div>
      {story.line && <p className="sh-line">{story.line}</p>}
    </div>
  );
}
