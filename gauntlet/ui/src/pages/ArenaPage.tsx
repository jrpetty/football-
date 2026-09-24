/** The Arena — list of head-to-head tournaments. */
import type { CSSProperties } from 'react';
import { useAsync, useInterval } from '../hooks.ts';
import { Link, pathOf } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { Empty, ErrorState, LoadingPage, PageHead, RunStatusBadge, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtCost, fmtRelative } from '../format.ts';
import { arenaApi } from '../arena/client.ts';
import { formatName } from '../arena/useTournament.ts';
import { ArenaIcon, GameGlyph } from '../arena/ArenaIcon.tsx';
import '../arena/arena.css';

export default function ArenaPage() {
  const { data, error, loading, reload } = useAsync(() => arenaApi.list(), []);
  const running = (data ?? []).some((t) => t.status === 'running' || t.status === 'queued');
  useInterval(reload, running ? 4000 : null);
  useViewerCaption('The Arena: AI models play each other head to head. Every tournament here is a bracket of real games with every move checked by the harness.');

  return (
    <div className="page arena-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 6 }}>
            <ArenaIcon style={{ width: 14, height: 14 }} /> Head to head
          </span>
        }
        title="The Arena"
        sub="Models play Connect Four and chess against each other. Every move is a fresh prompt with the full rules; illegal moves get one retry, then a random move and a strike. Each pairing plays both sides."
        actions={
          <Link to="/arena/new" className="btn primary">
            <Icon.Plus /> New tournament
          </Link>
        }
      />
      {error && !data ? (
        <ErrorState error={error} onRetry={reload} />
      ) : loading && !data ? (
        <LoadingPage />
      ) : !data?.length ? (
        <div className="card">
          <Empty
            icon={<ArenaIcon />}
            title="No tournaments yet"
            actions={
              <Link to="/arena/new" className="btn primary">
                <Icon.Plus /> Start the first tournament
              </Link>
            }
          >
            Pick a game and four or eight models. Tip: add the free Random Baseline for a dry run that costs nothing.
          </Empty>
        </div>
      ) : (
        <div className="ar-list">
          {data.map((t) => {
            const champ = t.champion ? t.entrants.find((e) => e.id === t.champion) : null;
            const pct = t.gamesTotal ? Math.round((t.gamesDone / t.gamesTotal) * 100) : 0;
            return (
              <Link key={t.id} to={pathOf('arena', t.id)} className={cx('ar-card card', (t.status === 'running' || t.status === 'queued') && 'is-live')}>
                <div className="ar-card-top">
                  <GameGlyph gameId={t.game.id} />
                  <div className="stack tight" style={{ minWidth: 0, flex: 1 }}>
                    <span className="eyebrow">
                      {t.game.name} · {formatName(t.format)} · {t.entrants.length} models
                    </span>
                    <strong className="ar-card-name ellipsis">{t.name}</strong>
                  </div>
                  <RunStatusBadge status={t.status} />
                </div>
                <div className="ar-entrants">
                  {t.entrants
                    .slice()
                    .sort((a, b) => a.seed - b.seed)
                    .map((e) => (
                      <span key={e.id} className={cx('ar-ent', champ?.id === e.id && 'champ')} style={{ ['--c' as string]: e.color } as CSSProperties} title={`Seed ${e.seed}: ${e.label}`}>
                        <i />
                        {e.label}
                      </span>
                    ))}
                </div>
                <div className="ar-card-foot">
                  {champ ? (
                    <span className="ar-champ">
                      <span aria-hidden="true">🏆</span> {champ.label}
                    </span>
                  ) : (
                    <span className="ar-prog">
                      <span className="bar">
                        <i style={{ width: `${pct}%` }} />
                      </span>
                      <span className="tnum muted">
                        {t.gamesDone}/{t.gamesTotal} games
                      </span>
                    </span>
                  )}
                  <span className="spacer" />
                  <span className="tnum">{fmtCost(t.costUsd)}</span>
                  <span className="muted">{fmtRelative(t.createdAt)}</span>
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
