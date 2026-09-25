/**
 * Debate / Courtroom stage: the motion (or case file), a round progress bar,
 * two lecterns with side names and speech bubbles (streaming live), word-limit
 * meters with the over-limit penalty, exhibits cited in each speech linked to
 * the evidence panel, and the judges' scorecards revealed one by one.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { Link } from '../router.tsx';
import { cx } from '../components/ui.tsx';
import { caseById } from '../../../src/arena/games/debate-bank.ts';
import type { ArenaJudging, DebateSnapshot, JudgeVerdict, MatchState, Speech } from './types.ts';
import type { TablePlayer } from './poker.tsx';
import './formats.css';
import './debate-visual.css';

export type VerdictView = Omit<ArenaJudging, 'transcript' | 'metrics'>;

const RUBRIC_LABEL: Record<string, string> = { argument: 'Argument', rebuttal: 'Rebuttal', evidence: 'Evidence', clarity: 'Clarity', rules: 'Rules' };

const words = (t: string) => (t.match(/\S+/g) ?? []).length;

const EXHIBIT_RE = /(Exhibits? [A-E](?:\s*(?:,|and|&)\s*[A-E])*)/g;

/** Exhibit letters cited in a text ("Exhibits A and C" → A, C). */
export function citedExhibits(text: string): string[] {
  const out = new Set<string>();
  for (const m of text.matchAll(/Exhibits? ([A-E](?:\s*(?:,|and|&)\s*[A-E])*)/g)) for (const l of m[1]!.match(/[A-E]/g) ?? []) out.add(l);
  return [...out].sort();
}

/** Word-limit meter: green under the limit, amber in the last 10%, red over (the harness cuts the excess). */
function WordMeter({ n, limit, live }: { n: number; limit: number; live?: boolean }) {
  const over = n > limit;
  const near = !over && n >= limit * 0.9;
  const scale = Math.max(limit * 1.25, n);
  return (
    <span className={cx('db-wm', over && 'over', near && 'near')} title="Words used / word limit (the harness cuts anything over the limit)">
      <span className="db-wm-track" aria-hidden="true">
        <i style={{ width: `${(Math.min(n, scale) / scale) * 100}%` }} />
        <u style={{ left: `${(limit / scale) * 100}%` }} />
      </span>
      <span className="db-wm-n tnum">
        <b>{n}</b> / {limit} words{over ? (live ? ' · over: will be cut' : '') : ''}
      </span>
    </span>
  );
}

function Bubble({
  speech,
  live,
  text,
  limit,
  roundName,
  player,
  role,
  current,
  exhibits,
  onExhibit,
  selected,
}: {
  speech?: Speech;
  live?: boolean;
  text: string;
  limit: number;
  roundName: string;
  player: TablePlayer;
  role: string;
  current: boolean;
  exhibits?: boolean;
  onExhibit?: (id: string) => void;
  selected?: string | null;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (live && ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [text, live]);
  const n = speech ? speech.words : words(text);
  const body = speech?.missing ? '(No speech: the model failed to reply.)' : text;
  // Exhibit references light up (courtroom) and open the exhibit in the evidence panel.
  const parts = exhibits ? body.split(EXHIBIT_RE) : [body];
  return (
    <div className={cx('db-bubble', current && 'current', live && 'live', speech?.missing && 'missing')} style={{ ['--c' as string]: player.color } as CSSProperties}>
      <div className="db-b-head">
        <span className="db-b-round">{roundName}</span>
        <span className="db-b-role">
          <b>{player.label}</b> · {role}
        </span>
      </div>
      <div className="db-b-text" ref={ref}>
        {parts.map((p, i) => {
          if (i % 2 === 0) return <span key={i}>{p}</span>;
          const ids: string[] = p.match(/[A-E]/g) ?? [];
          return (
            <button key={i} type="button" className={cx('db-cite', ids.includes(selected ?? '') && 'sel')} onClick={() => onExhibit?.(ids[0]!)} title="Show this exhibit in the evidence panel">
              {p}
            </button>
          );
        })}
        {live && <span className="caret" aria-hidden="true" />}
      </div>
      <div className="db-b-foot">
        <WordMeter n={n} limit={limit} live={live} />
        {speech && speech.cut > 0 && (
          <span className="db-pen" title="The harness cut the speech at the word limit; the judges see this as a rule-following penalty.">
            ✂ {speech.cut} words cut · rule penalty
          </span>
        )}
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

/** Reveal timing: one card every REVEAL_MS, the verdict banner after the last one. */
const REVEAL_MS = 900;
const FIRST_MS = 300;

function Scorecard({ v, players, i }: { v: JudgeVerdict; players: [TablePlayer, TablePlayer]; i: number }) {
  const keys = v.scores ? Object.keys(v.scores[0]) : [];
  const tot = (s: 0 | 1) => (v.scores ? Object.values(v.scores[s]).reduce((a, b) => a + b, 0) : 0);
  const pick = v.winner === null ? null : players[v.winner];
  const delay = FIRST_MS + i * REVEAL_MS;
  return (
    <div className={cx('db-card', v.error && 'err')} style={{ animationDelay: `${delay}ms`, ['--d' as string]: `${delay}ms`, ['--c' as string]: pick?.color ?? 'var(--text-3)' } as CSSProperties}>
      <div className="db-card-head">
        <span className="db-card-j">
          Judge {i + 1} · {v.judgeLabel}
        </span>
        <span className="muted">{v.human ? 'human judge' : v.vendor}</span>
      </div>
      {v.error ? (
        <div className="db-card-err">Could not judge: {v.error}</div>
      ) : (
        <>
          {v.scores && (
            <div className="db-card-rows">
              <div className="db-card-names" aria-hidden="true">
                <span className="ellipsis" style={{ color: players[0].color }}>
                  {players[0].label}
                </span>
                <span className="ellipsis" style={{ color: players[1].color }}>
                  {players[1].label}
                </span>
              </div>
              {keys.map((k, r) => (
                <div key={k} className="db-card-row" style={{ ['--rd' as string]: `${delay + 250 + r * 110}ms` } as CSSProperties}>
                  <span className="k">{RUBRIC_LABEL[k] ?? k}</span>
                  <span className="tnum" style={{ color: players[0].color }}>
                    {v.scores![0][k]}
                  </span>
                  <span className="db-duel" aria-hidden="true">
                    <i style={{ width: `${v.scores![0][k]! * 10}%`, background: players[0].color }} />
                    <i style={{ width: `${v.scores![1][k]! * 10}%`, background: players[1].color }} />
                  </span>
                  <span className="tnum r" style={{ color: players[1].color }}>
                    {v.scores![1][k]}
                  </span>
                </div>
              ))}
              <div className="db-card-row total">
                <span className="k">Total</span>
                <b className="tnum">{tot(0)}</b>
                <span />
                <b className="tnum r">{tot(1)}</b>
              </div>
            </div>
          )}
          <div className="db-card-pick" style={{ ['--pd' as string]: `${delay + 250 + keys.length * 110 + 150}ms` } as CSSProperties}>
            <span className="muted">picks</span> <b>{pick?.label ?? 'no one (a draw)'}</b>
          </div>
          {v.rationale && (
            <p className="db-card-why" title={v.rationale}>
              “{v.rationale}”
            </p>
          )}
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
  const n = verdict.verdicts.length;
  const bannerAt = FIRST_MS + n * REVEAL_MS + 200;
  return (
    <div className="db-verdict" role="status">
      <div className="db-v-left">
        <div className="db-tally" aria-label="Votes as each judge reveals">
          <span className="db-tally-k">Judges’ votes</span>
          <span className="db-tally-dots">
            {verdict.verdicts.map((v, i) => (
              <i
                key={v.judgeId}
                title={`${v.judgeLabel}: ${v.winner === null ? 'no decision' : players[v.winner].label}`}
                style={{ ['--c' as string]: v.winner === null ? 'var(--border-strong)' : players[v.winner].color, animationDelay: `${FIRST_MS + i * REVEAL_MS + 500}ms` } as CSSProperties}
              />
            ))}
          </span>
        </div>
        <div className="db-v-banner" style={{ ['--c' as string]: w?.color ?? 'var(--text-3)', animationDelay: `${bannerAt}ms` } as CSSProperties}>
          <span className={cx('db-v-kind', verdict.split && 'split')}>{verdict.decision}</span>
          <strong>{w ? `${w.label} wins` : 'A draw'}</strong>
          {n > 1 && <span className="db-v-votes tnum">{votes}</span>}
        </div>
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

/** A level match settled on the judges' combined points or picks (shown on the game page and match card). */
export function TieBreakNote({ match, labelOf }: { match: MatchState; labelOf: (id: string | null) => { label: string; color: string } }): ReactNode {
  if (match.decidedBy !== "judges' points" && match.decidedBy !== "judges' picks") return null;
  const pts = match.decidedBy === "judges' points" ? match.judgePoints : match.judgePicks;
  if (!pts) return null;
  const a = labelOf(match.players[0]);
  const b = labelOf(match.players[1]);
  return (
    <div className="ar-tiebreak" role="note">
      <span className="eyebrow">Tie-break used</span>
      <span>
        Level on games, so the {match.decidedBy === "judges' points" ? 'judges’ total rubric points over both games' : 'number of judges who picked each side over both games'} decided it:
      </span>
      <span className="ar-tb-score tnum">
        <span style={{ color: a.color }}>{a.label}</span> <b>{pts[0]}</b> – <b>{pts[1]}</b> <span style={{ color: b.color }}>{b.label}</span>
      </span>
    </div>
  );
}

export interface DebateLiveInfo {
  toMove: 0 | 1;
  thinking: string;
  phase?: string;
}

export function DebateStage({ snap, players, live, className, judgeHref }: { snap: DebateSnapshot; players: [TablePlayer, TablePlayer]; live?: DebateLiveInfo; className?: string; judgeHref?: string }) {
  const [picked, setPicked] = useState<string | null>(null);
  const court = snap?.variant === 'courtroom';
  const cf = court && snap ? caseById(snap.topicId) : undefined;
  const liveText = live?.thinking ?? '';
  const verdict = snap?.verdict as VerdictView | undefined;
  const judging = live?.phase === 'judging' && !verdict;
  const speaking = !verdict && !judging && live && snap?.next ? snap.next.side : null;
  const lastIdx = (snap?.speeches.length ?? 0) - 1;
  const currentText = speaking !== null ? liveText : (snap?.speeches[lastIdx]?.text ?? '');
  const cited = useMemo(() => new Set(citedExhibits(currentText)), [currentText]);
  /** How often each side has cited each exhibit so far. */
  const citeCount = useMemo(() => {
    const out = new Map<string, [number, number]>();
    for (const sp of snap?.speeches ?? []) for (const id of citedExhibits(sp.text)) {
      const c = out.get(id) ?? [0, 0];
      c[sp.side]++;
      out.set(id, c);
    }
    return out;
  }, [snap?.speeches]);
  // Follow the newest citation unless the viewer picked an exhibit.
  const lastCited = [...cited].pop() ?? null;
  const shown = picked ?? lastCited;
  const exhibit = cf?.exhibits.find((e) => e.id === shown);
  if (!snap || snap.kind !== 'debate') return <div className="db-stage empty" />;

  const round = snap.next && !verdict ? snap.next.round : 2;
  const lastBy = (s: 0 | 1) => [...snap.speeches].reverse().find((x) => x.side === s);
  const totalSteps = snap.roundNames.length * 2;
  const doneSteps = snap.speeches.length;
  const pct = verdict ? 100 : (Math.min(doneSteps + (speaking !== null ? 0.5 : 0), totalSteps) / (totalSteps + 1)) * 100;

  const col = (s: 0 | 1) => {
    const last = lastBy(s);
    const isLive = speaking === s;
    const current = isLive || (speaking === null && !verdict && snap.speeches[lastIdx]?.side === s) || (!live && snap.speeches[lastIdx]?.side === s && !verdict);
    return (
      <div className={cx('db-col', s === 1 && 'right')}>
        {isLive ? (
          <Bubble live text={liveText} limit={snap.limits[snap.next!.round] ?? 180} roundName={snap.roundNames[snap.next!.round]!} player={players[s]} role={snap.sides[s]} current exhibits={court} onExhibit={setPicked} selected={shown} />
        ) : last ? (
          <Bubble speech={last} text={last.text} limit={last.limit} roundName={snap.roundNames[last.round]!} player={players[s]} role={snap.sides[s]} current={current} exhibits={court} onExhibit={setPicked} selected={shown} />
        ) : (
          <div className="db-bubble empty" style={{ ['--c' as string]: players[s].color } as CSSProperties}>
            <span className="muted">
              {players[s].label} {s === 0 ? 'opens the debate' : 'replies second'}
            </span>
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
        <div className="db-sides-line" aria-label="Sides">
          <span style={{ ['--c' as string]: players[0].color } as CSSProperties}>
            <i /> <b>{players[0].label}</b> {snap.sides[0]}
          </span>
          <span className="muted">vs</span>
          <span style={{ ['--c' as string]: players[1].color } as CSSProperties}>
            <i /> <b>{players[1].label}</b> {snap.sides[1]}
          </span>
        </div>
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
        <div className="db-progress" role="progressbar" aria-valuemin={0} aria-valuemax={totalSteps} aria-valuenow={doneSteps} aria-label={`Speech ${Math.min(doneSteps + 1, totalSteps)} of ${totalSteps}`}>
          <i style={{ width: `${pct}%` }} />
          {Array.from({ length: totalSteps }, (_, k) => (
            <u key={k} className={cx(k < doneSteps && 'done')} style={{ left: `${((k + 1) / (totalSteps + 1)) * 100}%`, ['--c' as string]: players[(k % 2) as 0 | 1].color } as CSSProperties} />
          ))}
          <span className="db-progress-t tnum">{verdict ? 'Decided' : judging ? 'Judging' : `Speech ${Math.min(doneSteps + (speaking !== null ? 1 : 0) || 1, totalSteps)} of ${totalSteps}`}</span>
        </div>
        {cf && (
          <div className="db-exhibits" aria-label="Exhibits (click to read)">
            {cf.exhibits.map((e) => {
              const c = citeCount.get(e.id);
              return (
                <button key={e.id} type="button" className={cx('db-ex', cited.has(e.id) && 'on', shown === e.id && 'sel')} title={e.text} onClick={() => setPicked(picked === e.id ? null : e.id)}>
                  <b>{e.id}</b> {e.title}
                  {c && (c[0] || c[1]) ? (
                    <span className="db-ex-n tnum" aria-label={`cited ${c[0]} times by ${snap.sides[0]}, ${c[1]} by ${snap.sides[1]}`}>
                      {c[0] > 0 && <i style={{ background: players[0].color }}>{c[0]}</i>}
                      {c[1] > 0 && <i style={{ background: players[1].color }}>{c[1]}</i>}
                    </span>
                  ) : null}
                </button>
              );
            })}
          </div>
        )}
        {cf && !verdict && (
          <div className={cx('db-evidence', !exhibit && 'empty')} aria-live="polite">
            {exhibit ? (
              <>
                <span className="db-ev-k">
                  Exhibit {exhibit.id} · {exhibit.title}
                  {!picked && <span className="muted"> · cited in the current speech</span>}
                </span>
                <span className="db-ev-t">{exhibit.text}</span>
              </>
            ) : (
              <span className="muted">The current speech cites no exhibit. Click any exhibit to read it.</span>
            )}
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
