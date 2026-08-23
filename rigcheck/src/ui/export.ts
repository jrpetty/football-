/**
 * Export. Everything the app computes should be extractable — a tool you cannot
 * get data out of is a dead end, and a quote you cannot hand to a customer is
 * half a feature.
 */
/**
 * Hand a file to the user.
 *
 * Two paths, because the app runs in two very different places. Served
 * normally (or opened from the single-file build) an anchor download works.
 * Inside the artifact viewer it does NOT — page-initiated downloads are
 * blocked there, so an anchor click silently does nothing, which is the worst
 * possible outcome: a button that looks like it worked. The viewer instead
 * grants a mediated save the user confirms, so try that first when it exists.
 */
async function download(filename: string, content: string, mime: string): Promise<void> {
  const claude = (globalThis as { claude?: { use?: (n: string) => Promise<unknown> } }).claude;
  if (claude?.use) {
    try {
      const dl = (await claude.use('downloads')) as
        | { save: (r: { filename: string; data: string }) => Promise<unknown> }
        | null;
      if (dl) {
        await dl.save({ filename, data: content });
        return;
      }
    } catch (err) {
      // The viewer declining is a normal outcome, not an error to shout about.
      const code = (err as { code?: string } | undefined)?.code;
      if (code === 'declined' || code === 'rate_limited') return;
      // Anything else: fall through and try the ordinary anchor path.
    }
  }

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

export function exportCsv(filename: string, rows: Record<string, unknown>[]): void {
  if (!rows.length) return;
  const headers = [...new Set(rows.flatMap((r) => Object.keys(r)))];
  const body = [headers.join(','), ...rows.map((r) => headers.map((h) => csvCell(r[h])).join(','))].join('\n');
  void download(filename, body, 'text/csv;charset=utf-8');
}

export function exportJson(filename: string, data: unknown): void {
  void download(filename, JSON.stringify(data, null, 2), 'application/json');
}

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
