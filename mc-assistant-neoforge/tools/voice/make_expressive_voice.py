#!/usr/bin/env python3
"""[voice37] Make an expressive voice's conditioning file (.mcav) from a licensed reference recording.

The expressive voice (Chatterbox-Turbo, MIT) speaks in the voice of a short reference recording. Rather than make every
player download the 350 MB speech encoder and a recording, the encoder is run once here and its four outputs are written
into a small file that ships in the mod jar (src/main/resources/assets/mc_assistant/voice/expressive/<id>.mcav), which
PaExpressive.readCond reads.

Only use recordings whose licence allows this (the shipped voices come from the CSTR VCTK Corpus, CC BY 4.0, which needs
the attribution in PaExpressive.ATTRIBUTION and the guide). Never a celebrity or a person who hasn't agreed.

    pip install onnxruntime numpy soundfile
    # speech_encoder.onnx + speech_encoder.onnx_data from ResembleAI/chatterbox-turbo-ONNX (onnx/), any precision
    python3 tools/voice/make_expressive_voice.py --encoder DIR/speech_encoder.onnx --wav ref.wav --out gb_m_p226.mcav

Format: b"MCAV", int32 version 1, then four arrays (prompt embedding, prompt speech tokens, speaker embedding, speaker
features), each: kind byte (0 float16, 1 int16), dimension count byte, int32 dimensions, values; all little-endian.
"""
import argparse
import struct

import numpy as np


def load(path, rate=24000):
    import soundfile as sf
    a, sr = sf.read(path, dtype="float32")
    if a.ndim > 1:
        a = a.mean(axis=1)
    if sr != rate:
        n = int(round(len(a) * rate / sr))
        a = np.interp(np.linspace(0, len(a) - 1, n), np.arange(len(a)), a).astype(np.float32)
    return a


def write(path, arrays):
    with open(path, "wb") as f:
        f.write(b"MCAV")
        f.write(struct.pack("<i", 1))
        for a in arrays:
            ints = np.issubdtype(a.dtype, np.integer)
            if ints and (a.min() < -32768 or a.max() > 32767):
                raise ValueError("token out of int16 range")
            f.write(struct.pack("<BB", 1 if ints else 0, a.ndim))
            for d in a.shape:
                f.write(struct.pack("<i", int(d)))
            f.write((a.astype("<i2") if ints else a.astype("<f2")).tobytes())


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--encoder", required=True, help="speech_encoder*.onnx (its .onnx_data beside it)")
    ap.add_argument("--wav", required=True, help="the licensed reference recording (5 to 15 seconds of clean speech)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    import onnxruntime as ort
    s = ort.InferenceSession(a.encoder, providers=["CPUExecutionProvider"])
    audio = load(a.wav)[None, :]
    emb, tokens, spk, feat = s.run(None, {"audio_values": audio})
    write(a.out, [emb.astype(np.float32), tokens.astype(np.int64), spk.astype(np.float32), feat.astype(np.float32)])
    print("%s: prompt %s, tokens %s, speaker %s, features %s" % (a.out, emb.shape, tokens.shape, spk.shape, feat.shape))


if __name__ == "__main__":
    main()
