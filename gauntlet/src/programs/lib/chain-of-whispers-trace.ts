/**
 * Chain of Whispers — where each fact sits in a text, for the replay's "river"
 * view. Display only: scoring uses factPresent() alone and never this file.
 *
 *  - kept:    the fact is present (factPresent); `sentence` is the sentence that carries it.
 *  - changed: the fact no longer counts, but one sentence still shares some of
 *             its words (a name without its role, a different number next to the
 *             unit…); `sentence` is that closest sentence, so viewers can see how
 *             the wording drifted.
 *  - lost:    nothing recognisable is left.
 */
import type { FactSpec } from './chain-of-whispers-story.ts';
import { factPresent, splitSentences } from './chain-of-whispers-story.ts';
import { normalizeText } from './needle-haystack-normalize.ts';

export type FactStatus = 'kept' | 'changed' | 'lost';

export interface FactTrace {
  status: FactStatus;
  /** The sentence carrying the fact (kept) or the closest surviving wording (changed). */
  sentence?: string;
}

const STOP = new Set(['the', 'and', 'with', 'from', 'that', 'this', 'was', 'were', 'her', 'his', 'she', 'they', 'into', 'for']);

function tokensOf(alias: string): string[] {
  return normalizeText(alias)
    .split(' ')
    .filter((t) => t && !STOP.has(t) && (t.length >= 3 || /^\d+$/.test(t)));
}

/**
 * How closely a sentence echoes a fact: `groups` = keyword groups it touches
 * (a whole alias, a shared significant word, or — for a number group — any
 * other number, since a changed number is the classic drift), `score` = 2 per
 * whole alias and 1 per partial touch.
 */
function closeness(sentence: string, f: FactSpec): { groups: number; score: number } {
  const norm = normalizeText(sentence);
  const words = new Set(norm.split(' ').filter(Boolean));
  const padded = ` ${norm} `;
  let groups = 0;
  let score = 0;
  for (const group of f.groups) {
    const numeric = group.every((a) => /^\d+$/.test(normalizeText(a)));
    if (group.some((a) => padded.includes(` ${normalizeText(a)} `))) {
      groups++;
      score += 2;
    } else if (numeric ? [...words].some((w) => /^\d+$/.test(w)) : group.some((a) => tokensOf(a).some((t) => words.has(t)))) {
      groups++;
      score += 1;
    }
  }
  return { groups, score };
}

/** Traces one fact through one text. */
export function traceFact(text: string, f: FactSpec): FactTrace {
  const sentences = splitSentences(text);
  if (factPresent(text, f)) {
    const sentence = sentences.find((s) => factPresent(s, f));
    return { status: 'kept', sentence };
  }
  // A multi-part fact has drifted only when a sentence still touches at least two of its parts
  // ("40 passengers" for "37 passengers"); one shared word alone is too weak to call it the same fact.
  const need = Math.min(2, f.groups.length);
  let best: { s: string; score: number } | null = null;
  for (const s of sentences) {
    const c = closeness(s, f);
    if (c.groups >= need && (!best || c.score > best.score)) best = { s, score: c.score };
  }
  return best ? { status: 'changed', sentence: best.s } : { status: 'lost' };
}

/** Traces every fact through a text, keyed by fact id. */
export function traceFacts(text: string, facts: readonly FactSpec[]): Record<string, FactTrace> {
  return Object.fromEntries(facts.map((f) => [f.id, traceFact(text, f)]));
}
