/**
 * Performance facts for the Studio script generator: one 30-word summary per
 * model × test, with names, ready to drop into a narration. Pure (the UI's mock
 * Studio uses it too); the server passes the exact summaries from station.ts.
 */
import type { StudioInput, StudioFact } from '../media/types.ts';
import { templateSummary } from './summary.ts';

function unitOf(t: StudioInput['tests'][number] | undefined): string {
  if (!t) return 'question';
  if (t.kind === 'program') return 'world';
  return t.scorerType === 'code-js' || t.scorerType === 'artifact' || t.scorerType === 'human' ? 'task' : 'question';
}

/** Facts from a Studio input (template summaries), optionally replaced by known texts keyed "contestantId|testId". */
export function studioFacts(input: StudioInput, known?: Array<{ contestantId: string; testId: string; text: string; source: 'template' | 'ai' }>): StudioFact[] {
  const tests = new Map(input.tests.map((t) => [t.id, t]));
  const labels = new Map(input.manifest.contestants.map((c) => [c.id, c.label]));
  const manual = new Set(input.manualIds ?? []);
  if (known) {
    return known
      .filter((k) => labels.has(k.contestantId) && tests.has(k.testId))
      .map((k) => ({ ...k, label: labels.get(k.contestantId)!, testName: tests.get(k.testId)!.name }));
  }
  const groups = new Map<string, StudioInput['results']>();
  for (const r of input.results) groups.set(`${r.contestantId}|${r.testId}`, [...(groups.get(`${r.contestantId}|${r.testId}`) ?? []), r]);
  const out: StudioFact[] = [];
  for (const [k, rs] of groups) {
    const [contestantId = '', testId = ''] = k.split('|');
    const t = tests.get(testId);
    if (!t || !labels.has(contestantId)) continue;
    out.push({ contestantId, testId, label: labels.get(contestantId)!, testName: t.name, text: templateSummary({ unit: unitOf(t), kind: t.kind, results: rs, manual: manual.has(contestantId) }), source: 'template' });
  }
  return out;
}
