/**
 * The Game Jam card for one game (result inspector, Grader, the jam wall's detail panel): the arcade screen with
 * "Play it", the playtest filmstrip, the judges' scorecard per criterion with every judge's mark (so a split
 * panel is visible), the numbered-requirement checklist with ticks and crosses, and how the score adds up.
 * Everything shown is recorded in scoreDetail.gameJam; older results without it fall back to ArtifactShowcase.
 */
import { useState } from 'react';
import type { CSSProperties } from 'react';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { VizHeadline } from './VizHeadline.tsx';
import { ToneIcon } from './VizLegend.tsx';
import type { Tone } from './vizModel.ts';
import { JAM_CRITERIA, jamGenreInfo, type RequirementVerdict } from '../../../../src/scoring/game-jam-shared.ts';
import { STATE_WORDS, entryOf, judgeName, type JamEntry, type JamResult } from './gameJamModel.ts';
import { PlaytestFilmstrip } from './PlaytestFilmstrip.tsx';
import './game-jam.css';

const tenths = (x: number | null | undefined) => (typeof x === 'number' ? (x * 10).toFixed(1) : '—');
const VERDICT_WORD: Record<RequirementVerdict, string> = { pass: 'met', partial: 'partly', fail: 'missing' };

export function ReqMark({ v, size = 'md' }: { v: RequirementVerdict | null; size?: 'sm' | 'md' }) {
  if (!v) return <span className={cx('jam-rq', 'none', size)} aria-label="not graded">·</span>;
  return (
    <span className={cx('jam-rq', v, size)} aria-label={VERDICT_WORD[v]}>
      {v === 'pass' ? (
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 8.5 L6.5 12 L13 4.5" /></svg>
      ) : v === 'fail' ? (
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 4 L12 12 M12 4 L4 12" /></svg>
      ) : (
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8 H12.5" /></svg>
      )}
    </span>
  );
}

export function JamScorecard({ entry, names }: { entry: JamEntry; names?: Map<string, string> }) {
  const d = entry.detail;
  if (!d || !d.judges.length) return null;
  const judges = d.judges;
  const colors = ['var(--seq-1)', 'var(--seq-4)', 'var(--seq-6)'];
  return (
    <div className="vz-card jam-card">
      <div className="vz-card-k">
        Judges’ scorecard · {judges.length} judge{judges.length === 1 ? '' : 's'} from other companies
        {d.spread !== null && d.spread > 0.3 && <span className="jam-split">judges disagree: {tenths(d.spread)} points apart</span>}
      </div>
      <div className="jam-crit">
        {JAM_CRITERIA.map((c) => {
          const v = d.criteria[c.id];
          return (
            <div key={c.id} className={cx('jam-crit-row', c.id === 'creativity' && 'lead')}>
              <span className="jam-crit-l">
                {c.label}
                <em>{Math.round(d.weights[c.id] * 100)}%</em>
              </span>
              <span className="jam-crit-bar">
                <i style={{ width: `${Math.round((v ?? 0) * 100)}%` }} />
                {judges.map((j, k) => (
                  <b key={j.judgeId} style={{ left: `${Math.round(j.criteria[c.id] * 100)}%`, background: colors[k % 3] } as CSSProperties} title={`${judgeName(j.judgeId, names)}: ${tenths(j.criteria[c.id])}/10`} />
                ))}
              </span>
              <span className="jam-crit-v tnum">{tenths(v)}</span>
            </div>
          );
        })}
      </div>
      <div className="jam-judges">
        {judges.map((j, k) => (
          <figure key={j.judgeId} className="jam-quote">
            <span className="jam-jdot" style={{ background: colors[k % 3] }} />
            <figcaption>
              <b>{judgeName(j.judgeId, names)}</b> <span className="tnum">{tenths(j.total)}/10</span>
              {!j.sawImages && <em className="muted"> · read the code only (no image input)</em>}
            </figcaption>
            {j.verdict && <blockquote>“{j.verdict}”</blockquote>}
          </figure>
        ))}
      </div>
      <p className="vz-fine">Bar = panel average (out of 10); dots = each judge. Creativity carries the most weight, as the owner asked.</p>
    </div>
  );
}

export function JamChecklist({ entry, names, compact }: { entry: JamEntry; names?: Map<string, string>; compact?: boolean }) {
  const d = entry.detail;
  if (!d) return null;
  const graded = d.requirements.some((r) => r.verdict);
  const met = d.requirements.filter((r) => r.verdict === 'pass').length;
  return (
    <div className={cx('vz-card jam-card', compact && 'compact')}>
      <div className="vz-card-k">
        The brief’s numbered requirements · {graded ? `${met} of ${d.requirements.length} met` : 'not graded'}
      </div>
      {!graded && <p className="jam-note">{d.judgesSkipped ?? 'The judges did not grade this game.'}</p>}
      <ol className="jam-reqs">
        {d.requirements.map((r) => (
          <li key={r.id} className={cx(r.verdict ?? 'none', r.split && 'split')}>
            <ReqMark v={r.verdict} />
            <span className="jam-req-id">{r.id}</span>
            <span className="jam-req-l">{r.label}</span>
            {!compact && Object.keys(r.votes).length > 1 && (
              <span className="jam-req-votes" title="Each judge's verdict">
                {Object.entries(r.votes).map(([jid, v]) => (
                  <span key={jid} title={`${judgeName(jid, names)}: ${VERDICT_WORD[v.verdict]}${v.reason ? ` · ${v.reason}` : ''}`}>
                    <ReqMark v={v.verdict} size="sm" />
                  </span>
                ))}
              </span>
            )}
          </li>
        ))}
      </ol>
      <div className="jam-legend">
        <span><ReqMark v="pass" size="sm" /> met</span>
        <span><ReqMark v="partial" size="sm" /> partly</span>
        <span><ReqMark v="fail" size="sm" /> missing</span>
        <span className="jam-legend-split">amber edge = judges split</span>
      </div>
    </div>
  );
}

export function GameJamShowcase({ result, urlFor, names, eyebrowPrefix }: { result: JamResult; urlFor: (file: string) => string; names?: Map<string, string>; eyebrowPrefix?: string }) {
  const entry = entryOf(result);
  const d = entry.detail;
  const g = jamGenreInfo(entry.genre);
  const [sel, setSel] = useState(Math.min(3, Math.max(0, entry.frames.length - 1)));
  const [playing, setPlaying] = useState(false);
  const score = result.score;
  const tone: Tone = score === null ? 'neutral' : score >= 0.7 ? 'good' : score >= 0.4 ? 'warn' : 'bad';
  const creative = d?.criteria.creativity;
  const title =
    entry.state !== 'ok'
      ? STATE_WORDS[entry.state].line
      : d && d.requirements.some((r) => r.verdict)
        ? `It plays · ${d.requirements.filter((r) => r.verdict === 'pass').length} of ${d.requirements.length} requirements met${typeof creative === 'number' ? ` · creativity ${tenths(creative)}/10` : ''}`
        : result.summary;
  const url = entry.html ? urlFor(entry.html.file) : '';
  const frame = entry.frames[sel];
  const auto = d?.automatedScore;
  return (
    <div className="vz-showcase jam-showcase">
      <VizHeadline eyebrow={`${eyebrowPrefix ?? 'The Game Jam'} · Round ${g?.round ?? '?'}: ${g?.label ?? entry.genre}`} title={title} tone={tone} big={score === null ? '—' : Math.round(score * 100)} bigSub="/100" />
      {(d?.cap || d?.truncated) && (
        <div className="jam-banner">
          <ToneIcon tone="bad" /> {d.truncated ? 'Ran out of output space: the reply hit the output-token limit before the file was finished. The unfinished file was still tested; the judges were not asked.' : d.cap}
        </div>
      )}
      <div className="vz-cols wide-left">
        <div className="vz-col">
          <div className="jam-stage">
            {playing && url ? (
              <iframe title="Play the game" src={url} sandbox="allow-scripts" referrerPolicy="no-referrer" />
            ) : frame ? (
              <img src={urlFor(frame.art.file)} alt={`Playtest screenshot at ${frame.label}`} />
            ) : (
              <div className="jam-stage-empty">
                <span className="jam-static" aria-hidden="true" />
                <b>{STATE_WORDS[entry.state].stamp || 'No screenshot'}</b>
              </div>
            )}
            {!playing && frame && <span className="jam-stage-t">{frame.label}</span>}
          </div>
          <div className="vz-game-bar">
            {url && (
              <button type="button" className={cx('btn', playing ? '' : 'primary', 'vz-play')} onClick={() => setPlaying((p) => !p)}>
                {playing ? <Icon.Stop /> : <Icon.Play />} {playing ? 'Stop' : 'Play it'}
              </button>
            )}
            {url && (
              <a className="btn" href={url} target="_blank" rel="noreferrer noopener">
                <Icon.External /> Open in a new tab
              </a>
            )}
            <span className="muted">{playing ? 'Running in a locked-down frame: no network, no storage, no access to this app.' : 'Screenshots from the automatic playtest; press Play it to try the game yourself.'}</span>
          </div>
          <div className="vz-card jam-card">
            <div className="vz-card-k">The playtest · {d?.playtest ? `15 seconds of scripted play` : 'not run'}</div>
            {d?.playtest && <p className="jam-note">What the robot player did: {d.playtest.inputs}.</p>}
            <PlaytestFilmstrip entry={entry} urlFor={urlFor} selected={playing ? -1 : sel} onSelect={(i) => { setPlaying(false); setSel(i); }} />
          </div>
        </div>
        <div className="vz-col narrow">
          <div className="vz-card jam-card jam-sum">
            <div className="vz-card-k">How the score adds up</div>
            <div className="jam-sum-row">
              <span>Automatic checks</span>
              <b className="tnum">{typeof auto === 'number' ? `${Math.round(auto * 100)}%` : '—'}</b>
              <em>× {Math.round((1 - (d?.judgeWeight ?? 0.75)) * 100)}%</em>
            </div>
            <div className="jam-sum-row">
              <span>Judges</span>
              <b className="tnum">{d?.judgeScore === null || d?.judgeScore === undefined ? 'not asked' : `${tenths(d.judgeScore)}/10`}</b>
              <em>× {Math.round((d?.judgeWeight ?? 0.75) * 100)}%</em>
            </div>
            {d?.cap && <div className="jam-sum-cap">Capped at 30: {d.cap.replace(/, so its score is capped at 30\.$/, '')}</div>}
            <div className="jam-sum-total">
              <span>Score</span>
              <b className={cx('tnum', `t-${tone}`)}>{score === null ? '—' : Math.round(score * 100)}</b>
            </div>
          </div>
          {entry.detail && <JamScorecard entry={entry} names={names} />}
          {entry.detail && <JamChecklist entry={entry} names={names} />}
          <details className="vz-card vz-fold">
            <summary className="vz-card-k">Automatic checks · {(result.scoreDetail?.items ?? []).filter((i) => i.passed).length}/{(result.scoreDetail?.items ?? []).length} passed</summary>
            <ul className="vz-checks">
              {(result.scoreDetail?.items ?? []).map((it, i) => (
                <li key={i} className={it.passed ? 'pass' : 'fail'}>
                  <ToneIcon tone={it.passed ? 'good' : 'bad'} />
                  <span>{it.label}</span>
                  {it.detail && <em>{it.detail}</em>}
                </li>
              ))}
            </ul>
          </details>
        </div>
      </div>
    </div>
  );
}
