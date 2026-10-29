// The Town Center's production in a match (Age of Empires IV): idle stretches
// and time housed with a villager waiting, on a strip across the game time.

import type { ProductionSummary } from '../../core/aoe4/production.ts'
import { productionLines } from '../../core/aoe4/production.ts'

const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`

export function ProductionPanel({ summary: s }: { summary: ProductionSummary }) {
  const lines = productionLines(s)
  const span = Math.max(60, s.toSec - s.fromSec)
  const x = (t: number) => ((t - s.fromSec) / span) * 100
  const w = (sec: number) => Math.max(0.6, (sec / span) * 100)
  const ticks: number[] = []
  for (let t = Math.ceil(s.fromSec / 120) * 120; t <= s.toSec; t += 120) ticks.push(t)
  return (
    <div className="production">
      <ul className="week-lines">
        {lines.map((l) => (
          <li key={l}>{l}</li>
        ))}
      </ul>
      <svg className="production-strip" viewBox="0 0 100 14" preserveAspectRatio="none" role="img" aria-label={`Town Center idle stretches between ${clock(s.fromSec)} and ${clock(s.toSec)}`}>
        <rect x={0} y={3} width={100} height={6} className="strip-bg" />
        {s.stretches.map((r) => (
          <rect key={`i${r.at}`} x={x(r.at)} y={2} width={w(r.sec)} height={8} className="strip-idle">
            <title>{`Idle ${r.sec} s from ${clock(r.at)}`}</title>
          </rect>
        ))}
        {s.housedWaits.map((r) => (
          <rect key={`h${r.at}`} x={x(r.at)} y={2} width={w(r.sec)} height={8} className="strip-housed">
            <title>{`Housed with a villager waiting ${r.sec} s from ${clock(r.at)}`}</title>
          </rect>
        ))}
      </svg>
      <div className="production-ticks small muted" aria-hidden="true">
        {ticks.map((t) => (
          <span key={t} style={{ left: `${x(t)}%` }}>
            {clock(t)}
          </span>
        ))}
      </div>
      <p className="muted small" style={{ margin: '18px 0 0' }}>
        <span className="lg-box idle" /> idle · <span className="lg-box housed" /> housed with a villager waiting. From the villager counter (top left), read about every {s.readEverySec} s; one villager every {s.trainSec} s is a Town Center that never stops. Short gaps under a few seconds are not counted, and a second Town Center makes
        this read low, never high.
      </p>
    </div>
  )
}
