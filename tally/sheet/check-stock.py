# Works the sheet out with `formulas` — a third-party implementation of Excel's
# semantics — and checks it against figures worked out here from her receipts.
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

# Row 5 is Taddy Lager: 1,056 pints counted, 123 pints + 20 halves sold.
eq('Taddy line      A5', val('A5'), 'Taddy Lager')
eq('Taddy counted   C5', val('C5'), 1056)
eq('Taddy sold      D5', val('D5'), 133)
eq('Taddy left      E5', val('E5'), 1056 - 133)
eq('Taddy took      F5', val('F5'), 532.00)

# Row 6 Alpine: 80 pints + 34 halves = 97.
eq('Alpine sold     D6', val('D6'), 97)
eq('Alpine left     E6', val('E6'), round(302.4 - 97, 2))

# Row 12 is White wine, counted in millilitres: 18x125 + 11x175 + 1x250.
eq('White wine      A12', val('A12'), 'White wine')
eq('White wine sold D12', val('D12'), 18 * 125 + 11 * 175 + 250)
eq('White wine left E12', val('E12'), 55125 - 4425)

# A line nothing was sold on must not move.
eq('Red wine sold   D11', val('D11'), 175)
eq('Cherry sold     D13', val('D13'), 0)
eq('Cherry left     E13', val('E13'), 46)

# The uncounted lines carry their trade but refuse to guess what is left.
eq('OBB is listed   A32', val('A32'), 'OBB (draught)')
eq('OBB sold        D32', val('D32'), 85 + 3 * 0.5)
eq('OBB left        E32', val('E32'), 'not counted')
eq('OBB took        F32', val('F32'), 311.40)

# And the whole night foots to the paper.
eq('taken, all lines', val('F41'), 2011.05)

print()
print('FAILED' if fails else 'all good')
sys.exit(1 if fails else 0)
