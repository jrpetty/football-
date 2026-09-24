/** Viewer Challenge — import viewer questions, review them, and write a private test. */
import { useMemo, useState } from 'react';
import { useAsync } from '../hooks.ts';
import { Link, setQuery, useRoute } from '../router.tsx';
import { useMeta, useToast, useViewerCaption } from '../context.tsx';
import { Callout, Card, ConfirmDialog, Empty, ErrorState, Field, PageHead, SkeletonRows, Switch, Tabs, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtDate } from '../format.ts';
import { channelApi, type ChallengeItem, type ChallengePatch, type ChallengeQueue, type ChallengeWriteResult } from './channelApi.ts';
import './channel.css';

type Filter = 'pending' | 'approved' | 'rejected' | 'all';

const SAMPLE = `question,answer,answer_type,name,handle,notes
"How many r's are in the word ""strawberry""? Give a number.",3,number,Ada,@ada,Counted twice`;

function ItemCard({ item, season, onQueue }: { item: ChallengeItem; season: string; onQueue: (q: ChallengeQueue) => void }) {
  const toast = useToast();
  const [edit, setEdit] = useState<ChallengePatch>({});
  const [busy, setBusy] = useState(false);
  const v = { ...item, ...edit };
  const dirty = Object.keys(edit).length > 0;
  const act = async (patch: ChallengePatch, ok?: string) => {
    setBusy(true);
    try {
      onQueue(await channelApi.updateChallenge(season, item.id, { ...edit, ...patch }));
      setEdit({});
      if (ok) toast.success(ok);
    } catch (e) {
      toast.error(e, 'Not saved');
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    setBusy(true);
    try {
      onQueue(await channelApi.deleteChallenge(season, item.id));
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };
  const errors = item.issues.filter((i) => i.level === 'error');
  return (
    <article className={cx('card ch-item', item.status)}>
      <header className="ch-item-head">
        <span className={cx('badge', item.status === 'approved' ? 'good' : item.status === 'rejected' ? '' : 'info')}>{item.status}</span>
        <strong className="ch-credit">{item.viewerHandle || item.viewerName || 'anonymous'}</strong>
        {item.viewerName && item.viewerHandle && <span className="muted">{item.viewerName}</span>}
        <span className="muted small">submitted {fmtDate(item.submittedAt)}</span>
        {item.caseId && <span className="badge outline mono">case {item.caseId}</span>}
        <span className="spacer" />
        <span className="muted small mono">#{item.id}</span>
      </header>
      <div className="ch-item-body">
        <Field label="Question (exactly what every model will see)">
          <textarea className="textarea" rows={Math.min(8, Math.max(2, v.question.split('\n').length + 1))} value={v.question} onChange={(e) => setEdit({ ...edit, question: e.target.value })} />
        </Field>
        <div className="ch-item-grid">
          <Field label="Answer type">
            <select className="select" value={v.answerType} onChange={(e) => setEdit({ ...edit, answerType: e.target.value as ChallengeItem['answerType'] })}>
              <option value="exact">exact word / phrase</option>
              <option value="number">number</option>
              <option value="choice">multiple choice (letter)</option>
            </select>
          </Field>
          <Field label="Answer key (never shown to models)" error={errors.find((i) => i.code === 'missing-answer' || i.code === 'bad-number' || i.code === 'bad-choice')?.message}>
            <input className="input mono" value={v.answer} onChange={(e) => setEdit({ ...edit, answer: e.target.value })} />
          </Field>
          {v.answerType === 'exact' && (
            <Field label="Also accept" hint="Other spellings, separated by |">
              <input className="input" value={(v.alternatives ?? []).join(' | ')} onChange={(e) => setEdit({ ...edit, alternatives: e.target.value.split('|').map((s) => s.trim()).filter(Boolean) })} />
            </Field>
          )}
          <Field label="Credit as">
            <input className="input" value={v.viewerHandle} placeholder="@handle" onChange={(e) => setEdit({ ...edit, viewerHandle: e.target.value })} />
          </Field>
          <div className="field">
            <span className="label">Show credit on screen</span>
            <span className="row" style={{ gap: 8, minHeight: 36 }}>
              <Switch checked={v.credit} onChange={(b) => setEdit({ ...edit, credit: b })} label="Show credit on screen" />
              <span className="muted small">{v.credit ? 'yes' : 'anonymous'}</span>
            </span>
          </div>
        </div>
        {v.notes && <p className="ch-notes"><Icon.Info /> {v.notes}</p>}
        {item.issues.length > 0 && (
          <ul className="ch-issues">
            {item.issues.map((i, k) => (
              <li key={k} className={i.level}>
                {i.level === 'error' ? <Icon.X /> : <Icon.Alert />} {i.message}
              </li>
            ))}
          </ul>
        )}
      </div>
      <footer className="ch-item-foot">
        {dirty && (
          <button className="btn sm" disabled={busy} onClick={() => void act({}, 'Saved')}>
            Save edits
          </button>
        )}
        <span className="spacer" />
        <button className="btn sm ghost" disabled={busy} onClick={() => void remove()} title="Delete this submission">
          <Icon.Trash />
        </button>
        {item.status !== 'rejected' && (
          <button className="btn sm" disabled={busy} onClick={() => void act({ status: 'rejected' })}>
            <Icon.X /> Reject
          </button>
        )}
        {item.status !== 'pending' && (
          <button className="btn sm" disabled={busy} onClick={() => void act({ status: 'pending' })}>
            Back to pending
          </button>
        )}
        {item.status !== 'approved' && (
          <button className="btn sm primary" disabled={busy} onClick={() => void act({ status: 'approved' }, 'Approved')}>
            <Icon.Check /> Approve
          </button>
        )}
      </footer>
    </article>
  );
}

export default function ChallengePage() {
  const { query } = useRoute();
  const { categories } = useMeta();
  const toast = useToast();
  const seasonParam = query.get('season') || undefined;
  const state = useAsync(() => channelApi.challenge(seasonParam), [seasonParam]);
  const [filter, setFilter] = useState<Filter>('pending');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [category, setCategory] = useState('');
  const [written, setWritten] = useState<ChallengeWriteResult | null>(null);
  const [confirmWrite, setConfirmWrite] = useState(false);
  const q = state.data?.queue;
  const counts = useMemo(() => {
    const items = q?.items ?? [];
    return { pending: items.filter((i) => i.status === 'pending').length, approved: items.filter((i) => i.status === 'approved').length, rejected: items.filter((i) => i.status === 'rejected').length, flagged: items.filter((i) => i.status === 'pending' && i.issues.length).length, all: items.length };
  }, [q]);
  useViewerCaption('Questions sent in by viewers: each one is checked for a clear answer, duplicates and give-aways before it joins a private test that no AI has ever seen.', q ? `${counts.approved} approved · ${counts.pending} waiting for review` : undefined);

  if (state.error) return <div className="page"><ErrorState error={state.error} onRetry={state.reload} /></div>;
  if (!q) return <div className="page"><SkeletonRows rows={10} /></div>;
  const season = q.season;
  const setQueue = (next: ChallengeQueue) => state.setData((d) => ({ seasons: d?.seasons ?? [], queue: next }));
  const shown = q.items.filter((i) => filter === 'all' || i.status === filter);

  const doImport = async () => {
    setBusy(true);
    try {
      const r = await channelApi.importChallenge(season, text);
      setQueue(r.queue);
      setText('');
      setFilter('pending');
      toast.success(`${r.added} added, ${r.skipped} skipped`, 'Imported');
      for (const e of r.errors.slice(0, 3)) toast.info(e);
    } catch (e) {
      toast.error(e, 'Import failed');
    } finally {
      setBusy(false);
    }
  };
  const readFile = (f: File | undefined) => {
    if (!f) return;
    void f.text().then(setText);
  };
  const write = async () => {
    setConfirmWrite(false);
    setBusy(true);
    try {
      const r = await channelApi.writeChallenge(season, category || undefined);
      setWritten(r);
      if (r.errors.length) toast.error(r.errors.join(' '), 'Not written');
      else toast.success(`${r.cases} questions in ${r.file}`, 'Private test written');
      state.reload();
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page ch-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Inbox style={{ width: 14, height: 14 }} /> Channel
          </span>
        }
        title="Viewer Challenge"
        sub="Your viewers write the questions. You check them. The approved ones become a private test no AI has ever seen."
        actions={
          <>
            <Link to={`/slides/challenge?season=${encodeURIComponent(season)}`} className="btn">
              <Icon.Present /> Present
            </Link>
            <button className="btn primary" disabled={busy || counts.approved === 0} onClick={() => setConfirmWrite(true)}>
              <Icon.Lock /> Write private test ({counts.approved})
            </button>
          </>
        }
      />

      <div className="stats">
        <div className="stat">
          <span className="k">Season</span>
          <span className="v mono">{season}</span>
          <span className="s">
            <select className="select sm" value={season} onChange={(e) => setQuery({ season: e.target.value })} aria-label="Season">
              {[...new Set([season, ...(state.data?.seasons ?? [])])].map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </span>
        </div>
        <div className="stat">
          <span className="k">Waiting for review</span>
          <span className="v">{counts.pending}</span>
          <span className="s">{counts.flagged} flagged by the checks</span>
        </div>
        <div className="stat leader-stat" style={{ ['--c' as string]: 'var(--good)' }}>
          <span className="k">Approved</span>
          <span className="v">{counts.approved}</span>
          <span className="s">{q.writtenVersion ? `test v${q.writtenVersion} written ${fmtDate(q.writtenAt)}` : 'not written yet'}</span>
        </div>
        <div className="stat">
          <span className="k">Rejected</span>
          <span className="v">{counts.rejected}</span>
          <span className="s">kept for reference</span>
        </div>
      </div>

      {written && !written.errors.length && (
        <Callout tone="info" icon={<Icon.Lock />}>
          <strong>Private test written:</strong> <code>{written.file}</code> ({written.testId} v{written.version}, {written.cases} questions). It lives in the git-ignored <code>tests/private/</code> folder and is never published. Run it from <Link to={`/run/new`}>New Run</Link> → pick the test.
        </Callout>
      )}

      <div className="grid split-8-4" style={{ alignItems: 'start' }}>
        <div className="stack">
          <Tabs
            value={filter}
            onChange={setFilter}
            tabs={[
              { id: 'pending', label: 'To review', count: counts.pending },
              { id: 'approved', label: 'Approved', count: counts.approved },
              { id: 'rejected', label: 'Rejected', count: counts.rejected },
              { id: 'all', label: 'All', count: counts.all },
            ]}
          />
          {shown.length === 0 ? (
            <div className="card">
              <Empty icon={<Icon.Inbox />} title={filter === 'pending' ? 'Nothing waiting for review' : 'Nothing here'}>
                Import a Google Forms export (CSV) or paste submissions on the right.
              </Empty>
            </div>
          ) : (
            shown.map((i) => <ItemCard key={`${i.id}-${i.status}`} item={i} season={season} onQueue={setQueue} />)
          )}
        </div>
        <div className="stack no-broadcast">
          <Card title="Import submissions" desc="A CSV exported from Google Forms (Responses → Download), a JSON list, or rows pasted by hand.">
            <div className="stack">
              <textarea className="textarea mono" rows={7} value={text} placeholder={SAMPLE} onChange={(e) => setText(e.target.value)} aria-label="Submissions to import" />
              <div className="row wrap" style={{ gap: 8 }}>
                <label className="btn sm">
                  <Icon.Upload /> Choose file…
                  <input type="file" accept=".csv,.json,.txt,text/csv,application/json" hidden onChange={(e) => readFile(e.target.files?.[0])} />
                </label>
                <span className="spacer" />
                <button className="btn sm primary" disabled={busy || !text.trim()} onClick={() => void doImport()}>
                  Import
                </button>
              </div>
              <p className="muted small">Columns are matched by name: question, answer, answer type (exact / number / choice), name, handle, notes. Re-importing the same export skips what you already have.</p>
            </div>
          </Card>
          <Card title="Automatic checks" desc="Every submission is checked for:">
            <ul className="ch-list small">
              <li>a missing or malformed answer key (numbers, A–H letters)</li>
              <li>answers pasted into the question box, or that give themselves away</li>
              <li>too long / too short questions</li>
              <li>duplicates and near-duplicates of other submissions and of your existing tests</li>
            </ul>
          </Card>
          <Card title="Written as" desc="A normal prompt test; credits go in each case’s notes.">
            <Field label="Category">
              <select className="select" value={category || q.testId.split('.')[0]} onChange={(e) => setCategory(e.target.value)}>
                {categories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </Field>
            <p className="muted small" style={{ marginTop: 8 }}>
              <code>{q.testFile}</code>
            </p>
          </Card>
        </div>
      </div>

      <ConfirmDialog
        open={confirmWrite}
        title="Write the private test?"
        confirmLabel="Write test"
        onCancel={() => setConfirmWrite(false)}
        onConfirm={() => void write()}
        busy={busy}
        body={
          <p>
            {counts.approved} approved question{counts.approved === 1 ? '' : 's'} will be saved to <code>{q.testFile}</code>. If the test already exists and anything changed, its version goes up, so old results stop counting (as with any edited test). Nothing is run and nothing is spent.
          </p>
        }
      />
    </div>
  );
}
