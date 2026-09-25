/**
 * Heads-up poker table drawn from the engine's snapshots: felt, both players'
 * hole cards (the viewer sees both; each model only ever saw its own), the
 * community cards dealt street by street, chip piles for stacks, bets and the
 * pot (they grow and shrink as chips move), the dealer button, the betting
 * line of the hand as a street-by-street timeline, and the showdown with both
 * hands spelled out on the hand-rank ladder.
 */
import type { CSSProperties } from 'react';
import { cx } from '../components/ui.tsx';
import { TweenNumber } from '../components/viz/TweenNumber.tsx';
import type { ArenaMove, PokerSnapshot } from './types.ts';
import { HAND_LADDER, chipCount, handCategory } from './analysis.ts';
import './formats.css';
import './poker-visual.css';

const SUIT: Record<string, string> = { s: '♠', h: '♥', d: '♦', c: '♣' };
const SUIT_NAME: Record<string, string> = { s: 'spades', h: 'hearts', d: 'diamonds', c: 'clubs' };
const RANK_NAME: Record<string, string> = { T: '10', J: 'J', Q: 'Q', K: 'K', A: 'A' };
const STREETS = ['Pre-flop', 'Flop', 'Turn', 'River'];

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

const CHIP_COLORS = ['#dc2626', '#2563eb', '#111827', '#16a34a', '#f59e0b', '#7c3aed'];

/** A pile of poker chips whose height follows the amount; new chips drop onto the pile. */
export function ChipPile({ amount, label, className, tone }: { amount: number; label?: string; className?: string; tone?: 'pot' | 'bet' | 'stack' }) {
  const n = chipCount(amount);
  return (
    <span className={cx('pk-pile', tone && `t-${tone}`, className)} aria-label={label ?? `${amount} chips`} role="img">
      <span className="pk-pile-chips" style={{ height: `${Math.max(1, n) * 5 + 10}px` }}>
        {Array.from({ length: n }, (_, i) => (
          <i key={i} style={{ bottom: `${i * 5}px`, background: CHIP_COLORS[i % CHIP_COLORS.length] }} />
        ))}
      </span>
    </span>
  );
}

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
        <ChipPile amount={snap.stacks[side]} tone="stack" label={`${player.label}: ${snap.stacks[side]} chips behind`} />
      </div>
      <div className="pk-plate">
        <span className="pk-bar" aria-hidden="true" />
        <div className="pk-who">
          <strong className="ellipsis">{player.label}</strong>
          <span className="pk-stack tnum">
            <TweenNumber value={snap.stacks[side]} /> chips
            <span className={cx('pk-net tnum', snap.net[side] > 0 ? 'up' : snap.net[side] < 0 ? 'down' : '')} title="Chips won so far in this game">
              {signed(snap.net[side])}
            </span>
            {snap.button === side && (
              <span className="pk-dealer" title="Dealer button (small blind)">
                D
              </span>
            )}
          </span>
        </div>
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

/** The showdown: both best hands spelled out, who beats whom, and where each sits on the hand-rank ladder. */
export function Showdown({ snap, players }: { snap: PokerSnapshot; players: [TablePlayer, TablePlayer] }) {
  const e = snap.ended;
  if (!e || e.how !== 'showdown' || !e.hands) return null;
  const cat = [handCategory(e.hands[0].name), handCategory(e.hands[1].name)];
  const w = e.winner;
  return (
    <div className="pk-sd" role="status" aria-label="Showdown">
      <span className="pk-sd-k">Showdown</span>
      <div className="pk-sd-line">
        {([0, 1] as const).map((s) => (
          <span key={s} className={cx('pk-sd-hand', w === s && 'win', w !== null && w !== s && 'lose')} style={{ ['--c' as string]: players[s].color } as CSSProperties}>
            <b className="ellipsis">{players[s].label}</b>
            <span className="pk-sd-name">{e.hands![s].name}</span>
            <span className="pk-sd-cards">
              {e.hands![s].cards.map((c, i) => (
                <PlayingCard key={`${c}${i}`} card={c} small win={w === s} />
              ))}
            </span>
          </span>
        ))}
        <span className="pk-sd-vs" aria-hidden="true">
          {w === null ? 'TIE' : w === 0 ? 'BEATS ▸' : '◂ BEATS'}
        </span>
      </div>
      <ol className="pk-ladder" aria-label="Hand ranks, weakest to strongest">
        {HAND_LADDER.map((name, i) => {
          const here = ([0, 1] as const).filter((s) => cat[s] === i);
          return (
            <li key={name} className={cx(here.length > 0 && 'on')}>
              <span>{name}</span>
              {here.map((s) => (
                <i key={s} style={{ background: players[s].color }} title={players[s].label} />
              ))}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/** The betting line of the hand on the table: one column per street with the cards dealt and every action. */
export function BetTimeline({ snap, players }: { snap: PokerSnapshot; players: [TablePlayer, TablePlayer] }) {
  const cur = STREETS.findIndex((s) => s.toLowerCase() === snap.street.toLowerCase().replace('preflop', 'pre-flop'));
  const reached = Math.max(0, ...snap.actions.map((a) => a.street), cur);
  const dealt = [[], snap.board.slice(0, 3), snap.board.slice(3, 4), snap.board.slice(4, 5)];
  const e = snap.ended;
  return (
    <div className="pk-tl" aria-label={`Betting in hand ${snap.handNo}`}>
      {STREETS.map((name, i) => {
        const acts = snap.actions.filter((a) => a.street === i);
        const future = i > reached;
        return (
          <div key={name} className={cx('pk-tl-col', i === reached && !e && 'on', future && 'future')}>
            <div className="pk-tl-h">
              <span>{name}</span>
              <span className="pk-tl-cards">
                {dealt[i]!.map((c) => (
                  <PlayingCard key={c} card={c} small />
                ))}
              </span>
            </div>
            <ol className="pk-tl-acts">
              {future ? (
                <li className="pk-tl-none">{e ? 'not reached' : '—'}</li>
              ) : acts.length === 0 ? (
                <li className="pk-tl-none">no betting</li>
              ) : (
                acts.map((a, k) => (
                  <li key={k} className={cx('pk-tl-a', a.allIn && 'allin', /fold/.test(a.text) && 'fold')} style={{ ['--c' as string]: players[a.side].color } as CSSProperties}>
                    <i aria-hidden="true" />
                    <b className="ellipsis">{players[a.side].label}</b>
                    <span>{a.text.replace(/^posts the /, '')}</span>
                  </li>
                ))
              )}
            </ol>
          </div>
        );
      })}
      {e && (
        <div className="pk-tl-end" style={{ ['--c' as string]: e.winner === null ? 'var(--text-3)' : players[e.winner].color } as CSSProperties}>
          {e.winner === null ? 'Split pot' : `${players[e.winner].label} wins ${Math.abs(e.delta[e.winner])} chips${e.how === 'fold' ? ' (opponent folded)' : ' at showdown'}`}
        </div>
      )}
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
            <div className="pk-bets" aria-label={`Bets this street: ${snap.bets[0]} and ${snap.bets[1]}`}>
              {([0, 1] as const).map((s) => (
                <span key={s} className={cx('pk-bet', s === 1 && 'right', !(snap.bets[s] > 0) || ended ? 'empty' : '')} style={{ ['--c' as string]: players[s].color } as CSSProperties}>
                  {snap.bets[s] > 0 && !ended && (
                    <>
                      <ChipPile amount={snap.bets[s]} tone="bet" />
                      <b className="tnum">{snap.bets[s]}</b>
                    </>
                  )}
                </span>
              ))}
            </div>
            <div className="pk-pot">
              <ChipPile amount={snap.pot} tone="pot" label={`Pot: ${snap.pot} chips`} />
              <span className="pk-pot-n">
                Pot <b className="tnum">{<TweenNumber value={snap.pot} />}</b>
              </span>
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
      {ended?.how === 'showdown' ? <Showdown snap={snap} players={players} /> : <BetTimeline snap={snap} players={players} />}
      <div className="pk-note">
        <span aria-hidden="true">👁</span> The viewer can see both hands. Each model only ever saw its own cards.
      </div>
    </div>
  );
}

/** The actions of the hand on the table, grouped by street (shown beside the table). */
export function HandLog({ snap, players }: { snap: PokerSnapshot; players: [TablePlayer, TablePlayer] }) {
  const by = STREETS.map((name, i) => ({ name, acts: snap.actions.filter((a) => a.street === i) })).filter((s) => s.acts.length);
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
