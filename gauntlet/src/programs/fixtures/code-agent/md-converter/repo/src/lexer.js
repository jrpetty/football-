'use strict';

/**
 * Classify every line of a document.
 * Token types: blank, heading, hr, item, quote, fence-start, code, fence-end, text.
 */
function lex(markdown) {
  const lines = String(markdown).replace(/\r\n?/g, '\n').split('\n');
  const tokens = [];
  let inFence = false;
  for (const line of lines) {
    let m;
    if (inFence) {
      if (/^```\s*$/.test(line)) {
        tokens.push({ type: 'fence-end' });
        inFence = false;
      } else {
        tokens.push({ type: 'code', text: line });
      }
      continue;
    }
    if ((m = /^```\s*([\w-]*)\s*$/.exec(line))) {
      tokens.push({ type: 'fence-start', lang: m[1] });
      inFence = true;
    } else if (/^\s*$/.test(line)) {
      tokens.push({ type: 'blank' });
    } else if ((m = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line))) {
      tokens.push({ type: 'heading', level: m[1].length, text: m[2] });
    } else if (/^\s*(\*\s*){3,}$|^\s*(-\s*){3,}$/.test(line)) {
      tokens.push({ type: 'hr' });
    } else if ((m = /^\s*[-*+]\s+(.*)$/.exec(line))) {
      tokens.push({ type: 'item', ordered: false, text: m[1].trim() });
    } else if ((m = /^\s*\d+[.)]\s+(.*)$/.exec(line))) {
      tokens.push({ type: 'item', ordered: true, text: m[1].trim() });
    } else if ((m = /^\s*>\s?(.*)$/.exec(line))) {
      tokens.push({ type: 'quote', text: m[1].trim() });
    } else {
      tokens.push({ type: 'text', text: line.trim() });
    }
  }
  return tokens;
}

module.exports = { lex };
