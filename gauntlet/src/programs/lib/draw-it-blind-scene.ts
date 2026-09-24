/**
 * Draw It Blind — seeded scenes, the fixed colour palette, and the target SVG.
 */
import type { Rng } from '../../core/types.ts';

export const CANVAS = 400;

export type ShapeType = 'circle' | 'square' | 'rectangle' | 'triangle' | 'star';
export const SHAPE_TYPES: readonly ShapeType[] = ['circle', 'square', 'rectangle', 'triangle', 'star'];

export type PaletteName = 'red' | 'orange' | 'yellow' | 'green' | 'blue' | 'purple' | 'pink' | 'black';

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

export function paletteHex(name: PaletteName): string {
  return PALETTE.find((p) => p.name === name)!.anchors[0]!;
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
export function generateScene(rng: Rng, minShapes = 5, maxShapes = 7): Scene {
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
  if (s.type === 'triangle') return 'triangle (pointing up)';
  if (s.type === 'star') return 'star (5 points, pointing up)';
  return s.type;
}

/** The precise, structured scene given to the describer. */
export function sceneTable(scene: Scene): string {
  const rows = scene.shapes.map((s) => {
    const size = s.type === 'circle' ? `radius ${s.r} (${s.w} wide × ${s.h} tall)` : `${s.w} wide × ${s.h} tall`;
    return `| ${s.id} | ${describeType(s)} | ${s.color} | (${s.cx}, ${s.cy}) | ${size} |`;
  });
  return [
    `Canvas: ${CANVAS} × ${CANVAS} pixels, white background. x runs from 0 (left) to ${CANVAS} (right); y runs from 0 (top) to ${CANVAS} (bottom). Positions are the centre of each shape's bounding box. No shapes overlap.`,
    '',
    '| # | shape | colour | centre (x, y) | size |',
    '|---|-------|--------|---------------|------|',
    ...rows,
  ].join('\n');
}
