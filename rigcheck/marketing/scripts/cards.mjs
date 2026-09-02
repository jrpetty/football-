/**
 * Render the standard card set from the committed JSON, in both formats, and
 * write the manifest the caption verifier holds instagram.md to.
 *
 * Everything that draws lives in cardlib.mjs; this file only decides what to
 * feed it. `npm run marketing:cards`.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { renderCards, standardCards, unesc } from './cardlib.mjs';

const json = (f) => JSON.parse(readFileSync(f, 'utf8'));
const data = { builds: json('marketing/builds.json'), bottleneck: json('marketing/bottleneck.json'), pillars: json('marketing/pillars.json') };

mkdirSync('marketing/images/story', { recursive: true });
const post = standardCards(data, 'post');
const story = standardCards(data, 'story');
const written = [
  ...(await renderCards(post, { dir: 'marketing/images', format: 'post' })),
  ...(await renderCards(story, { dir: 'marketing/images/story', format: 'story' })),
];

// The manifest: every image, its story twin, and the sentence printed on it.
// verify.mjs holds the caption file to this — an image with no caption, a
// caption pointing at no image, or a caption whose "shows" line differs from
// the card's all fail.
writeFileSync('marketing/cards.json', JSON.stringify(post.map((c) => ({
  name: c.name, file: `images/${c.name}.png`, story: `images/story/${c.name}.png`, subject: unesc(c.subject),
})), null, 2) + '\n');
console.log(written.join('\n'));
