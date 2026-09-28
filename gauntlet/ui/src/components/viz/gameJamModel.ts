/**
 * The Game Jam, as the screens need it: one "entry" per model per genre, built only from the recorded result
 * (scoreDetail.gameJam, the playtest screenshots and the score). Nothing here recomputes a score; it only
 * sorts, picks winners by the recorded numbers and turns states into plain words.
 */
import { JAM_GENRES, checklistLine, gameJamOf, type GameJamDetail, type JamGenre } from '../../../../src/scoring/game-jam-shared.ts';
import type { ArtifactRef, CaseResultLite } from '../../types.ts';

export const JAM_TEST_ID = 'creative.game-jam';

/** A result as any screen has it (full or lite). */
export type JamResult = Omit<CaseResultLite, 'hasReplay'>;

/** What the cabinet screen shows when there is no working game to show. */
export type JamState = 'ok' | 'frozen' | 'blank' | 'crashed' | 'out-of-space' | 'out-of-time' | 'no-game' | 'error' | 'pending';

export interface JamEntry {
  result: JamResult;
  genre: JamGenre;
  state: JamState;
  /** Filmstrip artifacts in time order, with their captions. */
  frames: Array<{ art: ArtifactRef; label: string; t: number }>;
  html: ArtifactRef | null;
  score: number | null;
  creativity: number | null;
  detail: GameJamDetail | null;
  /** One plain line for captions, e.g. "12 of 14 requirements met". */
  line: string;
  /** The judges' quoted one-sentence verdict (first judge that gave one). */
  quote: string | null;
}

export function isGameJamTest(testId: string): boolean {
  return testId === JAM_TEST_ID;
}

/** Does this result carry Game Jam data (or belong to the jam test)? */
export function hasJam(r: Pick<CaseResultLite, 'testId' | 'scoreDetail'>): boolean {
  return isGameJamTest(r.testId) || gameJamOf(r.scoreDetail) !== null;
}

export function stateOf(r: JamResult, d: GameJamDetail | null): JamState {
  if (r.status === 'timeout') return 'out-of-time';
  if (r.status === 'error' || r.status === 'cancelled') return 'error';
  if (r.status === 'pending-human') return 'pending';
  if (d?.truncated) return 'out-of-space';
  const pt = d?.playtest;
  if (!(r.artifacts ?? []).some((a) => a.kind === 'html')) return 'no-game';
  if (pt?.hung) return 'frozen';
  if (pt && !pt.drawsPicture.passed) return (r.scoreDetail?.items ?? []).some((i) => /JavaScript errors/.test(i.label) && !i.passed) ? 'crashed' : 'blank';
  return 'ok';
}

export const STATE_WORDS: Record<JamState, { stamp: string; line: string }> = {
  ok: { stamp: '', line: '' },
  frozen: { stamp: 'FROZE', line: 'The game locked up during the playtest (an endless loop)' },
  blank: { stamp: 'BLANK SCREEN', line: 'The game ran but never showed a picture' },
  crashed: { stamp: 'CRASHED', line: 'A code error stopped the game before it could draw' },
  'out-of-space': { stamp: 'RAN OUT OF SPACE', line: 'The reply hit the output limit, so the game file was cut off' },
  'out-of-time': { stamp: 'OUT OF TIME', line: 'No reply within the time limit' },
  'no-game': { stamp: 'NO GAME', line: 'The reply contained no playable game file' },
  error: { stamp: 'NO RESULT', line: 'The model call failed' },
  pending: { stamp: 'AWAITING REVIEW', line: 'Waiting for a human rating' },
};

export function entryOf(r: JamResult): JamEntry {
  const d = gameJamOf(r.scoreDetail);
  const genre = (d?.genre ?? JAM_GENRES[Math.max(0, Number(/^j(\d)/.exec(r.caseId)?.[1] ?? 1) - 1)]?.id ?? 'flappy') as JamGenre;
  const arts = r.artifacts ?? [];
  const frames = (d?.playtest?.frames ?? [])
    .map((f) => ({ art: arts.find((a) => a.name === f.name)!, label: f.label, t: f.t }))
    .filter((f) => f.art);
  const state = stateOf(r, d);
  const reqLine = d && d.requirements.some((q) => q.verdict) ? checklistLine(d.requirements) : '';
  return {
    result: r,
    genre,
    state,
    frames,
    html: arts.find((a) => a.kind === 'html') ?? null,
    score: r.score,
    creativity: d?.criteria.creativity ?? null,
    detail: d,
    line: state === 'ok' ? reqLine || r.summary : STATE_WORDS[state].line,
    quote: d?.judges.find((j) => j.verdict)?.verdict ?? null,
  };
}

/** Higher score wins; a tie goes to the more creative game (the jam's deciding factor). */
export function compareEntries(a: JamEntry, b: JamEntry): number {
  return (b.score ?? -1) - (a.score ?? -1) || (b.creativity ?? -1) - (a.creativity ?? -1);
}

export interface JamBoard {
  genres: typeof JAM_GENRES;
  models: string[];
  /** entries[model][genre] (first repeat). */
  entries: Map<string, Map<JamGenre, JamEntry>>;
  /** Per model: mean jam score and mean creativity over its recorded games. */
  totals: Map<string, { score: number | null; creativity: number | null; games: number; wins: number }>;
  genreWinner: Map<JamGenre, string | null>;
  /** Model with the best mean score (ties: mean creativity). Null when nothing was scored. */
  gameOfTheJam: { model: string; entry: JamEntry } | null;
}

const mean = (xs: number[]) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);

/** The board for a run: every model × genre, the genre winners and the Game of the Jam. */
export function jamBoard(results: CaseResultLite[], modelOrder: string[]): JamBoard {
  const entries = new Map<string, Map<JamGenre, JamEntry>>();
  for (const r of results) {
    if (!isGameJamTest(r.testId) || r.repeat !== 0) continue;
    const e = entryOf(r);
    let row = entries.get(r.contestantId);
    if (!row) entries.set(r.contestantId, (row = new Map()));
    row.set(e.genre, e);
  }
  const models = modelOrder.filter((m) => entries.has(m));
  const genreWinner = new Map<JamGenre, string | null>();
  const wins = new Map<string, number>();
  for (const g of JAM_GENRES) {
    const cands = models.map((m) => entries.get(m)!.get(g.id)).filter((e): e is JamEntry => !!e && e.score !== null && e.score > 0);
    const best = cands.sort(compareEntries)[0];
    genreWinner.set(g.id, best ? best.result.contestantId : null);
    if (best) wins.set(best.result.contestantId, (wins.get(best.result.contestantId) ?? 0) + 1);
  }
  const totals = new Map<string, { score: number | null; creativity: number | null; games: number; wins: number }>();
  for (const m of models) {
    const row = [...entries.get(m)!.values()];
    totals.set(m, {
      score: mean(row.filter((e) => e.score !== null).map((e) => e.score!)),
      creativity: mean(row.filter((e) => e.creativity !== null).map((e) => e.creativity!)),
      games: row.filter((e) => e.state === 'ok').length,
      wins: wins.get(m) ?? 0,
    });
  }
  const ranked = models.filter((m) => totals.get(m)!.score !== null && totals.get(m)!.score! > 0).sort((a, b) => (totals.get(b)!.score! - totals.get(a)!.score!) || ((totals.get(b)!.creativity ?? 0) - (totals.get(a)!.creativity ?? 0)));
  const top = ranked[0];
  const topEntry = top ? [...entries.get(top)!.values()].sort(compareEntries)[0] : undefined;
  return { genres: JAM_GENRES, models, entries, totals, genreWinner, gameOfTheJam: top && topEntry ? { model: top, entry: topEntry } : null };
}

/** Judge id → readable name (real runs store judges as "<id>@judge"). */
export function judgeName(id: string, names?: Map<string, string>): string {
  return names?.get(id) ?? names?.get(id.replace(/@judge$/, '')) ?? id.replace(/@judge$/, '');
}

export const pct100 = (x: number | null | undefined) => (typeof x === 'number' ? Math.round(x * 100) : null);
