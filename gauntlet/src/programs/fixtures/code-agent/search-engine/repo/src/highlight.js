'use strict';

const { analyze } = require('./analyzer');
const { escapeHtml } = require('./escape');

/**
 * HTML-escape `text` and wrap every word whose term is in `terms` in <mark>.
 * Works on the analyzer's character offsets so the original spelling
 * (capitals, apostrophes) is preserved inside the mark.
 */
function highlightText(text, terms) {
  const wanted = new Set(terms);
  let out = '';
  let cursor = 0;
  for (const t of analyze(text)) {
    if (t.stop || !wanted.has(t.term)) continue;
    out += escapeHtml(text.slice(cursor, t.start));
    out += `<mark>${escapeHtml(text.slice(t.start, t.end))}</mark>`;
    cursor = t.end;
  }
  return out + escapeHtml(text.slice(cursor));
}

/** The first `maxWords` words of a highlighted text, for result lists. TODO: centre the snippet on the first match. */
function snippet(text, terms, maxWords = 30) {
  const tokens = analyze(text);
  const cut = tokens.length > maxWords ? tokens[maxWords - 1].end : text.length;
  const html = highlightText(text.slice(0, cut), terms);
  return tokens.length > maxWords ? `${html}…` : html;
}

module.exports = { highlightText, snippet };
