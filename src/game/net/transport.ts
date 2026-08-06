import type { Msg } from './protocol'

// How bytes actually get from one player to another.
//
// Two ways, because they solve different problems:
//
//   WebRTC, with the offer and answer copy-pasted between the two players. No
//     server exists anywhere in this path — the browsers talk to each other
//     directly. It costs one exchange of text to set up, and after that it is
//     the lowest-latency option there is. This is the one that works today,
//     with nothing to host and nothing to pay for.
//
//   WebSocket, to a relay. Someone has to run `npm run relay`, but then joining
//     is a four-letter room code and any number of people can join.
//
// Both are the same interface, so nothing above this file knows or cares.

export interface Transport {
  readonly kind: 'rtc' | 'ws'
  send(m: Msg): void
  onMessage: (m: Msg, from: string) => void
  onOpen: () => void
  onClose: (why: string) => void
  close(): void
  readonly open: boolean
}

// ---------------------------------------------------------------- WebRTC ----

// A direct peer-to-peer link. `signal` is the blob of text the two players
// exchange: the host produces an offer, the guest turns it into an answer, and
// the host pastes that back. Ugly, and completely free of infrastructure.
export class RtcTransport implements Transport {
  readonly kind = 'rtc' as const
  onMessage: (m: Msg, from: string) => void = () => {}
  onOpen: () => void = () => {}
  onClose: (why: string) => void = () => {}

  private pc: RTCPeerConnection
  private ch: RTCDataChannel | null = null
  private peerName = 'peer'

  constructor(peerName = 'peer') {
    this.peerName = peerName
    this.pc = new RTCPeerConnection({
      // A public STUN server only helps discover your own address; if it can't
      // be reached the connection still works on a local network.
      iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
    })
    this.pc.onconnectionstatechange = () => {
      const st = this.pc.connectionState
      if (st === 'failed' || st === 'disconnected' || st === 'closed') this.onClose(st)
    }
  }

  get open(): boolean {
    return this.ch?.readyState === 'open'
  }

  private bind(ch: RTCDataChannel) {
    this.ch = ch
    ch.onopen = () => this.onOpen()
    ch.onclose = () => this.onClose('channel closed')
    ch.onmessage = (e) => {
      try {
        this.onMessage(JSON.parse(e.data as string) as Msg, this.peerName)
      } catch {
        /* a malformed frame is not worth tearing the game down for */
      }
    }
  }

  // Host side: create the channel and produce an offer to hand over.
  async createOffer(): Promise<string> {
    // Ordered and reliable. An unordered, no-retransmit channel is the textbook
    // choice for a game — a dropped input is better skipped than resent — but it
    // is an optimisation, and correctness comes first: the handshake and the
    // snapshots both have to arrive.
    const ch = this.pc.createDataChannel('play', { ordered: true })
    this.bind(ch)
    const offer = await this.pc.createOffer()
    await this.pc.setLocalDescription(offer)
    return this.gathered()
  }

  // Guest side: take the host's offer, produce an answer to hand back.
  async acceptOffer(offerBlob: string): Promise<string> {
    this.pc.ondatachannel = (e) => this.bind(e.channel)
    await this.pc.setRemoteDescription(decode(offerBlob))
    const answer = await this.pc.createAnswer()
    await this.pc.setLocalDescription(answer)
    return this.gathered()
  }

  // Host side: finish the handshake with the guest's answer.
  async acceptAnswer(answerBlob: string): Promise<void> {
    await this.pc.setRemoteDescription(decode(answerBlob))
  }

  // Wait for ICE to finish so the blob we hand over is complete — otherwise the
  // players would have to exchange candidates separately, which is unusable by
  // copy-paste.
  private gathered(): Promise<string> {
    return new Promise((resolve) => {
      if (this.pc.iceGatheringState === 'complete') return resolve(encode(this.pc.localDescription!))
      const done = () => {
        if (this.pc.iceGatheringState === 'complete') {
          this.pc.removeEventListener('icegatheringstatechange', done)
          resolve(encode(this.pc.localDescription!))
        }
      }
      this.pc.addEventListener('icegatheringstatechange', done)
      // Don't hang forever if a candidate source is unreachable.
      setTimeout(() => resolve(encode(this.pc.localDescription!)), 2500)
    })
  }

  send(m: Msg) {
    if (this.ch?.readyState === 'open') this.ch.send(JSON.stringify(m))
  }

  close() {
    try {
      this.ch?.close()
      this.pc.close()
    } catch {
      /* already gone */
    }
  }
}

const encode = (d: RTCSessionDescriptionInit) =>
  btoa(JSON.stringify({ t: d.type, s: d.sdp })).replace(/=+$/, '')
const decode = (blob: string): RTCSessionDescriptionInit => {
  const o = JSON.parse(atob(blob.trim().replace(/\s+/g, '')))
  return { type: o.t, sdp: o.s }
}

// ------------------------------------------------------------- WebSocket ----

// A relay. Everything is forwarded verbatim by the server, which knows nothing
// about football — it is a postbox, so the host is still the only authority.
export class WsTransport implements Transport {
  readonly kind = 'ws' as const
  onMessage: (m: Msg, from: string) => void = () => {}
  onOpen: () => void = () => {}
  onClose: (why: string) => void = () => {}

  private ws: WebSocket
  private ready = false

  constructor(
    url: string,
    private room: string,
    private role: 'host' | 'guest',
    private name: string,
  ) {
    this.ws = new WebSocket(url)
    this.ws.onopen = () => {
      this.ws.send(JSON.stringify({ rtype: 'join', room: this.room, role: this.role, name: this.name }))
    }
    this.ws.onmessage = (e) => {
      let o: { rtype?: string; from?: string; body?: Msg; why?: string }
      try {
        o = JSON.parse(e.data as string)
      } catch {
        return
      }
      if (o.rtype === 'joined') {
        this.ready = true
        this.onOpen()
      } else if (o.rtype === 'error') {
        this.onClose(o.why ?? 'relay refused the room')
      } else if (o.rtype === 'peer-left') {
        this.onClose('the other player left')
      } else if (o.body) {
        this.onMessage(o.body, o.from ?? 'peer')
      }
    }
    this.ws.onclose = () => this.onClose('relay disconnected')
    this.ws.onerror = () => this.onClose('could not reach the relay')
  }

  get open(): boolean {
    return this.ready && this.ws.readyState === WebSocket.OPEN
  }

  send(m: Msg) {
    if (this.open) this.ws.send(JSON.stringify({ rtype: 'msg', body: m }))
  }

  close() {
    try {
      this.ws.close()
    } catch {
      /* already gone */
    }
  }
}
