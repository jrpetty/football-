// ---------------------------------------------------------------------------
// Parser for Python `repr` output embedded in the FPL fixture exports.
//
// The `stats` column looks like JSON but is not:
//
//   [{'identifier': 'yellow_cards', 'a': [{'value': 1, 'element': 371}], 'h': []}]
//
// Single quotes, and True/False/None instead of true/false/null. JSON.parse
// throws on all of it. Every per-fixture card, goal and assist attribution
// lives in this column, so it has to be read correctly — a regex would break
// on any apostrophe in a name.
//
// This is a proper recursive-descent parser over a deliberately tiny grammar.
// It accepts only literals, lists and dicts; anything else is a hard error
// rather than a silent partial parse.
// ---------------------------------------------------------------------------

export type PyValue = string | number | boolean | null | PyValue[] | { [k: string]: PyValue }

class Reader {
  text: string
  pos: number
  constructor(text: string) {
    this.text = text
    this.pos = 0
  }
  error(msg: string): never {
    const near = this.text.slice(Math.max(0, this.pos - 30), this.pos + 30)
    throw new Error(`pyrepr: ${msg} at ${this.pos} near "${near}"`)
  }
  skipWs(): void {
    while (this.pos < this.text.length && /\s/.test(this.text[this.pos]!)) this.pos++
  }
  peek(): string {
    return this.text[this.pos] ?? ''
  }
  expect(ch: string): void {
    if (this.text[this.pos] !== ch) this.error(`expected "${ch}"`)
    this.pos++
  }
}

function parseString(r: Reader): string {
  const quote = r.peek()
  if (quote !== "'" && quote !== '"') r.error('expected a string')
  r.pos++
  let out = ''
  while (r.pos < r.text.length) {
    const c = r.text[r.pos]!
    if (c === '\\') {
      const next = r.text[r.pos + 1]!
      // Python escapes we can actually meet in club and player names.
      out += next === 'n' ? '\n' : next === 't' ? '\t' : next
      r.pos += 2
      continue
    }
    if (c === quote) {
      r.pos++
      return out
    }
    out += c
    r.pos++
  }
  r.error('unterminated string')
}

function parseValue(r: Reader): PyValue {
  r.skipWs()
  const c = r.peek()
  if (c === '[' || c === '(') {
    const close = c === '[' ? ']' : ')'
    r.pos++
    const arr: PyValue[] = []
    r.skipWs()
    if (r.peek() === close) {
      r.pos++
      return arr
    }
    for (;;) {
      arr.push(parseValue(r))
      r.skipWs()
      if (r.peek() === ',') {
        r.pos++
        r.skipWs()
        // Trailing comma, as in a one-element Python tuple.
        if (r.peek() === close) {
          r.pos++
          return arr
        }
        continue
      }
      if (r.peek() === close) {
        r.pos++
        return arr
      }
      r.error('expected "," or end of list')
    }
  }
  if (c === '{') {
    r.pos++
    const obj: { [k: string]: PyValue } = {}
    r.skipWs()
    if (r.peek() === '}') {
      r.pos++
      return obj
    }
    for (;;) {
      r.skipWs()
      const key = parseString(r)
      r.skipWs()
      r.expect(':')
      obj[key] = parseValue(r)
      r.skipWs()
      if (r.peek() === ',') {
        r.pos++
        r.skipWs()
        if (r.peek() === '}') {
          r.pos++
          return obj
        }
        continue
      }
      if (r.peek() === '}') {
        r.pos++
        return obj
      }
      r.error('expected "," or "}"')
    }
  }
  if (c === "'" || c === '"') return parseString(r)
  if (r.text.startsWith('True', r.pos)) {
    r.pos += 4
    return true
  }
  if (r.text.startsWith('False', r.pos)) {
    r.pos += 5
    return false
  }
  if (r.text.startsWith('None', r.pos)) {
    r.pos += 4
    return null
  }
  const m = /^-?\d+(\.\d+)?([eE][-+]?\d+)?/.exec(r.text.slice(r.pos))
  if (m) {
    r.pos += m[0].length
    return Number(m[0])
  }
  r.error('unrecognised value')
}

/** Parse a Python repr literal. Throws on anything malformed. */
export function parsePyRepr(text: string): PyValue {
  const trimmed = text.trim()
  if (trimmed === '') return []
  const r = new Reader(trimmed)
  const v = parseValue(r)
  r.skipWs()
  if (r.pos !== r.text.length) r.error('trailing content')
  return v
}

export interface FixtureStatEntry {
  value: number
  element: number
}
export interface FixtureStatBlock {
  identifier: string
  h: FixtureStatEntry[]
  a: FixtureStatEntry[]
}

/**
 * Read the fixture `stats` blob into typed per-side event lists.
 *
 * Accepts JSON too, because the live FPL API returns real JSON here while the
 * CSV mirror returns Python repr — one function serves both paths.
 */
export function parseFixtureStats(raw: string): FixtureStatBlock[] {
  if (!raw || raw.trim() === '' || raw.trim() === '[]') return []
  let parsed: PyValue
  try {
    parsed = JSON.parse(raw) as PyValue
  } catch {
    parsed = parsePyRepr(raw)
  }
  if (!Array.isArray(parsed)) return []
  const out: FixtureStatBlock[] = []
  for (const item of parsed) {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) continue
    const rec = item as { [k: string]: PyValue }
    const identifier = typeof rec['identifier'] === 'string' ? rec['identifier'] : ''
    if (!identifier) continue
    const side = (key: 'h' | 'a'): FixtureStatEntry[] => {
      const list = rec[key]
      if (!Array.isArray(list)) return []
      const entries: FixtureStatEntry[] = []
      for (const e of list) {
        if (typeof e !== 'object' || e === null || Array.isArray(e)) continue
        const er = e as { [k: string]: PyValue }
        const value = typeof er['value'] === 'number' ? er['value'] : 0
        const element = typeof er['element'] === 'number' ? er['element'] : 0
        if (element) entries.push({ value, element })
      }
      return entries
    }
    out.push({ identifier, h: side('h'), a: side('a') })
  }
  return out
}
