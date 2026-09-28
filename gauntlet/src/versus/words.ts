/**
 * Head to Head — plain-English lines built from the data (no ids, nothing
 * invented). Shared by the page, the Presenter deck and the Shorts card.
 */
import type { VersusData, VersusFighter, VersusRound } from './types.ts';

export function nameOf(f: VersusFighter): string {
  return f.baseline ? 'Random guessing' : f.label;
}

/** "7–4", winner first (a draw shows both equal). */
export function tally(d: VersusData): string {
  const a = d.totals.a.roundsWon;
  const b = d.totals.b.roundsWon;
  return `${Math.max(a, b)}–${Math.min(a, b)}`;
}

/** "Nova 3 Pro wins 7–4" / "It's a draw, 5–5" / "No shared tests yet". */
export function resultHeadline(d: VersusData): string {
  if (!d.rounds.length) return 'No shared tests yet';
  if (d.winner === 'tie') return `It’s a draw, ${tally(d)}`;
  return `${nameOf(d[d.winner])} wins ${tally(d)}`;
}

/** One line under the headline: ties and the average score. */
export function resultSub(d: VersusData): string {
  const n = d.rounds.length;
  const parts = [`${n} ${n === 1 ? 'test' : 'tests'} both models took`];
  if (d.ties) parts.push(`${d.ties} ${d.ties === 1 ? 'draw' : 'draws'}`);
  const aa = d.totals.a.avgScore;
  const bb = d.totals.b.avgScore;
  if (aa !== null && bb !== null) parts.push(`average score ${Math.round(aa)} vs ${Math.round(bb)}`);
  return parts.join(' · ');
}

/** "Nova 3 Pro wins by 18 points" / "Draw: less than 2 points apart". */
export function roundVerdict(r: VersusRound, d: VersusData): string {
  if (r.winner === 'tie') return r.margin === 0 ? 'Draw: identical scores' : `Draw: less than ${d.tieMargin} points apart`;
  const m = Math.round(r.margin);
  return `${nameOf(d[r.winner])} wins by ${m} ${m === 1 ? 'point' : 'points'}`;
}

/** "On the same question, Nova 3 Pro got it right and Sable Large got it wrong." */
export function momentLine(r: VersusRound, d: VersusData): string | null {
  if (!r.moment) return null;
  const right = nameOf(d[r.moment.right]);
  const wrong = nameOf(d[r.moment.right === 'a' ? 'b' : 'a']);
  return `Same question: ${right} got it right, ${wrong} got it wrong.`;
}

/** Tokens → "400K tokens" / "1M tokens". */
export function contextWords(n: number | null): string {
  if (!n) return 'not recorded';
  if (n >= 1_000_000) return `${Number((n / 1_000_000).toFixed(n % 1_000_000 ? 1 : 0))}M tokens`;
  return `${Math.round(n / 1000)}K tokens`;
}
