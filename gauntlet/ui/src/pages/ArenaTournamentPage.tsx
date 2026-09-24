/**
 * One Arena tournament: the live board (streaming "thinking", move list,
 * clocks, spend), the bracket filling in as matches finish, and every game.
 */
import { useMemo, useState } from 'react';
import { Link, pathOf, setQuery, useRoute } from '../router.tsx';
import { usePrefs, useToast, useViewerCaption } from '../context.tsx';
import { Callout, ConfirmDialog, ErrorState, HashTag, LoadingPage, PageHead, RunStatusBadge, Seg, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtCost, fmtDateTime } from '../format.ts';
import { arenaApi, exportTournamentUrl } from '../arena/client.ts';
import { entrantMap, formatName, useTournament } from '../arena/useTournament.ts';
import { GameBoard } from '../arena/boards.tsx';
import { Bracket, RoundRobin } from '../arena/Bracket.tsx';
import { sidesOf } from '../arena/parts.tsx';
import { LivePanel } from '../arena/LivePanel.tsx';
import type { ArenaGameLite, TournamentDetail } from '../arena/types.ts';
import '../arena/arena.css';

type View = 'live' | 'bracket' | 'games';

function Between({ d }: { d: TournamentDetail }) {
  const ents = entrantMap(d);
  const lastGame = [...d.games].filter((g) => g.status === 'ok').sort((a, b) => a.finishedAt.localeCompare(b.finishedAt)).pop();
  const next = d.state.matches.find((m) => m.status === 'ready' || m.status === 'playing');
  if (!lastGame) {
    return (
      <div className="card ar-between">
        <div className="ar-between-t">{d.active ? 'The first game is about to start…' : 'No games have been played yet.'}</div>
      </div>
    );
  }
  const sides = sidesOf(d.manifest.game.id);
  return (
    <div className="card ar-between">
      <div className="ar-between-board">
        <GameBoard gameId={d.manifest.game.id} snap={lastGame.lastSnapshot} colors={[sides[0]!.color, sides[1]!.color]} />
      </div>
      <div className="stack">
        <span className="eyebrow">Latest result</span>
        <h2>
          {lastGame.winner === null ? 'Draw' : `${ents.get(lastGame.players[lastGame.winner])?.label} wins`}
          <span className="muted"> · {lastGame.reason}</span>
        </h2>
        <p className="muted">
          {ents.get(lastGame.players[0])?.label} ({sides[0]!.name}) vs {ents.get(lastGame.players[1])?.label} ({sides[1]!.name}) · {lastGame.plies} moves
        </p>
        {next && d.active && (
          <p>
            <b>Up next:</b> {ents.get(next.players[0] ?? '')?.label} vs {ents.get(next.players[1] ?? '')?.label} ({next.roundName})
          </p>
        )}
        {d.state.champion && (
          <p className="ar-champ-line">
            <span aria-hidden="true">🏆</span> Champion: <b>{ents.get(d.state.champion)?.label}</b>
          </p>
        )}
        <div className="row">
          <Link className="btn sm" to={pathOf('arena', d.manifest.id, 'game', lastGame.key)}>
            <Icon.Play /> Replay this game
          </Link>
        </div>
      </div>
    </div>
  );
}

function GamesTable({ d, liveKeys }: { d: TournamentDetail; liveKeys: Set<string> }) {
  const ents = entrantMap(d);
  const sides = sidesOf(d.manifest.game.id);
  const byKey = new Map<string, ArenaGameLite>(d.games.map((g) => [g.key, g]));
  const rows = d.state.matches.flatMap((m) => m.games.map((s) => ({ m, s, g: byKey.get(s.key) })));
  return (
    <div className="card">
      <div className="table-wrap">
        <table className="table ar-games-table">
          <thead>
            <tr>
              <th>Match</th>
              <th>Game</th>
              <th>{sides[0]!.name}</th>
              <th>{sides[1]!.name}</th>
              <th>Result</th>
              <th className="num">Moves</th>
              <th className="num">Illegal</th>
              <th className="num">Cost</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map(({ m, s, g }) => (
              <tr key={s.key} className={cx(liveKeys.has(s.key) && 'is-live')}>
                <td>
                  <span className="muted">{m.roundName}</span> <span className="mono">{m.id}</span>
                </td>
                <td className="tnum">{s.suddenDeath ? `${s.gameNo} · SD` : s.gameNo}</td>
                {[0, 1].map((i) => (
                  <td key={i}>
                    <span className="row" style={{ gap: 6 }}>
                      <span className="bk-bar" style={{ background: ents.get(s.players[i]!)?.color }} />
                      <span className={cx(g && g.winner === i && 'strong')}>{ents.get(s.players[i]!)?.label}</span>
                    </span>
                  </td>
                ))}
                <td>
                  {liveKeys.has(s.key) ? (
                    <span className="badge live">
                      <span className="dot" /> playing
                    </span>
                  ) : g ? (
                    g.status !== 'ok' ? (
                      <span className="badge bad" title={g.error}>
                        {g.status}
                      </span>
                    ) : (
                      <span>
                        {g.winner === null ? 'Draw' : `${ents.get(g.players[g.winner])?.label} wins`} <span className="muted">· {g.reason}</span>
                      </span>
                    )
                  ) : (
                    <span className="muted">not played yet</span>
                  )}
                </td>
                <td className="num tnum">{g?.plies ?? ''}</td>
                <td className="num tnum">{g ? `${g.illegal[0]} / ${g.illegal[1]}` : ''}</td>
                <td className="num tnum">{g ? fmtCost(g.metrics[0].costUsd + g.metrics[1].costUsd) : ''}</td>
                <td className="num">
                  {(g || liveKeys.has(s.key)) && (
                    <Link to={pathOf('arena', d.manifest.id, 'game', s.key)} className="btn xs">
                      {liveKeys.has(s.key) ? 'Watch' : 'Replay'}
                    </Link>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default function ArenaTournamentPage({ id }: { id: string }) {
  const route = useRoute();
  const toast = useToast();
  const { broadcast } = usePrefs();
  const { detail: d, error, live, lastLog, reload } = useTournament(id);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [busy, setBusy] = useState(false);
  const liveKeys = useMemo(() => new Set(live.map((g) => g.key)), [live]);
  const requested = route.query.get('view') as View | null;
  const view: View = requested ?? (d?.active || live.length ? 'live' : 'bracket');
  const featured = live.find((g) => g.key === route.query.get('game')) ?? live[0];
  const ents = entrantMap(d);

  useViewerCaption(
    !d
      ? null
      : view === 'live' && featured
        ? `Live: ${ents.get(featured.players[0])?.label} against ${ents.get(featured.players[1])?.label} at ${d.manifest.game.name}. Each move is a fresh prompt with the full rules; an illegal move gets one retry, then a random move and a strike.`
        : view === 'bracket'
          ? `The ${d.manifest.game.name} ${d.manifest.settings.format === 'knockout' ? 'knockout bracket' : 'round-robin table'}: each pairing plays ${d.manifest.settings.gamesPerMatch} games with the sides swapped; winners move on.`
          : `Every game of the tournament, with the result and what it cost.`,
    `Fingerprint ${d?.manifest.fingerprint ?? ''} · ${d?.manifest.game.name ?? ''} v${d?.manifest.game.version ?? ''}`,
  );

  if (error && !d) return <div className="page"><ErrorState error={error} onRetry={reload} /></div>;
  if (!d) return <LoadingPage />;
  const m = d.manifest;
  const status = d.active && m.status !== 'running' ? 'running' : m.status;

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    try {
      await fn();
      toast.success(ok);
      reload();
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };
  const exportUrl = exportTournamentUrl(m.id);
  const featuredMatch = featured ? d.state.matches.find((x) => x.id === featured.matchId) : undefined;

  return (
    <div className={cx('page arena-t', broadcast && 'bc')}>
      <PageHead
        eyebrow={
          <span>
            <Link to="/arena">The Arena</Link> · {m.game.name} · {formatName(m.settings.format)} · {m.entrants.length} models
          </span>
        }
        title={m.name}
        sub={
          <span className="row wrap" style={{ gap: 10 }}>
            <RunStatusBadge status={status} />
            <span className="tnum">
              {d.state.gamesDone}/{d.state.gamesTotal} games
            </span>
            <span className="tnum">
              {fmtCost(d.state.costUsd)} spent{m.settings.maxCostUsd ? ` of ${fmtCost(m.settings.maxCostUsd)} cap` : ''}
            </span>
            <span className="muted no-broadcast">{fmtDateTime(m.createdAt)}</span>
            <span className="no-broadcast">
              <HashTag value={m.fingerprint} label="Fingerprint" />
            </span>
          </span>
        }
        actions={
          <>
            {d.active ? (
              <button className="btn" onClick={() => setConfirmCancel(true)} disabled={busy}>
                <Icon.Stop /> Cancel
              </button>
            ) : m.status !== 'completed' ? (
              <button className="btn primary" onClick={() => act(() => arenaApi.resume(m.id), 'Tournament resumed')} disabled={busy}>
                <Icon.Refresh /> Resume
              </button>
            ) : null}
            <Link className="btn" to={pathOf('arena', m.id, 'card')}>
              <Icon.Present /> Match cards
            </Link>
            {exportUrl && (
              <a className="btn ghost icon" href={exportUrl} title="Export JSON (manifest, every game, every prompt and reply)" aria-label="Export JSON">
                <Icon.Download />
              </a>
            )}
          </>
        }
      />
      {m.error && !d.active && (
        <div style={{ marginBottom: 14 }}>
          <Callout tone={m.status === 'failed' ? 'bad' : 'warn'}>{m.error}</Callout>
        </div>
      )}
      {lastLog && d.active && (
        <div style={{ marginBottom: 14 }} className="no-broadcast">
          <Callout tone={lastLog.level === 'error' ? 'bad' : 'warn'}>{lastLog.message}</Callout>
        </div>
      )}
      <div className="row ar-tabs">
        <Seg
          label="View"
          value={view}
          onChange={(v) => setQuery({ view: v })}
          options={[
            { value: 'live', label: live.length ? `● Live${live.length > 1 ? ` (${live.length})` : ''}` : 'Live' },
            { value: 'bracket', label: m.settings.format === 'knockout' ? 'Bracket' : 'Standings' },
            { value: 'games', label: `Games (${d.state.gamesDone})` },
          ]}
        />
        {view === 'live' && live.length > 1 && (
          <div className="row wrap" style={{ gap: 6 }}>
            {live.map((g) => (
              <button key={g.key} className={cx('btn xs', g.key === featured?.key && 'primary')} onClick={() => setQuery({ game: g.key })}>
                {ents.get(g.players[0])?.label} v {ents.get(g.players[1])?.label}
              </button>
            ))}
          </div>
        )}
        <span className="spacer" />
        {view === 'live' && featured && (
          <Link className="btn sm ghost no-broadcast" to={pathOf('arena', m.id, 'game', featured.key)}>
            Open game page <Icon.ChevronRight />
          </Link>
        )}
      </div>

      {view === 'live' && (featured ? <LivePanel d={d} game={featured} match={featuredMatch} /> : <Between d={d} />)}
      {view === 'bracket' && (
        <div className="card ar-bracket-card">
          {m.settings.format === 'knockout' ? <Bracket d={d} liveKeys={liveKeys} /> : <RoundRobin d={d} />}
          <div className="ar-legend muted">
            <span>
              <i className="win" /> win
            </span>
            <span>
              <i className="draw" /> draw
            </span>
            <span>
              <i className="loss" /> loss
            </span>
            <span>
              <i className="live" /> playing now
            </span>
            <span>Each pairing plays {m.settings.gamesPerMatch} games with the sides swapped{m.settings.format === 'knockout' ? '; a level match goes to sudden death, then fewer illegal moves, then lower cost' : ''}.</span>
          </div>
        </div>
      )}
      {view === 'games' && <GamesTable d={d} liveKeys={liveKeys} />}

      <ConfirmDialog
        open={confirmCancel}
        title="Cancel this tournament?"
        body="Finished games are kept. Games in progress stop and will be replayed from the start when you resume."
        confirmLabel="Cancel tournament"
        danger
        busy={busy}
        onConfirm={() => {
          setConfirmCancel(false);
          void act(() => arenaApi.cancel(m.id), 'Cancelling: games in progress stop at their next move');
        }}
        onCancel={() => setConfirmCancel(false)}
      />
    </div>
  );
}
