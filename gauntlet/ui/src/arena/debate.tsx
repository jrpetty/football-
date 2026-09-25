/**
 * Debate / Courtroom stage: the motion (or case file), a round indicator, two
 * lecterns with speech bubbles (streaming live), word counters with the
 * over-limit penalty, and the judges' scorecards reveal.
 */
import { useEffect, useRef } from 'react';
import type { CSSProperties } from 'react';
import { Link } from '../router.tsx';
import { cx } from '../components/ui.tsx';
import { caseById } from '../../../src/arena/games/debate-bank.ts';
import type { ArenaJudging, DebateSnapshot, JudgeVerdict, Speech } from './types.ts';
import type { TablePlayer } from './poker.tsx';
import './formats.css';

export type VerdictView = Omit<ArenaJudging, 'transcript' | 'metrics'>;

const RUBRIC_LABEL: Record<string, string> = { argument: 'Argument', rebuttal: 'Rebuttal', evidence: 'Evidence', clarity: 'Clarity', rules: 'Rules' };

const words = (t: string) => (t.match(/\S+/g) ?? []).length;

function Bubble({ speech, live, text, limit, roundName, player, role, current, exhibits }: { speech?: Speech; live?: boolean; text: string; limit: number; roundName: string; player: TablePlayer; role: string; current: boolean; exhibits?: Set<string> }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (live && ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [text, live]);
  const n = speech ? speech.words : words(text);
  const over = n > limit;
  const pct = Math.min(100, (Math.min(n, limit) / limit) * 100);
  const body = speech?.missing ? '(No speech: the model failed to reply.)' : text;
  // Exhibit references light up (courtroom).
  const parts = exhibits ? body.split(/(Exhibits? [A-E](?:\s*(?:,|and|&)\s*[A-E])*)/g) : [body];
  return (
    <div className={cx('db-bubble', current && 'current', live && 'live', speech?.missing && 'missing')} style={{ ['--c' as string]: player.color } as CSSProperties}>
      <div className="db-b-head">
        <span className="db-b-round">{roundName}</span>
        <span className="db-b-role">{role}</span>
      </div>
      <div className="db-b-text" ref={ref}>
        {parts.map((p, i) => (i % 2 === 1 ? <mark key={i}>{p}</mark> : <span key={i}>{p}</span>))}
        {live && <span className="caret" aria-hidden="true" />}
      </div>
      <div className="db-b-foot">
        <span className={cx('db-wc tnum', over && 'over')} title="Words used / word limit">
          <span className="db-wc-bar" aria-hidden="true">
            <i style={{ width: `${pct}%` }} />
          </span>
          {n} / {limit} words
        </span>
        {speech && speech.cut > 0 && (
          <span className="db-pen" title="The harness cut the speech at the word limit; the judges see this as a rule-following penalty.">
            ✂ {speech.cut} words cut · rule penalty
          </span>
        )}
        {live && over && <span className="db-pen">over the limit: will be cut</span>}
      </div>
    </div>
  );
}

function Lectern({ player, role, speaking, won }: { player: TablePlayer; role: string; speaking: boolean; won: boolean }) {
  return (
    <div className={cx('db-lectern', speaking && 'speaking', won && 'won')} style={{ ['--c' as string]: player.color } as CSSProperties}>
      <div className="db-lec-top">
        <span className="db-mic" aria-hidden="true" />
        <strong className="ellipsis">{player.label}</strong>
        <span className="db-role">{role}</span>
      </div>
      <div className="db-lec-body" aria-hidden="true" />
    </div>
  );
}

function Scorecard({ v, players, i }: { v: JudgeVerdict; players: [TablePlayer, TablePlayer]; i: number }) {
  const keys = v.scores ? Object.keys(v.scores[0]) : [];
  const tot = (s: 0 | 1) => (v.scores ? Object.values(v.scores[s]).reduce((a, b) => a + b, 0) : 0);
  const pick = v.winner === null ? null : players[v.winner];
  return (
    <div className={cx('db-card', v.error && 'err')} style={{ animationDelay: `${300 + i * 650}ms`, ['--c' as string]: pick?.color ?? 'var(--text-3)' } as CSSProperties}>
      <div className="db-card-head">
        <span className="db-card-j">{v.judgeLabel}</span>
        <span className="muted">{v.human ? 'human judge' : v.vendor}</span>
      </div>
      {v.error ? (
        <div className="db-card-err">Could not judge: {v.error}</div>
      ) : (
        <>
          <div className="db-card-pick">
            <span className="muted">picks</span> <b>{pick?.label}</b>
          </div>
          {v.scores && (
            <div className="db-card-rows">
              {keys.map((k) => (
                <div key={k} className="db-card-row">
                  <span className="k">{RUBRIC_LABEL[k] ?? k}</span>
                  <span className="tnum" style={{ color: players[0].color }}>
                    {v.scores![0][k]}
                  </span>
                  <span className="db-duel" aria-hidden="true">
                    <i style={{ width: `${v.scores![0][k]! * 10}%`, background: players[0].color }} />
                    <i style={{ width: `${v.scores![1][k]! * 10}%`, background: players[1].color }} />
                  </span>
                  <span className="tnum" style={{ color: players[1].color }}>
                    {v.scores![1][k]}
                  </span>
                </div>
              ))}
              <div className="db-card-row total">
                <span className="k">Total</span>
                <b className="tnum">{tot(0)}</b>
                <span />
                <b className="tnum">{tot(1)}</b>
              </div>
            </div>
          )}
          {v.rationale && <p className="db-card-why" title={v.rationale}>“{v.rationale}”</p>}
        </>
      )}
    </div>
  );
}

export function VerdictPanel({ verdict, players, judgeHref }: { verdict: VerdictView; players: [TablePlayer, TablePlayer]; judgeHref?: string }) {
  if (verdict.status !== 'judged') {
    return (
      <div className="db-verdict awaiting" role="status">
        <span className="eyebrow">The verdict</span>
        <h3>Awaiting human judging</h3>
        <p className="muted">{verdict.note ?? 'No judge model could decide this game.'}</p>
        {judgeHref && (
          <Link className="btn primary sm" to={judgeHref}>
            Judge it now
          </Link>
        )}
      </div>
    );
  }
  const w = verdict.winner === null ? null : players[verdict.winner];
  const votes = verdict.winner === null ? `${verdict.votes[0]}–${verdict.votes[1]}` : `${verdict.votes[verdict.winner]}–${verdict.votes[1 - verdict.winner]}`;
  return (
    <div className="db-verdict" role="status">
      <div className="db-v-banner" style={{ ['--c' as string]: w?.color ?? 'var(--text-3)' } as CSSProperties}>
        <span className={cx('db-v-kind', verdict.split && 'split')}>{verdict.decision}</span>
        <strong>{w ? `${w.label} wins` : 'A draw'}</strong>
        {verdict.verdicts.length > 1 && <span className="db-v-votes tnum">{votes}</span>}
      </div>
      <div className="db-cards">
        {verdict.verdicts.map((v, i) => (
          <Scorecard key={v.judgeId} v={v} players={players} i={i} />
        ))}
      </div>
      <p className="db-v-fine muted">
        Judges saw only “Side A” and “Side B”, in a random order, with every model name removed.
        {verdict.excludedVendors.length ? ` Judges from ${verdict.excludedVendors.join(' and ')} sat this one out (same vendor as a debater).` : ''}
      </p>
    </div>
  );
}

export interface DebateLiveInfo {
  toMove: 0 | 1;
  thinking: string;
  phase?: string;
}

export function DebateStage({ snap, players, live, className, judgeHref }: { snap: DebateSnapshot; players: [TablePlayer, TablePlayer]; live?: DebateLiveInfo; className?: string; judgeHref?: string }) {
  if (!snap || snap.kind !== 'debate') return <div className="db-stage empty" />;
  const court = snap.variant === 'courtroom';
  const verdict = snap.verdict as VerdictView | undefined;
  const judging = live?.phase === 'judging' && !verdict;
  const speaking = !verdict && !judging && live && snap.next ? snap.next.side : null;
  const round = snap.next && !verdict ? snap.next.round : 2;
  const lastBy = (s: 0 | 1) => [...snap.speeches].reverse().find((x) => x.side === s);
  const lastIdx = snap.speeches.length - 1;
  const cf = court ? caseById(snap.topicId) : undefined;
  const liveText = live?.thinking ?? '';
  const cited = new Set<string>();
  const currentText = speaking !== null ? liveText : (snap.speeches[lastIdx]?.text ?? '');
  for (const m of currentText.matchAll(/Exhibits? ([A-E](?:\s*(?:,|and|&)\s*[A-E])*)/g)) for (const l of m[1]!.match(/[A-E]/g) ?? []) cited.add(l);

  const col = (s: 0 | 1) => {
    const last = lastBy(s);
    const isLive = speaking === s;
    const current = isLive || (speaking === null && !verdict && snap.speeches[lastIdx]?.side === s) || (!live && snap.speeches[lastIdx]?.side === s && !verdict);
    const r = isLive ? snap.next!.round : (last?.round ?? 0);
    return (
      <div className={cx('db-col', s === 1 && 'right')}>
        {isLive ? (
          <Bubble live text={liveText} limit={snap.limits[r] ?? 180} roundName={snap.roundNames[r]!} player={players[s]} role={snap.sides[s]} current exhibits={court ? cited : undefined} />
        ) : last ? (
          <Bubble speech={last} text={last.text} limit={last.limit} roundName={snap.roundNames[last.round]!} player={players[s]} role={snap.sides[s]} current={current} exhibits={court ? cited : undefined} />
        ) : (
          <div className="db-bubble empty" style={{ ['--c' as string]: players[s].color } as CSSProperties}>
            <span className="muted">{s === 0 ? 'Opens the debate' : 'Replies second'}</span>
          </div>
        )}
        <Lectern player={players[s]} role={snap.sides[s]} speaking={isLive} won={verdict?.status === 'judged' && verdict.winner === s} />
      </div>
    );
  };

  return (
    <div className={cx('db-stage', court && 'court', className)}>
      <div className="db-top">
        <span className="eyebrow">{court ? `The case · ${cf?.charge ?? ''}` : 'The motion'}</span>
        <h2 className="db-motion">{court ? snap.title : `“${snap.title}”`}</h2>
        <div className="db-rounds" aria-label="Rounds">
          {snap.roundNames.map((name, i) => {
            const done = snap.speeches.filter((x) => x.round === i).length;
            return (
              <span key={name} className={cx('db-round', i === round && !verdict && !judging && snap.next && 'on', done === 2 && 'done')}>
                <b className="tnum">{i + 1}</b> {name} <span className="muted tnum">· {snap.limits[i]} words</span>
              </span>
            );
          })}
          <span className={cx('db-round', (judging || verdict) && 'on', verdict && 'done')}>
            <b>⚖</b> {court ? 'Verdict' : 'Judges'}
          </span>
        </div>
        {cf && (
          <div className="db-exhibits" aria-label="Exhibits">
            {cf.exhibits.map((e) => (
              <span key={e.id} className={cx('db-ex', cited.has(e.id) && 'on')} title={e.text}>
                <b>{e.id}</b> {e.title}
              </span>
            ))}
          </div>
        )}
      </div>
      {verdict ? (
        <VerdictPanel verdict={verdict} players={players} judgeHref={judgeHref} />
      ) : (
        <div className={cx('db-floor', judging && 'judging')}>
          {col(0)}
          {col(1)}
          {judging && (
            <div className="db-deliberate" role="status">
              <span className="dots" aria-hidden="true">
                <i />
                <i />
                <i />
              </span>
              The judges are reading the blinded transcript…
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Compact result of a finished debate (match cards, latest result). */
export function DebateSummary({ snap, players }: { snap: DebateSnapshot; players: [TablePlayer, TablePlayer] }) {
  if (!snap || snap.kind !== 'debate') return null;
  const v = snap.verdict as VerdictView | undefined;
  const w = v && v.status === 'judged' && v.winner !== null ? players[v.winner] : null;
  const cut = [0, 1].map((s) => snap.speeches.filter((x) => x.side === s).reduce((a, x) => a + x.cut, 0));
  return (
    <div className="db-sum">
      <span className="db-sum-t">{snap.variant === 'courtroom' ? snap.title : `“${snap.title}”`}</span>
      <div className="db-sum-sides">
        {[0, 1].map((s) => (
          <span key={s} className={cx('db-sum-p', v?.winner === s && 'won')} style={{ ['--c' as string]: players[s as 0 | 1].color } as CSSProperties}>
            <i aria-hidden="true" />
            <b className="ellipsis">{players[s as 0 | 1].label}</b>
            <span className="muted">{snap.sides[s]}</span>
            {cut[s]! > 0 && <span className="db-pen sm">✂ {cut[s]}</span>}
          </span>
        ))}
      </div>
      {v ? (
        v.status === 'judged' ? (
          <div className="db-sum-v">
            <span className={cx('db-v-kind', v.split && 'split')}>{v.decision}</span>
            <span className="db-sum-dots" aria-label="Each judge's pick">
              {v.verdicts.map((x) => (
                <i key={x.judgeId} title={`${x.judgeLabel}: ${x.winner === null ? 'no decision' : players[x.winner].label}`} style={{ background: x.winner === null ? 'var(--border-strong)' : players[x.winner].color }} />
              ))}
            </span>
            <b style={{ color: w?.color }}>{w ? `${w.label} wins` : 'Draw'}</b>
          </div>
        ) : (
          <div className="db-sum-v">
            <span className="db-v-kind split">Awaiting human judging</span>
          </div>
        )
      ) : null}
    </div>
  );
}
