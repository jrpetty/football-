'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toHtml, toc, renderInline, slugify } = require('../src');

test('several emphasis spans on one line', () => {
  assert.equal(renderInline('*a* and *b*'), '<em>a</em> and <em>b</em>');
  assert.equal(renderInline('**x** **y** **z**'), '<strong>x</strong> <strong>y</strong> <strong>z</strong>');
  assert.equal(renderInline('**one** then *two* then **three**'), '<strong>one</strong> then <em>two</em> then <strong>three</strong>');
});

test('nested emphasis', () => {
  assert.equal(renderInline('**bold *and soft* text**'), '<strong>bold <em>and soft</em> text</strong>');
  assert.equal(renderInline('*soft **and bold** text*'), '<em>soft <strong>and bold</strong> text</em>');
});

test('emphasis inside link labels and next to code', () => {
  assert.equal(renderInline('[**a**](u) and [*b*](v)'), '<a href="u"><strong>a</strong></a> and <a href="v"><em>b</em></a>');
  assert.equal(renderInline('*x* `*y*` *z*'), '<em>x</em> <code>*y*</code> <em>z</em>');
});

test('lists at the end of the document', () => {
  assert.equal(toHtml('1. one\n2. two'), '<ol>\n<li>one</li>\n<li>two</li>\n</ol>');
  assert.equal(toHtml('- solo'), '<ul>\n<li>solo</li>\n</ul>');
  assert.equal(toHtml('- a\n- b\n- c\n- d'), '<ul>\n<li>a</li>\n<li>b</li>\n<li>c</li>\n<li>d</li>\n</ul>');
});

test('a list that switches kind at the end', () => {
  assert.equal(toHtml('- a\n- b\n1. c'), '<ul>\n<li>a</li>\n<li>b</li>\n</ul>\n<ol>\n<li>c</li>\n</ol>');
});

test('a list with emphasis at the end of the document', () => {
  assert.equal(toHtml('Notes:\n* **x** and **y**\n* *z*'), '<p>Notes:</p>\n<ul>\n<li><strong>x</strong> and <strong>y</strong></li>\n<li><em>z</em></li>\n</ul>');
});

test('ids are unique within a document', () => {
  assert.equal(
    toHtml('# FAQ\n## Setup\n## Setup\n### Setup'),
    '<h1 id="faq">FAQ</h1>\n<h2 id="setup">Setup</h2>\n<h2 id="setup-1">Setup</h2>\n<h3 id="setup-2">Setup</h3>',
  );
});

test('every conversion starts fresh', () => {
  const md = '# Overview\n## Details\n## Details';
  const first = toHtml(md);
  assert.equal(toHtml(md), first);
  assert.equal(toHtml(md), '<h1 id="overview">Overview</h1>\n<h2 id="details">Details</h2>\n<h2 id="details-1">Details</h2>');
});

test('toc matches toHtml and is independent of earlier calls', () => {
  const md = '# Overview\n## Details\n## Details';
  toHtml(md);
  toc(md);
  assert.deepEqual(toc(md), [
    { level: 1, text: 'Overview', id: 'overview' },
    { level: 2, text: 'Details', id: 'details' },
    { level: 2, text: 'Details', id: 'details-1' },
  ]);
});

test('slugify', () => {
  assert.equal(slugify('Using `npm` **fast**!'), 'using-npm-fast');
  assert.equal(slugify('  C++ & Rust  '), 'c-rust');
  assert.equal(slugify('!!!'), 'section');
});

test('a full document', () => {
  const md = [
    '# Guide',
    '',
    'Read **this** and *that*, then **the rest**.',
    '',
    '> Quoted *text*',
    '> continues',
    '',
    '---',
    '',
    '## Steps',
    '1. install',
    '2. run `x < y`',
  ].join('\n');
  assert.equal(
    toHtml(md),
    [
      '<h1 id="guide">Guide</h1>',
      '<p>Read <strong>this</strong> and <em>that</em>, then <strong>the rest</strong>.</p>',
      '<blockquote>\n<p>Quoted <em>text</em> continues</p>\n</blockquote>',
      '<hr>',
      '<h2 id="steps">Steps</h2>',
      '<ol>\n<li>install</li>\n<li>run <code>x &lt; y</code></li>\n</ol>',
    ].join('\n'),
  );
});

test('escaping still works everywhere', () => {
  assert.equal(toHtml('# A & B\n\n<script>'), '<h1 id="a-b">A &amp; B</h1>\n<p>&lt;script&gt;</p>');
});
