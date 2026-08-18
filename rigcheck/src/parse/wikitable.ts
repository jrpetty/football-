/**
 * Rowspan/colspan-aware HTML table parser.
 *
 * Spec hazard §5.2: Wikipedia hardware tables use rowspan and colspan
 * aggressively. Naive parsing silently misaligns columns and corrupts every
 * downstream record — silently, which is the dangerous part. This expands spans
 * into a dense grid so column N always means column N.
 *
 * We parse the RENDERED HTML (MediaWiki action=parse) rather than wikitext:
 * wikitext would mean reimplementing template expansion.
 *
 * This module is deliberately standalone and fully unit-tested against fixtures,
 * because the sources it targets are egress-blocked in the current environment.
 * When they unblock, the parser is already proven.
 */

export interface Cell {
  text: string;
  isHeader: boolean;
  /** True when this cell's value came from a span rather than its own tag. */
  spanned: boolean;
}

export type Grid = Cell[][];

interface RawCell {
  text: string;
  isHeader: boolean;
  rowSpan: number;
  colSpan: number;
}

/** Strip tags, decode the entities that actually appear, drop footnote markers. */
export function cleanCellText(html: string): string {
  let s = html;
  // Remove reference superscripts entirely — they corrupt numeric parses.
  s = s.replace(/<sup\b[^>]*>[\s\S]*?<\/sup>/gi, '');
  s = s.replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '');
  s = s.replace(/<[^>]+>/g, ' ');
  s = s
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#(\d+);/g, (_, d: string) => String.fromCharCode(Number(d)))
    .replace(/&[a-z]+;/gi, ' ');
  // Bracketed footnote markers such as [1] or [a].
  s = s.replace(/\[\s*[a-z0-9]{1,3}\s*\]/gi, '');
  return s.replace(/\s+/g, ' ').trim();
}

function attrNumber(tag: string, name: string): number {
  const m = new RegExp(`${name}\\s*=\\s*["']?(\\d+)`, 'i').exec(tag);
  const n = m ? Number(m[1]) : 1;
  // Guard against absurd spans in malformed markup.
  return Number.isFinite(n) && n > 0 && n < 1000 ? n : 1;
}

function parseRows(tableHtml: string): RawCell[][] {
  const rows: RawCell[][] = [];
  const rowRe = /<tr\b[^>]*>([\s\S]*?)<\/tr>/gi;
  let rm: RegExpExecArray | null;
  while ((rm = rowRe.exec(tableHtml))) {
    const cells: RawCell[] = [];
    const cellRe = /<(t[hd])\b([^>]*)>([\s\S]*?)<\/\1>/gi;
    let cm: RegExpExecArray | null;
    while ((cm = cellRe.exec(rm[1]))) {
      cells.push({
        text: cleanCellText(cm[3]),
        isHeader: cm[1].toLowerCase() === 'th',
        rowSpan: attrNumber(cm[2], 'rowspan'),
        colSpan: attrNumber(cm[2], 'colspan'),
      });
    }
    rows.push(cells);
  }
  return rows;
}

/**
 * Expand a table's rowspan/colspan into a dense rectangular grid.
 *
 * The algorithm walks cells left to right, skipping columns already claimed by
 * a rowspan from an earlier row, then writes the value into every cell the span
 * covers. Cells written by a span are marked `spanned: true` so callers can tell
 * a real value from an inherited one.
 */
export function expandTable(tableHtml: string): Grid {
  const raw = parseRows(tableHtml);
  const grid: Grid = [];
  // Columns claimed by an active rowspan: colIndex -> { remaining, cell }.
  const pending = new Map<number, { remaining: number; cell: Cell }>();

  for (let r = 0; r < raw.length; r++) {
    const out: Cell[] = [];
    let col = 0;

    const placePending = () => {
      while (pending.has(col)) {
        const p = pending.get(col)!;
        out[col] = { ...p.cell, spanned: true };
        p.remaining--;
        if (p.remaining <= 0) pending.delete(col);
        col++;
      }
    };

    for (const cell of raw[r]) {
      placePending();
      const base: Cell = { text: cell.text, isHeader: cell.isHeader, spanned: false };
      for (let c = 0; c < cell.colSpan; c++) {
        out[col] = c === 0 ? base : { ...base, spanned: true };
        if (cell.rowSpan > 1) {
          pending.set(col, { remaining: cell.rowSpan - 1, cell: base });
        }
        col++;
      }
    }
    placePending();

    grid.push(out);
  }

  // Rectangularise: a short row means missing markup, not a shifted column.
  const width = Math.max(0, ...grid.map((r) => r.length));
  for (const row of grid) {
    for (let i = 0; i < width; i++) {
      if (row[i] === undefined) row[i] = { text: '', isHeader: false, spanned: false };
    }
  }
  return grid;
}

/** Extract every <table> in a document. */
export function extractTables(html: string): string[] {
  const out: string[] = [];
  const re = /<table\b[^>]*>[\s\S]*?<\/table>/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(html))) out.push(m[0]);
  return out;
}

/**
 * Turn an expanded grid into records keyed by header text.
 * Header rows are the leading run of rows that are majority <th>.
 */
export function gridToRecords(grid: Grid): Record<string, string>[] {
  if (!grid.length) return [];
  let headerRows = 0;
  for (const row of grid) {
    const th = row.filter((c) => c.isHeader).length;
    if (th > row.length / 2) headerRows++;
    else break;
  }
  if (headerRows === 0) headerRows = 1;

  const width = grid[0].length;
  const headers: string[] = [];
  for (let c = 0; c < width; c++) {
    const parts: string[] = [];
    for (let r = 0; r < headerRows; r++) {
      const t = grid[r][c]?.text ?? '';
      if (t && parts[parts.length - 1] !== t) parts.push(t);
    }
    headers.push(parts.join(' ').trim() || `col${c}`);
  }

  const records: Record<string, string>[] = [];
  for (let r = headerRows; r < grid.length; r++) {
    const rec: Record<string, string> = {};
    for (let c = 0; c < width; c++) rec[headers[c]] = grid[r][c]?.text ?? '';
    // Skip separator rows that carry no data.
    if (Object.values(rec).some((v) => v !== '')) records.push(rec);
  }
  return records;
}

/** Parse a numeric field, tolerating units, ranges and thousands separators. */
export function parseNumeric(text: string): number | null {
  if (!text) return null;
  const cleaned = text.replace(/,/g, '').replace(/[–—]/g, '-');
  // A range like "1506-1683" takes the upper bound (boost clocks are quoted so).
  const range = /(-?\d+(?:\.\d+)?)\s*-\s*(-?\d+(?:\.\d+)?)/.exec(cleaned);
  if (range) return Number(range[2]);
  const m = /(-?\d+(?:\.\d+)?)/.exec(cleaned);
  return m ? Number(m[1]) : null;
}
