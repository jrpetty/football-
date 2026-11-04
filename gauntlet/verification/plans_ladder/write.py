"""Writes tests/reasoning/plans-ladder.json ("Shortest Plans: Ladder") from cases.json (made by hanoi.py).

    python3 hanoi.py          # BFS over every position of every level -> cases.json
    python3 write.py          # renders the prompts and writes the test file
    python3 write.py --check  # proves the test file is reproduced byte for byte
    node verify.mjs ../../tests/reasoning/plans-ladder.json   # independent re-proof of every minimum from the prompt text

The ten levels are FROZEN once published: never regenerate a published version; add L11+ in a new version.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
from common import write_test, tok, ONE, ROOT  # noqa: E402

NUMW = {4: 'four', 5: 'five', 6: 'six', 7: 'seven', 8: 'eight', 9: 'nine', 10: 'ten', 11: 'eleven', 12: 'twelve', 13: 'thirteen'}
# Expected output tokens per level (thinking included): small at the bottom, the model's whole budget at the top.
OUT_TOKENS = [3000, 6000, 10000, 16000, 25000, 40000, 60000, 90000, 110000, 128000]
LADDER_NOTE = ('Ladder: ten frozen levels (L01 easiest, L10 hardest) of the same task under the same rules; only the size grows. '
               'Every level is worth the same, so the test score is the share of the ladder climbed; the headline is the highest '
               'level solved reliably.')


def peg_line(name, disks):
    if not disks:
        return f'- Peg {name}: empty.'
    if len(disks) == 1:
        return f'- Peg {name}: disk {disks[0]} only.'
    if len(disks) == 2:
        return f'- Peg {name}: disk {disks[0]} at the bottom with disk {disks[1]} on top of it.'
    mid = ''.join(f', then disk {d}' for d in disks[1:-1])
    return f'- Peg {name}: disk {disks[0]} at the bottom{mid}, then disk {disks[-1]} on top.'


def prompt_for(c):
    n = c['disks']
    lines = [peg_line(p, d) for p, d in zip('ABCD', c['start'])]
    return (f"There are four pegs, A, B, C and D, and {NUMW[n]} disks of different sizes numbered 1 (smallest) to {n} (largest).\n\n"
            "Current position (each peg listed from the bottom up):\n" + '\n'.join(lines) + "\n\n"
            "A move consists of taking the top disk from any peg and placing it on top of another peg. "
            "A disk may never be placed on top of a smaller disk.\n\n"
            f"What is the minimum number of moves needed to get all {NUMW[n]} disks onto peg D?\n\n"
            f"Give the answer as a single integer (digits only, no units). {ONE}")


def cases():
    out = []
    for c in json.load(open(os.path.join(HERE, 'cases.json'))):
        plan = '; '.join(f'disk {d} {a} to {b}' for d, a, b in c['plan'])
        notes = (f"[level {c['level']}] {c['disks']} disks on four pegs: 4^{c['disks']} = {c['states']:,} positions, proven minimum "
                 f"{c['minimum']} moves. Breadth-first search over every position (numpy, plans_ladder/hanoi.py) gives the exact distance "
                 f"of every position to the goal; this start was drawn (seed {c['seed']}) among the {c['at_distance']:,} positions exactly "
                 f"{c['minimum']} moves away, with the largest disk not yet on peg D and disks on at least three pegs. The same search "
                 f"re-proves the known four-peg minima for a full tower (here {c['perfect']} moves from peg A, the farthest any position "
                 f"is from the goal: {c['eccentricity']}). Independently re-proved by plans_ladder/verify.mjs, a forward breadth-first "
                 f"search in JavaScript from the position parsed out of this prompt. {LADDER_NOTE} One optimal plan: {plan}.")
        out.append({'id': f"L{c['level']:02d}", 'prompt': prompt_for(c), 'expected': c['minimum'], 'notes': notes})
    return out


def meta(out):
    return dict(
        id='reasoning.plans-ladder', category='reasoning', name='Shortest Plans: Ladder',
        description=('A ten-level difficulty ladder of one planning task: the fewest moves that bring every disk of a scattered '
                     'four-peg Tower of Hanoi onto peg D. Only the number of disks grows, from 4 (256 positions, a 6-move minimum) '
                     'to 13 (67 million positions, a 70-move minimum), so every level is strictly more search than the one below. '
                     'The answer is the proven minimum, so the model must both find a short plan and rule out every shorter one; '
                     'each minimum is proven by breadth-first search over every position and re-proved by an independent search.'),
        version='1.0.0', difficulty='extreme', createdAt='2026-11-04',
        tags=['planning', 'search', 'optimisation', 'ladder', 'bfs-verified'],
        hook='From 6 moves to 70: how deep can it plan before one move too many?',
        maxOutputTokens='model-max', timeLimitSec=3600, maxRetries=1,
        estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)), 'outputTokens': int(sum(OUT_TOKENS) / len(OUT_TOKENS))},
        scorer={'type': 'number', 'tolerance': 0},
    )


if __name__ == '__main__':
    out = cases()
    path = os.path.join(ROOT, 'reasoning', 'plans-ladder.json')
    before = open(path, 'rb').read() if os.path.exists(path) else None
    write_test(meta(out), out)
    after = open(path, 'rb').read()
    if '--check' in sys.argv:
        if before != after:
            if before is not None:
                open(path, 'wb').write(before)
            print('MISMATCH: tests/reasoning/plans-ladder.json is not reproduced byte for byte')
            sys.exit(1)
        print('OK: tests/reasoning/plans-ladder.json reproduced byte for byte')
    else:
        print('wrote', path)
    for c, t in zip(out, OUT_TOKENS):
        print(f"  {c['id']}: minimum {c['expected']}, ~{tok(c['prompt'])} input tokens, ~{t:,} output tokens expected")
