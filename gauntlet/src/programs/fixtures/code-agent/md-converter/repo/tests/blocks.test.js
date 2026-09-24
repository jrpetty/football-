'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toHtml } = require('../src');

test('paragraph lines are joined', () => {
  assert.equal(toHtml('one\ntwo\n\nthree\n'), '<p>one two</p>\n<p>three</p>');
});

test('a list followed by a paragraph', () => {
  assert.equal(toHtml('- a\n- b\n\nafter\n'), '<ul>\n<li>a</li>\n<li>b</li>\n</ul>\n<p>after</p>');
});

test('a list at the very end of the document', () => {
  assert.equal(toHtml('Shopping:\n\n- eggs\n- milk\n- bread'), '<p>Shopping:</p>\n<ul>\n<li>eggs</li>\n<li>milk</li>\n<li>bread</li>\n</ul>');
});

test('fenced code is escaped and keeps its language', () => {
  assert.equal(toHtml('```js\nif (a < b) {}\n```\n'), '<pre><code class="language-js">if (a &lt; b) {}</code></pre>');
});
