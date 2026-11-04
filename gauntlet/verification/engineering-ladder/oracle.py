"""Independent oracle for Engineering: Ladder, written from the prompt text in the most literal way possible.

    python3 oracle.py < tests.json-like input      # stdin: a JSON list of command lists; stdout: a JSON list of reply lists

No cleverness at all, so it is easy to check against the specification by reading:
  - the store is a dict key -> [value, deadline or None]; entries whose deadline has passed are simply skipped
    (lazy expiry) instead of being removed (the reference removes them eagerly);
  - BEGIN pushes a full copy of the store, ROLLBACK puts that copy back, COMMIT forgets it;
  - SNAPSHOT keeps a full copy of the store plus the clock;
  - COUNT, SIZE, SCAN, DELPREFIX and COUNTRANGE look at every key; integers are Python ints.
It is far too slow for the largest hidden tests, which verify.mjs checks with the second JavaScript implementation
(alt.js) instead.
"""
import json
import re
import sys

CANON = re.compile(r'(0|-?[1-9][0-9]*)\Z')


def run(commands):
    store = {}       # key -> [value, deadline or None]
    clock = 0
    txs = []         # copies of the store taken at each BEGIN
    snaps = []       # (copy of the store, clock)

    def alive(entry, at):
        return entry is not None and (entry[1] is None or at < entry[1])

    def get(k):
        e = store.get(k)
        return e if alive(e, clock) else None

    def live_keys():
        return [k for k, e in store.items() if alive(e, clock)]

    out = []
    for c in commands:
        op = c[0]
        if op == 'SET':
            store[c[1]] = [c[2], None]
            r = 'OK'
        elif op == 'GET':
            e = get(c[1])
            r = e[0] if e else '(nil)'
        elif op == 'DEL':
            if get(c[1]):
                del store[c[1]]
                r = '1'
            else:
                r = '0'
        elif op == 'SETNX':
            if get(c[1]):
                r = '0'
            else:
                store[c[1]] = [c[2], None]
                r = '1'
        elif op == 'COUNT':
            r = str(sum(1 for k in live_keys() if store[k][0] == c[1]))
        elif op == 'SIZE':
            r = str(len(live_keys()))
        elif op == 'POPULATE':
            made = 0
            for i in range(int(c[2])):
                k = c[1] + str(i)
                if not get(k):
                    store[k] = [c[3], None]
                    made += 1
            r = str(made)
        elif op == 'INCR':
            e = get(c[1])
            cur = e[0] if e else '0'
            if not CANON.match(cur):
                r = 'ERR not an integer'
            else:
                r = str(int(cur) + int(c[2]))
                store[c[1]] = [r, e[1] if e else None]
        elif op == 'BEGIN':
            txs.append({k: list(v) for k, v in store.items()})
            r = 'OK'
        elif op == 'ROLLBACK':
            if not txs:
                r = 'NO TRANSACTION'
            else:
                store = txs.pop()
                r = 'OK'
        elif op == 'COMMIT':
            if not txs:
                r = 'NO TRANSACTION'
            else:
                txs.pop()
                r = 'OK'
        elif op == 'SCAN':
            ks = sorted(k for k in live_keys() if k.startswith(c[1]))
            r = ','.join(ks[:int(c[2])])
        elif op == 'TIME':
            assert int(c[1]) >= clock
            clock = int(c[1])
            r = 'OK'
        elif op == 'EXPIRE':
            e = get(c[1])
            if e:
                e[1] = clock + int(c[2])
                r = '1'
            else:
                r = '0'
        elif op == 'TTL':
            e = get(c[1])
            r = '-2' if not e else '-1' if e[1] is None else str(e[1] - clock)
        elif op == 'PERSIST':
            e = get(c[1])
            if e and e[1] is not None:
                e[1] = None
                r = '1'
            else:
                r = '0'
        elif op == 'RENAME':
            e = get(c[1])
            if not e:
                r = 'ERR no such key'
            else:
                if c[1] != c[2]:
                    store[c[2]] = [e[0], e[1]]
                    del store[c[1]]
                r = 'OK'
        elif op == 'SNAPSHOT':
            snaps.append(({k: list(v) for k, v in store.items()}, clock))
            r = str(len(snaps))
        elif op == 'GETAT':
            i = int(c[2])
            if not 1 <= i <= len(snaps):
                r = 'ERR no such snapshot'
            else:
                snap, at = snaps[i - 1]
                e = snap.get(c[1])
                r = e[0] if alive(e, at) else '(nil)'
        elif op == 'DELPREFIX':
            ks = [k for k in live_keys() if k.startswith(c[1])]
            for k in ks:
                del store[k]
            r = str(len(ks))
        elif op == 'COUNTRANGE':
            r = str(sum(1 for k in live_keys() if c[1] <= k < c[2]))
        else:
            raise ValueError(op)
        out.append(r)
    return out


if __name__ == '__main__':
    tests = json.load(sys.stdin)
    json.dump([run(t) for t in tests], sys.stdout)
