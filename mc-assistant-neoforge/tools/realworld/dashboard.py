#!/usr/bin/env python3
"""
The long game, build against build: reads epic-history.json (the day-by-day METRICS lines of
each build's long game, kept by .github/workflows/village-long-soak.yml) and writes one HTML
page of small line charts, one line per build, so a change that makes villages worse shows as
a curve bending the wrong way against the builds before it.

    python3 dashboard.py epic-history.json epic-dashboard.html
"""
import html
import json
import sys

RANKS = {"hamlet": 0, "village": 1, "town": 2, "city": 3, "capital": 4}
AGES = {"the Wood Age": 0, "the Stone Age": 1, "the Iron Age": 2, "the Diamond Age": 3, "the Nether Age": 4}

# (title, how to read one day's number, unit)
CHARTS = [
    ("Folk", lambda d: d.get("folk"), ""),
    ("Age (0 Wood … 4 Nether)", lambda d: AGES.get(d.get("age")), ""),
    ("Buildings", lambda d: d.get("buildings"), ""),
    ("Rank (0 hamlet … 4 capital)", lambda d: RANKS.get(d.get("rank")), ""),
    ("Share of folk with a bed", lambda d: (d["bedded"] / d["folk"]) if d.get("bedded") is not None and d.get("folk") else None, "%"),
    ("Guards", lambda d: d.get("guards"), ""),
    ("Iron in the stores", lambda d: d.get("iron"), ""),
    ("Food in the stores", lambda d: d.get("food"), ""),
    ("Logs in the stores", lambda d: d.get("logs"), ""),
    ("Stone in the stores", lambda d: d.get("stone"), ""),
    ("Treasury (coin)", lambda d: d.get("coins"), ""),
    ("Contentment", lambda d: d.get("contentment"), ""),
    ("Gates on the wall", lambda d: d.get("gates"), ""),
    ("Watch posts", lambda d: d.get("posts"), ""),
    ("Renown (great works, ten each, and the museum)", lambda d: d.get("renown"), ""),
    ("Server ms per tick (sprint)", lambda d: d.get("ms_per_tick"), ""),
]

# Newest build last; its line is drawn on top in the strongest colour.
PALETTE = ["#9aa5b1", "#7f8ea3", "#b07aa1", "#59a14f", "#f28e2b", "#4e79a7"]


def chart(title, read, unit, builds):
    w, h, pad = 300, 150, 28
    series = []
    for sha, run in builds:
        pts = [(d.get("day"), read(d)) for d in run.get("days", []) if d.get("day") is not None]
        pts = [(x, y) for x, y in pts if y is not None]
        series.append((sha, pts))
    xs = [x for _, pts in series for x, _ in pts]
    ys = [y for _, pts in series for _, y in pts]
    if not xs:
        return ""
    x0, x1 = min(xs), max(max(xs), min(xs) + 1)
    y0, y1 = min(0, min(ys)), max(ys) if max(ys) > min(0, min(ys)) else min(0, min(ys)) + 1

    def px(x):
        return pad + (x - x0) * (w - pad - 8) / (x1 - x0)

    def py(y):
        return h - pad + 6 - (y - y0) * (h - pad - 14) / (y1 - y0)

    lines = []
    for i, (sha, pts) in enumerate(series):
        if not pts:
            continue
        colour = PALETTE[max(0, len(PALETTE) - len(series) + i)]
        width = 2.4 if i == len(series) - 1 else 1.4
        path = " ".join(("M" if j == 0 else "L") + "%.1f,%.1f" % (px(x), py(y)) for j, (x, y) in enumerate(pts))
        lines.append('<path d="%s" fill="none" stroke="%s" stroke-width="%s"><title>%s</title></path>'
                     % (path, colour, width, html.escape(sha)))
    last = series[-1][1][-1][1] if series and series[-1][1] else None
    fmt = (lambda v: "%d%%" % round(v * 100)) if unit == "%" else (lambda v: "%g" % round(v, 1))
    return """<figure><figcaption>%s<span>%s</span></figcaption>
<svg viewBox="0 0 %d %d" role="img" aria-label="%s">
<line x1="%d" y1="%d" x2="%d" y2="%d" class="axis"/><line x1="%d" y1="%d" x2="%d" y2="%d" class="axis"/>
<text x="%d" y="%d" class="tick">%s</text><text x="%d" y="%d" class="tick">%s</text>
<text x="%d" y="%d" class="tick">day %d</text><text x="%d" y="%d" class="tick" text-anchor="end">day %d</text>
%s</svg></figure>""" % (
        html.escape(title), fmt(last) if last is not None else "", w, h, html.escape(title),
        pad, 6, pad, h - pad + 6, pad, h - pad + 6, w - 8, h - pad + 6,
        2, 14, fmt(y1), 2, h - pad + 4, fmt(y0),
        pad, h - 6, x0, w - 8, h - 6, x1, "\n".join(lines))


def main():
    src, out = sys.argv[1], sys.argv[2]
    with open(src) as f:
        history = json.load(f)
    builds = sorted(history.items(), key=lambda kv: kv[1].get("when", ""))[-len(PALETTE):]
    legend = "".join('<li><i style="background:%s"></i>%s <small>%s, %d days</small></li>'
                     % (PALETTE[max(0, len(PALETTE) - len(builds) + i)], html.escape(sha),
                        html.escape(run.get("when", "")), len(run.get("days", [])))
                     for i, (sha, run) in enumerate(builds))
    charts = "\n".join(c for c in (chart(t, r, u, builds) for t, r, u in CHARTS) if c)
    page = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Long Game Dashboard</title>
<style>
:root{--bg:#fbfaf7;--fg:#22252a;--muted:#6b7280;--rule:#d9d6cf;--card:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#16181c;--fg:#e6e3dc;--muted:#9aa1ab;--rule:#3a3d44;--card:#1e2126}}
body{margin:0;padding:24px 16px;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,sans-serif}
h1{font-size:20px;margin:0 0 4px}p{color:var(--muted);margin:0 0 16px;max-width:60ch}
ul{list-style:none;padding:0;margin:0 0 20px;display:flex;flex-wrap:wrap;gap:6px 16px}
li i{display:inline-block;width:14px;height:4px;margin-right:6px;vertical-align:middle;border-radius:2px}
small{color:var(--muted)}
main{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px}
figure{margin:0;background:var(--card);border:1px solid var(--rule);border-radius:8px;padding:10px 12px}
figcaption{display:flex;justify-content:space-between;font-weight:600;font-size:13px;margin-bottom:4px}
figcaption span{font-variant-numeric:tabular-nums;color:var(--muted);font-weight:500}
svg{width:100%%;height:auto}.axis{stroke:var(--rule)}.tick{fill:var(--muted);font-size:10px}
</style></head><body>
<h1>The long game, build against build</h1>
<p>One line per build, newest thickest. Each point is one game day of the long test game
(a village of eight left alone). The number at the top right of each chart is the newest build's last day.</p>
<ul>%s</ul><main>%s</main></body></html>""" % (legend, charts)
    with open(out, "w") as f:
        f.write(page)


if __name__ == "__main__":
    main()
