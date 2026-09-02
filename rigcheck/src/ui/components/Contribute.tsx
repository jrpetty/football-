/**
 * The way in for data the user has and this build does not.
 *
 * Every other screen consumes the catalogue. This one adds to it, and it exists
 * because the routes that already existed all led outside the app: prices come
 * from `npm run import:prices`, measurements from `npm run import:manual`, and
 * the thing anyone actually has is a single HTML file. So the numbers stayed
 * recalled for everybody who was not already set up to develop the tool.
 *
 * Two things are shown before either form, because they are the reason to
 * bother: how much of the catalogue can be costed at all, and how many real
 * frame-rate captures the peer comparison has to work with. The second is
 * normally zero, and it is better to say so than to leave a panel that silently
 * never appears.
 */

import { useMemo, useState } from 'react';
import { useApp } from '../store.ts';
import { PartPicker } from './Parts.tsx';
import { exportJson } from '../export.ts';
import { loadPrices, pricedIds } from '../pricing.ts';
import {
  exportBundle, priceCoverageOf, readUserData, type UserMeasurement, type UserPrice,
} from '../../core/userdata.ts';
import {
  addMeasurement, addPrice, clearUserData, loadUserData, removeMeasurement, removePrice, saveUserData,
} from '../userdata.ts';
import { mergeUserData } from '../../core/userdata.ts';
import { changeOverall, describeChange, monthLabel } from '../../core/pricetrend.ts';
import type { Resolution } from '../../core/types.ts';

const RESOLUTIONS: Resolution[] = ['1080p', '1440p', '2160p', '3440x1440'];
const today = () => new Date().toISOString().slice(0, 10);

export function Contribute() {
  const { data } = useApp();
  const [user, setUser] = useState(() => loadUserData());
  const [notice, setNotice] = useState<{ good: boolean; lines: string[] } | null>(null);

  const priced = useMemo(() => pricedIds(loadPrices(user)), [user]);
  const observed = useMemo(() => loadPrices(user).observed, [user]);
  const watched = useMemo(() => observed.filter((o) => (o.series?.length ?? 0) > 1), [observed]);
  const single = useMemo(() => observed.filter((o) => (o.series?.length ?? 0) === 1), [observed]);
  const partName = (id: string) => data.gpus.get(id)?.fullName ?? data.cpus.get(id)?.fullName ?? id;
  const coverage = useMemo(
    () => ({
      gpu: priceCoverageOf([...data.gpus.keys()], priced, user),
      cpu: priceCoverageOf([...data.cpus.keys()], priced, user),
    }),
    [data, priced, user],
  );

  const importFile = async (file: File) => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(await file.text());
    } catch {
      setNotice({ good: false, lines: ['That file is not valid JSON.'] });
      return;
    }
    const r = readUserData(parsed, {
      parts: new Set([...data.gpus.keys(), ...data.cpus.keys()]),
      games: new Set(data.games.keys()),
    });
    if (r.unreadable) {
      setNotice({ good: false, lines: r.rejected });
      return;
    }
    const merged = mergeUserData(user, r.data);
    setUser(saveUserData(merged));
    setNotice({
      good: true,
      lines: [
        `Imported ${r.data.prices.length} price${r.data.prices.length === 1 ? '' : 's'} and ` +
          `${r.data.measurements.length} measurement${r.data.measurements.length === 1 ? '' : 's'}.`,
        ...r.warnings,
        ...r.rejected,
      ],
    });
  };

  return (
    <>
      <div className="panel">
        <div className="panel-head"><h2>What this build can and cannot cost</h2></div>
        <div className="panel-body">
          <p className="mini" style={{ marginTop: 0 }}>
            The budget planner can only recommend a part it has a price for, so this figure is a ceiling on
            how good its answer can be. It is not choosing the best part for your money — it is choosing the
            best part it happens to know the price of, and those are different claims.
          </p>
          <div className="stat-row">
            <CoverageStat label="graphics cards priced" c={coverage.gpu} />
            <CoverageStat label="processors priced" c={coverage.cpu} />
            <div className="stat">
              <span
                className="v"
                style={{ color: user.measurements.length ? 'var(--good)' : 'var(--bad)' }}
              >
                {user.measurements.length}
              </span>
              <span className="k">
                frame-rate capture{user.measurements.length === 1 ? '' : 's'} on record
              </span>
            </div>
          </div>
          {!user.measurements.length && (
            <div className="note warn" style={{ marginTop: 12 }}>
              This build ships with an empty measured corpus, so the health report's "compared with other
              machines" panel has nothing to compare against and does not appear. Every capture added below
              goes into it.
            </div>
          )}
        </div>
      </div>

      <div className="panel">
        <div className="panel-head"><h2>Price movement</h2></div>
        <div className="panel-body">
          {observed.length === 0 ? (
            <p className="mini" style={{ marginTop: 0 }}>
              No market observations on record yet, so every price in the app is a recalled seed with no
              date behind it. Record one in a line from a checkout:{' '}
              <span className="mono">npm run price -- "i7 7700" used 40</span>. It goes into this
              week's snapshot file under <span className="mono">data/prices-observed/</span>, and a
              second snapshot on a later date is what turns a price into a trend.
            </p>
          ) : (
            <>
              <p className="mini" style={{ marginTop: 0 }}>
                The current figure for a part is the median of its newest observations; every earlier
                snapshot is kept, and the movement below runs from the first date on record to the
                latest. A part seen once has a price, not a trend — the second snapshot is the one that
                makes a claim like "down 12% since August" possible, and nothing here will say it before
                then.
              </p>
              {watched.length > 0 && (
                <div className="table-wrap">
                  <table className="data">
                    <thead>
                      <tr>
                        <th>part</th><th>condition</th><th className="n">first seen</th><th className="n">latest</th><th>movement</th>
                      </tr>
                    </thead>
                    <tbody>
                      {watched.map((o) => {
                        const m = changeOverall(o.series)!;
                        return (
                          <tr key={`${o.partId}-${o.condition}`}>
                            <td>{partName(o.partId)}</td>
                            <td className="sub">{o.condition}</td>
                            <td className="n sub">£{m.from.price} · {m.from.date}</td>
                            <td className="n">£{m.to.price} · {m.to.date}</td>
                            <td className={m.pct < 0 ? 'good' : m.pct > 0 ? 'bad' : 'sub'}>
                              {describeChange(m, monthLabel(m.from.date.slice(0, 7)))}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
              {single.length > 0 && (
                <p className="mini" style={{ marginBottom: 0 }}>
                  Seen once, so a price rather than a trend:{' '}
                  {single.map((o, i) => (
                    <span key={`${o.partId}-${o.condition}`}>
                      {i ? ', ' : ''}
                      {partName(o.partId)} ({o.condition}) £{o.price} on {o.newestDate}
                    </span>
                  ))}
                  . Record it again in a later week and the movement appears here.
                </p>
              )}
            </>
          )}
        </div>
      </div>

      <div className="grid two">
        <PriceForm
          onAdd={(p) => {
            setUser(addPrice(p));
            setNotice({ good: true, lines: [`Saved ${p.gbp} pounds for ${p.partId} (${p.condition}).`] });
          }}
        />
        <MeasurementForm
          onAdd={(m) => {
            setUser(addMeasurement(m));
            setNotice({ good: true, lines: [`Saved ${m.avgFps}fps for ${m.gameId} at ${m.resolution}.`] });
          }}
        />
      </div>

      {notice && (
        <div className={`note ${notice.good ? '' : 'bad'}`} style={{ marginBottom: 14 }}>
          {notice.lines.map((l, i) => (
            <div key={i} style={{ marginTop: i ? 5 : 0 }}>{l}</div>
          ))}
        </div>
      )}

      <div className="panel">
        <div className="panel-head">
          <h2>What you have added</h2>
          <span className="spacer" />
          <button
            className="btn"
            disabled={!user.prices.length && !user.measurements.length}
            onClick={() => exportJson('rigcheck-my-data.json', JSON.parse(exportBundle(user, new Date().toISOString())))}
          >
            export
          </button>
          <label className="btn" style={{ cursor: 'pointer' }}>
            import
            <input
              type="file"
              accept="application/json,.json"
              style={{ display: 'none' }}
              onChange={(e) => {
                const f = e.target.files?.[0];
                // Cleared so the same file can be picked twice — a browser fires
                // no change event when the value has not changed, which makes a
                // corrected re-import look like a dead button.
                e.target.value = '';
                if (f) void importFile(f);
              }}
            />
          </label>
        </div>
        <div className="panel-body">
          <p className="mini" style={{ marginTop: 0 }}>
            Held in this browser only — nothing here is transmitted, because there is nowhere to transmit it
            to. Export it to keep a copy that survives a cleared cache, to move it to another machine, or to
            drop into <span className="mono">data/</span> in the repository.
          </p>

          {!user.prices.length && !user.measurements.length && (
            <div className="empty">Nothing yet. The two forms above are the way in.</div>
          )}

          {user.prices.length > 0 && (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr><th>part</th><th>condition</th><th className="num">GBP</th><th>seen</th><th>where</th><th /></tr>
                </thead>
                <tbody>
                  {user.prices.map((p) => (
                    <tr key={`${p.partId}-${p.condition}`}>
                      <td>{data.gpus.get(p.partId)?.fullName ?? data.cpus.get(p.partId)?.fullName ?? p.partId}</td>
                      <td>{p.condition}</td>
                      <td className="num">{p.gbp.toFixed(0)}</td>
                      <td>{p.at.slice(0, 10)}</td>
                      <td>{p.where ?? '—'}</td>
                      <td>
                        <button className="btn tiny" onClick={() => setUser(removePrice(p.partId, p.condition))}>
                          remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {user.measurements.length > 0 && (
            <div className="table-wrap" style={{ marginTop: 12 }}>
              <table className="data">
                <thead>
                  <tr><th>game</th><th>at</th><th>machine</th><th className="num">avg</th><th className="num">1% low</th><th /></tr>
                </thead>
                <tbody>
                  {user.measurements.map((m) => (
                    <tr key={m.id}>
                      <td>{data.games.get(m.gameId)?.name ?? m.gameId}</td>
                      <td>{m.resolution}, {m.preset ?? 'high'}</td>
                      <td className="mini">
                        {data.cpus.get(m.cpuId)?.brand ?? m.cpuId} + {data.gpus.get(m.gpuId)?.brand ?? m.gpuId}
                      </td>
                      <td className="num">{m.avgFps.toFixed(0)}</td>
                      <td className="num">{m.low1PctFps?.toFixed(0) ?? '—'}</td>
                      <td>
                        <button className="btn tiny" onClick={() => setUser(removeMeasurement(m.id))}>remove</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {(user.prices.length > 0 || user.measurements.length > 0) && (
            <div className="wizard-nav">
              <button
                className="btn"
                onClick={() => {
                  // Export first, then clear. Losing hand-entered data to a
                  // misclick is the kind of thing that stops somebody entering
                  // any more of it.
                  exportJson('rigcheck-my-data-backup.json', JSON.parse(exportBundle(user, new Date().toISOString())));
                  setUser(clearUserData());
                  setNotice({ good: true, lines: ['Cleared. A backup was downloaded first.'] });
                }}
              >
                clear everything
              </button>
              <span className="mini">Downloads a backup before it clears.</span>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

function CoverageStat({ label, c }: { label: string; c: { covered: number; total: number; share: number; fromUser: number } }) {
  return (
    <div className="stat">
      <span className="v" style={{ color: c.share < 0.5 ? 'var(--bad)' : 'var(--good)' }}>
        {c.covered}/{c.total}
      </span>
      <span className="k">
        {label}
        {c.fromUser > 0 ? ` · ${c.fromUser} by you` : ''}
      </span>
    </div>
  );
}

/* ----------------------------------------------------------------- forms -- */

function PriceForm({ onAdd }: { onAdd: (p: UserPrice) => void }) {
  const [kind, setKind] = useState<'gpu' | 'cpu'>('gpu');
  const [partId, setPartId] = useState('');
  const [condition, setCondition] = useState<'new' | 'used'>('used');
  const [gbp, setGbp] = useState<number | ''>('');
  const [at, setAt] = useState(today());
  const [where, setWhere] = useState('');

  const high = typeof gbp === 'number' && gbp > 5000;
  const ok = !!partId && typeof gbp === 'number' && gbp > 0;

  return (
    <div className="panel">
      <div className="panel-head"><h2>Add a price you have seen</h2></div>
      <div className="panel-body">
        <p className="mini" style={{ marginTop: 0 }}>
          A figure you can actually buy at beats anything this build recalls, so it wins outright wherever a
          price is used. New and used are kept separate — the Trade Desk is entirely about the gap between
          them.
        </p>
        {/* The picker is given its own label rather than wrapped in a field
            that carries one. Nesting it inside another `.field` left its search
            box with no label of its own — the outer label described the group,
            and a screen reader landing on the input heard nothing. */}
        <div className="field">
          <label>kind of part</label>
          <div className="toggle-row">
            {(['gpu', 'cpu'] as const).map((k) => (
              <button
                key={k}
                className={`toggle${kind === k ? ' on' : ''}`}
                onClick={() => { setKind(k); setPartId(''); }}
              >
                {k === 'gpu' ? 'graphics card' : 'processor'}
              </button>
            ))}
          </div>
        </div>
        <PartPicker kind={kind} label="part" value={partId} onChange={setPartId} />
        <div className="grid two">
          <div className="field">
            <label>condition</label>
            <div className="toggle-row">
              {(['used', 'new'] as const).map((c) => (
                <button key={c} className={`toggle${condition === c ? ' on' : ''}`} onClick={() => setCondition(c)}>{c}</button>
              ))}
            </div>
          </div>
          <div className="field">
            <label>price (GBP)</label>
            <input
              type="number"
              value={gbp}
              placeholder="165"
              onChange={(e) => setGbp(e.target.value === '' ? '' : Number(e.target.value))}
            />
          </div>
        </div>
        <div className="grid two">
          <div className="field">
            <label>seen on</label>
            <input type="date" value={at} onChange={(e) => setAt(e.target.value)} />
          </div>
          <div className="field">
            <label>where</label>
            <input value={where} placeholder="eBay sold, a retailer, local" onChange={(e) => setWhere(e.target.value)} />
          </div>
        </div>
        {high && (
          <div className="note warn" style={{ fontSize: 12 }}>
            {gbp} pounds is high for a single component. If you meant {(Number(gbp) / 100).toFixed(2)}, this is
            a hundred times too large and will move every recommendation on the budget screens.
          </div>
        )}
        <div className="wizard-nav">
          <button
            className="btn primary"
            disabled={!ok}
            onClick={() => {
              onAdd({ partId, condition, gbp: Number(gbp), at, where: where.trim() || undefined });
              setGbp('');
              setWhere('');
            }}
          >
            {ok ? 'save this price' : 'pick a part and a price'}
          </button>
        </div>
      </div>
    </div>
  );
}

function MeasurementForm({ onAdd }: { onAdd: (m: UserMeasurement) => void }) {
  const { data } = useApp();
  const [cpuId, setCpuId] = useState('');
  const [gpuId, setGpuId] = useState('');
  const [gameId, setGameId] = useState('cyberpunk-2077');
  const [resolution, setResolution] = useState<Resolution>('1440p');
  const [preset, setPreset] = useState('high');
  const [avgFps, setAvgFps] = useState<number | ''>('');
  const [low, setLow] = useState<number | ''>('');
  const [note, setNote] = useState('');

  const lowTooHigh = typeof low === 'number' && typeof avgFps === 'number' && low > avgFps;
  const ok = !!cpuId && !!gpuId && typeof avgFps === 'number' && avgFps > 0 && !lowTooHigh;

  return (
    <div className="panel">
      <div className="panel-head"><h2>Add a frame rate you have captured</h2></div>
      <div className="panel-body">
        <p className="mini" style={{ marginTop: 0 }}>
          A real capture — from the PowerShell harness, an overlay, or a game's own benchmark. This is the
          only kind of number in the whole tool that is measured rather than modelled, and it is what the
          health report compares your machine against.
        </p>
        <PartPicker kind="cpu" label="processor" value={cpuId} onChange={setCpuId} />
        <PartPicker kind="gpu" label="graphics card" value={gpuId} onChange={setGpuId} />
        <div className="grid two">
          <div className="field">
            <label>game</label>
            <select value={gameId} onChange={(e) => setGameId(e.target.value)}>
              {[...data.games.values()].map((g) => (
                <option key={g.id} value={g.id}>{g.name}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>preset</label>
            <input value={preset} onChange={(e) => setPreset(e.target.value)} />
          </div>
        </div>
        <div className="field">
          <label>resolution</label>
          <div className="toggle-row">
            {RESOLUTIONS.map((r) => (
              <button key={r} className={`toggle${resolution === r ? ' on' : ''}`} onClick={() => setResolution(r)}>{r}</button>
            ))}
          </div>
        </div>
        <div className="grid two">
          <div className="field">
            <label>average fps</label>
            <input type="number" value={avgFps} placeholder="58" onChange={(e) => setAvgFps(e.target.value === '' ? '' : Number(e.target.value))} />
          </div>
          <div className="field">
            <label>1% low</label>
            <input type="number" value={low} placeholder="optional" onChange={(e) => setLow(e.target.value === '' ? '' : Number(e.target.value))} />
          </div>
        </div>
        <div className="field">
          <label>note</label>
          <input value={note} placeholder="upscaling off, stock clocks" onChange={(e) => setNote(e.target.value)} />
        </div>
        {lowTooHigh && (
          <div className="note bad" style={{ fontSize: 12 }}>
            The 1% low cannot be above the average — those two are probably the wrong way round.
          </div>
        )}
        <div className="wizard-nav">
          <button
            className="btn primary"
            disabled={!ok}
            onClick={() => {
              const at = new Date().toISOString();
              onAdd({
                // Timestamped rather than keyed on the settings: two captures of
                // the same game on the same machine are two runs, and having
                // both is what makes a spread visible.
                id: `um-${gameId}-${resolution}-${Date.parse(at).toString(36)}`,
                cpuId, gpuId, gameId, resolution,
                preset: preset.trim() || undefined,
                avgFps: Number(avgFps),
                low1PctFps: typeof low === 'number' ? low : undefined,
                at,
                note: note.trim() || undefined,
              });
              setAvgFps('');
              setLow('');
            }}
          >
            {ok ? 'save this capture' : lowTooHigh ? 'check the two figures' : 'pick the parts and a frame rate'}
          </button>
        </div>
      </div>
    </div>
  );
}
