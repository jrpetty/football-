#!/usr/bin/env python3
"""
For the modpack scenario: put the newest release of each named mod that will actually load on
the NeoForge this project runs (gradle.properties neo_version) into run/mods. The newest JEI
asks for a newer NeoForge than the dev server's, and refused to start; so each candidate jar's
own neoforge.mods.toml is read, and the first whose NeoForge range admits ours is kept.

    python3 fetch_mods.py jei jade
"""
import io
import json
import re
import sys
import urllib.request
import zipfile


def neo_version():
    with open("gradle.properties") as f:
        for line in f:
            if line.startswith("neo_version="):
                return tuple(int(x) for x in line.split("=", 1)[1].strip().split("."))
    return (21, 1, 0)


def version_tuple(s):
    return tuple(int(x) for x in re.findall(r"\d+", s)[:3])


def admits(range_text, mine):
    """A Maven range like [21.1.238,) or [21.1,): is ours at or above its lower bound?"""
    m = re.match(r"\s*[\[(]\s*([0-9.]*)", range_text or "")
    if not m or not m.group(1):
        return True
    low = version_tuple(m.group(1))
    return mine[:len(low)] >= low


def neoforge_range(jar_bytes):
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as z:
        for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if name in z.namelist():
                text = z.read(name).decode("utf-8", "replace")
                # the [[dependencies.*]] block whose modId is "neoforge"
                for block in re.split(r"\[\[dependencies", text)[1:]:
                    if re.search(r'modId\s*=\s*"neoforge"', block):
                        r = re.search(r'versionRange\s*=\s*"([^"]*)"', block)
                        return r.group(1) if r else ""
    return ""


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "mc-assistant-ci"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def main():
    mine = neo_version()
    for mod in sys.argv[1:]:
        api = ("https://api.modrinth.com/v2/project/%s/version?loaders=%%5B%%22neoforge%%22%%5D"
               "&game_versions=%%5B%%221.21.1%%22%%5D" % mod)
        versions = [v for v in json.loads(get(api)) if v.get("version_type") == "release" and v.get("files")]
        # Newest first; a newer release never needs an older NeoForge than the one before it,
        # so the newest that loads is found by halving: a handful of downloads, not a hundred.
        cache = {}

        def fits(i):
            if i not in cache:
                jar = get(versions[i]["files"][0]["url"])
                rng = neoforge_range(jar)
                cache[i] = (admits(rng, mine), jar, rng)
            return cache[i][0]

        chosen = None
        lo, hi = 0, len(versions) - 1
        if versions and fits(hi):
            while lo < hi:
                mid = (lo + hi) // 2
                if fits(mid):
                    hi = mid
                else:
                    lo = mid + 1
            fits(lo)
            ok, jar, rng = cache[lo]
            with open("run/mods/%s.jar" % mod, "wb") as out:
                out.write(jar)
            chosen = "%s %s (needs neoforge %s)" % (mod, versions[lo].get("version_number"), rng or "any")
        print("modpack: " + (chosen or "%s: no release loads on neoforge %s" % (mod, ".".join(map(str, mine)))))


if __name__ == "__main__":
    main()
