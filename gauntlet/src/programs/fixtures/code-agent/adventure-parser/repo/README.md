# adventure-parser

The command parser for a small text adventure. It turns what the player types
into a structured command object.

```js
const { parse } = require('./src');

const scope = [
  { id: 'lamp', names: ['brass lamp', 'lamp'] },
  { id: 'chest', names: ['oak chest', 'chest', 'box'] },
];
parse('put the brass lamp in the box', scope);
// → { verb: 'put', object: 'lamp', target: 'chest', preposition: 'in', direction: null, error: null }
```

## Command object

Every call to `parse` returns a **new** object with exactly these fields:

| field | meaning |
|---|---|
| `verb` | canonical verb (`take`, `drop`, `examine`, `put`, `go`, …) or `null` |
| `object` | id of the direct object, or `null` |
| `target` | id of the indirect object after a preposition, or `null` |
| `preposition` | the preposition that introduced the target, or `null` |
| `direction` | for movement: `north`, `south`, … or `null` |
| `error` | a message for the player when the input can't be understood, otherwise `null` |

## Grammar

* Input is case-insensitive; punctuation is ignored; articles (`the`, `a`, `an`,
  `some`) are dropped.
* Verbs have synonyms (see `src/lexicon.js`): `get`/`grab` → `take`, `x` → `examine`, …
  `pick up X` means `take X`, `look at X` means `examine X`.
* A bare direction (`n`, `north`, `sw`, …) or `go <direction>` moves the player.
* Objects are matched against the names of the things in scope. Names can be one
  or several words; the longest matching name wins (`red key` beats `key`). If a
  name matches more than one thing, the error asks the player which one they mean.

## Development

```
node --test
```
