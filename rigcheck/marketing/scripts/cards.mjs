/**
 * Renders the Instagram cards from the generated JSON.
 *
 * Every card is a chart or a hero figure, never a table, and every number on
 * every card arrives through a data expression — verify.mjs scans this file's
 * templates for typed-in figures and fails on any. That rule exists because
 * the previous version of the silent-tax card said "40%" in 78-point type
 * while the data underneath it said 46.
 *
 * Form follows the data's job:
 *   cover        price → frame rate is a curve, so it is drawn as one, knee marked
 *   builds       six games against a refresh target: horizontal bars, target rule
 *   bottleneck   the story is that the SHAPE changes between cards, so: slope
 *                chart indexed to the slowest chip, and a two-panel version
 *                where bunched-vs-fanned is visible from across the room
 *   silent tax   before → after per game: dumbbell, one hue in two shades
 *   VRAM         two versions of one die at three resolutions: paired columns
 *   still good   four cards × three games: small multiples with a 60fps rule
 *   hero-*       one derived figure, one line, nothing else
 *
 * Marks follow the house rules: bars ≤ 24px with a 4px rounded data-end and a
 * square base, 2px lines, ≥ 8px markers with a 2px surface ring, hairline
 * solid grid, and text in ink — never in a series colour. Identity comes from
 * the mark beside the text.
 */
import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';

const builds = JSON.parse(readFileSync('marketing/builds.json', 'utf8'));
const bottleneck = JSON.parse(readFileSync('marketing/bottleneck.json', 'utf8'));
const pillars = JSON.parse(readFileSync('marketing/pillars.json', 'utf8'));
/** Card ages are stated on the card, so they have to move with the calendar. */
const NOW = new Date().getFullYear();
/** The one real-world constant a card is allowed to carry: the 60fps line. */
const SIXTY = 60;
const fonts = readFileSync('src/ui/fonts.css', 'utf8');

/* Kept in step with src/ui/theme.css by hand — the cards render outside the app,
   so they cannot read its custom properties. `cat` is the validated categorical
   set (adjacent-pair CVD ΔE 8.4 worst, normal-vision 19.3, all clearing 3:1 on
   the card surface) and `seq` the single-hue magnitude ramp. If the app's
   palette moves, move these with it or the posts stop looking like the tool. */
const P = {
  bg: '#080b11', surface: '#111823', surface2: '#1a2331', line: '#2c3849', lineStrong: '#3e4d61',
  ink: '#eaf0f7', muted: '#a3b4c6', faint: '#90a0b2', accent: '#5eb3f5',
  good: '#2fa96b', spec: '#c99200',
  gpu: '#3987e5', cpu: '#d95926', balanced: '#199e70', cap: '#c98500', vram: '#d55181',
  cat: ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181'],
  seq: ['#16324d', '#1c4d78', '#2a6ca8', '#3d8fd0', '#6fb4e8'],
};
const LIM = {
  gpu: { c: P.gpu, l: 'gpu-bound' }, cpu: { c: P.cpu, l: 'cpu-bound' },
  balanced: { c: P.balanced, l: 'balanced' }, 'engine-cap': { c: P.cap, l: 'engine cap' },
  vram: { c: P.vram, l: 'vram wall' },
};
/* Colour follows the entity. Two panels list the same games in different
   orders, and a game must be the same hue on both or the comparison is noise. */
const GAME_SLOT = ["Baldur's Gate 3", 'Total War Warhammer III', 'Counter-Strike 2', 'Cyberpunk 2077', 'Fortnite'];
const gameColor = (g) => { const i = GAME_SLOT.indexOf(g); return i < 0 ? P.faint : P.cat[i]; };

const css = `
${fonts}
*{margin:0;padding:0;box-sizing:border-box}
body{width:1080px;height:1350px;background:${P.bg};color:${P.ink};
  font-family:'IBM Plex Sans',system-ui,sans-serif;-webkit-font-smoothing:antialiased;
  display:flex;flex-direction:column;padding:60px 60px 48px;position:relative;overflow:hidden}
body::after{content:'';position:absolute;inset:0;
  background:radial-gradient(940px 640px at 78% -8%, rgba(94,179,245,.16), transparent 62%),radial-gradient(760px 560px at -8% 104%, rgba(213,81,129,.07), transparent 58%);pointer-events:none}
.brandrow{display:flex;align-items:center;gap:12px;margin-bottom:38px}
.mark{width:32px;height:32px;flex:none}
.wordmark{font-family:'IBM Plex Mono',monospace;font-size:19px;font-weight:500;letter-spacing:.24em}
.kicker{margin-left:auto;font-family:'IBM Plex Mono',monospace;font-size:13px;letter-spacing:.18em;
  text-transform:uppercase;color:${P.faint}}
h1{font-size:78px;line-height:.98;font-weight:600;letter-spacing:-.028em;margin-bottom:16px}
h1.tight{font-size:66px}
h1 em{font-style:normal;color:${P.accent}}
.sub{font-size:23px;line-height:1.42;color:${P.muted};margin-bottom:30px;max-width:880px}
.chart{margin-bottom:0}
.chart svg{display:block}
.panels{display:flex;gap:20px;margin-bottom:0}
.panel{flex:1}
.panel .t{font-size:24px;font-weight:600;margin-bottom:6px;letter-spacing:-.01em}
.panel .d{font-family:'IBM Plex Mono',monospace;font-size:13px;letter-spacing:.12em;text-transform:uppercase;color:${P.faint};margin-bottom:10px}
.legend{display:flex;flex-wrap:wrap;gap:10px 22px;margin-top:18px;padding:0 2px}
.legend div{display:flex;align-items:center;gap:8px;font-size:15px;color:${P.muted}}
.legend i{display:inline-block;width:14px;height:14px;border-radius:3px;flex:none}
.legend i.line{height:3px;border-radius:2px;width:22px}
.legend i.tick{width:2px;height:16px;border-radius:1px;background:${P.ink};opacity:.8}
.foot{display:flex;align-items:flex-end;gap:20px;margin-top:auto;padding-top:22px;border-top:1px solid ${P.line}}
.foot .note{flex:1;font-size:16px;line-height:1.5;color:${P.faint}}
.foot .note b{color:${P.spec};font-weight:600}
.why{display:flex;gap:16px;margin-top:30px}
.why div{flex:1;border-left:2px solid ${P.accent}66;padding:4px 0 4px 16px}
.why .t{font-size:19px;font-weight:600;margin-bottom:6px}
.why .b{font-size:16px;line-height:1.48;color:${P.faint}}
.card-note{font-size:20px;line-height:1.5;color:${P.muted};max-width:880px;margin-bottom:26px}
/* What this shows: one derived sentence, printed on the card and repeated
   word-for-word in the caption file, so an image never travels without its
   subject. The accent rail marks it as the thing to read first after the title. */
.shows{display:flex;gap:18px;align-items:flex-start;font-size:19px;line-height:1.42;color:${P.ink};
  margin:0 0 24px;padding:14px 18px;border:1px solid ${P.line};border-left:3px solid ${P.accent};
  border-radius:8px;background:${P.surface}}
.shows span{font-family:'IBM Plex Mono',monospace;font-size:11.5px;letter-spacing:.16em;text-transform:uppercase;
  color:${P.accent};flex:none;padding-top:4px}
.howto{display:flex;gap:18px;align-items:flex-start;font-size:16px;line-height:1.45;color:${P.faint};margin-top:16px}
.howto span{font-family:'IBM Plex Mono',monospace;font-size:11px;letter-spacing:.16em;text-transform:uppercase;
  color:${P.faint};flex:none;padding-top:3px;white-space:nowrap}
/* Hero figures: the same sans as everything else, proportional digits. Mono at
   this size gives every digit the width of a zero and "121" falls apart. */
.hero{display:flex;flex-direction:column;gap:30px;margin:26px 0 0}
.hero .fig{display:flex;align-items:baseline;gap:34px}
.hero .v{font-size:250px;font-weight:700;letter-spacing:-.06em;line-height:.9;font-variant-numeric:normal;flex:none}
.hero .v.two{font-size:190px}
.hero .fig .l{margin-top:0;flex:1}
.hero .v small{font-size:64px;font-weight:600;letter-spacing:-.02em;margin-left:8px;color:${P.muted}}
.hero .l{font-size:26px;color:${P.muted};margin-top:14px;line-height:1.35}
.hero .l b{color:${P.ink};font-weight:600}
.hero .swatch{display:inline-block;width:18px;height:18px;border-radius:4px;vertical-align:-2px;margin-right:10px}
.vs{font-family:'IBM Plex Mono',monospace;font-size:15px;letter-spacing:.18em;text-transform:uppercase;color:${P.faint};margin-left:12px}
.strip{display:flex;gap:26px;margin-bottom:26px}
.strip div{font-family:'IBM Plex Mono',monospace;font-size:15px;color:${P.faint}}
.strip b{color:${P.muted};font-weight:400}
`;

const MARK = `<svg class="mark" viewBox="0 0 20 20">
  <circle cx="10" cy="10" r="8.1" fill="none" stroke="${P.ink}" stroke-opacity=".5" stroke-width="1.5"/>
  <path d="M10 1.9v2.3M18.1 10h-2.3M10 18.1v-2.3M1.9 10h2.3" stroke="${P.ink}" stroke-opacity=".5" stroke-width="1.3" stroke-linecap="round"/>
  <path d="M10 10l3.4-3.4" stroke="${P.accent}" stroke-width="1.8" stroke-linecap="round"/>
  <circle cx="10" cy="10" r="1.7" fill="${P.accent}"/></svg>`;

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');
const fmt = (n) => Number(n).toLocaleString('en-GB');
const pct = (n) => `${n > 0 ? '+' : ''}${n}%`;
const brand = (row) => `<div class="brandrow">${MARK}<span class="wordmark">RIGCHECK</span><span class="kicker">${esc(row)}</span></div>`;
const foot = (html) => `<div class="foot"><div class="note"><b>Modelled, not measured.</b> ${html}</div></div>`;
const shortCpu = (c) => c.replace(/^Ryzen (\d) /, 'R$1 ');
const shows = (t) => `<div class="shows"><span>What this shows</span><div>${esc(t)}</div></div>`;
const howto = (t) => `<div class="howto"><span>How to read it</span><div>${esc(t)}</div></div>`;

/* ---- SVG primitives ------------------------------------------------------ */
const SVG = (w, h, inner) => `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${inner}</svg>`;
const T = (x, y, s, o = {}) =>
  `<text x="${x}" y="${y}" font-family="${o.mono ? "'IBM Plex Mono',monospace" : "'IBM Plex Sans',sans-serif"}" font-size="${o.size ?? 15}" font-weight="${o.weight ?? 400}" fill="${o.fill ?? P.muted}" text-anchor="${o.anchor ?? 'start'}" dominant-baseline="${o.base ?? 'middle'}" letter-spacing="${o.ls ?? 0}">${esc(s)}</text>`;
const hair = (x1, y1, x2, y2, c = P.line) => `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${c}" stroke-width="1"/>`;
/** A bar with a 4px rounded data-end and a square base. `dir` = 'right' | 'up'. */
function bar(x, y, w, h, fill, dir = 'right', r = 4, extra = '') {
  if (dir === 'right') {
    const rr = Math.min(r, w / 2, h / 2);
    return `<path d="M${x} ${y}h${w - rr}a${rr} ${rr} 0 0 1 ${rr} ${rr}v${h - 2 * rr}a${rr} ${rr} 0 0 1 -${rr} ${rr}h-${w - rr}z" fill="${fill}" ${extra}/>`;
  }
  const rr = Math.min(r, w / 2, h / 2);
  return `<path d="M${x} ${y + h}v-${h - rr}a${rr} ${rr} 0 0 1 ${rr} -${rr}h${w - 2 * rr}a${rr} ${rr} 0 0 1 ${rr} ${rr}v${h - rr}z" fill="${fill}" ${extra}/>`;
}
/** Marker with a 2px surface ring so it survives crossing a line. */
const dot = (x, y, fill, r = 6) => `<circle cx="${x}" cy="${y}" r="${r + 2}" fill="${P.bg}"/><circle cx="${x}" cy="${y}" r="${r}" fill="${fill}"/>`;
/** Push labels apart to a minimum gap, keeping them inside [lo, hi]. */
function spread(items, gap, lo, hi) {
  const out = items.map((i) => ({ ...i, ly: i.y })).sort((a, b) => a.y - b.y);
  for (let k = 1; k < out.length; k++) if (out[k].ly - out[k - 1].ly < gap) out[k].ly = out[k - 1].ly + gap;
  const over = out.length ? out[out.length - 1].ly - hi : 0;
  if (over > 0) for (const o of out) o.ly -= over;
  for (let k = out.length - 2; k >= 0; k--) if (out[k + 1].ly - out[k].ly < gap) out[k].ly = out[k + 1].ly - gap;
  const under = out.length ? lo - out[0].ly : 0;
  if (under > 0) for (const o of out) o.ly += under;
  return out;
}

/* ---- cover: the price curve ---------------------------------------------- */
function curve(pts, { w, h, big = true }) {
  const padL = big ? 78 : 10, padR = big ? 60 : 10, padT = big ? 44 : 10, padB = big ? 86 : 10;
  const xmax = Math.max(...pts.map((p) => p.x)) * 1.06, ymax = Math.max(...pts.map((p) => p.y)) * 1.22;
  const X = (v) => padL + (v / xmax) * (w - padL - padR), Y = (v) => padT + (1 - v / ymax) * (h - padT - padB);
  let s = '';
  if (big) {
    for (let g = 0; g <= ymax; g += 40) { s += hair(padL, Y(g), w - padR, Y(g)); s += T(padL - 12, Y(g), `${g}`, { mono: true, size: 14, fill: P.faint, anchor: 'end' }); }
    s += T(padL - 12, padT - 22, 'fps', { mono: true, size: 12, fill: P.faint, anchor: 'end', ls: 1 });
  }
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${X(p.x)} ${Y(p.y)}`).join('');
  s += `<path d="${line}L${X(pts[pts.length - 1].x)} ${Y(0)}L${X(pts[0].x)} ${Y(0)}Z" fill="${P.seq[3]}" opacity=".1"/>`;
  s += `<path d="${line}" fill="none" stroke="${P.seq[3]}" stroke-width="${big ? 2.5 : 2}" stroke-linejoin="round" stroke-linecap="round"/>`;
  // The knee: the point after which each pound buys the least. Computed, not chosen.
  const slopes = pts.slice(0, -1).map((p, i) => (pts[i + 1].y - p.y) / (pts[i + 1].x - p.x));
  const k = slopes.indexOf(Math.min(...slopes));
  pts.forEach((p, i) => {
    s += dot(X(p.x), Y(p.y), i === k && big ? P.accent : P.seq[4], big ? 8 : 4);
    if (big) {
      // Two builds £293 apart share ~130px of axis; their names cannot both sit
      // on one row. Stagger any label whose neighbour is closer than a name.
      const crowded = i > 0 && X(p.x) - X(pts[i - 1].x) < 200;
      s += T(X(p.x), h - padB + 26, `£${fmt(p.x)}`, { mono: true, size: 17, fill: P.ink, weight: 500, anchor: 'middle' });
      s += T(X(p.x), h - padB + (crowded ? 70 : 48), p.label.replace(/^(GeForce|Radeon|Intel) /, ''), { size: 14, fill: P.faint, anchor: 'middle' });
      s += T(X(p.x) + (i === pts.length - 1 ? -16 : 0), Y(p.y) - 24, `${p.y}`, { mono: true, size: 22, weight: 500, fill: P.ink, anchor: i === pts.length - 1 ? 'end' : 'middle' });
    }
  });
  if (big) {
    const a = pts[k], b = pts[k + 1];
    const lx = X(a.x) - 30, ly = Y(a.y) - 120;
    s += hair(X(a.x), Y(a.y) - 14, X(a.x), ly + 34, P.lineStrong);
    s += T(lx, ly, 'the curve flattens here', { size: 18, weight: 600, fill: P.ink, anchor: 'end' });
    s += T(lx, ly + 26, `the next £${fmt(b.x - a.x)} buys ${b.y - a.y} frames`, { size: 16, fill: P.muted, anchor: 'end' });
  }
  return SVG(w, h, s);
}

function coverCard() {
  const pts = builds.map((b) => ({ x: b.total, y: b.cyberpunk1440, label: b.gpuShort }));
  const subject = `Four PC builds from £${fmt(builds[0].total)} to £${fmt(builds[builds.length - 1].total)}, all running Cyberpunk 2077 at 1440p on high with no upscaling — what each costs against the frame rate it gets`;
  return { subject, html: `${brand(`${NOW} build guide`)}
  <h1>Four PC builds,<br><em>and what they<br>actually do</em></h1>
  ${shows(subject)}
  <div class="chart">${curve(pts, { w: 960, h: 560 })}</div>
  ${howto('Each dot is a build. Left to right is what it costs; up is the frame rate it gets. Where the line flattens, the next pound buys less.')}
  ${foot(`Frame rates come out of an open model that shows its working. Prices are recalled UK street prices, not
    scraped — read the totals as the shape of a budget. Swipe for each build: parts, power draw and six games apiece.`)}` };
}

/* ---- build cards: bars against the target -------------------------------- */
function buildBars(b) {
  const rows = b.rows;
  const W = 960, LAB = 300, TIP = 96, BAND = 98, TOP = 46;
  const H = TOP + rows.length * BAND;
  const max = Math.max(b.refreshHz, ...rows.map((r) => r.fps)) * 1.06;
  const x = (v) => LAB + (v / max) * (W - LAB - TIP);
  let s = '';
  s += `<line x1="${x(b.refreshHz)}" y1="${TOP - 10}" x2="${x(b.refreshHz)}" y2="${H}" stroke="${P.lineStrong}" stroke-width="1"/>`;
  s += T(x(b.refreshHz), TOP - 30, `${b.refreshHz}Hz target`, { mono: true, size: 13, fill: P.faint, anchor: 'middle', ls: 1.5 });
  rows.forEach((r, i) => {
    const cy = TOP + i * BAND + BAND / 2 - 6;
    const lim = LIM[r.limiter] ?? { c: P.faint, l: r.limiter };
    const clears = r.fps >= b.refreshHz;
    s += T(0, cy - 8, r.game, { size: 22, weight: 500, fill: P.ink });
    s += `<circle cx="6" cy="${cy + 18}" r="4.5" fill="${lim.c}"/>` + T(17, cy + 18, lim.l, { mono: true, size: 12.5, fill: P.faint, ls: 1 });
    s += bar(LAB, cy - 12, Math.max(6, x(r.fps) - LAB), 24, P.seq[3], 'right', 4, `opacity="${clears ? 1 : 0.55}"`);
    s += `<rect x="${x(r.low1) - 1}" y="${cy - 12}" width="2" height="24" fill="${P.ink}" opacity=".8"/>`;
    s += T(x(r.fps) + 12, cy, `${r.fps}`, { mono: true, size: 24, weight: 500, fill: P.ink });
  });
  return SVG(W, H, s);
}

function buildCard(b) {
  const clears = b.rows.filter((r) => r.fps >= b.refreshHz).length;
  const subject = `The £${fmt(b.total)} build — ${esc(b.gpuShort)} with a ${esc(b.cpuShort)} — in ${b.rows.length} games at ${b.resolution} on high with no upscaling, against its ${b.refreshHz}Hz monitor`;
  return { subject, html: `${brand(`${b.resolution} · ${b.refreshHz}Hz · high · no upscaling`)}
  <h1>The £${fmt(b.total)} build<br><em>${clears} of ${b.rows.length} games clear ${b.refreshHz}Hz</em></h1>
  ${shows(subject)}
  <div class="strip"><div><b>${esc(b.gpuShort)}</b></div><div><b>${esc(b.cpuShort)}</b></div><div><b>${esc(b.ram)}</b></div><div><b>${b.powerW}W</b> draw</div><div><b>${b.psuPartW}W</b> supply</div></div>
  <div class="chart">${buildBars(b)}</div>
  <div class="legend"><div><i style="background:${P.seq[3]}"></i>average fps</div><div><i class="tick"></i>1% low</div><div><i style="background:${P.seq[3]};opacity:.55"></i>under the target</div></div>
  ${howto(`Bars are average frame rate. The white tick inside each bar is the 1% low — the stutters. The vertical rule is the monitor's ${b.refreshHz}Hz; a dim bar falls short of it.`)}
  ${foot(`${clears} of ${b.rows.length} games clear the ${b.refreshHz}Hz target at ${b.resolution}. The dimmer bars do not — for those you
    are buying a ${b.resolution} panel, not a ${b.refreshHz}Hz one. Prices are recalled, not sourced; price the parts yourself.`)}` };
}

/* ---- bottleneck: slope chart indexed to the slowest chip ----------------- */
function slope(s, { w, h, ymax, axis = true }) {
  const cpus = s.cpus;
  // Under 600px the panel is one of a pair: shorter tick labels, a narrower
  // label gutter, and "capped" alone at the line end — the legend names the game.
  const narrow = w < 600;
  const padL = axis ? 54 : 30, padR = narrow ? 118 : 206, padT = 26, padB = 46;
  const X = (i) => padL + i * ((w - padL - padR) / (cpus.length - 1));
  const Y = (v) => padT + (1 - (v - 100) / (ymax - 100)) * (h - padT - padB);
  let out = '';
  for (let g = 100; g <= ymax; g += 25) {
    out += hair(padL, Y(g), w - padR, Y(g));
    if (axis) out += T(padL - 10, Y(g), pct(g - 100), { mono: true, size: 13, fill: P.faint, anchor: 'end' });
  }
  cpus.forEach((c, i) => { out += hair(X(i), padT, X(i), h - padB, P.line); out += T(X(i), h - padB + 22, narrow ? c.replace(/^Ryzen \d /, '') : shortCpu(c), { mono: true, size: 13, fill: P.faint, anchor: 'middle' }); });
  const series = s.games.map((g) => {
    const base = g.points[0].fps;
    return { name: g.game, idx: g.points.map((p) => (p.fps / base) * 100), capped: g.gainPct === 0, color: gameColor(g.game), gain: g.gainPct };
  });
  for (const se of series) {
    const d = se.idx.map((v, i) => `${i ? 'L' : 'M'}${X(i)} ${Y(v)}`).join('');
    out += `<path d="${d}" fill="none" stroke="${se.color}" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round" ${se.capped ? 'stroke-dasharray="4 6"' : ''}/>`;
  }
  for (const se of series) se.idx.forEach((v, i) => { out += dot(X(i), Y(v), se.color, i === se.idx.length - 1 ? 6 : 4); });
  const last = cpus.length - 1;
  const labels = spread(series.map((se) => ({ y: Y(se.idx[last]), se })), 26, padT + 8, h - padB - 8);
  for (const l of labels) {
    const lx = w - padR + 18;
    if (Math.abs(l.ly - l.y) > 2) out += hair(X(last) + 9, l.y, lx - 8, l.ly, P.lineStrong);
    out += `<rect x="${lx}" y="${l.ly - 6}" width="12" height="12" rx="3" fill="${l.se.color}"/>`;
    out += T(lx + 20, l.ly, l.se.capped ? (narrow ? 'capped' : `${l.se.name} · capped`) : pct(l.se.gain), { mono: true, size: 15, weight: 500, fill: P.ink });
  }
  return SVG(w, h, out);
}
const shortGame = (g) => g.replace('Total War Warhammer III', 'Total War').replace("Baldur's Gate 3", "Baldur's Gate").replace('Counter-Strike 2', 'Counter-Strike').replace('Cyberpunk 2077', 'Cyberpunk');
const gameLegend = (s) => `<div class="legend">${s.games.map((g) => `<div><i class="line" style="background:${gameColor(g.game)}${g.gainPct === 0 ? ';opacity:.6' : ''}"></i>${esc(shortGame(g.game))}</div>`).join('')}</div>`;
const sharedYmax = () => Math.ceil(Math.max(...bottleneck.flatMap((s) => s.games.map((g) => (g.points[g.points.length - 1].fps / g.points[0].fps) * 100))) / 25) * 25;

function bottleneckCard(s) {
  const top = [...s.games].sort((a, b) => b.gainPct - a.gainPct)[0];
  const low = [...s.games].filter((g) => g.gainPct > 0).sort((a, b) => a.gainPct - b.gainPct)[0];
  const subject = `Four processors, from a ${esc(s.cpus[0])} to a ${esc(s.cpus[s.cpus.length - 1])}, under one ${esc(s.gpuShort)} at ${s.resolution} on high — how much faster each game runs on the better chip`;
  return { subject, html: `${brand(`${s.gpuShort} · ${s.resolution} · high`)}
  <h1>Four processors,<br><em>one graphics card</em></h1>
  ${shows(subject)}
  <div class="chart">${slope(s, { w: 960, h: 560, ymax: sharedYmax() })}</div>
  ${gameLegend(s)}
  ${howto('Each line is one game. It starts at the slowest chip on the left and ends at the best on the right; the higher it climbs, the more the processor mattered. The dashed line is capped by its engine.')}
  ${foot(`${pct(top.gainPct)} in ${esc(top.game)} against ${pct(low.gainPct)} in ${esc(low.game)}, from the same upgrade on the same
    card. The dashed line is capped by its engine and no processor changes it. Change the card or the game and the answer changes.`)}` };
}

function fanOutCard() {
  const ymax = sharedYmax();
  const [a, b] = bottleneck;
  const g = (s, name) => s.games.find((x) => x.game === name);
  const cp = 'Cyberpunk 2077';
  const subject = `The same four processors under a ${esc(a.gpuShort)} and then a ${esc(b.gpuShort)}, at ${a.resolution} on high — how much the processor matters depends on the card it sits next to`;
  return { subject, html: `${brand(`same four processors · ${a.resolution} · high`)}
  <h1 class="tight">Same four processors,<br><em>two graphics cards</em></h1>
  ${shows(subject)}
  <div class="panels">
    <div class="panel"><div class="t">${esc(a.gpuShort)}</div><div class="d">lines bunch — the card is the limit</div>${slope(a, { w: 470, h: 500, ymax })}</div>
    <div class="panel"><div class="t">${esc(b.gpuShort)}</div><div class="d">lines fan — the chip is the limit</div>${slope(b, { w: 470, h: 500, ymax, axis: false })}</div>
  </div>
  ${gameLegend(a)}
  ${howto('Each line is one game, starting at the slowest chip and ending at the best. Both panels share one scale. Bunched lines mean the card was the limit; fanned lines mean the processor was.')}
  ${foot(`Cyberpunk goes from ${pct(g(a, cp).gainPct)} to ${pct(g(b, cp).gainPct)} for the same four chips. "Is a ${esc(a.cpus[1])} enough?"
    cannot be answered without knowing what card it sits next to and what you play.`)}` };
}

/* ---- silent tax: dumbbell, one hue in two shades ------------------------- */
function dumbbell(rows, { w, h }) {
  /* Indexed: one stick = 100 on every row. On a shared absolute axis the
     Counter-Strike pair (336 → 469) spanned the card and Baldur's Gate
     (65 → 95) collapsed to two touching dots — the geometry was showing frame
     counts when the story is the gain. Now every row starts on the same line
     and the length of the bar is what the second stick is worth. The real
     frame rates still sit beside the dots. */
  const LAB = 300, GAIN = 110, BAND = h / rows.length;
  const idx = (r) => (r.after / r.before) * 100;
  const max = Math.max(...rows.map(idx)) * 1.03;
  const X = (v) => LAB + 44 + ((v - 100) / (max - 100)) * (w - LAB - 44 - GAIN - 90);
  let s = '';
  s += hair(X(100), 0, X(100), h, P.lineStrong);
  rows.forEach((r, i) => {
    const cy = i * BAND + BAND / 2;
    s += T(0, cy, r.game, { size: 22, weight: 500, fill: P.ink });
    s += `<line x1="${X(100)}" y1="${cy}" x2="${X(idx(r))}" y2="${cy}" stroke="${P.seq[2]}" stroke-width="4" stroke-linecap="round"/>`;
    s += dot(X(100), cy, P.seq[1], 8);
    s += dot(X(idx(r)), cy, P.seq[4], 8);
    s += T(X(100) - 18, cy, `${r.before}`, { mono: true, size: 18, fill: P.faint, anchor: 'end' });
    s += T(X(idx(r)) + 18, cy, `${r.after}`, { mono: true, size: 20, weight: 500, fill: P.ink });
    s += T(w, cy, pct(r.gainPct), { mono: true, size: 28, weight: 600, fill: P.ink, anchor: 'end' });
  });
  return SVG(w, h, s);
}
/** Two memory slots, one or two of them filled. */
function slots(filled) {
  const cell = (i) => `<rect x="${i * 46}" y="0" width="34" height="90" rx="5" fill="${i < filled ? P.seq[4] : 'none'}" stroke="${i < filled ? P.seq[4] : P.lineStrong}" stroke-width="2"/>` +
    [18, 36, 54, 72].map((y) => `<rect x="${i * 46 + 8}" y="${y}" width="18" height="6" rx="1.5" fill="${i < filled ? P.bg : P.line}" opacity=".9"/>`).join('');
  return SVG(84, 92, cell(0) + cell(1));
}

function silentTaxCard(t) {
  const rows = [...t.rows].sort((a, b) => b.gainPct - a.gainPct);
  const best = rows[0];
  const subject = `One stick of RAM against two — same capacity, same speed — on a ${esc(t.gpu)} with a ${esc(t.cpu)} at ${t.resolution}: what the second memory channel is worth, game by game`;
  return { subject, html: `${brand(`${t.gpu} · ${t.cpu} · ${t.resolution}`)}
  <h1 class="tight">One RAM stick or two?<br><em>Two is worth up to ${pct(best.gainPct)}</em></h1>
  ${shows(subject)}
  <div class="chart">${dumbbell(rows, { w: 960, h: 500 })}</div>
  <div class="legend"><div><i style="background:${P.seq[1]};border-radius:50%"></i>one stick, one channel</div><div><i style="background:${P.seq[4]};border-radius:50%"></i>two sticks, two channels</div>
    <div style="margin-left:auto;gap:14px">${slots(1)}<span style="color:${P.faint}">→</span>${slots(2)}</div></div>
  ${howto('Left dot: one stick. Right dot: two sticks. The bar between them is what the second channel adds. Every row starts on the same line so the bars compare; the real frame rates sit beside the dots.')}
  ${foot(`Check yours: Task Manager → Performance → Memory, "Slots used". If it reads one of two and you have a spare
    stick, that is ${pct(best.gainPct)} in ${esc(best.game)} sitting in a drawer.`)}` };
}

/* ---- VRAM: paired columns, one hue in two shades ------------------------- */
function paired(rows, { w, h, a, b }) {
  const padL = 70, padT = 40, padB = 60, GAP = 48;
  const max = Math.max(...rows.flatMap((r) => [r.a, r.b])) * 1.2;
  const groupW = (w - padL) / rows.length, colW = Math.min(24, (groupW - GAP) / 2 - 2);
  const Y = (v) => padT + (1 - v / max) * (h - padT - padB);
  let s = '';
  for (let g = 0; g <= max; g += 25) { s += hair(padL, Y(g), w, Y(g)); s += T(padL - 12, Y(g), `${g}`, { mono: true, size: 13, fill: P.faint, anchor: 'end' }); }
  rows.forEach((r, i) => {
    const cx = padL + i * groupW + groupW / 2;
    const xa = cx - colW - 1, xb = cx + 1;
    s += bar(xa, Y(r.a), colW, Y(0) - Y(r.a), P.seq[2], 'up');
    s += bar(xb, Y(r.b), colW, Y(0) - Y(r.b), P.seq[4], 'up');
    // Label selectively: an identical pair gets one figure, centred, because
    // "90 90" over two touching columns read as a single number.
    if (r.a === r.b) s += T(cx, Y(r.a) - 16, `${r.a}`, { mono: true, size: 20, weight: 500, fill: P.ink, anchor: 'middle' });
    else {
      s += T(xa + colW / 2 - 4, Y(r.a) - 16, `${r.a}`, { mono: true, size: 19, fill: P.muted, anchor: 'middle' });
      s += T(xb + colW / 2 + 4, Y(r.b) - 16, `${r.b}`, { mono: true, size: 20, weight: 500, fill: P.ink, anchor: 'middle' });
    }
    s += T(cx, h - padB + 26, r.resolution, { mono: true, size: 16, fill: P.ink, weight: 500, anchor: 'middle' });
    s += T(cx, h - padB + 48, r.gainPct ? `${pct(r.gainPct)} · vram wall` : 'identical', { size: 14, fill: r.gainPct ? P.ink : P.faint, weight: r.gainPct ? 600 : 400, anchor: 'middle' });
  });
  return SVG(w, h, s);
}
/** One die, and the memory beside it. Module count drawn from capacity. */
function die(rec, shade) {
  const n = Math.round(rec.vramGB / 2);
  let s = `<rect x="0" y="0" width="70" height="70" rx="6" fill="${P.surface2}" stroke="${P.lineStrong}" stroke-width="1.5"/>`;
  s += `<rect x="14" y="14" width="42" height="42" rx="3" fill="${shade}" opacity=".9"/>`;
  for (let i = 0; i < n; i++) s += `<rect x="${86 + (i % 4) * 22}" y="${8 + Math.floor(i / 4) * 30}" width="16" height="24" rx="2" fill="${shade}" opacity=".75"/>`;
  return SVG(180, 72, s);
}

function vramCard(v) {
  const same = v.rows.filter((r) => r.gainPct === 0).map((r) => r.resolution);
  const wall = v.rows.find((r) => r.gainPct > 0);
  const subject = `${esc(v.a.brand)} with ${v.a.vramGB}GB against the same card with ${v.b.vramGB}GB — identical ${esc(v.a.chip)} chip, ${fmt(v.a.shaders)} shaders and clocks — in Cyberpunk 2077 on high at three resolutions`;
  return { subject, html: `${brand(`${v.a.brand} · cyberpunk 2077 · high`)}
  <h1 class="tight">Same chip, ${v.a.vramGB}GB or ${v.b.vramGB}GB.<br><em>Zero extra frames at ${same.join(' and ')}.</em></h1>
  ${shows(subject)}
  <div class="chart">${paired(v.rows, { w: 960, h: 500, a: v.a, b: v.b })}</div>
  <div class="legend"><div><i style="background:${P.seq[2]}"></i>${v.a.vramGB}GB</div><div><i style="background:${P.seq[4]}"></i>${v.b.vramGB}GB</div>
    <div style="margin-left:auto;gap:22px">${die(v.a, P.seq[2])}${die(v.b, P.seq[4])}</div></div>
  ${howto(`Two columns per resolution: the ${v.a.vramGB}GB card, then the ${v.b.vramGB}GB. The same height means the extra memory did nothing. The chips on the right are the same die with more memory beside it.`)}
  ${foot(`A card runs out of shader throughput long before it runs out of memory at the two resolutions almost
    everybody plays at. VRAM matters when memory is the thing you ran out of — ${wall ? wall.resolution : '4K'}, texture mods, heavy ray tracing. Real list. Not most people.`)}` };
}

/* ---- still good: small multiples with a 60fps rule ----------------------- */
function stillGrid(s) {
  const games = s.cards[0].rows.map((r) => r.game);
  const COL = 300, GAPX = 30, HEAD = 52, BAND = 96, LAB = 132;
  const H = HEAD + s.cards.length * BAND;
  let out = '';
  games.forEach((game, gi) => {
    const ox = gi * (COL + GAPX);
    const vals = s.cards.map((c) => c.rows.find((r) => r.game === game).fps ?? 0);
    const max = Math.max(SIXTY, ...vals) * 1.22;
    const X = (v) => ox + LAB + (v / max) * (COL - LAB - 44);
    out += T(ox, 12, shortGame(game), { size: 21, weight: 600, fill: P.ink });
    out += hair(X(SIXTY), HEAD - 8, X(SIXTY), H, P.lineStrong);
    out += T(X(SIXTY), HEAD - 18, `${SIXTY}`, { mono: true, size: 12, fill: P.faint, anchor: 'middle' });
    s.cards.forEach((c, ci) => {
      const cy = HEAD + ci * BAND + BAND / 2;
      const v = c.rows.find((r) => r.game === game).fps;
      out += T(ox, cy - 9, c.gpu.replace('GeForce ', '').replace('Radeon ', ''), { size: 16, weight: 500, fill: P.ink });
      out += T(ox, cy + 12, `${c.year} · ${c.vram}GB`, { mono: true, size: 12, fill: P.faint });
      if (v == null) { out += T(X(0) + 4, cy, '—', { mono: true, size: 18, fill: P.faint }); return; }
      out += bar(X(0), cy - 12, Math.max(4, X(v) - X(0)), 24, P.seq[3], 'right', 4, `opacity="${v >= SIXTY ? 1 : 0.55}"`);
      out += T(X(v) + 10, cy, `${v}`, { mono: true, size: 19, weight: 500, fill: P.ink });
    });
  });
  return SVG(960, H, out);
}

function stillGoodCard(s) {
  const ages = s.cards.map((c) => NOW - Number(c.year));
  const cp = s.cards.map((c) => c.rows.find((r) => /cyberpunk/i.test(r.game)).fps).filter(Boolean);
  const under = s.cards.filter((c) => s.minVramGB != null && c.vram < s.minVramGB);
  const multi = s.cards.filter((c) => c.siblings.length > 1);
  const clearsShooters = s.cards.every((c) => c.rows.filter((r) => !/cyberpunk/i.test(r.game)).every((r) => r.fps >= SIXTY));
  const years = s.cards.map((c) => Number(c.year));
  const subject = `Four graphics cards from ${Math.min(...years)} to ${Math.max(...years)} — ${s.cards.map((c) => esc(c.gpu.replace(/^\w+ /, ''))).join(', ')} — in three current games at ${s.resolution} on high, with a ${esc(s.cpu)}`;
  return { subject, html: `${brand(`${s.resolution} · high · ${s.cpu}`)}
  <h1>Is your old card<br><em>still good?</em></h1>
  ${shows(subject)}
  <div class="chart">${stillGrid(s)}</div>
  ${howto(`Each column is a game, each row a card. The rule is ${SIXTY}fps; a bright bar clears it and a dim one falls short. ${clearsShooters ? 'Every card clears it in both shooters.' : ''} Cyberpunk is where they separate: ${Math.min(...cp)} to ${Math.max(...cp)}fps.`)}
  <div class="why">
    <div><div class="t">Check which one you own</div><div class="b">${multi.map((c, i) => `${i ? 'the' : 'The'} ${esc(c.gpu.replace(/^\w+ /, ''))} shipped as ${c.siblings.map((g) => `${g}GB`).join(' and ')}`).join(', ')}.
      On a chart about where the line falls, the memory size is half the answer.</div></div>
    <div><div class="t">Below minimum still starts</div><div class="b">${under.map((c) => `The ${esc(c.gpu.replace(/^\w+ /, ''))}'s ${c.vram}GB is under ${esc(s.minVramGame)}'s published ${s.minVramGB}GB. That costs frames, not the launch — ${c.rows.find((r) => /cyberpunk/i.test(r.game)).fps}fps is running, and running is not the same as playable.`).join(' ')}</div></div>
  </div>
  ${foot(`Four cards between ${Math.min(...ages)} and ${Math.max(...ages)} years old. A dash would mean the card cannot run that game at all.`)}` };
}

/* ---- hero figures --------------------------------------------------------- */
function heroStepCard() {
  const a = builds[builds.length - 2], b = builds[builds.length - 1];
  const pts = builds.map((x) => ({ x: x.total, y: x.cyberpunk1440 }));
  const subject = `The top step of the build ladder — from the £${fmt(a.total)} build to the £${fmt(b.total)} one — in Cyberpunk 2077 at 1440p on high`;
  return { subject, html: `${brand('the last two builds · cyberpunk 2077 · 1440p')}
  <h1>The last £${fmt(b.total - a.total)}<br><em>of a PC build</em></h1>
  ${shows(subject)}
  <div class="hero">
    <div class="fig"><div class="v two">£${fmt(b.total - a.total)}</div><div class="l">more money, from the £${fmt(a.total)} build to the £${fmt(b.total)} one</div></div>
    <div class="fig"><div class="v two">+${b.cyberpunk1440 - a.cyberpunk1440}</div><div class="l">more frames — ${a.cyberpunk1440} to ${b.cyberpunk1440} in Cyberpunk 2077 at 1440p</div></div>
  </div>
  <div class="sub" style="margin-top:36px">£${fmt(a.total)} → £${fmt(b.total)} is ${Math.round((b.total / a.total - 1) * 100)}% more money for
    ${Math.round((b.cyberpunk1440 / a.cyberpunk1440 - 1) * 100)}% more frames. The curve flattens hard after about £${fmt(Math.round(a.total / 100) * 100)},
    and that is the most useful thing on this account if you are deciding what to spend.</div>
  <div class="chart">${curve(pts, { w: 960, h: 150, big: false })}</div>
  ${howto('The line is all four builds — price left to right, frame rate up. The last segment is the flattest.')}
  ${foot(`Ordering reliable; absolute frame rates are not. Prices are recalled, not sourced — the shape holds, the totals move.`)}` };
}

function heroCpuCard() {
  const s = bottleneck[0];
  const games = [...s.games].filter((g) => g.gainPct > 0).sort((a, b) => b.gainPct - a.gainPct);
  const top = games[0], low = games[games.length - 1];
  const subject = `What the best of four processors is worth over the slowest, on a ${esc(s.gpuShort)} at ${s.resolution} on high — in the game where it matters most and the one where it barely does`;
  return { subject, html: `${brand(`same four processors · ${s.gpuShort} · ${s.resolution}`)}
  <h1>What a CPU upgrade<br><em>is actually worth</em></h1>
  ${shows(subject)}
  <div class="hero">
    <div class="fig"><div class="v two">${pct(top.gainPct)}</div><div class="l"><span class="swatch" style="background:${gameColor(top.game)}"></span><b>${esc(top.game)}</b><br>the best chip over the slowest</div></div>
    <div class="fig"><div class="v two">${pct(low.gainPct)}</div><div class="l"><span class="swatch" style="background:${gameColor(low.game)}"></span><b>${esc(low.game)}</b><br>same four chips, same card</div></div>
  </div>
  <div class="sub" style="margin-top:44px">What the best processor on the list is worth over the slowest — same card, same
    resolution, same four chips. If somebody says a CPU "bottlenecks" a card without asking what you play, they are guessing.</div>
  ${foot(`Simulation and strategy live on the processor; shooters at ${s.resolution} live on the card. The answer is per-game and it always was.`)}` };
}

function hero970Card() {
  const s = pillars.stillGood;
  const c = s.cards.find((x) => /970/.test(x.gpu));
  const v = c.rows.find((r) => /cyberpunk/i.test(r.game)).fps;
  const subject = `A ${esc(c.gpu)} from ${c.year} running Cyberpunk 2077 at ${s.resolution} on high, with a ${esc(s.cpu)}`;
  return { subject, html: `${brand(`${c.gpu} · ${c.year} · cyberpunk 2077 · ${s.resolution} high`)}
  <h1>A ${NOW - Number(c.year)}-year-old card<br><em>in Cyberpunk 2077</em></h1>
  ${shows(subject)}
  <div class="hero"><div class="fig"><div class="v">${v}<small>fps</small></div>
    <div class="l">A ${NOW - Number(c.year)}-year-old card in ${esc(s.minVramGame)}. Its ${c.vram}GB is under the published ${s.minVramGB}GB minimum.</div></div></div>
  <div class="sub" style="margin-top:44px"><b style="color:${P.ink}">It starts.</b> Nothing in a shipping game checks your memory and refuses. A card under the minimum spills to system
    RAM over the bus and stutters — a low number, not a locked door. Starting is not the same as playing, and ${v} is the honest figure.</div>
  ${foot(`The number the model used to give here was "will not run". That was false about the real world, and it has been fixed.`)}` };
}

/* ---- render --------------------------------------------------------------- */
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1080, height: 1350 }, deviceScaleFactor: 2 });
const shots = [];
const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-');
const cards = [
  { name: '01-cover', ...coverCard() },
  ...builds.map((b, i) => ({ name: `0${i + 2}-${b.budget}`, ...buildCard(b) })),
  ...bottleneck.map((s, i) => ({ name: `bottleneck-${i + 1}-${slug(s.gpuShort)}`, ...bottleneckCard(s) })),
  { name: 'bottleneck-fan-out', ...fanOutCard() },
  { name: 'silent-tax-memory-channels', ...silentTaxCard(pillars.silentTax) },
  ...(pillars.vram ? [{ name: 'myth-vram', ...vramCard(pillars.vram) }] : []),
  { name: 'still-good-old-cards', ...stillGoodCard(pillars.stillGood) },
  { name: 'hero-price-step', ...heroStepCard() },
  { name: 'hero-cpu-split', ...heroCpuCard() },
  { name: 'hero-gtx-970', ...hero970Card() },
];
// The manifest: every image, and the sentence printed on it. verify.mjs holds
// the caption file to this — an image with no caption, a caption pointing at
// no image, or a caption whose "shows" line differs from the card's all fail.
const unesc = (t) => t.replace(/&amp;/g, '&').replace(/&lt;/g, '<');
writeFileSync('marketing/cards.json', JSON.stringify(cards.map((c) => ({ name: c.name, file: `images/${c.name}.png`, subject: unesc(c.subject) })), null, 2) + '\n');
for (const c of cards) {
  await page.setContent(`<style>${css}</style>${c.html}`, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(150);
  const f = `marketing/images/${c.name}.png`;
  await page.screenshot({ path: f });
  shots.push(f);
}
await browser.close();
console.log(shots.join('\n'));
