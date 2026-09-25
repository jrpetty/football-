/**
 * Running chip count across the duplicate pair: the same deals are played
 * twice with the cards swapped, so the two lines (game 1 and game 2, both from
 * the first player's point of view) show how much of the result is skill and
 * how much is the cards. Only games already finished before the one being
 * replayed are drawn in full, so the chart never spoils a later game.
 */
import { LineChart, type LineSeries } from '../components/charts/LineChart.tsx';
import type { MatchState, PokerSnapshot } from './types.ts';

function cumulative(results: PokerSnapshot['results'], seat: 0 | 1): Array<{ x: number; y: number }> {
  let sum = 0;
  const pts = [{ x: 0, y: 0 }];
  for (const r of results) {
    sum += r.delta[seat];
    pts.push({ x: r.no + 1, y: sum });
  }
  return pts;
}

export function PokerPairChart({ match, gameKey, snap, labelOf }: { match: MatchState; gameKey: string; snap: PokerSnapshot; labelOf: (id: string) => { label: string; color: string } }) {
  const hero = match.players[0];
  if (!hero || !snap || snap.kind !== 'poker') return null;
  const me = match.games.find((s) => s.key === gameKey);
  if (!me) return null;
  const h = labelOf(hero);
  const series: LineSeries[] = [];
  // The same deals, played earlier in this pair with the cards the other way round.
  const pairNo = Math.ceil(me.gameNo / 2);
  for (const s of match.games) {
    if (s.key === gameKey || Math.ceil(s.gameNo / 2) !== pairNo || s.gameNo > me.gameNo || s.suddenDeath) continue;
    const ls = s.game?.lastSnapshot as PokerSnapshot | undefined;
    if (!ls || ls.kind !== 'poker' || !s.game) continue;
    const seat = s.game.players[0] === hero ? 0 : 1;
    series.push({ name: `Game ${s.gameNo} (${seat === 0 ? 'seat 1' : 'seat 2'})`, color: 'var(--text-3)', points: cumulative(ls.results, seat) });
  }
  const seat = me.players[0] === hero ? 0 : 1;
  series.push({ name: `Game ${me.gameNo} so far (${seat === 0 ? 'seat 1' : 'seat 2'})`, color: h.color, points: cumulative(snap.results, seat) });
  const done = snap.results.length;
  return (
    <div className="ar-insight">
      <div className="bt-head">
        <span className="bt-t">{h.label}’s chips, hand by hand</span>
        <span className="muted" style={{ fontSize: '0.8rem' }}>
          {series.length > 1 ? 'same deals, cards swapped' : `${done} of ${snap.hands} hands`}
        </span>
      </div>
      <LineChart title={`${h.label}'s running chip count across the duplicate pair`} series={series} marker={done} xLabel="hand" yLabel="chips" height={170} />
      <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
        Above zero = {h.label} is up. {series.length > 1 ? `The grey line is the earlier game with the same deals: if the two lines mirror each other, the cards decided it; if both finish above zero, ${h.label} out-played the opponent with either set of cards.` : 'Game 2 replays these deals with the cards swapped.'}
      </p>
    </div>
  );
}
