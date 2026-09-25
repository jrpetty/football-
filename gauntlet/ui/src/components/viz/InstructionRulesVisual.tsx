/**
 * Instruction tests: the model's reply with every rule as a checklist beside
 * it, the exact characters that broke a rule highlighted, counters as gauges,
 * and multi-turn attacks as chat bubbles.
 */
import { useState } from 'react';
import type { CSSProperties } from 'react';
import { instructionHeadline, spanRuns, type Gauge, type InstructionVisual } from '../../../../src/presenter/visuals/instruction.ts';
import { cx } from '../ui.tsx';
import { ToneMark, VizFrame, type VizMode } from './VizFrame.tsx';

function GaugeBar({ g, label }: { g: Gauge; label: string }) {
  const top = Math.max(g.value, g.max ?? g.min ?? 1) * 1.2 || 1;
  const lo = ((g.min ?? 0) / top) * 100;
  const hi = ((g.max ?? top) / top) * 100;
  const at = Math.min(100, (g.value / top) * 100);
  const target = g.min !== undefined && g.max !== undefined ? (g.min === g.max ? `${g.min}` : `${g.min}–${g.max}`) : g.max !== undefined ? `≤ ${g.max}` : `≥ ${g.min}`;
  return (
    <div className={cx('vz-gauge', g.passed ? 'good' : 'bad')} aria-label={`${label}: ${g.value}, target ${target}`}>
      <div className="vz-gauge-t">
        <span>{label}</span>
        <b className="tnum">{g.value}</b>
        <small className="tnum">target {target}</small>
      </div>
      <div className="vz-gauge-bar">
        <i className="band" style={{ left: `${lo}%`, width: `${Math.max(0.8, hi - lo)}%` }} />
        <i className="needle" style={{ left: `${at}%` }} />
      </div>
    </div>
  );
}

function Highlighted({ v, focus }: { v: InstructionVisual; focus: number | null }) {
  const runs = spanRuns(v.reply, v.spans);
  return (
    <div className="vz-reply-text">
      {runs.map((r, i) =>
        r.tone ? (
          <mark key={i} className={cx('vz-hl', r.tone, focus !== null && (r.rules.includes(focus) ? 'focus' : 'dim'))} title={r.rules.map((k) => `Rule ${k + 1}: ${v.rules[k]?.plain}`).join('\n')}>
            {r.text}
          </mark>
        ) : (
          <span key={i}>{r.text}</span>
        ),
      )}
    </div>
  );
}

export function InstructionRulesVisual({ v, mode, score }: { v: InstructionVisual; mode: VizMode; score?: number | null }) {
  const [focus, setFocus] = useState<number | null>(null);
  const allOk = v.passedCount === v.rules.length;
  const tone = allOk ? 'good' : v.passedCount === 0 ? 'bad' : 'bad';
  const reply = <Highlighted v={v} focus={focus} />;
  // On a slide there is room for six to nine rules: broken ones first.
  const indexed = v.rules.map((r, i) => ({ r, i }));
  const shown = mode === 'slide' ? [...indexed.filter((x) => !x.r.passed), ...indexed.filter((x) => x.r.passed)].slice(0, v.gauges.length ? 6 : 9) : indexed;
  // Identical rules that were all kept collapse into one line ("A banned pattern never appears ×13").
  const lines: Array<{ r: (typeof v.rules)[number]; i: number; count: number }> = [];
  for (const x of shown) {
    const same = x.r.passed ? lines.find((l) => l.r.passed && l.r.plain === x.r.plain) : undefined;
    if (same) same.count++;
    else lines.push({ ...x, count: 1 });
  }
  const hidden = v.rules.length - shown.length;
  const hiddenBroken = v.rules.filter((r) => !r.passed).length - shown.filter((x) => !x.r.passed).length;
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow={v.chat ? `${v.chat.length}-turn conversation · only the last reply is scored` : 'Follow every rule'}
      headline={`${instructionHeadline(v)}${!allOk && score === 0 && v.passedCount > 0 ? ': scores zero' : ''}`}
      big={`${v.passedCount}/${v.rules.length}`}
      bigSub="rules kept"
      legend={[
        { tone: 'bad', label: 'Where a rule was broken' },
        { tone: 'count', label: 'Counted towards a rule with a limit' },
        { tone: 'good', label: 'Rule kept' },
        ...(v.chat ? [{ tone: 'trap', label: 'Pressure turn' }] : []),
      ]}
    >
      <div className="vz-instr">
        <div className="vz-instr-reply">
          {v.chat ? (
            <ol className="vz-chat">
              {mode === 'slide' && v.chat.length > 2 && (
                <li className="vz-chat-steps" aria-label="Earlier turns">
                  {v.chat.slice(0, -1).map((t, i) => (
                    <span key={i} className={cx('vz-chat-step', t.tag && 'attack')}>
                      <b className="tnum">{i + 1}</b> {t.tag ?? 'user turn'}
                    </span>
                  ))}
                </li>
              )}
              {(mode === 'slide' && v.chat.length > 2 ? v.chat.slice(-1) : v.chat).map((t, j, arr) => {
                const i = v.chat!.length - arr.length + j;
                return (
                <li key={i} style={{ ['--i' as string]: i } as CSSProperties}>
                  <div className={cx('vz-msg user', t.tag && 'attack')}>
                    <div className="vz-msg-k">
                      User · turn {i + 1}
                      {t.tag && <span className="vz-attack">{t.tag}</span>}
                    </div>
                    <p>{t.user}</p>
                  </div>
                  {t.reply !== null && (
                    <div className={cx('vz-msg model', t.scored && 'scored')}>
                      <div className="vz-msg-k">{t.scored ? 'Model · scored reply' : 'Model'}</div>
                      {t.scored ? reply : <p>{t.reply}</p>}
                    </div>
                  )}
                </li>
                );
              })}
            </ol>
          ) : (
            <div className="vz-msg model scored solo">
              <div className="vz-msg-k">Model’s reply</div>
              {v.reply ? reply : <div className="vz-missing">No reply was recorded.</div>}
            </div>
          )}
        </div>
        <aside className="vz-instr-side">
          {v.gauges.length > 0 && (
            <div className="vz-gauges">
              {v.gauges.map((g) => (
                <GaugeBar key={g.rule} g={g} label={g.noun.charAt(0).toUpperCase() + g.noun.slice(1)} />
              ))}
            </div>
          )}
          <ol className="vz-rules" onMouseLeave={() => setFocus(null)}>
            {lines.map(({ r, i, count }) => (
              <li
                key={i}
                className={cx(r.passed ? 'good' : 'bad', focus === i && 'focus')}
                onMouseEnter={() => setFocus(i)}
                onFocus={() => setFocus(i)}
                tabIndex={v.highlighted ? 0 : -1}
                style={{ ['--i' as string]: i } as CSSProperties}
              >
                <ToneMark tone={r.passed ? 'good' : 'bad'} />
                <span className="vz-rule-n tnum">{i + 1}</span>
                <span className="vz-rule-t">
                  {r.plain}
                  {count > 1 && <b className="vz-rule-x tnum"> ×{count}</b>}
                  {!r.passed && r.detail && <small>{r.detail}</small>}
                </span>
              </li>
            ))}
          </ol>
          {hidden > 0 && <p className="vz-hint">+ {hidden} more rules{hiddenBroken > 0 ? ` (${hiddenBroken} broken)` : ', all kept'}</p>}
          {v.highlighted && mode === 'inspector' && <p className="vz-hint">Point at a rule to see exactly where the reply broke it.</p>}
        </aside>
      </div>
    </VizFrame>
  );
}
