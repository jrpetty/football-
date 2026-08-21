// Read a team sheet from a picture.
//
// An hour before kickoff the confirmed elevens go up on television, and the
// reader has information the pipeline cannot have. This is the shortest route
// from their screen into the model: photograph it, and the forecast re-runs
// against the side that is actually playing.
//
// The design is deliberately unhurried about trust. Every name Claude read is
// shown against the player it was matched to, anything it could not resolve is
// listed rather than dropped, and nothing touches the forecast until the
// reader presses apply.

import { useRef, useState } from 'react'
import { readLineupsFromImage, type ReadLineupsResult } from '../ai/lineupVision.ts'
import { describeAiError, hasApiKey } from '../ai/client.ts'
import { matchLineup, type LineupMatchResult, type MatchCandidate, type MatchQuality } from '../core/lineupMatch.ts'
import type { PlayerRates } from '../core/availability.ts'
import { clubName } from '../config/teams.ts'

const QUALITY_LABEL: Record<MatchQuality, string> = {
  exact: 'exact', strong: 'good', weak: 'uncertain', ambiguous: 'ambiguous', none: 'no match',
}

function qualityStyle(q: MatchQuality): string {
  return q === 'exact' || q === 'strong' ? 'badge badge-good' : 'badge badge-live'
}

function candidates(squad: readonly PlayerRates[]): MatchCandidate[] {
  return squad.map((p) => ({ id: p.id, name: p.name, fullName: p.fullName, position: p.position }))
}

export interface ConfirmedSelection {
  home: Set<number>
  away: Set<number>
}

function SideResult({
  label,
  result,
}: {
  label: string
  result: LineupMatchResult
}) {
  return (
    <div>
      <div className="eyebrow" style={{ marginBottom: 8 }}>
        {label} — {result.matched.length} of 11
      </div>
      <div className="stack gap-6">
        {result.matched.map((m) => (
          <div key={m.player.id} className="row gap-8" style={{ fontSize: 13, alignItems: 'center' }}>
            <span style={{ fontWeight: 600, minWidth: 0 }}>{m.player.name}</span>
            {m.query.toLowerCase() !== m.player.name.toLowerCase() && (
              <span className="faint" style={{ fontSize: 11.5 }}>read as "{m.query}"</span>
            )}
            {m.quality !== 'exact' && (
              <span className={qualityStyle(m.quality)} style={{ fontSize: 10 }}>
                {QUALITY_LABEL[m.quality]}
              </span>
            )}
          </div>
        ))}
        {result.unresolved.map((u) => (
          <div key={u.query} className="row gap-8" style={{ fontSize: 13, alignItems: 'center' }}>
            <span className="faint" style={{ textDecoration: 'line-through' }}>{u.query}</span>
            <span className="badge badge-danger" style={{ fontSize: 10 }}>
              {u.quality === 'ambiguous'
                ? `could be ${u.alternatives.map((a) => a.name).join(' or ')}`
                : 'not in squad'}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

export function LineupUpload({
  homeCode,
  awayCode,
  homeSquad,
  awaySquad,
  onApply,
  onClear,
  applied,
}: {
  homeCode: string
  awayCode: string
  homeSquad: readonly PlayerRates[]
  awaySquad: readonly PlayerRates[]
  onApply: (selection: ConfirmedSelection) => void
  onClear: () => void
  applied: boolean
}) {
  const [preview, setPreview] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [read, setRead] = useState<ReadLineupsResult | null>(null)
  const [home, setHome] = useState<LineupMatchResult | null>(null)
  const [away, setAway] = useState<LineupMatchResult | null>(null)
  const [swapped, setSwapped] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const handle = async (file: File | Blob): Promise<void> => {
    setError(null)
    setBusy(true)
    setRead(null)
    setHome(null)
    setAway(null)
    setSwapped(false)
    setPreview(URL.createObjectURL(file))
    try {
      const result = await readLineupsFromImage({
        file,
        homeName: clubName(homeCode),
        awayName: clubName(awayCode),
        homeSquad: candidates(homeSquad),
        awaySquad: candidates(awaySquad),
      })
      setRead(result)
      setHome(matchLineup(result.homeStarters, candidates(homeSquad)))
      setAway(matchLineup(result.awayStarters, candidates(awaySquad)))
    } catch (err) {
      setError(describeAiError(err))
    } finally {
      setBusy(false)
    }
  }

  // Re-match with the two sides swapped, for when Claude could not tell which
  // eleven belonged to which club.
  const swap = (): void => {
    if (!read) return
    const next = !swapped
    setSwapped(next)
    const forHome = next ? read.awayStarters : read.homeStarters
    const forAway = next ? read.homeStarters : read.awayStarters
    setHome(matchLineup(forHome, candidates(homeSquad)))
    setAway(matchLineup(forAway, candidates(awaySquad)))
  }

  const reset = (): void => {
    setPreview(null)
    setRead(null)
    setHome(null)
    setAway(null)
    setError(null)
    onClear()
  }

  if (!hasApiKey()) {
    return (
      <div className="note">
        <span className="note-icon">i</span>
        <span>
          Add an Anthropic API key below and you can photograph the confirmed team sheet — from the
          television, a screenshot, anywhere — and have the forecast re-run against the side that is
          actually playing. Confirmed elevens go up about an hour before kickoff, which is the one
          thing this model never has when it publishes.
        </span>
      </div>
    )
  }

  const total = (home?.matched.length ?? 0) + (away?.matched.length ?? 0)

  return (
    <div>
      <div
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault()
          const file = e.dataTransfer.files[0]
          if (file) void handle(file)
        }}
        onPaste={(e) => {
          const item = [...e.clipboardData.items].find((i) => i.type.startsWith('image/'))
          const file = item?.getAsFile()
          if (file) void handle(file)
        }}
        tabIndex={0}
        style={{
          border: '1px dashed var(--border)',
          borderRadius: 'var(--radius-sm)',
          padding: preview ? 12 : '22px 16px',
          textAlign: 'center',
          background: 'var(--panel-2)',
        }}
      >
        {preview && (
          <img
            src={preview}
            alt="The team sheet you uploaded"
            style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 8, marginBottom: 10 }}
          />
        )}
        <div className="row gap-8" style={{ justifyContent: 'center', flexWrap: 'wrap' }}>
          <button
            className="gw-btn active"
            style={{ padding: '0 16px' }}
            disabled={busy}
            onClick={() => inputRef.current?.click()}
          >
            {busy ? 'Reading…' : preview ? 'Use another picture' : 'Upload a team sheet'}
          </button>
          {preview && !busy && (
            <button className="gw-btn" style={{ padding: '0 12px' }} onClick={reset}>
              Clear
            </button>
          )}
        </div>
        {!preview && (
          <p className="faint" style={{ fontSize: 12, marginTop: 9, lineHeight: 1.5 }}>
            Drop an image here, paste one, or choose a file. A photograph of the television works.
          </p>
        )}
        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) void handle(file)
            e.target.value = ''
          }}
        />
      </div>

      {error && (
        <div className="note" style={{ marginTop: 12, borderColor: 'rgba(230,103,103,0.3)', background: 'rgba(230,103,103,0.07)' }}>
          <span className="note-icon" style={{ color: 'var(--away)' }}>!</span>
          <span>{error}</span>
        </div>
      )}

      {read && home && away && (
        <div style={{ marginTop: 14 }}>
          {(read.confidence !== 'high' || read.notes) && (
            <div className="note" style={{ marginBottom: 12 }}>
              <span className="note-icon">i</span>
              <span>
                {read.confidence !== 'high' && (
                  <strong>Read with {read.confidence} confidence. </strong>
                )}
                {read.notes || 'Check the names below before applying.'}
              </span>
            </div>
          )}

          <div className="grid-2" style={{ gap: 18 }}>
            <SideResult label={clubName(homeCode)} result={home} />
            <SideResult label={clubName(awayCode)} result={away} />
          </div>

          <div className="row gap-8 wrap" style={{ marginTop: 14 }}>
            <button
              className="gw-btn active"
              style={{ padding: '0 16px' }}
              disabled={total === 0}
              onClick={() =>
                onApply({
                  home: new Set(home.ids),
                  away: new Set(away.ids),
                })
              }
            >
              {applied ? 'Re-apply these elevens' : 'Apply to the forecast'}
            </button>
            <button className="gw-btn" style={{ padding: '0 12px' }} onClick={swap}>
              {swapped ? 'Sides swapped — swap back' : 'Wrong way round? Swap sides'}
            </button>
            {read.sidesUncertain && (
              <span className="badge badge-live" style={{ fontSize: 10 }}>
                which side is which was unclear
              </span>
            )}
          </div>
          <p className="faint" style={{ fontSize: 11.5, marginTop: 10, lineHeight: 1.5 }}>
            Claude is asked to read only what is printed — never to infer a likely eleven or fill a
            side out from what it knows of the clubs. Names are matched to the squad by code
            afterwards, and anything it could not resolve is listed above rather than quietly
            dropped. Applying replaces the model's guess at who plays with the side you can see.
          </p>
        </div>
      )}
    </div>
  )
}
