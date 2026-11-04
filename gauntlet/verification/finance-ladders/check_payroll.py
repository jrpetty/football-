"""Independent second derivation of the Check the Payslips ladder (work.payroll-ladder).

A separate payroll written in Python from the rules text of the prompt (not a port of the JavaScript engine's
code paths: integer pence throughout, fractions for every rounding), run month by month over the month inputs in
payroll-inputs.json with its OWN year-to-date chain. It must reproduce, to the penny, every payslip of every month
(the six figures and employer NI), every month's employer NI total, every corrected payslip in the key and every
year-to-date figure.   Run from this folder:  python3 check_payroll.py   (exit 1 on any difference)
"""
import json
import os
import sys
from datetime import date, timedelta
from fractions import Fraction as Fr
from math import floor, ceil

HERE = os.path.dirname(os.path.abspath(__file__))

PT, UST, ST = 104800, 418900, 41700           # monthly NI thresholds (pence)
QE_LO, QE_HI = 52000, 418900                  # pension qualifying earnings
SL = {'1': 217200, '2': 237200, '4': 272800, 'PGL': 175000}
SSP_DAY, SSP_LEL = 2375, 12500
BANDS = [(3770000, Fr(20, 100)), (12514000, Fr(40, 100)), (None, Fr(45, 100))]


def half_up(x):
    """Nearest integer, half up (x a Fraction, any sign: symmetric, as 'half a penny rounds up' is stated for amounts)."""
    return floor(x + Fr(1, 2)) if x >= 0 else -floor(-x + Fr(1, 2))


def half_down(x):
    return ceil(x - Fr(1, 2)) if x >= 0 else -ceil(-x - Fr(1, 2))


def working_days(a, b):
    n, d = 0, a
    while d <= b:
        n += d.weekday() < 5
        d += timedelta(days=1)
    return n


def age_on(dob, day):
    return day.year - dob.year - ((day.month, day.day) < (dob.month, dob.day))


def tax_due(taxable_td, m):
    """Tax to date on taxable pay to date (whole pounds, in pence) for m months: limits rounded UP to the pound."""
    tax, prev = Fr(0), 0
    for top, rate in BANDS:
        lim = None if top is None else ceil(Fr(top, 100) * m / 12) * 100
        hi = taxable_td if lim is None else min(taxable_td, lim)
        tax += max(0, hi - prev) * rate
        if lim is None or taxable_td <= lim:
            break
        prev = lim
    return floor(tax)


def payslip(e, pay_day, month, ytd):
    d = lambda s: date.fromisoformat(s)  # noqa: E731
    sal = e.get('salary', 0)
    if e.get('hourly'):
        basic = half_up(Fr(e['hours']) * e['rate'])
    else:
        monthly = half_up(Fr(sal, 12))
        if e.get('start'):
            first = pay_day.replace(day=1)
            last = (first + timedelta(days=32)).replace(day=1) - timedelta(days=1)
            basic = half_up(Fr(monthly) * working_days(d(e['start']), last) / working_days(first, last))
        else:
            basic = monthly
    gross = basic
    hourly_rate = Fr(sal, 52) / Fr(e.get('weekly', 1)) if not e.get('hourly') else None
    if e.get('ot'):
        gross += half_up(Fr(e['ot']) * hourly_rate * Fr(3, 2))
    if e.get('otSun'):
        gross += half_up(Fr(e['otSun']) * hourly_rate * 2)
    if e.get('sick'):
        gross -= half_up(Fr(sal * e['sick'], 260))
        if e['awe'] >= SSP_LEL:
            gross += max(0, e['sick'] - e['waitingLeft']) * SSP_DAY
    gross += e.get('bonus', 0)
    if e.get('sacrifice'):
        gross -= e['sacrifice']
    niable = gross
    pension = 0
    er_pen = 0
    if e.get('pensionSacrifice'):
        qe = max(0, min(gross, QE_HI) - QE_LO)
        sac = half_up(Fr(qe * 5, 100))
        gross -= sac
        niable -= sac
    elif e.get('pension') == 'enrolled':
        qe = max(0, min(gross, QE_HI) - QE_LO)
        pension = half_up(Fr(qe * 5, 100))
    taxable = gross - pension
    code = e['code']
    if code == 'BR':
        tax = floor(Fr(taxable * 20, 100))
    elif code == 'D0':
        tax = floor(Fr(taxable * 40, 100))
    elif code == 'NT':
        tax = 0
    else:
        k = code.startswith('K')
        num = 0 if code == '0T' else int(''.join(c for c in code if c.isdigit()))
        m1 = bool(e.get('m1'))
        mm = 1 if m1 else month
        pay_td = taxable if m1 else ytd['pay'] + taxable
        allow = half_up(Fr(num * 1000 * mm, 12))
        t_td = pay_td + allow if k else pay_td - allow
        t_td = max(0, floor(Fr(t_td, 100)) * 100)
        tax = tax_due(t_td, mm) - (0 if m1 else ytd['tax'])
        if k:
            tax = min(tax, floor(Fr(taxable, 2)))
    age = age_on(d(e['dob']), pay_day)
    cat = 'C' if age >= 66 else ('H' if e.get('apprentice') and age < 25 else 'A')
    if e.get('director'):
        td = ytd['niable'] + niable
        ni = half_down(Fr(max(0, min(td, 5027000) - 1257000) * 8 + max(0, td - 5027000) * 2, 100)) - ytd['ni']
        er = half_down(Fr(max(0, td - 500000) * 15, 100)) - ytd['erNi']
    else:
        ni = 0 if cat == 'C' else half_down(Fr(max(0, min(niable, UST) - PT) * 8 + max(0, niable - UST) * 2, 100))
        base = max(0, niable - UST) if cat == 'H' else max(0, niable - ST)
        er = half_down(Fr(base * 15, 100))
    sl = 0
    for plan in e.get('loans', []):
        thr = SL[str(plan)]
        rate = 6 if str(plan) == 'PGL' else 9
        sl += floor(Fr(max(0, niable - thr) * rate, 100 * 100)) * 100
    net = gross - tax - ni - pension - sl
    return dict(gross=gross, tax=tax, ni=ni, pension=pension, sl=sl, net=net, erNi=er), niable


def check_level(lid, dump, case):
    bad = []
    ytd = {i: dict(v) for i, v in dump['opening'].items()}
    key = case['expected']
    for mo in dump['months']:
        pay_day = date.fromisoformat(mo['pay'])
        label = f"{['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'][pay_day.month - 1]} {pay_day.year}"
        er_total = 0
        for e in mo['staff']:
            p, niable = payslip(e, pay_day, mo['month'], ytd[e['id']])
            js = mo['correct'][e['id']]
            for f in ('gross', 'tax', 'ni', 'pension', 'sl', 'net', 'erNi'):
                if p[f] != js[f]:
                    bad.append(f"{lid} {label} {e['id']} {f}: python {p[f]}, engine {js[f]}")
            km = key['months'][label]
            if e['id'] in km['corrected']:
                c = km['corrected'][e['id']]
                for f, g in (('gross', 'gross'), ('tax', 'tax'), ('ni', 'ni'), ('pension', 'pension'), ('student_loan', 'sl'), ('net', 'net')):
                    if round(c[f] * 100) != p[g]:
                        bad.append(f"{lid} {label} {e['id']} key {f}: {c[f]} vs python {p[g] / 100}")
            er_total += p['erNi']
            y = ytd[e['id']]
            y['pay'] += p['gross'] - p['pension']
            y['tax'] += p['tax']
            if e.get('director'):
                y['niable'] += niable
                y['ni'] += p['ni']
                y['erNi'] += p['erNi']
        if round(key['months'][label]['employer_ni_total'] * 100) != er_total:
            bad.append(f"{lid} {label} employer NI total {key['months'][label]['employer_ni_total']} vs python {er_total / 100}")
    for i, v in (key.get('year_to_date') or {}).items():
        if round(v['taxable_pay'] * 100) != ytd[i]['pay'] or round(v['tax'] * 100) != ytd[i]['tax']:
            bad.append(f"{lid} {i} year to date {v} vs python {ytd[i]['pay'] / 100}, {ytd[i]['tax'] / 100}")
    return bad


def main():
    dumps = json.load(open(os.path.join(HERE, 'payroll-inputs.json'), encoding='utf-8'))
    test = json.load(open(os.path.join(HERE, '..', '..', 'tests', 'work', 'payroll-ladder.json'), encoding='utf-8'))
    bad, n = [], 0
    for case in test['cases']:
        bad += check_level(case['id'], dumps[case['id']], case)
        n += sum(len(m['staff']) for m in dumps[case['id']]['months'])
    for b in bad[:40]:
        print(b)
    print(f"{n} payslips recomputed independently: {'OK' if not bad else f'{len(bad)} differences'}")
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
