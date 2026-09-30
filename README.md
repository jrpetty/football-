# ⚽ Open Pitch — Physics Football

A **physics-driven, input-driven** browser football game, inspired by *Pro Soccer
Online*. Your decisions and skill are the only thing that matters — there are no
animation-locked outcomes and no pay-to-win. Every dribble, pass and shot is a
real-time physics calculation: the ball has **spin, curve, height, bounce and
momentum**, and players carry momentum too.

It runs entirely in the browser — no install, no account, no server. Drop into
free-play **training** on your own, or **play online** with friends (peer to peer,
or through a small relay), in either a **3D first/third-person view** or a **2D
top-down view**. There is no AI: every shirt is a seat, and the ones nobody has
taken just stand there.

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

## Looking and feel

The 3D game is a **night match under floodlights**: a raked stadium with a lit
crowd (home end blue, away end red), LED hoardings, floodlight masts, a striped
and scuffed pitch, a ball painted as a real truncated icosahedron so you can *see*
it spin, and players with their own faces — skin, hair and build come from the
shirt's id, so every screen in an online match agrees about who is who. Turf
flies off a strike, a hard-hit ball leaves a streak, the woodwork throws sparks,
a goal fires a confetti cannon, and the camera opens as you sprint and shakes for
goals and hard contact. All of it is presentation: none of it can change what a
shot does.

The title screen is the real stadium at the moment before kick-off, with a camera
drifting round the ball. Everything you can change lives in the **settings
drawer** (title screen, or *Settings* on the pause screen), and takes effect the
moment you close it — even mid-match:

- **How to play** — every move on the mouse, with its button lit, and the rest of
  the keys.
- **Controls** — kick height and curve sensitivity, mouse-look sensitivity,
  invert-Y, and every key (click one and press the one you want).
- **Display** — field of view, camera distance, HUD size, frame-rate readout.
- **Audio** — volume and mute.
- **Match** — position, team size, half length and keepers for a match you host.

Everything is remembered between visits.

New here? **Learn the touch** (on the title screen, and under *How to play*) is a
nine-step lesson in the real game — look, run, touch, strike, shape it, jump,
shield, slide, score. Each step ends when the world says the thing happened, not
when you have read the text, so it cannot be passed by clicking at nothing.
**Enter** skips a step.

In training, charging a strike draws a **dotted line** from the ball to where
it would come down — your flick included. It is the simulation's own answer, not
an estimate: the strike and the preview share one piece of code
(`World.strikePlan`), and a test holds the line to within a centimetre of the
ball's real track. Switch it off under *Display* to learn the flick by feel.

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

**Dribbling is manual, and it is all clicks.** The ball is never glued to your
feet, and it is never pushed by your body either: run straight through a ball and
it does not move. The only things that put pace on it are your two buttons (and a
slide tackle). A body can only ever take pace *off* the ball — it is a wall the
ball bounces off, never a paddle that hits it — so a defender still blocks a pass
but you cannot dribble by running into it. You knock it forward with right-click
touches and run onto it, so close control is a skill rather than a state. The
power bar and a live LIFTED / DRIVEN / CURVE readout show what your flick is
about to do before you commit.

**Training never restarts.** Score as many as you like. There is no goal replay and
no reset: the net holds the ball for a beat and rolls it back out through the
mouth, and you go and get it wherever you are standing. `R` replays whatever you
want, whenever you want it.

### You are one player

You pick a position on the menu — **striker, midfielder, defender or keeper** —
and you are that single footballer for the whole match. Control never jumps to
whoever happens to be nearest the ball: if you're out of position, your team is
out a player, so positioning and off-ball movement matter as much as anything
you do on it. Your team-mates and both keepers are AI.

In 3D, **click the pitch** to capture the mouse for looking around; **Esc**
releases it and pauses.

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
  ui/        HUD (scoreboard, stamina, power meter, minimap, ball pointer),
             DOM screens + settings drawer, the title screen's live backdrop, fonts
  render3d/fx  pooled particles (turf, dust, sparks, ball trail, confetti)
  match/tutorial  the guided first lesson
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

- **Playable now** — core physics engine, online matches, free-play/training
  with unlimited goals and a guided lesson, goalkeeper mechanics, **both a 3D (first/third-person) and a 2D top-down
  view**, a live stats HUD, and custom match settings (team size 3–6, half
  length, single-keeper mode).
- **Next** — more set-piece detail, weather & surface effects on ball physics,
  cosmetic customization, more drills.
- **Later** — online play (rollback netcode), ranked matchmaking & seasons,
  tournaments.

Competitive integrity is baked in from day one: **identical attributes for every
player** — skill decides, not stats. No loot boxes, no power creep.
