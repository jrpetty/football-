# search-engine

A tiny full-text search engine for the help centre: an analyzer, an inverted
index, BM25 ranking, phrase queries, exclusions, pagination and highlighting.

```js
const { createEngine } = require('./src');

const engine = createEngine();
engine.add({ id: 'kb-1', title: 'Resetting passwords', body: "Don't panic: you can reset it yourself." });
engine.search('"reset it" password');       // { total, page, hits: [{ id, title, score }] }
engine.highlight('kb-1', 'panic');           // "Resetting passwords\n\nDon&#39;t <mark>panic</mark>: you can reset it yourself."
```

## Analysis

Text is split into words (letters and digits, optionally joined by apostrophes: `don't`, `dog's`). For each
word the analyzer records:

* **term**: lower-cased, apostrophes removed, then stemmed (see below);
* **position**: its index among *all* words of the text, counting stop words (0-based);
* **start / end**: character offsets of the **original word** in the original text, `end` exclusive
  (`text.slice(start, end)` is exactly the word as written, apostrophes included).

Stop words (`src/stopwords.js`) are not indexed and are ignored in queries, but they still occupy positions.

Stemming (`src/stemmer.js`) is deliberately simple, and exactly this:
1. words longer than 4 letters ending in `ies` → replace `ies` with `y` (`stories` → `story`);
2. otherwise, words longer than 3 letters ending in `s` but not in `ss`, `us` or `is` lose the `s`
   (`bridges` → `bridge`, `news` → `new`, `glass` and `bus` unchanged).

## Ranking (BM25)

A document matches if it contains at least one query term (and every phrase, and no excluded term). Its score
is the sum over the distinct query terms `t` it contains of

```
idf(t) × tf × (k1 + 1) / (tf + k1 × (1 − b + b × dl / avgdl))
idf(t) = ln(1 + (N − df + 0.5) / (df + 0.5))
```

with `k1 = 1.2`, `b = 0.75`, `tf` = occurrences of `t` in the document, `dl` = number of indexed (non-stop)
terms in the document, `avgdl` = the mean `dl` over all documents, `N` = number of documents and `df` = number
of documents containing `t`. Title and body are indexed together as one text (title first).

Results are sorted by score, highest first; **equal scores are ordered by document id** (ascending string
order), so results and pages are stable no matter in which order documents were added.

## Queries

* Plain words: `password reset`.
* Phrases in double quotes: `"tower of london"` matches only where the words appear in that order at the
  same relative positions as in the query (stop words count, so `tower bridge london` does not match).
* Exclusions: `-closed` drops documents containing `closed`.
* `search(query, { page = 1, pageSize = 10 })` returns `{ total, page, hits }`.

## Extras

* `suggest(query)`: "did you mean" — each unknown word is replaced by the indexed term with the smallest edit
  distance (at most 2), preferring more common terms; `null` when nothing changes.
* `facets(query)`: tag counts over all results; `searchTagged(query, tags)`: results that carry every tag.
* `stats()`: document, term and posting counts for the admin page.

## Highlighting

`highlight(id, query)` returns the document's title and body (joined by a blank line) HTML-escaped, with every
word whose term matches a query term wrapped in `<mark>…</mark>`, covering the whole original word.

## Development

```
node --test
```
