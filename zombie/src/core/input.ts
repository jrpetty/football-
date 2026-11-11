// Keyboard / mouse input with pointer lock.
export class Input {
  keys = new Set<string>();
  pressed = new Set<string>();
  released = new Set<string>();
  mouseDX = 0; mouseDY = 0; wheel = 0;
  mouse = [false, false, false];
  mousePressed = [false, false, false];
  mouseReleased = [false, false, false];
  locked = false;
  sensitivity = 0.0022;
  onLockChange: (l: boolean) => void = () => {};

  constructor(private el: HTMLElement) {
    addEventListener('keydown', e => {
      if ((e.target as HTMLElement)?.tagName === 'INPUT') return;
      if (!this.keys.has(e.code)) this.pressed.add(e.code);
      this.keys.add(e.code);
      if (['Tab', 'Space', 'ArrowUp', 'ArrowDown', 'F1', 'F3'].includes(e.code)) e.preventDefault();
    });
    addEventListener('keyup', e => { this.keys.delete(e.code); this.released.add(e.code); });
    addEventListener('mousemove', e => { if (this.locked) { this.mouseDX += e.movementX; this.mouseDY += e.movementY; } });
    addEventListener('mousedown', e => { if (!this.locked) return; this.mouse[e.button] = true; this.mousePressed[e.button] = true; });
    addEventListener('mouseup', e => { this.mouse[e.button] = false; this.mouseReleased[e.button] = true; });
    addEventListener('wheel', e => { if (this.locked) this.wheel += Math.sign(e.deltaY); }, { passive: true });
    addEventListener('contextmenu', e => e.preventDefault());
    document.addEventListener('pointerlockchange', () => { this.locked = document.pointerLockElement === this.el; if (!this.locked) { this.keys.clear(); this.mouse = [false, false, false]; } this.onLockChange(this.locked); });
    addEventListener('blur', () => { this.keys.clear(); this.mouse = [false, false, false]; });
  }
  lock() { try { const p = this.el.requestPointerLock() as any; if (p && p.catch) p.catch(() => {}); } catch { /* ignore */ } }
  unlock() { if (document.pointerLockElement) document.exitPointerLock(); }
  down(code: string) { return this.keys.has(code); }
  hit(code: string) { return this.pressed.has(code); }
  endFrame() { this.pressed.clear(); this.released.clear(); this.mouseDX = 0; this.mouseDY = 0; this.wheel = 0; this.mousePressed = [false, false, false]; this.mouseReleased = [false, false, false]; }
}
