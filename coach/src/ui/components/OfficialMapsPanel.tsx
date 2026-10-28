// Official overhead maps: each map's published tactical map, which the coach
// places your stitched minimap on, so pins land on the exact map. The images
// are the publisher's artwork, so the app does not ship them: the player
// downloads each from its official page (desktop app) or saves and imports it.

import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import type { PlatformInfo } from '../../../shared/bridge.ts'
import type { GameMap, GameProfile } from '../../core/profile/schema.ts'
import { dateShort } from '../format.ts'
import type { CoachSession } from '../session.ts'

type Result = { readonly name: string; readonly ok: boolean; readonly message: string }

export function OfficialMapsPanel({ session, profile, info }: { session: CoachSession; profile: GameProfile; info: PlatformInfo | null }) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const maps = profile.knowledge.maps ?? []
  const [results, setResults] = useState<readonly Result[]>([])
  const [drag, setDrag] = useState(false)
  const [confirm, setConfirm] = useState<string | null>(null)
  const picker = useRef<HTMLInputElement>(null)
  const forMap = useRef<string | null>(null)
  const desktop = info?.shell === 'electron'
  const kept = maps.filter((m) => state.overheads[m.id]).length
  const busy = state.overheadBusy

  const importFiles = async (files: readonly File[], mapId?: string) => {
    if (!files.length) return
    setResults(await session.importOverheads(files, mapId))
  }
  const download = async (m: GameMap) => {
    const r = await session.downloadOverhead(m.id)
    setResults([{ name: m.name, ...r }])
  }

  return (
    <section className="panel" aria-labelledby="official-h">
      <div className="panel-head">
        <h2 id="official-h">Official overhead maps: {profile.game.title}</h2>
        <span className={`badge ${kept ? 'ok' : 'neutral'}`}>
          {kept} of {maps.length} maps
        </span>
      </div>
      <p className="small" style={{ marginTop: 0 }}>
        Give the coach a map’s official overhead (tac) map and it places your stitched minimap on it by itself while you play, so deaths, kills, killers and your path
        are pinned on the exact map, shown in the overhead and 3D views. A placement it is not sure of is never drawn (two look-alike wings stay unplaced until you
        walk somewhere only one has).
      </p>
      <p className="muted small">
        These images are the publisher’s artwork, so the app does not ship them. {desktop ? 'Download each from its official page here, or open' : 'Open'} the page, save
        the map image and import it. They are kept on this computer only and never uploaded.
      </p>

      <div
        className={`drop-zone${drag ? ' over' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDrag(true)
        }}
        onDragLeave={() => setDrag(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDrag(false)
          void importFiles([...e.dataTransfer.files])
        }}
      >
        <span>Drop map images here, named after their maps (“cachette.png”, “Reactor U92.webp”)</span>
        <button
          type="button"
          className="btn sm"
          disabled={!!busy}
          onClick={() => {
            forMap.current = null
            picker.current?.click()
          }}
        >
          Import files…
        </button>
        <input
          ref={picker}
          type="file"
          accept="image/png,image/jpeg,image/webp"
          multiple
          hidden
          onChange={(e) => {
            const files = [...(e.target.files ?? [])]
            e.target.value = ''
            void importFiles(files, forMap.current ?? undefined)
          }}
        />
      </div>

      {results.length ? (
        <ul className="plain small official-results" aria-live="polite">
          {results.map((r, i) => (
            <li key={i} className={r.ok ? 'ok-text' : 'warn-text'}>
              {r.ok ? '✓' : '⚠'} {r.name}: {r.message}
            </li>
          ))}
        </ul>
      ) : null}

      <ul className="plain official-list">
        {maps.map((m) => {
          const o = state.overheads[m.id]
          const page = m.overhead
          return (
            <li key={m.id} className="official-row">
              <Thumb session={session} mapId={m.id} savedAt={o?.savedAt ?? null} />
              <div className="official-name">
                <b>{m.name}</b>
                <span className="muted small">
                  {o ? `Kept · ${(o.bytes / 1024 / 1024).toFixed(1)} MB · ${dateShort(o.savedAt)}` : 'Not yet'}
                  {page ? ` · ${page.source === 'official' ? 'official page' : 'community page'}` : ' · no page known'}
                </span>
              </div>
              <div className="btn-row">
                {busy === m.id ? <span className="muted small">Working…</span> : null}
                {page && desktop ? (
                  <button type="button" className="btn sm" disabled={!!busy} onClick={() => void download(m)} title={page.page}>
                    {o ? 'Download again' : 'Download'}
                  </button>
                ) : null}
                {page && desktop ? (
                  <button type="button" className="btn sm ghost" onClick={() => void session.openOverheadPage(m.id)}>
                    Open page
                  </button>
                ) : null}
                <button
                  type="button"
                  className="btn sm ghost"
                  disabled={!!busy}
                  onClick={() => {
                    forMap.current = m.id
                    picker.current?.click()
                  }}
                >
                  Import…
                </button>
                {o ? (
                  confirm === m.id ? (
                    <>
                      <button
                        type="button"
                        className="btn sm danger"
                        onClick={() => {
                          void session.removeOverhead(m.id)
                          setConfirm(null)
                        }}
                      >
                        Remove
                      </button>
                      <button type="button" className="btn sm ghost" onClick={() => setConfirm(null)}>
                        Keep
                      </button>
                    </>
                  ) : (
                    <button type="button" className="btn sm ghost danger" onClick={() => setConfirm(m.id)}>
                      Remove…
                    </button>
                  )
                ) : null}
              </div>
            </li>
          )
        })}
      </ul>
      {!desktop ? <p className="muted small">Downloading and opening pages need the desktop app; importing works here (for this page only).</p> : null}
    </section>
  )
}

function Thumb({ session, mapId, savedAt }: { session: CoachSession; mapId: string; savedAt: string | null }) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    setUrl(null)
    if (savedAt) void session.overheadPicture(mapId).then((u) => live && setUrl(u))
    return () => {
      live = false
    }
  }, [session, mapId, savedAt])
  return <div className="official-thumb">{url ? <img src={url} alt="" /> : <span aria-hidden="true">▦</span>}</div>
}
