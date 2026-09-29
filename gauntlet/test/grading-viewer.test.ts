/** Universal output viewer: type detection, sanitisers (SVG, Markdown, HTML srcdoc), CSV and ZIP parsing. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { detectKind, dataUrlBytes, extensionOf, hexRows, looksLikeText, sniffBytes, sniffText, codeLangOf } from '../ui/src/components/viewer/detect.ts';
import { parseDelimited, renderMarkdown, sandboxedSrcdoc, sanitizeSvg, svgDataUrl } from '../ui/src/components/viewer/sanitize.ts';
import { buildStoredZip, crc32, listZip } from '../ui/src/components/viewer/zip.ts';

const bytes = (...xs: Array<number | string>) => new Uint8Array(xs.flatMap((x) => (typeof x === 'string' ? [...x].map((c) => c.charCodeAt(0)) : [x])));

test('magic bytes identify every binary format the viewer supports', () => {
  const cases: Array<[Uint8Array, string, string]> = [
    [bytes(0x89, 'PNG', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0), 'image', 'image/png'],
    [bytes(0xff, 0xd8, 0xff, 0xe0), 'image', 'image/jpeg'],
    [bytes('GIF89a', 1, 0), 'image', 'image/gif'],
    [bytes('RIFF', 0, 0, 0, 0, 'WEBPVP8 '), 'image', 'image/webp'],
    [bytes(0, 0, 0, 0x1c, 'ftypavif', 0, 0), 'image', 'image/avif'],
    [bytes('BM', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 'image', 'image/bmp'],
    [bytes(0, 0, 1, 0, 1, 0, 16, 16), 'image', 'image/x-icon'],
    [bytes('%PDF-1.4\n'), 'pdf', 'application/pdf'],
    [bytes('RIFF', 0, 0, 0, 0, 'WAVEfmt '), 'audio', 'audio/wav'],
    [bytes('ID3', 4, 0), 'audio', 'audio/mpeg'],
    [bytes('OggS', 0), 'audio', 'audio/ogg'],
    [bytes(0x1a, 0x45, 0xdf, 0xa3, 0), 'video', 'video/webm'],
    [bytes(0, 0, 0, 0x18, 'ftypmp42', 0), 'video', 'video/mp4'],
    [bytes('PK', 3, 4, 0), 'zip', 'application/zip'],
    [bytes('<?xml version="1.0"?>\n<svg xmlns="x">'), 'svg', 'image/svg+xml'],
    [bytes('<!DOCTYPE html><html>'), 'html', 'text/html'],
  ];
  for (const [b, kind, mime] of cases) assert.deepEqual(sniffBytes(b), { kind, mime }, mime);
  assert.equal(sniffBytes(bytes('hello world')), null);
});

test('detectKind: magic beats a wrong name; then MIME, extension, content; hints for replays', () => {
  assert.equal(detectKind({ name: 'fake.txt', bytes: bytes(0x89, 'PNG', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0) }).kind, 'image');
  assert.equal(detectKind({ name: 'x', mime: 'application/pdf' }).kind, 'pdf');
  const ext: Array<[string, string]> = [
    ['a.html', 'html'], ['a.svg', 'svg'], ['a.png', 'image'], ['a.jpg', 'image'], ['a.gif', 'image'], ['a.webp', 'image'], ['a.avif', 'image'], ['a.bmp', 'image'], ['a.ico', 'image'],
    ['a.pdf', 'pdf'], ['a.wav', 'audio'], ['a.mp3', 'audio'], ['a.ogg', 'audio'], ['a.mp4', 'video'], ['a.webm', 'video'],
    ['a.json', 'json'], ['a.csv', 'csv'], ['a.tsv', 'csv'], ['a.md', 'markdown'], ['a.js', 'code'], ['a.py', 'code'], ['a.ts', 'code'], ['a.diff', 'diff'], ['a.patch', 'diff'],
    ['a.txt', 'text'], ['a.log', 'text'], ['a.zip', 'zip'],
  ];
  for (const [name, kind] of ext) assert.equal(detectKind({ name }).kind, kind, name);
  assert.equal(detectKind({ name: 'reply', text: '{"a": 1}' }).kind, 'json');
  assert.equal(detectKind({ name: 'reply', text: '<svg viewBox="0 0 1 1"></svg>' }).kind, 'svg');
  assert.equal(detectKind({ name: 'reply', text: 'diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b' }).kind, 'diff');
  assert.equal(detectKind({ name: 'reply', text: 'Just words.' }).kind, 'text');
  assert.equal(detectKind({ name: 'blob.bin', bytes: bytes(0, 1, 2, 3, 0, 5) }).kind, 'binary');
  assert.equal(detectKind({ name: 'r', hint: 'replay' }).kind, 'replay');
  assert.equal(extensionOf('https://x/y/file.PNG?x=1'), 'png');
  assert.equal(codeLangOf('x.py'), 'py');
  assert.equal(codeLangOf('block', 'javascript'), 'js');
  assert.equal(sniffText('[1,2'), null);
  assert.ok(looksLikeText(bytes('plain\ttext\n')));
  assert.ok(!looksLikeText(bytes('a', 0, 'b')));
  assert.equal(hexRows(bytes('ABCDEFGHIJKLMNOPQ'))[1]!.ascii, 'Q');
  assert.equal(new TextDecoder().decode(dataUrlBytes('data:text/plain;base64,aGk=')!), 'hi');
  assert.equal(new TextDecoder().decode(dataUrlBytes('data:text/plain,a%20b')!), 'a b');
});

test('sanitizeSvg strips scripts, handlers, external refs and entity tricks', () => {
  const evil = `<?xml-stylesheet href="http://x/s.css"?><!DOCTYPE svg [<!ENTITY a "aaaa">]>
<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)">
  <script>alert(2)</script><script href="http://evil/x.js"/>
  <foreignObject><iframe src="http://evil"></iframe></foreignObject>
  <image href="https://evil/pixel.png" width="10"/>
  <image href="data:image/png;base64,iVBORw0KGgo=" width="10"/>
  <a xlink:href="javascript:alert(3)"><circle r="4" onclick='alert(4)' fill="url(#g)"/></a>
  <rect style="fill:url(http://evil/p.svg#x)" />
  <style>@import url(http://evil/c.css); .a{fill:red}</style>
  <use href="#local"/>
  <set attributeName="href" to="javascript:alert(5)"/>
</svg>`;
  const { svg, removed } = sanitizeSvg(evil);
  for (const bad of ['<script', 'onload', 'onclick', 'foreignObject', 'iframe', 'evil', 'javascript:', '@import', 'ENTITY', 'xml-stylesheet', '<set']) assert.ok(!svg.includes(bad), `${bad} removed`);
  assert.ok(svg.includes('href="#local"'), 'internal references survive');
  assert.ok(svg.includes('data:image/png;base64'), 'embedded images survive');
  assert.ok(svg.includes('fill="url(#g)"'), 'internal gradients survive');
  assert.ok(removed.length >= 6);
  assert.match(svgDataUrl('<svg></svg>'), /^data:image\/svg\+xml;charset=utf-8,%3Csvg%20xmlns/);
});

test('renderMarkdown only emits safe tags', () => {
  const html = renderMarkdown(
    '# Title\n\nHello **bold** and *it* and `code<b>`.\n\n<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[ok](https://example.com) [bad](javascript:alert(1)) ![remote](https://evil/x.png) ![inline](data:image/png;base64,iVBORw0KGgo=)\n\n- one\n- [x] done\n\n1. first\n\n> quote\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```js\nconst x = "<y>";\n```',
  );
  assert.ok(html.includes('<h1>Title</h1>'));
  assert.ok(html.includes('<strong>bold</strong>') && html.includes('<em>it</em>'));
  assert.ok(html.includes('<code>code&lt;b&gt;</code>'));
  assert.ok(!/<script|<img src=x|onerror=alert/i.test(html.replace(/&lt;[^&]*&gt;/g, '')), 'raw HTML is shown as text');
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(html.includes('href="https://example.com"') && html.includes('rel="noopener noreferrer nofollow"'));
  assert.ok(!html.includes('javascript:'));
  assert.ok(!html.includes('https://evil'), 'remote images are never fetched');
  assert.ok(html.includes('<img alt="inline" src="data:image/png;base64,iVBORw0KGgo=">'));
  assert.ok(html.includes('<ul>') && html.includes('md-task') && html.includes('<ol>') && html.includes('<blockquote>') && html.includes('<table>'));
  assert.ok(html.includes('const x = &quot;&lt;y&gt;&quot;;'));
});

test('sandboxedSrcdoc puts a no-network CSP first, after the doctype', () => {
  const doc = sandboxedSrcdoc('<!DOCTYPE html><html><head><script>fetch("http://x")</script></head></html>');
  assert.match(doc, /^<!DOCTYPE html><meta http-equiv="Content-Security-Policy" content="default-src 'none'.*connect-src 'none'/);
  assert.match(sandboxedSrcdoc('<p>hi</p>'), /^<!doctype html><meta http-equiv/);
});

test('CSV/TSV parsing handles quotes, delimiters and row limits', () => {
  const { rows, delimiter } = parseDelimited('name,note\n"Smith, J","said ""hi"""\nLee,"two\nlines"\n');
  assert.equal(delimiter, ',');
  assert.deepEqual(rows, [['name', 'note'], ['Smith, J', 'said "hi"'], ['Lee', 'two\nlines']]);
  assert.equal(parseDelimited('a\tb\n1\t2').delimiter, '\t');
  const big = parseDelimited(Array.from({ length: 50 }, (_, i) => `${i},x`).join('\n'), undefined, 10);
  assert.equal(big.rows.length, 10);
  assert.equal(big.truncated, true);
});

test('ZIP listing reads the central directory without decompressing', () => {
  const zip = buildStoredZip([
    { name: 'game/index.html', content: '<!doctype html><p>hi</p>' },
    { name: 'game/', content: '' },
    { name: 'README.md', content: '# Hello' },
  ]);
  assert.deepEqual([...zip.slice(0, 4)], [0x50, 0x4b, 0x03, 0x04]);
  const l = listZip(zip);
  assert.equal(l.error, undefined);
  assert.equal(l.total, 3);
  assert.deepEqual(l.entries.map((e) => [e.name, e.size, e.method, e.dir]), [['game/index.html', 24, 'stored', false], ['game/', 0, 'stored', true], ['README.md', 7, 'stored', false]]);
  assert.equal(l.entries[0]!.modified, '2026-09-28 12:00');
  assert.match(listZip(bytes('not a zip at all')).error!, /Not a readable ZIP/);
  assert.equal(crc32(new TextEncoder().encode('123456789')), 0xcbf43926);
});
