import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';

const builds = JSON.parse(readFileSync('marketing/builds.json', 'utf8'));
const bottleneck = JSON.parse(readFileSync('marketing/bottleneck.json', 'utf8'));
const pillars = JSON.parse(readFileSync('marketing/pillars.json', 'utf8'));
/** Card ages are stated on the card, so they have to move with the calendar. */
const NOW = new Date().getFullYear();
const fonts = readFileSync('src/ui/fonts.css', 'utf8');

const P = {
  bg: '#0a0d12', surface: '#10151c', surface2: '#161d26', line: '#242e3a', lineStrong: '#34404e',
  ink: '#e6ecf3', muted: '#9aabbc', faint: '#83919f', accent: '#8ec1ee',
  good: '#5ec27a', gpu: '#62a8dd', cpu: '#d3a34a', spec: '#c2a04c',
};

const css = `
${fonts}
*{margin:0;padding:0;box-sizing:border-box}
body{width:1080px;height:1350px;background:${P.bg};color:${P.ink};
  font-family:'IBM Plex Sans',system-ui,sans-serif;-webkit-font-smoothing:antialiased;
  display:flex;flex-direction:column;padding:64px 60px 52px;position:relative;overflow:hidden}
body::after{content:'';position:absolute;inset:0;
  background:radial-gradient(900px 620px at 78% -8%, rgba(142,193,238,.10), transparent 62%);pointer-events:none}
.brandrow{display:flex;align-items:center;gap:12px;margin-bottom:44px}
.mark{width:32px;height:32px;flex:none}
.wordmark{font-family:'IBM Plex Mono',monospace;font-size:19px;font-weight:500;letter-spacing:.24em}
.kicker{margin-left:auto;font-family:'IBM Plex Mono',monospace;font-size:13px;letter-spacing:.18em;
  text-transform:uppercase;color:${P.faint}}
h1{font-size:78px;line-height:.98;font-weight:600;letter-spacing:-.028em;margin-bottom:16px}
h1 em{font-style:normal;color:${P.accent}}
.sub{font-size:23px;line-height:1.42;color:${P.muted};margin-bottom:40px;max-width:850px}
.parts{display:flex;gap:14px;margin-bottom:34px}
.part{flex:1;background:${P.surface};border:1px solid ${P.line};border-radius:12px;padding:20px 22px}
.part .k{font-family:'IBM Plex Mono',monospace;font-size:11.5px;letter-spacing:.16em;text-transform:uppercase;
  color:${P.faint};margin-bottom:9px}
.part .v{font-size:26px;font-weight:600;line-height:1.18;letter-spacing:-.012em}
.part .d{font-family:'IBM Plex Mono',monospace;font-size:13.5px;color:${P.muted};margin-top:8px}
.rows{background:${P.surface};border:1px solid ${P.line};border-radius:12px;overflow:hidden;margin-bottom:auto}
.row{display:flex;align-items:center;gap:18px;padding:25px 26px;border-bottom:1px solid ${P.line}}
.row:last-child{border-bottom:none}
.row .g{flex:1;font-size:23px;font-weight:500;display:flex;align-items:center;gap:11px}
.cap{font-family:'IBM Plex Mono',monospace;font-size:11px;letter-spacing:.1em;text-transform:uppercase;
  color:${P.spec};border:1px solid ${P.spec}55;border-radius:4px;padding:3px 7px}
.strip{display:flex;gap:26px;margin-bottom:30px;padding:0 4px}
.strip div{font-family:'IBM Plex Mono',monospace;font-size:15px;color:${P.faint}}
.strip b{color:${P.muted};font-weight:400}
.row .bar{width:210px;height:9px;background:${P.surface2};border-radius:5px;overflow:hidden}
.row .bar i{display:block;height:100%;background:${P.accent};border-radius:5px}
.row .n{width:120px;text-align:right;font-family:'IBM Plex Mono',monospace;font-size:31px;font-weight:500;
  letter-spacing:-.02em}
.row .n span{font-size:14px;color:${P.faint};margin-left:4px;letter-spacing:0}
.foot{display:flex;align-items:flex-end;gap:20px;margin-top:34px;padding-top:24px;border-top:1px solid ${P.line}}
.foot .note{flex:1;font-size:16px;line-height:1.5;color:${P.faint}}
.foot .note b{color:${P.spec};font-weight:600}
.price{text-align:right;flex:none}
.price .k{font-family:'IBM Plex Mono',monospace;font-size:11.5px;letter-spacing:.16em;
  text-transform:uppercase;color:${P.faint};margin-bottom:4px}
.price .v{font-family:'IBM Plex Mono',monospace;font-size:56px;font-weight:600;letter-spacing:-.03em;color:${P.ink}}
.why{display:flex;gap:16px;margin-top:34px}
.why div{flex:1;border-left:2px solid ${P.accent}66;padding:4px 0 4px 16px}
.why .t{font-size:19px;font-weight:600;margin-bottom:6px}
.why .b{font-size:16px;line-height:1.48;color:${P.faint}}
/* One game per row, four processors across it.
   No bars. Each row scaled to its own maximum, which is the only scaling that
   fits four numbers spanning 60 to 366 on one card — and under that scaling
   Factorio's four identical 60s rendered as four full-height bars, which reads
   as "maxed out" when it means "the engine is capped and the processor changes
   nothing". The gain column is the story; geometry that argues with it is
   worse than no geometry. */
.bn{display:flex;align-items:center;gap:16px;padding:24px;border-bottom:1px solid ${P.line}}
.bn:last-child{border-bottom:none}
.bn .g{width:250px;font-size:21px;font-weight:500;display:flex;align-items:center;gap:9px;flex:none}
.bn .pts{flex:1;display:flex;align-items:baseline;gap:8px}
.bn .pt{flex:1;text-align:center;font-family:'IBM Plex Mono',monospace;font-size:26px;color:${P.muted}}
.bn .pt.top{color:${P.accent};font-weight:500}
.bn .arrow{color:${P.faint};font-size:16px;flex:none}
.bn .gain{width:96px;text-align:right;font-family:'IBM Plex Mono',monospace;font-size:30px;font-weight:500;flex:none}
.bn .gain.nil{color:${P.faint}}
.legend{display:flex;gap:10px;margin-bottom:26px;padding:0 4px}
.legend div{flex:1;font-family:'IBM Plex Mono',monospace;font-size:13px;color:${P.faint};text-align:center}
/* before → after, with the gain carrying the emphasis */
.ba{display:flex;align-items:center;gap:18px;padding:26px 24px;border-bottom:1px solid ${P.line}}
.ba:last-child{border-bottom:none}
.ba .g{flex:1;font-size:22px;font-weight:500}
.ba .n{font-family:'IBM Plex Mono',monospace;font-size:28px;color:${P.faint};width:78px;text-align:right}
.ba .n.to{color:${P.good};font-weight:500}
.ba .arrow{color:${P.faint};font-size:17px}
.ba .gain{width:104px;text-align:right;font-family:'IBM Plex Mono',monospace;font-size:32px;font-weight:600;color:${P.good}}
.ba .gain.nil{color:${P.faint};font-weight:400}
.big{font-family:'IBM Plex Mono',monospace;font-size:150px;font-weight:600;letter-spacing:-.05em;
  line-height:1;color:${P.good};margin:10px 0 6px}
.big.warn{color:${P.spec}}
.card-note{font-size:20px;line-height:1.5;color:${P.muted};max-width:880px;margin-bottom:30px}
`;

const MARK = `<svg class="mark" viewBox="0 0 20 20">
  <circle cx="10" cy="10" r="8.1" fill="none" stroke="${P.ink}" stroke-opacity=".5" stroke-width="1.5"/>
  <path d="M10 1.9v2.3M18.1 10h-2.3M10 18.1v-2.3M1.9 10h2.3" stroke="${P.ink}" stroke-opacity=".5" stroke-width="1.3" stroke-linecap="round"/>
  <path d="M10 10l3.4-3.4" stroke="${P.accent}" stroke-width="1.8" stroke-linecap="round"/>
  <circle cx="10" cy="10" r="1.7" fill="${P.accent}"/></svg>`;

const esc = (s) => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');

function buildCard(b) {
  const shown = b.rows.filter(r => r.fps).slice(0, 6);
  const max = Math.max(...shown.map(r => r.fps));
  return `<div class="brandrow">${MARK}<span class="wordmark">RIGCHECK</span>
    <span class="kicker">£${b.budget} budget · ${b.resolution} ${b.refreshHz}Hz</span></div>
  <h1>£${b.total.toLocaleString()}<br><em>gaming PC</em></h1>
  <div class="sub">${esc(b.gpu)} paired with a ${esc(b.cpuShort)}. Estimated frame rates at ${b.resolution}, high preset, no upscaling.</div>
  <div class="parts">
    <div class="part"><div class="k">graphics</div><div class="v">${esc(b.gpuShort)}</div><div class="d">${b.vram}GB VRAM</div></div>
    <div class="part"><div class="k">processor</div><div class="v">${esc(b.cpuShort)}</div><div class="d">${b.cores}C / ${b.threads}T</div></div>
    <div class="part"><div class="k">power</div><div class="v">${b.powerW}W</div><div class="d">${b.psuW}W PSU</div></div>
  </div>
  <div class="strip"><div>memory <b>${esc(b.ram)}</b></div><div>storage <b>${esc(b.storage)}</b></div>
    <div>1% lows <b>${Math.min(...shown.map(r => r.low1))}–${Math.max(...shown.map(r => r.low1))} fps</b></div></div>
  <div class="rows">${shown.map(r => `<div class="row">
      <span class="g">${esc(r.game)}${r.limiter === 'engine-cap' ? '<span class="cap">engine cap</span>' : ''}</span>
      <span class="bar"><i style="width:${Math.round((r.fps / max) * 100)}%"></i></span>
      <span class="n">${r.fps}<span>fps</span></span></div>`).join('')}</div>
  <div class="foot">
    <div class="note"><b>Modelled, not measured.</b> These come from a physics-and-specification model,
      not from a benchmark run on this exact machine. Treat the ordering as reliable and the absolute
      numbers as ±20%.</div>
    <div class="price"><div class="k">under budget by</div><div class="v">£${(b.budget - b.total).toLocaleString()}</div></div>
  </div>`;
}


function bottleneckCard(s) {
  const shown = s.games.slice(0, 6);
  return `<div class="brandrow">${MARK}<span class="wordmark">RIGCHECK</span>
    <span class="kicker">${esc(s.gpuShort)} · ${s.resolution} · high</span></div>
  <h1>Does your CPU<br><em>actually matter?</em></h1>
  <div class="sub">Same graphics card throughout. Four processors underneath it. The answer changes
    completely depending on which game you are asking about.</div>
  <div class="legend">${s.cpus.map((c) => `<div>${esc(c.replace('Ryzen ', 'R'))}</div>`).join('')}
    <div style="width:96px;flex:none;text-align:right">GAIN</div></div>
  <div class="rows" style="margin-bottom:auto">${shown.map((g) => {
    const ok = g.points.filter((p) => p.fps);
    const max = Math.max(...ok.map((p) => p.fps));
    const capped = g.points.every((p) => p.limiter === 'engine-cap');
    // Nothing is "best" in a row where every value is the same — highlighting
    // all four put the accent on the one row where the processor changes
    // nothing, which is the opposite of what the colour is for.
    const flat = ok.length > 1 && ok.every((p) => p.fps === ok[0].fps);
    return `<div class="bn">
      <span class="g">${esc(g.game)}${capped ? '<span class="cap">engine cap</span>' : ''}</span>
      <span class="pts">${g.points.map((p, i) => `${i ? '<span class="arrow">→</span>' : ''}<span class="pt${!flat && p.fps === max ? ' top' : ''}">${p.fps ?? '—'}</span>`).join('')}</span>
      <span class="gain ${g.gainPct < 5 ? 'nil' : ''}">${g.gainPct > 0 ? '+' : ''}${g.gainPct}%</span>
    </div>`;
  }).join('')}</div>
  <div class="foot">
    <div class="note"><b>Modelled, not measured.</b> The spread on the right is what swapping
      ${esc(s.cpus[0])} for ${esc(s.cpus[s.cpus.length - 1])} is worth in that game, on this card, at this
      resolution. Change any of the three and the answer changes.</div>
  </div>`;
}


function silentTaxCard(t) {
  const best = t.rows[0];
  return `<div class="brandrow">${MARK}<span class="wordmark">RIGCHECK</span>
    <span class="kicker">${esc(t.gpu)} · ${esc(t.cpu)} · ${t.resolution}</span></div>
  <h1>Your PC might be<br><em>losing 40% for free</em></h1>
  <div class="card-note">One stick of RAM instead of two. Same capacity, same speed, same everything else —
    the memory just runs on one channel instead of two. It is the most common invisible fault there is,
    and fixing it costs nothing if you already own the second stick.</div>
  <div class="rows" style="margin-bottom:auto">${t.rows.map((r) => `<div class="ba">
      <span class="g">${esc(r.game)}</span>
      <span class="n">${r.before}</span><span class="arrow">→</span><span class="n to">${r.after}</span>
      <span class="gain ${r.gainPct < 5 ? 'nil' : ''}">+${r.gainPct}%</span></div>`).join('')}</div>
  <div class="foot"><div class="note"><b>Modelled, not measured.</b> Check yours: Task Manager →
    Performance → Memory, and look for "Slots used". If it says 1 of 2 or 1 of 4 and you have a spare
    stick, that is ${best.gainPct}% in ${esc(best.game)} sitting in a drawer.</div></div>`;
}

function vramCard(v) {
  const at4k = v.rows.find((r) => r.resolution === '2160p');
  return `<div class="brandrow">${MARK}<span class="wordmark">RIGCHECK</span>
    <span class="kicker">${esc(v.a.brand)} · 8GB vs 16GB · Cyberpunk 2077</span></div>
  <h1>Twice the VRAM.<br><em>Zero extra frames.</em></h1>
  <div class="card-note">The same chip, sold with 8GB and with 16GB. At the resolutions most people
    actually play at, the extra memory does exactly nothing — because the card runs out of shader
    performance long before it runs out of memory.</div>
  <div class="rows" style="margin-bottom:auto">${v.rows.map((r) => `<div class="ba">
      <span class="g">${r.resolution}${r.vramWall ? '<span class="cap">8GB runs out</span>' : ''}</span>
      <span class="n">${r.a}</span><span class="arrow">→</span><span class="n ${r.gainPct ? 'to' : ''}">${r.b}</span>
      <span class="gain ${r.gainPct < 5 ? 'nil' : ''}">${r.gainPct ? '+' : ''}${r.gainPct}%</span></div>`).join('')}</div>
  <div class="why">
    <div><div class="t">Why it happens</div><div class="b">A card runs out of shader throughput long before it runs out of memory. Extra VRAM only helps once memory is the thing you ran out of.</div></div>
    <div><div class="t">When it does matter</div><div class="b">4K, heavy texture mods, ray tracing, and anything that streams a lot of assets. That is a real list — it is just not most people.</div></div>
    <div><div class="t">What to buy instead</div><div class="b">At the same money, a faster chip with less memory beats a slower chip with more, unless you are at 4K.</div></div>
  </div>
  <div class="foot"><div class="note"><b>Modelled, not measured.</b> The 16GB card earns its money at 4K
    and only at 4K — ${at4k ? `+${at4k.gainPct}% there, and the 8GB version hits a memory wall` : 'where the 8GB version runs out'}.
    Below that you are paying for headroom you cannot use.</div></div>`;
}

function stillGoodCard(s) {
  return `<div class="brandrow">${MARK}<span class="wordmark">RIGCHECK</span>
    <span class="kicker">${s.resolution} · high · ${esc(s.cpu)}</span></div>
  <h1>Is your old card<br><em>still good?</em></h1>
  <div class="card-note">Four cards between ${Math.min(...s.cards.map((c) => NOW - Number(c.year)))} and
    ${Math.max(...s.cards.map((c) => NOW - Number(c.year)))} years old, against three games people are
    playing now. Modern shooters are kinder to old hardware than anyone expects; one 2020 title is where
    they stop.</div>
  <div class="legend"><div style="flex:1;text-align:left">&nbsp;</div>
    ${s.cards[0].rows.map((r) => `<div>${esc(r.game.split(' ')[0].toUpperCase())}</div>`).join('')}</div>
  <div class="rows" style="margin-bottom:auto">${s.cards.map((c) => `<div class="bn">
      <span class="g">${esc(c.gpu)}<span style="color:${P.faint};font-size:15px">${c.year} · ${c.vram}GB</span></span>
      <span class="pts">${c.rows.map((r) => `<span class="pt${r.fps && r.fps >= 60 ? ' top' : ''}">${r.fps ?? '—'}</span>`).join('')}</span>
    </div>`).join('')}</div>
  <div class="why">
    <div><div class="t">Check which one you own</div><div class="b">The 1060 shipped as 3GB and 6GB with
      different shader counts, and the RX 580 as 4GB and 8GB. On a chart about where the line falls, the
      memory size is half the answer.</div></div>
    <div><div class="t">Below minimum still starts</div><div class="b">The 970's 4GB is under Cyberpunk's
      published 6GB. That costs frames, not the launch — 21fps is running, and running is not the same as
      playable.</div></div>
  </div>
  <div class="foot"><div class="note"><b>Modelled, not measured.</b> Every card here still clears 60 in a
    shooter, which is most of what most people play. Cyberpunk is where they separate:
    ${Math.min(...s.cards.flatMap((c) => c.rows.filter((r) => /cyberpunk/i.test(r.game) && r.fps).map((r) => r.fps)))}
    to ${Math.max(...s.cards.flatMap((c) => c.rows.filter((r) => /cyberpunk/i.test(r.game) && r.fps).map((r) => r.fps)))}fps
    across four cards people mention in the same breath. A dash would mean the card cannot run that game
    at all.</div></div>`;
}

function coverCard() {
  return `<div class="brandrow">${MARK}<span class="wordmark">RIGCHECK</span>
    <span class="kicker">2026 build guide</span></div>
  <h1>Four PC builds,<br><em>and what they<br>actually do</em></h1>
  <div class="sub">£582 to £2,012. Every frame rate below comes out of an open model that shows its
    working — and tells you where it is guessing.</div>
  <div class="rows" style="margin-bottom:auto">
    ${builds.map(b => `<div class="row">
        <span class="g">£${b.total.toLocaleString()}<span style="color:${P.faint};font-size:17px;font-weight:400">${esc(b.gpuShort)}</span></span>
        <span class="bar"><i style="width:${Math.round((b.cyberpunk1440 / Math.max(...builds.map(x => x.cyberpunk1440))) * 100)}%"></i></span>
        <span class="n">${b.cyberpunk1440}<span>fps</span></span></div>`).join('')}
  </div>
  <div class="why">
    <div><div class="t">Every number opens</div><div class="b">Tap any frame rate and it shows the terms, the multipliers and where each came from.</div></div>
    <div><div class="t">It argues with itself</div><div class="b">One screen exists to tell you how much of the model is measured and how much is recalled.</div></div>
    <div><div class="t">No affiliate links</div><div class="b">Nothing here is sponsored and no part is promoted. The planner picks on price and fit.</div></div>
  </div>
  <div class="foot"><div class="note">All four running <b style="color:${P.muted}">Cyberpunk 2077 at 1440p</b>,
    high preset, no upscaling — the same test on every build, so the ladder means something. Swipe for
    each one in full: parts, power draw and six games apiece.</div></div>`;
}

const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1080, height: 1350 }, deviceScaleFactor: 2 });
const shots = [];
const cards = [
  { name: '01-cover', html: coverCard() },
  ...builds.map((b, i) => ({ name: `0${i + 2}-${b.budget}`, html: buildCard(b) })),
  ...bottleneck.map((s, i) => ({ name: `bottleneck-${i + 1}-${s.gpuShort.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`, html: bottleneckCard(s) })),
  { name: 'silent-tax-memory-channels', html: silentTaxCard(pillars.silentTax) },
  ...(pillars.vram ? [{ name: 'myth-vram', html: vramCard(pillars.vram) }] : []),
  { name: 'still-good-old-cards', html: stillGoodCard(pillars.stillGood) },
];
for (const c of cards) {
  await page.setContent(`<style>${css}</style>${c.html}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(300);
  const f = `marketing/images/${c.name}.png`;
  await page.screenshot({ path: f });
  shots.push(f);
}
await browser.close();
console.log(shots.join('\n'));
