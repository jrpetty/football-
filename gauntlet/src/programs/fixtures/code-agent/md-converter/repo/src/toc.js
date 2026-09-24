'use strict';

const { lex } = require('./lexer');
const { parseBlocks } = require('./blocks');
const { plainText } = require('./inline');
const { uniqueSlug } = require('./slug');

/** Table of contents: [{ level, text, id }] for every heading. */
function toc(markdown) {
  return parseBlocks(lex(markdown))
    .filter((block) => block.type === 'heading')
    .map((block) => ({ level: block.level, text: plainText(block.text), id: uniqueSlug(block.text) }));
}

module.exports = { toc };
