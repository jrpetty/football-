// Structural diffs of the camp state, for keeping several browsers on one
// camp. A diff is a small tree of nodes:
//   [0, v]                set the value (a rounded deep copy)
//   [1]                   delete the key
//   [2, {k: node}]        patch an object's fields
//   [3, key, {a, d, m, o}] patch an array of records keyed by `key` (id/uid):
//                         a: added [[index, record]], d: removed ids,
//                         m: {id: node}, o: the full id order when it moved
//   [4, n]                add n to a number (resources, counters)
//   [5, len, {i: node}]   patch a plain array position by position
// Patching an object or a keyed record changes it in place, so the scene's
// references to survivors and stations stay valid on every machine.
//
// A policy steers a diff: {skip: Set of keys, add: numbers here are
// additive, kids: {key: policy}, all: policy for every child, each: policy
// for every record of an array}.

const r3 = (v) => (Number.isInteger(v) || !Number.isFinite(v) ? v : Math.abs(v) >= 1e4 ? Math.round(v * 10) / 10 : Math.round(v * 1000) / 1000)

// A JSON-safe deep copy with numbers rounded to what is worth sending.
export function clone(v) {
  if (v === null || typeof v !== 'object') return typeof v === 'number' ? (Number.isFinite(v) ? r3(v) : null) : v === undefined ? null : v
  if (Array.isArray(v)) return v.map(clone)
  const o = {}
  for (const k in v) {
    const x = v[k]
    if (x === undefined || typeof x === 'function') continue
    o[k] = clone(x)
  }
  return o
}

const kind = (v) => (v === null || v === undefined ? 'u' : Array.isArray(v) ? 'a' : typeof v === 'object' ? 'o' : typeof v)

// Numbers that drift every tick are only worth sending once they have moved.
function numEq(a, b, k, exact) {
  if (a === b) return true
  if (exact) return false
  if (k === 'time') return Math.abs(a - b) < 1.5
  if (Number.isInteger(a) && Number.isInteger(b)) return false
  return Math.abs(a - b) <= Math.max(0.01, Math.abs(b) * 0.002)
}

// The key records in an array are known by, or null for a plain array.
function recKey(arr) {
  let k = null
  for (const e of arr) {
    if (!e || typeof e !== 'object' || Array.isArray(e)) return null
    const kk = e.id != null ? 'id' : e.uid != null ? 'uid' : null
    if (!kk || (k && kk !== k)) return null
    k = kk
  }
  return k
}

const child = (pol, k) => (pol && (pol.kids?.[k] || pol.all)) || null

export function diff(a, b, pol = null, k = '', exact = false) {
  if (a === b) return undefined
  const ta = kind(a)
  const tb = kind(b)
  if (tb === 'u') return ta === 'u' ? undefined : [1]
  if (ta !== tb) return [0, clone(b)]
  if (tb === 'number') {
    if (numEq(a, b, k, exact)) return undefined
    return pol?.add ? [4, r3(b - a)] : [0, r3(b)]
  }
  if (tb !== 'o' && tb !== 'a') return a === b ? undefined : [0, b]
  if (tb === 'o') return diffObj(a, b, pol, exact)
  return diffArr(a, b, pol, k, exact)
}

function diffObj(a, b, pol, exact) {
  let out = null
  const skip = pol?.skip
  for (const k in b) {
    if (skip?.has(k)) continue
    const bv = b[k]
    if (bv === undefined || typeof bv === 'function') continue
    const n = diff(a[k], bv, child(pol, k), k, exact)
    if (n) (out ||= {})[k] = n
  }
  for (const k in a) {
    if (skip?.has(k) || (b[k] !== undefined && typeof b[k] !== 'function')) continue
    if (a[k] !== undefined) (out ||= {})[k] = [1]
  }
  return out ? [2, out] : undefined
}

function diffArr(a, b, pol, k, exact) {
  const key = b.length ? recKey(b) : a.length ? recKey(a) : null
  const keyA = a.length ? recKey(a) : key
  if (key && keyA === key) return diffKeyed(a, b, key, pol?.each || null, exact)
  if (a.length === b.length && a.length) {
    let out = null
    let n = 0
    for (let i = 0; i < b.length; i++) {
      const d = diff(a[i], b[i], pol?.each || null, k, exact)
      if (d) {
        ;(out ||= {})[i] = d
        n++
      }
    }
    if (!out) return undefined
    if (n * 2 <= b.length) return [5, b.length, out]
  } else if (!a.length && !b.length) return undefined
  return [0, clone(b)]
}

function diffKeyed(a, b, key, pol, exact) {
  const old = new Map()
  for (const e of a) old.set(String(e[key]), e)
  const seen = new Set()
  const out = {}
  b.forEach((e, i) => {
    const id = String(e[key])
    seen.add(id)
    const o = old.get(id)
    if (!o) (out.a ||= []).push([i, clone(e)])
    else {
      const n = diff(o, e, pol, '', exact)
      if (n) (out.m ||= {})[id] = n
    }
  })
  for (const id of old.keys()) if (!seen.has(id)) (out.d ||= []).push(id)
  // does applying adds and removals give b's order? if not, send it
  const ord = a.map((e) => String(e[key])).filter((id) => seen.has(id))
  for (const [i, e] of out.a || []) ord.splice(Math.min(i, ord.length), 0, String(e[key]))
  if (ord.length !== b.length || ord.some((id, i) => id !== String(b[i][key]))) out.o = b.map((e) => String(e[key]))
  return out.a || out.d || out.m || out.o ? [3, key, out] : undefined
}

// ---------------------------------------------------------------- apply
// Apply node to obj[k]. `pol.skip` keys are left alone; `opts.keepOrder`
// ignores a sender's ordering (the host keeps its own).
export function apply(obj, k, node, opts = {}) {
  switch (node[0]) {
    case 0:
      obj[k] = clone(node[1])
      break
    case 1:
      if (Array.isArray(obj)) obj[k] = null
      else delete obj[k]
      break
    case 2: {
      let o = obj[k]
      if (!o || typeof o !== 'object' || Array.isArray(o)) o = obj[k] = {}
      applyFields(o, node[1], opts)
      break
    }
    case 3:
      applyKeyed(obj, k, node[1], node[2], opts)
      break
    case 4:
      obj[k] = r3((typeof obj[k] === 'number' ? obj[k] : 0) + node[1])
      break
    case 5: {
      let arr = obj[k]
      if (!Array.isArray(arr)) arr = obj[k] = []
      arr.length = node[1]
      for (const i in node[2]) apply(arr, +i, node[2][i], opts)
      break
    }
  }
}
export function applyFields(o, fields, opts = {}) {
  for (const kk in fields) {
    if (opts.skip?.has(kk)) continue
    apply(o, kk, fields[kk], { ...opts, skip: null })
  }
}

function applyKeyed(obj, k, key, P, opts) {
  let arr = obj[k]
  if (!Array.isArray(arr)) arr = obj[k] = []
  if (P.d) {
    const gone = new Set(P.d.map(String))
    let j = 0
    for (const e of arr) if (!e || !gone.has(String(e[key]))) arr[j++] = e
    arr.length = j
  }
  if (P.m) {
    const at = new Map()
    arr.forEach((e, i) => e && at.set(String(e[key]), i))
    for (const id in P.m) {
      const i = at.get(id)
      if (i === undefined) continue
      const n = P.m[id]
      if (n[0] === 2) applyFields(arr[i], n[1], opts)
      else if (n[0] === 0 && n[1]) arr[i] = clone(n[1])
    }
  }
  if (P.a) {
    const have = new Set(arr.map((e) => e && String(e[key])))
    for (const [i, e] of P.a) {
      if (have.has(String(e[key]))) continue
      arr.splice(Math.min(i, arr.length), 0, clone(e))
    }
  }
  if (P.o && !opts.keepOrder) {
    const by = new Map(arr.map((e) => [String(e[key]), e]))
    const next = P.o.map((id) => by.get(id)).filter(Boolean)
    for (const e of arr) if (!P.o.includes(String(e[key]))) next.push(e)
    arr.length = 0
    arr.push(...next)
  }
}

// ---------------------------------------------------------------- helpers
// The top-level keys a diff touches.
export const touched = (node) => (node && node[0] === 2 ? Object.keys(node[1]) : [])
export const size = (x) => (x == null ? 0 : JSON.stringify(x).length)
