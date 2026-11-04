"""The contract ladder's negotiation layer: version 2 is settled by the customer's response and the supplier's final note.

The model sees version 1, the supplier's version 2, the customer's response (accept, reject, "accepted, but with X",
general statements about a schedule with specific exceptions, a whole new section rejected) and the supplier's final
note (counter-proposals agreed or not, changes withdrawn, corrections that stand unless rejected). It lists the
changes between version 1 and the AGREED version, numbered as the agreed version is numbered: every rejected
insertion or kept deletion renumbers the clauses after it, so each answer depends on earlier settlement decisions.

  settle()   the engine: the agreed version built from version 2 (rejected changes reverted, kept deletions put back,
             agreed counter-proposals applied), then the key = contract.diff(version 1, agreed)
  predict()  the independent check: from version 2's own key and the statuses alone, the expected agreed key (numbers
             mapped to the agreed numbering, counter values substituted), which must equal settle()'s diff
"""
import random, re
import contract as K

UNIT_CHOICES = {'site': (1, 2, 4, 6, 8, 12), 'asset': (1, 2, 4, 6, 12), 'kpi': (85, 90, 95, 97.5, 98, 99), 'rate': (180, 210, 245, 260, 295, 320, 345, 380, 420)}
SCHEDULE_NAME = {'Schedule: Sites': 'Schedule 1 (Sites)', 'Schedule: Service levels': 'Schedule 2 (Service levels)', 'Schedule: Rate card': 'Schedule 3 (Rate card)', 'Schedule: Asset register': 'Schedule 4 (Asset register)'}


def identity_changes(b):
    v1, v2, texts = b['v1'], b['v2'], b['texts']
    k1 = {k for _, ks in v1.sections for k in ks}
    k2 = {k for _, ks in v2.sections for k in ks}
    out = []
    for k in sorted(k2 - k1):
        out.append(dict(kind='add', key=k))
    for k in sorted(k1 - k2):
        out.append(dict(kind='del', key=k))
    for k in sorted(k1 & k2):
        diff = [(kind, arg) for kind, arg in re.findall(r'\[(\w+):(\w+)\]', texts[k]) if kind != 'ref' and v1.params[arg] != v2.params[arg]]
        if diff:
            # a definition can change two parameters at once (09:00 to 17:30 -> 08:00 to 18:00): one change, all of them
            out.append(dict(kind='set', key=k, param=diff[0][1], unit=diff[0][0], params=sorted({a for _, a in diff})))
    return out


def section_of(version, key):
    return next(s for s, ks in version.sections if key in ks)


def counter_value(ch, b, rng):
    p, unit = ch['param'], ch['unit']
    if unit not in ('money', 'pct', 'days', 'months', 'years', 'hours', 'count') or len(ch['params']) > 1:
        return None
    pre = p.split('_')[0]
    choices = UNIT_CHOICES.get(pre) if pre in UNIT_CHOICES and p.endswith(('_v', '_t', '_r')) else K.CHANGEABLE.get(p, (None, None))[1]
    if not choices:
        return None
    opts = [c for c in choices if c not in (b['v1'].params[p], b['v2'].params[p])]
    return rng.choice(opts) if opts else None


def assign(b, R, seed):
    """Statuses: the customer's response and the supplier's note for every version-2 change."""
    rng = random.Random(seed)
    chs = identity_changes(b)
    for ch in chs:
        ch['sec'] = section_of(b['v2'] if ch['kind'] != 'del' else b['v1'], ch['key'])
    resp = {}  # change index -> ('accept'|'reject'|'counter', value)
    blanket = []  # (scope section or 'all', 'accept'|'reject')
    if R.get('accept_all'):
        blanket.append(('all', 'accept'))
    for sec in R.get('blanket', []):
        secs = [s for s in SCHEDULE_NAME if any(ch['sec'] == s for ch in chs)]
        if secs:
            pick = secs[min(len(secs) - 1, sec[0])]
            blanket.append((pick, sec[1]))
    new_sections = [s for s, _ in b['v2'].sections if s in (K.NEW_SECTION[0], 'Social Value')]
    section_reject = new_sections[:R.get('reject_sections', 0)]
    order = list(range(len(chs)))
    rng.shuffle(order)
    n = len(chs)
    quota = {k: int(round(R.get(k, 0) * n)) for k in ('reject', 'counter', 'none', 'withdraw', 'correction')}
    quota['reject'] = max(quota['reject'], R.get('min_reject', 0))
    withdrawn, corrections, notes = set(), set(), {}
    for i in order:
        ch = chs[i]
        if ch['sec'] in section_reject:
            continue
        covered = next((s for s in blanket if s[0] in ('all', ch['sec'])), None)
        if quota['counter'] and ch['kind'] == 'set':
            v = counter_value(ch, b, rng)
            if v is not None:
                resp[i] = ('counter', v)
                notes[i] = rng.random() < 0.55
                quota['counter'] -= 1
                continue
        if quota['reject'] and (ch['kind'] != 'set' or rng.random() < 0.6):
            resp[i] = ('reject', None)
            quota['reject'] -= 1
            continue
        if quota['none'] and not covered:
            quota['none'] -= 1
            if quota['correction'] and ch['kind'] == 'set':
                corrections.add(i)
                quota['correction'] -= 1
            continue
        if covered:
            # a specific item against the general statement, now and then
            if rng.random() < R.get('exceptions', 0):
                resp[i] = ('reject', None) if covered[1] == 'accept' else ('accept', None)
            continue
        resp[i] = ('accept', None)
    for i in order:
        if quota['withdraw'] and resp.get(i, ('',))[0] == 'accept':
            withdrawn.add(i)
            quota['withdraw'] -= 1
    # a correction the customer did reject (the exception to the exception)
    if R.get('correction', 0):
        rej = [i for i in order if resp.get(i, ('',))[0] == 'reject' and chs[i]['kind'] == 'set']
        if rej:
            corrections.add(rej[0])
    return dict(changes=chs, resp=resp, blanket=blanket, section_reject=section_reject, withdrawn=withdrawn, corrections=corrections, counter_ok=notes)


def effective(st, i, flags=frozenset()):
    """(agreed?, value) for change i."""
    ch = st['changes'][i]
    if i in st['withdrawn']:
        return False, None
    if ch['sec'] in st['section_reject']:
        return False, None
    r = st['resp'].get(i)
    general = next((s for s in st['blanket'] if s[0] in ('all', ch['sec'])), None)
    if 'blanket_wins' in flags and general and ch['sec'] == general[0]:
        r = (general[1], None)
    if r is None and general:
        r = (general[1], None)
    if r is None:
        return (i in st['corrections'] or 'unanswered_ok' in flags), None
    if r[0] == 'reject':
        return False, None
    if r[0] == 'counter':
        if st['counter_ok'][i] or 'counter_always' in flags:
            return True, r[1]
        return False, None
    return True, None


def settle(b, st, flags=frozenset()):
    v1, v2, texts = b['v1'], b['v2'], b['texts']
    secs = [(s, list(ks)) for s, ks in v2.sections]
    params = dict(v2.params)
    put_back = []
    for i, ch in enumerate(st['changes']):
        ok, val = effective(st, i, flags)
        if ch['kind'] == 'set':
            if not ok:
                for q in ch['params']:
                    params[q] = v1.params[q]
            elif val is not None:
                params[ch['param']] = val
        elif ch['kind'] == 'add' and not ok:
            for _, ks in secs:
                if ch['key'] in ks:
                    ks.remove(ch['key'])
        elif ch['kind'] == 'del' and not ok:
            put_back.append(ch)
    # Kept deletions go back in version-1 order, each after its nearest version-1 predecessor in that section.
    pos1 = {k: (i, j) for i, (_, ks) in enumerate(v1.sections) for j, k in enumerate(ks)}
    for ch in sorted(put_back, key=lambda c: pos1[c['key']]):
        s1 = ch['sec']
        v1keys = next(ks for s, ks in v1.sections if s == s1)
        before = v1keys[:v1keys.index(ch['key'])]
        target = next((ks for s, ks in secs if s == s1), None)
        if target is None:
            raise KeyError(f'section {s1} missing')
        pos = 0
        for p in reversed(before):
            if p in target:
                pos = target.index(p) + 1
                break
        target.insert(pos, ch['key'])
    secs = [(s, ks) for s, ks in secs if ks]
    agreed = K.Version(params, secs)
    title = "SERVICES AGREEMENT - agreed version"
    style = K.STYLE2
    tA, nA = K.render(agreed, texts, style, title)
    key = K.diff(v1, agreed, texts, b['n1'], nA)
    for c in key.values():
        for f in ('old', 'new'):
            if isinstance(c[f], float) and c[f].is_integer():
                c[f] = int(c[f])
    return dict(agreed=agreed, tA=tA, nA=nA, key=key)


def predict(b, st, s):
    """The agreed key from version 2's key and the statuses alone (no diff of the agreed version)."""
    inv2 = {v: k for k, v in b['n2'].items()}
    inv1 = {v: k for k, v in b['n1'].items()}
    out = {}
    by_key = {}
    for i, ch in enumerate(st['changes']):
        by_key[(ch['kind'], ch['key'])] = i
    for num, c in b['key'].items():
        if c['type'] == 'deleted':
            k = inv1[num]
            i = by_key[('del', k)]
        else:
            k = inv2[num]
            i = by_key[('add', k)] if c['type'] == 'added' else next(j for j, ch in enumerate(st['changes']) if ch['kind'] == 'set' and ch['key'] == k)
        ok, val = effective(st, i)
        if not ok:
            continue
        c = dict(c)
        if val is not None:
            c['new'] = int(val) if isinstance(val, float) and val.is_integer() else val
        if c['type'] == 'cross_reference':
            c['new'] = s['nA'][inv2[c['new']]]
        out[num if c['type'] == 'deleted' else s['nA'][k]] = c
    return out


def fmt_val(ch, v):
    return K.fmt_value(ch['unit'], v, K.STYLE2)


def render_responses(b, st):
    chs = st['changes']
    num = lambda ch: b['n1'][ch['key']] if ch['kind'] == 'del' else b['n2'][ch['key']]
    lines = []
    for scope, what in st['blanket']:
        where = 'every change in version 2' if scope == 'all' else f'every change to {SCHEDULE_NAME[scope]}'
        lines.append(f"We {what} {where}, except where an item below says otherwise.")
    for sec in st['section_reject']:
        n = next(i + 1 for i, (s, _) in enumerate(s for s in b['v2'].sections if not s[0].startswith('Schedule')) if s == sec)
        lines.append(f"New section {n} ({sec}): rejected, with all its clauses.")
    items = sorted(st['resp'].items(), key=lambda kv: K_order(num(chs[kv[0]])))
    rid = {}
    for i, (what, val) in items:
        ch = chs[i]
        label = f"The deletion of version 1's clause {num(ch)}" if ch['kind'] == 'del' else f"New clause {num(ch)}" if ch['kind'] == 'add' else f"Clause {num(ch)}"
        if what == 'counter':
            text = f"{label}: accepted, but with {fmt_val(ch, val)} instead of {fmt_val(ch, b['v2'].params[ch['param']])}."
        elif what == 'reject':
            text = f"{label}: rejected{' - keep it' if ch['kind'] == 'del' else ''}."
        else:
            text = f"{label}: accepted."
        lines.append(text)
        rid[i] = len(lines)
    lines.append('Any change in version 2 that this response does not accept is not agreed.')
    resp = '\n'.join(f"R{j + 1}. {t}" for j, t in enumerate(lines))
    notes = []
    for i, ok in sorted(st['counter_ok'].items(), key=lambda kv: rid[kv[0]]):
        notes.append(f"R{rid[i]}: {'we agree your figure.' if ok else 'we do not agree your figure, so that change is withdrawn.'}")
    for i in sorted(st['withdrawn'], key=lambda i: K_order(num(chs[i]))):
        notes.append(f"We withdraw our change to {'the deletion of version 1’s clause' if chs[i]['kind'] == 'del' else 'new clause' if chs[i]['kind'] == 'add' else 'clause'} {num(chs[i])}.")
    if st['corrections']:
        cs = sorted((num(chs[i]) for i in st['corrections']), key=K_order)
        notes.append(f"Our changes to clause{'s' if len(cs) > 1 else ''} {', '.join(cs)} only correct errors in version 1: they stand unless you rejected them.")
    note = '\n'.join(f"N{j + 1}. {t}" for j, t in enumerate(notes)) if notes else 'N1. Nothing further.'
    return resp, note


def K_order(num):
    a, b2 = num.split('.')
    return (1, int(a[1:]), int(b2)) if a.startswith('S') else (0, int(a), int(b2))


RULES = """HOW THE AGREED VERSION IS SETTLED
1. A change made in version 2 is agreed only if the customer accepted it - by a specific item, or by a general statement that no specific item overrides - and the supplier did not withdraw it in the final note. A specific item always beats a general statement.
2. "Accepted, but with X instead of Y" is a counter-proposal: the change is agreed with X only if the supplier agrees it in the final note; otherwise the change falls away.
3. Exception: a change that the supplier's note lists as a correction is agreed unless the customer rejected it, even if the customer did not mention it.
4. A change that is not agreed is not made: version 1's wording stands; a clause that version 2 deletes stays; a clause or section that version 2 adds is left out.
5. Everything else stays as version 2 has it (the order of sections, moved clauses, re-sorted schedules, wording that only changes presentation). The agreed version is numbered like the drafts: sections in order, clauses in order within each section, schedule rows S1.1, S1.2, ... A deleted clause that stays goes back into its version-1 section, straight after the nearest clause that came before it in version 1 and is in that section of the agreed version (at the start of the section if there is none)."""


def prompt(b, st):
    base = K.prompt(b)
    reps = [
        ("Our supplier has sent back a revised draft of our services agreement. Before I sign, tell me exactly what they changed.",
         "Our supplier sent back a revised draft (version 2) of our services agreement; we answered it and they sent a final note. Tell me exactly what has changed from version 1 in the AGREED version."),
        ("List every SUBSTANTIVE change between version 1 and version 2", "List every SUBSTANTIVE change between version 1 and the AGREED version (settled by the rules, the response and the note below)"),
        ("- the clause number in VERSION 2 (for a deleted clause, its number in version 1);", "- the clause number in the AGREED version (for a clause the agreed version deletes, its number in version 1);"),
        ("(the clause number written in version 1 and the one written in version 2)", "(the clause number written in version 1 and the one it has in the agreed version)"),
    ]
    for a, r in reps:
        assert a in base, a[:40]
        base = base.replace(a, r)
    resp, note = render_responses(b, st)
    cut = base.index('\n\nAnswer with one JSON object')
    return base[:cut] + f"\n\nCUSTOMER'S RESPONSE TO VERSION 2 (clause numbers are version 2's; for a clause that version 2 deletes, version 1's number):\n{resp}\n\nSUPPLIER'S FINAL NOTE:\n{note}\n\n{RULES}" + base[cut:]
