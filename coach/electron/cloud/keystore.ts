// The API key store. The key is encrypted with the OS keychain (Electron
// safeStorage: DPAPI on Windows, Keychain on macOS, libsecret/kwallet on
// Linux) and kept in the main process only. If OS encryption is unavailable
// the key is held in memory for this run and never written to disk in
// plaintext. The renderer only ever learns whether a key is set and its last
// four characters.

import { readFile, rm, writeFile } from 'node:fs/promises'
import type { KeyStatus } from '../../shared/cloud.ts'

export type { KeyStatus }

export interface Cipher {
  readonly available: boolean
  encrypt(text: string): Buffer
  decrypt(data: Buffer): string
}

const KEY_SHAPE = /^sk-ant-[A-Za-z0-9_-]{20,300}$/

export class KeyStore {
  private key: string | null = null
  private persisted = false
  private readonly path: string
  private readonly cipher: Cipher

  constructor(path: string, cipher: Cipher) {
    this.path = path
    this.cipher = cipher
  }

  async load(): Promise<void> {
    if (!this.cipher.available) return
    try {
      this.key = this.cipher.decrypt(await readFile(this.path))
      this.persisted = true
    } catch {
      this.key = null
    }
  }

  get(): string | null {
    return this.key
  }

  async set(key: string | null): Promise<KeyStatus> {
    if (key === null) {
      this.key = null
      this.persisted = false
      await rm(this.path, { force: true })
      return this.status
    }
    const trimmed = key.trim()
    if (!KEY_SHAPE.test(trimmed)) throw new Error('That does not look like an Anthropic API key (sk-ant-…).')
    this.key = trimmed
    if (this.cipher.available) {
      await writeFile(this.path, this.cipher.encrypt(trimmed))
      this.persisted = true
    } else {
      this.persisted = false
    }
    return this.status
  }

  get status(): KeyStatus {
    return {
      set: this.key !== null,
      last4: this.key ? this.key.slice(-4) : null,
      persisted: this.persisted,
      encryptionAvailable: this.cipher.available,
    }
  }
}
