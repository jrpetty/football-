import sys
sys.path.insert(0, '..')
from common import write_test
from build_common import build_cases, mean_tokens
from prompts_hard import HARD
from prompts_alg import TRAILER
NOTES = {
 'h1': 'Iterative shunting-yard reference; hidden tests + 50,000 fuzzed expressions re-checked by an independent Python evaluator built on the ast module with JS remainder semantics (only 4 fuzz cases skipped where float remainder of an inexact pow/division is chaotic; hidden tests avoid that). Hidden tests include precedence/associativity traps (-2^2, 2^-2, 2^3^2, 2^-1^-1), invalid syntax, non-finite results, 20,000-deep parentheses, a 20,001-long unary chain and a 5,000-term expression (recursive parsers overflow the stack).',
 'h2': 'Thompson-NFA reference; hidden tests + 20,000 fuzzed pattern/text pairs re-checked with Python re.fullmatch after a careful syntax translation; the pathological tests (which make backtracking engines exponential) are checked against their obvious closed-form answers. True/false outcomes are balanced (18/17).',
 'h3': 'Cycle-arithmetic reference (360-year cycle = 131,457 days); re-checked by a Python day-by-day stepper for offsets up to 3,000,000 and by an independent Python cycle-prefix implementation for all tests incl. 10^12 offsets. Traps: leap rule (90, 180 not leap; 360, 720 leap), Leap Day and Year Day positions, negative offsets across year boundaries, very large years.',
 'h4': 'Map+heap reference; hidden tests and 3,000 fuzzed event lists re-checked by a naive Python list simulation. Traps: expiry is inclusive at t+ttl, get does not refresh TTL, put refreshes TTL and recency, expired entries free capacity before eviction, capacity 1, same-time events.',
 'h5': 'Tarjan-SCC + Kahn topological reference (fully iterative); hidden tests and 3,000 fuzzed sheets re-checked by an independent recursive Python evaluator with reachability-based cycle detection. Traps: self-reference through a SUM rectangle, error priority #CYCLE! > #REF! > #DIV/0!, propagation through SUM, reversed range corners, empty ranges, unary minus chains, and a 3,000-cell dependency chain (naive recursion overflows the stack).',
}
cases = build_cases('hard', HARD, TRAILER, NOTES)
meta = dict(
    id='coding.hard', category='coding', name='Hard Mode Engineering',
    description='Five genuinely hard implementation tasks with exhaustive specs: an expression evaluator with tricky precedence and 50,000-deep nesting, a regex engine that must survive catastrophic-backtracking patterns, date arithmetic in an invented calendar with 10^12-day offsets, an LRU cache with TTL semantics, and a spreadsheet engine with cycles and error propagation. Only precise, robust implementations pass the hidden tests, which separates the very top models from each other.',
    difficulty='extreme', tags=['coding', 'javascript', 'parsing', 'simulation', 'hidden-tests'],
    hook='Build a regex engine, a spreadsheet and a calendar - in one shot each.',
    maxOutputTokens=32000,
    estimate={'inputTokens': mean_tokens(cases), 'outputTokens': 16000},
    scorer={'type': 'code-js', 'timeoutMs': 2000},
)
print(write_test(meta, cases))
