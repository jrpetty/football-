"""math.word-problems: statements + exact computation (Decimal / Fraction), printed for review."""
from decimal import Decimal as D, ROUND_HALF_UP, ROUND_CEILING, getcontext
from fractions import Fraction as F
import datetime, math, json
getcontext().prec = 50
C = D('0.01')
def r2(x):
    return D(x).quantize(C, rounding=ROUND_HALF_UP)

cases = []

# ---------------------------------------------------------------- W01 (easy) bakery
sub_pastry = 3 * D('3.40') + 2 * D('2.75')
disc = r2(sub_pastry * D('0.15'))
sub = sub_pastry - disc + 2 * D('4.20')
tax = r2(sub * D('0.08'))
total = sub + tax
change = D('50') - total
cases.append(dict(id='w01', difficulty='easy', expected=float(change), tol=0.005, prompt=(
"A bakery charges $3.40 per croissant, $2.75 per muffin and $4.20 per coffee. On Tuesdays every pastry "
"(croissants and muffins) is 15% off; drinks are never discounted. Sales tax of 8% is then added to the whole "
"discounted subtotal. The bakery also sells day-old bread at $1.90 a loaf, but this customer buys none.\n\n"
"On a Tuesday a customer buys 3 croissants, 2 muffins and 2 coffees and pays with a single $50 note.\n\n"
"Rules: compute the 15% discount on the pastry total and round that discount to the nearest cent (half up); "
"compute the tax on the discounted subtotal and round the tax to the nearest cent (half up).\n\n"
"How much change, in dollars, does the customer receive? Give the answer as a number with exactly two decimal places and no currency symbol (for example 12.34)."),
notes=f"pastry subtotal {sub_pastry}; discount {disc}; discounted subtotal incl. coffee {sub}; tax {tax}; total {total}; change {change}. Distractor: day-old bread price."))

# ---------------------------------------------------------------- W02 (easy) pool
vol_l = D('6') * D('3.5') * D('1.2') * 1000   # litres
need = vol_l - 4000
minutes = (need / 14).to_integral_value(rounding=ROUND_CEILING)
cases.append(dict(id='w02', difficulty='easy', expected=int(minutes), tol=0, prompt=(
"A rectangular pool has vertical walls and a flat bottom. It is 6 m long and 3.5 m wide, and it is to be filled "
"to a water depth of exactly 1.2 m (the pool itself is 1.8 m deep). It already contains 4,000 litres of water. "
"A hose adds water at a constant 14 litres per minute, and nothing leaks out. (1 cubic metre = 1,000 litres.)\n\n"
"How many whole minutes must the hose run so that the water reaches a depth of at least 1.2 m? Round up to the next "
"whole minute. Give the answer as an integer number of minutes with no units."),
notes=f"target volume {vol_l} L; still needed {need} L; {need}/14 = {need/14:.4f} -> ceil {minutes}. Distractor: 1.8 m pool depth."))

# ---------------------------------------------------------------- W03 (medium) printers
# A: 45 pages/min from 9:00, jams at 9:20 for 12 min (resumes 9:32). B: 30 pages/min, but after every 25 minutes of printing it pauses 5 min.
# Job 3000 pages shared: both print simultaneously from the same queue. Find finishing time (minutes after 9:00, exact).
def printers():
    t = F(0)
    done = F(0)
    # simulate in small exact intervals: events at integer minutes are enough; rates constant between events
    # A active: [0,20) and [32, inf). B active: [0,25),[30,55),[60,85),...
    def a_on(x):
        return x < 20 or x >= 32
    def b_on(x):
        return (x % 30) < 25
    minute = 0
    while True:
        rate = (45 if a_on(minute) else 0) + (30 if b_on(minute) else 0)
        if done + rate >= 3000:
            frac = F(3000 - done, rate)
            return minute + frac
        done += rate
        minute += 1
tp = printers()
cases.append(dict(id='w03', difficulty='medium', expected=float(tp), tol=0.01, prompt=(
"Two printers share one print job of exactly 3,000 pages; each page is printed by exactly one printer, and both "
"work on the job at the same time whenever they are running. Both start at 9:00.\n"
"- Printer A prints 45 pages per minute. At 9:20 it jams and is out of action for exactly 12 minutes, then resumes at full speed and never stops again.\n"
"- Printer B prints 30 pages per minute, but after every 25 minutes of printing it pauses for exactly 5 minutes to cool down, then resumes (so it prints 9:00-9:25, pauses 9:25-9:30, prints 9:30-9:55, and so on).\n"
"- A third printer, C, rated at 60 pages per minute, is out of paper all morning and prints nothing.\n"
"Printing rates are exactly constant while a printer is running, and partial pages are allowed in the arithmetic.\n\n"
"How many minutes after 9:00 is the last page of the job finished? Give the answer as a number of minutes, as an exact decimal rounded to two decimal places (for example 41.25)."),
notes=f"Exact simulation minute by minute with exact fractions: finish at {tp} = {float(tp):.4f} min after 9:00. Distractor: printer C."))

# ---------------------------------------------------------------- W04 (medium) electricity tiers
kwh = 1340 - 0  # consumption
def bill(k):
    tiers = [(300, D('0.12')), (500, D('0.17')), (None, D('0.23'))]
    rem = D(k); cost = D(0)
    for size, price in tiers:
        take = rem if size is None else min(rem, D(size))
        cost += take * price
        rem -= take
        if rem <= 0:
            break
    return cost
reading_prev, reading_now = 48_215, 49_555
k = reading_now - reading_prev
energy = bill(k)
fixed = D('18.50')
solar_credit = D('0.09') * 210
subtotal = energy + fixed - solar_credit
vat = r2(subtotal * D('0.05'))
tot4 = subtotal + vat
cases.append(dict(id='w04', difficulty='medium', expected=float(tot4), tol=0.005, prompt=(
"An electricity supplier bills monthly as follows.\n"
"- Energy is charged in tiers on the month's consumption: the first 300 kWh at $0.12 per kWh, the next 500 kWh at $0.17 per kWh, and every kWh beyond 800 at $0.23 per kWh.\n"
"- A fixed service charge of $18.50 is added.\n"
"- Solar exports are credited at $0.09 per kWh exported; the credit is subtracted after the energy and service charges are added. Exports do not change the consumption used for the tiers.\n"
"- Finally, 5% tax is applied to the amount after the solar credit; round the tax to the nearest cent (half up).\n\n"
"This month the meter read 48,215 kWh at the start and 49,555 kWh at the end. The household exported 210 kWh of solar energy. "
"Last month's bill was $231.40, and the household's neighbour used 1,120 kWh this month.\n\n"
"What is this month's total bill in dollars? Give the answer as a number with exactly two decimal places and no currency symbol."),
notes=f"consumption {k} kWh; energy {energy}; +18.50 -18.90 credit = {subtotal}; tax {vat}; total {tot4}. Distractors: last month's bill, neighbour usage."))

# ---------------------------------------------------------------- W05 (medium) mixture
c = F(30, 100); vol = F(12)
trace = []
for _ in range(3):
    c = (c * (vol - F(5, 2)) + F(65, 100) * F(5, 2)) / vol
    trace.append(c)
pct = c * 100
cases.append(dict(id='w05', difficulty='medium', expected=float(pct), tol=0.005, prompt=(
"A tank holds 12 litres of a solution that is 30% acid by volume. The following operation is carried out three times in a row: "
"2.5 litres of the (well-mixed) solution are drained out, and then 2.5 litres of a 65% acid solution are added and mixed thoroughly. "
"Assume volumes simply add (no contraction when mixing), and the drained liquid is discarded.\n\n"
"What is the acid concentration of the solution in the tank after the three operations, as a percentage? Do not round until the end. Give the percentage rounded to two decimal places (half up), without the % sign (for example 41.07)."),
notes=f"Exact fractions: after each op {[str(x*100) for x in trace]} %; final {pct} = {float(pct):.6f}%."))

# ---------------------------------------------------------------- W06 (medium) time zones
dep_local = datetime.datetime(2026, 3, 28, 22, 45)  # UTC+1
dep_utc = dep_local - datetime.timedelta(hours=1)
arr_utc = dep_utc + datetime.timedelta(hours=9, minutes=40) + datetime.timedelta(minutes=35)
arr_local = arr_utc + datetime.timedelta(hours=3)
ans6 = arr_local.strftime('%H:%M')
cases.append(dict(id='w06', difficulty='medium', expected=[ans6], exact=True, prompt=(
"An overnight train leaves Station P at 22:45 local time. Station P's local time is UTC+1. The timetabled journey takes "
"9 hours 40 minutes, but on this night the train is delayed and arrives 35 minutes later than timetabled. Station Q's "
"local time is UTC+3. Neither location changes its clocks (no daylight-saving change) during the journey. The train "
"stops for 20 minutes at an intermediate station; this stop is already included in the timetabled 9 hours 40 minutes.\n\n"
"At what local time (at Station Q) does the train arrive? Give the answer in 24-hour HH:MM format (for example 07:05)."),
notes=f"dep UTC {dep_utc:%H:%M}; arrival UTC {arr_utc:%H:%M}; local Q {ans6}. Distractor: 20-minute stop already included."))

# ---------------------------------------------------------------- W07 (hard) payroll
# rate $24/h. Days: Mon 9.5h, Tue 7h, Wed 10h, Thu 8h, Fri 11h, Sat 5h. Each day includes a 30-min unpaid break
# on days worked > 6 hours (listed hours are time on site). Overtime: paid hours beyond 8 in a day at 1.5x.
# Saturday: all paid hours at 2x (no overtime stacking). Weekly: no additional overtime rule.
rate = D('24')
days = [('Mon', D('9.5')), ('Tue', D('7')), ('Wed', D('10')), ('Thu', D('8')), ('Fri', D('11')), ('Sat', D('5'))]
pay = D(0); detail = []
for name, onsite in days:
    paid = onsite - (D('0.5') if onsite > 6 else 0)
    if name == 'Sat':
        p = paid * rate * 2
    else:
        reg = min(paid, D(8)); ot = max(paid - 8, D(0))
        p = reg * rate + ot * rate * D('1.5')
    pay += p; detail.append((name, str(paid), str(p)))
cases.append(dict(id='w07', difficulty='hard', expected=float(pay), tol=0.005, prompt=(
"Kit is paid $24.00 per paid hour. The pay rules are:\n"
"1. On any day with more than 6 hours on site, 30 minutes of that time is an unpaid break. On days with 6 hours or fewer on site there is no break deduction. Paid hours = hours on site minus any unpaid break.\n"
"2. Monday to Friday: the first 8 paid hours of a day are at the normal rate; paid hours beyond 8 in that day are at 1.5 times the normal rate.\n"
"3. Saturday: every paid hour is at 2 times the normal rate, and the 1.5x rule does not also apply.\n"
"4. There are no other bonuses, weekly overtime rules or deductions.\n\n"
"Hours on site this week: Monday 9.5, Tuesday 7, Wednesday 10, Thursday 8, Friday 11, Saturday 5. (Kit also spent 3 hours on Sunday at a colleague's birthday party, which is not work.)\n\n"
"What is Kit's total gross pay for the week in dollars? Give the answer as a number with exactly two decimal places and no currency symbol."),
notes=f"per day (paid h, pay): {detail}; total {pay}. Distractor: Sunday party."))

# ---------------------------------------------------------------- W08 (hard) FIFO inventory
# purchases & sales
lots = [[40, D('12.00')]]  # opening
events = [('buy', 60, D('13.50')), ('sell', 70, D('21.00')), ('buy', 50, D('14.20')), ('sell', 45, D('22.00')), ('return_to_supplier', 0, None), ('buy', 30, D('15.00')), ('sell', 55, D('23.50'))]
cogs = D(0); revenue = D(0)
for ev, qty, price in events:
    if ev == 'buy':
        lots.append([qty, price])
    elif ev == 'sell':
        revenue += qty * price
        q = qty
        while q:
            take = min(q, lots[0][0])
            cogs += take * lots[0][1]
            lots[0][0] -= take; q -= take
            if lots[0][0] == 0:
                lots.pop(0)
gross = revenue - cogs
ending_units = sum(l[0] for l in lots)
ending_value = sum(l[0] * l[1] for l in lots)
cases.append(dict(id='w08', difficulty='hard', expected=float(cogs), tol=0.005, prompt=(
"A shop tracks one product using FIFO (first in, first out): each sale uses up the oldest units still in stock first, at the price those units were bought for.\n\n"
"Start of the month: 40 units in stock, bought at $12.00 each.\n"
"In order, during the month:\n"
"1. Bought 60 units at $13.50 each.\n"
"2. Sold 70 units at $21.00 each.\n"
"3. Bought 50 units at $14.20 each.\n"
"4. Sold 45 units at $22.00 each.\n"
"5. A supplier offered 100 units at $11.00 each; the shop declined and bought none.\n"
"6. Bought 30 units at $15.00 each.\n"
"7. Sold 55 units at $23.50 each.\n\n"
"What is the total cost of goods sold (the total purchase cost of all units sold this month) under FIFO, in dollars? Give the answer as a number with exactly two decimal places and no currency symbol."),
notes=f"COGS {cogs}; revenue {revenue}; ending {ending_units} units worth {ending_value}. Distractor: declined supplier offer; sale prices."))

# ---------------------------------------------------------------- W09 (hard) savings simulation
bal = D('2500.00'); monthly = D('150.00'); r = D('0.042') / 12
hist = []
for m in range(1, 25):
    interest = r2(bal * r)
    bal = bal + interest
    if m % 12 == 0:
        bal -= D('25.00')  # annual fee at end of months 12, 24 after interest
    bal += monthly
    hist.append(str(bal))
cases.append(dict(id='w09', difficulty='hard', expected=float(bal), tol=0.005, prompt=(
"A savings account starts with a balance of $2,500.00 at the beginning of month 1. At the END of every month, in this exact order:\n"
"1. Interest for the month is added: the balance at that moment times 0.042/12 (4.2% per year divided by 12), rounded to the nearest cent (half up) before it is added.\n"
"2. In months 12 and 24 only, an annual fee of $25.00 is subtracted.\n"
"3. A deposit of $150.00 is added.\n"
"No other transactions occur.\n\n"
"What is the balance immediately after the end-of-month steps for month 24? Give the answer in dollars as a number with exactly two decimal places and no currency symbol."),
notes=f"Month-by-month simulation with Decimal and half-up cent rounding each month. Balance after m12 {hist[11]}, after m24 {hist[23]}."))

# ---------------------------------------------------------------- W10 (hard) ages
# Find ages: currently mother M, daughter d, son s.
# M = 3*d + 2 ; in 6 years M+6 = 2*((d+6)+(s+6)) - 8? choose constraints and brute force uniqueness
sols = []
for M in range(1, 120):
    for d in range(0, 60):
        for s in range(0, 60):
            if M == 3 * d + 4 and (M + 6) == (d + 6) + (s + 6) + 20 and (d - s) == 5:
                sols.append((M, d, s))
cases.append(dict(id='w10', difficulty='medium', expected=sols[0][0] + sols[0][1] + sols[0][2] if len(sols) == 1 else None, tol=0, prompt=(
"Rhea is exactly 4 years older than three times her daughter Juno's age. Juno is exactly 5 years older than her brother Tev. "
"In 6 years, Rhea's age will be exactly 20 more than the sum of Juno's and Tev's ages at that time. All ages are whole numbers of years, and Rhea's own mother is 71.\n\n"
"What is the sum of the current ages of Rhea, Juno and Tev? Give the answer as an integer."),
notes=f"Brute force over integer ages: solutions {sols}. Distractor: grandmother's age 71."))

# ---------------------------------------------------------------- W11 (hard) wind
# plane airspeed v, wind w: out (against wind) 1260 km takes 3h, back (with wind) takes 2.5h -> solve; ask fuel
v_minus_w = F(1260, 3); v_plus_w = F(1260, F(5, 2))
v = (v_minus_w + v_plus_w) / 2; w = (v_plus_w - v_minus_w) / 2
# third leg: 900 km with crosswind ignored at airspeed v in still air -> time; fuel 2.4 t/h of flight
t3 = F(900) / v
fuel_total = (F(3) + F(5, 2) + t3) * F(12, 5)
cases.append(dict(id='w11', difficulty='hard', expected=float(fuel_total), tol=0.005, prompt=(
"A plane flies with the same airspeed (speed relative to the air) on every leg. On leg 1 it flies 1,260 km directly into a steady headwind and takes exactly 3 hours. "
"On leg 2 it flies the same 1,260 km back with the same wind directly behind it (a tailwind) and takes exactly 2.5 hours. "
"On leg 3 it flies 900 km in completely still air. The plane burns fuel at a constant 2.4 tonnes per hour of flight time, whatever the wind. "
"Ignore take-off, landing and time on the ground. The plane's maximum range with full tanks is 5,000 km.\n\n"
"How many tonnes of fuel are burned in total over the three legs? Give the answer as a number rounded to two decimal places."),
notes=f"v-w={v_minus_w}, v+w={v_plus_w} -> airspeed v={v} km/h, wind {w} km/h; leg3 time {t3} h; fuel {fuel_total} = {float(fuel_total):.4f} t. Distractor: max range."))

# ---------------------------------------------------------------- W12 (extreme) billing dates
start = datetime.date(2026, 1, 17)
bills = []
d0 = start
while d0 <= datetime.date(2027, 12, 31):
    bills.append(d0)
    d0 += datetime.timedelta(days=45)
n_2027 = sum(1 for b in bills if b.year == 2027)
last_2027 = [b for b in bills if b.year == 2027][-1]
cases.append(dict(id='w12', difficulty='hard', expected=[last_2027.isoformat()], exact=True, prompt=(
"A subscription bills a customer every 45 days. The first bill is issued on 17 January 2026, the second exactly 45 days later, and so on, forever (bills are issued on weekends and holidays too). "
"Use the ordinary Gregorian calendar (2026 and 2027 are not leap years). The customer's card expires on 30 June 2027 but is automatically renewed, so billing never stops.\n\n"
"On what date is the last bill issued in calendar year 2027? Give the date in ISO format YYYY-MM-DD."),
notes=f"Python datetime: bills every 45 days from 2026-01-17; 2027 bills: {[b.isoformat() for b in bills if b.year == 2027]}; last {last_2027}. Distractor: card expiry."))

# ---------------------------------------------------------------- W13 (extreme) progressive tax
income = D('86400'); pension = income * D('0.05'); deduction = D('12500')
taxable = income - pension - deduction
brackets = [(D('10000'), D('0')), (D('30000'), D('0.10')), (D('40000'), D('0.22')), (None, D('0.35'))]  # widths
rem = taxable; tax = D(0); lower = D(0)
for width, rate_ in brackets:
    take = rem if width is None else min(rem, width)
    tax += take * rate_
    rem -= take
    if rem <= 0:
        break
credit = min(D('1800'), tax)
tax_after = tax - credit
eff = r2(tax_after / income * 100)
cases.append(dict(id='w13', difficulty='hard', expected=float(tax_after), tol=0.005, prompt=(
"In the country of Veloria, annual income tax is computed as follows.\n"
"1. Start from gross salary. Subtract the employee's pension contribution, which is 5% of gross salary.\n"
"2. Subtract the standard deduction of 12,500. The result is taxable income.\n"
"3. Tax is progressive on taxable income: the first 10,000 is taxed at 0%; the next 30,000 at 10%; the next 40,000 at 22%; everything above 80,000 at 35%. Each rate applies only to the part of taxable income inside its band.\n"
"4. Subtract a family tax credit of 1,800 from the tax (the tax cannot go below zero).\n\n"
"Mara's gross salary is 86,400. She also received a one-off 2,000 gift from her aunt, which is not taxable and is not part of salary. "
"Her employer's own pension contribution for her (a further 8% of salary) is also not part of this calculation.\n\n"
"How much income tax does Mara pay for the year? Give the answer as a number with exactly two decimal places and no currency symbol."),
notes=f"pension {pension}; taxable {taxable}; tax before credit {tax}; after credit {tax_after}. Distractors: gift, employer pension."))

# ---------------------------------------------------------------- W14 (medium) recipe scaling
# recipe for 6 serves: 250 g flour, 1.5 cups milk, 3 eggs, 40 g butter. scale to 15 serves; milk only sold in 473 ml cartons; 1 cup = 240 ml
scale = F(15, 6)
milk_ml = F(3, 2) * 240 * scale
cartons = math.ceil(milk_ml / 473)
cases.append(dict(id='w14', difficulty='easy', expected=cartons, tol=0, prompt=(
"A pancake recipe that serves 6 people uses 250 g of flour, 1.5 cups of milk, 3 eggs and 40 g of butter. Ana wants to make enough for exactly 15 people by scaling every ingredient in proportion. "
"Use 1 cup = 240 ml. Milk is sold only in whole cartons of 473 ml each, and Ana has no milk at home. Eggs come in boxes of 6.\n\n"
"How many cartons of milk must Ana buy (the minimum number that gives her at least enough milk)? Give the answer as an integer."),
notes=f"milk needed 1.5*240*15/6 = {milk_ml} ml -> {float(milk_ml/473):.3f} cartons -> {cartons}. Distractors: flour, eggs boxes."))

# ---------------------------------------------------------------- W15 (hard) road trip fuel currency
# car 6.8 L/100km ; trip 1,340 km ; but 180 km of it is by car-train (car carried, not driven). Fuel price 1.92 EUR/L in country A for first 700 driven km,
# 2.05 CHF/L in country B afterwards; 1 EUR = 0.94 CHF. Answer total fuel cost in EUR (2dp) assuming fuel bought exactly as consumed in each country.
driven = 1340 - 180
a_km = 700; b_km = driven - a_km
a_l = D(a_km) * D('6.8') / 100
b_l = D(b_km) * D('6.8') / 100
cost_eur = a_l * D('1.92') + (b_l * D('2.05')) / D('0.94')
cases.append(dict(id='w15', difficulty='hard', expected=float(r2(cost_eur)), tol=0.005, prompt=(
"A car uses 6.8 litres of fuel per 100 km driven. A trip covers 1,340 km in total, but 180 km of that distance is on a car-train, where the car is carried and not driven (it uses no fuel). "
"The first 700 km that are actually driven are in Country A, where fuel costs 1.92 EUR per litre; all remaining driven kilometres are in Country B, where fuel costs 2.05 CHF per litre. "
"Assume fuel is bought exactly as it is used, in the country where it is used. The exchange rate is 1 EUR = 0.94 CHF (so 1 CHF = 1/0.94 EUR). The car-train ticket costs 95 EUR.\n\n"
"What is the total fuel cost of the trip in EUR? Do not include the car-train ticket. Round only the final answer to two decimal places and give it as a number without a currency symbol."),
notes=f"driven {driven} km; A {a_l} L -> {a_l*D('1.92')} EUR; B {b_l} L -> {b_l*D('2.05')} CHF = {(b_l*D('2.05'))/D('0.94')} EUR; total {cost_eur} -> {r2(cost_eur)}. Distractor: ticket."))

# ---------------------------------------------------------------- W16 (hard) cyclists meeting
# road P--Q 90 km. C1 leaves P 8:00 at 24 km/h, stops 20 min at the 40 km mark, then continues at 20 km/h.
# C2 leaves Q 8:30 towards P at 18 km/h.
def pos1(t):  # t hours after 8:00
    if t <= F(40, 24): return 24 * t
    if t <= F(40, 24) + F(1, 3): return F(40)
    return 40 + 20 * (t - F(40, 24) - F(1, 3))
def pos2(t):
    if t <= F(1, 2): return F(90)
    return 90 - 18 * (t - F(1, 2))
lo, hi = F(0), F(10)
# pos2-pos1 decreasing; bisection with exact piecewise solve
for seg in [(F(0), F(1, 2)), (F(1, 2), F(40, 24)), (F(40, 24), F(40, 24) + F(1, 3)), (F(40, 24) + F(1, 3), F(10))]:
    a, b = seg
    ga, gb = pos2(a) - pos1(a), pos2(b) - pos1(b)
    if ga >= 0 >= gb:
        tm = a + (b - a) * ga / (ga - gb)  # linear on segment
        break
meet = pos1(tm)
assert pos1(tm) == pos2(tm)
cases.append(dict(id='w16', difficulty='hard', expected=float(meet), tol=0.005, prompt=(
"Towns P and Q are 90 km apart along a straight road. Cyclist 1 leaves P at 8:00 riding towards Q at a constant 24 km/h. "
"When she reaches the 40 km mark (40 km from P) she stops for exactly 20 minutes, then continues towards Q at a constant 20 km/h. "
"Cyclist 2 leaves Q at 8:30 riding towards P at a constant 18 km/h without stopping. A support van leaves P at 9:00 at 60 km/h but plays no part in the question.\n\n"
"How far from P (in km) are the two cyclists when they meet? Give the distance rounded to two decimal places (half up), as a number without units."),
notes=f"Piecewise-linear exact solve with fractions: meet at t={tm} h after 8:00, distance {meet} = {float(meet):.5f} km. Distractor: van."))

# ---------------------------------------------------------------- W17 (hard) checkout
goods = D('89.00') + 3 * D('19.50') + D('24.00') + D('7.25')
promo = goods - D('19.50')
coupon = r2(promo * D('0.10')) if promo >= 100 else D(0)
after_coupon = promo - coupon
ship = D(0) if after_coupon >= 150 else D('6.95')
tax17 = r2(after_coupon * D('0.075'))
total17 = after_coupon + tax17 + ship
charged = total17 - D('50')
cases.append(dict(id='w17', difficulty='hard', expected=float(charged), tol=0.005, prompt=(
"An online shop applies these rules at checkout, in this order:\n"
"1. T-shirt promotion: for every 3 T-shirts in the cart, the cheapest of those 3 is free.\n"
"2. Coupon: if the goods subtotal after step 1 is at least $100.00, take 10% off it; round this discount to the nearest cent (half up).\n"
"3. Shipping: free if the goods subtotal after step 2 is at least $150.00; otherwise shipping costs $6.95.\n"
"4. Tax: 7.5% of the goods subtotal after step 2 (shipping is not taxed); round the tax to the nearest cent (half up).\n"
"5. Order total = goods subtotal after step 2 + tax + shipping.\n"
"6. A $50.00 gift card is applied to the order total, and the rest is charged to a credit card.\n\n"
"The cart contains: one jacket at $89.00, three T-shirts at $19.50 each, one belt at $24.00, and one pair of socks at $7.25. "
"The customer also looked at a $39.00 scarf but did not add it to the cart.\n\n"
"How much is charged to the credit card, in dollars? Give the answer as a number with exactly two decimal places and no currency symbol."),
notes=f"goods {goods}; after promo {promo}; coupon {coupon}; after coupon {after_coupon}; shipping {ship} (threshold uses post-coupon subtotal); tax {tax17}; total {total17}; charged {charged}. Distractor: scarf."))

for c_ in cases:
    print(c_['id'], c_['difficulty'], c_['expected'], '|', c_['notes'])
json.dump(cases, open('word_cases.json', 'w'), indent=1, default=str)
