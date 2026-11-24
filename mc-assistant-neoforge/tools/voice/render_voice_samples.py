#!/usr/bin/env python3
"""[pa35] Render sample clips of the Player AI Assistant's default natural voices saying its own lines.

For each default voice (British bm_george and bf_emma, American am_michael and af_heart) it says five of the assistant's
real lines (read from its phrase banks): a gloomy one, a joker's, a serious one, a reaction to its player's death and a
roast. Phonemes come from misaki (the dictionaries the mod downloads), the sound is finished as in the game (silence
trimmed, -16 LUFS, a soft limiter under -1 dBFS, short fades) and written as 24 kHz WAV, plus MP3 when ffmpeg is there.

Runs on CI or a player's machine (not in a locked-down sandbox):
    pip install kokoro-onnx misaki[en] soundfile numpy
    python3 tools/voice/build_voice_pack.py --out /tmp/pack   # or download the model files yourself
    python3 tools/voice/render_voice_samples.py --models DIR --out samples/
"""
import argparse
import math
import os
import random
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LINES = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "mc_assistant", "pa")
VOICES = {"bm_george": "en-gb", "bf_emma": "en-gb", "am_michael": "en-us", "af_heart": "en-us"}
SAMPLES = [("gloomy", "say.greet@gloomy"), ("joker", "joke"), ("serious", "say.greet@serious"), ("death", "death.lava"), ("roast", "stranger.roast.clean")]


def bank():
    out, key = {}, None
    for f in sorted(os.listdir(LINES)):
        if not f.startswith("lines_"):
            continue
        for raw in open(os.path.join(LINES, f), encoding="utf-8"):
            t = raw.strip()
            if not t or t.startswith("#"):
                continue
            if t.startswith("== "):
                key = t[3:].strip()
                out.setdefault(key, [])
            elif key:
                out[key].append(re.sub(r"^(\[[^\]]+\]\s*)+", "", t))
    return out


def fill(line):
    return (line.replace("{owner}", "Dev").replace("{name}", "Basil").replace("{player}", "Alex").replace("{what}", "logs")
            .replace("{item}", "iron").replace("{thing}", "steak"))


def biquad(x, b0, b1, b2, a1, a2):
    import numpy as np
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i in range(len(x)):
        v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, x[i], y1, v
        y[i] = v
    return y


def lufs(a, rate):
    """ITU-R BS.1770 integrated loudness (K-weighted, gated), as the mod's PaAudio measures it."""
    import numpy as np
    k = math.tan(math.pi * 1681.974450955533 / rate)
    vh = 10 ** (3.999843853973347 / 20)
    vb = vh ** 0.4996667741545416
    q = 0.7071752369554196
    a0 = 1 + k / q + k * k
    x = biquad(a, (vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    k = math.tan(math.pi * 38.13547087602444 / rate)
    q = 0.5003270373238773
    a0 = 1 + k / q + k * k
    x = biquad(x, 1, -2, 1, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    block, step = int(0.4 * rate), int(0.1 * rate)
    z = [float(np.mean(x[s:s + block] ** 2)) for s in range(0, max(1, len(x) - block + 1), step)] or [float(np.mean(x ** 2))]
    loud = lambda v: -0.691 + 10 * math.log10(v) if v > 0 else -1000
    gated = [v for v in z if loud(v) > -70]
    if not gated:
        return -70.0
    rel = loud(sum(gated) / len(gated)) - 10
    gated = [v for v in gated if loud(v) > rel]
    return loud(sum(gated) / len(gated))


def finish(audio, rate=24000):
    import numpy as np
    a = np.asarray(audio, dtype=np.float64)
    nz = np.nonzero(np.abs(a) > 10 ** (-50 / 20))[0]
    if len(nz):
        pad = int(0.025 * rate)
        a = a[max(0, nz[0] - pad): nz[-1] + pad]
    a = a * min(32.0, max(0.05, 10 ** ((-16.0 - lufs(a, rate)) / 20)))
    c, knee = 10 ** (-1 / 20), 10 ** (-4 / 20)
    m = np.abs(a)
    over = m > knee
    m[over] = knee + (c - knee) * np.tanh((m[over] - knee) / (c - knee))
    a = np.sign(a) * np.minimum(m, c * 0.9999)
    fi, fo = int(0.006 * rate), int(0.018 * rate)
    a[:fi] *= 0.5 - 0.5 * np.cos(np.pi * np.arange(fi) / fi)
    a[-fo:] *= (0.5 - 0.5 * np.cos(np.pi * np.arange(fo) / fo))[::-1]
    return a.astype(np.float32)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--models", required=True, help="folder with kokoro-v1.0.int8.onnx and voices-v1.0.bin")
    ap.add_argument("--out", default="voice-samples")
    ap.add_argument("--seed", type=int, default=35)
    ap.add_argument("--list", action="store_true", help="only print the chosen lines (lang<TAB>text), to phonemise elsewhere")
    ap.add_argument("--phonemes", help="a JSON map of \"lang|text\" to phonemes (e.g. from the mod's own PaG2p), for exact parity with the game")
    a = ap.parse_args()
    if a.list:
        lines, rnd = bank(), random.Random(a.seed)
        for voice, lang in VOICES.items():
            for name, key in SAMPLES:
                pool = [l for l in lines.get(key, []) if 30 <= len(l) <= 140] or lines.get(key) or ["Right then."]
                print("%s\t%s" % (lang, fill(rnd.choice(pool))))
        return 0
    given = {}
    if a.phonemes:
        import json
        given = json.load(open(a.phonemes, encoding="utf-8"))
    import soundfile
    from kokoro_onnx import Kokoro
    kokoro = Kokoro(os.path.join(a.models, "kokoro-v1.0.int8.onnx"), os.path.join(a.models, "voices-v1.0.bin"))
    try:                                        # misaki: the dictionaries the mod itself uses (needs misaki[en])
        from misaki import en
        g2p = {"en-gb": en.G2P(british=True), "en-us": en.G2P(british=False)}
    except Exception:                           # else kokoro-onnx's own espeak phonemiser (close, a little less natural)
        g2p = None
    lines = bank()
    rnd = random.Random(a.seed)
    os.makedirs(a.out, exist_ok=True)
    ffmpeg = shutil.which("ffmpeg")
    for voice, lang in VOICES.items():
        for name, key in SAMPLES:
            pool = [l for l in lines.get(key, []) if 30 <= len(l) <= 140] or lines.get(key) or ["Right then."]
            text = fill(rnd.choice(pool))
            speed = 0.9 if name == "gloomy" else 1.04 if name == "joker" else 1.0
            if lang + "|" + text in given:
                audio, rate = kokoro.create(given[lang + "|" + text], voice=voice, speed=speed, is_phonemes=True)
            elif g2p:
                phonemes, _ = g2p[lang](text)
                audio, rate = kokoro.create(phonemes, voice=voice, speed=speed, is_phonemes=True)
            else:
                audio, rate = kokoro.create(text, voice=voice, speed=speed, lang=lang)
            wav = os.path.join(a.out, "%s_%s.wav" % (voice, name))
            soundfile.write(wav, finish(audio, rate), rate, subtype="PCM_16")
            if ffmpeg:
                subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", wav, "-codec:a", "libmp3lame", "-q:a", "2", wav[:-4] + ".mp3"], check=False)
            print("%s  %-8s %s  [%.1f LUFS]" % (voice, name, text, lufs(soundfile.read(wav)[0], rate)))
    print("wrote", a.out)


if __name__ == "__main__":
    sys.exit(main())
