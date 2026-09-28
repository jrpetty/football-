/**
 * The Head to Head Shorts card: a vertical 1080×1920 "A vs B" result. Like the
 * Studio cards (src/media/cards.ts) it is one self-contained HTML page (no
 * scripts, no external files, system fonts), so it renders identically in
 * headless Chrome (server PNG) and in the browser (preview and PNG fallback).
 */
import { money, pts } from '../media/common.ts';
import { cornerColors, inkOn } from './colors.ts';
import { glyphPath } from './glyphs.ts';
import type { VersusData, VersusFighter, VersusRound } from './types.ts';
import { nameOf, resultHeadline } from './words.ts';

/** Most round rows on the card. */
const MAX_ROWS = 8;

export const VERSUS_CARD = { width: 1080, height: 1920 } as const;

const DISPLAY = `'Arial Black', 'Segoe UI Black', 'Helvetica Neue', Impact, 'Inter', system-ui, sans-serif`;
const TEXT = `'Segoe UI', 'Inter', system-ui, -apple-system, 'Helvetica Neue', Arial, sans-serif`;

const esc = (s: unknown) =>
  String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

/** Scale a font size down for long text. */
function fit(text: string, base: number, maxChars: number, min = base * 0.45): number {
  const len = [...text].length;
  return Math.round(Math.max(min, len > maxChars ? (base * maxChars) / len : base));
}

function slug(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40);
}

export function versusCardFileName(d: VersusData): string {
  return `versus-${slug(nameOf(d.a)) || 'a'}-vs-${slug(nameOf(d.b)) || 'b'}.png`;
}

const CROWN = (size: number) =>
  `<svg width="${size}" height="${Math.round(size * 0.72)}" viewBox="0 0 100 72" aria-hidden="true"><path d="M6 62 L0 14 L28 36 L50 0 L72 36 L100 14 L94 62 Z" fill="#f2c14e" stroke="rgba(0,0,0,.35)" stroke-width="3" stroke-linejoin="round"/><rect x="6" y="60" width="88" height="12" rx="3" fill="#f2c14e" stroke="rgba(0,0,0,.35)" stroke-width="3"/></svg>`;

const glyph = (cat: string, color: string, size: number) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${glyphPath(cat)}"/></svg>`;

function corner(f: VersusFighter, color: string, side: 'a' | 'b', won: boolean, lost: boolean, rounds: number): string {
  const name = nameOf(f);
  return `<div class="corner ${side}${lost ? ' lost' : ''}" style="--c:${color};--ink:${inkOn(color)}">
    <div class="crownslot">${won ? CROWN(92) : ''}</div>
    <div class="rounds disp">${rounds}</div>
    <div class="rw">${rounds === 1 ? 'round' : 'rounds'}</div>
    <div class="nm disp" style="font-size:${fit(name, 58, 11, 30)}px">${esc(name)}</div>
    ${f.vendor && !f.baseline ? `<div class="vd">${esc(f.vendor)}</div>` : ''}
  </div>`;
}

function row(r: VersusRound, ca: string, cb: string, h: number): string {
  const sa = r.a.score ?? 0;
  const sb = r.b.score ?? 0;
  const nameSize = Math.min(34, Math.round(h * 0.33));
  const num = Math.min(44, Math.round(h * 0.42));
  const mark =
    r.winner === 'tie'
      ? '<span class="tie">DRAW</span>'
      : `<span class="tie win" style="background:${r.winner === 'a' ? ca : cb};color:${inkOn(r.winner === 'a' ? ca : cb)}">${r.winner === 'a' ? '◀ WIN' : 'WIN ▶'}</span>`;
  return `<div class="row" style="height:${h}px">
    <div class="rn" style="font-size:${nameSize}px">${glyph(r.category, r.categoryColor, nameSize + 4)}<span class="rt">${esc(r.testName)}</span>${mark}</div>
    <div class="rb">
      <span class="sc disp${r.winner === 'a' ? ' win' : ''}" style="font-size:${num}px;color:${r.winner === 'b' ? 'rgba(255,255,255,.55)' : '#fff'}">${pts(sa)}</span>
      <div class="tr l"><div class="fl${r.winner === 'b' ? ' dim' : ''}" style="width:${sa > 0 ? Math.max(1.5, sa * 100) : 0}%;background:${ca}"></div></div>
      <div class="tr r"><div class="fl${r.winner === 'a' ? ' dim' : ''}" style="width:${sb > 0 ? Math.max(1.5, sb * 100) : 0}%;background:${cb}"></div></div>
      <span class="sc disp${r.winner === 'b' ? ' win' : ''}" style="font-size:${num}px;color:${r.winner === 'a' ? 'rgba(255,255,255,.55)' : '#fff'}">${pts(sb)}</span>
    </div>
  </div>`;
}

function stat(label: string, a: string, b: string, better: 'a' | 'b' | null): string {
  return `<div class="st"><div class="sv${better === 'a' ? ' best' : ''}">${esc(a)}</div><div class="sl">${esc(label)}</div><div class="sv${better === 'b' ? ' best' : ''}">${esc(b)}</div></div>`;
}

const cmp = (a: number | null, b: number | null, higher: boolean): 'a' | 'b' | null => {
  if (a === null || b === null || a === b) return null;
  return (a > b) === higher ? 'a' : 'b';
};

export function renderVersusCardHtml(d: VersusData): string {
  const { width, height } = VERSUS_CARD;
  const col = cornerColors(d.a.color, d.b.color);
  const headline = resultHeadline(d);
  // Up to MAX_ROWS rounds stay readable on a phone; with more, show the most one-sided ones (in round order).
  const keep = new Set([...d.rounds].sort((x, y) => y.margin - x.margin).slice(0, MAX_ROWS).map((r) => r.testId));
  const shown = d.rounds.filter((r) => keep.has(r.testId));
  const more = d.rounds.length - shown.length;
  const twoLine = [...headline].length > 18;
  const rowH = shown.length ? Math.max(64, Math.min(100, Math.floor((twoLine ? 600 : 680) / (shown.length + (more > 0 ? 1 : 0))))) : 0;
  const ta = d.totals.a;
  const tb = d.totals.b;
  const scope = d.scope.kind === 'run' ? d.scope.runName : 'All stored results';
  const body = `
  <div class="bg"></div><div class="wrap">
  <div class="top">
    <div class="brand"><i></i>Gauntlet · Head to Head</div>
    <div class="head disp" style="font-size:${fit(headline, 84, 18, 40)}px">${esc(headline).replace(/(\d+–\d+)$/, '<span style="white-space:nowrap">$1</span>')}</div>
  </div>
  <div class="face">
    ${corner(d.a, col.a, 'a', d.winner === 'a', d.winner === 'b', ta.roundsWon)}
    <div class="vs disp">VS</div>
    ${corner(d.b, col.b, 'b', d.winner === 'b', d.winner === 'a', tb.roundsWon)}
  </div>
  <div class="rounds-list">
    ${shown.length ? shown.map((r) => row(r, col.a, col.b, rowH)).join('') : '<div class="none">These two models have no test in common yet.</div>'}
    ${more > 0 ? `<div class="more">The ${shown.length} most one-sided of ${d.rounds.length} rounds</div>` : ''}
  </div>
  <div class="stats">
    ${stat('Average score', ta.avgScore === null ? '—' : String(Math.round(ta.avgScore)), tb.avgScore === null ? '—' : String(Math.round(tb.avgScore)), cmp(ta.avgScore, tb.avgScore, true))}
    ${stat('Cost for these tests', ta.costUsd > 0 ? money(ta.costUsd) : 'not recorded', tb.costUsd > 0 ? money(tb.costUsd) : 'not recorded', ta.costUsd > 0 && tb.costUsd > 0 ? cmp(ta.costUsd, tb.costUsd, false) : null)}
  </div>
  <div class="foot">Scores out of 100 · a draw is less than ${d.tieMargin} points apart · ${esc(scope)}</div></div>`;

  const css = `
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:${width}px;height:${height}px;overflow:hidden}
body{background:#070a10;color:#fff;font-family:${TEXT};-webkit-font-smoothing:antialiased}
.card{position:relative;width:${width}px;height:${height}px;overflow:hidden}
.bg{position:absolute;inset:0;background:radial-gradient(900px 700px at 0% 22%, ${col.a}55, transparent 70%),radial-gradient(900px 700px at 100% 22%, ${col.b}55, transparent 70%),linear-gradient(180deg,#0b0f18,#05070b)}
.disp{font-family:${DISPLAY};font-weight:900;letter-spacing:-.01em;line-height:.95;text-transform:uppercase}
.wrap{position:absolute;left:60px;right:60px;top:110px;bottom:170px;display:flex;flex-direction:column;gap:26px}
.top{text-align:center;flex:none}
.brand{display:inline-flex;align-items:center;gap:14px;font-family:${DISPLAY};font-weight:900;letter-spacing:.16em;text-transform:uppercase;font-size:26px;color:#f2c14e}
.brand i{display:inline-block;width:14px;height:14px;background:#f2c14e;transform:rotate(45deg);border-radius:2px}
.head{margin-top:22px;text-shadow:0 6px 0 rgba(0,0,0,.35)}
.face{flex:none;height:440px;display:flex;align-items:stretch;margin:0 -10px}
.corner{flex:1;min-width:0;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;padding:18px 22px 26px;border-radius:34px;background:var(--c);color:var(--ink);box-shadow:0 24px 60px -24px rgba(0,0,0,.8)}
.corner.a{clip-path:polygon(0 0,100% 0,92% 100%,0 100%);padding-right:48px}
.corner.b{clip-path:polygon(8% 0,100% 0,100% 100%,0 100%);padding-left:48px}
.corner.lost{filter:saturate(.5) brightness(.6)}
.crownslot{height:62px;flex:none;display:flex;align-items:flex-end}
.rounds{font-size:160px;line-height:.82;flex:none}
.rw{flex:none;font-size:26px;font-weight:800;letter-spacing:.14em;text-transform:uppercase;opacity:.85;margin-top:4px}
.nm{flex:none;margin-top:16px;padding-bottom:2px;text-align:center;max-width:100%;overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.08}
.vd{flex:none;font-size:24px;font-weight:700;opacity:.8;margin-top:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}
.vs{align-self:center;width:118px;height:118px;margin:0 -59px;z-index:2;border-radius:50%;background:#0a0d12;color:#fff;display:grid;place-items:center;font-size:52px;border:7px solid #fff;box-shadow:0 12px 40px rgba(0,0,0,.6);transform:rotate(-6deg)}
.rounds-list{flex:1;min-height:0;display:flex;flex-direction:column;justify-content:center}
.row{flex:none;display:flex;flex-direction:column;justify-content:center;gap:8px;border-bottom:2px solid rgba(255,255,255,.08)}
.rn{display:flex;align-items:center;gap:12px;font-weight:700;color:#e6ebf3;min-width:0}
.rt{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
.tie{margin-left:auto;flex:none;font-size:20px;font-weight:900;letter-spacing:.12em;padding:4px 12px;border-radius:999px;background:rgba(255,255,255,.14);color:#fff}
.rb{display:flex;align-items:center;gap:14px}
.sc{width:84px;text-align:center;flex:none}
.tr{flex:1;height:22px;border-radius:11px;background:rgba(255,255,255,.08);display:flex;overflow:hidden}
.tr.l{justify-content:flex-end}
.fl{height:100%;border-radius:11px}
.fl.dim{opacity:.4}
.none,.more{flex:none;font-size:26px;color:#c3cad6;text-align:center;padding:14px}
.stats{flex:none;display:flex;flex-direction:column;gap:10px}
.st{display:flex;align-items:center;gap:18px;background:rgba(255,255,255,.07);border:2px solid rgba(255,255,255,.12);border-radius:22px;padding:16px 26px}
.sv{flex:1;font-size:40px;font-weight:900;font-family:${DISPLAY};color:rgba(255,255,255,.7)}
.sv:last-child{text-align:right}
.sv.best{color:#fff}
.sv.best:after{content:" ✓";color:#22c55e}
.sl{font-size:24px;font-weight:800;letter-spacing:.12em;text-transform:uppercase;color:#c3cad6;text-align:center}
.foot{flex:none;text-align:center;font-size:22px;color:#9aa4b5;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}`;

  return `<!doctype html><html><head><meta charset="utf-8"><title>Gauntlet head to head</title><style>${css}</style></head><body><div class="card">${body}</div></body></html>`;
}
