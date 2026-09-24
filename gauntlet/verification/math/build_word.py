import sys, json, runpy
from decimal import Decimal as D, ROUND_HALF_UP
sys.path.insert(0, '..')
from common import write_test, tok, ONE
W = runpy.run_path('word.py')['cases']
# answer precision per case: number of decimals the prompt asks for (None = integer / exact string)
DEC = {'w01': 2, 'w02': 0, 'w03': 2, 'w04': 2, 'w05': 2, 'w07': 2, 'w08': 2, 'w09': 2, 'w10': 0, 'w11': 2, 'w13': 2, 'w14': 0, 'w15': 2, 'w16': 2, 'w17': 2}
out = []
for c in W:
    cid = c['id']
    prompt = c['prompt'].rstrip() + ' ' + ONE
    case = {'id': cid, 'prompt': prompt}
    if c.get('exact'):
        case['expected'] = c['expected']
        case['scorer'] = {'type': 'exact', 'normalize': 'alnum'}
        shown = c['expected'][0]
    else:
        q = DEC[cid]
        val = D(repr(c['expected'])).quantize(D(1) if q == 0 else D('0.' + '0' * (q - 1) + '1'), rounding=ROUND_HALF_UP)
        case['expected'] = int(val) if q == 0 else float(val)
        shown = str(val)
    case['notes'] = f"[{c['difficulty']}] Expected {shown}. " + c['notes'] + " Computed with exact Decimal/Fraction arithmetic in math/word.py."
    out.append(case)
    print(cid, c['difficulty'], case['expected'])
meta = dict(
    id='math.word-problems', category='math', name='Real-World Word Problems',
    description='Multi-step quantitative problems from everyday settings (billing tiers, payroll, FIFO inventory, savings with monthly rounding, time zones, mixtures, relative motion, checkout rules) with explicit rounding rules and deliberate distractors. They reward careful reading and exact bookkeeping, and punish models that grab the wrong number or round at the wrong step.',
    difficulty='medium', tags=['math', 'word-problems', 'arithmetic', 'distractors', 'rounding'],
    hook='Real receipts, real payslips, real traps.',
    maxOutputTokens=16000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)) + 40, 'outputTokens': 4000},
    scorer={'type': 'number', 'tolerance': 0.001},
)
print(write_test(meta, out))
