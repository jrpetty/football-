import sys, os, json
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from common import write_test, tok
here = os.path.dirname(os.path.abspath(__file__))
cases = json.load(open(os.path.join(here, 'cases.json')))
out = [{'id': c['id'], 'prompt': c['prompt'], 'expected': c['expected'], 'notes': c['notes']} for c in cases]
meta = dict(
    id='reasoning.deduction-grid-extreme', category='reasoning', name='Deduction Grid: Extreme',
    description='Seven or eight people by five or six attributes, pinned down by 32-55 minimal clues of every kind: conditionals, exactly-k-of-3 statements, either/or with overlapping domains, ordering chains, betweenness, negative positions and age arithmetic. The model must return two complete attribute orderings, so only a fully solved grid scores; it is built to separate the strongest reasoning models from each other.',
    version='1.0.0', difficulty='extreme', tags=['logic', 'deduction', 'zebra-puzzle', 'constraint-satisfaction', 'sat-verified', 'extreme'],
    hook='Eight people, six attributes, forty clues, zero slack.',
    maxOutputTokens=32000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)) + 60, 'outputTokens': 28000},
    scorer={'type': 'exact', 'normalize': 'alnum'},
)
print(write_test(meta, out))
