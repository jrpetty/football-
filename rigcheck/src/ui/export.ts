/**
 * Export. Everything the app computes should be extractable — a tool you cannot
 * get data out of is a dead end, and a quote you cannot hand to a customer is
 * half a feature.
 */
import type { ComparisonMatrix } from '../core/types.ts';
import type { EngineData } from '../core/engine.ts';

function download(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function csvCell(v: unknown): string {
  const s = String(v ?? '');
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function exportCsv(filename: string, rows: Record<string, unknown>[]) {
  if (!rows.length) return;
  const headers = [...new Set(rows.flatMap((r) => Object.keys(r)))];
  const body = [headers.join(','), ...rows.map((r) => headers.map((h) => csvCell(r[h])).join(','))].join('\n');
  download(filename, body, 'text/csv;charset=utf-8');
}

export function exportJson(filename: string, data: unknown) {
  download(filename, JSON.stringify(data, null, 2), 'application/json');
}

export function matrixToRows(matrix: ComparisonMatrix, data: EngineData): Record<string, unknown>[] {
  return matrix.cells.map((c) => ({
    build: c.buildId,
    game: data.games.get(c.gameId)?.name ?? c.gameId,
    resolution: c.resolution,
    status: c.estimate.status,
    avg_fps: c.estimate.avgFps?.toFixed(1) ?? '',
    low_1pct: c.estimate.low1PctFps?.toFixed(1) ?? '',
    low_01pct: c.estimate.low01PctFps?.toFixed(1) ?? '',
    smoothness: c.estimate.smoothness?.verdict ?? '',
    limiter: c.estimate.limiter ?? '',
    uncertainty_pct: c.estimate.uncertainty ? (c.estimate.uncertainty * 100).toFixed(0) : '',
    confidence: c.estimate.confidence,
    delta_vs_baseline_pct: c.delta != null ? (c.delta * 100).toFixed(1) : '',
    delta_within_noise: c.deltaWithinNoise ? 'yes' : '',
    gate_failures: c.estimate.gateFailures.map((f) => f.code).join('; '),
  }));
}

/**
 * A printable customer quote.
 *
 * Deliberately carries the provenance caveat: handing someone a performance
 * sheet that looks authoritative without saying where the numbers came from
 * would be the dishonest use of this tool.
 */
export function printQuote(html: string) {
  const w = window.open('', '_blank');
  if (!w) return;
  w.document.write(html);
  w.document.close();
  w.focus();
  setTimeout(() => w.print(), 400);
}

export function buildQuoteHtml(opts: {
  title: string;
  buildName: string;
  cpu: string;
  gpu: string;
  ram: string;
  storage: string;
  rows: { game: string; resolution: string; fps: string; low: string }[];
  powerW?: number;
  psuW?: number;
  yearlyCost?: string;
  price?: string;
  seeded: boolean;
}): string {
  const esc = (s: string) => s.replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' })[c]!);
  return `<!doctype html><html><head><meta charset="utf-8"><title>${esc(opts.title)}</title>
<style>
  body { font-family: ui-sans-serif, system-ui, sans-serif; color: #111; margin: 32px; line-height: 1.5; }
  h1 { font-size: 20px; margin: 0 0 2px; }
  .sub { color: #666; font-size: 13px; margin-bottom: 20px; }
  table { border-collapse: collapse; width: 100%; margin: 14px 0; font-size: 13px; }
  th, td { text-align: left; padding: 7px 10px; border-bottom: 1px solid #ddd; }
  th { font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; color: #666; }
  td.n, th.n { text-align: right; font-variant-numeric: tabular-nums; }
  .spec { display: grid; grid-template-columns: auto 1fr; gap: 3px 16px; font-size: 13px; margin-bottom: 8px; }
  .spec dt { color: #666; }
  .spec dd { margin: 0; font-weight: 600; }
  .caveat { margin-top: 24px; padding: 10px 12px; background: #fdf6e3; border-left: 3px solid #b58900; font-size: 11.5px; color: #555; }
  .price { font-size: 22px; font-weight: 700; margin: 12px 0; }
</style></head><body>
<h1>${esc(opts.buildName)}</h1>
<div class="sub">Expected performance — prepared ${new Date().toLocaleDateString('en-GB')}</div>
<dl class="spec">
  <dt>Processor</dt><dd>${esc(opts.cpu)}</dd>
  <dt>Graphics</dt><dd>${esc(opts.gpu)}</dd>
  <dt>Memory</dt><dd>${esc(opts.ram)}</dd>
  <dt>Storage</dt><dd>${esc(opts.storage)}</dd>
  ${opts.powerW ? `<dt>Power draw</dt><dd>${opts.powerW}W under load${opts.psuW ? ` · ${opts.psuW}W PSU recommended` : ''}</dd>` : ''}
  ${opts.yearlyCost ? `<dt>Running cost</dt><dd>about ${esc(opts.yearlyCost)} a year in electricity</dd>` : ''}
</dl>
${opts.price ? `<div class="price">${esc(opts.price)}</div>` : ''}
<table>
  <thead><tr><th>Game</th><th>Resolution</th><th class="n">Average FPS</th><th class="n">1% low</th></tr></thead>
  <tbody>
    ${opts.rows.map((r) => `<tr><td>${esc(r.game)}</td><td>${esc(r.resolution)}</td><td class="n">${esc(r.fps)}</td><td class="n">${esc(r.low)}</td></tr>`).join('')}
  </tbody>
</table>
<div class="caveat">
  <b>About these figures.</b> They are modelled estimates, not measurements taken on this machine.
  ${opts.seeded ? 'The underlying hardware data is currently seeded rather than sourced, so treat the numbers as indicative of relative performance rather than exact. ' : ''}
  Actual frame rates vary with game version, driver, settings and the rest of the system.
</div>
</body></html>`;
}
