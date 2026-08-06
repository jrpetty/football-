import { RtcTransport, WsTransport } from './transport'
import type { Transport } from './transport'

// The online lobby.
//
// Two routes, deliberately: the direct one needs nothing at all, and the relay
// one needs someone to run a script. Whichever you pick, what comes out the far
// end is a Transport and a role, and everything above here stops caring.

export type NetRole = 'host' | 'guest'
export interface NetHandoff {
  role: NetRole
  transport: Transport
  label: string
}

export class Lobby {
  onCancel: () => void = () => {}

  private rtc: RtcTransport | null = null
  private handedOver = false

  // The link can report itself open more than once — an immediate check plus
  // the event that follows it. Starting the match twice would build a second
  // World and leave the first one holding the connection, so this fires once.
  private ready: (h: NetHandoff) => void = () => {}
  set onReady(fn: (h: NetHandoff) => void) {
    this.ready = fn
  }
  private handOver(h: NetHandoff) {
    if (this.handedOver) return
    this.handedOver = true
    this.ready(h)
  }

  render(root: HTMLElement) {
    root.innerHTML = `
      <div class="panel menu net">
        <div class="brand"><div class="logo">🌐</div><div>
          <h1>PLAY ONLINE</h1>
          <p class="tag">The host runs the match. Everyone else plays a shirt in it.</p>
        </div></div>

        <div class="netcols">
          <div class="netcol">
            <h3>Direct — no server</h3>
            <p class="nethint">
              Costs one exchange of text and then your browsers talk to each
              other. Nothing to run, nothing to host. Best latency there is.
            </p>
            <div class="actions">
              <button class="btn primary" data-net="rtc-host">Host a game</button>
              <button class="btn" data-net="rtc-join">Join a game</button>
            </div>
            <div class="netstep" data-step="rtc"></div>
          </div>

          <div class="netcol">
            <h3>Relay — short codes</h3>
            <p class="nethint">
              Someone runs <code>npm run relay</code> and everyone points at it.
              Joining is then a room code you can say out loud, and more than two
              can play.
            </p>
            <label>Relay address</label>
            <input class="netinput" data-f="url" value="ws://localhost:8787" spellcheck="false" />
            <label>Room code</label>
            <input class="netinput" data-f="room" value="PITCH" spellcheck="false" />
            <div class="actions">
              <button class="btn primary" data-net="ws-host">Host here</button>
              <button class="btn" data-net="ws-join">Join here</button>
            </div>
          </div>
        </div>

        <div class="netstatus" data-status></div>
        <div class="actions"><button class="btn" data-net="back">← Back</button></div>
      </div>`
    this.wire(root)
  }

  private wire(root: HTMLElement) {
    const status = root.querySelector<HTMLElement>('[data-status]')!
    const step = root.querySelector<HTMLElement>('[data-step="rtc"]')!
    const val = (f: string) => root.querySelector<HTMLInputElement>(`[data-f="${f}"]`)?.value.trim() ?? ''
    const say = (t: string) => (status.textContent = t)

    const ws = (role: NetRole) => {
      const url = val('url')
      const room = val('room').toUpperCase()
      if (!url || !room) return say('A relay address and a room code, please.')
      say(`Connecting to ${url} as ${role}…`)
      const t = new WsTransport(url, room, role, role === 'host' ? 'host' : 'guest')
      t.onOpen = () => this.handOver({ role, transport: t, label: `relay ${room}` })
      t.onClose = (why) => say(`Couldn't connect: ${why}`)
    }

    root.querySelectorAll<HTMLButtonElement>('[data-net]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const act = btn.dataset.net
        if (act === 'back') return this.onCancel()
        if (act === 'ws-host') return ws('host')
        if (act === 'ws-join') return ws('guest')

        if (act === 'rtc-host') {
          say('Building your invite…')
          const t = new RtcTransport('guest')
          this.rtc = t
          const offer = await t.createOffer()
          step.innerHTML = `
            <label>1. Send this to the other player</label>
            <textarea class="netblob" readonly>${offer}</textarea>
            <label>2. Paste their reply here</label>
            <textarea class="netblob" data-f="answer" placeholder="their reply…"></textarea>
            <div class="actions"><button class="btn primary" data-go>Start the match</button></div>`
          step.querySelector<HTMLTextAreaElement>('.netblob')?.select()
          step.querySelector('[data-go]')?.addEventListener('click', async () => {
            const answer = step.querySelector<HTMLTextAreaElement>('[data-f="answer"]')!.value.trim()
            if (!answer) return say('Paste their reply first.')
            try {
              await t.acceptAnswer(answer)
              say('Connecting…')
              t.onOpen = () => this.handOver({ role: 'host', transport: t, label: 'direct' })
              // The channel may already be open by the time we get here.
              if (t.open) this.handOver({ role: 'host', transport: t, label: 'direct' })
            } catch {
              say("That reply didn't parse — copy the whole thing.")
            }
          })
          say('Waiting on their reply.')
        }

        if (act === 'rtc-join') {
          step.innerHTML = `
            <label>1. Paste the host's invite</label>
            <textarea class="netblob" data-f="offer" placeholder="their invite…"></textarea>
            <div class="actions"><button class="btn primary" data-go>Generate my reply</button></div>
            <div data-out></div>`
          step.querySelector('[data-go]')?.addEventListener('click', async () => {
            const offer = step.querySelector<HTMLTextAreaElement>('[data-f="offer"]')!.value.trim()
            if (!offer) return say('Paste their invite first.')
            const t = new RtcTransport('host')
            this.rtc = t
            try {
              const answer = await t.acceptOffer(offer)
              step.querySelector<HTMLElement>('[data-out]')!.innerHTML = `
                <label>2. Send this back to them</label>
                <textarea class="netblob" readonly>${answer}</textarea>`
              step.querySelectorAll<HTMLTextAreaElement>('.netblob')[1]?.select()
              say('Sent? Then wait — the match starts the moment they accept it.')
              t.onOpen = () => this.handOver({ role: 'guest', transport: t, label: 'direct' })
            } catch {
              say("That invite didn't parse — copy the whole thing.")
            }
          })
        }
      })
    })
  }

  dispose() {
    this.rtc?.close()
    this.rtc = null
    this.handedOver = false
  }
}
