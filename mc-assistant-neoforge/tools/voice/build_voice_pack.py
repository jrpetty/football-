#!/usr/bin/env python3
"""[pa35] Build the Player AI Assistant's offline voice pack.

Downloads the natural voice's files (the same pinned URLs and SHA-256 sums the mod uses, from GitHub and Maven Central),
checks every one, and writes mc_assistant-voice-pack.zip. A player drops that zip into their Minecraft config folder
(config/ or config/mc_assistant/voice/); the mod unpacks and checks it at start, with no network at all.

[voice37] --expressive also writes mc_assistant-expressive-voice-pack.zip, the optional expressive voice (Chatterbox-Turbo,
MIT, about 570 MB, from its Hugging Face page at the pinned revision). Drop it in beside the first; it needs the natural
pack (its ONNX Runtime) too. A modpack can ship both zips in its config folder.

Standard library only:  python3 build_voice_pack.py [--out DIR] [--cache DIR] [--expressive]
"""
import argparse
import hashlib
import os
import re
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
VOICE_JAVA = os.path.join(HERE, "..", "..", "src", "main", "java", "com", "jrpetty", "mcassistant", "pa", "voice")
KOKORO_JAVA = os.path.join(VOICE_JAVA, "PaKokoro.java")
EXPRESSIVE_JAVA = os.path.join(VOICE_JAVA, "PaExpressive.java")


def parts(java=KOKORO_JAVA, least=8):
    """The mod's own list (PaKokoro.PARTS or PaExpressive.PARTS), read from its source so the two can never disagree."""
    src = open(java, encoding="utf-8").read()
    consts = dict(re.findall(r'(\w+) = "([^"]+)"', src))
    sizes = dict(re.findall(r'(\w+) = ([0-9_]+)L', src))
    out = []
    for m in re.finditer(r'new (?:PaKokoro\.)?Part\("([^"]+)", ([^,]+), ([^,]+), ([^,]+), "([0-9a-f]{64})", "([^"]+)"\)', src):
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
        size = int(re.sub(r"[^0-9]", "", sizes.get(size.strip(), size)))
        out.append((what, url, val(file), size, sha, licence))
    if len(out) < least:
        sys.exit("could not read the file list from %s (%d found)" % (os.path.basename(java), len(out)))
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
    ap.add_argument("--expressive", action="store_true", help="also build the optional expressive voice pack (about 570 MB more)")
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)
    pack(a, parts(), "mc_assistant-voice-pack.zip", "kokoro", "Player AI Assistant offline voice pack (the natural voice).")
    if a.expressive:
        pack(a, parts(EXPRESSIVE_JAVA, 7), "mc_assistant-expressive-voice-pack.zip", "expressive",
             "Player AI Assistant expressive voice pack (optional; needs the natural voice pack too).\n"
             "Chatterbox-Turbo by Resemble AI, MIT licence. The voices' conditioning ships in the mod itself, made from the\n"
             "CSTR VCTK Corpus 0.92 (University of Edinburgh), CC BY 4.0.")


def pack(a, listed, name, folder, title):
    for what, url, file, size, sha, _ in listed:
        path = os.path.join(a.cache, file)
        print("%s <- %s" % (what, url))
        fetch(url, path, size)
        if os.path.getsize(path) != size or sha256(path) != sha:
            os.remove(path)
            sys.exit("%s failed its size or SHA-256 check; removed. Run again." % file)
    out = os.path.join(a.out, name)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        for what, url, file, size, sha, licence in listed:
            z.write(os.path.join(a.cache, file), folder + "/" + file)
        z.writestr(folder + "/README.txt", title + "\nPut this zip in your Minecraft config folder; it is unpacked and checked at start.\n\n"
                   + "\n".join("%s  %s  %s  (%s)" % (sha, file, url, licence) for what, url, file, size, sha, licence in listed) + "\n")
    print("wrote %s (%d MB)" % (out, os.path.getsize(out) // 1_000_000))


if __name__ == "__main__":
    main()
