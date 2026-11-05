/**
 * Customer service drawn as a helpdesk: each ticket with the customer's message, the order record and account
 * history; the model's decision as a stamp (REFUND £x / ESCALATE / DECLINE, priority, SLA due) with the correct one
 * beside it and the handbook rule that decides it. Tag the Ticket shows the helpdesk tag panel; Reply Within Policy
 * shows the drafted reply as an email with every policy check ticked or crossed.
 */
import type { ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { Chip, Fix, Impact, MiniWall, MoreLine, Paper, Stamp, Tally, Tick, docVisual, type MiniItem, type Tone } from './parts.tsx';
import { money, niceDate, plural, show } from './kit.ts';
import './support.css';
import { TAG_FIELDS, parseQueue, parseReply, parseTag, queueAnswer, replyAnswer, ruleFor, tagAnswer, type Order, type QueueAnswer, type QueueDoc, type ReplyAnswer, type ReplyDoc, type TagDoc, type TagMark, type Ticket, type TicketDecision, type TicketMark } from './support.ts';

// ───────────────────────────── stamps ─────────────────────────────

const DEC_TONE: Record<string, Tone> = { refund: 'good', partial: 'good', replace: 'info', repair: 'info', decline: 'neutral', escalate: 'warn' };

function decisionText(d: TicketDecision): string {
  const dec = d.decision.toLowerCase();
  return dec === 'refund' || dec === 'partial' ? `${dec === 'partial' ? 'Partial ' : 'Refund '}${money(d.refund ?? 0)}` : d.decision.toUpperCase() || 'NO DECISION';
}

function DecisionStamp({ d, ok }: { d: TicketDecision; ok?: boolean | null }) {
  const tone: Tone = ok === false ? 'bad' : (DEC_TONE[d.decision.toLowerCase()] ?? 'neutral');
  return <Stamp tone={tone} sub={`${d.priority || '—'} · reply by ${niceDate(d.sla_due) || '—'}`}>{decisionText(d)}</Stamp>;
}

// ───────────────────────────── the helpdesk ticket ─────────────────────────────

function OrderCard({ o }: { o: Order | undefined }) {
  if (!o) return null;
  return (
    <div className="cd-order">
      <div className="cd-order-head">
        <b>Order {o.id}</b>
        <span className="muted">
          placed {o.placed} · {o.paidBy}
        </span>
      </div>
      <table className="cd-order-lines">
        <tbody>
          {o.lines.map((l, i) => (
            <tr key={i}>
              <td className="muted">{l.sku}</td>
              <td>{l.name}</td>
              <td className="tnum">{l.qty}</td>
              <td className="tnum num">{l.price}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <dl className="cd-facts">
        {o.facts.map((f, i) => (
          <div key={i} className={cx(/claims/i.test(f.k) && !/none/i.test(f.v) && 'warn')}>
            <dt>{f.k}</dt>
            <dd>{f.v}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function TicketCard({ doc, t, mark, showKey }: { doc: QueueDoc; t: Ticket; mark?: TicketMark; showKey?: boolean }) {
  const k = mark?.key ?? doc.key?.[t.id.replace(/^#/, '')] ?? doc.key?.[t.id] ?? null;
  const ok = mark?.ok ? Object.values(mark.ok).every(Boolean) : null;
  const decOk = mark?.ok ? mark.ok.decision && mark.ok.refund : null;
  const rule = k && (mark ? ok === false : showKey) ? ruleFor(doc, t, k, mark?.ok ?? undefined) : null;
  return (
    <Paper className={cx('cd-ticket', ok === true && 't-good', ok === false && (decOk ? 't-warn' : 't-bad'))}>
      <div className="cd-ticket-bar">
        <b>Ticket {t.id}</b>
        <span className="cd-chan">{t.channel}</span>
        <span className="muted tnum">{t.received}</span>
        {t.previous !== '0' && <Chip tone="warn">{t.previous} earlier contacts</Chip>}
        {t.reopened && <Chip tone="warn">reopened</Chip>}
      </div>
      <div className="cd-ticket-from">
        <b>{t.from.replace(/<[^>]+>/, '').trim()}</b>
        <span className="muted"> {t.from.match(/<[^>]+>/)?.[0]}</span>
      </div>
      <div className="cd-ticket-msg">{t.body}</div>
      <OrderCard o={doc.orders[t.orderId]} />
      {(mark || showKey) && (
        <div className="cd-decide">
          {mark?.model ? (
            <div className="cd-decide-col">
              <span className="cd-k">Model</span>
              <DecisionStamp d={mark.model} ok={decOk} />
              <span className="cd-decide-cat">
                {mark.model.category || '—'}
                {mark.ok && !mark.ok.category && <Tick ok={false} />}
              </span>
            </div>
          ) : mark ? (
            <div className="cd-decide-col">
              <span className="cd-k">Model</span>
              <Stamp tone="bad">No answer</Stamp>
            </div>
          ) : null}
          {mark && <Tick ok={decOk} title={decOk ? 'Right decision and refund' : 'Wrong decision or refund'} />}
          {k && (!mark || ok === false) && (
            <div className="cd-decide-col">
              <span className="cd-k">{mark ? 'Correct' : 'The answer'}</span>
              <DecisionStamp d={k} ok={null} />
              <span className="cd-decide-cat">{k.category}</span>
            </div>
          )}
        </div>
      )}
      {mark?.ok && ok === false && k && mark.model && (
        <ul className="cd-diffs">
          {(['decision', 'refund', 'priority', 'sla_due', 'category'] as const)
            .filter((f) => !mark.ok![f])
            .map((f) => (
              <li key={f}>
                <span className="cd-k">{f === 'sla_due' ? 'reply by' : f}</span> <s>{f === 'refund' ? money(mark.model![f] ?? 0) : f === 'sla_due' ? niceDate(mark.model![f]) : show(mark.model![f])}</s> → <b>{f === 'refund' ? money(k[f] ?? 0) : f === 'sla_due' ? niceDate(k[f]) : show(k[f])}</b>
              </li>
            ))}
        </ul>
      )}
      {rule && (
        <p className="cd-rule">
          <b>Handbook {rule.no}</b> {rule.text}
        </p>
      )}
    </Paper>
  );
}

const FULL = 5;

function ticketsToShow(doc: QueueDoc, ans: QueueAnswer | null): Ticket[] {
  if (!ans) return doc.tickets.slice(0, FULL);
  const bad = new Set(ans.marks.filter((m) => !m.ok || !Object.values(m.ok).every(Boolean)).map((m) => m.id));
  const order = [...doc.tickets.filter((t) => bad.has(t.id)), ...doc.tickets.filter((t) => !bad.has(t.id))];
  return order.slice(0, FULL);
}

function QueueHeader({ doc, ans }: { doc: QueueDoc; ans?: QueueAnswer }) {
  return (
    <div className="cd-queue-head">
      <b>Helpdesk queue</b> <span className="muted">· overnight · {doc.day}</span>
      <span className="spacer" />
      <span className="tnum">{ans ? `${ans.right}/${doc.tickets.length} tickets fully right` : plural(doc.tickets.length, 'open ticket')}</span>
    </div>
  );
}

function queueWall(doc: QueueDoc, ans: QueueAnswer | null) {
  if (doc.tickets.length <= FULL) return null;
  const marks = new Map(ans?.marks.map((m) => [m.id, m]) ?? []);
  const items: MiniItem[] = doc.tickets.map((t) => {
    const m = marks.get(t.id);
    const ok = m?.ok ? Object.values(m.ok).every(Boolean) : null;
    return { id: t.id, label: t.id, tone: !ans ? 'neutral' : ok ? 'good' : m?.ok?.decision && m.ok.refund ? 'warn' : 'bad', title: `${t.id} · ${t.from.replace(/<[^>]+>/, '').trim()}` };
  });
  return <MiniWall items={items} noun="ticket" note={ans ? `All ${doc.tickets.length} tickets: green fully right, amber right decision but a wrong detail, red wrong decision` : `All ${doc.tickets.length} tickets`} render={(id) => <TicketCard doc={doc} t={doc.tickets.find((t) => t.id === id)!} mark={marks.get(id)} showKey={!ans} />} />;
}

function queueImpact(_doc: QueueDoc, ans: QueueAnswer) {
  const lines: Array<{ tone: Tone; text: ReactNode }> = [];
  let over = 0;
  let under = 0;
  for (const m of ans.marks) {
    if (!m.key || !m.model) continue;
    const paid = ['refund', 'partial'].includes(m.model.decision.toLowerCase()) ? (m.model.refund ?? 0) : 0;
    const owed = ['refund', 'partial'].includes(m.key.decision) ? (m.key.refund ?? 0) : 0;
    if (paid > owed + 0.004) over += paid - owed;
    if (owed > paid + 0.004) under += owed - paid;
    if (m.key.decision === 'escalate' && m.model.decision.toLowerCase() !== 'escalate') lines.push({ tone: 'bad', text: <>Ticket {m.id} should have gone to {m.key.priority === 'P1' ? 'the safety or fraud team (P1)' : 'a team leader'}; the model chose {decisionText(m.model).toLowerCase()}.</> });
    else if (m.ok && !m.ok.priority && m.key.priority < m.model.priority) lines.push({ tone: 'warn', text: <>Ticket {m.id} marked {m.model.priority}; it is {m.key.priority}, so the customer would wait too long for an answer.</> });
  }
  if (over > 0) lines.unshift({ tone: 'bad', text: <>Would have paid out {money(over)} more than the rules allow.</> });
  if (under > 0) lines.unshift({ tone: 'warn', text: <>Would have refunded customers {money(under)} less than they are owed.</> });
  return lines;
}

function queueSentence(doc: QueueDoc, ans: QueueAnswer): string {
  const n = doc.tickets.length;
  const base = `Got ${ans.decisionsRight} of ${n} decisions and refunds right, and ${ans.right} of ${n} tickets right in every detail (category, priority, reply-by time)`;
  const imp = queueImpact(doc, ans);
  if (ans.right === n) return `Handled all ${n} tickets exactly by the handbook: every refund to the penny, every priority and reply-by time right.`;
  return `${base}.${imp.length ? ' What that would have meant for the business is below.' : ' The slips are in priorities and reply-by times, not money.'}`;
}

const queueSpec = () =>
  docVisual<QueueDoc, QueueAnswer>({
    family: 'business',
    caption: 'The overnight helpdesk queue: each ticket with its order, the model’s decision stamped on it and, where it was wrong, the correct decision with the handbook rule that settles it.',
    doc: (input) => parseQueue(input.turns[0] ?? '', input.expected),
    answer: (input, doc) => queueAnswer(input, doc),
    sub: (doc) => `${plural(doc.tickets.length, 'ticket')} · ${plural(Object.keys(doc.orders).length, 'order')} in the lookup · handbook section 4`,
    Question: ({ doc }) => (
      <>
        <QueueHeader doc={doc} />
        {doc.tickets.slice(0, FULL).map((t) => (
          <TicketCard key={t.id} doc={doc} t={t} />
        ))}
        <MoreLine n={doc.tickets.length - FULL} noun="tickets (open any one below)" />
      </>
    ),
    QuestionExtra: ({ doc }) => queueWall(doc, null),
    Key: ({ doc }) => (
      <>
        {doc.tickets.slice(0, FULL).map((t) => (
          <TicketCard key={t.id} doc={doc} t={t} showKey />
        ))}
        <MoreLine n={doc.tickets.length - FULL} noun="tickets" />
      </>
    ),
    Answer: ({ doc, ans }) => {
      const shown = ticketsToShow(doc, ans);
      const marks = new Map(ans.marks.map((m) => [m.id, m]));
      return (
        <>
          <QueueHeader doc={doc} ans={ans} />
          {shown.map((t) => (
            <TicketCard key={t.id} doc={doc} t={t} mark={marks.get(t.id)} />
          ))}
          <MoreLine n={doc.tickets.length - shown.length} noun="tickets (wrong ones are shown first; open any one below)" />
        </>
      );
    },
    AnswerExtra: ({ doc, ans }) => queueWall(doc, ans),
    Result: ({ doc, ans }) => (
      <>
        <Tally
          items={[
            { n: ans.decisionsRight, of: doc.tickets.length, label: 'decisions and refunds right' },
            { n: ans.right, of: doc.tickets.length, label: 'tickets right in every detail' },
          ]}
        />
        <Impact lines={queueImpact(doc, ans).slice(0, 6)} />
      </>
    ),
    sentence: queueSentence,
    headline: (doc, ans) => `${ans.decisionsRight} of ${doc.tickets.length} decisions right · ${ans.right} fully right`,
    alt: {
      question: (doc) => `A helpdesk queue of ${doc.tickets.length} customer tickets with their orders, to be decided by the handbook.`,
      key: (doc) => `The correct decisions: ${Object.entries(doc.key ?? {}).slice(0, 8).map(([id, d]) => `#${id.replace(/^#/, '')} ${decisionText(d)}`).join('; ')}.`,
      answer: (doc, ans) => `The model got ${ans.decisionsRight} of ${doc.tickets.length} decisions right. ${ans.marks.filter((m) => m.ok && !m.ok.decision).map((m) => `${m.id}: ${m.model ? decisionText(m.model) : 'no answer'}, should be ${m.key ? decisionText(m.key) : '?'}`).join('; ')}`,
    },
  });

export const SUPPORT_REFUNDS = queueSpec();
export const SUPPORT_LADDER = queueSpec();

// ───────────────────────────── Tag the Ticket ─────────────────────────────

const FIELD_LABEL: Record<string, string> = { category: 'Category', sentiment: 'Sentiment', product: 'Product', order_number: 'Order', wants: 'Customer wants', urgency: 'Urgency', overcharge_owed: 'Owed back' };

function fmtTag(f: string, v: unknown): string {
  if (f === 'overcharge_owed') return typeof v === 'number' ? money(v) : show(v);
  if (f === 'wants' && typeof v === 'string') return v.replace(/_/g, ' ');
  return show(v);
}

/** The part of a rubric rule that gives the right value ("P2: overcharge_owed is £100 or more, …"), else the start of it. */
function rubricLine(rule: string, key: unknown): string {
  const k = String(key ?? '');
  const at = k ? rule.search(new RegExp(`(^|[;(]\\s*)${k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[:\\s(]`)) : -1;
  const part = at >= 0 ? rule.slice(at).replace(/^[;(]\s*/, '').split(/;\s*/)[0]! : rule;
  return part.length > 200 ? `${part.slice(0, 197)}…` : part;
}

function TagTicket({ doc }: { doc: TagDoc }) {
  return (
    <Paper className="cd-ticket">
      <div className="cd-ticket-bar">
        <b>{doc.subject}</b>
        <span className="muted">{doc.received}</span>
      </div>
      <div className="cd-ticket-from">
        <b>{doc.from}</b>
      </div>
      <div className="cd-ticket-msg">{doc.body}</div>
      {doc.quoted && <blockquote className="cd-mv-fwd">{doc.quoted}</blockquote>}
    </Paper>
  );
}

function TagPanel({ doc, marks }: { doc: TagDoc; marks: TagMark[] | null }) {
  return (
    <Paper className="cd-tags" title={marks ? 'The model’s tags' : 'Helpdesk tags'}>
      <dl>
        {TAG_FIELDS.map((f) => {
          const m = marks?.find((x) => x.field === f);
          const v = m ? m.model : doc.key?.[f];
          return (
            <div key={f} className={cx('cd-tagrow', m && (m.ok ? 't-good' : 't-bad'))}>
              <dt>{FIELD_LABEL[f]}</dt>
              <dd>
                <span className={cx('cd-tagval', f === 'urgency' && 'urg')}>{fmtTag(f, v)}</span>
                {m && !m.ok && <Fix>{fmtTag(f, m.key)}</Fix>}
                {m && !m.ok && doc.rubric[f] && <span className="cd-why">Rubric: {rubricLine(doc.rubric[f]!, m.key)}</span>}
              </dd>
              {m && <Tick ok={m.ok} />}
            </div>
          );
        })}
      </dl>
    </Paper>
  );
}

export const TICKET_TAG = docVisual<TagDoc, TagMark[]>({
  family: 'extraction',
  caption: 'The customer’s email as it reached the helpdesk, with the model’s tags beside it: each ticked or crossed against the rubric’s answer.',
  doc: (input) => parseTag(input.turns[0] ?? '', input.expected, input.notes),
  answer: (input, doc) => tagAnswer(input, doc),
  sub: (doc) => `One ticket · ${doc.received}`,
  Question: ({ doc }) => <TagTicket doc={doc} />,
  Key: ({ doc }) => <TagPanel doc={doc} marks={null} />,
  Answer: ({ doc, ans }) => (
    <div className="cd-split two">
      <TagTicket doc={doc} />
      <TagPanel doc={doc} marks={ans} />
    </div>
  ),
  Result: ({ ans }) => {
    const lines: Array<{ tone: Tone; text: ReactNode }> = [];
    const owed = ans.find((m) => m.field === 'overcharge_owed');
    const urg = ans.find((m) => m.field === 'urgency');
    if (owed && !owed.ok && typeof owed.key === 'number') {
      const got = typeof owed.model === 'number' ? owed.model : Number(owed.model) || 0;
      lines.push({ tone: 'bad', text: got < owed.key ? <>The customer is owed {money(owed.key)}; the tag says {money(got)}, so {money(owed.key - got)} would not have been paid back.</> : <>Tagged {money(got)} owed; the customer is owed {money(owed.key)}: {money(got - owed.key)} paid out that is not due.</> });
    }
    if (urg && !urg.ok) lines.push({ tone: 'warn', text: <>Urgency {show(urg.model)} instead of {show(urg.key)}: the ticket lands in the wrong place in the queue.</> });
    return (
      <>
        <Tally items={[{ n: ans.filter((m) => m.ok).length, of: ans.length, label: 'tags right' }]} />
        <Impact lines={lines} />
      </>
    );
  },
  sentence: (_doc, ans) => {
    const wrong = ans.filter((m) => !m.ok);
    if (!wrong.length) return 'Every tag right, including the money owed back and the urgency.';
    return `Got ${ans.length - wrong.length} of ${ans.length} tags right; wrong: ${wrong.map((m) => FIELD_LABEL[m.field]!.toLowerCase()).join(', ')}.`;
  },
  headline: (_doc, ans) => `${ans.filter((m) => m.ok).length} of ${ans.length} tags right`,
  alt: {
    question: (doc) => `A customer email to the helpdesk from ${doc.from}: “${doc.subject}”.`,
    key: (doc) => `Correct tags: ${TAG_FIELDS.map((f) => `${FIELD_LABEL[f]} ${fmtTag(f, doc.key?.[f])}`).join(', ')}.`,
    answer: (_doc, ans) => ans.map((m) => `${FIELD_LABEL[m.field]} ${fmtTag(m.field, m.model)}${m.ok ? ' (right)' : ` (wrong, should be ${fmtTag(m.field, m.key)})`}`).join('; '),
  },
});

// ───────────────────────────── Reply Within Policy ─────────────────────────────

const NEXT: Record<string, string> = { SEND_RETURNS_LABEL: 'send a returns label', COLLECTION: 'collect it free', NO_RETURN_NEEDED: 'no return needed', REQUEST_PHOTOS: 'ask for photos', CARRIER_INVESTIGATION: 'investigate with the carrier', TEAM_LEADER: 'pass to a team leader', NONE: 'nothing more' };
const RDEC_TONE: Record<string, Tone> = { REFUND: 'good', REPLACE: 'info', REFUSE: 'neutral', REQUEST_INFO: 'warn', INVESTIGATE: 'warn', ESCALATE: 'warn' };

function ReplyStamp({ decision, refund, next, ok }: { decision: string; refund: number | null; next: string; ok?: boolean | null }) {
  const d = decision.toUpperCase();
  return (
    <Stamp tone={ok === false ? 'bad' : (RDEC_TONE[d] ?? 'neutral')} sub={NEXT[next.toUpperCase()] ?? next}>
      {d.replace('_', ' ')}
      {refund ? ` ${money(refund)}` : ''}
    </Stamp>
  );
}

function CustomerEmail({ doc }: { doc: ReplyDoc }) {
  return (
    <>
      <Paper className="cd-mailview">
        <div className="cd-mv-subj">Email from {doc.email.from}</div>
        <div className="cd-mv-body">{doc.email.body}</div>
      </Paper>
      <Paper className="cd-order">
        <div className="cd-order-head">
          <b>Order {doc.order.id}</b>
          <span className="muted">from the order system · today is {doc.today}</span>
        </div>
        <ul className="cd-plain">
          {doc.order.lines.map((l, i) => (
            <li key={i}>{l}</li>
          ))}
        </ul>
        {doc.other && <p className="cd-other">Search also found another customer (must never be mentioned): {doc.other}</p>}
      </Paper>
    </>
  );
}

function ReplyEmail({ doc, text, to }: { doc: ReplyDoc; text: string; to: string }) {
  return (
    <Paper className="cd-mailview cd-reply">
      <div className="cd-mv-head">
        <span className="cd-k">Reply</span>
        <span className="muted">
          to {to} · re: order {doc.order.id}
        </span>
      </div>
      <div className="cd-mv-body">{text || '(no reply text)'}</div>
    </Paper>
  );
}

export const SUPPORT_REPLIES = docVisual<ReplyDoc, ReplyAnswer>({
  family: 'business',
  caption: 'The customer’s email and order, the model’s decision as a stamp beside the correct one, and its drafted reply with every house rule ticked or crossed.',
  doc: (input) => parseReply(input.turns[0] ?? '', input.expected, input.notes),
  answer: (input, doc) => replyAnswer(input, doc),
  sub: (doc) => `${doc.shop} · order ${doc.order.id} · ${doc.today}`,
  Question: ({ doc }) => <CustomerEmail doc={doc} />,
  Key: ({ doc }) =>
    doc.key ? (
      <>
        <div className="cd-decide">
          <ReplyStamp decision={doc.key.decision} refund={doc.key.refund} next={doc.key.nextStep} />
        </div>
        {doc.why && <p className="cd-rule">{doc.why}</p>}
        <ReplyEmail doc={doc} text={doc.key.reply} to={doc.email.from} />
      </>
    ) : (
      <p className="cd-empty">No key.</p>
    ),
  Answer: ({ doc, ans }) => {
    const head = ans.items.slice(0, 3);
    const decOk = head.every((i) => i.passed);
    return (
      <>
        <div className="cd-decide">
          <div className="cd-decide-col">
            <span className="cd-k">Model</span>
            <ReplyStamp decision={ans.decision} refund={ans.refund} next={ans.nextStep} ok={decOk} />
          </div>
          <Tick ok={decOk} />
          {!decOk && doc.key && (
            <div className="cd-decide-col">
              <span className="cd-k">Correct</span>
              <ReplyStamp decision={doc.key.decision} refund={doc.key.refund} next={doc.key.nextStep} />
            </div>
          )}
        </div>
        {!decOk && doc.why && (
          <p className="cd-rule">
            <b>Policy</b> {doc.why}
          </p>
        )}
        <ReplyEmail doc={doc} text={ans.reply} to={doc.email.from} />
        <Paper className="cd-checks" title="Policy and house-rule checks">
          <ul>
            {ans.items.map((it, i) => (
              <li key={i} className={it.passed ? 't-good' : 't-bad'}>
                <Tick ok={it.passed} />
                <span>
                  {it.label}
                  {it.weight && it.weight > 1 ? <small className="muted"> ({it.weight} points)</small> : null}
                  {!it.passed && it.detail && <small className="cd-why"> — {it.detail}</small>}
                </span>
              </li>
            ))}
          </ul>
        </Paper>
      </>
    );
  },
  Result: ({ doc, ans }) => {
    const lines: Array<{ tone: Tone; text: ReactNode }> = [];
    if (doc.key && ans.decision.toUpperCase() !== doc.key.decision) lines.push({ tone: 'bad', text: <>Told the customer “{ans.decision.toLowerCase().replace('_', ' ')}”; the policy says {doc.key.decision.toLowerCase().replace('_', ' ')}.</> });
    if (doc.key && (ans.refund ?? 0) > doc.key.refund + 0.004) lines.push({ tone: 'bad', text: <>Promised {money(ans.refund)}; the policy pays {money(doc.key.refund)} — {money((ans.refund ?? 0) - doc.key.refund)} too much.</> });
    if (doc.key && (ans.refund ?? 0) < doc.key.refund - 0.004) lines.push({ tone: 'warn', text: <>Promised {money(ans.refund ?? 0)}; the customer is owed {money(doc.key.refund)}.</> });
    for (const it of ans.items.slice(3).filter((i) => !i.passed && !/only counted when/.test(i.detail ?? ''))) lines.push({ tone: 'warn', text: <>Broke a house rule: {it.label}{it.detail ? ` (${it.detail})` : ''}.</> });
    return (
      <>
        <Tally items={[{ n: ans.items.filter((i) => i.passed).length, of: ans.items.length, label: 'checks passed' }]} />
        <Impact lines={lines.slice(0, 5)} />
      </>
    );
  },
  sentence: (doc, ans) => {
    const head = ans.items.slice(0, 3).every((i) => i.passed);
    const fails = ans.items.filter((i) => !i.passed).length;
    if (head && !fails) return 'Right decision, right money, and a reply that keeps every house rule: this one could be sent as it is.';
    if (head) return `Right decision and money, but the reply breaks ${plural(fails, 'house rule')}: it would need editing before it is sent.`;
    return `The wrong outcome for the customer (${ans.decision || 'no decision'} instead of ${doc.key?.decision ?? '?'}): sending this reply would break the returns policy.`;
  },
  headline: (_doc, ans) => `${ans.items.filter((i) => i.passed).length} of ${ans.items.length} checks passed`,
  alt: {
    question: (doc) => `A customer email from ${doc.email.from} about order ${doc.order.id}: “${doc.email.body.slice(0, 200)}”.`,
    key: (doc) => `Correct: ${doc.key?.decision}, refund ${money(doc.key?.refund ?? 0)}, next step ${doc.key?.nextStep}.`,
    answer: (_doc, ans) => `The model decided ${ans.decision}, refund ${money(ans.refund ?? 0)}, next step ${ans.nextStep}. Checks: ${ans.items.map((i) => `${i.label} ${i.passed ? 'passed' : 'failed'}`).join('; ')}.`,
  },
});
