"""Writes the five Horizon tests (tests/horizon/*.json) from the generated, verified case files.

    python3 mind_runner.py && python3 modpow.py && python3 sliding.py && python3 nonogram.py && python3 tilings.py
    python3 build.py
    node verify.mjs          # independent re-check of every key, parsed back from the rendered prompts

The levels are FROZEN once published: never regenerate a published version. A model measured years from
now must meet exactly the same ten rungs. To add harder rungs, add levels L11, L12, ... in a new version.
"""
from __future__ import annotations

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
TESTS = os.path.join(HERE, '..', '..', 'tests', 'horizon')
VERSION = '1.0.0'
CREATED = '2026-09-28'
LADDER_NOTE = ('Horizon ladder: ten frozen levels (L01 easiest, L10 hardest), each strictly more work than the one below. '
               'Every level is worth the same, so the test score is the share of the ladder climbed; the headline is the '
               'highest level solved reliably.')


def load(name):
    with open(os.path.join(HERE, name)) as fh:
        return json.load(fh)


def board_text(cells, rows, cols):
    w = len(str(rows * cols - 1))
    lines = []
    for r in range(rows):
        lines.append(' '.join(('_' if v == 0 else str(v)).rjust(w) for v in cells[r * cols:(r + 1) * cols]))
    return '\n'.join(lines)


def mind_runner():
    cases = []
    for c in load('mind_runner_cases.json'):
        prompt = (
            'Here is a complete JavaScript program. Work out, by reasoning alone, exactly what it prints.\n\n'
            'Every value is a whole number from 0 to 65535: each assignment is masked with `& 65535`, and every '
            'operator works on non-negative whole numbers (`%` is the remainder, `>>` and `<<` are bit shifts, '
            '`^` is bitwise XOR, `&` is bitwise AND, `|` is bitwise OR). Loops have fixed bounds, and the program '
            'always stops. It prints exactly one number.\n\n'
            f"```javascript\n{c['js']}```\n\n"
            'What number does the program print? Give the answer as a single integer (digits only).'
        )
        cases.append({
            'id': f"L{c['level']:02d}",
            'prompt': prompt,
            'expected': str(c['answer']),
            'notes': (f"[level {c['level']}] {c['lines']} lines, {c['steps']} statements executed. Key = the printed number, "
                      f"obtained by running the program in Node.js and its line-by-line Python translation (identical output), "
                      f"re-run from this prompt by verification/horizon/verify.mjs. Generator seed {c['seed']}, attempt {c['attempt']}."),
        })
    return {
        'kind': 'prompt', 'id': 'horizon.mind-runner', 'version': VERSION, 'name': 'Horizon: Run It In Your Head',
        'category': 'horizon',
        'description': ('Ten generated JavaScript programs that the model must execute in its head and '
                        'report the exact number printed. Level N executes about twice as many statements as level N-1 '
                        '(about 150 up to about 73,000). Every loop iteration feeds the next (a 16-bit xorshift register, a '
                        'linear congruential counter, array cells picked by them and a running hash), so nothing can be skipped '
                        'or pattern-matched: one wrong bit anywhere changes the answer. Keys come from actually running every '
                        'program in two languages.'),
        'difficulty': 'extreme',
        'tags': ['horizon', 'ladder', 'program-tracing', 'execution-verified'],
        'hook': 'Can it run a program for 70,000 steps in its head?',
        'maxOutputTokens': 'model-max', 'timeLimitSec': 3600, 'maxRetries': 1,
        'estimate': {'inputTokens': 2400, 'outputTokens': 60000},
        'author': 'Gauntlet Horizon', 'createdAt': CREATED,
        'scorer': {'type': 'ladder', 'answer': 'integer'},
        'cases': cases,
    }


def modpow():
    cases = []
    for c in load('modpow_cases.json'):
        prompt = (
            'Compute a modular power exactly, without a calculator:\n\n'
            f"a = {c['a']}\ne = {c['e']}\nm = {c['m']}\n\n"
            'What is the remainder when a to the power e (a multiplied by itself e times) is divided by m? '
            'The answer is a whole number from 0 to m - 1. Give it as a single integer, all digits, with no spaces, commas or other separators.'
        )
        cases.append({
            'id': f"L{c['level']:02d}",
            'prompt': prompt,
            'expected': str(c['answer']),
            'notes': (f"[level {c['level']}] m has {len(str(c['m']))} digits, e has {len(str(c['e']))} digits ({c['bits']} bits), so about "
                      f"{c['bits'] + bin(c['e']).count('1')} modular multiplications. Key = Python pow(a, e, m), re-checked by a "
                      f"left-to-right binary method and by an independent JavaScript BigInt implementation (verify.mjs)."),
        })
    return {
        'kind': 'prompt', 'id': 'horizon.modpow-ladder', 'version': VERSION, 'name': 'Horizon: No Calculator',
        'category': 'horizon',
        'description': ('Ten exact modular powers a^e mod m, from an 8-digit modulus to a 44-digit one with a 36-digit exponent. '
                        'There is no trick: the only way is square-and-multiply, dozens of long multiplications and long divisions '
                        'done exactly. Every level has longer numbers and more steps. A single wrong digit anywhere gives a '
                        'different answer. Keys are computed by two independent big-integer implementations.'),
        'difficulty': 'extreme',
        'tags': ['horizon', 'ladder', 'arithmetic', 'number-theory', 'exact'],
        'hook': 'Can it do 44-digit arithmetic in its head, with no mistakes?',
        'maxOutputTokens': 'model-max', 'timeLimitSec': 3600, 'maxRetries': 1,
        'estimate': {'inputTokens': 250, 'outputTokens': 60000},
        'author': 'Gauntlet Horizon', 'createdAt': CREATED,
        'scorer': {'type': 'ladder', 'answer': 'integer'},
        'cases': cases,
    }


def sliding():
    cases = []
    for c in load('sliding_cases.json'):
        r, k = c['rows'], c['cols']
        n = r * k - 1
        goal = list(range(1, n + 1)) + [0]
        prompt = (
            f'A sliding puzzle on a board with {r} rows and {k} columns. The tiles are numbered 1 to {n} and one square is '
            'empty (shown as _).\n\n'
            f"Start:\n{board_text(c['start'], r, k)}\n\n"
            f"Goal:\n{board_text(goal, r, k)}\n\n"
            'A move slides one tile that is directly next to the empty square (above, below, left or right, never diagonally) '
            'into the empty square. Find a plan that reaches the goal in the FEWEST possible moves.\n\n'
            'Scoring: a plan that reaches the goal in the minimum possible number of moves scores full marks. A plan that '
            'reaches the goal with more moves than necessary scores a little partial credit. A plan with an illegal move, or '
            'one that does not end exactly at the goal, scores 0.\n\n'
            'Write the plan as the numbers of the tiles you slide, in order, separated by spaces (for example: 6 3 2 5).'
        )
        cases.append({
            'id': f"L{c['level']:02d}",
            'prompt': prompt,
            'expected': {'rows': r, 'cols': k, 'start': c['start'], 'optimal': c['optimal'], 'plan': c['plan']},
            'notes': (f"[level {c['level']}] {r}x{k} board, proven minimum {c['optimal']} moves "
                      f"({'breadth-first search over all 181,440 positions' if (r, k) == (3, 3) else 'IDA* with Manhattan + linear-conflict heuristic'}; "
                      f"independently re-proved in JavaScript by verify.mjs). One optimal plan: {' '.join(map(str, c['plan']))}."),
        })
    return {
        'kind': 'prompt', 'id': 'horizon.sliding-ladder', 'version': VERSION, 'name': 'Horizon: The Sliding Ladder',
        'category': 'horizon',
        'description': ('Ten sliding-tile puzzles on 3x3, 3x4 and 4x4 boards whose proven minimum grows every level: 20, 24, 27, '
                        '30, 36, 42, 46, 50, 53 and 56 moves. The model writes the whole plan; the scorer replays it tile by tile. '
                        'Only a plan of exactly the minimum length scores full marks, so the model has to search deep and be sure '
                        'nothing shorter exists. Every minimum is proven by exhaustive search in two languages.'),
        'difficulty': 'extreme',
        'tags': ['horizon', 'ladder', 'planning', 'search', 'bfs-verified'],
        'hook': 'Can it find the shortest solution when the answer is 56 moves long?',
        'maxOutputTokens': 'model-max', 'timeLimitSec': 3600, 'maxRetries': 1,
        'estimate': {'inputTokens': 400, 'outputTokens': 60000},
        'author': 'Gauntlet Horizon', 'createdAt': CREATED,
        'scorer': {'type': 'ladder', 'answer': 'plan'},
        'cases': cases,
    }


def nonogram():
    cases = []
    for c in load('nonogram_cases.json'):
        R, C = c['rows'], c['cols']
        rows = '\n'.join(f"Row {i + 1}: {' '.join(map(str, cl))}" for i, cl in enumerate(c['rowClues']))
        cols = '\n'.join(f"Column {i + 1}: {' '.join(map(str, cl))}" for i, cl in enumerate(c['colClues']))
        prompt = (
            f'Solve this nonogram (paint-by-numbers). The grid has {R} rows and {C} columns. Every cell is either filled or empty.\n\n'
            'The numbers for a row give the lengths of the runs of consecutive filled cells in that row, in order from left to '
            'right; the numbers for a column give the runs from top to bottom. Runs are separated by at least one empty cell. '
            'The puzzle has exactly one solution. The picture is random, so it does not show anything recognisable.\n\n'
            f'Rows (top to bottom):\n{rows}\n\nColumns (left to right):\n{cols}\n\n'
            f'When you are finished, write the line FINAL ANSWER: and then the {R} rows of the grid, top to bottom, one row per line, '
            f'using # for a filled cell and . for an empty cell ({C} characters per row, no spaces). For example, a 3x3 answer looks like:\n'
            'FINAL ANSWER:\n#.#\n###\n..#'
        )
        cases.append({
            'id': f"L{c['level']:02d}",
            'prompt': prompt,
            'expected': c['solution'],
            'notes': (f"[level {c['level']}] {R}x{C}, {sum(r.count('#') for r in c['solution'])} filled cells; {c['undecidedByLineLogic']} cells stay open after pure row/column logic. Uniqueness proven by "
                      'OR-Tools CP-SAT (automaton constraints; the found solution is excluded and the solver proves infeasibility) and '
                      'independently by a JavaScript line-solver with backtracking that counts solutions (verify.mjs).'),
        })
    return {
        'kind': 'prompt', 'id': 'horizon.nonogram-ladder', 'version': VERSION, 'name': 'Horizon: The Picture Logic Ladder',
        'category': 'horizon',
        'description': ('Ten nonograms (paint-by-numbers logic puzzles) from 8x8 up to 50x50, 2,500 cells, each with exactly one '
                        'solution. The pictures are random, so nothing can be guessed from a shape: every cell has to be deduced from '
                        'the row and column clues. From level 3 on, looking at one row or column at a time is not enough: a growing '
                        'share of the grid can only be settled by case analysis (try a cell, follow it to a contradiction). The whole grid '
                        'must be exactly right. Uniqueness is proven by two independent solvers.'),
        'difficulty': 'extreme',
        'tags': ['horizon', 'ladder', 'constraint-puzzle', 'nonogram', 'sat-verified'],
        'hook': 'Can it deduce a 2,500-cell picture from numbers alone?',
        'maxOutputTokens': 'model-max', 'timeLimitSec': 3600, 'maxRetries': 1,
        'estimate': {'inputTokens': 700, 'outputTokens': 60000},
        'author': 'Gauntlet Horizon', 'createdAt': CREATED,
        'scorer': {'type': 'ladder', 'answer': 'grid'},
        'cases': cases,
    }


def tilings():
    cases = []
    for c in load('tilings_cases.json'):
        R, C = c['rows'], c['cols']
        squares = sum(row.count('#') for row in c['board'])
        prompt = (
            f'Here is a board with {R} rows and {C} columns. Squares marked # must be covered; squares marked . are holes and must '
            f'stay uncovered.\n\n' + '\n'.join(c['board']) + '\n\n'
            f'A domino is a 1x2 tile that covers two squares sharing a side (it can lie horizontally or vertically). In how many '
            f'different ways can the {squares} marked squares be covered completely with {squares // 2} dominoes, with no overlaps, '
            'no domino on a hole and nothing sticking out of the board? Two coverings are different if some pair of squares is '
            'covered by one domino in one covering but not in the other.\n\n'
            'Give the exact count as a single integer, all digits, with no spaces, commas or other separators.'
        )
        cases.append({
            'id': f"L{c['level']:02d}",
            'prompt': prompt,
            'expected': str(c['answer']),
            'notes': (f"[level {c['level']}] {R}x{C} board, {squares} squares. Key by column-by-column transfer-matrix counting "
                      '(Python, big integers), re-checked by an independent memoised first-empty-square search in JavaScript (verify.mjs).'),
        })
    return {
        'kind': 'prompt', 'id': 'horizon.tiling-count', 'version': VERSION, 'name': 'Horizon: Count Every Tiling',
        'category': 'horizon',
        'description': ('Ten boards with holes, from 6x6 to 18x18. The task: the exact number of ways to cover each board with '
                        'dominoes, a count that grows from 110 to a 31-digit number. It can be done by hand, column by '
                        'column, but only by keeping perfect books over thousands of partial patterns. Keys are computed by two '
                        'different exact algorithms.'),
        'difficulty': 'extreme',
        'tags': ['horizon', 'ladder', 'combinatorics', 'counting', 'exact'],
        'hook': 'Can it count every domino tiling when the answer has 31 digits?',
        'maxOutputTokens': 'model-max', 'timeLimitSec': 3600, 'maxRetries': 1,
        'estimate': {'inputTokens': 350, 'outputTokens': 60000},
        'author': 'Gauntlet Horizon', 'createdAt': CREATED,
        'scorer': {'type': 'ladder', 'answer': 'integer'},
        'cases': cases,
    }


def main():
    os.makedirs(TESTS, exist_ok=True)
    for name, fn in [('mind-runner', mind_runner), ('modpow-ladder', modpow), ('sliding-ladder', sliding),
                     ('nonogram-ladder', nonogram), ('tiling-count', tilings)]:
        t = fn()
        for c in t['cases']:
            c['notes'] = c['notes'] + ' ' + LADDER_NOTE if c['id'] == 'L01' else c['notes']
        with open(os.path.join(TESTS, f'{name}.json'), 'w') as fh:
            json.dump(t, fh, indent=2, ensure_ascii=False)
            fh.write('\n')
        print(name, len(t['cases']))


if __name__ == '__main__':
    main()
