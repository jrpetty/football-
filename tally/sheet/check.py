# Works the whole workbook out with `formulas` — a third-party implementation of
# Excel's semantics. Nothing here is borrowed from the script that wrote the file,
# and every figure expected below is worked out from her stock take and her roll.
import sys, warnings, logging
warnings.filterwarnings('ignore'); logging.disable(logging.WARNING)
import formulas

path = sys.argv[1]
sol = formulas.ExcelModel().loads(path).finish().calculate()

index = {}
for k in sol:
    if "'!" in k:
        book_sheet, ref = k.rsplit("'!", 1)
        sheet = book_sheet.rsplit(']', 1)[-1]
        index[(sheet.lower(), ref.upper())] = k


def val(sheet, ref):
    k = index.get((sheet.lower(), ref.upper()))
    if k is None:
        return '<<missing>>'
    v = sol[k]
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


fails = []


def eq(what, got, want, tol=0.005):
    if isinstance(want, str):
        ok = isinstance(got, str) and got == want
    else:
        ok = isinstance(got, (int, float)) and abs(got - want) <= tol
    print(('ok   ' if ok else 'FAIL ') + f'{what}: {got!r}' + ('' if ok else f'   want {want!r}'))
    if not ok:
        fails.append(what)


print('--- the night of 23 August 2026, off her own roll ---')
till, card, cash = 2192.80, 1841.00, 351.80
eq('night total      Nights!G5', val('Nights', 'G5'), till)
eq('card             Nights!H5', val('Nights', 'H5'), card)
eq('cash             Nights!I5', val('Nights', 'I5'), cash)
eq('float            Nights!J5', val('Nights', 'J5'), 0)
eq('counted          Nights!K5', val('Nights', 'K5'), card + cash)
eq('out by           Nights!L5', val('Nights', 'L5'), card + cash - till)
eq('verdict          Nights!M5', val('Nights', 'M5'), 'Balanced')
print('--- a night nobody has typed anything into must not read as balanced ---')
eq('empty row        Nights!K6', val('Nights', 'K6'), '')
eq('empty row        Nights!L6', val('Nights', 'L6'), '')
eq('empty row        Nights!M6', val('Nights', 'M6'), '')

print()
print('--- the roll, line by line ---')
eq('120 pints Taddy    Sold!F5', val('Sold', 'F5'), 120)
eq('19 halves Taddy    Sold!F6', val('Sold', 'F6'), 9.5)
eq('11 x 250ml wine   Sold!F15', val('Sold', 'F15'), 2750)
eq('name known         Sold!G5', val('Sold', 'G5'), 'ok')
eq('empty row         Sold!F30', val('Sold', 'F30'), '')
eq('empty row         Sold!G30', val('Sold', 'G30'), '')

print()
print('--- the cellar: the example night is before the stock take, so nothing comes off ---')
eq('Taddy sold       Cellar!E5', val('Cellar', 'E5'), 0)
eq('Taddy left       Cellar!F5', val('Cellar', 'F5'), 1056)
eq('Alpine left      Cellar!F6', val('Cellar', 'F6'), 302.4)
eq('White wine left Cellar!F12', val('Cellar', 'F12'), 55125)
eq('Crisps left     Cellar!F29', val('Cellar', 'F29'), 525)
eq('orphan lines    Cellar!C33', val('Cellar', 'C33'), 0)

print()
print('FAILED' if fails else 'all good')
sys.exit(1 if fails else 0)
