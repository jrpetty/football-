// The specification of Engineering: Ladder, assembled level by level. Level L's prompt is the introduction, the
// sections of levels 1..L (in order), its examples, its limits and the trailer. Each section only ADDS commands and
// rules; nothing a later section says changes what an earlier command does on inputs of an earlier level.

export const FN = 'runStore';

/** Per level: commands it adds, largest test input, largest store, time limit per hidden test (ms). */
export const LEVELS = [
  { level: 1, adds: ['SET', 'GET', 'DEL'], maxCommands: 200, maxKeys: 100, timeoutMs: 2000 },
  { level: 2, adds: ['SETNX', 'COUNT', 'SIZE', 'POPULATE'], maxCommands: 1000, maxKeys: 2000, timeoutMs: 2000 },
  { level: 3, adds: ['INCR'], maxCommands: 2000, maxKeys: 5000, timeoutMs: 2000 },
  { level: 4, adds: ['BEGIN', 'ROLLBACK', 'COMMIT'], maxCommands: 3000, maxKeys: 10000, timeoutMs: 2000 },
  { level: 5, adds: ['SCAN'], maxCommands: 4000, maxKeys: 20000, timeoutMs: 2000 },
  { level: 6, adds: ['TIME', 'EXPIRE', 'TTL', 'PERSIST'], maxCommands: 5000, maxKeys: 40000, timeoutMs: 3000 },
  { level: 7, adds: ['RENAME'], maxCommands: 6000, maxKeys: 60000, timeoutMs: 4000 },
  { level: 8, adds: ['SNAPSHOT', 'GETAT'], maxCommands: 8000, maxKeys: 100000, timeoutMs: 8000 },
  { level: 9, adds: ['DELPREFIX'], maxCommands: 10000, maxKeys: 150000, timeoutMs: 12000 },
  { level: 10, adds: ['COUNTRANGE'], maxCommands: 12000, maxKeys: 200000, timeoutMs: 15000 },
];

export const commandsUpTo = (level) => LEVELS.filter((l) => l.level <= level).flatMap((l) => l.adds);

const INTRO = `Write a JavaScript function \`runStore(commands)\` that simulates a small in-memory key-value store (a mini database) and returns its replies.

\`commands\` is an array of commands, processed in order. Each command is an array of strings: the command name followed by its arguments, for example \`["SET", "user:1", "Ada"]\`. The function returns an array of strings with exactly one reply per command, in the same order.

- A **key** is a non-empty string of at most 24 characters, each one of \`a\`-\`z\`, \`0\`-\`9\`, \`:\` and \`_\`.
- A **value** is a string of at most 24 printable ASCII characters (character codes 32 to 126, so spaces and punctuation are allowed). A value may be the empty string.
- A key **exists** once a value is stored under it, until it is removed.
- Every command is well-formed: it is one of the commands listed below, with exactly the arguments shown, each of the stated form.`;

const SECTIONS = {
  1: `**Level 1: basic commands**
- \`["SET", key, value]\`: stores \`value\` under \`key\`, replacing any value it had. Reply \`"OK"\`.
- \`["GET", key]\`: reply the value stored under \`key\`, or \`"(nil)"\` if the key does not exist.
- \`["DEL", key]\`: removes the key. Reply \`"1"\` if it existed, \`"0"\` if it did not.`,

  2: `**Level 2: counting and bulk loading**
- \`["SETNX", key, value]\`: if the key does not exist, store \`value\` under it and reply \`"1"\`; if it exists, change nothing and reply \`"0"\`.
- \`["COUNT", value]\`: reply the number of existing keys whose value is exactly \`value\` (compared character by character, so case and spaces matter), as a decimal string such as \`"0"\` or \`"12"\`.
- \`["SIZE"]\`: reply the number of existing keys, as a decimal string.
- \`["POPULATE", prefix, count, value]\`: \`prefix\` is a string of key characters (possibly empty) and \`count\` a canonical integer (defined below) from 0 to 200000; every key it makes is a valid key. For each i = 0, 1, ..., count - 1 in this order, the key made of \`prefix\` followed by i in decimal (\`"u:0"\`, \`"u:1"\`, ..., \`"u:10"\`, ...) is created with \`value\` if it does not exist; keys that already exist are left unchanged. Reply the number of keys created, as a decimal string.
- A **canonical integer** is \`"0"\`, or an optional \`-\` followed by a digit from 1 to 9 and then any number of digits. For example \`"0"\`, \`"7"\`, \`"-42"\` and \`"123456789012345678901234"\` are canonical; \`"-0"\`, \`"007"\`, \`"+5"\`, \`" 5"\`, \`"5 "\`, \`"1e3"\`, \`"1.0"\`, \`"0x1F"\` and \`""\` are not.`,

  3: `**Level 3: integer values**
- \`["INCR", key, delta]\`: \`delta\` is a canonical integer from -10^18 to 10^18. If the key does not exist, its value counts as \`"0"\`. If the key's value is not a canonical integer, reply \`"ERR not an integer"\` and change nothing. Otherwise add \`delta\` to the value, store the result as a canonical integer and reply with it (for example \`"-3"\`). Values can grow far beyond 2^53 and the arithmetic must be exact (JavaScript's \`BigInt\` is available).`,

  4: `**Level 4: transactions**
- \`["BEGIN"]\`: opens a transaction. Transactions nest: a BEGIN while a transaction is open opens a new innermost transaction inside it. Reply \`"OK"\`.
- \`["ROLLBACK"]\`: undoes every change made to the store since the BEGIN of the innermost open transaction (including the changes of transactions nested inside it that were committed) and closes that transaction. Reply \`"OK"\`, or \`"NO TRANSACTION"\` (changing nothing) if no transaction is open.
- \`["COMMIT"]\`: closes the innermost open transaction and keeps its changes. If it was nested inside another transaction, its changes now belong to that outer transaction, so a later ROLLBACK of the outer transaction undoes them too. Reply \`"OK"\`, or \`"NO TRANSACTION"\` if no transaction is open.
- Every command sees the current contents of the store, including the changes of open transactions. After a ROLLBACK every key exists, or not, with exactly the value it had just before the matching BEGIN. A command that changes nothing (such as a failed INCR or SETNX) has nothing to undo.`,

  5: `**Level 5: ordered scans**
- \`["SCAN", prefix, limit]\`: \`prefix\` is a string of key characters (possibly empty) and \`limit\` a canonical integer from 1 to 1000. Reply the existing keys that start with \`prefix\`, in ascending order, at most \`limit\` of them (the smallest ones), joined by commas, for example \`"user:1,user:10,user:2"\`. Reply \`""\` (the empty string) if no existing key starts with \`prefix\`.
- Keys are ordered by comparing their character codes from left to right (JavaScript's default string order, \`a < b\`): digits come before \`:\`, \`:\` before \`_\`, \`_\` before letters, and a key comes before every longer key that starts with it.`,

  6: `**Level 6: expiry**
- The store has a clock: a whole number that starts at 0.
- \`["TIME", t]\`: \`t\` is a canonical integer from 0 to 10^9 and is never less than the current clock. Sets the clock to \`t\`. Reply \`"OK"\`.
- \`["EXPIRE", key, seconds]\`: \`seconds\` is a canonical integer from 0 to 10^9. If the key exists, give it the deadline clock + seconds (replacing any deadline it had) and reply \`"1"\`; otherwise reply \`"0"\`.
- \`["TTL", key]\`: reply \`"-2"\` if the key does not exist, \`"-1"\` if it exists without a deadline, and otherwise its deadline minus the clock, for example \`"15"\`.
- \`["PERSIST", key]\`: if the key exists and has a deadline, remove the deadline and reply \`"1"\`; otherwise reply \`"0"\`.
- A key with a deadline exists only while the clock is less than its deadline. As soon as the clock reaches the deadline, the key is removed, for every command (GET, DEL, SETNX, COUNT, SIZE, POPULATE, INCR, SCAN, TTL, EXPIRE, PERSIST and all later ones). EXPIRE with 0 seconds therefore removes the key at once (and replies \`"1"\`).
- SET, SETNX and POPULATE store keys without a deadline (SET on a key that had one removes it). INCR keeps the deadline of an existing key; INCR on a key that does not exist creates it without a deadline.
- Transactions: ROLLBACK also restores deadlines, so every key gets back the value and the deadline it had just before the matching BEGIN. The clock is never rolled back (TIME is not undone), so a key that gets back a deadline the clock has already reached does not exist.`,

  7: `**Level 7: renaming**
- \`["RENAME", source, target]\`: if \`source\` does not exist, reply \`"ERR no such key"\` and change nothing. Otherwise reply \`"OK"\`: if \`target\` is the same key as \`source\`, nothing changes; otherwise \`target\` gets the value and the deadline (or lack of one) of \`source\`, replacing whatever \`target\` held, and \`source\` is removed. ROLLBACK undoes a RENAME like any other change.`,

  8: `**Level 8: snapshots**
- \`["SNAPSHOT"]\`: records a read-only snapshot of the store as it is at this moment, including the changes of open transactions and the current clock. Snapshots are numbered 1, 2, 3, ... in the order they are taken; reply the new snapshot's number, for example \`"1"\`. Nothing that happens later changes a snapshot, not even a ROLLBACK, and snapshot numbers are never reused or undone.
- \`["GETAT", key, id]\`: \`id\` is a canonical integer. If snapshot \`id\` has been taken, reply what \`["GET", key]\` would have replied at the moment it was taken (a key whose deadline that moment's clock had reached did not exist). Otherwise reply \`"ERR no such snapshot"\`.`,

  9: `**Level 9: bulk delete**
- \`["DELPREFIX", prefix]\`: \`prefix\` is a string of key characters (possibly empty, which matches every key). Removes every existing key that starts with \`prefix\` and replies the number of keys removed, as a decimal string. It is a change like any other: ROLLBACK undoes it and earlier snapshots are not affected.`,

  10: `**Level 10: range counts**
- \`["COUNTRANGE", low, high]\`: \`low\` and \`high\` are strings of key characters (either may be empty). Reply the number of existing keys k with low <= k < high in the order defined for SCAN, as a decimal string (\`"0"\` when low >= high).`,
};

const TRAILER = (sec) =>
  'Respond with a single ```javascript code block containing the complete function (plain JavaScript, no imports, no TypeScript). ' +
  'The function must have exactly the name and parameters given above; you may define helper functions inside the same code block. ' +
  'Do not read input or print output: the function is called directly with the arguments and its return value is checked against hidden tests, ' +
  `including large inputs with a time limit of about ${sec} seconds per test. Give exactly one solution: if your answer contains several code blocks, only the last one is run.`;

function limits(L) {
  const fmt = (n) => n.toLocaleString('en-GB');
  return `**Size of the tests.** A test has at most ${fmt(L.maxCommands)} commands${L.level >= 2 ? `, and at most ${fmt(L.maxKeys)} keys exist at any one time (most of them made by POPULATE)` : ''}.${L.level >= 4 ? ' Transactions may be nested more than a thousand deep.' : ''} The hidden tests include the largest sizes, so every command should take time that grows at most slowly with the size of the store.`;
}

export function fmtExamples(examples) {
  return '```javascript\n' + examples.map(([args, exp]) => `${FN}(${args.map((a) => JSON.stringify(a)).join(', ')})\n// returns ${JSON.stringify(exp)}`).join('\n') + '\n```';
}

/** The full prompt of one level. */
export function prompt(level, examples) {
  const L = LEVELS[level - 1];
  const sections = [];
  for (let l = 1; l <= level; l++) sections.push(SECTIONS[l]);
  return [
    INTRO,
    `The commands, level by level (this task is level ${level}; all ${level === 1 ? 'of it applies' : `${level} levels apply`}):`,
    ...sections,
    limits(L),
    'Examples:\n' + fmtExamples(examples),
    TRAILER(L.timeoutMs / 1000),
  ].join('\n\n');
}
