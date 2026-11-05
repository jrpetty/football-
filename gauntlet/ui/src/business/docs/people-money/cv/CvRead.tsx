/**
 * Read a CV (work.cv-read): the screening form beside the CV. Question: the blank form and the CV. Answer: the
 * form as the model filled it in, every box ticked or crossed against the key, and highlighter marks on the CV
 * (numbered like the boxes) showing where each answer comes from, plus the relevant roles on a timeline.
 */
import { useMemo } from 'react';
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../../components/viz/closeup/types.ts';
import { VizFrame, type VizMode } from '../../../../components/viz/VizFrame.tsx';
import { cx } from '../../../../components/ui.tsx';
import { DocBeats, Impact, Paper, Pin, Tick, type Tone } from '../kit.tsx';
import { CV_FIELDS, cvFieldText, cvReadRows, cvReadSentence, cvSources, monthLabel, readCvQuestion, relevantMonths, type CvFormRow, type CvField, type ReadCv } from '../model/cv.ts';
import { keyOf } from '../model/common.ts';
import { ReadCvPage, type LineMark } from './CvPages.tsx';

const RULES: Record<CvField, string> = {
  current_role: 'Their current paid job (the one running to “Present”); a promotion noted later wins',
  current_employer: 'The employer of that current paid job',
  relevant_experience_months: 'Paid roles titled “Payroll” or “Pensions”, no internships; a month counts once, part-time months count half',
  longest_unbroken_relevant_months: 'The longest run of months with no gap in relevant work',
  highest_qualification: 'GCSE · A level · HND · Bachelor’s · Master’s · Doctorate: completed only',
  notice_period: 'As written; a later change wins; probation ends after its months',
  right_to_work: '“yes”, “needs sponsorship” or “not stated”: only what the CV says, never guessed',
};

export function ScreeningForm({ cv, rows, show }: { cv: ReadCv; rows?: CvFormRow[]; show: 'blank' | 'model' | 'key' }) {
  return (
    <Paper className="pm-form" label="Screening form">
      <div className="pm-form-head">
        <b>Screening form · {cv.job}</b>
        <span>Fill in from the CV only, by the written rules</span>
      </div>
      <div className="pm-form-rows">
        {CV_FIELDS.map((f, i) => {
          const r = rows?.find((x) => x.key === f.key);
          const tone: Tone = show === 'key' ? 'key' : r?.ok === false ? 'bad' : r?.ok ? 'good' : 'muted';
          const value = show === 'key' ? r?.want : show === 'model' ? r?.got : undefined;
          return (
            <div key={f.key} className={cx('pm-form-row', show === 'model' && r?.ok === false && 'bad')}>
              <Pin tone={show === 'blank' ? 'muted' : tone} n={i + 1} />
              <span className="lbl">{f.label}</span>
              {show !== 'blank' ? <span>{show === 'model' && r && r.ok !== null && <Tick ok={r.ok} size="lg" />}</span> : <span />}
              <span className={cx('box', show === 'blank' && 'empty')}>
                {show === 'blank' ? (
                  RULES[f.key]
                ) : show === 'model' && r?.ok === false ? (
                  <>
                    <s>{cvFieldText(f.key, value)}</s>
                    <span className="fix">✓ {cvFieldText(f.key, r.want)}</span>
                  </>
                ) : (
                  cvFieldText(f.key, value)
                )}
              </span>
              {show === 'model' && r?.why && <span className="why">{r.why}</span>}
            </div>
          );
        })}
      </div>
    </Paper>
  );
}

/** The relevant roles on a months timeline: what counts, part-time hatched, the longest run outlined. */
export function ExperienceTimeline({ cv, input }: { cv: ReadCv; input: CaseVisualInput }) {
  const rm = relevantMonths(cv);
  const key = keyOf(input);
  const roles = cv.roles.filter((r) => r.start !== null && r.end !== null);
  if (!rm || !roles.length || !key || rm.total !== key.relevant_experience_months || rm.longest !== key.longest_unbroken_relevant_months) return null;
  const lo = Math.min(...roles.map((r) => r.start!));
  const hi = Math.max(...roles.map((r) => r.end!));
  const span = Math.max(1, hi - lo + 1);
  const pos = (m: number) => `${((m - lo) / span) * 100}%`;
  const width = (a: number, b: number) => `${((b - a + 1) / span) * 100}%`;
  const years: number[] = [];
  for (let y = Math.floor(lo / 12); y <= Math.floor(hi / 12); y++) years.push(y);
  const full = [...rm.months].filter(([, v]) => v === 1).length;
  const half = [...rm.months].filter(([, v]) => v === 0.5).length;
  return (
    <div className="pm-tl" aria-label="Experience timeline">
      {roles.map((r) => (
        <div key={r.line} className="pm-tl-row">
          <span className={cx('n', !r.relevant && 'off')} title={`${r.title} · ${r.employer}`}>
            {r.title}
          </span>
          <span className="pm-tl-track">
            <i className={cx('pm-tl-bar', !r.relevant ? 'off' : r.partTime && 'part')} style={{ left: pos(r.start!), width: width(r.start!, r.end!) }} />
          </span>
        </div>
      ))}
      <div className="pm-tl-row">
        <span className="n">
          <b>Counted</b>
        </span>
        <span className="pm-tl-track">
          {[...rm.months.keys()].map((m) => (
            <i key={m} className={cx('pm-tl-bar sum', rm.months.get(m) === 0.5 && 'part')} style={{ left: pos(m), width: width(m, m), borderRadius: 0 }} />
          ))}
          {rm.longestFrom !== null && <i className="pm-tl-bar run" style={{ left: pos(rm.longestFrom), width: width(rm.longestFrom, rm.longestFrom + rm.longest - 1) }} />}
        </span>
      </div>
      <div className="pm-tl-row">
        <span />
        <span className="pm-tl-axis">
          {years.map((y) => (
            <span key={y}>{y}</span>
          ))}
        </span>
      </div>
      <div className="pm-tl-sum">
        <span>
          <b>{full}</b> full months{half ? <> + <b>{half}</b> part-time months × ½</> : null} = <b>{rm.total}</b> months
        </span>
        {rm.longestFrom !== null && (
          <span>
            Longest unbroken run (outlined): <b>{rm.longest}</b> months, {monthLabel(rm.longestFrom)} – {monthLabel(rm.longestFrom + rm.longest - 1)}
          </span>
        )}
      </div>
    </div>
  );
}

function marksFor(cv: ReadCv, rows: CvFormRow[] | null, keyTone: boolean, input: CaseVisualInput): Map<number, LineMark> {
  const src = cvSources(cv, keyOf(input));
  const out = new Map<number, LineMark>();
  CV_FIELDS.forEach((f, i) => {
    const r = rows?.find((x) => x.key === f.key);
    const tone: Tone = keyTone ? 'key' : r?.ok === false ? 'bad' : 'good';
    for (const l of src[f.key]) {
      const m = out.get(l) ?? { pins: [], tone };
      if (!m.pins.some((p) => p.n === i + 1)) m.pins.push({ n: i + 1, tone });
      out.set(l, m);
    }
  });
  // A line serving several boxes: green when all are right, red when all are wrong, blue (pins coloured) when mixed.
  for (const m of out.values()) {
    m.pins.sort((a, b) => a.n - b.n);
    const tones = new Set(m.pins.map((p) => p.tone));
    m.tone = tones.size === 1 ? m.pins[0]!.tone : 'info';
  }
  return out;
}

function alt(cv: ReadCv): string {
  const roles = cv.roles.map((r) => `${r.title} at ${r.employer}, ${r.dates}`).join('; ');
  return `The CV of ${cv.name}: ${roles}. Beside it, the screening form for a ${cv.job} with seven boxes to fill in.`;
}

export function CvReadBeats(p: KindProps) {
  const cv = useMemo(() => readCvQuestion(p.prompt || p.q.turns[0] || '', p.q.notes), [p.prompt, p.q]);
  const res = useMemo(() => (cv && p.a ? cvReadRows(cv, p.a) : null), [cv, p.a]);
  const keyRows = useMemo(() => (cv ? cvReadRows(cv, p.q).rows : null), [cv, p.q]);
  if (!cv) return <p className="lc-empty">This CV could not be drawn.</p>;
  const wrong = res?.rows.filter((r) => r.ok === false) ?? [];
  const rtw = res?.rows.find((r) => r.key === 'right_to_work');
  const months = res?.rows.find((r) => r.key === 'relevant_experience_months');
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={res ? cvReadSentence(res.rows, res.answered) : null}
        question={{
          title: 'The question',
          sub: `One CV, a screening form for a ${cv.job}, and written rules`,
          alt: alt(cv),
          node: (
            <div className="pm-cvq">
              <ScreeningForm cv={cv} show="blank" />
              <ReadCvPage cv={cv} />
            </div>
          ),
        }}
        keyBeat={
          keyRows
            ? {
                title: 'The correct form',
                sub: 'Yellow marks show where each answer comes from',
                alt: keyRows.map((r) => `${r.label}: ${cvFieldText(r.key, r.want)}`).join('. '),
                node: (
                  <div className="pm-cvq">
                    <ScreeningForm cv={cv} rows={keyRows} show="key" />
                    <div className="pm-pile">
                      <ReadCvPage cv={cv} marks={marksFor(cv, null, true, p.q)} />
                      <ExperienceTimeline cv={cv} input={p.q} />
                    </div>
                  </div>
                ),
              }
            : null
        }
        answer={
          p.a && res
            ? {
                title: 'The model’s answer',
                sub: res.answered ? 'The form as it filled it in. The numbered marks on the CV show where each answer comes from' : 'No form could be read from the reply',
                alt: res.rows.map((r) => `${r.label}: ${cvFieldText(r.key, r.got)}${r.ok === false ? `, wrong; correct ${cvFieldText(r.key, r.want)}` : r.ok ? ', right' : ''}`).join('. '),
                node: (
                  <div className="pm-cvq">
                    <ScreeningForm cv={cv} rows={res.rows} show="model" />
                    <ReadCvPage cv={cv} marks={marksFor(cv, res.rows, false, p.q)} />
                  </div>
                ),
              }
            : null
        }
        impact={
          rtw?.ok === false ? (
            <Impact tone="bad">
              {rtw.want === 'needs sponsorship'
                ? 'Would have recorded someone who needs a visa sponsor as free to work: a firm without a sponsor licence could not employ them.'
                : rtw.want === 'not stated'
                  ? 'Would have recorded a right to work the CV never states: a guess about a person, not a fact from the CV.'
                  : 'Would have marked someone free to work in the UK as needing sponsorship, and could screen them out unfairly.'}
            </Impact>
          ) : months?.ok === false ? (
            <Impact tone="warn">
              Recorded {String(months.got)} months of experience instead of {String(months.want)}: a minimum-experience screen would use the wrong figure.
            </Impact>
          ) : res && res.answered && !wrong.length ? (
            <Impact tone="good">Every box matches the answer key: this form could go straight into the hiring system.</Impact>
          ) : null
        }
        result={
          res && wrong.length
            ? {
                title: 'The result',
                alt: wrong.map((r) => `${r.label}: gave ${cvFieldText(r.key, r.got)}, correct ${cvFieldText(r.key, r.want)}. ${r.why ?? ''}`).join(' '),
                node: (
                  <div className="pm-pile">
                    <ul className="pm-problems">
                      {wrong.map((r) => (
                        <li key={r.key} className="t-bad">
                          <Pin tone="bad" n={r.n} />
                          <span>
                            <span className="pm-tag">{r.label}</span>
                            Gave <b>{cvFieldText(r.key, r.got)}</b>; correct: <b>{cvFieldText(r.key, r.want)}</b>. {r.why}
                          </span>
                        </li>
                      ))}
                    </ul>
                    {(months?.ok === false || res.rows.find((r) => r.key === 'longest_unbroken_relevant_months')?.ok === false) && <ExperienceTimeline cv={cv} input={p.q} />}
                  </div>
                ),
              }
            : null
        }
      />
    </div>
  );
}

/** Result Inspector / Presenter: the filled-in form beside the marked CV. */
export function CvReadInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const cv = readCvQuestion(input.turns[0] ?? '', input.notes);
  if (!cv) return null;
  const { rows, answered } = cvReadRows(cv, input);
  const right = rows.filter((r) => r.ok).length;
  return (
    <VizFrame
      mode={mode}
      tone={!answered ? 'bad' : right === rows.length ? 'good' : right >= rows.length - 2 ? 'half' : 'bad'}
      className="pm-inline"
      eyebrow={`Read a CV · ${cv.job} screening form`}
      headline={cvReadSentence(rows, answered)}
      big={`${right}/${rows.length}`}
      bigSub="boxes right"
      legend={[
        { tone: 'good', label: 'Box right · where it comes from' },
        { tone: 'bad', label: 'Box wrong · struck out, correct value beside it' },
      ]}
    >
      <div className="pm-cvq pm-doc-root">
        <ScreeningForm cv={cv} rows={rows} show="model" />
        <ReadCvPage cv={cv} marks={marksFor(cv, rows, false, input)} />
      </div>
    </VizFrame>
  );
}
