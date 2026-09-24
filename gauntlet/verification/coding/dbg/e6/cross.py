import json, sys, posixpath
def norm(p):
    r = posixpath.normpath(p) if p != '' else '.'
    # posixpath keeps a leading '//' (POSIX implementation-defined); the spec collapses it
    if r.startswith('//'):
        r = '/' + r.lstrip('/')
    return r
bad = 0
for f in sys.argv[1:]:
    for t in json.load(open(f))['tests']:
        if norm(t['args'][0]) != t['expected']:
            bad += 1; print('MISMATCH', repr(t['args'][0]), norm(t['args'][0]), t['expected'])
print('python cross-check', 'FAILED' if bad else 'OK'); sys.exit(1 if bad else 0)
