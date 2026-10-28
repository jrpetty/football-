// A reader for Counter-Strike 2's .nav files (Source 2 nav mesh, versions 31-36), written from the
// layout documented by ValveResourceFormat and awpy's MIT-licensed parser. Areas come out as convex
// polygons in world units with their connections.

export function readNav(buf) {
  let pos = 0
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength)
  const need = (n) => {
    if (pos + n > buf.length) throw new Error(`unexpected end of data at ${pos}`)
  }
  const u8 = () => (need(1), buf[pos++])
  const u32 = () => (need(4), (pos += 4), dv.getUint32(pos - 4, true))
  const i32 = () => (need(4), (pos += 4), dv.getInt32(pos - 4, true))
  const f32 = () => (need(4), (pos += 4), dv.getFloat32(pos - 4, true))
  const skip = (n) => (need(n), (pos += n))
  const vec3 = () => [f32(), f32(), f32()]
  const skipKv3 = () => {
    skip((8 - (pos % 8)) % 8)
    const magic = u32()
    const v = magic & 0xff
    if ((magic & 0xffffff00) >>> 0 !== 0x4b563300 || v < 2 || v > 5) throw new Error(`unsupported KV3 ${magic.toString(16)}`)
    skip(16)
    const method = u32()
    skip(4 + 16 + 4)
    const uncTotal = i32()
    const cmpTotal = i32()
    const blocks = i32()
    const blobs = i32()
    let blockCmp = 0
    if (v >= 4) {
      skip(4)
      blockCmp = i32()
    }
    let unc1 = uncTotal, cmp1 = cmpTotal, unc2 = 0, cmp2 = 0
    if (v >= 5) {
      unc1 = i32(); cmp1 = i32(); unc2 = i32(); cmp2 = i32()
      skip(32)
    }
    if (method > 2 || blocks || blobs) throw new Error('KV3 with data')
    skip((method === 0 ? unc1 : cmp1) + (method === 0 ? unc2 : cmp2) + blockCmp)
  }
  if (u32() !== 0xfeedface) throw new Error('not a nav file')
  const version = u32()
  if (version < 31 || version > 36) throw new Error(`nav version ${version}`)
  const sub = u32()
  u32()
  if (version >= 36) skipKv3()
  const corners = []
  for (let n = u32(); n > 0; n--) corners.push(vec3())
  const polygons = []
  for (let n = u32(); n > 0; n--) {
    const k = u8()
    const poly = []
    for (let i = 0; i < k; i++) {
      const ci = u32()
      if (ci >= corners.length) throw new Error('corner index out of range')
      poly.push(corners[ci])
    }
    if (version >= 35) skip(4)
    polygons.push(poly)
  }
  if (version >= 32) skip(4)
  if (version >= 35) {
    for (let n = u32(); n > 0; n--) {
      while (u8() !== 0);
      skip(48)
    }
  }
  if (version >= 36) skipKv3()
  const areas = []
  for (let n = u32(); n > 0; n--) {
    const id = u32()
    skip(8) // dynamic attribute flags (i64)
    const hull = u8()
    const pi = u32()
    if (pi >= polygons.length) throw new Error('polygon index out of range')
    const poly = polygons[pi]
    skip(4)
    const links = []
    for (let e = 0; e < poly.length; e++) for (let c = u32(); c > 0; c--) (links.push(u32()), u32())
    skip(5)
    const up = []
    for (let c = u32(); c > 0; c--) up.push(u32())
    const down = []
    for (let c = u32(); c > 0; c--) down.push(u32())
    areas.push({ id, hull, poly, links, up, down })
  }
  return { version, sub, areas, rest: buf.length - pos }
}
