// The hidden tests and prompt examples of every level of Engineering: Ladder. Pure functions of fixed seeds:
// gen.mjs turns them into tests/coding/engineering-ladder.json with every expected reply computed by ref.js.
//
// Each level has: hand-made tests for the rules it adds (and for their interplay with earlier levels), small
// random streams over a near-collision key pool (checked by the Python oracle), medium random streams, and from
// level 4 on, large tests sized so that the obvious full-scan / copy-everything approach to that level's new
// commands runs out of time while the reference finishes in a fraction of the limit.
import { rng, randomStream, KEY_POOL, VALUE_POOL } from './lib.mjs';

const S = (k, v) => ['SET', k, v], G = (k) => ['GET', k], D = (k) => ['DEL', k];
const NX = (k, v) => ['SETNX', k, v], CNT = (v) => ['COUNT', v], SZ = () => ['SIZE'], POP = (p, n, v) => ['POPULATE', p, String(n), v];
const INC = (k, d) => ['INCR', k, String(d)], B = () => ['BEGIN'], RB = () => ['ROLLBACK'], CM = () => ['COMMIT'];
const SC = (p, n) => ['SCAN', p, String(n)], T = (t) => ['TIME', String(t)], EX = (k, s) => ['EXPIRE', k, String(s)];
const TTL = (k) => ['TTL', k], PER = (k) => ['PERSIST', k], RN = (a, b) => ['RENAME', a, b], SNAP = () => ['SNAPSHOT'];
const GA = (k, i) => ['GETAT', k, String(i)], DP = (p) => ['DELPREFIX', p], CR = (a, b) => ['COUNTRANGE', a, b];

// ── examples shown in each prompt (also checked against the reference and the oracle) ─────────────────
export const EXAMPLES = {
  1: [[[S('user:1', 'Ada'), G('user:1'), G('user:2'), D('user:1'), D('user:1'), G('user:1')]]],
  2: [[[S('a', 'red'), NX('a', 'blue'), NX('b', 'red'), CNT('red'), SZ(), POP('u:', 12, 'red'), G('u:10'), CNT('red'), SZ()]]],
  3: [[[INC('n', 5), INC('n', -8), S('big', '9007199254740993'), INC('big', 1), S('x', '007'), INC('x', 1), G('x')]]],
  4: [[[S('a', '1'), B(), S('a', '2'), B(), D('a'), CM(), G('a'), RB(), G('a'), RB()]], [[B(), INC('c', 1), B(), INC('c', 1), RB(), CM(), G('c'), SZ()]]],
  5: [[[POP('u:', 12, 'x'), S('u', 'y'), S('u_a', 'z'), SC('u', 4), SC('u:1', 10), SC('v', 3)]]],
  6: [[[S('a', '1'), EX('a', 10), T(4), TTL('a'), S('b', '2'), EX('b', 5), T(9), G('b'), SZ(), B(), PER('a'), RB(), TTL('a'), T(10), G('a')]]],
  7: [[[S('a', '1'), EX('a', 5), RN('a', 'b'), TTL('b'), G('a'), RN('a', 'c'), S('c', '3'), RN('b', 'c'), G('c'), CNT('3')]]],
  8: [[[S('a', '1'), SNAP(), B(), S('a', '2'), SNAP(), RB(), GA('a', 1), GA('a', 2), G('a'), GA('a', 3)]]],
  9: [[[POP('u:', 12, 'x'), S('user', 'y'), B(), DP('u:1'), SZ(), RB(), DP(''), SZ()]]],
  10: [[[POP('u:', 12, 'x'), CR('u:1', 'u:2'), CR('u:', 'u:5'), CR('b', 'a'), CR('', 'u:10')]]],
};

// ── helpers for big tests ─────────────────────────────────────────────────────────────────────────────
const groups = (R, prefixes, total, values) => {
  const per = Math.floor(total / prefixes.length);
  return prefixes.map((p) => POP(p, per, R.pick(values)));
};
const existingKey = (R, prefixes, per) => R.pick(prefixes) + R.int(0, per - 1);
const PFX = ['acct:', 'user:', 'item:', 'log:', 'tmp:', 'zz_'];

/** Mostly reads of one kind over a big populated store, with writes in between that keep the answers changing. */
function bigMixed(R, level, { keys, n, mix, scanPrefixes }) {
  // populated keys + a small pool of extra keys = at most `keys` keys at any time (the limit the prompt states)
  const extra = Math.max(10, Math.floor(keys / 25));
  const per = Math.floor((keys - extra) / PFX.length);
  const vals = ['red', 'green', 'blue', 'x', '1', '0'];
  const cmds = groups(R, PFX, per * PFX.length, vals);
  const newKey = () => 'new:' + R.int(0, extra - 1);
  let clock = 0, snaps = 0, depth = 0;
  const ops = Object.entries(mix);
  const tot = ops.reduce((s, [, w]) => s + w, 0);
  for (let i = 0; i < n; i++) {
    let x = R.next() * tot, op = ops[0][0];
    for (const [o, w] of ops) { x -= w; if (x <= 0) { op = o; break; } }
    const k = R.chance(0.85) ? existingKey(R, PFX, per) : newKey();
    switch (op) {
      case 'set': cmds.push(S(k, R.pick(vals))); break;
      case 'get': cmds.push(G(k)); break;
      case 'del': cmds.push(D(k)); break;
      case 'count': cmds.push(CNT(R.pick(vals))); break;
      case 'size': cmds.push(SZ()); break;
      case 'incr': cmds.push(INC(k, R.int(-5, 5))); break;
      case 'tx': if (depth && R.chance(0.5)) { cmds.push(R.chance(0.5) ? RB() : CM()); depth--; } else { cmds.push(B()); depth++; } break;
      case 'scan': cmds.push(SC(R.pick(scanPrefixes ?? [...PFX, 'user:1', 'acct:99', 'log:12', 'n', '', 'item:5']), R.int(1, 5))); break;
      case 'time': clock += R.int(0, 3); cmds.push(T(clock)); break;
      case 'expire': cmds.push(EX(k, R.int(0, 40))); break;
      case 'ttl': cmds.push(TTL(k)); break;
      case 'persist': cmds.push(PER(k)); break;
      case 'rename': cmds.push(RN(k, R.chance(0.5) ? existingKey(R, PFX, per) : newKey())); break;
      case 'snap': cmds.push(SNAP()); snaps++; break;
      case 'getat': cmds.push(GA(k, R.int(1, Math.max(1, snaps)))); break;
      case 'delprefix': cmds.push(DP(R.pick(PFX) + R.int(1, 999))); break;
      case 'repop': cmds.push(POP(R.pick(PFX), R.int(1, Math.min(per, 1000)), R.pick(vals))); break;
      case 'range': { const a = existingKey(R, PFX, per), b = existingKey(R, PFX, per); cmds.push(CR(a < b ? a : b, a < b ? b : a)); break; }
    }
  }
  while (depth-- > 0) cmds.push(R.chance(0.5) ? CM() : RB());
  return cmds;
}

/** Thousands of nested transactions: work at the bottom, then a long run of COMMITs, then a ROLLBACK of the outermost. */
function deepNest(R, { depth, keys }) {
  const cmds = [POP('base:', keys, 'v')];
  for (let i = 0; i < depth; i++) { cmds.push(B()); if (i % 7 === 0) cmds.push(S('base:' + R.int(0, keys - 1), 'd' + i)); }
  cmds.push(POP('deep:', keys, 'w'), SZ(), CNT('w'));
  for (let i = 0; i < depth - 1; i++) { cmds.push(CM()); if (i % 97 === 0) cmds.push(SZ(), CNT('v')); }
  cmds.push(SZ(), CNT('w'), RB(), SZ(), CNT('w'), CNT('v'), RB());
  return cmds;
}

// ── the tests of each level ───────────────────────────────────────────────────────────────────────────
export function levelTests(level) {
  const R = rng(4242 + level * 101);
  const T_ = [];
  const add = (label, commands) => T_.push({ label, commands });
  const randoms = (count, n, opts = {}) => { for (let i = 0; i < count; i++) add(`random stream ${i + 1} (${n} commands, near-collision keys)`, randomStream(R, level, { n, ...opts })); };
  const wideKeys = Array.from({ length: 300 }, (_, i) => ['k', 'k:', 'k_', 'q:'][i % 4] + (i * 7919 % 1000));
  const wideVals = [...VALUE_POOL, ...Array.from({ length: 20 }, (_, i) => String(i - 10))];

  switch (level) {
    case 1:
      add('overwrite then read', [S('a', '1'), S('a', '2'), G('a'), S('b', 'x'), G('b'), G('a')]);
      add('missing keys read as (nil)', [G('nobody'), S('a', 'x'), G('ab'), G('a:'), G('a')]);
      add('DEL replies 1 then 0, and only removes that key', [S('a', '1'), S('ab', '2'), D('a'), D('a'), G('a'), G('ab'), D('zz')]);
      add('the empty value is a value, not (nil)', [S('e', ''), G('e'), D('e'), G('e'), S('e', ''), G('e')]);
      add('values are stored verbatim (spaces, punctuation, look-alikes)', [S('s', 'a b, c!'), S('n', '(nil)'), S('o', 'OK'), S('t', '  '), G('s'), G('n'), G('o'), G('t'), D('n'), G('n')]);
      add('similar keys are different keys', [S('a:1', 'x'), S('a_1', 'y'), S('a1', 'z'), G('a:1'), G('a_1'), G('a1'), D('a_1'), G('a:1'), G('a_1')]);
      add('re-SET after DEL', [S('k', '1'), D('k'), S('k', '2'), G('k'), D('k'), D('k'), G('k')]);
      randoms(6, 50);
      randoms(2, 200, { keys: wideKeys, values: wideVals });
      break;
    case 2:
      add('SETNX only writes missing keys', [NX('a', '1'), NX('a', '2'), G('a'), S('a', '3'), NX('a', '3'), G('a'), D('a'), NX('a', '4'), G('a')]);
      add('COUNT is exact: case and spaces matter', [S('a', 'red'), S('b', 'Red'), S('c', 'red '), S('d', 'red'), CNT('red'), CNT('Red'), CNT('red '), CNT('RED'), CNT('')]);
      add('COUNT follows overwrites and deletes', [S('a', 'x'), S('b', 'x'), CNT('x'), S('a', 'y'), CNT('x'), CNT('y'), D('b'), CNT('x'), S('a', 'y'), CNT('y')]);
      add('SIZE counts keys, not writes', [SZ(), S('a', '1'), S('a', '2'), SZ(), D('zz'), SZ(), NX('b', '1'), NX('b', '1'), SZ(), D('a'), SZ()]);
      add('the empty value is counted like any other', [S('a', ''), S('b', ''), NX('c', ''), CNT(''), SZ(), D('a'), CNT('')]);
      add('POPULATE makes prefix + decimal index', [POP('u:', 12, 'v'), SZ(), G('u:0'), G('u:9'), G('u:10'), G('u:11'), G('u:12'), CNT('v')]);
      add('POPULATE leaves existing keys alone and replies the number created', [S('u:3', 'mine'), S('u:5', 'v'), POP('u:', 8, 'v'), G('u:3'), CNT('v'), CNT('mine'), POP('u:', 10, 'w'), G('u:8'), G('u:7'), SZ()]);
      add('POPULATE with count 0 and with an empty prefix', [POP('p', 0, 'x'), SZ(), POP('', 3, 'x'), G('0'), G('2'), G('3'), SZ(), POP('', 5, 'y'), CNT('x'), CNT('y')]);
      add('POPULATE re-creates deleted keys only', [POP('k', 5, 'a'), D('k2'), D('k4'), S('k1', 'b'), POP('k', 6, 'c'), G('k1'), G('k2'), G('k4'), G('k5'), CNT('a'), CNT('c'), SZ()]);
      add('SETNX and COUNT on values that look like replies', [S('a', 'OK'), NX('b', '(nil)'), CNT('OK'), CNT('(nil)'), G('b'), NX('b', 'x'), G('b')]);
      randoms(6, 60);
      randoms(2, 300, { keys: wideKeys, values: wideVals, big: true });
      add('bulk load then counts (2,000 keys)', bigMixed(R, level, { keys: 2000, n: 800, mix: { set: 3, get: 2, del: 1, count: 4, size: 2, repop: 0.2 } }));
      break;
    case 3:
      add('INCR on a missing key starts from 0', [INC('n', 5), G('n'), INC('m', -3), G('m'), INC('z', 0), G('z'), SZ()]);
      add('INCR down through zero gives "0", never "-0"', [S('n', '-2'), INC('n', 1), INC('n', 1), INC('n', 1), G('n'), INC('n', -1), CNT('0')]);
      add('values that are not canonical integers', [S('a', '007'), S('b', '-0'), S('c', '+5'), S('d', ' 5'), S('e', '5 '), S('f', '1e3'), S('g', '1.0'), S('h', ''), S('i', '0x1F'), S('j', 'five'),
        INC('a', 1), INC('b', 1), INC('c', 1), INC('d', 1), INC('e', 1), INC('f', 1), INC('g', 1), INC('h', 1), INC('i', 1), INC('j', 1), G('a'), G('h'), CNT('007')]);
      add('canonical values are accepted', [S('a', '0'), S('b', '-42'), S('c', '123456789012345678901234'), INC('a', 1), INC('b', 42), INC('c', -1), G('c')]);
      add('exact beyond 2^53', [S('n', '9007199254740992'), INC('n', 1), INC('n', 1), S('m', '-9007199254740993'), INC('m', -1), INC('p', '1000000000000000000'), INC('p', '1000000000000000000'), INC('p', '1000000000000000000'), G('p')]);
      add('a failed INCR changes nothing', [S('a', 'x'), INC('a', 1), G('a'), CNT('x'), SZ(), NX('b', '1.5'), INC('b', 2), G('b')]);
      add('COUNT and SETNX see INCR results', [S('a', '4'), S('b', '5'), INC('a', 1), CNT('5'), CNT('4'), NX('c', '5'), CNT('5'), INC('c', 0), CNT('5')]);
      add('INCR on POPULATE values', [POP('c:', 5, '10'), INC('c:3', -11), INC('c:3', 1), G('c:3'), CNT('10'), CNT('0'), POP('d:', 2, '01'), INC('d:1', 1), G('d:1')]);
      { const cmds = [S('acc', '1')]; for (let i = 0; i < 1500; i++) cmds.push(INC('acc', i % 3 === 0 ? '999999999999999999' : i % 3 === 1 ? '-1000000000000000000' : '123456789123456789')); cmds.push(G('acc'), CNT('1')); add('1,500 big INCRs on one key (exact running total)', cmds); }
      randoms(6, 60);
      randoms(2, 400, { keys: wideKeys, values: wideVals, big: true });
      add('bulk load, counts and increments (5,000 keys)', bigMixed(R, level, { keys: 5000, n: 1800, mix: { set: 2, get: 1, del: 1, count: 3, size: 1, incr: 3, repop: 0.2 } }));
      break;
    case 4:
      add('ROLLBACK and COMMIT with no transaction', [RB(), CM(), S('a', '1'), RB(), G('a'), B(), CM(), CM(), RB()]);
      add('ROLLBACK restores changed, deleted and created keys', [S('a', '1'), S('b', '2'), B(), S('a', '9'), D('b'), S('c', '3'), G('a'), G('b'), SZ(), RB(), G('a'), G('b'), G('c'), SZ()]);
      add('a committed inner transaction is undone by the outer ROLLBACK', [S('a', '1'), B(), B(), S('a', '2'), CM(), G('a'), RB(), G('a'), RB()]);
      add('ROLLBACK closes only the innermost transaction', [B(), S('a', '1'), B(), S('a', '2'), RB(), G('a'), CM(), G('a'), RB()]);
      add('COMMIT closes only the innermost transaction', [B(), B(), S('x', '1'), CM(), G('x'), RB(), G('x'), CM()]);
      add('many changes to one key: ROLLBACK goes back to before BEGIN', [S('k', 'start'), B(), S('k', '1'), INC('k', 1), D('k'), S('k', 'end'), RB(), G('k'), B(), D('k'), S('k', 'x'), D('k'), RB(), G('k')]);
      add('COUNT and SIZE inside and after transactions', [POP('u:', 5, 'v'), B(), S('u:1', 'w'), D('u:2'), CNT('v'), SZ(), B(), POP('u:', 8, 'v'), CNT('v'), RB(), CNT('v'), SZ(), RB(), CNT('v'), SZ(), CNT('w')]);
      add('failed commands inside a transaction leave nothing to undo', [S('a', 'x'), B(), INC('a', 1), NX('a', 'y'), D('nope'), RB(), G('a'), CNT('x')]);
      add('POPULATE inside a transaction is undone as a whole', [POP('p', 3, 'a'), B(), POP('p', 6, 'b'), SZ(), CNT('b'), RB(), SZ(), CNT('b'), G('p4')]);
      add('INCR inside nested transactions', [S('n', '10'), B(), INC('n', 5), B(), INC('n', 5), CM(), B(), INC('n', 100), RB(), G('n'), RB(), G('n')]);
      { const cmds = []; for (let i = 0; i < 300; i++) cmds.push(B(), S('d', String(i))); for (let i = 0; i < 300; i++) cmds.push(i % 2 ? CM() : RB(), G('d')); cmds.push(RB(), G('d')); add('300 nested transactions, alternately rolled back and committed', cmds); }
      randoms(6, 70);
      randoms(2, 500, { keys: wideKeys, values: wideVals, big: true });
      add('transactions over a 10,000-key store', bigMixed(R, level, { keys: 10000, n: 2500, mix: { set: 3, get: 1, del: 1, count: 2, size: 1, incr: 1, tx: 0.4, repop: 0.1 } }));
      add('1,200 nested transactions with bulk work at the bottom', deepNest(R, { depth: 1200, keys: 5000 }));
      break;
    case 5:
      add('order: digits < ":" < "_" < letters, shorter first', [S('ab', '1'), S('a_1', '1'), S('a:1', '1'), S('a1', '1'), S('a', '1'), S('a:10', '1'), S('a:2', '1'), SC('a', 10), SC('', 3)]);
      add('POPULATE keys sort as strings, not numbers', [POP('u:', 12, 'x'), SC('u:', 12), SC('u:1', 5), SC('u:2', 5)]);
      add('limit keeps the smallest keys', [POP('k', 30, 'x'), SC('k', 1), SC('k', 4), SC('k2', 1000), SC('k3', 2)]);
      add('no match replies the empty string; a key equal to the prefix matches', [S('user', 'x'), S('users', 'y'), SC('user', 5), SC('userz', 5), SC('v', 5), SC('use', 1)]);
      add('scans see deletes and rollbacks', [POP('a', 5, 'x'), D('a2'), SC('a', 10), B(), D('a0'), S('a9', 'y'), SC('a', 10), RB(), SC('a', 10), B(), S('a7', 'z'), CM(), SC('a', 3)]);
      add('scan after keys were removed and re-created', [S('m:1', 'a'), S('m:2', 'b'), D('m:1'), SC('m:', 5), S('m:1', 'c'), SC('m:', 5), SC('m', 1)]);
      randoms(6, 70);
      randoms(2, 600, { keys: wideKeys, values: wideVals, big: true });
      add('scans over a 20,000-key store', bigMixed(R, level, { keys: 20000, n: 3950, mix: { set: 2, del: 1, scan: 8, count: 1, tx: 3, incr: 0.5 }, scanPrefixes: ['', '', 'a', 'u', 'i', 'l', 't', 'z', ...PFX, 'user:1', 'acct:99'] }));
      add('transactions and scans, 1,500 nested', deepNest(R, { depth: 1500, keys: 10000 }).concat([SC('deep:', 3), SC('base:1', 4)]));
      break;
    case 6:
      add('a key exists while the clock is below its deadline', [S('a', '1'), EX('a', 5), T(4), G('a'), TTL('a'), T(5), G('a'), TTL('a'), D('a')]);
      add('EXPIRE 0 removes at once but replies 1; EXPIRE on a missing key replies 0', [S('a', '1'), EX('a', 0), G('a'), SZ(), EX('a', 5), EX('nope', 5), TTL('nope')]);
      add('TTL: -2 missing, -1 no deadline, remaining time otherwise', [S('a', 'x'), TTL('a'), TTL('b'), EX('a', 100), TTL('a'), T(30), TTL('a'), EX('a', 7), TTL('a')]);
      add('SET removes a deadline, INCR keeps it', [S('a', '1'), EX('a', 10), S('a', '2'), TTL('a'), S('b', '5'), EX('b', 10), INC('b', 1), TTL('b'), T(10), G('a'), G('b'), INC('b', 1), TTL('b')]);
      add('PERSIST replies 1 only when it removed a deadline', [S('a', '1'), PER('a'), EX('a', 3), PER('a'), PER('a'), T(10), G('a'), PER('nope')]);
      add('expired keys vanish from COUNT, SIZE and SCAN, and SETNX/POPULATE can re-create them', [POP('p', 4, 'v'), EX('p1', 2), EX('p3', 3), CNT('v'), T(2), CNT('v'), SZ(), SC('p', 10), NX('p1', 'w'), T(3), POP('p', 4, 'z'), G('p3'), CNT('z'), SC('p', 10)]);
      add('INCR on an expired key starts again from 0 without a deadline', [S('n', '41'), EX('n', 1), T(1), INC('n', 1), TTL('n')]);
      add('ROLLBACK restores deadlines', [S('a', '1'), EX('a', 10), B(), PER('a'), S('b', '2'), EX('b', 3), RB(), TTL('a'), TTL('b'), B(), EX('a', 100), RB(), TTL('a')]);
      add('the clock is never rolled back', [S('a', '1'), EX('a', 10), B(), T(20), G('a'), RB(), G('a'), TTL('a'), SZ()]);
      add('a key that expired inside a transaction and is restored with a past deadline stays gone', [S('a', '1'), EX('a', 5), B(), S('a', '2'), T(8), G('a'), RB(), G('a'), CNT('1')]);
      add('rollback brings back a key whose old deadline is still ahead', [S('a', '1'), EX('a', 50), B(), D('a'), T(10), RB(), G('a'), TTL('a'), T(50), G('a')]);
      add('repeated TIME with the same value', [S('a', 'x'), EX('a', 2), T(1), T(1), G('a'), T(2), T(2), G('a')]);
      randoms(8, 80);
      randoms(2, 700, { keys: wideKeys, values: wideVals, big: true });
      add('expiry over a 40,000-key store', bigMixed(R, level, { keys: 40000, n: 4000, mix: { set: 1, get: 1, del: 0.5, count: 0.3, size: 0.3, expire: 3, time: 2, ttl: 1, persist: 0.5 } }));
      add('counts, scans and transactions over a 40,000-key store', bigMixed(R, level, { keys: 40000, n: 4000, mix: { set: 2, count: 3, size: 2, scan: 2, tx: 4, repop: 0.1, expire: 0.5, time: 0.5 } }));
      break;
    case 7:
      add('RENAME moves the value and removes the source', [S('a', '1'), RN('a', 'b'), G('a'), G('b'), SZ(), CNT('1')]);
      add('RENAME of a missing key is an error and changes nothing', [S('b', '2'), RN('a', 'b'), G('b'), RN('a', 'a'), SZ()]);
      add('RENAME onto an existing key replaces it (COUNT follows)', [S('a', 'x'), S('b', 'y'), RN('a', 'b'), G('b'), CNT('x'), CNT('y'), SZ()]);
      add('RENAME to itself keeps the key and its deadline', [S('a', 'x'), EX('a', 9), RN('a', 'a'), G('a'), TTL('a')]);
      add('RENAME carries the deadline, or the lack of one', [S('a', '1'), EX('a', 5), S('b', '2'), EX('b', 50), RN('a', 'b'), TTL('b'), S('c', '3'), S('d', '4'), EX('d', 9), RN('c', 'd'), TTL('d'), T(5), G('b'), G('d')]);
      add('RENAME of an expired key is an error', [S('a', '1'), EX('a', 2), T(2), RN('a', 'b'), G('b')]);
      add('ROLLBACK undoes a RENAME, both sides', [S('a', '1'), S('b', '2'), B(), RN('a', 'b'), G('a'), G('b'), RB(), G('a'), G('b'), B(), RN('a', 'c'), CM(), G('c'), G('a')]);
      add('RENAME moves a key in scan order', [POP('k', 3, 'v'), RN('k0', 'z'), RN('k2', 'a'), SC('', 5), RN('a', 'k1'), SC('', 5), SZ()]);
      add('a chain of renames inside nested transactions', [S('x', 'v'), B(), RN('x', 'y'), B(), RN('y', 'z'), CM(), RN('z', 'w'), G('w'), RB(), G('x'), G('y'), G('z'), G('w')]);
      randoms(8, 80);
      randoms(2, 800, { keys: wideKeys, values: wideVals, big: true });
      add('renames over a 60,000-key store', bigMixed(R, level, { keys: 60000, n: 4200, mix: { rename: 4, count: 2, size: 1, scan: 2, get: 1, tx: 2.5, expire: 0.5, time: 0.5 } }));
      add('transactions copying nothing: 60,000 keys, 1,500 BEGINs', bigMixed(R, level, { keys: 60000, n: 4200, mix: { tx: 3, set: 2, rename: 1, count: 1, size: 1, scan: 0.5 } }));
      break;
    case 8:
      add('GETAT sees the store as it was', [S('a', '1'), SNAP(), S('a', '2'), SNAP(), D('a'), SNAP(), GA('a', 1), GA('a', 2), GA('a', 3), G('a')]);
      add('unknown snapshot numbers', [GA('a', 1), SNAP(), GA('a', 0), GA('a', 2), GA('a', 1), GA('a', '99999999999999999999')]);
      add('a snapshot inside a rolled-back transaction keeps what it saw', [S('a', '1'), B(), S('a', '2'), SNAP(), RB(), GA('a', 1), G('a'), SNAP(), GA('a', 2)]);
      add('snapshot numbers are never undone', [B(), SNAP(), SNAP(), RB(), SNAP(), GA('k', 3), GA('k', 4)]);
      add('a snapshot remembers the clock: keys that had expired by then are (nil)', [S('a', '1'), EX('a', 5), T(3), SNAP(), T(5), SNAP(), GA('a', 1), GA('a', 2), S('a', '2'), GA('a', 1)]);
      add('a key that expired later is still visible in an earlier snapshot', [S('a', 'x'), EX('a', 10), SNAP(), T(20), GA('a', 1), G('a')]);
      add('GETAT after renames and POPULATE', [POP('p', 3, 'v'), SNAP(), RN('p1', 'q'), SNAP(), GA('p1', 1), GA('p1', 2), GA('q', 1), GA('q', 2)]);
      add('many changes between snapshots of one key', (() => { const c = []; for (let i = 0; i < 60; i++) { c.push(S('k', 'v' + i)); if (i % 5 === 0) c.push(SNAP()); } for (let s = 1; s <= 12; s++) c.push(GA('k', s)); return c; })());
      randoms(8, 90);
      randoms(2, 900, { keys: wideKeys, values: wideVals, big: true });
      add('2,000 snapshots of a 100,000-key store', bigMixed(R, level, { keys: 100000, n: 5000, mix: { snap: 2, getat: 4, set: 2, del: 0.5, tx: 0.5, rename: 0.5, count: 0.5 } }));
      add('snapshots, transactions and counts over 100,000 keys', bigMixed(R, level, { keys: 100000, n: 5000, mix: { snap: 1, getat: 2, tx: 2, set: 2, count: 1, size: 1, scan: 1, expire: 0.5, time: 0.5 } }));
      break;
    case 9:
      add('DELPREFIX removes exactly the matching keys', [S('a', '1'), S('a:1', '2'), S('a1', '3'), S('ab', '4'), S('b', '5'), DP('a:'), SC('', 10), DP('a'), SC('', 10), DP('x')]);
      add('DELPREFIX with the empty prefix empties the store', [POP('k', 7, 'v'), S('z', 'w'), DP(''), SZ(), CNT('v'), DP('')]);
      add('DELPREFIX is undone by ROLLBACK', [POP('u:', 12, 'x'), B(), DP('u:1'), SZ(), SC('u:1', 5), RB(), SZ(), SC('u:1', 5), CNT('x')]);
      add('earlier snapshots still see deleted keys', [POP('u:', 4, 'x'), SNAP(), DP('u:'), SNAP(), GA('u:2', 1), GA('u:2', 2), G('u:2')]);
      add('DELPREFIX does not count expired keys', [POP('p', 5, 'v'), EX('p1', 2), EX('p2', 9), T(3), DP('p'), SZ(), T(10), SZ()]);
      add('DELPREFIX then POPULATE re-creates', [POP('g', 20, 'a'), DP('g1'), POP('g', 20, 'b'), CNT('a'), CNT('b'), SC('g1', 20)]);
      add('DELPREFIX inside nested transactions', [POP('n', 10, 'v'), B(), DP('n1'), B(), DP('n'), RB(), SZ(), CM(), SZ(), RB(), SZ()]);
      randoms(8, 90);
      randoms(2, 1000, { keys: wideKeys, values: wideVals, big: true });
      add('bulk deletes over a 150,000-key store', bigMixed(R, level, { keys: 150000, n: 5100, mix: { delprefix: 3, repop: 0.4, count: 2, size: 1, scan: 1, set: 1, tx: 0.5 } }));
      add('bulk deletes, snapshots and renames over 150,000 keys', bigMixed(R, level, { keys: 150000, n: 5100, mix: { delprefix: 2, snap: 1, getat: 2, rename: 1, set: 1, size: 1, tx: 0.5, repop: 0.3 } }));
      break;
    case 10:
      add('COUNTRANGE uses string order, high is exclusive', [POP('u:', 12, 'x'), CR('u:1', 'u:2'), CR('u:10', 'u:11'), CR('u:10', 'u:10'), CR('u:2', 'u:1')]);
      add('empty bounds', [S('a', '1'), S('b', '2'), CR('', 'b'), CR('', ''), CR('a', ''), CR('', 'zzzz')]);
      add('bounds need not be keys', [S('a:1', 'x'), S('a_1', 'x'), S('ab', 'x'), CR('a', 'a_'), CR('a:', 'a:2'), CR('a:9', 'b')]);
      add('COUNTRANGE sees expiry, rollback and DELPREFIX', [POP('k', 10, 'v'), EX('k3', 1), B(), DP('k5'), CR('k', 'l'), RB(), CR('k', 'l'), T(1), CR('k', 'l'), CR('k3', 'k4')]);
      add('COUNTRANGE after renames', [POP('r', 5, 'v'), RN('r0', 'z'), RN('r4', 'a'), CR('b', 'y'), CR('a', 'zz')]);
      randoms(8, 100);
      randoms(2, 1000, { keys: wideKeys, values: wideVals, big: true });
      add('range counts over a 200,000-key store', bigMixed(R, level, { keys: 200000, n: 5200, mix: { range: 4, set: 1, del: 1, rename: 0.5, delprefix: 0.3, repop: 0.1, tx: 0.3 } }));
      add('everything at once over 200,000 keys', bigMixed(R, level, { keys: 200000, n: 5200, mix: { range: 2, count: 1, size: 1, scan: 1, snap: 0.5, getat: 1, tx: 1, rename: 0.5, delprefix: 0.3, expire: 0.5, time: 0.5, set: 1, incr: 0.5 } }));
      break;
  }
  return T_;
}
