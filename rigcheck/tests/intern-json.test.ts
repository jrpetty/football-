import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { internJson } from '../scripts/vite/intern-json.ts';

/**
 * The plugin rewrites the catalogue into JavaScript. If that rewrite ever
 * differed from the source by so much as one field, every estimate in the app
 * would silently be computed from wrong data — so the property under test is
 * not "it is smaller", it is "it is identical".
 */
function run(json: string, minRepeats = 3): { code: string; obj: unknown } | null {
  const plugin = internJson({ minBytes: 0, minRepeats });
  const load = plugin.load as (id: string) => { code: string } | null;
  // Drive the load hook directly with a temp file the plugin will read.
  const tmp = `/tmp/intern-${Math.abs(hash(json))}.json`;
  writeTmp(tmp, json);
  const ctx = { addWatchFile() {} };
  const out = load.call(ctx as never, `\0intern:${tmp}#intern`);
  if (!out) return null;
  // Evaluate the emitted module body to recover the object it describes.
  const body = out.code.replace(/^export default /m, 'return ').replace(/;\n$/, ';');
  // eslint-disable-next-line no-new-func
  const obj = new Function(`${body}`)();
  return { code: out.code, obj };
}

function hash(s: string) { let h = 0; for (const c of s) h = (Math.imul(h, 31) + c.charCodeAt(0)) | 0; return h; }
function writeTmp(p: string, s: string) { // eslint-disable-next-line @typescript-eslint/no-var-requires
  readFileSync; require('node:fs').writeFileSync(p, s); }

describe('JSON string interning', () => {
  it('round-trips to an identical object', () => {
    const src = {
      records: Array.from({ length: 40 }, (_, i) => ({
        id: `part-${i}`,
        arch: 'Zen 3',
        _prov: { id: ['model-knowledge'], arch: ['model-knowledge'], cores: ['model-knowledge'] },
        cores: i,
        nested: { deep: [{ x: 'model-knowledge' }, null, true, 1.5] },
      })),
    };
    const json = JSON.stringify(src);
    const out = run(json)!;
    expect(out.obj).toEqual(src);
  });

  it('actually hoists the repeated string', () => {
    const json = JSON.stringify({ a: Array.from({ length: 40 }, () => 'model-knowledge') });
    const out = run(json)!;
    expect(out.code).toMatch(/^const _s0="model-knowledge";/);
    // The literal must appear exactly once — in the declaration.
    expect(out.code.match(/"model-knowledge"/g)).toHaveLength(1);
    expect(out.obj).toEqual({ a: Array.from({ length: 40 }, () => 'model-knowledge') });
  });

  it('preserves null, false, 0 and empty string rather than dropping them', () => {
    // JSON.stringify(undefined) is undefined, and a naive emitter turns these
    // falsy values into holes. A missing `false` on a capability flag would read
    // as "unknown" instead of "not supported".
    const src = { r: Array.from({ length: 30 }, () => ({ a: null, b: false, c: 0, d: '', e: 'model-knowledge' })) };
    const out = run(JSON.stringify(src))!;
    expect(out.obj).toEqual(src);
  });

  it('leaves keys alone, only values', () => {
    // The key and the value are the same string here. Only the value may be
    // hoisted: interning a key would need computed-property syntax, which is a
    // slower object literal for no gain.
    const src = { r: Array.from({ length: 40 }, () => ({ 'model-knowledge': 'model-knowledge' })) };
    const out = run(JSON.stringify(src))!;
    expect(out.obj).toEqual(src);
    expect(out.code).not.toMatch(/\[_s\d+\]:/);
    expect(out.code).toMatch(/"model-knowledge":_s0/);
  });

  it('declines when nothing repeats enough to pay for itself', () => {
    expect(run(JSON.stringify({ a: 'x', b: 'y' }), 24)).toBeNull();
  });

  it('is lossless on the real catalogue, field for field', () => {
    for (const f of ['data/catalogue/cpus.json', 'data/catalogue/gpus.json', 'data/catalogue/games.json']) {
      const raw = readFileSync(f, 'utf8');
      const out = run(raw, 24)!;
      expect(out.obj, f).toEqual(JSON.parse(raw));
    }
  });

  it('measurably shrinks the real catalogue', () => {
    const raw = readFileSync('data/catalogue/cpus.json', 'utf8');
    const minified = JSON.stringify(JSON.parse(raw));
    const out = run(raw, 24)!;
    expect(out.code.length).toBeLessThan(minified.length * 0.75);
  });
});
