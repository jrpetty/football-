/**
 * The registry entry of a people-and-money document visual: the same shape as an entry of the case-visual
 * registry (components/viz/caseVisuals.tsx) — family, caption, build, render — plus `Beats`, the Level close-up
 * drawing (question, answer, result), which also draws the question-only view (no answer chosen) with the
 * correct answer behind "Reveal the answer".
 */
import type { ReactNode } from 'react';
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import type { CaseVisualFamily, VisualContext } from '../../../components/viz/caseVisuals.tsx';
import type { VizMode } from '../../../components/viz/VizFrame.tsx';
import type { KindProps } from '../../../components/viz/closeup/types.ts';

export interface DocEntry {
  family: CaseVisualFamily;
  caption: string;
  build: (input: CaseVisualInput) => unknown | null;
  render: (model: unknown, mode: VizMode, input: CaseVisualInput, ctx: VisualContext) => ReactNode;
  Beats: (p: KindProps) => JSX.Element;
}
