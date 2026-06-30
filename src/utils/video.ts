import type { VideoSource } from '../types'

/** Extract an 11-char YouTube id from a URL, id, or embed string. */
export function parseYouTubeId(input: string): string | null {
  if (!input) return null
  const trimmed = input.trim()
  // Already a bare id.
  if (/^[a-zA-Z0-9_-]{11}$/.test(trimmed)) return trimmed
  const patterns = [
    /youtu\.be\/([a-zA-Z0-9_-]{11})/,
    /youtube\.com\/watch\?v=([a-zA-Z0-9_-]{11})/,
    /youtube\.com\/embed\/([a-zA-Z0-9_-]{11})/,
    /youtube\.com\/shorts\/([a-zA-Z0-9_-]{11})/,
    /[?&]v=([a-zA-Z0-9_-]{11})/,
  ]
  for (const p of patterns) {
    const m = trimmed.match(p)
    if (m) return m[1]
  }
  return null
}

export function youTubeEmbedUrl(idOrUrl: string): string {
  const id = parseYouTubeId(idOrUrl) ?? idOrUrl
  const params = new URLSearchParams({ rel: '0', modestbranding: '1', playsinline: '1', enablejsapi: '1' })
  // A valid origin improves embed compatibility; only meaningful over http(s).
  if (typeof window !== 'undefined' && window.location?.protocol?.startsWith('http')) {
    params.set('origin', window.location.origin)
  }
  return `https://www.youtube.com/embed/${id}?${params.toString()}`
}

/** Public watch URL for opening a clip on YouTube. */
export function youTubeWatchUrl(idOrUrl: string, atSeconds?: number): string {
  const id = parseYouTubeId(idOrUrl) ?? idOrUrl
  const t = atSeconds && atSeconds > 0 ? `&t=${Math.floor(atSeconds)}` : ''
  return `https://www.youtube.com/watch?v=${id}${t}`
}

/** True when the app is opened directly from disk (YouTube embeds are flaky here). */
export function isFileProtocol(): boolean {
  return typeof window !== 'undefined' && window.location?.protocol === 'file:'
}

/** Best-effort guess of the source type from a pasted string. */
export function guessSource(input: string): VideoSource {
  if (parseYouTubeId(input)) return 'youtube'
  return 'url'
}

/** Seconds → m:ss (or h:mm:ss for long clips). */
export function fmtClock(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
  return `${m}:${String(sec).padStart(2, '0')}`
}

/** Parse "m:ss" or "h:mm:ss" or a raw seconds string into seconds. */
export function parseClock(input: string): number | null {
  const t = input.trim()
  if (/^\d+$/.test(t)) return parseInt(t, 10)
  const parts = t.split(':').map((p) => parseInt(p, 10))
  if (parts.some((p) => Number.isNaN(p))) return null
  if (parts.length === 2) return parts[0] * 60 + parts[1]
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2]
  return null
}
