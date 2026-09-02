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
import { useStickyState, clearSticky } from '../useStickyState.ts';
import { useDebounced } from '../useDebounced.ts';
import { planBuild, type ComponentPrices, type PlanRequest } from '../../core/planner.ts';
import { PRESETS, type Preset } from '../../core/presets.ts';
import { DEFAULT_USAGE, PSU_EFFICIENCY, efficiencyPayback, runningCost, totalCostOfOwnership, type PsuTier, type UsageProfile } from '../../core/running.ts';
import { exportJson } from '../export.ts';
import { findObserved, loadPrices, plannerTables, priceCoverage } from '../pricing.ts';
import { changeOverall, describeChange, monthLabel } from '../../core/pricetrend.ts';
import componentPrices from '../../../data/pricing/components-gbp.json';
import monitorsJson from '../../../data/catalogue/monitors.json';
import type { MonitorRecord } from '../../core/fit.ts';
import type { Resolution } from '../../core/types.ts';

const monitors = (monitorsJson as { records: MonitorRecord[] }).records;
const comp = componentPrices as unknown as ComponentPrices;
// Observed market data merged over the recalled seed. A part someone has
// actually sourced beats a part the model remembers.


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
  // Loaded inside the component, not at module scope. At module scope it ran
  // once per page load and never again, so a price entered in the Data Explorer
  // did not reach this screen until the tab was reloaded — which, on a tool
  // whose whole point is that you can improve its data from inside it, reads as
  // the entry having silently failed.
  const { prices, priceTables, coverage, PRICE_AS_OF } = useMemo(() => {
    const p = loadPrices();
    return {
      prices: p,
      priceTables: plannerTables(p),
      coverage: priceCoverage(p),
      PRICE_AS_OF: p.newP.updated ?? 'unknown',
    };
  }, []);
  const { data } = useApp();
  // Both are indexes into STEPS. A restored value outside that range rendered
  // the rail with nothing active and no panel at all — a blank screen with no
  // explanation, which is the worst thing persistence can do.
  const inRange = (v: unknown) => typeof v === 'number' && Number.isInteger(v) && v >= 0 && v < STEPS.length;
  const [step, setStep] = useStickyState('wiz.step', 0, inRange);
  const [reached, setReached] = useStickyState('wiz.reached', 0, inRange);

  const [resolution, setResolution] = useStickyState<Resolution>('wiz.resolution', '1440p');
  const [refreshHz, setRefreshHz] = useStickyState('wiz.refreshHz', 144);
  const [monitorId, setMonitorId] = useStickyState<string>('wiz.monitorId', '');
  const [gameIds, setGameIds] = useStickyState<string[]>('wiz.gameIds', [], (v) => Array.isArray(v));
  const [gameQuery, setGameQuery] = useStickyState('wiz.gameQuery', '');
  const [budget, setBudget] = useStickyState('wiz.budget', 1200);
  /* The slider stays on `budget` so the handle and its label track the finger.
     The planner reads the settled value, because re-planning on every one of
     the slider's 145 stops froze the page for seconds at a time. */
  const plannedBudget = useDebounced(budget, 200);
  const [noBudget, setNoBudget] = useStickyState('wiz.noBudget', false);
  const [condition, setCondition] = useStickyState<'new' | 'used' | 'either'>('wiz.condition', 'new');
  const [minPreset, setMinPreset] = useStickyState<Preset>('wiz.minPreset', 'high');
  const [targetFps, setTargetFps] = useStickyState<number | null>('wiz.targetFps', null);
  const [preferQuiet, setPreferQuiet] = useStickyState('wiz.preferQuiet', false);
  const [ramGB, setRamGB] = useStickyState<number | null>('wiz.ramGB', null);
  const [storageGB, setStorageGB] = useStickyState('wiz.storageGB', 1000);
  const [showAlt, setShowAlt] = useState(false);
  const [usage, setUsage] = useStickyState<UsageProfile>('wiz.usage', DEFAULT_USAGE);
  const [psuTier, setPsuTier] = useStickyState<PsuTier>('wiz.psuTier', 'gold');

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
      budget: noBudget ? undefined : plannedBudget,
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
    [plannedBudget, noBudget, condition, resolution, refreshHz, gameIds, targetFps, minPreset, preferQuiet, ramGB, storageGB],
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
      <div className="print-only" style={{ marginBottom: 12, borderBottom: '1px solid #bbb', paddingBottom: 8 }}>
        <b style={{ fontSize: 16 }}>RIGCHECK build sheet</b>
        <div style={{ fontSize: 11, color: '#555', marginTop: 3 }}>
          {resolution} at {refreshHz}Hz · {gameIds.length} game{gameIds.length === 1 ? '' : 's'} ·{' '}
          {noBudget ? 'no budget limit' : `£${budget} budget`} · {condition} parts · printed {new Date().toISOString().slice(0, 10)}
        </div>
        <div style={{ fontSize: 10, color: '#555', marginTop: 4 }}>
          Prices are estimates and were not sourced from a retailer — check each one before buying.
          Frame rates come from a model, not from measurements of this exact build.
        </div>
      </div>

      <div className="page-head no-print">
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
                    <button
                      key={f}
                      className={`toggle${targetFps === f ? ' on' : ''}`}
                      aria-label={`Target ${f} frames per second`}
                      aria-pressed={targetFps === f}
                      onClick={() => setTargetFps(f)}
                    >
                      {f}
                    </button>
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
            {coverage.sourced === 0 ? (
              <div className="note bad" style={{ marginBottom: 14 }}>
                <b>Every price here is a recalled seed, not live data.</b> They date from {PRICE_AS_OF} and
                were never sourced from a retailer or a marketplace. Graphics card pricing moves 30% on
                supply alone, so treat each figure as an order of magnitude rather than a number.
                <br />
                <br />
                To replace them with something real: put marketplace observations in{' '}
                <span className="mono">data/prices-observed/</span> and run{' '}
                <span className="mono">npm run import:prices</span>. The README there explains how to
                pull <b>sold</b> prices rather than asking prices, which is the difference between a
                useful figure and a misleading one — the listings easiest to find are the ones nobody
                bought.
              </div>
            ) : (
              <div className="note warn" style={{ marginBottom: 14 }}>
                <b>
                  {coverage.sourced} of {coverage.seeded} priced parts are sourced from real market
                  observations ({Math.round(coverage.sourcedShare * 100)}%).
                </b>{' '}
                The rest still run on a recalled seed dated {PRICE_AS_OF} and are marked{' '}
                <span className="tag">recalled</span> in the parts list below.
                {coverage.staleCount > 0 && ` ${coverage.staleCount} sourced price(s) are over 90 days old and marked stale.`}{' '}
                Add more in <span className="mono">data/prices-observed/</span> and re-run{' '}
                <span className="mono">npm run import:prices</span>.
              </div>
            )}

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
      {step === 3 && (
        <BuildStep
          prices={prices}
          priceAsOf={PRICE_AS_OF}
          plan={plan}
          showAlt={showAlt}
          setShowAlt={setShowAlt}
          target={target}
          usage={usage}
          setUsage={setUsage}
          psuTier={psuTier}
          setPsuTier={setPsuTier}
        />
      )}

      {/* -------------------------------------------------------- settings -- */}
      {step === 4 && <SettingsStep plan={plan} resolution={resolution} />}

      <div className="wizard-nav">
        <button className="btn" onClick={() => go(Math.max(0, step - 1))} disabled={step === 0}>back</button>
        <button
          className="btn primary"
          onClick={() => go(Math.min(STEPS.length - 1, step + 1))}
          disabled={step === STEPS.length - 1 || !canAdvance}
        >
          {step === 1 && !gameIds.length ? 'pick at least one game' : 'next'}
        </button>
        <span className="spacer" style={{ flex: 1 }} />
        {/* Answers now persist across navigation and reload, which needs a way
            out: without one, a wizard that remembers is a wizard you cannot
            start over. */}
        <button
          className="btn"
          title="Forget every answer and start from the first step"
          onClick={() => {
            clearSticky('wiz.');
            setStep(0); setReached(0);
            setResolution('1440p'); setRefreshHz(144); setMonitorId('');
            setGameIds([]); setGameQuery('');
            setBudget(1200); setNoBudget(false); setCondition('new');
            setMinPreset('high'); setTargetFps(null); setPreferQuiet(false);
            setRamGB(null); setStorageGB(1000);
            setUsage(DEFAULT_USAGE); setPsuTier('gold');
          }}
        >
          start again
        </button>
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
        {plan?.pick && (
          <button className="btn" onClick={() => window.print()} title="Prints the parts list, prices and per-game settings on white, with the navigation and controls removed">
            print the build sheet
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
  usage,
  setUsage,
  psuTier,
  setPsuTier,
  prices,
  priceAsOf,
}: {
  plan: ReturnType<typeof planBuild> | null;
  showAlt: boolean;
  setShowAlt: (b: boolean) => void;
  target: number;
  usage: UsageProfile;
  setUsage: (u: UsageProfile) => void;
  psuTier: PsuTier;
  setPsuTier: (t: PsuTier) => void;
  // Passed down rather than read from module scope, so a price entered during
  // this session reaches the build sheet without a reload.
  prices: ReturnType<typeof loadPrices>;
  priceAsOf: string;
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
                      {!l.allowance && l.partId && <PriceOrigin id={l.partId} prices={prices} asOf={priceAsOf} />}
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

      <RunningCostPanel build={shown} usage={usage} setUsage={setUsage} psuTier={psuTier} setPsuTier={setPsuTier} />

      {plan.candidates.length > 1 && (
        <div className="panel no-print">
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

/* ------------------------------------------------------------ provenance -- */

/**
 * Where a part's price came from, next to the price.
 *
 * A recalled figure and a figure drawn from fourteen completed sales are not
 * the same kind of thing, and a build sheet that renders them identically
 * invites someone to plan a purchase around a number nobody checked. Sourced
 * prices carry their sample size and age; recalled ones say so plainly.
 */
function PriceOrigin({ id, prices, asOf, condition = 'new' }: { id: string; prices: ReturnType<typeof loadPrices>; asOf: string; condition?: 'new' | 'used' }) {
  // The bill of materials is priced NEW, so the tag looks up the new price and
  // says so. It used to look up a used observation first, which would have put
  // "sourced · 1 sale" beside a recalled new figure the moment a used price for
  // the same part was recorded — the i7-7700 at £40 would have done exactly that.
  const mine = prices.override[condition][id];
  if (mine != null) {
    return (
      <span className="tag good" style={{ marginLeft: 6 }} title={`A ${condition} price you entered yourself. It beats every other source here.`}>
        yours · {condition}
      </span>
    );
  }
  const o = findObserved(prices.observed, id, condition);
  if (!o) {
    const other = findObserved(prices.observed, id, condition === 'new' ? 'used' : 'new');
    return (
      <span
        className="tag"
        style={{ marginLeft: 6 }}
        title={`No market observation for this part as ${condition} — the figure is the recalled seed dated ${asOf}, which was never sourced.${other ? ` A ${other.condition} price is on record (£${other.price}, ${other.newestDate}), but this line is priced ${condition}.` : ''} Add one with npm run price, or under "add your own data" in the Data Explorer.`}
      >
        recalled · {condition}
      </span>
    );
  }
  const detail =
    `${o.observations} observation${o.observations === 1 ? '' : 's'} covering ${o.totalSamples} sale${o.totalSamples === 1 ? '' : 's'} ` +
    `from ${o.sources.join(', ')}, newest ${o.newestDate} (${o.ageDays} days ago). ` +
    `Observed range ${o.spread.low}–${o.spread.high}.` +
    (o.warnings.length ? ` ${o.warnings.join(' ')}` : '');
  // Movement, when there is more than one snapshot to read it from. Measured
  // from the first observation on record, and named by that month, so the tag
  // says "down 12% since Aug" and the caption verifier can check the same thing.
  const move = changeOverall(o.series ?? []);
  const trend = move && move.from.date.slice(0, 7) !== move.to.date.slice(0, 7)
    ? ` · ${describeChange(move, monthLabel(move.from.date.slice(0, 7), true))}`
    : '';
  return (
    <span
      className={`tag ${o.stale || o.containsAsking || o.totalSamples < 5 ? 'bad' : 'good'}`}
      style={{ marginLeft: 6 }}
      title={detail + (move ? ` Watched since ${o.firstDate}: ${describeChange(move, monthLabel(move.from.date.slice(0, 7)))}.` : '')}
    >
      {o.stale ? `sourced · ${condition} · ${o.ageDays}d old` : o.containsAsking ? `sourced · ${condition} · asking` : `sourced · ${condition} · ${o.totalSamples} sale${o.totalSamples === 1 ? '' : 's'}`}{trend}
    </span>
  );
}

/* ----------------------------------------------------------- running cost -- */

/**
 * What the machine costs to own, not to buy.
 *
 * The price is the number everyone compares and it is not the number that
 * decides what a machine costs. Two builds within 50 of each other on price can
 * be 200 apart across four years, and until this panel existed nothing here
 * said so. The power supply's own losses are broken out separately, because
 * that is what turns an efficiency rating from a specification into a purchase
 * decision.
 */
function RunningCostPanel({
  build,
  usage,
  setUsage,
  psuTier,
  setPsuTier,
}: {
  build: { total: number; powerW: number; bom: { category: string; label: string }[] };
  usage: UsageProfile;
  setUsage: (u: UsageProfile) => void;
  psuTier: PsuTier;
  setPsuTier: (t: PsuTier) => void;
}) {
  const psuLine = build.bom.find((l) => l.category === 'PSU');
  const psuWatts = Number(/(\d+)W/.exec(psuLine?.label ?? '')?.[1] ?? 750);
  const running = runningCost(build.powerW, psuWatts, psuTier, usage);
  const tco = totalCostOfOwnership(build.total, running, usage);

  // Is the next tier up worth buying? The premium is a typical retail figure the
  // user can type over, because the honest answer depends on the two specific
  // units in front of them rather than on the badge.
  const tiers = Object.keys(PSU_EFFICIENCY) as PsuTier[];
  const nextTier = tiers[tiers.indexOf(psuTier) + 1] ?? null;
  const premiumKey = nextTier ? `${psuTier}->${nextTier}` : null;
  const defaultPremium = premiumKey
    ? Number((componentPrices.psuTierPremium as Record<string, unknown>)[premiumKey] ?? 20)
    : 0;
  // Keyed by the tier step, not held globally: a price typed in for
  // gold -> platinum is meaningless for unrated -> bronze, and carrying it
  // across produced a straight-faced "80+ Bronze costs £900 more".
  const [premiums, setPremiums] = useState<Record<string, number>>({});
  const usedPremium = premiumKey != null ? (premiums[premiumKey] ?? defaultPremium) : defaultPremium;
  const payback = nextTier
    ? efficiencyPayback(build.powerW, psuWatts, psuTier, nextTier, usedPremium, usage)
    : null;

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>What it costs to run</h2>
        <span className="spacer" />
        <span className="mini">{psuWatts}W supply at {(running.loadFraction * 100).toFixed(0)}% load</span>
      </div>
      <div className="panel-body">
        <div className="stat-row" style={{ marginBottom: 14 }}>
          <div className="stat"><span className="v">{running.loadWallW.toFixed(0)}W</span><span className="k">from the wall, gaming</span></div>
          <div className="stat"><span className="v">{running.idleWallW.toFixed(0)}W</span><span className="k">from the wall, idle</span></div>
          <div className="stat"><span className="v">{(running.efficiencyAtLoad * 100).toFixed(0)}%</span><span className="k">PSU efficiency at load</span></div>
          <div className="stat"><span className="v">£{running.costPerYear.toFixed(0)}</span><span className="k">a year</span></div>
          <div className="stat"><span className="v">£{tco.total.toFixed(0)}</span><span className="k">total over {usage.ownershipYears} years</span></div>
        </div>

        <div className="note" style={{ marginBottom: 12 }}>{tco.verdict}</div>

        <p className="mini" style={{ marginTop: 0 }}>
          The wall figure is higher than the component draw because a power supply is not free: at{' '}
          {build.powerW.toFixed(0)}W of components this one pulls {running.loadWallW.toFixed(0)}W,
          and the difference is heat. That is the entire reason efficiency ratings exist, and it is
          what a "how much does my PC cost to run" sum usually leaves out.
        </p>

        {running.notes.map((n) => (
          <div key={n} className="note warn" style={{ marginTop: 8 }}>{n}</div>
        ))}

        <div className="grid three no-print" style={{ marginTop: 14 }}>
          <div className="field">
            <label>gaming hours a week</label>
            <input type="number" min={0} max={168} value={usage.gamingHoursPerWeek}
              onChange={(e) => setUsage({ ...usage, gamingHoursPerWeek: Math.max(0, Number(e.target.value)) })} />
          </div>
          <div className="field">
            <label>idle hours a week</label>
            <input type="number" min={0} max={168} value={usage.idleHoursPerWeek}
              onChange={(e) => setUsage({ ...usage, idleHoursPerWeek: Math.max(0, Number(e.target.value)) })} />
            <span className="mini">Powered on but not playing. Most machines spend more time here than gaming.</span>
          </div>
          <div className="field">
            <label>electricity, pence per kWh</label>
            <input type="number" min={0} step={0.1} value={usage.pencePerKwh}
              onChange={(e) => setUsage({ ...usage, pencePerKwh: Math.max(0, Number(e.target.value)) })} />
            <span className="mini">On your bill as the unit rate. The default is a UK average and yours will differ.</span>
          </div>
          <div className="field">
            <label>years you will keep it</label>
            <div className="toggle-row">
              {[2, 3, 4, 6].map((y) => (
                <button key={y} className={`toggle${usage.ownershipYears === y ? ' on' : ''}`} onClick={() => setUsage({ ...usage, ownershipYears: y })}>{y}</button>
              ))}
            </div>
          </div>
          <div className="field" style={{ gridColumn: 'span 2' }}>
            <label>power supply efficiency rating</label>
            <div className="toggle-row">
              {(Object.keys(PSU_EFFICIENCY) as PsuTier[]).map((t) => (
                <button key={t} className={`toggle${psuTier === t ? ' on' : ''}`} onClick={() => setPsuTier(t)}>
                  {PSU_EFFICIENCY[t].label}
                </button>
              ))}
            </div>
            <span className="mini">
              Efficiency depends on load as well as on the badge — the 80 Plus tiers are specified at
              20%, 50% and 100% of rated output and the curve sags at both ends. An oversized supply
              spends its life at the wrong end of it, which is the honest counterweight to buying
              headroom for transient spikes.
            </span>
          </div>
        </div>

        {payback && nextTier && (
          <div className="panel sub-panel" style={{ marginTop: 14 }}>
            <div className="panel-head">
              <h3>Is {PSU_EFFICIENCY[nextTier].label} worth the extra?</h3>
              <span className="spacer" />
              <span className={`tag ${payback.worthIt ? 'good' : ''}`}>
                {payback.worthIt ? 'pays for itself' : 'does not pay for itself'}
              </span>
            </div>
            <div className="panel-body">
              <div className="stat-row" style={{ marginBottom: 12 }}>
                <div className="stat">
                  <span className="v">£{payback.savingOverOwnership.toFixed(0)}</span>
                  <span className="k">saved over {usage.ownershipYears} years</span>
                </div>
                <div className="stat">
                  <span className="v">
                    {payback.paybackYears == null ? '—' : `${payback.paybackYears.toFixed(1)}y`}
                  </span>
                  <span className="k">to break even</span>
                </div>
                <div className="stat">
                  <span className="v">£{usedPremium}</span>
                  <span className="k">assumed premium</span>
                </div>
              </div>

              <div className="note" style={{ marginBottom: 12 }}>{payback.detail}</div>

              <div className="field no-print" style={{ maxWidth: 260 }}>
                <label>what the upgrade actually costs you, £</label>
                <input
                  type="number"
                  min={0}
                  step={1}
                  value={usedPremium}
                  onChange={(e) =>
                    premiumKey &&
                    setPremiums({ ...premiums, [premiumKey]: Math.max(0, Number(e.target.value)) })
                  }
                />
                <span className="mini">
                  The default is a typical retail step at this wattage. Put your own two prices in —
                  the answer swings entirely on this number, and a sale price can flip it.
                </span>
              </div>

              <p className="mini" style={{ marginTop: 10 }}>
                This compares electricity only. Higher tiers usually also bring better capacitors, a
                quieter fan and a longer warranty, none of which show up in the sum above — so a
                "does not pay for itself" verdict is a reason not to buy it <em>for the running
                cost</em>, not a reason to avoid it.
              </p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
