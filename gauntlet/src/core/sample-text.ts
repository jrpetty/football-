/**
 * Pure text helpers for the viewer sample question (no Node imports, so the UI's
 * mock mode can use them too): trimming long prompts, the answer key in viewer
 * words, and the sample of a prompt test's first case.
 */
import type { PromptTest, PromptTestCase, ScorerSpec } from './types.ts';
import type { SampleImage, TestSample } from './explainers.ts';

/** Cut `text` to about `max` characters at a paragraph, line, sentence or word boundary. */
export function trimForViewer(text: string, max = 700): { text: string; truncated: boolean } {
  const t = text
    .replace(/\r\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/^(\s*\n)+/, '')
    .trimEnd();
  if (t.length <= max) return { text: t, truncated: false };
  const head = t.slice(0, max);
  const floor = Math.floor(max * 0.55);
  const cuts = [head.lastIndexOf('\n\n'), head.lastIndexOf('\n'), Math.max(head.lastIndexOf('. '), head.lastIndexOf('? '), head.lastIndexOf('! ')) + 1, head.lastIndexOf(' ')];
  const at = cuts.find((i) => i >= floor) ?? max;
  return { text: `${head.slice(0, at).trimEnd()} …`, truncated: true };
}

const LONG_PROMPT = 6000;

/** Four or more consecutive lines that differ only in their digits ("A1: <answer>", "A2: …") → first, "…", last. */
export function collapseRepeats(text: string): string {
  const lines = text.split('\n');
  const out: string[] = [];
  for (let i = 0; i < lines.length; ) {
    const shape = lines[i]!.replace(/\d+/g, '#');
    let j = i + 1;
    while (j < lines.length && shape.includes('#') && lines[j]!.replace(/\d+/g, '#') === shape) j++;
    if (j - i >= 4) out.push(lines[i]!, '…', lines[j - 1]!);
    else out.push(...lines.slice(i, j));
    i = j;
  }
  return out.join('\n');
}

/** Head (and, for very long prompts, the tail with the actual questions) of a prompt for a viewer. */
export function viewerExcerpt(raw: string, max: number): Pick<TestSample, 'text' | 'truncated' | 'tail' | 'skippedWords'> {
  if (raw.length <= LONG_PROMPT) return trimForViewer(raw, max);
  const head = trimForViewer(raw, Math.min(max, 420));
  const endRaw = collapseRepeats(raw.trimEnd());
  // Start the tail where the questions start ("Q1.", "1.", "Answer these…") when that is near the end.
  const window = endRaw.slice(-4000);
  const starts = [...window.matchAll(/^(?:Q1\b|1[.)]\s|Answer (?:these|the following))/gm)].map((m) => m.index!);
  const from = starts.length ? endRaw.length - window.length + starts[starts.length - 1]! : endRaw.length - 900;
  const nl = starts.length ? from - 1 : endRaw.indexOf('\n', from);
  const tail = trimForViewer(endRaw.slice(nl > 0 && nl < from + 400 ? nl + 1 : from), 1100).text;
  const headLen = head.text.replace(/ …$/, '').length;
  const middle = endRaw.slice(headLen, endRaw.length - tail.length);
  const skippedWords = middle.split(/\s+/).filter(Boolean).length;
  return { text: head.text.replace(/ …$/, ''), truncated: true, tail, skippedWords };
}

// ───────────────────────────── Answers (reveal only) ─────────────────────────────

function caseScorer(test: PromptTest, c: PromptTestCase): ScorerSpec {
  return c.scorer ?? test.scorer;
}

function short(s: string, max = 400): string {
  return trimForViewer(s, max).text;
}

/** The answer key of one prompt case in words, or null when there is no single answer (judged / rubric). */
export function answerForViewer(test: PromptTest, c: PromptTestCase): { answer: string; note: string } | null {
  const sc = caseScorer(test, c);
  const e = c.expected;
  if (c.displayAnswer) return { answer: short(c.displayAnswer), note: 'The answer key.' };
  switch (sc.type) {
    case 'number':
      return typeof e === 'number' ? { answer: String(e), note: sc.tolerance ? `Must match within ±${sc.tolerance}.` : 'Must match exactly.' } : null;
    case 'exact':
    case 'choice': {
      const list = (Array.isArray(e) ? e : [e]).filter((x) => typeof x === 'string') as string[];
      if (!list.length) return null;
      return { answer: short(list[0]!), note: list.length > 1 ? `Also accepted: ${list.length - 1} other spelling${list.length === 2 ? '' : 's'} of the same answer.` : 'Must match exactly.' };
    }
    case 'json':
      return e !== undefined
        ? {
            answer: short(JSON.stringify(e, null, 1).replace(/\n\s*/g, ' '), 600),
            note: sc.allOrNothing ? 'Every field must match; one wrong field scores zero.' : 'Each matching field earns its share of the points.',
          }
        : null;
    case 'constraints': {
      if (!Array.isArray(e)) return null;
      return { answer: `${e.length} rules, each checked by machine`, note: sc.allOrNothing ? 'Break any one rule and the answer scores zero.' : 'Points for each rule followed.' };
    }
    case 'code-js': {
      const x = e as { functionName?: string; tests?: unknown[] } | undefined;
      if (!x?.tests) return null;
      return { answer: `${x.tests.length} hidden unit tests call ${x.functionName ?? 'the function'}()`, note: 'Score = share of tests the code passes.' };
    }
    case 'judge-classify': {
      const x = e as { type?: string; reference?: string } | undefined;
      if (!x?.reference) return null;
      // The reference ends with grading notes for the judges ("CORRECT requires…"): keep the facts only.
      const cut = x.reference.search(/\b(CORRECT|CAUGHT_TRAP|PARTIAL|HALLUCINATED|OVER_REFUSAL|WRONG)\b/);
      const facts = cut > 40 ? x.reference.slice(0, cut).trim() : x.reference;
      return { answer: short(facts, 500), note: x.type === 'trap' ? 'A trap: the right move is to point out the problem.' : 'A real question: the right move is to answer it.' };
    }
    case 'regex':
      return null;
    case 'contains': {
      const x = e as { all?: string[] } | undefined;
      return x?.all?.length ? { answer: x.all.join(', '), note: 'Every one of these must be mentioned.' } : null;
    }
    case 'judge':
      return typeof e === 'string' ? { answer: short(e), note: 'A reference answer for the judges.' } : null;
    default:
      return null;
  }
}

/** The first case of a prompt test as a viewer sample; `images` are its pictures (resolved by the caller). */
export function promptSampleFrom(def: PromptTest, images: SampleImage[], reveal: boolean): TestSample {
  const c = def.cases[0];
  const raw = c?.prompt ?? c?.turns?.[0] ?? '';
  const shared = def.preamble || def.system || '';
  const key = c ? answerForViewer(def, c) : null;
  return {
    testId: def.id,
    kind: 'prompt',
    caseId: c?.id ?? '',
    caseCount: def.cases.length,
    situation: null,
    context: shared ? trimForViewer(shared, 320).text : null,
    ...(shared ? { contextKind: def.preamble ? ('preamble' as const) : ('system' as const) } : {}),
    ...viewerExcerpt(raw, 700),
    fullChars: raw.length,
    turns: c?.turns?.length ?? 1,
    images,
    hasAnswer: !!key,
    ...(reveal && key ? { answer: key.answer, answerNote: key.note } : {}),
  };
}
