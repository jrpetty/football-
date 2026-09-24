'use strict';

const { analyze } = require('./analyzer');

/**
 * Parse a query string.
 *   words        → terms
 *   "a phrase"   → phrases (list of { term, position } relative to the phrase, stop words dropped)
 *   -word        → excluded terms
 */
function parseQuery(query) {
  const terms = [];
  const phrases = [];
  const excluded = [];
  const re = /(-?)"([^"]*)"|(-?)(\S+)/g;
  let m;
  while ((m = re.exec(String(query))) !== null) {
    if (m[2] !== undefined) {
      const tokens = analyze(m[2]).filter((t) => !t.stop);
      if (!tokens.length) continue;
      const first = tokens[0].position;
      const phrase = tokens.map((t) => ({ term: t.term, offset: t.position - first }));
      if (m[1]) excluded.push(...phrase.map((p) => p.term));
      else phrases.push(phrase);
      continue;
    }
    const negative = m[3] === '-';
    for (const t of analyze(m[4])) {
      if (t.stop) continue;
      (negative ? excluded : terms).push(t.term);
    }
  }
  return { terms, phrases, excluded };
}

// TODO: support OR and parentheses? Product says no: the help-centre search box
// is plain words, "phrases" and -exclusions, nothing more.

/** Every distinct term that contributes to the score (plain words and phrase words). */
function scoringTerms(parsed) {
  return [...new Set([...parsed.terms, ...parsed.phrases.flatMap((p) => p.map((x) => x.term))])];
}

module.exports = { parseQuery, scoringTerms };
