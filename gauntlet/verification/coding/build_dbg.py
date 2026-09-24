import sys
sys.path.insert(0, '..')
from common import write_test
from build_common import build_cases, mean_tokens
from prompts_dbg import DBG
from prompts_alg import TRAILER
NOTES = {
 'e1': 'Digit-array reference cross-checked against Python decimal.Decimal (precision 5000) on 22,000 random pairs plus all hidden tests. Traps: "-0" results, trailing/leading zeros, carries across 30+ digits, borrow across the decimal point, 900-digit operands.',
 'e2': 'Reference uses Array.from for code points; hidden tests re-checked by an independent Python implementation using the exact JS /\\s/ character set. A naive UTF-16 .slice().trimEnd() version fails 5/23 (surrogate splits, emoji, ZWJ).',
 'e3': 'Index-sort reference; hidden tests re-checked by a Python O(n^2) definition-based ranker. Traps: standard competition ranking (1,1,3), equal score but different time, stable order for full ties, duplicate names, negative/decimal values. A dense-ranking bug fails 5/13 of hidden tests.',
 'e4': 'State-machine reference cross-checked against an independent regex-grammar parser in Python on 20,000 fuzzed records plus all hidden tests. Traps: empty record, trailing comma, escaped quotes, newline inside quotes, junk or space after a closing quote, quote inside an unquoted field, unterminated quote.',
 'e5': 'Reference converts then re-encodes to the canonical numeral and compares; hidden tests re-checked by a Python regex+conversion implementation. The classic lenient converter fails 16/41 of the hidden tests.',
 'e6': 'Stack-based reference cross-checked against Python posixpath.normpath (adjusted only for the documented leading-"//" difference) on 20,000 fuzzed paths plus all hidden tests.',
}
cases = build_cases('dbg', DBG, TRAILER, NOTES)
meta = dict(
    id='coding.debug-and-edge-cases', category='coding', name='Edge-Case Minefield',
    description='Small functions whose specifications are full of traps: exact big-decimal arithmetic without number conversion, code-point-aware truncation with emoji and ZWJ, stable competition ranking, strict CSV parsing, canonical Roman numerals and path normalisation. Hidden tests hammer the corner cases, so the score reflects how carefully a model reads a spec rather than whether it knows the textbook version.',
    difficulty='medium', tags=['coding', 'javascript', 'edge-cases', 'unicode', 'hidden-tests'],
    hook='The happy path is easy. The hidden tests are not.',
    maxOutputTokens=32000,
    estimate={'inputTokens': mean_tokens(cases), 'outputTokens': 8000},
    scorer={'type': 'code-js', 'timeoutMs': 2000},
)
print(write_test(meta, cases))
