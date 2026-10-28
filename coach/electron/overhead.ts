// Fetching a map's official overhead map, when the player asks for it. The
// image is not bundled with the app (it is the game publisher's artwork): the
// pack names the map's official guide page, and on the player's click the main
// process fetches that page and the images on it that may be the overhead map.
// Only the page's own sites are contacted; nothing about the player is sent.
// The renderer then checks each image (does it look like a tactical map?) and
// keeps the best one on this computer.

import type { GameProfile } from '../src/core/profile/schema.ts'

/** Sites a guide page and its images may be on. */
const ALLOWED_HOSTS = ['callofduty.com', 'activision.com', 'mw4camos.com']
const MAX_PAGE_BYTES = 4 * 1024 * 1024
const MAX_IMAGE_BYTES = 30 * 1024 * 1024
const MAX_CANDIDATES = 6
const IMAGE_EXT = /\.(png|jpe?g|webp)(?:$|[?#])/i

export function allowedUrl(raw: string): URL | null {
  try {
    const u = new URL(raw)
    if (u.protocol !== 'https:') return null
    const host = u.hostname.toLowerCase()
    return ALLOWED_HOSTS.some((h) => host === h || host.endsWith(`.${h}`)) ? u : null
  } catch {
    return null
  }
}

/** The map's guide page and names, from the pack. */
export function overheadPage(profile: GameProfile | null, mapId: unknown): { url: URL; names: string[] } | null {
  if (!profile || typeof mapId !== 'string') return null
  const map = profile.knowledge?.maps?.find((m) => m.id === mapId)
  const url = map?.overhead ? allowedUrl(map.overhead.page) : null
  return map && url ? { url, names: [map.id, map.name, ...(map.aliases ?? [])] } : null
}

const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '')
const attr = (tag: string, name: string) => new RegExp(`\\s${name}\\s*=\\s*("([^"]*)"|'([^']*)')`, 'i').exec(tag)?.slice(2).find((v) => v !== undefined) ?? null

/**
 * The images on a page that may be the map's overhead map, best first: named
 * after the map, or called a map/tac map/overhead/layout; logos, icons and
 * thumbnails last. Same-family sites only, https only.
 */
export function imageCandidates(html: string, pageUrl: string, names: readonly string[]): string[] {
  const found = new Map<string, { score: number; order: number }>()
  const keys = names.map(slug).filter((k) => k.length >= 3)
  const add = (raw: string | null, hint = '', width = 0) => {
    if (!raw) return
    let u: URL | null = null
    try {
      u = allowedUrl(new URL(raw.replace(/\\\//g, '/').replace(/&amp;/g, '&').trim(), pageUrl).toString())
    } catch {
      return
    }
    if (!u || !IMAGE_EXT.test(u.pathname)) return
    const href = u.toString()
    const text = slug(`${decodeURIComponent(u.pathname)} ${hint}`)
    let score = 0
    if (keys.some((k) => text.includes(k))) score += 4
    if (/tacmap|tactical|overhead|minimap|layout|topdown|mapoverview|map/.test(text)) score += 2
    if (/logo|icon|favicon|avatar|sprite|badge|thumb|button|social|share|banner|operator|weapon|camo/.test(text)) score -= 3
    if (/\.png/i.test(u.pathname)) score += 1
    if (width >= 1200) score += 1
    const prev = found.get(href)
    if (!prev || score > prev.score) found.set(href, { score, order: prev?.order ?? found.size })
  }
  for (const m of html.matchAll(/<meta\b[^>]*>/gi)) {
    const prop = (attr(m[0], 'property') ?? attr(m[0], 'name') ?? '').toLowerCase()
    if (prop === 'og:image' || prop === 'twitter:image') add(attr(m[0], 'content'), 'share')
  }
  for (const m of html.matchAll(/<(img|source)\b[^>]*>/gi)) {
    const hint = attr(m[0], 'alt') ?? attr(m[0], 'title') ?? ''
    for (const a of ['src', 'data-src', 'data-original']) add(attr(m[0], a), hint)
    for (const a of ['srcset', 'data-srcset']) {
      for (const part of (attr(m[0], a) ?? '').split(',')) {
        const [u, w] = part.trim().split(/\s+/)
        add(u, hint, Number.parseInt(w ?? '', 10) || 0)
      }
    }
  }
  for (const m of html.matchAll(/<a\b[^>]*>/gi)) add(attr(m[0], 'href'), attr(m[0], 'title') ?? '')
  // URLs in scripts and JSON (escaped slashes included).
  for (const m of html.matchAll(/https?:(?:\\?\/){2}[^\s"'<>()]+?\.(?:png|jpe?g|webp)(?:\?[^\s"'<>()]*)?/gi)) add(m[0])
  return [...found]
    .filter(([, v]) => v.score >= 0)
    .sort((a, b) => b[1].score - a[1].score || a[1].order - b[1].order)
    .slice(0, MAX_CANDIDATES)
    .map(([u]) => u)
}

async function readCapped(res: Response, max: number): Promise<Uint8Array | null> {
  const len = Number(res.headers.get('content-length') ?? 0)
  if (len > max || !res.body) return null
  const chunks: Uint8Array[] = []
  let n = 0
  const reader = res.body.getReader()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    n += value.byteLength
    if (n > max) {
      await reader.cancel().catch(() => undefined)
      return null
    }
    chunks.push(value)
  }
  const out = new Uint8Array(n)
  let o = 0
  for (const c of chunks) {
    out.set(c, o)
    o += c.byteLength
  }
  return out
}

export type FetchedOverhead = { ok: true; page: string; candidates: { url: string; bytes: Uint8Array }[] } | { ok: false; error: string }

/** Fetches the guide page and its likely overhead map images (the renderer picks the one that is a map). */
export async function fetchOverhead(page: { url: URL; names: string[] }, fetchImpl: typeof fetch = fetch): Promise<FetchedOverhead> {
  const manual = 'Open the official page, save the overhead map image, and import it here.'
  let html: string
  try {
    const res = await fetchImpl(page.url, { redirect: 'follow', signal: AbortSignal.timeout(20_000), headers: { accept: 'text/html' } })
    if (!res.ok || !allowedUrl(res.url || page.url.toString())) return { ok: false, error: `The official page did not load (HTTP ${res.status}). ${manual}` }
    const bytes = await readCapped(res, MAX_PAGE_BYTES)
    if (!bytes) return { ok: false, error: `The official page is too large. ${manual}` }
    html = new TextDecoder().decode(bytes)
  } catch (e) {
    return { ok: false, error: `The official page could not be reached (${e instanceof Error ? e.message : String(e)}). ${manual}` }
  }
  const urls = imageCandidates(html, page.url.toString(), page.names)
  const candidates: { url: string; bytes: Uint8Array }[] = []
  for (const url of urls) {
    try {
      const res = await fetchImpl(url, { redirect: 'follow', signal: AbortSignal.timeout(30_000) })
      if (!res.ok || !allowedUrl(res.url || url) || !(res.headers.get('content-type') ?? '').startsWith('image/')) continue
      const bytes = await readCapped(res, MAX_IMAGE_BYTES)
      if (bytes) candidates.push({ url, bytes })
    } catch {
      // The next one.
    }
  }
  if (!candidates.length) return { ok: false, error: `No map image was found on the official page. ${manual}` }
  return { ok: true, page: page.url.toString(), candidates }
}
