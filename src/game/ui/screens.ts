import { sfx } from '../audio/sfx'
import { store } from '../core/store'
import { binds, label as keyLabel, ACTIONS, DEFAULT_BINDS } from '../core/bindings'
import type { Action } from '../core/bindings'
import { playerName, setPlayerName } from '../net/identity'
import type { InputManager } from '../core/input'
import type { MatchConfig, Role } from '../types'

// DOM overlays: main menu, pause, and full-time screens. Kept out of the canvas
// so they can use real buttons, focus, and accessible markup.
export class Screens {
  root: HTMLElement
  // Everything the menu can set is remembered. Turning the game on and finding
  // it exactly as you left it is not a feature anybody notices, which is the
  // point — having to redo it every time is the thing they notice.
  private config: MatchConfig = {
    teamSize: store.get('teamSize'),
    halfLength: store.get('halfLength'),
    mode: 'match',
    singleKeeper: store.get('singleKeeper'),
    view: store.get('view'),
    position: store.get('position') as Role,
    heightSens: store.get('heightSens'),
    curveSens: store.get('curveSens'),
  }

  onStart: (config: MatchConfig) => void = () => {}
  onOnline: (config: MatchConfig) => void = () => {}
  onResume: () => void = () => {}
  onRestart: () => void = () => {}
  onMenu: () => void = () => {}

  // The rebinding rows need to hear a raw keypress, which only the input
  // manager sees. Set by boot(); without it the controls list is read-only.
  input: InputManager | null = null

  constructor(root: HTMLElement) {
    this.root = root
  }

  hide() {
    this.cancelCapture()
    this.root.innerHTML = ''
    this.root.style.display = 'none'
  }

  show() {
    this.cancelCapture()
    this.root.style.display = 'flex'
  }

  // Stop waiting for a key. A rebind row arms itself and then waits for a
  // keypress that may never come: click "Jump", change your mind, hit Resume,
  // and the very next key you pressed in the match was swallowed and bound to
  // jump instead of doing what you asked. Leaving a screen abandons the rebind.
  private cancelCapture() {
    if (this.input) this.input.capture = null
  }

  showMenu() {
    this.show()
    this.root.innerHTML = `
      <div class="panel menu">
        <div class="brand">
          <div class="logo">⚽</div>
          <div>
            <h1>OPEN PITCH</h1>
            <p class="tag">Physics-driven, input-driven football. Every touch is yours.</p>
          </div>
        </div>

        <div class="settings">
          <div class="field">
            <label>Your name <span class="hint">shown over your head online</span></label>
            <input class="netinput" data-f="name" maxlength="14" spellcheck="false" value="${esc(playerName())}" />
          </div>
          <div class="field">
            <label>View</label>
            <div class="segmented" data-group="view">
              ${this.segStr('view', '3d', this.config.view === '3d', '🎮 Immersive 3D')}
              ${this.segStr('view', '2d', this.config.view === '2d', '🗺️ Classic 2D')}
            </div>
          </div>
          <div class="field">
            <label>Your position <span class="hint">you play this one player all match</span></label>
            <div class="segmented" data-group="position">
              ${this.segStr('position', 'FWD', this.config.position === 'FWD', 'Striker')}
              ${this.segStr('position', 'MID', this.config.position === 'MID', 'Midfield')}
              ${this.segStr('position', 'DEF', this.config.position === 'DEF', 'Defender')}
              ${this.segStr('position', 'GK', this.config.position === 'GK', 'Keeper')}
            </div>
          </div>
          <div class="field">
            <label>Team size <span class="hint">(per side, incl. keeper)</span></label>
            <div class="segmented" data-group="teamSize">
              ${[3, 4, 5, 6].map((n) => this.seg('teamSize', n, n === this.config.teamSize, `${n}v${n}`)).join('')}
            </div>
          </div>
          <div class="field">
            <label>Half length</label>
            <div class="segmented" data-group="halfLength">
              ${[
                [60, '1 min'],
                [120, '2 min'],
                [180, '3 min'],
              ]
                .map(([v, l]) => this.seg('halfLength', v as number, v === this.config.halfLength, l as string))
                .join('')}
            </div>
          </div>
          <div class="field">
            <label>Keepers</label>
            <div class="segmented" data-group="singleKeeper">
              ${this.seg('singleKeeper', 0, !this.config.singleKeeper, 'Both')}
              ${this.seg('singleKeeper', 1, this.config.singleKeeper, 'Home only')}
            </div>
          </div>
          <div class="field">
            <label>Kick height sensitivity <span class="hint">how much an up-flick lifts it</span></label>
            <div class="slider">
              <input type="range" min="0.4" max="5" step="0.05" value="${this.config.heightSens}" data-sens="height" />
              <output>${this.config.heightSens.toFixed(2)}</output>
            </div>
          </div>
          <div class="field">
            <label>Kick curve sensitivity <span class="hint">how much a side-flick bends it</span></label>
            <div class="slider">
              <input type="range" min="0.2" max="3" step="0.05" value="${this.config.curveSens}" data-sens="curve" />
              <output>${this.config.curveSens.toFixed(2)}</output>
            </div>
          </div>
        </div>

        <div class="actions">
          <button class="btn primary" data-act="online">🌐  Play Online <span class="sub">(host or join)</span></button>
          <button class="btn" data-act="training">🎯  Training <span class="sub">(solo drills)</span></button>
        </div>

        ${this.controlsHtml()}
        <p class="foot">
          Nothing on this pitch plays itself. Training is you, a ball and two
          empty goals — press <b>${keyLabel(binds.get('spawnBall'))}</b> to put the ball
          back in front of you, <b>${keyLabel(binds.get('nextDrill'))}</b> for the
          next drill. A match is other people: every shirt is a
          seat, and the ones nobody has taken just stand there.
        </p>
      </div>`
    this.wireMenu()
  }

  private seg(group: string, value: number, active: boolean, label: string): string {
    return `<button class="seg ${active ? 'active' : ''}" data-seg="${group}" data-val="${value}">${label}</button>`
  }

  private segStr(group: string, value: string, active: boolean, label: string): string {
    return `<button class="seg ${active ? 'active' : ''}" data-seg="${group}" data-val="${value}">${label}</button>`
  }

  // The mouse half of the vocabulary, which is fixed: the whole design is that
  // two buttons do everything, so there is nothing here to rebind.
  private mouseRows(): [string, string][] {
    return [
      ['Aim / Look', 'Mouse'],
      ['<b>Touch</b> — close control', '<b>Right click</b> · tap or hold'],
      ['Touch to the side / back', 'move + right click'],
      ['<b>Skill move</b> — drag / roll / lift', 'Right click + <b>hard flick</b>'],
      ['<b>Backheel</b> <span class="hint">right click only</span>', 'Right click + flick down while backing away'],
      ['<b>Strike</b> — pass or shot', '<b>Left click</b> · hold = power'],
      ['<b>Cushion</b> a ball out of the air', 'Right click while it drops'],
      ['<b>Volley / header</b>', 'Left click while it drops'],
      ['<b>Win the ball back</b>', 'Just right / left click it'],
      ['<b>Keeper: dive</b>', '<b>Left click</b> · hold = distance, flick = height'],
      ['<b>Keeper: gather</b>', '<b>Right click</b>'],
    ]
  }

  // Every key, and every one of them changeable. Eleven bindings is past the
  // point where a fixed layout is defensible — and Q for shield in particular
  // is a choice no player would ever guess at.
  private keysHtml(): string {
    const groups: ActionInfoGroup[] = ['Moving', 'On the ball', 'Match']
    const section = (g: ActionInfoGroup) => {
      const rows = ACTIONS.filter((a) => a.group === g)
        .map((a) => {
          const alt = a.also?.length ? `<span class="alt">or ${a.also.map(keyLabel).join(' / ')}</span>` : ''
          return `<div class="k">${a.label}</div>
            <div class="v"><button class="keybtn" data-bind="${a.action}">${keyLabel(binds.get(a.action))}</button>${alt}</div>`
        })
        .join('')
      return `<h4>${g}</h4><div class="grid">${rows}</div>`
    }
    return `<div class="keybinds">
      ${groups.map(section).join('')}
      <div class="actions">
        <button class="btn ghost small" data-act="resetkeys">Reset to defaults</button>
      </div>
      <p class="keyhint">
        Click a key and press the one you want. <b>Esc</b> can't be rebound —
        it's the only way out of pointer lock, so binding it away would leave
        you with no way back to this screen. Giving a key a second job takes it
        off the first.
      </p>
    </div>`
  }

  private controlsHtml(): string {
    return `<div class="controls">
      <h3>Controls</h3>
      <div class="grid">
        ${this.mouseRows().map(([a, b]) => `<div class="k">${a}</div><div class="v">${b}</div>`).join('')}
      </div>
      ${this.keysHtml()}
      <div class="flickhint">
        <b>Height &amp; curve come from your mouse.</b> As your strike is charging,
        <b>flick the mouse up</b> to lift the ball, <b>flick down</b> to drive it low,
        and <b>flick sideways</b> to bend it — the ball curves the way you drag.
        Flick diagonally to do both. Over-flick and you'll skin it.
      </div>
      <div class="flickhint">
        <b>Nothing is glued to anybody.</b> The ball is never attached to a
        player — it bounces off bodies and runs free unless someone is actually
        playing it. That cuts both ways: your own touches have to be judged, and
        an opponent's ball is always takeable. There is no tackle button, so you
        win it back with the same two clicks you use for everything else —
        <b>right click</b> to take it off them and keep it, <b>left click</b> to
        hammer it clear. <b>${keyLabel(binds.get('slide'))}</b> slides: the hitbox is your own body and nothing
        more, so it's all timing.
      </div>
      <div class="flickhint">
        <b>${keyLabel(binds.get('jump'))} jumps.</b> Everything you can reach goes up with you — control
        height, aerial reach, the height at which a contact becomes a header —
        so a ball that would sail over your head is one you can attack. In the
        air you have nothing to push against, so whatever you left the ground
        with is what you land with: it's a commitment, not a dodge.
      </div>
      <div class="flickhint">
        <b>Hold ${keyLabel(binds.get('shield'))} to shield.</b> You turn side-on, drop to a shuffle and lose
        the ability to strike — but your body goes wide and soft, so the ball
        dies against you instead of pinging away, and nobody can play a ball your
        shoulder is in front of. Nothing is attached to you: keeping it is purely
        a question of where you stand. Right click to knock it out and go again.
      </div>
      <div class="flickhint">
        <b>The same wrist works on the ball at your feet.</b> Flick hard during a
        <b>touch</b> to drag it back, roll it across your body, or lift it over a
        leg — and flick down while you're already backing away and it's a
        backheel instead, struck past you rather than pulled under you.
        <b>Every one of those is the right button only.</b> You cannot backheel
        with a left click, which means you cannot get a shot's power on one —
        a heel is not a swing, and the touch's ceiling is the whole cost of
        playing the ball behind you. And the ball doesn't have to be on the
        floor: <b>right click</b> a dropping ball to cushion it dead (flick up
        to keep it in the air), <b>left click</b> to volley it — or, above head
        height, to head it.
      </div>
    </div>`
  }

  // Shared by the menu and the pause screen, both of which show the key list.
  private wireKeys() {
    this.root.querySelectorAll<HTMLButtonElement>('[data-bind]').forEach((el) => {
      el.addEventListener('click', () => {
        if (!this.input) return
        const action = el.dataset.bind as Action
        el.classList.add('listening')
        el.textContent = 'press a key…'
        this.input.capture = (code) => {
          const r = binds.bind(action, code)
          el.classList.remove('listening')
          if (!r.ok) {
            el.textContent = keyLabel(binds.get(action))
            el.title = r.why ?? ''
            el.classList.add('bad')
            setTimeout(() => el.classList.remove('bad'), 900)
            return
          }
          // Rewrite the whole list: taking a key off another action has to show
          // up on that row too, or it silently reads as still bound.
          this.refreshKeys()
        }
      })
    })
    this.root.querySelector('[data-act="resetkeys"]')?.addEventListener('click', () => {
      binds.reset()
      this.refreshKeys()
    })
  }

  private refreshKeys() {
    this.root.querySelectorAll<HTMLButtonElement>('[data-bind]').forEach((el) => {
      const a = el.dataset.bind as Action
      el.classList.remove('listening')
      const code = binds.get(a)
      el.textContent = keyLabel(code)
      // An action whose key was taken by something else is unbound, and looks it.
      el.classList.toggle('unbound', !code)
      el.title = code ? '' : `${defaultLabel(a)} by default — click to set one`
    })
  }

  private wireMenu() {
    this.root.querySelectorAll<HTMLButtonElement>('.seg').forEach((el) => {
      el.addEventListener('click', () => {
        const group = el.dataset.seg as string
        const raw = el.dataset.val ?? ''
        el.parentElement?.querySelectorAll('.seg').forEach((s) => s.classList.remove('active'))
        el.classList.add('active')
        if (group === 'view') store.set('view', (this.config.view = raw === '2d' ? '2d' : '3d'))
        else if (group === 'position') store.set('position', (this.config.position = raw as MatchConfig['position']))
        else if (group === 'teamSize') store.set('teamSize', (this.config.teamSize = Number(raw)))
        else if (group === 'halfLength') store.set('halfLength', (this.config.halfLength = Number(raw)))
        else if (group === 'singleKeeper') store.set('singleKeeper', (this.config.singleKeeper = Number(raw) === 1))
      })
    })
    this.root.querySelectorAll<HTMLInputElement>('input[data-sens]').forEach((el) => {
      el.addEventListener('input', () => {
        const v = Number(el.value)
        if (el.dataset.sens === 'height') store.set('heightSens', (this.config.heightSens = v))
        else store.set('curveSens', (this.config.curveSens = v))
        const out = el.parentElement?.querySelector('output')
        if (out) out.textContent = v.toFixed(2)
      })
    })
    this.root.querySelector<HTMLInputElement>('[data-f="name"]')?.addEventListener('input', (e) => {
      setPlayerName((e.target as HTMLInputElement).value)
    })
    this.wireKeys()
    this.root.querySelector<HTMLButtonElement>('[data-act="__gone"]')?.addEventListener('click', () => {
      sfx.unlock()
      this.onStart({ ...this.config, mode: 'match' })
    })
    this.root.querySelector<HTMLButtonElement>('[data-act="online"]')?.addEventListener('click', () => {
      sfx.unlock()
      this.onOnline({ ...this.config, mode: 'match' })
    })
    this.root.querySelector<HTMLButtonElement>('[data-act="training"]')?.addEventListener('click', () => {
      sfx.unlock()
      this.onStart({ ...this.config, mode: 'training' })
    })
  }

  showPause() {
    this.show()
    this.root.innerHTML = `
      <div class="panel pause">
        <h2>Paused</h2>
        <div class="actions col">
          <button class="btn primary" data-act="resume">Resume</button>
          <button class="btn" data-act="restart">Restart</button>
          <button class="btn ghost" data-act="menu">Main Menu</button>
        </div>
        ${this.controlsHtml()}
      </div>`
    this.root.querySelector('[data-act="resume"]')?.addEventListener('click', () => this.onResume())
    this.root.querySelector('[data-act="restart"]')?.addEventListener('click', () => this.onRestart())
    this.root.querySelector('[data-act="menu"]')?.addEventListener('click', () => this.onMenu())
    // Pausing to change a key you got wrong mid-match is the main reason
    // anybody opens this screen.
    this.wireKeys()
  }

  showEnd(homeScore: number, awayScore: number, stats: string) {
    this.show()
    const result =
      homeScore === awayScore ? 'Draw' : homeScore > awayScore ? 'Home win' : 'Away win'
    this.root.innerHTML = `
      <div class="panel pause">
        <h2>Full Time</h2>
        <div class="bigscore">${homeScore} – ${awayScore}</div>
        <p class="tag">${result}</p>
        <div class="statline">${stats}</div>
        <div class="actions col">
          <button class="btn primary" data-act="restart">Rematch</button>
          <button class="btn ghost" data-act="menu">Main Menu</button>
        </div>
      </div>`
    this.root.querySelector('[data-act="restart"]')?.addEventListener('click', () => this.onRestart())
    this.root.querySelector('[data-act="menu"]')?.addEventListener('click', () => this.onMenu())
  }
}

type ActionInfoGroup = (typeof ACTIONS)[number]['group']

// What a key would be if you hadn't taken it, for the tooltip on an unbound row.
function defaultLabel(a: Action): string {
  return keyLabel(DEFAULT_BINDS[a])
}

// A name is whatever somebody typed, and it goes into an attribute.
function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
