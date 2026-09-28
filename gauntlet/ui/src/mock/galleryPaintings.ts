/**
 * Demo-mode placeholder paintings for The Gallery (made locally, no downloads): one procedural SVG scene per
 * commission brief, painted with layered gradients, a displacement "brush" filter and canvas grain. Each scene
 * reports which required elements it actually drew and which rules it broke, so the mock judges' checklist
 * matches what a viewer can see.
 */

export type MockQuality = 'master' | 'good' | 'crude';

export interface MockPainting {
  svg: string;
  /** Required elements (E1..E6) visible in the picture. */
  drawn: Set<string>;
  /** Rules (N1..N3) the picture breaks. */
  broken: Set<string>;
}

export const W = 1536;
export const H = 1024;

function rngOf(seed: string) {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  let s = h >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

type Rand = () => number;
const f = (n: number) => Math.round(n * 10) / 10;

/** A smooth ridge line across the canvas (for hills, mountains, shores). */
function ridge(r: Rand, y: number, amp: number, steps = 8, bottom = H): string {
  const pts: Array<[number, number]> = [];
  for (let i = 0; i <= steps; i++) pts.push([(i / steps) * W, y + (r() - 0.5) * 2 * amp]);
  let d = `M0 ${bottom} L0 ${f(pts[0]![1])}`;
  for (let i = 1; i < pts.length; i++) {
    const [x0, y0] = pts[i - 1]!;
    const [x1, y1] = pts[i]!;
    d += ` C${f(x0 + (x1 - x0) / 2)} ${f(y0)} ${f(x0 + (x1 - x0) / 2)} ${f(y1)} ${f(x1)} ${f(y1)}`;
  }
  return `${d} L${W} ${bottom} Z`;
}

/** Many short strokes: the "dabs" of an oil painting. */
function dabs(r: Rand, n: number, box: [number, number, number, number], colors: string[], len = [18, 60], thick = [3, 8], angle = 0, opacity = 0.7): string {
  let s = '';
  const [x, y, w, h] = box;
  for (let i = 0; i < n; i++) {
    const cx = x + r() * w;
    const cy = y + r() * h;
    const l = len[0]! + r() * (len[1]! - len[0]!);
    const t = thick[0]! + r() * (thick[1]! - thick[0]!);
    const a = angle + (r() - 0.5) * 16;
    s += `<rect x="${f(cx - l / 2)}" y="${f(cy - t / 2)}" width="${f(l)}" height="${f(t)}" rx="${f(t / 2)}" fill="${colors[Math.floor(r() * colors.length)]}" opacity="${f(opacity * (0.6 + r() * 0.4))}" transform="rotate(${f(a)} ${f(cx)} ${f(cy)})"/>`;
  }
  return s;
}

function defs(seed: number, quality: MockQuality): string {
  const scale = quality === 'master' ? 9 : quality === 'good' ? 6 : 2;
  return `<defs>
<filter id="brush" x="-5%" y="-5%" width="110%" height="110%"><feTurbulence type="fractalNoise" baseFrequency="0.018 0.045" numOctaves="3" seed="${seed}" result="n"/><feDisplacementMap in="SourceGraphic" in2="n" scale="${scale}" xChannelSelector="R" yChannelSelector="G"/></filter>
<filter id="grain" x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="2" seed="${seed + 7}"/><feColorMatrix type="matrix" values="0 0 0 0 0.35  0 0 0 0 0.3  0 0 0 0 0.24  0 0 0 0.55 0"/></filter>
<filter id="soft" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="14"/></filter>
<filter id="glow" x="-80%" y="-80%" width="260%" height="260%"><feGaussianBlur stdDeviation="30"/></filter>
<radialGradient id="vignette" cx="50%" cy="48%" r="75%"><stop offset="0.55" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="${quality === 'crude' ? 0 : 0.42}"/></radialGradient>
</defs>`;
}

function finish(body: string, seed: number, quality: MockQuality, extras = ''): string {
  // Each artist's palette drifts a little (hue and saturation), so the same commission looks like different hands.
  const hue = (seed % 37) - 18;
  const sat = 0.85 + ((seed >> 3) % 30) / 100;
  body = `<filter id="hand"><feColorMatrix type="hueRotate" values="${hue}"/><feColorMatrix type="saturate" values="${sat.toFixed(2)}"/></filter><g filter="url(#hand)">${body}</g>`;
  const texture = quality === 'crude' ? '' : `<rect width="${W}" height="${H}" filter="url(#grain)" opacity="${quality === 'master' ? 0.5 : 0.35}" style="mix-blend-mode:multiply"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${defs(seed, quality)}<g filter="${quality === 'crude' ? '' : 'url(#brush)'}">${body}</g>${texture}<rect width="${W}" height="${H}" fill="url(#vignette)"/>${extras}</svg>`;
}

/** Rule-breaking extras some demo artists add: a signature (text), or a painted frame. */
function signature(r: Rand): string {
  const x = W - 260 + r() * 40;
  return `<path d="M${f(x)} ${H - 58} q 18 -30 34 0 t 30 -4 q 10 -22 22 2 t 40 -6 m -110 14 l 150 -8" fill="none" stroke="#2a1a10" stroke-width="3.5" stroke-linecap="round" opacity="0.8"/>`;
}
function paintedFrame(): string {
  return `<rect x="10" y="10" width="${W - 20}" height="${H - 20}" fill="none" stroke="#6b4a1f" stroke-width="20"/><rect x="26" y="26" width="${W - 52}" height="${H - 52}" fill="none" stroke="#c9a24a" stroke-width="6"/>`;
}

// ───────────────────────────── Figures ─────────────────────────────

function figureFromBehind(x: number, y: number, s: number, coat: string): string {
  return `<g transform="translate(${f(x)} ${f(y)}) scale(${f(s)})"><path d="M-22 0 C-26 -60 -24 -110 -16 -128 L16 -128 C24 -110 26 -60 22 0 Z" fill="${coat}"/><circle cx="0" cy="-142" r="15" fill="#2b221c"/><path d="M-14 -150 q 14 -18 28 0" fill="#3a2a1e"/><line x1="26" y1="0" x2="36" y2="-150" stroke="#3b2a1a" stroke-width="4"/></g>`;
}
function seatedWoman(x: number, y: number, dress: string, hair: string, skin: string, book = true): string {
  return `<g transform="translate(${f(x)} ${f(y)})"><path d="M-70 120 C-90 40 -60 -40 -30 -70 L30 -70 C60 -40 90 40 110 120 Z" fill="${dress}"/><path d="M-26 -70 C-30 -110 30 -110 26 -70 Z" fill="${dress}" opacity="0.9"/><ellipse cx="0" cy="-122" rx="26" ry="31" fill="${skin}"/><path d="M-28 -128 C-30 -170 30 -170 30 -126 C24 -150 -20 -152 -28 -128 Z" fill="${hair}"/><circle cx="20" cy="-150" r="15" fill="${hair}"/>${book ? `<path d="M-40 -30 L0 -44 L40 -30 L40 -6 L0 -20 L-40 -6 Z" fill="#f3e6c4"/><path d="M0 -44 L0 -20" stroke="#b69b6a" stroke-width="2"/>` : ''}<path d="M-46 -24 q 10 -14 22 -6 M46 -24 q -10 -14 -22 -6" stroke="${skin}" stroke-width="12" stroke-linecap="round" fill="none"/></g>`;
}

// ───────────────────────────── Scenes ─────────────────────────────

type Scene = (r: Rand, q: MockQuality, drop: Set<string>) => string;

const keeper: Scene = (r, q, drop) => {
  const lamp = [1050, 560];
  let s = `<rect width="${W}" height="${H}" fill="#2a1d14"/><rect width="${W}" height="${H}" fill="url(#roomlight)"/>`;
  s += `<defs><radialGradient id="roomlight" cx="${lamp[0]! / W}" cy="${lamp[1]! / H}" r="0.75"><stop offset="0" stop-color="#e9b36a" stop-opacity="0.85"/><stop offset="0.35" stop-color="#8a5a2c" stop-opacity="0.55"/><stop offset="1" stop-color="#1a110b" stop-opacity="0"/></radialGradient><linearGradient id="dusk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1d2f57"/><stop offset="0.55" stop-color="#4d6a9c"/><stop offset="0.75" stop-color="#c58a74"/><stop offset="1" stop-color="#26405e"/></linearGradient></defs>`;
  // back wall and floor
  s += `<rect y="${H * 0.72}" width="${W}" height="${H * 0.28}" fill="#3b2818"/>`;
  if (q !== 'crude') for (let i = 0; i < 9; i++) s += `<path d="M${i * 190 - 60} ${H} L${i * 190 + 30} ${H * 0.72}" stroke="#2a1b10" stroke-width="3" opacity="0.5"/>`;
  // window (left)
  s += `<rect x="90" y="120" width="360" height="560" fill="#1a120b"/><rect x="110" y="140" width="320" height="520" fill="url(#dusk)"/>`;
  s += `<path d="M110 520 C200 500 300 540 430 510 L430 660 L110 660 Z" fill="#1c2c45"/>`;
  if (!drop.has('E3')) s += `<path d="M150 560 L230 520 L300 560 Z" fill="#2c2620"/><path d="M195 530 L205 380 L235 380 L245 530 Z" fill="#e8e1d2"/><rect x="200" y="360" width="40" height="22" fill="#3a3025"/><circle cx="220" cy="350" r="12" fill="#ffe7a1"/><circle cx="220" cy="350" r="40" fill="#ffd66b" opacity="0.5" filter="url(#glow)"/>${q === 'master' ? '<path d="M232 348 L430 300 L430 380 Z" fill="#fff2c2" opacity="0.28"/>' : ''}`;
  s += `<g stroke="#2a1d14" stroke-width="7">${[1, 2, 3].map((i) => `<line x1="${110 + i * 80}" y1="140" x2="${110 + i * 80}" y2="660"/>`).join('')}${[1, 2, 3, 4, 5].map((i) => `<line x1="110" y1="${140 + i * 87}" x2="430" y2="${140 + i * 87}"/>`).join('')}</g>`;
  s += `<rect x="70" y="660" width="420" height="36" fill="#5a3d24"/>`;
  if (!drop.has('E4')) s += `<g transform="translate(300 650)"><ellipse cx="0" cy="0" rx="62" ry="24" fill="#7d8088"/><circle cx="-54" cy="-10" r="20" fill="#858891"/><path d="M-68 -24 l6 -16 l8 14 M-48 -26 l6 -16 l6 16" fill="#858891"/><path d="M58 4 q 30 6 20 -16" stroke="#7d8088" stroke-width="10" fill="none" stroke-linecap="round"/><path d="M-62 -10 q 4 3 8 0 M-50 -10 q 4 3 8 0" stroke="#2a2a2a" stroke-width="2" fill="none"/></g>`;
  // woman
  s += `<path d="M470 110 C520 300 500 520 540 700 L600 700 C560 480 590 260 520 110 Z" fill="#6a2f24" opacity="0.85"/>`;
  s += `<rect x="610" y="470" width="16" height="250" fill="#3d2615"/><rect x="600" y="460" width="190" height="16" rx="6" fill="#4a2f1a"/>`;
  if (!drop.has('E1')) s += seatedWoman(700, 620, '#3c5a7a', '#5a3a22', '#e6c3a0');
  // table + lamp + jug
  s += `<path d="M880 640 L1300 640 L1340 700 L840 700 Z" fill="#5b3a20"/><rect x="870" y="700" width="16" height="200" fill="#3d2615"/><rect x="1300" y="700" width="16" height="200" fill="#3d2615"/>`;
  if (!drop.has('E5')) s += `<circle cx="${lamp[0]}" cy="${lamp[1]! - 40}" r="120" fill="#ffc768" opacity="0.35" filter="url(#glow)"/><path d="M1020 640 L1080 640 L1070 600 L1030 600 Z" fill="#b8892f"/><ellipse cx="1050" cy="600" rx="34" ry="12" fill="#d6a843"/><path d="M1038 598 L1034 520 Q1050 500 1066 520 L1062 598 Z" fill="#fff6d8" opacity="0.55"/><path d="M1050 540 q -8 -18 0 -34 q 8 16 0 34" fill="#ffb13b"/>`;
  if (!drop.has('E6')) s += `<path d="M1170 640 C1150 600 1160 560 1180 548 L1180 530 L1214 530 L1214 548 C1236 560 1244 600 1224 640 Z" fill="#eef0f4"/><path d="M1162 590 q 35 -14 70 0 M1166 612 q 32 -10 62 0" stroke="#2b4f9a" stroke-width="7" fill="none"/><path d="M1224 560 q 26 10 4 40" stroke="#eef0f4" stroke-width="8" fill="none"/>`;
  if (q === 'master') s += dabs(r, 70, [480, 60, W - 520, H * 0.6], ['#3a2616', '#4a321c', '#5a3c20'], [40, 120], [8, 16], -6, 0.18);
  return s;
};

const harbour: Scene = (r, q, drop) => {
  let s = `<defs><linearGradient id="dawn" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#a9a3cf"/><stop offset="0.45" stop-color="#f0b6b0"/><stop offset="0.62" stop-color="#f7d49a"/><stop offset="1" stop-color="#9fc9c0"/></linearGradient></defs>`;
  s += `<rect width="${W}" height="${H}" fill="url(#dawn)"/>`;
  if (!drop.has('E2')) s += `<circle cx="1050" cy="430" r="120" fill="#ffe0a6" opacity="0.6" filter="url(#glow)"/><circle cx="1050" cy="440" r="46" fill="#fff0c8"/>`;
  s += `<rect y="470" width="${W}" height="${H - 470}" fill="#8fbfb6"/>`;
  if (!drop.has('E4')) {
    s += `<rect x="0" y="420" width="${W}" height="52" fill="#b9a38a"/>`;
    for (let i = 0; i < 16; i++) {
      const x = i * 98 + r() * 20;
      const h = 60 + r() * 50;
      s += `<rect x="${f(x)}" y="${f(440 - h)}" width="84" height="${f(h)}" fill="${['#efe2cf', '#e8d6bd', '#f3e9d8'][i % 3]}"/><path d="M${f(x - 6)} ${f(440 - h)} L${f(x + 42)} ${f(440 - h - 34)} L${f(x + 90)} ${f(440 - h)} Z" fill="${['#c2553a', '#b8482f', '#cf6a45'][i % 3]}"/>`;
    }
  }
  if (!drop.has('E3')) s += dabs(r, q === 'crude' ? 60 : 420, [0, 480, W, H - 480], ['#f7d49a', '#f0b6b0', '#a9a3cf', '#6fa8a3', '#ffffff'], [16, 70], [3, 7], 0, 0.75);
  const boats = drop.has('E1') ? 2 : 3;
  for (let i = 0; i < boats; i++) {
    const x = 380 + i * 330 + r() * 40;
    const y = 600 + (i % 2) * 40;
    s += `<path d="M${f(x - 90)} ${y} L${f(x + 90)} ${y} L${f(x + 64)} ${y + 34} L${f(x - 70)} ${y + 34} Z" fill="${['#3b4a6b', '#7a3b2e', '#2f5d50'][i]}"/><line x1="${f(x)}" y1="${y}" x2="${f(x)}" y2="${y - 200}" stroke="#4a3322" stroke-width="5"/><path d="M${f(x)} ${y - 180} q 8 60 0 150" stroke="#e9dccb" stroke-width="12" fill="none" stroke-linecap="round"/><path d="M${f(x - 90)} ${y + 40} L${f(x + 90)} ${y + 40}" stroke="${['#3b4a6b', '#7a3b2e', '#2f5d50'][i]}" stroke-width="10" opacity="0.35"/>`;
  }
  s += `<path d="M0 ${H} L0 800 L520 780 L600 ${H} Z" fill="#9a8a78"/>`;
  if (!drop.has('E5')) s += `<g transform="translate(260 800)"><path d="M-20 0 C-24 -50 -20 -90 -14 -104 L14 -104 C20 -90 24 -50 20 0 Z" fill="#46506e"/><circle cx="0" cy="-116" r="13" fill="#e2b999"/><ellipse cx="0" cy="-126" rx="36" ry="9" fill="#e7c86b"/><path d="M-14 -128 Q0 -150 14 -128 Z" fill="#e7c86b"/></g>`;
  if (!drop.has('E6')) for (let i = 0; i < (q === 'crude' ? 2 : 6); i++) {
    const x = 300 + r() * 1000;
    const y = 120 + r() * 200;
    s += `<path d="M${f(x - 20)} ${f(y)} q 10 -12 20 0 q 10 -12 20 0" stroke="#5a5566" stroke-width="3.5" fill="none" stroke-linecap="round"/>`;
  }
  return s;
};

const storm: Scene = (r, q, drop) => {
  let s = `<defs><linearGradient id="storm" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2c2f3d"/><stop offset="0.5" stop-color="#5b5f73"/><stop offset="1" stop-color="#9aa0ae"/></linearGradient><linearGradient id="beam" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ffe3a0" stop-opacity="0.75"/><stop offset="1" stop-color="#ffe3a0" stop-opacity="0"/></linearGradient></defs>`;
  s += `<rect width="${W}" height="${H}" fill="url(#storm)"/>`;
  for (let i = 0; i < (q === 'crude' ? 4 : 14); i++) s += `<ellipse cx="${f(r() * W)}" cy="${f(60 + r() * 260)}" rx="${f(160 + r() * 200)}" ry="${f(40 + r() * 60)}" fill="${['#3a3d4c', '#4b4f61', '#6c6f80'][i % 3]}" filter="url(#soft)" opacity="0.85"/>`;
  s += `<path d="${ridge(r, 470, 90, 7)}" fill="#6d7488"/>`;
  s += `<path d="M1040 470 L1160 250 L1290 470 Z" fill="#8f97aa"/><path d="M1122 320 L1160 250 L1198 320 L1178 306 L1160 328 L1140 304 Z" fill="#f4f1ea"/>`;
  if (!drop.has('E4')) s += `<path d="M880 0 L1000 0 L1200 330 L1130 330 Z" fill="url(#beam)"/><path d="M1120 320 L1160 250 L1200 320 Z" fill="#ffe7b0" opacity="0.8"/>`;
  s += `<path d="${ridge(r, 560, 60, 6)}" fill="#4f5566"/>`;
  if (!drop.has('E5')) s += `<g transform="translate(360 520)" fill="#2f3140"><rect x="-40" y="-70" width="18" height="70"/><path d="M-22 0 L-22 -60 Q0 -96 22 -60 L22 0 L14 0 L14 -52 Q0 -76 -14 -52 L-14 0 Z"/><rect x="30" y="-40" width="14" height="40"/><path d="M-40 -70 l9 -26 l9 26 Z"/></g>`;
  s += `<path d="M0 ${H} L0 700 C300 640 700 720 1000 690 C1200 670 1400 700 ${W} 680 L${W} ${H} Z" fill="#3b4150"/>`;
  s += `<path d="M560 700 C600 640 660 600 700 620 L760 ${H} L520 ${H} Z" fill="#4a5063"/>`;
  if (!drop.has('E3')) s += `<path d="M640 610 C646 660 636 720 650 800" stroke="#eef2f6" stroke-width="${q === 'crude' ? 8 : 16}" fill="none" opacity="0.9"/><ellipse cx="652" cy="810" rx="50" ry="14" fill="#e9eef3" opacity="0.7" filter="url(#soft)"/>`;
  s += `<path d="M0 ${H} L0 820 C160 790 320 800 470 850 L520 ${H} Z" fill="#1f2229"/>`;
  if (!drop.has('E6')) s += `<path d="M130 830 C150 700 190 610 270 560" stroke="#2c2118" stroke-width="16" fill="none"/>${[0, 1, 2, 3].map((i) => `<ellipse cx="${190 + i * 34}" cy="${640 - i * 26}" rx="${70 - i * 8}" ry="16" fill="#1e3326" transform="rotate(-18 ${190 + i * 34} ${640 - i * 26})"/>`).join('')}`;
  if (!drop.has('E1')) s += figureFromBehind(330, 830, 1.1, '#2a2d38');
  if (q !== 'crude') s += dabs(r, 140, [0, 0, W, 420], ['#4b4f61', '#6c6f80', '#3a3d4c'], [40, 120], [6, 16], -10, 0.35);
  return s;
};

const ukiyoe: Scene = (r, q, drop) => {
  const ink = '#1d1a24';
  let s = `<defs><linearGradient id="bokashi" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0f1c3d"/><stop offset="0.45" stop-color="#23417a"/><stop offset="1" stop-color="#6d86a8"/></linearGradient></defs>`;
  s += `<rect width="${W}" height="${H}" fill="url(#bokashi)"/>`;
  if (!drop.has('E4')) s += `<circle cx="1150" cy="190" r="86" fill="#f4ecd4"/><path d="M1020 210 C1100 190 1200 230 1300 200 L1300 236 C1200 262 1100 226 1020 246 Z" fill="#3c5487" opacity="0.9"/>`;
  if (!drop.has('E5')) s += `<path d="M560 520 L760 250 L960 520 Z" fill="#4c6c96" stroke="${ink}" stroke-width="4"/><path d="M700 330 L760 250 L820 330 L798 318 L780 338 L760 316 L740 340 L720 316 Z" fill="#f4f1ea" stroke="${ink}" stroke-width="3"/>`;
  s += `<rect y="520" width="${W}" height="${H - 520}" fill="#2d5a8c"/>`;
  s += `<path d="M0 520 L${W} 520" stroke="${ink}" stroke-width="4"/>`;
  for (let i = 0; i < 10; i++) s += `<path d="M${i * 170} ${600 + (i % 3) * 90} q 40 -14 80 0" stroke="#8fb3d8" stroke-width="4" fill="none"/>`;
  if (!drop.has('E1')) {
    s += `<path d="M180 620 Q 760 300 1360 620" stroke="#7a4b2a" stroke-width="44" fill="none"/><path d="M180 620 Q 760 300 1360 620" stroke="${ink}" stroke-width="3" fill="none" transform="translate(0 -22)"/><path d="M180 640 Q 760 322 1360 640" stroke="${ink}" stroke-width="3" fill="none"/>`;
    for (let i = 0; i < 7; i++) {
      const x = 300 + i * 160;
      const t = (x - 180) / 1180;
      const y = (1 - t) * (1 - t) * 620 + 2 * (1 - t) * t * 300 + t * t * 620;
      s += `<rect x="${x - 7}" y="${f(y + 18)}" width="14" height="${f(Math.max(0, 760 - y))}" fill="#5b3820" stroke="${ink}" stroke-width="2"/>`;
    }
  }
  if (!drop.has('E2')) for (let k = 0; k < 2; k++) {
    const x = 680 + k * 110;
    s += `<g transform="translate(${x} ${446 - k * 3})"><path d="M-14 0 L-18 -60 L18 -60 L14 0 Z" fill="${k ? '#b8412e' : '#3f5f4a'}" stroke="${ink}" stroke-width="2"/><ellipse cx="0" cy="-66" rx="26" ry="8" fill="#d9b86a" stroke="${ink}" stroke-width="2"/>${k === 0 ? `<path d="M-60 -92 Q0 -140 60 -92 Z" fill="#e7cf8f" stroke="${ink}" stroke-width="3"/><line x1="0" y1="-92" x2="0" y2="-40" stroke="${ink}" stroke-width="3"/>` : ''}</g>`;
  }
  if (!drop.has('E3')) s += `<g transform="translate(1000 820)"><path d="M-110 0 L110 0 L90 26 L-90 26 Z" fill="#6b4226" stroke="${ink}" stroke-width="3"/><path d="M-10 0 L-14 -54 L12 -54 L10 0 Z" fill="#2f3b5c" stroke="${ink}" stroke-width="2"/><ellipse cx="0" cy="-60" rx="22" ry="7" fill="#d9b86a" stroke="${ink}" stroke-width="2"/><line x1="30" y1="-90" x2="-60" y2="60" stroke="${ink}" stroke-width="5"/></g>`;
  s += `<path d="M0 ${H} L0 760 C120 740 220 760 300 ${H} Z" fill="#2c4a3a" stroke="${ink}" stroke-width="3"/>`;
  if (q !== 'crude') for (let i = 0; i < 14; i++) s += `<path d="M${120 + i * 9} 700 C${110 + i * 12} 800 ${140 + i * 8} 880 ${150 + i * 10} 960" stroke="#5f8a52" stroke-width="3" fill="none"/>`;
  if (!drop.has('E6')) for (let i = 0; i < (q === 'crude' ? 40 : 170); i++) {
    const x = r() * (W + 300) - 150;
    const y = r() * H;
    s += `<line x1="${f(x)}" y1="${f(y)}" x2="${f(x - 50)}" y2="${f(y + 120)}" stroke="#d6e2f0" stroke-width="1.6" opacity="0.7"/>`;
  }
  return s;
};

const nouveau: Scene = (r, q, drop) => {
  const line = '#4a3526';
  let s = `<rect width="${W}" height="${H}" fill="#cfd3b4"/><rect y="760" width="${W}" height="${H - 760}" fill="#a9b28a"/>`;
  if (!drop.has('E3')) {
    s += `<circle cx="820" cy="360" r="300" fill="#e6c98f" stroke="${line}" stroke-width="6"/><circle cx="820" cy="360" r="250" fill="none" stroke="#b98e4e" stroke-width="3"/>`;
    for (let i = 0; i < 24; i++) {
      const a = (i / 24) * Math.PI * 2;
      s += `<line x1="${f(820 + Math.cos(a) * 180)}" y1="${f(360 + Math.sin(a) * 180)}" x2="${f(820 + Math.cos(a) * 246)}" y2="${f(360 + Math.sin(a) * 246)}" stroke="#b98e4e" stroke-width="3"/>`;
    }
  }
  // hair
  if (!drop.has('E2')) for (let i = 0; i < (q === 'crude' ? 5 : 16); i++) s += `<path d="M770 250 C${f(640 - i * 14)} ${f(300 + i * 8)} ${f(760 + (r() - 0.5) * 120)} ${f(520 + i * 12)} ${f(560 - i * 12)} ${f(700 + i * 10)}" stroke="${['#8b4a24', '#a55a2c', '#6f3a1c'][i % 3]}" stroke-width="${q === 'crude' ? 10 : 7}" fill="none" stroke-linecap="round"/>`;
  // gown
  s += `<path d="M760 420 C700 520 690 700 640 900 L1000 900 C960 700 920 520 860 420 Z" fill="#b9c7b0" stroke="${line}" stroke-width="5"/>`;
  for (let i = 0; i < 5; i++) s += `<path d="M${780 + i * 18} 460 C${760 + i * 22} 620 ${720 + i * 40} 780 ${700 + i * 60} 900" stroke="${line}" stroke-width="2" fill="none" opacity="0.6"/>`;
  // profile head
  if (!drop.has('E1')) s += `<path d="M770 250 C770 200 840 190 870 230 C880 250 878 262 892 280 C884 286 884 290 880 296 C884 306 876 312 872 318 C872 336 850 344 830 336 L826 380 L784 380 C790 350 770 320 770 250 Z" fill="#f0d4bb" stroke="${line}" stroke-width="5"/><path d="M770 250 C760 180 850 170 880 226 C850 206 800 210 790 260 Z" fill="#8b4a24" stroke="${line}" stroke-width="4"/>`;
  else s += `<ellipse cx="820" cy="280" rx="60" ry="72" fill="#f0d4bb" stroke="${line}" stroke-width="5"/>`;
  if (!drop.has('E4')) s += `<g transform="translate(930 470) rotate(12)"><path d="M-50 -110 C-70 -40 -40 20 0 30 C40 20 70 -40 50 -110" fill="none" stroke="#c9a24a" stroke-width="12"/><line x1="-56" y1="-100" x2="56" y2="-100" stroke="#c9a24a" stroke-width="10"/>${[-24, -8, 8, 24].map((x) => `<line x1="${x}" y1="-100" x2="${x * 0.5}" y2="20" stroke="${line}" stroke-width="2"/>`).join('')}</g>`;
  if (!drop.has('E5')) for (let i = 0; i < (q === 'crude' ? 3 : 8); i++) {
    const x = 180 + i * (i < 4 ? 90 : 0) + (i >= 4 ? 1100 + (i - 4) * 80 : 0);
    const y = 700 + (i % 2) * 40;
    s += `<line x1="${x}" y1="${y + 300}" x2="${x}" y2="${y}" stroke="#5e7a4a" stroke-width="6"/><path d="M${x} ${y} C${x - 40} ${y - 30} ${x - 30} ${y - 70} ${x} ${y - 50} C${x + 30} ${y - 70} ${x + 40} ${y - 30} ${x} ${y} Z" fill="${i % 2 ? '#7a5aa6' : '#f3efe2'}" stroke="${line}" stroke-width="3"/>`;
  }
  if (!drop.has('E6')) {
    s += `<g transform="translate(560 860)">`;
    for (let i = 0; i < 11; i++) {
      const a = Math.PI + (i / 10) * Math.PI;
      const x = Math.cos(a) * 190;
      const y = Math.sin(a) * 150;
      s += `<line x1="0" y1="0" x2="${f(x)}" y2="${f(y)}" stroke="#3c6f5e" stroke-width="5"/><ellipse cx="${f(x)}" cy="${f(y)}" rx="22" ry="30" fill="#2f8a7e" stroke="${line}" stroke-width="3"/><circle cx="${f(x)}" cy="${f(y)}" r="9" fill="#1d3a6b"/>`;
    }
    s += `<path d="M-20 10 C-30 -40 10 -70 30 -40 C40 -20 20 20 -20 10 Z" fill="#1f5f8a" stroke="${line}" stroke-width="4"/><circle cx="30" cy="-50" r="12" fill="#1f5f8a" stroke="${line}" stroke-width="3"/></g>`;
  }
  return s;
};

const fresco: Scene = (r, q, drop) => {
  let s = `<rect width="${W}" height="${H}" fill="#e7dcc6"/>`;
  s += `<defs><linearGradient id="hillsky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#b9cfd9"/><stop offset="1" stop-color="#e9e2cf"/></linearGradient></defs>`;
  const arches = drop.has('E1') ? 2 : 3;
  const aw = 1200 / arches;
  for (let i = 0; i < arches; i++) {
    const x0 = 168 + i * aw;
    s += `<path d="M${x0 + 40} 760 L${x0 + 40} 380 A ${aw / 2 - 40} ${aw / 2 - 40} 0 0 1 ${x0 + aw - 40} 380 L${x0 + aw - 40} 760 Z" fill="url(#hillsky)"/>`;
    if (!drop.has('E6')) s += `<path d="M${x0 + 40} 640 C${x0 + 200} 560 ${x0 + 260} 620 ${x0 + aw - 40} 580 L${x0 + aw - 40} 760 L${x0 + 40} 760 Z" fill="#9fb17e"/>${[0, 1, 2].map((k) => `<ellipse cx="${f(x0 + 90 + k * (aw - 140) / 2)}" cy="${600 - (k % 2) * 20}" rx="12" ry="50" fill="#4e6b3e"/>`).join('')}`;
  }
  for (let i = 0; i <= arches; i++) s += `<rect x="${168 + i * aw - 8}" y="380" width="${i === 0 || i === arches ? 50 : 30}" height="380" fill="#d9ccb2"/>`;
  s += `<rect x="130" y="240" width="1276" height="60" fill="#cdbd9e"/>`;
  s += `<rect y="760" width="${W}" height="${H - 760}" fill="#d8c3a0"/>`;
  if (q !== 'crude') {
    for (let i = -8; i <= 8; i++) s += `<line x1="${768 + i * 60}" y1="760" x2="${768 + i * 220}" y2="${H}" stroke="#a88c66" stroke-width="2"/>`;
    for (let k = 0; k < 5; k++) s += `<line x1="0" y1="${780 + k * k * 12}" x2="${W}" y2="${780 + k * k * 12}" stroke="#a88c66" stroke-width="2"/>`;
  }
  if (!drop.has('E2')) s += `<g transform="translate(620 880)"><path d="M-60 0 C-70 -120 -50 -220 -20 -260 L30 -260 C60 -220 70 -120 60 0 Z" fill="#3f6aa8"/><ellipse cx="4" cy="-282" rx="24" ry="28" fill="#e7c5a3"/><path d="M-26 -290 C-30 -330 40 -330 34 -286 C30 -310 -20 -312 -26 -290 Z" fill="#3f6aa8"/>${drop.has('E3') ? '' : '<ellipse cx="96" cy="-150" rx="54" ry="22" fill="#9b6b3c"/><circle cx="80" cy="-166" r="14" fill="#d6a45e"/><circle cx="104" cy="-170" r="12" fill="#6b3d6e"/><circle cx="116" cy="-162" r="11" fill="#6b3d6e"/>'}<path d="M40 -170 L90 -150" stroke="#e7c5a3" stroke-width="12" stroke-linecap="round"/></g>`;
  if (!drop.has('E4')) s += `<g transform="translate(900 880)"><path d="M-60 0 C-70 -120 -50 -220 -20 -270 L30 -270 C60 -220 70 -120 60 0 Z" fill="#a0523a"/><ellipse cx="0" cy="-292" rx="24" ry="28" fill="#d9b48f"/><path d="M-20 -276 C-20 -240 20 -240 20 -276 Z" fill="#f2efe6"/><path d="M-22 -306 C-18 -326 18 -326 22 -306 Z" fill="#e8e4da"/></g>`;
  if (!drop.has('E5')) s += `<g transform="translate(470 900)"><path d="M-30 0 C-40 -60 -20 -100 0 -110 L20 -100 C30 -60 30 -30 40 0 Z" fill="#c9a24a"/><circle cx="4" cy="-122" r="18" fill="#e7c5a3"/><ellipse cx="-70" cy="-40" rx="46" ry="30" fill="#f5f2ea"/><circle cx="-106" cy="-58" r="16" fill="#f5f2ea"/></g>`;
  if (q !== 'crude') s += dabs(r, 60, [0, 0, W, H], ['#efe6d3', '#d9cbb0'], [60, 200], [2, 4], 0, 0.3);
  return s;
};

const herb: Scene = (r, q, drop) => {
  let s = `<defs><linearGradient id="wood" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#bcd9a8"/><stop offset="0.6" stop-color="#5e8a4c"/><stop offset="1" stop-color="#2e4a2a"/></linearGradient></defs><rect width="${W}" height="${H}" fill="url(#wood)"/>`;
  if (!drop.has('E6')) s += `<rect x="1080" y="170" width="46" height="170" fill="#b7b3aa"/><path d="M1074 170 l10 -18 l10 18 l10 -18 l10 18 l10 -18 l10 18 Z" fill="#b7b3aa"/><ellipse cx="1100" cy="260" rx="150" ry="140" fill="#eef4dc" opacity="0.35" filter="url(#glow)"/>`;
  for (let i = 0; i < 9; i++) {
    const x = i * 180 + r() * 60;
    if (x > 1000 && x < 1180) continue;
    s += `<rect x="${f(x)}" y="0" width="${f(28 + r() * 30)}" height="760" fill="${['#3d3226', '#4a3b2c', '#34291f'][i % 3]}"/>`;
  }
  s += `<path d="${ridge(r, 700, 30, 6)}" fill="#3f6a34"/>`;
  if (!drop.has('E3')) s += `<path d="M0 860 C300 800 500 900 800 840 C1100 780 1300 860 ${W} 820 L${W} 900 C1300 930 1100 860 800 920 C500 980 300 880 0 940 Z" fill="#7fb1c9"/>${q !== 'crude' ? dabs(r, 80, [0, 830, W, 80], ['#d8eef5', '#a6cfe0'], [20, 60], [2, 4], 0, 0.7) : ''}`;
  s += dabs(r, q === 'crude' ? 80 : 600, [0, 700, W, 180], drop.has('E3') ? ['#6b5fb8'] : ['#5b5fc0', '#7a6ad0', '#4f4aa8'], [6, 12], [5, 8], 0, 0.9);
  if (!drop.has('E1') || !drop.has('E2')) s += `<g transform="translate(640 820)"><path d="M-120 20 C-110 -60 -60 -120 0 -130 C60 -120 90 -60 110 20 Z" fill="${drop.has('E2') ? '#7a2d3a' : '#2f6b3a'}"/><ellipse cx="0" cy="-160" rx="28" ry="33" fill="#f2d6c0"/>${drop.has('E1') ? '<path d="M-30 -170 C-30 -210 30 -210 30 -170 Z" fill="#e9d38a"/>' : `<path d="M-30 -168 C-40 -210 30 -216 34 -172 C60 -120 50 -70 40 -40 C30 -80 20 -120 20 -150 C10 -180 -20 -180 -30 -168 Z" fill="#9a3f1c"/>`}<path d="M-20 -110 L-90 -40" stroke="#f2d6c0" stroke-width="14" stroke-linecap="round"/></g>`;
  if (!drop.has('E4')) s += `<g transform="translate(520 820)"><path d="M-44 0 L44 0 L34 40 L-34 40 Z" fill="#b08650"/><path d="M-44 0 Q0 -40 44 0" fill="none" stroke="#8a6436" stroke-width="5"/>${[-20, 0, 20].map((x, k) => `<line x1="${x}" y1="0" x2="${x + (k - 1) * 6}" y2="-110" stroke="#4d7a3c" stroke-width="4"/>${[0, 1, 2, 3, 4].map((j) => `<ellipse cx="${x + (k - 1) * 6 * (j / 5) + 5}" cy="${-20 - j * 18}" rx="7" ry="9" fill="#d65a9a"/>`).join('')}`).join('')}</g>`;
  if (!drop.has('E5')) s += `<g transform="translate(1180 780)">${[0, 1, 2, 3, 4].map((i) => `<path d="M${-60 + i * 30} 40 q 10 -60 ${20 + i * 4} -80" stroke="#3f7a3a" stroke-width="8" fill="none"/>`).join('')}<ellipse cx="0" cy="0" rx="40" ry="28" fill="#f7f5ef"/><circle cx="32" cy="-18" r="16" fill="#f7f5ef"/><ellipse cx="28" cy="-48" rx="6" ry="22" fill="#f7f5ef"/><ellipse cx="40" cy="-46" rx="6" ry="22" fill="#f7f5ef"/><circle cx="38" cy="-20" r="3" fill="#3a2a1e"/></g>`;
  return s;
};

const hudson: Scene = (r, q, drop) => {
  let s = `<defs><linearGradient id="gold" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8fb0cf"/><stop offset="0.45" stop-color="#f3d49a"/><stop offset="0.62" stop-color="#f6b98a"/><stop offset="1" stop-color="#d7a26c"/></linearGradient><linearGradient id="mirror" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f6cf98"/><stop offset="0.35" stop-color="#e9b98c"/><stop offset="1" stop-color="#7f9dbd"/></linearGradient></defs><rect width="${W}" height="${H}" fill="url(#gold)"/>`;
  if (!drop.has('E6')) for (let i = 0; i < (q === 'crude' ? 3 : 9); i++) {
    const x = 300 + r() * 1000;
    const y = 120 + r() * 160;
    s += `<g opacity="0.95"><ellipse cx="${f(x)}" cy="${f(y)}" rx="${f(90 + r() * 90)}" ry="${f(40 + r() * 30)}" fill="#fff1d6" filter="url(#soft)"/><ellipse cx="${f(x + 40)}" cy="${f(y + 20)}" rx="80" ry="30" fill="#f2c79a" filter="url(#soft)"/></g>`;
  }
  s += `<circle cx="820" cy="470" r="90" fill="#fff4d6" opacity="0.8" filter="url(#glow)"/>`;
  if (!drop.has('E5')) s += `<path d="${ridge(r, 470, 50, 8, 560)}" fill="#7b8fb5" opacity="0.9"/><path d="${ridge(r, 510, 35, 8, 580)}" fill="#6679a3"/>`;
  s += `<rect y="560" width="${W}" height="${H - 560}" fill="url(#mirror)"/>`;
  s += dabs(r, q === 'crude' ? 40 : 260, [0, 570, W, 330], ['#fbe2b4', '#e2b27f', '#b9cde0', '#fff4dc'], [30, 110], [2, 5], 0, 0.55);
  s += `<path d="M0 560 C300 540 500 600 700 580 L700 640 C500 660 300 610 0 640 Z" fill="#4e6a3f"/><path d="M${W} 540 C1300 560 1100 600 950 590 L950 650 C1150 660 1300 620 ${W} 640 Z" fill="#4a6440"/>`;
  if (!drop.has('E2')) s += `<g transform="translate(1250 560)"><rect x="-70" y="-60" width="140" height="60" fill="#6b4a2c"/>${[0, 1, 2, 3].map((i) => `<line x1="-70" y1="${-48 + i * 14}" x2="70" y2="${-48 + i * 14}" stroke="#4a321c" stroke-width="3"/>`).join('')}<path d="M-84 -60 L0 -110 L84 -60 Z" fill="#5a3b22"/><rect x="30" y="-120" width="14" height="40" fill="#5a4a3e"/><path d="M37 -124 C20 -170 60 -200 40 -250 C30 -280 60 -300 50 -330" stroke="#e9e1d6" stroke-width="12" fill="none" opacity="0.6" filter="url(#soft)"/></g>`;
  if (!drop.has('E3')) s += `<g transform="translate(820 720)"><path d="M-110 0 Q0 22 110 0 Q0 10 -110 0 Z" fill="#7a3e22" stroke="#4a2412" stroke-width="3"/><circle cx="-40" cy="-30" r="12" fill="#3a2a22"/><path d="M-50 -20 L-30 -20 L-32 4 L-48 4 Z" fill="#8a3b2a"/><circle cx="40" cy="-30" r="12" fill="#3a2a22"/><path d="M30 -20 L50 -20 L48 4 L32 4 Z" fill="#2f4a6a"/><line x1="-60" y1="-10" x2="-20" y2="20" stroke="#3a2a22" stroke-width="4"/><path d="M-110 20 Q0 40 110 20" stroke="#7a3e22" stroke-width="6" opacity="0.3" fill="none"/></g>`;
  if (!drop.has('E4')) s += `<g transform="translate(520 860)"><ellipse cx="0" cy="0" rx="60" ry="26" fill="#9a6a3e"/><path d="M-50 10 l-6 50 M-30 14 l-2 50 M40 12 l4 50 M24 14 l0 50" stroke="#7a522e" stroke-width="7"/><path d="M-50 -6 C-80 10 -96 40 -110 60" stroke="#9a6a3e" stroke-width="22" stroke-linecap="round" fill="none"/><ellipse cx="-114" cy="66" rx="20" ry="12" fill="#8a5a32"/></g>`;
  s += `<path d="M0 ${H} L0 880 C300 860 600 900 800 ${H} Z" fill="#4a5a2e"/>`;
  if (!drop.has('E1')) s += `<path d="M160 ${H} C170 700 150 400 180 120 L206 120 C200 400 214 700 214 ${H} Z" fill="#f2efe6"/>${Array.from({ length: 12 }, (_, i) => `<rect x="${162 + (i % 3) * 14}" y="${200 + i * 62}" width="${14 + (i % 2) * 8}" height="5" fill="#2a2a2a"/>`).join('')}${Array.from({ length: q === 'crude' ? 10 : 40 }, () => `<circle cx="${f(120 + r() * 260)}" cy="${f(80 + r() * 300)}" r="${f(14 + r() * 20)}" fill="${['#c9a23a', '#9fb04a', '#d8b54e'][Math.floor(r() * 3)]}" opacity="0.85"/>`).join('')}`;
  return s;
};

const SCENES: Record<number, Scene> = { 1: keeper, 2: harbour, 3: storm, 4: ukiyoe, 5: nouveau, 6: fresco, 7: herb, 8: hudson };

/**
 * One demo painting. `missing` lists required elements this artist leaves out; `breaks` lists rules it breaks
 * (N1 adds a signature, N2 paints a frame border; N3 is only claimed, never drawn).
 */
export function paintMock(brief: number, seed: string, quality: MockQuality, missing: string[] = [], breaks: string[] = []): MockPainting {
  const r = rngOf(`${brief}|${seed}`);
  const drop = new Set(missing);
  const scene = SCENES[brief] ?? hudson;
  const body = scene(r, quality, drop);
  const extras = (breaks.includes('N1') ? signature(r) : '') + (breaks.includes('N2') ? paintedFrame() : '');
  const s = Math.floor(r() * 1000);
  return { svg: finish(body, s, quality, extras), drawn: new Set(['E1', 'E2', 'E3', 'E4', 'E5', 'E6'].filter((e) => !drop.has(e))), broken: new Set(breaks) };
}

/** The Random Baseline's noise picture (the real one is a small PNG of value noise; this is its look-alike). */
export function noiseMock(seed: string): string {
  const r = rngOf(seed);
  const c = () => `hsl(${Math.floor(r() * 360)} ${30 + Math.floor(r() * 50)}% ${30 + Math.floor(r() * 40)}%)`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="384" height="256" viewBox="0 0 384 256"><defs><filter id="n"><feTurbulence type="fractalNoise" baseFrequency="0.03" numOctaves="2" seed="${Math.floor(r() * 99)}"/><feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1.4 -0.2"/></filter><filter id="g"><feTurbulence type="fractalNoise" baseFrequency="0.9" seed="3"/><feColorMatrix type="matrix" values="0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0.35 0"/></filter></defs><rect width="384" height="256" fill="${c()}"/><rect width="384" height="256" fill="${c()}" filter="url(#n)"/><rect width="384" height="256" filter="url(#g)"/></svg>`;
}

/** The Random Baseline in Painted in Code: its fixed "draw something" reply, one grey circle. */
export function circleMock(): string {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><rect width="${W}" height="${H}" fill="#fff"/><circle cx="210" cy="180" r="40" fill="gray"/></svg>`;
}
