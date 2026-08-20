// ---------------------------------------------------------------------------
// A small RFC 4180 CSV reader.
//
// The FPL exports embed a Python-repr blob (containing commas, quotes and
// apostrophes) inside a quoted field, and player names carry accents and
// commas. A naive split(',') silently mangles all of it, so this handles
// quoting properly rather than approximately.
// ---------------------------------------------------------------------------

/** Parse CSV text into rows of raw string cells. */
export function parseCsvRows(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false
  let i = 0
  // Strip a UTF-8 BOM, which would otherwise poison the first header name.
  if (text.charCodeAt(0) === 0xfeff) i = 1

  while (i < text.length) {
    const c = text[i]!
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"'
          i += 2
          continue
        }
        quoted = false
        i++
        continue
      }
      field += c
      i++
      continue
    }
    if (c === '"') {
      quoted = true
      i++
      continue
    }
    if (c === ',') {
      row.push(field)
      field = ''
      i++
      continue
    }
    if (c === '\n') {
      row.push(field)
      rows.push(row)
      row = []
      field = ''
      i++
      continue
    }
    if (c === '\r') {
      i++
      continue
    }
    field += c
    i++
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field)
    rows.push(row)
  }
  return rows
}

export type CsvRecord = Record<string, string>

/** Parse CSV text into objects keyed by header name. */
export function parseCsv(text: string): CsvRecord[] {
  const rows = parseCsvRows(text)
  if (rows.length === 0) return []
  const header = rows[0]!
  const out: CsvRecord[] = []
  for (let r = 1; r < rows.length; r++) {
    const row = rows[r]!
    // Trailing newline produces a single empty cell; that is not a record.
    if (row.length === 1 && row[0] === '') continue
    const rec: CsvRecord = {}
    for (let c = 0; c < header.length; c++) rec[header[c]!] = row[c] ?? ''
    out.push(rec)
  }
  return out
}

/** Read a numeric cell, falling back to `fallback` for blanks and junk. */
export function num(rec: CsvRecord, key: string, fallback = 0): number {
  const raw = rec[key]
  if (raw === undefined || raw === '') return fallback
  const v = Number(raw)
  return Number.isFinite(v) ? v : fallback
}

/** Read a Python-style boolean cell ("True"/"False"). */
export function bool(rec: CsvRecord, key: string): boolean {
  return rec[key] === 'True' || rec[key] === 'true' || rec[key] === '1'
}
