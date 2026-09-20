# With the stock take dated before the receipt, every line the roll sold must come
# off the cellar. The expected figures are worked out here from her own numbers —
# the counts off her sheet, the quantities off the printed roll — and compared with
# what a third-party Excel engine makes of the workbook.
import sys, shutil, tempfile, os, warnings, logging
from datetime import date
warnings.filterwarnings('ignore'); logging.disable(logging.WARNING)
from openpyxl import load_workbook
import formulas

src = sys.argv[1]
tmp = os.path.join(tempfile.mkdtemp(), 'joined.xlsx')
shutil.copy(src, tmp)
wb = load_workbook(tmp)                 # formulas, not values
wb['Cellar']['B3'] = date(2026, 8, 1)   # before the receipt
wb.calculation.fullCalcOnLoad = True
wb.save(tmp)

sol = formulas.ExcelModel().loads(tmp).finish().calculate()
index = {}
for k in sol:
    if "'!" in k:
        bs, ref = k.rsplit("'!", 1)
        index[(bs.rsplit(']', 1)[-1].lower(), ref.upper())] = k


def val(sheet, ref):
    k = index.get((sheet.lower(), ref.upper()))
    v = sol[k] if k else None
    try:
        v = v.value[0, 0]
    except Exception:
        pass
    if hasattr(v, 'item'):
        try:
            v = v.item()
        except Exception:
            pass
    return v


PINT, HALF = 1, 0.5
# row: (line, what she counted, what the roll says went)
want = {
    5:  ('Taddy Lager', 1056,   120 * PINT + 19 * HALF),
    6:  ('Alpine',      302.4,  66 * PINT + 26 * HALF),
    7:  ('Stout',       218.24, 24 * PINT),
    8:  ('Cider',       171.03, 28 * PINT + 6 * HALF),
    9:  ('Dark Mild',   67.86,  5 * PINT),
    10: ('Rose',        18750,  6 * 175 + 3 * 250),
    11: ('Red wine',    11125,  0),
    12: ('White wine',  55125,  8 * 125 + 7 * 175 + 11 * 250),
    17: ('Pear',        85,     0),
    20: ('Pure Brew (bottled)', 30, 1),
    21: ('alc free',    29,     1),
    22: ('Orange Juice', 53,    1),
    23: ('Apple Juice', 56,     7),
    25: ('Elderflower', 39,     5),
    27: ('Tonic',       41,     3),
    28: ('Ginger Beer', 58,     6),
    29: ('Crisps',      525,    79),
    30: ('Salted Nuts', 68,     3),
    31: ('Dry Roast',   58,     4),
}

fails = []
for row, (name, counted, gone) in sorted(want.items()):
    shown = val('Cellar', f'A{row}')
    sold, left = val('Cellar', f'E{row}'), val('Cellar', f'F{row}')
    ok = (shown == name
          and isinstance(sold, (int, float)) and abs(sold - gone) < 0.005
          and isinstance(left, (int, float)) and abs(left - (counted - gone)) < 0.005)
    print(('ok   ' if ok else 'FAIL ') +
          f'{name:<20} {counted:>8} counted - {sold!r:>8} sold = {left!r:>9} left'
          + ('' if ok else f'   want sold {gone}, left {round(counted - gone, 2)}'))
    if not ok:
        fails.append(name)

print()
print('FAILED' if fails else 'every line came off the cellar')
sys.exit(1 if fails else 0)
