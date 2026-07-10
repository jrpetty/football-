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
  }

  onStart: (config: MatchConfig) => void = () => {}
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

  private show() {
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
        </div>

        <div class="actions">
          <button class="btn primary" data-act="match">▶  Play Match</button>
          <button class="btn" data-act="freeplay">Free Play / Training</button>
        </div>

        ${this.controlsHtml()}
        <p class="foot">Tip: your player auto-switches to whoever's nearest the ball. Aim with the mouse.</p>
      </div>`
    this.wireMenu()
  }

  private seg(group: string, value: number, active: boolean, label: string): string {
    return `<button class="seg ${active ? 'active' : ''}" data-seg="${group}" data-val="${value}">${label}</button>`
  }

  private controlsHtml(): string {
    const rows: [string, string][] = [
      ['Move / Sprint', 'WASD · hold Shift'],
      ['Aim', 'Mouse'],
      ['Pass (hold = longer)', 'Left click'],
      ['Shoot (hold = power)', 'Right click'],
      ['Lofted / chip', 'hold Space'],
      ['Through ball', 'E'],
      ['Tackle / Slide', 'F · C'],
      ['Switch player', 'Q'],
      ['Camera · Pause', 'V · Esc'],
      ['Spawn ball (free play)', 'B'],
    ]
    return `<div class="controls">
      <h3>Controls</h3>
      <div class="grid">
        ${rows.map(([a, b]) => `<div class="k">${a}</div><div class="v">${b}</div>`).join('')}
      </div>
    </div>`
  }

  private wireMenu() {
    this.root.querySelectorAll<HTMLButtonElement>('.seg').forEach((el) => {
      el.addEventListener('click', () => {
        const group = el.dataset.seg as keyof MatchConfig | 'singleKeeper'
        const val = Number(el.dataset.val)
        el.parentElement?.querySelectorAll('.seg').forEach((s) => s.classList.remove('active'))
        el.classList.add('active')
        if (group === 'teamSize') this.config.teamSize = val
        else if (group === 'halfLength') this.config.halfLength = val
        else if (group === 'singleKeeper') this.config.singleKeeper = val === 1
      })
    })
    this.root.querySelector<HTMLButtonElement>('[data-act="match"]')?.addEventListener('click', () => {
      this.onStart({ ...this.config, mode: 'match' })
    })
    this.root.querySelector<HTMLButtonElement>('[data-act="freeplay"]')?.addEventListener('click', () => {
      this.onStart({ ...this.config, mode: 'freeplay' })
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
