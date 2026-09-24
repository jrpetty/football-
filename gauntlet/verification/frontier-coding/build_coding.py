# Builds tests/coding/frontier.json from c1..c6 (prompt.md, tests.json, examples.json).
# Usage (from this folder): python3 build_coding.py
# Every example is re-checked against the reference AND an independent oracle; the reference is re-run on every hidden test.
import json, os, subprocess, sys, math
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
def tok(s): return int(math.ceil(len(s) / 3.6)) + 20

CASES = {
 'c1': dict(timeout=6000, d='extreme', oracle=('brute.py', None),
   notes="Register-machine interpreter (assembler + 32-bit machine with Z/N/C/V flags, shared call/data stack, step limit, 9 statuses). "
         "Reference: frontier-coding/c1/ref.js. Independent oracle: c1/brute.py (Python, exact integers, character-level parser written from the prompt text). "
         "Cross-checked on 23,000 random programs (valid and malformed, random case/spacing/labels/comments; c1/fuzz.mjs) with 100% agreement; all 40 hidden tests agree with the oracle. "
         "Hidden tests: one per rule (MUL wrap and C/V, DIV/MOD truncation and INT_MIN/-1, shift counts mod 32 with carry, ADD carry vs overflow, signed compare needing V, unsigned compare, hex/wrapped immediates, case rules, label placement, running off the end, stack overflow/underflow, BAD_JUMP, BAD_ADDRESS incl. wrapped absolute address, NO_INPUT, LIMIT vs HALT precedence, LOOP from 0, five SYNTAX cases), programs (factorial, bubble sort, sieve) and 4 performance programs of 2-8.4 million steps. "
         "Sandbox timing (real worker): reference max 275 ms vs 6000 ms timeout; a typical pre-assembled solution with top-level helpers ~1.8 s. Naive: re-parsing the text every step fails all 4 performance tests (timeouts); a fast solution with (a*b)|0, N-only signed jumps and shift-by-0 keeping C scores 32/40 (c1/naive_*.js)."),
 'c2': dict(timeout=6000, d='extreme', oracle=('brute.py', 'small'),
   notes="Exact area covered by >= k octilinear simple polygons, reduced fraction string. Reference: c2/ref.js (vertical slabs at every vertex and crossing x, coordinates scaled by 4 so all events and midpoints are integers, BigInt accumulation). "
         "Oracles: c2/brute.py counts quarter-triangles of unit squares (every edge lies on x=c, y=c or x+-y=c, so each triangle is fully covered or not; exact integer ray casting); c2/slab.py is a generic Fraction slab integrator with Cramer's-rule intersections; c2/typical.js is a third, all-BigInt-fraction implementation. "
         "Fuzz: 13,500 random multi-polygon cases (polyomino boundaries with 45-degree corner cuts, 45-degree-rotated polyominoes, octagons, diamonds, triangles, duplicated/shifted copies) agree with brute.py; slab.py agrees with brute.py on 600 more. "
         "Hidden tests: 14 hand-made (quarter-unit crossings, nesting, coincident opposite-orientation copies, shared edges, corner touching, collinear vertices, clockwise input, notches), 4 coordinate-1e9 cases whose areas exceed 2^53, 6 random mediums (brute), 4 of them scaled to ~1e9 (area scales by s^2, checked exactly), 6 large (427-1435 vertices, slab.py). "
         "Timing: reference max 57 ms vs 6000 ms; the all-BigInt generic solution 1.7 s. Naive: double-precision accumulation 24/34, winding count assuming counter-clockwise input 17/34, quarter-cell counting 20/34 (times out on large coordinates)."),
 'c3': dict(timeout=3000, d='extreme', oracle=('brute.py', 'small'),
   notes="Distinct palindromes with two non-overlapping occurrences: count, longest, total occurrences, on run-length-described strings up to 200,000. Reference: c3/ref.js (eertree; first end at creation, last end and occurrence counts propagated along suffix links in reverse creation order; iterative). "
         "Oracles: c3/brute.py (all substrings), c3/brute2.py (expand around centres), c3/oracle2.py (last occurrences from an eertree of the REVERSED string matched node-by-node, total occurrences from Manacher radii + hashing with binary search, using the fact that counted palindromes at one centre form a prefix of radii). "
         "Fuzz: 9,500 random strings agree three ways (ref, brute, oracle2). Hidden tests: 14 small (brute), 5 medium (brute2), 11 large incl. a^200000 (closed form [100000,100000,15000050000]), periodic, one-letter-different centre, Fibonacci (90k), Zimin (32k), Thue-Morse; all 30 checked by oracle2. "
         "Timing: reference max ~250 ms vs 3000 ms. Naive: expand-around-centre with a Map 21/30 (9 timeouts); eertree without last-position propagation 5/30; eertree with recursive DFS propagation 23/30 (stack overflow on deep suffix-link chains)."),
 'c4': dict(timeout=8000, d='extreme', oracle=('brute.py', 'small'),
   notes="Minimum-cost truck fleet = minimum-cost path cover of the 'can follow' DAG with a cap on the number of paths. Reference: c4/ref.js (successive shortest augmenting paths with potentials on the link matching; augment while f < n-K or the next path is negative). "
         "Oracles: c4/brute.py enumerates every split into ordered routes and simulates each truck's timeline directly; c4/hungarian.py solves a different formulation (2n x 2n assignment with K truck-start rows and n-K filler rows) with a numpy O(N^3) Hungarian algorithm. "
         "Fuzz: 10,000 random cases (n <= 8) agree with brute.py; hungarian.py agrees with brute.py on 800 more; all 32 hidden tests agree with hungarian.py, the 22 small ones also with brute.py. "
         "Hidden tests: exact-equality chaining, one-unit-late infeasibility, -1 cases, free waiting, unsorted/equal-start jobs, cap > n, 9 search-found traps where min-cost MAXIMUM matching, ignoring the cap, or greedy dispatch is wrong, 4 random n=8, 10 large (n=60-400; F=0, huge F, binding cap, cap one below the minimum fleet). "
         "Timing: reference max 1.14 s vs 8000 ms; a generic SPFA min-cost-flow template 2.3 s. Naive: min-cost maximum matching 22/32, ignoring the cap 27/32, greedy dispatch 12/32."),
 'c5': dict(timeout=3000, d='extreme', oracle=('brute.py', 'small'),
   notes="Minimum-cost towers on a tree with per-vertex reach and a subset of vertices that must be covered. Reference: c5/ref.js (bottom-up DP whose state is either the best inside reach P[r] or the deepest uncovered demand D[d]; deficits make inside reach irrelevant; iterative). "
         "Oracles: c5/brute.py (all tower subsets); c5/label.py, a different DP that labels every vertex with its coverage slack and enforces neighbour consistency and justification; c5/greedy.py for uniform-reach unit-cost instances (classic deepest-first greedy). "
         "Fuzz: 9,500 random trees (n <= 15; random, deep, path, star, caterpillar shapes; reach up to 5) agree three ways. Hidden tests: 10 hand-made (coverage through the root from another branch, reach beyond the tree, overlapping towers, unordered parent indices), 3 random small (brute), 6 random n=30-60, 7 medium and 4 large (n up to 5,000, reach up to 20); all 30 checked by label.py, 4 uniform ones also by greedy.py. "
         "Timing: reference max 50 ms vs 3000 ms. Naive: uniform-radius DP with R = max(reach) 9/30, deficit pruned one step too early 16/30, exhaustive search 16/30, cost-per-coverage greedy 16/30."),
 'c6': dict(timeout=3000, d='extreme', oracle=('brute.py', None),
   notes="Order-book simulation: price-time priority, icebergs (display refill re-queues), market/stop orders (checked only after complete events, earliest first, re-checked after each), cancels and amends (priority kept only for same-price reductions; icebergs/stops/unknown ids ignored). "
         "Reference: c6/ref.js (price heaps + FIFO levels). Oracle: c6/brute.py (Python, scans a plain list for every match step, written from the prompt text). Fuzz: 6,800 random event streams (up to 300 events) agree; all 30 hidden tests agree. "
         "Hidden tests: 22 hand-made scenarios, one per rule interaction (iceberg refresh behind same-price orders, repeated refresh, incoming iceberg trading its full size, partial display not refilled early, stop firing after its own submission, cascade with re-check, a stop that stops qualifying, no mid-sweep checks, amend keep/lose priority, crossing amend as aggressor, ignored amends, displayed-only book totals), 6 random streams and 2 large streams (1,500-2,000 events). "
         "Timing: reference max 10 ms vs 3000 ms. Naive: refreshed iceberg keeps its place 21/30, all qualifying stops fired in one batch 20/30, stops armed mid-sweep 24/30, incoming iceberg trades only its peak 21/30."),
}
TRAILER = ("Respond with a single ```javascript code block containing the complete function (plain JavaScript, no imports, no TypeScript). "
           "The function must have exactly the name and parameters given above; you may define helper functions inside the same code block. "
           "Do not read input or print output: the function is called directly with the arguments and its return value is checked against hidden tests, "
           "including large inputs with a time limit of about {sec} seconds per test. Give exactly one solution: if your answer contains several code blocks, only the last one is run.")

def js(v): return json.dumps(v, ensure_ascii=False)
def fmt_examples(fn, examples):
    return '```javascript\n' + '\n'.join(f"{fn}({', '.join(js(a) for a in args)})  // returns {js(exp)}" for args, exp in examples) + '\n```'

EX = json.load(open(os.path.join(HERE, 'examples.json')))
cases = []
for cid, meta in CASES.items():
    folder = os.path.join(HERE, cid)
    tpath = os.path.join(folder, 'tests.json')
    if not os.path.exists(tpath):
        print('generating', cid, '...'); subprocess.run(['node', os.path.join(folder, 'gen.mjs')], check=True, cwd=HERE, capture_output=True)
    spec = json.load(open(tpath))
    fn = EX[cid]['fn']; examples = EX[cid]['examples']
    assert spec['functionName'] == fn
    # re-verify examples + hidden tests with the reference (in-process VM) and examples with the independent oracle
    chk = subprocess.run(['node', os.path.join(HERE, 'check_ref.mjs'), cid], capture_output=True, text=True, cwd=HERE)
    assert chk.returncode == 0, chk.stdout + chk.stderr
    print(chk.stdout.strip())
    ofile = os.path.join(folder, meta['oracle'][0])
    res = json.loads(subprocess.run(['python3', ofile], input=json.dumps([a for a, _ in examples]), capture_output=True, text=True, check=True).stdout)
    assert res == [e for _, e in examples], (cid, res)
    prompt = open(os.path.join(folder, 'prompt.md')).read().strip()
    prompt = prompt.replace('{examples}', fmt_examples(fn, examples)).replace('{trailer}', TRAILER.format(sec=meta['timeout'] // 1000))
    assert '{' + 'examples}' not in prompt and '{' + 'trailer}' not in prompt
    n = len(spec['tests'])
    assert 20 <= n <= 40, (cid, n)
    cases.append({'id': cid.replace('c', 'f'), 'prompt': prompt, 'expected': spec,
                  'scorer': {'type': 'code-js', 'timeoutMs': meta['timeout']},
                  'notes': f"[{meta['d']}] " + meta['notes'] + f" Hidden tests: {n}. Scripts: verification/frontier-coding/{cid}/ (gen.mjs regenerates tests.json; fuzz.mjs re-runs the cross-check). Every prompt example was re-checked against the reference and the independent oracle."})

obj = {
  'kind': 'prompt', 'id': 'coding.frontier', 'version': '1.0.0', 'name': 'Frontier Engineering',
  'category': 'coding',
  'description': ('Six frontier-level implementation tasks with complete specifications and hidden tests that include large inputs: '
                  'a register-machine assembler and interpreter with exact 32-bit flag semantics, exact-arithmetic area covered by at least k '
                  'octilinear polygons with coordinates up to 1e9, palindrome statistics over 200,000-character strings, a minimum-cost truck fleet '
                  '(min-cost path cover with a fleet cap), minimum-cost radio towers on a tree with per-vertex reach, and an order-book '
                  'matching engine with icebergs, stops and amendments. Each needs the right algorithm AND every rule right; plausible '
                  'shortcuts lose a large share of the tests.'),
  'difficulty': 'extreme',
  'tags': ['coding', 'javascript', 'algorithms', 'exact-arithmetic', 'simulation', 'hidden-tests', 'frontier'],
  'hook': 'Six problems where the obvious solution is too slow or subtly wrong.',
  'maxOutputTokens': 32000,
  'estimate': {'inputTokens': int(sum(tok(c['prompt']) for c in cases) / len(cases)) + 30, 'outputTokens': 20000},
  'author': 'Gauntlet Core', 'createdAt': '2026-09-24',
  'scorer': {'type': 'code-js', 'timeoutMs': 6000},
  'cases': cases,
}
# compact serialisation of the hidden tests (same approach as verification/common.py)
placeholders = {}
for i, c in enumerate(obj['cases']):
    key = f'@@COMPACT_{i}@@'; placeholders[key] = json.dumps(c['expected'], ensure_ascii=False, separators=(',', ':')); c['expected'] = key
text = json.dumps(obj, indent=2, ensure_ascii=False)
for key, compact in placeholders.items(): text = text.replace(json.dumps(key), compact, 1)
json.loads(text)
out = os.path.join(ROOT, 'tests', 'coding', 'frontier.json')
open(out, 'w', encoding='utf-8').write(text + '\n')
print('wrote', out, f'{len(text) / 1024:.0f} KB', 'estimate', obj['estimate'])
