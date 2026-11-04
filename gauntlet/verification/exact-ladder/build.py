"""Writes tests/math/exact-ladder.json (Exact Maths: Ladder) from snakes_cases.json.

    python3 snakes.py     # the ten boards and their keys (exact Fractions, full linear system)
    python3 build.py      # the test file (byte-identical on every run); --cases FILE --out FILE for a dry run
    node verify.mjs       # independent re-check: every board parsed back out of the prompt text, BigInt rationals

The levels are FROZEN once published: never regenerate a published version. To add harder rungs, add levels L11,
L12, ... in a new version and keep L01-L10 unchanged.
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'tests', 'math', 'exact-ladder.json')
VERSION = '1.0.0'
CREATED = '2026-11-04'
LADDER_NOTE = ('Ladder: ten frozen levels (L01 easiest, L10 hardest), each strictly more work than the one below. Every level '
               'is worth the same, so the test score is the share of the ladder climbed; the headline is the highest level '
               'solved reliably.')


def listing(pairs, empty):
    return ', '.join(f'{a} → {b}' for a, b in pairs) if pairs else empty


def prompt(c):
    N, d = c['N'], c['d']
    spinner = 'a fair two-sided spinner numbered 1 and 2' if d == 2 else f'a fair spinner numbered 1 to {d}'
    return (
        f'A one-player board game is played on a track of squares numbered 0 to {N}. The counter starts on square 0. '
        f'Each turn you spin {spinner} (every number is equally likely and every spin is independent of the others) '
        'and move the counter forward by the number spun. Then:\n'
        f'- If the counter has reached square {N} or gone past it, you win and the game ends.\n'
        '- Otherwise, if it landed on a trap, you lose and the game ends.\n'
        '- Otherwise, if it landed on the foot of a ladder, it climbs to the top of that ladder; if it landed on the head '
        'of a snake, it slides down to the tail of that snake. Ladder tops and snake tails are ordinary squares: nothing '
        'more happens on them that turn.\n'
        '- On any other square nothing happens. The next turn starts from where the counter is.\n\n'
        'The board:\n'
        f"- Ladders (foot → top): {listing(c['ladders'], 'none')}\n"
        f"- Snakes (head → tail): {listing(c['snakes'], 'none')}\n"
        f"- Traps: {', '.join(map(str, c['traps']))}\n"
        f'All other squares from 0 to {N - 1} are ordinary. The game ends with probability 1.\n\n'
        'What is the exact probability that you win? Give it as a fraction p/q of two whole numbers, for example 7/24. '
        'Any fraction equal to the exact probability is marked correct, so it does not have to be in lowest terms. '
        'A decimal is marked wrong, and so is an answer that gives more than one value.'
    )


def main():
    arg = lambda name, default: sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default
    with open(arg('--cases', os.path.join(HERE, 'snakes_cases.json'))) as fh:
        levels = json.load(fh)
    cases = []
    for c in levels:
        p, q = c['answer'].split('/')
        notes = (f"[level {c['level']}] track 0..{c['N']}, spinner 1-{c['d']}, {len(c['ladders'])} ladders, {len(c['snakes'])} snakes, "
                 f"{len(c['traps'])} traps ({c['restingSquares']} squares the counter can rest on, {len(c['snakes'])} snake loops); "
                 f"key in lowest terms has a {len(p)}-digit numerator and a {len(q)}-digit denominator. Key = Gauss-Jordan elimination of the full system (one equation per resting "
                 'square) over Python Fractions (verification/exact-ladder/snakes.py), re-checked by verify.mjs, which parses the board '
                 'back out of this prompt and solves it in JavaScript BigInt rationals by a different method (backward recursion with '
                 f"affine snake-tail unknowns). Generator seed {c['seed']}.")
        if c['level'] == 1:
            notes += ' ' + LADDER_NOTE
        cases.append({'id': f"L{c['level']:02d}", 'prompt': prompt(c), 'expected': c['answer'], 'notes': notes,
                      'displayAnswer': c['answer']})
    test = {
        'kind': 'prompt', 'id': 'math.exact-ladder', 'version': VERSION, 'name': 'Exact Maths: Ladder',
        'category': 'math',
        'description': ('Ten levels of one exact probability question: the chance of winning a game of snakes and ladders with '
                        'traps. Level 1 is a 10-square track with a two-sided spinner; level 10 is 64 squares with a six-sided '
                        'spinner, 6 ladders, 6 snakes and 8 traps. Snakes send the counter back, so from level 3 the answer is '
                        'fixed by simultaneous equations, not a simple countdown, and the exact fractions grow from 3 to 30-digit '
                        'denominators. Any fraction of the right value scores; a decimal or a single wrong digit scores 0. Keys '
                        'are computed twice, by two different exact methods in two languages.'),
        'difficulty': 'extreme',
        'tags': ['math', 'ladder', 'probability', 'markov-chain', 'exact', 'linear-equations'],
        'hook': 'Snakes, ladders and traps: what is the exact chance of winning?',
        'maxOutputTokens': 'model-max', 'timeLimitSec': 3600, 'maxRetries': 1,
        'estimate': {'inputTokens': 420, 'outputTokens': 45000},
        'author': 'Gauntlet Ladders', 'createdAt': CREATED,
        'scorer': {'type': 'ladder', 'answer': 'fraction'},
        'cases': cases,
    }
    out = arg('--out', OUT)
    with open(out, 'w', encoding='utf-8') as fh:
        json.dump(test, fh, indent=2, ensure_ascii=False)
        fh.write('\n')
    print('wrote', os.path.normpath(out), len(cases), 'levels')


if __name__ == '__main__':
    main()
