"""Writes tests/reasoning/grid-ladder.json ("Deduction Grid: Ladder") from ladder_cases.json (made by gen_ladder.py).

    python3 gen_ladder.py     # CP-SAT generation, uniqueness and minimality proofs -> ladder_cases.json
    python3 write.py          # writes the test file
    python3 write.py --check  # proves the test file is reproduced byte for byte
    python3 ../grid_extreme/verify_sat.py ../../tests/reasoning/grid-ladder.json --minimal   # independent SAT re-proof

The ten levels are FROZEN once published: never regenerate a published version; add L11+ in a new version.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
from common import write_test, tok, ROOT  # noqa: E402

OUT_TOKENS = [3000, 5000, 9000, 15000, 25000, 40000, 60000, 90000, 110000, 128000]
LADDER_NOTE = ('Ladder: ten frozen levels (L01 easiest, L10 hardest) of the same task under the same rules and definitions; only the '
               'grid size, the number of clues and the clue depth grow. Every level is worth the same, so the test score is the share '
               'of the ladder climbed; the headline is the highest level solved reliably.')


def with_note(notes):
    # the ladder note goes before the full solution, which must stay last (the grid visual reads it from the notes)
    assert notes.count(' Full solution: ') == 1
    return notes.replace(' Full solution: ', ' ' + LADDER_NOTE + ' Full solution: ')


def cases():
    raw = json.load(open(os.path.join(HERE, 'ladder_cases.json')))
    return raw, [{'id': c['id'], 'prompt': c['prompt'], 'expected': c['expected'], 'notes': with_note(c['notes'])} for c in raw]


def meta(raw, out):
    lo, hi = raw[0], raw[-1]
    return dict(
        id='reasoning.grid-ladder', category='reasoning', name='Deduction Grid: Ladder',
        description=(f"A ten-level difficulty ladder of one logic-grid task: people in numbered positions, each with one value per "
                     f"category, pinned down by a minimal set of clues, and the model must return two complete orderings. Level 1 is "
                     f"{lo['n']} people x {len(lo['cats'])} categories with {lo['nclues']} direct clues; level 10 is {hi['n']} people x "
                     f"{len(hi['cats'])} categories with {hi['nclues']} clues of every kind (either/or, if-then, exactly k of 3, "
                     f"betweenness, ordering chains, distances and age arithmetic). Every level has exactly one solution (CP-SAT, "
                     f"re-proved by an independent SAT check parsed from the English) and every clue is needed, so only a fully "
                     f"solved grid scores."),
        version='1.0.0', difficulty='extreme', createdAt='2026-11-04',
        tags=['logic', 'deduction', 'zebra-puzzle', 'constraint-satisfaction', 'ladder', 'sat-verified'],
        hook=f"From {lo['nclues']} clues to {hi['nclues']}: how big a logic grid can it solve?",
        maxOutputTokens='model-max', timeLimitSec=3600, maxRetries=1,
        estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)), 'outputTokens': int(sum(OUT_TOKENS) / len(OUT_TOKENS))},
        scorer={'type': 'exact', 'normalize': 'alnum'},
    )


if __name__ == '__main__':
    raw, out = cases()
    assert [c['id'] for c in out] == [f'L{i:02d}' for i in range(1, 11)]
    path = os.path.join(ROOT, 'reasoning', 'grid-ladder.json')
    before = open(path, 'rb').read() if os.path.exists(path) else None
    write_test(meta(raw, out), out)
    after = open(path, 'rb').read()
    if '--check' in sys.argv:
        if before != after:
            if before is not None:
                open(path, 'wb').write(before)
            print('MISMATCH: tests/reasoning/grid-ladder.json is not reproduced byte for byte')
            sys.exit(1)
        print('OK: tests/reasoning/grid-ladder.json reproduced byte for byte')
    else:
        print('wrote', path)
    for r, c, t in zip(raw, out, OUT_TOKENS):
        print(f"  {c['id']}: {r['n']}x{len(r['cats'])}, {r['nclues']} clues, ~{tok(c['prompt'])} input tokens, ~{t:,} output tokens expected")
