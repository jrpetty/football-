"""Shared helpers for the Real-World Work pack (tests/work/*.json).

Everything here is deterministic: generators take a fixed seed and use their own random.Random, so
`python3 build.py` rewrites every test file byte for byte.
"""
import os, sys, json, random
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
from common import write_test, tok, ONE  # noqa: E402,F401

# England and Wales bank holidays (gov.uk), 2026 and early 2027.
BANK_HOLIDAYS = [date(2026, 1, 1), date(2026, 4, 3), date(2026, 4, 6), date(2026, 5, 4), date(2026, 5, 25),
                 date(2026, 8, 31), date(2026, 12, 25), date(2026, 12, 28), date(2027, 1, 1)]
BH = set(BANK_HOLIDAYS)
DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
DOW_LONG = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
MON_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']


def is_working(d):
    return d.weekday() < 5 and d not in BH


def add_working(d, n, holidays=True):
    """The n-th working day after d (n >= 1)."""
    x = d
    while n > 0:
        x += timedelta(days=1)
        if x.weekday() < 5 and (not holidays or x not in BH):
            n -= 1
    return x


def sub_working(d, n):
    """The n-th working day before d."""
    x = d
    while n > 0:
        x -= timedelta(days=1)
        if is_working(x):
            n -= 1
    return x


def next_working(d, holidays=True):
    return add_working(d, 1, holidays)


def uk(d):
    """'Tue 3 Mar'"""
    return f"{DOW[d.weekday()]} {d.day} {MON[d.month - 1]}"


def uky(d):
    return f"{uk(d)} {d.year}"


def iso(d):
    return d.isoformat()


def money(x):
    """'£1,234.50'"""
    s = f"{abs(x):,.2f}"
    return ('-£' if x < 0 else '£') + s


FIRST = ['Aisha', 'Tomasz', 'Priya', 'Kwame', 'Siobhan', 'Hiroshi', 'Mateo', 'Fatima', 'Oluwaseun', 'Ingrid', 'Ravi', 'Chloe',
         'Dmitri', 'Nkechi', 'Yusuf', 'Mei', 'Callum', 'Leila', 'Bogdan', 'Ines', 'Zainab', 'Rhys', 'Anjali', 'Kofi', 'Elif',
         'Tariq', 'Freya', 'Jun', 'Amara', 'Gareth', 'Lucia', 'Sanjay', 'Niamh', 'Wei', 'Ayodele', 'Marek', 'Rosa', 'Hamza',
         'Keisha', 'Ewan', 'Sofia', 'Arjun', 'Bronwen', 'Hyun-woo', 'Carmen', 'Declan', 'Yasmin', 'Thabo', 'Agnieszka', 'Omar',
         'Saoirse', 'Kenji', 'Abena', 'Florin', 'Maryam', 'Lachlan', 'Esi', 'Joaquin', 'Harriet', 'Vikram']
LAST = ['Okafor', 'Kowalski', 'Raman', 'Mensah', "O'Brien", 'Tanaka', 'Alvarez', 'Haddad', 'Adeyemi', 'Lindqvist', 'Patel',
        'Dubois', 'Petrov', 'Eze', 'Demir', 'Chen', 'MacLeod', 'Farahani', 'Nowak', 'Ferreira', 'Hussain', 'Pritchard', 'Iyer',
        'Boateng', 'Yilmaz', 'Qureshi', 'Morgan', 'Park', 'Nwosu', 'Hughes', 'Moreno', 'Sharma', 'Gallagher', 'Zhang', 'Balogun',
        'Novak', 'Castillo', 'Malik', 'Campbell', 'Rossi', 'Kapoor', 'Evans', 'Kim', 'Navarro', 'Doyle', 'Rahman', 'Dlamini',
        'Wojcik', 'Achterberg', 'Osei', 'Lindgren', 'Bianchi', 'Takahashi', 'Mbeki', 'Sorensen', 'Ahmadi']


def people(rng, n, avoid=()):
    """n distinct invented full names with distinct first names and surnames."""
    firsts = [f for f in FIRST if f not in avoid]
    lasts = list(LAST)
    rng.shuffle(firsts)
    rng.shuffle(lasts)
    return [f"{firsts[i]} {lasts[i]}" for i in range(n)]


def email_of(name, domain):
    f, l = name.split(' ', 1)
    l = l.replace("'", '').replace(' ', '').lower()
    return f"{f.lower().replace('-', '')}.{l}@{domain}"


JSON_FORMAT_NOTE = ("Reply with the JSON object only (a ```json code block is fine). Key order, spacing and letter case do not matter, "
                    "and amounts may be written with or without a £ sign. Give exactly one answer: if you give two different values for "
                    "the same field, or more than one JSON object that could be the answer, the last one counts.")
