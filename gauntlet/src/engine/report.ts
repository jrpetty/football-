import type { Leaderboard, RunManifest } from '../core/types.ts';

export function fmtCost(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  if (v === 0) return '$0';
  if (v < 0.01) return `$${v.toFixed(4)}`;
  if (v < 100) return `$${v.toFixed(2)}`;
  return `$${Math.round(v).toLocaleString('en-US')}`;
}

export function fmtMs(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  if (v < 1000) return `${Math.round(v)} ms`;
  if (v < 60_000) return `${(v / 1000).toFixed(1)} s`;
  const m = Math.floor(v / 60_000);
  const s = Math.round((v % 60_000) / 1000);
  return `${m}m ${String(s).padStart(2, '0')}s`;
}

function pct(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : `${Math.round(v * 100)}`;
}

/** Markdown leaderboard — ready to paste into a video description or blog post. */
export function leaderboardMarkdown(board: Leaderboard, manifest?: RunManifest): string {
  const cats = board.categories;
  const lines: string[] = [];
  lines.push(`# Gauntlet leaderboard${manifest ? ` — ${manifest.name}` : ''}`);
  lines.push('');
  lines.push(`Fingerprint \`${board.fingerprint}\` · ${board.tests.length} tests · generated ${board.generatedAt.slice(0, 16).replace('T', ' ')} UTC`);
  if (manifest) lines.push(`Run \`${manifest.id}\` · harness ${manifest.harnessVersion} · protocol ${manifest.settings.protocolVersion} · ${manifest.settings.repeats} repeat(s) per case`);
  lines.push('');
  lines.push(`| # | Model | Index (95% CI) | ${cats.map((c) => c.name).join(' | ')} | 🥇🥈🥉 | Cost | $/pt | Median case |`);
  lines.push(`|---|---|---|${cats.map(() => '---').join('|')}|---|---|---|---|`);
  for (const r of board.rows) {
    const ci = r.indexCi95 ? ` (${r.indexCi95[0].toFixed(1)}–${r.indexCi95[1].toFixed(1)})` : '';
    lines.push(
      `| ${r.rank || '—'} | **${r.label}** <sub>${r.vendor}</sub> | ${r.index === null ? '—' : r.index.toFixed(1)}${ci} | ${cats.map((c) => pct(r.categoryScores[c.id])).join(' | ')} | ${r.medals.gold}/${r.medals.silver}/${r.medals.bronze} | ${fmtCost(r.totals.costUsd)} | ${fmtCost(r.costPerPoint)} | ${fmtMs(r.speed.medianCaseMs)} |`,
    );
  }
  lines.push('');
  lines.push('Index = weighted mean of category scores × 100. Category = mean of its tests; test = mean over cases of the mean over repeats. CIs: cluster bootstrap over cases. Cost excludes judge calls.');
  if (board.staleExcluded) lines.push(`\n${board.staleExcluded} result(s) excluded because their test or model configuration has changed since they were recorded.`);
  return lines.join('\n') + '\n';
}

/** Fixed-width console table. */
export function leaderboardTable(board: Leaderboard): string {
  const header = ['#', 'Model', 'Index', '95% CI', 'Cost', '$/pt', 'Median', 'Tok/s', 'Err'];
  const rows = board.rows.map((r) => [
    r.rank ? String(r.rank) : '—',
    r.label,
    r.index === null ? '—' : r.index.toFixed(1),
    r.indexCi95 ? `${r.indexCi95[0].toFixed(1)}–${r.indexCi95[1].toFixed(1)}` : '—',
    fmtCost(r.totals.costUsd),
    fmtCost(r.costPerPoint),
    fmtMs(r.speed.medianCaseMs),
    r.speed.outputTokensPerSec === null ? '—' : r.speed.outputTokensPerSec.toFixed(0),
    String(r.totals.errors),
  ]);
  const widths = header.map((h, i) => Math.max(h.length, ...rows.map((r) => r[i]!.length)));
  const line = (cells: string[]) => cells.map((c, i) => (i === 1 ? c.padEnd(widths[i]!) : c.padStart(widths[i]!))).join('  ');
  return [line(header), widths.map((w) => '─'.repeat(w)).join('  '), ...rows.map(line)].join('\n');
}
