# lru-client

A small API client with a least-recently-used (LRU) response cache and
request de-duplication.

```js
const { createClient, memoryTransport } = require('./src');

const transport = memoryTransport({ '/users/1': { id: 1, name: 'Ada' } }, { delayMs: 5 });
const client = createClient({ transport, capacity: 50 });

await client.getUser(1); // one request
await client.getUser(1); // served from the cache
```

## LRUCache (`src/lru.js`)

* `new LRUCache({ capacity = 100, ttlMs = 0, now = Date.now })` — `ttlMs = 0` means entries never expire.
* `get(key)` returns the value (or `undefined`) and marks the entry as **most recently used**.
* `peek(key)` and `has(key)` do **not** change recency.
* `set(key, value)` inserts or replaces (a replaced entry becomes most recent) and then evicts the
  least recently used entries until `size <= capacity`.
* An entry expires once `now() - storedAt >= ttlMs`; expired entries behave as if they were absent.
* `keys()` lists keys from least to most recently used.

## Client (`src/client.js`)

* `getJson(path)` returns the cached value if there is one. Otherwise it asks the transport — but
  **concurrent calls for the same path share one request**. Successful responses are cached;
  failures are not (the next call tries again).
* `getUser(id)` fetches `/users/<id>`; `getUsers(ids)` fetches several users and returns them in the
  order of `ids`.
* `stats()` returns `{ requests, hits }`: transport calls made and calls answered from the cache.

## Development

```
node --test
```
