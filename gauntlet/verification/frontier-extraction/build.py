# Builds tests/extraction/frontier.json from the case generators e1..e5 in this folder.
# Run from this folder: python3 build.py
import sys, os, json
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..'))
from common import write_test, tok
import e1, e2, e3, e4, e5

JSON_RULES = ("Output rules: respond with ONLY the JSON object (you may wrap it in a ```json code block), with exactly the keys listed, in any key order. "
              "Use JSON numbers (not strings) for numeric fields, with no currency symbols, units or thousands separators. Use null where the schema allows it and the value is not given. "
              "Use ISO dates YYYY-MM-DD. Where the document states a correction or a change, use the corrected / final value. Do not add explanations.")

cases = []
for i, m in enumerate([e1, e2, e3, e4, e5], 1):
    prompt = (f"{m.INSTRUCTIONS}\n\nDocument:\n<<<\n{m.DOC.strip()}\n>>>\n\nSchema:\n{m.SCHEMA.strip()}\n\n"
              f"{JSON_RULES} {m.EXTRA_RULES}")
    exp = m.expected()
    # expected must round-trip through JSON unchanged (no Decimals, no NaN)
    assert json.loads(json.dumps(exp)) == exp
    cases.append(dict(id=f'x{i:02d}', prompt=prompt, expected=exp, notes='[extreme] ' + m.NOTES))

meta = dict(
    id='extraction.frontier', category='extraction', name='Extraction: Frontier',
    description=('Five long, realistic documents in which almost every early fact is later corrected, retracted or superseded: a hotel offsite email thread '
                 '(withdrawals, UTC flight times, minimum covers, deposit and cancellation dates), a multi-currency freight invoice whose credit note the model must '
                 'recalculate itself, a subscription contract with two amendments and an unsigned side letter (CPI indexation, SLA credits, termination fee), an air-cargo '
                 'manifest with lb/in conversions, volumetric weight and weight-break pricing, and AGM minutes with entitlement-weighted votes and corrections that flip '
                 'three outcomes. Every schema is spelled out exactly and each leaf field is scored, so only a reader who tracks the whole document and does the '
                 'arithmetic scores well.'),
    version='1.0.0', difficulty='extreme',
    tags=['extraction', 'json', 'information-extraction', 'multi-document', 'arithmetic', 'dates', 'units', 'frontier'],
    hook='Five messy documents where every correction moves a number.',
    maxOutputTokens=32000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in cases) / len(cases)) + 20, 'outputTokens': 14000},
    scorer={'type': 'json', 'numberTolerance': 0.000001},
)
for c in cases:
    print(c['id'], len(c['prompt']), 'chars,', tok(c['prompt']), 'tokens est.')
print(write_test(meta, cases))
