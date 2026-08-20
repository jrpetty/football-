import { useState } from 'react';
import { deleteBuild, loadLibrary, restoreRevision, saveBuild, saveRevision, type SavedBuild } from '../library.ts';
import { exportJson } from '../export.ts';
import { useApp } from '../store.ts';
import { diffBuilds, summariseDiff } from '../../core/builddiff.ts';
import type { Build } from '../../core/types.ts';

export function BuildLibrary({ current, onLoad }: { current: Build; onLoad: (b: Build) => void }) {
  const { data } = useApp();
  const [items, setItems] = useState<SavedBuild[]>(() => loadLibrary());
  const [name, setName] = useState('');
  const [customer, setCustomer] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const [revNote, setRevNote] = useState('');

  const nameOf = (id: string) =>
    data.cpus.get(id)?.fullName ?? data.gpus.get(id)?.fullName ?? id;

  return (
    <div className="panel">
      <div className="panel-head">
        <span>saved builds — {items.length}</span>
        <span className="spacer" />
        {items.length > 0 && (
          <button className="btn" onClick={() => exportJson('rigcheck-library.json', items)}>export</button>
        )}
      </div>
      <div className="panel-body">
        <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
          <input type="text" placeholder="build name" value={name} onChange={(e) => setName(e.target.value)} />
          <input type="text" placeholder="customer (optional)" value={customer} onChange={(e) => setCustomer(e.target.value)} />
          <button
            className="btn primary"
            disabled={!name.trim()}
            onClick={() => {
              setItems(saveBuild(current, { name: name.trim(), customer: customer.trim() || undefined }));
              setName('');
              setCustomer('');
            }}
          >
            save
          </button>
        </div>
        {items.length === 0 && <div className="mini">Nothing saved yet. Saved builds persist in this browser and can be exported.</div>}
        <div className="scroll-y">
          {items.map((it) => (
            <div key={it.id}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '5px 0', borderBottom: '1px solid var(--line)' }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12 }}>
                  {it.name}
                  {it.customer && <span className="mini" style={{ marginLeft: 6 }}>· {it.customer}</span>}
                </div>
                <div className="mini mono">
                  {data.cpus.get(it.build.cpuId)?.brand ?? it.build.cpuId} + {data.gpus.get(it.build.gpuId)?.brand ?? it.build.gpuId}
                </div>
              </div>
              <button className="btn" onClick={() => onLoad(it.build)}>load</button>
              <button
                className="btn"
                title="Save the build you are looking at into this slot, keeping the current one as a revision"
                onClick={() => { setItems(saveRevision(it.id, current, revNote.trim() || undefined)); setRevNote(''); }}
              >
                save here
              </button>
              <button
                className="btn"
                aria-expanded={open === it.id}
                onClick={() => setOpen(open === it.id ? null : it.id)}
              >
                {(it.revisions?.length ?? 0) > 0 ? `${it.revisions!.length} rev` : 'diff'}
              </button>
              <button className="btn danger" aria-label={`Delete ${it.name}`} onClick={() => setItems(deleteBuild(it.id))}>×</button>
            </div>
            {open === it.id && <SlotDetail slot={it} current={current} nameOf={nameOf} onRestore={(at) => setItems(restoreRevision(it.id, at))} />}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}


/**
 * A slot opened up: how it differs from what is on screen, and what it used to
 * be.
 *
 * The diff against the CURRENT build is the useful half. Someone opens a saved
 * build to ask "what did I change since this", and answering that with two
 * parts lists side by side makes them do the comparison themselves.
 */
function SlotDetail({
  slot,
  current,
  nameOf,
  onRestore,
}: {
  slot: SavedBuild;
  current: Build;
  nameOf: (id: string) => string;
  onRestore: (savedAt: string) => void;
}) {
  const changes = diffBuilds(slot.build, current, nameOf);
  const real = changes.filter((c) => c.weight !== 'cosmetic');
  return (
    <div style={{ padding: '8px 0 10px', borderBottom: '1px solid var(--line)' }}>
      <div className="mini" style={{ marginBottom: 4 }}>versus the build on screen</div>
      {real.length === 0 ? (
        <div className="mini" style={{ color: 'var(--measured)' }}>Identical — nothing has changed since this was saved.</div>
      ) : (
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th scope="col">field</th><th scope="col">saved</th><th scope="col">on screen</th></tr></thead>
            <tbody>
              {real.map((c) => (
                <tr key={c.field}>
                  <td className="sub">{c.label}</td>
                  <td className="mono" style={{ fontSize: 11 }}>{c.from}</td>
                  <td className="mono" style={{ fontSize: 11, color: c.weight === 'major' ? 'var(--ink)' : undefined }}>{c.to}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {(slot.revisions?.length ?? 0) > 0 && (
        <>
          <div className="mini" style={{ margin: '10px 0 4px' }}>earlier versions of this slot</div>
          {slot.revisions!.map((r) => (
            <div key={r.savedAt} style={{ display: 'flex', alignItems: 'baseline', gap: 8, padding: '3px 0' }}>
              <span className="mini mono">{r.savedAt.slice(0, 10)}</span>
              <span className="mini" style={{ flex: 1, minWidth: 0 }}>
                {r.note ? r.note : summariseDiff(diffBuilds(r.build, slot.build, nameOf))}
              </span>
              <button className="btn" onClick={() => onRestore(r.savedAt)}>restore</button>
            </div>
          ))}
        </>
      )}
    </div>
  );
}
