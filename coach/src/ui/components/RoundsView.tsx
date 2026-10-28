// Counter-Strike 2 rounds, from the game's own data: one cell per round
// (won or lost, the buy you made, your kills, and when you died), your money
// at the start of each round against what you carried into it, and the lines
// the coach draws from them. Colour is never the only cue: each cell also
// says W or L, the buy as a letter, kills as pips and a death as a cross.

import { useMemo, useState } from 'react'
import type { RoundsReport } from '../../core/engine.ts'
import { roundInsights, roundTotals, type RoundInsight } from '../../core/analyses/rounds.ts'
import type { BuyType, Cs2Round, Cs2Rounds } from '../../core/gsi/cs2.ts'
import type { GameProfile } from '../../core/profile/schema.ts'

const BUY: Readonly<Record<BuyType, { letter: string; label: string }>> = {
  pistol: { letter: 'P', label: 'Pistol round' },
  eco: { letter: 'E', label: 'Eco (saved)' },
  force: { letter: 'F', label: 'Force buy' },
  full: { letter: 'B', label: 'Full buy' },
}

const money = (v: number | null) => (v === null ? '—' : `$${v.toLocaleString('en-US')}`)

function cellTitle(r: Cs2Round): string {
  return [
    `Round ${r.n}${r.side ? ` (${r.side})` : ''}: ${r.result === 'won' ? 'won' : r.result === 'lost' ? 'lost' : 'in play'}${r.reason ? `, ${r.reason}` : ''}`,
    r.buy ? `${BUY[r.buy].label}${r.equip !== null ? `, ${money(r.equip)} of equipment` : ''}` : null,
    r.startMoney !== null ? `Started with ${money(r.startMoney)}${r.spent !== null ? `, spent ${money(r.spent)}` : ''}` : null,
    `${r.kills} ${r.kills === 1 ? 'kill' : 'kills'}${r.headshots ? ` (${r.headshots} headshot${r.headshots === 1 ? '' : 's'})` : ''}${r.damage !== null ? `, ${r.damage} damage` : ''}`,
    r.died ? `Died${r.diedAt !== null ? ` ${Math.round(r.diedAt)} s in` : ''}${r.blindAtDeath ? ', flashed' : ''}${r.utilityAtDeath ? `, ${r.utilityAtDeath} grenade${r.utilityAtDeath === 1 ? '' : 's'} unused` : ''}` : 'Survived',
  ]
    .filter(Boolean)
    .join('\n')
}

/** One cell per round, the half marked where the sides changed. */
export function RoundStrip({ rounds }: { rounds: readonly Cs2Round[] }) {
  return (
    <ol className="round-strip" aria-label="Rounds">
      {rounds.map((r, i) => {
        const half = i > 0 && r.side !== rounds[i - 1].side
        return (
          <li key={r.n} className={`round-cell ${r.result ?? 'live'}${half ? ' half' : ''}`} title={cellTitle(r)}>
            <span className="rc-n">{r.n}</span>
            <span className="rc-res" aria-label={r.result ?? 'in play'}>
              {r.result === 'won' ? 'W' : r.result === 'lost' ? 'L' : '…'}
            </span>
            <span className={`rc-buy ${r.buy ?? ''}`} aria-label={r.buy ? BUY[r.buy].label : 'buy unknown'}>
              {r.buy ? BUY[r.buy].letter : '?'}
            </span>
            <span className="rc-kills" aria-label={`${r.kills} kills`}>
              {'●'.repeat(Math.min(5, r.kills))}
            </span>
            <span className="rc-death" aria-label={r.died ? 'died' : 'survived'}>
              {r.died ? `✕${r.diedAt !== null ? Math.round(r.diedAt) : ''}` : ''}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

/** Money at the start of each round (line) and the equipment carried into it (bars). */
export function EconomyChart({ rounds }: { rounds: readonly Cs2Round[] }) {
  const W = 640
  const H = 170
  const pad = { l: 46, r: 10, t: 10, b: 22 }
  const max = Math.max(5000, ...rounds.map((r) => Math.max(r.startMoney ?? 0, r.equip ?? 0)))
  const step = (W - pad.l - pad.r) / Math.max(1, rounds.length)
  const y = (v: number) => pad.t + (1 - v / max) * (H - pad.t - pad.b)
  const ticks = [0, Math.round(max / 2 / 1000) * 1000, Math.floor(max / 1000) * 1000]
  const line = rounds
    .map((r, i) => (r.startMoney === null ? null : `${pad.l + i * step + step / 2},${y(r.startMoney)}`))
    .filter(Boolean)
    .join(' ')
  return (
    <figure className="econ-chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Money at the start of each round and the equipment you carried into it">
        {ticks.map((v) => (
          <g key={v}>
            <line x1={pad.l} x2={W - pad.r} y1={y(v)} y2={y(v)} className="econ-grid" />
            <text x={pad.l - 6} y={y(v) + 4} className="econ-tick">
              ${v / 1000}k
            </text>
          </g>
        ))}
        {rounds.map((r, i) =>
          r.equip !== null ? (
            <rect key={r.n} x={pad.l + i * step + step * 0.2} y={y(r.equip)} width={step * 0.6} height={Math.max(0, y(0) - y(r.equip))} rx={2} className={`econ-bar ${r.buy ?? ''}`}>
              <title>{cellTitle(r)}</title>
            </rect>
          ) : null,
        )}
        {line ? <polyline points={line} className="econ-line" /> : null}
        {rounds.map((r, i) =>
          r.startMoney !== null ? <circle key={r.n} cx={pad.l + i * step + step / 2} cy={y(r.startMoney)} r={3} className="econ-dot" /> : null,
        )}
        {rounds.map((r, i) => (
          <text key={r.n} x={pad.l + i * step + step / 2} y={H - 6} className="econ-tick mid">
            {r.n}
          </text>
        ))}
      </svg>
      <figcaption className="econ-legend small">
        <span>
          <span className="lg-bar" aria-hidden="true" /> Equipment carried into the round
        </span>
        <span>
          <span className="lg-line" aria-hidden="true" /> Money when the round began
        </span>
      </figcaption>
    </figure>
  )
}

export interface RoundsMatch {
  readonly id: string
  readonly label: string
  readonly rounds: Cs2Rounds
}

export function RoundsPanel({ profile, matches, insights, onPlaybook }: { profile: GameProfile; matches: readonly RoundsMatch[]; insights?: readonly RoundInsight[]; onPlaybook?: (id: string) => void }) {
  const [pick, setPick] = useState<string>(matches[matches.length - 1]?.id ?? '')
  const book = useMemo(() => new Map((profile.knowledge.playbook ?? []).map((e) => [e.id, e])), [profile])
  const chosen = matches.find((m) => m.id === pick) ?? matches[matches.length - 1]
  const lines = useMemo(() => insights ?? roundInsights(matches.map((m) => m.rounds)), [insights, matches])
  if (!chosen)
    return (
      <div className="empty small">
        No rounds yet. Turn on Game State Integration in Settings (it reads your rounds, money, kills and deaths straight from the game), or run the Counter-Strike 2 demo.
      </div>
    )
  const t = roundTotals(chosen.rounds.rounds)
  const buys = (['pistol', 'eco', 'force', 'full'] as const).filter((b) => t.byBuy[b].n > 0)
  return (
    <div className="rounds-panel">
      {matches.length > 1 ? (
        <label className="small">
          <span className="sr-only">Match</span>
          <select value={chosen.id} onChange={(e) => setPick(e.target.value)} aria-label="Which match">
            {[...matches].reverse().map((m) => (
              <option key={m.id} value={m.id}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      <div className="rounds-head">
        <b>
          {chosen.rounds.map ?? 'Unknown map'}
          {chosen.rounds.mode ? ` · ${chosen.rounds.mode}` : ''}
        </b>
        {chosen.rounds.score ? (
          <span className="badge">
            {chosen.rounds.score.ours}–{chosen.rounds.score.theirs}
          </span>
        ) : null}
        <span className="muted small">
          {t.rounds} rounds · {t.kills} kills · {t.deaths} deaths{t.damageRounds ? ` · ${Math.round(t.damage / t.damageRounds)} damage a round` : ''}
        </span>
      </div>
      <RoundStrip rounds={chosen.rounds.rounds} />
      <p className="muted small rounds-key">W won · L lost · P pistol · E eco · F force · B full buy · ● your kills · ✕ died (seconds into the round). Hover a round for its details.</p>
      <EconomyChart rounds={chosen.rounds.rounds} />
      {buys.length ? (
        <table className="rounds-table small">
          <thead>
            <tr>
              <th scope="col">Your buy</th>
              <th scope="col">Rounds</th>
              <th scope="col">Won</th>
            </tr>
          </thead>
          <tbody>
            {buys.map((b) => (
              <tr key={b}>
                <td>{BUY[b].label}</td>
                <td>{t.byBuy[b].n}</td>
                <td>
                  {t.byBuy[b].won} ({Math.round((100 * t.byBuy[b].won) / t.byBuy[b].n)}%)
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {lines.length ? (
        <ul className="pitch-insights">
          {lines.map((l) => (
            <li key={l.id}>
              {l.text}
              {l.playbook && book.has(l.playbook) && onPlaybook ? (
                <>
                  {' '}
                  <button type="button" className="link-btn small" onClick={() => onPlaybook(l.playbook!)}>
                    {book.get(l.playbook)!.title} →
                  </button>
                </>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      <p className="muted small">
        From the game’s own data feed: only your state (the game sends nothing about other players while you play). Your buy is judged by the equipment you carried in; your team’s
        buys are not known.
      </p>
    </div>
  )
}

/** The game's data feed now, and this match's rounds so far (Coach screen). */
export function LiveRounds({ report }: { report: RoundsReport }) {
  const s = report.status
  return (
    <div className="live-rounds">
      <p className="small" style={{ margin: 0 }}>
        {s.connected ? (
          <>
            <span className="badge ok">Game data</span> {s.map ?? 'In the menus'}
            {s.round ? ` · round ${s.round}` : ''}
            {s.side ? ` · ${s.side}` : ''}
            {s.money !== null ? ` · ${money(s.money)}` : ''}
            {s.score ? ` · ${s.score.ours}–${s.score.theirs}` : ''}
            {s.watching === 'other' ? ' · watching a teammate' : ''}
          </>
        ) : (
          <>
            <span className="badge">No game data</span>{' '}
            <span className="muted">
              {s.lastAt !== null ? 'No update for a while: is Counter-Strike 2 running?' : 'Turn on Game State Integration in Settings for exact rounds, money, kills and deaths.'}
            </span>
          </>
        )}
      </p>
      {report.match?.rounds.length ? <RoundStrip rounds={report.match.rounds} /> : null}
    </div>
  )
}
