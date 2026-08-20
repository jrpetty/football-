/**
 * Build a PC — the guided path.
 *
 * Everything else in this tool assumes you already know what you want to
 * compare. This is for the person who does not: they have a screen, a handful
 * of games and an amount of money, and no idea which of the 279 graphics cards
 * in the catalogue is the right one.
 *
 * Three deliberate choices in how it is laid out:
 *
 *  - **A step rail, not a progress bar.** People change an earlier answer far
 *    more often than they wonder how far through they are, so every completed
 *    step stays clickable and nothing is destroyed by going back.
 *  - **The answer so far is pinned under the rail.** A wizard that hides its
 *    own state makes people scroll back to check what they picked.
 *  - **The recommendation updates from the first answer, not the last.** As
 *    soon as there is a screen and a game, there is a build worth showing, and
 *    watching it change as the budget moves is the fastest way to learn what a
 *    budget actually buys.
 */
import { useMemo, useState } from 'react';
import { useApp } from '../store.ts';
import { planBuild, type ComponentPrices, type PlanRequest } from '../../core/planner.ts';
import { PRESETS, type Preset } from '../../core/presets.ts';
import { exportJson } from '../export.ts';
import newPrices from '../../../data/pricing/gbp-new.json';
import usedPrices from '../../../data/pricing/gbp-used.json';
import componentPrices from '../../../data/pricing/components-gbp.json';
import monitorsJson from '../../../data/catalogue/monitors.json';
import type { MonitorRecord } from '../../core/fit.ts';
import type { Resolution } from '../../core/types.ts';

const monitors = (monitorsJson as { records: MonitorRecord[] }).records;
const comp = componentPrices as unknown as ComponentPrices;
const priceTables = {
  newP: (newPrices as { prices: Record<string, number> }).prices,
  usedP: (usedPrices as { prices: Record<string, number> }).prices,
};
const PRICE_AS_OF = (newPrices as { asOf?: string }).asOf ?? 'unknown';

const STEPS = [
  { key: 'screen', title: 'Your screen', hint: 'resolution and refresh' },
  { key: 'games', title: 'Your games', hint: 'what it has to run' },
  { key: 'budget', title: 'Your budget', hint: 'and how you shop' },
  { key: 'build', title: 'The build', hint: 'and why' },
  { key: 'settings', title: 'Your settings', hint: 'game by game' },
] as const;

/** Common panels, offered before the catalogue so nobody has to browse 37 monitors. */
const QUICK_SCREENS: { label: string; sub: string; resolution: Resolution; refreshHz: number }[] = [
  { label: '1080p 60Hz', sub: 'an older or office monitor', resolution: '1080p', refreshHz: 60 },
  { label: '1080p 144Hz', sub: 'the common competitive setup', resolution: '1080p', refreshHz: 144 },
  { label: '1080p 240Hz', sub: 'esports, frame rate above all', resolution: '1080p', refreshHz: 240 },
  { label: '1440p 144Hz', sub: 'the current mainstream sweet spot', resolution: '1440p', refreshHz: 144 },
  { label: '1440p 240Hz', sub: 'fast and sharp, needs real hardware', resolution: '1440p', refreshHz: 240 },
  { label: '3440x1440 144Hz', sub: 'ultrawide', resolution: '3440x1440', refreshHz: 144 },
  { label: '4K 60Hz', sub: 'sharpness over speed', resolution: '2160p', refreshHz: 60 },
  { label: '4K 144Hz', sub: 'the most demanding target here', resolution: '2160p', refreshHz: 144 },
];

const LIBRARY_PRESETS: { label: string; sub: string; pick: (ids: string[], archetypeOf: (id: string) => string) => string[] }[] = [
  { label: 'Competitive shooters', sub: 'frame rate is the point', pick: (ids, a) => ids.filter((i) => a(i) === 'esports') },
  { label: 'Big single-player', sub: 'looks matter more than 240fps', pick: (ids, a) => ids.filter((i) => a(i) === 'aaa-raster' || a(i) === 'aaa-rt') },
  { label: 'Newest and heaviest', sub: 'UE5 and ray tracing', pick: (ids, a) => ids.filter((i) => a(i) === 'ue5' || a(i) === 'aaa-rt') },
  { label: 'Simulation and strategy', sub: 'CPU-bound, cache-hungry', pick: (ids, a) => ids.filter((i) => a(i) === 'sim-cpu') },
];

export function BuildWizard() {
  const { data } = useApp();
  const [step, setStep] = useState(0);
  const [reached, setReached] = useState(0);

  const [resolution, setResolution] = useState<Resolution>('1440p');
  const [refreshHz, setRefreshHz] = useState(144);
  const [monitorId, setMonitorId] = useState<string>('');
  const [gameIds, setGameIds] = useState<string[]>([]);
  const [gameQuery, setGameQuery] = useState('');
  const [budget, setBudget] = useState(1200);
  const [noBudget, setNoBudget] = useState(false);
  const [condition, setCondition] = useState<'new' | 'used' | 'either'>('new');
  const [minPreset, setMinPreset] = useState<Preset>('high');
  const [targetFps, setTargetFps] = useState<number | null>(null);
  const [preferQuiet, setPreferQuiet] = useState(false);
  const [ramGB, setRamGB] = useState<number | null>(null);
  const [storageGB, setStorageGB] = useState(1000);
  const [showAlt, setShowAlt] = useState(false);

  const go = (i: number) => {
    setStep(i);
    setReached((r) => Math.max(r, i));
  };

  const games = useMemo(() => [...data.games.values()].sort((a, b) => a.name.localeCompare(b.name)), [data]);
  const archetypeOf = (id: string) => data.games.get(id)?.archetype ?? '';
  const filteredGames = useMemo(
    () => games.filter((g) => !gameQuery.trim() || g.name.toLowerCase().includes(gameQuery.toLowerCase())),
    [games, gameQuery],
  );

  const request: PlanRequest = useMemo(
    () => ({
      budget: noBudget ? undefined : budget,
      condition,
      resolution,
      refreshHz,
      gameIds,
      targetFps: targetFps ?? undefined,
      minPreset,
      preferQuiet,
      ramGB: ramGB ?? undefined,
      storageGB,
    }),
    [budget, noBudget, condition, resolution, refreshHz, gameIds, targetFps, minPreset, preferQuiet, ramGB, storageGB],
  );

  // Planning is ~0.2s, so it runs on every change rather than behind a button.
  // A recommendation that moves while you drag the budget teaches more about
  // what money buys than any amount of explanation.
  const plan = useMemo(
    () => (gameIds.length ? planBuild(request, data, priceTables, comp) : null),
    [request, data, gameIds.length],
  );

  const target = targetFps ?? refreshHz;
  const canAdvance = step === 1 ? gameIds.length > 0 : true;

  return (
    <>
      <div className="page-head">
        <h1>Build a PC</h1>
        <p>
          Tell it what screen you have, what you play and what you can spend. It works backwards to a
          parts list, explains every line, and then tells you what to set each game to.
        </p>
      </div>

      <div className="steps">
        {STEPS.map((s, i) => (
          <button
            key={s.key}
            className={`step${i === step ? ' on' : ''}${i < step ? ' done' : ''}`}
            onClick={() => go(i)}
            disabled={i > reached && !(i === step + 1 && canAdvance)}
          >
            <span className="n">{i < step ? '✓' : i + 1}</span>
            <span className="lbl">
              <b>{s.title}</b>
              <span>{s.hint}</span>
            </span>
          </button>
        ))}
      </div>

      <div className="plan-strip">
        <span>
          <span className="k">screen</span>{' '}
          <span className="v">{resolution} @ {refreshHz}Hz</span>
        </span>
        <span>
          <span className="k">games</span>{' '}
          <span className={`v${gameIds.length ? '' : ' none'}`}>{gameIds.length || 'none picked'}</span>
        </span>
        <span>
          <span className="k">budget</span>{' '}
          <span className="v">{noBudget ? 'no limit' : `£${budget}`}</span>
        </span>
        <span className="spacer" style={{ flex: 1 }} />
        {plan?.pick && (
          <>
            <span>
              <span className="k">recommendation</span>{' '}
              <span className="v">£{plan.pick.total}</span>
            </span>
            <span>
              <span className="k">runs at</span>{' '}
              <span className="v">{plan.pick.typicalPreset}, {plan.pick.medianFps.toFixed(0)}fps</span>
            </span>
          </>
        )}
      </div>

      {/* ---------------------------------------------------------- screen -- */}
      {step === 0 && (
        <div className="panel">
          <div className="panel-head"><h2>What are you playing on?</h2></div>
          <div className="panel-body">
            <p className="mini" style={{ marginTop: 0 }}>
              This is the single most important answer, and the one people skip. The same money buys a
              very different machine for 1080p 240Hz than for 4K 60 — one needs CPU and frame rate, the
              other needs GPU and pixels. Picking hardware before picking a screen is how budgets get wasted.
            </p>
            <div className="pick-grid" style={{ marginBottom: 14 }}>
              {QUICK_SCREENS.map((q) => (
                <button
                  key={q.label}
                  className={`pick-card${resolution === q.resolution && refreshHz === q.refreshHz && !monitorId ? ' on' : ''}`}
                  onClick={() => { setResolution(q.resolution); setRefreshHz(q.refreshHz); setMonitorId(''); setTargetFps(null); }}
                >
                  <b>{q.label}</b>
                  <span className="sub">{q.sub}</span>
                </button>
              ))}
            </div>

            <div className="grid two">
              <div className="field">
                <label>or pick your exact monitor</label>
                <select
                  value={monitorId}
                  onChange={(e) => {
                    const m = monitors.find((x) => x.id === e.target.value);
                    setMonitorId(e.target.value);
                    if (m) { setResolution(m.resolution); setRefreshHz(m.refreshHz); setTargetFps(null); }
                  }}
                >
                  <option value="">— not listed / use the tiles above —</option>
                  {monitors.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.fullName} — {m.resolution} {m.refreshHz}Hz {m.sizeInches}"
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>frame rate to aim for</label>
                <div className="toggle-row">
                  <button className={`toggle${targetFps === null ? ' on' : ''}`} onClick={() => setTargetFps(null)}>
                    match the panel ({refreshHz})
                  </button>
                  {[60, 120, 144].filter((f) => f < refreshHz).map((f) => (
                    <button key={f} className={`toggle${targetFps === f ? ' on' : ''}`} onClick={() => setTargetFps(f)}>{f}</button>
                  ))}
                </div>
                <span className="mini">
                  Aiming below your panel's refresh is a legitimate trade, not a compromise: on a 240Hz
                  screen, targeting 120 and spending the difference on visuals is the right call for most
                  single-player games.
                </span>
              </div>
            </div>

            {monitorId && (() => {
              const m = monitors.find((x) => x.id === monitorId);
              return m ? (
                <div className="note" style={{ marginTop: 12 }}>
                  <b>{m.fullName}</b> — {m.panelType}, {m.sizeInches}&quot;, {m.adaptiveSync} adaptive sync
                  {m.hdr ? `, ${m.hdr}` : ''}. {m.notes}
                </div>
              ) : null;
            })()}
          </div>
        </div>
      )}

      {/* ----------------------------------------------------------- games -- */}
      {step === 1 && (
        <div className="panel">
          <div className="panel-head">
            <h2>What do you play?</h2>
            <span className="spacer" />
            <span className="mini">{gameIds.length} selected</span>
            {gameIds.length > 0 && (
              <button className="btn" style={{ marginLeft: 8 }} onClick={() => setGameIds([])}>clear</button>
            )}
          </div>
          <div className="panel-body">
            <p className="mini" style={{ marginTop: 0 }}>
              Pick the ones you actually play, not the ones you own. A build is only right relative to
              what it has to run — a machine that is perfect for Counter-Strike is the wrong machine for
              Cyberpunk, and both cost the same.
            </p>
            <div className="pick-grid" style={{ marginBottom: 12 }}>
              {LIBRARY_PRESETS.map((p) => {
                const ids = p.pick(games.map((g) => g.id), archetypeOf);
                return (
                  <button key={p.label} className="pick-card" onClick={() => setGameIds((cur) => [...new Set([...cur, ...ids])])}>
                    <b>{p.label}</b>
                    <span className="sub">{p.sub} — adds {ids.length}</span>
                  </button>
                );
              })}
            </div>
            <input
              type="text"
              value={gameQuery}
              placeholder="filter games…"
              onChange={(e) => setGameQuery(e.target.value)}
              style={{ width: '100%', marginBottom: 10 }}
            />
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {filteredGames.map((g) => {
                const on = gameIds.includes(g.id);
                return (
                  <button
                    key={g.id}
                    className={`toggle${on ? ' on' : ''}`}
                    onClick={() => setGameIds((cur) => (on ? cur.filter((x) => x !== g.id) : [...cur, g.id]))}
                    title={`${g.archetype}${g.fpsCap ? ` · capped at ${g.fpsCap}fps` : ''}`}
                  >
                    {g.name}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* ---------------------------------------------------------- budget -- */}
      {step === 2 && (
        <div className="panel">
          <div className="panel-head"><h2>What can you spend?</h2></div>
          <div className="panel-body">
            <div className="note bad" style={{ marginBottom: 14 }}>
              <b>Prices here are a recalled seed, not live data.</b> They date from {PRICE_AS_OF} and were
              never sourced from a retailer. Graphics card pricing in particular moves 30% on supply
              alone. Treat every figure as an order of magnitude, check the two or three parts you
              actually intend to buy, and replace <span className="mono">data/pricing/</span> with your
              own sourcing before trusting a plan to the pound.
            </div>

            <div className="grid two">
              <div className="field">
                <label>total budget for the tower — £{noBudget ? '—' : budget}</label>
                <input
                  className="slider"
                  type="range"
                  min={400}
                  max={4000}
                  step={25}
                  value={budget}
                  disabled={noBudget}
                  onChange={(e) => setBudget(Number(e.target.value))}
                />
                <div className="toggle-row" style={{ marginTop: 6 }}>
                  {[600, 800, 1000, 1200, 1500, 2000, 2500].map((b) => (
                    <button key={b} className={`toggle${!noBudget && budget === b ? ' on' : ''}`} onClick={() => { setBudget(b); setNoBudget(false); }}>£{b}</button>
                  ))}
                  <button className={`toggle${noBudget ? ' on' : ''}`} onClick={() => setNoBudget(!noBudget)}>no limit</button>
                </div>
                <span className="mini">
                  Tower only — this does not include the monitor, keyboard, mouse or a Windows licence.
                  It does include the motherboard, memory, storage, power supply, case and cooler, which
                  a CPU-and-GPU budget forgets and which come to 300–400 of any build.
                </span>
              </div>

              <div>
                <div className="field">
                  <label>how do you shop?</label>
                  <div className="toggle-row">
                    {(['new', 'either', 'used'] as const).map((c) => (
                      <button key={c} className={`toggle${condition === c ? ' on' : ''}`} onClick={() => setCondition(c)}>
                        {c === 'either' ? 'either' : c}
                      </button>
                    ))}
                  </div>
                  <span className="mini">
                    Used doubles what a budget buys and carries no warranty. &quot;Either&quot; takes whichever is
                    cheaper per part, which is what someone shopping both markets actually pays.
                  </span>
                </div>

                <div className="field">
                  <label>lowest settings you would accept</label>
                  <div className="toggle-row">
                    {PRESETS.map((p) => (
                      <button key={p} className={`toggle${minPreset === p ? ' on' : ''}`} onClick={() => setMinPreset(p)}>{p}</button>
                    ))}
                  </div>
                  <span className="mini">
                    This stops the planner answering the wrong question. Without a floor, the cheapest
                    build that &quot;hits {target}fps&quot; is whatever gets there at low — technically correct,
                    and not what anyone means.
                  </span>
                </div>

                <div className="field">
                  <label>preferences</label>
                  <div className="toggle-row">
                    <button className={`toggle${preferQuiet ? ' on' : ''}`} onClick={() => setPreferQuiet(!preferQuiet)}>quiet build</button>
                    {[16, 32, 64].map((r) => (
                      <button key={r} className={`toggle${ramGB === r ? ' on' : ''}`} onClick={() => setRamGB(ramGB === r ? null : r)}>{r}GB RAM</button>
                    ))}
                    {[1000, 2000, 4000].map((s) => (
                      <button key={s} className={`toggle${storageGB === s ? ' on' : ''}`} onClick={() => setStorageGB(s)}>{s / 1000}TB</button>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ----------------------------------------------------------- build -- */}
      {step === 3 && <BuildStep plan={plan} showAlt={showAlt} setShowAlt={setShowAlt} target={target} />}

      {/* -------------------------------------------------------- settings -- */}
      {step === 4 && <SettingsStep plan={plan} resolution={resolution} />}

      <div className="wizard-nav">
        <button className="btn" onClick={() => go(Math.max(0, step - 1))} disabled={step === 0}>back</button>
        <button
          className="btn"
          onClick={() => go(Math.min(STEPS.length - 1, step + 1))}
          disabled={step === STEPS.length - 1 || !canAdvance}
        >
          {step === 1 && !gameIds.length ? 'pick at least one game' : 'next'}
        </button>
        <span className="spacer" style={{ flex: 1 }} />
        {plan?.pick && (
          <button
            className="btn"
            onClick={() =>
              exportJson('rigcheck-build-plan.json', {
                request,
                pick: { total: plan.pick!.total, bom: plan.pick!.bom, notes: plan.pick!.notes },
                basis: plan.pickBasis,
                settings: plan.pick!.library.perGame.map((g) => ({
                  game: g.gameName,
                  recommendation: g.best?.label ?? 'target not reachable',
                  fps: g.best?.avgFps,
                  verdict: g.verdict,
                })),
                priceCaveat: `Prices are a recalled seed dated ${PRICE_AS_OF} and were never sourced. Verify before purchase.`,
              })
            }
          >
            export plan
          </button>
        )}
      </div>
    </>
  );
}

/* ------------------------------------------------------------------ build -- */

function BuildStep({
  plan,
  showAlt,
  setShowAlt,
  target,
}: {
  plan: ReturnType<typeof planBuild> | null;
  showAlt: boolean;
  setShowAlt: (b: boolean) => void;
  target: number;
}) {
  if (!plan) return <div className="empty">Pick at least one game and there will be a build to show.</div>;
  if (!plan.pick) {
    return (
      <div className="panel">
        <div className="panel-head"><h2>Nothing fits</h2></div>
        <div className="panel-body">
          {plan.warnings.map((w) => (
            <div key={w} className="note bad" style={{ marginBottom: 8 }}>{w}</div>
          ))}
          {plan.minimumViableTotal != null && (
            <p className="mini">
              Raise the budget to about £{Math.ceil(plan.minimumViableTotal / 25) * 25}, drop to a lower
              resolution, or switch to used parts — any of the three opens this up.
            </p>
          )}
        </div>
      </div>
    );
  }

  const shown = showAlt && plan.maxOut ? plan.maxOut : plan.pick;

  return (
    <>
      <div className="panel">
        <div className="panel-head">
          <h2>{showAlt ? 'The most your budget buys' : 'The build'}</h2>
          <span className="spacer" />
          {plan.maxOut && (
            <div className="toggle-row">
              <button className={`toggle${!showAlt ? ' on' : ''}`} onClick={() => setShowAlt(false)}>recommended £{plan.pick.total}</button>
              <button className={`toggle${showAlt ? ' on' : ''}`} onClick={() => setShowAlt(true)}>max out £{plan.maxOut.total}</button>
            </div>
          )}
        </div>
        <div className="panel-body">
          <div className="note" style={{ marginBottom: 12 }}>
            <b>{plan.pickBasis}</b>
            {plan.maxOutVerdict && (
              <>
                <br />
                {plan.maxOutVerdict}
              </>
            )}
          </div>

          <div className="stat-row" style={{ marginBottom: 14 }}>
            <div className="stat"><span className="v">£{shown.total}</span><span className="k">total</span></div>
            <div className="stat"><span className="v">{shown.medianFps.toFixed(0)}</span><span className="k">median fps</span></div>
            <div className="stat"><span className="v">{shown.typicalPreset}</span><span className="k">typical settings</span></div>
            <div className="stat"><span className="v">{Math.round(shown.targetHitRate * 100)}%</span><span className="k">games at {target}fps</span></div>
            <div className="stat"><span className="v">{shown.powerW.toFixed(0)}W</span><span className="k">sustained draw</span></div>
            <div className="stat"><span className="v">{shown.transientPeakW.toFixed(0)}W</span><span className="k">transient peak</span></div>
          </div>

          <div className="table-wrap">
            <table className="bom">
              <tbody>
                {shown.bom.map((l) => (
                  <tr key={l.category}>
                    <td className="cat">{l.category}</td>
                    <td className="part">
                      <b>{l.label}</b>
                      {l.allowance && <span className="tag" style={{ marginLeft: 6 }}>allowance</span>}
                      <div className="why">{l.rationale}</div>
                    </td>
                    <td className="money">£{l.price}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <td className="cat">total</td>
                  <td className="part sub" style={{ fontSize: 11 }}>
                    Lines marked <span className="tag">allowance</span> are a typical price for a competent
                    part in that class, not a specific product — this catalogue models CPUs and GPUs, not
                    motherboards and power supplies. They are sized from the build: the socket picks the
                    board, the CPU's memory support picks DDR4 or DDR5, the transient peak picks the PSU.
                  </td>
                  <td className="money">£{shown.total}</td>
                </tr>
              </tfoot>
            </table>
          </div>

          {shown.notes.map((n) => (
            <div key={n} className="note warn" style={{ marginTop: 10 }}>{n}</div>
          ))}
        </div>
      </div>

      {plan.candidates.length > 1 && (
        <div className="panel">
          <div className="panel-head"><h2>Other builds in this budget</h2></div>
          <div className="panel-body">
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr><th>build</th><th className="n">total</th><th className="n">median fps</th><th>settings</th><th className="n">at target</th><th className="n">balance</th></tr>
                </thead>
                <tbody>
                  {plan.candidates.map((c) => (
                    <tr key={c.build.id} style={c.build.id === plan.pick!.build.id ? { background: 'var(--surface-2)' } : undefined}>
                      <td>
                        {c.cpu.fullName} + {c.gpu.fullName}
                        {c.build.id === plan.pick!.build.id && <span className="tag good" style={{ marginLeft: 6 }}>pick</span>}
                      </td>
                      <td className="n mono">£{c.total}</td>
                      <td className="n mono">{c.medianFps.toFixed(0)}</td>
                      <td className="sub">{c.typicalPreset}</td>
                      <td className="n sub">{Math.round(c.targetHitRate * 100)}%</td>
                      <td className="n sub" title="1.00 means the CPU and GPU are evenly matched across your library">{c.balance.toFixed(2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="mini" style={{ marginTop: 8 }}>
              Balance is how evenly the two halves are matched across your library. A low number means one
              side is the limit almost everywhere, which is money in the wrong place — the classic version
              is a large graphics card starved by a small CPU.
            </p>
          </div>
        </div>
      )}
    </>
  );
}

/* --------------------------------------------------------------- settings -- */

function SettingsStep({
  plan,
  resolution,
}: {
  plan: ReturnType<typeof planBuild> | null;
  resolution: Resolution;
}) {
  const [open, setOpen] = useState<string | null>(null);
  if (!plan?.pick) return <div className="empty">There is no build yet, so there is nothing to configure.</div>;
  const lib = plan.pick.library;

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>What to set each game to</h2>
        <span className="spacer" />
        <span className="mini">{plan.pick.cpu.fullName} + {plan.pick.gpu.fullName} at {resolution}</span>
      </div>
      <div className="panel-body">
        <div className="note" style={{ marginBottom: 12 }}>{lib.summary}</div>

        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr><th>game</th><th>set it to</th><th className="n">fps</th><th className="n">1% low</th><th>why</th></tr>
            </thead>
            <tbody>
              {lib.perGame.map((g) => (
                <tr key={g.gameId} onClick={() => setOpen(open === g.gameId ? null : g.gameId)} style={{ cursor: 'pointer' }}>
                  <td>{g.gameName}</td>
                  <td className="mono" style={{ fontSize: 12, color: g.best ? 'var(--ink)' : 'var(--bad)' }}>
                    {g.best?.label ?? 'target not reachable'}
                  </td>
                  <td className="n mono">{g.best ? g.best.avgFps.toFixed(0) : g.fastest ? `${g.fastest.avgFps.toFixed(0)}*` : '—'}</td>
                  <td className="n sub">{g.best?.low1PctFps?.toFixed(0) ?? '—'}</td>
                  <td className="sub" style={{ fontSize: 11 }}>{open === g.gameId ? g.verdict : `${g.verdict.slice(0, 90)}…`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {open && (() => {
          const g = lib.perGame.find((x) => x.gameId === open);
          if (!g) return null;
          return (
            <div className="note" style={{ marginTop: 12 }}>
              <b>{g.gameName}</b> — {g.verdict}
              {g.options.length > 1 && (
                <div className="table-wrap" style={{ marginTop: 8 }}>
                  <table className="data">
                    <thead><tr><th>option</th><th>settings</th><th className="n">fps</th></tr></thead>
                    <tbody>
                      {g.options.map((o) => (
                        <tr key={o.label}>
                          <td className="sub">{o.role === 'pick' ? 'our pick' : o.role === 'one-step-prettier' ? 'a step prettier' : o.role === 'one-step-safer' ? 'a step smoother' : o.role === 'prettiest' ? 'maximum quality' : 'maximum frame rate'}</td>
                          <td className="mono" style={{ fontSize: 12 }}>{o.label}</td>
                          <td className="n mono">{o.avgFps.toFixed(0)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {g.notes.map((n) => (
                <p key={n} className="mini" style={{ marginTop: 6, marginBottom: 0 }}>{n}</p>
              ))}
              {g.frameGen && (
                <div className="note warn" style={{ marginTop: 8 }}>
                  <b>Frame generation</b> — {g.frameGen.caution} Latency verdict: {g.frameGen.verdict}.
                </div>
              )}
            </div>
          );
        })()}

        <p className="mini" style={{ marginTop: 10 }}>
          A <span className="mono">*</span> means the target is out of reach and the figure is the best the
          build manages. Click any row for the alternatives and the reasoning. Preset scaling is the
          least-evidenced part of this model — the multipliers are priors rather than fitted values, so
          treat these as directionally right and the exact frame rates as soft.
        </p>
      </div>
    </div>
  );
}
