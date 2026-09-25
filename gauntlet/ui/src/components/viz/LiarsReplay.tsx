/**
 * The Liar's Table replay stage: the suspects around the table (drawn
 * portraits), who was asked what and their answer as a speech bubble (or the
 * evidence file that was opened), the question budget, the deduction board
 * with contradictions lighting up, and at the verdict the accused side by
 * side with the real thief.
 */
import type { LiarsSimFrame, LiarsSimWorld } from '../../types.ts';
import { cx } from '../ui.tsx';
import { LiarsBoard, sourceName } from './LiarsBoard.tsx';
import { SimFinaleCard } from './SimFinaleCard.tsx';
import { SimIcon } from './SimIcon.tsx';
import { SuspectPortrait } from './SuspectPortrait.tsx';
import { clip, liarsBoard, liarsFinale } from './simStory.ts';
import type { SimStageProps } from './SimStage.tsx';

function Budget({ used, budget }: { used: number; budget: number }) {
  return (
    <div className="lt-budget" aria-label={`${used} of ${budget} questions used`}>
      <span className="lt-bk">Questions</span>
      <span className="lt-dots">
        {Array.from({ length: budget }, (_, i) => (
          <i key={i} className={cx(i < used && 'used', i === used - 1 && 'last')} />
        ))}
      </span>
      <b className="tnum">
        {used}
        <small>/{budget}</small>
      </b>
    </div>
  );
}

export function LiarsReplay({ replay, idx, score, hideFinale }: SimStageProps) {
  const world = replay.sim as LiarsSimWorld;
  const frames = replay.frames;
  const frame = frames[idx]!;
  const sim = frame.sim as LiarsSimFrame;
  const board = liarsBoard(world, frames, idx);
  const verdict = frame.label === 'Verdict';
  const atEnd = idx >= frames.length - 1;
  const n = world.suspects.length;
  const asked = sim.ask?.who;
  const answer = (frame.outcome ?? '').replace(new RegExp(`^${asked ?? '\\u0000'}:\\s*`), '');
  const exposed = new Set(Object.entries(board.cells).filter(([, cells]) => cells.some((c) => c.conflict?.hard)).map(([name]) => name));

  return (
    <div className="sim-body sim-liars">
      <div className="sim-main">
        <div className="lt-scene">
          <div className="lt-case">
            <SimIcon name="clue" size="1.3em" />
            <span>
              <b>{world.object}</b> vanished from the {world.objectRoom} at {world.venue}
            </span>
          </div>
          <div className="lt-table" style={{ ['--n' as string]: n }}>
            <div className="lt-top" aria-hidden="true" />
            {world.suspects.map((s, i) => {
              const a = (i / n) * Math.PI * 2 - Math.PI / 2;
              const accused = verdict && sim.accuse?.who === s.name;
              const thief = verdict && s.name === world.culprit;
              return (
                <div
                  key={s.name}
                  className={cx('lt-seat', asked === s.name && 'asked', accused && 'accused', thief && 'thief', exposed.has(s.name) && 'exposed')}
                  style={{ left: `${50 + Math.cos(a) * 41}%`, top: `${50 + Math.sin(a) * 36}%` }}
                  title={`${s.name}, ${s.blurb}`}
                >
                  <SuspectPortrait name={s.name} size="100%" />
                  <span className="lt-name">{s.name}</span>
                  {thief && <span className="lt-tag thief">THIEF</span>}
                  {accused && !thief && <span className="lt-tag wrong">ACCUSED</span>}
                  {exposed.has(s.name) && !verdict && <span className="lt-tag exposed">story broke</span>}
                </div>
              );
            })}
            {sim.check && (
              <div className="lt-file" key={idx}>
                <SimIcon name="clue" size="1.6em" />
                <b>{sourceName(sim.check)}</b>
              </div>
            )}
          </div>
          <Budget used={sim.used} budget={world.budget} />
        </div>

      </div>

      <aside className="sim-side">
        {atEnd && !hideFinale && <SimFinaleCard finale={liarsFinale(world, frames)} score={score} />}
        {!verdict && idx > 0 && (
          <div className={cx('lt-bubble', sim.check && 'evidence', sim.invalid && 'invalid')} key={`b${idx}`}>
            <div className="lt-q">
              <span className="lt-qk">Q{sim.used}</span> {clip(frame.action ?? '', 90)}
            </div>
            <div className="lt-a">
              {asked && <SuspectPortrait name={asked} size="2.4em" />}
              <p>{clip(answer || '(no answer)', 420)}</p>
            </div>
          </div>
        )}
        {verdict && (
          <div className="lt-verdict">
            <div className={cx('lv-side', sim.accuse?.correct ? 'good' : 'bad')}>
              <span className="lv-k">Model accused</span>
              {sim.accuse ? <SuspectPortrait name={sim.accuse.who} size="4.5em" /> : <SimIcon name="cross" size="4.5em" />}
              <b>{sim.accuse?.who ?? 'nobody'}</b>
            </div>
            <span className="lv-vs">{sim.accuse?.correct ? '=' : '≠'}</span>
            <div className="lv-side truth">
              <span className="lv-k">Real thief</span>
              <SuspectPortrait name={world.culprit} size="4.5em" />
              <b>{world.culprit}</b>
            </div>
            {sim.accuse?.reason && <p className="lv-reason">“{clip(sim.accuse.reason, 240)}” · reason {sim.accuse.reasonScore}/3</p>}
          </div>
        )}
        {idx === 0 && (
          <div className="sim-panel">
            <div className="sp-h">The suspects</div>
            <ul className="lt-cast">
              {world.suspects.map((s) => (
                <li key={s.name}>
                  <SuspectPortrait name={s.name} size="2em" />
                  <b>{s.name}</b> <span className="muted">{s.blurb}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </aside>

      <div className="sim-panel lt-board-wrap">
        <div className="sp-h">Deduction board · where each suspect says they were, and who backs it up</div>
        <LiarsBoard world={world} board={board} reveal={verdict} activeWho={asked} />
        <div className="sim-legend">
          <span>
            <i className="lg-box said" /> their own story
          </span>
          <span>
            <i className="lg-box ok" /> ✓ backed up by others
          </span>
          <span>
            <i className="lg-box conflict" /> contradiction
          </span>
          <span>
            <i className="lg-box soft" /> “might be muddling” (unsure)
          </span>
          <span>
            <i className="lg-box theft" /> theft time
          </span>
        </div>
      </div>
    </div>
  );
}
