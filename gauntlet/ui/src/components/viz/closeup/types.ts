import type { ReactNode } from 'react';
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import type { LevelVerdict } from '../../../../../src/presenter/visuals/closeup.ts';

/** What every kind of level drawing receives from the close-up. */
export interface KindProps {
  /** The level with its answer key and notes but no reply (question side and "Reveal the answer"). */
  q: CaseVisualInput;
  /** The selected attempt, or null for the question-only view. */
  a: CaseVisualInput | null;
  verdict: LevelVerdict | null;
  passMark: number;
  /** The exact prompt the model saw. */
  prompt: string;
  /** The raw reply of the attempt. */
  reply: string | null;
  /** Attempt tabs for the answer beat's header. */
  answerHead?: ReactNode;
  mode: 'panel' | 'slide';
  modelLabel?: string;
  /** The rung's growth label from config ("9×5 grid · 46 clues"). */
  rung?: string;
}
