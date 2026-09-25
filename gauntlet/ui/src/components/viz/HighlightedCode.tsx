/** JavaScript with light syntax colouring (tiny tokenizer, no dependency) and line numbers. */
import { useMemo } from 'react';
import { tokenizeJs } from '../../../../src/presenter/visuals/code.ts';

export function HighlightedCode({ code, maxLines }: { code: string; maxLines?: number }) {
  const { lines, cut } = useMemo(() => {
    // Tokenise the whole file (so block comments and template strings span lines), then split into lines.
    const all: Array<ReturnType<typeof tokenizeJs>> = [[]];
    for (const t of tokenizeJs(code.replace(/\t/g, '  '))) {
      const parts = t.v.split('\n');
      parts.forEach((v, i) => {
        if (i > 0) all.push([]);
        if (v) all[all.length - 1]!.push({ k: t.k, v });
      });
    }
    const keep = maxLines && all.length > maxLines ? all.slice(0, maxLines) : all;
    return { lines: keep, cut: all.length - keep.length };
  }, [code, maxLines]);
  return (
    <pre className="vz-code" aria-label="Model's code">
      {lines.map((toks, i) => (
        <span key={i} className="vz-code-line">
          <i className="tnum" aria-hidden="true">
            {i + 1}
          </i>
          <code>
            {toks.map((t, k) =>
              t.k === 'ws' || t.k === 'id' || t.k === 'pun' ? (
                <span key={k} className={t.k === 'pun' ? 'j-pun' : undefined}>
                  {t.v}
                </span>
              ) : (
                <span key={k} className={`j-${t.k}`}>
                  {t.v}
                </span>
              ),
            )}
            {'\n'}
          </code>
        </span>
      ))}
      {cut > 0 && <span className="vz-code-more">… {cut} more lines</span>}
    </pre>
  );
}
