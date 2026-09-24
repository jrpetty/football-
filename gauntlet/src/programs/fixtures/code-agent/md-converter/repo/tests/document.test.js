'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toHtml, toc } = require('../src');

const DOC = '# Introduction\n\nHello.\n';

test('headings get ids', () => {
  assert.equal(toHtml(DOC), '<h1 id="introduction">Introduction</h1>\n<p>Hello.</p>');
});

test('repeated headings in one document get numbered ids', () => {
  assert.equal(toHtml('## Setup\n## Setup\n'), '<h2 id="setup">Setup</h2>\n<h2 id="setup-1">Setup</h2>');
});

test('converting the same document twice gives the same HTML', () => {
  assert.equal(toHtml(DOC), toHtml(DOC));
});

test('table of contents', () => {
  assert.deepEqual(toc('# Introduction\n\n## Usage `npm`\n'), [
    { level: 1, text: 'Introduction', id: 'introduction' },
    { level: 2, text: 'Usage npm', id: 'usage-npm' },
  ]);
});
