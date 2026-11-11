// Keyboard / mouse input with pointer lock.
export const ACTIONS: [string, string, string][] = [
  ['KeyW', 'Move forward', 'Movement'], ['KeyS', 'Move back', 'Movement'], ['KeyA', 'Move left / steer left', 'Movement'], ['KeyD', 'Move right / steer right', 'Movement'],
  ['ShiftLeft', 'Sprint', 'Movement'], ['KeyC', 'Crouch', 'Movement'], ['Space', 'Jump / handbrake', 'Movement'], ['KeyV', 'Camera view', 'Movement'],
  ['KeyE', 'Interact', 'Actions'], ['KeyT', 'Secondary action', 'Actions'], ['KeyR', 'Reload / rotate', 'Actions'], ['KeyF', 'Flashlight', 'Actions'],
  ['KeyQ', 'Quick bandage', 'Actions'], ['KeyG', 'Drop held item', 'Actions'], ['KeyX', 'Dismantle mode', 'Actions'],
  ['Tab', 'Inventory', 'Menus'], ['KeyH', 'Health', 'Menus'], ['KeyK', 'Skills', 'Menus'], ['KeyJ', 'Journal', 'Menus'], ['KeyM', 'Map', 'Menus'], ['KeyB', 'Build menu', 'Menus'],
];
export class Input {
  /** action (default key code) -> physical key code chosen by the player */
  bindings: Record<string, string> = {};
  private remap: Record<string, string> = {};
  capture: ((code: string) => void) | null = null;
  setBindings(b: Record<string, string>) {
    this.bindings = { ...b }; this.remap = {};
    for (const def in this.bindings) { const phys = this.bindings[def]; if (phys && phys !== def) this.remap[phys] = def; }
  }
  keyName(def: string) { const c = this.bindings[def] ?? def; return c.replace(/^Key/, '').replace(/^Digit/, '').replace('Left', ' L').replace('Right', ' R'); }
  keys = new Set<string>();
  pressed = new Set<string>();
  released = new Set<string>();
  mouseDX = 0; mouseDY = 0; wheel = 0;
  mouse = [false, false, false];
  mousePressed = [false, false, false];
  mouseReleased = [false, false, false];
  locked = false;
  /** pointer lock unavailable (e.g. sandboxed iframe): treat the canvas as captured while the game wants it */
  fallback = false;
  private want = false;
  sensitivity = 0.0022;
  onLockChange: (l: boolean) => void = () => {};

  constructor(private el: HTMLElement) {
    addEventListener('keydown', e => {
      if (this.capture) { e.preventDefault(); const c = this.capture; this.capture = null; if (e.code !== 'Escape') c(e.code); return; }
      if ((e.target as HTMLElement)?.tagName === 'INPUT') return;
      const code = this.remap[e.code] ?? e.code;
      if (!this.keys.has(code)) this.pressed.add(code);
      this.keys.add(code);
      if (['Tab', 'Space', 'ArrowUp', 'ArrowDown', 'F1', 'F3'].includes(e.code)) e.preventDefault();
    });
    addEventListener('keyup', e => { const code = this.remap[e.code] ?? e.code; this.keys.delete(code); this.released.add(code); });
    addEventListener('mousemove', e => { if (this.locked) { this.mouseDX += e.movementX; this.mouseDY += e.movementY; } });
    document.addEventListener('pointerlockerror', () => this.enableFallback());
    addEventListener('mousedown', e => { if (!this.locked) return; this.mouse[e.button] = true; this.mousePressed[e.button] = true; });
    addEventListener('mouseup', e => { this.mouse[e.button] = false; this.mouseReleased[e.button] = true; });
    addEventListener('wheel', e => { if (this.locked) this.wheel += Math.sign(e.deltaY); }, { passive: true });
    addEventListener('contextmenu', e => e.preventDefault());
    document.addEventListener('pointerlockchange', () => { this.locked = document.pointerLockElement === this.el; if (!this.locked) { this.keys.clear(); this.mouse = [false, false, false]; } this.onLockChange(this.locked); });
    addEventListener('blur', () => { this.keys.clear(); this.mouse = [false, false, false]; });
  }
  lock() {
    this.want = true;
    if (this.fallback) { this.locked = true; return; }
    try { const p = this.el.requestPointerLock() as any; if (p && p.catch) p.catch(() => this.enableFallback()); } catch { this.enableFallback(); }
  }
  unlock() { this.want = false; if (this.fallback) { this.locked = false; this.onLockChange(false); return; } if (document.pointerLockElement) document.exitPointerLock(); }
  private enableFallback() {
    if (this.fallback) return;
    this.fallback = true;
    if (this.want) this.locked = true;
  }
  down(code: string) { return this.keys.has(code); }
  hit(code: string) { return this.pressed.has(code); }
  endFrame() { this.pressed.clear(); this.released.clear(); this.mouseDX = 0; this.mouseDY = 0; this.wheel = 0; this.mousePressed = [false, false, false]; this.mouseReleased = [false, false, false]; }
}
