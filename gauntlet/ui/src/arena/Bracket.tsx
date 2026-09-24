/**
 * Knockout bracket that fills in live (winners slide into the next round),
 * and the round-robin standings + results grid.
 */
import type { CSSProperties } from 'react';
import { Link, pathOf } from '../router.tsx';
import { cx } from '../components/ui.tsx';
import { fmtCost } from '../format.ts';
import type { ArenaEntrant, GameSlot, MatchState, TournamentDetail } from './types.ts';
import { entrantMap, pts } from './useTournament.ts';

function gameMark(slot: GameSlot, player: string): { cls: string; label: string } {
  const g = slot.game;
  if (!g) return { cls: 'todo', label: 'not played yet' };
  if (g.winner === null) return { cls: 'draw', label: 'draw' };
  return g.players[g.winner] === player ? { cls: 'win', label: 'win' } : { cls: 'loss', label: 'loss' };
}

function PlayerRow({ e, m, idx, liveKeys }: { e: ArenaEntrant | undefined; m: MatchState; idx: 0 | 1; liveKeys: Set<string> }) {
  const id = m.players[idx];
  const winner = m.winner && id === m.winner;
  const loser = m.winner && id && id !== m.winner;
  if (!id) {
    return (
      <div className="bk-row tbd">
        <span className="bk-seed" />
        <span className="bk-name">{m.status === 'bye' ? 'bye' : 'to be decided'}</span>
      </div>
    );
  }
  return (
    <div className={cx('bk-row', winner && 'won', loser && 'lost')} key={id}>
      <span className="bk-seed tnum">{e?.seed ?? ''}</span>
      <span className="bk-bar" style={{ background: e?.color }} aria-hidden="true" />
      <span className="bk-name" title={e?.label ?? id}>
        {e?.label ?? id}
      </span>
      {m.status !== 'bye' && (
        <span className="bk-games" aria-label="game results">
          {m.games.map((s) => {
            const mk = gameMark(s, id);
            return <i key={s.key} className={cx(mk.cls, liveKeys.has(s.key) && 'live', s.suddenDeath && 'sd')} title={`Game ${s.gameNo}${s.suddenDeath ? ' (sudden death)' : ''}: ${liveKeys.has(s.key) ? 'in progress' : mk.label}`} />;
          })}
        </span>
      )}
      {m.status !== 'bye' && m.games.some((s) => s.game) && <b className="bk-score tnum">{pts(m.score[idx])}</b>}
    </div>
  );
}

export function MatchBox({ m, d, liveKeys, compact }: { m: MatchState; d: TournamentDetail; liveKeys: Set<string>; compact?: boolean }) {
  const ents = entrantMap(d);
  const isLive = m.games.some((s) => liveKeys.has(s.key));
  const firstGame = m.games.find((s) => s.game) ?? null;
  const body = (
    <>
      <PlayerRow e={m.players[0] ? ents.get(m.players[0]) : undefined} m={m} idx={0} liveKeys={liveKeys} />
      <PlayerRow e={m.players[1] ? ents.get(m.players[1]) : undefined} m={m} idx={1} liveKeys={liveKeys} />
      {!compact && m.decidedBy && m.decidedBy !== 'games' && m.decidedBy !== 'bye' && <div className="bk-note">{m.decidedBy === 'sudden-death' ? 'won in sudden death' : `tie-break: ${m.decidedBy}`}</div>}
    </>
  );
  const cls = cx('bk-match', isLive && 'live', m.status === 'done' && 'done', m.status === 'bye' && 'bye', m.status === 'waiting' && 'waiting');
  if (firstGame) {
    return (
      <Link to={pathOf('arena', d.manifest.id, 'game', (m.games.find((s) => liveKeys.has(s.key)) ?? firstGame).key)} className={cls} aria-label={`${m.roundName} ${m.id}: ${m.summary || 'in progress'}`}>
        {isLive && <span className="bk-live">LIVE</span>}
        {body}
      </Link>
    );
  }
  return (
    <div className={cls}>
      {isLive && <span className="bk-live">LIVE</span>}
      {body}
    </div>
  );
}

export function Bracket({ d, liveKeys }: { d: TournamentDetail; liveKeys: Set<string> }) {
  const rounds = [...new Set(d.state.matches.map((m) => m.round))].sort((a, b) => a - b);
  const champ = d.state.champion ? entrantMap(d).get(d.state.champion) : undefined;
  const first = d.state.matches.filter((m) => m.round === 1).length;
  return (
    <div className="bracket" style={{ ['--r1' as string]: first } as CSSProperties} role="group" aria-label="Knockout bracket">
      {rounds.map((r) => {
        const ms = d.state.matches.filter((m) => m.round === r).sort((a, b) => a.slot - b.slot);
        return (
          <div key={r} className={cx('bk-col', r === rounds[rounds.length - 1] && 'final')}>
            <div className="bk-round eyebrow">{ms[0]?.roundName}</div>
            <div className="bk-cells" style={{ gridTemplateRows: `repeat(${ms.length}, 1fr)` }}>
              {ms.map((m, i) => (
                <div key={m.id} className={cx('bk-cell', i % 2 === 0 ? 'even' : 'odd', r > 1 && 'fed')}>
                  <MatchBox m={m} d={d} liveKeys={liveKeys} />
                </div>
              ))}
            </div>
          </div>
        );
      })}
      <div className="bk-col champ-col">
        <div className="bk-round eyebrow">Champion</div>
        <div className="bk-cells" style={{ gridTemplateRows: '1fr' }}>
          <div className="bk-cell fed">
            <div className={cx('bk-champ', champ && 'crowned')} style={{ ['--c' as string]: champ?.color ?? 'var(--border-strong)' } as CSSProperties} key={champ?.id ?? 'none'}>
              <span className="bk-trophy" aria-hidden="true">
                🏆
              </span>
              <strong>{champ?.label ?? 'to be decided'}</strong>
              {champ && <span className="muted">{champ.vendor}</span>}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export function RoundRobin({ d }: { d: TournamentDetail }) {
  const ents = entrantMap(d);
  const rows = d.state.standings;
  const cell = (a: string, b: string) => {
    const m = d.state.matches.find((x) => x.players.includes(a) && x.players.includes(b));
    if (!m || !m.games.some((s) => s.game)) return null;
    const idx = m.players[0] === a ? 0 : 1;
    return { m, mine: m.score[idx], theirs: m.score[1 - idx]! };
  };
  return (
    <div className="rr">
      <div className="table-wrap">
        <table className="table rr-table">
          <thead>
            <tr>
              <th className="num">#</th>
              <th>Model</th>
              <th className="num">Played</th>
              <th className="num">W</th>
              <th className="num">D</th>
              <th className="num">L</th>
              <th className="num">Points</th>
              <th className="num">Illegal</th>
              <th className="num">Cost</th>
              {rows.map((r) => (
                <th key={r.contestantId} className="num rr-h" title={`vs ${ents.get(r.contestantId)?.label}`}>
                  <span className="bk-bar" style={{ background: ents.get(r.contestantId)?.color }} />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const e = ents.get(r.contestantId);
              return (
                <tr key={r.contestantId} className={cx(r.rank === 1 && d.state.complete && 'rr-top')}>
                  <td className="num tnum">{r.rank}</td>
                  <td>
                    <span className="row" style={{ gap: 8 }}>
                      <span className="bk-bar" style={{ background: e?.color }} />
                      <strong>{e?.label ?? r.contestantId}</strong>
                    </span>
                  </td>
                  <td className="num tnum">{r.played}</td>
                  <td className="num tnum">{r.wins}</td>
                  <td className="num tnum">{r.draws}</td>
                  <td className="num tnum">{r.losses}</td>
                  <td className="num tnum">
                    <b>{pts(r.points)}</b>
                  </td>
                  <td className="num tnum">{r.illegal}</td>
                  <td className="num tnum">{fmtCost(r.costUsd)}</td>
                  {rows.map((o) => {
                    if (o.contestantId === r.contestantId) return <td key={o.contestantId} className="rr-x" />;
                    const c = cell(r.contestantId, o.contestantId);
                    const tone = !c ? '' : c.mine > c.theirs ? 'win' : c.mine < c.theirs ? 'loss' : 'draw';
                    return (
                      <td key={o.contestantId} className={cx('num tnum rr-c', tone)}>
                        {c ? `${pts(c.mine)}–${pts(c.theirs)}` : '·'}
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
