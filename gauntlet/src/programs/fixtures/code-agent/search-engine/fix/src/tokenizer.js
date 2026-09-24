'use strict';

const { normalize } = require('./normalize');

/** Letters and digits, optionally joined by apostrophes ("don't", "dog's", "rock'n'roll"). */
const WORD_RE = /[A-Za-z0-9]+(?:'[A-Za-z0-9]+)*/g;

/**
 * Split text into words. Each token: { raw, word, position, start, end }
 * where `word` is the normalised form and [start, end) are character offsets
 * into `text`.
 */
function tokenize(text) {
  const tokens = [];
  const re = new RegExp(WORD_RE.source, 'g');
  let position = 0;
  let m;
  while ((m = re.exec(text)) !== null) {
    const raw = m[0];
    const word = normalize(raw);
    tokens.push({ raw, word, position: position++, start: m.index, end: m.index + raw.length });
  }
  return tokens;
}

module.exports = { tokenize, WORD_RE };
