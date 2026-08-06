import { CONTROL } from '../config'
import { sfx } from '../audio/sfx'
import type { MatchConfig } from '../types'

// DOM overlays: main menu, pause, and full-time screens. Kept out of the canvas
// so they can use real buttons, focus, and accessible markup.
export class Screens {
  root: HTMLElement
  private config: MatchConfig = {
    teamSize: 4,
    halfLength: 120,
    mode: 'match',
    singleKeeper: false,
    view: '3d',
    position: 'FWD',
    heightSens: CONTROL.heightSensitivity,
    curveSens: CONTROL.curveSensitivity,
  }

  onStart: (config: MatchConfig) => void = () => {}
  onOnline: (config: MatchConfig) => void = () => {}
  onResume: () => void = () => {}
  onRestart: () => void = () => {}
  onMenu: () => void = () => {}

  constructor(root: HTMLElement) {
    this.root = root
  }

  hide() {
    this.root.innerHTML = ''
    this.root.style.display = 'none'
  }

  show() {
    this.root.style.display = 'flex'
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
          <button class="btn primary" data-act="match">▶  Play Match</button>
          <button class="btn" data-act="training">🎯  Training <span class="sub">(solo, no AI)</span></button>
          <button class="btn" data-act="online">🌐  Play Online <span class="sub">(host or join)</span></button>
        </div>

        ${this.controlsHtml()}
        <p class="foot">Training is solo — just you, a ball and two empty goals, with nothing else on the pitch. Press <b>B</b> to put the ball back in front of you.</p>
      </div>`
    this.wireMenu()
  }

  private seg(group: string, value: number, active: boolean, label: string): string {
    return `<button class="seg ${active ? 'active' : ''}" data-seg="${group}" data-val="${value}">${label}</button>`
  }

  private segStr(group: string, value: string, active: boolean, label: string): string {
    return `<button class="seg ${active ? 'active' : ''}" data-seg="${group}" data-val="${value}">${label}</button>`
  }

  private controlsHtml(): string {
    const rows: [string, string][] = [
      ['Move / Sprint / Walk', 'WASD · Shift · Alt'],
      ['Aim / Look', 'Mouse'],
      ['<b>Touch</b> — close control', '<b>Right click</b> · tap or hold'],
      ['Touch to the side / back', 'A D S + right click'],
      ['<b>Skill move</b> — drag / roll / lift', 'Right click + <b>hard flick</b>'],
      ['<b>Strike</b> — pass or shot', '<b>Left click</b> · hold = power'],
      ['<b>Cushion</b> a ball out of the air', 'Right click while it drops'],
      ['<b>Volley / header</b>', 'Left click while it drops'],
      ['<b>Jump</b> — head it, or reach higher', '<b>Space</b>'],
      ['<b>Shield</b> — hold it off with your body', '<b>Q</b> · hold'],
      ['<b>Win the ball back</b>', 'Just right / left click it'],
      ['<b>Slide tackle</b>', 'C · body-sized, no assist'],
      ['<b>Keeper: dive</b>', '<b>Left click</b> · hold = distance, flick = height'],
      ['<b>Keeper: gather</b>', '<b>Right click</b>'],
      ['View: zoom (2D) · 1st/3rd (3D)', 'V'],
      ['Mute sound', 'M'],
      ['Pause', 'Esc / P'],
      ['Spawn ball (training)', 'B'],
    ]
    return `<div class="controls">
      <h3>Controls</h3>
      <div class="grid">
        ${rows.map(([a, b]) => `<div class="k">${a}</div><div class="v">${b}</div>`).join('')}
      </div>
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
        hammer it clear. <b>C</b> slides: the hitbox is your own body and nothing
        more, so it's all timing.
      </div>
      <div class="flickhint">
        <b>Space jumps.</b> Everything you can reach goes up with you — control
        height, aerial reach, the height at which a contact becomes a header —
        so a ball that would sail over your head is one you can attack. In the
        air you have nothing to push against, so whatever you left the ground
        with is what you land with: it's a commitment, not a dodge.
      </div>
      <div class="flickhint">
        <b>Hold Q to shield.</b> You turn side-on, drop to a shuffle and lose
        the ability to strike — but your body goes wide and soft, so the ball
        dies against you instead of pinging away, and nobody can play a ball your
        shoulder is in front of. Nothing is attached to you: keeping it is purely
        a question of where you stand. Right click to knock it out and go again.
      </div>
      <div class="flickhint">
        <b>The same wrist works on the ball at your feet.</b> Flick hard during a
        <b>touch</b> to drag it back, roll it across your body, or lift it over a
        leg. And the ball doesn't have to be on the floor: <b>right click</b> a
        dropping ball to cushion it dead (flick up to keep it in the air),
        <b>left click</b> to volley it — or, above head height, to head it.
      </div>
    </div>`
  }

  private wireMenu() {
    this.root.querySelectorAll<HTMLButtonElement>('.seg').forEach((el) => {
      el.addEventListener('click', () => {
        const group = el.dataset.seg as string
        const raw = el.dataset.val ?? ''
        el.parentElement?.querySelectorAll('.seg').forEach((s) => s.classList.remove('active'))
        el.classList.add('active')
        if (group === 'view') this.config.view = raw === '2d' ? '2d' : '3d'
        else if (group === 'position') this.config.position = raw as MatchConfig['position']
        else if (group === 'teamSize') this.config.teamSize = Number(raw)
        else if (group === 'halfLength') this.config.halfLength = Number(raw)
        else if (group === 'singleKeeper') this.config.singleKeeper = Number(raw) === 1
      })
    })
    this.root.querySelectorAll<HTMLInputElement>('input[data-sens]').forEach((el) => {
      el.addEventListener('input', () => {
        const v = Number(el.value)
        if (el.dataset.sens === 'height') this.config.heightSens = v
        else this.config.curveSens = v
        const out = el.parentElement?.querySelector('output')
        if (out) out.textContent = v.toFixed(2)
      })
    })
    this.root.querySelector<HTMLButtonElement>('[data-act="match"]')?.addEventListener('click', () => {
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
