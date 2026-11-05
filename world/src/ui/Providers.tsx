import { useState } from 'react'
import { mind } from '../ai/mind.ts'
import { VENDORS, newProfile, vendorOf, listModels, testProfile, PROXY_URL, type MindProfile } from '../ai/providers.ts'
import { MODELS } from '../ai/client.ts'
import { useMindVersion } from './common.tsx'

/** Pick the default mind, and add/edit/test minds from other providers. */
export function ProvidersSection() {
  useMindVersion()
  const s = mind.settings
  const [editing, setEditing] = useState<string | null>(null)
  const custom = s.profiles ?? []
  const save = (list: MindProfile[]) => mind.update({ profiles: list })
  const add = (vid: string) => {
    const v = VENDORS.find((x) => x.id === vid)!
    const p = newProfile(v)
    save([...custom, p])
    setEditing(p.id)
  }
  return (
    <>
      <div className="field">
        <label>Default mind</label>
        <div className="choices">
          {mind.profiles.map((p) => {
            const m = MODELS.find((x) => x.id === p.model)
            const v = vendorOf(p)
            const native = p.kind === 'native'
            const usable = mind.usable(p.id)
            return (
              <button key={p.id} className={`choice ${s.model === p.id ? 'on' : ''}`} onClick={() => mind.update({ model: p.id })} style={{ opacity: usable ? 1 : 0.6 }}>
                <div className="t">
                  <span>{native ? '🌱' : p.kind === 'anthropic' ? '✦' : v?.flag} {p.label}</span>
                  <span className="meta">{native ? 'built in · free' : p.priceIn === 0 && p.priceOut === 0 ? 'free' : `$${p.priceIn}/$${p.priceOut}`}</span>
                </div>
                <div className="d">{native ? 'Our own AI. Plans, talks, leads, writes and wages war on its own — no key, no network, and it can think for every person at once.' : m ? m.blurb : `${v?.label ?? 'Custom'} · ${p.model}${p.useProxy ? ' · via proxy' : ''}`}{!usable && ' — needs a key'}</div>
              </button>
            )
          })}
        </div>
        <div className="hint">Prices are $ per million input/output tokens. Local models cost nothing but your electricity.</div>
      </div>

      <div className="field">
        <label>Add a mind from another provider</label>
        <div className="choices">
          {VENDORS.map((v) => (
            <button key={v.id} className="choice" onClick={() => add(v.id)}>
              <div className="t"><span>{v.flag} {v.label}</span><span className="meta">{v.local ? 'local' : '+ add'}</span></div>
              <div className="d">{v.note}</div>
            </button>
          ))}
        </div>
      </div>

      {custom.map((p) => (
        <ProfileEditor key={p.id} p={p} open={editing === p.id} onToggle={() => setEditing(editing === p.id ? null : p.id)}
          onChange={(np) => save(custom.map((x) => (x.id === p.id ? np : x)))}
          onDelete={() => { save(custom.filter((x) => x.id !== p.id)); if (s.model === p.id) mind.update({ model: MODELS[0].id }) }} />
      ))}

      {custom.some((p) => p.useProxy) && (
        <div className="note" style={{ marginBottom: 14 }}>
          Some minds use the <b>local proxy</b>. Most cloud APIs refuse requests made straight from a web page, so run this once in the <code>world</code> folder and leave it open:
          <pre style={{ margin: '6px 0 0', background: 'rgba(0,0,0,0.3)', padding: 8, borderRadius: 8 }}>npm run proxy</pre>
          It listens on {PROXY_URL}, only on your own machine.
        </div>
      )}
    </>
  )
}

function ProfileEditor({ p, open, onToggle, onChange, onDelete }: { p: MindProfile; open: boolean; onToggle: () => void; onChange: (p: MindProfile) => void; onDelete: () => void }) {
  const v = vendorOf(p)
  const [models, setModels] = useState<string[]>(v?.models ?? [])
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const set = (k: Partial<MindProfile>) => onChange({ ...p, ...k })
  return (
    <div className="card">
      <h3 style={{ cursor: 'pointer' }} onClick={onToggle}>
        <span>{v?.flag}</span>{p.label}
        <span className="tag" style={{ marginLeft: 'auto' }}>{open ? 'close' : 'edit'}</span>
      </h3>
      {open && (
        <>
          <div className="cols">
            <div className="field"><label>Name</label><input className="input" value={p.label} onChange={(e) => set({ label: e.target.value })} /></div>
            <div className="field">
              <label>Model</label>
              <input className="input" list={`models-${p.id}`} value={p.model} onChange={(e) => set({ model: e.target.value })} placeholder="model name" />
              <datalist id={`models-${p.id}`}>{models.map((m) => <option key={m} value={m} />)}</datalist>
            </div>
            <div className="field" style={{ gridColumn: '1 / -1' }}><label>Endpoint (OpenAI-compatible base URL)</label><input className="input" value={p.baseUrl} onChange={(e) => set({ baseUrl: e.target.value })} /></div>
            {!v?.local && (
              <div className="field" style={{ gridColumn: '1 / -1' }}>
                <label>API key</label>
                <input className="input" type="password" value={p.apiKey} onChange={(e) => set({ apiKey: e.target.value })} placeholder="sk-…" autoComplete="off" />
                {v?.keyUrl && <div className="hint">Get one at {v.keyUrl}. Stored only in this browser.</div>}
              </div>
            )}
            <div className="field"><label>$ per M input</label><input className="input" type="number" step="0.01" value={p.priceIn} onChange={(e) => set({ priceIn: +e.target.value })} /></div>
            <div className="field"><label>$ per M output</label><input className="input" type="number" step="0.01" value={p.priceOut} onChange={(e) => set({ priceOut: +e.target.value })} /></div>
          </div>
          <div className="field" style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
            <label className="toggle"><input type="checkbox" checked={p.useProxy} onChange={(e) => set({ useProxy: e.target.checked })} /><span>Use local proxy</span></label>
            <label className="toggle"><input type="checkbox" checked={p.jsonMode} onChange={(e) => set({ jsonMode: e.target.checked })} /><span>JSON mode</span></label>
          </div>
          {v && <div className="hint" style={{ marginBottom: 10 }}>{v.note}</div>}
          <div className="btn-row" style={{ justifyContent: 'flex-start', marginTop: 0 }}>
            <button className="btn" disabled={busy} onClick={async () => {
              setBusy(true)
              try { const m = await listModels(p); setModels(m); setStatus({ ok: true, msg: `${m.length} models available — pick one from the Model field.` }) } catch (e) { setStatus({ ok: false, msg: `Couldn't list models: ${(e as Error).message}` }) }
              setBusy(false)
            }}>List models</button>
            <button className="btn primary" disabled={busy} onClick={async () => {
              setBusy(true)
              try { const r = await testProfile(p, mind.settings.apiKey); setStatus({ ok: true, msg: `Connected ✓ ${r.slice(0, 160)}` }) } catch (e) { setStatus({ ok: false, msg: (e as Error).message }) }
              setBusy(false)
            }}>{busy ? '…' : 'Test connection'}</button>
            <button className="btn" onClick={() => mind.update({ model: p.id })}>Make default</button>
            <button className="btn danger" onClick={onDelete}>Remove</button>
          </div>
          {status && <div className={`note ${status.ok ? '' : 'warn'}`} style={{ marginTop: 10 }}>{status.msg}</div>}
        </>
      )}
    </div>
  )
}

/** A <select> of every mind profile. */
export function ProfileSelect({ value, onChange, emptyLabel, className, style }: { value: string; onChange: (v: string) => void; emptyLabel: string; className?: string; style?: React.CSSProperties }) {
  useMindVersion()
  return (
    <select className={className} style={style} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{emptyLabel}</option>
      {mind.profiles.map((p) => <option key={p.id} value={p.id}>{p.kind === 'native' ? '🌱 ' : p.kind === 'anthropic' ? '✦ ' : `${vendorOf(p)?.flag ?? ''} `}{p.label}</option>)}
    </select>
  )
}
