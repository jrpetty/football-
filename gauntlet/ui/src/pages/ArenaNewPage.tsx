/** New Arena tournament: game, models, format, spending cap — with a live cost estimate and bracket preview. */
import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { api } from '../api.ts';
import { useAsync, useDebounced } from '../hooks.ts';
import { Link, navigate, pathOf } from '../router.tsx';
import { useToast, useViewerCaption } from '../context.tsx';
import { Callout, ErrorState, Field, LoadingPage, PageHead, Seg, Switch, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtCost, fmtInt, fmtPricePerM } from '../format.ts';
import { arenaApi } from '../arena/client.ts';
import type { ArenaEstimate, ArenaFormat, ArenaRequest, ArenaSeeding } from '../arena/types.ts';
import { GameGlyph } from '../arena/ArenaIcon.tsx';
import '../arena/arena.css';
import '../arena/formats.css';

export default function ArenaNewPage() {
  const toast = useToast();
  const games = useAsync(() => arenaApi.games(), []);
  const cons = useAsync(() => api.contestants(), []);
  const [gameId, setGameId] = useState('connect4');
  const [picked, setPicked] = useState<string[]>([]);
  const [format, setFormat] = useState<ArenaFormat>('knockout');
  const [gpm, setGpm] = useState('2');
  const [seeding, setSeeding] = useState<ArenaSeeding>('index');
  const [capText, setCapText] = useState('');
  const [capTouched, setCapTouched] = useState(false);
  const [name, setName] = useState('');
  const [concurrency, setConcurrency] = useState(2);
  const [legal, setLegal] = useState(true);
  const [maxPlies, setMaxPlies] = useState<number | null>(null);
  const [opts, setOpts] = useState<Record<string, string>>({});
  const [est, setEst] = useState<ArenaEstimate | null>(null);
  const [estErr, setEstErr] = useState<string | null>(null);
  const [estLoading, setEstLoading] = useState(false);
  const [starting, setStarting] = useState(false);
  useViewerCaption('Setting up a head-to-head tournament: pick a game and the models, and Gauntlet shows the bracket and what it will cost before anything is spent.');

  const enabled = useMemo(() => (cons.data ?? []).filter((c) => c.enabled), [cons.data]);
  const game = games.data?.find((g) => g.id === gameId);
  const engine = game?.engine ?? 'board';
  const gpmChoices = game?.gamesPerMatchOptions ?? [2, 4, 6];
  const gameOpts = game?.options ?? [];
  const optionValues = Object.fromEntries(gameOpts.map((o) => [o.key, opts[o.key] ?? o.default]));
  const pickGame = (id: string) => {
    const g = games.data?.find((x) => x.id === id);
    setGameId(id);
    setMaxPlies(null);
    setOpts({});
    const choices = g?.gamesPerMatchOptions ?? [2, 4, 6];
    if (!choices.includes(Number(gpm))) setGpm(String(choices[0]));
  };
  const cap = capText.trim() === '' ? null : Number(capText);
  const capValid = cap === null || (Number.isFinite(cap) && cap > 0);

  const req: ArenaRequest | null = picked.length >= 2 && capValid
    ? {
        game: gameId,
        contestantIds: picked,
        format,
        gamesPerMatch: gpmChoices.includes(Number(gpm)) ? Number(gpm) : gpmChoices[0],
        seeding,
        maxCostUsd: cap ?? undefined,
        name: name.trim() || undefined,
        concurrency,
        ...(engine === 'board' ? { listLegalMoves: legal, maxPlies: maxPlies ?? undefined } : {}),
        ...(gameOpts.length ? { options: optionValues } : {}),
      }
    : null;
  const reqKey = useDebounced(JSON.stringify(req), 250);

  useEffect(() => {
    if (!reqKey || reqKey === 'null') {
      setEst(null);
      setEstErr(null);
      return;
    }
    let alive = true;
    setEstLoading(true);
    arenaApi
      .estimate(JSON.parse(reqKey) as ArenaRequest)
      .then((e) => {
        if (!alive) return;
        setEst(e);
        setEstErr(null);
        // Prefill the cap at ~1.25× the estimate until the user edits it.
        if (!capTouched && e.estCostUsd > 0) setCapText(String(Math.max(0.5, Math.ceil(e.estCostUsd * 1.25 * 100) / 100)));
      })
      .catch((err: unknown) => alive && setEstErr((err as Error).message))
      .finally(() => alive && setEstLoading(false));
    return () => {
      alive = false;
    };
  }, [reqKey, capTouched]);

  const toggle = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : p.length >= 16 ? p : [...p, id]));
  const labelOf = (id: string) => enabled.find((c) => c.id === id)?.label ?? id;
  const colorOf = (id: string) => enabled.find((c) => c.id === id)?.color ?? 'var(--text-3)';

  const start = async () => {
    if (!req) return;
    setStarting(true);
    try {
      const { tournamentId } = await arenaApi.start(req);
      toast.success('Tournament started');
      navigate(pathOf('arena', tournamentId));
    } catch (e) {
      toast.error(e);
    } finally {
      setStarting(false);
    }
  };

  if (games.error || cons.error) return <div className="page"><ErrorState error={games.error ?? cons.error} onRetry={() => (games.reload(), cons.reload())} /></div>;
  if (!games.data || !cons.data) return <LoadingPage />;

  const r1 = est?.matches.filter((m) => m.round === 1) ?? [];
  const seedOf = new Map((est?.entrants ?? []).map((e) => [e.id, e]));
  const maxGame = Math.max(0.0001, ...(est?.perContestant.map((p) => p.perGameUsd) ?? [0]));

  return (
    <div className="page arena-new">
      <PageHead eyebrow={<Link to="/arena">The Arena</Link>} title="New tournament" sub="Two models per game, both sides played, every move checked by the harness." />
      <div className="grid split-8-4 newrun">
        <div className="stack loose">
          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>1 · Game</h2>
                <div className="desc">Same rules, same prompt format and the same seed for every pairing.</div>
              </div>
            </div>
            <div className="card-body">
              <div className="ar-games" role="radiogroup" aria-label="Game">
                {games.data.map((g) => (
                  <button key={g.id} type="button" role="radio" aria-checked={g.id === gameId} className={cx('ar-game', g.id === gameId && 'on')} onClick={() => pickGame(g.id)}>
                    <GameGlyph gameId={g.id} />
                    <span className="stack tight" style={{ textAlign: 'left' }}>
                      <strong>{g.name}</strong>
                      <span className="muted">{g.tagline}</span>
                      <span className="ar-game-desc">{g.description}</span>
                      <span className="row wrap" style={{ gap: 6, marginTop: 4 }}>
                        <span className="badge outline">v{g.version}</span>
                        {g.engine === 'turns' ? (
                          <>
                            <span className="badge">hidden cards · duplicate deals</span>
                            <span className="badge">blinds 1/2 · 200 chips</span>
                          </>
                        ) : g.engine === 'debate' ? (
                          <>
                            <span className="badge">3 rounds · 180/150/120 words</span>
                            <span className="badge">blind judge panel</span>
                          </>
                        ) : (
                          <>
                            <span className="badge">~{g.estimate.pliesPerGame} moves per game</span>
                            <span className="badge">cap {g.defaults.maxPlies} moves</span>
                          </>
                        )}
                      </span>
                    </span>
                  </button>
                ))}
              </div>
              {game && (
                <details className="collapse" style={{ marginTop: 12 }}>
                  <summary>Rules sent to the models</summary>
                  <div className="inner">
                    <pre className="ar-rules">{game.rules}</pre>
                    <p className="muted" style={{ fontSize: '0.84rem' }}>
                      {engine === 'debate' ? `Each move is ${game.moveHelp}. ` : `Moves are written as ${game.moveHelp}. `}
                      {game.capRule}
                    </p>
                  </div>
                </details>
              )}
            </div>
          </section>

          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>2 · Models</h2>
                <div className="desc">{picked.length} selected · 4, 8 or 16 make a full bracket (other sizes give the top seeds a bye).</div>
              </div>
              <div className="tools">
                <button className="btn sm ghost" onClick={() => setPicked([])} disabled={!picked.length}>
                  Clear
                </button>
              </div>
            </div>
            <div className="card-body">
              <div className="con-grid">
                {enabled.map((c) => {
                  const on = picked.includes(c.id);
                  const manual = c.providerType === 'manual';
                  return (
                    <label key={c.id} className={cx('con-card', on && 'on')} style={{ ['--c' as string]: c.color } as CSSProperties}>
                      <input type="checkbox" checked={on} onChange={() => toggle(c.id)} />
                      <div className="con-main">
                        <div className="row" style={{ gap: 8 }}>
                          <strong className="ellipsis">{c.label}</strong>
                          {on && seeding === 'manual' && <span className="badge info tnum">#{picked.indexOf(c.id) + 1}</span>}
                        </div>
                        <div className="muted ellipsis" style={{ fontSize: '0.78rem' }}>
                          {c.vendor}
                        </div>
                        <div className="row wrap" style={{ gap: 5, marginTop: 4 }}>
                          {manual ? (
                            <span className="badge info">
                              <Icon.Copy /> manual · copy &amp; paste
                            </span>
                          ) : (
                            <span className="badge outline tnum" title="USD per 1M input / output tokens">
                              {fmtPricePerM(c.pricing?.inputPerM)} / {fmtPricePerM(c.pricing?.outputPerM)}
                            </span>
                          )}
                          {!c.hasKey && !manual && c.providerType !== 'mock' && (
                            <span className="badge bad">
                              <Icon.Key /> no API key
                            </span>
                          )}
                        </div>
                      </div>
                    </label>
                  );
                })}
              </div>
              {picked.some((id) => enabled.find((c) => c.id === id)?.providerType === 'manual') && (
                <div style={{ marginTop: 12 }}>
                  <Callout tone="info" icon={<Icon.Inbox />}>
                    Manual models play by copy &amp; paste: every move appears in the <Link to="/inbox">Manual Inbox</Link>. Their games wait for you; other games keep going.
                  </Callout>
                </div>
              )}
            </div>
          </section>

          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>3 · Format</h2>
                <div className="desc">Every pairing is a mini-match with the sides swapped, so nobody gets an unfair first move.</div>
              </div>
            </div>
            <div className="card-body">
              <div className="form-grid">
                <Field
                  label="Format"
                  hint={
                    format === 'knockout'
                      ? engine === 'turns'
                        ? 'Lose a match and you are out. Level on chips: fewer illegal actions, then lower cost.'
                        : 'Lose a match and you are out. Level matches go to sudden death.'
                      : engine === 'turns'
                        ? 'Everyone plays everyone; most chips wins.'
                        : 'Everyone plays everyone; most points wins.'
                  }
                >
                  <Seg label="Format" value={format} onChange={setFormat} options={[{ value: 'knockout', label: 'Knockout' }, { value: 'round-robin', label: 'Round-robin' }]} />
                </Field>
                {gpmChoices.length > 1 ? (
                  <Field label="Games per pairing" hint="Sides swap every game.">
                    <Seg label="Games per pairing" value={gpm} onChange={setGpm} options={gpmChoices.map((v) => ({ value: String(v), label: String(v) }))} />
                  </Field>
                ) : (
                  <Field label="Games per pairing" hint={engine === 'turns' ? 'The same deals twice, cards swapped.' : 'Both models argue both sides.'}>
                    <div className="ar-fixed">{gpmChoices[0]} games · sides swapped</div>
                  </Field>
                )}
                {gameOpts.map((o) => (
                  <Field key={o.key} label={o.label} hint={o.choices.find((c) => c.value === optionValues[o.key])?.hint ?? o.hint} className={o.choices.length > 5 ? 'span-2' : undefined} htmlFor={`ar-opt-${o.key}`}>
                    {o.choices.length <= 5 ? (
                      <Seg label={o.label} value={optionValues[o.key]!} onChange={(v) => setOpts((x) => ({ ...x, [o.key]: v }))} options={o.choices.map((c) => ({ value: c.value, label: c.label }))} />
                    ) : (
                      <select id={`ar-opt-${o.key}`} className="select" value={optionValues[o.key]} onChange={(e) => setOpts((x) => ({ ...x, [o.key]: e.target.value }))}>
                        {o.choices.map((c) => (
                          <option key={c.value} value={c.value}>
                            {c.label}
                          </option>
                        ))}
                      </select>
                    )}
                  </Field>
                ))}
                {engine === 'turns' && (
                  <div className="span-2">
                    <Callout tone="info" icon={<span aria-hidden="true">♠</span>}>
                      <b>Each deal is played twice with cards swapped, so luck cancels out.</b> Game 1 and game 2 of every pairing use the same shuffled decks with the seats swapped; the match goes to whoever wins more chips in total. Blinds 1/2, 200-chip stacks reset every hand.
                    </Callout>
                  </div>
                )}
                {engine === 'debate' && (
                  <div className="span-2">
                    <Callout tone="info" icon={<span aria-hidden="true">⚖</span>}>
                      <b>Judged blind by other AI companies.</b> The judges in Settings read each transcript as “Side A” and “Side B” (random order, model names removed) and never judge a debater from their own vendor. With no judge available, games wait for you on the human judging screen.
                    </Callout>
                  </div>
                )}
                <Field label="Seeding" hint={seeding === 'index' ? 'By current Gauntlet Index: the top two can only meet in the final.' : 'In the order you ticked the models (#1 = top seed).'}>
                  <Seg label="Seeding" value={seeding} onChange={setSeeding} options={[{ value: 'index', label: 'Gauntlet Index' }, { value: 'manual', label: 'My order' }]} />
                </Field>
                <Field
                  label={
                    <>
                      <Icon.Dollar style={{ width: 13, height: 13 }} /> Spending cap (USD)
                    </>
                  }
                  hint={capTouched ? 'Hard stop, checked before every move. Empty = no cap.' : 'Prefilled at ~1.25× the estimate.'}
                  error={!capValid ? 'Enter a positive number, or leave empty.' : undefined}
                  htmlFor="ar-cap"
                >
                  <div className="input-prefix">
                    <span>$</span>
                    <input id="ar-cap" className={cx('input tnum', !capValid && 'invalid')} inputMode="decimal" placeholder="no cap" value={capText} onChange={(e) => (setCapTouched(true), setCapText(e.target.value))} />
                  </div>
                </Field>
                <Field label="Games at once" hint="Parallel games. 1 is easiest to follow live on video." htmlFor="ar-conc">
                  <input id="ar-conc" className="input" type="number" min={1} max={16} value={concurrency} onChange={(e) => setConcurrency(Math.max(1, Math.min(16, Number(e.target.value) || 1)))} />
                </Field>
                {engine === 'board' && (
                  <>
                    <Field label="Move cap" hint={game?.capRule} htmlFor="ar-plies">
                      <input id="ar-plies" className="input" type="number" min={2} max={1000} value={maxPlies ?? game?.defaults.maxPlies ?? ''} onChange={(e) => setMaxPlies(Math.max(2, Math.min(1000, Number(e.target.value) || 2)))} />
                    </Field>
                    <Field label="List the legal moves in each prompt" hint="On: models pick from the list (and the Random Baseline can play). Off: harder — models must find legal moves themselves." className="span-2">
                      <Switch checked={legal} onChange={setLegal} label="List legal moves" />
                    </Field>
                  </>
                )}
                <Field label="Name" htmlFor="ar-name" className="span-2">
                  <input id="ar-name" className="input" placeholder={`${game?.name ?? 'Arena'} ${format === 'knockout' ? 'knockout' : 'round-robin'} · ${new Date().toLocaleDateString('en-GB', { month: 'long', year: 'numeric' })}`} value={name} onChange={(e) => setName(e.target.value)} />
                </Field>
              </div>
            </div>
          </section>
        </div>

        <div className="sticky-col">
          <aside className="card estimate-panel" aria-live="polite" aria-busy={estLoading}>
            <div className="card-head">
              <div className="t">
                <h2>Cost estimate</h2>
                <div className="desc">Before anything is spent.</div>
              </div>
              {estLoading && <span className="spinner" aria-label="Updating estimate" />}
            </div>
            <div className="card-body stack">
              {picked.length < 2 ? (
                <div className="muted">Pick at least two models to see the bracket and the cost.</div>
              ) : estErr ? (
                <Callout tone="bad">{estErr}</Callout>
              ) : est ? (
                <div className={cx('stack', estLoading && 'refetching')}>
                  <div className="est-hero">
                    <span className="est-range tnum">
                      {fmtCost(est.estCostUsd)}
                      <span className="est-dash">–</span>
                      {fmtCost(est.estCostUsdHigh)}
                    </span>
                    <span className="muted" style={{ fontSize: '0.8rem' }}>
                      central estimate – conservative upper bound (includes every possible sudden-death game)
                    </span>
                  </div>
                  <div className="est-kpis">
                    <div>
                      <b className="tnum">{fmtInt(est.games)}</b>
                      <span>games</span>
                    </div>
                    <div>
                      <b className="tnum">~{fmtInt(est.moves)}</b>
                      <span>{engine === 'turns' ? 'decisions' : engine === 'debate' ? 'speeches' : 'moves'}</span>
                    </div>
                    <div>
                      <b className="tnum">{cap && capValid ? fmtCost(cap) : '—'}</b>
                      <span>cap</span>
                    </div>
                  </div>
                  <div className="est-rows">
                    <div className="mini-title">Per game, per model</div>
                    {est.perContestant.map((p) => (
                      <div key={p.contestantId} className="est-row">
                        <div className="row" style={{ gap: 8, minWidth: 0 }}>
                          <span className="sw" style={{ background: colorOf(p.contestantId) }} />
                          <span className="ellipsis" style={{ fontWeight: 600 }}>
                            {labelOf(p.contestantId)}
                          </span>
                          <span className="spacer" />
                          <span className="tnum muted" style={{ fontSize: '0.78rem' }}>
                            {p.manual ? 'manual' : `${fmtCost(p.perGameUsd)} / game${p.basis === 'measured' ? ' · measured' : ''}`}
                          </span>
                        </div>
                        {!p.manual && (
                          <div className="ar-est-bar" title={p.basis === 'measured' ? 'Measured in earlier tournaments of this game' : 'From the game’s own token estimate (no games measured yet)'}>
                            <i style={{ width: `${(p.perGameUsd / maxGame) * 100}%`, background: colorOf(p.contestantId) }} />
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                  {est.judgeCalls !== undefined && (
                    <div className="est-rows">
                      <div className="mini-title">Judges (cost included above)</div>
                      {est.judges && est.judges.length ? (
                        <>
                          <div className="row wrap" style={{ gap: 6 }}>
                            {est.judges.map((j) => (
                              <span key={j.id} className="badge outline">
                                {j.label} <span className="muted">· {j.vendor}</span>
                              </span>
                            ))}
                          </div>
                          <div className="muted tnum" style={{ fontSize: '0.8rem' }}>
                            {fmtInt(est.judgeCalls)} judge calls · ~{fmtCost(est.judgeCostUsd ?? 0)}. A judge never judges a debater from its own vendor.
                          </div>
                        </>
                      ) : (
                        <div className="muted" style={{ fontSize: '0.82rem' }}>
                          No judge models with API keys: every game will wait for human judging.
                        </div>
                      )}
                    </div>
                  )}
                  {r1.length > 0 && (
                    <div className="ar-preview">
                      <div className="mini-title">{format === 'knockout' ? `${r1[0]!.roundName}` : 'Round 1'}</div>
                      {r1.map((m) => {
                        const side = (s: (typeof m)['a']) =>
                          'entrant' in s ? (
                            <span className="ar-pv-p">
                              <span className="ar-pv-seed tnum">{seedOf.get(s.entrant)?.seed}</span>
                              <span className="sw" style={{ background: colorOf(s.entrant) }} />
                              <span className="ellipsis">{labelOf(s.entrant)}</span>
                            </span>
                          ) : (
                            <span className="ar-pv-p muted">bye</span>
                          );
                        return (
                          <div key={m.id} className="ar-pv-m">
                            {side(m.a)}
                            <span className="ar-pv-vs">vs</span>
                            {side(m.b)}
                          </div>
                        );
                      })}
                    </div>
                  )}
                  <div className="row" style={{ gap: 8, fontSize: '0.8rem' }}>
                    <Icon.Fingerprint style={{ width: 14, height: 14 }} />
                    <span className="muted">Fingerprint</span>
                    <span className="hash">{est.fingerprint}</span>
                  </div>
                  {est.warnings.length > 0 && (
                    <ul className="warn-list">
                      {est.warnings.map((w) => (
                        <li key={w}>
                          <Icon.Alert /> {w}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              ) : (
                <div className="muted">Calculating…</div>
              )}
            </div>
            <div className="card-foot stack">
              <button className="btn primary lg block" disabled={!req || !est || starting} onClick={start}>
                <Icon.Play /> {starting ? 'Starting…' : 'Start tournament'}
              </button>
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}
