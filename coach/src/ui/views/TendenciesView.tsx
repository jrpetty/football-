import { useState } from 'react'
import type { SightlineBridge } from '../../../shared/bridge.ts'
import type { InsightView } from '../../core/engine.ts'
import { negativeRate, wilson } from '../../core/evidence.ts'
import { responseLabel } from '../../core/language.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { OutcomeCounts } from '../../core/types.ts'
import { GradeBadge, Notice, Switch } from '../components/common.tsx'
import { pct } from '../format.ts'
import type { SessionState } from '../session.ts'

interface Props {
  state: SessionState
  profile: GameProfile
  settings: CoachSettings
  onSettings: (patch: Partial<CoachSettings>) => void
  bridge: SightlineBridge
}

export function TendenciesView({ state, profile, settings, onSettings, bridge }: Props) {
  const [forgotten, setForgotten] = useState(false)
  const forget = async () => {
    if (!window.confirm('Delete all saved tendency counts for every game profile? This cannot be undone.')) return
    await bridge.forgetTendencies(null)
    setForgotten(true)
  }

  return (
    <div className="page">
      <div>
        <h1>Tendencies</h1>
        <p className="lede">
          Patterns the coach has counted: what visibly happened after you responded to a situation one way or another. These
          are associations, not proof of cause, and are graded by how much evidence supports them.
        </p>
      </div>

      <section className="panel">
        <Switch
          checked={settings.saveTendencies}
          onChange={(saveTendencies) => onSettings({ saveTendencies })}
          label={<b>Remember tendencies between sessions</b>}
          hint="Off by default. When on, only outcome counts per situation are saved on this computer (no video, images, timestamps or text), so the coach can build on earlier sessions. Applies from the next session."
        />
        <div className="btn-row" style={{ marginTop: 14 }}>
          <button type="button" className="btn sm" onClick={() => void forget()}>
            Forget saved tendencies
          </button>
          {forgotten ? <span className="muted small">Saved tendencies deleted.</span> : null}
        </div>
      </section>

      {state.insights.length === 0 ? (
        <div className="empty">
          No patterns yet. The coach needs to see the same situation several times, with different responses, before it can
          compare them.
        </div>
      ) : (
        state.insights.map((v) => <InsightCard key={v.insight.key} v={v} profile={profile} />)
      )}

      {state.insights.length > 0 ? (
        <Notice level="info">
          Bars show how often each response was followed by the bad outcome; the thin range is a 95% interval. Wide ranges mean
          few examples. A pattern can reflect the situations you were in, not only the choice you made.
        </Notice>
      ) : null}
    </div>
  )
}

function InsightCard({ v, profile }: { v: InsightView; profile: GameProfile }) {
  const spec = profile.episodes.find((e) => e.id === v.insight.episodeId)
  if (!spec) return null
  const rows: [string, OutcomeCounts, string][] = [
    [responseLabel(spec, v.insight.worse.response), v.insight.worse.counts, 'var(--live)'],
    [responseLabel(spec, v.insight.better.response), v.insight.better.counts, 'var(--accent)'],
  ]
  return (
    <section className="panel insight" aria-label={`${v.episodeLabel} pattern`}>
      <div className="panel-head" style={{ marginBottom: 0 }}>
        <h2>
          {v.episodeLabel}: {spec.phrases.negativeShort} rate by response
        </h2>
        <div className="btn-row">
          {v.insight.usesHistory ? <span className="badge neutral">Includes earlier sessions</span> : null}
          <GradeBadge grade={v.insight.grade} />
        </div>
      </div>
      {rows.map(([label, c, color]) => {
        const w = wilson(c.negative, c.n)
        return (
          <div className="compare" key={label}>
            <span>{label}</span>
            <div className="bar" style={{ position: 'relative' }} aria-hidden="true">
              <span style={{ width: `${negativeRate(c) * 100}%`, background: color }} />
              <i style={{ position: 'absolute', top: 4, height: 2, left: `${w.lo * 100}%`, width: `${(w.hi - w.lo) * 100}%`, background: 'rgba(231,236,245,0.55)' }} />
            </div>
            <span className="num">
              {c.negative}/{c.n} · {pct(negativeRate(c))}
            </span>
          </div>
        )
      })}
      <p className="muted small" style={{ margin: 0 }}>
        {v.statement}
      </p>
    </section>
  )
}
