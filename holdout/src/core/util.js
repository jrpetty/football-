// Small shared helpers: math, randomness, events, DOM.

export const clamp = (v, a, b) => (v < a ? a : v > b ? b : v)
export const lerp = (a, b, t) => a + (b - a) * t
export const smooth = (t) => t * t * (3 - 2 * t)
export const rand = (a = 0, b = 1) => a + Math.random() * (b - a)
export const rint = (a, b) => Math.floor(a + Math.random() * (b - a + 1))
export const pick = (arr) => arr[Math.floor(Math.random() * arr.length)]
export const chance = (p) => Math.random() < p
export const dist = (ax, az, bx, bz) => Math.hypot(ax - bx, az - bz)
export const angleLerp = (a, b, t) => {
  t = t < 0 ? 0 : t > 1 ? 1 : t
  let d = ((b - a + Math.PI) % (Math.PI * 2)) - Math.PI
  if (d < -Math.PI) d += Math.PI * 2
  return a + d * t
}

export function weighted(list, key = 'w') {
  let total = 0
  for (const e of list) total += e[key]
  let r = Math.random() * total
  for (const e of list) {
    r -= e[key]
    if (r <= 0) return e
  }
  return list[list.length - 1]
}

export function shuffle(arr) {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[arr[i], arr[j]] = [arr[j], arr[i]]
  }
  return arr
}

// Deterministic RNG for the city map, so a save always regenerates the same streets.
export function mulberry32(seed) {
  return function () {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

let uidCounter = Date.now() % 100000
export const uid = (p = 'id') => `${p}${(uidCounter++).toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`

export class Emitter {
  constructor() {
    this.map = new Map()
  }
  on(ev, fn) {
    if (!this.map.has(ev)) this.map.set(ev, new Set())
    this.map.get(ev).add(fn)
    return () => this.map.get(ev)?.delete(fn)
  }
  emit(ev, ...args) {
    const s = this.map.get(ev)
    if (s) for (const fn of [...s]) fn(...args)
  }
}
export const bus = new Emitter()

// Tiny hyperscript: h('div.card#id', {onclick}, ...children)
export function h(sel, attrs, ...kids) {
  const m = sel.match(/^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i)
  const el = document.createElement(m[1] || 'div')
  for (const part of m[2].match(/[.#][\w-]+/g) || []) {
    if (part[0] === '.') el.classList.add(part.slice(1))
    else el.id = part.slice(1)
  }
  if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
    kids.unshift(attrs)
    attrs = null
  }
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue
      if (k.startsWith('on')) el.addEventListener(k.slice(2), v)
      else if (k === 'style' && typeof v === 'object') {
        for (const [sk, sv] of Object.entries(v)) {
          if (sv == null) continue
          if (sk.startsWith('--')) el.style.setProperty(sk, sv)
          else el.style[sk] = sv
        }
      }
      else if (k === 'html') el.innerHTML = v
      else if (k in el && k !== 'list' && k !== 'type') el[k] = v
      else el.setAttribute(k, v === true ? '' : v)
    }
  }
  appendKids(el, kids)
  return el
}
function appendKids(el, kids) {
  for (const k of kids) {
    if (k == null || k === false) continue
    if (Array.isArray(k)) appendKids(el, k)
    else el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)))
  }
}

export const fmt = (n) => {
  n = Math.floor(n)
  if (n >= 10000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k'
  return String(n)
}
export const fmtTime = (s) => {
  s = Math.max(0, Math.ceil(s))
  const m = Math.floor(s / 60)
  return m > 0 ? `${m}:${String(s % 60).padStart(2, '0')}` : `${s}s`
}

export const store = {
  get(k) {
    try {
      return localStorage.getItem(k)
    } catch {
      return null
    }
  },
  set(k, v) {
    try {
      localStorage.setItem(k, v)
      return true
    } catch {
      return false
    }
  },
  del(k) {
    try {
      localStorage.removeItem(k)
    } catch {}
  },
}
