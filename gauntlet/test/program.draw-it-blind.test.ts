import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRng } from '../src/core/rng.ts';
import { program, buildScene, checkDescription, readConfig } from '../src/programs/draw-it-blind.ts';
import { CANVAS, generateScene, PALETTE, paletteHex, renderSceneSvg } from '../src/programs/lib/draw-it-blind-scene.ts';
import type { Scene, SceneShape } from '../src/programs/lib/draw-it-blind-scene.ts';
import {
  classifyPolygon,
  triangleApexAngle,
  extractSvg,
  nearestPalette,
  parseColor,
  parsePath,
  parseSvg,
  sanitizeSvg,
} from '../src/programs/lib/draw-it-blind-svg.ts';
import type { DrawnShape } from '../src/programs/lib/draw-it-blind-svg.ts';
import { matchShapes, pairScore, MIN_PAIR, orientationFactor } from '../src/programs/lib/draw-it-blind-match.ts';
import { oraclePolicy, qualitativePolicy } from './helpers/draw-it-blind-policies.ts';
import { EXTENDED_PALETTE } from '../src/programs/lib/draw-it-blind-scene.ts';
import {
  constantResponder,
  createFakeModel,
  createTestContext,
  mockBaselineResponder,
  randomBacktickResponder,
} from './helpers/fake-model.ts';
import type { Responder } from './helpers/fake-model.ts';

function sceneFor(seed: number): Scene {
  return buildScene({ rng: createRng(seed) }, readConfig(program.defaults ?? {}));
}

async function play(seed: number, responder: Responder, config: Record<string, unknown> = {}) {
  const model = createFakeModel(responder);
  const { ctx, artifacts } = createTestContext({ seed, model, config, defaults: program.defaults });
  const result = await program.run(ctx);
  return { result, model, artifacts };
}

const WORDS_ONLY = 'Five bright shapes are spread around a white square canvas, none of them touching.';
const isDescribe = (u: string) => u.includes('PICTURE');

/** Describes without digits, then draws the exact target (it has the ground truth). */
function oracle(scene: Scene): Responder {
  return (_s, u) => (isDescribe(u) ? WORDS_ONLY : `Here you go:\n\`\`\`svg\n${renderSceneSvg(scene)}\n\`\`\``);
}

function shape(p: Partial<DrawnShape>): DrawnShape {
  return { kind: 'circle', tag: 'circle', rgb: [0, 0, 0], color: 'red', cx: 100, cy: 100, w: 50, h: 50, ...p };
}

const svgOf = (body: string, attrs = 'width="400" height="400" viewBox="0 0 400 400"') => `<svg xmlns="http://www.w3.org/2000/svg" ${attrs}>${body}</svg>`;
const one = (body: string, attrs?: string) => {
  const p = parseSvg(svgOf(body, attrs));
  assert.equal(p.shapes.length, 1, `expected one shape, got ${JSON.stringify(p)}`);
  return p.shapes[0]!;
};
const near = (a: number, b: number, eps = 1) => assert.ok(Math.abs(a - b) <= eps, `${a} ≈ ${b}`);

// ─── scene ──────────────────────────────────────────────────────────────────

test('draw-it-blind: scenes have 5–7 non-overlapping shapes, distinct colours, ≥3 types, inside the canvas', () => {
  for (let seed = 1; seed <= 200; seed++) {
    const { shapes } = generateScene(createRng(seed));
    assert.ok(shapes.length >= 5 && shapes.length <= 7);
    assert.equal(new Set(shapes.map((s) => s.color)).size, shapes.length);
    assert.ok(new Set(shapes.map((s) => s.type)).size >= 3);
    for (const [i, a] of shapes.entries()) {
      assert.ok(a.cx - a.w / 2 >= 0 && a.cx + a.w / 2 <= CANVAS && a.cy - a.h / 2 >= 0 && a.cy + a.h / 2 <= CANVAS, `seed ${seed} inside`);
      for (const b of shapes.slice(i + 1)) {
        const overlap = Math.abs(a.cx - b.cx) * 2 < a.w + b.w && Math.abs(a.cy - b.cy) * 2 < a.h + b.h;
        assert.ok(!overlap, `seed ${seed}: shapes ${a.id} and ${b.id} overlap`);
      }
    }
  }
});

test('draw-it-blind: every target SVG parses back to itself (self-match ≈ 100%)', () => {
  for (let seed = 1; seed <= 100; seed++) {
    const scene = generateScene(createRng(seed));
    const parsed = parseSvg(renderSceneSvg(scene));
    const m = matchShapes(scene.shapes, parsed.shapes);
    assert.ok(m.match > 0.99, `seed ${seed}: ${m.match}`);
    assert.equal(m.rebuilt, scene.shapes.length);
    assert.deepEqual(parsed.shapes.map((s) => s.color), scene.shapes.map((s) => s.color));
  }
});

// ─── program ────────────────────────────────────────────────────────────────

test('draw-it-blind: determinism — same seed, same prompts and result; seeds differ', async () => {
  const s = sceneFor(101);
  const a = await play(101, oracle(s));
  const b = await play(101, oracle(s));
  assert.deepEqual(a.model.calls.map((c) => c.messages), b.model.calls.map((c) => c.messages));
  assert.deepEqual(a.result, b.result);
  const prompts = new Set([101, 202, 303, 404].map((seed) => renderSceneSvg(sceneFor(seed))));
  assert.equal(prompts.size, 4);
});

test('draw-it-blind: oracle scores ≥ 0.9 with svgCompare and both artifacts', async () => {
  for (const seed of [101, 202, 303, 7, 8]) {
    const scene = sceneFor(seed);
    const { result, artifacts, model } = await play(seed, oracle(scene));
    assert.ok(result.score >= 0.99, `seed ${seed}: ${result.score}`);
    assert.equal(result.summary, `Rebuilt ${scene.shapes.length}/${scene.shapes.length} shapes · 100% match`);
    assert.deepEqual(artifacts.map((a) => a.name), ['target.svg', 'drawn.svg']);
    assert.equal(result.replay!.svgCompare!.left.svg, renderSceneSvg(scene));
    assert.ok(result.replay!.svgCompare!.right.svg.includes('<svg'));
    assert.equal(model.calls.length, 2);
    // Fresh context: the draw call sees only the description, never the scene table.
    const drawCall = model.calls[1]!.messages;
    assert.equal(drawCall.length, 1);
    assert.ok(drawCall[0]!.content.includes(WORDS_ONLY));
    assert.ok(!drawCall[0]!.content.includes('PICTURE') && !drawCall[0]!.content.includes('| # |'));
  }
});

test('draw-it-blind: a plausible word-level redraw scores in between', async () => {
  const scene = sceneFor(202);
  // Right shapes and colours, but positions ~40px off and sizes ~20% off.
  const body = scene.shapes
    .map((s) => {
      const cx = s.cx + 30;
      const cy = s.cy - 25;
      const w = s.w * 1.2;
      const h = s.h * 0.85;
      const fill = s.color;
      if (s.type === 'circle') return `<circle cx="${cx}" cy="${cy}" r="${w / 2}" fill="${fill}"/>`;
      if (s.type === 'triangle') return `<polygon points="${cx},${cy - h / 2} ${cx + w / 2},${cy + h / 2} ${cx - w / 2},${cy + h / 2}" fill="${fill}"/>`;
      if (s.type === 'star') return `<polygon points="${s.points!.map(([x, y]) => `${x + 30},${y - 25}`).join(' ')}" fill="${fill}"/>`;
      return `<rect x="${cx - w / 2}" y="${cy - h / 2}" width="${w}" height="${h}" fill="${fill}"/>`;
    })
    .join('');
  const { result } = await play(202, (_s, u) => (isDescribe(u) ? WORDS_ONLY : svgOf(body)));
  assert.ok(result.score > 0.7 && result.score < 0.97, `score ${result.score}`);
});

test('draw-it-blind: garbage, empty, refusal and random-backtick policies score 0 without crashing', async () => {
  for (const r of [constantResponder('lorem ipsum dolor sit amet'), randomBacktickResponder(11), constantResponder('<svg><circle r="x"/><rect width=></svg>')]) {
    const { result } = await play(303, r);
    assert.equal(result.score, 0, JSON.stringify(result.summary));
  }
  const empty = await play(303, constantResponder(''));
  assert.equal(empty.result.score, 0);
  assert.equal(empty.model.calls.length, 1, 'no draw call without a description');
  assert.equal(empty.result.summary, 'No description · 0% match');
  const refused = await play(303, constantResponder('I cannot help with that.', 'refusal'));
  assert.equal(refused.result.score, 0);
  const noSvg = await play(303, (_s, u) => (isDescribe(u) ? WORDS_ONLY : 'Sorry, I cannot draw.'));
  assert.equal(noSvg.result.summary, 'No drawing returned · 0% match');
  assert.ok(noSvg.result.replay!.svgCompare!.right.svg.includes('No drawing'));
});

test('draw-it-blind: the real Random Baseline (one random gray circle) scores low', async () => {
  let total = 0;
  for (let seed = 1; seed <= 30; seed++) total += (await play(seed, mockBaselineResponder(`b${seed}`))).result.score;
  assert.ok(total / 30 < 0.1, `baseline mean ${total / 30}`);
});

test('draw-it-blind: model errors propagate', async () => {
  await assert.rejects(play(101, () => Promise.reject(new Error('503'))), /503/);
});

test('draw-it-blind: digits are stripped before drawing and penalised', async () => {
  const scene = sceneFor(101);
  const good = oracle(scene);
  const { result, model } = await play(101, (s, u, h, i) =>
    isDescribe(u) ? 'A red rectangle at x 337 and y 150, one hundred and twenty wide, plus 4 more shapes.' : good(s, u, h, i),
  );
  const drawText = model.calls[1]!.messages[0]!.content;
  const delivered = drawText.slice(drawText.indexOf('"""') + 3, drawText.lastIndexOf('"""'));
  assert.ok(!/\d/.test(delivered), `digits leaked: ${delivered}`);
  assert.ok(!/hundred/i.test(delivered));
  const d = result.detail as { numberViolations: string[]; penalties: { description: number } };
  assert.deepEqual(d.numberViolations, ['337', '150', '4', 'one hundred and twenty']);
  assert.ok(!/\bone\b|\band\s*,/.test(delivered), 'the whole number phrase is removed');
  assert.equal(d.penalties.description, 0.16);
  assert.equal(result.score, 0.84);
  assert.match(result.summary, /· −16% for digits$/);
});

test('draw-it-blind: description checks (limit, small numbers allowed)', () => {
  const ok = checkDescription('Two circles sit a third of the way down, one on each side; three squares fill the bottom.', 120);
  assert.deepEqual(ok.violations, []);
  assert.equal(ok.penalty, 0);
  const long = checkDescription(Array.from({ length: 150 }, () => 'word').join(' '), 120);
  assert.equal(long.truncated, true);
  assert.equal(long.delivered.split(' ').length, 120);
  assert.equal(long.penalty, 0.05);
  const nums = checkDescription('Twenty-five percent across, 50% down, twelve o\'clock.', 120);
  assert.deepEqual(nums.violations, ['50%', 'Twenty-five', 'twelve']);
  assert.equal(nums.delivered, "percent across, down, o'clock.");
});

test('draw-it-blind: prompts end with the exact output format and use no stray backticks', async () => {
  const s = sceneFor(101);
  const { model } = await play(101, oracle(s));
  const [describe, draw] = model.calls.map((c) => c.messages[0]!.content) as [string, string];
  assert.match(describe, /\n\nReply with only the description: plain prose, at most 120 words, no digits\.$/);
  assert.match(draw, /\n\nReply with a single ```svg code block and nothing else\.$/);
  assert.ok(!describe.includes('`'));
});

// ─── SVG parser ─────────────────────────────────────────────────────────────

test('svg: attributes in any order, units and percentages', () => {
  const c = one('<circle fill="red" r="40" cy="100" cx="50"/>');
  assert.equal(c.kind, 'circle');
  assert.equal(c.color, 'red');
  near(c.cx, 50);
  near(c.cy, 100);
  near(c.w, 80);
  const r = one("<rect height='50%' width = '100px' y='0' x='0' fill='#0000ff'/>");
  assert.equal(r.kind, 'rectangle');
  near(r.h, 200);
  near(r.w, 100);
});

test('svg: colours — hex, short hex, rgb(), rgba(), hsl(), named, style="", classes, gradients', () => {
  assert.equal(one('<rect x="0" y="0" width="50" height="50" style="fill: #00f; stroke: none"/>').color, 'blue');
  assert.equal(one('<rect x="0" y="0" width="50" height="50" fill="rgb(30, 136, 229)"/>').color, 'blue');
  assert.equal(one('<rect x="0" y="0" width="50" height="50" fill="rgba(255,0,0,0.6)"/>').color, 'red');
  assert.equal(one('<rect x="0" y="0" width="50" height="50" fill="hsl(120, 100%, 25%)"/>').color, 'green');
  assert.equal(one('<style>.a { fill: gold }</style><rect class="a" x="0" y="0" width="50" height="50"/>').color, 'yellow');
  assert.equal(one('<style><![CDATA[ #s { fill: hotpink; } ]]></style><rect id="s" x="0" y="0" width="50" height="50" fill="blue"/>').color, 'pink');
  assert.equal(
    one('<defs><linearGradient id="g"><stop offset="0" stop-color="#8e24aa"/><stop offset="1" stop-color="#fff"/></linearGradient></defs><circle cx="50" cy="50" r="20" fill="url(#g)"/>').color,
    'purple',
  );
  const names: Record<string, string> = {
    royalblue: 'blue', navy: 'blue', gold: 'yellow', hotpink: 'pink', pink: 'pink', darkorange: 'orange', orange: 'orange',
    forestgreen: 'green', lime: 'green', purple: 'purple', violet: 'purple', crimson: 'red', black: 'black', '#222': 'black',
  };
  for (const [css, want] of Object.entries(names)) assert.equal(nearestPalette(parseColor(css)!.rgb), want, css);
  for (const p of PALETTE) assert.equal(nearestPalette(parseColor(paletteHex(p.name))!.rgb), p.name);
  assert.equal(parseColor('none'), null);
  assert.equal(parseColor('url(#x)'), null);
  assert.equal(parseColor('#12345678')!.alpha, 0x78 / 255);
});

test('svg: inherited group styles, fill="none" with stroke, invisible and white shapes', () => {
  assert.equal(one('<g fill="green"><circle cx="50" cy="50" r="20"/></g>').color, 'green');
  assert.equal(one('<circle cx="50" cy="50" r="20" fill="none" stroke="purple" stroke-width="3"/>').color, 'purple');
  const p = parseSvg(
    svgOf(
      '<rect width="400" height="400" fill="white"/><rect width="400" height="400" fill="#eee"/><circle cx="5" cy="5" r="30" fill="#ffffff"/>' +
        '<circle cx="50" cy="50" r="20" fill="none"/><circle cx="50" cy="50" r="20" fill="red" opacity="0"/><g display="none"><rect width="9" height="9"/></g>' +
        '<line x1="0" y1="0" x2="50" y2="50" stroke="black"/><defs><circle id="c" r="30" fill="red"/></defs>',
    ),
  );
  assert.equal(p.shapes.length, 0);
  assert.deepEqual(
    p.ignored.map((i) => i.reason),
    ['background', 'background', 'white', 'no visible fill or stroke', 'no visible fill or stroke', 'hidden', 'line'],
  );
});

test('svg: transforms — translate, scale, rotate, matrix, nested groups, viewBox', () => {
  const t = one('<g transform="translate(100,50)"><rect x="0" y="0" width="40" height="40" fill="red"/></g>');
  near(t.cx, 120);
  near(t.cy, 70);
  const nested = one('<g transform="translate(10 10)"><g transform="scale(2)"><circle cx="20" cy="20" r="10" fill="red"/></g></g>');
  near(nested.cx, 50);
  near(nested.w, 40);
  const rot = one('<rect x="-20" y="-20" width="40" height="40" fill="blue" transform="translate(200,200) rotate(45)"/>');
  assert.equal(rot.kind, 'quad');
  near(rot.w, 56.6);
  const rot90 = one('<rect x="0" y="0" width="100" height="40" fill="blue" transform="rotate(90 50 20)"/>');
  assert.equal(rot90.kind, 'rectangle');
  near(rot90.w, 40);
  near(rot90.h, 100);
  const mat = one('<circle cx="0" cy="0" r="10" fill="red" transform="matrix(1 0 0 1 30 40)"/>');
  near(mat.cx, 30);
  near(mat.cy, 40);
  const vb = one('<circle cx="50" cy="25" r="10" fill="red"/>', 'viewBox="0 0 100 100"');
  near(vb.cx, 200);
  near(vb.cy, 100);
  near(vb.w, 80);
  const big = one('<circle cx="400" cy="400" r="100" fill="red"/>', 'width="800" height="800"');
  near(big.cx, 200);
  near(big.w, 100);
});

test('svg: polygon classification — triangle, star, pentagram, pentagon, square, diamond', () => {
  assert.equal(classifyPolygon([[0, 0], [10, 0], [5, 8]]), 'triangle');
  assert.equal(classifyPolygon([[0, 0], [10, 0], [10, 10], [0, 10]]), 'square');
  assert.equal(classifyPolygon([[0, 0], [30, 0], [30, 10], [0, 10], [0, 0]]), 'rectangle');
  assert.equal(classifyPolygon([[5, 0], [10, 5], [5, 10], [0, 5]]), 'quad');
  assert.equal(classifyPolygon([[0, 0], [5, 0], [10, 0], [10, 10], [0, 10]]), 'square', 'collinear midpoint dropped');
  const star10: Array<[number, number]> = Array.from({ length: 10 }, (_, i) => {
    const r = i % 2 ? 20 : 50;
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    return [r * Math.cos(a), r * Math.sin(a)];
  });
  assert.equal(classifyPolygon(star10), 'star');
  const penta = Array.from({ length: 5 }, (_, i) => [Math.cos((i * 2 * Math.PI) / 5), Math.sin((i * 2 * Math.PI) / 5)] as [number, number]);
  assert.equal(classifyPolygon(penta), 'polygon');
  const pentagram = [0, 2, 4, 1, 3].map((k) => penta[k]!);
  assert.equal(classifyPolygon(pentagram), 'star');
  const round = Array.from({ length: 36 }, (_, i) => [50 * Math.cos(i / 5.73), 50 * Math.sin(i / 5.73)] as [number, number]);
  assert.equal(classifyPolygon(round), 'circle');
  assert.equal(one('<polyline points="10,10 90,10 50,80" fill="orange"/>').kind, 'triangle');
});

test('svg: paths — lines, relative commands, H/V, arcs (incl. compact flags), cubic circles, subpaths', () => {
  assert.equal(one('<path d="M 10 10 L 50 10 L 30 40 Z" fill="red"/>').kind, 'triangle');
  assert.equal(one('<path d="m10 10 l40 0 l-20 30z" fill="red"/>').kind, 'triangle');
  const r = one('<path d="M10 10 H 110 V 50 H 10 Z" fill="red"/>');
  assert.equal(r.kind, 'rectangle');
  near(r.w, 100);
  for (const d of [
    'M 150 100 A 50 50 0 1 0 50 100 A 50 50 0 1 0 150 100 Z',
    'M150,100a50,50 0 1,0 -100,0a50,50 0 1,0 100,0',
    'M150 100a50 50 0 1 0-100 0a50 50 0 1 0 100 0z',
    'M150,100a50,50,0,1,0-100,0a50,50,0,1,0,100,0',
  ]) {
    const c = one(`<path d="${d}" fill="green"/>`);
    assert.equal(c.kind, 'circle', d);
    near(c.cx, 100);
    near(c.cy, 100);
    near(c.w, 100, 2);
  }
  const k = 0.5523 * 50;
  const cubic = one(`<path d="M100 50 C${100 + k} 50 150 ${100 - k} 150 100 S ${100 + k} 150 100 150 S 50 ${100 + k} 50 100 S ${100 - k} 50 100 50 Z" fill="red"/>`);
  assert.equal(cubic.kind, 'circle');
  const ellipse = one('<path d="M 160 100 A 60 30 0 1 0 40 100 A 60 30 0 1 0 160 100" fill="red"/>');
  assert.equal(ellipse.kind, 'ellipse');
  const two = parseSvg(svgOf('<path d="M10 10 L50 10 L30 40 Z M100 100 L140 100 L120 140 Z" fill="red"/>'));
  assert.equal(two.shapes.length, 2);
  assert.equal(parsePath('garbage 12 ,,').length, 0);
});

test('svg: extraction and sanitising', () => {
  const inner = '<svg viewBox="0 0 10 10"><circle r="1"/></svg>';
  assert.equal(extractSvg(`Sure!\n\`\`\`svg\n${inner}\n\`\`\`\nDone.`), inner);
  assert.equal(extractSvg(`\`\`\`xml\n<?xml version="1.0"?>\n${inner}\n\`\`\``), inner);
  assert.equal(extractSvg(`Here: ${inner} ok`), inner);
  assert.equal(extractSvg('```svg\n<svg><circle r="1"/>'), '<svg><circle r="1"/></svg>');
  assert.equal(extractSvg('no svg at all'), null);
  const dirty = '<svg onload="alert(1)"><script>alert(2)</script><circle r="4" onclick="x()"/><a href="javascript:evil()"><rect/></a></svg>';
  const clean = sanitizeSvg(dirty);
  assert.ok(!/script|onload|onclick|javascript/i.test(clean), clean);
  assert.ok(clean.includes('xmlns="http://www.w3.org/2000/svg"'));
  assert.doesNotThrow(() => parseSvg('<svg><g><g><circle r="5" cx="a"/></svg>'));
  assert.equal(parseSvg('<svg><path d="M 0 0 L 1e400 0 L 5 5 Z"/></svg>').shapes.length >= 0, true);
});

// ─── matcher ────────────────────────────────────────────────────────────────

const target = (p: Partial<SceneShape>): SceneShape => ({ id: 1, type: 'circle', color: 'red', cx: 100, cy: 100, w: 50, h: 50, ...p });

test('matcher: pair similarity components', () => {
  const t = target({});
  assert.equal(pairScore(t, shape({})).total, 1);
  assert.ok(Math.abs(pairScore(t, shape({ color: 'blue' })).total - 0.7) < 1e-9);
  assert.ok(Math.abs(pairScore(t, shape({ kind: 'ellipse' })).total - (1 - 0.3 * 0.4)) < 1e-9);
  const far = pairScore(t, shape({ cx: 100 + 160 }));
  assert.equal(far.position, 0);
  const small = pairScore(t, shape({ w: 25, h: 25 }));
  assert.equal(small.size, 0.5);
});

test('matcher: unmatched targets score 0 and extra shapes cost a little', () => {
  const ts = [target({ id: 1 }), target({ id: 2, cx: 300, color: 'blue' })];
  const one = matchShapes(ts, [shape({})]);
  assert.equal(one.geometric, 0.5);
  assert.equal(one.pairs[1]!.drawn, null);
  const extra = matchShapes(ts, [shape({}), shape({ cx: 300, color: 'blue' }), shape({ cx: 20, cy: 380, color: 'green' }), shape({ cx: 380, cy: 380, kind: 'triangle', color: 'pink' })]);
  assert.equal(extra.geometric, 1);
  assert.equal(extra.extras.length, 2);
  assert.ok(Math.abs(extra.match - 0.94) < 1e-9);
  assert.equal(matchShapes(ts, []).match, 0);
});

test('matcher: the DP assignment is optimal (equals brute force) where greedy is not', () => {
  // Greedy would pair T1 with D1 (best single pair) and strand T2.
  const t1 = target({ id: 1, cx: 100, cy: 100 });
  const t2 = target({ id: 2, cx: 140, cy: 100, type: 'square' });
  const d1 = shape({ cx: 110, cy: 100, kind: 'square' });
  const d2 = shape({ cx: 95, cy: 100, kind: 'ellipse' });
  const m = matchShapes([t1, t2], [d1, d2]);
  assert.equal(m.pairs[0]!.drawn, d2);
  assert.equal(m.pairs[1]!.drawn, d1);

  const rng = createRng(42);
  const kinds = ['circle', 'square', 'rectangle', 'triangle', 'star', 'ellipse', 'quad'] as const;
  const colors = PALETTE.map((p) => p.name);
  for (let trial = 0; trial < 150; trial++) {
    const ts = generateScene(rng.fork(`t${trial}`), 3, 5).shapes;
    const ds: DrawnShape[] = Array.from({ length: rng.int(0, 6) }, () =>
      shape({ kind: rng.pick(kinds), color: rng.pick(colors), cx: rng.int(0, 400), cy: rng.int(0, 400), w: rng.int(20, 150), h: rng.int(20, 150) }),
    );
    let bestTotal = 0;
    const rec = (t: number, used: Set<number>, acc: number) => {
      if (t === ts.length) {
        bestTotal = Math.max(bestTotal, acc);
        return;
      }
      rec(t + 1, used, acc);
      ds.forEach((d, i) => {
        const s = pairScore(ts[t]!, d).total;
        if (used.has(i) || s < MIN_PAIR) return;
        used.add(i);
        rec(t + 1, used, acc + s);
        used.delete(i);
      });
    };
    rec(0, new Set(), 0);
    const got = matchShapes(ts, ds).pairs.reduce((a, p) => a + (p.score?.total ?? 0), 0);
    assert.ok(Math.abs(got - bestTotal) < 1e-9, `trial ${trial}: dp ${got} vs brute ${bestTotal}`);
  }
});

// ─── clarity rules + hard tier ─────────────────────────────────────────────

const HARD = {
  minShapes: 9,
  maxShapes: 12,
  descriptionWords: 90,
  palette: 'extended',
  overlap: true,
  rotation: true,
  positionFalloff: 60,
  weights: { type: 0.2, color: 0.2, position: 0.4, size: 0.2 },
};
const hardScene = (seed: number) => buildScene({ rng: createRng(seed) }, readConfig({ ...program.defaults, ...HARD }));

test('draw-it-blind: number rule — fractions up to tenths are fine; digits and numbers above ten are removed', () => {
  const ok = checkDescription('A navy circle two-thirds across and seven-tenths down, half the size of a quarter-width square; three stars.', 120);
  assert.deepEqual(ok.violations, []);
  const bad = checkDescription('A dot eleven-twentieths across, three twelfths down, and twenty-five percent wide.', 120);
  assert.deepEqual(bad.violations, ['eleven-twentieths', 'three twelfths', 'twenty-five']);
  assert.equal(bad.delivered, 'A dot across, down, and percent wide.');
});

test('draw-it-blind: prompts state how words are counted and the exact number rule', async () => {
  const s = sceneFor(101);
  const { model } = await play(101, oraclePolicy(s));
  const describe = model.calls[0]!.messages[0]!.content;
  assert.ok(describe.includes('Words are counted by splitting on spaces, so a hyphenated word such as "top-left" counts as one word.'));
  assert.ok(describe.includes('Numbers may only be spelled out, and only up to ten'));
  assert.ok(!describe.includes('palette of'), 'standard prompt has no palette line');
  assert.ok(describe.includes('No shapes overlap.'));
});

test('draw-it-blind hard: 9–12 shapes, 12-colour palette, rotated tall triangles, overlaps and nesting', () => {
  let nested = 0;
  let overlapping = 0;
  const colours = new Set<string>();
  const directions = new Set<string>();
  for (let seed = 1; seed <= 80; seed++) {
    const { shapes } = hardScene(seed);
    assert.ok(shapes.length >= 9 && shapes.length <= 12);
    assert.equal(new Set(shapes.map((x) => x.color)).size, shapes.length, 'distinct colours');
    shapes.forEach((x, i) => {
      colours.add(x.color);
      assert.equal(x.id, i + 1);
      if (i > 0) assert.ok(shapes[i - 1]!.w * shapes[i - 1]!.h >= x.w * x.h, 'listed back to front');
      assert.ok(x.cx - x.w / 2 >= 0 && x.cx + x.w / 2 <= CANVAS && x.cy - x.h / 2 >= 0 && x.cy + x.h / 2 <= CANVAS);
      if (x.type === 'triangle') {
        directions.add(x.direction!);
        assert.ok(triangleApexAngle(x.points!) !== null, 'clearly isosceles');
      }
      for (const y of shapes.slice(i + 1)) {
        if (Math.abs(x.cx - y.cx) * 2 <= x.w - y.w && Math.abs(x.cy - y.cy) * 2 <= x.h - y.h) nested++;
        else if (Math.abs(x.cx - y.cx) * 2 < x.w + y.w && Math.abs(x.cy - y.cy) * 2 < x.h + y.h) overlapping++;
      }
    });
  }
  assert.equal(colours.size, 12);
  assert.equal(directions.size, 8);
  assert.ok(nested >= 40 && overlapping >= 80, `nested ${nested}, overlapping ${overlapping}`);
});

test('draw-it-blind hard: targets parse back to themselves with the extended palette and orientation', () => {
  for (let seed = 1; seed <= 60; seed++) {
    const scene = hardScene(seed);
    const parsed = parseSvg(renderSceneSvg(scene), { palette: 'extended' });
    const m = matchShapes(scene.shapes, parsed.shapes, { orientation: true, positionFalloff: 60, weights: HARD.weights });
    assert.ok(m.match > 0.99, `seed ${seed}: ${m.match}`);
    assert.deepEqual(parsed.shapes.map((x) => x.color), scene.shapes.map((x) => x.color));
  }
});

test('draw-it-blind hard: close colours are kept apart only in the extended palette', () => {
  const cases: Record<string, string> = { navy: 'navy', darkblue: 'navy', blue: 'blue', royalblue: 'blue', teal: 'teal', turquoise: 'teal', green: 'green', brown: 'brown', saddlebrown: 'brown', gray: 'gray', silver: 'gray', black: 'black', orange: 'orange' };
  for (const [css, want] of Object.entries(cases)) assert.equal(nearestPalette(parseColor(css)!.rgb, 'extended'), want, css);
  for (const p of EXTENDED_PALETTE) assert.equal(nearestPalette(parseColor(p.anchors[0]!)!.rgb, 'extended'), p.name);
  assert.equal(nearestPalette(parseColor('navy')!.rgb), 'blue', 'basic palette unchanged');
  assert.equal(nearestPalette(parseColor('teal')!.rgb, 'basic') === 'teal', false);
});

test('draw-it-blind hard: triangle direction is detected and scored only when rotation is on', () => {
  near(triangleApexAngle([[50, 0], [70, 80], [30, 80]])!, -90);
  near(triangleApexAngle([[0, 50], [80, 30], [80, 70]])!, 180);
  assert.equal(triangleApexAngle([[0, 0], [10, 0], [5, 8.66]]), null, 'equilateral has no clear apex');
  assert.equal(orientationFactor(-90, -80), 1);
  assert.equal(orientationFactor(-90, -45), 0.7);
  assert.equal(orientationFactor(-90, 90), 0.4);
  assert.equal(orientationFactor(undefined, 90), 1);
  const t: SceneShape = { id: 1, type: 'triangle', color: 'red', cx: 100, cy: 100, w: 50, h: 70, angle: 90 };
  const drawnUp = shape({ kind: 'triangle', w: 50, h: 70, angle: -90 });
  assert.equal(pairScore(t, drawnUp).total, 1);
  assert.ok(Math.abs(pairScore(t, drawnUp, { orientation: true }).total - (1 - 0.3 * 0.6)) < 1e-9);
});

test('draw-it-blind hard: prompts list the palette in words, overlap and direction rules, and keep the output format', async () => {
  const scene = hardScene(101);
  const { model, result } = await play(101, oraclePolicy(scene), HARD);
  const [describe, draw] = model.calls.map((c) => c.messages[0]!.content) as [string, string];
  assert.ok(describe.includes('a palette of twelve (red, orange, yellow, green, teal, blue, navy, purple, pink, brown, black, gray)'));
  assert.ok(describe.includes('Shapes are listed from back to front'));
  assert.ok(describe.includes('Say which way each triangle points.'));
  assert.ok(describe.includes('at most 90 words'));
  assert.match(describe, /\n\nReply with only the description: plain prose, at most 90 words, no digits\.$/);
  assert.ok(draw.includes('similar names (navy and blue, teal and green) are different colours'));
  assert.match(draw, /\n\nReply with a single ```svg code block and nothing else\.$/);
  assert.ok(result.score >= 0.99);
});

test('draw-it-blind hard: ideal word-level redraws score well below the standard tier; baseline stays near 0', async () => {
  const meanOf = async (config: Record<string, unknown>, mk: (s: Scene, limit: number) => Responder) => {
    let total = 0;
    for (let seed = 1; seed <= 25; seed++) {
      const c = readConfig({ ...program.defaults, ...config });
      const scene = buildScene({ rng: createRng(seed) }, c);
      total += (await play(seed, mk(scene, c.descriptionWords), config)).result.score;
    }
    return total / 25;
  };
  const ideal = (s: Scene, limit: number) => qualitativePolicy(s, { grid: 5, wordsPerShape: 11, limit });
  const std = await meanOf({}, ideal);
  const hard = await meanOf(HARD, ideal);
  const baseline = await meanOf(HARD, () => mockBaselineResponder('b'));
  assert.ok(std > 0.88, `standard ${std}`);
  assert.ok(hard < 0.75 && hard < std - 0.2, `hard ${hard} vs standard ${std}`);
  assert.ok(baseline < 0.1, `baseline ${baseline}`);
});

test('hard tier: hyphenated compounds count as separate words (closes the "fifth-wide" loophole)', async () => {
  const { checkDescription, describePrompt } = await import('../src/programs/draw-it-blind.ts');
  const text = 'A navy fifth-wide square sits top-left; a teal quarter-tall triangle points down-left.';
  assert.equal(checkDescription(text, 90, false).words, 12);
  const split = checkDescription(text, 90, true);
  assert.equal(split.words, 16);
  assert.ok(!split.delivered.includes('-'), 'the drawer receives the same words the limit counted');
  const cut = checkDescription(text, 10, true);
  assert.equal(cut.truncated, true);
  const prompt = describePrompt({ shapes: [] } as never, 90, { hyphenSplit: true, rotation: true });
  assert.match(prompt, /splitting on spaces AND hyphens/);
  assert.match(prompt, /tall isosceles triangle/);
});
