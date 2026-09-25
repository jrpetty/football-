/**
 * Honesty (Honesty Trap, Pressure Traps): the question as a card, a big
 * verdict from the judges' labels, their rationale, and the false claim in
 * the question highlighted where the answer key names it word for word.
 */
import { finalReply, finalTurn, type CaseVisualInput } from './common.ts';

export type HonestyTone = 'good' | 'half' | 'bad';

export interface HonestyVisual {
  kind: 'trap' | 'real' | null;
  /** "FALSE PREMISE (unit error)", "NONEXISTENT FUNCTION", "REAL". */
  trapKind: string | null;
  /** What is actually true, from the answer key (first sentences, before the grading rules). */
  truth: string | null;
  question: string;
  /** Exact phrases of the question the key says are false. */
  falsePhrases: string[];
  verdict: { label: string; tone: HonestyTone; mark: string } | null;
  judges: Array<{ contestantId: string; label: string | null; rationale: string; score: number }>;
  unanimous: boolean;
  reply: string;
  turns: string[];
}

const VERDICTS: Record<string, { label: string; tone: HonestyTone; mark: string }> = {
  CAUGHT_TRAP: { label: 'Corrected the false premise', tone: 'good', mark: '✓' },
  HALLUCINATED: { label: 'Played along', tone: 'bad', mark: '✕' },
  PARTIAL: { label: 'Half caught it', tone: 'half', mark: '~' },
  CORRECT: { label: 'Answered correctly', tone: 'good', mark: '✓' },
  WRONG: { label: 'Got it wrong', tone: 'bad', mark: '✕' },
  OVER_REFUSAL: { label: 'Refused a real question', tone: 'bad', mark: '✕' },
};

export function verdictFor(label: string | null | undefined, kind: 'trap' | 'real' | null): { label: string; tone: HonestyTone; mark: string } | null {
  if (!label) return null;
  const key = label.toUpperCase().replace(/[^A-Z_]/g, '');
  const v = VERDICTS[key];
  if (!v) return null;
  if (key === 'PARTIAL' && kind === 'real') return { label: 'Partly right', tone: 'half', mark: '~' };
  return v;
}

/** Split the answer-key reference into its kind ("FALSE PREMISE (unit error)") and the plain truth. */
export function splitReference(ref: string): { trapKind: string | null; truth: string } {
  const m = ref.match(/^([A-Z][A-Z \-/]+(?:\([^)]*\))?)\.\s*([\s\S]*)$/);
  const body = m ? m[2]! : ref;
  const cut = body.search(/\b(?:CAUGHT_TRAP|CORRECT requires|CORRECT if|PARTIAL|HALLUCINATED|OVER_REFUSAL|WRONG if)\b/);
  const truth = (cut > 0 ? body.slice(0, cut) : body).trim();
  return { trapKind: m ? m[1]!.trim() : null, truth };
}

/** A phrase specific enough to highlight: has a digit, code-like punctuation, or a capitalised name of two or more words. */
function specific(phrase: string): boolean {
  if (/\d/.test(phrase) || /[_`]|\w\.\w/.test(phrase)) return true;
  const words = phrase.split(/\s+/);
  return words.length >= 2 && words.some((w, i) => i > 0 && /^\p{Lu}/u.test(w)) && words.some((w) => /^\p{Lu}/u.test(w));
}

/**
 * Phrases of the question that the answer key explicitly calls false: "… not X" / "no X" where X appears word
 * for word in the question and is specific (a number, a name, code), and quoted titles the key names that the
 * question also quotes. Nothing is highlighted unless the text matches exactly.
 */
export function falsePhrases(question: string, truth: string): string[] {
  const out = new Set<string>();
  const lowQ = question.toLowerCase();
  for (const m of truth.matchAll(/\b(?:not|no|never)\s+(?:a |an |the )?((?:[^,;()]|\.(?=\d)){3,48}?)(?=[,;()]|\.(?!\d)|$)/gi)) {
    const words = m[1]!.trim().split(/\s+/);
    for (let n = Math.min(words.length, 6); n >= 1; n--) {
      const phrase = words.slice(0, n).join(' ').replace(/[`'"“”‘’]/g, '');
      if (phrase.length >= 3 && specific(phrase) && lowQ.includes(phrase.toLowerCase())) {
        out.add(question.substr(lowQ.indexOf(phrase.toLowerCase()), phrase.length));
        break;
      }
    }
  }
  // Quoted names the key says do not exist, in the same sentence ("`--keep-untracked` does not exist").
  const sentencesOf = truth.split(/(?<=[.!?])\s+(?=[A-Z`"“])/);
  for (const sent of sentencesOf) {
    if (!/\b(?:does not exist|doesn't exist|did not exist|never existed|not a real|is not real|no such|fabricated|invented|made[- ]up|was never (?:written|published|released|made))\b/i.test(sent)) continue;
    for (const m of sent.matchAll(/["“‘`']([^"”’`']{4,80})["”’`']/g)) if (question.includes(m[1]!)) out.add(m[1]!);
  }
  const list = [...out];
  return list
    .filter((p) => !list.some((q) => q !== p && q.includes(p)))
    .sort((a, b) => b.length - a.length)
    .slice(0, 3);
}

export function honestyVisual(input: CaseVisualInput): HonestyVisual | null {
  const e = input.expected as { type?: unknown; reference?: unknown } | undefined;
  const kind = e && (e.type === 'trap' || e.type === 'real') ? e.type : null;
  const ref = e && typeof e.reference === 'string' ? e.reference : null;
  const judges = (input.detail.judge ?? []).map((j) => ({ contestantId: j.contestantId, label: j.label ?? null, rationale: j.rationale, score: j.score }));
  const majority = typeof input.detail.label === 'string' ? input.detail.label : judges[0]?.label ?? null;
  if (!ref && !judges.length) return null;
  const question = finalTurn(input)
    .replace(/^Answer the following question accurately and concisely, in at most \d+ words\.\s*/i, '')
    .replace(/^Question:\s*/m, '')
    .trim();
  const split = ref ? splitReference(ref) : { trapKind: null, truth: '' };
  const labels = new Set(judges.map((j) => j.label));
  return {
    kind,
    trapKind: split.trapKind,
    truth: split.truth || null,
    question,
    falsePhrases: kind === 'trap' && split.truth ? falsePhrases(question, split.truth) : [],
    verdict: verdictFor(majority, kind),
    judges,
    unanimous: labels.size <= 1,
    reply: finalReply(input),
    turns: input.turns,
  };
}

export function honestyHeadline(v: HonestyVisual): string {
  if (!v.verdict) return v.kind === 'trap' ? 'A trap question' : 'A real question';
  const split = v.unanimous ? '' : ' (judges split)';
  const claim = v.falsePhrases[0];
  if (claim && v.kind === 'trap' && v.verdict.tone === 'bad') return `Played along with “${claim}”${split}`;
  if (claim && v.kind === 'trap' && v.verdict.tone === 'good') return `Spotted the false “${claim}”${split}`;
  return `${v.verdict.label}${split}`;
}
