"""work.meeting-scheduler — "Find a Slot".

A seeded team spread over several time zones, each with working hours, meetings, focus blocks, in-person
appointments that need travel time, a shared London meeting room, the company's "no Friday afternoon" rule and
a calendar update that arrives later. Each case asks for the EARLIEST start (London time, on a quarter hour) of
four meetings.

Two independent solvers find every answer: `earliest_scan` tests every quarter-hour start against every rule,
and `earliest_intervals` subtracts busy time from working time with interval arithmetic and takes the first
aligned start that fits. They must agree. The earliest start is unique by definition, and the build also checks
that a careless reading (fixed "usual" UTC offsets instead of the real daylight-saving rules, the later update
ignored, travel time, the Friday rule and earlier bookings forgotten) gives a different answer on most requests,
so every rule matters.
"""
import random
from datetime import date, datetime, timedelta, time, timezone
from zoneinfo import ZoneInfo
from wk import uk, uky, DOW, DOW_LONG, MON, MON_LONG, people, BH

UTC = timezone.utc
LON = ZoneInfo('Europe/London')
CITIES = {
    'London': ('Europe/London', 0.0),
    'New York': ('America/New_York', -5.0),
    'Toronto': ('America/Toronto', -5.0),
    'Singapore': ('Asia/Singapore', 8.0),
    'Sydney': ('Australia/Sydney', 11.0),
    'Dubai': ('Asia/Dubai', 4.0),
    'Mumbai': ('Asia/Kolkata', 5.5),
    'Johannesburg': ('Africa/Johannesburg', 2.0),
    'Lisbon': ('Europe/Lisbon', 0.0),
    'Warsaw': ('Europe/Warsaw', 1.0),
}
DST_RULES = ("Clock changes: the UK and Portugal move clocks forward one hour on the last Sunday of March and back on the last Sunday of October "
             "(the rest of Europe on the same days); the US and Canada move forward on the second Sunday of March and back on the first Sunday of November; "
             "Sydney moves back one hour on the first Sunday of April and forward on the first Sunday of October. Singapore, Dubai, Mumbai and "
             "Johannesburg never change. Standard offsets from UTC: London and Lisbon +0, Warsaw +1, New York and Toronto -5, Johannesburg +2, Dubai +4, "
             "Mumbai +5:30, Singapore +8, Sydney +10.")

Q = timedelta(minutes=15)
ALL = set(CITIES)
# Public holidays in the booking windows: Good Friday and Easter Monday 2026.
HOLIDAYS = {date(2026, 4, 3): ALL - {'Dubai'}, date(2026, 4, 6): {'London', 'Sydney', 'Warsaw', 'Johannesburg'}}


def fmt_off(h):
    sign = '+' if h >= 0 else '-'
    h = abs(h)
    return f"UTC{sign}{int(h)}" + (f":{int(round((h % 1) * 60)):02d}" if h % 1 else '')


class Person:
    def __init__(self, name, city, hours, days=(0, 1, 2, 3, 4)):
        self.name, self.city = name, city
        self.tzname, self.usual = CITIES[city]
        self.tz = ZoneInfo(self.tzname)
        self.hours = hours  # (time, time) local
        self.days = days
        self.off = []  # local dates not working
        self.events = []  # (local date, start time, end time, title, travel minutes)
        self.focus = []  # (weekday set, start, end, label)
        self.update = None  # dict(kind='add'|'remove'|'move', ...)
        self.buffer_after_long = False


def local_to_utc(p, d, t, careless=False):
    if careless:
        return (datetime.combine(d, t) - timedelta(hours=p.usual)).replace(tzinfo=UTC)
    return datetime.combine(d, t, tzinfo=p.tz).astimezone(UTC)


def lon_to_utc(d, t, careless=False):
    if careless:
        return datetime.combine(d, t, tzinfo=UTC)
    return datetime.combine(d, t, tzinfo=LON).astimezone(UTC)


def utc_to_local(p, u, careless=False):
    if careless:
        return (u + timedelta(hours=p.usual)).replace(tzinfo=None)
    return u.astimezone(p.tz).replace(tzinfo=None)


class World:
    def __init__(self):
        self.people = []
        self.room = []  # (london date, start, end, who)
        self.requests = []  # dict(label, who, dur, room, not_before)
        self.win = None  # (first london date, last london date)
        self.sequential = False
        self.friday_rule = True
        self.offsets_given = False


def events_of(p, careless=False):
    """Busy intervals (UTC) for a person, after the later update (unless careless) and with travel time."""
    evs = list(p.events)
    if p.update and not careless:
        u = p.update
        if u['kind'] == 'remove':
            evs = [e for e in evs if e is not u['event']]
        elif u['kind'] == 'move':
            evs = [e for e in evs if e is not u['event']] + [u['new']]
        elif u['kind'] == 'add':
            evs = evs + [u['new']]
    out = []
    for d, s, e, title, travel in evs:
        a = local_to_utc(p, d, s, careless)
        b = local_to_utc(p, d, e, careless)
        if travel and not careless:
            a -= timedelta(minutes=travel)
            b += timedelta(minutes=travel)
        out.append((a, b, title))
    return out


def ok_for(w, p, a, b, careless, booked):
    """Can person p attend [a, b) (UTC)?"""
    la, lb = utc_to_local(p, a, careless), utc_to_local(p, b, careless)
    if la.date() != (lb - timedelta(microseconds=1)).date():
        return False
    d = la.date()
    if d.weekday() not in p.days or d in p.off:
        return False
    if la.time() < p.hours[0] or lb > datetime.combine(d, p.hours[1]):
        return False
    if w.friday_rule and not careless and d.weekday() == 4 and lb > datetime.combine(d, time(12, 0)):
        return False
    for days, fs, fe, _ in p.focus:
        if d.weekday() in days:
            x, y = datetime.combine(d, fs), datetime.combine(d, fe)
            if la < y and x < lb:
                return False
    for x, y, _ in events_of(p, careless):
        if a < y and x < b:
            return False
    if not careless:
        for (x, y, who) in booked:
            if p.name in who:
                gap = timedelta(minutes=15) if p.buffer_after_long else timedelta(0)
                if a < y + gap and x - gap < b:
                    return False
    return True


def room_free(w, a, b, careless, booked):
    for d, s, e, _ in w.room:
        x, y = lon_to_utc(d, s, careless), lon_to_utc(d, e, careless)
        if a < y and x < b:
            return False
    if not careless:
        for (x, y, who, room) in [(bk[0], bk[1], bk[2], bk[3]) for bk in booked]:
            if room and a < y and x < b:
                return False
    return True


def earliest_scan(w, req, careless=False, booked=()):
    d0, d1 = w.win
    start = lon_to_utc(d0, time(0, 0), careless)
    if req.get('not_before'):
        start = max(start, lon_to_utc(req['not_before'][0], req['not_before'][1], careless))
    end = lon_to_utc(d1 + timedelta(days=1), time(0, 0), careless)
    dur = timedelta(minutes=req['dur'])
    t = start
    who = [p for p in w.people if p.name in req['who']]
    while t + dur <= end:
        b = t + dur
        lt = utc_to_local_lon(t, careless)
        if lt.minute % 15 == 0 and all(ok_for(w, p, t, b, careless, [(x[0], x[1], x[2]) for x in booked]) for p in who) and (not req['room'] or room_free(w, t, b, careless, booked)):
            return t
        t += timedelta(minutes=5)
    return None


def utc_to_local_lon(u, careless=False):
    if careless:
        return u.replace(tzinfo=None)
    return u.astimezone(LON).replace(tzinfo=None)


# ── independent second solver: interval arithmetic ──────────────────────────────

def subtract(free, busy):
    out = []
    for a, b in free:
        segs = [(a, b)]
        for x, y in busy:
            nxt = []
            for s, e in segs:
                if y <= s or e <= x:
                    nxt.append((s, e))
                else:
                    if s < x:
                        nxt.append((s, x))
                    if y < e:
                        nxt.append((y, e))
            segs = nxt
        out.extend(segs)
    return sorted(out)


def intersect(A, B):
    out = []
    for a, b in A:
        for c, d in B:
            s, e = max(a, c), min(b, d)
            if s < e:
                out.append((s, e))
    return sorted(out)


def free_of(w, p, booked):
    d0, d1 = w.win
    free = []
    d = d0 - timedelta(days=2)
    while d <= d1 + timedelta(days=2):
        if d.weekday() in p.days and d not in p.off:
            a = datetime.combine(d, p.hours[0], tzinfo=p.tz).astimezone(UTC)
            end_t = p.hours[1]
            if w.friday_rule and d.weekday() == 4:
                end_t = min(end_t, time(12, 0))
            b = datetime.combine(d, end_t, tzinfo=p.tz).astimezone(UTC)
            if a < b:
                free.append((a, b))
        d += timedelta(days=1)
    busy = [(x, y) for x, y, _ in events_of(p)]
    d = d0 - timedelta(days=2)
    while d <= d1 + timedelta(days=2):
        for days, fs, fe, _ in p.focus:
            if d.weekday() in days:
                busy.append((datetime.combine(d, fs, tzinfo=p.tz).astimezone(UTC), datetime.combine(d, fe, tzinfo=p.tz).astimezone(UTC)))
        d += timedelta(days=1)
    gap = timedelta(minutes=15) if p.buffer_after_long else timedelta(0)
    for x, y, who, room in booked:
        if p.name in who:
            busy.append((x - gap, y + gap))
    return subtract(free, busy)


def earliest_intervals(w, req, booked=()):
    who = [p for p in w.people if p.name in req['who']]
    free = None
    for p in who:
        f = free_of(w, p, booked)
        free = f if free is None else intersect(free, f)
    if req['room']:
        rb = [(lon_to_utc(d, s), lon_to_utc(d, e)) for d, s, e, _ in w.room] + [(x, y) for x, y, wh, room in booked if room]
        free = subtract(free, rb)
    d0, d1 = w.win
    lo = lon_to_utc(d0, time(0, 0))
    if req.get('not_before'):
        lo = max(lo, lon_to_utc(*req['not_before']))
    hi = lon_to_utc(d1 + timedelta(days=1), time(0, 0))
    dur = timedelta(minutes=req['dur'])
    for a, b in free:
        a = max(a, lo)
        # align to a London quarter hour
        la = a.astimezone(LON)
        mins = (la.minute % 15)
        if mins or la.second:
            a = a + timedelta(minutes=15 - mins) - timedelta(seconds=la.second)
        if a + dur <= min(b, hi):
            return a
    return None


def solve(w, careless=False):
    booked = []
    out = {}
    for r in w.requests:
        t = earliest_scan(w, r, careless, booked if (w.sequential and not careless) else [])
        out[r['label']] = t
        if t is not None:
            booked.append((t, t + timedelta(minutes=r['dur']), r['who'], r['room']))
    return out


def solve2(w):
    booked = []
    out = {}
    for r in w.requests:
        t = earliest_intervals(w, r, booked if w.sequential else [])
        out[r['label']] = t
        if t is not None:
            booked.append((t, t + timedelta(minutes=r['dur']), r['who'], r['room']))
    return out


def lon_str(u):
    if u is None:
        return None
    return u.astimezone(LON).strftime('%Y-%m-%d %H:%M')


def careless_str(u):
    if u is None:
        return None
    return u.replace(tzinfo=None).strftime('%Y-%m-%d %H:%M')  # the careless reader treats London as UTC all year


# ── generation ─────────────────────────────────────────────────────────────

TITLES = ['Weekly ops sync', 'Client call', '1:1 with line manager', 'Budget review', 'Interview panel', 'Vendor demo', 'Team stand-up', 'Training',
          'Board prep', 'Recruitment screen', 'Design review', 'Pipeline review', 'Quarterly planning', 'Customer workshop']
INPERSON = [('Site visit', 45), ('Lunch with a client across town', 30), ('Dentist', 20), ('Supplier visit', 40), ('Workshop at the partner office', 30)]


def qtime(h, m):
    return time(h, m)


def rand_block(rng, hours, lo=30, hi=120):
    s0 = hours[0].hour * 60 + hours[0].minute
    e0 = hours[1].hour * 60 + hours[1].minute
    dur = rng.choice([x for x in (30, 45, 60, 90, 120) if lo <= x <= hi])
    st = rng.randrange(s0, e0 - dur + 1, 15)
    st -= st % 15
    return time(st // 60, st % 60), time((st + dur) // 60, (st + dur) % 60)


def gen(seed, tier, cfg):
    rng = random.Random(seed)
    w = World()
    w.win = cfg['win']
    w.offsets_given = tier == 'easy'
    w.sequential = tier == 'hard'
    names = people(rng, len(cfg['cities']))
    for name, city in zip(names, cfg['cities']):
        if city in ('London', 'Lisbon', 'Warsaw', 'Johannesburg'):
            hrs = rng.choice([(time(9, 0), time(17, 30)), (time(8, 30), time(17, 0)), (time(9, 30), time(18, 0))])
        elif city in ('New York', 'Toronto'):
            hrs = rng.choice([(time(8, 0), time(16, 30)), (time(7, 30), time(16, 0)), (time(8, 30), time(17, 0))])
        elif city == 'Sydney':
            hrs = rng.choice([(time(7, 0), time(15, 30)), (time(15, 0), time(21, 0))]) if tier != 'easy' else (time(16, 0), time(21, 0))
            if hrs[0] == time(7, 0):
                hrs = (time(17, 0), time(21, 30))
        elif city == 'Singapore':
            hrs = rng.choice([(time(10, 0), time(19, 0)), (time(11, 0), time(20, 0))])
        elif city == 'Mumbai':
            hrs = (time(11, 0), time(19, 30))
        else:  # Dubai
            hrs = (time(9, 0), time(18, 0))
        days = (0, 1, 2, 3, 4) if city != 'Dubai' else (0, 1, 2, 3, 4)
        p = Person(name, city, hrs, days)
        w.people.append(p)
    # local working days for each person covering the window
    for p in w.people:
        d = w.win[0] - timedelta(days=1)
        while d <= w.win[1] + timedelta(days=1):
            if d.weekday() < 5:
                if p.city in HOLIDAYS.get(d, ()):
                    p.off.append(d)
                else:
                    k = rng.choice(cfg['events_per_day'])
                    for _ in range(k * 3):
                        if sum(1 for e in p.events if e[0] == d) >= k:
                            break
                        s, e = rand_block(rng, p.hours)
                        if free_slot(p, d, s, e, 0):
                            p.events.append((d, s, e, rng.choice(TITLES), 0))
            d += timedelta(days=1)
        if tier != 'easy' and rng.random() < 0.6:
            fs, fe = rand_block(rng, p.hours, 60, 120)
            days = set(rng.sample(range(5), rng.choice([2, 3, 5])))
            p.focus.append((days, fs, fe, 'Focus time (no meetings)'))
    # in-person appointments with travel time
    placed = 0
    for _ in range(60):
        if placed >= cfg['inperson']:
            break
        p = rng.choice(w.people)
        d = rng.choice([x for x in daterange(w.win[0], w.win[1]) if x.weekday() < 5 and x not in p.off])
        s, e = rand_block(rng, p.hours, 45, 90)
        t, tr = rng.choice(INPERSON)
        if free_slot(p, d, s, e, tr):
            p.events.append((d, s, e, t + ' (in person)', tr))
            placed += 1
    # room bookings (London time), never double-booked
    for d in daterange(w.win[0], w.win[1]):
        if d.weekday() < 5 and d not in HOLIDAYS:
            want = rng.choice(cfg['room_per_day'])
            for _ in range(want * 4):
                if sum(1 for r in w.room if r[0] == d) >= want:
                    break
                s, e = rand_block(rng, (time(8, 0), time(18, 0)), 60, 120)
                if all(not (r[0] == d and s < r[2] and r[1] < e) for r in w.room):
                    w.room.append((d, s, e, rng.choice(['Finance team', 'All-hands rehearsal', 'Interviews', 'Sales training', 'Board meeting'])))
    if tier == 'hard':
        rng.choice(w.people).buffer_after_long = True
    return w, rng


def mins(t):
    return t.hour * 60 + t.minute


def free_slot(p, d, s, e, travel):
    """No overlap with the person's other entries on that local date (travel time included)."""
    a, b = mins(s) - travel, mins(e) + travel
    for x in p.events:
        if x[0] == d and a < mins(x[2]) + x[4] and mins(x[1]) - x[4] < b:
            return False
    return True


def daterange(a, b):
    d = a
    while d <= b:
        yield d
        d += timedelta(days=1)


def add_update(w, rng, careless_ans, true_reqs):
    """A later message from one attendee that changes the answer of at least one request when applied."""
    cands = []
    for p in w.people:
        for e in p.events:
            if e[4] == 0:
                cands.append((p, e))
    rng.shuffle(cands)
    for p, e in cands[:40]:
        kind = rng.choice(['remove', 'move', 'add'])
        if kind == 'remove':
            p.update = dict(kind='remove', event=e)
        elif kind == 'move':
            s, en = rand_block(rng, p.hours, 30, 90)
            nd = e[0] + timedelta(days=rng.choice([0, 1]))
            others = [x for x in p.events if x is not e]
            if nd.weekday() >= 5 or nd in p.off or not all(not (x[0] == nd and mins(s) < mins(x[2]) + x[4] and mins(x[1]) - x[4] < mins(en)) for x in others):
                continue
            p.update = dict(kind='move', event=e, new=(nd, s, en, e[3], 0))
        else:
            d = rng.choice([x for x in daterange(w.win[0], w.win[1]) if x.weekday() < 5 and x not in p.off])
            s, en = rand_block(rng, p.hours, 60, 120)
            if not free_slot(p, d, s, en, 0):
                continue
            p.update = dict(kind='add', new=(d, s, en, rng.choice(['Customer escalation call', 'Covering for a colleague', 'Doctor appointment']), 0))
        sol = solve(w)
        p.update_saved = p.update
        p.update = None
        before = solve(w)
        p.update = p.update_saved
        if any(sol[k] != before[k] for k in sol) and all(v is not None for v in sol.values()):
            return p
        p.update = None
    return None


CASES = [
    # id, seed, tier, cities, window (London dates), requests spec
    ('s01', 11, 'easy', dict(cities=['London', 'London', 'New York'], win=(date(2026, 6, 16), date(2026, 6, 18)), events_per_day=[1, 2, 2], inperson=0, room_per_day=[1])),
    ('s02', 12, 'easy', dict(cities=['London', 'Singapore', 'London'], win=(date(2026, 7, 7), date(2026, 7, 9)), events_per_day=[1, 2, 3], inperson=0, room_per_day=[1, 2])),
    ('s03', 13, 'easy', dict(cities=['London', 'Dubai', 'New York'], win=(date(2026, 9, 15), date(2026, 9, 17)), events_per_day=[1, 2], inperson=1, room_per_day=[1])),
    ('s04', 24, 'medium', dict(cities=['London', 'New York', 'Toronto', 'London'], win=(date(2026, 3, 9), date(2026, 3, 13)), events_per_day=[1, 2, 3], inperson=1, room_per_day=[1, 2])),
    ('s05', 25, 'medium', dict(cities=['London', 'Singapore', 'Mumbai', 'London'], win=(date(2026, 3, 26), date(2026, 4, 1)), events_per_day=[1, 2, 3], inperson=1, room_per_day=[1, 2])),
    ('s06', 26, 'medium', dict(cities=['Lisbon', 'New York', 'London', 'Johannesburg'], win=(date(2026, 10, 26), date(2026, 10, 30)), events_per_day=[1, 2, 3], inperson=2, room_per_day=[1, 2])),
    ('s07', 37, 'hard', dict(cities=['London', 'Sydney', 'Singapore', 'London', 'Mumbai'], win=(date(2026, 3, 30), date(2026, 4, 8)), events_per_day=[1, 2, 3], inperson=2, room_per_day=[1, 2, 3])),
    ('s08', 38, 'hard', dict(cities=['London', 'New York', 'Warsaw', 'Johannesburg', 'London'], win=(date(2026, 10, 22), date(2026, 10, 30)), events_per_day=[1, 2, 3], inperson=2, room_per_day=[1, 2, 3])),
    ('s09', 39, 'hard', dict(cities=['London', 'Toronto', 'Dubai', 'Mumbai', 'Lisbon', 'London'], win=(date(2026, 3, 26), date(2026, 4, 3)), events_per_day=[1, 2, 3], inperson=2, room_per_day=[1, 2, 3])),
]


def make_requests(w, rng, tier):
    names = [p.name for p in w.people]
    lon = [p.name for p in w.people if p.city == 'London']
    reqs = []
    sizes = {'easy': [2, 3, 2, 3], 'medium': [3, 2, 4, 3], 'hard': [len(names), 3, 3, 2]}[tier]
    durs = {'easy': [30, 45, 60, 30], 'medium': [45, 60, 30, 90], 'hard': [60, 90, 45, 30]}[tier]
    for i, (k, dur) in enumerate(zip(sizes, durs)):
        who = sorted(rng.sample(names, k), key=names.index)
        room = bool(lon) and any(n in lon for n in who) and (i == 1 or (tier == 'hard' and i == 0))
        nb = None
        if i == 2 and tier != 'easy':
            d = w.win[0] + timedelta(days=1)
            while d.weekday() >= 5:
                d += timedelta(days=1)
            nb = (d, time(12, 0))
        reqs.append(dict(label='abcd'[i], who=who, dur=dur, room=room, not_before=nb))
    return reqs


def build_case(cid, seed, tier, cfg):
    for attempt in range(400):
        w, rng = gen(seed * 1000 + attempt, tier, cfg)
        w.requests = make_requests(w, rng, tier)
        base = solve(w)
        if any(v is None for v in base.values()):
            continue
        if tier != 'easy':
            p = add_update(w, rng, None, None)
            if p is None:
                continue
        sol = solve(w)
        sol2 = solve2(w)
        if sol != sol2:
            raise AssertionError(f"{cid}: solvers disagree {sol} {sol2}")
        if any(v is None for v in sol.values()):
            continue
        care = solve(w, careless=True)
        diff = sum(1 for k in sol if careless_str(care[k]) != lon_str(sol[k]))
        need = {'easy': 0, 'medium': 2, 'hard': 4}[tier]
        if len(set(sol.values())) < 3 or len(sol) != 4:
            continue
        if diff < need:
            continue
        # not trivially the first moment of the window
        if any(lon_str(sol[k]).endswith('00:00') for k in sol):
            continue
        return w, sol, care, attempt
    raise AssertionError(f"{cid}: no suitable world")


def fmt_t(t):
    return t.strftime('%H:%M')


def render_person(w, p):
    lines = []
    off = ''
    if w.offsets_given:
        offs = set()
        for d in daterange(w.win[0] - timedelta(days=1), w.win[1] + timedelta(days=1)):
            offs.add(datetime.combine(d, time(12), tzinfo=p.tz).utcoffset().total_seconds() / 3600)
        off = ' — ' + ', '.join(fmt_off(h) for h in sorted(offs)) + ' all this week'
    days = 'Mon–Fri'
    late = ' (a late shift, to overlap with Europe)' if p.hours[0] >= time(15, 0) else ''
    lines.append(f"{p.name} — {p.city}{off}. Works {fmt_t(p.hours[0])}–{fmt_t(p.hours[1])} {days}, local time{late}.")
    if p.off:
        for d in p.off:
            if w.win[0] - timedelta(days=1) <= d <= w.win[1] + timedelta(days=1):
                lines.append(f"  - {uk(d)}: public holiday, not working")
    for days, fs, fe, label in p.focus:
        dn = 'every weekday' if len(days) == 5 else ', '.join(DOW_LONG[i] + 's' for i in sorted(days))
        lines.append(f"  - {label}: {fmt_t(fs)}–{fmt_t(fe)} {dn}")
    for d, s, e, title, travel in sorted(p.events, key=lambda x: (x[0], x[1])):
        tr = f" — needs {travel} min travel before and after" if travel else ''
        lines.append(f"  - {uk(d)} {fmt_t(s)}–{fmt_t(e)} {title}{tr}")
    if p.buffer_after_long:
        lines.append("  - Needs at least 15 minutes between any two of the meetings booked in this request (a, b, c, d) that they attend.")
    return '\n'.join(lines)


def describe_update(p):
    u = p.update
    if u['kind'] == 'remove':
        d, s, e, t, _ = u['event']
        return f"\"Quick update: my {t} on {uk(d)} ({fmt_t(s)}–{fmt_t(e)}) has been cancelled, so I'm free then.\""
    if u['kind'] == 'move':
        d, s, e, t, _ = u['event']
        nd, ns, ne, _, _ = u['new']
        return f"\"Quick update: my {t} on {uk(d)} ({fmt_t(s)}–{fmt_t(e)}) has moved to {uk(nd)} {fmt_t(ns)}–{fmt_t(ne)}.\""
    nd, ns, ne, t, _ = u['new']
    return f"\"Quick update: I've had to add a {t} on {uk(nd)} {fmt_t(ns)}–{fmt_t(ne)}, so please avoid that.\""


def prompt(w, tier):
    d0, d1 = w.win
    lines = [f"I need to book four meetings for our team and I'd like the earliest possible slot for each. The booking window is {uky(d0)} to {uky(d1)} (London dates)."]
    lines.append("")
    lines.append("Calendars (every time is the person's own local time on their own local date):")
    for p in w.people:
        lines.append(render_person(w, p))
    lines.append("")
    lines.append("Fleet Room (London office) bookings, London time:")
    for d, s, e, who in sorted(w.room, key=lambda x: (x[0], x[1])):
        lines.append(f"  - {uk(d)} {fmt_t(s)}–{fmt_t(e)} {who}")
    lines.append("")
    rules = ["Rules:",
             "1. A meeting must fit entirely inside every attendee's working hours on a day they work (local time).",
             "2. It must not overlap any attendee's calendar entry or focus time. For an in-person entry it must also not overlap the travel time before and after it.",
             "3. No meeting may run past 12:00 on a Friday in any attendee's local time (company rule: no meetings on Friday afternoons).",
             "4. A meeting marked \"in the Fleet Room\" needs the room to be free for the whole meeting.",
             "5. Meetings start on the hour or at :15, :30 or :45 London time."]
    if w.sequential:
        rules.append("6. Book the meetings in order (a, then b, then c, then d). Once a meeting is booked it is in its attendees' calendars and, if it uses the Fleet Room, in the room's bookings, so later meetings must not overlap it.")
    else:
        rules.append("6. Treat each meeting on its own: they do not block each other.")
    lines += rules
    lines.append("")
    if w.offsets_given:
        lines.append("The UTC offset of each person is shown next to their city; London is UTC+1 (BST) all this week.")
    else:
        lines.append(DST_RULES)
    lines.append("")
    lines.append("Meetings to book:")
    for r in w.requests:
        extra = ' in the Fleet Room (London attendees in the room, everyone else by video)' if r['room'] else ' (video call)'
        nb = f", not starting before {uk(r['not_before'][0])} {fmt_t(r['not_before'][1])} London time" if r.get('not_before') else ''
        lines.append(f"  ({r['label']}) {r['dur']} minutes with {', '.join(r['who'])}{extra}{nb}.")
    ups = [p for p in w.people if p.update]
    if ups:
        p = ups[0]
        lines.append("")
        lines.append(f"Message from {p.name}, received after the calendars above were exported: {describe_update(p)}")
    lines.append("")
    lines.append("For each meeting give the earliest start that satisfies every rule, as a London date and time (24-hour clock, London local time on that date). "
                 "Answer with one JSON object exactly in this shape:\n{\"a\": \"YYYY-MM-DD HH:MM\", \"b\": \"YYYY-MM-DD HH:MM\", \"c\": \"YYYY-MM-DD HH:MM\", \"d\": \"YYYY-MM-DD HH:MM\"}\n"
                 "Each meeting is marked on its start time, right or wrong. Reply with the JSON object only (a ```json code block is fine). "
                 "Give one time per meeting: a field with two times, a range or \"either\" is marked wrong, and if you give more than one JSON answer only the last one counts.")
    return '\n'.join(lines)


def make_cases():
    out = []
    for cid, seed, tier, cfg in CASES:
        w, sol, care, attempt = build_case(cid, seed, tier, cfg)
        exp = {k: lon_str(v) for k, v in sol.items()}
        careless = {k: careless_str(v) for k, v in care.items()}
        # the easy tier states London's offset as UTC+1 all week, which the generator relies on
        if w.offsets_given:
            for d in daterange(w.win[0], w.win[1]):
                assert datetime.combine(d, time(12), tzinfo=LON).utcoffset() == timedelta(hours=1)
        out.append(dict(id=cid, tier=tier, w=w, prompt=prompt(w, tier), expected=exp, careless=careless, attempt=attempt))
    return out


if __name__ == '__main__':
    cs = make_cases()
    for c in cs:
        print(c['id'], c['tier'], 'attempt', c['attempt'], len(c['prompt']), 'chars', c['expected'], 'careless', c['careless'])
    print(cs[6]['prompt'])
