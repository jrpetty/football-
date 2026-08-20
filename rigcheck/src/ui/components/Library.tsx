import { useState } from 'react';
import { deleteBuild, loadLibrary, saveBuild, type SavedBuild } from '../library.ts';
import { exportJson } from '../export.ts';
import { useApp } from '../store.ts';
import type { Build } from '../../core/types.ts';

export function BuildLibrary({ current, onLoad }: { current: Build; onLoad: (b: Build) => void }) {
  const { data } = useApp();
  const [items, setItems] = useState<SavedBuild[]>(() => loadLibrary());
  const [name, setName] = useState('');
  const [customer, setCustomer] = useState('');

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
            <div key={it.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '5px 0', borderBottom: '1px solid var(--line)' }}>
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
              <button className="btn danger" onClick={() => setItems(deleteBuild(it.id))}>×</button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
