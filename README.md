# ⚽ Gaffer — Football Analysis

A soccer (association football) **coaching & player-analysis** application. Gaffer
is a single-page web app for managing a squad, analysing matches and players,
planning training, building tactics — and **breaking down match video** with
timestamped, categorised tagging.

Everything runs in the browser and saves to your device (no account, no server,
no setup beyond `npm install`). It ships with a full sample team — *Riverside
Athletic* — so every screen is populated the moment you open it.

---

## Features

### 📊 Dashboard & Team Analysis
- Season snapshot: points, record, goals, clean sheets, win rate, possession, xG.
- Form guide, cumulative points progression, **goals vs expected goals (xG)**.
- Results breakdown (W/D/L), formation usage & PPG, home/away splits,
  goals by 15-minute interval, and squad availability.

### 👥 Squad & Player Analysis
- Roster manager with search, position filters, sorting, and full add/edit.
- **Player profiles**: attribute radar, season aggregates, per-90 metrics, a
  rating trend, a **touch/position heatmap**, and a full match log.
- **Head-to-head comparison** of up to three players (overlaid radar + tables).

### 🗓️ Matches
- Result list with competition/venue filters and quick add.
- **Match report**: lineup on the pitch, event timeline, dual-bar stat
  comparison (possession, shots, xG, corners), a shot map, and player ratings.

### ♟️ Tactics Board
- Pick a formation (4-3-3, 4-4-2, 4-2-3-1, 3-5-2, 4-1-4-1, 3-4-3), **assign
  players and drag them** to fine-tune positions, auto-fill the best XI, and
  save reusable lineups.

### 🎬 Video Analysis
- Link footage by **YouTube** link, **direct video URL** (mp4/webm), or a
  **local file**.
- Custom player with scrubber, frame nudging, playback speed, and keyboard
  shortcuts (space / ← → / **M to mark a moment**).
- **Tag timestamped moments** by category (goal, chance, press, defensive
  error, set piece, transition…), link them to a player and team, add coaching
  notes — then **click any tag to jump straight to that moment**.

### 🏋️ Training
- A drill library (categories, intensity, objectives, equipment, focus
  attributes) and a **session planner** with running durations.

### ⚙️ Data & Export
- Edit club details, **export to JSON / CSV**, **import** a saved dataset, and
  reset to the sample data. Your data is auto-saved to `localStorage`.

---

## Easiest way to use it — no install

Open **`gaffer.html`** (in the repo root) directly in any modern browser
(double-click it, or drag it onto a browser tab). It's the entire app inlined
into one self-contained file — no server, no install, works offline, and your
data still saves locally in that browser.

Re-generate it after code changes with:

```bash
npm install
npm run build:single   # writes ./gaffer.html
```

## Getting started (development)

```bash
npm install      # install dependencies
npm run dev      # start the dev server (http://localhost:5173)
```

Build for production:

```bash
npm run build    # type-check + bundle to ./dist
npm run preview  # preview the production build
```

---

## Tech & architecture

- **React 18 + TypeScript + Vite**, no UI/chart libraries — every chart, the
  football pitch, the radar, heatmap and shot map are hand-rolled **SVG**.
- **State**: a typed reducer in `src/store/store.tsx`, persisted to
  `localStorage`. All analytics are pure functions in
  `src/analytics/selectors.ts`.
- **Routing**: `react-router-dom` (hash router, so it works from a static host
  or opened file).

```
src/
  types.ts                 # the whole domain model
  data/                    # seed dataset, formation presets, tag categories
  store/store.tsx          # reducer + persistence + hooks
  analytics/selectors.ts   # derived stats (team, players, leaderboards…)
  utils/                   # formatting + video helpers
  components/
    charts/                # RadarChart, BarChart, LineChart, Donut, Sparkline
    pitch/                 # Pitch, Heatmap, ShotMap
    ui/                    # StatCard, Modal, Avatar, badges, attribute bars
  pages/                   # one file per screen
```

## Notes & limitations

- **Local video files** are referenced by an in-memory blob URL, so they are
  only available for the current browser session. Paste a YouTube or direct
  video URL for footage that persists across reloads, or re-attach the file.
- The sample data is generated deterministically — clearing it (Data → Reset)
  restores the same demo team.

---

*Built for coaches and analysts. Swap in your own squad, footage and matches
from the **Squad**, **Matches**, **Video** and **Data** screens.*
