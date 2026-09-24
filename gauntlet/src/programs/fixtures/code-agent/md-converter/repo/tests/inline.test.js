'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { renderInline } = require('../src');

test('escapes HTML', () => {
  assert.equal(renderInline('a < b & "c"'), 'a &lt; b &amp; &quot;c&quot;');
});

test('strong and emphasis', () => {
  assert.equal(renderInline('**bold** and *soft*'), '<strong>bold</strong> and <em>soft</em>');
});

test('two strong spans in one line', () => {
  assert.equal(renderInline('a **big** and **bold** claim'), 'a <strong>big</strong> and <strong>bold</strong> claim');
});

test('code spans are literal', () => {
  assert.equal(renderInline('run `a*b*c < d` now'), 'run <code>a*b*c &lt; d</code> now');
});

test('links', () => {
  assert.equal(renderInline('see [the docs](https://x.dev/?a=1&b=2)'), 'see <a href="https://x.dev/?a=1&amp;b=2">the docs</a>');
});
