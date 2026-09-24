import sys, json
sys.path.insert(0, '..')
from common import write_test, tok
cases = json.load(open('grid_cases.json'))
out = [{'id': c['id'], 'prompt': c['prompt'], 'expected': c['expected'], 'notes': f"[{c['difficulty']}] " + c['notes']} for c in cases]
meta = dict(
    id='reasoning.deduction-grid', category='reasoning', name='Deduction Grid',
    description='Original zebra-style logic grids (4-6 positions x 3-4 attributes) on lines, round tables and 2-D grids, with numeric, disjunctive, conditional and either-or clues. Each clue set is minimal and has exactly one solution (verified by exhaustive search and by re-parsing the English), and the model must return a full ordering, so guessing and partial reasoning score zero.',
    version='1.1.0', difficulty='hard', tags=['logic', 'deduction', 'zebra-puzzle', 'constraint-satisfaction', 'exhaustively-verified'],
    hook='Nineteen clues, one arrangement. No partial credit.',
    maxOutputTokens=32000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)) + 40, 'outputTokens': 12000},
    scorer={'type': 'exact', 'normalize': 'alnum'},
)
print(write_test(meta, out))
