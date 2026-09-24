/** Stylesheet and script of the public website (plain CSS + JS, no dependencies). */

export function siteCss(accent: string): string {
  return `/* Gauntlet public leaderboard — generated file */
:root {
  --accent: ${accent};
  --bg: #f6f7fb;
  --surface: #ffffff;
  --surface-2: #f0f2f7;
  --text: #121521;
  --text-2: #3c4256;
  --muted: #6b7185;
  --border: #e1e4ee;
  --good: #0f9d6b;
  --bad: #d64545;
  --warn: #b7791f;
  --gold: #d4a017;
  --silver: #9aa3b2;
  --bronze: #b8733a;
  --shadow: 0 1px 2px rgba(16, 24, 40, 0.06), 0 4px 16px rgba(16, 24, 40, 0.05);
  --radius: 14px;
  --mono: ui-monospace, "SFMono-Regular", "Cascadia Mono", Consolas, monospace;
  color-scheme: light;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #0b0d14;
    --surface: #131722;
    --surface-2: #1a1f2d;
    --text: #eef0f6;
    --text-2: #c3c8d6;
    --muted: #8a91a6;
    --border: #262c3d;
    --good: #34d399;
    --bad: #f87171;
    --warn: #fbbf24;
    --shadow: 0 1px 2px rgba(0, 0, 0, 0.4), 0 8px 24px rgba(0, 0, 0, 0.25);
    color-scheme: dark;
  }
}
:root[data-theme="dark"] {
  --bg: #0b0d14;
  --surface: #131722;
  --surface-2: #1a1f2d;
  --text: #eef0f6;
  --text-2: #c3c8d6;
  --muted: #8a91a6;
  --border: #262c3d;
  --good: #34d399;
  --bad: #f87171;
  --warn: #fbbf24;
  --shadow: 0 1px 2px rgba(0, 0, 0, 0.4), 0 8px 24px rgba(0, 0, 0, 0.25);
  color-scheme: dark;
}
* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font: 15px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}
a { color: var(--accent); text-decoration: none; }
a:hover { text-decoration: underline; }
h1, h2, h3 { line-height: 1.2; margin: 0 0 8px; letter-spacing: -0.01em; }
h1 { font-size: clamp(26px, 4vw, 38px); }
h2 { font-size: 22px; }
h3 { font-size: 17px; }
p { margin: 0 0 12px; }
code, pre, .mono { font-family: var(--mono); font-size: 0.9em; }
.wrap { max-width: 1180px; margin: 0 auto; padding: 0 16px; }
.muted { color: var(--muted); }
.small { font-size: 13px; }
.tnum { font-variant-numeric: tabular-nums; }

/* Header */
.top { background: var(--surface); border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
.top .wrap { display: flex; align-items: center; gap: 14px; min-height: 64px; flex-wrap: wrap; }
.brand { display: flex; align-items: center; gap: 10px; color: var(--text); font-weight: 750; font-size: 18px; min-width: 0; }
.brand:hover { text-decoration: none; }
.brand img, .brand svg { width: 34px; height: 34px; border-radius: 8px; flex: none; object-fit: cover; }
.brand span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav { display: flex; gap: 2px; flex-wrap: wrap; margin-left: auto; align-items: center; }
.nav a { color: var(--text-2); padding: 7px 11px; border-radius: 9px; font-weight: 550; font-size: 14px; }
.nav a:hover { background: var(--surface-2); text-decoration: none; }
.nav a[aria-current="page"] { color: var(--accent); background: color-mix(in srgb, var(--accent) 12%, transparent); }
.btn { display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 10px; padding: 7px 12px; font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; }
.btn:hover { text-decoration: none; background: var(--surface-2); }
.btn.primary { background: var(--accent); border-color: var(--accent); color: #fff; }
.btn.primary:hover { filter: brightness(1.08); }
.btn svg { width: 16px; height: 16px; }
.theme-btn { padding: 7px 9px; }
@media (max-width: 720px) {
  .top { position: static; }
  .top .wrap { padding-top: 10px; padding-bottom: 10px; }
  .brand { flex: 1; }
  .nav { margin-left: 0; width: 100%; overflow-x: auto; flex-wrap: nowrap; order: 3; }
  .nav a { white-space: nowrap; }
}

/* Hero */
.hero { padding: 34px 0 10px; }
.hero p.lead { font-size: 17px; color: var(--text-2); max-width: 760px; }
.meta-line { display: flex; flex-wrap: wrap; gap: 8px 16px; color: var(--muted); font-size: 13px; margin-top: 6px; }
.pill { display: inline-flex; align-items: center; gap: 6px; background: var(--surface-2); border: 1px solid var(--border); padding: 2px 9px; border-radius: 999px; font-size: 12.5px; color: var(--text-2); white-space: nowrap; }
.pill.warn { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 40%, var(--border)); }
.pill.good { color: var(--good); border-color: color-mix(in srgb, var(--good) 40%, var(--border)); }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; flex: none; }

/* Cards & layout */
main { padding-bottom: 40px; }
section { margin: 22px 0; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow); padding: 18px; }
.card + .card { margin-top: 16px; }
.grid2 > .card, .grid3 > .card { margin-top: 0; }
.card h2 { margin-bottom: 4px; }
.card .desc { color: var(--muted); font-size: 13.5px; margin-bottom: 14px; }
.grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.grid3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
@media (max-width: 900px) { .grid2, .grid3 { grid-template-columns: minmax(0, 1fr); } }
.scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }

/* Tabs */
.tabs { display: flex; gap: 6px; flex-wrap: wrap; margin: 18px 0 4px; }
.tabs button { border: 1px solid var(--border); background: var(--surface); color: var(--text-2); border-radius: 999px; padding: 7px 14px; font: inherit; font-weight: 600; font-size: 14px; cursor: pointer; }
.tabs button[aria-selected="true"] { background: var(--accent); border-color: var(--accent); color: #fff; }
.js [data-panel][hidden] { display: none; }
.nojs-title { display: none; }
html:not(.js) .nojs-title { display: block; }
html:not(.js) .tabs { display: none; }

/* Podium */
.podium { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin: 16px 0; }
.podium .p { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow); position: relative; overflow: hidden; }
.podium .p::before { content: ""; position: absolute; inset: 0 0 auto 0; height: 4px; background: var(--c); }
.podium .place { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted); }
.podium .name { font-size: 19px; font-weight: 750; margin: 6px 0 2px; }
.podium .idx { font-size: 38px; font-weight: 800; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.podium .ci { color: var(--muted); font-size: 13px; }
@media (max-width: 720px) { .podium { grid-template-columns: minmax(0, 1fr); } }

/* Tables */
table { border-collapse: collapse; width: 100%; font-size: 14px; }
th, td { padding: 9px 10px; text-align: left; border-bottom: 1px solid var(--border); vertical-align: middle; }
th { font-size: 12px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--muted); font-weight: 650; white-space: nowrap; }
th[data-sort] { cursor: pointer; user-select: none; }
th[data-sort]:hover { color: var(--text); }
th[aria-sort="ascending"]::after { content: " ▲"; }
th[aria-sort="descending"]::after { content: " ▼"; }
td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
tr:last-child td { border-bottom: 0; }
.model { display: flex; align-items: center; gap: 9px; min-width: 150px; }
.model a { color: var(--text); font-weight: 650; }
.model .v { color: var(--muted); font-size: 12.5px; display: block; font-weight: 400; }
.rank { font-weight: 800; color: var(--muted); width: 34px; }
.baseline td { color: var(--muted); }
.ci-bar { position: relative; height: 8px; background: var(--surface-2); border-radius: 99px; min-width: 150px; }
.ci-bar i { position: absolute; top: 0; bottom: 0; background: color-mix(in srgb, var(--c) 35%, transparent); border-radius: 99px; }
.ci-bar b { position: absolute; top: -3px; width: 3px; height: 14px; background: var(--c); border-radius: 2px; }
.idx-cell { display: flex; align-items: center; gap: 10px; }
.idx-cell strong { font-size: 16px; min-width: 42px; text-align: right; font-variant-numeric: tabular-nums; }

/* Heatmap */
.heat td.h { text-align: center; font-variant-numeric: tabular-nums; font-weight: 650; min-width: 54px; color: var(--text); }
.heat th.cat { text-align: center; white-space: normal; min-width: 64px; line-height: 1.2; }
.heat th.cat span { display: block; height: 3px; border-radius: 2px; margin: 6px auto 0; width: 28px; }

/* Medals */
.medal { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%; font-size: 11px; font-weight: 800; color: #fff; }
.medal.g { background: var(--gold); } .medal.s { background: var(--silver); } .medal.b { background: var(--bronze); }

/* Charts */
.chart svg { width: 100%; height: auto; display: block; }
.chart text { fill: var(--muted); font-size: 12px; font-family: inherit; }
.chart .lbl { fill: var(--text); font-weight: 650; }
.chart .grid-line { stroke: var(--border); }
.chart .axis { stroke: var(--muted); }
.chart .frontier { stroke: var(--accent); stroke-dasharray: 5 5; fill: none; stroke-width: 1.5; }
.legend { display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 13px; color: var(--text-2); margin-top: 8px; }
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.seg { display: inline-flex; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.seg button { border: 0; background: var(--surface); color: var(--text-2); padding: 6px 12px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
.seg button[aria-pressed="true"] { background: var(--accent); color: #fff; }
.controls { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; margin-bottom: 12px; }

/* Callouts */
.callouts { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
.callout { border: 1px solid var(--border); border-left: 4px solid var(--c, var(--accent)); background: var(--surface); border-radius: 12px; padding: 12px 14px; }
.callout .big { font-size: 26px; font-weight: 800; color: var(--good); font-variant-numeric: tabular-nums; }
.note { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border: 1px solid color-mix(in srgb, var(--accent) 25%, var(--border)); border-radius: 12px; padding: 12px 14px; color: var(--text-2); }

/* Test / model pages */
.kv { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.kv div { background: var(--surface-2); border-radius: 10px; padding: 10px 12px; }
.kv .k { font-size: 12px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.05em; }
.kv .v { font-size: 20px; font-weight: 750; font-variant-numeric: tabular-nums; }
pre.prompt { white-space: pre-wrap; word-break: break-word; background: var(--surface-2); border: 1px solid var(--border); border-radius: 10px; padding: 14px; max-height: 520px; overflow: auto; font-size: 13px; line-height: 1.5; }
.bar { height: 8px; border-radius: 99px; background: var(--surface-2); overflow: hidden; min-width: 80px; }
.bar i { display: block; height: 100%; background: var(--c, var(--accent)); border-radius: 99px; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; }
.tcard { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 14px 16px; display: block; color: var(--text); box-shadow: var(--shadow); }
.tcard:hover { text-decoration: none; border-color: var(--accent); }
.tcard .t { font-weight: 700; }
.tcard .h { color: var(--muted); font-size: 13.5px; margin-top: 4px; }
.back { display: inline-block; margin: 20px 0 0; font-size: 14px; }

/* Footer */
footer { border-top: 1px solid var(--border); padding: 22px 0 36px; color: var(--muted); font-size: 13px; }
footer .wrap { display: flex; flex-wrap: wrap; gap: 8px 18px; justify-content: space-between; }
`;
}

export const SITE_JS = `// Gauntlet public leaderboard — theme toggle, tabs, sortable tables, chart filters. No dependencies.
(function () {
  var root = document.documentElement;
  root.classList.add('js');
  var KEY = 'gauntlet-site-theme';
  function stored() { try { return localStorage.getItem(KEY); } catch (e) { return null; } }
  function store(v) { try { localStorage.setItem(KEY, v); } catch (e) { /* private mode */ } }
  var saved = stored();
  if (saved === 'light' || saved === 'dark') root.setAttribute('data-theme', saved);
  function current() {
    var t = root.getAttribute('data-theme');
    if (t) return t;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  document.addEventListener('DOMContentLoaded', function () {
    var btn = document.getElementById('theme-btn');
    if (btn) btn.addEventListener('click', function () {
      var next = current() === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      store(next);
    });

    // Tabs: <div data-tabs> <button data-tab="x"> … and <div data-panel="x" data-group="g">
    document.querySelectorAll('[data-tabs]').forEach(function (bar) {
      var group = bar.getAttribute('data-tabs');
      var buttons = bar.querySelectorAll('[data-tab]');
      function show(id) {
        buttons.forEach(function (b) { b.setAttribute('aria-selected', String(b.getAttribute('data-tab') === id)); });
        document.querySelectorAll('[data-group="' + group + '"]').forEach(function (p) { p.hidden = p.getAttribute('data-panel') !== id; });
      }
      buttons.forEach(function (b) { b.addEventListener('click', function () { show(b.getAttribute('data-tab')); }); });
      if (buttons.length) show(buttons[0].getAttribute('data-tab'));
    });

    // Variant switchers (e.g. history tier filter): <div data-variants="g"> <button data-variant="x">
    document.querySelectorAll('[data-variants]').forEach(function (bar) {
      var group = bar.getAttribute('data-variants');
      var buttons = bar.querySelectorAll('[data-variant]');
      function show(id) {
        buttons.forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-variant') === id)); });
        document.querySelectorAll('[data-vgroup="' + group + '"]').forEach(function (p) { p.hidden = p.getAttribute('data-vpanel') !== id; });
      }
      buttons.forEach(function (b) { b.addEventListener('click', function () { show(b.getAttribute('data-variant')); }); });
      if (buttons.length) show(buttons[0].getAttribute('data-variant'));
    });

    // Sortable tables: <th data-sort> and <td data-v="number|text">
    document.querySelectorAll('table[data-sortable]').forEach(function (table) {
      var heads = table.querySelectorAll('th[data-sort]');
      heads.forEach(function (th) {
        th.addEventListener('click', function () {
          var col = Array.prototype.indexOf.call(th.parentNode.children, th);
          var dir = th.getAttribute('aria-sort') === 'descending' ? 1 : -1;
          heads.forEach(function (h) { h.removeAttribute('aria-sort'); });
          th.setAttribute('aria-sort', dir === 1 ? 'ascending' : 'descending');
          var body = table.tBodies[0];
          var rows = Array.prototype.slice.call(body.rows);
          rows.sort(function (a, b) {
            var x = a.cells[col] ? a.cells[col].getAttribute('data-v') : '';
            var y = b.cells[col] ? b.cells[col].getAttribute('data-v') : '';
            var nx = parseFloat(x), ny = parseFloat(y);
            if (x === '' || x === null) return 1;
            if (y === '' || y === null) return -1;
            if (!isNaN(nx) && !isNaN(ny)) return (nx - ny) * dir;
            return String(x).localeCompare(String(y)) * dir;
          });
          rows.forEach(function (r) { body.appendChild(r); });
        });
      });
    });
  });
})();
`;
