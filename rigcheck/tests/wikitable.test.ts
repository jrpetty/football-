/**
 * Fixtures modelled on the exact shapes that break naive parsers: a rowspan in
 * the middle of a table, a colspan header group, footnote superscripts inside
 * numeric cells, and a row that ends early.
 *
 * Spec §5.2 requires these to pass before a single record is trusted.
 */
import { describe, expect, it } from 'vitest';
import { cleanCellText, expandTable, gridToRecords, parseNumeric } from '../src/parse/wikitable.ts';

const ROWSPAN_TABLE = `
<table>
  <tr><th>Model</th><th>Launch</th><th>Shaders</th><th>Memory</th></tr>
  <tr><td>GTX 1060 3GB</td><td rowspan="2">2016-07-19</td><td>1152</td><td>3 GB</td></tr>
  <tr><td>GTX 1060 6GB</td><td>1280</td><td>6 GB</td></tr>
  <tr><td>GTX 1070</td><td>2016-06-10</td><td>1920</td><td>8 GB</td></tr>
</table>`;

const COLSPAN_HEADER_TABLE = `
<table>
  <tr><th rowspan="2">Model</th><th colspan="2">Clock (MHz)</th><th rowspan="2">TDP</th></tr>
  <tr><th>Base</th><th>Boost</th></tr>
  <tr><td>RTX 3060</td><td>1320</td><td>1777</td><td>170 W</td></tr>
</table>`;

const MESSY_TABLE = `
<table>
  <tr><th>Model</th><th>Clock</th><th>TDP</th></tr>
  <tr><td>RX 6800 XT<sup class="reference">[1]</sup></td><td>2250&nbsp;MHz</td><td>300&nbsp;W</td></tr>
  <tr><td>RX 6800</td><td>1815–2105 MHz</td></tr>
</table>`;

describe('cleanCellText', () => {
  it('strips footnote superscripts that would corrupt numeric parses', () => {
    expect(cleanCellText('300<sup class="reference">[3]</sup> W')).toBe('300 W');
  });

  it('decodes the entities that actually appear in these tables', () => {
    expect(cleanCellText('2250&nbsp;MHz')).toBe('2250 MHz');
    expect(cleanCellText('R&amp;D')).toBe('R&D');
  });

  it('strips bracketed footnote markers', () => {
    expect(cleanCellText('1777 [a]')).toBe('1777');
  });
});

describe('expandTable — rowspan', () => {
  const grid = expandTable(ROWSPAN_TABLE);

  it('produces a rectangular grid', () => {
    expect(grid).toHaveLength(4);
    for (const row of grid) expect(row).toHaveLength(4);
  });

  it('propagates a rowspan value into the following row WITHOUT shifting columns', () => {
    // This is the exact corruption the spec warns about: without expansion the
    // second data row would read shaders=6 GB and memory=undefined.
    expect(grid[1].map((c) => c.text)).toEqual(['GTX 1060 3GB', '2016-07-19', '1152', '3 GB']);
    expect(grid[2].map((c) => c.text)).toEqual(['GTX 1060 6GB', '2016-07-19', '1280', '6 GB']);
  });

  it('marks inherited cells as spanned so callers can tell them from real values', () => {
    expect(grid[1][1].spanned).toBe(false);
    expect(grid[2][1].spanned).toBe(true);
  });

  it('stops propagating once the span is exhausted', () => {
    expect(grid[3].map((c) => c.text)).toEqual(['GTX 1070', '2016-06-10', '1920', '8 GB']);
    expect(grid[3][1].spanned).toBe(false);
  });
});

describe('expandTable — colspan header groups', () => {
  const grid = expandTable(COLSPAN_HEADER_TABLE);

  it('expands a colspan across the columns it covers', () => {
    expect(grid[0].map((c) => c.text)).toEqual(['Model', 'Clock (MHz)', 'Clock (MHz)', 'TDP']);
  });

  it('keeps the second header row aligned under the group', () => {
    expect(grid[1].map((c) => c.text)).toEqual(['Model', 'Base', 'Boost', 'TDP']);
  });

  it('builds compound headers and aligns the data row', () => {
    const records = gridToRecords(grid);
    expect(records).toHaveLength(1);
    expect(records[0]['Clock (MHz) Base']).toBe('1320');
    expect(records[0]['Clock (MHz) Boost']).toBe('1777');
    expect(records[0]['Model']).toBe('RTX 3060');
    expect(records[0]['TDP']).toBe('170 W');
  });
});

describe('expandTable — malformed rows', () => {
  it('pads a short row rather than shifting its columns left', () => {
    const grid = expandTable(MESSY_TABLE);
    const last = grid[grid.length - 1];
    expect(last).toHaveLength(3);
    expect(last[0].text).toBe('RX 6800');
    expect(last[2].text).toBe('');
  });
});

describe('parseNumeric', () => {
  it('handles units and separators', () => {
    expect(parseNumeric('2250 MHz')).toBe(2250);
    expect(parseNumeric('1,024 MB')).toBe(1024);
    expect(parseNumeric('20.74 TFLOPS')).toBe(20.74);
  });

  it('takes the upper bound of a clock range, since boost is what is quoted', () => {
    expect(parseNumeric('1815–2105 MHz')).toBe(2105);
    expect(parseNumeric('1506-1683')).toBe(1683);
  });

  it('returns null rather than a fabricated zero when there is no number', () => {
    expect(parseNumeric('N/A')).toBeNull();
    expect(parseNumeric('')).toBeNull();
  });
});
