// Local speech models: speech recognition (talk to the coach) and neural
// voices (the coach talking back). None is shipped with the app. The player
// opts in; the main process downloads one pinned archive from the sherpa-onnx
// GitHub releases, verifies its SHA-256, and unpacks only the files the model
// needs into the app's data folder. Decompression runs in a worker thread so
// the app never freezes. After that, everything runs on this computer.

import { createHash } from 'node:crypto'
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { isMainThread, parentPort, Worker, workerData } from 'node:worker_threads'
import { untar } from './stt/tar.ts'

const require = createRequire(import.meta.url)

export interface ModelSpec {
  readonly id: string
  readonly kind: 'asr' | 'tts'
  readonly label: string
  readonly license: string
  readonly url: string
  readonly sha256: string
  readonly archiveBytes: number
  /** Top-level folder inside the archive. */
  readonly root: string
  /** Files that must exist after unpacking (relative to the model folder). */
  readonly files: readonly string[]
  /** Folders unpacked whole (e.g. the phonemiser's data). */
  readonly dirs?: readonly string[]
}

const RELEASES = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'

export const ASR_MODEL: ModelSpec = {
  id: 'moonshine-tiny-en-int8',
  kind: 'asr',
  label: 'Moonshine tiny (English), int8',
  license: 'MIT (Useful Sensors); runtime sherpa-onnx, Apache-2.0',
  url: `${RELEASES}/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2`,
  sha256: 'd5fe6ec4334fef36255b2a4010412cad4c007e33103fec62fb5d17cad88086f2',
  archiveBytes: 107_600_538,
  root: 'sherpa-onnx-moonshine-tiny-en-int8',
  files: ['preprocess.onnx', 'encode.int8.onnx', 'uncached_decode.int8.onnx', 'cached_decode.int8.onnx', 'tokens.txt', 'LICENSE'],
}

/** The larger Moonshine: about the same words right on clear speech in our tests, more robust on real voices, twice as slow. */
export const ASR_MODEL_BASE: ModelSpec = {
  id: 'moonshine-base-en-int8',
  kind: 'asr',
  label: 'Moonshine base (English), int8',
  license: 'MIT (Useful Sensors); runtime sherpa-onnx, Apache-2.0',
  url: `${RELEASES}/asr-models/sherpa-onnx-moonshine-base-en-int8.tar.bz2`,
  sha256: '21870cecaa2e44e4e2bf63e02d1072bed183ccd10284871353bd9d24dad14e5e',
  archiveBytes: 250_807_309,
  root: 'sherpa-onnx-moonshine-base-en-int8',
  files: ['preprocess.onnx', 'encode.int8.onnx', 'uncached_decode.int8.onnx', 'cached_decode.int8.onnx', 'tokens.txt', 'LICENSE'],
}

export const ASR_MODELS = { tiny: ASR_MODEL, base: ASR_MODEL_BASE } as const
export type AsrModelKey = keyof typeof ASR_MODELS

/** Silero VAD: tells speech from other sound (game audio) before anything is transcribed. One small file. */
export const VAD_MODEL = {
  id: 'silero-vad',
  url: `${RELEASES}/asr-models/silero_vad.onnx`,
  sha256: '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6',
  bytes: 643_854,
  file: 'silero_vad.onnx',
  license: 'MIT (Silero Team)',
} as const

export function vadPath(root: string): string {
  return join(root, 'models', VAD_MODEL.id, VAD_MODEL.file)
}

/** Download and verify the voice detector (a single file). */
export async function installVad(root: string, fetchImpl: typeof fetch = fetch): Promise<void> {
  const res = await fetchImpl(VAD_MODEL.url, { redirect: 'follow' })
  if (!res.ok) throw new Error(`Voice detector download failed (HTTP ${res.status}).`)
  const data = Buffer.from(await res.arrayBuffer())
  if (createHash('sha256').update(data).digest('hex') !== VAD_MODEL.sha256) throw new Error('The voice detector did not match its expected checksum; nothing was installed.')
  const path = vadPath(root)
  await mkdir(dirname(path), { recursive: true })
  await writeFile(`${path}.partial`, data)
  await rename(`${path}.partial`, path)
}

export async function isVadInstalled(root: string): Promise<boolean> {
  const s = await stat(vadPath(root)).catch(() => null)
  return !!s?.isFile() && s.size === VAD_MODEL.bytes
}

/** Neural voices. Jenny is fast enough for any PC; Kokoro sounds best but needs a quicker CPU. */
export const TTS_MODELS = {
  jenny: {
    id: 'piper-en_GB-jenny_dioco-medium',
    kind: 'tts',
    label: 'Jenny (British English, Piper)',
    license: 'Piper VITS voice; Jenny dataset by Dioco (see MODEL_CARD); runtime sherpa-onnx, Apache-2.0',
    url: `${RELEASES}/tts-models/vits-piper-en_GB-jenny_dioco-medium.tar.bz2`,
    sha256: 'a0888024569bafbefc05a4b48ddf8419d8dbbf3205f4af37cf7c6f1a87cc20c5',
    archiveBytes: 67_225_842,
    root: 'vits-piper-en_GB-jenny_dioco-medium',
    files: ['en_GB-jenny_dioco-medium.onnx', 'tokens.txt', 'MODEL_CARD'],
    dirs: ['espeak-ng-data'],
  },
  kokoro: {
    id: 'kokoro-int8-en-v0_19',
    kind: 'tts',
    label: 'Kokoro (11 English voices)',
    license: 'Kokoro-82M, Apache-2.0; runtime sherpa-onnx, Apache-2.0',
    url: `${RELEASES}/tts-models/kokoro-int8-en-v0_19.tar.bz2`,
    sha256: 'c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd',
    archiveBytes: 103_248_205,
    root: 'kokoro-int8-en-v0_19',
    files: ['model.int8.onnx', 'voices.bin', 'tokens.txt', 'LICENSE'],
    dirs: ['espeak-ng-data'],
  },
} as const satisfies Record<string, ModelSpec>

export type TtsModelKey = keyof typeof TTS_MODELS

export function modelDir(root: string, spec: ModelSpec): string {
  return join(root, 'models', spec.id)
}

export async function isInstalled(root: string, spec: ModelSpec): Promise<boolean> {
  for (const f of spec.files) {
    const s = await stat(join(modelDir(root, spec), f)).catch(() => null)
    if (!s?.isFile() || s.size === 0) return false
  }
  for (const d of spec.dirs ?? []) {
    const s = await stat(join(modelDir(root, spec), d)).catch(() => null)
    if (!s?.isDirectory()) return false
  }
  return true
}

/** Verify and unpack an archive into the model folder (only the listed files and folders). */
export async function unpackArchive(archive: Buffer, root: string, spec: ModelSpec): Promise<void> {
  const digest = createHash('sha256').update(archive).digest('hex')
  if (digest !== spec.sha256) throw new Error('The downloaded model did not match its expected checksum; nothing was installed.')
  const Bunzip = require('seek-bzip') as { decode(input: Buffer): Buffer }
  const tar = Bunzip.decode(archive)
  const prefix = `${spec.root}/`
  const files = new Set(spec.files)
  const dirs = (spec.dirs ?? []).map((d) => `${d}/`)
  const dir = modelDir(root, spec)
  const staging = `${dir}.partial`
  await rm(staging, { recursive: true, force: true })
  await mkdir(staging, { recursive: true })
  const found = new Set<string>()
  for (const entry of untar(tar)) {
    if (!entry.name.startsWith(prefix)) continue
    const rel = entry.name.slice(prefix.length)
    const inDir = dirs.find((d) => rel.startsWith(d))
    if (!files.has(rel) && !inDir) continue
    const target = join(staging, rel)
    await mkdir(dirname(target), { recursive: true })
    await writeFile(target, entry.data)
    found.add(inDir ?? rel)
  }
  if (found.size !== files.size + dirs.length) throw new Error('The model archive was missing files; nothing was installed.')
  await rm(dir, { recursive: true, force: true })
  await rename(staging, dir)
}

/** Unpack in a worker thread (decompression takes tens of seconds of CPU). */
function unpackInWorker(archive: Buffer, root: string, spec: ModelSpec): Promise<void> {
  return new Promise((resolve, reject) => {
    const copy = new Uint8Array(archive)
    const w = new Worker(new URL(import.meta.url), { workerData: { unpack: true, archive: copy, root, spec } , transferList: [copy.buffer] })
    w.once('message', (m: { ok: boolean; error?: string }) => (m.ok ? resolve() : reject(new Error(m.error))))
    w.once('error', reject)
    w.once('exit', (code) => code !== 0 && reject(new Error(`Model install stopped (exit ${code}).`)))
  })
}

if (!isMainThread && (workerData as { unpack?: boolean } | null)?.unpack && parentPort) {
  const port = parentPort
  const d = workerData as { archive: Uint8Array; root: string; spec: ModelSpec }
  unpackArchive(Buffer.from(d.archive.buffer, d.archive.byteOffset, d.archive.byteLength), d.root, d.spec).then(
    () => port.postMessage({ ok: true }),
    (e: unknown) => port.postMessage({ ok: false, error: e instanceof Error ? e.message : String(e) }),
  )
}

/** Download, verify and unpack. `onProgress` gets 0..1 (download is 0..0.9, unpacking the rest). */
export async function installModel(root: string, spec: ModelSpec, onProgress: (p: number) => void, fetchImpl: typeof fetch = fetch): Promise<void> {
  const res = await fetchImpl(spec.url, { redirect: 'follow' })
  if (!res.ok || !res.body) throw new Error(`Model download failed (HTTP ${res.status}).`)
  const chunks: Uint8Array[] = []
  let received = 0
  const reader = res.body.getReader()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
    received += value.length
    onProgress(Math.min(0.9, (received / spec.archiveBytes) * 0.9))
  }
  await unpackInWorker(Buffer.concat(chunks), root, spec)
  onProgress(1)
}

export async function removeModel(root: string, spec: ModelSpec): Promise<void> {
  await rm(modelDir(root, spec), { recursive: true, force: true })
}

/** Install from an archive already on disk (tests, or a manual download). */
export async function installFromFile(path: string, root: string, spec: ModelSpec): Promise<void> {
  await unpackInWorker(await readFile(path), root, spec)
}
