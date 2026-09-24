s=open('h2/gen.mjs').read()
s=s.replace("""add('(a|aa)*c', 'a'.repeat(5000)); add('(a?){30}a{30}'.replace('{30}', '').replace('{30}', ''), 'aaaa');""","""add('(a|aa)*c', 'a'.repeat(5000)); add('(a?)'.repeat(25) + 'a'.repeat(25), 'a'.repeat(25));""")
lines=[l for l in s.split('\n') if 'naive backtracking' not in l]
s='\n'.join(lines)
open('h2/gen.mjs','w').write(s)
c=open('h2/cross.py').read()
c=c.replace("""        if len(text) > 3000 and ('*)*' in pat or ')*)*' in pat):  # python backtracking would be exponential on the pathological tests
            continue""","""        if len(text) > 20 and pat in PATHO:
            if PATHO[pat](text) != t['expected']:
                bad += 1; print('MISMATCH (manual)', pat, t['expected'])
            continue""")
c=c.replace("bad = 0\n","""bad = 0
# Pathological patterns that make Python's backtracking engine exponential; expected values follow directly from the pattern.
PATHO = {
    '((a*)*)*b': lambda s: set(s[:-1]) <= {'a'} and s.endswith('b'),
    '(a|aa)*c': lambda s: set(s[:-1]) <= {'a'} and s.endswith('c'),
    '(a?)' * 25 + 'a' * 25: lambda s: set(s) <= {'a'} and 25 <= len(s) <= 50,
    '(a|b|ab|ba)*(c|d)*': lambda s: re.fullmatch(r'[ab]*[cd]*', s) is not None,
    '.*.*.*.*.*.*.*.*.*.*x': lambda s: s.endswith('x'),
}
""",1)
open('h2/cross.py','w').write(c)
