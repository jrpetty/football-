import { readFileSync, statSync } from 'node:fs';
import type { Plugin } from 'vite';

/**
 * Hoist repeated string values out of large JSON modules.
 *
 * Vite turns `import x from './cpus.json'` into a JavaScript object literal, so
 * every repeated string in that JSON is written out again, in full, in the
 * bundle. This catalogue records per-field provenance — an honest and
 * load-bearing feature — as one array per field on every record:
 *
 *     "_prov": { "cores": ["model-knowledge"], "threads": ["model-knowledge"], ... }
 *
 * Across 442 processors and 279 graphics cards that is 19,476 copies of the
 * same fifteen-character string: 323KB, a fifth of the entire bundle, spent
 * writing one word over and over.
 *
 * Emitting `const _s0 = "model-knowledge"` once and referencing it is
 * byte-for-byte the same data structure at runtime and around 290KB smaller on
 * disk. That matters most to the standalone single-file build, which is opened
 * straight from disk with no compression in front of it.
 *
 * **Strings only, never arrays or objects.** Sharing one array instance across
 * 19,476 fields would save a further 39KB and introduce aliasing: anything that
 * ever pushed to one record's provenance would silently edit every record's.
 * Strings are immutable, so interning them cannot change behaviour at all. The
 * extra 39KB does not justify a class of bug that would stay invisible until it
 * corrupted the one thing this project is most careful about.
 */
function intern(code: string, minRepeats: number): { code: string; map: null } | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(code);
  } catch {
    return null; // Not our problem — let Vite report it.
  }

  // Pass 1: count every string VALUE. Keys are left alone; interning them would
  // force computed-property syntax and a slower object literal.
  const counts = new Map<string, number>();
  const count = (v: unknown): void => {
    if (typeof v === 'string') counts.set(v, (counts.get(v) ?? 0) + 1);
    else if (Array.isArray(v)) v.forEach(count);
    else if (v && typeof v === 'object') Object.values(v).forEach(count);
  };
  count(parsed);

  // Worth hoisting only when the identifier is shorter than the literal and it
  // repeats often enough to pay for its own declaration.
  const table = new Map<string, string>();
  let n = 0;
  for (const [s, times] of counts) {
    if (times < minRepeats) continue;
    const name = `_s${n}`;
    const literal = JSON.stringify(s).length;
    if (literal <= name.length) continue;
    if ((literal - name.length) * times - (name.length + literal + 1) <= 0) continue;
    table.set(s, name);
    n++;
  }
  if (!table.size) return null;

  // Pass 2: serialise, substituting identifiers for the hoisted strings.
  const emit = (v: unknown): string => {
    if (typeof v === 'string') return table.get(v) ?? JSON.stringify(v);
    if (v === null || typeof v !== 'object') return JSON.stringify(v) ?? 'null';
    if (Array.isArray(v)) return `[${v.map(emit).join(',')}]`;
    return `{${Object.entries(v).map(([k, val]) => `${JSON.stringify(k)}:${emit(val)}`).join(',')}}`;
  };

  const decls = [...table].map(([s, name]) => `${name}=${JSON.stringify(s)}`).join(',');
  return { code: `const ${decls};\nexport default ${emit(parsed)};\n`, map: null };
}

export function internJson(opts: { minBytes?: number; minRepeats?: number } = {}): Plugin {
  const minBytes = opts.minBytes ?? 64 * 1024;
  const minRepeats = opts.minRepeats ?? 24;

  return {
    name: 'rigcheck:intern-json',
    enforce: 'pre',

    /**
     * Claim big JSON imports as a virtual module.
     *
     * Transforming the .json id in place does not work: vite:json still runs
     * afterwards and tries to JSON.parse the JavaScript this produced. A
     * \0-prefixed virtual id is the rollup convention for "no other plugin
     * touches this", so vite:json never sees the file at all.
     */
    async resolveId(source, importer) {
      if (!source.endsWith('.json')) return null;
      const r = await this.resolve(source, importer, { skipSelf: true });
      if (!r || r.external) return null;
      try {
        if (statSync(r.id).size < minBytes) return null;
      } catch {
        return null;
      }
      // The suffix matters: vite:json matches ids ending `.json` (or `.json?`),
      // so a virtual id that still ended in .json would be claimed by it and it
      // would try to JSON.parse the JavaScript emitted here. A `#` fragment is
      // neither, so the id is unmistakably ours.
      return `\0intern:${r.id}#intern`;
    },

    load(id) {
      if (!id.startsWith('\0intern:')) return null;
      const file = id.slice('\0intern:'.length, -'#intern'.length);
      this.addWatchFile(file);
      return intern(readFileSync(file, 'utf8'), minRepeats);
    },
  };
}
