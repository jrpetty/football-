#!/usr/bin/env python3
"""Keep one CI shard's game tests and switch the rest off, in place (the CI checkout is thrown away after).

    python3 tools/ci/shard_gametests.py <shard> <shards> [src/gametest/java]

The game-test classes are dealt out to the shards by their weight (the sum of their tests' timeoutTicks, 100 for a
test that names none), heaviest first, each to the lightest shard so far: so every shard runs about as long, and a
class stays whole on one shard (its batches and its shared set-up together). A test's timeout is only its ceiling,
so each counts at most CAP ticks, plus a fixed cost for its set-up: a long soak can't fill a shard on its own. A test outside the shard keeps its
method; only its @GameTest annotation goes, its lines kept so stack traces still point at the right line.
"""
import os
import re
import sys

ANNOTATION = re.compile(r"@GameTest\b(?!\w)")
TIMEOUT = re.compile(r"timeoutTicks\s*=\s*([0-9_]+)")
CAP, SETUP = 6000, 200


def annotations(text):
    """Each @GameTest's (start, end), its arguments included however many lines they run to."""
    out, at = [], 0
    for m in ANNOTATION.finditer(text):
        if m.start() < at:
            continue
        k = m.end()
        while k < len(text) and text[k] in " \t":
            k += 1
        end = m.end()
        if k < len(text) and text[k] == "(":
            depth, quote, j = 0, False, k
            while j < len(text):
                c = text[j]
                if c == '"' and text[j - 1] != "\\":
                    quote = not quote
                elif not quote and c == "(":
                    depth += 1
                elif not quote and c == ")":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            end = j + 1
        out.append((m.start(), end))
        at = end
    return out


def weight(text, spans):
    total = 0
    for s, e in spans:
        t = TIMEOUT.search(text[s:e])
        total += SETUP + min(CAP, int(t.group(1).replace("_", "")) if t else 100)
    return total


def main():
    shard, shards = int(sys.argv[1]), int(sys.argv[2])
    root = sys.argv[3] if len(sys.argv) > 3 else "src/gametest/java"
    files = {}
    for r, _, fs in os.walk(root):
        for f in fs:
            if f.endswith(".java"):
                p = os.path.join(r, f)
                text = open(p, encoding="utf-8").read()
                spans = annotations(text)
                if spans:
                    files[p] = (text, spans)
    load = [0] * shards
    mine = set()
    for p in sorted(files, key=lambda p: (-weight(*files[p]), p)):
        i = min(range(shards), key=lambda k: (load[k], k))
        load[i] += weight(*files[p])
        if i == shard:
            mine.add(p)
    kept = 0
    for p, (text, spans) in files.items():
        if p in mine:
            kept += len(spans)
            continue
        out, at = [], 0
        for s, e in spans:
            out.append(text[at:s])
            out.append(" " + "\n" * text[s:e].count("\n"))
            at = e
        out.append(text[at:])
        open(p, "w", encoding="utf-8").write("".join(out))
    print("shard %d of %d: %d classes, %d tests, weight %d (shards: %s)"
          % (shard, shards, len(mine), kept, load[shard], ", ".join(map(str, load))))
    print("  " + ", ".join(sorted(os.path.basename(p)[:-5] for p in mine)))


if __name__ == "__main__":
    main()
