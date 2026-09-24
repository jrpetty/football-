import json, sys, re
FIELD = r'(?:"(?:[^"]|"")*"|[^",]*)'
REC = re.compile(FIELD + r'(?:,' + FIELD + r')*', re.S)
def parse(s):
    if not REC.fullmatch(s):
        return None
    fields = []
    pos = 0
    fre = re.compile(FIELD, re.S)
    while True:
        m = fre.match(s, pos)
        tok = m.group(0)
        if tok.startswith('"'):
            fields.append(tok[1:-1].replace('""', '"'))
        else:
            fields.append(tok)
        pos = m.end()
        if pos == len(s):
            return fields
        assert s[pos] == ','
        pos += 1
bad = 0
for f in sys.argv[1:]:
    for t in json.load(open(f))['tests']:
        got = parse(t['args'][0])
        if got != t['expected']:
            bad += 1; print('MISMATCH', repr(t['args'][0][:80]), got if got is None else got[:5], t['expected'] if t['expected'] is None else t['expected'][:5])
print('python cross-check', 'FAILED' if bad else 'OK')
sys.exit(1 if bad else 0)
