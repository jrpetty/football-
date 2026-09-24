import sys, json
sys.path.insert(0, '..')
from common import write_test
from build_common import build_cases, mean_tokens
from prompts_alg import ALG, TRAILER
NOTES = {
 'a1': 'Sweep-line reference cross-checked against a per-unit-cell brute force on 3,000 random small inputs. Hidden tests: empty input, touching equal/unequal weights (merge rule), nesting, duplicates, gaps, negative and 10^12 coordinates, 1,200 random intervals over a 2*10^12 range (defeats per-coordinate arrays) and 4,000 shuffled touching blocks that must collapse to one segment.',
 'a2': 'Layered-Dijkstra reference cross-checked against a Bellman-Ford state-space brute force on 3,000 random small graphs. Hidden tests: start==target, unreachable target, floor of odd costs, undirected roads, parallel edges and self-loops, coupons exceeding path length, cost-1 roads becoming free, and graphs with 2,000 nodes / 5,000 roads.',
 'a3': 'BigInt tree-navigation reference cross-checked against full expansion on 4,000 random small patterns x 6 indices. Hidden tests include decoded lengths around 3*10^27 with index near 2^53, 900-level nesting (length 3*2^900) and a 60k-character flat pattern.',
 'a4': 'Iterative DP reference cross-checked against exhaustive recursion on 4,000 random strings (<=14 digits). Hidden tests include 40,000-character strings (exponential recursion and deep recursion both fail), Fibonacci-like counts needing the modulus, and leading-zero traps.',
 'a5': 'Offline reverse union-find reference cross-checked against recompute-by-DFS brute force on 3,000 random small graphs. The brute force times out on the largest hidden test (5,000 nodes, 6,000 edges, 4,200 cuts), so an O(cuts x (n+m)) approach fails it.',
 'a6': 'Two-pointer reference cross-checked against O(n^2) brute force on 5,000 random small arrays; the brute force times out on the three hidden 25,000-element tests. Edge cases: empty array, maxDistinct 0, zeros with maxSum 0, element larger than maxSum.',
 'a7': 'Regex/Map reference; hidden tests independently re-checked by a separate Python implementation (coding/alg/a7/brute.py). Edge cases: empty text, k=0, apostrophes/hyphens/digits as separators, non-ASCII letters as separators, alphabetical tie-breaks, 16,000-word text.',
 'a8': 'Slab-sweep reference with BigInt accumulation cross-checked against unit-cell counting on 3,000 random small inputs. Hidden tests: touching/contained/identical rectangles, 10^9-length thin rectangles, an area just below 2^53, and 1,500-rectangle inputs (dense-grid marking fails).',
}
cases = build_cases('alg', ALG, TRAILER, NOTES)
meta = dict(
    id='coding.algorithms', category='coding', name='Algorithms Under Test',
    description='Medium-to-hard algorithmic functions (sweep lines, layered shortest paths, implicit-string indexing, DP counting, offline union-find, two pointers, rectangle union) executed against hidden unit tests that include edge cases and large inputs with a ~2 s limit. Correct-but-slow and almost-correct solutions both lose points, which separates models that reason about complexity and corner cases from those that pattern-match a textbook answer.',
    version='1.1.0', difficulty='hard', tags=['coding', 'javascript', 'algorithms', 'hidden-tests', 'performance'],
    hook='Hidden tests, huge inputs, two seconds. Does the code actually work?',
    maxOutputTokens=32000,
    estimate={'inputTokens': mean_tokens(cases), 'outputTokens': 10000},
    scorer={'type': 'code-js', 'timeoutMs': 2000},
)
print(write_test(meta, cases))
