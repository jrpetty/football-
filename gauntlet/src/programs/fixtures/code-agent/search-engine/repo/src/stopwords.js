'use strict';

/** Words too common to index. They still count for positions (see README). */
const STOP_WORDS = new Set(['a', 'an', 'and', 'are', 'as', 'at', 'be', 'by', 'for', 'in', 'is', 'it', 'of', 'on', 'or', 'the', 'to', 'with']);

function isStopWord(word) {
  return STOP_WORDS.has(word);
}

module.exports = { STOP_WORDS, isStopWord };
