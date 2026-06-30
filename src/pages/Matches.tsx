import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Match, Competition, Venue } from '../types'
import { useData, useActions } from '../store/store'
import { teamSeasonStats, recent, form } from '../analytics/selectors'
import {
  fmtDate,
  num,
  pct,
  RESULT_COLOR,
  resultOf,
  genId,
  clamp,
} from '../utils/format'
import { FORMATION_NAMES } from '../data/formations'
import { StatCard } from '../components/ui/StatCard'
import { Modal } from '../components/ui/Modal'
import { FormPips, EmptyState } from '../components/ui/Primitives'

const COMPETITIONS: Competition[] = ['League', 'Cup', 'Friendly', 'Continental']
const VENUE_FILTERS: (Venue | 'All')[] = ['All', 'Home', 'Away']

const COMP_COLOR: Record<Competition, string> = {
  League: '#22c55e',
  Cup: '#a855f7',
  Friendly: '#64748b',
  Continental: '#38bdf8',
}

type CompFilter = Competition | 'All'

// Quick-entry draft for recording a result. Player-level stats and lineups are
// captured on the match detail page, so this form only covers team totals.
interface MatchDraft {
  date: string
  opponent: string
  venue: Venue
  competition: Competition
  formation: string
  goalsFor: number
  goalsAgainst: number
  possession: number
  xgFor: number
  xgAgainst: number
  shotsFor: number
  shotsAgainst: number
  cornersFor: number
  cornersAgainst: number
  notes: string
}

function blankDraft(): MatchDraft {
  return {
    date: new Date().toISOString().slice(0, 10),
    opponent: '',
    venue: 'Home',
    competition: 'League',
    formation: FORMATION_NAMES[0] ?? '4-3-3',
    goalsFor: 0,
    goalsAgainst: 0,
    possession: 50,
    xgFor: 0,
    xgAgainst: 0,
    shotsFor: 0,
    shotsAgainst: 0,
    cornersFor: 0,
    cornersAgainst: 0,
    notes: '',
  }
}

export function Matches() {
  const data = useData()
  const actions = useActions()
  const navigate = useNavigate()

  const [compFilter, setCompFilter] = useState<CompFilter>('All')
  const [venueFilter, setVenueFilter] = useState<Venue | 'All'>('All')
  const [modalOpen, setModalOpen] = useState(false)

  const matches = data.matches
  const stats = useMemo(() => teamSeasonStats(matches), [matches])
  const recentForm = useMemo(() => form(matches, 8), [matches])

  // Only show competition chips for competitions that actually appear.
  const presentComps = useMemo(() => {
    const seen = new Set<Competition>()
    for (const m of matches) seen.add(m.competition)
    return COMPETITIONS.filter((c) => seen.has(c))
  }, [matches])

  const rows = useMemo(() => {
    return recent(matches).filter((m) => {
      if (compFilter !== 'All' && m.competition !== compFilter) return false
      if (venueFilter !== 'All' && m.venue !== venueFilter) return false
      return true
    })
  }, [matches, compFilter, venueFilter])

  const subtitle =
    stats.played > 0
      ? `${stats.wins}W–${stats.draws}D–${stats.losses}L · ${stats.points} pts · ${num(stats.ppg, 2)} PPG`
      : 'No matches recorded yet'

  function handleDelete(e: React.MouseEvent, id: string) {
    e.stopPropagation()
    actions.removeMatch(id)
  }

  function save(draft: MatchDraft) {
    const match: Match = {
      id: genId('m'),
      date: draft.date,
      opponent: draft.opponent.trim(),
      venue: draft.venue,
      competition: draft.competition,
      formation: draft.formation,
      goalsFor: draft.goalsFor,
      goalsAgainst: draft.goalsAgainst,
      possession: draft.possession,
      xgFor: draft.xgFor,
      xgAgainst: draft.xgAgainst,
      shotsFor: draft.shotsFor,
      shotsAgainst: draft.shotsAgainst,
      cornersFor: draft.cornersFor,
      cornersAgainst: draft.cornersAgainst,
      lineup: [],
      events: [],
      playerStats: [],
      notes: draft.notes.trim(),
    }
    actions.addMatch(match)
    setModalOpen(false)
  }

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <div className="page-title">Matches</div>
          <div className="page-subtitle">{subtitle}</div>
        </div>
        <button className="btn primary" onClick={() => setModalOpen(true)}>
          + Add match
        </button>
      </div>

      {/* Summary stats */}
      <div className="grid grid-4">
        <StatCard label="Played" value={stats.played} icon="📅" />
        <StatCard
          label="Record (W-D-L)"
          value={`${stats.wins}-${stats.draws}-${stats.losses}`}
          icon="📊"
          accent="#38bdf8"
          sub={<span className="muted">{pct(stats.winRate, 0)} win rate</span>}
        />
        <StatCard
          label="Goals (GF:GA)"
          value={`${stats.goalsFor}:${stats.goalsAgainst}`}
          icon="🥅"
          accent={stats.goalDiff >= 0 ? '#22c55e' : '#ef4444'}
          sub={
            <span className={stats.goalDiff >= 0 ? 'text-green' : 'text-red'}>
              {stats.goalDiff >= 0 ? '+' : ''}
              {stats.goalDiff} GD
            </span>
          }
        />
        <StatCard
          label="Points"
          value={stats.points}
          icon="🏆"
          accent="#eab308"
          sub={<span className="muted">{num(stats.ppg, 2)} per game</span>}
        />
      </div>

      {/* Form */}
      {recentForm.length > 0 && (
        <div className="card card-pad" style={{ marginTop: 16 }}>
          <div className="row between center wrap gap-sm">
            <div className="row gap-sm center">
              <span className="muted tiny bold" style={{ textTransform: 'uppercase', letterSpacing: 0.5 }}>
                Form
              </span>
              <span className="faint tiny">last {recentForm.length}</span>
            </div>
            <FormPips form={recentForm} />
          </div>
        </div>
      )}

      {/* Filters + table */}
      <div className="card" style={{ marginTop: 16 }}>
        <div className="card-pad row between wrap gap-sm">
          <div className="btn-group">
            <button
              className={compFilter === 'All' ? 'active' : ''}
              onClick={() => setCompFilter('All')}
            >
              All
            </button>
            {presentComps.map((c) => (
              <button
                key={c}
                className={compFilter === c ? 'active' : ''}
                onClick={() => setCompFilter(c)}
              >
                {c}
              </button>
            ))}
          </div>
          <div className="btn-group">
            {VENUE_FILTERS.map((v) => (
              <button
                key={v}
                className={venueFilter === v ? 'active' : ''}
                onClick={() => setVenueFilter(v)}
              >
                {v}
              </button>
            ))}
          </div>
        </div>

        {rows.length === 0 ? (
          <div className="card-pad">
            <EmptyState
              icon="⚽"
              title={matches.length === 0 ? 'No matches recorded yet' : 'No matches match your filters'}
              hint={
                matches.length === 0
                  ? 'Add your first result to start tracking the season.'
                  : 'Try clearing the competition or venue filter.'
              }
            />
          </div>
        ) : (
          <div className="scroll-x">
            <table className="table clickable">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Comp</th>
                  <th className="center-col">Venue</th>
                  <th>Opponent</th>
                  <th className="center-col">Score</th>
                  <th className="num">Poss</th>
                  <th className="num">xG</th>
                  <th className="center-col">Del</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((m) => {
                  const r = resultOf(m.goalsFor, m.goalsAgainst)
                  const home = m.venue === 'Home'
                  return (
                    <tr key={m.id} onClick={() => navigate(`/matches/${m.id}`)}>
                      <td className="tnum">{fmtDate(m.date)}</td>
                      <td>
                        <span
                          className="badge"
                          style={{
                            borderColor: `${COMP_COLOR[m.competition]}55`,
                            color: COMP_COLOR[m.competition],
                          }}
                        >
                          <span className="dot" style={{ background: COMP_COLOR[m.competition] }} />
                          {m.competition}
                        </span>
                      </td>
                      <td className="center-col">
                        <span
                          className="pos-pill"
                          style={{
                            background: home ? 'rgba(34,197,94,0.18)' : 'rgba(56,189,248,0.18)',
                            color: home ? '#22c55e' : '#38bdf8',
                          }}
                          title={m.venue}
                        >
                          {home ? 'H' : 'A'}
                        </span>
                      </td>
                      <td className="bold">{m.opponent}</td>
                      <td className="center-col">
                        <div className="row gap-sm center" style={{ justifyContent: 'center' }}>
                          <span className="mono bold tnum">
                            {m.goalsFor}–{m.goalsAgainst}
                          </span>
                          <span
                            className="form-pip"
                            style={{ background: RESULT_COLOR[r] }}
                            title={r === 'W' ? 'Win' : r === 'D' ? 'Draw' : 'Loss'}
                          >
                            {r}
                          </span>
                        </div>
                      </td>
                      <td className="num tnum">{m.possession}%</td>
                      <td className="num tnum">
                        {num(m.xgFor, 1)}–{num(m.xgAgainst, 1)}
                      </td>
                      <td className="center-col">
                        <button
                          className="btn danger sm"
                          onClick={(e) => handleDelete(e, m.id)}
                          title="Remove match"
                        >
                          ✕
                        </button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {modalOpen && (
        <MatchForm onCancel={() => setModalOpen(false)} onSave={save} />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Quick add-result form
// ---------------------------------------------------------------------------
function MatchForm({
  onCancel,
  onSave,
}: {
  onCancel: () => void
  onSave: (draft: MatchDraft) => void
}) {
  const [form, setForm] = useState<MatchDraft>(blankDraft)
  const opponentValid = form.opponent.trim().length > 0
  const dateValid = form.date.length > 0
  const valid = opponentValid && dateValid

  function set<K extends keyof MatchDraft>(key: K, value: MatchDraft[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  const result = resultOf(form.goalsFor, form.goalsAgainst)

  return (
    <Modal
      title="Add match result"
      onClose={onCancel}
      wide
      footer={
        <div className="row gap-sm" style={{ justifyContent: 'flex-end' }}>
          <button className="btn ghost" onClick={onCancel}>
            Cancel
          </button>
          <button className="btn primary" disabled={!valid} onClick={() => valid && onSave(form)}>
            Add match
          </button>
        </div>
      }
    >
      {/* Fixture */}
      <div className="grid grid-2">
        <div className="field">
          <label>Date</label>
          <input
            className="input"
            type="date"
            value={form.date}
            onChange={(e) => set('date', e.target.value)}
          />
          {!dateValid && <span className="tiny text-red">Date is required</span>}
        </div>
        <div className="field">
          <label>Opponent</label>
          <input
            className="input"
            value={form.opponent}
            onChange={(e) => set('opponent', e.target.value)}
            placeholder="e.g. Riverside FC"
            autoFocus
          />
          {!opponentValid && <span className="tiny text-red">Opponent is required</span>}
        </div>

        <div className="field">
          <label>Venue</label>
          <select
            className="select"
            value={form.venue}
            onChange={(e) => set('venue', e.target.value as Venue)}
          >
            <option value="Home">Home</option>
            <option value="Away">Away</option>
          </select>
        </div>
        <div className="field">
          <label>Competition</label>
          <select
            className="select"
            value={form.competition}
            onChange={(e) => set('competition', e.target.value as Competition)}
          >
            {COMPETITIONS.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>

        <div className="field">
          <label>Formation</label>
          <select
            className="select"
            value={form.formation}
            onChange={(e) => set('formation', e.target.value)}
          >
            {FORMATION_NAMES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>Possession (%)</label>
          <input
            className="input"
            type="number"
            min={0}
            max={100}
            value={form.possession}
            onChange={(e) => set('possession', clamp(Math.trunc(Number(e.target.value)), 0, 100))}
          />
        </div>
      </div>

      {/* Scoreline */}
      <div className="divider" />
      <div className="row between center" style={{ marginBottom: 10 }}>
        <span className="bold">Scoreline</span>
        <span className="row gap-sm center">
          <span className="mono bold tnum" style={{ fontSize: 15 }}>
            {form.goalsFor}–{form.goalsAgainst}
          </span>
          <span className="form-pip" style={{ background: RESULT_COLOR[result] }}>
            {result}
          </span>
        </span>
      </div>
      <div className="grid grid-2">
        <div className="field">
          <label>Goals for</label>
          <input
            className="input"
            type="number"
            min={0}
            value={form.goalsFor}
            onChange={(e) => set('goalsFor', Math.max(0, Math.trunc(Number(e.target.value))))}
          />
        </div>
        <div className="field">
          <label>Goals against</label>
          <input
            className="input"
            type="number"
            min={0}
            value={form.goalsAgainst}
            onChange={(e) => set('goalsAgainst', Math.max(0, Math.trunc(Number(e.target.value))))}
          />
        </div>
      </div>

      {/* Team stats */}
      <div className="divider" />
      <div className="bold" style={{ marginBottom: 10 }}>
        Match statistics
      </div>
      <div className="grid grid-2">
        <div className="field">
          <label>xG for</label>
          <input
            className="input"
            type="number"
            min={0}
            step={0.1}
            value={form.xgFor}
            onChange={(e) => set('xgFor', Math.max(0, Number(e.target.value)))}
          />
        </div>
        <div className="field">
          <label>xG against</label>
          <input
            className="input"
            type="number"
            min={0}
            step={0.1}
            value={form.xgAgainst}
            onChange={(e) => set('xgAgainst', Math.max(0, Number(e.target.value)))}
          />
        </div>

        <div className="field">
          <label>Shots for</label>
          <input
            className="input"
            type="number"
            min={0}
            value={form.shotsFor}
            onChange={(e) => set('shotsFor', Math.max(0, Math.trunc(Number(e.target.value))))}
          />
        </div>
        <div className="field">
          <label>Shots against</label>
          <input
            className="input"
            type="number"
            min={0}
            value={form.shotsAgainst}
            onChange={(e) => set('shotsAgainst', Math.max(0, Math.trunc(Number(e.target.value))))}
          />
        </div>

        <div className="field">
          <label>Corners for</label>
          <input
            className="input"
            type="number"
            min={0}
            value={form.cornersFor}
            onChange={(e) => set('cornersFor', Math.max(0, Math.trunc(Number(e.target.value))))}
          />
        </div>
        <div className="field">
          <label>Corners against</label>
          <input
            className="input"
            type="number"
            min={0}
            value={form.cornersAgainst}
            onChange={(e) => set('cornersAgainst', Math.max(0, Math.trunc(Number(e.target.value))))}
          />
        </div>
      </div>

      <div className="field" style={{ marginTop: 14 }}>
        <label>Notes</label>
        <textarea
          className="textarea"
          rows={3}
          value={form.notes}
          onChange={(e) => set('notes', e.target.value)}
          placeholder="Post-match analysis…"
        />
      </div>

      <p className="muted tiny" style={{ marginTop: 10 }}>
        Detailed per-player stats and lineups are entered on the match page — this quick-add just
        records the result.
      </p>
    </Modal>
  )
}
