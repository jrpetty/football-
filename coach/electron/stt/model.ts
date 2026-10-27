// The local speech-recognition model (for "talk to the coach"). It is not
// shipped with the app: the player opts in, and the main process downloads one
// pinned archive from the sherpa-onnx GitHub releases, verifies its SHA-256,
// and unpacks only the files the recogniser needs into the app's data folder.
// After that, speech recognition runs entirely on this computer; audio is
// never uploaded.

import { createHash } from 'node:crypto'
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { untar } from './tar.ts'

const require = createRequire(import.meta.url)

export const VOICE_MODEL = {
  id: 'moonshine-tiny-en-int8',
  label: 'Moonshine tiny (English), int8',
  license: 'MIT (Useful Sensors); runtime sherpa-onnx, Apache-2.0',
  url: 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2',
  sha256: 'd5fe6ec4334fef36255b2a4010412cad4c007e33103fec62fb5d17cad88086f2',
  archiveBytes: 107_600_538,
  root: 'sherpa-onnx-moonshine-tiny-en-int8',
  files: ['preprocess.onnx', 'encode.int8.onnx', 'uncached_decode.int8.onnx', 'cached_decode.int8.onnx', 'tokens.txt', 'LICENSE'],
} as const

export type { VoiceModelStatus as ModelStatus } from '../../shared/bridge.ts'

export function modelDir(root: string): string {
  return join(root, 'models', VOICE_MODEL.id)
}

export async function isInstalled(root: string): Promise<boolean> {
  for (const f of VOICE_MODEL.files) {
    const s = await stat(join(modelDir(root), f)).catch(() => null)
    if (!s?.isFile() || s.size === 0) return false
  }
  return true
}

/** Unpack a verified archive buffer into the model folder (only the listed files). */
export async function unpackArchive(archive: Buffer, root: string): Promise<void> {
  const digest = createHash('sha256').update(archive).digest('hex')
  if (digest !== VOICE_MODEL.sha256) throw new Error('The downloaded model did not match its expected checksum; nothing was installed.')
  const Bunzip = require('seek-bzip') as { decode(input: Buffer): Buffer }
  const tar = Bunzip.decode(archive)
  const wanted = new Set(VOICE_MODEL.files.map((f) => `${VOICE_MODEL.root}/${f}`))
  const dir = modelDir(root)
  const staging = `${dir}.partial`
  await rm(staging, { recursive: true, force: true })
  await mkdir(staging, { recursive: true })
  let written = 0
  for (const entry of untar(tar)) {
    if (!wanted.has(entry.name)) continue
    await writeFile(join(staging, entry.name.slice(VOICE_MODEL.root.length + 1)), entry.data)
    written++
  }
  if (written !== wanted.size) throw new Error('The model archive was missing files; nothing was installed.')
  await rm(dir, { recursive: true, force: true })
  await rename(staging, dir)
}

/** Download, verify and unpack. `onProgress` gets 0..1. */
export async function installModel(root: string, onProgress: (p: number) => void, fetchImpl: typeof fetch = fetch): Promise<void> {
  const res = await fetchImpl(VOICE_MODEL.url, { redirect: 'follow' })
  if (!res.ok || !res.body) throw new Error(`Model download failed (HTTP ${res.status}).`)
  const chunks: Uint8Array[] = []
  let received = 0
  const reader = res.body.getReader()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
    received += value.length
    onProgress(Math.min(0.99, received / VOICE_MODEL.archiveBytes))
  }
  await unpackArchive(Buffer.concat(chunks), root)
  onProgress(1)
}

export async function removeModel(root: string): Promise<void> {
  await rm(modelDir(root), { recursive: true, force: true })
}

/** For tests: read an archive from disk instead of downloading it. */
export async function installFromFile(path: string, root: string): Promise<void> {
  await unpackArchive(await readFile(path), root)
}
