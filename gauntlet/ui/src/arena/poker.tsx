/**
 * Heads-up poker table drawn from the engine's snapshots: felt, both players'
 * hole cards (the viewer sees both; each model only ever saw its own), the
 * community cards dealt street by street, pot, stacks, bets, the dealer
 * button, every action of the hand and the showdown.
 */
import type { CSSProperties } from 'react';
import { cx } from '../components/ui.tsx';
import type { ArenaMove, PokerSnapshot } from './types.ts';
import './formats.css';

const SUIT: Record<string, string> = { s: '♠', h: '♥', d: '♦', c: '♣' };
const SUIT_NAME: Record<string, string> = { s: 'spades', h: 'hearts', d: 'diamonds', c: 'clubs' };
const RANK_NAME: Record<string, string> = { T: '10', J: 'J', Q: 'Q', K: 'K', A: 'A' };

export interface TablePlayer {
  label: string;
  color: string;
}

export function PlayingCard({ card, hidden, small, win, delay }: { card?: string; hidden?: boolean; small?: boolean; win?: boolean; delay?: number }) {
  if (!card || hidden) return <span className={cx('pk-card back', small && 'sm')} aria-label="face-down card" />;
  const r = RANK_NAME[card[0]!] ?? card[0]!;
  const s = card[1]!;
  const red = s === 'h' || s === 'd';
  return (
    <span className={cx('pk-card', red && 'red', small && 'sm', win && 'win')} style={delay !== undefined ? ({ animationDelay: `${delay}ms` } as CSSProperties) : undefined} aria-label={`${r} of ${SUIT_NAME[s]}`} role="img">
      <span className="pk-r">{r}</span>
      <span className="pk-s">{SUIT[s]}</span>
    </span>
  );
}

const signed = (n: number) => (n > 0 ? `+${n}` : String(n));

function Seat({ snap, side, player, pos, thinking }: { snap: PokerSnapshot; side: 0 | 1; player: TablePlayer; pos: 'left' | 'right'; thinking?: boolean }) {
  const ended = snap.ended;
  const won = ended && ended.winner === side;
  const lastAct = [...snap.actions].reverse().find((a) => a.side === side && a.street === Math.max(0, ...snap.actions.map((x) => x.street)));
  const folded = ended?.how === 'fold' && ended.winner !== side;
  const best = ended?.hands?.[side];
  const active = snap.toAct === side && !ended;
  return (
    <div className={cx('pk-seat', pos, active && 'active', won && 'won', folded && 'folded')} style={{ ['--c' as string]: player.color } as CSSProperties}>
      <div className="pk-hole">
        {snap.hole[side].map((c, i) => (
          <PlayingCard key={`${snap.handNo}-${c}`} card={c} win={Boolean(won && best?.cards.includes(c))} delay={i * 90} />
        ))}
      </div>
      <div className="pk-plate">
        <span className="pk-bar" aria-hidden="true" />
        <div className="pk-who">
          <strong className="ellipsis">{player.label}</strong>
          <span className="pk-stack tnum">
            {snap.stacks[side]} chips
            {snap.button === side && (
              <span className="pk-dealer" title="Dealer button (small blind)">
                D
              </span>
            )}
          </span>
        </div>
        <span className={cx('pk-net tnum', snap.net[side] > 0 ? 'up' : snap.net[side] < 0 ? 'down' : '')} title="Chips won so far in this game">
          {signed(snap.net[side])}
        </span>
      </div>
      <div className="pk-status">
        {won && ended ? (
          <span className="pk-tag win">
            wins {Math.abs(ended.delta[side])} {best ? `· ${best.name}` : ''}
          </span>
        ) : folded ? (
          <span className="pk-tag muted">folded</span>
        ) : best ? (
          <span className="pk-tag">{best.name}</span>
        ) : active && thinking ? (
          <span className="pk-tag think">
            <span className="dots" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>{' '}
            thinking
          </span>
        ) : lastAct ? (
          <span className={cx('pk-tag', lastAct.allIn && 'allin')}>{lastAct.text}</span>
        ) : null}
      </div>
    </div>
  );
}

export function PokerTable({ snap, players, thinking, className }: { snap: PokerSnapshot; players: [TablePlayer, TablePlayer]; thinking?: boolean; className?: string }) {
  if (!snap || snap.kind !== 'poker') return <div className="pk-table empty" />;
  const ended = snap.ended;
  const slots = Array.from({ length: 5 }, (_, i) => snap.board[i]);
  const winCards = new Set(ended?.hands && ended.winner !== null ? ended.hands[ended.winner].cards : []);
  const banner = ended
    ? ended.winner === null
      ? `Split pot · ${ended.hands?.[0]?.name ?? ''}`
      : `${players[ended.winner].label} wins ${Math.abs(ended.delta[ended.winner])} chips${ended.how === 'fold' ? ' (opponent folded)' : ` with ${ended.hands?.[ended.winner]?.name ?? 'the best hand'}`}`
    : null;
  return (
    <div className={cx('pk-table', className)} role="img" aria-label={`Poker table, hand ${snap.handNo} of ${snap.hands}, ${snap.street}, pot ${snap.pot}`}>
      <div className="pk-felt">
        <div className="pk-head">
          <span className="pk-hand tnum">
            Hand {snap.handNo} <span className="muted">of {snap.hands}</span>
          </span>
          <span className={cx('pk-street', ended && 'done')}>{snap.street}</span>
        </div>
        <div className="pk-row">
          <Seat snap={snap} side={0} player={players[0]} pos="left" thinking={thinking} />
          <div className="pk-center">
            <div className="pk-board">
              {slots.map((c, i) => (c ? <PlayingCard key={`${snap.handNo}-${c}`} card={c} win={winCards.has(c)} delay={i < 3 ? i * 80 : 0} /> : <span key={i} className="pk-slot" />))}
            </div>
            <div className="pk-pot">
              <span className="pk-chips" aria-hidden="true">
                <i />
                <i />
                <i />
              </span>
              <span>
                Pot <b className="tnum">{snap.pot}</b>
              </span>
              {!ended && (snap.bets[0] || snap.bets[1]) ? (
                <span className="muted tnum">
                  · bets {snap.bets[0]} / {snap.bets[1]}
                </span>
              ) : null}
            </div>
            {banner && (
              <div className={cx('pk-banner', ended?.winner === null && 'split')} style={{ ['--c' as string]: ended?.winner !== null && ended ? players[ended.winner].color : 'var(--text-3)' } as CSSProperties} role="status">
                {banner}
              </div>
            )}
          </div>
          <Seat snap={snap} side={1} player={players[1]} pos="right" thinking={thinking} />
        </div>
      </div>
      <div className="pk-note">
        <span aria-hidden="true">👁</span> The viewer can see both hands. Each model only ever saw its own cards.
      </div>
    </div>
  );
}

/** The actions of the hand on the table, grouped by street (shown beside the table). */
export function HandLog({ snap, players }: { snap: PokerSnapshot; players: [TablePlayer, TablePlayer] }) {
  const streets = ['Pre-flop', 'Flop', 'Turn', 'River'];
  const by = streets.map((name, i) => ({ name, acts: snap.actions.filter((a) => a.street === i) })).filter((s) => s.acts.length);
  return (
    <div className="pk-log">
      {by.map((s) => (
        <div key={s.name} className="pk-log-st">
          <span className="pk-log-h">{s.name}</span>
          {s.acts.map((a, i) => (
            <span key={i} className={cx('pk-log-a', a.allIn && 'allin')} style={{ ['--c' as string]: players[a.side].color } as CSSProperties}>
              <i aria-hidden="true" />
              <b>{players[a.side].label}</b> {a.text}
            </span>
          ))}
        </div>
      ))}
    </div>
  );
}

/** Compact summary of a finished poker game (match cards, latest result). */
export function PokerSummary({ snap, players }: { snap: PokerSnapshot; players: [TablePlayer, TablePlayer] }) {
  if (!snap || snap.kind !== 'poker') return null;
  const wins = [0, 1].map((s) => snap.results.filter((r) => r.winner === s).length);
  const max = Math.max(1, ...snap.results.map((r) => Math.abs(r.delta[0])));
  return (
    <div className="pk-sum">
      <div className="pk-sum-net">
        {[0, 1].map((s) => (
          <div key={s} className="pk-sum-p" style={{ ['--c' as string]: players[s as 0 | 1].color } as CSSProperties}>
            <span className="ellipsis">{players[s as 0 | 1].label}</span>
            <b className={cx('tnum', snap.net[s as 0 | 1] > 0 ? 'up' : snap.net[s as 0 | 1] < 0 ? 'down' : '')}>{signed(snap.net[s as 0 | 1])}</b>
            <span className="muted tnum">
              {wins[s]} {wins[s] === 1 ? 'hand' : 'hands'} won
            </span>
          </div>
        ))}
      </div>
      <div className="pk-sum-bars" aria-label="Chips won per hand (seat 1 up, seat 2 down)">
        {snap.results.map((r) => (
          <span key={r.no} title={`Hand ${r.no + 1}: ${signed(r.delta[0])} for ${players[0].label} (${r.how})`}>
            <u>{r.delta[0] > 0 && <i style={{ height: `${(r.delta[0] / max) * 100}%`, background: players[0].color }} />}</u>
            <u>{r.delta[1] > 0 && <i style={{ height: `${(r.delta[1] / max) * 100}%`, background: players[1].color }} />}</u>
          </span>
        ))}
      </div>
    </div>
  );
}

/** "H3 Flop: raise to 12" → hand number, for grouping move lists. */
export function handOf(m: ArenaMove): number {
  return Number(m.label.match(/^H(\d+)/)?.[1] ?? 0);
}
