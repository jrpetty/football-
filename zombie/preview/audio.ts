import * as THREE from 'three';
import { audio, type SfxName, type LoopName } from '../src/audio/Audio';
import { SFX } from '../src/audio/sfx';
import { LOOPS } from '../src/audio/loops';

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;
const num = (id: string): number => Number($<HTMLInputElement>(id).value);

const listenerPos = new THREE.Vector3(0, 1.7, 0);
const fwd = new THREE.Vector3(0, 0, -1);
const up = new THREE.Vector3(0, 1, 0);

function sourcePos(): THREE.Vector3 | undefined {
  const d = num('dist');
  if (d <= 0) return undefined;
  const a = (num('ang') * Math.PI) / 180;
  return new THREE.Vector3(Math.sin(a) * d, 1.7, -Math.cos(a) * d);
}

$('init').addEventListener('click', () => { void audio.init(); });

const sfxDiv = $('sfx');
for (const name of Object.keys(SFX) as SfxName[]) {
  const b = document.createElement('button');
  b.textContent = name;
  b.dataset.sfx = name;
  b.addEventListener('click', () => audio.play(name, { pos: sourcePos() }));
  sfxDiv.appendChild(b);
}

const loopDiv = $('loops');
for (const name of Object.keys(LOOPS) as LoopName[]) {
  const b = document.createElement('button');
  b.textContent = name;
  b.dataset.loop = name;
  b.addEventListener('click', () => {
    const id = 'preview-' + name;
    if (b.classList.toggle('on')) audio.startLoop(id, name, { pos: sourcePos() ?? new THREE.Vector3(2, 1, -3) });
    else audio.stopLoop(id);
  });
  loopDiv.appendChild(b);
}

function updateAmbience(): void {
  audio.setAmbience({
    wind: num('wind') / 100, rain: num('rain') / 100, night: num('night') / 100,
    indoor: $<HTMLInputElement>('indoor').checked, insideBase: $<HTMLInputElement>('base').checked,
  });
}
for (const id of ['wind', 'rain', 'night', 'indoor', 'base']) $(id).addEventListener('input', updateAmbience);
for (const id of ['vm', 'vs', 'vmu', 'va']) {
  $(id).addEventListener('input', () => audio.setVolumes({ master: num('vm') / 100, sfx: num('vs') / 100, music: num('vmu') / 100, ambience: num('va') / 100 }));
}
let paused = false;
$('pause').addEventListener('click', () => {
  paused = !paused;
  if (paused) audio.suspend(); else audio.resume();
  $('pause').textContent = paused ? 'Resume' : 'Suspend';
});

function frame(): void {
  audio.setListener(listenerPos, fwd, up);
  audio.setMusicIntensity(num('music') / 100);
  audio.setLowHealth(num('lh') / 100);
  for (const name of Object.keys(LOOPS) as LoopName[]) {
    const p = sourcePos();
    if (p) audio.updateLoop('preview-' + name, { pos: p });
  }
  $('distv').textContent = num('dist') > 0 ? num('dist') + ' m' : '0 m (2D)';
  $('angv').textContent = num('ang') + '°';
  $('musicv').textContent = (num('music') / 100).toFixed(2);
  $('stats').textContent = JSON.stringify(audio.stats());
  requestAnimationFrame(frame);
}
updateAmbience();
requestAnimationFrame(frame);

(window as unknown as { __audio: typeof audio }).__audio = audio;
