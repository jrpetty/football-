/**
 * Thumbnail and Shorts card templates. Each template is a self-contained HTML
 * page at a fixed pixel size (no scripts, no external requests, system fonts
 * only) so it renders identically in headless Chromium (PNG export on the
 * server) and in the browser (live preview, and the in-browser PNG fallback).
 */
import { buildCtx, isBaselineId, pts, rankedOnTest, standings } from './common.ts';
import { HIGHLIGHT_LABELS } from './highlights.ts';
import type { CardContestant, CardData, CardKind, CardSpec, CardStyle, Highlight, StudioInput } from './types.ts';

export const CARD_STYLES: Array<{ id: CardStyle; label: string; hint: string }> = [
  { id: 'versus', label: 'Versus', hint: 'Model colours head to head' },
  { id: 'bold', label: 'Bold', hint: 'Black and yellow, huge numbers' },
  { id: 'clean', label: 'Clean', hint: 'Light, minimal, readable' },
  { id: 'neon', label: 'Neon', hint: 'Dark with glowing accents' },
];

export const CARD_KINDS: Array<{ id: CardKind; label: string; hint: string }> = [
  { id: 'thumbnail', label: 'YouTube thumbnail', hint: '16:9 · 1280×720' },
  { id: 'short-standings', label: 'Final standings', hint: 'Vertical · 1080×1920' },
  { id: 'short-test', label: 'Test result', hint: 'Vertical · 1080×1920' },
  { id: 'short-question', label: 'Question card', hint: 'Vertical · 1080×1920' },
  { id: 'short-highlight', label: 'Highlight card', hint: 'Vertical · 1080×1920' },
];

export function cardSize(kind: CardKind): { width: number; height: number } {
  return kind === 'thumbnail' ? { width: 1280, height: 720 } : { width: 1080, height: 1920 };
}

// ─────────────────────────────── Data ───────────────────────────────

export function cardData(input: StudioInput, highlights: Highlight[]): CardData {
  const ctx = buildCtx(input);
  const cats = new Map([...(input.leaderboard?.categories ?? []), ...input.categories].map((c) => [c.id, c]));
  const st = standings(ctx);
  const rows = input.leaderboard?.rows ?? [];
  const meanAll = (id: string): number | null => {
    const xs = input.results.filter((r) => r.contestantId === id && typeof r.score === 'number').map((r) => r.score as number);
    return xs.length ? (xs.reduce((a, b) => a + b, 0) / xs.length) * 100 : null;
  };
  const contestants: CardContestant[] = input.manifest.contestants.map((c) => {
    const row = rows.find((r) => r.contestantId === c.id);
    return { id: c.id, label: c.label, vendor: c.vendor, color: c.color, score: typeof row?.index === 'number' ? row.index : meanAll(c.id), baseline: ctx.baseline.has(c.id) };
  });
  const rank = new Map(st.map((s, i) => [s.id, i]));
  contestants.sort(
    (a, b) => Number(a.baseline) - Number(b.baseline) || (rank.get(a.id) ?? 99) - (rank.get(b.id) ?? 99) || (b.score ?? -1) - (a.score ?? -1) || a.id.localeCompare(b.id),
  );
  return {
    runId: input.manifest.id,
    runName: input.manifest.name || input.manifest.id,
    contestants,
    tests: ctx.tests.map((t) => ({
      id: t.id,
      name: t.name,
      hook: t.hook,
      categoryName: cats.get(t.category)?.name ?? t.category,
      categoryColor: cats.get(t.category)?.color ?? '#64748b',
      results: rankedOnTest(ctx, t.id, { includeBaseline: true }).map((r) => {
        const c = ctx.byId.get(r.id)!;
        return { id: r.id, label: c.label, color: c.color, score: Number(pts(r.score)) };
      }),
    })),
    highlights: highlights.slice(0, 12),
  };
}

/** The batch set used by "Export all": every thumbnail style, standings, one card per test, the top highlights. */
export function defaultCards(data: CardData, style: CardStyle = 'versus', headline?: string): CardSpec[] {
  const out: CardSpec[] = CARD_STYLES.map((s) => ({ kind: 'thumbnail' as const, style: s.id, headline }));
  out.push({ kind: 'short-standings', style });
  for (const t of data.tests) if (t.results.length) out.push({ kind: 'short-test', style, testId: t.id });
  for (const t of data.tests) if (t.results.length && t.hook) out.push({ kind: 'short-question', style, testId: t.id });
  for (const h of data.highlights.slice(0, 5)) out.push({ kind: 'short-highlight', style, highlightId: h.id });
  return out;
}

/** Windows-safe file name for a card. */
export function cardFileName(spec: CardSpec, i?: number): string {
  const subject = spec.testId ?? spec.highlightId ?? '';
  const slug = `${spec.kind}-${spec.style}${subject ? `-${subject}` : ''}`
    .toLowerCase()
    .replace(/[^a-z0-9.-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 90);
  return `${i !== undefined ? `${String(i + 1).padStart(2, '0')}-` : ''}${slug}.png`;
}

// ─────────────────────────────── Rendering helpers ───────────────────────────────

const esc = (s: unknown) =>
  String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

function hexRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h.padEnd(6, '0').slice(0, 6);
  const n = parseInt(full, 16);
  return Number.isNaN(n) ? [100, 116, 139] : [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** Black or white, whichever reads better on the colour. */
function inkOn(hex: string): string {
  const [r, g, b] = hexRgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  const L = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  return L > 0.36 ? '#0a0d12' : '#ffffff';
}

function shade(hex: string, f: number): string {
  const [r, g, b] = hexRgb(hex);
  const m = (v: number) => Math.round(Math.max(0, Math.min(255, f < 0 ? v * (1 + f) : v + (255 - v) * f)));
  return `rgb(${m(r)}, ${m(g)}, ${m(b)})`;
}

/** Scale a font size down for long text. */
function fit(text: string, base: number, maxChars: number, min = base * 0.4): number {
  const len = [...text].length;
  return Math.round(Math.max(min, len > maxChars ? (base * maxChars) / len : base));
}

/** Short name for big type: "Claude Opus 5" → "CLAUDE", "GPT-5.6 Sol" → "GPT-5.6". */
export function shortName(label: string): string {
  return (label.split(/\s+/)[0] ?? label).toUpperCase();
}

const CROWN = (size: number, color = '#f2c14e') =>
  `<svg class="crown" width="${size}" height="${Math.round(size * 0.72)}" viewBox="0 0 100 72" aria-hidden="true"><path d="M6 62 L0 14 L28 36 L50 0 L72 36 L100 14 L94 62 Z" fill="${color}" stroke="rgba(0,0,0,.35)" stroke-width="3" stroke-linejoin="round"/><rect x="6" y="60" width="88" height="12" rx="3" fill="${color}" stroke="rgba(0,0,0,.35)" stroke-width="3"/><circle cx="50" cy="40" r="6" fill="#fff" opacity=".85"/></svg>`;

interface Theme {
  bg: string;
  fg: string;
  muted: string;
  accent: string;
  panel: string;
  line: string;
  glow: string;
  display: string;
}

const DISPLAY = `'Arial Black', 'Segoe UI Black', 'Helvetica Neue', Impact, 'Inter', system-ui, sans-serif`;
const TEXT = `'Segoe UI', 'Inter', system-ui, -apple-system, 'Helvetica Neue', Arial, sans-serif`;

function theme(style: CardStyle, a?: string, b?: string): Theme {
  switch (style) {
    case 'bold':
      return { bg: '#0a0a0a', fg: '#ffffff', muted: '#bdbdbd', accent: '#ffd60a', panel: '#1b1b1b', line: '#2e2e2e', glow: 'none', display: DISPLAY };
    case 'clean':
      return { bg: '#f4f6fa', fg: '#0b1220', muted: '#4a5568', accent: '#0891b2', panel: '#ffffff', line: '#dde3ec', glow: 'none', display: DISPLAY };
    case 'neon':
      return { bg: '#05060f', fg: '#f5f7ff', muted: '#9aa3c7', accent: '#22d3ee', panel: 'rgba(255,255,255,.05)', line: 'rgba(34,211,238,.35)', glow: '0 0 18px rgba(34,211,238,.75), 0 0 42px rgba(167,139,250,.55)', display: DISPLAY };
    default:
      return {
        bg: `linear-gradient(135deg, ${shade(a ?? '#1e293b', -0.55)} 0%, #070a10 50%, ${shade(b ?? '#1e293b', -0.55)} 100%)`,
        fg: '#ffffff',
        muted: '#c3cad6',
        accent: '#f2c14e',
        panel: 'rgba(255,255,255,.07)',
        line: 'rgba(255,255,255,.14)',
        glow: 'none',
        display: DISPLAY,
      };
  }
}

function page(width: number, height: number, t: Theme, css: string, body: string): string {
  return `<!doctype html><html><head><meta charset="utf-8"><title>Gauntlet card</title><style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:${width}px;height:${height}px;overflow:hidden}
body{background:${t.bg};color:${t.fg};font-family:${TEXT};-webkit-font-smoothing:antialiased}
.card{position:relative;width:${width}px;height:${height}px;overflow:hidden}
.disp{font-family:${t.display};font-weight:900;letter-spacing:-.01em;line-height:.95;text-transform:uppercase}
.muted{color:${t.muted}}
.brand{position:absolute;display:flex;align-items:center;gap:12px;font-family:${t.display};font-weight:900;letter-spacing:.14em;text-transform:uppercase}
.brand i{display:inline-block;width:14px;height:14px;background:${t.accent};transform:rotate(45deg);border-radius:2px}
.glow{text-shadow:${t.glow}}
${css}
</style></head><body><div class="card">${body}</div></body></html>`;
}

// ─────────────────────────────── Templates ───────────────────────────────

function thumbnail(data: CardData, spec: CardSpec): string {
  const { width, height } = cardSize('thumbnail');
  const comps = data.contestants.filter((c) => !c.baseline);
  const a = comps.find((c) => c.id === spec.a) ?? comps[0] ?? data.contestants[0];
  const b = comps.find((c) => c.id === spec.b && c.id !== a?.id) ?? comps.find((c) => c.id !== a?.id) ?? data.contestants.find((c) => c.id !== a?.id);
  const t = theme(spec.style, a?.color, b?.color);
  if (!a) return page(width, height, t, '', `<div class="disp" style="font-size:90px;padding:80px">No results yet</div>`);
  const sa = a.score === null ? '—' : String(Math.round(a.score));
  const sb = b?.score === null || !b ? '—' : String(Math.round(b.score));
  const aWins = !b || (a.score ?? -1) >= (b.score ?? -1);
  const headline = (spec.headline?.trim() || (b ? `${shortName(a.label)} vs ${shortName(b.label)}` : shortName(a.label))).toUpperCase();
  const hSize = fit(headline, 96, 20, 44);

  if (spec.style === 'versus' && b) {
    const block = (c: CardContestant, score: string, side: 'l' | 'r', win: boolean) => `
      <div class="blk ${side}${win ? ' win' : ' lose'}" style="background:${c.color};color:${inkOn(c.color)}">
        <div class="inner">
          ${win ? CROWN(118) : '<div style="height:85px"></div>'}
          <div class="score disp">${esc(score)}</div>
          <div class="name disp" style="font-size:${fit(c.label, 50, 14, 26)}px">${esc(c.label)}</div>
        </div>
      </div>`;
    return page(
      width,
      height,
      t,
      `.blk{position:absolute;top:0;bottom:0;width:58%;display:flex;align-items:center}
       .blk.l{left:0;clip-path:polygon(0 0,100% 0,82% 100%,0 100%);justify-content:flex-start;padding-left:70px}
       .blk.r{right:0;clip-path:polygon(18% 0,100% 0,100% 100%,0 100%);justify-content:flex-end;padding-right:70px}
       .blk.lose{filter:saturate(.55) brightness(.62)}
       .inner{display:flex;flex-direction:column;align-items:center;gap:6px;margin-top:70px}
       .score{font-size:250px;line-height:.85;text-shadow:0 10px 0 rgba(0,0,0,.28)}
       .name{max-width:430px;text-align:center;line-height:1}
       .vs{position:absolute;left:50%;top:56%;transform:translate(-50%,-50%) rotate(-6deg);width:150px;height:150px;border-radius:50%;background:#0a0d12;color:#fff;display:grid;place-items:center;font-size:72px;border:8px solid #fff;box-shadow:0 12px 40px rgba(0,0,0,.6)}
       .head{position:absolute;left:0;right:0;top:0;padding:26px 40px;text-align:center;background:linear-gradient(180deg,rgba(0,0,0,.78),rgba(0,0,0,0));color:#fff;text-shadow:0 4px 0 rgba(0,0,0,.5)}
       .brand{right:34px;bottom:24px;font-size:20px;color:#fff;text-shadow:0 2px 6px rgba(0,0,0,.6)}`,
      `${block(a, sa, 'l', aWins)}${block(b, sb, 'r', !aWins)}
       <div class="vs disp">VS</div>
       <div class="head disp" style="font-size:${hSize}px">${esc(headline)}</div>
       <div class="brand"><i></i>Gauntlet</div>`,
    );
  }

  // Bold / clean / neon: headline on top, two score columns with colour bars.
  const col = (c: CardContestant, score: string, win: boolean) => `
    <div class="col${win ? ' win' : ''}">
      <div class="crownslot">${win ? CROWN(110, spec.style === 'clean' ? '#e0a100' : '#f2c14e') : ''}</div>
      <div class="num disp${spec.style === 'neon' ? ' glow' : ''}" style="color:${spec.style === 'bold' && win ? t.accent : spec.style === 'neon' ? c.color : t.fg}">${esc(score)}</div>
      <div class="bar" style="background:${c.color}"></div>
      <div class="lbl disp" style="font-size:${fit(c.label, 46, 15, 24)}px">${esc(c.label)}</div>
    </div>`;
  return page(
    width,
    height,
    t,
    `.head{position:absolute;top:44px;left:60px;right:60px;text-align:center}
     .row{position:absolute;left:60px;right:60px;top:200px;bottom:70px;display:flex;align-items:flex-end;justify-content:center;gap:40px}
     .col{flex:1;display:flex;flex-direction:column;align-items:center;gap:14px;${spec.style === 'clean' ? 'background:#fff;border:2px solid #dde3ec;border-radius:28px;padding:18px 20px 26px;box-shadow:0 18px 40px -20px rgba(15,23,42,.35);' : ''}}
     .col:not(.win){opacity:${spec.style === 'clean' ? '.8' : '.72'}}
     .crownslot{height:80px;display:flex;align-items:flex-end}
     .num{font-size:220px;line-height:.82}
     .bar{width:78%;height:22px;border-radius:11px}
     .lbl{text-align:center;max-width:100%}
     .mid{align-self:center;font-size:64px;color:${t.muted};margin-bottom:170px}
     .brand{right:40px;bottom:22px;font-size:20px;color:${t.muted}}
     .accentline{position:absolute;left:0;right:0;bottom:0;height:10px;background:${spec.style === 'bold' ? t.accent : `linear-gradient(90deg, ${a.color}, ${b?.color ?? a.color})`}}`,
    `<div class="head disp${spec.style === 'neon' ? ' glow' : ''}" style="font-size:${hSize}px;color:${spec.style === 'bold' ? t.accent : t.fg}">${esc(headline)}</div>
     <div class="row">${col(a, sa, aWins)}${b ? `<div class="mid disp">VS</div>${col(b, sb, !aWins)}` : ''}</div>
     <div class="brand"><i></i>Gauntlet</div><div class="accentline"></div>`,
  );
}

function shortShell(t: Theme, style: CardStyle, eyebrow: string, title: string, inner: string, css: string, titleColor?: string): string {
  const { width, height } = cardSize('short-standings');
  const tSize = fit(title, 112, 14, 56);
  return page(
    width,
    height,
    t,
    `.col{position:absolute;left:80px;right:80px;top:140px;bottom:300px;display:flex;flex-direction:column;justify-content:center;gap:64px}
     .top{position:relative;padding-bottom:40px}
     .top:after{content:"";position:absolute;left:0;bottom:0;width:140px;height:10px;border-radius:5px;background:${t.accent}}
     .eyebrow{font-size:34px;font-weight:800;letter-spacing:.2em;text-transform:uppercase;color:${t.accent};margin-bottom:26px}
     .title{font-size:${tSize}px;${titleColor ? `color:${titleColor};` : ''}}
     .body{display:flex;flex-direction:column;gap:28px}
     .brand{left:80px;bottom:150px;font-size:30px;color:${t.muted}}
     .panel{background:${t.panel};border:2px solid ${t.line};border-radius:32px}
     ${style === 'clean' ? '.panel{box-shadow:0 20px 50px -28px rgba(15,23,42,.35)}' : ''}
     ${css}`,
    `<div class="col"><div class="top"><div class="eyebrow">${esc(eyebrow)}</div><div class="title disp${style === 'neon' ? ' glow' : ''}">${esc(title)}</div></div>
     <div class="body">${inner}</div></div>
     <div class="brand"><i></i>Gauntlet · AI Benchmark Lab</div>`,
  );
}

function barRows(t: Theme, rows: Array<{ label: string; color: string; score: number | null; crown?: boolean; dim?: boolean; place?: number }>, big: boolean): string {
  const max = 100;
  return rows
    .map(
      (r) => `<div class="brow panel${r.dim ? ' dim' : ''}">
        ${r.place !== undefined ? `<div class="place disp">${r.place}</div>` : ''}
        <div class="bmain">
          <div class="bname" style="font-size:${fit(r.label, big ? 48 : 42, 18, 28)}px">${esc(r.label)}${r.crown ? ` ${CROWN(big ? 46 : 40)}` : ''}</div>
          <div class="btrack"><div class="bfill" style="width:${r.score === null ? 0 : Math.max(2, (r.score / max) * 100)}%;background:${r.color}"></div></div>
        </div>
        <div class="bscore disp" style="color:${t.fg}">${r.score === null ? '—' : Math.round(r.score)}</div>
      </div>`,
    )
    .join('');
}

const BAR_CSS = (t: Theme) => `.brow{display:flex;align-items:center;gap:26px;padding:24px 30px}
  .brow.dim{opacity:.55}
  .place{width:64px;font-size:56px;color:${t.muted};text-align:center}
  .bmain{flex:1;min-width:0;display:flex;flex-direction:column;gap:14px}
  .bname{font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;display:flex;align-items:center;gap:12px}
  .bname .crown{flex:none}
  .btrack{height:22px;border-radius:11px;background:${t.line};overflow:hidden}
  .bfill{height:100%;border-radius:11px}
  .bscore{font-size:78px;min-width:150px;text-align:right}`;

function shortStandings(data: CardData, spec: CardSpec): string {
  const comps = data.contestants.filter((c) => !c.baseline).slice(0, 7);
  const t = theme(spec.style, comps[0]?.color, comps[1]?.color);
  const base = data.contestants.find((c) => c.baseline);
  const rows = comps.map((c, i) => ({ label: c.label, color: c.color, score: c.score, crown: i === 0, place: i + 1 }));
  const extra = base ? barRows(t, [{ label: base.label, color: '#71717a', score: base.score, dim: true }], false) : '';
  return shortShell(t, spec.style, data.runName, spec.headline?.trim() || 'Final standings', barRows(t, rows, comps.length <= 4) + extra, BAR_CSS(t));
}

function shortTest(data: CardData, spec: CardSpec): string {
  const test = data.tests.find((x) => x.id === spec.testId) ?? data.tests[0];
  const t = theme(spec.style, test?.results[0]?.color, test?.results[1]?.color);
  if (!test) return shortShell(t, spec.style, data.runName, 'No tests', '', '');
  const rows = test.results.slice(0, 7).map((r, i) => ({ label: r.label, color: r.color, score: r.score, crown: i === 0 && r.score > (test.results[1]?.score ?? -1), place: i + 1, dim: isBaselineId({ id: r.id, label: r.label }) }));
  const hook = test.hook ? `<div class="hook muted" style="font-size:${fit(test.hook, 44, 70, 30)}px">“${esc(test.hook)}”</div>` : '';
  return shortShell(
    t,
    spec.style,
    test.categoryName,
    spec.headline?.trim() || test.name,
    hook + barRows(t, rows, rows.length <= 4),
    `${BAR_CSS(t)} .hook{font-weight:600;line-height:1.3;margin-bottom:18px} .eyebrow{color:${test.categoryColor}}`,
  );
}

function shortQuestion(data: CardData, spec: CardSpec): string {
  const test = data.tests.find((x) => x.id === spec.testId) ?? data.tests.find((x) => x.hook) ?? data.tests[0];
  const t = theme(spec.style, test?.results[0]?.color, test?.results[1]?.color);
  if (!test) return shortShell(t, spec.style, data.runName, 'No tests', '', '');
  const q = spec.headline?.trim() || test.hook || `Can AI beat ${test.name}?`;
  const res = test.results.slice(0, 6);
  const chips = res
    .map((r) => {
      const pass = r.score >= 50;
      return `<div class="qrow panel">
        <div class="mark" style="background:${pass ? '#16a34a' : '#dc2626'}">${pass ? '✓' : '✗'}</div>
        <div class="qname" style="font-size:${fit(r.label, 48, 18, 28)}px"><span class="dot" style="background:${r.color}"></span>${esc(r.label)}</div>
        <div class="qscore disp">${r.score}</div>
      </div>`;
    })
    .join('');
  const { width, height } = cardSize('short-question');
  return page(
    width,
    height,
    t,
    `.col{position:absolute;left:80px;right:80px;top:140px;bottom:300px;display:flex;flex-direction:column;justify-content:center;gap:80px}
     .eyebrow{font-size:34px;font-weight:800;letter-spacing:.2em;text-transform:uppercase;color:${test.categoryColor};margin-bottom:30px}
     .qt{font-size:${fit(q, 104, 34, 58)}px;line-height:1.02}
     .ans{display:flex;flex-direction:column;gap:22px}
     .sub{font-size:38px;font-weight:700;color:${t.muted};margin-bottom:6px}
     .panel{background:${t.panel};border:2px solid ${t.line};border-radius:28px}
     .qrow{display:flex;align-items:center;gap:26px;padding:20px 28px}
     .mark{width:74px;height:74px;border-radius:50%;display:grid;place-items:center;color:#fff;font-size:46px;font-weight:900;flex:none}
     .qname{flex:1;min-width:0;font-weight:800;display:flex;align-items:center;gap:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
     .dot{width:22px;height:22px;border-radius:50%;flex:none}
     .qscore{font-size:70px}
     .brand{left:80px;bottom:150px;font-size:30px;color:${t.muted}}`,
    `<div class="col"><div class="q"><div class="eyebrow">${esc(test.name)}</div><div class="qt disp${spec.style === 'neon' ? ' glow' : ''}">${esc(q)}</div></div>
     <div class="ans"><div class="sub">We put it to ${res.length} AI ${res.length === 1 ? 'model' : 'models'}. Score out of 100:</div>${chips}</div></div>
     <div class="brand"><i></i>Gauntlet · AI Benchmark Lab</div>`,
  );
}

function shortHighlight(data: CardData, spec: CardSpec): string {
  const h = data.highlights.find((x) => x.id === spec.highlightId) ?? data.highlights[0];
  const who = h ? data.contestants.find((c) => c.id === h.contestantIds[0]) : undefined;
  const t = theme(spec.style, who?.color, data.contestants.find((c) => c.id === h?.contestantIds[1])?.color);
  if (!h) return shortShell(t, spec.style, data.runName, 'No highlights yet', '', '');
  const tiles = h.evidence
    .slice(0, 4)
    .map((e) => `<div class="tile panel"><div class="tv disp" style="font-size:${fit(e.display, 92, 6, 48)}px">${esc(e.display)}</div><div class="tl muted">${esc(e.label)}</div></div>`)
    .join('');
  return shortShell(
    t,
    spec.style,
    HIGHLIGHT_LABELS[h.type],
    spec.headline?.trim() || h.title,
    `<div class="why" style="font-size:${fit(h.why, 44, 150, 30)}px">${esc(h.why)}</div><div class="tiles">${tiles}</div>`,
    `.why{font-weight:600;line-height:1.32}
     .tiles{display:grid;grid-template-columns:1fr 1fr;gap:22px;margin-top:12px}
     .tile{padding:26px 28px;display:flex;flex-direction:column;gap:10px;min-height:190px;justify-content:center}
     .tl{font-size:28px;font-weight:700;line-height:1.2}
     .title{font-size:${fit(spec.headline?.trim() || h.title, 92, 28, 54)}px !important;line-height:1.02}`,
    spec.style === 'bold' ? t.accent : undefined,
  );
}

export function renderCardHtml(data: CardData, spec: CardSpec): string {
  switch (spec.kind) {
    case 'thumbnail':
      return thumbnail(data, spec);
    case 'short-standings':
      return shortStandings(data, spec);
    case 'short-test':
      return shortTest(data, spec);
    case 'short-question':
      return shortQuestion(data, spec);
    case 'short-highlight':
      return shortHighlight(data, spec);
  }
}

export function cardTitle(data: CardData, spec: CardSpec): string {
  const kind = CARD_KINDS.find((k) => k.id === spec.kind)?.label ?? spec.kind;
  const subject =
    spec.kind === 'short-test' || spec.kind === 'short-question'
      ? data.tests.find((t) => t.id === spec.testId)?.name
      : spec.kind === 'short-highlight'
        ? data.highlights.find((h) => h.id === spec.highlightId)?.title
        : undefined;
  return subject ? `${kind}: ${subject}` : `${kind} (${spec.style})`;
}
