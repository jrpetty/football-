"""Horizon: Run It In Your Head (horizon.mind-runner).

Generates deterministic JavaScript programs from a seed at ten difficulty levels. Every program is
rendered twice from one syntax tree: once as JavaScript (what the model sees) and once as Python.
The answer key is the number the program prints, obtained by actually running BOTH renderings
(`node` and `python3`) and asserting they agree. The level of a program is set by how many
statements it executes (measured with an instrumented Python rendering), so level N is strictly
more work than level N-1.

All arithmetic stays in 0..65535 (every assignment is masked with `& 65535`), so there is no
overflow, no floating point and no language-specific behaviour to trip over: a patient human with
pencil and paper can execute every program exactly.

Run: python3 mind_runner.py            (prints a table and writes mind_runner_cases.json)
"""
from __future__ import annotations

import json
import os
import random
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
MASK = 65535

# Level -> (target executed statements, lower, upper). Roughly doubles per level.
TARGETS = {1: 160, 2: 320, 3: 640, 4: 1280, 5: 2560, 6: 5120, 7: 10240, 8: 20480, 9: 40960, 10: 81920}


# ─── syntax tree ─────────────────────────────────────────────────────────────
# Expressions: ('num', k) | ('var', name) | ('idx', arr, expr, n) | ('bin', op, l, r) | ('call', f, [args])
# Conditions:  ('cmp', op, l, r) | ('and', c1, c2)
# Statements:  ('assign', var, expr) | ('aset', arr, idx, expr, n) | ('for', i, lo, hi, body)
#              ('if', cond, then, else) | ('while', cond, body) | ('swap', arr, e1, e2, n)
#              ('ret', expr) | ('let', var, expr)


def js_expr(e) -> str:
    t = e[0]
    if t == 'num':
        return str(e[1])
    if t == 'var':
        return e[1]
    if t == 'idx':
        return f"{e[1]}[{js_expr(e[2])} % {e[3]}]"
    if t == 'bin':
        return f"({js_expr(e[2])} {e[1]} {js_expr(e[3])})"
    if t == 'call':
        return f"{e[1]}({', '.join(js_expr(a) for a in e[2])})"
    raise ValueError(t)


def py_expr(e) -> str:
    t = e[0]
    if t == 'num':
        return str(e[1])
    if t == 'var':
        return e[1]
    if t == 'idx':
        return f"{e[1]}[{py_expr(e[2])} % {e[3]}]"
    if t == 'bin':
        return f"({py_expr(e[2])} {e[1]} {py_expr(e[3])})"
    if t == 'call':
        return f"{e[1]}({', '.join(py_expr(a) for a in e[2])})"
    raise ValueError(t)


def js_cond(c) -> str:
    if c[0] == 'and':
        return f"{js_cond(c[1])} && {js_cond(c[2])}"
    op = {'==': '===', '!=': '!=='}.get(c[1], c[1])
    return f"{js_expr(c[2])} {op} {js_expr(c[3])}"


def py_cond(c) -> str:
    if c[0] == 'and':
        return f"{py_cond(c[1])} and {py_cond(c[2])}"
    return f"{py_expr(c[2])} {c[1]} {py_expr(c[3])}"


def is_masked(e) -> bool:
    return e[0] == 'bin' and e[1] == '&' and e[3] == ('num', MASK)


def masked_js(e) -> str:
    if is_masked(e):
        e = e[2]
    s = js_expr(e)
    if e[0] == 'bin':
        s = s[1:-1]
    return f"({s}) & {MASK}" if e[0] in ('bin', 'call') else s


def masked_py(e) -> str:
    if is_masked(e):
        e = e[2]
    s = py_expr(e)
    if e[0] == 'bin':
        s = s[1:-1]
    return f"({s}) & {MASK}" if e[0] in ('bin', 'call') else s


def js_stmts(stmts, ind: int, out: list[str]):
    p = '  ' * ind
    for s in stmts:
        t = s[0]
        if t == 'let':
            out.append(f"{p}let {s[1]} = {masked_js(s[2])};")
        elif t == 'assign':
            out.append(f"{p}{s[1]} = {masked_js(s[2])};")
        elif t == 'aset':
            out.append(f"{p}{s[1]}[{js_expr(s[2])} % {s[4]}] = {masked_js(s[3])};")
        elif t == 'swap':
            a, n = s[1], s[4]
            out.append(f"{p}{{ const p = {js_expr(s[2])} % {n}, q = {js_expr(s[3])} % {n}; const tmp = {a}[p]; {a}[p] = {a}[q]; {a}[q] = tmp; }}")
        elif t == 'for':
            out.append(f"{p}for (let {s[1]} = {s[2]}; {s[1]} < {s[3]}; {s[1]}++) {{")
            js_stmts(s[4], ind + 1, out)
            out.append(f"{p}}}")
        elif t == 'if':
            out.append(f"{p}if ({js_cond(s[1])}) {{")
            js_stmts(s[2], ind + 1, out)
            if s[3]:
                out.append(f"{p}}} else {{")
                js_stmts(s[3], ind + 1, out)
            out.append(f"{p}}}")
        elif t == 'while':
            out.append(f"{p}while ({js_cond(s[1])}) {{")
            js_stmts(s[2], ind + 1, out)
            out.append(f"{p}}}")
        elif t == 'ret':
            out.append(f"{p}return {masked_js(s[1])};")
        else:
            raise ValueError(t)


def py_stmts(stmts, ind: int, out: list[str], count: bool):
    p = '    ' * ind
    c = f"{p}_n[0] += 1" if count else None
    if not stmts:
        out.append(f"{p}pass")
    for s in stmts:
        t = s[0]
        if t in ('let', 'assign'):
            if c:
                out.append(c)
            out.append(f"{p}{s[1]} = {masked_py(s[2])}")
        elif t == 'aset':
            if c:
                out.append(c)
            out.append(f"{p}{s[1]}[{py_expr(s[2])} % {s[4]}] = {masked_py(s[3])}")
        elif t == 'swap':
            if c:
                out.append(c)
            a, n = s[1], s[4]
            out.append(f"{p}_p = {py_expr(s[2])} % {n}; _q = {py_expr(s[3])} % {n}; {a}[_p], {a}[_q] = {a}[_q], {a}[_p]")
        elif t == 'for':
            out.append(f"{p}for {s[1]} in range({s[2]}, {s[3]}):")
            py_stmts(s[4], ind + 1, out, count)
        elif t == 'if':
            if c:
                out.append(c)
            out.append(f"{p}if {py_cond(s[1])}:")
            py_stmts(s[2], ind + 1, out, count)
            if s[3]:
                out.append(f"{p}else:")
                py_stmts(s[3], ind + 1, out, count)
        elif t == 'while':
            out.append(f"{p}while {py_cond(s[1])}:")
            py_stmts(s[2], ind + 1, out, count)
        elif t == 'ret':
            if c:
                out.append(c)
            out.append(f"{p}return {masked_py(s[1])}")
        else:
            raise ValueError(t)


# ─── random program generator ────────────────────────────────────────────────

class Gen:
    """Programs whose work cannot be skipped.

    Every loop iteration feeds the next: a 16-bit xorshift register `s`, a linear congruential counter `t`,
    array cells chosen by those registers, and an accumulator hash. There are no fixed points to spot, no
    dead code and no loops that cancel out, so the only way to the printed number is to execute every
    statement. Levels differ in how many statements run (and in program length).
    """

    def __init__(self, rng: random.Random, level: int):
        self.r = rng
        self.level = level
        self.scalars = ['s', 't', 'acc', 'u']
        self.arrays: dict[str, int] = {}
        self.funcs: list[tuple[str, int]] = []

    def V(self, name):
        return ('var', name)

    def N(self, k):
        return ('num', k)

    def B(self, op, l, r):
        return ('bin', op, l, r)

    def idx(self, arr, e):
        return ('idx', arr, e, self.arrays[arr])

    def xorshift(self, v):
        a, b, c = self.r.choice([(7, 9, 8), (5, 3, 7), (6, 7, 13), (7, 5, 3), (3, 7, 11), (9, 5, 6)])
        return [('assign', v, self.B('^', self.V(v), self.B('<<', self.V(v), self.N(a)))),
                ('assign', v, self.B('^', self.V(v), self.B('>>', self.V(v), self.N(b)))),
                ('assign', v, self.B('^', self.V(v), self.B('<<', self.V(v), self.N(c))))]

    def lcg(self, v):
        m, k = self.r.choice([(75, 74), (109, 89), (37, 113), (61, 7), (93, 41), (29, 211)])
        mod = self.r.choice([65521, 65519, 65497])
        return [('assign', v, self.B('%', self.B('+', self.B('*', self.V(v), self.N(m)), self.N(k)), self.N(mod)))]

    def menu_item(self, loopvar, arrs):
        r = self.r.random()
        arr = self.r.choice(arrs)
        reg = self.r.choice(['s', 't'])
        other = 't' if reg == 's' else 's'
        if r < 0.16:
            return [('aset', arr, self.V(reg), self.B('+', self.B('+', self.idx(arr, self.V(reg)), self.V(other)), self.V(loopvar)), self.arrays[arr])]
        if r < 0.30:
            return [('assign', 'acc', self.B('+', self.B('*', self.V('acc'), self.N(self.r.choice([31, 33, 37, 5, 17]))), self.idx(arr, self.V(loopvar))))]
        if r < 0.40:
            return [('assign', 'acc', self.B('^', self.V('acc'), self.B('>>', self.V(reg), self.N(self.r.randint(1, 4)))))]
        if r < 0.55:
            bit = self.r.choice([1, 2, 4, 8])
            then = [('assign', 'u', self.B('+', self.V('u'), self.idx(arr, self.V(other))))]
            els = [('assign', 'acc', self.B('+', self.V('acc'), self.B('^', self.V('s'), self.V('t'))))]
            return [('if', ('cmp', '==', self.B('&', self.V(reg), self.N(bit)), self.N(0)), then, els)]
        if r < 0.65:
            return [('swap', arr, self.V('s'), self.V('t'), self.arrays[arr])]
        if r < 0.78 and self.funcs:
            f, k = self.r.choice(self.funcs)
            args = [self.V(reg), self.V('acc')][:k]
            return [('assign', 'u', ('call', f, args))]
        if r < 0.88 and loopvar == 'i':
            j_hi = self.r.randint(2, 4)
            body = [('assign', 'acc', self.B('+', self.V('acc'), self.B('*', self.idx(arr, self.B('+', self.V(reg), self.V('j'))), self.B('+', self.V('j'), self.N(1)))))]
            if self.r.random() < 0.5:
                body.append(('aset', arr, self.B('+', self.V('j'), self.V('u')), self.B('^', self.idx(arr, self.B('+', self.V('j'), self.V('u'))), self.V('acc')), self.arrays[arr]))
            return [('for', 'j', 0, j_hi, body)]
        return [('assign', 'u', self.B('^', self.B('+', self.V('u'), self.N(self.r.randint(1, 97))), self.V(reg)))]

    def function(self, name):
        arity = self.r.randint(1, 2)
        params = ['x', 'y'][:arity]
        body = [('let', 'r', self.B('^', self.B('*', self.V('x'), self.N(self.r.choice([3, 5, 7, 11, 13]))), self.N(self.r.randint(100, 60000))))]
        if arity == 2:
            body.append(('assign', 'r', self.B('+', self.V('r'), self.B('>>', self.V('y'), self.N(self.r.randint(1, 5))))))
        # r > 20000 before the subtraction of at most 19999, so it stays non-negative
        body.append(('if', ('cmp', '>', self.V('r'), self.N(self.r.randint(20000, 45000))),
                     [('assign', 'r', self.B('-', self.V('r'), self.N(self.r.randint(1000, 19999))))], []))
        body.append(('ret', self.B('^', self.V('r'), self.B('<<', self.V('r'), self.N(self.r.randint(2, 6))))))
        return (name, params, body, arity)

    def program(self):
        L = self.level
        nfuncs = 0 if L < 3 else (1 if L < 6 else 2)
        funcs = []
        for fi in range(nfuncs):
            f = self.function(['mix', 'fold'][fi])
            funcs.append(f)
            self.funcs.append((f[0], f[3]))
        decls = [('let', 's', self.N(self.r.randint(1, 65535))), ('let', 't', self.N(self.r.randint(1, 65000))),
                 ('let', 'acc', self.N(self.r.randint(0, 999))), ('let', 'u', self.N(self.r.randint(0, 999)))]
        arrays = []
        narr = 1 if L < 4 else 2
        for ai in range(narr):
            name = ['arr', 'buf'][ai]
            n = self.r.randint(5, 9)
            self.arrays[name] = n
            arrays.append((name, [self.r.randint(0, 255) for _ in range(n)]))
        main = []
        phases = 1 + (L - 1) // 2
        for _ in range(phases):
            body = []
            if self.r.random() < 0.5:
                body += self.xorshift('s') + self.lcg('t')
            else:
                body += self.lcg('t') + self.xorshift('s')
            for _ in range(self.r.randint(2, 3 + L // 2)):
                body += self.menu_item('i', list(self.arrays))
            main.append(('for', 'i', 0, self.r.randint(2, 6), body))
        fin = [('let', 'out', self.B('^', self.V('acc'), self.V('u')))]
        fin.append(('assign', 'out', self.B('+', self.B('*', self.V('out'), self.N(31)), self.B('^', self.V('s'), self.V('t')))))
        for name, _vals in arrays:
            fin.append(('for', 'i', 0, self.arrays[name], [('assign', 'out', self.B('+', self.B('*', self.V('out'), self.N(7)), self.idx(name, self.V('i'))))]))
        return funcs, decls, arrays, main, fin

    def scale(self, parts, factor):
        """Multiply every phase's trip count (make() uses it to land in the level's work band)."""
        funcs, decls, arrays, main, fin = parts
        main = [('for', 'i', 0, max(1, round(st[3] * factor)), st[4]) for st in main]
        return funcs, decls, arrays, main, fin


def render(funcs, decls, arrays, main, fin, scalars, lang: str, count=False) -> str:
    out: list[str] = []
    if lang == 'js':
        for name, params, body, _ in funcs:
            out.append(f"function {name}({', '.join(params)}) {{")
            js_stmts(body, 1, out)
            out.append('}')
            out.append('')
        extra = ['steps'] if 'steps' in scalars and not any(d[1] == 'steps' for d in decls) else []
        js_stmts(decls, 0, out)
        for v in extra:
            out.append(f"let {v} = 0;")
        for name, vals in arrays:
            out.append(f"const {name} = [{', '.join(map(str, vals))}];")
        out.append('')
        js_stmts(main, 0, out)
        out.append('')
        js_stmts(fin, 0, out)
        out.append('console.log(out);')
    else:
        if count:
            out.append('_n = [0]')
        for name, params, body, _ in funcs:
            out.append(f"def {name}({', '.join(params)}):")
            py_stmts(body, 1, out, count)
            out.append('')
        py_stmts(decls, 0, out, count)
        if 'steps' in scalars:
            out.append('steps = 0')
        for name, vals in arrays:
            out.append(f"{name} = [{', '.join(map(str, vals))}]")
        py_stmts(main, 0, out, count)
        py_stmts(fin, 0, out, count)
        out.append('print(out)' if not count else 'print(out, _n[0])')
    return '\n'.join(out) + '\n'


def run(cmd: list[str], src: str, suffix: str) -> str:
    with tempfile.NamedTemporaryFile('w', suffix=suffix, delete=False) as fh:
        fh.write(src)
        path = fh.name
    try:
        return subprocess.run(cmd + [path], capture_output=True, text=True, timeout=60, check=True).stdout.strip()
    finally:
        os.unlink(path)


def make(level: int, seed: int):
    """Search seeds and loop lengths until the executed-statement count lands in the level's band."""
    target = TARGETS[level]
    lo, hi = int(target * 0.85), int(target * 1.2)
    for attempt in range(4000):
        rng = random.Random(seed * 1000 + attempt)
        g = Gen(rng, level)
        base = g.program()
        for factor in [1.25 ** k for k in range(0, 60)]:
            parts = g.scale(base, factor)
            counted = render(*parts, g.scalars, 'py', count=True)
            value, steps = map(int, run([sys.executable], counted, '.py').split())
            if steps >= lo:
                break
        if not (lo <= steps <= hi):
            continue
        js = render(*parts, g.scalars, 'js')
        py = render(*parts, g.scalars, 'py')
        v_js = int(run(['node'], js, '.js'))
        v_py = int(run([sys.executable], py, '.py'))
        assert v_js == v_py == value, (level, seed, attempt, v_js, v_py, value)
        if value in (0, 1):
            continue
        return {'level': level, 'seed': seed, 'attempt': attempt, 'steps': steps, 'lines': js.count('\n'), 'js': js, 'py': py, 'answer': value}
    raise RuntimeError(f'no program for level {level}')


def main():
    seed = int(os.environ.get('HORIZON_SEED', '2026'))
    cases = []
    for level in range(1, 11):
        c = make(level, seed + level)
        cases.append(c)
        print(f"L{level:02d} lines={c['lines']:4d} executed={c['steps']:6d} answer={c['answer']}")
    with open(os.path.join(HERE, 'mind_runner_cases.json'), 'w') as fh:
        json.dump(cases, fh, indent=1)


if __name__ == '__main__':
    main()
