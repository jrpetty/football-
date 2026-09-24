import sys, os, json
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from common import write_test, tok
here = os.path.dirname(os.path.abspath(__file__))
cases = json.load(open(os.path.join(here, 'cases.json')))
out = [{'id': c['id'], 'prompt': c['prompt'], 'expected': c['expected'], 'notes': c['notes']} for c in cases]
meta = dict(
    id='reasoning.truth-tellers-extreme', category='reasoning', name='Knights, Knaves, Spies & Alternators: Extreme',
    description='Eight to ten islanders mixing knights, knaves, spies and alternators under stated spy/alternator counts, with 12-23 minimal statements that include parity claims, counts, statements about other people\'s numbered statements, "exactly k of X\'s statements are true" and nested "X would say that ..." claims. Exactly one type assignment survives (exhaustive enumeration over up to 4^10 worlds), so a single slip anywhere in the case analysis fails the case.',
    version='1.0.0', difficulty='extreme', tags=['logic', 'deduction', 'knights-and-knaves', 'self-reference', 'enumeration-verified', 'extreme'],
    hook='Ten islanders, four kinds of liar, one consistent story.',
    maxOutputTokens=32000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)) + 60, 'outputTokens': 26000},
    scorer={'type': 'exact', 'normalize': 'alnum'},
)
print(write_test(meta, out))
