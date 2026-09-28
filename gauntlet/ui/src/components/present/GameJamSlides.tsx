/**
 * Presenter slides for The Game Jam: one slide per genre with every model's game side by side as arcade
 * cabinets (their screens replay the recorded playtest), then the "Game of the Jam" winner slide. The crown goes
 * to the best score in the genre (ties: creativity); every number is recorded.
 */
import type { CSSProperties } from 'react';
import { artifactUrl } from '../../api.ts';
import type { CaseResultLite, CategoryInfo } from '../../types.ts';
import { GameCabinet } from '../viz/GameCabinet.tsx';
import { TrophySvg } from '../viz/TrophySvg.tsx';
import { jamGenreInfo, type JamGenre } from '../../../../src/scoring/game-jam-shared.ts';
import { JAM_TEST_ID, compareEntries, entryOf, jamBoard, pct100 } from '../viz/gameJamModel.ts';
import '../viz/game-jam.css';
import '../../styles/moments.css';

export interface JamContender {
  id: string;
  label: string;
  color: string;
  baseline: boolean;
}

/** The jam's case ids in genre order, for slides (only cases with results). */
export function jamSlideCases(results: CaseResultLite[], caseIds: string[]): string[] {
  return caseIds.filter((id) => results.some((r) => r.testId === JAM_TEST_ID && r.caseId === id));
}

export function jamGenreCaption(genre: string): { text: string; fine: string } {
  const g = jamGenreInfo(genre);
  return {
    text: `Round ${g?.round ?? ''}: ${g?.label ?? genre}. Every model got the same detailed brief. Each screen replays what the robot player saw in its 30-second full-HD playtest; the number is the game’s score out of 100, and the crown marks the best game.`,
    fine: 'Score = 25% automatic checks + 75% judges from other companies · visual quality and creativity weigh most · a frozen or blank game scores at most 30',
  };
}

export const JAM_WINNER_CAPTION = {
  text: 'The Game of the Jam: the model whose five games scored best on average. Ties go to the better visuals, then creativity: the two biggest parts of the score.',
  fine: 'Average of the five game scores · crowns = genre wins · visuals and creativity = judges’ scores out of 10',
};

export function JamGenreSlide({ runId, caseId, results, contenders, cat, testName }: { runId: string; caseId: string; results: CaseResultLite[]; contenders: JamContender[]; cat: CategoryInfo; testName: string }) {
  const people = contenders.filter((c) => results.some((r) => r.testId === JAM_TEST_ID && r.caseId === caseId && r.contestantId === c.id && r.repeat === 0));
  const entries = people.map((c) => ({ c, e: entryOf(results.find((r) => r.testId === JAM_TEST_ID && r.caseId === caseId && r.contestantId === c.id && r.repeat === 0)!) }));
  const genre = (entries[0]?.e.genre ?? 'flappy') as JamGenre;
  const g = jamGenreInfo(genre);
  const best = entries.filter((x) => (x.e.score ?? 0) > 0).sort((a, b) => compareEntries(a.e, b.e))[0];
  const n = entries.length;
  return (
    <div className="s-moment s-jam" data-moment="jam">
      <div className="mo-head">
        <div className="x-top">
          <span className="pcat" style={{ ['--cc' as string]: cat.color } as CSSProperties}>
            <i />
            {cat.name}
          </span>
          <span className="x-count">
            {testName} · Round {g?.round} of 5
          </span>
        </div>
        <h1 className="mo-title">{g?.label ?? genre}: {best ? `${best.c.label} builds the best game` : 'no working game'}</h1>
        <div className="mo-sub">Same brief for every model. The screens replay the recorded playtest.</div>
      </div>
      <div className="s-jam-row" style={{ gridTemplateColumns: `repeat(${Math.max(1, n)}, minmax(0, ${n <= 2 ? '720px' : '1fr'}))`, justifyContent: 'center' }}>
        {entries.map(({ c, e }, i) => (
          <GameCabinet
            key={c.id}
            entry={e}
            urlFor={(file) => artifactUrl(runId, file)}
            marquee={g?.marquee ?? 'PLAY'}
            model={c.baseline ? 'Random guessing' : c.label}
            color={c.baseline ? '#6b6b7b' : c.color}
            size={n <= 3 ? 'lg' : 'md'}
            winner={best?.c.id === c.id}
            delayMs={i * 140}
            footer={
              <>
                {e.line}
                {e.quote && <span className="s-jam-quote">“{e.quote}”</span>}
              </>
            }
          />
        ))}
      </div>
    </div>
  );
}

export function JamWinnerSlide({ runId, results, contenders, testName }: { runId: string; results: CaseResultLite[]; contenders: JamContender[]; testName: string }) {
  const board = jamBoard(results, contenders.map((c) => c.id));
  const goty = board.gameOfTheJam;
  const who = goty ? contenders.find((c) => c.id === goty.model) : undefined;
  const ranked = board.models.filter((m) => board.totals.get(m)?.score !== null).sort((a, b) => (board.totals.get(b)!.score ?? 0) - (board.totals.get(a)!.score ?? 0) || (board.totals.get(b)!.creativity ?? 0) - (board.totals.get(a)!.creativity ?? 0));
  const g = goty ? jamGenreInfo(goty.entry.genre) : undefined;
  return (
    <div className="s-moment s-jam" data-moment="jam-winner">
      <div className="mo-head">
        <div className="x-top">
          <span className="x-count">{testName} · the verdict</span>
        </div>
        <h1 className="mo-title">{who ? `Game of the Jam: ${who.label}` : 'No game was scored'}</h1>
        <div className="mo-sub">Best average over the five rounds. Its best game is on the machine.</div>
      </div>
      {goty && who && (
        <div className="s-jam-win">
          <GameCabinet entry={goty.entry} urlFor={(file) => artifactUrl(runId, file)} marquee={g?.marquee ?? 'WINNER'} model={`${who.label} · ${g?.short ?? ''}`} color={who.color} size="lg" winner footer={goty.entry.quote ? `“${goty.entry.quote}”` : goty.entry.line} />
          <div style={{ display: 'grid', gap: 22 }}>
            <TrophySvg className="s-jam-trophy" />
            <div className="k">Average score</div>
            <div className="big tnum">{pct100(board.totals.get(goty.model)!.score)}</div>
            <div className="why">
              {board.totals.get(goty.model)!.wins} of 5 genre crowns
              {board.totals.get(goty.model)!.visual !== null ? ` · visuals ${((board.totals.get(goty.model)!.visual ?? 0) * 10).toFixed(1)}/10` : ''} · creativity {((board.totals.get(goty.model)!.creativity ?? 0) * 10).toFixed(1)}/10
            </div>
            <div className="s-jam-table">
              {ranked.map((m, i) => {
                const c = contenders.find((x) => x.id === m);
                const t = board.totals.get(m)!;
                return (
                  <div key={m} className={i === 0 ? 'r first' : 'r'}>
                    <span className="tnum">{i + 1}</span>
                    <span style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <i style={{ background: c?.baseline ? '#6b6b7b' : c?.color, display: 'inline-block' }} />
                      {c?.baseline ? 'Random guessing' : (c?.label ?? m)}
                    </span>
                    <span className="tnum">{pct100(t.score)}</span>
                    <span className="tnum" style={{ color: 'var(--text-3)' }}>
                      {t.wins} crown{t.wins === 1 ? '' : 's'}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
