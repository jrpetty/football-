# Works the book out with `formulas` — Excel's semantics, implemented by someone
# else — and checks it against figures worked out here from her receipts.
import sys, warnings, logging
warnings.filterwarnings('ignore'); logging.disable(logging.WARNING)
import formulas

sol = formulas.ExcelModel().loads(sys.argv[1]).finish().calculate()
index = {}
for k in sol:
    if "'!" in k:
        bs, ref = k.rsplit("'!", 1)
        index[(bs.rsplit(']', 1)[-1].lower(), ref.upper())] = k

def val(ref, sheet='Stock'):
    k = index.get((sheet.lower(), ref.upper()))
    v = sol[k] if k else '<<missing>>'
    try: v = v.value[0, 0]
    except Exception: pass
    if hasattr(v, 'item'):
        try: v = v.item()
        except Exception: pass
    return v

fails = []
def eq(what, got, want, tol=0.005):
    ok = (isinstance(got, str) and got == want) if isinstance(want, str) else (
        isinstance(got, (int, float)) and abs(got - want) <= tol)
    print(('ok   ' if ok else 'FAIL ') + f'{what}: {got!r}' + ('' if ok else f'   want {want!r}'))
    if not ok: fails.append(what)

# Taddy: 1,056 pints counted; 123 pints and 20 halves sold on the 18th.
eq('Taddy line       A5', val('A5'), 'Taddy Lager')
eq('Taddy stock take C5', val('C5'), 1056)
eq('Taddy sold       D5', val('D5'), 133)
eq('Taddy left       E5', val('E5'), 1056 - 133)
eq('Taddy money      F5', val('F5'), 532.00)

eq('Alpine sold      D6', val('D6'), 80 + 34 * 0.5)
eq('Alpine left      E6', val('E6'), round(302.4 - 97, 2))

# White wine is counted in millilitres: 18 small, 11 large, one big.
eq('White wine sold D12', val('D12'), 18 * 125 + 11 * 175 + 250)
eq('White wine left E12', val('E12'), 55125 - 4425)

# A line the receipts never mention does not move.
eq('Cherry sold     D13', val('D13'), 0)
eq('Cherry left     E13', val('E13'), 46)

# A line the till sells but the stock take does not count carries its trade
# and still refuses to say what is left.
eq('OBB sold        D32', val('D32'), 86.5)
eq('OBB left        E32', val('E32'), 'not counted')
eq('OBB money       F32', val('F32'), 311.40)

# And the whole night foots to the paper.
eq('taken, all lines', val('F41'), 2011.05)

# The Nights tab does its own multiplying.
eq('a half takes a half  Nights!F6', val('F6', 'Nights'), 85 * 1)

print()
print('FAILED' if fails else 'all good')
sys.exit(1 if fails else 0)
