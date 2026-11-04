"""Writes tests/reasoning/truth-ladder.json ("Knights & Knaves: Ladder") from ladder_cases.json (made by gen_ladder.py).

    python3 gen_ladder.py     # CP-SAT generation and uniqueness proof -> ladder_cases.json
    python3 write.py          # writes the test file
    python3 write.py --check  # proves the test file is reproduced byte for byte
    node verify.mjs ../../tests/reasoning/truth-ladder.json   # independent exhaustive re-proof from the prompt text

The ten levels are FROZEN once published: never regenerate a published version; add L11+ in a new version.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
from common import write_test, tok, ROOT  # noqa: E402

OUT_TOKENS = [3000, 5000, 9000, 16000, 25000, 40000, 60000, 90000, 110000, 128000]
LADDER_NOTE = ('Ladder: ten frozen levels (L01 easiest, L10 hardest) of the same task under the same rules and conventions; only '
               'the number of islanders, spies, alternators and statements and the nesting depth grow. Every level is worth the '
               'same, so the test score is the share of the ladder climbed; the headline is the highest level solved reliably.')


def cases():
    out = []
    for c in json.load(open(os.path.join(HERE, 'ladder_cases.json'))):
        out.append({'id': c['id'], 'prompt': c['prompt'], 'expected': c['expected'], 'notes': c['notes'] + ' ' + LADDER_NOTE})
    return out


def meta(out):
    raw = json.load(open(os.path.join(HERE, 'ladder_cases.json')))
    top = raw[-1]
    lo = raw[0]
    trillions = top['assignments'] / 1e12
    assert top['n'] == 24 and 1 < trillions < 10
    return dict(
        id='reasoning.truth-ladder', category='reasoning', name='Knights & Knaves: Ladder',
        description=('A ten-level difficulty ladder of one logic task: knights always tell the truth, knaves always lie, spies say '
                     'anything and alternators flip between true and false, and the model must name every islander\'s type. '
                     f"Level 1 has {lo['n']} islanders and {lo['statements']} plain statements; level 10 has {top['n']} islanders "
                     f"({top['spies']} spies, {top['alternators']} alternators) and {top['statements']} statements, with nested "
                     '"X would say that Y would say that ..." claims and chains of statements about other people\'s statements, '
                     f'over {trillions:.1f} trillion line-ups allowed by the stated counts. Exactly one line-up fits each level '
                     '(proved by CP-SAT and re-proved by an independent exhaustive search), so one slip anywhere fails it.'),
        version='1.0.0', difficulty='extreme', createdAt='2026-11-04',
        tags=['logic', 'deduction', 'knights-and-knaves', 'self-reference', 'ladder', 'sat-verified'],
        hook='From 4 islanders to 24, with liars quoting liars: how high can it climb?',
        maxOutputTokens='model-max', timeLimitSec=3600, maxRetries=1,
        estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)), 'outputTokens': int(sum(OUT_TOKENS) / len(OUT_TOKENS))},
        scorer={'type': 'exact', 'normalize': 'alnum'},
    )


if __name__ == '__main__':
    out = cases()
    path = os.path.join(ROOT, 'reasoning', 'truth-ladder.json')
    before = open(path, 'rb').read() if os.path.exists(path) else None
    write_test(meta(out), out)
    after = open(path, 'rb').read()
    if '--check' in sys.argv:
        if before != after:
            if before is not None:
                open(path, 'wb').write(before)
            print('MISMATCH: tests/reasoning/truth-ladder.json is not reproduced byte for byte')
            sys.exit(1)
        print('OK: tests/reasoning/truth-ladder.json reproduced byte for byte')
    else:
        print('wrote', path)
    for c, t in zip(out, OUT_TOKENS):
        print(f"  {c['id']}: ~{tok(c['prompt'])} input tokens, ~{t:,} output tokens expected")
