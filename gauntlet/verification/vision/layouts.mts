// Writes ui/src/components/viz/vision-layouts.json: where the grids, counted objects and chart parts sit in every
// vision image, so the result inspector and the Presenter can draw the model's answer and the answer key on top of
// the picture. Display data only: nothing here is sent to a model or used for scoring.
//
//   node verification/vision/layouts.mts
//
// It re-runs the same seeded generators as build.mts (no PNG rendering) and refuses to write anything if a
// regenerated prompt or answer key differs from the committed test files, so the geometry always matches the images.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildCharts, type VisionCase } from './charts.mts';
import { buildSpots } from './spot.mts';
import { buildHandwriting } from './handwriting.mts';
import { buildCounts } from './count.mts';

const here = dirname(fileURLToPath(import.meta.url));
const TESTS = join(here, '..', '..', 'tests', 'vision');
const OUT = join(here, '..', '..', 'ui', 'src', 'components', 'viz', 'vision-layouts.json');

const sets: Array<[string, VisionCase[]]> = [
  ['read-the-chart', buildCharts()],
  ['spot-the-difference', buildSpots()],
  ['handwritten-maths', buildHandwriting()],
  ['count-and-locate', buildCounts()],
];

const out: Record<string, unknown> = {};
for (const [slug, cases] of sets) {
  const def = JSON.parse(readFileSync(join(TESTS, `${slug}.json`), 'utf8')) as { cases: Array<{ id: string; prompt: string; expected: unknown }> };
  for (const c of cases) {
    const committed = def.cases.find((x) => x.id === c.id);
    if (!committed || committed.prompt !== c.prompt || JSON.stringify(committed.expected) !== JSON.stringify(c.expected)) {
      throw new Error(`${slug}/${c.id}: the generator no longer matches the committed test file; not writing layouts`);
    }
    if (!c.marks) continue;
    out[`vision/images/${c.image.file}`] = { width: c.image.width, height: c.image.height, ...c.marks };
  }
}
writeFileSync(OUT, JSON.stringify(out, null, 1) + '\n');
console.log(`Wrote ${Object.keys(out).length} layouts to ${OUT}`);
