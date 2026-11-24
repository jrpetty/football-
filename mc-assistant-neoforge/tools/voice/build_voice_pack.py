#!/usr/bin/env python3
"""[pa35] Build the Player AI Assistant's offline voice pack.

Downloads the natural voice's files (the same pinned URLs and SHA-256 sums the mod uses, from GitHub and Maven Central),
checks every one, and writes mc_assistant-voice-pack.zip. A player drops that zip into their Minecraft config folder
(config/ or config/mc_assistant/voice/); the mod unpacks and checks it at start, with no network at all.

Standard library only:  python3 build_voice_pack.py [--out DIR] [--cache DIR]
"""
import argparse
import hashlib
import os
import re
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
KOKORO_JAVA = os.path.join(HERE, "..", "..", "src", "main", "java", "com", "jrpetty", "mcassistant", "pa", "voice", "PaKokoro.java")


def parts():
    """The mod's own list (PaKokoro.PARTS), read from its source so the two can never disagree."""
    src = open(KOKORO_JAVA, encoding="utf-8").read()
    consts = dict(re.findall(r'(\w+) = "([^"]+)"', src))
    out = []
    for m in re.finditer(r'new Part\("([^"]+)", ([^,]+), ([^,]+), ([^,]+), "([0-9a-f]{64})", "([^"]+)"\)', src):
        what, url, file, size, sha, licence = m.groups()
        def val(expr):
            expr = expr.strip()
            total = ""
            for piece in expr.split("+"):
                piece = piece.strip()
                total += piece[1:-1] if piece.startswith('"') else consts.get(piece, "")
            return total
        url = val(url).replace("\" + ORT_VERSION + \"", consts.get("ORT_VERSION", ""))
        if "ORT_URL" in m.group(2):
            url = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/%s/onnxruntime-%s.jar" % (consts["ORT_VERSION"], consts["ORT_VERSION"])
        size = int(re.sub(r"[^0-9]", "", {"MODEL_BYTES": "92_361_271", "VOICES_BYTES": "28_214_398", "ORT_BYTES": "92_987_839"}.get(size.strip(), size)))
        out.append((what, url, val(file), size, sha, licence))
    if len(out) < 8:
        sys.exit("could not read the file list from PaKokoro.java (%d found)" % len(out))
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url, to, size):
    have = os.path.getsize(to) if os.path.exists(to) else 0
    if have == size:
        return
    req = urllib.request.Request(url, headers={"User-Agent": "minecraft-mod", **({"Range": "bytes=%d-" % have} if 0 < have < size else {})})
    with urllib.request.urlopen(req, timeout=60) as r, open(to, "ab" if r.status == 206 else "wb") as f:
        while True:
            block = r.read(1 << 20)
            if not block:
                break
            f.write(block)
            sys.stdout.write("\r  %s: %d MB" % (os.path.basename(to), f.tell() // 1_000_000))
            sys.stdout.flush()
    print()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=".", help="where to write the zip")
    ap.add_argument("--cache", default=os.path.join(HERE, ".voice-cache"), help="where downloads are kept between runs")
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)
    listed = parts()
    for what, url, file, size, sha, _ in listed:
        path = os.path.join(a.cache, file)
        print("%s <- %s" % (what, url))
        fetch(url, path, size)
        if os.path.getsize(path) != size or sha256(path) != sha:
            os.remove(path)
            sys.exit("%s failed its size or SHA-256 check; removed. Run again." % file)
    out = os.path.join(a.out, "mc_assistant-voice-pack.zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        for what, url, file, size, sha, licence in listed:
            z.write(os.path.join(a.cache, file), "kokoro/" + file)
        z.writestr("kokoro/README.txt", "Player AI Assistant offline voice pack.\nPut this zip in your Minecraft config folder; it is unpacked and checked at start.\n\n"
                   + "\n".join("%s  %s  %s  (%s)" % (sha, file, url, licence) for what, url, file, size, sha, licence in listed) + "\n")
    print("wrote %s (%d MB)" % (out, os.path.getsize(out) // 1_000_000))


if __name__ == "__main__":
    main()
