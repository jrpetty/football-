import json
from datetime import date
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

CELLAR = json.load(open('cellar.json'))

A = 'Arial'
H = Font(name=A, size=11, bold=True, color='FFFFFF')
HEAD_FILL = PatternFill('solid', fgColor='4A5240')
TITLE = Font(name=A, size=14, bold=True)
BOLD = Font(name=A, size=11, bold=True)
BODY = Font(name=A, size=11)
NOTE = Font(name=A, size=10, italic=True, color='595959')
INPUT = Font(name=A, size=11, color='0000FF')
EXAMPLE = Font(name=A, size=11, italic=True, color='808080')
YELLOW = PatternFill('solid', fgColor='FFF2CC')
THIN = Side(style='thin', color='D9D9D9')
BOX = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)

MONEY = '£#,##0.00'
SIGNED = '£#,##0.00;[Red]-£#,##0.00;-'
NUM = '#,##0.##'
DATE = 'dd/mm/yyyy'

wb = Workbook()


def head(ws, row, labels, widths):
    for i, (label, width) in enumerate(zip(labels, widths), start=1):
        c = ws.cell(row=row, column=i, value=label)
        c.font = H
        c.fill = HEAD_FILL
        c.alignment = Alignment(horizontal='center', vertical='center', wrap_text=True)
        c.border = BOX
        ws.column_dimensions[get_column_letter(i)].width = width
    ws.row_dimensions[row].height = 30
    ws.freeze_panes = ws.cell(row=row + 1, column=1)


def title(ws, text, sub):
    ws['A1'] = text
    ws['A1'].font = TITLE
    ws['A2'] = sub
    ws['A2'].font = NOTE


def setting(ws, label, value, fmt, note):
    ws['A3'] = label
    ws['A3'].font = BOLD
    c = ws['B3']
    c.value = value
    c.font = INPUT
    c.fill = YELLOW
    c.number_format = fmt
    c.border = BOX
    ws['C3'] = note
    ws['C3'].font = NOTE


# ---------------------------------------------------------------- Read me ---
rm = wb.active
rm.title = 'Read me'
rm.column_dimensions['A'].width = 104
lines = [
    ('The Gardeners Arms — till and cellar', TITLE),
    ('', BODY),
    ('Type only in the shaded boxes. Everything else works itself out.', BOLD),
    ('', BODY),
    ('NIGHTS — one row per receipt.', BOLD),
    ('Put in the date, the Z number, and the figures off the receipt.', BODY),
    ('Two or three receipts in one night: give them each a row with the same date. The day', BODY),
    ('columns add them together and judge the night as a whole.', BODY),
    ('A receipt printed after midnight belongs to the night before, so date it the day the', BODY),
    ('night started.', BODY),
    ('Cash in drawer is everything in the till at the end. Float put back is what you leave in', BODY),
    ('for the morning, so it is not counted as takings. Leave it empty if you take the lot.', BODY),
    ('Until a card or a cash figure is in, the night reads "not counted yet" — never £0.', BODY),
    ('', BODY),
    ('SOLD — one row per line the receipt sold.', BOLD),
    ('Pick the cellar line from the drop down, then say how many went and what one takes.', BODY),
    ('A pint takes 1, a half takes 0.5, a large glass of wine takes 175, a small one 125,', BODY),
    ('a bottle off the shelf takes 1.', BODY),
    ('If the Matched column says NOT IN THE CELLAR, that line is coming off nothing — fix the', BODY),
    ('name or add the line to the Cellar sheet.', BODY),
    ('', BODY),
    ('CELLAR — your stock take of 16 September 2026 is already in it.', BOLD),
    ('It takes off whatever the Sold sheet says went, and adds anything you book in.', BODY),
    ('Count it again each week: type the new figures over the Counted column and move the', BODY),
    ('Counted on date to the day you counted. Only sales on or after that date come off.', BODY),
    ('A line you have not counted says "not counted" — it does not pretend to be nothing.', BODY),
    ('', BODY),
    ('About the example rows', BOLD),
    ('The grey italic rows are the real receipt of 23 August 2026. Delete them once you have', BODY),
    ('your own nights in, or leave them and start underneath.', BODY),
    ('They are dated before the stock take, so they take nothing off the cellar — that is the', BODY),
    ('sheet working properly. To watch it work, put the Counted on date back to 1 August and', BODY),
    ('the cellar will come down; then put it back to 16 September.', BODY),
    ('', BODY),
    ('The arithmetic', BOLD),
    ('A pint is treated as 568ml exactly, so two halves make one pint and a keg put in as 176', BODY),
    ('pints cancels dead against 176 pints of sales. Wine is counted in millilitres.', BODY),
    ('', BODY),
    ('Where the figures came from', BOLD),
    ('The cellar amounts are your own stock take of 16 September 2026, as you sent it.', BODY),
    ('The example night is your own till roll of 23 August 2026, Z number 1685.', BODY),
    ('Spirits, post mix, OBB and Pure Brew on draught were not on that stock take, so they are', BODY),
    ('not in the cellar here. Add them when you have counted them.', BODY),
    ('Alpine is entered at 2.1 kegs because the middle keg has no empty weight to work from.', BODY),
]
for i, (text, font) in enumerate(lines, start=1):
    rm.cell(row=i, column=1, value=text).font = font

# ----------------------------------------------------------------- Nights ---
ns = wb.create_sheet('Nights')
title(ns, 'Nights', 'One row per receipt. Two receipts in one night? Same date on both rows and the day columns add them.')
setting(ns, 'Allowed out by', 0.05, MONEY, 'Anything within this counts as balanced.')
head(ns, 4,
     ['Date', 'Z number', 'Till roll total', 'Card', 'Cash in drawer', 'Float put back',
      'Night — till', 'Night — card', 'Night — cash', 'Night — float',
      'Night — counted', 'Out by', 'Verdict'],
     [12, 10, 13, 12, 13, 13, 12, 12, 12, 12, 13, 12, 15])

FIRST, LAST = 5, 300
for r in range(FIRST, LAST + 1):
    for col in range(1, 7):
        c = ns.cell(row=r, column=col)
        c.fill = YELLOW
        c.font = INPUT
        c.border = BOX
    ns.cell(row=r, column=1).number_format = DATE
    ns.cell(row=r, column=2).number_format = '0'
    for col in (3, 4, 5, 6):
        ns.cell(row=r, column=col).number_format = MONEY

    d = f'$A{r}'
    span = f'$A${FIRST}:$A${LAST}'
    for col, src in ((7, 'C'), (8, 'D'), (9, 'E'), (10, 'F')):
        ns.cell(row=r, column=col,
                value=f'=IF({d}="","",SUMIF({span},{d},${src}${FIRST}:${src}${LAST}))')
    # "Has a figure actually been put in for this night?" — asked with ISNUMBER
    # rather than COUNTIFS(...,"<>"), which different spreadsheets read
    # differently. A night nobody has cashed up must read as not counted, never
    # as a balanced nothing.
    typed = (f'SUMPRODUCT(({span}={d})*'
             f'(ISNUMBER($D${FIRST}:$D${LAST})+ISNUMBER($E${FIRST}:$E${LAST})))')
    counted = f'=IF({d}="","",IF({typed}=0,"",H{r}+I{r}-J{r}))'
    ns.cell(row=r, column=11, value=counted)
    ns.cell(row=r, column=12, value=f'=IF(OR({d}="",K{r}=""),"",K{r}-G{r})')
    ns.cell(row=r, column=13,
            value=f'=IF({d}="","",IF(K{r}="","not counted yet",'
                  f'IF(ABS(L{r})<=$B$3,"Balanced",IF(L{r}<0,"Short","Over"))))')
    for col in range(7, 14):
        c = ns.cell(row=r, column=col)
        c.font = BODY
        c.border = BOX
        c.number_format = MONEY if col < 12 else (SIGNED if col == 12 else 'General')
    ns.cell(row=r, column=13).alignment = Alignment(horizontal='center')

# The real night of 23 August 2026, off her own roll.
for col, v in enumerate([date(2026, 8, 23), 1685, 2192.80, 1841.00, 351.80], start=1):
    ns.cell(row=FIRST, column=col, value=v).font = EXAMPLE

# ------------------------------------------------------------------- Sold ---
sd = wb.create_sheet('Sold')
title(sd, 'Sold', 'One row per line the receipt sold. What one sale takes: a pint 1, a half 0.5, a large glass 175, a bottle 1.')
head(sd, 4, ['Date', 'Cellar line', 'What the till called it', 'How many', 'Each takes',
             'Taken off', 'Matched'],
     [12, 24, 26, 11, 12, 12, 20])

SOLD_FIRST, SOLD_LAST = 5, 800
CELLAR_LAST = 4 + len(CELLAR)
dv = DataValidation(type='list', formula1=f'Cellar!$A$5:$A${CELLAR_LAST}', allow_blank=True)
sd.add_data_validation(dv)
dv.add(f'B{SOLD_FIRST}:B{SOLD_LAST}')

for r in range(SOLD_FIRST, SOLD_LAST + 1):
    for col in range(1, 6):
        c = sd.cell(row=r, column=col)
        c.fill = YELLOW
        c.font = INPUT
        c.border = BOX
    sd.cell(row=r, column=1).number_format = DATE
    sd.cell(row=r, column=4).number_format = NUM
    sd.cell(row=r, column=5).number_format = NUM
    c = sd.cell(row=r, column=6, value=f'=IF(OR($B{r}="",$D{r}=""),"",$D{r}*$E{r})')
    c.font = BODY
    c.border = BOX
    c.number_format = NUM
    m = sd.cell(row=r, column=7,
                value=f'=IF($B{r}="","",IF(COUNTIF(Cellar!$A${5}:$A${CELLAR_LAST},$B{r})=0,'
                      f'"NOT IN THE CELLAR","ok"))')
    m.font = BODY
    m.border = BOX
    m.alignment = Alignment(horizontal='center')

# The same night again, line by line, off the printed roll.
sold_example = [
    ('Taddy Lager', 'PINT TADDY LAGER', 120, 1),
    ('Taddy Lager', 'HALF TADDY LAGER', 19, 0.5),
    ('Alpine', 'PINT ALPINE', 66, 1),
    ('Alpine', 'HALF ALPINE', 26, 0.5),
    ('Cider', 'PINT CIDER', 28, 1),
    ('Cider', 'HALF CIDER', 6, 0.5),
    ('Stout', 'PINT STOUT', 24, 1),
    ('Dark Mild', 'PINT DARK MILD', 5, 1),
    ('White wine', '125ML HOUSE WINE', 8, 125),
    ('White wine', '175ML HOUSE WINE', 7, 175),
    ('White wine', '250ML HOUSE WINE', 11, 250),
    ('Rose', '175ML ROSE', 6, 175),
    ('Rose', '250ML ROSE', 3, 250),
    ('Crisps', 'CRISPS', 79, 1),
    ('Salted Nuts', 'SALTED NUTS', 3, 1),
    ('Dry Roast', 'DRY ROAST', 4, 1),
    ('Ginger Beer', 'GINGER BEER', 6, 1),
    ('Elderflower', 'ELDERFLOWER', 5, 1),
    ('Tonic', 'TONIC', 3, 1),
    ('Orange Juice', 'ORANGE JUICE', 1, 1),
    ('Apple Juice', 'APPLE JUICE', 7, 1),
    ('alc free', '550ml alc free', 1, 1),
    ('Pure Brew (bottled)', 'Bot pure brew', 1, 1),
]
for i, row in enumerate(sold_example):
    r = SOLD_FIRST + i
    for col, v in enumerate((date(2026, 8, 23),) + row, start=1):
        sd.cell(row=r, column=col, value=v).font = EXAMPLE

# ----------------------------------------------------------------- Cellar ---
cs = wb.create_sheet('Cellar')
title(cs, 'Cellar', 'Counted on 16 September 2026, from your own stock take. It takes off whatever the Sold sheet says went.')
setting(cs, 'Counted on', date(2026, 9, 16), DATE,
        'Only what the Sold sheet dates on or after this day comes off.')
head(cs, 4, ['Line', 'Counted in', 'Counted', 'Booked in since', 'Sold since', 'Left now',
             'A full container is'],
     [22, 12, 12, 15, 12, 13, 22])

for i, row in enumerate(CELLAR):
    r = 5 + i
    cs.cell(row=r, column=1, value=row['name']).font = BODY
    cs.cell(row=r, column=2, value=row['unit']).font = BODY
    for col in (3, 4):
        c = cs.cell(row=r, column=col)
        c.font = INPUT
        c.fill = YELLOW
        c.number_format = NUM
    cs.cell(row=r, column=3).value = round(row['counted'], 2)
    s = cs.cell(row=r, column=5,
                value=f'=SUMIFS(Sold!$F${SOLD_FIRST}:$F${SOLD_LAST},'
                      f'Sold!$B${SOLD_FIRST}:$B${SOLD_LAST},$A{r},'
                      f'Sold!$A${SOLD_FIRST}:$A${SOLD_LAST},">="&$B$3)')
    s.font = BODY
    s.number_format = NUM
    left = cs.cell(row=r, column=6,
                   value=f'=IF($C{r}="","not counted",$C{r}+IF($D{r}="",0,$D{r})-$E{r})')
    left.font = BOLD
    left.number_format = NUM
    cs.cell(row=r, column=7, value=row['container'] or '—').font = BODY
    for col in range(1, 8):
        cs.cell(row=r, column=col).border = BOX

n = CELLAR_LAST + 2
cs.cell(row=n, column=1, value='Lines on the Sold sheet matching nothing here').font = BOLD
orphan = cs.cell(row=n, column=3,
                 value=f'=COUNTIF(Sold!$G${SOLD_FIRST}:$G${SOLD_LAST},"NOT IN THE CELLAR")')
orphan.font = BOLD
orphan.number_format = '0'
orphan.border = BOX
cs.cell(row=n, column=4, value='Anything above zero is stock going out with nothing coming off.').font = NOTE

for j, text in enumerate([
    'Source: your own stock take of 16 September 2026.',
    'Alpine is 2.1 kegs: the middle keg has no empty weight, so it was measured by eye.',
    'Crisps are the six flavours added together, because the till sells them on one button.',
    'Spirits, post mix, OBB and Pure Brew on draught were not counted, so they are not here.',
    'A pint is 568ml, so two halves make one pint exactly. Wine is counted in millilitres.',
], start=n + 2):
    cs.cell(row=j, column=1, value=text).font = NOTE

# Excel and Sheets work every formula out the moment the file is opened, so
# the sheet is right even though openpyxl writes no answers of its own.
wb.calculation.fullCalcOnLoad = True

wb.save('Gardeners Arms.xlsx')
print('written')
