/**
 * Clean the Customer List drawn as customer cards: each "same person" group with the fields that link its records
 * highlighted in the same colour, the model's merges against the key (a wrong merge is flagged "two different
 * people merged", a missed one "duplicate left in"), and the surviving golden record as a clean card.
 */
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { Chip, Impact, MiniWall, MoreLine, Paper, Tally, Tick, docVisual, type MiniItem, type Tone } from './parts.tsx';
import { normSetId, plural, show } from './kit.ts';
import { LINK_FIELDS, apartReason, crmAnswer, normField, parseCrm, sharedFields, type CrmAnswer, type CrmDoc, type CrmRecord, type Group } from './crm.ts';
import './crm.css';

const HUES = [190, 40, 300, 140, 0, 250];
const LABEL: Record<string, string> = { email: 'email', phone: 'phone', dob: 'birthday', postcode: 'postcode', address: 'address', last_name: 'surname' };

function RecordCard({ r, links, master }: { r: CrmRecord; links: Map<string, number>; master?: boolean }) {
  const f = (col: string, text: ReactNode) => {
    const v = normField(col, r[col] ?? '');
    const idx = v ? links.get(`${col}|${v}`) : undefined;
    if (!r[col]) return null;
    return (
      <span className={cx('cd-rf', idx !== undefined && 'link')} style={idx !== undefined ? ({ ['--h' as string]: HUES[idx % HUES.length] } as CSSProperties) : undefined} title={idx !== undefined ? `same ${LABEL[col] ?? col} as another record here` : undefined}>
        {text}
      </span>
    );
  };
  const gone = /gone away/i.test(r.status ?? '');
  return (
    <div className={cx('cd-rec', master && 'master', gone && 'gone')}>
      <div className="cd-rec-top">
        <span className={cx('cd-src', r.id.startsWith('M') ? 'm' : 'b')}>{r.id.startsWith('M') ? 'MemberHub' : 'BookIt'}</span>
        <b>
          {r.first_name} {f('last_name', r.last_name)}
        </b>
        <span className="muted">{r.id}</span>
        {master && <Chip tone="good">survives</Chip>}
        {gone && <Chip tone="warn">gone away</Chip>}
      </div>
      <div className="cd-rec-fields">
        {f('dob', <>born {r.dob}</>)}
        {f('email', r.email)}
        {f('phone', r.phone)}
        {f('postcode', r.postcode)}
        {f('address', r.address)}
        {r.employer && <span className="cd-rf">{r.employer}</span>}
        <span className="cd-rf muted">active {r.last_activity}</span>
      </div>
      {r.notes && <div className="cd-rec-note">“{r.notes}”</div>}
    </div>
  );
}

function linkedBy(links: Map<string, number>): string {
  const cols = [...new Set([...links.keys()].map((k) => k.split('|')[0]!))];
  return cols.length ? `linked by the same ${cols.map((c) => LABEL[c] ?? c).join(', ')}` : 'no field in common';
}

function GoldenCard({ g, ok, title }: { g: Record<string, unknown>; ok?: Record<string, boolean> | null; title: string }) {
  return (
    <div className="cd-golden">
      <span className="cd-k">{title}</span>
      {Object.entries(g).map(([k, v]) => (
        <span key={k} className={cx('cd-gf', ok && (ok[k] ? 'ok' : 'no'))}>
          <span className="muted">{k}</span> {show(v)}
          {ok && <Tick ok={ok[k] ?? false} />}
        </span>
      ))}
    </div>
  );
}

const KIND: Record<Group['kind'], { label: string; tone: Tone }> = {
  right: { label: 'Same person, merged correctly', tone: 'good' },
  'merged-wrong': { label: 'Two different people merged', tone: 'bad' },
  'left-in': { label: 'Duplicate left in', tone: 'bad' },
  split: { label: 'Only partly merged: a duplicate left in', tone: 'warn' },
  'key-only': { label: 'Same person', tone: 'neutral' },
};

function GroupBox({ doc, g }: { doc: CrmDoc; g: Group }) {
  const recs = g.ids.map((id) => doc.byId.get(normSetId(id))).filter((r): r is CrmRecord => !!r);
  const links = sharedFields(recs);
  const k = KIND[g.kind];
  const why = g.kind === 'merged-wrong' ? apartReason(doc, g.ids) : null;
  const goldOk = g.goldenOk && Object.values(g.goldenOk).every(Boolean);
  return (
    <div className={cx('cd-group', `t-${k.tone}`)}>
      <div className="cd-group-head">
        <Tick ok={g.kind === 'right'} />
        <b>{k.label}</b>
        <span className="muted">· {linkedBy(links)}</span>
      </div>
      {g.people ? (
        <div className="cd-people">
          {g.people.map((p, i) => (
            <div key={i} className="cd-person">
              <span className="cd-k">{g.people!.length > 1 ? `Person ${i + 1}` : ''}</span>
              {p.map((id) => {
                const r = doc.byId.get(normSetId(id));
                return r ? <RecordCard key={id} r={r} links={links} /> : null;
              })}
            </div>
          ))}
        </div>
      ) : (
        recs.map((r) => <RecordCard key={r.id} r={r} links={links} master={!!g.master && normSetId(g.master) === normSetId(r.id)} />)
      )}
      {g.kind === 'split' && <p className="cd-why">The model’s groups: {g.model.map((m) => m.join(' + ')).join(' · ')}</p>}
      {g.kind === 'left-in' && <p className="cd-why">The model left these {recs.length} records apart: the new CRM would keep {recs.length} copies of one customer.</p>}
      {why && <p className="cd-why">{why}</p>}
      {g.golden && (
        <div className="cd-golds">
          {g.modelGolden && g.kind === 'right' && <GoldenCard g={g.modelGolden} ok={g.goldenOk} title={`Model’s golden record (${g.modelMaster})`} />}
          {(!goldOk || g.kind !== 'right') && <GoldenCard g={g.golden} title={`Correct golden record (${g.master})`} />}
        </div>
      )}
    </div>
  );
}

const SHOW = 6;

function ExportTable({ doc }: { doc: CrmDoc }) {
  const cols = ['id', 'first_name', 'last_name', 'dob', 'email', 'phone', 'postcode', 'last_activity'];
  return (
    <Paper className="cd-export">
      <div className="cd-paper-title">
        Customer export · {plural(doc.records.length, 'record')} from MemberHub and BookIt
      </div>
      <div className="cd-scroll">
        <table className="cd-grid">
          <thead>
            <tr>
              {cols.map((c) => (
                <th key={c}>{c.replace('_', ' ')}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {doc.records.slice(0, 16).map((r) => (
              <tr key={r.id}>
                {cols.map((c) => (
                  <td key={c}>{r[c]}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <MoreLine n={doc.records.length - 16} noun="records" />
    </Paper>
  );
}

function wall(doc: CrmDoc, ans: CrmAnswer) {
  if (ans.groups.length <= SHOW) return null;
  const items: MiniItem[] = ans.groups.map((g, i) => ({ id: String(i), label: g.ids[0] ?? '?', tone: g.kind === 'right' ? (g.goldenOk && !Object.values(g.goldenOk).every(Boolean) ? 'warn' : 'good') : g.kind === 'split' ? 'warn' : 'bad', title: `${KIND[g.kind].label}: ${g.ids.join(' + ')}` }));
  return <MiniWall items={items} noun="group" note={`All ${ans.groups.length} groups: green right, amber partly, red wrong`} render={(id) => <GroupBox doc={doc} g={ans.groups[Number(id)]!} />} />;
}

const crmSpec = () =>
  docVisual<CrmDoc, CrmAnswer>({
    family: 'business',
    caption: 'Customer cards grouped by person, matching fields in the same colour: the model’s merges against the key — a wrong merge (two people as one) and a duplicate left in are flagged — with the golden record each group keeps.',
    doc: (input) => parseCrm(input.turns[0] ?? '', input.expected),
    answer: (input, doc) => crmAnswer(input, doc),
    sub: (doc) => `${plural(doc.records.length, 'customer record')} from two systems${doc.key ? ` · ${plural(doc.key.clusters.length, 'real duplicate group')}` : ''}`,
    Question: ({ doc }) => <ExportTable doc={doc} />,
    Key: ({ doc }) => (
      <>
        {(doc.key?.clusters ?? []).slice(0, SHOW).map((ids, i) => {
          const master = Object.keys(doc.key!.golden).find((k) => ids.map(normSetId).includes(normSetId(k))) ?? null;
          return <GroupBox key={i} doc={doc} g={{ ids, kind: 'key-only', model: [], master, modelMaster: null, golden: master ? doc.key!.golden[master]! : null, modelGolden: null, goldenOk: null }} />;
        })}
        <MoreLine n={(doc.key?.clusters.length ?? 0) - SHOW} noun="groups" />
      </>
    ),
    Answer: ({ doc, ans }) => (
      <>
        {ans.groups.slice(0, SHOW).map((g, i) => (
          <GroupBox key={i} doc={doc} g={g} />
        ))}
        <MoreLine n={ans.groups.length - SHOW} noun="groups (problems are shown first; open any one below)" />
      </>
    ),
    AnswerExtra: ({ doc, ans }) => wall(doc, ans),
    Result: ({ ans }) => {
      const lines: Array<{ tone: Tone; text: ReactNode }> = [];
      if (ans.wrongMerges) lines.push({ tone: 'bad', text: <>{plural(ans.wrongMerges, 'wrong merge')}: different customers fused into one record — their bookings, payments and contact details mixed up.</> });
      if (ans.leftIn) lines.push({ tone: 'warn', text: <>{plural(ans.leftIn, 'duplicate')} left in: the same customer twice in the new CRM, getting every mailing twice.</> });
      const goldWrong = ans.groups.filter((g) => g.kind === 'right' && g.goldenOk && !Object.values(g.goldenOk).every(Boolean)).length;
      if (goldWrong) lines.push({ tone: 'warn', text: <>{plural(goldWrong, 'golden record')} kept an out-of-date email or phone, or the wrong surviving record.</> });
      return (
        <>
          <Tally
            items={[
              { n: ans.rightGroups, of: ans.keyGroups, label: 'duplicate groups merged exactly' },
              { n: ans.goldenRight, of: ans.keyGroups, label: 'golden records right' },
            ]}
          />
          <Impact lines={lines} />
        </>
      );
    },
    sentence: (_doc, ans) => {
      if (!ans.wrongMerges && !ans.leftIn && ans.goldenRight === ans.keyGroups) return `Merged all ${ans.keyGroups} duplicate groups exactly and kept the right details for each: the customer list is clean.`;
      return `Merged ${ans.rightGroups} of ${ans.keyGroups} duplicate groups exactly${ans.wrongMerges ? `, but fused ${plural(ans.wrongMerges, 'pair')} of different people` : ''}${ans.leftIn ? `${ans.wrongMerges ? ' and' : ', but'} left ${plural(ans.leftIn, 'duplicate')} in` : ''}.`;
    },
    headline: (_doc, ans) => `${ans.rightGroups} of ${ans.keyGroups} groups right · ${plural(ans.wrongMerges, "wrong merge")} · ${plural(ans.leftIn, "duplicate")} left in`,
    alt: {
      question: (doc) => `A customer export of ${doc.records.length} records from two systems, to be merged into one record per customer.`,
      key: (doc) => `The real duplicate groups: ${(doc.key?.clusters ?? []).slice(0, 10).map((g) => g.join(' + ')).join('; ')}.`,
      answer: (_doc, ans) => ans.groups.slice(0, 12).map((g) => `${g.ids.join(' + ')}: ${KIND[g.kind].label}`).join('; '),
    },
  });

export const CRM_DEDUPE = crmSpec();
export const CRM_LADDER = crmSpec();

export { LINK_FIELDS };
