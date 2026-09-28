/**
 * The Game Jam wall (#/runs/<id>/jam): every model's five games as arcade cabinets on one shelf per model,
 * each screen flipping through the recorded playtest screenshots. The best game of each genre wears a crown and
 * the "Game of the Jam" banner names the model with the best average (ties go to creativity). Click a cabinet for
 * the full card: play it, the filmstrip, the judges' scorecard and the requirement ticks.
 */
import { useEffect, useMemo } from 'react';
import type { CSSProperties } from 'react';
import { api, artifactUrl } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, pathOf, setQuery, useRoute } from '../router.tsx';
import { ErrorState, LoadingPage, PageHead } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { ModelBadge } from '../components/viz/ModelBadge.tsx';
import { TrophySvg } from '../components/viz/TrophySvg.tsx';
import { GameCabinet } from '../components/viz/GameCabinet.tsx';
import { GameJamShowcase } from '../components/viz/GameJamShowcase.tsx';
import { JAM_TEST_ID, jamBoard, pct100 } from '../components/viz/gameJamModel.ts';
import '../components/viz/game-jam.css';

export default function GameJamPage({ runId }: { runId: string }) {
  const state = useAsync(() => api.run(runId), [runId]);
  const { query } = useRoute();
  const open = query.get('game');
  const d = state.data;
  const people = useMemo(() => new Map((d?.manifest.contestants ?? []).map((c) => [c.id, c])), [d]);
  const names = useMemo(() => new Map([...(d?.manifest.contestants ?? []), ...(d?.manifest.judges ?? [])].map((c) => [c.id, c.label])), [d]);
  const board = useMemo(() => (d ? jamBoard(d.results, d.manifest.contestants.map((c) => c.id)) : null), [d]);
  const urlFor = (file: string) => artifactUrl(runId, file);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && open && setQuery({ game: null });
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
  if (state.error && !d) return <ErrorState error={state.error} onRetry={state.reload} />;
  if (!d || !board) return <LoadingPage />;
  const inRun = d.manifest.tests.some((t) => t.id === JAM_TEST_ID);
  const openResult = open ? d.results.find((r) => r.key === open) : undefined;
  const goty = board.gameOfTheJam;
  const gotyPerson = goty ? people.get(goty.model) : undefined;
  const gotyTotals = goty ? board.totals.get(goty.model) : undefined;
  return (
    <div className="page jam-wall-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Link to={pathOf('runs', runId)}>{d.manifest.name || runId}</Link> <Icon.ChevronRight style={{ width: 12, height: 12 }} /> The Game Jam
          </span>
        }
        title="The Game Jam"
        sub="Five genres, one prompt each. Every screen replays what the robot player saw; click a machine to play the game and see how the judges scored it."
        actions={
          <Link to={pathOf('present', runId)} className="btn">
            <Icon.Present /> Present
          </Link>
        }
      />
      {!inRun || board.models.length === 0 ? (
        <div className="card pad">This run has no Game Jam results yet. Start a run with the <b>games</b> suite (or the test “The Game Jam”).</div>
      ) : (
        <>
          <section className="jam-hero">
            <div>
              <h2>{goty && gotyPerson ? `Game of the Jam: ${gotyPerson.label}` : 'No game scored yet'}</h2>
              <p>
                Each game: a quarter automatic checks (it starts, draws, reacts, never freezes), three quarters AI judges from other companies, who tick every numbered requirement and weigh creativity most. A crown marks the best game in each genre.
              </p>
            </div>
            {goty && gotyPerson && gotyTotals && (
              <div className="jam-goty">
                <TrophySvg />
                <span className="k">Best average across the five rounds</span>
                <span className="who">
                  {gotyPerson.label} · {pct100(gotyTotals.score)}
                </span>
                <span className="why">
                  {gotyTotals.wins} genre {gotyTotals.wins === 1 ? 'win' : 'wins'} · {gotyTotals.games} of 5 games working · creativity {gotyTotals.creativity === null ? '—' : (gotyTotals.creativity * 10).toFixed(1)}/10
                </span>
              </div>
            )}
          </section>
          <section className="jam-shelf" aria-label="Every model's five games">
            <div className="jam-shelf-head corner" />
            {board.genres.map((g) => (
              <div key={g.id} className="jam-shelf-head">
                <b>
                  Round {g.round} · {g.short}
                </b>
                <span>{g.label}</span>
              </div>
            ))}
            {board.models.map((m, row) => {
              const p = people.get(m);
              const t = board.totals.get(m)!;
              return [
                <div key={`${m}-h`} className="jam-shelf-model" style={{ ['--mc' as string]: p?.color } as CSSProperties}>
                  <ModelBadge label={p?.label ?? m} color={p?.color} vendor={p?.vendor} size="md" baseline={m === 'random-baseline'} />
                  <span className="tot tnum">{pct100(t.score) ?? '—'}</span>
                  <span className="sub">
                    average of 5 games · {t.games} working{t.wins ? ` · ${t.wins} crown${t.wins > 1 ? 's' : ''}` : ''}
                  </span>
                </div>,
                ...board.genres.map((g, col) => {
                  const e = board.entries.get(m)?.get(g.id) ?? null;
                  return (
                    <GameCabinet
                      key={`${m}-${g.id}`}
                      entry={e}
                      urlFor={urlFor}
                      marquee={g.marquee}
                      model={g.short}
                      color={p?.color ?? '#888'}
                      size="sm"
                      winner={board.genreWinner.get(g.id) === m}
                      delayMs={(row * 5 + col) * 70}
                      footer={e ? e.line : 'no entry'}
                      onOpen={e ? () => setQuery({ game: e.result.key }) : undefined}
                    />
                  );
                }),
              ];
            })}
          </section>
          <p className="muted" style={{ margin: 0 }}>
            Screens show the recorded playtest screenshots (0.5, 3, 6, 10 and 15 seconds). “FROZE”, “BLANK SCREEN” and “RAN OUT OF SPACE” mark games the automatic checks caught; those score at most 30 (or only the automatic quarter when the file was cut off).
          </p>
        </>
      )}
      {openResult && (
        <div className="jam-drawer" role="dialog" aria-modal="true" onClick={(e) => e.target === e.currentTarget && setQuery({ game: null })}>
          <div className="jam-drawer-card">
            <div className="jam-drawer-top">
              <ModelBadge label={people.get(openResult.contestantId)?.label ?? openResult.contestantId} color={people.get(openResult.contestantId)?.color} size="lg" />
              <span className="spacer" />
              <button type="button" className="btn" onClick={() => setQuery({ game: null })}>
                Close
              </button>
            </div>
            <GameJamShowcase result={openResult} urlFor={urlFor} names={names} />
          </div>
        </div>
      )}
    </div>
  );
}
