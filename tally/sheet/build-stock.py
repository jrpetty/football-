# One sheet: the stock lines, a box to count into, and what the till sold beside
# it. The sold figures are the real night of 18 September 2026 — both cash-ups
# added — worked out here from the receipt's own PLU lines rather than typed by
# hand, and converted into the units she counts in.
import json
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter

night = json.load(open('night.json'))
cellar = json.load(open('cellar.json'))

# Which till button comes off which line, and what one sale takes in that
# line's own units. Only the ones that are certain: a wrong match here would
# quietly take stock off the wrong barrel, which is worse than not matching.
TAKES = {
    'P00014': ('Taddy Lager', 1),     'P00021': ('Taddy Lager', 0.5),
    'P00013': ('Alpine', 1),          'P00020': ('Alpine', 0.5),
    'P00017': ('Stout', 1),           'P00024': ('Stout', 0.5),
    'P00002': ('Cider', 1),           'P00007': ('Cider', 0.5),
    'P00001': ('Dark Mild', 1),       'P00006': ('Dark Mild', 0.5),
    'P00029': ('White wine', 125),    'P00030': ('White wine', 175),
    'P00031': ('White wine', 250),
    'P00026': ('Red wine', 175),      'P00038': ('Rose', 250),
    'P00074': ('Crisps', 1),          'P00076': ('Dry Roast', 1),
    'P00054': ('Elderflower', 1),     'P00060': ('Orange Juice', 1),
    'P00059': ('alc free', 1),        'P00064': ('Pear', 1),
    # The till sells these; the stock take does not count them yet. They get a
    # line of their own rather than being pushed onto something that looks close.
    'P00011': ('OBB (draught)', 1),   'P00018': ('OBB (draught)', 0.5),
    'P00016': ('Pure Brew (draught)', 1), 'P00023': ('Pure Brew (draught)', 0.5),
    'P00069': ('Post mix (half)', 1), 'P00070': ('Dash', 1),
    'P00041': ('Vodka', 1),           'P00039': ('Whisky', 1),
    'P00042': ('Dark rum', 1),        'P00052': ('Tequila', 1),
    'P00080': ('Open food', 1),
}

UNCOUNTED = ['OBB (draught)', 'Pure Brew (draught)', 'Post mix (half)', 'Dash',
             'Vodka', 'Whisky', 'Dark rum', 'Tequila', 'Open food']
UNIT = {'pint': 'pints', 'ml': 'ml', 'bottle': 'bottles', 'unit': 'each'}

sold, took = {}, {}
missing = []
for it in night['items']:
    hit = TAKES.get(it['code'])
    if not hit:
        missing.append(it['name'])
        continue
    line, each = hit
    sold[line] = sold.get(line, 0) + it['qty'] * each
    took[line] = took.get(line, 0) + it['pence']
assert not missing, missing
assert sum(took.values()) == night['totalPence'], (sum(took.values()), night['totalPence'])

rows = [(r['name'], UNIT[r['unit']], round(r['counted'], 2), r['container'] or '') for r in cellar]
rows += [(name, 'not counted yet', None, '') for name in UNCOUNTED]

# ---------------------------------------------------------------- the sheet ---
A = 'Arial'
wb = Workbook()
ws = wb.active
ws.title = 'Stock'

HEAD = Font(name=A, size=11, bold=True, color='FFFFFF')
HEAD_FILL = PatternFill('solid', fgColor='4A5240')
TITLE = Font(name=A, size=15, bold=True)
BODY = Font(name=A, size=11)
BOLD = Font(name=A, size=11, bold=True)
NOTE = Font(name=A, size=10, italic=True, color='595959')
INPUT = Font(name=A, size=11, color='0000FF')
YELLOW = PatternFill('solid', fgColor='FFF2CC')
THIN = Side(style='thin', color='D9D9D9')
BOX = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
MONEY = '£#,##0.00'
NUM = '#,##0.##'

ws['A1'] = 'The Gardeners Arms — stock'
ws['A1'].font = TITLE
ws['A2'] = 'Count into the shaded column. Everything else is already worked out.'
ws['A2'].font = NOTE

HEADERS = ['Line', 'Counted in', 'Counted', 'Sold', 'Left', 'Money it took', 'A full one is']
WIDTHS = [24, 14, 12, 12, 13, 14, 22]
for i, (label, width) in enumerate(zip(HEADERS, WIDTHS), start=1):
    c = ws.cell(row=4, column=i, value=label)
    c.font = HEAD
    c.fill = HEAD_FILL
    c.alignment = Alignment(horizontal='center', vertical='center', wrap_text=True)
    c.border = BOX
    ws.column_dimensions[get_column_letter(i)].width = width
ws.row_dimensions[4].height = 28
ws.freeze_panes = 'A5'

FIRST = 5
for i, (name, unit, counted, container) in enumerate(rows):
    r = FIRST + i
    ws.cell(row=r, column=1, value=name).font = BODY
    ws.cell(row=r, column=2, value=unit).font = BODY
    c = ws.cell(row=r, column=3, value=counted)
    c.font = INPUT
    c.fill = YELLOW
    c.number_format = NUM
    s = ws.cell(row=r, column=4, value=round(sold.get(name, 0), 2))
    s.font = INPUT
    s.fill = YELLOW
    s.number_format = NUM
    left = ws.cell(row=r, column=5, value=f'=IF($C{r}="","not counted",$C{r}-$D{r})')
    left.font = BOLD
    left.number_format = NUM
    m = ws.cell(row=r, column=6, value=round(took.get(name, 0) / 100, 2))
    m.font = INPUT
    m.fill = YELLOW
    m.number_format = MONEY
    ws.cell(row=r, column=7, value=container or '—').font = BODY
    for col in range(1, 8):
        ws.cell(row=r, column=col).border = BOX

LAST = FIRST + len(rows) - 1
t = LAST + 1
ws.cell(row=t, column=1, value='Taken, all lines').font = BOLD
tot = ws.cell(row=t, column=6, value=f'=SUM($F${FIRST}:$F${LAST})')
tot.font = BOLD
tot.number_format = MONEY
for col in range(1, 8):
    ws.cell(row=t, column=col).border = BOX
    ws.cell(row=t, column=col).fill = PatternFill('solid', fgColor='EFEDE6')

for j, text in enumerate([
    'Counted is your stock take of 16 September 2026. Type this week’s over the top of it.',
    'Sold and Money are the two cash-ups of 18 September added together — £1,174.75 at 16:57 and £836.30 at 22:46.',
    'Pints count halves as a half. Wine is in millilitres: a large glass is 175.',
    'A line you leave empty says "not counted", which is not the same as none.',
    'The bottom nine lines are things the till sells that the stock take does not count yet.',
], start=t + 2):
    ws.cell(row=j, column=1, value=text).font = NOTE

wb.calculation.fullCalcOnLoad = True
wb.save('Gardeners Arms stock.xlsx')
print(f'written: {len(rows)} lines, took £{sum(took.values())/100:,.2f}')
