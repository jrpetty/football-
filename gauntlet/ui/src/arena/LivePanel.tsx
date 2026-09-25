/** The live board for one game in progress: versus header with clocks, the board, streaming reasoning and the move list. */
import { pathOf } from '../router.tsx';
import { Connect4Legend, GameBoard } from './boards.tsx';
import { MoveCaption } from './MoveCaption.tsx';
import { MoveList, Thinking, Versus, engineOf, fmtScore, playsWord, seatPlayers, secs, sidesOf } from './parts.tsx';
import type { C4Snapshot, LiveGame, MatchState, TournamentDetail } from './types.ts';
import { entrantMap } from './useTournament.ts';

/** "Game 1 of 2", "Sudden death", or the format-specific line for poker and debates. */
export function gameLine(gameId: string, gameNo: number, planned: number): string {
  if (gameNo > planned) return 'Sudden death';
  const e = engineOf(gameId);
  if (e === 'turns') return `Game ${gameNo} of ${planned} · ${gameNo === 1 ? 'same deals follow, cards swapped' : 'same deals, cards swapped'}`;
  if (e === 'debate') return `Game ${gameNo} of ${planned} · ${gameNo === 1 ? 'sides swap next game' : 'sides swapped'}`;
  return `Game ${gameNo} of ${planned}`;
}

export function LivePanel({ d, game, match }: { d: TournamentDetail; game: LiveGame; match: MatchState | undefined }) {
  const ents = entrantMap(d);
  const gameId = d.manifest.game.id;
  const sides = sidesOf(gameId);
  const last = game.moves[game.moves.length - 1];
  const snap = last ? last.snapshot : game.initial;
  const mover = ents.get(game.players[game.toMove]);
  const planned = d.manifest.settings.gamesPerMatch;
  const engine = engineOf(gameId);
  const players = seatPlayers(ents, game.players);
  const words = playsWord(gameId);
  const flip = match?.players[0] === game.players[0] ? 0 : 1;
  const versus = (
    <Versus
      gameId={gameId}
      players={game.players}
      entrants={ents}
      metrics={game.metrics}
      strikes={game.strikes}
      maxStrikes={d.manifest.settings.maxStrikes}
      toMove={game.phase === 'judging' ? null : game.toMove}
      turnStartedAt={game.turnStartedAt}
      big
      center={
        <div className="vs-mid">
          <span className="vs-round">{match?.roundName ?? game.matchId}</span>
          <span className="vs-game">{gameLine(gameId, game.gameNo, planned)}</span>
          {match && match.games.some((s) => s.game?.status === 'ok') && (
            <span className="vs-series tnum" title={match.unit ? `Match total so far (${match.unit})` : 'Match score so far (win 1, draw ½)'}>
              {fmtScore(match.score[flip], match.unit)}
              {match.unit ? <span className="vs-unit"> {match.unit}</span> : `–${fmtScore(match.score[1 - flip]!)}`}
            </span>
          )}
        </div>
      }
    />
  );

  if (engine === 'debate') {
    return (
      <div className="ar-live ar-live-debate">
        {versus}
        <GameBoard gameId={gameId} snap={snap} colors={[sides[0]!.color, sides[1]!.color]} players={players} live={{ toMove: game.toMove, thinking: game.thinking, phase: game.phase }} judgeHref={pathOf('arena', d.manifest.id, 'judge')} className="big" />
      </div>
    );
  }

  return (
    <div className="ar-live">
      {versus}
      <div className="ar-live-grid">
        <div className="ar-stage">
          <MoveCaption
            gameId={gameId}
            move={last}
            prev={game.moves.length > 1 ? game.moves[game.moves.length - 2]!.snapshot : game.initial}
            names={[players[0].label, players[1].label]}
            color={last ? players[last.side].color : undefined}
            idle={engine === 'turns' ? 'Hand 1 dealt' : 'Starting position'}
          />
          <GameBoard gameId={gameId} snap={snap} colors={[sides[0]!.color, sides[1]!.color]} toMove={game.toMove} thinking players={players} className="big" />
          {gameId === 'connect4' && <Connect4Legend colors={[sides[0]!.color, sides[1]!.color]} names={[players[0].label, players[1].label]} snap={snap as C4Snapshot} />}
          <div className="ar-stage-foot">
            <span>
              {engine === 'turns' ? 'Decision' : 'Move'} <b className="tnum">{game.moves.filter((m) => m.move).length + 1}</b>
              {gameId === 'chess' && <span className="muted"> · cap {d.manifest.settings.game.maxPlies}</span>}
            </span>
            {last && (
              <span className="muted">
                Last: <b>{last.move ? last.label : 'strike'}</b> by {ents.get(game.players[last.side])?.label} in {secs(last.ms)}
              </span>
            )}
          </div>
        </div>
        <div className="ar-side">
          <Thinking text={game.thinking} who={mover?.label ?? game.players[game.toMove]} color={mover?.color} />
          <div className="card ar-moves">
            <div className="card-head">
              <div className="t">
                <h2>{words.moves[0]!.toUpperCase() + words.moves.slice(1)}</h2>
              </div>
              <span className="badge outline tnum">{game.moves.length}</span>
            </div>
            <MoveList gameId={gameId} moves={game.moves} live />
          </div>
        </div>
      </div>
    </div>
  );
}
