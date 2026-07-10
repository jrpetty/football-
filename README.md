# ⚽ Open Pitch — Physics Football

A **physics-driven, input-driven** browser football game, inspired by *Pro Soccer
Online*. Your decisions and skill are the only thing that matters — there are no
animation-locked outcomes and no pay-to-win. Every dribble, pass and shot is a
real-time physics calculation: the ball has **spin, curve, height, bounce and
momentum**, and players carry momentum too.

It runs entirely in the browser — no install, no account, no server. Play a
match against the AI, or drop into free-play training.

> **Note on scope.** The original design targets Unreal Engine 5 with 3D first/third-
> person cameras. That can't run or be verified in a browser sandbox, so this is a
> complete, genuinely playable **2D top-down** realisation of the same *philosophy*
> and *mechanics* — a real ball-physics engine and input-driven control — built to
> run and deploy anywhere. The 3D/Unreal target remains the long-term roadmap.

---

## Play it

```bash
npm install
npm run dev        # http://localhost:5173
```

Or build the static site:

```bash
npm run build      # type-checks then bundles to ./dist
npm run preview
```

The game is a self-contained, dependency-free bundle (~48 KB) — vanilla
TypeScript + HTML5 Canvas, no game engine or UI framework.

---

## Controls

| Action | Input |
| --- | --- |
| Move | **W A S D** (or arrows) |
| Sprint | hold **Shift** (drains stamina) |
| Aim | **Mouse** (the cursor is your kick direction) |
| Pass | **Left click** — tap = short, hold = long (power meter) |
| Shoot | **Right click** — hold to charge power, release to fire |
| Lofted / chip | hold **Space** while passing or shooting |
| Through ball | **E** (weighted pass into space) |
| Tackle · Slide | **F** · **C** |
| Switch player | **Q** |
| Camera zoom | **V** (TV → follow → close) |
| Pause | **Esc** / **P** |
| Spawn a ball (free play) | **B** (at the cursor) |

Your player **auto-switches** to whoever is nearest the ball, so you're always
controlling the most relevant player. Curve comes from *how you strike it* — aim
across your run to bend it (Magnus effect).

---

## The physics & mechanics

Everything is tuned in one file (`src/game/config.ts`) so the game's *feel* is
one place, not a scavenger hunt.

- **Ball** (`physics/ball.ts`) — full 3D state (x, y, height z + velocities +
  spin). Gravity, air drag, rolling friction, restitution bounces, a Magnus term
  that curves flight from side-spin, and a speed cap. Rolling vs airborne balls
  behave differently and need different control.
- **Players** (`entities/player.ts`) — momentum-based movement: you steer a
  *target* velocity and the body accelerates/decelerates toward it, so sharp
  reversals cost you a beat. Stamina gates top speed and regenerates when you
  ease off.
- **Ball control** (`match/world.ts`) — possession is a real reach check, not a
  magnet. Dribbling pushes the ball a **touch ahead** in your direction of
  travel; sprinting pushes it *further* (riskier, keepable). First touch traps a
  moving ball to your feet with an error that scales with pace and effort.
- **Kicking** — passes, through balls, lofted balls, chips and shots share one
  model. Power scales release speed; striking across your body adds spin;
  accuracy scatters with power and fatigue, so close-range finishing is a skill.
- **Goalkeepers** (`ai/director.ts`) — position on the ball–goal bisector to
  narrow the angle, rush out to smother a one-on-one, dive to a predicted
  interception point, then **catch** (slow shots) or **parry** (fast ones) and
  distribute. A gathered ball is shielded for a beat so opponents back off.
- **Defending** — pressing costs stamina; standing tackles poke the ball to your
  feet; slide tackles cover ground but leave you grounded and exposed.
- **Rules** — kickoffs, goals with post/crossbar collisions, throw-ins, corners
  and goal kicks, two halves and a clock, live match stats (possession, shots on
  target, saves, tackles, pass completion).

## Opponent & teammate AI

Human and AI feed the **identical** `Command` shape into the simulation — it
never distinguishes them. Per team, the player nearest the ball becomes the
chaser (press / carry); everyone else gets positional roles that slide with the
ball — attackers make runs and offer options, defenders man-mark goal-side. The
ball carrier shoots on sight in range, plays purposeful forward passes, or drives
at goal. Verified balanced in headless AI-vs-AI runs (add `?ai` to the URL).

---

## Architecture

```
src/game/
  core/      vec, math (+ seeded RNG), input manager, timing
  physics/   the ball (3D + spin) integrator
  entities/  the player (momentum, stamina, tackling)
  ai/        team director: goalkeeper, carrier, presser, off-ball
  match/     field geometry, formations, the World simulation + rules
  control/   maps raw input → a Command (charge, aim, loft modifier)
  render/    camera (world↔screen, follow/zoom) + canvas renderer
  ui/        HUD (scoreboard, stamina, power meter, minimap) + DOM screens
  config.ts  every tunable number
  main.ts    bootstrap + fixed-step game loop
```

The simulation is **fixed-step** (120 Hz) with a phase state machine
(kickoff → playing → goal → half-time → full-time) layered on top, decoupled
from the render frame rate.

---

## Roadmap

The design document's later phases map onto this foundation:

- **Playable now** — core physics engine, match vs AI, free-play/training,
  goalkeeper mechanics, camera zoom modes, a live stats HUD, and custom match
  settings (team size 3–6, half length, single-keeper mode).
- **Next** — replay capture, more set-piece detail (free-kick walls), weather &
  surface effects on ball physics, cosmetic customization, day/night pitches.
- **Later** — online play (rollback netcode), ranked matchmaking & seasons,
  tournaments, and the 3D / first-person target.

Competitive integrity is baked in from day one: **identical attributes for every
player** — skill decides, not stats. No loot boxes, no power creep.
