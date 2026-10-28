// Usage & cost: what the Claude API has cost (this session, today, this
// month with a projection, all time), where it went, what playing each game
// costs at the player's settings (measured from their own looks once there
// are enough), what the other jobs cost, the limits, and the prices used.
// Every figure is token counts × list prices; the Console bill is final.

import { useEffect, useState } from 'react'
import type { SightlineBridge } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import { minutesToCap, playEstimate, spendAlerts, WARN_AT, type PlayEstimate } from '../../core/estimate.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import { averageCall, FEATURE_LABEL, MEASURED_MIN_LOOKS, type UsageCounts, type UsageSummary } from '../../core/usage.ts'
import { FALLBACK_PRICES, imageTokens, jobEstimate, MODELS, modelPrice, PRICES_CHECKED_AT, PRICING_URL, WEB_SEARCH_USD, type CloudJob } from '../../core/vision/cost.ts'
import type { ProfileCatalog } from '../App.tsx'
import { Notice, Segmented, Switch } from '../components/common.tsx'
import { FEATURE_COLOR, UsageChart } from '../components/UsageChart.tsx'
import { compact, dateShort, formatMs, modelName, usd, usdFine, usdRange } from '../format.ts'

interface Props {
  summary: UsageSummary | null
  cloud: CloudStatus
  onCloud: (c: CloudStatus) => void
  bridge: SightlineBridge
  settings: CoachSettings
  profile: GameProfile
  catalog: ProfileCatalog
  capturing: boolean
  onForgot: () => void
}

const WIDTH_LABEL: Record<number, string> = { 768: 'Low', 1024: 'Medium', 1280: 'High', 1600: 'Max' }

export function UsageView({ summary, cloud, onCloud, bridge, settings, profile, catalog, capturing, onForgot }: Props) {
  const spend = cloud.spend
  const s = cloud.settings
  const alerts = spendAlerts(spend, s)
  const apply = (p: Promise<CloudStatus>) => void p.then(onCloud).catch(() => undefined)
  const rate = capturing ? spend.ratePerHour ?? null : null
  const toCap = rate ? minutesToCap(spend, s.sessionCapUsd) : null
  return (
    <div className="page usage">
      <div>
        <h1>Usage & cost</h1>
        <p className="lede">
          What the Claude API has cost you, and what playing will cost. Every figure is worked out from the token counts on each reply and Anthropic’s
          list prices. Your Anthropic Console bill is the final word.
        </p>
      </div>

      {!cloud.available ? (
        <Notice level="info">Cloud features run in the desktop app. Here nothing is sent and nothing is spent; the estimates and prices below still apply.</Notice>
      ) : !s.enabled ? (
        <Notice level="info">Cloud features are off (Settings), so nothing is being spent. Estimates below show what turning them on would cost.</Notice>
      ) : null}
      {alerts.map((a) => (
        <Notice key={a.text} level={a.level === 'stop' ? 'error' : 'warning'}>
          {a.text}
        </Notice>
      ))}

      <Figures summary={summary} cloud={cloud} />

      {rate ? (
        <div className="rate-line small" role="status">
          <span className="live-dot" aria-hidden="true" /> Right now: about <b>{usd(rate)}</b> an hour (the last few minutes of calls)
          {toCap !== null ? <> · this session’s {usd(s.sessionCapUsd)} cap in about {toCap < 1 ? 'a minute' : `${Math.round(toCap)} min`}</> : null}
        </div>
      ) : null}

      <section className="panel" aria-labelledby="u-days">
        <div className="panel-head">
          <h2 id="u-days">Last 30 days</h2>
          <span className="muted small">by feature</span>
        </div>
        {summary ? <UsageChart daily={summary.daily} /> : <p className="muted small">The usage history is kept by the desktop app.</p>}
      </section>

      {summary ? <Breakdown summary={summary} catalog={catalog} /> : null}

      <PlayCosts summary={summary} cloud={cloud} settings={settings} profile={profile} catalog={catalog} />
      <JobCosts summary={summary} cloud={cloud} />
      <Limits cloud={cloud} apply={apply} bridge={bridge} />
      <Prices />
      <HowCounted cloud={cloud} bridge={bridge} onForgot={onForgot} hasHistory={!!summary?.since} />
    </div>
  )
}

// --- The headline figures ---------------------------------------------------------------------

function Figures({ summary, cloud }: { summary: UsageSummary | null; cloud: CloudStatus }) {
  const spend = cloud.spend
  const s = cloud.settings
  const now = new Date()
  const monthName = now.toLocaleDateString(undefined, { month: 'long' })
  const month = summary?.thisMonth.usd ?? spend.monthUsd ?? 0
  const budget = s.monthlyBudgetUsd
  const projecting = summary && summary.last7.usd > 0 && summary.monthProjection > month + 0.005
  const sessionShare = s.sessionCapUsd > 0 ? spend.usd / s.sessionCapUsd : 0
  return (
    <section className="usage-figures" aria-label="Spend so far">
      <div className="panel usage-hero">
        <span className="label">This month · {monthName}</span>
        <div className="hero-figure">{usd(month)}</div>
        <div className="small muted">
          {summary ? `${summary.thisMonth.calls.toLocaleString()} calls` : null}
          {projecting ? ` · on track for about ${usd(summary.monthProjection)} by the month’s end, at the last 7 days’ pace` : ''}
        </div>
        {budget > 0 ? (
          <BudgetMeter used={month} of={budget} label={`of the ${usd(budget)} monthly budget${s.stopAtBudget ? ' (calls stop there)' : ''}`} />
        ) : (
          <div className="small muted">No monthly budget set (Limits, below).</div>
        )}
      </div>
      <div className="usage-tiles">
        <div className="stat-tile">
          <span className="label">This session</span>
          <div className="value">{usd(spend.usd)}</div>
          <div className="small muted">
            {spend.calls.toLocaleString()} calls · cap {usd(s.sessionCapUsd)}
          </div>
          <div className={`meter${sessionShare >= WARN_AT ? ' warn' : ''}`} aria-hidden="true">
            <span style={{ width: `${Math.min(100, sessionShare * 100)}%` }} />
          </div>
        </div>
        <Tile label="Today" c={summary?.today} />
        <Tile label="Last 7 days" c={summary?.last7} />
        <Tile label="All time" c={summary?.allTime} note={summary?.since ? `since ${dateShort(summary.since + 'T12:00:00')}` : undefined} />
      </div>
    </section>
  )
}

function Tile({ label, c, note }: { label: string; c: UsageCounts | undefined; note?: string }) {
  return (
    <div className="stat-tile">
      <span className="label">{label}</span>
      <div className="value">{c ? usd(c.usd) : '—'}</div>
      <div className="small muted">
        {c ? `${c.calls.toLocaleString()} calls` : 'desktop app only'}
        {note ? ` · ${note}` : ''}
      </div>
    </div>
  )
}

function BudgetMeter({ used, of, label }: { used: number; of: number; label: string }) {
  const share = used / of
  const tone = share >= 1 ? ' over' : share >= WARN_AT ? ' warn' : ''
  return (
    <div className="budget">
      <div className={`meter${tone}`} role="meter" aria-valuemin={0} aria-valuemax={of} aria-valuenow={Math.round(used * 100) / 100} aria-label="Monthly budget used">
        <span style={{ width: `${Math.min(100, share * 100)}%` }} />
      </div>
      <span className="small">
        {tone ? <span aria-hidden="true">⚠ </span> : null}
        <b>{Math.round(share * 100)}%</b> <span className="muted">{label}</span>
      </span>
    </div>
  )
}

// --- Where it went ------------------------------------------------------------------------

type By = 'feature' | 'model' | 'game'

function Breakdown({ summary, catalog }: { summary: UsageSummary; catalog: ProfileCatalog }) {
  const [by, setBy] = useState<By>('feature')
  const game = (id: string) => catalog.profiles.find((p) => p.id === id)?.game.title ?? id
  const rows =
    by === 'feature'
      ? summary.byFeature.map((r) => ({ key: r.feature, name: FEATURE_LABEL[r.feature], color: FEATURE_COLOR[r.feature], c: r.counts }))
      : by === 'model'
        ? summary.byModel.map((r) => ({ key: r.model, name: modelName(r.model), color: null, c: r.counts }))
        : summary.byPack.map((r) => ({ key: r.pack, name: game(r.pack), color: null, c: r.counts }))
  const total = summary.thisMonth.usd
  return (
    <section className="panel" aria-labelledby="u-where">
      <div className="panel-head">
        <h2 id="u-where">Where it went this month</h2>
        <Segmented
          label="Group by"
          value={by}
          onChange={setBy}
          options={[
            { value: 'feature', label: 'Feature' },
            { value: 'model', label: 'Model' },
            { value: 'game', label: 'Game' },
          ]}
        />
      </div>
      {rows.length ? (
        <div className="table-scroll">
          <table className="table num-table">
            <thead>
              <tr>
                <th>{by === 'feature' ? 'Feature' : by === 'model' ? 'Model (as served)' : 'Game'}</th>
                <th className="num">Calls</th>
                <th className="num" title="Tokens sent without the cache: images, questions, session data">Input</th>
                <th className="num" title="Reference text read from or written to the prompt cache">Cached</th>
                <th className="num" title="Tokens written, thinking included">Output</th>
                <th className="num">Searches</th>
                <th className="num">Cost</th>
                <th className="num">Share</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.key}>
                  <td className="nowrap">
                    {r.color ? <span className="swatch" style={{ background: r.color }} aria-hidden="true" /> : null}
                    {r.name}
                  </td>
                  <td className="num">{r.c.calls.toLocaleString()}</td>
                  <td className="num">{compact(r.c.input)}</td>
                  <td className="num">{compact(r.c.cacheRead + r.c.cacheWrite)}</td>
                  <td className="num">
                    {compact(r.c.output)}
                    {r.c.thinking ? <span className="muted"> ({compact(r.c.thinking)} thinking)</span> : null}
                  </td>
                  <td className="num">{r.c.webSearches ? r.c.webSearches.toLocaleString() : '—'}</td>
                  <td className="num">
                    <b>{usd(r.c.usd)}</b>
                  </td>
                  <td className="num">{total > 0 ? `${Math.round((r.c.usd / total) * 100)}%` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="muted small">Nothing spent this month yet.</p>
      )}
      {by === 'game' && summary.byFeature.some((f) => f.feature === 'research') ? (
        <p className="muted small">Web research is not tied to a game pack, so it shows under Feature and Model only.</p>
      ) : null}
    </section>
  )
}

// --- What playing costs ------------------------------------------------------------------------

function PlayCosts({ summary, cloud, settings, profile, catalog }: { summary: UsageSummary | null; cloud: CloudStatus; settings: CoachSettings; profile: GameProfile; catalog: ProfileCatalog }) {
  const games = catalog.profiles.filter((p) => p.vision)
  const current = playEstimate(profile, settings, cloud.settings, summary)
  const based = (e: PlayEstimate) => (e.measured ? `Your ${e.measured.looks.toLocaleString()} looks` : 'List prices')
  return (
    <section className="panel" aria-labelledby="u-play">
      <div className="panel-head">
        <h2 id="u-play">What playing costs</h2>
        <span className="muted small">screen reading with {modelName(cloud.settings.visionModel)}, at your settings for each game</span>
      </div>
      <div className="table-scroll">
        <table className="table num-table">
          <thead>
            <tr>
              <th>Game</th>
              <th>Looks every</th>
              <th>Detail</th>
              <th className="num">Per look</th>
              <th className="num">Per hour</th>
              <th className="num">Per match</th>
              <th>Based on</th>
            </tr>
          </thead>
          <tbody>
            {games.map((g) => {
              const e = playEstimate(g, settings, cloud.settings, summary)!
              return (
                <tr key={g.id} className={g.id === profile.id ? 'current' : undefined}>
                  <td>
                    {g.game.title}
                    {g.id === profile.id ? <span className="badge ok">selected</span> : null}
                  </td>
                  <td>{formatMs(e.intervalMs)}</td>
                  <td>{WIDTH_LABEL[e.width] ?? `${e.width} px`}</td>
                  <td className="num">{e.measured ? usdFine(e.measured.usdPerLook) : `${usdFine(e.listPerLook.low)}–${usdFine(e.listPerLook.high)}`}</td>
                  <td className="num">
                    <b>{usdRange(e.perHour.low, e.perHour.high)}</b>
                  </td>
                  <td className="num">
                    {usdRange(e.perMatch.low, e.perMatch.high)} <span className="muted">· {e.matchMinutes} min</span>
                  </td>
                  <td className="muted">{based(e)}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {current ? (
        <>
          <h3 className="sub-h">{profile.game.title} with each model</h3>
          <div className="table-scroll">
            <table className="table num-table">
              <thead>
                <tr>
                  <th>Model</th>
                  <th className="num">Per look</th>
                  <th className="num">Per hour</th>
                  <th className="num">Per {current.matchMinutes}-min match</th>
                  <th>Based on</th>
                </tr>
              </thead>
              <tbody>
                {MODELS.map((m) => {
                  const e = playEstimate(profile, settings, cloud.settings, summary, m.id)!
                  return (
                    <tr key={m.id} className={m.id === cloud.settings.visionModel ? 'current' : undefined}>
                      <td>
                        {modelName(m.id)}
                        {m.id === cloud.settings.visionModel ? <span className="badge ok">in use</span> : null}
                      </td>
                      <td className="num">{e.measured ? usdFine(e.measured.usdPerLook) : `${usdFine(e.listPerLook.low)}–${usdFine(e.listPerLook.high)}`}</td>
                      <td className="num">
                        <b>{usdRange(e.perHour.low, e.perHour.high)}</b>
                      </td>
                      <td className="num">{usdRange(e.perMatch.low, e.perMatch.high)}</td>
                      <td className="muted">{based(e)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <ul className="plain small muted usage-notes">
            <li>
              At most {Math.round(current.looksPerHour).toLocaleString()} looks an hour at {formatMs(current.intervalMs)}: still screens (menus, pauses, loading) are skipped, so real play
              usually costs less than the top figure.
            </li>
            <li>
              Each {WIDTH_LABEL[current.width] ?? ''} look is about {imageTokens(current.width, Math.round((current.width * 9) / 16)).toLocaleString()} image tokens, plus the game’s reference text
              read from the cache. The range runs from no thinking to a little thinking per look (Opus 5.5 always thinks a little).
            </li>
            <li>
              Once there are {MEASURED_MIN_LOOKS} or more looks of a game with a model, its figures come from what those looks actually cost instead. Change the refresh time and detail in
              Settings → Screen reading, or on the Coach screen.
            </li>
          </ul>
        </>
      ) : null}
    </section>
  )
}

// --- Other jobs ----------------------------------------------------------------------------

const JOBS: readonly { readonly job: CloudJob; readonly name: string; readonly what: string }[] = [
  { job: 'answer', name: 'Answer a question', what: 'by voice or typed, about the game or the screen' },
  { job: 'review', name: 'Review a moment or a match', what: 'a marked decision, or the coach’s review after a game' },
  { job: 'research', name: 'Research a game', what: 'a short brief from the web, up to 4 turns of searching' },
  { job: 'knowledge', name: 'Build game knowledge', what: 'a large reference from the web, once per game (rebuild after big patches)' },
]

function JobCosts({ summary, cloud }: { summary: UsageSummary | null; cloud: CloudStatus }) {
  const model = cloud.settings.answerModel
  return (
    <section className="panel" aria-labelledby="u-jobs">
      <div className="panel-head">
        <h2 id="u-jobs">Answers, reviews and research</h2>
        <span className="muted small">with {modelName(model)} (Settings → Answers & research model)</span>
      </div>
      <div className="table-scroll">
        <table className="table num-table">
          <thead>
            <tr>
              <th>Job</th>
              <th className="num">Each, estimated</th>
              <th className="num">Your average</th>
            </tr>
          </thead>
          <tbody>
            {JOBS.map((j) => {
              const est = jobEstimate(model, j.job)
              // Research and knowledge runs take several calls each, so only single-call jobs have a per-run average.
              const avg = j.job === 'answer' || j.job === 'review' ? averageCall(summary, j.job, model) : null
              return (
                <tr key={j.job}>
                  <td>
                    {j.name}
                    <div className="muted small">{j.what}</div>
                  </td>
                  <td className="num">{est ? usdRange(est.low, est.high) : <span className="muted">needs Opus or Sonnet</span>}</td>
                  <td className="num">{avg ? `${usd(avg.usd)} over ${avg.calls}` : <span className="muted">—</span>}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <p className="muted small">
        Estimates assume typical lengths (web search results count as input; searches are {usd(WEB_SEARCH_USD * 1000)} per 1,000). Each run’s real cost is shown when it finishes and
        recorded here.
      </p>
    </section>
  )
}

// --- Limits --------------------------------------------------------------------------------

function Limits({ cloud, apply, bridge }: { cloud: CloudStatus; apply: (p: Promise<CloudStatus>) => void; bridge: SightlineBridge }) {
  const s = cloud.settings
  const [draft, setDraft] = useState(s.monthlyBudgetUsd ? String(s.monthlyBudgetUsd) : '')
  useEffect(() => setDraft(s.monthlyBudgetUsd ? String(s.monthlyBudgetUsd) : ''), [s.monthlyBudgetUsd])
  const setBudget = (v: number) => apply(bridge.setCloudSettings({ monthlyBudgetUsd: Math.max(0, Math.min(10_000, Math.round(v * 100) / 100)) }))
  const commit = () => {
    const v = Number(draft.replace(/[$,\s]/g, ''))
    if (Number.isFinite(v)) setBudget(v)
    else setDraft(s.monthlyBudgetUsd ? String(s.monthlyBudgetUsd) : '')
  }
  return (
    <section className="panel" aria-labelledby="u-limits">
      <div className="panel-head">
        <h2 id="u-limits">Limits</h2>
      </div>
      <div className="grid-2">
        <div className="field">
          <label htmlFor="u-cap">Spending cap per session: {usd(s.sessionCapUsd)}</label>
          <input id="u-cap" type="range" min={0.5} max={20} step={0.5} value={s.sessionCapUsd} disabled={!cloud.available} onChange={(e) => apply(bridge.setCloudSettings({ sessionCapUsd: Number(e.target.value) }))} />
          <span className="muted small">All cloud calls pause for the rest of the session at the cap. A warning shows at {Math.round(WARN_AT * 100)}%.</span>
        </div>
        <div className="field">
          <label htmlFor="u-budget">Monthly budget</label>
          <form
            className="btn-row"
            onSubmit={(e) => {
              e.preventDefault()
              commit()
            }}
          >
            <span className="money-input">
              <span aria-hidden="true">$</span>
              <input id="u-budget" inputMode="decimal" placeholder="none" value={draft} disabled={!cloud.available} onChange={(e) => setDraft(e.target.value)} onBlur={commit} />
            </span>
            {[5, 10, 20, 50].map((v) => (
              <button key={v} type="button" className="btn sm ghost" disabled={!cloud.available} aria-pressed={s.monthlyBudgetUsd === v} onClick={() => setBudget(v)}>
                ${v}
              </button>
            ))}
            <button type="button" className="btn sm ghost" disabled={!cloud.available || !s.monthlyBudgetUsd} onClick={() => setBudget(0)}>
              None
            </button>
          </form>
          <Switch
            checked={s.stopAtBudget}
            onChange={(stopAtBudget) => apply(bridge.setCloudSettings({ stopAtBudget }))}
            label="Stop cloud calls at the budget"
            hint={`Off: only warn (from ${Math.round(WARN_AT * 100)}%). The month is your computer’s calendar month, counted from this app’s own records.`}
          />
        </div>
      </div>
    </section>
  )
}

// --- Prices ------------------------------------------------------------------------------------

function Prices() {
  const per = (v: number) => (Number.isInteger(v) ? `$${v}` : `$${v.toFixed(3).replace(/0$/, '')}`)
  const haiku = MODELS.find((m) => m.longContext)
  return (
    <section className="panel" aria-labelledby="u-prices">
      <div className="panel-head">
        <h2 id="u-prices">Prices used</h2>
        <span className="muted small">
          per million tokens · checked {PRICES_CHECKED_AT} · <span className="mono">{PRICING_URL.replace(/^https:\/\//, '')}</span>
        </span>
      </div>
      <div className="table-scroll">
        <table className="table num-table">
          <thead>
            <tr>
              <th>Model</th>
              <th className="num">Input</th>
              <th className="num">Output</th>
              <th className="num">Cache read</th>
              <th className="num">Cache write, 5 min</th>
              <th className="num">Cache write, 1 hour</th>
            </tr>
          </thead>
          <tbody>
            {[...MODELS, ...FALLBACK_PRICES].map((m) => (
              <tr key={m.id} className={MODELS.includes(m) ? undefined : 'muted'}>
                <td>
                  {modelName(m.id)}
                  {MODELS.includes(m) ? null : <span className="muted small"> · only if a request falls back</span>}
                </td>
                <td className="num">{per(m.input)}</td>
                <td className="num">{per(m.output)}</td>
                <td className="num">{per(m.cacheRead)}</td>
                <td className="num">{per(m.input * 1.25)}</td>
                <td className="num">{per(m.input * 2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="plain small muted usage-notes">
        {haiku?.longContext ? (
          <li>
            {modelName(haiku.id)} bills {per(haiku.longContext.input)} input, {per(haiku.longContext.output)} output and {per(haiku.longContext.cacheRead)} cache reads for a whole request
            whose prompt is over {compact(haiku.longContext.above)} tokens.
          </li>
        ) : null}
        <li>Web search: {usd(WEB_SEARCH_USD * 1000)} per 1,000 searches, plus the tokens of the results. Web fetch: tokens only.</li>
        <li>Images: about one token per 28 × 28 pixels (a 1280 × 720 look is about {imageTokens(1280, 720).toLocaleString()} tokens). Thinking is billed as output.</li>
        <li>{modelPrice('claude-opus-5-5').label.replace(/ \(.*\)$/, '')} and Sonnet 5.5 can fall back to another model if a request is declined; the call is then billed at that model’s price.</li>
      </ul>
    </section>
  )
}

// --- How it is counted ----------------------------------------------------------------------------

function HowCounted({ cloud, bridge, onForgot, hasHistory }: { cloud: CloudStatus; bridge: SightlineBridge; onForgot: () => void; hasHistory: boolean }) {
  const [confirm, setConfirm] = useState(false)
  return (
    <section className="panel" aria-labelledby="u-how">
      <div className="panel-head">
        <h2 id="u-how">How this is counted</h2>
      </div>
      <ul className="plain small usage-notes spaced">
        <li>Every reply from the API carries its exact token counts: input, cache reads and writes, output (thinking included) and web searches. The app multiplies them by the list price of the model that actually served it.</li>
        <li>Not included: taxes, credits, discounts or other terms on your Anthropic account. Prices change; the date they were checked is above. The Console’s Usage and Cost pages are the authority.</li>
        <li>Kept on this computer only, for 400 days: the day, the feature, the model, the game and the counts. No prompts, screens or answers.</li>
      </ul>
      {cloud.available ? (
        confirm ? (
          <div className="btn-row">
            <button
              type="button"
              className="btn sm danger"
              onClick={() =>
                void bridge.forgetUsage().then(() => {
                  setConfirm(false)
                  onForgot()
                })
              }
            >
              Forget the usage history
            </button>
            <button type="button" className="btn sm ghost" onClick={() => setConfirm(false)}>
              Keep it
            </button>
            <span className="muted small">This session’s total and cap are not affected.</span>
          </div>
        ) : (
          <button type="button" className="btn sm ghost danger" disabled={!hasHistory} onClick={() => setConfirm(true)}>
            Forget the usage history…
          </button>
        )
      ) : null}
    </section>
  )
}
