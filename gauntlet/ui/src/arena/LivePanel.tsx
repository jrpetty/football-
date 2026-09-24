/** The live board for one game in progress: versus header with clocks, the board, streaming reasoning and the move list. */
import { GameBoard } from './boards.tsx';
import { MoveList, Thinking, Versus, secs, sidesOf } from './parts.tsx';
import type { LiveGame, MatchState, TournamentDetail } from './types.ts';
import { entrantMap, pts } from './useTournament.ts';

export function LivePanel({ d, game, match }: { d: TournamentDetail; game: LiveGame; match: MatchState | undefined }) {
  const ents = entrantMap(d);
  const sides = sidesOf(d.manifest.game.id);
  const last = game.moves[game.moves.length - 1];
  const snap = last ? last.snapshot : game.initial;
  const mover = ents.get(game.players[game.toMove]);
  const planned = d.manifest.settings.gamesPerMatch;
  return (
    <div className="ar-live">
      <Versus
        gameId={d.manifest.game.id}
        players={game.players}
        entrants={ents}
        metrics={game.metrics}
        strikes={game.strikes}
        maxStrikes={d.manifest.settings.maxStrikes}
        toMove={game.toMove}
        turnStartedAt={game.turnStartedAt}
        big
        center={
          <div className="vs-mid">
            <span className="vs-round">{match?.roundName ?? game.matchId}</span>
            <span className="vs-game">
              {game.gameNo > planned ? 'Sudden death' : `Game ${game.gameNo} of ${planned}`}
            </span>
            {match && match.games.some((s) => s.game) && (
              <span className="vs-series tnum" title="Match score so far (win 1, draw ½)">
                {pts(match.score[match.players[0] === game.players[0] ? 0 : 1])}–{pts(match.score[match.players[0] === game.players[0] ? 1 : 0])}
              </span>
            )}
          </div>
        }
      />
      <div className="ar-live-grid">
        <div className="ar-stage">
          <GameBoard gameId={d.manifest.game.id} snap={snap} colors={[sides[0]!.color, sides[1]!.color]} toMove={game.toMove} thinking className="big" />
          <div className="ar-stage-foot">
            <span>
              Move <b className="tnum">{game.moves.filter((m) => m.move).length + 1}</b>
              {d.manifest.game.id === 'chess' && <span className="muted"> · cap {d.manifest.settings.game.maxPlies}</span>}
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
                <h2>Moves</h2>
              </div>
              <span className="badge outline tnum">{game.moves.length}</span>
            </div>
            <MoveList gameId={d.manifest.game.id} moves={game.moves} live />
          </div>
        </div>
      </div>
    </div>
  );
}
