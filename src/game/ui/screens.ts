import { sfx } from '../audio/sfx'
import { store } from '../core/store'
import type { Saved } from '../core/store'
import { binds, label as keyLabel, ACTIONS, DEFAULT_BINDS } from '../core/bindings'
import type { Action } from '../core/bindings'
import { playerName, setPlayerName } from '../net/identity'
import type { InputManager } from '../core/input'
import type { MatchConfig, Role } from '../types'

// The panes of glass in front of the game: the title screen, the settings
// drawer, pause and full time. DOM rather than canvas, so they are real buttons
// with real focus and real text fields.

// The mark: a ball, reduced to the thing that makes it one — a pentagon and the
// five seams leaving it. Built from its own numbers rather than drawn by hand.
export function glyph(): string {
  const cx = 16
  const cy = 16
  const pts = (r: number) =>
    [0, 1, 2, 3, 4].map((k) => {
      const a = -Math.PI / 2 + (k * 2 * Math.PI) / 5
      return [cx + r * Math.cos(a), cy + r * Math.sin(a)] as const
    })
  const inner = pts(5.4)
  const outer = pts(13.2)
  const seams = inner.map(([x, y], i) => `<line x1="${x.toFixed(2)}" y1="${y.toFixed(2)}" x2="${outer[i][0].toFixed(2)}" y2="${outer[i][1].toFixed(2)}"/>`).join('')
  const pent = inner.map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`).join(' ')
  return `<svg class="glyph" viewBox="0 0 32 32" aria-hidden="true">
    <circle cx="16" cy="16" r="14.4" fill="none" stroke="#c4ff45" stroke-width="1.8"/>
    <g stroke="#c4ff45" stroke-width="1.6" stroke-linecap="round">${seams}</g>
    <polygon points="${pent}" fill="#c4ff45"/>
  </svg>`
}

// A mouse with the buttons that matter lit, for the how-to-play cards.
function mouse(which: 'left' | 'right' | 'both' | 'none', flick = false): string {
  const on = 'fill="#c4ff45"'
  const off = 'fill="rgba(255,255,255,0.07)"'
  return `<svg viewBox="0 0 34 48" aria-hidden="true">
    <path d="M17 2 A14 14 0 0 0 3 16 V22 H17 Z" ${which === 'left' || which === 'both' ? on : off}/>
    <path d="M17 2 A14 14 0 0 1 31 16 V22 H17 Z" ${which === 'right' || which === 'both' ? on : off}/>
    <rect x="3" y="2" width="28" height="44" rx="14" fill="none" stroke="rgba(255,255,255,0.4)" stroke-width="1.5"/>
    <line x1="17" y1="2" x2="17" y2="22" stroke="rgba(255,255,255,0.4)" stroke-width="1.2"/>
    <line x1="3" y1="22" x2="31" y2="22" stroke="rgba(255,255,255,0.3)" stroke-width="1.2"/>
    ${flick ? '<path d="M9 36 Q17 43 25 36" fill="none" stroke="#c4ff45" stroke-width="1.8" stroke-linecap="round"/><path d="M22.6 33.6 L25.4 36.2 L21.8 37.6" fill="none" stroke="#c4ff45" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>' : ''}
  </svg>`
}

export type Tab = 'match' | 'controls' | 'display' | 'audio' | 'how'
const TABS: [Tab, string][] = [
  ['how', 'How to play'],
  ['controls', 'Controls'],
  ['display', 'Display'],
  ['audio', 'Audio'],
  ['match', 'Match'],
]

// Numeric settings that live in the store, with how they read and what, if
// anything, has to happen the moment they change.
const NUMBERS: Record<string, { key: 'lookSens' | 'fov' | 'hudScale' | 'volume'; fmt: (v: number) => string; apply?: (v: number) => void }> = {
  lookSens: { key: 'lookSens', fmt: (v) => `${v.toFixed(2)}×` },
  fov: { key: 'fov', fmt: (v) => `${Math.round(v)}°` },
  hudScale: { key: 'hudScale', fmt: (v) => `${Math.round(v * 100)}%` },
  volume: { key: 'volume', fmt: (v) => `${Math.round(v * 100)}%`, apply: (v) => sfx.setVolume(v) },
}
const SWITCHES: Record<string, { key: 'invertY' | 'showFps' | 'shotPreview'; apply?: (v: boolean) => void }> = {
  invertY: { key: 'invertY' },
  showFps: { key: 'showFps' },
  shotPreview: { key: 'shotPreview' },
}

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
    quality: store.get('quality'),
    position: store.get('position') as Role,
    heightSens: store.get('heightSens'),
    curveSens: store.get('curveSens'),
  }

  onStart: (config: MatchConfig) => void = () => {}
  onOnline: (config: MatchConfig) => void = () => {}
  onResume: () => void = () => {}
  onRestart: () => void = () => {}
  onMenu: () => void = () => {}
  onTutorial: (config: MatchConfig) => void = () => {}
  // Something in the settings drawer changed. A running game listens, so a
  // slider moved from the pause screen is felt the moment you resume.
  onSettings: () => void = () => {}

  // Whether a live scene is showing behind the title screen. Without one the
  // screen paints its own floor and horizon.
  backdrop = false

  // The rebinding rows need to hear a raw keypress, which only the input
  // manager sees. Set by boot(); without it the controls list is read-only.
  input: InputManager | null = null

  constructor(root: HTMLElement) {
    this.root = root
  }

  hide() {
    this.cancelCapture()
    this.closeDrawerKeys()
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

  // ---- the title screen -----------------------------------------------------

  showMenu() {
    this.show()
    this.closeDrawerKeys()
    const s = (group: string, value: string, active: boolean, label: string) =>
      `<button class="seg ${active ? 'active' : ''}" data-seg="${group}" data-val="${value}">${label}</button>`
    this.root.innerHTML = `
      <div class="title ${this.backdrop ? '' : 'bare'}">
        <header class="mast">${glyph()}<span class="word">Open <b>Pitch</b></span></header>

        <main class="hero">
          <div class="eyebrow">Physics football</div>
          <h1 class="headline">Every touch<br />is <em>yours.</em></h1>
          <p class="lede">Two buttons and a ball that goes exactly where your hand says. Nothing plays itself.</p>

          <div class="cta">
            <button class="play primary" data-act="training"><span class="big">Training</span><span class="sub">Solo · unlimited goals</span></button>
            <button class="play" data-act="online"><span class="big">Play online</span><span class="sub">Host or join · up to 6v6</span></button>
          </div>

          <div class="quick">
            <div class="qf">
              <label>Your name</label>
              <input class="text" data-f="name" maxlength="14" spellcheck="false" value="${esc(playerName())}" />
            </div>
            <div class="qf">
              <label>View</label>
              <div class="segmented" data-group="view">
                ${s('view', '3d', this.config.view === '3d', '3D')}${s('view', '2d', this.config.view === '2d', '2D')}
              </div>
            </div>
            <div class="qf">
              <label>Graphics</label>
              <div class="segmented" data-group="quality">
                ${s('quality', 'low', this.config.quality === 'low', 'Low')}${s('quality', 'medium', this.config.quality === 'medium', 'Medium')}${s('quality', 'high', this.config.quality === 'high', 'High')}
              </div>
            </div>
          </div>
          ${store.get('tutorialSeen') ? '' : `<div class="newhere">New here? <button data-act="tutorial">Learn the touch · 2 minutes</button></div>`}
        </main>

        <footer class="linkbar">
          <button class="linkbtn" data-open="how">How to play</button>
          <button class="linkbtn" data-open="controls">Controls</button>
          <button class="linkbtn" data-open="display">Settings</button>
          <span class="fine"><kbd>Esc</kbd> gives you the mouse back</span>
        </footer>
      </div>`
    this.wireMenu()
  }

  private wireMenu() {
    this.wireControls(this.root)
    this.root.querySelector<HTMLInputElement>('[data-f="name"]')?.addEventListener('input', (e) => {
      setPlayerName((e.target as HTMLInputElement).value)
    })
    this.root.querySelector<HTMLButtonElement>('[data-act="online"]')?.addEventListener('click', () => {
      sfx.unlock()
      this.onOnline({ ...this.config, mode: 'match' })
    })
    this.root.querySelector<HTMLButtonElement>('[data-act="training"]')?.addEventListener('click', () => {
      sfx.unlock()
      this.onStart({ ...this.config, mode: 'training' })
    })
    this.root.querySelector<HTMLButtonElement>('[data-act="tutorial"]')?.addEventListener('click', () => {
      sfx.unlock()
      this.startTutorial()
    })
    this.root.querySelectorAll<HTMLButtonElement>('[data-open]').forEach((el) => {
      el.addEventListener('click', () => this.openSettings(el.dataset.open as Tab))
    })
  }

  // The lesson runs in the real game, in training, in 3D — it teaches a mouse,
  // and the 2D view has no mouse-look to teach.
  private startTutorial() {
    this.onTutorial({ ...this.config, mode: 'training', view: '3d', tutorial: true })
  }

  // ---- shared controls: segmented buttons, sliders, switches -----------------
  //
  // Wired by the attribute they carry, so the title screen and the drawer are
  // built the same way and behave the same way, and a setting that exists in
  // both places (there are none today) would stay in step.
  private wireControls(scope: HTMLElement) {
    scope.querySelectorAll<HTMLButtonElement>('.seg').forEach((el) => {
      el.addEventListener('click', () => {
        const group = el.dataset.seg as string
        const raw = el.dataset.val ?? ''
        el.parentElement?.querySelectorAll('.seg').forEach((x) => x.classList.remove('active'))
        el.classList.add('active')
        if (group === 'view') store.set('view', (this.config.view = raw === '2d' ? '2d' : '3d'))
        else if (group === 'quality') store.set('quality', (this.config.quality = raw as MatchConfig['quality']))
        else if (group === 'position') store.set('position', (this.config.position = raw as MatchConfig['position']))
        else if (group === 'teamSize') store.set('teamSize', (this.config.teamSize = Number(raw)))
        else if (group === 'halfLength') store.set('halfLength', (this.config.halfLength = Number(raw)))
        else if (group === 'singleKeeper') store.set('singleKeeper', (this.config.singleKeeper = Number(raw) === 1))
        else if (group === 'camDist') store.set('camDist', raw as Saved['camDist'])
        this.onSettings()
      })
    })
    scope.querySelectorAll<HTMLInputElement>('input[data-sens]').forEach((el) => {
      el.addEventListener('input', () => {
        const v = Number(el.value)
        if (el.dataset.sens === 'height') store.set('heightSens', (this.config.heightSens = v))
        else store.set('curveSens', (this.config.curveSens = v))
        const out = el.parentElement?.querySelector('output')
        if (out) out.textContent = v.toFixed(2)
        this.onSettings()
      })
    })
    scope.querySelectorAll<HTMLInputElement>('input[data-set][type="range"]').forEach((el) => {
      const spec = NUMBERS[el.dataset.set ?? '']
      if (!spec) return
      el.addEventListener('input', () => {
        const v = Number(el.value)
        store.set(spec.key, v)
        spec.apply?.(v)
        const out = el.parentElement?.querySelector('output')
        if (out) out.textContent = spec.fmt(v)
        this.onSettings()
      })
    })
    scope.querySelectorAll<HTMLInputElement>('input[data-set][type="checkbox"]').forEach((el) => {
      const name = el.dataset.set ?? ''
      el.addEventListener('change', () => {
        if (name === 'muted') {
          sfx.setMuted(el.checked)
        } else {
          const spec = SWITCHES[name]
          if (!spec) return
          store.set(spec.key, el.checked)
          spec.apply?.(el.checked)
        }
        this.onSettings()
      })
    })
    this.wireKeys()
  }

  // ---- the settings drawer ------------------------------------------------------

  private drawerKeys: ((e: KeyboardEvent) => void) | null = null

  private closeDrawerKeys() {
    if (this.drawerKeys) document.removeEventListener('keydown', this.drawerKeys, true)
    this.drawerKeys = null
  }

  openSettings(tab: Tab = 'controls') {
    this.closeSettings()
    const wrap = document.createElement('div')
    wrap.className = 'drawer-wrap'
    wrap.innerHTML = `
      <aside class="drawer" role="dialog" aria-label="Settings">
        <header>
          <h2>Settings</h2>
          <button class="btn small ghost" data-close>Done</button>
        </header>
        <nav class="tabs">${TABS.map(([t, l]) => `<button class="tab" data-tab="${t}">${l}</button>`).join('')}</nav>
        <div class="body" data-body></div>
        <div class="foot">
          <span class="fine">${store.available ? 'Saved as you go.' : 'Your browser is not letting this page save — settings will not survive a reload.'}</span>
          <button class="btn primary small" data-close>Done</button>
        </div>
      </aside>`
    this.root.appendChild(wrap)
    wrap.addEventListener('mousedown', (e) => {
      if (e.target === wrap) this.closeSettings()
    })
    wrap.querySelectorAll('[data-close]').forEach((el) => el.addEventListener('click', () => this.closeSettings()))
    wrap.querySelectorAll<HTMLButtonElement>('.tab').forEach((el) => {
      el.addEventListener('click', () => this.renderTab(wrap, el.dataset.tab as Tab))
    })
    // Escape closes the drawer rather than whatever is beneath it. Captured, so
    // it gets there before a game's own Escape handling does.
    this.drawerKeys = (e) => {
      if (e.code === 'Escape' && !(this.input && this.input.capture)) {
        e.stopPropagation()
        e.preventDefault()
        this.closeSettings()
      }
    }
    document.addEventListener('keydown', this.drawerKeys, true)
    this.renderTab(wrap, tab)
  }

  closeSettings() {
    this.cancelCapture()
    this.closeDrawerKeys()
    this.root.querySelector('.drawer-wrap')?.remove()
  }

  private renderTab(wrap: HTMLElement, tab: Tab) {
    this.cancelCapture()
    wrap.querySelectorAll('.tab').forEach((el) => el.classList.toggle('on', (el as HTMLElement).dataset.tab === tab))
    const body = wrap.querySelector<HTMLElement>('[data-body]')!
    body.innerHTML = { how: () => this.howHtml(), controls: () => this.controlsHtml(), display: () => this.displayHtml(), audio: () => this.audioHtml(), match: () => this.matchHtml() }[tab]()
    body.scrollTop = 0
    this.wireControls(body)
    body.querySelector('[data-act="tutorial-start"]')?.addEventListener('click', () => {
      sfx.unlock()
      this.startTutorial()
    })
  }

  private seg(group: string, value: string | number, active: boolean, label: string): string {
    return `<button class="seg ${active ? 'active' : ''}" data-seg="${group}" data-val="${value}">${label}</button>`
  }

  private slider(spec: string, label: string, hint: string, min: number, max: number, step: number, value: number): string {
    return `<div class="row">
      <span class="lab">${label}</span>
      <span class="hint">${hint}</span>
      <div class="slider"><input type="range" min="${min}" max="${max}" step="${step}" value="${value}" data-set="${spec}" /><output>${NUMBERS[spec].fmt(value)}</output></div>
    </div>`
  }

  private toggle(name: string, label: string, hint: string, on: boolean): string {
    return `<div class="row">
      <label class="switch"><input type="checkbox" data-set="${name}" ${on ? 'checked' : ''} /><span>${label}</span></label>
      <span class="hint">${hint}</span>
    </div>`
  }

  private matchHtml(): string {
    return `
      <p class="tag" style="margin:0 0 24px">These shape a match you host online. Training is always you, a ball and two empty goals.</p>
      <div class="row"><span class="lab">Your position</span><span class="hint">You play this one player all match.</span>
        <div class="segmented wide" data-group="position">
          ${this.seg('position', 'FWD', this.config.position === 'FWD', 'Striker')}${this.seg('position', 'MID', this.config.position === 'MID', 'Midfield')}${this.seg('position', 'DEF', this.config.position === 'DEF', 'Defender')}${this.seg('position', 'GK', this.config.position === 'GK', 'Keeper')}
        </div></div>
      <div class="row"><span class="lab">Team size</span><span class="hint">Per side, keeper included.</span>
        <div class="segmented wide" data-group="teamSize">${[3, 4, 5, 6].map((n) => this.seg('teamSize', n, n === this.config.teamSize, `${n}v${n}`)).join('')}</div></div>
      <div class="row"><span class="lab">Half length</span>
        <div class="segmented wide" data-group="halfLength">${[[60, '1 min'], [120, '2 min'], [180, '3 min']].map(([v, l]) => this.seg('halfLength', v, v === this.config.halfLength, String(l))).join('')}</div></div>
      <div class="row"><span class="lab">Keepers</span>
        <div class="segmented wide" data-group="singleKeeper">${this.seg('singleKeeper', 0, !this.config.singleKeeper, 'Both')}${this.seg('singleKeeper', 1, this.config.singleKeeper, 'Home only')}</div></div>`
  }

  private displayHtml(): string {
    const cam = store.get('camDist')
    return `
      <p class="tag" style="margin:0 0 24px">View and graphics are chosen on the title screen — they take effect when a match starts. Everything here is felt the moment you close this.</p>
      ${this.slider('fov', 'Field of view', 'How much of the pitch you can see. Wider shows more and shrinks everything in the middle.', 50, 100, 1, store.get('fov'))}
      <div class="row"><span class="lab">Camera distance</span><span class="hint">How far behind your player the third-person camera sits.</span>
        <div class="segmented wide" data-group="camDist">${this.seg('camDist', 'close', cam === 'close', 'Close')}${this.seg('camDist', 'standard', cam === 'standard', 'Standard')}${this.seg('camDist', 'far', cam === 'far', 'Far')}</div></div>
      ${this.slider('hudScale', 'HUD size', 'The scoreboard, meters and panels.', 0.8, 1.5, 0.05, store.get('hudScale'))}
      ${this.toggle('showFps', 'Show frame rate', 'Top right, next to the camera name.', store.get('showFps'))}
      ${this.toggle('shotPreview', 'Shot preview in training', 'While a strike charges, a dotted line shows where it would go and where it would come down — your flick included. Switch it off to learn it by feel.', store.get('shotPreview'))}`
  }

  private audioHtml(): string {
    return `
      ${this.slider('volume', 'Volume', 'Everything: the crowd, the ball, the woodwork.', 0, 1, 0.05, store.get('volume'))}
      ${this.toggle('muted', 'Mute', `Also <b>${keyLabel(binds.get('mute'))}</b> in a match.`, sfx.muted)}`
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
    return `
      <h3>Feel</h3>
      <div class="row"><span class="lab">Kick height</span><span class="hint">How much an up-flick lifts the ball, and a down-flick drives it.</span>
        <div class="slider"><input type="range" min="0.4" max="5" step="0.05" value="${this.config.heightSens}" data-sens="height" /><output>${this.config.heightSens.toFixed(2)}</output></div></div>
      <div class="row"><span class="lab">Kick curve</span><span class="hint">How much a sideways flick bends it.</span>
        <div class="slider"><input type="range" min="0.2" max="3" step="0.05" value="${this.config.curveSens}" data-sens="curve" /><output>${this.config.curveSens.toFixed(2)}</output></div></div>
      ${this.slider('lookSens', 'Mouse look', 'How far the camera turns for a given movement of the mouse. Your aim is where you look.', 0.25, 3, 0.05, store.get('lookSens'))}
      ${this.toggle('invertY', 'Invert vertical look', 'Push the mouse forward to look down.', store.get('invertY'))}
      <h3>Keys</h3>
      ${this.keysHtml()}`
  }

  // The mouse half of the vocabulary is fixed: the whole design is that two
  // buttons do everything, so there is nothing about it to rebind.
  private howHtml(): string {
    const card = (icon: string, title: string, text: string) =>
      `<div class="move">${icon}<b>${title}</b><span>${text}</span></div>`
    const k = (a: Action) => `<kbd>${keyLabel(binds.get(a))}</kbd>`
    return `
      <div class="row">
        <button class="btn primary" data-act="tutorial-start">Start the tutorial</button>
        <span class="hint">Nine short steps in the real game — looking, running, touching, striking, shaping, jumping, shielding, sliding and ending with a goal. Around two minutes.</span>
      </div>
      <h3>The mouse does the football</h3>
      <div class="moves">
        ${card(mouse('right'), 'Touch', 'Right click. Tap or hold for close control. Move as you click to play it to the side or behind you.')}
        ${card(mouse('left'), 'Strike', 'Left click. A pass or a shot — the longer you hold, the harder it goes.')}
        ${card(mouse('left', true), 'Shape it', 'While a strike charges, flick up to lift it, down to drive it, sideways to bend it.')}
        ${card(mouse('right', true), 'Skill move', 'Flick hard during a touch: drag it back, roll it across you, lift it over a leg.')}
        ${card(mouse('right', true), 'Backheel', 'Flick down while backing away. Right click only — so it can never be a shot.')}
        ${card(mouse('right'), 'Cushion', 'Right click a dropping ball to kill it dead. Flick up to keep it in the air.')}
        ${card(mouse('left'), 'Volley / header', 'Left click a dropping ball to volley it — or, above head height, to head it.')}
        ${card(mouse('both'), 'Win it back', 'There is no tackle button. Right click takes the ball and keeps it; left click hammers it clear.')}
      </div>
      <h3>The keyboard does the rest</h3>
      <div class="keys">
        <span class="k">Move</span><span>${k('up')} ${k('left')} ${k('down')} ${k('right')}</span>
        <span class="k">Sprint</span><span>${k('sprint')}</span>
        <span class="k">Jump</span><span>${k('jump')}</span>
        <span class="k">Shield — hold</span><span>${k('shield')}</span>
        <span class="k">Slide tackle</span><span>${k('slide')}</span>
        <span class="k">Replay</span><span>${k('replay')}</span>
        <span class="k">Training: ball back / next drill</span><span>${k('spawnBall')} ${k('nextDrill')}</span>
      </div>
      <details class="more"><summary>Nothing is glued to anybody</summary>
        <p><b>Your clicks move the ball. Your legs do not.</b> Run straight through a ball and it does not budge. The only things that give it pace are your two buttons and a slide. A body is a wall the ball bounces off, never a paddle that hits it — so a defender still blocks your pass, but you cannot dribble by running into it: <b>tap right click</b> to knock it along as you go. That cuts both ways: your own touches have to be judged, and an opponent's ball is always takeable — with the same two clicks you use for everything else.</p></details>
      <details class="more"><summary>Slide, jump and shield</summary>
        <p><b>${keyLabel(binds.get('slide'))}</b> slides: the hitbox is your own body and nothing more, so it is all timing.</p>
        <p><b>${keyLabel(binds.get('jump'))} jumps.</b> Everything you can reach goes up with you — control height, aerial reach, the height at which a contact becomes a header — so a ball that would sail over your head is one you can attack. In the air you have nothing to push against, so whatever you left the ground with is what you land with: it's a commitment, not a dodge.</p>
        <p><b>Hold ${keyLabel(binds.get('shield'))} to shield.</b> You turn side-on, drop to a shuffle and lose the ability to strike — but your body goes wide and soft, so the ball dies against you instead of pinging away, and nobody can play a ball your shoulder is in front of. Right click to knock it out and go again.</p></details>
      <details class="more"><summary>The same wrist works at your feet</summary>
        <p>Flick hard during a <b>touch</b> to drag it back, roll it across your body, or lift it over a leg — and flick down while you're already backing away and it's a backheel instead, struck past you rather than pulled under you. <b>Every one of those is the right button only.</b> You cannot backheel with a left click, which means you cannot get a shot's power on one — a heel is not a swing, and the touch's ceiling is the whole cost of playing the ball behind you.</p></details>
      <details class="more"><summary>Training</summary>
        <p>Training is you, a ball and two empty goals. <b>Score as many as you like</b> — nothing resets after a goal: the net holds the ball for a beat and rolls it back out, and <b>${keyLabel(binds.get('replay'))}</b> replays whatever you want, when you want it. Down one touchline there is a slalom of mannequins to dribble and a five-man wall standing ten yards off a spot, both solid to you and to the ball. A match is other people: every shirt is a seat, and the ones nobody has taken just stand there.</p></details>`
  }

  // Shared by the settings drawer, which is where the key list lives.
  private wireKeys() {
    this.root.querySelectorAll<HTMLButtonElement>('[data-bind]').forEach((el) => {
      if (el.dataset.wired) return
      el.dataset.wired = '1'
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
    const reset = this.root.querySelector<HTMLElement>('[data-act="resetkeys"]')
    if (reset && !reset.dataset.wired) {
      reset.dataset.wired = '1'
      reset.addEventListener('click', () => {
        binds.reset()
        this.refreshKeys()
      })
    }
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

  // ---- pause and full time -------------------------------------------------------

  showPause(summary: string | null = null) {
    this.show()
    this.root.innerHTML = `
      <div class="center">
        <div class="panel pause">
          <h2>Paused</h2>
          ${summary ? `<div class="statline">${esc(summary)}</div>` : ''}
          <div class="actions col">
            <button class="btn primary" data-act="resume">Resume</button>
            <button class="btn" data-act="settings">Settings</button>
            <button class="btn" data-act="restart">Restart</button>
            <button class="btn ghost" data-act="menu">Main menu</button>
          </div>
        </div>
      </div>`
    this.root.querySelector('[data-act="resume"]')?.addEventListener('click', () => this.onResume())
    this.root.querySelector('[data-act="restart"]')?.addEventListener('click', () => this.onRestart())
    this.root.querySelector('[data-act="menu"]')?.addEventListener('click', () => this.onMenu())
    // Pausing to change a key you got wrong mid-match is the main reason
    // anybody opens this screen.
    this.root.querySelector('[data-act="settings"]')?.addEventListener('click', () => this.openSettings('controls'))
  }

  showEnd(homeScore: number, awayScore: number, stats: string) {
    this.show()
    const result = homeScore === awayScore ? 'Draw' : homeScore > awayScore ? 'Home win' : 'Away win'
    this.root.innerHTML = `
      <div class="center">
        <div class="panel pause">
          <div class="result">Full time</div>
          <div class="bigscore">${homeScore}<span>–</span>${awayScore}</div>
          <p class="tag">${result}</p>
          <div class="statline">${stats}</div>
          <div class="actions col">
            <button class="btn primary" data-act="restart">Rematch</button>
            <button class="btn ghost" data-act="menu">Main menu</button>
          </div>
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
