# The cellar, running off the receipts.
#
# Her stock take is the opening total. Every night's receipt lines go on the
# Nights tab, and the Stock tab takes them off. Add a night, the cellar moves.
import json
from datetime import date
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

night = json.load(open('night.json'))
cellar = json.load(open('cellar.json'))

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

missing = [i['name'] for i in night['items'] if i['code'] not in TAKES]
assert not missing, missing

lines = [(r['name'], UNIT[r['unit']], round(r['counted'], 2), r['container'] or '') for r in cellar]
lines += [(n, 'not counted yet', None, '') for n in UNCOUNTED]

A = 'Arial'
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
DATE = 'dd/mm/yyyy'

N_FIRST, N_LAST = 5, 600

wb = Workbook()

# ------------------------------------------------------------------ Stock ---
st = wb.active
st.title = 'Stock'
st['A1'] = 'The cellar'
st['A1'].font = TITLE
st['A2'] = 'Your stock take is the total. Every night on the Nights tab comes off it.'
st['A2'].font = NOTE

HEADERS = ['Line', 'Counted in', 'Stock take', 'Sold so far', 'Left', 'Money taken', 'A full one is']
WIDTHS = [22, 14, 12, 12, 13, 13, 22]
for i, (label, width) in enumerate(zip(HEADERS, WIDTHS), start=1):
    c = st.cell(row=4, column=i, value=label)
    c.font = HEAD; c.fill = HEAD_FILL; c.border = BOX
    c.alignment = Alignment(horizontal='center', vertical='center', wrap_text=True)
    st.column_dimensions[get_column_letter(i)].width = width
st.row_dimensions[4].height = 28
st.freeze_panes = 'A5'

S_FIRST = 5
for i, (name, unit, counted, container) in enumerate(lines):
    r = S_FIRST + i
    st.cell(row=r, column=1, value=name).font = BODY
    st.cell(row=r, column=2, value=unit).font = BODY
    c = st.cell(row=r, column=3, value=counted)
    c.font = INPUT; c.fill = YELLOW; c.number_format = NUM
    d = st.cell(row=r, column=4,
                value=f"=SUMIF(Nights!$B${N_FIRST}:$B${N_LAST},$A{r},Nights!$F${N_FIRST}:$F${N_LAST})")
    d.font = BODY; d.number_format = NUM
    e = st.cell(row=r, column=5, value=f'=IF($C{r}="","not counted",$C{r}-$D{r})')
    e.font = BOLD; e.number_format = NUM
    f = st.cell(row=r, column=6,
                value=f"=SUMIF(Nights!$B${N_FIRST}:$B${N_LAST},$A{r},Nights!$G${N_FIRST}:$G${N_LAST})")
    f.font = BODY; f.number_format = MONEY
    st.cell(row=r, column=7, value=container or '—').font = BODY
    for col in range(1, 8):
        st.cell(row=r, column=col).border = BOX

S_LAST = S_FIRST + len(lines) - 1
t = S_LAST + 1
st.cell(row=t, column=1, value='Taken, all lines').font = BOLD
tot = st.cell(row=t, column=6, value=f'=SUM($F${S_FIRST}:$F${S_LAST})')
tot.font = BOLD; tot.number_format = MONEY
for col in range(1, 8):
    st.cell(row=t, column=col).border = BOX
    st.cell(row=t, column=col).fill = PatternFill('solid', fgColor='EFEDE6')

for j, text in enumerate([
    'Stock take is what you counted on 16 September 2026. Type a new count over it whenever you take one.',
    'Sold so far and Money taken add up every night on the Nights tab — put each new night in there and these move.',
    'Left is the stock take less what the tills have sold since. A line you leave blank says "not counted".',
    'The last nine lines are things the till sells that the stock take does not count yet.',
], start=t + 2):
    st.cell(row=j, column=1, value=text).font = NOTE

# ----------------------------------------------------------------- Nights ---
ns = wb.create_sheet('Nights')
ns['A1'] = 'Every night'
ns['A1'].font = TITLE
ns['A2'] = 'One row per line on the receipt. Add each new night underneath — the Stock tab takes it off.'
ns['A2'].font = NOTE

NHEAD = ['Date', 'Line', 'What the till called it', 'How many', 'Each takes', 'Off the cellar', 'Money']
NWIDTH = [12, 22, 26, 11, 12, 14, 12]
for i, (label, width) in enumerate(zip(NHEAD, NWIDTH), start=1):
    c = ns.cell(row=4, column=i, value=label)
    c.font = HEAD; c.fill = HEAD_FILL; c.border = BOX
    c.alignment = Alignment(horizontal='center', vertical='center', wrap_text=True)
    ns.column_dimensions[get_column_letter(i)].width = width
ns.row_dimensions[4].height = 28
ns.freeze_panes = 'A5'

dv = DataValidation(type='list', formula1=f'Stock!$A${S_FIRST}:$A${S_LAST}', allow_blank=True)
ns.add_data_validation(dv)
dv.add(f'B{N_FIRST}:B{N_LAST}')

for r in range(N_FIRST, N_LAST + 1):
    for col in (1, 2, 3, 4, 5, 7):
        c = ns.cell(row=r, column=col)
        c.font = INPUT; c.fill = YELLOW; c.border = BOX
    ns.cell(row=r, column=1).number_format = DATE
    ns.cell(row=r, column=4).number_format = NUM
    ns.cell(row=r, column=5).number_format = NUM
    ns.cell(row=r, column=7).number_format = MONEY
    off = ns.cell(row=r, column=6, value=f'=IF(OR($B{r}="",$D{r}=""),"",$D{r}*$E{r})')
    off.font = BODY; off.border = BOX; off.number_format = NUM

# The night she sent: 18 September, both cash-ups, straight off the receipts.
for i, it in enumerate(night['items']):
    r = N_FIRST + i
    line, each = TAKES[it['code']]
    ns.cell(row=r, column=1, value=date(2026, 9, 18))
    ns.cell(row=r, column=2, value=line)
    ns.cell(row=r, column=3, value=it['name'])
    ns.cell(row=r, column=4, value=it['qty'])
    ns.cell(row=r, column=5, value=each)
    ns.cell(row=r, column=7, value=round(it['pence'] / 100, 2))
    ns.cell(row=r, column=1).number_format = DATE

after = N_FIRST + len(night['items']) + 1
ns.cell(row=after, column=1, value='↑ 18 September 2026, both cash-ups: £1,174.75 at 16:57 and £836.30 at 22:46.').font = NOTE
ns.cell(row=after + 1, column=1, value='Put the next night in underneath. What one sale takes: a pint 1, a half 0.5, a large glass of wine 175.').font = NOTE

wb.calculation.fullCalcOnLoad = True
wb.save('Gardeners Arms cellar.xlsx')
print(f'written: {len(lines)} lines, {len(night["items"])} till rows, £{night["totalPence"]/100:,.2f}')
