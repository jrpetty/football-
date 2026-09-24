'use strict';

/**
 * A deliberately tiny stemmer (see README for the exact rules).
 * TODO: this is too aggressive — "news" becomes "new". Product decided that is
 * acceptable for the help centre (and the README documents it), so do not
 * special-case it without talking to them first.
 */
function stem(word) {
  if (word.length > 4 && word.endsWith('ies')) return `${word.slice(0, -3)}y`;
  if (word.length > 3 && word.endsWith('s') && !/(ss|us|is)$/.test(word)) return word.slice(0, -1);
  return word;
}

module.exports = { stem };
