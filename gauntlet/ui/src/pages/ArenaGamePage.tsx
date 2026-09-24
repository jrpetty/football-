/**
 * One Arena game: live board while it is being played, then a step-by-step
 * replay (same keys and controls as the program ReplayPlayer: Space, ← →,
 * Home/End, F) with each move's reasoning, rejected attempts and full transcript.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useAsync, useHotkeys } from '../hooks.ts';
import { Link, pathOf } from '../router.tsx';
import { usePrefs, useViewerCaption } from '../context.tsx';
import { ErrorState, LoadingPage, PageHead, Seg, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { TranscriptView } from '../components/Transcript.tsx';
import { fmtCost, fmtTokens } from '../format.ts';
import { arenaApi } from '../arena/client.ts';
import { GameBoard } from '../arena/boards.tsx';
import { LivePanel } from '../arena/LivePanel.tsx';
import { MoveList, MoveReasoning, Versus, secs, sidesOf } from '../arena/parts.tsx';
import { entrantMap, pts, useTournament } from '../arena/useTournament.ts';
import type { ArenaGameRecord, TournamentDetail } from '../arena/types.ts';
import '../arena/arena.css';

const SPEEDS = [0.5, 1, 2, 4];
const BASE_MS = 1100;

function Replay({ d, g }: { d: TournamentDetail; g: ArenaGameRecord }) {
  const { broadcast } = usePrefs();
  const ents = entrantMap(d);
  const sides = sidesOf(d.manifest.game.id);
  const n = g.moves.length;
  /** 0 = starting position, k = after move k. */
  const [idx, setIdx] = useState(broadcast ? 0 : n);
  const [playing, setPlaying] = useState(broadcast && n > 0);
  const [speed, setSpeed] = useState(1);
  const [tab, setTab] = useState<'move' | 'transcript'>('move');
  const [tSide, setTSide] = useState<'0' | '1'>('0');
  const rootRef = useRef<HTMLDivElement>(null);
  const [isFs, setIsFs] = useState(false);

  useEffect(() => {
    if (!playing) return;
    if (idx >= n) {
      setPlaying(false);
      return;
    }
    const t = window.setTimeout(() => setIdx((i) => Math.min(n, i + 1)), (broadcast ? 1500 : BASE_MS) / speed);
    return () => window.clearTimeout(t);
  }, [playing, idx, n, speed, broadcast]);

  useEffect(() => {
    const on = () => setIsFs(document.fullscreenElement === rootRef.current);
    document.addEventListener('fullscreenchange', on);
    return () => document.removeEventListener('fullscreenchange', on);
  }, []);
  const toggleFs = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void rootRef.current?.requestFullscreen?.().catch(() => undefined);
  }, []);
  const togglePlay = () => {
    if (!playing && idx >= n) setIdx(0);
    setPlaying((p) => !p);
  };
  useHotkeys({
    ' ': (e) => (e.preventDefault(), togglePlay()),
    k: () => togglePlay(),
    ArrowRight: (e) => (e.preventDefault(), setPlaying(false), setIdx((i) => Math.min(n, i + 1))),
    ArrowLeft: (e) => (e.preventDefault(), setPlaying(false), setIdx((i) => Math.max(0, i - 1))),
    Home: () => (setPlaying(false), setIdx(0)),
    End: () => (setPlaying(false), setIdx(n)),
    f: () => toggleFs(),
  });

  const move = idx > 0 ? g.moves[idx - 1] : undefined;
  const snap = move ? move.snapshot : g.initial;
  const atEnd = idx >= n;
  const match = d.state.matches.find((m) => m.id === g.matchId);
  const planned = d.manifest.settings.gamesPerMatch;
  const upTo = g.moves.slice(0, idx);
  const metricsAt = useMemo(() => {
    const out: ArenaGameRecord['metrics'] = [
      { costUsd: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, apiCalls: 0, retries: 0, ms: 0 },
      { costUsd: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, apiCalls: 0, retries: 0, ms: 0 },
    ];
    for (const m of upTo) {
      out[m.side].costUsd += m.costUsd;
      out[m.side].ms += m.ms;
    }
    return out;
  }, [upTo]);
  const strikesAt: [number, number] = [upTo.filter((m) => m.side === 0 && m.forfeit).length, upTo.filter((m) => m.side === 1 && m.forfeit).length];
  const result = g.status !== 'ok' ? `Game ${g.status}` : g.winner === null ? 'Draw' : `${ents.get(g.players[g.winner])?.label} wins`;
  const pct = n ? (idx / n) * 100 : 100;
  const moverName = move ? (ents.get(g.players[move.side])?.label ?? '') : '';

  return (
    <div className={cx('ar-replay', isFs && 'is-fs')} ref={rootRef}>
      <Versus
        gameId={d.manifest.game.id}
        players={g.players}
        entrants={ents}
        metrics={atEnd ? g.metrics : metricsAt}
        strikes={atEnd ? g.strikes : strikesAt}
        maxStrikes={d.manifest.settings.maxStrikes}
        winner={atEnd ? g.winner : null}
        finished={atEnd}
        toMove={null}
        big
        center={
          <div className="vs-mid">
            <span className="vs-round">{match?.roundName ?? g.matchId}</span>
            <span className="vs-game">{g.gameNo > planned ? 'Sudden death' : `Game ${g.gameNo} of ${planned}`}</span>
            {atEnd ? <span className="vs-result">{result}</span> : <span className="vs-series tnum">Move {idx}/{n}</span>}
          </div>
        }
      />
      <div className="ar-live-grid">
        <div className="ar-stage">
          <GameBoard gameId={d.manifest.game.id} snap={snap} colors={[sides[0]!.color, sides[1]!.color]} className="big" />
          {atEnd && g.status === 'ok' && (
            <div className="ar-final" role="status">
              <span className="eyebrow">Final result</span>
              <b>{result}</b>
              <span>{g.reason}</span>
            </div>
          )}
          <div className="replay-controls">
            <button type="button" className="btn icon sm" onClick={() => (setPlaying(false), setIdx((i) => Math.max(0, i - 1)))} aria-label="Previous move" disabled={idx === 0}>
              <Icon.StepBack />
            </button>
            <button type="button" className="btn icon primary play-btn" onClick={togglePlay} aria-label={playing ? 'Pause' : 'Play'} disabled={n === 0}>
              {playing ? <Icon.Pause /> : <Icon.Play />}
            </button>
            <button type="button" className="btn icon sm" onClick={() => (setPlaying(false), setIdx((i) => Math.min(n, i + 1)))} aria-label="Next move" disabled={idx >= n}>
              <Icon.StepFwd />
            </button>
            <div className="scrub">
              <div className="scrub-ticks" aria-hidden="true">
                {g.moves.map((m, i) => (
                  <i key={i} className={cx(m.forfeit ? 't-bad' : m.attempts.some((a) => a.error) ? 't-neutral' : '')} style={{ left: `${n ? ((i + 1) / n) * 100 : 0}%` }} />
                ))}
              </div>
              <input type="range" min={0} max={n} value={idx} onChange={(e) => (setPlaying(false), setIdx(Number(e.target.value)))} aria-label="Scrub through the game" style={{ ['--pct' as string]: `${pct}%` }} />
            </div>
            <select className="select sm" style={{ width: 84 }} value={speed} onChange={(e) => setSpeed(Number(e.target.value))} aria-label="Playback speed">
              {SPEEDS.map((s) => (
                <option key={s} value={s}>
                  {s}×
                </option>
              ))}
            </select>
            <button type="button" className="btn ghost icon sm" onClick={toggleFs} aria-label="Full screen" title="Full screen (F)">
              <Icon.Maximize />
            </button>
          </div>
        </div>
        <div className="ar-side">
          <div className="card ar-movecard">
            <div className="card-head">
              <div className="t">
                <h2>{move ? (move.move ? `${moverName}: ${move.label}` : `${moverName}: strike`) : 'Starting position'}</h2>
                {move && !move.opening && (
                  <div className="desc tnum">
                    {secs(move.ms)} · {fmtCost(move.costUsd)} · {fmtTokens(move.inputTokens)} in / {fmtTokens(move.outputTokens)} out
                  </div>
                )}
              </div>
              <Seg small label="Panel" value={tab} onChange={setTab} options={[{ value: 'move', label: 'Reasoning' }, { value: 'transcript', label: 'Transcript' }]} />
            </div>
            <div className="card-body">
              {tab === 'move' ? (
                move ? (
                  <MoveReasoning move={move} label={moverName} />
                ) : (
                  <p className="muted">Press play (Space) or step through the moves (← →). Each move shows the model's own reasoning, any rejected attempts and why they were rejected.</p>
                )
              ) : (
                <div className="stack">
                  <Seg small label="Player" value={tSide} onChange={setTSide} options={[{ value: '0', label: ents.get(g.players[0])?.label ?? 'Player 1' }, { value: '1', label: ents.get(g.players[1])?.label ?? 'Player 2' }]} />
                  <div className="ar-transcript">
                    <TranscriptView entries={g.transcripts[Number(tSide) as 0 | 1]} />
                  </div>
                </div>
              )}
            </div>
          </div>
          <div className="card ar-moves">
            <div className="card-head">
              <div className="t">
                <h2>Moves</h2>
              </div>
              <span className="badge outline tnum">{n}</span>
            </div>
            <MoveList gameId={d.manifest.game.id} moves={g.moves} current={idx - 1} onSelect={(i) => (setPlaying(false), setIdx(i + 1))} />
          </div>
        </div>
      </div>
      <div className="replay-hint no-broadcast">
        <kbd>Space</kbd> play/pause <kbd>←</kbd>
        <kbd>→</kbd> step <kbd>F</kbd> full screen
      </div>
    </div>
  );
}

export default function ArenaGamePage({ id, gameKey }: { id: string; gameKey: string }) {
  const { detail: d, error: dErr, live, reload } = useTournament(id);
  const isLive = live.some((g) => g.key === gameKey);
  const finishedKey = d?.games.find((g) => g.key === gameKey)?.finishedAt ?? '';
  const rec = useAsync(() => (isLive ? Promise.resolve(null) : arenaApi.game(id, gameKey)), [id, gameKey, isLive, finishedKey]);
  const ents = entrantMap(d);
  const g = rec.data;
  const liveGame = live.find((x) => x.key === gameKey);
  const players = g?.players ?? liveGame?.players;
  useViewerCaption(
    d && players ? `${isLive ? 'Live' : 'Replay'}: ${ents.get(players[0])?.label} against ${ents.get(players[1])?.label} at ${d.manifest.game.name}. Every move was a fresh prompt with the full rules and the position; the harness checked it for legality.` : null,
    d ? `Recorded during the tournament — nothing is re-simulated · fingerprint ${d.manifest.fingerprint}` : undefined,
  );

  if (dErr && !d) return <div className="page"><ErrorState error={dErr} onRetry={reload} /></div>;
  if (!d) return <LoadingPage />;
  const match = d.state.matches.find((m) => m.id === (g?.matchId ?? liveGame?.matchId));
  const slot = match?.games.find((s) => s.key === gameKey);

  return (
    <div className="page arena-g">
      <PageHead
        eyebrow={
          <span>
            <Link to="/arena">The Arena</Link> · <Link to={pathOf('arena', id)}>{d.manifest.name}</Link>
            {match ? ` · ${match.roundName}` : ''}
          </span>
        }
        title={players ? `${ents.get(players[0])?.label ?? players[0]} vs ${ents.get(players[1])?.label ?? players[1]}` : gameKey}
        sub={
          match ? (
            <span>
              {d.manifest.game.name} · game {slot?.gameNo ?? '?'}
              {slot?.suddenDeath ? ' (sudden death: starts after one random move each)' : ''} · match: {ents.get(match.players[0] ?? '')?.label} {pts(match.score[0])} – {pts(match.score[1])} {ents.get(match.players[1] ?? '')?.label}
            </span>
          ) : undefined
        }
        actions={
          <>
            {match && (
              <Link className="btn" to={`${pathOf('arena', id, 'card')}?match=${encodeURIComponent(match.id)}`}>
                <Icon.Present /> Match card
              </Link>
            )}
            <Link className="btn ghost" to={pathOf('arena', id)}>
              Back to tournament
            </Link>
          </>
        }
      />
      {isLive && liveGame ? (
        <LivePanel d={d} game={liveGame} match={match} />
      ) : rec.error ? (
        <ErrorState error={rec.error} onRetry={rec.reload} title="This game has not been played yet" />
      ) : !g ? (
        <LoadingPage />
      ) : (
        <Replay key={g.key + g.finishedAt} d={d} g={g} />
      )}
    </div>
  );
}
