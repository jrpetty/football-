/**
 * Syntax colouring for one line of code (see codeTokens.ts). Good enough for
 * diffs on video; anything the tokenizer does not know stays plain text.
 */
import type { ReactNode } from 'react';
import { tokenizeLine } from './codeTokens.ts';
import './viz.css';

export { langOf } from './codeTokens.ts';

/** Coloured spans for one line. */
export function highlightLine(text: string, lang = 'js'): ReactNode[] {
  return tokenizeLine(text, lang).map((tok, i) =>
    tok.c ? (
      <span key={i} className={`sx-${tok.c}`}>
        {tok.t}
      </span>
    ) : (
      tok.t
    ),
  );
}
