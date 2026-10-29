// Counter-Strike 2 demo review: open a match demo (.dem), pick yourself, and
// see your rounds, duels, trades, damage, utility and where you died, on the
// map. The demo is read on this PC in the desktop app; nothing is sent.

import { useMemo, useState, useSyncExternalStore } from 'react'
import type { SightlineBridge } from '../../../shared/bridge.ts'
import { isDemoData, onOverview, reviewDemo, type DemoData, type DemoReview } from '../../core/cs2/demo.ts'
import type { LayoutLevel } from '../../core/map/layout.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import { useOfficialSource } from '../officialSource.ts'
import type { CoachSession } from '../session.ts'
import { Notice } from './common.tsx'
import { useOfficialPicture } from './MapView.tsx'

// Kept while the app is open, so switching tabs does not lose the demo.
let lastDemo: { file: string; data: DemoData } | null = null

export function DemoReviewPanel({ session, profile, settings, onSettings, bridge }: { session: CoachSession; profile: GameProfile; settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; bridge: SightlineBridge }) {
  const [demo, setDemo] = useState(lastDemo)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [picked, setPicked] = useState<string | null>(null)
  const who = picked ?? (demo?.data.players.some((p) => p.steamid === settings.steamId) ? settings.steamId : null)
  const review = useMemo(() => (demo && who ? reviewDemo(demo.data, who) : null), [demo, who])

  const open = async () => {
    setBusy(true)
    setError(null)
    const r = await bridge.cs2DemoOpen().catch(() => ({ ok: false as const, error: 'Could not read the demo.' }))
    setBusy(false)
    if (!r.ok) return r.error ? setError(r.error) : undefined
    if (!isDemoData(r.data)) return setError('The demo could not be read.')
    lastDemo = { file: r.file, data: r.data }
    setDemo(lastDemo)
    setPicked(null)
  }

  return (
    <section className="panel" aria-labelledby="demo-h">
      <div className="panel-head">
        <h2 id="demo-h">Demo review</h2>
        <div className="btn-row">
          {demo ? (
            <span className="muted small">
              {demo.file} · {demo.data.map} · {demo.data.rounds.length} rounds
            </span>
          ) : null}
          <button type="button" className="btn sm" disabled={busy} onClick={() => void open()}>
            {busy ? 'Reading the demo…' : demo ? 'Open another demo' : 'Open a demo'}
          </button>
        </div>
      </div>
      {error ? <Notice level="warning">{error}</Notice> : null}
      {!demo ? (
        <p className="muted small" style={{ margin: 0 }}>
          Open a match demo (.dem): download it from the game (Watch → Your Matches) or from FACEIT. The coach reads it on this PC and shows your rounds, opening duels, trades, damage, utility and where you died, on the map. A demo holds both teams' positions, so it is for after the match.
        </p>
      ) : (
        <>
          <label className="small demo-who">
            <span>You are</span>
            <select
              value={who ?? ''}
              onChange={(e) => {
                setPicked(e.target.value)
                onSettings({ steamId: e.target.value })
              }}
            >
              <option value="">Pick yourself…</option>
              {demo.data.players.map((p) => (
                <option key={p.steamid} value={p.steamid}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          {review ? <ReviewBody review={review} session={session} profile={profile} /> : who ? <p className="muted small">You are not in this demo's rounds.</p> : null}
        </>
      )}
    </section>
  )
}

function ReviewBody({ review: r, session, profile }: { review: DemoReview; session: CoachSession; profile: GameProfile }) {
  const t = r.totals
  const won = r.rounds.filter((x) => x.won).length
  const lost = r.rounds.filter((x) => x.won === false).length
  const thrown = t.nades.smoke + t.nades.flash + t.nades.he + t.nades.fire
  return (
    <>
      <div className="stat-row demo-stats">
        <Stat n={`${won}–${lost}`} l="Rounds won–lost" />
        <Stat n={`${t.kills}–${t.deaths}`} l={`Kills–deaths (${t.assists} assists)`} />
        <Stat n={String(t.adr)} l="Damage a round" />
        <Stat n={`${t.kast}%`} l="KAST" />
        <Stat n={`${t.openingWon}–${t.openingLost}`} l="Opening duels won–lost" />
        <Stat n={`${t.tradeKills} / ${t.deathsTraded}`} l="Trade kills / your deaths traded" />
        <Stat n={`${t.headshotPct}%`} l="Headshot kills" />
        <Stat n={String(thrown)} l={`Grenades (${t.nades.smoke} smoke, ${t.nades.flash} flash, ${t.nades.he} HE, ${t.nades.fire} fire)`} />
        <Stat n={`${t.enemiesFlashed}`} l={`Enemies flashed (${t.flashSeconds} s; ${t.teammatesFlashed} teammates)`} />
      </div>
      {r.findings.length ? (
        <ul className="week-lines">
          {r.findings.map((f) => (
            <li key={f}>{f}</li>
          ))}
        </ul>
      ) : null}
      <div className="grid-2">
        <DemoMap review={r} session={session} profile={profile} />
        <div className="small">
          {r.deathPlaces.length ? (
            <>
              <b>Where you died</b>
              <ul style={{ margin: '4px 0 8px', paddingLeft: 18 }}>
                {r.deathPlaces.slice(0, 6).map((p) => (
                  <li key={p.place}>
                    {p.place}: {p.deaths}
                    {p.first ? <span className="muted"> ({p.first} first in the round)</span> : null}
                  </li>
                ))}
              </ul>
            </>
          ) : null}
          {r.weapons.length ? (
            <>
              <b>Your kills by weapon</b>
              <p style={{ margin: '4px 0 0' }}>{r.weapons.map((w) => `${w.weapon} ${w.kills}`).join(', ')}</p>
            </>
          ) : null}
        </div>
      </div>
      <div className="table-scroll">
        <table className="table demo-rounds">
          <thead>
            <tr>
              <th>Round</th>
              <th>Side</th>
              <th>Result</th>
              <th>K</th>
              <th>A</th>
              <th>Dmg</th>
              <th>Opening</th>
              <th>Died</th>
              <th>Your buy / team</th>
              <th>Grenades</th>
            </tr>
          </thead>
          <tbody>
            {r.rounds.map((x) => (
              <tr key={x.n}>
                <td>{x.n}</td>
                <td>{x.side ?? '–'}</td>
                <td>{x.won === null ? '–' : x.won ? 'Won' : 'Lost'}</td>
                <td>{x.kills}</td>
                <td>{x.assists}</td>
                <td>{x.damage}</td>
                <td>{x.opening === 'won' ? 'Won' : x.opening === 'lost' ? 'Died first' : ''}</td>
                <td>{x.died ? `${x.deathPlace ?? 'yes'}${x.killedBy ? ` (${x.killedBy})` : ''}${x.traded ? ', traded' : ''}` : 'Lived'}</td>
                <td>{x.equip === null ? '–' : `$${x.equip} / $${x.teamEquip ?? '–'}`}</td>
                <td>{x.nades || ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <span className="muted small">
        KAST: rounds with a kill, assist, survival or a death traded within 5 seconds. Damage counts at most 100 a player a round. Buy is the equipment value when freeze time ended.
      </span>
    </>
  )
}

function DemoMap({ review: r, session, profile }: { review: DemoReview; session: CoachSession; profile: GameProfile }) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const official = useOfficialSource(session, state.overheads, profile.id)
  const map = profile.knowledge.maps?.find((m) => m.id === r.map.replace(/^(de|cs|ar)_/, ''))
  const radar = map?.radar
  const [level, setLevel] = useState<LayoutLevel>('upper')
  const pic = useOfficialPicture(official, radar ? map.id : null, { layout: true, level })
  if (!map || !radar) return <p className="muted small">No overview of {r.map} in the pack, so your kills and deaths are listed instead of drawn.</p>
  const S = radar.imageSize ?? 1024
  const square = pic && Math.abs(pic.base.srcW - pic.base.srcH) <= 2 ? pic : null
  const lower = radar.lowerBelowZ
  const marks = r.marks.filter((m) => lower === undefined || (level === 'lower') === m.z < lower)
  return (
    <figure className="utility-map demo-map">
      {lower !== undefined ? (
        <div className="btn-row" role="group" aria-label="Level">
          {(['upper', 'lower'] as const).map((l) => (
            <button key={l} type="button" className={`btn sm demo-level${level === l ? ' on' : ''}`} aria-pressed={level === l} onClick={() => setLevel(l)}>
              {l === 'upper' ? 'Upper level' : 'Lower level'}
            </button>
          ))}
        </div>
      ) : null}
      <svg viewBox={`0 0 ${S} ${S}`} role="img" aria-label={`${map.name}: ${marks.filter((m) => m.kind === 'kill').length} of your kills and ${marks.filter((m) => m.kind === 'death').length} of your deaths`}>
        {square ? <image href={square.picture.src} x={0} y={0} width={S} height={S} opacity={0.85} /> : <rect x={0} y={0} width={S} height={S} className="utility-bg" />}
        {marks.map((m, i) => {
          const [u, v] = onOverview(radar, m.x, m.y)
          const x = u * S
          const y = v * S
          return (
            <g key={i} className={`demo-mark ${m.kind}`}>
              <title>{`Round ${m.round}: ${m.kind === 'kill' ? 'you killed someone' : 'you died'}${m.place ? ` at ${m.place}` : ''} (${m.weapon})`}</title>
              {m.kind === 'kill' ? <rect x={x - 9} y={y - 9} width={18} height={18} transform={`rotate(45 ${x} ${y})`} /> : <circle cx={x} cy={y} r={11} />}
            </g>
          )
        })}
      </svg>
      <figcaption className="muted small">
        <span className="lg kill" /> where you killed someone · <span className="lg death" /> where you died. {square?.layout ? `On ${map.name}’s real layout.` : square ? `On ${map.name}’s overview from your game files.` : ''}
      </figcaption>
    </figure>
  )
}

function Stat({ n, l }: { n: string; l: string }) {
  return (
    <div className="stat">
      <div className="n">{n}</div>
      <div className="l">{l}</div>
    </div>
  )
}
