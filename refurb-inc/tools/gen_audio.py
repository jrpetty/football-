#!/usr/bin/env python3
"""Synthesised placeholder sound effects for Refurb Inc.

Writes 44.1 kHz mono 16-bit WAVs into assets/audio/{sfx,ui,ambience}/.
Swap any file for a real recording with the SAME name (variants: name_1.wav, name_2.wav …).
"""
import os
import wave

import numpy as np

SR = 44100
ROOT = os.path.join(os.path.dirname(__file__), "..", "assets", "audio")
rng = np.random.default_rng(7)


def t(d):
    return np.arange(int(SR * d)) / SR


def env(n, a=0.005, d=0.1, s=0.0, r=0.05, total=None):
    """ADSR envelope of n samples (times in seconds)."""
    e = np.zeros(n)
    ai, di, ri = int(a * SR), int(d * SR), int(r * SR)
    ai = max(ai, 1)
    e[:ai] = np.linspace(0, 1, ai)
    end_d = min(ai + di, n)
    e[ai:end_d] = np.linspace(1, s, end_d - ai) if end_d > ai else []
    e[end_d:max(n - ri, end_d)] = s
    if ri > 0 and n - ri > end_d:
        e[n - ri:] = np.linspace(s, 0, ri)
    return e


def exp_decay(n, tau):
    return np.exp(-np.arange(n) / (tau * SR))


def lowpass(x, cutoff):
    # one-pole low-pass
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.zeros_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc = (1 - a) * x[i] + a * acc
        y[i] = acc
    return y


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def bandpass(x, lo, hi):
    return lowpass(highpass(x, lo), hi)


def noise(d):
    return rng.uniform(-1, 1, int(SR * d))


def sine(f, d, phase=0.0):
    return np.sin(2 * np.pi * f * t(d) + phase)


def square(f, d):
    return np.sign(np.sin(2 * np.pi * f * t(d)))


def norm(x, peak=0.85):
    m = np.max(np.abs(x)) or 1.0
    return x / m * peak


def write(sub, name, x, peak=0.85, loop_fade=False):
    d = os.path.join(ROOT, sub)
    os.makedirs(d, exist_ok=True)
    x = norm(x, peak)
    if not loop_fade:
        fade = min(int(0.004 * SR), len(x) // 4)
        x[-fade:] *= np.linspace(1, 0, fade)
    data = (x * 32767).astype(np.int16)
    with wave.open(os.path.join(d, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())
    print("wrote", sub, name)


def loopable(x, xfade=0.15):
    """Cross-fade the end into the start so the sample loops seamlessly."""
    n = int(xfade * SR)
    head = x[:n].copy()
    tail = x[-n:].copy()
    ramp = np.linspace(0, 1, n)
    x = x[: len(x) - n].copy()
    x[:n] = tail * (1 - ramp) + head * ramp
    return x


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[: len(p)] += p
    return out


# ----------------------------------------------------------------- world sfx

def footsteps():
    for i in range(1, 5):
        d = 0.16
        n = int(SR * d)
        scuff = bandpass(noise(d), 300 + i * 60, 3500) * exp_decay(n, 0.03)
        thump = sine(70 + i * 6, d) * exp_decay(n, 0.02) * 0.8
        write("sfx", "footstep_%d" % i, scuff * 0.7 + thump, 0.6)


def handling():
    d = 0.18
    n = int(SR * d)
    write("sfx", "pickup", bandpass(noise(d), 800, 6000) * exp_decay(n, 0.02) + sine(220, d) * exp_decay(n, 0.015) * 0.4, 0.5)
    d = 0.3
    n = int(SR * d)
    clunk = sine(110, d) * exp_decay(n, 0.05) + sine(240, d) * exp_decay(n, 0.02) * 0.5 + bandpass(noise(d), 200, 2500) * exp_decay(n, 0.015)
    write("sfx", "place", clunk, 0.7)
    write("sfx", "impact_light", bandpass(noise(0.2), 400, 4000) * exp_decay(int(SR * 0.2), 0.02) + sine(180, 0.2) * exp_decay(int(SR * 0.2), 0.03), 0.6)
    d = 0.6
    n = int(SR * d)
    write("sfx", "impact_heavy", sine(55, d) * exp_decay(n, 0.12) + lowpass(noise(d), 600) * exp_decay(n, 0.06) * 2, 0.9)
    write("sfx", "pallet_drop", sine(48, 0.9) * exp_decay(int(SR * 0.9), 0.18) + lowpass(noise(0.9), 900) * exp_decay(int(SR * 0.9), 0.08) * 3
          + bandpass(noise(0.9), 1500, 5000) * exp_decay(int(SR * 0.9), 0.03) * 0.6, 0.95)
    d = 0.5
    n = int(SR * d)
    rip = bandpass(noise(d), 600, 5000) * (0.6 + 0.4 * np.abs(np.sin(2 * np.pi * 23 * t(d)))) * env(n, 0.01, 0.4, 0.0, 0.05)
    write("sfx", "box_open", rip, 0.6)


def electronics():
    # Classic single short POST beep
    d = 0.14
    write("sfx", "post_beep", square(1000, d) * env(int(SR * d), 0.002, 0.13, 1.0, 0.004) * 0.6, 0.45)
    # Pass: rising two-tone; fail: two low beeps
    a = sine(880, 0.12) * env(int(SR * 0.12), 0.005, 0.1, 0.0, 0.01)
    b = sine(1320, 0.22) * env(int(SR * 0.22), 0.005, 0.2, 0.0, 0.02)
    write("sfx", "test_pass", np.concatenate([a, np.zeros(int(SR * 0.04)), b]), 0.55)
    lo = square(330, 0.16) * env(int(SR * 0.16), 0.003, 0.15, 1.0, 0.01) * 0.5
    write("sfx", "test_fail", np.concatenate([lo, np.zeros(int(SR * 0.08)), lo]), 0.5)
    # Laptop fan loop: air noise + blade whine with slight wobble
    d = 3.0
    air = bandpass(noise(d), 250, 4000)
    wob = 1 + 0.02 * np.sin(2 * np.pi * 0.7 * t(d))
    whine = np.sin(2 * np.pi * 2350 * np.cumsum(wob) / SR) * 0.08
    write("sfx", "fan_loop", loopable(air * 0.5 + whine), 0.35, loop_fade=True)
    # Thermal label printer: stepper buzz in pulses
    d = 0.9
    steps = np.sin(2 * np.pi * 620 * t(d)) * (np.sin(2 * np.pi * 40 * t(d)) > 0) * 0.6 + bandpass(noise(d), 1500, 6000) * 0.2
    write("sfx", "label_print", steps * env(int(SR * d), 0.01, 0.8, 1.0, 0.05), 0.5)
    # Screwdriver ratchet loop
    d = 1.0
    clicks = np.zeros(int(SR * d))
    for k in range(10):
        s = int(k * 0.1 * SR + rng.integers(0, 600))
        n = int(0.012 * SR)
        clicks[s:s + n] += bandpass(noise(0.012), 2000, 9000) * exp_decay(n, 0.003)
    write("sfx", "repair_loop", loopable(clicks, 0.02), 0.45, loop_fade=True)
    done = np.concatenate([bandpass(noise(0.03), 2000, 8000) * exp_decay(int(SR * 0.03), 0.006), sine(1568, 0.25) * exp_decay(int(SR * 0.25), 0.08) * 0.5])
    write("sfx", "repair_done", done, 0.5)
    # Tape gun screech
    d = 0.75
    f = np.linspace(900, 1600, int(SR * d))
    screech = np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.3 + bandpass(noise(d), 1200, 7000) * 0.8
    write("sfx", "tape_gun", screech * env(int(SR * d), 0.02, 0.6, 0.8, 0.08), 0.55)


def building():
    d = 3.2
    rattle = bandpass(noise(d), 300, 5000) * (0.5 + 0.5 * np.abs(np.sin(2 * np.pi * 14 * t(d))))
    motor = sine(95, d) * 0.4 + sine(190, d) * 0.15
    write("sfx", "roller_door", (rattle + motor) * env(int(SR * d), 0.2, 2.8, 1.0, 0.3), 0.6)
    d = 3.5
    f = np.linspace(200, 1400, int(SR * d))
    boil = bandpass(noise(d), 300, 4000) * np.linspace(0.2, 1, int(SR * d)) + np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.03
    click = np.zeros(int(SR * 0.05))
    click[:200] = bandpass(noise(200 / SR), 1000, 8000)[:200]
    write("sfx", "kettle", np.concatenate([boil * env(int(SR * d), 0.3, 3.0, 1.0, 0.2), click]), 0.5)


def tea():
    # Pouring the brew and a satisfied sip (first-person tea break).
    d = 1.6
    gurgle = bandpass(noise(d), 500, 3500) * (0.6 + 0.4 * np.abs(np.sin(2 * np.pi * 7 * t(d))))
    write("sfx", "tea_pour", gurgle * env(int(SR * d), 0.08, 1.2, 0.9, 0.3), 0.45)
    d = 0.7
    f = np.linspace(900, 2600, int(SR * d))
    slurp = bandpass(noise(d), 700, 5000) * (0.5 + 0.5 * np.sin(2 * np.pi * np.cumsum(f / 300.0) / SR * 40))
    write("sfx", "tea_sip", slurp * env(int(SR * d), 0.05, 0.45, 0.6, 0.2), 0.35)
    d = 0.6
    ahh = (sine(180, d) * 0.5 + sine(360, d) * 0.2 + bandpass(noise(d), 300, 1500) * 0.4) * env(int(SR * d), 0.05, 0.4, 0.5, 0.25)
    write("sfx", "tea_ahh", ahh, 0.3)


def vehicles():
    # Reversing beeper: 1 s loop (beep 0.5 s, gap 0.5 s)
    d = 1.0
    beep = square(1100, 0.5) * 0.7 + sine(2200, 0.5) * 0.2
    beep *= env(len(beep), 0.004, 0.49, 1.0, 0.006)
    write("sfx", "reverse_beep", np.concatenate([beep, np.zeros(int(SR * 0.5))]), 0.55, loop_fade=True)
    # Diesel idle: low firing harmonics + clatter
    d = 3.0
    fire = sum(np.sin(2 * np.pi * 28 * k * t(d) + rng.random() * 6) / k for k in range(1, 9))
    clatter = bandpass(noise(d), 800, 3000) * (np.sin(2 * np.pi * 28 * t(d)) > 0.6) * 0.3
    write("sfx", "diesel_idle", loopable(fire + clatter + lowpass(noise(d), 200) * 0.6), 0.6, loop_fade=True)
    fire2 = sum(np.sin(2 * np.pi * 45 * k * t(d) + rng.random() * 6) / k for k in range(1, 7))
    write("sfx", "van_engine", loopable(fire2 + lowpass(noise(d), 400) * 0.5), 0.5, loop_fade=True)
    d = 1.2
    write("sfx", "air_brake", highpass(noise(d), 2000) * env(int(SR * d), 0.01, 1.1, 0.0, 0.05), 0.6)
    d = 2.2
    f = np.linspace(300, 380, int(SR * d))
    write("sfx", "hydraulic", (np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.4 + bandpass(noise(d), 200, 1500) * 0.5) * env(int(SR * d), 0.1, 2.0, 1.0, 0.1), 0.5)
    d = 0.4
    n = int(SR * d)
    write("sfx", "van_door", sine(70, d) * exp_decay(n, 0.06) + bandpass(noise(d), 300, 4000) * exp_decay(n, 0.03) * 1.5, 0.8)


# ----------------------------------------------------------------- ui

def ui():
    d = 0.05
    write("ui", "ui_click", sine(1800, d) * exp_decay(int(SR * d), 0.008) + bandpass(noise(d), 3000, 9000) * exp_decay(int(SR * d), 0.003) * 0.3, 0.4)
    write("ui", "error", np.concatenate([square(220, 0.09), np.zeros(int(SR * 0.03)), square(196, 0.12)]) * 0.4, 0.35)
    def chime(freqs, step=0.09, tail=0.35):
        parts = []
        for i, f in enumerate(freqs):
            dd = tail if i == len(freqs) - 1 else step
            parts.append(sine(f, dd) * 0.6 * exp_decay(int(SR * dd), 0.12) + sine(f * 2, dd) * 0.15 * exp_decay(int(SR * dd), 0.06))
        return np.concatenate(parts)
    write("ui", "notify_good", chime([784, 1175]), 0.45)
    write("ui", "notify_warn", chime([660, 660]), 0.4)
    write("ui", "notify_bad", chime([523, 392]), 0.45)
    write("ui", "pc_open", chime([523, 659, 784], 0.06, 0.25), 0.35)
    write("ui", "pc_close", chime([784, 523], 0.06, 0.2), 0.3)
    write("ui", "day_end", chime([523, 659, 784, 1047], 0.14, 0.8), 0.5)
    # Till "ka-ching"
    d = 0.6
    ding = sine(2637, d) * exp_decay(int(SR * d), 0.18) + sine(3520, d) * exp_decay(int(SR * d), 0.12) * 0.6
    drawer = bandpass(noise(0.08), 1000, 6000) * exp_decay(int(SR * 0.08), 0.02)
    write("ui", "cash", mix(drawer, np.concatenate([np.zeros(int(SR * 0.05)), ding])), 0.45)


def ambience():
    d = 8.0
    traffic = lowpass(noise(d), 350) * (0.6 + 0.4 * np.sin(2 * np.pi * 0.11 * t(d)))
    birds = np.zeros(int(SR * d))
    for k in range(9):
        s = int(rng.uniform(0.2, d - 0.4) * SR)
        dd = rng.uniform(0.08, 0.18)
        f = np.linspace(rng.uniform(3000, 4200), rng.uniform(2200, 5000), int(SR * dd))
        chirp = np.sin(2 * np.pi * np.cumsum(f) / SR) * env(int(SR * dd), 0.01, dd - 0.02, 0.0, 0.01)
        birds[s:s + len(chirp)] += chirp * 0.15
    write("ambience", "street_ambience", loopable(traffic * 2 + birds, 0.5), 0.4, loop_fade=True)
    d = 2.0
    hum = sine(100, d) * 0.6 + sine(200, d) * 0.25 + sine(300, d) * 0.1 + bandpass(noise(d), 4000, 9000) * 0.05
    write("ambience", "fluorescent_hum", loopable(hum, 0.2), 0.25, loop_fade=True)


if __name__ == "__main__":
    footsteps()
    handling()
    electronics()
    building()
    vehicles()
    ui()
    ambience()
    # "footstep" (no suffix) resolves to footstep_1..4 variants in Sfx.
