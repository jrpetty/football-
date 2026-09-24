// "Spot the Difference": two generated grids of shapes, side by side. The RIGHT grid is a copy of the LEFT grid
// with a known list of changes applied, so the answer key is exactly that list.
import { createRng } from '../../src/core/rng.ts';
import type { Rng } from '../../src/core/types.ts';
import { COLORS, SHAPES, shape, svgDoc, text, type ShapeKind } from './lib.mts';
import type { VisionCase } from './charts.mts';

interface Obj {
  shape: ShapeKind;
  color: string;
  scale: number;
  flip: boolean;
  dot: boolean;
}

type Change = 'colour' | 'shape' | 'size' | 'missing' | 'extra' | 'orientation' | 'dot';

const COLOR_NAMES = Object.keys(COLORS);

function randomObj(rng: Rng, withDots: boolean): Obj {
  return { shape: rng.pick(SHAPES), color: rng.pick(COLOR_NAMES), scale: 1, flip: false, dot: withDots && rng.chance(0.3) };
}

function cellName(col: number, row: number): string {
  return `${String.fromCharCode(65 + col)}${row + 1}`;
}

function drawGrid(grid: Array<Obj | null>, n: number, x0: number, y0: number, size: number, caption: string): string {
  const cell = size / n;
  let s = `<rect x="${x0}" y="${y0}" width="${size}" height="${size}" fill="#f8fafc" stroke="#334155" stroke-width="2.5"/>`;
  for (let i = 1; i < n; i++) {
    s += `<line x1="${x0 + cell * i}" y1="${y0}" x2="${x0 + cell * i}" y2="${y0 + size}" stroke="#cbd5e1" stroke-width="1.5"/>`;
    s += `<line x1="${x0}" y1="${y0 + cell * i}" x2="${x0 + size}" y2="${y0 + cell * i}" stroke="#cbd5e1" stroke-width="1.5"/>`;
  }
  for (let c = 0; c < n; c++) s += text(x0 + cell * (c + 0.5), y0 - 12, String.fromCharCode(65 + c), { size: 20, anchor: 'middle', weight: 700, fill: '#334155' });
  for (let r = 0; r < n; r++) s += text(x0 - 14, y0 + cell * (r + 0.5) + 7, String(r + 1), { size: 20, anchor: 'end', weight: 700, fill: '#334155' });
  s += text(x0 + size / 2, y0 + size + 44, caption, { size: 26, anchor: 'middle', weight: 800, fill: '#0f172a' });
  grid.forEach((o, i) => {
    if (!o) return;
    const c = i % n;
    const r = Math.floor(i / n);
    const rad = cell * 0.34 * o.scale;
    s += shape(o.shape, x0 + cell * (c + 0.5), y0 + cell * (r + 0.5), rad, COLORS[o.color]!, { rotation: o.flip ? 180 : 0, dot: o.dot });
  });
  return s;
}

function applyChange(rng: Rng, o: Obj | null, kind: Change, withDots: boolean): Obj | null {
  switch (kind) {
    case 'missing':
      return null;
    case 'extra':
      return randomObj(rng, withDots);
    case 'colour':
      return { ...o!, color: rng.pick(COLOR_NAMES.filter((c) => c !== o!.color)) };
    case 'shape':
      return { ...o!, shape: rng.pick(SHAPES.filter((s) => s !== o!.shape && !(o!.flip && s === 'triangle'))) };
    case 'size':
      return { ...o!, scale: 0.55 };
    case 'orientation':
      return { ...o!, flip: !o!.flip };
    case 'dot':
      return { ...o!, dot: !o!.dot };
  }
}

interface SpotSpec {
  id: string;
  seed: number;
  n: number;
  changes: Change[];
  dots: boolean;
  typed?: boolean;
  level: 'standard' | 'hard';
}

function buildSpot(spec: SpotSpec): VisionCase {
  const rng = createRng(spec.seed);
  const cells = spec.n * spec.n;
  const left: Array<Obj | null> = Array.from({ length: cells }, () => (rng.chance(0.14) ? null : randomObj(rng, spec.dots)));
  // Triangles are the only shape whose orientation is visible; keep some for orientation changes.
  const right = left.slice();
  const used = new Set<number>();
  const diffs: Array<{ cell: string; change: Change; index: number }> = [];
  for (const kind of spec.changes) {
    let idx = -1;
    for (let tries = 0; tries < 1000; tries++) {
      const i = rng.int(0, cells - 1);
      if (used.has(i)) continue;
      const o = left[i];
      if (kind === 'extra' ? o !== null : o === null) continue;
      if (kind === 'orientation' && o?.shape !== 'triangle') continue;
      // Keep changed cells apart so no two differences touch (each difference is one clearly separate cell).
      const [c, r] = [i % spec.n, Math.floor(i / spec.n)];
      if ([...used].some((u) => Math.abs((u % spec.n) - c) <= 1 && Math.abs(Math.floor(u / spec.n) - r) <= 1)) continue;
      idx = i;
      break;
    }
    if (idx < 0) {
      // Orientation needs a triangle: plant one in the left grid if none is free.
      if (kind === 'orientation') {
        const free = [...Array(cells).keys()].find((i) => !used.has(i) && left[i] && ![...used].some((u) => Math.abs((u % spec.n) - (i % spec.n)) <= 1 && Math.abs(Math.floor(u / spec.n) - Math.floor(i / spec.n)) <= 1));
        if (free === undefined) throw new Error(`${spec.id}: no room for an orientation change`);
        left[free] = { ...left[free]!, shape: 'triangle', flip: false };
        right[free] = left[free];
        idx = free;
      } else throw new Error(`${spec.id}: could not place a ${kind} change`);
    }
    used.add(idx);
    right[idx] = applyChange(rng, left[idx] ?? null, kind, spec.dots);
    diffs.push({ cell: cellName(idx % spec.n, Math.floor(idx / spec.n)), change: kind, index: idx });
  }
  // Sanity: the diff list must be exactly the set of cells whose drawing differs.
  const differing = left.map((o, i) => JSON.stringify(o) !== JSON.stringify(right[i])).reduce((a, d) => a + (d ? 1 : 0), 0);
  if (differing !== diffs.length) throw new Error(`${spec.id}: ${differing} cells differ but ${diffs.length} changes were recorded`);

  const W = 1400;
  const size = 560;
  const H = 110 + size + 90;
  const body =
    text(W / 2, 50, 'Spot the difference', { size: 30, anchor: 'middle', weight: 800, fill: '#0f172a' }) +
    drawGrid(left, spec.n, 110, 100, size, 'LEFT') +
    drawGrid(right, spec.n, 110 + size + 150, 100, size, 'RIGHT');
  const svg = svgDoc(W, H, body);
  const sorted = diffs.slice().sort((a, b) => a.cell.localeCompare(b.cell, 'en', { numeric: true }));
  const last = String.fromCharCode(64 + spec.n);
  const intro = `The image shows two ${spec.n}×${spec.n} grids of shapes, LEFT and RIGHT. The RIGHT grid is a copy of the LEFT grid with a few cells changed. Cells are named by column letter (A–${last}, left to right) and row number (1–${spec.n}, top to bottom), and the same labels are printed on both grids; for example, B3 is column B, row 3.`;
  const kinds = spec.dots
    ? 'A change can be a different colour, a different shape, a different size, a triangle pointing the other way, a small white dot added or removed, or an object that is missing from one grid.'
    : 'A change can be a different colour, a different shape, a different size, or an object that is missing from one grid.';
  let prompt: string;
  let expected: unknown;
  if (spec.typed) {
    prompt = `${intro} ${kinds} Each changed cell has exactly one change.\n\nFor every changed cell, name the cell and the kind of change, using exactly one of these words: "colour" (same shape, different colour), "shape" (different shape), "size" (same shape and colour, different size), "missing" (an object in LEFT has no object in RIGHT), "extra" (an empty cell in LEFT has an object in RIGHT).\n\nEnd your reply with a JSON object in exactly this form (any order): {"differences": [{"cell": "A1", "change": "colour"}, {"cell": "C4", "change": "missing"}]}`;
    expected = { differences: sorted.map((d) => ({ cell: d.cell, change: d.change })) };
  } else {
    prompt = `${intro} ${kinds} List every cell that differs between the two grids.\n\nEnd your reply with a JSON object in exactly this form, listing each differing cell once (any order): {"cells": ["A1", "C4"]}`;
    expected = { cells: sorted.map((d) => d.cell) };
  }
  return {
    id: spec.id,
    prompt,
    expected,
    notes: `[${spec.level}] ${spec.n}×${spec.n} grid, ${diffs.length} changes: ${sorted.map((d) => `${d.cell} ${d.change}`).join(', ')}. The generator copies LEFT to RIGHT and applies exactly these changes (no two changed cells touch, even diagonally); it asserts that no other cell differs. Generated by verification/vision/spot.mts (seed ${spec.seed}).`,
    image: { file: `spot-${spec.id}.png`, svg, width: W, height: H },
  };
}

export function buildSpots(): VisionCase[] {
  return [
    buildSpot({ id: 's01', seed: 12001, n: 4, changes: ['colour', 'shape', 'missing'], dots: false, level: 'standard' }),
    buildSpot({ id: 's02', seed: 12002, n: 5, changes: ['colour', 'extra', 'size', 'shape'], dots: false, level: 'standard' }),
    buildSpot({ id: 's03', seed: 12003, n: 6, changes: ['colour', 'dot', 'orientation', 'size', 'missing'], dots: true, level: 'hard' }),
    buildSpot({ id: 's04', seed: 12004, n: 7, changes: ['colour', 'shape', 'size', 'missing', 'extra', 'colour'], dots: false, typed: true, level: 'hard' }),
    buildSpot({ id: 's05', seed: 12005, n: 8, changes: ['dot', 'colour', 'orientation', 'shape', 'dot', 'size', 'extra'], dots: true, level: 'hard' }),
  ];
}
