'use strict';

const { lex } = require('./lexer');
const { parseBlocks } = require('./blocks');
const { renderBlocks } = require('./render');
const { renderInline } = require('./inline');
const { escapeHtml } = require('./escape');
const { slugify } = require('./slug');
const { toc } = require('./toc');

/** Convert a Markdown document to HTML. */
function toHtml(markdown) {
  return renderBlocks(parseBlocks(lex(markdown)));
}

module.exports = { toHtml, toc, renderInline, escapeHtml, slugify, lex, parseBlocks };
