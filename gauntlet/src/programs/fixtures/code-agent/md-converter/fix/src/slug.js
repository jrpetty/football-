'use strict';

const { plainText } = require('./inline');

/** "Using `npm` **fast**!" → "using-npm-fast" */
function slugify(text) {
  const slug = plainText(text)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug || 'section';
}

/** A fresh id generator for one document: returns slugs that have not been used yet. */
function createSlugger() {
  const seen = new Map();
  return function uniqueSlug(text) {
    const base = slugify(text);
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    return count === 0 ? base : `${base}-${count}`;
  };
}

module.exports = { slugify, createSlugger };
