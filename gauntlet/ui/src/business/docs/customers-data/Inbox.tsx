/**
 * Monday Inbox (work.inbox-triage) and What Do I Need to Do? (work.email-actions), drawn as a mail client: the
 * inbox list with sender, subject, preview and time; the model's labels and to-dos on each row with the correct
 * ones beside them; an email with its jobs highlighted and turned into a ticked or crossed to-do list.
 */
import type { CSSProperties, ReactNode } from 'react';
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { cx } from '../../../components/ui.tsx';
import { Chip, Fix, Impact, Legend, MiniWall, MoreLine, Paper, Tally, Tick, docVisual, type MiniItem, type Tone } from './parts.tsx';
import { hueOf, initials, niceDate, plural } from './kit.ts';
import { deadlineSentence, earlierCopy, emailAnswer, highlightJobs, inboxAnswer, lookalike, parseEmailActions, parseInbox, type Action, type EmailDoc, type InboxAnswer, type InboxDoc, type JobMark, type Mail, type MailMark } from './inbox.ts';

// ───────────────────────────── labels ─────────────────────────────

const CATS: Record<string, { label: string; hue: number }> = {
  phishing: { label: 'Scam', hue: 0 },
  duplicate: { label: 'Duplicate', hue: 220 },
  newsletter: { label: 'Newsletter', hue: 200 },
  meeting: { label: 'Meeting', hue: 270 },
  hr: { label: 'HR', hue: 320 },
  manager: { label: 'Manager', hue: 30 },
  client: { label: 'Client', hue: 150 },
  supplier: { label: 'Supplier', hue: 45 },
  internal: { label: 'Internal', hue: 190 },
};

function catLabel(c: string | null): string {
  if (!c) return 'No label';
  return CATS[c.toLowerCase()]?.label ?? c;
}

function CatChip({ c, strike }: { c: string | null; strike?: boolean }) {
  const hue = c ? (CATS[c.toLowerCase()]?.hue ?? hueOf(c)) : 220;
  return (
    <Chip strike={strike} style={{ ['--h' as string]: hue } as CSSProperties}>
      <span className={cx('cd-cat', c?.toLowerCase() === 'phishing' && 'scam')}>{catLabel(c)}</span>
    </Chip>
  );
}

/** The folder a message ends up in: Urgent / This week / Later for a to-do, Scam, Duplicate or FYI otherwise. */
function folderOf(cat: string | null, act: Action | null): { label: string; tone: Tone } {
  const c = cat?.toLowerCase();
  if (c === 'phishing') return { label: 'Spam · scam', tone: 'bad' };
  if (c === 'duplicate') return { label: 'Duplicate', tone: 'neutral' };
  if (act) {
    const p = act.priority.toUpperCase();
    return p === 'P1' ? { label: 'Urgent', tone: 'bad' } : p === 'P2' ? { label: 'This week', tone: 'warn' } : { label: 'Later', tone: 'info' };
  }
  return { label: 'FYI', tone: 'neutral' };
}

function ActTag({ a, fields }: { a: Action; fields?: MailMark['actFields'] }) {
  const f = (ok: boolean | undefined, text: ReactNode) => <span className={cx(fields && (ok ? 'ok' : 'no'))}>{text}</span>;
  return (
    <span className="cd-todo">
      <span aria-hidden="true">☐</span> {f(fields?.owner, a.owner.split(' ')[0] || '—')} · due {f(fields?.due, niceDate(a.due) || '—')} · {f(fields?.priority, a.priority || '—')}
    </span>
  );
}

// ───────────────────────────── the mail client ─────────────────────────────

function Avatar({ name }: { name: string }) {
  return (
    <span className="cd-avatar" style={{ ['--h' as string]: hueOf(name) } as CSSProperties} aria-hidden="true">
      {initials(name)}
    </span>
  );
}

function shortWhen(w: string): string {
  // "Fri 27 Feb 2026, 07:02" → "Fri 27 Feb 07:02"
  return w.replace(/\s\d{4},?/, '');
}

function MailRow({ m, children, tone }: { m: Mail; children?: ReactNode; tone?: 'good' | 'bad' | null }) {
  return (
    <li className={cx('cd-mrow', tone && `t-${tone}`)}>
      <Avatar name={m.fromName} />
      <div className="cd-mrow-main">
        <div className="cd-mrow-top">
          <b className="cd-mrow-from">{m.fromName}</b>
          <span className="cd-mrow-id">{m.id}</span>
          <span className="cd-mrow-when">{shortWhen(m.when)}</span>
        </div>
        <div className="cd-mrow-subj">{m.subject}</div>
        <div className="cd-mrow-prev">{m.preview}</div>
        {children}
      </div>
    </li>
  );
}

function InboxFrame({ doc, children, count }: { doc: InboxDoc; children: ReactNode; count?: string }) {
  return (
    <Paper className="cd-mail">
      <div className="cd-mail-bar">
        <span className="cd-mail-dot" aria-hidden="true" />
        <b>Inbox</b>
        <span className="muted">{doc.owner}</span>
        <span className="spacer" />
        <span className="muted tnum">{count ?? plural(doc.mails.length, 'message')}</span>
      </div>
      <ul className="cd-mlist">{children}</ul>
    </Paper>
  );
}

/** One email opened full size (scam domains marked). */
export function MailView({ doc, m, mark }: { doc: InboxDoc; m: Mail; mark?: MailMark }) {
  const fake = doc.key?.cats[m.id] === 'phishing' ? lookalike(doc, m.fromAddr) : null;
  const [own, fwd] = m.body.split(/-{3,}\s*Forwarded message\s*-{3,}/i);
  return (
    <Paper className="cd-mailview">
      <div className="cd-mv-subj">{m.subject}</div>
      <div className="cd-mv-head">
        <Avatar name={m.fromName} />
        <div>
          <b>{m.fromName}</b>{' '}
          <span className={cx('cd-mv-addr', fake && 'fake')}>&lt;{m.fromAddr}&gt;</span>
          <div className="muted">
            to {m.to} · {m.when}
          </div>
        </div>
      </div>
      {fake?.real && (
        <div className="cd-mv-warn">
          Sent from <b>{fake.fake}</b> — the real address is <b>{fake.real}</b>
        </div>
      )}
      <div className="cd-mv-body">{own}</div>
      {fwd && <blockquote className="cd-mv-fwd">{fwd.trim()}</blockquote>}
      {mark && <MarkLine m={m} mark={mark} doc={doc} />}
    </Paper>
  );
}

function MarkLine({ m, mark, doc }: { m: Mail; mark: MailMark; doc: InboxDoc }) {
  const why = whyMail(doc, m, mark);
  return (
    <div className="cd-markline">
      <div className="cd-markline-row">
        <span className="cd-k">Model</span>
        <CatChip c={mark.cat} strike={mark.catOk === false} />
        {mark.act && <ActTag a={mark.act} fields={mark.actFields} />}
        {!mark.act && <span className="muted">no to-do</span>}
      </div>
      {(mark.catOk === false || (mark.actState !== 'right' && mark.actState !== 'none')) && (
        <div className="cd-markline-row">
          <span className="cd-k">Correct</span>
          <CatChip c={mark.keyCat} />
          {mark.keyAct ? <ActTag a={mark.keyAct} /> : <span className="muted">no to-do</span>}
        </div>
      )}
      {why && <p className="cd-why">{why}</p>}
    </div>
  );
}

function whyMail(doc: InboxDoc, m: Mail, mark: MailMark): string | null {
  if (mark.keyCat === 'phishing' && mark.catOk === false) {
    const f = lookalike(doc, m.fromAddr);
    return f?.real ? `A scam: sent from ${f.fake}, a lookalike of ${f.real}, asking you to act.` : 'A scam: the sender’s address is not the one in the directory.';
  }
  if (mark.cat?.toLowerCase() === 'phishing' && mark.keyCat !== 'phishing') return `Not a scam: ${m.fromAddr} is the genuine address in the directory.`;
  if (mark.keyCat === 'duplicate' && mark.catOk === false) {
    const twin = earlierCopy(doc, m.id);
    return twin ? `An exact copy of ${twin}: only the first copy is triaged.` : 'An exact copy of an earlier message.';
  }
  if (mark.actState === 'invented') return mark.keyCat === 'duplicate' ? 'A duplicate never carries a to-do.' : 'Nothing here is a new request with a deadline (it repeats, moves, cancels or only forwards one).';
  const s = deadlineSentence(m.body);
  if (mark.actState === 'missed') return s ? `It asks: “${s}”` : 'It asks for something by a deadline.';
  if (mark.actState === 'wrong' && mark.actFields && !mark.actFields.due) return s ? `The deadline: “${s}”` : null;
  return null;
}

// ───────────────────────────── Monday Inbox ─────────────────────────────

const ROWS = 14;

function rowsToShow(doc: InboxDoc, ans: InboxAnswer | null): Mail[] {
  if (doc.mails.length <= ROWS + 2) return doc.mails;
  if (!ans) return doc.mails.slice(0, ROWS);
  const bad = new Set(ans.marks.filter((k) => !k.catOk || (k.actState !== 'right' && k.actState !== 'none')).map((k) => k.id));
  const keep = new Set<string>([...bad].slice(0, ROWS));
  for (const m of doc.mails) if (keep.size < ROWS) keep.add(m.id);
  return doc.mails.filter((m) => keep.has(m.id));
}

function InboxQuestion({ doc }: { doc: InboxDoc }) {
  const shown = rowsToShow(doc, null);
  return (
    <>
      <InboxFrame doc={doc}>
        {shown.map((m) => (
          <MailRow key={m.id} m={m} />
        ))}
      </InboxFrame>
      <MoreLine n={doc.mails.length - shown.length} noun="emails (open any one below)" />
    </>
  );
}

function InboxKey({ doc }: { doc: InboxDoc }) {
  const shown = rowsToShow(doc, null);
  return (
    <InboxFrame doc={doc} count={`${plural(Object.keys(doc.key?.actions ?? {}).length, 'to-do')}`}>
      {shown.map((m) => {
        const c = doc.key?.cats[m.id] ?? null;
        const a = doc.key?.actions[m.id] ?? null;
        const f = folderOf(c, a);
        return (
          <MailRow key={m.id} m={m}>
            <div className="cd-labels">
              <Chip tone={f.tone}>{f.label}</Chip>
              <CatChip c={c} />
              {a && <ActTag a={a} />}
            </div>
          </MailRow>
        );
      })}
    </InboxFrame>
  );
}

function InboxAnswerView({ doc, ans }: { doc: InboxDoc; ans: InboxAnswer }) {
  const shown = rowsToShow(doc, ans);
  const marks = new Map(ans.marks.map((k) => [k.id, k]));
  return (
    <>
      <InboxFrame doc={doc} count={`${ans.catRight}/${doc.mails.length} labels right`}>
        {shown.map((m) => {
          const k = marks.get(m.id)!;
          const ok = k.catOk !== false && (k.actState === 'right' || k.actState === 'none');
          const f = folderOf(k.cat, k.act);
          const why = ok ? null : whyMail(doc, m, k);
          return (
            <MailRow key={m.id} m={m} tone={ok ? 'good' : 'bad'}>
              <div className="cd-labels">
                <Tick ok={ok} />
                {k.cat?.toLowerCase() !== 'phishing' && <Chip tone={f.tone}>{f.label}</Chip>}
                <CatChip c={k.cat} strike={k.catOk === false} />
                {k.catOk === false && (
                  <Fix>
                    <CatChip c={k.keyCat} />
                  </Fix>
                )}
                {k.act && <ActTag a={k.act} fields={k.actFields} />}
                {k.actState === 'invented' && <span className="cd-flag t-bad">invented to-do</span>}
                {k.actState === 'missed' && <span className="cd-flag t-bad">missed to-do</span>}
              </div>
              {(k.actState === 'missed' || k.actState === 'wrong') && k.keyAct && (
                <div className="cd-labels">
                  <Fix>
                    <ActTag a={k.keyAct} />
                  </Fix>
                </div>
              )}
              {why && <p className="cd-why">{why}</p>}
            </MailRow>
          );
        })}
      </InboxFrame>
      <MoreLine n={doc.mails.length - shown.length} noun="emails, all labelled right (open any one below)" />
      <Legend
        items={[
          { tone: 'good', label: 'right' },
          { tone: 'bad', label: 'wrong (correct one beside it)' },
        ]}
      />
    </>
  );
}

function inboxResult(doc: InboxDoc, ans: InboxAnswer) {
  const lines: Array<{ tone: Tone; text: ReactNode }> = [];
  const subj = (id: string) => doc.mails.find((m) => m.id === id)?.subject ?? id;
  for (const id of ans.scamsMissed) {
    const m = doc.mails.find((x) => x.id === id)!;
    const f = lookalike(doc, m.fromAddr);
    lines.push({ tone: 'bad', text: <>Treated the scam “{subj(id)}” ({id}) as genuine{f?.real ? <> — it came from {f.fake}, not {f.real}</> : null}.</> });
  }
  for (const id of ans.falseScams) lines.push({ tone: 'warn', text: <>Binned a genuine email as a scam: “{subj(id)}” ({id}).</> });
  for (const k of ans.marks.filter((x) => x.actState === 'missed')) lines.push({ tone: 'bad', text: <>No to-do for “{subj(k.id)}” ({k.id}): due {niceDate(k.keyAct!.due)}, {k.keyAct!.priority} — it would have been missed.</> });
  for (const k of ans.marks.filter((x) => x.actState === 'wrong' && x.actFields && !x.actFields.due)) lines.push({ tone: 'warn', text: <>“{subj(k.id)}” ({k.id}) put down for {niceDate(k.act!.due)}; it is due {niceDate(k.keyAct!.due)}.</> });
  for (const k of ans.marks.filter((x) => x.actState === 'wrong' && x.actFields?.due && !x.actFields.owner)) lines.push({ tone: 'warn', text: <>“{subj(k.id)}” ({k.id}) given to {k.act!.owner || 'nobody'}; it is {k.keyAct!.owner}’s.</> });
  for (const k of ans.marks.filter((x) => x.actState === 'invented')) lines.push({ tone: 'warn', text: <>Made a to-do from “{subj(k.id)}” ({k.id}), which asks for nothing new.</> });
  const more = lines.length - 6;
  return (
    <>
      <Tally
        items={[
          { n: ans.catRight, of: doc.mails.length, label: 'emails labelled right' },
          { n: ans.actRight, of: ans.actTotal, label: 'to-dos right (who, when, priority)' },
          { n: ans.marks.filter((k) => k.keyCat === 'phishing').length - ans.scamsMissed.length, of: ans.marks.filter((k) => k.keyCat === 'phishing').length, label: 'scams caught' },
        ].filter((t) => t.of > 0)}
      />
      <Impact lines={lines.slice(0, 6)} />
      {more > 0 && <div className="cd-more">and {more} more slips</div>}
    </>
  );
}

function inboxSentence(doc: InboxDoc, ans: InboxAnswer): string {
  const head = `Labelled ${ans.catRight} of ${doc.mails.length} emails and got ${ans.actRight} of ${ans.actTotal} to-dos exactly right`;
  const missed = ans.marks.filter((k) => k.actState === 'missed').length;
  const invented = ans.marks.filter((k) => k.actState === 'invented').length;
  const dates = ans.marks.filter((k) => k.actState === 'wrong').length;
  const labels = ans.marks.filter((k) => k.catOk === false && k.keyCat !== 'phishing').length;
  if (ans.scamsMissed.length) return `${head}, but let ${plural(ans.scamsMissed.length, 'scam')} through as genuine mail: in a real inbox, that is how passwords and payments get stolen.`;
  if (missed) return `${head}; ${plural(missed, 'task')} with a deadline never made it onto the to-do list, so it would have been missed.`;
  if (ans.catRight === doc.mails.length && ans.actRight === ans.actTotal && !invented) return `${head}: every scam caught, every deadline worked out. This inbox could be handed over.`;
  const slips = [invented ? `${plural(invented, 'to-do')} made up from emails that ask for nothing new` : '', dates ? `${plural(dates, 'to-do')} with the wrong date, owner or priority` : '', labels ? `${plural(labels, 'wrong label')}` : '', ans.falseScams.length ? `${plural(ans.falseScams.length, 'genuine email')} binned as a scam` : ''].filter(Boolean);
  return `${head}. The slips: ${slips.join(', ')}. Worth a human check before relying on it.`;
}

function inboxWall(doc: InboxDoc, ans: InboxAnswer | null) {
  if (doc.mails.length <= ROWS + 2) return null;
  const marks = new Map(ans?.marks.map((k) => [k.id, k]) ?? []);
  const items: MiniItem[] = doc.mails.map((m) => {
    const k = marks.get(m.id);
    const ok = !k || (k.catOk !== false && (k.actState === 'right' || k.actState === 'none'));
    return { id: m.id, label: m.id, tone: !ans ? 'neutral' : ok ? 'good' : 'bad', title: `${m.id} · ${m.fromName}: ${m.subject}` };
  });
  return <MiniWall items={items} noun="email" note={ans ? `All ${doc.mails.length} emails, green right, red wrong` : `All ${doc.mails.length} emails`} render={(id) => <MailView doc={doc} m={doc.mails.find((m) => m.id === id)!} mark={marks.get(id)} />} />;
}

export const INBOX_TRIAGE = docVisual<InboxDoc, InboxAnswer>({
  family: 'extraction',
  caption: 'The inbox as the assistant saw it, each email with the model’s label, folder and to-do; wrong ones in red with the correct label beside them.',
  doc: (input) => parseInbox(input.turns[0] ?? '', input.expected),
  answer: (input, doc) => inboxAnswer(input, doc),
  sub: (doc) => `${plural(doc.mails.length, 'email')} for ${doc.owner}${doc.now ? ` · ${doc.now}` : ''}`,
  Question: InboxQuestion,
  QuestionExtra: ({ doc }) => inboxWall(doc, null),
  Key: InboxKey,
  Answer: ({ doc, ans }) => <InboxAnswerView doc={doc} ans={ans} />,
  AnswerExtra: ({ doc, ans }) => inboxWall(doc, ans),
  Result: ({ doc, ans }) => inboxResult(doc, ans),
  sentence: (doc, ans) => inboxSentence(doc, ans),
  headline: (doc, ans) => `${ans.catRight} of ${doc.mails.length} emails labelled right · ${ans.actRight} of ${ans.actTotal} to-dos right`,
  alt: {
    question: (doc) => `An inbox of ${doc.mails.length} emails for ${doc.owner}, oldest first: ${doc.mails.slice(0, 5).map((m) => `${m.fromName}, “${m.subject}”`).join('; ')}…`,
    key: (doc) => `The correct triage: ${Object.keys(doc.key?.actions ?? {}).length} to-dos and ${Object.values(doc.key?.cats ?? {}).filter((c) => c === 'phishing').length} scams.`,
    answer: (doc, ans) => `The model labelled ${ans.catRight} of ${doc.mails.length} emails right and ${ans.actRight} of ${ans.actTotal} to-dos. Wrong: ${ans.marks.filter((k) => k.catOk === false).map((k) => `${k.id} called ${catLabel(k.cat)}, should be ${catLabel(k.keyCat)}`).join('; ') || 'no labels'}.`,
  },
});

// ───────────────────────────── What Do I Need to Do? ─────────────────────────────

const JOB_HUES = [48, 190, 300, 140, 20, 250, 90, 330];

function jobHue(doc: EmailDoc, id: string): number {
  return JOB_HUES[Math.max(0, doc.jobs.findIndex((j) => j.id === id)) % JOB_HUES.length]!;
}

function EmailThread({ doc }: { doc: EmailDoc }) {
  return (
    <div className="cd-thread">
      {doc.messages.map((m, i) => (
        <Paper key={i} className={cx('cd-mailview', m.forwarded && 'fwd')}>
          {m.forwarded && <div className="cd-fwd-tag">Forwarded — for information</div>}
          {m.subject && <div className="cd-mv-subj">{m.subject}</div>}
          <div className="cd-mv-head">
            <Avatar name={m.from || '?'} />
            <div>
              <b>{m.from}</b>
              <div className="muted">
                to {m.to || '—'}
                {m.sent && <> · {m.sent}</>}
              </div>
            </div>
          </div>
          <div className="cd-mv-body">
            {highlightJobs(m.body, doc.jobs).map((s, k) =>
              s.job ? (
                <mark key={k} className="cd-hl" style={{ ['--h' as string]: jobHue(doc, s.job) } as CSSProperties}>
                  <sup>{s.job}</sup>
                  {s.text}
                </mark>
              ) : (
                <span key={k}>{s.text}</span>
              ),
            )}
          </div>
        </Paper>
      ))}
    </div>
  );
}

function JobList({ doc, marks }: { doc: EmailDoc; marks: JobMark[] | null }) {
  return (
    <Paper className="cd-todo-list" title={marks ? 'The model’s to-do list' : `Tracker jobs — which are ${doc.me.split(' ')[0]}’s?`}>
      <ul>
        {doc.jobs.map((j) => {
          const k = marks?.find((x) => x.id === j.id);
          const ok = k ? k.mineOk !== false && k.dueOk !== false : null;
          const mine = k ? k.mine : j.key?.mine ?? null;
          const due = k ? k.due : j.key?.due ?? null;
          return (
            <li key={j.id} className={cx('cd-job', ok === true && 't-good', ok === false && 't-bad', mine === false && 'notmine')}>
              <span className="cd-job-id" style={{ ['--h' as string]: jobHue(doc, j.id) } as CSSProperties}>
                {j.id}
              </span>
              <span className="cd-job-box" aria-hidden="true">
                {mine ? '☐' : '–'}
              </span>
              <div className="cd-job-main">
                <span className="cd-job-t">{j.title}</span>
                <span className="cd-job-v">
                  {mine === null ? (k ? 'no answer' : '') : mine ? <>mine · due <b>{due ? niceDate(due) : 'no date'}</b></> : 'not mine'}
                </span>
                {k && ok === false && j.key && (
                  <Fix>
                    {j.key.mine ? (
                      <>
                        mine · due <b>{j.key.due ? niceDate(j.key.due) : 'no date'}</b>
                      </>
                    ) : (
                      'not mine'
                    )}
                  </Fix>
                )}
                {k && ok === false && j.why && <span className="cd-why">{explainJob(j.why)}</span>}
              </div>
              {k && <Tick ok={ok} />}
            </li>
          );
        })}
      </ul>
    </Paper>
  );
}

const RULE_WORDS: Record<string, string> = {
  tomorrow: '“tomorrow”',
  weekday: '“by <weekday>”',
  nextWeekday: '“by next <weekday>”',
  endThisWeek: '“the end of this week”',
  endNextWeek: '“the end of next week”',
  endMonth: '“the end of the month”',
  workingDays: '“within N working days”',
  wdBefore: '“N working days before”',
  nth: '“by the Nth”',
  dayAfter: '“the day after”',
};

/** "mine, weekday Friday from 2026-03-11 → 2026-03-13" → "Mine: “by <weekday>” Friday, counted from Wed 11 Mar → Fri 13 Mar." */
export function explainJob(why: string): string {
  const m = why.match(/^(mine|not mine)(?:,\s*(\w+)\s*(.*?)\s*from\s*(\d{4}-\d{2}-\d{2})\s*→\s*(\d{4}-\d{2}-\d{2}))?/);
  if (!m) return why;
  if (m[1] === 'not mine') return 'Not this person’s job (given to someone else, taken back, or for info only).';
  if (!m[2]) return 'This person’s job.';
  const rule = RULE_WORDS[m[2]] ?? m[2];
  return `Theirs: ${rule}${m[3] ? ` ${m[3]}` : ''}, counted from ${niceDate(m[4])} → ${niceDate(m[5])}.`;
}

function EmailQuestion({ doc }: { doc: EmailDoc }) {
  return (
    <div className="cd-split">
      <EmailThread doc={doc} />
      <JobList doc={{ ...doc, jobs: doc.jobs.map((j) => ({ ...j, key: null })) }} marks={null} />
    </div>
  );
}

export const EMAIL_ACTIONS = docVisual<EmailDoc, JobMark[]>({
  family: 'extraction',
  caption: 'The email with every job highlighted, turned into the model’s to-do list: each job ticked or crossed against the key, with the right date beside a wrong one.',
  doc: (input) => parseEmailActions(input.turns[0] ?? '', input.expected, input.notes),
  answer: (input, doc) => emailAnswer(input, doc),
  sub: (doc) => `${plural(doc.messages.length, 'message')} · ${plural(doc.jobs.length, 'tracker job')} for ${doc.me}`,
  Question: EmailQuestion,
  Key: ({ doc }) => <JobList doc={doc} marks={null} />,
  Answer: ({ doc, ans }) => (
    <div className="cd-split">
      <EmailThread doc={doc} />
      <JobList doc={doc} marks={ans} />
    </div>
  ),
  Result: ({ doc, ans }) => {
    const right = ans.filter((k) => k.mineOk !== false && k.dueOk !== false).length;
    const lines: Array<{ tone: Tone; text: ReactNode }> = [];
    for (const k of ans) {
      const j = doc.jobs.find((x) => x.id === k.id)!;
      if (!j.key) continue;
      if (j.key.mine && k.mine === false) lines.push({ tone: 'bad', text: <>Dropped “{j.title}”: it is theirs, due {niceDate(j.key.due)}. Nobody would have done it.</> });
      else if (!j.key.mine && k.mine) lines.push({ tone: 'warn', text: <>Took on “{j.title}”, which is not theirs: wasted effort, or two people doing it.</> });
      else if (k.dueOk === false && j.key.mine) lines.push({ tone: 'warn', text: <>“{j.title}” put down for {k.due ? niceDate(k.due) : 'no date'}; it is due {niceDate(j.key.due)}{k.due && j.key.due && k.due > j.key.due ? ' — it would be late' : ''}.</> });
    }
    return (
      <>
        <Tally items={[{ n: right, of: ans.length, label: 'jobs right (whose, and when)' }]} />
        <Impact lines={lines} />
      </>
    );
  },
  sentence: (doc, ans) => {
    const wrong = ans.filter((k) => k.mineOk === false || k.dueOk === false);
    if (!wrong.length) return `Sorted all ${doc.jobs.length} jobs correctly: which are ${doc.me.split(' ')[0]}’s and when each is due.`;
    const late = wrong.filter((k) => {
      const j = doc.jobs.find((x) => x.id === k.id)!;
      return j.key?.mine && (k.mine === false || (k.due && j.key.due && k.due > j.key.due));
    }).length;
    return `Got ${doc.jobs.length - wrong.length} of ${doc.jobs.length} jobs right${late ? `; ${plural(late, 'job')} would have been missed or done late` : ''}.`;
  },
  headline: (doc, ans) => `${ans.filter((k) => k.mineOk !== false && k.dueOk !== false).length} of ${doc.jobs.length} jobs right`,
  alt: {
    question: (doc) => `An email thread to ${doc.me} (${doc.messages.length} messages) and ${doc.jobs.length} tracker jobs: ${doc.jobs.map((j) => j.title).join('; ')}.`,
    key: (doc) => `Correct: ${doc.jobs.map((j) => `${j.title}: ${j.key?.mine ? `mine, due ${niceDate(j.key.due)}` : 'not mine'}`).join('; ')}.`,
    answer: (doc, ans) => ans.map((k) => `${doc.jobs.find((j) => j.id === k.id)?.title}: ${k.mine ? `mine, due ${niceDate(k.due)}` : 'not mine'} (${k.mineOk !== false && k.dueOk !== false ? 'right' : 'wrong'})`).join('; '),
  },
});

export type { CaseVisualInput };
