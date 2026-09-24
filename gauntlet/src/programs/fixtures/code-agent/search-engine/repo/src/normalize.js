'use strict';

/** "Don't" → "dont", "Dog's" → "dogs" */
function normalize(word) {
  return word.toLowerCase().replace(/'/g, '');
}

module.exports = { normalize };
