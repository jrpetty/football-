'use strict';

const { ARTICLES } = require('./lexicon');

/** "Take THE brass lamp!" → ['take', 'brass', 'lamp'] */
function tokenize(input) {
  return String(input)
    .toLowerCase()
    .replace(/[^a-z0-9'\-\s]+/g, ' ')
    .split(/\s+/)
    .filter((word) => word.length > 0)
    .filter((word) => !ARTICLES.includes(word));
}

module.exports = { tokenize };
