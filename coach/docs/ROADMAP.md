# Implementation plan, status and open questions (v1)

> Historical: this is the v1 MVP plan. Current status is in
> [TODO.md](../TODO.md), and the v2 plan is in [plan.md](../plan.md).

## MVP task list

| # | Task | Status |
| --- | --- | --- |
| 1 | Domain model; profile schema and validator; reference profile | Done |
| 2 | Detectors (bar, coverage, signature) with "can't read" confidence | Done |
| 3 | Visible-state tracker (smoothing, hysteresis, staleness) | Done |
| 4 | Event rules that only fire on observed transitions | Done |
| 5 | Episodes and outcome evaluator, with evidence exclusions | Done |
| 6 | Evidence grading and pattern memory (opt-in persistence, patch gating) | Done |
| 7 | Policy governor (modes, competitive integrity, gates, reasons) | Done |
| 8 | Template language in four tones, with guardrail tests | Done |
| 9 | Synthetic Arena simulator with ground truth; end-to-end tests | Done |
| 10 | Analysis worker, bounded queue, flow control | Done |
| 11 | Capture sources (screen, simulation); local TTS controller | Done |
| 12 | Electron shell: consent, network lock, permissions, storage, stop shortcut | Done |
| 13 | UI: setup, live status, Stop capture, review, tendencies, settings, privacy, alignment check | Done |
| 14 | Docs: architecture, privacy and integrity, profile authoring | Done |

## Verified here vs not yet verified

Verified in a Linux container:
- unit and end-to-end tests;
- a production renderer build driven by Playwright (simulation, review,
  tendencies; no CSP violations or external requests);
- the packaged-layout Electron app under Xvfb (security probes, plus a real
  screen-capture session through the picker).

**Not verified, because it needs Windows hardware:**
- Windows Graphics Capture behaviour in Electron 44: the capture border,
  DPI scaling, multi-monitor setups, HDR colour shifts in captured frames;
- which SAPI/OneCore voices report `localService`;
- the global shortcut while a fullscreen game has focus;
- performance on low-end CPUs.

## Next increments (in priority order)

1. **Windows validation pass.** Run the checklist above on Windows 10 and 11,
   with windowed, borderless and exclusive-fullscreen games.
2. **First real game profile.** Pick one title, author regions against the
   current patch, research the publisher's policy for each mode, and mark
   `community` until reviewed. No real-game profile ships until this is done
   honestly.
3. **Profile tooling.** An in-app region editor working on the alignment
   still (in memory only), a colour picker for HSV ranges, and template
   capture for `signature` signals.
4. **Packaging.** An electron-builder NSIS installer, code signing, and a
   documented update story. Auto-update needs network access, which the app
   blocks today, so it requires an explicit, separately consented design.
5. **OCR detector** (local, WASM) for timers, scores and result text, behind
   the existing `Reading` interface, with bundled language data and no CDN.
6. **Native WGC picker** (`GraphicsCapturePicker`) through a small native
   module, so Windows uses the OS picker as macOS 15+ does.
7. **Better TTS.** An optional bundled neural voice (e.g. Piper) for
   consistent quality.
8. **Statistics.** A multiple-comparison correction once profiles have many
   episode types, decay of old evidence by session, and per-mode separation
   of tendencies.

## Open questions and unknowns (flagged, not assumed)

- **Publisher policies.** Live-coaching permission is per title and per mode.
  None has been researched yet.
- **Anti-cheat and tournament rules.** OS-level capture is the same path
  streaming tools use, but no compatibility claim is made for any specific
  anti-cheat or event.
- **Capture-blocked games.** Some games exclude themselves from capture. The
  app reports an unreadable HUD rather than working around it.
- **Confounding.** Responses correlate with situations, and the coach cannot
  see the situation fully. Language stays associative. Whether players read
  it that way needs user testing.
- **Accessibility audit.** Keyboard navigation, focus styles, ARIA roles and
  reduced motion are implemented. A screen-reader audit has not been done.
- **Product name.** "Sightline" is a working name; trademark clearance has not
  been done.
