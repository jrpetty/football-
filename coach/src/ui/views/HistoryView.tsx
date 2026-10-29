import { useMemo, useState } from 'react'
import type { SightlineBridge } from '../../../shared/bridge.ts'
import type { HeatmapResult } from '../../core/analyses/heatmap.ts'
import type { MatchRecord } from '../../core/analyses/matchRecorder.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { Tab } from '../App.tsx'
import { Notice } from '../components/common.tsx'
import { AccuracyPanel } from '../components/Accuracy.tsx'
import { Sparkline, StatTrends, ZoneMap } from '../components/viz.tsx'
import { dateShort, formatMs, labelOf } from '../format.ts'
import type { CoachSession, SessionState } from '../session.ts'

interface Props {
  state: SessionState
  session: CoachSession
  profile: GameProfile
  settings: CoachSettings
  onSettings: (p: Partial<CoachSettings>) => void
  bridge: SightlineBridge
  onTab: (t: Tab) => void
}

export function HistoryView({ state, session, profile, settings, onSettings, bridge, onTab }: Props) {
  const [confirm, setConfirm] = useState(false)
  // Saved history, plus this session's matches if they were not saved.
  const matches = useMemo(() => {
    const ids = new Set(state.history.map((m) => m.id))
    return [...state.history, ...state.matches.filter((m) => m.gameProfileId === profile.id && !ids.has(m.id))].sort((a, b) => a.endedAt.localeCompare(b.endedAt))
  }, [state.history, state.matches, profile.id])
  const mine = matches.filter((m) => m.gameProfileId === profile.id)
  const record = { win: 0, loss: 0, draw: 0, unknown: 0 }
  for (const m of mine) record[m.result]++

  const download = async () => {
    const json = await bridge.exportPlayerData()
    const url = URL.createObjectURL(new Blob([json], { type: 'application/json' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `sightline-data-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return (
    <div className="page">
      <div>
        <h1>History · {profile.game.title}</h1>
        <p className="lede">Match by match: results, end-screen statistics, mistakes and zones. Structured numbers only — never video or screenshots.</p>
      </div>

      {!settings.saveTendencies ? (
        <Notice level="info">
          Long-term memory is off, so only this session’s matches are shown and nothing is kept.{' '}
          <button type="button" className="btn sm" onClick={() => onTab('settings')}>
            Turn it on
          </button>
        </Notice>
      ) : null}

      {mine.length === 0 ? (
        <>
          <div className="empty">No matches recorded for {profile.game.title} yet. A match is recorded when its end is visible on screen, or when you press “Match over”.</div>
          {/* Set up the check against the real game before the first match. */}
          <AccuracyPanel state={state} session={session} profile={profile} settings={settings} onSettings={onSettings} bridge={bridge} matches={mine} />
        </>
      ) : (
        <>
          <section className="panel">
            <div className="stat-row">
              <div className="stat">
                <div className="n">{mine.length}</div>
                <div className="l">Matches</div>
              </div>
              <div className="stat">
                <div className="n">
                  {record.win}–{record.draw}–{record.loss}
                </div>
                <div className="l">Won–drawn–lost</div>
              </div>
              <div className="stat">
                <div className="n">{goalsLine(mine)}</div>
                <div className="l">Goals for–against</div>
              </div>
            </div>
          </section>

          <div className="grid-2">
            {profile.matchStats && (
              <section className="panel" aria-labelledby="trend-h">
                <div className="panel-head">
                  <h2 id="trend-h">Statistics trend</h2>
                  <span className="muted small">Last {Math.min(12, mine.length)} matches</span>
                </div>
                <StatTrends profile={profile} matches={mine} />
              </section>
            )}
            {(profile.mistakes ?? []).length > 0 && (
              <section className="panel" aria-labelledby="mt-h">
                <div className="panel-head">
                  <h2 id="mt-h">Mistakes per match</h2>
                </div>
                <table className="trend-table">
                  <tbody>
                    {(profile.mistakes ?? []).map((spec) => {
                      const series = mine.map((m) => m.mistakes[spec.id]?.count ?? 0)
                      return (
                        <tr key={spec.id}>
                          <th scope="row">{spec.label}</th>
                          <td className="num">{series.reduce((a, b) => a + b, 0)}</td>
                          <td>
                            <Sparkline values={series.slice(-12)} />
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </section>
            )}
          </div>

          {(profile.heatmaps ?? []).length > 0 && (
            <section className="panel" aria-labelledby="hz-h">
              <div className="panel-head">
                <h2 id="hz-h">Zones across matches</h2>
                <span className="muted small">All recorded matches combined</span>
              </div>
              <div className="heatmaps">
                {aggregateZones(profile, mine).map((h) => (
                  <div key={h.spec.id} className="heatmap-tile">
                    <div className="hm-title">{h.spec.label}</div>
                    <ZoneMap heatmap={h} graded={false} />
                  </div>
                ))}
              </div>
            </section>
          )}

          <AccuracyPanel state={state} session={session} profile={profile} settings={settings} onSettings={onSettings} bridge={bridge} matches={mine} />

          <section className="panel" aria-labelledby="ml-h">
            <div className="panel-head">
              <h2 id="ml-h">Matches</h2>
            </div>
            <table className="table">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Result</th>
                  <th>Score</th>
                  <th>Length</th>
                  <th>Mistakes</th>
                  <th>Statistics</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {[...mine].reverse().map((m) => (
                  <tr key={m.id}>
                    <td>{dateShort(m.endedAt)}</td>
                    <td>
                      <span className={`result ${m.result}`}>{m.result === 'unknown' ? 'Not read' : labelOf(m.result)}</span>
                      {m.endedManually ? <span className="muted small"> (you ended it)</span> : null}
                    </td>
                    <td>{m.score ? `${m.score.player}–${m.score.opponent}` : '—'}</td>
                    <td>{formatMs(m.durationSec * 1000)}</td>
                    <td className="small">
                      {Object.entries(m.mistakes)
                        .filter(([, v]) => v.count)
                        .map(([k, v]) => `${profile.mistakes?.find((s) => s.id === k)?.label ?? k} ×${v.count}`)
                        .join(', ') || '—'}
                    </td>
                    <td className="small muted">
                      {Object.entries(m.stats)
                        .map(([k, v]) => {
                          const spec = profile.matchStats?.keys.find((s) => s.key === k)
                          return `${spec?.label ?? labelOf(k)} ${v}${spec?.unit ?? ''}`
                        })
                        .join(' · ') || '—'}
                    </td>
                    <td>
                      {state.history.some((h) => h.id === m.id) ? (
                        <button type="button" className="btn sm ghost danger" onClick={() => void session.deleteMatch(m.id)} aria-label={`Delete match of ${dateShort(m.endedAt)}`}>
                          Delete
                        </button>
                      ) : (
                        <span className="muted small">not saved</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </>
      )}

      <section className="panel" aria-labelledby="data-h">
        <div className="panel-head">
          <h2 id="data-h">Your data</h2>
        </div>
        <div className="btn-row">
          <button type="button" className="btn" onClick={() => void download()}>
            Export everything (JSON)
          </button>
          {confirm ? (
            <>
              <span className="small">Delete all saved matches, patterns and notes for {profile.game.title}?</span>
              <button
                type="button"
                className="btn danger"
                onClick={() => {
                  setConfirm(false)
                  void bridge.forgetPlayerData(profile.id).then(() => session.reloadPlayerData())
                }}
              >
                Delete
              </button>
              <button type="button" className="btn ghost" onClick={() => setConfirm(false)}>
                Cancel
              </button>
            </>
          ) : (
            <button type="button" className="btn ghost danger" onClick={() => setConfirm(true)}>
              Delete {profile.game.title} data…
            </button>
          )}
        </div>
      </section>
    </div>
  )
}

function goalsLine(ms: readonly MatchRecord[]): string {
  const scored = ms.filter((m) => m.score)
  if (!scored.length) return '—'
  return `${scored.reduce((s, m) => s + m.score!.player, 0)}–${scored.reduce((s, m) => s + m.score!.opponent, 0)}`
}

/** Zone counts summed over matches, shaped for the zone map (no claims: those need the live grading). */
function aggregateZones(profile: GameProfile, ms: readonly MatchRecord[]): HeatmapResult[] {
  return (profile.heatmaps ?? []).flatMap((spec) => {
    const zoneSet = profile.zones?.find((z) => z.id === spec.zoneSet)
    if (!zoneSet) return []
    const cells: Record<string, number> = {}
    for (const m of ms) for (const [cell, n] of Object.entries(m.zones[spec.id] ?? {})) cells[cell] = (cells[cell] ?? 0) + n
    const located = Object.values(cells).reduce((a, b) => a + b, 0)
    return [{ spec, zoneSet, total: located, located, cells, breakdown: {}, claim: null, topBreakdown: null }]
  })
}
