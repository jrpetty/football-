/**
 * Scripted Draw It Blind players used by tests and difficulty reports.
 *
 *  - oracle: redraws the exact target (upper bound).
 *  - qualitative: simulates an ideal describer that can only convey what plain
 *    words allow — positions snapped to a grid ("top-left", "centre"), sizes to
 *    tenths of the canvas, colours by name — and that runs out of words after
 *    `limit / wordsPerShape` shapes. The drawer renders exactly that. Options
 *    model common weaknesses: merging close colours (navy→blue, teal→green,
 *    brown→orange, gray→black) and ignoring triangle direction.
 */
import type { Responder } from './fake-model.ts';
import { CANVAS, paletteHex, renderSceneSvg } from '../../src/programs/lib/draw-it-blind-scene.ts';
import type { PaletteName, Scene, SceneShape } from '../../src/programs/lib/draw-it-blind-scene.ts';

const DESCRIBE = 'Several coloured shapes are spread across a white canvas; each is described by where it sits and how big it is.';
const isDescribe = (u: string) => u.includes('PICTURE');

export function oraclePolicy(scene: Scene): Responder {
  return (_s, u) => (isDescribe(u) ? DESCRIBE : `\`\`\`svg\n${renderSceneSvg(scene)}\n\`\`\``);
}

export interface QualitativeOptions {
  /** Grid used for positions (3 = thirds, 5 = fifths). */
  grid: number;
  /** Words the describer needs per shape. */
  wordsPerShape: number;
  /** Description word limit. */
  limit: number;
  mergeCloseColours?: boolean;
  ignoreDirection?: boolean;
}

const MERGE: Partial<Record<PaletteName, PaletteName>> = { navy: 'blue', teal: 'green', brown: 'orange', gray: 'black' };

function redraw(s: SceneShape, o: QualitativeOptions): string {
  const cell = CANVAS / o.grid;
  const snap = (v: number) => (Math.min(o.grid - 1, Math.floor(v / cell)) + 0.5) * cell;
  const tenth = (v: number) => Math.max(20, Math.round(v / 40) * 40);
  const cx = snap(s.cx);
  const cy = snap(s.cy);
  const w = tenth(s.w);
  const h = tenth(s.h);
  const color = paletteHex((o.mergeCloseColours && MERGE[s.color]) || s.color);
  switch (s.type) {
    case 'circle':
      return `<circle cx="${cx}" cy="${cy}" r="${w / 2}" fill="${color}"/>`;
    case 'square':
    case 'rectangle':
      return `<rect x="${cx - w / 2}" y="${cy - h / 2}" width="${w}" height="${h}" fill="${color}"/>`;
    case 'triangle': {
      const rot = o.ignoreDirection || s.angle === undefined ? 0 : ((s.angle + 90) * Math.PI) / 180;
      const base: Array<[number, number]> = [
        [0, -h / 2],
        [w / 2, h / 2],
        [-w / 2, h / 2],
      ];
      const pts = base.map(([x, y]) => [cx + x * Math.cos(rot) - y * Math.sin(rot), cy + x * Math.sin(rot) + y * Math.cos(rot)]);
      return `<polygon points="${pts.map(([x, y]) => `${x!.toFixed(1)},${y!.toFixed(1)}`).join(' ')}" fill="${color}"/>`;
    }
    case 'star': {
      const r = Math.max(w, h) / 2;
      const pts = Array.from({ length: 10 }, (_, i) => {
        const rad = i % 2 ? r * 0.45 : r;
        const a = -Math.PI / 2 + (i * Math.PI) / 5;
        return `${(cx + rad * Math.cos(a)).toFixed(1)},${(cy + rad * Math.sin(a)).toFixed(1)}`;
      });
      return `<polygon points="${pts.join(' ')}" fill="${color}"/>`;
    }
  }
}

export function qualitativePolicy(scene: Scene, o: QualitativeOptions): Responder {
  const covered = scene.shapes.slice(0, Math.floor(o.limit / o.wordsPerShape));
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400"><rect width="400" height="400" fill="#fff"/>${covered.map((s) => redraw(s, o)).join('')}</svg>`;
  return (_s, u) => (isDescribe(u) ? DESCRIBE : `\`\`\`svg\n${svg}\n\`\`\``);
}
