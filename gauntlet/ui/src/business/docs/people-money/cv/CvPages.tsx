/**
 * One-page CVs drawn from the plain text the model was given: a name header with contact line, experience as a
 * timeline of roles with bullets, education, skills and the rest. Lines can carry highlighter marks and numbered
 * pins (Read a CV) or requirement annotations (Shortlist the Candidates).
 */
import type { ReactNode } from 'react';
import { cx } from '../../../../components/ui.tsx';
import { Hl, Paper, Pin, type Tone } from '../kit.tsx';
import type { Assessment, Candidate, ReadCv, ShortlistQuestion } from '../model/cv.ts';
import type { CvJobRubric } from '../model/cvRubrics.ts';

const initials = (name: string) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w[0]!.toUpperCase())
    .slice(0, 2)
    .join('');

const titleCase = (s: string) => (s === s.toUpperCase() ? s.toLowerCase().replace(/(^|[\s-])\p{L}/gu, (m) => m.toUpperCase()) : s);

export interface LineMark {
  pins: Array<{ n: number; tone: Tone }>;
  tone: Tone;
}

/** Read a CV: the CV page, with highlighter marks on the lines the form's fields come from. */
export function ReadCvPage({ cv, marks, stamp }: { cv: ReadCv; marks?: Map<number, LineMark>; stamp?: ReactNode }) {
  const mark = (i: number, body: ReactNode) => {
    const m = marks?.get(i);
    if (!m) return body;
    return (
      <Hl tone={m.tone}>
        {m.pins.map((p) => (
          <Pin key={p.n} tone={p.tone} n={p.n} />
        ))}
        {body}
      </Hl>
    );
  };
  const contactParts = cv.contact.split('·').map((x) => x.trim());
  return (
    <Paper className="pm-cv" label={`CV of ${titleCase(cv.name)}`}>
      {stamp && <div className="pm-cv-stamp">{stamp}</div>}
      <div className="pm-cv-head">
        <span className="pm-cv-face" aria-hidden="true">
          {initials(cv.name)}
        </span>
        <div>
          <div className="pm-cv-name">{titleCase(cv.name)}</div>
          <div className="pm-cv-contact">
            {contactParts.map((c, i) => (
              <span key={i}>{c}</span>
            ))}
          </div>
        </div>
      </div>
      {cv.sections.map((s) => {
        if (s.title === 'Note') {
          const i = s.lines[0]!;
          return (
            <div key={s.title} className="pm-cv-sec">
              <p className="pm-cv-line">{mark(i, <em>{cv.lines[i]!.text}</em>)}</p>
            </div>
          );
        }
        const role = (i: number) => cv.roles.find((r) => r.line === i);
        const items: ReactNode[] = [];
        let bullets: ReactNode[] = [];
        const flush = (k: number) => {
          if (bullets.length) items.push(<ul key={`b${k}`} className="b">{bullets}</ul>);
          bullets = [];
        };
        s.lines.forEach((i) => {
          const l = cv.lines[i]!;
          const r = role(i);
          if (l.kind === 'bullet') {
            bullets.push(<li key={i}>{mark(i, l.text.replace(/^\s*•\s*/, ''))}</li>);
            return;
          }
          flush(i);
          if (r)
            items.push(
              <div key={i} className="pm-cv-role">
                <span>
                  {mark(
                    i,
                    <>
                      <span className="t">{r.title}</span> <span className="e">· {r.employer}</span>
                    </>,
                  )}
                </span>
                <span className="d">{r.dates}</span>
              </div>,
            );
          else
            items.push(
              <p key={i} className="pm-cv-line">
                {mark(i, l.text.replace(/^\s*•\s*/, ''))}
              </p>,
            );
        });
        flush(-1);
        return (
          <div key={s.title} className="pm-cv-sec">
            <h5>{titleCase(s.title)}</h5>
            {items}
          </div>
        );
      })}
    </Paper>
  );
}

/** Shortlist: one candidate's CV, optionally with every requirement annotated (evidence bullets marked). */
export function CandidateCvPage({ q, c, a, stamp, compact }: { q: ShortlistQuestion; c: Candidate; a?: Assessment | null; stamp?: ReactNode; compact?: boolean }) {
  const rb: CvJobRubric | null = q.rubric;
  const evidenceOf = (b: string) => rb?.nice.find((n) => n.bullets.includes(b)) ?? null;
  const qualMark = (name: string, status: string | null): ReactNode => {
    if (!a || !rb) return null;
    const must = rb.mustOk.includes(name) || rb.degreesOk.includes(name);
    const bonus = q.bonus?.name === name;
    if (!must && !bonus) return null;
    if (status) return <span className="pm-cv-ann t-bad">✕ {must ? 'must-have' : 'bonus'} not met: not completed</span>;
    return <span className="pm-cv-ann t-good">✓ {must && bonus ? 'must-have + bonus' : must ? 'must-have' : `+${q.bonus?.pts} bonus`}</span>;
  };
  const skillsListed = new Set(a ? a.evidence.filter((e) => e.listedOnly).map((e) => e.label) : []);
  const isListedOnly = (s: string) => a && [...skillsListed].some((l) => l.toLowerCase().includes(s.toLowerCase().split(/[ /(]/)[0]!));
  const other = c.other ?? '';
  const rtwBad = !!a?.checks.find((x) => x.must.kind === 'rtw' && !x.ok);
  const licBad = !!a?.checks.find((x) => x.must.kind === 'licence' && !x.ok);
  return (
    <Paper className={cx('pm-cv', compact && 'compact')} label={`CV ${c.id}: ${c.name}`}>
      {stamp && <div className="pm-cv-stamp">{stamp}</div>}
      <div className="pm-cv-head">
        <span className="pm-cv-face" aria-hidden="true">
          {initials(c.name)}
        </span>
        <div>
          <div className="pm-cv-name">{titleCase(c.name)}</div>
          <div className="pm-cv-contact">
            <span>{c.id}</span>
            <span>{c.town}</span>
            <span>{c.email}</span>
            <span>{c.phone}</span>
          </div>
        </div>
      </div>
      {c.profile && (
        <div className="pm-cv-sec">
          <h5>Profile</h5>
          <p>{c.profile}</p>
        </div>
      )}
      <div className="pm-cv-sec">
        <h5>Work history</h5>
        {c.roles.map((r, k) => (
          <div key={k}>
            <div className="pm-cv-role">
              <span>
                {a ? (
                  <Hl tone={r.relevant ? 'info' : 'muted'}>
                    <span className="t">{r.title}</span>
                  </Hl>
                ) : (
                  <span className="t">{r.title}</span>
                )}{' '}
                <span className="e">· {r.employer}</span>
                {a && <span className={cx('pm-cv-ann', r.relevant ? 't-good' : 't-muted')}>{r.relevant ? 'relevant role' : 'not a relevant role'}</span>}
              </span>
              <span className="d">{r.dates}</span>
            </div>
            <ul className="b">
              {r.bullets.map((b, j) => {
                const ev = a ? evidenceOf(b) : null;
                return (
                  <li key={j}>
                    {ev ? (
                      <Hl tone="good">
                        {b} <span className="pm-cv-ann t-good">+{q.nice.find((n) => n.label === ev.label)?.pts ?? ev.pts} {ev.label.toLowerCase()}</span>
                      </Hl>
                    ) : (
                      b
                    )}
                  </li>
                );
              })}
            </ul>
            {k === 0 && c.breaks.length > 0 && c.breaks.map((x) => <p key={x} className="pm-cv-break">{x}</p>)}
          </div>
        ))}
      </div>
      {c.quals.length > 0 && (
        <div className="pm-cv-sec">
          <h5>Qualifications</h5>
          {c.quals.map((x, k) => (
            <p key={k} className="pm-cv-line">
              {x.name}
              {x.status && <span className="muted"> ({x.status})</span>}
              {qualMark(x.name, x.status)}
            </p>
          ))}
        </div>
      )}
      {c.skills.length > 0 && (
        <div className="pm-cv-sec">
          <h5>Skills</h5>
          <div className="pm-cv-skills">
            {c.skills.map((s) => (
              <span key={s} className={cx(isListedOnly(s) && 'nope')} title={isListedOnly(s) ? 'Listed as a skill only: earns nothing without a job that shows it' : undefined}>
                {s}
              </span>
            ))}
          </div>
          {a && skillsListed.size > 0 && <p className="pm-cv-ann t-muted">Skills listed without a job that shows them earn nothing.</p>}
        </div>
      )}
      {other && (
        <div className="pm-cv-sec">
          <h5>Other</h5>
          <p>{rtwBad || licBad ? <Hl tone="bad">{other}</Hl> : other}</p>
        </div>
      )}
      {c.hidden && (
        <div className="pm-cv-hidden">
          <b>Hidden text found in the file</b>
          {c.hidden}
        </div>
      )}
      <p className="pm-cv-ref">References available on request.</p>
    </Paper>
  );
}
