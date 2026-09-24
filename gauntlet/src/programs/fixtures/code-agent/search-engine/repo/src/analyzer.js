'use strict';

const { tokenize } = require('./tokenizer');
const { isStopWord } = require('./stopwords');
const { stem } = require('./stemmer');

/**
 * Analyse text into tokens: { term, raw, position, start, end, stop }.
 * `term` is the stemmed word used for indexing and matching.
 */
function analyze(text) {
  return tokenize(String(text ?? '')).map((t) => {
    const stop = isStopWord(t.word);
    return { term: stop ? t.word : stem(t.word), raw: t.raw, position: t.position, start: t.start, end: t.end, stop };
  });
}

/** Only the tokens that get indexed. */
function indexTerms(text) {
  return analyze(text).filter((t) => !t.stop);
}

module.exports = { analyze, indexTerms };
