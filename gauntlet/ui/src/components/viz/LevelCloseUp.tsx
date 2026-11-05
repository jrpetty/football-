/**
 * One level of a difficulty ladder up close: the question, a model's answer and the result.
 *
 * STUB: the real panel is being built separately and replaces this file (it loads its own data). Until then it
 * names the level and offers a way out. The climb (DifficultyLadder.tsx) shows it in a side sheet (full screen
 * on a phone) that handles Esc and focus.
 */
import type { JSX } from 'react';

export function LevelCloseUp(props: { testId: string; level: number; contestantId?: string; runId?: string | null; scope?: string; onClose: () => void; mode?: 'panel' | 'slide' }): JSX.Element {
  const { level, onClose } = props;
  return (
    <div className="lcu-stub" role="group" aria-label={`Level ${level}`}>
      <h2>Level {level}</h2>
      <button type="button" className="btn" onClick={onClose}>
        Close
      </button>
    </div>
  );
}
