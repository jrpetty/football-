/**
 * Renders the "answer vs truth" visual for one stored result: loads the test
 * (for the answer key, notes and presentation fields), builds the visual
 * input and draws the registered visual. Renders nothing when the test has
 * no visual or its data does not parse, so the plain score view stands alone.
 */
import { Component, useEffect, useMemo, useState } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { api } from '../../api.ts';
import type { CaseResult, TestDetail } from '../../types.ts';
import { buildCaseInput, type CaseVisualInput } from '../../../../src/presenter/visuals/common.ts';
import { renderCaseVisual, visualFamilyFor, type VisualContext } from './caseVisuals.tsx';
import type { VizMode } from './VizFrame.tsx';

const detailCache = new Map<string, Promise<TestDetail | null>>();

/** GET /api/tests/:id once per page load (visuals for many cases share it). */
export function loadTestDetail(testId: string): Promise<TestDetail | null> {
  let p = detailCache.get(testId);
  if (!p) {
    p = api.test(testId).catch(() => null);
    detailCache.set(testId, p);
  }
  return p;
}

export function useTestDetail(testId: string | null | undefined): TestDetail | null | undefined {
  const [d, setD] = useState<TestDetail | null | undefined>(undefined);
  useEffect(() => {
    if (!testId) return;
    let alive = true;
    setD(undefined);
    void loadTestDetail(testId).then((x) => alive && setD(x));
    return () => {
      alive = false;
    };
  }, [testId]);
  return d;
}

/** The visual input for a result, using the test detail when it has the same prompt. */
export function caseInputFor(res: CaseResult, detail: TestDetail | null | undefined, modelLabel?: string): CaseVisualInput {
  const def = detail?.definition;
  const rendered = detail?.rendered.find((r) => r.caseId === res.caseId) ?? null;
  const c = def?.kind === 'prompt' ? def.cases.find((x) => x.id === res.caseId) : undefined;
  const scorer = def?.kind === 'prompt' ? (c?.scorer ?? def.scorer) : undefined;
  return buildCaseInput(res, rendered, {
    scorerType: scorer?.type ?? detail?.summary.scorerType,
    category: def?.category ?? detail?.summary.category,
    modelLabel,
    lure: c?.lure,
    displayAnswer: c?.displayAnswer,
    answerWithinSec: c?.answerWithinSec ?? (def?.kind === 'prompt' ? def.answerWithinSec : undefined),
  });
}

class VisualBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch(err: Error, info: ErrorInfo) {
    console.warn('Case visual failed; showing the plain view instead.', err, info.componentStack);
  }
  render() {
    return this.state.failed ? null : this.props.children;
  }
}

export function CaseVisual({ input, mode, ctx }: { input: CaseVisualInput; mode: VizMode; ctx?: VisualContext }) {
  const family = visualFamilyFor(input.testId, input.scorerType, input.category);
  const node = useMemo(() => (family ? renderCaseVisual(family, input, mode, ctx) : null), [family, input, mode, ctx]);
  if (!node) return null;
  return <VisualBoundary>{node}</VisualBoundary>;
}

/** ?plain=1 turns the visuals off (the plain score view, e.g. for before/after comparisons). */
function plainOnly(): boolean {
  try {
    return new URLSearchParams(window.location.search).get('plain') === '1' || /[?&]plain=1\b/.test(window.location.hash);
  } catch {
    return false;
  }
}

export function CaseVisualPanel({ res, mode = 'inspector', modelLabel, names }: { res: CaseResult; mode?: VizMode; modelLabel?: string; names?: Map<string, string> }) {
  const detail = useTestDetail(res.testId);
  const input = useMemo(() => (detail === undefined ? null : caseInputFor(res, detail, modelLabel)), [res, detail, modelLabel]);
  const ctx = useMemo<VisualContext>(() => ({ name: (id) => names?.get(id) ?? id }), [names]);
  if (!input || plainOnly() || res.status === 'skipped' || res.status === 'error' || res.status === 'cancelled') return null;
  return <CaseVisual input={input} mode={mode} ctx={ctx} />;
}
