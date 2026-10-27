// A minimal reader for POSIX/ustar tar archives (regular files only), enough
// to unpack the speech model. Rejects anything that is not a plain relative
// path, so an archive cannot write outside the target folder.

export interface TarEntry {
  readonly name: string
  readonly data: Buffer
}

export function* untar(buf: Buffer): Generator<TarEntry> {
  let offset = 0
  let longName: string | null = null
  while (offset + 512 <= buf.length) {
    const header = buf.subarray(offset, offset + 512)
    if (header.every((b) => b === 0)) return // end of archive
    const name = longName ?? (field(header, 345, 155) ? `${field(header, 345, 155)}/${field(header, 0, 100)}` : field(header, 0, 100))
    longName = null
    const size = Number.parseInt(field(header, 124, 12).trim() || '0', 8)
    const type = String.fromCharCode(header[156] || 48)
    const dataStart = offset + 512
    const data = buf.subarray(dataStart, dataStart + size)
    offset = dataStart + Math.ceil(size / 512) * 512
    if (type === 'L') {
      longName = data.toString('utf8').replace(/\0.*$/s, '')
      continue
    }
    if (type !== '0' && type !== '\0') continue // directories, links, pax headers: skipped
    const clean = name.replace(/^\.\//, '')
    if (clean.startsWith('/') || clean.split('/').includes('..')) throw new Error(`Unsafe path in archive: ${clean}`)
    yield { name: clean, data }
  }
}

function field(header: Buffer, start: number, length: number): string {
  return header.subarray(start, start + length).toString('utf8').replace(/\0.*$/s, '')
}
