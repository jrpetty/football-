"""Render logic-grid clues to English using a theme definition."""

def cap(s):
    return s[0].upper() + s[1:]


class Theme:
    def __init__(self, cats, subj, pred, npred, pos_pred, pos_npred, extra=None):
        self.cats = cats          # dict cat -> list of value labels
        self.subj = subj          # fn(cat, label) -> noun phrase
        self.pred = pred          # fn(cat, label) -> verb phrase ("owns the ferret")
        self.npred = npred        # fn(cat, label) -> negated verb phrase
        self.pos_pred = pos_pred  # fn(p) -> verb phrase ("is in spot 3")
        self.pos_npred = pos_npred
        self.extra = extra or {}

    def S(self, it):
        c, v = it
        return self.subj(c, self.cats[c][v])

    def PR(self, it):
        c, v = it
        return self.pred(c, self.cats[c][v])

    def NP(self, it):
        c, v = it
        return self.npred(c, self.cats[c][v])


def render(cl, th, geo):
    k, d = cl.kind, cl.data
    X = th.extra
    if k == 'pos':
        x, p = d
        return f"{cap(th.S(x))} {th.pos_pred(p)}."
    if k == 'notpos':
        x, p = d
        return f"{cap(th.S(x))} {th.pos_npred(p)}."
    if k == 'end':
        (x,) = d
        return f"{cap(th.S(x))} {X['end']}."
    if k == 'same':
        x, y = d
        return f"{cap(th.S(x))} {th.PR(y)}."
    if k == 'notsame':
        x, y = d
        return f"{cap(th.S(x))} {th.NP(y)}."
    if k == 'adj':
        x, y = d
        return f"{cap(th.S(x))} {X['adj']} {th.S(y)}."
    if k == 'notadj':
        x, y = d
        return f"{cap(th.S(x))} {X['notadj']} {th.S(y)}."
    if k == 'left':
        x, y = d
        return f"{cap(th.S(x))} {X['left']} {th.S(y)}."
    if k == 'immleft':
        x, y = d
        return f"{cap(th.S(x))} {X['immleft']} {th.S(y)}."
    if k == 'dist':
        x, y, dd = d
        return X['dist'](cap(th.S(x)), th.S(y), dd)
    if k == 'immcw':
        x, y = d
        return f"{cap(th.S(y))} {X['immcw']} {th.S(x)}."
    if k == 'opposite':
        x, y = d
        return f"{cap(th.S(x))} {X['opposite']} {th.S(y)}."
    if k == 'above':
        x, y = d
        return X['above'](cap(th.S(x)), th.S(y))
    if k == 'gimmleft':
        x, y = d
        return X['gimmleft'](cap(th.S(x)), th.S(y))
    if k == 'samerow':
        x, y = d
        return X['samerow'](cap(th.S(x)), th.S(y))
    if k == 'samecol':
        x, y = d
        return X['samecol'](cap(th.S(x)), th.S(y))
    if k == 'diffrow':
        x, y = d
        return X['diffrow'](cap(th.S(x)), th.S(y))
    if k == 'xor':
        x, a, b = d
        return f"Exactly one of these two statements is true: {th.S(x)} {th.PR(a)}; {th.S(x)} {th.PR(b)}."
    if k == 'ofxy':
        x, y, a, b = d
        return f"Of {th.S(x)} and {th.S(y)} (two different {X['people']}), one {th.PR(a)} and the other {th.PR(b)}."
    if k == 'ifthen':
        a, b, c, dd = d
        return f"If {th.S(a)} {th.PR(b)}, then {th.S(c)} {th.PR(dd)}."
    if k == 'numgt':
        x, y, nc = d
        return X['numgt'](cap(th.S(x)), th.S(y))
    if k == 'numdiff':
        x, y, nc, diff = d
        return X['numdiff'](cap(th.S(x)), th.S(y), diff)
    raise ValueError(k)
