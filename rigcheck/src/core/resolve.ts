/**
 * Name-to-id resolution for anything the catalogue holds.
 *
 * Every command-line surface takes part names the way a person writes them —
 * "2060 super", "i7 7700", "fractal north" — and has to land on exactly one
 * catalogue id. Getting that wrong is not a cosmetic failure: a price filed
 * against the wrong part, or an analysis of a part the operator did not ask
 * about, is worse than no answer, because it looks like an answer.
 *
 * So the rules are deliberately strict. Every token in the query must appear
 * somewhere in the candidate. A query that is a whole name wins outright. A
 * near-tie is not broken by a coin flip — it is reported as ambiguous and the
 * caller is asked to say which.
 *
 * This lived inside scripts/price.ts. It moved here when a second command
 * needed the same behaviour, because two copies of a matcher drift and then
 * two commands disagree about what "titan x" means.
 */

export interface Candidate { id: string; label: string; score: number }

export type ResolveResult =
  | { ok: true; id: string; label: string }
  | { ok: false; reason: 'none'; query: string; candidates: [] }
  | { ok: false; reason: 'ambiguous'; query: string; candidates: Candidate[] };

/** Lowercase, strip punctuation to single spaces. "RTX 4060-Ti" -> "rtx 4060 ti". */
export const norm = (t: string) => t.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();

/**
 * A clear lead one candidate must have over the next to be chosen outright.
 * Below this the two names are close enough that picking one is a guess.
 */
export const LEAD = 10;

/**
 * Score every label against the query, best first.
 *
 * A candidate that misses any query token is dropped outright rather than
 * scored low — "titan xp" must not match the Titan X. Among survivors, a
 * whole-word hit counts for more than a substring one, shorter names are
 * preferred over longer ones carrying the same tokens, and words the label
 * has that the query never mentioned count against it: "fractal north" is the
 * North, not the North XL, and "rtx 3070" is not the 3070 Ti.
 */
export function rank(query: string, labels: Map<string, string>): Candidate[] {
  const tokens = norm(query).split(' ').filter(Boolean);
  if (!tokens.length) return [];
  const out: Candidate[] = [];
  for (const [id, label] of labels) {
    const hay = norm(`${label} ${id.replace(/[.-]/g, ' ')}`);
    let score = 0;
    let miss = false;
    for (const t of tokens) {
      const whole = new RegExp(`\\b${t}\\b`).test(hay);
      // A one- or two-character token matching anywhere is meaningless: "x"
      // is inside "rtx" and "gtx", so a query of "titan x" was scoring Titan
      // RTX above the actual Titan X. Short tokens must start a word — which
      // still reaches "Xp" from "x", the case that matters.
      const hit = whole || (t.length <= 2 ? new RegExp(`\\b${t}`).test(hay) : hay.includes(t));
      if (!hit) { miss = true; break; }
      score += whole ? 20 : 8;
    }
    if (miss) continue;
    const unmatched = norm(label).split(' ').filter((w) => w && !tokens.some((t) => w.includes(t))).length;
    out.push({ id, label, score: score + Math.max(0, 30 - hay.length / 2) - 12 * unmatched });
  }
  return out.sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
}

/**
 * Resolve one query to one id, or explain why it cannot be done.
 *
 * An exact whole-name match short-circuits the scoring entirely, so "Titan X"
 * resolves even though "Titan Xp" and "Titan X Pascal" both contain it. Where
 * several labels are exact — the same name under different vendors — the tie
 * is broken among those, not against the wider field.
 */
export function resolveOne(query: string, labels: Map<string, string>): ResolveResult {
  const q = norm(query);
  const hits = rank(query, labels);
  if (!hits.length) return { ok: false, reason: 'none', query, candidates: [] };
  const exact = hits.filter((h) => { const l = norm(h.label); return l === q || l.endsWith(` ${q}`); });
  if (exact.length === 1) return { ok: true, id: exact[0].id, label: exact[0].label };
  const pool = exact.length > 1 ? exact : hits;
  const [a, b] = pool;
  if (b && a.score - b.score < LEAD) return { ok: false, reason: 'ambiguous', query, candidates: pool.slice(0, 8) };
  return { ok: true, id: a.id, label: a.label };
}

/**
 * Resolve a query that is allowed to name several parts at once.
 *
 * "titan x 12gb" is not one card — the catalogue holds a 2015 Maxwell Titan X,
 * a 2016 Pascal Titan X and a 2017 Titan Xp, and they are 33% apart. A caller
 * comparing cards would rather see all three than be told to pick one blind,
 * so this returns the whole tied cluster instead of refusing. Callers that
 * must land on exactly one part still use resolveOne.
 */
export function resolveAll(query: string, labels: Map<string, string>): Candidate[] {
  const hits = rank(query, labels);
  if (!hits.length) return [];
  // Deliberately no exact-match short-circuit. "Titan X" is the exact name of
  // the 2015 Maxwell card, so resolveOne rightly picks it — but a caller that
  // accepts several parts is asking about the family, and silently dropping
  // the two Pascal cards that also carry the name is how you compare the
  // wrong hardware. Everything close to the top comes back; the caller shows
  // what it ran.
  // Every survivor of rank() matched every token in the query, so the survivor
  // set IS "everything the query completely describes": one card for "2060
  // super", three for "titan x", forty for "rtx". Returning all of them and
  // letting the caller refuse an unreasonable count keeps the judgement about
  // how many is too many where the caller's usage message can explain it.
  return hits;
}
