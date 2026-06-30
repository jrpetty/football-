import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import type { VideoClip, VideoSource, Match, TagCategory } from '../types'
import { useData, useActions } from '../store/store'
import { guessSource, parseYouTubeId } from '../utils/video'
import { genId } from '../utils/format'
import { StatCard } from '../components/ui/StatCard'
import { Modal } from '../components/ui/Modal'
import { EmptyState } from '../components/ui/Primitives'
import { TAG_CATEGORIES, tagMeta } from '../data/tags'

// Group the tags on a clip into ordered category → count pairs so each card can
// show which kinds of moments have been logged at a glance.
function categoryCounts(clip: VideoClip): { category: TagCategory; count: number }[] {
  const counts = new Map<TagCategory, number>()
  for (const t of clip.tags) counts.set(t.category, (counts.get(t.category) ?? 0) + 1)
  // Preserve the canonical TAG_CATEGORIES order for stable, predictable chips.
  return TAG_CATEGORIES.filter((m) => counts.has(m.category)).map((m) => ({
    category: m.category,
    count: counts.get(m.category) ?? 0,
  }))
}

function matchLabel(m: Match): string {
  return `vs ${m.opponent} · ${m.date}`
}

export function VideoAnalysis() {
  const data = useData()
  const actions = useActions()

  const [modalOpen, setModalOpen] = useState(false)

  const videos = data.videos

  const totals = useMemo(() => {
    let tagged = 0
    let linked = 0
    for (const v of videos) {
      tagged += v.tags.length
      if (v.matchId) linked += 1
    }
    return { tagged, linked }
  }, [videos])

  function handleDelete(e: React.MouseEvent, id: string) {
    e.preventDefault()
    e.stopPropagation()
    actions.removeVideo(id)
  }

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <div className="page-title">Video Analysis</div>
          <div className="page-subtitle">Link match footage and tag key moments for review.</div>
        </div>
        <button className="btn primary" onClick={() => setModalOpen(true)}>
          + Add video
        </button>
      </div>

      {/* Library summary */}
      <div className="grid grid-3">
        <StatCard label="Clips" value={videos.length} icon="🎬" accent="#38bdf8" />
        <StatCard
          label="Tagged moments"
          value={totals.tagged}
          icon="🏷️"
          accent="#a855f7"
          sub={
            <span className="muted">
              {videos.length > 0
                ? `${(totals.tagged / videos.length).toFixed(1)} per clip`
                : 'No clips yet'}
            </span>
          }
        />
        <StatCard
          label="Linked matches"
          value={totals.linked}
          icon="🔗"
          accent="#22c55e"
          sub={
            <span className="muted">
              {videos.length > 0
                ? `${videos.length - totals.linked} unlinked`
                : 'Attach footage to fixtures'}
            </span>
          }
        />
      </div>

      {/* Source guidance */}
      <div className="panel muted" style={{ marginTop: 16 }}>
        Paste a <span className="bold">YouTube</span> link or a direct video URL (
        <span className="mono">.mp4</span> / <span className="mono">.webm</span>), or upload a local
        file. Uploaded files are kept for the current session only — re-attach them after a reload.
      </div>

      {/* Clip grid */}
      {videos.length === 0 ? (
        <div className="card card-pad" style={{ marginTop: 16 }}>
          <EmptyState
            icon="🎥"
            title="No video clips yet"
            hint="Add a YouTube link, a direct video URL, or upload a file to start tagging key moments."
          />
        </div>
      ) : (
        <div className="grid grid-auto" style={{ marginTop: 16 }}>
          {videos.map((v) => {
            const cats = categoryCounts(v)
            const ytId = v.source === 'youtube' ? parseYouTubeId(v.url) : null
            return (
              <Link
                key={v.id}
                to={`/video/${v.id}`}
                className="card"
                style={{ textDecoration: 'none', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}
              >
                {/* 16:9 thumbnail */}
                <div
                  style={{
                    position: 'relative',
                    width: '100%',
                    aspectRatio: '16 / 9',
                    background:
                      'linear-gradient(135deg, rgba(56,189,248,0.18), rgba(168,85,247,0.18))',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    overflow: 'hidden',
                  }}
                >
                  {ytId ? (
                    <img
                      src={`https://img.youtube.com/vi/${ytId}/hqdefault.jpg`}
                      alt={v.title}
                      style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
                    />
                  ) : (
                    <div className="col center" style={{ gap: 4 }}>
                      <span style={{ fontSize: 34, lineHeight: 1, opacity: 0.85 }}>▶</span>
                      <span className="faint tiny" style={{ textTransform: 'uppercase', letterSpacing: 0.6 }}>
                        {v.source === 'file' ? 'Local file' : 'Direct URL'}
                      </span>
                    </div>
                  )}
                  {/* Tag-count badge */}
                  <span
                    className="badge solid"
                    style={{
                      position: 'absolute',
                      top: 8,
                      right: 8,
                      background: 'rgba(15,23,42,0.78)',
                      backdropFilter: 'blur(2px)',
                    }}
                    title={`${v.tags.length} tagged moment${v.tags.length === 1 ? '' : 's'}`}
                  >
                    🏷️ {v.tags.length}
                  </span>
                  {/* Delete */}
                  <button
                    className="btn danger sm icon"
                    onClick={(e) => handleDelete(e, v.id)}
                    title="Remove clip"
                    style={{ position: 'absolute', top: 8, left: 8 }}
                  >
                    ✕
                  </button>
                </div>

                {/* Meta */}
                <div className="card-pad" style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <div className="bold" style={{ lineHeight: 1.25 }}>
                    {v.title}
                  </div>
                  <div className="muted tiny">
                    {v.opponent ? `vs ${v.opponent}` : 'Unlinked clip'}
                    {v.date ? ` · ${v.date}` : ''}
                  </div>
                  {cats.length > 0 ? (
                    <div className="tag-list" style={{ marginTop: 2 }}>
                      {cats.slice(0, 5).map((c) => {
                        const meta = tagMeta(c.category)
                        return (
                          <span
                            key={c.category}
                            className="chip"
                            style={{
                              borderColor: `${meta.color}55`,
                              color: meta.color,
                            }}
                            title={`${c.category}: ${c.count}`}
                          >
                            {meta.icon} {c.count}
                          </span>
                        )
                      })}
                      {cats.length > 5 && <span className="chip faint">+{cats.length - 5}</span>}
                    </div>
                  ) : (
                    <span className="faint tiny" style={{ marginTop: 2 }}>
                      No moments tagged yet
                    </span>
                  )}
                </div>
              </Link>
            )
          })}
        </div>
      )}

      {modalOpen && (
        <AddVideoForm
          matches={data.matches}
          onCancel={() => setModalOpen(false)}
          onSave={(clip) => {
            actions.addVideo(clip)
            setModalOpen(false)
          }}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Add-video form
// ---------------------------------------------------------------------------
type Mode = 'link' | 'file'

function AddVideoForm({
  matches,
  onCancel,
  onSave,
}: {
  matches: Match[]
  onCancel: () => void
  onSave: (clip: VideoClip) => void
}) {
  const [mode, setMode] = useState<Mode>('link')
  const [title, setTitle] = useState('')
  const [link, setLink] = useState('')
  // For uploads we keep the generated object URL separate from the link input.
  const [fileUrl, setFileUrl] = useState('')
  const [fileName, setFileName] = useState('')
  const [matchId, setMatchId] = useState('')
  const [opponent, setOpponent] = useState('')
  const [date, setDate] = useState('')
  const [notes, setNotes] = useState('')

  const url = mode === 'link' ? link.trim() : fileUrl
  const source: VideoSource = mode === 'file' ? 'file' : guessSource(link)
  const titleValid = title.trim().length > 0
  const urlValid = url.length > 0
  const valid = titleValid && urlValid

  // Show a small confirmation of how a pasted link was interpreted.
  const linkHint =
    mode === 'link' && link.trim().length > 0
      ? source === 'youtube'
        ? `Detected YouTube clip (${parseYouTubeId(link)})`
        : 'Treated as a direct video URL'
      : ''

  function onFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    const objectUrl = URL.createObjectURL(file)
    setFileUrl(objectUrl)
    setFileName(file.name)
    if (!title.trim()) setTitle(file.name.replace(/\.[^.]+$/, ''))
  }

  function onPickMatch(id: string) {
    setMatchId(id)
    const m = matches.find((x) => x.id === id)
    if (m) {
      setOpponent(m.opponent)
      setDate(m.date)
      if (!title.trim()) setTitle(`vs ${m.opponent}`)
    }
  }

  function save() {
    if (!valid) return
    const clip: VideoClip = {
      id: genId('v'),
      title: title.trim(),
      source,
      url,
      matchId: matchId || undefined,
      opponent: opponent.trim() || undefined,
      date: date || undefined,
      tags: [],
      notes: notes.trim(),
    }
    onSave(clip)
  }

  return (
    <Modal
      title="Add video"
      onClose={onCancel}
      wide
      footer={
        <div className="row gap-sm" style={{ justifyContent: 'flex-end' }}>
          <button className="btn ghost" onClick={onCancel}>
            Cancel
          </button>
          <button className="btn primary" disabled={!valid} onClick={save}>
            Add video
          </button>
        </div>
      }
    >
      {/* Source mode toggle */}
      <div className="field">
        <label>Source</label>
        <div className="btn-group">
          <button className={mode === 'link' ? 'active' : ''} onClick={() => setMode('link')}>
            Link (URL/YouTube)
          </button>
          <button className={mode === 'file' ? 'active' : ''} onClick={() => setMode('file')}>
            Upload file
          </button>
        </div>
      </div>

      {mode === 'link' ? (
        <div className="field">
          <label>Video link</label>
          <input
            className="input"
            value={link}
            onChange={(e) => setLink(e.target.value)}
            placeholder="https://youtu.be/… or https://…/clip.mp4"
            autoFocus
          />
          {linkHint && <span className="tiny faint">{linkHint}</span>}
        </div>
      ) : (
        <div className="field">
          <label>Video file</label>
          <input className="input" type="file" accept="video/*" onChange={onFile} />
          {fileName ? (
            <span className="tiny text-green">Loaded {fileName}</span>
          ) : (
            <span className="tiny faint">Kept for this session only — re-attach after reload.</span>
          )}
        </div>
      )}

      <div className="field">
        <label>Title</label>
        <input
          className="input"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="e.g. 2nd-half pressing review"
        />
        {!titleValid && <span className="tiny text-red">Title is required</span>}
      </div>

      <div className="divider" />

      {/* Optional metadata */}
      <div className="field">
        <label>Link to match (optional)</label>
        <select className="select" value={matchId} onChange={(e) => onPickMatch(e.target.value)}>
          <option value="">— No linked match —</option>
          {matches.map((m) => (
            <option key={m.id} value={m.id}>
              {matchLabel(m)}
            </option>
          ))}
        </select>
      </div>

      <div className="grid grid-2">
        <div className="field">
          <label>Opponent (optional)</label>
          <input
            className="input"
            value={opponent}
            onChange={(e) => setOpponent(e.target.value)}
            placeholder="e.g. Riverside FC"
          />
        </div>
        <div className="field">
          <label>Date (optional)</label>
          <input className="input" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </div>
      </div>

      <div className="field">
        <label>Notes</label>
        <textarea
          className="textarea"
          rows={3}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="What to look for in this clip…"
        />
      </div>
    </Modal>
  )
}
