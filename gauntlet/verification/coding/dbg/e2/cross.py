import json, sys
JS_WS = set('\t\n\v\f\r       　﻿') | {chr(c) for c in range(0x2000, 0x200b)}
def trunc(text, n):
    if len(text) <= n: return text
    if n < 1: return ''
    head = list(text[:n-1])
    while head and head[-1] in JS_WS: head.pop()
    return ''.join(head) + '…'
for t in json.load(open(sys.argv[1]))['tests']:
    assert trunc(*t['args']) == t['expected'], t['args'][0][:30]
print('python cross-check OK')
