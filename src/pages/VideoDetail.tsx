import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, Link, useNavigate } from 'react-router-dom'
import { useData, useActions } from '../store/store'
import type { VideoClip, VideoTag, TagCategory, DrawShape, DrawTool } from '../types'
import { TAG_CATEGORIES, tagMeta } from '../data/tags'
import { youTubeEmbedUrl, fmtClock, parseClock } from '../utils/video'
import { genId, clamp } from '../utils/format'
import { Modal } from '../components/ui/Modal'
import { EmptyState } from '../components/ui/Primitives'
import { Telestration } from '../components/video/Telestration'

function emptyTag(time: number): VideoTag {
  return { id: '', time, category: 'Chance', title: '', note: '', team: 'us' }
}

const DRAW_TOOLS: { tool: DrawTool; icon: string; label: string }[] = [
  { tool: 'arrow', icon: '↗', label: 'Arrow' },
  { tool: 'line', icon: '╱', label: 'Line' },
  { tool: 'rect', icon: '▭', label: 'Box' },
  { tool: 'ellipse', icon: '◯', label: 'Circle' },
  { tool: 'pen', icon: '✎', label: 'Pen' },
]
const DRAW_COLORS = ['#fbbf24', '#ef4444', '#22c55e', '#38bdf8', '#ffffff', '#000000']

interface Annotate { tagId: string; shapes: DrawShape[] }
interface Reel { idx: number }

export function VideoDetail() {
  const { videoId } = useParams()
  const data = useData()
  const actions = useActions()
  const navigate = useNavigate()

  const video = data.videos.find((v) => v.id === videoId)

  const videoRef = useRef<HTMLVideoElement>(null)
  const trackRef = useRef<HTMLDivElement>(null)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(video?.durationSec ?? 0)
  const [playing, setPlaying] = useState(false)
  const [rate, setRate] = useState(1)
  const [ytStart, setYtStart] = useState<{ t: number; key: number } | null>(null)
  const [filter, setFilter] = useState<TagCategory | 'all'>('all')
  const [editing, setEditing] = useState<VideoTag | null>(null)
  const [fileError, setFileError] = useState(false)
  // Telestration
  const [annotate, setAnnotate] = useState<Annotate | null>(null)
  const [tool, setTool] = useState<DrawTool>('arrow')
  const [color, setColor] = useState('#fbbf24')
  const [activeTagId, setActiveTagId] = useState<string | null>(null)
  // Moment reel
  const [reel, setReel] = useState<Reel | null>(null)

  const isYouTube = video?.source === 'youtube'

  // Keyboard shortcuts (ignore when typing in a field / modal open).
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || editing) return
      if (isYouTube) return
      const el = videoRef.current
      if (!el) return
      if (e.code === 'Space') { e.preventDefault(); el.paused ? el.play() : el.pause() }
      else if (e.code === 'ArrowLeft') { el.currentTime = Math.max(0, el.currentTime - 5) }
      else if (e.code === 'ArrowRight') { el.currentTime = Math.min(el.duration || 1e9, el.currentTime + 5) }
      else if (e.key === 'm' || e.key === 'M') { setEditing(emptyTag(Math.round(el.currentTime))) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [isYouTube, editing])

  const tags = useMemo(
    () => (video ? [...video.tags].sort((a, b) => a.time - b.time) : []),
    [video],
  )
  const shownTags = filter === 'all' ? tags : tags.filter((t) => t.category === filter)

  const countByCat = useMemo(() => {
    const m = new Map<TagCategory, number>()
    for (const t of tags) m.set(t.category, (m.get(t.category) ?? 0) + 1)
    return m
  }, [tags])

  if (!video) {
    return (
      <div className="content">
        <EmptyState icon="🎬" title="Clip not found" hint={<Link className="link" to="/video">Back to Video Analysis</Link>} />
      </div>
    )
  }

  // ---- Playback helpers --------------------------------------------------
  function seekTo(t: number) {
    if (isYouTube) {
      setYtStart({ t: Math.max(0, Math.floor(t)), key: Date.now() })
    } else if (videoRef.current) {
      videoRef.current.currentTime = t
      videoRef.current.play().catch(() => {})
    }
  }
  function seekToTag(t: VideoTag) {
    setActiveTagId(t.id)
    seekTo(t.time)
  }
  function togglePlay() {
    const el = videoRef.current
    if (!el) return
    setActiveTagId(null)
    el.paused ? el.play() : el.pause()
  }
  function nudge(seconds: number) {
    const el = videoRef.current
    if (!el) return
    setActiveTagId(null)
    el.currentTime = clamp(el.currentTime + seconds, 0, el.duration || 1e9)
  }
  function onTrackPointer(e: React.PointerEvent) {
    const rect = trackRef.current?.getBoundingClientRect()
    const el = videoRef.current
    if (!rect || !el || !duration) return
    setActiveTagId(null)
    setReel(null)
    const frac = clamp((e.clientX - rect.left) / rect.width, 0, 1)
    el.currentTime = frac * duration
  }

  // ---- Telestration ------------------------------------------------------
  function startAnnotate(t: VideoTag) {
    videoRef.current?.pause()
    seekTo(t.time)
    setActiveTagId(t.id)
    setAnnotate({ tagId: t.id, shapes: t.drawing ? [...t.drawing] : [] })
  }
  function saveAnnotation() {
    if (!annotate || !video) return
    const tags = video.tags.map((t) => (t.id === annotate.tagId ? { ...t, drawing: annotate.shapes } : t))
    persist({ ...video, tags })
    setAnnotate(null)
  }

  // ---- Moment reel -------------------------------------------------------
  function playReel() {
    if (shownTags.length === 0) return
    setReel({ idx: 0 })
    seekToTag(shownTags[0])
  }
  function jumpMoment(dir: 1 | -1) {
    if (shownTags.length === 0) return
    const here = current
    let next: VideoTag | undefined
    if (dir === 1) next = shownTags.find((t) => t.time > here + 0.4)
    else next = [...shownTags].reverse().find((t) => t.time < here - 0.4)
    if (next) { setReel(null); seekToTag(next) }
  }
  // YouTube can't report currentTime to us, so step by index instead.
  function ytStep(dir: 1 | -1) {
    if (shownTags.length === 0) return
    const idx = clamp((reel ? reel.idx : -1) + dir, 0, shownTags.length - 1)
    setReel({ idx })
    seekToTag(shownTags[idx])
  }
  // Advance the reel when playback passes the current moment's window.
  function handleTimeUpdate(t: number) {
    setCurrent(t)
    if (!reel) return
    const tag = shownTags[reel.idx]
    if (!tag) { setReel(null); return }
    const end = tag.endTime ?? tag.time + 6
    if (t >= end) {
      const nextIdx = reel.idx + 1
      if (nextIdx < shownTags.length) { setReel({ idx: nextIdx }); seekToTag(shownTags[nextIdx]) }
      else { setReel(null); videoRef.current?.pause() }
    }
  }

  // ---- Tag CRUD ----------------------------------------------------------
  function persist(next: VideoClip) {
    actions.updateVideo(next)
  }
  function saveTag(tag: VideoTag) {
    const exists = video!.tags.some((t) => t.id === tag.id)
    const withId = tag.id ? tag : { ...tag, id: genId('vt') }
    const tags = exists
      ? video!.tags.map((t) => (t.id === tag.id ? withId : t))
      : [...video!.tags, withId]
    persist({ ...video!, tags })
    setEditing(null)
  }
  function deleteTag(id: string) {
    persist({ ...video!, tags: video!.tags.filter((t) => t.id !== id) })
  }

  function replaceFile(file: File) {
    const url = URL.createObjectURL(file)
    persist({ ...video!, url, source: 'file' })
    setFileError(false)
  }

  const playerName = (id?: string) => data.players.find((p) => p.id === id)?.name

  const activeTag = annotate
    ? video.tags.find((t) => t.id === annotate.tagId)
    : activeTagId
      ? video.tags.find((t) => t.id === activeTagId)
      : undefined
  const overlayShapes: DrawShape[] = annotate ? annotate.shapes : activeTag?.drawing ?? []
  const showOverlay = annotate != null || overlayShapes.length > 0
  const annotateTag = annotate ? video.tags.find((t) => t.id === annotate.tagId) : undefined

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <Link to="/video" className="link tiny">← Video Analysis</Link>
          <div className="page-title" style={{ marginTop: 4 }}>{video.title}</div>
          <div className="page-subtitle">
            {video.opponent ? `${video.opponent} · ` : ''}{video.date ?? ''} · {tags.length} tagged moments
          </div>
        </div>
        <button className="btn danger" onClick={() => { actions.removeVideo(video.id); navigate('/video') }}>
          Delete clip
        </button>
      </div>

      <div className="row" style={{ alignItems: 'flex-start' }}>
        {/* Player column */}
        <div className="flex-1">
          <div className="card" style={{ overflow: 'hidden' }}>
            <div style={{ position: 'relative', background: '#000', aspectRatio: '16 / 9' }}>
              {isYouTube ? (
                <iframe
                  key={ytStart?.key ?? 'base'}
                  title={video.title}
                  src={youTubeEmbedUrl(video.url) + (ytStart ? `&start=${ytStart.t}&autoplay=1` : '')}
                  style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', border: 0 }}
                  allow="accelerometer; autoplay; encrypted-media; picture-in-picture"
                  allowFullScreen
                />
              ) : (
                <video
                  ref={videoRef}
                  src={video.url}
                  style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}
                  onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)}
                  onTimeUpdate={(e) => handleTimeUpdate(e.currentTarget.currentTime)}
                  onPlay={() => setPlaying(true)}
                  onPause={() => setPlaying(false)}
                  onError={() => setFileError(true)}
                  playsInline
                />
              )}

              {showOverlay && (
                <Telestration
                  shapes={overlayShapes}
                  editable={annotate != null}
                  tool={tool}
                  color={color}
                  onChange={(s) => setAnnotate((a) => (a ? { ...a, shapes: s } : a))}
                />
              )}

              {fileError && video.source === 'file' && (
                <div style={{ position: 'absolute', inset: 0, zIndex: 10, display: 'grid', placeItems: 'center', background: 'rgba(10,15,26,0.92)', textAlign: 'center', padding: 24 }}>
                  <div>
                    <div style={{ fontSize: 40 }}>📁</div>
                    <p className="muted" style={{ maxWidth: 360, margin: '10px auto' }}>
                      This clip was an uploaded file. Local files aren’t stored permanently — re-attach it to keep analysing.
                    </p>
                    <label className="btn primary" style={{ cursor: 'pointer' }}>
                      Re-attach video file
                      <input type="file" accept="video/*" hidden onChange={(e) => e.target.files?.[0] && replaceFile(e.target.files[0])} />
                    </label>
                  </div>
                </div>
              )}
            </div>

            {/* Telestration toolbar */}
            {annotate && (
              <div className="card-pad" style={{ borderTop: '1px solid var(--border-soft)', background: 'rgba(56,189,248,0.06)' }}>
                <div className="row between center wrap" style={{ gap: 10 }}>
                  <div className="row gap-sm center wrap">
                    <span className="tiny bold" style={{ color: 'var(--accent)' }}>✏️ Drawing on “{annotateTag?.title || 'moment'}”</span>
                    <div className="btn-group">
                      {DRAW_TOOLS.map((d) => (
                        <button key={d.tool} className={tool === d.tool ? 'active' : ''} title={d.label} onClick={() => setTool(d.tool)}>{d.icon}</button>
                      ))}
                    </div>
                    <div className="row gap-sm center">
                      {DRAW_COLORS.map((c) => (
                        <button
                          key={c}
                          onClick={() => setColor(c)}
                          title={c}
                          style={{ width: 22, height: 22, borderRadius: '50%', background: c, cursor: 'pointer', border: color === c ? '2px solid #fff' : '2px solid rgba(255,255,255,0.25)' }}
                        />
                      ))}
                    </div>
                  </div>
                  <div className="row gap-sm center">
                    <button className="btn sm ghost" disabled={annotate.shapes.length === 0} onClick={() => setAnnotate((a) => (a ? { ...a, shapes: a.shapes.slice(0, -1) } : a))}>↺ Undo</button>
                    <button className="btn sm ghost" disabled={annotate.shapes.length === 0} onClick={() => setAnnotate((a) => (a ? { ...a, shapes: [] } : a))}>Clear</button>
                    <button className="btn sm" onClick={() => setAnnotate(null)}>Cancel</button>
                    <button className="btn sm primary" onClick={saveAnnotation}>💾 Save drawing</button>
                  </div>
                </div>
              </div>
            )}

            {/* Custom controls (native video only) */}
            {!isYouTube && (
              <div className="card-pad" style={{ borderTop: '1px solid var(--border-soft)' }}>
                {/* Scrubber with tag markers */}
                <div
                  ref={trackRef}
                  onPointerDown={onTrackPointer}
                  style={{ position: 'relative', height: 12, background: 'var(--bg-2)', borderRadius: 999, cursor: 'pointer', margin: '4px 0 12px' }}
                >
                  <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${duration ? (current / duration) * 100 : 0}%`, background: 'linear-gradient(90deg, var(--accent-strong), var(--accent))', borderRadius: 999 }} />
                  {duration > 0 && tags.map((t) => (
                    <div
                      key={t.id}
                      title={`${fmtClock(t.time)} — ${t.title}`}
                      onPointerDown={(e) => { e.stopPropagation(); seekToTag(t) }}
                      style={{ position: 'absolute', top: -3, left: `${(t.time / duration) * 100}%`, width: 4, height: 18, marginLeft: -2, background: tagMeta(t.category).color, borderRadius: 2, boxShadow: '0 0 0 1px rgba(0,0,0,0.4)' }}
                    />
                  ))}
                </div>

                <div className="row between center wrap" style={{ gap: 10 }}>
                  <div className="row gap-sm center">
                    <button className="btn icon" onClick={() => nudge(-5)} title="Back 5s">⏪</button>
                    <button className="btn primary icon" onClick={togglePlay} style={{ width: 44 }}>{playing ? '⏸' : '▶'}</button>
                    <button className="btn icon" onClick={() => nudge(5)} title="Forward 5s">⏩</button>
                    <span className="mono tiny muted" style={{ minWidth: 96 }}>{fmtClock(current)} / {fmtClock(duration)}</span>
                  </div>
                  <div className="row gap-sm center">
                    <div className="btn-group" title="Step through tagged moments">
                      <button onClick={() => jumpMoment(-1)} disabled={shownTags.length === 0} title="Previous moment">⏮</button>
                      <button onClick={reel ? () => setReel(null) : playReel} disabled={shownTags.length === 0} className={reel ? 'active' : ''}>
                        {reel ? `■ ${reel.idx + 1}/${shownTags.length}` : `▶ Reel (${shownTags.length})`}
                      </button>
                      <button onClick={() => jumpMoment(1)} disabled={shownTags.length === 0} title="Next moment">⏭</button>
                    </div>
                  </div>
                  <div className="row gap-sm center">
                    <select
                      className="select" style={{ width: 92 }}
                      value={rate}
                      onChange={(e) => { const r = parseFloat(e.target.value); setRate(r); if (videoRef.current) videoRef.current.playbackRate = r }}
                    >
                      {[0.25, 0.5, 0.75, 1, 1.5, 2].map((r) => <option key={r} value={r}>{r}×</option>)}
                    </select>
                    <button className="btn primary" onClick={() => setEditing(emptyTag(Math.round(current)))}>
                      ⊕ Mark moment <span className="tiny faint" style={{ marginLeft: 4 }}>(M)</span>
                    </button>
                  </div>
                </div>
              </div>
            )}

            {isYouTube && (
              <div className="card-pad" style={{ borderTop: '1px solid var(--border-soft)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                <span className="tiny muted">YouTube clip · tag moments by timecode, then click a tag to jump to it.</span>
                <div className="row gap-sm center">
                  <div className="btn-group" title="Step through tagged moments">
                    <button onClick={() => ytStep(-1)} disabled={shownTags.length === 0} title="Previous moment">⏮</button>
                    <button onClick={() => ytStep(1)} disabled={shownTags.length === 0} title="Next moment">⏭</button>
                  </div>
                  <button className="btn primary" onClick={() => setEditing(emptyTag(0))}>⊕ Add moment</button>
                </div>
              </div>
            )}
          </div>

          {/* Notes */}
          <div className="card" style={{ marginTop: 16 }}>
            <div className="card-head"><h3>Analysis notes</h3></div>
            <div className="card-pad">
              <textarea
                className="textarea"
                value={video.notes}
                placeholder="Overall observations, themes to work on, talking points for the team…"
                onChange={(e) => persist({ ...video, notes: e.target.value })}
              />
            </div>
          </div>
        </div>

        {/* Tag column */}
        <div style={{ width: 380, flexShrink: 0 }}>
          <div className="card">
            <div className="card-head">
              <h3>Tagged moments</h3>
              <span className="badge">{shownTags.length}</span>
            </div>
            <div className="card-pad" style={{ paddingBottom: 8 }}>
              <div className="tag-list" style={{ marginBottom: 4 }}>
                <span className={`chip${filter === 'all' ? ' active' : ''}`} onClick={() => setFilter('all')} style={{ cursor: 'pointer' }}>All ({tags.length})</span>
                {TAG_CATEGORIES.filter((c) => countByCat.get(c.category)).map((c) => (
                  <span
                    key={c.category}
                    className={`chip${filter === c.category ? ' active' : ''}`}
                    onClick={() => setFilter(c.category)}
                    style={{ cursor: 'pointer', borderColor: filter === c.category ? c.color : undefined }}
                  >
                    {c.icon} {c.category} ({countByCat.get(c.category)})
                  </span>
                ))}
              </div>
            </div>
            <div style={{ maxHeight: 620, overflowY: 'auto', padding: '0 12px 12px' }}>
              {shownTags.length === 0 ? (
                <EmptyState icon="🏷️" title="No moments tagged yet" hint="Use “Mark moment” to capture a clip with the current timecode." />
              ) : (
                <div className="col" style={{ gap: 8 }}>
                  {shownTags.map((t) => {
                    const meta = tagMeta(t.category)
                    return (
                      <div key={t.id} className="panel" style={{ padding: 11, borderLeft: `3px solid ${meta.color}` }}>
                        <div className="row between center" style={{ gap: 8 }}>
                          <button className="badge mono" onClick={() => seekToTag(t)} style={{ cursor: 'pointer', borderColor: meta.color, color: meta.color }}>
                            ▶ {fmtClock(t.time)}{t.endTime ? `–${fmtClock(t.endTime)}` : ''}
                          </button>
                          <span className="tiny" style={{ color: meta.color, fontWeight: 700 }}>{meta.icon} {t.category}</span>
                          {t.drawing && t.drawing.length > 0 && <span title="Has telestration" className="tiny">✏️</span>}
                          <span style={{ flex: 1 }} />
                          <span className="link tiny" onClick={() => startAnnotate(t)} title="Draw on this moment">draw</span>
                          <span className="link tiny" onClick={() => setEditing(t)}>edit</span>
                          <span className="link tiny" onClick={() => deleteTag(t.id)} style={{ color: 'var(--red)' }}>✕</span>
                        </div>
                        <div style={{ fontWeight: 700, marginTop: 6 }}>{t.title || '(untitled)'}</div>
                        {t.note && <div className="tiny muted" style={{ marginTop: 3 }}>{t.note}</div>}
                        <div className="row gap-sm center" style={{ marginTop: 7 }}>
                          <span className="badge tiny" style={{ borderColor: t.team === 'us' ? 'rgba(56,189,248,0.4)' : 'rgba(239,68,68,0.4)', color: t.team === 'us' ? 'var(--accent)' : 'var(--red)' }}>
                            {t.team === 'us' ? data.team.name : 'Opposition'}
                          </span>
                          {t.playerId && playerName(t.playerId) && (
                            <Link to={`/squad/${t.playerId}`} className="chip tiny">👤 {playerName(t.playerId)}</Link>
                          )}
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {editing && (
        <TagEditor
          tag={editing}
          isYouTube={isYouTube}
          players={data.players}
          onClose={() => setEditing(null)}
          onSave={saveTag}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
function TagEditor({
  tag, isYouTube, players, onClose, onSave,
}: {
  tag: VideoTag
  isYouTube: boolean
  players: { id: string; name: string; number: number }[]
  onClose: () => void
  onSave: (t: VideoTag) => void
}) {
  const [form, setForm] = useState<VideoTag>(tag)
  const [timeStr, setTimeStr] = useState(fmtClock(tag.time))
  const [endStr, setEndStr] = useState(tag.endTime != null ? fmtClock(tag.endTime) : '')

  function commit() {
    const time = parseClock(timeStr) ?? form.time
    const endTime = endStr.trim() ? parseClock(endStr) ?? undefined : undefined
    onSave({ ...form, time, endTime })
  }

  return (
    <Modal
      title={tag.id ? 'Edit moment' : 'Tag a moment'}
      onClose={onClose}
      footer={
        <>
          <button className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" onClick={commit} disabled={!form.title.trim()}>Save moment</button>
        </>
      }
    >
      <div className="col" style={{ gap: 14 }}>
        <div className="grid grid-2">
          <div className="field">
            <label>Timecode {isYouTube && '(m:ss)'}</label>
            <input className="input mono" value={timeStr} onChange={(e) => setTimeStr(e.target.value)} placeholder="0:00" />
          </div>
          <div className="field">
            <label>End (optional)</label>
            <input className="input mono" value={endStr} onChange={(e) => setEndStr(e.target.value)} placeholder="—" />
          </div>
        </div>

        <div className="field">
          <label>Category</label>
          <div className="tag-list">
            {TAG_CATEGORIES.map((c) => (
              <span
                key={c.category}
                className={`chip${form.category === c.category ? ' active' : ''}`}
                onClick={() => setForm({ ...form, category: c.category })}
                style={{ cursor: 'pointer', borderColor: form.category === c.category ? c.color : undefined }}
              >
                {c.icon} {c.category}
              </span>
            ))}
          </div>
        </div>

        <div className="field">
          <label>Title</label>
          <input className="input" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="e.g. High press wins the ball back" autoFocus />
        </div>

        <div className="field">
          <label>Note</label>
          <textarea className="textarea" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} placeholder="Coaching detail, what to reinforce…" />
        </div>

        <div className="grid grid-2">
          <div className="field">
            <label>Player</label>
            <select className="select" value={form.playerId ?? ''} onChange={(e) => setForm({ ...form, playerId: e.target.value || undefined })}>
              <option value="">— none —</option>
              {players.slice().sort((a, b) => a.number - b.number).map((p) => (
                <option key={p.id} value={p.id}>{p.number} · {p.name}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Team</label>
            <div className="btn-group" style={{ width: '100%' }}>
              <button className={form.team === 'us' ? 'active' : ''} style={{ flex: 1 }} onClick={() => setForm({ ...form, team: 'us' })}>Us</button>
              <button className={form.team === 'them' ? 'active' : ''} style={{ flex: 1 }} onClick={() => setForm({ ...form, team: 'them' })}>Opposition</button>
            </div>
          </div>
        </div>
      </div>
    </Modal>
  )
}
