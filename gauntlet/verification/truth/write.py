import sys, json
sys.path.insert(0, '..')
from common import write_test, tok
cases = json.load(open('truth_cases.json'))
out = []
for c in cases:
    out.append({'id': c['id'], 'prompt': c['prompt'], 'expected': c['expected'], 'notes': f"[{c['difficulty']}] " + c['notes']})
meta = dict(
    id='reasoning.truth-tellers', category='reasoning', name='Knights, Knaves, Spies & Alternators',
    description='Freshly generated truth-teller puzzles with knights, knaves, spies and alternators, compound and self-referential statements, and statements about other statements. Every puzzle has exactly one consistent assignment (verified by enumeration), so a single slip in case analysis produces a wrong line-up; the hard tail (6-7 islanders, mixed types, meta-statements) separates careful exhaustive reasoners from pattern-matchers.',
    version='1.1.0', difficulty='hard', tags=['logic', 'deduction', 'knights-and-knaves', 'enumeration-verified'],
    hook='Knights never lie, knaves always do, and the spy is doing whatever it wants.',
    maxOutputTokens=32000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)) + 40, 'outputTokens': 11000},
    scorer={'type': 'exact', 'normalize': 'alnum'},
)
print(write_test(meta, out))
