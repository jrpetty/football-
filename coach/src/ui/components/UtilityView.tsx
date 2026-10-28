// Grenade lineups for a map: where to stand, where to aim, how to throw and
// what each one does, drawn from where it is thrown to where it lands, on the
// game's own overview when the player has imported it (else on a sketch of
// the callouts). Valve's own lineups carry exact spots from the game files;
// researched ones are drawn callout to callout, dashed. Executes and retakes
// number their grenades in throw order. Every grenade type also has a letter,
// so colour is never the only cue.

import { useMemo, useState } from 'react'
import type { GameMap, GameProfile, GrenadeType, UtilityLineup, UtilityPlan } from '../../core/profile/schema.ts'
import { Segmented } from './common.tsx'
import { useOfficialPicture, type OfficialSource } from './MapView.tsx'

type Kind = 'smoke' | 'flash' | 'he' | 'fire' | 'decoy'
const KIND: Readonly<Record<GrenadeType, Kind>> = { smoke: 'smoke', flash: 'flash', he: 'he', molotov: 'fire', incendiary: 'fire', decoy: 'decoy' }
const KIND_LABEL: Readonly<Record<Kind, { label: string; letter: string }>> = {
  smoke: { label: 'Smokes', letter: 'S' },
  flash: { label: 'Flashes', letter: 'F' },
  he: { label: 'HE', letter: 'H' },
  fire: { label: 'Molotovs', letter: 'M' },
  decoy: { label: 'Decoys', letter: 'D' },
}
const TYPE_NAME: Readonly<Record<GrenadeType, string>> = { smoke: 'Smoke', flash: 'Flash', he: 'HE grenade', molotov: 'Molotov', incendiary: 'Incendiary', decoy: 'Decoy' }
const THROW_NAME: Readonly<Record<string, string>> = {
  'left-click': 'Left click',
  'right-click': 'Right click (short lob)',
  'left+right': 'Both buttons (medium)',
  'jump-throw': 'Jump-throw',
  'run-jump-throw': 'Run, then jump-throw',
  'walk-throw': 'Walk and throw',
  'crouch-throw': 'Crouch and throw',
  'run-throw': 'Run and throw',
}

const domain = (url: string) => {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return ''
  }
}

/** Where a lineup starts and lands on the overview, and whether that is exact (game files) or by callout. */
function ends(l: UtilityLineup, px: Readonly<Record<string, readonly [number, number]>>): { a: readonly [number, number]; b: readonly [number, number]; exact: boolean } | null {
  const a = l.standPx ?? px[l.from]
  const b = l.landPx ?? px[l.to]
  if (!a || !b) return null
  return { a, b, exact: !!l.standPx && !!l.landPx }
}

export function UtilityPanel({ profile, official, current }: { profile: GameProfile; official?: OfficialSource; current?: string | null }) {
  const maps = useMemo(() => (profile.knowledge.maps ?? []).filter((m) => m.utility?.length), [profile])
  const start = maps.find((m) => m.name === current) ?? maps[0]
  const [mapId, setMapId] = useState(start?.id ?? '')
  const [side, setSide] = useState<'all' | 'T' | 'CT'>('all')
  const [kind, setKind] = useState<'all' | Kind>('all')
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const [planId, setPlanId] = useState<string | null>(null)
  const m = maps.find((x) => x.id === mapId) ?? start
  if (!m) return <p className="muted small">No grenade lineups in this pack.</p>
  const byId = new Map(m.utility!.map((l) => [l.id, l]))
  const q = query.trim().toLowerCase()
  const list = m.utility!.filter(
    (l) =>
      (side === 'all' || l.side === side) &&
      (kind === 'all' || KIND[l.type] === kind) &&
      (!q || [l.name, l.from, l.to, l.toDetail ?? '', l.when ?? '', l.purpose].some((x) => x.toLowerCase().includes(q))),
  )
  const plan = planId ? (m.utilityPlans ?? []).find((p) => p.id === planId) ?? null : null
  const shown = plan ? plan.utility.map((id) => byId.get(id)).filter((l): l is UtilityLineup => !!l) : list
  const counts = (['smoke', 'flash', 'he', 'fire', 'decoy'] as const).filter((k) => m.utility!.some((l) => KIND[l.type] === k))
  return (
    <div className="utility-panel">
      <div className="arsenal-controls">
        {maps.length > 1 ? (
          <label className="small">
            <span className="sr-only">Map</span>
            <select
              value={m.id}
              onChange={(e) => {
                setMapId(e.target.value)
                setOpen(null)
                setPlanId(null)
              }}
              aria-label="Map"
            >
              {maps.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.name} ({x.utility!.length})
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <Segmented label="Side" value={side} onChange={(v) => (setSide(v), setPlanId(null))} options={[{ value: 'all', label: 'Both sides' }, { value: 'T', label: 'T' }, { value: 'CT', label: 'CT' }]} />
        <Segmented
          label="Grenade"
          value={kind}
          onChange={(v) => (setKind(v), setPlanId(null))}
          options={[{ value: 'all' as const, label: 'All' }, ...counts.map((k) => ({ value: k, label: KIND_LABEL[k].label }))]}
        />
        <input type="search" placeholder="Search callouts, sites, purpose…" value={query} onChange={(e) => (setQuery(e.target.value), setPlanId(null))} aria-label="Search lineups" />
      </div>
      <div className="utility-body">
        <UtilityMap map={m} lineups={shown} official={official} selected={open} plan={plan} onPick={(id) => setOpen(id === open ? null : id)} />
        <div className="utility-side">
          {m.utilityPlans?.length ? (
            <div className="utility-plans">
              <h3 className="small">Executes and retakes</h3>
              <ul>
                {m.utilityPlans
                  .filter((p) => side === 'all' || p.side === side)
                  .map((p) => (
                    <li key={p.id}>
                      <button type="button" className={`plan-btn${planId === p.id ? ' on' : ''}`} aria-pressed={planId === p.id} onClick={() => setPlanId(planId === p.id ? null : p.id)}>
                        <span className={`badge ${p.kind === 'execute' ? 'warn' : 'ok'}`}>{p.kind === 'execute' ? `${p.side} execute` : 'CT retake'}</span> {p.name}
                        <span className="muted"> · {p.site}</span>
                      </button>
                    </li>
                  ))}
              </ul>
              {plan ? <PlanSteps plan={plan} byId={byId} onOpen={setOpen} /> : null}
            </div>
          ) : null}
          <p className="muted small" style={{ margin: '4px 0' }}>
            {plan ? `${shown.length} lineups in this ${plan.kind}` : `${list.length} of ${m.utility!.length} lineups`} · {(plan ? shown : list).filter((l) => l.official).length} from Valve’s own guide in the game (exact spots, solid lines); the rest from guides, drawn callout to callout (dashed).
          </p>
          <ul className="lineup-list">
            {(plan ? shown : list).map((l, i) => (
              <LineupRow key={l.id} l={l} n={plan ? i + 1 : null} open={open === l.id} onToggle={() => setOpen(open === l.id ? null : l.id)} />
            ))}
          </ul>
          {!list.length && !plan ? <p className="muted small">Nothing matches.</p> : null}
        </div>
      </div>
    </div>
  )
}

function PlanSteps({ plan, byId, onOpen }: { plan: UtilityPlan; byId: Map<string, UtilityLineup>; onOpen: (id: string) => void }) {
  return (
    <div className="plan-steps small">
      <p>{plan.how}</p>
      <ol>
        {plan.utility.map((id) => {
          const l = byId.get(id)
          return l ? (
            <li key={id}>
              <button type="button" className="link-btn" onClick={() => onOpen(id)}>
                {l.name}
              </button>{' '}
              <span className="muted">
                ({TYPE_NAME[l.type].toLowerCase()} from {l.from})
              </span>
            </li>
          ) : null
        })}
      </ol>
    </div>
  )
}

function LineupRow({ l, n, open, onToggle }: { l: UtilityLineup; n: number | null; open: boolean; onToggle: () => void }) {
  const k = KIND[l.type]
  return (
    <li className={`lineup ${open ? 'open' : ''}`}>
      <button type="button" className="lineup-row" onClick={onToggle} aria-expanded={open}>
        <span className={`nade-glyph ${k}`} aria-hidden="true">
          {n ?? KIND_LABEL[k].letter}
        </span>
        <span className="lineup-name">
          <b>{l.name}</b>
          <span className="muted small">
            {' '}
            {TYPE_NAME[l.type]} · {l.side} · {l.from} → {l.to}
            {l.when ? ` · ${l.when}` : ''}
          </span>
          {l.official ? <span className="badge ok">Valve</span> : null}
          {l.unverified ? <span className="badge neutral">unverified</span> : null}
        </span>
      </button>
      {open ? (
        <div className="lineup-detail small">
          <p>{l.purpose}</p>
          {l.toDetail ? (
            <p>
              <span className="label">Lands</span> {l.toDetail}
            </p>
          ) : null}
          {l.stand ? (
            <p>
              <span className="label">Stand</span> {l.stand}
            </p>
          ) : null}
          {l.aim ? (
            <p>
              <span className="label">Aim</span> {l.aim}
            </p>
          ) : null}
          {l.throw ? (
            <p>
              <span className="label">Throw</span> {THROW_NAME[l.throw] ?? l.throw}
            </p>
          ) : null}
          {l.pairsWith?.length ? (
            <p>
              <span className="label">With</span> {l.pairsWith.join(', ')}
            </p>
          ) : null}
          {l.note ? <p className="muted">{l.note}</p> : null}
          {l.sources?.length ? (
            <ul className="sources" aria-label="Sources">
              {l.sources.map((s) => (
                <li key={s.url} title={s.url}>
                  {s.title} <span className="muted">· {domain(s.url)}</span>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </li>
  )
}

/** The overview with the lineups drawn from where they are thrown to where they land. */
function UtilityMap({
  map,
  lineups,
  official,
  selected,
  plan,
  onPick,
}: {
  map: GameMap
  lineups: readonly UtilityLineup[]
  official?: OfficialSource
  selected: string | null
  plan: UtilityPlan | null
  onPick: (id: string) => void
}) {
  const S = map.radar?.imageSize ?? 1024
  const px = map.radar?.calloutPx ?? {}
  const pic = useOfficialPicture(official, map.id, { layout: true })
  // Only a square overview (the game's own, or the pack's drawing of the real layout) lines up with these pixels.
  const square = pic && Math.abs(pic.base.srcW - pic.base.srcH) <= 2 ? pic : null
  const drawn = lineups.map((l, i) => ({ l, i, e: ends(l, px) })).filter((x) => x.e)
  const sel = selected ? drawn.find((x) => x.l.id === selected) : null
  const order = [...drawn.filter((x) => x.l.id !== selected), ...(sel ? [sel] : [])]
  return (
    <figure className="utility-map">
      <svg viewBox={`0 0 ${S} ${S}`} role="img" aria-label={`${map.name}: ${drawn.length} grenade lineups drawn from where they are thrown to where they land`}>
        <defs>
          {(['smoke', 'flash', 'he', 'fire', 'decoy'] as const).map((k) => (
            <marker key={k} id={`arrow-${k}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" className={`nade-fill ${k}`} />
            </marker>
          ))}
        </defs>
        {square ? <image href={square.picture.src} x={0} y={0} width={S} height={S} opacity={0.85} /> : <rect x={0} y={0} width={S} height={S} className="utility-bg" />}
        {Object.entries(px).map(([name, [x, y]]) => (
          <g key={name} className="callout-mark">
            <circle cx={x} cy={y} r={4} />
            <text x={x + 7} y={y + 4}>
              {name}
            </text>
          </g>
        ))}
        {order.map(({ l, i, e }) => {
          const k = KIND[l.type]
          const dim = !!selected && l.id !== selected
          return (
            <g key={l.id} className={`lineup-mark ${k}${dim ? ' dim' : ''}${l.id === selected ? ' on' : ''}`} onClick={() => onPick(l.id)} role="button" tabIndex={-1}>
              <title>{`${l.name}: ${TYPE_NAME[l.type].toLowerCase()} from ${l.from} to ${l.to}`}</title>
              <line x1={e!.a[0]} y1={e!.a[1]} x2={e!.b[0]} y2={e!.b[1]} className={`nade-stroke ${k}`} strokeDasharray={e!.exact ? undefined : '10 8'} markerEnd={`url(#arrow-${k})`} />
              <circle cx={e!.a[0]} cy={e!.a[1]} r={6} className="stand" />
              <circle cx={e!.b[0]} cy={e!.b[1]} r={k === 'smoke' ? 22 : 15} className={`land nade-fill ${k}`} />
              <text x={e!.b[0]} y={e!.b[1] + 6} className="land-letter">
                {plan ? i + 1 : KIND_LABEL[k].letter}
              </text>
            </g>
          )
        })}
      </svg>
      <figcaption className="muted small">
        {square?.layout ? `On ${map.name}’s real layout, drawn from the game’s nav mesh.` : square ? `On ${map.name}’s overview from your game files.` : `A sketch of ${map.name}’s callouts.`} Solid line: exact spot from Valve’s
        guide; dashed: callout to callout. Letters: S smoke, F flash, H HE, M molotov or incendiary, D decoy.
      </figcaption>
    </figure>
  )
}
