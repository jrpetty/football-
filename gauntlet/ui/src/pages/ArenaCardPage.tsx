/**
 * Full-screen Arena "match cards" for the video: the bracket, one card per
 * decided match (both players, the score, every game's final board) and the
 * champion. Drawn on the Presenter's fixed 1920×1080 stage, scaled to fit.
 *
 * Keys: → / Space next · ← back · Home / End · F full screen · Esc back to the tournament.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { useHotkeys } from '../hooks.ts';
import { navigate, pathOf, setQuery, useRoute } from '../router.tsx';
import { BrandMark } from '../components/Brand.tsx';
import { Icon } from '../components/icons.tsx';
import { cx } from '../components/ui.tsx';
import { fmtCost } from '../format.ts';
import { GameBoard } from '../arena/boards.tsx';
import { Bracket, RoundRobin } from '../arena/Bracket.tsx';
import { sidesOf } from '../arena/parts.tsx';
import { entrantMap, formatName, pts, useTournament } from '../arena/useTournament.ts';
import type { ArenaEntrant, MatchState, TournamentDetail } from '../arena/types.ts';
import '../arena/arena.css';

const W = 1920;
const H = 1080;

type Slide = { kind: 'bracket' } | { kind: 'match'; m: MatchState } | { kind: 'champion' };

function useStageScale(): number {
  const calc = () => Math.min(window.innerWidth / W, window.innerHeight / H);
  const [k, setK] = useState(calc);
  useEffect(() => {
    const on = () => setK(calc());
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  return k;
}

function PlayerPanel({ e, score, won, side }: { e: ArenaEntrant | undefined; score: number; won: boolean; side: 'left' | 'right' }) {
  return (
    <div className={cx('mc-player', side, won && 'won')} style={{ ['--c' as string]: e?.color ?? 'var(--text-3)' } as CSSProperties}>
      <span className="mc-bar" aria-hidden="true" />
      <div className="mc-names">
        <span className="mc-seed">Seed {e?.seed}</span>
        <strong>{e?.label}</strong>
        <span className="mc-vendor">{e?.vendor}</span>
      </div>
      <b className="mc-score tnum">{pts(score)}</b>
      {won && <span className="mc-adv">{side === 'left' ? 'WINS ▸' : '◂ WINS'}</span>}
    </div>
  );
}

function MatchCard({ d, m }: { d: TournamentDetail; m: MatchState }) {
  const ents = entrantMap(d);
  const sides = sidesOf(d.manifest.game.id);
  const [a, b] = m.players as [string, string];
  const games = m.games.filter((s) => s.game);
  return (
    <div className="mc">
      <div className="mc-head">
        <span className="mc-round">{m.roundName}</span>
        <span className="mc-game">{d.manifest.game.name}</span>
      </div>
      <div className="mc-versus">
        <PlayerPanel e={ents.get(a)} score={m.score[0]} won={m.winner === a} side="left" />
        <span className="mc-vs">VS</span>
        <PlayerPanel e={ents.get(b)} score={m.score[1]} won={m.winner === b} side="right" />
      </div>
      <div className="mc-games" style={{ gridTemplateColumns: `repeat(${Math.min(games.length, 4)}, minmax(0, 1fr))` }}>
        {games.slice(0, 4).map((s) => {
          const g = s.game!;
          const w = g.winner === null ? null : ents.get(g.players[g.winner]);
          return (
            <div key={s.key} className="mc-g">
              <div className="mc-g-board">
                <GameBoard gameId={d.manifest.game.id} snap={g.lastSnapshot} colors={[sides[0]!.color, sides[1]!.color]} />
              </div>
              <div className="mc-g-t">
                <span className="mc-g-n">{s.suddenDeath ? 'Sudden death' : `Game ${s.gameNo}`}</span>
                <span className="mc-g-p">
                  {sides[0]!.name}: {ents.get(g.players[0])?.label}
                </span>
                <b style={{ color: w?.color }}>{w ? `${w.label} wins` : 'Draw'}</b>
                <span className="mc-g-r">
                  {g.reason} · {g.plies} moves
                </span>
              </div>
            </div>
          );
        })}
      </div>
      <div className="mc-foot">
        <span>{m.summary}</span>
        <span className="tnum">
          Illegal moves {m.illegal[0]} / {m.illegal[1]} · cost {fmtCost(m.cost[0])} / {fmtCost(m.cost[1])}
        </span>
      </div>
    </div>
  );
}

function ChampionCard({ d }: { d: TournamentDetail }) {
  const ents = entrantMap(d);
  const champ = d.state.champion ? ents.get(d.state.champion) : undefined;
  const row = d.state.standings.find((s) => s.contestantId === d.state.champion);
  const path = d.state.matches.filter((m) => m.players.includes(d.state.champion) && m.status === 'done');
  if (!champ) return <div className="mc-empty">The champion is not decided yet.</div>;
  return (
    <div className="champ-card" style={{ ['--c' as string]: champ.color } as CSSProperties}>
      <div className="champ-trophy" aria-hidden="true">
        🏆
      </div>
      <span className="champ-k">{d.manifest.game.name} champion</span>
      <strong className="champ-name">{champ.label}</strong>
      <span className="champ-v">{champ.vendor}</span>
      <div className="champ-path">
        {path.map((m) => {
          const idx = m.players[0] === champ.id ? 0 : 1;
          const opp = ents.get(m.players[1 - idx] ?? '');
          return (
            <div key={m.id} className="champ-step">
              <span className="k">{m.roundName}</span>
              <span>
                beat <b style={{ color: opp?.color }}>{opp?.label}</b>
              </span>
              <b className="tnum">
                {pts(m.score[idx])}–{pts(m.score[1 - idx]!)}
              </b>
            </div>
          );
        })}
      </div>
      {row && (
        <div className="champ-stats tnum">
          <span>
            <b>{row.wins}</b> wins
          </span>
          <span>
            <b>{row.draws}</b> draws
          </span>
          <span>
            <b>{row.losses}</b> losses
          </span>
          <span>
            <b>{row.illegal}</b> illegal moves
          </span>
          <span>
            <b>{fmtCost(row.costUsd)}</b> spent
          </span>
        </div>
      )}
    </div>
  );
}

function caption(slide: Slide, d: TournamentDetail): { text: string; fine: string } {
  const ents = entrantMap(d);
  const G = d.manifest.settings.gamesPerMatch;
  if (slide.kind === 'bracket')
    return {
      text: `${d.manifest.entrants.length} AI models, one ${d.manifest.game.name} ${d.manifest.settings.format === 'knockout' ? 'knockout' : 'round-robin'}. Every pairing played ${G} games with the sides swapped, so neither model got the first move twice.`,
      fine: `Seeded by ${d.manifest.settings.seeding === 'index' ? 'Gauntlet Index' : 'manual order'} · fingerprint ${d.manifest.fingerprint}`,
    };
  if (slide.kind === 'match') {
    const m = slide.m;
    const how = m.decidedBy === 'sudden-death' ? 'The match was level, so it went to a sudden-death game.' : m.decidedBy && m.decidedBy !== 'games' ? `The games were level, so it was decided on ${m.decidedBy}.` : 'Win = 1 point, draw = ½.';
    return { text: `${m.summary}. Each board is the final position of one game. ${how}`, fine: `Illegal moves get one retry, then a random legal move and a strike; ${d.manifest.settings.maxStrikes} strikes lose` };
  }
  const champ = d.state.champion ? ents.get(d.state.champion)?.label : '';
  return { text: `${champ} won the ${d.manifest.game.name} tournament. Every move was checked by the harness and every prompt is saved for anyone to audit.`, fine: `Total spend ${fmtCost(d.state.costUsd)} · ${d.state.gamesDone} games` };
}

export default function ArenaCardPage({ id }: { id: string }) {
  const route = useRoute();
  const { detail: d, live } = useTournament(id);
  const scale = useStageScale();
  const liveKeys = useMemo(() => new Set(live.map((g) => g.key)), [live]);
  const slides: Slide[] = useMemo(() => {
    if (!d) return [];
    const out: Slide[] = [{ kind: 'bracket' }];
    const done = d.state.matches.filter((m) => m.status === 'done').sort((a, b) => a.round - b.round || a.slot - b.slot);
    for (const m of done) out.push({ kind: 'match', m });
    if (d.state.champion) out.push({ kind: 'champion' });
    return out;
  }, [d]);
  const wanted = route.query.get('match');
  const [idx, setIdx] = useState(0);
  useEffect(() => {
    if (!wanted) return;
    const i = slides.findIndex((s) => s.kind === 'match' && s.m.id === wanted);
    if (i >= 0) setIdx(i);
  }, [wanted, slides]);
  const go = useCallback(
    (i: number) => {
      const n = Math.max(0, Math.min(slides.length - 1, i));
      setIdx(n);
      const s = slides[n];
      setQuery({ match: s?.kind === 'match' ? s.m.id : undefined });
    },
    [slides],
  );
  const toggleFs = () => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void document.documentElement.requestFullscreen?.().catch(() => undefined);
  };
  useHotkeys({
    ArrowRight: () => go(idx + 1),
    ' ': (e) => (e.preventDefault(), go(idx + 1)),
    PageDown: () => go(idx + 1),
    ArrowLeft: () => go(idx - 1),
    PageUp: () => go(idx - 1),
    Home: () => go(0),
    End: () => go(slides.length - 1),
    f: toggleFs,
    Escape: () => navigate(pathOf('arena', id)),
  });

  const slide = slides[Math.min(idx, Math.max(0, slides.length - 1))];
  if (!d || !slide) {
    return (
      <div className="deck">
        <div className="deck-stage" style={{ transform: `translate(-50%, -50%) scale(${scale})` }}>
          <div className="mc-empty">Loading…</div>
        </div>
      </div>
    );
  }
  const cap = caption(slide, d);
  return (
    <div className="deck ar-deck">
      <div className="deck-stage" style={{ transform: `translate(-50%, -50%) scale(${scale})` }}>
        <div className="deck-bg" aria-hidden="true">
          <div className="glow a" />
          <div className="glow b" />
          <div className="grid" />
        </div>
        <header className="d-top">
          <span className="d-brand">
            <BrandMark className="d-mark" />
            <span className="d-word">GAUNTLET</span>
          </span>
          <span className="d-run">The Arena · {d.manifest.name}</span>
          <span className="d-spacer" />
          <span className="d-section">
            {d.manifest.game.name} · {formatName(d.manifest.settings.format)}
          </span>
          <span className="d-prog" aria-label={`Card ${idx + 1} of ${slides.length}`}>
            <span className="tnum">
              {idx + 1} / {slides.length}
            </span>
            <i>
              <b style={{ width: `${((idx + 1) / slides.length) * 100}%` }} />
            </i>
          </span>
        </header>
        <main className="d-body ar-d-body" key={idx}>
          {slide.kind === 'bracket' && (
            <div className="mc-bracket">
              <h1 className="mc-title">{d.manifest.name}</h1>
              {d.manifest.settings.format === 'knockout' ? <Bracket d={d} liveKeys={liveKeys} /> : <RoundRobin d={d} />}
            </div>
          )}
          {slide.kind === 'match' && <MatchCard d={d} m={slide.m} />}
          {slide.kind === 'champion' && <ChampionCard d={d} />}
        </main>
        <footer className="d-cap" key={`cap-${idx}`}>
          <span className="d-cap-k">What you’re seeing</span>
          <span className="d-cap-t">{cap.text}</span>
          <span className="d-cap-f">{cap.fine}</span>
        </footer>
      </div>
      <div className="ar-deck-ctrl">
        <button className="btn sm icon" onClick={() => go(idx - 1)} aria-label="Previous card" disabled={idx === 0}>
          <Icon.StepBack />
        </button>
        <button className="btn sm icon" onClick={() => go(idx + 1)} aria-label="Next card" disabled={idx >= slides.length - 1}>
          <Icon.StepFwd />
        </button>
        <button className="btn sm icon" onClick={toggleFs} aria-label="Full screen" title="Full screen (F)">
          <Icon.Maximize />
        </button>
        <button className="btn sm" onClick={() => navigate(pathOf('arena', id))} title="Back (Esc)">
          <Icon.X /> Exit
        </button>
      </div>
    </div>
  );
}
