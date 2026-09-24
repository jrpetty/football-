/**
 * Draw It Blind — seeded scenes, the fixed colour palette, and the target SVG.
 */
import type { Rng } from '../../core/types.ts';

export const CANVAS = 400;

export type ShapeType = 'circle' | 'square' | 'rectangle' | 'triangle' | 'star';
export const SHAPE_TYPES: readonly ShapeType[] = ['circle', 'square', 'rectangle', 'triangle', 'star'];

export type PaletteName =
  | 'red' | 'orange' | 'yellow' | 'green' | 'blue' | 'purple' | 'pink' | 'black'
  | 'teal' | 'navy' | 'brown' | 'gray';
export type PaletteId = 'basic' | 'extended';

/**
 * The eight scene colours. Each name has several anchor colours (the first is
 * the one we draw with); a drawn fill maps to the name of its nearest anchor,
 * so "blue", "#0000ff", "royalblue" and "rgb(30,136,229)" all count as blue.
 */
export const PALETTE: ReadonlyArray<{ name: PaletteName; anchors: string[] }> = [
  { name: 'red', anchors: ['#e53935', '#ff0000', '#f44336', '#d32f2f', '#dc143c', '#b22222', '#c62828', '#8b0000'] },
  { name: 'orange', anchors: ['#fb8c00', '#ffa500', '#ff9800', '#ff8c00', '#ff7f00', '#f57c00', '#e65100', '#ff6600', '#ff4500'] },
  { name: 'yellow', anchors: ['#fdd835', '#ffff00', '#ffeb3b', '#ffd700', '#ffc107', '#f9a825', '#fff176', '#ffe135'] },
  { name: 'green', anchors: ['#43a047', '#008000', '#00ff00', '#4caf50', '#2e7d32', '#228b22', '#32cd32', '#00c853', '#66bb6a', '#006400'] },
  { name: 'blue', anchors: ['#1e88e5', '#0000ff', '#2196f3', '#1565c0', '#4169e1', '#1e90ff', '#0d47a1', '#000080', '#00bfff', '#87ceeb', '#3f51b5'] },
  { name: 'purple', anchors: ['#8e24aa', '#800080', '#9c27b0', '#7b1fa2', '#a020f0', '#6a1b9a', '#9400d3', '#8a2be2', '#ba55d3', '#ee82ee', '#673ab7'] },
  { name: 'pink', anchors: ['#ec407a', '#ffc0cb', '#ff69b4', '#e91e63', '#ff1493', '#f48fb1', '#ffb6c1', '#db7093', '#ff00ff'] },
  { name: 'black', anchors: ['#212121', '#000000', '#333333', '#424242', '#2f2f2f'] },
];

/**
 * The hard tier's twelve colours, including close pairs (navy/blue,
 * teal/green, brown/orange, gray/black). Anchors never overlap between names.
 */
export const EXTENDED_PALETTE: ReadonlyArray<{ name: PaletteName; anchors: string[] }> = [
  { name: 'red', anchors: ['#e53935', '#ff0000', '#f44336', '#d32f2f', '#dc143c', '#b22222', '#c62828', '#8b0000'] },
  { name: 'orange', anchors: ['#fb8c00', '#ffa500', '#ff9800', '#ff8c00', '#ff7f00', '#f57c00', '#ff6600', '#ff4500'] },
  { name: 'yellow', anchors: ['#fdd835', '#ffff00', '#ffeb3b', '#ffd700', '#ffc107', '#fff176', '#ffe135'] },
  { name: 'green', anchors: ['#43a047', '#008000', '#00ff00', '#4caf50', '#2e7d32', '#228b22', '#32cd32', '#66bb6a', '#006400', '#90ee90', '#7cfc00'] },
  { name: 'teal', anchors: ['#00897b', '#008080', '#009688', '#20b2aa', '#00796b', '#48d1cc', '#40e0d0', '#00ced1', '#5f9ea0', '#00ffff', '#26a69a'] },
  { name: 'blue', anchors: ['#1e88e5', '#0000ff', '#2196f3', '#4169e1', '#1e90ff', '#00bfff', '#87ceeb', '#6495ed', '#4682b4', '#42a5f5', '#1565c0'] },
  { name: 'navy', anchors: ['#1a237e', '#000080', '#00008b', '#191970', '#0d47a1', '#283593', '#000066'] },
  { name: 'purple', anchors: ['#8e24aa', '#800080', '#9c27b0', '#7b1fa2', '#a020f0', '#6a1b9a', '#9400d3', '#8a2be2', '#ba55d3', '#ee82ee', '#673ab7', '#663399'] },
  { name: 'pink', anchors: ['#ec407a', '#ffc0cb', '#ff69b4', '#e91e63', '#ff1493', '#f48fb1', '#ffb6c1', '#db7093', '#ff00ff'] },
  { name: 'brown', anchors: ['#795548', '#8b4513', '#a52a2a', '#6d4c41', '#5d4037', '#a0522d', '#8b5a2b', '#654321', '#d2691e'] },
  { name: 'black', anchors: ['#212121', '#000000', '#333333', '#2f2f2f'] },
  { name: 'gray', anchors: ['#9e9e9e', '#808080', '#757575', '#a9a9a9', '#696969', '#bdbdbd', '#c0c0c0', '#778899', '#708090', '#607d8b'] },
];

export function paletteOf(id: PaletteId): ReadonlyArray<{ name: PaletteName; anchors: string[] }> {
  return id === 'extended' ? EXTENDED_PALETTE : PALETTE;
}

export function paletteHex(name: PaletteName): string {
  return (PALETTE.find((p) => p.name === name) ?? EXTENDED_PALETTE.find((p) => p.name === name)!).anchors[0]!;
}

export interface SceneShape {
  id: number;
  type: ShapeType;
  color: PaletteName;
  /** Bounding-box centre and size (pixels). */
  cx: number;
  cy: number;
  w: number;
  h: number;
  /** Circle radius / star outer radius. */
  r?: number;
  /** Polygon vertices (triangle, star). */
  points?: Array<[number, number]>;
  /** Triangles in the hard tier: direction the apex points, in degrees (SVG axes: -90 = up, 0 = right, 90 = down). */
  angle?: number;
  /** Human label of the apex direction, e.g. "down-left". */
  direction?: string;
}

export interface SceneOptions {
  palette?: PaletteId;
  /** Allow overlapping and nested shapes (listed and drawn back to front). */
  overlap?: boolean;
  /** Rotate triangles to one of eight directions. */
  rotation?: boolean;
}

export interface Scene {
  shapes: SceneShape[];
}

const round = (x: number) => Math.round(x);

function starPoints(cx: number, cy: number, R: number): Array<[number, number]> {
  const pts: Array<[number, number]> = [];
  for (let i = 0; i < 10; i++) {
    const rad = i % 2 === 0 ? R : R * 0.45;
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    pts.push([cx + rad * Math.cos(a), cy + rad * Math.sin(a)]);
  }
  return pts;
}

function bbox(pts: Array<[number, number]>): { x0: number; y0: number; x1: number; y1: number } {
  const xs = pts.map((p) => p[0]);
  const ys = pts.map((p) => p[1]);
  return { x0: Math.min(...xs), y0: Math.min(...ys), x1: Math.max(...xs), y1: Math.max(...ys) };
}

interface Draft {
  type: ShapeType;
  w: number;
  h: number;
  r?: number;
}

function draftSize(rng: Rng, type: ShapeType, shrink: number): Draft {
  const s = (a: number, b: number) => round(rng.int(a, b) * shrink);
  switch (type) {
    case 'circle': {
      const r = s(25, 58);
      return { type, w: 2 * r, h: 2 * r, r };
    }
    case 'square': {
      const a = s(50, 105);
      return { type, w: a, h: a };
    }
    case 'rectangle': {
      const long = s(95, 165);
      const short = s(40, 70);
      return rng.chance(0.5) ? { type, w: long, h: short } : { type, w: short, h: long };
    }
    case 'triangle':
      return { type, w: s(65, 120), h: s(60, 110) };
    case 'star': {
      const r = s(32, 58);
      const b = bbox(starPoints(0, 0, r));
      return { type, w: b.x1 - b.x0, h: b.y1 - b.y0, r };
    }
  }
}

/** 5–7 non-overlapping shapes, distinct colours, at least three different shape types. */
export function generateScene(rng: Rng, minShapes = 5, maxShapes = 7, opts: SceneOptions = {}): Scene {
  if (opts.overlap || opts.rotation || (opts.palette ?? 'basic') !== 'basic') return generateHardScene(rng, minShapes, maxShapes, opts);
  const n = rng.int(minShapes, maxShapes);
  const types: ShapeType[] = rng.shuffle(SHAPE_TYPES).slice(0, 3);
  while (types.length < n) types.push(rng.pick(SHAPE_TYPES));
  const order = rng.shuffle(types);
  const colors = rng.shuffle(PALETTE.map((p) => p.name)).slice(0, n);
  const placed: Array<{ x0: number; y0: number; x1: number; y1: number }> = [];
  const shapes: SceneShape[] = [];
  const margin = 14;
  const gap = 10;

  order.forEach((type, i) => {
    let shrink = 1;
    for (let attempt = 0; ; attempt++) {
      if (attempt > 0 && attempt % 60 === 0) shrink *= 0.85;
      const d = draftSize(rng, type, shrink);
      const cx = rng.int(Math.ceil(margin + d.w / 2), Math.floor(CANVAS - margin - d.w / 2));
      const cy = rng.int(Math.ceil(margin + d.h / 2), Math.floor(CANVAS - margin - d.h / 2));
      const box = { x0: cx - d.w / 2, y0: cy - d.h / 2, x1: cx + d.w / 2, y1: cy + d.h / 2 };
      const clash = placed.some((b) => box.x0 < b.x1 + gap && box.x1 > b.x0 - gap && box.y0 < b.y1 + gap && box.y1 > b.y0 - gap);
      if (clash && attempt < 600) continue;
      placed.push(box);
      const shape: SceneShape = { id: i + 1, type, color: colors[i]!, cx, cy, w: round(d.w), h: round(d.h) };
      if (type === 'circle') shape.r = d.r;
      if (type === 'star') {
        // Centre the star's bounding box on (cx, cy).
        const raw = starPoints(0, 0, d.r!);
        const b = bbox(raw);
        const ox = cx - (b.x0 + b.x1) / 2;
        const oy = cy - (b.y0 + b.y1) / 2;
        shape.r = d.r;
        shape.points = raw.map(([x, y]) => [Math.round((x + ox) * 10) / 10, Math.round((y + oy) * 10) / 10]);
      }
      if (type === 'triangle') {
        shape.points = [
          [cx, cy - d.h / 2],
          [cx + d.w / 2, cy + d.h / 2],
          [cx - d.w / 2, cy + d.h / 2],
        ];
      }
      shapes.push(shape);
      break;
    }
  });
  return { shapes };
}

const DIRECTIONS = ['up', 'up-right', 'right', 'down-right', 'down', 'down-left', 'left', 'up-left'];

function rotatePts(pts: Array<[number, number]>, deg: number, cx: number, cy: number): Array<[number, number]> {
  const r = (deg * Math.PI) / 180;
  return pts.map(([x, y]) => [cx + (x - cx) * Math.cos(r) - (y - cy) * Math.sin(r), cy + (x - cx) * Math.sin(r) + (y - cy) * Math.cos(r)]);
}

/**
 * Hard scenes: more, smaller shapes; some nested inside or overlapping others;
 * triangles are tall isosceles pointing in one of eight directions. Shapes are
 * ordered back to front (largest first) so nested ones stay visible.
 */
function generateHardScene(rng: Rng, minShapes: number, maxShapes: number, opts: SceneOptions): Scene {
  const n = rng.int(minShapes, maxShapes);
  const types: ShapeType[] = rng.shuffle(SHAPE_TYPES).slice(0, 3);
  while (types.length < n) types.push(rng.pick(SHAPE_TYPES));
  const order = rng.shuffle(types);
  const colors = rng.shuffle(paletteOf(opts.palette ?? 'basic').map((p) => p.name)).slice(0, n);
  const shrink = n >= 9 ? 0.72 : 0.9;
  const margin = 10;
  const placed: SceneShape[] = [];

  order.forEach((type, i) => {
    let d = draftSize(rng, type, shrink);
    let tri: Array<[number, number]> | null = null;
    let angle: number | undefined;
    if (type === 'triangle') {
      // Tall isosceles so the apex (and its direction) is unambiguous.
      const w = round(rng.int(36, 60) * (shrink / 0.72));
      const h = round(w * (1.35 + rng.next() * 0.3));
      const rot = opts.rotation ? rng.pick([0, 45, 90, 135, 180, 225, 270, 315]) : 0;
      tri = rotatePts([[0, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]], rot, 0, 0);
      const b = bbox(tri);
      d = { type, w: b.x1 - b.x0, h: b.y1 - b.y0 };
      angle = -90 + rot;
      if (angle > 180) angle -= 360;
    }
    const mode = opts.overlap && i >= 2 ? rng.pick(['nest', 'overlap', 'free', 'free'] as const) : 'free';
    let cx = -1;
    let cy = -1;
    const fits = (x: number, y: number) => x - d.w / 2 >= margin && x + d.w / 2 <= CANVAS - margin && y - d.h / 2 >= margin && y + d.h / 2 <= CANVAS - margin;
    if (mode === 'nest') {
      const hosts = placed.filter((p) => (p.type === 'square' || p.type === 'rectangle' || p.type === 'circle') && Math.min(p.w, p.h) >= 56);
      if (hosts.length > 0) {
        const host = rng.pick(hosts);
        // Shrink the new shape so it sits well inside its host.
        const f = Math.min(1, (0.45 * Math.min(host.w, host.h)) / Math.max(d.w, d.h));
        d = { ...d, w: d.w * f, h: d.h * f, r: d.r === undefined ? undefined : d.r * f };
        if (tri) tri = tri.map(([x, y]) => [x * f, y * f] as [number, number]);
        const slackX = ((host.w - d.w) / 2) * 0.35;
        const slackY = ((host.h - d.h) / 2) * 0.35;
        cx = round(host.cx + (rng.next() * 2 - 1) * slackX);
        cy = round(host.cy + (rng.next() * 2 - 1) * slackY);
      }
    } else if (mode === 'overlap' && placed.length > 0) {
      const other = rng.pick(placed);
      const a = rng.next() * Math.PI * 2;
      const dist = ((Math.max(other.w, other.h) + Math.max(d.w, d.h)) / 2) * 0.62;
      const x = round(other.cx + Math.cos(a) * dist);
      const y = round(other.cy + Math.sin(a) * dist);
      if (fits(x, y)) {
        cx = x;
        cy = y;
      }
    }
    if (cx < 0) {
      for (let attempt = 0; attempt < 500; attempt++) {
        const x = rng.int(Math.ceil(margin + d.w / 2), Math.floor(CANVAS - margin - d.w / 2));
        const y = rng.int(Math.ceil(margin + d.h / 2), Math.floor(CANVAS - margin - d.h / 2));
        const clash = placed.some((b) => Math.abs(b.cx - x) * 2 < b.w + d.w + 12 && Math.abs(b.cy - y) * 2 < b.h + d.h + 12);
        cx = x;
        cy = y;
        if (!clash) break;
      }
    }
    const shape: SceneShape = { id: 0, type, color: colors[i]!, cx, cy, w: round(d.w), h: round(d.h) };
    if (type === 'circle') shape.r = d.r;
    if (type === 'star') {
      const raw = starPoints(0, 0, d.r!);
      const b = bbox(raw);
      shape.r = d.r;
      shape.points = raw.map(([x, y]) => [Math.round((x + cx - (b.x0 + b.x1) / 2) * 10) / 10, Math.round((y + cy - (b.y0 + b.y1) / 2) * 10) / 10]);
    }
    if (tri) {
      const b = bbox(tri);
      shape.points = tri.map(([x, y]) => [Math.round((x + cx - (b.x0 + b.x1) / 2) * 10) / 10, Math.round((y + cy - (b.y0 + b.y1) / 2) * 10) / 10]);
      shape.angle = angle;
      shape.direction = DIRECTIONS[(((angle! + 90) / 45) % 8 + 8) % 8]!;
    }
    placed.push(shape);
  });
  // Back to front: larger shapes first so nested ones stay visible.
  const shapes = [...placed].sort((a, b) => b.w * b.h - a.w * a.h).map((s, k) => ({ ...s, id: k + 1 }));
  return { shapes };
}

function shapeElement(s: SceneShape): string {
  const fill = paletteHex(s.color);
  switch (s.type) {
    case 'circle':
      return `<circle cx="${s.cx}" cy="${s.cy}" r="${s.r}" fill="${fill}"/>`;
    case 'square':
    case 'rectangle':
      return `<rect x="${s.cx - s.w / 2}" y="${s.cy - s.h / 2}" width="${s.w}" height="${s.h}" fill="${fill}"/>`;
    case 'triangle':
    case 'star':
      return `<polygon points="${s.points!.map(([x, y]) => `${x},${y}`).join(' ')}" fill="${fill}"/>`;
  }
}

export function renderSceneSvg(scene: Scene): string {
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${CANVAS}" height="${CANVAS}" viewBox="0 0 ${CANVAS} ${CANVAS}">`,
    `<rect x="0" y="0" width="${CANVAS}" height="${CANVAS}" fill="#ffffff"/>`,
    ...scene.shapes.map(shapeElement),
    '</svg>',
  ].join('\n');
}

function describeType(s: SceneShape): string {
  if (s.type === 'triangle') return `triangle (pointing ${s.direction ?? 'up'})`;
  if (s.type === 'star') return 'star (5 points, pointing up)';
  return s.type;
}

/** The precise, structured scene given to the describer. */
export function sceneTable(scene: Scene, opts: SceneOptions = {}): string {
  const rows = scene.shapes.map((s) => {
    const size = s.type === 'circle' ? `radius ${s.r} (${s.w} wide × ${s.h} tall)` : `${s.w} wide × ${s.h} tall`;
    return `| ${s.id} | ${describeType(s)} | ${s.color} | (${s.cx}, ${s.cy}) | ${size} |`;
  });
  return [
    `Canvas: ${CANVAS} × ${CANVAS} pixels, white background. x runs from 0 (left) to ${CANVAS} (right); y runs from 0 (top) to ${CANVAS} (bottom). Positions are the centre of each shape's bounding box. ${
      opts.overlap ? 'Shapes are listed from back to front: some overlap, and some sit inside others.' : 'No shapes overlap.'
    }`,
    '',
    '| # | shape | colour | centre (x, y) | size |',
    '|---|-------|--------|---------------|------|',
    ...rows,
  ].join('\n');
}
