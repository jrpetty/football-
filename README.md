# ⚽ Open Pitch — Physics Football

A **physics-driven, input-driven** browser football game, inspired by *Pro Soccer
Online*. Your decisions and skill are the only thing that matters — there are no
animation-locked outcomes and no pay-to-win. Every dribble, pass and shot is a
real-time physics calculation: the ball has **spin, curve, height, bounce and
momentum**, and players carry momentum too.

It runs entirely in the browser — no install, no account, no server. Play a
match against the AI, or drop into free-play training, in either a **3D
first/third-person view** or a **2D top-down view**.

> **Note on scope.** The original design targets Unreal Engine 5. That can't run
> or be verified in a browser sandbox, so this is a complete, genuinely playable
> web realisation of the same *philosophy* and *mechanics* — a real ball-physics
> engine and input-driven control. Crucially the simulation is **view-agnostic**:
> the same physics/AI/rules `World` drives both a WebGL 3D presentation
> (first/third-person, à la Pro Soccer Online) and a top-down 2D one. You choose
> the view in the menu; toggle first/third-person in-game with **V**.

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

Vanilla TypeScript, no UI framework. The simulation, 2D renderer and HUD are
dependency-free; the 3D view uses **Three.js** for WebGL rendering.

---

## Controls

Two mouse buttons do all the ball work, in the style of *Pro Soccer Online*:

| Action | Input |
| --- | --- |
| Move | **W A S D** (or arrows) — camera-relative in 3D |
| Sprint | hold **Shift** (drains stamina) |
| Aim / Look | **Mouse** — the direction you point is where the ball goes |
| **Touch** — close control | **Right click**: tap for a small nudge, hold to push it further |
| Touch to the side / back | **A D S** + right click |
| **Strike** — pass or shot | **Left click**: hold longer for more power |
| Tackle · Slide | **F** · **C** |
| Switch player | **Q** |
| View | **V** — 3D: first ⇄ third person · 2D: zoom TV → follow → close |
| Pause | **Esc** / **P** |
| Spawn a ball (free play) | **B** |

### Height and curve come from your mouse

There is no loft button. While a strike is charging, **flick the mouse** in the
final moments before you release:

- **Flick up** → the ball lifts. A full flick launches a genuine lofted ball
  (~14 m at the apex); a small one clips it just off the deck.
- **Flick down** → you drive it, keeping it low and hard along the ground.
- **Flick sideways** → the ball bends the way you dragged, several metres across
  its flight.
- **Flick diagonally** → curve *and* lift together.

Two settings on the menu scale this, mirroring the ones PSO exposes: **Kick
height sensitivity** and **Kick curve sensitivity**. Over-flick and you'll skin
it — that risk is the point.

**Dribbling is manual.** The ball is never glued to your feet: you knock it
forward with touches and run onto it, so close control is a skill rather than a
state. The power bar and a live LIFTED / DRIVEN / CURVE readout show what your
flick is about to do before you commit.

In 3D, **click the pitch** to capture the mouse for looking around; **Esc**
releases it and pauses. Your player **auto-switches** to whoever is nearest the
ball.

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
- **Kicking** (`control/strike.ts`) — one continuous model: hold time sets
  power, the late mouse flick sets loft and spin. Accuracy scatters with power
  and fatigue, so a full-blooded strike is genuinely harder to place than a
  measured one. The strike direction is sampled from *before* the flick, so
  bending a ball doesn't also throw your aim off.
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
  core/      vec, math (+ seeded RNG), input manager (+ pointer lock), timing
  physics/   the ball (3D + spin) integrator
  entities/  the player (momentum, stamina, tackling)
  ai/        team director: goalkeeper, carrier, presser, off-ball
  match/     field geometry, formations, the World simulation + rules
  control/   input → Command: human.ts (2D, mouse-aim) · human3d.ts (mouse-look)
  render/    2D top-down: camera (world↔screen, follow/zoom) + canvas renderer
  render3d/  3D: Three.js scene builder + first/third-person camera
  ui/        HUD (scoreboard, stamina, power meter, minimap) + DOM screens
  config.ts  every tunable number
  main.ts    bootstrap; picks the 2D Game or the 3D Game3D by the chosen view
  game3d.ts  the 3D game loop (WebGL scene + HUD overlay + pointer lock)
```

The simulation is **fixed-step** (120 Hz) with a phase state machine
(kickoff → playing → goal → half-time → full-time), fully decoupled from
presentation. Because both views feed the identical `Command` into the same
`World`, choosing 2D or 3D only swaps the renderer, camera and controller —
the game itself is one codebase.

---

## Roadmap

The design document's later phases map onto this foundation:

- **Playable now** — core physics engine, match vs AI, free-play/training,
  goalkeeper mechanics, **both a 3D (first/third-person) and a 2D top-down
  view**, a live stats HUD, and custom match settings (team size 3–6, half
  length, single-keeper mode).
- **Next** — nicer 3D player models & animation, replay capture, more set-piece
  detail (free-kick walls), weather & surface effects on ball physics, cosmetic
  customization, day/night pitches.
- **Later** — online play (rollback netcode), ranked matchmaking & seasons,
  tournaments.

Competitive integrity is baked in from day one: **identical attributes for every
player** — skill decides, not stats. No loot boxes, no power creep.
