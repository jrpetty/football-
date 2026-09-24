# md-converter

A small Markdown → HTML converter used by our docs site. It supports a
deliberately limited subset of Markdown.

```js
const { toHtml, toc } = require('./src');

toHtml('# Hello\n\nSome **bold** text.');
// '<h1 id="hello">Hello</h1>\n<p>Some <strong>bold</strong> text.</p>'
```

## Supported syntax

| Markdown | HTML |
|---|---|
| `# Title` … `###### Title` | `<h1 id="title">Title</h1>` … |
| blank-line separated text | `<p>…</p>` (lines of one paragraph are joined with a space) |
| `- item`, `* item`, `+ item` | `<ul>` with one `<li>` per line |
| `1. item` | `<ol>` |
| `> quote` | `<blockquote>` (consecutive lines are joined) |
| ```` ``` ```` fenced code (optional language) | `<pre><code class="language-js">…</code></pre>` |
| `---` or `***` on its own line | `<hr>` |
| `**strong**`, `*emphasis*` | `<strong>`, `<em>` (each pair of markers is one span; they may nest) |
| `` `code` `` | `<code>` — contents are literal, no Markdown inside |
| `[label](url)` | `<a href="url">label</a>` |

* All text is HTML-escaped (`&`, `<`, `>`, `"`, `'`).
* Blocks are joined with a single newline `\n`; list items each go on their own line.
* A document does not need to end with a newline.

## Heading ids

Every heading gets an `id` made from its text (`slugify`): lowercase, Markdown
markers removed, runs of other characters replaced by `-`. Ids are **unique
within one document**: the second `## Setup` becomes `setup-1`, the third
`setup-2`. Each call to `toHtml` or `toc` is independent — converting the same
Markdown twice gives the same HTML.

`toc(markdown)` returns `[{ level, text, id }]` for every heading, with the same
ids `toHtml` would use.

## Development

```
node --test
```
