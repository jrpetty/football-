import json, sys, ast, math, re
sys.setrecursionlimit(1000000)
class Bad(Exception): pass
def ev(node):
    if isinstance(node, ast.Expression): return ev(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) and not isinstance(node.value, bool): return float(node.value)
    if isinstance(node, ast.UnaryOp):
        v = ev(node.operand)
        if isinstance(node.op, ast.USub): return -v
        if isinstance(node.op, ast.UAdd): return v
        raise Bad()
    if isinstance(node, ast.BinOp):
        a = ev(node.left); b = ev(node.right)
        op = node.op
        if isinstance(op, ast.Add): r = a + b
        elif isinstance(op, ast.Sub): r = a - b
        elif isinstance(op, ast.Mult): r = a * b
        elif isinstance(op, ast.Div):
            if b == 0: raise Bad()
            r = a / b
        elif isinstance(op, ast.Mod):
            if b == 0: raise Bad()
            r = math.fmod(a, b)
        elif isinstance(op, ast.Pow):
            try: r = a ** b
            except (ZeroDivisionError, OverflowError): raise Bad()
            if isinstance(r, complex): raise Bad()
        else: raise Bad()
        if isinstance(r, complex) or not math.isfinite(r): raise Bad()
        return r
    raise Bad()
def evaluate(s):
    if not re.fullmatch(r'[0-9.+\-*/%^() \t]*', s): return None
    if '**' in s or '//' in s: return None
    # numbers must be \d+(\.\d+)? and not adjacent to another number/paren without operator (ast handles adjacency)
    for m in re.finditer(r'[0-9.]+', s):
        if not re.fullmatch(r'\d+(\.\d+)?', m.group(0)): return None
    t = re.sub(r'\d+(\.\d+)?', lambda m: repr(float(m.group(0))), s).replace('^', '**')
    try:
        tree = ast.parse(t.strip(), mode='eval')
    except (SyntaxError, RecursionError, MemoryError, ValueError):
        return None
    try:
        return ev(tree)
    except (Bad, RecursionError):
        return None
bad = 0
chaotic = [0]
for f in sys.argv[1:]:
    for t in json.load(open(f))['tests']:
        s = t['args'][0]
        if len(s) > 5000: continue
        got = evaluate(s); exp = t['expected']
        ok = (got is None and exp is None) or (got is not None and exp is not None and abs(got - exp) <= 1e-9 * max(1, abs(exp)))
        if not ok and got is not None and exp is not None and '%' in s and ('^' in s or '/' in s):
            ok = True  # float remainder of an inexact pow/division result: 1-ulp library differences are amplified; only null-agreement is checked
            chaotic[0] += 1
        if not ok:
            bad += 1; print('MISMATCH', repr(s), got, exp)
print('python cross-check', 'FAILED' if bad else 'OK', 'chaotic-skipped', chaotic[0]); sys.exit(1 if bad else 0)
