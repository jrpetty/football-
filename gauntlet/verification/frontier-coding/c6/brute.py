# Independent, deliberately simple implementation of the c6 exchange, written from the prompt text:
# the book is a plain list scanned in full for every matching step.
import sys, json
def run(events):
    book = []        # dicts: id side price rem disp prio kind peak
    stops = []       # dicts: id side trigger qty (submission order)
    trades = []
    st = {'prio': 0, 'last': None}
    def new_prio():
        st['prio'] += 1; return st['prio']
    def best(side):
        cand = [o for o in book if o['side'] == side]
        if not cand: return None
        if side == 'BUY': return min(cand, key=lambda o: (-o['price'], o['prio']))
        return min(cand, key=lambda o: (o['price'], o['prio']))
    def match(oid, side, q, limit):
        opp = 'SELL' if side == 'BUY' else 'BUY'
        while q > 0:
            o = best(opp)
            if o is None: break
            if limit is not None and ((side == 'BUY' and o['price'] > limit) or (side == 'SELL' and o['price'] < limit)): break
            x = min(q, o['disp'])
            q -= x; o['disp'] -= x; o['rem'] -= x
            trades.append([oid, o['id'], o['price'], x] if side == 'BUY' else [o['id'], oid, o['price'], x])
            st['last'] = o['price']
            if o['rem'] == 0: book.remove(o)
            elif o['disp'] == 0:
                o['disp'] = min(o['peak'], o['rem']); o['prio'] = new_prio()
        return q
    def limit_order(oid, side, price, qty):
        q = match(oid, side, qty, price)
        if q > 0: book.append({'id': oid, 'side': side, 'price': price, 'rem': q, 'disp': q, 'prio': new_prio(), 'kind': 'L', 'peak': None})
    for ev in events:
        t = ev[0]
        if t == 'LIMIT':
            limit_order(ev[1], ev[2], ev[3], ev[4])
        elif t == 'ICEBERG':
            _, oid, side, price, qty, peak = ev
            q = match(oid, side, qty, price)
            if q > 0: book.append({'id': oid, 'side': side, 'price': price, 'rem': q, 'disp': min(peak, q), 'prio': new_prio(), 'kind': 'I', 'peak': peak})
        elif t == 'MARKET':
            match(ev[1], ev[2], ev[3], None)
        elif t == 'STOP':
            stops.append({'id': ev[1], 'side': ev[2], 'trigger': ev[3], 'qty': ev[4]})
        elif t == 'CANCEL':
            oid = ev[1]
            hit = [o for o in book if o['id'] == oid]
            if hit: book.remove(hit[0])
            else:
                hs = [s for s in stops if s['id'] == oid]
                if hs: stops.remove(hs[0])
        elif t == 'AMEND':
            _, oid, price, qty = ev
            hit = [o for o in book if o['id'] == oid and o['kind'] == 'L']
            if hit:
                o = hit[0]
                if price == o['price'] and qty <= o['rem']:
                    o['rem'] = o['disp'] = qty
                else:
                    book.remove(o)
                    limit_order(oid, o['side'], price, qty)
        else:
            raise ValueError(t)
        while True:
            trig = [s for s in stops if st['last'] is not None and ((s['side'] == 'BUY' and st['last'] >= s['trigger']) or (s['side'] == 'SELL' and st['last'] <= s['trigger']))]
            if not trig: break
            s = trig[0]; stops.remove(s)
            match(s['id'], s['side'], s['qty'], None)
    def levels(side):
        agg = {}
        for o in book:
            if o['side'] == side: agg[o['price']] = agg.get(o['price'], 0) + o['disp']
        return [[p, agg[p]] for p in sorted(agg, reverse=(side == 'BUY'))]
    return {'trades': trades, 'bids': levels('BUY'), 'asks': levels('SELL'), 'stops': [s['id'] for s in stops]}
if __name__ == '__main__':
    print(json.dumps([run(*c) for c in json.load(sys.stdin)]))
