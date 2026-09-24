'use strict';

const { escapeHtml } = require('./escape');

const CODE_MARK = '\u0000';

/** Render inline Markdown (code spans, links, strong, emphasis) to HTML. */
function renderInline(text) {
  // 1. Pull code spans out first so nothing inside them is treated as Markdown.
  const codes = [];
  let out = String(text).replace(/`([^`]+)`/g, (_, code) => {
    codes.push(code);
    return `${CODE_MARK}${codes.length - 1}${CODE_MARK}`;
  });

  // 2. Escape everything else.
  out = escapeHtml(out);

  // 3. Links, then strong, then emphasis.
  out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, url) => `<a href="${url}">${label}</a>`);
  out = out.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  out = out.replace(/\*(.+?)\*/g, '<em>$1</em>');

  // 4. Put the code spans back.
  return out.replace(new RegExp(`${CODE_MARK}(\\d+)${CODE_MARK}`, 'g'), (_, n) => `<code>${escapeHtml(codes[Number(n)])}</code>`);
}

/** Inline Markdown reduced to plain text (used for heading ids). */
function plainText(text) {
  return String(text)
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[*`]/g, '');
}

module.exports = { renderInline, plainText };
