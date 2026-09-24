'use strict';

const { escapeHtml } = require('./escape');
const { renderInline } = require('./inline');
const { uniqueSlug } = require('./slug');

function renderBlock(block) {
  switch (block.type) {
    case 'heading':
      return `<h${block.level} id="${uniqueSlug(block.text)}">${renderInline(block.text)}</h${block.level}>`;
    case 'paragraph':
      return `<p>${renderInline(block.text)}</p>`;
    case 'hr':
      return '<hr>';
    case 'code': {
      const cls = block.lang ? ` class="language-${escapeHtml(block.lang)}"` : '';
      return `<pre><code${cls}>${escapeHtml(block.text)}</code></pre>`;
    }
    case 'quote':
      return `<blockquote>\n<p>${renderInline(block.text)}</p>\n</blockquote>`;
    case 'list': {
      const tag = block.ordered ? 'ol' : 'ul';
      const items = block.items.map((item) => `<li>${renderInline(item)}</li>`);
      return [`<${tag}>`, ...items, `</${tag}>`].join('\n');
    }
    default:
      throw new Error(`Unknown block type ${block.type}`);
  }
}

function renderBlocks(blocks) {
  return blocks.map(renderBlock).join('\n');
}

module.exports = { renderBlock, renderBlocks };
