// Hand-written demo SVGs for mock mode's "Precise SVG Illustration" results: one correct version of each case
// and a few flawed ones (a clock hand at the wrong angle, a bar the wrong height, a pawn on the wrong square...),
// so the requirement overlay and the measured-values checklist have something real to show.

type Flaw = 'none' | 'minor' | 'major';

function lighthouse(flaw: Flaw): string {
  const beams = flaw === 'major' ? 4 : 3;
  const beamSvg = Array.from({ length: beams }, (_, i) => {
    const a = -150 + i * (120 / Math.max(1, beams - 1));
    return `<polygon points="256,128 ${256 + 260 * Math.cos((a * Math.PI) / 180)},${128 + 260 * Math.sin((a * Math.PI) / 180) - 40} ${256 + 260 * Math.cos(((a + 14) * Math.PI) / 180)},${128 + 260 * Math.sin(((a + 14) * Math.PI) / 180) + 40}" fill="#fde68a" fill-opacity="0.35"/>`;
  }).join('');
  const moon = flaw === 'major' ? '<circle cx="110" cy="90" r="34" fill="#fef3c7"/>' : '<circle cx="110" cy="90" r="34" fill="#fef3c7"/><circle cx="124" cy="80" r="30" fill="#0b1437"/>';
  const stars = [[40, 40], [200, 30], [330, 60], [420, 40], [470, 120], [60, 190], [380, 200]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="2.5" fill="#fff"/>`).join('');
  const stripes = [0, 1, 2].map((i) => `<rect x="${226 + i * 3}" y="${210 + i * 70}" width="${60 - i * 6}" height="22" fill="#dc2626"/>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512"><defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0b1437"/><stop offset="1" stop-color="#3b6fb6"/></linearGradient></defs><rect width="512" height="512" fill="url(#sky)"/>${stars}${moon}${beamSvg}<polygon points="226,420 286,420 272,150 240,150" fill="#f8fafc"/>${stripes}<rect x="232" y="112" width="48" height="40" rx="6" fill="#fcd34d"/><polygon points="226,112 286,112 256,86" fill="#1f2937"/><path d="M0 430 Q64 410 128 430 T256 430 T384 430 T512 430 V512 H0Z" fill="#1d4ed8"/><path d="M0 455 Q64 435 128 455 T256 455 T384 455 T512 455 V512 H0Z" fill="#1e3a8a"/></svg>`;
}

function clock(flaw: Flaw): string {
  const hour = flaw === 'major' ? 300 : 304.25;
  const minute = flaw === 'none' ? 51 : 48;
  const hand = (deg: number, len: number, w: number, color: string) => {
    const a = ((deg - 90) * Math.PI) / 180;
    return `<line x1="200" y1="200" x2="${(200 + len * Math.cos(a)).toFixed(2)}" y2="${(200 + len * Math.sin(a)).toFixed(2)}" stroke="${color}" stroke-width="${w}" stroke-linecap="round"/>`;
  };
  const ticks = Array.from({ length: 60 }, (_, i) => {
    const a = ((i * 6 - 90) * Math.PI) / 180;
    const big = i % 5 === 0;
    const r1 = big ? 150 : 162;
    return `<line x1="${(200 + r1 * Math.cos(a)).toFixed(2)}" y1="${(200 + r1 * Math.sin(a)).toFixed(2)}" x2="${(200 + 172 * Math.cos(a)).toFixed(2)}" y2="${(200 + 172 * Math.sin(a)).toFixed(2)}" stroke="#111827" stroke-width="${big ? 5 : 2}"/>`;
  }).join('');
  const numerals = flaw === 'major' ? Array.from({ length: 12 }, (_, i) => i + 1) : [12, 3, 6, 9];
  const nums = numerals
    .map((n) => {
      const a = ((n * 30 - 90) * Math.PI) / 180;
      return `<text x="${(200 + 124 * Math.cos(a)).toFixed(1)}" y="${(200 + 124 * Math.sin(a) + 10).toFixed(1)}" font-family="sans-serif" font-size="28" text-anchor="middle" fill="#111827">${n}</text>`;
    })
    .join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400"><circle cx="200" cy="200" r="180" fill="#ffffff" stroke="#1f2937" stroke-width="8"/>${ticks}${nums}${hand(hour, 90, 10, '#111827')}${hand(minute, 140, 6, '#111827')}${hand(180, 160, 2, '#dc2626')}<circle cx="200" cy="200" r="8" fill="#111827"/></svg>`;
}

function bars(flaw: Flaw): string {
  const data: Array<[string, number]> = [['Mon', 12], ['Tue', 7], ['Wed', 15], ['Thu', flaw === 'minor' ? 3 : 4], ['Fri', 9]];
  const rects = data
    .map(([d, v], i) => {
      const x = 90 + 90 * i;
      const h = v * 20;
      const fill = flaw === 'major' ? '#29335C' : v === 15 ? '#E4572E' : '#29335C';
      return `<rect x="${x}" y="${450 - h}" width="60" height="${h}" fill="${fill}"/><text x="${x + 30}" y="472" text-anchor="middle" font-family="sans-serif" font-size="16">${d}</text><text x="${x + 30}" y="${444 - h}" text-anchor="middle" font-family="sans-serif" font-size="16">${v}</text>`;
    })
    .join('');
  const ticks = [0, 5, 10, 15].map((t) => `<line x1="54" y1="${450 - t * 20}" x2="60" y2="${450 - t * 20}" stroke="#374151" stroke-width="2"/><text x="48" y="${455 - t * 20}" text-anchor="end" font-family="sans-serif" font-size="14">${t}</text>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="500" viewBox="0 0 600 500"><rect width="600" height="500" fill="#ffffff"/><line x1="60" y1="450" x2="570" y2="450" stroke="#374151" stroke-width="2"/><line x1="60" y1="450" x2="60" y2="80" stroke="#374151" stroke-width="2"/>${ticks}${rects}</svg>`;
}

function chess(flaw: Flaw): string {
  let squares = '';
  for (let r = 0; r < 8; r++)
    for (let c = 0; c < 8; c++) {
      const dark = flaw === 'major' ? (r + c) % 2 === 0 : (r + c) % 2 === 1;
      squares += `<rect x="${c * 50}" y="${r * 50}" width="50" height="50" fill="${dark ? '#B58863' : '#F0D9B5'}"/>`;
    }
  const at = (sq: string) => ({ x: (sq.charCodeAt(0) - 97) * 50 + 25, y: (8 - Number(sq[1])) * 50 + 38 });
  const pieces: Array<[string, string]> = [['♔', 'e1'], ['♖', 'h1'], ['♙', flaw === 'none' ? 'd4' : 'd5'], ['♚', 'e8'], ['♛', 'd8'], ['♟', 'f7']];
  const glyphs = pieces.map(([p, sq]) => `<text x="${at(sq).x}" y="${at(sq).y}" font-size="38" text-anchor="middle" font-family="serif">${p}</text>`).join('');
  const files = 'abcdefgh'.split('').map((f, i) => `<text x="${i * 50 + 44}" y="396" font-size="10" font-family="sans-serif">${f}</text>`).join('');
  const ranks = Array.from({ length: 8 }, (_, i) => `<text x="3" y="${(7 - i) * 50 + 12}" font-size="10" font-family="sans-serif">${i + 1}</text>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400">${squares}${glyphs}${files}${ranks}</svg>`;
}

export function demoSvg(caseId: string, flaw: Flaw): string {
  switch (caseId) {
    case 'v01':
      return lighthouse(flaw);
    case 'v02':
      return clock(flaw);
    case 'v03':
      return bars(flaw);
    default:
      return chess(flaw);
  }
}
