/**
 * Accessibility audit.
 *
 * Runs the real checks against a running preview rather than reading the source
 * and hoping: contrast ratios computed from the COMPUTED colours against the
 * effective background, form labels, table headers, accessible names on
 * icon-only controls, heading structure, landmarks, and a focus ring driven by
 * actual Tab presses.
 *
 * Two things this got wrong on its first run, both fixed and both worth knowing
 * if you extend it. An element inside a `display: none` ancestor still returns
 * a colour, so the print-only header — correct at 7.5:1 on white paper — was
 * reported as a screen failure; `getClientRects()` is the test for actually
 * being laid out. And calling `.focus()` does NOT match `:focus-visible`, by
 * design, so a programmatic check reports no focus ring on a page that has one.
 *
 * Usage:  npm run preview   (in another shell, note the port)
 *         node scripts/a11y-audit.mjs [port]
 */
import { chromium } from 'playwright';

// WCAG relative luminance and contrast ratio.
const lum = ([r,g,b]) => { const f=c=>{c/=255;return c<=0.03928?c/12.92:((c+0.055)/1.055)**2.4;}; return 0.2126*f(r)+0.7152*f(g)+0.0722*f(b); };
const ratio = (a,b) => { const [l1,l2]=[lum(a),lum(b)].sort((x,y)=>y-x); return (l1+0.05)/(l2+0.05); };
const parse = s => { const m=/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(s); return m?[+m[1],+m[2],+m[3],m[4]===undefined?1:+m[4]]:null; };

const PORT = process.argv[2] ?? 4173;
const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const routes = ['start','wizard','analyser','matrix','upgrade','inventory','machine','trade','system','detect','data','health'];
const issues = { contrast: [], labels: [], headings: [], tables: [], buttons: [], landmarks: [], focus: [] };

for (const r of routes) {
  const p = await b.newPage({ viewport: { width: 1400, height: 1000 } });
  await p.goto(`http://localhost:${PORT}/#/${r}`, { waitUntil: 'networkidle' });
  await p.waitForTimeout(500);

  const found = await p.evaluate(() => {
    const out = { texts: [], unlabelled: [], headings: [], tables: [], iconButtons: [], landmarks: {} };
    // effective background, walking up through transparent ancestors
    const bgOf = (el) => {
      let n = el;
      while (n && n !== document.documentElement) {
        const c = getComputedStyle(n).backgroundColor;
        const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(c);
        if (m && (m[4] === undefined || +m[4] > 0.5)) return c;
        n = n.parentElement;
      }
      return 'rgb(10,12,14)';
    };
    for (const el of document.querySelectorAll('main *')) {
      const t = [...el.childNodes].filter(n => n.nodeType === 3).map(n => n.textContent.trim()).join('');
      if (!t || t.length < 3) continue;
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none') continue;
      // An element inside a hidden ancestor is not on screen either. Without
      // this the print-only header — correct at 7.5:1 on white paper — was
      // reported as a screen contrast failure on every run.
      if (!el.getClientRects().length) continue;
      const size = parseFloat(cs.fontSize);
      const weight = parseInt(cs.fontWeight) || 400;
      out.texts.push({ fg: cs.color, bg: bgOf(el), size, weight, sample: t.slice(0, 45), tag: el.tagName });
    }
    for (const el of document.querySelectorAll('input, select, textarea')) {
      const id = el.id;
      const hasLabel = (id && document.querySelector(`label[for="${id}"]`)) || el.closest('label')
        || el.getAttribute('aria-label') || el.getAttribute('aria-labelledby') || el.getAttribute('placeholder')
        || el.closest('.field')?.querySelector('label');
      if (!hasLabel) out.unlabelled.push({ tag: el.tagName, type: el.type ?? '', cls: el.className });
    }
    out.headings = [...document.querySelectorAll('main h1,main h2,main h3,main h4')].map(h => +h.tagName[1]);
    for (const tb of document.querySelectorAll('main table')) {
      out.tables.push({ hasHeaders: tb.querySelectorAll('th').length > 0, scoped: tb.querySelectorAll('th[scope]').length, ths: tb.querySelectorAll('th').length });
    }
    for (const el of document.querySelectorAll('main button, header button')) {
      const txt = (el.innerText || '').trim();
      if (txt.length <= 2 && !el.getAttribute('aria-label') && !el.getAttribute('title')) {
        out.iconButtons.push({ txt, cls: el.className });
      }
    }
    out.landmarks = { main: !!document.querySelector('main'), nav: !!document.querySelector('nav'), header: !!document.querySelector('header'), h1: document.querySelectorAll('main h1').length };
    return out;
  });

  for (const t of found.texts) {
    const fg = parse(t.fg), bg = parse(t.bg);
    if (!fg || !bg) continue;
    const cr = ratio(fg.slice(0,3), bg.slice(0,3));
    const large = t.size >= 24 || (t.size >= 18.66 && t.weight >= 700);
    const need = large ? 3.0 : 4.5;
    if (cr < need) issues.contrast.push({ route: r, cr: cr.toFixed(2), need, size: t.size, sample: t.sample, fg: t.fg });
  }
  for (const u of found.unlabelled) issues.labels.push({ route: r, ...u });
  for (const tb of found.tables) if (!tb.hasHeaders) issues.tables.push({ route: r, issue: 'table with no th' });
  for (const ib of found.iconButtons) issues.buttons.push({ route: r, ...ib });
  if (found.landmarks.h1 !== 1) issues.headings.push({ route: r, h1count: found.landmarks.h1 });
  if (!found.landmarks.main || !found.landmarks.nav) issues.landmarks.push({ route: r, ...found.landmarks });
  await p.close();
}

// focus visibility
const p = await b.newPage({ viewport: { width: 1400, height: 1000 } });
await p.goto(`http://localhost:${PORT}/#/analyser`, { waitUntil: 'networkidle' });
// Must be a real Tab press. Calling .focus() does NOT match :focus-visible in
// Chromium — that is the whole point of the pseudo-class — so a programmatic
// check reports "no focus ring" on a page that has one.
let ringed = 0;
let stops = 0;
for (let i = 0; i < 10; i++) {
  await p.keyboard.press('Tab');
  const r = await p.evaluate(() => {
    const el = document.activeElement;
    if (!el || el === document.body) return null;
    const cs = getComputedStyle(el);
    return cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0;
  });
  if (r === null) continue;
  stops++;
  if (r) ringed++;
}
const focusOk = `${ringed}/${stops} keyboard tab stops show a focus ring`;
await p.close();
await b.close();

const uniq = (arr, k) => { const s=new Map(); for (const x of arr) s.set(k(x), x); return [...s.values()]; };
console.log('=== CONTRAST (WCAG AA) ===');
const c = uniq(issues.contrast, x => x.fg + x.size).sort((a,b)=>a.cr-b.cr);
console.log(`${issues.contrast.length} failing text nodes, ${c.length} distinct colour/size combinations`);
for (const x of c.slice(0, 12)) console.log(`  ${x.cr} (need ${x.need})  ${x.size}px  ${x.fg}  "${x.sample}"  [${x.route}]`);
console.log('\n=== FORM LABELS ===', issues.labels.length ? uniq(issues.labels, x=>x.cls).slice(0,8) : 'all inputs labelled');
console.log('=== TABLES ===', issues.tables.length ? issues.tables : 'all tables have headers');
console.log('=== ICON BUTTONS WITHOUT NAMES ===', issues.buttons.length ? uniq(issues.buttons, x=>x.cls) : 'all buttons named');
console.log('=== HEADINGS (want exactly one h1) ===', issues.headings.length ? issues.headings : 'ok on every route');
console.log('=== LANDMARKS ===', issues.landmarks.length ? issues.landmarks : 'main + nav present everywhere');
console.log('=== FOCUS RING ===', focusOk);
