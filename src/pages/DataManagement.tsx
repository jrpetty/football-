import { useRef, useState } from 'react'
import type { AppData, Player, Match } from '../types'
import { useData, useActions } from '../store/store'
import { resultOf } from '../utils/format'
import { overallRating } from '../analytics/selectors'
import { StatCard } from '../components/ui/StatCard'
import { Modal } from '../components/ui/Modal'

// Replace anything that is not safe in a filename with a dash so exported files
// land cleanly on every OS.
function sanitize(s: string): string {
  return (s || 'gaffer').replace(/[^a-z0-9-_]+/gi, '-').replace(/-+/g, '-').replace(/^-|-$/g, '').toLowerCase()
}

// Wrap a single value for a CSV cell — quote it and escape embedded quotes so
// commas / newlines inside notes never corrupt the column layout.
function csvCell(v: string | number): string {
  const s = String(v)
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

function csvRow(cells: (string | number)[]): string {
  return cells.map(csvCell).join(',')
}

type ImportMsg = { kind: 'ok' | 'err'; text: string } | null

export function DataManagement() {
  const data = useData()
  const actions = useActions()
  const team = data.team

  const fileRef = useRef<HTMLInputElement>(null)
  const [importMsg, setImportMsg] = useState<ImportMsg>(null)
  const [importName, setImportName] = useState<string>('')
  // In-app confirmation (native window.confirm is blocked in embedded views).
  const [confirmKind, setConfirmKind] = useState<'reset' | 'clear' | null>(null)

  // Single download helper: build a blob from text, hand the browser an anchor
  // with a download attribute, click it, then revoke the object URL.
  function download(filename: string, mime: string, text: string) {
    const blob = new Blob([text], { type: mime })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  function exportJSON() {
    const snapshot = actions.exportData()
    download(`gaffer-${sanitize(team.season)}.json`, 'application/json', JSON.stringify(snapshot, null, 2))
  }

  function exportPlayersCSV() {
    const header = [
      'name', 'number', 'position', 'positionGroup', 'age', 'nationality', 'foot', 'status',
      'pace', 'shooting', 'passing', 'dribbling', 'defending', 'physical', 'overall', 'marketValueM',
    ]
    const rows = data.players.map((p: Player) =>
      csvRow([
        p.name, p.number, p.position, p.positionGroup, p.age, p.nationality, p.foot, p.status,
        p.attributes.pace, p.attributes.shooting, p.attributes.passing, p.attributes.dribbling,
        p.attributes.defending, p.attributes.physical, overallRating(p), p.marketValueM,
      ]),
    )
    download(`gaffer-players-${sanitize(team.season)}.csv`, 'text/csv', [csvRow(header), ...rows].join('\n'))
  }

  function exportMatchesCSV() {
    const header = [
      'date', 'opponent', 'venue', 'competition', 'goalsFor', 'goalsAgainst', 'result',
      'possession', 'xgFor', 'xgAgainst', 'shotsFor', 'shotsAgainst',
    ]
    const rows = data.matches.map((m: Match) =>
      csvRow([
        m.date, m.opponent, m.venue, m.competition, m.goalsFor, m.goalsAgainst,
        resultOf(m.goalsFor, m.goalsAgainst), m.possession, m.xgFor, m.xgAgainst, m.shotsFor, m.shotsAgainst,
      ]),
    )
    download(`gaffer-matches-${sanitize(team.season)}.csv`, 'text/csv', [csvRow(header), ...rows].join('\n'))
  }

  function onPickFile(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0]
    setImportName(f ? f.name : '')
    setImportMsg(null)
  }

  function runImport() {
    const f = fileRef.current?.files?.[0]
    if (!f) {
      setImportMsg({ kind: 'err', text: 'Choose a .json file first.' })
      return
    }
    const reader = new FileReader()
    reader.onerror = () => setImportMsg({ kind: 'err', text: 'Could not read that file.' })
    reader.onload = () => {
      try {
        const parsed = JSON.parse(String(reader.result)) as Partial<AppData>
        const valid =
          parsed &&
          typeof parsed === 'object' &&
          Array.isArray(parsed.players) &&
          Array.isArray(parsed.matches) &&
          typeof parsed.version === 'number'
        if (!valid) {
          setImportMsg({ kind: 'err', text: 'Not a valid Gaffer export (missing players, matches or version).' })
          return
        }
        actions.importData(parsed as AppData)
        setImportMsg({
          kind: 'ok',
          text: `Imported ${parsed.players!.length} players and ${parsed.matches!.length} matches. Current data replaced.`,
        })
        if (fileRef.current) fileRef.current.value = ''
        setImportName('')
      } catch {
        setImportMsg({ kind: 'err', text: 'That file is not valid JSON.' })
      }
    }
    reader.readAsText(f)
  }

  function runConfirm() {
    if (confirmKind === 'reset') actions.resetData()
    else if (confirmKind === 'clear') actions.clearData()
    setConfirmKind(null)
  }

  const taggedMoments = data.videos.reduce((sum, v) => sum + v.tags.length, 0)

  const counts: { k: string; v: number }[] = [
    { k: 'Players', v: data.players.length },
    { k: 'Matches', v: data.matches.length },
    { k: 'Drills', v: data.drills.length },
    { k: 'Training sessions', v: data.sessions.length },
    { k: 'Saved lineups', v: data.lineups.length },
    { k: 'Video clips', v: data.videos.length },
    { k: 'Tagged moments', v: taggedMoments },
    { k: 'Scouting reports', v: data.scouting.length },
    { k: 'Set-piece routines', v: data.setPieces.length },
    { k: 'Development objectives', v: data.objectives.length },
  ]

  return (
    <div className="content">
      <div className="page-head">
        <div className="page-title">Data &amp; Export</div>
        <div className="page-subtitle">
          Team identity, local storage overview, and back-up / restore for your entire Gaffer dataset.
        </div>
      </div>

      <div className="grid grid-4" style={{ marginBottom: 16 }}>
        <StatCard label="Players" value={data.players.length} icon="users" accent="#3b82f6" />
        <StatCard label="Matches" value={data.matches.length} icon="calendar" accent="#22c55e" />
        <StatCard label="Drills" value={data.drills.length} icon="target" accent="#eab308" />
        <StatCard label="Tagged moments" value={taggedMoments} sub={`across ${data.videos.length} clips`} icon="video" accent="#a855f7" />
      </div>

      <div className="grid grid-2">
        {/* ── Team settings ───────────────────────────────────────────── */}
        <div className="card">
          <div className="card-head">
            <h3>Team settings</h3>
            <span className="sub">Identity used across the app</span>
          </div>
          <div className="card-pad col gap-lg">
            <div className="field">
              <label>Club name</label>
              <input
                className="input"
                value={team.name}
                onChange={(e) => actions.updateTeam({ name: e.target.value })}
              />
            </div>
            <div className="row gap-lg">
              <div className="field flex-1">
                <label>Season</label>
                <input
                  className="input"
                  value={team.season}
                  onChange={(e) => actions.updateTeam({ season: e.target.value })}
                />
              </div>
              <div className="field flex-1">
                <label>Head coach</label>
                <input
                  className="input"
                  value={team.coach}
                  onChange={(e) => actions.updateTeam({ coach: e.target.value })}
                />
              </div>
            </div>
            <div className="row gap-lg">
              <div className="field flex-1">
                <label>Primary colour</label>
                <div className="row gap-sm center">
                  <input
                    type="color"
                    value={team.primaryColor}
                    onChange={(e) => actions.updateTeam({ primaryColor: e.target.value })}
                  />
                  <span className="mono muted">{team.primaryColor}</span>
                </div>
              </div>
              <div className="field flex-1">
                <label>Secondary colour</label>
                <div className="row gap-sm center">
                  <input
                    type="color"
                    value={team.secondaryColor}
                    onChange={(e) => actions.updateTeam({ secondaryColor: e.target.value })}
                  />
                  <span className="mono muted">{team.secondaryColor}</span>
                </div>
              </div>
            </div>
            <div className="row gap-sm center">
              <span
                className="badge solid"
                style={{ background: team.primaryColor, color: '#fff' }}
              >
                {team.name || 'Your club'}
              </span>
              <span className="dot" style={{ background: team.secondaryColor }} />
              <span className="tiny muted">Live preview of your club colours</span>
            </div>
          </div>
        </div>

        {/* ── Storage overview ────────────────────────────────────────── */}
        <div className="card">
          <div className="card-head">
            <h3>Storage</h3>
            <span className="sub">Saved automatically</span>
          </div>
          <div className="card-pad">
            <div className="kv">
              {counts.map((c) => (
                <div className="row between" key={c.k} style={{ padding: '4px 0' }}>
                  <span className="k muted">{c.k}</span>
                  <span className="v bold tnum">{c.v}</span>
                </div>
              ))}
            </div>
            <div className="divider" />
            <p className="tiny muted" style={{ margin: 0 }}>
              All changes are written to your browser&apos;s <span className="mono">localStorage</span> the moment you make
              them — nothing is sent to a server. Clearing site data or switching browser will lose your edits, so use{' '}
              <span className="bold">Export JSON</span> for a portable back-up.
            </p>
          </div>
        </div>

        {/* ── Export ──────────────────────────────────────────────────── */}
        <div className="card">
          <div className="card-head">
            <h3>Export</h3>
            <span className="sub">Download a copy</span>
          </div>
          <div className="card-pad col gap-lg">
            <div className="row between center">
              <div className="col">
                <span className="bold">Full back-up</span>
                <span className="tiny muted">Complete dataset, re-importable below</span>
              </div>
              <button className="btn primary" onClick={exportJSON}>Export JSON</button>
            </div>
            <div className="divider" />
            <div className="row between center">
              <div className="col">
                <span className="bold">Players</span>
                <span className="tiny muted">{data.players.length} rows · attributes &amp; values</span>
              </div>
              <button className="btn ghost" onClick={exportPlayersCSV}>Players (CSV)</button>
            </div>
            <div className="row between center">
              <div className="col">
                <span className="bold">Matches</span>
                <span className="tiny muted">{data.matches.length} rows · results &amp; xG</span>
              </div>
              <button className="btn ghost" onClick={exportMatchesCSV}>Matches (CSV)</button>
            </div>
          </div>
        </div>

        {/* ── Import ──────────────────────────────────────────────────── */}
        <div className="card">
          <div className="card-head">
            <h3>Import</h3>
            <span className="sub">Restore from a back-up</span>
          </div>
          <div className="card-pad col gap-lg">
            <p className="tiny text-amber" style={{ margin: 0 }}>
              Importing <span className="bold">replaces your current data entirely</span>. Export a back-up first if you
              want to keep what you have now.
            </p>
            <div className="field">
              <label>Gaffer JSON export</label>
              <input
                ref={fileRef}
                type="file"
                accept="application/json"
                className="input"
                onChange={onPickFile}
              />
            </div>
            <div className="row between center">
              <span className="tiny muted">{importName ? `Selected: ${importName}` : 'No file selected'}</span>
              <button className="btn primary" onClick={runImport}>Import &amp; replace</button>
            </div>
            {importMsg && (
              <div
                className={`tiny ${importMsg.kind === 'ok' ? 'text-green' : 'text-red'}`}
                style={{ marginTop: 2 }}
              >
                {importMsg.text}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── Start fresh / Danger zone ─────────────────────────────────── */}
      <div className="card" style={{ marginTop: 16, borderColor: '#ef4444' }}>
        <div className="card-head">
          <h3 className="text-red">Start fresh / Danger zone</h3>
          <span className="sub">Irreversible — export a backup first</span>
        </div>
        <div className="card-pad row between center wrap gap-lg" style={{ borderBottom: '1px solid var(--border-soft)' }}>
          <div className="col flex-1">
            <span className="bold">Start fresh (empty)</span>
            <span className="tiny muted">
              Clears the demo team completely so you can enter your own club, players, matches and analysis from a blank slate.
            </span>
          </div>
          <button className="btn danger" onClick={() => setConfirmKind('clear')}>Start fresh</button>
        </div>
        <div className="card-pad row between center wrap gap-lg">
          <div className="col flex-1">
            <span className="bold">Reset to sample data</span>
            <span className="tiny muted">
              Wipes every change you have made and reloads the bundled demo squad, fixtures, drills and videos.
            </span>
          </div>
          <button className="btn danger" onClick={() => setConfirmKind('reset')}>Reset to sample data</button>
        </div>
      </div>

      {confirmKind && (
        <Modal
          title={confirmKind === 'clear' ? 'Start fresh?' : 'Reset to sample data?'}
          onClose={() => setConfirmKind(null)}
          footer={
            <>
              <button className="btn ghost" onClick={() => setConfirmKind(null)}>Cancel</button>
              <button className="btn danger" onClick={runConfirm}>
                {confirmKind === 'clear' ? 'Yes, clear everything' : 'Yes, reset to demo'}
              </button>
            </>
          }
        >
          <p className="muted">
            {confirmKind === 'clear'
              ? 'This permanently removes the current team and all players, matches, videos, drills, scouting, set pieces and objectives, leaving an empty workspace for your own data.'
              : 'This permanently wipes every edit you have made and restores the bundled demo dataset (Riverside Athletic).'}
          </p>
          <p className="tiny faint" style={{ marginTop: 10 }}>
            This cannot be undone. If you want to keep your current data, cancel and use “Export JSON” first.
          </p>
        </Modal>
      )}
    </div>
  )
}
