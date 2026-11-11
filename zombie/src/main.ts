import './ui/style.css';
import { Game } from './game';

const canvas = document.getElementById('game') as HTMLCanvasElement;
const bar = document.getElementById('loadbar') as HTMLElement;
const label = document.getElementById('loadlabel') as HTMLElement;

function fail(e: unknown) {
  console.error(e);
  label.textContent = 'Failed to start: ' + (e instanceof Error ? e.message : String(e)) + ' — WebGL2 is required.';
  label.style.color = '#e0503a';
}

const game = new Game(canvas);
(window as any).game = game;
game.input.sensitivity = 0.0022 * game.settings.sens;
game.boot((p, l) => { bar.style.width = (p * 100).toFixed(0) + '%'; label.textContent = l; })
  .then(() => { const el = document.getElementById('loading')!; el.style.transition = 'opacity 0.8s'; el.style.opacity = '0'; setTimeout(() => el.remove(), 800); })
  .catch(fail);
addEventListener('error', e => { if (document.getElementById('loading')) fail(e.error ?? e.message); });
