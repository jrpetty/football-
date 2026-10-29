// System-wide key presses for the app tests, as a real keyboard sends them:
// a held key repeats (after about half a second, then about 30 times a
// second) and the app's global shortcut sees each repeat. Linux uses xdotool
// (the X server repeats a held key); Windows sends the repeats itself through
// the user32 keybd_event call. Only function keys are supported.

import { execFileSync } from 'node:child_process'

const VK = { F6: 0x75, F7: 0x76, F8: 0x77, F9: 0x78 }

function windows(script) {
  execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { stdio: 'inherit' })
}

const WIN_KEYS = `
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Threading;
public static class Keys {
  [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static void Hold(byte vk, int ms) {
    keybd_event(vk, 0, 0, UIntPtr.Zero);
    var start = Environment.TickCount;
    Thread.Sleep(500);
    while (Environment.TickCount - start < ms) { keybd_event(vk, 0, 0, UIntPtr.Zero); Thread.Sleep(33); }
    keybd_event(vk, 0, 2, UIntPtr.Zero);
  }
  public static void Tap(byte vk) {
    keybd_event(vk, 0, 0, UIntPtr.Zero);
    Thread.Sleep(60);
    keybd_event(vk, 0, 2, UIntPtr.Zero);
  }
}
"@
`

/** Hold a key down for `ms` milliseconds, with the repeats a keyboard sends. */
export function holdKey(key, ms) {
  if (process.platform === 'win32') windows(`${WIN_KEYS}[Keys]::Hold(${VK[key]}, ${ms})`)
  else execFileSync('xdotool', ['keydown', key, 'sleep', String(ms / 1000), 'keyup', key])
}

/** Press and release a key. */
export function tapKey(key) {
  if (process.platform === 'win32') windows(`${WIN_KEYS}[Keys]::Tap(${VK[key]})`)
  else execFileSync('xdotool', ['key', key])
}
