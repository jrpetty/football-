'use strict';

/** Consecutive list items of the same kind form one list. */
function collectList(tokens, start) {
  const ordered = tokens[start].ordered;
  const items = [tokens[start].text];
  let i = start + 1;
  while (i < tokens.length - 1 && tokens[i].type === 'item' && tokens[i].ordered === ordered) {
    items.push(tokens[i].text);
    i++;
  }
  return { block: { type: 'list', ordered, items }, next: i };
}

function collectWhile(tokens, start, type) {
  const lines = [];
  let i = start;
  while (i < tokens.length && tokens[i].type === type) {
    lines.push(tokens[i].text);
    i++;
  }
  return { lines, next: i };
}

/** Group line tokens into blocks. */
function parseBlocks(tokens) {
  const blocks = [];
  let i = 0;
  while (i < tokens.length) {
    const token = tokens[i];
    if (token.type === 'blank') {
      i++;
    } else if (token.type === 'heading') {
      blocks.push({ type: 'heading', level: token.level, text: token.text });
      i++;
    } else if (token.type === 'hr') {
      blocks.push({ type: 'hr' });
      i++;
    } else if (token.type === 'fence-start') {
      const { lines, next } = collectWhile(tokens, i + 1, 'code');
      blocks.push({ type: 'code', lang: token.lang, text: lines.join('\n') });
      i = next < tokens.length && tokens[next].type === 'fence-end' ? next + 1 : next;
    } else if (token.type === 'item') {
      const { block, next } = collectList(tokens, i);
      blocks.push(block);
      i = next;
    } else if (token.type === 'quote') {
      const { lines, next } = collectWhile(tokens, i, 'quote');
      blocks.push({ type: 'quote', text: lines.join(' ') });
      i = next;
    } else if (token.type === 'text') {
      const { lines, next } = collectWhile(tokens, i, 'text');
      blocks.push({ type: 'paragraph', text: lines.join(' ') });
      i = next;
    } else {
      // A stray fence-end or code line: treat as text.
      blocks.push({ type: 'paragraph', text: token.text ?? '' });
      i++;
    }
  }
  return blocks;
}

module.exports = { parseBlocks, collectList };
